# STEP4 Test Matrix

Statuses distinguish a passing existing test from a fixture that only prepares an input and from physical-device evidence. A fixture-construction test is not proof of USB, TUN, RTSP, audio, or display behavior on a phone/head unit.

## PR-00 baseline fixtures

| Evidence ID | Scenario | Main test/evidence | Status at PR-00 |
|---|---|---|---|
| U-01 | NTB16 single datagram, sequence, alignment, maximum size | `Ntb16CodecTest` | `PASS_LOCAL` |
| U-02 | Split NTB reads and two coalesced NTBs through `NcmUsbBridge.recv()` | `NcmUsbBridgeFixtureTest.scriptedUsbRequestReadsSplitAndCoalescedNtb16Blocks` | `PASS_ROBOLECTRIC` |
| U-03 | Exact 512-byte block with optional zero pad | `NcmUsbBridgeFixtureTest.paddedExactPacketBoundaryBlockIsDelivered` | `PASS_ROBOLECTRIC` |
| U-04 | Exact 512-byte block without pad | `NcmUsbBridgeFixtureTest.missingExactPacketBoundaryPadIsCharacterizedForPr01` | `KNOWN_DEFECT_REPRODUCED`: the current bridge waits for a byte that was not sent; PR-01 must change the assertion to frame delivery |
| U-05 | Persistent request queue true/false, timeout, null and unexpected completion | `PersistentUsbReadRequestStateTest`, `NcmUsbBridgeFixtureTest` | `PASS_PURE_JVM` / `PASS_ROBOLECTRIC` |
| U-06 | Existing 32 KiB buffer on queue failure | `NcmUsbBridgeFixtureTest.fakeCapturesTheExistingThirtyTwoKiBReadQueueContract` | `PASS_ROBOLECTRIC`; no fallback policy is adopted here |
| U-07 | NCM send `NotReady`, full `Sent`, and partial failure remain distinct | `NcmUsbBridgeFixtureTest.fakeBulkOutDistinguishesNotReadySentAndPartialFailure` | `PASS_ROBOLECTRIC` |
| U-08 | Truncated NTH, invalid NDP, NTB32 candidate, >1 MiB garbage | `Step4TestFixturesTest`, existing `Ntb16CodecTest` | `FIXTURE_AND_CODEC_ONLY`; stream resynchronization is PR-01 work |
| U-09 | IPv4 SYN, IPv6 SYN/SYN-ACK, TUN no-reply, accept-only, RTSP-active-only, MAIN/ALT-only timelines | `Step4TestFixtures` | `FIXTURE_ONLY`; EX-01 adds production-path observations |
| U-10 | Recovered session followed by a second loss | `Step4VirtualScheduler` | `FIXTURE_ONLY`; EX-02 needs a production retry-policy regression test |
| U-11 | Explicitly denied microphone input, Siri/phone/default voice input | `MicrophoneRequestFixture` | `FIXTURE_ONLY`; PR-04 must test request classification and lifecycle |
| U-12 | 55% local Split geometry, PCM16 samples, encoded-video timing/crop | `Step4TestFixturesTest` | `FIXTURE_ONLY`; PR-06/07 add production-path checks |
| U-13 | Legacy DSP OFF, profile migration/future-schema protection, native DSP buffers | Existing DSP unit tests | `PASS_LOCAL` on base SHA; physical audio/A/V remains `NOT_RUN` |

## Main baseline runs

| Level | Main-SHA result | Limit |
|---|---|---|
| JVM/Robolectric, initial main | 453 shared + 136 common; zero failures/errors/skips | Does not exercise physical USB, VPN/TUN, or iPhone communication |
| JVM/Robolectric, after PR-00 fixtures | 464 shared + 136 common; zero failures/errors/skips | Includes 7 `NcmUsbBridgeFixtureTest` and 4 `Step4TestFixturesTest` cases |
| Android instrumentation | 16/16 on API 36.1 x86_64 AVD | Emulator is not TS10/K706/iPhone |
| Native C | Host unsanitized suite passed | Host ASan/UBSan linking unavailable in MSYS2 |
| App lint/build | Both app Debug/Benchmark builds and lints passed | No release signing/update exercise |
| Hardware | No TS10/K706/iPhone was attached | `NOT_RUN`; not a pass |

The remaining acceptance IDs in specification sections 8.3 and R-04 stay open for their dependent PR/EX stages.
