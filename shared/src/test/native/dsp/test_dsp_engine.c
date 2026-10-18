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

static dsp_engine *create_test_engine_with_advanced_ms(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_spatial_config *spatial_config,
    const dsp_advanced_ms_config *advanced_ms_config,
    const double *advanced_ms_coefficients,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count) {
    const dsp_seat_focus_config disabled_seat_focus = {0};
    const dsp_cabin_config disabled_cabin = {0, 0, 0};
    const dsp_harmonic_config disabled_harmonic = {
        .enabled = 0,
        .bypassed = 0,
        .quality = 2,
        .high_exciter_enabled = 0,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 1.0,
        .high_exciter_amount = 0.5,
        .high_exciter_mode = DSP_HARMONIC_MODE_MIXED,
        .high_exciter_wet = 0.0,
        .high_exciter_compensation_db = 0.0,
        .virtual_bass_enabled = 0,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 0.5,
        .virtual_bass_drive = 1.0,
        .virtual_bass_second_amount = 0.5,
        .virtual_bass_third_amount = 0.25,
        .virtual_bass_wet = 0.0,
    };
    const double disabled_harmonic_coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    return dsp_engine_create_with_modules(
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
        mono_bass_coefficients,
        mono_bass_coefficient_count,
        spatial_config,
        &bypass_convolver,
        &disabled_seat_focus,
        advanced_ms_config,
        advanced_ms_coefficients,
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT,
        &disabled_cabin,
        NULL,
        0,
        &bypass_dynamic_eq,
        &disabled_harmonic,
        disabled_harmonic_coefficients,
        DSP_HARMONIC_COEFFICIENT_COUNT);
}

static dsp_engine *create_test_engine_with_cabin(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_cabin_config *cabin_config,
    const double *static_coefficients,
    int static_coefficient_count,
    const dsp_dynamic_eq_config *cabin_dynamic_eq_config) {
    const dsp_seat_focus_config disabled_seat_focus = {0};
    const dsp_advanced_ms_config disabled_advanced_ms = {
        0, 0.0, 0.0, 1.0, 1.0, 1.0, 150.0, 4000.0, 0.0,
        DSP_ADVANCED_MS_MONITOR_NORMAL, 0, 0,
    };
    const double disabled_advanced_ms_coefficients[
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT] = {0.0};
    const dsp_spatial_config spatial = {1.0, 0, 120};
    const dsp_harmonic_config disabled_harmonic = {
        .enabled = 0,
        .bypassed = 0,
        .quality = 2,
        .high_exciter_enabled = 0,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 1.0,
        .high_exciter_amount = 0.5,
        .high_exciter_mode = DSP_HARMONIC_MODE_MIXED,
        .high_exciter_wet = 0.0,
        .high_exciter_compensation_db = 0.0,
        .virtual_bass_enabled = 0,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 0.5,
        .virtual_bass_drive = 1.0,
        .virtual_bass_second_amount = 0.5,
        .virtual_bass_third_amount = 0.25,
        .virtual_bass_wet = 0.0,
    };
    const double disabled_harmonic_coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    return dsp_engine_create_with_modules(
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
        &spatial,
        &bypass_convolver,
        &disabled_seat_focus,
        &disabled_advanced_ms,
        disabled_advanced_ms_coefficients,
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT,
        cabin_config,
        static_coefficients,
        static_coefficient_count,
        cabin_dynamic_eq_config,
        &disabled_harmonic,
        disabled_harmonic_coefficients,
        DSP_HARMONIC_COEFFICIENT_COUNT);
}

static dsp_engine *create_test_engine_with_harmonic(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_harmonic_config *harmonic_config,
    const double *filter_coefficients) {
    const dsp_seat_focus_config disabled_seat_focus = {0};
    const dsp_advanced_ms_config disabled_advanced_ms = {
        0, 0.0, 0.0, 1.0, 1.0, 1.0, 150.0, 4000.0, 0.0,
        DSP_ADVANCED_MS_MONITOR_NORMAL, 0, 0,
    };
    const double disabled_advanced_ms_coefficients[
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT] = {0.0};
    const dsp_cabin_config disabled_cabin = {0, 0, 0};
    return dsp_engine_create_with_modules(
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
        &disabled_seat_focus,
        &disabled_advanced_ms,
        disabled_advanced_ms_coefficients,
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT,
        &disabled_cabin,
        NULL,
        0,
        &bypass_dynamic_eq,
        harmonic_config,
        filter_coefficients,
        DSP_HARMONIC_COEFFICIENT_COUNT);
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

static dsp_engine *create_test_engine_with_seat_focus_and_gain(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const dsp_seat_focus_config *seat_focus_config);

static dsp_engine *create_test_engine_with_seat_focus(
    int sample_rate,
    int channels,
    int max_frames,
    const dsp_seat_focus_config *seat_focus_config) {
    return create_test_engine_with_seat_focus_and_gain(
        sample_rate,
        channels,
        max_frames,
        0.0,
        seat_focus_config);
}

static dsp_engine *create_test_engine_with_seat_focus_and_gain(
    int sample_rate,
    int channels,
    int max_frames,
    double gain_db,
    const dsp_seat_focus_config *seat_focus_config) {
    return dsp_engine_create_with_seat_focus(
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
        seat_focus_config);
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

static void process_seat_focus_signal(
    dsp_engine *engine,
    const float *input,
    float *output,
    size_t frames,
    size_t channels,
    const size_t *block_sizes,
    size_t block_size_count) {
    size_t offset = 0;
    size_t block_index = 0;
    while (offset < frames) {
        size_t block_frames = block_sizes[block_index % block_size_count];
        if (block_frames > frames - offset) block_frames = frames - offset;
        assert_true(dsp_engine_process(
                engine,
                input + offset * channels,
                block_frames * channels,
                output + offset * channels,
                block_frames * channels,
                (int)block_frames,
                (int)channels) == DSP_STATUS_OK,
            "seat focus block processes");
        offset += block_frames;
        block_index++;
    }
}

static void test_seat_focus_fixed_latency_mono_bypass_and_isolation(void) {
    const dsp_seat_focus_config config = {1, 0, 0.0, 0.0, 0.0, 0.0, 0, 0};
    dsp_engine *stereo = create_test_engine_with_seat_focus(48000, 2, 512, &config);
    assert_true(stereo != NULL, "seat focus graph creates");
    assert_true(dsp_engine_get_latency_frames(stereo) == 264, "seat focus reserves a fixed 5.5ms delay budget");

    float input[512 * 2] = {0.0f};
    float output[512 * 2] = {0.0f};
    input[0] = 1.0f;
    input[1] = 0.5f;
    assert_true(dsp_engine_process(stereo, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "seat focus stereo impulse processes");
    assert_near(output[264 * 2], 1.0, 1e-6, "left impulse aligns to reserved latency");
    assert_near(output[264 * 2 + 1], 0.5, 1e-6, "right impulse aligns to reserved latency");
    for (size_t frame = 0; frame < 512; frame++) {
        if (frame == 264) continue;
        assert_near(output[frame * 2], 0.0, 1e-6, "seat focus left impulse has one output tap");
        assert_near(output[frame * 2 + 1], 0.0, 1e-6, "seat focus right impulse has one output tap");
    }
    assert_true(dsp_engine_reset(stereo) == DSP_STATUS_OK, "seat focus reset is accepted");
    memset(input, 0, sizeof(input));
    memset(output, 0, sizeof(output));
    input[0] = 0.25f;
    assert_true(dsp_engine_process(stereo, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "single-channel seat focus impulse processes");
    for (size_t frame = 0; frame < 512; frame++) {
        assert_near(output[frame * 2 + 1], 0.0, 1e-7, "seat focus does not leak an impulse across channels");
    }
    dsp_engine_destroy(stereo);

    dsp_engine *mono = create_test_engine_with_seat_focus(48000, 1, 512, &config);
    assert_true(mono != NULL, "mono graph bypasses stereo-only seat focus");
    assert_true(dsp_engine_get_latency_frames(mono) == 0, "mono bypass adds no seat latency");
    float mono_input[4] = {0.25f, -0.5f, 0.75f, -1.0f};
    float mono_output[4] = {0.0f};
    assert_true(dsp_engine_process(mono, mono_input, 4, mono_output, 4, 4, 1) == DSP_STATUS_OK,
        "mono seat focus bypass processes");
    for (size_t index = 0; index < 4; index++) {
        assert_near(mono_output[index], mono_input[index], 1e-7, "mono seat focus is identity");
    }
    dsp_engine_destroy(mono);
}

static void test_seat_focus_fractional_delay_accuracy_and_chunk_invariance(void) {
    const int sample_rates[] = {44100, 48000, 96000};
    const size_t frame_count = 8192;
    const size_t channels = 2;
    const size_t block_sizes[] = {1, 128, 512, 1537, 2048};
    const size_t reference_block_sizes[] = {512};
    float *input = (float *)calloc(frame_count * channels, sizeof(float));
    float *output = (float *)calloc(frame_count * channels, sizeof(float));
    float *reference = (float *)calloc(frame_count * channels, sizeof(float));
    assert_true(input != NULL && output != NULL && reference != NULL, "seat focus correlation buffers allocate");
    uint32_t random_state = UINT32_C(0x13579bdf);
    for (size_t frame = 0; frame < frame_count; frame++) {
        random_state = random_state * UINT32_C(1664525) + UINT32_C(1013904223);
        const float sample = (float)(random_state >> 8) / 8388607.5f - 1.0f;
        input[frame * 2] = sample;
        input[frame * 2 + 1] = sample;
    }

    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        const dsp_seat_focus_config config = {1, 0, 0.0, 0.65, 0.0, 0.0, 0, 0};
        const int expected_latency = (int)ceil((double)sample_rate * 0.0055);
        dsp_engine *reference_engine = create_test_engine_with_seat_focus(sample_rate, 2, 2048, &config);
        assert_true(reference_engine != NULL, "fractional seat focus reference graph creates");
        assert_true(dsp_engine_get_latency_frames(reference_engine) == expected_latency,
            "fractional seat focus reference latency is rate-specific and fixed");
        memset(reference, 0, frame_count * channels * sizeof(float));
        process_seat_focus_signal(reference_engine, input, reference, frame_count, channels, reference_block_sizes, 1);
        dsp_engine_destroy(reference_engine);

        dsp_engine *engine = create_test_engine_with_seat_focus(sample_rate, 2, 2048, &config);
        assert_true(engine != NULL, "fractional seat focus graph creates");
        assert_true(dsp_engine_get_latency_frames(engine) == expected_latency,
            "fractional seat focus latency is rate-specific and fixed");
        memset(output, 0, frame_count * channels * sizeof(float));
        process_seat_focus_signal(engine, input, output, frame_count, channels, block_sizes,
            sizeof(block_sizes) / sizeof(block_sizes[0]));
        double maximum_chunk_error = 0.0;
        for (size_t sample = 0; sample < frame_count * channels; sample++) {
            const double error = fabs((double)output[sample] - reference[sample]);
            if (error > maximum_chunk_error) maximum_chunk_error = error;
        }
        assert_true(maximum_chunk_error <= 1e-7, "seat focus output is invariant to process block boundaries");

        const int expected_lag = (int)lround(0.00065 * (double)sample_rate);
        double best_correlation = -DBL_MAX;
        int best_lag = 0;
        double correlations[9];
        for (int lag = expected_lag - 4; lag <= expected_lag + 4; lag++) {
            double correlation = 0.0;
            const size_t start = (size_t)expected_latency + 128U;
            const size_t end = frame_count - (size_t)lag - 128U;
            for (size_t frame = start; frame < end; frame++) {
                correlation += (double)output[frame * 2] * output[(frame + (size_t)lag) * 2 + 1U];
            }
            if (correlation > best_correlation) {
                best_correlation = correlation;
                best_lag = lag;
            }
            correlations[lag - (expected_lag - 4)] = correlation;
        }
        const size_t peak_index = (size_t)(best_lag - (expected_lag - 4));
        double fractional_offset = 0.0;
        if (peak_index > 0U && peak_index + 1U < 9U) {
            const double left_correlation = correlations[peak_index - 1U];
            const double center_correlation = correlations[peak_index];
            const double right_correlation = correlations[peak_index + 1U];
            const double denominator = left_correlation - 2.0 * center_correlation + right_correlation;
            if (fabs(denominator) > DBL_EPSILON) {
                fractional_offset = 0.5 * (left_correlation - right_correlation) / denominator;
            }
        }
        const double measured_lag = (double)best_lag + fractional_offset;
        const double expected_fractional_lag = 0.00065 * (double)sample_rate;
        const double error_ms = (measured_lag - expected_fractional_lag) * 1000.0 / (double)sample_rate;
        printf(
            "DSP_SEAT_DELAY sampleRate=%d expectedSamples=%.4f measuredSamples=%.4f errorMs=%.6f\n",
            sample_rate,
            expected_fractional_lag,
            measured_lag,
            error_ms);
        assert_true(fabs(error_ms) <= 0.05,
            "fractional L/R delay correlation is within 0.05ms");
        dsp_engine_destroy(engine);
    }
    free(input);
    free(output);
    free(reference);
}

static void test_seat_focus_trim_polarity_and_live_targets_are_smooth(void) {
    const dsp_seat_focus_config initial = {1, 0, 0.0, 0.0, 0.0, 0.0, 0, 0};
    dsp_engine *engine = create_test_engine_with_seat_focus(48000, 2, 512, &initial);
    assert_true(engine != NULL, "live seat focus graph creates");
    const int initial_latency = dsp_engine_get_latency_frames(engine);
    float input[512 * 2];
    float output[512 * 2];
    for (size_t frame = 0; frame < 512U; frame++) {
        input[frame * 2] = 0.25f;
        input[frame * 2 + 1U] = 0.125f;
    }
    for (int block = 0; block < 2; block++) {
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "seat focus state warms before live update");
    }

    const dsp_seat_focus_config updated = {1, 0, 0.0, 0.65, 6.0, -6.0, 1, 0};
    assert_true(dsp_engine_update_seat_focus(engine, &updated) == DSP_STATUS_OK,
        "live seat focus target is accepted");
    assert_true(dsp_engine_get_latency_frames(engine) == initial_latency,
        "seat delay edits do not change prepared graph latency");
    assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
        "live seat focus update processes");
    float maximum_step = 0.0f;
    float previous_left = 0.25f;
    for (size_t frame = 0; frame < 512U; frame++) {
        const float current_left = output[frame * 2];
        const float step = fabsf(current_left - previous_left);
        if (step > maximum_step) maximum_step = step;
        previous_left = current_left;
    }
    assert_true(maximum_step < 0.01f, "live trim and polarity changes are ramped without a click");

    const dsp_seat_focus_config invalid = {1, 0, 0.0, 5.1, 0.0, 0.0, 0, 0};
    assert_true(dsp_engine_update_seat_focus(engine, &invalid) == DSP_STATUS_INVALID_ARGUMENT,
        "native live update rejects out-of-range delay");
    dsp_engine_destroy(engine);
}

static void test_seat_focus_live_trim_keeps_prepared_headroom_safe(void) {
    const dsp_seat_focus_config initial = {1, 0, 0.0, 0.0, 0.0, 0.0, 0, 0};
    dsp_engine *engine = create_test_engine_with_seat_focus_and_gain(48000, 2, 1024, -1.0, &initial);
    assert_true(engine != NULL, "headroom seat focus graph creates");
    float input[1024 * 2];
    float output[1024 * 2];
    for (size_t frame = 0; frame < 1024U; frame++) {
        input[frame * 2U] = 0.75f;
        input[frame * 2U + 1U] = 0.75f;
    }
    assert_true(dsp_engine_process(engine, input, 2048, output, 2048, 1024, 2) == DSP_STATUS_OK,
        "headroom reference block processes");
    const float prepared_level = output[1023U * 2U];
    assert_near(prepared_level, 0.75 * pow(10.0, -1.0 / 20.0), 1e-4,
        "prepared preamp margin is the level reference");

    const dsp_seat_focus_config boosted = {1, 0, 0.0, 0.0, 6.0, 6.0, 0, 0};
    assert_true(dsp_engine_update_seat_focus(engine, &boosted) == DSP_STATUS_OK,
        "maximum live trim is accepted");
    assert_true(dsp_engine_process(engine, input, 2048, output, 2048, 1024, 2) == DSP_STATUS_OK,
        "maximum live trim block processes");
    for (size_t frame = 900U; frame < 1024U; frame++) {
        assert_near(output[frame * 2U], prepared_level, 1e-4,
            "live positive trim remains inside the prepared headroom budget");
        assert_near(output[frame * 2U + 1U], prepared_level, 1e-4,
            "live positive trim preserves both channels");
    }
    dsp_engine_destroy(engine);
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
    assert_near(diagnostics.input_correlation, -0.875 / sqrt(0.625 * 1.25), 1e-6,
        "input correlation describes the most recent block");
    assert_near(diagnostics.output_correlation, diagnostics.input_correlation, 1e-6,
        "linear gain preserves correlation");
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

static void design_low_pass(double sample_rate, double frequency, double q, double coefficients[5]) {
    const double pi = 3.14159265358979323846;
    const double omega = 2.0 * pi * frequency / sample_rate;
    const double cosine = cos(omega);
    const double alpha = sin(omega) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    coefficients[0] = (1.0 - cosine) / (2.0 * a0);
    coefficients[1] = (1.0 - cosine) / a0;
    coefficients[2] = (1.0 - cosine) / (2.0 * a0);
    coefficients[3] = -2.0 * cosine / a0;
    coefficients[4] = (1.0 - alpha) / a0;
}

typedef struct {
    double fundamental;
    double second;
    double third;
} dsp_test_harmonic_spectrum;

static dsp_test_harmonic_spectrum measure_harmonic_spectrum(
    dsp_engine *engine,
    int channels,
    double input_frequency,
    double amplitude) {
    const size_t total_frames = 48000U;
    const size_t warmup_frames = 4800U;
    const double output_frequencies[3] = {input_frequency, input_frequency * 2.0, input_frequency * 3.0};
    double sine_sums[3] = {0.0, 0.0, 0.0};
    double cosine_sums[3] = {0.0, 0.0, 0.0};
    const size_t measured_frames = total_frames - warmup_frames;
    size_t position = 0;
    while (position < total_frames) {
        const size_t frames = total_frames - position < 512U ? total_frames - position : 512U;
        float input[512];
        float mono_output[512];
        float stereo_input[1024];
        float stereo_output[1024];
        for (size_t frame = 0; frame < frames; frame++) {
            const size_t sample_index = position + frame;
            const float sample = (float)(amplitude * sin(2.0 * 3.14159265358979323846 * input_frequency *
                (double)sample_index / 48000.0));
            if (channels == 1) {
                input[frame] = sample;
            } else {
                stereo_input[frame * 2U] = sample;
                stereo_input[frame * 2U + 1U] = sample;
            }
        }
        const float *input_buffer = channels == 1 ? input : stereo_input;
        float *output_buffer = channels == 1 ? mono_output : stereo_output;
        assert_true(dsp_engine_process(engine, input_buffer, frames * (size_t)channels,
            output_buffer, frames * (size_t)channels, (int)frames, channels) == DSP_STATUS_OK,
            "harmonic test tone block processes");
        for (size_t frame = 0; frame < frames; frame++) {
            const size_t sample_index = position + frame;
            if (sample_index < warmup_frames) continue;
            for (size_t bin = 0; bin < 3U; bin++) {
                const double phase = 2.0 * 3.14159265358979323846 * output_frequencies[bin] *
                    (double)sample_index / 48000.0;
                const float output_sample = output_buffer[frame * (size_t)channels];
                sine_sums[bin] += (double)output_sample * sin(phase);
                cosine_sums[bin] += (double)output_sample * cos(phase);
            }
        }
        position += frames;
    }
    const double scale = 2.0 / (double)measured_frames;
    return (dsp_test_harmonic_spectrum) {
        scale * hypot(sine_sums[0], cosine_sums[0]),
        scale * hypot(sine_sums[1], cosine_sums[1]),
        scale * hypot(sine_sums[2], cosine_sums[2]),
    };
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

static void test_cabin_static_and_dynamic_cuts_are_measurement_band_limited(void) {
    double static_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_peak(48000.0, 82.0, 4.0, -6.0, static_coefficients);
    const dsp_cabin_config static_config = {1, 1, 0};
    dsp_engine *engine = create_test_engine_with_cabin(
        48000, 2, 512, &static_config, static_coefficients, DSP_BIQUAD_COEFFICIENT_COUNT,
        &bypass_dynamic_eq);
    assert_true(engine != NULL, "measurement-derived static cabin cut graph creates");
    assert_true(measure_sine_gain(engine, 82.0, 2) < 0.6,
        "static cabin cut attenuates only its measured resonance band");
    assert_true(measure_sine_gain(engine, 1000.0, 2) > 0.95,
        "static cabin cut leaves distant frequencies unchanged");
    dsp_engine_destroy(engine);

    dsp_dynamic_eq_config dynamic_config = bypass_dynamic_eq;
    dynamic_config.enabled = 1;
    dynamic_config.bands[0] = (dsp_dynamic_eq_band_config) {
        1, DSP_DYNAMIC_EQ_CUT, 82.0, 4.0, -40.0, 2.0, 1.0, 100.0, 0.0, 3.0,
    };
    const dsp_cabin_config dynamic_only_config = {1, 0, 0};
    engine = create_test_engine_with_cabin(
        48000, 2, 512, &dynamic_only_config, NULL, 0, &dynamic_config);
    assert_true(engine != NULL, "measurement-banded dynamic cabin control graph creates");
    const double dynamic_gain = measure_sine_gain(engine, 82.0, 2);
    assert_true(dynamic_gain < 0.9 && dynamic_gain > 0.6,
        "dynamic cabin control stays within its three-dB maximum cut");
    assert_true(measure_sine_gain(engine, 1000.0, 2) > 0.95,
        "dynamic cabin control does not affect unmeasured bands");
    dsp_engine_destroy(engine);

    engine = create_test_engine_with_cabin(
        48000, 2, 512, &static_config, static_coefficients, DSP_BIQUAD_COEFFICIENT_COUNT,
        &bypass_dynamic_eq);
    assert_true(engine != NULL, "cabin A/B graph creates");
    double phase = 0.0;
    const double phase_step = 2.0 * 3.14159265358979323846 * 82.0 / 48000.0;
    float input[512U * 2U];
    float output[512U * 2U];
    for (int block = 0; block < 16; block++) {
        for (size_t frame = 0; frame < 512U; frame++) {
            const float sample = (float)(0.4 * sin(phase));
            phase += phase_step;
            input[frame * 2U] = sample;
            input[frame * 2U + 1U] = sample;
        }
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "cabin filter state warms before A/B transition");
    }
    assert_true(dsp_engine_update_cabin_bypass(engine, 1) == DSP_STATUS_OK,
        "temporary Cabin A/B bypass target is accepted");
    float previous = output[1022];
    float maximum_step = 0.0f;
    for (int block = 0; block < 2; block++) {
        for (size_t frame = 0; frame < 512U; frame++) {
            const float sample = (float)(0.4 * sin(phase));
            phase += phase_step;
            input[frame * 2U] = sample;
            input[frame * 2U + 1U] = sample;
        }
        assert_true(dsp_engine_process(engine, input, 1024, output, 1024, 512, 2) == DSP_STATUS_OK,
            "cabin A/B transition block processes");
        for (size_t frame = 0; frame < 512U; frame++) {
            const float sample = output[frame * 2U];
            maximum_step = fmaxf(maximum_step, fabsf(sample - previous));
            previous = sample;
        }
    }
    assert_true(maximum_step < 0.03f, "Cabin A/B bypass crossfades without a click");
    assert_near(output[1022], input[1022], 1e-6, "Cabin A/B bypass settles to the latency-aligned dry signal");
    assert_true(dsp_engine_update_cabin_bypass(engine, 2) == DSP_STATUS_INVALID_ARGUMENT,
        "native Cabin A/B update rejects unknown values");
    dsp_engine_destroy(engine);
}

static void test_harmonic_exciter_virtual_bass_oversampling_and_fixed_latency(void) {
    double high_coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    double virtual_bass_coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    design_high_pass(48000.0, 5000.0, 0.7071067811865476, high_coefficients);
    design_low_pass(48000.0, 16000.0, 0.7071067811865476, high_coefficients + 5);
    design_high_pass(48000.0, 35.0, 0.7071067811865476, virtual_bass_coefficients + 10);
    design_low_pass(48000.0, 80.0, 0.7071067811865476, virtual_bass_coefficients + 15);

    dsp_harmonic_config exciter = {
        .enabled = 1,
        .bypassed = 0,
        .quality = 2,
        .high_exciter_enabled = 1,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 2.0,
        .high_exciter_amount = 0.75,
        .high_exciter_mode = DSP_HARMONIC_MODE_EVEN,
        .high_exciter_wet = 1.0,
        .high_exciter_compensation_db = 0.0,
        .virtual_bass_enabled = 0,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 0.5,
        .virtual_bass_drive = 2.0,
        .virtual_bass_second_amount = 1.0,
        .virtual_bass_third_amount = 1.0,
        .virtual_bass_wet = 0.0,
    };
    dsp_engine *engine = create_test_engine_with_harmonic(48000, 1, 512, &exciter, high_coefficients);
    assert_true(engine != NULL, "2x high exciter graph creates");
    assert_true(dsp_engine_get_latency_frames(engine) == 64, "2x high exciter reports aligned interpolation and decimation delay");
    const dsp_test_harmonic_spectrum even_spectrum = measure_harmonic_spectrum(engine, 1, 6000.0, 0.3);
    assert_near(even_spectrum.fundamental, 0.3, 0.01,
        "even exciter cancels its fundamental residual");
    assert_true(even_spectrum.second > 0.005, "even exciter creates a measurable second harmonic");
    assert_true(even_spectrum.third < even_spectrum.second * 0.2,
        "even exciter produces less third than second harmonic");
    dsp_engine_destroy(engine);

    exciter.high_exciter_mode = DSP_HARMONIC_MODE_ODD;
    engine = create_test_engine_with_harmonic(48000, 1, 512, &exciter, high_coefficients);
    assert_true(engine != NULL, "odd high exciter graph creates");
    const dsp_test_harmonic_spectrum odd_spectrum = measure_harmonic_spectrum(engine, 1, 6000.0, 0.3);
    assert_near(odd_spectrum.fundamental, 0.3, 0.01,
        "odd exciter cancels its fundamental residual");
    assert_true(odd_spectrum.third > 0.002, "odd exciter creates a measurable third harmonic");
    assert_true(odd_spectrum.second < odd_spectrum.third * 0.2,
        "odd exciter produces less second than third harmonic");
    dsp_engine_destroy(engine);

    engine = create_test_engine_with_harmonic(48000, 1, 512, &exciter, high_coefficients);
    assert_true(engine != NULL, "2x alias test graph creates");
    const dsp_test_harmonic_spectrum odd_2x_alias = measure_harmonic_spectrum(engine, 1, 10000.0, 0.25);
    dsp_engine_destroy(engine);
    exciter.quality = 4;
    engine = create_test_engine_with_harmonic(48000, 1, 512, &exciter, high_coefficients);
    assert_true(engine != NULL, "4x high exciter graph creates");
    const dsp_test_harmonic_spectrum odd_4x_alias = measure_harmonic_spectrum(engine, 1, 10000.0, 0.25);
    const double alias_2x_db = 20.0 * log10(odd_2x_alias.third / odd_2x_alias.fundamental);
    const double alias_4x_db = 20.0 * log10(odd_4x_alias.third / odd_4x_alias.fundamental);
    printf("DSP_HARMONIC_ALIAS mode=odd inputHz=10000 quality=2x dBc=%.2f quality=4x dBc=%.2f\n",
        alias_2x_db, alias_4x_db);
    assert_true(alias_2x_db < -50.0 && alias_4x_db < -50.0,
        "oversampled exciter attenuates the 30kHz product folded at 18kHz below -50dBc");
    assert_true(dsp_engine_get_latency_frames(engine) == 64, "2x and 4x modes share a fixed graph latency");
    dsp_engine_destroy(engine);

    dsp_harmonic_config virtual_bass = {
        .enabled = 1,
        .bypassed = 0,
        .quality = 4,
        .high_exciter_enabled = 0,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 1.0,
        .high_exciter_amount = 0.5,
        .high_exciter_mode = DSP_HARMONIC_MODE_MIXED,
        .high_exciter_wet = 0.0,
        .high_exciter_compensation_db = 0.0,
        .virtual_bass_enabled = 1,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 1.0,
        .virtual_bass_drive = 2.0,
        .virtual_bass_second_amount = 1.0,
        .virtual_bass_third_amount = 1.0,
        .virtual_bass_wet = 1.0,
    };
    engine = create_test_engine_with_harmonic(48000, 2, 512, &virtual_bass, virtual_bass_coefficients);
    assert_true(engine != NULL, "4x virtual bass graph creates");
    const dsp_test_harmonic_spectrum bass_spectrum = measure_harmonic_spectrum(engine, 2, 50.0, 0.2);
    assert_true(dsp_engine_get_latency_frames(engine) == 64, "virtual bass uses the declared fixed latency");
    assert_true(bass_spectrum.second > 0.004, "virtual bass creates a measured second harmonic");
    assert_true(bass_spectrum.third > 0.001, "virtual bass creates a measured third harmonic");
    assert_near(bass_spectrum.fundamental, 0.2, 0.01,
        "virtual bass generation does not boost the in-band fundamental");
    dsp_engine_destroy(engine);

    exciter.high_exciter_wet = 0.0;
    engine = create_test_engine_with_harmonic(48000, 1, 512, &exciter, high_coefficients);
    assert_true(engine != NULL, "zero-wet exciter bypass graph creates");
    assert_true(dsp_engine_get_latency_frames(engine) == 0, "zero-wet exciter adds no latency");
    float input[] = {0.25f, -0.5f, 0.75f, -1.0f};
    float output[4] = {0.0f};
    assert_true(dsp_engine_process(engine, input, 4, output, 4, 4, 1) == DSP_STATUS_OK,
        "zero-wet exciter bypass block processes");
    for (size_t index = 0; index < 4U; index++) {
        assert_near(output[index], input[index], 1e-7, "zero-wet exciter is an exact identity");
    }
    dsp_engine_destroy(engine);
}

static void test_harmonic_silence_impulse_channel_isolation_and_sample_rates(void) {
    const int sample_rates[] = {44100, 48000, 96000};
    const dsp_harmonic_config config = {
        .enabled = 1,
        .bypassed = 0,
        .quality = 2,
        .high_exciter_enabled = 1,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 2.0,
        .high_exciter_amount = 0.5,
        .high_exciter_mode = DSP_HARMONIC_MODE_MIXED,
        .high_exciter_wet = 0.75,
        .high_exciter_compensation_db = 0.0,
        .virtual_bass_enabled = 0,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 0.5,
        .virtual_bass_drive = 1.0,
        .virtual_bass_second_amount = 0.5,
        .virtual_bass_third_amount = 0.25,
        .virtual_bass_wet = 0.0,
    };
    for (size_t rate_index = 0; rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]); rate_index++) {
        const int sample_rate = sample_rates[rate_index];
        double coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
        design_high_pass((double)sample_rate, 5000.0, 0.7071067811865476, coefficients);
        design_low_pass((double)sample_rate, 0.42 * sample_rate, 0.7071067811865476,
            coefficients + DSP_BIQUAD_COEFFICIENT_COUNT);
        for (int channels = 1; channels <= 2; channels++) {
            dsp_engine *engine = create_test_engine_with_harmonic(
                sample_rate, channels, 512, &config, coefficients);
            assert_true(engine != NULL, "harmonic graph creates for each rate and channel count");
            assert_true(dsp_engine_get_latency_frames(engine) == DSP_HARMONIC_ALGORITHM_LATENCY_FRAMES,
                "harmonic reports the rate-prepared FIR latency");
            float zero_input[1024] = {0.0f};
            float zero_output[1024] = {0.0f};
            for (int block = 0; block < 4; block++) {
                assert_true(dsp_engine_process(engine, zero_input, 512U * (size_t)channels,
                    zero_output, 512U * (size_t)channels, 512, channels) == DSP_STATUS_OK,
                    "harmonic silence block processes");
                for (size_t sample = 0; sample < 512U * (size_t)channels; sample++) {
                    assert_near(zero_output[sample], 0.0, 1e-8, "zero input remains digital silence");
                }
            }
            dsp_engine_destroy(engine);
        }
    }

    double coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    design_high_pass(48000.0, 5000.0, 0.7071067811865476, coefficients);
    design_low_pass(48000.0, 16000.0, 0.7071067811865476,
        coefficients + DSP_BIQUAD_COEFFICIENT_COUNT);
    dsp_harmonic_config stereo_config = config;
    dsp_engine *stereo = create_test_engine_with_harmonic(48000, 2, 512, &stereo_config, coefficients);
    assert_true(stereo != NULL, "stereo harmonic isolation graph creates");
    float left_only_input[1024] = {0.0f};
    float stereo_output[1024] = {0.0f};
    left_only_input[0] = 0.8f;
    for (int block = 0; block < 16; block++) {
        assert_true(dsp_engine_process(stereo, left_only_input, 1024, stereo_output, 1024, 512, 2) ==
            DSP_STATUS_OK, "stereo harmonic impulse block processes");
        for (size_t frame = 0; frame < 512U; frame++) {
            assert_true(isfinite(stereo_output[frame * 2U]), "harmonic impulse output remains finite");
            assert_near(stereo_output[frame * 2U + 1U], 0.0, 1e-8,
                "harmonic processing never leaks left input into the right channel");
        }
        memset(left_only_input, 0, sizeof(left_only_input));
    }
    dsp_engine_destroy(stereo);
}

static void test_harmonic_ab_bypass_crossfades_at_fixed_latency(void) {
    double coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    design_high_pass(48000.0, 5000.0, 0.7071067811865476, coefficients);
    design_low_pass(48000.0, 16000.0, 0.7071067811865476,
        coefficients + DSP_BIQUAD_COEFFICIENT_COUNT);
    const dsp_harmonic_config config = {
        .enabled = 1,
        .bypassed = 0,
        .quality = 2,
        .high_exciter_enabled = 1,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 2.0,
        .high_exciter_amount = 0.75,
        .high_exciter_mode = DSP_HARMONIC_MODE_MIXED,
        .high_exciter_wet = 0.8,
        .high_exciter_compensation_db = 0.0,
        .virtual_bass_enabled = 0,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 0.5,
        .virtual_bass_drive = 1.0,
        .virtual_bass_second_amount = 0.5,
        .virtual_bass_third_amount = 0.25,
        .virtual_bass_wet = 0.0,
    };
    dsp_engine *engine = create_test_engine_with_harmonic(48000, 1, 512, &config, coefficients);
    assert_true(engine != NULL, "harmonic A/B bypass graph creates");
    assert_true(dsp_engine_get_latency_frames(engine) == DSP_HARMONIC_ALGORITHM_LATENCY_FRAMES,
        "harmonic A/B bypass graph reserves fixed latency");

    const double phase_step = 2.0 * 3.14159265358979323846 * 6000.0 / 48000.0;
    double phase = 0.0;
    size_t absolute_frame = 0;
    float input[512];
    float output[512];
    for (int block = 0; block < 3; block++) {
        for (size_t frame = 0; frame < 512U; frame++) {
            input[frame] = 0.3f * (float)sin(phase);
            phase += phase_step;
        }
        assert_true(dsp_engine_process(engine, input, 512, output, 512, 512, 1) == DSP_STATUS_OK,
            "harmonic active block before bypass processes");
        absolute_frame += 512U;
    }

    const size_t active_block_start = absolute_frame - 512U;
    double previous_error = (double)output[0] -
        0.3 * sin(phase_step * (double)(active_block_start - 64U));
    double maximum_active_error_step = 0.0;
    for (size_t frame = 1; frame < 512U; frame++) {
        const double error = (double)output[frame] -
            0.3 * sin(phase_step * (double)(active_block_start + frame - 64U));
        maximum_active_error_step = fmax(maximum_active_error_step, fabs(error - previous_error));
        previous_error = error;
    }
    assert_true(dsp_engine_update_harmonic_bypass(engine, 1) == DSP_STATUS_OK,
        "harmonic A/B bypass accepts valid target");
    double maximum_error_step = 0.0;
    for (int block = 0; block < 3; block++) {
        for (size_t frame = 0; frame < 512U; frame++) {
            input[frame] = 0.3f * (float)sin(phase);
            phase += phase_step;
        }
        assert_true(dsp_engine_process(engine, input, 512, output, 512, 512, 1) == DSP_STATUS_OK,
            "harmonic bypass transition block processes");
        for (size_t frame = 0; frame < 512U; frame++) {
            const size_t stream_frame = absolute_frame + frame;
            const float aligned_dry = 0.3f * (float)sin(phase_step * (double)(stream_frame - 64U));
            const double error = (double)output[frame] - aligned_dry;
            maximum_error_step = fmax(maximum_error_step, fabs(error - previous_error));
            previous_error = error;
        }
        absolute_frame += 512U;
    }
    printf("DSP_HARMONIC_BYPASS residualStepActive=%.6f residualStepCrossfade=%.6f\n",
        maximum_active_error_step, maximum_error_step);
    assert_true(maximum_error_step <= maximum_active_error_step + 0.01,
        "harmonic A/B crossfade has no abrupt residual step");
    const size_t final_block_start = absolute_frame - 512U;
    for (size_t frame = 0; frame < 512U; frame++) {
        const size_t stream_frame = final_block_start + frame;
        const float aligned_dry = 0.3f * (float)sin(phase_step * (double)(stream_frame - 64U));
        assert_near(output[frame], aligned_dry, 1e-6,
            "harmonic A/B bypass settles to delayed dry without changing latency");
    }
    assert_true(dsp_engine_update_harmonic_bypass(engine, 2) == DSP_STATUS_INVALID_ARGUMENT,
        "native Harmonic A/B update rejects unknown values");
    dsp_engine_destroy(engine);
}

static void test_harmonic_extreme_drive_is_finite_and_chunk_invariant(void) {
    double coefficients[DSP_HARMONIC_COEFFICIENT_COUNT] = {0.0};
    design_high_pass(48000.0, 5000.0, 0.7071067811865476, coefficients);
    design_low_pass(48000.0, 16000.0, 0.7071067811865476,
        coefficients + DSP_BIQUAD_COEFFICIENT_COUNT);
    design_high_pass(48000.0, 35.0, 0.7071067811865476,
        coefficients + 2U * DSP_BIQUAD_COEFFICIENT_COUNT);
    design_low_pass(48000.0, 80.0, 0.7071067811865476,
        coefficients + 3U * DSP_BIQUAD_COEFFICIENT_COUNT);
    const dsp_harmonic_config config = {
        .enabled = 1,
        .bypassed = 0,
        .quality = 4,
        .high_exciter_enabled = 1,
        .high_exciter_frequency_hz = 5000.0,
        .high_exciter_drive = 8.0,
        .high_exciter_amount = 1.0,
        .high_exciter_mode = DSP_HARMONIC_MODE_MIXED,
        .high_exciter_wet = 1.0,
        .high_exciter_compensation_db = 6.0,
        .virtual_bass_enabled = 1,
        .virtual_bass_fundamental_low_hz = 35.0,
        .virtual_bass_fundamental_high_hz = 80.0,
        .virtual_bass_amount = 1.0,
        .virtual_bass_drive = 8.0,
        .virtual_bass_second_amount = 1.0,
        .virtual_bass_third_amount = 1.0,
        .virtual_bass_wet = 1.0,
    };
    const size_t total_frames = 2048U;
    const int block_sizes[] = {1, 128, 512, 1537};
    float input[total_frames * 2U];
    float reference[total_frames * 2U];
    float output[total_frames * 2U];
    for (size_t frame = 0; frame < total_frames; frame++) {
        const double time = (double)frame / 48000.0;
        const float sample = (float)(0.25 * sin(2.0 * 3.14159265358979323846 * 6000.0 * time) +
            0.35 * sin(2.0 * 3.14159265358979323846 * 50.0 * time));
        input[frame * 2U] = sample;
        input[frame * 2U + 1U] = sample;
    }
    for (size_t block_index = 0; block_index < sizeof(block_sizes) / sizeof(block_sizes[0]); block_index++) {
        dsp_engine *engine = create_test_engine_with_harmonic(48000, 2, 2048, &config, coefficients);
        assert_true(engine != NULL, "maximum-drive Harmonic graph creates");
        size_t position = 0;
        while (position < total_frames) {
            const size_t frames = total_frames - position < (size_t)block_sizes[block_index]
                ? total_frames - position
                : (size_t)block_sizes[block_index];
            assert_true(dsp_engine_process(engine, input + position * 2U, frames * 2U,
                output + position * 2U, frames * 2U, (int)frames, 2) == DSP_STATUS_OK,
                "maximum-drive Harmonic chunk processes");
            position += frames;
        }
        for (size_t sample = 0; sample < total_frames * 2U; sample++) {
            assert_true(isfinite(output[sample]), "maximum-drive Harmonic output remains finite");
        }
        if (block_index == 0U) {
            memcpy(reference, output, sizeof(reference));
        } else {
            for (size_t sample = 0; sample < total_frames * 2U; sample++) {
                assert_near(output[sample], reference[sample], 1e-7,
                    "Harmonic output is invariant to process chunk size");
            }
        }
        dsp_engine_destroy(engine);
    }
}

static double measure_side_sine_gain(dsp_engine *engine, double frequency_hz) {
    const size_t total_frames = 8192U;
    const size_t warmup_frames = 2048U;
    const size_t block_frames = 512U;
    double input_energy = 0.0;
    double output_energy = 0.0;
    double phase = 0.0;
    const double phase_step = 2.0 * 3.14159265358979323846 * frequency_hz / 48000.0;
    for (size_t block = 0; block < total_frames / block_frames; block++) {
        float input[block_frames * 2U];
        float output[block_frames * 2U];
        for (size_t frame = 0; frame < block_frames; frame++) {
            const float sample = (float)sin(phase);
            phase += phase_step;
            input[frame * 2U] = sample;
            input[frame * 2U + 1U] = -sample;
        }
        assert_true(dsp_engine_process(engine, input, block_frames * 2U, output,
            block_frames * 2U, (int)block_frames, 2) == DSP_STATUS_OK,
            "M/S width sine block processes");
        const size_t block_start = block * block_frames;
        if (block_start + block_frames <= warmup_frames) continue;
        const size_t first_frame = warmup_frames > block_start ? warmup_frames - block_start : 0U;
        for (size_t frame = first_frame; frame < block_frames; frame++) {
            input_energy += (double)input[frame * 2U] * input[frame * 2U];
            output_energy += (double)output[frame * 2U] * output[frame * 2U];
        }
    }
    return sqrt(output_energy / input_energy);
}

static void test_advanced_ms_neutral_reconstruction_legacy_parity_and_monitor_modes(void) {
    double mono_bass_coefficients[DSP_BIQUAD_COEFFICIENT_COUNT];
    design_high_pass(48000.0, 120.0, 0.7071067811865476, mono_bass_coefficients);
    const dsp_spatial_config legacy_spatial = {1.5, 1, 120};
    const dsp_advanced_ms_config neutral_advanced = {
        1, 0.0, 0.0, 1.0, 1.0, 1.0, 150.0, 4000.0, 0.0,
        DSP_ADVANCED_MS_MONITOR_NORMAL, 0, 0,
    };
    const double advanced_coefficients[
        DSP_ADVANCED_MS_COEFFICIENT_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT] = {0.0};
    dsp_engine *legacy = create_test_engine_with_spatial(
        48000, 2, 512, &legacy_spatial, NULL, 0, mono_bass_coefficients, 1);
    dsp_engine *advanced = create_test_engine_with_advanced_ms(
        48000, 2, 512, &legacy_spatial, &neutral_advanced,
        advanced_coefficients, mono_bass_coefficients, 1);
    assert_true(legacy != NULL && advanced != NULL, "legacy and Advanced M/S graphs create");
    uint32_t random_state = UINT32_C(0x2468ace1);
    for (size_t block = 0; block < 16U; block++) {
        float input[512U * 2U];
        float legacy_output[512U * 2U];
        float advanced_output[512U * 2U];
        for (size_t sample = 0; sample < 512U * 2U; sample++) {
            random_state = random_state * UINT32_C(1664525) + UINT32_C(1013904223);
            input[sample] = (float)(random_state >> 8) / 8388607.5f - 1.0f;
        }
        assert_true(dsp_engine_process(legacy, input, 1024, legacy_output, 1024, 512, 2) == DSP_STATUS_OK,
            "legacy M/S reference block processes");
        assert_true(dsp_engine_process(advanced, input, 1024, advanced_output, 1024, 512, 2) == DSP_STATUS_OK,
            "Advanced M/S unity block processes");
        for (size_t sample = 0; sample < 512U * 2U; sample++) {
            assert_near(advanced_output[sample], legacy_output[sample], 2e-6,
                "Advanced M/S unity settings preserve legacy width and mono bass");
        }
    }
    dsp_engine_destroy(legacy);
    dsp_engine_destroy(advanced);

    const dsp_spatial_config width_one = {1.0, 0, 120};
    dsp_advanced_ms_config width_bands = neutral_advanced;
    width_bands.low_width = 0.5;
    width_bands.high_width = 1.5;
    advanced = create_test_engine_with_advanced_ms(
        48000, 2, 512, &width_one, &width_bands, advanced_coefficients, NULL, 0);
    assert_true(advanced != NULL, "frequency-dependent width graph creates");
    const double low_side_gain = measure_side_sine_gain(advanced, 40.0);
    assert_true(dsp_engine_reset(advanced) == DSP_STATUS_OK, "low-band side state resets");
    const double mid_side_gain = measure_side_sine_gain(advanced, 1000.0);
    assert_true(dsp_engine_reset(advanced) == DSP_STATUS_OK, "mid-band side state resets");
    const double high_side_gain = measure_side_sine_gain(advanced, 15000.0);
    printf("DSP_ADVANCED_MS_WIDTH low=%.5f mid=%.5f high=%.5f\n",
        low_side_gain, mid_side_gain, high_side_gain);
    assert_true(low_side_gain < mid_side_gain && mid_side_gain < high_side_gain,
        "low, mid and high Side width bands remain independent");
    assert_true(low_side_gain < 0.85 && mid_side_gain > 0.85 && mid_side_gain < 1.15 && high_side_gain > 1.25,
        "frequency-dependent width follows its configured band controls");
    dsp_engine_destroy(advanced);

    dsp_advanced_ms_config mid_only = neutral_advanced;
    mid_only.monitor_mode = DSP_ADVANCED_MS_MONITOR_MID_ONLY;
    advanced = create_test_engine_with_advanced_ms(
        48000, 2, 512, &width_one, &mid_only, advanced_coefficients, NULL, 0);
    assert_true(advanced != NULL, "temporary Mid-only monitor graph creates");
    const float anti_phase[] = {0.25f, -0.25f, -0.5f, 0.5f};
    float monitor_output[4] = {0};
    assert_true(dsp_engine_process(advanced, anti_phase, 4, monitor_output, 4, 2, 2) == DSP_STATUS_OK,
        "Mid-only monitor block processes");
    for (size_t index = 0; index < 4; index++) {
        assert_near(monitor_output[index], 0.0, 1e-7, "Mid-only monitor removes Side-only input");
    }
    dsp_engine_destroy(advanced);

    dsp_advanced_ms_config side_only = neutral_advanced;
    side_only.monitor_mode = DSP_ADVANCED_MS_MONITOR_SIDE_ONLY;
    advanced = create_test_engine_with_advanced_ms(
        48000, 2, 512, &width_one, &side_only, advanced_coefficients, NULL, 0);
    assert_true(advanced != NULL, "temporary Side-only monitor graph creates");
    const float center_input[] = {0.25f, 0.25f, -0.5f, -0.5f};
    memset(monitor_output, 0, sizeof(monitor_output));
    assert_true(dsp_engine_process(advanced, center_input, 4, monitor_output, 4, 2, 2) == DSP_STATUS_OK,
        "Side-only monitor block processes");
    for (size_t index = 0; index < 4; index++) {
        assert_near(monitor_output[index], 0.0, 1e-7, "Side-only monitor removes Mid-only input");
    }
    dsp_engine_destroy(advanced);

    advanced = create_test_engine_with_advanced_ms(
        48000, 2, 512, &width_one, &neutral_advanced, advanced_coefficients, NULL, 0);
    assert_true(advanced != NULL, "live monitor graph creates");
    float live_input[512U * 2U];
    float live_output[512U * 2U];
    for (size_t frame = 0; frame < 512U; frame++) {
        live_input[frame * 2U] = 0.5f;
        live_input[frame * 2U + 1U] = -0.5f;
    }
    assert_true(dsp_engine_process(advanced, live_input, 1024, live_output, 1024, 512, 2) == DSP_STATUS_OK,
        "normal monitor warmup block processes");
    assert_true(dsp_engine_update_advanced_ms_monitor_mode(
        advanced, DSP_ADVANCED_MS_MONITOR_MID_ONLY) == DSP_STATUS_OK,
        "temporary Mid-only mode updates in place");
    assert_true(dsp_engine_process(advanced, live_input, 1024, live_output, 1024, 512, 2) == DSP_STATUS_OK,
        "temporary monitor transition processes");
    float maximum_step = 0.0f;
    float previous_sample = 0.5f;
    for (size_t frame = 0; frame < 512U; frame++) {
        const float current_sample = live_output[frame * 2U];
        maximum_step = fmaxf(maximum_step, fabsf(current_sample - previous_sample));
        previous_sample = current_sample;
    }
    assert_true(maximum_step < 0.01f, "temporary monitor changes crossfade without a click");
    assert_true(dsp_engine_process(advanced, live_input, 1024, live_output, 1024, 512, 2) == DSP_STATUS_OK,
        "temporary monitor transition completes");
    assert_near(live_output[1022], 0.0, 1e-5, "Mid-only monitor settles to the center channel");
    assert_true(dsp_engine_update_advanced_ms_monitor_mode(advanced, 99) == DSP_STATUS_INVALID_ARGUMENT,
        "native monitor update rejects unknown enum values");
    dsp_engine_destroy(advanced);
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
    test_cabin_static_and_dynamic_cuts_are_measurement_band_limited();
    test_harmonic_exciter_virtual_bass_oversampling_and_fixed_latency();
    test_harmonic_silence_impulse_channel_isolation_and_sample_rates();
    test_harmonic_extreme_drive_is_finite_and_chunk_invariant();
    test_harmonic_ab_bypass_crossfades_at_fixed_latency();
    test_advanced_ms_neutral_reconstruction_legacy_parity_and_monitor_modes();
    test_seat_focus_fixed_latency_mono_bypass_and_isolation();
    test_seat_focus_fractional_delay_accuracy_and_chunk_invariance();
    test_seat_focus_trim_polarity_and_live_targets_are_smooth();
    test_seat_focus_live_trim_keeps_prepared_headroom_safe();
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
