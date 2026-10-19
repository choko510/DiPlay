# DSP-05 Final Graph Integration

## Integrated graph

The finalized processing order is `gain → Seat Focus → static PEQ (including approved Cabin Correction filters) → Bass → compressor → multiband → Dynamic EQ → Advanced M/S → Harmonic Engine → Convolver → safety limiter → PCM16`. The four STEP4 features are integrated into the existing Float32 graph. Cabin Correction continues to reuse PEQ slots; no second notch stage is introduced. `DspPcmPipeline` performs the final PCM16 conversion once.

With the DSP master enabled, the 16 on/off combinations of Seat Focus, Advanced M/S, Cabin Correction, and Harmonic Engine are exercised at 44.1 and 48 kHz in mono and stereo. A single 1,024-frame call is compared byte-for-byte with 1, 127, 256, 512, and 128-frame calls. The matrix also checks total Seat Focus plus Harmonic latency and finite, limiter-bounded output. Mono format preparation disables Seat Focus and Advanced M/S while retaining supported mono processing.

## Final audit

- Existing migration tests cover profile schemas 0–7 and verify that future schemas are returned without overwriting their files. Profiles through v6 keep the Harmonic Engine off.
- JNI creation rejects malformed Harmonic arrays, non-finite and out-of-range values, invalid enum values, and cutoffs beyond the sample-rate safety margin.
- Native alias rejection is measured at 44.1/48 kHz for mono/stereo. The NDK C suite passes unsanitized and with ASan/UBSan on the API 36.1 x86_64 AVD; LeakSanitizer is disabled. Host `scripts/test_dsp_native.sh` remains `NOT_RUN` because WSL is unavailable and no Windows host C compiler is installed.
- KISSFFT remains the only vendored FFT dependency. Its BSD-3-Clause notice and license text are already included in `docs/THIRD_PARTY_NOTICES.md` and `docs/licenses/dependencies/`.
- No `SoundRiser` or `Realizer` processing path exists in the source tree, so no duplicate legacy exciter application was found.
- Existing renderer tests cover graph preparation, deferred stateful updates, latency-preserving bypass, crossfade, and graph retirement. Quality/latency updates are re-prepared from the latest config when a new AudioTrack generation starts.

## Verification

| Gate | Result |
|---|---|
| Shared/common unit tests | `PASS_LOCAL`: 485/140; zero failures/errors/skips; forced rerun |
| API 36.1 x86_64 instrumentation | `PASS_EMULATOR`: 21/21, including all 16 feature combinations and malformed Harmonic JNI arguments |
| Native C and ASan/UBSan | `PASS_EMULATOR`; alias matrix covers both sample rates and channel counts |
| Mobile/automotive lint, debug and benchmark builds | `PASS_LOCAL` |
| Optimized JNI mappings and public-tree check | `PASS_LOCAL` |
| TS10/K706/iPhone listening, measured cabin response, THD/alias on hardware, CarPlay MEDIA A/V sync, and long-run test | `NOT_RUN` |

### FullStep4Integration API 36.1 emulator performance

This case combines 12 user PEQ bands and three approved cabin cuts, Bass, compressor, multiband, Dynamic EQ, Seat Focus, Advanced M/S, both Harmonic effects, a 4,096-frame Convolver impulse response, Auto Headroom, and the safety limiter. It uses 256 blocks of 128 frames per run. Values are the median of three runs; `max` is the largest individual block across those runs. Production timings include the latency-preserving bypass.

| Rate/channel | Native p50 / p95 / p99 / max (µs) | Production p50 / p95 / p99 / max (µs) |
|---|---:|---:|
| 44.1 kHz mono | 960 / 1,020 / 1,280 / 1,686 | 1,260 / 1,340 / 1,460 / 1,686 |
| 44.1 kHz stereo | 1,580 / 1,700 / 1,940 / 2,034 | 2,060 / 2,200 / 2,440 / 2,595 |
| 48 kHz mono | 920 / 1,000 / 1,140 / 1,387 | 1,220 / 1,320 / 1,460 / 1,685 |
| 48 kHz stereo | 1,580 / 1,700 / 2,000 / 2,357 | 2,040 / 2,180 / 2,480 / 3,012 |

These are emulator timing results, not vehicle or listening evidence. No sound-quality improvement or release readiness is claimed.
