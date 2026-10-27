# Current Work — DiPlay STEP4 / PR #45 (2026-10-27)

## Scope

- Continue in the existing open Draft PR #45 on `codex/step4-pr-00-test-baseline`; keep its fixed base and do not merge or mark release-ready.
- Treat the attached implementation specification as project requirements. Keep B05–B12 order, user-confirmed C-00 status, and independent review `NOT_RUN`.
- Keep DSP OFF/new-track output byte-exact legacy PCM16 and MEDIA-only routing.

## State

- Workflow-sensitive run #490 passed the full host, sanitizer, unit/build, JNI/R8 and API 35 lanes on PR #45 before the latest test-only timing diagnostic. Its reports confirm 865 unit tests and 20 API 35 instrumentation tests passed.
- Added per-block thread-CPU p99 beside wall p99 in the DSP instrumentation benchmark. Local API 35 arm64 runs still fail benchmark wall-p99 assertions; the latest focused class reports 10.9–18.06 ms thread-CPU p99 while wall p99 is 27.5–147.6 ms. The 21.332/23.218 ms wall ceilings remain unchanged; this points to timing gaps but does not establish their cause or target-device behavior. Current test-code CI is pending.
- Preserve run #490's 41.636 ms single-run tail, local prior 84.009 ms tail, and the earlier failed p99 run. Local macOS LeakSanitizer is unavailable; hosted Linux sanitizer passed.
- Software implementation and its regression coverage are recorded in `completed.md` and the Step4 result package.
- TS10/K706+iPhone acceptance, SH-M27 rendering, release signing/update rollback, and independent review remain externally gated.

## Next

1. Run the workflow-sensitive host/API 35 gate for the new test-only timing metric, then update B09 with its artifact and CI result.
2. Keep PR #45 Draft and G8 `NO_GO_THIS_RUN`; device acceptance and signed update still require TS10/K706+iPhone hardware and authorized signing credentials.
