# B11 — Conditional Changes: NO_GO_THIS_RUN

## Decision

No conditional production feature is enabled or added in B11. The required TS10/K706 + iPhone causal traces and controlled device A/B results are unavailable. B08 provides host-side observation and per-thread overhead measurements, but those do not establish that any retry, transport fallback, route change, or codec override is needed on the supported device.

For EX-05, the production decision is `NO_GO_FOR_PRODUCTION_RETRY`: additional `0x4301` StartSession sends remain **zero**. Existing NDP retry and 8/15-second watchdog behavior are unchanged. Parse uncertainty remains `UNKNOWN`; it does not authorize a retry.

## Candidate decisions

| Candidate | Status | Existing default retained | Evidence required before a separate flagged implementation |
|---|---|---|---|
| EX-05 additional StartSession `0x4301` retry | `NO_GO_FOR_PRODUCTION_RETRY` | No extra send; current watchdog unchanged | Same-attempt iAP2 owner, first full OUT delayed after `0x4301`, a valid classified SYN/SYN-ACK failure chain, no active session, plus T-01–T-05 stale timer/owner/attempt tests and target-device A/B. Unknown parse cases must send nothing. |
| API 28+ USB read queue-false fallback | `NO_GO_THIS_RUN` | Existing request sizing/ownership | Reproduce `queue()==false` on a supported device while USB remains present; preserve an already queued request and show recovery. |
| Same USB node delayed re-enumeration | `NO_GO_THIS_RUN` | Existing detach/attach recovery | Device-present stall with no detach/attach, plus USB identity and owner evidence. |
| Disable NCM status endpoint polling | `NO_GO_THIS_RUN` | `NCM AUTO` | Same-device `AUTO` vs `NO_STATUS_POLLING` A/B with first-frame, success/timeout, CPU/GC evidence. |
| Wired IPv4 or interface scope | `NO_GO_THIS_RUN` | Existing IPv6 path | Captured valid IPv4 route/peer schema and supported-device traffic showing the current path is insufficient. |
| Dynamic ViewArea | `NO_GO_THIS_RUN` | Legacy single area; opt-in local scaling only | iPhone accepts the same-session ViewArea request and frame geometry changes without reconnect; full coordinate and 20-cycle Split evidence. |
| Vendor MediaCodec options | `NO_GO_THIS_RUN` | Existing capability-gated generic behavior | Target codec capability, first-frame/rebuffer improvement, Surface safety, and rollback evidence. |
| Music PI clock trim | `NO_GO_THIS_RUN` | Existing AudioTrack rate | Sustained drift, pitch, A/V and DSP comparison on the supported audio path. |
| AudioManager mode ownership | `NO_GO_THIS_RUN` | Existing route behavior | Reproduced route failure and evidence that DiPlay owns the conflicting mode; verify call completion/restoration. |
| API 28 TCP keepalive change | `NO_GO_THIS_RUN` | Existing socket behavior | Reproduced idle/socket death and a public-API ownership-safe fix. |
| Bluetooth/P2P/wireless changes | `NO_GO_THIS_RUN` | Existing wireless owner and permissions | Supported TS10/K706 reproduction with the same configuration and permission path. |
| `ROTATION_SQUARE_CANVAS` | `NO_GO_THIS_RUN` | Feature absent/off | Target rotation screenshot, safe-area bounds, and matching HID coordinates through a remote CarPlay rotation. |

The 111-row semantic audit is recorded separately in `EX08_DELTA111_REVIEW.md`; no candidate met the fixed-main failing-test plus safe narrow-adaptation threshold for B11. Existing `NEED_EVIDENCE` records remain open with their own re-entry evidence.

## Required conditions for a future implementation

Any adopted candidate must use a separate flag with the old behavior as the default. Before enabling it, add OFF-parity and ON-reproduction tests, current/old-owner and permission tests, bounded retry/rollback behavior, and a same-device A/B record. Update `UPSTREAM_DECISION_REGISTER.md` with the evidence. If the precondition is still unknown, keep the feature off.

## B11 completion status

`NO_GO_THIS_RUN` is the intended outcome when prerequisites are not proven; it is not an implementation failure. No production retry, timer, codec override, route change, or transport fallback was introduced.
