# PR #45 Review Fix Test Matrix

**Branch:** `codex/step4-pr-00-test-baseline`

**Base:** `95ba398f95ee104527812cd2b095977d71c51f98`

**Latest source snapshot:** `7aff2f8bc8bff05af3962e6c6887e020ad80be81` (Controller teardown and video drain follow-up)

**Latest test head:** `3e81cd5d6f250ffc0a4e9189bd83bc718bd40f3`

**Latest GitHub Actions:** [run #568](https://github.com/choko510/DiPlay/actions/runs/40675349338) (full host/native/unit/lint/build/JNI/R8 and API 35 instrumentation passed)
**Physical target:** no TS10/K706 or supported iPhone attached; all HW rows remain blocked.

`PASS` means the named local test or gate passed at its stated scope. `NOT_RUN` means the specific injection or combination has no dedicated evidence. Emulator results never promote a device row to `PASS`.

## USB-01

| ID | Input / condition | Expected result | Test / evidence | Status |
|---|---|---|---|---|
| USB-01-T01 | Existing wired Host; attach token verified; VPN and startup prerequisites ready | `onNewIntent` evaluates start exactly once | `CarPlayHostActivityReconnectTest.verifiedUsbAttachReevaluatesStartForAnExistingWiredHost`; Common unit suite | PASS |
| USB-01-T02 | Existing controller present; deliver 20 verified wired attach actions | Existing controller remains; no second start request | `CarPlayHostActivityReconnectTest.twentyVerifiedWiredAttachesKeepTheExistingControllerAndDoNotStartAnother`; Common unit suite | PASS |
| USB-01-T03 | Background controller/sink is owned by another Activity; verified wired attach arrives | Existing controller is adopted; no second start request or stale-owner teardown | `CarPlayHostActivityReconnectTest.verifiedWiredAttachAdoptsTheExistingBackgroundOwner`, plus stale-owner ticket/callback/session-end regressions | PASS |
| USB-01-T04 | Wireless Host; two verified attaches during teardown | One deferred switch, wireless setting cleared after teardown, one new wired Host action | `CarPlayHostActivityReconnectTest.wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown`; no physical wireless-session teardown in this test | PASS |
| USB-01-T05 | External action, consumed token replay, wrong device, absent permission, unavailable device | No internal Host action is authorized | `CarPlayHostActionsTest.externalHostActionStringsAreIgnoredAndInternalTokensAreSingleUse`, `usbAttachTrampolineRequiresTheSameCurrentlyAttachedCh341Device`, `onlyUsbTrampolineIsExported` | PASS |
| USB-01-T06 | Verified wired attach before VPN readiness; readiness then becomes available | No early start; existing readiness path can start later | `CarPlayHostActivityReconnectTest.verifiedUsbAttachWaitsForVpnReadinessBeforeStarting`; display-size-not-ready is guarded in production but not isolated in this test | PASS |
| USB-01-T07 | Two verified attaches while a non-process shutdown is running | One completion resumes after controller/sink teardown; no stale old-owner action | `CarPlayHostActivityReconnectTest.wiredUsbAttachDuringShutdownQueuesOneResumeAfterTeardown` and `wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown` | PASS |
| USB-01-HW | USB detach/reinsert, stopped-Host attach, tethering ON/OFF | Record Stage3 and session identity on TS10/K706 + iPhone | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-01–HW-03 | BLOCKED_DEVICE |

## USB auto-launch review findings

| ID | Scenario | Local evidence | Status / limit |
|---|---|---|---|
| P1-01 / T01 | Background wireless Controller exists when an iPhone attach requests wired CarPlay | Transport-aware adoption policy; `backgroundControllerIsReusableOnlyWhenItsTransportMatchesTheRequestedTransport`; shutdown/handoff regression | PASS_LOCAL; background wireless-to-wired behavior still needs TS10/K706 + iPhone |
| P1-02 / T02–T03 | USB re-enumerates while Controller shutdown/handoff is active; old callback completes late | Deferred/handoff queues keep the latest identity; handoff generations and owner epochs fence stale completion; shutdown resumes once after teardown | PASS_LOCAL; physical re-enumeration and ownership soak remain untested |
| P1-03 / P2-06 / T07 | USB identity changes or becomes stale before Host/controller start | Current-device equality is checked at dispatch and Host startup; stale/current identities are covered; new observed attach replaces queued identity | PARTIAL; if no new attach event arrives there is no safe proof that a new identity belongs to the same iPhone, so no timed cross-identity rediscovery is attempted |
| P2-01 / T04 | Explicit open action arrives while wired Controller already runs | `duplicateSystemAttachDoesNotRaiseTheHostButAnExplicitOpenDoes`; existing Controller is retained | PASS_LOCAL; frontmost behavior on the target launcher remains untested |
| P2-02 | Another app sends an explicit launch to the exported USB entry | Apple entry has `MANAGE_USB` as caller permission and rejects missing attach action/device; manifest assertions cover the restriction | PARTIAL; Android 10 OEM USB chooser and caller-permission behavior need target-device validation |
| P2-03 | Device implements USB Host but omits `FEATURE_USB_HOST` | Switch stays enabled; feature declaration only changes the status message | PASS_BY_CODE_INSPECTION; no custom-ROM target attached |
| P2-04 / T05 | Activity dispatch succeeds but Host rejects/permission is refused; reconnect should retry | Failed dispatch/Host rejection releases its generation; current USB identity is revalidated; debounce expires after 1.5 s | PARTIAL; Android permission-refusal and retry cycle not exercised on hardware; a successful `startActivity()` is not Controller acceptance |
| P2-05 | A session is transitioning and the USB event arrives | Deferred request queue retains the latest identity and resumes through the normal dispatcher | PASS_LOCAL; transition behavior on device remains unverified |
| T06 | Setting is switched OFF while USB is attached | Policy and Host checks require the saved enabled preference and enabled component | PASS_LOCAL; active device stop behavior remains a hardware acceptance item |
| T08 | 20 repeated attach events while a wired Controller is active; 20 identity replacements during handoff | `twentyVerifiedWiredAttachesKeepTheExistingControllerAndDoNotStartAnother`; `handoffQueueUsesTheLatestIdentityAndPreservesTheSplitSnapshot` | PASS_LOCAL for event modeling; not a 20-cycle physical USB soak |
| T09 | DSP Split wireless-to-wired handoff | Handoff queue preserves the Host layout snapshot; DSP profile draft is held by existing runtime/persistence | PARTIAL; no end-to-end assertion of live DSP editor draft data across the actual transport handoff; verify on target |
| T10 | iPhone is already connected during Android navigation reboot | Boot path offers a user-initiated notification fallback | NOT_RUN_DEVICE; no target reboot test |

The test fixture timeout that failed in run #541 was changed from a scheduler-sensitive 2 ms timeout to an explicit delayed timeout. Run #545 passed after that correction. Run #546 passed after the 20-event regressions were added; host-baseline and Android instrumentation remain skipped for this Draft update.

## Follow-up review findings — 2026-10-28

| ID | Scenario | Local evidence | Status / limit |
|---|---|---|---|
| P1-NEW-01 | Controller `awaitClosed()` times out during shutdown/full-stack restart | `shutdownTimeoutKeepsTheTeardownBarrierUntilTheControllerCloses`; `CarPlayBackgroundSession` remains session-active and prevents another start until a later close confirmation | PASS_LOCAL; synthetic wait verifies callback/start gate. Real USB/VPN close timing remains device-gated |
| P1-NEW-02 | Deferred USB request is replayed inside the 1.5 s attach duplicate window | `deferredReplayIsNotDiscardedInsideTheDuplicateWindow`; queue replay carries an internal bypass flag and still revalidates the current device | PASS_LOCAL; physical attach/permission flow remains `BLOCKED_DEVICE` |
| P2-NEW-01 | Handoff owner becomes stale/destroyed after teardown or launch dispatch fails | `failedCompletedHandoffReleasesTheQueueAndPreservesItsLatestRequestForRetry`; generation/owner mismatch defers the latest request after close confirmation; launch failure releases its debounce generation | PASS_LOCAL; abnormal OEM Activity lifecycle remains unverified |
| P2-NEW-02 | User explicitly retries from settings/foreground after an auto-launch attempt | `duplicateSystemAttachDoesNotRaiseTheHostButAnExplicitOpenDoes` now checks the explicit source bypasses attach debounce | PASS_LOCAL; device UI and Android permission refusal remain untested |
| P2-NEW-03 | Pending output estimate reaches zero before a delayed final codec output | `VideoDecodeQueueTest.recentInputAndOutputKeepTheZeroCountDrainGraceShort`; recent input/output activity uses up to 8 ms output dequeues for a 50 ms grace | PASS_LOCAL; OEM MediaCodec final-frame timing remains `BLOCKED_DEVICE` |
| P2-NEW-04 | Frame latency metric could be mistaken for visible display presentation | `VideoStatsTest` expects `receiveToSurfaceSubmitMs`; timestamp ends after `releaseOutputBuffer(..., true)` returns | PASS_LOCAL for naming; actual display-presentation latency remains unmeasured |
| P2-USB-HW | Android 10 TS10 USB resolver/chooser and `MANAGE_USB` delivery | No target attached; source manifest restriction unchanged | BLOCKED_DEVICE; verify using USB chooser plus `adb shell dumpsys usb` on the target |

Run [#568](https://github.com/choko510/DiPlay/actions/runs/40675349338) passed after the updated shutdown, replay, drain-grace and metric-name tests were added. Its API 35 instrumentation remains an emulator result; USB chooser delivery and actual display-presentation timing need target hardware.

## AUDIO-01A / AUDIO-01B

| ID | Input / condition | Expected result | Test / evidence | Status |
|---|---|---|---|---|
| AUDIO-01-T01 | Old native graph close blocks while PCM format changes on `carplay-audio` | Format setup returns after ownership transfer; close continues on the reaper | `DspRendererProductionPathTest.decoderFormatChangeDoesNotWaitForOldNativeCloseOnTheAudioOwner` | PASS |
| AUDIO-01-T02 | Format A→B→A while a prepared candidate is publishing | Only current format/generation is adoptable; stale candidate retires once | `DspRendererProductionPathTest.staleFormatCandidateIsClosedWhenRendererFormatReturnsToItsPreviousValue` and `preparedPublicationIsLinearizedWithAFormatChangeAfterTheOwnerCheck` | PASS |
| AUDIO-01-T03 | Live fade then decoder format change | Fade is abandoned and old-format graphs retire without later processing | `DspRendererProductionPathTest.decoderFormatChangeCancelsAnInFlightFadeAndRetiresItsOldFormatGraph` | PASS |
| AUDIO-01-T04 | DSP OFF at 44.1/48 kHz, mono/stereo | PCM16 output remains byte-exact with zero added latency | `DspRendererProductionPathTest.dspOffLpcmProductionPathWritesTheOriginalPcm16Samples` | PASS |
| AUDIO-01-T05 | MEDIA, navigation, phone, assistant, and unknown stream roles | Full DSP stays MEDIA-only regardless of Android routing | `AudioStreamClassificationTest.knownAudioTypesUseSemanticRolesRegardlessOfRoutingMode`, `DspStreamPolicyTest.processingRequiresBothTheGlobalEnableFlagAndMediaRole`, `DspRendererProductionPathTest.nonMediaRendererDoesNotReportDspApplicationStatus` | PASS |
| AUDIO-01-T06 | Native prepare/close fault injection and owner shutdown | Failure does not publish a false active generation; adoption remains blocked after failed retirement | `DspRendererProductionPathTest.failedLiveGraphPreparationIsReportedWithoutClaimingTheNewGenerationIsActive`, `DspLiveUpdateControllerTest.failedNativeCloseIsReportedAndBlocksFurtherAdoption`, plus the new owner-failure cases below | PASS |
| AUDIO-01-T07 | Startup Convolver, Seat, Advanced M/S, Harmonic, headroom, phase, A/V clock | Existing graph and latency contracts remain unchanged | Full `DspRendererProductionPathTest`; `:shared:testDebugUnitTest` | PASS |
| AUDIO-01-T08 | Same target hardware before/after p50/p95/p99, underruns, and first-write timing | Compare matching ABI, variant, input, and load | Transition diagnostics now expose timing; no controlled TS10/K706 comparison was run | BLOCKED_DEVICE |

## VIDEO-01

| ID | Input / condition | Expected result | Test / evidence | Status |
|---|---|---|---|---|
| VIDEO-01-T01 | Codec release succeeds and the old Surface has ACKed detach | Old surface retirement completes once; next decoder is allowed | `AndroidMediaSinkStateTest.stoppedVideoDecoderReleasesSurfaceConfigAndQueuedFrameReferences`, `SurfaceRetirementGateTest.textureReleaseWaitsForEverySurfaceOwnerAndReturnsTheBatchOnlyOnce` | PASS |
| VIDEO-01-T02 | First release throws; same decoder worker retries successfully | ACK completes after retry; state reports `RECOVERED` | `AndroidMediaSinkStateTest.transientCodecReleaseFailureRetriesOnItsWorkerAndCompletesSurfaceRetirement` | PASS |
| VIDEO-01-T03 | All bounded release attempts throw | Old decoder/Surface stay retained; replacement is refused; `SESSION_RESTART_REQUIRED` is reported | `AndroidMediaSinkStateTest.codecReleaseFailureRetainsItsSurfaceAndBlocksDecoderReplacement` | PASS |
| VIDEO-01-T04 | MAIN release fails persistently while ALT release succeeds; detach ACKs race | ALT ACK completes; MAIN ACK stays pending and its Surface owner remains retained | `AndroidMediaSinkStateTest.mainCodecReleaseFailureDoesNotBlockAltSurfaceAcknowledgement` | PASS |
| VIDEO-01-T05 | Deferred SurfaceTexture and same-texture replacement | Destroyed/stale texture is not reattached; release waits for every owner ACK | `SurfaceRetirementGateTest.sameTextureSurfaceReplacementWaitsForAckWithoutReleasingTheLiveTexture`, `duplicateDestructionDoesNotScheduleAnotherRetirement` | PASS |
| VIDEO-01-T06 | Old screen/session callback after replacement | Callback cannot target the new stream/session | `CarPlayMediaEngineTest.replacedScreenStreamCannotDeliverStaleCallbacksOrCloseTheSession`, `oldSessionScreenCallbacksAndTeardownCannotReachTheCurrentSessionSink` | PASS |
| VIDEO-01-T07 | Recovery queue saturation and Surface generation churn | Recovery queue and retained generation count stay bounded | `AndroidMediaSinkStateTest.videoRecoveryQueueHasAFixedCapacityWhenWorkersAreBlocked`, `SurfaceRetirementGateTest.surfaceGenerationChurnBoundsRetirementsAndKeepsOnlyTheLatestDeferredGeneration`; long memory/FD soak is tracked in HW-10 | PASS |
| VIDEO-01-HW | OEM codec release behavior and black-screen recovery | Record codec identity class, ACKs, and first recovered frame on TS10/K706 | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-06/HW-07 | BLOCKED_DEVICE |

## DSP-01 retirement failure

| ID | Input / condition | Expected result | Test / evidence | Status |
|---|---|---|---|---|
| DSP-01-T01 | Native close fails for a retired graph | Failed controller never reopens its adoption gate | `DspLiveUpdateControllerTest.failedNativeCloseIsReportedAndBlocksFurtherAdoption` | PASS |
| DSP-01-T02 | Old renderer's close fails after a new renderer is created | Old failure cannot block or overwrite new renderer status | `DspRendererProductionPathTest.lateRetirementFailureFromReleasedRendererDoesNotBlockTheNewOwner` | PASS |
| DSP-01-T03 | Failed retirement while newer settings are requested | Existing graph remains active; updates stay blocked until a new renderer is active | `DspRendererProductionPathTest.oldFormatRetirementFailureBlocksUpdatesUntilANewRendererOwnsTheGraph` | PASS |
| DSP-01-T04 | Close failure races profile changes and track replacement | No double-close, unsafe handle reuse, deadlock, or false ACTIVE update | Old-format close/config race is covered; profile A→B, master OFF/ON, and AudioTrack recreation in every ordering are not separately injected | NOT_RUN |
| DSP-01-T05 | Draft persistence and split/non-split editor synchronization | Existing draft/token semantics remain unchanged | Full Common unit suite (`DspEditorSessionTest`, profile runtime/repository tests) | PASS |
| DSP-01-T06 | DSP retirement failure with non-MEDIA audio roles | Non-MEDIA audio remains outside full DSP | `AudioStreamClassificationTest` and `DspStreamPolicyTest`; no physical Siri/call test | PASS |

## Phase 06 cross-subsystem and Phase 07 gates

| Gate | Evidence | Status |
|---|---|---|
| Cross-subsystem NCM/IPv6/VPN/RTSP regression | `NcmUsbBridgeFixtureTest`, `Ntb16CodecTest`, `Ipv6NcmBridgeWiredPathDiagnosticsTest`, `CarPlayVpnServiceAcceptGenerationTest`, `RtspMessageParserTest`; Shared full unit suite | PASS |
| Full/Split viewport, touch, session and Surface identity | `CarPlayHostActivityLocalScaleTest.fullAndSplitViewportCyclesKeepTheRemoteControllerSessionAndSurface`, touch-cancel tests, `CarPlayMediaEngineTest` stale-session tests | PASS (Robolectric) |
| 30–60 minute memory/FD/native allocation soak | Target vehicle and iPhone under sustained CarPlay/DSP load | BLOCKED_DEVICE |
| Android API 26 runtime | Current app minimum SDK is 28; no API 26 runtime lane exists in this checkout | NOT_RUN |
| Full shared/common JVM unit suites | `:shared:testDebugUnitTest :common:testDebugUnitTest --parallel`: 683 Shared + 241 Common, 0 failed/errors/skipped at current test head `3e81cd5d` | PASS |
| Mobile/automotive lint and build | `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` | PASS |
| Native DSP C tests | `scripts/test_dsp_native.sh` using MSYS2 GCC | PASS |
| ASan/UBSan | `scripts/test_dsp_native.sh --sanitize`; local linker reports missing `-lasan` and `-lubsan` | BLOCKED_ENV |
| JNI/R8 mapping | `python -B scripts/check_jni_mapping.py`; mobile and automotive Benchmark mappings | PASS |
| API 36.1 emulator instrumentation | `scripts/run_android_instrumentation.sh`: Shared Release 19/19 + Common Debug 1/1; instrumentation evidence validator passed | PASS (emulator) |
| API 35 emulator instrumentation | Current GitHub run #568: API 35 instrumentation passed; artifact 12811100979 was uploaded. Earlier run #527 also passed Shared Release 19/19 + Common Debug 1/1 (artifact 12747252715). | PASS_CI |
| GitHub host/native/unit/lint/build/JNI/R8 | Current run #568 passed `check`, host native, ASan/UBSan, full units, lint, Debug/optimized builds, and R8/JNI verification. Check artifact 12810711700 was uploaded. Earlier run #527 results are historical to source head `c8a1ab0`. | PASS_CI |
| CI classifier/evidence validators | `scripts/test_android_ci_classifier.sh`; `scripts/test_android_instrumentation_evidence.sh` via MSYS2 Bash | PASS |
| EX-08 delta-111 / generated ledger | `scripts/test_step4_ex08_delta111.py` 19/19; `scripts/generate_step4_ex08_inventory.py --check`; 1,400 unique SHAs | PASS after temporary LF normalization for Windows `core.autocrlf=true`; tracked source data restored afterward |
| Public source tree | `python -B scripts/check_public_tree.py` | PASS |
| Concurrent branch update `d140f6e` | Opt-in iPhone USB auto-launch policy/settings/coordinator/trampoline and internal-intent fallback | `IphoneUsbAutoLaunchPolicyTest`, `IphoneUsbAutoLaunchSettingsTest`, `IphoneUsbAutoLaunchCoordinatorTest`, `IphoneUsbAutoAttachActivityTest`, `CarPlayHostActionsTest`; Common suite 241/241 on current tree | PASS_LOCAL |
| GitHub Actions run #527 | [Android checks](https://github.com/choko510/DiPlay/actions/runs/40531665594) on product-source head `c8a1ab0`; `changes`, `check`, `host-baseline`, `android-instrumentation`, and Draft scope succeeded | PASS |
| Test-only follow-up run #533 | USB background-owner adoption and MAIN/ALT release-failure isolation tests at `344181eb`; Draft unit/quick-baseline passed, with 32 tests and 0 failures/errors/skips in artifact 12789926418. Android instrumentation and host-baseline were skipped because product source did not change. | PASS |
| Current review follow-up run #568 | Head `3e81cd5d`; `changes`/EX-08, host native, ASan/UBSan, full Gradle unit/lint/build, JNI/R8, and API 35 instrumentation passed. Check/instrumentation artifacts 12810711700 and 12811100979 were uploaded. | PASS_CI |
| TS10/K706 + supported iPhone acceptance | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-01–HW-10/HW-12/HW-13 | BLOCKED_DEVICE |
| Release-signed update and installed rollback | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-11 | BLOCKED_ENV |
| Independent review | Kept `NOT_RUN` per prior user direction | NOT_RUN |
