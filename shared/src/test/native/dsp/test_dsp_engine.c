#include "dsp_engine.h"
#include "dsp_dynamic_bass.h"
#include "dsp_graph.h"

#include <float.h>
#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

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

static dsp_engine *create_test_engine_with_convolver(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_convolver_config *convolver_config) {
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
        1, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 1000.0,
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
        .macro = {0, 0.0, 0.0, 0.0, 1.0},
        .smart_loudness = {0, 0.0, 0.0},
        .dynamic_bass = {0, 1.0, 3.0, 120.0, -90.0, -50.0, -40.0, 40.0, 1000.0},
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
        for (size_t sample = 0; sample < 1024; sample++) {
            const double absolute_output = fabs((double)output[sample]);
            if (absolute_output > maximum_absolute_output) maximum_absolute_output = absolute_output;
        }
        absolute_frame += 512;
    }
    assert_true(maximum_absolute_output <= 1.001,
        "preamp refund waits for Macro, Loudness, and Dynamic Bass to leave their old boost envelope");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 42,
        "headroom refund HOT update is acknowledged after its first processed block");
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
    assert_true(!dsp_engine_enqueue_hot_update(engine, &next_update), "single-slot hot queue rejects a second pending command");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 0, "hot command waits for a processed block");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "hot command block processes");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 43, "hot acknowledgement follows the processed block");
    assert_true(dsp_engine_enqueue_hot_update(engine, &next_update), "hot queue accepts a new command after the block");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "second hot command block processes");
    assert_true(dsp_engine_get_hot_ack_token(engine) == 44, "newer generation is acknowledged after its block");
    assert_true(fabsf(output[1022]) > 0.5f && fabsf(output[1022]) < 0.63f,
        "macro width moves toward its target without an abrupt step");
    assert_true(dsp_engine_bind_hot_update_context(engine, 48000, 2, 0, 0, 0, 0, 0),
        "renderer close invalidates native owner context");
    assert_true(!dsp_engine_enqueue_hot_update(engine, &next_update),
        "closed renderer cannot enqueue a stale hot command");
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
    assert_true(!dsp_graph_apply_studio_hot(&graph, 6.0, &invalid_macro, &loudness, &dynamic_bass),
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
    assert_true(dsp_engine_get_hot_ack_token(engine) == 42, "matching generation token is acknowledged");

    dsp_engine_hot_update wrapped_token_update = update;
    wrapped_token_update.generation = UINT64_C(0x80000000);
    wrapped_token_update.generation_token = 1;
    assert_true(dsp_engine_enqueue_hot_update(engine, &wrapped_token_update),
        "zero low-31-bit generation uses JNI fallback token");
    assert_true(dsp_engine_process(engine, input, 4, output, 4, 2, 2) == DSP_STATUS_OK,
        "wrapped generation token update processes");
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
    test_stereo_limiter_caps_sample_peaks_and_preserves_image();
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
    test_convolver_sparse_sentinel_taps_reach_their_output_frames();
    test_studio_native_macro_and_loudness_frequency_response();
    test_studio_hot_refund_waits_for_stage_gain_release();
    test_studio_neutral_macro_preserves_exact_samples();
    test_studio_hot_command_is_acknowledged_after_its_block();
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
    puts("native DSP tests passed");
    return 0;
}
