# STEP4 feature flags and rollback baseline

## Current main behavior

| Capability | Fixed-main default | Existing fallback / note |
|---|---|---|
| Connection trace | `CarPlayController` receives `connectionTraceEnabled` from `AirPlayPersistence.loadDebugLogsEnabled`; preference key `debug_logs_enabled` defaults to `false` | Trace storage is app-owned and bounded; enabling it does not change NCM framing or retry policy |
| NCM diagnostic profile/logging | `NcmDiagnosticProfile.AUTO`; detailed NCM logging is enabled only for debuggable app builds | 100 ms pre-ready OUT timeout, async persistent bulk IN, status polling if a status endpoint is present, no forced function pair. Profile overrides persist only in debuggable builds. |
| DSP master | `false` when no preference exists | Existing profile data remains intact; `DspStreamPolicy` runs the full graph only for `MEDIA` |
| CarPlay canvas scale | `CarPlayUiScale.DEFAULT` = 100% | Existing supported scale presets are 75%, 85%, 100%, and 115%; no dynamic ViewArea command is added by PR-00 |
| AirPlay ViewArea | Existing single-area advertisement | No new wire payload, capability claim, or post-setup area switch is enabled by PR-00 |
| USB/NCM queue policy | Existing 32 KiB persistent request | No API26/27 compatibility branch or automatic buffer-size fallback is added by PR-00 |

## Planned STEP4 flags (not implemented in PR-00)

Names below are proposals from v3, not new production settings. Resolve collisions with existing preferences/schema before implementation.

| Feature | Initial state | Required rollback |
|---|---|---|
| `wiredPathDiagnosticsV1` | OFF; debug opt-in only | Restore original NCM/TUN/AirPlay event behavior; no extra packet retry |
| `ncmRxFiniteResync` | OFF pending PR-01 tests and device approval | Restore the old receive parser while retaining diagnostic fixtures |
| `hostRetryEpochGuard` | Enable only after EX-02 race and regression tests | Restore prior retry path without changing Controller ownership |
| `reenumerationProbe` | OFF | Keep existing USB mode wait |
| `usbQueueFallback` | OFF; API28+ and queue-false evidence required | Retain existing 32 KiB request behavior |
| `statusEndpointExperiment` | OFF until endpoint A/B and single-owner design | Retain current profile and requestWait ownership |
| `wiredLateOutRecovery` | OFF; TS10 evidence required | Preserve existing 15-second watchdog and current NDP-only retry |
| `splitLocalNoRestart` | OFF until local regression succeeds | Keep legacy reconnect action available |
| `splitDynamicViewArea` | OFF / experimental | Fall back to local scaling or legacy single-area behavior |
| `videoHoldLastFrame` | OFF | Keep existing MediaCodec/Surface path |
| `seatFocus`, `advancedMS`, `cabinCorrection`, `harmonicEngine` | OFF | Keep existing 2-channel DSP graph and master default |

PR-00 adds no application feature flag and no production preference/schema migration.
