#include "dsp_spatial.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

#define DSP_SPATIAL_PI 3.14159265358979323846

static int mono_bass_cutoff_is_supported(int cutoff_hz) {
    return cutoff_hz == 60 || cutoff_hz == 80 || cutoff_hz == 100 ||
        cutoff_hz == 120 || cutoff_hz == 150 || cutoff_hz == 200;
}

static int advanced_ms_config_is_valid(const dsp_advanced_ms_config *config, int sample_rate) {
    return config != NULL &&
        (config->enabled == 0 || config->enabled == 1) &&
        isfinite(config->mid_gain_db) && config->mid_gain_db >= -6.0 && config->mid_gain_db <= 6.0 &&
        isfinite(config->side_gain_db) && config->side_gain_db >= -6.0 && config->side_gain_db <= 6.0 &&
        isfinite(config->low_width) && config->low_width >= 0.0 && config->low_width <= 1.5 &&
        isfinite(config->mid_width) && config->mid_width >= 0.0 && config->mid_width <= 1.5 &&
        isfinite(config->high_width) && config->high_width >= 0.0 && config->high_width <= 1.5 &&
        isfinite(config->low_mid_crossover_hz) && config->low_mid_crossover_hz >= 80.0 &&
        config->low_mid_crossover_hz <= 500.0 &&
        isfinite(config->mid_high_crossover_hz) && config->mid_high_crossover_hz >= 1000.0 &&
        config->mid_high_crossover_hz <= 8000.0 &&
        config->low_mid_crossover_hz < config->mid_high_crossover_hz &&
        (config->enabled == 0 || (config->low_mid_crossover_hz < sample_rate * 0.45 &&
            config->mid_high_crossover_hz < sample_rate * 0.45)) &&
        isfinite(config->center_focus_db) && config->center_focus_db >= -3.0 &&
        config->center_focus_db <= 3.0 &&
        config->monitor_mode >= DSP_ADVANCED_MS_MONITOR_NORMAL &&
        config->monitor_mode <= DSP_ADVANCED_MS_MONITOR_MONO_FOLD_DOWN &&
        config->mid_eq_band_count >= 0 && config->mid_eq_band_count <= DSP_ADVANCED_MS_MAX_MID_EQ_BANDS &&
        config->side_eq_band_count >= 0 && config->side_eq_band_count <= DSP_ADVANCED_MS_MAX_SIDE_EQ_BANDS;
}

static int prepare_allpass(
    dsp_first_order_allpass *allpass,
    int sample_rate,
    double cutoff_hz) {
    if (allpass == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        !isfinite(cutoff_hz) || cutoff_hz <= 0.0 || cutoff_hz >= sample_rate * 0.5) {
        return 0;
    }
    const double tangent = tan(DSP_SPATIAL_PI * cutoff_hz / (double)sample_rate);
    const double coefficient = (tangent - 1.0) / (tangent + 1.0);
    if (!isfinite(coefficient) || fabs(coefficient) >= 1.0) return 0;
    allpass->coefficient = (float)coefficient;
    allpass->previous_input = 0.0f;
    allpass->previous_output = 0.0f;
    return isfinite(allpass->coefficient);
}

static float process_allpass(dsp_first_order_allpass *allpass, float sample) {
    const float output = allpass->coefficient * sample + allpass->previous_input -
        allpass->coefficient * allpass->previous_output;
    allpass->previous_input = sample;
    allpass->previous_output = output;
    return output;
}

static void set_monitor_target(dsp_spatial *spatial, int monitor_mode) {
    spatial->monitor_transition_target[0] = monitor_mode == DSP_ADVANCED_MS_MONITOR_NORMAL ? 1.0f : 0.0f;
    spatial->monitor_transition_target[1] =
        monitor_mode == DSP_ADVANCED_MS_MONITOR_MID_ONLY ||
        monitor_mode == DSP_ADVANCED_MS_MONITOR_MONO_FOLD_DOWN ? 1.0f : 0.0f;
    spatial->monitor_transition_target[2] = monitor_mode == DSP_ADVANCED_MS_MONITOR_SIDE_ONLY ? 1.0f : 0.0f;
}

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    int sample_rate,
    const dsp_spatial_config *config,
    const dsp_advanced_ms_config *advanced_ms_config,
    const double *advanced_ms_coefficients,
    size_t advanced_ms_coefficient_count,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count) {
    if (spatial == NULL || sample_rate < 8000 || sample_rate > 192000 || config == NULL ||
        advanced_ms_config == NULL || !isfinite(config->width) ||
        config->width < 0.0 || config->width > 2.0 ||
        (config->mono_bass_enabled != 0 && config->mono_bass_enabled != 1) ||
        !mono_bass_cutoff_is_supported(config->mono_bass_cutoff_hz) ||
        !advanced_ms_config_is_valid(advanced_ms_config, sample_rate) ||
        advanced_ms_coefficient_count != DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT ||
        advanced_ms_coefficients == NULL ||
        bass_coefficient_count < 0 || bass_coefficient_count > 1 ||
        mono_bass_coefficient_count < 0 || mono_bass_coefficient_count > 1 ||
        (bass_coefficient_count > 0 && bass_coefficients == NULL) ||
        (mono_bass_coefficient_count > 0 && mono_bass_coefficients == NULL) ||
        (config->mono_bass_enabled != 0 && mono_bass_coefficient_count != 1) ||
        (config->mono_bass_enabled == 0 && mono_bass_coefficient_count != 0) ||
        (advanced_ms_config->enabled != 0 &&
            (advanced_ms_config->mid_eq_band_count > 0 || advanced_ms_config->side_eq_band_count > 0) &&
            advanced_ms_coefficients == NULL)) {
        return 0;
    }
    for (size_t index = 0; index < advanced_ms_coefficient_count; index++) {
        if (!isfinite(advanced_ms_coefficients[index])) return 0;
    }

    memset(spatial, 0, sizeof(*spatial));
    spatial->width = config->width;
    spatial->bass_enabled = bass_coefficient_count == 1;
    spatial->mono_bass_enabled = config->mono_bass_enabled;
    spatial->advanced_ms_enabled = advanced_ms_config->enabled;
    spatial->low_width = advanced_ms_config->low_width;
    spatial->mid_width = advanced_ms_config->mid_width;
    spatial->high_width = advanced_ms_config->high_width;
    atomic_init(&spatial->monitor_mode, advanced_ms_config->monitor_mode);
    spatial->observed_monitor_mode = advanced_ms_config->monitor_mode;
    spatial->monitor_transition_frames = (uint32_t)ceil((double)sample_rate * 0.02);
    spatial->monitor_transition_position = spatial->monitor_transition_frames;
    set_monitor_target(spatial, advanced_ms_config->monitor_mode);
    for (size_t index = 0; index < 3U; index++) {
        spatial->monitor_mix[index] = spatial->monitor_transition_target[index];
        spatial->monitor_transition_start[index] = spatial->monitor_transition_target[index];
    }
    spatial->mid_gain = (float)pow(10.0, advanced_ms_config->mid_gain_db / 20.0);
    spatial->side_gain = (float)pow(10.0, advanced_ms_config->side_gain_db / 20.0);
    spatial->mid_eq_band_count = advanced_ms_config->enabled ? advanced_ms_config->mid_eq_band_count : 0;
    spatial->side_eq_band_count = advanced_ms_config->enabled ? advanced_ms_config->side_eq_band_count : 0;
    if (!isfinite(spatial->mid_gain) || !isfinite(spatial->side_gain)) return 0;
    if (spatial->bass_enabled && !dsp_biquad_prepare(&spatial->bass, bass_coefficients)) {
        return 0;
    }
    if (spatial->mono_bass_enabled && !dsp_biquad_prepare(&spatial->mono_bass, mono_bass_coefficients)) {
        return 0;
    }
    for (int index = 0; index < spatial->mid_eq_band_count; index++) {
        const double *coefficients = advanced_ms_coefficients + (size_t)index * DSP_BIQUAD_COEFFICIENT_COUNT;
        if (!dsp_biquad_prepare(&spatial->mid_eq[index], coefficients)) return 0;
    }
    for (int index = 0; index < spatial->side_eq_band_count; index++) {
        const size_t band_index = DSP_ADVANCED_MS_MAX_MID_EQ_BANDS + (size_t)index;
        const double *coefficients = advanced_ms_coefficients + band_index * DSP_BIQUAD_COEFFICIENT_COUNT;
        if (!dsp_biquad_prepare(&spatial->side_eq[index], coefficients)) return 0;
    }
    if (spatial->advanced_ms_enabled != 0 &&
        (!prepare_allpass(&spatial->low_mid_allpass, sample_rate, advanced_ms_config->low_mid_crossover_hz) ||
            !prepare_allpass(&spatial->mid_high_allpass, sample_rate, advanced_ms_config->mid_high_crossover_hz))) {
        return 0;
    }
    return 1;
}

int dsp_spatial_update_monitor_mode(dsp_spatial *spatial, int monitor_mode) {
    if (spatial == NULL || spatial->advanced_ms_enabled == 0 ||
        monitor_mode < DSP_ADVANCED_MS_MONITOR_NORMAL ||
        monitor_mode > DSP_ADVANCED_MS_MONITOR_MONO_FOLD_DOWN) {
        return 0;
    }
    atomic_store_explicit(&spatial->monitor_mode, monitor_mode, memory_order_release);
    return 1;
}

void dsp_spatial_reset(dsp_spatial *spatial) {
    if (spatial == NULL) {
        return;
    }
    if (spatial->bass_enabled) dsp_biquad_reset(&spatial->bass);
    if (spatial->mono_bass_enabled) dsp_biquad_reset(&spatial->mono_bass);
    for (int index = 0; index < spatial->mid_eq_band_count; index++) {
        dsp_biquad_reset(&spatial->mid_eq[index]);
    }
    for (int index = 0; index < spatial->side_eq_band_count; index++) {
        dsp_biquad_reset(&spatial->side_eq[index]);
    }
    spatial->observed_monitor_mode = atomic_load_explicit(&spatial->monitor_mode, memory_order_acquire);
    set_monitor_target(spatial, spatial->observed_monitor_mode);
    spatial->monitor_transition_position = spatial->monitor_transition_frames;
    for (size_t index = 0; index < 3U; index++) {
        spatial->monitor_mix[index] = spatial->monitor_transition_target[index];
        spatial->monitor_transition_start[index] = spatial->monitor_transition_target[index];
    }
    spatial->low_mid_allpass.previous_input = 0.0f;
    spatial->low_mid_allpass.previous_output = 0.0f;
    spatial->mid_high_allpass.previous_input = 0.0f;
    spatial->mid_high_allpass.previous_output = 0.0f;
}

float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel) {
    return spatial != NULL && spatial->bass_enabled
        ? dsp_biquad_process_sample(&spatial->bass, sample, channel)
        : sample;
}

static float clamp_width(double width) {
    if (width < 0.0) return 0.0f;
    if (width > 2.0) return 2.0f;
    return (float)width;
}

void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels) {
    if (spatial == NULL || left == NULL || right == NULL || channels != 2 ||
        (spatial->width == 1.0 && !spatial->mono_bass_enabled && !spatial->advanced_ms_enabled)) {
        return;
    }

    const float input_mid = (*left + *right) * 0.5f;
    float input_side = (*left - *right) * 0.5f;
    if (spatial->mono_bass_enabled) {
        input_side = dsp_biquad_process_sample(&spatial->mono_bass, input_side, 0);
    }
    if (spatial->advanced_ms_enabled == 0) {
        const float side = input_side * (float)spatial->width;
        *left = input_mid + side;
        *right = input_mid - side;
        return;
    }

    float middle = input_mid;
    float side = input_side;
    for (int index = 0; index < spatial->mid_eq_band_count; index++) {
        middle = dsp_biquad_process_sample(&spatial->mid_eq[index], middle, 0);
    }
    for (int index = 0; index < spatial->side_eq_band_count; index++) {
        side = dsp_biquad_process_sample(&spatial->side_eq[index], side, 0);
    }
    middle *= spatial->mid_gain;
    side *= spatial->side_gain;

    const float low_mid_allpass = process_allpass(&spatial->low_mid_allpass, side);
    const float low_band = (side + low_mid_allpass) * 0.5f;
    const float upper_bands = (side - low_mid_allpass) * 0.5f;
    const float mid_high_allpass = process_allpass(&spatial->mid_high_allpass, upper_bands);
    const float mid_band = (upper_bands + mid_high_allpass) * 0.5f;
    const float high_band = (upper_bands - mid_high_allpass) * 0.5f;
    const float low_width = clamp_width(spatial->width * spatial->low_width);
    const float mid_width = clamp_width(spatial->width * spatial->mid_width);
    const float high_width = clamp_width(spatial->width * spatial->high_width);
    const float output_side = low_band * low_width + mid_band * mid_width + high_band * high_width;

    const int monitor_mode = atomic_load_explicit(&spatial->monitor_mode, memory_order_acquire);
    if (monitor_mode != spatial->observed_monitor_mode) {
        for (size_t index = 0; index < 3U; index++) {
            spatial->monitor_transition_start[index] = spatial->monitor_mix[index];
        }
        set_monitor_target(spatial, monitor_mode);
        spatial->monitor_transition_position = 0;
        spatial->observed_monitor_mode = monitor_mode;
    }
    if (spatial->monitor_transition_position >= spatial->monitor_transition_frames) {
        if (monitor_mode == DSP_ADVANCED_MS_MONITOR_SIDE_ONLY) {
            *left = output_side;
            *right = -output_side;
        } else if (monitor_mode == DSP_ADVANCED_MS_MONITOR_MID_ONLY ||
            monitor_mode == DSP_ADVANCED_MS_MONITOR_MONO_FOLD_DOWN) {
            *left = middle;
            *right = middle;
        } else {
            *left = middle + output_side;
            *right = middle - output_side;
        }
    } else {
        float progress = (float)(spatial->monitor_transition_position + 1U) /
            (float)spatial->monitor_transition_frames;
        progress = progress * progress * (3.0f - 2.0f * progress);
        spatial->monitor_transition_position++;
        for (size_t index = 0; index < 3U; index++) {
            spatial->monitor_mix[index] = spatial->monitor_transition_start[index] +
                (spatial->monitor_transition_target[index] - spatial->monitor_transition_start[index]) * progress;
        }
        if (spatial->monitor_transition_position >= spatial->monitor_transition_frames) {
            for (size_t index = 0; index < 3U; index++) {
                spatial->monitor_mix[index] = spatial->monitor_transition_target[index];
            }
        }
        const float normal_mix = spatial->monitor_mix[0];
        const float mid_mix = spatial->monitor_mix[1];
        const float side_mix = spatial->monitor_mix[2];
        *left = normal_mix * (middle + output_side) + mid_mix * middle + side_mix * output_side;
        *right = normal_mix * (middle - output_side) + mid_mix * middle - side_mix * output_side;
    }
}

void dsp_spatial_flush_denormals(dsp_spatial *spatial) {
    if (spatial == NULL) {
        return;
    }
    if (spatial->bass_enabled) dsp_biquad_flush_denormals(&spatial->bass);
    if (spatial->mono_bass_enabled) dsp_biquad_flush_denormals(&spatial->mono_bass);
    for (int index = 0; index < spatial->mid_eq_band_count; index++) {
        dsp_biquad_flush_denormals(&spatial->mid_eq[index]);
    }
    for (int index = 0; index < spatial->side_eq_band_count; index++) {
        dsp_biquad_flush_denormals(&spatial->side_eq[index]);
    }
    if (fabsf(spatial->low_mid_allpass.previous_input) < 1e-20f) spatial->low_mid_allpass.previous_input = 0.0f;
    if (fabsf(spatial->low_mid_allpass.previous_output) < 1e-20f) spatial->low_mid_allpass.previous_output = 0.0f;
    if (fabsf(spatial->mid_high_allpass.previous_input) < 1e-20f) spatial->mid_high_allpass.previous_input = 0.0f;
    if (fabsf(spatial->mid_high_allpass.previous_output) < 1e-20f) spatial->mid_high_allpass.previous_output = 0.0f;
}
