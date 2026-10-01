# Performance Optimization Ledger

## 2026-10-01 — CarPlay + YouTube split

- Reuse the Activity-scoped primary GeckoSession across split exits for the same resolved profile; suspend it with `setActive(false)`, close popups before suspending, and avoid new `open()`/`loadUri()` calls on a healthy warm reopen. Keep the old view hidden until the active iPhone identity resolves.
- Cache one active AirPlay session's profile resolution using an in-memory weak session token and the exact identity evidence; skip repeat SHA-256 and preference lookup while that evidence is unchanged. Persist only changed hashed alias values.
- Make IME suppression edge-triggered, retain the two-frame settlement guard and recheck true layout changes after the keyboard closes.
- Add Android trace spans/counters for split, Gecko, profile and CarPlay restart work in debuggable builds and the opt-in benchmark variant. Enable release R8 optimization and add non-debuggable, profileable benchmark variants signed with the debug key for CI.
- Runtime latency, PSS, CPU, frame timing, GC and thermal deltas remain unmeasured on the target head unit. Dynamic ViewArea and adaptive decoder resizing remain off; the verified full restart fallback is retained.

## 2026-10-01 — PR #13 review fixes

- Keep the retained browser inactive and hidden while a replacement AirPlaySession resolves; compare the resolved profile context before reusing it. Distinguish active same-profile calls from true suspended-session reactivation so a live popup remains current.
- Add the Linux I2C exception JNI upcall keep rule through the shared AAR consumer rules; benchmark R8 mappings preserve the class name and `(int, String)` constructor.
- Make optimized benchmark APKs non-debuggable and profileable, with trace collection opted in by benchmark-manifest metadata.
- End split presentation tracing at visible Gecko content paint/composite, and CarPlay restart tracing at the first main decoded output submitted to the render surface. Reconcile IME suppression on resume/focus.
- Target-device JNI failure-path, latency, PSS and CarPlay regression measurements remain outstanding.
- Preserve the warm session across an AirPlaySession object replacement only after the new profile context matches; keep ACTIVE-session opens on the current primary/popup delegate path.
- Make benchmark variants release-like, non-debuggable and profileable. The shared JNI consumer rule preserves the native `FindClass()` class name and constructor; R8 output mappings confirm both apps retain them.
- Measure split presentation to first visible Gecko content/composite, CarPlay restart to first main decoded output submitted for rendering, and IME recovery after resume. These are trace endpoints, not target-device latency measurements.
- Keep `diplay.split.total` open until visible Gecko paint, and keep `diplay.split.carplay_restart` open until the first main MediaCodec output is submitted to the render surface. `page_ready` remains a separate network/load milestone.
- 2026-10-02: Count Gecko paint-status resets, composites, FCP callbacks, visible completions and missing-paint timeouts. After 10 seconds without visible paint, close the split span with `paint_missing`; discard a suspended browser on the pre-Android-14 running-critical trim notification only while split mode is closed. The UI-hidden callback does not evict the cache. This adds diagnostics and a scoped OS-pressure escape without claiming target-device latency or PSS improvements.
- 2026-10-02: Preserve a Gecko reset observed during suspension through warm reopen, and anchor the diagnostic paint timeout to first activation so repeated profile resolution and background/resume do not extend the 10-second interval. Unit tests cover callback ordering and the fixed deadline; real Gecko callback timing remains to be measured on the target head unit.
