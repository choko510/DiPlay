#ifndef XCERTPLAY_DSP_MACRO_H
#define XCERTPLAY_DSP_MACRO_H

typedef struct {
    int enabled;
    double bass_gain_db;
    double vocal_gain_db;
    double air_gain_db;
    double relative_width;
} dsp_macro_config;

int dsp_macro_config_is_valid(const dsp_macro_config *config);

typedef struct {
    int sample_rate;
    int channels;
    int enabled;
    int target_enabled;
    double bass_alpha;
    double vocal_low_alpha;
    double vocal_high_alpha;
    double air_alpha;
    double vocal_normalization;
    double current_bass_gain;
    double current_vocal_gain;
    double current_air_gain;
    double current_width;
    double target_bass_gain;
    double target_vocal_gain;
    double target_air_gain;
    double target_width;
    double bass_step;
    double vocal_step;
    double air_step;
    double width_step;
    float bass_state[2];
    float vocal_low_state[2];
    float vocal_high_state[2];
    float air_state[2];
    int ramp_frames_remaining;
} dsp_macro;

int dsp_macro_prepare(dsp_macro *macro, int sample_rate, int channels, const dsp_macro_config *config);
int dsp_macro_apply_hot(dsp_macro *macro, const dsp_macro_config *config);
void dsp_macro_process_frame(dsp_macro *macro, float *left, float *right, int channels);
void dsp_macro_reset(dsp_macro *macro);
void dsp_macro_flush_denormals(dsp_macro *macro);

#endif
