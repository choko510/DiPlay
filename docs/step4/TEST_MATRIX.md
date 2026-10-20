# STEP4 acceptance test matrix

The source requirements are in v3 §8.1–8.4 and §R-04. This matrix keeps every acceptance ID visible while separating current fixed-main evidence from future implementation and device gates.

## Evidence labels

| Label | Meaning |
|---|---|
| `PASS_LOCAL` | The named source-level/unit/native check ran locally and passed |
| `ROBOLECTRIC_PASS` | The production code path ran against scripted Android USB shadows; no physical USB claim |
| `EMULATOR_PASS` | Android emulator test or measurement passed; not TS10/K706/iPhone evidence |
| `PARTIAL` | Related current-main unit coverage exists but does not satisfy the complete acceptance case |
| `FIXTURE_ONLY` | A deterministic input or outcome fixture exists; the production behavior assertion remains for a later stage |
| `NOT_RUN` | Required behavior or hardware test has not run |
| `BLOCKED_ENV` | The host toolchain cannot execute the named sanitizer gate |

## PR-00 baseline verification

| Check | Result |
|---|---|
| `./gradlew tasks --all` | `PASS_LOCAL`; required Gradle tasks exist |
| `:shared:testDebugUnitTest :common:testDebugUnitTest` before PR-00 | `PASS_LOCAL`, 589 tests, 0 failures/errors/skips |
| Same unit task reports after the 8 PR-00 fixture tests | `PASS_LOCAL`, 461 shared + 136 common = 597, 0 failures/errors/skips |
| `scripts/test_dsp_native.sh` | `PASS_LOCAL` with MSYS2 UCRT GCC 15.2.0 |
| `scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV`: linker cannot find `libasan` or `libubsan`; hosted sanitizer check remains required |
| `scripts/run_android_instrumentation.sh` | `EMULATOR_PASS`, 16/16 methods, no skips/failures, API 36.1 x86_64 |
| DSP emulator benchmark | `EMULATOR_PASS`, 40 configurations × 3 repetitions at 44.1/48 kHz mono/stereo; maximum median production p99 3.42 ms for Convolver64k at 44.1 kHz stereo |
| Mobile/automotive Debug lint and Debug/Benchmark builds | `PASS_LOCAL`; both lint reports have 0 issues |
| `scripts/check_jni_mapping.py` / `scripts/check_public_tree.py` | Both `PASS_LOCAL` |
| Draft PR CI run #244 | `CI_ANDROID_PASS` for public-tree/native/sanitizer/shared/common unit checks; quick lane finished in 4m08 |
| Ready/full PR CI run #245 | `CI_ANDROID_PASS`; host check and API 35 x86_64 instrumentation both succeeded in 10m24 overall |
| PR-scoped Gradle cache write, run #247 | `CI_ANDROID_PASS`; same-repository PR cache write succeeded; fork/manual non-main runs remain read-only |
| Warm-cache docs-only draft run #248 | `CI_ANDROID_PASS`; 30/51 Gradle tasks restored from cache and the unit task phase completed in 25s |
| Ready/full run #250 | `CI_ANDROID_PASS`; host Gradle checks and API 35 x86_64 instrumentation passed, with 68/359 host tasks from cache |
| TS10/K706/iPhone wired, audio, split and release rollback checks | `NOT_RUN` |

The 8 new fixture tests verify the fixture contract, including a `NcmUsbBridge.recv()` call against a scripted Robolectric USB-IN request. The queue, completion and packet contents are explicit in the test; this is not physical USB evidence. The fake bulk-OUT path has no implicit success value.

## Acceptance IDs U01–U75

| ID | Required case | Fixed-main / PR-00 evidence and current status |
|---|---|---|
| U01 | 512-byte NTB boundary, no pad | `FIXTURE_ONLY`: exact 512-byte body is available; receiver delivery without waiting for byte 513 is not yet asserted |
| U02 | 512-byte NTB plus one zero short pad | `FIXTURE_ONLY`: padded input is available; one-byte consumption is not yet asserted |
| U03 | Two NTBs split across reads or coalesced | `FIXTURE_ONLY`: deterministic split/coalesced inputs exist; framing order is not yet asserted |
| U04 | Garbage followed by a valid NTB | `FIXTURE_ONLY`: inputs are representable; bounded production resynchronization is pending PR-01 |
| U05 | Invalid NTH/NDP offsets | `FIXTURE_ONLY`: corrupt offset fixture exists; bounded parser behavior is pending PR-01 |
| U06 | More than 1 MiB of corrupt input | `FIXTURE_ONLY`: oversized input can be constructed; finite receiver behavior is pending PR-01 |
| U07 | Successful 32 KiB USB queue | `ROBOLECTRIC_PASS`: scripted `NcmUsbBridge.recv()` queues the existing 32 KiB request and returns the configured frame |
| U08 | Multiple queue-false sizes | `FIXTURE_ONLY`: fake queue outcomes can express 32/16/8/4/2 KiB results; production fallback is conditional PR-02, not implemented |
| U09 | Already-queued request wait timeout | `ROBOLECTRIC_PASS`: timeout then completion uses one queued request; queue count remains one |
| U10 | Unexpected/different request completion | `ROBOLECTRIC_PASS` for request-identity mismatch; cross-endpoint `requestWait` dispatch is not physically exercised |
| U11 | Close racing a queued wait | `PARTIAL`: `PersistentUsbReadRequestStateTest.closeDuringWaitRequestsOneCancelAndOwnsOneFinalClose` covers state transitions, not an Android USB thread race |
| U12 | Sync and async read modes preserve results | `PARTIAL`: `NcmDiagnosticProfileTest` checks profile defaults; sync/async transport output parity is not run |
| U13 | IPv4 SYN to AirPlay listener | `NOT_RUN`: EX-01 packet classifier is not in main |
| U14 | IPv6 SYN with extension headers | `PARTIAL`: IPv6 codec tests exist; no bounded per-attempt packet classifier yet |
| U15 | IPv6 fragments and jumbo packets | `NOT_RUN`: diagnostic SYN classification is pending EX-01 |
| U16 | VLAN and short Ethernet packets | `NOT_RUN`: no EX-01 classifier acceptance test |
| U17 | Invalid IPv6 version/length rejection | `PARTIAL`: codec tests cover bounded parsing primitives, not EX-01 valid-TCP counts |
| U18 | AirPlay-port SYN-ACK direction | `NOT_RUN`: EX-01 provenance is pending |
| U19 | Wrong IPv4/IPv6 destination or port | `NOT_RUN`: EX-01 destination classifier is pending |
| U20 | 100,000 seeded random packet inputs | `NOT_RUN`: no classifier fuzz test |
| U21 | IPv6 SYN successfully written to TUN | `NOT_RUN`: no per-attempt TUN provenance test |
| U22 | TUN write exception | `NOT_RUN`: no EX-01 write-failure classifier test |
| U23 | Kernel SYN-ACK observation separated from USB OUT | `NOT_RUN`: EX-01 is not implemented |
| U24 | SYN-ACK bulk OUT returns NotReady, no extra retry | `NOT_RUN`: no per-attempt SYN-ACK outcome test |
| U25 | Full-byte SYN-ACK transfer is Sent | `NOT_RUN`: no EX-01 full-transfer accounting test |
| U26 | Partial bulk transfer is typed failure | `NOT_RUN`: no SYN-ACK partial-transfer test |
| U27 | Missing peer MAC is separate from OUT result | `NOT_RUN`: no EX-01 peer-MAC provenance test |
| U28 | Old attachment accept cannot update the new generation | `NOT_RUN`: no EX-01 server-generation test |
| U29 | Final snapshot races with callback | `NOT_RUN`: no immutable per-attempt final snapshot yet |
| U30 | Concurrent diagnostic increments lose no updates | `NOT_RUN`: no EX-01 concurrent-counter test |
| U31 | Diagnostic on/off leaves transport unchanged | `NOT_RUN`: no EX-01 side-effect comparison |
| U32 | Export contains no PII/payload/key | `PARTIAL`: `DiagnosticRedactorTest` and `DiagnosticExportStoreTest` cover existing export redaction, not future `WIRED_PATH_V1` output |
| U33 | Watchdog is not armed before StartSession | `PARTIAL`: `WiredStartupWatchdogTest` covers the state model; controller wiring is not re-tested here |
| U34 | 8-second soft watchdog is diagnostic only | `PARTIAL`: `softSnapshotDoesNotExpireTheHardWatchdog` verifies model behavior; no end-to-end teardown assertion |
| U35 | 15-second hard timeout tears down exactly once | `PARTIAL`: watchdog expiry token is single-use in the model; controller resource teardown is not exercised |
| U36 | ALT-only setup remains distinct from MAIN | `NOT_RUN`: EX-03 stage observation is pending |
| U37 | Active at 10s with MAIN absent at 15s | `NOT_RUN`: MAIN/ALT hardware timing is unavailable |
| U38 | Old watchdog token cannot affect current attempt | `PARTIAL`: `oldSoftAndHardCallbacksCannotAffectTheNextAttempt` passes as a pure state test |
| U39 | Recovery cancels a stale pending Host retry | `NOT_RUN`: EX-02 has not been implemented |
| U40 | Recovery followed by a second loss permits one new retry | `NOT_RUN`: EX-02 has not been implemented |
| U41 | Controller-owned wired failure avoids duplicate Host retry | `NOT_RUN`: EX-02 end-to-end owner gate is pending |
| U42 | Owner transfer/Activity recreation fences old callback | `PARTIAL`: `CarPlayRestartHandoffTest` covers handoff state; wired retry ownership remains untested |
| U43 | Same USB generation is probed once at 10s | `NOT_RUN`: conditional EX-04 gate is not run |
| U44 | Detach/re-enumeration before 10s makes probe a no-op | `NOT_RUN`: conditional EX-04 gate is not run |
| U45 | First OUT becomes ready after 15s | `FIXTURE_ONLY`: virtual scheduler is available; EX-03/EX-05 acceptance is pending |
| U46 | Healthy initial SYN/accept does not add StartSession | `NOT_RUN`: conditional EX-05 gate is not run |
| U47 | Stale CSM/reconnect is never retransmitted | `NOT_RUN`: conditional EX-05 gate is not run |
| U48 | IPv6 bind/scope and destination match | `NOT_RUN`: conditional EX-07 gate is not run |
| U49 | Old server accept cannot enter a new attachment | `NOT_RUN`: conditional EX-07 gate is not run |
| U50 | `input=false` plus valid port starts no capture | `PARTIAL`: microphone lifecycle tests exist; PR-04 request policy is not yet covered |
| U51 | Siri/Phone microphone path | `PARTIAL`: current engine/lifecycle unit coverage exists; device acceptance is pending PR-04 |
| U52 | Default/compat voice-message request | `NOT_RUN`: additional capture request policy is pending PR-04 |
| U53 | AudioRecord/codec failure recovery is bounded | `PARTIAL`: `MicrophoneUplinkLifecycleTest` covers lifecycle outcomes; Siri/phone failure matrix is not run |
| U54 | NAV/ALERT/PHONE/ASSISTANT routing and DSP policy | `PARTIAL`: current audio-policy, stream-classification and clock unit tests pass; no target audio acceptance |
| U55 | Full↔55% Split 20 cycles, same session/audio | `PARTIAL`: `SplitLayoutStateTest` and `DisplayResizeCoordinatorTest` test model state; no same-session cycle run |
| U56 | Letterbox edge touch inverse transform | `PARTIAL`: scale/mapping unit tests exist; split letterbox geometry is not accepted on device |
| U57 | Legacy one-area `/info` output | `PASS_LOCAL`: `AirPlayInfoPlistTest` verifies the fixed-main one-area default |
| U58 | Invalid two-area/safe-area declaration | `NOT_RUN`: multi-area model and validation are pending PR-05 |
| U59 | Command send alone is not Dynamic success | `NOT_RUN`: Dynamic ViewArea coordinator is not in main |
| U60 | Phone rejection/timeout returns to LOCAL/LEGACY | `NOT_RUN`: protocol fallback is not implemented |
| U61 | Two iPhone profiles do not mix cookies/logins | `PARTIAL`: profile/alias unit tests pass; two-device browser acceptance is not run |
| U62 | Surface/rotation/IME recovery keeps intended session | `PARTIAL`: lifecycle tests exist; emulator/hardware lifecycle matrix is not complete |
| U63 | Codec output-format change preserves crop/decoder/touch geometry | `PARTIAL`: media-sink/decoder queue tests exist; joined output geometry is not accepted |
| U64 | Missing CSD/timeout/seek uses bounded decoder-only fallback | `PARTIAL`: decoder queue tests exist; codec-specific fallback matrix is not run |
| U65 | DSP master OFF matches legacy PCM | `EMULATOR_PASS`: `DspOffLegacyPcmTest` and the existing DSP instrumentation/native baseline pass on the local emulator/host |
| U66 | Seat delay/trim/polarity and click-free updates | `NOT_RUN`: Seat Focus is not in fixed main |
| U67 | Advanced M/S mono/antiphase/headroom | `PARTIAL`: legacy stereo-width tests exist; the new Advanced M/S module is not in main |
| U68 | Cabin measurement rejection/safe-fail | `NOT_RUN`: measured calibration import is not implemented |
| U69 | Harmonic oversampling/alias/headroom/latency | `NOT_RUN`: Harmonic Engine is not in main |
| U70 | 44.1/48 kHz, mono/stereo, specified block sizes | `PARTIAL`: local emulator benchmarks cover 40 graphs at 512 frames and a 1537-frame allocation check; the full block-size matrix is not complete |
| U71 | Live updates/Convolver preserve A/V clock | `PARTIAL`: existing DSP live-update and latency tests pass; target playback clock/AV acceptance is pending |
| U72 | Removed BYD-only code has no manifest/reference leak | `NOT_RUN`: no PR-00 ownership/manifest acceptance test is claimed |
| U73 | Feature rollback leaves unrelated features unchanged | `NOT_RUN`: PR-08 rollback rehearsal is pending |
| U74 | Old profiles/APK update preserve settings and signing | `PARTIAL`: schema migration tests and benchmark JNI mapping pass; install/update/signing rehearsal is not run |
| U75 | TS10 initial/reconnect/Split/audio acceptance | `NOT_RUN`: physical TS10/K706/iPhone hardware is unavailable in this baseline |

The fixed fixtures are synthetic and deterministic. None of `FIXTURE_ONLY`, `PARTIAL`, or emulator results is a `HARDWARE_PASS`; conditional features remain off until their stage gate is satisfied.
