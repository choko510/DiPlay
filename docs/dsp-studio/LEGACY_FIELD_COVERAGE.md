# Legacy DSP field coverage

This map is reconciled against `DspAudioProfile` and the production `DspSettingsScreen` at source head `fe083f4f524fa6ee1038860343e4f100aee8dad6`. It records where each legacy profile value is edited in the five-tab UI. It does not claim that every control has a dedicated instrumented assertion or that the CarPlay Split layout was verified on a target device.

| Legacy field | New screen and control path | Evidence / remaining gap |
|---|---|---|
| `id`, `name` | Studio → profile selection and profile/preset save flow | Profile selection/save paths are implemented; exact rename and round-trip screen assertions remain partial. |
| `enabled` | Studio → DSP Master toggle | Covered by runtime and UI contract tests; device playback remains NOT_RUN. |
| `preampDb` | EQ → Preamp value control and graph response | Value editing is implemented; live preamp ramp is unavailable, so a committed-track preamp-only edit defers. |
| `autoHeadroomEnabled`, `autoHeadroomMarginDb` | Advanced → Headroom controls and calculated reduction status | Resolver/headroom tests cover software bounds; target true-peak/audio evidence remains NOT_RUN. |
| `eqBands[]` (`type`, `frequencyHz`, `gainDb`, `q`, `enabled`) | EQ → 15-band response graph, band selection, numeric controls, and band type controls | Graph and pointer-edit tests exist; full overlap/Q/type/frequency-tolerance evidence remains partial. User-visible PEQ/Cabin capacity remains 15. |
| `bass` (`enabled`, `gainDb`, `frequencyHz`) | Studio → Bass Shelf controls | Bass Shelf uses a separate internal sixteenth graph filter and supports opt-in LOCAL_WARM; API 35 Native and renderer tests pass. |
| `compressor` (`enabled`, `thresholdDb`, `ratio`, `attackMs`, `releaseMs`, `kneeDb`, `makeupDb`) | Dynamics → Compressor card | Controls and config tests exist; envelope continuity and target listening remain partial. |
| `stereoWidth` | Spatial → Width control | Width/M/S software path is implemented; phase/listening/target evidence remains partial. |
| `monoBass` (`enabled`, `cutoffHz`) | Spatial → Mono Bass control | Control and resolver/native path are present; target phase/listening evidence remains NOT_RUN. |
| `convolver` (`enabled`, `impulseResponseId`, `wet`) | Advanced → Convolver/IR status, import, and preset actions | IR reference and storage paths are validated; topology/latency-changing in-track edits defer. Target IR listening remains NOT_RUN. |
| `multiband` (`enabled`, crossover frequencies, low/mid/high compressor values) | Dynamics → Multiband card with per-band compressor controls | Config and renderer tests exist; crossover/phase history remains deferred when continuity is required. |
| `dynamicEq` (`enabled`, five bands of enable/mode/frequency/Q/threshold/ratio/attack/release/max boost/max cut) | Dynamics → Dynamic EQ card | Five-band controls and Native config path are implemented; detector-state continuity is not claimed for frequency changes. |
| `limiter` (`enabled`, `thresholdDb`, `releaseMs`) | Dynamics → Limiter card | Config, headroom, and Native tests cover software behavior; target true-peak evidence remains NOT_RUN. |
| `seatAlignment` (`enabled`, `preset`, `nearSpeaker`, `distanceDifferenceCm`, left/right trims, left/right polarity) | Spatial → Seat Alignment controls | Config, delay, and headroom coverage exists; active delay changes defer and vehicle-level alignment remains NOT_RUN. |
| `advancedMs` (enable, crossover, center focus, mid/side gains and widths, side-compressor fields) | Spatial → Advanced M/S controls | Controls and parameter serialization are present; phase and state-continuity evidence remains partial. |
| `cabinCorrection` (`enabled`, measurement summary, up to three notches with frequency/gain/Q) | Advanced → Cabin Correction measurement and notch controls; EQ graph overlay | Shared 15-slot PEQ/Cabin capacity is preserved. Cabin local warm-up is not implemented and safely defers during committed playback. |
| `harmonic` (enable, mode, quality, exciter/virtual-bass enable/amount/cutoff, level match) | Advanced → Harmonic Engine controls | Runtime validation and headroom tests exist; quality/latency changes defer when continuity or latency would change. Target listening remains NOT_RUN. |

## Studio-only fields

The v7 `DspAudioProfile` remains unchanged. The schema-v1 Studio workspace adds Macro values, Smart Loudness settings/calibration, Dynamic Bass settings, and selected module-preset IDs. They are edited from Studio, Dynamics, and Advanced surfaces and are stored independently of the legacy profile. Their per-field screen and process-death evidence remains tracked in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md).

Physical Split geometry/touch/session evidence remains `NOT_RUN`; the standalone Compose instrumentation does not establish CarPlay host behavior. See [RUNBOOK.md](RUNBOOK.md).
