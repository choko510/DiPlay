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

static uint32_t hash_bytes(uint32_t hash, const void *value, size_t size) {
    const unsigned char *bytes = (const unsigned char *)value;
    for (size_t index = 0; index < size; index++) {
        hash = (hash ^ bytes[index]) * UINT32_C(16777619);
    }
    return hash;
}

static uint32_t hot_params_fingerprint(const dsp_dynamics_hot_params *params) {
    uint32_t hash = UINT32_C(2166136261);
#define HASH_HOT_FIELD(field) hash = hash_bytes(hash, &params->field, sizeof(params->field))
    HASH_HOT_FIELD(version);
    HASH_HOT_FIELD(sample_rate);
    HASH_HOT_FIELD(config.compressor_enabled);
    HASH_HOT_FIELD(config.compressor_threshold_db);
    HASH_HOT_FIELD(config.compressor_ratio);
    HASH_HOT_FIELD(config.compressor_attack_ms);
    HASH_HOT_FIELD(config.compressor_release_ms);
    HASH_HOT_FIELD(config.compressor_knee_db);
    HASH_HOT_FIELD(config.compressor_makeup_db);
    HASH_HOT_FIELD(config.limiter_enabled);
    HASH_HOT_FIELD(config.limiter_threshold_db);
    HASH_HOT_FIELD(config.limiter_release_ms);
    HASH_HOT_FIELD(compressor_attack_coefficient);
    HASH_HOT_FIELD(compressor_release_coefficient);
    HASH_HOT_FIELD(compressor_makeup_linear);
    HASH_HOT_FIELD(limiter_release_coefficient);
    HASH_HOT_FIELD(limiter_threshold_linear);
#undef HASH_HOT_FIELD
    return hash == 0 ? 1 : hash;
}

static int prepared_float_matches(float actual, float expected) {
    return isfinite(actual) && fabsf(actual - expected) <= fmaxf(1.0e-7f, fabsf(expected) * 1.0e-5f);
}

int dsp_dynamics_hot_params_validate(dsp_dynamics_hot_params *params) {
    if (params == NULL || params->version != 1 || params->sample_rate < 8000 || params->sample_rate > 192000 ||
        !config_is_valid(&params->config)) return 0;
    const float expected_attack = time_coefficient(params->sample_rate, params->config.compressor_attack_ms);
    const float expected_release = time_coefficient(params->sample_rate, params->config.compressor_release_ms);
    const float expected_makeup = (float)pow(10.0, params->config.compressor_makeup_db / 20.0);
    const float expected_limiter_release = time_coefficient(params->sample_rate, params->config.limiter_release_ms);
    const float expected_limiter_threshold = (float)pow(10.0, params->config.limiter_threshold_db / 20.0);
    if (!prepared_float_matches(params->compressor_attack_coefficient, expected_attack) ||
        !prepared_float_matches(params->compressor_release_coefficient, expected_release) ||
        !prepared_float_matches(params->compressor_makeup_linear, expected_makeup) ||
        !prepared_float_matches(params->limiter_release_coefficient, expected_limiter_release) ||
        !prepared_float_matches(params->limiter_threshold_linear, expected_limiter_threshold)) return 0;
    params->validation_token = hot_params_fingerprint(params);
    return 1;
}

int dsp_dynamics_prepare_hot_params(
    int sample_rate,
    const dsp_dynamics_config *config,
    dsp_dynamics_hot_params *params) {
    if (sample_rate < 8000 || sample_rate > 192000 || params == NULL || !config_is_valid(config)) return 0;
    dsp_dynamics_hot_params prepared = {
        .version = 1,
        .sample_rate = sample_rate,
        .config = *config,
        .compressor_attack_coefficient = time_coefficient(sample_rate, config->compressor_attack_ms),
        .compressor_release_coefficient = time_coefficient(sample_rate, config->compressor_release_ms),
        .compressor_makeup_linear = (float)pow(10.0, config->compressor_makeup_db / 20.0),
        .limiter_release_coefficient = time_coefficient(sample_rate, config->limiter_release_ms),
        .limiter_threshold_linear = (float)pow(10.0, config->limiter_threshold_db / 20.0),
    };
    *params = prepared;
    return dsp_dynamics_hot_params_validate(params);
}

static int hot_params_are_valid(const dsp_dynamics *dynamics, const dsp_dynamics_hot_params *params) {
    return dynamics != NULL && params != NULL && params->version == 1 &&
        params->validation_token == hot_params_fingerprint(params) && params->sample_rate == dynamics->sample_rate &&
        config_is_valid(&params->config) &&
        params->config.compressor_enabled == dynamics->config.compressor_enabled &&
        params->config.limiter_enabled == dynamics->config.limiter_enabled &&
        params->config.limiter_threshold_db <= dynamics->config.limiter_threshold_db &&
        params->limiter_threshold_linear <= dynamics->limiter_threshold_linear &&
        isfinite(params->compressor_attack_coefficient) && params->compressor_attack_coefficient > 0.0f &&
        params->compressor_attack_coefficient < 1.0f &&
        isfinite(params->compressor_release_coefficient) && params->compressor_release_coefficient > 0.0f &&
        params->compressor_release_coefficient < 1.0f &&
        isfinite(params->compressor_makeup_linear) && params->compressor_makeup_linear >= 0.0630957f &&
        params->compressor_makeup_linear <= 15.848932f &&
        isfinite(params->limiter_release_coefficient) && params->limiter_release_coefficient > 0.0f &&
        params->limiter_release_coefficient < 1.0f &&
        isfinite(params->limiter_threshold_linear) && params->limiter_threshold_linear >= 0.2511886f &&
        params->limiter_threshold_linear <= 1.0f;
}

int dsp_dynamics_apply_hot_params(
    dsp_dynamics *dynamics,
    const dsp_dynamics_hot_params *params,
    int makeup_ramp_frames) {
    if (dynamics == NULL || params == NULL) return DSP_DYNAMICS_HOT_INVALID;
    if (params->version == 1 && config_is_valid(&params->config) &&
        (params->config.compressor_enabled != dynamics->config.compressor_enabled ||
            params->config.limiter_enabled != dynamics->config.limiter_enabled ||
            params->config.limiter_threshold_db > dynamics->config.limiter_threshold_db)) {
        return DSP_DYNAMICS_HOT_DEFERRED;
    }
    if (!hot_params_are_valid(dynamics, params) || makeup_ramp_frames < 1 ||
        makeup_ramp_frames > dynamics->sample_rate / 5) return DSP_DYNAMICS_HOT_INVALID;
    const float makeup_start = dynamics->compressor_makeup_linear;
    const float makeup_step = (params->compressor_makeup_linear - makeup_start) / (float)makeup_ramp_frames;
    const float threshold_step = ((float)params->config.compressor_threshold_db - dynamics->compressor_threshold_db) /
        (float)makeup_ramp_frames;
    const float ratio_step = ((float)params->config.compressor_ratio - dynamics->compressor_ratio) /
        (float)makeup_ramp_frames;
    const float knee_step = ((float)params->config.compressor_knee_db - dynamics->compressor_knee_db) /
        (float)makeup_ramp_frames;
    dynamics->config = params->config;
    dynamics->compressor_attack_coefficient = params->compressor_attack_coefficient;
    dynamics->compressor_release_coefficient = params->compressor_release_coefficient;
    dynamics->limiter_release_coefficient = params->limiter_release_coefficient;
    dynamics->limiter_threshold_linear = params->limiter_threshold_linear;
    dynamics->compressor_makeup_target_linear = params->compressor_makeup_linear;
    dynamics->compressor_makeup_step = makeup_step;
    dynamics->compressor_threshold_step = threshold_step;
    dynamics->compressor_ratio_step = ratio_step;
    dynamics->compressor_knee_step = knee_step;
    dynamics->compressor_makeup_ramp_remaining = makeup_ramp_frames;
    dynamics->compressor_gain_slew_active = params->config.compressor_enabled;
    if (!params->config.compressor_enabled) {
        dynamics->compressor_makeup_linear = params->compressor_makeup_linear;
        dynamics->compressor_makeup_target_linear = params->compressor_makeup_linear;
        dynamics->compressor_threshold_db = (float)params->config.compressor_threshold_db;
        dynamics->compressor_ratio = (float)params->config.compressor_ratio;
        dynamics->compressor_knee_db = (float)params->config.compressor_knee_db;
        dynamics->compressor_makeup_ramp_remaining = 0;
    }
    return DSP_DYNAMICS_HOT_APPLIED;
}

int dsp_dynamics_hot_transition_pending(const dsp_dynamics *dynamics) {
    return dynamics != NULL && (dynamics->compressor_makeup_ramp_remaining > 0 ||
        dynamics->compressor_gain_slew_active);
}

int dsp_dynamics_prepare(dsp_dynamics *dynamics, int sample_rate, const dsp_dynamics_config *config) {
    if (dynamics == NULL || sample_rate < 8000 || sample_rate > 192000 || !config_is_valid(config)) {
        return 0;
    }
    dynamics->config = *config;
    dynamics->sample_rate = sample_rate;
    dynamics->compressor_threshold_db = (float)config->compressor_threshold_db;
    dynamics->compressor_ratio = (float)config->compressor_ratio;
    dynamics->compressor_knee_db = (float)config->compressor_knee_db;
    dynamics->last_compressor_gain = 1.0f;
    dynamics->compressor_gain_slew_active = 0;
    dynamics->compressor_attack_coefficient = time_coefficient(sample_rate, config->compressor_attack_ms);
    dynamics->compressor_release_coefficient = time_coefficient(sample_rate, config->compressor_release_ms);
    dynamics->compressor_makeup_linear = (float)pow(10.0, config->compressor_makeup_db / 20.0);
    dynamics->compressor_makeup_target_linear = dynamics->compressor_makeup_linear;
    dynamics->compressor_makeup_step = 0.0f;
    dynamics->compressor_makeup_ramp_remaining = 0;
    dynamics->compressor_threshold_step = 0.0f;
    dynamics->compressor_ratio_step = 0.0f;
    dynamics->compressor_knee_step = 0.0f;
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
    dynamics->compressor_makeup_linear = dynamics->compressor_makeup_target_linear;
    dynamics->compressor_makeup_step = 0.0f;
    dynamics->compressor_makeup_ramp_remaining = 0;
    dynamics->compressor_threshold_db = (float)dynamics->config.compressor_threshold_db;
    dynamics->compressor_ratio = (float)dynamics->config.compressor_ratio;
    dynamics->compressor_knee_db = (float)dynamics->config.compressor_knee_db;
    dynamics->compressor_threshold_step = 0.0f;
    dynamics->compressor_ratio_step = 0.0f;
    dynamics->compressor_knee_step = 0.0f;
    dynamics->last_compressor_gain = 1.0f;
    dynamics->compressor_gain_slew_active = 0;
}

static float compressor_gain(const dsp_dynamics *dynamics) {
    const float level_db = 20.0f * log10f(fmaxf(dynamics->compressor_envelope, 1e-12f));
    const float threshold = dynamics->compressor_threshold_db;
    const float ratio = dynamics->compressor_ratio;
    const float knee = dynamics->compressor_knee_db;
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

void dsp_dynamics_process_compressor_frame(dsp_dynamics *dynamics, float *left, float *right, int channels) {
    if (dynamics == NULL || left == NULL || right == NULL || channels < 1 || channels > 2) {
        return;
    }

    if (dynamics->compressor_makeup_ramp_remaining > 0) {
        dynamics->compressor_makeup_linear += dynamics->compressor_makeup_step;
        dynamics->compressor_threshold_db += dynamics->compressor_threshold_step;
        dynamics->compressor_ratio += dynamics->compressor_ratio_step;
        dynamics->compressor_knee_db += dynamics->compressor_knee_step;
        if (--dynamics->compressor_makeup_ramp_remaining == 0) {
            dynamics->compressor_makeup_linear = dynamics->compressor_makeup_target_linear;
            dynamics->compressor_threshold_db = (float)dynamics->config.compressor_threshold_db;
            dynamics->compressor_ratio = (float)dynamics->config.compressor_ratio;
            dynamics->compressor_knee_db = (float)dynamics->config.compressor_knee_db;
        }
    }
    if (dynamics->config.compressor_enabled) {
        float detector = fabsf(*left);
        if (channels == 2) detector = fmaxf(detector, fabsf(*right));
        const float coefficient = detector > dynamics->compressor_envelope
            ? dynamics->compressor_attack_coefficient
            : dynamics->compressor_release_coefficient;
        dynamics->compressor_envelope = coefficient * dynamics->compressor_envelope +
            (1.0f - coefficient) * detector;
        const float target_gain = compressor_gain(dynamics);
        float gain = target_gain;
        if (dynamics->compressor_gain_slew_active) {
            const float gain_difference = target_gain - dynamics->last_compressor_gain;
            const float maximum_step = 0.01f;
            if (gain_difference > maximum_step) {
                gain = dynamics->last_compressor_gain + maximum_step;
            } else if (gain_difference < -maximum_step) {
                gain = dynamics->last_compressor_gain - maximum_step;
            } else {
                dynamics->compressor_gain_slew_active = dynamics->compressor_makeup_ramp_remaining > 0;
            }
        }
        dynamics->last_compressor_gain = gain;
        *left *= gain;
        if (channels == 2) *right *= gain;
    }
}

void dsp_dynamics_process_limiter_frame(dsp_dynamics *dynamics, float *left, float *right, int channels) {
    if (dynamics == NULL || left == NULL || right == NULL || channels < 1 || channels > 2) {
        return;
    }
    if (dynamics->config.limiter_enabled) {
        float peak = fabsf(*left);
        if (channels == 2) peak = fmaxf(peak, fabsf(*right));
        const float required_gain = peak > dynamics->limiter_threshold_linear
            ? dynamics->limiter_threshold_linear / peak
            : 1.0f;
        const float released_gain = dynamics->limiter_release_coefficient * dynamics->limiter_gain +
            (1.0f - dynamics->limiter_release_coefficient);
        dynamics->limiter_gain = fminf(released_gain, required_gain);
        *left *= dynamics->limiter_gain;
        if (channels == 2) *right *= dynamics->limiter_gain;
    }
}
