# DSP-04 Harmonic Engine

The opt-in Harmonic Engine has a high-frequency exciter and a virtual-bass branch. Each branch uses a bounded even, odd, or mixed polynomial shaper between 2× Blackman-windowed FIR interpolation and decimation. The FIR quality choices use 9, 17, or 33 taps and report 4, 8, or 16 input-frame latency; the dry path is delayed by the same fixed amount. A DC blocker follows the even-harmonic paths.

Both branches default off. Exciter and bass amounts are capped at 0.5 each, Auto Headroom budgets their maximum additive gain, level matching is enabled by default, and the existing final limiter remains last. Prepared FIR coefficients, fixed rings, and filter state are allocated before the render loop. Active changes wait for the next track generation.

The alias regression drives a 14 kHz tone through the odd exciter and measures the 6 kHz folded third-harmonic component after 2× FIR filtering. Native tests also cover quality latency, block-boundary invariance, and disabled-path parity. The main source tree contains no SoundRiser or Realizer stage; this implementation does not layer on a second instance of either.

No THD, sound-quality, or in-car benefit claim is made until target-speaker measurements and listening checks are recorded.
