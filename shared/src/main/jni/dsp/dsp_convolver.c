#include "dsp_convolver.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define DSP_CONVOLVER_FFT_FRAMES (DSP_CONVOLVER_PARTITION_FRAMES * 2)
#define DSP_CONVOLVER_BINS (DSP_CONVOLVER_FFT_FRAMES / 2 + 1)
#define DSP_CONVOLVER_MAX_IR_SAMPLE_ABS 32.0f
#define DSP_CONVOLVER_MAX_IR_ABSOLUTE_SUM 1000000.0

static int supported_sample_rate(int sample_rate) {
    return sample_rate == 44100 || sample_rate == 48000 || sample_rate == 96000;
}

static size_t input_spectrum_offset(const dsp_convolver *convolver, int channel, size_t slot) {
    return ((size_t)channel * (size_t)convolver->partition_count + slot) * DSP_CONVOLVER_BINS;
}

static size_t ir_spectrum_offset(const dsp_convolver *convolver, int channel, int partition) {
    return ((size_t)channel * (size_t)convolver->partition_count + (size_t)partition) * DSP_CONVOLVER_BINS;
}

static void accumulate_product(kiss_fft_cpx *sum, const kiss_fft_cpx *left, const kiss_fft_cpx *right) {
    sum->r += left->r * right->r - left->i * right->i;
    sum->i += left->r * right->i + left->i * right->r;
}

void dsp_convolver_close(dsp_convolver *convolver) {
    if (convolver == NULL) {
        return;
    }
    if (convolver->forward_fft != NULL) kiss_fftr_free(convolver->forward_fft);
    if (convolver->inverse_fft != NULL) kiss_fftr_free(convolver->inverse_fft);
    free(convolver->ir_spectra);
    free(convolver->input_spectra);
    free(convolver->frequency_sum);
    free(convolver->fft_input);
    free(convolver->fft_output);
    free(convolver->input_blocks);
    free(convolver->ready_output);
    free(convolver->dry_delay);
    free(convolver->overlap);
    memset(convolver, 0, sizeof(*convolver));
}

void dsp_convolver_reset(dsp_convolver *convolver) {
    if (convolver == NULL || !convolver->active) {
        return;
    }
    convolver->frame_offset = 0;
    convolver->spectrum_write_index = 0;
    const size_t sample_count = (size_t)convolver->channels * DSP_CONVOLVER_PARTITION_FRAMES;
    const size_t input_spectrum_count = (size_t)convolver->channels *
        (size_t)convolver->partition_count * DSP_CONVOLVER_BINS;
    const size_t ir_spectrum_count = (size_t)convolver->ir_channels *
        (size_t)convolver->partition_count * DSP_CONVOLVER_BINS;
    memset(convolver->input_spectra, 0, input_spectrum_count * sizeof(kiss_fft_cpx));
    memset(convolver->input_blocks, 0, sample_count * sizeof(float));
    memset(convolver->ready_output, 0, sample_count * sizeof(float));
    memset(convolver->dry_delay, 0, sample_count * sizeof(float));
    memset(convolver->overlap, 0, sample_count * sizeof(float));
    memset(convolver->frequency_sum, 0, DSP_CONVOLVER_BINS * sizeof(kiss_fft_cpx));
    memset(convolver->fft_input, 0, DSP_CONVOLVER_FFT_FRAMES * sizeof(float));
    memset(convolver->fft_output, 0, DSP_CONVOLVER_FFT_FRAMES * sizeof(float));
    if (ir_spectrum_count == 0) {
        convolver->active = 0;
    }
}

static int allocate_processing_state(dsp_convolver *convolver) {
    const size_t sample_count = (size_t)convolver->channels * DSP_CONVOLVER_PARTITION_FRAMES;
    const size_t input_spectrum_count = (size_t)convolver->channels *
        (size_t)convolver->partition_count * DSP_CONVOLVER_BINS;
    const size_t ir_spectrum_count = (size_t)convolver->ir_channels *
        (size_t)convolver->partition_count * DSP_CONVOLVER_BINS;
    convolver->ir_spectra = (kiss_fft_cpx *)calloc(ir_spectrum_count, sizeof(kiss_fft_cpx));
    convolver->input_spectra = (kiss_fft_cpx *)calloc(input_spectrum_count, sizeof(kiss_fft_cpx));
    convolver->frequency_sum = (kiss_fft_cpx *)calloc(DSP_CONVOLVER_BINS, sizeof(kiss_fft_cpx));
    convolver->fft_input = (float *)calloc(DSP_CONVOLVER_FFT_FRAMES, sizeof(float));
    convolver->fft_output = (float *)calloc(DSP_CONVOLVER_FFT_FRAMES, sizeof(float));
    convolver->input_blocks = (float *)calloc(sample_count, sizeof(float));
    convolver->ready_output = (float *)calloc(sample_count, sizeof(float));
    convolver->dry_delay = (float *)calloc(sample_count, sizeof(float));
    convolver->overlap = (float *)calloc(sample_count, sizeof(float));
    convolver->forward_fft = kiss_fftr_alloc(DSP_CONVOLVER_FFT_FRAMES, 0, NULL, NULL);
    convolver->inverse_fft = kiss_fftr_alloc(DSP_CONVOLVER_FFT_FRAMES, 1, NULL, NULL);
    return convolver->ir_spectra != NULL && convolver->input_spectra != NULL &&
        convolver->frequency_sum != NULL && convolver->fft_input != NULL && convolver->fft_output != NULL &&
        convolver->input_blocks != NULL && convolver->ready_output != NULL && convolver->dry_delay != NULL &&
        convolver->overlap != NULL && convolver->forward_fft != NULL && convolver->inverse_fft != NULL;
}

int dsp_convolver_prepare(
    dsp_convolver *convolver,
    int sample_rate,
    int channels,
    const dsp_convolver_config *config) {
    if (convolver == NULL || config == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        channels < 1 || channels > 2 || (config->enabled != 0 && config->enabled != 1) ||
        !isfinite(config->wet) || config->wet < 0.0f || config->wet > 1.0f) {
        return 0;
    }
    memset(convolver, 0, sizeof(*convolver));
    if (!config->enabled) {
        return config->frames == 0 && config->sample_count == 0 && config->samples == NULL;
    }
    if (config->frames == 0) {
        return config->sample_count == 0 && config->samples == NULL;
    }
    if (config->frames < 0 || config->frames > DSP_CONVOLVER_MAX_IR_FRAMES ||
        config->channels < 1 || config->channels > 2 || config->samples == NULL ||
        config->sample_count != (size_t)config->frames * (size_t)config->channels ||
        !supported_sample_rate(config->sample_rate)) {
        return 0;
    }
    const size_t sample_count = (size_t)config->frames * (size_t)config->channels;
    double absolute_sum = 0.0;
    for (size_t index = 0; index < sample_count; index++) {
        const float sample = config->samples[index];
        if (!isfinite(sample) || fabsf(sample) > DSP_CONVOLVER_MAX_IR_SAMPLE_ABS) return 0;
        absolute_sum += fabs((double)sample);
        if (absolute_sum > DSP_CONVOLVER_MAX_IR_ABSOLUTE_SUM) return 0;
    }
    if (config->wet == 0.0f || config->sample_rate != sample_rate) {
        return 1;
    }

    convolver->channels = channels;
    convolver->ir_channels = config->channels;
    convolver->partition_count = (config->frames + DSP_CONVOLVER_PARTITION_FRAMES - 1) /
        DSP_CONVOLVER_PARTITION_FRAMES;
    convolver->wet = config->wet;
    convolver->latency_frames = DSP_CONVOLVER_PARTITION_FRAMES;
    if (!allocate_processing_state(convolver)) {
        dsp_convolver_close(convolver);
        return 0;
    }

    for (int channel = 0; channel < convolver->ir_channels; channel++) {
        for (int partition = 0; partition < convolver->partition_count; partition++) {
            memset(convolver->fft_input, 0, DSP_CONVOLVER_FFT_FRAMES * sizeof(float));
            const int first_frame = partition * DSP_CONVOLVER_PARTITION_FRAMES;
            for (int frame = 0; frame < DSP_CONVOLVER_PARTITION_FRAMES; frame++) {
                const int impulse_frame = first_frame + frame;
                if (impulse_frame < config->frames) {
                    convolver->fft_input[frame] = config->samples[
                        (size_t)impulse_frame * (size_t)config->channels + (size_t)channel];
                }
            }
            kiss_fft_cpx *spectrum = convolver->ir_spectra + ir_spectrum_offset(convolver, channel, partition);
            kiss_fftr(convolver->forward_fft, convolver->fft_input, spectrum);
            for (size_t bin = 0; bin < DSP_CONVOLVER_BINS; bin++) {
                if (!isfinite(spectrum[bin].r) || !isfinite(spectrum[bin].i)) {
                    dsp_convolver_close(convolver);
                    return 0;
                }
            }
        }
    }
    convolver->active = 1;
    return 1;
}

static void calculate_output_block(dsp_convolver *convolver) {
    const size_t input_slot = convolver->spectrum_write_index;
    for (int channel = 0; channel < convolver->channels; channel++) {
        memset(convolver->fft_input, 0, DSP_CONVOLVER_FFT_FRAMES * sizeof(float));
        memcpy(
            convolver->fft_input,
            convolver->input_blocks + (size_t)channel * DSP_CONVOLVER_PARTITION_FRAMES,
            DSP_CONVOLVER_PARTITION_FRAMES * sizeof(float));
        kiss_fftr(
            convolver->forward_fft,
            convolver->fft_input,
            convolver->input_spectra + input_spectrum_offset(convolver, channel, input_slot));
    }

    for (int channel = 0; channel < convolver->channels; channel++) {
        memset(convolver->frequency_sum, 0, DSP_CONVOLVER_BINS * sizeof(kiss_fft_cpx));
        const int impulse_channel = convolver->ir_channels == 1 || convolver->channels == 1 ? 0 : channel;
        const kiss_fft_cpx *impulse_start = convolver->ir_spectra +
            ir_spectrum_offset(convolver, impulse_channel, 0);
        for (int partition = 0; partition < convolver->partition_count; partition++) {
            const size_t history_slot = (input_slot + (size_t)convolver->partition_count -
                (size_t)partition) % (size_t)convolver->partition_count;
            const kiss_fft_cpx *input_spectrum = convolver->input_spectra +
                input_spectrum_offset(convolver, channel, history_slot);
            const kiss_fft_cpx *impulse_spectrum = impulse_start + (size_t)partition * DSP_CONVOLVER_BINS;
            for (size_t bin = 0; bin < DSP_CONVOLVER_BINS; bin++) {
                accumulate_product(&convolver->frequency_sum[bin], &input_spectrum[bin], &impulse_spectrum[bin]);
            }
        }
        kiss_fftri(convolver->inverse_fft, convolver->frequency_sum, convolver->fft_output);
        float *ready = convolver->ready_output + (size_t)channel * DSP_CONVOLVER_PARTITION_FRAMES;
        float *overlap = convolver->overlap + (size_t)channel * DSP_CONVOLVER_PARTITION_FRAMES;
        for (size_t frame = 0; frame < DSP_CONVOLVER_PARTITION_FRAMES; frame++) {
            ready[frame] = convolver->fft_output[frame] / DSP_CONVOLVER_FFT_FRAMES + overlap[frame];
            overlap[frame] = convolver->fft_output[frame + DSP_CONVOLVER_PARTITION_FRAMES] /
                DSP_CONVOLVER_FFT_FRAMES;
        }
    }
    convolver->spectrum_write_index = (input_slot + 1) % (size_t)convolver->partition_count;
}

void dsp_convolver_process_frame(dsp_convolver *convolver, float *left, float *right, int channels) {
    if (convolver == NULL || !convolver->active || left == NULL || right == NULL ||
        channels < 1 || channels > 2) {
        return;
    }
    const int frame = convolver->frame_offset;
    const float input_left = *left;
    const float input_right = channels == 2 ? *right : 0.0f;
    float *input_left_block = convolver->input_blocks;
    float *ready_left_block = convolver->ready_output;
    float *dry_left_block = convolver->dry_delay;
    const float delayed_dry_left = dry_left_block[frame];
    const float wet_left = ready_left_block[frame];
    dry_left_block[frame] = input_left;
    input_left_block[frame] = input_left;
    *left = (1.0f - convolver->wet) * delayed_dry_left + convolver->wet * wet_left;

    if (channels == 2) {
        float *input_right_block = input_left_block + DSP_CONVOLVER_PARTITION_FRAMES;
        float *ready_right_block = ready_left_block + DSP_CONVOLVER_PARTITION_FRAMES;
        float *dry_right_block = dry_left_block + DSP_CONVOLVER_PARTITION_FRAMES;
        const float delayed_dry_right = dry_right_block[frame];
        const float wet_right = ready_right_block[frame];
        dry_right_block[frame] = input_right;
        input_right_block[frame] = input_right;
        *right = (1.0f - convolver->wet) * delayed_dry_right + convolver->wet * wet_right;
    }

    convolver->frame_offset++;
    if (convolver->frame_offset >= DSP_CONVOLVER_PARTITION_FRAMES) {
        calculate_output_block(convolver);
        convolver->frame_offset = 0;
    }
}

void dsp_convolver_flush_denormals(dsp_convolver *convolver) {
    if (convolver == NULL || !convolver->active) return;
    const size_t samples = (size_t)convolver->channels * DSP_CONVOLVER_PARTITION_FRAMES;
    float *buffers[] = {
        convolver->input_blocks,
        convolver->ready_output,
        convolver->dry_delay,
        convolver->overlap,
    };
    for (size_t buffer = 0; buffer < sizeof(buffers) / sizeof(buffers[0]); buffer++) {
        for (size_t index = 0; index < samples; index++) {
            if (fabsf(buffers[buffer][index]) < 1e-20f) buffers[buffer][index] = 0.0f;
        }
    }
}

int dsp_convolver_latency_frames(const dsp_convolver *convolver) {
    return convolver != NULL && convolver->active ? convolver->latency_frames : 0;
}
