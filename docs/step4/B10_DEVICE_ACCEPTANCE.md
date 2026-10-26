# B10 — TS10/K706 and iPhone Device Acceptance

## Current status

All target hardware rows below are `BLOCKED_DEVICE`, except the signed update/rollback row, which is `BLOCKED_ENV`. The current local ADB inventory contains only `Medium_Phone_API_35` (emulator-5556); it is useful for JNI and unit instrumentation, not for USB head-unit, iPhone, acoustic, or release acceptance. No TS10/K706 or supported iPhone was attached during this work.

The user-reported SH M20 + iPhone 17 Pro Max / iOS 27.0.1 session is `PARTIAL` evidence and outside the required TS10/K706 class. It does not close these rows. DSP Split C-00 remains `USER_CONFIRMED_RESOLVED`; only ordinary Split regression coverage belongs here.

## Hardware matrix

| ID | Status | Exact next run / required evidence |
|---|---|---|
| HW-01 | `BLOCKED_DEVICE` | On TS10/K706 + iPhone, cold wired start 10 times. Record the attempt-relative Stage3 chain through visible MAIN; distinguish each failure at iAP2, NCM RX/TX, IPv6/TUN, TCP, RECORD, or frame submission. |
| HW-02 | `BLOCKED_DEVICE` | While parked, reproduce cable detach/reconnect and USB-present OUT `NotReady`/close separately. Record queue/completion ownership, attach generation, teardown and recovery; assert no deadlock, duplicate queue, or stale owner close. |
| HW-03 | `BLOCKED_DEVICE` | Same-device NCM `AUTO` vs `NO_STATUS_POLLING` A/B with identical cable/phone/firmware. Compare first visible frame, `SCREEN_STREAM_OPENED`, success/timeouts, CPU and GC. Keep `AUTO` unless evidence supports a change. |
| HW-04 | `BLOCKED_DEVICE` | Complete 20 Full↔YouTube/DSP Split cycles. Record controller/session identity and remote dimensions; verify CarPlay audio, touch, and visible video stay active. |
| HW-05 | `BLOCKED_DEVICE` | Exercise 50/55/60% local scale, 1/2/3-finger input, and `ACTION_CANCEL`; verify viewport transforms, stable slot 0/1, and the three-finger menu. |
| HW-06 | `BLOCKED_DEVICE` | Exercise rotation, IME, background/foreground, Activity recreation, and Surface absent for 0/50/600 ms. Verify old callbacks cannot black out or stop the current screen. |
| HW-07 | `BLOCKED_DEVICE` | Exercise MAIN/ALT and a controlled MediaCodec release failure/recovery. Capture ownership ACKs and show old Surface release occurs only after the required ACKs; record first visible recovery frame. |
| HW-08 | `BLOCKED_DEVICE` | Test Siri, calls, voice messages, deny/allow paths, and route restoration. Verify permission policy, AEC/AGC behavior, Opus timestamps, and audio recovery without collecting PCM/audio payload. |
| HW-09 | `BLOCKED_DEVICE` | Exercise DSP master OFF/ON and Seat/M-S/Cabin/Harmonic/Convolver. Verify initial OFF byte-exact PCM16, headroom/THD/alias behavior, and A/V sync on the target hardware. |
| HW-10 | `BLOCKED_DEVICE` | Run 30–60 minutes wired with DSP load. Record PSS, native memory, file descriptors, GC, CPU, underruns/xruns, temperature, and Surface-retirement counts at fixed intervals. |
| HW-11 | `BLOCKED_ENV` | With authorized release signing credentials, install an old signed version, update with the same signing identity, verify schema/profile/IR preservation, then exercise the supported rollback. Record APK hashes and signing lineage; a Debug signature is not evidence. |
| HW-12 | `BLOCKED_DEVICE` | Reproduce MAIN and ALT SETUP followed by control TCP close before RECORD. Verify exactly one recovery for the current attempt and no effect on an already accepted session. |
| HW-13 | `BLOCKED_DEVICE` | On a supported API 28+ device, reproduce `queue()==false` while the USB device is still present. Capture endpoint/request state before considering any fallback. |

## Run record template

Create one record per test ID. Leave unavailable values `null`; do not infer them.

```yaml
branch: codex/step4-pr-00-test-baseline
head: <40-character source SHA>
apk_sha256: <64-character hash or null>
abi: <observed ABI>
signing_variant: <release/debug and signing identity class>
head_unit_model: <TS10/K706 model or null>
android_api: <observed API or null>
iphone_model: <model or null>
ios: <version or null>
usb_cable_setup: <short physical setup description>
debug_logs_enabled: <true/false>
ncm_diagnostic_profile: <AUTO/other profile>
test_id: <HW-01..HW-13>
attempt_id: <attempt identifier without device identity>
attach_generation: <generation or null>
start_monotonic_ns: <relative/host-provided value>
end_monotonic_ns: <relative/host-provided value>
connection_phase: <observed phase>
result: <PASS/FAIL/BLOCKED_DEVICE/NOT_RUN/BLOCKED_ENV>
redacted_artifact_ref: <sanitized local artifact reference or null>
excluded_data: [serials, MACs, certificates, keys, auth headers, audio and packet payloads]
```

## Collection and safety

- Run USB disconnect/fault tests only while parked. Do not perform cable pulls while driving.
- Change Bluetooth, Wi-Fi, call, or microphone permissions only through the user's normal settings flow. Do not automate grants or alter system app-ops.
- Capture only the relevant time window and tags, for example the `xcertplay-usb`, Stage3, and DSP benchmark tags needed by the test. Redact iPhone/iAP2 identifiers, MAC addresses, certificates, auth material, and any media or packet bodies before sharing.
- For a failure, retain the same-attempt monotonic timing line plus only the corresponding NCM RX/TX outcome, Surface owner/ACK, DSP generation, memory/FD snapshot, or thread state required to explain it.
- Keep raw device artifacts outside Git unless they are sanitized and explicitly approved for inclusion. Store the redacted summary and hash, not private identifiers.

## Re-entry condition

Run the matrix only when a supported TS10/K706, compatible iPhone, safe parked test setup, and a normal user-authorized pairing/permission path are available. HW-11 additionally requires authorized release signing credentials and a recoverable signed rollback package.
