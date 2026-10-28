#include "dsp_engine.h"
#include "dsp_graph.h"

#include <jni.h>
#include <math.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdint.h>

#define DSP_HANDLE_SLOT_COUNT 64u
#define DSP_RESERVED_POINTER ((dsp_engine *)(uintptr_t)1)
#define DSP_MAX_HANDLE_GENERATION UINT32_C(0x7fffffff)

_Static_assert(ATOMIC_POINTER_LOCK_FREE == 2, "DSP handles require lock-free pointer atomics");
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "DSP handles require lock-free 32-bit atomics");

typedef struct {
    _Atomic(dsp_engine *) engine;
    _Atomic uint32_t generation;
    _Atomic uint32_t active_calls;
} dsp_handle_slot;

typedef struct {
    dsp_handle_slot *slot;
    dsp_engine *engine;
} dsp_engine_lease;

static dsp_handle_slot dsp_handle_slots[DSP_HANDLE_SLOT_COUNT] = {0};

static uint32_t next_generation(uint32_t current) {
    return current >= DSP_MAX_HANDLE_GENERATION ? 1 : current + 1;
}

static jlong register_engine(dsp_engine *engine) {
    if (engine == NULL) {
        return 0;
    }
    for (uint32_t index = 0; index < DSP_HANDLE_SLOT_COUNT; index++) {
        dsp_handle_slot *slot = &dsp_handle_slots[index];
        dsp_engine *expected = NULL;
        if (!atomic_compare_exchange_strong_explicit(
                &slot->engine,
                &expected,
                DSP_RESERVED_POINTER,
                memory_order_acq_rel,
                memory_order_relaxed)) {
            continue;
        }
        const uint32_t generation = next_generation(
            atomic_load_explicit(&slot->generation, memory_order_relaxed));
        atomic_store_explicit(&slot->generation, generation, memory_order_release);
        atomic_store_explicit(&slot->engine, engine, memory_order_release);
        const uint64_t handle = ((uint64_t)generation << 32) | ((uint64_t)index + 1);
        return (jlong)handle;
    }
    dsp_engine_destroy(engine);
    return 0;
}

static int acquire_engine(jlong handle, dsp_engine_lease *lease) {
    if (handle <= 0 || lease == NULL) {
        return 0;
    }
    const uint64_t token = (uint64_t)handle;
    const uint32_t index_token = (uint32_t)token;
    const uint32_t generation = (uint32_t)(token >> 32);
    if (index_token == 0 || index_token > DSP_HANDLE_SLOT_COUNT || generation == 0) {
        return 0;
    }

    dsp_handle_slot *slot = &dsp_handle_slots[index_token - 1];
    if (atomic_load_explicit(&slot->generation, memory_order_acquire) != generation) {
        return 0;
    }
    dsp_engine *engine = atomic_load_explicit(&slot->engine, memory_order_acquire);
    if (engine == NULL || engine == DSP_RESERVED_POINTER) {
        return 0;
    }
    atomic_fetch_add_explicit(&slot->active_calls, 1, memory_order_acquire);
    if (atomic_load_explicit(&slot->generation, memory_order_acquire) != generation ||
        atomic_load_explicit(&slot->engine, memory_order_acquire) != engine) {
        atomic_fetch_sub_explicit(&slot->active_calls, 1, memory_order_release);
        return 0;
    }
    lease->slot = slot;
    lease->engine = engine;
    return 1;
}

static void release_engine(dsp_engine_lease *lease) {
    if (lease != NULL && lease->slot != NULL) {
        atomic_fetch_sub_explicit(&lease->slot->active_calls, 1, memory_order_release);
        lease->slot = NULL;
        lease->engine = NULL;
    }
}

static void destroy_engine(jlong handle) {
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) {
        return;
    }

    dsp_engine *expected = lease.engine;
    if (!atomic_compare_exchange_strong_explicit(
            &lease.slot->engine,
            &expected,
            DSP_RESERVED_POINTER,
            memory_order_acq_rel,
            memory_order_relaxed)) {
        release_engine(&lease);
        return;
    }
    const uint32_t generation = atomic_load_explicit(&lease.slot->generation, memory_order_relaxed);
    atomic_store_explicit(&lease.slot->generation, next_generation(generation), memory_order_release);
    dsp_handle_slot *slot = lease.slot;
    dsp_engine *engine = lease.engine;
    release_engine(&lease);
    while (atomic_load_explicit(&slot->active_calls, memory_order_acquire) != 0) {
        sched_yield();
    }
    dsp_engine_destroy(engine);
    atomic_store_explicit(&slot->engine, NULL, memory_order_release);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeCreate(
    JNIEnv *env,
    jobject receiver,
    jint sample_rate,
    jint channels,
    jint max_frames,
    jdouble gain_db,
    jdoubleArray peq_coefficients,
    jdoubleArray dynamics_values,
    jdoubleArray multiband_values,
    jdoubleArray dynamic_eq_values,
    jdoubleArray bass_coefficients,
    jdoubleArray mono_bass_coefficients,
    jdoubleArray spatial_values,
    jdoubleArray convolver_values,
    jfloatArray convolver_samples,
    jdoubleArray harmonic_values,
    jdoubleArray macro_values,
    jdoubleArray smart_loudness_values,
    jdoubleArray dynamic_bass_values) {
    (void)env;
    (void)receiver;
    if (peq_coefficients == NULL || dynamics_values == NULL || multiband_values == NULL ||
        dynamic_eq_values == NULL || bass_coefficients == NULL ||
        mono_bass_coefficients == NULL || spatial_values == NULL || convolver_values == NULL ||
        convolver_samples == NULL || harmonic_values == NULL || macro_values == NULL ||
        smart_loudness_values == NULL || dynamic_bass_values == NULL) {
        return 0;
    }
    const jsize coefficient_count = (*env)->GetArrayLength(env, peq_coefficients);
    const jsize dynamics_count = (*env)->GetArrayLength(env, dynamics_values);
    const jsize multiband_count = (*env)->GetArrayLength(env, multiband_values);
    const jsize dynamic_eq_count = (*env)->GetArrayLength(env, dynamic_eq_values);
    const jsize bass_coefficient_count = (*env)->GetArrayLength(env, bass_coefficients);
    const jsize mono_bass_coefficient_count = (*env)->GetArrayLength(env, mono_bass_coefficients);
    const jsize spatial_count = (*env)->GetArrayLength(env, spatial_values);
    const jsize convolver_value_count = (*env)->GetArrayLength(env, convolver_values);
    const jsize convolver_sample_count = (*env)->GetArrayLength(env, convolver_samples);
    const jsize harmonic_count = (*env)->GetArrayLength(env, harmonic_values);
    const jsize macro_count = (*env)->GetArrayLength(env, macro_values);
    const jsize smart_loudness_count = (*env)->GetArrayLength(env, smart_loudness_values);
    const jsize dynamic_bass_count = (*env)->GetArrayLength(env, dynamic_bass_values);
    if (coefficient_count < 0 || coefficient_count >
            DSP_GRAPH_EQ_FILTER_CAPACITY * DSP_BIQUAD_COEFFICIENT_COUNT ||
        coefficient_count % DSP_BIQUAD_COEFFICIENT_COUNT != 0 || dynamics_count != 10 ||
        multiband_count != DSP_MULTIBAND_VALUES_COUNT ||
        dynamic_eq_count != DSP_DYNAMIC_EQ_VALUES_COUNT ||
        (bass_coefficient_count != 0 && bass_coefficient_count != DSP_BIQUAD_COEFFICIENT_COUNT) ||
        (mono_bass_coefficient_count != 0 && mono_bass_coefficient_count != DSP_BIQUAD_COEFFICIENT_COUNT) ||
        spatial_count != DSP_SPATIAL_VALUES_COUNT || convolver_value_count != 5 ||
        harmonic_count != DSP_HARMONIC_VALUES_COUNT || macro_count != 5 ||
        smart_loudness_count != 3 || dynamic_bass_count != 9 || convolver_sample_count < 0 ||
        convolver_sample_count > DSP_CONVOLVER_MAX_IR_FRAMES * 2) {
        return 0;
    }
    double coefficients[DSP_GRAPH_EQ_FILTER_CAPACITY * DSP_BIQUAD_COEFFICIENT_COUNT];
    double multiband_values_data[DSP_MULTIBAND_VALUES_COUNT];
    double dynamic_eq_values_data[DSP_DYNAMIC_EQ_VALUES_COUNT];
    double bass_coefficients_values[DSP_BIQUAD_COEFFICIENT_COUNT];
    double mono_bass_coefficients_values[DSP_BIQUAD_COEFFICIENT_COUNT];
    double dynamic_values[10];
    double spatial_values_data[DSP_SPATIAL_VALUES_COUNT];
    double convolver_values_data[5];
    double harmonic_values_data[DSP_HARMONIC_VALUES_COUNT];
    double macro_values_data[5];
    double smart_loudness_values_data[3];
    double dynamic_bass_values_data[9];
    if (coefficient_count > 0) {
        (*env)->GetDoubleArrayRegion(env, peq_coefficients, 0, coefficient_count, coefficients);
        if ((*env)->ExceptionCheck(env)) {
            return 0;
        }
    }
    (*env)->GetDoubleArrayRegion(env, dynamics_values, 0, dynamics_count, dynamic_values);
    if ((*env)->ExceptionCheck(env) || (dynamic_values[0] != 0.0 && dynamic_values[0] != 1.0) ||
        (dynamic_values[7] != 0.0 && dynamic_values[7] != 1.0)) {
        return 0;
    }
    (*env)->GetDoubleArrayRegion(env, multiband_values, 0, multiband_count, multiband_values_data);
    if ((*env)->ExceptionCheck(env) ||
        (multiband_values_data[0] != 0.0 && multiband_values_data[0] != 1.0) ||
        !isfinite(multiband_values_data[1]) || !isfinite(multiband_values_data[2])) {
        return 0;
    }
    (*env)->GetDoubleArrayRegion(env, macro_values, 0, macro_count, macro_values_data);
    if ((*env)->ExceptionCheck(env) ||
        (macro_values_data[0] != 0.0 && macro_values_data[0] != 1.0) ||
        !isfinite(macro_values_data[1]) || macro_values_data[1] < -2.0 || macro_values_data[1] > 4.0 ||
        !isfinite(macro_values_data[2]) || macro_values_data[2] < -2.0 || macro_values_data[2] > 2.5 ||
        !isfinite(macro_values_data[3]) || macro_values_data[3] < -2.0 || macro_values_data[3] > 2.0 ||
        !isfinite(macro_values_data[4]) || macro_values_data[4] < 0.75 || macro_values_data[4] > 1.25) {
        return 0;
    }
    (*env)->GetDoubleArrayRegion(env, smart_loudness_values, 0, smart_loudness_count, smart_loudness_values_data);
    if ((*env)->ExceptionCheck(env) ||
        (smart_loudness_values_data[0] != 0.0 && smart_loudness_values_data[0] != 1.0) ||
        !isfinite(smart_loudness_values_data[1]) || smart_loudness_values_data[1] < 0.0 ||
        smart_loudness_values_data[1] > 3.0 || !isfinite(smart_loudness_values_data[2]) ||
        smart_loudness_values_data[2] < 0.0 || smart_loudness_values_data[2] > 1.5) {
        return 0;
    }
    (*env)->GetDoubleArrayRegion(env, dynamic_bass_values, 0, dynamic_bass_count, dynamic_bass_values_data);
    if ((*env)->ExceptionCheck(env) ||
        (dynamic_bass_values_data[0] != 0.0 && dynamic_bass_values_data[0] != 1.0) ||
        !isfinite(dynamic_bass_values_data[1]) || dynamic_bass_values_data[1] < 0.0 ||
        dynamic_bass_values_data[1] > 1.0 || !isfinite(dynamic_bass_values_data[2]) ||
        dynamic_bass_values_data[2] < 0.0 || dynamic_bass_values_data[2] > 3.0 ||
        !isfinite(dynamic_bass_values_data[3]) || dynamic_bass_values_data[3] < 80.0 ||
        dynamic_bass_values_data[3] > 160.0 || !isfinite(dynamic_bass_values_data[4]) ||
        dynamic_bass_values_data[4] < -90.0 || dynamic_bass_values_data[4] > -30.0 ||
        !isfinite(dynamic_bass_values_data[5]) || dynamic_bass_values_data[5] < -60.0 ||
        dynamic_bass_values_data[5] > 0.0 || !isfinite(dynamic_bass_values_data[6]) ||
        dynamic_bass_values_data[6] < -60.0 || dynamic_bass_values_data[6] > 0.0 ||
        dynamic_bass_values_data[5] >= dynamic_bass_values_data[6] ||
        !isfinite(dynamic_bass_values_data[7]) || dynamic_bass_values_data[7] < 10.0 ||
        dynamic_bass_values_data[7] > 200.0 || !isfinite(dynamic_bass_values_data[8]) ||
        dynamic_bass_values_data[8] < 50.0 || dynamic_bass_values_data[8] > 1000.0) {
        return 0;
    }
    (*env)->GetDoubleArrayRegion(env, harmonic_values, 0, harmonic_count, harmonic_values_data);
    if ((*env)->ExceptionCheck(env) ||
        (harmonic_values_data[0] != 0.0 && harmonic_values_data[0] != 1.0) ||
        (harmonic_values_data[1] != 0.0 && harmonic_values_data[1] != 1.0 && harmonic_values_data[1] != 2.0) ||
        (harmonic_values_data[2] != 9.0 && harmonic_values_data[2] != 17.0 && harmonic_values_data[2] != 33.0) ||
        (harmonic_values_data[3] != 0.0 && harmonic_values_data[3] != 1.0) ||
        !isfinite(harmonic_values_data[4]) || harmonic_values_data[4] < 0.0 || harmonic_values_data[4] > 0.5 ||
        !isfinite(harmonic_values_data[5]) || harmonic_values_data[5] < 1000.0 || harmonic_values_data[5] > 18000.0 ||
        (harmonic_values_data[6] != 0.0 && harmonic_values_data[6] != 1.0) ||
        !isfinite(harmonic_values_data[7]) || harmonic_values_data[7] < 0.0 || harmonic_values_data[7] > 0.5 ||
        !isfinite(harmonic_values_data[8]) || harmonic_values_data[8] < 60.0 || harmonic_values_data[8] > 250.0 ||
        (harmonic_values_data[9] != 0.0 && harmonic_values_data[9] != 1.0)) {
        return 0;
    }
    for (size_t band = 0; band < DSP_MULTIBAND_COUNT; band++) {
        const size_t start = 3 + band * 7;
        if ((multiband_values_data[start] != 0.0 && multiband_values_data[start] != 1.0) ||
            !isfinite(multiband_values_data[start + 1]) || !isfinite(multiband_values_data[start + 2]) ||
            !isfinite(multiband_values_data[start + 3]) || !isfinite(multiband_values_data[start + 4]) ||
            !isfinite(multiband_values_data[start + 5]) || !isfinite(multiband_values_data[start + 6])) {
            return 0;
        }
    }
    (*env)->GetDoubleArrayRegion(env, dynamic_eq_values, 0, dynamic_eq_count, dynamic_eq_values_data);
    if ((*env)->ExceptionCheck(env) ||
        (dynamic_eq_values_data[0] != 0.0 && dynamic_eq_values_data[0] != 1.0)) {
        return 0;
    }
    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        const size_t start = 1 + band * 10;
        if ((dynamic_eq_values_data[start] != 0.0 && dynamic_eq_values_data[start] != 1.0) ||
            (dynamic_eq_values_data[start + 1] != DSP_DYNAMIC_EQ_CUT &&
                dynamic_eq_values_data[start + 1] != DSP_DYNAMIC_EQ_BOOST) ||
            !isfinite(dynamic_eq_values_data[start + 2]) || !isfinite(dynamic_eq_values_data[start + 3]) ||
            !isfinite(dynamic_eq_values_data[start + 4]) || !isfinite(dynamic_eq_values_data[start + 5]) ||
            !isfinite(dynamic_eq_values_data[start + 6]) || !isfinite(dynamic_eq_values_data[start + 7]) ||
            !isfinite(dynamic_eq_values_data[start + 8]) || !isfinite(dynamic_eq_values_data[start + 9])) {
            return 0;
        }
    }
    if (bass_coefficient_count > 0) {
        (*env)->GetDoubleArrayRegion(env, bass_coefficients, 0, bass_coefficient_count, bass_coefficients_values);
        if ((*env)->ExceptionCheck(env)) return 0;
    }
    if (mono_bass_coefficient_count > 0) {
        (*env)->GetDoubleArrayRegion(env, mono_bass_coefficients, 0, mono_bass_coefficient_count,
            mono_bass_coefficients_values);
        if ((*env)->ExceptionCheck(env)) return 0;
    }
    (*env)->GetDoubleArrayRegion(env, spatial_values, 0, spatial_count, spatial_values_data);
    if ((*env)->ExceptionCheck(env) || (spatial_values_data[1] != 0.0 && spatial_values_data[1] != 1.0) ||
        !isfinite(spatial_values_data[2]) || spatial_values_data[2] < 1.0 || spatial_values_data[2] > 1000.0 ||
        (double)(int)spatial_values_data[2] != spatial_values_data[2] ||
        (spatial_values_data[3] != 0.0 && spatial_values_data[3] != 1.0) ||
        !isfinite(spatial_values_data[4]) || spatial_values_data[4] < DSP_SPATIAL_NEAR_CENTER ||
        spatial_values_data[4] > DSP_SPATIAL_NEAR_RIGHT ||
        (double)(int)spatial_values_data[4] != spatial_values_data[4] ||
        !isfinite(spatial_values_data[5]) || spatial_values_data[5] < 0.0 || spatial_values_data[5] > 171.5 ||
        !isfinite(spatial_values_data[6]) || spatial_values_data[6] < -6.0 || spatial_values_data[6] > 6.0 ||
        !isfinite(spatial_values_data[7]) || spatial_values_data[7] < -6.0 || spatial_values_data[7] > 6.0 ||
        (spatial_values_data[8] != 0.0 && spatial_values_data[8] != 1.0) ||
        (spatial_values_data[9] != 0.0 && spatial_values_data[9] != 1.0) ||
        (spatial_values_data[10] != 0.0 && spatial_values_data[10] != 1.0) ||
        !isfinite(spatial_values_data[11]) || spatial_values_data[11] < 60.0 ||
        spatial_values_data[11] > 1000.0 || !isfinite(spatial_values_data[12]) ||
        spatial_values_data[12] < -6.0 || spatial_values_data[12] > 6.0 ||
        !isfinite(spatial_values_data[13]) || spatial_values_data[13] < -6.0 ||
        spatial_values_data[13] > 6.0 || !isfinite(spatial_values_data[14]) ||
        spatial_values_data[14] < -6.0 || spatial_values_data[14] > 6.0 ||
        !isfinite(spatial_values_data[15]) || spatial_values_data[15] < -6.0 ||
        spatial_values_data[15] > 6.0 || !isfinite(spatial_values_data[16]) ||
        spatial_values_data[16] < -6.0 || spatial_values_data[16] > 6.0 ||
        !isfinite(spatial_values_data[17]) || spatial_values_data[17] < 0.0 ||
        spatial_values_data[17] > 2.0 || !isfinite(spatial_values_data[18]) ||
        spatial_values_data[18] < 0.0 || spatial_values_data[18] > 2.0 ||
        (spatial_values_data[19] != 0.0 && spatial_values_data[19] != 1.0) ||
        !isfinite(spatial_values_data[20]) || spatial_values_data[20] < -60.0 ||
        spatial_values_data[20] > 0.0 || !isfinite(spatial_values_data[21]) ||
        spatial_values_data[21] < 1.0 || spatial_values_data[21] > 8.0 ||
        !isfinite(spatial_values_data[22]) || spatial_values_data[22] < 0.1 ||
        spatial_values_data[22] > 2000.0 || !isfinite(spatial_values_data[23]) ||
        spatial_values_data[23] < 0.1 || spatial_values_data[23] > 2000.0 ||
        !isfinite(spatial_values_data[24]) || spatial_values_data[24] < 0.0 ||
        spatial_values_data[24] > 24.0) {
        return 0;
    }
    (*env)->GetDoubleArrayRegion(env, convolver_values, 0, convolver_value_count, convolver_values_data);
    if ((*env)->ExceptionCheck(env) || (convolver_values_data[0] != 0.0 && convolver_values_data[0] != 1.0) ||
        !isfinite(convolver_values_data[1]) || !isfinite(convolver_values_data[2]) ||
        !isfinite(convolver_values_data[3]) || !isfinite(convolver_values_data[4]) ||
        convolver_values_data[1] < 0.0 || convolver_values_data[1] > 192000.0 ||
        convolver_values_data[2] < 0.0 || convolver_values_data[2] > 2.0 ||
        convolver_values_data[3] < 0.0 || convolver_values_data[3] > DSP_CONVOLVER_MAX_IR_FRAMES ||
        convolver_values_data[4] < 0.0 || convolver_values_data[4] > 1.0) {
        return 0;
    }
    const int convolver_enabled = (int)convolver_values_data[0];
    const int convolver_sample_rate = (int)convolver_values_data[1];
    const int convolver_channels = (int)convolver_values_data[2];
    const int convolver_frames = (int)convolver_values_data[3];
    if ((double)convolver_sample_rate != convolver_values_data[1] ||
        (double)convolver_channels != convolver_values_data[2] ||
        (double)convolver_frames != convolver_values_data[3] ||
        (convolver_enabled == 0 && convolver_sample_count != 0) ||
        (convolver_enabled == 1 && (convolver_frames < 1 || convolver_frames > DSP_CONVOLVER_MAX_IR_FRAMES ||
            convolver_channels < 1 || convolver_channels > 2 ||
            (jlong)convolver_frames * convolver_channels != convolver_sample_count))) {
        return 0;
    }
    const dsp_dynamics_config dynamics_config = {
        .compressor_enabled = (int)dynamic_values[0],
        .compressor_threshold_db = dynamic_values[1],
        .compressor_ratio = dynamic_values[2],
        .compressor_attack_ms = dynamic_values[3],
        .compressor_release_ms = dynamic_values[4],
        .compressor_knee_db = dynamic_values[5],
        .compressor_makeup_db = dynamic_values[6],
        .limiter_enabled = (int)dynamic_values[7],
        .limiter_threshold_db = dynamic_values[8],
        .limiter_release_ms = dynamic_values[9],
    };
    const dsp_spatial_config spatial_config = {
        .width = spatial_values_data[0],
        .mono_bass_enabled = (int)spatial_values_data[1],
        .mono_bass_cutoff_hz = (int)spatial_values_data[2],
        .seat_enabled = (int)spatial_values_data[3],
        .seat_near_speaker = (int)spatial_values_data[4],
        .seat_distance_difference_cm = spatial_values_data[5],
        .seat_left_trim_db = spatial_values_data[6],
        .seat_right_trim_db = spatial_values_data[7],
        .seat_invert_left = (int)spatial_values_data[8],
        .seat_invert_right = (int)spatial_values_data[9],
        .advanced_ms = {
            .enabled = (int)spatial_values_data[10],
            .crossover_hz = spatial_values_data[11],
            .center_focus_db = spatial_values_data[12],
            .mid_low_gain_db = spatial_values_data[13],
            .mid_high_gain_db = spatial_values_data[14],
            .side_low_gain_db = spatial_values_data[15],
            .side_high_gain_db = spatial_values_data[16],
            .low_band_width = spatial_values_data[17],
            .high_band_width = spatial_values_data[18],
            .side_compressor_enabled = (int)spatial_values_data[19],
            .side_compressor_threshold_db = spatial_values_data[20],
            .side_compressor_ratio = spatial_values_data[21],
            .side_compressor_attack_ms = spatial_values_data[22],
            .side_compressor_release_ms = spatial_values_data[23],
            .side_compressor_knee_db = spatial_values_data[24],
        },
    };
    dsp_multiband_config multiband_config = {
        .enabled = (int)multiband_values_data[0],
        .low_mid_crossover_hz = multiband_values_data[1],
        .mid_high_crossover_hz = multiband_values_data[2],
    };
    for (size_t band = 0; band < DSP_MULTIBAND_COUNT; band++) {
        const size_t start = 3 + band * 7;
        multiband_config.bands[band] = (dsp_multiband_compressor_config) {
            .enabled = (int)multiband_values_data[start],
            .threshold_db = multiband_values_data[start + 1],
            .ratio = multiband_values_data[start + 2],
            .attack_ms = multiband_values_data[start + 3],
            .release_ms = multiband_values_data[start + 4],
            .knee_db = multiband_values_data[start + 5],
            .makeup_db = multiband_values_data[start + 6],
        };
    }
    dsp_dynamic_eq_config dynamic_eq_config = {
        .enabled = (int)dynamic_eq_values_data[0],
    };
    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        const size_t start = 1 + band * 10;
        dynamic_eq_config.bands[band] = (dsp_dynamic_eq_band_config) {
            .enabled = (int)dynamic_eq_values_data[start],
            .mode = (int)dynamic_eq_values_data[start + 1],
            .frequency_hz = dynamic_eq_values_data[start + 2],
            .q = dynamic_eq_values_data[start + 3],
            .threshold_db = dynamic_eq_values_data[start + 4],
            .ratio = dynamic_eq_values_data[start + 5],
            .attack_ms = dynamic_eq_values_data[start + 6],
            .release_ms = dynamic_eq_values_data[start + 7],
            .max_boost_db = dynamic_eq_values_data[start + 8],
            .max_cut_db = dynamic_eq_values_data[start + 9],
        };
    }
    jfloat *impulse_samples = NULL;
    if (convolver_sample_count > 0) {
        impulse_samples = (*env)->GetFloatArrayElements(env, convolver_samples, NULL);
        if (impulse_samples == NULL) return 0;
    }
    const dsp_convolver_config convolver_config = {
        .enabled = convolver_enabled,
        .sample_rate = convolver_sample_rate,
        .channels = convolver_channels,
        .frames = convolver_frames,
        .sample_count = (size_t)convolver_sample_count,
        .wet = (float)convolver_values_data[4],
        .samples = impulse_samples,
    };
    const dsp_harmonic_config harmonic_config = {
        .enabled = (int)harmonic_values_data[0],
        .mode = (int)harmonic_values_data[1],
        .taps = (int)harmonic_values_data[2],
        .exciter_enabled = (int)harmonic_values_data[3],
        .exciter_amount = harmonic_values_data[4],
        .exciter_cutoff_hz = harmonic_values_data[5],
        .virtual_bass_enabled = (int)harmonic_values_data[6],
        .virtual_bass_amount = harmonic_values_data[7],
        .virtual_bass_cutoff_hz = harmonic_values_data[8],
        .level_match_enabled = (int)harmonic_values_data[9],
    };
    const dsp_macro_config macro_config = {
        .enabled = (int)macro_values_data[0],
        .bass_gain_db = macro_values_data[1],
        .vocal_gain_db = macro_values_data[2],
        .air_gain_db = macro_values_data[3],
        .relative_width = macro_values_data[4],
    };
    const dsp_smart_loudness_config smart_loudness_config = {
        .enabled = (int)smart_loudness_values_data[0],
        .low_gain_db = smart_loudness_values_data[1],
        .high_gain_db = smart_loudness_values_data[2],
    };
    const dsp_dynamic_bass_config dynamic_bass_config = {
        .enabled = (int)dynamic_bass_values_data[0],
        .strength = dynamic_bass_values_data[1],
        .max_boost_db = dynamic_bass_values_data[2],
        .detector_cutoff_hz = dynamic_bass_values_data[3],
        .silence_gate_dbfs = dynamic_bass_values_data[4],
        .low_threshold_dbfs = dynamic_bass_values_data[5],
        .high_threshold_dbfs = dynamic_bass_values_data[6],
        .attack_ms = dynamic_bass_values_data[7],
        .release_ms = dynamic_bass_values_data[8],
    };
    dsp_engine *engine = dsp_engine_create_with_studio(
        sample_rate,
        channels,
        max_frames,
        gain_db,
        coefficient_count > 0 ? coefficients : NULL,
        coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT,
        &dynamics_config,
        &multiband_config,
        &dynamic_eq_config,
        bass_coefficient_count > 0 ? bass_coefficients_values : NULL,
        bass_coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT,
        mono_bass_coefficient_count > 0 ? mono_bass_coefficients_values : NULL,
        mono_bass_coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT,
        &spatial_config,
        &convolver_config,
        &harmonic_config,
        &macro_config,
        &smart_loudness_config,
        &dynamic_bass_config);
    if (impulse_samples != NULL) {
        (*env)->ReleaseFloatArrayElements(env, convolver_samples, impulse_samples, JNI_ABORT);
    }
    return register_engine(engine);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeApplyHotConfig(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jlong generation,
    jlong editor_owner_epoch,
    jlong renderer_owner_token,
    jlong pcm_format_epoch,
    jint hot_context_token,
    jboolean media_role,
    jdouble gain_db,
    jdouble gain_linear,
    jboolean gain_pair_validated,
    jdoubleArray macro_values,
    jdoubleArray smart_loudness_values,
    jdoubleArray dynamic_bass_values,
    jint sample_rate,
    jint field_mask,
    jint hot_version,
    jdoubleArray dynamics_values,
    jdoubleArray dynamic_eq_values,
    jdoubleArray dynamics_coefficients,
    jdoubleArray dynamic_eq_coefficients) {
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return DSP_STATUS_INVALID_HANDLE;
    if (generation <= 0 || editor_owner_epoch <= 0 || renderer_owner_token <= 0 || pcm_format_epoch <= 0 ||
        hot_context_token <= 0 ||
        media_role != JNI_TRUE || !isfinite(gain_db) || gain_db < -700.0 || gain_db > 24.0 ||
        gain_pair_validated != JNI_TRUE || !isfinite(gain_linear) || gain_linear < 1.0e-35 ||
        gain_linear > 15.84893193 ||
        macro_values == NULL || smart_loudness_values == NULL || dynamic_bass_values == NULL ||
        dynamics_values == NULL || dynamic_eq_values == NULL || dynamics_coefficients == NULL ||
        dynamic_eq_coefficients == NULL || sample_rate < 8000 || sample_rate > 192000 ||
        field_mask <= 0 || (field_mask & ~7) != 0 || hot_version != 1 ||
        (*env)->GetArrayLength(env, macro_values) != 5 ||
        (*env)->GetArrayLength(env, smart_loudness_values) != 3 ||
        (*env)->GetArrayLength(env, dynamic_bass_values) != 9 ||
        (*env)->GetArrayLength(env, dynamics_values) != 10 ||
        (*env)->GetArrayLength(env, dynamic_eq_values) != DSP_DYNAMIC_EQ_VALUES_COUNT ||
        (*env)->GetArrayLength(env, dynamics_coefficients) != 5 ||
        (*env)->GetArrayLength(env, dynamic_eq_coefficients) != 10) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    jdouble macro_values_data[5];
    jdouble smart_loudness_values_data[3];
    jdouble dynamic_bass_values_data[9];
    jdouble dynamics_values_data[10];
    jdouble dynamic_eq_values_data[DSP_DYNAMIC_EQ_VALUES_COUNT];
    jdouble dynamics_coefficients_data[5];
    jdouble dynamic_eq_coefficients_data[10];
    (*env)->GetDoubleArrayRegion(env, macro_values, 0, 5, macro_values_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    (*env)->GetDoubleArrayRegion(env, dynamics_values, 0, 10, dynamics_values_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    (*env)->GetDoubleArrayRegion(env, dynamic_eq_values, 0, DSP_DYNAMIC_EQ_VALUES_COUNT, dynamic_eq_values_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    (*env)->GetDoubleArrayRegion(env, dynamics_coefficients, 0, 5, dynamics_coefficients_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    (*env)->GetDoubleArrayRegion(env, dynamic_eq_coefficients, 0, 10, dynamic_eq_coefficients_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    (*env)->GetDoubleArrayRegion(env, smart_loudness_values, 0, 3, smart_loudness_values_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    (*env)->GetDoubleArrayRegion(env, dynamic_bass_values, 0, 9, dynamic_bass_values_data);
    if ((*env)->ExceptionCheck(env)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    if ((macro_values_data[0] != 0.0 && macro_values_data[0] != 1.0) ||
        !isfinite(macro_values_data[1]) || macro_values_data[1] < -2.0 || macro_values_data[1] > 4.0 ||
        !isfinite(macro_values_data[2]) || macro_values_data[2] < -2.0 || macro_values_data[2] > 2.5 ||
        !isfinite(macro_values_data[3]) || macro_values_data[3] < -2.0 || macro_values_data[3] > 2.0 ||
        !isfinite(macro_values_data[4]) || macro_values_data[4] < 0.75 || macro_values_data[4] > 1.25 ||
        (smart_loudness_values_data[0] != 0.0 && smart_loudness_values_data[0] != 1.0) ||
        !isfinite(smart_loudness_values_data[1]) || smart_loudness_values_data[1] < 0.0 ||
        smart_loudness_values_data[1] > 3.0 || !isfinite(smart_loudness_values_data[2]) ||
        smart_loudness_values_data[2] < 0.0 || smart_loudness_values_data[2] > 1.5 ||
        (dynamic_bass_values_data[0] != 0.0 && dynamic_bass_values_data[0] != 1.0) ||
        !isfinite(dynamic_bass_values_data[1]) || dynamic_bass_values_data[1] < 0.0 ||
        dynamic_bass_values_data[1] > 1.0 || !isfinite(dynamic_bass_values_data[2]) ||
        dynamic_bass_values_data[2] < 0.0 || dynamic_bass_values_data[2] > 3.0 ||
        !isfinite(dynamic_bass_values_data[3]) || dynamic_bass_values_data[3] < 80.0 ||
        dynamic_bass_values_data[3] > 160.0 || !isfinite(dynamic_bass_values_data[4]) ||
        dynamic_bass_values_data[4] < -90.0 || dynamic_bass_values_data[4] > -30.0 ||
        !isfinite(dynamic_bass_values_data[5]) || dynamic_bass_values_data[5] < -60.0 ||
        dynamic_bass_values_data[5] > 0.0 || !isfinite(dynamic_bass_values_data[6]) ||
        dynamic_bass_values_data[6] < -60.0 || dynamic_bass_values_data[6] > 0.0 ||
        dynamic_bass_values_data[5] >= dynamic_bass_values_data[6] ||
        !isfinite(dynamic_bass_values_data[7]) || dynamic_bass_values_data[7] < 10.0 ||
        dynamic_bass_values_data[7] > 200.0 || !isfinite(dynamic_bass_values_data[8]) ||
        dynamic_bass_values_data[8] < 50.0 || dynamic_bass_values_data[8] > 1000.0) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    const dsp_dynamics_config dynamics_config = {
        .compressor_enabled = (int)dynamics_values_data[0],
        .compressor_threshold_db = dynamics_values_data[1],
        .compressor_ratio = dynamics_values_data[2],
        .compressor_attack_ms = dynamics_values_data[3],
        .compressor_release_ms = dynamics_values_data[4],
        .compressor_knee_db = dynamics_values_data[5],
        .compressor_makeup_db = dynamics_values_data[6],
        .limiter_enabled = (int)dynamics_values_data[7],
        .limiter_threshold_db = dynamics_values_data[8],
        .limiter_release_ms = dynamics_values_data[9],
    };
    dsp_dynamics_hot_params dynamics_params = {0};
    dynamics_params.version = 1;
    dynamics_params.sample_rate = sample_rate;
    dynamics_params.config = dynamics_config;
    dynamics_params.compressor_attack_coefficient = (float)dynamics_coefficients_data[0];
    dynamics_params.compressor_release_coefficient = (float)dynamics_coefficients_data[1];
    dynamics_params.compressor_makeup_linear = (float)dynamics_coefficients_data[2];
    dynamics_params.limiter_release_coefficient = (float)dynamics_coefficients_data[3];
    dynamics_params.limiter_threshold_linear = (float)dynamics_coefficients_data[4];
    dsp_dynamic_eq_config dynamic_eq_config = { .enabled = (int)dynamic_eq_values_data[0] };
    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        const size_t start = 1 + band * 10;
        if ((dynamic_eq_values_data[start] != 0.0 && dynamic_eq_values_data[start] != 1.0) ||
            (dynamic_eq_values_data[start + 1] != DSP_DYNAMIC_EQ_CUT &&
                dynamic_eq_values_data[start + 1] != DSP_DYNAMIC_EQ_BOOST)) {
            release_engine(&lease);
            return DSP_STATUS_INVALID_ARGUMENT;
        }
        dynamic_eq_config.bands[band] = (dsp_dynamic_eq_band_config) {
            .enabled = (int)dynamic_eq_values_data[start],
            .mode = (int)dynamic_eq_values_data[start + 1],
            .frequency_hz = dynamic_eq_values_data[start + 2],
            .q = dynamic_eq_values_data[start + 3],
            .threshold_db = dynamic_eq_values_data[start + 4],
            .ratio = dynamic_eq_values_data[start + 5],
            .attack_ms = dynamic_eq_values_data[start + 6],
            .release_ms = dynamic_eq_values_data[start + 7],
            .max_boost_db = dynamic_eq_values_data[start + 8],
            .max_cut_db = dynamic_eq_values_data[start + 9],
        };
    }
    dsp_dynamic_eq_hot_update dynamic_eq_update = {0};
    float attack_coefficients[DSP_DYNAMIC_EQ_MAX_BANDS];
    float release_coefficients[DSP_DYNAMIC_EQ_MAX_BANDS];
    for (size_t band = 0; band < DSP_DYNAMIC_EQ_MAX_BANDS; band++) {
        attack_coefficients[band] = (float)dynamic_eq_coefficients_data[band * 2];
        release_coefficients[band] = (float)dynamic_eq_coefficients_data[band * 2 + 1];
    }
    int values_are_finite = 1;
    for (size_t index = 0; index < 10; index++) {
        if (!isfinite(dynamics_values_data[index])) values_are_finite = 0;
    }
    for (size_t index = 0; index < DSP_DYNAMIC_EQ_VALUES_COUNT; index++) {
        if (!isfinite(dynamic_eq_values_data[index])) values_are_finite = 0;
    }
    for (size_t index = 0; index < 5; index++) {
        if (!isfinite(dynamics_coefficients_data[index])) values_are_finite = 0;
    }
    for (size_t index = 0; index < 10; index++) {
        if (!isfinite(dynamic_eq_coefficients_data[index])) values_are_finite = 0;
    }
    if (!values_are_finite || (dynamics_values_data[0] != 0.0 && dynamics_values_data[0] != 1.0) ||
        (dynamics_values_data[7] != 0.0 && dynamics_values_data[7] != 1.0) ||
        (dynamic_eq_values_data[0] != 0.0 && dynamic_eq_values_data[0] != 1.0) ||
        !dsp_dynamics_hot_params_validate_prepared(&dynamics_params) ||
        !dsp_dynamic_eq_prepare_hot_update_with_coefficients(sample_rate, &dynamic_eq_config,
            attack_coefficients, release_coefficients, &dynamic_eq_update)) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    uint32_t generation_token = (uint32_t)((uint64_t)generation & UINT32_C(0x7fffffff));
    if (generation_token == 0) generation_token = 1;
    const dsp_engine_hot_update update = {
        .generation_token = generation_token,
        .generation = (uint64_t)generation,
        .editor_owner_epoch = (uint64_t)editor_owner_epoch,
        .renderer_owner_token = (uint64_t)renderer_owner_token,
        .pcm_format_epoch = (uint64_t)pcm_format_epoch,
        .hot_context_token = hot_context_token > 0 ? (uint32_t)hot_context_token : 0,
        .media_role = media_role == JNI_TRUE ? 1 : 0,
        .gain_db = gain_db,
        .gain_linear = gain_linear,
        .gain_pair_validated = gain_pair_validated == JNI_TRUE ? 1u : 0u,
        .payload_kind = DSP_ENGINE_HOT_KIND_STUDIO,
        .field_mask = (uint32_t)field_mask,
        .hot_version = (uint32_t)hot_version,
        .macro = {
            .enabled = (int)macro_values_data[0],
            .bass_gain_db = macro_values_data[1],
            .vocal_gain_db = macro_values_data[2],
            .air_gain_db = macro_values_data[3],
            .relative_width = macro_values_data[4],
        },
        .smart_loudness = {
            .enabled = (int)smart_loudness_values_data[0],
            .low_gain_db = smart_loudness_values_data[1],
            .high_gain_db = smart_loudness_values_data[2],
        },
        .dynamic_bass = {
            .enabled = (int)dynamic_bass_values_data[0],
            .strength = dynamic_bass_values_data[1],
            .max_boost_db = dynamic_bass_values_data[2],
            .detector_cutoff_hz = dynamic_bass_values_data[3],
            .silence_gate_dbfs = dynamic_bass_values_data[4],
            .low_threshold_dbfs = dynamic_bass_values_data[5],
            .high_threshold_dbfs = dynamic_bass_values_data[6],
            .attack_ms = dynamic_bass_values_data[7],
            .release_ms = dynamic_bass_values_data[8],
        },
        .dynamics = dynamics_params,
        .dynamic_eq = dynamic_eq_update,
    };
    const int queued = dsp_engine_enqueue_hot_update(lease.engine, &update);
    release_engine(&lease);
    return queued ? DSP_STATUS_OK : DSP_STATUS_QUEUE_FULL;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeApplyPreampHot(
    JNIEnv *env, jobject receiver, jlong handle, jint version, jlong generation,
    jlong editor_owner_epoch, jlong renderer_owner_token, jlong pcm_format_epoch,
    jint hot_context_token, jboolean media_role, jdouble gain_db, jdouble gain_linear,
    jboolean gain_pair_validated) {
    (void)env;
    (void)receiver;
    if (version != 1 || generation <= 0 || editor_owner_epoch <= 0 || renderer_owner_token <= 0 ||
        pcm_format_epoch <= 0 || hot_context_token <= 0 || media_role != JNI_TRUE ||
        gain_pair_validated != JNI_TRUE || !isfinite(gain_db) || gain_db < -120.0 || gain_db > -6.0 ||
        !isfinite(gain_linear) || gain_linear < 1.0e-6 || gain_linear > 0.50118724) {
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    uint32_t generation_token = (uint32_t)((uint64_t)generation & UINT32_C(0x7fffffff));
    if (generation_token == 0) generation_token = 1;
    const dsp_engine_preamp_update update = {
        .version = (uint32_t)version,
        .generation_token = generation_token,
        .generation = (uint64_t)generation,
        .editor_owner_epoch = (uint64_t)editor_owner_epoch,
        .renderer_owner_token = (uint64_t)renderer_owner_token,
        .pcm_format_epoch = (uint64_t)pcm_format_epoch,
        .hot_context_token = (uint32_t)hot_context_token,
        .media_role = 1,
        .gain_db = gain_db,
        .gain_linear = gain_linear,
        .gain_pair_validated = gain_pair_validated == JNI_TRUE ? 1u : 0u,
    };
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return DSP_STATUS_INVALID_HANDLE;
    const int queued = dsp_engine_enqueue_preamp_update(lease.engine, &update);
    release_engine(&lease);
    return queued ? DSP_STATUS_OK : DSP_STATUS_QUEUE_FULL;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeBindHotUpdateContext(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jint sample_rate,
    jint channels,
    jlong editor_owner_epoch,
    jlong renderer_owner_token,
    jlong pcm_format_epoch,
    jint hot_context_token,
    jboolean media_role) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return DSP_STATUS_INVALID_HANDLE;
    const int bound = dsp_engine_bind_hot_update_context(
        lease.engine,
        sample_rate,
        channels,
        editor_owner_epoch > 0 ? (uint64_t)editor_owner_epoch : 0,
        renderer_owner_token > 0 ? (uint64_t)renderer_owner_token : 0,
        pcm_format_epoch > 0 ? (uint64_t)pcm_format_epoch : 0,
        hot_context_token > 0 ? (uint32_t)hot_context_token : 0,
        media_role == JNI_TRUE ? 1 : 0);
    release_engine(&lease);
    return bound ? DSP_STATUS_OK : DSP_STATUS_INVALID_ARGUMENT;
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativePrepareLocalPeqUpdate(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jdoubleArray eq_coefficients,
    jdoubleArray prepared_values,
    jlongArray history_epoch_sequence) {
    (void)receiver;
    if (eq_coefficients == NULL || prepared_values == NULL || history_epoch_sequence == NULL) return JNI_FALSE;
    const jsize coefficient_count = (*env)->GetArrayLength(env, eq_coefficients);
    const jsize prepared_count = (*env)->GetArrayLength(env, prepared_values);
    if (coefficient_count < 0 || coefficient_count > DSP_GRAPH_EQ_FILTER_CAPACITY * DSP_BIQUAD_COEFFICIENT_COUNT ||
        coefficient_count % DSP_BIQUAD_COEFFICIENT_COUNT != 0 ||
        prepared_count != (coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT) *
            DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND ||
        (*env)->GetArrayLength(env, history_epoch_sequence) != 2) return JNI_FALSE;

    double coefficients[DSP_GRAPH_EQ_FILTER_CAPACITY * DSP_BIQUAD_COEFFICIENT_COUNT] = {0};
    double values[DSP_GRAPH_EQ_FILTER_CAPACITY * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0};
    if (coefficient_count > 0) {
        (*env)->GetDoubleArrayRegion(env, eq_coefficients, 0, coefficient_count, coefficients);
        if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    }
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return JNI_FALSE;
    unsigned long long history_epoch = 0;
    unsigned long long history_end = 0;
    const int prepared = dsp_engine_prepare_local_peq_update(
        lease.engine,
        coefficient_count > 0 ? coefficients : NULL,
        (size_t)(coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT),
        prepared_count > 0 ? values : NULL,
        (size_t)prepared_count,
        &history_epoch,
        &history_end);
    if (prepared) {
        if (prepared_count > 0) {
            (*env)->SetDoubleArrayRegion(env, prepared_values, 0, prepared_count, values);
        }
        if (!(*env)->ExceptionCheck(env)) {
            const jlong sequences[2] = {(jlong)history_epoch, (jlong)history_end};
            (*env)->SetLongArrayRegion(env, history_epoch_sequence, 0, 2, sequences);
        }
    }
    const int failed = (*env)->ExceptionCheck(env);
    release_engine(&lease);
    return prepared && !failed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeEnqueueLocalPeqUpdate(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jlong generation,
    jlong editor_owner_epoch,
    jlong renderer_owner_token,
    jlong pcm_format_epoch,
    jint hot_context_token,
    jboolean media_role,
    jlong history_epoch_sequence,
    jlong history_end_sequence,
    jint band_count,
    jdoubleArray prepared_values) {
    (void)receiver;
    if (prepared_values == NULL || generation <= 0 || editor_owner_epoch <= 0 || renderer_owner_token <= 0 ||
        pcm_format_epoch <= 0 || hot_context_token <= 0 || media_role != JNI_TRUE ||
        history_epoch_sequence < 0 || history_end_sequence < history_epoch_sequence || band_count < 0 ||
        band_count > DSP_GRAPH_EQ_FILTER_CAPACITY ||
        (*env)->GetArrayLength(env, prepared_values) != band_count * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND) {
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    const jsize value_count = (*env)->GetArrayLength(env, prepared_values);
    jdouble prepared_values_data[DSP_GRAPH_EQ_FILTER_CAPACITY * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND] = {0};
    if (value_count > 0) {
        (*env)->GetDoubleArrayRegion(env, prepared_values, 0, value_count, prepared_values_data);
        if ((*env)->ExceptionCheck(env)) return DSP_STATUS_INVALID_ARGUMENT;
    }

    uint32_t generation_token = (uint32_t)((uint64_t)generation & UINT32_C(0x7fffffff));
    if (generation_token == 0) generation_token = 1;
    dsp_engine_local_peq_update update = {
        .generation_token = generation_token,
        .generation = (uint64_t)generation,
        .editor_owner_epoch = (uint64_t)editor_owner_epoch,
        .renderer_owner_token = (uint64_t)renderer_owner_token,
        .pcm_format_epoch = (uint64_t)pcm_format_epoch,
        .history_epoch_sequence = (uint64_t)history_epoch_sequence,
        .history_end_sequence = (uint64_t)history_end_sequence,
        .hot_context_token = (uint32_t)hot_context_token,
        .media_role = 1,
        .peq_count = (size_t)band_count,
    };
    for (size_t band = 0; band < update.peq_count; band++) {
        const size_t offset = band * DSP_PEQ_LOCAL_WARM_VALUES_PER_BAND;
        update.peq[band] = (dsp_biquad) {
            .b0 = prepared_values_data[offset],
            .b1 = prepared_values_data[offset + 1],
            .b2 = prepared_values_data[offset + 2],
            .a1 = prepared_values_data[offset + 3],
            .a2 = prepared_values_data[offset + 4],
            .z1 = {(float)prepared_values_data[offset + 5], (float)prepared_values_data[offset + 6]},
            .z2 = {(float)prepared_values_data[offset + 7], (float)prepared_values_data[offset + 8]},
        };
    }

    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return DSP_STATUS_INVALID_HANDLE;
    const int queued = dsp_engine_enqueue_local_peq_update(lease.engine, &update);
    release_engine(&lease);
    return queued;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetHotAckToken(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return -DSP_STATUS_INVALID_HANDLE;
    const uint32_t ack_token = dsp_engine_get_hot_ack_token(lease.engine);
    release_engine(&lease);
    return (jint)ack_token;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetHotDeferredToken(
    JNIEnv *env, jobject receiver, jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return 0;
    const uint32_t token = dsp_engine_get_hot_deferred_token(lease.engine);
    release_engine(&lease);
    return (jint)token;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetHotDeferredReason(
    JNIEnv *env, jobject receiver, jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return 0;
    const uint32_t reason = dsp_engine_get_hot_deferred_reason(lease.engine);
    release_engine(&lease);
    return (jint)reason;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetPreampHotAckToken(
    JNIEnv *env, jobject receiver, jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return -DSP_STATUS_INVALID_HANDLE;
    const uint32_t ack_token = dsp_engine_get_preamp_ack_token(lease.engine);
    release_engine(&lease);
    return (jint)ack_token;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetLocalPeqAckToken(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return -DSP_STATUS_INVALID_HANDLE;
    const uint32_t ack_token = dsp_engine_get_local_peq_ack_token(lease.engine);
    release_engine(&lease);
    return (jint)ack_token;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeCancelLocalPeqUpdate(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jint generation_token) {
    (void)env;
    (void)receiver;
    if (generation_token <= 0) return DSP_STATUS_INVALID_ARGUMENT;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return DSP_STATUS_INVALID_HANDLE;
    const int status = dsp_engine_cancel_local_peq_update(lease.engine, (uint32_t)generation_token);
    release_engine(&lease);
    return (jint)status;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetLocalPeqRejectedToken(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) return -DSP_STATUS_INVALID_HANDLE;
    const uint32_t rejected_token = dsp_engine_get_local_peq_rejected_token(lease.engine);
    release_engine(&lease);
    return (jint)rejected_token;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeProcess(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jobject input_buffer,
    jint input_position,
    jint input_remaining,
    jobject output_buffer,
    jint output_position,
    jint output_remaining,
    jint frames,
    jint channels) {
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    if (input_buffer == NULL || output_buffer == NULL ||
        input_position < 0 || output_position < 0 || input_remaining < 0 || output_remaining < 0 ||
        frames <= 0 || channels < 1 || channels > 2) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }

    const jlong expected_bytes = (jlong)frames * (jlong)channels * (jlong)sizeof(float);
    const jlong input_capacity = (*env)->GetDirectBufferCapacity(env, input_buffer);
    const jlong output_capacity = (*env)->GetDirectBufferCapacity(env, output_buffer);
    void *input_address = (*env)->GetDirectBufferAddress(env, input_buffer);
    void *output_address = (*env)->GetDirectBufferAddress(env, output_buffer);
    if (input_address == NULL || output_address == NULL || input_capacity < 0 || output_capacity < 0 ||
        input_remaining != expected_bytes || output_remaining < expected_bytes ||
        (jlong)input_position + input_remaining > input_capacity ||
        (jlong)output_position + output_remaining > output_capacity ||
        input_remaining % (jint)sizeof(float) != 0 || output_remaining % (jint)sizeof(float) != 0) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_BUFFER;
    }

    const uintptr_t input_base = (uintptr_t)input_address;
    const uintptr_t output_base = (uintptr_t)output_address;
    const uintptr_t expected_size = (uintptr_t)expected_bytes;
    if ((uintptr_t)input_position > UINTPTR_MAX - input_base ||
        (uintptr_t)output_position > UINTPTR_MAX - output_base) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_BUFFER;
    }
    const uintptr_t input_start = input_base + (uintptr_t)input_position;
    const uintptr_t output_start = output_base + (uintptr_t)output_position;
    if (input_start % _Alignof(float) != 0 || output_start % _Alignof(float) != 0 ||
        expected_size > UINTPTR_MAX - input_start || expected_size > UINTPTR_MAX - output_start) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_BUFFER;
    }
    if (input_start < output_start + expected_size && output_start < input_start + expected_size) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
    const float *input = (const float *)input_start;
    float *output = (float *)output_start;
    const dsp_status status = dsp_engine_process(
        lease.engine,
        input,
        (size_t)input_remaining / sizeof(float),
        output,
        (size_t)output_remaining / sizeof(float),
        frames,
        channels);
    release_engine(&lease);
    return (jint)status;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeReset(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    const dsp_status status = dsp_engine_reset(lease.engine);
    release_engine(&lease);
    return (jint)status;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetLatencyFrames(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) {
        return -1;
    }
    const int latency = dsp_engine_get_latency_frames(lease.engine);
    release_engine(&lease);
    return (jint)latency;
}

JNIEXPORT jdouble JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetInterchannelRelativeDelayFrames(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) {
        return -1.0;
    }
    const double delay = dsp_engine_get_interchannel_relative_delay_frames(lease.engine);
    release_engine(&lease);
    return delay;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeGetDiagnostics(
    JNIEnv *env,
    jobject receiver,
    jlong handle,
    jdoubleArray values,
    jlongArray counters) {
    (void)receiver;
    dsp_engine_lease lease = {0};
    if (!acquire_engine(handle, &lease)) {
        return DSP_STATUS_INVALID_HANDLE;
    }
    if (values == NULL || counters == NULL || (*env)->GetArrayLength(env, values) < 8 ||
        (*env)->GetArrayLength(env, counters) < 5) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }

    dsp_engine_diagnostics snapshot;
    const dsp_status status = dsp_engine_get_diagnostics(lease.engine, &snapshot);
    if (status != DSP_STATUS_OK) {
        release_engine(&lease);
        return (jint)status;
    }
    const jdouble metrics[8] = {
        snapshot.input_peak[0],
        snapshot.input_peak[1],
        snapshot.input_rms[0],
        snapshot.input_rms[1],
        snapshot.output_peak[0],
        snapshot.output_peak[1],
        snapshot.output_rms[0],
        snapshot.output_rms[1],
    };
    const jlong counts[5] = {
        (jlong)snapshot.processed_frames,
        (jlong)snapshot.processed_blocks,
        (jlong)snapshot.non_finite_input_samples,
        (jlong)snapshot.non_finite_output_samples,
        (jlong)snapshot.native_error_count,
    };
    (*env)->SetDoubleArrayRegion(env, values, 0, 8, metrics);
    (*env)->SetLongArrayRegion(env, counters, 0, 5, counts);
    release_engine(&lease);
    return DSP_STATUS_OK;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_dsp_NativeDspJni_nativeDestroy(
    JNIEnv *env,
    jobject receiver,
    jlong handle) {
    (void)env;
    (void)receiver;
    destroy_engine(handle);
}
