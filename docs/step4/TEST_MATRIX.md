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
| U-10 | Recovered session followed by a second loss | `Step4VirtualScheduler` | `FIXTURE_ONLY`; EX-02 needs a production retry-policy regression test |
| U-11 | Explicitly denied microphone input, Siri/phone/default voice input | `MicrophoneRequestFixture` | `FIXTURE_ONLY`; PR-04 must test request classification and lifecycle |
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
| EX02-02 | Stale restart/connection/owner epochs, live session, shutdown, menu plus pending resize, handshake reset, and controller-owned wired recovery cannot trigger the delayed host retry | `ReconnectAfterLossGateTest`, `CarPlayHostActivity` gate wiring | `PASS_LOCAL` policy test; full Activity/owner-handoff simulation `NOT_RUN` |

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
| Hardware | No TS10/K706/iPhone was attached | `NOT_RUN`; not a pass |

The remaining acceptance IDs in specification sections 8.3 and R-04 stay open for their dependent PR/EX stages. EX-01 has a source-level implementation and fixture coverage; it is not device-validated or release-ready.
