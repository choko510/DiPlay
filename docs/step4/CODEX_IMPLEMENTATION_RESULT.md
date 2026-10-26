# Codex Implementation Result — DiPlay PR #45

## Scope and decision

- PR: [#45](https://github.com/choko510/DiPlay/pull/45), existing branch `codex/step4-pr-00-test-baseline`.
- Fixed base: `main@95ba398f95ee104527812cd2b095977d71c51f98`.
- Product-feature baseline commit: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`.
- Current code/test evidence head: `52799604c5810ea97b2c7b5e685cf1c2155de773`; it adds a `MediaCodec.release()` test seam that delegates directly to the platform call by default, plus a VIDEO-02 fault-injection regression.
- CI-classifier policy commit: `22ada3a051d16f07c742771bc979e6b900642218`; changes to `AndroidMediaSink.kt` now require the full workflow-sensitive host/API 35 lane even while PR #45 is Draft.
- Historical docs-only CI head for run #478: `05c02dd017e96f1fa3228bae3879efc9e5e7fb50`; it adds no source/test changes.
- Result-package commit `5acd98aa9ef8d85384dea218a0e68a4ba454c433` passed docs-only run #482 (`changes`, static `check`, and Draft scope; runtime jobs skipped).
- PR #45 remains open, Draft and unmerged, based on the fixed base above.

**Current decision: `NO_GO_THIS_RUN` for merge/release.** Workflow-sensitive run #486 passed the full host/native/sanitizer/lint/build/JNI/R8/API 35 gates on the current workflow-classifier HEAD, including the VIDEO-02 test seam; run #481 passed on the preceding code/test head. Local verification passes 865/865 unit tests and API 35 Shared 19/19 + Common 1/1. Preserve the earlier API 35 p99 failure beside later passing repeats; its cause is unconfirmed. Target hardware and release signing are unavailable, and independent review is `NOT_RUN`.

## Requirement findings

- F8-01, F8-02, and F8-03 fixes were already present in fixed main at `550ec3a065a13d6fb690f5d1ceee243461a23db4`: pre-RECORD TCP-close recovery, incremental RTSP parsing with a 64 KiB header / 4 MiB body limit, and a four-session cap. B04 added MAIN/ALT, parser-boundary, and permit-reuse regression coverage; this work did not duplicate the production fixes. Supported-iPhone compatibility remains `BLOCKED_DEVICE`.
- The DSP Split log, inset/scroll, and measurement corrections were already present. Repeated measurement signatures are suppressed before formatting/logging; process output is capped at 64; insets are applied before scrolling; the existing pane records the exact MeasureSpecs it passes to `ComposeView.measure()`. `ComposeView` is final, so direct `onMeasure()` override is unavailable. SH-M27 acceptance remains `BLOCKED_DEVICE`.
- VIDEO-02 now has a deterministic Robolectric failure injection: if `MediaCodec.release()` throws during decoder retirement, the prior decoder/Surface owner stays retained and the next decoder is not created. A cleanup retry is asserted after the failure state is verified; physical OEM codec behavior remains `BLOCKED_DEVICE`.
- B08 adds attempt-relative `System.nanoTime()` milestones, generation fencing, exact successful FULL OUT accounting, conservative SYN classification, and a bounded in-memory report. It adds no resend, timer, watchdog rule, connection phase, or packet rewrite.
- The B08 host overhead fixture measured the receive-header classifier/timing path; this is not a target-device CPU benchmark. It found 16.6–21.4 ns/packet incremental per-thread CPU across two runs and no incremental allocation on the timing lanes within measurement precision. Device causal timing remains required before any conditional retry.

## Batch results

| Batch | Status | Evidence / unresolved work |
|---|---|---|
| B00 | `PASS_STATIC` | Fixed base, source scope, old EX-08 checksum, and evidence schema were established. Historical CI scope is preserved in `REMAINING_GATES.md`. |
| B01 | `PASS_CI` | Run #444 passed the classifier, inventory, full host, and API 35 lanes on its recorded SHA; the 1,289-row legacy prefix remains byte-identical. |
| B02 | `PASS_CI` | Run #450 passed the Draft unit lane. Added NCM resync/sequence/cancel tests and bounded DSP measurement logging; physical USB behavior is still device-gated. |
| B03 | `PASS_CI` | Run #455 passed Draft checks; stale listener/session/revoke/error ownership tests cover generation and active-owner fencing. |
| B04 | `PASS_CI` | Run #462 passed Draft checks; local tests cover MAIN/ALT pre-RECORD close recovery, 64 KiB/4 MiB RTSP boundaries, fragmentation and four-session permit return. |
| B05 | `PASS_CI` | Run #469 passed Draft checks; Surface retirement is bounded to eight live/retiring pairs and one deferred latest Texture, with release ACK-gated. |
| B06 | `PASS_CI` | Run #471 passed Draft checks; AudioRecord zero reads emit no packet and positive reads resume output; DSP-OFF PCM16 is checked across 44.1/48 kHz mono/stereo. |
| B07 | `PASS_LOCAL` | All 111 source/merge SHAs have individual dispositions and evidence links; all 51 merges are classified. Overlay: 2 `FORK_SUPERIOR`, 16 `SKIP_OEM`, 56 `REJECT_UNSAFE`, 37 `NEED_EVIDENCE`, 0 new `ADAPT`. |
| B08 | `PASS_LOCAL` | Observation-only Stage3 timing, bounded report retention, full shared/common tests 862/862, and host per-thread CPU/allocation comparison are recorded. TS10/K706+iPhone causal timing remains `BLOCKED_DEVICE`; EX-05 retry remains zero. |
| B09 | `PASS_CI` | Run #486 passed the workflow-sensitive current-head host/native/sanitizer/lint/build/JNI/R8/API 35 lane; local shared/common 865/865, both app lint/Debug/Benchmark builds, JNI/R8, and API 35 Shared 19/19 + Common 1/1 also pass. Preserve the earlier p99 failure; local macOS LeakSanitizer remains `BLOCKED_ENV`. |
| B10 | `BLOCKED_DEVICE` / `BLOCKED_ENV` | The complete HW-01–HW-13 procedure and record template are in `B10_DEVICE_ACCEPTANCE.md`. No target unit or phone was attached. HW-11 also lacks authorized release signing credentials. |
| B11 | `NO_GO_THIS_RUN` | `B11_CONDITIONAL_NO_GO.md` records EX-05 as `NO_GO_FOR_PRODUCTION_RETRY` and all other conditional candidates off pending their exact evidence. No conditional production feature was added. |
| B12 | `PARTIAL` | This result, the final summary, device runbook, no-go package, test matrix, and remaining-gate register are present; G6 passes on current-head run #486. G7/G8/G9 remain open; preserve both the earlier API 35 p99 failure and the passing rerun. |

## B09 verification details

| Command / run | Result | Scope and evidence |
|---|---|---|
| `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest` | `PASS_LOCAL` | 865 tests, zero failures/errors/skips, including the VIDEO-02 release-failure regression, at code/test head `52799604`. |
| `python3 scripts/generate_step4_ex08_inventory.py --check` and `python3 scripts/test_step4_ex08_delta111.py` | `PASS_LOCAL` | 1,400 unique ledger SHAs; frozen 1,289-row prefix; delta tests 19/19. |
| `bash scripts/test_android_ci_classifier.sh` | `PASS_LOCAL` | CI-01..10 classifier fixtures pass, including empty/malformed/oversized compare-file fallback. Run #481's `changes` job also passes these fixtures. |
| `bash scripts/test_dsp_native.sh` | `PASS_LOCAL` | Host unsanitized native suite passes. |
| `bash scripts/test_dsp_native.sh --sanitize` with default options | `BLOCKED_ENV` | macOS AddressSanitizer aborts because LeakSanitizer `detect_leaks` is unsupported. |
| Sanitizer rerun with `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1` | `PASS_LOCAL` | ASan/UBSan passes with leak detection disabled; LeakSanitizer remains unverified locally. |
| `:mobile:lintDebug :automotive:lintDebug` | `PASS_LOCAL` | Both app Debug lint tasks pass. |
| `:mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` | `PASS_LOCAL` | Both apps' Debug and Benchmark variants build; shared JNI libraries built for configured ABIs. |
| `python3 scripts/check_jni_mapping.py` | `PASS_LOCAL` | Both mobile and automotive Benchmark R8 mappings retain JNI entry points. This was run on the unchanged product source `b767ed8f`; `0f425cdc` changes tests/docs only. |
| `ANDROID_HOME=/Users/choko/Library/Android/sdk ./gradlew :shared:connectedDebugAndroidTest` / API 35 emulator | `PASS_EMULATOR` on latest repeat | 19 tests, zero failures/errors/skips. Latest Gain median p99 values are 320 µs (48 kHz stereo), 140 µs (48 kHz mono), 120 µs (44.1 kHz mono), and 180 µs (44.1 kHz stereo), below the unchanged ceilings. A preceding focused 48 kHz stereo run also passed. The earlier same-head full rerun remains a separate failure: three cases failed, including 151,772 µs stereo and 44,053 µs mono; the 44.1 kHz mono method failed before a process crash. The outlier's cause is unconfirmed. Common API 35 passed 1/1. The filtered logcat evidence validator passes. |
| GitHub Actions [run #476](https://github.com/choko510/DiPlay/actions/runs/40358035639) | `PARTIAL` | `changes` and `check` passed, including Draft unit tests and EX-08 checks; `quick-baseline` and Draft scope passed. Full host/native/sanitizer/lint/build/JNI/R8 and API 35 instrumentation were explicitly skipped because PR #45 remains Draft. |
| GitHub Actions [run #477](https://github.com/choko510/DiPlay/actions/runs/40361161687) / [run #478](https://github.com/choko510/DiPlay/actions/runs/40361499379) | `PASS_CI` for docs-only Draft scope | Both docs-only runs passed `changes`, static `check`, and Draft scope; runtime lanes were skipped. These runs add no runtime-test evidence. |
| GitHub Actions [run #480](https://github.com/choko510/DiPlay/actions/runs/40364789296) | `PASS_CI` for class-filtered Draft scope | The changed `DspRendererProductionPathTest` class passed; full host/native/build/JNI/R8 and instrumentation were skipped under the previous workflow classifier. |
| GitHub Actions [run #481](https://github.com/choko510/DiPlay/actions/runs/40366764979) | `PASS_CI` on preceding head | Workflow-sensitive full host/native/sanitizer/lint/build/JNI/R8 and API 35 lanes passed on `ae603512`. Shared instrumentation passed 19/19 and Common 1/1. |
| GitHub Actions [run #486](https://github.com/choko510/DiPlay/actions/runs/40372862000) | `PASS_CI` | Workflow-sensitive classification selected and passed `changes`, host native DSP, ASan/UBSan, unit/lint/build, JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope on the current classifier HEAD. Shared instrumentation passed 19/19 and Common 1/1; `quick-baseline` was skipped because the full host lane ran. |
| GitHub Actions [run #482](https://github.com/choko510/DiPlay/actions/runs/40369187235) | `PASS_STATIC` | The result-package docs-only head passed `changes`, EX-08/static `check`, and Draft scope. Host/native/sanitizer/lint/build/JNI/R8 and API 35 instrumentation were skipped; this adds no runtime evidence. |
`scripts/run_android_instrumentation.sh` environment note: macOS has neither `timeout` nor `gtimeout`; the post-run capture step reports `timeout: command not found`. The successful Gradle runs were invoked directly, followed by tag-filtered `adb logcat` capture and `check_android_instrumentation_evidence.sh`.

## Re-entry and rollback

- For API 35 instrumentation, keep the 21.332 ms budget unchanged and retain both failed and passing runs. The earlier outlier was not reproduced in local or hosted full repeats, but its cause is unconfirmed.
- For device/conditional gates, use `B10_DEVICE_ACCEPTANCE.md` and `B11_CONDITIONAL_NO_GO.md`; no change is authorized by a host-only result.
- B08 product rollback: revert `b767ed8f` to remove the observation-only timing path; `0f425cdc` is only its measurement fixture and can be reverted independently. Earlier feature rollback remains per the existing PR/feature defaults and `REMAINING_GATES.md`.
- No signing secret, MFi credential, device serial/MAC, audio payload, or packet body is stored in this report.

## Source/test commit list

The table below is generated from commits between the fixed base and current code/test head `52799604c5810ea97b2c7b5e685cf1c2155de773`, retaining commits that touched `*/src/main`, `*/src/test`, or `*/src/androidTest`.

| Commit | Subject |
|---|---|
| `015857f9258faa26fea3602ee88a660e8300ad61` | test(step4): add main baseline fixtures |
| `715f0586448437ccbbdc36f09db91176ebc4ffa3` | test(step4): timestamp startup packet fixtures |
| `3f1470723a84895f4d5723ba8fa859c3360745ef` | feat(step4): add opt-in wired path observability |
| `bc08b6b156b9719e9565301e6b736a635911f228` | fix(step4): bound NCM framing resynchronization |
| `3d091bf4f79e49c3d3a4dea55bca941397c87105` | fix(step4): fence stale host reconnect retries |
| `dd3b3fa5c873cd2c35a0c22f8f10b13d50fdd8a8` | fix(step4): classify CarPlay microphone input requests |
| `c6b056222e635a533d3c66130e1a706afee10795` | feat(step4): resize CarPlay locally in split mode |
| `cdf4e704a1c68f43d1b4d701dfff803f90b69e12` | feat(step4): model opt-in CarPlay view areas |
| `b535216dea445c7a7638eb0fd1dc7d0404ff531f` | test(step4): capture the DSP baseline contract |
| `62a43268a3434f231e6cd819f082d2d7a43420ee` | feat(step4): add DSP seat alignment |
| `13c287aab906e3e744d598d6e827892019f9ac2e` | feat(step4): add advanced mid-side processing |
| `bade57adf1b4d642cdd9874cb5dd1ff9f24d38da` | feat(step4): add measured cabin correction |
| `68f97e02fd7eeb1cb5337b06fc93b44c455f50c7` | feat(step4): add oversampled harmonic engine |
| `097fd7d28df82991bc25a38a1366d73b606b8e01` | test(step4): validate harmonic JNI arguments |
| `72bab8fce9ddfee1c8e03e63974efb9b6325009b` | test(step4): complete DSP integration matrix |
| `5840d72b4424069c688188229153b6ff04b081c2` | fix(step4): keep wired config independent of hotspot credentials |
| `c28c4845985ecd39ec6441b77d10ae4d9aeec50e` | fix(wireless): explain refused startup permissions |
| `5673ac4d08abf4525c9ccfd400cd79a4f29336f0` | fix(wireless): restore hotspot warning after failed start |
| `0c09b5a86541772c4b6ab5d55739cf7ca2da8020` | fix(privacy): omit Bluetooth identifiers from logs |
| `16991d6d643b94f0b9fd387eabe3cc36b40f3726` | feat: add in-host CarPlay DSP split view |
| `47635944b158d805be014acf59f0276e72dbc7dd` | fix: stabilize multitouch and reject stale AirPlay accepts |
| `caeef8f41c5d31ad66b6c123509d0218f10c6e47` | fix: address PR45 integrated review findings |
| `fe6607e2464c3c7d571a25d26a28d7b31a21dfdd` | fix: stabilize DSP p99 benchmark priority |
| `bdf7e99ef44b373b9fac7de949c325216c6fa578` | fix: serialize DSP editor operations and reset split on open |
| `23c268ae596adb157d4855b8fe188743dd7ac289` | test: cover unadopted DSP import cleanup |
| `da8402d9f2224b0936d7f7eb03bd23497e897b62` | fix: harden NCM resync and wired trace snapshots |
| `2a056143609498e9390f6d80ab3573ff0cbb27bc` | fix: preserve watchdog and split rotation behavior |
| `92669243d7f6f7c4cc56b38dea5304e51e2788ee` | fix: complete source-correctable 4AI re-audit findings |
| `eab621b77d9612ddc31eaeb06bcc348c6837362a` | fix: address third PR45 audit findings |
| `51a655c0344352b94221b9553d0d20e079d74ce7` | fix: address fourth PR45 audit findings |
| `e811e1c4b3b7cbea23f3d2ad512a79d00bf28d2c` | fix: address PR45 fifth integrated review findings |
| `a80a6497774903293c86940da549d574a8a4768c` | Bound DSP split scroll height |
| `24e094235f04185a275e14b8a4c6b9657369eca5` | Fix sixth audit transport and media findings |
| `4515aa4cb4e4cc31481021059c3587227eb225ab` | Guard DSP split scroll constraints directly |
| `45cbd8266da74e6250d5786bc8c0d582a41fb9a4` | Fix shared test failures from audit batch |
| `70437237f5a83e53f38c12bc3f628fa226923da6` | Fix seventh review crash and DSP priority findings |
| `5c851fad85f4ae9f8cf37190185eeb7c52f013c5` | Stabilize queue and DSP async test assertions |
| `53a50b1b420da3012741ce981b43a08c18853ef5` | Wait for DSP retirement gate in concurrency test |
| `550ec3a065a13d6fb690f5d1ceee243461a23db4` | Fix AirPlay startup and RTSP resource bounds |
| `8ba8b2296806c10c64ea1add8b06b4258b033372` | Stabilize decoder retirement test under interrupts |
| `8d2e967e05fa3b5c2ca5d7eb9472f1eb1078f351` | fix: suppress repeated split logs and audit EX08 delta-111 |
| `fa9b37046f8b5e4bdc5b1caa99ff497e899ddd50` | test: cover NCM recovery and cap split diagnostics |
| `9f499afb393fa8e574f1021e8c269eb0f9fb396d` | Fix stale AirPlay attachment teardown races |
| `aaedf0e5fe08f3b22d255ee15bc2d18b38774f37` | test: cover wired recovery and RTSP bounds |
| `2070b215087bd2df8f7ec4926b27edb66352184e` | test: enforce production RTSP body limit |
| `ae63a36566871e1197cf9c736c411307aa0bbef7` | B05 bound SurfaceTexture retirement pressure |
| `686516f5a3fbd220822c89e394c5c12222a24eb8` | test: cover microphone zero-read and DSP-off formats |
| `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f` | feat: add observation-only wired startup timing |
| `0f425cdc98fd78e3835350fc614881ecf911b729` | test(step4): measure wired timing overhead |
| `abf7bbd9f776a812ef7ec0f93b91034fc28ad577` | test(dsp): assert process and reset owner |
| `ae60351267b14a8ef6de3dacb6f718b37bc2310c` | test: harden Android CI classification coverage |
| `52799604c5810ea97b2c7b5e685cf1c2155de773` | test(video): retain decoder owner on release failure |
