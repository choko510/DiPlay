# Current Work

## Pending real-device wired NCM A/B

- The latest mobile arm64 debug APK includes the local authentication assets and verifies with v1/v2 signatures. Its updated diagnostics are built, but `adb devices -l` currently reports no connected device, so it has not been installed.
- The 2026-10-07 report contains `AUTO`, `FORCE_3_4`, `FORCE_5_6`, and `SYNC_BULK_IN`; it has no `STATUS_POLLING` or non-100-ms OUT-timeout run.
- `FORCE_5_6` previously printed `outcome=LINK_READY`, but this readiness phase can also be set by AirPlay control acceptance and the 700-character export clipped the RX/TX proof fields. The new logs separate `ncmOutcome` (derived only from RX/TX proof), AirPlay control acceptance, screen opening, and the overall outcome.
- Prioritize `LEGACY_OUT_TIMEOUT` (2,000 ms) on 3→4/1 next, then `STATUS_POLLING`, then 500/1,000 ms if needed. The 5→6 pair remains a negative control; do not change AUTO or treat 5→6 as the production fix based on this report.
- iOS's normal NCM plus Aux NCM layout is a plausible explanation for the descriptor difference, but the external analysis does not identify this device's interface numbers; treat the mapping as a hypothesis.
- `NDP_RS` packets are now visible as a classification blind spot in old counters; retry behavior remains limited to NS/NA. The separate UsbRequest failure also remains open.
- On the mobile arm64 debug APK, use DiPlay Settings → About & Diagnostics to select a profile and export each result report.
