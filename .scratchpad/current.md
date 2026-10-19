# Current Work

## DiPlay STEP4 / DSP series

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`. Preserve all PR-00..PR-08, EX-01..EX-08, and DSP-00..05 items and dependencies; report unverified hardware gates as such.
- Fixed baseline: fork `main` / `origin/main` `95ba398f95ee104527812cd2b095977d71c51f98`. Current branch `codex/step4-dsp-00-baseline` (draft DSP-00 PR #39) was created directly from that commit; no unrelated open PR code is used as its base. Do not push to `main`.
- Prior staged work is recorded in PRs #20–38; those drafts remain unmerged. PR-08 still records `NOT_RELEASE_READY` pending target-device, rollback, signing/update, and soak gates.
- Current stage: DSP-00 baseline is locally complete in draft PR #39. Production DSP behavior and schema are unchanged; a renderer-boundary test covers master-OFF exact PCM16/zero latency across rates, channels, and frame sizes. `docs/step4/DSP_BASELINE.md` records the main graph/schema. Next is DSP-01 Seat Focus/L-R Time Alignment.
- DSP-00 checks: shared/common 453 + 136 tests pass; mobile/automotive lint, debug/benchmark builds, JNI map, and public-tree pass; API 36.1 emulator instrumentation 16/16; NDK C test and ASan/UBSan test pass on emulator. Host `scripts/test_dsp_native.sh` remains `NOT_RUN` because WSL cannot start (`HCS_E_SERVICE_NOT_AVAILABLE`) and no Windows host C compiler is installed.
- DSP-01 constraints: preserve master-OFF PCM/latency, 2-channel limit, MEDIA-only DSP, profile schema v3/future-schema safety, playback-clock/Convolver latency, and no allocations on the audio path.
- Remaining physical/release checks include TS10/K706+iPhone audio/AV, all full/split cycles, measurement-backed DSP, rollback, update/signature, and 30–60 minute soak. Overall is not release-ready.
