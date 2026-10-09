#ifndef XCERTPLAY_DSP_GRAPH_H
#define XCERTPLAY_DSP_GRAPH_H

#include "dsp_gain.h"
#include "dsp_meter.h"
#include "dsp_biquad.h"
#include "dsp_dynamics.h"
#include "dsp_multiband.h"
#include "dsp_dynamic_eq.h"
#include "dsp_spatial.h"
#include "dsp_convolver.h"
#include "dsp_seat_focus.h"
#include "dsp_cabin.h"

#include <stddef.h>
#include <stdint.h>

typedef struct {
    dsp_gain gain;
    dsp_biquad peq[DSP_BIQUAD_MAX_BANDS];
    size_t peq_count;
    dsp_dynamics dynamics;
    dsp_multiband multiband;
    dsp_dynamic_eq dynamic_eq;
    dsp_spatial spatial;
    dsp_seat_focus seat_focus;
    dsp_cabin cabin;
    dsp_convolver convolver;
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
    const dsp_seat_focus_config *seat_focus_config,
    const dsp_advanced_ms_config *advanced_ms_config,
    const double *advanced_ms_coefficients,
    size_t advanced_ms_coefficient_count,
    const dsp_cabin_config *cabin_config,
    const double *cabin_static_coefficients,
    int cabin_static_coefficient_count,
    const dsp_dynamic_eq_config *cabin_dynamic_eq_config);
void dsp_graph_reset(dsp_graph *graph);
void dsp_graph_close(dsp_graph *graph);
int dsp_graph_latency_frames(const dsp_graph *graph);
int dsp_graph_update_advanced_ms_monitor_mode(dsp_graph *graph, int monitor_mode);
int dsp_graph_update_cabin_bypass(dsp_graph *graph, int bypassed);
void dsp_graph_process(
    dsp_graph *graph,
    const float *input,
    float *output,
    size_t frames,
    size_t channels,
    uint64_t *non_finite_input,
    uint64_t *non_finite_output);

#endif
