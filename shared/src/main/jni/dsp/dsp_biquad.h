#ifndef XCERTPLAY_DSP_BIQUAD_H
#define XCERTPLAY_DSP_BIQUAD_H

#define DSP_BIQUAD_MAX_BANDS 15
#define DSP_BIQUAD_COEFFICIENT_COUNT 5

typedef struct {
    double b0;
    double b1;
    double b2;
    double a1;
    double a2;
    float z1[2];
    float z2[2];
} dsp_biquad;

int dsp_biquad_prepare(dsp_biquad *biquad, const double *coefficients);
void dsp_biquad_reset(dsp_biquad *biquad);
float dsp_biquad_process_sample(dsp_biquad *biquad, float sample, int channel);
void dsp_biquad_flush_denormals(dsp_biquad *biquad);

#endif
