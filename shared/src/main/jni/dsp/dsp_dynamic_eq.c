#include "dsp_dynamic_eq.h"

#include <math.h>
#include <stddef.h>

static int band_config_is_valid(const dsp_dynamic_eq_band_config *config) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        (config->mode == DSP_DYNAMIC_EQ_CUT || config->mode == DSP_DYNAMIC_EQ_BOOST) &&
        isfinite(config->frequency_hz) && config->frequency_hz >= 20.0 && config->frequency_hz <= 20000.0 &&
        isfinite(config->q) && config->q >= 0.1 && config->q <= 20.0 &&
        isfinite(config->threshold_db) && config->threshold_db >= -60.0 && config->threshold_db <= 0.0 &&
        isfinite(config->ratio) && config->ratio >= 1.0 && config->ratio <= 20.0 &&
        isfinite(config->attack_ms) && config->attack_ms >= 0.1 && config->attack_ms <= 2000.0 &&
        isfinite(config->release_ms) && config->release_ms >= 0.1 && config->release_ms <= 2000.0 &&
        isfinite(config->max_boost_db) && config->max_boost_db >= 0.0 && config->max_boost_db <= 12.0 &&
        isfinite(config->max_cut_db) && config->max_cut_db >= 0.0 && config->max_cut_db <= 12.0;
}

static float time_coefficient(int sample_rate, double time_ms) {
    return (float)exp(-1.0 / ((double)sample_rate * time_ms * 0.001));
}

static int design_detector(
    int sample_rate,
    const dsp_dynamic_eq_band_config *config,
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT]) {
    if (config->frequency_hz >= (double)sample_rate * 0.45) return 0;
    const double omega = 2.0 * 3.14159265358979323846 * config->frequency_hz / (double)sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * config->q);
    const double a0 = 1.0 + alpha;
    if (!isfinite(cosine) || !isfinite(alpha) || !isfinite(a0) || a0 <= 0.0) return 0;
    coefficients[0] = alpha / a0;
    coefficients[1] = 0.0;
    coefficients[2] = -alpha / a0;
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
    return 1;
}

static int design_dynamic_peak(
    int sample_rate,
    const dsp_dynamic_eq_band_config *config,
    double gain_db,
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT]) {
    if (config->frequency_hz >= (double)sample_rate * 0.45) return 0;
    const double amplitude = pow(10.0, gain_db / 40.0);
    const double omega = 2.0 * 3.14159265358979323846 * config->frequency_hz / (double)sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * config->q);
    const double a0 = 1.0 + alpha / amplitude;
    if (!isfinite(amplitude) || !isfinite(cosine) || !isfinite(alpha) || !isfinite(a0) || a0 <= 0.0) {
        return 0;
    }
    coefficients[0] = (1.0 + alpha * amplitude) / a0;
    coefficients[1] = -2.0 * cosine / a0;
    coefficients[2] = (1.0 - alpha * amplitude) / a0;
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha / amplitude) / a0;
    return 1;
}

int dsp_dynamic_eq_prepare(dsp_dynamic_eq *dynamic_eq, int sample_rate, const dsp_dynamic_eq_config *config) {
    if (dynamic_eq == NULL || config == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        (config->enabled != 0 && config->enabled != 1)) {
        return 0;
    }
    dynamic_eq->config = *config;
    for (size_t index = 0; index < DSP_DYNAMIC_EQ_MAX_BANDS; index++) {
        dsp_dynamic_eq_band *band = &dynamic_eq->bands[index];
        band->config = config->bands[index];
        if (!band_config_is_valid(&band->config)) return 0;
        band->envelope = 0.0f;
        band->attack_coefficient = time_coefficient(sample_rate, band->config.attack_ms);
        band->release_coefficient = time_coefficient(sample_rate, band->config.release_ms);
        band->current_mix = 0.0f;
        band->target_mix = 0.0f;
        band->mix_step = 0.0f;
        band->frames_until_control = 0;
        if (!config->enabled || !band->config.enabled) continue;

        double detector_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
        double filter_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
        const double static_gain_db = band->config.mode == DSP_DYNAMIC_EQ_CUT
            ? -band->config.max_cut_db
            : band->config.max_boost_db;
        if (!design_detector(sample_rate, &band->config, detector_coefficients) ||
            !design_dynamic_peak(sample_rate, &band->config, static_gain_db, filter_coefficients) ||
            !dsp_biquad_prepare(&band->detector, detector_coefficients) ||
            !dsp_biquad_prepare(&band->filter, filter_coefficients)) {
            return 0;
        }
    }
    return 1;
}

void dsp_dynamic_eq_reset(dsp_dynamic_eq *dynamic_eq) {
    if (dynamic_eq == NULL) return;
    for (size_t index = 0; index < DSP_DYNAMIC_EQ_MAX_BANDS; index++) {
        dsp_dynamic_eq_band *band = &dynamic_eq->bands[index];
        dsp_biquad_reset(&band->detector);
        dsp_biquad_reset(&band->filter);
        band->envelope = 0.0f;
        band->current_mix = 0.0f;
        band->target_mix = 0.0f;
        band->mix_step = 0.0f;
        band->frames_until_control = 0;
    }
}

static float target_mix(const dsp_dynamic_eq_band *band) {
    const float level_db = 20.0f * log10f(fmaxf(band->envelope, 1e-12f));
    const float overshoot = band->config.mode == DSP_DYNAMIC_EQ_CUT
        ? level_db - (float)band->config.threshold_db
        : (float)band->config.threshold_db - level_db;
    if (overshoot <= 0.0f || band->config.ratio <= 1.0) return 0.0f;
    const float maximum = (float)(band->config.mode == DSP_DYNAMIC_EQ_CUT
        ? band->config.max_cut_db
        : band->config.max_boost_db);
    if (maximum <= 0.0f) return 0.0f;
    const float gain_change_db = fminf(
        maximum,
        overshoot * (float)(1.0 - 1.0 / band->config.ratio));
    const float sign = band->config.mode == DSP_DYNAMIC_EQ_CUT ? -1.0f : 1.0f;
    const float desired_gain = powf(10.0f, sign * gain_change_db / 20.0f);
    const float static_gain = powf(10.0f, sign * maximum / 20.0f);
    const float denominator = static_gain - 1.0f;
    if (!isfinite(desired_gain) || !isfinite(static_gain) || fabsf(denominator) <= 1e-12f) return 0.0f;
    const float mix = (desired_gain - 1.0f) / denominator;
    return fminf(1.0f, fmaxf(0.0f, mix));
}

void dsp_dynamic_eq_process_frame(dsp_dynamic_eq *dynamic_eq, float *left, float *right, int channels) {
    if (dynamic_eq == NULL || left == NULL || right == NULL || channels < 1 || channels > 2 ||
        !dynamic_eq->config.enabled) {
        return;
    }
    for (size_t index = 0; index < DSP_DYNAMIC_EQ_MAX_BANDS; index++) {
        dsp_dynamic_eq_band *band = &dynamic_eq->bands[index];
        if (!band->config.enabled) continue;

        const float source_left = *left;
        const float source_right = channels == 2 ? *right : 0.0f;
        const float detector_left = dsp_biquad_process_sample(&band->detector, source_left, 0);
        float detector_peak = fabsf(detector_left);
        if (channels == 2) {
            const float detector_right = dsp_biquad_process_sample(&band->detector, source_right, 1);
            detector_peak = fmaxf(detector_peak, fabsf(detector_right));
        }
        const float envelope_coefficient = detector_peak > band->envelope
            ? band->attack_coefficient
            : band->release_coefficient;
        band->envelope = envelope_coefficient * band->envelope +
            (1.0f - envelope_coefficient) * detector_peak;

        if (band->frames_until_control == 0) {
            band->target_mix = target_mix(band);
            band->mix_step = (band->target_mix - band->current_mix) / DSP_DYNAMIC_EQ_CONTROL_FRAMES;
            band->frames_until_control = DSP_DYNAMIC_EQ_CONTROL_FRAMES;
        }
        band->current_mix += band->mix_step;
        band->frames_until_control--;
        if (band->frames_until_control == 0) band->current_mix = band->target_mix;
        band->current_mix = fminf(1.0f, fmaxf(0.0f, band->current_mix));

        const float filtered_left = dsp_biquad_process_sample(&band->filter, source_left, 0);
        *left = source_left + band->current_mix * (filtered_left - source_left);
        if (channels == 2) {
            const float filtered_right = dsp_biquad_process_sample(&band->filter, source_right, 1);
            *right = source_right + band->current_mix * (filtered_right - source_right);
        }
    }
}

void dsp_dynamic_eq_flush_denormals(dsp_dynamic_eq *dynamic_eq) {
    if (dynamic_eq == NULL) return;
    for (size_t index = 0; index < DSP_DYNAMIC_EQ_MAX_BANDS; index++) {
        dsp_biquad_flush_denormals(&dynamic_eq->bands[index].detector);
        dsp_biquad_flush_denormals(&dynamic_eq->bands[index].filter);
        if (fabsf(dynamic_eq->bands[index].envelope) < 1e-20f) dynamic_eq->bands[index].envelope = 0.0f;
    }
}
