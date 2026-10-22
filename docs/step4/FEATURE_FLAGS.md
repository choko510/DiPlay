# STEP4 Feature and Rollback Flags

PR-00 records existing controls and planned defaults. EX-01 adds opt-in diagnostics only and does not change production transport behavior.

| Area | Current main behavior | STEP4 default / gate |
|---|---|---|
| Connection trace | `debug_logs_enabled` persists through `AirPlayPersistence`, default false; the host passes it to `CarPlayController` | EX-01 remains opt-in and observational; it cannot change transport retries or deadlines |
| Wired path V1 | No per-attempt wired packet provenance recorder exists in main | Uses the existing opt-in debug-log setting; OFF by default. Emits only fixed counters/timestamps, with no payloads or raw network identifiers. CPU `<1%` and memory `≤64 KiB` are targets and remain `NOT_RUN` on TS10 |
| NCM diagnostic profile | `AUTO` is the default for missing/invalid preferences and all non-debuggable builds. Debug builds can save `NO_STATUS_POLLING`, timeout comparators, synchronous IN, or a forced function pair | Keep `AUTO`; forced pairs and sync IN are debug-only comparators, not release fallbacks |
| DSP master / Seat Focus / Advanced M/S / Cabin Correction / Harmonic Engine | `DspProfileRuntime` reads `dsp_enabled` with default false; each new module defaults off | Keep the global master disabled until user action; Seat Focus never infers a near side from RHD, Advanced M/S replaces legacy Width/Mono Bass only while enabled, Cabin Correction uses reviewed external or manual PEQ cuts, and Harmonic Engine is opt-in with bounded level-matched effects |
| Local Split | User selects `HostLayoutMode`; default is full CarPlay and the local CarPlay fraction is 55%, clamped to 50–60% | PR-06 LOCAL must remain local and must not depend on a remote ViewArea command |
| Existing `/info` displays | `AirPlayConfig.cluster` is optional; each configured display has one view area | Do not treat optional type-111 cluster as dynamic MAIN type-110 ViewArea support |
| PR-02 queue fallback | No production size fallback exists in main | Absent/off until a device-present API28+ `queue()==false` reproduction |
| PR-03 first-OUT changes | Existing NDP-only retry and debug timeout profiles | No retry/timeout default change without EX-01 evidence |
| EX-04 delayed re-enumeration | No 10 s same-node probe in main | Off until a same-node stall is reproduced |
| EX-05 second StartSession | No additional StartSession retry in main | Off until the exact TS10 packet chronology is observed |
| EX-07 IPv4 routing | NCM/TUN route is IPv6 | Keep IPv6-only unless a valid IPv4 path is observed and tested |
| PR-05 ViewArea declaration | `AirPlayDisplayConfig.viewAreas` defaults to `null`; the app does not populate it | Preserve the legacy one-area plist by default. Two-area `/info` output is explicit-config-only; unknown switch commands stay off until phone acknowledgement and actual same-session frame-size change |
| PR-07 codec tuning | Fixed main's tuned decoder attempt already requests `MediaFormat.KEY_LOW_LATENCY` on Android R+ only when the selected codec reports that capability | Preserve this generic capability-gated baseline. STEP4 adds no vendor/per-device overrides; TS10/K706 behavior and performance remain `NOT_RUN` |
| PR-08 release | Main has Debug/Benchmark build variants, not STEP4 rollout flags | Release/experimental flags and rollback test are PR-08 work |

Every behavior-changing flag must identify its owner, safe default, activation evidence, persistence scope, fallback, and rollback test before it can be enabled.
