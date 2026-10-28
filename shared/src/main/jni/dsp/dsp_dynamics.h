#ifndef XCERTPLAY_DSP_DYNAMICS_H
#define XCERTPLAY_DSP_DYNAMICS_H

#include <stdint.h>

typedef struct {
    int compressor_enabled;
    double compressor_threshold_db;
    double compressor_ratio;
    double compressor_attack_ms;
    double compressor_release_ms;
    double compressor_knee_db;
    double compressor_makeup_db;
    int limiter_enabled;
    double limiter_threshold_db;
    double limiter_release_ms;
} dsp_dynamics_config;

typedef struct {
    uint32_t version;
    int sample_rate;
    dsp_dynamics_config config;
    float compressor_attack_coefficient;
    float compressor_release_coefficient;
    float compressor_makeup_linear;
    float limiter_release_coefficient;
    float limiter_threshold_linear;
} dsp_dynamics_hot_params;

typedef struct {
    dsp_dynamics_config config;
    int sample_rate;
    float compressor_attack_coefficient;
    float compressor_release_coefficient;
    float compressor_makeup_linear;
    float compressor_makeup_target_linear;
    float compressor_makeup_step;
    int compressor_makeup_ramp_remaining;
    float compressor_envelope;
    float limiter_release_coefficient;
    float limiter_threshold_linear;
    float limiter_gain;
} dsp_dynamics;

typedef enum {
    DSP_DYNAMICS_HOT_INVALID = 0,
    DSP_DYNAMICS_HOT_APPLIED = 1,
    DSP_DYNAMICS_HOT_DEFERRED = 2,
} dsp_dynamics_hot_result;

int dsp_dynamics_prepare(dsp_dynamics *dynamics, int sample_rate, const dsp_dynamics_config *config);
int dsp_dynamics_prepare_hot_params(int sample_rate, const dsp_dynamics_config *config, dsp_dynamics_hot_params *params);
int dsp_dynamics_apply_hot_params(dsp_dynamics *dynamics, const dsp_dynamics_hot_params *params, int makeup_ramp_frames);
void dsp_dynamics_reset(dsp_dynamics *dynamics);
void dsp_dynamics_process_compressor_frame(dsp_dynamics *dynamics, float *left, float *right, int channels);
void dsp_dynamics_process_limiter_frame(dsp_dynamics *dynamics, float *left, float *right, int channels);

#endif
