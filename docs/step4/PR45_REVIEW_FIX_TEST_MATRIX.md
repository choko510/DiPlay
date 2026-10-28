# PR #45 Review Fix Test Matrix

**Branch:** `codex/step4-pr-00-test-baseline`

**Base:** `95ba398f95ee104527812cd2b095977d71c51f98`

**Source/test head:** `c8a1ab0bd9126b7bc6f11e28e2fa6fa2b8cea78c`

**GitHub Actions:** [run #527](https://github.com/choko510/DiPlay/actions/runs/40531665594)
**Physical target:** no TS10/K706 or supported iPhone attached; all HW rows remain blocked.

`PASS` means the named local test or gate passed at its stated scope. `NOT_RUN` means the specific injection or combination has no dedicated evidence. Emulator results never promote a device row to `PASS`.

## USB-01

| ID | Input / condition | Expected result | Test / evidence | Status |
|---|---|---|---|---|
| USB-01-T01 | Existing wired Host; attach token verified; VPN and startup prerequisites ready | `onNewIntent` evaluates start exactly once | `CarPlayHostActivityReconnectTest.verifiedUsbAttachReevaluatesStartForAnExistingWiredHost`; Common unit suite | PASS |
| USB-01-T02 | Existing controller present; deliver two verified wired attach actions | Existing controller remains; no second start request | `CarPlayHostActivityReconnectTest.verifiedWiredAttachKeepsAnExistingControllerAndDoesNotStartAnother`; Common unit suite | PASS |
| USB-01-T03 | Different Activity/session owner and stale callback interleavings | Current owner remains authoritative; stale callbacks cannot stop it | `CarPlayHostActivityReconnectTest.staleOwnerEpochTicketIsRejectedByTheActivityDelayRunnable`, `queuedOldOwnerTransportCallbackCannotChangeTheNewHostStage`, `staleOldSessionEndCannotClearOrReconnectTheNewActiveSession`; attach-specific owner transfer is not separately injected | NOT_RUN |
| USB-01-T04 | Wireless Host; two verified attaches during teardown | One deferred switch, wireless setting cleared after teardown, one new wired Host action | `CarPlayHostActivityReconnectTest.wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown`; no physical wireless-session teardown in this test | PASS |
| USB-01-T05 | External action, consumed token replay, wrong device, absent permission, unavailable device | No internal Host action is authorized | `CarPlayHostActionsTest.externalHostActionStringsAreIgnoredAndInternalTokensAreSingleUse`, `usbAttachTrampolineRequiresTheSameCurrentlyAttachedCh341Device`, `onlyUsbTrampolineIsExported` | PASS |
| USB-01-T06 | Verified wired attach before VPN readiness; readiness then becomes available | No early start; existing readiness path can start later | `CarPlayHostActivityReconnectTest.verifiedUsbAttachWaitsForVpnReadinessBeforeStarting`; display-size-not-ready is guarded in production but not isolated in this test | PASS |
| USB-01-T07 | Two verified attaches while a non-process shutdown is running | One completion resumes after controller/sink teardown; no stale old-owner action | `CarPlayHostActivityReconnectTest.wiredUsbAttachDuringShutdownQueuesOneResumeAfterTeardown` and `wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown` | PASS |
| USB-01-HW | USB detach/reinsert, stopped-Host attach, tethering ON/OFF | Record Stage3 and session identity on TS10/K706 + iPhone | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-01–HW-03 | BLOCKED_DEVICE |

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
| VIDEO-01-T04 | MAIN/ALT release failures with reordered ACKs | One stream's failure does not release or stop the other | MAIN/ALT recovery request and concurrent close tests pass; simultaneous release-failure/ACK-order injection was not added | NOT_RUN |
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
| Full shared/common JVM unit suites | `:shared:testDebugUnitTest :common:testDebugUnitTest --parallel`: 663 shared + 230 common, 0 failed/errors/skipped | PASS |
| Mobile/automotive lint and build | `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` | PASS |
| Native DSP C tests | `scripts/test_dsp_native.sh` using MSYS2 GCC | PASS |
| ASan/UBSan | `scripts/test_dsp_native.sh --sanitize`; local linker reports missing `-lasan` and `-lubsan` | BLOCKED_ENV |
| JNI/R8 mapping | `python -B scripts/check_jni_mapping.py`; mobile and automotive Benchmark mappings | PASS |
| API 36.1 emulator instrumentation | `scripts/run_android_instrumentation.sh`: Shared Release 19/19 + Common Debug 1/1; instrumentation evidence validator passed | PASS (emulator) |
| API 35 emulator instrumentation | GitHub run #527: Shared Release 19/19 + Common Debug 1/1; the uploaded artifact has 20 tests, 0 failures/errors/skips ([artifact 12747252715](https://github.com/choko510/DiPlay/actions/runs/40531665594/artifacts/12747252715)) | PASS |
| GitHub host/native/unit/lint/build/JNI/R8 | Run #527 `check` and `host-baseline` passed; host native, ASan/UBSan, full units, lint, Debug/Benchmark builds, and R8 passed. The uploaded check artifact has 893 tests, 0 failures/errors/skips ([artifact 12747761188](https://github.com/choko510/DiPlay/actions/runs/40531665594/artifacts/12747761188)). `quick-baseline` was skipped because full host-baseline ran; nested draft-unit/JNI steps were skipped because the full check path covered them. | PASS |
| CI classifier/evidence validators | `scripts/test_android_ci_classifier.sh`; `scripts/test_android_instrumentation_evidence.sh` via MSYS2 Bash | PASS |
| EX-08 delta-111 / generated ledger | `scripts/test_step4_ex08_delta111.py` 19/19; `scripts/generate_step4_ex08_inventory.py --check`; 1,400 unique SHAs | PASS after temporary LF normalization for Windows `core.autocrlf=true`; tracked source data restored afterward |
| Public source tree | `python -B scripts/check_public_tree.py` | PASS |
| Concurrent branch update `d140f6e` | Opt-in iPhone USB auto-launch policy/settings/coordinator/trampoline and internal-intent fallback | `IphoneUsbAutoLaunchPolicyTest`, `IphoneUsbAutoLaunchSettingsTest`, `IphoneUsbAutoLaunchCoordinatorTest`, `IphoneUsbAutoAttachActivityTest`, `CarPlayHostActionsTest`; Common 230/230 | PASS |
| GitHub Actions run #527 | [Android checks](https://github.com/choko510/DiPlay/actions/runs/40531665594) on source/test head `c8a1ab0`; `changes`, `check`, `host-baseline`, `android-instrumentation`, and Draft scope succeeded | PASS |
| TS10/K706 + supported iPhone acceptance | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-01–HW-10/HW-12/HW-13 | BLOCKED_DEVICE |
| Release-signed update and installed rollback | [B10_DEVICE_ACCEPTANCE.md](B10_DEVICE_ACCEPTANCE.md), HW-11 | BLOCKED_ENV |
| Independent review | Kept `NOT_RUN` per prior user direction | NOT_RUN |
