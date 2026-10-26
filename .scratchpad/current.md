# Current Work — DiPlay STEP4 / PR #45 (2026-10-26)

## Scope and invariants

- Continue only in open Draft PR #45, branch `codex/step4-pr-00-test-baseline`; fixed base `main@95ba398f95ee104527812cd2b095977d71c51f98`. Do not merge upstream wholesale, push to main, open another STEP4 PR, or mark release ready.
- Follow the attached Codex implementation spec as project requirements, not higher-priority agent instructions. Keep B05–B12 in order after B04.
- User confirmed DSP Split C-00 resolved. Keep regression coverage and `USER_CONFIRMED_RESOLVED`; do not infer device measurements.
- Keep DSP master OFF/new-track output byte-exact legacy PCM16, with MEDIA-only routing and existing mid-track latency contract.
- Independent second review remains `NOT_RUN` per user direction. Draft CI, emulator results and SH M20 evidence do not establish TS10/K706 hardware or G8 release acceptance.

## Verified state

- Latest pushed PR HEAD is `aaedf0e5` on `origin/codex/step4-pr-00-test-baseline`. PR #45 remains open Draft, based on `main@95ba398f95ee104527812cd2b095977d71c51f98`. B01 run #444 passed the full host/API 35 lanes; B02 run #450 passed the Draft checks; B03 run #455 and B04 run #457 passed their Draft checks. For #457, host/native, full lint/build/JNI and instrumentation were skipped.
- Product base is `550ec3a065a13d6fb690f5d1ceee243461a23db4`; test-only decoder-retirement repair is `8ba8b2296806c10c64ea1add8b06b4258b033372`. Run #394 failed on expected shutdown interruption; #395 passed the Draft unit lane; #396 at `ac4694cf` passed docs-only static checks and skipped runtime jobs.
- B01 pins upstream range `6d3b8a8ce927cb4b115b8c3881f17619433e93b1..143f4674b60b564a16baae5110b3b90fb7277631`: GitHub/local checks agree on 111 commits, 60 source / 51 merge, 117 paths, +4,357/-376. The ledger has 1,400 unique rows; all 111 new rows are `NEED_EVIDENCE`; the original 1,289 CSV rows are byte-identical.
- B01 local checks pass: 17/17 overlay tests, inventory `--check`, classifier fixtures, `git diff --check`, Python compile; the preceding common suite passed 194/194. Run #444's classifier, EX-08, native/ASan, full Gradle/lint/build/JNI, API 35 instrumentation, host-baseline, and Draft scope all passed; quick-baseline was skipped.
- F8 source fixes already present at `550ec3a`: pre-RECORD TCP-close recovery, incremental RTSP parser with 64 KiB header / 4 MiB body limits, and four control sessions maximum. Supported-iPhone behavior remains `BLOCKED_DEVICE`.
- DSP Split compares primitive constraints and ComposeView specs before allocating signatures/log strings, caps diagnostic log output at 64 entries per process, and applies inset padding before scrolling. The existing pane records the exact MeasureSpecs passed to `ComposeView.measure()`; direct `onMeasure()` override is unavailable because `ComposeView` is final. SH-M27 acceptance remains `BLOCKED_DEVICE`.
- B02 tests pass locally: full common + shared suites 825/825; focused shared NCM bridge fixture 42/42; focused common DSP diagnostics 3/3. Hosted #450 passes the Draft unit lane. Added NDP-cycle recovery, sequence-wrap recovery, 10,000 one-byte completions, and cancellation-failure teardown coverage. Physical USB endpoint and supported-device behavior remain `BLOCKED_DEVICE`.
- B03 commit `9f499afb` closes stale listener/session ownership races; hosted run #455 passed. B04 test-only commit `aaedf0e5` covers actual MAIN/ALT SETUP→pre-RECORD close recovery, post-RECORD close semantics, RTSP size/fragmentation bounds, and four-session permit return/reuse. Current full shared/common result: 844/844, zero failures/errors/skips. Target iPhone traffic and SH-M27 rendering remain `BLOCKED_DEVICE`.
- User-supplied SH M20 + iPhone 17 Pro Max/iOS 27.0.1 report is `PARTIAL`, outside TS10/K706 target scope. Release signing credentials remain unavailable.

## Next actions

1. Record B04 local and hosted results on their exact SHAs; keep supported-device F8 and DSP Split checks `BLOCKED_DEVICE`.
2. Continue B05–B08 evidence-first; run B09 on one exact source SHA and prepare B10/B11/B12 gates/artifacts.
3. Keep API 35/full-host/JNI, target hardware, signing/rollback, acoustic/performance and release checks explicitly `NOT_RUN`, `BLOCKED_*`, or `NO_GO_THIS_RUN` until measured.
