# XCertPlay 3e07bca Adaptive Port — PR45

## Baseline

- Source: `shilapi/xcertplay@3e07bca57c8700d1268db3f45ba2c22607da1930`
- Target PR: https://github.com/choko510/DiPlay/pull/45
- Target start SHA: `c8a1ab0bd9126b7bc6f11e28e2fa6fa2b8cea78c`
- Target branch/base: `codex/step4-pr-00-test-baseline` / `main` (`95ba398f95ee104527812cd2b095977d71c51f98`)
- PR status: open, Draft, unmerged
- Code/test environment: Windows; Android Studio JBR 21.0.9; Android SDK 37.1; connected API 36 x86_64 emulator. No TS10/K706 head unit or iPhone is attached.
- Start worktree was clean. Its local HEAD is detached at the PR head because the existing branch is checked out in another managed worktree. New commits will be fast-forwarded to the existing PR branch only.
- Baseline focused tests passed: shared 52/52 and common 22/22, with zero failures, errors, or skips. Exact commands are in [Test commands and results](#test-commands-and-results).

## 20-file disposition

| Donor file | Disposition | Target / rationale |
|---|---|---|
| `common/.../CarPlayHostActivity.kt` | `PARTIAL` | Inspect log delivery; batch only if startup/reconnect measurements show material UI-thread load. |
| `common/.../CarPlayWebDisplayFactory.kt` | `SKIP_NO_TARGET` | No browser display/preview path exists in this PR. |
| `common/.../HandshakeTimeline.kt` | `SKIP_NO_TARGET` | No HandshakeTimeline or prepared UI model exists. |
| `common/.../JsonProfileVehicleData.kt` | `SKIP_NO_TARGET` | No corresponding vehicle-profile parser exists. |
| `common/.../WebDisplayCertificate.kt` | `NOT_ADOPTED_RISK` | Web DER/private-key caching is unrelated and increases secret-handling risk. |
| `common/.../ui/components/Lavender.kt` | `SKIP_NO_TARGET` | The donor UI library is absent. |
| `common/.../ui/components/RenderThreadLoop.kt` | `SKIP_NO_TARGET` | No matching animated-vector UI is needed. |
| `common/.../ui/home/HomeScreen.kt` | `SKIP_NO_TARGET` | No corresponding Compose home screen exists. |
| `common/src/main/res/drawable/avd_glyph_spin.xml` | `SKIP_NO_TARGET` | Donor-only loading animation. |
| `common/src/main/res/drawable/avd_phase_pulse_disc.xml` | `SKIP_NO_TARGET` | Donor-only loading animation. |
| `common/src/main/res/drawable/avd_phase_pulse_ring.xml` | `SKIP_NO_TARGET` | Donor-only loading animation. |
| `common/src/test/.../CarPlayWebDisplayTest.kt` | `SKIP_NO_TARGET` | No web display feature to test. |
| `shared/.../airplay/CarPlayMediaEngine.kt` | `ADAPT` | Route recovery commands by MAIN/ALT type; ALT needs the SETUP UUID, with owner checks preserved. |
| `shared/.../media/AndroidMediaSink.kt` | `ADAPT` | Reuse worker-owned `BufferInfo`, bound idle output waits, and evaluate conservative Surface recovery without replacing lifecycle ownership. |
| `shared/.../media/MediaCodecSupport.kt` | `FORK_SUPERIOR` | Keep the existing Annex B path and random-access scan; no browser forwarding consumer exists. |
| `shared/.../media/MediaTap.kt` | `SKIP_NO_TARGET` | No Web MediaTap consumer exists. |
| `shared/.../media/VideoDecodeFlow.kt` | `FORK_SUPERIOR` | Keep the existing queue and reference-chain separation. |
| `shared/.../orchestration/CarPlayController.kt` | `ADAPT` | Obtain `UsbManager` from the already-owned application context. |
| `shared/src/test/.../airplay/CarPlayMediaEngineTest.kt` | `ADAPT` | Add MAIN/ALT command-shape and missing-UUID coverage while retaining stale-owner tests. |
| `shared/src/test/.../media/MediaCodecSupportTest.kt` | `PARTIAL` | Retain and rerun existing forward-conversion/random-access coverage; do not import reverse-conversion tests. |

## Implemented optimization phases

| Phase | Intended change | Status | Test evidence / rollback |
|---|---|---|---|
| OPT-00 | Freeze start state, review ownership and design regressions | `IMPLEMENTED_AND_TESTED` | Start SHA, 20-file disposition, ownership invariants and focused baseline are recorded above. |
| OPT-01 | Read `UsbManager` from `appContext` | `IMPLEMENTED_AND_TESTED` | New regression failed before the fix (`usbManager must not be null`) and passed after it; `CarPlayControllerWatchdogTest` passed 5/5. Revert: OPT-01 commit. |
| OPT-02 | Reuse one `MediaCodec.BufferInfo` on the decoder worker | `IMPLEMENTED_AND_TESTED` | `AndroidMediaSinkStateTest` passed 19/19; its regression verifies separate MAIN/ALT decoder-owned instances. This proves ownership and source-level allocation removal, not a codec runtime gain. Revert: OPT-02 commit. |
| OPT-03 | Reduce idle polling while draining queued codec output with bounded waits | `NOT_RUN` | Regressions and phase test to be recorded. Revert: phase commit. |
| OPT-04 | Send correctly addressed MAIN/ALT keyframe requests | `NOT_RUN` | Regressions and phase test to be recorded. Revert: phase commit. |
| OPT-05 | Recover a changed Surface only after a bounded no-output condition | `NOT_RUN` | Device-unproven behavior must default off in release. Revert: phase commit. |
| OPT-06 | Batch Activity log delivery | `NOT_RUN` | Adoption requires real startup/reconnect counters; do not infer from synthetic tests. |
| OPT-07 | Validate existing NAL conversion and justify non-migration | `IN_PROGRESS` | Existing `MediaCodecSupportTest` baseline passed; final source and output-byte evidence to be recorded. |
| OPT-08 | Add bounded, payload-free video-path measurements | `NOT_RUN` | Report emulator/device comparisons only when actually measured. |

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
| API 36 instrumentation / physical acceptance | `c8a1ab0` | emulator attached; no target hardware | `NOT_RUN` at baseline. |

## Performance before/after

| Metric | Baseline | Optimized | Units / sample | Device / confidence |
|---|---|---|---|---|
| Decoder idle wake rate and thread CPU | `NOT_RUN` | `NOT_RUN` | Requires decoder runtime; no source-level estimate substituted. | API 36 emulator instrumentation pending; TS10/K706 unavailable. |
| Queue-wait/dequeue-output time and frame-to-Surface p50/p95/p99 | `NOT_RUN` | `NOT_RUN` | Same device/codec/mode/log settings required. | No comparable before/after data yet. |
| MediaCodec per-output allocation | One `BufferInfo` allocation per drain invocation at start SHA | `NOT_RUN` | Source-level allocation reduction will not be described as a measured runtime gain. | Emulator/device allocation profile pending. |
| Activity log dispatch cost | `NOT_RUN` | `NOT_RUN` | OPT-06 requires real redacted startup/reconnect counters. | No CarPlay target session available. |

## Open gates

- HW-01–HW-13 target acceptance: `BLOCKED_DEVICE` until TS10/K706 and supported iPhone evidence is collected.
- MAIN/ALT physical keyframe recovery and changed-Surface frame delivery: `BLOCKED_DEVICE`.
- G8 release: `NO_GO_THIS_RUN`; signed update/rollback remains `BLOCKED_ENV` without authorized signing credentials.
- Independent review: `NOT_RUN`, preserving the existing direction.
- PR remains open Draft; no merge or main update is requested.

## Regression risk and rollback

- OPT-01, OPT-02 and OPT-04 are isolated source changes with targeted regression coverage and phase commits.
- OPT-03 is limited to decoder-worker wait/drain behavior; keep the 250 ms stale-frame limit, 500 ms input pump limit and 8 MiB/60-frame queue limits unchanged.
- OPT-05 changes recovery behavior and must have a release-safe default off until target-device evidence supports activation. Roll back only its phase commit if frame continuity, keyframe rate or Surface ACK behavior regresses.
- OPT-06 remains unadopted absent real measurements. Keep NAL changes out unless profiling identifies duplicate copies.

## Change commits

Pending implementation; add each phase SHA and synopsis after committing.
