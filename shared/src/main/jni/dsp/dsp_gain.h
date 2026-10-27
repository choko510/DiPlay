#ifndef XCERTPLAY_DSP_GAIN_H
#define XCERTPLAY_DSP_GAIN_H

#include <stdint.h>

typedef struct {
    float linear;
    double decibels;
    float ramp_step;
    uint32_t ramp_remaining;
} dsp_gain;

int dsp_gain_set_db(dsp_gain *gain, double decibels);
int dsp_gain_db_linear_pair_is_valid(double decibels, double linear);
int dsp_gain_set_linear_ramped(dsp_gain *gain, float linear, double decibels, uint32_t ramp_samples);
float dsp_gain_apply(const dsp_gain *gain, float sample);
void dsp_gain_advance(dsp_gain *gain);
void dsp_gain_cancel_ramp(dsp_gain *gain);

#endif
