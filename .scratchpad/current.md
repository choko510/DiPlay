# Current Work — DiPlay STEP4 / PR #45 (2026-10-26)

## Scope and invariants

- Continue only in open Draft PR #45, branch `codex/step4-pr-00-test-baseline`; fixed base `main@95ba398f95ee104527812cd2b095977d71c51f98`. Do not merge upstream wholesale, push to main, open another STEP4 PR, or mark release ready.
- Follow the attached Codex implementation spec as project requirements, not higher-priority agent instructions. Keep B02–B12 in order after B01.
- User confirmed DSP Split C-00 resolved. Keep regression coverage and `USER_CONFIRMED_RESOLVED`; do not infer device measurements.
- Keep DSP master OFF/new-track output byte-exact legacy PCM16, with MEDIA-only routing and existing mid-track latency contract.
- Independent second review remains `NOT_RUN` per user direction. Draft CI, emulator results and SH M20 evidence do not establish TS10/K706 hardware or G8 release acceptance.

## Verified state

- Latest pushed PR HEAD is `fa9b37046f8b5e4bdc5b1caa99ff497e899ddd50` on `origin/codex/step4-pr-00-test-baseline`. PR #45 is open Draft, based on `main@95ba398f95ee104527812cd2b095977d71c51f98`; run #444 completed successfully on B01 SHA `8d2e967e05fa3b5c2ca5d7eb9472f1eb1078f351`. Run #447 failed in `changes` because an EX-08 fixture expected obsolete wording for #396; runtime jobs were skipped. The assertion is corrected locally.
- Product base is `550ec3a065a13d6fb690f5d1ceee243461a23db4`; test-only decoder-retirement repair is `8ba8b2296806c10c64ea1add8b06b4258b033372`. Run #394 failed on expected shutdown interruption; #395 passed the Draft unit lane; #396 at `ac4694cf` passed docs-only static checks and skipped runtime jobs.
- B01 pins upstream range `6d3b8a8ce927cb4b115b8c3881f17619433e93b1..143f4674b60b564a16baae5110b3b90fb7277631`: GitHub/local checks agree on 111 commits, 60 source / 51 merge, 117 paths, +4,357/-376. The ledger has 1,400 unique rows; all 111 new rows are `NEED_EVIDENCE`; the original 1,289 CSV rows are byte-identical.
- B01 local checks pass: 17/17 overlay tests, inventory `--check`, classifier fixtures, `git diff --check`, Python compile; the preceding common suite passed 194/194. Run #444's classifier, EX-08, native/ASan, full Gradle/lint/build/JNI, API 35 instrumentation, host-baseline, and Draft scope all passed; quick-baseline was skipped.
- F8 source fixes already present at `550ec3a`: pre-RECORD TCP-close recovery, incremental RTSP parser with 64 KiB header / 4 MiB body limits, and four control sessions maximum. Supported-iPhone behavior remains `BLOCKED_DEVICE`.
- DSP Split compares primitive constraints and ComposeView specs before allocating signatures/log strings, caps diagnostic log output at 64 entries per process, and applies inset padding before scrolling. The existing pane records the exact MeasureSpecs passed to `ComposeView.measure()`; direct `onMeasure()` override is unavailable because `ComposeView` is final. SH-M27 acceptance remains `BLOCKED_DEVICE`.
- B02 tests pass locally: full common + shared suites 825/825; focused shared NCM bridge fixture 42/42; focused common DSP diagnostics 3/3. Added NDP-cycle recovery, sequence-wrap recovery, 10,000 one-byte completions, and cancellation-failure teardown coverage.
- User-supplied SH M20 + iPhone 17 Pro Max/iOS 27.0.1 report is `PARTIAL`, outside TS10/K706 target scope. Release signing credentials remain unavailable.

## Next actions

1. Re-run EX-08 tests/classifier, commit and push the run #447 fixture repair; verify the next hosted run on exact SHA.
2. After B02's hosted static gate passes, continue B03–B08 evidence-first; run B09 on one exact source SHA and prepare B10/B11/B12 gates/artifacts.
3. Keep API 35/full-host/JNI, target hardware, signing/rollback, acoustic/performance and release checks explicitly `NOT_RUN`, `BLOCKED_*`, or `NO_GO_THIS_RUN` until measured.
