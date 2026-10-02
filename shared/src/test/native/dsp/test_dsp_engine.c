#include "dsp_engine.h"

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
static const dsp_spatial_config bypass_spatial = {1.0, 0, 120};
static const dsp_convolver_config bypass_convolver = {0, 0, 0, 0, 0, 0.0f, NULL};

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
        &bypass_convolver);
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
        &bypass_convolver);
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
        &bypass_convolver);
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
        &bypass_convolver);
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
        convolver_config);
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
        &bypass_convolver);
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
            &bypass_spatial, &bypass_convolver) == NULL,
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
    const float sustained_overload[] = {2.0f, -2.0f, 1.0f, -1.0f};
    float overload_output[4] = {0.0f};
    assert_true(dsp_engine_process(engine, sustained_overload, 4, overload_output, 4, 2, 2) == DSP_STATUS_OK,
        "limiter processes consecutive over-threshold peaks");
    assert_near(fabs(overload_output[0]), threshold, 1e-6, "first overload frame reaches the hard ceiling");
    assert_true(fabs(overload_output[2]) < threshold,
        "limiter does not recover gain while the signal remains over threshold");
    assert_near(overload_output[2], threshold / 2.0, 1e-6,
        "limiter retains the gain reduction across a still-hot frame");

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

static void test_spatial_width_and_mono_input_behavior(void) {
    const dsp_spatial_config mono_width = {0.0, 0, 120};
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

    const dsp_spatial_config default_width = {1.0, 0, 120};
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

    const dsp_spatial_config double_width = {2.0, 0, 120};
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
    const dsp_spatial_config wide_mono = {2.0, 1, 120};
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

static void test_spatial_bass_shelf_and_mono_bass_frequency_response(void) {
    double bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_low_shelf(48000.0, 80.0, 6.0, bass_coefficients);
    const dsp_spatial_config width_one = {1.0, 0, 120};
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
    const dsp_spatial_config mono_bass = {1.0, 1, 120};
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
        &mismatched_rate);
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
    test_spatial_bass_shelf_and_mono_bass_frequency_response();
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
    puts("native DSP tests passed");
    return 0;
}
