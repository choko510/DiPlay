#include "dsp_engine.h"

#include "dsp_graph.h"

#include <math.h>
#include <stdatomic.h>
#include <string.h>
#include <stdlib.h>

#define DSP_ENGINE_MAGIC UINT32_C(0x44535031)

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "DSP diagnostics require lock-free 32-bit atomics");

struct dsp_engine {
    uint32_t magic;
    int sample_rate;
    int channels;
    int max_frames;
    dsp_graph graph;
    _Atomic uint32_t diagnostic_values[10];
    _Atomic uint32_t processed_frames;
    _Atomic uint32_t processed_blocks;
    _Atomic uint32_t non_finite_input_samples;
    _Atomic uint32_t non_finite_output_samples;
    _Atomic uint32_t native_error_count;
    _Atomic uint32_t reset_requested;
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
    const dsp_convolver_config *convolver_config) {
    const dsp_seat_focus_config disabled_seat_focus = {0};
    return dsp_engine_create_with_seat_focus(
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
        &disabled_seat_focus);
}

dsp_engine *dsp_engine_create_with_seat_focus(
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
    const dsp_seat_focus_config *seat_focus_config) {
    const dsp_advanced_ms_config disabled_advanced_ms = {
        0, 0.0, 0.0, 1.0, 1.0, 1.0, 150.0, 4000.0, 0.0,
        DSP_ADVANCED_MS_MONITOR_NORMAL, 0, 0,
    };
    const double disabled_advanced_ms_coefficients[
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT] = {0.0};
    return dsp_engine_create_with_modules(
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
        seat_focus_config,
        &disabled_advanced_ms,
        disabled_advanced_ms_coefficients,
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT);
}

dsp_engine *dsp_engine_create_with_modules(
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
    const dsp_seat_focus_config *seat_focus_config,
    const dsp_advanced_ms_config *advanced_ms_config,
    const double *advanced_ms_coefficients,
    size_t advanced_ms_coefficient_count) {
    if (sample_rate < 8000 || sample_rate > 192000 || channels < 1 || channels > 2 ||
        max_frames < 1 || max_frames > 65536 || !isfinite(gain_db) || peq_band_count < 0 ||
        peq_band_count > DSP_BIQUAD_MAX_BANDS || (peq_band_count > 0 && peq_coefficients == NULL) ||
        dynamics_config == NULL || multiband_config == NULL || dynamic_eq_config == NULL ||
        bass_coefficient_count < 0 || bass_coefficient_count > 1 ||
        (bass_coefficient_count > 0 && bass_coefficients == NULL) ||
        mono_bass_coefficient_count < 0 || mono_bass_coefficient_count > 1 ||
        (mono_bass_coefficient_count > 0 && mono_bass_coefficients == NULL) || spatial_config == NULL ||
        convolver_config == NULL || !dsp_seat_focus_validate_config(seat_focus_config) ||
        advanced_ms_config == NULL || advanced_ms_coefficients == NULL ||
        advanced_ms_coefficient_count !=
            DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT) {
        return NULL;
    }

    dsp_engine *engine = (dsp_engine *)calloc(1, sizeof(*engine));
    if (engine == NULL) {
        return NULL;
    }
    for (size_t index = 0; index < 10; index++) {
        atomic_init(&engine->diagnostic_values[index], float_to_bits(0.0f));
    }
    atomic_init(&engine->processed_frames, 0);
    atomic_init(&engine->processed_blocks, 0);
    atomic_init(&engine->non_finite_input_samples, 0);
    atomic_init(&engine->non_finite_output_samples, 0);
    atomic_init(&engine->native_error_count, 0);
    atomic_init(&engine->reset_requested, 0);
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
            seat_focus_config,
            advanced_ms_config,
            advanced_ms_coefficients,
            advanced_ms_coefficient_count)) {
        dsp_graph_close(&engine->graph);
        free(engine);
        return NULL;
    }
    engine->magic = DSP_ENGINE_MAGIC;
    return engine;
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
        dsp_graph_reset(&engine->graph);
    }

    dsp_graph_process(
        &engine->graph,
        input,
        output,
        (size_t)frames,
        (size_t)channels,
        &non_finite_input,
        &non_finite_output);
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
    float input_correlation;
    float output_correlation;
    dsp_meter_snapshot(&engine->graph.input_meter, input_peak, input_rms, &input_correlation);
    dsp_meter_snapshot(&engine->graph.output_meter, output_peak, output_rms, &output_correlation);
    const float metrics[10] = {
        input_peak[0], input_peak[1], (float)input_rms[0], (float)input_rms[1],
        output_peak[0], output_peak[1], (float)output_rms[0], (float)output_rms[1],
        input_correlation, output_correlation,
    };
    for (size_t index = 0; index < 10; index++) {
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
    for (size_t index = 0; index < 10; index++) {
        atomic_store_explicit(&engine->diagnostic_values[index], float_to_bits(0.0f), memory_order_relaxed);
    }
    return DSP_STATUS_OK;
}

dsp_status dsp_engine_update_seat_focus(dsp_engine *engine, const dsp_seat_focus_config *config) {
    if (!engine_is_prepared(engine)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    if (!dsp_seat_focus_validate_config(config) || !dsp_seat_focus_update(&engine->graph.seat_focus, config)) {
        saturating_add_counter(&engine->native_error_count, 1);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    return DSP_STATUS_OK;
}

dsp_status dsp_engine_update_advanced_ms_monitor_mode(dsp_engine *engine, int monitor_mode) {
    if (!engine_is_prepared(engine)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    if (!dsp_graph_update_advanced_ms_monitor_mode(&engine->graph, monitor_mode)) {
        saturating_add_counter(&engine->native_error_count, 1);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    return DSP_STATUS_OK;
}

int dsp_engine_get_latency_frames(const dsp_engine *engine) {
    return engine_is_prepared(engine) ? dsp_graph_latency_frames(&engine->graph) : -1;
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
    diagnostics->input_correlation = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[8], memory_order_relaxed));
    diagnostics->output_correlation = (double)bits_to_float(
        atomic_load_explicit(&engine->diagnostic_values[9], memory_order_relaxed));
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
