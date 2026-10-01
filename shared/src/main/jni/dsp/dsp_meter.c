#include "dsp_meter.h"

#include <math.h>
#include <stddef.h>

void dsp_meter_reset(dsp_meter *meter) {
    if (meter == NULL) {
        return;
    }
    meter->peak[0] = 0.0f;
    meter->peak[1] = 0.0f;
    meter->sum_squares[0] = 0.0;
    meter->sum_squares[1] = 0.0;
    meter->frames = 0;
}

void dsp_meter_add_frame(dsp_meter *meter, float left, float right) {
    if (meter == NULL) {
        return;
    }

    const float samples[2] = {left, right};
    for (int channel = 0; channel < 2; channel++) {
        const float magnitude = fabsf(samples[channel]);
        if (magnitude > meter->peak[channel]) {
            meter->peak[channel] = magnitude;
        }
        meter->sum_squares[channel] += (double)samples[channel] * (double)samples[channel];
    }
    meter->frames++;
}

void dsp_meter_snapshot(const dsp_meter *meter, float peak[2], double rms[2]) {
    if (meter == NULL || peak == NULL || rms == NULL) {
        return;
    }

    for (int channel = 0; channel < 2; channel++) {
        peak[channel] = meter->peak[channel];
        rms[channel] = meter->frames == 0
            ? 0.0
            : sqrt(meter->sum_squares[channel] / (double)meter->frames);
    }
}
