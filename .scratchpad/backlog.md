# Backlog

- Validate the status-polling default with the mobile arm64 debug APK: alternate `AUTO` and `NO_STATUS_POLLING` on the same 3→4/1 setup and compare `SCREEN_STREAM_OPENED` plus first-frame counts. The 2026-10-09 report has four complete successes with status polling and 100 ms OUT; status-off paired runs and timeout comparisons with polling on remain pending. Do not change 5→6 or add CDC-NCM control requests.
- Keep UsbRequest queue/wait recovery separate. The 2026-10-09 queue-false and wait-null events coincide with physical device removal; capture a device-present failure before changing request recreation or reopen behavior.
- Implement DSP-01 Seat Focus/L-R Time Alignment with fixed latency budget, fractional-delay validation, smoothing, and OFF golden parity.
- Implement DSP-02 Advanced M/S integrated with current Stereo Width/Mono Bass, including mono and anti-phase/headroom cases.
- Implement DSP-03 measured Cabin Correction via validated external CSV/IR and a clear unavailable state; do not infer cabin response from music playback.
- Implement DSP-04 Harmonic Engine with bounded harmonics, anti-alias/oversampling, and latency accounting; keep disabled by default.
- Complete DSP-05 combined graph, live-update, clock/latency, native sanitizer, and performance validation after DSP-01..04.
- Re-run the host `scripts/test_dsp_native.sh` and sanitizer variant when WSL or a host C toolchain is available; the API 36 emulator-native C pass is not a host-native substitute.
