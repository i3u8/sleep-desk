# Android validation environment

Date: 2026-10-03 (Australia/Sydney).

## Scope

- Environment preparation only until the source owner confirms a final build.
- Do not modify production source or Gradle files.
- Never install on, uninstall from, or clear data on a physical device.
- Dedicated AVD: `sleepdesk_audio_v2_api30`; reserved serial: `emulator-5580`.
- SDK: `C:/Users/unknown/.cache/sleep-desk-toolchains/android-sdk`.
- JDK: `C:/Users/unknown/.cache/sleep-desk-toolchains/jdk/jdk-17.0.20.1+1`.
- Gradle: portable 8.7, existing `gradle-home` cache.

## Initial observations

- `adb devices -l`: no devices attached; adb server started automatically.
- Windows HypervisorPresent is true; firmware virtualization is enabled.
- SDK initially had platform/build-tools 34 and platform-tools.
- No existing S: substitution was reported.
- No project agent-incidents logger was found in the file inventory.

## Command incidents

| Symptom | Root cause | Avoidance |
| --- | --- | --- |
| File discovery command returned exit 1 | `rg --files -g '*incident*' -g AGENTS.md` matched no files; not a tool or dependency failure | Treat rg exit 1 as empty search results; explicitly normalize this expected status in future scripted discovery |
| `Get-Process -Id 27260` returned "process not found" | SDK installation completed between the progress observations and the process lookup | Poll the owning execution session for completion; use `-ErrorAction SilentlyContinue` for optional process observations |

## Ready state (11:19 local)

- Official SDK manager installation completed successfully, exit 0.
- Emulator version: 37.2.12; API 30 default x86_64 system image installed.
- `emulator -accel-check`: exit 0, `WHPX(10.0.26200) is installed and usable`.
- AVD home: `C:/Users/unknown/.cache/sleep-desk-toolchains/avd`.
- AVD name verified through `adb -s emulator-5580 emu avd name`.
- Boot completed within the 180-second limit; API 30, ABI x86_64, `ro.kernel.qemu=1`.
- Device serial: `emulator-5580`. No physical devices were present.
- `pm list packages com.i3u8.sleepdesk` returned no packages.
- `S:` now maps to this repository; no existing drive mapping was replaced.
- Screenshot inspected: nonblank launcher, no dialogs; retained under
  `../private-analysis/android-validation-v2/environment-ready.png` (outside the repository).
- Active startup output: `emulator-stdout.txt` and `emulator-stderr.txt`.
  These remain at the original location while the emulator is running, but are Git-ignored.
- Validation script passed PowerShell AST syntax parsing; build/test execution remains deferred.
- No APK installed, app data cleared, source/Gradle edited, or global virtualization settings changed.
- Emulator remains running for the final validation handoff.

## Restart if needed

Only run when this dedicated AVD is stopped and port 5580 is free:

```powershell
$env:ANDROID_HOME = 'C:/Users/unknown/.cache/sleep-desk-toolchains/android-sdk'
$env:ANDROID_AVD_HOME = 'C:/Users/unknown/.cache/sleep-desk-toolchains/avd'
# Run from the repository's physical path, not its S: alias.
$repo = (Get-Location).Path
$logs = Join-Path (Split-Path $repo -Parent) 'private-analysis/android-validation-v2'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
Start-Process "$env:ANDROID_HOME/emulator/emulator.exe" -WindowStyle Hidden `
  -ArgumentList @('-avd','sleepdesk_audio_v2_api30','-port','5580',
    '-no-window','-no-audio','-no-snapshot','-no-boot-anim',
    '-gpu','swiftshader_indirect','-memory','2048','-cores','2','-accel','on') `
  -RedirectStandardOutput "$logs/emulator-stdout.txt" `
  -RedirectStandardError "$logs/emulator-stderr.txt"
```

Wait at most 180 seconds for `adb -s emulator-5580 shell getprop sys.boot_completed`
to return `1`; do not repeatedly restart on failure. Software fallback was not
needed because hardware acceleration worked.

## Final validation entry point

After explicit source-stability confirmation, run from the repository's physical
path (not its S: alias). The script uses S: internally for Gradle only:

```powershell
& ./tools/verify-android-isolated.ps1 -SourceStable
```

The script checks AVD identity and boot completion before building. It runs
`testDebugUnitTest assembleDebug assembleDebugAndroidTest`, verifies the debug APK
signature and packaged TFLite hashes against source assets, then runs
`connectedDebugAndroidTest` with the dedicated `ANDROID_SERIAL`.
Timestamped logs, reports, model hashes and a final screenshot are retained under
`../private-analysis/android-validation-v2` outside the repository, alongside the
other experiments. The output root is derived from the repository parent, not a
hardcoded machine-specific path.
The full suite explicitly sets `privateReplay=false`, so `PrivateSoundReplayTest`
uses its default assumption skip. No private recording fixtures are uploaded.
`FullAudioPipelineTest` runs as part of the normal instrumentation suite.
The final screenshot is evidence of device state, not proof of every UI assertion.
