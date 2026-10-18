# STEP4 feature flag register

この表は仕様書の初期gateと実装状況を記録する。各PRで現行設定/schemaとの重複を再確認し、OFF時にmainの既存動作へ戻ることを検証する。

| Feature | Main baseline | Planned initial state | Rollback / gate |
|---|---|---|---|
| NCM diagnostic profile | Existing debug-only `NcmDiagnosticProfileStore`; `AUTO` default; status polling is already part of current behavior when endpoint exists | Keep current profile; no change in PR-00 | Restore the existing profile selection |
| `wiredPathDiagnosticsV1` | Not present in frozen main; EX-01 uses existing `connectionTraceEnabled` | Off unless debug connection tracing is enabled; no new preference or schema | Disable recorder; USB/TUN/AirPlay path remains unchanged |
| `ncmRxFiniteResync` | Not present; current parser requires a short pad at exact 512-byte boundary | Enable only after PR-01 CI and hardware gate | Revert receiver framing routine |
| `hostRetryEpochGuard` | Not present as a loss-epoch guard | Enable after EX-02 tests | Revert Host retry policy only |
| `reenumerationProbe` | Not present; EX-04 conditional not reproduced on TS10/K706+iPhone | NO-GO for this iteration; keep OFF | Keep existing USB-mode wait; reconsider only with same-node evidence |
| `usbQueueFallback` | Current request uses the existing 32 KiB buffer and persistent-request state | `NO_GO_PENDING_DEVICE_REPRO`; keep OFF until API28+ device-present `queue()==false` is observed | Restore 32 KiB request path |
| `firstOutTimeoutPolicy` | Existing `AUTO` uses 100 ms before link ready; NDP NS/NA retries are bounded and TCP is not retried | `NO_GO_PENDING_CAUSAL_LOG`; keep current profile defaults | Restore current pre-ready timeout and NDP-only retry |
| `statusEndpointExperiment` | Existing `AUTO` and debug-only `NO_STATUS_POLLING` profile | Keep A/B in debug; no new reaper in PR-00 | Return to current profile behavior |
| `wiredLateOutRecovery` | Not present; EX-05 causal log gate unmet | `NO_GO_PENDING_EX01_DEVICE_EVIDENCE`; require all six packet-timing conditions and a virtual-time reachability pass | Existing watchdog and retry path |
| `splitLocalNoRestart` | Existing GeckoView split and resize/handoff paths; exact restart behavior is to be revalidated for PR-06 | OFF until LOCAL tests pass | `LEGACY_RECONNECT` behavior |
| `splitDynamicViewArea` | Not present | OFF / experimental only after PR-05 device acceptance | LOCAL scale without wire change |
| `videoHoldLastFrame` | Not present as an opt-in recovery feature | OFF | Existing decoder/Surface behavior |
| `seatFocus`, `advancedMS`, `cabinCorrection`, `harmonicEngine` | No STEP4 nodes in frozen graph | OFF | Existing DSP graph and master-off path |

No feature setting, preference, profile schema, default, or migration is changed by PR-00.
