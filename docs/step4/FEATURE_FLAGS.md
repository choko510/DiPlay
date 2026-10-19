# STEP4 feature flags and baseline defaults

## Observed fixed-main defaults

| Area | Main default | Baseline consequence |
|---|---|---|
| NCM profile | `NcmDiagnosticProfile.AUTO`; status polling is enabled when a status endpoint exists; 100 ms pre-ready OUT timeout; async bulk IN; no forced interface pair. Profile persistence is debug-build-only. | PR-00 changes no profile or USB behavior. |
| Wired diagnostics | Existing NCM diagnostic detail is gated by debuggable build state. | EX-01's per-attempt `WIRED_PATH_V1` recorder does not exist yet. |
| DSP master | `dsp_profile_selection/enabled` defaults to false; selected profile defaults to `flat`; repository schema is 3. | Effective main runtime starts with DSP disabled; the default profile's `enabled` field is not the global runtime switch. |
| YouTube/Split | Host layout starts `CARPLAY_FULL`; Split is entered by an explicit host action; the saved/default CarPlay fraction is 0.55. | Split geometry/state tests do not prove same-session media continuity. |
| Split tracing | `SplitPerformanceTracer.enabled` defaults to false and is debuggable-only. | No new production telemetry is enabled by PR-00. |

No real user preferences, cookies, identities, or credentials were read to produce this file. These are defaults inferred from fixed-main source, not values exported from a device.

## Candidate STEP4 flags from v3

Names below are planning examples; confirm collisions and owner before introducing any flag. No flag or production setting is added in PR-00.

| Candidate | Initial state | Fallback / gate |
|---|---|---|
| `wiredPathDiagnosticsV1` | OFF or debug opt-in | Original NCM/TUN/RTSP behavior; EX-01 ON/OFF equivalence and PII/overhead checks. |
| `ncmRxFiniteResync` | Not enabled until CI/device acceptance | Existing NTB framing; PR-01 boundary/resync proof. |
| `hostRetryEpochGuard` | Enable only after race regression passes | Existing Host path remains the fallback. |
| `reenumerationProbe` | OFF | Current USB mode wait; EX-04 same-node evidence. |
| `usbQueueFallback` | OFF | Persistent 32 KiB request; only consider API28+ reproducible queue failure. |
| `statusEndpointExperiment` | OFF as a new experiment | Current `AUTO` profile; single-reaper ownership required before demux changes. |
| `wiredLateOutRecovery` | OFF | Existing watchdog/retry; EX-05 needs explicit TS10/iPhone evidence. |
| `splitLocalNoRestart` | OFF until local regression acceptance | `LEGACY_RECONNECT` fallback. |
| `splitDynamicViewArea` | OFF / experiment-only | LOCAL or legacy; enable only after iPhone ACK, size change, and same-session evidence. |
| `videoHoldLastFrame` | OFF | Existing MediaCodec/Surface path. |
| `seatFocus`, `advancedMS`, `cabinCorrection`, `harmonicEngine` | OFF | Existing two-channel DSP graph and master-off PCM path. |
