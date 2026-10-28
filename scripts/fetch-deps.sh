#!/usr/bin/env bash
#
# 下载构建所需的第三方二进制依赖与模型文件。
#
# 仓库里**不提交**这些大文件（约 20MB 的 AAR/JAR + 12MB 的模型），
# 首次构建前跑一次本脚本即可；之后 build.sh 直接用 deps/ 里的内容。
#
# 用法：  bash scripts/fetch-deps.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="$ROOT/deps"
MP="$DEPS/mediapipe"
LIBS="$MP/libs"
MODELS="$ROOT/app/assets"

GOOGLE_MVN="https://dl.google.com/dl/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"

mkdir -p "$MP/tasks-vision" "$MP/tasks-core" "$LIBS" "$MP/jni/arm64-v8a" \
         "$MODELS/gesture" "$MODELS/face"

fetch() {  # fetch <url> <outfile>
    local url="$1" out="$2"
    if [ -s "$out" ]; then
        echo "  skip  $(basename "$out")"
        return 0
    fi
    echo "  get   $(basename "$out")"
    rm -f "$out.tmp"
    curl -fL --retry 3 --retry-delay 2 --max-time 600 -o "$out.tmp" "$url"
    mv "$out.tmp" "$out"
}

# .aar 其实是个 zip，里面才是 classes.jar
unpack_aar() {  # <aar> <target-dir>
    local aar="$1" dir="$2"
    rm -rf "$dir"
    mkdir -p "$dir"
    unzip -o -q "$aar" classes.jar -d "$dir"
    if [ ! -s "$dir/classes.jar" ]; then
        echo "!! $aar 中没有 classes.jar（下载可能不完整）" >&2
        exit 1
    fi
}

echo "[1/4] MediaPipe（任务库 + JNI）"
fetch "$GOOGLE_MVN/com/google/mediapipe/tasks-vision/0.10.14/tasks-vision-0.10.14.aar" \
      "$MP/tasks-vision.aar"
fetch "$GOOGLE_MVN/com/google/mediapipe/tasks-core/0.10.14/tasks-core-0.10.14.aar" \
      "$MP/tasks-core.aar"
unpack_aar "$MP/tasks-vision.aar" "$MP/tasks-vision"
unpack_aar "$MP/tasks-core.aar" "$MP/tasks-core"
unzip -o -q "$MP/tasks-vision.aar" 'jni/arm64-v8a/*' -d "$MP"
echo "  jni: $(ls -lh "$MP/jni/arm64-v8a/" | tail -n +2 | awk '{print $5, $9}')"

echo "[2/4] MediaPipe 的传递依赖（缺一个就会 NoClassDefFoundError）"
# datatransport：MediaPipe 用它上报匿名统计，类加载期就会引用，不能省
fetch "$GOOGLE_MVN/com/google/android/datatransport/transport-api/3.0.0/transport-api-3.0.0.aar" \
      "$DEPS/transport-api.aar"
fetch "$GOOGLE_MVN/com/google/android/datatransport/transport-runtime/3.1.1/transport-runtime-3.1.1.aar" \
      "$DEPS/transport-runtime.aar"
fetch "$GOOGLE_MVN/com/google/android/datatransport/transport-backend-cct/3.1.1/transport-backend-cct-3.1.1.aar" \
      "$DEPS/transport-backend-cct.aar"
unpack_aar "$DEPS/transport-api.aar"          "$LIBS/transport-api"
unpack_aar "$DEPS/transport-runtime.aar"      "$LIBS/transport-runtime"
unpack_aar "$DEPS/transport-backend-cct.aar"  "$LIBS/transport-backend-cct"

# Dagger 的 Provider 接口（Factory 的父接口，缺它报的错会指向 Factory，很难查）
fetch "$CENTRAL/javax/inject/javax.inject/1/javax.inject-1.jar" "$LIBS/javax.inject-1.jar"

fetch "$CENTRAL/com/google/guava/guava/31.1-android/guava-31.1-android.jar" \
      "$LIBS/guava-31.1-android.jar"
fetch "$CENTRAL/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar" \
      "$LIBS/failureaccess-1.0.1.jar"
fetch "$CENTRAL/com/google/protobuf/protobuf-javalite/3.21.12/protobuf-javalite-3.21.12.jar" \
      "$LIBS/protobuf-javalite-3.21.12.jar"
fetch "$CENTRAL/com/google/flogger/flogger/0.7.4/flogger-0.7.4.jar" \
      "$LIBS/flogger-0.7.4.jar"
fetch "$CENTRAL/com/google/flogger/flogger-system-backend/0.7.4/flogger-system-backend-0.7.4.jar" \
      "$LIBS/flogger-system-backend-0.7.4.jar"
fetch "$CENTRAL/com/google/auto/value/auto-value-annotations/1.10.1/auto-value-annotations-1.10.1.jar" \
      "$LIBS/auto-value-annotations-1.10.1.jar"
fetch "$GOOGLE_MVN/com/google/firebase/firebase-encoders/17.0.0/firebase-encoders-17.0.0.jar" \
      "$LIBS/firebase-encoders-17.0.0.jar"
fetch "$GOOGLE_MVN/com/google/firebase/firebase-encoders-proto/16.0.0/firebase-encoders-proto-16.0.0.jar" \
      "$LIBS/firebase-encoders-proto-16.0.0.jar"
fetch "$GOOGLE_MVN/com/google/firebase/firebase-encoders-json/18.0.0/firebase-encoders-json-18.0.0.aar" \
      "$DEPS/firebase-encoders-json.aar"
unpack_aar "$DEPS/firebase-encoders-json.aar" "$LIBS/encjson"
fetch "$GOOGLE_MVN/androidx/annotation/annotation/1.1.0/annotation-1.1.0.jar" \
      "$LIBS/annotation-1.1.0.jar"

echo "[3/4] MediaPipe 模型（约 12MB）"
fetch "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task" \
      "$MODELS/gesture/gesture_recognizer.task"
fetch "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task" \
      "$MODELS/face/face_landmarker.task"

echo "[4/4] 校验模型完整性（.task 是 zip，下载被截断会导致运行时 Unable to open zip archive）"
check_model() {  # <file> <expected-bytes>
    local f="$1" want="$2" got
    got=$(wc -c < "$f" | tr -d ' ')
    if [ "$got" != "$want" ]; then
        echo "!! $(basename "$f") 大小异常：$got（期望 $want）" >&2
        echo "   删除该文件后重跑本脚本" >&2
        exit 1
    fi
    if ! unzip -t "$f" > /dev/null 2>&1; then
        echo "!! $(basename "$f") 不是完整的 zip 包" >&2
        exit 1
    fi
    echo "  ok    $(basename "$f")  $got bytes"
}
check_model "$MODELS/gesture/gesture_recognizer.task" 8373440
check_model "$MODELS/face/face_landmarker.task" 3758596

echo
echo "依赖就绪。接下来运行：  bash build.sh"
