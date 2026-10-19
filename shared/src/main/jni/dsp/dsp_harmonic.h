#ifndef XCERTPLAY_DSP_HARMONIC_H
#define XCERTPLAY_DSP_HARMONIC_H

#include "dsp_biquad.h"

#include <stddef.h>

#define DSP_HARMONIC_VALUES_COUNT 9
#define DSP_HARMONIC_MAX_FIR_TAPS 33
#define DSP_HARMONIC_DRY_DELAY_CAPACITY 17
#define DSP_HARMONIC_OVERSAMPLE_FACTOR 2

typedef enum {
    DSP_HARMONIC_MODE_EVEN = 0,
    DSP_HARMONIC_MODE_ODD = 1,
    DSP_HARMONIC_MODE_MIXED = 2,
} dsp_harmonic_mode;

typedef enum {
    DSP_HARMONIC_QUALITY_LOW = 0,
    DSP_HARMONIC_QUALITY_BALANCED = 1,
    DSP_HARMONIC_QUALITY_HIGH = 2,
} dsp_harmonic_quality;

typedef struct {
    int high_exciter_enabled;
    double high_exciter_amount;
    double high_exciter_crossover_hz;
    int virtual_bass_enabled;
    double virtual_bass_amount;
    double virtual_bass_crossover_hz;
    int mode;
    int quality;
    int level_match_enabled;
} dsp_harmonic_config;

typedef struct {
    int taps;
    float up_coefficients[DSP_HARMONIC_MAX_FIR_TAPS];
    float down_coefficients[DSP_HARMONIC_MAX_FIR_TAPS];
    float up_history[2][DSP_HARMONIC_MAX_FIR_TAPS];
    float down_history[2][DSP_HARMONIC_MAX_FIR_TAPS];
    int up_write_index[2];
    int down_write_index[2];
    float dc_block_coefficient;
    float dc_previous_input[2];
    float dc_previous_output[2];
} dsp_harmonic_core;

typedef struct {
    int sample_rate;
    int channels;
    int active;
    int high_exciter_enabled;
    int virtual_bass_enabled;
    int mode;
    int latency_frames;
    float high_exciter_amount;
    float virtual_bass_amount;
    float level_match_gain;
    int dry_write_index;
    float dry_delay[2][DSP_HARMONIC_DRY_DELAY_CAPACITY];
    dsp_biquad high_exciter_high_pass;
    dsp_biquad virtual_bass_low_pass;
    dsp_biquad virtual_bass_harmonic_high_pass;
    dsp_harmonic_core high_exciter_core;
    dsp_harmonic_core virtual_bass_core;
} dsp_harmonic;

int dsp_harmonic_prepare(dsp_harmonic *harmonic, int sample_rate, int channels, const dsp_harmonic_config *config);
void dsp_harmonic_reset(dsp_harmonic *harmonic);
void dsp_harmonic_close(dsp_harmonic *harmonic);
int dsp_harmonic_latency_frames(const dsp_harmonic *harmonic);
void dsp_harmonic_process_frame(dsp_harmonic *harmonic, float *left, float *right, int channels);
void dsp_harmonic_flush_denormals(dsp_harmonic *harmonic);

#endif
