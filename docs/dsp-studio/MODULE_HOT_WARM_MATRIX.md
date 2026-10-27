# DSP field update matrix

This matrix reflects `DspUpdatePlanner`, renderer eligibility checks, and Native capabilities at source head `fe083f4f524fa6ee1038860343e4f100aee8dad6`. `HOT`, `LOCAL_WARM`, `WARM`, and `DEFERRED` describe runtime plans, not UI optimism. Any update that fails the stated conditions must retain the active graph and report its deferred reason.

| Runtime field | Plan | Conditions and limits | Direct evidence / remaining gate |
|---|---|---|---|
| `MACRO_TONE` | HOT | Active MEDIA pipeline, current format/owner, Native HOT rollout enabled; native payload validation and generation ACK required. | Native/renderer macro tests pass; target audio and performance remain NOT_RUN. |
| `SMART_LOUDNESS` | HOT | Same MEDIA/owner/format conditions; route reading must be trusted and match calibration, otherwise AUTO is unavailable. Conservative headroom includes the high-pass transient bound. | Resolver, volume-source, Native, and headroom tests pass; TS10/K706 route calibration remains NOT_RUN. |
| `DYNAMIC_BASS` | HOT | Same MEDIA/owner/format conditions; bounded gain and shared headroom budget apply. Detector stays on its pre-boost input tap. | Native detector, resolver, and renderer tests pass; listening/THD/p99 remains NOT_RUN. |
| `PEQ` | LOCAL_WARM | Opt-in Local Warm flag, committed MEDIA track, active matching PCM pipeline and renderer owner, unchanged latency/headroom, sufficient bounded pre-EQ history. New EQ state warms off the render callback and crossfades at equal latency. | `peqLocalWarmUpdatesOnlyThePeqStageWhileCompressorAndConvolverStayActive`, latest-wins/catch-up Native tests, API 35 tests pass. Full rate/long-run evidence remains partial. |
| Static `BASS` shelf | LOCAL_WARM | Same gates as PEQ. Shelf uses one internal graph filter beyond the unchanged 15-band user PEQ/Cabin capacity; it shares the EQ history and crossfade. If Auto Headroom changes, the update defers. | `bassShelfLocalWarmUpdatesTheEqualizationStageWithoutRebuildingStatefulStages`, `nativeLocalWarmAcceptsFifteenPeqBandsPlusStaticBassShelf`, and API 35 tests pass. Acoustic/performance evidence remains NOT_RUN. |
| `CABIN_CORRECTION` | DEFERRED | Local warm-up is not implemented. Do not claim it shares PEQ/Bass warm status even though it uses the same coefficient chain. | Planner asserts deferral with an active track; added Cabin warm-up is outside this specification. |
| `PREAMP` only | HOT | Version-1 Preamp payload kind in the shared bounded Native HOT mailbox; current MEDIA owner/editor/renderer/PCM-format tuple required. Auto Headroom must be enabled and prepared gain must retain at least 6 dB of reserve (HOT targets above -6 dB are rejected). Native applies a 20 ms sample ramp and ACKs only after it finishes; owner/format rebind cancels an in-flight ramp at the last-good value. Cross-lane generations are monotonic with Macro/Loudness/Dynamic Bass HOT and LOCAL_WARM. | Native ramp/fence/final-ACK and Kotlin bridge/latest-generation tests pass; API/vehicle audio, p99 and acoustic evidence remain NOT_RUN. |
| `CONVOLVER` / IR topology | DEFERRED when active wet IR topology changes | Rebuilding or changing algorithmic latency without history can break continuity. Keep the current graph until a new track unless an evidenced safe path applies. | Planner/renderer deferral tests pass; IR live warm remains unsupported. |
| `MULTIBAND`, `DYNAMIC_EQ`, `SEAT_ALIGNMENT`, `ADVANCED_MS`, `HARMONIC` | WARM or DEFERRED | Whole-pipeline WARM is permitted only where latency and required state continuity can be preserved. If required history/phase/latency cannot be proven, keep the active graph and defer. | Field planner tests cover supported deferrals; each field's full one-to-one acceptance evidence remains incomplete. |
| `MASTER_ENABLED`, `AUTO_HEADROOM`, `COMPRESSOR`, `LIMITER`, `WIDTH`, `MONO_BASS` | WARM or DEFERRED | Not in the Native HOT or Local Warm allowlist. Use a new pipeline only when latency and state-continuity requirements permit; otherwise defer. | Software policy tests pass; some field-specific effect/continuity assertions remain open. |

## Global rejection conditions

- Non-MEDIA roles never receive the full DSP update path.
- Track latency mismatch, PCM format change, renderer-owner change, stale generation, or invalid Native payload rejects the candidate.
- The Local Warm rollout flag defaults off. With it off, committed-track PEQ/Bass changes defer with a reason.
- Audio callbacks do not allocate or wait for preparation. Preparation, history catch-up, and resource retirement use control/reaper paths; the callback applies only bounded work.
- Final software checks passed at `fe083f4f`; current-head hosted CI is pending push. Target-device audio, A/V, p99, and acoustic claims remain `NOT_RUN`.
