package com.example.vcam;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 在 app 内把视频真转码旋转（MediaCodec 解码 -> GL 旋转 -> MediaCodec 重编码 -> MP4）。
 * 等价于 ffmpeg transpose，但不依赖任何外部二进制。0 度直接拷贝。
 * 结构参考 bigflake DecodeEditEncodeTest（Android CTS 同源），旋转叠加在顶点 MVP 上。
 */
public class VideoRotator {

    private static final long TIMEOUT_US = 10000;

    public interface Progress { void onProgress(String msg); }

    /** rotationDeg: 0/90/180/270（GL 逆时针）。真转像素后写到 outPath。 */
    public static void rotate(String inPath, String outPath, int rotationDeg, Progress p) throws Exception {
        rotationDeg = ((rotationDeg % 360) + 360) % 360;
        if (rotationDeg == 0) { copyFile(new File(inPath), new File(outPath)); return; }
        new VideoRotator().doRotate(inPath, outPath, rotationDeg, p);
    }

    private MediaExtractor extractor;
    private MediaCodec decoder, encoder;
    private MediaMuxer muxer;
    private InputSurface inputSurface;
    private OutputSurface outputSurface;
    private int muxTrack = -1;
    private int audioMuxTrack = -1;
    private boolean muxStarted = false;
    private MediaExtractor audioExtractor;

    private void doRotate(String inPath, String outPath, int rot, Progress p) throws Exception {
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inPath);
            int track = selectVideoTrack(extractor);
            if (track < 0) throw new RuntimeException("视频里找不到视频轨");
            extractor.selectTrack(track);
            MediaFormat inFormat = extractor.getTrackFormat(track);
            int inW = inFormat.getInteger(MediaFormat.KEY_WIDTH);
            int inH = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int metaRot = 0;
            try { if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) metaRot = inFormat.getInteger(MediaFormat.KEY_ROTATION); } catch (Exception ignored) {}
            int totalRot = ((rot + metaRot) % 360 + 360) % 360;
            boolean swap = (totalRot == 90 || totalRot == 270);
            int outW = swap ? inH : inW;
            int outH = swap ? inW : inH;
            // H264 编码器对 >1080p 常超 level 上限、大视频 GL 转码又慢又耗内存：统一把长边缩到 ≤1920
            int maxDim = 1920;
            if (Math.max(outW, outH) > maxDim) {
                float sc = maxDim / (float) Math.max(outW, outH);
                outW = Math.round(outW * sc);
                outH = Math.round(outH * sc);
            }
            outW &= ~1;
            outH &= ~1;
            Log.i("VCAM-rot", "in " + inW + "x" + inH + " meta" + metaRot + " rot" + rot + " -> out " + outW + "x" + outH);
            int frameRate = 30;
            try { if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) frameRate = inFormat.getInteger(MediaFormat.KEY_FRAME_RATE); } catch (Exception ignored) {}
            if (frameRate <= 0) frameRate = 30;
            String inMime = inFormat.getString(MediaFormat.KEY_MIME);

            MediaFormat outFormat = MediaFormat.createVideoFormat("video/avc", outW, outH);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(4_000_000, outW * outH * 3));
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new InputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            outputSurface = new OutputSurface();
            outputSurface.setRotation(totalRot);
            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inFormat, outputSurface.getSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupAudio(inPath);

            transcode(p);
            muxAudio();
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (muxer != null) { if (muxStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            try { if (outputSurface != null) outputSurface.release(); } catch (Exception ignored) {}
            try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
        }
    }

    /** 选音频轨、addTrack（不解码，转码完直接 remux 到输出）。 */
    private void setupAudio(String inPath) {
        try {
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(inPath);
            int at = -1;
            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                String mime = audioExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { at = i; break; }
            }
            if (at < 0) { audioExtractor.release(); audioExtractor = null; return; }
            audioExtractor.selectTrack(at);
            audioMuxTrack = muxer.addTrack(audioExtractor.getTrackFormat(at));
        } catch (Exception e) {
            if (audioExtractor != null) { try { audioExtractor.release(); } catch (Exception ignored) {} }
            audioExtractor = null;
            audioMuxTrack = -1;
        }
    }

    private void muxAudio() {
        if (audioExtractor == null || audioMuxTrack < 0 || !muxStarted) return;
        ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int size = audioExtractor.readSampleData(buf, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = audioExtractor.getSampleTime();
                info.flags = 0;
                muxer.writeSampleData(audioMuxTrack, buf, info);
                audioExtractor.advance();
            }
        } catch (Exception ignored) {}
    }

    private void transcode(Progress p) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, decoderDone = false, outputDone = false;
        int frames = 0;
        while (!outputDone) {
            if (!inputDone) {
                int inId = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inId >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inId);
                    int size = extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(inId, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            boolean decoderOutputAvailable = !decoderDone;
            boolean encoderOutputAvailable = true;
            while (decoderOutputAvailable || encoderOutputAvailable) {
                // 先清空 encoder 输出，写进 muxer
                int encStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    encoderOutputAvailable = false;
                } else if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new RuntimeException("encoder 输出格式变了两次");
                    muxTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (encStatus >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(encStatus);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size != 0 && muxStarted) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(muxTrack, data, info);
                    }
                    boolean encEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encStatus, false);
                    if (encEos) { outputDone = true; break; }
                }
                if (encStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue; // 先把 encoder 榨干

                // 再从 decoder 取一帧，画到 encoder 的输入 surface
                if (!decoderDone) {
                    int decStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (decStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decoderOutputAvailable = false;
                    } else if (decStatus >= 0) {
                        boolean render = info.size != 0;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoder.releaseOutputBuffer(decStatus, false);
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        } else {
                            decoder.releaseOutputBuffer(decStatus, render);
                            if (render) {
                                outputSurface.awaitNewImage();
                                outputSurface.drawImage();
                                inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                                inputSurface.swapBuffers();
                                frames++;
                                if (p != null && frames % 30 == 0) p.onProgress("转码中… 已处理 " + frames + " 帧");
                            }
                        }
                    }
                }
            }
        }
    }

    private static int selectVideoTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close(); in.close();
    }

    // ============ encoder 端：EGL 包裹 encoder 的 input surface ============
    static class InputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private EGLDisplay mEGLDisplay;
        private EGLContext mEGLContext;
        private EGLSurface mEGLSurface;
        private Surface mSurface;

        InputSurface(Surface surface) {
            mSurface = surface;
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1);
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
            int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface, surfaceAttribs, 0);
        }
        void makeCurrent() { EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext); }
        boolean swapBuffers() { return EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface); }
        void setPresentationTime(long nsecs) { EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs); }
        void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mSurface.release();
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    // ============ decoder 端：SurfaceTexture 承接解码帧，再由 GL 画出去 ============
    static class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private TextureRender mRender;
        private final Object mLock = new Object();
        private boolean mFrameAvailable;
        private int mRotation = 0;
        private HandlerThread mCbThread;

        OutputSurface() {
            mRender = new TextureRender();
            mRender.surfaceCreated();
            mSurfaceTexture = new SurfaceTexture(mRender.getTextureId());
            mCbThread = new HandlerThread("vr-cb");
            mCbThread.start();
            mSurfaceTexture.setOnFrameAvailableListener(this, new Handler(mCbThread.getLooper()));
            mSurface = new Surface(mSurfaceTexture);
        }
        void setRotation(int r) { mRotation = r; }
        Surface getSurface() { return mSurface; }
        void awaitNewImage() {
            synchronized (mLock) {
                while (!mFrameAvailable) {
                    try {
                        mLock.wait(2500);
                        if (!mFrameAvailable) throw new RuntimeException("等待解码帧超时");
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                mFrameAvailable = false;
            }
            mSurfaceTexture.updateTexImage();
        }
        void drawImage() { mRender.drawFrame(mSurfaceTexture, mRotation); }
        @Override public void onFrameAvailable(SurfaceTexture st) {
            synchronized (mLock) { mFrameAvailable = true; mLock.notifyAll(); }
        }
        void release() {
            if (mCbThread != null) mCbThread.quit();
            if (mSurface != null) mSurface.release();
            if (mSurfaceTexture != null) mSurfaceTexture.release();
        }
    }

    // ============ OES 外部纹理 -> 全屏四边形，MVP 上叠加旋转 ============
    static class TextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int STRIDE = 5 * FLOAT_SIZE_BYTES;
        private static final int POS_OFFSET = 0;
        private static final int UV_OFFSET = 3;
        private final float[] mVerts = {
                -1.0f, -1.0f, 0, 0.f, 0.f,
                 1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f,  1.0f, 0, 0.f, 1.f,
                 1.0f,  1.0f, 0, 1.f, 1.f,
        };
        private FloatBuffer mVertBuf;
        private final float[] mMVP = new float[16];
        private final float[] mST = new float[16];
        private int mProgram, mTextureID = -1;
        private int muMVP, muST, maPos, maTex;

        private static final String VS =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";
        private static final String FS =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";

        TextureRender() {
            mVertBuf = ByteBuffer.allocateDirect(mVerts.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mVertBuf.put(mVerts).position(0);
            Matrix.setIdentityM(mST, 0);
        }
        int getTextureId() { return mTextureID; }

        void drawFrame(SurfaceTexture st, int rotation) {
            st.getTransformMatrix(mST);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(mProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            mVertBuf.position(POS_OFFSET);
            GLES20.glVertexAttribPointer(maPos, 3, GLES20.GL_FLOAT, false, STRIDE, mVertBuf);
            GLES20.glEnableVertexAttribArray(maPos);
            mVertBuf.position(UV_OFFSET);
            GLES20.glVertexAttribPointer(maTex, 2, GLES20.GL_FLOAT, false, STRIDE, mVertBuf);
            GLES20.glEnableVertexAttribArray(maTex);
            Matrix.setIdentityM(mMVP, 0);
            Matrix.rotateM(mMVP, 0, rotation, 0f, 0f, 1f);
            GLES20.glUniformMatrix4fv(muMVP, 1, false, mMVP, 0);
            GLES20.glUniformMatrix4fv(muST, 1, false, mST, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glFinish();
        }

        void surfaceCreated() {
            mProgram = createProgram(VS, FS);
            maPos = GLES20.glGetAttribLocation(mProgram, "aPosition");
            maTex = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            muMVP = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            muST = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            mTextureID = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private int createProgram(String vs, String fs) {
            int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
            int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, v);
            GLES20.glAttachShader(program, f);
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new RuntimeException("GL program 链接失败: " + log);
            }
            return program;
        }
        private int loadShader(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new RuntimeException("shader 编译失败: " + log);
            }
            return shader;
        }
    }
}
