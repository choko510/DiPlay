#ifndef XCERTPLAY_DSP_DYNAMIC_BASS_H
#define XCERTPLAY_DSP_DYNAMIC_BASS_H

typedef struct {
    int enabled;
    double strength;
    double max_boost_db;
    double detector_cutoff_hz;
    double silence_gate_dbfs;
    double low_threshold_dbfs;
    double high_threshold_dbfs;
    double attack_ms;
    double release_ms;
} dsp_dynamic_bass_config;

int dsp_dynamic_bass_config_is_valid(const dsp_dynamic_bass_config *config);

typedef struct {
    int sample_rate;
    int channels;
    int enabled;
    int target_enabled;
    double strength;
    double max_boost_db;
    double detector_cutoff_hz;
    double silence_gate_dbfs;
    double low_threshold_dbfs;
    double high_threshold_dbfs;
    double silence_gate_linear;
    double low_threshold_linear;
    double high_threshold_linear;
    double max_gain;
    double attack_alpha;
    double release_alpha;
    double detector_alpha;
    double bass_alpha;
    double current_gain;
    double envelope;
    float detector_state[2];
    float bass_state[2];
} dsp_dynamic_bass;

int dsp_dynamic_bass_prepare(
    dsp_dynamic_bass *bass,
    int sample_rate,
    int channels,
    const dsp_dynamic_bass_config *config);
int dsp_dynamic_bass_apply_hot(dsp_dynamic_bass *bass, const dsp_dynamic_bass_config *config);
void dsp_dynamic_bass_process_frame(dsp_dynamic_bass *bass, float *left, float *right, int channels);
void dsp_dynamic_bass_reset(dsp_dynamic_bass *bass);
void dsp_dynamic_bass_flush_denormals(dsp_dynamic_bass *bass);

#endif
