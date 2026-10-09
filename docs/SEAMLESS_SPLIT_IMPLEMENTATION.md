# Seamless CarPlay + YouTube Split View implementation log

Specification: `DiPlay_Seamless_Split_View_Implementation_Spec_v1.md` (v1.0, 2026-10-09). Initial analysis base: `db9853266c71e2351c08b8934f223f7df671fd74`. Final PR base: `95ba398f95ee104527812cd2b095977d71c51f98` (`origin/main` after PR #14 and #16 landed). Work branch: `codex/seamless-split-view-implementation`.

Open PRs checked before implementation: #14 (audio DSP, open) and #16 (wired NCM diagnostics, draft). Their changes are not part of this branch.

## PR-01 — Baseline and current architecture

- Current remote `main` matches the specification's base SHA. `gh` is unavailable in the checkout; public GitHub pages were inspected directly.
- Existing split entry changes the CarPlay/YouTube pane weights, then `scheduleDisplaySize()` treats the narrower `TextureView` as a changed negotiated canvas and calls `restartCarPlay()`. The restart closes and recreates the controller, AirPlay session, and screen stream.
- Existing `/info` declares one main-display ViewArea. `SETUP` enables `viewAreas`. No verified runtime switch payload or head-unit trace exists in this checkout.
- `AndroidMediaSink.VideoDecoder` uses fixed dimensions from the session config. `INFO_OUTPUT_FORMAT_CHANGED` is logged, and output crop is not passed to the host geometry. Touch contacts are normalized to the gesture view and then scaled to the full declared canvas.
- Baseline command: `:shared:testDebugUnitTest :common:testDebugUnitTest`. Shared: 357 tests, 1 failure (`AirPlayIapTunnelStreamTest.ipv6ListenerAcceptsIpv6Loopback`, Windows `SocketException: Permission denied: connect`); common: 116 tests, 0 failures.
- Repeated the full baseline test command under Android Studio JBR 21 with the Gradle JVM criteria file restored in `finally`; the same shared IPv6 test failed, so this is not isolated to JDK 25 or the known Robolectric native-DLL issue.
- Baseline command: `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --continue`. All four tasks passed. Local SDK 37.1 and pinned NDK 27 are present; the build also reports a nonfatal local NDK 28.2 `source.properties` warning and duplicate platform SDK path warning.
- `python scripts/check_jni_mapping.py` was attempted before the benchmark mapping exists and reported `Missing optimized mapping: mobile\build\outputs\mapping\benchmark\mapping.txt`; rerun after benchmark assembly. `git diff --check` passed.
- Stage review: the disconnect is caused by UI pane width being treated as a transport display-size change. Protocol acceptance and dynamic frame behavior remain hardware-dependent.

## PR-02 — Local scale without restart

- Added a Gradle opt-in flag: `-PdiplaySplitViewMode=local`. It defaults to `off`; only debuggable builds can select local mode. `off` keeps the existing legacy resize/reconnect behavior.
- Local mode uses the full host row as the negotiated canvas during Split. TextureView pane-size changes update a fit-center matrix while preserving the same `Surface`; a separate geometry snapshot drives the inverse touch transform. Touches in letterbox bars are dropped and active touch sequences are released/cancelled when geometry changes.
- Physical host-row size changes still go through the existing resize debounce and restart handoff. Keyboard resizing remains under the existing IME suppression logic.
- Tests: `:shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.CarPlayRenderGeometryTest` (4 passed); `:common:testDebugUnitTest --tests com.shilapi.xcertplay.youtube.SplitViewModeTest` (2 passed).
- Build/lint: with `-PdiplaySplitViewMode=local`, `:mobile:lintDebug`, `:automotive:lintDebug`, `:mobile:assembleDebug`, and `:automotive:assembleDebug` all passed.
- Review and repair: the first common compile found the new geometry types were `internal` across the shared/common module boundary; exposed the narrow geometry API and reran tests/build successfully. Geometry tests cover fit-center, letterboxing, viewport mapping, rotation matrix and invalid bounds.
- Gate 1 status: local code path and coordinate math are verified; `HARDWARE UNVERIFIED`. A real CarPlay session, visible TextureView output, calibrated HID positions, and measured controller/session/stream restart deltas require the target head unit and iPhone.

## PR-03 — Multi-ViewArea declaration

- Added immutable `CarPlayRect`, `CarPlayViewArea`, and defensively copied `DynamicViewAreaConfig`; configurations validate area count/order, initial index, safe-area containment, canvas bounds, and even codec alignment.
- Added a two-area factory for initial full plus 55% split geometry. It clips the saved safe-area insets to each viewport, rounds inward to even coordinates, and declines Dynamic advertisement if the safe region cannot be represented.
- `/info` serializes the existing root main display unchanged and emits the ordered view-area children only when the optional config is present. The default path retains the exact existing single-area builder and initial index 0.
- `-PdiplaySplitViewMode=dynamic-experimental` is an additional debug-only opt-in. It advertises two areas during the next session's initial `/info`; no runtime switch command is sent yet at this stage.
- Tests: `:shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.DynamicViewAreaFactoryTest --tests com.shilapi.xcertplay.airplay.AirPlayInfoPlistTest` passed (18 tests, 0 failures). Coverage includes 480p, 720p, 1080p, 4:3, portrait-like and ultrawide canvas sizes, invalid fractions, clipping/alignment, old single-area output, and binary-plist encode/decode order and integer normalization.
- `:common:testDebugUnitTest --tests com.shilapi.xcertplay.youtube.SplitViewModeTest -PdiplaySplitViewMode=dynamic-experimental` passed (2 tests). With the dynamic flag, mobile/automotive debug lint and both debug builds passed.
- Review: `/info` retains the original display parent dimensions and UUID; the experimental area list is contained in that parent. Existing stream 111/cluster configuration and audio negotiation were not modified.
- Gate 2 status: static config shape and tests pass; iPhone `/info` acceptance and initial HID/audio behavior remain `NOT RUN — hardware required`. No dynamic wire switch is implemented or claimed by PR-03.

## PR-04 — Protocol diagnostics

- Added a typed parser for incoming `requestViewArea` messages. It checks the main display UUID, integer index, and whether two areas were declared; invalid and conflicting requests are recorded without changing layout. Unknown command types remain untouched.
- Added `AirPlaySession.writeViewAreaSelection()`, limited to sessions configured with exactly two areas. It serializes the candidate `updateViewArea` dictionary on the existing event-channel write lock and distinguishes `NOT_READY`, invalid/undeclared, closed, successful-write, and write-failure outcomes. A failed candidate write does not invoke `close()`; existing `sendCommand()` policy remains unchanged.
- The candidate follows the nonofficial reverse-engineering format described in [the cited receiver research](https://github.com/harman-f/mhi2_altscreen_carplay/blob/main/docs/architecture/NAVIGATION_COMPOSITION_AND_SAFEAREA.md). It is treated as a test hypothesis, not an Apple-confirmed or iPhone-accepted payload. The event-channel HTTP 200 behavior remains the existing generic response; no undocumented acknowledgement is inferred.
- Logs use a process-local anonymous AirPlay session tag and include only declared area counts, requested index, and disposition. They do not include device identifiers or binary plist payloads.
- Tests: `:shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.AirPlayViewAreaCommandTest` passed (5 tests), including payload round-trip, parser validation, not-ready behavior, and a forced output-stream failure that leaves the session open. `:common:testDebugUnitTest --tests com.shilapi.xcertplay.youtube.SplitViewModeTest -PdiplaySplitViewMode=dynamic-experimental` passed (2 tests).
- Build/lint: dynamic experimental debug mode passed mobile/automotive debug lint and both debug APK builds.
- Stage review: dynamic commands remain restricted to an explicit debuggable build flag and a two-area session. A write success is not treated as a layout confirmation; transition-state integration is next, while actual event acceptance remains hardware unverified.

## PR-05 — Dynamic ViewArea coordinator

- Added a session-identity- and generation-fenced coordinator with declared-area gating, `requestedIndex`/`committedIndex`, pending token/deadline, one retry allowance, and a per-session failure latch.
- Local/dynamic Split and return-to-full now issue the experimental command only for the debug dynamic mode. The command runs on the existing single-thread event writer; event-channel-not-ready retries once after 250 ms. Write success does not commit the transition. A geometry timeout/write failure enters local rendering fallback and never calls `restartCarPlay()`.
- Valid phone `requestViewArea` messages are checked against the current session, display UUID, declared count, and index, then applied to the host split/full layout if it can be represented. Requests that conflict with mode/session state are logged and held. The generic HTTP 200 response remains unchanged because the receiver-specific response contract is not established.
- Named retry/timeout callbacks are removed on session replacement/end, restart, shutdown, and Activity destruction. New requests supersede the prior token; callbacks from another session/generation are ignored.
- Tests: `:common:testDebugUnitTest --tests com.shilapi.xcertplay.youtube.DynamicViewAreaCoordinatorTest -PdiplaySplitViewMode=dynamic-experimental` passed (5 tests). Cases cover write-not-confirmation, matching geometry evidence, rapid toggles, stale callbacks, bounded retry, timeout, phone request gating, and session replacement.
- Build/lint: `-PdiplaySplitViewMode=dynamic-experimental` mobile/automotive debug lint and both debug builds passed.
- Stage boundary: PR-05 introduced the state machine; PR-06 connects output geometry evidence. Actual phone acceptance and on-device geometry remain hardware-dependent.

## PR-06 — Rendering and MediaCodec geometry

- Added validated `VideoOutputGeometry` derived from coded dimensions, inclusive crop coordinates, and rotation. `AndroidMediaSink` publishes a geometry only after it submits the first output buffer with that format; the host updates the existing TextureView transform and uses the same geometry for touch inversion.
- Dynamic rendering chooses the coordinator's committed viewport. A full-canvas frame is cropped to the selected viewport; an area-sized frame fills that viewport. Other shapes remain unclassified and use the conservative full-source fit. A dynamic switch is only committed after a changed output geometry uniquely matches a declared area and TextureView reports an update. That remains geometry evidence, not proof of phone-side layout correctness.
- Adaptive decoder setup checks `FEATURE_AdaptivePlayback`, codec size capability, and the negotiated canvas bound before setting both `KEY_MAX_WIDTH` and `KEY_MAX_HEIGHT`. A codec that rejects those hints is retried without adaptive bounds on the same codec name before the existing alternate decoder attempts.
- For adaptive in-band config changes, the decoder keeps its worker and waits for an IDR/IRAP. It prefixes all parsed H.264 SPS/PPS or HEVC VPS/SPS/PPS data to that keyframe in one input buffer. Non-adaptive coded-size changes use a decoder-only stop/configure/start within the declared canvas bound, then request a throttled keyframe. Invalid or oversized output geometry is not applied to UI state.
- Tests: 27 shared tests passed across `MediaCodecSupportTest` (12), `VideoOutputGeometryTest` (5), `VideoDecodeQueueTest` (5), and `AndroidMediaSinkStateTest` (5); 7 common tests passed across the ViewArea matcher and coordinator. Total: 34 passed, 0 failed.
- Build/lint: `-PdiplaySplitViewMode=dynamic-experimental` mobile/automotive debug lint and debug builds passed.
- Hardware remains unverified: codec capability reporting, actual H.264/HEVC adaptive behavior, vendor `BAD_VALUE`, setOutputSurface behavior, crop/rotation presentation, black-frame duration, and visible texture/touch alignment require the target head unit.

## PR-07 — Touch, audio, and Gecko integration

- The same immutable `CarPlayRenderGeometry` now drives both `TextureView.setTransform()` and inverse HID mapping. Split-pane letterbox presses are dropped; changing the transform releases active contacts before a new gesture can use the updated coordinates.
- Kept the existing `TextureView`/`SurfaceTexture` through pane-weight changes. Physical host display/IME resize handling still uses the existing debounce and restart handoff; split-only pane changes resolve to the existing full negotiated canvas.
- Dynamic selection remains inside the existing AirPlay session and single event writer. MediaCodec adaptive playback or decoder-only reconfiguration does not replace `CarPlayController`, AirPlay session, or the audio renderer. No audio negotiation or Gecko profile/session implementation was changed; existing common/shared tests were rerun as integration regression coverage.
- Tests before the upstream advance: shared 383 and common 126 passed, including render transform, crop/rotation, two-pointer/up, letterbox rejection, session fencing, existing Gecko reuse/profile, popup/fullscreen and IME coverage. Final post-rebase results are recorded under PR-08.
- Review pass 2: verified pane-only resizing does not reach `restartCarPlay()`, `SurfaceTexture` callbacks are lifecycle-fenced, touch releases on geometry changes, and the existing Gecko warm-reuse path remains intact. The callback counter is explicitly only a `SurfaceTexture` update proxy; it does not prove compositor scanout or visible correctness.
- Gate 1 local path: static code and unit tests PASS; no Android instrumentation device or head unit was available, so displayed output, HID calibration, audio continuity, Gecko interaction during an actual CarPlay split, and lifecycle/rotation on target hardware remain `NOT RUN — hardware required`.

## PR-08 — Fallback, metrics, and quality audit

- Completed debug-only opt-in modes: default `off`, `local`, and `dynamic-experimental`. `SplitViewMode.fromBuild()` forces release/non-debuggable builds to the legacy path; the dynamic advertisement and candidate command are not forced on for production.
- Added per-process counters for ViewArea advertisement/request/write/geometry observation/timeout/fallback, controller starts/closes, session changes, SETUP/TEARDOWN, screen streams, codec configuration/output formats/reconfiguration, Surface/TextureView callbacks, decoder submissions, and existing Gecko split/FCP events. The first Split request records a counter baseline; later exits log deltas to make repeated-cycle comparisons possible.
- A successful event-channel write is counted on the writer thread even if a newer UI generation supersedes its callback. It still means only that bytes were written. `viewarea_transition_confirmed` is a local output-geometry plus `TextureView` update observation; it is not an iPhone protocol acknowledgement.
- Review pass 1 found that stale UI callbacks could omit a successfully written command from the counter and that exit logs showed only cumulative totals. Moved write-success counting to the serialized writer, added baseline-delta output, and added a regression test for counter deltas. Re-review and targeted common tests passed.
- During the task, remote main advanced with PR #14 (audio DSP) and PR #16 (wired NCM diagnostics). Rebased onto `95ba398f95ee104527812cd2b095977d71c51f98`; the reviewed diff against that base contains only the Split View work and its records/tests.
- Rebase review: verified that `AndroidMediaSink.createAudioRenderer()` and the host's DSP config provider are unchanged from the new base; this branch adds video geometry/decoder callbacks only. No NCM files are in the branch diff. The existing Gecko profile/session code also remains unchanged outside the split-host calls already covered above.
- Final post-rebase tests: `:shared:testDebugUnitTest :common:testDebugUnitTest --continue` passed (shared 479, common 146; 0 failures, 0 errors, 0 skipped). The initial baseline had one Windows IPv6 loopback permission failure; it did not recur.
- Revalidated after rebase with `-PdiplaySplitViewMode=dynamic-experimental`: mobile/automotive `lintDebug` and `assembleDebug` passed. Mobile/automotive `assembleBenchmark` passed with R8, and `python scripts/check_jni_mapping.py` verified both optimized mappings. The SDK duplicate-platform warning and unused local NDK 28.2 `source.properties`/strip warnings were non-fatal; pinned NDK 27 builds completed.
- `git diff --check` passed after the metric correction. No connected-device instrumentation, emulator, head unit, iPhone, CPU/GC/PSS/thermal capture, black-frame duration, 20-cycle timing, call/Siri/navigation audio test, H.264/HEVC vendor behavior, or long-playback/memory test was available; each remains `NOT RUN — hardware required`.
- The second review found no remaining code-level defect; the unresolved items below require device evidence rather than more local code changes.

## Hardware verification status

No target head unit or iPhone is available in this execution environment. All wired/wireless protocol acceptance, CarPlay session/stream continuity, audio interaction, Siri/navigation/call behavior, live H.264/HEVC adaptation, visible frame geometry, and touch calibration remain `NOT RUN — hardware required` until tested on the target equipment.

## Gate status at implementation completion

- Gate 1 — **PASS (code/tests), HARDWARE UNVERIFIED**: split-only pane changes preserve the negotiated canvas and use local fit/touch transforms.
- Gate 2 — **NOT RUN**: iPhone acceptance of the two-area `/info` and initial HID/audio behavior needs a real iPhone/head unit.
- Gate 3 — **NOT RUN**: the candidate `updateViewArea` wire format has not been accepted on-device; no session/stream continuity claim is made.
- Gate 4 — **PASS (geometry/decoder policy unit coverage), HARDWARE UNVERIFIED**: crop, rotation, adaptive bounds, CSD/keyframe, fallback, and touch geometry are covered in code tests; actual codec/surface display needs hardware.
- Gate 5 — **NOT RUN**: wired/wireless 20-cycle, long playback, low-memory, profile, Siri, call, and navigation scenarios were not available.
- Gate 6 — **PASS**: default `off`, release parser falls back to legacy, and old single-area serialization remains unchanged in tests.

## Device A/B matrix remaining

- A — Legacy one-area plus reconnect baseline: **NOT RUN**.
- B — Legacy one-area plus local scaling: **NOT RUN**; the local path is covered by geometry/policy unit tests only.
- C — Two-area `/info`, initial full display, HID and audio negotiation: **NOT RUN**.
- D — Dynamic `1 → 0` selection with unchanged controller/session/screen stream and observed frame shape: **NOT RUN**; the command is still a nonofficial wire hypothesis.
- E — Repeated switching with touch, YouTube/Gecko, Siri/navigation/call audio, IME, popup, fullscreen and profile reuse: **NOT RUN**.
- F — Unsupported phone, event write failure, timeout, malformed/oversized frame and local recovery: **unit/fake-stream paths tested where possible; device recovery NOT RUN**.

Run the matrix on the same head unit and iPhone over both wired and wireless CarPlay, record exact device/iOS/Android/codec versions, and compare controller/session/stream counter deltas before claiming Gates 1–5.
