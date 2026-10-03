# Sleep Desk / 睡眠桌面

[![CI](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml/badge.svg)](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/redreamality/sleep-desk?include_prereleases&sort=semver)](https://github.com/redreamality/sleep-desk/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**English** · [中文](#sleep-desk--睡眠桌面-中文)

Minimal Android sleep tracker driven by the **microphone** (phone can stay on the nightstand—no mattress placement). One-tap start/stop, segment timeline, short key-event clips, local-only storage.

**Development 0.4.0:** independent sound detection, real pre/post-roll, on-device YAMNet suggestions,
and explicit uncertain/failed analysis with reviewable recordings. See [Audio v2](docs/audio-v2.md).
This is not a clinical detector or a claim of validated classification accuracy.

| | |
|---|---|
| **Package** | `com.i3u8.sleepdesk` |
| **Latest Build** | [Latest successful main release](https://github.com/redreamality/sleep-desk/releases/latest) |
| **License** | [MIT](LICENSE) |
| **Docs** | [`docs/`](docs/) |

> **Disclaimer / 免责声明:** Experimental, non-medical. Sleep Desk estimates acoustic activity and segments overnight sound events. It is **not** a hypnogram, clinical sleep study, or medical device. Do not use for diagnosis or treatment decisions.

---

## What it is

Sleep Desk monitors ambient night sounds through a foreground microphone service. It classifies short acoustic events (snore, cough, speech, wake-related sounds, etc.), aggregates them into **density-aware segments**, and keeps only **representative AAC clips**—never a full-night raw recording.

Phone on the nightstand is enough. Secondary signals (screen / charge / light) are optional helpers, not the primary path.

## Features

- **Mic-first** — `FOREGROUND_SERVICE_MICROPHONE`; far-field / high-sensitivity defaults for bed-side placement
- **Segments** — night split into bout-style bands (merge → split long runs → MIXED); colored timeline
- **Clips** — 0–2 representative AAC paths per segment (up to 3 for long snore ≥10 min); reference only, never auto-delete clip files
- **Export** — export one night or all nights as zip (sessions JSON + clips) via the system share sheet
- **Delete** — delete one night or clear all, with double confirm; cascades `audio_clips`
- **Local-only** — data stays on device; no account, no cloud upload of audio

## Install (from Releases)

1. Open **[Releases](https://github.com/redreamality/sleep-desk/releases)** and download the latest debug APK (e.g. `sleep-desk-v0.3.2-debug.apk`).
2. On Android, allow install from the browser / file manager.
3. Grant **microphone** (and notifications on Android 13+) when you tap **开始睡**.

Side-load only; not on Play Store yet.


## How to get CI APK

- **Actions (every push/PR to `main`):** open [Actions](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml) → latest successful **Android CI** run → download artifact `sleep-desk-debug` (and `sleep-desk-release` when signing Secrets are set).
- **Automatic Releases (pushes to `main` only):** after tests and the build succeed, the same APK is published as `main-<run-number>-<short-sha>`. PRs and failed builds never publish. Rerunning a CI run updates the same release; older commits do not replace Latest. App version numbers are not automatically incremented.
- **Releases (tagged builds):** open [Releases](https://github.com/redreamality/sleep-desk/releases) for APKs attached to `v*` tags (e.g. `sleep-desk-v0.3.2-debug.apk`).

No manual tag is needed for main builds. To cut a separate versioned release: `git tag vX.Y.Z && git push origin vX.Y.Z` (triggers the Release workflow). You can also run **Release** via `workflow_dispatch` with an optional tag input.

Without upload-keystore Secrets, these are debug-signed APKs (signatures may differ across runs). With Secrets configured, CI uses your packaging key so upgrades stay consistent.

## CI signing (upload keystore)

Gradle reads signing from env (no keystore in git). Set these GitHub Actions **Secrets** to sign with your **existing** local packaging key:

| Secret | Purpose |
|--------|---------|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the local `.jks` / `.keystore` (`base64 -w0 your.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Workflows decode the base64 into `$RUNNER_TEMP/sleep-desk-upload.jks` and set `ANDROID_KEYSTORE_PATH`. **Do not generate a new key for CI.**

## How to use

1. Place the phone on the nightstand (not required on the mattress).
2. Tap **开始睡** → grant mic / notifications.
3. Leave the app running in the background; live Snackbar shows detected events.
4. Morning: **结束** → segment list + timeline; tap a segment for summary and clips.

## Build with Gradle

```bash
git clone https://github.com/redreamality/sleep-desk.git
cd sleep-desk
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK / JDK suitable for AGP 8.5 + Kotlin 1.9.

## Privacy

- Processing and storage are **on-device**.
- Only **short clips around key events** are written under `audio_clips/{sessionId}/`—not continuous raw audio.
- Export is user-initiated (share sheet). There is no telemetry backend in this app.
- You can delete a single night or wipe everything from History.

## Documentation

| Doc | Topic |
|-----|--------|
| [`docs/audio-algo.md`](docs/audio-algo.md) | Microphone pipeline, event types, far-field config |
| [`docs/segments.md`](docs/segments.md) | Segment aggregation & representative clip quota |
| [`docs/sleep-sounds-and-cycles.md`](docs/sleep-sounds-and-cycles.md) | Honest boundaries vs sleep staging / medical claims |

## Architecture (short)

```
MainActivity (首页 / 历史)
  ├─ HomeFragment — start/stop, live events, segments
  └─ HistoryFragment — nights, export, delete

SleepTrackingService (FGS microphone)
  ├─ NightAudioEngineImpl
  └─ SecondarySignals

SegmentBuilder → NightSegment[] + clip quota
SessionStore → sessions.json
audio_clips/{sessionId}/*.m4a
```

## License

MIT — see [LICENSE](LICENSE).

---

## Sleep Desk / 睡眠桌面 (中文)

[![CI](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml/badge.svg)](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/redreamality/sleep-desk?include_prereleases&sort=semver)](https://github.com/redreamality/sleep-desk/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

用**麦克风**做夜间环境音监测的极简 Android 睡眠记录工具（手机可放床头柜，不必压在床垫上）。一键开始/结束，段式时间线，关键事件短片段，**全部本地存储**。

| | |
|---|---|
| **包名** | `com.i3u8.sleepdesk` |
| **最新构建** | [最近成功的主分支发布](https://github.com/redreamality/sleep-desk/releases/latest) |
| **许可证** | [MIT](LICENSE) |
| **文档** | [`docs/`](docs/) |

> **免责声明：** 实验性、**非医疗**产品。本应用估计夜间声学活动与声音事件分段，**不是**睡眠分期图（hypnogram）、临床多导睡眠或医疗器械。请勿用于诊断或治疗决策。

### 它是什么

前台麦克风服务持续监听环境音，规则分类短时声学事件（鼾声、咳嗽、说话、夜醒相关声等），聚合成**密度感知的段（Segment）**，并只保留**代表 AAC 片段**——从不录制整晚原始音频。

手机放床头即可。屏幕/充电/光线等为辅信号，不是主路径。

### 功能

- **麦克风优先** — 前台麦克风服务；默认远场/高灵敏度，适合床头摆放
- **段式夜晚** — 同类型粘连 → 超长切开 → MIXED；时间线按段着色
- **代表片段** — 每段 0–2 条 AAC（长鼾 ≥10 分钟最多 3 条）；只引用，不自动删文件
- **导出** — 「导出本晚」/「导出全部」→ zip（会话 JSON + 片段），系统分享面板
- **删除** — 删单晚或清空全部（二次确认），级联清理 `audio_clips`
- **本地隐私** — 数据不出设备；无账号、无云端上传音频

### 安装（Releases）

1. 打开 **[Releases](https://github.com/redreamality/sleep-desk/releases)**，下载最新 debug APK（如 `sleep-desk-v0.3.2-debug.apk`）。
2. 在 Android 上允许来自浏览器/文件管理器的安装。
3. 点击 **开始睡** 时授予**麦克风**（Android 13+ 还需通知权限）。

目前仅侧载，尚未上架应用商店。


### 获取 CI APK

- **Actions（每次 push/PR 到 `main`）：** 打开 [Actions](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml) → 最近一次成功的 **Android CI** → 下载产物 `sleep-desk-debug`（若已配置签名 Secrets，另有 `sleep-desk-release`）。
- **自动发布（仅推送 `main`）：** 测试和打包成功后，将同一 APK 发布为 `main-运行编号-提交短哈希`。PR 和失败的构建不发布；重跑同一次 CI 更新同一 Release，旧提交不会替换 Latest。应用内版本号不会自动递增。
- **Releases（打标签构建）：** 打开 [Releases](https://github.com/redreamality/sleep-desk/releases) 下载挂在 `v*` 标签上的 APK（如 `sleep-desk-v0.3.2-debug.apk`）。

主分支构建无需手动打标签。另发版本号版：`git tag vX.Y.Z && git push origin vX.Y.Z`（触发 Release 工作流）。也可在 Actions 里手动跑 **Release**（可选填写 tag）。

未配置上传 keystore Secrets 时为 debug 签名（不同构建可能不一致）。配置后 CI 使用本机打包同一把 key，便于覆盖安装。

### CI 签名（本机上传 keystore）

Gradle 只从环境变量读签名（仓库不进 keystore）。在 GitHub Actions **Secrets** 配置：

| Secret | 用途 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | 本机 `.jks` / `.keystore` 的 Base64（`base64 -w0 your.jks`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key 别名 |
| `ANDROID_KEY_PASSWORD` | key 密码 |

Workflow 解到 `$RUNNER_TEMP/sleep-desk-upload.jks` 并设置 `ANDROID_KEYSTORE_PATH`。**不要为 CI 新造 key。**

### 使用

1. 手机放床头柜即可。
2. 点 **开始睡** → 授权麦克风/通知。
3. 可离开应用；检测到事件时有 Snackbar 提示。
4. 早上点 **结束** → 查看段列表与时间线；点进段可听代表片段。

### 用 Gradle 构建

```bash
git clone https://github.com/redreamality/sleep-desk.git
cd sleep-desk
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

需要适配 AGP 8.5 + Kotlin 1.9 的 Android SDK / JDK。

### 隐私

- 处理与存储均在**本机**。
- 仅在关键事件写入短片段到 `audio_clips/{sessionId}/`，**不**连续存 raw。
- 导出由用户主动触发（系统分享）；应用内无遥测后端。
- 可在历史中删除单晚或清空全部。

### 文档

| 文档 | 内容 |
|------|------|
| [`docs/audio-algo.md`](docs/audio-algo.md) | 麦克风管线、事件类型、远场配置 |
| [`docs/segments.md`](docs/segments.md) | 段聚合与代表 clip 配额 |
| [`docs/sleep-sounds-and-cycles.md`](docs/sleep-sounds-and-cycles.md) | 与睡眠分期/医疗表述的诚实边界 |

### 许可证

MIT — 见 [LICENSE](LICENSE)。
