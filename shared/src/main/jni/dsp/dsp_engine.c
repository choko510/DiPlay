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
    _Atomic uint32_t diagnostic_values[8];
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

dsp_engine *dsp_engine_create(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config) {
    if (sample_rate < 8000 || sample_rate > 192000 || channels < 1 || channels > 2 ||
        max_frames < 1 || max_frames > 65536 || !isfinite(gain_db) || peq_band_count < 0 ||
        peq_band_count > DSP_BIQUAD_MAX_BANDS || (peq_band_count > 0 && peq_coefficients == NULL) ||
        dynamics_config == NULL || multiband_config == NULL ||
        bass_coefficient_count < 0 || bass_coefficient_count > 1 ||
        (bass_coefficient_count > 0 && bass_coefficients == NULL) ||
        mono_bass_coefficient_count < 0 || mono_bass_coefficient_count > 1 ||
        (mono_bass_coefficient_count > 0 && mono_bass_coefficients == NULL) || spatial_config == NULL ||
        convolver_config == NULL) {
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
            bass_coefficients,
            bass_coefficient_count,
            mono_bass_coefficients,
            mono_bass_coefficient_count,
            spatial_config,
            convolver_config)) {
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
        atomic_fetch_add_explicit(&engine->native_error_count, 1, memory_order_relaxed);
        return DSP_STATUS_INVALID_ARGUMENT;
    }

    const size_t sample_count = (size_t)frames * (size_t)channels;
    if (sample_count / (size_t)channels != (size_t)frames ||
        input_capacity_samples < sample_count || output_capacity_samples < sample_count) {
        atomic_fetch_add_explicit(&engine->native_error_count, 1, memory_order_relaxed);
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
    atomic_fetch_add_explicit(&engine->processed_frames, (uint32_t)frames, memory_order_relaxed);
    atomic_fetch_add_explicit(&engine->processed_blocks, 1, memory_order_relaxed);
    atomic_fetch_add_explicit(
        &engine->non_finite_input_samples,
        (uint32_t)non_finite_input,
        memory_order_relaxed);
    atomic_fetch_add_explicit(
        &engine->non_finite_output_samples,
        (uint32_t)non_finite_output,
        memory_order_relaxed);

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
    return engine_is_prepared(engine) ? dsp_convolver_latency_frames(&engine->graph.convolver) : -1;
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
