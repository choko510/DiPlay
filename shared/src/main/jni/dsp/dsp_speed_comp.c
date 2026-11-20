#include "dsp_speed_comp.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

#define DSP_SPEED_COMP_RAMP_MS 40.0
#define DSP_SPEED_COMP_RAMP_MIN_FRAMES 1
#define DSP_SPEED_COMP_MAX_VOLUME_DB 6.0
#define DSP_SPEED_COMP_MAX_LOW_DB 6.0
#define DSP_SPEED_COMP_MAX_MID_DB 3.0
#define DSP_SPEED_COMP_MAX_HIGH_DB 4.0
#define DSP_SPEED_COMP_LOW_SPLIT_HZ 180.0
#define DSP_SPEED_COMP_HIGH_SPLIT_HZ 4000.0

static double one_pole_alpha(double frequency_hz, int sample_rate) {
    return 1.0 - exp(-2.0 * 3.14159265358979323846 * frequency_hz / (double)sample_rate);
}

static double db_to_linear(double db) {
    return pow(10.0, db / 20.0);
}

int dsp_speed_comp_config_is_valid(const dsp_speed_comp_config *config) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        isfinite(config->volume_gain_db) && config->volume_gain_db >= 0.0 &&
        config->volume_gain_db <= DSP_SPEED_COMP_MAX_VOLUME_DB &&
        isfinite(config->low_gain_db) && config->low_gain_db >= 0.0 &&
        config->low_gain_db <= DSP_SPEED_COMP_MAX_LOW_DB &&
        isfinite(config->mid_gain_db) && config->mid_gain_db >= 0.0 &&
        config->mid_gain_db <= DSP_SPEED_COMP_MAX_MID_DB &&
        isfinite(config->high_gain_db) && config->high_gain_db >= 0.0 &&
        config->high_gain_db <= DSP_SPEED_COMP_MAX_HIGH_DB;
}

int dsp_speed_comp_is_neutral(const dsp_speed_comp *comp) {
    if (comp == NULL) return 1;
    return !comp->enabled ||
        (comp->ramp_frames_remaining <= 0 && comp->current_volume_gain == 1.0 &&
            comp->current_low_gain == 1.0 && comp->current_mid_gain == 1.0 &&
            comp->current_high_gain == 1.0);
}

static int ramp_frames_for_rate(int sample_rate) {
    const int frames = (int)llround((double)sample_rate * DSP_SPEED_COMP_RAMP_MS / 1000.0);
    return frames < DSP_SPEED_COMP_RAMP_MIN_FRAMES ? DSP_SPEED_COMP_RAMP_MIN_FRAMES : frames;
}

static void set_targets(dsp_speed_comp *comp, const dsp_speed_comp_config *config) {
    const int enabled = config->enabled;
    comp->target_enabled = enabled;
    comp->target_volume_gain = db_to_linear(enabled ? config->volume_gain_db : 0.0);
    comp->target_low_gain = db_to_linear(enabled ? config->low_gain_db : 0.0);
    comp->target_mid_gain = db_to_linear(enabled ? config->mid_gain_db : 0.0);
    comp->target_high_gain = db_to_linear(enabled ? config->high_gain_db : 0.0);
}

static void snap_to_targets(dsp_speed_comp *comp) {
    comp->current_volume_gain = comp->target_volume_gain;
    comp->current_low_gain = comp->target_low_gain;
    comp->current_mid_gain = comp->target_mid_gain;
    comp->current_high_gain = comp->target_high_gain;
}

int dsp_speed_comp_prepare(
    dsp_speed_comp *comp,
    int sample_rate,
    int channels,
    const dsp_speed_comp_config *config) {
    if (comp == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || !dsp_speed_comp_config_is_valid(config)) {
        return 0;
    }
    memset(comp, 0, sizeof(*comp));
    comp->sample_rate = sample_rate;
    comp->channels = channels;
    comp->low_alpha = (float)one_pole_alpha(DSP_SPEED_COMP_LOW_SPLIT_HZ, sample_rate);
    comp->high_alpha = (float)one_pole_alpha(
        fmin(DSP_SPEED_COMP_HIGH_SPLIT_HZ, (double)sample_rate * 0.4),
        sample_rate);
    set_targets(comp, config);
    snap_to_targets(comp);
    comp->enabled = config->enabled;
    comp->ramp_frames_remaining = 0;
    return 1;
}

int dsp_speed_comp_set_config(dsp_speed_comp *comp, const dsp_speed_comp_config *config) {
    if (comp == NULL || comp->sample_rate < 8000 || !dsp_speed_comp_config_is_valid(config)) return 0;
    // Creation time use only: no audio is running yet, so the new state is applied directly
    // to avoid a pointless ramp from silence.
    set_targets(comp, config);
    snap_to_targets(comp);
    comp->enabled = config->enabled;
    comp->ramp_frames_remaining = 0;
    return 1;
}

int dsp_speed_comp_apply_hot(dsp_speed_comp *comp, const dsp_speed_comp_config *config) {
    if (comp == NULL || comp->sample_rate < 8000 || !dsp_speed_comp_config_is_valid(config)) return 0;
    if (!comp->enabled && config->enabled == 0) {
        // Fully disabled stage; make sure a stale state cannot resurrect gain.
        set_targets(comp, config);
        snap_to_targets(comp);
        comp->ramp_frames_remaining = 0;
        return 1;
    }
    const double next_volume_gain = db_to_linear(config->enabled ? config->volume_gain_db : 0.0);
    const double next_low_gain = db_to_linear(config->enabled ? config->low_gain_db : 0.0);
    const double next_mid_gain = db_to_linear(config->enabled ? config->mid_gain_db : 0.0);
    const double next_high_gain = db_to_linear(config->enabled ? config->high_gain_db : 0.0);
    const int same_targets = comp->target_enabled == config->enabled &&
        comp->target_volume_gain == next_volume_gain &&
        comp->target_low_gain == next_low_gain &&
        comp->target_mid_gain == next_mid_gain &&
        comp->target_high_gain == next_high_gain;
    if (same_targets) {
        // Repeated identical updates must not restart the ramp or keep the stage awake.
        return 1;
    }
    if (config->enabled) comp->enabled = 1;
    set_targets(comp, config);
    comp->ramp_frames_remaining = ramp_frames_for_rate(comp->sample_rate);
    const double frames = (double)comp->ramp_frames_remaining;
    comp->volume_step = (comp->target_volume_gain - comp->current_volume_gain) / frames;
    comp->low_step = (comp->target_low_gain - comp->current_low_gain) / frames;
    comp->mid_step = (comp->target_mid_gain - comp->current_mid_gain) / frames;
    comp->high_step = (comp->target_high_gain - comp->current_high_gain) / frames;
    return 1;
}

void dsp_speed_comp_process_frame(dsp_speed_comp *comp, float *left, float *right, int channels) {
    if (comp == NULL || left == NULL || channels < 1 || channels > 2 || !comp->enabled) return;

    if (comp->ramp_frames_remaining > 0) {
        comp->current_volume_gain += comp->volume_step;
        comp->current_low_gain += comp->low_step;
        comp->current_mid_gain += comp->mid_step;
        comp->current_high_gain += comp->high_step;
        comp->ramp_frames_remaining--;
        if (comp->ramp_frames_remaining == 0) {
            snap_to_targets(comp);
            if (!comp->target_enabled) comp->enabled = 0;
        }
    }

    // Keep the band split filters warm even while the gains are neutral so that the next
    // boost starts from a settled state instead of retriggering a filter transient.
    const float low_l = comp->low_alpha * (*left - comp->low_state[0]) + comp->low_state[0];
    comp->low_state[0] = low_l;
    const float split_l = comp->high_alpha * (*left - comp->high_state[0]) + comp->high_state[0];
    comp->high_state[0] = split_l;
    float low_r = 0.0f;
    float split_r = 0.0f;
    if (channels == 2 && right != NULL) {
        low_r = comp->low_alpha * (*right - comp->low_state[1]) + comp->low_state[1];
        comp->low_state[1] = low_r;
        split_r = comp->high_alpha * (*right - comp->high_state[1]) + comp->high_state[1];
        comp->high_state[1] = split_r;
    }

    const int neutral = comp->ramp_frames_remaining <= 0 &&
        comp->current_volume_gain == 1.0 && comp->current_low_gain == 1.0 &&
        comp->current_mid_gain == 1.0 && comp->current_high_gain == 1.0;
    if (neutral) return;

    const double low_gain = comp->current_low_gain - 1.0;
    const double mid_gain = comp->current_mid_gain - 1.0;
    const double high_gain = comp->current_high_gain - 1.0;
    const double volume_gain = comp->current_volume_gain;

    const double input_l = (double)*left;
    const double output_l = input_l + low_gain * (double)low_l +
        mid_gain * (double)(split_l - low_l) + high_gain * (double)(input_l - (double)split_l);
    *left = (float)(output_l * volume_gain);
    if (channels == 2 && right != NULL) {
        const double input_r = (double)*right;
        const double output_r = input_r + low_gain * (double)low_r +
            mid_gain * (double)(split_r - low_r) + high_gain * (double)(input_r - (double)split_r);
        *right = (float)(output_r * volume_gain);
    }
}

void dsp_speed_comp_reset(dsp_speed_comp *comp) {
    if (comp == NULL) return;
    memset(comp->low_state, 0, sizeof(comp->low_state));
    memset(comp->high_state, 0, sizeof(comp->high_state));
}

void dsp_speed_comp_flush_denormals(dsp_speed_comp *comp) {
    if (comp == NULL) return;
    for (size_t channel = 0; channel < 2; channel++) {
        if (fabsf(comp->low_state[channel]) < 1.0e-20f) comp->low_state[channel] = 0.0f;
        if (fabsf(comp->high_state[channel]) < 1.0e-20f) comp->high_state[channel] = 0.0f;
    }
}
