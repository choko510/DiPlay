# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- Workflow-sensitive run #495 passed the full host, sanitizer, unit/build, JNI/R8 and API 35 lanes on the current code/test head; its artifacts confirm 865 unit tests and 20 API 35 instrumentation tests passed. All 56 x86_64 median wall-p99 gates passed (max median 15.440 ms, max single-run p99 17.720 ms, max thread-CPU p99 4.760 ms). Subsequent docs-only run #497 passed its static checks; runtime jobs were skipped.
- The current test-only ComposeView measurement regression passes 9/9 locally and run #498's Draft unit job. Run #498 skipped host/native/lint/build/JNI/R8, `host-baseline`, and API 35 instrumentation; it adds no full-runtime evidence.
- The updated evidence package was pushed to PR #45; docs-only run #499 passed `changes`, static `check`, and Draft scope. Host/native, all unit/build/JNI/R8/instrumentation jobs were skipped by the classifier.
- Added per-block thread-CPU p99 beside wall p99 in the DSP instrumentation benchmark. Two focused local API 35 arm64 Debug runs fail different configurations; the latest fails three cases, and two have thread-CPU p99 over their fixed ceilings. Debug native code is `-O0` while Release is `-O2`; this confirms a variant difference but not the cause. The 21.332/23.218 ms wall ceilings remain unchanged, and target-device behavior is unverified.
- Added a Robolectric regression that measures an actual ComposeView through the existing DSP pane hook and verifies the exact recorded MeasureSpecs/result. The focused Host-layout and diagnostics suites pass 9/9; SH-M27 layout/render acceptance remains `BLOCKED_DEVICE`.
- Preserve run #490's 41.636 ms single-run tail, local prior 84.009 ms tail, and the earlier failed p99 run. Local macOS LeakSanitizer is unavailable; hosted Linux sanitizer passed.
- Software implementation and its regression coverage are recorded in `completed.md` and the Step4 result package.
- TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.

## Next

1. Keep PR #45 Draft and G8 `NO_GO_THIS_RUN`; device acceptance and signed update still require TS10/K706+iPhone hardware and authorized signing credentials.
2. Keep independent review `NOT_RUN` per the existing direction. Do not infer target-device audio/layout acceptance from emulator tests.
