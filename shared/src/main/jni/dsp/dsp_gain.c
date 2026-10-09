#include "dsp_gain.h"

#include <math.h>
#include <stddef.h>

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
    return 1;
}

float dsp_gain_apply(const dsp_gain *gain, float sample) {
    return gain == NULL ? 0.0f : sample * gain->linear;
}
