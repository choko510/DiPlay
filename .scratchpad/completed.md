# Completed

- 2026-09-28: Refined trace error classification to prioritize Lockdown/plist/TLS, USBMUX/NCM, and iAP2 operation markers before generic USB exception fallbacks; shared/common tests and both app builds pass.
- 2026-09-28: Corrected connection trace semantics by separating iAP2 USB session open from NCM attachment and mapping nested USB timeout/protocol exception types to the right error categories. Shared and common unit tests pass.
- 2026-09-28: Added an opt-in CarPlay connection trace from USB discovery through first rendered frame, typed error events, bounded persistent storage, and inclusion in exported diagnostic reports. Unit tests and both debug app builds pass; physical CarPlay timing still needs device verification.
- 2026-09-28: Investigated the wired CarPlay opening display and identified its exact start/end conditions, existing diagnostic markers, and code-level latency candidates. Actual device timing remains unmeasured.
- 2026-09-28: Added persisted Japanese/English app language with Japanese default across mobile and automotive UI, dialogs, crop screen, CarPlay host, and notifications. Robolectric tests and both debug builds pass; live CarPlay continuity still needs device verification.
