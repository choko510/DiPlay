# PR-DSP-12 — DSP pipeline profiling and performance gate

Measured 10 configurations (Gain, PEQ15, PEQ+Compressor+Limiter, Bass+Stereo+Mono Bass, 3-band dynamics, Dynamic EQ, Convolver 4k/16k/64k, and the full heavy chain) at 44.1/48 kHz, Mono/Stereo: 40 cases total. Each case used 64 warmup blocks, 64 allocation-check blocks, and 256 measured 512-frame blocks on the Medium Phone API 36.1 x86_64 emulator. Fixed 20 µs histograms report native processing separately from PCM conversion + JNI + native + PCM16 conversion.

Final run worst cases:

- Light group, Multiband3 at 48 kHz Stereo: native p50/p95/p99/max 360/560/980/1,982 µs; full pipeline 680/1,080/1,720/2,286 µs.
- Heavy group, Convolver64k at 48 kHz Stereo: native 2,080/2,460/2,660/2,771 µs; full pipeline 2,360/2,740/3,000/3,078 µs.
- FullHeavyChain at 48 kHz Stereo: full pipeline 1,720/2,040/2,200/2,210 µs.

All final-run p99 values stayed below the 2.67 ms light and 5.33 ms heavy budgets. Two repeated complete matrices remained within budget; the first run's isolated high p99 samples did not recur. The 64-block steady-state Android allocation check reported zero Kotlin allocations for every case. Source review found no native allocator calls in the process graph; convolver/engine allocation remains in prepare/create and close paths.

No DSP math, chunk-size, FFT-partition, JNI, or NEON changes were justified: measured p99 budgets passed, and no stable regression hotspot remained after repeat runs. Keep the portable scalar path and 128-frame convolver latency.
