# Backlog

- Validate the status-polling default with the mobile arm64 debug APK: alternate `AUTO` and `NO_STATUS_POLLING` on the same 3→4/1 setup and compare `SCREEN_STREAM_OPENED` plus first-frame counts. The 2026-10-09 report has four complete successes with status polling and 100 ms OUT; status-off paired runs and timeout comparisons with polling on remain pending. Do not change 5→6 or add CDC-NCM control requests.
- Keep UsbRequest queue/wait recovery separate. The 2026-10-09 queue-false and wait-null events coincide with physical device removal; capture a device-present failure before changing request recreation or reopen behavior.
- Implement EX-01 per-attempt wired packet provenance next as diagnostics-only, with PII/race/hot-path and ON/OFF behavior tests; physical attempt samples remain NOT_RUN without TS10/iPhone.
- Implement PR-01 bounded NTB optional-pad handling/resynchronization after EX-01; replace the exact-512-byte no-pad characterization with a delivery assertion and add corruption/resync bounds.
- Implement EX-02 recovered-session/second-loss Host retry ownership after PR-01. Apply EX-03..07 behavior changes only when their evidence gates allow them.
- Complete PR-04, PR-06 LOCAL → PR-05 ViewArea → PR-06 DYNAMIC if phone-accepted → PR-07, then the isolated DSP-00..05 series, and PR-08 last.
- Complete the per-commit EX-08 upstream decision ledger; no bulk merge. Keep physical, rollback, update/signature and soak gates NOT_RUN until evidence exists.
