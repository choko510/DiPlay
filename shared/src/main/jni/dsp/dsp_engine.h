#ifndef XCERTPLAY_DSP_ENGINE_H
#define XCERTPLAY_DSP_ENGINE_H

#include <stddef.h>
#include <stdint.h>
#include "dsp_biquad.h"

typedef struct dsp_engine dsp_engine;

typedef enum {
    DSP_STATUS_OK = 0,
    DSP_STATUS_INVALID_HANDLE = 1,
    DSP_STATUS_INVALID_ARGUMENT = 2,
    DSP_STATUS_INVALID_BUFFER = 3,
    DSP_STATUS_NOT_PREPARED = 4
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

dsp_engine *dsp_engine_create(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count);
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
dsp_status dsp_engine_get_diagnostics(const dsp_engine *engine, dsp_engine_diagnostics *diagnostics);
void dsp_engine_destroy(dsp_engine *engine);

#endif
