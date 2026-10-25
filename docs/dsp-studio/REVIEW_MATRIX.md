# DSP Studio review matrix

Status at the initial integration commit. Test IDs are defined in TEST_MATRIX.md. A NOT_RUN item is not a pass.

| Package | Requirement scope | Implementation / evidence | Test IDs | Unit | Native | Build/JNI | Instrumented | TS10 | P0/P1 | Risk and rollback | State |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PKG-00 | REQ-009/010 | docs/dsp-studio/BASELINE.md; parent head ac4694cf | REG-01..03, PERF-01 | Baseline pending final Gradle result | Native pass; sanitizer pass with LSan unavailable | NOT_RUN | NOT_RUN | NOT_RUN | None assessed yet | Start from PR head; parent branch untouched | IN_PROGRESS |
| PKG-01 | REQ-001/005/009 | Planned immutable workspace settings, resolver, shared Gain Budget | MAC-01..06, SAFE-01..03, REG-04 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | Disable overlay and keep legacy profile untouched | NOT_STARTED |
| PKG-02 | REQ-004/005/009/011 | Planned fixed-storage DSP stages and disabled-output parity | DB-01..12, REG-01..08, PERF-01..03 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | New stages disabled; remove only new stages if parity fails | NOT_STARTED |
| PKG-03 | REQ-006/007/009 | Planned latest-wins renderer Hot mailbox, generation/owner checks, JNI ACK | LIVE-01..12, SAFE-04..05 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | Keep Hot flag off and existing whole-graph defer behavior | NOT_STARTED |
| PKG-04 | REQ-002/010 | Planned module preset and workspace stores, preview/commit split | PRE-01..09, MIG-01..10, LIVE-14 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | Preserve v7 files; discard workspace feature if migration tests fail | NOT_STARTED |
| PKG-05 | REQ-001/006/008 | Planned Studio Macro UI, A/B, Undo, status | UI-01..06, MAC-01..06, LIVE-02/09 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | Retain prior screen behind UI rollout flag | NOT_STARTED |
| PKG-06 | REQ-003/005 | Planned calibrated AUTO and working MANUAL/FIXED sources | LOUD-01..10, SAFE-06 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | AUTO unavailable without route calibration; OFF fallback | NOT_STARTED |
| PKG-07 | REQ-004/005/011 | Planned pre-boost stereo-linked Dynamic Bass detector and output stage | DB-01..12, SAFE-04..09, PERF-04..07 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | New stage disabled and capped at zero gain | NOT_STARTED |
| PKG-08 | REQ-006/007/009 | Planned field-aware Hot/Warm/Deferred legacy update classifier | LIVE-13..19, REG-01..12, PERF-08 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | Preserve current defer path for unsupported changes | NOT_STARTED |
| PKG-09 | REQ-002/008 | Planned PEQ graph and modular full/compact editing views | UI-01..09, PRE-01..09, REG-12 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Open | Keep existing DSP controls reachable during UI transition | NOT_STARTED |
| PKG-10 | REQ-008/009/011/012 | Planned split integration, end-to-end gates, rollback and docs | UI-10..11, PERF-01..12, REG-01..12 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | HARDWARE_NOT_RUN | Open | No merge; hardware remains a release blocker | NOT_STARTED |

## Independent review tracks

| Track | Scope | Agent/model | Findings | Fix SHA / retest | Status |
|---|---|---|---|---|---|
| Native signal and acoustics | DSP math, filter stability, headroom, latency, denormals | Not assigned | None yet | None | NOT_STARTED |
| JNI and renderer | ownership, generation, thread serialization, lifecycle | Not assigned | None yet | None | NOT_STARTED |
| UI and saved data | Compose flows, presets, workspace, future schema, accessibility | Not assigned | None yet | None | NOT_STARTED |
| QA and integration | coverage, CI skips, privacy, rollback, hardware status | Not assigned | None yet | None | NOT_STARTED |
