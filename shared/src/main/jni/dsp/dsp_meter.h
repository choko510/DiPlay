#ifndef XCERTPLAY_DSP_METER_H
#define XCERTPLAY_DSP_METER_H

#include <stdint.h>

typedef struct {
    float peak[2];
    double sum_squares[2];
    uint64_t frames;
} dsp_meter;

void dsp_meter_reset(dsp_meter *meter);
void dsp_meter_add_frame(dsp_meter *meter, float left, float right);
void dsp_meter_snapshot(const dsp_meter *meter, float peak[2], double rms[2]);

#endif
