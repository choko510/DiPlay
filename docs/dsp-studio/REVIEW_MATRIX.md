# DSP Studio review matrix

Review status is based on the accessible goal and initial test matrix. The referenced detailed specification file was unavailable. Line references below are from the final source tree; the final integration commit SHA is recorded in [FINAL_IMPLEMENTATION_REPORT.md](FINAL_IMPLEMENTATION_REPORT.md).

## Package status

| Package | Scope | Implementation state | Automated evidence | Remaining gate | Status |
|---|---|---|---|---|---|
| PKG-00 | Baseline and evidence | Baseline recorded; parent PR #45 remains open Draft and untouched. | Baseline source gates and latest static checks recorded. | Full CI at final head remains pending. | COMPLETE |
| PKG-01 | Workspace, macros, tuning resolver | Schema-v1 workspace, 50-neutral Macro controls, bounded resolver budget. | Unit/resolver/persistence/native tests. | Target listening acceptance. | COMPLETE |
| PKG-02 | Native stages and graph | Fixed-state Macro, Smart Loudness, Dynamic Bass stages; shared PEQ capacity retained. | Native tests and ASan/UBSan. | Target acoustic, p99, and CPU/RSS evidence. | COMPLETE |
| PKG-03 | Hot/Warm/Deferred renderer | Generation/owner/format/context fencing, one-shot ACK completion, stale/close/failure handling. | Renderer, Native, JNI tests; stale rebind regression added. | Device playback and stress evidence. | COMPLETE |
| PKG-04 | Persistence and module presets | Independent workspace/module stores, validation, preview/save/cancel, A/B and history. | Repository/session/runtime unit suites. | Connected instrumentation unavailable. | COMPLETE |
| PKG-05 | Macro UI and status | Full and compact screens, Macro controls and renderer state. | UI contract tests; final screen instrumentation source compiles. | Connected screen interaction and host acceptance. | PARTIAL |
| PKG-06 | Smart Loudness | Calibrated route-bound AUTO plus MANUAL/FIXED; no false AUTO when readings are not trustworthy. | Source/resolver/native tests. | TS10/K706 route calibration. | PARTIAL |
| PKG-07 | Dynamic Bass | Pre-own-stage low-band detector, gate, smoothed linked stereo gain, shared boost budget. | Native signal tests and resolver tests. | Target listening, THD, p99, and CPU/RSS. | PARTIAL |
| PKG-08 | Live compatibility | Changed-field planner, renderer-owned Hot path, stateful/latency deferral. | Renderer, policy, PCM, JNI/native tests. | CarPlay, format-change, A/V, and long-run device evidence. | PARTIAL |
| PKG-09 | EQ and advanced UI | Graphical PEQ and editor panes with shared 15-band capacity. | Editor pane and UI contract tests. | Device layout/touch acceptance. | PARTIAL |
| PKG-10 | Integration and rollback | Six Luna child PRs integrated only into `codex/dsp-studio-final`; PR #52 stacked on #45. | Integration tests, safety docs, reviewer follow-ups. | Final connected suite and target hardware. `DspStudioFeatureFlags` constants are unused and are not operational kill switches. | PARTIAL |

## Four independent review tracks

| Track | Scope and finding | Disposition / evidence | Remaining status |
|---|---|---|---|
| Native signal and algorithms | Found a Kotlin/JNI 64-bit generation versus 31-bit token validation mismatch. | Generation token masking and validation were made coherent in Native/JNI; invalid hot configurations are rejected before graph mutation. Native tests plus ASan/UBSan passed; follow-up found no remaining P0/P1/P2 source issue. | Target audio quality and performance NOT_RUN. |
| Kotlin/JNI synchronization and lifetime | Flagged pending HOT apply races among ACK, renderer close, and stale ownership. Follow-up requested a direct stale context-rebind race test. | Pending apply completion is a one-shot CAS; close/failure/rebind tests verify FAILED cannot be overwritten by a late ACK. Follow-up confirmed no remaining P0/P1/P2 source issue. | Instrumentation/device stress NOT_RUN. |
| UI and saved data | Flagged persisted preference side effects and missing proof that draft edits remain unapplied before Preview. | Screen instrumentation now creates an isolated runtime with test-only files/preferences and asserts the runtime config equals its initial snapshot after edit and before Preview. Final Kotlin Android-test compile passed; reviewer confirmed both findings closed. | Instrumentation execution and actual CarPlay host flow NOT_RUN. |
| QA and integration | Flagged missing screen-flow coverage and stale planned-only matrices. | Added standalone FULL/COMPACT_SPLIT Preview/Cancel test; replaced planning matrices with execution and review ledgers. QA follow-up found no P0/P1 source-correctable issue. | Actual 55:45 host geometry/session/Surface/touch continuity remains a P2 hardware/e2e gap; documentation marks it NOT_RUN. |

## Priority summary and rollback

- Open source-correctable P0/P1 findings: **none identified by the four review tracks**.
- Remaining P2/evidence gaps: connected instrumentation could not run; test does not construct `CarPlayHostActivity`; TS10/K706/iPhone geometry, Surface/session/touch continuity, route calibration, acoustics, latency, CPU/RSS, A/V, Siri/call/navigation, and soak have not been measured.
- `DspStudioFeatureFlags` defines constants but there are no production references. Do not treat those constants as rollback gates. Rollback for a review build is to keep DSP Master OFF and the new modules disabled/default-neutral; unsupported stateful or latency-changing changes remain DEFERRED. A newly started AudioTrack preserves the legacy master-off PCM16 path; mid-track disable retains its existing latency behavior.
- Review disposition: `IMPLEMENTED_REVIEW_READY / MERGE_NO_GO_HARDWARE_PENDING`. PR #52 must remain unmerged until a human review and hardware evidence close the listed gates. PR #45 and `main` are not to be merged.
