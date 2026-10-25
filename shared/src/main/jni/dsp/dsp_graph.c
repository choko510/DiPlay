#include "dsp_graph.h"

#include <math.h>

int dsp_graph_prepare(
    dsp_graph *graph,
    int sample_rate,
    int channels,
    double gain_db,
    const double *peq_coefficients,
    size_t peq_count,
    const dsp_dynamics_config *dynamics_config,
    const dsp_multiband_config *multiband_config,
    const dsp_dynamic_eq_config *dynamic_eq_config,
    const double *bass_coefficients,
    int bass_coefficient_count,
    const double *mono_bass_coefficients,
    int mono_bass_coefficient_count,
    const dsp_spatial_config *spatial_config,
    const dsp_convolver_config *convolver_config,
    const dsp_harmonic_config *harmonic_config,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config) {
    if (graph == NULL || peq_count > DSP_BIQUAD_MAX_BANDS ||
        (peq_count > 0 && peq_coefficients == NULL) || !dsp_gain_set_db(&graph->gain, gain_db)) {
        return 0;
    }
    graph->peq_count = peq_count;
    for (size_t index = 0; index < peq_count; index++) {
        if (!dsp_biquad_prepare(
                &graph->peq[index],
                peq_coefficients + index * DSP_BIQUAD_COEFFICIENT_COUNT)) {
            return 0;
        }
    }
    if (!dsp_dynamics_prepare(&graph->dynamics, sample_rate, dynamics_config)) {
        return 0;
    }
    if (!dsp_multiband_prepare(&graph->multiband, sample_rate, multiband_config)) {
        return 0;
    }
    if (!dsp_dynamic_eq_prepare(&graph->dynamic_eq, sample_rate, dynamic_eq_config)) {
        return 0;
    }
    if (!dsp_spatial_prepare(
            &graph->spatial,
            spatial_config,
            sample_rate,
            channels,
            bass_coefficients,
            bass_coefficient_count,
            mono_bass_coefficients,
            mono_bass_coefficient_count)) {
        return 0;
    }
    if (!dsp_harmonic_prepare(&graph->harmonic, sample_rate, channels, harmonic_config)) {
        return 0;
    }
    if (!dsp_convolver_prepare(&graph->convolver, sample_rate, channels, convolver_config)) {
        return 0;
    }
    if (!dsp_macro_prepare(&graph->macro, sample_rate, channels, macro_config) ||
        !dsp_smart_loudness_prepare(&graph->smart_loudness, sample_rate, channels, smart_loudness_config) ||
        !dsp_dynamic_bass_prepare(&graph->dynamic_bass, sample_rate, channels, dynamic_bass_config)) {
        return 0;
    }
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
    return 1;
}

int dsp_graph_apply_studio_hot(
    dsp_graph *graph,
    double gain_db,
    const dsp_macro_config *macro_config,
    const dsp_smart_loudness_config *smart_loudness_config,
    const dsp_dynamic_bass_config *dynamic_bass_config) {
    if (graph == NULL || !isfinite(gain_db) || gain_db < -700.0 || gain_db > 24.0 ||
        macro_config == NULL || smart_loudness_config == NULL || dynamic_bass_config == NULL) {
        return 0;
    }
    if (!dsp_gain_set_db(&graph->gain, gain_db) ||
        !dsp_macro_apply_hot(&graph->macro, macro_config) ||
        !dsp_smart_loudness_apply_hot(&graph->smart_loudness, smart_loudness_config) ||
        !dsp_dynamic_bass_apply_hot(&graph->dynamic_bass, dynamic_bass_config)) {
        return 0;
    }
    return 1;
}

void dsp_graph_reset(dsp_graph *graph) {
    if (graph == NULL) {
        return;
    }
    for (size_t index = 0; index < graph->peq_count; index++) {
        dsp_biquad_reset(&graph->peq[index]);
    }
    dsp_dynamics_reset(&graph->dynamics);
    dsp_multiband_reset(&graph->multiband);
    dsp_dynamic_eq_reset(&graph->dynamic_eq);
    dsp_spatial_reset(&graph->spatial);
    dsp_harmonic_reset(&graph->harmonic);
    dsp_convolver_reset(&graph->convolver);
    dsp_macro_reset(&graph->macro);
    dsp_smart_loudness_reset(&graph->smart_loudness);
    dsp_dynamic_bass_reset(&graph->dynamic_bass);
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
}

void dsp_graph_close(dsp_graph *graph) {
    if (graph == NULL) return;
    dsp_convolver_close(&graph->convolver);
}

void dsp_graph_process(
    dsp_graph *graph,
    const float *input,
    float *output,
    size_t frames,
    size_t channels,
    uint64_t *non_finite_input,
    uint64_t *non_finite_output) {
    if (graph == NULL || input == NULL || output == NULL || channels < 1 || channels > 2) {
        return;
    }

    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
    for (size_t frame = 0; frame < frames; frame++) {
        const size_t offset = frame * channels;
        float input_left = input[offset];
        float input_right = channels == 2 ? input[offset + 1] : 0.0f;
        if (!isfinite(input_left)) {
            input_left = 0.0f;
            if (non_finite_input != NULL) {
                (*non_finite_input)++;
            }
        }
        if (channels == 2 && !isfinite(input_right)) {
            input_right = 0.0f;
            if (non_finite_input != NULL) {
                (*non_finite_input)++;
            }
        }
        dsp_meter_add_frame(&graph->input_meter, input_left, input_right);

        float output_left = dsp_gain_apply(&graph->gain, input_left);
        float output_right = channels == 2 ? dsp_gain_apply(&graph->gain, input_right) : 0.0f;
        dsp_spatial_process_seat(&graph->spatial, &output_left, &output_right, (int)channels);
        for (size_t index = 0; index < graph->peq_count; index++) {
            output_left = dsp_biquad_process_sample(&graph->peq[index], output_left, 0);
            if (channels == 2) {
                output_right = dsp_biquad_process_sample(&graph->peq[index], output_right, 1);
            }
        }
        output_left = dsp_spatial_process_bass(&graph->spatial, output_left, 0);
        if (channels == 2) output_right = dsp_spatial_process_bass(&graph->spatial, output_right, 1);
        dsp_dynamics_process_compressor_frame(&graph->dynamics, &output_left, &output_right, (int)channels);
        dsp_multiband_process_frame(&graph->multiband, &output_left, &output_right, (int)channels);
        dsp_dynamic_eq_process_frame(&graph->dynamic_eq, &output_left, &output_right, (int)channels);
        dsp_spatial_process_stereo(&graph->spatial, &output_left, &output_right, (int)channels);
        dsp_harmonic_process_frame(&graph->harmonic, &output_left, &output_right, (int)channels);
        dsp_convolver_process_frame(&graph->convolver, &output_left, &output_right, (int)channels);
        dsp_dynamic_bass_process_frame(&graph->dynamic_bass, &output_left, &output_right, (int)channels);
        dsp_macro_process_frame(&graph->macro, &output_left, &output_right, (int)channels);
        dsp_smart_loudness_process_frame(&graph->smart_loudness, &output_left, &output_right, (int)channels);
        dsp_dynamics_process_limiter_frame(&graph->dynamics, &output_left, &output_right, (int)channels);
        if (!isfinite(output_left)) {
            output_left = 0.0f;
            if (non_finite_output != NULL) {
                (*non_finite_output)++;
            }
        }
        if (channels == 2 && !isfinite(output_right)) {
            output_right = 0.0f;
            if (non_finite_output != NULL) {
                (*non_finite_output)++;
            }
        }
        output[offset] = output_left;
        if (channels == 2) {
            output[offset + 1] = output_right;
        }
        dsp_meter_add_frame(&graph->output_meter, output_left, output_right);
    }
    for (size_t index = 0; index < graph->peq_count; index++) {
        dsp_biquad_flush_denormals(&graph->peq[index]);
    }
    dsp_spatial_flush_denormals(&graph->spatial);
    dsp_harmonic_flush_denormals(&graph->harmonic);
    dsp_multiband_flush_denormals(&graph->multiband);
    dsp_dynamic_eq_flush_denormals(&graph->dynamic_eq);
    dsp_convolver_flush_denormals(&graph->convolver);
    dsp_macro_flush_denormals(&graph->macro);
    dsp_smart_loudness_flush_denormals(&graph->smart_loudness);
    dsp_dynamic_bass_flush_denormals(&graph->dynamic_bass);
}
