package pub.smartnet.lifepal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pub.smartnet.lifepal.data.HealthConnectManager
import pub.smartnet.lifepal.data.remote.ApiClient
import pub.smartnet.lifepal.data.TokenManager
import java.time.Instant

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val apiClient by lazy { ApiClient(TokenManager(applicationContext)) }
    private val healthConnectManager by lazy { HealthConnectManager(applicationContext) }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token received: $token")
        
        // This is a good place to send the token if the user is already logged in.
        // However, the most reliable approach is to send it upon login.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // We can try to register it here, but it might fail if the user is not logged in
                apiClient.registerDeviceToken(token)
            } catch (e: Exception) {
                Log.w("FCM", "Could not register token from onNewToken, will retry on next login.")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Handle data payload for Health Connect writes
        remoteMessage.data.let { data ->
            when (data["action"]) {
                "write_weight" -> handleWriteWeight(data)
                "write_exercise" -> handleWriteExercise(data)
                else -> {
                    // If no recognized action, show as notification
                    remoteMessage.notification?.let {
                        sendNotification(it.title, it.body)
                    }
                }
            }
        }
    }
    
    private fun handleWriteWeight(data: Map<String, String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val weightKg = data["weight_kg"]?.toDoubleOrNull()
                val timestampStr = data["timestamp"]
                
                if (weightKg == null) {
                    Log.e("FCM", "Invalid weight_kg value: ${data["weight_kg"]}")
                    return@launch
                }
                
                val timestamp = if (timestampStr.isNullOrBlank()) {
                    Instant.now()
                } else {
                    try {
                        Instant.parse(timestampStr)
                    } catch (e: Exception) {
                        Log.w("FCM", "Invalid timestamp, using current time: $timestampStr")
                        Instant.now()
                    }
                }
                
                val success = healthConnectManager.writeWeight(weightKg, timestamp)
                if (success) {
                    Log.d("FCM", "Successfully wrote weight from backend: $weightKg kg")
                } else {
                    Log.e("FCM", "Failed to write weight from backend")
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error handling write_weight", e)
            }
        }
    }
    
    private fun handleWriteExercise(data: Map<String, String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val activityTypeStr = data["activity_type"]
                val startTimeStr = data["start_time"]
                val endTimeStr = data["end_time"]
                val title = data["title"]
                val notes = data["notes"]
                
                if (activityTypeStr.isNullOrBlank() || startTimeStr.isNullOrBlank() || endTimeStr.isNullOrBlank()) {
                    Log.e("FCM", "Missing required exercise fields")
                    return@launch
                }
                
                val activityType = mapActivityTypeFromString(activityTypeStr)
                val startTime = Instant.parse(startTimeStr)
                val endTime = Instant.parse(endTimeStr)
                
                val success = healthConnectManager.writeExerciseSession(
                    activityType = activityType,
                    startTime = startTime,
                    endTime = endTime,
                    title = title,
                    notes = notes
                )
                
                if (success) {
                    Log.d("FCM", "Successfully wrote exercise from backend: $activityTypeStr")
                } else {
                    Log.e("FCM", "Failed to write exercise from backend")
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error handling write_exercise", e)
            }
        }
    }
    
    private fun mapActivityTypeFromString(type: String): Int {
        return when (type.uppercase()) {
            "RUNNING" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            "WALKING" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            "BIKING", "CYCLING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            "SWIMMING" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            "YOGA" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            "STRENGTH_TRAINING", "WEIGHTS" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            "BADMINTON" -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
            "BASKETBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
            "CRICKET" -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
            "DANCING" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
            "FOOTBALL", "SOCCER" -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN
            "GOLF" -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
            "HIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            "PILATES" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
            "ROWING" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
            "TENNIS" -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
            else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
    }

    private fun sendNotification(title: String?, messageBody: String?) {
        val channelId = "default_channel_id"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Default Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0, notificationBuilder.build())
    }
}
