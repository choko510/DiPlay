# Advanced Mid/Side

Advanced M/S extends the existing `dsp_spatial` node. The legacy global Stereo Width and Mono Bass controls remain in that node and keep their original behavior while Advanced M/S is off. There is no second serial width stage.

## Signal path

The node forms `Mid = (L + R) / 2` and `Side = (L - R) / 2`. Mono Bass filters Side once. Optional Mid and Side biquads, branch gains, and the Mid center-band filter run before the Side width network. The center-band control is a broad 1.5 kHz peaking filter at Q 0.5; it adjusts the full Mid signal and does not isolate vocals or instruments.

The width network uses complementary first-order all-pass splits. Each split calculates `(x + A(x)) / 2` and `(x - A(x)) / 2`; their sum reconstructs `x`, so equal band widths with neutral EQ/gain are an identity. The low, mid, and high Side bands are weighted by `clamp(globalWidth * bandWidth, 0, 2)`. No FFT or integer delay is introduced. Crossover and EQ coefficients are prepared before processing.

## Preview and diagnostics

Mid-only, Side-only, and mono fold-down monitoring are temporary. Native monitor targets are atomic and transition over 20 ms; the profile serializer does not save them. Correlation is measured over the latest native process block. The settings screen refreshes this diagnostic and output peak risk from the renderer's periodic diagnostics snapshot.

Mono streams bypass this stereo-only node. Advanced M/S changes that require filter-state preparation defer to the next MEDIA stream; temporary monitor changes update the active graph in place.

## Verification

The native suite compares neutral Advanced M/S against legacy global width and Mono Bass sample by sample, measures low/mid/high Side gain, checks Mid-only separation, and verifies the live monitor crossfade. Android instrumentation checks neutral reconstruction, correlation, and JNI monitor updates.
