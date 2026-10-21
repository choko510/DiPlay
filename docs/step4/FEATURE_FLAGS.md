# STEP4 feature and experiment flags

## Fixed-main defaults

| Existing control | Main default / scope | Owner | Evidence |
|---|---|---|---|
| NCM diagnostic experiment profile | `AUTO`; async persistent bulk-IN, status polling when the endpoint exists, 100 ms pre-ready OUT timeout | `NcmDiagnosticProfile` / `NcmDiagnosticProfileStore` | `shared/.../transport/NcmDiagnosticProfile.kt`, `NcmDiagnosticProfileTest` |
| NCM `NO_STATUS_POLLING` comparator | Debug-app setting only; production/non-debug loads `AUTO` | `NcmDiagnosticProfileStore` | `shared/.../transport/NcmDiagnosticProfile.kt` |
| DSP master | Disabled unless the persisted master preference is true; an in-memory `DspRuntimeConfigStore` starts disabled | `DspProfileRuntime` | `common/.../dsp/DspProfileRuntime.kt`, `common/.../dsp/DspRuntimeConfigStore.kt` |
| YouTube profile context | Existing Split resolves an iPhone identity to a versioned SHA-256-derived context ID | `YoutubeDeviceProfileManager` | `common/.../youtube/YoutubeDeviceProfile.kt` |
| Dynamic ViewArea switching | No dynamic coordinator or new wire command in fixed main; keep existing `/info` behavior | AirPlay info/session path | `shared/.../airplay/AirPlayInfoPlist.kt`, `AirPlayInfoPlistTest` |
| New `WIRED_PATH_V1` recorder | Not present at fixed main | Future EX-01 | Must remain opt-in and payload-free when implemented. |

The main branch's existing two-display `/info` fields include the optional cluster display, not a negotiated multi-ViewArea resize protocol. Existing YouTube Split state is not treated as proof that one CarPlay session survives 20 physical full/Split cycles.

## Required defaults for planned changes

These flags are baseline constraints, not claims that the feature already exists in main.

| Planned capability | Required default while unverified | Enable condition |
|---|---|---|
| EX-01 packet-provenance logging | OFF | Explicit debuggable/diagnostic opt-in; no raw payload, MAC, IP, SSID, or pairing data. |
| EX-04 10-second USB re-enumeration probe | OFF / absent | Same-node, same-generation stall reproduced; detach and newer-generation callbacks are no-ops. |
| PR-02 API28+ queue-size fallback | Legacy 32 KiB behavior | Device-present queue failure reproduced and requeue ownership proven. API26/27-only workaround is out of scope at minSdk 28. |
| PR-03 timeout experiments | Preserve current `AUTO` 100 ms pre-ready OUT behavior | Same-device A/B supports a specific value. |
| EX-05 delayed OUT recovery or extra StartSession | OFF / absent | The six EX-01 causal conditions are observed on TS10/K706, and virtual-time reachability against the existing 15-second watchdog is proven. |
| EX-06 status endpoint changes | Preserve current `AUTO` polling and single `requestWait` owner | Same-device A/B shows benefit; no second completion consumer without a unified dispatcher. |
| EX-07 IPv4/scope/peer-MAC changes | Preserve current IPv6 path | Causal packet/bind/generation evidence identifies the fault. |
| PR-05 multi-ViewArea advertisement | Existing `/info` output remains the fallback | Enable only after explicit iPhone acceptance and real output-size evidence. |
| PR-06 DYNAMIC ViewArea switching | OFF / absent | iPhone acceptance, dimensions, audio continuity and same-session behavior are observed. |
| PR-06 LOCAL no-restart scaling | Separate opt-in mode during development; legacy restart remains available | Same Controller/Session/stream survives target-device geometry and touch tests. |
| PR-07 codec/surface changes | Current codec behavior | Add codec-specific opt-in only after capability and device validation. |
| DSP-01..04 new nodes | OFF | Each node preserves the master-off PCM/latency baseline and passes its own measurements. |

Any later flag must have an owner, persisted/default semantics, test ID, migration rule if stored, and a rollback path recorded before it is enabled.
