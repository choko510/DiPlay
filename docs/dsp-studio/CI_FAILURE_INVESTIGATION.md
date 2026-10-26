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
