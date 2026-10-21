# STEP4 Test Matrix

Statuses distinguish a passing existing test from a fixture that only prepares an input and from physical-device evidence. A fixture-construction test is not proof of USB, TUN, RTSP, audio, or display behavior on a phone/head unit.

## PR-00 baseline fixtures

| Evidence ID | Scenario | Main test/evidence | Status at PR-00 |
|---|---|---|---|
| U-01 | NTB16 single datagram, sequence, alignment, maximum size | `Ntb16CodecTest` | `PASS_LOCAL` |
| U-02 | Split NTB reads and two coalesced NTBs through `NcmUsbBridge.recv()` | `NcmUsbBridgeFixtureTest.scriptedUsbRequestReadsSplitAndCoalescedNtb16Blocks` | `PASS_ROBOLECTRIC` |
| U-03 | Exact 512-byte block with optional zero pad | `NcmUsbBridgeFixtureTest.paddedExactPacketBoundaryBlockIsDelivered` | `PASS_ROBOLECTRIC` |
| U-04 | Exact 512-byte block without pad | PR-00 head's `NcmUsbBridgeFixtureTest.missingExactPacketBoundaryPadIsCharacterizedForPr01` | `KNOWN_DEFECT_REPRODUCED` at PR-00; PR-01 flips the characterization to delivery |
| U-05 | Persistent request queue true/false, timeout, null and unexpected completion | `PersistentUsbReadRequestStateTest`, `NcmUsbBridgeFixtureTest` | `PASS_PURE_JVM` / `PASS_ROBOLECTRIC` |
| U-06 | Existing 32 KiB buffer on queue failure | `NcmUsbBridgeFixtureTest.fakeCapturesTheExistingThirtyTwoKiBReadQueueContract` | `PASS_ROBOLECTRIC`; no fallback policy is adopted here |
| U-07 | NCM send `NotReady`, full `Sent`, and partial failure remain distinct | `NcmUsbBridgeFixtureTest.fakeBulkOutDistinguishesNotReadySentAndPartialFailure` | `PASS_ROBOLECTRIC` |
| U-08 | Truncated NTH, invalid NDP, NTB32 candidate, >1 MiB garbage | `Step4TestFixturesTest`, existing `Ntb16CodecTest` | `FIXTURE_AND_CODEC_ONLY`; stream resynchronization is PR-01 work |
| U-09 | IPv4 SYN, IPv6 SYN/SYN-ACK, TUN no-reply, accept-only, RTSP-active-only, MAIN/ALT-only timelines | `Step4TestFixtures` | `FIXTURE_ONLY`; EX-01 adds production-path observations |
| U-10 | Recovered session followed by a second loss | `ReconnectAfterLossGateTest`, `CarPlayHostActivityReconnectTest` | `PASS_LOCAL` policy and Activity timer regression; full lifecycle/owner-handoff simulation remains `NOT_RUN` |
| U-11 | Explicitly denied microphone input and default/compatibility/telephony/speech-recognition voice requests | `MicrophoneRequestPolicyTest`; `CarPlayMediaEngineTest.explicitInputFalseKeepsAudioOutputButDoesNotStartMicrophone`; `CarPlayMediaEngineTest.defaultAndCompatibilityVoiceInputsReachTheExistingUplink`; `CarPlayMediaEngineTest.microphoneInputRespectsPermissionAndAdvertisedMonoFormat` | `PARTIAL`: request policy `PASS_LOCAL` and media/uplink gating `PASS_ROBOLECTRIC`; physical Siri/call/voice-message and real `AudioRecord` behavior remain `NOT_RUN` |
| U-12 | 55% local Split geometry, PCM16 samples, encoded-video timing/crop | `Step4TestFixturesTest` | `FIXTURE_ONLY`; PR-06/07 add production-path checks |
| U-13 | Legacy DSP OFF, profile migration/future-schema protection, native DSP buffers | Existing DSP unit tests | `PASS_LOCAL` on base SHA; physical audio/A/V remains `NOT_RUN` |

## EX-01 wired path observability

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| EX01-01 | Ethernet II, VLAN/QinQ, IPv4 IHL/length/fragments, IPv6 extensions/fragments/jumbograms, TCP flags/ports/bounds | `WiredTcpPacketClassifierTest` | `PASS_LOCAL`; 100,000 deterministic arbitrary buffers |
| EX01-02 | Concurrent counters, first timestamps, saturation, attach-generation fencing, idempotent seal and late-event rejection | `WiredPathDiagnosticsTest` | `PASS_LOCAL` |
| EX01-03 | NCM send results are unchanged with diagnostics OFF/ON; TUN write failure, peer-MAC drop and SYN-ACK OUT NotReady are counted | `NcmUsbBridgeFixtureTest`, `Ipv6NcmBridgeWiredPathDiagnosticsTest` | `PASS_ROBOLECTRIC`; NotReady is one send attempt |
| EX01-04 | RTSP screen stream setup distinguishes MAIN/ALT; only MAIN first frame is counted | `WiredPathAirPlayDiagnosticsTest` | `PASS_ROBOLECTRIC` |
| EX01-05 | Separate fixed `WIRED_PATH_V1` line is persisted without changing `ConnectionTraceEvent` records | `ConnectionTraceStoreTest.wiredPathReportsUseTheirOwnFixedLineFormat` | `PASS_ROBOLECTRIC` |
| EX01-06 | Same-attempt USB IN → Ethernet → IPv6 SYN → TUN → SYN-ACK → bulk OUT → listener accept → RECORD → MAIN on TS10/K706/iPhone | Physical device run | `NOT_RUN`; no target hardware is connected |
| EX01-07 | Diagnostics OFF/ON CPU delta `<1%` and added memory `≤64 KiB` on target hardware | Physical performance A/B | `NOT_RUN`; emulator/host tests do not establish target cost |

## PR-01 NCM boundary and damaged-block recovery

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| PR01-01 | Exact endpoint-packet boundary with optional pad present/absent, next NTH16 preserved, lengths ±1/±2, one-byte completions, and a 64-byte endpoint | `NcmUsbBridgeFixtureTest` | `PASS_ROBOLECTRIC`; corrected the PR-00 characterized no-pad stall |
| PR01-02 | Corrupt NDP offset/signature, false NTH16, unknown NTH32 signature, invalid header length, multiple datagrams, and resync to the next valid NTB | `NcmUsbBridgeFixtureTest` | `PASS_ROBOLECTRIC` |
| PR01-03 | More than 1 MiB of garbage, existing frame-queue limit, close racing a queued read, and async/sync Bulk IN | `NcmUsbBridgeFixtureTest` | `PASS_ROBOLECTRIC`; resync stops at its 1 MiB cap |
| PR01-04 | Target-endpoint packet boundary behavior on TS10/K706/iPhone | Physical USB run | `NOT_RUN`; no target hardware is connected |

## EX-02 host retry fencing

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| EX02-01 | Failed → pending → recovered session cancels the old ticket; a later second loss gets a fresh ticket | `ReconnectAfterLossGateTest` | `PASS_LOCAL` policy test |
| EX02-02 | Stale restart/connection/owner epochs, live session, shutdown, menu plus pending resize, handshake reset, controller-owned wired recovery, and replacement-timer deadlines cannot trigger a stale host retry | `ReconnectAfterLossGateTest`; `CarPlayHostActivityReconnectTest.staleOwnerEpochTicketIsRejectedByTheActivityDelayRunnable`; `CarPlayHostActivityReconnectTest.replacementLossTicketDoesNotRunAtTheFirstLossDeadline` | `PARTIAL`: policy matrix and delayed Activity callback/reticket deadline tests `PASS_LOCAL`; full Activity lifecycle/owner-handoff simulation `NOT_RUN` |

## EX-03 watchdog observation and conditional-stage dispositions

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| EX03-01 | Existing 8 s slow snapshot / 15 s hard timeout, stale token rejection, MAIN/ALT setup separation, and MAIN first-frame timing | `WiredStartupWatchdogTest`, `WiredPathAirPlayDiagnosticsTest`, `CarPlayController` path audit | `PASS_LOCAL` observation coverage; no timeout or completion behavior changed |
| EX03-02 | Select different control/screen deadlines or a MAIN-only completion condition from TS10/iPhone timing and UX evidence | Physical device A/B | `NOT_RUN`; preserve 8 s / 15 s and current completion policy |
| EX04-01 | Recheck the same USB node 10 s after a mode-change request, with permission and generation fencing | Physical USB trace | `NO_GO_THIS_RUN`; same-node mode migration without detach/attach has not been observed |
| PR02-01 | API 28+ queue-false size fallback; keep an already-queued timeout on the same request | Queue-failure reproduction on a supported device | `NO_GO_THIS_RUN`; API 26/27 is `NOT_APPLICABLE` at minSdk 28, and no API 28+ device-present queue-false failure is evidenced |
| PR03-01 / EX05-01 | Compare first NCM OUT readiness and consider one conditional StartSession retry only for the proven delayed-OUT/SYN-ACK-loss chain | Same-attempt TS10/iPhone wired trace | `NO_GO_THIS_RUN`; required causal timing evidence is unavailable; current OUT/retry behavior is unchanged |
| EX06-01 | Compare `AUTO` and `NO_STATUS_POLLING` for status usefulness, GC, packet loss, and RTSP timing | Physical device A/B | `NOT_RUN`; retain current polling behavior |
| EX07-01 | Reproduce IPv4-only traffic, IPv6 link-local scope failure, or stale accept generation before changing bridge/listener behavior | Physical interface evidence and targeted stress test | `NO_GO_THIS_RUN`; no trigger condition is evidenced |

## EX-08 upstream commit decision ledger

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| EX08-01 | Enumerate all upstream-only commit SHAs from the frozen base plus 57-commit delta, with source URLs, patch digests, changed paths, disposition, test, STEP4 mapping, and rollback field | `scripts/generate_step4_ex08_inventory.py --check`, `docs/step4/EX08_COMMIT_LEDGER.csv` | `PASS_LOCAL` inventory: 1,289 unique SHAs (1,232 + 57); current dispositions are 7 `ADAPT`, 24 `FORK_SUPERIOR`, 188 `SKIP_OEM`, 690 `REJECT_UNSAFE`, and 380 `NEED_EVIDENCE` |
| EX08-02 | Assign a current per-commit decision or an explicit evidence-pending state for each row | Pinned audit catalog, patch archives, fork source, review overrides, generated ledger | `PARTIAL`: all 1,289 rows have nonempty current review state and disposition; 858 base rows have final dispositions and 374 are explicitly evidence-pending; 51 delta rows have final dispositions and 6 remain `NEED_EVIDENCE`. The original base `source_review_state` remains heuristic provenance; 380 pending rows are not completed semantic evidence or parity proof |
| EX08-03 | Manual hotspot credentials are required only when the active transport is wireless | `ManualHotspotConfigTest.wiredTransportIgnoresManualHotspotCredentials`, `ManualHotspotConfigTest.wirelessTransportStillRequiresManualHotspotSsid` | `PASS_LOCAL`; `PASS_GITHUB` host unit-test suite in run #168; identical source patch recorded once per upstream SHA |
| EX08-04 | A USBMUX request remains owned across a timed wait and its later completion | `PersistentUsbReadRequestStateTest.timeoutKeepsSameRequestQueuedAndDoesNotRequestCancellation`, `Iap2UsbSession.read` | `FORK_SUPERIOR`: state test passed in run #168 and source review confirms the same request/buffer is reused; target timing report remains `NOT_RUN` |

## PR-04 microphone request classification and routing

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| PR04-01 | Explicit `input=false`; MainAudio-only voice types; default/compatibility/telephony/speechRecognition; integer destination port bounds | `MicrophoneRequestPolicyTest` | `PASS_LOCAL` |
| PR04-02 | Denied input leaves downlink audio active; permitted default/compatibility requests reach the uplink; permission-disabled and non-mono input do not start capture | `CarPlayMediaEngineTest` | `PASS_ROBOLECTRIC` |
| PR04-03 | Repeated request for the same active audio owner does not replace/start another microphone uplink; inactive same-token uplink remains recoverable | `AndroidMediaSinkStateTest` | `PASS_ROBOLECTRIC` |
| PR04-04 | Siri, telephone call, voice message, permission-denied, and real AudioRecord/uplink behavior on TS10/K706/iPhone | Physical device run | `NOT_RUN`; no target hardware is connected |

## PR-06 LOCAL split scaling without reconnect

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| PR06L-01 | Fit the fixed remote 16:9 display inside 55% split and portrait viewports with centered letterbox bounds; repeated full/split geometry leaves remote dimensions unchanged | `LocalCarPlayScalePolicyTest` | `PASS_LOCAL` policy/geometry test |
| PR06L-02 | Touch center maps to the same normalized remote coordinate; letterbox bars lie outside the fitted content hit region | `LocalCarPlayScaleTouchTest`, `LocalCarPlayScalePolicyTest` | `PASS_ROBOLECTRIC` / `PASS_LOCAL` |
| PR06L-03 | Local resize keeps the active session only with current owner, controller, Surface, and remote size; unsafe lifecycle states keep the restart fallback | `LocalCarPlayScalePolicyTest`; `CarPlayHostActivityLocalScaleTest.fullAndSplitViewportCyclesKeepTheRemoteControllerSessionAndSurface` | `PASS_ROBOLECTRIC`: 20 Activity resize cycles keep the remote dimensions and stored controller/session/surface identities; physical Surface/frame continuity remains `NOT_RUN` |
| PR06L-04 | 20 full↔split cycles with the same Controller/AirPlaySession, touch, YouTube identity, rotation/IME, owner/background, and Surface recreation on TS10/K706/iPhone | Physical device run | `NOT_RUN`; no target hardware is connected |

## PR-05 explicit ViewArea declaration model

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| PR05-01 | Legacy display config with no explicit model still serializes one full/custom area and initial index 0 | `AirPlayInfoPlistTest`, `CarPlayViewAreasTest` | `PASS_LOCAL`; production app leaves `viewAreas=null` |
| PR05-02 | Explicit Full/Split areas share one Main display entry/UUID; initial index, split fraction, physical size, RHD, and safe-area geometry remain valid | `CarPlayViewAreasTest` | `PASS_LOCAL` pure model/serializer test |
| PR05-03 | Resolution and UI canvas scaling carry both area coordinate spaces onto the target pixel size; empty/out-of-bounds geometry and invalid indices are rejected | `CarPlayViewAreasTest`, `CarPlayDisplayScaleTest`, `CarPlayUiScaleTest` | `PASS_LOCAL` |
| PR05-04 | iPhone acceptance of multiple Main ViewAreas and actual `/info` compatibility on TS10/K706/iPhone | Physical device run | `NOT_RUN`; the model is not enabled by the application |
| PR05-05 | Dynamic switch command/acknowledgement and same-session frame-size change | Physical protocol capture | `NOT_RUN`; no command is sent and PR-06 DYNAMIC remains gated |
| PR05-06 | Implicit single-area `/info` display encoding matches fixed main's legacy displayEntry/areaDict wire shape | `AirPlayInfoPlistTest.implicitSingleAreaDisplayEncodingMatchesTheFixedMainWireShape` | `PASS_LOCAL` for default and custom insets; phone compatibility and multi-area acceptance remain `NOT_RUN` |

## PR-06 DYNAMIC and PR-07 conditional gates

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| PR06D-01 | Phone accepts a specific ViewArea switch, acknowledges it, and changes the main frame size without reconnect across 20 full/split cycles | TS10/K706/iPhone protocol and video trace | `NO_GO_THIS_RUN`; no accepted command or same-session frame-size evidence; dynamic control remains off |
| PR07-01 | Codec-specific capability, output-format, Surface recovery, first-frame, pacing, and stall behavior on TS10/K706 | Physical decoder/Surface run | `NO_GO_THIS_RUN`; no target codec/Surface issue is evidenced; vendor-specific tuning remains off |
| PR07-02 | Existing codec CSD/random-access, decoder queue, and display diagnostic regression harnesses | `MediaCodecSupportTest`, `VideoDecodeQueueTest`, `DisplayDiagnosticSnapshotTest` | `PASS_LOCAL`; existing fallback remains unchanged |
| PR07-03 | A replaced screen stream cannot deliver stale codec/config/frame callbacks or invoke its close callback against the new owner | `CurrentScreenListenerTest`; `CarPlayMediaEngineTest.replacedScreenStreamCannotDeliverStaleCallbacksOrCloseTheSession` | `PASS_ROBOLECTRIC` listener and engine-map ownership checks; live `ScreenStream` payload interleaving, MediaCodec worker handoff, and target frame continuity remain `NOT_RUN` |

## DSP-00 existing DSP baseline

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| DSP00-01 | Profile schema v3, schema 0/1/2 migration, validation, and future-schema preservation | `DspProfileRepositoryTest` | `PASS_LOCAL` |
| DSP00-02 | Global master defaults off, MEDIA-only full DSP policy, and DSP-OFF PCM16 byte passthrough with zero track-generation latency and no pipeline preparation | `DspProfileRuntimeTest`, `DspStreamPolicyTest`, `DspRendererProductionPathTest.dspOffLpcmProductionPathWritesTheOriginalPcm16Samples` | `PASS_ROBOLECTRIC` |
| DSP00-03 | Existing mono/stereo Float32 pipeline chunking, reusable output, latency, reset, and failure behavior | `DspPcmPipelineTest`, `NativeDspProcessorTest` | `PASS_LOCAL` |
| DSP00-04 | Existing native graph C regression | `scripts/test_dsp_native.sh` with MSYS2 UCRT GCC | `PASS_LOCAL` unsanitized; `--sanitize` is blocked because this host lacks `libasan`/`libubsan` |
| DSP00-05 | Existing 40-case API 36.1 emulator performance baseline from the optimization ledger | `.scratchpad/optimization.md` | Prior baseline: worst light p99 1.10 ms, heavy p99 2.92 ms, zero steady-state Kotlin allocations; not rerun on a target head unit |

## DSP-01 Seat Focus and L/R time alignment

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| DSP01-01 | Explicit left/right near-speaker choice, Driver/Passenger/Center/Custom presets, distance conversion capped at 5 ms, independent ±6 dB trim, polarity, and v3→v4 profile migration | `DspSeatAlignmentConfigTest`, `DspProfileRepositoryTest` | `PASS_LOCAL` |
| DSP01-02 | Native integer/fractional delay, reset, trim/polarity, mono bypass, and latency sum with Convolver; audio-thread update defers until a new track generation | `test_dsp_engine.c`, `DspRendererProductionPathTest`, `NativeDspProcessorTest` | `PASS_LOCAL` unsanitized C / `PASS_ROBOLECTRIC` |
| DSP01-03 | SeatAlignment and FullHeavyChain-with-seat p99 plus steady-state allocation gate | API 36.1 x86_64 `DspPipelineBenchmarkInstrumentedTest` (44 configurations, 3×256-block rounds) | `PASS_EMULATOR` 16/16; seat stereo production p99 max 1.50 ms, FullHeavyChain-with-seat max 2.76 ms, zero steady-state Kotlin allocations |
| DSP01-04 | TS10/K706 listening, measured speaker distances, A/V sync, and head-unit memory/CPU impact | Physical device run | `NOT_RUN`; no target head unit or listening path is connected |
| DSP01-05 | Native ASan/UBSan on this Windows host | `scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV`; MSYS2 GCC lacks `libasan`/`libubsan`; unsanitized C suite passes |

## DSP-02 Advanced Mid/Side

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| DSP02-01 | Advanced M/S defaults off, legacy Stereo Width/Mono Bass are folded into low/high bands on first enable, sample-rate and parameter bounds are enforced, and positive Mid/Side/width gain enters Auto Headroom | `DspAdvancedMsConfigTest`, `DspAutoHeadroomTest` | `PASS_LOCAL` |
| DSP02-02 | LR4 low/high split reconstructs unity magnitude; band-specific Side width, mono input, and anti-phase Side behavior remain stable; final limiter constrains width overshoot | `test_dsp_engine.c` | `PASS_LOCAL` unsanitized |
| DSP02-03 | Mid/Side native parameters and DSP stateful-update deferral are wired through JNI and renderer lifecycle | `NativeDspProcessorTest`, `DspRendererProductionPathTest` | `PASS_LOCAL` / `PASS_ROBOLECTRIC` |
| DSP02-04 | Advanced M/S and full-chain p99 plus allocation gate across 44.1/48 kHz mono/stereo | API 36.1 x86_64 `DspPipelineBenchmarkInstrumentedTest` (56 configurations after adding the four new DSP cases) | `PASS_EMULATOR`; AdvancedMS max median-run p99 0.80 ms, FullHeavyChain-with-all-four max 3.12 ms, zero steady-state Kotlin allocations |
| DSP02-05 | TS10/K706 listening, phase/group-delay assessment, mono/anti-phase car playback, and long-run validation | Physical device run | `NOT_RUN`; no target head unit or listening path is connected |

## DSP-03 Measured cabin correction

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| DSP03-01 | External CSV format, measurement metadata, 20–300 Hz coverage, 1/6-octave spacing, ≥12 dB signal/noise margin, clipping rejection, bounded suggestions, and `CALIBRATION_UNAVAILABLE` outcomes | `DspCabinMeasurementCsvTest` | `PASS_LOCAL` |
| DSP03-02 | Up to three user-reviewed static PEQ cuts; manual edit/remove/undo; schema v5→v6 migration defaults off; no program-audio analysis | `DspProfileRepositoryTest`, `DspAutoHeadroomTest`, `NativeDspProcessorTest` | `PASS_LOCAL` |
| DSP03-03 | Cabin3Notches and final-chain p99 plus allocation gate across 44.1/48 kHz mono/stereo | API 36.1 x86_64 `DspPipelineBenchmarkInstrumentedTest` (56 configurations) | `PASS_EMULATOR`; Cabin3Notches max median-run p99 0.64 ms; zero steady-state Kotlin allocations |
| DSP03-04 | TS10/K706 measured response, noise/clipping environment, listening and A/V validation | Physical device run | `NOT_RUN`; no target measurement or listening path is connected |

## DSP-04 Harmonic Engine

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| DSP04-01 | EVEN/ODD/MIXED shaping, 9/17/33-tap 2× FIR, matched dry latency of 4/8/16 frames, amount/cutoff bounds, and Auto Headroom | `DspHarmonicConfigTest`, `DspAutoHeadroomTest` | `PASS_LOCAL` |
| DSP04-02 | Disabled-path parity, block invariance, mode-specific harmonic generation, 14 kHz→6 kHz alias rejection, and latency sum with Convolver | `test_dsp_engine.c` | `PASS_LOCAL` unsanitized |
| DSP04-03 | Harmonic JNI parameters, profile v6→v7 migration, and active update deferral to the next track | `NativeDspProcessorTest`, `DspProfileRepositoryTest`, `DspRendererProductionPathTest` | `PASS_LOCAL` / `PASS_ROBOLECTRIC` |
| DSP04-04 | HarmonicEngine and FullHeavyChain p99/peak/allocation across 44.1/48 kHz mono/stereo | API 36.1 x86_64 `DspPipelineBenchmarkInstrumentedTest` (56 configurations) | `PASS_EMULATOR`; HarmonicEngine max median-run p99 1.48 ms; FullHeavyChain-with-all-four max 3.12 ms; zero steady-state Kotlin allocations |
| DSP04-05 | TS10/K706 THD/alias, peak, listening, A/V sync, and long-run behavior | Physical device run | `NOT_RUN`; no target head unit or listening path is connected |

## DSP-05 Final graph integration and pre-merge audit

| Evidence ID | Scenario | Main test/evidence | Status |
|---|---|---|---|
| DSP05-01 | All 16 on/off combinations of four new modules, each at 44.1/48 kHz mono/stereo; 1,024 PCM16 frames match between one block and variable block boundaries | API 36.1 x86_64 `DspFinalIntegrationInstrumentedTest` | `PASS_EMULATOR`; 64 format/combination runs compare byte-for-byte |
| DSP05-02 | Harmonic alias rejection across both sample rates/channels, malformed Harmonic JNI rejection, exact half-LSB PCM16 rounding, profile v0–v7 migration/future schema | `DspFinalIntegrationInstrumentedTest`, `NativeDspBridgeInstrumentedTest`, `DspPcmConverterTest`, `DspProfileRepositoryTest` | `PASS_LOCAL` and `PASS_EMULATOR`; measured folded alias was at most 4.1×10⁻⁵ of the fundamental across all four formats |
| DSP05-03 | Retained Float32 graph order and latency sum: Seat + Harmonic FIR + Convolver; disabled path remains byte-exact and zero latency | `DSP_FINAL_INTEGRATION.md`, `dsp_engine_get_latency_frames`, production-path tests | `PASS_STATIC` / `PASS_LOCAL` |
| DSP05-04 | `FullHeavyChain` p99, peak, and steady-state allocation; both apps' lint and Debug/Benchmark builds; JNI/public-tree/license checks | API 36.1 x86_64 matrix, Gradle, scripts | `PASS_EMULATOR` for 56 cases, `PASS_LOCAL` for 532 shared + 152 common unit tests, both app lint/Debug/Benchmark builds and unsanitized native C. Hosted ASan/UBSan passed in run #130; local MSYS2 sanitizer linking is `BLOCKED_ENV`. |
| DSP05-05 | TS10/K706/iPhone A/V, THD/alias/listening, CPU/memory, soak, rollback/update/signature | Physical device/release run | `NOT_RUN`; target hardware and release exercise are unavailable |

## PR-08 integrated regression, rollback, and staged release

| Specification gate | Scenario | Evidence | Status |
|---|---|---|---|
| PR-08 automated integration | Unit/Robolectric, emulator instrumentation, DSP/native checks, both app builds/lint, JNI/public-tree, and cross-stage regressions | GitHub Actions #130 at `72bab8f`: `check` and API 36.1 instrumentation passed; #168 at `5840d72`: full host/native/ASan+UBSan/unit/lint/build/JNI passed. The local instrumentation script passed 19 shared + 1 common test on isolated API 36.1; runs #294/#296 passed the screen callback fix and engine-map regression; run #297 passed the shared PR-05 byte-parity test. Draft CI filters newly added or modified standalone `*Test`, `*Tests`, or `*TestCase` files; helper/fixture, production, mixed, unknown, or unsafe paths retain full applicable test modules. `RemoteMfiAuthenticationClientTest` passed locally 5/5 in 8 s and hosted in run #305; the hosted Gradle task took 1m20. This does not establish a time saving against the differently scoped #297 run | `PASS_LOCAL` / `PASS_EMULATOR` for recorded stages; not a hardware or release pass |
| PR-08 EX-08 ledger | Complete current decision coverage for pinned upstream commits | `scripts/generate_step4_ex08_inventory.py --check`; EX08-01/02 above | `PASS_LOCAL` inventory; 380 `NEED_EVIDENCE` entries remain pending |
| U73 | Roll back implemented STEP4 flag/config choices to their safe default while preserving unrelated state; keep conditional no-go features inactive | `ConnectionTraceStoreTest.debugPreferenceDefaultsOffAndCanBeReturnedToOff`; `NcmDiagnosticProfileStoreTest` debug profile save/restore and non-debug ignore; `DspProfileRuntimeTest`; `DspProfileRepositoryTest.rollingBackEachAddedModulePreservesLegacyConfigAndOtherProfiles`; `SplitLayoutStateTest`; `CarPlayViewAreasTest` legacy-null fallback | `PARTIAL`: latest fresh shared suite has 540 tests; latest common suite has 156, all with zero failures/errors/skips. Rollback/default cases cover debug trace, NCM comparators, DSP master/modules, LOCAL split exit, and legacy ViewArea fallback. Run #266 passed the earlier shared/common draft lane; installed-release rollback and physical trigger/isolation checks remain `NOT_RUN`; conditional features stay unimplemented/off |
| U74 | Upgrade an older APK/profile and preserve settings, signature, and ownership | Fixed-main automotive debug APK v1201 on isolated API 36.1 AVD, seeded a valid schema-v3 `custom1` profile (`autoHeadroom=false`) and selection preferences, then test-only current-code v1202 APK with matching debug certificate via `adb install -r`; profile and preference bytes stayed unchanged. `DspProfileRepositoryInstrumentedTest.schemaThreeProfileLoadsAtRuntimeAndSavesWithAndroidAtomicFile` exercised `DspProfileRuntime` with the v3 profile, confirmed legacy values/new-module OFF defaults, and saved/reloaded v7 | `PARTIAL`: `PASS_EMULATOR` for debug APK replacement/data preservation and the common runtime/migration test in test context; `PASS_CI` shared/common draft run #266; production signing and profile loading in the updated automotive package remain `NOT_RUN` |
| U75 / G8 hardware | TS10/K706+iPhone initial/reconnect/Split/audio matrix and recorded success/failure evidence | No physical target attached | `NOT_RUN` |
| Review A/B | Two independent correctness/protocol and Android/product reviews, with fixes and rerun evidence | No independent review submitted; user directed review to remain unrequested | `NOT_RUN` |
| G8 release decision | Hardware matrix, rollback, signed update, required reviews, and all applicable automated gates | The preceding rows and remaining `NEED_EVIDENCE` ledger items | `NO_GO_THIS_RUN`; do not mark the release ready |

## Main baseline runs

| Level | Main-SHA result | Limit |
|---|---|---|
| JVM/Robolectric, initial main | 453 shared + 136 common; zero failures/errors/skips | Does not exercise physical USB, VPN/TUN, or iPhone communication |
| JVM/Robolectric, after PR-00 fixtures | 464 shared + 136 common; zero failures/errors/skips | Includes 7 `NcmUsbBridgeFixtureTest` and 4 `Step4TestFixturesTest` cases |
| Android instrumentation | 16/16 on API 36.1 x86_64 AVD | Emulator is not TS10/K706/iPhone |
| Native C | Host unsanitized suite passed | Host ASan/UBSan linking unavailable in MSYS2 |
| App lint/build | Both app Debug/Benchmark builds and lints passed | No release signing/update exercise |
| EX-01 branch verification | 483 shared + 137 common unit tests; both app lints and Debug/Benchmark assemblies passed | Full target-path and performance A/B remain `NOT_RUN` |
| PR-01 + EX-02 working-tree verification | 495 shared + 142 common tests; zero failures/errors/skips; both app lints and Debug/Benchmark assemblies passed | Device path and independent second review remain `NOT_RUN` |
| PR-04 working-tree verification | 502 shared + 142 common tests; zero failures/errors/skips; both app lints and Debug/Benchmark assemblies passed | Physical microphone/Siri/call/voice-message checks remain `NOT_RUN` |
| PR-06 LOCAL working-tree verification | 502 shared + 147 common tests; zero failures/errors/skips; both app lints and Debug/Benchmark assemblies passed | Physical 20-cycle same-session validation remains `NOT_RUN` |
| PR-05 working-tree verification | 507 shared + 147 common tests; zero failures/errors/skips; both app lints and Debug/Benchmark assemblies passed | Multi-area `/info` acceptance and dynamic wire behavior remain `NOT_RUN` |
| DSP-00 baseline | 507 shared + 148 common tests; profile schema/default-OFF, MEDIA-only, PCM16 bypass/latency, and native C baseline passed | Host ASan/UBSan blocked by missing MSYS2 runtimes |
| DSP-01 working-tree verification | 514 shared + 149 common tests; both app lints and Debug/Benchmark assemblies passed; API 36.1 instrumented 16/16 | SeatAlignment emulator p99/zero-allocation evidence passed; physical acoustic/A/V validation remains `NOT_RUN` |
| DSP-05 final working-tree verification | 532 shared + 152 common tests; API 36.1 x86_64 instrumentation 19/19; both app Debug/Benchmark builds and lints passed; native C unsanitized passed | 56 benchmark cases across 44.1/48 kHz mono/stereo; maximum median-run FullHeavyChain production p99 3.12 ms; hardware acoustic/A/V and soak gates remain `NOT_RUN` |
| Hardware | No TS10/K706/iPhone was attached | `NOT_RUN`; not a pass |

G8 is not passed: the unit/emulator results above apply only to their recorded test levels. U73/U74/U75 and independent Review A/B remain open, and the 380 EX-08 `NEED_EVIDENCE` entries do not convert hardware or release gates into passes. EX-01 has a source-level implementation and fixture coverage but is not device-validated or release-ready.
