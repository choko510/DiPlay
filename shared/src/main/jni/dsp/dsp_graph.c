#include "dsp_graph.h"

#include <math.h>

int dsp_graph_prepare(
    dsp_graph *graph,
    int sample_rate,
    double gain_db,
    const double *peq_coefficients,
    size_t peq_count,
    const dsp_dynamics_config *dynamics_config) {
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
    dsp_meter_reset(&graph->input_meter);
    dsp_meter_reset(&graph->output_meter);
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
        for (size_t index = 0; index < graph->peq_count; index++) {
            output_left = dsp_biquad_process_sample(&graph->peq[index], output_left, 0);
            if (channels == 2) {
                output_right = dsp_biquad_process_sample(&graph->peq[index], output_right, 1);
            }
        }
        dsp_dynamics_process_frame(&graph->dynamics, &output_left, &output_right, (int)channels);
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
}
