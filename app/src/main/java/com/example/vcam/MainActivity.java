package com.example.vcam;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity {

    private Switch disable_switch;      // switch2 临时停用模块
    private Switch play_sound_switch;   // switch3 播放视频原声

    // ===== 二开：视频源配置 =====
    private static final int REQ_PICK_VIDEO = 1001;
    private TextView tv_video, tv_status, tv_target;
    private EditText et_live_url;
    private String selectedPkg = "com.zhiliaoapp.musically";   // 默认目标 App = TikTok
    private android.content.SharedPreferences prefs;
    private float restorePosX = -1f, restorePosY = -1f;        // >=0 时 loadOverlayThumb 用它恢复上次位置
    private File cachedVideo;
    private FlvVideoSource flvPreview;
    private volatile boolean applying = false;

    // ===== 合成模式 =====
    private static final int REQ_PICK_OVERLAY = 1002;
    private File cachedOverlay;
    private boolean overlayIsVideo = false;
    private int camRot = 0;    // 相机旋转（选 App 时按预设填；默认抖音=0）
    private int ovRot = 0;     // 挂件旋转
    private TextView tv_overlay, tv_overlay_pos, tv_composite_status;
    private android.widget.SeekBar sb_size;
    private Button btn_cam_rot;
    private android.widget.FrameLayout canvas;
    private android.widget.ImageView overlayThumb;
    private float dragDX, dragDY;
    private float overlayAspect = 1.777f;      // 生效比例：框 高/宽（随旋转 90/270 对调）
    private float overlayAspectRaw = 1.777f;   // 素材原始 高/宽（默认 9:16 竖屏）
    private android.graphics.Bitmap overlayBmpRaw;  // 素材原始缩略图（旋转时据此重生成）
    private android.media.MediaPlayer previewPlayer;
    private int previewRotation = 0;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(MainActivity.this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show();
            }else {
                File camera_dir = new File (Environment.getExternalStorageDirectory().getAbsolutePath()+"/DCIM/Camera1/");
                if (!camera_dir.exists()){
                    camera_dir.mkdir();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sync_statue_with_files();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (previewPlayer != null) {
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
        if (flvPreview != null) {
            try { flvPreview.stopDecode(); } catch (Exception ignored) {}
            flvPreview = null;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("vcam_cfg", MODE_PRIVATE);
        selectedPkg = prefs.getString("pkg", selectedPkg);     // 恢复上次选的目标 App

        disable_switch = findViewById(R.id.switch2);
        play_sound_switch = findViewById(R.id.switch3);



        sync_statue_with_files();

        disable_switch.setOnCheckedChangeListener((compoundButton, b) -> {
            if (compoundButton.isPressed()) {
                if (!has_permission()) {
                    request_permission();
                } else {
                    File disable_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/disable.jpg");
                    if (disable_file.exists() != b){
                        if (b){
                            try {
                                disable_file.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }else {
                            disable_file.delete();
                        }
                    }
                }
                sync_statue_with_files();
            }
        });

        play_sound_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (compoundButton.isPressed()) {
                    if (!has_permission()) {
                        request_permission();
                    } else {
                        File play_sound_switch = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no-silent.jpg");
                        if (play_sound_switch.exists() != b){
                            if (b){
                                try {
                                    play_sound_switch.createNewFile();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }else {
                                play_sound_switch.delete();
                            }
                        }
                    }
                    sync_statue_with_files();
                }
            }
        });

        // ===== 目标 App =====
        tv_target = findViewById(R.id.tv_target);
        initTargetLabel();
        tv_target.setOnClickListener(v -> pickTargetApp());
        Button btn_pick_app = findViewById(R.id.btn_pick_app);
        btn_pick_app.setOnClickListener(v -> pickTargetApp());

        // ===== 合成模式控件 =====
        tv_overlay = findViewById(R.id.tv_overlay);
        tv_overlay_pos = findViewById(R.id.tv_overlay_pos);
        tv_composite_status = findViewById(R.id.tv_composite_status);
        sb_size = findViewById(R.id.sb_size);
        canvas = findViewById(R.id.canvas);
        overlayThumb = findViewById(R.id.overlay_thumb);
        btn_cam_rot = findViewById(R.id.btn_cam_rot);
        Button btn_ov_rot = findViewById(R.id.btn_ov_rot);
        Button btn_pick_overlay = findViewById(R.id.btn_pick_overlay);
        Button btn_apply_composite = findViewById(R.id.btn_apply_composite);
        applyCamRotPreset(selectedPkg);
        camRot = prefs.getInt("cam_rot", camRot);              // 上次手调的相机方向优先于预设
        btn_cam_rot.setText("相机旋转（" + camRot + "°）");
        ovRot = prefs.getInt("ov_rot", ovRot);
        sb_size.setProgress(prefs.getInt("size", sb_size.getProgress()));
        btn_ov_rot.setText("挂件旋转（" + ovRot + "°）");
        btn_pick_overlay.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            startActivityForResult(i, REQ_PICK_OVERLAY);
        });
        btn_cam_rot.setOnClickListener(v -> {
            camRot = (camRot + 90) % 360;
            btn_cam_rot.setText("相机旋转（" + camRot + "°）");
        });
        btn_ov_rot.setOnClickListener(v -> {
            ovRot = (ovRot + 90) % 360;
            btn_ov_rot.setText("挂件旋转（" + ovRot + "°）");
            applyOverlayRotation();
        });
        sb_size.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) { applyThumbSize(); }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        setupThumbDrag();
        updatePosText();
        btn_apply_composite.setOnClickListener(v -> applyComposite());

        // ===== 底部 Tab：合成 / 设置 =====
        setupBottomTabs();

        restoreOverlay();   // 启动时恢复上次的挂件设置
    }

    /** 启动时恢复上次保存的挂件（文件/位置/尺寸/旋转，见 onPause→saveOverlay）。 */
    private void restoreOverlay() {
        if (prefs == null) return;
        String path = prefs.getString("ov_path", null);
        if (path == null) return;
        File f = new File(path);
        if (!f.exists()) return;
        cachedOverlay = f;
        overlayIsVideo = prefs.getBoolean("ov_video", false);
        tv_overlay.setText("已选挂件：" + (overlayIsVideo ? "视频" : "图片") + " " + (cachedOverlay.length() / 1024) + " KB");
        restorePosX = prefs.getFloat("pos_x", 0.6f);
        restorePosY = prefs.getFloat("pos_y", 0.05f);
        loadOverlayThumb();
    }

    /** 保存当前挂件配置，供下次启动恢复。 */
    private void saveOverlay() {
        if (prefs == null) return;
        android.content.SharedPreferences.Editor e = prefs.edit();
        e.putString("pkg", selectedPkg);
        e.putInt("cam_rot", camRot);
        e.putInt("ov_rot", ovRot);
        if (sb_size != null) e.putInt("size", sb_size.getProgress());
        if (cachedOverlay != null && cachedOverlay.exists()) {
            e.putString("ov_path", cachedOverlay.getAbsolutePath());
            e.putBoolean("ov_video", overlayIsVideo);
        }
        if (canvas != null && canvas.getWidth() > 0 && overlayThumb != null
                && overlayThumb.getVisibility() == View.VISIBLE) {
            e.putFloat("pos_x", overlayThumb.getX() / canvas.getWidth());
            e.putFloat("pos_y", overlayThumb.getY() / canvas.getHeight());
        }
        e.apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveOverlay();
    }

    private void setupBottomTabs() {
        final TextView tabComposite = findViewById(R.id.tab_composite);
        final TextView tabSettings = findViewById(R.id.tab_settings);
        final View pageComposite = findViewById(R.id.page_composite);
        final View pageSettings = findViewById(R.id.page_settings);
        final int active = getResources().getColor(R.color.accent_purple);
        final int inactive = getResources().getColor(R.color.text_secondary);
        tabComposite.setOnClickListener(v -> {
            pageComposite.setVisibility(View.VISIBLE);
            pageSettings.setVisibility(View.GONE);
            tabComposite.setTextColor(active);
            tabComposite.setTypeface(null, android.graphics.Typeface.BOLD);
            tabSettings.setTextColor(inactive);
            tabSettings.setTypeface(null, android.graphics.Typeface.NORMAL);
        });
        tabSettings.setOnClickListener(v -> {
            pageComposite.setVisibility(View.GONE);
            pageSettings.setVisibility(View.VISIBLE);
            tabSettings.setTextColor(active);
            tabSettings.setTypeface(null, android.graphics.Typeface.BOLD);
            tabComposite.setTextColor(inactive);
            tabComposite.setTypeface(null, android.graphics.Typeface.NORMAL);
        });
    }

    private void updatePosText() {
        if (tv_overlay_pos == null) return;
        if (canvas == null || canvas.getWidth() == 0 || overlayThumb == null
                || overlayThumb.getVisibility() != android.view.View.VISIBLE) {
            tv_overlay_pos.setText("尺寸 " + sb_size.getProgress() + "%（选挂件后可在上面拖动，可拖出框）");
            return;
        }
        int cw = canvas.getWidth(), ch = canvas.getHeight();
        int px = Math.round(overlayThumb.getX() / cw * 100);
        int py = Math.round(overlayThumb.getY() / ch * 100);
        tv_overlay_pos.setText("位置 X " + px + "% · Y " + py + "% · 尺寸 " + sb_size.getProgress() + "%");
    }

    private float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private void setupThumbDrag() {
        overlayThumb.setOnTouchListener((v, e) -> {
            if (canvas.getWidth() == 0) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // 拖挂件时锁住外层 ScrollView，别跟着上下滚
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    dragDX = v.getX() - e.getRawX();
                    dragDY = v.getY() - e.getRawY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    // 不 clamp，允许挂件拖出相机框
                    v.setX(e.getRawX() + dragDX);
                    v.setY(e.getRawY() + dragDY);
                    updatePosText();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return false;
        });
    }

    /** 尺寸滑块 → 挂件缩略图尺寸：滑块控宽度，高按素材真实比例推，保持中心；不 clamp，允许超出相机框。 */
    private void applyThumbSize() {
        if (canvas == null || overlayThumb == null || canvas.getWidth() == 0) return;
        final int cw = canvas.getWidth();
        int w = Math.max(24, Math.round(sb_size.getProgress() / 100f * cw));
        int h = Math.round(w * overlayAspect);
        final int fw = w, fh = h;
        final float ocx = overlayThumb.getX() + overlayThumb.getWidth() / 2f;
        final float ocy = overlayThumb.getY() + overlayThumb.getHeight() / 2f;
        android.view.ViewGroup.LayoutParams lp = overlayThumb.getLayoutParams();
        lp.width = fw; lp.height = fh;
        overlayThumb.setLayoutParams(lp);
        overlayThumb.post(() -> {
            overlayThumb.setX(ocx - fw / 2f);   // 保持中心，不 clamp 到画布内
            overlayThumb.setY(ocy - fh / 2f);
            updatePosText();
        });
    }

    /** 加载挂件缩略图（图片直接解，视频取第一帧），显示并初始化到右上角。 */
    private void loadOverlayThumb() {
        if (overlayThumb == null || cachedOverlay == null) return;
        try {
            android.graphics.Bitmap bmp;
            if (overlayIsVideo) {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                mmr.setDataSource(cachedOverlay.getAbsolutePath());
                bmp = mmr.getFrameAtTime(0);
                mmr.release();
            } else {
                bmp = android.graphics.BitmapFactory.decodeFile(cachedOverlay.getAbsolutePath());
            }
            if (bmp == null) return;
            overlayBmpRaw = bmp;
            overlayAspectRaw = bmp.getWidth() > 0 ? (float) bmp.getHeight() / bmp.getWidth() : 1f;
            overlayThumb.post(() -> {
                applyOverlayRotation();   // 按当前角度设缩略图 + 框比例 + 尺寸
                overlayThumb.setVisibility(android.view.View.VISIBLE);
                overlayThumb.post(() -> {
                    if (restorePosX >= 0) {   // 恢复上次保存的位置
                        overlayThumb.setX(restorePosX * canvas.getWidth());
                        overlayThumb.setY(restorePosY * canvas.getHeight());
                        restorePosX = -1f;
                    } else {                  // 新选的挂件：默认放右上区
                        overlayThumb.setX(canvas.getWidth() * 0.6f);
                        overlayThumb.setY(canvas.getHeight() * 0.05f);
                    }
                    updatePosText();
                });
            });
        } catch (Exception ignored) {}
    }

    /** 按当前挂件旋转角：重生成转后缩略图并对调框比例（90/270 宽高互换），保证预览 = 合成输出。 */
    private void applyOverlayRotation() {
        if (overlayThumb == null || overlayBmpRaw == null) return;
        android.graphics.Bitmap show = overlayBmpRaw;
        if (ovRot != 0) {
            try {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(ovRot);
                show = android.graphics.Bitmap.createBitmap(overlayBmpRaw, 0, 0,
                        overlayBmpRaw.getWidth(), overlayBmpRaw.getHeight(), m, true);
            } catch (Exception ignored) { show = overlayBmpRaw; }
        }
        overlayAspect = (ovRot % 180 == 0) ? overlayAspectRaw : 1f / overlayAspectRaw;
        overlayThumb.setRotation(0);   // 方向已烘进 bitmap，不再视觉旋转
        overlayThumb.setImageBitmap(show);
        applyThumbSize();
    }

    /** 从 assets 释放占位视频（合成模式激活 hook 需要 virtual.mp4 存在）。 */
    private File ensurePlaceholder() {
        File ph = new File(getExternalCacheDir(), "placeholder.mp4");
        if (ph.exists() && ph.length() > 0) return ph;
        try {
            java.io.InputStream in = getAssets().open("placeholder.mp4");
            java.io.FileOutputStream out = new java.io.FileOutputStream(ph);
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close(); in.close();
        } catch (Exception ignored) {}
        return ph;
    }

    /** 选目标 App 后按预设填相机方向角（抖音/TikTok 已适配）。 */
    private void applyCamRotPreset(String pkg) {
        if (pkg == null) return;
        if (pkg.equals("com.ss.android.ugc.aweme")) camRot = 0;          // 抖音
        else if (pkg.equals("com.zhiliaoapp.musically")) camRot = 90;    // TikTok
        if (btn_cam_rot != null) btn_cam_rot.setText("相机旋转（" + camRot + "°）");
    }

    /** 应用合成：真实相机背景 + 挂件，写配置到目标私有目录。 */
    private void applyComposite() {
        final String pkg = selectedPkg;
        if (pkg == null || pkg.isEmpty()) { tv_composite_status.setText("请先在②选择目标 App"); return; }
        if (cachedOverlay == null || !cachedOverlay.exists()) { tv_composite_status.setText("请先选择挂件（图片/视频）"); return; }
        if (canvas == null || canvas.getWidth() == 0 || overlayThumb.getVisibility() != android.view.View.VISIBLE) {
            tv_composite_status.setText("请先选择挂件"); return;
        }
        if (applying) { tv_composite_status.setText("正在处理中…"); return; }
        applying = true;
        final int cr = camRot, or = ovRot;
        final int cw = canvas.getWidth(), ch = canvas.getHeight();
        final float ox = overlayThumb.getX() / cw;
        final float oy = overlayThumb.getY() / ch;
        final float ow = (float) overlayThumb.getWidth() / cw;
        final float oh = (float) overlayThumb.getHeight() / ch;
        final boolean isVid = overlayIsVideo;
        final String ovSrc = cachedOverlay.getAbsolutePath();
        final String placeholder = ensurePlaceholder().getAbsolutePath();
        tv_composite_status.setText("应用合成中…");
        new Thread(() -> {
            String r = pushComposite(pkg, ovSrc, isVid, placeholder, ox, oy, ow, oh, cr, or);
            runOnUiThread(() -> { tv_composite_status.setText(r); sync_statue_with_files(); });
            applying = false;
        }).start();
    }

    private String pushComposite(String pkg, String ovSrc, boolean isVideo, String placeholder,
                                 float x, float y, float ow, float oh, int camR, int ovR) {
        String destDir = "/sdcard/Android/data/" + pkg + "/files/Camera1";
        String priv = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg";
        String ovName = isVideo ? "overlay.mp4" : "overlay.png";
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("mkdir -p '" + destDir + "'\n");
            os.writeBytes("cp '" + placeholder + "' '" + destDir + "/virtual.mp4'\n");   // 占位，激活 hook
            os.writeBytes("chmod 666 '" + destDir + "/virtual.mp4'\n");
            os.writeBytes("rm -f '" + destDir + "/live_url.txt' '" + destDir + "/overlay.png' '" + destDir + "/overlay.mp4'\n");
            os.writeBytes("cp '" + ovSrc + "' '" + destDir + "/" + ovName + "'\n");
            os.writeBytes("chmod 666 '" + destDir + "/" + ovName + "'\n");
            os.writeBytes("printf '%s %s %s %s' '" + x + "' '" + y + "' '" + ow + "' '" + oh + "' > '" + destDir + "/overlay_rect.txt'\n");
            os.writeBytes("printf %s '" + camR + "' > '" + destDir + "/live_rot.txt'\n");
            os.writeBytes("printf %s '" + ovR + "' > '" + destDir + "/overlay_rot.txt'\n");
            os.writeBytes("chmod 666 '" + destDir + "/overlay_rect.txt' '" + destDir + "/live_rot.txt' '" + destDir + "/overlay_rot.txt'\n");
            os.writeBytes("touch '" + priv + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            int r = p.waitFor();
            if (r == 0) return "✓ 已应用合成到 " + pkg + "（相机 " + camR + "° 挂件 " + ovR + "°）。\n重新进入拍摄/直播画面即生效；方向不对就调旋转再点一次。";
            return "su 执行失败（code=" + r + "）";
        } catch (Exception e) {
            return "root 调用失败：" + e.getMessage();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_OVERLAY && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                android.net.Uri uri = data.getData();
                String mime = getContentResolver().getType(uri);
                overlayIsVideo = mime != null && mime.startsWith("video/");
                cachedOverlay = new File(getExternalCacheDir(), overlayIsVideo ? "overlay_pick.mp4" : "overlay_pick.png");
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.FileOutputStream out = new java.io.FileOutputStream(cachedOverlay);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                tv_overlay.setText("已选挂件：" + (overlayIsVideo ? "视频" : "图片") + " " + (cachedOverlay.length() / 1024) + " KB");
                loadOverlayThumb();
            } catch (Exception e) {
                tv_overlay.setText("读取挂件失败：" + e.getMessage());
            }
            return;
        }
    }

    /** 只列“用户安装的 + 申请了相机权限的”App（排除系统 App 与自己），带图标，像 LSPosed 那样精简。 */
    private void pickTargetApp() {
        PackageManager pm = getPackageManager();
        final java.util.List<AppItem> apps = new java.util.ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean pureSystem = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (pureSystem) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS);
                boolean hasCam = false;
                if (pi.requestedPermissions != null) {
                    for (String perm : pi.requestedPermissions) {
                        if (Manifest.permission.CAMERA.equals(perm)) { hasCam = true; break; }
                    }
                }
                if (!hasCam) continue;
            } catch (Exception ignored) { continue; }
            AppItem it = new AppItem();
            it.pkg = ai.packageName;
            it.label = pm.getApplicationLabel(ai).toString();
            try { it.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) {}
            apps.add(it);
        }
        java.util.Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int i) { return apps.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_app, parent, false);
                AppItem it = apps.get(pos);
                ((ImageView) cv.findViewById(R.id.app_icon)).setImageDrawable(it.icon);
                ((TextView) cv.findViewById(R.id.app_label)).setText(it.label);
                ((TextView) cv.findViewById(R.id.app_pkg)).setText(it.pkg);
                return cv;
            }
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择目标 App（" + apps.size() + " 个）");
        b.setAdapter(adapter, (d, which) -> {
            AppItem it = apps.get(which);
            selectedPkg = it.pkg;
            tv_target.setText(it.label + "\n" + it.pkg);
            applyCamRotPreset(it.pkg);   // 按 App 预设相机方向
            tv_composite_status.setText("已选目标：" + it.label + "，相机方向预设 " + camRot + "°");
        });
        b.setNegativeButton(R.string.negative, null);
        b.show();
    }

    /** 初始化目标显示（默认抖音；未装则显示包名）。 */
    private void initTargetLabel() {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(selectedPkg, 0);
            tv_target.setText(getPackageManager().getApplicationLabel(ai) + "\n" + selectedPkg);
        } catch (Exception e) {
            tv_target.setText(selectedPkg + "（未安装？点“选择目标 App”重选）");
        }
    }

    static class AppItem {
        String label, pkg;
        android.graphics.drawable.Drawable icon;
    }

    /** 后台线程：按当前旋转角度真转码视频，再用 root 放进目标 App 私有目录并开启强制私有目录。 */
    private void applyVideoToApp() {
        if (applying) { tv_status.setText("正在处理中，请稍候…（别重复点，会多个转码抢同一文件）"); return; }
        final String pkg = selectedPkg;
        if (pkg == null || pkg.isEmpty()) { tv_status.setText("请先选择目标 App"); return; }
        final String liveUrl = et_live_url.getText().toString().trim();
        if (liveUrl.startsWith("http")) {
            if (cachedVideo == null || !cachedVideo.exists()) { tv_status.setText("直播模式也要先选一个本地视频做占位（hook 激活需要）"); return; }
            applying = true;
            tv_status.setText("配置直播流…");
            new Thread(() -> {
                try {
                    final String result = pushLive(pkg, liveUrl, cachedVideo.getAbsolutePath(), previewRotation);
                    runOnUiThread(() -> { tv_status.setText(result); sync_statue_with_files(); });
                } finally {
                    applying = false;
                }
            }).start();
            return;
        }
        if (cachedVideo == null || !cachedVideo.exists()) { tv_status.setText("请先选择视频文件"); return; }
        final int rot = previewRotation;
        applying = true;
        tv_status.setText("应用中…（实时旋转 " + rot + "°，秒生效）");
        new Thread(() -> {
            try {
                // 不预转码：推原始视频 + 写角度，hook 里实时旋转（长视频也秒回）
                final String result = pushWithRoot(pkg, cachedVideo.getAbsolutePath(), rot);
                runOnUiThread(() -> { tv_status.setText(result); sync_statue_with_files(); });
            } catch (Throwable e) {
                runOnUiThread(() -> tv_status.setText("应用失败：" + e));
            } finally {
                applying = false;
            }
        }).start();
    }

    /** root 把 src 放进目标 App 私有目录（+ 共享 DCIM 兜底），并开启强制私有目录。 */
    private String pushWithRoot(String pkg, String src, int rot) {
        String destDir = "/sdcard/Android/data/" + pkg + "/files/Camera1";
        String priv = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg";
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("mkdir -p '" + destDir + "'\n");
            os.writeBytes("cp '" + src + "' '" + destDir + "/virtual.mp4'\n");
            os.writeBytes("chmod 666 '" + destDir + "/virtual.mp4'\n");
            os.writeBytes("mkdir -p /sdcard/DCIM/Camera1\n");
            os.writeBytes("cp '" + src + "' /sdcard/DCIM/Camera1/virtual.mp4\n");
            os.writeBytes("chmod 666 /sdcard/DCIM/Camera1/virtual.mp4\n");
            os.writeBytes("rm -f '" + destDir + "/live_url.txt'\n");
            os.writeBytes("printf %s '" + rot + "' > '" + destDir + "/live_rot.txt'\n");
            os.writeBytes("chmod 666 '" + destDir + "/live_rot.txt'\n");
            os.writeBytes("touch '" + priv + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            int r = p.waitFor();
            if (r == 0) {
                return "✓ 已应用到 " + pkg + "（实时旋转 " + rot + "°，秒生效）。\n强制停止该 App 后重开；方向不对就换角度再点一次。";
            }
            return "su 执行失败（code=" + r + "）——vcam 是否已授予 root？";
        } catch (Exception e) {
            return "root 调用失败：" + e.getMessage();
        }
    }

    /** 直播模式：占位视频 + 写 live_url.txt（root）；目标 App 一开相机就拉直播流。 */
    private String pushLive(String pkg, String url, String placeholderVideo, int rot) {
        String destDir = "/sdcard/Android/data/" + pkg + "/files/Camera1";
        String priv = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg";
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("mkdir -p '" + destDir + "'\n");
            os.writeBytes("cp '" + placeholderVideo + "' '" + destDir + "/virtual.mp4'\n");
            os.writeBytes("chmod 666 '" + destDir + "/virtual.mp4'\n");
            os.writeBytes("printf %s '" + url + "' > '" + destDir + "/live_url.txt'\n");
            os.writeBytes("chmod 666 '" + destDir + "/live_url.txt'\n");
            os.writeBytes("printf %s '" + rot + "' > '" + destDir + "/live_rot.txt'\n");
            os.writeBytes("chmod 666 '" + destDir + "/live_rot.txt'\n");
            os.writeBytes("touch '" + priv + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            int r = p.waitFor();
            if (r == 0) {
                return "✓ 已配置直播流到 " + pkg + "。\n强制停止该 App 后重开，一进相机即拉流。";
            }
            return "su 执行失败（code=" + r + "）——vcam 是否已授予 root？";
        } catch (Exception e) {
            return "root 调用失败：" + e.getMessage();
        }
    }

    private void request_permission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED
                    || this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle(R.string.permission_lack_warn);
                builder.setMessage(R.string.permission_description);

                builder.setNegativeButton(R.string.negative, (dialogInterface, i) -> Toast.makeText(MainActivity.this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show());

                builder.setPositiveButton(R.string.positive, (dialogInterface, i) -> requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1));
                builder.show();
            }
        }
    }

    private boolean has_permission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return this.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_DENIED
                    && this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_DENIED;
        }
        return true;
    }


    private void sync_statue_with_files() {
        Log.d(this.getApplication().getPackageName(), "【VCAM】[sync]同步开关状态");

        if (!has_permission()){
            request_permission();
        }else {
            File camera_dir = new File (Environment.getExternalStorageDirectory().getAbsolutePath()+"/DCIM/Camera1");
            if (!camera_dir.exists()){
                camera_dir.mkdir();
            }
            // 私有目录强制默认开启（不再作为开关暴露，标记文件缺失就补上）
            File privFlag = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg");
            if (!privFlag.exists()){
                try { privFlag.createNewFile(); } catch (IOException ignored) {}
            }
        }

        File disable_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/disable.jpg");
        disable_switch.setChecked(disable_file.exists());

        File play_sound_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no-silent.jpg");
        play_sound_switch.setChecked(play_sound_file.exists());

    }


}
