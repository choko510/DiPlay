# DSP Studio test matrix

## Baseline execution ledger

| Target SHA | Command | Result | Evidence |
|---|---|---|---|
| ac4694cf1fbb08e443f53a53158528619ed81940 | bash scripts/test_dsp_native.sh | PASS, exit 0 | Native DSP tests passed |
| ac4694cf1fbb08e443f53a53158528619ed81940 | bash scripts/test_dsp_native.sh --sanitize | BLOCKED_ENV, exit 134 | macOS reports detect_leaks is unsupported |
| ac4694cf1fbb08e443f53a53158528619ed81940 | ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize | PASS, exit 0 | ASan/UBSan pass; LeakSanitizer unavailable |
| ac4694cf1fbb08e443f53a53158528619ed81940 | ./gradlew --parallel :shared:testDebugUnitTest :common:testDebugUnitTest | BLOCKED_ENV, exit 1 | ANDROID_HOME was unset |
| ac4694cf1fbb08e443f53a53158528619ed81940 | ANDROID_HOME=/Users/choko/Library/Android/sdk ANDROID_SDK_ROOT=/Users/choko/Library/Android/sdk ./gradlew --parallel --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest | PASS, exit 0 | shared 625/625; common 193/193; 818 total; 0 failures, errors, or skips. Reports: shared/build/reports/tests/testDebugUnitTest/index.html; common/build/reports/tests/testDebugUnitTest/index.html |
| ac4694cf1fbb08e443f53a53158528619ed81940 | python3 scripts/check_public_tree.py | PASS, exit 0 | No credential containers or private-key blocks |
| ac4694cf1fbb08e443f53a53158528619ed81940 | python3 scripts/generate_step4_ex08_inventory.py --check | PASS, exit 0 | 1,289 unique rows; disposition counts recorded in BASELINE.md |

## Requirement-to-test plan

The table maps every specification test ID to its intended automated or hardware evidence. Until an implementation test is attached, status is NOT_RUN. Actual test class/method, command, target SHA, counts, and result will replace the planned mapping as each package lands.

| ID range | Required coverage | Planned test location | Status |
|---|---|---|---|
| T-MAC-01..06 | defaults/Neutral, bounded curves, immutable base profile, mono/width behavior, immutable Factory, latest update wins | common DSP model/resolver unit tests; shared native DSP host tests; common Compose tests | NOT_RUN |
| T-PRE-01..09 | module locality, round-trip, AtomicFile failure, future schema, malicious payload, 15-band cap, IR reference, undo/A-B | common preset/workspace repository and coordinator tests | NOT_RUN |
| T-LOUD-01..10 | zero response, monotonic mapping, signal independence, unavailable AUTO, invalid route values, ramp, MANUAL/FIXED, calibration protection, latency | common source/controller tests; shared native DSP tests; TS10 volume calibration | NOT_RUN |
| T-DB-01..12 | disabled output, silence/gate, level mapping, transients, stereo link, rates/channels, block invariance, pre-boost independence, combined safety, hot updates, lifecycle | shared native DSP host/sanitizer tests and Kotlin renderer tests | NOT_RUN |
| T-SAFE-01..09 | combined worst-case bounds, existing headroom, preamp ramps, atomic Hot command, invalid inputs, volume transitions, peak/TruePeak, limiter behavior, finite output | common resolver tests; shared native DSP tests and offline signal analysis | NOT_RUN |
| T-LIVE-01..19 | uninterrupted playback, latest-wins, close/format/owner races, stale rejection, graph coexistence, post-block ACK, no-media, A/B/Undo, master-off, stateful modules, Warm/Deferred, reaper and status progression | shared renderer/controller/NativeDspProcessor tests; native stress tests | NOT_RUN |
| T-UI-01..11 | FULL/Split controls, graph editing, module tabs, state fidelity, loudness labeling, accessibility, legacy reachability, 20-cycle Surface/session/touch continuity | common Compose/Robolectric and Android instrumentation; TS10 Split cycles | NOT_RUN |
| T-REG-01..12 | master-off parity, latency continuity, MEDIA-only, default parity, four formats, block boundaries, band capacity, existing graph, JNI lifecycle, A/V, ABI validation, Surface/owner | existing and new shared native/unit/instrumented tests; TS10 A/V | NOT_RUN |
| T-MIG-01..10 | v0-v7 compatibility, custom1/custom2, no implicit workspace write, neutral defaults, restart round-trip, atomic failure, future schema, IR references, preview cancel, flags-off rollback | common repository/runtime tests; Android AtomicFile instrumentation | NOT_RUN |
| T-PERF-01..12 | old/new performance, no allocation, control storm, stage p99, full chain, UI telemetry, four formats, Warm retirement, soak, volume correspondence, A/V/20-cycle, sanitizer/JNI/build | shared instrumentation benchmark, native host/sanitizers, APK/JNI gates, TS10 runbook | NOT_RUN |

## Evidence rules

- A skipped, unavailable, or unexecuted test is SKIPPED_CI, BLOCKED_ENV, or NOT_RUN, never PASS.
- Test totals must include failures, errors, and skips from Gradle XML reports. Native sanitizer summaries state the exact sanitizer set and host limitations.
- Emulator results do not substitute for TS10/K706, target CPU/RAM, acoustic, MCU volume, A/V, or long-soak measurements.
