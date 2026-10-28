#ifndef XCERTPLAY_DSP_GRAPH_H
#define XCERTPLAY_DSP_GRAPH_H

#include "dsp_gain.h"
#include "dsp_meter.h"
#include "dsp_biquad.h"
#include "dsp_dynamics.h"
#include "dsp_multiband.h"
#include "dsp_dynamic_eq.h"
#include "dsp_spatial.h"
#include "dsp_harmonic.h"
#include "dsp_convolver.h"
#include "dsp_macro.h"
#include "dsp_smart_loudness.h"
#include "dsp_dynamic_bass.h"

#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>

#define DSP_PEQ_WARM_HISTORY_CAPACITY 32768u
#define DSP_PEQ_WARM_AUDIO_CATCH_UP_MAX 512u

#define DSP_GRAPH_HOT_APPLIED 1
#define DSP_GRAPH_HOT_DEFERRED 2

typedef struct {
    _Atomic unsigned long long sequence;
    _Atomic unsigned long long packed_samples;
} dsp_peq_history_entry;

typedef struct {
    dsp_peq_history_entry *entries;
    unsigned long long writer_sequence;
    _Atomic unsigned long long published_sequence;
    _Atomic unsigned long long reset_sequence;
} dsp_peq_input_history;

typedef struct {
    int sample_rate;
    dsp_gain gain;
    dsp_biquad peq[DSP_GRAPH_EQ_FILTER_CAPACITY];
    size_t peq_count;
    dsp_biquad peq_target[DSP_GRAPH_EQ_FILTER_CAPACITY];
    size_t peq_target_count;
    size_t peq_transition_position;
    size_t peq_transition_frames;
    int peq_transition_active;
    int peq_transition_canceling;
    size_t peq_cancel_position;
    size_t peq_cancel_frames;
    float peq_cancel_start_mix;
    dsp_peq_input_history peq_history;
    dsp_dynamics dynamics;
    dsp_multiband multiband;
    dsp_dynamic_eq dynamic_eq;
    dsp_spatial spatial;
    dsp_harmonic harmonic;
    dsp_convolver convolver;
    dsp_macro macro;
    dsp_smart_loudness smart_loudness;
    dsp_dynamic_bass dynamic_bass;
    double pending_studio_gain_db;
    float pending_studio_gain_linear;
    int studio_gain_refund_pending;
    dsp_meter input_meter;
    dsp_meter output_meter;
} dsp_graph;

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
    const dsp_dynamic_bass_config *dynamic_bass_config);
int dsp_graph_apply_studio_hot(
    dsp_graph *graph,
    double gain_db,
    double gain_linear,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config);
int dsp_graph_apply_preamp_hot(dsp_graph *graph, double gain_db, double gain_linear);
int dsp_graph_prepare_local_peq(
    const dsp_graph *graph,
    const double *peq_coefficients,
    size_t peq_count,
    dsp_biquad *prepared_bands,
    unsigned long long *history_epoch_sequence,
    unsigned long long *history_end_sequence);
int dsp_graph_begin_local_peq(
    dsp_graph *graph,
    const dsp_biquad *prepared_bands,
    size_t peq_count,
    unsigned long long history_epoch_sequence,
    unsigned long long history_end_sequence);
int dsp_graph_local_peq_transition_active(const dsp_graph *graph);
int dsp_graph_begin_cancel_local_peq(dsp_graph *graph);
void dsp_graph_cancel_local_peq(dsp_graph *graph);
int dsp_graph_studio_hot_config_is_valid(
    double gain_db,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config);
void dsp_graph_reset(dsp_graph *graph);
void dsp_graph_close(dsp_graph *graph);
void dsp_graph_process(
    dsp_graph *graph,
    const float *input,
    float *output,
    size_t frames,
    size_t channels,
    uint64_t *non_finite_input,
    uint64_t *non_finite_output);

#endif
