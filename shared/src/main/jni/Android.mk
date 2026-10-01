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
    dsp/dsp_gain.c \
    dsp/dsp_meter.c \
    dsp/dsp_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/dsp
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
