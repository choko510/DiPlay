# DSP-03 Measured Cabin Correction

DSP-03 accepts an external low-frequency response CSV and suggests a small set of bounded static notches. It does not analyze the playing signal or claim an in-car measurement. Suggestions stay inactive until the user stores them in a profile and enables Cabin Correction.

## CSV and quality gates

The supported format is UTF-8 CSV with a header and quality metadata:

```text
# noise_floor_db=-60
# clipped_samples=0
frequency_hz,level_db
20.0,71.2
...
300.0,69.8
```

REW or another measurement tool can supply the frequency/level rows; add the measured noise-floor and clipping metadata when exporting. The importer validates the declared metadata but cannot independently inspect the microphone or capture hardware. It does not persist the source file path.

The importer caps the file at 256 KiB and the source data at 4,096 rows. It requires sorted finite frequency/level pairs, at least 32 points from 20 to 300 Hz, no gap larger than 1/6 octave in that band, a noise floor at least 10 dB below each analyzed point, and zero clipped samples. Missing metadata, clipping, high noise, insufficient span/resolution, or no significant peaks returns `CALIBRATION_UNAVAILABLE` with a reason and leaves the profile unchanged.

At most three local peaks with at least 4 dB prominence become suggestions. Suggested notches are limited to 20–300 Hz, -3 to -9 dB, and Q 0.7–10. The user can edit or remove them. Cabin Correction reuses the existing PEQ peaking filters as bounded bell cuts and consumes existing PEQ slots; the total active band count remains at most 15. It adds no native node or algorithmic latency. Dynamic notch tracking is not included because this stage has no continuous calibrated microphone input.

## Verification

| Gate | Result | Evidence |
|---|---|---|
| Shared/common unit tests | `PASS_LOCAL`: 476/139 | Full tasks forced to rerun; zero failures/errors/skips, including CSV quality/peak, schema v6 migration, profile persistence, PEQ capacity, and headroom coverage |
| Android instrumentation | `PASS_EMULATOR`: 18/18 | API 36.1 x86_64 AVD; approved cabin PEQ notch reached the native engine and the four-rate/channel benchmark matrix passed |
| Mobile/automotive lint and builds | `PASS_LOCAL` | Both app lints and debug/benchmark APK assembly passed |
| JNI mappings and public tree | `PASS_LOCAL` | Both benchmark R8 mappings verified; credential scan passed |
| Cabin response or target-car acoustics | `NOT_RUN` | Requires an external measured file and TS10/K706/iPhone listening; no vehicle response is claimed |

### Cabin3Notches API 36.1 emulator performance

Each entry is the median of three 256-block runs at 128 frames; `max` is the largest single-block value across the runs. Native and full Kotlin/native pipeline timings are separate; the IIR notch filters add no algorithmic latency.

| Rate/channel | Native p50 / p95 / p99 / max (µs) | Pipeline p50 / p95 / p99 / max (µs) |
|---|---:|---:|
| 44.1 kHz mono | 100 / 100 / 140 / 238 | 280 / 320 / 400 / 511 |
| 44.1 kHz stereo | 140 / 160 / 200 / 311 | 460 / 520 / 580 / 761 |
| 48 kHz mono | 100 / 100 / 140 / 180 | 260 / 320 / 360 / 421 |
| 48 kHz stereo | 140 / 160 / 180 / 402 | 420 / 500 / 580 / 734 |

This importer stores only measurement summary metadata and approved notch values in the profile, not the source CSV or its path.
