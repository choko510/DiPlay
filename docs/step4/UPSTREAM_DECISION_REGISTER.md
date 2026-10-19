# Upstream decision register — seeded from v3, not parity proof

## Scope and evidence level

- Production comparison baseline remains the fixed fork `main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`.
- The v3 audit identifies 1,232 older upstream-only commits plus 57 incremental commits (1,289 total; 789 ordinary and 500 merge commits in its classification). These are audit-summary figures, not an independent rehash/review of every patch in PR-00.
- The audit reports a virtual merge with 79 conflict files and 385 conflict blocks. This does not prove every conflict has a unique function-level resolution.
- No upstream commit is merged or copied in PR-00. Per-commit dispositions, surviving behavior, and regression links remain an EX-08 deliverable; inventory coverage must not be described as equivalence/parity proof.

## Corrected audit conclusions carried into implementation planning

| Audit ref | Corrected interpretation from v3 | PR-00 disposition |
|---|---|---|
| C01 | Keep 105 two-parent remerge diffs separate from one Octopus merge represented by 11 parent records; do not flatten those parent records into independent merge counts. | Preserve the audit's classification; no code action. |
| C02 | Main's NCM TX constant and the selected USB endpoint's real packet size are separate facts. | Preserve endpoint evidence; any 512-byte RX boundary adaptation belongs in PR-01. |
| C03 | Main already has a 100 ms pre-ready OUT policy and NDP retry behavior. | No generic 20 ms timeout or duplicate retransmission. |
| C04 | Main already has 8 s soft / 15 s hard wired watchdog semantics. | Preserve deadlines until EX-01/EX-03 evidence. |
| C05 | Main minSdk is 28, so API 26/27 Android 8-only queue behavior is unreachable. | Mark API26/27-only code not applicable; evaluate only API28+ queue failure conditionally. |
| C06 | SYN counts can be incomplete because of VLAN, IPv6 extension, fragments, destination, or port checks. | Do not equate a zero counter with no phone SYN; EX-01 owns classifier scope. |
| C07 | IPv6 receive count, destination/scope, TUN write, TCP SYN-ACK, and accept are distinct layers. | Keep the evidence fields separate. |
| C08 | A guessed ViewArea command being sent is not evidence the iPhone accepted or applied it. | Keep dynamic ViewArea disabled absent explicit ACK and changed frame dimensions. |
| C09 | LOCAL texture/layout scaling can be independent of a new ViewArea declaration. | Preserve a LOCAL-only path and legacy fallback. |
| C10 | The audit flags a 1-second video-reconnect behavior as reverted. | Do not transplant it without a current-main source review. |
| C11 | A Concentus exception fix does not match main's MediaCodec microphone encoder path. | Review the actual main codec route; no name-only patch adoption. |
| C12 | BYD operating-system split and generic in-app CarPlay/YouTube Split are different features. | Do not reintroduce BYD-only dependencies as generic Split support. |
| C13 | A static Python pass is not full audit completion. | Keep semantic review, Gradle/runtime, and device evidence separately classified. |
| C14 | NCM RX 0 bytes and NTB parser boundaries are different failure layers. | Observe request queue/completion before attributing an RX failure to framing. |
| C15 | `0x4302` is not a verified iAP2 EndSession opcode; the audit identifies it as AvailableDigitalCarKeys. | Never send a guessed EndSession command. |
| C16 | Upstream tests cannot be copied unchanged across main's API/constructor/DSP/owner differences. | Build and maintain fork-specific test factories. |
| C17 | Structural merge resolution does not prove functional equivalence for all merge commits. | Keep merge disposition distinct from runtime proof. |
| C18 | `MediaAudioBuffer.plan` and related signatures differ across upstream and fork. | Rebuild tests against current-main interfaces. |
| C19 | TCP accept, RTSP Active, screen setup, and MAIN first frame are different milestones. | Preserve independent test/report states. |
| C20 | Other head-unit/iPhone reports are hypotheses, not TS10/K706 evidence. | No physical-fix claim without local reproduction. |

## Initial decision classes

| Decision class | Rule for this workstream |
|---|---|
| `PRESERVE_MAIN` | Keep fork-owned NCM request lifecycle, diagnostics, CarPlay session owner, GeckoView profile isolation, current audio routing, DSP master-off, and background session behavior unless a stage proves a narrowly scoped correction. |
| `ADAPT` | Apply only a current-main-equivalent, source-reviewed change whose constructors, state transitions, ownership, and tests are adapted to the fork. PR-01 boundary handling is an example to assess after PR-00/EX-01. |
| `CONDITIONAL` | Queue fallback, re-enumeration probe, startup OUT changes, extra StartSession, status demux, IP/scope changes, ViewArea wire commands, codec tuning, and hardware-dependent behavior require their stated evidence first; otherwise record `NO_GO`/`NOT_RUN`. |
| `REJECT_UNSAFE` | No bulk merge, guessed iAP2 opcode, second `requestWait` owner, unbounded retry/resync, or production-on unknown protocol path. |
| `ALREADY_PRESENT` | Only record when the behavior is confirmed in fixed-main source and has adequate main tests; a similarly named upstream feature is insufficient. |
| `NEED_REVIEW` | Default for individual upstream commits until EX-08 records a per-SHA source/patch anchor, disposition, reason, test evidence, STEP4 mapping, and rollback. |

## PR-00 status

This register is seeded from the formal v3 report and main source baseline. PR-00 did not analyze all 1,289 commits and does not close EX-08.
