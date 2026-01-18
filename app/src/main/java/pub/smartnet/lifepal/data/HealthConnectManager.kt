package pub.smartnet.lifepal.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneOffset

enum class HealthConnectAvailability {
    AVAILABLE,
    NOT_SUPPORTED,
    NOT_INSTALLED,
    NEEDS_UPDATE
}

class HealthConnectManager(private val context: Context) {
    private val healthConnectClient: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val providerPackageName = "com.google.android.apps.healthdata"


    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),

        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        // Write permissions
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    )

    init {
        Log.d("HealthConnectManager", "Permissions to request: ${permissions.size}")
        permissions.forEach { permission ->
            Log.d("HealthConnectManager", "Permission: $permission")
        }
    }

    fun checkAvailability(): HealthConnectAvailability {
        return when (HealthConnectClient.getSdkStatus(context, providerPackageName)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectAvailability.NOT_INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_UPDATE
            else -> HealthConnectAvailability.NOT_SUPPORTED
        }
    }

    suspend fun getGrantedPermissions(): Set<String> {
        return healthConnectClient.permissionController.getGrantedPermissions()
    }

    suspend fun hasAllPermissions(): Boolean {
        val grantedPermissions = getGrantedPermissions()
        val hasAll = grantedPermissions.containsAll(permissions)
        
        Log.d("HealthConnectManager", "Requested permissions: ${permissions.size}")
        Log.d("HealthConnectManager", "Granted permissions: ${grantedPermissions.size}")
        
        if (!hasAll) {
            val missing = permissions.filterNot { it in grantedPermissions }
            Log.w("HealthConnectManager", "Missing permissions: $missing")
        }
        
        return hasAll
    }

    suspend fun readData(startTime: Instant, endTime: Instant): Map<String, List<*>> {
        val allData = mutableMapOf<String, List<*>>()
        try {
            allData["steps"] = readSteps(startTime, endTime)
            allData["heart_rate"] = readHeartRate(startTime, endTime)
            allData["exercise_sessions"] = readExerciseSessions(startTime, endTime)
            allData["sleep_sessions"] = readSleepSessions(startTime, endTime)
            allData["total_calories"] = readTotalCalories(startTime, endTime)
            allData["distance"] = readDistance(startTime, endTime)
            allData["weight"] = readWeight(startTime, endTime)
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Error reading data", e)
        }
        return allData
    }

    private suspend fun readSteps(startTime: Instant, endTime: Instant): List<StepsRecord> {
        val request = ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        return healthConnectClient.readRecords(request).records
    }

    private suspend fun readHeartRate(startTime: Instant, endTime: Instant): List<HeartRateRecord> {
        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        return healthConnectClient.readRecords(request).records
    }

    private suspend fun readExerciseSessions(startTime: Instant, endTime: Instant): List<Pair<ExerciseSessionRecord, ExerciseRoute?>> {
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                dataOriginFilter = emptySet() // Read from all sources
            )
        )
        
        Log.d("HealthConnectManager", "Read ${response.records.size} exercise sessions")
        
        return response.records.map { session ->
            Log.d("HealthConnectManager", "Processing session: ${session.metadata.id}, title: ${session.title}, exerciseType: ${session.exerciseType}")
            Log.d("HealthConnectManager", "Session start: ${session.startTime}, end: ${session.endTime}")
            Log.d("HealthConnectManager", "Session data origin: ${session.metadata.dataOrigin.packageName}")
            
            val route = when (val result = session.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> {
                    val routePoints = result.exerciseRoute.route.size
                    Log.d("HealthConnectManager", "✓ Session has GPS route with $routePoints points")
                    result.exerciseRoute
                }
                is ExerciseRouteResult.ConsentRequired -> {
                    Log.w("HealthConnectManager", "⚠ GPS route CONSENT REQUIRED for session ${session.metadata.id} from ${session.metadata.dataOrigin.packageName}")
                    null
                }
                is ExerciseRouteResult.NoData -> {
                    Log.d("HealthConnectManager", "✗ Session has NoData for route")
                    null
                }
                else -> {
                    Log.w("HealthConnectManager", "? Unknown or null route result")
                    null
                }
            }
            Pair(session, route)
        }
    }

    suspend fun readWeight(startTime: Instant, endTime: Instant): List<WeightRecord> {
        val request = ReadRecordsRequest(
            recordType = WeightRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        return healthConnectClient.readRecords(request).records
    }

    private suspend fun readSleepSessions(startTime: Instant, endTime: Instant): List<SleepSessionRecord> {
        return healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        ).records
    }

    private suspend fun readTotalCalories(startTime: Instant, endTime: Instant): List<TotalCaloriesBurnedRecord> {
        return healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = TotalCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        ).records
    }

    private suspend fun readDistance(startTime: Instant, endTime: Instant): List<DistanceRecord> {
        return healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = DistanceRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        ).records
    }
    
    // Write methods for backend-triggered data
    suspend fun writeWeight(weightKg: Double, timestamp: Instant): Boolean {
        return try {
            val weightRecord = WeightRecord(
                weight = androidx.health.connect.client.units.Mass.kilograms(weightKg),
                time = timestamp,
                zoneOffset = ZoneOffset.UTC,
                metadata = Metadata.manualEntry()
            )
            healthConnectClient.insertRecords(listOf(weightRecord))
            Log.d("HealthConnectManager", "Successfully wrote weight: $weightKg kg at $timestamp")
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to write weight: ${e.message}", e)
            false
        }
    }
    
    suspend fun writeExerciseSession(
        activityType: Int,
        startTime: Instant,
        endTime: Instant,
        title: String? = null,
        notes: String? = null
    ): Boolean {
        return try {
            val exerciseSession = ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = ZoneOffset.UTC,
                endTime = endTime,
                endZoneOffset = ZoneOffset.UTC,
                exerciseType = activityType,
                title = title,
                notes = notes,
                metadata = Metadata.manualEntry(
                    device = Device(type = Device.TYPE_PHONE)
                )
            )
            healthConnectClient.insertRecords(listOf(exerciseSession))
            Log.d("HealthConnectManager", "Successfully wrote exercise: type=$activityType from $startTime to $endTime")
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to write exercise session: ${e.message}", e)
            false
        }
    }
}
