# PR #45 Review Fix Implementation Report

**Updated:** 2026-10-28 (JST)

**PR:** [#45](https://github.com/choko510/DiPlay/pull/45)

**Branch:** `codex/step4-pr-00-test-baseline`

**Base:** `main@95ba398f95ee104527812cd2b095977d71c51f98`

**Start HEAD:** `a99066f6133c2fe6f5258a2515a89986b75e042e`

**Remote USB/video source snapshot:** `7aff2f8bc8bff05af3962e6c6887e020ad80be81` (source/test head `3e81cd5d`)

**Subsequent independent-review fixes:** F-01–F-06 are in commits `8b64714d` through `24a6d650`, integrated with the remote USB/video updates. The final codec release metric is `receivedToCodecSurfaceReleaseMs`; local Shared/Common suites pass 689/689 and 241/241. GitHub run [#40679997119](https://github.com/choko510/DiPlay/actions/runs/40679997119) on head `daeba474` passed the full host and API 35 lanes; run #568 below covers the earlier remote head.

PR #45 remains open and Draft. No merge, close, or ready-for-review action was performed. The concurrent remote video/USB commits through `f7aa6705` and original opt-in iPhone USB commit `d140f6e` were preserved. P1 shutdown and deferred-replay paths now have source fixes and regression coverage; Android 10 acceptance and no-attach-event re-enumeration remain open.

| Gate | Result | Evidence / limit |
|---|---|---|
| Code | `SOURCE_FIXES_COMPLETE_WITH_DEVICE_LIMITS` | Shutdown timeouts now retain a teardown barrier; internal deferred replay and explicit user retries bypass attach debounce; handoff release is generation/owner guarded. Video output has a 50 ms drain grace after recent codec activity and the latency metric name identifies Surface submission. Device-only USB and actual display-presentation evidence remain open. |
| Local host checks | `PASS` | Full Shared 689/689 and Common 241/241 suites passed after F-01–F-06. Mobile/automotive Debug builds/lint passed on the remote source snapshot; run #40679997119 also passed the current head's full build/lint checks. |
| GitHub CI | `PASS_CI` | Run [#40679997119](https://github.com/choko510/DiPlay/actions/runs/40679997119) passed changes/EX-08, host native, ASan/UBSan, full unit/lint/Debug+optimized builds/JNI+R8, `host-baseline`, Draft scope, and API 35 instrumentation on head `daeba474`; check and instrumentation artifacts `12811838948` and `12812213115` were uploaded. The separate Draft-unit and quick-baseline jobs were skipped because full checks ran. Earlier run #568 is the remote baseline. |
| Device acceptance | `BLOCKED_DEVICE` | Only an API 36.1 x86_64 emulator was attached. TS10/K706 and iPhone acceptance remains untested. |
| Release | `NO_GO_THIS_RUN` | Device, signed update/rollback, and independent review gates remain incomplete. |

## Commits

| Commit | Change |
|---|---|
| `5a29318` | Reproduce wired USB attach on an existing Host; this regression failed against the pre-fix path. |
| `01b6c5b` | Resume wired startup on a verified attach intent while preserving existing owner/readiness guards. |
| `68a18aa` | Add measured asynchronous DSP graph retirement, fail-closed renderer status, and bounded video decoder recovery with regression tests. |
| `d140f6e` | Concurrent PR-branch update adding opt-in iPhone USB auto-launch; preserved unchanged. |
| `184c02a` | Merge the concurrent PR-branch update without rewriting either history. |
| `c8a1ab0` | Cover active-controller and shutdown-time USB attach deduplication. |
| `344181eb` | Add attach adoption of a background-owned session and MAIN/ALT release-failure isolation tests. |
| `5f69b20` | Keep the newest validated USB request through Controller handoff and reject cross-transport session adoption. |
| `1290fb8` | Replace a scheduler-sensitive 2 ms USB timeout test with a delayed deterministic fixture. |
| `a3c66a6` | Exercise 20 repeated wired attaches and 20 handoff identity replacements. |
| `8151439` | Merge remote OPT-00–OPT-08/video changes into the updated USB branch without rewriting history. |
| `7aff2f8` | Keep shutdown/restart fail-closed until Controller and sink close; bypass attach debounce for deferred/manual retries; add video drain grace and precise latency label. |
| `3e81cd5` | Assert background-session ownership remains gated through a Controller-close timeout. |

The implementation report and test matrix are committed after the source/test head; their documentation commit ID is recorded in the final response and the PR branch history.

## Phase 00 — Baseline and current HEAD

- The initial GitHub PR snapshot matched start HEAD `a99066f`, base `main@95ba398`, and the local PR branch. At that point the latest run was #505: static checks passed while host-baseline and API 35 instrumentation were skipped.
- During implementation, GitHub advanced the same PR branch to `d140f6e`. The branch was fetched and merged without rebasing or force-pushing; the new opt-in iPhone USB auto-launch behavior was preserved.
- Current source snapshot `7aff2f8b` includes the remote Video/OPT work and the USB shutdown/deferred-replay follow-up; current test head is `3e81cd5d`. These supersede older current-head summaries below; the PR is still Draft. No upstream-ledger row or disposition was changed.

## Phase 01 — USB-01 wired attach recovery

**Confirmed defect:** a verified internal `USB_DEVICE_ATTACHED` delivered to an existing wired Host cleared restart suppression, then fell through to `CarPlayHostLayoutCommand.NONE` without re-evaluating startup.

`CarPlayHostActivity.onNewIntent()` now consumes and sanitizes the existing single-use internal action first. Wired attach calls `maybeStartCarPlay()`, retaining its controller/background-owner, shutdown, display, VPN, permission, menu, and handshake guards. Wireless-to-wired switching remains behind the existing shutdown flow and a per-Activity one-shot gate. An attach received during a non-process shutdown queues one coalesced resume/switch callback and starts the new Host after the old controller and sink teardown completes. A process-exit shutdown logs a blocked reason and does not revive the process.

`CarPlayUsbAttachActivity` CH341/current-device/permission validation, exported trampoline policy, internal token consumption, NCM setup, and wired startup protocol were not changed. Logs contain event/result and generation fields only; no serial, MAC, or payload data is added.

**Tests:** `verifiedUsbAttachReevaluatesStartForAnExistingWiredHost`, `verifiedUsbAttachWaitsForVpnReadinessBeforeStarting`, `twentyVerifiedWiredAttachesKeepTheExistingControllerAndDoNotStartAnother`, `wiredUsbAttachDuringShutdownQueuesOneResumeAfterTeardown`, and `wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown`, plus existing token, spoofing, CH341, permission, stale owner, and reconnect-gate tests. The first wired regression failed against the pre-fix branch, then passed after the fix. A USB attach specifically paired with a different live background-session owner has no new end-to-end test; the production `adoptBackgroundSession()`/owner-epoch guards remain in place and existing stale-owner callbacks pass.

## Phase 01B — iPhone USB auto-launch handoff review

`adoptBackgroundSession()` now compares the existing Controller transport with the Host's requested transport before adopting it. A mismatch enters the existing stop/handoff path; the new wired Controller is not started until the previous owner has closed. During the transition, `IphoneUsbAutoLaunchRequestQueue` keeps only the latest validated attach identity and preserves the Host layout snapshot. Owner epochs and handoff generations reject stale completion callbacks. Deferred requests are revalidated against the current USB inventory before dispatch.

An existing wired Controller remains the owner for duplicate attach events. Settings/foreground open actions bring that wired Host forward; a system attach does not steal focus. USB Host feature declaration affects the status message but does not disable the switch. The exported iPhone entry requires the system `MANAGE_USB` caller permission and still validates the attach action and current device.

USB identities remain exact (`deviceId` and `deviceName` included). A newly observed attach can replace a stale request; the implementation does not guess that a different identity is the same phone when no new attach notification arrives. Timed same-device rediscovery is not inferred because multiple attached Apple devices with the same descriptors cannot be safely distinguished through that heuristic. Android 10 OEM chooser delivery and no-event re-enumeration remain device gates.

**Tests:** `backgroundControllerIsReusableOnlyWhenItsTransportMatchesTheRequestedTransport`, `wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown`, `deferredQueueKeepsOnlyTheLatestCurrentUsbIdentity`, `handoffQueueUsesTheLatestIdentityAndPreservesTheSplitSnapshot`, `twentyVerifiedWiredAttachesKeepTheExistingControllerAndDoNotStartAnother`, `duplicateSystemAttachDoesNotRaiseTheHostButAnExplicitOpenDoes`, `failedDispatchCanReleaseItsOwnGenerationForRetry`, stale/current identity policy tests, and manifest/explicit-intent validation tests. The 20-event checks model event sequences, not 20 physical USB re-enumerations.

## Phase 01C — Confirmed teardown and retry ownership

The shutdown and `restartCarPlay()` paths now create a process-local teardown token before dropping the Activity's Controller references. A Controller-close timeout reports `TIMED_OUT`, keeps the token visible through `CarPlayBackgroundSession.hasSession()`, retries `awaitClosed()` off the main thread, and does not run Host-start callbacks. The token is released only after the Controller close is confirmed and the old `AndroidMediaSink.close()` call returns. A close exception reports `FAILED`; if the Controller is still open, close is retried. The process-exit path can still terminate the process without opening a replacement Controller.

Shutdown callbacks now receive `CLOSED`, `TIMED_OUT`, or `FAILED`. Callbacks that create a new Host act only on `CLOSED`; timeout/failure notifications leave them pending for the later confirmed close. `maybeStartCarPlay()` and `startCarPlay()` both check the teardown barrier. A background stop request with no remaining stop owner waits for pending teardown completion instead of reporting success immediately.

Deferred queue replays carry an internal replay flag through `dispatch()`, so replay bypasses the 1.5 s physical-event debounce while still revalidating the current attached device and enabled setting. Explicit `SETTINGS` and `FOREGROUND` requests also bypass that debounce; repeated system `ATTACH` events remain deduplicated. A stale/destroyed handoff owner releases its generation only after confirmed teardown and returns the latest request to the deferred queue. A failed `startActivity()` releases its debounce generation.

**Tests:** `shutdownTimeoutKeepsTheTeardownBarrierUntilTheControllerCloses`, `pendingControllerTeardownBlocksSessionStartsUntilExplicitCompletion`, and `backgroundStopWaitsForAnExistingTeardownBarrier` cover timeout, start gating, and stop completion. `deferredReplayIsNotDiscardedInsideTheDuplicateWindow`, `failedCompletedHandoffReleasesTheQueueAndPreservesItsLatestRequestForRetry`, and explicit-source debounce assertions cover replay/manual retry semantics.

## Phase 01C — Confirmed teardown and retry ownership

The shutdown and `restartCarPlay()` paths now create a process-local teardown token before dropping the Activity's Controller references. A Controller-close timeout reports `TIMED_OUT`, keeps the token visible through `CarPlayBackgroundSession.hasSession()`, retries `awaitClosed()` off the main thread, and does not run Host-start callbacks. The token is released only after the Controller close is confirmed and the old `AndroidMediaSink.close()` call returns. A close exception reports `FAILED`; if the Controller is still open, close is retried. The process-exit path can still terminate the process without opening a replacement Controller.

Shutdown callbacks now receive `CLOSED`, `TIMED_OUT`, or `FAILED`. Callbacks that create a new Host act only on `CLOSED`; timeout/failure notifications leave them pending for the later confirmed close. `maybeStartCarPlay()` and `startCarPlay()` both check the teardown barrier. A background stop request with no remaining stop owner waits for pending teardown completion instead of reporting success immediately.

Deferred queue replays carry an internal replay flag through `dispatch()`, so replay bypasses the 1.5 s physical-event debounce while still revalidating the current attached device and enabled setting. Explicit `SETTINGS` and `FOREGROUND` requests also bypass that debounce; repeated system `ATTACH` events remain deduplicated. A stale/destroyed handoff owner releases its generation only after confirmed teardown and returns the latest request to the deferred queue. A failed `startActivity()` releases its debounce generation.

**Tests:** `shutdownTimeoutKeepsTheTeardownBarrierUntilTheControllerCloses` holds the close waiter across its first timeout and verifies the barrier prevents a start request until the next wait confirms closure. `pendingControllerTeardownBlocksSessionStartsUntilExplicitCompletion` and `backgroundStopWaitsForAnExistingTeardownBarrier` cover the process session gate. `deferredReplayIsNotDiscardedInsideTheDuplicateWindow`, `failedCompletedHandoffReleasesTheQueueAndPreservesItsLatestRequestForRetry`, and explicit-source debounce assertions cover replay/manual retry semantics.

## Phase 08 — Output-drain grace and metric meaning

`VideoPendingOutputState` remains a bounded hint rather than an exact input/output pairing count. When the estimate reaches zero, recent input or output activity keeps output draining in at most 8 ms dequeue slices for a 50 ms grace; after that the existing 100 ms idle queue poll can resume. The 500 ms pending limit, 250 ms frame-age bound, queue capacity and default-off Surface-recovery policy are unchanged.

The PTS-correlated percentile is logged as `receivedToCodecSurfaceReleaseMs`, with unmatched-output and overwritten-input numerator/denominator counts. Its endpoint is after `releaseOutputBuffer(..., true)` returns; it is not the time Android presents the frame on screen. Target visible-frame timing remains a separate device measurement.

**Tests:** `VideoDecodeQueueTest.recentInputAndOutputKeepTheZeroCountDrainGraceShort` verifies that recent output activity prevents the 100 ms idle queue wait after the approximate pending count reaches zero. Existing delayed-output/500 ms bound tests remain. `VideoStatsTest` verifies the corrected `receivedToCodecSurfaceReleaseMs` label and correlation fractions.

## Phases 02–03 — AUDIO-01 transition measurement and safe retirement

PCM format transitions now record `audioOwnerEpoch`, `trackGeneration`, `dspConfigGeneration`, format/encoding, audio thread ID/name, prepare duration, reaper request duration, AudioTrack creation duration, first-write delay, and fallback reason. The event is emitted once on the first write for a transition. Native destroy duration and reaper thread identity are emitted when retirement completes. Diagnostic callbacks are isolated with `runCatching`; no PCM is recorded and no histogram or per-block string work is added.

On format change, the active graph is detached from `AudioRenderer` and retired through its existing `DspLiveUpdateController` and `DspPipelineReaper`. Pending candidates and shutdown graphs use the same reaper. Native graph preparation remains synchronous on format changes so startup Convolver state is installed before the first decoded PCM; this batch moves old graph destruction off that path and does not claim target-device latency improvement.

If native close fails, the owning renderer becomes permanently update-blocked. On the next PCM or format boundary, the audio owner detaches and retires active/fading graphs, uses PCM fallback, and preserves the last known algorithmic latency duration when the sample rate changes. Pending candidates are retired; later setting changes continue to display blocked status, and a released old renderer cannot overwrite a new renderer's status. A new renderer can publish fresh ACTIVE status only after its own graph is prepared. DSP master OFF stays byte-exact legacy PCM16 with zero added latency; MEDIA-only role policy, crossfade, seat delay, clock handling, and DSP algorithms remain unchanged.

**Tests:** `decoderFormatChangeDoesNotWaitForOldNativeCloseOnTheAudioOwner` delays close and verifies format preparation returns while the reaper owns the old graph, and checks timing/thread diagnostic fields. Existing A→B→A stale-candidate, in-flight fade cancellation, publication linearization, AAC/Opus startup Convolver, non-MEDIA, and DSP-OFF PCM16 matrix tests pass.

## Phase 04 — VIDEO-01 MediaCodec and Surface recovery

`VideoDecoderRecoveryStatus` adds typed `RUNNING`, `DETACHING`, `RELEASE_BLOCKED`, `RETRYING_SAFE_RELEASE`, `RECOVERED`, and `SESSION_RESTART_REQUIRED` states with stream generation, decoder owner ID, pending ACK count, exception class, last-frame age, blocked duration, and retry count. Diagnostics contain no codec pointer or media payload.

Release retries run only on the existing video worker, at most twice per codec with a 25 ms delay. A successful retry completes the existing Surface ACK. After persistent failure, the decoder and Surface owner remain retained, `surfaceReleaseSafe` stays false, replacement is denied, and the diagnostic requests an app restart. The eight-generation Surface retirement backpressure and existing MAIN/ALT owner checks are unchanged.

**Tests:** `transientCodecReleaseFailureRetriesOnItsWorkerAndCompletesSurfaceRetirement`, `codecReleaseFailureRetainsItsSurfaceAndBlocksDecoderReplacement`, and `mainCodecReleaseFailureDoesNotBlockAltSurfaceAcknowledgement`, plus existing surface-gate and stale stream-callback suites. Persistent failure does not release a Surface or admit a replacement decoder; an ALT ACK still completes while MAIN remains blocked.

## Phase 05 — DSP-01 failed retirement

The current renderer no longer loses an old pipeline's retirement failure when a format change replaces its live-update controller. The failure is tied to the renderer epoch and retired pipeline/config generation, disables further adoption for that renderer, retires a pending candidate, and keeps `updatesBlocked` status current as settings change. It is not cleared by master OFF/ON or a profile update. A fresh renderer is the recovery boundary; no unsafe handle is marked released or reused.

Both FULL and COMPACT_SPLIT use the existing retirement status and now instruct the user to restart DiPlay before applying changes.

**Tests:** existing `failedNativeCloseIsReportedAndBlocksFurtherAdoption`, new `oldFormatRetirementFailureBlocksUpdatesUntilANewRendererOwnsTheGraph`, and `lateRetirementFailureFromReleasedRendererDoesNotBlockTheNewOwner`.

## Phase 06 — Cross-subsystem ownership review

The updated [OWNERSHIP_MAP.md](OWNERSHIP_MAP.md) records the USB token/start guards, VPN attachment generations, Host `restartGeneration`/`connectionEpoch`/owner epoch, MAIN/ALT stream generation and Surface ACK, and AudioRenderer/AudioTrack/DSP pipeline ownership. Existing NCM, VPN accepted-session, RTSP bounds, Split, touch, and session-generation regression suites were retained and passed. No NCM payload, StartSession count, profile, VPN routing, RTP limit, or benchmark threshold changed.

## Phase 07 — Verification and evidence

Remote baseline run #568 passed changes/EX-08, host native, ASan/UBSan, full unit tests, lint, Debug/optimized builds, JNI/R8, `host-baseline`, Draft scope, and API 35 instrumentation on test head `3e81cd5d`. After F-01–F-06 were added, run [#40679997119](https://github.com/choko510/DiPlay/actions/runs/40679997119) passed those host and API 35 lanes on `daeba474`; artifacts `12811838948` and `12812213115` were uploaded. The Android instrumentation artifact is separate from target TS10/K706/Android 10 USB acceptance. Previous runs #545/#546 and #551 remain historical evidence for their own heads.

Local verification on Windows:

- `:shared:testDebugUnitTest :common:testDebugUnitTest --parallel --stacktrace` — full local suites contain 689 Shared + 241 Common tests, 0 failures/errors/skips. This includes the F-01–F-06 regressions and the teardown barrier, deferred replay/manual retry, and video drain-grace regressions.
- `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug` — passed on the current source. No new lint warning was reported in the changed source. GitHub run #568 also passed the optimized builds. Existing NDK 28.2 missing-`source.properties` and dependency strip warnings remain; the local build completed with the configured NDK 27 path.
- `scripts/run_android_instrumentation.sh` — an earlier local API 36.1 x86_64 run passed 19 Shared Release + 1 Common Debug instrumentation test. Run #40679997119 passed API 35 instrumentation after F-01–F-06. Emulator results do not prove USB chooser or vehicle behavior.
- `scripts/test_dsp_native.sh` — passed with MSYS2 GCC. `scripts/test_dsp_native.sh --sanitize` was attempted but could not link because this host's MSYS2 installation lacks `libasan` and `libubsan`.
- `scripts/check_jni_mapping.py`, `scripts/test_android_ci_classifier.sh`, `scripts/test_android_instrumentation_evidence.sh`, `scripts/test_step4_ex08_delta111.py` (19/19), `scripts/generate_step4_ex08_inventory.py --check` (1,400 unique SHAs), and `scripts/check_public_tree.py` — passed. The Windows checkout uses `core.autocrlf=true`; the byte-frozen EX-08 checks passed after temporary LF normalization of the ledger/manifest/override files, then those files were restored without content changes.

## Remaining gates

- `HW-01`–`HW-10`, `HW-12`, and `HW-13` remain `BLOCKED_DEVICE`; no TS10/K706 or supported iPhone was attached. Specifically, Android 10 USB chooser delivery, permission-refusal retry, same-device re-enumeration, DSP Split draft continuity, already-connected reboot, final-frame timing, and visible-display latency are not physically verified.
- `HW-11` signed update/rollback remains `BLOCKED_ENV`; no release signing credentials or release APK were used.
- Local API 35 instrumentation is `NOT_RUN` because an API 35 emulator image is unavailable. Hosted API 35 instrumentation passed in runs #568 (remote baseline) and #40679997119 (post-review head).
- Independent second review remains `NOT_RUN` per prior user direction.
- Keep G8 / release decision `NO_GO_THIS_RUN`. Do not merge or mark this PR ready for review without the remaining gates.
