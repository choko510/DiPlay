# B08 — Stage3 wired startup timing

## Scope

B08 adds observation-only timestamps for one wired startup attempt. Each milestone is stored as nanoseconds elapsed from that attempt's `System.nanoTime()` origin. The snapshot carries both the startup attempt ID and VPN attach generation; callbacks from a different generation are rejected. No wall-clock time, packet bytes, address strings, or other packet contents are retained.

| Snapshot field | Recorded when |
|---|---|
| `start_session_ns` | Immediately after the existing wired `CarPlayStartSession` send callback. |
| `first_full_bulk_out_ns` | The first successful NCM OUT whose `transferred` equals the exact full NTB size. `NotReady` and partial transfers do not count. |
| `first_valid_airplay_syn_ns` | The fork packet classifier validates a TCP SYN for the expected AirPlay destination and port. |
| `syn_ack_tun_ns` / `syn_ack_out_ns` | The first valid AirPlay SYN-ACK is read from TUN, then successfully sent as a complete NCM NTB. |
| `listener_accept_ns` | The active attachment accepts a TCP socket, before the session permit check. |
| `rtsp_record_ns` | The AirPlay session handles its first RTSP RECORD. |
| `main_setup_ns` / `main_first_frame_ns` | MAIN screen SETUP succeeds and its first frame is submitted to the surface. |
| `teardown_ns` | The wired attempt begins teardown or reaches its final cleanup path. |

`SynObservation` is `PRESENT` when a valid AirPlay SYN was recorded, `ABSENT_WITHIN_WINDOW` only after 15 seconds from the first StartSession with no parse ambiguity, and `UNKNOWN` otherwise. Unsupported/truncated IPv6 and unverified IPv4-only SYNs remain unknown. The 2-second quiet-window helper is a pure observation calculation; it schedules no timer and sends nothing.

## Bounded collection and behavior

The always-on state is a fixed 10-slot atomic timestamp array per active attempt. It records only first milestones and one uncertainty flag. It is separate from `WiredPathDiagnostics`, whose packet counters remain opt-in with debug logging. The completed summary is validated and retained in memory for the latest three attempts so a manual diagnostic report can include it even when debug logs are off; it is not written to Logcat or a file.

The change does not add a StartSession send, timer, retry, watchdog rule, connection-phase transition, or packet rewrite. Existing watchdog deadlines remain 8 seconds and 15 seconds. NCM success accounting and NDP behavior are unchanged. `FIRST_FULL_BULK_OUT` is independent from the debug-only wired-path counter.

## Verification

- `WiredStartupTimingTest`: exact full/partial OUT, before/after StartSession, attempt and generation separation, all ten milestones, valid/unknown SYN cases, 14.9/15/17/26-second boundaries, the 2-second quiet boundary, and debug-independent collection.
- `NcmUsbBridgeFixtureTest`: NCM send results stay the same; timing is collected with packet diagnostics disabled; partial output does not create a FULL OUT timestamp.
- `Ipv6NcmBridgeWiredPathDiagnosticsTest`: debug-off timing observes a valid SYN while the exact same IP packet bytes reach TUN.
- Full local shared suite: 655/655; full local common suite: 206/206; zero failures, errors, or skips.

CPU/heap differential measurements and TS10/K706 plus supported-iPhone causal traces have not been captured. B11 remains `CONDITIONAL_NO_GO`; this instrumentation does not establish hardware acceptance.
