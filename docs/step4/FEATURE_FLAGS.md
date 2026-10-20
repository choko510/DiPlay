# PR-00 — Feature Flags and Rollback Baseline

PR-00 adds no production flags or default changes. Names below are v3 examples; they do not imply an existing main flag.

| Feature | Fixed-main state | Planned initial gate | Rollback boundary |
|---|---|---|---|
| Wired attempt diagnostics (`wiredPathDiagnosticsV1`) | Main has `ConnectionTraceStore` and NCM counters, but no unified TCP/TUN/SYN-ACK recorder | Debug opt-in / release OFF; EX-01 must prove PII safety, same behavior, and bounded cost | Disable recorder; preserve NCM/TUN behavior |
| NCM finite resynchronization (`ncmRxFiniteResync`) | Current receiver does not scan past corrupt NTBs | Enable only after PR-01 bounded-resync and next-NTH tests pass | Revert only the framing routine |
| Host retry generation guard (`hostRetryEpochGuard`) | Main has Controller/Host recovery and data-path generation helpers; stale Host retry remains an EX-02 candidate | Enable after recovered-session/second-loss tests pass | Revert the Host retry guard without changing USB/NCM ownership |
| USB re-enumeration probe (`reenumerationProbe`) | No delayed 10-second probe in PR-00 | OFF; EX-04 requires same-device, permission, and generation evidence | Remove only the delayed probe |
| API 28+ queue fallback (`usbQueueFallback`) | Main has a 32 KiB persistent request and no size ladder | OFF; PR-02 conditional on device-present `queue(false)` | Revert only the fallback |
| Status endpoint mode | `NcmDiagnosticProfile.AUTO` polls an available endpoint; `NO_STATUS_POLLING` exists as a comparison profile | Preserve current default and one completion owner | Restore current profile and owner |
| Late bulk-OUT recovery (`wiredLateOutRecovery`) | No unconditional second `StartSession` | OFF until EX-05 causal trace and bounded A/B pass | Revert only the recovery branch |
| Local Split | Main already has 55:45 in-app layout state; no PR-00 flag | Preserve current UI; PR-06 LOCAL must prove no session/AudioTrack restart | Restore existing full layout without changing remote ViewArea |
| Dynamic remote ViewArea (`splitDynamicViewArea`) | Main retains legacy single-area declaration | OFF / `NO_GO_THIS_RUN` until iPhone accepts the exact command and frame dimensions are observed | Fall back to local Split or legacy layout |
| Video hold-last-frame (`videoHoldLastFrame`) | Current decoder/Surface path remains | OFF until codec, Surface, resume, and A/V tests pass | Restore current decoder behavior |
| Seat Focus / Advanced M/S / Cabin / Harmonic DSP | Current two-channel DSP remains; PR-00 adds no node | New nodes default OFF; Cabin requires valid user-approved measurements | Disable new node and retain legacy DSP-off PCM/latency behavior |

The existing DSP master, `NcmDiagnosticProfile`, and local Split state are current-main configuration/state, not new PR-00 rollout flags. Check settings/profile schema before adding persistence for any later gate.
