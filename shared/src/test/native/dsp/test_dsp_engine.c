#include "dsp_engine.h"

#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

static void fail(const char *message) {
    fprintf(stderr, "FAIL: %s\n", message);
    exit(1);
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

static void test_gain_and_meter(void) {
    const double gain_db = 6.020599913279624;
    dsp_engine *engine = dsp_engine_create(48000, 2, 512, gain_db);
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

    dsp_engine *engine = dsp_engine_create(48000, 2, 2, 0.0);
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

    assert_true(dsp_engine_create(7999, 2, 512, 0.0) == NULL, "low sample rate is rejected");
    assert_true(dsp_engine_create(48000, 3, 512, 0.0) == NULL, "multichannel output is rejected");
    assert_true(dsp_engine_create(48000, 2, 512, INFINITY) == NULL, "non-finite gain is rejected");
    assert_true(dsp_engine_create(48000, 2, 512, 25.0) == NULL, "out-of-range gain is rejected");
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

    dsp_engine *whole = dsp_engine_create(44100, 2, 8, -3.0);
    dsp_engine *split = dsp_engine_create(44100, 2, 8, -3.0);
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
    dsp_engine *engine = dsp_engine_create(48000, 1, 4, 24.0);
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

int main(void) {
    test_gain_and_meter();
    test_bad_handles_arguments_and_capacities();
    test_chunk_invariance_and_buffer_canaries();
    test_nonfinite_samples_and_reset();
    puts("native DSP tests passed");
    return 0;
}
