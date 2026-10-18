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
    size_t harmonic_filter_coefficient_count) {
    if (graph == NULL || peq_count > DSP_BIQUAD_MAX_BANDS ||
        (peq_count > 0 && peq_coefficients == NULL) || !dsp_gain_set_db(&graph->gain, gain_db)) {
        return 0;
    }
    if (!dsp_seat_focus_prepare(&graph->seat_focus, sample_rate, channels, seat_focus_config)) {
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
    if (!dsp_cabin_prepare(
            &graph->cabin,
            sample_rate,
            cabin_config,
            cabin_static_coefficients,
            cabin_static_coefficient_count,
            cabin_dynamic_eq_config)) {
        return 0;
    }
    if (!dsp_harmonic_prepare(
            &graph->harmonic,
            sample_rate,
            channels,
            harmonic_config,
            harmonic_filter_coefficients,
            harmonic_filter_coefficient_count)) {
        return 0;
    }
    if (!dsp_spatial_prepare(
            &graph->spatial,
            sample_rate,
            spatial_config,
            advanced_ms_config,
            advanced_ms_coefficients,
            advanced_ms_coefficient_count,
            bass_coefficients,
            bass_coefficient_count,
            mono_bass_coefficients,
            mono_bass_coefficient_count)) {
        return 0;
    }
    if (!dsp_convolver_prepare(&graph->convolver, sample_rate, channels, convolver_config)) {
        return 0;
    }
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
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
    dsp_cabin_reset(&graph->cabin);
    dsp_harmonic_reset(&graph->harmonic);
    dsp_spatial_reset(&graph->spatial);
    dsp_seat_focus_reset(&graph->seat_focus);
    dsp_convolver_reset(&graph->convolver);
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
}

void dsp_graph_close(dsp_graph *graph) {
    if (graph == NULL) return;
    dsp_seat_focus_close(&graph->seat_focus);
    dsp_convolver_close(&graph->convolver);
    dsp_harmonic_close(&graph->harmonic);
}

int dsp_graph_latency_frames(const dsp_graph *graph) {
    if (graph == NULL) return -1;
    return dsp_seat_focus_latency_frames(&graph->seat_focus) +
        dsp_harmonic_latency_frames(&graph->harmonic) +
        dsp_convolver_latency_frames(&graph->convolver);
}

int dsp_graph_update_advanced_ms_monitor_mode(dsp_graph *graph, int monitor_mode) {
    return graph != NULL && dsp_spatial_update_monitor_mode(&graph->spatial, monitor_mode);
}

int dsp_graph_update_cabin_bypass(dsp_graph *graph, int bypassed) {
    return graph != NULL && dsp_cabin_update_bypass(&graph->cabin, bypassed);
}

int dsp_graph_update_harmonic_bypass(dsp_graph *graph, int bypassed) {
    return graph != NULL && dsp_harmonic_update_bypass(&graph->harmonic, bypassed);
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
        dsp_seat_focus_process_frame(&graph->seat_focus, &output_left, &output_right, (int)channels);
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
        dsp_cabin_process_frame(&graph->cabin, &output_left, &output_right, (int)channels);
        dsp_spatial_process_stereo(&graph->spatial, &output_left, &output_right, (int)channels);
        dsp_harmonic_process_frame(&graph->harmonic, &output_left, &output_right, (int)channels);
        dsp_convolver_process_frame(&graph->convolver, &output_left, &output_right, (int)channels);
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
    dsp_multiband_flush_denormals(&graph->multiband);
    dsp_dynamic_eq_flush_denormals(&graph->dynamic_eq);
    dsp_cabin_flush_denormals(&graph->cabin);
    dsp_harmonic_flush_denormals(&graph->harmonic);
    dsp_convolver_flush_denormals(&graph->convolver);
}
