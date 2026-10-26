# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- F8-01/02/03 production fixes and MAIN/ALT, RTSP-bound, and four-session regression tests are already in PR #45. Supported-iPhone compatibility remains `BLOCKED_DEVICE`.
- DSP Split duplicate-log suppression, inset-before-scroll ordering, and exact MeasureSpecs passed to `ComposeView.measure()` are already present. The ComposeView dependency is final; SH-M27 acceptance remains `BLOCKED_DEVICE`.
- Added the explicit F-05 regression for a target SYN after eight unrelated destination ports. Local shared/common suites pass 864/864; CI classifier fixtures and EX-08 tests pass, and inventory remains 1,400 unique rows.
- Pushed classifier fallback and workflow coverage to PR #45. Run #481 passed `changes`, full host/native/sanitizer/lint/build/JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope; Shared was 19/19 and Common 1/1.
- Local shared/common suites pass 864/864, CI-01..10 fixtures pass, and EX-08 remains 19/19 at 1,400 rows. `docs/step4/TEST_MATRIX.md` maps all 98 v4 case IDs but is not yet pushed.
- B09 is `PASS_CI` on the current code/test head. Preserve the earlier API 35 p99 failure beside the later local and hosted passes; its cause remains unconfirmed. Local macOS LeakSanitizer is unavailable, while hosted Linux sanitizer passed.
- TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.

## Next

1. Validate the completed result documents, 98-ID crosswalk, and working memory; commit and push the documentation batch to PR #45.
2. Check the documentation-only run and leave PR #45 Draft; the TS10/K706+iPhone, signed update/rollback, and independent-review gates remain open.
