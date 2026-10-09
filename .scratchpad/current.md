# Current Work

## Pending real-device wired NCM A/B

- The 2026-10-09 report has four complete traces through AirPlay control acceptance, encryption/event acceptance, `SCREEN_STREAM_OPENED`, and first-frame submission. They correlate with 3→4/1, status polling on, async Bulk IN, and the unchanged 100 ms pre-ready OUT timeout.
- Production `AUTO` now polls when the selected candidate exposes a status endpoint. Debug `NO_STATUS_POLLING` preserves the old off behavior for an alternating A/B comparison; timeout and other diagnostic profiles keep polling on. The saved debug name `STATUS_POLLING` now resolves to `AUTO`.
- Next alternate `AUTO` / `NO_STATUS_POLLING` on the same phone and cable, comparing screen-open and first-frame counts. If status polling's effect is confirmed, compare timeout values while polling remains on.
- The observed queue-false and requestWait-null errors coincided with physical USB disconnects; the existing controller closes and reopens the NCM connection on clean retries. Do not add request recreation unless the error recurs while the device is still present.
- The rebuilt mobile arm64 APK verifies with v1/v2 and contains the two local authentication assets. The user asked not to install it, so keep it as a build artifact only.
- Keep `FORCE_5_6` as a negative control; do not change the production function pair or add CDC-NCM control requests.
