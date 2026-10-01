# Sleep Desk / 睡眠桌面

Minimal Android sleep tracker — one big Start/Stop button, bedside accelerometer, local only.

极简 Android 睡眠追踪：一个大按钮开始/结束，床边加速度计采样，数据仅存本地。

**Package:** `com.i3u8.sleepdesk` · **v0.1.0** · MIT

## How to use / 使用方法

1. Put the phone on the mattress or bed edge (screen can be off).  
   把手机放在床垫或床边（可熄屏）。
2. Tap **开始睡** — a foreground notification keeps sampling motion.  
   点 **开始睡** — 前台通知持续采样动作。
3. In the morning tap **结束** — see duration + high-motion bucket count (rough night-wake proxy).  
   早上点 **结束** — 查看时长与高动作分钟数（粗略夜间醒来代理）。

No mic, no BLE, no accounts. Motion energy only — no sleep-stage ML.

无麦克风、无蓝牙、无账号。仅动作能量阈值，无睡眠分期模型。

## Build / 构建

Requirements: JDK 17+ (21 OK), Android SDK platform 34 + build-tools 34.

```bash
# set SDK path
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Permissions / 权限

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — bedside sampling
- `POST_NOTIFICATIONS` (API 33+) — persistent tracking notification
- Accelerometer via `SensorManager` only (no `ACTIVITY_RECOGNITION`)

## Project layout / 结构

- `MainActivity` — single screen, Start/Stop toggle
- `SleepTrackingService` — FGS + accelerometer → 60s motion buckets
- `SessionStore` — JSON file under app `filesDir`

## License

MIT — see [LICENSE](LICENSE).
