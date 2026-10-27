#include "dsp_gain.h"

#include <math.h>
#include <stddef.h>

int dsp_gain_db_linear_pair_is_valid(double decibels, double linear) {
    if (!isfinite(decibels) || decibels < -120.0 || decibels > -6.0 ||
        !isfinite(linear) || linear < 1.0e-6 || linear > 0.50118724) return 0;
    const double expected = pow(10.0, decibels / 20.0);
    const double tolerance = fmax(1.0e-12, expected * 1.0e-6);
    return isfinite(expected) && fabs(linear - expected) <= tolerance;
}

int dsp_gain_set_db(dsp_gain *gain, double decibels) {
    if (gain == NULL || !isfinite(decibels) || decibels < -700.0 || decibels > 24.0) {
        return 0;
    }

    const double linear = pow(10.0, decibels / 20.0);
    if (!isfinite(linear) || linear <= 0.0) {
        return 0;
    }

    gain->linear = (float)linear;
    gain->decibels = decibels;
    gain->ramp_step = 0.0f;
    gain->ramp_remaining = 0;
    return 1;
}

int dsp_gain_set_linear_ramped(dsp_gain *gain, float linear, double decibels, uint32_t ramp_samples) {
    if (gain == NULL || !isfinite(linear) || linear <= 0.0f || !isfinite(decibels) ||
        decibels < -700.0 || decibels > 24.0) return 0;
    if (ramp_samples == 0) {
        gain->linear = linear;
        gain->decibels = decibels;
        gain->ramp_step = 0.0f;
        gain->ramp_remaining = 0;
        return 1;
    }
    gain->ramp_step = (linear - gain->linear) / (float)ramp_samples;
    gain->ramp_remaining = ramp_samples;
    gain->decibels = decibels;
    return 1;
}

float dsp_gain_apply(const dsp_gain *gain, float sample) {
    return gain == NULL ? 0.0f : sample * gain->linear;
}

void dsp_gain_advance(dsp_gain *gain) {
    if (gain == NULL) return;
    if (gain->ramp_remaining > 0) {
        gain->linear += gain->ramp_step;
        gain->ramp_remaining--;
        if (gain->ramp_remaining == 0) gain->ramp_step = 0.0f;
    }
}

void dsp_gain_cancel_ramp(dsp_gain *gain) {
    if (gain == NULL) return;
    gain->ramp_step = 0.0f;
    gain->ramp_remaining = 0;
}
