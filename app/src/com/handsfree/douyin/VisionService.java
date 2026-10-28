package com.handsfree.douyin;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * v4.0：纯视觉控制服务（前置摄像头手势 / 面部表情），**不再包含任何语音功能**。
 *
 * 与 v3.x 的差别：
 * - 删除 Vosk / sherpa-KWS / 麦克风采集 / 口令匹配 / 语音自触发静音窗 / 连续触发熔断
 * - 只保留：相机引擎生命周期、冷却、抖音前台门禁、ShortX 触发、震动/提示音反馈、息屏策略
 * - 表情动作由用户配置（prefs face_next / face_prev，blendshape 名）
 *
 * 摄像头在后台只能由前台服务访问（Android 11+），所以服务类型声明为 camera，
 * 且必须「应用可见时启动」——即从 App 点开启，再切到抖音。
 */
public class VisionService extends Service implements CameraVisionEngine.Callback {

    public static final String ACTION_START = "com.handsfree.douyin.VISION_START";
    public static final String ACTION_STOP = "com.handsfree.douyin.VISION_STOP";

    private static final String PREF = "hf";
    private static final String CHANNEL_ID = "vision";
    private static final int NOTIF_ID = 1;
    private static final long DEFAULT_COOLDOWN_MS = 1500;

    /** 引擎是否运行中（界面状态用） */
    public static volatile boolean engineReady = false;
    /** 最近识别到的动作（界面展示用） */
    public static volatile String lastAction = "";

    private SharedPreferences sp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CameraVisionEngine engine;
    private volatile boolean running = false;
    private boolean faceMode = false;
    private long cooldownMs = DEFAULT_COOLDOWN_MS;
    private long lastFireAt = 0L;
    private String lastNotifText = "";
    /** v4.3：当前生效的动作。表情=jawOpen/mouthPucker；手势=手指数 "1"~"5" */
    private String curNextAction = "jawOpen";
    private String curPrevAction = "mouthPucker";
    /** 表情阈值（用户可配） */
    private float thNext = 0.55f;
    private float thPrev = 0.50f;
    /** 手势：手部检测置信度（用户可配，需重开识别生效） */
    private float handDetConf = 0.35f;
    /** v4.6：高精度模式（960×720 采集） */
    private boolean highAccuracy = false;

    /**
     * v5.0：**息屏 = 手动停止识别**。
     *
     * 用户明确要求的语义：屏幕熄灭时不是「暂停识别」，而是跟手动点「停止识别」完全一样——
     * 服务直接结束、相机关闭、通知消失；亮屏后也不自动恢复，需要回 App 重新点「开启识别」。
     * 好处：不会在没人看的时候占相机/耗电，也不会因为锁屏后的误动作去翻页。
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && running) {
                EventLog.add("屏幕已关闭，自动停止识别（等同手动停止）");
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        createChannel();
        Feedback.init(this);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "手势切抖音", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_STICKY;
        }
        // 摄像头权限是硬前提：缺权限时前台服务（camera 类型）会直接抛 SecurityException
        if (!hasCamPermission()) {
            EventLog.add("未授予摄像头权限，服务未启动");
            updateNotif("请先授予摄像头权限（回 App 点「授予摄像头权限」）");
            stopSelf();
            return START_NOT_STICKY;
        }
        faceMode = sp.getBoolean("face_ctrl", false);
        highAccuracy = sp.getBoolean("high_accuracy", false);
        loadConfig();
        cooldownMs = sp.getLong("cooldown_ms", DEFAULT_COOLDOWN_MS);

        running = true;
        goForeground();
        EventLog.add("视觉服务启动（" + (faceMode ? "面部表情" : "手势") + "）");
        Feedback.play(this, Feedback.EV_LISTENING);
        startEngine();
        return START_STICKY;
    }

    private boolean hasCamPermission() {
        try {
            return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v4.3：读取并消毒配置。
     * 表情动作只有「张嘴 / 撅嘴」；手势动作是手指数 1~5。两个方向都不能相同。
     * 阈值：表情 0.05~0.95（blendshape 分数），手势 0.10~0.90（手部检测置信度）。
     */
    private void loadConfig() {
        if (faceMode) {
            String n = sp.getString("face_next", "jawOpen");
            String p = sp.getString("face_prev", "mouthPucker");
            if (!isFaceAction(n)) {
                n = "jawOpen";
            }
            if (!isFaceAction(p)) {
                p = "mouthPucker";
            }
            if (n.equals(p)) {
                p = "jawOpen".equals(n) ? "mouthPucker" : "jawOpen";
            }
            curNextAction = n;
            curPrevAction = p;
            thNext = clamp(sp.getFloat("face_th_next", 0.55f), 0.05f, 0.95f);
            thPrev = clamp(sp.getFloat("face_th_prev", 0.50f), 0.05f, 0.95f);
            sp.edit().putString("face_next", n).putString("face_prev", p).apply();
        } else {
            String n = sp.getString("gesture_next", "1");
            String p = sp.getString("gesture_prev", "2");
            if (!isFinger(n)) {
                n = "1";
            }
            if (!isFinger(p)) {
                p = "2";
            }
            if (n.equals(p)) {
                p = "1".equals(n) ? "2" : "1";
            }
            curNextAction = n;
            curPrevAction = p;
            handDetConf = clamp(sp.getFloat("gesture_th", 0.35f), 0.10f, 0.90f);
            sp.edit().putString("gesture_next", n).putString("gesture_prev", p).apply();
        }
    }

    /** v5.3：8 种表情动作白名单 */
    private static boolean isFaceAction(String v) {
        return "jawOpen".equals(v) || "mouthPucker".equals(v) || "mouthSmile".equals(v)
                || "teethBare".equals(v) || "cheekPuff".equals(v) || "browInnerUp".equals(v)
                || "eyeSquint".equals(v) || "eyeWide".equals(v);
    }

    private static boolean isFinger(String v) {
        return "1".equals(v) || "2".equals(v) || "3".equals(v)
                || "4".equals(v) || "5".equals(v);
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void goForeground() {
        try {
            Notification n = buildNotif("视觉引擎启动中…");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            running = false;
            EventLog.add("前台服务启动失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
            updateNotif("前台服务启动失败（" + e.getClass().getSimpleName() + "），详情见日志");
            stopSelf();
        }
    }

    // ---------------- 引擎生命周期 ----------------

    private void startEngine() {
        if (!running) {
            return;
        }
        if (engine == null) {
            engine = new CameraVisionEngine(this,
                    faceMode ? CameraVisionEngine.MODE_FACE : CameraVisionEngine.MODE_GESTURE,
                    curNextAction,
                    curPrevAction,
                    thNext,
                    thPrev,
                    handDetConf,
                    highAccuracy,
                    this);
            updateNotif("正在加载视觉模型（" + (faceMode ? "表情" : "手势")
                    + (highAccuracy ? " · 高精度" : "") + "）…");
            engine.initAsync();
            // 看门狗：模型加载/初始化异常时自动停服，避免卡住
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running && (engine == null || !engine.isInited())) {
                        String err = engine == null ? null : engine.initError();
                        EventLog.add("视觉模型 20 秒未就绪（"
                                + (err == null ? "疑似初始化卡死" : err) + "），已停止服务");
                        updateNotif("模型加载失败，已停止（详情见日志）");
                        stopSelf();
                    }
                }
            }, 20000);
            return;
        }
        if (engine.isInited()) {
            engine.start();
            engineReady = true;
            updateNotif(faceMode ? "表情识别中：张嘴/撅嘴翻页" : "手势识别中：按手指数翻页");
        }
    }

    private void stopEngine() {
        engineReady = false;
        if (engine != null) {
            engine.stop();
        }
    }

    // ---------------- 引擎回调 ----------------

    @Override
    public void onReady() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (running && engine != null && engine.isInited()) {
                    engine.start();
                    engineReady = true;
                    EventLog.add((faceMode ? "表情" : "手势") + "引擎已启动（"
                            + (highAccuracy ? "高精度 960×720" : "标准 640×480") + "）");
                    updateNotif(faceMode ? "表情识别中：张嘴/撅嘴翻页" : "手势识别中：按手指数翻页");
                }
            }
        });
    }

    @Override
    public void onGesture(final String action, final float score) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                handleAction(action, score);
            }
        });
    }

    @Override
    public void onError(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                EventLog.add("视觉引擎：" + message);
                updateNotif("视觉引擎：" + message);
            }
        });
    }

    /** v4.2：引擎的诊断信息（限流后每 2~3 秒一条），直接进事件日志，便于定位「看不到/没认出来」 */
    @Override
    public void onDebug(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    EventLog.add(message);
                }
            }
        });
    }

    private void handleAction(String action, float score) {
        if (!running) {
            return;
        }
        String nAction = curNextAction;
        String pAction = curPrevAction;
        boolean isNext = action.equals(nAction);
        boolean isPrev = action.equals(pAction);
        if (!isNext && !isPrev) {
            return;
        }
        String label = actionLabel(action);
        lastAction = label;
        trigger(isNext, label, score);
    }

    /** blendshape 名 → 中文，用于日志/播报 */
    private static String actionLabel(String action) {
        if ("jawOpen".equals(action)) {
            return "张嘴";
        }
        if ("mouthPucker".equals(action)) {
            return "撅嘴";
        }
        if ("mouthSmile".equals(action)) {
            return "露牙笑";
        }
        if ("teethBare".equals(action)) {
            return "龇牙";
        }
        if ("cheekPuff".equals(action)) {
            return "鼓腮";
        }
        if ("browInnerUp".equals(action)) {
            return "抬眉";
        }
        if ("eyeSquint".equals(action)) {
            return "眯眼";
        }
        if ("eyeWide".equals(action)) {
            return "睁大眼";
        }
        if (isFinger(action)) {
            return action + " 根手指";
        }
        return action;
    }

    /** 冷却 → 震动反馈 → 拉起 ShortX 翻页（v4.5 删除前台门禁与熔断） */
    private void trigger(boolean isNext, String label, float score) {
        long now = System.currentTimeMillis();
        if (cooldownMs > 0 && now - lastFireAt < cooldownMs) {
            EventLog.add("冷却中（" + cooldownMs + "ms），忽略「" + label + "」");
            return;
        }
        lastFireAt = now;
        EventLog.add("识别到「" + label + "」（置信度 "
                + String.format(java.util.Locale.US, "%.2f", score) + "）→ "
                + (isNext ? "下一个" : "上一个"));
        Feedback.play(this, Feedback.EV_RECOGNIZED);
        Trigger.Listener verdict = new Trigger.Listener() {
            @Override
            public void onVerdict(String text) {
                updateNotif(text);
            }
        };
        String source = (faceMode ? "表情「" : "手势「") + label + "」";
        if (isNext) {
            Trigger.next(this, source, verdict);
        } else {
            Trigger.prev(this, source, verdict);
        }
    }

    // ---------------- 通知 ----------------

    private Notification buildNotif(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("手势切抖音 · 识别中")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_SERVICE);
        }
        Intent stop = new Intent(this, VisionService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(0, "停止", pi);
        return b.build();
    }

    private void updateNotif(String text) {
        if (text == null || text.equals(lastNotifText)) {
            return;
        }
        lastNotifText = text;
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif(text));
        } catch (Exception ignore) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        engineReady = false;
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignore) {
        }
        handler.removeCallbacksAndMessages(null);
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignore) {
            }
            engine = null;
        }
        Feedback.release();
        EventLog.add("视觉服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
