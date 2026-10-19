# DSP-01 Seat Focus / L-R Time Alignment

DSP-01 adds per-channel seat timing, trim, and polarity controls to the existing two-channel MEDIA graph. The feature defaults off. DSP master-off still bypasses graph preparation.

## Behavior

- The selected Driver, Passenger, Center, or Custom preset records the target seat. Timing uses only the measured physical left/right speaker distances; preset and steering-wheel side do not infer which speaker is closer.
- With two non-zero measurements, relative delay is derived from their distance difference at 343 m/s and capped at 5 ms. The farther speaker receives `5 ms - relative delay`; the nearer speaker receives the fixed 5 ms budget. Equal distances receive 5 ms on each channel.
- Left and right trim are independent and limited to ±6 dB. Each channel can also invert polarity. Auto Headroom includes the largest positive trim while Seat Focus is enabled.
- Fractional delays use linear interpolation. The two delay rings are allocated while preparing an enabled stereo graph, and reset with the graph. The sample loop performs no allocation or locking. Mono input bypasses Seat Focus and adds no latency.
- An enabled stereo graph reports `ceil(sampleRate × 5 ms)` frames, independent of measured distances. This latency is added to Convolver latency and passed through the existing playback-clock path. Distance edits keep the same budget and use the live graph crossfade. Enable/disable changes can wait for the next audio stream because they change the graph latency; the settings UI states this.
- Profile schema v4 stores Seat Focus. Schema v0–v3 profiles migrate with Seat Focus disabled; unknown future schemas remain protected from overwrite.

## Verification

| Gate | Result | Evidence |
|---|---|---|
| Shared/common unit tests | `PASS_LOCAL`: 462/137 | Full shared and common test tasks forced to rerun; 599 total, zero failures/errors/skips |
| Fractional-delay phase | `PASS_LOCAL` | Native test measures 1 kHz and 4 kHz phase difference against the 1.5-frame target |
| Live distance update | `PASS_LOCAL` | Renderer test checks same 240-frame latency at 48 kHz, live crossfade, and stable playback-clock anchor |
| Android instrumentation | `PASS_EMULATOR`: 16/16 | API 36.1 x86_64 AVD; DSP JNI smoke tests and benchmark matrix |
| Seat Focus performance | `PASS_EMULATOR` | API 36.1 x86_64 AVD; 3 × 256 measured 128-frame blocks at 44.1/48 kHz. Native p50/p95/p99/max was 100/100–120/160/144–189 µs at 44.1 kHz and 100/120/160–180/180–307 µs at 48 kHz. Full production path including latency-preserving bypass was 620/680–700/720–780/793–891 µs and 620–640/720–740/740–800/810–932 µs respectively. |
| Native C and ASan/UBSan | `PASS_EMULATOR` | x86_64 Android build and API 36.1 AVD execution; not a host-native result |
| Host `scripts/test_dsp_native.sh` | `NOT_RUN` | WSL launch is unavailable (`HCS_E_SERVICE_NOT_AVAILABLE`) and no Windows host C compiler is installed |
| Mobile/automotive lint and builds | `PASS_LOCAL` | `:mobile:lintDebug`, `:automotive:lintDebug`, and debug/benchmark APK assembly succeeded |
| JNI mappings and public tree | `PASS_LOCAL` | Both benchmark R8 mappings verified; public-tree credential scan passed |
| TS10/K706/iPhone acoustics and long-run stability | `NOT_RUN` | Requires target hardware and measurements; no click/pop or quality acceptance is claimed |

The live update test verifies graph adoption and clock accounting; it does not replace listening or acoustic measurement on the target system.
