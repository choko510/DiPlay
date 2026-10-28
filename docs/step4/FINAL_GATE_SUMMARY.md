# Final Gate Summary — PR #45 / STEP4

**Decision: `NO_GO_THIS_RUN`. Do not merge or release from this evidence set.** PR #45 remains open and Draft.

## Snapshot

- Base: `main@95ba398f95ee104527812cd2b095977d71c51f98`
- Product-feature baseline: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`
- Prior code/test/config head for run #502: `df3977d4a0860970e15a42d5f65a473cfe60e8ee` (Release-target instrumentation configuration; product source for that run was `6392b56f`)
- PR: [#45](https://github.com/choko510/DiPlay/pull/45)
- Earlier completed full workflow-sensitive run: [#502](https://github.com/choko510/DiPlay/actions/runs/40487578349) passed host/native/sanitizer/unit/lint/build/JNI/R8, Release-target API 35 instrumentation, and host-baseline on test/config head `df3977d4`; check and instrumentation report artifacts were uploaded. Run #495 remains the full product-source baseline with 865 unit tests and 20 API 35 instrumentation tests, all passing; its x86_64 benchmark passed all 56 median wall-p99 gates (maximum median 15,440 µs; maximum single-run p99 17,720 µs; maximum thread-CPU p99 4,760 µs). Earlier Draft unit run #498 on test head `78f43526` passed, with runtime lanes skipped. PR #45 remains open and Draft.

## Current review-fix snapshot — 2026-10-28

- Latest source snapshot: `d4954869f14b945dba3dfd4732146a34ddc708f6`, including USB handoff fix `5f69b20c`; latest test head: `a3c66a68af57b52bcd9302e25839fa795468cbf3`. The concurrent PR commit `d140f6e` adding opt-in iPhone USB auto-launch was preserved.
- Local tests: full Shared 664/664 passed after the timeout-fixture correction. Full Common 235/235 passed on the merged tree; after the additional 20-event assertions, both modified USB Common classes passed. Mobile/automotive Debug lint, Debug/Benchmark builds, and merged manifests passed on the source snapshot. API 36.1 x86_64 emulator instrumentation passed Shared Release 19/19 and Common Debug 1/1 on an earlier snapshot; these results do not substitute for TS10/K706+iPhone acceptance.
- Run [#545](https://github.com/choko510/DiPlay/actions/runs/40628808576) passed `changes`, Draft unit/quick-baseline, and Draft scope on `1290fb8`. Run [#546](https://github.com/choko510/DiPlay/actions/runs/40629443355) passed the same Draft lanes for the additional USB 20-event tests on `a3c66a68`; host-baseline and API 35 instrumentation were skipped under Draft classification. Earlier full run #527 passed on `c8a1ab0`, before the latest handoff source changes, so the current source snapshot does not have a full-host/API 35 CI pass.
- Detailed changes and per-test evidence are in [PR45_REVIEW_FIX_IMPLEMENTATION_REPORT.md](PR45_REVIEW_FIX_IMPLEMENTATION_REPORT.md) and [PR45_REVIEW_FIX_TEST_MATRIX.md](PR45_REVIEW_FIX_TEST_MATRIX.md). Keep release `NO_GO_THIS_RUN` until device, signing/rollback, and review gates are resolved.

## Release gates

| Gate | Status | Evidence and remaining condition |
|---|---|---|
| G0 — Preserve user-resolved C-00 | `USER_CONFIRMED_RESOLVED` | DSP Split C-00 was not reimplemented. Regression coverage remains; this confirmation is not a device measurement. |
| G1 — Source fixes and rollback ownership | `PASS_LOCAL` | F8 fixes already present at `550ec3a`; B03/B04, Surface, DSP and B08 regression suites pass locally. B08 adds observation only. Production rollback is the existing PR branch/revert path. |
| G2 — USB/VPN/HID owner regressions | `PASS_LOCAL` | Full Shared/Common suites passed 664/235 on the merged source; the current timeout fix passed the full Shared suite, and the expanded USB Host/coordinator classes passed after 20-event coverage was added. Physical USB endpoint/OEM and head-unit behavior remain unverified. |
| G3 — RTSP/Surface recovery and stress | `PARTIAL` | RTSP limits, MAIN/ALT pre-RECORD recovery, four-session permits, Surface ACK/backpressure, and VIDEO-02 release-failure ownership fixtures pass locally. Supported iPhone traffic and TS10/K706 Surface continuity remain `BLOCKED_DEVICE`. |
| G4 — DSP OFF/MEDIA/latency/fallback/JNI/native | `PARTIAL` | Run #495 passed host native tests, ASan/UBSan, unit tests, mobile/automotive Debug and Benchmark builds, lint, JNI mapping and API 35 instrumentation (Shared 19/19, Common 1/1). Its x86_64 benchmark passed all 56 median wall-p99 gates (maximum median 15,440 µs; maximum single-run p99 17,720 µs; maximum thread-CPU p99 4,760 µs). The updated local script passes the full API 35 arm64 Shared Release 19/19 + Common Debug 1/1 instrumentation; all 56 optimized Release median p99 gates pass (maximum median 1,560 µs; maximum single-run p99 2,140 µs). Preserve two focused Debug failures and one earlier combined-invocation Release failure (three p99 cases); the isolated sequential runs pass, and the combined-run cause remains unknown. Keep thresholds unchanged. Default LeakSanitizer is unsupported locally; target acoustics/THD/A-V evidence is `BLOCKED_DEVICE`. |
| G5 — EX-08 fixed 1,289 + 111 overlay | `PASS_LOCAL` | 1,400 unique SHA rows; legacy 1,289-row prefix byte-identical; all 111 source/merge SHAs classified; overlay tests pass 19/19; generator `--check` passes. This is audit/inventory evidence, not device parity. |
| G6 — Current product source full host/API 35 lane | `PARTIAL` | Run #527 passed host/native/ASan+UBSan/unit/lint/build/JNI/R8 and API 35 instrumentation for earlier source head `c8a1ab0`; it uploaded check/instrumentation artifacts 12747761188 and 12747252715. Later handoff source snapshot `d4954869` passed local full unit/build/lint checks, and Draft runs #545/#546 cover current unit changes; the current source snapshot has not rerun the full host/API 35 lane. Physical device acceptance remains separate. |
| G7 — TS10/K706 + iPhone matrix | `BLOCKED_DEVICE` | No supported target head unit/iPhone attached. See `B10_DEVICE_ACCEPTANCE.md`, HW-01–HW-13. SH M20 evidence remains `PARTIAL`, outside the required target class. |
| G8 — Signed update, compatibility and rollback | `BLOCKED_ENV` / `NO_GO_THIS_RUN` | Authorized release-signing credentials and installed release rollback evidence are unavailable. Debug signing is not a substitute. |
| G9 — Independent review | `NOT_RUN` | Remains unrequested; no independent approval is inferred. |

## Unchanged conditional behavior

No additional `0x4301` StartSession send, timer, NCM profile change, IPv4 route, codec override, or clock trim was introduced. The Apple USB Activity now has a `MANAGE_USB` caller restriction; the app does not request that privileged permission. B11 records `NO_GO_THIS_RUN`; EX-05 production retry remains zero.

## Next actions required for a release decision

1. Preserve run #495's x86_64 pass alongside the focused local arm64 wall-p99 failure, with the 21.332/23.218 ms budgets unchanged.
2. Attach the supported TS10/K706 and iPhone, then execute and record HW-01–HW-13 using the metadata template in `B10_DEVICE_ACCEPTANCE.md`.
3. Provide authorized release-signing credentials and perform same-signature update plus installed rollback for HW-11.
4. Obtain the independent review required by G9.

No PR state change or merge was performed.
