# Harmonic Engine

The Harmonic Engine is off by default and contains two independent effects: a band-limited High Exciter and a measured-band Virtual Bass generator. Both add harmonics to the existing stereo signal. Virtual Bass does not create an independent subwoofer output.

## Signal path

The native graph runs Harmonic after Cabin Correction and Advanced M/S, and before Convolver and the Safety Limiter. Each enabled branch has prepared biquad band limits and an independent oversampling history. The shared bounded waveshaper core derives even, odd, or mixed residuals; Virtual Bass controls its second- and third-harmonic amounts separately. The residual passes through a DC blocker and anti-alias decimator before it is added to the delayed dry signal.

The process thread reuses all filter, FIR, envelope, and delay state. Coefficients and buffers are prepared before the graph is published. 2× uses a 129-tap Blackman-windowed sinc and 4× uses 257 taps. Each FIR stage contributes 32 input frames, so both quality settings report a fixed 64-frame latency; the dry ring uses that same timeline. A/B bypass changes only an atomic target and crossfades to latency-aligned dry over 20 ms while processing state continues to run.

The configured sample-rate range is 16–192 kHz. At lower rates both effects are bypassed. A branch is independently bypassed when its configured band reaches 0.42 times the sample rate. No music-only cabin or room measurement is performed.

## Safety and headroom

Model, UI, and native validation share the same limits: exciter input 1–16 kHz, Virtual Bass fundamental band 35–80 Hz, drive 0–8, amount and wet 0–1, and exciter output compensation −12 to +6 dB. The nonlinear input is clamped before harmonic generation. Auto Headroom uses conservative unit bounds for each residual component, includes maximum compensation, and keeps the same preamp attenuation during temporary A/B bypass so the comparison level does not change. The existing Safety Limiter remains the final protection stage.

Harmonic quality and A/B bypass are not saved as temporary bypass state. The selected quality and both effect configurations are stored in profile schema v4; a master-off graph still bypasses all DSP processing.

## Native measurements

The host C suite verifies that EVEN and ODD modes cancel the generated fundamental residual and produce their expected second/third harmonics. At 48 kHz with a 10 kHz ODD-mode input, the 30 kHz product would fold to 18 kHz without adequate rejection. The measured folded component was −129.26 dBc at 2× and −129.56 dBc at 4×, below the −50 dBc acceptance limit. The suite also checks 44.1/48/96 kHz, mono/stereo, silence, channel isolation, fixed latency, wet-off identity, and A/B transition continuity.

These results are deterministic host measurements, not a subjective sound-quality claim. Android pipeline p99, device CPU headroom, TS10 audio, CarPlay continuity, and long-duration dropout measurements are tracked by the integration audit.
