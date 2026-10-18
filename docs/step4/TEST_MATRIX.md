# STEP4 acceptance test matrix

Source IDs: plan Part II §8.3 (`U01`–`U75`) and Part III §R-04. The matrix preserves every named case. Status describes current evidence on frozen main plus PR-00 fixtures; it does not imply hardware behavior. `OPEN` means the required test still has to be implemented/run in its dependent phase. `PARTIAL` means a related component test exists but does not prove the full acceptance condition. `FIXTURE_ONLY` validates the test double, not production behavior.

## Original acceptance set U01–U75

| ID | Case | Current evidence / status |
|---|---|---|
| U01 | Exact 512-byte NTB without pad | `KNOWN_GAP`: `drainFrames()` waits for the optional byte; PR-01 |
| U02 | Exact 512-byte NTB with one zero pad | `PARTIAL`: fixture builds both forms; bridge RX integration remains PR-01 |
| U03 | Two NTBs split/coalesced, order retained | `PASS_LOCAL`: synchronous bridge test splits the first block and coalesces the second |
| U04 | Garbage followed by valid NTB, bounded resync | `OPEN`: PR-01 |
| U05 | Invalid NTH/NDP offsets without OOB or loop | `OPEN`: malformed offset matrix and stream resync remain PR-01 |
| U06 | More than 1 MiB corrupt input without OOM | `OPEN`: PR-01 |
| U07 | 32 KiB queue success and request reuse | `PARTIAL`: existing persistent-state tests; fake-request bridge coverage does not yet assert 32 KiB |
| U08 | Queue false size fallback 16/8/4/2 KiB | `OPEN/CONDITIONAL`: API28+ device-present reproduction required, PR-02 |
| U09 | Already queued request after timeout, no requeue | `PASS_LOCAL`: `PersistentUsbReadRequestStateTest` and `Step4FixtureFactoryTest.ncmAsyncReadReusesQueuedRequestAfterTimeout` |
| U10 | `requestWait` completion for another endpoint | `PASS_LOCAL`: unexpected completion is rejected and the queued request is cancelled on close; EX-06 shared reaper remains conditional |
| U11 | Concurrent close and `requestWait` | `PARTIAL`: state-machine close tests; real bridge race remains open |
| U12 | Sync/async diagnostic modes return consistent reads | `PARTIAL`: both modes exist; common bridge fixture comparison remains open |
| U13 | IPv4 SYN to listener port counted once | `OPEN`: EX-01 |
| U14 | IPv6 SYN with extension header bounded and classified | `OPEN`: EX-01 |
| U15 | IPv6 fragment/jumbo is not called a confirmed SYN | `OPEN`: EX-01 |
| U16 | VLAN and short Ethernet input safe classification | `OPEN`: EX-01 |
| U17 | Invalid IPv6 version/length rejected | `OPEN`: EX-01 |
| U18 | AirPlay-source-port SYN-ACK/RST counted outbound | `OPEN`: EX-01 |
| U19 | Wrong family/port not counted as AirPlay SYN | `OPEN`: EX-01 |
| U20 | 100,000 seeded random packets terminate safely | `OPEN`: EX-01 |
| U21 | TUN write success counted only after successful write | `FIXTURE_ONLY`: fake TUN requires an explicit scripted success; EX-01 integration open |
| U22 | TUN write exception/error category | `OPEN`: EX-01 |
| U23 | Kernel SYN-ACK observed separately from USB OUT | `OPEN`: EX-01 |
| U24 | SYN-ACK USB OUT `NotReady` counted without retry | `PARTIAL`: send result behavior covered; diagnostic correlation is EX-01 |
| U25 | Full-byte SYN-ACK transfer counted as sent | `PARTIAL`: `NcmSendResult` direct fixture test; EX-01 correlation open |
| U26 | Partial transfer is typed failure, not sent | `PASS_LOCAL`: `Step4FixtureFactoryTest.ncmBridgeUsesScriptedUsbOutResults` |
| U27 | Missing peer MAC tracked separately | `OPEN`: EX-01 |
| U28 | Old attachment accept cannot enter new attempt | `OPEN`: EX-01 / EX-07 |
| U29 | Callback concurrent with FINAL leaves immutable snapshot | `OPEN`: EX-01 |
| U30 | Concurrent counter increments lose no updates | `OPEN`: EX-01 |
| U31 | Diagnostic ON/OFF has identical USB/TUN behavior | `OPEN`: EX-01 |
| U32 | Exported diagnostics contain no PII or payload | `PARTIAL`: existing redactor tests; new report redaction is EX-01 |
| U33 | No watchdog before StartSession send | `PARTIAL`: `WiredStartupWatchdogTest`; controller integration evidence open |
| U34 | 8-second soft event does not tear down | `PARTIAL`: `WiredStartupWatchdogTest`; controller integration evidence open |
| U35 | 15-second hard deadline tears down once | `PARTIAL`: watchdog state tests; controller teardown integration open |
| U36 | ALT-only setup remains distinct from MAIN | `OPEN`: EX-03 |
| U37 | Active at 10s / no MAIN by 15s has distinct stages | `OPEN`: EX-03 |
| U38 | Old watchdog token cannot affect current attempt | `PARTIAL`: existing watchdog token tests; controller integration open |
| U39 | Recovered session skips old pending retry | `PARTIAL`: host handoff tests; exact loss-epoch case is EX-02 |
| U40 | Recovery followed by a second loss permits one retry | `OPEN`: EX-02 |
| U41 | Controller-owned failure does not trigger Host duplicate retry | `PARTIAL`: current recovery ownership tests; exact timeout case is EX-02 |
| U42 | Owner transfer/Activity recreation rejects old callback | `PARTIAL`: `CarPlayRestartHandoffTest`; reconnect race integration open |
| U43 | Same generation at 10s schedules one re-enumeration probe | `OPEN/CONDITIONAL`: EX-04 |
| U44 | Detach or USB generation change makes probe a no-op | `OPEN/CONDITIONAL`: EX-04 |
| U45 | First bulk OUT after 15s detects unreachable recovery proposal | `OPEN/CONDITIONAL`: EX-05 virtual-time gate |
| U46 | Healthy initial SYN/accept suppresses another StartSession | `OPEN/CONDITIONAL`: EX-05 |
| U47 | Stale CSM/reconnect never receives an extra send | `OPEN/CONDITIONAL`: EX-05 |
| U48 | Local bind/address validation separates destination from accept | `OPEN/CONDITIONAL`: EX-07 |
| U49 | Old server accept cannot read new attachment | `OPEN/CONDITIONAL`: EX-07 |
| U50 | `input=false` with valid port never starts recording | `PARTIAL`: existing mic lifecycle tests; classifier case is PR-04 |
| U51 | Siri/Phone use existing microphone path | `PARTIAL`: existing component tests; hardware gate remains PR-04 |
| U52 | Default/compat voice-note request starts and stops if required | `OPEN`: PR-04 |
| U53 | AudioRecord exception/retry has one bounded owner | `PARTIAL`: lifecycle tests; requested exception matrix remains PR-04 |
| U54 | NAV/ALERT/PHONE/ASSISTANT routing, DSP policy, RTP and clock | `PARTIAL`: current audio/DSP component tests; full regression remains PR-04 |
| U55 | LOCAL full→55%→full keeps same session/audio | `PARTIAL`: current split state tests; zero-restart acceptance is PR-06 LOCAL |
| U56 | Letterbox edge touch inverse matches render geometry | `OPEN`: PR-06 LOCAL |
| U57 | Default `/info` remains single-area compatible | `PARTIAL`: `AirPlayInfoPlistTest`; multi-area toggle remains PR-05 |
| U58 | Invalid second area/safeArea safely rejected | `OPEN`: PR-05 |
| U59 | Wire send alone is not DYNAMIC acceptance | `OPEN`: PR-05 / PR-06 DYNAMIC |
| U60 | Device silence/rejection falls back to LOCAL/LEGACY once | `OPEN`: PR-05 / PR-06 DYNAMIC |
| U61 | Google profiles A/B do not share cookies/login | `PARTIAL`: profile/context tests; combined Split regression remains PR-06 |
| U62 | Surface resume/rotation/IME does not recreate session | `PARTIAL`: existing lifecycle and resize tests; device gate remains PR-06/07 |
| U63 | Codec output format change keeps crop/decoder/touch aligned | `PARTIAL`: `AndroidMediaSinkStateTest`; full format integration is PR-07 |
| U64 | Missing CSD/timeout/seek has bounded decoder-only fallback | `PARTIAL`: decoder component tests; PR-07 |
| U65 | DSP master OFF matches legacy PCM baseline | `PARTIAL`: `DspOffLegacyPcmTest`; new-node golden remains DSP |
| U66 | Seat L/R delay, trim, polarity and click-free transition | `OPEN`: DSP-01 |
| U67 | M/S mono/antiphase behavior is stable | `OPEN`: DSP-02 |
| U68 | Invalid cabin measurement fails safe without auto-correction | `OPEN`: DSP-03 |
| U69 | Harmonic oversampling alias/headroom/latency | `OPEN`: DSP-04 |
| U70 | Native 44.1/48k, mono/stereo, variable block sizes | `PARTIAL`: current native DSP tests; full matrix and local host test runner unavailable |
| U71 | Live update/Convolver preserves A/V clock | `PARTIAL`: existing DSP live-update tests; new latency integration remains DSP-05 |
| U72 | Deleted BYD-only dependency does not return | `PARTIAL`: frozen main and public-tree check; PR-08 manifest audit remains |
| U73 | Feature rollback leaves other features unchanged | `OPEN`: PR-08 |
| U74 | Old profile/APK update preserves settings and ownership | `PARTIAL`: profile migration tests; release/update evidence is PR-08 |
| U75 | TS10 first connect/reconnect/split/audio | `NOT_RUN`: hardware unavailable |

## Part III R-04 added cases

The plan labels these as 50 additional cases and supplies 50 named IDs. All IDs are preserved below.

| ID | Case | Status |
|---|---|---|
| R04-USB-01 | `0x4301` without USB read completion stays a USB classification, not NTB | OPEN |
| R04-USB-02 | Async persistent timeout retains original queued request | PASS_LOCAL: U09 |
| R04-USB-03 | Unexpected status completion exposes current ownership failure; shared reaper only if designed | FIXTURE_ONLY / EX-06 |
| R04-USB-04 | Same USB node at 10s cannot duplicate permission prompt | OPEN / EX-04 |
| R04-USB-05 | Unusual but valid interface altitude/CDC Union remains safely selected | OPEN |
| R04-USB-06 | Complete aligned 512-byte NTB without optional pad is delivered | KNOWN_GAP / PR-01 |
| R04-USB-07 | 512-byte NTB plus one pad leaves next NTB untouched | OPEN / PR-01 |
| R04-USB-08 | USB chunks split/coalesce blocks; zeros and ZLP are handled | OPEN / PR-01 |
| R04-USB-09 | Invalid NTH/NDP resync is bounded and cannot create false frames | OPEN / PR-01 |
| R04-USB-10 | USBMUX trailer and 1+N frames across three reads preserve bytes | PARTIAL: decoder tests; combined read fixture open |
| R04-USB-11 | 32 KiB queue false falls back finitely without stale request; minSdk stays 28 | OPEN / CONDITIONAL PR-02 |
| R04-USB-12 | Partial `NcmSendResult` and NotReady remain distinct | PASS_LOCAL: U24–U26 |
| R04-IP-01 | Valid IPv4 SYN to actual listener is counted by family | OPEN / EX-01 |
| R04-IP-02 | IPv6 extension parses bounded; incomplete fragment is unknown | OPEN / EX-01 |
| R04-IP-03 | Truncated IPv6 is invalid without transport changes | OPEN / EX-01 |
| R04-IP-04 | VLAN packet has a distinct classification | OPEN / EX-01 |
| R04-IP-05 | Wrong IPv6 destination/port is distinct from target SYN | OPEN / EX-01 |
| R04-IP-06 | Failed TUN write does not set write-success | FIXTURE_ONLY / EX-01 |
| R04-IP-07 | SYN-ACK plus NCM NotReady is correlated in the same attempt | OPEN / EX-01 |
| R04-IP-08 | SYN-ACK sent without ACK/accept is not called socket success | OPEN / EX-01 |
| R04-IP-09 | Old VPN accept loop cannot increment new generation | OPEN / EX-01 / EX-07 |
| R04-IP-10 | Detach during read/write/status close does not deadlock | OPEN |
| R04-REC-01 | Host retry skips after current session recovers | PARTIAL / EX-02 |
| R04-REC-02 | Recovery then second loss creates the legitimate retry | OPEN / EX-02 |
| R04-REC-03 | Controller-owned recovery produces zero Host duplicate retry | PARTIAL / EX-02 |
| R04-REC-04 | Menu/Split owner change preserves background controller | PARTIAL: handoff tests; combined case open |
| R04-REC-05 | AirPlay Active and delayed MAIN setup have separate timings | OPEN / EX-03 |
| R04-REC-06 | ALT-only setup is not reported as MAIN first frame | OPEN / EX-03 |
| R04-REC-07 | Late StartSession suppressed after correct-port SYN | OPEN / EX-05 |
| R04-REC-08 | Active session survives stale retry and late USB reset | OPEN / EX-02 / EX-05 |
| R04-A-01 | Explicit `isInput=false` with data port does not record | PARTIAL / PR-04 |
| R04-A-02 | Voice note may record in compat mode; music alone never captures | OPEN / PR-04 |
| R04-A-03 | Mic zero-read recovery is bounded and cancellable | PARTIAL / PR-04 |
| R04-A-04 | RTP 16/48 kHz packetization, NAV gain and AEC stay aligned | PARTIAL / PR-04 |
| R04-A-05 | Buffer pacing, upsample and DSP clock changes do not alter pitch unintentionally | OPEN / PR-04 and DSP-05 |
| R04-V-01 | LOCAL 55:45 geometry/touch roundtrip on resize | OPEN / PR-06 LOCAL |
| R04-V-02 | Dynamic send without acknowledgement remains unconfirmed | OPEN / PR-05 |
| R04-V-03 | Decoder format change keeps crop and touch synchronized | PARTIAL / PR-07 |
| R04-V-04 | Last-frame bitmap/Surface/thread releases after frame or timeout | OPEN / PR-07 |
| R04-V-05 | Twenty rapid Split cycles retain controller/session/stream IDs | OPEN / PR-06 |
| R04-D-01 | New DSP nodes OFF match old PCM golden constraints | PARTIAL / DSP-00 |
| R04-D-02 | Seat fractional delay and graph latency without clipping | OPEN / DSP-01 |
| R04-D-03 | M/S mono compatibility and no duplicate width stage | OPEN / DSP-02 |
| R04-D-04 | Cabin import validates measurements/notches and makes no synthetic claim | OPEN / DSP-03 |
| R04-D-05 | Harmonic anti-aliasing, delay and CPU budget | OPEN / DSP-04 |
| R04-D-06 | Audio block processing has no dynamic allocation, lock, or I/O | OPEN / DSP-05 |
| R04-G-01 | Rollback restores defaults and preserves profiles | OPEN / PR-08 |
| R04-G-02 | BYD-only packages remain absent with no unresolved references | PARTIAL / PR-08 |
| R04-G-03 | No old revert commit and no `0x4302` EndSession misuse | OPEN / PR-08 |
| R04-G-04 | Android variants, unit, R8 mapping, ABI/native gates pass | PARTIAL: local workflow checks except native/instrumentation |
