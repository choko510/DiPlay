# STEP4 upstream and behavior decision register — PR-00 baseline

## Fixed references and rules

- Fork implementation source: `95ba398f95ee104527812cd2b095977d71c51f98`.
- Upstream comparison reference only: `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`.
- Audit catalog reference: `a69a4acb6d50116f4855bebbb94b16047c36c466`.
- Do not merge upstream wholesale. Original `PR-00` through `PR-08` retain their numbers and scope; EX-01–EX-08 are additional work, and DSP-00–05 remain an isolated series.
- The EX-08 1,289-commit decision ledger is a later required artifact. PR-00 does not claim that ledger is complete or that candidate parity has been proven.

## Decisions that constrain later stages

| Decision ID | Main evidence / decision | Work item | Status at PR-00 |
|---|---|---|---|
| D-00-01 | Fixed fork main is the sole production baseline; PR-00 contains no production edit | All | `PASS_SOURCE_LOCK` |
| D-00-02 | Fork `minSdk=28`; Android 8/API26–27 queue-cap work is not reachable without a separate minSdk decision | PR-02 | `NOT_APPLICABLE` for API26/27; API28+ fallback remains conditional |
| D-00-03 | `PersistentUsbReadRequestState` keeps the same request queued after timeout; null/unexpected completion fails the transport | PR-00 / PR-02 / EX-06 | `PASS_LOCAL` state tests; endpoint reaper changes not approved |
| D-00-04 | Existing bulk OUT is typed `Sent`/`NotReady`/`Failed`; only NDP startup retry is specialized | PR-03 / EX-05 | Preserve; do not add a TCP SYN-ACK retry before EX-01 evidence |
| D-00-05 | `NcmUsbBridge` receive framing requires the boundary pad for a declared 512-byte block | PR-01 | `KNOWN_DEFECT_REPRODUCED`; repair belongs to PR-01 |
| D-00-06 | Existing watchdog is 8-second soft / 15-second hard; `SCREEN_STREAM_OPENED` remains its current completion condition | EX-03 | Preserve while adding stage observations; no new timer in PR-00 |
| D-00-07 | Existing Controller and HostActivity use different attempt/UI generations and recovery owners | EX-02 | Preserve; recovered-session timer race requires its own regression stage |
| D-00-08 | IPv6-only NCM→TUN path is current behavior; an IPv4 failure cause is not proven | EX-07 | Keep IPv4 route addition `NOT_RUN` |
| D-00-09 | `0x4302` is `AvailableDigitalCarKeys`, not an end-session command | EX-05 | Never send as an end-session signal |
| D-00-10 | CarPlay ViewArea multi-view support does not prove an undocumented dynamic wire payload | PR-05 / PR-06 | Dynamic wire `OFF`; local scaling is a separate candidate |
| D-00-11 | The existing in-app 55:45 GeckoView split and per-device profile state are fork assets | PR-06 | Preserve; do not replace with OEM/BYD OS multi-window code |
| D-00-12 | Existing DSP is a 2-channel graph with PEQ, bass, dynamics, M/S width, convolver, and limiter | DSP-00..05 | Freeze current graph/defaults before adding nodes |
| D-00-13 | Local MSYS2 UCRT GCC cannot link ASan/UBSan in this environment | PR-08 | `BLOCKED_ENV` locally; hosted Ubuntu sanitizer result is the available sanitizer evidence |

## EX-08 dispositions

Use only `TAKE_DIRECT`, `ADAPT`, `FORK_SUPERIOR`, `SKIP_OEM`, `REJECT_UNSAFE`, and `NEED_EVIDENCE` with a source anchor, reason, tests, target PR, and rollback. A search result or commit index is not functional-equivalence proof. The detailed per-commit ledger remains to be built in EX-08; this register is not a substitute for it.
