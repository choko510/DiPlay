#include "dsp_spatial.h"

#include <math.h>
#include <stddef.h>

static int mono_bass_cutoff_is_supported(int cutoff_hz) {
    return cutoff_hz == 60 || cutoff_hz == 80 || cutoff_hz == 100 ||
        cutoff_hz == 120 || cutoff_hz == 150 || cutoff_hz == 200;
}

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    const dsp_spatial_config *config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count) {
    if (spatial == NULL || config == NULL || !isfinite(config->width) ||
        config->width < 0.0 || config->width > 2.0 ||
        (config->mono_bass_enabled != 0 && config->mono_bass_enabled != 1) ||
        !mono_bass_cutoff_is_supported(config->mono_bass_cutoff_hz) ||
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
