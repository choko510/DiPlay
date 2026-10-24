#include "dsp_spatial.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

static int mono_bass_cutoff_is_supported(int cutoff_hz) {
    return cutoff_hz == 60 || cutoff_hz == 80 || cutoff_hz == 100 ||
        cutoff_hz == 120 || cutoff_hz == 150 || cutoff_hz == 200;
}

static int advanced_ms_config_is_valid(const dsp_advanced_ms_config *config, int sample_rate) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        (config->side_compressor_enabled == 0 || config->side_compressor_enabled == 1) &&
        isfinite(config->crossover_hz) && config->crossover_hz >= 60.0 &&
        config->crossover_hz <= 1000.0 && config->crossover_hz < sample_rate * 0.45 &&
        isfinite(config->center_focus_db) && config->center_focus_db >= -6.0 &&
        config->center_focus_db <= 6.0 && isfinite(config->mid_low_gain_db) &&
        config->mid_low_gain_db >= -6.0 && config->mid_low_gain_db <= 6.0 &&
        isfinite(config->mid_high_gain_db) && config->mid_high_gain_db >= -6.0 &&
        config->mid_high_gain_db <= 6.0 && isfinite(config->side_low_gain_db) &&
        config->side_low_gain_db >= -6.0 && config->side_low_gain_db <= 6.0 &&
        isfinite(config->side_high_gain_db) && config->side_high_gain_db >= -6.0 &&
        config->side_high_gain_db <= 6.0 && isfinite(config->low_band_width) &&
        config->low_band_width >= 0.0 && config->low_band_width <= 2.0 &&
        isfinite(config->high_band_width) && config->high_band_width >= 0.0 &&
        config->high_band_width <= 2.0 && isfinite(config->side_compressor_threshold_db) &&
        config->side_compressor_threshold_db >= -60.0 && config->side_compressor_threshold_db <= 0.0 &&
        isfinite(config->side_compressor_ratio) && config->side_compressor_ratio >= 1.0 &&
        config->side_compressor_ratio <= 8.0 && isfinite(config->side_compressor_attack_ms) &&
        config->side_compressor_attack_ms >= 0.1 && config->side_compressor_attack_ms <= 2000.0 &&
        isfinite(config->side_compressor_release_ms) && config->side_compressor_release_ms >= 0.1 &&
        config->side_compressor_release_ms <= 2000.0 && isfinite(config->side_compressor_knee_db) &&
        config->side_compressor_knee_db >= 0.0 && config->side_compressor_knee_db <= 24.0;
}

static void design_crossover(int sample_rate, double frequency_hz, int high_pass, double coefficients[5]) {
    const double omega = 2.0 * 3.14159265358979323846 * frequency_hz / sample_rate;
    const double cosine = cos(omega);
    const double sine = sin(omega);
    const double alpha = sine / (2.0 * 0.70710678118654752440);
    const double a0 = 1.0 + alpha;
    const double factor = high_pass ? (1.0 + cosine) * 0.5 : (1.0 - cosine) * 0.5;
    coefficients[0] = factor / a0;
    coefficients[1] = (high_pass ? -(1.0 + cosine) : 1.0 - cosine) / a0;
    coefficients[2] = factor / a0;
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
}

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    const dsp_spatial_config *config,
    int sample_rate,
    int channels,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count) {
    if (spatial == NULL || config == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || !isfinite(config->width) ||
        config->width < 0.0 || config->width > 2.0 ||
        (config->mono_bass_enabled != 0 && config->mono_bass_enabled != 1) ||
        !mono_bass_cutoff_is_supported(config->mono_bass_cutoff_hz) ||
        (config->seat_enabled != 0 && config->seat_enabled != 1) ||
        config->seat_near_speaker < DSP_SPATIAL_NEAR_CENTER ||
        config->seat_near_speaker > DSP_SPATIAL_NEAR_RIGHT ||
        !isfinite(config->seat_distance_difference_cm) ||
        config->seat_distance_difference_cm < 0.0 ||
        config->seat_distance_difference_cm > DSP_SPATIAL_MAX_DISTANCE_DIFFERENCE_CM ||
        !isfinite(config->seat_left_trim_db) || config->seat_left_trim_db < -6.0 ||
        config->seat_left_trim_db > 6.0 || !isfinite(config->seat_right_trim_db) ||
        config->seat_right_trim_db < -6.0 || config->seat_right_trim_db > 6.0 ||
        (config->seat_invert_left != 0 && config->seat_invert_left != 1) ||
        (config->seat_invert_right != 0 && config->seat_invert_right != 1) ||
        bass_coefficient_count < 0 || bass_coefficient_count > 1 ||
        mono_bass_coefficient_count < 0 || mono_bass_coefficient_count > 1 ||
        (bass_coefficient_count > 0 && bass_coefficients == NULL) ||
        (mono_bass_coefficient_count > 0 && mono_bass_coefficients == NULL) ||
        (config->mono_bass_enabled != 0 && mono_bass_coefficient_count != 1) ||
        (config->mono_bass_enabled == 0 && mono_bass_coefficient_count != 0)) {
        return 0;
    }
    if (config->advanced_ms.enabled && !advanced_ms_config_is_valid(&config->advanced_ms, sample_rate)) {
        return 0;
    }

    spatial->advanced_ms_enabled = config->advanced_ms.enabled != 0 && channels == 2;
    spatial->width = spatial->advanced_ms_enabled ? 1.0 : config->width;
    spatial->bass_enabled = bass_coefficient_count == 1;
    spatial->mono_bass_enabled = config->mono_bass_enabled && !spatial->advanced_ms_enabled;
    spatial->seat_enabled = config->seat_enabled && channels == 2;
    spatial->seat_invert_left = config->seat_invert_left;
    spatial->seat_invert_right = config->seat_invert_right;
    spatial->seat_left_gain = (float)pow(10.0, config->seat_left_trim_db / 20.0);
    spatial->seat_right_gain = (float)pow(10.0, config->seat_right_trim_db / 20.0);
    spatial->seat_relative_delay_frames = 0.0;
    spatial->seat_left_delay.integer_delay_frames = 0;
    spatial->seat_left_delay.fractional_delay = 0.0f;
    spatial->seat_left_delay.fractional_allpass_coefficient = 0.0f;
    spatial->seat_left_delay.fractional_x_previous = 0.0f;
    spatial->seat_left_delay.fractional_y_previous = 0.0f;
    spatial->seat_right_delay.integer_delay_frames = 0;
    spatial->seat_right_delay.fractional_delay = 0.0f;
    spatial->seat_right_delay.fractional_allpass_coefficient = 0.0f;
    spatial->seat_right_delay.fractional_x_previous = 0.0f;
    spatial->seat_right_delay.fractional_y_previous = 0.0f;
    spatial->advanced_ms_identity = 0;
    if (spatial->seat_enabled && config->seat_near_speaker != DSP_SPATIAL_NEAR_CENTER &&
        config->seat_distance_difference_cm > 0.0) {
        const double delay_frames = fmin(
            config->seat_distance_difference_cm * sample_rate / DSP_SPATIAL_SOUND_SPEED_CM_PER_SECOND,
            sample_rate * 0.005);
        const int integer_delay_frames = (int)floor(delay_frames);
        const float fractional_delay = (float)(delay_frames - integer_delay_frames);
        dsp_spatial_delay_line *near_delay = config->seat_near_speaker == DSP_SPATIAL_NEAR_LEFT
            ? &spatial->seat_left_delay
            : &spatial->seat_right_delay;
        near_delay->integer_delay_frames = integer_delay_frames;
        near_delay->fractional_delay = fractional_delay;
        near_delay->fractional_allpass_coefficient =
            (1.0f - fractional_delay) / (1.0f + fractional_delay);
        spatial->seat_relative_delay_frames = delay_frames;
    }
    if (spatial->advanced_ms_enabled) {
        spatial->advanced_ms_identity =
            config->advanced_ms.center_focus_db == 0.0 &&
            config->advanced_ms.mid_low_gain_db == 0.0 &&
            config->advanced_ms.mid_high_gain_db == 0.0 &&
            config->advanced_ms.side_low_gain_db == 0.0 &&
            config->advanced_ms.side_high_gain_db == 0.0 &&
            config->advanced_ms.low_band_width == 1.0 &&
            config->advanced_ms.high_band_width == 1.0 &&
            config->advanced_ms.side_compressor_enabled == 0;
        double low_pass[5];
        double high_pass[5];
        design_crossover(sample_rate, config->advanced_ms.crossover_hz, 0, low_pass);
        design_crossover(sample_rate, config->advanced_ms.crossover_hz, 1, high_pass);
        for (int component = 0; component < 2; component++) {
            if (!dsp_biquad_prepare(&spatial->ms_low_pass_first[component], low_pass) ||
                !dsp_biquad_prepare(&spatial->ms_low_pass_second[component], low_pass) ||
                !dsp_biquad_prepare(&spatial->ms_high_pass_first[component], high_pass) ||
                !dsp_biquad_prepare(&spatial->ms_high_pass_second[component], high_pass)) {
                return 0;
            }
        }
        spatial->mid_low_gain = (float)pow(10.0,
            (config->advanced_ms.center_focus_db + config->advanced_ms.mid_low_gain_db) / 20.0);
        spatial->mid_high_gain = (float)pow(10.0,
            (config->advanced_ms.center_focus_db + config->advanced_ms.mid_high_gain_db) / 20.0);
        spatial->side_low_gain = (float)(pow(10.0, config->advanced_ms.side_low_gain_db / 20.0) *
            config->advanced_ms.low_band_width);
        spatial->side_high_gain = (float)(pow(10.0, config->advanced_ms.side_high_gain_db / 20.0) *
            config->advanced_ms.high_band_width);
        const dsp_dynamics_config side_dynamics_config = {
            .compressor_enabled = config->advanced_ms.side_compressor_enabled,
            .compressor_threshold_db = config->advanced_ms.side_compressor_threshold_db,
            .compressor_ratio = config->advanced_ms.side_compressor_ratio,
            .compressor_attack_ms = config->advanced_ms.side_compressor_attack_ms,
            .compressor_release_ms = config->advanced_ms.side_compressor_release_ms,
            .compressor_knee_db = config->advanced_ms.side_compressor_knee_db,
            .compressor_makeup_db = 0.0,
            .limiter_enabled = 0,
            .limiter_threshold_db = -1.0,
            .limiter_release_ms = 60.0,
        };
        if (!dsp_dynamics_prepare(&spatial->side_compressor, sample_rate, &side_dynamics_config)) {
            return 0;
        }
    } else {
        spatial->mid_low_gain = 1.0f;
        spatial->mid_high_gain = 1.0f;
        spatial->side_low_gain = 1.0f;
        spatial->side_high_gain = 1.0f;
        const dsp_dynamics_config bypass_side_dynamics_config = {
            .compressor_enabled = 0,
            .compressor_threshold_db = -24.0,
            .compressor_ratio = 2.0,
            .compressor_attack_ms = 10.0,
            .compressor_release_ms = 100.0,
            .compressor_knee_db = 6.0,
            .compressor_makeup_db = 0.0,
            .limiter_enabled = 0,
            .limiter_threshold_db = -1.0,
            .limiter_release_ms = 60.0,
        };
        if (!dsp_dynamics_prepare(&spatial->side_compressor, sample_rate, &bypass_side_dynamics_config)) {
            return 0;
        }
    }
    if (spatial->bass_enabled && !dsp_biquad_prepare(&spatial->bass, bass_coefficients)) {
        return 0;
    }
    if (spatial->mono_bass_enabled && !dsp_biquad_prepare(&spatial->mono_bass, mono_bass_coefficients)) {
        return 0;
    }
    dsp_spatial_reset(spatial);
    return 1;
}

void dsp_spatial_reset(dsp_spatial *spatial) {
    if (spatial == NULL) {
        return;
    }
    if (spatial->bass_enabled) dsp_biquad_reset(&spatial->bass);
    if (spatial->mono_bass_enabled) dsp_biquad_reset(&spatial->mono_bass);
    memset(spatial->seat_left_delay.samples, 0, sizeof(spatial->seat_left_delay.samples));
    memset(spatial->seat_right_delay.samples, 0, sizeof(spatial->seat_right_delay.samples));
    spatial->seat_left_delay.write_index = 0;
    spatial->seat_right_delay.write_index = 0;
    spatial->seat_left_delay.fractional_x_previous = 0.0f;
    spatial->seat_left_delay.fractional_y_previous = 0.0f;
    spatial->seat_right_delay.fractional_x_previous = 0.0f;
    spatial->seat_right_delay.fractional_y_previous = 0.0f;
    for (int component = 0; component < 2; component++) {
        dsp_biquad_reset(&spatial->ms_low_pass_first[component]);
        dsp_biquad_reset(&spatial->ms_low_pass_second[component]);
        dsp_biquad_reset(&spatial->ms_high_pass_first[component]);
        dsp_biquad_reset(&spatial->ms_high_pass_second[component]);
    }
    dsp_dynamics_reset(&spatial->side_compressor);
}

float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel) {
    return spatial != NULL && spatial->bass_enabled
        ? dsp_biquad_process_sample(&spatial->bass, sample, channel)
        : sample;
}

static float process_seat_delay(dsp_spatial_delay_line *delay, float sample) {
    if (delay->integer_delay_frames == 0 && delay->fractional_delay <= 0.0f) return sample;
    int write_index = delay->write_index;
    delay->samples[write_index] = fabsf(sample) < 1e-20f ? 0.0f : sample;
    int first_index = write_index - delay->integer_delay_frames;
    if (first_index < 0) first_index += DSP_SPATIAL_DELAY_LINE_FRAMES;
    const float integer_delayed = delay->samples[first_index];
    float output = integer_delayed;
    if (delay->fractional_delay > 0.0f) {
        const float coefficient = delay->fractional_allpass_coefficient;
        output = coefficient * integer_delayed + delay->fractional_x_previous -
            coefficient * delay->fractional_y_previous;
        delay->fractional_x_previous = integer_delayed;
        delay->fractional_y_previous = output;
    }
    write_index++;
    if (write_index == DSP_SPATIAL_DELAY_LINE_FRAMES) write_index = 0;
    delay->write_index = write_index;
    return fabsf(output) < 1e-20f ? 0.0f : output;
}

void dsp_spatial_process_seat(dsp_spatial *spatial, float *left, float *right, int channels) {
    if (spatial == NULL || left == NULL || right == NULL || channels != 2 || !spatial->seat_enabled) return;
    float left_sample = *left * spatial->seat_left_gain;
    float right_sample = *right * spatial->seat_right_gain;
    if (spatial->seat_invert_left) left_sample = -left_sample;
    if (spatial->seat_invert_right) right_sample = -right_sample;
    *left = process_seat_delay(&spatial->seat_left_delay, left_sample);
    *right = process_seat_delay(&spatial->seat_right_delay, right_sample);
}

double dsp_spatial_relative_delay_frames(const dsp_spatial *spatial) {
    return spatial != NULL ? spatial->seat_relative_delay_frames : 0.0;
}

void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels) {
    if (spatial == NULL || left == NULL || right == NULL || channels != 2) {
        return;
    }

    if (spatial->advanced_ms_enabled) {
        if (spatial->advanced_ms_identity) return;
        const float mid = (*left + *right) * 0.5f;
        const float side = (*left - *right) * 0.5f;
        const float mid_low = dsp_biquad_process_sample(
            &spatial->ms_low_pass_second[0],
            dsp_biquad_process_sample(&spatial->ms_low_pass_first[0], mid, 0),
            0);
        const float mid_high = dsp_biquad_process_sample(
            &spatial->ms_high_pass_second[0],
            dsp_biquad_process_sample(&spatial->ms_high_pass_first[0], mid, 0),
            0);
        const float side_low = dsp_biquad_process_sample(
            &spatial->ms_low_pass_second[1],
            dsp_biquad_process_sample(&spatial->ms_low_pass_first[1], side, 0),
            0);
        const float side_high = dsp_biquad_process_sample(
            &spatial->ms_high_pass_second[1],
            dsp_biquad_process_sample(&spatial->ms_high_pass_first[1], side, 0),
            0);
        float output_mid = mid_low * spatial->mid_low_gain + mid_high * spatial->mid_high_gain;
        float output_side = side_low * spatial->side_low_gain + side_high * spatial->side_high_gain;
        dsp_dynamics_process_compressor_frame(&spatial->side_compressor, &output_side, &output_side, 1);
        *left = output_mid + output_side;
        *right = output_mid - output_side;
        return;
    }

    if (spatial->width == 1.0 && !spatial->mono_bass_enabled) return;

    const float middle = (*left + *right) * 0.5f;
    float side = (*left - *right) * 0.5f * (float)spatial->width;
    if (spatial->mono_bass_enabled) {
        side = dsp_biquad_process_sample(&spatial->mono_bass, side, 0);
    }
    *left = middle + side;
    *right = middle - side;
}

void dsp_spatial_flush_denormals(dsp_spatial *spatial) {
    if (spatial == NULL) {
        return;
    }
    if (spatial->bass_enabled) dsp_biquad_flush_denormals(&spatial->bass);
    if (spatial->mono_bass_enabled) dsp_biquad_flush_denormals(&spatial->mono_bass);
    if (spatial->advanced_ms_enabled) {
        for (int component = 0; component < 2; component++) {
            dsp_biquad_flush_denormals(&spatial->ms_low_pass_first[component]);
            dsp_biquad_flush_denormals(&spatial->ms_low_pass_second[component]);
            dsp_biquad_flush_denormals(&spatial->ms_high_pass_first[component]);
            dsp_biquad_flush_denormals(&spatial->ms_high_pass_second[component]);
        }
        if (fabsf(spatial->side_compressor.compressor_envelope) < 1e-20f) {
            spatial->side_compressor.compressor_envelope = 0.0f;
        }
    }
}
