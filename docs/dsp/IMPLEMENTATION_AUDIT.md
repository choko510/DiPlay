# Four-Module DSP Integration Audit

Updated 2026-10-19 for implementation branch `codex/diplay-4dsp`, based on `95ba398f95ee104527812cd2b095977d71c51f98` (PR #14 merged into `main`).

## Integrated graph and timing

The active MEDIA graph runs input meter → gain/preamp → Seat Focus → PEQ → bass shelf → compressor → multiband → Dynamic EQ → Cabin Correction → the existing spatial node (legacy Stereo Width/Mono Bass plus Advanced M/S) → Harmonic Engine → Convolver → Safety Limiter → output meter. No separate stereo-width node is added. Non-MEDIA roles and DSP master-off keep their existing paths.

Seat Focus reserves `ceil(sampleRate × 5.5 ms)` frames for every active stereo graph. Harmonic uses 64 frames at both 2× and 4×. The Convolver uses 128 frames when its IR and output rate match at 44.1/48/96 kHz. Graph latency is the sum of active module latency plus any latency-preserving output delay; the renderer publishes that value per AudioTrack generation and uses it for both playback-clock mapping and delayed-dry fallback. An active Harmonic history is not replaced after PCM commits to the track; that update waits for the next AudioTrack generation. Transient Harmonic A/B bypass is updated in place over 20 ms.

At 48 kHz Stereo, an instrumented graph with Seat Focus, Advanced M/S, Cabin, Harmonic, and Convolver reports 456 frames: 264 + 64 + 128. Unsupported Harmonic bands bypass only their own branch. Unsupported or mismatched Convolver rates disable only Convolution; the other stages remain active.

## Validation

- Shared and common full unit suites: 641 tests, 0 failures.
- Android instrumentation: 23 tests passed on `Medium_Phone_API_36.1` (API 36, x86_64), including the full-module JNI graph and summed latency.
- Host native C DSP suite passed with the repository's C11 warning-as-error flags. Synthetic 10 kHz ODD-mode folded 18 kHz energy measured −129.26 dBc at 2× and −129.56 dBc at 4×.
- The native C suite was cross-compiled with NDK 27 Clang ASan/UBSan and passed on the API 36 x86_64 emulator. `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1`; `UBSAN_OPTIONS=halt_on_error=1`. LeakSanitizer was disabled for this emulator path.
- Android native libraries built for arm64-v8a, armeabi-v7a, and x86_64. Mobile/automotive Debug and Benchmark builds, both app lint tasks, R8 JNI mapping checks, and the public-tree credential scan passed locally.
- GitHub Actions had not created a hosted PR workflow run or check status for the final head at inspection time. The hosted CI link is the PR checks page; local sanitizer/Gradle runs are separate from hosted CI.

## Emulator performance matrix

The API 36 x86_64 benchmark covers 18 configurations at 44.1/48 kHz, Mono/Stereo, with three measured runs of 256 blocks each: 216 machine-validated records. Every case reported zero steady-state Kotlin allocations. Percentiles below are medians across the three runs; `max` is the worst observed sample. Units are microseconds.

| 48 kHz Stereo case | Native p50/p95/p99/max | Pipeline p99 | Production p99/max |
|---|---:|---:|---:|
| Harmonic2x | 800 / 940 / 1,020 / 1,180 | 1,360 | 1,580 / 1,757 |
| Harmonic4x | 1,480 / 1,720 / 1,840 / 2,064 | 2,200 | 2,420 / 2,642 |
| VirtualBass | 1,520 / 1,800 / 1,880 / 2,046 | 2,240 | 2,460 / 2,668 |
| All4 | 3,100 / 3,400 / 3,660 / 3,807 | 4,000 | 4,220 / 4,334 |
| All4 + Convolver64k | 5,060 / 6,060 / 6,260 / 6,956 | 6,600 | 6,820 / 7,528 |
| LegacyBaseline | 1,400 / 1,580 / 1,720 / 1,980 | 2,020 | 2,240 / 2,447 |

The worst production p99 was 6.82 ms, below the 7.5 ms 30%-headroom target for a 512-frame/48 kHz block. The worst observed sample was 7.528 ms, still below the 10.67 ms block duration. A three-graph swap sample with active Convolver64k, All4+Convolver64k candidate and rollback, and latency-aligned fallback recorded 12,182,480 bytes of native heap (6,484,256-byte increase) and 62,814 KiB process PSS. These are emulator process measurements, not TS10 memory limits.

The instrumentation workflow writes raw CSV/JSON, an aggregate CSV, and this-format Markdown performance tables under `shared/build/outputs/dsp-benchmark/` and uploads them as an Actions artifact.

## Remaining validation limits

TS10 hardware was unavailable. CarPlay playback continuity, 30–60 minute dropout/underrun behavior, target-device latency/thermal/CPU/memory, and listening-room or in-car acoustic evaluation remain unverified. Cabin Correction requires an imported measurement; guided microphone measurement is unavailable. No user-facing sound-quality claim is based on the synthetic alias or emulator tests.
