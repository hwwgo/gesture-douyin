package com.handsfree.douyin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * v5.6：手势切抖音（纯前置摄像头版）。
 *
 * 界面按「卡片 + 可折叠分组」重做：首屏只有状态、主控按钮和分组入口，
 * 详细设置全部收进折叠区，避免一打开就是一大段说明文字和一堆开关混在一起。
 *
 * 功能与 v5.5 一致：视觉方式（手势/表情）、表情动作自定义、冷却、执行通道、反馈开关、日志。
 * 新增：「停止识别并退出」——停掉识别服务并关闭界面。
 */
public class MainActivity extends Activity {

    private static final String PREF = "hf";

    // ---------------- 配色 ----------------
    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF16181D;
    private static final int C_SUB = 0xFF8A8F99;
    private static final int C_LINE = 0xFFEDEFF2;
    private static final int C_ACCENT = 0xFFFE2C55;
    private static final int C_GREEN = 0xFF0BBF5E;
    private static final int C_DANGER = 0xFFE5484D;
    private static final int C_TRACK = 0xFFF1F2F4;

    private SharedPreferences sp;

    // ---------------- 状态卡 ----------------
    private LinearLayout statusCard;
    private View stateDot;
    private TextView stateText;
    private TextView stateMode;
    private TextView stateDetail;
    private TextView stateLast;

    // ---------------- 主控 ----------------
    private Button startBtn;
    private Button stopBtn;
    private Button exitBtn;

    // ---------------- 分组 ----------------
    private Section secMode;
    private Section secAction;
    private Section secTrigger;
    private Section secShortX;
    private Section secPreview;

    // ---------------- 其它控件 ----------------
    private ImageView previewImg;
    private Switch previewSwitch;
    private Bitmap previewBitmap;
    private TextView pvRotInfo;
    private TextView linkStatus;
    private TextView logText;
    private TextView segGesture;
    private TextView segFace;
    /** 0=未授权 1=未启动 2=识别中，仅在变化时重设状态卡背景 */
    private int lastStateCode = -1;
    private final Handler uiHandler = new Handler();
    private int refreshTick = 0;

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

        // 去掉系统标题栏后，把状态栏/导航栏刷成同一底色 + 深色图标，避免顶部一条深色横条
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        int vis = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(vis);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        buildHeader(root);
        buildStatusCard(root);
        buildControls(root);
        buildDouyinCard(root);

        buildModeSection(root);
        buildActionSection(root);
        buildTriggerSection(root);
        buildShortXSection(root);
        buildPreviewSection(root);
        buildLogSection(root);
        buildDebugSection(root);

        TextView footer = text("v5.6 · 前置摄像头识别 · 不使用麦克风 / root", 11, C_SUB, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(10), 0, 0);
        root.addView(footer);

        refresh();
    }

    // ==================== 各区块 ====================

    private void buildHeader(LinearLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(8), dp(4), dp(14));
        root.addView(box);

        TextView t = text("手势切抖音", 25, C_TEXT, true);
        box.addView(t);

        TextView s = text("前置摄像头 · 比手势或做表情，抖音自动翻页", 12, C_SUB, false);
        s.setPadding(0, dp(3), 0, 0);
        box.addView(s);
    }

    private void buildStatusCard(LinearLayout root) {
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.setBackground(roundRect(C_CARD, 16, C_LINE, 1));
        statusCard.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        statusCard.setLayoutParams(lp);
        root.addView(statusCard);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(row);

        stateDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.rightMargin = dp(9);
        stateDot.setLayoutParams(dotLp);
        stateDot.setBackground(circle(0xFFC7CBD1));
        row.addView(stateDot);

        stateText = text("未启动", 17, C_TEXT, true);
        stateText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(stateText);

        stateMode = text("表情模式", 12, C_SUB, false);
        row.addView(stateMode);

        stateDetail = text("", 12, C_SUB, false);
        stateDetail.setPadding(0, dp(6), 0, 0);
        statusCard.addView(stateDetail);

        stateLast = text("", 12, 0xFF44484F, false);
        stateLast.setPadding(0, dp(4), 0, 0);
        statusCard.addView(stateLast);
    }

    private void buildControls(LinearLayout root) {
        startBtn = primaryButton("开启识别");
        startBtn.setOnClickListener(new View.OnClickListener() {
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

        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        startLp.bottomMargin = dp(10);
        startBtn.setLayoutParams(startLp);
        root.addView(startBtn);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(12);
        row.setLayoutParams(rowLp);
        root.addView(row);

        stopBtn = ghostButton("停止识别");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, VisionService.class));
                refresh();
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp1.rightMargin = dp(10);
        stopBtn.setLayoutParams(lp1);
        row.addView(stopBtn);

        // v5.6：停止识别并退出——先停服务，再关闭界面
        exitBtn = softDangerButton("停止识别并退出");
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, VisionService.class));
                Toast.makeText(MainActivity.this, "已停止识别，正在退出", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        exitBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        row.addView(exitBtn);
    }

    private void buildDouyinCard(LinearLayout root) {
        final String dyPkg = findDouyin();
        final Button openDouyin = new Button(this);
        openDouyin.setAllCaps(false);
        openDouyin.setText(dyPkg == null ? "打开抖音（未安装）" : "打开" + douyinName(dyPkg));
        openDouyin.setTextSize(15);
        openDouyin.setTextColor(C_ACCENT);
        openDouyin.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        openDouyin.setBackground(roundRect(0xFFFFF0F3, 14, 0xFFFFD6DE, 1));
        openDouyin.setMinHeight(0);
        openDouyin.setMinimumHeight(0);
        openDouyin.setMinWidth(0);
        openDouyin.setMinimumWidth(0);
        openDouyin.setStateListAnimator(null);
        openDouyin.setPadding(dp(16), 0, dp(16), 0);
        openDouyin.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        openDouyin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 每次点击重查：新装/卸载抖音都能即时反映
                String pkg = findDouyin();
                if (pkg == null) {
                    openDouyin.setText("打开抖音（未安装）");
                    Toast.makeText(MainActivity.this,
                            "没找到抖音 / 抖音极速版，请先安装", Toast.LENGTH_LONG).show();
                    return;
                }
                openDouyin.setText("打开" + douyinName(pkg));
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    Toast.makeText(MainActivity.this,
                            "启动失败：系统没有返回启动入口", Toast.LENGTH_LONG).show();
                    return;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(launch);
                    EventLog.add("手动打开 " + douyinName(pkg));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "启动失败：" + e, Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(openDouyin);

        TextView hint = text("识别开着时点这里进抖音，手势 / 表情照样生效（极速版优先）", 11, C_SUB, false);
        hint.setPadding(dp(4), dp(6), dp(4), dp(12));
        root.addView(hint);
    }

    private void buildModeSection(LinearLayout root) {
        secMode = addSection(root, "识别方式");
        LinearLayout body = secMode.body;

        segGesture = segmentItem("手势");
        segFace = segmentItem("面部表情");
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(roundRect(C_TRACK, 12, 0, 0));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams sg = new LinearLayout.LayoutParams(0, dp(38), 1f);
        sg.rightMargin = dp(4);
        seg.addView(segGesture, sg);
        seg.addView(segFace, new LinearLayout.LayoutParams(0, dp(38), 1f));
        body.addView(seg);

        // 首次使用默认「表情」模式（张嘴比比手势更省事）
        if (!sp.contains("face_ctrl") && !sp.contains("gesture_cam")) {
            sp.edit().putBoolean("face_ctrl", true).putBoolean("gesture_cam", false).apply();
        }
        segGesture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFaceMode(false);
            }
        });
        segFace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFaceMode(true);
            }
        });
        applyModeSegment();

        TextView tip = hint("手机斜靠台面正对自己；5fps 采样、约 0.5~0.8 秒响应。"
                + "息屏会自动停止识别（等同手动停止，亮屏后不自动恢复）。切换后请「停止识别」再「开启识别」。");
        tip.setPadding(0, dp(10), 0, 0);
        body.addView(tip);

        body.addView(divider());

        body.addView(switchRow("高精度模式", "high_accuracy", false));
        TextView hdTip = hint("按 960×720 采集（像素量 2.2 倍）：手离得远、光线偏暗时更容易认对，代价是耗电上升。"
                + "改完需重开识别。");
        body.addView(hdTip);

        body.addView(divider());

        LinearLayout zmHead = new LinearLayout(this);
        zmHead.setOrientation(LinearLayout.HORIZONTAL);
        zmHead.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(zmHead);

        TextView zmLabel = text("画面放大", 14, C_TEXT, false);
        zmLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        zmHead.addView(zmLabel);

        final TextView zmVal = text(String.format(java.util.Locale.US, "%.1fx",
                sp.getFloat("zoom_factor", 1.0f)), 14, C_ACCENT, true);
        zmVal.setGravity(Gravity.END);
        zmHead.addView(zmVal);

        SeekBar zmBar = new SeekBar(this);
        float z0 = sp.getFloat("zoom_factor", 1.0f);
        zmBar.setMax(30); // 1.0 ~ 4.0，步长 0.1
        zmBar.setProgress(Math.round((z0 - 1.0f) * 10));
        zmBar.setPadding(0, dp(4), 0, dp(4));
        zmBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
        body.addView(zmBar);

        body.addView(hint("离得远就调大：只取画面中心放大，人脸和手在模型里更大、更容易认，"
                + "代价是视野变窄（2.0x 只剩中心 1/4）。超过 3x 会明显变糊，建议先挪近再微调。"));
    }

    private void buildActionSection(LinearLayout root) {
        secAction = addSection(root, "动作设置");
        LinearLayout body = secAction.body;

        body.addView(subTitle("手势动作"));
        body.addView(hint("伸 1~5 根手指并保持 0.4 秒。手抬到脸旁、掌心朝镜头、离 30~60cm，握拳不触发。"
                + "右侧「阈值」是手部检测置信度，调低更灵敏也更容易误判。"));
        body.addView(actionRow("下一个", "gesture_next", FINGER_ACTIONS, "1", "gesture_prev",
                "gesture_th", 0.35f, 0.10f, 0.90f));
        body.addView(actionRow("上一个", "gesture_prev", FINGER_ACTIONS, "2", "gesture_next",
                "gesture_th", 0.35f, 0.10f, 0.90f));

        body.addView(divider());

        body.addView(subTitle("表情动作"));
        body.addView(hint("保持 0.5 秒触发，两个方向可各选一种（也能相同）。切换动作时阈值会自动填成推荐值；"
                + "难触发调低 0.05，容易误触发调高 0.05。日志会显示实时分数。"));
        body.addView(faceCatalogCard());
        body.addView(actionRow("下一个", "face_next", FACE_ACTIONS, "jawOpen", "face_prev",
                "face_th_next", 0.55f, 0.05f, 0.95f));
        body.addView(actionRow("上一个", "face_prev", FACE_ACTIONS, "mouthPucker", "face_next",
                "face_th_prev", 0.50f, 0.05f, 0.95f));
    }

    private void buildTriggerSection(LinearLayout root) {
        secTrigger = addSection(root, "触发与反馈");
        LinearLayout body = secTrigger.body;

        body.addView(subTitle("触发冷却"));
        body.addView(hint("两次翻页之间的最短间隔。调小可更快连续翻页，0 = 不冷却。"));
        final EditText etCooldown = new EditText(this);
        etCooldown.setText(String.format(java.util.Locale.US, "%g",
                sp.getLong("cooldown_ms", 1500) / 1000.0));
        etCooldown.setTextSize(13);
        etCooldown.setSingleLine(true);
        etCooldown.setGravity(Gravity.CENTER_VERTICAL);
        etCooldown.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etCooldown.setHint("秒，默认 1.5");
        etCooldown.setBackground(roundRect(C_TRACK, 10, 0, 0));
        etCooldown.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cdLp.topMargin = dp(6);
        etCooldown.setLayoutParams(cdLp);
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
                if (secTrigger != null) {
                    secTrigger.summary("冷却 " + String.format(java.util.Locale.US, "%g", ms / 1000.0) + "s");
                }
            }
        });
        body.addView(etCooldown);

        body.addView(divider());

        body.addView(switchRow("震动", "fb_vibrate", true));
        body.addView(hint("识别到动作两短震、拉起 ShortX 单击、失败长震。"));
    }

    private void buildShortXSection(LinearLayout root) {
        secShortX = addSection(root, "ShortX 执行通道");
        LinearLayout body = secShortX.body;

        body.addView(hint("翻页命令通过 shortx:// 链接交给 ShortX 执行，需要：① ShortX 已安装并在 LSPosed 启用；"
                + "② 本 App 有「后台弹出界面」或「悬浮窗」权限。"));

        // 固定为 deeplink（兼容老配置里残留的 notify）
        if (!"deeplink".equals(sp.getString("exec_channel", ""))) {
            sp.edit().putString("exec_channel", "deeplink").apply();
        }

        body.addView(subTitle("「下一个」链接"));
        final EditText etNextUrl = urlField("shortx_url_next", "shortx://run?id=DA-xxxx");
        body.addView(etNextUrl);

        body.addView(subTitle("「上一个」链接"));
        final EditText etPrevUrl = urlField("shortx_url_prev", "shortx://run?id=DA-yyyy");
        body.addView(etPrevUrl);

        linkStatus = text("", 12, C_SUB, false);
        linkStatus.setPadding(0, dp(10), 0, 0);
        body.addView(linkStatus);
    }

    private void buildPreviewSection(LinearLayout root) {
        secPreview = addSection(root, "摄像头预览");
        LinearLayout body = secPreview.body;

        previewSwitch = new Switch(this);
        previewSwitch.setChecked(false);
        styleSwitch(previewSwitch);
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
                        if (secPreview != null) {
                            secPreview.summary(on ? "已开启" : "关闭");
                        }
                    }
                });
        body.addView(switchRow("显示摄像头画面", previewSwitch));
        body.addView(hint("显示的是识别引擎实际处理的那一帧，不额外开相机，不会和识别抢设备。"
                + "画面为竖屏正立方向、未做镜像（摄像头真实所见）。"));

        FrameLayout pvBox = new FrameLayout(this);
        pvBox.setBackground(roundRect(0xFF000000, 12, 0, 0));
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210));
        pvLp.topMargin = dp(10);
        pvBox.setLayoutParams(pvLp);
        previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImg.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pvBox.addView(previewImg);
        body.addView(pvBox);

        // v4.8：画面方向微调（各厂商前置方向定义不同，自动值可能上下颠倒）
        LinearLayout pvRow = new LinearLayout(this);
        pvRow.setOrientation(LinearLayout.HORIZONTAL);
        pvRow.setGravity(Gravity.CENTER_VERTICAL);
        pvRow.setPadding(0, dp(8), 0, 0);
        body.addView(pvRow);

        pvRotInfo = text("", 11, C_SUB, false);
        pvRotInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pvRow.addView(pvRotInfo);

        Button pvRotBtn = smallButton("方向 +90°");
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
        updatePvRotInfo();
    }

    private void buildLogSection(LinearLayout root) {
        Section secLog = addSection(root, "最近识别记录");
        LinearLayout body = secLog.body;

        logText = text("", 11, 0xFF33363C, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setLineSpacing(dp(2), 1f);
        logText.setBackground(roundRect(0xFFFAFBFC, 10, C_LINE, 1));
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        body.addView(logText);

        Button clear = smallButton("清空记录");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(96), dp(34));
        clp.topMargin = dp(10);
        clp.gravity = Gravity.END;
        clear.setLayoutParams(clp);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EventLog.clear();
                if (logText != null) {
                    logText.setText(EventLog.dump());
                }
            }
        });
        body.addView(clear);
    }

    private void buildDebugSection(LinearLayout root) {
        Section secDebug = addSection(root, "调试与权限");
        LinearLayout body = secDebug.body;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(row);

        Button tNext = smallButton("测试「下一个」");
        tNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Trigger.next(MainActivity.this, "测试按钮", verdictListener());
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp1.rightMargin = dp(8);
        tNext.setLayoutParams(lp1);
        row.addView(tNext);

        Button tPrev = smallButton("测试「上一个」");
        tPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Trigger.prev(MainActivity.this, "测试按钮", verdictListener());
            }
        });
        tPrev.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        row.addView(tPrev);

        Button grantCam = smallButton("授予摄像头权限（必须）");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        glp.topMargin = dp(10);
        grantCam.setLayoutParams(glp);
        grantCam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
            }
        });
        body.addView(grantCam);
    }

    // ==================== 控件工厂 ====================

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12, C_SUB, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView subTitle(String s) {
        TextView t = text(s, 13, C_TEXT, true);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        }
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundRect(C_ACCENT, 14, 0, 0));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private Button ghostButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(C_TEXT);
        b.setBackground(roundRect(C_TRACK, 14, 0, 0));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private Button softDangerButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(C_DANGER);
        b.setBackground(roundRect(0xFFFFF1F2, 14, 0xFFFFD9DE, 1));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(0xFF44484F);
        b.setBackground(roundRect(C_TRACK, 10, 0, 0));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private TextView segmentItem(String label) {
        TextView t = text(label, 14, C_SUB, true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void styleSwitch(Switch sw) {
        sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{C_ACCENT, 0xFFD9DCE1}));
    }

    private EditText urlField(final String key, String hintText) {
        final EditText et = new EditText(this);
        et.setText(sp.getString(key, ""));
        et.setTextSize(12);
        et.setSingleLine(true);
        et.setGravity(Gravity.CENTER_VERTICAL);
        et.setHint(hintText);
        et.setBackground(roundRect(C_TRACK, 10, 0, 0));
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        et.setLayoutParams(lp);
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sp.edit().putString(key, s.toString().trim()).apply();
                refreshChannel();
            }
        });
        return et;
    }

    // ==================== 折叠分组 ====================

    private class Section {
        final LinearLayout body;
        final TextView summaryText;
        final TextView arrow;
        boolean open;

        Section(LinearLayout body, TextView summaryText, TextView arrow, boolean open) {
            this.body = body;
            this.summaryText = summaryText;
            this.arrow = arrow;
            this.open = open;
        }

        void toggle() {
            open = !open;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "▾" : "▸");
        }

        void summary(String s) {
            summaryText.setText(s == null ? "" : s);
        }
    }

    private Section addSection(LinearLayout root, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(C_CARD, 16, C_LINE, 1));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);
        root.addView(card);

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.addView(head);

        TextView t = text(title, 15, C_TEXT, true);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);

        final TextView summary = text("", 12, C_SUB, false);
        head.addView(summary);

        final TextView arrow = text("▸", 12, C_SUB, false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        arrow.setLayoutParams(alp);
        head.addView(arrow);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(16));
        body.setVisibility(View.GONE);
        card.addView(body);

        final Section sec = new Section(body, summary, arrow, false);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sec.toggle();
            }
        });
        return sec;
    }

    // ==================== 配置行 ====================

    private View switchRow(String label, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(6));
        TextView t = text(label, 14, C_TEXT, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        Switch s = new Switch(this);
        s.setChecked(sp.getBoolean(key, def));
        styleSwitch(s);
        s.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(key, isChecked).apply();
                refresh();
            }
        });
        row.addView(s);
        return row;
    }

    private View switchRow(String label, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));
        TextView t = text(label, 14, C_TEXT, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        row.addView(sw);
        return row;
    }

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
        row.setPadding(0, dp(8), 0, dp(8));

        TextView name = text(label, 14, C_TEXT, false);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
        row.addView(name);

        // 阈值输入框先建，下拉回调里要改它
        final EditText th = new EditText(this);
        th.setText(String.format(java.util.Locale.US, "%g", sp.getFloat(thKey, thDef)));
        th.setTextSize(12);
        th.setSingleLine(true);
        th.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        th.setGravity(Gravity.CENTER);
        th.setHint("阈值");
        th.setBackground(roundRect(C_TRACK, 9, 0, 0));
        th.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(dp(62),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp.leftMargin = dp(8);
        th.setLayoutParams(thLp);
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
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));

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

    // ==================== 状态刷新 ====================

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
        refreshSummaries();
        if (logText != null) {
            logText.setText(EventLog.dump());
        }
    }

    private void refreshSummaries() {
        boolean faceMode = sp.getBoolean("face_ctrl", true);
        if (secMode != null) {
            secMode.summary(faceMode ? "面部表情" : "手势");
        }
        if (secAction != null) {
            if (faceMode) {
                secAction.summary(actionName(sp.getString("face_next", "jawOpen"))
                        + " / " + actionName(sp.getString("face_prev", "mouthPucker")));
            } else {
                secAction.summary(fingerName(sp.getString("gesture_next", "1"))
                        + " / " + fingerName(sp.getString("gesture_prev", "2")));
            }
        }
        if (secTrigger != null) {
            secTrigger.summary("冷却 " + String.format(java.util.Locale.US, "%g",
                    sp.getLong("cooldown_ms", 1500) / 1000.0) + "s"
                    + (sp.getBoolean("fb_vibrate", true) ? " · 震动" : ""));
        }
        if (secPreview != null && previewSwitch != null) {
            secPreview.summary(previewSwitch.isChecked() ? "已开启" : "关闭");
        }
        applyModeSegment();
    }

    private void refreshStatusOnly() {
        if (stateText == null) {
            return;
        }
        boolean camPerm = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean faceMode = sp.getBoolean("face_ctrl", true);
        boolean running = camPerm && VisionService.engineReady;

        int bgColor;
        int strokeColor;
        int dotColor;
        String state;
        int code;
        if (!camPerm) {
            bgColor = 0xFFFFF8F3;
            strokeColor = 0xFFFFE2C7;
            dotColor = 0xFFFF9500;
            state = "缺少摄像头权限";
            code = 0;
        } else if (running) {
            bgColor = 0xFFF1FBF5;
            strokeColor = 0xFFC9EFD8;
            dotColor = C_GREEN;
            state = "识别中";
            code = 2;
        } else {
            bgColor = C_CARD;
            strokeColor = C_LINE;
            dotColor = 0xFFC7CBD1;
            state = "未启动";
            code = 1;
        }
        if (code != lastStateCode) {
            lastStateCode = code;
            statusCard.setBackground(roundRect(bgColor, 16, strokeColor, 1));
            stateDot.setBackground(circle(dotColor));
        }
        stateText.setText(state);
        stateMode.setText(faceMode ? "表情模式" : "手势模式");

        String zoom = String.format(java.util.Locale.US, "%.1fx", sp.getFloat("zoom_factor", 1.0f));
        stateDetail.setText("采集 " + (sp.getBoolean("high_accuracy", false) ? "960×720" : "640×480")
                + " · 放大 " + zoom
                + " · 冷却 " + String.format(java.util.Locale.US, "%g",
                sp.getLong("cooldown_ms", 1500) / 1000.0) + "s");

        String last = VisionService.lastAction;
        stateLast.setText(last == null || last.isEmpty() ? "还没有识别到动作" : "最近：" + last);

        if (startBtn != null) {
            startBtn.setEnabled(!running);
            startBtn.setAlpha(running ? 0.45f : 1f);
        }
        if (stopBtn != null) {
            stopBtn.setAlpha(running ? 1f : 0.6f);
        }
        if (exitBtn != null) {
            exitBtn.setAlpha(1f);
        }
    }

    private void setFaceMode(boolean face) {
        sp.edit().putBoolean("face_ctrl", face).putBoolean("gesture_cam", !face).apply();
        applyModeSegment();
        refreshSummaries();
        refreshStatusOnly();
        if (VisionService.engineReady) {
            Toast.makeText(MainActivity.this,
                    "已切换识别方式，请「停止识别」再「开启识别」使其生效", Toast.LENGTH_LONG).show();
        }
    }

    private void applyModeSegment() {
        if (segGesture == null || segFace == null) {
            return;
        }
        boolean face = sp.getBoolean("face_ctrl", true);
        segGesture.setBackground(face ? null : roundRect(C_CARD, 9, 0, 0));
        segGesture.setTextColor(face ? C_SUB : C_TEXT);
        segFace.setBackground(face ? roundRect(C_CARD, 9, 0, 0) : null);
        segFace.setTextColor(face ? C_TEXT : C_SUB);
        segGesture.setElevation(face ? 0 : dp(1));
        segFace.setElevation(face ? dp(1) : 0);
    }

    private static String fingerName(String v) {
        return v + " 根手指";
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
            text = "通道状态：ShortX 未检测到 ✗——请确认已安装并在 LSPosed 启用";
        } else if (url.isEmpty()) {
            text = "通道状态：⚠ 上方链接还没填";
        } else if (sp.getString("shortx_url_prev", "").isEmpty()) {
            text = "通道状态：⚠「上一个」链接还没填（只能翻下一个）";
        } else {
            text = "通道状态：就绪 ✓";
        }
        linkStatus.setText(text);
        linkStatus.setTextColor(ok ? C_GREEN : C_DANGER);
        if (secShortX != null) {
            if (!ok) {
                secShortX.summary("未连接");
            } else if (url.isEmpty() || sp.getString("shortx_url_prev", "").isEmpty()) {
                secShortX.summary("待配置");
            } else {
                secShortX.summary("就绪");
            }
        }
    }

    /** v4.8：显示当前预览方向（0 = 自动） */
    private void updatePvRotInfo() {
        if (pvRotInfo == null) {
            return;
        }
        int ex = CameraVisionEngine.previewRotationExtra;
        pvRotInfo.setText(ex == 0
                ? "方向：自动（颠倒时点右边）"
                : "方向：自动 +" + ex + "°（长按恢复）");
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

    // ---------------- v5.4：一键启动抖音 ----------------

    /** 抖音极速版（日常使用，优先） */
    private static final String PKG_DOUYIN_LITE = "com.ss.android.ugc.aweme.lite";
    /** 抖音正式版 */
    private static final String PKG_DOUYIN = "com.ss.android.ugc.aweme";

    /**
     * 找可启动的抖音：极速版优先，其次正式版；都没装返回 null。
     * 注意 Android 11+ 必须在 manifest 的 &lt;queries&gt; 里声明这两个包名，
     * 否则 getLaunchIntentForPackage 一律返回 null，装了也认不出。
     */
    private String findDouyin() {
        PackageManager pm = getPackageManager();
        if (pm.getLaunchIntentForPackage(PKG_DOUYIN_LITE) != null) {
            return PKG_DOUYIN_LITE;
        }
        if (pm.getLaunchIntentForPackage(PKG_DOUYIN) != null) {
            return PKG_DOUYIN;
        }
        return null;
    }

    private static String douyinName(String pkg) {
        return PKG_DOUYIN_LITE.equals(pkg) ? "抖音极速版" : "抖音";
    }

    /**
     * v5.5：「支持哪些动作」全部列出来。
     * 下拉不展开就看不到清单，用户不知道还有什么能用，这里直接摊在界面上：
     * 动作名 + 怎么做 + 推荐阈值（推荐值就是切动作时自动填入的那个数）。
     */
    private View faceCatalogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(0xFFF7F8FA, 10, 0, 0));
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        card.addView(catalogHead("支持的全部表情动作（共 8 种）"));
        for (String[] a : FACE_ACTIONS) {
            card.addView(catalogRow(a[0], faceHint(a[1]), "推荐 " + a[2]));
        }

        TextView note = text("「推荐」= 实测比较好触发的阈值，切换动作时会自动填进右侧输入框，也可以自己改。",
                11, C_SUB, false);
        note.setPadding(0, dp(8), 0, 0);
        note.setLineSpacing(dp(2), 1f);
        card.addView(note);
        return card;
    }

    /** 每个表情动作「怎么做」的一句话说明，键名与 FACE_ACTIONS 第二列一致 */
    private static String faceHint(String key) {
        if ("jawOpen".equals(key)) {
            return "张开嘴（像说「啊」）";
        }
        if ("mouthSmile".equals(key)) {
            return "咧嘴笑，两边嘴角上扬";
        }
        if ("teethBare".equals(key)) {
            return "上唇上提 + 下唇下拉，露出上下牙";
        }
        if ("mouthPucker".equals(key)) {
            return "嘴唇向前收拢（嘟嘴 / 亲嘴）";
        }
        if ("cheekPuff".equals(key)) {
            return "腮帮鼓起（嘴里含气）";
        }
        if ("browInnerUp".equals(key)) {
            return "眉毛内侧往上抬（惊讶）";
        }
        if ("eyeSquint".equals(key)) {
            return "眼睛眯成一条缝";
        }
        if ("eyeWide".equals(key)) {
            return "眼睛睁大瞪圆";
        }
        return "";
    }

    private TextView catalogHead(String text) {
        TextView t = text(text, 12, 0xFF33363C, true);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private View catalogRow(String name, String desc, String th) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView n = text(name, 12, C_TEXT, false);
        n.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
        row.addView(n);

        TextView d = text(desc, 11, C_SUB, false);
        d.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2.4f));
        row.addView(d);

        TextView t = text(th, 11, 0xFF00796B, false);
        t.setGravity(Gravity.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(58),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(t);
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
