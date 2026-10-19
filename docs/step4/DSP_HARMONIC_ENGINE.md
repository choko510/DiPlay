# DSP-04 Harmonic Engine

DSP-04 adds an opt-in High Exciter and Virtual Bass around one bounded harmonic core. Both effects default off. The core uses 2× oversampling and a windowed-sinc FIR to limit aliasing; the quality setting selects 9, 17, or 33 taps. Even, Odd, and Mixed modes share that core.

## Behavior

- High Exciter feeds a bounded harmonic residual from an upper-frequency high-pass band. Virtual Bass derives bounded harmonics from a low-pass bass band, then high-passes the generated component so it does not simply boost the original bass.
- Effect amounts are limited to 0–0.3. The optional Level Match applies deterministic output compensation, while Auto Headroom budgets the maximum combined harmonic gain and the existing safety limiter remains last.
- The oversampling FIR group delay is 4, 8, or 16 frames by quality. The dry signal is delayed by the same frame count; the native engine adds it to Seat Focus and Convolver latency so `PlaybackClock`, bypass, and live-update latency checks see the complete graph delay.
- Rebuilding an active harmonic effect requires fresh FIR history, so live parameter changes defer to the next stream. Enabling/disabling the last active effect or changing quality can also change latency and is deferred; the UI explains this behavior.
- The Harmonic profile fields are schema v7 and default off in v0–v6 profiles. This adds no processing when neither effect is active.

## Verification

| Gate | Result | Evidence |
|---|---|---|
| Shared/common unit tests | `PASS_LOCAL`: 484/140 | Full tasks forced to rerun; zero failures/errors/skips; includes config bounds, headroom, schema v7 migration, JNI values, and deferred quality-change adoption |
| Native C and ASan/UBSan | `PASS_EMULATOR` | NDK Clang x86_64 build and API 36.1 AVD run; exercises even/odd partials, alias rejection, 4/8/16-frame dry alignment, and Harmonic+Convolver latency sum. LeakSanitizer disabled. |
| Android instrumentation | `PASS_EMULATOR`: 19/19 | API 36.1 x86_64 AVD; JNI creation/latency and HarmonicEngine benchmark matrix passed |
| Mobile/automotive lint and builds | `PASS_LOCAL` | Both lints and debug/benchmark APK assembly passed |
| JNI mappings and public tree | `PASS_LOCAL` | Both benchmark R8 mappings verified; credential scan passed |
| Target-car audio, THD/alias listening, and long-run validation | `NOT_RUN` | Requires TS10/K706/iPhone measurement; no sound-quality improvement is claimed |

### HarmonicEngine API 36.1 emulator performance

Each entry is the median of three 256-block runs at 128 frames; `max` is the largest single-block value across the runs. Native process timings and the production pipeline (including latency-preserving bypass) are shown separately.

| Rate/channel | Native p50 / p95 / p99 / max (µs) | Production p50 / p95 / p99 / max (µs) |
|---|---:|---:|
| 44.1 kHz mono | 320 / 360 / 380 / 474 | 620 / 680 / 740 / 907 |
| 44.1 kHz stereo | 500 / 540 / 620 / 758 | 960 / 1,040 / 1,180 / 1,392 |
| 48 kHz mono | 300 / 340 / 400 / 541 | 560 / 680 / 800 / 826 |
| 48 kHz stereo | 520 / 620 / 660 / 846 | 980 / 1,160 / 1,260 / 1,427 |
