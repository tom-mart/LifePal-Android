package pub.smartnet.lifepal

import android.app.Application
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class LifePalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("LifePalApplication", "Application starting")
        
        // Start foreground service for reliable background work
        LifePalForegroundService.startService(this)
        
        Log.d("LifePalApplication", "Foreground service started")
    }

    private fun runWorkersImmediately() {
        val healthConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateHealthWork = OneTimeWorkRequestBuilder<HealthDataWorker>()
            .setConstraints(healthConstraints)
            .build()

        val immediateContextualWork = OneTimeWorkRequestBuilder<ContextualDataWorker>()
            .setConstraints(healthConstraints)
            .build()

        WorkManager.getInstance(this).apply {
            enqueue(immediateHealthWork)
            enqueue(immediateContextualWork)
        }
        
        Log.d("LifePalApplication", "Immediate workers queued for execution")
    }

    private fun scheduleHealthDataWorker() {
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

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "healthDataSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d("LifePalApplication", "Health data worker scheduled with ID: ${workRequest.id}")
    }

    private fun scheduleContextualDataWorker() {
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

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "contextualDataSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d("LifePalApplication", "Contextual data worker scheduled with ID: ${workRequest.id}")
    }
}
