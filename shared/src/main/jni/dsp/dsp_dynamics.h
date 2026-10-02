#ifndef XCERTPLAY_DSP_DYNAMICS_H
#define XCERTPLAY_DSP_DYNAMICS_H

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
    dsp_dynamics_config config;
    float compressor_attack_coefficient;
    float compressor_release_coefficient;
    float compressor_makeup_linear;
    float compressor_envelope;
    float limiter_release_coefficient;
    float limiter_threshold_linear;
    float limiter_gain;
} dsp_dynamics;

int dsp_dynamics_prepare(dsp_dynamics *dynamics, int sample_rate, const dsp_dynamics_config *config);
void dsp_dynamics_reset(dsp_dynamics *dynamics);
void dsp_dynamics_process_compressor_frame(dsp_dynamics *dynamics, float *left, float *right, int channels);
void dsp_dynamics_process_limiter_frame(dsp_dynamics *dynamics, float *left, float *right, int channels);

#endif
