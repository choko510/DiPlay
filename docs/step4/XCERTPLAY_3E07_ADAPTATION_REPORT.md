# XCertPlay 3e07bca Adaptive Port — PR45

## Baseline

- Source: `shilapi/xcertplay@3e07bca57c8700d1268db3f45ba2c22607da1930`
- Target PR: https://github.com/choko510/DiPlay/pull/45
- Target start SHA: `c8a1ab0bd9126b7bc6f11e28e2fa6fa2b8cea78c`
- Final source/test integration SHA: `54b65005ffeffb2cdd802028bceffea28eaa68ee` (includes concurrent PR #45 USB handoff work; follow-up documentation commits are listed below)
- Independent-review remediation code head: `24a6d650` (F-01–F-06); docs and Working Memory updates follow in a separate commit.
- Target branch/base: `codex/step4-pr-00-test-baseline` / `main` (`95ba398f95ee104527812cd2b095977d71c51f98`)
- PR status: open, Draft, unmerged
- Code/test environment: Windows; Android Studio JBR 21.0.9; Android SDK 37.1; connected API 36 x86_64 emulator. No TS10/K706 head unit or iPhone is attached.
- Start worktree was clean. Its local HEAD is detached at the PR head because the existing branch is checked out in another managed worktree. New commits will be fast-forwarded to the existing PR branch only.
- Baseline focused tests passed: shared 52/52 and common 22/22, with zero failures, errors, or skips. Exact commands are in [Test commands and results](#test-commands-and-results).

## 20-file disposition

| Donor file | Disposition | Target / rationale |
|---|---|---|
| `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` | `PARTIAL` | Inspect log delivery; batch only if startup/reconnect measurements show material UI-thread load. |
| `common/src/main/java/com/shilapi/xcertplay/CarPlayWebDisplayFactory.kt` | `SKIP_NO_TARGET` | No browser display/preview path exists in this PR. |
| `common/src/main/java/com/shilapi/xcertplay/HandshakeTimeline.kt` | `SKIP_NO_TARGET` | No HandshakeTimeline or prepared UI model exists. |
| `common/src/main/java/com/shilapi/xcertplay/JsonProfileVehicleData.kt` | `SKIP_NO_TARGET` | No corresponding vehicle-profile parser exists. |
| `common/src/main/java/com/shilapi/xcertplay/WebDisplayCertificate.kt` | `NOT_ADOPTED_RISK` | Web DER/private-key caching is unrelated and increases secret-handling risk. |
| `common/src/main/java/com/shilapi/xcertplay/ui/components/Lavender.kt` | `SKIP_NO_TARGET` | The donor UI library is absent. |
| `common/src/main/java/com/shilapi/xcertplay/ui/components/RenderThreadLoop.kt` | `SKIP_NO_TARGET` | No matching animated-vector UI is needed. |
| `common/src/main/java/com/shilapi/xcertplay/ui/home/HomeScreen.kt` | `SKIP_NO_TARGET` | No corresponding Compose home screen exists. |
| `common/src/main/res/drawable/avd_glyph_spin.xml` | `SKIP_NO_TARGET` | Donor-only loading animation. |
| `common/src/main/res/drawable/avd_phase_pulse_disc.xml` | `SKIP_NO_TARGET` | Donor-only loading animation. |
| `common/src/main/res/drawable/avd_phase_pulse_ring.xml` | `SKIP_NO_TARGET` | Donor-only loading animation. |
| `common/src/test/java/com/shilapi/xcertplay/CarPlayWebDisplayTest.kt` | `SKIP_NO_TARGET` | No web display feature to test. |
| `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt` | `ADAPT` | Route recovery commands by MAIN/ALT type; ALT needs the SETUP UUID, with owner checks preserved. |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | `ADAPT` | Reuse worker-owned `BufferInfo`, bound idle output waits, and evaluate conservative Surface recovery without replacing lifecycle ownership. |
| `shared/src/main/java/com/shilapi/xcertplay/media/MediaCodecSupport.kt` | `FORK_SUPERIOR` | Keep the existing Annex B path and random-access scan; no browser forwarding consumer exists. |
| `shared/src/main/java/com/shilapi/xcertplay/media/MediaTap.kt` | `SKIP_NO_TARGET` | No Web MediaTap consumer exists. |
| `shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeFlow.kt` | `FORK_SUPERIOR` | Keep the existing queue and reference-chain separation. |
| `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` | `ADAPT` | Obtain `UsbManager` from the already-owned application context. |
| `shared/src/test/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngineTest.kt` | `ADAPT` | Add MAIN/ALT command-shape and missing-UUID coverage while retaining stale-owner tests. |
| `shared/src/test/java/com/shilapi/xcertplay/media/MediaCodecSupportTest.kt` | `PARTIAL` | Retain and rerun existing forward-conversion/random-access coverage; do not import reverse-conversion tests. |

## Implemented optimization phases

| Phase | Intended change | Status | Test evidence / rollback |
|---|---|---|---|
| OPT-00 | Freeze start state, review ownership and design regressions | `IMPLEMENTED_AND_TESTED` | Start SHA, 20-file disposition, ownership invariants and focused baseline are recorded above. |
| OPT-01 | Read `UsbManager` from `appContext` | `IMPLEMENTED_AND_TESTED` | New regression failed before the fix (`usbManager must not be null`) and passed after it; `CarPlayControllerWatchdogTest` passed 5/5. Revert: OPT-01 commit. |
| OPT-02 | Reuse one `MediaCodec.BufferInfo` on the decoder worker | `IMPLEMENTED_AND_TESTED` | `AndroidMediaSinkStateTest` passed 19/19; its regression verifies separate MAIN/ALT decoder-owned instances. This proves ownership and source-level allocation removal, not a codec runtime gain. Revert: OPT-02 commit. |
| OPT-03 | Reduce idle polling while draining queued codec output with bounded waits | `IMPLEMENTED_AND_TESTED` | Focused queue/sink suites passed 30/30. Simulated output covers final-frame drain, multiple delayed outputs, 8 ms cap, 500 ms expiry, bounded pending count, Surface reset and ACK. Device wake/latency effect remains unmeasured. Revert: OPT-03 commit. |
| OPT-04 | Send correctly addressed MAIN/ALT keyframe requests | `IMPLEMENTED_AND_TESTED` | `CarPlayMediaEngineTest` passed 22/22; MAIN/ALT payload shape, missing UUID, unknown type, send failure and stale owner are covered. Diagnostics contain no UUID. Device command acceptance remains unverified. Revert: OPT-04 commit. |
| OPT-05 | Recover a changed Surface only after a bounded no-output condition | `IMPLEMENTED_DEVICE_PENDING` | The policy waits 250 ms, requires a running decoder/config/non-null Surface, stops after a non-config output, and uses the existing 1 s request gate. `VideoDecodeQueueTest` and `AndroidMediaSinkStateTest` passed 32/32; production default is OFF. Target frame continuity/request rate is unverified. Revert: OPT-05 commit. |
| OPT-06 | Batch Activity log delivery | `NOT_ADOPTED_RISK` | `onDebugLog` posts each redacted message to the UI, but no real startup/reconnect dispatch count or UI/file-write timing was available. Batching without evidence risks changing ordering/snapshot and durable log behavior. |
| OPT-07 | Validate existing NAL conversion and justify non-migration | `IMPLEMENTED_AND_TESTED` | Kept the existing forward conversion/random-access scan. `MediaCodecSupportTest` passed 14/14 including byte-exact output, unchanged input, 3/4-byte Annex B headers, zero/oversized lengths, H.264/H.265. No donor reverse converter was added. |
| OPT-08 | Add bounded, payload-free video-path measurements | `IMPLEMENTED_AND_TESTED` | Five-second decoder summaries include queue/output waits, thread CPU, received-to-codec-Surface-release p50/p95/p99, unmatched/overwritten correlation fractions, pending peaks/timeouts, keyframe attempts and MAIN/ALT recovery outcomes. Process allocation/GC/PSS sampling is opt-in through the video-stats DEBUG log tag. The release metric stops at `releaseOutputBuffer(..., true)` returning; it does not measure compositor or panel presentation. No live-video performance sample was available. |

## Behavioral invariants

- Preserve stream owner/generation checks, the bounded recovery executor, queue limits and FIFO behavior.
- Preserve codec fallback, capability-gated low-latency configuration, `VideoInputPump` draining, and finite input waits.
- Preserve MAIN/ALT independent decoder ownership and every Surface detach/release acknowledgement. A failed codec release retains the old codec and Surface owner.
- Preserve local Split dimensions/session/controller/Surface identity, DSP OFF byte-exact PCM16 behavior, MEDIA-only DSP routing, USB/NCM, audio routing, and watchdog behavior.
- Do not add a Web MediaTap, TLS/DER key cache, Compose Home, HandshakeTimeline, new manifest permissions, or media payload/UUID logs.

## Test commands and results

| Command | SHA | Environment | Result |
|---|---|---|---|
| `:shared:testDebugUnitTest --rerun-tasks --tests AndroidMediaSinkStateTest --tests VideoDecodeQueueTest --tests CarPlayMediaEngineTest --tests MediaCodecSupportTest` | `c8a1ab0` | Windows, JDK 21.0.9 | `PASS`: 52 tests, 0 failures/errors/skips. |
| `:common:testDebugUnitTest --rerun-tasks --tests CarPlayHostActivityReconnectTest --tests CarPlayHostActivityLocalScaleTest --tests CarPlayControllerWatchdogTest` | `c8a1ab0` | Windows, JDK 21.0.9 | `PASS`: 22 tests, 0 failures/errors/skips. |
| `:common:testDebugUnitTest --tests CarPlayControllerWatchdogTest` | OPT-01 worktree | Windows, JDK 21.0.9 | `PASS`: 5 tests, 0 failures/errors/skips. The application-context regression fails on the original code and passes with the fix. |
| `:shared:testDebugUnitTest --tests AndroidMediaSinkStateTest` | OPT-02 worktree | Windows, JDK 21.0.9 | `PASS`: 19 tests, 0 failures/errors/skips. MediaCodec output runtime behavior remains subject to emulator/device validation. |
| `:shared:testDebugUnitTest --tests VideoDecodeQueueTest --tests AndroidMediaSinkStateTest` | OPT-03 worktree | Windows, JDK 21.0.9 | `PASS`: 30 tests, 0 failures/errors/skips. The fake-output drain tests are software timing evidence, not OEM MediaCodec evidence. |
| `:shared:testDebugUnitTest --tests CarPlayMediaEngineTest` | OPT-04 worktree | Windows, JDK 21.0.9 | `PASS`: 22 tests, 0 failures/errors/skips. No live event-channel/iPhone acknowledgement was exercised. |
| `:shared:testDebugUnitTest --tests VideoDecodeQueueTest --tests AndroidMediaSinkStateTest` | OPT-05 worktree | Windows, JDK 21.0.9 | `PASS`: 32 tests, 0 failures/errors/skips. Policy tests validate delay/gates and explicit-off default, not actual Surface frame presentation. |
| `:shared:testDebugUnitTest --tests MediaCodecSupportTest` | OPT-07 worktree | Windows, JDK 21.0.9 | `PASS`: 14 tests, 0 failures/errors/skips. |
| `:shared:testDebugUnitTest --tests CarPlayMediaEngineTest --tests AndroidMediaSinkStateTest --tests VideoStatsTest --tests VideoDecodeQueueTest` | OPT-08 worktree | Windows, JDK 21.0.9 | `PASS`: 56 tests, 0 failures/errors/skips. Includes per-display recovery outcome counters and deterministic stats percentile formatting. |
| `:shared:testDebugUnitTest` + `:common:testDebugUnitTest` | Merged integration SHA `54b65005` | Windows, JDK 21.0.9 | `PASS`: shared 682/682; common 236/236; zero failures/errors/skips. |
| `:shared:testDebugUnitTest --tests DspRendererProductionPathTest` | `8b64714d` | Windows, Android Studio JBR 21 | `PASS`: 32 tests, 0 failures/errors/skips. Includes fail-closed retirement and latency-rescaling regression. |
| `:shared:testDebugUnitTest --tests VideoDecodeQueueTest --tests AndroidMediaSinkStateTest` | `b5a6182d` | Windows, Android Studio JBR 21 | `PASS`: 37 tests, 0 failures/errors/skips. Covers codec-config/empty/EOS/format-change/invalid-Surface output dispositions and pending-frame drain. |
| `:shared:testDebugUnitTest --tests CarPlayMediaEngineTest` | `1c8ff8c6` | Windows, Android Studio JBR 21 | `PASS`: 23 tests, 0 failures/errors/skips. Includes a latch-controlled screen-owner replacement during dispatch. |
| `:shared:testDebugUnitTest --tests VideoStatsTest` | `24a6d650` | Windows, Android Studio JBR 21 | `PASS`: 3 tests, 0 failures/errors/skips. Covers non-blocking logging, corrected timing label, and correlation fractions. |
| `:shared:testDebugUnitTest :common:testDebugUnitTest --parallel` | `24a6d650` | Windows, Android Studio JBR 21, Android SDK 37.1 | `PASS`: Shared 688/688 + Common 236/236; 924 tests total, 0 failures/errors/skips. |
| `:shared:connectedDebugAndroidTest` | OPT-08 worktree | `Medium_Phone_API_36.1`, x86_64 emulator | `PASS`: 19 tests, 0 failures/errors/skips. This is the existing DSP instrumentation suite; no live MediaCodec video test exists in the suite. |
| GitHub Actions run #551 on PR head `000e59e9` | `choko510/DiPlay` PR #45 | hosted Linux + API 35 emulator | `PASS`: classifier, public-tree, EX-08 inventory, host native, ASan/UBSan, full unit/lint/build/JNI/R8, API 35 instrumentation (20/20), host-baseline and Draft scope. The Draft unit lane was skipped because full-host verification ran. Archived artifacts contain 918 unit tests in 162 XML suites and 20 instrumentation tests in 2 XML suites, all with zero failures/errors/skips. |
| `:shared:assembleDebug`, `:mobile:assembleDebug`, `:automotive:assembleDebug` | OPT-08 worktree | Windows, Android SDK 37.1 | `PASS`: all Debug artifacts built. NDK 28.2 emitted the existing missing `source.properties` and unstripped-library warnings. |
| `:mobile:assembleBenchmark`, `:automotive:assembleBenchmark`, `scripts/check_jni_mapping.py` | OPT-08 worktree | Windows, Android SDK 37.1 | `PASS`: both Benchmark artifacts built; JNI/R8 mappings verified. |
| `:mobile:lintDebug`, `:automotive:lintDebug` | OPT-08 worktree | Windows, Android SDK 37.1 | `PASS`. |
| `:shared:lintDebug`, `:common:lintDebug` | Merged integration SHA `54b65005` | Windows, Android SDK 37.1 | `FAIL_EXISTING`: each reports 13 errors. Common findings are unchanged Bluetooth permission calls, the microphone foreground-service declaration and Compose resource lookups; Shared findings are unchanged Bluetooth permission calls, API-33 locale calls under minSdk 28, and suspicious indentation. `CarPlayController.kt` findings are at unchanged Bluetooth enumeration/address lines, not OPT-01. No new media/engine/test diagnostic was reported. |
| Android CI classifier/evidence fixtures and public-tree check | OPT-08 worktree | Windows, Git for Windows Bash, Python 3.14 | `PASS`. The System32 `bash.exe` resolves to an unavailable WSL stub; rerunning with Git Bash passed. |
| EX-08 delta-111 and generated-inventory checks | Merged integration SHA `54b65005` | Windows, `core.autocrlf=true` | `PASS_LOCAL`: temporarily LF-normalized the frozen commits JSONL, review-override JSON and generated CSV; delta tests passed 19/19 and inventory check reported 1,400 unique rows. Exact original worktree bytes were restored; no ledger data was changed. |
| API 36 instrumentation / physical acceptance | `c8a1ab0` | emulator attached; no target hardware | `NOT_RUN` at baseline. |

## Independent review follow-up (2026-10-28)

The attached independent code review's P1/P2 findings F-01–F-06 are fixed in five small commits on the existing Draft PR #45.

| Finding | Change | Regression evidence | Remaining limit |
|---|---|---|---|
| F-01 | DSP retirement failure now fails closed: the audio owner detaches and retires active/fading graphs, uses PCM fallback, and carries the previous latency duration into the next sample rate. Graph publication is serialized with failure notification. | `DspRendererProductionPathTest` 32/32; its retirement-failure case verifies no new prepare call after failure, no active graph, and 128 frames at 44.1 kHz preserved as 93 frames at 32 kHz. | Native destroy failure remains fault-injected; physical audio continuity and target latency are unverified. |
| F-02 | Codec-config and zero-size outputs are released with `render=false`; only a non-config picture on a valid Surface updates submit metrics, recovery state, or first-frame status. | `VideoDecodeQueueTest` and `AndroidMediaSinkStateTest`, including config/empty output and first valid picture cases. | No OEM MediaCodec/Surface device run. |
| F-03 | Pending count decreases only for decoded picture output; EOS resets it after processing, and format-change notifications do not consume it. Invalid Surface outputs cannot claim a Surface submission. | `VideoDecodeQueueTest` covers config followed by delayed picture, zero-size, EOS, format change, invalid Surface and bounded pending state. | The pending counter remains an approximate bounded wake-up hint, not an input/output pairing. |
| F-04 | A per-screen ownership lock serializes owner validation and keyframe command dispatch with MAIN/ALT owner replacement. Session teardown after event-write failure now runs after releasing the event-write lock. | `CarPlayMediaEngineTest` 23/23, including a latch-controlled replacement/dispatch race. | The test uses a fake sender; phone command acceptance and real socket failure timing are unverified. |
| F-05 | Stats counters are copied/reset under a short lock; percentile sorting, formatting and logging run outside it. | `VideoStatsTest.slowLoggerDoesNotBlockFrameArrival` passes with an intentionally blocked logger. | No live stream contention or CPU profile was available. |
| F-06 | Renamed the metric to `receivedToCodecSurfaceReleaseMs` and added unmatched-output and overwritten-input numerator/denominator counts. | `VideoStatsTest` 3/3 verifies percentile label, correlation fractions and slow-logger concurrency. | This measures receive-to-codec Surface release call completion, not display presentation. |

Post-fix local full suites pass: Shared 688/688 and Common 236/236 (924 tests total, zero failures/errors/skips). The second independent review remains `NOT_RUN` per the existing direction. Physical TS10/K706 + iPhone video/audio evidence remains `BLOCKED_DEVICE`.

## Performance before/after

| Metric | Baseline | Optimized | Units / sample | Device / confidence |
|---|---|---|---|---|
| Idle decoder queue poll cadence | 5 ms timed poll | 100 ms timed poll when no output is pending; pending output uses up to 8 ms dequeue waits until 500 ms after the last successful input | Source-level nominal timeout ceiling changes from 200 to 10 idle polls/s; not a measured CPU/wakeup comparison. | No live stream; expected bounds only. |
| Decoder idle poll rate and thread CPU | `NOT_RUN` | Instrumentation emits empty-queue poll/s and thread CPU ms/s; sample values `NOT_RUN` | Requires a live decoder on the same device and codec. | API 36 emulator was available but no iPhone/video stream was attached; TS10/K706 unavailable. |
| Queue-wait/dequeue-output time and receive-to-codec-Surface-release p50/p95/p99 | `NOT_RUN` | Instrumentation emits queue wait, output block time and PTS-correlated `releaseOutputBuffer` completion percentiles with correlation fractions; sample values `NOT_RUN` | Same device/codec/mode/log settings required. This is not panel/display latency. | No comparable before/after live-video data. |
| Stats logger contention with frame arrivals | `NOT_RUN` | A blocked-logger concurrency regression confirms `onReceived` can proceed while formatting/logging is stalled; no wall-time or CPU improvement is claimed. | Synthetic latch test only. | No live stream profile collected. |
| MediaCodec per-output allocation | One `BufferInfo` allocation per `drainOutput()` invocation at start SHA | One worker-owned `BufferInfo` per decoder | Source-level allocation reduction; no allocated-bytes profile was collected. | Device allocation profile pending. |
| Process allocation, GC and PSS | `NOT_RUN` | Optional five-second runtime-stat sample behind the `DiPlay-VideoStats` DEBUG log tag; values `NOT_RUN` | Process allocation bytes/s, GC/minute and process PSS KB. Per-thread object counts and FD counts are not sampled. `sendCommand` results reflect local event-channel writes, not iPhone acknowledgement. | No live stream/profile collected. |
| Activity log dispatch cost | `NOT_RUN` | `NOT_RUN` | OPT-06 requires real redacted startup/reconnect counters and UI/file-write timing. | No CarPlay target session available; batching not adopted. |

## Open gates

- HW-01–HW-13 target acceptance: `BLOCKED_DEVICE` until TS10/K706 and supported iPhone evidence is collected.
- MAIN/ALT physical keyframe recovery and changed-Surface frame delivery: `BLOCKED_DEVICE`.
- G8 release: `NO_GO_THIS_RUN`; signed update/rollback remains `BLOCKED_ENV` without authorized signing credentials.
- Attached independent review received; F-01–F-06 are corrected. A second post-fix review remains `NOT_RUN`, preserving the existing direction.
- PR remains open Draft; no merge or main update is requested.

## Regression risk and rollback

- OPT-01, OPT-02 and OPT-04 are isolated source changes with targeted regression coverage and phase commits.
- OPT-03 is limited to decoder-worker wait/drain behavior; keep the 250 ms stale-frame limit, 500 ms input pump limit and 8 MiB/60-frame queue limits unchanged.
- OPT-05 changes recovery behavior and must have a release-safe default off until target-device evidence supports activation. Roll back only its phase commit if frame continuity, keyframe rate or Surface ACK behavior regresses.
- OPT-06 remains unadopted absent real measurements. Keep NAL changes out unless profiling identifies duplicate copies.

## License and secrets check

- The target repository and donor commit both carry GPL-3.0 `LICENSE` files. This is an adaptive implementation against existing DiPlay abstractions; no donor UI, Web TLS, MediaTap or NAL reverse-conversion implementation was copied, and no new third-party files were imported.
- `scripts/check_public_tree.py` passes. No credentials, device identifiers, media payloads, or runtime log captures were added.

## Change commits

- `96edfe1e` — OPT-00 baseline, disposition table and evidence report.
- `feca360e` — OPT-01 application-context `UsbManager` lookup and regression test.
- `11df796b` — OPT-02 decoder-owned reusable `BufferInfo`.
- `67b943cc` — OPT-03 bounded pending-output drain and idle queue wait.
- `3473fd23` — OPT-04 type-specific MAIN/ALT keyframe commands and owner fences.
- `d6fbc8e6` — OPT-05 default-off, bounded Surface recovery policy and feature documentation.
- `5694f3a7` — OPT-06 non-adoption evidence and OPT-07 NAL conversion regression coverage.
- `245e06a8` — OPT-08 decoder/recovery measurement instrumentation and focused tests.
- `54b65005` — Merge current remote PR #45 work to preserve the concurrent USB handoff/review updates; no main branch update or PR merge.
- `99b96535` — Initial evidence and Working Memory closeout.
- `178739b1` — Merge remote PR #45 USB review follow-up documentation without dropping the OPT implementation.
- `000e59e9` — Report and Working Memory closeout; run #551 passed on this PR head.
- `8b64714d` — F-01 DSP retirement failure now fails closed with latency-preserving PCM fallback.
- `b5a6182d` — F-02/F-03 ignore non-picture outputs and preserve pending-frame waits.
- `1c8ff8c6` — F-04 serialize MAIN/ALT keyframe dispatch with screen-owner replacement.
- `190d4001` — F-05 snapshot video stats and log outside the frame-arrival lock.
- `24a6d650` — F-06 label codec Surface release timing and report correlation fractions.
