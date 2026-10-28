# 手势切抖音 v5.3 · 构建说明（纯视觉 · 精简优化）

> **v5.3 = 表情动作扩到 8 种（含「露牙」）**：
> 新增 `scoreOf(action, shapes)`，把组合动作也算成分数——`mouthSmile` = 左右嘴角上扬均值（露牙笑）、
> `teethBare` = min(上唇上提均值, 下唇下拉均值)（龇牙）、`eyeSquint`/`eyeWide` = 左右眼均值；
> 单个 blendshape 仍是 `jawOpen`/`mouthPucker`/`cheekPuff`/`browInnerUp`。
> 已验证 `face_blendshapes.tflite` 内确实含 `mouthSmileLeft/Right`、`mouthUpperUpLeft`、
> `mouthLowerDownLeft`、`cheekPuff`、`browInnerUp`、`eyeSquintLeft`、`eyeWideRight` 等标签。
> 顺带修两个 bug：① `thresholdOf()` 原来按动作名判定方向（`FACE_PREV.equals(action)`），
> 一旦「上一个」不是撅嘴就会错用 next 的阈值 → 改为按**方向**直接取 `thNext`/`thPrev`；
> ② Spinner 初始化会对当前项回调一次，会把用户调好的阈值覆盖成推荐值 → 跳过首次回调。
> UI 动作表加第三列「推荐阈值」，切换动作时自动填入。
> **v5.2 = 放大上限提到 4.0x**（按需求）：`ZOOM_MAX` 3.0→4.0、滑条 `setMax(20)`→`setMax(30)`
> （步长仍 0.1）。4.0x 取中心 160×120 放大到 640×480，已复核映射不越界
> （x[240..399] y[180..299]）；提示语注明 4x 只剩 1/16 视野、数字放大不增加真实细节。
> **v5.1 = 画面放大（数字变焦）**：首页加 1.0x~3.0x 滑条，解决「手机离得远、脸和手在画面里太小」。
> 实现放在 YUV→ARGB 环节做**中心裁剪放大**：取 `cropW=w/z, cropH=h/z` 的中心块，用预计算的
> `colMapX[]/colMapY[]`（按 `cropX + i*cropW/w`）映射回整幅输出，**零额外 Bitmap 分配**，
> 只是内层循环多一次数组取值；映射表仅在尺寸/倍率变化时重建。cropX/cropY 强制偶数保证
> UV 采样对齐。`zoomFactor` 为静态 volatile，滑条拖动即时生效；prefs `zoom_factor` 持久化。
> 因为预览与识别共用同一帧，预览框会直接显示放大后的构图，方便边拖边调。
> 已用 Python 复核 z=1.1/1.5/2/2.5/3 的源区域范围均在画面内（如 2.0x 取 x[160..479] y[120..359]）。
> **v5.0 = 更正息屏语义：息屏 = 手动停止识别**（v4.9 理解反了，撤回）：
> 用户原意是「屏幕熄灭就等于按了停止按钮」，而不是「息屏继续识别」。改为 `SCREEN_OFF` 时
> 直接 `stopSelf()`——服务结束、相机释放、前台通知消失，亮屏后**不自动恢复**（需回 App 重开）。
> 同时撤回 v4.9 的 `PARTIAL_WAKE_LOCK`、`acquireWake/releaseWake`、启动时 `isInteractive` 判断
> 与 Manifest 的 `WAKE_LOCK` 权限；屏幕接收器只注册 `ACTION_SCREEN_OFF`（不再理会 SCREEN_ON）。
> **v4.9 = 息屏继续识别**：v4.5 按「删运行设置」的要求把息屏行为固定为「息屏停、亮屏恢复」，
> 但实际使用场景是洗碗时手机支在台面上、屏幕会自然熄灭 —— 息屏即停等于功能失效。
> 改为**息屏继续识别**，并补上工程前提：`SCREEN_OFF` 时 `acquire()` 一个
> `PARTIAL_WAKE_LOCK`（`hf:vision`，`setReferenceCounted(false)`），`SCREEN_ON`/`onDestroy`
> 立即释放；服务启动时若 `PowerManager.isInteractive()` 为 false（锁屏下启动）即刻持锁。
> 没有这把锁，息屏后 CPU 进入休眠、相机帧回调停摆，识别会「假死」——只删掉 stopEngine 是不够的。
> Manifest 增 `WAKE_LOCK` 权限。已在通知栏区分「识别中（息屏继续）」。
> **v4.8 = 修正预览方向（实测上下颠倒）+ 方向可调**：v4.7 用
> `previewRotation = (360 - sensorOrientation) % 360` 计算，对 sensor=270 的前置得到 90°，
> 实机呈现上下颠倒——正确值应是 sensor 角本身（270°），即两者差 180°。
> 改为 `previewRotation = sensorOrientation`，并新增 `previewRotationExtra`
> （0/90/180/270，prefs `preview_rot_extra`）叠加在自动值之上；界面加「画面方向 +90°」按钮
> （点击在当前所见基础上 +90°，长按恢复自动），因为各厂商前置方向定义并不统一，
> 留一个现场可调的出口比死磕公式更可靠。
> **v4.7 = 摄像头预览（调试用）**：首页日志区上方新增「显示摄像头画面」开关 + 220dp 预览框。
> 实现方式：**复用引擎正在处理的那一帧**，不额外开一路相机（否则与识别抢 CameraDevice，
> 只能二选一，还会引入 session 重建风险）。`CameraVisionEngine` 新增静态
> `PreviewListener` 与 `deliverPreview()`：每 200ms 把 `bmpBuf` 缩一半、按
> `SENSOR_ORIENTATION`（前置 `(360-sensor)%360`）旋正、post 到主线程交给 ImageView；
> UI 侧负责回收上一帧 Bitmap，`onPause` 清空监听、`onResume` 按开关状态重挂，避免泄漏与后台耗电。
> 画面**不做镜像**——目的是看摄像头真实构图，不是自拍预览。
> **v4.6 = 真正实现「高精度模式」**：v4.1 只是口头提过这个备选方案、从未落地（用户以为被删了）。
> 现在落地为 `high_accuracy` 开关（默认关）：目标采集尺寸 640×480 → **960×720**
> （像素量 2.2 倍，手部关键点与 blendshape 分数更稳），`pickSize()` 改为按目标尺寸选最接近的
> 支持尺寸并在日志里回报实际分辨率；状态区显示「采集：高精度 960×720」。
> 注意：像素量增加会让 Java 侧重 YUV→ARGB 的耗时上升约 2 倍（约 40~60ms/帧），
> 仍在 5fps 的 200ms 预算内，但低端机会掉帧——所以默认关闭、按需开启，切换需重开识别。
> 关于「为什么不直接换 HandLandmarker 模型」：`gesture_recognizer.task` 内嵌的
> `hand_landmarker.task`（7.82MB）确实比整包（8.37MB）小、且能省掉每帧的手势分类推理，
> 但这会改动已验证可用的人手识别链路，收益只有 0.55MB，本轮不做，记录备查。
> **v4.5 = 精简 + 体积/性能优化**（按需求删功能、清死代码）：
> ① 反馈只剩震动：`Feedback` 重写，删除 `ToneGenerator` 提示音、`TextToSpeech` 语音播报
> 及其 `fb_sound`/`fb_tts` 开关；Vibrator 与 `hasVibrator()` 结果缓存，不再每次触发做 binder 调用。
> ② 删除「运行设置」三项：`BootReceiver.java` 整体移除（含 `RECEIVE_BOOT_COMPLETED`）、
> 「息屏时也识别」改为固定行为（息屏停/亮屏恢复，receiver 保留）、
> 「只在抖音前台时响应」删除（`Guard.java` 整体移除 + `PACKAGE_USAGE_STATS` 权限 +
> 使用情况访问授权按钮）。
> ③ 清理死代码：`Guard`、`BootReceiver`、`CmdNotifier` 三文件删除，`PowerManager` 等无用 import、
> `Settings` import、RadioGroup/NotificationManager import 清掉；`Feedback.play(ctx, ev, text)` 双参
> 重载收敛为单参。dex 类数 5064 → 3865（我们自己的类 50 → 49）。
> ④ 体积：模型 assets 从 `-0 task`（Stored 12.1MB）改回 Deflate（9.65MB）——v3.8.5 起已用
> `setModelAssetBuffer` 读入内存，不再依赖 mmap，压缩存放安全。APK 27.8MB → **25.3MB**。
> ⑤ 流畅度：YUV→Bitmap 改为**复用 `int[]` 与 `Bitmap`**（原来每帧新建 1.2MB 数组 + 位图，
> 5fps 下每秒 6MB 垃圾），GC 抖动消除；`release()` 时统一回收缓冲。
> ⑥ 补回 Android 13+ 的 `POST_NOTIFICATIONS` 申请（v4.0 重写界面时漏了，前台服务通知会不显示）。
> **v4.4 = 阈值「设多少就是多少」+ 删除通知触发通道**：
> ① 表情阈值原来取 `max(用户阈值, 基线+0.22)`——用户填 0.2 实际按 0.28 判（日志显示的正是
> 被抬高的值），表现为「调了没变化」。改为**直接用用户设定值**，自适应基线只留在日志里显示
> （`张嘴 0.42/0.20（基线 0.06）`）供参考。
> ② 删除「通知触发」通道（`CmdNotifier.java` 整类移除，`Trigger.fireNotify` 删除），
> 只保留 ShortX DeepLink；界面去掉通道单选，改为固定说明 + 两行链接输入；
> `refreshChannel` 只校验 DeepLink，并区分「只填了下一个」的情况；
> 老配置里的 `exec_channel=notify` 首次打开 App 时自动改写为 deeplink。
> **v4.3 = 手势改为「数手指」+ 阈值可视化可调**：
> ① 弃用内置手势分类（Victory/ILoveYou 对角度光线太挑，实测难稳定识别），改用
> `GestureRecognizerResult.landmarks()` 的 21 点**几何数手指**：
> 食指~小指用 MCP-PIP-TIP 夹角 ≥155°（伸直≈180、弯曲≈90~130，与手的尺度/朝向无关），
> 拇指用 `d(指尖4, 食指根5) / d(指节3, 食指根5) ≥ 1.12` **且** 2-3-4 夹角 ≥140°
> （只用距离比时「握拳+拇指搭在手指上」会被多数成 1 根，故加夹角条件）；
> 数到 1~5 根即动作 "1"~"5"，握拳（0 根）不触发；两端判定用 Python 合成关键点跑了
> 7 个用例（全开/只伸食指/2/3/4 根/握拳/握拳带拇指）全部符合预期。
> ② 手势动作可自定义：`gesture_next` / `gesture_prev` 各选 1~5 根，互斥校验。
> ③ **阈值可调**：表情两方向各一个阈值（`face_th_next` / `face_th_prev`，0.05~0.95，
> 默认 0.55/0.50），手势一个手部检测置信度（`gesture_th`，0.10~0.90，默认 0.35，
> 因是模型初始化参数故需重开识别）；UI 用「方向名 + Spinner + 阈值输入框」一行呈现。
> ④ 表情自适应基线改为**最多只比用户阈值高 0.10**——否则用户把阈值调低也会被基线抬回去，
> 出现「调了没用」。
> **v4.2 = 手势「比耶」识别修复 + 表情动作精简为两项**：
> ① **只取 top-1 类别是最大问题**：`gestures().get(0).get(0)` 在「None」占首位时直接丢弃比耶，
> 改为遍历该手全部 Category，只要目标手势分数 ≥ 0.35 就认；
> ② 保持时长 600ms→400ms，采样 5fps→约 7fps（150ms），并加入 450ms「宽限」——
> 手势分类常在 Victory/None 之间跳，断一帧不再把计时清零；
> ③ 手部检测置信度 0.5→0.35（手稍远/光线一般时不再整条链路静默）；
> ④ 新增诊断日志（限流 2~3 秒）：区分「画面里没手 / 看到手但认成别的手势 / 已识别为比耶」，
> 帧处理异常也不再静默吞掉（原来 `catch (Throwable)` 会把识别器报错完全吃掉）；
> ⑤ 表情动作精简为 **张嘴 / 撅嘴** 两种，两个方向都能任选（互斥校验），
> `tongueOut`/`browInnerUp` 从代码与 UI 全部移除，服务端 `loadActions()` 自动纠正老配置；
> ⑥ 表情平滑改为**非对称 EMA**（上升 0.8 / 下降 0.4）——原来对称 0.45 上升太慢，
> 3 帧（500ms）内爬不到阈值，等于把动作拖没了。
> **v4.1 = 表情识别提准 + 修回 DeepLink 输入框**：
> ① 表情分数做 EMA 平滑（α=0.45）压掉逐帧抖动；
> ② 启动后前 10 帧（约 2 秒）只学习「中性表情」基线、不触发；
> ③ 判定阈值改为 `max(动作阈值, 基线+0.22)`（基线上限 0.25），抵消个体与光线差异——
> 这是 MediaPipe blendshape 分数个体差异大的主要解法；
> ④ v4.0 重写界面时漏掉了 DeepLink 链接输入框（`shortx_url_next/prev`），本版补回，
> 且通道状态会明确提示「链接还没填」。
> **v4.0 = 纯视觉版（按需求做减法）**：删除全部语音功能——Vosk 模型（42MB）、KWS 模型与引擎、
> 麦克风采集、口令匹配与词表、TTS 回声静音窗、**连续触发熔断**；只保留前置摄像头手势/表情识别。
> `VoiceService` → `VisionService`（仅 camera 前台服务），`MainActivity` 重写（去掉口令/引擎/词表/
> 降噪/麦克风权限，新增表情动作单选）。表情动作可自定义：下一个（张嘴/撅嘴/伸舌头）、
> 上一个（撅嘴/伸舌头/抬眉），互斥校验。Manifest 删除 `RECORD_AUDIO` 与
> `FOREGROUND_SERVICE_MICROPHONE`，服务类型只留 `camera`；只打 arm64 的 MediaPipe 库。
> dex 5064→3868 类，APK 118MB→**27.8MB**。
> **v3.9 = 修复模型文件损坏 + 新增面部表情控制**：真机仍报 `Unable to open zip archive`，
> 排查发现**手势模型下载不完整**（本地 8,012,054 字节 vs 官方 8,373,440，缺 361KB，zip 目录读不到），
> 所以改加载方式当然无效。重新下载并加 MD5/`unzip -t` 双校验。同时新增表情模式：
> FaceLandmarker + blendshapes，**张嘴（jawOpen≥0.55）= 下一个、撅嘴（mouthPucker≥0.50）= 上一个**，
> 保持 0.5 秒触发；与手势互斥（同一前置摄像头只能跑一个识别器）。`CameraGestureEngine` 泛化为
> `CameraVisionEngine`（MODE_GESTURE / MODE_FACE 共用相机管线）。APK 118MB。
> **v3.8.5 = 修复「Unable to open zip archive」**：MediaPipe 加载模型时报此错。根因是 aapt2
> 默认压缩 assets（`Defl:N`），而 MediaPipe 的 ModelAssetBundle 会把 .task 当 zip 打开并 mmap，
> 压缩条目无法 mmap。修复双管齐下：① 改用 `setModelAssetBuffer(ByteBuffer)`——整块读进直接内存
> 交给 MediaPipe 解析，与压缩方式无关；② aapt2 link 加 `-0 task` 让 .task 以 Stored 存储。
> APK 114MB（模型不压缩 +2MB）。
> **v3.8.3/3.8.4 = 补齐 MediaPipe 依赖链**：真机报
> `NoClassDefFoundError: Failed resolution of: .../datatransport/runtime/dagger/internal/Factory`。
> 类其实在 dex 里，缺的是它的父接口 `javax.inject.Provider`——类加载时父接口解析失败，报的却是子类。
> 补齐 `javax.inject:1`、`firebase-encoders:17.0.0`、`firebase-encoders-proto:16.0.0`、
> `firebase-encoders-json:18.0.0`（aar）、`guava:failureaccess:1.0.1`、`androidx.annotation:1.1.0`，
> 并用「全量类引用闭合校验」（所有输入 jar 的 `Lcom/...;` 引用 vs dex 类表）确认第三方依赖零缺失。
> **v3.8.2 = 修复「手势引擎一直未启动」**：v3.8.1 只开手势时前台服务类型含 CAMERA，
> 但摄像头权限未授予 → `startForeground` 抛 SecurityException → 服务直接退出（错误提示却写成
> 「请允许麦克风权限」，方向完全错）。修复：启动前检查 CAMERA 权限、缺失则本次降级并给出明确
> 提示；goForeground 错误按缺失权限分类；手势开关「打开即弹权限请求」（原来只在 onCreate 请求一次）；
> 手势区新增「授予摄像头权限」按钮；状态区显示「⚠ 未授予摄像头权限」；看门狗 12s→20s 并加启动日志。
> **v3.8.1 = 语音/手势独立开关**：v3.8 虽然手势有独立开关，但启动监听时语音引擎仍无条件启动，
> 无法「只用手势」。修复：新增 `voice_enabled`（默认开）总开关，关闭后完全不初始化麦克风链路
> （`ensureRunning/startVosk/startKws` 全部提前返回）、麦克风权限也不再请求；前台服务类型按开关
> 组合计算（仅手势时 `type=CAMERA`，不含 MICROPHONE）；语音与手势都关时拒绝启动并提示。
> 另加一次性迁移：从 v3.8 升级且已开手势的用户自动设为「仅手势」，无需手动关。
> **v3.8 = 前置摄像头手势控制（实验，默认关）**：Camera2 前置 5fps 采帧 → YUV 转 Bitmap →
> MediaPipe GestureRecognizer（gesture_recognizer.task，7.7MB）→ 手势保持 0.6 秒才触发。
> **只认 Victory（比耶）= 下一个、ILoveYou = 上一个**——Closed_Fist/Open_Palm 虽然是内置
> 手势，但正是洗碗高频动作，故意不采用。与语音可同时开；12 秒看门狗，模型加载失败自动关。
> 顺带修复一个老缺陷：TTS 播报「下一个」会被自己的麦克风听到形成回声自触发，加 1.2 秒静音窗。
> 依赖：tasks-vision/tasks-core（Google Maven）+ guava/protobuf/flogger/datatransport。
> APK 112MB（+14MB so、+7.7MB 模型、+4MB 类）。
> **v3.7.4 = KWS 识别率调优（阈值 + 拼音变体）**：用户实测「只有『下个』能识别」。用 edge-tts
> 生成 16 段真实中文语音 + Python 同模型端到端测试定位：模型与词表本身没问题（干净/带噪下
> 全部 100% 命中），问题在 ① 阈值 0.25 对真人口音偏严 → 降到 0.15；② 「一个」的口语轻声/
> 变调与标注不符 → 同一词支持多个拼音变体（`|` 分隔，命中任一）。另外证伪了一个错误改法：
> 「先 decode 到不 ready 再取结果」实测会大幅漏检（上一个/上一条 0/2），必须保持官方示例的
> 每帧取结果写法。
> **v3.7.3 = 修复 KWS 模式下界面误报「需要解压」**：KWS 引擎已启动成功，但点「开启监听」的
> Toast 与状态区仍按 Vosk 的 `modelReady` 判断，显示「首次启动：正在解压离线模型」/「未解压」。
> 修复：新增 `VoiceService.kwsReady` 标志，启动提示与状态区按当前引擎分支显示
> （KWS 模式显示「关键词引擎运行中 ✓」，并注明模型随 APK 打包无需解压）；状态区改为每 3 秒
> 自动同步一次（此前只在 onResume 刷新，引擎启动后状态不会变）；KWS 模式日志摘要不再显示
> 无关的「词表约束开」。
> **v3.7.2 = 修复 KWS 引擎「cannot locate symbol OrtGetApiBase」（v3.7.1 热修复）**：
> 补上 onnxruntime 后仍加载失败，真机日志 `dlopen failed: cannot locate symbol "OrtGetApiBase"`。
> 根因是**符号版本不匹配**：sherpa 编译时链接 ORT 1.17.1（请求 `OrtGetApiBase@VERS_1.17.1`），
> v3.7.1 补的却是 ORT 1.20.0（只提供 `OrtGetApiBase@@VERS_1.20.0`）——Android linker 按版本匹配，
> 视为符号不存在。改用 `onnxruntime-android:1.17.1`（16MB）后符号完全闭合（已验证：sherpa 全部
> 未定义符号在 ORT 中均有对应版本）。APK 90.3MB。
> **v3.7.1 = 修复 KWS 引擎「NoClassDefFoundError」无法启动（v3.7 热修复）**：
> 真机实测点开关后 10 秒看门狗回退，日志 `NoClassDefFoundError: com.k2fsa.sherpa.onnx.KeywordSpotter`。
> 根因：`libsherpa-onnx-jni.so` 动态依赖 `libonnxruntime.so`（`readelf -d` 的 NEEDED 明确写着），
> 而 Maven AAR 只带 jni.so、不带 onnxruntime——loadLibrary 失败 → 类静态块再 loadLibrary 抛
> UnsatisfiedLinkError → 类初始化失败 → 上层只看到 NoClassDefFoundError。修复：补
> `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` 的 arm64 `libonnxruntime.so`（17.6MB）；
> `loadLibrary` 失败不再静默（直接报「JNI 库加载失败：dlopen 原因」）；KWS 仅 arm64-v8a。
> APK 91.9MB。
> **v3.7 = 双引擎版（Vosk 默认 / sherpa-onnx KWS 实验）**：新增中文关键词检测引擎
> `sherpa-onnx-kws-zipformer-wenetspeech-3.3M`（int8，4.9MB，准确率 95.2%），
> 首页「识别引擎」开关切换，共用冷却/门禁/熔断/反馈全部逻辑。KWS 只做「关键词命中判断」，
> 带置信度阈值，不命中完全静默——从机制上根治「视频里说到口令就误触发」。
> 依赖：Maven AAR `com.bihe0832.android:lib-sherpa-onnx:6.25.21`（含 arm64 的
> libsherpa-onnx-jni.so，onnxruntime 已静态链接）+ kotlin-stdlib + ModelScope 模型。
> 已在 Python 同模型上完成端到端验证（真实录音命中、静音零误报、16 词全解析）；
> Android 侧初始化有 10 秒看门狗，卡死自动回退 Vosk。APK 77MB。
> **v3.6 = 代码审查优化 + 识别引擎升级评估**：修 12 处实现问题（识别回调线程内做
> binder IPC、onTimeout 在 Vosk 回调栈内重建、模型加载失败无限重试、状态栏卡「冷却中」、
> 每秒递归统计 66MB 目录、正则重复编译、媒体检测每秒 IPC 等）；新增模型版本标记
> `assets/model-cn/version.txt`（为将来替换内置模型铺路，老用户升级不重解压）。
> 引擎升级结论：推荐 sherpa-onnx 中文 KWS（关键词检测，3.3MB、准确率 95.2%），
> 但存在 Android 15 ARM64 初始化死锁风险（issue #2734），需真机联调后切换。
> **v3.5 = 口令防误判增强**（应对「视频语音里出现口令字眼就被触发」）：① 精简默认词表
> 18 词 → 4 词且纯中文（next/back 有被 grammar 编译器拒的风险，见文末要点 6）；词表 = grammar 解码候选集，词越多误判面越大）；② 首页「推荐口令」一键预设
> （走你/回撤、咻/啪、恢复默认）——口令挑选原则：避开高频词本身（「下一个」解说常说）、
> 避开高频词前缀（「往回」会被「往回看」激活）、发音干脆差异大；③ 连续触发熔断：
> 10 秒内触发满 5 次判定为误触连环，自动停识别 20 秒后恢复（正常洗碗使用远达不到）。
> **v3.4 = 抗抖音背景音**：三层应对——① 词表约束（grammar，默认开）：`new Recognizer(model, 16000, grammarJson)`
> 只解码口令短语表，背景音/旁人说话不在表内不可能被识别成口令；② NoiseSuppressor/AGC
> 反射挂载（实验性开关，默认关）；③ isMusicActive 轮询检测媒体播放（仅提示）。
> **v3.4.1 = 修复 v3.4「开启监听约 1 秒闪退」**：SpeechService.shutdown() 只 release
> AudioRecord、不停 RecognizerThread；v3.4 的无谓重建（启动标志未清）+ 裸 shutdown 让孤儿线程
> read 返回负值抛未捕获 RuntimeException → 进程崩。修复：cancel()（interrupt+join）→ shutdown()
> 安全停序列、startVosk 成功即清重建标志、重建异步延迟 300ms、grammar 编译失败回退全词表。
> **v3.3 = 冷却间隔开放自定义**：两次翻页执行之间的最短间隔从硬编码 800ms 改为
> prefs `cooldown_ms`（毫秒，MainActivity 按秒输入，0~60 秒 clamp，0 = 不冷却），
> 与 v3.2 的 partial/result 去重相互独立——去重管「同一句不重复」，冷却管「两次口令之间」。
> **v3.2 = 修复「偶尔执行两次」+ 开放自定义口令**：Vosk 对一句话会先后回调
> onPartialResult（说完即翻）与 onResult（静音判定后），两条路径都会触发同一条口令，
> 此前只靠 800ms 冷却兜底——说完停顿稍长就执行第二次。v3.2 加双层去重：
> partial 同句一次性锁（规范化文本相同即同一句）+ result 2.5 秒同方向去重窗口。
> 口令词表开放自定义：prefs `voice_cmds_next`/`voice_cmds_prev`（逗号分隔），
> VoiceService 每秒刷新缓存，改完即时生效。
> **v3.1 = 修复后台拉不起 ShortX**：ColorOS 会静默拦截后台 App 的 `startActivity`（DeepLink 拉起失败、不抛异常），
> 新增「通知触发」为默认执行通道——App 发一条内容为魔法串 `HF_CMD_NEXT`/`HF_CMD_PREV` 的通知，
> ShortX 的「有新通知」规则按包名+内容匹配收到即执行。这是系统级钩子，后台永远放行，
> 不需要后台弹出界面、不需要悬浮窗权限。DeepLink 保留为备选（`exec_channel` 首选项切换）。
> **v3.0 = root 全移除版**：删除 Swiper（root 直注）/ Injector（双模式编排）/ Verify（截屏与 dumpsys 验证）三个类，
> 反馈精简为 4 事件（震动只在成功识别口令时出现）。
> v2.7 及以前的历史技术说明（动态坐标、双通道验证、ShortX 双模式）保留在文末「历史章节」，仅供追溯。

## 工程结构（v3.7）

```
src/com/handsfree/douyin/
├── MainActivity.java    # 界面：状态、结果、DeepLink 配置、自定义口令（含推荐预设）、冷却间隔、执行通道单选、识别设置（词表约束/降噪）、开关、测试按钮、日志
├── VoiceService.java    # 前台服务：双引擎编排（Vosk/KWS）+ 口令分发（Trigger）
├── KwsEngine.java       # v3.7 新增：sherpa-onnx 关键词检测引擎（AudioRecord + KeywordSpotter）
├── Trigger.java         # 触发编排：前台门禁 → 按 exec_channel 分发（通知/DeepLink）→ 分级反馈
├── ShortX.java          # ShortX DeepLink 执行器（shortx://run?id=xxx，含 fromBackground 悬浮窗辅助）
├── CmdNotifier.java     # 通知触发执行器（v3.1 新增）：发 HF_CMD_NEXT/PREV 魔法串通知
├── OverlayLaunch.java   # 悬浮窗辅助启动（v3.1 新增）：1x1 不可见窗口掩护 startActivity
├── Guard.java           # 抖音前台门禁（UsageStats，fail-open，无需 root）
├── Feedback.java        # 4 事件反馈：震动 + 提示音 + TTS
├── EventLog.java        # 内存事件流水（40 条）
├── HandsFreeApp.java    # Application：全局初始化
└── BootReceiver.java    # 开机自启（Android 14+ 可能被系统限制）
AndroidManifest.xml      # versionCode 16 / versionName 3.7
build.sh                 # 无 Gradle 手工构建脚本（8 步）
res/                     # 图标
```

## 构建依赖（需提前准备）

build.sh 假定以下文件已就位：

| 依赖 | 路径 | 获取方式 |
|---|---|---|
| Android SDK build-tools（aapt2/d8/apksigner/zipalign） | `/tmp/andsdk/android-14/` | dl.google.com 下载 commandlinetools |
| android.jar (API 35) | `/tmp/andsdk/android-35/android.jar` | SDK Manager |
| Vosk Android AAR 0.3.75 | `/tmp/vosk/vosk-android-0.3.75.aar` | Maven Central: `com.alphacephei:vosk-android:0.3.75` |
| JNA AAR 5.14.0 | `/tmp/vosk/jna-5.14.0.aar` | Maven Central: `net.java.dev.jna:jna:5.14.0@aar` |
| Vosk 中文模型 | `assets/model-cn/` | https://alphacephei.com/vosk/models → vosk-model-small-cn-0.22.zip |

**模型处理要点**：解压模型 zip 到 `assets/model-cn/` 后，**必须手工补一个 uuid 文件**（Vosk StorageService 强制读取）：

```bash
python3 -c "import uuid; print(uuid.uuid4())" > assets/model-cn/uuid
```

## 构建

```bash
bash build.sh
```

产物：`build/语音切抖音-v3.4.1.apk`（约 65MB，已签名 + 16KB 页对齐）。

## v3.0：删除所有 root 功能（本次核心改动）

**动机**：用户明确要求「删掉 app root 相关的所有功能，纯粹保留语音触发 ShortX 的 Deeplink 功能」。
滑动执行由 ShortX（Xposed 模块）里的 root 规则完成，本 App 只负责识别与投递。

**删除的类**（共 3 个，约 1100 行）：

| 类 | 原职责 | 去向 |
|---|---|---|
| `Swiper.java` | root `input swipe` 直注（动态坐标 + 备用轨迹） | 坐标参数移入 ShortX 规则 |
| `Verify.java` | 双通道验证（dumpsys media_session / screencap 截屏指纹） | 整体删除，不做页面验证 |
| `Injector.java` | 执行编排（ShortX / root 双模式 + 验证 + 自动重试） | 由 `Trigger.java` 取代（约 60 行） |

**新增的类**：

| 类 | 职责 |
|---|---|
| `Trigger.java` | `next(c, source, l)` / `prev(...)`：调 `ShortX.runNext/runPrev` → OK 播 EV_TRIGGERED / NO_PREV 提示去配链接 / FAIL 播 EV_FAIL；verdict 回调更新通知栏 |
| `Guard.java` | 前台门禁：`hasUsagePermission`（`AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)`）+ `douyinForeground`（`queryUsageStats(INTERVAL_DAILY, now-10s, now)` 判抖音/极速版）。**fail-open**：未授权/异常一律放行只记日志；开关 `fg_guard` 默认开 |

**Feedback.java 重写为 4 事件**（对应用户要求「成功识别了才震动」）：

| 事件 | 震动 | 声音 | TTS |
|---|---|---|---|
| `EV_LISTENING` 就绪 | 双短震 {0,40,60,40} | PROMPT | 「开始监听」 |
| `EV_RECOGNIZED` 识别到口令 ★ | 两短震 {0,50,50,50} | ACK | 播报口令 |
| `EV_TRIGGERED` DeepLink 已拉起 | 单击 {0,25} | BEEP | — |
| `EV_FAIL` 拉起失败 | 长震组 {0,150,100,150} | NACK | 「切换失败」 |

删除：EV_HEARD（听到任何声音就震，600ms 节流）、EV_OK/EV_UNCHANGED/EV_UNKNOWN（验证类）、
EV_INJECTED、fb_retry 开关、Handler 节流逻辑。**环境噪声不再产生任何震动。**

**清单变更**：versionCode 8 / versionName 3.0；新增 `PACKAGE_USAGE_STATS`
（`tools:ignore="ProtectedPermissions"`，供 Guard 查询前台应用）；保留 `<queries>` shortx scheme。

**VoiceService/MainActivity 变更**：

- `executeCommand`：`Swiper.isDouyinForeground()` → `Guard.douyinForeground(this)`（未授权 usage 时记日志提示）；`Injector.next` → `Trigger.next`
- `onPartialResult`：只更新 lastHeardText，不再震/响
- 启动日志：「执行方式：ShortX DeepLink（…）——由 ShortX 执行 root 规则，本 App 不使用 root」
- MainActivity：删 Root 状态行、测试翻上一个/下一个按钮、exec_mode RadioGroup、fb_retry 开关、execStatus；
  新增 linkStatus（`ShortX.available` 绿/红）、etNext/etPrev 两个 DeepLink 输入框、「测试 DeepLink」按钮（走 `Trigger.next`）、
  fg_guard 开关、「授予使用情况访问权限」按钮（`Settings.ACTION_USAGE_ACCESS_SETTINGS`）

**dex 校验**：`dexdump` 全量扫描确认无 `su -c` / `getevent` / `input swipe` / `screencap` / `dumpsys` 残留
（唯一匹配是 MainActivity 帮助文本里的说明文字「root input swipe 在规则里」）。

## ShortX DeepLink 执行（v2.7 引入 · v3.0 曾是唯一方式 · v3.1 降级为备选通道）

**动机**：滑动执行需要 root 或无障碍服务。ShortX 已有验证过的 root 执行链路，
且提供 `shortx://run?id=xxx` DeepLink 直接触发规则——把执行环节交给 ShortX，
本 App 不需要 root/无障碍。

`ShortX.java`（v3.0 起注释同步更新，删掉 Injector 验证层描述）：

| 方法 | 说明 |
|---|---|
| `runNext(c)` / `runPrev(c)` | `startActivity(ACTION_VIEW, shortx://run?id=...)` + `FLAG_ACTIVITY_NEW_TASK`；返回 OK/FAIL/NO_PREV（上一个未配链接） |
| `available(c)` | `resolveActivity` 探测 scheme 是否被响应（配 `<queries>` 声明，见下） |
| `urlNext(c)` / `urlPrev(c)` | 读 `hf` 首选项 `shortx_url_next`（默认 DA-1d634d7d-…）/`shortx_url_prev`（可空） |
| `testRun(a)` | App 内自检手动触发 |

**关键坑**：

- **后台弹出界面权限**：从前台服务 `startActivity` 受 ColorOS 后台 activity 启动限制拦截（不抛异常、静默失败），必须去设置给 App 开「后台弹出界面」
- **`<queries>` 包可见性**：Android 11+ 不声明就 `resolveActivity` 判不出 ShortX 在不在：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.VIEW"/>
        <data android:scheme="shortx"/>
    </intent>
</queries>
```

- **v3.0 无兜底**：DeepLink 投递失败直接 EV_FAIL 提示，不再回退 root 直注（root 代码已删）

## v3.1：通知触发通道（默认）+ DeepLink 悬浮窗辅助（备选）

**动机（用户实测反馈）**：「App 放在后台，即便能正常识别到语音下一个，也没办法拉起 Shortcuts 的 DeepLink」。
根因：ColorOS 对后台 App 的 `startActivity` 做静默拦截——不抛异常、无日志、`startActivity` 照常返回，
表现就是「识别成功、ShortX 没反应」。

### 调研结论：ShortX「有新通知」触发器

从 ShortX-Files 官方共享规则仓库（github.com/ShortX-Repo/ShortX-Files）的「取件码」规则验证了 schema：

```json
{"@type": "type.googleapis.com/NotificationPosted",
 "record": {"apps": [{"pkgName": "com.handsfree.douyin"}],
            "contentText": "HF_CMD_NEXT",
            "contentRegexOptions": "RegexMatchOptions_ContainsMatchIn"}}
```

`apps` 是**包名列表**、`contentText` 是内容正则、`ContainsMatchIn` = 包含匹配。
通知是系统级钩子——后台 App 发通知永远被系统允许，**这是绕开 ColorOS 后台拦截的关键**。

### 新增类

| 类 | 职责 |
|---|---|
| `CmdNotifier.java` | `postCommand(ctx, cmd)`：检查 `areNotificationsEnabled()` → 建 IMPORTANCE_MIN 无声无震通道 `hf_cmd` → `nm.notify("hf_cmd_"+ts, 21, notif)`（标题「抖音手势控制」、内容=cmd）→ 主线程 1500ms 后 cancel。唯一 tag 保证是「新通知」而非更新（NotificationPosted 只在新增时触发）；异常/禁用返回 false 由调用方回退 |
| `OverlayLaunch.java` | DeepLink 备选通道的辅助：`canDrawOverlays` 检查 → 挂 1x1 `TYPE_APPLICATION_OVERLAY` 不可见窗口（FLAG_NOT_TOUCHABLE\|NOT_FOCUSABLE\|LAYOUT_NO_LIMITS）→ `startActivity` → finally 800ms 后 removeView。利用 ColorOS 对悬浮窗可见窗口的放行。**需要主线程**（WindowManager.addView 要求 Looper） |

### Trigger.fire 双轨分发

```java
String channel = sp.getString("exec_channel", "notify");  // 默认通知触发
if ("deeplink".equals(channel)) fireDeepLink(...); else fireNotify(...);
// fireNotify: CmdNotifier.postCommand 成功 → EV_TRIGGERED；失败 → 自动回退 fireDeepLink
// fireDeepLink: ShortX.runNext(c, !appVisible)，fromBackground=true 时走 OverlayLaunch
```

- `MainActivity.onResume/onPause` 维护 `Trigger.appVisible` 标志，决定 DeepLink 是否走悬浮窗辅助路径
- `Trigger.fire` 检测 `Looper.myLooper()`，非主线程先 post 到主线程（Vosk 回调线程 → overlay 需要主线程）
- 通知通道不可用（`areNotificationsEnabled()` false）时**自动回退 DeepLink**，无需用户干预
- ShortX.java 新增 `OK_OVERLAY=3` 结果码与 `fromBackground` 重载；`runPrev` 保留 NO_PREV 语义
- 清单新增 `SYSTEM_ALERT_WINDOW` 权限（仅悬浮窗辅助需要，通知通道本身不需要任何权限）

### 配套规则

ShortX 侧需导入两条「有新通知」规则（`ShortX-抖音通知触发-下一个/上一个.txt`）：
facts=NotificationPosted（包名 + `HF_CMD_NEXT`/`HF_CMD_PREV` 包含匹配）、conditions=ScreenIsOn、
actions=ShellCommand（`su -c "input swipe 707 2100 707 1282 120"` / 下滑反向）+ Vibrate 80ms。
**包名匹配写在规则里，重新导入规则 id 变化也不受影响**——这是通知通道相对 DeepLink 的另一个优势。

## v3.2：修复「偶尔执行两次」+ 自定义口令

### 根因：partial / result 双路径重复触发

Vosk `RecognitionListener` 对同一句话会回调两条路径：

| 回调 | 时机 | 触发？ |
|---|---|---|
| `onPartialResult` | 边说边出词级结果（约 0.2~0.5 秒） | 是——说完即翻，不等停顿 |
| `onResult` | 静音段判定后出最终结果（说完后约 0.5~1 秒） | 是（v3.2 前去重） |

两条路径都会调用 `executeCommand`，此前只靠 `COOLDOWN_MS = 800` 兜底：
两条路径间隔 < 800ms 时能挡住；**说完停顿稍长（> 800ms）result 才到达，就会执行第二次**。
`onFinalResult`（Vosk 偶发与 onResult 双回调）同理。

### v3.2 双层去重（VoiceService.java）

1. **partial 同句一次性锁**（`partialFiredKey`）：partial 规范化文本（`normalize()`：去标点+小写）相同即同一句，
   只触发第一次；partial 不再含口令词时解锁，允许下一句重新触发
2. **result 去重窗口**（`RESULT_DEDUP_MS = 2500`）：`onResult`/`onFinalResult` 里若
   `cmd == lastFireDir`（同方向）且距上次实际触发 < 2.5 秒，记事件日志「与刚执行的口令相同，忽略重复触发」后跳过。
   窗口要盖住「说完 → 静音判定 → result 到达」整条链路（通常 < 1.5 秒），又不影响正常快速连说（间隔通常 > 3 秒）
3. `executeCommand` 改为返回 `boolean`（真正触发才更新 `lastFireDir`/`lastActionAt`，供调用方更新 partial 锁）；
   被冷却/门禁拦截时返回 false，不上锁——门禁拦截的那句下次还能触发

### 自定义口令

- 字段：`DEFAULT_NEXT_WORDS`/`DEFAULT_PREV_WORDS`（原 `NEXT_WORDS`/`PREV_WORDS` 改名）+ volatile `nextWords`/`prevWords`
- prefs：`voice_cmds_next` / `voice_cmds_prev`（逗号/空格分隔；留空或全无效 → 回退默认词表）
- 刷新：`onCreate`/`onStartCommand` 各刷一次 + `onPartialResult` 入口 `refreshWordsIfStale()`（1 秒节流），改完即时生效
- MainActivity：测试执行按钮下方新增两个 EditText（即改即存 prefs，无保存按钮）
- `parseWordList()`：split `[,，\s]+` → trim → 小写；`parseCommand` 与 partial 锁共用 `normalize()`

## v3.3：冷却间隔开放自定义

- 原 `private static final long COOLDOWN_MS = 800` 硬编码 → `DEFAULT_COOLDOWN_MS` + volatile `cooldownMs`
- prefs：`cooldown_ms`（long，毫秒）。MainActivity 输入框按**秒**输入（`InputType.TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_DECIMAL`，
  `%g` 格式显示当前值），afterTextChanged 里 parse → ×1000 → clamp 0~60000 → `putLong`；空值/解析失败回落默认 800
- VoiceService `refreshWords()`（v3.3 起实际是「运行时设置」刷新：口令词表 + 冷却间隔）同步读 `cooldown_ms`
  并 clamp；仍走 1 秒节流的 `refreshWordsIfStale()`，onCreate/onStartCommand 各刷一次
- `executeCommand` 冷却判断改为 `cooldownMs > 0 && now - lastActionAt < cooldownMs`——**填 0 时整段跳过**，
  仅保留 v3.2 的 partial/result 去重兜底（同一句不会执行两次，但快速连说两句会执行两次）
- 与 RESULT_DEDUP_MS（2500，v3.2「同一句」去重窗口）语义独立：冷却管「两次口令之间」，去重管「同句话的两条回调路径」

## v3.4：抗抖音背景音（词表约束 + 降噪 + 出声提示）

**问题**：抖音视频有人声/背景音乐时与口令一起进麦克风，全词表（几万词）识别容易乱识别。

### ① 词表约束（grammar，默认开，prefs `grammar_mode`）

- `Recognizer.setGrammar()` 底层是 `LibVosk.vosk_recognizer_set_grm`（JNA 短名映射，`Recognizer(Model, float, String)`
  构造器走 `vosk_recognizer_new_grm`）——small-cn 模型支持 dynamic grammar FST 编译
- `buildGrammarJson()`：LinkedHashSet 去重，词表 = nextWords + prevWords + 各唤醒词 +
  prefix 开启时的「唤醒词+口令」组合（如「抖音下一个」）；JSON 引号/反斜杠清洗
- **不用热 setGrammar**（Vosk 文档明示 setGrammar 非线程安全，SpeechService 解码线程在跑）——
  改为**重建识别器**：`needRebuildRecognizer` 标志 + 2 秒防抖（用户连续改口令词表只重建最后一次），
  走既有的 `stopVosk → new Recognizer → new SpeechService → startListening` 路径，与亮息屏切换同机制
- 关开关 = `new Recognizer(model, 16000.0f)` 无 grammar 构造（全词表恢复）
- `activeGrammarJson` 字段记录当前生效 grammar，用于检测词表内容变化
- 代价（文档明示）：词表外变体不被识别；解法是把变体加进口令输入框

### ② NoiseSuppressor / AGC 反射挂载（实验性，prefs `denoise`，默认关）

- 反射 `SpeechService.recorder`（private final AudioRecord）→ `getAudioSessionId()` →
  `android.media.audiofx.NoiseSuppressor.create(session)` / `AutomaticGainControl.create(session)`
- 注意包名是 `android.media.audiofx`（不是 android.media）；AudioRecord 音源本就是
  VOICE_RECOGNITION(6)，设备侧处理已关，这里补软件侧
- 挂载时机：`startListening()` 之后（session 已激活）；开关切换热挂载/热卸载（release），
  不重建识别器；全异常路径只记 EventLog，不影响识别主链路
- 实测预期：对稳态噪声（水声/音乐底噪）有效，非稳态人声混合效果有限

### ③ 媒体播放检测（仅提示）

- `AudioManager.isMusicActive()`（STREAM_MUSIC 有活跃播放即 true，抖音视频声音走此流）
- 在 refreshWords 的每秒轮询里顺带检测，false→true 跳变且服务在跑时记一条 EventLog 提示
- 原本想用 `registerAudioPlaybackCallback` + `AudioPlaybackConfiguration.getPlayerState()`，
  但 android-35/android.jar 里 getPlayerState() 是隐藏 API（javap 不可见）——换 isMusicActive，
  公开 API、无需回调、还省一个注册点

## v3.5：口令防误判增强（推荐预设 + 熔断 + 词表精简）

**背景**：用户反馈「视频语音里出现口令的字眼就会被触发」。grammar 词表约束是白名单
（解码只在词表内挑最像的），它挡住了「不在词表内的声音」，但挡不住「词表内口令本身
在解说里出现」——「下一个」正是视频解说高频词，必然撞车。且词越短越容易被解说「捎带」：
词表只有「往回」时，解说喊「往回看」最接近的词表项就是「往回」→ 照样触发。

**口令挑选三原则**（写入 App 内提示与使用指南）：

1. 避开高频词本身（「下一个」「换一个」「回去」解说常说）
2. 避开高频词的前缀（「往回」会被「往回看」激活、「退一」会被「退一步」激活）
3. 发音干脆、音节差异大（爆破音/卷舌在嘈杂环境更抗干扰）

**改动（3 处）**：

1. **精简默认词表 18 词 → 4 词且纯中文**：DEFAULT_NEXT_WORDS `下一个/下个`、
   DEFAULT_PREV_WORDS `上一个/上一条`。原词表含「翻页/换一个/回去/再看一遍」
   等解说常见词，是默认配置下误判面大的直接原因。词表 = grammar 候选集，越短越抗误判。
2. **首页「推荐口令」一键预设**（MainActivity）：三个按钮——「推荐:走你/回撤」
   （2 字生僻组合：网络口语+金融词，普通视频极少连读，且不是任何常见词的前缀）、
   「单字:咻/啪」（拟声信号音，视频人声几乎不会单字说；grammar 兜底只输出单字）、
   「恢复默认」（清空两框即回落默认词表）。点击即填入并自动保存。
3. **连续触发熔断**（VoiceService）：10 秒内触发满 5 次 → `pauseForBurst()`
   停识别 + 失败音 + 通知提示，20 秒后 `ensureRunning(0)` 自动恢复。防的是解说
   连环撞词（如 PK 直播一直喊「下一个」）——冷却/去重只能减速，挡不住每次都是新的一句。
   正常洗碗使用平均 2 秒说 5 次口令达不到，不会误伤。

**单字 vs 双字触发成功率分析**（回答用户「2 字触发成功率很低」的疑虑）：

- grammar 模式下词表越短、解码延迟越低，partial（说完即翻）出得越快——**2 字理论上
  比 3 字默认词触发更快**；若实测 2 字触发率低，常见原因是词表里混了多个发音差异大的词
  或发音本身含糊，建议词表只留 1 个词实测
- 单字的真实短板不是触发慢，而是**起播鲁棒性**：单音稍糊就被 VAD 整句漏掉；
  2 字有两处起播机会。但单字胜在撞车面最小（选对拟声字时）
- 综合推荐：**2 字生僻组合（走你/回撤）是触发率与误判率的最佳平衡**；
  追求极限速度可用单字（咻/啪），接受稍高的漏识别率

## v3.4.1：修复「开启监听约 1 秒闪退」（v3.4 热修复）

**现象**：v3.4 点「开启语音监听」约 1 秒后 App 闪退（用户实测反馈）。

**根因（读 vosk-api SpeechService 源码确认）**：

```java
// org.vosk.android.SpeechService（0.3.75）
public void shutdown() { recorder.release(); }   // 只释放录音器，不停识别线程！
// RecognizerThread.run() 循环内：
int nread = recorder.read(buffer, 0, buffer.length);
if (nread < 0) throw new RuntimeException("error reading audio buffer");  // ← 崩溃点
```

崩溃链：① `refreshWords()` 在服务启动前（`activeGrammarJson` 仍为 null）就置了
`needRebuildRecognizer=true`；② `startVosk()` 成功后**没有清该标志**；③ 约 0.2~1 秒后第一个
`onPartialResult` 到达 → `rebuildRecognizerIfNeeded()` 消费该标志 → `startVosk()` 重建；
④ 重建时旧 service 只调了裸 `shutdown()`（release recorder，**不 interrupt/join 识别线程**）；
⑤ 孤儿 RecognizerThread 下一轮 `recorder.read()` 在已 release 的 recorder 上返回负值 →
抛未捕获 RuntimeException → 默认 UncaughtExceptionHandler → **进程崩溃**。

（v3.3 及以前不崩只因日常路径从不热重建——息屏监听默认开着，stopVosk 只在点「停止监听」/
出错重试时才走；v3.4 首次引入重建即踩中。）

**修复（4 处）**：

1. **安全停序列** `shutdownVoskService()`：`cancel()`（内部 interrupt + join，等线程真正退出；
   cancel 而非 stop——不需要 onFinalResult 回调）→ `shutdown()`（release）。
   startVosk/stopVosk/onDestroy 全部改走此方法，代码内已无裸 `shutdown()`
2. **startVosk 成功即清 `needRebuildRecognizer=false`**——消灭启动后那次无谓重建（直接触发因素）
3. **重建异步化**：`rebuildRecognizerIfNeeded()` 改为 `handler.postDelayed(..., 300)`，
   绝不在 Vosk 回调栈内重入停/建识别器；并挂到 onResult（静默期无 partial 时也能消费）
4. **grammar 编译失败回退**：`new Recognizer(model, 16000, gjson)` try-catch Throwable →
   失败则 `grammarEnabled=false` + 无 grammar 构造 + 事件日志，绝不让用户用不了识别

另：旧 recognizer 现在正确 `close()`（修复 native 内存泄漏——线程 join 后再 close，无并发）。

**教训**：Vosk SpeechService 的 stop 语义是「stop()/cancel() 停线程，shutdown() 释放 recorder」，
二者必须成对；单独 shutdown() = 给孤儿线程留 use-after-free。

## v3.6：代码审查优化 + 识别引擎升级评估

### 一、代码审查：12 处优化

按「问题 → 改动 → 收益」记录。前两条是真实隐患，其余是性能/体验/健壮性。

| # | 位置 | 问题 | 改动 | 收益 |
|---|---|---|---|---|
| 1 | `refreshWordsIfStale()` | 在 Vosk 识别回调线程里直接执行 `SharedPreferences` 读取 + `AudioManager.isMusicActive()`（binder IPC）+ grammar 拼串 | 判断仍在回调线程，实际刷新 `handler.post` 到主线程（`refreshWordsTask`），先占位时间戳避免重复 post | 回调线程不再被 IPC 阻塞，partial 输出更及时（音频缓冲不堆积） |
| 2 | `onTimeout()` | 直接在 Vosk 回调栈内调 `startVosk()`（停旧建新） | 改为 `handler.post`，且校验 `running && modelReady` | 消除 v3.4.1 同类崩溃路径（回调栈内重建） |
| 3 | `loadModelAsync()` 异常分支 | 失败后 `ensureRunning(3000)` 无限重试——模型损坏时每 3 秒刷日志、耗电、永不成功 | 新增 `loadFailures` 计数，≥3 次记录日志并停止重试；成功后清零 | 损坏模型不再死循环 |
| 4 | `executeCommand()` 冷却分支 | 通知停在「冷却中」，用户误以为服务挂了 | 只记 EventLog + 1.2 秒后恢复「离线识别中，说「X」」 | 状态栏语义正确 |
| 5 | `MainActivity.modelSizeText()` | `refresh()` 每次递归遍历 66MB 模型目录做 stat，只为显示「已解压 XX MB」 | 结果缓存，仅 `modelReady` 状态变化时重算 | 去掉每次刷新几十次文件 stat |
| 6 | `normalize()` | 每次 partial 调 `String.replaceAll(正则)`，每次都编译 Pattern | 预编译 `static final Pattern PUNCT` | 高频路径省正则编译 |
| 7 | `containsAny()` | 每个词 `toLowerCase()`（词表已小写） | 去掉重复转换；顺带修复被压坏的缩进 | 微优化 + 可读性 |
| 8 | `checkNativeLibs()` | 每次 `ensureRunning` 都调 `System.loadLibrary`（息屏轮询每秒一次） | `static nativeLibsOk` 缓存 | 去掉每秒 native 库表查询 |
| 9 | `startVosk()` 通知文案 | 硬编码「说「下一个」」，用户改成「走你」后文案错误 | 新增 `currentCmdHint()` 取 `nextWords.get(0)` | 文案跟随自定义口令 |
| 10 | 媒体播放检测 | 每秒一次 `isMusicActive()` IPC | 节流到 5 秒一次（`lastMediaCheckAt`） | 减少 80% 无效 IPC |
| 11 | 媒体提示文案 | 无论 grammar 开关都写「词表约束已开启」 | 按 `grammarEnabled` 分支提示 | 提示与真实配置一致 |
| 12 | 模型完整性 | 无版本标记——将来换内置模型，旧解压目录不会被覆盖（装了新版还在用旧模型） | 新增 `assets/model-cn/version.txt`（`cn-0.22`）+ `isModelVersionOk()`；旧目录无 version.txt 时补写当前版本并放行 | 老用户升级不重解压 66MB；未来换模型可自动覆盖 |

`assets/model-cn/version.txt` 随模型一起被 `copyAssetDir` 递归拷贝；`isModelComplete` 判据不变。

### 二、识别引擎升级评估

**现状**：Vosk `vosk-model-small-cn-0.22`（42MB，Kaldi nnet3 + 3-gram，2022 年前后发布）。
问题不是「不能用」，而是：中文识别率一般（嘈杂环境尤甚）；grammar 是「限制解码空间」
而不是「关键词检测」——没有置信度阈值可调，视频里说出口令词必然触发（v3.5 只能靠换词规避）。

**候选方案对比**（均为离线、Apache-2.0 系许可或开源可用）：

| 方案 | 体积 | 流式 | 中文能力 | 适配口令场景 |
|---|---|---|---|---|
| **sherpa-onnx KWS zipformer-wenetspeech-3.3M** | **3.3MB** | 实时（0.8x） | 关键词检测专用，准确率 95.2% | ★★★★★ 只输出命中关键词，支持 `:权重` `#阈值`，阈值可调抗误报 |
| sherpa-onnx SenseVoice int8 | 60MB | 否（需 VAD 分段） | 阿里通义 FunAudioLLM，工业级，含标点/情感 | ★★★☆ 识别率最高，但非流式，触发延迟 0.5~0.8s |
| sherpa-onnx paraformer-zh-small | 30MB | 否 | 阿里达摩院 Paraformer 轻量版 | ★★★ |
| sherpa-onnx streaming-zipformer-zh | 80MB | 是 | 流式中文，WER≈14% | ★★★ 模型权重许可不明（部分标注 research only） |

**结论：推荐 KWS 路线，而不是「换更大的通用模型」**。理由：

1. 本 App 只需要判断「有没有说到这 1~2 个词」，不需要转写整句话——KWS 是为这个任务设计的
2. 模型仅 3.3MB，APK 可从 65MB 降到约 25MB（去掉 42MB Vosk 模型 + 55MB .so，换 ~10MB JNI）
3. 关键词文件可动态生成（用户改口令即重写 `keywords.txt`），阈值可调——比 Vosk grammar 多一个「抗误报」维度
4. 实时率 0.8x，单核可跑，功耗低于 Vosk 大词表解码

**三个必须正视的风险**：

1. **Android 15 ARM64 初始化死锁**：sherpa-onnx issue #2734（2025-10）报告
   `OnlineRecognizer` 构造在 Android 15 / 小米 ARM64 上卡死无返回。目标机型一加 Ace 5
   同属 Android 15 + ARM64，必须真机验证（KWS 走 `KeywordSpotter`，代码路径不同，但不能假设没事）
2. **无法在构建环境真机验证**：JNI 初始化、模型路径、AudioRecord 生命周期只能靠实机暴露；
   历史 Vosk 集成踩过 JNA 符号 / d8 丢类 / 16KB 对齐 / 回调名四个坑，sherpa 同样会有
3. **二进制获取受限**：官方 `sherpa-onnx-v2.0.0-android.tar.bz2` 在本构建环境下载超时
   （GitHub objects 不可达）；需改用 ModelScope/Gitee 镜像或让用户侧提供

**建议的落地路径（下一轮）**：

1. 做「双引擎实验版」：默认仍 Vosk（零风险），设置里加「识别引擎：Vosk / sherpa-KWS（实验）」
2. sherpa 初始化全程后台线程 + 8 秒超时看门狗（超时判定失败并回退 Vosk），所有 native 调用
   `try-catch Throwable`——即使死锁也只是该线程卡住，不拖垮主链路
3. 用户真机验证通过（能初始化、能识别、不崩）后，再切换默认为 sherpa-KWS 并瘦身 APK

## v3.7：双引擎版（Vosk / sherpa-onnx KWS）

### 一、为什么是 KWS 而不是「更大的通用模型」

口令场景只需要回答「有没有说到这 1~2 个词」，不需要整句转写。KWS 模型专为此设计：
只输出命中的关键词 + 置信度，**不命中就完全静默**。Vosk grammar 则是「把解码空间限制在词表内」，
视频里说出口令词仍会输出——这是 v3.5 只能靠换生僻词规避的根因。KWS 从机制上解决了这个问题，
且模型仅 4.9MB（int8），比 Vosk 的 42MB 小一个量级。

### 二、依赖来源（构建环境实测可用）

| 依赖 | 来源 | 体积 |
|---|---|---|
| `sherpa-onnx` Java 类 + JNI .so | Maven Central `com.bihe0832.android:lib-sherpa-onnx:6.25.21`（解包 AAR：`classes.jar` + `jni/{arm64-v8a,armeabi-v7a}/libsherpa-onnx-jni.so`） | classes 74KB + so 4.7MB/2.9MB |
| Kotlin 运行时 | Maven Central `org.jetbrains.kotlin:kotlin-stdlib:1.9.24` | 1.7MB |
| KWS 中文模型（int8） | ModelScope `pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01` | 4.9MB |
| 关键词拼音生成 | `sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type ppinyin`（需 pypinyin + sentencepiece） | 离线生成一次 |

sherpa-onnx 的 **onnxruntime 已静态链接进 libsherpa-onnx-jni.so**，不需要额外 .so。
模型放 `assets/kws/`，由 `KeywordSpotter(AssetManager, config)` 直接读——**无需解压到外部存储**，
这是相对 Vosk 的一大简化（Vosk 要解压 66MB 到 externalFilesDir）。

### 三、Android 侧调用要点（KwsEngine.java）

```java
// 模型配置：11 参（Kotlin data class 非空检查 → 其余模型配置必须传空对象而非 null）
OnlineModelConfig mc = new OnlineModelConfig(tr,
        new OnlineParaformerModelConfig(), new OnlineZipformer2CtcModelConfig(),
        new OnlineNeMoCtcModelConfig(), "kws/tokens.txt", 2, false, "cpu", "zipformer2", "", "");
KeywordSpotterConfig cfg = new KeywordSpotterConfig(
        new FeatureConfig(16000, 80, 0f), mc, 4, "kws/keywords.txt",
        1.5f /*score*/, 0.25f /*threshold*/, 1);
KeywordSpotter kws = new KeywordSpotter(getAssets(), cfg);

// 采集循环（100ms 帧）
OnlineStream s = kws.createStream(keywordsString);   // 动态词表：改口令只需重建 stream
s.acceptWaveform(floatBuf, 16000);                   // 第二参是采样率，不是长度
while (kws.isReady(s)) {
    kws.decode(s);
    String hit = kws.getResult(s).getKeyword();      // 空串 = 未命中
    if (!hit.isEmpty()) { emit(hit); kws.reset(s); }
}
```

**动态词表**：`createStream(keywords)` 接受多行串（`拼音 token @显示词`），
所以用户改口令不用重建引擎，只需 stop → start(新串)。

### 四、四个踩坑（都已解决）

1. **官方 `sherpa-onnx-v2.0.0-android.tar.bz2` 只有 x86_64**（模拟器用），不含 arm64-v8a——
   直接下载用不了手机。改用 Maven Central 的 AAR（含 arm64 + armeabi）。
2. **Kotlin 运行时缺失**：AAR 的 classes.jar 不含 `kotlin/`（0 个类），但 API 引用了
   `kotlin.jvm.internal.DefaultConstructorMarker` / `Intrinsics` → 运行时必然
   `NoClassDefFoundError`。必须单独打包 `kotlin-stdlib.jar` 进 d8。
3. **关键词行内权重格式**：`z ǒu n ǐ:1.5 #0.25 @走你` 会被解析成 token `ǐ:1.5`
   （报 `Cannot find ID for token ǐ:1.5`）。权重必须与最后一个 token **用空格隔开**
   （`ǐ :1.5`），或干脆不用行内权重、统一走 config 的 `keywordsScore/keywordsThreshold`。
   App 采用后者（`拼音 @词`），已验证 16 个词全部解析成功。
4. **`acceptWaveform(float[], int)` 第二参是采样率**（16000），不是样本数——传错会导致
   特征提取完全错位（Vosk/OnlineRecognizer 同款签名，容易惯性写错）。

### 五、验证方法（无真机条件下的最优解）

用 Python 版 `sherpa-onnx` 加载**同一套模型 + 同一份 tokens + 同一份 keywords**，
跑官方 7 段真实录音：

| 音频 | 命中 |
|---|---|
| 0/1/2.wav | 无（负样本，验证零假阳性） |
| 3.wav | 文森特卡索、法国 |
| 4.wav | 蒋友伯、女儿 |
| 5.wav | 周望军、落实 |
| 6.wav | 朱丽楠、见面会 |

同时验证：静音输入 0 命中、16 个口令词全解析、生产参数（score 1.5 / threshold 0.25）
与默认参数命中率一致。**结论：模型、词表、检测流程均正确**，剩余不确定性只在
Android JNI 初始化层。

### 六、风险与回退

- **Android 15 初始化卡死**（sherpa-onnx issue #2734）：`startKws()` 带 10 秒看门狗，
  未就绪则自动写 `engine_kws=false`、释放引擎、回退 Vosk，并在通知/日志说明。
- **native crash 无法拦截**：若 .so 本身崩溃，进程会死——标为实验性，默认关闭。
- **prefix（先说抖音）在 KWS 模式不生效**：关键词串不含「抖音+词」组合；KWS 本身误报率低，
  该开关在 KWS 模式下被忽略。
- **词库限制**：只能识别 `assets/kws/keywords_map.txt` 里的 16 个预置词；
  用户自定义词若不在库内会被跳过，全部跳过则自动回退 Vosk 并提示。

### 七、构建改动（build.sh）

- `javac -cp` 与 `d8` 输入追加 `sherpa-classes.jar`、`kotlin-stdlib.jar`
- 打包 `lib/{arm64-v8a,armeabi-v7a}/libsherpa-onnx-jni.so`（`zip -0` 不压缩 + 16KB 对齐）
- `assets/kws/` 随 `aapt2 -A assets` 自动打入（4.9MB）
- APK：65MB → **77MB**

## v3.7.1：修复 KWS 引擎无法启动（NoClassDefFoundError）

**现象**（真机 v3.7 实测）：打开「用 sherpa 关键词引擎」→ 停止/开启监听 → 约 10 秒后看门狗回退：

```
14:03:32  KWS 引擎：NoClassDefFoundError: com.k2fsa.sherpa.onnx.KeywordSpotter
14:03:42  KWS 引擎 10 秒未就绪（...），已自动回退 Vosk
```

注意：看门狗与回退逻辑本身工作正常（这是 v3.7 设计的保命机制生效）。

**排查过程**：

1. 先怀疑多 dex：`ls build/dex/` 只有一个 classes.dex，APK 内也只有 classes.dex → 排除
2. 再查类是否真在 dex：`dexdump | grep "Class descriptor"` → `KeywordSpotter`、`KeywordSpotter$Companion`、
   985 个 `kotlin/*` 类全部在（`class_defs_size=1239`）→ 排除打包缺失
3. 于是怀疑类初始化失败。`javap -c KeywordSpotter` 看 `<clinit>`：静态块里
   `System.loadLibrary("sherpa-onnx-jni")` ——即类初始化会加载 JNI 库
4. `readelf -d jni/arm64-v8a/libsherpa-onnx-jni.so`：

```
(NEEDED) Shared library: [libonnxruntime.so]   ← 动态依赖！
(NEEDED) Shared library: [libandroid.so] [liblog.so] [libm.so] [libdl.so] [libc.so]
```

**根因**：AAR 只提供 `libsherpa-onnx-jni.so`，**onnxruntime 是动态链接而非静态链接**（v3.7 文档里
「onnxruntime 已静态链接」的判断是错的）。缺 `libonnxruntime.so` → `dlopen` 失败 →
`<clinit>` 抛 `UnsatisfiedLinkError` → 类标记为初始化失败 → 上层 catch 到
`NoClassDefFoundError`（消息只有类名，不带原因），真正的错误信息在第一次 loadLibrary 时被
`try-catch ignore` 吞掉了。

**修复（3 处）**：

1. 补 `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` 的 `arm64-v8a/libonnxruntime.so`
   （17.6MB；其自身 NEEDED 全是系统库，可独立加载）。ORT 1.20 向后兼容旧 API 版本请求，
   可满足 sherpa 编译期请求的 ORT API
2. `KwsEngine.initAsync()` 不再静默：loadLibrary 失败直接
   `initError = "JNI 库加载失败：..."` 并回调 onError——下次任何 dlopen 问题都能一眼看到
3. **KWS 仅打包 arm64-v8a**：onnxruntime 每个 ABI 17MB/12MB，两个 ABI 会让 APK 破 100MB；
   32 位设备开开关会加载失败并自动回退 Vosk（Vosk 仍双 ABI 可用）

**结果**：APK 91.9MB（v3.7 的 77MB + 17.6MB ORT）。`libonnxruntime.so` 与 `libvosk.so` 均为
16KB 段对齐；`libsherpa-onnx-jni.so` 为 4KB 段对齐——4KB 页设备（绝大多数，含目标机型）可正常
加载，16KB 页设备可能失败（自动回退，不影响 Vosk）。

**教训**：

- **不要凭印象判断第三方 .so 是静态链接**——`readelf -d | grep NEEDED` 是唯一可信依据
- **加载失败不要 `catch (Throwable ignore)`**：吞掉的正是定位所需的 dlopen 原因；
  v3.7 因此多花了一轮真机排查。

## v3.7.2：修复 KWS「cannot locate symbol OrtGetApiBase」

**现象**（真机 v3.7.1）：补上 onnxruntime 后仍然失败，但错误终于具体了：

```
dlopen failed: cannot locate symbol "OrtGetApiBase" referenced by
  .../lib/arm64-v8a/libsherpa-onnx-jni.so
```

**根因：GNU 符号版本不匹配。**

```
# sherpa 需要的（UND，带版本号）
readelf --dyn-syms libsherpa-onnx-jni.so
  UND OrtGetApiBase@VERS_1.17.1 (3)

# v3.7.1 提供的（ORT 1.20.0）
readelf --dyn-syms libonnxruntime.so
  OrtGetApiBase@@VERS_1.20.0
```

onnxruntime 从 1.17 起给自己的符号加了 version script（`VERS_1.x.y`）。Android linker
**按「符号名 + 版本」匹配**：请求 `@VERS_1.17.1`、只提供 `@@VERS_1.20.0` → 视为符号不存在。
所以「库放进去了、符号也确实存在」仍然加载失败。

**修复**：改用 `com.microsoft.onnxruntime:onnxruntime-android:1.17.1`
（与 sherpa 编译期链接的 ORT 完全同版本），arm64 `libonnxruntime.so` 16MB。

**验证（交付前闭合校验，避免再赌一次真机）**：

```bash
# 1. 提取 sherpa 的全部未定义符号（去掉系统库）
readelf --dyn-syms -W libsherpa-onnx-jni.so | awk '$7=="UND"{print $8}' | sort -u
# 2. 提取 ORT 的全部导出符号（把 @@ 规范成 @ 再比）
readelf --dyn-syms -W libonnxruntime.so | awk '$5=="GLOBAL"{print $8}' | sed 's/@@/@/g' | sort -u
# 3. 差集：除 AAsset_* / __android_log_print（系统 libandroid/liblog 提供）外应为空
comm -23 need.txt have.txt
# → 空，依赖完全闭合
```

再从最终 APK 里解出两个 .so 复核：ORT 提供 `OrtGetApiBase@@VERS_1.17.1`、sherpa 请求
`OrtGetApiBase@VERS_1.17.1`——版本一致。

**教训**：

- Android 的 .so 依赖有两层：**库存在**（NEEDED）与**符号版本对齐**（`@VERS_x`）。
  选第三方预编译库时，必须用 `readelf --dyn-syms` 看它请求的符号版本，再去选对应版本的依赖库；
- 只靠「同类库的新版本应该兼容」是赌博——ORT 的 ABI 兼容指 API 层，不保证 linker 的
  版本化符号匹配；
- 交付前做一次符号闭合校验，成本 30 秒，能替代一轮真机试错。

## v3.7.4：KWS 识别率调优（阈值 + 拼音变体）

**问题**（真机）：KWS 引擎启动正常，但试了多个口令只有「下个」能识别。

### 诊断方法（可复用）

构建环境没有真机，但可以用「合成语音 + 同模型桌面推理」做端到端定位：

```bash
# 1. edge-tts 生成真实中文语音（两个音色，覆盖男女声）
python3 -m edge_tts --text "下一个" --voice zh-CN-XiaoxiaoNeural --write-media next.mp3
ffmpeg -i next.mp3 -ar 16000 -ac 1 -c:a pcm_s16le next.wav
# 2. Python 版 sherpa-onnx 加载「同一套模型 + 同一份 keywords」，逐条检测
```

### 测试结果（16 段音频，每词男女声各一）

| 条件 | 下一个 | 下个 | 上一个 | 上一条 | 走你 | 回撤 |
|---|---|---|---|---|---|---|
| 阈值 0.25 / 无噪声 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| 阈值 0.25 / SNR 10dB | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| 阈值 0.15 / 无噪声 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |

单独测拼音变体（同一音频，只换标注）：

| 词 | 标注 | 命中 |
|---|---|---|
| 下一个 | `x ià y í g è`（标准变调） | 2/2 |
| 下一个 | `x ià y ī g è`（原调） | 2/2 |
| 下一个 | `x ià y i g e`（轻声） | **0/2** |
| 下个 | `x ià g è` | 2/2 |
| 上一条 | `sh àng y ī t iáo` | 2/2 |
| 上一条 | `sh àng y i t iáo`（轻声） | **0/2** |

**结论**：

1. 模型与词表没问题——干净与带噪条件下都 100% 命中；
2. **轻声标注 0 命中**：模型训练用的是带调音素，轻声序列反而完全对不上。所以不能把标注
   改成轻声，正确做法是**同时保留多套变体**（标准 + 轻声），真人口语命中哪套算哪套；
3. 用户真机「只有下个能识别」最可能是：阈值 0.25 对真人口音偏严（合成音清晰度远高于真人，
   噪声/距离/水声会把分数压到阈值以下），加上「一个」的口语读音与标注有差异。

### 修复

1. **阈值 0.25 → 0.15**（中文 KWS 常用区间 0.2~0.35 的下沿，提高召回）
2. **词表支持多变体**：`assets/kws/keywords_map.txt` 改为「词\t拼音1|拼音2」，
   `appendKwsLine` 按 `|` 拆开逐条加入。已覆盖：
   - 下一个 / 上一个 / 换一个：标准变调 `y í g è` + 口语轻声 `y i g e`
   - 上一条：`y ī t iáo`（原调）+ `y ì t iáo`（「一」在四声前变调）
3. **保留每帧取结果的解码写法**（官方示例同款）。中途试过「decode 到不 ready 再取一次」，
   实测「上一个/上一条」直接 0/2——已回退并在代码里加注释，防止以后再「优化」回去。

### 教训

- 合成语音**不能完全代表真人**：清晰度太高，噪声实验也偏乐观（SNR 10dB 仍 100%），
  所以「桌面测通」不等于「真机可用」，阈值这类参数要留足余量；
- 中文 KWS 的拼音标注必须带声调，轻声序列会让模型彻底失配；
- 官方示例的写法不要凭直觉「优化」——先跑数据再改。

## v3.8：前置摄像头手势控制（Camera2 + MediaPipe）

### 一、手势为什么只选 Victory / ILoveYou

MediaPipe 内置 7 个手势，其中两个是洗碗高频动作，**必须排除**：

| 内置手势 | 洗碗时的对应动作 | 结论 |
|---|---|---|
| Closed_Fist（握拳） | 握碗、攥抹布 | ✗ 误触发极高 |
| Open_Palm（张开手掌） | 冲碗、摊手、甩水 | ✗ 误触发极高 |
| **Victory（比耶）** | 几乎不会自然做出 | ✓ 下一个 |
| **ILoveYou（拇指+食指+小指）** | 几乎不会自然做出 | ✓ 上一个 |
| Thumb_Up / Thumb_Down / Pointing_Up | 少见 | 暂不启用 |

触发条件为「目标手势 + 连续保持 600ms + 距上次触发 1.5s」，三重门槛把瞬时误判压掉。

### 二、工程实现要点

```java
// 模型：7.7MB，assets 直读，无需解压
BaseOptions bo = BaseOptions.builder().setModelAssetPath("gesture/gesture_recognizer.task").build();
GestureRecognizerOptions opts = GestureRecognizer.GestureRecognizerOptions.builder()
        .setBaseOptions(bo).setRunningMode(RunningMode.IMAGE).setNumHands(1)
        .setMinHandDetectionConfidence(0.5f).build();
GestureRecognizer rec = GestureRecognizer.createFromOptions(ctx, opts);
GestureRecognizerResult r = rec.recognize(new BitmapImageBuilder(bitmap).build());
String g = r.gestures().get(0).get(0).categoryName();  // "Victory" / "ILoveYou" / "None"
```

- **Camera2 无预览采帧**：`ImageReader(YUV_420_888, 2)` 作为唯一 target，`TEMPLATE_PREVIEW`
  重复请求；`acquireLatestImage` + 时间戳节流到 5fps（静态手势不需要 30fps，功耗约 1/6）
- **尺寸自适应**：从 `SCALER_STREAM_CONFIGURATION_MAP` 选最接近 640×480 的 YUV 尺寸，
  避免个别机型不支持标准尺寸导致 configure 失败
- **YUV_420_888 → ARGB** 手写转换（兼容 rowStride/pixelStride），640×480 约 10~20ms
- **前台服务类型**：`foregroundServiceType="microphone|camera"` + `FOREGROUND_SERVICE_CAMERA`；
  Android 11+ 后台访问摄像头要求「服务启动时应用可见」——所以必须从 App 点开监听再切抖音

### 三、同时修复：TTS 播报的回声自触发

任何触发后都会 TTS 播报「下一个」，这句话会被自己的麦克风听到；若语音引擎仍在解码，
就会「播报 → 再触发」。v3.8 在 `executeCommand` 成功后设置 `voiceMuteUntil = now + 1200`，
`onPartialResult` / `onResult` / `handleKwsKeyword` 在窗口内直接返回。
代价：1.2 秒内连续说第二句口令会被忽略（与 800ms 冷却语义重叠，实测影响可忽略）。

### 四、依赖与体积

| 依赖 | 来源 | 体积 |
|---|---|---|
| tasks-vision / tasks-core | Google Maven `com.google.mediapipe:0.10.14` | classes 264KB + 1.3MB |
| libmediapipe_tasks_vision_jni.so | 同上 AAR，仅 arm64 | 14MB |
| guava 31.1-android / protobuf-javalite 3.21.12 | Maven Central | 2.7MB / 688KB |
| flogger + system-backend | Maven Central | 196KB |
| datatransport（api/runtime/backend-cct） | Google Maven | 156KB |
| gesture_recognizer.task | Google Storage（mediapipe-models） | 7.7MB |

APK：90MB → **112MB**。dex 类 4966 个（含 mediapipe 889 / guava 2148 / protobuf 360 /
datatransport 306 / flogger 168），已用 dexdump 逐一核对关键类在位。

### 五、风险与未验证项（重要）

- **无法在构建环境验证运行时**：Camera2 出帧、MediaPipe 初始化、YUV 转换只能靠真机。
  已加 12 秒看门狗：模型未就绪则自动写 `gesture_cam=false`、释放引擎、通知说明。
- **部分机型可能不出帧**：只有 ImageReader 没有预览 Surface 时，个别 ROM 的 3A 不启动或
  曝光极暗。若真机表现为「一直识别不到」，下一步需加一个 dummy SurfaceTexture 作为预览 target。
- **绿点**：Android 12+ 摄像头使用指示器无法隐藏，属系统行为。
- **耗电**：5fps 下约 1W（估算），连续 40 分钟约 5~8% 电量；发热明显低于 30fps 预览。

## v3.8.1：语音/手势独立开关

**问题**：v3.8 的手势开关只是「额外启动手势引擎」，语音引擎仍然无条件启动——用户想「只用手势、
不碰麦克风」做不到，且语音识别仍在后台耗电。

**修复（6 处）**：

1. 新增 prefs `voice_enabled`（默认 true）作为语音总开关
2. `onStartCommand` 按开关分支：`voiceEnabled` 为 false 时只启动手势
3. **麦克风链路三重防御**：`ensureRunning()` / `startVosk()` / `startKws()` 开头都检查
   `voiceEnabled`，关闭时直接返回——保证任何路径（熔断恢复、亮屏恢复、重建）都不会偷偷开麦
4. **前台服务类型按需计算**：`voiceEnabled ? MICROPHONE : 0` | `camMode ? CAMERA : 0`，
   仅手势时服务类型只有 `camera`（与 Android 的实际硬件使用一致，也避免多余的麦克风权限语义）
5. 麦克风权限请求加 `voiceEnabled` 条件——纯手势模式下不再向系统要录音权限
6. 语音与手势都关时拒绝启动（`START_NOT_STICKY` + 提示），不空跑前台服务

**一次性迁移**（避免用户还要手动关一次）：

```java
if (!sp.contains("voice_enabled") && sp.getBoolean("gesture_cam", false)) {
    sp.edit().putBoolean("voice_enabled", false).apply();  // v3.8 开过手势 → 意图是只用手势
}
```

**界面**：新增「控制方式（可只开一种）」区，语音开关与手势开关相邻；状态区在纯手势模式显示
「控制方式：仅手势（语音已关闭，未占用麦克风）」，麦克风权限显示「不需要（语音已关闭）」。

## 签名密钥持久化（v2.7 起）

`build.sh` 原来把 keystore 放在 `$OUT/keys/`，而 `$OUT` 每次构建整体 `rm -rf`，
导致**每个版本都是新签名密钥**——用户升级必须卸载重装（66MB 模型还要重解压）。
现改为 `$SRC/keys/voice.keystore`（构建目录外，不存在才生成；`-keypass` 无连字符），
v2.7 起同密钥升级可直接覆盖安装（v3.0~v3.4.1 与 v2.7 密钥一致：SHA-256 `b05e1714…`）。
无语音版 `build.sh` 同样修复（`mini.keystore`，SHA-256 `a279559a…`）。

---

## 历史章节（v2.5~v2.7，代码已删，仅供追溯）

### 动态滑动坐标：v2.5 → v2.6（已删除，坐标移入 ShortX 规则）

**Bug**：v2.5 及以前按规格书写法「2800×1272」计算坐标，x=1556。但一加 Ace 5 至尊版实际是
**1272×2800 竖屏**（`wm size` 输出 Physical size: 1272x2800），x=1556 **超出屏宽 1272**，
`input` 事件被系统静默丢弃——Shell 命令返回成功，抖音收不到任何手势。

**当时的修复**（`Swiper.java`）：`screenSize()` 运行时解析 `wm size`，min/max 交换保证竖屏口径；
坐标按经典命令 `input swipe 600 1800 600 1100 200`（1080×2400）的**比例**计算：
x=55.6%W、y1=75%H、y2=45.8%H → 本机竖屏 **707 2100→1282**；时长收紧为 **120ms**（≈2424dp/s）。

**现状**：这些参数现在就是 ShortX 规则里的 `su -c "input swipe 707 2100 707 1282 120"`，
App 不再参与坐标计算。

### 双通道交叉验证（v2.5~v2.6，已删除）

`Verify.java` 曾用两个独立通道验证翻页是否真的生效（`input` 退出码 0 ≠ 抖音翻页）：

| 通道 | 方法 | 判据 |
|---|---|---|
| A · 媒体元数据 | `dumpsys media_session` 抖音会话 metadata 前后文本对比 | 文本变化 |
| B · 截屏指纹 | `screencap` 底部条带（y78-96%）→ 64×16 灰度指纹 | 差异 > max(14, 噪声×4) |

v3.0 删除原因：执行移到 ShortX 后，验证窗口与 ShortX 自身行为（执行时闪界面）互相干扰，
误报「未生效」反而添乱；翻页结果以抖音实际画面为准。

### 执行方式双模式（v2.7，已删除）

v2.7 的 `Injector` 曾支持 `exec_mode` 首选项切「shortx / root」两种模式，
root 模式保留 v2.6 全部直注+验证+自动重试逻辑。v3.0 按要求删除 root 模式，
只留 ShortX DeepLink 一种。

### 其他技术要点（仍然有效）

1. **JNA 符号机制**：vosk-android AAR 的 libvosk.so 只导出 C API 短名（`vosk_model_new` 等），没有 `Java_*` JNI 符号。JNA `Native.register(LibVosk.class, "vosk")` 按 `method.getName()` 短名 dlsym，恰好一一对应——**这是设计如此，不是缺陷**。验证：`nm -D libvosk.so | grep vosk_model_new`。
2. **d8 必须把 jar 作为输入**：只编译自己的 class 而 jar 仅放 classpath，dex 里会丢 Vosk/JNA 类定义（运行时报 ClassNotFound）。d8 命令行要追加 `"$VOSK/aar/classes.jar" "$VOSK/jna/classes.jar"`。
3. **.so 打包**：`android:extractNativeLibs="false"` 要求 .so 存储不压缩（`zip -0`）且 4096 页对齐（`zipalign -p 4`）。JNA 的 libjnidispatch.so 与 libvosk.so 都要打进去（两个 ABI：arm64-v8a + armeabi-v7a）；Android 15 设备还需 16KB 页对齐（`zipalign -P 16`）。
4. **StorageService.Callback 的方法是 `onComplete` 不是 `onResult`**（官方示例与文档不一致，以 javap 为准）。
5. **口令即时触发**：`onPartialResult` 里检测口令词（Vosk partial 是词级输出，不会出半个词），说完即翻，比等 onResult 快 0.5~1 秒。
6. **中文模型不识别英文**：small-cn-0.22 词表无英文，"next" 会被识别成中文字。口令表以中文为准。
7. **Android 14+ 前台服务**：从后台（如 BootReceiver）启动 microphone 类型前台服务会被拒（ForegroundServiceStartNotAllowedException），已加 try-catch 降级；从可见 Activity 启动不受限。

## 构建模式：release 与 debug

`build.sh` 接受一个位置参数（默认 `release`），两条路只在四处分叉：

| 环节 | release | debug |
|---|---|---|
| manifest | 原样 | `sed` 生成临时副本，注入 `android:debuggable="true"`、label 改「手势切抖音(调试)」 |
| javac | 默认（`-g:lines`） | `-g`（含局部变量表） |
| d8 | `--release` | `--debug`（保留行号，可 attach 调试器） |
| 签名 | `keys/release.keystore` / 别名 `app` | `keys/debug.keystore` / 别名 `androiddebugkey` |

几个实现上的注意点：

1. **不污染工作区**：debug 的 manifest 写到 `build/debug/AndroidManifest.xml` 再传给
   `aapt2 link --manifest`，源码里的 `app/AndroidManifest.xml` 一个字节都不动——
   否则每次 `bash build.sh debug` 都会让 `git status` 变脏。
2. **产物目录分离**：debug 输出到 `build/debug/`，与 `build/` 下的正式包互不影响
   （脚本开头的 `rm -rf "$OUT"` 只清自己那层）。
3. **`set -e` 下别用 `[ cond ] && VAR=x`**：条件为假时整个 `&&` 列表返回 1，
   脚本会直接退出。必须写成 `if [ ... ]; then VAR=x; fi`——`JAVAC_DEBUG` 最初就是这么挂的。
4. **debug 签名 ≠ 正式签名**：两者证书不同，`adb install` 会报
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先卸载正式版；要覆盖安装就
   `DEBUG_SIGN_WITH_RELEASE=1 bash build.sh debug`（此时仍是 debuggable 包，只是借用正式密钥）。
5. **logcat 分流**：`EventLog.setLogcat()` 只在 `ApplicationInfo.FLAG_DEBUGGABLE` 为真时打开，
   release 包不会往 logcat 里写识别分数（既省 I/O 也不在正式包里刷屏）。
6. **CI 校验 debuggable 真的生效**：`aapt2 dump badging` 里出现 `application-debuggable`
   这一行才算数——只看构建成功不够，`sed` 没匹配到 manifest 结构时会静默失败，
   所以脚本里额外 `grep -q 'android:debuggable="true"'` 做断言。

## 版本对应

- vosk-android 0.3.75（Java 类与 arm64 .so 已做 26 符号全量比对，零缺失）
- 若换 vosk 版本，务必重跑符号比对：提取 `javap org.vosk.LibVosk` 的 native 方法名，与 `nm -D libvosk.so` 对照
