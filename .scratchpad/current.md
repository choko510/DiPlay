# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- F8-01/02/03 production fixes and MAIN/ALT, RTSP-bound, and four-session regression tests are already in PR #45. Supported-iPhone compatibility remains `BLOCKED_DEVICE`.
- DSP Split duplicate-log suppression, inset-before-scroll ordering, and exact MeasureSpecs passed to `ComposeView.measure()` are already present. The ComposeView dependency is final; SH-M27 acceptance remains `BLOCKED_DEVICE`.
- Added the explicit F-05 regression for a target SYN after eight unrelated destination ports. Local shared/common suites pass 865/865; CI classifier fixtures and EX-08 tests pass, and inventory remains 1,400 unique rows.
- Added VIDEO-02 release-failure injection and retained-owner regression; the default release delegates directly to `MediaCodec.release()`. Workflow-sensitive run #486 passed host/native/sanitizer/lint/build/JNI/R8/API 35; Shared was 19/19 and Common 1/1.
- The implementation result, final summary, remaining-gate register, and test matrix record current-head evidence; `TEST_MATRIX.md` maps all 98 v4 case IDs. B09 is `PASS_CI`. Preserve the earlier API 35 p99 failure beside later passes; its cause remains unconfirmed. Local macOS LeakSanitizer is unavailable, while hosted Linux sanitizer passed.
- TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.

## Next

1. Keep PR #45 Draft and G8 `NO_GO_THIS_RUN`; wait for the supported TS10/K706+iPhone matrix, release-signing credentials, and independent review.
2. If new device or review evidence arrives, update only the applicable gate records and preserve the 98-ID crosswalk.
