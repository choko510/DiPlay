#ifndef XCERTPLAY_DSP_GRAPH_H
#define XCERTPLAY_DSP_GRAPH_H

#include "dsp_gain.h"
#include "dsp_meter.h"
#include "dsp_biquad.h"
#include "dsp_dynamics.h"
#include "dsp_spatial.h"
#include "dsp_convolver.h"

#include <stddef.h>
#include <stdint.h>

typedef struct {
    dsp_gain gain;
    dsp_biquad peq[DSP_BIQUAD_MAX_BANDS];
    size_t peq_count;
    dsp_dynamics dynamics;
    dsp_spatial spatial;
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
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config);
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
