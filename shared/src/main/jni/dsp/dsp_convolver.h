#ifndef XCERTPLAY_DSP_CONVOLVER_H
#define XCERTPLAY_DSP_CONVOLVER_H

#include "kiss_fftr.h"

#include <stddef.h>

#define DSP_CONVOLVER_PARTITION_FRAMES 128
#define DSP_CONVOLVER_MAX_IR_FRAMES 65536

typedef struct {
    int enabled;
    int sample_rate;
    int channels;
    int frames;
    size_t sample_count;
    float wet;
    const float *samples;
} dsp_convolver_config;

typedef struct {
    int active;
    int channels;
    int ir_channels;
    int frame_offset;
    int partition_count;
    int latency_frames;
    size_t spectrum_write_index;
    float wet;
    kiss_fftr_cfg forward_fft;
    kiss_fftr_cfg inverse_fft;
    kiss_fft_cpx *ir_spectra;
    kiss_fft_cpx *input_spectra;
    kiss_fft_cpx *frequency_sum;
    float *fft_input;
    float *fft_output;
    float *input_blocks;
    float *ready_output;
    float *dry_delay;
    float *overlap;
} dsp_convolver;

int dsp_convolver_prepare(
    dsp_convolver *convolver,
    int sample_rate,
    int channels,
    const dsp_convolver_config *config);
void dsp_convolver_reset(dsp_convolver *convolver);
void dsp_convolver_close(dsp_convolver *convolver);
void dsp_convolver_process_frame(dsp_convolver *convolver, float *left, float *right, int channels);
void dsp_convolver_flush_denormals(dsp_convolver *convolver);
int dsp_convolver_latency_frames(const dsp_convolver *convolver);

#endif
