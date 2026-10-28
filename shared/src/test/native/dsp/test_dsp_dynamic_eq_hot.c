#include "dsp_dynamic_eq.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TEST_SAMPLE_RATE 48000
#define TEST_FREQUENCY_HZ 1000.0

static int failures;

static void assert_true(int condition, const char *message) {
    if (!condition) {
        fprintf(stderr, "FAIL: %s\n", message);
        failures++;
    }
}

static void assert_near(double actual, double expected, double tolerance, const char *message) {
    if (!isfinite(actual) || fabs(actual - expected) > tolerance) {
        fprintf(stderr, "FAIL: %s (actual %.9f expected %.9f tolerance %.9f)\n",
            message, actual, expected, tolerance);
        failures++;
    }
}

static dsp_dynamic_eq_config make_config(int mode, double maximum) {
    dsp_dynamic_eq_config config = {
        .enabled = 1,
        .bands = {
            {1, mode, 1000.0, 1.4, -24.0, 2.0, 5.0, 60.0, maximum, maximum},
            {1, mode, 2500.0, 1.1, -25.0, 2.5, 7.0, 70.0, maximum, maximum},
            {1, mode, 5000.0, 0.9, -26.0, 3.0, 9.0, 80.0, maximum, maximum},
            {1, mode, 8000.0, 0.8, -27.0, 3.5, 11.0, 90.0, maximum, maximum},
            {1, mode, 12000.0, 0.7, -28.0, 4.0, 13.0, 100.0, maximum, maximum},
        },
    };
    return config;
}

static int prepare_eq(dsp_dynamic_eq *eq, const dsp_dynamic_eq_config *config) {
    memset(eq, 0, sizeof(*eq));
    return dsp_dynamic_eq_prepare(eq, TEST_SAMPLE_RATE, config);
}

static int same_biquad(const dsp_biquad *left, const dsp_biquad *right) {
    return left->b0 == right->b0 && left->b1 == right->b1 && left->b2 == right->b2 &&
        left->a1 == right->a1 && left->a2 == right->a2 &&
        left->z1[0] == right->z1[0] && left->z1[1] == right->z1[1] &&
        left->z2[0] == right->z2[0] && left->z2[1] == right->z2[1];
}

static int same_band_state(const dsp_dynamic_eq_band *left, const dsp_dynamic_eq_band *right) {
    return same_biquad(&left->detector, &right->detector) && same_biquad(&left->filter, &right->filter) &&
        left->envelope == right->envelope && left->current_mix == right->current_mix &&
        left->target_mix == right->target_mix && left->mix_step == right->mix_step &&
        left->frames_until_control == right->frames_until_control;
}

static void test_hot_update_changes_parameters_and_preserves_all_band_state(void) {
    dsp_dynamic_eq_config initial = make_config(DSP_DYNAMIC_EQ_BOOST, 3.0);
    dsp_dynamic_eq eq;
    assert_true(prepare_eq(&eq, &initial), "initial five-band Dynamic EQ prepares");

    for (int frame = 0; frame < 384; frame++) {
        float left = 0.2f * sinf((float)(2.0 * 3.14159265358979323846 * 997.0 * frame / TEST_SAMPLE_RATE));
        float right = -left;
        dsp_dynamic_eq_process_frame(&eq, &left, &right, 2);
    }
    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        eq.bands[band].target_mix = 0.15f + (float)band * 0.1f;
        eq.bands[band].current_mix = 0.1f + (float)band * 0.1f;
        eq.bands[band].mix_step = 0.003f;
        eq.bands[band].frames_until_control = (int)band + 2;
    }
    dsp_dynamic_eq before = eq;

    dsp_dynamic_eq_config target = initial;
    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        target.bands[band].threshold_db = -18.0 - (double)band;
        target.bands[band].ratio = 5.0 + (double)band;
        target.bands[band].attack_ms = 1.0 + (double)band;
        target.bands[band].release_ms = 20.0 + (double)band;
        target.bands[band].max_boost_db = (double)band * 2.5;
        target.bands[band].max_cut_db = 12.0 - (double)band * 2.0;
    }
    dsp_dynamic_eq_hot_update update;
    assert_true(dsp_dynamic_eq_prepare_hot_update(TEST_SAMPLE_RATE, &target, &update),
        "all five band parameters prepare off the render path");
    assert_true(dsp_dynamic_eq_apply_hot_update(&eq, &update), "five-band parameter update applies");

    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        assert_true(same_band_state(&before.bands[band], &eq.bands[band]),
            "detector/filter coefficients and delay, envelope, and current mix remain unchanged");
        assert_near(eq.bands[band].config.threshold_db, target.bands[band].threshold_db, 0.0,
            "threshold updates");
        assert_near(eq.bands[band].config.ratio, target.bands[band].ratio, 0.0, "ratio updates");
        assert_near(eq.bands[band].config.attack_ms, target.bands[band].attack_ms, 0.0,
            "attack updates");
        assert_near(eq.bands[band].config.release_ms, target.bands[band].release_ms, 0.0,
            "release updates");
        assert_near(eq.bands[band].config.max_boost_db, target.bands[band].max_boost_db, 0.0,
            "boost cap updates");
        assert_near(eq.bands[band].config.max_cut_db, target.bands[band].max_cut_db, 0.0,
            "cut cap updates");
        assert_near(eq.bands[band].attack_coefficient, update.attack_coefficients[band], 0.0,
            "precomputed attack coefficient installs");
        assert_near(eq.bands[band].release_coefficient, update.release_coefficients[band], 0.0,
            "precomputed release coefficient installs");
        assert_near(update.attack_coefficients[band],
            exp(-1.0 / (TEST_SAMPLE_RATE * target.bands[band].attack_ms * 0.001)), 1.0e-7,
            "attack coefficient is prepared for the active sample rate");
        assert_near(update.release_coefficients[band],
            exp(-1.0 / (TEST_SAMPLE_RATE * target.bands[band].release_ms * 0.001)), 1.0e-7,
            "release coefficient is prepared for the active sample rate");
    }
}

static void test_topology_and_invalid_value_rejections_are_atomic(void) {
    dsp_dynamic_eq_config initial = make_config(DSP_DYNAMIC_EQ_BOOST, 3.0);
    const size_t topology_changes[] = {0, 1, 2, 3, 4, 5};
    for (size_t change = 0; change < sizeof(topology_changes) / sizeof(topology_changes[0]); change++) {
        dsp_dynamic_eq eq;
        assert_true(prepare_eq(&eq, &initial), "topology rejection test graph prepares");
        dsp_dynamic_eq before = eq;
        dsp_dynamic_eq_config target = initial;
        switch (topology_changes[change]) {
            case 0: target.bands[2].frequency_hz += 1.0; break;
            case 1: target.bands[2].q += 0.1; break;
            case 2: target.bands[2].mode = DSP_DYNAMIC_EQ_CUT; break;
            case 3: target.bands[2].enabled = 0; break;
            case 4: target.enabled = 0; break;
            default: target.bands[3].frequency_hz += 2.0; break;
        }
        dsp_dynamic_eq_hot_update update;
        assert_true(dsp_dynamic_eq_prepare_hot_update(TEST_SAMPLE_RATE, &target, &update),
            "validly shaped topology candidate prepares");
        assert_true(!dsp_dynamic_eq_apply_hot_update(&eq, &update), "topology change is rejected");
        assert_true(memcmp(&eq, &before, sizeof(eq)) == 0, "rejected topology leaves the full graph unchanged");
    }

    const int invalid_fields[] = {0, 1, 2, 3};
    for (size_t invalid = 0; invalid < sizeof(invalid_fields) / sizeof(invalid_fields[0]); invalid++) {
        dsp_dynamic_eq eq;
        assert_true(prepare_eq(&eq, &initial), "range rejection test graph prepares");
        dsp_dynamic_eq before = eq;
        dsp_dynamic_eq_config target = initial;
        if (invalid_fields[invalid] == 0) target.bands[0].max_boost_db = 12.01;
        if (invalid_fields[invalid] == 1) target.bands[0].max_cut_db = 12.01;
        if (invalid_fields[invalid] == 2) target.bands[0].ratio = NAN;
        if (invalid_fields[invalid] == 3) target.bands[4].attack_ms = 0.0;
        dsp_dynamic_eq_hot_update update;
        assert_true(!dsp_dynamic_eq_prepare_hot_update(TEST_SAMPLE_RATE, &target, &update),
            "invalid HOT value is rejected during control-side preparation");
        assert_true(memcmp(&eq, &before, sizeof(eq)) == 0, "invalid candidate leaves the active graph unchanged");
    }

    dsp_dynamic_eq eq;
    assert_true(prepare_eq(&eq, &initial), "tampered-payload graph prepares");
    dsp_dynamic_eq before = eq;
    dsp_dynamic_eq_hot_update update;
    assert_true(dsp_dynamic_eq_prepare_hot_update(TEST_SAMPLE_RATE, &initial, &update),
        "valid HOT payload prepares");
    update.config.bands[4].max_cut_db = 12.01;
    assert_true(!dsp_dynamic_eq_apply_hot_update(&eq, &update), "Native apply revalidates payload bounds");
    assert_true(memcmp(&eq, &before, sizeof(eq)) == 0, "tampered payload is rejected atomically");
}

typedef struct {
    double real;
    double imaginary;
} complex_value;

static complex_value biquad_response(const dsp_biquad *filter, double frequency_hz, int sample_rate) {
    const double omega = 2.0 * 3.14159265358979323846 * frequency_hz / (double)sample_rate;
    const double cosine = cos(omega);
    const double sine = sin(omega);
    const double cosine_twice = cos(2.0 * omega);
    const double sine_twice = sin(2.0 * omega);
    const double numerator_real = filter->b0 + filter->b1 * cosine + filter->b2 * cosine_twice;
    const double numerator_imaginary = -filter->b1 * sine - filter->b2 * sine_twice;
    const double denominator_real = 1.0 + filter->a1 * cosine + filter->a2 * cosine_twice;
    const double denominator_imaginary = -filter->a1 * sine - filter->a2 * sine_twice;
    const double denominator_magnitude_squared = denominator_real * denominator_real +
        denominator_imaginary * denominator_imaginary;
    return (complex_value) {
        (numerator_real * denominator_real + numerator_imaginary * denominator_imaginary) /
            denominator_magnitude_squared,
        (numerator_imaginary * denominator_real - numerator_real * denominator_imaginary) /
            denominator_magnitude_squared,
    };
}

static void test_filter_peak_is_independent_of_limits_and_phase_is_zero_at_center(void) {
    const double limits[] = {0.0, 3.0, 6.0, 12.0};
    for (int mode = DSP_DYNAMIC_EQ_CUT; mode <= DSP_DYNAMIC_EQ_BOOST; mode++) {
        dsp_dynamic_eq reference;
        dsp_dynamic_eq_config first = make_config(mode, limits[0]);
        assert_true(prepare_eq(&reference, &first), "reference peak filter prepares");
        const dsp_biquad expected = reference.bands[0].filter;
        const double signed_peak_db = mode == DSP_DYNAMIC_EQ_CUT ? -12.0 : 12.0;
        const complex_value response = biquad_response(&expected, TEST_FREQUENCY_HZ, TEST_SAMPLE_RATE);
        assert_near(hypot(response.real, response.imaginary), pow(10.0, signed_peak_db / 20.0), 1.0e-8,
            "fixed peak filter has the expected 12 dB center gain");
        assert_near(atan2(response.imaginary, response.real), 0.0, 1.0e-8,
            "fixed peak filter has zero center-frequency phase");

        for (size_t index = 1; index < sizeof(limits) / sizeof(limits[0]); index++) {
            dsp_dynamic_eq candidate;
            dsp_dynamic_eq_config config = make_config(mode, limits[index]);
            assert_true(prepare_eq(&candidate, &config), "limit-specific graph prepares");
            assert_true(same_biquad(&expected, &candidate.bands[0].filter),
                "changing the dynamic limit does not change filter coefficients or delay state");
            const complex_value candidate_response = biquad_response(
                &candidate.bands[0].filter, TEST_FREQUENCY_HZ, TEST_SAMPLE_RATE);
            assert_near(atan2(candidate_response.imaginary, candidate_response.real),
                atan2(response.imaginary, response.real), 0.0,
                "dynamic limit leaves filter phase unchanged");
        }
    }
}

typedef struct {
    double gain_db;
    double phase_radians;
} tone_result;

static tone_result measure_dynamic_eq_tone(dsp_dynamic_eq *eq, int mode, int start_frame, int frame_count) {
    double input_sine = 0.0;
    double input_cosine = 0.0;
    double output_sine = 0.0;
    double output_cosine = 0.0;
    for (int offset = 0; offset < frame_count; offset++) {
        const int frame = start_frame + offset;
        const float input = (mode == DSP_DYNAMIC_EQ_BOOST ? 0.05f : 0.5f) *
            sinf((float)(2.0 * 3.14159265358979323846 * TEST_FREQUENCY_HZ * frame / TEST_SAMPLE_RATE));
        float left = input;
        float right = 0.0f;
        dsp_dynamic_eq_process_frame(eq, &left, &right, 1);
        const double phase = 2.0 * 3.14159265358979323846 * TEST_FREQUENCY_HZ * frame / TEST_SAMPLE_RATE;
        input_sine += (double)input * sin(phase);
        input_cosine += (double)input * cos(phase);
        output_sine += (double)left * sin(phase);
        output_cosine += (double)left * cos(phase);
    }
    const double input_amplitude = 2.0 * hypot(input_sine, input_cosine) / frame_count;
    const double output_amplitude = 2.0 * hypot(output_sine, output_cosine) / frame_count;
    tone_result result = {
        .gain_db = 20.0 * log10(output_amplitude / input_amplitude),
        .phase_radians = atan2(-output_cosine, output_sine) - atan2(-input_cosine, input_sine),
    };
    return result;
}

static void test_gain_caps_track_center_gain_without_changing_phase_or_latency(void) {
    for (int mode = DSP_DYNAMIC_EQ_CUT; mode <= DSP_DYNAMIC_EQ_BOOST; mode++) {
        dsp_dynamic_eq_config config = make_config(mode, 0.0);
        config.bands[0].frequency_hz = TEST_FREQUENCY_HZ;
        config.bands[0].threshold_db = mode == DSP_DYNAMIC_EQ_BOOST ? -10.0 : -30.0;
        config.bands[0].ratio = 20.0;
        config.bands[0].attack_ms = 0.1;
        config.bands[0].release_ms = 0.1;
        for (size_t band = 1; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) config.bands[band].enabled = 0;

        dsp_dynamic_eq eq;
        assert_true(prepare_eq(&eq, &config), "single-band transfer test graph prepares");
        tone_result result = measure_dynamic_eq_tone(&eq, mode, 0, 24000);
        assert_near(result.gain_db, 0.0, 0.05, "zero dB cap leaves the signal unchanged");
        assert_near(result.phase_radians, 0.0, 0.01, "zero cap adds no phase shift at center");

        const double caps[] = {3.0, 6.0, 12.0};
        for (size_t index = 0; index < sizeof(caps) / sizeof(caps[0]); index++) {
            dsp_dynamic_eq_config target = eq.config;
            target.bands[0].max_boost_db = caps[index];
            target.bands[0].max_cut_db = caps[index];
            dsp_dynamic_eq_hot_update update;
            assert_true(dsp_dynamic_eq_prepare_hot_update(TEST_SAMPLE_RATE, &target, &update),
                "gain cap HOT update prepares");
            const dsp_biquad filter_before = eq.bands[0].filter;
            assert_true(dsp_dynamic_eq_apply_hot_update(&eq, &update), "gain cap HOT update applies");
            assert_true(same_biquad(&filter_before, &eq.bands[0].filter),
                "gain cap update preserves filter coefficients and delay state");
            const int start = 24000 + (int)index * 24000;
            result = measure_dynamic_eq_tone(&eq, mode, start, 24000);
            assert_near(result.gain_db, mode == DSP_DYNAMIC_EQ_BOOST ? caps[index] : -caps[index], 0.12,
                "HOT cap produces the requested center gain");
            assert_near(result.phase_radians, 0.0, 0.01,
                "gain cap does not add center-frequency phase or latency");
        }
    }
}

int main(void) {
    test_hot_update_changes_parameters_and_preserves_all_band_state();
    test_topology_and_invalid_value_rejections_are_atomic();
    test_filter_peak_is_independent_of_limits_and_phase_is_zero_at_center();
    test_gain_caps_track_center_gain_without_changing_phase_or_latency();
    if (failures != 0) {
        fprintf(stderr, "%d Dynamic EQ HOT test(s) failed\n", failures);
        return EXIT_FAILURE;
    }
    puts("Dynamic EQ native HOT tests passed");
    return EXIT_SUCCESS;
}
