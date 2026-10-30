# DSP Full-Heavy Benchmark Evidence — 2026-10-28

This records software benchmark evidence for `T-PERF-01`. It compares the earlier integration source with the current implementation on the same API 35 x86_64 Pixel 7 Pro emulator profile. It is emulator evidence, not TS10/K706/iPhone performance acceptance.

## Source and execution

| Role | Source SHA | Hosted run | Instrumentation artifact |
|---|---|---|---|
| Earlier baseline | `1e4a8962cedcc90fbf505c5f01346408e2842b77` | [#506](https://github.com/choko510/DiPlay/actions/runs/40497504756) | [artifact 12731817271](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12731817271) |
| Current candidate | `7aa7ba41a1c4f3b97c44d0f305a72276219e7847` | [#575](https://github.com/choko510/DiPlay/actions/runs/40686737568) | [artifact 12815317901](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12815317901) |

The benchmark class is byte-for-byte unchanged between the two source SHAs. Both artifacts contain `shared/build/outputs/dsp-benchmark/logcat.txt` from `DspPipelineBenchmarkInstrumentedTest`. The test uses 512-frame chunks, 64 warm-up blocks, 256 measured blocks per run and three measured runs at audio-thread priority. `FullHeavyChain` enables the 15-band processing chain, including PEQ, Bass, Compressor, Limiter, Mono Bass, Seat, Advanced M/S, Cabin, Harmonic, Convolver, Multiband, Dynamic EQ and Smart Loudness.

## FullHeavyChain median measurements

Values are medians of the three run-level percentile summaries. `Production p50/p95/p99` includes the latency-preserving bypass where applicable. Times are microseconds.

| Sample rate | Channels | Earlier thread CPU p99 | Earlier production p50/p95/p99 | Current thread CPU p99 | Current production p50/p95/p99 |
|---:|---:|---:|---:|---:|---:|
| 44,100 Hz | Mono | 580 | 480 / 580 / 680 | 820 | 540 / 940 / 1,380 |
| 44,100 Hz | Stereo | 1,380 | 860 / 1,060 / 1,400 | 1,280 | 980 / 2,360 / 4,320 |
| 48,000 Hz | Mono | 600 | 480 / 580 / 680 | 880 | 520 / 900 / 2,180 |
| 48,000 Hz | Stereo | 1,020 | 860 / 1,000 / 1,120 | 1,600 | 1,080 / 3,400 / 6,320 |

The configured test gate is twice the chunk duration: 23,220 µs at 44.1 kHz and 21,332 µs at 48 kHz. All measured median p99 values remained below the gate. The current run's wall-time tails were higher than the earlier run; run #565 at source `e4f6c379` measured median production p99 of 1,380 µs at 44.1 kHz stereo and 1,160 µs at 48 kHz stereo. These emulator runs show tail variability and do not establish target-device performance.

The test also asserts zero Java/Kotlin thread allocations in the steady-state pipeline call. It does not measure native allocator activity or RSS. The current result remains `PARTIAL` pending a dedicated target-device baseline and physical CPU/RSS/soak measurements.
