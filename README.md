# Sleep Desk / 睡眠桌面

Android sleep tracker — **microphone ambient monitoring** (no bedside placement required), one-tap start/stop, local-only.

**Package:** `com.i3u8.sleepdesk` · **v0.3.1** · MIT

## What’s new in v0.3.1 — compat / perf / export

- **Legacy sessions.json**: nights without `segments` still load fully; on open, auto-run SegmentBuilder and persist once (events never deleted)
- **Perf**: History uses lightweight summaries; session detail defaults to segments + timeline (events lazy on expand); IO off main thread; timeline draw O(segments)
- **Export**: 「导出本晚」/「导出全部」→ zip (sessions JSON + representative AAC clips) via system share sheet

## What’s new in v0.3.0 — 段式夜晚

- **Segment bands**: night split into density-aware bouts (same-type merge → split if >20 min → MIXED merge); timeline colored by segments
- **Representative clips**: 0–2 AAC paths per segment (up to 3 for long snore ≥10 min); strategy A — reference only, never delete clip files
- **Collapsed events**: Home / session detail primary list = segments; tap segment → summary + play clips; fine-grained events behind expand
- **Honesty**: UI marks 估算/实验性; not a hypnogram / deep-sleep / medical staging — see [`docs/sleep-sounds-and-cycles.md`](docs/sleep-sounds-and-cycles.md)
- Spec: [`docs/segments.md`](docs/segments.md) · detection layer unchanged ([`docs/audio-algo.md`](docs/audio-algo.md))

## What’s new in v0.2.4

- **Delete data**: delete one night + **清空全部** with double confirm; cascades `audio_clips`
- **Full-night timeline** + acoustic activity band + experimental cycle band (superseded by segments in 0.3)

## What’s in v0.2

- **Primary signal = microphone** via foreground service (`FOREGROUND_SERVICE_MICROPHONE`)
- Continuous energy gate → rule classifier → **short AAC clips only** on key events
- Two bottom tabs: **首页** · **历史**
- Secondary signals: screen / charge / light
- Pluggable `NightAudioEngine` — see [`docs/audio-algo.md`](docs/audio-algo.md)

## How to use

1. Phone can stay on the nightstand — **not** on the mattress.
2. Tap **开始睡** → grant **microphone** (and notifications on Android 13+).
3. Leave the app; live Snackbar「检测到：…」on events.
4. Morning: **结束** → segment list + timeline; tap a segment to hear representative clips.

## Build

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
MainActivity (BottomNav: 首页 / 历史)
  ├─ HomeFragment — sleep button, stats, segment list, live Snackbar
  └─ HistoryFragment — duration bars + session cards → segment timeline

SegmentDetailBottomSheet — summary + representative clips + expand events
SessionDetailBottomSheet — segment timeline + segment list + collapsed events

SleepTrackingService (FGS microphone)
  ├─ NightAudioEngineImpl  ← docs/audio-algo.md
  └─ SecondarySignals

SegmentBuilder (docs/segments.md) — bout merge → NightSegment[] + clip quota
SessionStore — sessions.json (events + materialized segments at stop)
audio_clips/{sessionId}/*.m4a
```

## License

MIT — see [LICENSE](LICENSE).
