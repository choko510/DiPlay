#ifndef XCERTPLAY_DSP_GAIN_H
#define XCERTPLAY_DSP_GAIN_H

typedef struct {
    float linear;
    double decibels;
} dsp_gain;

int dsp_gain_set_db(dsp_gain *gain, double decibels);
float dsp_gain_apply(const dsp_gain *gain, float sample);

#endif
