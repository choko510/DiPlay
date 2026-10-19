# DSP-02 Advanced Mid/Side

DSP-02 extends the existing Stereo Width/Mono Bass node as one M/S stage. Advanced M/S defaults off, so profiles retain the established path until the user enables it. When enabled for stereo, it replaces those legacy operations; width is not applied twice.

## Behavior

- Convert stereo to `Mid = (L + R) / 2` and `Side = (L - R) / 2`, then split each with the same cascaded Linkwitz-Riley low/high pair. Recombine after processing to preserve complementary crossover magnitude and keep Mid/Side phase aligned.
- Low and high bands each have independent Mid gain, Side gain, and Side width. Each gain is bounded to ±6 dB and width to 0–2. Center Focus is an additional bounded Mid gain. Mid contains all center-correlated material, not vocals alone.
- An optional compressor processes the recombined Side signal only. Auto Headroom includes the largest band gain, width, Side compressor makeup, and a 3 dB allowance when the Mid and Side transfer gains differ. The sample limiter remains last.
- Mono streams bypass Advanced M/S. Stereo processing adds no algorithmic latency. Crossover filters and dynamics state are prepared with the native graph; the sample loop allocates no memory and takes no locks.
- On first enable from an untouched Advanced M/S profile, Mono Bass cutoff seeds the crossover. If Mono Bass is off, the existing Stereo Width is copied to both band widths; if it is on, low-band width starts at 0 and the existing width carries to the high band. Profile schema v5 stores Advanced M/S; v0–v4 data migrates with the new feature disabled.

## Verification

| Gate | Result | Evidence |
|---|---|---|
| Shared/common unit tests | `PASS_LOCAL`: 469/138 | Full tasks forced to rerun; zero failures/errors/skips |
| Native C and ASan/UBSan | `PASS_EMULATOR` | NDK Clang x86_64 build and API 36.1 AVD execution; LeakSanitizer disabled. Covers complementary magnitude, matched Mid/Side phase/group delay, low/high width, Mid gain, Side compression, mono bypass, and zero latency. |
| Android instrumentation | `PASS_EMULATOR`: 17/17 | API 36.1 x86_64 AVD; Advanced M/S JNI tone/width check and benchmark matrix passed on the isolated run. |
| Mobile/automotive lint and builds | `PASS_LOCAL` | Debug and benchmark variants assembled; both lint tasks passed. |
| JNI mappings and public tree | `PASS_LOCAL` | Both benchmark R8 mappings verified; credential scan passed. |
| Target-car listening and acoustic validation | `NOT_RUN` | TS10/K706/iPhone hardware and measurements were not available. |

### API 36.1 emulator performance

Each entry is the median of three 256-block runs at 128 frames per block; `max` is the largest single-block value across all three runs. Native and complete Kotlin/native pipeline metrics are separate. Advanced M/S adds no graph latency, so its pipeline timing has no latency-preserving bypass.

| Rate | Native p50 / p95 / p99 / max (µs) | Pipeline p50 / p95 / p99 / max (µs) |
|---|---:|---:|
| 44.1 kHz stereo | 200 / 240 / 280 / 1,244 | 480 / 600 / 660 / 1,625 |
| 48 kHz stereo | 180 / 220 / 280 / 361 | 460 / 560 / 620 / 732 |

The first combined instrumentation/build run exceeded the existing FullHeavyChain p99 gate at 48 kHz mono and 44.1 kHz stereo while build work was active. Running instrumentation alone passed 17/17, including those cases. Emulator timings are not TS10 or in-car performance evidence.
