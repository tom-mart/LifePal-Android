package pub.smartnet.lifepal

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pub.smartnet.lifepal.data.HealthConnectManager
import pub.smartnet.lifepal.data.SyncTimeTracker
import pub.smartnet.lifepal.data.TokenManager
import pub.smartnet.lifepal.data.db.AppDatabase
import pub.smartnet.lifepal.data.db.QueuedHealthData
import pub.smartnet.lifepal.data.remote.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthDataWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val healthConnectManager = HealthConnectManager(appContext)
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
        Log.d("HealthDataWorker", "doWork() called - Worker is running")
        
        if (!healthConnectManager.hasAllPermissions()) {
            Log.w("HealthDataWorker", "Missing permissions, skipping this run")
            return Result.success() // Return success so worker continues to run
        }
        Log.d("HealthDataWorker", "Starting work with permissions granted")

        return try {
            // Step 1: Try to send all queued data first
            sendQueuedData()
            
            // Step 2: Collect new data
            val metrics = collectHealthData()
            
            // Step 3: Save to database with timestamp
            if (metrics.isNotEmpty()) {
                val payload = HealthDataPayload(metrics)
                val payloadJson = json.encodeToString(payload)
                val timestamp = metrics.firstOrNull()?.timestamp ?: Instant.now().toString()
                database.queuedDataDao().insertHealthData(
                    QueuedHealthData(
                        timestamp = timestamp,
                        payload = payloadJson
                    )
                )
                Log.d("HealthDataWorker", "Saved data to queue. Queue size: ${database.queuedDataDao().getHealthDataCount()}")
            }
            
            // Step 4: Try to send queued data (including what we just saved)
            sendQueuedData()

            Result.success()
        } catch (e: Exception) {
            Log.e("HealthDataWorker", "Work failed: ${e.message}", e)
            // Don't retry - data is safely queued in database
            Result.success()
        }
    }
    
    private suspend fun sendQueuedData() {
        try {
            val queuedData = database.queuedDataDao().getAllHealthData()
            if (queuedData.isEmpty()) {
                Log.d("HealthDataWorker", "No queued data to send")
                return
            }
            
            Log.d("HealthDataWorker", "Attempting to send ${queuedData.size} queued items")
            apiClient.init()
            
            for (item in queuedData) {
                try {
                    val payload = json.decodeFromString<HealthDataPayload>(item.payload)
                    apiClient.sendHealthData(payload)
                    database.queuedDataDao().deleteHealthData(item.id)
                    Log.d("HealthDataWorker", "Successfully sent and deleted item ${item.id}")
                } catch (e: Exception) {
                    Log.w("HealthDataWorker", "Failed to send item ${item.id}: ${e.message}")
                    // Stop trying to send more if one fails (likely network issue)
                    break
                }
            }
            
            val remaining = database.queuedDataDao().getHealthDataCount()
            Log.d("HealthDataWorker", "Queue size after send attempt: $remaining")
        } catch (e: Exception) {
            Log.w("HealthDataWorker", "Failed to send queued data: ${e.message}")
        }
    }
    
    private suspend fun collectHealthData(): List<HealthMetric> {
        // Check if this is a full sync (7 days) or regular daily sync
        val daysToSync = inputData.getInt("DAYS_TO_SYNC", 1)
        
        val startTime = if (daysToSync > 1) {
            // Full sync: query specified number of days
            Instant.now().minus(daysToSync.toLong(), ChronoUnit.DAYS)
        } else {
            // Daily sync: from midnight to now (like contextual data)
            getStartOfToday()
        }
        val endTime = Instant.now()
        
        Log.d("HealthDataWorker", "Syncing from $startTime to $endTime (${if (daysToSync > 1) "$daysToSync days" else "today only"})")

        val rawData = healthConnectManager.readData(startTime, endTime)
        val metrics = mutableListOf<HealthMetric>()

            // Transform Steps - ALWAYS send, even if 0
            val stepsRecords = rawData["steps"]?.filterIsInstance<StepsRecord>()
            val totalSteps = stepsRecords?.sumOf { it.count } ?: 0
            
            // Always add STEPS metric with aggregated count for the time period
            metrics.add(HealthMetric("STEPS", endTime.toString(), HealthMetricData(
                count = totalSteps,
                start_time = startTime.toString(),
                end_time = endTime.toString()
            )))
            
            if (stepsRecords.isNullOrEmpty()) {
                Log.d("HealthDataWorker", "STEPS: No records found, sending 0 steps")
            } else {
                Log.d("HealthDataWorker", "STEPS: Found ${stepsRecords.size} records, total steps: $totalSteps")
            }

            // Transform Heart Rate
            val heartRateRecords = rawData["heart_rate"]?.filterIsInstance<HeartRateRecord>()
            val heartRateSamples = heartRateRecords?.flatMap { it.samples }
            if (!heartRateSamples.isNullOrEmpty()) {
                metrics.add(HealthMetric("HEART_RATE", endTime.toString(), HealthMetricData(
                    samples = heartRateSamples.map { HeartRateSample(it.beatsPerMinute, it.time.toString()) }
                )))
                syncTimeTracker.updateSyncTime("HEART_RATE")
                Log.d("HealthDataWorker", "HEART_RATE: Found ${heartRateSamples.size} samples")
            }

            // Transform Exercise Sessions
            val exerciseSessions = rawData["exercise_sessions"]?.filterIsInstance<Pair<ExerciseSessionRecord, ExerciseRoute?>>()
            val heartRateRecordsAll = rawData["heart_rate"]?.filterIsInstance<HeartRateRecord>() ?: emptyList()
            val distanceRecords = rawData["distance"]?.filterIsInstance<DistanceRecord>() ?: emptyList()
            val caloriesRecords = rawData["total_calories"]?.filterIsInstance<TotalCaloriesBurnedRecord>() ?: emptyList()
            
            Log.d("HealthDataWorker", "Found ${exerciseSessions?.size ?: 0} exercise sessions")
            
            exerciseSessions?.forEach { (sessionRecord, routeRecord) ->
                Log.d("HealthDataWorker", "Processing session ${sessionRecord.metadata.id}")
                Log.d("HealthDataWorker", "Route record is: ${if (routeRecord == null) "NULL" else "PRESENT with ${routeRecord.route.size} points"}")
                
                val trackPoints = routeRecord?.route?.map { location ->
                    TrackPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude?.inMeters,
                        timestamp = location.time.toString(),
                        accuracy_meters = location.horizontalAccuracy?.inMeters?.toFloat(),
                        speed_mps = null
                    )
                }
                
                if (routeRecord != null) {
                    Log.d("HealthDataWorker", "✓ Session has GPS route with ${trackPoints?.size ?: 0} points")
                } else {
                    Log.d("HealthDataWorker", "✗ Session has no GPS route")
                }
                
                // Calculate duration in minutes
                val durationMinutes = java.time.Duration.between(
                    sessionRecord.startTime,
                    sessionRecord.endTime
                ).toMinutes()
                
                // Find heart rate data during this exercise session
                val sessionHeartRate = heartRateRecordsAll
                    .filter { hr ->
                        hr.samples.any { sample ->
                            !sample.time.isBefore(sessionRecord.startTime) && 
                            !sample.time.isAfter(sessionRecord.endTime)
                        }
                    }
                    .flatMap { it.samples }
                    .filter { sample ->
                        !sample.time.isBefore(sessionRecord.startTime) && 
                        !sample.time.isAfter(sessionRecord.endTime)
                    }
                    .map { HeartRateSample(it.beatsPerMinute, it.time.toString()) }
                
                // Find distance during this exercise session
                val sessionDistance = distanceRecords
                    .filter { d ->
                        !d.startTime.isBefore(sessionRecord.startTime) && 
                        !d.endTime.isAfter(sessionRecord.endTime)
                    }
                    .sumOf { it.distance.inMeters }
                
                // Find calories during this exercise session
                val sessionCalories = caloriesRecords
                    .filter { c ->
                        !c.startTime.isBefore(sessionRecord.startTime) && 
                        !c.endTime.isAfter(sessionRecord.endTime)
                    }
                    .sumOf { it.energy.inKilocalories }

                metrics.add(HealthMetric("EXERCISE_SESSION", sessionRecord.endTime.toString(), HealthMetricData(
                    session_id = sessionRecord.metadata.id,
                    activity_type = mapExerciseTypeToString(sessionRecord.exerciseType),
                    start_time = sessionRecord.startTime.toString(),
                    end_time = sessionRecord.endTime.toString(),
                    duration_minutes = durationMinutes,
                    distance_meters = if (sessionDistance > 0) sessionDistance else null,
                    kilocalories = if (sessionCalories > 0) sessionCalories else null,
                    heart_rate_samples = if (sessionHeartRate.isNotEmpty()) sessionHeartRate else null,
                    track_points = trackPoints
                )))
            }
            if (!exerciseSessions.isNullOrEmpty()) {
                syncTimeTracker.updateSyncTime("EXERCISE_SESSION")
                Log.d("HealthDataWorker", "EXERCISE_SESSION: Processed ${exerciseSessions.size} sessions")
            }
            
            // Transform Sleep Sessions
            rawData["sleep_sessions"]?.filterIsInstance<SleepSessionRecord>()?.forEach { record ->
                val durationMinutes = java.time.Duration.between(
                    record.startTime,
                    record.endTime
                ).toMinutes()
                
                val stages = record.stages.map { stage ->
                    SleepStage(
                        stage = mapSleepStageToString(stage.stage),
                        start_time = stage.startTime.toString(),
                        end_time = stage.endTime.toString()
                    )
                }
                
                metrics.add(HealthMetric("SLEEP_SESSION", record.endTime.toString(), HealthMetricData(
                    start_time = record.startTime.toString(),
                    end_time = record.endTime.toString(),
                    duration_minutes = durationMinutes,
                    stages = stages
                )))
            }

            if (metrics.isNotEmpty()) {
                Log.d("HealthDataWorker", "Collected ${metrics.size} metrics")
            } else {
                Log.d("HealthDataWorker", "No new health metrics found in time window.")
            }
            
            return metrics
    }
    
    private fun mapExerciseTypeToString(exerciseType: Int): String {
        return when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "BADMINTON"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "BIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING_POOL"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH_TRAINING"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "YOGA"
            // Add other exercise types as needed
            else -> "UNKNOWN"
        }
    }
    
    private fun mapSleepStageToString(stage: Int): String {
        return when (stage) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
            SleepSessionRecord.STAGE_TYPE_REM -> "REM"
            else -> "UNKNOWN"
        }
    }
}
