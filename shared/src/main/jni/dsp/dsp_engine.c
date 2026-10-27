#include "dsp_engine.h"

#include "dsp_graph.h"

#include <math.h>
#include <stdatomic.h>
#include <string.h>
#include <stdlib.h>

#define DSP_ENGINE_MAGIC UINT32_C(0x44535031)
#define DSP_HOT_UPDATE_QUEUE_CAPACITY 2u
#define DSP_LOCAL_PEQ_QUEUE_CAPACITY 1u

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "DSP diagnostics require lock-free 32-bit atomics");
_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2, "DSP owner epochs require lock-free 64-bit atomics");

struct dsp_engine {
    uint32_t magic;
    int sample_rate;
    int channels;
    int max_frames;
    dsp_graph graph;
    _Atomic uint32_t diagnostic_values[8];
    _Atomic uint32_t processed_frames;
    _Atomic uint32_t processed_blocks;
    _Atomic uint32_t non_finite_input_samples;
    _Atomic uint32_t non_finite_output_samples;
    _Atomic uint32_t native_error_count;
    _Atomic uint32_t reset_requested;
    dsp_engine_hot_update hot_updates[DSP_HOT_UPDATE_QUEUE_CAPACITY];
    _Atomic uint32_t hot_write_index;
    _Atomic uint32_t hot_read_index;
    _Atomic uint32_t hot_ack_token;
    uint32_t active_hot_ack_token;
    uint32_t active_hot_context_token;
    int active_hot_ack_after_ramp;
    dsp_engine_local_peq_update local_peq_updates[DSP_LOCAL_PEQ_QUEUE_CAPACITY];
    _Atomic uint32_t local_peq_write_index;
    _Atomic uint32_t local_peq_read_index;
    _Atomic uint32_t local_peq_ack_token;
    _Atomic uint32_t local_peq_rejected_token;
    _Atomic uint32_t local_peq_cancel_token;
    _Atomic uint64_t local_peq_last_generation;
    uint32_t active_local_peq_ack_token;
    uint32_t active_local_peq_context_token;
    uint64_t active_local_peq_generation;
    _Atomic uint64_t hot_editor_owner_epoch;
    _Atomic uint64_t hot_renderer_owner_token;
    _Atomic uint64_t hot_pcm_format_epoch;
    _Atomic uint64_t hot_last_generation;
    _Atomic uint32_t hot_context_token;
};

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

static int engine_is_prepared(const dsp_engine *engine) {
    return engine != NULL && engine->magic == DSP_ENGINE_MAGIC &&
        engine->sample_rate >= 8000 && engine->sample_rate <= 192000 &&
        engine->channels >= 1 && engine->channels <= 2 && engine->max_frames > 0;
}

static uint32_t hot_generation_token(uint64_t generation) {
    const uint32_t token = (uint32_t)(generation & UINT64_C(0x7fffffff));
    return token == 0 ? 1 : token;
}

static void saturating_add_counter(_Atomic uint32_t *counter, uint32_t increment) {
    uint32_t current = atomic_load_explicit(counter, memory_order_relaxed);
    while (current != UINT32_MAX) {
        const uint32_t remaining = UINT32_MAX - current;
        const uint32_t next = increment > remaining ? UINT32_MAX : current + increment;
        if (atomic_compare_exchange_weak_explicit(
                counter,
                &current,
                next,
                memory_order_relaxed,
                memory_order_relaxed)) {
            return;
        }
    }
}

dsp_engine *dsp_engine_create_with_studio(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count,
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
    if (sample_rate < 8000 || sample_rate > 192000 || channels < 1 || channels > 2 ||
        max_frames < 1 || max_frames > 65536 || !isfinite(gain_db) || peq_band_count < 0 ||
        peq_band_count > DSP_GRAPH_EQ_FILTER_CAPACITY || (peq_band_count > 0 && peq_coefficients == NULL) ||
        dynamics_config == NULL || multiband_config == NULL || dynamic_eq_config == NULL ||
        bass_coefficient_count < 0 || bass_coefficient_count > 1 ||
        (bass_coefficient_count > 0 && bass_coefficients == NULL) ||
        mono_bass_coefficient_count < 0 || mono_bass_coefficient_count > 1 ||
        (mono_bass_coefficient_count > 0 && mono_bass_coefficients == NULL) || spatial_config == NULL ||
        convolver_config == NULL || harmonic_config == NULL || macro_config == NULL ||
        smart_loudness_config == NULL || dynamic_bass_config == NULL) {
        return NULL;
    }

    dsp_engine *engine = (dsp_engine *)calloc(1, sizeof(*engine));
    if (engine == NULL) {
        return NULL;
    }
    for (size_t index = 0; index < 8; index++) {
        atomic_init(&engine->diagnostic_values[index], float_to_bits(0.0f));
    }
    atomic_init(&engine->processed_frames, 0);
    atomic_init(&engine->processed_blocks, 0);
    atomic_init(&engine->non_finite_input_samples, 0);
    atomic_init(&engine->non_finite_output_samples, 0);
    atomic_init(&engine->native_error_count, 0);
    atomic_init(&engine->reset_requested, 0);
    atomic_init(&engine->hot_write_index, 0);
    atomic_init(&engine->hot_read_index, 0);
    atomic_init(&engine->hot_ack_token, 0);
    atomic_init(&engine->local_peq_write_index, 0);
    atomic_init(&engine->local_peq_read_index, 0);
    atomic_init(&engine->local_peq_ack_token, 0);
    atomic_init(&engine->local_peq_rejected_token, 0);
    atomic_init(&engine->local_peq_cancel_token, 0);
    atomic_init(&engine->local_peq_last_generation, 0);
    atomic_init(&engine->hot_editor_owner_epoch, 0);
    atomic_init(&engine->hot_renderer_owner_token, 0);
    atomic_init(&engine->hot_pcm_format_epoch, 0);
    atomic_init(&engine->hot_last_generation, 0);
    atomic_init(&engine->hot_context_token, 0);
    engine->sample_rate = sample_rate;
    engine->channels = channels;
    engine->max_frames = max_frames;
    if (!dsp_graph_prepare(
            &engine->graph,
            sample_rate,
            channels,
            gain_db,
            peq_coefficients,
            (size_t)peq_band_count,
            dynamics_config,
            multiband_config,
            dynamic_eq_config,
            bass_coefficients,
            bass_coefficient_count,
            mono_bass_coefficients,
            mono_bass_coefficient_count,
            spatial_config,
            convolver_config,
            harmonic_config,
            macro_config,
            smart_loudness_config,
            dynamic_bass_config)) {
        dsp_graph_close(&engine->graph);
        free(engine);
        return NULL;
    }
    engine->magic = DSP_ENGINE_MAGIC;
    return engine;
}

dsp_engine *dsp_engine_create(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const dsp_dynamic_eq_config *dynamic_eq_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config,
    const dsp_harmonic_config *harmonic_config) {
    const dsp_macro_config macro_config = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config smart_loudness_config = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass_config = {
        0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0,
    };
    return dsp_engine_create_with_studio(
        sample_rate,
        channels,
        max_frames,
        gain_db,
        peq_coefficients,
        peq_band_count,
        dynamics_config,
        multiband_config,
        dynamic_eq_config,
        bass_coefficients,
        bass_coefficient_count,
        mono_bass_coefficients,
        mono_bass_coefficient_count,
        spatial_config,
        convolver_config,
        harmonic_config,
        &macro_config,
        &smart_loudness_config,
        &dynamic_bass_config);
}

int dsp_engine_enqueue_hot_update(dsp_engine *engine, const dsp_engine_hot_update *update) {
    if (!engine_is_prepared(engine) || update == NULL || update->generation == 0 ||
        update->generation_token != hot_generation_token(update->generation) ||
        update->editor_owner_epoch == 0 || update->renderer_owner_token == 0 ||
        update->pcm_format_epoch == 0 || update->hot_context_token == 0 || update->media_role != 1 ||
        update->hot_context_token != atomic_load_explicit(&engine->hot_context_token, memory_order_acquire) ||
        update->editor_owner_epoch != atomic_load_explicit(&engine->hot_editor_owner_epoch, memory_order_acquire) ||
        update->renderer_owner_token != atomic_load_explicit(&engine->hot_renderer_owner_token, memory_order_acquire) ||
        update->pcm_format_epoch != atomic_load_explicit(&engine->hot_pcm_format_epoch, memory_order_acquire) ||
        update->generation <= atomic_load_explicit(&engine->hot_last_generation, memory_order_acquire)) return 0;
    if (update->payload_kind == DSP_ENGINE_HOT_KIND_PREAMP) {
        if (update->preamp_version != 1 || !dsp_gain_db_linear_pair_is_valid(update->gain_db, update->gain_linear)) {
            return 0;
        }
    } else if (update->payload_kind != DSP_ENGINE_HOT_KIND_STUDIO ||
        !dsp_graph_studio_hot_config_is_valid(
            update->gain_db,
            &update->macro,
            &update->smart_loudness,
            &update->dynamic_bass)) return 0;
    const uint32_t write_index = atomic_load_explicit(&engine->hot_write_index, memory_order_relaxed);
    const uint32_t read_index = atomic_load_explicit(&engine->hot_read_index, memory_order_acquire);
    if ((uint32_t)(write_index - read_index) >= DSP_HOT_UPDATE_QUEUE_CAPACITY) return 0;
    engine->hot_updates[write_index % DSP_HOT_UPDATE_QUEUE_CAPACITY] = *update;
    atomic_store_explicit(&engine->hot_ack_token, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->local_peq_ack_token, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->hot_write_index, write_index + 1, memory_order_release);
    atomic_store_explicit(&engine->hot_last_generation, update->generation, memory_order_release);
    return 1;
}

int dsp_engine_enqueue_preamp_update(dsp_engine *engine, const dsp_engine_preamp_update *update) {
    if (!engine_is_prepared(engine) || update == NULL || update->version != 1 || update->generation == 0 ||
        update->generation_token != hot_generation_token(update->generation) ||
        update->editor_owner_epoch == 0 || update->renderer_owner_token == 0 || update->pcm_format_epoch == 0 ||
        update->hot_context_token == 0 || update->media_role != 1 ||
        update->hot_context_token != atomic_load_explicit(&engine->hot_context_token, memory_order_acquire) ||
        update->editor_owner_epoch != atomic_load_explicit(&engine->hot_editor_owner_epoch, memory_order_acquire) ||
        update->renderer_owner_token != atomic_load_explicit(&engine->hot_renderer_owner_token, memory_order_acquire) ||
        update->pcm_format_epoch != atomic_load_explicit(&engine->hot_pcm_format_epoch, memory_order_acquire)) return 0;
    const dsp_engine_hot_update hot_update = {
        .preamp_version = update->version,
        .generation_token = update->generation_token,
        .generation = update->generation,
        .editor_owner_epoch = update->editor_owner_epoch,
        .renderer_owner_token = update->renderer_owner_token,
        .pcm_format_epoch = update->pcm_format_epoch,
        .hot_context_token = update->hot_context_token,
        .media_role = update->media_role,
        .gain_db = update->gain_db,
        .gain_linear = update->gain_linear,
        .payload_kind = DSP_ENGINE_HOT_KIND_PREAMP,
    };
    return dsp_engine_enqueue_hot_update(engine, &hot_update);
}

int dsp_engine_prepare_local_peq_update(
    dsp_engine *engine,
    const double *peq_coefficients,
    size_t peq_count,
    double *prepared_values,
    size_t prepared_values_count,
    unsigned long long *history_epoch_sequence,
    unsigned long long *history_end_sequence) {
    if (!engine_is_prepared(engine) || peq_count > DSP_GRAPH_EQ_FILTER_CAPACITY ||
        (peq_count > 0 && (peq_coefficients == NULL || prepared_values == NULL)) ||
        prepared_values_count != peq_count * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND ||
        history_epoch_sequence == NULL || history_end_sequence == NULL) return 0;

    dsp_biquad prepared_bands[DSP_GRAPH_EQ_FILTER_CAPACITY] = {0};
    if (!dsp_graph_prepare_local_peq(
            &engine->graph,
            peq_coefficients,
            peq_count,
            prepared_bands,
            history_epoch_sequence,
            history_end_sequence)) return 0;
    for (size_t band = 0; band < peq_count; band++) {
        const size_t offset = band * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND;
        prepared_values[offset] = prepared_bands[band].b0;
        prepared_values[offset + 1] = prepared_bands[band].b1;
        prepared_values[offset + 2] = prepared_bands[band].b2;
        prepared_values[offset + 3] = prepared_bands[band].a1;
        prepared_values[offset + 4] = prepared_bands[band].a2;
        prepared_values[offset + 5] = prepared_bands[band].z1[0];
        prepared_values[offset + 6] = prepared_bands[band].z1[1];
        prepared_values[offset + 7] = prepared_bands[band].z2[0];
        prepared_values[offset + 8] = prepared_bands[band].z2[1];
    }
    return 1;
}

static int local_peq_coefficients_are_valid(const double *values) {
    for (size_t index = 0; index < DSP_BIQUAD_COEFFICIENT_COUNT; index++) {
        if (!isfinite(values[index])) return 0;
    }
    const double a1 = values[3];
    const double a2 = values[4];
    return fabs(a2) < 1.0 && 1.0 + a1 + a2 > 0.0 && 1.0 - a1 + a2 > 0.0;
}

int dsp_engine_enqueue_local_peq_update(dsp_engine *engine, const dsp_engine_local_peq_update *update) {
    if (!engine_is_prepared(engine) || update == NULL) return DSP_STATUS_INVALID_ARGUMENT;
    const uint64_t latest_control_generation = atomic_load_explicit(
        &engine->hot_last_generation, memory_order_acquire);
    const uint64_t last_local_generation = atomic_load_explicit(
        &engine->local_peq_last_generation, memory_order_acquire);
    const uint32_t rejected_token = atomic_load_explicit(
        &engine->local_peq_rejected_token, memory_order_acquire);
    const int is_rejected_local_retry = update->generation == latest_control_generation &&
        update->generation == last_local_generation && update->generation_token == rejected_token &&
        rejected_token != 0;
    if (!engine_is_prepared(engine) || update == NULL || update->generation == 0 ||
        update->generation_token != hot_generation_token(update->generation) ||
        update->editor_owner_epoch == 0 || update->renderer_owner_token == 0 ||
        update->pcm_format_epoch == 0 || update->hot_context_token == 0 || update->media_role != 1 ||
        update->peq_count > DSP_GRAPH_EQ_FILTER_CAPACITY ||
        update->hot_context_token != atomic_load_explicit(&engine->hot_context_token, memory_order_acquire) ||
        update->editor_owner_epoch != atomic_load_explicit(&engine->hot_editor_owner_epoch, memory_order_acquire) ||
        update->renderer_owner_token != atomic_load_explicit(&engine->hot_renderer_owner_token, memory_order_acquire) ||
        update->pcm_format_epoch != atomic_load_explicit(&engine->hot_pcm_format_epoch, memory_order_acquire) ||
        update->generation < latest_control_generation ||
        (update->generation == latest_control_generation && !is_rejected_local_retry) ||
        update->generation < last_local_generation ||
        (update->generation == last_local_generation && update->generation_token != rejected_token) ||
        update->history_epoch_sequence != atomic_load_explicit(
            &engine->graph.peq_history.reset_sequence, memory_order_acquire)) return DSP_STATUS_STALE_UPDATE;

    const uint64_t published_history_sequence = atomic_load_explicit(
        &engine->graph.peq_history.published_sequence, memory_order_acquire);
    if (update->history_end_sequence < update->history_epoch_sequence ||
        update->history_end_sequence > published_history_sequence) return DSP_STATUS_STALE_UPDATE;

    const uint32_t write_index = atomic_load_explicit(&engine->local_peq_write_index, memory_order_relaxed);
    const uint32_t read_index = atomic_load_explicit(&engine->local_peq_read_index, memory_order_acquire);
    if ((uint32_t)(write_index - read_index) >= DSP_LOCAL_PEQ_QUEUE_CAPACITY) return DSP_STATUS_QUEUE_FULL;

    dsp_engine_local_peq_update prepared = *update;
    for (size_t band = 0; band < prepared.peq_count; band++) {
        dsp_biquad *biquad = &prepared.peq[band];
        const dsp_biquad *source = &update->peq[band];
        const double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT] = {
            source->b0, source->b1, source->b2, source->a1, source->a2,
        };
        if (!local_peq_coefficients_are_valid(coefficients)) return DSP_STATUS_INVALID_ARGUMENT;
        biquad->b0 = source->b0;
        biquad->b1 = source->b1;
        biquad->b2 = source->b2;
        biquad->a1 = source->a1;
        biquad->a2 = source->a2;
        for (size_t channel = 0; channel < 2; channel++) {
            if (!isfinite(source->z1[channel]) || !isfinite(source->z2[channel])) {
                return DSP_STATUS_INVALID_ARGUMENT;
            }
            biquad->z1[channel] = source->z1[channel];
            biquad->z2[channel] = source->z2[channel];
        }
    }

    engine->local_peq_updates[write_index % DSP_LOCAL_PEQ_QUEUE_CAPACITY] = prepared;
    atomic_store_explicit(&engine->hot_ack_token, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->local_peq_ack_token, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->local_peq_rejected_token, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->local_peq_write_index, write_index + 1, memory_order_release);
    atomic_store_explicit(&engine->local_peq_last_generation, update->generation, memory_order_release);
    atomic_store_explicit(&engine->hot_last_generation, update->generation, memory_order_release);
    return DSP_STATUS_OK;
}

int dsp_engine_cancel_local_peq_update(dsp_engine *engine, uint32_t generation_token) {
    if (!engine_is_prepared(engine) || generation_token == 0) return DSP_STATUS_INVALID_ARGUMENT;
    atomic_store_explicit(&engine->local_peq_cancel_token, generation_token, memory_order_release);
    return DSP_STATUS_OK;
}

int dsp_engine_bind_hot_update_context(
    dsp_engine *engine,
    int sample_rate,
    int channels,
    uint64_t editor_owner_epoch,
    uint64_t renderer_owner_token,
    uint64_t pcm_format_epoch,
    uint32_t hot_context_token,
    int media_role) {
    if (!engine_is_prepared(engine) || sample_rate != engine->sample_rate || channels != engine->channels) {
        return 0;
    }
    /* Invalidate queued work before publishing another owner tuple. */
    atomic_store_explicit(&engine->hot_context_token, 0, memory_order_release);
    if (media_role != 1) {
        atomic_store_explicit(&engine->hot_editor_owner_epoch, 0, memory_order_release);
        atomic_store_explicit(&engine->hot_renderer_owner_token, 0, memory_order_release);
        atomic_store_explicit(&engine->hot_pcm_format_epoch, 0, memory_order_release);
        return 1;
    }
    if (editor_owner_epoch == 0 || renderer_owner_token == 0 || pcm_format_epoch == 0 || hot_context_token == 0) return 0;
    atomic_store_explicit(&engine->hot_renderer_owner_token, renderer_owner_token, memory_order_release);
    atomic_store_explicit(&engine->hot_pcm_format_epoch, pcm_format_epoch, memory_order_release);
    atomic_store_explicit(&engine->hot_editor_owner_epoch, editor_owner_epoch, memory_order_release);
    atomic_store_explicit(&engine->hot_context_token, hot_context_token, memory_order_release);
    return 1;
}

uint32_t dsp_engine_get_hot_ack_token(const dsp_engine *engine) {
    if (!engine_is_prepared(engine)) return 0;
    return atomic_load_explicit(&engine->hot_ack_token, memory_order_acquire);
}

uint32_t dsp_engine_get_preamp_ack_token(const dsp_engine *engine) {
    if (!engine_is_prepared(engine)) return 0;
    return atomic_load_explicit(&engine->hot_ack_token, memory_order_acquire);
}

uint32_t dsp_engine_get_local_peq_ack_token(const dsp_engine *engine) {
    if (!engine_is_prepared(engine)) return 0;
    return atomic_load_explicit(&engine->local_peq_ack_token, memory_order_acquire);
}

uint32_t dsp_engine_get_local_peq_rejected_token(const dsp_engine *engine) {
    if (!engine_is_prepared(engine)) return 0;
    return atomic_load_explicit(&engine->local_peq_rejected_token, memory_order_acquire);
}

static int local_peq_update_context_is_current(const dsp_engine *engine, const dsp_engine_local_peq_update *update) {
    return update->media_role == 1 && update->hot_context_token != 0 &&
        update->hot_context_token == atomic_load_explicit(&engine->hot_context_token, memory_order_acquire) &&
        update->editor_owner_epoch == atomic_load_explicit(&engine->hot_editor_owner_epoch, memory_order_acquire) &&
        update->renderer_owner_token == atomic_load_explicit(&engine->hot_renderer_owner_token, memory_order_acquire) &&
        update->pcm_format_epoch == atomic_load_explicit(&engine->hot_pcm_format_epoch, memory_order_acquire);
}

static void begin_pending_local_peq_update(dsp_engine *engine) {
    if (dsp_graph_local_peq_transition_active(&engine->graph)) return;
    uint32_t read_index = atomic_load_explicit(&engine->local_peq_read_index, memory_order_relaxed);
    const uint32_t write_index = atomic_load_explicit(&engine->local_peq_write_index, memory_order_acquire);
    if (read_index == write_index) return;

    const dsp_engine_local_peq_update *update =
        &engine->local_peq_updates[read_index % DSP_LOCAL_PEQ_QUEUE_CAPACITY];
    const uint64_t latest_control_generation = atomic_load_explicit(
        &engine->hot_last_generation, memory_order_acquire);
    if (update->generation == latest_control_generation && local_peq_update_context_is_current(engine, update)) {
        if (dsp_graph_begin_local_peq(
                &engine->graph,
                update->peq,
                update->peq_count,
                update->history_epoch_sequence,
                update->history_end_sequence)) {
            engine->active_local_peq_ack_token = update->generation_token;
            engine->active_local_peq_context_token = update->hot_context_token;
            engine->active_local_peq_generation = update->generation;
        } else {
            atomic_store_explicit(
                &engine->local_peq_rejected_token, update->generation_token, memory_order_release);
        }
    } else {
        atomic_store_explicit(
            &engine->local_peq_rejected_token, update->generation_token, memory_order_release);
    }
    read_index++;
    atomic_store_explicit(&engine->local_peq_read_index, read_index, memory_order_release);
}

static void cancel_superseded_local_peq_update(dsp_engine *engine) {
    if (engine->active_local_peq_ack_token == 0) return;
    const uint64_t latest_control_generation = atomic_load_explicit(
        &engine->hot_last_generation, memory_order_acquire);
    if (engine->active_local_peq_generation >= latest_control_generation) return;
    dsp_graph_begin_cancel_local_peq(&engine->graph);
    atomic_store_explicit(
        &engine->local_peq_rejected_token, engine->active_local_peq_ack_token, memory_order_release);
    engine->active_local_peq_ack_token = 0;
    engine->active_local_peq_context_token = 0;
    engine->active_local_peq_generation = 0;
}

static void cancel_requested_local_peq_update(dsp_engine *engine) {
    const uint32_t generation_token = atomic_exchange_explicit(
        &engine->local_peq_cancel_token, 0, memory_order_acquire);
    if (generation_token == 0) return;

    if (engine->active_local_peq_ack_token == generation_token) {
        dsp_graph_begin_cancel_local_peq(&engine->graph);
        engine->active_local_peq_ack_token = 0;
        engine->active_local_peq_context_token = 0;
        engine->active_local_peq_generation = 0;
        atomic_store_explicit(&engine->local_peq_rejected_token, generation_token, memory_order_release);
    }

    uint32_t read_index = atomic_load_explicit(&engine->local_peq_read_index, memory_order_relaxed);
    const uint32_t write_index = atomic_load_explicit(&engine->local_peq_write_index, memory_order_acquire);
    if (read_index != write_index) {
        const dsp_engine_local_peq_update *queued =
            &engine->local_peq_updates[read_index % DSP_LOCAL_PEQ_QUEUE_CAPACITY];
        if (queued->generation_token == generation_token) {
            atomic_store_explicit(&engine->local_peq_rejected_token, generation_token, memory_order_release);
            atomic_store_explicit(&engine->local_peq_read_index, read_index + 1, memory_order_release);
        }
    }
}

dsp_status dsp_engine_process(
    dsp_engine *engine,
    const float *input,
    size_t input_capacity_samples,
    float *output,
    size_t output_capacity_samples,
    int frames,
    int channels) {
    if (!engine_is_prepared(engine)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    if (input == NULL || output == NULL || frames <= 0 || channels < 1 || channels > 2 ||
        frames > engine->max_frames || channels != engine->channels) {
        saturating_add_counter(&engine->native_error_count, 1);
        return DSP_STATUS_INVALID_ARGUMENT;
    }

    const size_t sample_count = (size_t)frames * (size_t)channels;
    if (sample_count / (size_t)channels != (size_t)frames ||
        input_capacity_samples < sample_count || output_capacity_samples < sample_count) {
        saturating_add_counter(&engine->native_error_count, 1);
        return DSP_STATUS_INVALID_BUFFER;
    }

    uint64_t non_finite_input = 0;
    uint64_t non_finite_output = 0;
    if (atomic_exchange_explicit(&engine->reset_requested, 0, memory_order_acquire) != 0) {
        if (engine->active_local_peq_ack_token != 0) {
            atomic_store_explicit(
                &engine->local_peq_rejected_token, engine->active_local_peq_ack_token, memory_order_release);
        }
        engine->active_local_peq_ack_token = 0;
        engine->active_local_peq_context_token = 0;
        engine->active_local_peq_generation = 0;
        dsp_gain_cancel_ramp(&engine->graph.gain);
        engine->active_hot_ack_token = 0;
        engine->active_hot_context_token = 0;
        engine->active_hot_ack_after_ramp = 0;
        dsp_graph_reset(&engine->graph);
        const uint32_t local_write_index = atomic_load_explicit(
            &engine->local_peq_write_index, memory_order_acquire);
        uint32_t local_read_index = atomic_load_explicit(
            &engine->local_peq_read_index, memory_order_relaxed);
        if (local_read_index != local_write_index) {
            const dsp_engine_local_peq_update *queued =
                &engine->local_peq_updates[local_read_index % DSP_LOCAL_PEQ_QUEUE_CAPACITY];
            atomic_store_explicit(
                &engine->local_peq_rejected_token, queued->generation_token, memory_order_release);
        }
        atomic_store_explicit(&engine->local_peq_read_index, local_write_index, memory_order_release);
    }

    if (engine->active_local_peq_ack_token != 0 && engine->active_local_peq_context_token !=
        atomic_load_explicit(&engine->hot_context_token, memory_order_acquire)) {
        dsp_graph_cancel_local_peq(&engine->graph);
        engine->active_local_peq_ack_token = 0;
        engine->active_local_peq_context_token = 0;
        engine->active_local_peq_generation = 0;
    }

    if (engine->active_hot_ack_token != 0 && engine->active_hot_context_token !=
        atomic_load_explicit(&engine->hot_context_token, memory_order_acquire)) {
        dsp_gain_cancel_ramp(&engine->graph.gain);
        engine->active_hot_ack_token = 0;
        engine->active_hot_context_token = 0;
        engine->active_hot_ack_after_ramp = 0;
    }

    cancel_superseded_local_peq_update(engine);
    cancel_requested_local_peq_update(engine);

    uint32_t hot_read_index = atomic_load_explicit(&engine->hot_read_index, memory_order_relaxed);
    const uint32_t hot_write_index = atomic_load_explicit(&engine->hot_write_index, memory_order_acquire);
    uint32_t processed_hot_token = 0;
    const uint64_t latest_control_generation = atomic_load_explicit(
        &engine->hot_last_generation, memory_order_acquire);
    while (hot_read_index != hot_write_index) {
        const dsp_engine_hot_update *update =
            &engine->hot_updates[hot_read_index % DSP_HOT_UPDATE_QUEUE_CAPACITY];
        const int owner_is_current = update->media_role == 1 && update->hot_context_token != 0 &&
            update->hot_context_token == atomic_load_explicit(&engine->hot_context_token, memory_order_acquire);
        if (!owner_is_current) {
            hot_read_index++;
            continue;
        }
        const int superseded_preamp_for_latest_studio = update->payload_kind == DSP_ENGINE_HOT_KIND_PREAMP &&
            update->generation < latest_control_generation &&
            (uint32_t)(hot_write_index - hot_read_index) > 1 &&
            engine->hot_updates[(hot_read_index + 1) % DSP_HOT_UPDATE_QUEUE_CAPACITY].payload_kind ==
                DSP_ENGINE_HOT_KIND_STUDIO &&
            engine->hot_updates[(hot_read_index + 1) % DSP_HOT_UPDATE_QUEUE_CAPACITY].generation ==
                latest_control_generation;
        if (update->generation != latest_control_generation && !superseded_preamp_for_latest_studio) {
            hot_read_index++;
            continue;
        }
        if (update->payload_kind == DSP_ENGINE_HOT_KIND_PREAMP) {
            if (dsp_graph_apply_preamp_hot(&engine->graph, update->gain_db, update->gain_linear)) {
                engine->active_hot_ack_token = update->generation_token;
                engine->active_hot_context_token = update->hot_context_token;
                engine->active_hot_ack_after_ramp = 1;
            } else {
                saturating_add_counter(&engine->native_error_count, 1);
            }
            hot_read_index++;
            continue;
        }
        const int studio_hot_result = dsp_graph_apply_studio_hot(
                &engine->graph,
                update->gain_db,
                &update->macro,
                &update->smart_loudness,
                &update->dynamic_bass);
        if (studio_hot_result == DSP_GRAPH_HOT_DEFERRED) break;
        if (studio_hot_result == DSP_GRAPH_HOT_APPLIED) {
            if (engine->graph.gain.ramp_remaining > 0) {
                engine->active_hot_ack_token = update->generation_token;
                engine->active_hot_context_token = update->hot_context_token;
                engine->active_hot_ack_after_ramp = 1;
            } else {
                processed_hot_token = update->generation_token;
            }
        } else {
            saturating_add_counter(&engine->native_error_count, 1);
        }
        hot_read_index++;
    }
    atomic_store_explicit(&engine->hot_read_index, hot_read_index, memory_order_release);

    begin_pending_local_peq_update(engine);

    dsp_graph_process(
        &engine->graph,
        input,
        output,
        (size_t)frames,
        (size_t)channels,
        &non_finite_input,
        &non_finite_output);
    if (engine->active_local_peq_ack_token != 0 &&
        !dsp_graph_local_peq_transition_active(&engine->graph)) {
        if (engine->active_local_peq_generation == atomic_load_explicit(
                &engine->hot_last_generation, memory_order_acquire)) {
            atomic_store_explicit(
                &engine->local_peq_ack_token, engine->active_local_peq_ack_token, memory_order_release);
        } else {
            atomic_store_explicit(
                &engine->local_peq_rejected_token, engine->active_local_peq_ack_token, memory_order_release);
        }
        engine->active_local_peq_ack_token = 0;
        engine->active_local_peq_context_token = 0;
        engine->active_local_peq_generation = 0;
    }
    if (processed_hot_token != 0) {
        atomic_store_explicit(&engine->hot_ack_token, processed_hot_token, memory_order_release);
    }
    if (engine->active_hot_ack_token != 0 && engine->active_hot_ack_after_ramp &&
        engine->graph.gain.ramp_remaining == 0 && engine->active_hot_context_token == atomic_load_explicit(
            &engine->hot_context_token, memory_order_acquire)) {
        if (engine->active_hot_ack_token == hot_generation_token(atomic_load_explicit(
                &engine->hot_last_generation, memory_order_acquire))) {
            atomic_store_explicit(&engine->hot_ack_token, engine->active_hot_ack_token, memory_order_release);
        }
        engine->active_hot_ack_token = 0;
        engine->active_hot_context_token = 0;
        engine->active_hot_ack_after_ramp = 0;
    }
    saturating_add_counter(&engine->processed_frames, (uint32_t)frames);
    saturating_add_counter(&engine->processed_blocks, 1);
    saturating_add_counter(
        &engine->non_finite_input_samples,
        non_finite_input > UINT32_MAX ? UINT32_MAX : (uint32_t)non_finite_input);
    saturating_add_counter(
        &engine->non_finite_output_samples,
        non_finite_output > UINT32_MAX ? UINT32_MAX : (uint32_t)non_finite_output);

    float input_peak[2];
    float output_peak[2];
    double input_rms[2];
    double output_rms[2];
    dsp_meter_snapshot(&engine->graph.input_meter, input_peak, input_rms);
    dsp_meter_snapshot(&engine->graph.output_meter, output_peak, output_rms);
    const float metrics[8] = {
        input_peak[0], input_peak[1], (float)input_rms[0], (float)input_rms[1],
        output_peak[0], output_peak[1], (float)output_rms[0], (float)output_rms[1],
    };
    for (size_t index = 0; index < 8; index++) {
        atomic_store_explicit(
            &engine->diagnostic_values[index],
            float_to_bits(metrics[index]),
            memory_order_relaxed);
    }
    return DSP_STATUS_OK;
}

#ifdef DSP_ENGINE_TESTING
void dsp_engine_test_set_processed_frames(dsp_engine *engine, uint32_t value) {
    if (engine_is_prepared(engine)) {
        atomic_store_explicit(&engine->processed_frames, value, memory_order_relaxed);
    }
}

float dsp_engine_test_get_compressor_envelope(const dsp_engine *engine) {
    return engine_is_prepared(engine) ? engine->graph.dynamics.compressor_envelope : 0.0f;
}
#endif

dsp_status dsp_engine_reset(dsp_engine *engine) {
    if (!engine_is_prepared(engine)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    atomic_store_explicit(&engine->reset_requested, 1, memory_order_release);
    atomic_store_explicit(&engine->processed_frames, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->processed_blocks, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->non_finite_input_samples, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->non_finite_output_samples, 0, memory_order_relaxed);
    atomic_store_explicit(&engine->native_error_count, 0, memory_order_relaxed);
    for (size_t index = 0; index < 8; index++) {
        atomic_store_explicit(&engine->diagnostic_values[index], float_to_bits(0.0f), memory_order_relaxed);
    }
    return DSP_STATUS_OK;
}

int dsp_engine_get_latency_frames(const dsp_engine *engine) {
    return engine_is_prepared(engine)
        ? dsp_convolver_latency_frames(&engine->graph.convolver) +
            dsp_harmonic_latency_frames(&engine->graph.harmonic)
        : -1;
}

double dsp_engine_get_interchannel_relative_delay_frames(const dsp_engine *engine) {
    return engine_is_prepared(engine)
        ? dsp_spatial_relative_delay_frames(&engine->graph.spatial)
        : -1.0;
}

dsp_status dsp_engine_get_diagnostics(const dsp_engine *engine, dsp_engine_diagnostics *diagnostics) {
    if (!engine_is_prepared(engine)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    if (diagnostics == NULL) {
        return DSP_STATUS_INVALID_ARGUMENT;
    }

    diagnostics->input_peak[0] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[0], memory_order_relaxed));
    diagnostics->input_peak[1] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[1], memory_order_relaxed));
    diagnostics->input_rms[0] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[2], memory_order_relaxed));
    diagnostics->input_rms[1] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[3], memory_order_relaxed));
    diagnostics->output_peak[0] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[4], memory_order_relaxed));
    diagnostics->output_peak[1] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[5], memory_order_relaxed));
    diagnostics->output_rms[0] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[6], memory_order_relaxed));
    diagnostics->output_rms[1] = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[7], memory_order_relaxed));
    diagnostics->processed_frames = atomic_load_explicit(&engine->processed_frames, memory_order_relaxed);
    diagnostics->processed_blocks = atomic_load_explicit(&engine->processed_blocks, memory_order_relaxed);
    diagnostics->non_finite_input_samples =
        atomic_load_explicit(&engine->non_finite_input_samples, memory_order_relaxed);
    diagnostics->non_finite_output_samples =
        atomic_load_explicit(&engine->non_finite_output_samples, memory_order_relaxed);
    diagnostics->native_error_count = atomic_load_explicit(&engine->native_error_count, memory_order_relaxed);
    return DSP_STATUS_OK;
}

void dsp_engine_destroy(dsp_engine *engine) {
    if (engine == NULL || engine->magic != DSP_ENGINE_MAGIC) {
        return;
    }
    engine->magic = 0;
    dsp_graph_close(&engine->graph);
    free(engine);
}
