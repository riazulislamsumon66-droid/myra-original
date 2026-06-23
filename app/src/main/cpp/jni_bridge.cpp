#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <GLES2/gl2.h>
#include <string>
#include <cstring>

// Live2D Cubism Core header
#include "Live2DCubismCore.h"

#define LOG_TAG "Live2D_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Model state
static csmMoc* g_moc = nullptr;
static csmModel* g_model = nullptr;
static void* g_mocBuffer = nullptr;
static void* g_modelBuffer = nullptr;
static csmUint32 g_mocSize = 0;
static bool g_sdkLoaded = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager,
        jstring modelPath,
        jint width,
        jint height) {

    LOGI("Initializing Live2D native renderer: %dx%d", width, height);

    // Load .moc3 file from assets
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    std::string basePath(path);
    env->ReleaseStringUTFChars(modelPath, path);

    // Try to find the .moc3 file
    std::string mocPath;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    // First try: specific file
    mocPath = basePath + "/miara_pro_t03.moc3";
    AAsset* asset = AAssetManager_open(mgr, mocPath.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        mocPath = basePath + ".moc3";
        asset = AAssetManager_open(mgr, mocPath.c_str(), AASSET_MODE_BUFFER);
    }
    if (!asset) {
        // List directory to find .moc3
        AAssetDir* dir = AAssetManager_openDir(mgr, basePath.c_str());
        if (dir) {
            const char* filename = nullptr;
            while ((filename = AAssetDir_getNextFileName(dir)) != nullptr) {
                std::string fn(filename);
                if (fn.length() > 5 && fn.substr(fn.length() - 5) == ".moc3") {
                    mocPath = basePath + "/" + fn;
                    asset = AAssetManager_open(mgr, mocPath.c_str(), AASSET_MODE_BUFFER);
                    if (asset) {
                        LOGI("Found moc3: %s", mocPath.c_str());
                        break;
                    }
                }
            }
            AAssetDir_close(dir);
        }
    }

    if (!asset) {
        LOGE("Failed to find .moc3 file in: %s", basePath.c_str());
        return JNI_FALSE;
    }

    g_mocSize = AAsset_getLength(asset);
    g_mocBuffer = malloc(g_mocSize);
    memcpy(g_mocBuffer, AAsset_getBuffer(asset), g_mocSize);
    AAsset_close(asset);

    // Revive Moc
    g_moc = csmReviveMocInPlace(g_mocBuffer, g_mocSize);
    if (!g_moc) {
        LOGE("Failed to revive moc3");
        free(g_mocBuffer);
        g_mocBuffer = nullptr;
        return JNI_FALSE;
    }

    // Initialize model
    g_modelBuffer = malloc(g_mocSize);  // Allocate at least mocSize
    g_model = csmInitializeModelInPlace(g_moc, g_modelBuffer, g_mocSize);
    if (!g_model) {
        LOGE("Failed to initialize model");
        csmDeleteMoc(g_moc);
        g_moc = nullptr;
        free(g_mocBuffer);
        g_mocBuffer = nullptr;
        return JNI_FALSE;
    }

    g_sdkLoaded = true;

    // Log model info
    int paramCount = csmGetParameterCount(g_model);
    LOGI("Live2D native renderer initialized successfully");
    LOGI("Model params: %d", paramCount);

    // Log parameter names
    const char** paramIds = csmGetParameterIds(g_model);
    if (paramIds) {
        for (int i = 0; i < paramCount && i < 10; i++) {
            LOGI("  Param[%d]: %s", i, paramIds[i]);
        }
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeRender(
        JNIEnv* /* env */,
        jobject /* this */,
        jint /* width */,
        jint /* height */) {

    if (!g_sdkLoaded || !g_model) return;
    csmUpdateModel(g_model);
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeOnTouch(
        JNIEnv* /* env */,
        jobject /* this */,
        jfloat /* x */,
        jfloat /* y */) {
    // Touch handling for Live2D model
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativePlayMotion(
        JNIEnv* env,
        jobject /* this */,
        jstring group,
    jint index) {

    if (!g_sdkLoaded) return;

    const char* g = env->GetStringUTFChars(group, nullptr);
    LOGI("Motion requested: group=%s index=%d", g, index);
    env->ReleaseStringUTFChars(group, g);
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeSetParameter(
        JNIEnv* env,
        jobject /* this */,
        jstring paramId,
        jfloat value) {

    if (!g_sdkLoaded || !g_model) return;

    const char* id = env->GetStringUTFChars(paramId, nullptr);

    // Find parameter by name
    int count = csmGetParameterCount(g_model);
    const char** ids = csmGetParameterIds(g_model);
    float* values = csmGetParameterValues(g_model);

    if (ids && values) {
        for (int i = 0; i < count; i++) {
            if (ids[i] && strcmp(ids[i], id) == 0) {
                values[i] = value;
                break;
            }
        }
    }

    env->ReleaseStringUTFChars(paramId, id);
}

JNIEXPORT jfloat JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeGetParameter(
        JNIEnv* env,
        jobject /* this */,
        jstring paramId) {

    if (!g_sdkLoaded || !g_model) return 0.0f;

    const char* id = env->GetStringUTFChars(paramId, nullptr);
    float value = 0.0f;

    int count = csmGetParameterCount(g_model);
    const char** ids = csmGetParameterIds(g_model);
    float* values = csmGetParameterValues(g_model);

    if (ids && values) {
        for (int i = 0; i < count; i++) {
            if (ids[i] && strcmp(ids[i], id) == 0) {
                value = values[i];
                break;
            }
        }
    }

    env->ReleaseStringUTFChars(paramId, id);
    return value;
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeCleanup(
        JNIEnv* /* env */,
        jobject /* this */) {

    LOGI("Cleaning up Live2D native renderer");

    if (g_model) {
        // SDK 5.x doesn't have csmDeleteModel — model is in-place allocated
        g_model = nullptr;
        free(g_modelBuffer);
        g_modelBuffer = nullptr;
    }
    if (g_moc) {
        // SDK 5.x doesn't have csmDeleteMoc — moc is in-place allocated
        g_moc = nullptr;
        free(g_mocBuffer);
        g_mocBuffer = nullptr;
    }

    g_sdkLoaded = false;
    LOGI("Live2D cleanup complete");
}

JNIEXPORT jboolean JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeIsAvailable(
        JNIEnv* /* env */,
        jobject /* this */) {
    return g_sdkLoaded ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
