#ifndef XCERTPLAY_DSP_SPEED_COMP_H
#define XCERTPLAY_DSP_SPEED_COMP_H

/*
 * Speed adaptive audio: a transient boost layer driven by the head unit's GPS speed.
 *
 * The stage is intentionally independent from the user EQ, Macro Tone, Smart Loudness
 * and Dynamic Bass so that a speed change can be applied as a bounded HOT update without
 * rewriting any saved user setting. When the stage is disabled or fully neutral it does
 * not touch the samples at all, which keeps the DSP-OFF/neutral PCM path bit exact.
 */

typedef struct {
    int enabled;
    double volume_gain_db;
    double low_gain_db;
    double mid_gain_db;
    double high_gain_db;
} dsp_speed_comp_config;

typedef struct {
    int sample_rate;
    int channels;
    int enabled;
    int target_enabled;
    float low_alpha;
    float high_alpha;
    float low_state[2];
    float high_state[2];
    double current_volume_gain;
    double current_low_gain;
    double current_mid_gain;
    double current_high_gain;
    double target_volume_gain;
    double target_low_gain;
    double target_mid_gain;
    double target_high_gain;
    double volume_step;
    double low_step;
    double mid_step;
    double high_step;
    int ramp_frames_remaining;
} dsp_speed_comp;

int dsp_speed_comp_config_is_valid(const dsp_speed_comp_config *config);

int dsp_speed_comp_prepare(
    dsp_speed_comp *comp,
    int sample_rate,
    int channels,
    const dsp_speed_comp_config *config);

int dsp_speed_comp_set_config(dsp_speed_comp *comp, const dsp_speed_comp_config *config);

int dsp_speed_comp_apply_hot(dsp_speed_comp *comp, const dsp_speed_comp_config *config);

void dsp_speed_comp_process_frame(dsp_speed_comp *comp, float *left, float *right, int channels);

void dsp_speed_comp_reset(dsp_speed_comp *comp);

void dsp_speed_comp_flush_denormals(dsp_speed_comp *comp);

int dsp_speed_comp_is_neutral(const dsp_speed_comp *comp);

#endif
