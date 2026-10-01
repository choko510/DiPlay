#ifndef XCERTPLAY_DSP_SPATIAL_H
#define XCERTPLAY_DSP_SPATIAL_H

#include "dsp_biquad.h"

typedef struct {
    double width;
    int mono_bass_enabled;
    int mono_bass_cutoff_hz;
} dsp_spatial_config;

typedef struct {
    double width;
    int bass_enabled;
    int mono_bass_enabled;
    dsp_biquad bass;
    dsp_biquad mono_bass;
} dsp_spatial;

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    const dsp_spatial_config *config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count);
void dsp_spatial_reset(dsp_spatial *spatial);
float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel);
void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels);
void dsp_spatial_flush_denormals(dsp_spatial *spatial);

#endif
