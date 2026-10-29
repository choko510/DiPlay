# PR #52 Independent Re-audit F-03 Follow-up — 2026-10-30

## Finding and repair

The supplied re-audit examined PR #52 source HEAD 53d15588522f467c17b6f75f039bbe346f5b0746 and identified F-03: after a renderer-owner change cancels an in-progress gain ramp, the effective linear gain remains between the old and target values, while decisions based on the requested dB can treat the cancelled target as the current gain. A subsequent Studio HOT update can therefore skip protective attenuation.

Commit 00ac7453acd325f3523e1e832c01fb2a8375cf68 fixes the state contract:

- dsp_gain stores the effective current linear value and the ramp target linear value; it no longer stores a dB field that could be mistaken for the current physical gain.
- Protective attenuation and Studio refund ordering compare the requested linear target with the effective current linear gain. An update during an active ramp is compatible only when its linear target matches the active ramp target.
- The audio callback performs no dB conversion, allocation, lock, or additional DSP work for these decisions.

## Regression evidence

- test_studio_hot_uses_effective_gain_after_cancelled_ramp covers 44.1/48 kHz, mono/stereo, Macro/Dynamics/Dynamic EQ enabled, a partial -6 dB to -12 dB ramp, cancellation, and subsequent -12/-14/-10/-6 dB targets. Lower effective targets restart protective attenuation before module mutation; a higher target follows the module-settle/refund path.
- test_preamp_hot_update_is_fenced_ramped_and_acknowledged_at_completion now rebinds the renderer during a Preamp ramp, then sends a same-target Studio HOT update. It checks sample continuity and withholds ACK until the restarted gain ramp and module transition complete.
- The new matrix test failed against the audited source with “same or lower effective gain protects against a cancelled ramp's retained target”; it passes after the fix.
- Local scripts/test_dsp_native.sh passes both the Native DSP and Dynamic EQ HOT suites on the fixed source head. The regular Native run is available locally; the local MSYS2 sanitizer runtime lacks libasan and libubsan.

## Hosted verification

Pull-request workflow [run #589](https://github.com/choko510/DiPlay/actions/runs/40926483922) completed successfully against exact source/test HEAD 00ac7453acd325f3523e1e832c01fb2a8375cf68. The changes and full check jobs passed, including Native, ASan/UBSan, Shared/Common, lint, Debug/Benchmark builds, and JNI/R8. API 35 Shared Release passed 23/23 and Common Debug passed 29/29 on the failed-job rerun. Host-baseline and Release verification passed; quick-baseline was skipped because the full check ran. The first API 35 attempt failed the existing FullHeavyChain wall-clock p99 assertion (63.2 ms versus the fixed 21.3 ms limit; thread CPU p99 was 1.64 ms). Both results are preserved; the same-HEAD retry passed but does not explain the original wall-time tail.

Artifacts: [successful instrumentation rerun](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12922445682), [check reports](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12919269803), and [initial instrumentation attempt](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12921157333).

The 170-row evidence ledger is unchanged: PASS 2 / PARTIAL 162 / NOT_RUN 6. No device-only result is inferred from emulator or host tests.

## Remaining acceptance and PR state

The supplied re-audit's hardware-only gates remain unrun: TS10/K706 and iPhone audio/USB/video/touch behavior, MCU volume correlation, A/V, CPU/RSS/thermal measurements, reconnect cycles, and soak. No post-fix independent review of commit 00ac7453 has been performed. PR #52 remains open and unmerged; main and PR #45 remain unchanged.
