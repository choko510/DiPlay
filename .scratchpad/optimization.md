# Performance and Optimization

> **2026-10-23 status:** Earlier local API 36.1 benchmarks are historical; they do not override unstable hosted API 35 p99 observations. Later changes aligned render/benchmark thread priority and removed retire-time work from the audio caller. Physical TS10/K706 CPU, underrun, latency and A/V gates are NOT_RUN. Consult [current](current.md) before claiming performance acceptance.

## 2026-10-28 — Bound decoder output waits and add video-path metrics

- Replaced the unconditional 5 ms queue timeout with a 100 ms FIFO wait only when there is neither an estimated pending output nor recent codec activity. When the bounded pending count reaches zero, retain a 50 ms drain grace from the latest input or decoded-picture output and use at most 8 ms output-dequeue waits during that interval. The count remains a wake-up hint because codecs can drop, reorder or emit multiple outputs per input. The 500 ms pending limit and 250 ms/60-frame/8 MiB backlog bounds remain unchanged.
- Reuse one worker-owned `MediaCodec.BufferInfo` per decoder. Diagnostics include five-second queue/dequeue timing, thread CPU, PTS-correlated `receivedToCodecSurfaceReleaseMs` p50/p95/p99, unmatched/overwritten correlation fractions, pending peaks/timeouts, process allocation/GC/PSS (debug-tag opt-in), and per-type MAIN/ALT recovery outcomes. The latency metric ends after `releaseOutputBuffer(..., true)` returns; it is not compositor/panel presentation.
- Source-level idle cadence reaches 10 polls/s after recent input/output activity has been quiet for 50 ms; during the bounded grace it can check at up to 125 polls/s. No live video stream was available for an actual CPU, allocation, latency, final-frame or p99 comparison. Do not call the expected cadence change a measured performance gain.
- Independent-review F-05 moves percentile sorting, formatting and logger calls outside the synchronized stats window after copying/resetting compact counters and bounded samples. A deliberately blocked logger test confirms frame arrival proceeds; no wall-time, CPU or live-stream contention gain was measured.
- F-01 carries known algorithmic latency duration into a safe PCM fallback after DSP retirement failure and rescales the frame count for the next sample rate. This is correctness/fallback evidence, not a performance comparison.

## PR-DSP-12 — DSP pipeline profiling and performance gate (historical API 36.1)

Measured 10 configurations (Gain, PEQ15, PEQ+Compressor+Limiter, Bass+Stereo+Mono Bass, 3-band dynamics, Dynamic EQ, Convolver 4k/16k/64k, and the full heavy chain) at 44.1/48 kHz, Mono/Stereo: 40 cases total. Each case used 64 warmup blocks, 64 allocation-check blocks, and 256 measured 512-frame blocks on the Medium Phone API 36.1 x86_64 emulator. Fixed 20 µs histograms report native processing separately from PCM conversion + JNI + native + PCM16 conversion.

Final post-main-sync run worst cases:

- Light group, PEQ15 at 48 kHz Stereo: native p50/p95/p99/max 380/540/680/905 µs; full pipeline 680/900/1,100/1,243 µs.
- Heavy group, Convolver64k at 48 kHz Stereo: native 2,000/2,260/2,600/2,790 µs; full pipeline 2,280/2,560/2,920/3,117 µs.
- FullHeavyChain at 48 kHz Stereo: native 1,400/1,680/1,920/2,055 µs; full pipeline 1,660/2,000/2,260/2,369 µs.

All final-run p99 values stayed below the 2.67 ms light and 5.33 ms heavy budgets. Two repeated complete matrices remained within budget; the first run's isolated high p99 samples did not recur. The 64-block steady-state Android allocation check reported zero Kotlin allocations for every case. Source review found no native allocator calls in the process graph; convolver/engine allocation remains in prepare/create and close paths.

No DSP math, chunk-size, FFT-partition, JNI, or NEON changes were justified: measured p99 budgets passed, and no stable regression hotspot remained after repeat runs. Keep the portable scalar path and 128-frame convolver latency.
# Performance Optimization Ledger

## 2026-10-27 — Measure wired startup timing-path overhead

- A warmed two-million-iteration, five-round host fixture compared the bare parser with timing-disabled and timing-enabled classifier/recorder paths using per-thread CPU/allocation counters. The two timing lanes were 16.6–21.4 ns/packet above the bare baseline; timing ON versus OFF was within host-run variation, with no measured incremental allocation beyond counter precision.
- Treat these as host-JVM overhead bounds, not an Android or TS10/K706 profile. Keep the diagnostic path fixed-size and attempt-local; profile target CPU/heap separately before changing production behavior.

## 2026-10-25 — Keep DSP fallback and Surface churn work bounded

- Replaced fallback-time full DSP graph preparation with a conservative scalar headroom estimate over bounded profile fields. It avoids EQ frequency scans and convolution sample copies on track-generation changes and no longer rethrows invalid PEQ configuration from the fallback path. Shared/common Kotlin compilation passed; no p99, allocation, underrun or hardware measurements were collected.
- Moved Surface retirement callbacks out of coalesced control jobs and removed repeated accumulated-list flatten/copy work. Added a 5-second count/oldest-age diagnostic interval. Callback count is still retained until decoder acknowledgement; memory-cap/overflow policy and target churn measurements remain open.

## 2026-10-23 — Move retired DSP pipeline cleanup off the render worker

- Replaced the audio-caller's synchronized retire queue and timer scheduling with a prestarted single reaper thread and an intrusive lock-free pipeline stack. Retirement uses existing pipeline objects, and the renderer waits for the retire count to drain before adopting another graph.
- `DspLiveUpdateControllerTest.retiredGraphClosesOnDedicatedReaperWithoutBlockingTheCaller` blocks native close, verifies the retire caller returns immediately, and confirms adoption stays gated until close completes. This is a real-time path correction; TS10/K706 underrun and p99 impact remain unmeasured.

## 2026-10-23 — Bound incomplete NCM block recovery work

- Replaced the per-completion bytewise scan through a plausible incomplete NTB with checks at the validated data end and the one allowed endpoint-boundary short-pad position. This preserves a valid partial block and removes the quadratic recovery scan identified in W-04.
- Over-MTU datagram declarations are rejected against the same 1,500-byte IPv6 TUN MTU used by `CarPlayVpnService`; resynchronizing that invalid header requires two complete consecutive NTBs. `NcmUsbBridgeFixtureTest` reproduces the false-gap signature and the oversized datagram length, including fragmented reads, and passes locally.
- This is a source-level work bound plus Robolectric regression coverage; target USB CPU and endpoint behavior remain unmeasured.

## 2026-10-23 — Match DSP benchmark priority to the production audio worker

- API 35 run #367's uploaded logcat showed 48 kHz stereo `FullHeavyChain` p50 around 9.5 ms with repeated wall-time p99 tails of 26–69 ms, while 44.1 kHz stereo stayed near 2.9 ms p50 and below 4.1 ms p99. The product code was unchanged from passing run #361; this points to scheduling pressure in the normal-priority instrumentation thread rather than a newly introduced DSP arithmetic regression.
- The audio renderer now requests Android's audio thread priority, and `DspPipelineBenchmarkInstrumentedTest` measures on that same priority. The 2× processing-block p99 ceiling remains unchanged. Local shared/common suites passed 570/178; latest API 36.1 instrumentation passed 5/5 with FullHeavyChain 48 kHz stereo p99 <=4.32 ms and Convolver64k <=5.02 ms. Hosted API 35 run #368 passed with median per-run p99 values of 3.28 ms and 4.92 ms respectively, below the 21.332 ms ceiling.
- The benchmark instrumentation file is now classified workflow-sensitive so a draft change runs the benchmark instead of silently deferring it; classifier fixtures pass locally.

## 2026-10-22 — Run full draft unit modules in one Gradle invocation

- Production/mixed draft changes with `quick_scope=both` and no class filter previously invoked Gradle once for `shared` and then again for `common`. Run #340 on `c0524c3` passed both unit suites, but the check job took 4m13s and its draft unit-test step took 3m17s.
- The `both`/`all` branch now invokes `:shared:testDebugUnitTest` and `:common:testDebugUnitTest` together with `--parallel`. Test-only class-filtered changes still use per-module invocations so a module does not receive another module's `--tests` patterns. Local combined runs passed 552 shared + 160 common in 1m11 and 553 shared + 165 common in 1m17; workflow YAML parsing and Git Bash `bash -n` passed.
- PR run #346 on `c28c484` selected `quick_scope=common` and passed the full common unit module; hosted Gradle reported 2m36. Native, lint/build/JNI, and emulator paths skipped. This validates module-only selection, not a controlled time reduction, and does not exercise the hosted `both`/`all` command.
- PR run #347 on `5673ac4` selected `quick_scope=both` with `quick_test_filters=all` and passed both Gradle unit tasks in one `--parallel` invocation; Gradle reported `BUILD SUCCESSFUL in 3m`. It exercised the hosted combined branch. Run #340's sequential step was 3m17, but test counts, cache state, and commit differ, so this is not a controlled wall-time comparison.
- PR run #349 on `0c09b5a` also selected `quick_scope=both` and passed both modules after the Bluetooth identifier-log cleanup; Gradle reported `BUILD SUCCESSFUL in 2m45`. This confirms the combined hosted path after the privacy change; the difference from #347 is not a controlled timing comparison.

## 2026-10-22 — Batch related EX-08 audio reviews into one PR update

- Reviewed rebuffer source/follow-up/merge rows 1133–1135 and telephony source/merge rows 1136–1137 together, regenerated the ledger once, and pushed the combined audit as commit `0907ec0` to PR #45. GitHub Actions run #336 finished successfully in 18 seconds; public-tree and EX-08 checks passed while Gradle, Android SDK, native, unit-test, and emulator work skipped. This validates the audit-only workflow path, not product runtime behavior.
- Reusable workflow rule: group adjacent source, follow-up, and merge decisions by subsystem, then update overrides, generated CSV, count summaries, and PR metadata in one push. Do not start a separate CI cycle for each ledger row.

## 2026-10-22 — Filter draft runs to changed standalone test classes

- Draft updates limited to newly added or modified standalone `*Test`, `*Tests`, or `*TestCase` files in `shared/src/test` or `common/src/test` now pass those class names to Gradle, instead of rerunning the entire test module. Changes to helpers/fixtures, production sources, native code, mixed or unknown paths keep the full applicable test scope. Missing baselines, oversized diffs, API failures, pushes, manual runs, and ready-for-review checks keep their existing full-verification fallback.
- The existing `:shared:testDebugUnitTest`/`:common:testDebugUnitTest` project tasks still compile the module test sources, while Gradle executes only the selected test classes for this narrow draft case. Workflow-only run #300 skipped runtime work; run #305 was the first hosted exercise and selected only the modified MFi class.
- A local run of the modified `RemoteMfiAuthenticationClientTest` class passed 5/5 in 8 s. Hosted run #305 passed the class-filtered test step; its Gradle task took 1m20. This is not a controlled comparison with full shared-suite run #297 (Gradle 1m04), so this sample does not demonstrate lower wall time.
- GitHub Actions run #300 passed on workflow head `a27f4f0`; public-tree and EX-08 checks ran, and Android SDK, Gradle, native, unit-test, and emulator jobs skipped as expected for a workflow-only draft update. This validates the static lane, not the new class filter or its runtime savings.
- PR run #312 on `77446c7` selected only `shared|com.shilapi.xcertplay.airplay.CarPlayMediaEngineTest` after inspecting eight recent commits and passed the class-filtered lane. The test job's Gradle step took 1m06; local execution passed 14/14 in 8 s. This confirms the narrowed selection, but setup and cleanup dominate enough that no overall wall-time reduction is claimed versus differently scoped runs.
- PR run #316 on `98e4866` selected only `common|com.shilapi.xcertplay.CarPlayHostActivityLocalScaleTest` and passed. Its Gradle step took 58 s; this is confirmation that the common-only class filter works, not a controlled wall-time comparison.
- PR run #323 on `f52d528` selected only `shared|com.shilapi.xcertplay.airplay.CarPlayMediaEngineTest` and passed after the race-test synchronization was hardened. Its Gradle step took 1m11; this confirms the changed-class filter remains active for standalone shared tests, with no controlled full-suite timing comparison.
- PR run #328 selected only `shared|com.shilapi.xcertplay.media.AndroidMediaSinkStateTest` and passed the sink reset regression (7 local tests; Gradle step 1m05). This confirms class filtering for another shared test class, not an overall wall-time saving versus the full suite.
- PR run #333 selected only `shared|com.shilapi.xcertplay.media.MicrophoneUplinkRecorderFailureTest` and passed the six API 28/33 Robolectric cases; the Gradle step took 1m03. This confirms class filtering for the microphone recorder test class, without a controlled full-suite time comparison.

## 2026-10-21 — Scope draft JVM tests to test-only module changes

- PR run #268's common-only test addition ran both `shared` and `common` unit suites; the Gradle task took 2m05. Draft CI now runs the whole changed JVM test module when only `shared/src/test/java|kotlin` or `common/src/test/java|kotlin` paths changed since its successful quick baseline. Any production, mixed, unknown, native-only, missing-baseline, or large-diff case keeps the prior broad/fallback behavior. `ready_for_review` full host and instrumentation gates are unchanged.
- Run #275 passed on `b99256f`; its workflow-only change selected `quick=false / quick_scope=none`, and the public-tree/EX-08 checks passed while Android jobs skipped. This validates the fast static lane and logged scope. It does not exercise the scoped unit-test path, so the actual time saved remains unmeasured until a test-only update uses it.

## 2026-10-21 — Skip unrelated native suites in draft CI

- Compare changes since the latest successful quick baseline and run host native DSP plus ASan/UBSan only when DSP C, KissFFT, native DSP test files, or `scripts/test_dsp_native.sh` changed. Keep shared/common unit tests on every code-changing draft update; full host, main-push, and manual runs still include all native checks. Missing baselines, API errors, and large diffs fall back to full verification.
- Run #252 passed on `a130158` in 2m07. Shared/common draft unit tests passed while native DSP and ASan/UBSan were skipped, confirming the non-native path. This run did not reduce elapsed time against run #242's 2m00; the saved work for future Kotlin-only edits is the native test/sanitizer execution, with its wall-time impact unmeasured.

## 2026-10-21 — Trim draft SDK setup and workflow-only checks

- Draft unit tests use API 37.0/37.1 and Build Tools 36.0.0 without platform-tools or NDK 27. Gradle dry-run confirmed the shared/common unit-test task graph contains no external native build tasks. In run #254, Android SDK setup took 21s versus 36s in #252, and the unit tests passed; total time was 2m18 because Gradle test and cache-cleanup times varied upward, so the package trim did not reduce total wall time on its own.
- Classify `.github/workflows/*` as workflow-only so the active Actions workflow still runs public-tree and EX-08 checks but skips product runtime setup/tests. Run #255 passed in 32s, compared with 2m18 for #254's workflow-only edit; the 1m46 reduction includes the skipped Gradle/native work. Product-source, main-push, and manual verification paths remain unchanged.

## 2026-10-20 — Shorten draft PR feedback

- Run #151 logs showed the host check job taking 2m45s (Gradle command 1m04s) and the instrumentation job taking 5m12s. Run #168 then took 9m39s overall, with the Gradle unit/lint/build/JNI command alone at 7m33s, despite the emulator job being skipped.
- Keep public-tree/EX-08 checks on every PR update. Draft code updates now use native/sanitizer plus shared/common unit tests; defer app lint/build/JNI and emulator instrumentation until `ready_for_review`. Quick, full-host, and full-instrumentation markers are separate so later docs-only changes do not rerun draft tests or treat skipped checks as a full pass. Seed missing markers from the previous successful full run to avoid walking the entire history. Pushes and manual runs still run the full suite; cancel superseded PR runs.
- Run #169 passed in 2m13 with public-tree/EX-08, both host native suites, and shared/common unit tests; the app lint/build/JNI matrix and emulator job were skipped by the draft lane. Compared with run #168's 9m39 full draft host check (Gradle 7m33), this reduced end-to-end feedback by 7m26. Follow-up docs/audit runs #170–176 passed in 33s/41s/41s/44s/48s/43s/39s with runtime checks skipped, including #172 after regenerating the EX-08 ledger. Runs #184–186 completed in 48s/43s/56s, with Android/Gradle/native/unit/instrumentation work skipped. The lanes are not equivalent: the full host and emulator checks still run when the PR is ready for review.
- Run #241 passed on audit-only head `9155cb5` in 76s: public-source and EX-08 inventory checks passed; Android instrumentation, host-baseline, and quick-baseline jobs were skipped. This confirms the audit-only lane avoids runtime checks for memory and ledger updates.

## 2026-10-21 — Reduce baseline discovery latency

- Run #241's `changes` job took 63s because it queried check runs and commit parents serially while walking the PR history. The classifier now retrieves up to the latest 100 PR commit SHAs in one compare request, checks check-run markers in batches of eight, and stops when all prior baselines are found. API failures and an empty history still fall back to full verification.
- Run #242 passed in 2m00. Baseline discovery took 16s (12s for classification), inspected 96 of 100 recent PR commits, and reduced the `changes` job from 63s in #241 by 47s (75%). Native DSP, hosted sanitizer, and shared/common draft unit checks passed; Android instrumentation and the full-host baseline remained skipped for the draft. The extracted workflow Bash block and `git diff --check` pass.

## 2026-10-19 — PR #45 Android CI feedback and Gradle cache

- Kept public-tree and EX-08 checks on every PR, while classifying paths changed since the latest successful full check. Documentation-only diffs skip Android SDK setup, native tests, Gradle, and emulator jobs; unknown paths, code/workflow changes, missing baselines, API errors, pushes, and manual runs stay full.
- Enabled Gradle task output caching for same-repository PRs only, scoped to each PR merge ref; fork PRs remain read-only. Passed `--build-cache` to both check and instrumentation Gradle invocations.
- GitHub Actions run #144 passed both full jobs in 10m46s. The check job reported 41 tasks `FROM-CACHE`, and both jobs saved Gradle cache entries for later commits. Documentation-only run #145 then passed in 15s: public-tree and EX-08 checks ran while setup-java/Android/Gradle/native/instrumentation were skipped. Later code changes remain on the full test path; compare their elapsed time against #144 to quantify the cache benefit.
- Enabled `--parallel` for the full multi-project Gradle check. Run #149 passed in 3m55s overall; its check job took 2m35s, including a 1m Gradle command, while the emulator test step took 1m50s. The Gradle log contained 162 `FROM-CACHE` task lines out of 417, compared with 41 out of 418 in #144. Since the cache state changed substantially, the timing difference cannot be assigned to parallel execution alone; use a comparable code-changing run to estimate its incremental effect.
- Added an exact fast-path exemption for the EX-08 inventory generator while keeping its regeneration-consistency check on every PR. Run #151's full check job succeeded. Its first API 35 emulator attempt failed one FullHeavyChain case (48 kHz stereo p99 30,875 µs over the 21,332 µs gate); rerunning only the failed instrumentation job passed. No production source changed, so keep the gate unchanged and require a confirming run before treating an isolated hosted-emulator spike as a code regression.
- Run #152 exercised the EX-08/doc fast path after the full baseline: public-tree and generated-ledger checks passed in 21s, while Android SDK setup, Gradle, native, and emulator work were skipped.

## 2026-10-02 — PR #14 CI wall time

- Moved API 35 emulator boot and `:shared:connectedDebugAndroidTest` into a parallel `android-instrumentation` job, and stopped installing the emulator system image in the host verification job. Kept all 40 DSP cases, three 256-block measurement rounds, and the p99 gate unchanged.
- Actions run 85 passed both jobs and finished in 9m55s, compared with 13m28s for the preceding serial run 84 (3m33s / 26% less wall time). The instrumentation job completed while host verification continued.

## 2026-10-02 — PR #14 production-path DSP benchmark

- Extended the emulator matrix for latency-bearing graphs to process both the delayed-dry shadow bypass and DSP pipeline on each block. The combined p99 is now the gate for Convolver cases, and the zero-allocation check covers both paths. The 40-case matrix and three 256-block rounds remain.

## 2026-10-02 — PR #14 DSP review follow-up

- Preallocate the PCM16 output and Float32 crossfade scratch for the supported 16,384-frame decoder block during pipeline preparation. The first 1,537-frame stereo call reports zero Kotlin allocations on the API 36.1 x86_64 emulator; the identity processor uses indexed Float copying.
- Crossfade live graph output in Float32 and quantize/dither the mixed result once. Keep the performance decision tied to the complete PCM→Float→JNI/native→PCM16 pipeline histogram.
- Gate the median pipeline p99 over three 256-block runs at 200% of a 512-frame block. Hosted-emulator medians reached 16.32 ms under runner contention while the local repeated run stayed below 3.3 ms; this accommodates that variance but still fails sustained p99 above two block durations. In the final 40-case local matrix, worst median-run light pipeline p99 was 1.10 ms; worst heavy p99 was 3.26 ms (Convolver64k, 44.1 kHz stereo). No loop fusion, FFT, compiler, or SIMD change was justified.

## 2026-10-19 — DSP-01 Seat Alignment real-time cost

- Extended the API 36.1 x86_64 matrix from 40 to 44 configurations with SeatAlignment and FullHeavyChain-with-seat at 44.1/48 kHz mono/stereo. Each configuration used three measured 256-block rounds and the existing production-path latency bypass. SeatAlignment stereo production p99 max was 1.50 ms; FullHeavyChain-with-seat stereo production p99 max was 2.76 ms. All instrumented tests passed 16/16, and the steady-state Kotlin allocation assertion stayed at zero.
- The native graph embeds two 962-float delay rings (7,696 bytes total) per processor and prepares their delay taps/gains before audio processing. No runtime allocation, configuration/FFT work, or global locking was added to the render loop. Target-head-unit CPU/memory, acoustic alignment, and A/V sync remain unmeasured.

## 2026-10-19 — DSP-02..05 integrated DSP cost

- Extended the API 36.1 x86_64 matrix to 56 configurations (14 cases × 44.1/48 kHz × mono/stereo), each with three 256-block measured rounds. Instrumentation passed 19/19 methods, including 64 exact PCM16 whole-block/variable-block comparisons for the 16 combinations of Seat, Advanced M/S, Cabin, and Harmonic across all four formats. Every matrix case passed its p99 gate and zero steady-state Kotlin allocations. The four-format 14 kHz-class harmonic alias check measured a worst folded-alias/fundamental ratio of 4.1×10⁻⁵.
- Median-run production/pipeline p99 for the new cases: AdvancedMS max 0.80 ms, Cabin3Notches max 0.64 ms, HarmonicEngine max 1.48 ms. FullHeavyChain-with-all-four modules max was 3.12 ms at 44.1 kHz stereo; the 48 kHz stereo result was 3.00 ms. These are emulator timings, not head-unit or playback A/V measurements.
- HarmonicEngine uses preallocated FIR rings with 9/17/33 taps and reports 4/8/16 input-frame latency. High quality, both branches active, was the measured cost case; CPU, THD, speaker output and in-car alias performance remain `NOT_RUN` on target hardware. Host ASan/UBSan could not link because this MSYS2 toolchain lacks `libasan` and `libubsan`; the unsanitized native C suite passes.

## 2026-10-01 — CarPlay + YouTube split

- Reuse the Activity-scoped primary GeckoSession across split exits for the same resolved profile; suspend it with `setActive(false)`, close popups before suspending, and avoid new `open()`/`loadUri()` calls on a healthy warm reopen. Keep the old view hidden until the active iPhone identity resolves.
- Cache one active AirPlay session's profile resolution using an in-memory weak session token and the exact identity evidence; skip repeat SHA-256 and preference lookup while that evidence is unchanged. Persist only changed hashed alias values.
- Make IME suppression edge-triggered, retain the two-frame settlement guard and recheck true layout changes after the keyboard closes.
- Add Android trace spans/counters for split, Gecko, profile and CarPlay restart work in debuggable builds and the opt-in benchmark variant. Enable release R8 optimization and add non-debuggable, profileable benchmark variants signed with the debug key for CI.
- Runtime latency, PSS, CPU, frame timing, GC and thermal deltas remain unmeasured on the target head unit. Dynamic ViewArea and adaptive decoder resizing remain off; the verified full restart fallback is retained.

## 2026-10-01 — PR #13 review fixes

- Keep the retained browser inactive and hidden while a replacement AirPlaySession resolves; compare the resolved profile context before reusing it. Distinguish active same-profile calls from true suspended-session reactivation so a live popup remains current.
- Add the Linux I2C exception JNI upcall keep rule through the shared AAR consumer rules; benchmark R8 mappings preserve the class name and `(int, String)` constructor.
- Make optimized benchmark APKs non-debuggable and profileable, with trace collection opted in by benchmark-manifest metadata.
- End split presentation tracing at visible Gecko content paint/composite, and CarPlay restart tracing at the first main decoded output submitted to the render surface. Reconcile IME suppression on resume/focus.
- Target-device JNI failure-path, latency, PSS and CarPlay regression measurements remain outstanding.
- Preserve the warm session across an AirPlaySession object replacement only after the new profile context matches; keep ACTIVE-session opens on the current primary/popup delegate path.
- Make benchmark variants release-like, non-debuggable and profileable. The shared JNI consumer rule preserves the native `FindClass()` class name and constructor; R8 output mappings confirm both apps retain them.
- Measure split presentation to first visible Gecko content/composite, CarPlay restart to first main decoded output submitted for rendering, and IME recovery after resume. These are trace endpoints, not target-device latency measurements.
- Keep `diplay.split.total` open until visible Gecko paint, and keep `diplay.split.carplay_restart` open until the first main MediaCodec output is submitted to the render surface. `page_ready` remains a separate network/load milestone.
- 2026-10-02: Count Gecko paint-status resets, composites, FCP callbacks, visible completions and missing-paint timeouts. After 10 seconds without visible paint, close the split span with `paint_missing`; discard a suspended browser on the pre-Android-14 running-critical trim notification only while split mode is closed. The UI-hidden callback does not evict the cache. This adds diagnostics and a scoped OS-pressure escape without claiming target-device latency or PSS improvements.
- 2026-10-02: Scope Gecko reset evidence to suspension generations and keep it independent of trace completion, so profile-resolution timeout neither loses a valid reset nor reuses one from an earlier pause. Anchor the diagnostic timeout to first activation so profile retries and background/resume do not extend the 10-second interval. Real callback timing remains to be measured on hardware.
- 2026-10-21: GitHub Actions run #231 passed on audit-only head `fb212b6`; public-source and EX-08 checks ran while Android SDK, Gradle, native, unit, lint/build/JNI, emulator, and baseline jobs were skipped. This confirms the short audit lane remains active; the next push has not run yet.
- 2026-10-21: GitHub Actions run #232 passed on audit-only head `8acb3d3`; public-source and EX-08 checks ran while Android runtime, host, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #233 passed on audit-only head `24b62fc`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #234 passed on audit-only head `f3a6e6e`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #235 passed on audit-only head `5c48954`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #236 passed on audit-only head `b67b922`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #237 passed on audit-only head `5ccafb3`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #238 passed on audit-only head `2c3177b`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #239 passed on audit-only head `ad93168`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: GitHub Actions run #240 passed on audit-only head `80ad50b`; public-source and EX-08 checks ran while Android instrumentation, host-baseline, and quick-baseline jobs were skipped.
- 2026-10-21: Updated draft CI baseline discovery in `97b3cbd`: draft runs stop after locating the latest successful quick-test baseline because host and emulator baselines are not used in that lane. Hosted PR run #281 passed in 27s; classifier time fell from 12s in run #242 to 5s, and the `changes` job fell from 16s to 8s. Ready-for-review and push paths retain their full baseline discovery and verification behavior.
- 2026-10-21: Scoped common-only draft changes in `f7294a7`: when changed files are confined to `common/src/main/java`, `common/src/main/kotlin`, and/or common JVM test sources, CI runs only `:common:testDebugUnitTest`; shared production, mixed, unknown, and unsafe changes still run both suites. The local common suite passed 156 tests with zero failures/errors/skips in 1m10. Hosted common-only source runs #287 and #291 passed; their `check` jobs took 3m02/2m37 with Gradle common-test commands at 2m10/1m55. These were different commits and not a controlled cache benchmark; all-module, native, lint/build/JNI, and emulator suites were skipped as intended.
- 2026-10-22: Shared production callback fencing in `CarPlayMediaEngine` requires both quick module suites. Draft run #294 passed 538 shared + 156 common tests; the `check` job took 3m19 and its Gradle test step 2m26. Full host lint/build/JNI, native, and emulator checks remained deferred. This is a different test scope from #287/#291 and is recorded separately, not as a controlled timing comparison.
- 2026-10-22: The follow-up engine-map replacement test ran only `:shared:testDebugUnitTest`; GitHub Actions #296 passed the targeted-module lane with a 2m13 `check` job and a 1m19 Gradle step. `common`, native, full host, lint/build/JNI, and emulator suites were skipped. This is a test-only shared update and a different scope from run #294's shared production change.
- 2026-10-22: The PR-05 single-area plist byte-parity test also ran only `:shared:testDebugUnitTest`; GitHub Actions #297 passed the shared lane in 1m49 `check` wall time, with a 1m04 Gradle task. Common, native, full host, lint/build/JNI, and emulator suites were skipped as expected for a shared-test-only update.
- 2026-10-23: `DspLiveUpdateController` now starts its 10 ms retired-pipeline cleanup timer only when a pipeline is retired and cancels it as soon as the retirement slots drain. The regression test verifies no timer after ordinary config preparation and confirms cleanup stays on `dsp-live-control`; target idle wakeups/CPU impact remain unmeasured. Draft CI keeps the existing quick lane for ordinary source/test updates; only workflow-sensitive changes force full host plus instrumentation, while Android.mk drafts add a shared NDK link check. No hosted wall-time comparison is available for these changes yet.
- 2026-10-23: API 35 hosted instrumentation p99 exceeded the two-block-duration gate in varying workloads across review follow-up runs #362/#364/#365, while the local API 36.1 benchmark class passed 5/5 (FullHeavyChain p99 5.06 ms). Since the cases varied and two earlier/current source checks passed, treat this as unstable hosted emulator timing, not evidence for changing the DSP budget without a controlled target measurement. Draft baseline discovery now reuses a successful full host+instrumentation commit, and workflow-sensitive host baselines require instrumentation success.
- 2026-10-24: Cached Seat trim gains outside the real-time DSP callback. Video recovery now uses two workers with a 16-job bounded queue; per-type request coalescing and VideoDecodeQueue control-job coalescing bound ordinary backlog. No target CPU, queue-stress latency or surface-churn measurements were collected in this source batch.
- 2026-10-26: AirPlay control and event readers now frame encrypted records and RTSP messages incrementally instead of repeatedly concatenating and rescanning the incomplete prefix. The RTSP parser caps headers at 64 KiB and bodies at 4 MiB; the listener caps active control sessions at four, bounding parser buffers and session threads. Unit tests pass, but no heap/CPU benchmark or supported-iPhone traffic capture was collected.
- 2026-10-26: DSP Split measurement diagnostics compare primitive Compose constraints and actual child MeasureSpec values before creating signature/log strings; output is capped at 64 entries per process and recent history at 64 signatures. Formatting occurs only for a new signature within the log cap. Focused diagnostics tests pass 3/3; SH-M27 UI-thread timing remains unmeasured.

## 2026-10-28 — Retire decoder-format DSP graphs off the audio owner

- On PCM format change, detach the active DSP graph on the audio owner and enqueue its close on the existing two-worker lock-free reaper. Initial graph preparation stays synchronous so AAC/Opus Convolver state is installed before first decoded PCM. A delayed-close test proves the format owner returns while the detached graph is still closing; it does not measure Android underruns or end-to-end format latency.
- Added one transition event at first PCM write with audio owner epoch, AudioTrack generation, DSP configuration generation, format, prepare duration, reaper-request duration, AudioTrack creation duration, first-write delay, fallback reason, and audio thread identity. A separate reaper event records actual destroy duration/thread. No per-block diagnostic histogram or PCM capture was added.
- Native retirement failure is sticky for its AudioRenderer owner and prevents further candidate adoption until a new renderer publishes active state. MediaCodec release gets one bounded 25 ms same-worker retry; persistent failures retain the decoder/Surface. Target CPU, underruns, audio p99, Surface churn, and soak behavior remain unmeasured on TS10/K706.
