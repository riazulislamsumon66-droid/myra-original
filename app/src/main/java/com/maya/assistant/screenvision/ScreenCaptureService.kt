package com.maya.assistant.screenvision

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maya.assistant.R
import com.maya.assistant.ui.main.MainActivity

/**
 * Foreground service that holds the MediaProjection token and manages screen capture.
 * Started by MainActivity after user grants screen capture permission.
 * Stops when user disables Screen Vision or app is destroyed.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "SCREEN_SVC"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "maya_screen_vision"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        var captureManager: ScreenCaptureManager? = null
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }

        fun isRunning(): Boolean = captureManager != null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == -1 || data == null) {
            android.util.Log.e(TAG, "Missing resultCode or data — cannot start capture")
            stopSelf()
            return START_NOT_STICKY
        }

        // Create notification channel & start foreground
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Get MediaProjection
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)

        if (projection == null) {
            android.util.Log.e(TAG, "MediaProjection is null — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Get display metrics
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val dpi = displayMetrics.densityDpi

        // Start capture manager
        captureManager = ScreenCaptureManager(this)
        captureManager?.startCapture(projection, width, height, dpi)

        android.util.Log.d(TAG, "ScreenCaptureService started: ${width}x${height}@${dpi}dpi")
        return START_STICKY
    }

    override fun onDestroy() {
        captureManager?.stop()
        captureManager = null
        android.util.Log.d(TAG, "ScreenCaptureService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAYA Screen Vision")
            .setContentText("Screen analysis active 🔍")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MAYA Screen Vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
