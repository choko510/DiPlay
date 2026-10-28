#ifndef XCERTPLAY_DSP_DYNAMIC_EQ_H
#define XCERTPLAY_DSP_DYNAMIC_EQ_H

#include "dsp_biquad.h"

#define DSP_DYNAMIC_EQ_MAX_BANDS 5
#define DSP_DYNAMIC_EQ_CONTROL_FRAMES 16
#define DSP_DYNAMIC_EQ_VALUES_COUNT 51
#define DSP_DYNAMIC_EQ_MAX_GAIN_DB 12.0

typedef enum {
    DSP_DYNAMIC_EQ_CUT = 0,
    DSP_DYNAMIC_EQ_BOOST = 1,
} dsp_dynamic_eq_mode;

typedef struct {
    int enabled;
    int mode;
    double frequency_hz;
    double q;
    double threshold_db;
    double ratio;
    double attack_ms;
    double release_ms;
    double max_boost_db;
    double max_cut_db;
} dsp_dynamic_eq_band_config;

typedef struct {
    int enabled;
    dsp_dynamic_eq_band_config bands[DSP_DYNAMIC_EQ_MAX_BANDS];
} dsp_dynamic_eq_config;

typedef struct {
    int sample_rate;
    dsp_dynamic_eq_config config;
    float attack_coefficients[DSP_DYNAMIC_EQ_MAX_BANDS];
    float release_coefficients[DSP_DYNAMIC_EQ_MAX_BANDS];
} dsp_dynamic_eq_hot_update;

typedef struct {
    dsp_dynamic_eq_band_config config;
    dsp_biquad detector;
    dsp_biquad filter;
    float envelope;
    float attack_coefficient;
    float release_coefficient;
    float current_mix;
    float target_mix;
    float mix_step;
    int frames_until_control;
} dsp_dynamic_eq_band;

typedef struct {
    dsp_dynamic_eq_config config;
    dsp_dynamic_eq_band bands[DSP_DYNAMIC_EQ_MAX_BANDS];
    int sample_rate;
} dsp_dynamic_eq;

int dsp_dynamic_eq_prepare(dsp_dynamic_eq *dynamic_eq, int sample_rate, const dsp_dynamic_eq_config *config);
/* Prepare on the control thread; apply validates topology before committing any band. */
int dsp_dynamic_eq_prepare_hot_update(
    int sample_rate,
    const dsp_dynamic_eq_config *config,
    dsp_dynamic_eq_hot_update *update);
int dsp_dynamic_eq_apply_hot_update(dsp_dynamic_eq *dynamic_eq, const dsp_dynamic_eq_hot_update *update);
void dsp_dynamic_eq_reset(dsp_dynamic_eq *dynamic_eq);
void dsp_dynamic_eq_process_frame(dsp_dynamic_eq *dynamic_eq, float *left, float *right, int channels);
void dsp_dynamic_eq_flush_denormals(dsp_dynamic_eq *dynamic_eq);

#endif
