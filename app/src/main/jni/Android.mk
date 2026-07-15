HEV_PATH := $(abspath $(HEV_SOURCE))
LOCAL_PATH := $(HEV_PATH)

ifeq ($(filter $(modules-get-list),yaml),)
include $(HEV_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
include $(HEV_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),hev-task-system),)
include $(HEV_PATH)/third-part/hev-task-system/Android.mk
endif

SRCDIR := $(HEV_PATH)/src
include $(CLEAR_VARS)
include $(HEV_PATH)/build.mk
LOCAL_MODULE := hev-socks5-tunnel
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(filter-out $(SRCDIR)/hev-jni.c,$(SRCFILES)))
LOCAL_C_INCLUDES := \
    $(HEV_PATH)/include \
    $(HEV_PATH)/src \
    $(HEV_PATH)/src/misc \
    $(HEV_PATH)/src/core/include \
    $(HEV_PATH)/third-part/yaml/include \
    $(HEV_PATH)/third-part/lwip/src/include \
    $(HEV_PATH)/third-part/lwip/src/ports/include \
    $(HEV_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
