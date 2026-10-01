#include "dsp_dynamics.h"

#include <math.h>
#include <stddef.h>

static int config_is_valid(const dsp_dynamics_config *config) {
    if (config == NULL || (config->compressor_enabled != 0 && config->compressor_enabled != 1) ||
        (config->limiter_enabled != 0 && config->limiter_enabled != 1)) {
        return 0;
    }
    return isfinite(config->compressor_threshold_db) && config->compressor_threshold_db >= -60.0 &&
        config->compressor_threshold_db <= 0.0 && isfinite(config->compressor_ratio) &&
        config->compressor_ratio >= 1.0 && config->compressor_ratio <= 20.0 &&
        isfinite(config->compressor_attack_ms) && config->compressor_attack_ms >= 0.1 &&
        config->compressor_attack_ms <= 2000.0 && isfinite(config->compressor_release_ms) &&
        config->compressor_release_ms >= 0.1 && config->compressor_release_ms <= 2000.0 &&
        isfinite(config->compressor_knee_db) && config->compressor_knee_db >= 0.0 &&
        config->compressor_knee_db <= 24.0 && isfinite(config->compressor_makeup_db) &&
        config->compressor_makeup_db >= -24.0 && config->compressor_makeup_db <= 24.0 &&
        isfinite(config->limiter_threshold_db) && config->limiter_threshold_db >= -12.0 &&
        config->limiter_threshold_db <= 0.0 && isfinite(config->limiter_release_ms) &&
        config->limiter_release_ms >= 1.0 && config->limiter_release_ms <= 2000.0;
}

static float time_coefficient(int sample_rate, double time_ms) {
    return (float)exp(-1.0 / ((double)sample_rate * time_ms * 0.001));
}

int dsp_dynamics_prepare(dsp_dynamics *dynamics, int sample_rate, const dsp_dynamics_config *config) {
    if (dynamics == NULL || sample_rate < 8000 || sample_rate > 192000 || !config_is_valid(config)) {
        return 0;
    }
    dynamics->config = *config;
    dynamics->compressor_attack_coefficient = time_coefficient(sample_rate, config->compressor_attack_ms);
    dynamics->compressor_release_coefficient = time_coefficient(sample_rate, config->compressor_release_ms);
    dynamics->compressor_makeup_linear = (float)pow(10.0, config->compressor_makeup_db / 20.0);
    dynamics->limiter_release_coefficient = time_coefficient(sample_rate, config->limiter_release_ms);
    dynamics->limiter_threshold_linear = (float)pow(10.0, config->limiter_threshold_db / 20.0);
    dsp_dynamics_reset(dynamics);
    return isfinite(dynamics->compressor_attack_coefficient) &&
        isfinite(dynamics->compressor_release_coefficient) &&
        isfinite(dynamics->compressor_makeup_linear) &&
        isfinite(dynamics->limiter_release_coefficient) &&
        isfinite(dynamics->limiter_threshold_linear);
}

void dsp_dynamics_reset(dsp_dynamics *dynamics) {
    if (dynamics == NULL) {
        return;
    }
    dynamics->compressor_envelope = 0.0f;
    dynamics->limiter_gain = 1.0f;
}

static float compressor_gain(const dsp_dynamics *dynamics) {
    const float level_db = 20.0f * log10f(fmaxf(dynamics->compressor_envelope, 1e-12f));
    const float threshold = (float)dynamics->config.compressor_threshold_db;
    const float ratio = (float)dynamics->config.compressor_ratio;
    const float knee = (float)dynamics->config.compressor_knee_db;
    const float overshoot = level_db - threshold;
    float reduction_db = 0.0f;
    if (knee <= 0.0f) {
        if (overshoot > 0.0f) {
            reduction_db = (threshold + overshoot / ratio) - level_db;
        }
    } else if (overshoot > -knee * 0.5f) {
        if (overshoot >= knee * 0.5f) {
            reduction_db = (threshold + overshoot / ratio) - level_db;
        } else {
            const float curved = overshoot + knee * 0.5f;
            reduction_db = (1.0f / ratio - 1.0f) * curved * curved / (2.0f * knee);
        }
    }
    return (float)pow(10.0, reduction_db / 20.0) * dynamics->compressor_makeup_linear;
}

void dsp_dynamics_process_frame(dsp_dynamics *dynamics, float *left, float *right, int channels) {
    if (dynamics == NULL || left == NULL || right == NULL || channels < 1 || channels > 2) {
        return;
    }

    if (dynamics->config.compressor_enabled) {
        float detector = fabsf(*left);
        if (channels == 2) detector = fmaxf(detector, fabsf(*right));
        const float coefficient = detector > dynamics->compressor_envelope
            ? dynamics->compressor_attack_coefficient
            : dynamics->compressor_release_coefficient;
        dynamics->compressor_envelope = coefficient * dynamics->compressor_envelope +
            (1.0f - coefficient) * detector;
        const float gain = compressor_gain(dynamics);
        *left *= gain;
        if (channels == 2) *right *= gain;
    }

    if (dynamics->config.limiter_enabled) {
        float peak = fabsf(*left);
        if (channels == 2) peak = fmaxf(peak, fabsf(*right));
        if (peak > dynamics->limiter_threshold_linear) {
            dynamics->limiter_gain = dynamics->limiter_threshold_linear / peak;
        } else {
            dynamics->limiter_gain = dynamics->limiter_release_coefficient * dynamics->limiter_gain +
                (1.0f - dynamics->limiter_release_coefficient);
        }
        *left *= dynamics->limiter_gain;
        if (channels == 2) *right *= dynamics->limiter_gain;
    }
}
