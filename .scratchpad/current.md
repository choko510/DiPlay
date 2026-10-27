# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- Workflow-sensitive run #495 passed the full host, sanitizer, unit/build, JNI/R8 and API 35 lanes on product source/test head `6392b56f`; its artifacts confirm 865 unit tests and 20 API 35 instrumentation tests passed. All 56 x86_64 median wall-p99 gates passed (max median 15.440 ms, max single-run p99 17.720 ms, max thread-CPU p99 4.760 ms). Subsequent docs-only run #497 passed its static checks; runtime jobs were skipped.
- The current test-only ComposeView measurement regression passes 9/9 locally and run #498's Draft unit job. Run #498 skipped host/native/lint/build/JNI/R8, `host-baseline`, and API 35 instrumentation; it adds no full-runtime evidence.
- The updated evidence package was pushed to PR #45; docs-only run #499 passed `changes`, static `check`, and Draft scope. Host/native, all unit/build/JNI/R8/instrumentation jobs were skipped by the classifier.
- Added per-block thread-CPU p99 beside wall p99 in the DSP instrumentation benchmark. Two focused local API 35 arm64 Debug runs fail different configurations; the latest fails three cases, and two have thread-CPU p99 over their fixed ceilings. Debug native code is `-O0`; Release is `-O2`. The sequential Shared Release/Common Debug script passes 19/19 + 1/1 locally with valid evidence logs and all 56 Release p99 gates. One earlier combined invocation failed three p99 cases; its cause remains unconfirmed. Run #502 passed the workflow-sensitive Release-target CI path. The 21.332/23.218 ms ceilings remain unchanged, and target-device behavior is unverified.
- Current EX-08 inventory verification passes at 1,400 unique SHAs, and the delta-111 fixture passes 19/19. `sharedAndroidTestBuildType=release` now provides `connectedReleaseAndroidTest`; the default still exposes `connectedDebugAndroidTest`.
- Added a Robolectric regression that measures an actual ComposeView through the existing DSP pane hook and verifies the exact recorded MeasureSpecs/result. The focused Host-layout and diagnostics suites pass 9/9; SH-M27 layout/render acceptance remains `BLOCKED_DEVICE`.
- Preserve run #490's 41.636 ms single-run tail, local prior 84.009 ms tail, and the earlier failed p99 run. Local macOS LeakSanitizer is unavailable; hosted Linux sanitizer passed.
- Software implementation and its regression coverage are recorded in `completed.md` and the Step4 result package.
- TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.
- Workflow-sensitive [run #502](https://github.com/choko510/DiPlay/actions/runs/40487578349) passed on `df3977d4`: classifier, EX-08, host native, ASan/UBSan, full unit/lint/build/JNI/R8, API 35 instrumentation, host-baseline, and Draft scope succeeded. `quick-baseline` and separate Draft unit lane were skipped because full-host scope ran. Shared Release and Common Debug reports were uploaded as artifacts 12726236011 and 12727040240.
- Docs-only [run #503](https://github.com/choko510/DiPlay/actions/runs/40489671828) on current HEAD `e8de90e3` passed the EX-08 delta-111 test, generated-ledger check, public-tree check, and Draft scope; product runtime jobs were skipped. Current generated totals are 11 `ADAPT`, 27 `FORK_SUPERIOR`, 207 `SKIP_OEM`, 765 `REJECT_UNSAFE`, and 390 `NEED_EVIDENCE` across 1,400 unique SHAs. The 111-row overlay has 2 `FORK_SUPERIOR`, 16 `SKIP_OEM`, 56 `REJECT_UNSAFE`, 37 `NEED_EVIDENCE`, and 0 `ADAPT`.

## Next

1. Keep PR #45 Draft and G8 `NO_GO_THIS_RUN`; device acceptance and signed update still require TS10/K706+iPhone hardware and authorized signing credentials.
2. Keep independent review `NOT_RUN` per the existing direction. Do not infer target-device audio/layout acceptance from emulator tests.
