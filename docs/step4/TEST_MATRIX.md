# STEP4 test matrix

Definitions and acceptance criteria are retained from v3 §8.3 and §R-04. This matrix records only evidence run from fixed main or PR-00 test fixtures; a fixture result does not establish physical USB or vehicle behavior. `PURE_JVM_PASS`, `ROBOLECTRIC_PASS` and `EMULATOR_PASS` are limited to the v3 evidence levels. Hardware-dependent cases stay `NOT_RUN` until the named device, iPhone/iOS, build SHA and logs are available.

## PR-00 executed checks

| Check | Result | Scope |
|---|---|---|
| Main shared/common unit + Robolectric baseline | `PASS` — 589 tests, 0 failures/errors/skips | main SHA `95ba398f`; mobile/automotive unit tasks `NO-SOURCE` |
| PR-00 fixture suite | `ROBOLECTRIC_PASS` — 15 tests, 0 failures/errors/skips | targeted `Step4TestFixturesTest` |
| Full shared/common unit + Robolectric rerun after fixtures | `PASS` — 468 + 136 = 604 tests, 0 failures/errors/skips | uncached rerun after adding PR-00 test code |
| Existing Android instrumentation baseline | `EMULATOR_PASS` — 16 methods, 0 failures/errors/skips | API 36.1 x86_64 emulator only |
| PFD socket-pair fixture | `EMULATOR_PASS` — 1 test; post-fixture instrumentation 17/17 | Validates Android PFD duplex I/O only, not VPN/TUN/NCM integration. |
| Existing native DSP C test | `PASS` unsanitized | MSYS2 UCRT GCC 15.2 |
| Existing host ASan/UBSan command | `BLOCKED` | local linker lacks `libasan`/`libubsan`; hosted CI has not yet run from this branch |
| Debug lint / Debug and Benchmark APKs | `PASS` | mobile and automotive; four existing warnings per app recorded in `BASELINE.md` |
| `check_public_tree.py` / JNI R8 mapping | `PASS` | both Benchmark mappings verified |
| Physical TS10/K706/iPhone gates | `NOT_RUN` | no physical target connected |

## Original v3 U01–U75

| ID(s) | Status | Evidence and limit |
|---|---|---|
| U01 | `FAIL` on fixed main | `mainBaselineBlocksOnA512ByteNtbWithoutTheOptionalPad`; current `drainFrames()` waits for byte 513. This is the PR-01 reproduction, not a passing acceptance case. |
| U02 | `ROBOLECTRIC_PASS` | `mainBaselineAcceptsA512ByteNtbWithTheOptionalPad`; this verifies the observed padded form. |
| U03 | `ROBOLECTRIC_PASS` | `ncmBridgeReassemblesSplitAndCoalescedNtbReads`; two ordinary NTBs are fragmented/coalesced and preserve order. Boundary corruption is separate. |
| U04–U06 | `NOT_RUN` | corrupt offsets, bounded resync and >1 MiB garbage/OOM limits remain untested. |
| U07 | `ROBOLECTRIC_PASS` (fixture only) | Scripted 32 KiB request-buffer capacity is recorded; no hardware queue success is implied. |
| U08 | `NOT_RUN` | Product 32→16→8→4→2 KiB fallback is not implemented or exercised. |
| U09 | `PURE_JVM_PASS` (state only) | `PersistentUsbReadRequestStateTest.timeoutKeepsTheSameRequestQueuedAndDoesNotRequestCancellation`; actual device timeout remains untested. |
| U10 | `NOT_RUN` | A separate status endpoint completion through the real requestWait owner is not exercised. |
| U11 | `ROBOLECTRIC_PASS` (fixture/state only) | Persistent request close state and scripted waiter wakeup are tested; no physical USB cancellation race is claimed. |
| U12 | `NOT_RUN` | Sync/async device behavior parity has not been measured. |
| U13–U20 | `NOT_RUN` | IPv4/IPv6 SYN and SYN-ACK input builders exist; EX-01 per-attempt family/destination classifier, malformed-header matrix and seeded fuzz are not implemented. |
| U21–U27 | `NOT_RUN` | The scripted stream fixture verifies explicit TUN outcomes; the production NCM↔TUN worker and NCM OUT correlation are not tested. |
| U28–U32 | `NOT_RUN` | Attempt-generation sealing, callback races, concurrent counters, ON/OFF behavior and PII-safe export are not implemented. |
| U33–U34 | `NOT_RUN` | Controller StartSession gating and soft-timeout no-teardown behavior are not exercised end-to-end. |
| U35 | `PURE_JVM_PASS` (watchdog state only) | `WiredStartupWatchdogTest` verifies a token expires once; the real 15 s handler/teardown path is not run. |
| U36–U37 | `NOT_RUN` | Production MAIN/ALT stage and Active/MAIN timing are not correlated. The fixture event recorder only keeps the slots distinct. |
| U38 | `PURE_JVM_PASS` (token state only) | `oldSoftAndHardCallbacksCannotAffectTheNextAttempt`; no controller callback race is claimed. |
| U39–U49 | `NOT_RUN` | Host recovered/second-loss, re-enumeration, delayed OUT, VPN listener-generation and device-present queue fallback cases remain open. |
| U50–U54 | `NOT_RUN` | The microphone fixture stores `input=false` and port values; Siri/phone/voice message, AudioRecord, RTP/AEC and route regression remain untested. |
| U55–U56 | `NOT_RUN` | Split geometry fixture is deterministic; same-session cycles, touch roundtrip and letterbox behavior are not tested. |
| U57 | `PURE_JVM_PASS` | `AirPlayInfoPlistTest.defaultDisplayIncludesFullViewAndSafeAreas` verifies a single default ViewArea. No iPhone response is implied. |
| U58–U64 | `NOT_RUN` | Multi-area validation/ACK, touch/profile isolation, Surface/codec and decoder recovery cases remain open. |
| U65 | `PURE_JVM_PASS` (normalization path only) | `DspOffLegacyPcmTest.disabledDspKeepsPcm16OnTheLegacyPassThroughPath` preserves the source buffer/offset/length; full playback/bit-exact all-format golden comparison remains pending. |
| U66–U71 | `NOT_RUN` | DSP extensions, native interaction matrix, all block sizes, real-time allocation and live-update A/V tests are not part of the main baseline. |
| U72 | `NOT_RUN` | BYD dependency/reference removal has not been implemented or checked by a dedicated guard. |
| U73–U74 | `NOT_RUN` | Feature-flag rollback and installed-profile/APK update tests are not run. |
| U75 | `NOT_RUN` | TS10 + iPhone baseline/acceptance hardware is absent. |

## v3 R-04 additional 50 cases

| ID(s) | Status | Evidence and limit |
|---|---|---|
| R4-U-01 | `NOT_RUN` | StartSession sent without USB read completion. |
| R4-U-02 | `PURE_JVM_PASS` (state/fixture only) | Same request remains queued after scripted timeout; no Android USB driver claim. |
| R4-U-03 | `NOT_RUN` | Status request completion demultiplexing against the real requestWait owner. |
| R4-U-04–U-05 | `NOT_RUN` | Same-node 10 s probe and unusual-but-valid descriptor selection. |
| R4-U-06 | `FAIL` on fixed main | Exact 512-byte unpadded block is not delivered. |
| R4-U-07 | `ROBOLECTRIC_PASS` | Padded 512-byte block followed by a valid NTB preserves both frames. |
| R4-U-08 | `ROBOLECTRIC_PASS` (partial) | Split/coalesced blocks pass; zero boundary bytes and ZLP are not included. |
| R4-U-09 | `NOT_RUN` | Invalid offsets and bounded false-positive-resistant resync. |
| R4-U-10 | `NOT_RUN` | USBMUX trailer and 1+N frames over three reads. |
| R4-U-11 | `NOT_RUN` | API 28+ queue-size fallback. |
| R4-U-12 | `ROBOLECTRIC_PASS` | `NcmUsbBridge.send()` fake transport distinguishes full, partial and NotReady; no vehicle result is implied. |
| R4-N-01–N-10 | `NOT_RUN` | IPv4/IPv6 TCP fixtures exist; per-attempt IP/TUN/TCP listener and stale-generation correlation are not implemented. |
| R4-R-01–R-08 | `NOT_RUN` | Host retry recovery/second-loss, controller ownership and startup-stage outcomes. |
| R4-A-01–A-05 | `NOT_RUN` | Microphone policy and audio/RTP/AEC device regressions. |
| R4-V-01–V-05 | `NOT_RUN` | Local/dynamic split, Surface ownership, frame-change and touch integration. |
| R4-D-01 | `PURE_JVM_PASS` (limited) | Existing DSP-off PCM normalization test; full legacy PCM golden matrix not run. |
| R4-D-02–D-06 | `NOT_RUN` | Seat/M-S/Cabin/Harmonic/real-time allocation acceptance. |
| R4-G-01–G-03 | `NOT_RUN` | Feature rollback, BYD guard and EndSession/revert regression. |
| R4-G-04 | `BLOCKED` locally / hosted pending | Debug lint/build, Benchmark build and JNI mapping passed; local ASan/UBSan linker is unavailable and CI from this new branch has not run. |

Every `NOT_RUN` remains open for its owning stage. The matrix is not a release sign-off.
