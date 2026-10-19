# Backlog

- Validate the status-polling default with the mobile arm64 debug APK: alternate `AUTO` and `NO_STATUS_POLLING` on the same 3→4/1 setup and compare `SCREEN_STREAM_OPENED` plus first-frame counts. The 2026-10-09 report has four complete successes with status polling and 100 ms OUT; status-off paired runs and timeout comparisons with polling on remain pending. Do not change 5→6 or add CDC-NCM control requests.
- Keep UsbRequest queue/wait recovery separate. The 2026-10-09 queue-false and wait-null events coincide with physical device removal; capture a device-present failure before changing request recreation or reopen behavior.
- Push the completed PR-05 model/serializer work to the same PR #45. Multi-area `/info` remains opt-in/off; no dynamic switch command is implemented without iPhone acceptance evidence.
- Record PR-06 DYNAMIC and PR-07 `NO_GO_THIS_RUN` for unavailable phone acceptance/codec/Surface evidence; continue DSP-00..05, then PR-08. Physical mic/Siri/call/voice-message, PR-05 phone acceptance, and PR-06 LOCAL 20-cycle checks remain `NOT_RUN` without target hardware.
- Complete the per-commit EX-08 upstream decision ledger; no bulk merge. Keep physical, rollback, update/signature and soak gates NOT_RUN until evidence exists.
