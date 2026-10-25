#include "dsp_dynamic_bass.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

static double one_pole_alpha(double frequency_hz, int sample_rate) {
    return 1.0 - exp(-2.0 * 3.14159265358979323846 * frequency_hz / (double)sample_rate);
}

static double envelope_alpha(double time_ms, int sample_rate) {
    return exp(-1.0 / (time_ms * 0.001 * (double)sample_rate));
}

int dsp_dynamic_bass_config_is_valid(const dsp_dynamic_bass_config *config) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        isfinite(config->strength) && config->strength >= 0.0 && config->strength <= 1.0 &&
        isfinite(config->max_boost_db) && config->max_boost_db >= 0.0 && config->max_boost_db <= 3.0 &&
        isfinite(config->detector_cutoff_hz) && config->detector_cutoff_hz >= 80.0 &&
        config->detector_cutoff_hz <= 160.0 &&
        isfinite(config->silence_gate_dbfs) && config->silence_gate_dbfs >= -90.0 &&
        config->silence_gate_dbfs <= -30.0 &&
        isfinite(config->low_threshold_dbfs) && config->low_threshold_dbfs >= -60.0 &&
        config->low_threshold_dbfs <= 0.0 &&
        isfinite(config->high_threshold_dbfs) && config->high_threshold_dbfs >= -60.0 &&
        config->high_threshold_dbfs <= 0.0 && config->low_threshold_dbfs < config->high_threshold_dbfs &&
        isfinite(config->attack_ms) && config->attack_ms >= 10.0 && config->attack_ms <= 200.0 &&
        isfinite(config->release_ms) && config->release_ms >= 50.0 && config->release_ms <= 1000.0;
}

static void copy_config(dsp_dynamic_bass *bass, const dsp_dynamic_bass_config *config) {
    bass->target_enabled = config->enabled;
    bass->strength = config->strength;
    bass->max_boost_db = config->max_boost_db;
    bass->detector_cutoff_hz = config->detector_cutoff_hz;
    bass->silence_gate_dbfs = config->silence_gate_dbfs;
    bass->low_threshold_dbfs = config->low_threshold_dbfs;
    bass->high_threshold_dbfs = config->high_threshold_dbfs;
    bass->silence_gate_linear = pow(10.0, config->silence_gate_dbfs / 20.0);
    bass->low_threshold_linear = pow(10.0, config->low_threshold_dbfs / 20.0);
    bass->high_threshold_linear = pow(10.0, config->high_threshold_dbfs / 20.0);
    bass->max_gain = pow(10.0, config->max_boost_db * config->strength / 20.0);
    bass->attack_alpha = envelope_alpha(config->attack_ms, bass->sample_rate);
    bass->release_alpha = envelope_alpha(config->release_ms, bass->sample_rate);
    bass->detector_alpha = one_pole_alpha(config->detector_cutoff_hz, bass->sample_rate);
    bass->bass_alpha = one_pole_alpha(150.0, bass->sample_rate);
}

int dsp_dynamic_bass_prepare(
    dsp_dynamic_bass *bass,
    int sample_rate,
    int channels,
    const dsp_dynamic_bass_config *config) {
    if (bass == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || !dsp_dynamic_bass_config_is_valid(config)) return 0;
    memset(bass, 0, sizeof(*bass));
    bass->sample_rate = sample_rate;
    bass->channels = channels;
    copy_config(bass, config);
    bass->enabled = config->enabled;
    bass->current_gain = 1.0;
    return 1;
}

int dsp_dynamic_bass_apply_hot(dsp_dynamic_bass *bass, const dsp_dynamic_bass_config *config) {
    if (bass == NULL || bass->sample_rate < 8000 || !dsp_dynamic_bass_config_is_valid(config)) return 0;
    if (!bass->enabled && config->enabled == 0) return 1;
    copy_config(bass, config);
    if (config->enabled || bass->current_gain > 1.000001) bass->enabled = 1;
    return 1;
}

static float lowpass(float sample, float *state, double alpha) {
    const float output = (float)alpha * (sample - *state) + *state;
    *state = output;
    return output;
}

static double target_gain(const dsp_dynamic_bass *bass) {
    if (!bass->target_enabled || bass->strength <= 0.0 || bass->max_boost_db <= 0.0 ||
        bass->envelope < bass->silence_gate_linear || bass->envelope >= bass->high_threshold_linear) {
        return 1.0;
    }
    double amount = 1.0;
    if (bass->envelope > bass->low_threshold_linear) {
        amount = (bass->high_threshold_linear - bass->envelope) /
            (bass->high_threshold_linear - bass->low_threshold_linear);
    }
    amount = fmax(0.0, fmin(1.0, amount));
    return 1.0 + amount * (bass->max_gain - 1.0);
}

void dsp_dynamic_bass_process_frame(dsp_dynamic_bass *bass, float *left, float *right, int channels) {
    if (bass == NULL || left == NULL || channels < 1 || channels > 2 || !bass->enabled) return;
    const float detector_left = lowpass(*left, &bass->detector_state[0], bass->detector_alpha);
    const float detector_right = channels == 2 && right != NULL
        ? lowpass(*right, &bass->detector_state[1], bass->detector_alpha)
        : 0.0f;
    const double level = channels == 2
        ? sqrt(0.5 * ((double)detector_left * detector_left + (double)detector_right * detector_right))
        : fabs((double)detector_left);
    const double envelope_coefficient = level > bass->envelope ? bass->attack_alpha : bass->release_alpha;
    bass->envelope = envelope_coefficient * bass->envelope + (1.0 - envelope_coefficient) * level;
    const double desired_gain = target_gain(bass);
    const double gain_coefficient = desired_gain > bass->current_gain ? bass->attack_alpha : bass->release_alpha;
    bass->current_gain = gain_coefficient * bass->current_gain +
        (1.0 - gain_coefficient) * desired_gain;

    const float bass_left = lowpass(*left, &bass->bass_state[0], bass->bass_alpha);
    *left = (float)(*left + (bass->current_gain - 1.0) * bass_left);
    if (channels == 2 && right != NULL) {
        const float bass_right = lowpass(*right, &bass->bass_state[1], bass->bass_alpha);
        *right = (float)(*right + (bass->current_gain - 1.0) * bass_right);
    }
    if (!bass->target_enabled && bass->current_gain <= 1.000001) {
        bass->current_gain = 1.0;
        bass->enabled = 0;
    }
}

void dsp_dynamic_bass_reset(dsp_dynamic_bass *bass) {
    if (bass == NULL) return;
    memset(bass->detector_state, 0, sizeof(bass->detector_state));
    memset(bass->bass_state, 0, sizeof(bass->bass_state));
    bass->envelope = 0.0;
    bass->current_gain = 1.0;
}

void dsp_dynamic_bass_flush_denormals(dsp_dynamic_bass *bass) {
    if (bass == NULL) return;
    for (size_t channel = 0; channel < 2; channel++) {
        if (fabsf(bass->detector_state[channel]) < 1.0e-20f) bass->detector_state[channel] = 0.0f;
        if (fabsf(bass->bass_state[channel]) < 1.0e-20f) bass->bass_state[channel] = 0.0f;
    }
    if (fabs(bass->envelope) < 1.0e-20) bass->envelope = 0.0;
}
