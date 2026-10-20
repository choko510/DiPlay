# Current Work

## DiPlay STEP4 v3 — main-only implementation

- Specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`, SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73`.
- Implementation base: fork `origin/main` at `95ba398f95ee104527812cd2b095977d71c51f98`. Current branch `codex/step4-pr-00-baseline-v3` was created directly from that SHA. Existing open PR branches/results are not evidence or implementation inputs.
- Active stage: PR-00 only. Test helpers are in `shared/src/test/.../step4`; PR-00 production code delta is zero. The five required `docs/step4` artifacts are present and list all U01–U75 statuses.
- Test factory: explicit Robolectric bulk-IN/OUT, queue, and requestWait scripts; unscripted USB actions throw. It covers sync/async reads, timeout with later completion of the same request, queue false at 32 KiB, null/unexpected completion, split/coalesced NTBs, pad/no-pad boundary, short/corrupt blocks, startup traces, TUN, virtual time, PCM/video, 55:45 geometry, and input-request scenarios.
- Baseline before fixtures: 453 shared + 136 common unit tests passed, 0 failures/errors/skips. Full suite after fixtures: 470 shared + 136 common passed, 0 failures/errors/skips; full tasks were forced with `--rerun-tasks`.
- Host: mobile/automotive Debug lint, Debug/Benchmark assemblies, public-tree check, JNI mapping check, and unsanitized native DSP tests pass. Local GCC ASan/UBSan is `BLOCKED_ENV` because `-lasan`/`-lubsan` are absent.
- Emulator: API 36.1 Medium Phone AVD instrumentation 16/16; 40 DSP configurations × 3 benchmark runs. This is emulator evidence only. ADB physical device count is zero; TS10/K706/iPhone, mic/Siri/Phone, real USB timing, Split 20-cycle, DSP acoustics/target performance, soak/update/signature/rollback remain `NOT_RUN`.
- Host build emits the duplicate API 37.1 platform and NDK `ndk.dir`/AGP default-version warnings; Debug/Benchmark tasks pass. MSYS2 UCRT ASan/UBSan cannot link.
- Author self-review of PR-00 is complete. The independent review is still `NOT_RUN`; the branch also needs pushing, a draft PR, and GitHub CI. Do not mark PR-00 reviewed or advance to EX-01 until the independent review and CI findings are resolved.
