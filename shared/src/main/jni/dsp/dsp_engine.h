#ifndef XCERTPLAY_DSP_ENGINE_H
#define XCERTPLAY_DSP_ENGINE_H

#include <stddef.h>
#include <stdint.h>
#include "dsp_biquad.h"
#include "dsp_dynamics.h"
#include "dsp_multiband.h"
#include "dsp_dynamic_eq.h"
#include "dsp_spatial.h"
#include "dsp_convolver.h"
#include "dsp_seat_focus.h"
#include "dsp_cabin.h"
#include "dsp_harmonic.h"

typedef struct dsp_engine dsp_engine;

typedef enum {
    DSP_STATUS_OK = 0,
    DSP_STATUS_INVALID_HANDLE = 1,
    DSP_STATUS_INVALID_ARGUMENT = 2,
    DSP_STATUS_INVALID_BUFFER = 3,
    DSP_STATUS_NOT_PREPARED = 4
} dsp_status;

typedef struct {
    double input_peak[2];
    double input_rms[2];
    double output_peak[2];
    double output_rms[2];
    double input_correlation;
    double output_correlation;
    uint64_t processed_frames;
    uint64_t processed_blocks;
    uint64_t non_finite_input_samples;
    uint64_t non_finite_output_samples;
    uint64_t native_error_count;
} dsp_engine_diagnostics;

dsp_engine *dsp_engine_create(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const dsp_dynamic_eq_config *dynamic_eq_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config);
dsp_engine *dsp_engine_create_with_seat_focus(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const dsp_dynamic_eq_config *dynamic_eq_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config,
    const dsp_seat_focus_config *seat_focus_config);
dsp_engine *dsp_engine_create_with_modules(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const dsp_dynamic_eq_config *dynamic_eq_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config,
    const dsp_seat_focus_config *seat_focus_config,
    const dsp_advanced_ms_config *advanced_ms_config,
    const double *advanced_ms_coefficients,
    size_t advanced_ms_coefficient_count,
    const dsp_cabin_config *cabin_config,
    const double *cabin_static_coefficients,
    int cabin_static_coefficient_count,
    const dsp_dynamic_eq_config *cabin_dynamic_eq_config,
    const dsp_harmonic_config *harmonic_config,
    const double *harmonic_filter_coefficients,
    size_t harmonic_filter_coefficient_count);
dsp_status dsp_engine_process(
    dsp_engine *engine,
    const float *input,
    size_t input_capacity_samples,
    float *output,
    size_t output_capacity_samples,
    int frames,
    int channels);
dsp_status dsp_engine_reset(dsp_engine *engine);
dsp_status dsp_engine_update_seat_focus(dsp_engine *engine, const dsp_seat_focus_config *config);
dsp_status dsp_engine_update_advanced_ms_monitor_mode(dsp_engine *engine, int monitor_mode);
dsp_status dsp_engine_update_cabin_bypass(dsp_engine *engine, int bypassed);
dsp_status dsp_engine_update_harmonic_bypass(dsp_engine *engine, int bypassed);
int dsp_engine_get_latency_frames(const dsp_engine *engine);
dsp_status dsp_engine_get_diagnostics(const dsp_engine *engine, dsp_engine_diagnostics *diagnostics);
void dsp_engine_destroy(dsp_engine *engine);

#ifdef DSP_ENGINE_TESTING
void dsp_engine_test_set_processed_frames(dsp_engine *engine, uint32_t value);
#endif

#endif
