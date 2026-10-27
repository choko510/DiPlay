# Final Gate Summary — PR #45 / STEP4

**Decision: `NO_GO_THIS_RUN`. Do not merge or release from this evidence set.** PR #45 remains open and Draft.

## Snapshot

- Base: `main@95ba398f95ee104527812cd2b095977d71c51f98`
- Product-feature baseline: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`
- Current code/test head: `78f43526d69b5fe97aae150d0a222ae590fb3b53` (test-only ComposeView measurement regression; product source remains at `6392b56f`)
- PR: [#45](https://github.com/choko510/DiPlay/pull/45)
- Latest completed full workflow-sensitive run: [#495](https://github.com/choko510/DiPlay/actions/runs/40473871836) passed host/native/sanitizer/unit/lint/build/JNI/R8 and API 35 instrumentation on product source head `6392b56f`. Its artifacts contain 865 unit-test results (659 Shared + 206 Common) and 20 API 35 instrumentation results (19 Shared + 1 Common), all with zero failures/errors/skips. The x86_64 DSP benchmark passed all 56 median wall-p99 gates (maximum median 15,440 µs; maximum single-run p99 17,720 µs; maximum thread-CPU p99 4,760 µs). Current test-only head `78f43526` passed the Draft unit job in run #498; runtime lanes were skipped. PR #45 remains open and Draft.

## Release gates

| Gate | Status | Evidence and remaining condition |
|---|---|---|
| G0 — Preserve user-resolved C-00 | `USER_CONFIRMED_RESOLVED` | DSP Split C-00 was not reimplemented. Regression coverage remains; this confirmation is not a device measurement. |
| G1 — Source fixes and rollback ownership | `PASS_LOCAL` | F8 fixes already present at `550ec3a`; B03/B04, Surface, DSP and B08 regression suites pass locally. B08 adds observation only. Production rollback is the existing PR branch/revert path. |
| G2 — USB/VPN/HID owner regressions | `PASS_LOCAL` | The full shared/common suite passed 865/865 before the latest test-only measurement regression; the affected focused Host/diagnostics suites now pass 9/9. Physical USB endpoint/OEM and head-unit behavior remain unverified. |
| G3 — RTSP/Surface recovery and stress | `PARTIAL` | RTSP limits, MAIN/ALT pre-RECORD recovery, four-session permits, Surface ACK/backpressure, and VIDEO-02 release-failure ownership fixtures pass locally. Supported iPhone traffic and TS10/K706 Surface continuity remain `BLOCKED_DEVICE`. |
| G4 — DSP OFF/MEDIA/latency/fallback/JNI/native | `PARTIAL` | Run #495 passed host native tests, ASan/UBSan, unit tests, mobile/automotive Debug and Benchmark builds, lint, JNI mapping and API 35 instrumentation (Shared 19/19, Common 1/1). Its x86_64 benchmark passed all 56 median wall-p99 gates (maximum median 15,440 µs; maximum single-run p99 17,720 µs; maximum thread-CPU p99 4,760 µs). Two focused local arm64 Debug AVD runs fail different configurations; the latest fails three test methods, including two with thread-CPU p99 above budget. The local Debug build uses native `-O0` while Release uses `-O2`, but this does not prove the cause. Preserve these separately from earlier 41,636 µs CI and 84,009 µs local tails. The limit is unchanged. Default LeakSanitizer is unsupported locally; target acoustics/THD/A-V evidence is `BLOCKED_DEVICE`. |
| G5 — EX-08 fixed 1,289 + 111 overlay | `PASS_LOCAL` | 1,400 unique SHA rows; legacy 1,289-row prefix byte-identical; all 111 source/merge SHAs classified; overlay tests pass 19/19; generator `--check` passes. This is audit/inventory evidence, not device parity. |
| G6 — Current product source full host/API 35 lane | `PASS_CI` | Run #495 passed the full workflow-sensitive host/native/sanitizer/unit/lint/build/JNI/R8/API 35 lane on product source head `6392b56f`. Its retained XML confirms 865 unit tests and 20 instrumentation tests with zero failures/errors/skips. The current test-only head `78f43526` passed its focused Draft unit scope in run #498. Preserve the local arm64 focused p99 failures; physical device acceptance remains separate. |
| G7 — TS10/K706 + iPhone matrix | `BLOCKED_DEVICE` | No supported target head unit/iPhone attached. See `B10_DEVICE_ACCEPTANCE.md`, HW-01–HW-13. SH M20 evidence remains `PARTIAL`, outside the required target class. |
| G8 — Signed update, compatibility and rollback | `BLOCKED_ENV` / `NO_GO_THIS_RUN` | Authorized release-signing credentials and installed release rollback evidence are unavailable. Debug signing is not a substitute. |
| G9 — Independent review | `NOT_RUN` | Remains unrequested; no independent approval is inferred. |

## Unchanged conditional behavior

No additional `0x4301` StartSession send, timer, NCM profile change, IPv4 route, codec override, clock trim, or permission change was introduced. B11 records `NO_GO_THIS_RUN`; EX-05 production retry remains zero.

## Next actions required for a release decision

1. Preserve run #495's x86_64 pass alongside the focused local arm64 wall-p99 failure, with the 21.332/23.218 ms budgets unchanged.
2. Attach the supported TS10/K706 and iPhone, then execute and record HW-01–HW-13 using the metadata template in `B10_DEVICE_ACCEPTANCE.md`.
3. Provide authorized release-signing credentials and perform same-signature update plus installed rollback for HW-11.
4. Obtain the independent review required by G9.

No PR state change or merge was performed.
