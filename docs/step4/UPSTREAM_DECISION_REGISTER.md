# STEP4 upstream decision register

基準はfork main `95ba398f95ee104527812cd2b095977d71c51f98`。比較対象upstream mainは計画書固定値 `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`。このregisterは添付監査の判断を実装前に追跡する初期台帳であり、1,289コミットまたは全ファイルの意味論的レビュー完了を主張しない。EX-08でcommit/file/test/rollback単位へ展開する。

| Decision ID | Area / source path | Disposition | Reason and evidence status | Gate |
|---|---|---|---|---|
| UP-NTB-01 | upstream NCM framing; fork `NcmUsbBridge.kt`, `Ntb16Codec.kt` | `ADAPT` | Optional short pad and bounded resync address a source-confirmed receive wait; preserve fork request queue, typed errors and TX format. Not yet hardware-proven. | PR-01 |
| UP-HOST-01 | upstream reconnect behavior; fork `CarPlayHostActivity.kt`, `CarPlayController.kt` | `ADAPT` | A pending Host retry can race recovery in the frozen code; require loss/owner epoch test and second-loss behavior. | EX-02 |
| UP-REENUM-01 | upstream same-node 10s USB re-enumeration probe (`ab324016`); fork `CarPlayController.beginReenumeration()` | `NO_GO_PENDING_EVIDENCE` | No TS10/K706+iPhone same-node mode-transition stall was reproduced. Keep the existing event-driven wait; do not add a timer or permission/mode request. Revisit only with same-node, generation, and permission evidence. | EX-04 |
| UP-USB-01 | Android 8 USB queue size | `NOT_APPLICABLE` | Current `minSdk=28`; do not lower SDK or add an unreachable API26/27 branch. API28+ queue-false fallback remains conditional. | PR-02 |
| UP-USB-02 | API28+ `UsbRequest.queue()` fallback | `NO_GO_PENDING_DEVICE_REPRO` | No API28+ device-present `UsbRequest.queue()==false` reproduction exists. Keep the 32 KiB persistent request and do not add size fallback; emulator execution does not exercise the iPhone USB endpoint. Revisit only with device-present failure evidence. | PR-02 |
| UP-OUT-POLICY-01 | NCM pre-ready OUT timeout policy; fork `NcmDiagnosticProfile`, `NcmStartupNdpRetry` | `NO_GO_PENDING_CAUSAL_LOG` | No TS10/K706+iPhone per-attempt evidence correlates StartSession, first bulk OUT, SYN/SYN-ACK, NDP, and NotReady/Sent timing. Keep AUTO's 100 ms pre-ready OUT and existing NDP-only retry. Do not introduce a 20 ms policy or increase timeout based on external-device reports. | PR-03 |
| UP-STATUS-01 | CDC status endpoint / `requestWait()` | `NEED_EVIDENCE` | Completion is shared across endpoints on one connection; a separate requestWait thread would violate single ownership. Use AUTO/NO_STATUS A/B first. | EX-06 |
| UP-LATE-OUT-01 | delayed NCM OUT recovery / iAP2 StartSession | `NO_GO_PENDING_EX01_DEVICE_EVIDENCE` | None of the six EX-01 causal conditions has been captured on TS10/K706+iPhone. Do not send an extra StartSession or guessed `0x4302` EndSession; require the EX-05 virtual-time reachability test after device evidence. | PR-03, EX-05 |
| UP-IP-01 | IPv4, IPv6 scope, peer MAC, accept generation | `NEED_EVIDENCE` | Source facts do not establish the failed packet family or device cause. Make only the sub-fix reproduced by EX-01 evidence. | EX-07 |
| UP-AUDIO-01 | upstream software Opus/Concentus handling | `REJECT_UNSAFE` for direct transplant | Frozen fork uses Android MediaCodec Opus paths; software implementation exceptions do not transfer directly. Reassess only with source and codec evidence. | PR-04 / AUDIO-EX |
| UP-SPLIT-01 | BYD DiLink HUD/cluster/rhythm/OS split | `SKIP_OEM` | OEM-specific deleted dependencies must remain absent. | PR-08 |
| UP-SPLIT-02 | GeckoView in-app Split View / Google profile isolation | `FORK_SUPERIOR` / `PRESERVE` | Fork-owned split/profile behavior is distinct from OEM multi-window support and must not be overwritten. | PR-06 |
| UP-VIEWAREA-01 | AirPlay multi-ViewArea wire model | `ADAPT` + hardware gate | Two-area model may be represented, but send success alone is not acceptance; production dynamic mode stays OFF until iPhone response and frame-size change are observed. | PR-05 / PR-06 DYNAMIC |
| UP-WIRELESS-01 | wireless listener / first TCP / dual-stack candidates | `NEED_EVIDENCE` | Not part of the immediate wired path; no listener/route change without a reproduced Wi-Fi Direct or hotspot issue. | PR-08 review |

## Register completeness

- The plan's audit describes 1,289 upstream-only commits as 755 ordinary + 477 merge + 34 added ordinary + 23 added merge, and 79 conflicted files / 385 blocks. These are audit-supplied scope numbers, not recomputed by this PR-00 baseline.
- Each row above is a planning decision, not evidence that the referenced upstream commit was implemented.
- EX-08 remains OPEN until commit/path-level entries, tests, ownership, and rollback are recorded for the full candidate set. A bulk merge is prohibited.
