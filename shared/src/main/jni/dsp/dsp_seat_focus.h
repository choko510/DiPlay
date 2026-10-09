#ifndef XCERTPLAY_DSP_SEAT_FOCUS_H
#define XCERTPLAY_DSP_SEAT_FOCUS_H

#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>

#define DSP_SEAT_FOCUS_INTERPOLATION_TAPS 4U
#define DSP_SEAT_FOCUS_LAGRANGE_PHASES 1024U

typedef struct {
    int enabled;
    int bypassed;
    double left_delay_ms;
    double right_delay_ms;
    double left_trim_db;
    double right_trim_db;
    int invert_left;
    int invert_right;
} dsp_seat_focus_config;

typedef struct {
    int active;
    int sample_rate;
    int latency_frames;
    double prepared_trim_headroom_db;
    size_t capacity_frames;
    size_t write_position;
    float *delay_ring;
    float *lagrange_table;
    _Atomic uint32_t target_generation;
    _Atomic uint32_t target_delay_samples_bits[2];
    _Atomic uint32_t target_gain_bits[2];
    uint32_t observed_generation;
    uint32_t transition_position;
    uint32_t transition_frames;
    float current_delay_samples[2];
    float transition_start_delay_samples[2];
    float target_delay_samples[2];
    float current_gain[2];
    float transition_start_gain[2];
    float target_gain[2];
} dsp_seat_focus;

int dsp_seat_focus_validate_config(const dsp_seat_focus_config *config);
int dsp_seat_focus_prepare(
    dsp_seat_focus *seat_focus,
    int sample_rate,
    int channels,
    const dsp_seat_focus_config *config);
int dsp_seat_focus_update(dsp_seat_focus *seat_focus, const dsp_seat_focus_config *config);
void dsp_seat_focus_reset(dsp_seat_focus *seat_focus);
void dsp_seat_focus_close(dsp_seat_focus *seat_focus);
void dsp_seat_focus_process_frame(dsp_seat_focus *seat_focus, float *left, float *right, int channels);
int dsp_seat_focus_latency_frames(const dsp_seat_focus *seat_focus);

#endif
