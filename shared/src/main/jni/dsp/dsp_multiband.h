#ifndef XCERTPLAY_DSP_MULTIBAND_H
#define XCERTPLAY_DSP_MULTIBAND_H

#include "dsp_biquad.h"
#include "dsp_dynamics.h"

#define DSP_MULTIBAND_COUNT 3
#define DSP_MULTIBAND_FILTER_STAGES 2
#define DSP_MULTIBAND_VALUES_COUNT 24

typedef struct {
    int enabled;
    double threshold_db;
    double ratio;
    double attack_ms;
    double release_ms;
    double knee_db;
    double makeup_db;
} dsp_multiband_compressor_config;

typedef struct {
    int enabled;
    double low_mid_crossover_hz;
    double mid_high_crossover_hz;
    dsp_multiband_compressor_config bands[DSP_MULTIBAND_COUNT];
} dsp_multiband_config;

typedef struct {
    dsp_multiband_config config;
    dsp_biquad low_pass[DSP_MULTIBAND_FILTER_STAGES];
    dsp_biquad mid_high_pass[DSP_MULTIBAND_FILTER_STAGES];
    dsp_biquad mid_low_pass[DSP_MULTIBAND_FILTER_STAGES];
    dsp_biquad high_pass[DSP_MULTIBAND_FILTER_STAGES];
    dsp_dynamics compressors[DSP_MULTIBAND_COUNT];
} dsp_multiband;

int dsp_multiband_prepare(dsp_multiband *multiband, int sample_rate, const dsp_multiband_config *config);
void dsp_multiband_reset(dsp_multiband *multiband);
void dsp_multiband_process_frame(dsp_multiband *multiband, float *left, float *right, int channels);
void dsp_multiband_flush_denormals(dsp_multiband *multiband);

#endif
