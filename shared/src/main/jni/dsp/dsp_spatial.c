#include "dsp_spatial.h"

#include <limits.h>
#include <math.h>
#include <stddef.h>
#include <stdlib.h>

static int mono_bass_cutoff_is_supported(int cutoff_hz) {
    return cutoff_hz == 60 || cutoff_hz == 80 || cutoff_hz == 100 ||
        cutoff_hz == 120 || cutoff_hz == 150 || cutoff_hz == 200;
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
    if (spatial->seat_focus_enabled) {
        spatial->seat_delay_capacity = (size_t)maximum_seat_latency_frames + 2;
        spatial->seat_delay_lines = (float *)calloc(2 * spatial->seat_delay_capacity, sizeof(float));
        if (spatial->seat_delay_lines == NULL) return 0;
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
