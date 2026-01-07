package pub.smartnet.lifepal

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.DetectedActivity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pub.smartnet.lifepal.data.ContextualDataManager
import pub.smartnet.lifepal.data.SyncTimeTracker
import pub.smartnet.lifepal.data.TokenManager
import pub.smartnet.lifepal.data.db.AppDatabase
import pub.smartnet.lifepal.data.db.QueuedContextualData
import pub.smartnet.lifepal.data.remote.ApiClient
import pub.smartnet.lifepal.data.remote.AppUsage
import pub.smartnet.lifepal.data.remote.ContextualDataPayload
import pub.smartnet.lifepal.data.remote.ContextualMetric
import pub.smartnet.lifepal.data.remote.ContextualMetricData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ContextualDataWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val contextualDataManager = ContextualDataManager(appContext)
    private val tokenManager = TokenManager(appContext)
    private val apiClient = ApiClient(tokenManager)
    private val syncTimeTracker = SyncTimeTracker(appContext)
    private val database = AppDatabase.getInstance(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    private fun getStartOfToday(): Instant {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }

    override suspend fun doWork(): Result {
        Log.d("ContextualDataWorker", "doWork() called - Worker is running")
        return try {
            // Step 1: Try to send all queued data first
            sendQueuedData()
            
            // Step 2: Collect new data
            val metrics = collectContextualData()
            
            // Step 3: Save to database with timestamp
            if (metrics.isNotEmpty()) {
                val payload = ContextualDataPayload(Instant.now().toString(), metrics)
                val payloadJson = json.encodeToString(payload)
                database.queuedDataDao().insertContextualData(
                    QueuedContextualData(
                        timestamp = payload.timestamp,
                        payload = payloadJson
                    )
                )
                Log.d("ContextualDataWorker", "Saved data to queue. Queue size: ${database.queuedDataDao().getContextualDataCount()}")
            }
            
            // Step 4: Try to send queued data (including what we just saved)
            sendQueuedData()

            Result.success()
        } catch (e: Exception) {
            Log.e("ContextualDataWorker", "Work failed: ${e.message}", e)
            // Don't retry - data is safely queued in database
            Result.success()
        }
    }
    
    private suspend fun sendQueuedData() {
        try {
            val queuedData = database.queuedDataDao().getAllContextualData()
            if (queuedData.isEmpty()) {
                Log.d("ContextualDataWorker", "No queued data to send")
                return
            }
            
            Log.d("ContextualDataWorker", "Attempting to send ${queuedData.size} queued items")
            apiClient.init()
            
            for (item in queuedData) {
                try {
                    val payload = json.decodeFromString<ContextualDataPayload>(item.payload)
                    apiClient.sendContextualData(payload)
                    database.queuedDataDao().deleteContextualData(item.id)
                    Log.d("ContextualDataWorker", "Successfully sent and deleted item ${item.id}")
                } catch (e: Exception) {
                    Log.w("ContextualDataWorker", "Failed to send item ${item.id}: ${e.message}")
                    // Stop trying to send more if one fails (likely network issue)
                    break
                }
            }
            
            val remaining = database.queuedDataDao().getContextualDataCount()
            Log.d("ContextualDataWorker", "Queue size after send attempt: $remaining")
        } catch (e: Exception) {
            Log.w("ContextualDataWorker", "Failed to send queued data: ${e.message}")
        }
    }
    
    private suspend fun collectContextualData(): List<ContextualMetric> {
        val metrics = mutableListOf<ContextualMetric>()
        var foundData = false

            // App Usage - from midnight to now (today only)
            if (contextualDataManager.hasUsageStatsPermission()) {
                val startOfToday = getStartOfToday()
                val now = Instant.now()
                val appUsageStats = contextualDataManager.getAppUsageStats(startOfToday.toEpochMilli(), now.toEpochMilli())
                if (appUsageStats.isNotEmpty()) {
                    foundData = true
                    val totalTime = appUsageStats.values.sum()
                    val usageList = appUsageStats.map { AppUsage(it.key, it.value / 1000) }
                    metrics.add(ContextualMetric("APP_USAGE", now.toString(), ContextualMetricData(
                        total_foreground_time_seconds = totalTime / 1000,
                        usage = usageList
                    )))
                    syncTimeTracker.updateSyncTime("APP_USAGE")
                    Log.d("ContextualDataWorker", "APP_USAGE: Found ${usageList.size} apps, total time: ${totalTime / 1000}s (today only)")
                }
            }

            // Device State
            val deviceState = contextualDataManager.getDeviceState()
            metrics.add(ContextualMetric("DEVICE_STATE", (deviceState["timestamp"] as Instant).toString(), ContextualMetricData(
                is_charging = deviceState["is_charging"] as Boolean,
                battery_percent = deviceState["battery_percent"] as Int,
                is_dnd_on = deviceState["is_dnd_on"] as Boolean,
                is_headset_connected = deviceState["is_headset_connected"] as Boolean
            )))
            
            // Screen Interaction Stats - from midnight to now (today only)
            if (contextualDataManager.hasUsageStatsPermission()) {
                val startOfToday = getStartOfToday()
                val now2 = Instant.now()
                val screenStats = contextualDataManager.getScreenInteractionStats(startOfToday.toEpochMilli(), now2.toEpochMilli())
                metrics.add(ContextualMetric("SCREEN_INTERACTION", (screenStats["timestamp"] as Instant).toString(), ContextualMetricData(
                    screen_unlocks = screenStats["screen_unlocks"] as Int,
                    screen_time_seconds = screenStats["screen_time_seconds"] as Long
                )))
                syncTimeTracker.updateSyncTime("SCREEN_INTERACTION")
                Log.d("ContextualDataWorker", "SCREEN_INTERACTION: ${screenStats["screen_unlocks"]} unlocks, ${screenStats["screen_time_seconds"]}s screen time (today only)")
            }
            
            // Environmental Sensors
            val sensors = contextualDataManager.getEnvironmentalSensors()
            metrics.add(ContextualMetric("ENVIRONMENTAL", (sensors["timestamp"] as Instant).toString(), ContextualMetricData(
                ambient_light_lux = sensors["ambient_light_lux"] as Float?,
                atmospheric_pressure_hpa = sensors["atmospheric_pressure_hpa"] as Float?,
                altitude_meters = sensors["altitude_meters"] as Float?
            )))
            syncTimeTracker.updateSyncTime("ENVIRONMENTAL")
            Log.d("ContextualDataWorker", "ENVIRONMENTAL: light=${sensors["ambient_light_lux"]} lux, pressure=${sensors["atmospheric_pressure_hpa"]} hPa, altitude=${sensors["altitude_meters"]} m")

            return metrics
        }

    private fun Int.toActivityName(): String {
        return when (this) {
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.ON_FOOT -> "ON_FOOT"
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            else -> "UNKNOWN"
        }
    }
}
