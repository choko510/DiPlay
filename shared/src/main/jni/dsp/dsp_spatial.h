#ifndef XCERTPLAY_DSP_SPATIAL_H
#define XCERTPLAY_DSP_SPATIAL_H

#include "dsp_biquad.h"
#include "dsp_dynamics.h"

#define DSP_SPATIAL_VALUES_COUNT 25
#define DSP_SPATIAL_NEAR_CENTER 0
#define DSP_SPATIAL_NEAR_LEFT 1
#define DSP_SPATIAL_NEAR_RIGHT 2
#define DSP_SPATIAL_SOUND_SPEED_CM_PER_SECOND 34300.0
#define DSP_SPATIAL_MAX_DISTANCE_DIFFERENCE_CM 171.5
#define DSP_SPATIAL_MAX_DELAY_FRAMES 960
#define DSP_SPATIAL_DELAY_LINE_FRAMES (DSP_SPATIAL_MAX_DELAY_FRAMES + 2)

typedef struct {
    int enabled;
    double crossover_hz;
    double center_focus_db;
    double mid_low_gain_db;
    double mid_high_gain_db;
    double side_low_gain_db;
    double side_high_gain_db;
    double low_band_width;
    double high_band_width;
    int side_compressor_enabled;
    double side_compressor_threshold_db;
    double side_compressor_ratio;
    double side_compressor_attack_ms;
    double side_compressor_release_ms;
    double side_compressor_knee_db;
} dsp_advanced_ms_config;

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
    dsp_advanced_ms_config advanced_ms;
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
    int advanced_ms_enabled;
    float mid_low_gain;
    float mid_high_gain;
    float side_low_gain;
    float side_high_gain;
    dsp_biquad ms_low_pass_first[2];
    dsp_biquad ms_low_pass_second[2];
    dsp_biquad ms_high_pass_first[2];
    dsp_biquad ms_high_pass_second[2];
    dsp_dynamics side_compressor;
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
