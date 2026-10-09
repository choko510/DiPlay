#include "dsp_seat_focus.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define DSP_SEAT_FOCUS_MAX_DELAY_MS 5.0
#define DSP_SEAT_FOCUS_DELAY_BUDGET_MS 5.5
#define DSP_SEAT_FOCUS_TRANSITION_MS 20.0

static uint32_t float_to_bits(float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static float bits_to_float(uint32_t bits) {
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

int dsp_seat_focus_validate_config(const dsp_seat_focus_config *config) {
    return config != NULL &&
        (config->enabled == 0 || config->enabled == 1) &&
        (config->bypassed == 0 || config->bypassed == 1) &&
        isfinite(config->left_delay_ms) && config->left_delay_ms >= 0.0 &&
        config->left_delay_ms <= DSP_SEAT_FOCUS_MAX_DELAY_MS &&
        isfinite(config->right_delay_ms) && config->right_delay_ms >= 0.0 &&
        config->right_delay_ms <= DSP_SEAT_FOCUS_MAX_DELAY_MS &&
        isfinite(config->left_trim_db) && config->left_trim_db >= -6.0 && config->left_trim_db <= 6.0 &&
        isfinite(config->right_trim_db) && config->right_trim_db >= -6.0 && config->right_trim_db <= 6.0 &&
        (config->invert_left == 0 || config->invert_left == 1) &&
        (config->invert_right == 0 || config->invert_right == 1);
}

typedef struct {
    float delay_samples;
    float gain;
} dsp_seat_focus_target;

static void design_lagrange_coefficients(double fraction, float coefficients[DSP_SEAT_FOCUS_INTERPOLATION_TAPS]) {
    for (size_t tap = 0; tap < DSP_SEAT_FOCUS_INTERPOLATION_TAPS; tap++) {
        double coefficient = 1.0;
        for (size_t node = 0; node < DSP_SEAT_FOCUS_INTERPOLATION_TAPS; node++) {
            if (node == tap) continue;
            coefficient *= (fraction - (double)node) / ((double)tap - (double)node);
        }
        coefficients[tap] = (float)coefficient;
    }
}

static int calculate_targets(
    const dsp_seat_focus *seat_focus,
    const dsp_seat_focus_config *config,
    dsp_seat_focus_target targets[2]) {
    if (!dsp_seat_focus_validate_config(config) || seat_focus == NULL || seat_focus->sample_rate < 8000 ||
        seat_focus->latency_frames < 1) {
        return 0;
    }

    if (config->bypassed != 0) {
        const double bypass_gain = pow(10.0, seat_focus->prepared_trim_headroom_db / 20.0);
        if (!isfinite(bypass_gain)) return 0;
        for (size_t channel = 0; channel < 2U; channel++) {
            targets[channel].delay_samples = (float)seat_focus->latency_frames;
            targets[channel].gain = (float)bypass_gain;
        }
        return 1;
    }

    const double left_samples = config->left_delay_ms * (double)seat_focus->sample_rate / 1000.0;
    const double right_samples = config->right_delay_ms * (double)seat_focus->sample_rate / 1000.0;
    const double max_user_delay_samples = fmax(left_samples, right_samples);
    const double common_delay = (double)seat_focus->latency_frames - max_user_delay_samples;
    const double effective_left = common_delay + left_samples;
    const double effective_right = common_delay + right_samples;
    if (!isfinite(effective_left) || !isfinite(effective_right) || effective_left < 0.0 ||
        effective_right < 0.0 || effective_left > seat_focus->latency_frames ||
        effective_right > seat_focus->latency_frames) {
        return 0;
    }

    const double current_trim_headroom_db = fmax(0.0, fmax(config->left_trim_db, config->right_trim_db));
    const double trim_headroom_adjustment_db = current_trim_headroom_db - seat_focus->prepared_trim_headroom_db;
    const double left_linear = pow(10.0, (config->left_trim_db - trim_headroom_adjustment_db) / 20.0);
    const double right_linear = pow(10.0, (config->right_trim_db - trim_headroom_adjustment_db) / 20.0);
    if (!isfinite(left_linear) || !isfinite(right_linear)) {
        return 0;
    }
    const double effective_delays[2] = {effective_left, effective_right};
    const double linear_gains[2] = {
        config->invert_left != 0 ? -left_linear : left_linear,
        config->invert_right != 0 ? -right_linear : right_linear,
    };
    for (size_t channel = 0; channel < 2U; channel++) {
        targets[channel].delay_samples = (float)effective_delays[channel];
        targets[channel].gain = (float)linear_gains[channel];
        if (!isfinite(targets[channel].delay_samples) || !isfinite(targets[channel].gain)) return 0;
    }
    return 1;
}

int dsp_seat_focus_prepare(
    dsp_seat_focus *seat_focus,
    int sample_rate,
    int channels,
    const dsp_seat_focus_config *config) {
    if (seat_focus == NULL || sample_rate < 8000 || sample_rate > 192000 || channels < 1 || channels > 2 ||
        !dsp_seat_focus_validate_config(config)) {
        return 0;
    }
    memset(seat_focus, 0, sizeof(*seat_focus));
    atomic_init(&seat_focus->target_generation, 0);
    for (size_t channel = 0; channel < 2U; channel++) {
        atomic_init(&seat_focus->target_delay_samples_bits[channel], float_to_bits(0.0f));
        atomic_init(&seat_focus->target_gain_bits[channel], float_to_bits(1.0f));
    }
    if (config->enabled == 0 || channels != 2) {
        return 1;
    }

    seat_focus->sample_rate = sample_rate;
    const double budget_frames = ceil((double)sample_rate * DSP_SEAT_FOCUS_DELAY_BUDGET_MS / 1000.0);
    if (!isfinite(budget_frames) || budget_frames < 1.0 || budget_frames > INT32_MAX) {
        return 0;
    }
    seat_focus->latency_frames = (int)budget_frames;
    seat_focus->prepared_trim_headroom_db = config->bypassed != 0
        ? 0.0
        : fmax(0.0, fmax(config->left_trim_db, config->right_trim_db));
    seat_focus->capacity_frames = (size_t)seat_focus->latency_frames + DSP_SEAT_FOCUS_INTERPOLATION_TAPS;
    if (seat_focus->capacity_frames > SIZE_MAX / (2U * sizeof(float))) {
        return 0;
    }
    seat_focus->delay_ring = (float *)calloc(seat_focus->capacity_frames * 2U, sizeof(float));
    if (seat_focus->delay_ring == NULL) {
        return 0;
    }
    if ((size_t)DSP_SEAT_FOCUS_LAGRANGE_PHASES + 1U >
        SIZE_MAX / (DSP_SEAT_FOCUS_INTERPOLATION_TAPS * sizeof(float))) {
        dsp_seat_focus_close(seat_focus);
        return 0;
    }
    const size_t coefficient_count = ((size_t)DSP_SEAT_FOCUS_LAGRANGE_PHASES + 1U) *
        DSP_SEAT_FOCUS_INTERPOLATION_TAPS;
    seat_focus->lagrange_table = (float *)calloc(coefficient_count, sizeof(float));
    if (seat_focus->lagrange_table == NULL) {
        dsp_seat_focus_close(seat_focus);
        return 0;
    }
    for (size_t phase = 0; phase <= DSP_SEAT_FOCUS_LAGRANGE_PHASES; phase++) {
        design_lagrange_coefficients(
            (double)phase / (double)DSP_SEAT_FOCUS_LAGRANGE_PHASES,
            seat_focus->lagrange_table + phase * DSP_SEAT_FOCUS_INTERPOLATION_TAPS);
    }

    dsp_seat_focus_target targets[2];
    if (!calculate_targets(seat_focus, config, targets)) {
        dsp_seat_focus_close(seat_focus);
        return 0;
    }
    seat_focus->active = 1;
    seat_focus->transition_frames = (uint32_t)ceil((double)sample_rate * DSP_SEAT_FOCUS_TRANSITION_MS / 1000.0);
    seat_focus->observed_generation = 0;
    seat_focus->transition_position = seat_focus->transition_frames;
    for (size_t channel = 0; channel < 2U; channel++) {
        seat_focus->current_delay_samples[channel] = targets[channel].delay_samples;
        seat_focus->transition_start_delay_samples[channel] = targets[channel].delay_samples;
        seat_focus->target_delay_samples[channel] = targets[channel].delay_samples;
        seat_focus->current_gain[channel] = targets[channel].gain;
        seat_focus->transition_start_gain[channel] = targets[channel].gain;
        seat_focus->target_gain[channel] = targets[channel].gain;
        atomic_store_explicit(&seat_focus->target_delay_samples_bits[channel],
            float_to_bits(targets[channel].delay_samples), memory_order_relaxed);
        atomic_store_explicit(&seat_focus->target_gain_bits[channel], float_to_bits(targets[channel].gain), memory_order_relaxed);
    }
    return 1;
}

int dsp_seat_focus_update(dsp_seat_focus *seat_focus, const dsp_seat_focus_config *config) {
    if (seat_focus == NULL || seat_focus->active == 0 || config == NULL || config->enabled == 0) {
        return 0;
    }
    dsp_seat_focus_target targets[2];
    if (!calculate_targets(seat_focus, config, targets)) return 0;
    for (size_t channel = 0; channel < 2U; channel++) {
        atomic_store_explicit(&seat_focus->target_delay_samples_bits[channel],
            float_to_bits(targets[channel].delay_samples), memory_order_relaxed);
        atomic_store_explicit(&seat_focus->target_gain_bits[channel], float_to_bits(targets[channel].gain), memory_order_relaxed);
    }
    atomic_fetch_add_explicit(&seat_focus->target_generation, 1U, memory_order_release);
    return 1;
}

static void adopt_target_if_changed(dsp_seat_focus *seat_focus);

void dsp_seat_focus_reset(dsp_seat_focus *seat_focus) {
    if (seat_focus == NULL || seat_focus->active == 0) return;
    memset(seat_focus->delay_ring, 0, seat_focus->capacity_frames * 2U * sizeof(float));
    seat_focus->write_position = 0;
    seat_focus->transition_position = seat_focus->transition_frames;
    adopt_target_if_changed(seat_focus);
}

void dsp_seat_focus_close(dsp_seat_focus *seat_focus) {
    if (seat_focus == NULL) return;
    free(seat_focus->delay_ring);
    free(seat_focus->lagrange_table);
    seat_focus->delay_ring = NULL;
    seat_focus->lagrange_table = NULL;
    seat_focus->active = 0;
    seat_focus->capacity_frames = 0;
    seat_focus->latency_frames = 0;
}

static float read_fractional_delay(
    const dsp_seat_focus *seat_focus,
    size_t channel,
    float delay_samples) {
    const float integer_part = floorf(delay_samples);
    const size_t integer_delay = (size_t)integer_part;
    const float fraction = delay_samples - integer_part;
    const float phase = fraction * (float)DSP_SEAT_FOCUS_LAGRANGE_PHASES;
    size_t phase_index = (size_t)phase;
    float phase_mix = phase - (float)phase_index;
    if (phase_index >= DSP_SEAT_FOCUS_LAGRANGE_PHASES) {
        phase_index = DSP_SEAT_FOCUS_LAGRANGE_PHASES - 1U;
        phase_mix = 1.0f;
    }
    float result = 0.0f;
    for (size_t tap = 0; tap < DSP_SEAT_FOCUS_INTERPOLATION_TAPS; tap++) {
        const size_t coefficient_index = phase_index * DSP_SEAT_FOCUS_INTERPOLATION_TAPS + tap;
        const float coefficient_start = seat_focus->lagrange_table[coefficient_index];
        const float coefficient_end = seat_focus->lagrange_table[coefficient_index + DSP_SEAT_FOCUS_INTERPOLATION_TAPS];
        const float coefficient = coefficient_start + (coefficient_end - coefficient_start) * phase_mix;
        const size_t offset = integer_delay + tap;
        size_t read_position = seat_focus->write_position;
        read_position = (read_position + seat_focus->capacity_frames - offset) % seat_focus->capacity_frames;
        result += seat_focus->delay_ring[read_position * 2U + channel] * coefficient;
    }
    return result;
}

static void adopt_target_if_changed(dsp_seat_focus *seat_focus) {
    const uint32_t generation = atomic_load_explicit(&seat_focus->target_generation, memory_order_acquire);
    if (generation == seat_focus->observed_generation ||
        seat_focus->transition_position < seat_focus->transition_frames) return;
    for (size_t channel = 0; channel < 2U; channel++) {
        seat_focus->transition_start_delay_samples[channel] = seat_focus->current_delay_samples[channel];
        seat_focus->transition_start_gain[channel] = seat_focus->current_gain[channel];
        seat_focus->target_delay_samples[channel] = bits_to_float(
            atomic_load_explicit(&seat_focus->target_delay_samples_bits[channel], memory_order_relaxed));
        seat_focus->target_gain[channel] = bits_to_float(
            atomic_load_explicit(&seat_focus->target_gain_bits[channel], memory_order_relaxed));
    }
    seat_focus->transition_position = 0;
    seat_focus->observed_generation = generation;
}

void dsp_seat_focus_process_frame(dsp_seat_focus *seat_focus, float *left, float *right, int channels) {
    if (seat_focus == NULL || seat_focus->active == 0 || left == NULL || right == NULL || channels != 2) return;
    adopt_target_if_changed(seat_focus);
    seat_focus->delay_ring[seat_focus->write_position * 2U] = *left;
    seat_focus->delay_ring[seat_focus->write_position * 2U + 1U] = *right;

    const int transitioning = seat_focus->transition_position < seat_focus->transition_frames;
    float progress = 1.0f;
    if (transitioning) {
        progress = (float)(seat_focus->transition_position + 1U) / (float)seat_focus->transition_frames;
        progress = progress * progress * (3.0f - 2.0f * progress);
        seat_focus->transition_position++;
    }
    for (size_t channel = 0; channel < 2U; channel++) {
        const float delay = seat_focus->transition_start_delay_samples[channel] +
            (seat_focus->target_delay_samples[channel] - seat_focus->transition_start_delay_samples[channel]) * progress;
        const float gain = seat_focus->transition_start_gain[channel] +
            (seat_focus->target_gain[channel] - seat_focus->transition_start_gain[channel]) * progress;
        const float sample = read_fractional_delay(seat_focus, channel, delay) * gain;
        if (channel == 0U) {
            *left = sample;
        } else {
            *right = sample;
        }
    }
    if (transitioning && seat_focus->transition_position >= seat_focus->transition_frames) {
        for (size_t channel = 0; channel < 2U; channel++) {
            seat_focus->current_delay_samples[channel] = seat_focus->target_delay_samples[channel];
            seat_focus->current_gain[channel] = seat_focus->target_gain[channel];
        }
    }
    seat_focus->write_position++;
    if (seat_focus->write_position == seat_focus->capacity_frames) seat_focus->write_position = 0;
}

int dsp_seat_focus_latency_frames(const dsp_seat_focus *seat_focus) {
    return seat_focus != NULL && seat_focus->active != 0 ? seat_focus->latency_frames : 0;
}
