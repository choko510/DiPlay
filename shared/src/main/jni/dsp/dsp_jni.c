#include "dsp_engine.h"

#include <jni.h>
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
    jdoubleArray dynamics_values) {
    (void)env;
    (void)receiver;
    if (peq_coefficients == NULL || dynamics_values == NULL) {
        return 0;
    }
    const jsize coefficient_count = (*env)->GetArrayLength(env, peq_coefficients);
    const jsize dynamics_count = (*env)->GetArrayLength(env, dynamics_values);
    if (coefficient_count < 0 || coefficient_count >
            DSP_BIQUAD_MAX_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT ||
        coefficient_count % DSP_BIQUAD_COEFFICIENT_COUNT != 0 || dynamics_count != 10) {
        return 0;
    }
    double coefficients[DSP_BIQUAD_MAX_BANDS * DSP_BIQUAD_COEFFICIENT_COUNT];
    double dynamic_values[10];
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
    return register_engine(dsp_engine_create(
        sample_rate,
        channels,
        max_frames,
        gain_db,
        coefficient_count > 0 ? coefficients : NULL,
        coefficient_count / DSP_BIQUAD_COEFFICIENT_COUNT,
        &dynamics_config));
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

    const float *input = (const float *)((uint8_t *)input_address + input_position);
    float *output = (float *)((uint8_t *)output_address + output_position);
    const uintptr_t input_start = (uintptr_t)input;
    const uintptr_t output_start = (uintptr_t)output;
    const uintptr_t expected_size = (uintptr_t)expected_bytes;
    if (input_start < output_start + expected_size && output_start < input_start + expected_size) {
        release_engine(&lease);
        return DSP_STATUS_INVALID_ARGUMENT;
    }
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
