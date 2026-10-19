#ifndef XCERTPLAY_DSP_SPATIAL_H
#define XCERTPLAY_DSP_SPATIAL_H

#include "dsp_biquad.h"

#include <stddef.h>

#define DSP_SPATIAL_VALUES_COUNT 10
#define DSP_SEAT_FOCUS_MAX_DELAY_MS 5.0
#define DSP_SEAT_FOCUS_MAX_DELAY_FRAMES 960
#define DSP_SEAT_FOCUS_MIN_TRIM_LINEAR 0.5011872336272722
#define DSP_SEAT_FOCUS_MAX_TRIM_LINEAR 1.9952623149688795

typedef struct {
    double width;
    int mono_bass_enabled;
    int mono_bass_cutoff_hz;
    int seat_focus_enabled;
    double seat_left_delay_frames;
    double seat_right_delay_frames;
    double seat_left_trim_linear;
    double seat_right_trim_linear;
    int seat_left_polarity_inverted;
    int seat_right_polarity_inverted;
} dsp_spatial_config;

typedef struct {
    double width;
    int bass_enabled;
    int mono_bass_enabled;
    int seat_focus_enabled;
    int seat_latency_frames;
    int seat_left_delay_whole_frames;
    int seat_right_delay_whole_frames;
    float seat_left_delay_fraction;
    float seat_right_delay_fraction;
    float seat_left_gain;
    float seat_right_gain;
    float *seat_delay_lines;
    size_t seat_delay_capacity;
    int seat_write_index;
    int seat_written_frames;
    dsp_biquad bass;
    dsp_biquad mono_bass;
} dsp_spatial;

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    int sample_rate,
    int channels,
    const dsp_spatial_config *config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count);
void dsp_spatial_reset(dsp_spatial *spatial);
void dsp_spatial_close(dsp_spatial *spatial);
int dsp_spatial_latency_frames(const dsp_spatial *spatial);
void dsp_spatial_process_seat(dsp_spatial *spatial, float *left, float *right, int channels);
float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel);
void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels);
void dsp_spatial_flush_denormals(dsp_spatial *spatial);

#endif
