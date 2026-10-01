#include "dsp_biquad.h"

#include <math.h>
#include <stddef.h>

int dsp_biquad_prepare(dsp_biquad *biquad, const double *coefficients) {
    if (biquad == NULL || coefficients == NULL) {
        return 0;
    }
    for (size_t index = 0; index < DSP_BIQUAD_COEFFICIENT_COUNT; index++) {
        if (!isfinite(coefficients[index])) {
            return 0;
        }
    }

    const double a1 = coefficients[3];
    const double a2 = coefficients[4];
    if (fabs(a2) >= 1.0 || 1.0 + a1 + a2 <= 0.0 || 1.0 - a1 + a2 <= 0.0) {
        return 0;
    }

    biquad->b0 = coefficients[0];
    biquad->b1 = coefficients[1];
    biquad->b2 = coefficients[2];
    biquad->a1 = a1;
    biquad->a2 = a2;
    dsp_biquad_reset(biquad);
    return 1;
}

void dsp_biquad_reset(dsp_biquad *biquad) {
    if (biquad == NULL) {
        return;
    }
    for (size_t channel = 0; channel < 2; channel++) {
        biquad->z1[channel] = 0.0f;
        biquad->z2[channel] = 0.0f;
    }
}

float dsp_biquad_process_sample(dsp_biquad *biquad, float sample, int channel) {
    if (biquad == NULL || channel < 0 || channel > 1) {
        return 0.0f;
    }
    const float output = (float)(biquad->b0 * (double)sample + (double)biquad->z1[channel]);
    const float next_z1 = (float)(biquad->b1 * (double)sample - biquad->a1 * (double)output +
        (double)biquad->z2[channel]);
    const float next_z2 = (float)(biquad->b2 * (double)sample - biquad->a2 * (double)output);
    if (!isfinite(output) || !isfinite(next_z1) || !isfinite(next_z2)) {
        biquad->z1[channel] = 0.0f;
        biquad->z2[channel] = 0.0f;
        return 0.0f;
    }
    biquad->z1[channel] = next_z1;
    biquad->z2[channel] = next_z2;
    return output;
}

void dsp_biquad_flush_denormals(dsp_biquad *biquad) {
    if (biquad == NULL) {
        return;
    }
    for (size_t channel = 0; channel < 2; channel++) {
        if (fabsf(biquad->z1[channel]) < 1e-20f) biquad->z1[channel] = 0.0f;
        if (fabsf(biquad->z2[channel]) < 1e-20f) biquad->z2[channel] = 0.0f;
    }
}
