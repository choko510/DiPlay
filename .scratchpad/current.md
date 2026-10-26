# Current Work — DiPlay DSP Studio PR #52 and STEP4 PR #45 (2026-10-27)

> Active goal: complete PR #52 from `docs/dsp-studio/REMEDIATION_SPEC.md`. Keep PR #52 open and unmerged until user approval. Do not modify PR #45 or `main`; PR #45 remains a separate Draft workstream.

## Scope and invariants

- Follow the active PR #52 goal objective and its attached remediation specification as project requirements. Keep formal test IDs, added test IDs, known CRs, CI evidence, and review dispositions individually traceable.
- PR #52 owns the DSP Studio integration branch. Child PRs are reviewed and may be merged into that branch.
- Hardware-only TS10/K706/iPhone acceptance remains NOT_RUN unless measurements are available; record the procedure and residual risk.
- Preserve the existing zero-latency DSP-OFF contract and avoid claiming hardware acceptance from emulator or source checks.

## Current integration state

### Historical PR #62/#63 integration evidence
- PR #62 EQ/sample-rate changes were merged into PR #52. Its callbackProfile-null instrumentation failure was traced to selecting a band from the touch-slop drag start instead of the initial pointer-down position. The fix selects from pointer-down and edits from post-slop positions; API 35 graph instrumentation passed 3/3 and hosted run #464 passed.
- PR #63 Smart Loudness route monitoring was merged into PR #52 at commit 8660c6132926f5d0c1f74e7629e2205c9d68a8ec. Its final source head c56cba9ddd379e69f82cd2696ce41578a92e1d98 passed hosted run #468, including Android instrumentation, unit tests/lint/build/R8, host baseline, and release verification.
- PR #63's earlier FullHeavyChain instrumentation attempt measured p99 31,579 us against the existing 21,332 us threshold. The same case passed on retry at that commit; the threshold was unchanged. Latest hosted instrumentation also passed.
- Independent review of PR #63's final head confirmed both prior P2 findings were fixed: only MEDIA routes publish the active playback sample rate, and route reads/listeners/release are serialized to prevent stale route publication. No P0–P2 findings remain.
- PR #52 head is 8660c6132926f5d0c1f74e7629e2205c9d68a8ec. Its base branch codex/step4-pr-00-test-baseline advanced to 15fa5e06bd96af855aa168605f2ac4b7ad2e1b6d; GitHub currently reports the PR as not mergeable with a stale base snapshot.

## Historical test status before this remediation run

- PR #62 pointer-down correction: focused API 35 instrumentation, Android-test Kotlin compilation, and unit checks passed; hosted run #464 passed.
- PR #63 route/rate corrections: focused volume-monitor, route-gate, stream-policy, and runtime-config tests passed; common/shared debug assemblies and diff check passed; hosted run #468 passed.
- PR #52 needs a fresh full hosted CI run after updating its base branch. Any failure must be diagnosed and fixed at the current exact head; do not count skipped jobs as passes.

## Superseded next actions from the pre-remediation status

1. Finish merging the current PR #45 base branch into the PR #52 integration head. Source files auto-merge; resolve and preserve both histories in the four Working Memory files.
2. Push the resolved PR #52 head and wait for its current GitHub Actions checks. Repair failures, rerun the affected checks, and confirm the final CI state.
3. Complete the requested formal/additional test ID matrix, CR ledger, phase implementation records, and four-field independent review evidence against the final PR #52 head.
4. Keep unsupported hardware checks NOT_RUN with steps and residual risks; refresh PR #52's implementation and verification summary.
5. Leave PR #52 unmerged and leave PR #45 and main unchanged.
## PR #52 remediation status

- Local implementation commits are `306e0db9`, `46917860`, and `7070a825`; the latest PR #45 base (`0f425cdc`) is merged in the working tree. Merge resolution retained both `onPlaybackRouteChanged` and `onWiredStartupTiming` callbacks from `CarPlayHostActivity`.
- Added bounded workspace/profile/preset persistence, fail-closed IR reference checks, serialized session edits, generation-fenced save and feature-flag apply, module-preset selection invalidation, EQ drag/pointer-up preview, and dirty-exit Save/Discard/Continue.
- Native Macro/Loudness headroom uses conservative bounds; Dynamic Bass is before compressor detection and still reads raw PCM. Unsupported stage-local WARM paths remain deferred.
- API 35 Common instrumentation passed 14/14. Shared instrumentation first reported Convolver64k p99 57,340 us; the isolated rerun and full 19-test rerun passed. Threshold unchanged.
- Full Common + Shared local unit tests passed. Native and ASan/UBSan passed with LeakSanitizer disabled on macOS. Final checks and PR-head CI remain pending after merge resolution.
- TS10/K706/iPhone hardware acceptance is NOT_RUN. PR #52 remains open and unmerged; PR #45 and `main` remain unchanged.

### PR #45 base status brought by the merge
- B04 test commits `aaedf0e5` and `2070b215` are pushed to `origin/codex/step4-pr-00-test-baseline`. PR #45 remains open Draft, based on `main@95ba398f95ee104527812cd2b095977d71c51f98`. B01 run #444 passed the full host/API 35 lanes; B02 run #450 passed the Draft checks; B03 run #455 and B04 runs #457/#462 passed their Draft checks. Full host/native, lint/build/JNI and instrumentation were skipped on the Draft runs. The intervening PR commits contain only verification records.
- Product base is `550ec3a065a13d6fb690f5d1ceee243461a23db4`; test-only decoder-retirement repair is `8ba8b2296806c10c64ea1add8b06b4258b033372`. Run #394 failed on expected shutdown interruption; #395 passed the Draft unit lane; #396 at `ac4694cf` passed docs-only static checks and skipped runtime jobs.
- B01 pins upstream range `6d3b8a8ce927cb4b115b8c3881f17619433e93b1..143f4674b60b564a16baae5110b3b90fb7277631`: GitHub/local checks agree on 111 commits, 60 source / 51 merge, 117 paths, +4,357/-376. B07 has since recorded decisions for every SHA and classified all 51 merges; the original 1,289 CSV rows remain byte-identical.
- B01 local checks pass: 17/17 overlay tests, inventory `--check`, classifier fixtures, `git diff --check`, Python compile; the preceding common suite passed 194/194. Run #444's classifier, EX-08, native/ASan, full Gradle/lint/build/JNI, API 35 instrumentation, host-baseline, and Draft scope all passed; quick-baseline was skipped.
- F8 source fixes already present at `550ec3a`: pre-RECORD TCP-close recovery, incremental RTSP parser with 64 KiB header / 4 MiB body limits, and four control sessions maximum. Supported-iPhone behavior remains `BLOCKED_DEVICE`.
- DSP Split compares primitive constraints and ComposeView specs before allocating signatures/log strings, caps diagnostic log output at 64 entries per process, and applies inset padding before scrolling. The existing pane records the exact MeasureSpecs passed to `ComposeView.measure()`; direct `onMeasure()` override is unavailable because `ComposeView` is final. SH-M27 acceptance remains `BLOCKED_DEVICE`.
- B02 tests pass locally: full common + shared suites 825/825; focused shared NCM bridge fixture 42/42; focused common DSP diagnostics 3/3. Hosted #450 passes the Draft unit lane. Added NDP-cycle recovery, sequence-wrap recovery, 10,000 one-byte completions, and cancellation-failure teardown coverage. Physical USB endpoint and supported-device behavior remain `BLOCKED_DEVICE`.
- B03 commit `9f499afb` closes stale listener/session ownership races; hosted run #455 passed. B04 test commits `aaedf0e5` and `2070b215` cover actual MAIN/ALT SETUP→pre-RECORD close recovery, post-RECORD close semantics, RTSP size/fragmentation including the production 4 MiB+1 rejection, and four-session permit return/reuse. Current full shared/common result: 845/845, zero failures/errors/skips. Target iPhone traffic and SH-M27 rendering remain `BLOCKED_DEVICE`.
- B05 code commit `ae63a36566871e1197cf9c736c411307aa0bbef7` bounds active/retiring Surface generations to eight, reserves the live generation, and defers only the newest Texture under ACK pressure. A pre-fix 10,000-notice probe reproduced unbounded retention at `d274435`. Gate tests cover 1/100/1,000/10,000 churn, duplicate destroy, MAIN/ALT ACK, and same-Texture replacement without early Texture release. Full common/shared suites pass 849/849. Hosted run #469 at `660c6164` passed `changes`, `check`, `quick-baseline`, and Draft scope; host-baseline and Android instrumentation were skipped.
- B06 test commit `686516f5a3fbd220822c89e394c5c12222a24eb8` adds a production capture-loop regression: three scripted zero reads emit no packet, and one positive 640-byte PCM read resumes one packet. Initial DSP-OFF exact PCM16/no-prepare/zero-latency behavior is now checked for 44.1/48 kHz mono/stereo. Shared suite: 646/646, zero failures/errors/skips. No production behavior changed. The latest DSP Split check confirms duplicate signatures do not log, insets precede scrolling, and the exact child MeasureSpecs are passed to ComposeView.measure(); SH-M27 acceptance remains blocked.
- B07 reviews all 111 source/merge SHAs against fixed main. Overlay dispositions are 2 `FORK_SUPERIOR`, 16 `SKIP_OEM`, 56 `REJECT_UNSAFE`, 37 `NEED_EVIDENCE`, 0 `ADAPT`; all 51 merges are classified. EX-08 tests pass 19/19, inventory `--check` passes at 1,400 rows, and the legacy 1,289-row prefix is unchanged. No new source met the adaptation evidence threshold.
- B08 adds fixed-size, attempt-relative Stage3 timing with attach-generation checks, exact successful FULL OUT accounting, conservative SYN classification, and a three-line in-memory report. Full shared/common suites pass 862/862; two host per-thread measurements found 16.6–21.4 ns/packet incremental CPU and no incremental timing allocation. No transmission, retry, timer, watchdog, or phase behavior changed. TS10/K706 causal timing remains `BLOCKED_DEVICE`.
- B09 local verification at code/test head `0f425cdc`: 862/862 JVM/Robolectric tests, overlay checks, unsanitized native, ASan/UBSan with LSan disabled, both app lint/Debug/Benchmark builds, both JNI mappings, Common API 35 (1/1), and the latest Shared API 35 repeat (19/19) pass. The earlier same-head Shared run failed three p99 cases and crashed; the outlier was not reproduced and its cause is unconfirmed. Run #476 passes its static/unit jobs but skips the full-host and instrumentation lanes; docs-only run #477 passes `changes`/`check`/Draft scope and skips quick-baseline/runtime lanes. LSan remains unavailable locally. B09 is `PARTIAL`.
- B10 HW-01–HW-13 procedures and explicit blocked statuses are in `docs/step4/B10_DEVICE_ACCEPTANCE.md`; B11 conditional no-go decisions are in `docs/step4/B11_CONDITIONAL_NO_GO.md`. `CODEX_IMPLEMENTATION_RESULT.md` and `FINAL_GATE_SUMMARY.md` record the current no-go decision. Release signing and independent review remain unavailable/not run.
- User-supplied SH M20 + iPhone 17 Pro Max/iOS 27.0.1 report is `PARTIAL`, outside TS10/K706 target scope. Release signing credentials remain unavailable.

## Next actions

1. Keep B09 `PARTIAL`: retain both API 35 Shared outcomes, with the latest 19/19 pass, and record that full hosted lanes remain skipped under Draft scope and LSan is unavailable locally.
2. Run HW-01–HW-13 only after supported TS10/K706 + iPhone hardware is available; obtain authorized release signing credentials for HW-11.
3. Keep the PR Draft and G8 `NO_GO_THIS_RUN` until the API 35 lane, hardware matrix, signed rollback, and independent review have evidence.
