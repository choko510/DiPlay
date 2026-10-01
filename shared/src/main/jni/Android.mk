LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_dsp
LOCAL_SRC_FILES := \
    dsp/dsp_engine.c \
    dsp/dsp_graph.c \
    dsp/dsp_biquad.c \
    dsp/dsp_dynamics.c \
    dsp/dsp_multiband.c \
    dsp/dsp_dynamic_eq.c \
    dsp/dsp_spatial.c \
    dsp/dsp_convolver.c \
    dsp/dsp_gain.c \
    dsp/dsp_meter.c \
    dsp/dsp_jni.c \
    third_party/kissfft/kiss_fft.c \
    third_party/kissfft/kiss_fftr.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/dsp $(LOCAL_PATH)/third_party/kissfft
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
