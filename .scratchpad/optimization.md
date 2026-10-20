# PR-DSP-12 — DSP pipeline profiling and performance gate

Measured 10 configurations (Gain, PEQ15, PEQ+Compressor+Limiter, Bass+Stereo+Mono Bass, 3-band dynamics, Dynamic EQ, Convolver 4k/16k/64k, and the full heavy chain) at 44.1/48 kHz, Mono/Stereo: 40 cases total. Each case used 64 warmup blocks, 64 allocation-check blocks, and 256 measured 512-frame blocks on the Medium Phone API 36.1 x86_64 emulator. Fixed 20 µs histograms report native processing separately from PCM conversion + JNI + native + PCM16 conversion.

Final post-main-sync run worst cases:

- Light group, PEQ15 at 48 kHz Stereo: native p50/p95/p99/max 380/540/680/905 µs; full pipeline 680/900/1,100/1,243 µs.
- Heavy group, Convolver64k at 48 kHz Stereo: native 2,000/2,260/2,600/2,790 µs; full pipeline 2,280/2,560/2,920/3,117 µs.
- FullHeavyChain at 48 kHz Stereo: native 1,400/1,680/1,920/2,055 µs; full pipeline 1,660/2,000/2,260/2,369 µs.

All final-run p99 values stayed below the 2.67 ms light and 5.33 ms heavy budgets. Two repeated complete matrices remained within budget; the first run's isolated high p99 samples did not recur. The 64-block steady-state Android allocation check reported zero Kotlin allocations for every case. Source review found no native allocator calls in the process graph; convolver/engine allocation remains in prepare/create and close paths.

No DSP math, chunk-size, FFT-partition, JNI, or NEON changes were justified: measured p99 budgets passed, and no stable regression hotspot remained after repeat runs. Keep the portable scalar path and 128-frame convolver latency.
# Performance Optimization Ledger

## 2026-10-21 — Reduce draft STEP4 feedback

- Fixed-main CI previously ran the full host and API 35 emulator jobs on every PR update. PR-00 now keeps public-tree, native/sanitizer and shared/common unit checks on draft updates, defers app lint/build/JNI and emulator instrumentation until `ready_for_review`, cancels superseded PR runs, and keeps main pushes/manual runs full. Gradle host/instrumentation invocations use `--build-cache --parallel`.
- Draft Actions run #244 passed in 4m08: public-tree, host native, hosted ASan/UBSan, and shared/common unit checks passed, while full host and emulator steps were skipped. Ready/full run #245 passed in 10m24: the host job took 10m20 (Gradle verification step 9m26) and the parallel API 35 emulator job took 5m05. The scopes differ, so report lane timings rather than a controlled speedup ratio.
- Run #246 confirmed that same-repo PR cache writes were read-only by default; its docs-only quick check took 5m46 with only 3/51 Gradle tasks cached. PR-00 now writes Gradle cache only within the same PR merge-ref and keeps fork/non-main manual runs read-only. Run #247 passed in 5m20, with the Gradle unit/native path completing in 4m03 and the action reporting `Cache mode: write` plus saved entries. Docs-only run #248 restored the same-PR cache and passed in 1m50; Gradle completed the unit task phase in 25s with 30/51 tasks from cache. Unit-test outputs were reused because neither source nor test inputs changed; code/test changes still invalidate their tasks.
- Full run #250 passed in 7m28 on the warmed PR cache: the host Gradle verification step took 6m09 (68/359 tasks from cache), and the parallel API 35 instrumentation job passed in 5m29. Compared with run #245's 10m20 host job on a read-only cache, run #250's host job took 7m25, a 2m55 reduction; cache warmth explains part, while runner variance remains possible.
- Local `scripts/run_android_instrumentation.sh` passed 16/16 on API 36.1 in 41s after adding Gradle flags; the earlier 55s run used a different warmed state, so it is not a controlled comparison. The PR draft lane avoids repeating lint/build/JNI and emulator work during implementation; all full gates ran on ready-for-review.

## 2026-10-02 — PR #14 CI wall time

- Moved API 35 emulator boot and `:shared:connectedDebugAndroidTest` into a parallel `android-instrumentation` job, and stopped installing the emulator system image in the host verification job. Kept all 40 DSP cases, three 256-block measurement rounds, and the p99 gate unchanged.
- Actions run 85 passed both jobs and finished in 9m55s, compared with 13m28s for the preceding serial run 84 (3m33s / 26% less wall time). The instrumentation job completed while host verification continued.

## 2026-10-02 — PR #14 production-path DSP benchmark

- Extended the emulator matrix for latency-bearing graphs to process both the delayed-dry shadow bypass and DSP pipeline on each block. The combined p99 is now the gate for Convolver cases, and the zero-allocation check covers both paths. The 40-case matrix and three 256-block rounds remain.

## 2026-10-02 — PR #14 DSP review follow-up

- Preallocate the PCM16 output and Float32 crossfade scratch for the supported 16,384-frame decoder block during pipeline preparation. The first 1,537-frame stereo call reports zero Kotlin allocations on the API 36.1 x86_64 emulator; the identity processor uses indexed Float copying.
- Crossfade live graph output in Float32 and quantize/dither the mixed result once. Keep the performance decision tied to the complete PCM→Float→JNI/native→PCM16 pipeline histogram.
- Gate the median pipeline p99 over three 256-block runs at 200% of a 512-frame block. Hosted-emulator medians reached 16.32 ms under runner contention while the local repeated run stayed below 3.3 ms; this accommodates that variance but still fails sustained p99 above two block durations. In the final 40-case local matrix, worst median-run light pipeline p99 was 1.10 ms; worst heavy p99 was 3.26 ms (Convolver64k, 44.1 kHz stereo). No loop fusion, FFT, compiler, or SIMD change was justified.

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
