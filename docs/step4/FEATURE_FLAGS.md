# STEP4 feature and diagnostic controls

This file inventories fixed-main behavior. PR-00 adds no runtime feature flag and does not change a default. Conditional STEP4 behavior remains off until its evidence gate passes.

| Control | Main default / scope | Current behavior | Rollback or gate |
|---|---|---|---|
| Debug logs / connection trace | Off by default; app preference `debug_logs_enabled` | When enabled, connection events are appended to bounded app-private files and included in the exported diagnostics report | Turn the preference off; retain current connection behavior. Sharing a raw capture still requires redaction |
| NCM diagnostic profile | `AUTO`; saved choice is read only in a debuggable build | Async persistent bulk-IN; status polling when the endpoint exists; 100 ms pre-ready OUT timeout | Debug-only profiles include `NO_STATUS_POLLING`, alternate timeout experiments, synchronous bulk-IN and forced pairs. Do not promote an experiment to release default without its stated A/B evidence |
| Global DSP master | Off by default (`enabled=false`); selected profile defaults to `flat` | `DspProfileRuntime` persists the master separately from the profile. `DspStreamPolicy` controls eligible stream roles | Turn master off to return to the legacy bypass. New DSP nodes must each default off and preserve existing schema behavior |
| In-app CarPlay + YouTube Split | Full-screen CarPlay is the initial host layout; split is user-selected | Existing local split ratio defaults to 55:45. Resizing can use the established restart/handoff path | Keep the safe full-screen/restart fallback. Same-session 20-cycle acceptance is still `NOT_RUN` |
| Split performance tracing | Enabled in debuggable builds; non-debug variants require metadata `com.shilapi.xcertplay.host.SPLIT_PERFORMANCE_TRACING=true` | Trace sections/counters cover Split, GeckoView, profile resolution and CarPlay lifecycle | No production behavior is controlled by these counters; no target-device performance claim follows from enabling them |
| AirPlay ViewArea declaration | No multi-area experiment switch in current `main`; `/info` emits one ViewArea, index 0 | The session advertises the existing `viewAreas` feature and retains the legacy single-area geometry | Dynamic ViewArea changes remain unimplemented/unverified; do not send guessed commands or enable two-area behavior without phone acceptance and frame-size evidence |
| Local auth asset injection | Build-time environment variable `DIPLAY_AUTH_ASSETS_DIR`; unset in CI and ordinary builds | When explicitly supplied, only the expected local offline-MFi identity/certificate files are permitted by `rejectBundledCredentials` | Keep accessory identity and private key material out of the repository and APK source tree |

## Not present in fixed main

There is no production flag for EX-01 provenance, EX-05 delayed-OUT retry, Dynamic ViewArea, decoder low-latency/vendor tuning, Seat Focus, Advanced M/S, Cabin measurement correction, or Harmonic Engine. These are separate planned work items, not enabled capabilities. `FEATURE_FLAGS.md` should be updated as each stage adds its own default-off switch and rollback behavior.
