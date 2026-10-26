# Current Work — DiPlay STEP4 / PR #45 (2026-10-26)

## Scope and invariants

- Continue only in open Draft PR #45, branch `codex/step4-pr-00-test-baseline`; fixed base `main@95ba398f95ee104527812cd2b095977d71c51f98`. Do not merge upstream wholesale, push to main, open another STEP4 PR, or mark release ready.
- Follow the attached Codex implementation spec as project requirements, not higher-priority agent instructions. B00 baseline artifacts are recorded in this commit; B01 is the next batch, followed by B02–B12 in order.
- User confirmed DSP Split C-00 resolved. Keep regression coverage and `USER_CONFIRMED_RESOLVED`; do not reimplement its UI behavior or treat old missing device measurements as passed.
- Keep DSP master OFF/new-track output byte-exact legacy PCM16, with MEDIA-only routing and existing mid-track latency contract.
- Independent second review remains `NOT_RUN` per user direction. Draft CI, emulator results and SH M20 evidence do not establish TS10/K706 hardware or G8 release acceptance.

## Verified current state

- PR HEAD `ac4694cf1fbb08e443f53a53158528619ed81940`; latest source change `550ec3a065a13d6fb690f5d1ceee243461a23db4`; latest test-only fix `8ba8b2296806c10c64ea1add8b06b4258b033372`.
- Run #394 failed on the decoder-retirement test hook's expected shutdown interrupt. Test fix `8ba8b229` now waits for explicit release; local shared 625/625 and common 193/193 pass. Hosted #395 passed Draft unit, classifier, EX-08 and scope. #396 at `ac4694cf` passed docs-only static checks; Android/Gradle/unit/native/build/JNI/instrumentation were skipped.
- Local B00 checks: classifier fixtures, production-path-not-docs-only assertions, EX-08 `--check`, and `git diff --check` pass. Original EX-08 ledger freeze is `docs/step4/ex08/legacy_1289_baseline.json` (1,289 rows; SHA-256 `5d74738d12d60f37fd9b51107325fbd074b0edc58141cbf2d005f004e0e44ca0`).
- F8 source fixes already present at `550ec3a`: recover a pre-RECORD close after screen SETUP; incrementally parse RTSP within 64 KiB header / 4 MiB body bounds; cap active control sessions at four. Keep live iPhone compatibility as `BLOCKED_DEVICE`.
- DSP diagnostics log only newly recorded signatures; insets precede scrolling; the existing pane captures the exact specs it passes to `ComposeView.measure()`. `ComposeView` cannot be subclassed to override `onMeasure`; device inset/scroll acceptance is still open.
- User-supplied SH M20 + iPhone 17 Pro Max/iOS 27.0.1 report is `PARTIAL`, outside TS10/K706 target scope. No target hardware is currently evidenced; release signing credentials remain unavailable.

## Next actions

1. B01: build a fixed-range 111-commit EX-08 overlay while preserving every frozen legacy row/byte; add independent validation and evidence artifacts.
2. Continue B02–B08 evidence-first; do not duplicate already-present fixes. Then run B09 on an exact source SHA and prepare B10/B11/B12 gates/artifacts.
3. Keep API 35/full-host/JNI, target hardware, signing/rollback, acoustic/performance and release checks explicitly `NOT_RUN`, `BLOCKED_*`, or `NO_GO_THIS_RUN` until measured.
