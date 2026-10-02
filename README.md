# Sleep Desk / 睡眠桌面

[![CI](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml/badge.svg)](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/redreamality/sleep-desk?include_prereleases&sort=semver)](https://github.com/redreamality/sleep-desk/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**English** · [中文](#sleep-desk--睡眠桌面-中文)

Minimal Android sleep tracker driven by the **microphone** (phone can stay on the nightstand—no mattress placement). One-tap start/stop, segment timeline, short key-event clips, local-only storage.

| | |
|---|---|
| **Package** | `com.i3u8.sleepdesk` |
| **Latest** | [v0.3.2](https://github.com/redreamality/sleep-desk/releases/tag/v0.3.2) |
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

- **Actions (every push/PR to `main`):** open [Actions](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml) → latest successful **Android CI** run → download artifact `sleep-desk-debug` (APK named with short commit SHA).
- **Releases (tagged builds):** open [Releases](https://github.com/redreamality/sleep-desk/releases) for APKs attached to `v*` tags (e.g. `sleep-desk-v0.3.2-debug.apk`).

To cut a release: `git tag vX.Y.Z && git push origin vX.Y.Z` (triggers the Release workflow). You can also run **Release** via `workflow_dispatch` with an optional tag input.

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
| **最新版** | [v0.3.2](https://github.com/redreamality/sleep-desk/releases/tag/v0.3.2) |
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

- **Actions（每次 push/PR 到 `main`）：** 打开 [Actions](https://github.com/redreamality/sleep-desk/actions/workflows/android.yml) → 最近一次成功的 **Android CI** → 下载产物 `sleep-desk-debug`（文件名含短 SHA）。
- **Releases（打标签构建）：** 打开 [Releases](https://github.com/redreamality/sleep-desk/releases) 下载挂在 `v*` 标签上的 APK（如 `sleep-desk-v0.3.2-debug.apk`）。

发版：`git tag vX.Y.Z && git push origin vX.Y.Z`（触发 Release 工作流）。也可在 Actions 里手动跑 **Release**（可选填写 tag）。

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
