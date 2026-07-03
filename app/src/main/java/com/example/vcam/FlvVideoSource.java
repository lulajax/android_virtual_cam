package com.example.vcam;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

/**
 * 直播流视频源：HTTP-FLV 拉流 → 手写 FLV demux → 提取 H264(SPS/PPS + NALU) → MediaCodec 解码到目标 App 的相机 Surface。
 * 不依赖 ExoPlayer/ffmpeg。只处理 AVC(codecId=7) 视频，音频 tag 直接丢弃。
 * 直播实时流：不循环、不 seek，跟随网络速率解码；断流即结束（外层可重启）。
 */
public class FlvVideoSource implements Runnable {

    private volatile boolean stop = false;
    private Thread thread;
    private String url;
    private Surface surface;
    private MediaCodec decoder;
    private boolean decoderStarted = false;
    private int rotation = 0;
    private VideoRotator.InputSurface inputSurface;
    private VideoRotator.OutputSurface outputSurface;
    private boolean audioEnabled = false;
    private MediaCodec audioDecoder;
    private android.media.AudioTrack audioTrack;
    private boolean audioStarted = false;

    public void setSurface(Surface s) { this.surface = s; }

    public void setRotation(int r) { this.rotation = ((r % 360) + 360) % 360; }

    public void setAudioEnabled(boolean e) { this.audioEnabled = e; }

    public void start(String url) {
        this.url = url;
        if (thread == null) {
            thread = new Thread(this, "flv-live");
            thread.start();
        }
    }

    public void stopDecode() {
        stop = true;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.connect();
            Log.i("VCAM-flv","【VCAM】【flv】已连接 " + conn.getResponseCode());
            DataInputStream in = new DataInputStream(new BufferedInputStream(conn.getInputStream(), 1 << 16));
            demux(in);
        } catch (Throwable t) {
            Log.i("VCAM-flv","【VCAM】【flv】结束/异常：" + t);
        } finally {
            releaseDecoder();
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void demux(DataInputStream in) throws Exception {
        // FLV header: 'F''L''V' ver flags dataOffset(4)
        byte[] sig = new byte[3];
        in.readFully(sig);
        if (sig[0] != 'F' || sig[1] != 'L' || sig[2] != 'V') {
            throw new RuntimeException("不是 FLV 流");
        }
        in.readUnsignedByte();                 // version
        in.readUnsignedByte();                 // flags
        int dataOffset = in.readInt();         // header size (通常 9)
        int skip = dataOffset - 9;
        while (skip-- > 0) in.readUnsignedByte();
        in.readInt();                          // PreviousTagSize0

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stop) {
            int tagType = in.readUnsignedByte();
            int dataSize = readUInt24(in);
            int ts = readUInt24(in);
            int tsExt = in.readUnsignedByte();
            long timestampMs = (((long) (tsExt & 0xff)) << 24) | (ts & 0xffffff);
            readUInt24(in);                    // stream id (always 0)

            byte[] data = new byte[dataSize];
            in.readFully(data);
            in.readInt();                      // PreviousTagSize

            if (tagType == 9 && dataSize >= 5) {        // video tag
                int b0 = data[0] & 0xff;
                int codecId = b0 & 0x0f;                // 7 = AVC
                if (codecId != 7) continue;
                int avcPacketType = data[1] & 0xff;     // 0=seq header, 1=NALU
                if (avcPacketType == 0) {
                    configureFromAvcConfig(data, 5);
                } else if (avcPacketType == 1 && decoderStarted) {
                    byte[] annexb = avccToAnnexB(data, 5, dataSize - 5);
                    if (annexb != null) {
                        feedFrame(annexb, timestampMs * 1000);
                        drain(info);
                    }
                }
            } else if (tagType == 8 && dataSize >= 2 && audioEnabled) {   // audio tag（AAC）
                int soundFormat = (data[0] >> 4) & 0x0f;                  // 10 = AAC
                if (soundFormat == 10) {
                    int aacPacketType = data[1] & 0xff;                   // 0=seq header, 1=raw
                    if (aacPacketType == 0) {
                        configureAudioDecoder(data, 2, dataSize - 2);
                    } else if (aacPacketType == 1 && audioStarted) {
                        feedAudio(data, 2, dataSize - 2, timestampMs * 1000);
                        drainAudio();
                    }
                }
            }
            // 脚本 tag 忽略
        }
    }

    /** 从 AudioSpecificConfig 配置 AAC decoder + AudioTrack。 */
    private void configureAudioDecoder(byte[] d, int off, int len) {
        try {
            int b0 = d[off] & 0xff, b1 = d[off + 1] & 0xff;
            int freqIdx = ((b0 & 0x07) << 1) | ((b1 >> 7) & 0x01);
            int chanCfg = (b1 >> 3) & 0x0f;
            int[] freqTable = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
            int sampleRate = (freqIdx >= 0 && freqIdx < freqTable.length) ? freqTable[freqIdx] : 44100;
            int channels = (chanCfg >= 1 && chanCfg <= 2) ? chanCfg : 2;

            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
            byte[] csd = new byte[len];
            System.arraycopy(d, off, csd, 0, len);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            audioDecoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioDecoder.configure(fmt, null, null, 0);
            audioDecoder.start();

            int chanOut = channels == 1 ? android.media.AudioFormat.CHANNEL_OUT_MONO : android.media.AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = android.media.AudioTrack.getMinBufferSize(sampleRate, chanOut, android.media.AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, sampleRate, chanOut,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, sampleRate), android.media.AudioTrack.MODE_STREAM);
            audioTrack.play();
            audioStarted = true;
            Log.i("VCAM-flv", "【VCAM】【flv】音频就绪 " + sampleRate + "Hz " + channels + "ch");
        } catch (Throwable t) {
            Log.i("VCAM-flv", "【VCAM】【flv】音频配置失败：" + t);
        }
    }

    private void feedAudio(byte[] d, int off, int len, long ptsUs) {
        if (audioDecoder == null) return;
        int id = audioDecoder.dequeueInputBuffer(0);
        if (id >= 0) {
            ByteBuffer buf = audioDecoder.getInputBuffer(id);
            buf.clear();
            buf.put(d, off, len);
            audioDecoder.queueInputBuffer(id, 0, len, ptsUs, 0);
        }
    }

    private void drainAudio() {
        if (audioDecoder == null || audioTrack == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int id = audioDecoder.dequeueOutputBuffer(info, 0);
            if (id < 0) break;
            ByteBuffer out = audioDecoder.getOutputBuffer(id);
            if (info.size > 0 && out != null) {
                byte[] pcm = new byte[info.size];
                out.position(info.offset);
                out.get(pcm, 0, info.size);
                audioTrack.write(pcm, 0, pcm.length);
            }
            audioDecoder.releaseOutputBuffer(id, false);
        }
    }

    /** 解析 AVCDecoderConfigurationRecord，拿 SPS/PPS，配置并启动 decoder。 */
    private void configureFromAvcConfig(byte[] d, int off) {
        try {
            // off: configurationVersion(1) profile(1) compat(1) level(1) [6bits res|2bits lenSizeMinus1](1) [3bits res|5bits numSPS](1)
            int p = off + 4;
            int lengthSize = (d[p] & 0x03) + 1;   // NAL length prefix size (通常 4)
            p += 1;
            int numSps = d[p] & 0x1f;
            p += 1;
            List<byte[]> spsList = new ArrayList<>();
            for (int i = 0; i < numSps; i++) {
                int len = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
                p += 2;
                spsList.add(slice(d, p, len));
                p += len;
            }
            int numPps = d[p] & 0xff;
            p += 1;
            List<byte[]> ppsList = new ArrayList<>();
            for (int i = 0; i < numPps; i++) {
                int len = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
                p += 2;
                ppsList.add(slice(d, p, len));
                p += len;
            }
            if (spsList.isEmpty() || ppsList.isEmpty()) {
                Log.i("VCAM-flv","【VCAM】【flv】SPS/PPS 缺失");
                return;
            }
            byte[] sps = spsList.get(0);
            byte[] pps = ppsList.get(0);
            int[] wh = parseSpsSize(sps);
            int width = wh[0] > 0 ? wh[0] : 1080;
            int height = wh[1] > 0 ? wh[1] : 1920;

            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));

            releaseDecoder();
            decoder = MediaCodec.createDecoderByType("video/avc");
            if (rotation != 0) {
                // 解码到中间 SurfaceTexture，再用 GL 旋转画到目标 surface
                inputSurface = new VideoRotator.InputSurface(surface);
                inputSurface.makeCurrent();
                outputSurface = new VideoRotator.OutputSurface();
                outputSurface.setRotation(rotation);
                decoder.configure(fmt, outputSurface.getSurface(), null, 0);
            } else {
                decoder.configure(fmt, surface, null, 0);
            }
            decoder.start();
            decoderStarted = true;
            Log.i("VCAM-flv","【VCAM】【flv】decoder 就绪 " + width + "x" + height + " lenSize=" + lengthSize);
        } catch (Throwable t) {
            Log.i("VCAM-flv","【VCAM】【flv】配置 decoder 失败：" + t);
        }
    }

    /** AVCC(4字节长度前缀 NALU，可能多个) → Annex-B(00 00 00 01 前缀)。 */
    private byte[] avccToAnnexB(byte[] d, int off, int len) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(len + 16);
        int p = off;
        int end = off + len;
        try {
            while (p + 4 <= end) {
                int nalLen = ((d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16)
                        | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
                p += 4;
                if (nalLen <= 0 || p + nalLen > end) break;
                out.write(0); out.write(0); out.write(0); out.write(1);
                out.write(d, p, nalLen);
                p += nalLen;
            }
        } catch (Exception e) {
            return null;
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    private void feedFrame(byte[] annexb, long ptsUs) {
        if (decoder == null) return;
        int id = decoder.dequeueInputBuffer(10000);
        if (id >= 0) {
            ByteBuffer buf = decoder.getInputBuffer(id);
            buf.clear();
            buf.put(annexb);
            decoder.queueInputBuffer(id, 0, annexb.length, ptsUs, 0);
        }
    }

    private void drain(MediaCodec.BufferInfo info) {
        if (decoder == null) return;
        while (true) {
            int id = decoder.dequeueOutputBuffer(info, 0);
            if (id >= 0) {
                if (rotation != 0 && outputSurface != null && inputSurface != null) {
                    decoder.releaseOutputBuffer(id, true);   // render 到中间 SurfaceTexture
                    try {
                        outputSurface.awaitNewImage();
                        outputSurface.drawImage();           // GL 旋转
                        inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                        inputSurface.swapBuffers();          // 推到目标 surface
                    } catch (Throwable t) {
                        Log.i("VCAM-flv", "【VCAM】【flv】GL 旋转异常：" + t);
                    }
                } else {
                    decoder.releaseOutputBuffer(id, true);   // 直接 render 到目标 surface
                }
            } else {
                break;   // INFO_TRY_AGAIN_LATER / 格式变更等，直接退出本次
            }
        }
    }

    private void releaseDecoder() {
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        if (outputSurface != null) { try { outputSurface.release(); } catch (Exception ignored) {} outputSurface = null; }
        if (inputSurface != null) { try { inputSurface.release(); } catch (Exception ignored) {} inputSurface = null; }
        if (audioTrack != null) { try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {} audioTrack = null; }
        if (audioDecoder != null) { try { audioDecoder.stop(); audioDecoder.release(); } catch (Exception ignored) {} audioDecoder = null; }
        audioStarted = false;
        decoderStarted = false;
    }

    private static int readUInt24(DataInputStream in) throws Exception {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte(), c = in.readUnsignedByte();
        return (a << 16) | (b << 8) | c;
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(src, off, r, 0, len);
        return r;
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] r = new byte[nal.length + 4];
        r[0] = 0; r[1] = 0; r[2] = 0; r[3] = 1;
        System.arraycopy(nal, 0, r, 4, nal.length);
        return r;
    }

    /** 极简 SPS 解析，取 width/height（够用即可，失败返回 0）。 */
    private static int[] parseSpsSize(byte[] sps) {
        try {
            // 跳过 NAL header(1 byte)，对 RBSP 做 emulation-prevention 去除后按 SPS 语法读
            byte[] rbsp = ebspToRbsp(sps, 1);
            BitReader br = new BitReader(rbsp);
            int profileIdc = br.u(8);
            br.u(8);                    // constraint flags + reserved
            br.u(8);                    // level_idc
            br.ue();                    // seq_parameter_set_id
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                    || profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134) {
                int chromaFormatIdc = br.ue();
                if (chromaFormatIdc == 3) br.u(1);
                br.ue();                // bit_depth_luma_minus8
                br.ue();                // bit_depth_chroma_minus8
                br.u(1);                // qpprime_y_zero_transform_bypass
                if (br.u(1) == 1) {     // seq_scaling_matrix_present
                    for (int i = 0; i < 8; i++) if (br.u(1) == 1) skipScalingList(br, i < 6 ? 16 : 64);
                }
            }
            br.ue();                    // log2_max_frame_num_minus4
            int picOrderCntType = br.ue();
            if (picOrderCntType == 0) {
                br.ue();                // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                br.u(1);
                br.se(); br.se();
                int n = br.ue();
                for (int i = 0; i < n; i++) br.se();
            }
            br.ue();                    // max_num_ref_frames
            br.u(1);                    // gaps_in_frame_num_value_allowed
            int picWidthInMbsMinus1 = br.ue();
            int picHeightInMapUnitsMinus1 = br.ue();
            int frameMbsOnly = br.u(1);
            int width = (picWidthInMbsMinus1 + 1) * 16;
            int height = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnly);
            return new int[]{width, height};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private static void skipScalingList(BitReader br, int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                int delta = br.se();
                next = (last + delta + 256) % 256;
            }
            last = (next == 0) ? last : next;
        }
    }

    private static byte[] ebspToRbsp(byte[] d, int off) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(d.length);
        int zeros = 0;
        for (int i = off; i < d.length; i++) {
            int b = d[i] & 0xff;
            if (zeros >= 2 && b == 3) { zeros = 0; continue; }   // 去掉 emulation byte
            out.write(b);
            if (b == 0) zeros++; else zeros = 0;
        }
        return out.toByteArray();
    }

    /** 简易比特读取器，支持 u(n)/ue/se。 */
    private static class BitReader {
        private final byte[] d;
        private int bytePos = 0, bitPos = 0;
        BitReader(byte[] d) { this.d = d; }
        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v <<= 1;
                if ((d[bytePos] & (0x80 >> bitPos)) != 0) v |= 1;
                bitPos++;
                if (bitPos == 8) { bitPos = 0; bytePos++; }
            }
            return v;
        }
        int ue() {
            int zeros = 0;
            while (u(1) == 0) zeros++;
            int v = 0;
            for (int i = 0; i < zeros; i++) v = (v << 1) | u(1);
            return v + (1 << zeros) - 1;
        }
        int se() {
            int k = ue();
            int sign = (k & 1) == 1 ? 1 : -1;
            return sign * ((k + 1) / 2);
        }
    }
}
