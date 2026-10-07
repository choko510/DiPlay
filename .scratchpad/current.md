# Current Work

## Pending real-device wired NCM A/B

- The mobile arm64 debug APK now includes the local authentication assets and has verified v1/v2 APK signatures. If installation still fails, compare the exact installed file and capture the installer result before changing the NCM diagnosis.
- The 2026-10-07 report contains `AUTO`, `FORCE_3_4`, `FORCE_5_6`, and `SYNC_BULK_IN`; it has no `STATUS_POLLING` or non-100-ms OUT-timeout run.
- `FORCE_5_6` reached `NCM_AB_RESULT outcome=LINK_READY` once; `FORCE_3_4` and `SYNC_BULK_IN` ended at `NO_LINK_PROOF`. The FORCE_5_6 records include two successful OUT writes and two 140-byte async IN completions, but the report contains no AirPlay-control, screen-stream, or first-frame marker.
- Repeat FORCE_3_4 and FORCE_5_6 in alternating attempts before changing the production default; the historic 3→4/1 success makes one FORCE_5_6 result insufficient to confirm causality.
- `NDP_RS` packets are now visible as a classification blind spot in old counters; retry behavior remains limited to NS/NA. The separate UsbRequest failure also remains open.
- On the mobile arm64 debug APK, use DiPlay Settings → About & Diagnostics to select a profile and export each result report.
