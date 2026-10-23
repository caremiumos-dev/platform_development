LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_MODULE := monkey
LOCAL_MODULE_TAGS := optional
LOCAL_JAVA_LIBRARIES := framework

include $(BUILD_JAVA_LIBRARY)
