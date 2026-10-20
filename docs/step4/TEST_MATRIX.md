# PR-00 — Baseline and Acceptance Matrix

The complete test definitions are U01–U75 in v3. This matrix retains every ID and shows current-main evidence after PR-00. `PASS_LOCAL` is limited to the named local test, `PASS_CHARACTERIZATION` may record a known defect, `PASS_STATE_MODEL` covers only a pure ownership/state model, `PARTIAL_LOCAL` covers only the stated unit/model portion, `PARTIAL_EMULATOR` covers only the stated AVD subset, `FIXTURE_ONLY` means test data exists but no production path is exercised, and `NOT_RUN_HARDWARE` means no supported head unit/iPhone test occurred. No local or emulator result is a hardware pass.

PR-00 test-only helpers are under `shared/src/test/java/com/shilapi/xcertplay/step4/`. `NcmUsbBridgeFixtureTest` scripts every bulk-IN/OUT, queue, and `requestWait` outcome; unscripted USB actions throw instead of assuming success. `Step4FixtureCatalogTest` verifies the deterministic startup, NTB, TUN, virtual time, PCM/video, split, and input-request fixtures.

| ID | v3 scenario / acceptance | Main evidence | PR-00 status |
|---|---|---|---|
| U01 | 512B NTB without pad delivers without waiting for another byte | `NcmUsbBridgeFixtureTest.exact512ByteNtbAcceptsTheMainPadAndCharacterizesTheUnpaddedWait` | `PASS_CHARACTERIZATION`: current main waits for the pad and delivers no frame; PR-01 must reverse this |
| U02 | 512B NTB with zero pad consumes exactly one byte | Same fixture test | `PASS_LOCAL` |
| U03 | Two NTBs split/coalesced preserve order and the next header | `NcmUsbBridgeFixtureTest.synchronousReadsReassembleSplitAndCoalescedNtb16Blocks` | `PASS_LOCAL` |
| U04 | Garbage followed by valid NTB recovers with bounded resync | Main has no receiver resync path yet | `NOT_RUN` |
| U05 | Invalid NTH/NDP offsets cause no OOB or infinite loop | `Ntb16FixtureCatalogTest` tests invalid NDP fixture parsing; receiver path not covered | `PARTIAL_LOCAL` |
| U06 | More than 1 MiB of corrupt input fails finitely without OOM | No large-garbage receiver test | `NOT_RUN` |
| U07 | Successful 32 KiB USB read preserves request and buffer | Async `NcmUsbBridgeFixtureTest` checks the 32 KiB direct-read capacity and later completion | `PASS_LOCAL` |
| U08 | Queue false retries smaller sizes only when permitted | `NcmUsbBridgeFixtureTest.queueRejectionRecordsThe32KiBRequestAndDoesNotAssumeSuccess` | `PASS_CHARACTERIZATION`: current main fails the queue attempt; no fallback ladder exists |
| U09 | Timeout while queued causes no second queue | `NcmUsbBridgeFixtureTest.asyncTimeoutRetainsOneQueuedRequestForTheLaterCompletion`, `PersistentUsbReadRequestStateTest` | `PASS_LOCAL` |
| U10 | Completion from another endpoint/request is attributed to its owner | Unexpected request is rejected; no concurrent multi-endpoint dispatcher | `PARTIAL_LOCAL` |
| U11 | Close racing with requestWait does not leak or double-close | `PersistentUsbReadRequestStateTest.closeDuringWaitRequestsOneCancelAndOwnsOneFinalClose` | `PASS_STATE_MODEL`; device race `NOT_RUN` |
| U12 | Sync and async read modes produce the same result | `NcmUsbBridgeFixtureTest.synchronousAndAsynchronousReadModesDeliverTheSameFrame` | `PASS_LOCAL` |
| U13 | IPv4 SYN to listener is counted once and only for AirPlay | IPv4 SYN input fixture exists; main has no per-attempt SYN classifier | `FIXTURE_ONLY` |
| U14 | IPv6 SYN with extension headers is bounded and port-correct | `EthernetIpv6CodecTest` bounds extension parsing for NDP, not TCP SYN classification | `PARTIAL_LOCAL` |
| U15 | IPv6 fragments/jumbograms are not counted as a proven SYN | No per-attempt TCP SYN classifier | `NOT_RUN` |
| U16 | VLAN and short Ethernet inputs become UNKNOWN/invalid safely | `EthernetIpv6CodecTest` rejects truncated/non-IPv6 frames; VLAN classification is absent | `PARTIAL_LOCAL` |
| U17 | Invalid IPv6 version/length never counts as valid TCP | `EthernetIpv6CodecTest` rejects truncated/non-IPv6 input; no classifier counter exists | `PARTIAL_LOCAL` |
| U18 | AirPlay-port SYN-ACK is measured outbound only | No per-attempt packet direction classifier | `NOT_RUN` |
| U19 | Wrong-port IPv4/IPv6 SYN increments no AirPlay SYN count | No per-attempt destination-port classifier | `NOT_RUN` |
| U20 | 100k seeded arbitrary packets cause no exception, loop, or OOM | No seeded fuzz run | `NOT_RUN` |
| U21 | Inbound IPv6 SYN counts success only after TUN write succeeds | `SimulatedTun` fixture exists; not wired to `Ipv6NcmBridge` | `FIXTURE_ONLY` |
| U22 | TUN write exception records zero success and a classified error | No per-attempt TUN-write counters | `NOT_RUN` |
| U23 | Kernel SYN-ACK observation is separate from USB OUT | No per-attempt TUN/NCM trace | `NOT_RUN` |
| U24 | SYN-ACK bulk OUT NotReady is counted without invented retry | `NcmUsbBridgeFixtureTest.notReadyAndPartialBulkOutResultsStayDistinct` uses IPv6 SYN-ACK input and verifies typed NotReady/attempt counters | `PARTIAL_LOCAL`: no SYN-ACK-specific lost counter |
| U25 | Full NCM block transfer is the only Sent result | `NcmUsbBridgeFixtureTest.bulkOutRequiresAnExplicitScriptedResultAndWritesTheBuiltNtb` | `PASS_LOCAL` |
| U26 | Partial transfer is not Sent and returns typed failure | `NcmUsbBridgeFixtureTest.notReadyAndPartialBulkOutResultsStayDistinct` | `PASS_LOCAL` |
| U27 | Missing peer MAC is separately observable | No peer-MAC per-attempt recorder | `NOT_RUN` |
| U28 | Old attach-generation accept cannot contaminate a new attempt | `WiredStartupWatchdogTest.oldDataPathCallbackCannotPublishIntoANewerGeneration` | `PARTIAL_LOCAL`: generation fence, not per-attempt diagnostic attribution |
| U29 | FINAL racing with callback leaves immutable snapshot | No per-attempt FINAL recorder exists | `NOT_RUN` |
| U30 | Concurrent event increments lose no updates | No per-attempt counter recorder exists | `NOT_RUN` |
| U31 | Diagnostic ON/OFF has identical USB/TUN side effects | New EX-01 recorder is not implemented | `NOT_RUN` |
| U32 | Exported logs contain no PII, payload, or credentials | `DiagnosticRedactorTest`, `DiagnosticExportStoreTest` cover existing export paths, not EX-01 schema | `PARTIAL_LOCAL` |
| U33 | Watchdog is not armed before StartSession is sent | `WiredStartupWatchdogTest.startSessionArmsOnceAndExpiresOnce` tests policy, not Controller call ordering | `PARTIAL_LOCAL` |
| U34 | 8s soft event snapshots without teardown | `WiredStartupWatchdogTest.softSnapshotDoesNotExpireTheHardWatchdog` | `PASS_LOCAL` policy test |
| U35 | 15s hard expiry performs exactly one teardown | Watchdog token/policy tests only; Controller resource teardown not integrated | `PARTIAL_LOCAL` |
| U36 | ALT SETUP without MAIN SETUP is separately recorded | No MAIN/ALT diagnostic stage implementation | `NOT_RUN` |
| U37 | Active control without MAIN frame retains current deadline semantics | No stage-specific recorder | `NOT_RUN` |
| U38 | Old watchdog token has no effect on current attempt | `WiredStartupWatchdogTest.oldSoftAndHardCallbacksCannotAffectTheNextAttempt` | `PASS_LOCAL` |
| U39 | fail→active cancels stale Host retry timer | `CarPlayRestartHandoffTest` covers owner handoff, not the Host retry timer race | `PARTIAL_LOCAL` |
| U40 | fail→active→second loss allows one new retry | No exact Host retry timer regression | `NOT_RUN` |
| U41 | Controller-owned wired failure does not trigger Host double retry | `WiredStartupWatchdogTest.startupRecoveryAllowsThreeShortCleanRetriesBeforeDeepRecoveryCandidate` tests policy only | `PARTIAL_LOCAL` |
| U42 | Owner transfer/Activity recreation fences old callbacks | `CarPlayRestartHandoffTest` | `PARTIAL_LOCAL` |
| U43 | Same-generation 10s re-enumeration probe runs once | EX-04 not implemented | `NOT_RUN` / conditional |
| U44 | Detach or USB update before 10s makes probe a no-op | EX-04 not implemented | `NOT_RUN` / conditional |
| U45 | First OUT delay beyond 15s proves conditional retry unreachable | No late-OUT recovery implementation or causal trace | `NOT_RUN` |
| U46 | Healthy SYN/accept path sends no extra StartSession | No late-OUT retry implementation | `NOT_RUN` |
| U47 | Stale CSM/reconnect state cannot resend StartSession | No late-OUT retry implementation | `NOT_RUN` |
| U48 | VPN local bind/address validation separates destination match from accept | Current bridge and VPN code have no PR-00 bind/accept test | `NOT_RUN` |
| U49 | Old server accept after new attachment cannot use new generation | Existing generation callback test does not exercise TCP accept | `PARTIAL_LOCAL` |
| U50 | input=false with valid port starts no recording | `AirPlayInfoPlistTest.microphoneInputsAreAdvertisedOnlyWhenEnabled` covers advertisement, not capture start | `PARTIAL_LOCAL` |
| U51 | Siri/Phone retain existing routes | Unit capability/lifecycle tests exist; no supported-phone call/Siri device test | `NOT_RUN_HARDWARE` |
| U52 | Default/compat voice note is allowed only when requested and stops correctly | No voice-message end-to-end fixture | `NOT_RUN` |
| U53 | AudioRecord error/retry is bounded without duplicate recorder | `MicrophoneUplinkLifecycleTest` covers stale close-before-start only | `PARTIAL_LOCAL` |
| U54 | NAV/ALERT/PHONE/ASSISTANT preserve DSP policy, RTP, and clock | `AudioStreamClassificationTest`, `DspStreamPolicyTest`, and audio lifecycle tests cover component policies, not phone routing | `PARTIAL_LOCAL` |
| U55 | Local full→55%→full preserves same AirPlaySession/audio | `SplitLayoutStateTest` covers local state; no same-session 20-cycle hardware test | `PARTIAL_LOCAL` |
| U56 | Edge/letterbox touch uses inverse geometry | `CarPlayDisplayScaleTest` covers scale/dimension math, not the complete rendered touch overlay | `PARTIAL_LOCAL` |
| U57 | `/info` single-area default stays compatible | `AirPlayInfoPlistTest.defaultDisplayIncludesFullViewAndSafeAreas` | `PASS_LOCAL` |
| U58 | Invalid safe area in two-area declaration safely rejects/falls back | Main remains single-area; multi-area validation is not implemented | `NOT_RUN` |
| U59 | Wire-send success alone never enables DYNAMIC | Dynamic ViewArea coordinator not implemented | `NOT_RUN` |
| U60 | Device silence/rejection falls back finitely to LOCAL/LEGACY | Dynamic ViewArea coordinator not implemented | `NOT_RUN` |
| U61 | Google profile A/B has no cookie/login crossover | `YoutubeDeviceProfileTest` covers profile identity/context separation, not real login/cookie isolation | `PARTIAL_LOCAL` |
| U62 | Surface/rotation/IME changes do not unexpectedly recreate a session | `DisplayResizeCoordinatorTest` and host UI tests cover state decisions, not device Surface lifecycle | `PARTIAL_LOCAL` |
| U63 | Codec output-format change keeps crop/decoder/touch aligned | `MediaCodecSupportTest`/`VideoDecodeQueueTest` cover components, not the full Surface/touch path | `PARTIAL_LOCAL` |
| U64 | Missing CSD/timeout/seek uses bounded decoder-only fallback | No complete CSD/seek/Surface fixture in PR-00 | `NOT_RUN` |
| U65 | DSP master OFF matches legacy PCM golden | `DspOffLegacyPcmTest.disabledDspKeepsPcm16OnTheLegacyPassThroughPath` | `PASS_LOCAL` |
| U66 | Seat Focus L/R delay, trim, polarity, and pop behavior | Seat Focus extension not present on main | `NOT_RUN` |
| U67 | Advanced M/S mono/antiphase stability | Main has legacy width behavior, not the proposed Advanced M/S processor | `NOT_RUN` for Advanced M/S |
| U68 | Invalid cabin measurement fails safely without auto correction | Cabin correction extension not present | `NOT_RUN` |
| U69 | Harmonic oversampling checks alias, headroom, and latency | Harmonic Engine extension not present | `NOT_RUN` |
| U70 | Native 44.1/48 kHz mono/stereo and all block sizes stay finite | API 36.1 AVD benchmark ran 40 configurations × 3 rounds; it does not cover every v3 block size or a TS10 unit | `PARTIAL_EMULATOR` |
| U71 | Live update plus Convolver preserves A/V clock | Existing live-update tests cover controller state; no target A/V or long-session measurement | `PARTIAL_LOCAL` |
| U72 | Deleted BYD-only dependencies do not reappear | PR-00 did not run a complete OEM-only source/manifest audit; main contains remaining BYD package/settings references to classify under PR-08 | `NOT_RUN` |
| U73 | Each feature-flag rollback leaves other features unchanged | PR-00 adds no production feature flag | `NOT_RUN` |
| U74 | Old profile and APK update preserve settings/signature/ownership | Profile repository migration tests exist; APK update/signature rehearsal is not run | `PARTIAL_LOCAL` |
| U75 | TS10 first connection/reconnect/Split/audio yields measured evidence | No TS10/K706/iPhone device connected to ADB | `NOT_RUN_HARDWARE` |

### PR-00 execution record

- Before new fixtures: 453 shared + 136 common unit tests, 0 failures/errors/skips.
- After new fixtures: 470 shared + 136 common unit tests, 0 failures/errors/skips; the full tasks were forced with `--rerun-tasks`.
- API 36.1 emulator: 16/16 instrumentation tests; DSP benchmark log contained 120 records (40 configurations × 3 runs).
- The 512-byte no-pad case is a known-current-main characterization and remains an implementation defect until PR-01 passes it after the repair.
