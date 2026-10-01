# Performance Optimization Ledger

## 2026-10-01 — CarPlay + YouTube split

- Reuse the Activity-scoped primary GeckoSession across split exits for the same resolved profile; suspend it with `setActive(false)`, close popups before suspending, and avoid new `open()`/`loadUri()` calls on a healthy warm reopen. Keep the old view hidden until the active iPhone identity resolves.
- Cache one active AirPlay session's profile resolution using an in-memory weak session token and the exact identity evidence; skip repeat SHA-256 and preference lookup while that evidence is unchanged. Persist only changed hashed alias values.
- Make IME suppression edge-triggered, retain the two-frame settlement guard and recheck true layout changes after the keyboard closes.
- Add debug-only Android trace spans/counters for split, Gecko, profile and CarPlay restart work. Enable release R8 optimization and add debug-signed optimized benchmark variants for CI.
- Runtime latency, PSS, CPU, frame timing, GC and thermal deltas remain unmeasured on the target head unit. Dynamic ViewArea and adaptive decoder resizing remain off; the verified full restart fallback is retained.
