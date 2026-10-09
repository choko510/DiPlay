# Cabin calibration import

Cabin correction uses user-imported measurements or manual cut filters. It never infers a car resonance from music. Measurement files live under app-private storage in `dsp/cabin-calibrations`; profiles store only a calibration ID, format version, and approved cuts. If a referenced file is missing or invalid, Cabin is bypassed while the rest of DSP remains active.

## Accepted UTF-8 CSV

Metadata comments must precede the exact two-column header. Frequencies must be strictly increasing; duplicate rows, locale-formatted numbers, nonfinite values, and unknown metadata are rejected.

```csv
# format=diplay-cabin-calibration-v1
# formatVersion=1
# sampleRate=48000
# position=front-seat
# measuredAt=2026-10-09T12:00:00Z
# microphoneCorrectionApplied=true
frequencyHz,magnitudeDb
20,-3.2
40,-1.0
82,5.8
117,3.9
250,-2.0
```

The dedicated JSON equivalent uses the same `format`, `formatVersion`, `sampleRate`, `position`, `measuredAt`, and `microphoneCorrectionApplied` fields, plus a `points` array. Each point is either `{ "frequencyHz": 82, "magnitudeDb": 5.8 }` or `[82, 5.8]`. Duplicate property names are rejected.

Inputs are limited to 512 KiB and 8,000 points. Points must be finite, sorted, above 20 Hz, and below the measurement Nyquist frequency. Analysis uses the 40–250 Hz range. Guided recording is unavailable because this app has no verified calibrated microphone/input path.

## Candidate detection and correction

The detector resamples the response on a logarithmic grid at 24 points per octave, smooths across 1/12 octave, and compares local peaks with nearby response baselines. Candidates need at least 3 dB of local prominence, an estimated Q from 0.5 to 8, and are limited to five. Near-duplicate peaks are merged. Candidate confidence is a prominence-based heuristic, not a microphone SNR or a statistical guarantee.

Candidates are stored disabled until the user approves them. Static correction uses only PEAK cuts from 0 to -6 dB. The before/after preview shows measured magnitude and predicted static cuts. Manual cuts are separate from the imported data. Optional dynamic control reuses the bounded Dynamic EQ detector only at approved measurement frequencies, with a fixed -24 dB threshold, 2:1 ratio, 30 ms attack, 300 ms release, and a user limit of 0–3 dB additional cut. It does not continuously learn from music.

Cabin A/B bypass is a temporary 20 ms crossfade around the complete Cabin layer and is not serialized. The Native node continues updating its filter/detector histories while bypassed so returning to the corrected signal does not start from cold state.

A correction measured at one seat is position-specific; it is not claimed to optimize every seat.
