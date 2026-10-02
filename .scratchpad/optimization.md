# PR-DSP-12 — DSP pipeline profiling and performance gate

Measured 10 configurations (Gain, PEQ15, PEQ+Compressor+Limiter, Bass+Stereo+Mono Bass, 3-band dynamics, Dynamic EQ, Convolver 4k/16k/64k, and the full heavy chain) at 44.1/48 kHz, Mono/Stereo: 40 cases total. Each case used 64 warmup blocks, 64 allocation-check blocks, and 256 measured 512-frame blocks on the Medium Phone API 36.1 x86_64 emulator. Fixed 20 µs histograms report native processing separately from PCM conversion + JNI + native + PCM16 conversion.

Final post-main-sync run worst cases:

- Light group, PEQ15 at 48 kHz Stereo: native p50/p95/p99/max 380/540/680/905 µs; full pipeline 680/900/1,100/1,243 µs.
- Heavy group, Convolver64k at 48 kHz Stereo: native 2,000/2,260/2,600/2,790 µs; full pipeline 2,280/2,560/2,920/3,117 µs.
- FullHeavyChain at 48 kHz Stereo: native 1,400/1,680/1,920/2,055 µs; full pipeline 1,660/2,000/2,260/2,369 µs.

All final-run p99 values stayed below the 2.67 ms light and 5.33 ms heavy budgets. Two repeated complete matrices remained within budget; the first run's isolated high p99 samples did not recur. The 64-block steady-state Android allocation check reported zero Kotlin allocations for every case. Source review found no native allocator calls in the process graph; convolver/engine allocation remains in prepare/create and close paths.

No DSP math, chunk-size, FFT-partition, JNI, or NEON changes were justified: measured p99 budgets passed, and no stable regression hotspot remained after repeat runs. Keep the portable scalar path and 128-frame convolver latency.
# Performance Optimization Ledger

## 2026-10-02 — PR #14 DSP review follow-up

- Preallocate the PCM16 output and Float32 crossfade scratch for the supported 16,384-frame decoder block during pipeline preparation. The first 1,537-frame stereo call reports zero Kotlin allocations on the API 36.1 x86_64 emulator; the identity processor uses indexed Float copying.
- Crossfade live graph output in Float32 and quantize/dither the mixed result once. Keep the performance decision tied to the complete PCM→Float→JNI/native→PCM16 pipeline histogram.
- Gate non-FIR cases at 25% of a 512-frame block and FIR/full-heavy-chain cases at 50%. In the final 40-case matrix, worst light pipeline p99 was 1.12 ms; worst heavy pipeline p99 was 3.26 ms (Convolver64k, 44.1 kHz stereo). No loop fusion, FFT, compiler, or SIMD change was justified.

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
- 2026-10-02: Scope Gecko reset evidence to suspension generations and keep it independent of trace completion, so profile-resolution timeout neither loses a valid reset nor reuses one from an earlier pause. Anchor the diagnostic timeout to first activation so profile retries and background/resume do not extend the 10-second interval. Real callback timing remains to be measured on hardware.
