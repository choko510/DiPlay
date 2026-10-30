# DSP Studio target-hardware runbook

Use this runbook on a TS10/K706 head unit and the supported iPhone/CarPlay combination. It is a procedure only; no results are recorded here. Do not mark a gate PASS until the collected evidence has been reviewed against [the remediation specification](REMEDIATION_SPEC.md) or an approved acceptance threshold.

## 1. Build and install

From the repository root, build the Automotive debug APK and confirm its location:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ./gradlew --no-daemon --console=plain :automotive:assembleDebug
find automotive/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -print
```

Connect the head unit, install the exact generated APK path, and record the app version and device identity:

```sh
adb devices -l
adb install -r automotive/build/outputs/apk/debug/automotive-debug.apk
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell dumpsys package com.shilapi.xcertplay | rg 'versionName|versionCode'
```

If the APK filename differs, use the path printed by `find`. Use the signed release artifact for release-signing checks; a debug install does not prove release signature or rollback behavior.

## 2. Capture one test session

Record the head-unit model/firmware, app SHA/version, iPhone model/iOS, CarPlay connection type, PCM sample rate/channels/encoding, selected DSP profile/workspace, DSP Master state, route token/calibration state, and ambient/volume conditions. Keep raw diagnostic logs local and redact identifiers before sharing.

Start a bounded log capture before connecting the iPhone:

```sh
adb logcat -c
adb logcat -v threadtime > dsp-studio-logcat.txt
```

Stop capture with Ctrl-C after the session. Retain only the diagnostic lines needed to explain renderer status, generation, deferred/failed updates, CarPlay layout, and lifecycle. Do not commit raw device logs.

## 3. Verify baseline and DSP Master OFF

1. Start a fresh CarPlay media track with DSP Master OFF. Record the advertised PCM format, callback behavior, audio latency, and any audible discontinuity.
2. Compare the same source with the parent PR #45 baseline build using the same device, route, track, volume, and connection. Confirm the new-track OFF output remains on the existing PCM16 bypass path.
3. Turn DSP Master OFF mid-track separately. Record the retained latency behavior; do not describe it as the new-track bit-exact bypass.
4. Exercise MEDIA-only and navigation/call/Siri audio roles. Confirm only the intended MEDIA role receives the full DSP path and that route/session ownership remains stable.

## 4. Exercise DSP Studio and CarPlay Split

1. Open DSP Studio in FULL mode with media playing and DSP Master ON. Exercise Macro controls, 15-band PEQ editing, module Factory/User presets, A/B, Undo/Redo, Preview, Save, and Cancel. Studio workspace edits live-preview as values change; the explicit Preview action reapplies the current workspace. Verify Cancel restores the committed workspace and Save survives process restart. Repeat with DSP Master OFF and confirm the legacy bypass path remains active.
2. Open the 55:45 CarPlay Split pane. Measure the actual pane bounds and confirm video remains visible and touchable. Record the Surface/session identity before and after every mode transition.
3. Perform 20 cycles of Split open → DSP pane navigation/edit → Split close → reopen. Count failures, lost frames, black surfaces, touch misroutes, audio restarts, and session re-creation. A composable-only presentation test does not satisfy this gate.
4. Repeat with profile changes that alter stateful processing or latency. Confirm the renderer reports DEFERRED when a safe in-place switch is unavailable and adopts only the correct generation.

## 5. Verify signal and loudness behavior

1. At Macro value 50/50/50/50 with Macro disabled, compare output against the legacy profile path. Sweep each control from 0 through 100 using known test material and capture output levels/spectrum. Verify no profile fields are rewritten.
2. Exercise Dynamic Bass with gated silence, low-level bass, high-level bass, transients, mono, and stereo. Capture detector/envelope/gain behavior and compare output with Dynamic Bass off; verify no clicks or clipping and no duplicated boost with existing Bass/Dynamic EQ/Harmonic.
3. Keep Smart Loudness AUTO unavailable until a trusted, route-specific calibration is recorded. Change route/device and verify the old calibration is rejected. Separately exercise MANUAL and FIXED modes.
4. Measure latency, peak/true-peak, THD/noise, frequency response, and A/V synchronization against the same-device baseline. Record tools, file format, sample rate, and calibration chain.

## 6. Lifecycle, performance, and soak

1. During playback, exercise rapid setting changes, track changes, PCM format changes, split open/close, and surface recreation. Record requested and active generations, status transitions, clicks/dropouts, latency, and session/Surface identities.
2. Exercise Siri, phone call, navigation prompt, and return to media. Confirm routing, volume, session continuity, and DSP status recover without stale ACK or UI ownership.
3. Measure CPU, RSS, renderer p99/control-to-active latency, and audio callback/dropout counters for the required sample formats and representative signal paths. State the device, firmware, build type, and measurement tool.
4. Run a 30–60 minute media soak and record start/end temperature, CPU/RSS, dropouts, status errors, and audio quality observations.
5. Repeat the connection and rollback procedure using the previous signed build. Verify data compatibility and that a failed update or disabled DSP falls back to the supported legacy path.

## 7. Required evidence bundle

Attach a concise report with command/build SHA, device/firmware, iPhone/iOS, test conditions, raw measurement files, redacted log excerpt, per-step PASS/FAIL/NOT_RUN, defects with reproduction, and reviewer sign-off. Keep hardware results separate from host unit tests and emulator instrumentation. Update [TEST_MATRIX.md](TEST_MATRIX.md) and [FINAL_IMPLEMENTATION_REPORT.md](FINAL_IMPLEMENTATION_REPORT.md) only after reviewing the evidence.
