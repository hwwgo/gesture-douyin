package com.handsfree.douyin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;
import android.util.Size;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * v3.9：前置摄像头视觉控制引擎（手势 / 面部表情双模式，实验性，默认关闭）。
 *
 * 两种模式共用同一套相机管线（Camera2 5fps → YUV 转 Bitmap → MediaPipe），只换识别器：
 * - MODE_GESTURE：GestureRecognizer 的手部关键点 → **数伸直的手指**（1~5 根），
 *   用户可把任一根数映射到「下一个 / 上一个」（v4.3 起不再用内置的比耶/ILoveYou 分类）
 * - MODE_FACE：FaceLandmarker + blendshapes，张嘴（jawOpen）/ 撅嘴（mouthPucker）任选映射
 *
 * 为什么用手指数：内置手势分类只有 7 种、且对角度/光线敏感（实测比耶经常认不出）；
 * 数手指只依赖关键点几何，同一个手势能表达 5 种含义，且「伸 3 根」这种姿势洗碗时不会出现。
 * 手势为什么不用 Closed_Fist/Open_Palm：洗碗时握碗/冲碗就是这两个动作，必然误触发。
 *
 * 模型都用 ByteBuffer 从 assets 整块读入（避免 aapt2 压缩导致 MediaPipe 无法 mmap）。
 */
public final class CameraVisionEngine {

    public static final int MODE_GESTURE = 0;
    public static final int MODE_FACE = 1;

    /**
     * v4.7：预览回调——把「引擎正在处理的那一帧」缩图交给界面显示（调试用）。
     * 复用同一路相机与同一帧，不额外开相机，所以不会和识别抢设备。
     */
    public interface PreviewListener {
        void onPreview(Bitmap frame);
    }

    /**
     * v5.1：数字变焦倍率（1.0~4.0），界面滑条直接改这个值，**每帧读取、实时生效**。
     * 实现方式是在 YUV→ARGB 时只取画面中心的一块区域并映射到整幅输出，
     * 等于把「中间那块」放大填满画面——人脸/手在 MediaPipe 的输入里就变大了，
     * 离得远时检测率会明显提升。代价是视野变窄（倍率 2x 时只剩中心 1/4 面积）。
     */
    public static volatile float zoomFactor = 1.0f;
    private static final float ZOOM_MIN = 1.0f;
    private static final float ZOOM_MAX = 4.0f;

    /** 全局唯一的预览监听者（界面设置/清空） */
    public static volatile PreviewListener previewListener = null;

    /**
     * v4.8：在自动角度之上的**额外旋转**（0/90/180/270）。
     * 各厂商前置摄像头的方向定义不一致（实测某机型自动值会上下颠倒 180°），
     * 界面用「画面方向 +90°」按钮在**当前所见**基础上继续旋转，即时生效。
     */
    public static volatile int previewRotationExtra = 0;

    public interface Callback {
        void onReady();

        /** 命中的动作名（Victory/ILoveYou/jawOpen/mouthPucker）+ 置信度 */
        void onGesture(String gesture, float score);

        void onError(String message);

        /** v4.2：诊断信息（限流后回调），用于把「看到了什么」写进事件日志 */
        void onDebug(String message);
    }

    private static final String GESTURE_MODEL = "gesture/gesture_recognizer.task";
    private static final String FACE_MODEL = "face/face_landmarker.task";
    /** 标准 640×480；高精度 960×720（像素量 2.2 倍，手指/远处的手更清楚） */
    private static final int W = 640;
    private static final int H = 480;
    private static final int W_HD = 960;
    private static final int H_HD = 720;
    /** 表情 5fps 够用；手势分类在 5fps 下容易抖成 None，提到 ~7fps（150ms）提高连续命中率 */
    private static final long FRAME_INTERVAL_FACE_MS = 200;
    private static final long FRAME_INTERVAL_GESTURE_MS = 150;
    private static final long FIRE_GAP_MS = 1500;
    /** 预览刷新间隔（0.2 秒一帧足够看清构图，成本可忽略） */
    private static final long PREVIEW_INTERVAL_MS = 200;
    /** v4.2：目标动作短暂丢失的宽限——手势分类常在 Victory/None 之间跳，断一帧不该清零计时 */
    private static final long HOLD_GRACE_MS = 450;

    /** 表情动作名（手势动作在 v4.3 起用数字字符串 "1"~"5" 表示手指数） */
    public static final String FACE_NEXT = "jawOpen";
    public static final String FACE_PREV = "mouthPucker";

    /** v4.3：手指数判定的默认参数 */
    /** 手指伸直角度阈值（MCP-PIP-TIP 夹角，伸直≈180°，弯曲≈90~130°） */
    private static final float FINGER_ANGLE_MIN = 155f;
    /** 拇指伸直判据：指尖到食指根的距离 / 指节到食指根的距离 */
    private static final float THUMB_RATIO = 1.12f;

    /** 张嘴/撅嘴的默认阈值（可由用户在界面上改） */
    private static final float TH_JawOpen = 0.55f;
    private static final float TH_Pucker = 0.50f;

    private final Context ctx;
    private final Callback cb;
    private final int mode;
    /** 用户配置的动作：表情为 blendshape 名（jawOpen/mouthPucker），手势为手指数（"1"~"5"） */
    private final String nextAction;
    private final String prevAction;
    /** v4.3：用户配置的阈值（表情=blendshape 阈值，手势=手部检测置信度） */
    private final float thNext;
    private final float thPrev;
    private final float handDetConf;
    /** v4.6：高精度模式（720p 采集） */
    private final boolean highAccuracy;
    /** 实际采用的采集尺寸（机型不支持目标尺寸时取最接近） */
    private volatile int camW = W;
    private volatile int camH = H;

    private volatile boolean inited = false;
    private volatile String initError = null;
    private volatile boolean running = false;

    private GestureRecognizer gestureRec;
    private FaceLandmarker faceRec;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;

    private long lastFrameAt = 0L;
    private long lastFireAt = 0L;
    /** v4.2：诊断日志限流（2 秒最多一条） */
    private long lastDebugAt = 0L;
    private long lastErrLogAt = 0L;

    // v4.5：复用像素缓冲与 Bitmap——原来每帧新建 int[30万]（1.2MB）+ Bitmap，
    // GC 抖动明显；复用后帧处理更稳，长时间运行也不掉速。
    private int[] argbBuf;
    private Bitmap bmpBuf;
    /** v5.1：变焦用的列/行映射缓存（避免每像素做一次乘除） */
    private int[] colMapX;
    private int[] colMapY;
    private int mapW = -1;
    private int mapH = -1;
    private float mapZoom = -1f;

    /** v4.7：预览旋转角（前置通常 90°）与限流时间戳 */
    private volatile int previewRotation = 0;
    private long lastPreviewAt = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // v4.1：表情分数平滑 + 自适应基线（提高准确率、降低个体差异影响）
    private boolean smoothInit = false;
    private float emaNext = 0f;
    private float emaPrev = 0f;
    private int calibFrames = 0;
    private float baseNext = 0f;
    private float basePrev = 0f;
    /** v4.2：最近一次判定的实际阈值（诊断显示用，避免按动作名猜基线取错） */
    private float dbgTn = 0f;
    private float dbgTp = 0f;
    private String holdGesture = "";
    private long holdStart = 0L;
    /** v4.2：最近一次仍看到目标动作的时间（宽限判定用） */
    private long lastSeenAt = 0L;

    public CameraVisionEngine(Context ctx, int mode, String nextAction, String prevAction,
                              float thNext, float thPrev, float handDetConf,
                              boolean highAccuracy, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.mode = mode;
        this.nextAction = (nextAction == null || nextAction.isEmpty()) ? FACE_NEXT : nextAction;
        this.prevAction = (prevAction == null || prevAction.isEmpty()) ? FACE_PREV : prevAction;
        this.thNext = clamp01(thNext, 0.05f, 0.95f);
        this.thPrev = clamp01(thPrev, 0.05f, 0.95f);
        this.handDetConf = clamp01(handDetConf, 0.10f, 0.90f);
        this.highAccuracy = highAccuracy;
        this.cb = cb;
    }

    private static float clamp01(float v, float lo, float hi) {
        if (Float.isNaN(v) || v <= 0f) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public int getMode() {
        return mode;
    }

    public boolean isInited() {
        return inited;
    }

    public String initError() {
        return initError;
    }

    private String modelPath() {
        return mode == MODE_FACE ? FACE_MODEL : GESTURE_MODEL;
    }

    private long holdMs() {
        // v4.2：手势 400ms（原来 600ms 在 5fps 抖动下几乎攒不够），表情仍 500ms
        return mode == MODE_FACE ? 500 : 400;
    }

    private long frameIntervalMs() {
        return mode == MODE_FACE ? FRAME_INTERVAL_FACE_MS : FRAME_INTERVAL_GESTURE_MS;
    }

    private boolean isTarget(String g) {
        if (g == null || g.isEmpty()) {
            return false;
        }
        if (mode == MODE_FACE) {
            return g.equals(nextAction) || g.equals(prevAction);
        }
        return g.equals(nextAction) || g.equals(prevAction);
    }

    /** 后台线程加载模型（手势 8MB / 表情 3.6MB） */
    public void initAsync() {
        if (inited) {
            cb.onReady();
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.loadLibrary("mediapipe_tasks_vision_jni");
                } catch (Throwable t2) {
                    initError = "JNI 库加载失败：" + t2.getClass().getSimpleName()
                            + (t2.getMessage() == null ? "" : "：" + t2.getMessage());
                    cb.onError(initError);
                    return;
                }
                try {
                    BaseOptions bo = BaseOptions.builder()
                            .setModelAssetBuffer(loadModelBuffer(modelPath()))
                            .build();
                    if (mode == MODE_FACE) {
                        FaceLandmarker.FaceLandmarkerOptions opts =
                                FaceLandmarker.FaceLandmarkerOptions.builder()
                                        .setBaseOptions(bo)
                                        .setRunningMode(RunningMode.IMAGE)
                                        .setNumFaces(1)
                                        .setOutputFaceBlendshapes(true)
                                        .build();
                        faceRec = FaceLandmarker.createFromOptions(ctx, opts);
                    } else {
                        GestureRecognizer.GestureRecognizerOptions opts =
                                GestureRecognizer.GestureRecognizerOptions.builder()
                                        .setBaseOptions(bo)
                                        .setRunningMode(RunningMode.IMAGE)
                                        .setNumHands(1)
                                        // v4.3：置信度改由用户配置（默认 0.35）。调低更容易检测到手，
                                        // 误检由「手指数 + 保持 400ms」兜住。改此值需重开识别。
                                        .setMinHandDetectionConfidence(handDetConf)
                                        .setMinHandPresenceConfidence(handDetConf)
                                        .setMinTrackingConfidence(handDetConf)
                                        .build();
                        gestureRec = GestureRecognizer.createFromOptions(ctx, opts);
                    }
                    inited = true;
                    cb.onReady();
                } catch (Throwable t3) {
                    initError = t3.getClass().getSimpleName()
                            + (t3.getMessage() == null ? "" : "：" + t3.getMessage());
                    cb.onError(initError);
                }
            }
        }, "vision-init");
        t.setDaemon(true);
        t.start();
    }

    /** 把 assets 里的 .task 整块读进直接内存（一次性） */
    private ByteBuffer loadModelBuffer(String path) throws java.io.IOException {
        java.io.InputStream in = ctx.getAssets().open(path);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(10 * 1024 * 1024);
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
            }
            byte[] data = bos.toByteArray();
            ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
            buf.put(data);
            buf.rewind();
            return buf;
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    public void start() {
        if (!inited) {
            return;
        }
        if (running) {
            return;
        }
        running = true;
        bgThread = new HandlerThread("vision-cam");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                openCamera();
            }
        });
    }

    public void stop() {
        running = false;
        Handler h = bgHandler;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    closeCamera();
                }
            });
        }
        HandlerThread t = bgThread;
        bgThread = null;
        bgHandler = null;
        if (t != null) {
            t.quitSafely();
        }
    }

    public void release() {
        stop();
        try {
            if (gestureRec != null) {
                gestureRec.close();
            }
        } catch (Throwable ignore) {
        }
        gestureRec = null;
        try {
            if (faceRec != null) {
                faceRec.close();
            }
        } catch (Throwable ignore) {
        }
        faceRec = null;
        if (bmpBuf != null) {
            try {
                bmpBuf.recycle();
            } catch (Throwable ignore) {
            }
            bmpBuf = null;
        }
        argbBuf = null;
        inited = false;
    }

    // ---------------- Camera2 ----------------

    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                cb.onError("相机服务不可用");
                running = false;
                return;
            }
            String frontId = null;
            CameraCharacteristics frontCh = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontId = id;
                    frontCh = ch;
                    break;
                }
            }
            if (frontId == null) {
                cb.onError("没有可用的前置摄像头");
                running = false;
                return;
            }
            try {
                Integer so = frontCh.get(CameraCharacteristics.SENSOR_ORIENTATION);
                int deg = (so == null) ? 270 : so;
                // v4.8：前置摄像头按 sensor 角顺时针旋转即可正立。
                // 之前写成 (360 - deg) 正好差 180°，实机表现为「上下颠倒」。
                previewRotation = ((deg % 360) + 360) % 360;
            } catch (Throwable ignore) {
                previewRotation = 0;
            }
            Size size = pickSize(frontCh);
            camW = size.getWidth();
            camH = size.getHeight();
            reader = ImageReader.newInstance(camW, camH,
                    ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastFrameAt < frameIntervalMs()) {
                            return;
                        }
                        lastFrameAt = now;
                        processFrame(img);
                    } catch (Throwable t) {
                        // 单帧异常忽略
                    } finally {
                        if (img != null) {
                            try {
                                img.close();
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                }
            }, bgHandler);
            cb.onDebug("采集分辨率 " + camW + "×" + camH
                    + (highAccuracy ? "（高精度模式）" : "") + "，识别模型已就绪");
            cm.openCamera(frontId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice dev) {
                    camera = dev;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice dev) {
                    try {
                        dev.close();
                    } catch (Throwable ignore) {
                    }
                    camera = null;
                    running = false;
                }

                @Override
                public void onError(CameraDevice dev, int error) {
                    try {
                        dev.close();
                    } catch (Throwable ignore) {
                    }
                    camera = null;
                    running = false;
                    cb.onError("相机打开失败（code=" + error + "）");
                }
            }, bgHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            running = false;
            cb.onError("相机不可用：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
    }

    private void createSession() {
        try {
            if (camera == null || reader == null) {
                return;
            }
            camera.createCaptureSession(
                    java.util.Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                s.setRepeatingRequest(b.build(), null, bgHandler);
                            } catch (Throwable t) {
                                cb.onError("相机会话启动失败：" + t.getClass().getSimpleName());
                                running = false;
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            cb.onError("相机会话配置失败");
                            running = false;
                        }
                    }, bgHandler);
        } catch (Throwable t) {
            cb.onError("相机会话异常：" + t.getClass().getSimpleName());
            running = false;
        }
    }

    private void closeCamera() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignore) {
        }
        session = null;
        try {
            if (camera != null) {
                camera.close();
            }
        } catch (Throwable ignore) {
        }
        camera = null;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignore) {
        }
        reader = null;
    }

    /** 选最接近目标尺寸的 YUV 尺寸（高精度 960×720，标准 640×480） */
    private Size pickSize(CameraCharacteristics ch) {
        final int tw = highAccuracy ? W_HD : W;
        final int th = highAccuracy ? H_HD : H;
        try {
            if (ch != null) {
                StreamConfigurationMap map =
                        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                    if (sizes != null && sizes.length > 0) {
                        Size best = null;
                        for (Size s : sizes) {
                            if (s.getWidth() == tw && s.getHeight() == th) {
                                return s;
                            }
                            if (best == null
                                    || Math.abs((long) s.getWidth() * s.getHeight() - (long) tw * th)
                                    < Math.abs((long) best.getWidth() * best.getHeight() - (long) tw * th)) {
                                best = s;
                            }
                        }
                        if (best != null) {
                            return best;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return new Size(tw, th);
    }

    // ---------------- 识别 ----------------

    private void processFrame(Image image) {
        try {
            Bitmap bmp = yuvToBitmap(image);
            if (bmp == null) {
                return;
            }
            MPImage mi = new BitmapImageBuilder(bmp).build();
            if (mode == MODE_FACE) {
                detectFace(mi);
            } else {
                detectGesture(mi);
            }
            deliverPreview(bmp);
        } catch (Throwable t) {
            // v4.2：不再静默——单帧异常很可能是识别器调用本身出错（例如输入格式不符），
            // 静默吞掉会让用户只看到「什么都不发生」。
            long now = System.currentTimeMillis();
            if (now - lastErrLogAt > 3000) {
                lastErrLogAt = now;
                cb.onDebug("帧识别异常：" + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage()));
            }
        }
    }

    /**
     * v4.3：手势改为「数手指」，不再依赖内置手势分类（比耶/ILoveYou）。
     *
     * 用 GestureRecognizer 输出的 21 个手部关键点数伸直的手指：
     * - 食指~小指：MCP-PIP-TIP 夹角 ≥ 155°（伸直≈180°，弯曲≈90~130°），与手的大小/朝向/远近无关
     * - 拇指：指尖到食指根的距离 / 指节到食指根的距离 ≥ 1.12（拇指横过掌心时该比值 < 1）
     * 数出 1~5 根即对应动作 "1"~"5"；0 根（握拳）不触发。
     */
    /** v4.7：把当前帧缩一半、按 sensor 方向旋正后交给界面（限流 200ms） */
    private void deliverPreview(Bitmap src) {
        PreviewListener l = previewListener;
        if (l == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPreviewAt < PREVIEW_INTERVAL_MS) {
            return;
        }
        lastPreviewAt = now;
        try {
            int w = src.getWidth() / 2;
            int h = src.getHeight() / 2;
            if (w <= 0 || h <= 0) {
                return;
            }
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, false);
            int rot = ((previewRotation + previewRotationExtra) % 360 + 360) % 360;
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(small, 0, 0, w, h, m, false);
                if (rotated != small) {
                    small.recycle();
                }
                small = rotated;
            }
            final Bitmap out = small;
            final PreviewListener target = l;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (previewListener == target) {
                        target.onPreview(out);
                    } else {
                        out.recycle();
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private void detectGesture(MPImage mi) {
        GestureRecognizerResult r = gestureRec.recognize(mi);
        int handCount = 0;
        String action = "";
        int fingers = -1;
        boolean[] up = null;

        List<List<NormalizedLandmark>> lms = (r == null) ? null : r.landmarks();
        if (lms != null && !lms.isEmpty()) {
            handCount = lms.size();
            List<NormalizedLandmark> hand = lms.get(0);
            if (hand != null && hand.size() >= 21) {
                up = fingerStates(hand);
                if (up != null) {
                    fingers = 0;
                    for (boolean b : up) {
                        if (b) {
                            fingers++;
                        }
                    }
                    if (fingers >= 1 && fingers <= 5) {
                        action = String.valueOf(fingers);
                    }
                }
            }
        }
        debugGesture(handCount, fingers, up, action);
        updateHold(action, 1.0f);
    }

    /**
     * 逐指判断是否伸直。返回 {拇指, 食指, 中指, 无名指, 小指}；关键点异常时返回 null。
     * MediaPipe 手部 21 点索引：0 腕、1~4 拇指(CMC/MCP/IP/TIP)、5~8 食指、9~12 中指、
     * 13~16 无名指、17~20 小指（MCP/PIP/DIP/TIP）。
     */
    private static boolean[] fingerStates(List<NormalizedLandmark> h) {
        try {
            boolean[] up = new boolean[5];
            // 拇指：① 指尖(4) 与指节(3) 到食指根(5) 的距离比（伸开时指尖明显更远）
            //       ② 2-3-4 夹角（握拳时拇指压在手指上，IP 关节是弯的）
            // 两条都要满足——只用距离比时「握拳」容易被多数成 1 根手指。
            float dTip = dist(h.get(4), h.get(5));
            float dIp = dist(h.get(3), h.get(5));
            up[0] = dIp > 1e-6f && dTip / dIp >= THUMB_RATIO
                    && angleAt(h.get(2), h.get(3), h.get(4)) >= 140f;
            // 四指：MCP-PIP-TIP 夹角
            int[] mcp = {5, 9, 13, 17};
            int[] pip = {6, 10, 14, 18};
            int[] tip = {8, 12, 16, 20};
            for (int i = 0; i < 4; i++) {
                up[i + 1] = angleAt(h.get(mcp[i]), h.get(pip[i]), h.get(tip[i])) >= FINGER_ANGLE_MIN;
            }
            return up;
        } catch (Throwable t) {
            return null;
        }
    }

    /** b 处的夹角（度） */
    private static float angleAt(NormalizedLandmark a, NormalizedLandmark b, NormalizedLandmark c) {
        float v1x = a.x() - b.x();
        float v1y = a.y() - b.y();
        float v2x = c.x() - b.x();
        float v2y = c.y() - b.y();
        float m1 = (float) Math.hypot(v1x, v1y);
        float m2 = (float) Math.hypot(v2x, v2y);
        if (m1 < 1e-6f || m2 < 1e-6f) {
            return 0f;
        }
        float cos = (v1x * v2x + v1y * v2y) / (m1 * m2);
        if (cos > 1f) {
            cos = 1f;
        } else if (cos < -1f) {
            cos = -1f;
        }
        return (float) Math.toDegrees(Math.acos(cos));
    }

    private static float dist(NormalizedLandmark a, NormalizedLandmark b) {
        return (float) Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    /** v4.3：每 2 秒最多一条诊断——手在不在画面、数到几根、哪根判错（拇/食/中/无/小） */
    private void debugGesture(int handCount, int fingers, boolean[] up, String action) {
        long now = System.currentTimeMillis();
        if (now - lastDebugAt < 2000) {
            return;
        }
        lastDebugAt = now;
        if (handCount <= 0) {
            cb.onDebug("手势诊断：画面里没有手——把手抬到脸旁、掌心朝镜头、离 30~60cm");
            return;
        }
        if (up == null) {
            cb.onDebug("手势诊断：检测到手但关键点不完整，稍等或调整角度");
            return;
        }
        String detail = "拇" + yn(up[0]) + " 食" + yn(up[1]) + " 中" + yn(up[2])
                + " 无" + yn(up[3]) + " 小" + yn(up[4]);
        if (action.isEmpty()) {
            cb.onDebug("手势诊断：数到 " + fingers + " 根手指（" + detail + "），"
                    + (fingers == 0 ? "握拳不触发，请伸出手指" : "目标为 " + nextAction + "/" + prevAction + " 根"));
        } else {
            cb.onDebug("手势诊断：" + action + " 根手指（" + detail + "），保持 "
                    + holdMs() + "ms 即触发" + (action.equals(nextAction) ? "→下一个"
                    : (action.equals(prevAction) ? "→上一个" : "（非当前配置，不触发）")));
        }
    }

    private static String yn(boolean b) {
        return b ? "✓" : "✗";
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** 表情：从 blendshapes 里取用户配置的两个动作（只支持 jawOpen / mouthPucker） */
    private void detectFace(MPImage mi) {
        FaceLandmarkerResult r = faceRec.detect(mi);
        String g = "";
        float score = 0f;
        boolean hasFace = false;
        float nextScore = 0f;
        float prevScore = 0f;
        if (r != null) {
            Optional<List<List<Category>>> opt = r.faceBlendshapes();
            if (opt != null && opt.isPresent()) {
                List<List<Category>> all = opt.get();
                if (all != null && !all.isEmpty() && all.get(0) != null) {
                    hasFace = true;
                    // v5.3：动作可以是单个 blendshape，也可以是组合（如「露牙」= 左右嘴角上扬均值，
                    // 「龇牙」= 上唇上提与下唇下拉的较小值），统一由 scoreOf 计算。
                    nextScore = scoreOf(nextAction, all.get(0));
                    prevScore = scoreOf(prevAction, all.get(0));
                    // v4.2：非对称 EMA——上升快（0.8 新值）保证「张嘴」立刻能起来，
                    // 下降慢（0.6 旧值）压掉抖动。之前对称 0.45 上升太慢，
                    // 3 帧（500ms）内还没爬到阈值，等于把动作拖没了。
                    emaNext = smoothInit
                            ? (nextScore > emaNext ? emaNext * 0.2f + nextScore * 0.8f
                                                   : emaNext * 0.6f + nextScore * 0.4f)
                            : nextScore;
                    emaPrev = smoothInit
                            ? (prevScore > emaPrev ? emaPrev * 0.2f + prevScore * 0.8f
                                                   : emaPrev * 0.6f + prevScore * 0.4f)
                            : prevScore;
                    smoothInit = true;

                    if (calibFrames < 10) {
                        baseNext = (baseNext * calibFrames + emaNext) / (calibFrames + 1);
                        basePrev = (basePrev * calibFrames + emaPrev) / (calibFrames + 1);
                        calibFrames++;
                        if (calibFrames == 10) {
                            cb.onDebug("表情校准完成（基线：" + actionCn(nextAction) + " "
                                    + fmt(baseNext) + "，" + actionCn(prevAction) + " "
                                    + fmt(basePrev) + "），现在可以做动作了");
                        }
                        return; // 校准期不触发
                    }
                    // v4.4：判定阈值 **就是用户设的值**，不再被自适应基线抬高。
                    // 原来用 max(用户阈值, 基线+0.22)，用户把阈值调到 0.2 仍会按 0.28 判，
                    // 表现为「调了没变化」。基线现在只作为诊断信息显示。
                    // v5.3：阈值按**方向**取。之前按动作名判断（FACE_PREV.equals(action)），
                    // 一旦「上一个」配的不是撅嘴（比如换成露牙），就会错用 next 的阈值。
                    float tn = thNext;
                    float tp = thPrev;
                    dbgTn = tn;
                    dbgTp = tp;
                    if (emaNext >= tn && emaNext >= emaPrev) {
                        g = nextAction;
                        score = emaNext;
                    } else if (emaPrev >= tp) {
                        g = prevAction;
                        score = emaPrev;
                    }
                }
            }
        }
        debugFace(hasFace);
        updateHold(g, score);
    }

    /**
     * v5.3：把「动作」换算成分数。支持 8 种：
     * jawOpen / mouthPucker / cheekPuff / browInnerUp（单个 blendshape）
     * mouthSmile（=左右嘴角上扬均值，即「露牙笑」）
     * teethBare（=上唇上提与下唇下拉的较小值，即「龇牙/露齿」）
     * eyeSquint / eyeWide（左右眼均值）
     */
    private static float scoreOf(String action, List<Category> shapes) {
        if (action == null || shapes == null) {
            return 0f;
        }
        if ("mouthSmile".equals(action)) {
            return avg(shapes, "mouthSmileLeft", "mouthSmileRight");
        }
        if ("teethBare".equals(action)) {
            float up = avg(shapes, "mouthUpperUpLeft", "mouthUpperUpRight");
            float low = avg(shapes, "mouthLowerDownLeft", "mouthLowerDownRight");
            return Math.min(up, low);
        }
        if ("eyeSquint".equals(action)) {
            return avg(shapes, "eyeSquintLeft", "eyeSquintRight");
        }
        if ("eyeWide".equals(action)) {
            return avg(shapes, "eyeWideLeft", "eyeWideRight");
        }
        return one(shapes, action);
    }

    /** 左右对称分量的均值 */
    private static float avg(List<Category> shapes, String a, String b) {
        float x = 0f;
        float y = 0f;
        for (Category c : shapes) {
            if (c == null) {
                continue;
            }
            String n = c.categoryName();
            if (a.equals(n)) {
                x = c.score();
            } else if (b.equals(n)) {
                y = c.score();
            }
        }
        return (x + y) / 2f;
    }

    private static float one(List<Category> shapes, String name) {
        for (Category c : shapes) {
            if (c != null && name.equals(c.categoryName())) {
                return c.score();
            }
        }
        return 0f;
    }

    /** v4.2：每 3 秒最多一条表情诊断，方便用户把实际分数发回来调阈值 */
    private void debugFace(boolean hasFace) {
        long now = System.currentTimeMillis();
        if (now - lastDebugAt < 3000) {
            return;
        }
        lastDebugAt = now;
        if (!hasFace) {
            cb.onDebug("表情诊断：画面里没有人脸——手机对准脸、光线亮一点");
            return;
        }
        cb.onDebug("表情诊断：" + actionCn(nextAction) + " " + fmt(emaNext) + "/" + fmt(dbgTn)
                + "（基线 " + fmt(baseNext) + "），"
                + actionCn(prevAction) + " " + fmt(emaPrev) + "/" + fmt(dbgTp)
                + "（基线 " + fmt(basePrev) + "）");
    }

    private static String actionCn(String act) {
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

    /** 目标动作保持 holdMs 才触发（v4.2：允许短暂丢失，避免分类抖动清零计时） */
    private void updateHold(String g, float score) {
        long now = System.currentTimeMillis();
        if (!isTarget(g)) {
            if (holdStart > 0L && now - lastSeenAt > HOLD_GRACE_MS) {
                holdGesture = "";
                holdStart = 0L;
            }
            return;
        }
        lastSeenAt = now;
        if (holdStart == 0L || !g.equals(holdGesture)) {
            holdGesture = g;
            holdStart = now;
            return;
        }
        if (now - holdStart >= holdMs() && now - lastFireAt >= FIRE_GAP_MS) {
            lastFireAt = now;
            holdGesture = "";
            holdStart = 0L;
            lastSeenAt = 0L;
            cb.onGesture(g, score);
        }
    }

    /** YUV_420_888 → ARGB Bitmap（v4.5：复用 int[] 与 Bitmap，避免每帧分配 1.2MB） */
    private Bitmap yuvToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (argbBuf == null || argbBuf.length != w * h) {
            argbBuf = new int[w * h];
        }
        if (bmpBuf == null || bmpBuf.getWidth() != w || bmpBuf.getHeight() != h) {
            if (bmpBuf != null) {
                bmpBuf.recycle();
            }
            bmpBuf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Image.Plane[] p = image.getPlanes();
        ByteBuffer yB = p[0].getBuffer();
        int yRow = p[0].getRowStride();
        int yPix = p[0].getPixelStride();
        ByteBuffer uB = p[1].getBuffer();
        int uRow = p[1].getRowStride();
        int uPix = p[1].getPixelStride();
        ByteBuffer vB = p[2].getBuffer();
        int vRow = p[2].getRowStride();
        int vPix = p[2].getPixelStride();

        // v5.1：数字变焦——从中心裁一块，坐标映射到整幅输出
        float z = zoomFactor;
        if (z < ZOOM_MIN) {
            z = ZOOM_MIN;
        } else if (z > ZOOM_MAX) {
            z = ZOOM_MAX;
        }
        boolean zoomed = z > 1.001f;
        int cropW = zoomed ? (int) (w / z) : w;
        int cropH = zoomed ? (int) (h / z) : h;
        if (cropW < 2) {
            cropW = 2;
        }
        if (cropH < 2) {
            cropH = 2;
        }
        int cropX = (w - cropW) / 2;
        int cropY = (h - cropH) / 2;
        if ((cropX & 1) == 1) {
            cropX++; // 保持偶数，UV 采样才对得上
        }
        if ((cropY & 1) == 1) {
            cropY++;
        }

        if (zoomed) {
            if (colMapX == null || mapW != w || mapZoom != z) {
                colMapX = new int[w];
                for (int i = 0; i < w; i++) {
                    int sx = cropX + (int) ((long) i * cropW / w);
                    if (sx >= w) {
                        sx = w - 1;
                    }
                    colMapX[i] = sx;
                }
            }
            if (colMapY == null || mapH != h || mapZoom != z) {
                colMapY = new int[h];
                for (int j = 0; j < h; j++) {
                    int sy = cropY + (int) ((long) j * cropH / h);
                    if (sy >= h) {
                        sy = h - 1;
                    }
                    colMapY[j] = sy;
                }
            }
            mapW = w;
            mapH = h;
            mapZoom = z;
        }

        final int[] out = argbBuf;
        final int[] mapX = zoomed ? colMapX : null;
        final int[] mapY = zoomed ? colMapY : null;
        for (int j = 0; j < h; j++) {
            int sy = zoomed ? mapY[j] : j;
            int yOff = sy * yRow;
            int uvOff = (sy >> 1) * uRow;
            int base = j * w;
            for (int i = 0; i < w; i++) {
                int sx = zoomed ? mapX[i] : i;
                int yy = yB.get(yOff + sx * yPix) & 0xff;
                int u = (uB.get(uvOff + (sx >> 1) * uPix) & 0xff) - 128;
                int v = (vB.get(uvOff + (sx >> 1) * vPix) & 0xff) - 128;
                int r = yy + (int) (1.402f * v);
                int gg = yy - (int) (0.344f * u + 0.714f * v);
                int b = yy + (int) (1.772f * u);
                out[base + i] = 0xff000000
                        | (clamp(r) << 16) | (clamp(gg) << 8) | clamp(b);
            }
        }
        bmpBuf.setPixels(out, 0, w, 0, 0, w, h);
        return bmpBuf;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
