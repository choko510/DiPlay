# DSP-05 Final Integration Audit

The final Float32 processing order is:

```text
gain → Seat alignment → PEQ + approved Cabin cuts → Bass shelf → compressor → multiband → Dynamic EQ → M/S → Harmonic Engine → Convolver → limiter → Float32 output
```

The existing `DspPcmPipeline` performs the only final PCM16 conversion. Cabin correction adds approved filters to the existing PEQ coefficient list and is subject to the same 15-band limit. Advanced M/S replaces legacy Stereo Width/Mono Bass while active; first-enable migration seeds controls from the legacy values but does not promise the same frequency response. Harmonic processing is after M/S and before Convolver; the ADAA-shaped component is added to the latency-matched dry signal. Native common latency sums Harmonic FIR group delay and Convolver partition latency; Seat interchannel-relative delay is reported separately and is not added to the common playback-clock latency. Active stateful or latency-changing node updates defer until a new track generation. The DSP editor distinguishes a configured profile from the MEDIA renderer state (`requested`, `preparing`, `prepared`, `deferred`, `active`, or `failed`); a deferred graph remains prepared and is adopted before the next track generation emits audio.

DSP OFF is exact on a new track with no active common-latency graph. Switching the master OFF mid-track preserves that track's established common latency through a float/dither bypass; it is not PCM16 bit-exact until the next track generation resets the common latency to zero.

The first-enable Advanced M/S conversion is a starting-point mapping from legacy Width/Mono Bass, not a frequency-response-preserving migration. Its 4th-order LR4 crossover differs from the legacy 2nd-order Mono Bass filter; the UI states this explicitly.

## Integration checks

- Exercise all 16 on/off combinations of Seat Focus, Advanced M/S, Cabin Correction, and Harmonic Engine for 44.1/48 kHz mono/stereo output. Compare 1,024 PCM16 frames byte-for-byte between one block and variable frame boundaries.
- Measure the Harmonic Engine folded third-harmonic component at 44.1 and 48 kHz in mono and stereo. Reject malformed Harmonic JNI arrays and test exact positive/negative half-LSB PCM16 rounding.
- For the native Harmonic Exciter, measure original fundamental gain across 44.1/48 kHz, EVEN/ODD/MIXED, 9/17/33 taps, 1/8/12/15 kHz tones, and 0.001/0.05/0.45/0.9 peaks with internal level matching disabled; low-level gain must remain within 5% of dry. At 0.9 peak, scan through 20 kHz below Nyquist and require folded second/third harmonics below -26 dBc. The exciter branch uses a sample-rate-relative upper-band guard; the delayed dry branch preserves the input fundamental.
- Compute Auto Headroom from the dry gain after internal level matching plus the maximum additive harmonic branches, so the same internal gain reduction is not counted twice.
- Compare the legacy Mono Bass HPF and the LR4 high-band side response at the crossover and above; keep `fromLegacy` values labeled as a starting point rather than exact equivalence.
- Distinguish initial master-OFF bit-exact output from live-OFF latency-preserving output and verify the next track generation returns to exact PCM16.
- Exercise Seat-only ON → master OFF during a committed track. The crossfade target must be dry/raw when common latency is zero, so the last Seat-only fallback block does not leak a relative delay into the OFF generation.
- Run the 56-case per-module/full-chain p99 and steady-state allocation matrix for 44.1/48 kHz mono/stereo. Include all four new modules with PEQ, Bass, dynamics, Convolver, Auto Headroom, and the safety limiter in `FullHeavyChain`.
- Keep the disabled path golden, migration chain v0–v7, future-schema preservation, and the existing third-party notices. The new Harmonic Engine uses no additional third-party library; the existing KissFFT notice remains in force for Convolver.

On the API 36.1 x86_64 emulator, all 56 benchmark configurations passed with zero steady-state Kotlin allocations. The maximum median-run production p99 was 3.12 ms for `FullHeavyChain` at 44.1 kHz stereo; AdvancedMS, Cabin3Notches, and HarmonicEngine maxima were 0.80, 0.64, and 1.48 ms. The 64 PCM16 whole/variable-block integration cases and four alias-format checks also passed. The largest measured folded-alias/fundamental ratio was 4.1×10⁻⁵ at 44.1 kHz in mono and stereo; the 48 kHz ratios were below 8.6×10⁻⁶. These results are emulator-only. Target CPU/memory, CarPlay A/V sync, 30–60-minute soak, in-car THD/listening remain `NOT_RUN`; host ASan/UBSan is `BLOCKED_ENV` because the local MSYS2 GCC lacks its runtimes.
