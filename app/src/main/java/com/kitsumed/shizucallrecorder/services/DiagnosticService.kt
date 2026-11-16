package com.kitsumed.shizucallrecorder.services

import android.app.*
import android.content.*
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kitsumed.shizucallrecorder.MainActivity
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiagnosticState(val running: Boolean = false, val lastSample: Long = 0, val error: String? = null)

/** User-started foreground session; never restarts itself after process death. */
class DiagnosticService : Service() {
    companion object {
        private val mutableState = MutableStateFlow(DiagnosticState())
        val state = mutableState.asStateFlow()
        private const val CHANNEL = "diagnostics"
        private const val NOTIFICATION_ID = 8104
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DiagnosticService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, DiagnosticService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (capture?.isActive == true) return START_NOT_STICKY
        try {
            showNotification()
        } catch (failure: RuntimeException) {
            mutableState.value = DiagnosticState(error = failure.toString())
            AppPreferences(this).setLoggingEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        AppPreferences(this).setLoggingEnabled(true)
        mutableState.value = DiagnosticState(running = true)
        AppLogger.i("Diagnostic session started; elapsed=${android.os.SystemClock.elapsedRealtime()}")
        capture = scope.launch {
            while (isActive) {
                captureSnapshot()
                delay(20_000)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun captureSnapshot() {
        val connection = ShizukuConnectionManager(applicationContext)
        try {
            check(ShizukuConnectionManager.isAvailable()) { "Shizuku is not running" }
            check(ShizukuConnectionManager.hasPermission()) { "Shizuku permission is missing" }
            val shell = withContext(Dispatchers.Main) { withTimeout(10_000) { connection.getShellService() } }
            val snapshot = shell.collectDiagnosticSnapshot()
            currentCoroutineContext().ensureActive()
            AppLogger.appendDiagnosticSnapshot(snapshot)
            val completedCommands = Regex("\\[exit=0\\]").findAll(snapshot).count()
            mutableState.value = DiagnosticState(
                running = true,
                lastSample = System.currentTimeMillis(),
                error = if (completedCommands == 3) null else "Some Shizuku commands failed or reached a limit; details saved in shizuku.log"
            )
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException && currentCoroutineContext().isActive) {
                reportFailure("Shizuku connection timed out")
            } else throw cancelled
        } catch (failure: Exception) {
            // External Binder, OEM service, and filesystem failures must not kill app logging.
            reportFailure(failure.toString())
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { connection.unbind() }
        }
    }

    private fun reportFailure(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
        AppLogger.w("Diagnostic snapshot failed; app logs continue; retry in 20 seconds: $message")
    }

    private fun showNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.diagnostics_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, DiagnosticService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.diagnostics_active))
            .setContentText(getString(R.string.diagnostics_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.diagnostics_stop), stop).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        AppLogger.i("Diagnostic session stopped; saved files retained")
        AppPreferences(this).setLoggingEnabled(false)
        mutableState.value = mutableState.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
