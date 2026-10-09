#ifndef XCERTPLAY_DSP_CABIN_H
#define XCERTPLAY_DSP_CABIN_H

#include "dsp_biquad.h"
#include "dsp_dynamic_eq.h"

#include <stdatomic.h>
#include <stdint.h>

#define DSP_CABIN_MAX_CUTS 5

typedef struct {
    int enabled;
    int static_cut_count;
    int bypassed;
} dsp_cabin_config;

typedef struct {
    int enabled;
    int static_cut_count;
    dsp_biquad static_cuts[DSP_CABIN_MAX_CUTS];
    dsp_dynamic_eq dynamic_eq;
    _Atomic int bypass_target;
    int observed_bypass;
    uint32_t bypass_transition_frames;
    uint32_t bypass_transition_position;
    float bypass_mix;
    float bypass_transition_start;
    float bypass_transition_target;
} dsp_cabin;

int dsp_cabin_prepare(
    dsp_cabin *cabin,
    int sample_rate,
    const dsp_cabin_config *config,
    const double *static_coefficients,
    int static_coefficient_count,
    const dsp_dynamic_eq_config *dynamic_config);
void dsp_cabin_reset(dsp_cabin *cabin);
int dsp_cabin_update_bypass(dsp_cabin *cabin, int bypassed);
void dsp_cabin_process_frame(dsp_cabin *cabin, float *left, float *right, int channels);
void dsp_cabin_flush_denormals(dsp_cabin *cabin);

#endif
