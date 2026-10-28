#include "dsp_engine.h"
#include "dsp_dynamic_bass.h"
#include "dsp_graph.h"

#include <float.h>
#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const dsp_dynamics_config bypass_dynamics = {
    0, -20.0, 4.0, 10.0, 100.0, 0.0, 0.0, 0, -1.0, 60.0,
};
static const dsp_multiband_config bypass_multiband = {
    0, 120.0, 2500.0,
    {
        {0, -20.0, 4.0, 10.0, 100.0, 0.0, 0.0},
        {0, -20.0, 4.0, 10.0, 100.0, 0.0, 0.0},
        {0, -20.0, 4.0, 10.0, 100.0, 0.0, 0.0},
    },
};
static const dsp_dynamic_eq_config bypass_dynamic_eq = {
    0,
    {
        {0, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -24.0, 2.0, 10.0, 100.0, 6.0, 6.0},
        {0, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -24.0, 2.0, 10.0, 100.0, 6.0, 6.0},
        {0, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -24.0, 2.0, 10.0, 100.0, 6.0, 6.0},
        {0, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -24.0, 2.0, 10.0, 100.0, 6.0, 6.0},
        {0, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -24.0, 2.0, 10.0, 100.0, 6.0, 6.0},
    },
};
static const dsp_spatial_config bypass_spatial = {
    .width = 1.0,
    .mono_bass_enabled = 0,
    .mono_bass_cutoff_hz = 120,
};
static const dsp_convolver_config bypass_convolver = {0, 0, 0, 0, 0, 0.0f, NULL};
static const dsp_harmonic_config bypass_harmonic = {
    .enabled = 0,
    .mode = 2,
    .taps = 17,
    .exciter_enabled = 0,
    .exciter_amount = 0.0,
    .exciter_cutoff_hz = 6000.0,
    .virtual_bass_enabled = 0,
    .virtual_bass_amount = 0.0,
    .virtual_bass_cutoff_hz = 120.0,
    .level_match_enabled = 1,
};

static void fail(const char *message) {
    fprintf(stderr, "FAIL: %s\n", message);
    exit(1);
}

static dsp_engine *create_test_engine(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const double *peq_coefficients,
    int peq_band_count) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        gain_db,
        peq_coefficients,
        peq_band_count,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        &bypass_harmonic);
}

static dsp_engine *create_test_engine_with_dynamics(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_dynamics_config *dynamics_config) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        0.0,
        NULL,
        0,
        dynamics_config,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        &bypass_harmonic);
}

static dsp_engine *create_test_engine_with_peq_dynamics_and_convolver(
    int sample_rate,
    int channels,
    int max_frames,
    const double *peq_coefficients,
    int peq_band_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_convolver_config *convolver_config) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        0.0,
        peq_coefficients,
        peq_band_count,
        dynamics_config,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        convolver_config,
        &bypass_harmonic);
}

static void design_test_peak_coefficients(
    double sample_rate,
    double frequency_hz,
    double gain_db,
    double q,
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT]) {
    const double omega = 2.0 * acos(-1.0) * frequency_hz / sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * q);
    const double amplitude = pow(10.0, gain_db / 40.0);
    const double a0 = 1.0 + alpha / amplitude;
    coefficients[0] = (1.0 + alpha * amplitude) / a0;
    coefficients[1] = (-2.0 * cosine) / a0;
    coefficients[2] = (1.0 - alpha * amplitude) / a0;
    coefficients[3] = (-2.0 * cosine) / a0;
    coefficients[4] = (1.0 - alpha / amplitude) / a0;
}

static dsp_engine_local_peq_update local_peq_update_from_prepared_values(
    uint64_t generation,
    uint64_t editor_owner_epoch,
    uint64_t renderer_owner_token,
    uint64_t pcm_format_epoch,
    uint32_t hot_context_token,
    unsigned long long history_epoch_sequence,
    unsigned long long history_end_sequence,
    size_t peq_count,
    const double *prepared_values) {
    dsp_engine_local_peq_update update = {0};
    update.generation = generation;
    update.generation_token = (uint32_t)(generation & UINT64_C(0x7fffffff));
    if (update.generation_token == 0) update.generation_token = 1;
    update.editor_owner_epoch = editor_owner_epoch;
    update.renderer_owner_token = renderer_owner_token;
    update.pcm_format_epoch = pcm_format_epoch;
    update.hot_context_token = hot_context_token;
    update.history_epoch_sequence = history_epoch_sequence;
    update.history_end_sequence = history_end_sequence;
    update.media_role = 1;
    update.peq_count = peq_count;
    for (size_t band = 0; band < peq_count; band++) {
        const size_t offset = band * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND;
        update.peq[band] = (dsp_biquad) {
            .b0 = prepared_values[offset],
            .b1 = prepared_values[offset + 1],
            .b2 = prepared_values[offset + 2],
            .a1 = prepared_values[offset + 3],
            .a2 = prepared_values[offset + 4],
            .z1 = {(float)prepared_values[offset + 5], (float)prepared_values[offset + 6]},
            .z2 = {(float)prepared_values[offset + 7], (float)prepared_values[offset + 8]},
        };
    }
    return update;
}

static dsp_engine *create_test_engine_with_multiband(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_multiband_config *multiband_config) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        0.0,
        NULL,
        0,
        &bypass_dynamics,
        multiband_config,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        &bypass_harmonic);
}

static dsp_engine *create_test_engine_with_spatial(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_spatial_config *spatial_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        0.0,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        bass_coefficients,
        bass_coefficient_count,
        mono_bass_coefficients,
        mono_bass_coefficient_count,
        spatial_config,
        &bypass_convolver,
        &bypass_harmonic);
}

static dsp_engine *create_test_engine_with_convolver_and_gain(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const dsp_convolver_config *convolver_config);

static dsp_engine *create_test_engine_with_convolver(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_convolver_config *convolver_config) {
    return create_test_engine_with_convolver_and_gain(
        sample_rate, channels, max_frames, 0.0, convolver_config);
}

static dsp_engine *create_test_engine_with_convolver_and_gain(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const dsp_convolver_config *convolver_config) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        gain_db,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        convolver_config,
        &bypass_harmonic);
}

static dsp_engine *create_test_engine_with_dynamic_eq(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_dynamic_eq_config *dynamic_eq_config) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        0.0,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        dynamic_eq_config,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        &bypass_harmonic);
}

static dsp_engine *create_test_engine_with_harmonic(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_harmonic_config *harmonic_config) {
    return dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        0.0,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        harmonic_config);
}

static void assert_true(int condition, const char *message) {
    if (!condition) {
        fail(message);
    }
}

static void assert_near(double actual, double expected, double tolerance, const char *message) {
    if (!isfinite(actual) || fabs(actual - expected) > tolerance) {
        fprintf(stderr, "FAIL: %s (actual %.12f, expected %.12f)\n", message, actual, expected);
        exit(1);
    }
}

static double measure_sine_gain(dsp_engine *engine, double frequency, int channels);

static void test_gain_and_meter(void) {
    const double gain_db = 6.020599913279624;
    dsp_engine *engine = create_test_engine(48000, 2, 512, gain_db, NULL, 0);
    assert_true(engine != NULL, "engine create for gain test");

    const float input[] = {0.25f, -0.5f, -0.75f, 1.0f};
    float output[4] = {0};
    assert_true(
        dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "valid stereo block processes");
    assert_near(output[0], 0.5, 1e-6, "left gain reference 1");
    assert_near(output[1], -1.0, 1e-6, "right gain reference 1");
    assert_near(output[2], -1.5, 1e-6, "left gain reference 2");
    assert_near(output[3], 2.0, 1e-6, "right gain reference 2");

    dsp_engine_diagnostics diagnostics;
    assert_true(
        dsp_engine_get_diagnostics(engine, &diagnostics) == DSP_STATUS_OK,
        "diagnostics snapshot succeeds");
    assert_near(diagnostics.input_peak[0], 0.75, 1e-6, "input peak left");
    assert_near(diagnostics.input_peak[1], 1.0, 1e-6, "input peak right");
    assert_near(diagnostics.output_peak[0], 1.5, 1e-6, "output peak left");
    assert_near(diagnostics.output_peak[1], 2.0, 1e-6, "output peak right");
    assert_near(diagnostics.input_rms[0], sqrt(0.3125), 1e-6, "input RMS left");
    assert_near(diagnostics.output_rms[1], sqrt(2.5), 1e-6, "output RMS right");
    assert_true(diagnostics.processed_frames == 2, "processed frame counter");
    assert_true(diagnostics.processed_blocks == 1, "processed block counter");
    dsp_engine_destroy(engine);
}

static void test_bad_handles_arguments_and_capacities(void) {
    const float input[] = {0.25f, -0.5f};
    float output[] = {11.0f, 12.0f, 13.0f, 14.0f};
    assert_true(
        dsp_engine_process(NULL, input, 2, output, 2, 1, 2) == DSP_STATUS_INVALID_HANDLE,
        "null handle is rejected");

    dsp_engine *engine = create_test_engine(48000, 2, 2, 0.0, NULL, 0);
    assert_true(engine != NULL, "engine create for validation tests");
    assert_true(
        dsp_engine_process(engine, input, 1, output, 4, 1, 2) == DSP_STATUS_INVALID_BUFFER,
        "undersized input and output are rejected");
    assert_near(output[0], 11.0, 0.0, "undersized output remains untouched");
    assert_near(output[3], 14.0, 0.0, "output canary remains untouched");
    assert_true(
        dsp_engine_process(engine, input, 2, output, 4, 0, 2) == DSP_STATUS_INVALID_ARGUMENT,
        "zero frames are rejected");
    assert_true(
        dsp_engine_process(engine, input, 2, output, 4, 3, 2) == DSP_STATUS_INVALID_ARGUMENT,
        "too many frames are rejected");
    assert_true(
        dsp_engine_process(engine, input, 2, output, 4, 1, 1) == DSP_STATUS_INVALID_ARGUMENT,
        "channel mismatch is rejected");
    dsp_engine_destroy(engine);

    assert_true(create_test_engine(7999, 2, 512, 0.0, NULL, 0) == NULL, "low sample rate is rejected");
    assert_true(create_test_engine(48000, 3, 512, 0.0, NULL, 0) == NULL, "multichannel output is rejected");
    assert_true(create_test_engine(48000, 2, 512, INFINITY, NULL, 0) == NULL, "non-finite gain is rejected");
    assert_true(create_test_engine(48000, 2, 512, 25.0, NULL, 0) == NULL, "out-of-range gain is rejected");
    assert_true(create_test_engine(48000, 2, 512, 0.0, NULL, 1) == NULL, "missing filter coefficients are rejected");
    assert_true(create_test_engine(48000, 2, 512, 0.0, NULL, 16) == NULL, "too many filters are rejected");
    assert_true(
        dsp_engine_create(48000, 2, 512, 0.0, NULL, 0, NULL, &bypass_multiband, &bypass_dynamic_eq, NULL, 0, NULL, 0,
            &bypass_spatial, &bypass_convolver, &bypass_harmonic) == NULL,
        "missing dynamics configuration is rejected");
}

static void test_chunk_invariance_and_buffer_canaries(void) {
    const float input[] = {
        0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f, 0.4f, -0.4f,
        0.5f, -0.5f, 0.6f, -0.6f, 0.7f, -0.7f, 0.8f, -0.8f,
    };
    float whole_storage[18];
    float split_output[16];
    for (size_t index = 0; index < 18; index++) {
        whole_storage[index] = 77.0f;
    }

    dsp_engine *whole = create_test_engine(44100, 2, 8, -3.0, NULL, 0);
    dsp_engine *split = create_test_engine(44100, 2, 8, -3.0, NULL, 0);
    assert_true(whole != NULL && split != NULL, "engines create for chunk test");
    assert_true(
        dsp_engine_process(whole, input, 16, whole_storage + 1, 16, 8, 2) == DSP_STATUS_OK,
        "whole buffer processes");
    assert_true(
        dsp_engine_process(split, input, 6, split_output, 6, 3, 2) == DSP_STATUS_OK,
        "first split block processes");
    assert_true(
        dsp_engine_process(split, input + 6, 10, split_output + 6, 10, 5, 2) == DSP_STATUS_OK,
        "second split block processes");
    assert_near(whole_storage[0], 77.0, 0.0, "leading output canary unchanged");
    assert_near(whole_storage[17], 77.0, 0.0, "trailing output canary unchanged");
    for (size_t index = 0; index < 16; index++) {
        assert_near(whole_storage[index + 1], split_output[index], 0.0, "chunk invariance");
    }
    dsp_engine_destroy(whole);
    dsp_engine_destroy(split);
}

static void test_nonfinite_samples_and_reset(void) {
    dsp_engine *engine = create_test_engine(48000, 1, 4, 24.0, NULL, 0);
    assert_true(engine != NULL, "engine create for non-finite test");
    const float input[] = {NAN, INFINITY, -INFINITY, 1.0f};
    float output[4] = {9.0f, 9.0f, 9.0f, 9.0f};
    assert_true(
        dsp_engine_process(engine, input, 4, output, 4, 4, 1) == DSP_STATUS_OK,
        "non-finite input block processes");
    assert_near(output[0], 0.0, 0.0, "NaN input is sanitized");
    assert_near(output[1], 0.0, 0.0, "infinite input is sanitized");
    assert_near(output[2], 0.0, 0.0, "negative infinite input is sanitized");
    assert_near(output[3], pow(10.0, 24.0 / 20.0), 1e-5, "finite gain output remains finite");

    dsp_engine_diagnostics diagnostics;
    assert_true(dsp_engine_get_diagnostics(engine, &diagnostics) == DSP_STATUS_OK, "non-finite diagnostics");
    assert_true(diagnostics.non_finite_input_samples == 3, "non-finite input counter");
    assert_true(diagnostics.non_finite_output_samples == 0, "finite output counter");
    assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "engine reset succeeds");
    assert_true(dsp_engine_get_diagnostics(engine, &diagnostics) == DSP_STATUS_OK, "reset diagnostics");
    assert_true(diagnostics.processed_frames == 0, "reset clears frame count");
    assert_true(diagnostics.processed_blocks == 0, "reset clears block count");
    assert_true(diagnostics.non_finite_input_samples == 0, "reset clears non-finite count");
    assert_near(diagnostics.output_peak[0], 0.0, 0.0, "reset clears meters");
    dsp_engine_destroy(engine);
}

static void test_processed_frame_counter_saturates_without_wrap(void) {
    dsp_engine *engine = create_test_engine(48000, 1, 1, 0.0, NULL, 0);
    assert_true(engine != NULL, "counter boundary engine creates");
    const float input[] = {0.25f};
    float output[] = {0.0f};
    dsp_engine_diagnostics diagnostics;

    dsp_engine_test_set_processed_frames(engine, UINT32_MAX - 1U);
    assert_true(dsp_engine_process(engine, input, 1, output, 1, 1, 1) == DSP_STATUS_OK,
        "counter reaches its saturation boundary");
    assert_true(dsp_engine_process(engine, input, 1, output, 1, 1, 1) == DSP_STATUS_OK,
        "counter stays valid after reaching its boundary");
    assert_true(dsp_engine_get_diagnostics(engine, &diagnostics) == DSP_STATUS_OK,
        "saturated counter diagnostics are readable");
    assert_true(diagnostics.processed_frames == UINT32_MAX,
        "processed frame counter saturates instead of wrapping");
    dsp_engine_destroy(engine);
}

static void design_peak(double sample_rate, double frequency, double q, double gain_db, double coefficients[5]) {
    const double pi = 3.14159265358979323846;
    const double omega = 2.0 * pi * frequency / sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * q);
    const double a = pow(10.0, gain_db / 40.0);
    const double a0 = 1.0 + alpha / a;
    coefficients[0] = (1.0 + alpha * a) / a0;
    coefficients[1] = -2.0 * cosine / a0;
    coefficients[2] = (1.0 - alpha * a) / a0;
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha / a) / a0;
}

static void test_peq_frequency_response_and_reset(void) {
    double coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_peak(48000.0, 1000.0, 1.0, 6.0, coefficients);
    dsp_engine *engine = create_test_engine(48000, 1, 512, 0.0, coefficients, 1);
    assert_true(engine != NULL, "engine creates with peak EQ coefficients");

    double input_square = 0.0;
    double output_square = 0.0;
    size_t frame = 0;
    for (size_t block = 0; block < 16; block++) {
        float input[512];
        float output[512];
        for (size_t index = 0; index < 512; index++, frame++) {
            input[index] = (float)sin(2.0 * 3.14159265358979323846 * 1000.0 * (double)frame / 48000.0);
        }
        assert_true(
            dsp_engine_process(engine, input, 512, output, 512, 512, 1) == DSP_STATUS_OK,
            "EQ sine block processes");
        if (frame <= 1024) continue;
        for (size_t index = 0; index < 512; index++) {
            if (frame - 512 + index < 1024) continue;
            input_square += (double)input[index] * input[index];
            output_square += (double)output[index] * output[index];
        }
    }
    const double rms_ratio = sqrt(output_square / input_square);
    assert_near(rms_ratio, pow(10.0, 6.0 / 20.0), 0.01, "1 kHz peak filter applies +6 dB");

    assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "EQ reset succeeds");
    const float silence[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float output[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    assert_true(
        dsp_engine_process(engine, silence, 4, output, 4, 4, 1) == DSP_STATUS_OK,
        "reset EQ processes silence");
    for (size_t index = 0; index < 4; index++) {
        assert_near(output[index], 0.0, 0.0, "EQ reset clears filter state");
    }
    dsp_engine_destroy(engine);
}

static void test_peq_stereo_state_and_denormal_flush(void) {
    const double identity[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, 0.0, 0.0};
    double fifteen_filters[DSP_BIQUAD_MAX_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT] = {0};
    for (size_t index = 0; index < DSP_BIQUAD_MAX_BANDS; index++) {
        fifteen_filters[index * DSP_BIQUAD_COEFFICIENT_COUNT] = 1.0;
    }
    dsp_engine *maximum = create_test_engine(48000, 1, 1, 0.0, fifteen_filters, DSP_BIQUAD_MAX_BANDS);
    assert_true(maximum != NULL, "maximum supported EQ band count creates");
    dsp_engine_destroy(maximum);
    const double invalid[DSP_BIQUAD_COEFFICIENT_COUNT] = {NAN, 0.0, 0.0, 0.0, 0.0};
    assert_true(
        create_test_engine(48000, 1, 1, 0.0, invalid, 1) == NULL,
        "non-finite EQ coefficients are rejected");

    dsp_engine *stereo = create_test_engine(48000, 2, 2, 0.0, identity, 1);
    assert_true(stereo != NULL, "identity EQ creates");
    const float impulse[] = {1.0f, 0.0f, 0.0f, 0.0f};
    float output[4] = {0};
    assert_true(
        dsp_engine_process(stereo, impulse, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "stereo EQ block processes");
    assert_near(output[0], 1.0, 0.0, "left EQ state is independent");
    assert_near(output[1], 0.0, 0.0, "right EQ state is independent");
    assert_near(output[2], 0.0, 0.0, "left impulse tail is empty");
    assert_near(output[3], 0.0, 0.0, "right impulse tail is empty");
    dsp_engine_destroy(stereo);

    const double slow_state[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, -1e-25, 0.0};
    dsp_engine *denormal = create_test_engine(48000, 1, 1, 0.0, slow_state, 1);
    assert_true(denormal != NULL, "stable small-state EQ creates");
    const float one[] = {1.0f};
    float first_output[] = {0.0f};
    assert_true(
        dsp_engine_process(denormal, one, 1, first_output, 1, 1, 1) == DSP_STATUS_OK,
        "small state filter first block processes");
    const float zero[] = {0.0f};
    float second_output[] = {1.0f};
    assert_true(
        dsp_engine_process(denormal, zero, 1, second_output, 1, 1, 1) == DSP_STATUS_OK,
        "small state filter second block processes");
    assert_near(second_output[0], 0.0, 0.0, "denormal state flushes at block end");
    dsp_engine_destroy(denormal);
}

static void test_peq_fifteenth_band_is_active(void) {
    double coefficients[DSP_BIQUAD_MAX_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT] = {0};
    for (size_t band = 0; band < DSP_BIQUAD_MAX_BANDS; band++) {
        coefficients[band * DSP_BIQUAD_COEFFICIENT_COUNT] = 1.0;
    }
    design_peak(
        48000.0,
        1000.0,
        1.0,
        6.0,
        coefficients + (DSP_BIQUAD_MAX_BANDS - 1) * DSP_BIQUAD_COEFFICIENT_COUNT);
    dsp_engine *engine = create_test_engine(
        48000,
        1,
        512,
        0.0,
        coefficients,
        DSP_BIQUAD_MAX_BANDS);
    assert_true(engine != NULL, "15th-band PEQ graph creates");
    assert_near(
        measure_sine_gain(engine, 1000.0, 1),
        pow(10.0, 6.0 / 20.0),
        0.03,
        "active 15th PEQ band applies its configured boost");
    dsp_engine_destroy(engine);
}

static void test_compressor_transfer_curve(void) {
    const double input_db[] = {-40.0, -20.0, -10.0, 0.0};
    dsp_dynamics_config config = bypass_dynamics;
    config.compressor_enabled = 1;
    config.compressor_threshold_db = -20.0;
    config.compressor_ratio = 4.0;
    config.compressor_attack_ms = 10.0;
    config.compressor_release_ms = 100.0;
    config.compressor_knee_db = 0.0;
    config.compressor_makeup_db = 0.0;

    for (size_t level = 0; level < sizeof(input_db) / sizeof(input_db[0]); level++) {
        const float input_level = (float)pow(10.0, input_db[level] / 20.0);
        const float expected = input_db[level] <= -20.0
            ? input_level
            : (float)pow(10.0, (-20.0 + (input_db[level] + 20.0) / 4.0) / 20.0);
        dsp_engine *engine = create_test_engine_with_dynamics(48000, 1, 512, &config);
        assert_true(engine != NULL, "compressor config creates");
        assert_true(dsp_engine_get_latency_frames(engine) == 0, "compressor has zero algorithmic latency");

        float output[512] = {0};
        size_t remaining = 48000;
        while (remaining > 0) {
            const size_t frames = remaining < 512 ? remaining : 512;
            float input[512];
            for (size_t index = 0; index < frames; index++) input[index] = input_level;
            assert_true(
                dsp_engine_process(engine, input, frames, output, frames, (int)frames, 1) == DSP_STATUS_OK,
                "compressor steady level processes");
            remaining -= frames;
        }
        assert_near(output[511], expected, 0.001, "compressor follows the 4:1 transfer curve");
        dsp_engine_destroy(engine);
    }
}

static void test_compressor_attack_and_release_steps(void) {
    dsp_dynamics_config config = bypass_dynamics;
    config.compressor_enabled = 1;
    config.compressor_threshold_db = -20.0;
    config.compressor_ratio = 4.0;
    config.compressor_attack_ms = 100.0;
    config.compressor_release_ms = 300.0;
    dsp_engine *engine = create_test_engine_with_dynamics(48000, 1, 512, &config);
    assert_true(engine != NULL, "attack/release compressor creates");

    const float high[] = {1.0f};
    float output[512] = {0};
    assert_true(
        dsp_engine_process(engine, high, 1, output, 1, 1, 1) == DSP_STATUS_OK,
        "compressor first high sample processes");
    assert_near(output[0], 1.0, 1e-5, "compressor attack does not look ahead");

    size_t remaining = 48000;
    while (remaining > 0) {
        const size_t frames = remaining < 512 ? remaining : 512;
        float input[512];
        for (size_t index = 0; index < frames; index++) input[index] = 1.0f;
        assert_true(
            dsp_engine_process(engine, input, frames, output, frames, (int)frames, 1) == DSP_STATUS_OK,
            "compressor attack step processes");
        remaining -= frames;
    }
    assert_true(output[511] < 0.2f, "compressor gain reaches steady-state reduction");

    const float low[] = {0.01f};
    assert_true(
        dsp_engine_process(engine, low, 1, output, 1, 1, 1) == DSP_STATUS_OK,
        "compressor release step begins");
    const float initial_release = output[0];
    assert_true(initial_release < 0.01f, "release holds gain reduction after a downward step");

    remaining = 96000;
    while (remaining > 0) {
        const size_t frames = remaining < 512 ? remaining : 512;
        float input[512];
        for (size_t index = 0; index < frames; index++) input[index] = 0.01f;
        assert_true(
            dsp_engine_process(engine, input, frames, output, frames, (int)frames, 1) == DSP_STATUS_OK,
            "compressor release recovers");
        remaining -= frames;
    }
    assert_near(output[511], 0.01, 0.0001, "compressor release restores low-level gain");
    dsp_engine_destroy(engine);
}

static void test_dynamics_hot_params_preserve_state_and_validate_atomically(void) {
    dsp_dynamics_config initial = {
        1, -24.0, 3.0, 10.0, 100.0, 2.0, 0.0, 1, -1.0, 80.0,
    };
    dsp_dynamics dynamics = {0};
    assert_true(dsp_dynamics_prepare(&dynamics, 48000, &initial), "dynamics HOT baseline prepares");
    dynamics.compressor_envelope = 0.375f;
    dynamics.limiter_gain = 0.41f;

    dsp_dynamics_config target = initial;
    target.compressor_threshold_db = -18.0;
    target.compressor_ratio = 5.0;
    target.compressor_attack_ms = 4.0;
    target.compressor_release_ms = 140.0;
    target.compressor_knee_db = 5.0;
    target.compressor_makeup_db = 6.0;
    target.limiter_threshold_db = -2.0;
    target.limiter_release_ms = 120.0;
    dsp_dynamics_hot_params params = {0};
    assert_true(dsp_dynamics_prepare_hot_params(48000, &target, &params), "safe dynamics HOT target prepares");
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &params, 100) == DSP_DYNAMICS_HOT_APPLIED,
        "compressor parameters and lower limiter ceiling apply HOT");
    assert_true(dynamics.compressor_envelope == 0.375f, "HOT preserves compressor envelope exactly");
    assert_true(dynamics.limiter_gain == 0.41f, "HOT preserves limiter gain exactly");
    assert_near(dynamics.compressor_attack_coefficient, params.compressor_attack_coefficient, 0.0,
        "HOT installs the prepared attack coefficient");
    assert_near(dynamics.compressor_release_coefficient, params.compressor_release_coefficient, 0.0,
        "HOT installs the prepared release coefficient");
    assert_true(dynamics.compressor_makeup_ramp_remaining == 100, "makeup uses the requested bounded transition");

    const float input = 0.05f;
    const float initial_makeup = 1.0f;
    const float target_makeup = params.compressor_makeup_linear;
    float previous_makeup = initial_makeup;
    for (int frame = 0; frame < 100; frame++) {
        float left = input;
        float right = input;
        dsp_dynamics_process_compressor_frame(&dynamics, &left, &right, 1);
        assert_true(isfinite(dynamics.compressor_makeup_linear), "smoothed makeup stays finite");
        assert_true(dynamics.compressor_makeup_linear >= previous_makeup &&
            dynamics.compressor_makeup_linear <= target_makeup, "makeup transition is monotonic and bounded");
        assert_true(fabsf(left) <= input * target_makeup + 1e-6f, "makeup ramp output stays within target range");
        previous_makeup = dynamics.compressor_makeup_linear;
    }
    assert_near(dynamics.compressor_makeup_linear, target_makeup, 0.0, "makeup ramp reaches exact target");
    assert_true(!dsp_dynamics_hot_transition_pending(&dynamics), "makeup transition clears pending state");

    const float lowered_threshold = dynamics.limiter_threshold_linear;
    float peak_left = 1.0f;
    float peak_right = -0.8f;
    dsp_dynamics_process_limiter_frame(&dynamics, &peak_left, &peak_right, 2);
    assert_true(fabsf(peak_left) <= lowered_threshold + 1e-6f &&
        fabsf(peak_right) <= lowered_threshold + 1e-6f, "lowered limiter threshold maintains the peak ceiling");

    dsp_dynamics before_invalid = dynamics;
    dsp_dynamics_hot_params invalid = params;
    invalid.compressor_release_coefficient = NAN;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &invalid, 100) == DSP_DYNAMICS_HOT_INVALID,
        "NaN prepared coefficient is rejected");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "invalid HOT payload has no partial state mutation");

    dsp_dynamics_hot_params inconsistent = params;
    inconsistent.compressor_makeup_linear = 15.848932f;
    assert_true(!dsp_dynamics_hot_params_validate(&inconsistent),
        "control-side validation rejects a finite but inconsistent makeup coefficient");
    before_invalid = dynamics;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &inconsistent, 100) == DSP_DYNAMICS_HOT_INVALID,
        "render-side apply rejects a modified prepared payload without recomputing coefficients");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "inconsistent finite payload has no partial state mutation");
    inconsistent = params;
    inconsistent.compressor_attack_coefficient *= 0.5f;
    assert_true(!dsp_dynamics_hot_params_validate(&inconsistent),
        "control-side validation rejects a finite but incorrect attack coefficient");
    before_invalid = dynamics;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &inconsistent, 100) == DSP_DYNAMICS_HOT_INVALID,
        "incorrect finite coefficient cannot enter the active stage");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "incorrect finite coefficient leaves active state unchanged");

    dsp_dynamics curve_dynamics = {0};
    dsp_dynamics_config curve_initial = initial;
    curve_initial.compressor_threshold_db = -24.0;
    curve_initial.compressor_ratio = 20.0;
    curve_initial.compressor_knee_db = 0.0;
    curve_initial.compressor_release_ms = 2000.0;
    assert_true(dsp_dynamics_prepare(&curve_dynamics, 48000, &curve_initial),
        "high-envelope curve-slew baseline prepares");
    curve_dynamics.compressor_envelope = 0.9f;
    curve_dynamics.limiter_gain = 0.37f;
    dsp_dynamics_config curve_target = curve_initial;
    curve_target.compressor_threshold_db = -6.0;
    curve_target.compressor_ratio = 1.0;
    curve_target.compressor_knee_db = 12.0;
    curve_target.limiter_threshold_db = -2.0;
    dsp_dynamics_hot_params curve_params = {0};
    assert_true(dsp_dynamics_prepare_hot_params(48000, &curve_target, &curve_params),
        "high-envelope curve-slew target prepares");
    const float saved_envelope = curve_dynamics.compressor_envelope;
    const float saved_limiter_gain = curve_dynamics.limiter_gain;
    assert_true(dsp_dynamics_apply_hot_params(&curve_dynamics, &curve_params, 200) == DSP_DYNAMICS_HOT_APPLIED,
        "large ratio and threshold change starts a bounded curve slew");
    assert_true(curve_dynamics.compressor_envelope == saved_envelope &&
        curve_dynamics.limiter_gain == saved_limiter_gain, "curve slew preserves detector and limiter state");
    assert_true(dsp_dynamics_hot_transition_pending(&curve_dynamics), "curve slew exposes pending state for ACK gating");
    float previous_gain = 0.0f;
    float largest_gain_step = 0.0f;
    for (int frame = 0; frame < 3000; frame++) {
        float left = 0.01f;
        float right = left;
        dsp_dynamics_process_compressor_frame(&curve_dynamics, &left, &right, 1);
        const float effective_gain = left / 0.01f;
        if (frame > 0) largest_gain_step = fmaxf(largest_gain_step, fabsf(effective_gain - previous_gain));
        previous_gain = effective_gain;
        if (!dsp_dynamics_hot_transition_pending(&curve_dynamics)) break;
    }
    assert_true(largest_gain_step < 0.03f, "ratio/threshold/knee slew bounds per-frame compressor gain steps");
    assert_true(!dsp_dynamics_hot_transition_pending(&curve_dynamics), "curve slew clears pending state at completion");
    assert_true(curve_dynamics.compressor_threshold_db == (float)curve_target.compressor_threshold_db &&
        curve_dynamics.compressor_ratio == (float)curve_target.compressor_ratio &&
        curve_dynamics.compressor_knee_db == (float)curve_target.compressor_knee_db,
        "curve slew converges to the exact requested parameters");

    curve_target.compressor_threshold_db = -12.0;
    curve_target.compressor_ratio = 20.0;
    curve_target.compressor_attack_ms = 0.1;
    curve_target.compressor_release_ms = 0.1;
    curve_target.compressor_knee_db = 24.0;
    curve_target.limiter_threshold_db = -3.0;
    assert_true(dsp_dynamics_prepare_hot_params(48000, &curve_target, &curve_params),
        "changing-signal slew target prepares");
    assert_true(dsp_dynamics_apply_hot_params(&curve_dynamics, &curve_params, 64) == DSP_DYNAMICS_HOT_APPLIED,
        "changing-signal transition starts");
    previous_gain = 0.0f;
    largest_gain_step = 0.0f;
    for (int frame = 0; frame < 64; frame++) {
        float left = frame % 2 == 0 ? 1.0f : 0.01f;
        float right = left;
        const float input_level = left;
        dsp_dynamics_process_compressor_frame(&curve_dynamics, &left, &right, 1);
        const float effective_gain = left / input_level;
        if (frame > 0) largest_gain_step = fmaxf(largest_gain_step, fabsf(effective_gain - previous_gain));
        previous_gain = effective_gain;
    }
    assert_true(largest_gain_step <= 0.01001f, "changing input remains within the per-frame gain slew");
    assert_true(!dsp_dynamics_hot_transition_pending(&curve_dynamics),
        "changing input cannot extend pending beyond the configured transition frame count");

    dsp_dynamics_hot_params stale_format = {0};
    assert_true(dsp_dynamics_prepare_hot_params(44100, &target, &stale_format),
        "different sample-rate parameters prepare independently");
    before_invalid = dynamics;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &stale_format, 100) == DSP_DYNAMICS_HOT_INVALID,
        "sample-rate-stale HOT payload is rejected");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "stale sample-rate rejection leaves active state unchanged");

    dsp_dynamics_config invalid_config = target;
    invalid_config.compressor_knee_db = NAN;
    dsp_dynamics_hot_params unchanged_params = params;
    assert_true(!dsp_dynamics_prepare_hot_params(48000, &invalid_config, &unchanged_params),
        "NaN config is rejected during preparation");
    assert_true(memcmp(&unchanged_params, &params, sizeof(params)) == 0,
        "failed preparation does not partially overwrite prepared payload");

    dsp_dynamics_hot_params unsafe = params;
    unsafe.config.limiter_threshold_db = -1.0;
    unsafe.limiter_threshold_linear = (float)pow(10.0, -1.0 / 20.0);
    before_invalid = dynamics;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &unsafe, 100) == DSP_DYNAMICS_HOT_DEFERRED,
        "limiter threshold increase is explicitly deferred");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "deferred limiter increase leaves active state unchanged");

    dsp_dynamics_hot_params topology = params;
    topology.config.compressor_enabled = 0;
    before_invalid = dynamics;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &topology, 100) == DSP_DYNAMICS_HOT_DEFERRED,
        "compressor enable transition is explicitly deferred");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "deferred topology change leaves active state unchanged");

    topology = params;
    topology.config.limiter_enabled = 0;
    before_invalid = dynamics;
    assert_true(dsp_dynamics_apply_hot_params(&dynamics, &topology, 100) == DSP_DYNAMICS_HOT_DEFERRED,
        "limiter disable transition is explicitly deferred");
    assert_true(memcmp(&dynamics, &before_invalid, sizeof(dynamics)) == 0,
        "deferred limiter disable leaves active state unchanged");
}

static void test_combined_dynamics_dynamic_eq_hot_preflights_before_any_mutation(void) {
    dsp_dynamics_config current_dynamics = bypass_dynamics;
    current_dynamics.compressor_enabled = 1;
    dsp_dynamic_eq_config current_eq = bypass_dynamic_eq;
    current_eq.enabled = 1;
    current_eq.bands[0].enabled = 1;
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    dsp_graph graph = {0};
    assert_true(dsp_graph_prepare(&graph, 48000, 2, -6.0, NULL, 0, &current_dynamics,
        &bypass_multiband, &current_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
        &bypass_harmonic, &macro, &loudness, &dynamic_bass), "combined HOT preflight graph prepares");
    graph.dynamics.compressor_envelope = 0.375f;
    graph.dynamic_eq.bands[0].envelope = 0.25f;
    graph.dynamic_eq.bands[0].filter.z1[0] = 0.125f;
    const dsp_dynamics before_dynamics = graph.dynamics;
    const dsp_dynamic_eq before_eq = graph.dynamic_eq;
    const dsp_gain before_gain = graph.gain;
    const dsp_macro before_macro = graph.macro;
    const dsp_smart_loudness before_loudness = graph.smart_loudness;
    const dsp_dynamic_bass before_bass = graph.dynamic_bass;
    const dsp_macro_config target_macro = {1, 1.0, 0.5, 0.25, 1.2};

    dsp_dynamics_config safe_dynamics = current_dynamics;
    safe_dynamics.compressor_threshold_db = -28.0;
    dsp_dynamics_hot_params dynamics_update = {0};
    assert_true(dsp_dynamics_prepare_hot_params(48000, &safe_dynamics, &dynamics_update),
        "safe Dynamics HOT payload prepares");
    dsp_dynamic_eq_config unsafe_eq = current_eq;
    unsafe_eq.bands[0].frequency_hz = 125.0;
    dsp_dynamic_eq_hot_update eq_update = {0};
    assert_true(dsp_dynamic_eq_prepare_hot_update(48000, &unsafe_eq, &eq_update),
        "unsafe topology payload is structurally valid");
    assert_true(dsp_graph_apply_hot(&graph,
        DSP_ENGINE_HOT_FIELD_STUDIO | DSP_ENGINE_HOT_FIELD_DYNAMICS | DSP_ENGINE_HOT_FIELD_DYNAMIC_EQ,
        -8.0, pow(10.0, -8.0 / 20.0), &target_macro, &loudness, &dynamic_bass,
        &dynamics_update, &eq_update) == DSP_GRAPH_HOT_REJECTED,
        "safe Dynamics plus unsafe Dynamic EQ topology rejects as one command");
    assert_true(memcmp(&graph.dynamics, &before_dynamics, sizeof(before_dynamics)) == 0,
        "unsafe Dynamic EQ topology leaves compressor configuration and envelope unchanged");
    assert_true(memcmp(&graph.dynamic_eq, &before_eq, sizeof(before_eq)) == 0,
        "unsafe Dynamic EQ topology leaves filter and envelope unchanged");
    assert_true(memcmp(&graph.gain, &before_gain, sizeof(before_gain)) == 0,
        "unsafe mixed command leaves protective gain unchanged");
    assert_true(memcmp(&graph.macro, &before_macro, sizeof(before_macro)) == 0 &&
        memcmp(&graph.smart_loudness, &before_loudness, sizeof(before_loudness)) == 0 &&
        memcmp(&graph.dynamic_bass, &before_bass, sizeof(before_bass)) == 0,
        "unsafe Dynamic EQ leaves all pre-existing Studio stages unchanged");

    dsp_dynamics_config unsafe_dynamics = safe_dynamics;
    unsafe_dynamics.compressor_enabled = 0;
    assert_true(dsp_dynamics_prepare_hot_params(48000, &unsafe_dynamics, &dynamics_update),
        "unsafe compressor topology payload prepares structurally");
    dsp_dynamic_eq_config safe_eq = current_eq;
    safe_eq.bands[0].threshold_db = -30.0;
    assert_true(dsp_dynamic_eq_prepare_hot_update(48000, &safe_eq, &eq_update),
        "safe Dynamic EQ parameter payload prepares");
    assert_true(dsp_graph_apply_hot(&graph,
        DSP_ENGINE_HOT_FIELD_STUDIO | DSP_ENGINE_HOT_FIELD_DYNAMICS | DSP_ENGINE_HOT_FIELD_DYNAMIC_EQ,
        -8.0, pow(10.0, -8.0 / 20.0), &target_macro, &loudness, &dynamic_bass,
        &dynamics_update, &eq_update) == DSP_GRAPH_HOT_DEFERRED_DYNAMICS,
        "unsafe Dynamics topology plus safe Dynamic EQ rejects as one command");
    assert_true(memcmp(&graph.dynamics, &before_dynamics, sizeof(before_dynamics)) == 0 &&
        memcmp(&graph.dynamic_eq, &before_eq, sizeof(before_eq)) == 0 &&
        memcmp(&graph.gain, &before_gain, sizeof(before_gain)) == 0 &&
        memcmp(&graph.macro, &before_macro, sizeof(before_macro)) == 0 &&
        memcmp(&graph.smart_loudness, &before_loudness, sizeof(before_loudness)) == 0 &&
        memcmp(&graph.dynamic_bass, &before_bass, sizeof(before_bass)) == 0,
        "unsafe Dynamics topology leaves every stage and gain unchanged");

    dsp_dynamic_eq_config cap_increase = current_eq;
    cap_increase.bands[0].max_cut_db = 8.0;
    assert_true(dsp_dynamic_eq_prepare_hot_update(48000, &cap_increase, &eq_update),
        "filter cap increase payload prepares structurally");
    assert_true(dsp_graph_apply_hot(&graph,
        DSP_ENGINE_HOT_FIELD_STUDIO | DSP_ENGINE_HOT_FIELD_DYNAMIC_EQ,
        -6.0, pow(10.0, -6.0 / 20.0), &macro, &loudness, &dynamic_bass,
        &dynamics_update, &eq_update) == DSP_GRAPH_HOT_DEFERRED_DYNAMIC_EQ_CAP,
        "unprepared Dynamic EQ cap increase explicitly defers");

    dsp_dynamics_config gain_increasing_dynamics = current_dynamics;
    gain_increasing_dynamics.compressor_makeup_db = 2.0;
    assert_true(dsp_dynamics_prepare_hot_params(48000, &gain_increasing_dynamics, &dynamics_update),
        "gain-increasing Compressor parameters prepare");
    safe_eq = current_eq;
    safe_eq.bands[0].threshold_db = -26.0;
    assert_true(dsp_dynamic_eq_prepare_hot_update(48000, &safe_eq, &eq_update),
        "gain-increasing combined Dynamic EQ parameters prepare");
    assert_true(dsp_graph_apply_hot(&graph,
        DSP_ENGINE_HOT_FIELD_DYNAMICS | DSP_ENGINE_HOT_FIELD_DYNAMIC_EQ,
        -8.0, pow(10.0, -8.0 / 20.0), &macro, &loudness, &dynamic_bass,
        &dynamics_update, &eq_update) == DSP_GRAPH_HOT_PROTECTIVE_RAMP,
        "protective preamp ramp starts before the gain-increasing stage command");
    assert_true(graph.dynamics.config.compressor_makeup_db == before_dynamics.config.compressor_makeup_db &&
        graph.dynamic_eq.bands[0].config.threshold_db == before_eq.bands[0].config.threshold_db,
        "Dynamics and Dynamic EQ remain unchanged while protective attenuation ramps");
    float ramp_input[1920];
    float ramp_output[1920] = {0.0f};
    for (size_t index = 0; index < 1920; index++) ramp_input[index] = 0.1f;
    uint64_t nonfinite_input = 0;
    uint64_t nonfinite_output = 0;
    dsp_graph_process(&graph, ramp_input, ramp_output, 960, 2, &nonfinite_input, &nonfinite_output);
    assert_true(graph.gain.ramp_remaining == 0, "protective attenuation ramp completes before stage apply");
    assert_true(dsp_graph_apply_hot(&graph,
        DSP_ENGINE_HOT_FIELD_DYNAMICS | DSP_ENGINE_HOT_FIELD_DYNAMIC_EQ,
        -8.0, pow(10.0, -8.0 / 20.0), &macro, &loudness, &dynamic_bass,
        &dynamics_update, &eq_update) == DSP_GRAPH_HOT_APPLIED,
        "gain-increasing stages apply after protective attenuation finishes");
    assert_true(graph.dynamics.config.compressor_makeup_db == 2.0 &&
        graph.dynamic_eq.bands[0].config.threshold_db == -26.0,
        "prepared stage targets commit together after the safety ramp");
    assert_true(dsp_graph_hot_transition_pending(&graph),
        "bounded parameter and mix transitions keep the final ACK pending");
    dsp_graph_process(&graph, ramp_input, ramp_output, 960, 2, &nonfinite_input, &nonfinite_output);
    assert_true(!dsp_graph_hot_transition_pending(&graph),
        "final HOT completion waits for makeup and Dynamic EQ mix ramps");
    dsp_graph_close(&graph);
}

static void test_stereo_limiter_caps_sample_peaks_and_preserves_image(void) {
    dsp_dynamics_config config = bypass_dynamics;
    config.limiter_enabled = 1;
    config.limiter_threshold_db = -1.0;
    config.limiter_release_ms = 60.0;
    dsp_engine *engine = create_test_engine_with_dynamics(48000, 2, 512, &config);
    assert_true(engine != NULL, "safety limiter creates");
    assert_true(dsp_engine_get_latency_frames(engine) == 0, "limiter has zero lookahead latency");

    float input[512] = {0};
    float output[512] = {0};
    for (size_t frame = 0; frame < 4; frame++) {
        input[frame * 2] = 1.2f;
        input[frame * 2 + 1] = -1.2f;
    }
    assert_true(
        dsp_engine_process(engine, input, 8, output, 8, 4, 2) == DSP_STATUS_OK,
        "linked limiter handles stereo DC");
    const double threshold = pow(10.0, -1.0 / 20.0);
    for (size_t index = 0; index < 8; index++) {
        assert_true(isfinite(output[index]), "limiter output remains finite");
        assert_true(fabs(output[index]) <= threshold + 1e-6, "sample peak stays below limiter threshold");
    }
    assert_near(output[0], -output[1], 1e-6, "linked limiter preserves anti-phase image");

    assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "limiter resets before sustained overload");
    const float initial_overload[] = {2.0f, -2.0f};
    float overload_output[2] = {0.0f};
    assert_true(dsp_engine_process(engine, initial_overload, 2, overload_output, 2, 1, 2) == DSP_STATUS_OK,
        "limiter processes an initial high peak");
    assert_near(fabs(overload_output[0]), threshold, 1e-6, "first overload frame reaches the hard ceiling");
    assert_near(fabs(overload_output[0]), fabs(overload_output[1]), 1e-6,
        "initial overload keeps linked stereo gain");

    float first_recovery_output = 0.0f;
    float last_recovery_output = 0.0f;
    int recovery_output_finite = 1;
    int recovery_stays_below_ceiling = 1;
    int recovery_preserves_stereo_image = 1;
    size_t recovery_remaining = 9600;
    while (recovery_remaining > 0) {
        const size_t frames = recovery_remaining < 512 ? recovery_remaining : 512;
        float steady_overload[1024];
        float recovered[1024];
        for (size_t frame = 0; frame < frames; frame++) {
            steady_overload[frame * 2] = 1.2f;
            steady_overload[frame * 2 + 1] = -1.2f;
        }
        assert_true(dsp_engine_process(engine, steady_overload, frames * 2, recovered, frames * 2,
            (int)frames, 2) == DSP_STATUS_OK, "limiter releases toward the required gain during overload");
        for (size_t frame = 0; frame < frames; frame++) {
            const float left = recovered[frame * 2];
            const float right = recovered[frame * 2 + 1];
            if (!isfinite(left) || !isfinite(right)) recovery_output_finite = 0;
            if (fabs(left) > threshold + 1e-6 || fabs(right) > threshold + 1e-6) {
                recovery_stays_below_ceiling = 0;
            }
            if (fabsf(left + right) > 1e-6f) recovery_preserves_stereo_image = 0;
            if (recovery_remaining == 9600 && frame == 0) first_recovery_output = fabsf(left);
            if (recovery_remaining <= frames && frame == frames - 1) last_recovery_output = fabsf(left);
        }
        recovery_remaining -= frames;
    }
    assert_true(recovery_output_finite, "sustained limiter output remains finite");
    assert_true(recovery_stays_below_ceiling,
        "sustained over-threshold input never exceeds the sample ceiling");
    assert_true(recovery_preserves_stereo_image, "sustained overload keeps linked stereo gain");
    assert_true(last_recovery_output > first_recovery_output + 0.25f,
        "limiter recovers toward the lower sustained peak while it remains over threshold");

    assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "limiter reset succeeds");
    for (size_t index = 0; index < 512; index++) {
        input[index] = 0.0f;
        output[index] = 0.0f;
    }
    input[0] = 1.2f;
    assert_true(
        dsp_engine_process(engine, input, 16, output, 16, 8, 2) == DSP_STATUS_OK,
        "limiter processes an impulse");
    assert_true(fabs(output[0]) <= threshold + 1e-6, "limiter caps impulse sample peak");
    for (size_t index = 1; index < 16; index++) {
        assert_near(output[index], 0.0, 0.0, "limiter impulse tail stays silent");
    }

    const float low_signal[] = {0.8f, -0.8f, 0.8f, -0.8f};
    assert_true(
        dsp_engine_process(engine, low_signal, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "limiter release begins below the threshold");
    assert_true(output[0] < 0.8f, "limiter release does not jump immediately to unity gain");
    assert_near(output[0], -output[1], 1e-6, "limiter release preserves stereo image");
    for (size_t block = 0; block < 64; block++) {
        float steady_input[512];
        for (size_t frame = 0; frame < 256; frame++) {
            steady_input[frame * 2] = 0.8f;
            steady_input[frame * 2 + 1] = -0.8f;
        }
        assert_true(
            dsp_engine_process(engine, steady_input, 512, output, 512, 256, 2) == DSP_STATUS_OK,
            "limiter release continues smoothly");
    }
    assert_near(output[510], 0.8, 0.001, "limiter release restores sub-threshold level");
    assert_near(output[511], -0.8, 0.001, "limiter release uses the same gain for both channels");

    assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "limiter resets before noise");
    uint32_t random_state = 0x12345678u;
    for (size_t frame = 0; frame < 256; frame++) {
        random_state = random_state * 1664525u + 1013904223u;
        input[frame * 2] = (float)(((int32_t)(random_state >> 8) - 8388608) / 2000000.0);
        random_state = random_state * 1664525u + 1013904223u;
        input[frame * 2 + 1] = (float)(((int32_t)(random_state >> 8) - 8388608) / 2000000.0);
    }
    assert_true(
        dsp_engine_process(engine, input, 512, output, 512, 256, 2) == DSP_STATUS_OK,
        "limiter processes deterministic noise");
    for (size_t index = 0; index < 512; index++) {
        assert_true(isfinite(output[index]), "noise output remains finite");
        assert_true(fabs(output[index]) <= threshold + 1e-6, "noise sample peak stays below threshold");
    }
    dsp_engine_destroy(engine);
}

static void design_low_shelf(double sample_rate, double frequency, double gain_db, double coefficients[5]) {
    const double pi = 3.14159265358979323846;
    const double omega = 2.0 * pi * frequency / sample_rate;
    const double cosine = cos(omega);
    const double sine = sin(omega);
    const double a = pow(10.0, gain_db / 40.0);
    const double alpha = sine / 2.0 * sqrt(2.0);
    const double beta = 2.0 * sqrt(a) * alpha;
    const double a0 = (a + 1.0) + (a - 1.0) * cosine + beta;
    coefficients[0] = a * ((a + 1.0) - (a - 1.0) * cosine + beta) / a0;
    coefficients[1] = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosine) / a0;
    coefficients[2] = a * ((a + 1.0) - (a - 1.0) * cosine - beta) / a0;
    coefficients[3] = -2.0 * ((a - 1.0) + (a + 1.0) * cosine) / a0;
    coefficients[4] = ((a + 1.0) + (a - 1.0) * cosine - beta) / a0;
}

static void design_high_pass(double sample_rate, double frequency, double q, double coefficients[5]) {
    const double pi = 3.14159265358979323846;
    const double omega = 2.0 * pi * frequency / sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    coefficients[0] = (1.0 + cosine) / (2.0 * a0);
    coefficients[1] = -(1.0 + cosine) / a0;
    coefficients[2] = (1.0 + cosine) / (2.0 * a0);
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
}

static double measure_sine_gain_with_amplitude(dsp_engine *engine, double frequency, int channels, double amplitude) {
    double input_square = 0.0;
    double output_square = 0.0;
    size_t frame = 0;
    while (frame < 48000) {
        const size_t frames = 48000 - frame < 512 ? 48000 - frame : 512;
        float input[1024];
        float output[1024];
        for (size_t index = 0; index < frames; index++) {
            const float sample = (float)(amplitude * sin(2.0 * 3.14159265358979323846 * frequency *
                (double)(frame + index) / 48000.0));
            input[index * (size_t)channels] = sample;
            if (channels == 2) input[index * 2 + 1] = -sample;
        }
        assert_true(
            dsp_engine_process(
                engine,
                input,
                frames * (size_t)channels,
                output,
                frames * (size_t)channels,
                (int)frames,
                channels) == DSP_STATUS_OK,
            "spatial frequency response block processes");
        for (size_t index = 0; index < frames; index++) {
            if (frame + index < 4096) continue;
            const double input_sample = input[index * (size_t)channels];
            const double output_sample = output[index * (size_t)channels];
            input_square += input_sample * input_sample;
            output_square += output_sample * output_sample;
        }
        frame += frames;
    }
    return sqrt(output_square / input_square);
}

static double measure_sine_gain(dsp_engine *engine, double frequency, int channels) {
    return measure_sine_gain_with_amplitude(engine, frequency, channels, 0.1);
}

static double measure_output_tone_component_at_rate_with_amplitude(
    dsp_engine *engine,
    int sample_rate,
    double input_frequency,
    double output_frequency,
    double input_amplitude) {
    double sine_sum = 0.0;
    double cosine_sum = 0.0;
    size_t measured = 0;
    size_t frame = 0;
    while (frame < (size_t)sample_rate) {
        const size_t frames = (size_t)sample_rate - frame < 512 ?
            (size_t)sample_rate - frame : 512;
        float input[1024];
        float output[1024];
        for (size_t index = 0; index < frames; index++) {
            const float sample = (float)(input_amplitude * sin(2.0 * 3.14159265358979323846 * input_frequency *
                (double)(frame + index) / sample_rate));
            input[index * 2] = sample;
            input[index * 2 + 1] = -sample;
        }
        assert_true(
            dsp_engine_process(engine, input, frames * 2, output, frames * 2, (int)frames, 2) == DSP_STATUS_OK,
            "harmonic alias measurement block processes");
        for (size_t index = 0; index < frames; index++) {
            const size_t absolute_frame = frame + index;
            if (absolute_frame < 4096) continue;
            const double phase = 2.0 * 3.14159265358979323846 * output_frequency *
                (double)absolute_frame / sample_rate;
            sine_sum += output[index * 2] * sin(phase);
            cosine_sum += output[index * 2] * cos(phase);
            measured++;
        }
        frame += frames;
    }
    return measured > 0 ? 2.0 * sqrt(sine_sum * sine_sum + cosine_sum * cosine_sum) / measured : 0.0;
}

static double measure_output_tone_component_at_rate(
    dsp_engine *engine,
    int sample_rate,
    double input_frequency,
    double output_frequency) {
    return measure_output_tone_component_at_rate_with_amplitude(
        engine, sample_rate, input_frequency, output_frequency, 0.5);
}

static double measure_output_tone_component(dsp_engine *engine, double input_frequency, double output_frequency) {
    return measure_output_tone_component_at_rate(engine, 48000, input_frequency, output_frequency);
}

static void test_spatial_width_and_mono_input_behavior(void) {
    const dsp_spatial_config mono_width = {
        .width = 0.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
    };
    dsp_engine *mono_engine = create_test_engine_with_spatial(48000, 2, 2, &mono_width, NULL, 0, NULL, 0);
    assert_true(mono_engine != NULL, "width zero graph creates");
    const float stereo[] = {1.0f, -1.0f, 0.5f, 0.5f};
    float output[4] = {0};
    assert_true(
        dsp_engine_process(mono_engine, stereo, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "width zero block processes");
    assert_near(output[0], 0.0, 0.0, "width zero removes anti-phase side");
    assert_near(output[1], 0.0, 0.0, "width zero produces mono left/right");
    assert_near(output[2], 0.5, 0.0, "width zero keeps mono input left");
    assert_near(output[3], 0.5, 0.0, "width zero keeps mono input right");
    dsp_engine_destroy(mono_engine);

    const dsp_spatial_config default_width = {
        .width = 1.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
    };
    dsp_engine *identity_engine = create_test_engine_with_spatial(
        48000, 2, 1, &default_width, NULL, 0, NULL, 0);
    assert_true(identity_engine != NULL, "width one graph creates");
    const float identity_input[] = {0.25f, -0.5f};
    float identity_output[2] = {0};
    assert_true(
        dsp_engine_process(identity_engine, identity_input, 2, identity_output, 2, 1, 2) == DSP_STATUS_OK,
        "width one block processes");
    assert_near(identity_output[0], identity_input[0], 0.0, "width one is identity left");
    assert_near(identity_output[1], identity_input[1], 0.0, "width one is identity right");
    dsp_engine_destroy(identity_engine);

    const dsp_spatial_config double_width = {
        .width = 2.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
    };
    dsp_engine *wide_engine = create_test_engine_with_spatial(48000, 2, 1, &double_width, NULL, 0, NULL, 0);
    assert_true(wide_engine != NULL, "width two graph creates");
    const float anti_phase[] = {0.5f, -0.5f};
    float wide_output[2] = {0};
    assert_true(
        dsp_engine_process(wide_engine, anti_phase, 2, wide_output, 2, 1, 2) == DSP_STATUS_OK,
        "width two anti-phase block processes");
    assert_near(wide_output[0], 1.0, 0.0, "width two doubles side left");
    assert_near(wide_output[1], -1.0, 0.0, "width two doubles side right");
    dsp_engine_destroy(wide_engine);

    const double high_pass[DSP_BIQUAD_COEFFICIENT_COUNT] = {
        1.0, 0.0, 0.0, 0.0, 0.0,
    };
    const dsp_spatial_config wide_mono = {
        .width = 2.0,
        .mono_bass_enabled = 1,
        .mono_bass_cutoff_hz = 120,
    };
    dsp_engine *mono_input_engine = create_test_engine_with_spatial(
        48000,
        1,
        2,
        &wide_mono,
        NULL,
        0,
        high_pass,
        1);
    assert_true(mono_input_engine != NULL, "mono input spatial config creates");
    const float mono_input[] = {0.25f, -0.5f};
    float mono_output[] = {0.0f, 0.0f};
    assert_true(
        dsp_engine_process(mono_input_engine, mono_input, 2, mono_output, 2, 2, 1) == DSP_STATUS_OK,
        "mono input bypasses width and mono bass");
    assert_near(mono_output[0], 0.25, 0.0, "mono width bypass sample zero");
    assert_near(mono_output[1], -0.5, 0.0, "mono width bypass sample one");
    dsp_engine_destroy(mono_input_engine);

    double mono_bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_high_pass(48000.0, 120.0, 0.7071067811865476, mono_bass_coefficients);
    dsp_engine *non_finite_engine = create_test_engine_with_spatial(
        48000,
        2,
        2,
        &wide_mono,
        NULL,
        0,
        mono_bass_coefficients,
        1);
    assert_true(non_finite_engine != NULL, "non-finite spatial graph creates");
    const float non_finite_input[] = {NAN, 0.5f, INFINITY, -INFINITY};
    float finite_output[4] = {0};
    assert_true(
        dsp_engine_process(non_finite_engine, non_finite_input, 4, finite_output, 4, 2, 2) == DSP_STATUS_OK,
        "spatial graph sanitizes non-finite input");
    for (size_t index = 0; index < 4; index++) {
        assert_true(isfinite(finite_output[index]), "spatial stage output remains finite");
    }
    dsp_engine_destroy(non_finite_engine);
}

static void test_spatial_seat_alignment_and_latency(void) {
    const dsp_spatial_config near_left = {
        .width = 1.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
        .seat_enabled = 1,
        .seat_near_speaker = DSP_SPATIAL_NEAR_LEFT,
        .seat_distance_difference_cm = 34300.0 * 2.0 / 48000.0,
    };
    dsp_engine *integer_delay = create_test_engine_with_spatial(
        48000, 2, 4, &near_left, NULL, 0, NULL, 0);
    assert_true(integer_delay != NULL, "left-speaker seat delay graph creates");
    assert_true(dsp_engine_get_latency_frames(integer_delay) == 0,
        "interchannel seat delay is excluded from common transport latency");
    assert_near(dsp_engine_get_interchannel_relative_delay_frames(integer_delay), 2.0, 0.0,
        "integer seat delay is reported as interchannel-relative latency");
    const float impulse[] = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    float output[8] = {0};
    assert_true(
        dsp_engine_process(integer_delay, impulse, 8, output, 8, 4, 2) == DSP_STATUS_OK,
        "integer seat delay processes stereo frames");
    assert_near(output[0], 0.0, 0.0, "near left is delayed at frame zero");
    assert_near(output[2], 0.0, 0.0, "near left is delayed at frame one");
    assert_near(output[4], 1.0, 1e-6, "near left arrives at the aligned frame");
    assert_true(dsp_engine_reset(integer_delay) == DSP_STATUS_OK, "seat delay ring resets");
    for (size_t index = 0; index < 8; index++) output[index] = 0.0f;
    assert_true(
        dsp_engine_process(integer_delay, impulse, 8, output, 8, 4, 2) == DSP_STATUS_OK,
        "reset seat delay processes a fresh block");
    assert_near(output[4], 1.0, 1e-6, "reset seat delay restarts from a clean ring");
    dsp_engine_destroy(integer_delay);

    dsp_spatial_config fractional = near_left;
    fractional.seat_distance_difference_cm = 34300.0 * 1.5 / 48000.0;
    dsp_engine *fractional_delay = create_test_engine_with_spatial(
        48000, 2, 3, &fractional, NULL, 0, NULL, 0);
    assert_true(fractional_delay != NULL, "fractional seat delay graph creates");
    assert_true(dsp_engine_get_latency_frames(fractional_delay) == 0,
        "fractional seat delay is excluded from common transport latency");
    assert_near(dsp_engine_get_interchannel_relative_delay_frames(fractional_delay), 1.5, 1e-6,
        "fractional seat delay retains its exact relative value");
    const float stereo_impulse[] = {1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    float fractional_output[6] = {0};
    assert_true(
        dsp_engine_process(fractional_delay, stereo_impulse, 6, fractional_output, 6, 3, 2) == DSP_STATUS_OK,
        "fractional seat delay processes stereo frames");
    assert_near(fractional_output[0], 0.0, 0.0, "fractional near channel starts with silence");
    assert_near(fractional_output[2], 1.0 / 3.0, 1e-6, "fractional allpass starts at the expected first tap");
    assert_near(fractional_output[4], 8.0 / 9.0, 1e-6, "fractional allpass preserves its recursive tail");
    assert_near(fractional_output[1], 1.0, 0.0, "far channel remains undelayed");
    dsp_engine_destroy(fractional_delay);

    dsp_spatial_config fractional_half_frame = near_left;
    fractional_half_frame.seat_distance_difference_cm = 34300.0 * 0.5 / 48000.0;
    const double frequencies[] = {1000.0, 5000.0, 10000.0, 18000.0, 20000.0};
    for (size_t index = 0; index < sizeof(frequencies) / sizeof(frequencies[0]); index++) {
        dsp_engine *allpass = create_test_engine_with_spatial(
            48000, 2, 512, &fractional_half_frame, NULL, 0, NULL, 0);
        assert_true(allpass != NULL, "fractional allpass response graph creates");
        assert_near(measure_sine_gain(allpass, frequencies[index], 2), 1.0, 1e-4,
            "fractional seat delay preserves near-speaker high-frequency magnitude");
        dsp_engine_destroy(allpass);
    }

    const dsp_spatial_config trims = {
        .width = 1.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
        .seat_enabled = 1,
        .seat_near_speaker = DSP_SPATIAL_NEAR_CENTER,
        .seat_left_trim_db = 6.0,
        .seat_right_trim_db = -6.0,
        .seat_invert_left = 1,
    };
    dsp_engine *trim_engine = create_test_engine_with_spatial(48000, 2, 1, &trims, NULL, 0, NULL, 0);
    assert_true(trim_engine != NULL, "seat trims and polarity graph creates");
    const float trim_input[] = {0.1f, 0.1f};
    float trim_output[2] = {0};
    assert_true(
        dsp_engine_process(trim_engine, trim_input, 2, trim_output, 2, 1, 2) == DSP_STATUS_OK,
        "seat trims and polarity process stereo frames");
    assert_near(trim_output[0], -0.1 * pow(10.0, 6.0 / 20.0), 1e-6,
        "left trim applies independent gain and polarity");
    assert_near(trim_output[1], 0.1 * pow(10.0, -6.0 / 20.0), 1e-6,
        "right trim applies independent gain");
    dsp_engine_destroy(trim_engine);

    dsp_engine *mono = create_test_engine_with_spatial(48000, 1, 1, &near_left, NULL, 0, NULL, 0);
    assert_true(mono != NULL, "mono stream safely ignores stereo seat alignment");
    assert_true(dsp_engine_get_latency_frames(mono) == 0, "mono seat alignment adds no common latency");
    assert_near(dsp_engine_get_interchannel_relative_delay_frames(mono), 0.0, 0.0,
        "mono seat alignment adds no interchannel delay");
    const float mono_sample[] = {0.25f};
    float mono_output[1] = {0};
    assert_true(
        dsp_engine_process(mono, mono_sample, 1, mono_output, 1, 1, 1) == DSP_STATUS_OK,
        "mono seat alignment bypass processes");
    assert_near(mono_output[0], mono_sample[0], 0.0, "mono seat alignment preserves the sample");
    dsp_engine_destroy(mono);

    const float unit_ir[] = {1.0f};
    const dsp_convolver_config unit_config = {1, 48000, 1, 1, 1, 1.0f, unit_ir};
    dsp_engine *combined = dsp_engine_create(
        48000, 2, 4, 0.0, NULL, 0, &bypass_dynamics, &bypass_multiband, &bypass_dynamic_eq,
        NULL, 0, NULL, 0, &near_left, &unit_config, &bypass_harmonic);
    assert_true(combined != NULL, "seat delay and convolver graph creates");
    assert_true(dsp_engine_get_latency_frames(combined) == DSP_CONVOLVER_PARTITION_FRAMES,
        "common transport latency includes the convolver only");
    assert_near(dsp_engine_get_interchannel_relative_delay_frames(combined), 2.0, 0.0,
        "relative seat delay remains separate from common convolver latency");
    dsp_engine_destroy(combined);

    dsp_spatial_config invalid = near_left;
    invalid.seat_distance_difference_cm = 171.51;
    dsp_engine *invalid_engine = create_test_engine_with_spatial(
        48000, 2, 4, &invalid, NULL, 0, NULL, 0);
    assert_true(invalid_engine == NULL, "seat delay beyond the five-millisecond cap is rejected");
}

static void test_spatial_bass_shelf_and_mono_bass_frequency_response(void) {
    double bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_low_shelf(48000.0, 80.0, 6.0, bass_coefficients);
    const dsp_spatial_config width_one = {
        .width = 1.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
    };
    dsp_engine *low_bass = create_test_engine_with_spatial(
        48000, 1, 512, &width_one, bass_coefficients, 1, NULL, 0);
    assert_true(low_bass != NULL, "low shelf graph creates");
    assert_near(measure_sine_gain(low_bass, 20.0, 1), pow(10.0, 6.0 / 20.0), 0.02,
        "bass shelf applies its low-frequency boost");
    dsp_engine_destroy(low_bass);
    low_bass = create_test_engine_with_spatial(
        48000, 1, 512, &width_one, bass_coefficients, 1, NULL, 0);
    assert_true(low_bass != NULL, "high-frequency bass shelf graph creates");
    assert_near(measure_sine_gain(low_bass, 20000.0, 1), 1.0, 0.02,
        "bass shelf leaves high frequencies unchanged");
    dsp_engine_destroy(low_bass);

    double high_pass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_high_pass(48000.0, 120.0, 0.7071067811865476, high_pass_coefficients);
    const dsp_spatial_config mono_bass = {
        .width = 1.0,
        .mono_bass_enabled = 1,
        .mono_bass_cutoff_hz = 120,
    };
    const double frequencies[] = {30.0, 60.0, 120.0, 1000.0};
    for (size_t index = 0; index < sizeof(frequencies) / sizeof(frequencies[0]); index++) {
        dsp_engine *engine = create_test_engine_with_spatial(
            48000, 2, 512, &mono_bass, NULL, 0, high_pass_coefficients, 1);
        assert_true(engine != NULL, "mono bass HPF graph creates");
        const double ratio = 120.0 / frequencies[index];
        const double expected = 1.0 / sqrt(1.0 + ratio * ratio * ratio * ratio);
        assert_near(
            measure_sine_gain(engine, frequencies[index], 2),
            expected,
            0.025,
            "mono bass filters side energy with a Butterworth slope");
        dsp_engine_destroy(engine);
    }
}

static dsp_spatial_config make_advanced_ms_config(void) {
    return (dsp_spatial_config) {
        .width = 1.0,
        .mono_bass_enabled = 0,
        .mono_bass_cutoff_hz = 120,
        .advanced_ms = {
            .enabled = 1,
            .crossover_hz = 250.0,
            .center_focus_db = 0.0,
            .mid_low_gain_db = 0.0,
            .mid_high_gain_db = 0.0,
            .side_low_gain_db = 0.0,
            .side_high_gain_db = 0.0,
            .low_band_width = 1.0,
            .high_band_width = 1.0,
            .side_compressor_enabled = 0,
            .side_compressor_threshold_db = -24.0,
            .side_compressor_ratio = 2.0,
            .side_compressor_attack_ms = 10.0,
            .side_compressor_release_ms = 100.0,
            .side_compressor_knee_db = 6.0,
        },
    };
}

static dsp_harmonic_config make_harmonic_config(void) {
    dsp_harmonic_config config = bypass_harmonic;
    config.enabled = 1;
    config.exciter_enabled = 1;
    config.exciter_amount = 0.25;
    config.exciter_cutoff_hz = 4000.0;
    return config;
}

static void test_safety_peak_levels_remain_finite_with_enabled_stages(void) {
    const int sample_rate = 48000;
    const size_t frames = 512;
    const double peak_levels[] = {0.001, 0.05, 0.45, 0.9};
    const float impulse[] = {1.0f};
    double peq_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    double bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    double mono_bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(sample_rate, 1000.0, 3.0, 1.0, peq_coefficients);
    design_test_peak_coefficients(sample_rate, 80.0, 3.0, 0.7, bass_coefficients);
    design_test_peak_coefficients(sample_rate, 120.0, 2.0, 0.7, mono_bass_coefficients);

    dsp_dynamics_config dynamics = bypass_dynamics;
    dynamics.compressor_enabled = 1;
    dynamics.compressor_threshold_db = -24.0;
    dynamics.compressor_ratio = 3.0;
    dynamics.compressor_attack_ms = 10.0;
    dynamics.compressor_release_ms = 100.0;
    dynamics.compressor_knee_db = 6.0;
    dynamics.compressor_makeup_db = 1.0;
    dynamics.limiter_enabled = 1;
    dynamics.limiter_threshold_db = -1.0;
    dynamics.limiter_release_ms = 60.0;

    dsp_multiband_config multiband = bypass_multiband;
    multiband.enabled = 1;
    for (size_t band = 0; band < DSP_MULTIBAND_COUNT; band++) {
        multiband.bands[band] = (dsp_multiband_compressor_config) {
            1, -28.0, 2.0, 10.0, 100.0, 6.0, 0.5,
        };
    }

    dsp_dynamic_eq_config dynamic_eq = bypass_dynamic_eq;
    dynamic_eq.enabled = 1;
    dynamic_eq.bands[0].enabled = 1;
    dynamic_eq.bands[0].mode = DSP_DYNAMIC_EQ_CUT;
    dynamic_eq.bands[0].frequency_hz = 120.0;
    dynamic_eq.bands[0].q = 1.0;
    dynamic_eq.bands[0].threshold_db = -24.0;
    dynamic_eq.bands[0].ratio = 2.0;
    dynamic_eq.bands[0].attack_ms = 10.0;
    dynamic_eq.bands[0].release_ms = 100.0;
    dynamic_eq.bands[0].max_boost_db = 6.0;
    dynamic_eq.bands[0].max_cut_db = 6.0;

    dsp_spatial_config spatial = make_advanced_ms_config();
    spatial.width = 1.2;
    spatial.mono_bass_enabled = 1;
    spatial.seat_enabled = 1;
    spatial.seat_near_speaker = DSP_SPATIAL_NEAR_CENTER;
    spatial.seat_left_trim_db = -1.0;
    spatial.seat_right_trim_db = -1.0;
    const dsp_convolver_config convolver = {
        1, sample_rate, 1, 1, 1, 0.25f, impulse,
    };
    const dsp_harmonic_config harmonic = make_harmonic_config();
    const dsp_macro_config macro = {1, 2.0, 1.0, 1.0, 1.1};
    const dsp_smart_loudness_config loudness = {1, 2.0, 1.0};
    const dsp_dynamic_bass_config dynamic_bass = {
        1, 1.0, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0,
    };
    dsp_engine *engine = dsp_engine_create_with_studio(
        sample_rate, 2, (int)frames, -6.0, peq_coefficients, 1, &dynamics, &multiband,
        &dynamic_eq, bass_coefficients, 1, mono_bass_coefficients, 1, &spatial, &convolver,
        &harmonic, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "combined safety peak graph creates");

    float input[1024] = {0.0f};
    float output[1024] = {0.0f};
    const double limiter_ceiling = pow(10.0, dynamics.limiter_threshold_db / 20.0);
    for (size_t level_index = 0; level_index < sizeof(peak_levels) / sizeof(peak_levels[0]); level_index++) {
        assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "safety peak graph resets between input levels");
        double maximum_output = 0.0;
        for (size_t frame = 0; frame < frames; frame++) {
            const float sample = (float)(peak_levels[level_index] *
                sin(2.0 * 3.14159265358979323846 * 997.0 * (double)frame / (double)sample_rate));
            input[frame * 2] = sample;
            input[frame * 2 + 1] = -sample;
        }
        assert_true(dsp_engine_process(engine, input, frames * 2, output, frames * 2,
            (int)frames, 2) == DSP_STATUS_OK, "combined safety peak block processes");
        for (size_t sample = 0; sample < frames * 2; sample++) {
            assert_true(isfinite(output[sample]), "combined safety output remains finite");
            const double absolute_output = fabs((double)output[sample]);
            if (absolute_output > maximum_output) maximum_output = absolute_output;
        }
        assert_true(maximum_output <= limiter_ceiling + 1e-4,
            "combined processing stays below the sample limiter ceiling");
    }
    dsp_engine_destroy(engine);
}

static void test_harmonic_engine_latency_oversampling_modes_and_alias_rejection(void) {
    const float identity_input[] = {0.25f, -0.5f, 0.75f, -1.0f};
    float identity_output[4] = {0};
    dsp_engine *legacy = create_test_engine(48000, 2, 2, 0.0, NULL, 0);
    dsp_engine *disabled = create_test_engine_with_harmonic(48000, 2, 2, &bypass_harmonic);
    assert_true(legacy != NULL && disabled != NULL, "disabled harmonic reference graphs create");
    assert_true(dsp_engine_process(legacy, identity_input, 4, identity_output, 4, 2, 2) == DSP_STATUS_OK,
        "legacy DSP reference block processes");
    float disabled_output[4] = {0};
    assert_true(dsp_engine_process(disabled, identity_input, 4, disabled_output, 4, 2, 2) == DSP_STATUS_OK,
        "disabled harmonic block processes");
    for (size_t index = 0; index < 4; index++) {
        assert_near(disabled_output[index], identity_output[index], 0.0,
            "disabled harmonic preserves the existing PCM-float path");
    }
    assert_true(dsp_engine_get_latency_frames(disabled) == 0, "disabled harmonic adds no latency");
    dsp_engine_destroy(legacy);
    dsp_engine_destroy(disabled);

    const int qualities[] = {9, 17, 33};
    const int expected_latencies[] = {4, 8, 16};
    for (size_t index = 0; index < sizeof(qualities) / sizeof(qualities[0]); index++) {
        dsp_harmonic_config config = make_harmonic_config();
        config.taps = qualities[index];
        dsp_engine *engine = create_test_engine_with_harmonic(48000, 2, 32, &config);
        assert_true(engine != NULL, "quality-specific harmonic graph creates");
        assert_true(dsp_engine_get_latency_frames(engine) == expected_latencies[index],
            "2x FIR group delay is reported in input frames");
        dsp_engine_destroy(engine);
    }

    dsp_harmonic_config chunk_config = make_harmonic_config();
    chunk_config.taps = 17;
    dsp_engine *whole = create_test_engine_with_harmonic(48000, 2, 256, &chunk_config);
    dsp_engine *split = create_test_engine_with_harmonic(48000, 2, 256, &chunk_config);
    assert_true(whole != NULL && split != NULL, "harmonic chunk-invariance graphs create");
    float input[512];
    float whole_output[512] = {0};
    float split_output[512] = {0};
    for (size_t frame = 0; frame < 256; frame++) {
        const float sample = (float)(0.45 * sin(2.0 * 3.14159265358979323846 * 997.0 * frame / 48000.0));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = -sample;
    }
    assert_true(dsp_engine_process(whole, input, 512, whole_output, 512, 256, 2) == DSP_STATUS_OK,
        "whole harmonic block processes");
    assert_true(dsp_engine_process(split, input, 154, split_output, 154, 77, 2) == DSP_STATUS_OK,
        "first harmonic split processes");
    assert_true(dsp_engine_process(split, input + 154, 358, split_output + 154, 358, 179, 2) == DSP_STATUS_OK,
        "second harmonic split processes");
    for (size_t index = 0; index < 512; index++) {
        assert_near(whole_output[index], split_output[index], 1e-7, "harmonic output is block invariant");
    }
    dsp_engine_destroy(whole);
    dsp_engine_destroy(split);

    dsp_harmonic_config even_config = make_harmonic_config();
    even_config.mode = 0;
    even_config.taps = 33;
    even_config.exciter_cutoff_hz = 1000.0;
    dsp_engine *even_engine = create_test_engine_with_harmonic(48000, 2, 512, &even_config);
    dsp_harmonic_config odd_config = even_config;
    odd_config.mode = 1;
    dsp_engine *odd_engine = create_test_engine_with_harmonic(48000, 2, 512, &odd_config);
    assert_true(even_engine != NULL && odd_engine != NULL, "even and odd harmonic graphs create");
    assert_true(measure_output_tone_component(even_engine, 1000.0, 2000.0) > 0.005,
        "even mode creates a measured second harmonic");
    assert_true(measure_output_tone_component(odd_engine, 1000.0, 3000.0) > 0.001,
        "odd mode creates a measured third harmonic");
    dsp_engine_destroy(even_engine);
    dsp_engine_destroy(odd_engine);

    dsp_harmonic_config alias_config = make_harmonic_config();
    alias_config.mode = 1;
    alias_config.taps = 33;
    alias_config.exciter_cutoff_hz = 4000.0;
    dsp_engine *alias_engine = create_test_engine_with_harmonic(48000, 2, 512, &alias_config);
    assert_true(alias_engine != NULL, "high-quality harmonic alias graph creates");
    const double alias_level = measure_output_tone_component(alias_engine, 14000.0, 6000.0);
    const double fundamental_level = measure_output_tone_component(alias_engine, 14000.0, 14000.0);
    assert_true(alias_level < fundamental_level * 0.03,
        "2x FIR oversampling rejects the folded third-harmonic alias");
    dsp_engine_destroy(alias_engine);

    const int sample_rates[] = {44100, 48000};
    const int alias_taps[] = {9, 17, 33};
    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        const double input_frequency = sample_rate == 44100 ? 13500.0 : 15000.0;
        for (int mode = 0; mode <= 2; mode++) {
            for (size_t quality_index = 0; quality_index < sizeof(alias_taps) / sizeof(alias_taps[0]); quality_index++) {
                dsp_harmonic_config alias_sweep = make_harmonic_config();
                alias_sweep.mode = mode;
                alias_sweep.taps = alias_taps[quality_index];
                alias_sweep.exciter_amount = 0.5;
                alias_sweep.exciter_cutoff_hz = 6000.0;
                dsp_engine *swept_engine = create_test_engine_with_harmonic(sample_rate, 2, 512, &alias_sweep);
                assert_true(swept_engine != NULL, "harmonic alias sweep graph creates");
                const double harmonic_order = mode == 0 ? 2.0 : mode == 1 ? 3.0 : 2.0;
                const double folded_alias = fabs(sample_rate - harmonic_order * input_frequency);
                const double swept_alias_level = measure_output_tone_component_at_rate(
                    swept_engine, sample_rate, input_frequency, folded_alias);
                const double swept_fundamental_level = measure_output_tone_component_at_rate(
                    swept_engine, sample_rate, input_frequency, input_frequency);
                assert_true(swept_fundamental_level > 1e-4,
                    "harmonic alias sweep retains a measurable fundamental");
                assert_true(swept_alias_level < swept_fundamental_level * 0.05,
                    "ADAA harmonic processing keeps folded alias below -26 dBc across modes and FIR qualities");
                dsp_engine_destroy(swept_engine);
            }
        }
    }

    const double exciter_frequencies[] = {1000.0, 8000.0, 12000.0, 15000.0};
    const double fundamental_amplitudes[] = {0.001, 0.05, 0.45, 0.9};
    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        for (int mode = 0; mode <= 2; mode++) {
            for (size_t quality_index = 0; quality_index < sizeof(alias_taps) / sizeof(alias_taps[0]); quality_index++) {
                dsp_harmonic_config exciter = make_harmonic_config();
                exciter.mode = mode;
                exciter.taps = alias_taps[quality_index];
                exciter.exciter_amount = 0.5;
                exciter.exciter_cutoff_hz = 2000.0;
                exciter.level_match_enabled = 0;
                dsp_engine *engine = create_test_engine_with_harmonic(sample_rate, 2, 512, &exciter);
                assert_true(engine != NULL, "harmonic exciter gain-sweep graph creates");
                for (size_t amplitude_index = 0;
                    amplitude_index < sizeof(fundamental_amplitudes) / sizeof(fundamental_amplitudes[0]);
                    amplitude_index++) {
                    const double amplitude = fundamental_amplitudes[amplitude_index];
                    for (size_t frequency_index = 0;
                        frequency_index < sizeof(exciter_frequencies) / sizeof(exciter_frequencies[0]);
                        frequency_index++) {
                        const double frequency = exciter_frequencies[frequency_index];
                        const double fundamental = measure_output_tone_component_at_rate_with_amplitude(
                            engine, sample_rate, frequency, frequency, amplitude);
                        if (amplitude <= 0.05) {
                            assert_near(fundamental, amplitude, amplitude * 0.05,
                                "low-level harmonic exciter preserves the original fundamental");
                        } else {
                            assert_true(fundamental >= amplitude * 0.95,
                                "high-level harmonic exciter does not attenuate the original fundamental");
                        }
                    }
                }
                dsp_engine_destroy(engine);
            }
        }
    }

    const double maximum_level_frequencies[] = {
        13500.0, 15000.0, 17000.0, 18500.0, 19500.0, 20000.0, 20400.0,
    };
    const double exciter_cutoffs[] = {6000.0, 12000.0, 18000.0};
    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        for (size_t frequency_index = 0;
            frequency_index < sizeof(maximum_level_frequencies) / sizeof(maximum_level_frequencies[0]);
            frequency_index++) {
            const double input_frequency = maximum_level_frequencies[frequency_index];
            if (input_frequency >= sample_rate * 0.5) continue;
            for (size_t cutoff_index = 0;
                cutoff_index < sizeof(exciter_cutoffs) / sizeof(exciter_cutoffs[0]);
                cutoff_index++) {
                for (int mode = 0; mode <= 2; mode++) {
                    for (size_t quality_index = 0;
                        quality_index < sizeof(alias_taps) / sizeof(alias_taps[0]);
                        quality_index++) {
                        dsp_harmonic_config max_level = make_harmonic_config();
                        max_level.mode = mode;
                        max_level.taps = alias_taps[quality_index];
                        max_level.exciter_amount = 0.5;
                        max_level.exciter_cutoff_hz = exciter_cutoffs[cutoff_index];
                        max_level.level_match_enabled = 0;
                        dsp_engine *engine = create_test_engine_with_harmonic(sample_rate, 2, 512, &max_level);
                        assert_true(engine != NULL, "maximum-level harmonic graph creates");
                        const double fundamental = measure_output_tone_component_at_rate_with_amplitude(
                            engine, sample_rate, input_frequency, input_frequency, 0.9);
                        const int first_order = mode == 1 ? 3 : 2;
                        const int last_order = mode == 2 ? 3 : first_order;
                        for (int harmonic_order = first_order; harmonic_order <= last_order; harmonic_order++) {
                            const double alias_frequency = fabs(sample_rate - harmonic_order * input_frequency);
                            const double alias = measure_output_tone_component_at_rate_with_amplitude(
                                engine, sample_rate, input_frequency, alias_frequency, 0.9);
                            char alias_message[256];
                            snprintf(alias_message, sizeof(alias_message),
                                "upper-band alias Fs=%d input=%.0fHz cutoff=%.0fHz mode=%d taps=%d order=%d ratio=%.5f",
                                sample_rate, input_frequency, exciter_cutoffs[cutoff_index], mode,
                                alias_taps[quality_index], harmonic_order,
                                fundamental > 0.0 ? alias / fundamental : INFINITY);
                            assert_true(alias < fundamental * 0.05, alias_message);
                        }
                        dsp_engine_destroy(engine);
                    }
                }
            }
        }
    }

    const float unit_ir[] = {1.0f};
    const dsp_convolver_config unit_config = {1, 48000, 1, 1, 1, 1.0f, unit_ir};
    dsp_harmonic_config convolved_config = make_harmonic_config();
    convolved_config.taps = 17;
    dsp_engine *combined = dsp_engine_create(
        48000, 2, 32, 0.0, NULL, 0, &bypass_dynamics, &bypass_multiband, &bypass_dynamic_eq,
        NULL, 0, NULL, 0, &bypass_spatial, &unit_config, &convolved_config);
    assert_true(combined != NULL, "harmonic and convolver graph creates");
    assert_true(dsp_engine_get_latency_frames(combined) == DSP_CONVOLVER_PARTITION_FRAMES + 8,
        "harmonic FIR latency sums with convolver latency");
    dsp_engine_destroy(combined);
}

static void test_advanced_ms_crossover_mono_side_dynamics_and_limiter(void) {
    dsp_spatial_config neutral = make_advanced_ms_config();
    dsp_spatial_config legacy_identity = neutral;
    legacy_identity.advanced_ms.enabled = 0;
    dsp_engine *identity_engine = create_test_engine_with_spatial(48000, 2, 512, &legacy_identity, NULL, 0, NULL, 0);
    dsp_engine *neutral_engine = create_test_engine_with_spatial(48000, 2, 512, &neutral, NULL, 0, NULL, 0);
    assert_true(identity_engine != NULL && neutral_engine != NULL, "neutral M/S identity graphs create");
    float identity_input[1024];
    float identity_output[1024] = {0};
    float neutral_output[1024] = {0};
    for (size_t frame = 0; frame < 512; frame++) {
        identity_input[frame * 2] = (float)(0.25 * sin(2.0 * 3.14159265358979323846 * 1300.0 * frame / 48000.0));
        identity_input[frame * 2 + 1] = (float)(0.2 * cos(2.0 * 3.14159265358979323846 * 4700.0 * frame / 48000.0));
    }
    assert_true(dsp_engine_process(identity_engine, identity_input, 1024, identity_output, 1024, 512, 2) == DSP_STATUS_OK,
        "legacy identity spatial graph processes");
    assert_true(dsp_engine_process(neutral_engine, identity_input, 1024, neutral_output, 1024, 512, 2) == DSP_STATUS_OK,
        "neutral M/S identity graph processes");
    for (size_t index = 0; index < 1024; index++) {
        assert_near(neutral_output[index], identity_output[index], 0.0,
            "neutral Advanced M/S is byte-exact to the legacy identity path");
    }
    dsp_engine_destroy(identity_engine);
    dsp_engine_destroy(neutral_engine);

    dsp_engine *engine = create_test_engine_with_spatial(48000, 2, 512, &neutral, NULL, 0, NULL, 0);
    assert_true(engine != NULL, "advanced M/S neutral graph creates");
    const double frequencies[] = {30.0, 60.0, 120.0, 250.0, 500.0, 2000.0, 10000.0, 20000.0};
    for (size_t index = 0; index < sizeof(frequencies) / sizeof(frequencies[0]); index++) {
        assert_near(measure_sine_gain(engine, frequencies[index], 2), 1.0, 0.025,
            "neutral LR4 crossover has flat reconstructed magnitude");
        assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "M/S crossover state resets between tones");
    }
    dsp_engine_destroy(engine);

    dsp_spatial_config band_width = make_advanced_ms_config();
    band_width.width = 0.0;
    band_width.advanced_ms.low_band_width = 0.0;
    band_width.advanced_ms.high_band_width = 1.0;
    engine = create_test_engine_with_spatial(48000, 2, 512, &band_width, NULL, 0, NULL, 0);
    assert_true(engine != NULL, "band-dependent M/S width graph creates");
    assert_true(measure_sine_gain(engine, 60.0, 2) < 0.04,
        "low Side energy is collapsed by the low-band width control");
    assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "M/S width graph resets before high-band tone");
    assert_true(measure_sine_gain(engine, 4000.0, 2) > 0.95,
        "high Side energy remains at unity width");
    dsp_engine_destroy(engine);

    engine = create_test_engine_with_spatial(48000, 2, 4, &band_width, NULL, 0, NULL, 0);
    assert_true(engine != NULL, "M/S mono-compatibility graph creates");
    const float mono_input[] = {0.1f, 0.1f, -0.2f, -0.2f, 0.3f, 0.3f, -0.4f, -0.4f};
    float mono_output[8] = {0};
    assert_true(dsp_engine_process(engine, mono_input, 8, mono_output, 8, 4, 2) == DSP_STATUS_OK,
        "mono-compatible M/S block processes");
    for (size_t frame = 0; frame < 4; frame++) {
        assert_near(mono_output[frame * 2], mono_output[frame * 2 + 1], 1e-7,
            "Side width controls do not create output from mono input");
    }
    dsp_engine_destroy(engine);

    engine = create_test_engine_with_spatial(48000, 2, 4, &band_width, NULL, 0, NULL, 0);
    assert_true(engine != NULL, "M/S antiphase graph creates");
    const float antiphase_input[] = {0.1f, -0.1f, -0.2f, 0.2f, 0.3f, -0.3f, -0.4f, 0.4f};
    float antiphase_output[8] = {0};
    assert_true(dsp_engine_process(engine, antiphase_input, 8, antiphase_output, 8, 4, 2) == DSP_STATUS_OK,
        "antiphase-compatible M/S block processes");
    for (size_t frame = 0; frame < 4; frame++) {
        assert_near(antiphase_output[frame * 2], -antiphase_output[frame * 2 + 1], 1e-7,
            "matched M/S crossover phase preserves antiphase separation");
    }
    dsp_engine_destroy(engine);

    dsp_spatial_config compressed = make_advanced_ms_config();
    compressed.advanced_ms.side_compressor_enabled = 1;
    compressed.advanced_ms.side_compressor_threshold_db = -30.0;
    compressed.advanced_ms.side_compressor_ratio = 4.0;
    dsp_engine *side_compressor = create_test_engine_with_spatial(
        48000, 2, 512, &compressed, NULL, 0, NULL, 0);
    engine = create_test_engine_with_spatial(48000, 2, 512, &neutral, NULL, 0, NULL, 0);
    assert_true(side_compressor != NULL && engine != NULL, "Side compressor and reference graphs create");
    const double compressed_gain = measure_sine_gain_with_amplitude(side_compressor, 1000.0, 2, 0.8);
    const double uncompressed_gain = measure_sine_gain_with_amplitude(engine, 1000.0, 2, 0.8);
    assert_true(compressed_gain < uncompressed_gain * 0.75,
        "optional Side compressor reduces sustained over-threshold Side energy");
    dsp_engine_destroy(side_compressor);
    dsp_engine_destroy(engine);

    dsp_spatial_config wide = make_advanced_ms_config();
    wide.advanced_ms.low_band_width = 2.0;
    wide.advanced_ms.high_band_width = 2.0;
    dsp_dynamics_config limiter = bypass_dynamics;
    limiter.limiter_enabled = 1;
    limiter.limiter_threshold_db = -1.0;
    dsp_engine *limited = dsp_engine_create(
        48000, 2, 16, 0.0, NULL, 0, &limiter, &bypass_multiband, &bypass_dynamic_eq,
        NULL, 0, NULL, 0, &wide, &bypass_convolver, &bypass_harmonic);
    assert_true(limited != NULL, "advanced M/S with final limiter graph creates");
    const float antiphase[] = {0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -0.8f};
    float limited_output[8] = {0};
    assert_true(dsp_engine_process(limited, antiphase, 8, limited_output, 8, 4, 2) == DSP_STATUS_OK,
        "wide antiphase Side reaches the final limiter");
    for (size_t index = 0; index < 8; index++) {
        assert_true(fabsf(limited_output[index]) <= powf(10.0f, -1.0f / 20.0f) + 1e-5f,
            "final limiter constrains M/S width overshoot");
    }
    dsp_engine_destroy(limited);

    dsp_spatial_config invalid = make_advanced_ms_config();
    invalid.advanced_ms.crossover_hz = 1001.0;
    assert_true(create_test_engine_with_spatial(48000, 2, 8, &invalid, NULL, 0, NULL, 0) == NULL,
        "M/S crossover outside the configured range is rejected");
}

static void test_advanced_ms_legacy_migration_is_a_starting_point(void) {
    double mono_bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_high_pass(48000.0, 120.0, 0.7071067811865476, mono_bass_coefficients);
    const dsp_spatial_config legacy = {
        .width = 1.5,
        .mono_bass_enabled = 1,
        .mono_bass_cutoff_hz = 120,
    };
    dsp_spatial_config migrated = make_advanced_ms_config();
    migrated.width = 0.0;
    migrated.advanced_ms.crossover_hz = 120.0;
    migrated.advanced_ms.low_band_width = 0.0;
    migrated.advanced_ms.high_band_width = 1.5;
    dsp_engine *legacy_engine = create_test_engine_with_spatial(
        48000, 2, 512, &legacy, NULL, 0, mono_bass_coefficients, 1);
    dsp_engine *migrated_engine = create_test_engine_with_spatial(
        48000, 2, 512, &migrated, NULL, 0, NULL, 0);
    assert_true(legacy_engine != NULL && migrated_engine != NULL, "legacy and migrated M/S graphs create");

    const double legacy_at_crossover = measure_sine_gain_with_amplitude(legacy_engine, 120.0, 2, 0.1);
    const double migrated_at_crossover = measure_sine_gain_with_amplitude(migrated_engine, 120.0, 2, 0.1);
    assert_true(legacy_at_crossover > migrated_at_crossover * 1.25,
        "legacy Mono Bass and migrated LR4 response differ at the crossover as documented");
    assert_true(dsp_engine_reset(legacy_engine) == DSP_STATUS_OK, "legacy Mono Bass resets before high-band tone");
    assert_true(dsp_engine_reset(migrated_engine) == DSP_STATUS_OK, "migrated M/S resets before high-band tone");
    const double legacy_at_500 = measure_sine_gain_with_amplitude(legacy_engine, 500.0, 2, 0.1);
    const double migrated_at_500 = measure_sine_gain_with_amplitude(migrated_engine, 500.0, 2, 0.1);
    assert_near(migrated_at_500, legacy_at_500, 0.08,
        "legacy-seeded high-band width remains close above the crossover");
    dsp_engine_destroy(legacy_engine);
    dsp_engine_destroy(migrated_engine);
}

static void test_multiband_unity_reconstruction_and_phase(void) {
    dsp_multiband_config config = bypass_multiband;
    config.enabled = 1;
    dsp_engine *engine = create_test_engine_with_multiband(48000, 1, 512, &config);
    assert_true(engine != NULL, "three-band unity graph creates");
    const double frequencies[] = {30.0, 60.0, 120.0, 250.0, 1000.0, 2500.0, 5000.0, 10000.0, 20000.0};
    for (size_t index = 0; index < sizeof(frequencies) / sizeof(frequencies[0]); index++) {
        assert_near(
            measure_sine_gain(engine, frequencies[index], 1),
            1.0,
            0.025,
            "unity bands recombine with a flat frequency response");
        assert_true(dsp_engine_reset(engine) == DSP_STATUS_OK, "unity crossover state resets between tones");
    }
    dsp_engine_destroy(engine);

    engine = create_test_engine_with_multiband(48000, 1, 512, &config);
    assert_true(engine != NULL, "three-band impulse graph creates");
    double impulse_energy = 0.0;
    for (size_t block = 0; block < 32; block++) {
        float input[512] = {0.0f};
        float output[512] = {0.0f};
        if (block == 0) input[0] = 1.0f;
        assert_true(
            dsp_engine_process(engine, input, 512, output, 512, 512, 1) == DSP_STATUS_OK,
            "multiband impulse response block processes");
        for (size_t frame = 0; frame < 512; frame++) {
            assert_true(isfinite(output[frame]), "multiband impulse response stays finite");
            impulse_energy += (double)output[frame] * (double)output[frame];
        }
    }
    assert_near(impulse_energy, 1.0, 0.03, "unity LR4 crossover preserves impulse energy");
    dsp_engine_destroy(engine);
}

static void test_multiband_compression_is_band_selective(void) {
    dsp_multiband_config low_only = bypass_multiband;
    low_only.enabled = 1;
    low_only.bands[0] = (dsp_multiband_compressor_config){1, -30.0, 4.0, 0.1, 80.0, 0.0, 0.0};
    dsp_engine *engine = create_test_engine_with_multiband(48000, 1, 512, &low_only);
    assert_true(engine != NULL, "low-band compressor graph creates");
    assert_true(measure_sine_gain(engine, 60.0, 1) < 0.75, "low compressor reduces the low band");
    assert_true(measure_sine_gain(engine, 5000.0, 1) > 0.95, "low compressor leaves the high band unchanged");
    dsp_engine_destroy(engine);

    dsp_multiband_config mid_only = bypass_multiband;
    mid_only.enabled = 1;
    mid_only.bands[1] = (dsp_multiband_compressor_config){1, -30.0, 4.0, 0.1, 80.0, 0.0, 0.0};
    engine = create_test_engine_with_multiband(48000, 1, 512, &mid_only);
    assert_true(engine != NULL, "mid-band compressor graph creates");
    assert_true(measure_sine_gain(engine, 1000.0, 1) < 0.75, "mid compressor reduces the mid band");
    assert_true(measure_sine_gain(engine, 60.0, 1) > 0.95, "mid compressor leaves the low band unchanged");
    dsp_engine_destroy(engine);

    dsp_multiband_config high_only = bypass_multiband;
    high_only.enabled = 1;
    high_only.bands[2] = (dsp_multiband_compressor_config){1, -30.0, 4.0, 0.1, 80.0, 0.0, 0.0};
    engine = create_test_engine_with_multiband(48000, 1, 512, &high_only);
    assert_true(engine != NULL, "high-band compressor graph creates");
    assert_true(measure_sine_gain(engine, 10000.0, 1) < 0.75, "high compressor reduces the high band");
    assert_true(measure_sine_gain(engine, 1000.0, 1) > 0.95, "high compressor leaves the mid band unchanged");
    dsp_engine_destroy(engine);
}

static void test_multiband_chunk_invariance_reset_and_validation(void) {
    dsp_multiband_config config = bypass_multiband;
    config.enabled = 1;
    config.bands[0] = (dsp_multiband_compressor_config){1, -24.0, 3.0, 3.0, 90.0, 3.0, 1.0};
    dsp_engine *whole = create_test_engine_with_multiband(48000, 2, 512, &config);
    dsp_engine *split = create_test_engine_with_multiband(48000, 2, 512, &config);
    assert_true(whole != NULL && split != NULL, "chunk comparison multiband engines create");
    float input[1024];
    float whole_output[1024] = {0};
    float split_output[1024] = {0};
    for (size_t index = 0; index < 1024; index++) {
        input[index] = (float)(0.3 * sin((double)index * 0.071) + 0.15 * cos((double)index * 0.023));
    }
    assert_true(
        dsp_engine_process(whole, input, 1024, whole_output, 1024, 512, 2) == DSP_STATUS_OK,
        "whole multiband block processes");
    const size_t chunks[] = {97, 1, 127, 64, 191, 32};
    size_t offset = 0;
    for (size_t chunk = 0; chunk < sizeof(chunks) / sizeof(chunks[0]); chunk++) {
        const size_t frames = chunks[chunk];
        assert_true(
            dsp_engine_process(
                split,
                input + offset * 2,
                frames * 2,
                split_output + offset * 2,
                frames * 2,
                (int)frames,
                2) == DSP_STATUS_OK,
            "split multiband block processes");
        offset += frames;
    }
    assert_true(offset == 512, "multiband chunks cover the same source frames");
    for (size_t index = 0; index < 1024; index++) {
        assert_near(whole_output[index], split_output[index], 1e-6, "multiband compressor is chunk invariant");
    }

    float burst[512];
    float output[512];
    for (size_t index = 0; index < 256; index++) {
        burst[index * 2] = index % 2 == 0 ? 0.8f : -0.8f;
        burst[index * 2 + 1] = -burst[index * 2];
    }
    assert_true(dsp_engine_process(whole, burst, 512, output, 512, 256, 2) == DSP_STATUS_OK,
        "multiband detector state is primed");
    assert_true(dsp_engine_reset(whole) == DSP_STATUS_OK, "multiband graph reset succeeds");
    dsp_engine *fresh = create_test_engine_with_multiband(48000, 2, 512, &config);
    assert_true(fresh != NULL, "fresh multiband graph creates");
    const float mono_test[] = {0.25f, -0.25f, 0.1f, -0.1f};
    float reset_output[4] = {0.0f};
    float fresh_output[4] = {0.0f};
    assert_true(dsp_engine_process(whole, mono_test, 4, reset_output, 4, 2, 2) == DSP_STATUS_OK,
        "reset multiband graph processes a fresh block");
    assert_true(dsp_engine_process(fresh, mono_test, 4, fresh_output, 4, 2, 2) == DSP_STATUS_OK,
        "fresh multiband graph processes a fresh block");
    for (size_t index = 0; index < 4; index++) {
        assert_near(reset_output[index], fresh_output[index], 1e-6,
            "multiband reset matches a freshly prepared graph");
    }
    dsp_engine_destroy(whole);
    dsp_engine_destroy(split);
    dsp_engine_destroy(fresh);

    config.low_mid_crossover_hz = 3000.0;
    config.mid_high_crossover_hz = 2500.0;
    assert_true(create_test_engine_with_multiband(48000, 1, 64, &config) == NULL,
        "reversed multiband crossovers are rejected");
    config = bypass_multiband;
    config.enabled = 1;
    config.mid_high_crossover_hz = 22000.0;
    assert_true(create_test_engine_with_multiband(48000, 1, 64, &config) == NULL,
        "multiband crossovers above the Nyquist safety margin are rejected");
    config = bypass_multiband;
    config.low_mid_crossover_hz = 900.0;
    config.mid_high_crossover_hz = 3500.0;
    dsp_engine *disabled = create_test_engine_with_multiband(8000, 1, 64, &config);
    assert_true(disabled != NULL,
        "disabled multiband does not invalidate an otherwise supported low-rate DSP engine");
    dsp_engine_destroy(disabled);
    config.enabled = 1;
    config.low_mid_crossover_hz = 1000.0;
    dsp_engine *lowest_rate = create_test_engine_with_multiband(8000, 1, 64, &config);
    assert_true(lowest_rate != NULL, "safe multiband cutoffs prepare at the lowest supported sample rate");
    dsp_engine_destroy(lowest_rate);
}

static unsigned int next_random(unsigned int *state) {
    *state ^= *state << 13;
    *state ^= *state >> 17;
    *state ^= *state << 5;
    return *state;
}

static void test_multiband_randomized_configurations_remain_finite(void) {
    unsigned int random_state = 0x5d31a9b7U;
    for (size_t iteration = 0; iteration < 1000; iteration++) {
        dsp_multiband_config config = bypass_multiband;
        config.enabled = 1;
        config.low_mid_crossover_hz = 30.0 + (double)(next_random(&random_state) % 450U);
        config.mid_high_crossover_hz = 1200.0 + (double)(next_random(&random_state) % 2300U);
        for (size_t band = 0; band < DSP_MULTIBAND_COUNT; band++) {
            const double threshold = -60.0 + (double)(next_random(&random_state) % 6001U) / 100.0;
            const double ratio = 1.0 + (double)(next_random(&random_state) % 1901U) / 100.0;
            const double attack = 0.1 + (double)(next_random(&random_state) % 1000U) / 10.0;
            const double release = 0.1 + (double)(next_random(&random_state) % 2000U) / 10.0;
            const double knee = (double)(next_random(&random_state) % 2401U) / 100.0;
            const double makeup = -24.0 + (double)(next_random(&random_state) % 4801U) / 100.0;
            config.bands[band] = (dsp_multiband_compressor_config) {
                (int)(next_random(&random_state) & 1U), threshold, ratio, attack, release, knee, makeup,
            };
        }
        const int sample_rates[] = {44100, 48000, 96000};
        const int sample_rate = sample_rates[iteration % 3];
        dsp_engine *engine = create_test_engine_with_multiband(sample_rate, 2, 64, &config);
        assert_true(engine != NULL, "randomized multiband configuration prepares");
        float input[128];
        float output[128] = {0.0f};
        for (size_t index = 0; index < 128; index++) {
            input[index] = (float)((int)(next_random(&random_state) % 2001U) - 1000) / 4000.0f;
        }
        assert_true(dsp_engine_process(engine, input, 128, output, 128, 64, 2) == DSP_STATUS_OK,
            "randomized multiband block processes");
        for (size_t index = 0; index < 128; index++) {
            assert_true(isfinite(output[index]), "randomized multiband output remains finite");
        }
        dsp_engine_destroy(engine);
    }
}

static double process_tone_block(dsp_engine *engine, double frequency, int channels, double amplitude, size_t start_frame) {
    float input[1024];
    float output[1024];
    double input_square = 0.0;
    double output_square = 0.0;
    for (size_t frame = 0; frame < 512; frame++) {
        const float sample = (float)(amplitude * sin(2.0 * 3.14159265358979323846 * frequency *
            (double)(start_frame + frame) / 48000.0));
        input[frame * (size_t)channels] = sample;
        if (channels == 2) input[frame * 2 + 1] = -sample;
    }
    assert_true(
        dsp_engine_process(engine, input, 512 * (size_t)channels, output, 512 * (size_t)channels, 512, channels) ==
            DSP_STATUS_OK,
        "dynamic EQ tone block processes");
    for (size_t frame = 0; frame < 512; frame++) {
        const double source = input[frame * (size_t)channels];
        const double result = output[frame * (size_t)channels];
        input_square += source * source;
        output_square += result * result;
    }
    return sqrt(output_square / input_square);
}

static void process_silence(dsp_engine *engine, int channels, size_t total_frames) {
    float input[1024] = {0.0f};
    float output[1024] = {0.0f};
    size_t processed = 0;
    while (processed < total_frames) {
        const size_t frames = total_frames - processed < 512 ? total_frames - processed : 512;
        assert_true(
            dsp_engine_process(
                engine,
                input,
                frames * (size_t)channels,
                output,
                frames * (size_t)channels,
                (int)frames,
                channels) == DSP_STATUS_OK,
            "dynamic EQ silence block processes");
        processed += frames;
    }
}

static void test_dynamic_eq_threshold_modes_and_band_selectivity(void) {
    dsp_engine *engine = create_test_engine_with_dynamic_eq(48000, 1, 512, &bypass_dynamic_eq);
    assert_true(engine != NULL, "disabled dynamic EQ graph creates");
    assert_near(measure_sine_gain(engine, 1000.0, 1), 1.0, 1e-6, "disabled dynamic EQ is an identity");
    dsp_engine_destroy(engine);

    dsp_dynamic_eq_config cut = bypass_dynamic_eq;
    cut.enabled = 1;
    cut.bands[0] = (dsp_dynamic_eq_band_config){1, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -30.0, 4.0, 0.1, 80.0, 6.0, 3.0};
    engine = create_test_engine_with_dynamic_eq(48000, 1, 512, &cut);
    assert_true(engine != NULL, "dynamic EQ cut graph creates");
    assert_near(
        measure_sine_gain_with_amplitude(engine, 1000.0, 1, 0.001),
        1.0,
        0.01,
        "below-threshold dynamic cut leaves audio unchanged");
    assert_true(
        measure_sine_gain(engine, 1000.0, 1) < 0.75,
        "above-threshold dynamic cut changes the selected band gain");
    dsp_engine_destroy(engine);

    cut.bands[0].threshold_db = -50.0;
    engine = create_test_engine_with_dynamic_eq(48000, 1, 512, &cut);
    assert_true(engine != NULL, "dynamic EQ maximum-cut graph creates");
    assert_near(
        measure_sine_gain(engine, 1000.0, 1),
        pow(10.0, -3.0 / 20.0),
        0.03,
        "dynamic cut never exceeds its configured maximum cut");
    dsp_engine_destroy(engine);

    cut.bands[0].threshold_db = -30.0;
    engine = create_test_engine_with_dynamic_eq(48000, 1, 512, &cut);
    assert_true(engine != NULL, "dynamic EQ detector selectivity graph creates");
    assert_true(measure_sine_gain(engine, 10000.0, 1) > 0.97,
        "the bandpass detector leaves frequencies outside its target band unchanged");
    dsp_engine_destroy(engine);

    dsp_dynamic_eq_config boost = bypass_dynamic_eq;
    boost.enabled = 1;
    boost.bands[0] = (dsp_dynamic_eq_band_config){1, DSP_DYNAMIC_EQ_BOOST, 1000.0, 1.0, -35.0, 4.0, 0.1, 80.0, 3.0, 6.0};
    engine = create_test_engine_with_dynamic_eq(48000, 1, 512, &boost);
    assert_true(engine != NULL, "dynamic EQ boost graph creates");
    assert_near(
        measure_sine_gain_with_amplitude(engine, 1000.0, 1, 0.001),
        pow(10.0, 3.0 / 20.0),
        0.03,
        "dynamic boost is capped at its configured maximum");
    dsp_engine_destroy(engine);
}

static double measure_dynamic_eq_mix_gain_db(int mode, double threshold_db) {
    dsp_dynamic_eq_config config = bypass_dynamic_eq;
    config.enabled = 1;
    config.bands[0] = (dsp_dynamic_eq_band_config) {
        1, mode, 1000.0, 1.0, threshold_db, 2.0, 10.0, 100.0, 12.0, 12.0,
    };
    dsp_dynamic_eq dynamic_eq;
    assert_true(dsp_dynamic_eq_prepare(&dynamic_eq, 48000, &config), "intermediate mix prepares");
    dynamic_eq.bands[0].envelope = mode == DSP_DYNAMIC_EQ_CUT ? 1.0f : (float)pow(10.0, -24.0 / 20.0);
    dynamic_eq.bands[0].attack_coefficient = 1.0f;
    dynamic_eq.bands[0].release_coefficient = 1.0f;

    double input_square = 0.0;
    double output_square = 0.0;
    for (size_t frame = 0; frame < 48000; frame++) {
        float input = (float)(0.1 * sin(2.0 * 3.14159265358979323846 * 1000.0 * (double)frame / 48000.0));
        float output = input;
        float unused_right = 0.0f;
        dsp_dynamic_eq_process_frame(&dynamic_eq, &output, &unused_right, 1);
        if (frame < 4096) continue;
        input_square += (double)input * input;
        output_square += (double)output * output;
    }
    return 20.0 * log10(sqrt(output_square / input_square));
}

static void test_dynamic_eq_intermediate_db_change_matches_tone_rms(void) {
    const double cut_thresholds[] = {-6.0, -12.0, -18.0};
    const double boost_thresholds[] = {-18.0, -12.0, -6.0};
    const double requested_changes[] = {3.0, 6.0, 9.0};
    for (size_t index = 0; index < sizeof(cut_thresholds) / sizeof(cut_thresholds[0]); index++) {
        const double cut_db = measure_dynamic_eq_mix_gain_db(DSP_DYNAMIC_EQ_CUT, cut_thresholds[index]);
        const double boost_db = measure_dynamic_eq_mix_gain_db(DSP_DYNAMIC_EQ_BOOST, boost_thresholds[index]);
        assert_near(cut_db, -requested_changes[index], 0.12,
            "dynamic cut intermediate dB change matches the measured center tone");
        assert_near(boost_db, requested_changes[index], 0.12,
            "dynamic boost intermediate dB change matches the measured center tone");
    }
}

static void test_dynamic_eq_attack_release_reset_and_chunk_invariance(void) {
    dsp_dynamic_eq_config config = bypass_dynamic_eq;
    config.enabled = 1;
    config.bands[0] = (dsp_dynamic_eq_band_config){1, DSP_DYNAMIC_EQ_CUT, 1000.0, 1.0, -30.0, 4.0, 100.0, 100.0, 6.0, 6.0};
    dsp_engine *whole = create_test_engine_with_dynamic_eq(48000, 1, 512, &config);
    assert_true(whole != NULL, "dynamic EQ attack graph creates");
    const double first_block_gain = process_tone_block(whole, 1000.0, 1, 0.1, 0);
    const double steady_gain = measure_sine_gain(whole, 1000.0, 1);
    assert_true(first_block_gain > steady_gain + 0.1, "dynamic EQ attack ramps into the requested cut");
    process_silence(whole, 1, 48000);
    const double after_release = process_tone_block(whole, 1000.0, 1, 0.1, 0);
    assert_true(after_release > 0.9, "dynamic EQ release restores the dry level after silence");

    dsp_engine *reset = create_test_engine_with_dynamic_eq(48000, 1, 512, &config);
    dsp_engine *fresh = create_test_engine_with_dynamic_eq(48000, 1, 512, &config);
    assert_true(reset != NULL && fresh != NULL, "dynamic EQ reset comparison graphs create");
    (void)measure_sine_gain(reset, 1000.0, 1);
    assert_true(dsp_engine_reset(reset) == DSP_STATUS_OK, "dynamic EQ graph reset succeeds");
    float input[512];
    float reset_output[512];
    float fresh_output[512];
    for (size_t frame = 0; frame < 512; frame++) {
        input[frame] = (float)(0.1 * sin(2.0 * 3.14159265358979323846 * 1000.0 * (double)frame / 48000.0));
    }
    assert_true(dsp_engine_process(reset, input, 512, reset_output, 512, 512, 1) == DSP_STATUS_OK,
        "reset dynamic EQ graph processes after reset");
    assert_true(dsp_engine_process(fresh, input, 512, fresh_output, 512, 512, 1) == DSP_STATUS_OK,
        "fresh dynamic EQ graph processes the same block");
    for (size_t frame = 0; frame < 512; frame++) {
        assert_near(reset_output[frame], fresh_output[frame], 1e-6,
            "dynamic EQ reset matches a fresh graph");
    }
    dsp_engine_destroy(whole);
    dsp_engine_destroy(reset);
    dsp_engine_destroy(fresh);

    config.bands[0].attack_ms = 0.1;
    dsp_engine *chunked = create_test_engine_with_dynamic_eq(48000, 2, 512, &config);
    dsp_engine *unbroken = create_test_engine_with_dynamic_eq(48000, 2, 512, &config);
    assert_true(chunked != NULL && unbroken != NULL, "dynamic EQ chunk-comparison graphs create");
    float stereo_input[1024];
    float chunked_output[1024];
    float unbroken_output[1024];
    for (size_t sample = 0; sample < 1024; sample++) {
        stereo_input[sample] = (float)(0.2 * sin((double)sample * 0.053) + 0.1 * cos((double)sample * 0.017));
    }
    assert_true(dsp_engine_process(unbroken, stereo_input, 1024, unbroken_output, 1024, 512, 2) == DSP_STATUS_OK,
        "one dynamic EQ block processes");
    const size_t chunks[] = {1, 15, 16, 127, 353};
    size_t offset = 0;
    for (size_t chunk = 0; chunk < sizeof(chunks) / sizeof(chunks[0]); chunk++) {
        const size_t frames = chunks[chunk];
        assert_true(
            dsp_engine_process(
                chunked,
                stereo_input + offset * 2,
                frames * 2,
                chunked_output + offset * 2,
                frames * 2,
                (int)frames,
                2) == DSP_STATUS_OK,
            "split dynamic EQ block processes");
        offset += frames;
    }
    assert_true(offset == 512, "dynamic EQ split chunks cover the same frames");
    for (size_t sample = 0; sample < 1024; sample++) {
        assert_near(chunked_output[sample], unbroken_output[sample], 1e-6,
            "dynamic EQ control updates are chunk invariant");
    }
    dsp_engine_destroy(chunked);
    dsp_engine_destroy(unbroken);
}

static void test_dynamic_eq_randomized_configs_stay_bounded_and_finite(void) {
    unsigned int random_state = 0x92d68ca5U;
    for (size_t iteration = 0; iteration < 1000; iteration++) {
        dsp_dynamic_eq_config config = bypass_dynamic_eq;
        config.enabled = 1;
        for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
            config.bands[band] = (dsp_dynamic_eq_band_config) {
                .enabled = (int)(next_random(&random_state) & 1U),
                .mode = (int)(next_random(&random_state) & 1U),
                .frequency_hz = 20.0 + (double)(next_random(&random_state) % 18000U),
                .q = 0.1 + (double)(next_random(&random_state) % 1991U) / 100.0,
                .threshold_db = -60.0 + (double)(next_random(&random_state) % 6001U) / 100.0,
                .ratio = 1.0 + (double)(next_random(&random_state) % 1901U) / 100.0,
                .attack_ms = 0.1 + (double)(next_random(&random_state) % 1000U) / 10.0,
                .release_ms = 0.1 + (double)(next_random(&random_state) % 2000U) / 10.0,
                .max_boost_db = (double)(next_random(&random_state) % 401U) / 100.0,
                .max_cut_db = (double)(next_random(&random_state) % 801U) / 100.0,
            };
        }
        const int sample_rates[] = {44100, 48000, 96000};
        const int sample_rate = sample_rates[iteration % 3];
        dsp_engine *engine = create_test_engine_with_dynamic_eq(sample_rate, 2, 64, &config);
        assert_true(engine != NULL, "randomized dynamic EQ config prepares");
        float input[128];
        float output[128] = {0.0f};
        for (size_t sample = 0; sample < 128; sample++) {
            input[sample] = (float)((int)(next_random(&random_state) % 2001U) - 1000) / 20000.0f;
        }
        assert_true(dsp_engine_process(engine, input, 128, output, 128, 64, 2) == DSP_STATUS_OK,
            "randomized dynamic EQ block processes");
        for (size_t sample = 0; sample < 128; sample++) {
            assert_true(isfinite(output[sample]), "randomized dynamic EQ output remains finite");
            assert_true(fabsf(output[sample]) < 2.0f, "randomized dynamic EQ output does not run away");
        }
        dsp_engine_destroy(engine);
    }
}

static void test_kiss_fft_round_trip(void) {
    kiss_fftr_cfg forward = kiss_fftr_alloc(256, 0, NULL, NULL);
    kiss_fftr_cfg inverse = kiss_fftr_alloc(256, 1, NULL, NULL);
    assert_true(forward != NULL && inverse != NULL, "real FFT plans allocate");
    float input[256];
    float output[256];
    kiss_fft_cpx spectrum[129];
    for (size_t index = 0; index < 256; index++) {
        input[index] = (float)(0.4 * sin(2.0 * 3.14159265358979323846 * 17.0 * index / 256.0) +
            0.1 * cos(2.0 * 3.14159265358979323846 * 31.0 * index / 256.0));
    }
    kiss_fftr(forward, input, spectrum);
    kiss_fftri(inverse, spectrum, output);
    for (size_t index = 0; index < 256; index++) {
        assert_near(output[index] / 256.0, input[index], 1e-5, "real FFT round-trip sample");
    }
    kiss_fftr_free(forward);
    kiss_fftr_free(inverse);
}

static void test_convolver_impulse_direct_convolution_and_latency(void) {
    const float unit_impulse[] = {1.0f};
    const dsp_convolver_config unit_config = {1, 48000, 1, 1, 1, 1.0f, unit_impulse};
    dsp_engine *unit = create_test_engine_with_convolver(48000, 1, 256, &unit_config);
    assert_true(unit != NULL, "unit impulse convolver creates");
    assert_true(dsp_engine_get_latency_frames(unit) == DSP_CONVOLVER_PARTITION_FRAMES,
        "convolver reports its measured partition startup latency");
    float input[256] = {0};
    float output[256] = {0};
    input[0] = 1.0f;
    assert_true(
        dsp_engine_process(unit, input, 256, output, 256, 256, 1) == DSP_STATUS_OK,
        "unit impulse convolution block processes");
    for (size_t index = 0; index < DSP_CONVOLVER_PARTITION_FRAMES; index++) {
        assert_near(output[index], 0.0, 0.0, "convolver emits startup silence");
    }
    assert_near(output[DSP_CONVOLVER_PARTITION_FRAMES], 1.0, 1e-5,
        "unit IR impulse emerges after reported latency");
    assert_true(dsp_engine_reset(unit) == DSP_STATUS_OK, "convolver reset succeeds");
    for (size_t index = 0; index < 256; index++) output[index] = 0.0f;
    assert_true(
        dsp_engine_process(unit, input, 256, output, 256, 256, 1) == DSP_STATUS_OK,
        "reset convolver processes a fresh impulse");
    assert_near(output[DSP_CONVOLVER_PARTITION_FRAMES], 1.0, 1e-5,
        "reset convolver matches its fresh-engine impulse response");
    dsp_engine_destroy(unit);

    const float shifted_ir[] = {0.0f, 1.0f};
    const dsp_convolver_config shifted_config = {1, 48000, 1, 2, 2, 1.0f, shifted_ir};
    dsp_engine *shifted = create_test_engine_with_convolver(48000, 1, 256, &shifted_config);
    assert_true(shifted != NULL, "shifted impulse convolver creates");
    for (size_t index = 0; index < 256; index++) {
        input[index] = 0.0f;
        output[index] = 0.0f;
    }
    input[0] = 1.0f;
    assert_true(
        dsp_engine_process(shifted, input, 256, output, 256, 256, 1) == DSP_STATUS_OK,
        "shifted impulse convolution processes");
    assert_near(output[128], 0.0, 1e-6, "IR sample zero stays at the block latency");
    assert_near(output[129], 1.0, 1e-5, "IR unit delay adds one sample after block latency");
    dsp_engine_destroy(shifted);

    const dsp_convolver_config mono_ir_config = {1, 48000, 1, 1, 1, 1.0f, unit_impulse};
    dsp_engine *mono_ir = create_test_engine_with_convolver(48000, 2, 256, &mono_ir_config);
    assert_true(mono_ir != NULL, "mono IR on stereo output creates");
    float stereo_input[512] = {0};
    float stereo_output[512] = {0};
    stereo_input[0] = 1.0f;
    stereo_input[1] = 0.25f;
    assert_true(
        dsp_engine_process(mono_ir, stereo_input, 512, stereo_output, 512, 256, 2) == DSP_STATUS_OK,
        "mono IR processes both stereo channels");
    assert_near(stereo_output[256], 1.0, 1e-5, "mono IR feeds left output");
    assert_near(stereo_output[257], 0.25, 1e-5, "mono IR feeds right output");
    dsp_engine_destroy(mono_ir);

    const float stereo_ir_samples[] = {1.0f, 2.0f};
    const dsp_convolver_config stereo_ir_config = {1, 48000, 2, 1, 2, 1.0f, stereo_ir_samples};
    dsp_engine *stereo_ir = create_test_engine_with_convolver(48000, 2, 256, &stereo_ir_config);
    assert_true(stereo_ir != NULL, "stereo IR graph creates");
    for (size_t index = 0; index < 512; index++) {
        stereo_input[index] = 0.0f;
        stereo_output[index] = 0.0f;
    }
    stereo_input[0] = 0.5f;
    stereo_input[1] = 0.25f;
    assert_true(
        dsp_engine_process(stereo_ir, stereo_input, 512, stereo_output, 512, 256, 2) == DSP_STATUS_OK,
        "stereo IR processes independent left/right inputs");
    assert_near(stereo_output[256], 0.5, 1e-5, "stereo IR maps left to left");
    assert_near(stereo_output[257], 0.5, 1e-5, "stereo IR maps right to right");
    dsp_engine_destroy(stereo_ir);

    const float ir[] = {1.0f, 0.5f, -0.25f};
    const dsp_convolver_config direct_config = {1, 48000, 1, 3, 3, 1.0f, ir};
    dsp_engine *direct = create_test_engine_with_convolver(48000, 1, 256, &direct_config);
    assert_true(direct != NULL, "short direct-reference convolver creates");
    float reference_input[256] = {0};
    reference_input[0] = 1.0f;
    reference_input[1] = 2.0f;
    reference_input[2] = 3.0f;
    reference_input[3] = 4.0f;
    float reference_output[256] = {0};
    assert_true(
        dsp_engine_process(direct, reference_input, 256, reference_output, 256, 256, 1) == DSP_STATUS_OK,
        "direct-reference convolution block processes");
    dsp_engine_destroy(direct);
    const double expected[] = {1.0, 2.5, 3.75, 5.0, 1.25, -1.0};
    for (size_t index = 0; index < sizeof(expected) / sizeof(expected[0]); index++) {
        assert_near(
            reference_output[DSP_CONVOLVER_PARTITION_FRAMES + index],
            expected[index],
            1e-4,
            "partitioned output matches direct FIR convolution");
    }
}

static void test_convolver_chunk_invariance_wet_mix_and_rate_mismatch_bypass(void) {
    const float impulse[] = {1.0f, 0.25f, -0.125f};
    const dsp_convolver_config convolver = {1, 48000, 1, 3, 3, 1.0f, impulse};
    dsp_engine *whole = create_test_engine_with_convolver(48000, 1, 512, &convolver);
    dsp_engine *split = create_test_engine_with_convolver(48000, 1, 512, &convolver);
    assert_true(whole != NULL && split != NULL, "convolver engines create for chunk test");
    float input[512];
    float whole_output[512] = {0};
    float split_output[512] = {0};
    for (size_t index = 0; index < 512; index++) input[index] = index == 0 ? 1.0f : 0.0f;
    assert_true(
        dsp_engine_process(whole, input, 512, whole_output, 512, 512, 1) == DSP_STATUS_OK,
        "whole convolver input block processes");
    const size_t chunks[] = {127, 1, 64, 256, 64};
    size_t offset = 0;
    for (size_t chunk = 0; chunk < sizeof(chunks) / sizeof(chunks[0]); chunk++) {
        const size_t frames = chunks[chunk];
        assert_true(
            dsp_engine_process(
                split,
                input + offset,
                frames,
                split_output + offset,
                frames,
                (int)frames,
                1) == DSP_STATUS_OK,
            "split convolver chunk processes");
        offset += frames;
    }
    assert_true(offset == 512, "convolver test chunks cover the source block");
    for (size_t index = 0; index < 512; index++) {
        assert_near(whole_output[index], split_output[index], 1e-6, "convolver output is chunk invariant");
    }
    dsp_engine_destroy(whole);
    dsp_engine_destroy(split);

    const dsp_convolver_config wet_mix = {1, 48000, 1, 1, 1, 0.5f, impulse};
    dsp_engine *mixed = create_test_engine_with_convolver(48000, 1, 256, &wet_mix);
    assert_true(mixed != NULL, "wet mix convolver creates");
    float mixed_input[256] = {0};
    float mixed_output[256] = {0};
    mixed_input[0] = 1.0f;
    assert_true(
        dsp_engine_process(mixed, mixed_input, 256, mixed_output, 256, 256, 1) == DSP_STATUS_OK,
        "wet/dry aligned convolver block processes");
    assert_near(mixed_output[128], 1.0, 1e-5, "wet/dry paths share the reported latency");
    dsp_engine_destroy(mixed);

    const dsp_convolver_config mismatched_rate = {1, 44100, 1, 1, 1, 1.0f, impulse};
    dsp_engine *bypassed = dsp_engine_create(
        48000,
        1,
        4,
        6.020599913279624,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &mismatched_rate,
        &bypass_harmonic);
    assert_true(bypassed != NULL, "sample-rate-mismatched IR leaves DSP engine prepared");
    assert_true(dsp_engine_get_latency_frames(bypassed) == 0, "mismatched IR reports zero added latency");
    const float dry_input[] = {0.25f, -0.5f};
    float dry_output[2] = {0};
    assert_true(
        dsp_engine_process(bypassed, dry_input, 2, dry_output, 2, 2, 1) == DSP_STATUS_OK,
        "mismatched IR bypasses only convolution");
    assert_near(dry_output[0], 0.5, 1e-6, "mismatched IR leaves preamp active");
    assert_near(dry_output[1], -1.0, 1e-6, "mismatched IR bypass leaves other DSP stages active");
    dsp_engine_destroy(bypassed);
}

static void test_convolver_ir_sizes_and_validation(void) {
    const int sizes[] = {4096, 16384, DSP_CONVOLVER_MAX_IR_FRAMES};
    for (size_t size_index = 0; size_index < sizeof(sizes) / sizeof(sizes[0]); size_index++) {
        const int frame_count = sizes[size_index];
        float *impulse = (float *)calloc((size_t)frame_count, sizeof(float));
        assert_true(impulse != NULL, "large IR test buffer allocates");
        impulse[0] = 1.0f;
        const dsp_convolver_config config = {1, 48000, 1, frame_count, (size_t)frame_count, 1.0f, impulse};
        dsp_engine *engine = create_test_engine_with_convolver(48000, 2, 512, &config);
        assert_true(engine != NULL, "supported partitioned IR size prepares");
        assert_true(dsp_engine_get_latency_frames(engine) == DSP_CONVOLVER_PARTITION_FRAMES,
            "large IR preserves explicit block latency");
        float input[258] = {0};
        float output[258] = {0};
        input[0] = 1.0f;
        assert_true(
            dsp_engine_process(engine, input, 258, output, 258, 129, 2) == DSP_STATUS_OK,
            "large IR impulse block processes");
        assert_near(output[256], 1.0, 1e-5, "large IR unit impulse emerges after fixed latency");
        dsp_engine_destroy(engine);
        free(impulse);
    }

    const float sample[] = {1.0f};
    const dsp_convolver_config too_long = {
        1, 48000, 1, DSP_CONVOLVER_MAX_IR_FRAMES + 1,
        DSP_CONVOLVER_MAX_IR_FRAMES + 1, 1.0f, sample,
    };
    assert_true(
        create_test_engine_with_convolver(48000, 1, 512, &too_long) == NULL,
        "IRs longer than the configured frame limit are rejected");

    const float huge_impulse[] = {FLT_MAX};
    const dsp_convolver_config overflowing_ir = {1, 48000, 1, 1, 1, 1.0f, huge_impulse};
    assert_true(
        create_test_engine_with_convolver(48000, 1, 512, &overflowing_ir) == NULL,
        "finite but unbounded IR amplitudes are rejected before FFT preparation");
}

static void test_high_gain_convolver_stays_below_full_scale_with_headroom(void) {
    const int frame_count = DSP_CONVOLVER_MAX_IR_FRAMES;
    float *impulse = (float *)malloc((size_t)frame_count * sizeof(float));
    assert_true(impulse != NULL, "high-gain IR test buffer allocates");
    for (int frame = 0; frame < frame_count; frame++) impulse[frame] = 1.0f;
    const dsp_convolver_config config = {
        1, 48000, 1, frame_count, (size_t)frame_count, 1.0f, impulse,
    };
    const double gain_db = -20.0 * log10((double)frame_count) - 1.0;
    dsp_engine *engine = create_test_engine_with_convolver_and_gain(48000, 1, 256, gain_db, &config);
    assert_true(engine != NULL, "maximum valid high-gain IR prepares with auto-headroom attenuation");

    float input[256];
    float output[256];
    double maximum_absolute_output = 0.0;
    const int total_frames = frame_count + 512;
    for (int offset = 0; offset < total_frames; offset += 256) {
        for (size_t index = 0; index < 256; index++) input[index] = 0.05f;
        assert_true(
            dsp_engine_process(engine, input, 256, output, 256, 256, 1) == DSP_STATUS_OK,
            "attenuated high-gain convolution processes constant PCM");
        for (size_t index = 0; index < 256; index++) {
            const double magnitude = fabs((double)output[index]);
            if (magnitude > maximum_absolute_output) maximum_absolute_output = magnitude;
        }
    }
    assert_true(maximum_absolute_output < 0.05,
        "convolver output stays below full scale after the full IR history warms");
    dsp_engine_destroy(engine);
    free(impulse);
}

static void test_convolver_sparse_sentinel_taps_reach_their_output_frames(void) {
    const int sizes[] = {4096, 16384, DSP_CONVOLVER_MAX_IR_FRAMES};
    const float tap_values[] = {0.125f, -0.25f, 0.375f, -0.5f, 0.625f, -0.75f, 0.875f};
    const int taps_4k[] = {0, 127, 128, 2048, 4095};
    const int taps_16k[] = {0, 127, 128, 4096, 16383};
    const int taps_64k[] = {0, 127, 128, 4096, 16384, 32768, 65535};
    for (size_t size_index = 0; size_index < sizeof(sizes) / sizeof(sizes[0]); size_index++) {
        const int frame_count = sizes[size_index];
        const int *tap_indices = size_index == 0 ? taps_4k : size_index == 1 ? taps_16k : taps_64k;
        const size_t tap_count = size_index == 0 ? 5 : size_index == 1 ? 5 : 7;
        float *impulse = (float *)calloc((size_t)frame_count, sizeof(float));
        assert_true(impulse != NULL, "sparse sentinel IR allocates");
        for (size_t tap = 0; tap < tap_count; tap++) {
            impulse[tap_indices[tap]] = tap_values[tap];
        }
        const dsp_convolver_config config = {
            1, 48000, 1, frame_count, (size_t)frame_count, 1.0f, impulse,
        };
        dsp_engine *engine = create_test_engine_with_convolver(48000, 1, 512, &config);
        assert_true(engine != NULL, "sparse sentinel convolution graph creates");

        const size_t total_frames = (size_t)frame_count + DSP_CONVOLVER_PARTITION_FRAMES;
        for (size_t block_start = 0; block_start < total_frames; block_start += 512) {
            const size_t frames = total_frames - block_start < 512 ? total_frames - block_start : 512;
            float input[512] = {0.0f};
            float output[512] = {0.0f};
            if (block_start == 0) input[0] = 1.0f;
            assert_true(dsp_engine_process(engine, input, frames, output, frames, (int)frames, 1) == DSP_STATUS_OK,
                "sparse sentinel impulse block processes");
            for (size_t frame = 0; frame < frames; frame++) {
                const size_t output_frame = block_start + frame;
                float expected = 0.0f;
                if (output_frame >= DSP_CONVOLVER_PARTITION_FRAMES) {
                    const size_t tap_index = output_frame - DSP_CONVOLVER_PARTITION_FRAMES;
                    for (size_t tap = 0; tap < tap_count; tap++) {
                        if (tap_indices[tap] == (int)tap_index) expected = tap_values[tap];
                    }
                }
                assert_near(output[frame], expected, 1e-4,
                    "sparse IR tap appears at reported latency plus tap index");
            }
        }
        dsp_engine_destroy(engine);
        free(impulse);
    }
}

static dsp_engine *create_studio_test_engine(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_macro_config *macro,
    const dsp_smart_loudness_config *loudness,
    const dsp_dynamic_bass_config *dynamic_bass) {
    return dsp_engine_create_with_studio(
        sample_rate,
        channels,
        max_frames,
        0.0,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        &bypass_harmonic,
        macro,
        loudness,
        dynamic_bass);
}

static dsp_engine *create_studio_test_engine_with_gain(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const dsp_macro_config *macro,
    const dsp_smart_loudness_config *loudness,
    const dsp_dynamic_bass_config *dynamic_bass) {
    return dsp_engine_create_with_studio(
        sample_rate,
        channels,
        max_frames,
        gain_db,
        NULL,
        0,
        &bypass_dynamics,
        &bypass_multiband,
        &bypass_dynamic_eq,
        NULL,
        0,
        NULL,
        0,
        &bypass_spatial,
        &bypass_convolver,
        &bypass_harmonic,
        macro,
        loudness,
        dynamic_bass);
}

typedef struct {
    double real;
    double imaginary;
} native_test_complex;

static native_test_complex native_test_one_pole_response(double cutoff_hz, double frequency_hz, int sample_rate) {
    const double pi = 3.14159265358979323846;
    const double alpha = 1.0 - exp(-2.0 * pi * cutoff_hz / (double)sample_rate);
    const double pole = 1.0 - alpha;
    const double omega = 2.0 * pi * frequency_hz / (double)sample_rate;
    const double denominator_real = 1.0 - pole * cos(omega);
    const double denominator_imaginary = pole * sin(omega);
    const double denominator_magnitude_squared =
        denominator_real * denominator_real + denominator_imaginary * denominator_imaginary;
    return (native_test_complex) {
        alpha * denominator_real / denominator_magnitude_squared,
        -alpha * denominator_imaginary / denominator_magnitude_squared,
    };
}

static native_test_complex native_test_complex_add(native_test_complex left, native_test_complex right) {
    return (native_test_complex) {left.real + right.real, left.imaginary + right.imaginary};
}

static native_test_complex native_test_complex_subtract(native_test_complex left, native_test_complex right) {
    return (native_test_complex) {left.real - right.real, left.imaginary - right.imaginary};
}

static native_test_complex native_test_complex_scale(native_test_complex value, double scale) {
    return (native_test_complex) {value.real * scale, value.imaginary * scale};
}

static double native_test_complex_magnitude(native_test_complex value) {
    return hypot(value.real, value.imaginary);
}

static double native_test_macro_response(
    const dsp_macro_config *config,
    double frequency_hz,
    int sample_rate) {
    const native_test_complex unity = {1.0, 0.0};
    const native_test_complex bass = native_test_one_pole_response(180.0, frequency_hz, sample_rate);
    const native_test_complex vocal_low = native_test_one_pole_response(500.0, frequency_hz, sample_rate);
    const native_test_complex vocal_high = native_test_one_pole_response(4000.0, frequency_hz, sample_rate);
    const native_test_complex air = native_test_one_pole_response(8000.0, frequency_hz, sample_rate);
    native_test_complex response = unity;
    response = native_test_complex_add(response, native_test_complex_scale(
        bass, pow(10.0, config->bass_gain_db / 20.0) - 1.0));
    response = native_test_complex_add(response, native_test_complex_scale(
        native_test_complex_subtract(vocal_high, vocal_low), pow(10.0, config->vocal_gain_db / 20.0) - 1.0));
    response = native_test_complex_add(response, native_test_complex_scale(
        native_test_complex_subtract(unity, air), pow(10.0, config->air_gain_db / 20.0) - 1.0));
    return native_test_complex_magnitude(response);
}

static double native_test_loudness_response(
    const dsp_smart_loudness_config *config,
    double frequency_hz,
    int sample_rate) {
    const native_test_complex unity = {1.0, 0.0};
    const native_test_complex low = native_test_one_pole_response(100.0, frequency_hz, sample_rate);
    const native_test_complex high = native_test_one_pole_response(8000.0, frequency_hz, sample_rate);
    native_test_complex response = unity;
    response = native_test_complex_add(response, native_test_complex_scale(
        low, pow(10.0, config->low_gain_db / 20.0) - 1.0));
    response = native_test_complex_add(response, native_test_complex_scale(
        native_test_complex_subtract(unity, high), pow(10.0, config->high_gain_db / 20.0) - 1.0));
    return native_test_complex_magnitude(response);
}

static void test_studio_native_macro_and_loudness_frequency_response(void) {
    const dsp_macro_config macro = {1, 3.0, 1.5, 1.0, 1.0};
    const dsp_macro_config neutral_macro = {1, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {1, 2.5, 1.0};
    const dsp_smart_loudness_config neutral_loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {
        0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0,
    };
    const double frequencies[] = {40.0, 1000.0, 10000.0};
    for (size_t index = 0; index < sizeof(frequencies) / sizeof(frequencies[0]); index++) {
        const double frequency_hz = frequencies[index];
        dsp_engine *macro_engine = create_studio_test_engine(
            48000, 2, 512, &macro, &neutral_loudness, &dynamic_bass);
        assert_true(macro_engine != NULL, "macro frequency-response engine creates");
        assert_near(
            measure_sine_gain(macro_engine, frequency_hz, 2),
            native_test_macro_response(&macro, frequency_hz, 48000),
            0.01,
            "native macro sine response matches its first-order transfer model");
        dsp_engine_destroy(macro_engine);

        dsp_engine *loudness_engine = create_studio_test_engine(
            48000, 2, 512, &neutral_macro, &loudness, &dynamic_bass);
        assert_true(loudness_engine != NULL, "Smart Loudness frequency-response engine creates");
        assert_near(
            measure_sine_gain(loudness_engine, frequency_hz, 2),
            native_test_loudness_response(&loudness, frequency_hz, 48000),
            0.01,
            "native Smart Loudness sine response matches its first-order transfer model");
        dsp_engine_destroy(loudness_engine);
    }
}

static void test_studio_hot_refund_waits_for_stage_gain_release(void) {
    const dsp_macro_config initial_macro = {1, 4.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config initial_loudness = {1, 3.0, 0.0};
    const dsp_dynamic_bass_config initial_dynamic_bass = {
        1, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 100.0,
    };
    dsp_engine *engine = create_studio_test_engine_with_gain(
        48000, 2, 512, -12.0, &initial_macro, &initial_loudness, &initial_dynamic_bass);
    assert_true(engine != NULL, "headroom refund test creates a studio engine");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "headroom refund test binds a MEDIA owner context");

    float output[1024];
    for (size_t block = 0; block < 180; block++) {
        float quiet_input[1024];
        for (size_t frame = 0; frame < 512; frame++) {
            const float sample = (float)(0.001 * sin(2.0 * 3.14159265358979323846 * 40.0 *
                (double)(block * 512u + frame) / 48000.0));
            quiet_input[frame * 2] = sample;
            quiet_input[frame * 2 + 1] = sample;
        }
        assert_true(dsp_engine_process(engine, quiet_input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "quiet low-frequency blocks raise Dynamic Bass toward its boost ceiling");
    }

    const dsp_engine_hot_update disable_update = {
        .generation_token = 42,
        .generation = 42,
        .editor_owner_epoch = 7,
        .renderer_owner_token = 9,
        .pcm_format_epoch = 3,
        .hot_context_token = 1,
        .media_role = 1,
        .gain_db = 0.0,
        .gain_linear = 1.0,
        .gain_pair_validated = 1,
        .field_mask = DSP_ENGINE_HOT_FIELD_STUDIO,
        .hot_version = 1,
        .macro = {0, 0.0, 0.0, 0.0, 1.0},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 100.0},
    };
    assert_true(dsp_engine_enqueue_hot_update(engine, &disable_update),
        "headroom refund test queues module disable");

    double maximum_absolute_output = 0.0;
    size_t absolute_frame = 180u * 512u;
    for (size_t block = 0; block < 140; block++) {
        float full_scale_input[1024];
        for (size_t frame = 0; frame < 512; frame++) {
            const float sample = (float)sin(2.0 * 3.14159265358979323846 * 40.0 *
                (double)(absolute_frame + frame) / 48000.0);
            full_scale_input[frame * 2] = sample;
            full_scale_input[frame * 2 + 1] = sample;
        }
        assert_true(dsp_engine_process(engine, full_scale_input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "full-scale low-frequency blocks process while old stage gains release");
        if (block == 0) {
            assert_true(dsp_engine_get_hot_ack_token(engine) == 0,
                "gain refund with Macro/Loudness/Dynamic Bass release has no early HOT ACK");
        }
        for (size_t sample = 0; sample < 1024; sample++) {
            const double absolute_output = fabs((double)output[sample]);
            if (absolute_output > maximum_absolute_output) maximum_absolute_output = absolute_output;
        }
        absolute_frame += 512;
    }
    assert_true(maximum_absolute_output <= 1.001,
        "preamp refund waits for Macro, Loudness, and Dynamic Bass to leave their old boost envelope");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 42,
        "headroom refund HOT update is acknowledged after stage release and gain refund settle");
    dsp_engine_destroy(engine);
}

static void test_studio_gain_refund_is_sample_ramped_at_supported_formats(void) {
    const int sample_rates[] = {44100, 48000};
    const int channel_counts[] = {1, 2};
    const dsp_macro_config initial_macro = {1, 4.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config initial_loudness = {1, 3.0, 0.0};
    const dsp_dynamic_bass_config initial_dynamic_bass = {
        1, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 100.0,
    };
    const dsp_macro_config neutral_macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config neutral_loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config neutral_dynamic_bass = {
        0, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 100.0,
    };

    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        for (size_t channel_index = 0;
             channel_index < sizeof(channel_counts) / sizeof(channel_counts[0]);
             channel_index++) {
            const int channels = channel_counts[channel_index];
            dsp_graph graph = {0};
            assert_true(dsp_graph_prepare(
                    &graph, sample_rate, channels, -12.0, NULL, 0, &bypass_dynamics, &bypass_multiband,
                    &bypass_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
                    &bypass_harmonic, &initial_macro, &initial_loudness, &initial_dynamic_bass),
                "studio refund graph prepares for every supported format");

            float warm_input[1024];
            float warm_output[1024] = {0.0f};
            uint64_t non_finite_input = 0;
            uint64_t non_finite_output = 0;
            size_t absolute_frame = 0;
            for (size_t block = 0; block < 180; block++) {
                for (size_t frame = 0; frame < 512; frame++) {
                    const float sample = (float)(0.001 * sin(2.0 * 3.14159265358979323846 * 40.0 *
                        (double)(absolute_frame + frame) / (double)sample_rate));
                    warm_input[frame * (size_t)channels] = sample;
                    if (channels == 2) warm_input[frame * 2 + 1] = sample;
                }
                dsp_graph_process(&graph, warm_input, warm_output, 512, (size_t)channels,
                    &non_finite_input, &non_finite_output);
                absolute_frame += 512;
            }
            assert_true(graph.dynamic_bass.current_gain > 1.0,
                "Dynamic Bass has a prior boost before the Studio refund");
            assert_true(dsp_graph_apply_studio_hot(
                    &graph, 0.0, 1.0, &neutral_macro, &neutral_loudness, &neutral_dynamic_bass) ==
                    DSP_GRAPH_HOT_APPLIED,
                "Studio update queues a headroom refund after lowering module gains");
            assert_true(graph.studio_gain_refund_pending,
                "headroom refund waits for prior module gains to settle");

            const float steady_input[2] = {0.125f, 0.125f};
            float frame_output[2] = {0.0f, 0.0f};
            dsp_graph_process(&graph, steady_input, frame_output, 1, (size_t)channels,
                &non_finite_input, &non_finite_output);
            float previous_left = frame_output[0];
            float previous_right = frame_output[1];
            float maximum_sample_delta = 0.0f;
            int saw_refund_ramp = graph.gain.ramp_remaining > 0;
            int ramp_pending_completion = 0;

            for (int frame = 0; frame < sample_rate * 3 && dsp_graph_hot_transition_pending(&graph); frame++) {
                dsp_graph_process(&graph, steady_input, frame_output, 1, (size_t)channels,
                    &non_finite_input, &non_finite_output);
                const float left_delta = fabsf(frame_output[0] - previous_left);
                if (left_delta > maximum_sample_delta) maximum_sample_delta = left_delta;
                previous_left = frame_output[0];
                if (channels == 2) {
                    const float right_delta = fabsf(frame_output[1] - previous_right);
                    if (right_delta > maximum_sample_delta) maximum_sample_delta = right_delta;
                    previous_right = frame_output[1];
                }
                if (graph.gain.ramp_remaining > 0) {
                    saw_refund_ramp = 1;
                    ramp_pending_completion = dsp_graph_hot_transition_pending(&graph);
                }
                assert_true(isfinite(frame_output[0]) && (channels == 1 || isfinite(frame_output[1])),
                    "Studio refund stays finite for mono and stereo");
            }

            assert_true(saw_refund_ramp, "headroom refund uses a bounded sample ramp");
            assert_true(ramp_pending_completion,
                "Studio completion remains pending for the entire refund ramp");
            assert_true(maximum_sample_delta < 0.005f,
                "headroom refund has no sample-boundary gain jump");
            assert_true(!dsp_graph_hot_transition_pending(&graph),
                "Studio transition completes only after the refund ramp settles");
            assert_true(graph.gain.ramp_remaining == 0,
                "headroom refund ramp completes before the graph reports settled");
            assert_near(graph.gain.linear, 1.0, 1e-6,
                "headroom refund reaches its latest target gain");
            assert_true(non_finite_input == 0 && non_finite_output == 0,
                "Studio refund introduces no non-finite samples");
            dsp_graph_close(&graph);
        }
    }
}

static void test_studio_gain_refund_ramps_when_modules_are_already_settled(void) {
    const int sample_rates[] = {44100, 48000};
    const int channel_counts[] = {1, 2};
    const dsp_dynamics_config refund_limiter = {
        0, -20.0, 4.0, 10.0, 100.0, 0.0, 0.0, 1, -1.0, 60.0,
    };
    const dsp_macro_config neutral_macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config neutral_loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config neutral_dynamic_bass = {
        0, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 100.0,
    };

    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        for (size_t channel_index = 0;
             channel_index < sizeof(channel_counts) / sizeof(channel_counts[0]);
             channel_index++) {
            const int channels = channel_counts[channel_index];
            dsp_graph graph = {0};
            assert_true(dsp_graph_prepare(
                    &graph, sample_rate, channels, -12.0, NULL, 0, &refund_limiter, &bypass_multiband,
                    &bypass_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
                    &bypass_harmonic, &neutral_macro, &neutral_loudness, &neutral_dynamic_bass),
                "settled Studio refund graph prepares for every supported format");
            const dsp_gain before_invalid_update = graph.gain;
            assert_true(!dsp_graph_apply_studio_hot(
                    &graph, 0.0, 0.0, &neutral_macro, &neutral_loudness, &neutral_dynamic_bass),
                "zero linear gain is rejected instead of entering a refund ramp");
            assert_true(memcmp(&graph.gain, &before_invalid_update, sizeof(before_invalid_update)) == 0,
                "zero gain rejection leaves the active gain and ramp state unchanged");

            assert_true(dsp_graph_apply_studio_hot(
                    &graph, 0.0, 1.0, &neutral_macro, &neutral_loudness, &neutral_dynamic_bass) ==
                    DSP_GRAPH_HOT_APPLIED,
                "settled Studio graph accepts a higher preamp target");
            assert_true(graph.gain.ramp_remaining == (uint32_t)(sample_rate / 50),
                "immediate headroom refund starts the bounded 20 ms sample ramp");
            assert_true(!graph.studio_gain_refund_pending && dsp_graph_hot_transition_pending(&graph),
                "ramp state, rather than a pending-module flag, holds completion open");

            float previous_gain = graph.gain.linear;
            float maximum_gain_step = 0.0f;
            float maximum_output_peak = 0.0f;
            uint32_t random_state = UINT32_C(0x5a17c9e3);
            int saw_pending_during_ramp = 0;
            for (int frame = 0; frame < sample_rate / 50; frame++) {
                random_state = random_state * UINT32_C(1664525) + UINT32_C(1013904223);
                const float noise = ((float)(random_state >> 8) / 16777215.0f - 0.5f) * 0.02f;
                float sample = 0.75f * sinf((float)(2.0 * 3.14159265358979323846 * 1000.0 *
                    (double)frame / (double)sample_rate)) + noise;
                if (frame == sample_rate / 50 - 10) sample = 0.9f;
                const float input[2] = {sample, sample};
                float output[2] = {0.0f, 0.0f};
                uint64_t non_finite_input = 0;
                uint64_t non_finite_output = 0;
                const float expected_gain = graph.gain.linear;
                dsp_graph_process(&graph, input, output, 1, (size_t)channels,
                    &non_finite_input, &non_finite_output);
                const float gain_step = fabsf(graph.gain.linear - previous_gain);
                if (gain_step > maximum_gain_step) maximum_gain_step = gain_step;
                const float output_peak = fmaxf(fabsf(output[0]), channels == 2 ? fabsf(output[1]) : 0.0f);
                if (output_peak > maximum_output_peak) maximum_output_peak = output_peak;
                assert_true(fabsf(graph.gain.linear - previous_gain) <=
                        (1.0f - 0.25118864f) / (float)(sample_rate / 50) + 2.0e-5f,
                    "Studio gain changes by no more than its bounded per-sample ramp step");
                assert_true(fabsf(output[0]) <= fabsf(sample * expected_gain) + 1e-6f,
                    "limiter or gain stage does not amplify beyond the current ramp gain");
                assert_true(graph.gain.linear >= previous_gain && graph.gain.linear <= 1.0f,
                    "Studio refund gain progresses monotonically without overshoot");
                previous_gain = graph.gain.linear;
                if (graph.gain.ramp_remaining > 0) {
                    saw_pending_during_ramp = saw_pending_during_ramp || dsp_graph_hot_transition_pending(&graph);
                }
            }

            assert_true(maximum_gain_step < 0.001f,
                "already-settled Studio refund has no sample-boundary gain jump");
            assert_true(saw_pending_during_ramp,
                "already-settled refund remains pending until its final sample");
            assert_true(!dsp_graph_hot_transition_pending(&graph),
                "already-settled Studio refund completes with its ramp");
            assert_near(graph.gain.linear, 1.0, 0.0,
                "already-settled Studio refund lands exactly on its target gain");
            assert_true(graph.dynamics.limiter_gain < 1.0f,
                "the impulse exercises limiter gain reduction during the refund");
            assert_true(maximum_output_peak <= powf(10.0f, -1.0f / 20.0f) + 1e-5f,
                "the limiter keeps output peaks below its sample-peak threshold");
            dsp_graph_close(&graph);
        }
    }
}

static void test_studio_gain_refund_ack_waits_for_ramp_and_newer_generation(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[1024];
    float output[1024] = {0.0f};
    for (size_t sample = 0; sample < 1024; sample++) input[sample] = 0.5f;

    dsp_engine *engine = create_studio_test_engine_with_gain(48000, 2, 512, -12.0,
        &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "Studio refund ACK graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "Studio refund ACK graph binds MEDIA owner");
    const dsp_engine_hot_update initial_refund = {
        .generation_token = 42,
        .generation = 42,
        .editor_owner_epoch = 7,
        .renderer_owner_token = 9,
        .pcm_format_epoch = 3,
        .hot_context_token = 1,
        .media_role = 1,
        .gain_db = 0.0,
        .gain_linear = 1.0,
        .gain_pair_validated = 1,
        .field_mask = DSP_ENGINE_HOT_FIELD_STUDIO,
        .hot_version = 1,
        .macro = {0, 0.0, 0.0, 0.0, 1.0},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0},
    };
    assert_true(dsp_engine_enqueue_hot_update(engine, &initial_refund),
        "Studio headroom refund enters the bounded HOT mailbox");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 256, 2) == DSP_STATUS_OK,
        "first partial refund ramp block processes");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0,
        "refund is not acknowledged before its sample ramp completes");

    dsp_engine_hot_update superseded_refund = initial_refund;
    superseded_refund.generation_token = 43;
    superseded_refund.generation = 43;
    superseded_refund.gain_db = -3.0;
    superseded_refund.gain_linear = 0.7079457844;
    assert_true(dsp_engine_enqueue_hot_update(engine, &superseded_refund),
        "intermediate generation enters the bounded HOT mailbox");
    dsp_engine_hot_update newer_refund = initial_refund;
    newer_refund.generation_token = 44;
    newer_refund.generation = 44;
    newer_refund.gain_db = -6.0;
    newer_refund.gain_linear = 0.5011872336;
    assert_true(dsp_engine_enqueue_hot_update(engine, &newer_refund),
        "newer generation safely supersedes a refund while its ramp is active");
    const int completion_blocks[] = {512, 192};
    for (size_t index = 0; index < sizeof(completion_blocks) / sizeof(completion_blocks[0]); index++) {
        const int frames = completion_blocks[index];
        assert_true(dsp_engine_process(engine, input, (size_t)frames * 2, output, (size_t)frames * 2,
                frames, 2) == DSP_STATUS_OK,
            "superseded refund ramp block processes");
        assert_true(dsp_engine_get_hot_ack_token(engine) != 42,
            "superseded refund generation never receives an ACK");
    }

    for (int block = 0; block < 8 && dsp_engine_get_hot_ack_token(engine) != 44; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "newer Studio gain target completes its bounded transition");
        assert_true(dsp_engine_get_hot_ack_token(engine) != 42 && dsp_engine_get_hot_ack_token(engine) != 43,
            "stale refund generations stay unacknowledged while the latest target applies");
    }
    assert_true(dsp_engine_get_hot_ack_token(engine) == 44,
        "only the latest Studio refund generation is acknowledged");
    dsp_engine_destroy(engine);
}

static void test_studio_refund_waits_for_dynamics_and_dynamic_eq(void) {
    const int sample_rate = 48000;
    const uint32_t module_ramp_frames = (uint32_t)(sample_rate / 50);
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {
        0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0,
    };
    dsp_dynamics_config initial_dynamics = bypass_dynamics;
    initial_dynamics.compressor_enabled = 1;
    initial_dynamics.compressor_makeup_db = 6.0;
    dsp_dynamics_config target_dynamics = initial_dynamics;
    target_dynamics.compressor_makeup_db = 0.0;
    dsp_dynamics_hot_params dynamics_update = {0};
    assert_true(dsp_dynamics_prepare_hot_params(sample_rate, &target_dynamics, &dynamics_update),
        "Compressor makeup reduction prepares before headroom refund");

    dsp_dynamic_eq_config initial_dynamic_eq = bypass_dynamic_eq;
    initial_dynamic_eq.enabled = 1;
    initial_dynamic_eq.bands[0].enabled = 1;
    initial_dynamic_eq.bands[0].mode = DSP_DYNAMIC_EQ_BOOST;
    initial_dynamic_eq.bands[0].frequency_hz = 1000.0;
    initial_dynamic_eq.bands[0].q = 1.0;
    initial_dynamic_eq.bands[0].threshold_db = -10.0;
    initial_dynamic_eq.bands[0].ratio = 4.0;
    initial_dynamic_eq.bands[0].attack_ms = 0.1;
    initial_dynamic_eq.bands[0].release_ms = 100.0;
    initial_dynamic_eq.bands[0].max_boost_db = 6.0;
    dsp_dynamic_eq_config target_dynamic_eq = initial_dynamic_eq;
    target_dynamic_eq.bands[0].threshold_db = -30.0;
    dsp_dynamic_eq_hot_update dynamic_eq_update = {0};
    assert_true(dsp_dynamic_eq_prepare_hot_update(sample_rate, &target_dynamic_eq, &dynamic_eq_update),
        "Dynamic EQ boost reduction prepares before headroom refund");

    dsp_graph graph = {0};
    assert_true(dsp_graph_prepare(
            &graph, sample_rate, 2, -6.0, NULL, 0, &initial_dynamics, &bypass_multiband,
            &initial_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
            &bypass_harmonic, &macro, &loudness, &dynamic_bass),
        "combined Dynamics/Dynamic EQ refund graph prepares");
    float input[2] = {0.1f, 0.1f};
    float output[2] = {0.0f, 0.0f};
    uint64_t non_finite_input = 0;
    uint64_t non_finite_output = 0;
    const float starting_gain = graph.gain.linear;
    assert_true(dsp_graph_apply_hot(
            &graph,
            DSP_ENGINE_HOT_FIELD_STUDIO | DSP_ENGINE_HOT_FIELD_DYNAMICS | DSP_ENGINE_HOT_FIELD_DYNAMIC_EQ,
            0.0, 1.0, &macro, &loudness, &dynamic_bass, &dynamics_update, &dynamic_eq_update) ==
            DSP_GRAPH_HOT_APPLIED,
        "combined stage reductions and headroom refund are accepted atomically");
    assert_true(dsp_graph_hot_transition_pending(&graph) && graph.studio_gain_refund_pending,
        "combined command remains pending while module gains settle");
    assert_true(graph.dynamics.compressor_makeup_ramp_remaining == (int)module_ramp_frames &&
            dsp_dynamic_eq_hot_transition_pending(&graph.dynamic_eq),
        "Compressor makeup and Dynamic EQ ramps both gate the refund");

    size_t frames_before_refund = 0;
    while (graph.gain.ramp_remaining == 0 && graph.studio_gain_refund_pending &&
        frames_before_refund <= module_ramp_frames + DSP_DYNAMIC_EQ_CONTROL_FRAMES + 2) {
        dsp_graph_process(&graph, input, output, 1, 2, &non_finite_input, &non_finite_output);
        frames_before_refund++;
        if (graph.gain.ramp_remaining == 0) {
            assert_true(graph.gain.linear == starting_gain,
                "preamp gain stays at the prior safe value until module ramps settle");
        }
    }
    assert_true(frames_before_refund > module_ramp_frames,
        "refund waits for the Compressor makeup ramp to settle");
    assert_true(graph.dynamics.compressor_makeup_ramp_remaining == 0 &&
            !dsp_dynamic_eq_hot_transition_pending(&graph.dynamic_eq),
        "all gain-affecting module transitions settle before preamp refund starts");
    assert_true(graph.gain.ramp_remaining == module_ramp_frames - 1 &&
            dsp_graph_hot_transition_pending(&graph),
        "20 ms refund ramp starts only after module transitions settle");

    while (graph.gain.ramp_remaining > 0) {
        dsp_graph_process(&graph, input, output, 1, 2, &non_finite_input, &non_finite_output);
        if (graph.gain.ramp_remaining > 0) {
            assert_true(dsp_graph_hot_transition_pending(&graph),
                "HOT remains pending until the preamp refund ramp ends");
        }
    }
    assert_true(!dsp_graph_hot_transition_pending(&graph),
        "combined HOT completion follows all module and preamp ramps");
    assert_true(non_finite_input == 0 && non_finite_output == 0,
        "combined headroom refund produces finite samples");
    dsp_graph_close(&graph);
}

static void test_local_peq_warm_preserves_downstream_state_and_latency(void) {
    enum { BLOCK_FRAMES = 512, WARM_BLOCKS = 8 };
    const dsp_dynamics_config compressor = {
        1, -30.0, 4.0, 10.0, 2000.0, 0.0, 0.0, 0, -1.0, 60.0,
    };
    double identity[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, 0.0, 0.0};
    double target_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(48000.0, 1000.0, 6.0, 1.0, target_coefficients);
    float impulse_samples[256 * 2] = {0.0f};
    impulse_samples[0] = 1.0f;
    impulse_samples[1] = 1.0f;
    const dsp_convolver_config convolver = {
        1, 48000, 2, 256, 256 * 2, 0.5f, impulse_samples,
    };
    float input[BLOCK_FRAMES * 2];
    float updated_output[BLOCK_FRAMES * 2] = {0.0f};
    float reference_output[BLOCK_FRAMES * 2] = {0.0f};
    dsp_engine *updated = create_test_engine_with_peq_dynamics_and_convolver(
        48000, 2, BLOCK_FRAMES, identity, 1, &compressor, &convolver);
    dsp_engine *reference = create_test_engine_with_peq_dynamics_and_convolver(
        48000, 2, BLOCK_FRAMES, identity, 1, &compressor, &convolver);
    assert_true(updated != NULL && reference != NULL, "local PEQ warm graphs with compressor and convolver create");

    size_t absolute_frame = 0;
    for (size_t block = 0; block < WARM_BLOCKS; block++) {
        for (size_t frame = 0; frame < BLOCK_FRAMES; frame++) {
            const float sample = 0.25f * sinf((float)(2.0 * acos(-1.0) * 1000.0 *
                (double)(absolute_frame + frame) / 48000.0));
            input[frame * 2] = sample;
            input[frame * 2 + 1] = -0.7f * sample;
        }
        assert_true(dsp_engine_process(updated, input, BLOCK_FRAMES * 2, updated_output,
                BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
            "warm-history update graph accepts its source block");
        assert_true(dsp_engine_process(reference, input, BLOCK_FRAMES * 2, reference_output,
                BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
            "reference graph accepts the matching source block");
        absolute_frame += BLOCK_FRAMES;
    }

    double prepared_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long history_epoch = 0;
    unsigned long long history_end = 0;
    assert_true(dsp_engine_prepare_local_peq_update(
            updated,
            target_coefficients,
            1,
            prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND,
            &history_epoch,
            &history_end),
        "target PEQ coefficients warm from the pre-PEQ processed history");
    int warmed_state_is_nonzero = 0;
    for (size_t index = 0; index < DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND; index++) {
        assert_true(isfinite(prepared_values[index]), "prepared PEQ candidate state is finite");
        if (index >= DSP_BIQUAD_COEFFICIENT_COUNT && fabs(prepared_values[index]) > 1e-3) {
            warmed_state_is_nonzero = 1;
        }
    }
    assert_true(warmed_state_is_nonzero, "replacement PEQ carries warmed state instead of copied old z1/z2");
    assert_true(dsp_engine_bind_hot_update_context(updated, 48000, 2, 7, 9, 3, 1, 1),
        "MEDIA owner binds before preparing its local PEQ update");
    const dsp_engine_local_peq_update local_update = local_peq_update_from_prepared_values(
        42, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(updated, &local_update) == DSP_STATUS_OK,
        "prepared local PEQ state enters the native mailbox");
    assert_true(dsp_engine_get_local_peq_ack_token(updated) == 0,
        "local PEQ update is not acknowledged before its transition");
    assert_true(dsp_engine_get_latency_frames(updated) == dsp_engine_get_latency_frames(reference),
        "local PEQ candidate preserves the active track latency");

    const float first_transition_sample = 0.25f * sinf((float)(2.0 * acos(-1.0) * 1000.0 *
        (double)absolute_frame / 48000.0));
    const float first_transition_input[2] = {first_transition_sample, -0.7f * first_transition_sample};
    float first_updated_output[2] = {0.0f};
    float first_reference_output[2] = {0.0f};
    assert_true(dsp_engine_process(updated, first_transition_input, 2, first_updated_output, 2, 1, 2) == DSP_STATUS_OK,
        "first local PEQ transition sample processes");
    assert_true(dsp_engine_process(reference, first_transition_input, 2, first_reference_output, 2, 1, 2) == DSP_STATUS_OK,
        "matching reference sample processes");
    assert_near(first_updated_output[0], first_reference_output[0], 1e-7,
        "equal-latency fade begins on the unchanged PEQ output");
    assert_near(first_updated_output[1], first_reference_output[1], 1e-7,
        "local fade preserves the second channel at its start");
    assert_near(dsp_engine_test_get_compressor_envelope(updated),
        dsp_engine_test_get_compressor_envelope(reference), 1e-7,
        "PEQ-only update does not reset the active compressor envelope");
    assert_true(dsp_engine_get_local_peq_ack_token(updated) == 0,
        "local PEQ update waits for the complete crossfade before ACK");

    absolute_frame++;
    float previous = first_updated_output[0];
    for (size_t block = 0; block < 2; block++) {
        for (size_t frame = 0; frame < BLOCK_FRAMES; frame++) {
            const float sample = 0.25f * sinf((float)(2.0 * acos(-1.0) * 1000.0 *
                (double)(absolute_frame + frame) / 48000.0));
            input[frame * 2] = sample;
            input[frame * 2 + 1] = -0.7f * sample;
        }
        assert_true(dsp_engine_process(updated, input, BLOCK_FRAMES * 2, updated_output,
                BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
            "crossfaded local PEQ block processes");
        for (size_t frame = 0; frame < BLOCK_FRAMES; frame++) {
            const float sample = updated_output[frame * 2];
            assert_true(isfinite(sample) && isfinite(updated_output[frame * 2 + 1]),
                "local PEQ crossfade output remains finite");
            assert_true(fabsf(sample - previous) < 0.2f,
                "warmed local PEQ transition remains continuous");
            previous = sample;
        }
        absolute_frame += BLOCK_FRAMES;
    }
    assert_true(dsp_engine_get_local_peq_ack_token(updated) == 42,
        "local PEQ update is acknowledged after crossfade completion");
    assert_true(dsp_engine_get_latency_frames(updated) == dsp_engine_get_latency_frames(reference),
        "active PEQ adoption keeps track latency unchanged");
    dsp_engine_destroy(updated);
    dsp_engine_destroy(reference);
}

static void test_local_peq_candidate_catches_up_or_rejects_stale_history(void) {
    enum { HISTORY_FRAMES = 4096, MAX_FRAMES = 1024, CATCH_UP_FRAMES = 128, STALE_FRAMES = 513 };
    double identity[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, 0.0, 0.0};
    double target_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(48000.0, 1200.0, 6.0, 1.0, target_coefficients);
    float input[MAX_FRAMES * 2];
    float output[MAX_FRAMES * 2] = {0.0f};
    dsp_engine *engine = create_test_engine(48000, 2, MAX_FRAMES, 0.0, identity, 1);
    assert_true(engine != NULL, "local PEQ sequence-fence graph creates");
    for (size_t frame = 0; frame < MAX_FRAMES; frame++) {
        const float sample = 0.15f * sinf((float)(2.0 * acos(-1.0) * 1200.0 * (double)frame / 48000.0));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = -0.7f * sample;
    }
    for (size_t processed = 0; processed < HISTORY_FRAMES; processed += MAX_FRAMES) {
        assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
                MAX_FRAMES * 2, MAX_FRAMES, 2) == DSP_STATUS_OK,
            "local PEQ sequence-fence history processes");
    }

    double prepared_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long history_epoch = 0;
    unsigned long long history_end = 0;
    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "local PEQ sequence-fence candidate prepares");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "local PEQ sequence-fence owner binds");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, CATCH_UP_FRAMES, 2) == DSP_STATUS_OK,
        "small candidate age is added to the input history");
    dsp_engine_local_peq_update current = local_peq_update_from_prepared_values(
        50, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &current) == DSP_STATUS_OK,
        "candidate within the audio-thread catch-up window is queued");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, 1, 2) == DSP_STATUS_OK,
        "audio thread catches the candidate up before transition");
    assert_true(dsp_engine_get_local_peq_rejected_token(engine) != current.generation_token,
        "bounded candidate catch-up is not rejected");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, 479, 2) == DSP_STATUS_OK,
        "caught-up candidate completes its equal-latency transition");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) == current.generation_token,
        "caught-up candidate is acknowledged after its transition");

    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "second candidate prepares from the latest history");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, STALE_FRAMES, 2) == DSP_STATUS_OK,
        "second candidate ages beyond the bounded catch-up window");
    dsp_engine_local_peq_update stale = local_peq_update_from_prepared_values(
        51, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale) == DSP_STATUS_OK,
        "aged candidate reaches the audio-thread freshness check");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, 1, 2) == DSP_STATUS_OK,
        "audio thread evaluates an aged candidate without blocking");
    assert_true(dsp_engine_get_local_peq_rejected_token(engine) == stale.generation_token,
        "candidate older than the catch-up cap is rejected");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) != stale.generation_token,
        "stale candidate never receives an ACK");
    dsp_engine_destroy(engine);
}

static void test_rejected_local_peq_generation_retries_with_fresh_history(void) {
    enum { HISTORY_FRAMES = 4096, MAX_FRAMES = 1024, STALE_FRAMES = 513 };
    double identity[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, 0.0, 0.0};
    double target_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(48000.0, 1200.0, 6.0, 1.0, target_coefficients);
    float input[MAX_FRAMES * 2];
    float output[MAX_FRAMES * 2] = {0.0f};
    dsp_engine *engine = create_test_engine(48000, 2, MAX_FRAMES, 0.0, identity, 1);
    assert_true(engine != NULL, "local PEQ same-generation retry graph creates");
    for (size_t frame = 0; frame < MAX_FRAMES; frame++) {
        const float sample = 0.15f * sinf((float)(2.0 * acos(-1.0) * 1200.0 * (double)frame / 48000.0));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = -0.7f * sample;
    }
    for (size_t processed = 0; processed < HISTORY_FRAMES; processed += MAX_FRAMES) {
        assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
                MAX_FRAMES * 2, MAX_FRAMES, 2) == DSP_STATUS_OK,
            "local PEQ same-generation retry history processes");
    }
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "local PEQ same-generation retry owner binds");

    double stale_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long stale_epoch = 0;
    unsigned long long stale_end = 0;
    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, stale_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &stale_epoch, &stale_end),
        "first local PEQ candidate prepares");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, STALE_FRAMES, 2) == DSP_STATUS_OK,
        "first local PEQ candidate ages beyond the render-thread catch-up bound");
    dsp_engine_local_peq_update stale = local_peq_update_from_prepared_values(
        52, 7, 9, 3, 1, stale_epoch, stale_end, 1, stale_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale) == DSP_STATUS_OK,
        "aged local PEQ candidate enters the native queue");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, 1, 2) == DSP_STATUS_OK,
        "render thread rejects an over-age local PEQ candidate");
    assert_true(dsp_engine_get_local_peq_rejected_token(engine) == stale.generation_token,
        "over-age candidate publishes a retryable rejection token");

    double fresh_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long fresh_epoch = 0;
    unsigned long long fresh_end = 0;
    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, fresh_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &fresh_epoch, &fresh_end),
        "fresh local PEQ candidate prepares for the same config generation");
    assert_true(fresh_end > stale_end, "retry candidate uses a newer pre-PEQ history snapshot");
    dsp_engine_local_peq_update retry = local_peq_update_from_prepared_values(
        52, 7, 9, 3, 1, fresh_epoch, fresh_end, 1, fresh_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &retry) == DSP_STATUS_OK,
        "same config generation can be retried after render-thread rejection");
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, MAX_FRAMES, 2) == DSP_STATUS_OK,
        "fresh local PEQ retry transitions without blocking the render thread");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) == retry.generation_token,
        "same-generation fresh candidate receives an active acknowledgment");
    dsp_engine_destroy(engine);
}

static void assert_local_peq_cancel_fades_back_smoothly(size_t frames_before_cancel, uint64_t generation) {
    enum { SAMPLE_RATE = 48000, HISTORY_FRAMES = 4096, MAX_FRAMES = 512 };
    double identity[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, 0.0, 0.0};
    double target_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(SAMPLE_RATE, 1000.0, 18.0, 1.0, target_coefficients);
    float input[MAX_FRAMES * 2];
    float output[MAX_FRAMES * 2] = {0.0f};
    dsp_engine *engine = create_test_engine(SAMPLE_RATE, 2, MAX_FRAMES, 0.0, identity, 1);
    assert_true(engine != NULL, "PEQ cancellation continuity graph creates");

    for (size_t block_start = 0; block_start < HISTORY_FRAMES; block_start += MAX_FRAMES) {
        for (size_t frame = 0; frame < MAX_FRAMES; frame++) {
            const size_t absolute_frame = block_start + frame;
            const float sample = 0.2f * sinf((float)(2.0 * acos(-1.0) * 1000.0 *
                (double)absolute_frame / SAMPLE_RATE));
            input[frame * 2] = sample;
            input[frame * 2 + 1] = -0.7f * sample;
        }
        assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
                MAX_FRAMES * 2, MAX_FRAMES, 2) == DSP_STATUS_OK,
            "PEQ cancellation continuity history processes");
    }

    double prepared_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long history_epoch = 0;
    unsigned long long history_end = 0;
    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "PEQ cancellation continuity candidate warms");
    assert_true(dsp_engine_bind_hot_update_context(engine, SAMPLE_RATE, 2, 7, 9, 3, 1, 1),
        "PEQ cancellation continuity owner binds");
    const dsp_engine_local_peq_update candidate = local_peq_update_from_prepared_values(
        generation, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &candidate) == DSP_STATUS_OK,
        "PEQ cancellation continuity candidate queues");

    for (size_t frame = 0; frame < frames_before_cancel; frame++) {
        const size_t absolute_frame = HISTORY_FRAMES + frame;
        const float sample = 0.2f * sinf((float)(2.0 * acos(-1.0) * 1000.0 *
            (double)absolute_frame / SAMPLE_RATE));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = -0.7f * sample;
    }
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, (int)frames_before_cancel, 2) == DSP_STATUS_OK,
        "PEQ cancellation starts at its requested transition point");
    float previous_sample = output[(frames_before_cancel - 1) * 2];
    assert_true(dsp_engine_get_local_peq_ack_token(engine) != candidate.generation_token,
        "in-progress PEQ candidate has no premature ACK");
    assert_true(dsp_engine_cancel_local_peq_update(engine, candidate.generation_token) == DSP_STATUS_OK,
        "rollout cancellation is requested during the PEQ crossfade");

    const size_t fade_frames = frames_before_cancel + 1;
    for (size_t frame = 0; frame < fade_frames; frame++) {
        const size_t absolute_frame = HISTORY_FRAMES + frames_before_cancel + frame;
        const float sample = 0.2f * sinf((float)(2.0 * acos(-1.0) * 1000.0 *
            (double)absolute_frame / SAMPLE_RATE));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = -0.7f * sample;
    }
    assert_true(dsp_engine_process(engine, input, MAX_FRAMES * 2, output,
            MAX_FRAMES * 2, (int)fade_frames, 2) == DSP_STATUS_OK,
        "PEQ cancellation completes its fade back to the active branch");
    for (size_t frame = 0; frame < fade_frames; frame++) {
        const float sample = output[frame * 2];
        assert_true(isfinite(sample) && isfinite(output[frame * 2 + 1]),
            "PEQ cancellation output remains finite");
        assert_true(fabsf(sample - previous_sample) < 0.3f,
            "early, middle, and late cancellation preserve PCM continuity");
        previous_sample = sample;
    }
    assert_true(dsp_engine_get_local_peq_rejected_token(engine) == candidate.generation_token,
        "cancelled PEQ candidate is rejected without an ACK");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) != candidate.generation_token,
        "cancelled PEQ transition never reports success");
    dsp_engine_destroy(engine);
}

static void test_local_peq_cancel_fades_back_smoothly_at_early_middle_and_late_points(void) {
    assert_local_peq_cancel_fades_back_smoothly(1, 60);
    assert_local_peq_cancel_fades_back_smoothly(240, 61);
    assert_local_peq_cancel_fades_back_smoothly(470, 62);
}

static void test_local_peq_rollout_cancel_stops_queued_and_active_transitions(void) {
    enum { BLOCK_FRAMES = 512, SHORT_BLOCK_FRAMES = 64 };
    const dsp_dynamics_config compressor = {
        1, -30.0, 4.0, 10.0, 2000.0, 0.0, 0.0, 0, -1.0, 60.0,
    };
    double identity[DSP_BIQUAD_COEFFICIENT_COUNT] = {1.0, 0.0, 0.0, 0.0, 0.0};
    double target_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(48000.0, 1000.0, 6.0, 1.0, target_coefficients);
    float input[BLOCK_FRAMES * 2];
    float output[BLOCK_FRAMES * 2] = {0.0f};
    dsp_engine *engine = create_test_engine_with_peq_dynamics_and_convolver(
        48000, 2, BLOCK_FRAMES, identity, 1, &compressor, &bypass_convolver);
    assert_true(engine != NULL, "local PEQ rollout cancellation graph creates");
    for (size_t frame = 0; frame < BLOCK_FRAMES; frame++) {
        const float sample = 0.25f * sinf((float)(2.0 * acos(-1.0) * 1000.0 * (double)frame / 48000.0));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = -0.7f * sample;
    }
    for (size_t block = 0; block < 8; block++) {
        assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
                BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
            "local PEQ cancellation history block processes");
    }

    double prepared_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long history_epoch = 0;
    unsigned long long history_end = 0;
    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "local PEQ cancellation candidate warms on the control thread");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "local PEQ cancellation owner binds");

    dsp_engine_local_peq_update queued = local_peq_update_from_prepared_values(
        90, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &queued) == DSP_STATUS_OK,
        "rollout candidate enters the native mailbox before disable");
    assert_true(dsp_engine_cancel_local_peq_update(engine, queued.generation_token) == DSP_STATUS_OK,
        "rollout disable publishes a lock-free cancellation request");
    assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
            BLOCK_FRAMES * 2, SHORT_BLOCK_FRAMES, 2) == DSP_STATUS_OK,
        "audio thread consumes cancellation before starting queued transition");
    assert_true(dsp_engine_get_local_peq_rejected_token(engine) == queued.generation_token,
        "queued rollout candidate is rejected without starting its crossfade");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) != queued.generation_token,
        "cancelled queued candidate receives no active ACK");

    dsp_engine_local_peq_update active = local_peq_update_from_prepared_values(
        91, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &active) == DSP_STATUS_OK,
        "new local PEQ candidate queues after the cancelled generation");
    assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
            BLOCK_FRAMES * 2, SHORT_BLOCK_FRAMES, 2) == DSP_STATUS_OK,
        "audio thread begins the new local PEQ transition");
    assert_true(dsp_engine_cancel_local_peq_update(engine, active.generation_token) == DSP_STATUS_OK,
        "rollout disable requests cancellation of an active PEQ crossfade");
    assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
            BLOCK_FRAMES * 2, SHORT_BLOCK_FRAMES, 2) == DSP_STATUS_OK,
        "audio thread stops the active PEQ crossfade at a block boundary");
    assert_true(dsp_engine_get_local_peq_rejected_token(engine) == active.generation_token,
        "active rollout candidate is rejected after cancellation");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) != active.generation_token,
        "cancelled active candidate receives no active ACK");
    assert_true(dsp_engine_test_get_compressor_envelope(engine) > 0.0f,
        "cancelling a PEQ crossfade preserves the active compressor envelope");
    dsp_engine_destroy(engine);
}

static void test_local_peq_candidate_history_and_owner_fences(void) {
    enum { BLOCK_FRAMES = 512 };
    double target_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_test_peak_coefficients(48000.0, 1200.0, 4.0, 1.0, target_coefficients);
    float input[BLOCK_FRAMES * 2];
    float output[BLOCK_FRAMES * 2] = {0.0f};
    dsp_engine *engine = create_test_engine(48000, 2, BLOCK_FRAMES, 0.0, NULL, 0);
    assert_true(engine != NULL, "local PEQ candidate fence graph creates");

    double prepared_values[DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0.0};
    unsigned long long history_epoch = 0;
    unsigned long long history_end = 0;
    assert_true(!dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "local PEQ preparation defers until enough pre-PEQ history exists");
    for (size_t frame = 0; frame < BLOCK_FRAMES; frame++) {
        input[frame * 2] = 0.1f * sinf((float)frame * 0.03f);
        input[frame * 2 + 1] = -input[frame * 2];
    }
    for (size_t block = 0; block < 8; block++) {
        assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
                BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
            "history fence setup block processes");
    }
    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "pre-PEQ history becomes available to a background candidate");

    dsp_engine_local_peq_update update = local_peq_update_from_prepared_values(
        40, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &update) == DSP_STATUS_STALE_UPDATE,
        "unbound owner cannot enqueue a warmed local PEQ candidate");
    assert_true(!dsp_engine_bind_hot_update_context(engine, 44100, 2, 7, 9, 3, 1, 1),
        "format mismatch cannot bind the PEQ stage owner");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "matching MEDIA format binds the PEQ stage owner");
    dsp_engine_local_peq_update stale = update;
    stale.editor_owner_epoch++;
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale) == DSP_STATUS_STALE_UPDATE,
        "stale editor owner cannot enqueue a warm candidate");
    stale = update;
    stale.renderer_owner_token++;
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale) == DSP_STATUS_STALE_UPDATE,
        "stale renderer owner cannot enqueue a warm candidate");
    stale = update;
    stale.pcm_format_epoch++;
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale) == DSP_STATUS_STALE_UPDATE,
        "stale PCM format epoch cannot enqueue a warm candidate");
    stale = update;
    stale.media_role = 0;
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale) == DSP_STATUS_STALE_UPDATE,
        "non-MEDIA role cannot enqueue a warm candidate");

    assert_true(dsp_engine_enqueue_local_peq_update(engine, &update) == DSP_STATUS_OK,
        "current owner enqueues a warmed PEQ candidate");
    dsp_engine_local_peq_update superseding = local_peq_update_from_prepared_values(
        41, 7, 9, 3, 1, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &superseding) == DSP_STATUS_QUEUE_FULL,
        "single-slot mailbox refuses to overwrite an unconsumed PEQ candidate");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 8, 9, 3, 2, 1),
        "new editor owner invalidates a queued PEQ candidate");
    assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
            BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
        "stale queued PEQ candidate is safely discarded on the render thread");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) == 0,
        "stale owner candidate never receives an ACK");

    assert_true(dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "current owner can prepare a replacement candidate from the retained input history");
    superseding = local_peq_update_from_prepared_values(
        41, 8, 9, 3, 2, history_epoch, history_end, 1, prepared_values);
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &superseding) == DSP_STATUS_OK,
        "new owner enqueues its own warmed candidate");
    assert_true(dsp_engine_process(engine, input, BLOCK_FRAMES * 2, output,
            BLOCK_FRAMES * 2, BLOCK_FRAMES, 2) == DSP_STATUS_OK,
        "current owner PEQ transition processes");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) == 41,
        "current owner candidate is acknowledged after the transition");

    dsp_engine_reset(engine);
    assert_true(dsp_engine_process(engine, input, 2, output, 2, 1, 2) == DSP_STATUS_OK,
        "format reset starts a fresh PEQ input-history epoch");
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &superseding) == DSP_STATUS_STALE_UPDATE,
        "candidate warmed before an AudioTrack reset cannot cross into the new history epoch");
    assert_true(!dsp_engine_prepare_local_peq_update(
            engine, target_coefficients, 1, prepared_values,
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND, &history_epoch, &history_end),
        "new history epoch remains deferred until its own warm history is sufficient");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 8, 9, 3, 2, 0),
        "role change invalidates the MEDIA PEQ owner context");
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &superseding) == DSP_STATUS_STALE_UPDATE,
        "role change rejects the prior MEDIA candidate");
    dsp_engine_destroy(engine);
}

static void test_studio_neutral_macro_preserves_exact_samples(void) {
    const dsp_macro_config macro = {1, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    const float input[] = {0.1234567f, -0.7654321f, 0.3333333f, -0.2222222f};
    float output[4] = {0.0f};
    dsp_engine *engine = create_studio_test_engine(48000, 2, 2, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "neutral studio graph creates");
    assert_true(dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "neutral studio block processes");
    assert_true(memcmp(input, output, sizeof(input)) == 0, "centered macro controls preserve exact samples");
    dsp_engine_destroy(engine);
}

static void test_studio_hot_command_is_acknowledged_after_its_block(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[1024];
    float output[1024] = {0.0f};
    for (size_t frame = 0; frame < 512; frame++) {
        input[frame * 2] = 0.5f;
        input[frame * 2 + 1] = -0.5f;
    }
    dsp_engine *engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "hot update studio graph creates");
    const dsp_engine_hot_update update = {
        .generation_token = 42,
        .generation = 42,
        .editor_owner_epoch = 7,
        .renderer_owner_token = 9,
        .pcm_format_epoch = 3,
        .hot_context_token = 1,
        .media_role = 1,
        .gain_db = 0.0,
        .gain_linear = 1.0,
        .gain_pair_validated = 1,
        .field_mask = DSP_ENGINE_HOT_FIELD_STUDIO,
        .hot_version = 1,
        .macro = {1, 0.0, 0.0, 0.0, 1.25},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0},
    };
    dsp_engine_hot_update next_update = update;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &update), "unbound owner context rejects hot command");
    assert_true(!dsp_engine_bind_hot_update_context(engine, 44100, 2, 7, 9, 3, 1, 1),
        "format mismatch cannot bind hot owner context");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "matching MEDIA owner context binds");
    dsp_engine_hot_update stale_update = update;
    stale_update.editor_owner_epoch++;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &stale_update), "stale editor epoch is rejected");
    stale_update = update;
    stale_update.renderer_owner_token++;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &stale_update), "stale renderer owner is rejected");
    stale_update = update;
    stale_update.pcm_format_epoch++;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &stale_update), "stale PCM format epoch is rejected");
    stale_update = update;
    stale_update.media_role = 0;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &stale_update), "non-MEDIA hot command is rejected");
    assert_true(dsp_engine_enqueue_hot_update(engine, &update), "current owner command enters queue");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 8, 9, 3, 2, 1),
        "new editor epoch invalidates an already queued command");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "stale queued command block processes without applying it");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0, "stale queued command is not acknowledged");
    dsp_engine_hot_update current_update = update;
    current_update.generation = 43;
    current_update.generation_token = 43;
    current_update.editor_owner_epoch = 8;
    current_update.pcm_format_epoch = 3;
    current_update.hot_context_token = 2;
    next_update.generation_token = 44;
    next_update.generation = 44;
    next_update.editor_owner_epoch = 8;
    next_update.hot_context_token = 2;
    assert_true(dsp_engine_enqueue_hot_update(engine, &current_update), "current editor epoch command enters queue");
    assert_true(dsp_engine_enqueue_hot_update(engine, &next_update), "bounded hot mailbox accepts a newer queued generation");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0, "hot command waits for a processed block");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "hot command block processes");
    for (int block = 0; block < 8 && dsp_engine_get_hot_ack_token(engine) != 44; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "bounded HOT transition completion block processes");
    }
    assert_true(dsp_engine_get_hot_ack_token(engine) == 44, "latest queued generation is applied and acknowledged");
    assert_true(fabsf(output[1022]) > 0.5f && fabsf(output[1022]) < 0.63f,
        "macro width moves toward its target without an abrupt step");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 0, 0, 0, 0, 0),
        "renderer close invalidates native owner context");
    assert_true(!dsp_engine_enqueue_hot_update(engine, &next_update),
        "closed renderer cannot enqueue a stale hot command");
    dsp_engine_destroy(engine);
}

static void test_preamp_hot_update_is_fenced_ramped_and_acknowledged_at_completion(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[1024];
    float output[1024] = {0.0f};
    for (size_t frame = 0; frame < 512; frame++) input[frame * 2] = input[frame * 2 + 1] = 0.5f;
    dsp_engine *engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "preamp HOT graph creates");
    const dsp_engine_preamp_update update = {
        .version = 1, .generation_token = 42, .generation = 42, .editor_owner_epoch = 7,
        .renderer_owner_token = 9, .pcm_format_epoch = 3, .hot_context_token = 1,
        .media_role = 1, .gain_db = -6.0,
        .gain_linear = 0.5011872336,
        .gain_pair_validated = 1,
    };
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &update), "unbound preamp owner is rejected");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "preamp HOT binds current MEDIA owner");
    dsp_engine_preamp_update invalid = update;
    invalid.version = 2;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "unknown preamp payload version is rejected");
    invalid = update;
    invalid.editor_owner_epoch++;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "stale preamp editor epoch is rejected");
    invalid = update;
    invalid.renderer_owner_token++;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "stale preamp renderer owner is rejected");
    invalid = update;
    invalid.pcm_format_epoch++;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "stale preamp PCM format is rejected");
    invalid = update;
    invalid.media_role = 0;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "non-MEDIA preamp update is rejected");
    invalid = update;
    invalid.gain_db = -5.99;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "preamp target without 6 dB reserve is rejected");
    invalid = update;
    invalid.gain_db = -120.0;
    invalid.gain_pair_validated = 0;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "mismatched dB/linear preamp pair is rejected");
    assert_true(dsp_engine_enqueue_preamp_update(engine, &update), "valid versioned preamp update is queued");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "first sample-ramp block processes");
    assert_near(output[0], 0.5, 1e-6, "ramp starts from the last-good preamp value");
    assert_true(output[1022] < output[0] && output[1022] > 0.25f,
        "preamp gain changes smoothly inside the PCM block");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 0, "preamp update is not acknowledged before ramp completion");
    dsp_engine_preamp_update latest_update = update;
    latest_update.generation = 43;
    latest_update.generation_token = 43;
    latest_update.gain_db = -12.0;
    latest_update.gain_linear = 0.2511886432;
    assert_true(dsp_engine_enqueue_preamp_update(engine, &latest_update),
        "newer preamp generation replaces the target while a ramp is active");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "superseding sample-ramp block processes");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 0, "superseded generation does not receive a final ACK");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "latest target ramp completion block processes");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 43, "final ACK follows the latest complete preamp ramp");
    assert_near(output[1022], 0.5 * pow(10.0, -12.0 / 20.0), 1e-5,
        "completed ramp reaches the latest bounded preamp target");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 0, 0, 0, 0, 0),
        "preamp renderer close invalidates owner context");
    invalid = latest_update;
    assert_true(!dsp_engine_enqueue_preamp_update(engine, &invalid), "closed preamp renderer rejects stale work");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 8, 9, 4, 2, 1),
        "preamp renderer can bind a new owner tuple");
    dsp_engine_preamp_update rebound_update = latest_update;
    rebound_update.generation = 44;
    rebound_update.generation_token = 44;
    rebound_update.editor_owner_epoch = 8;
    rebound_update.pcm_format_epoch = 4;
    rebound_update.hot_context_token = 2;
    rebound_update.gain_db = -18.0;
    rebound_update.gain_linear = 0.1258925412;
    assert_true(dsp_engine_enqueue_preamp_update(engine, &rebound_update), "rebound preamp target queues");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "rebound preamp ramp begins");
    const float last_good = output[1022];
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 9, 10, 5, 3, 1),
        "owner rebind invalidates an active preamp ramp");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "rebound stale ramp block processes");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 0, "stale rebind target receives no ACK");
    assert_true(fabsf(output[1022] - last_good) < 1e-4f, "rebind keeps the last-good preamp gain");
    dsp_engine_destroy(engine);
}

static void test_preamp_hot_ramp_preserves_active_compressor_and_convolver_state(void) {
    const dsp_dynamics_config dynamics = {
        1, -20.0, 4.0, 10.0, 100.0, 6.0, 0.0, 1, -1.0, 60.0,
    };
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float impulse[512] = {0.0f};
    impulse[0] = 1.0f;
    impulse[1] = 1.0f;
    const dsp_convolver_config convolver = {
        .enabled = 1,
        .sample_rate = 48000,
        .channels = 2,
        .frames = 256,
        .sample_count = 512,
        .wet = 1.0f,
        .samples = impulse,
    };
    dsp_graph graph = {0};
    assert_true(dsp_graph_prepare(
            &graph, 48000, 2, 0.0, NULL, 0, &dynamics, &bypass_multiband, &bypass_dynamic_eq,
            NULL, 0, NULL, 0, &bypass_spatial, &convolver, &bypass_harmonic,
            &macro, &loudness, &dynamic_bass),
        "active Compressor and Convolver graph prepares");

    float input[1024];
    float output[1024] = {0.0f};
    for (size_t frame = 0; frame < 512; frame++) {
        const float sample = 1.2f * sinf((float)(2.0 * 3.14159265358979323846 * 1000.0 * frame / 48000.0));
        input[frame * 2] = sample;
        input[frame * 2 + 1] = sample;
    }
    uint64_t non_finite_input = 0;
    uint64_t non_finite_output = 0;
    dsp_graph_process(&graph, input, output, 511, 2, &non_finite_input, &non_finite_output);
    assert_true(graph.dynamics.compressor_envelope > 0.0f && graph.dynamics.limiter_gain < 1.0f,
        "Compressor envelope and Limiter gain are active before the Preamp update");
    assert_true(graph.convolver.active && graph.convolver.spectrum_write_index > 0,
        "Convolver history is active before the Preamp update");

    const dsp_dynamics dynamics_before = graph.dynamics;
    const dsp_convolver convolver_before = graph.convolver;
    const int latency_before = dsp_convolver_latency_frames(&graph.convolver);
    assert_true(dsp_graph_apply_preamp_hot(&graph, -6.0, 0.5011872336),
        "bounded Preamp HOT ramp starts with stateful stages active");
    assert_true(memcmp(&graph.dynamics, &dynamics_before, sizeof(dynamics_before)) == 0,
        "Preamp HOT preserves Compressor envelope and Limiter gain state");
    assert_true(memcmp(&graph.convolver, &convolver_before, sizeof(convolver_before)) == 0,
        "Preamp HOT preserves Convolver instance and processing history");
    assert_true(dsp_convolver_latency_frames(&graph.convolver) == latency_before,
        "Preamp HOT keeps the active Convolver latency unchanged");
    assert_true(graph.gain.ramp_remaining == 48000 / 50,
        "Preamp target is applied through the prepared 20 ms sample ramp");

    dsp_graph_process(&graph, input, output, 512, 2, &non_finite_input, &non_finite_output);
    assert_true(graph.gain.ramp_remaining == 48000 / 50 - 512,
        "Preamp ramp advances on the current graph without rebuilding stateful stages");
    assert_true(graph.dynamics.compressor_envelope > 0.0f && graph.convolver.active,
        "Compressor and Convolver continue processing during the Preamp ramp");
    assert_true(non_finite_input == 0 && non_finite_output == 0,
        "state-preserving Preamp ramp produces finite PCM");
    for (size_t sample = 0; sample < 1024; sample++) {
        assert_true(isfinite(output[sample]), "state-preserving Preamp ramp output is finite");
    }
    dsp_graph_close(&graph);
}

static void test_preamp_hot_mailbox_coalesces_and_preserves_concurrent_studio_gain_ramps(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[1024];
    float output[1024] = {0.0f};
    for (size_t frame = 0; frame < 512; frame++) input[frame * 2] = input[frame * 2 + 1] = 0.5f;

    dsp_engine *engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "concurrent Preamp/Studio HOT graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "concurrent Preamp/Studio HOT binds MEDIA owner");
    const dsp_engine_preamp_update preamp = {
        .version = 1, .generation_token = 42, .generation = 42, .editor_owner_epoch = 7,
        .renderer_owner_token = 9, .pcm_format_epoch = 3, .hot_context_token = 1,
        .media_role = 1, .gain_db = -6.0, .gain_linear = 0.5011872336, .gain_pair_validated = 1,
    };
    const dsp_engine_hot_update studio = {
        .generation_token = 43, .generation = 43, .editor_owner_epoch = 7,
        .renderer_owner_token = 9, .pcm_format_epoch = 3, .hot_context_token = 1,
        .media_role = 1, .gain_db = -6.0,
        .gain_linear = 0.5011872336, .gain_pair_validated = 1,
        .field_mask = DSP_ENGINE_HOT_FIELD_STUDIO,
        .hot_version = 1,
        .macro = {1, 0.0, 0.0, 0.0, 1.25},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0},
    };
    assert_true(dsp_engine_enqueue_preamp_update(engine, &preamp), "preamp command enters shared bounded HOT mailbox");
    assert_true(dsp_engine_enqueue_hot_update(engine, &studio), "compatible Studio HOT queues behind Preamp ramp");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "concurrent Preamp/Studio first block processes");
    assert_near(output[0], 0.5, 1e-6, "compatible Studio HOT preserves the Preamp ramp start");
    assert_true(output[1022] < output[0] && output[1022] > 0.25f,
        "compatible Studio HOT does not cancel the in-block Preamp ramp");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 0,
        "neither concurrent HOT command is acknowledged before the ramp finishes");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 448, 2) == DSP_STATUS_OK,
        "concurrent Preamp/Studio ramp completion block processes");
    for (int block = 0; block < 8 && dsp_engine_get_hot_ack_token(engine) != 43; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 448, 2) == DSP_STATUS_OK,
            "concurrent Studio HOT transition completion block processes");
    }
    assert_true(dsp_engine_get_hot_ack_token(engine) == 43 && dsp_engine_get_preamp_ack_token(engine) == 43,
        "latest compatible Studio HOT receives the final shared mailbox ACK");
    assert_true(fabsf(output[894] - 0.5f * 0.50118723f) < 1e-3f,
        "compatible Studio HOT leaves the completed Preamp ramp at its target");
    dsp_engine_destroy(engine);

    engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "Preamp burst coalescing graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "Preamp burst coalescing graph binds MEDIA owner");
    dsp_engine_preamp_update older = preamp;
    dsp_engine_preamp_update latest = preamp;
    latest.generation = 44;
    latest.generation_token = 44;
    latest.gain_db = -12.0;
    latest.gain_linear = 0.2511886432;
    assert_true(dsp_engine_enqueue_preamp_update(engine, &older), "first burst target queues");
    assert_true(dsp_engine_enqueue_preamp_update(engine, &latest), "second burst target queues in the bounded mailbox");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "Preamp burst block processes");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 0, "burst target is not ACKed before its full ramp");
    assert_true(output[1022] < 0.5f && output[1022] > 0.25f,
        "PCM follows the newest burst target rather than an old abrupt gain");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 448, 2) == DSP_STATUS_OK,
        "latest burst ramp completion block processes");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 44,
        "only the newest Preamp burst generation receives an ACK");
    dsp_engine_destroy(engine);

    engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "incompatible Studio HOT deferral graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "incompatible Studio HOT deferral graph binds MEDIA owner");
    dsp_engine_hot_update incompatible_studio = studio;
    incompatible_studio.generation = 43;
    incompatible_studio.generation_token = 43;
    incompatible_studio.gain_db = -12.0;
    incompatible_studio.gain_linear = 0.2511886432;
    assert_true(dsp_engine_enqueue_preamp_update(engine, &preamp), "Preamp target queues before incompatible Studio HOT");
    assert_true(dsp_engine_enqueue_hot_update(engine, &incompatible_studio),
        "incompatible Studio HOT remains queued for safe deferral");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "incompatible Studio HOT is deferred while Preamp ramp is active");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0, "deferred Studio HOT has no early ACK");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 448, 2) == DSP_STATUS_OK,
        "original Preamp ramp finishes before incompatible Studio HOT");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0, "superseded Preamp target is not ACKed over newer Studio HOT");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "deferred Studio HOT applies after Preamp ramp completion");
    for (int block = 0; block < 8 && dsp_engine_get_hot_ack_token(engine) != 43; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "deferred Studio HOT transition completion block processes");
    }
    assert_true(dsp_engine_get_hot_ack_token(engine) == 43, "deferred latest Studio HOT is eventually acknowledged");
    dsp_engine_destroy(engine);
}

static void test_native_hot_and_local_warm_share_one_generation_order(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[1024] = {0.1f};
    float output[1024] = {0.0f};
    dsp_engine *engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "cross-lane generation graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "cross-lane generation graph binds MEDIA owner");
    const dsp_engine_local_peq_update older_local = {
        .generation_token = 50, .generation = 50, .editor_owner_epoch = 7,
        .renderer_owner_token = 9, .pcm_format_epoch = 3, .history_epoch_sequence = 0,
        .history_end_sequence = 0, .hot_context_token = 1, .media_role = 1, .peq_count = 0,
    };
    dsp_engine_preamp_update newer_preamp = {
        .version = 1, .generation_token = 51, .generation = 51, .editor_owner_epoch = 7,
        .renderer_owner_token = 9, .pcm_format_epoch = 3, .hot_context_token = 1,
        .media_role = 1, .gain_db = -6.0, .gain_linear = 0.5011872336, .gain_pair_validated = 1,
    };
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &older_local) == DSP_STATUS_OK,
        "older LOCAL_WARM enters its bounded queue first");
    assert_true(dsp_engine_enqueue_preamp_update(engine, &newer_preamp),
        "newer Preamp HOT advances the shared generation order");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "cross-lane stale LOCAL_WARM block processes");
    assert_true(dsp_engine_get_local_peq_ack_token(engine) == 0 &&
        dsp_engine_get_local_peq_rejected_token(engine) == 50,
        "older LOCAL_WARM is rejected instead of ACKing after newer Preamp HOT");
    assert_true(dsp_engine_get_preamp_ack_token(engine) == 0,
        "latest Preamp HOT remains pending until its ramp is complete");
    dsp_engine_destroy(engine);

    engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "reverse cross-lane generation graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "reverse cross-lane generation graph binds MEDIA owner");
    assert_true(dsp_engine_enqueue_preamp_update(engine, &newer_preamp), "newer HOT generation is accepted first");
    dsp_engine_local_peq_update stale_local = older_local;
    stale_local.generation = 50;
    stale_local.generation_token = 50;
    assert_true(dsp_engine_enqueue_local_peq_update(engine, &stale_local) == DSP_STATUS_STALE_UPDATE,
        "older LOCAL_WARM cannot regress an already queued HOT generation");
    dsp_engine_destroy(engine);
}

static void test_studio_hot_update_rejects_invalid_module_configs_atomically(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    dsp_engine *engine = create_studio_test_engine(48000, 2, 2, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "invalid hot update test graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "invalid hot update test binds MEDIA owner context");
    const dsp_engine_hot_update valid_update = {
        .generation_token = 42,
        .generation = 42,
        .editor_owner_epoch = 7,
        .renderer_owner_token = 9,
        .pcm_format_epoch = 3,
        .hot_context_token = 1,
        .media_role = 1,
        .gain_db = 6.0,
        .gain_linear = 1.995262315,
        .gain_pair_validated = 1,
        .field_mask = DSP_ENGINE_HOT_FIELD_STUDIO,
        .hot_version = 1,
        .macro = {1, 0.0, 0.0, 0.0, 1.0},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0},
    };
    dsp_engine_hot_update invalid_update = valid_update;
    invalid_update.macro.bass_gain_db = 5.0;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &invalid_update), "invalid macro config is rejected");
    invalid_update = valid_update;
    invalid_update.smart_loudness.low_gain_db = 3.1;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &invalid_update), "invalid loudness config is rejected");
    invalid_update = valid_update;
    invalid_update.dynamic_bass.high_threshold_dbfs = invalid_update.dynamic_bass.low_threshold_dbfs;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &invalid_update), "invalid dynamic bass config is rejected");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0, "invalid module payload is never acknowledged");
    dsp_engine_destroy(engine);

    dsp_graph graph = {0};
    dsp_macro_config invalid_macro = macro;
    invalid_macro.air_gain_db = 3.0;
    assert_true(!dsp_graph_apply_studio_hot(&graph, 6.0, 1.995262315, &invalid_macro, &loudness, &dynamic_bass),
        "graph rejects invalid module config before applying hot changes");
    assert_near(graph.gain.decibels, 0.0, 0.0, "invalid module config does not partially change preamp");
}

static void test_studio_hot_update_validates_ack_generation_token(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[4] = {0.25f, -0.25f, 0.5f, -0.5f};
    float output[4] = {0.0f};
    dsp_engine *engine = create_studio_test_engine(48000, 2, 2, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "generation token test graph creates");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 7, 9, 3, 1, 1),
        "generation token test binds MEDIA owner context");
    const dsp_engine_hot_update update = {
        .generation_token = 42,
        .generation = 42,
        .editor_owner_epoch = 7,
        .renderer_owner_token = 9,
        .pcm_format_epoch = 3,
        .hot_context_token = 1,
        .media_role = 1,
        .gain_db = 0.0,
        .gain_linear = 1.0,
        .gain_pair_validated = 1,
        .field_mask = DSP_ENGINE_HOT_FIELD_STUDIO,
        .hot_version = 1,
        .macro = {1, 0.0, 0.0, 0.0, 1.0},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0},
    };
    dsp_engine_hot_update invalid_update = update;
    invalid_update.generation_token = 0;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &invalid_update), "zero generation token is rejected");
    invalid_update = update;
    invalid_update.generation_token = 41;
    assert_true(!dsp_engine_enqueue_hot_update(engine, &invalid_update), "mismatched generation token is rejected");
    assert_true(dsp_engine_enqueue_hot_update(engine, &update), "matching generation token is accepted");
    assert_true(dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "matching generation token update processes");
    for (int block = 0; block < 1024 && dsp_engine_get_hot_ack_token(engine) != 42; block++) {
        assert_true(dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
            "matching generation bounded transition block processes");
    }
    assert_true(dsp_engine_get_hot_ack_token(engine) == 42, "matching generation token is acknowledged");

    dsp_engine_hot_update wrapped_token_update = update;
    wrapped_token_update.generation = UINT64_C(0x80000000);
    wrapped_token_update.generation_token = 1;
    assert_true(dsp_engine_enqueue_hot_update(engine, &wrapped_token_update),
        "zero low-31-bit generation uses JNI fallback token");
    assert_true(dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "wrapped generation token update processes");
    for (int block = 0; block < 1024 && dsp_engine_get_hot_ack_token(engine) != 1; block++) {
        assert_true(dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
            "wrapped generation bounded transition block processes");
    }
    assert_true(dsp_engine_get_hot_ack_token(engine) == 1, "wrapped generation uses token one for acknowledgement");
    dsp_engine_destroy(engine);
}

static void test_smart_loudness_uses_its_bounded_shelf_gains(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {1, 3.0, 1.5};
    const dsp_dynamic_bass_config dynamic_bass = {0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0};
    float input[1024];
    float output[1024] = {0.0f};
    for (size_t frame = 0; frame < 512; frame++) {
        input[frame * 2] = 0.05f;
        input[frame * 2 + 1] = 0.05f;
    }
    dsp_engine *engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "Smart Loudness graph creates");
    for (size_t block = 0; block < 100; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "Smart Loudness block processes");
    }
    assert_true(output[1022] > 0.07f && output[1022] <= 0.05f * 1.42f,
        "low-frequency compensation reaches the configured shelf ceiling");
    assert_near(output[1022], output[1023], 1e-6, "Smart Loudness preserves stereo balance");
    dsp_engine_destroy(engine);
}

static void test_dynamic_bass_stereo_link_and_boost_ceiling(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = {1, 1.0, 3.0, 120.0, -55.0, -25.0, -10.0, 40.0, 250.0};
    float input[1024];
    float output[1024] = {0.0f};
    for (size_t frame = 0; frame < 512; frame++) {
        input[frame * 2] = 0.05f;
        input[frame * 2 + 1] = 0.025f;
    }
    dsp_engine *engine = create_studio_test_engine(48000, 2, 512, &macro, &loudness, &dynamic_bass);
    assert_true(engine != NULL, "dynamic bass graph creates");
    for (size_t block = 0; block < 100; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "dynamic bass block processes");
    }
    assert_true(output[1022] > 0.05f && output[1022] <= 0.05f * 1.42f,
        "quiet bass is boosted without exceeding the configured ceiling");
    assert_near(output[1022] / output[1023], 2.0, 1e-4, "dynamic bass uses a stereo-linked detector");
    dsp_engine_destroy(engine);
}

static const dsp_dynamic_bass_config dynamic_bass_test_config = {
    1, 1.0, 3.0, 120.0, -60.0, -40.0, -10.0, 40.0, 250.0,
};

static void process_dynamic_bass_constant(
    dsp_dynamic_bass *bass,
    float left,
    float right,
    int channels,
    size_t frames) {
    for (size_t frame = 0; frame < frames; frame++) {
        float sample_left = left;
        float sample_right = right;
        dsp_dynamic_bass_process_frame(bass, &sample_left, &sample_right, channels);
    }
}

static void test_dynamic_bass_disabled_and_silence_are_neutral(void) {
    dsp_dynamic_bass bass;
    dsp_dynamic_bass_config config = dynamic_bass_test_config;
    config.enabled = 0;
    assert_true(dsp_dynamic_bass_prepare(&bass, 48000, 1, &config), "disabled dynamic bass prepares");
    float left = 0.25f;
    dsp_dynamic_bass_process_frame(&bass, &left, NULL, 1);
    assert_near(left, 0.25, 0.0, "disabled dynamic bass preserves samples exactly");
    assert_near(bass.current_gain, 1.0, 0.0, "disabled dynamic bass starts at unity");

    config.enabled = 1;
    assert_true(dsp_dynamic_bass_prepare(&bass, 48000, 1, &config), "enabled dynamic bass prepares");
    for (size_t frame = 0; frame < 48000; frame++) {
        left = 0.0f;
        dsp_dynamic_bass_process_frame(&bass, &left, NULL, 1);
    }
    assert_near(bass.current_gain, 1.0, 1e-12, "silence never requests bass lift");
    assert_near(bass.envelope, 0.0, 1e-12, "silence detector envelope remains at zero");
}

static void test_dynamic_bass_threshold_curve_and_boost_ceiling(void) {
    dsp_dynamic_bass bass;
    assert_true(dsp_dynamic_bass_prepare(&bass, 48000, 1, &dynamic_bass_test_config),
        "threshold-curve dynamic bass prepares");
    process_dynamic_bass_constant(&bass, 0.1f, 0.0f, 1, 48000);
    const double expected_max_gain = pow(10.0, 3.0 / 20.0);
    assert_true(bass.current_gain > 1.0 && bass.current_gain < expected_max_gain,
        "mid-threshold bass requests an intermediate lift");
    const double threshold_amount = (pow(10.0, -10.0 / 20.0) - 0.1) /
        (pow(10.0, -10.0 / 20.0) - pow(10.0, -40.0 / 20.0));
    assert_near(bass.current_gain, 1.0 + (expected_max_gain - 1.0) * threshold_amount, 0.015,
        "threshold interpolation follows the configured linear-amplitude curve");
    assert_true(bass.current_gain <= expected_max_gain + 1e-12,
        "dynamic bass gain remains within configured maximum boost");
}

static void test_dynamic_bass_attack_release_and_linked_detection(void) {
    dsp_dynamic_bass bass;
    assert_true(dsp_dynamic_bass_prepare(&bass, 44100, 1, &dynamic_bass_test_config),
        "mono dynamic bass prepares at 44.1 kHz");
    process_dynamic_bass_constant(&bass, 0.05f, 0.0f, 1, 44100);
    const double quiet_gain = bass.current_gain;
    assert_true(quiet_gain > 1.0, "quiet low band reaches a positive lift");
    process_dynamic_bass_constant(&bass, 0.5f, 0.0f, 1, 1);
    const double first_transient_gain = bass.current_gain;
    process_dynamic_bass_constant(&bass, 0.5f, 0.0f, 1, 4410);
    const double strong_bass_gain = bass.current_gain;
    assert_true(first_transient_gain < quiet_gain && strong_bass_gain < first_transient_gain,
        "attack progressively reduces lift when strong bass arrives");
    process_dynamic_bass_constant(&bass, 0.05f, 0.0f, 1, 22050);
    assert_true(bass.current_gain > strong_bass_gain && bass.current_gain <= quiet_gain,
        "release restores lift gradually when bass becomes quiet");

    dsp_dynamic_bass mono;
    dsp_dynamic_bass stereo;
    assert_true(dsp_dynamic_bass_prepare(&mono, 48000, 1, &dynamic_bass_test_config),
        "mono reference prepares at 48 kHz");
    assert_true(dsp_dynamic_bass_prepare(&stereo, 48000, 2, &dynamic_bass_test_config),
        "stereo linked detector prepares at 48 kHz");
    process_dynamic_bass_constant(&mono, 0.1f, 0.0f, 1, 48000);
    process_dynamic_bass_constant(&stereo, 0.1f, 0.1f, 2, 48000);
    assert_near(stereo.current_gain, mono.current_gain, 1e-12,
        "stereo-linked detector agrees with mono for dual-mono input");
}

static void test_dynamic_bass_stereo_link_preserves_channel_ratio(void) {
    dsp_dynamic_bass bass;
    assert_true(dsp_dynamic_bass_prepare(&bass, 48000, 2, &dynamic_bass_test_config),
        "stereo dynamic bass prepares");
    float left = 0.1f;
    float right = 0.05f;
    for (size_t frame = 0; frame < 48000; frame++) {
        left = 0.1f;
        right = 0.05f;
        dsp_dynamic_bass_process_frame(&bass, &left, &right, 2);
    }
    assert_true(left > 0.1f && right > 0.05f, "linked detector applies a common lift to both channels");
    assert_near(left / right, 2.0, 1e-4, "linked detector does not alter the channel level ratio");
}

static void test_dynamic_bass_graph_detector_uses_raw_input(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = dynamic_bass_test_config;
    const dsp_dynamics_config compressed = {1, -35.0, 8.0, 10.0, 100.0, 0.0, 0.0, 0, -1.0, 60.0};
    const float impulse[] = {0.0f, 1.0f, 0.0f};
    const dsp_convolver_config convolver = {1, 48000, 1, 3, 3, 1.0f, impulse};
    enum { FRAME_COUNT = 512 };
    float input[FRAME_COUNT];
    float unprocessed_output[FRAME_COUNT] = {0.0f};
    float processed_output[FRAME_COUNT] = {0.0f};
    for (int frame = 0; frame < FRAME_COUNT; frame++) {
        input[frame] = 0.1f * sinf((float)frame * 0.017f);
    }

    dsp_graph unprocessed_graph = {0};
    dsp_graph processed_graph = {0};
    assert_true(dsp_graph_prepare(
            &unprocessed_graph, 48000, 1, 0.0, NULL, 0, &bypass_dynamics, &bypass_multiband,
            &bypass_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
            &bypass_harmonic, &macro, &loudness, &dynamic_bass),
        "raw-tap reference graph prepares");
    assert_true(dsp_graph_prepare(
            &processed_graph, 48000, 1, 0.0, NULL, 0, &compressed, &bypass_multiband,
            &bypass_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &convolver,
            &bypass_harmonic, &macro, &loudness, &dynamic_bass),
        "compressor and IR graph prepares");

    uint64_t non_finite = 0;
    dsp_graph_process(&unprocessed_graph, input, unprocessed_output, FRAME_COUNT, 1, &non_finite, NULL);
    dsp_graph_process(&processed_graph, input, processed_output, FRAME_COUNT, 1, &non_finite, NULL);
    assert_near(processed_graph.dynamic_bass.envelope, unprocessed_graph.dynamic_bass.envelope, 1e-12,
        "compressor and IR changes do not alter raw-input detector envelope");
    assert_near(processed_graph.dynamic_bass.current_gain, unprocessed_graph.dynamic_bass.current_gain, 1e-12,
        "compressor and IR changes do not alter raw-input Dynamic Bass gain");
    int output_changed = 0;
    for (int frame = 0; frame < FRAME_COUNT; frame++) {
        if (fabsf(processed_output[frame] - unprocessed_output[frame]) > 1e-4f) {
            output_changed = 1;
            break;
        }
    }
    assert_true(output_changed, "downstream compressor and IR still change the audio output");

    dsp_graph_close(&processed_graph);
    dsp_graph_close(&unprocessed_graph);
}

static void test_dynamic_bass_precedes_compressor_processing(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamics_config compressor = {1, -40.0, 10.0, 1.0, 100.0, 0.0, 0.0, 0, -1.0, 60.0};
    dsp_dynamic_bass_config enabled_bass = dynamic_bass_test_config;
    dsp_dynamic_bass_config disabled_bass = dynamic_bass_test_config;
    disabled_bass.enabled = 0;
    enum { FRAME_COUNT = 4096 };
    float input[FRAME_COUNT];
    float enabled_output[FRAME_COUNT] = {0.0f};
    float disabled_output[FRAME_COUNT] = {0.0f};
    for (int frame = 0; frame < FRAME_COUNT; frame++) {
        input[frame] = 0.05f * sinf(2.0f * 3.14159265358979323846f * 80.0f * (float)frame / 48000.0f);
    }
    dsp_graph enabled_graph = {0};
    dsp_graph disabled_graph = {0};
    assert_true(dsp_graph_prepare(
            &enabled_graph, 48000, 1, 0.0, NULL, 0, &compressor, &bypass_multiband,
            &bypass_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
            &bypass_harmonic, &macro, &loudness, &enabled_bass),
        "Dynamic Bass and compressor graph prepares");
    assert_true(dsp_graph_prepare(
            &disabled_graph, 48000, 1, 0.0, NULL, 0, &compressor, &bypass_multiband,
            &bypass_dynamic_eq, NULL, 0, NULL, 0, &bypass_spatial, &bypass_convolver,
            &bypass_harmonic, &macro, &loudness, &disabled_bass),
        "compressor-only comparison graph prepares");

    uint64_t non_finite = 0;
    dsp_graph_process(&enabled_graph, input, enabled_output, FRAME_COUNT, 1, &non_finite, NULL);
    dsp_graph_process(&disabled_graph, input, disabled_output, FRAME_COUNT, 1, &non_finite, NULL);

    assert_true(enabled_graph.dynamic_bass.current_gain > 1.0, "Dynamic Bass increases the low-frequency stage signal");
    assert_true(enabled_graph.dynamics.compressor_envelope > disabled_graph.dynamics.compressor_envelope,
        "compressor detector follows the pre-compressor Dynamic Bass output");
    dsp_graph_close(&enabled_graph);
    dsp_graph_close(&disabled_graph);
}

static void test_dynamic_bass_graph_is_block_boundary_invariant(void) {
    const dsp_macro_config macro = {0, 0.0, 0.0, 0.0, 1.0};
    const dsp_smart_loudness_config loudness = {0, 0.0, 0.0};
    const dsp_dynamic_bass_config dynamic_bass = dynamic_bass_test_config;
    enum { FRAME_COUNT = 4096 };
    float input[FRAME_COUNT * 2];
    float reference[FRAME_COUNT * 2] = {0.0f};
    float partitioned[FRAME_COUNT * 2] = {0.0f};
    for (int frame = 0; frame < FRAME_COUNT; frame++) {
        const float sample = 0.08f * sinf((float)frame * 0.017f);
        input[frame * 2] = sample;
        input[frame * 2 + 1] = sample * 0.7f;
    }
    dsp_engine *reference_engine = create_studio_test_engine(48000, 2, FRAME_COUNT, &macro, &loudness, &dynamic_bass);
    dsp_engine *partitioned_engine = create_studio_test_engine(48000, 2, FRAME_COUNT, &macro, &loudness, &dynamic_bass);
    assert_true(reference_engine != NULL && partitioned_engine != NULL, "block-invariance graphs create");
    assert_true(dsp_engine_process(reference_engine, input, FRAME_COUNT * 2, reference, FRAME_COUNT * 2,
        FRAME_COUNT, 2) == DSP_STATUS_OK, "reference dynamic bass graph processes as one block");
    const int blocks[] = {1, 17, 128, 3, 511, 64};
    int cursor = 0;
    size_t block_index = 0;
    while (cursor < FRAME_COUNT) {
        const int frames = blocks[block_index++ % (sizeof(blocks) / sizeof(blocks[0]))] < FRAME_COUNT - cursor
            ? blocks[(block_index - 1) % (sizeof(blocks) / sizeof(blocks[0]))]
            : FRAME_COUNT - cursor;
        assert_true(dsp_engine_process(partitioned_engine, input + cursor * 2, (size_t)frames * 2,
            partitioned + cursor * 2, (size_t)frames * 2, frames, 2) == DSP_STATUS_OK,
            "partitioned dynamic bass graph block processes");
        cursor += frames;
    }
    for (int sample = 0; sample < FRAME_COUNT * 2; sample++) {
        assert_near(partitioned[sample], reference[sample], 1e-7,
            "dynamic bass output is independent of caller block boundaries");
    }
    dsp_engine_destroy(reference_engine);
    dsp_engine_destroy(partitioned_engine);
}

int main(void) {
    test_gain_and_meter();
    test_bad_handles_arguments_and_capacities();
    test_chunk_invariance_and_buffer_canaries();
    test_nonfinite_samples_and_reset();
    test_processed_frame_counter_saturates_without_wrap();
    test_peq_frequency_response_and_reset();
    test_peq_stereo_state_and_denormal_flush();
    test_peq_fifteenth_band_is_active();
    test_compressor_transfer_curve();
    test_compressor_attack_and_release_steps();
    test_dynamics_hot_params_preserve_state_and_validate_atomically();
    test_combined_dynamics_dynamic_eq_hot_preflights_before_any_mutation();
    test_stereo_limiter_caps_sample_peaks_and_preserves_image();
    test_safety_peak_levels_remain_finite_with_enabled_stages();
    test_spatial_width_and_mono_input_behavior();
    test_spatial_seat_alignment_and_latency();
    test_spatial_bass_shelf_and_mono_bass_frequency_response();
    test_advanced_ms_crossover_mono_side_dynamics_and_limiter();
    test_advanced_ms_legacy_migration_is_a_starting_point();
    test_harmonic_engine_latency_oversampling_modes_and_alias_rejection();
    test_multiband_unity_reconstruction_and_phase();
    test_multiband_compression_is_band_selective();
    test_multiband_chunk_invariance_reset_and_validation();
    test_multiband_randomized_configurations_remain_finite();
    test_dynamic_eq_threshold_modes_and_band_selectivity();
    test_dynamic_eq_intermediate_db_change_matches_tone_rms();
    test_dynamic_eq_attack_release_reset_and_chunk_invariance();
    test_dynamic_eq_randomized_configs_stay_bounded_and_finite();
    test_kiss_fft_round_trip();
    test_convolver_impulse_direct_convolution_and_latency();
    test_convolver_chunk_invariance_wet_mix_and_rate_mismatch_bypass();
    test_convolver_ir_sizes_and_validation();
    test_high_gain_convolver_stays_below_full_scale_with_headroom();
    test_convolver_sparse_sentinel_taps_reach_their_output_frames();
    test_studio_native_macro_and_loudness_frequency_response();
    test_studio_hot_refund_waits_for_stage_gain_release();
    test_studio_gain_refund_is_sample_ramped_at_supported_formats();
    test_studio_gain_refund_ramps_when_modules_are_already_settled();
    test_studio_gain_refund_ack_waits_for_ramp_and_newer_generation();
    test_studio_refund_waits_for_dynamics_and_dynamic_eq();
    test_studio_neutral_macro_preserves_exact_samples();
    test_studio_hot_command_is_acknowledged_after_its_block();
    test_preamp_hot_update_is_fenced_ramped_and_acknowledged_at_completion();
    test_preamp_hot_ramp_preserves_active_compressor_and_convolver_state();
    test_preamp_hot_mailbox_coalesces_and_preserves_concurrent_studio_gain_ramps();
    test_native_hot_and_local_warm_share_one_generation_order();
    test_studio_hot_update_rejects_invalid_module_configs_atomically();
    test_studio_hot_update_validates_ack_generation_token();
    test_smart_loudness_uses_its_bounded_shelf_gains();
    test_dynamic_bass_stereo_link_and_boost_ceiling();
    test_dynamic_bass_disabled_and_silence_are_neutral();
    test_dynamic_bass_threshold_curve_and_boost_ceiling();
    test_dynamic_bass_attack_release_and_linked_detection();
    test_dynamic_bass_stereo_link_preserves_channel_ratio();
    test_dynamic_bass_graph_detector_uses_raw_input();
    test_dynamic_bass_precedes_compressor_processing();
    test_dynamic_bass_graph_is_block_boundary_invariant();
    test_local_peq_warm_preserves_downstream_state_and_latency();
    test_local_peq_candidate_catches_up_or_rejects_stale_history();
    test_rejected_local_peq_generation_retries_with_fresh_history();
    test_local_peq_cancel_fades_back_smoothly_at_early_middle_and_late_points();
    test_local_peq_rollout_cancel_stops_queued_and_active_transitions();
    test_local_peq_candidate_history_and_owner_fences();
    puts("native DSP tests passed");
    return 0;
}
