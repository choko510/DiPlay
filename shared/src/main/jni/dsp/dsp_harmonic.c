#include "dsp_harmonic.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define DSP_HARMONIC_PI 3.14159265358979323846
#define DSP_HARMONIC_DC_BLOCKER_POLE 0.9995f

int dsp_harmonic_validate_config(const dsp_harmonic_config *config) {
    return config != NULL &&
        (config->enabled == 0 || config->enabled == 1) &&
        (config->bypassed == 0 || config->bypassed == 1) &&
        (config->quality == 2 || config->quality == 4) &&
        (config->high_exciter_enabled == 0 || config->high_exciter_enabled == 1) &&
        isfinite(config->high_exciter_frequency_hz) && config->high_exciter_frequency_hz >= 1000.0 &&
        config->high_exciter_frequency_hz <= 16000.0 &&
        isfinite(config->high_exciter_drive) && config->high_exciter_drive >= 0.0 &&
        config->high_exciter_drive <= 8.0 &&
        isfinite(config->high_exciter_amount) && config->high_exciter_amount >= 0.0 &&
        config->high_exciter_amount <= 1.0 &&
        config->high_exciter_mode >= DSP_HARMONIC_MODE_EVEN &&
        config->high_exciter_mode <= DSP_HARMONIC_MODE_MIXED &&
        isfinite(config->high_exciter_wet) && config->high_exciter_wet >= 0.0 &&
        config->high_exciter_wet <= 1.0 &&
        isfinite(config->high_exciter_compensation_db) && config->high_exciter_compensation_db >= -12.0 &&
        config->high_exciter_compensation_db <= 6.0 &&
        (config->virtual_bass_enabled == 0 || config->virtual_bass_enabled == 1) &&
        isfinite(config->virtual_bass_fundamental_low_hz) &&
        config->virtual_bass_fundamental_low_hz >= 35.0 &&
        config->virtual_bass_fundamental_low_hz <= 80.0 &&
        isfinite(config->virtual_bass_fundamental_high_hz) &&
        config->virtual_bass_fundamental_high_hz >= 35.0 &&
        config->virtual_bass_fundamental_high_hz <= 80.0 &&
        config->virtual_bass_fundamental_low_hz < config->virtual_bass_fundamental_high_hz &&
        isfinite(config->virtual_bass_amount) && config->virtual_bass_amount >= 0.0 &&
        config->virtual_bass_amount <= 1.0 &&
        isfinite(config->virtual_bass_drive) && config->virtual_bass_drive >= 0.0 &&
        config->virtual_bass_drive <= 8.0 &&
        isfinite(config->virtual_bass_second_amount) && config->virtual_bass_second_amount >= 0.0 &&
        config->virtual_bass_second_amount <= 1.0 &&
        isfinite(config->virtual_bass_third_amount) && config->virtual_bass_third_amount >= 0.0 &&
        config->virtual_bass_third_amount <= 1.0 &&
        isfinite(config->virtual_bass_wet) && config->virtual_bass_wet >= 0.0 &&
        config->virtual_bass_wet <= 1.0;
}

static double sinc(double value) {
    if (fabs(value) < 1e-12) return 1.0;
    const double angle = DSP_HARMONIC_PI * value;
    return sin(angle) / angle;
}

static int prepare_decimator(dsp_harmonic *harmonic) {
    if (harmonic == NULL || (harmonic->oversampling_factor != 2 && harmonic->oversampling_factor != 4)) {
        return 0;
    }
    harmonic->fir_tap_count = harmonic->oversampling_factor == 2 ? 129U : 257U;
    if (harmonic->fir_tap_count > DSP_HARMONIC_FIR_MAX_TAPS) return 0;
    const double cutoff_cycles_per_oversampled_frame = 0.45 / harmonic->oversampling_factor;
    const double center = (double)(harmonic->fir_tap_count - 1U) * 0.5;
    double coefficient_sum = 0.0;
    for (size_t tap = 0; tap < harmonic->fir_tap_count; tap++) {
        const double offset = (double)tap - center;
        const double window = 0.42 - 0.5 * cos(2.0 * DSP_HARMONIC_PI * (double)tap /
            (double)(harmonic->fir_tap_count - 1U)) +
            0.08 * cos(4.0 * DSP_HARMONIC_PI * (double)tap /
                (double)(harmonic->fir_tap_count - 1U));
        const double coefficient = 2.0 * cutoff_cycles_per_oversampled_frame *
            sinc(2.0 * cutoff_cycles_per_oversampled_frame * offset) * window;
        if (!isfinite(coefficient)) return 0;
        harmonic->fir_coefficients[tap] = coefficient;
        coefficient_sum += coefficient;
    }
    if (!isfinite(coefficient_sum) || fabs(coefficient_sum) < 1e-12) return 0;
    for (size_t tap = 0; tap < harmonic->fir_tap_count; tap++) {
        harmonic->fir_coefficients[tap] /= coefficient_sum;
    }
    const int fir_group_delay_frames = (int)((harmonic->fir_tap_count - 1U) /
        (2U * (size_t)harmonic->oversampling_factor));
    harmonic->latency_frames = fir_group_delay_frames * 2;
    return fir_group_delay_frames == DSP_HARMONIC_FIR_GROUP_DELAY_FRAMES &&
        harmonic->latency_frames == DSP_HARMONIC_ALGORITHM_LATENCY_FRAMES;
}

int dsp_harmonic_prepare(
    dsp_harmonic *harmonic,
    int sample_rate,
    int channels,
    const dsp_harmonic_config *config,
    const double *filter_coefficients,
    size_t filter_coefficient_count) {
    if (harmonic == NULL || sample_rate < 8000 || sample_rate > 192000 || channels < 1 || channels > 2 ||
        !dsp_harmonic_validate_config(config) || filter_coefficients == NULL ||
        filter_coefficient_count != DSP_HARMONIC_COEFFICIENT_COUNT) {
        return 0;
    }
    for (size_t index = 0; index < filter_coefficient_count; index++) {
        if (!isfinite(filter_coefficients[index])) return 0;
    }
    memset(harmonic, 0, sizeof(*harmonic));
    atomic_init(&harmonic->bypass_target, config->bypassed);
    dsp_harmonic_config effective_config = *config;
    if (sample_rate < 16000) {
        effective_config.high_exciter_enabled = 0;
        effective_config.virtual_bass_enabled = 0;
    } else {
        if (effective_config.high_exciter_frequency_hz >= sample_rate * 0.42) {
            effective_config.high_exciter_enabled = 0;
        }
        if (effective_config.virtual_bass_fundamental_high_hz >= sample_rate * 0.42) {
            effective_config.virtual_bass_enabled = 0;
        }
    }
    if (effective_config.enabled == 0 ||
        ((effective_config.high_exciter_enabled == 0 || effective_config.high_exciter_wet == 0.0 ||
            effective_config.high_exciter_amount == 0.0) &&
            (effective_config.virtual_bass_enabled == 0 || effective_config.virtual_bass_wet == 0.0 ||
                effective_config.virtual_bass_amount == 0.0))) {
        return 1;
    }
    harmonic->sample_rate = sample_rate;
    harmonic->channels = channels;
    harmonic->quality = config->quality;
    harmonic->config = effective_config;
    harmonic->oversampling_factor = effective_config.quality;
    harmonic->observed_bypass = effective_config.bypassed;
    harmonic->bypass_transition_frames = (uint32_t)ceil((double)sample_rate * 0.02);
    harmonic->bypass_transition_position = harmonic->bypass_transition_frames;
    harmonic->bypass_mix = effective_config.bypassed != 0 ? 0.0f : 1.0f;
    harmonic->bypass_transition_start = harmonic->bypass_mix;
    harmonic->bypass_transition_target = harmonic->bypass_mix;
    harmonic->envelope_smoothing = (float)exp(-2.0 * DSP_HARMONIC_PI * 5.0 /
        ((double)sample_rate * (double)harmonic->oversampling_factor));
    if (!isfinite(harmonic->envelope_smoothing) || harmonic->envelope_smoothing <= 0.0f ||
        harmonic->envelope_smoothing >= 1.0f) {
        return 0;
    }
    if (!prepare_decimator(harmonic)) {
        dsp_harmonic_close(harmonic);
        return 0;
    }
    harmonic->dry_capacity_frames = (size_t)harmonic->latency_frames + 1U;
    harmonic->upsample_history_capacity = (harmonic->fir_tap_count +
        (size_t)harmonic->oversampling_factor - 1U) / (size_t)harmonic->oversampling_factor;
    if (harmonic->dry_capacity_frames > SIZE_MAX / ((size_t)channels * sizeof(float)) ||
        harmonic->fir_tap_count > SIZE_MAX / sizeof(float) ||
        harmonic->upsample_history_capacity > SIZE_MAX / sizeof(float)) {
        dsp_harmonic_close(harmonic);
        return 0;
    }
    harmonic->dry_ring = (float *)calloc(harmonic->dry_capacity_frames * (size_t)channels, sizeof(float));
    if (harmonic->dry_ring == NULL) {
        dsp_harmonic_close(harmonic);
        return 0;
    }
    for (int channel = 0; channel < channels; channel++) {
        harmonic->fir_history[channel] = (float *)calloc(harmonic->fir_tap_count, sizeof(float));
        if (harmonic->fir_history[channel] == NULL) {
            dsp_harmonic_close(harmonic);
            return 0;
        }
        for (size_t branch = 0; branch < 2U; branch++) {
            harmonic->upsample_history[channel][branch] =
                (float *)calloc(harmonic->upsample_history_capacity, sizeof(float));
            if (harmonic->upsample_history[channel][branch] == NULL) {
                dsp_harmonic_close(harmonic);
                return 0;
            }
        }
    }
    for (size_t filter = 0; filter < DSP_HARMONIC_FILTER_COUNT; filter++) {
        if (!dsp_biquad_prepare(
                &harmonic->filters[filter],
                filter_coefficients + filter * DSP_BIQUAD_COEFFICIENT_COUNT)) {
            dsp_harmonic_close(harmonic);
            return 0;
        }
    }
    const double compensation_db = effective_config.high_exciter_compensation_db;
    const double compensation = pow(10.0, compensation_db / 20.0);
    if (!isfinite(compensation) || compensation < 0.251 || compensation > 2.0) {
        dsp_harmonic_close(harmonic);
        return 0;
    }
    harmonic->high_mix = effective_config.high_exciter_enabled != 0
        ? (float)(effective_config.high_exciter_amount * effective_config.high_exciter_wet * compensation)
        : 0.0f;
    harmonic->bass_mix = effective_config.virtual_bass_enabled != 0
        ? (float)(effective_config.virtual_bass_amount * effective_config.virtual_bass_wet)
        : 0.0f;
    harmonic->active = 1;
    return 1;
}

void dsp_harmonic_reset(dsp_harmonic *harmonic) {
    if (harmonic == NULL || harmonic->active == 0) return;
    memset(harmonic->dry_ring, 0, harmonic->dry_capacity_frames * (size_t)harmonic->channels * sizeof(float));
    for (int channel = 0; channel < harmonic->channels; channel++) {
        memset(harmonic->fir_history[channel], 0, harmonic->fir_tap_count * sizeof(float));
        harmonic->fir_position[channel] = 0;
        for (size_t branch = 0; branch < 2U; branch++) {
            memset(harmonic->upsample_history[channel][branch], 0,
                harmonic->upsample_history_capacity * sizeof(float));
            harmonic->upsample_position[channel][branch] = 0;
        }
        harmonic->previous_residual_input[channel] = 0.0f;
        harmonic->residual_dc_output[channel] = 0.0f;
        harmonic->envelope_power[channel][0] = 0.0f;
        harmonic->envelope_power[channel][1] = 0.0f;
    }
    for (size_t filter = 0; filter < DSP_HARMONIC_FILTER_COUNT; filter++) {
        dsp_biquad_reset(&harmonic->filters[filter]);
    }
    harmonic->dry_write_position = 0;
    harmonic->observed_bypass = atomic_load_explicit(&harmonic->bypass_target, memory_order_acquire);
    harmonic->bypass_transition_target = harmonic->observed_bypass != 0 ? 0.0f : 1.0f;
    harmonic->bypass_mix = harmonic->bypass_transition_target;
    harmonic->bypass_transition_start = harmonic->bypass_mix;
    harmonic->bypass_transition_position = harmonic->bypass_transition_frames;
}

int dsp_harmonic_update_bypass(dsp_harmonic *harmonic, int bypassed) {
    if (harmonic == NULL || (bypassed != 0 && bypassed != 1)) return 0;
    atomic_store_explicit(&harmonic->bypass_target, bypassed, memory_order_release);
    return 1;
}

void dsp_harmonic_close(dsp_harmonic *harmonic) {
    if (harmonic == NULL) return;
    free(harmonic->dry_ring);
    for (size_t channel = 0; channel < 2U; channel++) {
        free(harmonic->fir_history[channel]);
        for (size_t branch = 0; branch < 2U; branch++) free(harmonic->upsample_history[channel][branch]);
    }
    memset(harmonic, 0, sizeof(*harmonic));
}

static float clamp_unit(float value) {
    if (value < -1.0f) return -1.0f;
    if (value > 1.0f) return 1.0f;
    return value;
}

static float harmonic_residual(float driven, int mode, int virtual_bass,
    double second_amount, double third_amount, float average_power) {
    const float even_residual = driven * driven - average_power;
    const float odd_residual = 1.5f * average_power * driven - driven * driven * driven;
    if (virtual_bass != 0) {
        return (float)(second_amount) * even_residual + (float)(third_amount) * odd_residual;
    }
    if (mode == DSP_HARMONIC_MODE_EVEN) return even_residual;
    if (mode == DSP_HARMONIC_MODE_ODD) return -odd_residual;
    return 0.5f * (even_residual + odd_residual);
}

static void push_fir_sample(float *history, size_t capacity, size_t *position, float sample) {
    history[*position] = sample;
    (*position)++;
    if (*position == capacity) *position = 0;
}

static float process_decimator(dsp_harmonic *harmonic, int channel) {
    double output = 0.0;
    size_t read_position = harmonic->fir_position[channel] == 0
        ? harmonic->fir_tap_count - 1U
        : harmonic->fir_position[channel] - 1U;
    for (size_t tap = 0; tap < harmonic->fir_tap_count; tap++) {
        output += harmonic->fir_coefficients[tap] * harmonic->fir_history[channel][read_position];
        read_position = read_position == 0 ? harmonic->fir_tap_count - 1U : read_position - 1U;
    }
    return isfinite(output) ? (float)output : 0.0f;
}

static void push_upsampler_input(dsp_harmonic *harmonic, int channel, size_t branch, float sample) {
    push_fir_sample(
        harmonic->upsample_history[channel][branch],
        harmonic->upsample_history_capacity,
        &harmonic->upsample_position[channel][branch],
        sample);
}

static float process_upsampler_phase(dsp_harmonic *harmonic, int channel, size_t branch, size_t phase) {
    const size_t capacity = harmonic->upsample_history_capacity;
    size_t read_position = harmonic->upsample_position[channel][branch] == 0
        ? capacity - 1U
        : harmonic->upsample_position[channel][branch] - 1U;
    double output = 0.0;
    size_t coefficient_index = phase;
    size_t input_tap = 0;
    while (coefficient_index < harmonic->fir_tap_count) {
        output += harmonic->fir_coefficients[coefficient_index] *
            harmonic->upsample_history[channel][branch][read_position];
        read_position = read_position == 0 ? capacity - 1U : read_position - 1U;
        input_tap++;
        coefficient_index = phase + input_tap * (size_t)harmonic->oversampling_factor;
    }
    output *= (double)harmonic->oversampling_factor;
    return isfinite(output) ? (float)output : 0.0f;
}

static float process_channel(
    dsp_harmonic *harmonic,
    int channel,
    float input,
    float delayed_dry,
    const dsp_harmonic_config *config) {
    if (harmonic->high_mix > 0.0f) {
        const float high_band = dsp_biquad_process_sample(&harmonic->filters[0], input, channel);
        const float high_band_limited = dsp_biquad_process_sample(&harmonic->filters[1], high_band, channel);
        push_upsampler_input(harmonic, channel, 0, high_band_limited);
    }
    if (harmonic->bass_mix > 0.0f) {
        const float bass_highpassed = dsp_biquad_process_sample(&harmonic->filters[2], input, channel);
        const float bass_band = dsp_biquad_process_sample(&harmonic->filters[3], bass_highpassed, channel);
        push_upsampler_input(harmonic, channel, 1, bass_band);
    }
    float high_residual = 0.0f;
    for (int step = 0; step < harmonic->oversampling_factor; step++) {
        const float upsampled_high = harmonic->high_mix > 0.0f
            ? process_upsampler_phase(harmonic, channel, 0, (size_t)step)
            : 0.0f;
        const float upsampled_bass = harmonic->bass_mix > 0.0f
            ? process_upsampler_phase(harmonic, channel, 1, (size_t)step)
            : 0.0f;
        float residual = 0.0f;
        if (harmonic->high_mix > 0.0f) {
            const float driven_high = clamp_unit(upsampled_high * (float)config->high_exciter_drive);
            const float high_power = driven_high * driven_high;
            harmonic->envelope_power[channel][0] = harmonic->envelope_smoothing *
                harmonic->envelope_power[channel][0] + (1.0f - harmonic->envelope_smoothing) * high_power;
            residual += harmonic->high_mix * harmonic_residual(
                driven_high,
                config->high_exciter_mode,
                0,
                0.0,
                0.0,
                harmonic->envelope_power[channel][0]);
        }
        if (harmonic->bass_mix > 0.0f) {
            const float driven_bass = clamp_unit(upsampled_bass * (float)config->virtual_bass_drive);
            const float bass_power = driven_bass * driven_bass;
            harmonic->envelope_power[channel][1] = harmonic->envelope_smoothing *
                harmonic->envelope_power[channel][1] + (1.0f - harmonic->envelope_smoothing) * bass_power;
            residual += harmonic->bass_mix * harmonic_residual(
                driven_bass,
                DSP_HARMONIC_MODE_MIXED,
                1,
                config->virtual_bass_second_amount,
                config->virtual_bass_third_amount,
                harmonic->envelope_power[channel][1]);
        }
        const float dc_blocked = residual - harmonic->previous_residual_input[channel] +
            DSP_HARMONIC_DC_BLOCKER_POLE * harmonic->residual_dc_output[channel];
        harmonic->previous_residual_input[channel] = residual;
        harmonic->residual_dc_output[channel] = isfinite(dc_blocked) ? dc_blocked : 0.0f;
        push_fir_sample(harmonic->fir_history[channel], harmonic->fir_tap_count,
            &harmonic->fir_position[channel], harmonic->residual_dc_output[channel]);
        if (step == 0) high_residual = process_decimator(harmonic, channel);
    }
    const float output = delayed_dry + high_residual;
    return isfinite(output) ? output : delayed_dry;
}

void dsp_harmonic_process_frame(dsp_harmonic *harmonic, float *left, float *right, int channels) {
    if (harmonic == NULL || harmonic->active == 0 || left == NULL || right == NULL ||
        channels != harmonic->channels || channels < 1 || channels > 2) {
        return;
    }
    const float input[2] = {*left, channels == 2 ? *right : 0.0f};
    float delayed_dry[2] = {0.0f, 0.0f};
    for (int channel = 0; channel < channels; channel++) {
        const size_t read_position = (harmonic->dry_write_position + harmonic->dry_capacity_frames -
            (size_t)harmonic->latency_frames) % harmonic->dry_capacity_frames;
        delayed_dry[channel] = harmonic->dry_ring[read_position * (size_t)channels + (size_t)channel];
        harmonic->dry_ring[harmonic->dry_write_position * (size_t)channels + (size_t)channel] = input[channel];
    }
    const int bypassed = atomic_load_explicit(&harmonic->bypass_target, memory_order_acquire);
    if (bypassed != harmonic->observed_bypass) {
        harmonic->bypass_transition_start = harmonic->bypass_mix;
        harmonic->bypass_transition_target = bypassed != 0 ? 0.0f : 1.0f;
        harmonic->bypass_transition_position = 0;
        harmonic->observed_bypass = bypassed;
    }
    if (harmonic->bypass_transition_position < harmonic->bypass_transition_frames) {
        float progress = (float)(harmonic->bypass_transition_position + 1U) /
            (float)harmonic->bypass_transition_frames;
        progress = progress * progress * (3.0f - 2.0f * progress);
        harmonic->bypass_transition_position++;
        harmonic->bypass_mix = harmonic->bypass_transition_start +
            (harmonic->bypass_transition_target - harmonic->bypass_transition_start) * progress;
        if (harmonic->bypass_transition_position >= harmonic->bypass_transition_frames) {
            harmonic->bypass_mix = harmonic->bypass_transition_target;
        }
    }
    const float processed_left = process_channel(harmonic, 0, input[0], delayed_dry[0], &harmonic->config);
    *left = delayed_dry[0] + (processed_left - delayed_dry[0]) * harmonic->bypass_mix;
    if (channels == 2) {
        const float processed_right = process_channel(harmonic, 1, input[1], delayed_dry[1], &harmonic->config);
        *right = delayed_dry[1] + (processed_right - delayed_dry[1]) * harmonic->bypass_mix;
    }
    harmonic->dry_write_position++;
    if (harmonic->dry_write_position == harmonic->dry_capacity_frames) harmonic->dry_write_position = 0;
}

void dsp_harmonic_flush_denormals(dsp_harmonic *harmonic) {
    if (harmonic == NULL || harmonic->active == 0) return;
    for (size_t index = 0; index < DSP_HARMONIC_FILTER_COUNT; index++) {
        dsp_biquad_flush_denormals(&harmonic->filters[index]);
    }
    for (int channel = 0; channel < harmonic->channels; channel++) {
        if (fabsf(harmonic->previous_residual_input[channel]) < 1e-20f) harmonic->previous_residual_input[channel] = 0.0f;
        if (fabsf(harmonic->residual_dc_output[channel]) < 1e-20f) harmonic->residual_dc_output[channel] = 0.0f;
        for (size_t branch = 0; branch < 2U; branch++) {
            if (fabsf(harmonic->envelope_power[channel][branch]) < 1e-20f) {
                harmonic->envelope_power[channel][branch] = 0.0f;
            }
        }
    }
}

int dsp_harmonic_latency_frames(const dsp_harmonic *harmonic) {
    return harmonic != NULL && harmonic->active != 0 ? harmonic->latency_frames : 0;
}
