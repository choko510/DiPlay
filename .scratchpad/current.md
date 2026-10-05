# Current Work

## Pending real-device wired NCM A/B

- The mobile arm64 debug APK now includes the local authentication assets and has verified v1/v2 APK signatures. If installation still fails, compare the exact installed file and capture the installer result before changing the NCM diagnosis.
- The 2026-10-05 follow-up report still records only `AUTO` (102 profile mentions); no A/B profile has been exercised.
- Its 41 rate-limited startup `NCM_TX` records are all `NOT_READY` at 100 ms: 17 `NDP_RS`, 24 `ICMPV6_OTHER`. There are 18 async wait timeouts, one zero-byte completion, and one queue failure; no RX/TX link proof or AirPlay control acceptance.
- The old `ndpTxAttempts=0` was a classifier blind spot for Router Solicitation, not proof that no NDP packet was emitted. Retry behavior remains limited to NS/NA pending an isolated test.
- In the mobile arm64 debug APK, open DiPlay Settings → About & Diagnostics (or the projected CarPlay settings overlay) and run `STATUS_POLLING` against `AUTO` first.
- Repeat ON/OFF attempts; if inconclusive, isolate OUT timeout next, then use sync Bulk IN and forced function selection only as later experiments.
- Root cause and the separate post-startup UsbRequest failure remain unconfirmed until these device results are available.
