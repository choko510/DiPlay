#ifndef XCERTPLAY_DSP_SPATIAL_H
#define XCERTPLAY_DSP_SPATIAL_H

#include "dsp_biquad.h"

#define DSP_SPATIAL_VALUES_COUNT 10
#define DSP_SPATIAL_NEAR_CENTER 0
#define DSP_SPATIAL_NEAR_LEFT 1
#define DSP_SPATIAL_NEAR_RIGHT 2
#define DSP_SPATIAL_SOUND_SPEED_CM_PER_SECOND 34300.0
#define DSP_SPATIAL_MAX_DISTANCE_DIFFERENCE_CM 171.5
#define DSP_SPATIAL_MAX_DELAY_FRAMES 960
#define DSP_SPATIAL_DELAY_LINE_FRAMES (DSP_SPATIAL_MAX_DELAY_FRAMES + 2)

typedef struct {
    double width;
    int mono_bass_enabled;
    int mono_bass_cutoff_hz;
    int seat_enabled;
    int seat_near_speaker;
    double seat_distance_difference_cm;
    double seat_left_trim_db;
    double seat_right_trim_db;
    int seat_invert_left;
    int seat_invert_right;
} dsp_spatial_config;

typedef struct {
    float samples[DSP_SPATIAL_DELAY_LINE_FRAMES];
    int write_index;
    int integer_delay_frames;
    float fractional_delay;
} dsp_spatial_delay_line;

typedef struct {
    double width;
    int bass_enabled;
    int mono_bass_enabled;
    int seat_enabled;
    int seat_invert_left;
    int seat_invert_right;
    int seat_latency_frames;
    float seat_left_gain;
    float seat_right_gain;
    dsp_spatial_delay_line seat_left_delay;
    dsp_spatial_delay_line seat_right_delay;
    dsp_biquad bass;
    dsp_biquad mono_bass;
} dsp_spatial;

int dsp_spatial_prepare(
    dsp_spatial *spatial,
    const dsp_spatial_config *config,
    int sample_rate,
    int channels,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count);
void dsp_spatial_reset(dsp_spatial *spatial);
float dsp_spatial_process_bass(dsp_spatial *spatial, float sample, int channel);
void dsp_spatial_process_seat(dsp_spatial *spatial, float *left, float *right, int channels);
int dsp_spatial_latency_frames(const dsp_spatial *spatial);
void dsp_spatial_process_stereo(dsp_spatial *spatial, float *left, float *right, int channels);
void dsp_spatial_flush_denormals(dsp_spatial *spatial);

#endif
