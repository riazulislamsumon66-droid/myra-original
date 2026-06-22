package com.maya.assistant.voice

import android.content.Context
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import com.maya.assistant.utils.Constants
import com.maya.assistant.service.MayaCharacterService

object VoiceStateManager {

    val state = MutableLiveData(Constants.STATE_IDLE)
    val statusMessage = MutableLiveData("SYSTEM READY")
    val amplitude = MutableLiveData(0f)
    val isMicMuted = MutableLiveData(false)

    fun setListening() {
        state.postValue(Constants.STATE_LISTENING)
        statusMessage.postValue("LISTENING...")
        isMicMuted.postValue(false)
    }

    fun setThinking() {
        state.postValue(Constants.STATE_THINKING)
        statusMessage.postValue("THINKING...")
    }

    fun setSpeaking() {
        state.postValue(Constants.STATE_SPEAKING)
        statusMessage.postValue("SPEAKING...")
        isMicMuted.postValue(true)
    }

    fun setIdle() {
        state.postValue(Constants.STATE_IDLE)
        statusMessage.postValue("SYSTEM READY")
        isMicMuted.postValue(false)
        amplitude.postValue(0f)
    }

    fun setError(msg: String) {
        state.postValue(Constants.STATE_IDLE)
        statusMessage.postValue(msg)
    }

    fun updateAmplitude(rms: Float) {
        amplitude.postValue(rms.coerceIn(0f, 1f))
    }

    fun isAiSpeaking() = state.value == Constants.STATE_SPEAKING

    fun isListening() = state.value == Constants.STATE_LISTENING

    // ── Character Notifications ──────────────────────────────────────
    private fun safeNotify(ctx: Context, action: String, extraState: String? = null, extraMode: String? = null) {
        try {
            Intent(ctx, MayaCharacterService::class.java).apply {
                this.action = action
                extraState?.let { putExtra(MayaCharacterService.EXTRA_STATE, it) }
                extraMode?.let { putExtra(MayaCharacterService.EXTRA_MODE, it) }
            }.also { ctx.startService(it) }
        } catch (e: Exception) {
            // Service not ready, skip notification
        }
    }
    
    fun notifyCharacterListening(ctx: Context) {
        safeNotify(ctx, MayaCharacterService.ACTION_SET_STATE, MayaCharacterService.Companion.State.LISTENING.name)
        safeNotify(ctx, MayaCharacterService.ACTION_WAKE)
    }

    fun notifyCharacterTalking(ctx: Context) {
        safeNotify(ctx, MayaCharacterService.ACTION_SET_STATE, MayaCharacterService.Companion.State.TALKING.name)
        safeNotify(ctx, MayaCharacterService.ACTION_WAKE)
    }

    fun notifyCharacterThinking(ctx: Context) {
        safeNotify(ctx, MayaCharacterService.ACTION_SET_STATE, MayaCharacterService.Companion.State.THINKING.name)
    }

    fun notifyCharacterIdle(ctx: Context) {
        safeNotify(ctx, MayaCharacterService.ACTION_SET_STATE, MayaCharacterService.Companion.State.IDLE.name)
    }
}
