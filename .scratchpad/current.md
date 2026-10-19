# Current Work

## DiPlay STEP4 from fixed main

- Treat the attached v3 plan as the implementation spec. Use only the fixed fork-main baseline; do not use open PR implementations or test results.
- Current stage: PR-00 test infrastructure and baseline. Changes so far are test-only fixtures, baseline docs, matrix, ownership, flags, and upstream decisions; production files are unchanged.
- Local baseline: shared/common unit tests were 453/136 before fixtures and pass at 464/136 after fixtures, with zero failures/errors/skips. Mobile/automotive Debug lint, Debug/Benchmark assemblies, JNI mappings, public-tree scan, and unsanitized host native C pass.
- API 36.1 x86_64 AVD instrumentation passes 16/16; fresh filtered logcat has 120 benchmark records. Host ASan/UBSan is blocked by missing MSYS2 runtimes; physical TS10/K706/iPhone checks remain `NOT_RUN`.
- PR-00 local review is complete: only test sources, baseline deliverables, and working memory changed. The independent second reviewer is `NOT_RUN` per prior user direction.
- Next: commit/push only this dedicated branch, create the PR-00 draft against `main`, and inspect its full CI before moving to EX-01.
