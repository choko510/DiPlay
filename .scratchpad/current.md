# Current Work

## DiPlay STEP4 v3 — PR-00

- Fixed source baseline: fork main SHA 95ba398f95ee104527812cd2b095977d71c51f98; work branch codex/step4-main-v3 was created from that SHA. Prior PR branches and their test results are excluded from implementation evidence.
- Formal plan: C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md, SHA-256 7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73.
- PR-00 adds only test fixtures/tests, baseline docs, run-scoped instrumentation log capture, and a draft/static/full CI classifier. No production source changes.
- Main baseline: shared/common 453/136 tests; both app Debug lint and Debug/Benchmark builds plus JNI mapping pass; native DSP unsanitized pass; sanitizer is BLOCKED_ENV because local MSYS2 lacks libasan/libubsan; API36.1 emulator instrumentation is 16/16. See docs/step4/BASELINE.md.
- PR-00 fixture regression: shared/common 464/136 tests, 0 failures/errors/skips; eleven new fixture tests. CI classifier tests 10/10. Marker-scoped API36.1 instrumentation 16/16 with 120 fresh benchmark records.
- Hosted CI speed results are pending; the local timings are not end-to-end Actions timings. Emulator p99 varies between repeated runs and is not head-unit evidence.
- Hardware gates (TS10/K706/iPhone, physical USB/CarPlay/audio, signed update and rollback rehearsal) remain NOT_RUN. Continue the formal dependency order; do not start EX-01 until PR-00 review and CI are recorded.
