#ifndef XCERTPLAY_DSP_ENGINE_H
#define XCERTPLAY_DSP_ENGINE_H

#include <stddef.h>
#include <stdint.h>
#include "dsp_biquad.h"
#include "dsp_dynamics.h"
#include "dsp_multiband.h"
#include "dsp_dynamic_eq.h"
#include "dsp_spatial.h"
#include "dsp_harmonic.h"

#define DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND 9
#include "dsp_convolver.h"
#include "dsp_macro.h"
#include "dsp_smart_loudness.h"
#include "dsp_dynamic_bass.h"

typedef struct dsp_engine dsp_engine;

typedef enum {
    DSP_STATUS_OK = 0,
    DSP_STATUS_INVALID_HANDLE = 1,
    DSP_STATUS_INVALID_ARGUMENT = 2,
    DSP_STATUS_INVALID_BUFFER = 3,
    DSP_STATUS_NOT_PREPARED = 4,
    DSP_STATUS_QUEUE_FULL = 5,
    DSP_STATUS_STALE_UPDATE = 7
} dsp_status;

typedef struct {
    double input_peak[2];
    double input_rms[2];
    double output_peak[2];
    double output_rms[2];
    uint64_t processed_frames;
    uint64_t processed_blocks;
    uint64_t non_finite_input_samples;
    uint64_t non_finite_output_samples;
    uint64_t native_error_count;
} dsp_engine_diagnostics;

typedef struct {
    uint32_t generation_token;
    uint64_t generation;
    uint64_t editor_owner_epoch;
    uint64_t renderer_owner_token;
    uint64_t pcm_format_epoch;
    uint32_t hot_context_token;
    int media_role;
    double gain_db;
    dsp_macro_config macro;
    dsp_smart_loudness_config smart_loudness;
    dsp_dynamic_bass_config dynamic_bass;
} dsp_engine_hot_update;

typedef struct {
    uint32_t version;
    uint32_t generation_token;
    uint64_t generation;
    uint64_t editor_owner_epoch;
    uint64_t renderer_owner_token;
    uint64_t pcm_format_epoch;
    uint32_t hot_context_token;
    int media_role;
    double gain_db;
    double gain_linear;
} dsp_engine_preamp_update;

typedef struct {
    uint32_t generation_token;
    uint64_t generation;
    uint64_t editor_owner_epoch;
    uint64_t renderer_owner_token;
    uint64_t pcm_format_epoch;
    uint64_t history_epoch_sequence;
    uint64_t history_end_sequence;
    uint32_t hot_context_token;
    int media_role;
    size_t peq_count;
    dsp_biquad peq[DSP_GRAPH_EQ_FILTER_CAPACITY];
} dsp_engine_local_peq_update;

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
    const dsp_harmonic_config *harmonic_config);
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
    const dsp_dynamic_bass_config *dynamic_bass_config);
int dsp_engine_enqueue_hot_update(dsp_engine *engine, const dsp_engine_hot_update *update);
int dsp_engine_enqueue_preamp_update(dsp_engine *engine, const dsp_engine_preamp_update *update);
int dsp_engine_prepare_local_peq_update(
    dsp_engine *engine,
    const double *peq_coefficients,
    size_t peq_count,
    double *prepared_values,
    size_t prepared_values_count,
    unsigned long long *history_epoch_sequence,
    unsigned long long *history_end_sequence);
int dsp_engine_enqueue_local_peq_update(dsp_engine *engine, const dsp_engine_local_peq_update *update);
int dsp_engine_cancel_local_peq_update(dsp_engine *engine, uint32_t generation_token);
int dsp_engine_bind_hot_update_context(
    dsp_engine *engine,
    int sample_rate,
    int channels,
    uint64_t editor_owner_epoch,
    uint64_t renderer_owner_token,
    uint64_t pcm_format_epoch,
    uint32_t hot_context_token,
    int media_role);
uint32_t dsp_engine_get_hot_ack_token(const dsp_engine *engine);
uint32_t dsp_engine_get_preamp_ack_token(const dsp_engine *engine);
uint32_t dsp_engine_get_local_peq_ack_token(const dsp_engine *engine);
uint32_t dsp_engine_get_local_peq_rejected_token(const dsp_engine *engine);
dsp_status dsp_engine_process(
    dsp_engine *engine,
    const float *input,
    size_t input_capacity_samples,
    float *output,
    size_t output_capacity_samples,
    int frames,
    int channels);
dsp_status dsp_engine_reset(dsp_engine *engine);
int dsp_engine_get_latency_frames(const dsp_engine *engine);
double dsp_engine_get_interchannel_relative_delay_frames(const dsp_engine *engine);
dsp_status dsp_engine_get_diagnostics(const dsp_engine *engine, dsp_engine_diagnostics *diagnostics);
void dsp_engine_destroy(dsp_engine *engine);

#ifdef DSP_ENGINE_TESTING
void dsp_engine_test_set_processed_frames(dsp_engine *engine, uint32_t value);
float dsp_engine_test_get_compressor_envelope(const dsp_engine *engine);
#endif

#endif
