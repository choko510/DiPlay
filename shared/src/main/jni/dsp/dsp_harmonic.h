#ifndef XCERTPLAY_DSP_HARMONIC_H
#define XCERTPLAY_DSP_HARMONIC_H

#include "dsp_biquad.h"

#define DSP_HARMONIC_VALUES_COUNT 10
#define DSP_HARMONIC_MAX_FIR_TAPS 33
#define DSP_HARMONIC_RING_FRAMES 34
#define DSP_HARMONIC_MAX_LATENCY_FRAMES 16
#define DSP_HARMONIC_DELAY_FRAMES (DSP_HARMONIC_MAX_LATENCY_FRAMES + 1)

typedef struct {
    int enabled;
    int mode;
    int taps;
    int exciter_enabled;
    double exciter_amount;
    double exciter_cutoff_hz;
    int virtual_bass_enabled;
    double virtual_bass_amount;
    double virtual_bass_cutoff_hz;
    int level_match_enabled;
} dsp_harmonic_config;

typedef struct {
    float upsample_ring[DSP_HARMONIC_RING_FRAMES];
    float delta_ring[DSP_HARMONIC_RING_FRAMES];
    int upsample_write_index;
    int delta_write_index;
} dsp_harmonic_oversampler_state;

typedef struct {
    int active;
    int channels;
    int mode;
    int taps;
    int latency_frames;
    int exciter_enabled;
    int virtual_bass_enabled;
    int level_match_enabled;
    float exciter_amount;
    float virtual_bass_amount;
    float level_match_gain;
    float fir_coefficients[DSP_HARMONIC_MAX_FIR_TAPS];
    dsp_harmonic_oversampler_state branches[2][2];
    float dry_delay[2][DSP_HARMONIC_DELAY_FRAMES];
    int dry_write_index;
    dsp_biquad exciter_high_pass;
    dsp_biquad virtual_bass_low_pass;
    dsp_biquad exciter_dc_block;
    dsp_biquad virtual_bass_dc_block;
} dsp_harmonic;

int dsp_harmonic_prepare(
    dsp_harmonic *harmonic,
    int sample_rate,
    int channels,
    const dsp_harmonic_config *config);
void dsp_harmonic_reset(dsp_harmonic *harmonic);
void dsp_harmonic_process_frame(dsp_harmonic *harmonic, float *left, float *right, int channels);
void dsp_harmonic_flush_denormals(dsp_harmonic *harmonic);
int dsp_harmonic_latency_frames(const dsp_harmonic *harmonic);

#endif
