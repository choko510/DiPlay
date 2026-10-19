# DSP-05 Final Integration Audit

The final Float32 processing order is:

```text
gain → Seat alignment → PEQ + approved Cabin cuts → Bass shelf → compressor → multiband → Dynamic EQ → M/S → Harmonic Engine → Convolver → limiter → Float32 output
```

The existing `DspPcmPipeline` performs the only final PCM16 conversion. Cabin correction adds approved filters to the existing PEQ coefficient list and is subject to the same 15-band limit. Advanced M/S replaces legacy Stereo Width/Mono Bass while active. Harmonic processing is after M/S and before Convolver; its selected FIR group delay, Seat alignment delay, and Convolver partition latency are summed by the native engine. Active stateful node changes defer until a new track generation.

## Integration checks

- Exercise all 16 on/off combinations of Seat Focus, Advanced M/S, Cabin Correction, and Harmonic Engine for 44.1/48 kHz mono/stereo output. Compare 1,024 PCM16 frames byte-for-byte between one block and variable frame boundaries.
- Measure the Harmonic Engine folded third-harmonic component at 44.1 and 48 kHz in mono and stereo. Reject malformed Harmonic JNI arrays and test exact positive/negative half-LSB PCM16 rounding.
- Run the 56-case per-module/full-chain p99 and steady-state allocation matrix for 44.1/48 kHz mono/stereo. Include all four new modules with PEQ, Bass, dynamics, Convolver, Auto Headroom, and the safety limiter in `FullHeavyChain`.
- Keep the disabled path golden, migration chain v0–v7, future-schema preservation, and the existing third-party notices. The new Harmonic Engine uses no additional third-party library; the existing KissFFT notice remains in force for Convolver.

On the API 36.1 x86_64 emulator, all 56 benchmark configurations passed with zero steady-state Kotlin allocations. The maximum median-run production p99 was 3.12 ms for `FullHeavyChain` at 44.1 kHz stereo; AdvancedMS, Cabin3Notches, and HarmonicEngine maxima were 0.80, 0.64, and 1.48 ms. The 64 PCM16 whole/variable-block integration cases and four alias-format checks also passed. The largest measured folded-alias/fundamental ratio was 4.1×10⁻⁵ at 44.1 kHz in mono and stereo; the 48 kHz ratios were below 8.6×10⁻⁶. These results are emulator-only. Target CPU/memory, CarPlay A/V sync, 30–60-minute soak, in-car THD/listening remain `NOT_RUN`; host ASan/UBSan is `BLOCKED_ENV` because the local MSYS2 GCC lacks its runtimes.
