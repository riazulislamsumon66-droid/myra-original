package com.maya.assistant.ui.main

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * Simple waveform visualization placeholder.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var amplitude: Float = 0f

    fun updateAmplitude(amp: Float) {
        amplitude = amp
        invalidate()
    }
}
