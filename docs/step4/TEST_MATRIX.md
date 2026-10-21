# STEP4 test and acceptance matrix

## Status definitions

- `PASS_LOCAL`: the named unit/state test ran on fixed main; it proves only that test's scope.
- `PARTIAL`: related local coverage exists, but the required integration, race, or device condition is not proved.
- `PASS_EMULATOR`: connected test ran on the named emulator; it is not a physical-head-unit result.
- `NOT_RUN`: no test or target evidence has established the requirement.
- `BLOCKED_ENV`: the test was attempted but the local toolchain prevented execution.

## Stage order and completion gates

The original PR numbers remain unchanged. This order follows v3 dependencies; a `CONDITIONAL` item may finish as a documented `NO_GO` if its required evidence is unavailable.

| Sequence | Original / added ID | Scope | Current stage state |
|---:|---|---|---|
| 1 | PR-00 | Test fixtures and fixed-main baseline | `IN_PROGRESS`; fixtures and local evidence recorded; hosted PR check/review pending |
| 2 | EX-01 | Wired per-attempt NCM/TUN/TCP/OUT diagnostics only | `NOT_STARTED`; no behavior change before observation |
| 3 | PR-01 | NCM optional pad and bounded corruption resync | `NOT_STARTED`; source/integration tests required |
| 4 | EX-02 | Host stale-retry and second-loss ownership | `NOT_STARTED` |
| 5 | EX-03 | Watchdog stage observation; preserve existing deadlines initially | `NOT_STARTED`; policy change conditional on evidence |
| 6 | EX-04 | Same-node 10-second USB recheck | `CONDITIONAL`; no same-node reproduction yet |
| 7 | PR-02 | Original Android 8 title retained; API28+ queue fallback only if reproduced | `CONDITIONAL`; API26/27 path is not applicable at minSdk28 |
| 8 | PR-03 | Initial OUT timeout/readiness comparison | `CONDITIONAL`; preserve current AUTO until A/B evidence |
| 9 | EX-05 | Late OUT recovery / additional StartSession | `CONDITIONAL/NO_GO`; requires causal TS10 evidence and 15-second reachability test |
| 10 | EX-06 | Status endpoint and completion ownership | `CONDITIONAL`; preserve single requestWait owner |
| 11 | EX-07 | IP/scope/peer-MAC/listener-generation correction | `CONDITIONAL`; change only after causal repro |
| 12 | PR-04 | Microphone request classification and audio regressions | `NOT_STARTED` |
| 13 | AUDIO-EX | Additional audio buffer/Opus/clock/route candidates | `CONDITIONAL`; separate from PR-04 and only if their symptoms/evidence justify changes |
| 14 | PR-06 LOCAL | Local 55:45 no-restart scaling and touch mapping | `NOT_STARTED`; precedes wire ViewArea work |
| 15 | PR-06 GECKO | Preserve device-profile isolation, warm-session and background-owner behavior | `PRESERVE`; baseline tests exist, target-device A/B remains `NOT_RUN` |
| 16 | PR-05 | ViewArea model/protocol adapter; keep legacy default | `NOT_STARTED`; enable only after iPhone acceptance |
| 17 | PR-06 DYNAMIC | Dynamic ViewArea switching | `CONDITIONAL`; depends on PR-05 acknowledgement and same-session evidence |
| 18 | PR-07 | Codec/surface recovery and latency | `CONDITIONAL`; preserve current codec behavior until symptom/capability evidence |
| 19 | DSP-00 | Freeze existing two-channel DSP and PCM/latency baseline | `NOT_STARTED` |
| 20 | DSP-01 | Seat Focus / L-R alignment | `NOT_STARTED` |
| 21 | DSP-02 | Advanced Mid/Side | `NOT_STARTED` |
| 22 | DSP-03 | Cabin correction from measured inputs | `CONDITIONAL`; no synthetic measurement data |
| 23 | DSP-04 | Harmonic Engine | `NOT_STARTED` |
| 24 | DSP-05 | Integrated DSP graph and interaction audit | `NOT_STARTED`; follows DSP-00..04 |
| 25 | PR-08 | Full integration, rollback, update/signing and release decision | `NOT_STARTED`; final stage |
| Cross-cutting | EX-08 | Per-commit upstream decision ledger, 1,289 indexed changes | `PARTIAL`; register schema exists, commit-by-commit semantic ledger not yet completed |
| Cross-cutting | WIRELESS-EX | Conditional wireless first-TCP/bind/dual-stack candidate | `CONDITIONAL`; no change until the specified supported-head-unit evidence exists |

## Fixed-main baseline

Base SHA `95ba398f95ee104527812cd2b095977d71c51f98` ran shared/common 589 unit tests (453 + 136), 16 shared instrumentation tests, native DSP unsanitized tests, both app Debug lint tasks, both Debug and Benchmark assemblies, JNI mapping checks, and the public-tree check. See [BASELINE.md](BASELINE.md) for command results and limits. The `@main` status column describes coverage before PR-00 fixtures; PR-00's new tests are recorded separately below.

## PR-00 fixture verification

| Command / suite | Result | Scope |
|---|---|---|
| `:shared:testDebugUnitTest :common:testDebugUnitTest` after fixture addition | `PASS_LOCAL`: shared 464 + common 136 = 600; 0 failures, 0 errors, 0 skipped | Incremental invocation reran shared tests; unchanged common result was up-to-date from the preceding uncached run. Eleven new shared fixture tests; no production source changes. |
| `Step4TestFixturesTest` | `PASS_LOCAL`: 5 tests | NTB valid/split/coalesced/pad/malformed inputs; endpoint/startup/mic/Split/PCM/video fixture data; explicit TUN outcomes; virtual scheduler. |
| `NcmUsbBridgeFixtureTest` | `PASS_LOCAL`: 6 tests | Actual NCM bridge with scripted Robolectric USB: verifies 32 KiB request capacity and READ_QUEUED → FIRST_USB_COMPLETION → FIRST_ETHERNET_RX ordering; timeout retains one request; close wakes the receiver and cancels once; rejection/null/unexpected results remain distinct; NotReady/full/partial OUT results stay typed. |
| `scripts/test_classify_android_ci.py` | `PASS_LOCAL`: 11 tests | Static, draft, native, ready, unknown, missing-path, static-root/source mix, same-repository, fork-head and API-failure fallback cases. |
| `bash -n scripts/run_android_instrumentation.sh scripts/test_dsp_native.sh` | `PASS_LOCAL` | Both changed/existing test scripts parse under MSYS2 Bash. |
| `scripts/run_android_instrumentation.sh` after marker-scoped capture | `PASS_EMULATOR`: 16/16, 0 failures/errors/skips; 120 fresh benchmark records | Medium Phone API 36.1 x86_64 emulator. No physical target proof. |

The instrumentation logger records only `DSP_BENCHMARK` entries between unique begin/end markers for that run; earlier emulator logcat history is excluded.

## U01–U75 main-baseline matrix

| ID | Required scenario | Existing main evidence | Status at main |
|---|---|---|---|
| U01 | 512-byte NTB16 boundary with no optional pad is delivered without waiting for another byte | `Ntb16CodecTest` covers codec construction/parsing, not `NcmUsbBridge` receive framing | `NOT_RUN` |
| U02 | 512-byte NTB16 boundary with zero pad consumes only that pad | `Ntb16CodecTest`; no receive-stream pad assertion | `PARTIAL` |
| U03 | Two NTBs split/coalesced across reads preserve order and headers | `UsbMuxFrameDecoderTest` covers USBMUX framing, not NCM NTBs | `NOT_RUN` for NCM |
| U04 | Garbage followed by a valid NTB recovers with bounded resynchronization | No NCM receive-stream recovery test | `NOT_RUN` |
| U05 | Invalid NTH/NDP offsets fail safely without out-of-range access or an infinite loop | Parser source has bounds checks; no malformed NDP offset regression test | `NOT_RUN` |
| U06 | More than 1 MiB of corrupt stream data terminates without OOM | No NCM stream-size boundary test | `NOT_RUN` |
| U07 | A successful USB IN queue uses/reuses the existing 32 KiB request buffer | `PersistentUsbReadRequestStateTest` covers lifecycle state, not a real request buffer | `PARTIAL` |
| U08 | API28+ queue-false fallback, if justified, preserves one request owner | No device-present queue-false evidence; API26/27 path is not applicable at minSdk28 | `NOT_RUN` / `NOT_APPLICABLE` for API26/27 |
| U09 | A timed-out wait leaves the same request queued without a second queue call | `PersistentUsbReadRequestStateTest.timeoutKeepsTheSameRequestQueuedAndDoesNotRequestCancellation` | `PASS_LOCAL` state model only |
| U10 | A completion from another endpoint is dispatched to its owner or explicitly fails | `PersistentUsbReadRequestStateTest` covers unexpected request identity, not multi-endpoint `requestWait` | `PARTIAL` |
| U11 | Close racing with `requestWait` has one cancel/close and no deadlock | `PersistentUsbReadRequestStateTest` covers close-plan state, not a blocked Android request | `PARTIAL` |
| U12 | Sync and async NCM read modes produce equivalent frames | No paired NCM bridge test | `NOT_RUN` |
| U13 | IPv4 SYN to the configured AirPlay listener is counted only for that destination | `EthernetIpv6CodecTest` is IPv6-focused; no provenance classifier exists | `NOT_RUN` |
| U14 | IPv6 SYN with extension headers is parsed with bounded depth and correct port | `EthernetIpv6CodecTest` covers current IPv6 parsing, not the new classifier contract | `PARTIAL` |
| U15 | IPv6 fragments/jumbo payloads are not asserted to be a complete SYN | No classifier/jumbo regression case | `NOT_RUN` |
| U16 | VLAN and short Ethernet frames are categorized safely | Existing Ethernet codec tests cover some malformed frames; no diagnostic category test | `PARTIAL` |
| U17 | Invalid IPv6 version/length never increments valid TCP/SYN evidence | `EthernetIpv6CodecTest`; no attempt-scoped counters | `PARTIAL` |
| U18 | AirPlay-source-port SYN-ACK is recorded as outbound-only evidence | No packet-provenance recorder | `NOT_RUN` |
| U19 | IPv4/IPv6 SYN to a wrong port is not counted as AirPlay SYN | No packet-provenance recorder | `NOT_RUN` |
| U20 | 100,000 seeded random packets terminate without exceptions, loops, or OOM | No seeded classifier fuzz run | `NOT_RUN` |
| U21 | Inbound IPv6 SYN is marked successful only after `TUN.write` returns success | No attempt-scoped TUN recorder | `NOT_RUN` |
| U22 | A TUN write exception leaves success count at zero and records failure | No attempt-scoped TUN recorder | `NOT_RUN` |
| U23 | Kernel SYN-ACK observation is separate from USB OUT result | Existing NCM counters do not record the complete TUN-to-OUT provenance chain | `NOT_RUN` |
| U24 | SYN-ACK OUT `NotReady` is visible and causes no added retransmission | `NcmDiagnosticProfileTest` covers profile selection, not an observed SYN-ACK send | `NOT_RUN` |
| U25 | Only a full USB OUT transfer is counted as sent | `NcmUsbBridge.send` checks transfer length; no fixture drove these outcomes at main | `PARTIAL` source only |
| U26 | Partial bulk transfer is a typed failure, not success | `NcmUsbBridge.send` has a typed partial path; no test drove it at main | `PARTIAL` source only |
| U27 | Missing peer MAC is distinguished from a USB send failure | No attempt-scoped peer-MAC counter | `NOT_RUN` |
| U28 | Accept from an old attachment cannot update a newer generation | `WiredStartupWatchdogTest.oldDataPathCallbackCannotPublishIntoANewerGeneration` covers a generic stale callback, not an accept socket | `PARTIAL` |
| U29 | FINAL snapshot racing with a callback remains immutable and rejects late events | No attempt recorder/seal race test | `NOT_RUN` |
| U30 | Concurrent counters have no lost updates | Atomic counter implementation exists; no stress/concurrency assertion | `NOT_RUN` |
| U31 | Diagnostics ON/OFF has equivalent USB/TUN behavior | No diagnostic on/off transport pair | `NOT_RUN` |
| U32 | Exported logs contain no PII, payload, or credential material | `check_public_tree.py` passes source-tree checks; runtime diagnostic export is not exercised here | `PARTIAL` |
| U33 | No StartSession means the wired watchdog is not armed | `WiredStartupWatchdogTest.startSessionArmsOnceAndExpiresOnce` | `PARTIAL` |
| U34 | Eight-second soft deadline reports only and leaves the 15-second deadline active | `WiredStartupWatchdogTest.softSnapshotDoesNotExpireTheHardWatchdog` | `PASS_LOCAL` watchdog state only |
| U35 | Fifteen-second hard deadline tears down exactly once | `WiredStartupWatchdogTest.startSessionArmsOnceAndExpiresOnce` | `PARTIAL` teardown ownership not integrated |
| U36 | ALT setup alone is distinguishable from MAIN setup/first frame | No ALT/MAIN lifecycle integration test | `NOT_RUN` |
| U37 | Active-without-MAIN at the hard deadline is observed separately | No stage provenance recorder | `NOT_RUN` |
| U38 | Old watchdog token cannot affect the current attempt | `WiredStartupWatchdogTest.oldSoftAndHardCallbacksCannotAffectTheNextAttempt` | `PASS_LOCAL` watchdog state only |
| U39 | Fail→Active before retry timer expiry skips the old Host retry | No Host retry ticket integration test | `NOT_RUN` |
| U40 | Recovery followed by a second loss permits one new retry | No Host retry ticket integration test | `NOT_RUN` |
| U41 | Controller-owned wired failure does not trigger a duplicate Host retry | Existing watchdog/retry tests do not cover Host ownership together | `NOT_RUN` |
| U42 | Menu/Activity owner transfer invalidates callbacks from the old Activity | `DisplayResizeCoordinatorTest` covers resize state, not Controller owner transfer | `NOT_RUN` |
| U43 | Same-generation USB mode recheck fires once at ten seconds | No delayed USB re-enumeration probe | `NOT_RUN` |
| U44 | Detach or USB generation change makes a ten-second probe a no-op | No delayed USB re-enumeration probe | `NOT_RUN` |
| U45 | A proposed recovery beyond the 15-second watchdog is found unreachable | No virtual-time bulk-OUT reachability test | `NOT_RUN` |
| U46 | Healthy first SYN/accept path sends no extra StartSession | No conditional StartSession recovery exists | `NOT_RUN` |
| U47 | Stale CSM/reconnect state cannot receive an extra StartSession | No conditional StartSession recovery exists | `NOT_RUN` |
| U48 | VPN address/listener binding is checked separately from accepted traffic | `CarPlayBonjourTest` covers discovery policy, not end-to-end VPN binding | `PARTIAL` |
| U49 | Old server accept cannot publish into a newer VPN attachment | `WiredStartupWatchdogTest.oldDataPathCallbackCannotPublishIntoANewerGeneration` | `PARTIAL` |
| U50 | Explicit `isInput=false` with a valid port starts no microphone capture | `CarPlayMediaEngineTest` and `MicrophonePacketizerTest` cover related paths; no complete request-policy table | `PARTIAL` |
| U51 | Siri/phone input preserves the current microphone route | `CarPlayMediaEngineTest`, `MicrophoneUplinkLifecycleTest` | `PARTIAL` local routing only |
| U52 | Default/compatibility voice-message input is bounded and can stop | `MicrophoneUplinkLifecycleTest` | `PARTIAL` |
| U53 | AudioRecord read/start failure recovery is bounded and leak-free | `MicrophoneUplinkLifecycleTest` | `PARTIAL` local lifecycle only |
| U54 | NAV/ALERT/PHONE/ASSISTANT routing, DSP eligibility, RTP and clock remain aligned | Audio codec/clock and DSP policy suites | `PARTIAL` cross-layer and device behavior not proved |
| U55 | Full→55% local Split→full retains the same AirPlay session/audio | `DisplayResizeCoordinatorTest` and `CarPlayDisplayScaleTest` cover state/geometry, not a live session | `PARTIAL` |
| U56 | Letterbox edge touches map through inverse display geometry | `CarPlayDisplayScaleTest`, `CarPlayUiScaleTest` | `PARTIAL` local geometry only |
| U57 | `/info` retains the single-area baseline | `AirPlayInfoPlistTest` | `PASS_LOCAL` plist unit scope only |
| U58 | Invalid safe area in a multi-area declaration is rejected/falls back | `SafeAreaTest`, `AirPlayInfoPlistTest` cover related data validation | `PARTIAL` no new multi-area model |
| U59 | Successful wire send alone is not treated as Dynamic ViewArea success | No dynamic coordinator exists | `NOT_RUN` |
| U60 | Device rejection/no response falls back to LOCAL/LEGACY | No dynamic protocol state machine exists | `NOT_RUN` |
| U61 | Two iPhone browser profiles do not share account cookies | `YoutubeDeviceProfileTest`, `YoutubeDeviceProfileAliasStoreTest` | `PARTIAL` local identity/storage key tests, no physical account switch |
| U62 | Surface recreation, rotation, IME and resume do not recreate the session | `DisplayResizeCoordinatorTest`, `YoutubeVisiblePaintResumeStateTest` | `PARTIAL` local state tests only |
| U63 | Codec output format change keeps crop/decoder/touch geometry aligned | `ScreenCodecTest`, `MediaCodecSupportTest`, `VideoDecodeQueueTest` | `PARTIAL` no integrated resized-session test |
| U64 | Missing CSD, timeout and seek have bounded decoder fallback | `AacStartupFallbackTest`, `MediaCodecSupportTest` | `PARTIAL` |
| U65 | DSP master OFF matches the existing PCM path | `DspOffLegacyPcmTest`, DSP pipeline unit tests | `PASS_LOCAL` current graph only |
| U66 | Seat L/R delay/trim/polarity is sample-accurate and click-free | No Seat Focus feature exists in fixed main | `NOT_RUN` |
| U67 | Advanced M/S handles mono/antiphase input without unstable level/power | Existing width tests are not the planned M/S module tests | `NOT_RUN` |
| U68 | Cabin correction rejects invalid measurement input safely | No Cabin Resonance Correction module | `NOT_RUN` |
| U69 | Harmonic Engine oversampling meets alias/headroom/latency gates | No Harmonic Engine module | `NOT_RUN` |
| U70 | Native DSP is finite at 44.1/48 kHz, mono/stereo and varied block sizes | `NativeDspProcessorTest`, `DspPipelineBenchmarkInstrumentedTest`; native host and API36.1 instrumentation passed | `PARTIAL` current DSP only, not future modules |
| U71 | Live graph updates plus Convolver preserve the playback clock | `DspLiveUpdateControllerTest`, `DspPcmPipelineTest` | `PARTIAL` current graph only |
| U72 | Removed BYD-only dependency is absent from source, manifests and APKs | `check_public_tree.py` checks credentials, not OEM dependency parity | `NOT_RUN` |
| U73 | Rolling back every new flag leaves other features unchanged | Existing profile-store tests cover current settings only | `PARTIAL` |
| U74 | Older profile survives APK update with settings, signature and ownership intact | Profile migration unit tests exist; no APK update/signature exercise | `PARTIAL` |
| U75 | TS10/K706 first connection/reconnect/Split/audio acceptance | No physical target attached | `NOT_RUN` |

## R-04 additional 50 cases

These IDs are retained separately from U01–U75. A related class name does not upgrade a row to a full pass unless the named boundary was exercised.

| ID | Additional requirement | Main evidence | Status at main |
|---|---|---|---|
| USB U-01 | StartSession sent but no request completion remains a USB-stage failure | `NcmUsbBridge` source; no stage-provenance recorder | `NOT_RUN` |
| USB U-02 | Async request timeout preserves the queued request without duplicate queue | `PersistentUsbReadRequestStateTest` | `PASS_LOCAL` state model only |
| USB U-03 | Unexpected endpoint completion is detected and cannot steal NCM ownership | `PersistentUsbReadRequestStateTest` | `PARTIAL` state model only |
| USB U-04 | Same USB node remains unchanged at ten seconds without duplicate permission prompts | No delayed same-node recheck test | `NOT_RUN` |
| USB U-05 | Unusual but valid CDC Union descriptor selects the safe function | `NcmFunctionDiscoveryTest` | `PARTIAL` descriptor fixtures only |
| USB U-06 | Exact 512-byte NTB without pad is delivered immediately | No NCM stream integration test | `NOT_RUN` at receive boundary |
| USB U-07 | Padded 512-byte NTB leaves the next NTH untouched | No NCM stream integration test | `NOT_RUN` |
| USB U-08 | Split reads, coalesced blocks, zeros and ZLP preserve bytes | `UsbMuxFrameDecoderTest` is USBMUX, not NCM | `NOT_RUN` for NCM |
| USB U-09 | Corrupt NTH/NDP bounded resync cannot synthesize a frame from garbage | Parser source has bounds checks; no malformed NDP/resync regression test | `NOT_RUN` |
| USB U-10 | USBMUX trailer and multiple frames across reads preserve bytes | `UsbMuxFrameDecoderTest` | `PARTIAL` no USB device request integration |
| USB U-11 | Queue false fallback leaves no stale queued request | No device-present queue-false proof | `NOT_RUN` |
| USB U-12 | NCM partial OUT is distinct from NotReady and Sent | `NcmUsbBridge.send` has typed outcomes; no fixture drove them at main | `PARTIAL` source only |
| IP N-01 | Valid IPv4 SYN to listener is classified by family | Current bridge is IPv6-oriented; no provenance classifier | `NOT_RUN` |
| IP N-02 | IPv6 SYN extension headers parse safely; fragments remain unknown | `EthernetIpv6CodecTest` covers portions | `PARTIAL` |
| IP N-03 | Truncated IPv6 packet is rejected by diagnostic classification | No diagnostic classifier | `NOT_RUN` |
| IP N-04 | VLAN-tagged Ethernet is distinct from unknown IP | No diagnostic classifier test | `NOT_RUN` |
| IP N-05 | Wrong link-local destination or port is counted separately | No diagnostic classifier | `NOT_RUN` |
| IP N-06 | TUN write failure does not set SYN write success | No attempt recorder | `NOT_RUN` |
| IP N-07 | SYN-ACK OUT NotReady is correlated with the same attempt | No attempt recorder | `NOT_RUN` |
| IP N-08 | USB OUT Sent is not reported as TCP ACK/accept | No attempt provenance report | `NOT_RUN` |
| IP N-09 | Old VPN accept loop cannot increment a new generation | Generic stale-callback watchdog unit only | `PARTIAL` |
| IP N-10 | Detach during read/write/status close does not deadlock | NCM blocked-request integration not present | `NOT_RUN` |
| Recovery R-01 | Recovered session skips old pending Host timer | No Host retry ticket test | `NOT_RUN` |
| Recovery R-02 | A second loss after recovery can schedule a new retry | No Host retry ticket test | `NOT_RUN` |
| Recovery R-03 | Controller-owned recovery causes zero Host duplicate retries | No Host/Controller ownership integration test | `NOT_RUN` |
| Recovery R-04 | Menu/Split Activity owner changes preserve the Controller session | Resize state tests only | `PARTIAL` |
| Recovery R-05 | AirPlay Active and MAIN first frame remain separate milestones | No per-attempt stage recorder | `NOT_RUN` |
| Recovery R-06 | ALT-only setup cannot claim MAIN rendered | No MAIN/ALT diagnostic matrix | `NOT_RUN` |
| Recovery R-07 | Late StartSession is suppressed when correct-port SYN is present | No late retry recovery feature | `NOT_RUN` |
| Recovery R-08 | Active session is protected against stale retry/reset callbacks | Watchdog generation unit only | `PARTIAL` |
| Audio A-01 | Explicit input false plus data port starts no mic | Mic lifecycle/engine tests cover related paths | `PARTIAL` |
| Audio A-02 | Voice-message input is accepted; music alone does not capture | `CarPlayMediaEngineTest`, `MicrophoneUplinkLifecycleTest` | `PARTIAL` |
| Audio A-03 | AudioRecord zero-read recovery is bounded and cancellable | `MicrophoneUplinkLifecycleTest` | `PARTIAL` |
| Audio A-04 | RTP, navigation gain and AEC clock stay aligned | Audio clock/codec unit tests | `PARTIAL` no physical A/V measurement |
| Audio A-05 | Buffer pacing and DSP clock changes preserve pitch | DSP/audio unit tests cover parts; no end-to-end pitch baseline | `NOT_RUN` |
| Video V-01 | 55:45 local geometry, resize and letterbox touch round trip | `CarPlayDisplayScaleTest`, `CarPlayUiScaleTest` | `PARTIAL` local geometry only |
| Video V-02 | `send=true` without phone acknowledgement is not acceptance | No ViewArea wire switching state machine | `NOT_RUN` |
| Video V-03 | Decoder format/crop and touch map remain synchronized on Surface change | Codec/scale tests are separate | `NOT_RUN` integration |
| Video V-04 | Last-picture bitmap/surface resources release on frame or timeout | Existing media lifecycle tests cover separate components | `PARTIAL` |
| Video V-05 | 20 full/Split cycles preserve Controller, Session and stream IDs | No same-session physical cycle test | `NOT_RUN` |
| DSP D-01 | New nodes OFF match old PCM golden output | `DspOffLegacyPcmTest` passes for current graph | `PARTIAL` for future nodes |
| DSP D-02 | Seat alignment fraction and graph latency remain invariant | No Seat Focus module | `NOT_RUN` |
| DSP D-03 | M/S mono/crossover behavior is stable and avoids duplicate width | No Advanced M/S module | `NOT_RUN` |
| DSP D-04 | Cabin import validates measurement/notch bounds | No cabin measurement module | `NOT_RUN` |
| DSP D-05 | Harmonic alias, oversampling delay and CPU budget are measured | No Harmonic Engine module | `NOT_RUN` |
| DSP D-06 | Streaming block path allocates zero and has no locks/I/O | Existing emulator benchmark measures current pipeline only | `PARTIAL` |
| General G-01 | Rollback each future flag while preserving unrelated settings | Current setting tests only | `PARTIAL` |
| General G-02 | BYD-only dependencies remain absent | No dedicated source/manifest/APK dependency gate | `NOT_RUN` |
| General G-03 | No new upstream revert or `0x4302` EndSession misuse | No dedicated regression gate | `NOT_RUN` |
| General G-04 | All variants, sanitizers, JNI/R8, ABI and API levels are covered | Debug/Benchmark, JNI and API36.1 emulator passed; sanitizer blocked locally | `PARTIAL` |

## Hardware and release gates

| Gate | Baseline evidence | Status |
|---|---|---|
| TS10/K706+iPhone USB, wired connection and recovery | No physical target attached | `NOT_RUN` |
| Siri/phone/voice-message, navigation, audio quality and A/V sync | Emulator only | `NOT_RUN` |
| Same-session 20-cycle Split and YouTube account isolation | Local unit/state tests only | `NOT_RUN` |
| CPU/PSS/GC/thermal on target head unit | Not measured | `NOT_RUN` |
| Installed-release rollback, signed update and persisted settings | Not exercised | `NOT_RUN` |
| Review A/B | Protocol/security and Android/product/performance reviews, independent fixes and rerun evidence | Internal protocol/fixture/CI review plus tests completed; independent second reviewer not run per prior user direction | `PARTIAL`; do not claim dual review or release readiness |
