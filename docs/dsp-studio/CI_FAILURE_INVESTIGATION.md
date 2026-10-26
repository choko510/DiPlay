# CI failure investigation

## API 35 instrumentation results

| Run | Head | Result | Relevant evidence |
|---|---|---|---|
| #422 (`40249136921`) | `a0b197a9` | FAILED | `Convolver64k` 48 kHz mono production p99 was 30,477 µs against the unchanged 21,332 µs ceiling. The standalone Studio screen test also failed its unsaved-draft status assertion. |
| #423 (`40250193181`) | `ba6b6592` | FAILED | `FullHeavyChain` 48 kHz stereo p99 was 34,925 µs and `PEQ_Compressor_Limiter` 48 kHz mono p99 was 28,297 µs. Six Common instrumentation tests passed, including the repaired draft status assertion. During the shared 19-test AVD run, Gradle also compiled Common Kotlin. |
| #424 (`40257356980`) | `32dc066e` | PASSED | The shared 19-test AVD run completed before the separate Common instrumentation invocation began. All 6 Common tests passed; host checks and Release verification passed. |
| [#425](https://github.com/choko510/DiPlay/actions/runs/40257898406) (`40257898406`) | `e4cfc0a470d2f54e01e53eb798935f58bc0b37c8` | PASSED | Shared 19/19 and Common 6/6 API 35 instrumentation tests passed in separate Gradle invocations. Host native tests, ASan/UBSan, unit tests, lint, debug/optimized builds, JNI mapping, and Release verification passed. |

## Diagnosis and mitigation

The test runner previously requested `:shared:connectedDebugAndroidTest` and `:common:connectedDebugAndroidTest` in one Gradle invocation. Run #423 shows a Common Kotlin compilation overlapping the shared AVD benchmark matrix, which can distort tail-latency measurements. The configuration cache is enabled in the project, and Gradle documents that cached task execution may run independent tasks in parallel.

Commit `32dc066e` runs the Shared and Common connected-test tasks in separate Gradle invocations. In #424, all 19 Shared tests finished before Common compilation and its 6-test instrumentation run began. The 21,332 µs p99 gate was not changed.

This is a credible source of benchmark contamination, but not a complete explanation for all observed variation: #422 exceeded the same gate for `Convolver64k` while `:common:compileDebugKotlin` was already `FROM-CACHE`. Runs #424 and #425 both passed after the test tasks were serialized, but the #422 counterexample means the earlier failures are not classified as harmless noise or a proven product regression. Keep the gate unchanged and evaluate every API 35 run, including the p99 values and task timeline, before closing CR-023.

## Required follow-up

- Repeat API 35 instrumentation after the next native or renderer changes; do not rely on these two passes alone to close the intermittent p99 concern.
- Preserve the 21,332 µs gate and report any future outlier with its benchmark case, sample rate, channel count, run count, and concurrent task timeline.
- Physical target-device CPU, p99, A/V, and acoustic measurements remain `NOT_RUN` until captured on TS10/K706 hardware.

## Renderer update planner regression

| Run | Head | Result | Relevant evidence |
|---|---|---|---|
| [#427](https://github.com/choko510/DiPlay/actions/runs/40258920177) (`40258920177`) | `4949c0e38bf9e28c25231483aede847fd940c534` | FAILED | `:shared:testDebugUnitTest` ran 636 tests with 4 renderer expectations failing: seat-generation fallback, mismatched-IR adoption, dynamics deferral, and active-convolver history preservation. Release verification failed as a consequence of `check`. |
| [#432](https://github.com/choko510/DiPlay/actions/runs/40263293980) (`40263293980`) | `00087ba708cad5ba0cd5e9b222c7a7fa184b089b` | PASSED | Android instrumentation (including JNI DSP smoke test), host native tests, ASan/UBSan, unit tests, lint, debug/benchmark builds, and JNI/R8 mapping verification passed. The four renderer expectations remain intact. |

The final PR #60 update diffs the active effective graph against the prepared graph and defers unsupported `LOCAL_WARM` edits during a committed track with an explicit reason. The local Shared and Common unit-test rerun passed all 864 tests. Stage-local warm-up and history transfer are still unimplemented, so CR-013 remains partial; this pass validates safe deferral, not full CR-013 completion.

### Deferred-reason UI test follow-up

| Run | Head | Result | Relevant evidence |
|---|---|---|---|
| #433 (`40265685835`) | `ab68a2fd` | FAILED | `DspSettingsScreenTest.macroPreviewSurvivesCompactAndFullLayoutsAndCancelRestoresCommittedState` timed out while searching for the deferred reason in compact Studio (line 73). |
| #434 (`40276419856`) | `c5db7cd3` | FAILED | The assertion moved to Profile, but the diagnostics card was still absent from that page (line 82). |
| #435 (`40277535297`) | `78880702` | FAILED | Scrolling to the renderer-status label in Profile failed because the diagnostics section is not rendered there. |
| #436 (`40278161737`) | `75cf4dec` | FAILED | Using the localized status label still failed in Profile; the destination page was the remaining issue. |
| [#437](https://github.com/choko510/DiPlay/actions/runs/40278919992) (`40278919992`) | `7ff29246` | PASSED | Shared instrumentation 19/19 and Common instrumentation 6/6 passed. Host native tests, ASan/UBSan, unit tests, lint, debug/optimized builds, JNI/R8 verification, and Release verification passed. |

The diagnostics card is inside the Advanced section, which is reached through More and is hidden until Advanced controls are enabled. The test now selects More, enables Advanced, scrolls to the localized renderer-status label, then verifies the reason. This fixes the test's navigation assumption without changing the renderer or diagnostics UI. The local Common instrumentation test remains `NOT_RUN`: the local AVD was stopped and its 6 GiB data partition could not be started with the available host disk space. CI #437 ran the instrumentation suite successfully.

## PR #60 integration verification

| Run | Head | Result | Relevant evidence |
|---|---|---|---|
| [#438](https://github.com/choko510/DiPlay/actions/runs/40280273076) (`40280273076`) | `9e026e55d75f0c4f6d89cdaf66727bfbd023f40a` | PASSED | Integration PR #52 head. Classifier, public-tree/EX-08, host Native, ASan/UBSan, Shared/Common unit tests, lint, Debug/benchmark builds, JNI/R8 verification, API 35 Shared/Common instrumentation, and Release verification passed. The optional quick-baseline job was skipped because this was not a draft quick-only change; the normal full-host check ran instead. |

The integration run is the latest hosted result at this source head. Local post-merge verification also passed Shared 639/639 and Common 227/227 unit tests (866 total), plus Shared and Common Android-test Kotlin compilation, with zero failures, errors, or skips. Local connected instrumentation remains `NOT_RUN` because no connected device was available and the configured AVD could not start with the available disk space.
