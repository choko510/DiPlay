#include "dsp_spatial.h"

#include <limits.h>
#include <math.h>
#include <stddef.h>
#include <stdlib.h>

static int mono_bass_cutoff_is_supported(int cutoff_hz) {
    return cutoff_hz == 60 || cutoff_hz == 80 || cutoff_hz == 100 ||
        cutoff_hz == 120 || cutoff_hz == 150 || cutoff_hz == 200;
}

static int design_advanced_ms_butterworth(
    int sample_rate,
    double frequency_hz,
    int high_pass,
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT]) {
    if (sample_rate < 8000 || sample_rate > 192000 || !isfinite(frequency_hz) ||
        frequency_hz < 20.0 || frequency_hz >= (double)sample_rate * 0.45) {
        return 0;
    }
    const double omega = 2.0 * 3.14159265358979323846 * frequency_hz / (double)sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * 0.70710678118654752440);
    const double a0 = 1.0 + alpha;
    if (!isfinite(cosine) || !isfinite(alpha) || !isfinite(a0) || a0 <= 0.0) return 0;

    if (high_pass) {
        const double scale = (1.0 + cosine) * 0.5 / a0;
        coefficients[0] = scale;
        coefficients[1] = -2.0 * scale;
        coefficients[2] = scale;
    } else {
        const double scale = (1.0 - cosine) * 0.5 / a0;
        coefficients[0] = scale;
        coefficients[1] = 2.0 * scale;
        coefficients[2] = scale;
    }
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
    return 1;
}

static int prepare_advanced_ms_filter_pair(dsp_biquad filters[2], int sample_rate, double frequency_hz, int high_pass) {
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    if (!design_advanced_ms_butterworth(sample_rate, frequency_hz, high_pass, coefficients)) return 0;
    return dsp_biquad_prepare(&filters[0], coefficients) && dsp_biquad_prepare(&filters[1], coefficients);
}

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    int sample_rate,
    int channels,
    const dsp_spatial_config *config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count) {
    if (spatial == NULL || config == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || !isfinite(config->width) ||
        config->width < 0.0 || config->width > 2.0 ||
        (config->mono_bass_enabled != 0 && config->mono_bass_enabled != 1) ||
        (config->seat_focus_enabled != 0 && config->seat_focus_enabled != 1) ||
        (config->advanced_ms_enabled != 0 && config->advanced_ms_enabled != 1) ||
        (config->advanced_ms_side_compressor_enabled != 0 && config->advanced_ms_side_compressor_enabled != 1) ||
        !isfinite(config->seat_left_delay_frames) || !isfinite(config->seat_right_delay_frames) ||
        !isfinite(config->seat_left_trim_linear) || !isfinite(config->seat_right_trim_linear) ||
        (config->seat_left_polarity_inverted != 0 && config->seat_left_polarity_inverted != 1) ||
        (config->seat_right_polarity_inverted != 0 && config->seat_right_polarity_inverted != 1) ||
        !mono_bass_cutoff_is_supported(config->mono_bass_cutoff_hz) ||
        bass_coefficient_count < 0 || bass_coefficient_count > 1 ||
        mono_bass_coefficient_count < 0 || mono_bass_coefficient_count > 1 ||
        (bass_coefficient_count > 0 && bass_coefficients == NULL) ||
        (mono_bass_coefficient_count > 0 && mono_bass_coefficients == NULL) ||
        (config->mono_bass_enabled != 0 && mono_bass_coefficient_count != 1) ||
        (config->mono_bass_enabled == 0 && mono_bass_coefficient_count != 0)) {
        return 0;
    }

    const int maximum_seat_latency_frames =
        (int)ceil((double)sample_rate * DSP_SEAT_FOCUS_MAX_DELAY_MS / 1000.0);
    if (maximum_seat_latency_frames > DSP_SEAT_FOCUS_MAX_DELAY_FRAMES ||
        config->seat_left_delay_frames < 0.0 ||
        config->seat_left_delay_frames > (double)maximum_seat_latency_frames ||
        config->seat_right_delay_frames < 0.0 ||
        config->seat_right_delay_frames > (double)maximum_seat_latency_frames ||
        config->seat_left_trim_linear < DSP_SEAT_FOCUS_MIN_TRIM_LINEAR ||
        config->seat_left_trim_linear > DSP_SEAT_FOCUS_MAX_TRIM_LINEAR ||
        config->seat_right_trim_linear < DSP_SEAT_FOCUS_MIN_TRIM_LINEAR ||
        config->seat_right_trim_linear > DSP_SEAT_FOCUS_MAX_TRIM_LINEAR) {
        return 0;
    }

    if (config->advanced_ms_enabled != 0 &&
        (!isfinite(config->advanced_ms_crossover_hz) || config->advanced_ms_crossover_hz < 20.0 ||
            config->advanced_ms_crossover_hz > 1000.0 ||
            config->advanced_ms_crossover_hz >= (double)sample_rate * 0.45 ||
            !isfinite(config->advanced_ms_low_mid_gain_db) || config->advanced_ms_low_mid_gain_db < -6.0 ||
            config->advanced_ms_low_mid_gain_db > 6.0 ||
            !isfinite(config->advanced_ms_low_side_gain_db) || config->advanced_ms_low_side_gain_db < -6.0 ||
            config->advanced_ms_low_side_gain_db > 6.0 ||
            !isfinite(config->advanced_ms_low_width) || config->advanced_ms_low_width < 0.0 ||
            config->advanced_ms_low_width > 2.0 ||
            !isfinite(config->advanced_ms_high_mid_gain_db) || config->advanced_ms_high_mid_gain_db < -6.0 ||
            config->advanced_ms_high_mid_gain_db > 6.0 ||
            !isfinite(config->advanced_ms_high_side_gain_db) || config->advanced_ms_high_side_gain_db < -6.0 ||
            config->advanced_ms_high_side_gain_db > 6.0 ||
            !isfinite(config->advanced_ms_high_width) || config->advanced_ms_high_width < 0.0 ||
            config->advanced_ms_high_width > 2.0 ||
            !isfinite(config->advanced_ms_center_focus_db) || config->advanced_ms_center_focus_db < -6.0 ||
            config->advanced_ms_center_focus_db > 6.0 ||
            (config->advanced_ms_side_compressor_enabled != 0 && config->advanced_ms_side_compressor_enabled != 1) ||
            (config->advanced_ms_side_compressor_enabled != 0 &&
                (!isfinite(config->advanced_ms_side_compressor_threshold_db) ||
                    config->advanced_ms_side_compressor_threshold_db < -60.0 ||
                    config->advanced_ms_side_compressor_threshold_db > 0.0 ||
                    !isfinite(config->advanced_ms_side_compressor_ratio) ||
                    config->advanced_ms_side_compressor_ratio < 1.0 ||
                    config->advanced_ms_side_compressor_ratio > 20.0 ||
                    !isfinite(config->advanced_ms_side_compressor_attack_ms) ||
                    config->advanced_ms_side_compressor_attack_ms < 0.1 ||
                    config->advanced_ms_side_compressor_attack_ms > 2000.0 ||
                    !isfinite(config->advanced_ms_side_compressor_release_ms) ||
                    config->advanced_ms_side_compressor_release_ms < 0.1 ||
                    config->advanced_ms_side_compressor_release_ms > 2000.0 ||
                    !isfinite(config->advanced_ms_side_compressor_knee_db) ||
                    config->advanced_ms_side_compressor_knee_db < 0.0 ||
                    config->advanced_ms_side_compressor_knee_db > 24.0 ||
                    !isfinite(config->advanced_ms_side_compressor_makeup_db) ||
                    config->advanced_ms_side_compressor_makeup_db < -24.0 ||
                    config->advanced_ms_side_compressor_makeup_db > 24.0)))) {
        return 0;
    }

    spatial->width = config->width;
    spatial->bass_enabled = bass_coefficient_count == 1;
    spatial->mono_bass_enabled = config->mono_bass_enabled;
    spatial->seat_focus_enabled = config->seat_focus_enabled != 0 && channels == 2;
    spatial->seat_latency_frames = spatial->seat_focus_enabled ? maximum_seat_latency_frames : 0;
    const double left_whole = floor(config->seat_left_delay_frames);
    const double right_whole = floor(config->seat_right_delay_frames);
    spatial->seat_left_delay_whole_frames = (int)left_whole;
    spatial->seat_right_delay_whole_frames = (int)right_whole;
    spatial->seat_left_delay_fraction = (float)(config->seat_left_delay_frames - left_whole);
    spatial->seat_right_delay_fraction = (float)(config->seat_right_delay_frames - right_whole);
    spatial->seat_left_gain = (float)config->seat_left_trim_linear *
        (config->seat_left_polarity_inverted ? -1.0f : 1.0f);
    spatial->seat_right_gain = (float)config->seat_right_trim_linear *
        (config->seat_right_polarity_inverted ? -1.0f : 1.0f);
    spatial->seat_delay_lines = NULL;
    spatial->seat_delay_capacity = 0;
    spatial->seat_write_index = 0;
    spatial->seat_written_frames = 0;
    spatial->advanced_ms_enabled = config->advanced_ms_enabled != 0 && channels == 2;
    spatial->advanced_ms_side_compressor_enabled =
        spatial->advanced_ms_enabled && config->advanced_ms_side_compressor_enabled != 0;
    spatial->advanced_ms_low_mid_gain = 0.0f;
    spatial->advanced_ms_low_side_gain = 0.0f;
    spatial->advanced_ms_high_mid_gain = 0.0f;
    spatial->advanced_ms_high_side_gain = 0.0f;
    spatial->advanced_ms_center_gain = 1.0f;
    if (spatial->seat_focus_enabled) {
        spatial->seat_delay_capacity = (size_t)maximum_seat_latency_frames + 2;
        spatial->seat_delay_lines = (float *)calloc(2 * spatial->seat_delay_capacity, sizeof(float));
        if (spatial->seat_delay_lines == NULL) return 0;
    }
    if (spatial->advanced_ms_enabled) {
        if (!prepare_advanced_ms_filter_pair(
                spatial->advanced_ms_low_pass,
                sample_rate,
                config->advanced_ms_crossover_hz,
                0) ||
            !prepare_advanced_ms_filter_pair(
                spatial->advanced_ms_high_pass,
                sample_rate,
                config->advanced_ms_crossover_hz,
                1)) {
            return 0;
        }
        const double center_gain = pow(10.0, config->advanced_ms_center_focus_db / 20.0);
        spatial->advanced_ms_low_mid_gain = (float)pow(10.0, config->advanced_ms_low_mid_gain_db / 20.0);
        spatial->advanced_ms_low_side_gain = (float)(pow(10.0, config->advanced_ms_low_side_gain_db / 20.0) *
            config->advanced_ms_low_width);
        spatial->advanced_ms_high_mid_gain = (float)pow(10.0, config->advanced_ms_high_mid_gain_db / 20.0);
        spatial->advanced_ms_high_side_gain = (float)(pow(10.0, config->advanced_ms_high_side_gain_db / 20.0) *
            config->advanced_ms_high_width);
        spatial->advanced_ms_center_gain = (float)center_gain;
        if (!isfinite(spatial->advanced_ms_low_mid_gain) || !isfinite(spatial->advanced_ms_low_side_gain) ||
            !isfinite(spatial->advanced_ms_high_mid_gain) || !isfinite(spatial->advanced_ms_high_side_gain) ||
            !isfinite(spatial->advanced_ms_center_gain)) {
            return 0;
        }
        if (spatial->advanced_ms_side_compressor_enabled) {
            const dsp_dynamics_config side_compressor = {
                .compressor_enabled = 1,
                .compressor_threshold_db = config->advanced_ms_side_compressor_threshold_db,
                .compressor_ratio = config->advanced_ms_side_compressor_ratio,
                .compressor_attack_ms = config->advanced_ms_side_compressor_attack_ms,
                .compressor_release_ms = config->advanced_ms_side_compressor_release_ms,
                .compressor_knee_db = config->advanced_ms_side_compressor_knee_db,
                .compressor_makeup_db = config->advanced_ms_side_compressor_makeup_db,
                .limiter_enabled = 0,
                .limiter_threshold_db = -1.0,
                .limiter_release_ms = 60.0,
            };
            if (!dsp_dynamics_prepare(&spatial->advanced_ms_side_compressor, sample_rate, &side_compressor)) {
                return 0;
            }
        }
    }
    if (spatial->bass_enabled && !dsp_biquad_prepare(&spatial->bass, bass_coefficients)) {
        return 0;
    }
    if (spatial->mono_bass_enabled && !dsp_biquad_prepare(&spatial->mono_bass, mono_bass_coefficients)) {
        return 0;
    }
    return 1;
}

void dsp_spatial_reset(dsp_spatial *spatial) {
    if (spatial == NULL) {
        return;
    }
    if (spatial->bass_enabled) dsp_biquad_reset(&spatial->bass);
    if (spatial->mono_bass_enabled) dsp_biquad_reset(&spatial->mono_bass);
    if (spatial->advanced_ms_enabled) {
        for (size_t stage = 0; stage < 2; stage++) {
            dsp_biquad_reset(&spatial->advanced_ms_low_pass[stage]);
            dsp_biquad_reset(&spatial->advanced_ms_high_pass[stage]);
        }
        if (spatial->advanced_ms_side_compressor_enabled) {
            dsp_dynamics_reset(&spatial->advanced_ms_side_compressor);
        }
    }
    spatial->seat_write_index = 0;
    spatial->seat_written_frames = 0;
}

void dsp_spatial_close(dsp_spatial *spatial) {
    if (spatial == NULL) return;
    free(spatial->seat_delay_lines);
    spatial->seat_delay_lines = NULL;
    spatial->seat_delay_capacity = 0;
    spatial->seat_focus_enabled = 0;
    spatial->seat_latency_frames = 0;
}

int dsp_spatial_latency_frames(const dsp_spatial *spatial) {
    return spatial == NULL ? -1 : spatial->seat_latency_frames;
}

static int wrap_delay_index(int index, int capacity) {
    while (index < 0) index += capacity;
    while (index >= capacity) index -= capacity;
    return index;
}

static float delayed_sample(const dsp_spatial *spatial, int channel, int whole_frames, float fraction) {
    const int capacity = (int)spatial->seat_delay_capacity;
    const float *line = spatial->seat_delay_lines + (size_t)channel * spatial->seat_delay_capacity;
    const int recent_index = wrap_delay_index(spatial->seat_write_index - whole_frames, capacity);
    const float recent = spatial->seat_written_frames > whole_frames
        ? line[recent_index]
        : 0.0f;
    if (fraction == 0.0f) return recent;
    const int older_index = wrap_delay_index(recent_index - 1, capacity);
    const float older = spatial->seat_written_frames > whole_frames + 1
        ? line[older_index]
        : 0.0f;
    return recent * (1.0f - fraction) + older * fraction;
}

void dsp_spatial_process_seat(dsp_spatial *spatial, float *left, float *right, int channels) {
    if (spatial == NULL || left == NULL || right == NULL || channels != 2 ||
        !spatial->seat_focus_enabled || spatial->seat_delay_lines == NULL) {
        return;
    }
    const int write_index = spatial->seat_write_index;
    spatial->seat_delay_lines[write_index] = *left * spatial->seat_left_gain;
    spatial->seat_delay_lines[spatial->seat_delay_capacity + (size_t)write_index] = *right * spatial->seat_right_gain;
    if (spatial->seat_written_frames < (int)spatial->seat_delay_capacity) spatial->seat_written_frames++;
    *left = delayed_sample(
        spatial,
        0,
        spatial->seat_left_delay_whole_frames,
        spatial->seat_left_delay_fraction);
    *right = delayed_sample(
        spatial,
        1,
        spatial->seat_right_delay_whole_frames,
        spatial->seat_right_delay_fraction);
    spatial->seat_write_index++;
    if (spatial->seat_write_index == (int)spatial->seat_delay_capacity) spatial->seat_write_index = 0;
}

float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel) {
    return spatial != NULL && spatial->bass_enabled
        ? dsp_biquad_process_sample(&spatial->bass, sample, channel)
        : sample;
}

static float process_advanced_ms_filter_pair(dsp_biquad filters[2], float sample, int channel) {
    sample = dsp_biquad_process_sample(&filters[0], sample, channel);
    return dsp_biquad_process_sample(&filters[1], sample, channel);
}

static void process_advanced_ms(dsp_spatial *spatial, float *left, float *right) {
    const float middle = (*left + *right) * 0.5f;
    const float side = (*left - *right) * 0.5f;
    const float low_middle = process_advanced_ms_filter_pair(spatial->advanced_ms_low_pass, middle, 0);
    const float high_middle = process_advanced_ms_filter_pair(spatial->advanced_ms_high_pass, middle, 0);
    const float low_side = process_advanced_ms_filter_pair(spatial->advanced_ms_low_pass, side, 1);
    const float high_side = process_advanced_ms_filter_pair(spatial->advanced_ms_high_pass, side, 1);
    const float output_middle = (low_middle * spatial->advanced_ms_low_mid_gain +
        high_middle * spatial->advanced_ms_high_mid_gain) * spatial->advanced_ms_center_gain;
    float output_side = low_side * spatial->advanced_ms_low_side_gain +
        high_side * spatial->advanced_ms_high_side_gain;
    if (spatial->advanced_ms_side_compressor_enabled) {
        float unused_right = 0.0f;
        dsp_dynamics_process_compressor_frame(
            &spatial->advanced_ms_side_compressor,
            &output_side,
            &unused_right,
            1);
    }
    *left = output_middle + output_side;
    *right = output_middle - output_side;
}

void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels) {
    if (spatial != NULL && left != NULL && right != NULL && channels == 2 && spatial->advanced_ms_enabled) {
        process_advanced_ms(spatial, left, right);
        return;
    }
    if (spatial == NULL || left == NULL || right == NULL || channels != 2 ||
        (spatial->width == 1.0 && !spatial->mono_bass_enabled)) {
        return;
    }

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
        for (size_t stage = 0; stage < 2; stage++) {
            dsp_biquad_flush_denormals(&spatial->advanced_ms_low_pass[stage]);
            dsp_biquad_flush_denormals(&spatial->advanced_ms_high_pass[stage]);
        }
    }
}
