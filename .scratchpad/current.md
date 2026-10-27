# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- Workflow-sensitive run #495 passed the full host, sanitizer, unit/build, JNI/R8 and API 35 lanes on code/test HEAD `6392b56f`; its artifacts confirm 865 unit tests and 20 API 35 instrumentation tests passed. All 56 x86_64 median wall-p99 gates passed (max median 15.440 ms, max single-run p99 17.720 ms, max thread-CPU p99 4.760 ms).
- Added per-block thread-CPU p99 beside wall p99 in the DSP instrumentation benchmark. The focused local API 35 arm64 class fails four wall-p99 assertions; matching thread-CPU p99 medians are 10.9–18.06 ms while wall p99 is 27.5–147.6 ms. The 21.332/23.218 ms wall ceilings remain unchanged; this points to time outside the measuring thread but does not establish its cause or target-device behavior.
- Preserve run #490's 41.636 ms single-run tail, local prior 84.009 ms tail, and the earlier failed p99 run. Local macOS LeakSanitizer is unavailable; hosted Linux sanitizer passed.
- Software implementation and its regression coverage are recorded in `completed.md` and the Step4 result package.
- TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.

## Next

1. Commit and push the updated B09/B12 evidence record; inspect its docs-only CI result without treating skipped runtime jobs as new runtime evidence.
2. Keep PR #45 Draft and G8 `NO_GO_THIS_RUN`; device acceptance and signed update still require TS10/K706+iPhone hardware and authorized signing credentials.
