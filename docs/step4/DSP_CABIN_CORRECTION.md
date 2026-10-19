# DSP-03 Cabin Correction

Cabin Correction imports an external frequency-response CSV. The application does not estimate cabin response from music playback and does not require a built-in measurement microphone.

## CSV format

Use UTF-8 text with the following metadata and column header:

```text
# format=diplay-cabin-v1
# noise_floor_db=-80
# clipped=false
frequency_hz,level_db
20,70
...
```

Include 20–256 finite response points covering 20–300 Hz at 1/6-octave spacing. `noise_floor_db` is the measurement noise floor in dB; every included point must be at least 12 dB above it. Set `clipped=true` if the measurement chain clipped; clipped data is rejected. The parser returns `CALIBRATION_UNAVAILABLE` for missing metadata, inadequate coverage, irregular spacing, insufficient signal/noise margin, clipping, or malformed data.

## Applying correction

The importer suggests up to three bounded PEQ cuts from local measured peaks. Suggestions start disabled and can be edited or removed. Users can also add manual cuts without importing a measurement. The user must enable each cut and then explicitly enable Cabin Correction.

Approved cuts occupy the existing 15-band PEQ budget; Cabin Correction does not add another native filter stage. The profile stores a measurement summary, its SHA-256 digest, and approved notch values, but not the source CSV. Schema v5 profiles migrate with Cabin Correction disabled in schema v6. Dynamic notch tracking and any estimate derived from program audio are excluded.
