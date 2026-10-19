# STEP4 test matrix and current-main coverage

This matrix preserves every v3 U01–U75 acceptance ID and all 50 R-04 additions. It describes the fixed main baseline only. A nearby pure test is not proof of an Android USB, VPN, Surface, audio route, same-session, or hardware path.

## Status vocabulary

- `PASS_UNIT`: the exact pure/unit condition is present and passed.
- `PARTIAL`: related pure/unit coverage exists, but not the full acceptance condition.
- `NOT_COVERED`: no current-main automated test proves that condition.
- `NOT_RUN`: physical or other unavailable environment is required.
- `PR00_FIXTURE`: deterministic input/fake exists; it does not prove production behavior.

## PR-00 fixtures

`shared/src/test/java/com/shilapi/xcertplay/testing/step4/Step4TestFactory.kt` and `Step4TestFactoryTest.kt` provide:

- Robolectric USB connection, synthetic endpoint descriptors, explicit request-queue/wait outcomes.
- NTB16 single, exact-512-byte boundary, optional short-pad, split/coalesced reads, missing/zero/short/corrupt input, and expected datagram fixtures.
- Explicit TUN read/write outcomes, wired-attempt event sequences, virtual clock/scheduler, microphone request examples, 55% local Split geometry, PCM16 bytes, and opaque video payloads.
- Unspecified request-wait or TUN outcomes fail the fixture instead of fabricating success.

These 11 fixture tests are test-only; no production class, constructor, setting, transport, or UI behavior changed. The video bytes are opaque test payloads and are not decoder-valid H.264/HEVC samples.

## Original acceptance IDs U01–U75

| ID | Condition (short form) | Current-main evidence/status | Owner/next stage |
|---|---|---|---|
| U01 | Exact 512-byte NTB, no pad, delivered immediately | Codec-only tests; NCM reader boundary absent; `PARTIAL` | PR-01 |
| U02 | 512-byte NTB with one short pad consumes one byte | Encoder fixture exists; bridge receive behavior untested; `PARTIAL` | PR-01 |
| U03 | Split/coalesced NTBs preserve order and headers | USBMUX decoder tests cover a different framing layer; `PARTIAL` | PR-01 |
| U04 | Garbage followed by valid NTB bounded resync | `NOT_COVERED` | PR-01 |
| U05 | Invalid NTH/NDP offsets cannot OOB or loop | `NOT_COVERED` | PR-01 |
| U06 | More than 1 MiB corrupt input fails finitely | `NOT_COVERED` | PR-01 |
| U07 | Successful 32 KiB queue reuses request | `PersistentUsbReadRequestStateTest` tests generic request identity; no Android queue; `PARTIAL` | PR-00 / PR-02 |
| U08 | Queue-false fallback tiers avoid stale request | Main has no fallback; `NOT_COVERED` | PR-02 conditional |
| U09 | Timeout leaves original request queued | `PersistentUsbReadRequestStateTest.timeoutKeepsTheSameRequestQueuedAndDoesNotRequestCancellation`; `PASS_UNIT` | PR-00 |
| U10 | Unexpected status completion has one owner | Generic unexpected request transition only; `PARTIAL` | EX-06 |
| U11 | Close racing requestWait has no leak/deadlock | Pure request state is covered, not actual concurrent USB close; `PARTIAL` | PR-00 / EX-06 |
| U12 | Sync/async read modes return consistent behavior | `NcmDiagnosticProfileTest` checks settings, not reader integration; `PARTIAL` | PR-00 / PR-02 |
| U13 | IPv4 SYN to AirPlay listener counted by family | No TCP SYN classifier; `NOT_COVERED` | EX-01 |
| U14 | IPv6 SYN with extension headers bounded | NDP extension parser exists; TCP SYN classifier absent; `PARTIAL` | EX-01 |
| U15 | IPv6 fragment/jumbo not called confirmed SYN | `NOT_COVERED` | EX-01 |
| U16 | VLAN/short Ethernet classified safely | `NOT_COVERED` | EX-01 |
| U17 | Invalid IPv6 version/length not counted as TCP | Codec malformed-input tests exist, no SYN counter; `PARTIAL` | EX-01 |
| U18 | Outbound SYN-ACK counted separately | `NOT_COVERED` | EX-01 |
| U19 | Wrong destination/port not counted as AirPlay SYN | `NOT_COVERED` | EX-01 |
| U20 | 100,000 seeded packet inputs terminate safely | `NOT_COVERED` | EX-01 |
| U21 | Inbound SYN success recorded only after TUN write | `NcmLinkReadinessTest` covers directional state, not TUN write; `PARTIAL` | EX-01 |
| U22 | TUN exception leaves success count zero | `NOT_COVERED` | EX-01 |
| U23 | Kernel SYN-ACK separated from USB OUT | Readiness state distinguishes RX/TX, not SYN-ACK layers; `PARTIAL` | EX-01 |
| U24 | SYN-ACK NotReady counted without user-space retry | `NcmStartupNdpRetryTest` covers NDP only; `NOT_COVERED` | EX-01 / PR-03 |
| U25 | Only full NCM OUT bytes count as sent | No bridge send-result integration test; `NOT_COVERED` | EX-01 / PR-03 |
| U26 | Partial transfer is typed failure | `NOT_COVERED` | EX-01 |
| U27 | Unknown peer MAC is not treated as successful send | IPv6 link-option serialization exists, not peer-learning behavior; `PARTIAL` | EX-01 / EX-07 |
| U28 | Old attachment accept cannot update new attempt | `NOT_COVERED` | EX-01 / EX-02 |
| U29 | Final snapshot races callback without mutation | `NOT_COVERED` | EX-01 |
| U30 | Concurrent counters lose no updates | `NOT_COVERED` | EX-01 |
| U31 | Diagnostics ON/OFF has no USB/TUN side effect | NCM profile value tests only; `PARTIAL` | EX-01 |
| U32 | Export omits PII, payload, and secrets | Generic redactor/export tests exist, no wired report; `PARTIAL` | EX-01 |
| U33 | Watchdog is not armed before StartSession | Watchdog state tests, no controller send-order test; `PARTIAL` | EX-03 |
| U34 | Soft 8-second observation does not teardown | `WiredStartupWatchdogTest.softSnapshotDoesNotExpireTheHardWatchdog`; `PASS_UNIT` for state only | EX-03 |
| U35 | Hard 15-second expiry is exactly once | One-shot watchdog model test, not controller teardown; `PARTIAL` | EX-03 |
| U36 | ALT and MAIN setup remain distinct | No ALT/MAIN startup timeline test; `NOT_COVERED` | EX-01 / EX-03 |
| U37 | Active at 10 seconds and no MAIN at 15 is separated | `NOT_COVERED` | EX-01 / EX-03 |
| U38 | Old watchdog token cannot affect current attempt | `WiredStartupWatchdogTest.oldSoftAndHardCallbacksCannotAffectTheNextAttempt`; `PASS_UNIT` for model | EX-03 |
| U39 | Active recovery cancels old Host retry | No Host retry ticket test; `NOT_COVERED` | EX-02 |
| U40 | Second loss after recovery allows one new retry | `NOT_COVERED` | EX-02 |
| U41 | Controller-managed wired recovery suppresses Host retry | `NOT_COVERED` | EX-02 |
| U42 | Owner transfer/activity recreation fences old callback | `CarPlayRestartHandoffTest` covers handoff model, not retry race; `PARTIAL` | EX-02 |
| U43 | Current USB node at 10 seconds gets one probe | `NOT_COVERED` | EX-04 conditional |
| U44 | Detach/generation change cancels 10-second probe | `NOT_COVERED` | EX-04 conditional |
| U45 | First OUT delay beyond 15 seconds exposes unreachable recovery | No virtual-time interaction test; `NOT_COVERED` | PR-03 / EX-05 |
| U46 | Healthy SYN/accept suppresses added StartSession | `NOT_COVERED` | EX-05 conditional |
| U47 | Stale CSM/reconnect cannot resend StartSession | `NOT_COVERED` | EX-05 conditional |
| U48 | VPN bind/destination validity differs from accept | Wireless bind tests do not cover wired VPN; `PARTIAL` | EX-07 |
| U49 | Old server accept cannot succeed new attachment | `NOT_COVERED` | EX-02 / EX-07 |
| U50 | Explicit input=false + port starts no mic | Engine ownership/lifecycle tests exist, no full request policy; `PARTIAL` | PR-04 |
| U51 | Siri/Phone use existing capture route | Unit-only packet/lifecycle coverage; physical route `NOT_RUN` | PR-04 |
| U52 | Default/compat voice message capture policy | No full request/permission matrix; `PARTIAL` | PR-04 |
| U53 | AudioRecord failures/zero reads are bounded | Pure uplink lifecycle tests only; `PARTIAL` | PR-04 |
| U54 | NAV/ALERT/PHONE/ASSISTANT retain RTP/DSP/clock routes | Audio policy/codec/clock unit tests; combined route/AEC `NOT_RUN` | PR-04 / AUDIO-EX |
| U55 | Full→55%→full keeps the same session/audio | Split state/handoff tests exist; same-session continuity `NOT_RUN` | PR-06 LOCAL |
| U56 | Letterbox touch maps through inverse geometry | Geometry unit tests exist; device touch `NOT_RUN` | PR-06 LOCAL |
| U57 | `/info` defaults to single-area response | `AirPlayInfoPlistTest.defaultDisplayIncludesFullViewAndSafeAreas`; `PASS_UNIT` for current default | PR-05 |
| U58 | Invalid multi-area safe area rejects/falls back | No multi-area model in main; `NOT_COVERED` | PR-05 |
| U59 | ViewArea send without ACK is not confirmed | No dynamic ViewArea client in main; `NOT_COVERED` | PR-05 / PR-06 DYNAMIC |
| U60 | No response/rejection falls back finitely | No dynamic ViewArea client; `NOT_COVERED` | PR-05 / PR-06 DYNAMIC |
| U61 | Google profiles A/B do not cross-contaminate | Identity/profile/alias unit tests exist; actual login A/B `NOT_RUN` | PR-06 GECKO |
| U62 | Surface resume/rotation/IME avoids active-session recreation | Separate UI/presentation state tests; combined Surface path `PARTIAL` | PR-06 / PR-07 |
| U63 | Codec output-format change keeps crop/touch aligned | Decode queue/capability unit tests, no Surface handoff; `PARTIAL` | PR-07 |
| U64 | Missing CSD/timeout/seek has bounded fallback | No full decoder fallback test; `NOT_COVERED` | PR-07 |
| U65 | DSP master OFF matches legacy PCM | `DspOffLegacyPcmTest`; `PASS_UNIT` | DSP-00 |
| U66 | Seat delay/trim/polarity has correct timing/no pop | Feature absent from main; `NOT_COVERED` | DSP-01 |
| U67 | M/S mono/antiphase remains stable | Feature absent from main; `NOT_COVERED` | DSP-02 |
| U68 | Invalid cabin measurement fails safe | Feature absent from main; `NOT_COVERED` | DSP-03 |
| U69 | Harmonic alias/headroom/latency is measured | Feature absent from main; `NOT_COVERED` | DSP-04 |
| U70 | Native output finite across 44.1/48 kHz, mono/stereo, block sizes | Native C + 10-case AVD benchmark cover part; `PARTIAL` | DSP-00 / DSP-05 |
| U71 | Live update/Convolver preserve A/V clock | Live-update/clock unit tests exist; physical A/V `NOT_RUN` | DSP-05 |
| U72 | BYD-only package/manifest dependency stays absent | No dedicated BYD regression guard; `NOT_COVERED` | OEM-EX / PR-08 |
| U73 | Rollback restores defaults without other regressions | No STEP4 flags in main; `NOT_COVERED` | PR-08 |
| U74 | Old profile and APK update preserve settings/signature | DSP profile migration tests exist; APK update/signature `NOT_RUN` | PR-08 |
| U75 | TS10 + iPhone startup/reconnect/Split/audio baseline | Physical devices unavailable; `NOT_RUN` | PR-00 / PR-08 |

## v3 additional regression IDs (50 cases)

The following IDs are retained from v3 R-04. Status describes fixed-main coverage before the owning stage; all hardware observations remain separate.

| ID | Baseline status | Stage |
|---|---|---|
| U-01 | `NOT_COVERED` — StartSession/no-USB-read classification | EX-01 |
| U-02 | `PARTIAL` — persistent request timeout state | PR-00 / PR-02 |
| U-03 | `PARTIAL` — generic unexpected completion, no status demux | EX-06 |
| U-04 | `NOT_COVERED` — same-node re-enumeration generation | EX-04 |
| U-05 | `PARTIAL` — `NcmFunctionDiscoveryTest` Union pairing, no unusual physical descriptor | PR-00 / PR-02 |
| U-06 | `NOT_COVERED` — 512-byte no-pad NTB at reader boundary | PR-01 |
| U-07 | `NOT_COVERED` — boundary pad with following NTB retained | PR-01 |
| U-08 | `PARTIAL` — split/coalesced USBMUX only | PR-01 |
| U-09 | `NOT_COVERED` — corrupt NTH/NDP bounded resync | PR-01 |
| U-10 | `PARTIAL` — USBMUX decoder split/coalesced frames, no trailer stream case | PR-00 / PR-01 |
| U-11 | `NOT_COVERED` — API28+ queue-false fallback; conditional | PR-02 |
| U-12 | `NOT_COVERED` — partial/NotReady typed NCM send result | PR-03 / EX-01 |
| N-01 | `NOT_COVERED` — IPv4 listener SYN classifier | EX-01 |
| N-02 | `PARTIAL` — IPv6 NDP extensions only, no SYN classifier | EX-01 |
| N-03 | `PARTIAL` — codec invalid/truncated input, no per-attempt counter | EX-01 |
| N-04 | `NOT_COVERED` — VLAN-tagged packet classification | EX-01 |
| N-05 | `NOT_COVERED` — wrong destination/scope/port | EX-01 / EX-07 |
| N-06 | `NOT_COVERED` — TUN write exception counter | EX-01 |
| N-07 | `NOT_COVERED` — SYN-ACK/NotReady per-attempt correlation | EX-01 / PR-03 |
| N-08 | `NOT_COVERED` — Sent vs TCP ACK/accept proof | EX-01 |
| N-09 | `NOT_COVERED` — stale VPN accept generation | EX-02 / EX-07 |
| N-10 | `NOT_COVERED` — detach while read/write/status close | EX-06 |
| R-01 | `NOT_COVERED` — Active cancels pending Host retry | EX-02 |
| R-02 | `NOT_COVERED` — second loss reschedules retry | EX-02 |
| R-03 | `NOT_COVERED` — controller-owned failure avoids duplicate Host retry | EX-02 |
| R-04 | `PARTIAL` — Activity handoff state only | EX-02 / PR-06 |
| R-05 | `PARTIAL` — watchdog state only, no complete timeline | EX-01 / EX-03 |
| R-06 | `NOT_COVERED` — ALT-only setup vs MAIN frame | EX-01 / EX-03 |
| R-07 | `NOT_COVERED` — late StartSession suppressed by current SYN | EX-05 |
| R-08 | `NOT_COVERED` — active session fenced from stale USB callback | EX-02 / EX-04 |
| A-01 | `PARTIAL` — media-owner/lifecycle only | PR-04 |
| A-02 | `PARTIAL` — request intent/default-format policy | PR-04 |
| A-03 | `PARTIAL` — pure uplink lifecycle, no device AudioRecord | PR-04 |
| A-04 | `PARTIAL` — separate RTP/clock tests, no combined route/AEC | AUDIO-EX |
| A-05 | `PARTIAL` — clock tests, no full pacing/upsample route | AUDIO-EX |
| V-01 | `PARTIAL` — layout geometry unit tests | PR-06 LOCAL |
| V-02 | `NOT_COVERED` — send is not ACK/acceptance | PR-05 / PR-06 DYNAMIC |
| V-03 | `NOT_COVERED` — output format change with live Surface/touch | PR-07 |
| V-04 | `NOT_COVERED` — retained Bitmap/Surface release lifecycle | PR-07 |
| V-05 | `NOT_RUN` — 20 physical full/Split cycles | PR-06 |
| D-01 | `PASS_UNIT` — current master-off PCM golden behavior only | DSP-00 |
| D-02 | `NOT_COVERED` — seat delay graph latency | DSP-01 |
| D-03 | `NOT_COVERED` — advanced M/S integration | DSP-02 |
| D-04 | `NOT_COVERED` — measured cabin input and invalid-data handling | DSP-03 |
| D-05 | `NOT_COVERED` — harmonic alias/headroom/latency | DSP-04 |
| D-06 | `PARTIAL` — emulator allocation/host native baseline, not target hot-path stress | DSP-05 |
| G-01 | `NOT_COVERED` — feature-flag rollback matrix | PR-08 |
| G-02 | `NOT_COVERED` — automated BYD dependency guard | OEM-EX / PR-08 |
| G-03 | `PARTIAL` — source-level no-guess policy, no dedicated regression test | PR-08 |
| G-04 | `PARTIAL` — baseline task set passes; release/update/hardware gates remain | PR-08 |

Passing the PR-00 fixture tests confirms deterministic fixture construction only. Later stages must update the relevant IDs with their own test result and evidence; emulator or pure-JVM success never upgrades hardware rows.
