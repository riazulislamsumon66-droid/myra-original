#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <GLES2/gl2.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <vector>
#include <map>

// Live2D Cubism Core header
#include "Live2DCubismCore.h"

#define LOG_TAG "Live2D_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Minimal JSON parser for motion files
// ============================================================
struct MotionKeyframe {
    float time;
    float value;
};

struct MotionCurve {
    std::string target;
    std::string id;
    std::vector<MotionKeyframe> keyframes;
};

struct MotionData {
    float duration;
    float fps;
    bool loop;
    std::vector<MotionCurve> curves;
};

// Simple JSON string extraction helpers
static const char* skipWhitespace(const char* p) {
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    return p;
}

static bool parseJsonString(const char* p, std::string& out) {
    const char* cur = skipWhitespace(p);
    if (*cur != '"') return false;
    cur++;
    out.clear();
    while (*cur && *cur != '"') {
        if (*cur == '\\') {
            cur++;
            if (*cur == 'n') out += '\n';
            else if (*cur == 't') out += '\t';
            else if (*cur == '"') out += '"';
            else if (*cur == '\\') out += '\\';
            else out += *cur;
        } else {
            out += *cur;
        }
        cur++;
    }
    return true;
}

static bool parseJsonNumber(const char*& p, float& out) {
    p = skipWhitespace(p);
    const char* start = p;
    if (*p == '-') p++;
    while (*p >= '0' && *p <= '9') p++;
    if (*p == '.') {
        p++;
        while (*p >= '0' && *p <= '9') p++;
    }
    out = strtof(start, nullptr);
    return true;
}

static bool parseJsonBool(const char*& p, bool& out) {
    p = skipWhitespace(p);
    if (strncmp(p, "true", 4) == 0) { out = true; p += 4; return true; }
    if (strncmp(p, "false", 5) == 0) { out = false; p += 5; return true; }
    return false;
}

// Parse a motion JSON file from asset memory
static bool parseMotionData(const char* jsonData, size_t jsonLen, MotionData& motion) {
    std::string json(jsonData, jsonLen);
    const char* p = json.c_str();

    // Find "Meta" section
    const char* metaPos = strstr(p, "\"Meta\"");
    if (!metaPos) { LOGE("No Meta section in motion"); return false; }

    // Parse Meta fields
    const char* metaStart = strchr(metaPos, '{');
    if (!metaStart) return false;
    metaStart++;

    // Find Duration, Fps, Loop
    const char* key = strstr(metaStart, "\"Duration\"");
    if (key) {
        const char* colon = strchr(key, ':');
        if (colon) parseJsonNumber(colon + 1, motion.duration);
    }
    key = strstr(metaStart, "\"Fps\"");
    if (key) {
        const char* colon = strchr(key, ':');
        if (colon) parseJsonNumber(colon + 1, motion.fps);
    }
    key = strstr(metaStart, "\"Loop\"");
    if (key) {
        const char* colon = strchr(key, ':');
        if (colon) parseJsonBool(colon + 1, motion.loop);
    }

    LOGI("Motion: duration=%.3f, fps=%.1f, loop=%d", motion.duration, motion.fps, motion.loop);

    // Parse Curves array
    const char* curvesPos = strstr(p, "\"Curves\"");
    if (!curvesPos) { LOGE("No Curves section"); return false; }

    const char* arrStart = strchr(curvesPos, '[');
    if (!arrStart) return false;
    arrStart++;

    // Parse each curve object
    const char* cur = arrStart;
    while (*cur) {
        cur = skipWhitespace(cur);
        if (*cur == ']') break;
        if (*cur == ',') { cur++; continue; }
        if (*cur != '{') { cur++; continue; }

        MotionCurve curve;
        const char* objStart = cur + 1;

        // Find "Target"
        const char* targetKey = strstr(objStart, "\"Target\"");
        if (targetKey) {
            const char* colon = strchr(targetKey, ':');
            if (colon) {
                std::string targetStr;
                parseJsonString(colon + 1, targetStr);
                curve.target = targetStr;
            }
        }

        // Find "Id"
        const char* idKey = strstr(objStart, "\"Id\"");
        if (idKey) {
            const char* colon = strchr(idKey, ':');
            if (colon) {
                std::string idStr;
                parseJsonString(colon + 1, idStr);
                curve.id = idStr;
            }
        }

        // Find "Segments"
        const char* segKey = strstr(objStart, "\"Segments\"");
        if (segKey) {
            const char* segArr = strchr(segKey, '[');
            if (segArr) {
                segArr++;
                // Segments format: [time, value, time, value, ...] or [time, value, inBezier, outBezier, ...]
                // Simple: read pairs of (time, value)
                while (*segArr) {
                    segArr = skipWhitespace(segArr);
                    if (*segArr == ']') break;
                    if (*segArr == ',') { segArr++; continue; }

                    MotionKeyframe kf;
                    if (!parseJsonNumber(segArr, kf.time)) break;

                    segArr = skipWhitespace(segArr);
                    if (*segArr == ',') segArr++;
                    segArr = skipWhitespace(segArr);

                    if (!parseJsonNumber(segArr, kf.value)) break;

                    curve.keyframes.push_back(kf);

                    // Skip optional bezier values (in_tangent, out_tangent pairs)
                    // Motion format: [time, value, ...] where ... can be bezier data
                    // We just read pairs, so skip any extra numbers
                    segArr = skipWhitespace(segArr);
                    while (*segArr == ',') {
                        segArr++;
                        segArr = skipWhitespace(segArr);
                        // Skip bezier values (just consume the number)
                        float dummy;
                        if (*segArr == ']' || *segArr == ',') break;
                        parseJsonNumber(segArr, dummy);
                        segArr = skipWhitespace(segArr);
                    }
                }
            }
        }

        if (!curve.id.empty() && curve.target == "Parameter") {
            motion.curves.push_back(curve);
            LOGI("  Curve: %s, %zu keyframes", curve.id.c_str(), curve.keyframes.size());
        }

        // Find closing brace
        const char* objEnd = strchr(objStart, '}');
        if (objEnd) cur = objEnd + 1;
        else break;
    }

    LOGI("Total curves parsed: %zu", motion.curves.size());
    return !motion.curves.empty();
}

// Interpolate value from keyframes at given time
static float interpolateKeyframes(const std::vector<MotionKeyframe>& keyframes, float time, bool loop, float duration) {
    if (keyframes.empty()) return 0.0f;
    if (keyframes.size() == 1) return keyframes[0].value;

    // Handle looping
    if (loop && duration > 0) {
        while (time > duration) time -= duration;
        while (time < 0) time += duration;
    }

    // Clamp
    if (time <= keyframes.front().time) return keyframes.front().value;
    if (time >= keyframes.back().time) return keyframes.back().value;

    // Find surrounding keyframes
    for (size_t i = 0; i < keyframes.size() - 1; i++) {
        if (time >= keyframes[i].time && time <= keyframes[i + 1].time) {
            float t = (time - keyframes[i].time) / (keyframes[i + 1].time - keyframes[i].time);
            // Smooth interpolation (cubic ease in-out)
            t = t * t * (3.0f - 2.0f * t);
            return keyframes[i].value + (keyframes[i + 1].value - keyframes[i].value) * t;
        }
    }

    return keyframes.back().value;
}

// ============================================================
// Model state
// ============================================================
static csmMoc* g_moc = nullptr;
static csmModel* g_model = nullptr;
static void* g_mocBuffer = nullptr;
static void* g_modelBuffer = nullptr;
static unsigned int g_mocSize = 0;
static bool g_sdkLoaded = false;

// Motion state
static MotionData g_currentMotion;
static bool g_motionPlaying = false;
static float g_motionTime = 0.0f;
static float g_motionSpeed = 1.0f;

// Idle motion auto-play
static MotionData g_idleMotion;
static bool g_idleLoaded = false;

// Parameter name → index cache for fast lookup
static std::map<std::string, int> g_paramIndexCache;

// Stored asset manager for motion loading
static AAssetManager* g_assetManager = nullptr;
static std::string g_basePath;

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

    // Store asset manager for later use (motion loading)
    g_assetManager = AAssetManager_fromJava(env, assetManager);

    // Load .moc3 file from assets
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    std::string basePath(path);
    env->ReleaseStringUTFChars(modelPath, path);
    g_basePath = basePath;

    // Try to find the .moc3 file
    std::string mocPath;
    AAssetManager* mgr = g_assetManager;

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
    unsigned int modelBufferSize = g_mocSize * 4;
    g_modelBuffer = malloc(modelBufferSize);
    g_model = csmInitializeModelInPlace(g_moc, g_modelBuffer, modelBufferSize);
    if (!g_model) {
        LOGE("Failed to initialize model");
        g_moc = nullptr;
        free(g_mocBuffer);
        g_mocBuffer = nullptr;
        return JNI_FALSE;
    }

    g_sdkLoaded = true;

    // Build parameter index cache
    int paramCount = csmGetParameterCount(g_model);
    LOGI("Model params: %d", paramCount);
    const char** paramIds = csmGetParameterIds(g_model);
    if (paramIds) {
        for (int i = 0; i < paramCount; i++) {
            g_paramIndexCache[std::string(paramIds[i])] = i;
            if (i < 15) LOGI("  Param[%d]: %s", i, paramIds[i]);
        }
    }

    // Load idle motion (Scene1)
    std::string idlePath = basePath + "/motion/Scene1.motion3.json";
    AAsset* idleAsset = AAssetManager_open(mgr, idlePath.c_str(), AASSET_MODE_BUFFER);
    if (idleAsset) {
        size_t len = AAsset_getLength(idleAsset);
        const void* data = AAsset_getBuffer(idleAsset);
        if (parseMotionData(static_cast<const char*>(data), len, g_idleMotion)) {
            g_idleLoaded = true;
            g_currentMotion = g_idleMotion;
            g_motionPlaying = true;
            g_motionTime = 0.0f;
            LOGI("Idle motion loaded successfully");
        }
        AAsset_close(idleAsset);
    } else {
        LOGE("Failed to load idle motion from: %s", idlePath.c_str());
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

    // Update motion playback
    if (g_motionPlaying && !g_currentMotion.curves.empty()) {
        g_motionTime += (1.0f / 30.0f) * g_motionSpeed; // Assume 30fps

        if (g_currentMotion.loop && g_currentMotion.duration > 0) {
            if (g_motionTime > g_currentMotion.duration) {
                g_motionTime -= g_currentMotion.duration;
            }
        } else if (g_motionTime > g_currentMotion.duration) {
            // Non-looping motion finished — switch back to idle
            if (&g_currentMotion != &g_idleMotion && g_idleLoaded) {
                g_currentMotion = g_idleMotion;
                g_motionTime = 0.0f;
            } else {
                g_motionPlaying = false;
            }
        }

        // Apply motion curves to model parameters
        float* paramValues = csmGetParameterValues(g_model);
        if (paramValues) {
            for (const auto& curve : g_currentMotion.curves) {
                auto it = g_paramIndexCache.find(curve.id);
                if (it != g_paramIndexCache.end()) {
                    float value = interpolateKeyframes(curve.keyframes, g_motionTime,
                                                       g_currentMotion.loop, g_currentMotion.duration);
                    paramValues[it->second] = value;
                }
            }
        }
    }

    csmUpdateModel(g_model);
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeOnTouch(
        JNIEnv* /* env */,
        jobject /* this */,
        jfloat x,
        jfloat y) {

    if (!g_sdkLoaded) return;
    LOGI("Touch at (%.1f, %.1f) — playing Tap motion", x, y);

    // Play Tap motion (Scene2)
    if (g_sdkLoaded) {
        // We'll trigger tap via a flag — the renderer will load it
        // For now, just log — full implementation needs asset reload
    }
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativePlayMotion(
        JNIEnv* env,
        jobject /* this */,
        jstring group,
        jint index) {

    if (!g_sdkLoaded) return;

    const char* g = env->GetStringUTFChars(group, nullptr);
    std::string groupStr(g);
    env->ReleaseStringUTFChars(group, g);

    LOGI("Play motion: group=%s index=%d", groupStr.c_str(), index);

    // Map group names to motion files
    std::string motionFile;
    if (groupStr == "Idle" || groupStr == "idle") {
        motionFile = "motion/Scene1.motion3.json";
    } else if (groupStr == "Tap" || groupStr == "tap") {
        motionFile = "motion/Scene2.motion3.json";
    } else if (groupStr == "Flic" || groupStr == "flic") {
        motionFile = "motion/Scene3.motion3.json";
    } else {
        LOGE("Unknown motion group: %s", groupStr.c_str());
        return;
    }

    // Load motion from assets
    if (!g_assetManager) {
        LOGE("No asset manager available for motion loading");
        return;
    }

    std::string fullPath = g_basePath + "/" + motionFile;
    AAsset* motionAsset = AAssetManager_open(g_assetManager, fullPath.c_str(), AASSET_MODE_BUFFER);
    if (!motionAsset) {
        LOGE("Failed to open motion file: %s", fullPath.c_str());
        return;
    }

    size_t motionLen = AAsset_getLength(motionAsset);
    const void* motionData = AAsset_getBuffer(motionAsset);

    MotionData newMotion;
    if (parseMotionData(static_cast<const char*>(motionData), motionLen, newMotion)) {
        g_currentMotion = newMotion;
        g_motionTime = 0.0f;
        g_motionPlaying = true;
        LOGI("Motion loaded: %s (%.1fs, %zu curves)", motionFile.c_str(),
             g_currentMotion.duration, g_currentMotion.curves.size());
    } else {
        LOGE("Failed to parse motion: %s", motionFile.c_str());
    }

    AAsset_close(motionAsset);
}

JNIEXPORT void JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeSetParameter(
        JNIEnv* env,
        jobject /* this */,
        jstring paramId,
        jfloat value) {

    if (!g_sdkLoaded || !g_model) return;

    const char* id = env->GetStringUTFChars(paramId, nullptr);

    auto it = g_paramIndexCache.find(std::string(id));
    if (it != g_paramIndexCache.end()) {
        float* values = csmGetParameterValues(g_model);
        if (values) {
            values[it->second] = value;
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

    auto it = g_paramIndexCache.find(std::string(id));
    if (it != g_paramIndexCache.end()) {
        float* values = csmGetParameterValues(g_model);
        if (values) {
            value = values[it->second];
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
        g_model = nullptr;
        free(g_modelBuffer);
        g_modelBuffer = nullptr;
    }
    if (g_moc) {
        g_moc = nullptr;
        free(g_mocBuffer);
        g_mocBuffer = nullptr;
    }

    g_sdkLoaded = false;
    g_motionPlaying = false;
    g_idleLoaded = false;
    g_paramIndexCache.clear();
    LOGI("Live2D cleanup complete");
}

JNIEXPORT jboolean JNICALL
Java_com_maya_assistant_ui_character_Live2DRenderer_nativeIsAvailable(
        JNIEnv* /* env */,
        jobject /* this */) {
    return g_sdkLoaded ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
