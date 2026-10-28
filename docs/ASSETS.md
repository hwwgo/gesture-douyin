# assets

这里的两个模型文件由 `scripts/fetch-deps.sh` 下载，**不提交进仓库**：

| 文件 | 大小 | 来源 |
|---|---|---|
| `gesture/gesture_recognizer.task` | 8,373,440 B | [MediaPipe gesture recognizer (float16)](https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task) |
| `face/face_landmarker.task` | 3,758,596 B | [MediaPipe face landmarker (float16)](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task) |

两者都是 zip 容器（内含 tflite 模型）。下载不完整会在运行时报
`MediaPipeException: Unable to open zip archive`，fetch 脚本会校验字节数与 zip 完整性。

注意：应用用的是 `setModelAssetBuffer(ByteBuffer)` 加载，所以这两个文件在 APK 里
**可以被压缩**（不需要 `-0 task`），能省约 2.5MB。
