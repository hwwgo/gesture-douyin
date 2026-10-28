#!/usr/bin/env bash
#
# 手势切抖音 · 构建脚本（aapt2 + javac + d8 + apksigner，不依赖 Gradle）
#
# 前置：
#   1. 安装 JDK 11+（javac / keytool）与 Android SDK（build-tools 34+、platform android-35）
#   2. 设置 ANDROID_SDK_ROOT 或 ANDROID_HOME 指向 SDK（也可放在 ~/Android/Sdk）
#   3. 首次构建前跑一次：bash scripts/fetch-deps.sh
#
# 用法：
#   bash build.sh              # 正式版（等同上 release）
#   bash build.sh debug        # 调试版：debuggable=true + 保留调试信息 + debug 密钥签名
#
# 产物：
#   release → build/gesture-douyin-vX.Y.apk
#   debug   → build/debug/gesture-douyin-vX.Y-debug.apk
#
# 调试版额外说明：
#   * manifest 注入 android:debuggable="true"，可用 adb 附加调试器 / dump 堆
#   * 事件日志同时打到 logcat（标签 GestureDouyin）：
#       adb logcat -s GestureDouyin
#   * 默认用 keys/debug.keystore 签名（与正式版不同 → 安装前需卸载正式版）；
#     想直接覆盖安装正式版：DEBUG_SIGN_WITH_RELEASE=1 bash build.sh debug
#   * 用已有密钥：KS_PATH=my.keystore KS_ALIAS=xxx KS_STORE_PASS=xxx bash build.sh debug
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
DEPS="$ROOT/deps"

# ---------------- 构建模式 ----------------
MODE="${1:-release}"
case "$MODE" in
    release|rel|r) MODE=release ;;
    debug|dbg|d|--debug|-d) MODE=debug ;;
    -h|--help|help)
        sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'
        exit 0 ;;
    *)
        echo "未知参数：$MODE（可用：release | debug）" >&2
        exit 2 ;;
esac

# 中间产物各自独立成目录，避免 release 的清理动作把 debug 产物一起删掉
OUT="$ROOT/build/$MODE"
if [ "$MODE" = "debug" ]; then
    APK_SUFFIX="-debug"
    APK_DIR="$OUT"
else
    APK_SUFFIX=""
    APK_DIR="$ROOT/build"
fi

# ---------------- SDK 定位 ----------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK" ]; then
    echo "找不到 Android SDK：$SDK" >&2
    echo "请设置 ANDROID_SDK_ROOT（例如 export ANDROID_SDK_ROOT=~/Android/Sdk）" >&2
    exit 1
fi
# build-tools / platform：默认取版本号最大的；可用 BT_VERSION / PLATFORM_VERSION 固定
# （CI 里固定成 35.0.0 / android-35，避免 runner 预装更高版本时行为漂移）
BT=""
if [ -n "${BT_VERSION:-}" ]; then
    if [ -d "$SDK/build-tools/$BT_VERSION" ]; then
        BT="$SDK/build-tools/$BT_VERSION"
    else
        echo "提示：未安装 build-tools $BT_VERSION，退回自动探测" >&2
    fi
fi
[ -n "$BT" ] || BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"

PLATFORM=""
if [ -n "${PLATFORM_VERSION:-}" ]; then
    if [ -d "$SDK/platforms/$PLATFORM_VERSION" ]; then
        PLATFORM="$SDK/platforms/$PLATFORM_VERSION"
    else
        echo "提示：未安装 platform $PLATFORM_VERSION，退回自动探测" >&2
    fi
fi
[ -n "$PLATFORM" ] || PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1 || true)"

AJAR="$PLATFORM/android.jar"
if [ -z "$BT" ] || [ -z "$PLATFORM" ] || [ ! -f "$AJAR" ]; then
    echo "SDK 不完整：需要 build-tools 与 platforms/android-XX（当前 $SDK）" >&2
    exit 1
fi
echo "SDK：$(basename "$BT") / $(basename "$PLATFORM")"

# ---------------- 依赖检查 ----------------
MP="$DEPS/mediapipe"
MP_JARS="$MP/tasks-vision/classes.jar $MP/tasks-core/classes.jar \
$MP/libs/transport-api/classes.jar $MP/libs/transport-runtime/classes.jar \
$MP/libs/transport-backend-cct/classes.jar $MP/libs/encjson/classes.jar \
$MP/libs/guava-31.1-android.jar $MP/libs/protobuf-javalite-3.21.12.jar \
$MP/libs/flogger-0.7.4.jar $MP/libs/flogger-system-backend-0.7.4.jar \
$MP/libs/auto-value-annotations-1.10.1.jar $MP/libs/javax.inject-1.jar \
$MP/libs/firebase-encoders-17.0.0.jar $MP/libs/firebase-encoders-proto-16.0.0.jar \
$MP/libs/annotation-1.1.0.jar $MP/libs/failureaccess-1.0.1.jar"
for f in $MP_JARS; do
    if [ ! -f "$f" ]; then
        echo "缺少依赖：$f" >&2
        echo "请先运行：bash scripts/fetch-deps.sh" >&2
        exit 1
    fi
done
if [ ! -f "$APP/assets/gesture/gesture_recognizer.task" ] \
   || [ ! -f "$APP/assets/face/face_landmarker.task" ]; then
    echo "缺少模型文件，请先运行：bash scripts/fetch-deps.sh" >&2
    exit 1
fi
MP_CP="$(echo $MP_JARS | tr ' ' ':')"

VERSION="$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
APK_NAME="gesture-douyin-v${VERSION}${APK_SUFFIX}.apk"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# ---------------- 调试版：改写 manifest ----------------
MANIFEST="$APP/AndroidManifest.xml"
if [ "$MODE" = "debug" ]; then
    MANIFEST="$OUT/AndroidManifest.xml"
    # 只在内存里的临时副本上改，不污染工作区（避免 git 一直显示 manifest 有改动）
    sed -e 's|<application|<application android:debuggable="true"|' \
        -e 's|android:label="@string/app_name"|android:label="手势切抖音(调试)"|' \
        "$APP/AndroidManifest.xml" > "$MANIFEST"
    if ! grep -q 'android:debuggable="true"' "$MANIFEST"; then
        echo "注入 debuggable 失败：manifest 结构与预期不符" >&2
        exit 1
    fi
fi

echo "构建模式：$MODE${APK_SUFFIX:+（调试版）}"

echo "== [1/7] aapt2 compile（资源）=="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "== [2/7] aapt2 link（含 assets：两个 .task 模型）=="
"$BT/aapt2" link -o "$OUT/base.apk" \
    -I "$AJAR" \
    --manifest "$MANIFEST" \
    -A "$APP/assets" \
    --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    "$OUT/res.zip"

echo "== [3/7] javac =="
# 调试版用 -g 保留全部调试信息（行号 / 局部变量），release 保持精简
JAVAC_DEBUG=""
if [ "$MODE" = "debug" ]; then JAVAC_DEBUG="-g"; fi
javac -nowarn -encoding UTF-8 $JAVAC_DEBUG \
    -cp "$AJAR:$MP_CP" \
    -d "$OUT/classes" \
    $(find "$APP/src" -name '*.java') "$OUT/gen/com/handsfree/douyin/R.java"

echo "== [4/7] d8（dex）=="
# --release：优化并去调试信息；--debug（默认）：保留行号，可 attach 调试器
if [ "$MODE" = "debug" ]; then
    D8_FLAGS="--debug"
else
    D8_FLAGS="--release"
fi
"$BT/d8" $D8_FLAGS --min-api 26 --lib "$AJAR" \
    --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class') \
    $MP_JARS

echo "== [5/7] 打包 dex 与原生库 =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
# 原生库必须以 lib/<abi>/xxx.so 作为条目名，且**不压缩**存储
# （Manifest 里 extractNativeLibs=false，系统直接从 APK 映射）
mkdir -p "$OUT/lib/arm64-v8a"
cp "$MP/jni/arm64-v8a/libmediapipe_tasks_vision_jni.so" "$OUT/lib/arm64-v8a/"
(cd "$OUT" && zip -q -0 -X "$OUT/unsigned.apk" lib/arm64-v8a/libmediapipe_tasks_vision_jni.so)

echo "== [6/7] zipalign（16KB 页对齐，Android 15 起要求）=="
if "$BT/zipalign" -P 16 -f -v 4 "$OUT/unsigned.apk" "$OUT/aligned.apk" > "$OUT/align.log" 2>&1; then
    grep -E "lib/.*\.so" "$OUT/align.log" || true
    echo "  16KB 页对齐 OK"
else
    echo "  （当前 build-tools 不支持 -P，退化为 4 字节对齐）"
    "$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
fi

echo "== [7/7] 签名 =="
# 密钥目录已在 .gitignore 中，不要提交
mkdir -p "$ROOT/keys"
if [ "$MODE" = "debug" ] && [ "${DEBUG_SIGN_WITH_RELEASE:-0}" != "1" ]; then
    DEF_KS="$ROOT/keys/debug.keystore"
    DEF_ALIAS="androiddebugkey"
    DEF_DNAME="CN=Android Debug, O=Android, C=US"
else
    DEF_KS="$ROOT/keys/release.keystore"
    DEF_ALIAS="app"
    DEF_DNAME="CN=gesture-douyin, O=local, C=CN"
fi
# 想用自己的密钥：KS_PATH=... KS_ALIAS=... KS_STORE_PASS=... KS_KEY_PASS=... bash build.sh
KS="${KS_PATH:-$DEF_KS}"
KS_ALIAS="${KS_ALIAS:-$DEF_ALIAS}"
KS_STORE_PASS="${KS_STORE_PASS:-android}"
KS_KEY_PASS="${KS_KEY_PASS:-android}"
if [ ! -f "$KS" ]; then
    echo "  生成签名密钥：$(basename "$KS")（仅本地调试用；发布请换成自己的密钥并妥善保管）"
    keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KS_STORE_PASS" -keypass "$KS_KEY_PASS" \
        -dname "$DEF_DNAME" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_STORE_PASS" --key-pass "pass:$KS_KEY_PASS" \
    --out "$APK_DIR/$APK_NAME" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$APK_DIR/$APK_NAME" | head -2
ls -lh "$APK_DIR/$APK_NAME"
echo
echo "构建完成：$APK_DIR/$APK_NAME"
if [ "$MODE" = "debug" ]; then
    echo
    echo "调试版提示："
    echo "  · 已开启 debuggable（可 adb 附加调试器）"
    echo "  · 实时日志：adb logcat -s GestureDouyin"
    if [ -z "${KS_PATH:-}" ] && [ "${DEBUG_SIGN_WITH_RELEASE:-0}" != "1" ]; then
        echo "  · 签名与正式版不同，安装前先卸载正式版；想覆盖安装请用："
        echo "      DEBUG_SIGN_WITH_RELEASE=1 bash build.sh debug"
    fi
fi
