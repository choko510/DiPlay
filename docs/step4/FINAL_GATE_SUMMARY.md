# Final Gate Summary — PR #45 / STEP4

**Decision: `NO_GO_THIS_RUN`. Do not merge or release from this evidence set.** PR #45 remains open and Draft.

## Snapshot

- Base: `main@95ba398f95ee104527812cd2b095977d71c51f98`
- Product-source commit: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`
- Current code/test head: `0f425cdc98fd78e3835350fc614881ecf911b729` (B08 host-overhead fixture and measurement record; no production code changed from `b767ed8f`)
- PR: [#45](https://github.com/choko510/DiPlay/pull/45)
- Latest Draft CI: [run #477](https://github.com/choko510/DiPlay/actions/runs/40361161687) on docs-only head `94d2ba35`; run #476 covers code/test head `0f425cdc`.

## Release gates

| Gate | Status | Evidence and remaining condition |
|---|---|---|
| G0 — Preserve user-resolved C-00 | `USER_CONFIRMED_RESOLVED` | DSP Split C-00 was not reimplemented. Regression coverage remains; this confirmation is not a device measurement. |
| G1 — Source fixes and rollback ownership | `PASS_LOCAL` | F8 fixes already present at `550ec3a`; B03/B04, Surface, DSP and B08 regression suites pass locally. B08 adds observation only. Production rollback is the existing PR branch/revert path. |
| G2 — USB/VPN/HID owner regressions | `PASS_LOCAL` | Current shared/common JVM/Robolectric suites pass 862/862. Physical USB endpoint/OEM and head-unit behavior remain unverified. |
| G3 — RTSP/Surface recovery and stress | `PARTIAL` | RTSP limits, MAIN/ALT pre-RECORD recovery, four-session permits, and Surface ACK/backpressure fixtures pass locally. Supported iPhone traffic and TS10/K706 Surface continuity remain `BLOCKED_DEVICE`. |
| G4 — DSP OFF/MEDIA/latency/fallback/JNI/native | `PARTIAL` | Host native tests, exact PCM16 regressions, mobile/automotive Debug and Benchmark builds, lint, and JNI mapping pass. The latest API 35 Shared instrumentation run passes 19/19 and its sampled p99 values meet the unchanged budget; retain the earlier same-head p99 failure and crash. Default LeakSanitizer is unsupported locally. Target acoustics/THD/A-V evidence is `BLOCKED_DEVICE`. |
| G5 — EX-08 fixed 1,289 + 111 overlay | `PASS_LOCAL` | 1,400 unique SHA rows; legacy 1,289-row prefix byte-identical; all 111 source/merge SHAs classified; overlay tests pass 19/19; generator `--check` passes. This is audit/inventory evidence, not device parity. |
| G6 — Current-head full host/API 35 lane | `PARTIAL` | Local unit, lint, Debug/Benchmark, JNI/R8 mapping, classifier, and native checks passed. The latest API 35 Shared instrumentation repeat passes 19/19; the earlier same-head run exceeded the unchanged 21.332 ms p99 limit and crashed, so retain both. Run #476's Draft unit/check jobs passed; docs-only run #477 passed `changes`, static `check`, and Draft scope while skipping quick-baseline. Both runs skipped full host/native/sanitizer/lint/build/JNI/instrumentation. |
| G7 — TS10/K706 + iPhone matrix | `BLOCKED_DEVICE` | No supported target head unit/iPhone attached. See `B10_DEVICE_ACCEPTANCE.md`, HW-01–HW-13. SH M20 evidence remains `PARTIAL`, outside the required target class. |
| G8 — Signed update, compatibility and rollback | `BLOCKED_ENV` / `NO_GO_THIS_RUN` | Authorized release-signing credentials and installed release rollback evidence are unavailable. Debug signing is not a substitute. |
| G9 — Independent review | `NOT_RUN` | Remains unrequested; no independent approval is inferred. |

## Unchanged conditional behavior

No additional `0x4301` StartSession send, timer, NCM profile change, IPv4 route, codec override, clock trim, or permission change was introduced. B11 records `NO_GO_THIS_RUN`; EX-05 production retry remains zero.

## Next actions required for a release decision

1. Preserve the failed and passing API 35 runs at the same source head, keep the 21.332 ms budget unchanged, and obtain full hosted current-head evidence when the full lane is available; run #476 skipped it under Draft scope.
2. Attach the supported TS10/K706 and iPhone, then execute and record HW-01–HW-13 using the metadata template in `B10_DEVICE_ACCEPTANCE.md`.
3. Provide authorized release-signing credentials and perform same-signature update plus installed rollback for HW-11.
4. Obtain the independent review required by G9.

No PR state change or merge was performed.
