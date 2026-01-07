package pub.smartnet.lifepal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted, starting foreground service")
            
            // Start foreground service which will handle worker scheduling
            LifePalForegroundService.startService(context)
            
            Log.d("BootReceiver", "Foreground service started on boot")
        }
    }

    private fun scheduleHealthDataWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<HealthDataWorker>(
            repeatInterval = 21,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5,
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "healthDataSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d("BootReceiver", "Health data worker scheduled")
    }

    private fun scheduleContextualDataWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ContextualDataWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5,
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "contextualDataSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d("BootReceiver", "Contextual data worker scheduled")
    }
}
