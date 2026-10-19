#include "dsp_harmonic.h"

#include <math.h>
#include <string.h>

static int quality_tap_count(int quality) {
    switch (quality) {
        case DSP_HARMONIC_QUALITY_LOW: return 9;
        case DSP_HARMONIC_QUALITY_BALANCED: return 17;
        case DSP_HARMONIC_QUALITY_HIGH: return 33;
        default: return 0;
    }
}

static int design_butterworth(
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

static double sinc(double value) {
    if (fabs(value) < 1e-12) return 1.0;
    const double angle = 3.14159265358979323846 * value;
    return sin(angle) / angle;
}

static int prepare_core(dsp_harmonic_core *core, int sample_rate, int taps) {
    if (core == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        taps < 3 || taps > DSP_HARMONIC_MAX_FIR_TAPS || (taps & 1) == 0) {
        return 0;
    }
    memset(core, 0, sizeof(*core));
    core->taps = taps;
    const double cutoff_cycles_per_oversampled_frame = 0.45 / DSP_HARMONIC_OVERSAMPLE_FACTOR;
    const int center = taps / 2;
    double coefficients[DSP_HARMONIC_MAX_FIR_TAPS];
    double coefficient_sum = 0.0;
    for (int index = 0; index < taps; index++) {
        const int offset = index - center;
        const double ideal = 2.0 * cutoff_cycles_per_oversampled_frame *
            sinc(2.0 * cutoff_cycles_per_oversampled_frame * (double)offset);
        const double window = 0.54 - 0.46 * cos(
            2.0 * 3.14159265358979323846 * (double)index / (double)(taps - 1));
        coefficients[index] = ideal * window;
        coefficient_sum += coefficients[index];
    }
    if (!isfinite(coefficient_sum) || coefficient_sum <= 0.0) return 0;
    for (int index = 0; index < taps; index++) {
        const double low_pass = coefficients[index] / coefficient_sum;
        core->up_coefficients[index] = (float)(low_pass * DSP_HARMONIC_OVERSAMPLE_FACTOR);
        core->down_coefficients[index] = (float)low_pass;
        if (!isfinite(core->up_coefficients[index]) || !isfinite(core->down_coefficients[index])) return 0;
    }
    core->dc_block_coefficient = (float)exp(-2.0 * 3.14159265358979323846 * 10.0 / sample_rate);
    return isfinite(core->dc_block_coefficient);
}

static int prepare_filter(dsp_biquad *filter, int sample_rate, double frequency_hz, int high_pass) {
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    return design_butterworth(sample_rate, frequency_hz, high_pass, coefficients) &&
        dsp_biquad_prepare(filter, coefficients);
}

int dsp_harmonic_prepare(
    dsp_harmonic *harmonic,
    int sample_rate,
    int channels,
    const dsp_harmonic_config *config) {
    if (harmonic == NULL || config == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 ||
        (config->high_exciter_enabled != 0 && config->high_exciter_enabled != 1) ||
        (config->virtual_bass_enabled != 0 && config->virtual_bass_enabled != 1) ||
        config->mode < DSP_HARMONIC_MODE_EVEN || config->mode > DSP_HARMONIC_MODE_MIXED ||
        quality_tap_count(config->quality) == 0 ||
        (config->level_match_enabled != 0 && config->level_match_enabled != 1) ||
        !isfinite(config->high_exciter_amount) || config->high_exciter_amount < 0.0 ||
        config->high_exciter_amount > 0.3 || !isfinite(config->virtual_bass_amount) ||
        config->virtual_bass_amount < 0.0 || config->virtual_bass_amount > 0.3) {
        return 0;
    }
    if (config->high_exciter_enabled != 0 &&
        (!isfinite(config->high_exciter_crossover_hz) || config->high_exciter_crossover_hz < 3000.0 ||
            config->high_exciter_crossover_hz > 16000.0 ||
            config->high_exciter_crossover_hz >= (double)sample_rate * 0.45)) {
        return 0;
    }
    if (config->virtual_bass_enabled != 0 &&
        (!isfinite(config->virtual_bass_crossover_hz) || config->virtual_bass_crossover_hz < 30.0 ||
            config->virtual_bass_crossover_hz > 160.0 ||
            config->virtual_bass_crossover_hz >= (double)sample_rate * 0.45)) {
        return 0;
    }

    memset(harmonic, 0, sizeof(*harmonic));
    harmonic->sample_rate = sample_rate;
    harmonic->channels = channels;
    harmonic->mode = config->mode;
    harmonic->high_exciter_enabled = config->high_exciter_enabled != 0 && config->high_exciter_amount > 0.0;
    harmonic->virtual_bass_enabled = config->virtual_bass_enabled != 0 && config->virtual_bass_amount > 0.0;
    harmonic->active = harmonic->high_exciter_enabled || harmonic->virtual_bass_enabled;
    harmonic->high_exciter_amount = (float)config->high_exciter_amount;
    harmonic->virtual_bass_amount = (float)config->virtual_bass_amount;
    if (!harmonic->active) return 1;

    const int taps = quality_tap_count(config->quality);
    harmonic->latency_frames = (taps - 1) / 2;
    const double amount_sum = (double)harmonic->high_exciter_amount + (double)harmonic->virtual_bass_amount;
    harmonic->level_match_gain = config->level_match_enabled
        ? (float)(1.0 / sqrt(1.0 + 0.5 * amount_sum * amount_sum))
        : 1.0f;
    if (!isfinite(harmonic->level_match_gain)) return 0;

    if (harmonic->high_exciter_enabled &&
        (!prepare_filter(&harmonic->high_exciter_high_pass, sample_rate, config->high_exciter_crossover_hz, 1) ||
            !prepare_core(&harmonic->high_exciter_core, sample_rate, taps))) {
        return 0;
    }
    if (harmonic->virtual_bass_enabled &&
        (!prepare_filter(&harmonic->virtual_bass_low_pass, sample_rate, config->virtual_bass_crossover_hz, 0) ||
            !prepare_filter(
                &harmonic->virtual_bass_harmonic_high_pass,
                sample_rate,
                config->virtual_bass_crossover_hz,
                1) ||
            !prepare_core(&harmonic->virtual_bass_core, sample_rate, taps))) {
        return 0;
    }
    return 1;
}

void dsp_harmonic_reset(dsp_harmonic *harmonic) {
    if (harmonic == NULL) return;
    memset(harmonic->dry_delay, 0, sizeof(harmonic->dry_delay));
    harmonic->dry_write_index = 0;
    if (harmonic->high_exciter_enabled) {
        dsp_biquad_reset(&harmonic->high_exciter_high_pass);
        memset(harmonic->high_exciter_core.up_history, 0, sizeof(harmonic->high_exciter_core.up_history));
        memset(harmonic->high_exciter_core.down_history, 0, sizeof(harmonic->high_exciter_core.down_history));
        memset(harmonic->high_exciter_core.up_write_index, 0, sizeof(harmonic->high_exciter_core.up_write_index));
        memset(harmonic->high_exciter_core.down_write_index, 0, sizeof(harmonic->high_exciter_core.down_write_index));
        memset(harmonic->high_exciter_core.dc_previous_input, 0, sizeof(harmonic->high_exciter_core.dc_previous_input));
        memset(harmonic->high_exciter_core.dc_previous_output, 0, sizeof(harmonic->high_exciter_core.dc_previous_output));
    }
    if (harmonic->virtual_bass_enabled) {
        dsp_biquad_reset(&harmonic->virtual_bass_low_pass);
        dsp_biquad_reset(&harmonic->virtual_bass_harmonic_high_pass);
        memset(harmonic->virtual_bass_core.up_history, 0, sizeof(harmonic->virtual_bass_core.up_history));
        memset(harmonic->virtual_bass_core.down_history, 0, sizeof(harmonic->virtual_bass_core.down_history));
        memset(harmonic->virtual_bass_core.up_write_index, 0, sizeof(harmonic->virtual_bass_core.up_write_index));
        memset(harmonic->virtual_bass_core.down_write_index, 0, sizeof(harmonic->virtual_bass_core.down_write_index));
        memset(harmonic->virtual_bass_core.dc_previous_input, 0,
            sizeof(harmonic->virtual_bass_core.dc_previous_input));
        memset(harmonic->virtual_bass_core.dc_previous_output, 0,
            sizeof(harmonic->virtual_bass_core.dc_previous_output));
    }
}

void dsp_harmonic_close(dsp_harmonic *harmonic) {
    if (harmonic == NULL) return;
    harmonic->active = 0;
    harmonic->latency_frames = 0;
}

int dsp_harmonic_latency_frames(const dsp_harmonic *harmonic) {
    return harmonic == NULL ? -1 : harmonic->latency_frames;
}

static float harmonic_term(float sample, int mode) {
    const float bounded = fminf(fmaxf(sample, -1.0f), 1.0f);
    const float even = bounded * bounded;
    const float odd = even * bounded;
    switch (mode) {
        case DSP_HARMONIC_MODE_EVEN: return even;
        case DSP_HARMONIC_MODE_ODD: return odd;
        case DSP_HARMONIC_MODE_MIXED: return 0.5f * (even + odd);
        default: return 0.0f;
    }
}

static float fir_sample(
    const float history[DSP_HARMONIC_MAX_FIR_TAPS],
    int write_index,
    const float coefficients[DSP_HARMONIC_MAX_FIR_TAPS],
    int taps) {
    float output = 0.0f;
    int index = write_index;
    for (int tap = 0; tap < taps; tap++) {
        output += history[index] * coefficients[tap];
        index--;
        if (index < 0) index = taps - 1;
    }
    return output;
}

static float core_process(
    dsp_harmonic_core *core,
    float input,
    float amount,
    int mode,
    int channel) {
    float output = 0.0f;
    for (int phase = 0; phase < DSP_HARMONIC_OVERSAMPLE_FACTOR; phase++) {
        const int up_index = core->up_write_index[channel];
        core->up_history[channel][up_index] = phase == 0 ? input : 0.0f;
        const float oversampled = fir_sample(
            core->up_history[channel],
            up_index,
            core->up_coefficients,
            core->taps);
        core->up_write_index[channel]++;
        if (core->up_write_index[channel] == core->taps) core->up_write_index[channel] = 0;

        const float residual = harmonic_term(oversampled, mode) * amount;
        const int down_index = core->down_write_index[channel];
        core->down_history[channel][down_index] = residual;
        const float filtered = fir_sample(
            core->down_history[channel],
            down_index,
            core->down_coefficients,
            core->taps);
        core->down_write_index[channel]++;
        if (core->down_write_index[channel] == core->taps) core->down_write_index[channel] = 0;
        if (phase == DSP_HARMONIC_OVERSAMPLE_FACTOR - 1) output = filtered;
    }
    const float high_passed = output - core->dc_previous_input[channel] +
        core->dc_block_coefficient * core->dc_previous_output[channel];
    core->dc_previous_input[channel] = output;
    core->dc_previous_output[channel] = high_passed;
    return high_passed;
}

static float process_channel(dsp_harmonic *harmonic, float input, int channel) {
    const int dry_index = harmonic->dry_write_index;
    harmonic->dry_delay[channel][dry_index] = input;
    int dry_read_index = dry_index - harmonic->latency_frames;
    while (dry_read_index < 0) dry_read_index += DSP_HARMONIC_DRY_DELAY_CAPACITY;
    const float delayed_dry = harmonic->dry_delay[channel][dry_read_index];
    float residual = 0.0f;
    if (harmonic->high_exciter_enabled) {
        const float high_band = dsp_biquad_process_sample(&harmonic->high_exciter_high_pass, input, channel);
        residual += core_process(
            &harmonic->high_exciter_core,
            high_band,
            harmonic->high_exciter_amount,
            harmonic->mode,
            channel);
    }
    if (harmonic->virtual_bass_enabled) {
        const float low_band = dsp_biquad_process_sample(&harmonic->virtual_bass_low_pass, input, channel);
        float bass_harmonics = core_process(
            &harmonic->virtual_bass_core,
            low_band,
            harmonic->virtual_bass_amount,
            harmonic->mode,
            channel);
        bass_harmonics = dsp_biquad_process_sample(
            &harmonic->virtual_bass_harmonic_high_pass,
            bass_harmonics,
            channel);
        residual += bass_harmonics;
    }
    return (delayed_dry + residual) * harmonic->level_match_gain;
}

void dsp_harmonic_process_frame(dsp_harmonic *harmonic, float *left, float *right, int channels) {
    if (harmonic == NULL || left == NULL || right == NULL || !harmonic->active ||
        channels < 1 || channels > 2 || channels != harmonic->channels) {
        return;
    }
    *left = process_channel(harmonic, *left, 0);
    if (channels == 2) *right = process_channel(harmonic, *right, 1);
    harmonic->dry_write_index++;
    if (harmonic->dry_write_index == DSP_HARMONIC_DRY_DELAY_CAPACITY) harmonic->dry_write_index = 0;
}

static void flush_core(dsp_harmonic_core *core) {
    for (int channel = 0; channel < 2; channel++) {
        for (int index = 0; index < core->taps; index++) {
            if (fabsf(core->up_history[channel][index]) < 1e-30f) core->up_history[channel][index] = 0.0f;
            if (fabsf(core->down_history[channel][index]) < 1e-30f) core->down_history[channel][index] = 0.0f;
        }
        if (fabsf(core->dc_previous_input[channel]) < 1e-30f) core->dc_previous_input[channel] = 0.0f;
        if (fabsf(core->dc_previous_output[channel]) < 1e-30f) core->dc_previous_output[channel] = 0.0f;
    }
}

void dsp_harmonic_flush_denormals(dsp_harmonic *harmonic) {
    if (harmonic == NULL || !harmonic->active) return;
    if (harmonic->high_exciter_enabled) {
        dsp_biquad_flush_denormals(&harmonic->high_exciter_high_pass);
        flush_core(&harmonic->high_exciter_core);
    }
    if (harmonic->virtual_bass_enabled) {
        dsp_biquad_flush_denormals(&harmonic->virtual_bass_low_pass);
        dsp_biquad_flush_denormals(&harmonic->virtual_bass_harmonic_high_pass);
        flush_core(&harmonic->virtual_bass_core);
    }
}
