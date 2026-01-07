package pub.smartnet.lifepal.data

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlin.coroutines.resume

class ContextualDataManager(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermissionIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    suspend fun getAppUsageStats(startTime: Long, endTime: Long): Map<String, Long> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        return stats.associate { it.packageName to it.totalTimeInForeground }
    }

    suspend fun getDeviceState(): Map<String, Any> {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) {
            (batteryLevel * 100 / batteryScale.toFloat()).toInt()
        } else {
            -1
        }

        // DND Detection
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isDndOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val filter = notificationManager.currentInterruptionFilter
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }

        // Headset Detection (wired + Bluetooth)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isWiredHeadsetOn = audioManager.isWiredHeadsetOn
        
        // Bluetooth detection - works on all Android versions
        val isBluetoothOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Check audio devices
            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        } else {
            // Android 11 and below
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
        
        val isHeadsetConnected = isWiredHeadsetOn || isBluetoothOn
        
        Log.d("ContextualDataManager", "Headset: wired=$isWiredHeadsetOn, bluetooth=$isBluetoothOn, connected=$isHeadsetConnected")

        return mapOf(
            "timestamp" to Instant.now(),
            "is_charging" to isCharging,
            "battery_percent" to batteryPercent,
            "is_dnd_on" to isDndOn,
            "is_headset_connected" to isHeadsetConnected
        )
    }

    suspend fun getScreenInteractionStats(startTime: Long, endTime: Long): Map<String, Any> {
        if (!hasUsageStatsPermission()) {
            return mapOf(
                "screen_unlocks" to 0,
                "screen_time_seconds" to 0L
            )
        }
        
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageStatsManager.queryEvents(startTime, endTime)
        
        var screenUnlocks = 0
        var lastScreenOnTime: Long? = null
        var totalScreenTime = 0L
        
        while (events.hasNextEvent()) {
            val event = android.app.usage.UsageEvents.Event()
            events.getNextEvent(event)
            
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenUnlocks++
                    lastScreenOnTime = event.timeStamp
                }
                android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    lastScreenOnTime?.let {
                        totalScreenTime += event.timeStamp - it
                        lastScreenOnTime = null
                    }
                }
            }
        }
        
        // If screen is still on, count time until now
        lastScreenOnTime?.let {
            totalScreenTime += endTime - it
        }
        
        return mapOf(
            "timestamp" to Instant.now(),
            "screen_unlocks" to screenUnlocks,
            "screen_time_seconds" to totalScreenTime / 1000
        )
    }

    suspend fun getEnvironmentalSensors(): Map<String, Any> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            
            // Check if sensors are available
            if (lightSensor == null && pressureSensor == null) {
                Log.w("ContextualDataManager", "No environmental sensors available")
                continuation.resume(mapOf(
                    "timestamp" to Instant.now(),
                    "ambient_light_lux" to 0.0f,
                    "atmospheric_pressure_hpa" to 1013.25f,
                    "altitude_meters" to 0.0f
                ))
                return@suspendCancellableCoroutine
            }
            
            var lightValue: Float? = null
            var pressureValue: Float? = null
            var sensorTimestamp: Instant? = null
            var hasResumed = false
            val expectedSensors = listOfNotNull(lightSensor, pressureSensor).size
            var receivedSensors = 0
            
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    Log.d("ContextualDataManager", "Sensor changed: ${event.sensor.type}, value: ${event.values[0]}")
                    when (event.sensor.type) {
                        Sensor.TYPE_LIGHT -> {
                            if (lightValue == null) {
                                lightValue = event.values[0]
                                sensorTimestamp = Instant.now()
                                receivedSensors++
                                Log.d("ContextualDataManager", "Light sensor: ${event.values[0]} lux")
                            }
                        }
                        Sensor.TYPE_PRESSURE -> {
                            if (pressureValue == null) {
                                pressureValue = event.values[0]
                                if (sensorTimestamp == null) sensorTimestamp = Instant.now()
                                receivedSensors++
                                Log.d("ContextualDataManager", "Pressure sensor: ${event.values[0]} hPa")
                            }
                        }
                    }
                    
                    // Resume once we have all available sensor values
                    if (receivedSensors >= expectedSensors && !hasResumed) {
                        hasResumed = true
                        sensorManager.unregisterListener(this)
                        
                        // Calculate altitude from pressure (standard atmosphere formula)
                        val pressure = pressureValue ?: 1013.25f
                        val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                        
                        continuation.resume(mapOf(
                            "timestamp" to (sensorTimestamp ?: Instant.now()),
                            "ambient_light_lux" to (lightValue ?: 0.0f),
                            "atmospheric_pressure_hpa" to pressure,
                            "altitude_meters" to altitude
                        ))
                    }
                }
                
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                    Log.d("ContextualDataManager", "Sensor accuracy changed: ${sensor.type}, accuracy: $accuracy")
                }
            }
            
            // Register listeners with FASTEST delay for quicker response
            lightSensor?.let { 
                val registered = sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d("ContextualDataManager", "Light sensor registration: $registered")
            } ?: run {
                // If no light sensor, we already got one reading
                receivedSensors++
            }
            
            pressureSensor?.let { 
                val registered = sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d("ContextualDataManager", "Pressure sensor registration: $registered")
            } ?: run {
                // If no pressure sensor, we already got one reading
                receivedSensors++
            }
            
            // Timeout after 5 seconds (increased from 2s for background execution)
            CoroutineScope(Dispatchers.Default).launch {
                delay(5000)
                if (!hasResumed) {
                    hasResumed = true
                    sensorManager.unregisterListener(listener)
                    Log.w("ContextualDataManager", "Sensor timeout - light: $lightValue, pressure: $pressureValue")
                    
                    val pressure = pressureValue ?: 1013.25f
                    val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                    
                    continuation.resume(mapOf(
                        "timestamp" to (sensorTimestamp ?: Instant.now()),
                        "ambient_light_lux" to (lightValue ?: 0.0f),
                        "atmospheric_pressure_hpa" to pressure,
                        "altitude_meters" to altitude
                    ))
                }
            }
            
            continuation.invokeOnCancellation {
                sensorManager.unregisterListener(listener)
            }
        }
    }
}
