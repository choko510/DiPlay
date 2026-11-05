#include "dsp_harmonic.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

static int harmonic_taps_are_supported(int taps) {
    return taps == 9 || taps == 17 || taps == 33;
}

static int harmonic_config_is_valid(const dsp_harmonic_config *config, int sample_rate) {
    return config != NULL && (config->enabled == 0 || config->enabled == 1) &&
        config->mode >= 0 && config->mode <= 2 && harmonic_taps_are_supported(config->taps) &&
        (config->exciter_enabled == 0 || config->exciter_enabled == 1) &&
        isfinite(config->exciter_amount) && config->exciter_amount >= 0.0 && config->exciter_amount <= 0.5 &&
        isfinite(config->exciter_cutoff_hz) && config->exciter_cutoff_hz >= 1000.0 &&
        config->exciter_cutoff_hz <= 18000.0 &&
        (!(config->enabled && config->exciter_enabled && config->exciter_amount > 0.0) ||
            config->exciter_cutoff_hz < sample_rate * 0.45) &&
        (config->virtual_bass_enabled == 0 || config->virtual_bass_enabled == 1) &&
        isfinite(config->virtual_bass_amount) && config->virtual_bass_amount >= 0.0 &&
        config->virtual_bass_amount <= 0.5 && isfinite(config->virtual_bass_cutoff_hz) &&
        config->virtual_bass_cutoff_hz >= 60.0 && config->virtual_bass_cutoff_hz <= 250.0 &&
        (!(config->enabled && config->virtual_bass_enabled && config->virtual_bass_amount > 0.0) ||
            config->virtual_bass_cutoff_hz < sample_rate * 0.45) &&
        (config->level_match_enabled == 0 || config->level_match_enabled == 1);
}

static void design_low_pass(int sample_rate, double frequency_hz, double coefficients[5]) {
    const double omega = 2.0 * 3.14159265358979323846 * frequency_hz / sample_rate;
    const double cosine = cos(omega);
    const double sine = sin(omega);
    const double alpha = sine / (2.0 * 0.70710678118654752440);
    const double a0 = 1.0 + alpha;
    coefficients[0] = (1.0 - cosine) * 0.5 / a0;
    coefficients[1] = (1.0 - cosine) / a0;
    coefficients[2] = coefficients[0];
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
}

static void design_high_pass(int sample_rate, double frequency_hz, double coefficients[5]) {
    const double omega = 2.0 * 3.14159265358979323846 * frequency_hz / sample_rate;
    const double cosine = cos(omega);
    const double sine = sin(omega);
    const double alpha = sine / (2.0 * 0.70710678118654752440);
    const double a0 = 1.0 + alpha;
    coefficients[0] = (1.0 + cosine) * 0.5 / a0;
    coefficients[1] = -(1.0 + cosine) / a0;
    coefficients[2] = coefficients[0];
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
}

static void design_anti_alias_fir(int taps, float coefficients[DSP_HARMONIC_MAX_FIR_TAPS]) {
    const int midpoint = (taps - 1) / 2;
    double sum = 0.0;
    for (int index = 0; index < taps; index++) {
        const int offset = index - midpoint;
        const double sinc = offset == 0
            ? 0.5
            : sin(0.5 * 3.14159265358979323846 * offset) /
                (3.14159265358979323846 * offset);
        const double position = (double)index / (taps - 1);
        const double window = 0.42 - 0.5 * cos(2.0 * 3.14159265358979323846 * position) +
            0.08 * cos(4.0 * 3.14159265358979323846 * position);
        coefficients[index] = (float)(sinc * window);
        sum += sinc * window;
    }
    if (!isfinite(sum) || fabs(sum) < 1e-12) return;
    for (int index = 0; index < taps; index++) coefficients[index] = (float)(coefficients[index] / sum);
}

static float clamp_sample(float sample) {
    if (!isfinite(sample)) return 0.0f;
    return fminf(1.0f, fmaxf(-1.0f, sample));
}

static float waveshape_antiderivative(float sample, float previous_sample, int mode) {
    const float current = clamp_sample(sample);
    const float previous = clamp_sample(previous_sample);
    const float current_squared = current * current;
    const float previous_squared = previous * previous;
    // First-order ADAA divided differences for x^2 and x^3 avoid division at equal adjacent samples.
    const float even = (current_squared + current * previous + previous_squared) / 3.0f;
    const float odd = (current_squared * current + current_squared * previous +
        current * previous_squared + previous_squared * previous) * 0.25f;
    switch (mode) {
        case 0: return even;
        case 1: return odd;
        case 2: return 0.5f * (even + odd);
        default: return current;
    }
}

static float process_upsampled_branch(
    dsp_harmonic_oversampler_state *state,
    const float coefficients[DSP_HARMONIC_MAX_FIR_TAPS],
    int taps,
    float sample,
    int mode) {
    float output = 0.0f;
    for (int phase = 0; phase < 2; phase++) {
        state->upsample_ring[state->upsample_write_index] = phase == 0 ? sample : 0.0f;
        float upsampled = 0.0f;
        for (int tap = phase; tap < taps; tap += 2) {
            int read_index = state->upsample_write_index - tap;
            while (read_index < 0) read_index += DSP_HARMONIC_RING_FRAMES;
            upsampled += coefficients[tap] * state->upsample_ring[read_index];
        }
        upsampled *= 2.0f;
        const float shaped = waveshape_antiderivative(
            upsampled,
            state->previous_upsampled_sample,
            mode);
        state->previous_upsampled_sample = upsampled;
        state->shaped_ring[state->shaped_write_index] = fabsf(shaped) < 1e-20f ? 0.0f : shaped;
        if (phase == 0) {
            for (int tap = 0; tap < taps; tap++) {
                int read_index = state->shaped_write_index - tap;
                while (read_index < 0) read_index += DSP_HARMONIC_RING_FRAMES;
                output += coefficients[tap] * state->shaped_ring[read_index];
            }
        }
        state->upsample_write_index++;
        if (state->upsample_write_index == DSP_HARMONIC_RING_FRAMES) state->upsample_write_index = 0;
        state->shaped_write_index++;
        if (state->shaped_write_index == DSP_HARMONIC_RING_FRAMES) state->shaped_write_index = 0;
    }
    return fabsf(output) < 1e-20f ? 0.0f : output;
}

int dsp_harmonic_prepare(
    dsp_harmonic *harmonic,
    int sample_rate,
    int channels,
    const dsp_harmonic_config *config) {
    if (harmonic == NULL || sample_rate < 8000 || sample_rate > 192000 || channels < 1 || channels > 2 ||
        !harmonic_config_is_valid(config, sample_rate)) {
        return 0;
    }
    harmonic->active = config->enabled &&
        ((config->exciter_enabled && config->exciter_amount > 0.0) ||
            (config->virtual_bass_enabled && config->virtual_bass_amount > 0.0));
    harmonic->channels = channels;
    harmonic->mode = config->mode;
    harmonic->taps = config->taps;
    harmonic->latency_frames = harmonic->active ? (config->taps - 1) / 2 : 0;
    harmonic->exciter_enabled = harmonic->active && config->exciter_enabled && config->exciter_amount > 0.0;
    harmonic->virtual_bass_enabled = harmonic->active && config->virtual_bass_enabled && config->virtual_bass_amount > 0.0;
    harmonic->level_match_enabled = config->level_match_enabled;
    harmonic->exciter_amount = harmonic->exciter_enabled ? (float)config->exciter_amount : 0.0f;
    harmonic->virtual_bass_amount = harmonic->virtual_bass_enabled ? (float)config->virtual_bass_amount : 0.0f;
    const float additive_amount = harmonic->exciter_amount + harmonic->virtual_bass_amount;
    harmonic->level_match_gain = harmonic->level_match_enabled
        ? (float)pow(10.0, -1.5 * additive_amount / 20.0)
        : 1.0f;
    if (!harmonic->active) {
        dsp_harmonic_reset(harmonic);
        return 1;
    }

    design_anti_alias_fir(harmonic->taps, harmonic->fir_coefficients);
    if (harmonic->exciter_enabled) {
        double high_pass[5];
        double alias_guard[5];
        double dc_block[5];
        const double alias_guard_hz = fmin(
            sample_rate * 0.40,
            fmax(sample_rate * 0.30, config->exciter_cutoff_hz * 1.25));
        design_high_pass(sample_rate, config->exciter_cutoff_hz, high_pass);
        design_low_pass(sample_rate, alias_guard_hz, alias_guard);
        design_high_pass(sample_rate, 20.0, dc_block);
        if (!dsp_biquad_prepare(&harmonic->exciter_high_pass, high_pass) ||
            !dsp_biquad_prepare(&harmonic->exciter_alias_guard, alias_guard) ||
            !dsp_biquad_prepare(&harmonic->exciter_dc_block, dc_block)) {
            return 0;
        }
    }
    if (harmonic->virtual_bass_enabled) {
        double low_pass[5];
        double dc_block[5];
        design_low_pass(sample_rate, config->virtual_bass_cutoff_hz, low_pass);
        design_high_pass(sample_rate, 20.0, dc_block);
        if (!dsp_biquad_prepare(&harmonic->virtual_bass_low_pass, low_pass) ||
            !dsp_biquad_prepare(&harmonic->virtual_bass_dc_block, dc_block)) {
            return 0;
        }
    }
    dsp_harmonic_reset(harmonic);
    return 1;
}

void dsp_harmonic_reset(dsp_harmonic *harmonic) {
    if (harmonic == NULL) return;
    memset(harmonic->branches, 0, sizeof(harmonic->branches));
    memset(harmonic->dry_delay, 0, sizeof(harmonic->dry_delay));
    harmonic->dry_write_index = 0;
    if (harmonic->exciter_enabled) {
        dsp_biquad_reset(&harmonic->exciter_high_pass);
        dsp_biquad_reset(&harmonic->exciter_alias_guard);
        dsp_biquad_reset(&harmonic->exciter_dc_block);
    }
    if (harmonic->virtual_bass_enabled) {
        dsp_biquad_reset(&harmonic->virtual_bass_low_pass);
        dsp_biquad_reset(&harmonic->virtual_bass_dc_block);
    }
}

void dsp_harmonic_process_frame(dsp_harmonic *harmonic, float *left, float *right, int channels) {
    if (harmonic == NULL || left == NULL || right == NULL || !harmonic->active || channels != harmonic->channels) {
        return;
    }
    float *samples[2] = {left, right};
    float output[2] = {0.0f, 0.0f};
    const int latency = harmonic->latency_frames;
    for (int channel = 0; channel < channels; channel++) {
        const float input = *samples[channel];
        harmonic->dry_delay[channel][harmonic->dry_write_index] = input;
        int dry_index = harmonic->dry_write_index - latency;
        if (dry_index < 0) dry_index += DSP_HARMONIC_DELAY_FRAMES;
        output[channel] = harmonic->dry_delay[channel][dry_index] * harmonic->level_match_gain;
        if (harmonic->exciter_enabled) {
            const float high_passed = dsp_biquad_process_sample(&harmonic->exciter_high_pass, input, channel);
            const float band = dsp_biquad_process_sample(&harmonic->exciter_alias_guard, high_passed, channel);
            float harmonic_component = process_upsampled_branch(
                &harmonic->branches[channel][0], harmonic->fir_coefficients, harmonic->taps, band, harmonic->mode);
            harmonic_component = dsp_biquad_process_sample(&harmonic->exciter_dc_block, harmonic_component, channel);
            output[channel] += harmonic_component * harmonic->exciter_amount;
        }
        if (harmonic->virtual_bass_enabled) {
            const float band = dsp_biquad_process_sample(&harmonic->virtual_bass_low_pass, input, channel);
            float harmonic_component = process_upsampled_branch(
                &harmonic->branches[channel][1], harmonic->fir_coefficients, harmonic->taps, band, harmonic->mode);
            harmonic_component = dsp_biquad_process_sample(&harmonic->virtual_bass_dc_block, harmonic_component, channel);
            output[channel] += harmonic_component * harmonic->virtual_bass_amount;
        }
    }
    harmonic->dry_write_index++;
    if (harmonic->dry_write_index == DSP_HARMONIC_DELAY_FRAMES) harmonic->dry_write_index = 0;
    *left = isfinite(output[0]) ? output[0] : 0.0f;
    if (channels == 2) *right = isfinite(output[1]) ? output[1] : 0.0f;
}

void dsp_harmonic_flush_denormals(dsp_harmonic *harmonic) {
    if (harmonic == NULL || !harmonic->active) return;
    if (harmonic->exciter_enabled) {
        dsp_biquad_flush_denormals(&harmonic->exciter_high_pass);
        dsp_biquad_flush_denormals(&harmonic->exciter_alias_guard);
        dsp_biquad_flush_denormals(&harmonic->exciter_dc_block);
    }
    if (harmonic->virtual_bass_enabled) {
        dsp_biquad_flush_denormals(&harmonic->virtual_bass_low_pass);
        dsp_biquad_flush_denormals(&harmonic->virtual_bass_dc_block);
    }
}

int dsp_harmonic_latency_frames(const dsp_harmonic *harmonic) {
    return harmonic != NULL ? harmonic->latency_frames : 0;
}
