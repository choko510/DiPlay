#include "dsp_multiband.h"

#include <math.h>
#include <stddef.h>

static int compressor_config_is_valid(const dsp_multiband_compressor_config *config) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        isfinite(config->threshold_db) && config->threshold_db >= -60.0 && config->threshold_db <= 0.0 &&
        isfinite(config->ratio) && config->ratio >= 1.0 && config->ratio <= 20.0 &&
        isfinite(config->attack_ms) && config->attack_ms >= 0.1 && config->attack_ms <= 2000.0 &&
        isfinite(config->release_ms) && config->release_ms >= 0.1 && config->release_ms <= 2000.0 &&
        isfinite(config->knee_db) && config->knee_db >= 0.0 && config->knee_db <= 24.0 &&
        isfinite(config->makeup_db) && config->makeup_db >= -24.0 && config->makeup_db <= 24.0;
}

static int design_butterworth_coefficients(
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

static int prepare_filter_pair(
    dsp_biquad filters[DSP_MULTIBAND_FILTER_STAGES],
    int sample_rate,
    double frequency_hz,
    int high_pass) {
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    if (!design_butterworth_coefficients(sample_rate, frequency_hz, high_pass, coefficients)) return 0;
    return dsp_biquad_prepare(&filters[0], coefficients) && dsp_biquad_prepare(&filters[1], coefficients);
}

int dsp_multiband_prepare(dsp_multiband *multiband, int sample_rate, const dsp_multiband_config *config) {
    if (multiband == NULL || config == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        (config->enabled != 0 && config->enabled != 1) || !isfinite(config->low_mid_crossover_hz) ||
        !isfinite(config->mid_high_crossover_hz) || config->low_mid_crossover_hz < 20.0 ||
        config->low_mid_crossover_hz > 1000.0 || config->mid_high_crossover_hz < 500.0 ||
        config->mid_high_crossover_hz > 3500.0 ||
        config->low_mid_crossover_hz >= config->mid_high_crossover_hz) {
        return 0;
    }
    multiband->config = *config;
    for (size_t index = 0; index < DSP_MULTIBAND_COUNT; index++) {
        if (!compressor_config_is_valid(&config->bands[index])) return 0;
    }
    if (!config->enabled) return 1;
    if (config->low_mid_crossover_hz >= (double)sample_rate * 0.45 ||
        config->mid_high_crossover_hz >= (double)sample_rate * 0.45 ||
        !prepare_filter_pair(multiband->low_pass, sample_rate, config->low_mid_crossover_hz, 0) ||
        !prepare_filter_pair(multiband->mid_high_pass, sample_rate, config->low_mid_crossover_hz, 1) ||
        !prepare_filter_pair(multiband->mid_low_pass, sample_rate, config->mid_high_crossover_hz, 0) ||
        !prepare_filter_pair(multiband->high_pass, sample_rate, config->mid_high_crossover_hz, 1)) {
        return 0;
    }
    for (size_t index = 0; index < DSP_MULTIBAND_COUNT; index++) {
        const dsp_multiband_compressor_config *band = &config->bands[index];
        const dsp_dynamics_config dynamics_config = {
            .compressor_enabled = band->enabled,
            .compressor_threshold_db = band->threshold_db,
            .compressor_ratio = band->ratio,
            .compressor_attack_ms = band->attack_ms,
            .compressor_release_ms = band->release_ms,
            .compressor_knee_db = band->knee_db,
            .compressor_makeup_db = band->makeup_db,
            .limiter_enabled = 0,
            .limiter_threshold_db = -1.0,
            .limiter_release_ms = 60.0,
        };
        if (!dsp_dynamics_prepare(&multiband->compressors[index], sample_rate, &dynamics_config)) return 0;
    }
    return 1;
}

void dsp_multiband_reset(dsp_multiband *multiband) {
    if (multiband == NULL) return;
    for (size_t stage = 0; stage < DSP_MULTIBAND_FILTER_STAGES; stage++) {
        dsp_biquad_reset(&multiband->low_pass[stage]);
        dsp_biquad_reset(&multiband->mid_high_pass[stage]);
        dsp_biquad_reset(&multiband->mid_low_pass[stage]);
        dsp_biquad_reset(&multiband->high_pass[stage]);
    }
    for (size_t band = 0; band < DSP_MULTIBAND_COUNT; band++) {
        dsp_dynamics_reset(&multiband->compressors[band]);
    }
}

static float process_filter_pair(
    dsp_biquad filters[DSP_MULTIBAND_FILTER_STAGES],
    float sample,
    int channel) {
    for (size_t stage = 0; stage < DSP_MULTIBAND_FILTER_STAGES; stage++) {
        sample = dsp_biquad_process_sample(&filters[stage], sample, channel);
    }
    return sample;
}

void dsp_multiband_process_frame(dsp_multiband *multiband, float *left, float *right, int channels) {
    if (multiband == NULL || left == NULL || right == NULL || channels < 1 || channels > 2 ||
        !multiband->config.enabled) {
        return;
    }
    float band_left[DSP_MULTIBAND_COUNT] = {
        process_filter_pair(multiband->low_pass, *left, 0),
        process_filter_pair(
            multiband->mid_low_pass,
            process_filter_pair(multiband->mid_high_pass, *left, 0),
            0),
        process_filter_pair(multiband->high_pass, *left, 0),
    };
    float band_right[DSP_MULTIBAND_COUNT] = {0.0f, 0.0f, 0.0f};
    if (channels == 2) {
        band_right[0] = process_filter_pair(multiband->low_pass, *right, 1);
        band_right[1] = process_filter_pair(
            multiband->mid_low_pass,
            process_filter_pair(multiband->mid_high_pass, *right, 1),
            1);
        band_right[2] = process_filter_pair(multiband->high_pass, *right, 1);
    }
    float output_left = 0.0f;
    float output_right = 0.0f;
    for (size_t band = 0; band < DSP_MULTIBAND_COUNT; band++) {
        dsp_dynamics_process_compressor_frame(
            &multiband->compressors[band], &band_left[band], &band_right[band], channels);
        output_left += band_left[band];
        if (channels == 2) output_right += band_right[band];
    }
    *left = output_left;
    if (channels == 2) *right = output_right;
}

void dsp_multiband_flush_denormals(dsp_multiband *multiband) {
    if (multiband == NULL) return;
    for (size_t stage = 0; stage < DSP_MULTIBAND_FILTER_STAGES; stage++) {
        dsp_biquad_flush_denormals(&multiband->low_pass[stage]);
        dsp_biquad_flush_denormals(&multiband->mid_high_pass[stage]);
        dsp_biquad_flush_denormals(&multiband->mid_low_pass[stage]);
        dsp_biquad_flush_denormals(&multiband->high_pass[stage]);
    }
}
