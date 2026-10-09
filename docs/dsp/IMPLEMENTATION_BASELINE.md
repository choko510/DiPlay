# PR #14 Four DSP Modules: Baseline

Recorded 2026-10-09 before implementation. This describes the checked-out code, not a claim that the four requested modules are already implemented.

## Source revision and scope

- Repository: `choko510/DiPlay`; PR #14 is open against `main`.
- PR head / implementation base: `77d37ac8eda77c48ef020f58c26ea4e2e672e1ee` (`codex/dsp-01`). The specification's pinned `813f0d4` is an ancestor; the checked-out head includes subsequent latency, CI, and stale-generation fixes.
- PR base: `db9853266c71e2351c08b8934f223f7df671fd74`.
- Working branch: `codex/dsp-01-4dsp`, created from the verified PR head. No product source changes were present when baseline work began.
- `SoundRiser`/`Realizer` code is absent at this head; no related feature is assumed or changed.

## Existing signal path and contracts

1. `AndroidMediaSink` snapshots the configured MEDIA DSP graph per stream. `DspStreamPolicy` leaves navigation, alerts, assistant, phone, and other low-latency roles outside the DSP path. Master-off keeps the existing PCM16 write path.
2. `DspPcmPipeline` converts supported decoded PCM to reusable Float32 buffers, calls the native processor, and quantizes/dithers to PCM16 once. Graph replacement crossfades Float32 outputs before that final conversion.
3. The native graph order is gain/preamp, PEQ, bass shelf, compressor, three-band dynamics, Dynamic EQ, legacy stereo width/mono-bass M/S processing, convolver, limiter, and output meter. Stereo width and mono-bass share one `dsp_spatial` node.
4. `DspLiveUpdateController` prepares replacement pipelines on a control executor, rejects stale generations, defers unsafe latency changes, and retires native pipelines off the render thread. The sink aligns fallback and graph output to the active track generation.
5. `DspPcmPipeline.algorithmicLatencyFrames` combines native graph latency and its output delay. `AndroidMediaSink` snapshots that value for the track generation; `AudioPlaybackClock` subtracts it from played frames before mapping back to the RTP source sample.
6. `DspProfileRepository` uses `AtomicFile`; the current profile schema is v3. Unknown future schemas are read-only. The current profile model stores the legacy width, mono-bass, PEQ, dynamics, limiter, and convolver values.
7. The Compose settings screen is `common/.../DspSettingsActivity.kt`; Japanese and English resources are in `common/src/main/res/values*/dsp_settings_strings.xml`.
8. JNI `nativeCreate` receives validated coefficient/config arrays, prepares the graph before processing, and validates sample buffers at process time. Host DSP tests are in `shared/src/test/native/dsp/test_dsp_engine.c`.

## Baseline validation

- Latest PR-head GitHub Actions run passed: [run 37925267777](https://github.com/choko510/DiPlay/actions/runs/37925267777). It includes host native DSP tests, host ASan/UBSan, shared/common unit tests, both app lint/build variants, JNI R8 mapping, and Android instrumentation/benchmark.
- Local common unit tests: 136 passed.
- Local focused DSP and playback-clock tests: 98 passed.
- Local full shared unit suite: 446 tests, 445 passed and one unrelated IPv6 loopback test failed with Windows `SocketException: Permission denied: connect` in `AirPlayIapTunnelStreamTest`. This is recorded as an environment baseline; this DSP work does not alter that networking test or transport.
- Local Android instrumentation: 16 passed on `Medium_Phone_API_36.1` (API 36, x86_64). The debug native library also built for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- Local native C suite: `test_dsp_engine` passed on the same x86_64 API 36 emulator, built with NDK 27.0.12077973 and the script's C11 warning/error flags. The same-head hosted run above separately verifies the Linux host build and ASan/UBSan.
- The existing emulator benchmark emitted 120 records (4 sample-rate/channel combinations, 10 cases, 3 runs each). The 48 kHz stereo reference is:

  | Case | Native p99 median | Pipeline p99 median | Production p99 median | Worst observed production sample |
  | --- | ---: | ---: | ---: | ---: |
  | Gain | 0.12 ms | 0.50 ms | n/a | n/a |
  | PEQ15 | 0.48 ms | 0.88 ms | n/a | n/a |
  | FullHeavyChain | 1.84 ms | 2.16 ms | 2.34 ms | 2.562 ms |
  | Convolver64k | 2.56 ms | 2.90 ms | 3.12 ms | 3.268 ms |

  These are emulator timings, not TS10 performance claims.
- No TS10 or physical CarPlay device was attached. Device audio quality, track continuity, and long-duration dropout behavior were not measured.

## Implementation constraints carried forward

- Preserve the two-channel output contract, MEDIA-only policy, DSP-master-off PCM16 path, existing profile fields, legacy width/mono-bass output, and current Convolver/clock accounting.
- New modules default OFF. Native allocation and coefficient design remain in graph preparation; process-time code reuses prepared state.
- Calibration is based on imported measurements; a music signal alone is not evidence of a cabin resonance.
- Each phase records its code review, regression result, and commit before the next phase begins.
