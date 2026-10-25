#include "dsp_macro.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

#define DSP_MACRO_RAMP_MS 20.0
#define DSP_MACRO_RAMP_MIN_FRAMES 1

static double one_pole_alpha(double frequency_hz, int sample_rate) {
    return 1.0 - exp(-2.0 * 3.14159265358979323846 * frequency_hz / (double)sample_rate);
}

int dsp_macro_config_is_valid(const dsp_macro_config *config) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        isfinite(config->bass_gain_db) && config->bass_gain_db >= -2.0 && config->bass_gain_db <= 4.0 &&
        isfinite(config->vocal_gain_db) && config->vocal_gain_db >= -2.0 && config->vocal_gain_db <= 2.5 &&
        isfinite(config->air_gain_db) && config->air_gain_db >= -2.0 && config->air_gain_db <= 2.0 &&
        isfinite(config->relative_width) && config->relative_width >= 0.75 && config->relative_width <= 1.25;
}

static int ramp_frames_for_rate(int sample_rate) {
    const int frames = (int)llround((double)sample_rate * DSP_MACRO_RAMP_MS / 1000.0);
    return frames < DSP_MACRO_RAMP_MIN_FRAMES ? DSP_MACRO_RAMP_MIN_FRAMES : frames;
}

static void set_targets(dsp_macro *macro, const dsp_macro_config *config) {
    const double bass_db = config->enabled ? config->bass_gain_db : 0.0;
    const double vocal_db = config->enabled ? config->vocal_gain_db : 0.0;
    const double air_db = config->enabled ? config->air_gain_db : 0.0;
    macro->target_enabled = config->enabled;
    macro->target_bass_gain = pow(10.0, bass_db / 20.0);
    macro->target_vocal_gain = pow(10.0, vocal_db / 20.0);
    macro->target_air_gain = pow(10.0, air_db / 20.0);
    macro->target_width = config->enabled ? config->relative_width : 1.0;
}

int dsp_macro_prepare(dsp_macro *macro, int sample_rate, int channels, const dsp_macro_config *config) {
    if (macro == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || !dsp_macro_config_is_valid(config)) {
        return 0;
    }
    memset(macro, 0, sizeof(*macro));
    macro->sample_rate = sample_rate;
    macro->channels = channels;
    macro->bass_alpha = one_pole_alpha(180.0, sample_rate);
    macro->vocal_low_alpha = one_pole_alpha(500.0, sample_rate);
    macro->vocal_high_alpha = one_pole_alpha(4000.0, sample_rate);
    macro->air_alpha = one_pole_alpha(fmin(8000.0, (double)sample_rate * 0.4), sample_rate);
    macro->vocal_normalization = 1.0;
    set_targets(macro, config);
    macro->enabled = config->enabled;
    macro->current_bass_gain = macro->target_bass_gain;
    macro->current_vocal_gain = macro->target_vocal_gain;
    macro->current_air_gain = macro->target_air_gain;
    macro->current_width = macro->target_width;
    return 1;
}

int dsp_macro_apply_hot(dsp_macro *macro, const dsp_macro_config *config) {
    if (macro == NULL || macro->sample_rate < 8000 || !dsp_macro_config_is_valid(config)) return 0;
    if (!macro->enabled && config->enabled == 0) return 1;
    if (config->enabled) macro->enabled = 1;
    set_targets(macro, config);
    macro->ramp_frames_remaining = ramp_frames_for_rate(macro->sample_rate);
    const double frames = (double)macro->ramp_frames_remaining;
    macro->bass_step = (macro->target_bass_gain - macro->current_bass_gain) / frames;
    macro->vocal_step = (macro->target_vocal_gain - macro->current_vocal_gain) / frames;
    macro->air_step = (macro->target_air_gain - macro->current_air_gain) / frames;
    macro->width_step = (macro->target_width - macro->current_width) / frames;
    return 1;
}

static float process_channel(
    dsp_macro *macro,
    float sample,
    size_t channel) {
    const float bass = (float)macro->bass_alpha * (sample - macro->bass_state[channel]) +
        macro->bass_state[channel];
    macro->bass_state[channel] = bass;
    const float vocal_low = (float)macro->vocal_low_alpha * (sample - macro->vocal_low_state[channel]) +
        macro->vocal_low_state[channel];
    macro->vocal_low_state[channel] = vocal_low;
    const float vocal_high = (float)macro->vocal_high_alpha * (sample - macro->vocal_high_state[channel]) +
        macro->vocal_high_state[channel];
    macro->vocal_high_state[channel] = vocal_high;
    const float air_low = (float)macro->air_alpha * (sample - macro->air_state[channel]) + macro->air_state[channel];
    macro->air_state[channel] = air_low;

    const double vocal_gain = 1.0 + (macro->current_vocal_gain - 1.0) * macro->vocal_normalization;
    return (float)(sample +
        (macro->current_bass_gain - 1.0) * bass +
        (vocal_gain - 1.0) * (vocal_high - vocal_low) +
        (macro->current_air_gain - 1.0) * (sample - air_low));
}

void dsp_macro_process_frame(dsp_macro *macro, float *left, float *right, int channels) {
    if (macro == NULL || left == NULL || channels < 1 || channels > 2 ||
        (!macro->enabled && macro->ramp_frames_remaining <= 0)) return;

    if (macro->ramp_frames_remaining > 0) {
        macro->current_bass_gain += macro->bass_step;
        macro->current_vocal_gain += macro->vocal_step;
        macro->current_air_gain += macro->air_step;
        macro->current_width += macro->width_step;
        macro->ramp_frames_remaining--;
        if (macro->ramp_frames_remaining == 0) {
            macro->current_bass_gain = macro->target_bass_gain;
            macro->current_vocal_gain = macro->target_vocal_gain;
            macro->current_air_gain = macro->target_air_gain;
            macro->current_width = macro->target_width;
            if (!macro->target_enabled) macro->enabled = 0;
        }
    }

    const int neutral_tone = macro->current_bass_gain == 1.0 && macro->current_vocal_gain == 1.0 &&
        macro->current_air_gain == 1.0;
    float output_left = process_channel(macro, *left, 0);
    if (channels == 2 && right != NULL) {
        float output_right = process_channel(macro, *right, 1);
        if (macro->current_width != 1.0) {
            const float mid = 0.5f * (output_left + output_right);
            const float side = 0.5f * (output_left - output_right) * (float)macro->current_width;
            output_left = mid + side;
            *right = mid - side;
        } else if (!neutral_tone) {
            *right = output_right;
        }
    } else if (channels == 1 && !neutral_tone) {
        *left = output_left;
    }
    if (channels == 2 && (macro->current_width != 1.0 || !neutral_tone)) *left = output_left;
}

void dsp_macro_reset(dsp_macro *macro) {
    if (macro == NULL) return;
    memset(macro->bass_state, 0, sizeof(macro->bass_state));
    memset(macro->vocal_low_state, 0, sizeof(macro->vocal_low_state));
    memset(macro->vocal_high_state, 0, sizeof(macro->vocal_high_state));
    memset(macro->air_state, 0, sizeof(macro->air_state));
}

void dsp_macro_flush_denormals(dsp_macro *macro) {
    if (macro == NULL) return;
    for (size_t channel = 0; channel < 2; channel++) {
        if (fabsf(macro->bass_state[channel]) < 1.0e-20f) macro->bass_state[channel] = 0.0f;
        if (fabsf(macro->vocal_low_state[channel]) < 1.0e-20f) macro->vocal_low_state[channel] = 0.0f;
        if (fabsf(macro->vocal_high_state[channel]) < 1.0e-20f) macro->vocal_high_state[channel] = 0.0f;
        if (fabsf(macro->air_state[channel]) < 1.0e-20f) macro->air_state[channel] = 0.0f;
    }
}
