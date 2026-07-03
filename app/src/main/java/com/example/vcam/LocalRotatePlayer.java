package com.example.vcam;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

/**
 * 本地视频实时旋转喂帧：MediaPlayer 负责解码/循环/声音，外套一层 GL 把每帧旋转后画到目标 surface。
 * 不预转码——push 时只拷原始视频，旋转在这里实时完成。复用 VideoRotator 的 EGL/纹理部件。
 */
public class LocalRotatePlayer implements SurfaceTexture.OnFrameAvailableListener {

    private HandlerThread glThread;
    private Handler glHandler;
    private MediaPlayer player;
    private VideoRotator.InputSurface inputSurface;
    private VideoRotator.TextureRender render;
    private SurfaceTexture srcTexture;
    private Surface srcSurface;
    private volatile boolean released = false;
    private int rotation;

    public void start(final String path, final Surface target, int rot, final boolean audioOn) {
        this.rotation = ((rot % 360) + 360) % 360;
        glThread = new HandlerThread("local-rot-gl");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        glHandler.post(new Runnable() {
            public void run() {
                try {
                    inputSurface = new VideoRotator.InputSurface(target);
                    inputSurface.makeCurrent();
                    render = new VideoRotator.TextureRender();
                    render.surfaceCreated();
                    srcTexture = new SurfaceTexture(render.getTextureId());
                    srcTexture.setOnFrameAvailableListener(LocalRotatePlayer.this, glHandler);
                    srcSurface = new Surface(srcTexture);
                    player = new MediaPlayer();
                    player.setSurface(srcSurface);
                    player.setLooping(true);
                    float v = audioOn ? 1f : 0f;
                    player.setVolume(v, v);
                    player.setDataSource(path);
                    player.prepare();
                    player.start();
                    Log.i("VCAM-local", "【VCAM】【local】实时旋转 " + rotation + "° " + path);
                } catch (Throwable t) {
                    Log.i("VCAM-local", "【VCAM】【local】启动失败：" + t);
                }
            }
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // 在 glThread 上触发
        if (released) return;
        try {
            srcTexture.updateTexImage();
            render.drawFrame(srcTexture, rotation);
            inputSurface.swapBuffers();
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        released = true;
        if (glHandler != null) {
            glHandler.post(new Runnable() {
                public void run() {
                    try { if (player != null) { player.stop(); player.release(); player = null; } } catch (Exception ignored) {}
                    try { if (srcSurface != null) srcSurface.release(); } catch (Exception ignored) {}
                    try { if (srcTexture != null) srcTexture.release(); } catch (Exception ignored) {}
                    try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
                }
            });
        }
        if (glThread != null) glThread.quitSafely();
    }
}
