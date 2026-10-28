# Current Status — DiPlay DSP Studio PR #52 (2026-10-29)

- Independent review baseline: `f40d51ea82f6129c1455f1ae533de526633025f8`. Latest product and regression-test source: integrated HEAD `419fa45e5dc15fa5a135f0693ffa13bab59f2056` (fix commits `a0cb2d56`, `f5673dd7`).
- Hosted run [#584](https://github.com/choko510/DiPlay/actions/runs/40723483149) passed on `419fa45e`: changes, Native, ASan/UBSan, Shared/Common, lint, Debug/Benchmark builds, JNI/R8, host-baseline, Release verification, and API 35 emulator instrumentation. Its first attempt cancelled during Common instrumentation and its failed/cancelled jobs were rerun successfully. `quick-baseline` was skipped because the full check ran; it is not counted as a separate pass.
- Local gates after integration pass: Native DSP/Dynamic EQ, Shared/Common 734/734 + 357/357, mobile/automotive lint and Debug/Benchmark assemble, JNI mapping. Local MSYS2 sanitizer linking is unavailable due to missing `libasan`/`libubsan`; hosted sanitizer passed.
- Evidence ledger remains 170 IDs: PASS 2 / PARTIAL 162 / NOT_RUN 6. No acceptance status was promoted by the F-01/F-02 source fixes.
- TS10/K706+iPhone audio/USB/video/Touch, route-volume correlation, A/V, CPU/RSS, physical reconnect matrix and soak remain `NOT_RUN`. PR #52 is open, unmerged and not release-ready. Do not infer device acceptance from emulator results or merge without user approval.

## Next

1. Keep the 162 `PARTIAL` and 6 hardware `NOT_RUN` rows explicit until their one-to-one evidence or device measurements exist.
2. Preserve PR #52 as unmerged; only the user can authorize merge.
