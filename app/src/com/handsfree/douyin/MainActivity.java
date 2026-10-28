package com.handsfree.douyin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v4.0：手势切抖音（纯前置摄像头版）。
 *
 * 已删除全部语音功能（口令、Vosk/KWS 引擎、麦克风权限、连续触发熔断）。
 * 只保留：视觉方式（手势/表情）、表情动作自定义、冷却、执行通道、反馈开关、日志。
 */
public class MainActivity extends Activity {

    private static final String PREF = "hf";

    private SharedPreferences sp;
    private TextView status;
    private ImageView previewImg;
    private Switch previewSwitch;
    private Bitmap previewBitmap;
    private TextView pvRotInfo;
    /** v4.7：接收引擎预览帧（引擎已在主线程回调，这里直接贴图） */
    private final CameraVisionEngine.PreviewListener previewListener =
            new CameraVisionEngine.PreviewListener() {
                @Override
                public void onPreview(Bitmap frame) {
                    if (previewImg == null) {
                        frame.recycle();
                        return;
                    }
                    Bitmap old = previewBitmap;
                    previewBitmap = frame;
                    previewImg.setImageBitmap(frame);
                    if (old != null && old != frame) {
                        old.recycle();
                    }
                }
            };
    private TextView linkStatus;
    private TextView logText;
    private final Handler uiHandler = new Handler();
    private int refreshTick = 0;

    /** 表情动作选项：显示名 + blendshape 名 */
    /**
     * v5.3：表情动作扩到 8 种，第三列是该动作的**推荐阈值**（切换动作时自动填入）。
     * 「露牙笑」= 左右嘴角上扬均值；「龇牙」= 上唇上提与下唇下拉的较小值。
     */
    private static final String[][] FACE_ACTIONS = {
            {"张嘴", "jawOpen", "0.55"},
            {"露牙笑", "mouthSmile", "0.45"},
            {"龇牙", "teethBare", "0.30"},
            {"撅嘴", "mouthPucker", "0.50"},
            {"鼓腮", "cheekPuff", "0.40"},
            {"抬眉", "browInnerUp", "0.55"},
            {"眯眼", "eyeSquint", "0.45"},
            {"睁大眼", "eyeWide", "0.45"},
    };
    /** v4.3：手势动作 = 伸出的手指数 */
    private static final String[][] FINGER_ACTIONS = {
            {"1 根手指", "1"}, {"2 根手指", "2"}, {"3 根手指", "3"},
            {"4 根手指", "4"}, {"5 根手指", "5"}
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        // Android 13+：不申请通知权限的话，前台服务的通知不显示（服务本身仍运行）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        Feedback.init(this);
        CameraVisionEngine.previewRotationExtra = sp.getInt("preview_rot_extra", 0);
        CameraVisionEngine.zoomFactor = sp.getFloat("zoom_factor", 1.0f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("手势切抖音 · 前置摄像头版");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(2));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("对着前置摄像头比个手势或做个表情，抖音自动翻页\n"
                + "本 App 不使用麦克风、不使用 root，只负责识别 + 发命令给 ShortX");
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(10));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(13);
        status.setBackgroundColor(Color.parseColor("#F4F6F8"));
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(status);

        // ---- 测试 ----
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setPadding(0, dp(8), 0, 0);
        Button tNext = new Button(this);
        tNext.setText("测试「下一个」");
        tNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Trigger.next(MainActivity.this, "测试按钮", verdictListener());
            }
        });
        Button tPrev = new Button(this);
        tPrev.setText("测试「上一个」");
        tPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Trigger.prev(MainActivity.this, "测试按钮", verdictListener());
            }
        });
        testRow.addView(tNext);
        testRow.addView(tPrev);
        root.addView(testRow);

        Button start = new Button(this);
        start.setText("开启识别");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
                    Toast.makeText(MainActivity.this, "请先允许摄像头权限，再点开启", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(MainActivity.this, VisionService.class);
                i.setAction(VisionService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                Toast.makeText(MainActivity.this, "已开启（首次加载模型约 1~3 秒）", Toast.LENGTH_LONG).show();
                refresh();
            }
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("停止识别");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, VisionService.class));
                refresh();
            }
        });
        root.addView(stop);

        // ---- 识别方式：手势 / 表情（互斥）----
        TextView modeTitle = new TextView(this);
        modeTitle.setText("识别方式（二选一）：");
        modeTitle.setTextSize(15);
        modeTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(modeTitle);

        TextView modeTip = new TextView(this);
        modeTip.setTextSize(12);
        modeTip.setTextColor(Color.parseColor("#666666"));
        modeTip.setText("手机斜靠台面、正对自己；5fps 采样、约 0.5~0.8 秒响应、耗电约 1W，"
                + "状态栏会亮摄像头绿点；**息屏会自动停止识别**（等同手动停止，亮屏后不自动恢复）。切换后请「停止识别」再「开启识别」。");
        root.addView(modeTip);

        // 首次使用默认「表情」模式（张嘴比比手势更省事）
        if (!sp.contains("face_ctrl") && !sp.contains("gesture_cam")) {
            sp.edit().putBoolean("face_ctrl", true).putBoolean("gesture_cam", false).apply();
        }
        final Switch gestureSwitch = new Switch(this);
        gestureSwitch.setChecked(sp.getBoolean("gesture_cam", false));
        final Switch faceSwitch = new Switch(this);
        faceSwitch.setChecked(sp.getBoolean("face_ctrl", true));
        gestureSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton bv, boolean checked) {
                sp.edit().putBoolean("gesture_cam", checked).apply();
                if (checked) {
                    sp.edit().putBoolean("face_ctrl", false).apply();
                    faceSwitch.setChecked(false);
                }
                refresh();
            }
        });
        faceSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton bv, boolean checked) {
                sp.edit().putBoolean("face_ctrl", checked).apply();
                if (checked) {
                    sp.edit().putBoolean("gesture_cam", false).apply();
                    gestureSwitch.setChecked(false);
                }
                refresh();
            }
        });
        root.addView(switchRow("手势（数手指数，动作在下方自定义）", gestureSwitch));
        root.addView(switchRow("面部表情（张嘴 / 撅嘴，动作在下方自定义）", faceSwitch));

        // ---- 高精度模式（v4.6）----
        root.addView(switchRow("高精度模式（960×720 采集，识别更准、耗电略增；切换后需重开识别）",
                "high_accuracy", false));

        TextView hdTip = new TextView(this);
        hdTip.setTextSize(12);
        hdTip.setTextColor(Color.parseColor("#666666"));
        hdTip.setText("开启后摄像头按 960×720 采集（像素量 2.2 倍）：手离得远、手指细节、光线偏暗时"
                + "更容易认对；代价是 CPU 占用和耗电上升。日志会写「采集分辨率 960×720（高精度模式）」"
                + "以确认生效。机型不支持该分辨率时会自动选最接近的尺寸。");
        root.addView(hdTip);

        // ---- 画面放大（v5.1）----
        LinearLayout zmRow = new LinearLayout(this);
        zmRow.setOrientation(LinearLayout.HORIZONTAL);
        zmRow.setGravity(Gravity.CENTER_VERTICAL);
        zmRow.setPadding(0, dp(10), 0, 0);
        TextView zmLabel = new TextView(this);
        zmLabel.setText("画面放大");
        zmLabel.setTextSize(14);
        zmLabel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        zmRow.addView(zmLabel);

        final TextView zmVal = new TextView(this);
        zmVal.setTextSize(14);
        zmVal.setGravity(Gravity.END);
        zmVal.setLayoutParams(new LinearLayout.LayoutParams(
                dp(52), LinearLayout.LayoutParams.WRAP_CONTENT));
        zmVal.setText(String.format(java.util.Locale.US, "%.1fx",
                sp.getFloat("zoom_factor", 1.0f)));

        SeekBar zmBar = new SeekBar(this);
        float z0 = sp.getFloat("zoom_factor", 1.0f);
        zmBar.setMax(30); // 1.0 ~ 4.0，步长 0.1
        zmBar.setProgress(Math.round((z0 - 1.0f) * 10));
        zmBar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        zmBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float z = 1.0f + progress / 10f;
                CameraVisionEngine.zoomFactor = z;
                zmVal.setText(String.format(java.util.Locale.US, "%.1fx", z));
                sp.edit().putFloat("zoom_factor", z).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        zmRow.addView(zmBar);
        zmRow.addView(zmVal);
        root.addView(zmRow);

        TextView zmTip = new TextView(this);
        zmTip.setTextSize(12);
        zmTip.setTextColor(Color.parseColor("#666666"));
        zmTip.setText("手机离得远就把它调大：只取画面中心那块放大，人脸和手在识别模型里会更大、更容易认；"
                + "代价是视野变窄（2.0x 时只剩中心 1/4 面积、4.0x 时只剩 1/16）。"
                + "超过 3x 画面会明显变糊（数字放大，不增加真实细节），建议优先挪近手机、再用放大微调。"
                + "拖动即时生效，打开「摄像头预览」能直接看到放大后的构图。");
        root.addView(zmTip);

        // ---- 手势动作（v4.3：手指数 + 阈值）----
        TextView gTitle = new TextView(this);
        gTitle.setText("手势动作（伸几根手指，保持 0.4 秒触发）：");
        gTitle.setTextSize(15);
        gTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(gTitle);

        TextView gTip = new TextView(this);
        gTip.setTextSize(12);
        gTip.setTextColor(Color.parseColor("#666666"));
        gTip.setText("手抬到脸旁、掌心朝镜头、离 30~60cm，手指张开伸直。握拳（0 根）不触发。\n"
                + "右侧「阈值」= 手部检测置信度：调低更容易检测到手（更灵敏，也更容易误判）。"
                + "改完需「停止识别」再「开启识别」才生效。");
        root.addView(gTip);

        root.addView(actionRow("下一个", "gesture_next", FINGER_ACTIONS, "1", "gesture_prev",
                "gesture_th", 0.35f, 0.10f, 0.90f));
        root.addView(actionRow("上一个", "gesture_prev", FINGER_ACTIONS, "2", "gesture_next",
                "gesture_th", 0.35f, 0.10f, 0.90f));

        // ---- 表情动作（v4.3：动作 + 阈值）----
        TextView actTitle = new TextView(this);
        actTitle.setText("表情动作（两种任选，保持 0.5 秒触发）：");
        actTitle.setTextSize(15);
        actTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(actTitle);

        TextView fTip = new TextView(this);
        fTip.setTextSize(12);
        fTip.setTextColor(Color.parseColor("#666666"));
        fTip.setText("可选 8 种：张嘴 / 露牙笑 / 龇牙 / 撅嘴 / 鼓腮 / 抬眉 / 眯眼 / 睁大眼（两个方向各自任选）。"
                + "切换动作时右侧「阈值」会自动填成该动作的推荐值；难触发调低 0.05，容易误触发调高 0.05。"
                + "日志会显示实时分数（如「露牙笑 0.38/0.45」），照着调最准。");
        root.addView(fTip);

        root.addView(actionRow("下一个", "face_next", FACE_ACTIONS, "jawOpen", "face_prev",
                "face_th_next", 0.55f, 0.05f, 0.95f));
        root.addView(actionRow("上一个", "face_prev", FACE_ACTIONS, "mouthPucker", "face_next",
                "face_th_prev", 0.50f, 0.05f, 0.95f));

        // ---- 冷却 ----
        TextView cdLabel = new TextView(this);
        cdLabel.setText("触发冷却（秒）：两次翻页之间的最短间隔");
        cdLabel.setTextSize(13);
        cdLabel.setPadding(0, dp(12), 0, dp(2));
        root.addView(cdLabel);
        final EditText etCooldown = new EditText(this);
        etCooldown.setText(String.format(java.util.Locale.US, "%g",
                sp.getLong("cooldown_ms", 1500) / 1000.0));
        etCooldown.setTextSize(13);
        etCooldown.setSingleLine(true);
        etCooldown.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etCooldown.setHint("秒，默认 1.5；调小可更快连续翻页，0 = 不冷却");
        etCooldown.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                long ms;
                try {
                    double sec = Double.parseDouble(s.toString().trim().isEmpty() ? "1.5" : s.toString().trim());
                    ms = (long) (sec * 1000);
                } catch (NumberFormatException e) {
                    ms = 1500;
                }
                if (ms < 0) {
                    ms = 0;
                } else if (ms > 60000) {
                    ms = 60000;
                }
                sp.edit().putLong("cooldown_ms", ms).apply();
            }
        });
        root.addView(etCooldown);

        // ---- 执行通道（v4.4：只保留 DeepLink，通知触发已删除）----
        TextView chTitle = new TextView(this);
        chTitle.setText("执行通道：ShortX DeepLink");
        chTitle.setTextSize(15);
        chTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(chTitle);

        TextView chTip = new TextView(this);
        chTip.setTextSize(12);
        chTip.setTextColor(Color.parseColor("#666666"));
        chTip.setText("翻页命令通过 shortx:// 链接交给 ShortX 执行，需要：① ShortX 已安装并在 LSPosed 启用；"
                + "② 本 App 有「后台弹出界面」或「悬浮窗」权限。（v4.4 起移除了通知触发通道）");
        root.addView(chTip);

        // 固定为 deeplink（兼容老配置里残留的 notify）
        if (!"deeplink".equals(sp.getString("exec_channel", ""))) {
            sp.edit().putString("exec_channel", "deeplink").apply();
        }

        // DeepLink 链接输入框
        TextView dlLabel = new TextView(this);
        dlLabel.setText("DeepLink 链接（规则重导后 id 会变，改了要同步）：");
        dlLabel.setTextSize(13);
        dlLabel.setPadding(0, dp(8), 0, dp(2));
        root.addView(dlLabel);

        final EditText etNextUrl = new EditText(this);
        etNextUrl.setText(sp.getString("shortx_url_next", ""));
        etNextUrl.setTextSize(12);
        etNextUrl.setSingleLine(true);
        etNextUrl.setHint("「下一个」链接，如 shortx://run?id=DA-xxxx");
        etNextUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sp.edit().putString("shortx_url_next", s.toString().trim()).apply();
            }
        });
        root.addView(etNextUrl);

        final EditText etPrevUrl = new EditText(this);
        etPrevUrl.setText(sp.getString("shortx_url_prev", ""));
        etPrevUrl.setTextSize(12);
        etPrevUrl.setSingleLine(true);
        etPrevUrl.setHint("「上一个」链接，如 shortx://run?id=DA-yyyy");
        etPrevUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sp.edit().putString("shortx_url_prev", s.toString().trim()).apply();
            }
        });
        root.addView(etPrevUrl);

        linkStatus = new TextView(this);
        linkStatus.setTextSize(12);
        linkStatus.setPadding(0, dp(4), 0, 0);
        root.addView(linkStatus);

        // ---- 反馈（v4.5：只剩震动）----
        TextView fbTitle = new TextView(this);
        fbTitle.setText("反馈（成功识别到动作时）：");
        fbTitle.setTextSize(15);
        fbTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(fbTitle);
        root.addView(switchRow("震动", "fb_vibrate", true));

        TextView fbTip = new TextView(this);
        fbTip.setTextSize(12);
        fbTip.setTextColor(Color.parseColor("#666666"));
        fbTip.setText("识别到动作两短震、拉起 ShortX 单击、失败长震。**息屏即停止识别**（等同于手动点「停止识别」，通知也会消失），下次使用重新点「开启识别」即可。");
        root.addView(fbTip);

        // ---- 授权 ----
        Button grantCam = new Button(this);
        grantCam.setText("授予摄像头权限（必须）");
        grantCam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
            }
        });
        root.addView(grantCam);

        // ---- 摄像头预览（v4.7，调试用）----
        TextView pvTitle = new TextView(this);
        pvTitle.setText("摄像头预览（调手机角度/光线时用）：");
        pvTitle.setTextSize(15);
        pvTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(pvTitle);

        previewSwitch = new Switch(this);
        previewSwitch.setChecked(false);
        previewSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton bv, boolean on) {
                        if (on) {
                            CameraVisionEngine.previewListener = previewListener;
                            if (!VisionService.engineReady) {
                                Toast.makeText(MainActivity.this,
                                        "识别还没启动：先点「开启识别」，有画面后这里会显示",
                                        Toast.LENGTH_LONG).show();
                            }
                        } else {
                            CameraVisionEngine.previewListener = null;
                            clearPreview();
                        }
                    }
                });
        root.addView(switchRow("显示摄像头画面（识别照常运行，每 0.2 秒一帧）", previewSwitch));

        TextView pvTip = new TextView(this);
        pvTip.setTextSize(12);
        pvTip.setTextColor(Color.parseColor("#666666"));
        pvTip.setText("显示的是识别引擎实际处理的那一帧，不额外开相机，所以不会和识别抢设备。"
                + "画面为竖屏正立方向、未做镜像（即摄像头真实所见）。");
        root.addView(pvTip);

        FrameLayout pvBox = new FrameLayout(this);
        pvBox.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        pvLp.topMargin = dp(6);
        pvBox.setLayoutParams(pvLp);
        previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImg.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pvBox.addView(previewImg);
        root.addView(pvBox);

        // v4.8：画面方向微调（各厂商前置方向定义不同，自动值可能上下颠倒）
        LinearLayout pvRow = new LinearLayout(this);
        pvRow.setOrientation(LinearLayout.HORIZONTAL);
        pvRow.setGravity(Gravity.CENTER_VERTICAL);
        pvRow.setPadding(0, dp(6), 0, 0);
        pvRotInfo = new TextView(this);
        pvRotInfo.setTextSize(12);
        pvRotInfo.setTextColor(Color.parseColor("#666666"));
        pvRotInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pvRow.addView(pvRotInfo);
        Button pvRotBtn = new Button(this);
        pvRotBtn.setText("画面方向 +90°");
        pvRotBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (CameraVisionEngine.previewRotationExtra + 90) % 360;
                CameraVisionEngine.previewRotationExtra = next;
                sp.edit().putInt("preview_rot_extra", next).apply();
                updatePvRotInfo();
            }
        });
        pvRotBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                CameraVisionEngine.previewRotationExtra = 0;
                sp.edit().remove("preview_rot_extra").apply();
                updatePvRotInfo();
                Toast.makeText(MainActivity.this, "已恢复自动方向", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        pvRow.addView(pvRotBtn);
        root.addView(pvRow);
        updatePvRotInfo();

        // ---- 日志 ----
        TextView logTitle = new TextView(this);
        logTitle.setText("最近识别记录：");
        logTitle.setTextSize(15);
        logTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(logTitle);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTextColor(Color.parseColor("#333333"));
        logText.setBackgroundColor(Color.parseColor("#FAFAFA"));
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(logText);

        refresh();
    }

    private Trigger.Listener verdictListener() {
        return new Trigger.Listener() {
            @Override
            public void onVerdict(final String text) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };
    }

    /**
     * 表情动作单选组。两个组不能选同一个动作，冲突时自动回退到本组第一个可用项。
     */
    /**
     * v4.3：一行 = 方向名 + 动作下拉 + 阈值输入框。
     * key/otherKey 互斥（两个方向不能用同一个动作）；thKey 既可能是表情阈值也可能是手势阈值，
     * 两行共用同一个 thKey 时，两个输入框显示同一份数值（手势只有一个「检测置信度」）。
     */
    /**
     * v5.3：一行 = 方向名 + 动作下拉 + 阈值输入框。
     * 下拉切换动作时，若该动作带推荐阈值（opts[pos][2]），自动把阈值填成推荐值并保存——
     * 不同动作的分数分布差很多（张嘴 ~0.6、龇牙 ~0.3），沿用旧阈值必然不好用。
     */
    private View actionRow(final String label, final String key, final String[][] opts,
                           final String def, final String otherKey,
                           final String thKey, final float thDef,
                           final float thMin, final float thMax) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f));
        row.addView(name);

        // 阈值输入框先建，下拉回调里要改它
        final EditText th = new EditText(this);
        th.setText(String.format(java.util.Locale.US, "%g", sp.getFloat(thKey, thDef)));
        th.setTextSize(12);
        th.setSingleLine(true);
        th.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        th.setGravity(Gravity.CENTER);
        th.setHint("阈值");
        th.setLayoutParams(new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        th.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                try {
                    float v = Float.parseFloat(s.toString().trim());
                    if (v < thMin) {
                        v = thMin;
                    }
                    if (v > thMax) {
                        v = thMax;
                    }
                    sp.edit().putFloat(thKey, v).apply();
                } catch (Exception ignore) {
                }
            }
        });

        final Spinner spinner = new Spinner(this);
        String[] names = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            names[i] = opts[i][0];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String cur = sp.getString(key, def);
        for (int i = 0; i < opts.length; i++) {
            if (opts[i][1].equals(cur)) {
                spinner.setSelection(i);
                break;
            }
        }
        spinner.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));

        final boolean[] suppress = {false};
        // Spinner 在布局完成后会对「当前选项」回调一次，若不过滤会把用户已调好的阈值
        // 覆盖成推荐值——每次打开 App 都被重置，必须跳过首次。
        final boolean[] firstCallback = {true};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (suppress[0]) {
                    return;
                }
                if (firstCallback[0]) {
                    firstCallback[0] = false;
                    return;
                }
                String act = opts[pos][1];
                String other = sp.getString(otherKey, "");
                if (act.equals(other)) {
                    Toast.makeText(MainActivity.this,
                            "「下一个」和「上一个」不能用同一个动作", Toast.LENGTH_LONG).show();
                    String saved = sp.getString(key, def);
                    for (int i = 0; i < opts.length; i++) {
                        if (opts[i][1].equals(saved)) {
                            suppress[0] = true;
                            spinner.setSelection(i);
                            suppress[0] = false;
                            break;
                        }
                    }
                    return;
                }
                sp.edit().putString(key, act).apply();
                if (opts[pos].length > 2) {
                    try {
                        float rec = Float.parseFloat(opts[pos][2]);
                        th.setText(String.format(java.util.Locale.US, "%g", rec));
                        sp.edit().putFloat(thKey, rec).apply();
                    } catch (Exception ignore) {
                    }
                }
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        row.addView(spinner);
        row.addView(th);
        return row;
    }

    private View switchRow(String label, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        row.addView(sw);
        return row;
    }

    private View switchRow(String label, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch s = new Switch(this);
        s.setChecked(sp.getBoolean(key, def));
        s.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(key, isChecked).apply();
                refresh();
            }
        });
        row.addView(t);
        row.addView(s);
        return row;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Trigger.appVisible = true;
        if (previewSwitch != null && previewSwitch.isChecked()) {
            CameraVisionEngine.previewListener = previewListener;
        }
        refresh();
        uiHandler.postDelayed(logRefresher, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Trigger.appVisible = false;
        // 界面不可见时停止接收预览帧（避免无谓的缩图/GC）
        CameraVisionEngine.previewListener = null;
        uiHandler.removeCallbacks(logRefresher);
    }

    private final Runnable logRefresher = new Runnable() {
        @Override
        public void run() {
            if (logText != null) {
                logText.setText(EventLog.dump());
            }
            if (++refreshTick % 3 == 0) {
                refreshStatusOnly();
            }
            uiHandler.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        refreshStatusOnly();
        refreshChannel();
        if (logText != null) {
            logText.setText(EventLog.dump());
        }
    }

    private void refreshStatusOnly() {
        boolean camPerm = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean faceMode = sp.getBoolean("face_ctrl", true);

        StringBuilder sb = new StringBuilder("状态：\n");
        sb.append("· 识别方式：").append(faceMode ? "面部表情" : "手势").append("\n");
        if (faceMode) {
            String nAct = actionName(sp.getString("face_next", "jawOpen"));
            String pAct = actionName(sp.getString("face_prev", "mouthPucker"));
            float tn = sp.getFloat("face_th_next", 0.55f);
            float tp = sp.getFloat("face_th_prev", 0.50f);
            sb.append("· 动作：").append(nAct).append("(").append(fmtTh(tn)).append(") → 下一个，")
                    .append(pAct).append("(").append(fmtTh(tp)).append(") → 上一个（保持 0.5 秒）\n");
        } else {
            sb.append("· 动作：").append(fingerName(sp.getString("gesture_next", "1")))
                    .append(" → 下一个，").append(fingerName(sp.getString("gesture_prev", "2")))
                    .append(" → 上一个（保持 0.4 秒）\n");
            sb.append("· 检测阈值：").append(fmtTh(sp.getFloat("gesture_th", 0.35f)))
                    .append("（手抬到脸旁、掌心朝镜头、30~60cm）\n");
        }
        sb.append("· 采集：").append(sp.getBoolean("high_accuracy", false)
                ? "高精度 960×720" : "标准 640×480")
                .append(" · 放大 ").append(String.format(java.util.Locale.US, "%.1fx",
                        sp.getFloat("zoom_factor", 1.0f))).append("\n");
        sb.append("· 摄像头权限：").append(camPerm ? "已授权" : "⚠ 未授权（点下方按钮）").append("\n");
        sb.append("· 引擎：").append(!camPerm ? "未启动（缺权限）"
                : (VisionService.engineReady ? "识别中 ✓" : "未启动（点「开启识别」）")).append("\n");
        sb.append("· 最近识别：").append(VisionService.lastAction.isEmpty() ? "（还没有）" : VisionService.lastAction);
        status.setText(sb.toString());
    }

    private static String fingerName(String v) {
        return v + " 根手指";
    }

    private static String fmtTh(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String actionName(String act) {
        if ("jawOpen".equals(act)) {
            return "张嘴";
        }
        if ("mouthPucker".equals(act)) {
            return "撅嘴";
        }
        if ("mouthSmile".equals(act)) {
            return "露牙笑";
        }
        if ("teethBare".equals(act)) {
            return "龇牙";
        }
        if ("cheekPuff".equals(act)) {
            return "鼓腮";
        }
        if ("browInnerUp".equals(act)) {
            return "抬眉";
        }
        if ("eyeSquint".equals(act)) {
            return "眯眼";
        }
        if ("eyeWide".equals(act)) {
            return "睁大眼";
        }
        return act;
    }

    private void refreshChannel() {
        if (linkStatus == null) {
            return;
        }
        boolean ok;
        String text;
        String url = sp.getString("shortx_url_next", "");
        ok = ShortX.available(this);
        if (!ok) {
            text = "DeepLink 通道：ShortX 未检测到 ✗——请确认已安装并在 LSPosed 启用";
        } else if (url.isEmpty()) {
            text = "DeepLink 通道：⚠ 上方链接还没填——请粘贴 shortx://run?id=... ";
        } else if (sp.getString("shortx_url_prev", "").isEmpty()) {
            text = "DeepLink 通道：⚠「上一个」链接还没填（只能翻下一个）";
        } else {
            text = "DeepLink 通道：就绪 ✓（两个链接都已填）";
        }
        linkStatus.setText(text);
        linkStatus.setTextColor(Color.parseColor(ok ? "#0B6E00" : "#B00000"));
    }

    /** v4.8：显示当前预览方向（-1 = 自动） */
    private void updatePvRotInfo() {
        if (pvRotInfo == null) {
            return;
        }
        int ex = CameraVisionEngine.previewRotationExtra;
        pvRotInfo.setText(ex == 0
                ? "画面方向：自动（若上下颠倒，点右边按钮，每点一次转 90°）"
                : "画面方向：自动 +" + ex + "°（长按按钮恢复自动）");
    }

    private void clearPreview() {
        if (previewImg != null) {
            previewImg.setImageDrawable(null);
        }
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
