#ifndef XCERTPLAY_DSP_GRAPH_H
#define XCERTPLAY_DSP_GRAPH_H

#include "dsp_gain.h"
#include "dsp_meter.h"
#include "dsp_biquad.h"

#include <stddef.h>
#include <stdint.h>

typedef struct {
    dsp_gain gain;
    dsp_biquad peq[DSP_BIQUAD_MAX_BANDS];
    size_t peq_count;
    dsp_meter input_meter;
    dsp_meter output_meter;
} dsp_graph;

int dsp_graph_prepare(
    dsp_graph *graph,
    double gain_db,
    const double *peq_coefficients,
    size_t peq_count);
void dsp_graph_reset(dsp_graph *graph);
void dsp_graph_process(
    dsp_graph *graph,
    const float *input,
    float *output,
    size_t frames,
    size_t channels,
    uint64_t *non_finite_input,
    uint64_t *non_finite_output);

#endif
