# STEP4 verification matrix — fixed main / PR-00

## Evidence labels

`PURE_JVM_PASS`, `ROBOLECTRIC_PASS`, `CI_ANDROID_PASS`, `EMULATOR_PASS`, and `HARDWARE_PASS` apply only to the listed scope. `PARTIAL` means the current test covers only one layer or a subset of the stated acceptance case. `FIXTURE_ONLY` means deterministic input data exists but production behavior was not exercised. `KNOWN_DEFECT_REPRODUCED` records an observed baseline failure. `NOT_RUN` and `CONDITIONAL` do not count as passes.

## PR-00 test runs

| Run | Result |
|---|---|
| Fixed-main shared/common baseline | `PASS_LOCAL`: 453 + 136 = 589 tests; 0 failures, errors, or skips |
| PR-00 shared/common suite | `PASS_LOCAL`: 464 + 136 = 600 tests; 0 failures, errors, or skips; all eleven PR-00 fixture tests run |
| Connected instrumentation | `PASS_EMULATOR`: 16 tests on Medium Phone API 36.1 x86_64; 0 failures/skips |
| Native host DSP C | `PASS_LOCAL` unsanitized; local sanitizer link `BLOCKED_ENV` (`libasan`/`libubsan` unavailable) |
| Fixed-main hosted workflow #101 | `PASS_CI`: exact SHA `95ba398`; host `check` and Android instrumentation jobs succeeded |
| Mobile/automotive lint, Debug, Benchmark | `PASS_LOCAL`; four baseline lint warnings per app, no errors |
| Benchmark capture | `PASS_EMULATOR`: 120 run-scoped records, 40 case/rate/channel groups, three runs × 256 blocks; not hardware evidence |

## Original U01–U75 acceptance matrix

| ID | Scenario | Current main evidence and status |
|---|---|---|
| U01 | NTB16 declared length exactly 512 bytes, pad absent | `KNOWN_DEFECT_REPRODUCED`: PR-00 fake bridge receives no Ethernet frame before read timeout; PR-01 must make this deliver |
| U02 | NTB16 exactly 512 bytes with zero pad | `ROBOLECTRIC_PASS`: `Step4TestFixturesTest.paddedExactPacketBoundaryIsDeliveredAndMissingPadIsCharacterized` |
| U03 | Split reads and coalesced NTBs | `ROBOLECTRIC_PASS`: `Step4TestFixturesTest.synchronousBulkInReassemblesSplitAndCoalescedNtbBlocks` |
| U04 | Corrupt bytes followed by a valid NTB, bounded resync | `NOT_RUN`: resynchronization is not implemented in main |
| U05 | Invalid NTH/NDP offsets | `PARTIAL`: `Ntb16CodecTest` covers valid parsing and size limits; malformed offset integration cases remain unrun |
| U06 | More than 1 MiB of corrupt data | `NOT_RUN`: no bounded receive resync exists in main |
| U07 | Async queue of existing 32 KiB buffer | `ROBOLECTRIC_PASS`: PR-00 checks the queued direct-buffer capacity is 32768 bytes |
| U08 | Queue false and bounded 16/8/4/2 KiB fallback | `CONDITIONAL / NOT_RUN`: PR-02 fallback is absent pending API28+ evidence |
| U09 | Timeout while request remains queued | `ROBOLECTRIC_PASS`: PR-00 times out then completes the same request with one queue |
| U10 | Unexpected requestWait completion | `ROBOLECTRIC_PASS`: unexpected request fails the active NCM transport rather than being consumed as data |
| U11 | Close concurrent with requestWait | `ROBOLECTRIC_PASS`: `Step4TestFixturesTest.closeWhileRequestWaitIsBlockedCancelsAndClosesTheOwnedRequest` exercises the bridge with a scripted blocked waiter; physical USB close remains unverified |
| U12 | Sync and async read modes | `ROBOLECTRIC_PASS`: `syncAndAsyncBulkInProfilesDeliverTheSameFrame` |
| U13 | IPv4 SYN to the actual AirPlay listener | `FIXTURE_ONLY`: synthetic IPv4/TCP frame factory exists; no production classifier or listener correlation |
| U14 | IPv6 SYN with extension header | `FIXTURE_ONLY`: basic IPv6/TCP packet exists; extension-header classification is not implemented |
| U15 | IPv6 fragment and jumbo input | `NOT_RUN` |
| U16 | VLAN and short Ethernet input | `NOT_RUN` |
| U17 | Invalid IPv6 version/length | `PARTIAL`: `EthernetIpv6CodecTest` covers main-code parsing; no STEP4 classifier coverage |
| U18 | SYN-ACK from the AirPlay source port | `FIXTURE_ONLY`: a synthetic SYN-ACK packet exists; no production counter exists |
| U19 | Wrong port/address produces no AirPlay SYN classification | `NOT_RUN` |
| U20 | 100,000 seeded random packet inputs | `NOT_RUN` |
| U21 | Inbound IPv6 SYN and successful TUN write | `FIXTURE_ONLY`: JVM simulated TUN pipe proves byte exchange only |
| U22 | TUN write exception classification | `NOT_RUN` |
| U23 | Kernel SYN-ACK observation separated from USB OUT | `FIXTURE_ONLY`: event sequence data exists; no production recorder exists |
| U24 | SYN-ACK bulk OUT returns NotReady without retry | `ROBOLECTRIC_PASS`: direct `NcmUsbBridge.send` call preserves typed `NotReady`; no integrated TUN retry path is claimed |
| U25 | Full OUT transfer returns Sent | `ROBOLECTRIC_PASS`: fake transfer returns the full NTB length |
| U26 | Partial OUT transfer is Failed, not Sent | `ROBOLECTRIC_PASS`: fake transfer returns a short count and main returns `NcmSendResult.Failed` |
| U27 | Missing peer MAC prevents/records outbound send | `NOT_RUN` |
| U28 | Old attachment callback cannot mutate new generation | `PARTIAL`: `WiredStartupWatchdogTest.oldDataPathCallbackCannotPublishIntoANewerGeneration`; EX-01 callback provenance not implemented |
| U29 | Callback races final snapshot | `NOT_RUN`: per-attempt immutable recorder not implemented |
| U30 | Concurrent counter updates lose no increments | `NOT_RUN`: no STEP4 recorder stress test |
| U31 | Diagnostics ON/OFF has equal transport behavior | `NOT_RUN`: wired path diagnostics are not implemented |
| U32 | Exported logs contain no PII/payload | `PARTIAL`: existing `ConnectionTraceStoreTest` checks bounded/redacted trace records; no EX-01 report exists |
| U33 | No StartSession means watchdog is not armed | `PARTIAL`: `WiredStartupWatchdogTest.startSessionArmsOnceAndExpiresOnce` covers arming, not every Controller call path |
| U34 | 8-second soft watchdog does not tear down | `PURE_JVM_PASS`: `WiredStartupWatchdogTest.softSnapshotDoesNotExpireTheHardWatchdog` |
| U35 | 15-second hard timeout tears down exactly once | `PURE_JVM_PASS`: `WiredStartupWatchdogTest.startSessionArmsOnceAndExpiresOnce` |
| U36 | ALT-only setup does not prove MAIN | `NOT_RUN` |
| U37 | Active at 10 s, MAIN absent at 15 s | `NOT_RUN`: stage-specific watchdog observation not implemented |
| U38 | Old watchdog token cannot affect current attempt | `PURE_JVM_PASS`: `WiredStartupWatchdogTest.oldSoftAndHardCallbacksCannotAffectTheNextAttempt` |
| U39 | Failed → active → old retry timer fires | `NOT_RUN`: Host recovered-session race is not covered by main tests |
| U40 | Recovery followed by a second loss permits one retry | `FIXTURE_ONLY`: synthetic event list exists; production owner policy test is not present |
| U41 | Controller-owned failure does not trigger duplicate Host retry | `NOT_RUN` |
| U42 | Owner transfer / Activity recreation rejects old callback | `PARTIAL`: Controller data-path generation test exists; full Activity owner-transfer simulation does not |
| U43 | Same-node, same-generation probe after 10 seconds | `NOT_RUN`: EX-04 is conditional and not implemented |
| U44 | Detach or USB update before 10 seconds makes probe a no-op | `NOT_RUN` |
| U45 | First OUT arrives after the 15-second hard deadline | `NOT_RUN`: EX-05 deadline reachability remains unmeasured |
| U46 | Healthy first SYN/accept does not send another StartSession | `NOT_RUN` |
| U47 | Stale CSM/reconnect state suppresses invalid resend | `NOT_RUN` |
| U48 | VPN local bind address matches destination | `PARTIAL`: `WirelessHostAddressTest` covers wireless address selection, not wired IPv6 scope/bind |
| U49 | Old server accept cannot attach to new VPN generation | `NOT_RUN` |
| U50 | `isInput=false` with valid port starts no recording | `PARTIAL`: plist microphone advertisement is tested; record-start policy is not |
| U51 | Siri/Phone uses existing microphone route | `PARTIAL`: packetizer/lifecycle units pass; physical routing is `NOT_RUN` |
| U52 | Default/compatible voice-message request policy | `NOT_RUN` |
| U53 | AudioRecord exception/zero-read recovery is bounded | `PARTIAL`: microphone lifecycle close-before-start is tested; AudioRecord error recovery is not |
| U54 | NAV/ALERT/PHONE/ASSISTANT routing, DSP, RTP clock | `PARTIAL`: `AudioStreamClassificationTest`, `DspStreamPolicyTest`, and packetizer/clock unit coverage; no vehicle routing test |
| U55 | Full → 55% → full retains the same CarPlay session/audio | `PARTIAL`: split state/scale math exists; same live session continuity is not tested |
| U56 | Letterbox/touch coordinate round trip | `PURE_JVM_PASS` for the current scale/area geometry model; physical touch alignment is `NOT_RUN` |
| U57 | Default `/info` remains a single area | `PURE_JVM_PASS`: `AirPlayInfoPlistTest.defaultDisplayIncludesFullViewAndSafeAreas` |
| U58 | Invalid two-area/safe-area input rejects or falls back | `PARTIAL`: safe-area validation is tested; no dynamic two-area protocol is implemented |
| U59 | Wire send success alone is not device acceptance | `NOT_RUN`: no dynamic ViewArea sender exists in main |
| U60 | Device rejection/no response falls back finitely | `NOT_RUN`: dynamic protocol coordinator is not implemented |
| U61 | Separate Google profiles do not mix across split lifetimes | `PARTIAL`: `YoutubeDeviceProfileAliasStoreTest` covers profile alias retention, not actual cookie/account isolation |
| U62 | Surface recreation/rotation/IME preserves intended session | `PARTIAL`: `AndroidMediaSinkStateTest` covers state restoration; full UI/IME lifecycle is not covered |
| U63 | Codec output format change keeps crop/decoder/touch aligned | `PARTIAL`: queue/capability/media-sink state tests exist; no full format-change/touch integration test |
| U64 | CSD loss/timeout/seek uses finite decoder-only fallback | `PARTIAL`: `VideoDecodeQueueTest` covers bounded queue/recovery cases, not every CSD/seek scenario |
| U65 | DSP master OFF matches legacy PCM path | `PURE_JVM_PASS`: `DspOffLegacyPcmTest.disabledDspKeepsPcm16OnTheLegacyPassThroughPath` |
| U66 | Seat alignment delay/trim/polarity | `NOT_RUN`: DSP-01 is not in main |
| U67 | Advanced M/S mono/antiphase/crossover | `PARTIAL`: existing width/mono-bass behavior is covered; Advanced M/S is not implemented |
| U68 | Measured cabin correction safely rejects invalid measurement | `NOT_RUN`: DSP-03 measurement importer is not in main |
| U69 | Harmonic engine alias/headroom/latency | `NOT_RUN`: DSP-04 is not in main |
| U70 | Native DSP at 44.1/48 kHz, mono/stereo, block sizes | `PARTIAL`: host native C and emulator DSP benchmarks pass; the full v3 block-size matrix has not been executed |
| U71 | Live update and Convolver preserve A/V clock | `PARTIAL`: `DspLiveUpdateControllerTest` covers prepare/defer/retire; target A/V synchronization is `NOT_RUN` |
| U72 | Removed BYD-only code stays absent | `NOT_RUN`: no PR-00 OEM reintroduction occurs; EX-08/PR-08 source-tree audit remains pending |
| U73 | Rollback returns every new feature to default | `PARTIAL`: existing settings/profile defaults are tested; PR-00 adds no new production flags |
| U74 | Older profile/APK update preserves state/signature | `PARTIAL`: profile schema unit tests exist; installed APK update/signature test is `NOT_RUN` |
| U75 | TS10 initial/reconnect/split/voice acceptance | `NOT_RUN`: no TS10/K706/iPhone connected |

## R-04 additional cases (50)

| ID | Scenario | Current evidence/status |
|---|---|---|
| U-01 | `0x4301` sent but USB read queue has no completion | `NOT_RUN` |
| U-02 | Persistent request timeout keeps the same queued request | `ROBOLECTRIC_PASS`: PR-00 async request fixture |
| U-03 | Unexpected status endpoint completion is not stolen | `NOT_APPLICABLE` to fixed main: status uses `bulkTransfer`, not a second `UsbRequest`; do not add another `requestWait` owner |
| U-04 | Same-node USB mode remains unchanged at 10 seconds | `NOT_RUN` |
| U-05 | Unusual but valid CDC Union/interface descriptors | `NOT_RUN` |
| U-06 | Complete 512-byte NTB without optional pad | `KNOWN_DEFECT_REPRODUCED` |
| U-07 | 512-byte NTB plus pad followed by another valid NTB | `PARTIAL`: pad and coalescing are covered separately |
| U-08 | Split/coalesced reads, boundary zeros, ZLP | `PARTIAL`: split/coalesced frames pass; zero/ZLP matrix not run |
| U-09 | Invalid NTH/NDP bounded resync without false frame | `NOT_RUN` |
| U-10 | USBMUX trailer and multiple frames across three reads | `PARTIAL`: `UsbMuxFrameDecoderTest` covers split and coalesced framing; exact three-read trailer case not run |
| U-11 | Queue-false size fallback 32/16/8/4/2 KiB | `CONDITIONAL / NOT_RUN`: fallback is not implemented |
| U-12 | `NcmSendResult` partial vs NotReady | `ROBOLECTRIC_PASS`: PR-00 typed send result tests |
| N-01 | IPv4 SYN to actual AirPlay listener port | `FIXTURE_ONLY` |
| N-02 | IPv6 SYN with extension header / fragment | `FIXTURE_ONLY` basic IPv6 only; extension/fragment classification `NOT_RUN` |
| N-03 | Truncated IPv6 rejection without transport change | `NOT_RUN` |
| N-04 | VLAN Ethernet classification | `NOT_RUN` |
| N-05 | Wrong link-local destination or TCP port | `NOT_RUN` |
| N-06 | TUN write exception leaves success false | `NOT_RUN` |
| N-07 | SYN-ACK then NCM NotReady is correlated per attempt | `FIXTURE_ONLY` synthetic milestone list only |
| N-08 | SYN-ACK sent without ACK/accept is not socket success | `NOT_RUN` |
| N-09 | Old `CarPlayVpnService` accept loop cannot mutate new attach | `NOT_RUN` |
| N-10 | Detach while read/write/status threads close | `PARTIAL`: PR-00 closes a bridge while fake requestWait is blocked; multi-worker TUN/status teardown and physical detach stress remain `NOT_RUN` |
| R-01 | Host retry timer fires after successful recovery | `NOT_RUN` |
| R-02 | Recovery then second loss starts one legitimate retry | `FIXTURE_ONLY` synthetic event sequence |
| R-03 | Controller-owned failure has no duplicate Host retry | `NOT_RUN` |
| R-04 | Menu/Split Activity owner changes with background Controller | `NOT_RUN` |
| R-05 | RTSP Active before delayed MAIN setup | `NOT_RUN` |
| R-06 | ALT-only setup is not treated as first MAIN frame | `NOT_RUN` |
| R-07 | Healthy SYN suppresses late StartSession resend | `NOT_RUN` |
| R-08 | Active session survives stale retry/late USB reset | `PARTIAL`: generation/state tests exist, integrated race scenario not run |
| A-01 | `isInput=false` suppresses recording | `PARTIAL`: microphone plist advertisement only |
| A-02 | Default/compat voice-message input policy | `NOT_RUN` |
| A-03 | AudioRecord zero-read/exception recovery is bounded | `PARTIAL`: lifecycle test only |
| A-04 | 16/48 kHz packetizer, nav gain, and AEC clock align | `PARTIAL`: packetizer and clock tests exist separately |
| A-05 | Buffer pacing/upsampling/DSP clock does not alter pitch | `PARTIAL`: component tests only; target audio not measured |
| V-01 | LOCAL 55:45 coordinate/letterbox round trip | `PURE_JVM_PASS` geometry only |
| V-02 | Dynamic send without acknowledgement remains unconfirmed | `NOT_RUN`: no dynamic sender |
| V-03 | Output format change keeps crop and touch transform synchronized | `PARTIAL`: separate decoder/UI tests |
| V-04 | Last-frame/Surface cleanup after frame or timeout | `NOT_RUN` |
| V-05 | 20 rapid full/Split cycles retain same IDs | `NOT_RUN` |
| D-01 | DSP OFF matches old PCM golden | `PURE_JVM_PASS` |
| D-02 | Seat fractional delay/graph latency | `NOT_RUN` |
| D-03 | Advanced M/S mono/crossover/width | `NOT_RUN` |
| D-04 | Cabin measurement import validates bounds | `NOT_RUN` |
| D-05 | Harmonic anti-aliasing, oversampling delay, CPU budget | `NOT_RUN` |
| D-06 | Audio processing has no dynamic allocations/locks/I/O | `NOT_RUN` for the full v3 instrumented audit |
| G-01 | All new flags roll back to default and preserve profiles | `PARTIAL`: no PR-00 production flags introduced |
| G-02 | BYD-only packages remain absent | `NOT_RUN`: requires EX-08/PR-08 source audit |
| G-03 | No old revert or `0x4302` EndSession misuse | `NOT_RUN`: register decision prohibits `0x4302`; full audit pending |
| G-04 | All Android variants, native/sanitizer, mapping | `PARTIAL`: hosted main run #101 and local Debug/Benchmark/JNI pass; Release APK and target ABI/device coverage remain outstanding |

## Fixture provenance

The new `Step4TestFixtures` values are synthetic and deterministic. Their packet checksums are zero, the TUN substitute is a pair of JVM pipes, and the USB objects are Robolectric shadows. These fixtures cannot establish a physical USB descriptor, VPN/TUN driver behavior, TCP kernel response, iPhone protocol acceptance, AudioRecord route, Surface behavior, or TS10/K706 performance.
