#include "dsp_graph.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define DSP_PEQ_WARM_DECAY_FLOOR 1e-7
#define DSP_PEQ_WARM_CONTROL_CATCH_UP_MAX 4096u
#define DSP_PEQ_WARM_CONTROL_CATCH_UP_ATTEMPTS 3u

_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2, "PEQ input history requires lock-free 64-bit atomics");

static unsigned long long pack_float_samples(float left, float right) {
    uint32_t left_bits;
    uint32_t right_bits;
    memcpy(&left_bits, &left, sizeof(left_bits));
    memcpy(&right_bits, &right, sizeof(right_bits));
    return ((unsigned long long)right_bits << 32) | (unsigned long long)left_bits;
}

static float unpack_float_sample(uint32_t bits) {
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static void record_peq_input(dsp_peq_input_history *history, float left, float right) {
    if (history == NULL || history->entries == NULL) return;
    const unsigned long long sequence = history->writer_sequence + 1;
    dsp_peq_history_entry *entry = &history->entries[(sequence - 1) % DSP_PEQ_WARM_HISTORY_CAPACITY];
    atomic_store_explicit(&entry->sequence, 0, memory_order_relaxed);
    atomic_store_explicit(&entry->packed_samples, pack_float_samples(left, right), memory_order_relaxed);
    atomic_store_explicit(&entry->sequence, sequence, memory_order_release);
    history->writer_sequence = sequence;
    atomic_store_explicit(&history->published_sequence, sequence, memory_order_release);
}

static double biquad_max_pole_radius(const double *coefficients) {
    const double a1 = coefficients[3];
    const double a2 = coefficients[4];
    const double discriminant = a1 * a1 - 4.0 * a2;
    if (discriminant < 0.0) return sqrt(a2);
    const double root = sqrt(discriminant);
    const double first = fabs((-a1 + root) * 0.5);
    const double second = fabs((-a1 - root) * 0.5);
    return fmax(first, second);
}

static size_t biquad_warm_history_frames(const double *coefficients) {
    const double radius = biquad_max_pole_radius(coefficients);
    if (!isfinite(radius) || radius < 0.0 || radius >= 1.0) return DSP_PEQ_WARM_HISTORY_CAPACITY + 1;
    if (radius <= 0.0) return 1;
    const double required = ceil(log(DSP_PEQ_WARM_DECAY_FLOOR) / log(radius));
    if (!isfinite(required) || required > (double)DSP_PEQ_WARM_HISTORY_CAPACITY) {
        return DSP_PEQ_WARM_HISTORY_CAPACITY + 1;
    }
    return (size_t)fmax(1.0, required);
}

static int snapshot_peq_history_range(
    const dsp_peq_input_history *history,
    unsigned long long expected_epoch_sequence,
    unsigned long long first_sequence,
    unsigned long long end_sequence,
    float *destination) {
    if (history == NULL || history->entries == NULL || destination == NULL ||
        end_sequence < first_sequence || first_sequence <= expected_epoch_sequence ||
        end_sequence - first_sequence >= DSP_PEQ_WARM_HISTORY_CAPACITY) return 0;

    const unsigned long long epoch_before = atomic_load_explicit(&history->reset_sequence, memory_order_acquire);
    const unsigned long long published_before =
        atomic_load_explicit(&history->published_sequence, memory_order_acquire);
    if (epoch_before != expected_epoch_sequence || published_before < end_sequence ||
        published_before - first_sequence >= DSP_PEQ_WARM_HISTORY_CAPACITY) return 0;

    const size_t frame_count = (size_t)(end_sequence - first_sequence + 1);
    for (size_t index = 0; index < frame_count; index++) {
        const unsigned long long expected_sequence = first_sequence + index;
        const dsp_peq_history_entry *entry =
            &history->entries[(expected_sequence - 1) % DSP_PEQ_WARM_HISTORY_CAPACITY];
        const unsigned long long first_read = atomic_load_explicit(&entry->sequence, memory_order_acquire);
        if (first_read != expected_sequence) return 0;
        const unsigned long long packed_samples =
            atomic_load_explicit(&entry->packed_samples, memory_order_relaxed);
        const unsigned long long second_read = atomic_load_explicit(&entry->sequence, memory_order_acquire);
        if (first_read != second_read) return 0;
        destination[index * 2] = unpack_float_sample((uint32_t)packed_samples);
        destination[index * 2 + 1] = unpack_float_sample((uint32_t)(packed_samples >> 32));
    }

    const unsigned long long epoch_final = atomic_load_explicit(&history->reset_sequence, memory_order_acquire);
    const unsigned long long published_final =
        atomic_load_explicit(&history->published_sequence, memory_order_acquire);
    return epoch_final == expected_epoch_sequence && published_final >= end_sequence &&
        published_final - first_sequence < DSP_PEQ_WARM_HISTORY_CAPACITY;
}

static int snapshot_peq_history_tail(
    const dsp_peq_input_history *history,
    size_t required_frames,
    float *destination,
    unsigned long long *history_epoch_sequence,
    unsigned long long *history_end_sequence) {
    if (history == NULL || history->entries == NULL || required_frames > DSP_PEQ_WARM_HISTORY_CAPACITY ||
        (required_frames > 0 && destination == NULL) || history_epoch_sequence == NULL ||
        history_end_sequence == NULL) return 0;

    const unsigned long long epoch = atomic_load_explicit(&history->reset_sequence, memory_order_acquire);
    const unsigned long long end_sequence =
        atomic_load_explicit(&history->published_sequence, memory_order_acquire);
    if (end_sequence < epoch || end_sequence - epoch < required_frames) return 0;
    if (required_frames > 0) {
        const unsigned long long first_sequence = end_sequence - required_frames + 1;
        if (!snapshot_peq_history_range(
                history, epoch, first_sequence, end_sequence, destination)) return 0;
    }
    if (atomic_load_explicit(&history->reset_sequence, memory_order_acquire) != epoch) return 0;
    *history_epoch_sequence = epoch;
    *history_end_sequence = end_sequence;
    return 1;
}

static void process_peq_history(
    dsp_biquad *prepared_bands,
    size_t peq_count,
    const float *history_samples,
    size_t frame_count) {
    for (size_t frame = 0; frame < frame_count; frame++) {
        float left = history_samples[frame * 2];
        float right = history_samples[frame * 2 + 1];
        for (size_t band = 0; band < peq_count; band++) {
            left = dsp_biquad_process_sample(&prepared_bands[band], left, 0);
            right = dsp_biquad_process_sample(&prepared_bands[band], right, 1);
        }
    }
}

int dsp_graph_prepare(
    dsp_graph *graph,
    int sample_rate,
    int channels,
    double gain_db,
    const double *peq_coefficients,
    size_t peq_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const dsp_dynamic_eq_config *dynamic_eq_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config,
    const dsp_harmonic_config *harmonic_config,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config) {
    if (graph == NULL || peq_count > DSP_GRAPH_EQ_FILTER_CAPACITY ||
        (peq_count > 0 && peq_coefficients == NULL) || !dsp_gain_set_db(&graph->gain, gain_db)) {
        return 0;
    }
    graph->sample_rate = sample_rate;
    graph->peq_history.entries = (dsp_peq_history_entry *)calloc(
        DSP_PEQ_WARM_HISTORY_CAPACITY,
        sizeof(*graph->peq_history.entries));
    if (graph->peq_history.entries == NULL) return 0;
    graph->peq_history.writer_sequence = 0;
    atomic_init(&graph->peq_history.published_sequence, 0);
    atomic_init(&graph->peq_history.reset_sequence, 0);
    for (size_t index = 0; index < DSP_PEQ_WARM_HISTORY_CAPACITY; index++) {
        atomic_init(&graph->peq_history.entries[index].sequence, 0);
        atomic_init(&graph->peq_history.entries[index].packed_samples, 0);
    }
    graph->peq_transition_frames = (size_t)fmax(1.0, floor((double)sample_rate / 100.0));
    graph->peq_count = peq_count;
    for (size_t index = 0; index < peq_count; index++) {
        if (!dsp_biquad_prepare(
                &graph->peq[index],
                peq_coefficients + index * DSP_BIQUAD_COEFFICIENT_COUNT)) {
            return 0;
        }
    }
    if (!dsp_dynamics_prepare(&graph->dynamics, sample_rate, dynamics_config)) {
        return 0;
    }
    if (!dsp_multiband_prepare(&graph->multiband, sample_rate, multiband_config)) {
        return 0;
    }
    if (!dsp_dynamic_eq_prepare(&graph->dynamic_eq, sample_rate, dynamic_eq_config)) {
        return 0;
    }
    if (!dsp_spatial_prepare(
            &graph->spatial,
            spatial_config,
            sample_rate,
            channels,
            bass_coefficients,
            bass_coefficient_count,
            mono_bass_coefficients,
            mono_bass_coefficient_count)) {
        return 0;
    }
    if (!dsp_harmonic_prepare(&graph->harmonic, sample_rate, channels, harmonic_config)) {
        return 0;
    }
    if (!dsp_convolver_prepare(&graph->convolver, sample_rate, channels, convolver_config)) {
        return 0;
    }
    if (!dsp_macro_prepare(&graph->macro, sample_rate, channels, macro_config) ||
        !dsp_smart_loudness_prepare(&graph->smart_loudness, sample_rate, channels, smart_loudness_config) ||
        !dsp_dynamic_bass_prepare(&graph->dynamic_bass, sample_rate, channels, dynamic_bass_config)) {
        return 0;
    }
    graph->pending_studio_gain_db = gain_db;
    graph->studio_gain_refund_pending = 0;
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
    return 1;
}

int dsp_graph_studio_hot_config_is_valid(
    double gain_db,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config) {
    return isfinite(gain_db) && gain_db >= -700.0 && gain_db <= 24.0 &&
        dsp_macro_config_is_valid(macro_config) &&
        dsp_smart_loudness_config_is_valid(smart_loudness_config) &&
        dsp_dynamic_bass_config_is_valid(dynamic_bass_config);
}

static int studio_module_gains_are_settled(const dsp_graph *graph) {
    const double dynamic_bass_gain_ceiling = graph->dynamic_bass.target_enabled
        ? graph->dynamic_bass.max_gain
        : 1.0;
    return graph->macro.ramp_frames_remaining <= 0 &&
        graph->smart_loudness.ramp_frames_remaining <= 0 &&
        graph->dynamic_bass.current_gain <= dynamic_bass_gain_ceiling + 1.0e-6;
}

int dsp_graph_apply_studio_hot(
    dsp_graph *graph,
    double gain_db,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config) {
    if (graph == NULL || !dsp_graph_studio_hot_config_is_valid(
            gain_db, macro_config, smart_loudness_config, dynamic_bass_config)) return 0;
    const double current_gain_db = graph->gain.decibels;
    if (!dsp_macro_apply_hot(&graph->macro, macro_config) ||
        !dsp_smart_loudness_apply_hot(&graph->smart_loudness, smart_loudness_config) ||
        !dsp_dynamic_bass_apply_hot(&graph->dynamic_bass, dynamic_bass_config)) {
        return 0;
    }
    if (gain_db <= current_gain_db) {
        if (!dsp_gain_set_db(&graph->gain, gain_db)) return 0;
        graph->studio_gain_refund_pending = 0;
    } else {
        graph->pending_studio_gain_db = gain_db;
        graph->studio_gain_refund_pending = 1;
        if (studio_module_gains_are_settled(graph)) {
            if (!dsp_gain_set_db(&graph->gain, gain_db)) return 0;
            graph->studio_gain_refund_pending = 0;
        }
    }
    return 1;
}

int dsp_graph_apply_preamp_hot(dsp_graph *graph, double gain_db, double gain_linear) {
    if (graph == NULL || !isfinite(gain_db) || gain_db < -120.0 || gain_db > -6.0 ||
        !isfinite(gain_linear) || gain_linear < 1.0e-6 || gain_linear > 0.50118724) return 0;
    const uint32_t ramp_samples = (uint32_t)(graph->sample_rate / 50);
    if (!dsp_gain_set_linear_ramped(&graph->gain, (float)gain_linear, gain_db, ramp_samples)) return 0;
    graph->studio_gain_refund_pending = 0;
    return 1;
}

static void apply_pending_studio_gain_refund(dsp_graph *graph) {
    if (!graph->studio_gain_refund_pending || !studio_module_gains_are_settled(graph)) {
        return;
    }
    if (dsp_gain_set_db(&graph->gain, graph->pending_studio_gain_db)) {
        graph->studio_gain_refund_pending = 0;
    }
}
int dsp_graph_prepare_local_peq(
    const dsp_graph *graph,
    const double *peq_coefficients,
    size_t peq_count,
    dsp_biquad *prepared_bands,
    unsigned long long *history_epoch_sequence,
    unsigned long long *history_end_sequence) {
    if (graph == NULL || graph->peq_history.entries == NULL || peq_count > DSP_GRAPH_EQ_FILTER_CAPACITY ||
        (peq_count > 0 && (peq_coefficients == NULL || prepared_bands == NULL)) ||
        history_epoch_sequence == NULL || history_end_sequence == NULL) return 0;

    size_t required_frames = 0;
    for (size_t index = 0; index < peq_count; index++) {
        const double *coefficients = peq_coefficients + index * DSP_BIQUAD_COEFFICIENT_COUNT;
        if (!dsp_biquad_prepare(&prepared_bands[index], coefficients)) return 0;
        const size_t band_frames = biquad_warm_history_frames(coefficients);
        if (band_frames > DSP_PEQ_WARM_HISTORY_CAPACITY) return 0;
        required_frames = required_frames > band_frames ? required_frames : band_frames;
    }
    if (required_frames == 0) {
        return snapshot_peq_history_tail(
            &graph->peq_history, 0, NULL, history_epoch_sequence, history_end_sequence);
    }

    const size_t scratch_frames = required_frames > DSP_PEQ_WARM_CONTROL_CATCH_UP_MAX
        ? required_frames
        : DSP_PEQ_WARM_CONTROL_CATCH_UP_MAX;
    float *history_samples = (float *)malloc(scratch_frames * 2 * sizeof(*history_samples));
    if (history_samples == NULL) return 0;
    const int snapshot_ready = snapshot_peq_history_tail(
        &graph->peq_history, required_frames, history_samples, history_epoch_sequence, history_end_sequence);
    if (!snapshot_ready) {
        free(history_samples);
        return 0;
    }

    process_peq_history(prepared_bands, peq_count, history_samples, required_frames);
    int caught_up = 0;
    for (size_t attempt = 0; attempt < DSP_PEQ_WARM_CONTROL_CATCH_UP_ATTEMPTS; attempt++) {
        if (atomic_load_explicit(&graph->peq_history.reset_sequence, memory_order_acquire) !=
            *history_epoch_sequence) break;
        const unsigned long long published_sequence =
            atomic_load_explicit(&graph->peq_history.published_sequence, memory_order_acquire);
        if (published_sequence < *history_end_sequence) break;
        const unsigned long long frame_count = published_sequence - *history_end_sequence;
        if (frame_count == 0) {
            caught_up = 1;
            break;
        }
        if (frame_count > DSP_PEQ_WARM_CONTROL_CATCH_UP_MAX ||
            !snapshot_peq_history_range(
                &graph->peq_history,
                *history_epoch_sequence,
                *history_end_sequence + 1,
                published_sequence,
                history_samples)) break;
        process_peq_history(prepared_bands, peq_count, history_samples, (size_t)frame_count);
        *history_end_sequence = published_sequence;
    }

    const unsigned long long latest_sequence =
        atomic_load_explicit(&graph->peq_history.published_sequence, memory_order_acquire);
    const unsigned long long final_epoch =
        atomic_load_explicit(&graph->peq_history.reset_sequence, memory_order_acquire);
    if (!caught_up && latest_sequence >= *history_end_sequence &&
        latest_sequence - *history_end_sequence <= DSP_PEQ_WARM_AUDIO_CATCH_UP_MAX &&
        final_epoch == *history_epoch_sequence) caught_up = 1;
    free(history_samples);
    return caught_up;
}

int dsp_graph_begin_local_peq(
    dsp_graph *graph,
    const dsp_biquad *prepared_bands,
    size_t peq_count,
    unsigned long long history_epoch_sequence,
    unsigned long long history_end_sequence) {
    if (graph == NULL || peq_count > DSP_GRAPH_EQ_FILTER_CAPACITY ||
        (peq_count > 0 && prepared_bands == NULL) || graph->peq_transition_active) return 0;
    if (peq_count > 0) {
        memcpy(graph->peq_target, prepared_bands, peq_count * sizeof(*prepared_bands));
    }
    const unsigned long long epoch =
        atomic_load_explicit(&graph->peq_history.reset_sequence, memory_order_acquire);
    const unsigned long long published_sequence =
        atomic_load_explicit(&graph->peq_history.published_sequence, memory_order_acquire);
    if (epoch != history_epoch_sequence || published_sequence < history_end_sequence ||
        published_sequence - history_end_sequence > DSP_PEQ_WARM_AUDIO_CATCH_UP_MAX) return 0;
    const unsigned long long catch_up_frames = published_sequence - history_end_sequence;
    if (catch_up_frames > 0) {
        float history_samples[DSP_PEQ_WARM_AUDIO_CATCH_UP_MAX * 2];
        if (!snapshot_peq_history_range(
                &graph->peq_history,
                history_epoch_sequence,
                history_end_sequence + 1,
                published_sequence,
                history_samples)) return 0;
        process_peq_history(graph->peq_target, peq_count, history_samples, (size_t)catch_up_frames);
    }
    graph->peq_target_count = peq_count;
    graph->peq_transition_position = 0;
    graph->peq_transition_canceling = 0;
    graph->peq_cancel_position = 0;
    graph->peq_cancel_frames = 0;
    graph->peq_cancel_start_mix = 0.0f;
    graph->peq_transition_active = 1;
    return 1;
}

int dsp_graph_local_peq_transition_active(const dsp_graph *graph) {
    return graph != NULL && graph->peq_transition_active;
}

int dsp_graph_begin_cancel_local_peq(dsp_graph *graph) {
    if (graph == NULL || !graph->peq_transition_active) return 0;
    if (graph->peq_transition_canceling) return 1;

    const size_t denominator = graph->peq_transition_frames > 1 ? graph->peq_transition_frames - 1 : 1;
    const float current_mix = graph->peq_transition_frames > 1
        ? (float)graph->peq_transition_position / (float)denominator
        : 1.0f;
    if (current_mix <= 0.0f) {
        dsp_graph_cancel_local_peq(graph);
        return 1;
    }

    graph->peq_transition_canceling = 1;
    graph->peq_cancel_position = 0;
    graph->peq_cancel_start_mix = current_mix;
    graph->peq_cancel_frames = (size_t)ceil(current_mix * (float)denominator) + 1;
    return 1;
}

void dsp_graph_cancel_local_peq(dsp_graph *graph) {
    if (graph == NULL) return;
    graph->peq_transition_active = 0;
    graph->peq_transition_canceling = 0;
    graph->peq_transition_position = 0;
    graph->peq_cancel_position = 0;
    graph->peq_cancel_frames = 0;
    graph->peq_cancel_start_mix = 0.0f;
    graph->peq_target_count = 0;
}

void dsp_graph_reset(dsp_graph *graph) {
    if (graph == NULL) {
        return;
    }
    for (size_t index = 0; index < graph->peq_count; index++) {
        dsp_biquad_reset(&graph->peq[index]);
    }
    for (size_t index = 0; index < graph->peq_target_count; index++) {
        dsp_biquad_reset(&graph->peq_target[index]);
    }
    dsp_graph_cancel_local_peq(graph);
    if (graph->peq_history.entries != NULL) {
        atomic_store_explicit(
            &graph->peq_history.reset_sequence,
            graph->peq_history.writer_sequence,
            memory_order_release);
    }
    dsp_dynamics_reset(&graph->dynamics);
    dsp_multiband_reset(&graph->multiband);
    dsp_dynamic_eq_reset(&graph->dynamic_eq);
    dsp_spatial_reset(&graph->spatial);
    dsp_harmonic_reset(&graph->harmonic);
    dsp_convolver_reset(&graph->convolver);
    dsp_macro_reset(&graph->macro);
    dsp_smart_loudness_reset(&graph->smart_loudness);
    dsp_dynamic_bass_reset(&graph->dynamic_bass);
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
}

void dsp_graph_close(dsp_graph *graph) {
    if (graph == NULL) return;
    dsp_convolver_close(&graph->convolver);
    free(graph->peq_history.entries);
    graph->peq_history.entries = NULL;
}

void dsp_graph_process(
    dsp_graph *graph,
    const float *input,
    float *output,
    size_t frames,
    size_t channels,
    uint64_t *non_finite_input,
    uint64_t *non_finite_output) {
    if (graph == NULL || input == NULL || output == NULL || channels < 1 || channels > 2) {
        return;
    }

    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
    for (size_t frame = 0; frame < frames; frame++) {
        apply_pending_studio_gain_refund(graph);
        const size_t offset = frame * channels;
        float input_left = input[offset];
        float input_right = channels == 2 ? input[offset + 1] : 0.0f;
        if (!isfinite(input_left)) {
            input_left = 0.0f;
            if (non_finite_input != NULL) {
                (*non_finite_input)++;
            }
        }
        if (channels == 2 && !isfinite(input_right)) {
            input_right = 0.0f;
            if (non_finite_input != NULL) {
                (*non_finite_input)++;
            }
        }
        dsp_meter_add_frame(&graph->input_meter, input_left, input_right);

        const float detector_input_left = input_left;
        const float detector_input_right = input_right;

        float output_left = dsp_gain_apply(&graph->gain, input_left);
        float output_right = channels == 2 ? dsp_gain_apply(&graph->gain, input_right) : 0.0f;
        dsp_spatial_process_seat(&graph->spatial, &output_left, &output_right, (int)channels);
        record_peq_input(&graph->peq_history, output_left, output_right);
        if (graph->peq_transition_active) {
            float target_left = output_left;
            float target_right = output_right;
            for (size_t index = 0; index < graph->peq_count; index++) {
                output_left = dsp_biquad_process_sample(&graph->peq[index], output_left, 0);
                if (channels == 2) output_right = dsp_biquad_process_sample(&graph->peq[index], output_right, 1);
            }
            for (size_t index = 0; index < graph->peq_target_count; index++) {
                target_left = dsp_biquad_process_sample(&graph->peq_target[index], target_left, 0);
                if (channels == 2) target_right = dsp_biquad_process_sample(&graph->peq_target[index], target_right, 1);
            }
            float mix;
            if (graph->peq_transition_canceling) {
                const size_t cancel_denominator = graph->peq_cancel_frames > 1
                    ? graph->peq_cancel_frames - 1
                    : 1;
                const float cancel_progress = graph->peq_cancel_frames > 1
                    ? (float)graph->peq_cancel_position / (float)cancel_denominator
                    : 1.0f;
                mix = graph->peq_cancel_start_mix * (1.0f - cancel_progress);
            } else {
                const size_t denominator = graph->peq_transition_frames > 1
                    ? graph->peq_transition_frames - 1
                    : 1;
                mix = graph->peq_transition_frames > 1
                    ? (float)graph->peq_transition_position / (float)denominator
                    : 1.0f;
            }
            output_left += (target_left - output_left) * mix;
            if (channels == 2) output_right += (target_right - output_right) * mix;
            if (graph->peq_transition_canceling) {
                graph->peq_cancel_position++;
                if (graph->peq_cancel_position >= graph->peq_cancel_frames) {
                    dsp_graph_cancel_local_peq(graph);
                }
            } else {
                graph->peq_transition_position++;
                if (graph->peq_transition_position >= graph->peq_transition_frames) {
                    if (graph->peq_target_count > 0) {
                        memcpy(graph->peq, graph->peq_target, graph->peq_target_count * sizeof(*graph->peq));
                    }
                    graph->peq_count = graph->peq_target_count;
                    graph->peq_target_count = 0;
                    graph->peq_transition_position = 0;
                    graph->peq_transition_active = 0;
                }
            }
        } else {
            for (size_t index = 0; index < graph->peq_count; index++) {
                output_left = dsp_biquad_process_sample(&graph->peq[index], output_left, 0);
                if (channels == 2) output_right = dsp_biquad_process_sample(&graph->peq[index], output_right, 1);
            }
        }
        output_left = dsp_spatial_process_bass(&graph->spatial, output_left, 0);
        if (channels == 2) output_right = dsp_spatial_process_bass(&graph->spatial, output_right, 1);
        dsp_macro_process_frame(&graph->macro, &output_left, &output_right, (int)channels);
        dsp_smart_loudness_process_frame(&graph->smart_loudness, &output_left, &output_right, (int)channels);
        dsp_dynamic_bass_process_frame_with_detector(
            &graph->dynamic_bass,
            detector_input_left,
            detector_input_right,
            &output_left,
            &output_right,
            (int)channels);
        dsp_dynamics_process_compressor_frame(&graph->dynamics, &output_left, &output_right, (int)channels);
        dsp_multiband_process_frame(&graph->multiband, &output_left, &output_right, (int)channels);
        dsp_dynamic_eq_process_frame(&graph->dynamic_eq, &output_left, &output_right, (int)channels);
        dsp_spatial_process_stereo(&graph->spatial, &output_left, &output_right, (int)channels);
        dsp_harmonic_process_frame(&graph->harmonic, &output_left, &output_right, (int)channels);
        dsp_convolver_process_frame(&graph->convolver, &output_left, &output_right, (int)channels);
        dsp_dynamics_process_limiter_frame(&graph->dynamics, &output_left, &output_right, (int)channels);
        if (!isfinite(output_left)) {
            output_left = 0.0f;
            if (non_finite_output != NULL) {
                (*non_finite_output)++;
            }
        }
        if (channels == 2 && !isfinite(output_right)) {
            output_right = 0.0f;
            if (non_finite_output != NULL) {
                (*non_finite_output)++;
            }
        }
        output[offset] = output_left;
        if (channels == 2) {
            output[offset + 1] = output_right;
        }
        dsp_meter_add_frame(&graph->output_meter, output_left, output_right);
        dsp_gain_advance(&graph->gain);
    }
    for (size_t index = 0; index < graph->peq_count; index++) {
        dsp_biquad_flush_denormals(&graph->peq[index]);
    }
    if (graph->peq_transition_active) {
        for (size_t index = 0; index < graph->peq_target_count; index++) {
            dsp_biquad_flush_denormals(&graph->peq_target[index]);
        }
    }
    dsp_spatial_flush_denormals(&graph->spatial);
    dsp_harmonic_flush_denormals(&graph->harmonic);
    dsp_multiband_flush_denormals(&graph->multiband);
    dsp_dynamic_eq_flush_denormals(&graph->dynamic_eq);
    dsp_convolver_flush_denormals(&graph->convolver);
    dsp_macro_flush_denormals(&graph->macro);
    dsp_smart_loudness_flush_denormals(&graph->smart_loudness);
    dsp_dynamic_bass_flush_denormals(&graph->dynamic_bass);
}
