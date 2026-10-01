# Sleep Desk / 睡眠桌面

Android sleep tracker — **microphone ambient monitoring** (no bedside placement required), one-tap start/stop, local-only.

**Package:** `com.i3u8.sleepdesk` · **v0.2.4** · MIT

## What’s new in v0.2.4

- **Delete data**: delete one night (Home / History / session detail) + **清空全部** with double confirm; cascades `audio_clips`
- **Full-night timeline** in session detail: colored event markers, interrupt ticks (screen/charge), tap to replay
- **Acoustic activity band** labeled「夜间声音与体动活跃度（非睡眠分期）」
- **Experimental cycle band** (toggle): wake-ish / quieter-NREM-ish / rem-ish heuristic — marked 实验性/非医疗, not a hypnogram
- Includes [`docs/sleep-sounds-and-cycles.md`](docs/sleep-sounds-and-cycles.md)

## What’s new in v0.2.2

- **Higher sensitivity** (default): lower relative energy margin, shorter candidate gate, relaxed `RuleClassifier` — fewer misses, more false positives OK
- **Far desk**: try `UNPROCESSED`, auto-fallback to `MIC` if idle gain too low; longer EMA + p15 noise floor — see [`docs/audio-algo.md`](docs/audio-algo.md) §2.4
- **Live feedback**: on each event, home shows Snackbar「检测到：…」and refreshes the event list immediately (not only after stop)
- Includes v0.2.1: **tap-to-replay** AAC clips + **Material 3** UI polish

## What’s in v0.2

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
3. Leave the app; ongoing notification keeps ambient monitoring. When an event fires, the home tab shows a short「检测到」toast and inserts the row live.
4. Morning: tap **结束** → tonight stats + event list; tap an event to replay its clip. Past nights on 历史 (tap a card → event list).

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
  ├─ HomeFragment — big sleep button, tonight stats, live Snackbar + tappable events
  └─ HistoryFragment — Canvas duration bars + session cards → event list

EventDetailBottomSheet — type / time / Play-Pause via ClipPlayer (MediaPlayer)
SessionDetailBottomSheet — full-night timeline + events + delete

SleepTrackingService (FGS microphone)
  ├─ NightAudioEngineImpl  ← docs/audio-algo.md (high-sens + MIC fallback)
  │    AudioRecord 16 kHz mono → relative energy gate → candidates
  │    → RuleClassifier → NightEvent → AAC clip (AudioClipStore)
  └─ SecondarySignals (screen / charge / light)

SessionStore — sessions.json (history + audioEvents index + clip paths)
audio_clips/{sessionId}/*.m4a — private app storage only
```

Swap the algorithm by implementing `NightAudioEngine` / replacing `NightAudioEngineImpl` wiring in the service.

## License

MIT — see [LICENSE](LICENSE).
