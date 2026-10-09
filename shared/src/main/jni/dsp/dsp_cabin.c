#include "dsp_cabin.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

int dsp_cabin_prepare(
    dsp_cabin *cabin,
    int sample_rate,
    const dsp_cabin_config *config,
    const double *static_coefficients,
    int static_coefficient_count,
    const dsp_dynamic_eq_config *dynamic_config) {
    if (cabin == NULL || config == NULL || dynamic_config == NULL ||
        sample_rate < 8000 || sample_rate > 192000 ||
        (config->enabled != 0 && config->enabled != 1) ||
        (config->bypassed != 0 && config->bypassed != 1) ||
        config->static_cut_count < 0 || config->static_cut_count > DSP_CABIN_MAX_CUTS ||
        static_coefficient_count < 0 ||
        static_coefficient_count > DSP_CABIN_MAX_CUTS * DSP_BIQUAD_COEFFICIENT_COUNT ||
        static_coefficient_count % DSP_BIQUAD_COEFFICIENT_COUNT != 0 ||
        (static_coefficient_count > 0 && static_coefficients == NULL) ||
        static_coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT != config->static_cut_count ||
        (config->enabled == 0 && (config->bypassed != 0 || config->static_cut_count != 0 ||
            dynamic_config->enabled != 0)) ||
        (dynamic_config->enabled != 0 && config->enabled == 0)) {
        return 0;
    }
    for (int index = 0; index < static_coefficient_count; index++) {
        if (!isfinite(static_coefficients[index])) return 0;
    }

    memset(cabin, 0, sizeof(*cabin));
    cabin->enabled = config->enabled;
    cabin->static_cut_count = config->static_cut_count;
    atomic_init(&cabin->bypass_target, config->bypassed);
    cabin->observed_bypass = config->bypassed;
    cabin->bypass_transition_frames = (uint32_t)ceil((double)sample_rate * 0.02);
    cabin->bypass_transition_position = cabin->bypass_transition_frames;
    cabin->bypass_mix = config->bypassed != 0 ? 0.0f : 1.0f;
    cabin->bypass_transition_start = cabin->bypass_mix;
    cabin->bypass_transition_target = cabin->bypass_mix;
    for (int index = 0; index < cabin->static_cut_count; index++) {
        const double *coefficients = static_coefficients +
            (size_t)index * DSP_BIQUAD_COEFFICIENT_COUNT;
        if (!dsp_biquad_prepare(&cabin->static_cuts[index], coefficients)) return 0;
    }
    return dsp_dynamic_eq_prepare(&cabin->dynamic_eq, sample_rate, dynamic_config);
}

int dsp_cabin_update_bypass(dsp_cabin *cabin, int bypassed) {
    if (cabin == NULL || cabin->enabled == 0 || (bypassed != 0 && bypassed != 1)) return 0;
    atomic_store_explicit(&cabin->bypass_target, bypassed, memory_order_release);
    return 1;
}

void dsp_cabin_reset(dsp_cabin *cabin) {
    if (cabin == NULL || cabin->enabled == 0) return;
    for (int index = 0; index < cabin->static_cut_count; index++) {
        dsp_biquad_reset(&cabin->static_cuts[index]);
    }
    dsp_dynamic_eq_reset(&cabin->dynamic_eq);
    cabin->observed_bypass = atomic_load_explicit(&cabin->bypass_target, memory_order_acquire);
    cabin->bypass_transition_target = cabin->observed_bypass != 0 ? 0.0f : 1.0f;
    cabin->bypass_mix = cabin->bypass_transition_target;
    cabin->bypass_transition_start = cabin->bypass_mix;
    cabin->bypass_transition_position = cabin->bypass_transition_frames;
}

void dsp_cabin_process_frame(dsp_cabin *cabin, float *left, float *right, int channels) {
    if (cabin == NULL || cabin->enabled == 0 || left == NULL || right == NULL ||
        channels < 1 || channels > 2) {
        return;
    }
    const float dry_left = *left;
    const float dry_right = *right;
    float wet_left = dry_left;
    float wet_right = dry_right;
    for (int index = 0; index < cabin->static_cut_count; index++) {
        wet_left = dsp_biquad_process_sample(&cabin->static_cuts[index], wet_left, 0);
        if (channels == 2) wet_right = dsp_biquad_process_sample(&cabin->static_cuts[index], wet_right, 1);
    }
    dsp_dynamic_eq_process_frame(&cabin->dynamic_eq, &wet_left, &wet_right, channels);
    const int bypassed = atomic_load_explicit(&cabin->bypass_target, memory_order_acquire);
    if (bypassed != cabin->observed_bypass) {
        cabin->bypass_transition_start = cabin->bypass_mix;
        cabin->bypass_transition_target = bypassed != 0 ? 0.0f : 1.0f;
        cabin->bypass_transition_position = 0;
        cabin->observed_bypass = bypassed;
    }
    if (cabin->bypass_transition_position < cabin->bypass_transition_frames) {
        float progress = (float)(cabin->bypass_transition_position + 1U) /
            (float)cabin->bypass_transition_frames;
        progress = progress * progress * (3.0f - 2.0f * progress);
        cabin->bypass_transition_position++;
        cabin->bypass_mix = cabin->bypass_transition_start +
            (cabin->bypass_transition_target - cabin->bypass_transition_start) * progress;
        if (cabin->bypass_transition_position >= cabin->bypass_transition_frames) {
            cabin->bypass_mix = cabin->bypass_transition_target;
        }
    }
    *left = dry_left + (wet_left - dry_left) * cabin->bypass_mix;
    if (channels == 2) *right = dry_right + (wet_right - dry_right) * cabin->bypass_mix;
}

void dsp_cabin_flush_denormals(dsp_cabin *cabin) {
    if (cabin == NULL || cabin->enabled == 0) return;
    for (int index = 0; index < cabin->static_cut_count; index++) {
        dsp_biquad_flush_denormals(&cabin->static_cuts[index]);
    }
    dsp_dynamic_eq_flush_denormals(&cabin->dynamic_eq);
}
