#ifndef XCERTPLAY_DSP_SPATIAL_H
#define XCERTPLAY_DSP_SPATIAL_H

#include "dsp_biquad.h"

#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>

#define DSP_ADVANCED_MS_MAX_MID_EQ_BANDS 6
#define DSP_ADVANCED_MS_MAX_SIDE_EQ_BANDS 5
#define DSP_ADVANCED_MS_COEFFICIENT_BANDS \
    (DSP_ADVANCED_MS_MAX_MID_EQ_BANDS + DSP_ADVANCED_MS_MAX_SIDE_EQ_BANDS)

typedef enum {
    DSP_ADVANCED_MS_MONITOR_NORMAL = 0,
    DSP_ADVANCED_MS_MONITOR_MID_ONLY = 1,
    DSP_ADVANCED_MS_MONITOR_SIDE_ONLY = 2,
    DSP_ADVANCED_MS_MONITOR_MONO_FOLD_DOWN = 3,
} dsp_advanced_ms_monitor_mode;

typedef struct {
    int enabled;
    double mid_gain_db;
    double side_gain_db;
    double low_width;
    double mid_width;
    double high_width;
    double low_mid_crossover_hz;
    double mid_high_crossover_hz;
    double center_focus_db;
    int monitor_mode;
    int mid_eq_band_count;
    int side_eq_band_count;
} dsp_advanced_ms_config;

typedef struct {
    float coefficient;
    float previous_input;
    float previous_output;
} dsp_first_order_allpass;

typedef struct {
    double width;
    int bass_enabled;
    int mono_bass_enabled;
    dsp_biquad bass;
    dsp_biquad mono_bass;
    int advanced_ms_enabled;
    double low_width;
    double mid_width;
    double high_width;
    _Atomic int monitor_mode;
    float mid_gain;
    float side_gain;
    dsp_biquad mid_eq[DSP_ADVANCED_MS_MAX_MID_EQ_BANDS];
    int mid_eq_band_count;
    dsp_biquad side_eq[DSP_ADVANCED_MS_MAX_SIDE_EQ_BANDS];
    int side_eq_band_count;
    int observed_monitor_mode;
    uint32_t monitor_transition_frames;
    uint32_t monitor_transition_position;
    float monitor_mix[3];
    float monitor_transition_start[3];
    float monitor_transition_target[3];
    dsp_first_order_allpass low_mid_allpass;
    dsp_first_order_allpass mid_high_allpass;
} dsp_spatial;

typedef struct {
    double width;
    int mono_bass_enabled;
    int mono_bass_cutoff_hz;
} dsp_spatial_config;

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    int sample_rate,
    const dsp_spatial_config *config,
    const dsp_advanced_ms_config *advanced_ms_config,
    const double *advanced_ms_coefficients,
    size_t advanced_ms_coefficient_count,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count);
int dsp_spatial_update_monitor_mode(dsp_spatial *spatial, int monitor_mode);
void dsp_spatial_reset(dsp_spatial *spatial);
float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel);
void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels);
void dsp_spatial_flush_denormals(dsp_spatial *spatial);

#endif
