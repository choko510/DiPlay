#include "dsp_smart_loudness.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

#define DSP_SMART_LOUDNESS_RAMP_MS 20.0

static double one_pole_alpha(double frequency_hz, int sample_rate) {
    return 1.0 - exp(-2.0 * 3.14159265358979323846 * frequency_hz / (double)sample_rate);
}

int dsp_smart_loudness_config_is_valid(const dsp_smart_loudness_config *config) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        isfinite(config->low_gain_db) && config->low_gain_db >= 0.0 && config->low_gain_db <= 3.0 &&
        isfinite(config->high_gain_db) && config->high_gain_db >= 0.0 && config->high_gain_db <= 1.5;
}

static void set_targets(dsp_smart_loudness *loudness, const dsp_smart_loudness_config *config) {
    loudness->target_enabled = config->enabled;
    loudness->target_low_gain = pow(10.0, (config->enabled ? config->low_gain_db : 0.0) / 20.0);
    loudness->target_high_gain = pow(10.0, (config->enabled ? config->high_gain_db : 0.0) / 20.0);
}

int dsp_smart_loudness_prepare(
    dsp_smart_loudness *loudness,
    int sample_rate,
    int channels,
    const dsp_smart_loudness_config *config) {
    if (loudness == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || !dsp_smart_loudness_config_is_valid(config)) return 0;
    memset(loudness, 0, sizeof(*loudness));
    loudness->sample_rate = sample_rate;
    loudness->channels = channels;
    loudness->low_alpha = one_pole_alpha(100.0, sample_rate);
    loudness->high_alpha = one_pole_alpha(fmin(8000.0, (double)sample_rate * 0.4), sample_rate);
    set_targets(loudness, config);
    loudness->enabled = config->enabled;
    loudness->current_low_gain = loudness->target_low_gain;
    loudness->current_high_gain = loudness->target_high_gain;
    return 1;
}

int dsp_smart_loudness_apply_hot(
    dsp_smart_loudness *loudness,
    const dsp_smart_loudness_config *config) {
    if (loudness == NULL || loudness->sample_rate < 8000 || !dsp_smart_loudness_config_is_valid(config)) return 0;
    if (!loudness->enabled && config->enabled == 0) return 1;
    if (config->enabled) loudness->enabled = 1;
    set_targets(loudness, config);
    loudness->ramp_frames_remaining = (int)llround((double)loudness->sample_rate * DSP_SMART_LOUDNESS_RAMP_MS / 1000.0);
    if (loudness->ramp_frames_remaining < 1) loudness->ramp_frames_remaining = 1;
    const double frames = (double)loudness->ramp_frames_remaining;
    loudness->low_step = (loudness->target_low_gain - loudness->current_low_gain) / frames;
    loudness->high_step = (loudness->target_high_gain - loudness->current_high_gain) / frames;
    return 1;
}

static float process_channel(dsp_smart_loudness *loudness, float sample, size_t channel) {
    const float low = (float)loudness->low_alpha * (sample - loudness->low_state[channel]) +
        loudness->low_state[channel];
    loudness->low_state[channel] = low;
    const float high_low = (float)loudness->high_alpha * (sample - loudness->high_state[channel]) +
        loudness->high_state[channel];
    loudness->high_state[channel] = high_low;
    return (float)(sample +
        (loudness->current_low_gain - 1.0) * low +
        (loudness->current_high_gain - 1.0) * (sample - high_low));
}

void dsp_smart_loudness_process_frame(dsp_smart_loudness *loudness, float *left, float *right, int channels) {
    if (loudness == NULL || left == NULL || channels < 1 || channels > 2 ||
        (!loudness->enabled && loudness->ramp_frames_remaining <= 0)) return;
    if (loudness->ramp_frames_remaining > 0) {
        loudness->current_low_gain += loudness->low_step;
        loudness->current_high_gain += loudness->high_step;
        loudness->ramp_frames_remaining--;
        if (loudness->ramp_frames_remaining == 0) {
            loudness->current_low_gain = loudness->target_low_gain;
            loudness->current_high_gain = loudness->target_high_gain;
            if (!loudness->target_enabled) loudness->enabled = 0;
        }
    }
    *left = process_channel(loudness, *left, 0);
    if (channels == 2 && right != NULL) *right = process_channel(loudness, *right, 1);
}

void dsp_smart_loudness_reset(dsp_smart_loudness *loudness) {
    if (loudness == NULL) return;
    memset(loudness->low_state, 0, sizeof(loudness->low_state));
    memset(loudness->high_state, 0, sizeof(loudness->high_state));
}

void dsp_smart_loudness_flush_denormals(dsp_smart_loudness *loudness) {
    if (loudness == NULL) return;
    for (size_t channel = 0; channel < 2; channel++) {
        if (fabsf(loudness->low_state[channel]) < 1.0e-20f) loudness->low_state[channel] = 0.0f;
        if (fabsf(loudness->high_state[channel]) < 1.0e-20f) loudness->high_state[channel] = 0.0f;
    }
}
