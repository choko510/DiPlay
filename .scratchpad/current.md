# Current Status — DiPlay DSP Studio PR #52 (2026-10-29)

## Latest source and validation

- Independent review baseline: `f40d51ea82f6129c1455f1ae533de526633025f8`. Latest product/test head: `419fa45e5dc15fa5a135f0693ffa13bab59f2056`, integrating fix commits `a0cb2d56` and `f5673dd7`.
- Hosted run [#584](https://github.com/choko510/DiPlay/actions/runs/40723483149) passed at `419fa45e`: changes, Native, ASan/UBSan, Shared/Common, lint, Debug/Benchmark builds, JNI/R8, host-baseline, Release verification and API 35 emulator instrumentation. Its first attempt was cancelled during Common instrumentation; failed/cancelled jobs were rerun successfully. `quick-baseline` was skipped because the full check ran.
- Current PR tip `51d2caa7` adds only report/working-memory updates. Docs-only run [#586](https://github.com/choko510/DiPlay/actions/runs/40729323851) passed changes/check/Release verification; runtime lanes were skipped, so source/test evidence remains run #584.
- Local gates pass: Native DSP/Dynamic EQ, Shared/Common 734/734 + 357/357, mobile/automotive lint and Debug/Benchmark assemble, JNI mapping. Local MSYS2 sanitizer linking is unavailable because `libasan`/`libubsan` are missing; hosted sanitizer passed.
- Evidence ledger remains 170 IDs: PASS 2 / PARTIAL 162 / NOT_RUN 6. No acceptance status was promoted by these fixes.
- A separate independent post-fix review of F-01/F-02 remains `NOT_RUN`; known findings are covered by regression tests and run #584.

## Remaining acceptance

TS10/K706+iPhone audio/USB/video/Touch, route-volume correlation, A/V, CPU/RSS, physical reconnect matrix and soak remain `NOT_RUN`. PR #52 is open, unmerged and not release-ready. Do not infer device acceptance from emulator results or merge without user approval.
