# 手势切抖音（gesture-douyin）

用**前置摄像头**识别手势或面部表情，触发抖音翻页（下一个 / 上一个）。全部在手机本地完成，
不联网、不用麦克风、不用 root。

滑动的真正执行交给 **ShortX**（通过 `shortx://run?id=xxx` 深链拉起它的规则），
本 App 只负责「识别动作」这一件事。

> 项目最初是语音口令版，v4.0 起按需求删掉了全部语音代码，只保留视觉识别。

## 功能

| 能力 | 说明 |
|---|---|
| 手势识别 | **数手指**：伸 1/2/3/4/5 根，任意一种可映射为「下一个」或「上一个」；握拳不触发 |
| 表情识别 | **8 种动作**任选：张嘴 / 露牙笑 / 龇牙 / 撅嘴 / 鼓腮 / 抬眉 / 眯眼 / 睁大眼 |
| 阈值可调 | 每个方向一个阈值输入框，切换动作自动套用该动作的推荐值 |
| 画面放大 | 1.0x~4.0x 数字变焦，手机离得远时把画面中心放大后再识别 |
| 高精度模式 | 采集分辨率 640×480 → 960×720（可选，更准更耗电） |
| 摄像头预览 | 在 App 内直接看识别引擎处理的那一帧，方便调角度/光线 |
| 防误触 | 动作需保持 0.4~0.5 秒；可设触发冷却；握拳不触发 |
| 隐私友好 | 息屏即停止识别（等同手动停止）；不写任何图像文件 |

## 技术栈

- **Camera2** 采集 YUV_420_888，Java 侧转 ARGB（复用缓冲，零每帧分配）
- **MediaPipe Tasks**（`com.google.mediapipe:tasks-vision:0.10.14`）
  - `GestureRecognizer` 输出 21 个手部关键点 → 用几何判据数手指
    （四指用 MCP-PIP-TIP 夹角 ≥155°，拇指用距离比 + 夹角双条件）
  - `FaceLandmarker` + `blendshapes`（52 个 ARKit 风格分量）→ 组合出 8 种表情动作
- 无 Gradle：`aapt2` + `javac` + `d8` + `zipalign` + `apksigner` 手工打包

## 环境要求

- JDK 11+（需要 `javac`、`keytool`）
- Android SDK：**build-tools 34+**、**platform android-35**（`android.jar`）
- `curl`、`unzip`
- 运行时：Android 8.0+，**仅 arm64-v8a**（只打包了 64 位原生库）

## 构建

```bash
# 1) 设置 SDK 路径（也可放在 ~/Android/Sdk）
export ANDROID_SDK_ROOT=~/Android/Sdk

# 2) 首次：下载 MediaPipe 依赖与模型（约 20MB + 12MB，不提交进仓库）
bash scripts/fetch-deps.sh

# 3) 构建
bash build.sh            # 正式版：build/gesture-douyin-vX.Y.apk
bash build.sh debug      # 调试版：build/debug/gesture-douyin-vX.Y-debug.apk
```

`fetch-deps.sh` 会校验模型文件的字节数与 zip 完整性——`.task` 本质是 zip，
下载被截断会在运行时报 `Unable to open zip archive`，这是踩过的坑。

### 调试版（debug APK）

`bash build.sh debug` 与正式版走同一套流程，只有四处不同：

| 差异 | 正式版 | 调试版 |
|---|---|---|
| `android:debuggable` | 无 | `true`（可 adb 附加调试器、看堆栈） |
| javac / d8 | `d8 --release`（优化、去调试信息） | `javac -g` + `d8 --debug`（保留行号） |
| 事件日志 | 只写 App 内环形缓冲 | **同时输出 logcat**：`adb logcat -s GestureDouyin` |
| 签名 | `keys/release.keystore` | `keys/debug.keystore`（首次自动生成） |

调试版包名不变、应用名显示为「手势切抖音(调试)」，方便和正式版区分。
因为用的是 debug 密钥，**安装前要先卸载正式版**；想直接覆盖安装：

```bash
DEBUG_SIGN_WITH_RELEASE=1 bash build.sh debug
```

已经有自己的密钥（比如想沿用上一版正式包、直接覆盖升级）就指定它：

```bash
KS_PATH=~/my.keystore KS_ALIAS=voice KS_STORE_PASS=123456 KS_KEY_PASS=123456 \
  bash build.sh debug
```

`build/debug/` 与 `build/` 互不干扰，两种产物可以并存。

## 使用

安装后需要：

1. 授予**摄像头权限**
2. 安装 **ShortX** 并在 LSPosed 中启用，导入两个「执行滑动」动作
3. 把两个 `shortx://run?id=DA-xxxx` 链接粘到 App 首页的输入框
4. 授予本 App「后台弹出界面」或「悬浮窗」权限（后台拉起 ShortX 需要）
5. 点「开启识别」

详细步骤见 [`docs/USAGE.md`](docs/USAGE.md)，ShortX 侧规则见 [`docs/ShortX-SETUP.md`](docs/ShortX-SETUP.md)。

## 目录结构

```
.
├── build.sh                    # 构建脚本（自动探测 SDK / build-tools）
├── scripts/fetch-deps.sh       # 下载 MediaPipe 依赖与模型
├── .github/workflows/
│   └── build-debug.yml         # CI：自动构建 debug APK 并上传 artifact
├── app/
│   ├── AndroidManifest.xml
│   ├── src/com/handsfree/douyin/
│   │   ├── MainActivity.java        # 全部界面（纯代码构建，无 XML 布局）
│   │   ├── VisionService.java       # 前台服务：生命周期 / 冷却 / 触发
│   │   ├── CameraVisionEngine.java  # 相机 + MediaPipe（手势数指 / 表情）
│   │   ├── Trigger.java             # 触发编排（DeepLink 通道）
│   │   ├── ShortX.java              # shortx:// 拉起（含后台悬浮窗辅助）
│   │   ├── OverlayLaunch.java       # 1x1 不可见 overlay，绕过后台启动限制
│   │   ├── Feedback.java            # 震动反馈
│   │   ├── EventLog.java            # 环形日志（界面展示）
│   │   └── HandsFreeApp.java        # 崩溃日志落盘
│   ├── res/                    # 图标与 app_name
│   └── assets/{gesture,face}/  # 模型（由 fetch-deps.sh 下载，不入库）
├── deps/                       # 第三方二进制（不入库）
└── docs/
    ├── USAGE.md                # 用户使用指南
    ├── BUILD-NOTES.md          # 逐版本技术记录与踩坑（约 40 个版本）
    └── ShortX-SETUP.md         # ShortX 规则配置
```

## 关键实现要点

- **为什么要数手指而不是用内置手势分类**：MediaPipe 的 7 类手势分类对角度、距离、光线很挑，
  实测「比耶」很难稳定认出；而关键点几何判定稳定得多，同一个手势还能表达 5 种含义。
- **拇指判定要双条件**：只比「指尖/指节到食指根的距离比」时，握拳（拇指搭在手指外）会被
  多数成 1 根手指；叠加 IP 关节夹角 ≥140° 才能正确排除。
- **表情阈值为什么可调**：blendshape 分数的个体差异很大，同一动作有人 0.7 有人 0.35，
  固定阈值必然有人不好用。日志会输出「露牙笑 0.38/0.45」这样的实时分数，照着调即可。
- **YUV 转换复用缓冲**：每帧 30 万像素，原先每帧新建 `int[307200]` + Bitmap，
  5fps 下每秒约 6MB 垃圾，GC 抖动会让识别卡顿；改为复用后稳定。
- 更多踩坑（依赖链、符号版本、assets 压缩、16KB 页对齐等）见 `docs/BUILD-NOTES.md`。

## 已知限制

- 只支持 64 位设备（原生库仅 arm64-v8a）
- 依赖 ShortX 执行滑动，未内置 `input swipe`（需要 root 或无障碍）
- 息屏会停止识别，亮屏后需回 App 重新开启
- 面部表情在逆光、戴口罩、光线不足时检测率下降

## 隐私

- 所有识别在本地完成，**不联网、不上传任何图像**
- 摄像头画面只在内存中处理，不写入文件
- 不需要麦克风、不需要 root、不读取使用记录（使用情况权限已移除）

## 免责声明

仅供个人学习与自用。使用自动化工具操作第三方应用可能违反其服务条款，请自行评估风险；
因使用本软件产生的任何后果由使用者承担。

## 上传到 GitHub

这个目录已经是一个可直接入库的工程（`.gitignore` 已排除构建产物、签名密钥、依赖与模型）：

```bash
git init
git add .
git commit -m "手势切抖音 v5.3：数手指手势 + 8 种表情 + 画面放大"
git branch -M main
git remote add origin git@github.com:<你的账号>/gesture-douyin.git
git push -u origin main
```

不想用命令行也可以用 GitHub 网页的 *Add file → Upload files* 拖整个文件夹，
或用 GitHub Desktop 直接 *Add local repository*。

> 网络原因拉不到 Google/Maven 的话，可以在本地先跑一次 `scripts/fetch-deps.sh`，
> 然后临时删掉 `.gitignore` 里 `deps/` 和 `app/assets/**/*.task` 两行一起提交
> （仓库会多约 35MB）。

## 自动构建 debug APK（GitHub Actions）

仓库自带 [`.github/workflows/build-debug.yml`](.github/workflows/build-debug.yml)，
推上去就能在云端出包，本地不用装 SDK、不用 JDK：

| 触发 | 结果 |
|---|---|
| push 任意分支 / PR | 构建 debug APK，上传为 artifact（Actions 页面底部「Artifacts」下载，保留 30 天） |
| 推 `v*` tag | 额外创建一个 Release，把 `gesture-douyin-vX.Y-debug.apk` 挂上去 |
| Actions 页手动 *Run workflow* | 同 push |

流程：`checkout` → JDK 17 → Android SDK（含 build-tools 35 / platform 35）→
缓存 `deps/`、两个模型与固定 debug 密钥（首次约 35MB，之后命中缓存，连续几次构建的包可互相覆盖升级）→
`scripts/fetch-deps.sh` → `build.sh debug` → 用 `aapt2 dump badging` 断言
`application-debuggable` 出现 → 上传产物。

> 首次跑会下载依赖，约 3–5 分钟；之后有缓存大约 1 分钟。
> 想让 release 版也自动出包，需要把签名密钥放进仓库 Secrets——不建议，
> 正式包还是本地签比较稳妥。

## 许可

未附带开源许可证。如需转载或分发，请先联系作者。
