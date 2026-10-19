# Backlog

- Validate the status-polling default with the mobile arm64 debug APK: alternate `AUTO` and `NO_STATUS_POLLING` on the same 3→4/1 setup and compare `SCREEN_STREAM_OPENED` plus first-frame counts. The 2026-10-09 report has four complete successes with status polling and 100 ms OUT; status-off paired runs and timeout comparisons with polling on remain pending. Do not change 5→6 or add CDC-NCM control requests.
- Keep UsbRequest queue/wait recovery separate. The 2026-10-09 queue-false and wait-null events coincide with physical device removal; capture a device-present failure before changing request recreation or reopen behavior.
- Implement DSP-04 Harmonic Engine on the same PR #45 branch, with bounded oversampling/anti-alias, level matching, and measured latency. DSP-01/02 physical listening, phase/A-V, DSP-03 target measurement/listening, and all earlier device gates remain `NOT_RUN` without target hardware.
- Complete the per-commit EX-08 upstream decision ledger; no bulk merge. Keep physical, rollback, update/signature and soak gates NOT_RUN until evidence exists.
