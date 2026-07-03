package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * 摄像头合成器：真实相机帧当背景，用户的图片/视频当挂件叠在指定位置/尺寸，GL 合成后画到 app 的显示 surface。
 * 生命周期：prepareCamera()（会话配置前，产出相机输入 surface）→ startOutput()（build 时拿到 app 显示 surface）。
 */
public class CameraCompositor implements SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "VCAM-comp";

    private HandlerThread glThread;
    private Handler glHandler;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;

    private int camTexId;
    private SurfaceTexture camTexture;
    private Surface camSurface;
    private final float[] camMatrix = new float[16];

    private int overlayTexId = -1;
    private boolean overlayIsVideo = false;
    private int overlayVideoTex = -1;
    private SurfaceTexture overlayVideoST;
    private Surface overlayVideoSurface;
    private android.media.MediaPlayer overlayPlayer;
    private volatile boolean overlayHasFrame = false;
    private final float[] ovStMatrix = new float[16];
    private final float[] rot90tex = new float[16];
    private final float[] ovStTmp = new float[16];
    private float ox = 0.6f, oy = 0.02f, ow = 0.38f, oh = 0.38f;   // 归一化 rect（左上原点）
    private int outRot = 0;                                          // 整体旋转角（扶正相机+挂件）
    private volatile int camExtraRot = 0;                          // 前后摄 sensor 朝向差补偿（只作用于相机背景）
    private volatile boolean camMirror = false;                    // 前摄水平镜像（只作用于相机背景）
    private final float[] camMvp = new float[16];                   // 相机背景专用 MVP
    private final float[] ovMvp = new float[16];
    private final float[] rotM = new float[16];
    private final float[] ts = new float[16];
    private final float[] flipV = {1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1};
    private final float[] identity = new float[16];

    private int camProgram, ovProgram;
    private int camPos, camTex, camU_mvp, camU_st, camU_sampler;
    private int ovPos, ovTex, ovU_mvp, ovU_st, ovU_sampler;
    private FloatBuffer quad;

    private volatile boolean outputReady = false;
    private volatile boolean released = false;
    private int outW = 0, outH = 0;

    private static final String VS =
            "uniform mat4 uMVP;\nuniform mat4 uST;\n" +
            "attribute vec4 aPos;\nattribute vec4 aTex;\nvarying vec2 vTex;\n" +
            "void main(){ gl_Position = uMVP*aPos; vTex = (uST*aTex).xy; }\n";
    private static final String FS_OES =
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n" +
            "varying vec2 vTex;\nuniform samplerExternalOES sTex;\n" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";
    private static final String FS_2D =
            "precision mediump float;\nvarying vec2 vTex;\nuniform sampler2D sTex;\n" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";

    /** 会话配置前调用：建 EGL + 相机 OES 纹理，产出相机输入 surface。
     *  @param extraRot 当前摄像头相对首个摄像头的 sensor 朝向补偿角（前后摄切换用）
     *  @param mirror   是否水平镜像（前摄用） */
    public void prepareCamera(final int extraRot, final boolean mirror) {
        this.camExtraRot = ((extraRot % 360) + 360) % 360;
        this.camMirror = mirror;
        glThread = new HandlerThread("cam-comp");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        runSync(new Runnable() {
            public void run() {
                try {
                    initEGL();
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
                    Matrix.setIdentityM(identity, 0);
                    Matrix.setIdentityM(rot90tex, 0);
                    Matrix.translateM(rot90tex, 0, 0.5f, 0.5f, 0);
                    Matrix.rotateM(rot90tex, 0, 270, 0, 0, 1);
                    Matrix.translateM(rot90tex, 0, -0.5f, -0.5f, 0);
                    camProgram = buildProgram(VS, FS_OES);
                    camPos = GLES20.glGetAttribLocation(camProgram, "aPos");
                    camTex = GLES20.glGetAttribLocation(camProgram, "aTex");
                    camU_mvp = GLES20.glGetUniformLocation(camProgram, "uMVP");
                    camU_st = GLES20.glGetUniformLocation(camProgram, "uST");
                    camU_sampler = GLES20.glGetUniformLocation(camProgram, "sTex");
                    ovProgram = buildProgram(VS, FS_2D);
                    ovPos = GLES20.glGetAttribLocation(ovProgram, "aPos");
                    ovTex = GLES20.glGetAttribLocation(ovProgram, "aTex");
                    ovU_mvp = GLES20.glGetUniformLocation(ovProgram, "uMVP");
                    ovU_st = GLES20.glGetUniformLocation(ovProgram, "uST");
                    ovU_sampler = GLES20.glGetUniformLocation(ovProgram, "sTex");
                    float[] v = {-1,-1, 0,0,  1,-1, 1,0,  -1,1, 0,1,  1,1, 1,1};
                    quad = ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    quad.put(v).position(0);
                    int[] t = new int[1];
                    GLES20.glGenTextures(1, t, 0);
                    camTexId = t[0];
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTexId);
                    GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    camTexture = new SurfaceTexture(camTexId);
                    camTexture.setDefaultBufferSize(1920, 1080);   // 否则相机渲染到默认小 buffer → 放大后模糊
                    camTexture.setOnFrameAvailableListener(CameraCompositor.this, glHandler);
                    camSurface = new Surface(camTexture);
                    Log.i(TAG, "【VCAM】【comp】相机纹理就绪");
                } catch (Throwable e) {
                    Log.i(TAG, "【VCAM】【comp】prepare 失败：" + e);
                }
            }
        });
    }

    public Surface getCameraSurface() { return camSurface; }

    /** build 时调用：拿到 app 显示 surface，建窗口 EGLSurface，加载挂件，开始合成。 */
    public void startOutput(final Surface appSurface, final String overlayPath, final boolean isVideo, final float x, final float y, final float w, final float h, final int rot, final int ovRot) {
        glHandler.post(new Runnable() {
            public void run() {
                try {
                    if (window != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(eglDisplay, window); window = EGL14.EGL_NO_SURFACE; }
                    window = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, appSurface, new int[]{EGL14.EGL_NONE}, 0);
                    EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext);
                    int[] wv = new int[1], hv = new int[1];
                    EGL14.eglQuerySurface(eglDisplay, window, EGL14.EGL_WIDTH, wv, 0);
                    EGL14.eglQuerySurface(eglDisplay, window, EGL14.EGL_HEIGHT, hv, 0);
                    outW = wv[0]; outH = hv[0];
                    ox = x; oy = y; ow = w; oh = h;
                    outRot = ((rot % 360) + 360) % 360;
                    // 挂件方向校正角（可配置，不同视频朝向不同）
                    Matrix.setIdentityM(rot90tex, 0);
                    Matrix.translateM(rot90tex, 0, 0.5f, 0.5f, 0);
                    Matrix.rotateM(rot90tex, 0, ((ovRot % 360) + 360) % 360, 0, 0, 1);
                    Matrix.translateM(rot90tex, 0, -0.5f, -0.5f, 0);
                    overlayIsVideo = isVideo;
                    if (overlayPath != null && isVideo) {
                        // 视频挂件：OES 纹理 + SurfaceTexture，MediaPlayer 循环静音播放
                        int[] t = new int[1];
                        GLES20.glGenTextures(1, t, 0);
                        overlayVideoTex = t[0];
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayVideoTex);
                        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                        overlayVideoST = new SurfaceTexture(overlayVideoTex);
                        overlayVideoST.setOnFrameAvailableListener(CameraCompositor.this, glHandler);
                        overlayVideoSurface = new Surface(overlayVideoST);
                        overlayPlayer = new android.media.MediaPlayer();
                        overlayPlayer.setSurface(overlayVideoSurface);
                        overlayPlayer.setLooping(true);
                        overlayPlayer.setVolume(0, 0);
                        overlayPlayer.setDataSource(overlayPath);
                        overlayPlayer.prepare();
                        overlayPlayer.start();
                        Log.i(TAG, "【VCAM】【comp】视频挂件启动 " + overlayPath);
                    } else if (overlayPath != null) {
                        Bitmap bmp = BitmapFactory.decodeFile(overlayPath);
                        if (bmp != null) {
                            // 校正挂件与相机的固定 90° 方向差
                            android.graphics.Matrix bm = new android.graphics.Matrix();
                            bm.postRotate(90);
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), bm, true);
                            int[] t = new int[1];
                            GLES20.glGenTextures(1, t, 0);
                            overlayTexId = t[0];
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId);
                            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
                            bmp.recycle();
                        }
                    }
                    outputReady = true;
                    Log.i(TAG, "【VCAM】【comp】输出就绪 " + outW + "x" + outH + " overlay=" + (overlayTexId>=0) + " rect=" + ox + "," + oy + "," + ow + "," + oh);
                } catch (Throwable e) {
                    Log.i(TAG, "【VCAM】【comp】startOutput 失败：" + e);
                }
            }
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (released || camTexture == null) return;
        if (st == overlayVideoST) { overlayHasFrame = true; return; }
        try {
            if (!outputReady || window == EGL14.EGL_NO_SURFACE) {
                // 还没输出：仍要消费帧避免相机缓冲卡死
                EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
                camTexture.updateTexImage();
                return;
            }
            EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext);
            camTexture.updateTexImage();
            camTexture.getTransformMatrix(camMatrix);
            GLES20.glViewport(0, 0, outW, outH);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 整体旋转矩阵（挂件用）
            Matrix.setIdentityM(rotM, 0);
            Matrix.rotateM(rotM, 0, outRot, 0, 0, 1);

            // 相机背景专用 MVP：整体旋转 + 前后摄 sensor 朝向补偿 + 前摄镜像
            Matrix.setIdentityM(camMvp, 0);
            Matrix.rotateM(camMvp, 0, outRot + camExtraRot, 0, 0, 1);
            if (camMirror) Matrix.scaleM(camMvp, 0, -1, 1, 1);

            // 背景：真实相机（OES 全屏）
            GLES20.glUseProgram(camProgram);
            bindQuad(camPos, camTex);
            GLES20.glUniformMatrix4fv(camU_mvp, 1, false, camMvp, 0);
            GLES20.glUniformMatrix4fv(camU_st, 1, false, camMatrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTexId);
            GLES20.glUniform1i(camU_sampler, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // 挂件（叠在 rect）：整体旋转 * 定位缩放
            float cx = 2*ox - 1 + ow;
            float cy = 1 - 2*oy - oh;
            Matrix.setIdentityM(ts, 0);
            Matrix.translateM(ts, 0, cx, cy, 0);
            Matrix.scaleM(ts, 0, ow, oh, 1);
            Matrix.multiplyMM(ovMvp, 0, rotM, 0, ts, 0);
            if (overlayIsVideo && overlayVideoTex >= 0 && overlayHasFrame) {
                overlayVideoST.updateTexImage();
                overlayVideoST.getTransformMatrix(ovStMatrix);
                Matrix.multiplyMM(ovStTmp, 0, rot90tex, 0, ovStMatrix, 0);   // 方向校正（旋转在采样变换之后）
                GLES20.glUseProgram(camProgram);
                bindQuad(camPos, camTex);
                GLES20.glUniformMatrix4fv(camU_mvp, 1, false, ovMvp, 0);
                GLES20.glUniformMatrix4fv(camU_st, 1, false, ovStTmp, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayVideoTex);
                GLES20.glUniform1i(camU_sampler, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } else if (overlayTexId >= 0) {
                GLES20.glUseProgram(ovProgram);
                bindQuad(ovPos, ovTex);
                GLES20.glUniformMatrix4fv(ovU_mvp, 1, false, ovMvp, 0);
                GLES20.glUniformMatrix4fv(ovU_st, 1, false, flipV, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId);
                GLES20.glUniform1i(ovU_sampler, 0);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisable(GLES20.GL_BLEND);
            }

            EGL14.eglSwapBuffers(eglDisplay, window);
        } catch (Throwable ignored) {
        }
    }

    private void bindQuad(int posLoc, int texLoc) {
        quad.position(0);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(posLoc);
        quad.position(2);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(texLoc);
    }

    public void stop() {
        released = true;
        if (glHandler != null) {
            glHandler.post(new Runnable() {
                public void run() {
                    try { if (overlayPlayer != null) { overlayPlayer.stop(); overlayPlayer.release(); overlayPlayer = null; } } catch (Exception ignored) {}
                    try { if (overlayVideoSurface != null) overlayVideoSurface.release(); } catch (Exception ignored) {}
                    try { if (overlayVideoST != null) overlayVideoST.release(); } catch (Exception ignored) {}
                    try { if (camSurface != null) camSurface.release(); } catch (Exception ignored) {}
                    try { if (camTexture != null) camTexture.release(); } catch (Exception ignored) {}
                    try { if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, window); } catch (Exception ignored) {}
                    try { if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer); } catch (Exception ignored) {}
                    try {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay);
                    } catch (Exception ignored) {}
                }
            });
        }
        if (glThread != null) glThread.quitSafely();
    }

    private void initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);
        int[] attrib = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        };
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attrib, 0, cfgs, 0, 1, n, 0);
        eglConfig = cfgs[0];
        int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
        int[] pbAttr = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttr, 0);
    }

    private int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void runSync(Runnable r) {
        final CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(new Runnable() {
            public void run() { try { r.run(); } finally { latch.countDown(); } }
        });
        try { latch.await(); } catch (InterruptedException ignored) {}
    }
}
