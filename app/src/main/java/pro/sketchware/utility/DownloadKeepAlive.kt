package pro.sketchware.utility

import android.content.Context
import android.os.PowerManager
import android.view.Window
import android.view.WindowManager

/**
 * Keeps the device awake while dependencies are being downloaded.
 *
 * - [keepScreenOn] stops the screen from turning off while the download dialog is visible.
 * - [acquire] holds a partial wake lock, so the CPU keeps running if the screen is turned off
 *   or the app is in the background for a short moment.
 *
 * A timeout is always set, so the lock can never be held forever by accident.
 * Always call [release] when the download finished, failed, or the dialog is destroyed.
 */
class DownloadKeepAlive(context: Context) {
    private val appContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null

    fun keepScreenOn(window: Window?) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun allowScreenOff(window: Window?) {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Synchronized
    fun acquire(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                setReferenceCounted(false)
                acquire(timeoutMillis)
            }
        } catch (e: Exception) {
            // A missing wake lock must never break the download itself
            wakeLock = null
        }
    }

    @Synchronized
    fun release() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // Already released by the timeout
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "Sketchware:DependencyDownload"
        private const val DEFAULT_TIMEOUT_MILLIS = 30L * 60 * 1000
    }
}
