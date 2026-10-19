#include "dsp_spatial.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

static int mono_bass_cutoff_is_supported(int cutoff_hz) {
    return cutoff_hz == 60 || cutoff_hz == 80 || cutoff_hz == 100 ||
        cutoff_hz == 120 || cutoff_hz == 150 || cutoff_hz == 200;
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

    spatial->width = config->width;
    spatial->bass_enabled = bass_coefficient_count == 1;
    spatial->mono_bass_enabled = config->mono_bass_enabled;
    spatial->seat_enabled = config->seat_enabled && channels == 2;
    spatial->seat_invert_left = config->seat_invert_left;
    spatial->seat_invert_right = config->seat_invert_right;
    spatial->seat_left_gain = (float)pow(10.0, config->seat_left_trim_db / 20.0);
    spatial->seat_right_gain = (float)pow(10.0, config->seat_right_trim_db / 20.0);
    spatial->seat_latency_frames = 0;
    spatial->seat_left_delay.integer_delay_frames = 0;
    spatial->seat_left_delay.fractional_delay = 0.0f;
    spatial->seat_right_delay.integer_delay_frames = 0;
    spatial->seat_right_delay.fractional_delay = 0.0f;
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
        spatial->seat_latency_frames = (int)ceil(delay_frames);
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
    int second_index = first_index - 1;
    if (second_index < 0) second_index += DSP_SPATIAL_DELAY_LINE_FRAMES;
    const float fraction = delay->fractional_delay;
    const float output = delay->samples[first_index] * (1.0f - fraction) +
        delay->samples[second_index] * fraction;
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

int dsp_spatial_latency_frames(const dsp_spatial *spatial) {
    return spatial != NULL ? spatial->seat_latency_frames : 0;
}

void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels) {
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
}
