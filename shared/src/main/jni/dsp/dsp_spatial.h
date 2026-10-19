#ifndef XCERTPLAY_DSP_SPATIAL_H
#define XCERTPLAY_DSP_SPATIAL_H

#include "dsp_biquad.h"
#include "dsp_dynamics.h"

#include <stddef.h>

#define DSP_SPATIAL_VALUES_COUNT 26
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
    int advanced_ms_enabled;
    double advanced_ms_crossover_hz;
    double advanced_ms_low_mid_gain_db;
    double advanced_ms_low_side_gain_db;
    double advanced_ms_low_width;
    double advanced_ms_high_mid_gain_db;
    double advanced_ms_high_side_gain_db;
    double advanced_ms_high_width;
    double advanced_ms_center_focus_db;
    int advanced_ms_side_compressor_enabled;
    double advanced_ms_side_compressor_threshold_db;
    double advanced_ms_side_compressor_ratio;
    double advanced_ms_side_compressor_attack_ms;
    double advanced_ms_side_compressor_release_ms;
    double advanced_ms_side_compressor_knee_db;
    double advanced_ms_side_compressor_makeup_db;
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
    int advanced_ms_enabled;
    int advanced_ms_side_compressor_enabled;
    float advanced_ms_low_mid_gain;
    float advanced_ms_low_side_gain;
    float advanced_ms_high_mid_gain;
    float advanced_ms_high_side_gain;
    float advanced_ms_center_gain;
    dsp_biquad advanced_ms_low_pass[2];
    dsp_biquad advanced_ms_high_pass[2];
    dsp_dynamics advanced_ms_side_compressor;
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
