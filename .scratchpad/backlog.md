# Backlog

- Capture at least three successful/failed EX-01 WiredPath runs on TS10/K706 + iPhone and measure diagnostic overhead before treating the hardware gate as complete. A connected API 36 emulator is not a substitute.
- Run PR-01 against TS10/K706 + iPhone: NCM RX/TCP stability, paired connection-rate A/B, sustained throughput, and CPU/PSS. The emulator suite does not cover USB NCM.
- Re-run `scripts/test_dsp_native.sh` and its sanitizer variant when a host C compiler or functioning WSL distribution is available.
- Validate the status-polling default with the mobile arm64 debug APK: alternate `AUTO` and `NO_STATUS_POLLING` on the same 3→4/1 setup and compare `SCREEN_STREAM_OPENED` plus first-frame counts. The 2026-10-09 report has four complete successes with status polling and 100 ms OUT; status-off paired runs and timeout comparisons with polling on remain pending. Do not change 5→6 or add CDC-NCM control requests.
- Keep UsbRequest queue/wait recovery separate. The 2026-10-09 queue-false and wait-null events coincide with physical device removal; capture a device-present failure before changing request recreation or reopen behavior.
