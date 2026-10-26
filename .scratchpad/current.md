# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- Workflow-sensitive run #490 passed all required host, sanitizer, build, JNI/R8 and API 35 jobs on PR #45. Retained reports confirm 865 unit tests (659 Shared + 206 Common) and 20 API 35 tests (19 Shared + 1 Common), all passing; code/test HEAD remains `52799604`.
- F8-01/02/03, DSP Split measurement fixes and VIDEO-02 regression are recorded in `completed.md` and the Step4 result package. Preserve the earlier API 35 p99 failure beside later passes; its cause remains unconfirmed. Local macOS LeakSanitizer is unavailable, while hosted Linux sanitizer passed.
- The test matrix maps all 98 v4 case IDs. TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.

## Next

1. Keep PR #45 Draft and G8 `NO_GO_THIS_RUN`; wait for the supported TS10/K706+iPhone matrix, release-signing credentials, and independent review.
2. If new device or review evidence arrives, update only the applicable gate records and preserve the 98-ID crosswalk.
