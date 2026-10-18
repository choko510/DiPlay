#ifndef XCERTPLAY_DSP_HARMONIC_H
#define XCERTPLAY_DSP_HARMONIC_H

#include "dsp_biquad.h"

#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>

#define DSP_HARMONIC_FIR_MAX_TAPS 257U
#define DSP_HARMONIC_FILTER_COUNT 4U
#define DSP_HARMONIC_COEFFICIENT_COUNT \
    (DSP_HARMONIC_FILTER_COUNT * DSP_BIQUAD_COEFFICIENT_COUNT)
#define DSP_HARMONIC_NATIVE_VALUE_COUNT 18U
#define DSP_HARMONIC_MIN_SAMPLE_RATE 16000
#define DSP_HARMONIC_MAX_BAND_SAMPLE_RATE_FRACTION 0.42
#define DSP_HARMONIC_FIR_GROUP_DELAY_FRAMES 32
#define DSP_HARMONIC_ALGORITHM_LATENCY_FRAMES (2 * DSP_HARMONIC_FIR_GROUP_DELAY_FRAMES)

typedef enum {
    DSP_HARMONIC_MODE_EVEN = 0,
    DSP_HARMONIC_MODE_ODD = 1,
    DSP_HARMONIC_MODE_MIXED = 2,
} dsp_harmonic_mode;

typedef struct {
    int enabled;
    int bypassed;
    int quality;
    int high_exciter_enabled;
    double high_exciter_frequency_hz;
    double high_exciter_drive;
    double high_exciter_amount;
    int high_exciter_mode;
    double high_exciter_wet;
    double high_exciter_compensation_db;
    int virtual_bass_enabled;
    double virtual_bass_fundamental_low_hz;
    double virtual_bass_fundamental_high_hz;
    double virtual_bass_amount;
    double virtual_bass_drive;
    double virtual_bass_second_amount;
    double virtual_bass_third_amount;
    double virtual_bass_wet;
} dsp_harmonic_config;

typedef struct {
    int active;
    int sample_rate;
    int channels;
    int quality;
    dsp_harmonic_config config;
    int oversampling_factor;
    _Atomic int bypass_target;
    int observed_bypass;
    uint32_t bypass_transition_frames;
    uint32_t bypass_transition_position;
    float bypass_mix;
    float bypass_transition_start;
    float bypass_transition_target;
    int latency_frames;
    size_t fir_tap_count;
    size_t upsample_history_capacity;
    size_t fir_position[2];
    float *fir_history[2];
    float *upsample_history[2][2];
    float *dry_ring;
    size_t dry_capacity_frames;
    size_t dry_write_position;
    double fir_coefficients[DSP_HARMONIC_FIR_MAX_TAPS];
    dsp_biquad filters[DSP_HARMONIC_FILTER_COUNT];
    size_t upsample_position[2][2];
    float previous_residual_input[2];
    float residual_dc_output[2];
    float envelope_power[2][2];
    float envelope_smoothing;
    float high_mix;
    float bass_mix;
} dsp_harmonic;

int dsp_harmonic_validate_config(const dsp_harmonic_config *config);
int dsp_harmonic_prepare(
    dsp_harmonic *harmonic,
    int sample_rate,
    int channels,
    const dsp_harmonic_config *config,
    const double *filter_coefficients,
    size_t filter_coefficient_count);
void dsp_harmonic_reset(dsp_harmonic *harmonic);
void dsp_harmonic_close(dsp_harmonic *harmonic);
int dsp_harmonic_update_bypass(dsp_harmonic *harmonic, int bypassed);
void dsp_harmonic_process_frame(dsp_harmonic *harmonic, float *left, float *right, int channels);
void dsp_harmonic_flush_denormals(dsp_harmonic *harmonic);
int dsp_harmonic_latency_frames(const dsp_harmonic *harmonic);

#endif
