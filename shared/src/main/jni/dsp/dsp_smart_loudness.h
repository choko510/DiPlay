#ifndef XCERTPLAY_DSP_SMART_LOUDNESS_H
#define XCERTPLAY_DSP_SMART_LOUDNESS_H

typedef struct {
    int enabled;
    double low_gain_db;
    double high_gain_db;
} dsp_smart_loudness_config;

typedef struct {
    int sample_rate;
    int channels;
    int enabled;
    int target_enabled;
    double low_alpha;
    double high_alpha;
    double current_low_gain;
    double current_high_gain;
    double target_low_gain;
    double target_high_gain;
    double low_step;
    double high_step;
    float low_state[2];
    float high_state[2];
    int ramp_frames_remaining;
} dsp_smart_loudness;

int dsp_smart_loudness_prepare(
    dsp_smart_loudness *loudness,
    int sample_rate,
    int channels,
    const dsp_smart_loudness_config *config);
int dsp_smart_loudness_apply_hot(
    dsp_smart_loudness *loudness,
    const dsp_smart_loudness_config *config);
void dsp_smart_loudness_process_frame(dsp_smart_loudness *loudness, float *left, float *right, int channels);
void dsp_smart_loudness_reset(dsp_smart_loudness *loudness);
void dsp_smart_loudness_flush_denormals(dsp_smart_loudness *loudness);

#endif
