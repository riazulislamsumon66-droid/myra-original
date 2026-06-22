#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "Live2D_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Live2D Native Bridge
// This is a stub implementation that provides the JNI interface.
// Full implementation requires Live2D Cubism SDK 4.x headers and libCubismCore.so
//
// When libCubismCore.so is available:
// 1. Add SDK headers to cpp/ directory
// 2. Replace stub functions with actual Live2D API calls
// 3. Update CMakeLists.txt to link against cubism_core

static bool g_available = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager,
        jstring modelPath,
        jint width,
        jint height) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Live2D native init requested: %s (%dx%d)", path, width, height);
    env->ReleaseStringUTFChars(modelPath, path);

    // Check if native library is loaded
    // Without libCubismCore.so, this will always return false
    g_available = false;

    if (!g_available) {
        LOGE("Live2D native library (libCubismCore.so) not found. Using Canvas fallback.");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeRender(
        JNIEnv* /* env */,
        jobject /* this */,
        jint /* width */,
        jint /* height */) {
    // Stub - no rendering without native library
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeOnTouch(
        JNIEnv* /* env */,
        jobject /* this */,
        jfloat /* x */,
        jfloat /* y */) {
    // Stub
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativePlayMotion(
        JNIEnv* env,
        jobject /* this */,
        jstring group,
        jint index) {

    const char* g = env->GetStringUTFChars(group, nullptr);
    LOGI("Motion requested: group=%s index=%d (native not available)", g, index);
    env->ReleaseStringUTFChars(group, g);
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeSetParameter(
        JNIEnv* /* env */,
        jobject /* this */,
        jstring /* paramId */,
        jfloat /* value */) {
    // Stub
}

JNIEXPORT jfloat JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeGetParameter(
        JNIEnv* /* env */,
        jobject /* this */,
        jstring /* paramId */) {
    return 0.0f;
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeCleanup(
        JNIEnv* /* env */,
        jobject /* this */) {
    LOGI("Live2D native cleanup (stub)");
    g_available = false;
}

JNIEXPORT jboolean JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeIsAvailable(
        JNIEnv* /* env */,
        jobject /* this */) {
    return g_available ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
