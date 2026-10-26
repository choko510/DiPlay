# Final Gate Summary — PR #45 / STEP4

**Decision: `NO_GO_THIS_RUN`. Do not merge or release from this evidence set.** PR #45 remains open and Draft.

## Snapshot

- Base: `main@95ba398f95ee104527812cd2b095977d71c51f98`
- Product-feature baseline: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`
- Current code/test head: `52799604c5810ea97b2c7b5e685cf1c2155de773` (includes a test-only MediaCodec release injection seam and VIDEO-02 regression)
- PR: [#45](https://github.com/choko510/DiPlay/pull/45)
- Latest completed full workflow-sensitive run: [#486](https://github.com/choko510/DiPlay/actions/runs/40372862000) passed on the current workflow-classifier HEAD, including host/native/sanitizer/lint/build/JNI/R8, API 35 instrumentation, host baseline and Draft scope. Shared instrumentation passed 19/19 and Common 1/1. Run #481 also passed on the preceding code/test head. Docs-only runs #482 and #483 passed static checks and Draft scope; runtime lanes were skipped.

## Release gates

| Gate | Status | Evidence and remaining condition |
|---|---|---|
| G0 — Preserve user-resolved C-00 | `USER_CONFIRMED_RESOLVED` | DSP Split C-00 was not reimplemented. Regression coverage remains; this confirmation is not a device measurement. |
| G1 — Source fixes and rollback ownership | `PASS_LOCAL` | F8 fixes already present at `550ec3a`; B03/B04, Surface, DSP and B08 regression suites pass locally. B08 adds observation only. Production rollback is the existing PR branch/revert path. |
| G2 — USB/VPN/HID owner regressions | `PASS_LOCAL` | Current shared/common JVM/Robolectric suites pass 865/865. Physical USB endpoint/OEM and head-unit behavior remain unverified. |
| G3 — RTSP/Surface recovery and stress | `PARTIAL` | RTSP limits, MAIN/ALT pre-RECORD recovery, four-session permits, Surface ACK/backpressure, and VIDEO-02 release-failure ownership fixtures pass locally. Supported iPhone traffic and TS10/K706 Surface continuity remain `BLOCKED_DEVICE`. |
| G4 — DSP OFF/MEDIA/latency/fallback/JNI/native | `PARTIAL` | Run #486 passed host native tests, ASan/UBSan, exact PCM16 regressions, mobile/automotive Debug and Benchmark builds, lint, JNI mapping and API 35 instrumentation (Shared 19/19, Common 1/1). The latest DSP p99 samples meet the unchanged budget; retain the earlier same-head p99 failure and crash. Default LeakSanitizer is unsupported locally. Target acoustics/THD/A-V evidence is `BLOCKED_DEVICE`. |
| G5 — EX-08 fixed 1,289 + 111 overlay | `PASS_LOCAL` | 1,400 unique SHA rows; legacy 1,289-row prefix byte-identical; all 111 source/merge SHAs classified; overlay tests pass 19/19; generator `--check` passes. This is audit/inventory evidence, not device parity. |
| G6 — Current-head full host/API 35 lane | `PASS_CI` | Run #486 passed the current workflow-sensitive host/native/sanitizer/lint/build/JNI/R8/API 35 lane; Shared instrumentation passed 19/19 and Common 1/1. Local 865 shared/common tests also pass. Preserve the earlier API 35 p99 failure/crash; physical device acceptance remains separate. |
| G7 — TS10/K706 + iPhone matrix | `BLOCKED_DEVICE` | No supported target head unit/iPhone attached. See `B10_DEVICE_ACCEPTANCE.md`, HW-01–HW-13. SH M20 evidence remains `PARTIAL`, outside the required target class. |
| G8 — Signed update, compatibility and rollback | `BLOCKED_ENV` / `NO_GO_THIS_RUN` | Authorized release-signing credentials and installed release rollback evidence are unavailable. Debug signing is not a substitute. |
| G9 — Independent review | `NOT_RUN` | Remains unrequested; no independent approval is inferred. |

## Unchanged conditional behavior

No additional `0x4301` StartSession send, timer, NCM profile change, IPv4 route, codec override, clock trim, or permission change was introduced. B11 records `NO_GO_THIS_RUN`; EX-05 production retry remains zero.

## Next actions required for a release decision

1. Preserve the earlier failed and later passing API 35 runs with the 21.332 ms budget unchanged; the current full hosted gate passed.
2. Attach the supported TS10/K706 and iPhone, then execute and record HW-01–HW-13 using the metadata template in `B10_DEVICE_ACCEPTANCE.md`.
3. Provide authorized release-signing credentials and perform same-signature update plus installed rollback for HW-11.
4. Obtain the independent review required by G9.

No PR state change or merge was performed.
