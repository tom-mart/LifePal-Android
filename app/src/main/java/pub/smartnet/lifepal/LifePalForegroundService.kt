package pub.smartnet.lifepal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class LifePalForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "lifepal_sync_channel"
        private const val CHANNEL_NAME = "LifePal Data Sync"
        
        @Volatile
        private var isServiceRunning = false

        fun startService(context: Context) {
            if (isServiceRunning) {
                Log.d("LifePalForegroundService", "Service already running, skipping duplicate start")
                return
            }
            
            val intent = Intent(context, LifePalForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d("LifePalForegroundService", "Service start requested")
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LifePalForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.d("LifePalForegroundService", "Service created")
        
        try {
            createNotificationChannel()
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d("LifePalForegroundService", "Notification posted successfully")
            scheduleWorkers()
        } catch (e: Exception) {
            Log.e("LifePalForegroundService", "Failed to start foreground service", e)
            isServiceRunning = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LifePalForegroundService", "Service started")
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Delete old channel if it exists to force recreation with new settings
            notificationManager.deleteNotificationChannel(CHANNEL_ID)
            
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // LOW = minimal notification, won't make sound
            ).apply {
                description = "LifePal syncs health data every 15 minutes"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET // Hide on lock screen
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifePal sync")
            .setContentText("Syncing every 15 min")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW) // LOW = minimal notification
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun scheduleWorkers() {
        // No network constraint - workers run even offline and queue data
        val constraints = Constraints.Builder()
            .build()

        // Health Data Worker
        val healthWorkRequest = PeriodicWorkRequestBuilder<HealthDataWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5,
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "healthDataSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            healthWorkRequest
        )

        // Contextual Data Worker
        val contextualWorkRequest = PeriodicWorkRequestBuilder<ContextualDataWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5,
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "contextualDataSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            contextualWorkRequest
        )

        Log.d("LifePalForegroundService", "Workers scheduled with 15-minute interval")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.d("LifePalForegroundService", "Service destroyed")
    }
}
