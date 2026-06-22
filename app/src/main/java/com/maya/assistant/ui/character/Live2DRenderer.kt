package com.maya.assistant.ui.character

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * Live2DRenderer — Kotlin wrapper for native Live2D bridge.
 *
 * Provides graceful fallback: when native library is unavailable,
 * the character automatically uses Canvas-based rendering instead.
 */
class Live2DRenderer(private val context: Context) {

    companion object {
        private const val TAG = "Live2DRenderer"

        init {
            try {
                System.loadLibrary("live2d_bridge")
                Log.i(TAG, "Live2D native bridge loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Live2D native bridge not available: ${e.message}")
            }
        }
    }

    private var nativeHandle: Long = 0
    private var isNativeAvailable: Boolean = false

    /**
     * Initialize Live2D with a model from assets.
     * @param modelPath Path to model directory in assets (e.g., "live2d/miara")
     * @param width Screen width in pixels
     * @param height Screen height in pixels
     * @return true if native renderer initialized, false if Canvas fallback needed
     */
    fun init(modelPath: String, width: Int, height: Int): Boolean {
        return try {
            val result = nativeInit(context.assets, modelPath, width, height)
            isNativeAvailable = result
            if (result) {
                Log.i(TAG, "Live2D native renderer initialized: $modelPath")
            } else {
                Log.i(TAG, "Live2D native not available, will use Canvas fallback")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Live2D: ${e.message}")
            isNativeAvailable = false
            false
        }
    }

    /**
     * Render a frame. Only works if native library is available.
     */
    fun render(width: Int, height: Int) {
        if (!isNativeAvailable) return
        try {
            nativeRender(width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Render failed: ${e.message}")
        }
    }

    /**
     * Handle touch event.
     */
    fun onTouch(x: Float, y: Float) {
        if (!isNativeAvailable) return
        try {
            nativeOnTouch(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Touch failed: ${e.message}")
        }
    }

    /**
     * Play a motion animation.
     */
    fun playMotion(group: String, index: Int) {
        if (!isNativeAvailable) return
        try {
            nativePlayMotion(group, index)
        } catch (e: Exception) {
            Log.e(TAG, "Play motion failed: ${e.message}")
        }
    }

    /**
     * Set a model parameter value.
     */
    fun setParameter(paramId: String, value: Float) {
        if (!isNativeAvailable) return
        try {
            nativeSetParameter(paramId, value)
        } catch (e: Exception) {
            Log.e(TAG, "Set parameter failed: ${e.message}")
        }
    }

    /**
     * Get a model parameter value.
     */
    fun getParameter(paramId: String): Float {
        if (!isNativeAvailable) return 0f
        return try {
            nativeGetParameter(paramId)
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * Check if native Live2D is available.
     */
    fun isNativeAvailable(): Boolean {
        return isNativeAvailable
    }

    /**
     * Cleanup native resources.
     */
    fun cleanup() {
        if (!isNativeAvailable) return
        try {
            nativeCleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }
        isNativeAvailable = false
    }

    // Native JNI methods
    private external fun nativeInit(assetManager: AssetManager, modelPath: String, width: Int, height: Int): Boolean
    private external fun nativeRender(width: Int, height: Int)
    private external fun nativeOnTouch(x: Float, y: Float)
    private external fun nativePlayMotion(group: String, index: Int)
    private external fun nativeSetParameter(paramId: String, value: Float)
    private external fun nativeGetParameter(paramId: String): Float
    private external fun nativeCleanup()
    private external fun nativeIsAvailable(): Boolean
}
