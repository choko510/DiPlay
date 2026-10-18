# STEP4 feature flag register

この表は仕様書の初期gateと実装状況を記録する。各PRで現行設定/schemaとの重複を再確認し、OFF時にmainの既存動作へ戻ることを検証する。

| Feature | Main baseline | Planned initial state | Rollback / gate |
|---|---|---|---|
| NCM diagnostic profile | Existing debug-only `NcmDiagnosticProfileStore`; `AUTO` default; status polling is already part of current behavior when endpoint exists | Keep current profile; no change in PR-00 | Restore the existing profile selection |
| `wiredPathDiagnosticsV1` | Not present in frozen main; EX-01 uses existing `connectionTraceEnabled` | Off unless debug connection tracing is enabled; no new preference or schema | Disable recorder; USB/TUN/AirPlay path remains unchanged |
| `ncmRxFiniteResync` | Not present; current parser requires a short pad at exact 512-byte boundary | Enable only after PR-01 CI and hardware gate | Revert receiver framing routine |
| `hostRetryEpochGuard` | Not present as a loss-epoch guard | Enable after EX-02 tests | Revert Host retry policy only |
| `reenumerationProbe` | Not present as the guarded 10-second probe | OFF | Keep existing USB-mode wait |
| `usbQueueFallback` | Current request uses the existing 32 KiB buffer and persistent-request state | OFF; consider only after an API28+ queue-false reproduction | Restore 32 KiB request path |
| `statusEndpointExperiment` | Existing `AUTO` and debug-only `NO_STATUS_POLLING` profile | Keep A/B in debug; no new reaper in PR-00 | Return to current profile behavior |
| `wiredLateOutRecovery` | Not present | OFF; EX-05 requires all six packet-timing evidence conditions | Existing watchdog and retry path |
| `splitLocalNoRestart` | Existing GeckoView split and resize/handoff paths; exact restart behavior is to be revalidated for PR-06 | OFF until LOCAL tests pass | `LEGACY_RECONNECT` behavior |
| `splitDynamicViewArea` | Not present | OFF / experimental only after PR-05 device acceptance | LOCAL scale without wire change |
| `videoHoldLastFrame` | Not present as an opt-in recovery feature | OFF | Existing decoder/Surface behavior |
| `seatFocus`, `advancedMS`, `cabinCorrection`, `harmonicEngine` | No STEP4 nodes in frozen graph | OFF | Existing DSP graph and master-off path |

No feature setting, preference, profile schema, default, or migration is changed by PR-00.
