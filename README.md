# Sleep Desk / 睡眠桌面

Android sleep tracker — **microphone ambient monitoring** (no bedside placement required), one-tap start/stop, local-only.

**Package:** `com.i3u8.sleepdesk` · **v0.2.0** · MIT

## What’s new in v0.2

- **Primary signal = microphone** via foreground service (`FOREGROUND_SERVICE_MICROPHONE`)
- Continuous low-cost energy / VAD-style gate → rule classifier (`SNORE` / `COUGH` / `SPEECH` / `NIGHT_WAKE_SOUND` / …)
- **Short AAC clips only** on key events (~1.5 s pre/post, max 8 s) — **never** full-night WAV
- Two bottom tabs: **首页** (today) · **历史** (duration bars + night list)
- Secondary signals (no mattress needed): screen on/off, charging, optional light sensor
- Accel-as-primary from v0.1 removed
- Pluggable `NightAudioEngine` — see [`docs/audio-algo.md`](docs/audio-algo.md)

## How to use

1. Phone can stay on the nightstand / charger — **not** on the mattress.
2. Tap **开始睡** → grant **microphone** (and notifications on Android 13+).
3. Leave the app; ongoing notification keeps ambient monitoring.
4. Morning: tap **结束** → see duration + event / clip counts on 首页; past nights on 历史.

## Build

Requirements: JDK 17+ (21 OK), Android SDK platform 34 + build-tools 34.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | When |
| --- | --- |
| `RECORD_AUDIO` | Requested when user taps 开始睡 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Ambient mic FGS |
| `POST_NOTIFICATIONS` | Ongoing tracking notification (API 33+) |
| `WAKE_LOCK` | Keep sampling while screen off |

## Architecture

```
MainActivity (BottomNav: 首页 / 历史)
  ├─ HomeFragment — one-tap start/stop, live / last summary
  └─ HistoryFragment — Canvas duration bars + session list

SleepTrackingService (FGS microphone)
  ├─ NightAudioEngineImpl  ← docs/audio-algo.md
  │    AudioRecord 16 kHz mono → relative energy gate → candidates
  │    → RuleClassifier → NightEvent → AAC clip (AudioClipStore)
  └─ SecondarySignals (screen / charge / light)

SessionStore — sessions.json (history + audioEvents index + clip paths)
audio_clips/{sessionId}/*.m4a — private app storage only
```

Swap the algorithm by implementing `NightAudioEngine` / replacing `NightAudioEngineImpl` wiring in the service.

## License

MIT — see [LICENSE](LICENSE).
