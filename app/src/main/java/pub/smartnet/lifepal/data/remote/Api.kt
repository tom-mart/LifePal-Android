@file:OptIn(ExperimentalSerializationApi::class)

package pub.smartnet.lifepal.data.remote

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pub.smartnet.lifepal.data.TokenManager

// Authentication
@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val username: String, val refresh: String, val access: String)

@Serializable
data class RefreshRequest(val refresh: String)

@Serializable
data class RefreshResponse(val refresh: String, val access: String)

@Serializable
data class VerifyRequest(val token: String)

@Serializable
data class DeviceTokenRequest(val token: String)

// Chat
@Serializable
data class Agent(
    val id: Int,
    val name: String,
    val model_name: String,
    val system_prompt: String
)

@Serializable
data class StreamRequest(
    val message: String,
    val agent_id: Int? = null,
    val conversation_id: Int? = null
)

@Serializable
data class StreamData(
    val type: String,
    val content: String? = null,
    val conversation_id: Int? = null,
    val agent_id: Int? = null,
    val agent_name: String? = null
)

// Health Data
@Serializable
data class HealthMetricData(
    val count: Long? = null,
    val kilocalories: Double? = null,
    val samples: List<HeartRateSample>? = null,
    val start_time: String? = null,
    val end_time: String? = null,
    val duration_minutes: Long? = null,
    val stages: List<SleepStage>? = null,
    val session_id: String? = null,
    val activity_type: String? = null,
    val distance_meters: Double? = null,
    val heart_rate_samples: List<HeartRateSample>? = null,
    val track_points: List<TrackPoint>? = null
)

@Serializable
data class HeartRateSample(val bpm: Long, val timestamp: String)

@Serializable
data class SleepStage(val stage: String, val start_time: String, val end_time: String)

@Serializable
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestamp: String,
    val accuracy_meters: Float?,
    val speed_mps: Float?
)

@Serializable
data class HealthMetric(
    val type: String,
    val timestamp: String,
    val data: HealthMetricData
)

@Serializable
data class HealthDataPayload(val metrics: List<HealthMetric>)


// Contextual Data
@Serializable
data class AppUsage(
    val app_name: String,
    val total_time_seconds: Long
)

@Serializable
data class ContextualMetricData(
    val total_foreground_time_seconds: Long? = null,
    val usage: List<AppUsage>? = null,
    val is_charging: Boolean? = null,
    val battery_percent: Int? = null,
    val is_dnd_on: Boolean? = null,
    val is_headset_connected: Boolean? = null,
    val screen_unlocks: Int? = null,
    val screen_time_seconds: Long? = null,
    val activity: String? = null,
    val confidence: Int? = null,
    val ambient_light_lux: Float? = null,
    val atmospheric_pressure_hpa: Float? = null,
    val altitude_meters: Float? = null,
    val sensor_timeout: Boolean? = null,
    val timeout_reason: String? = null,
    val sensor_available: Boolean? = null
)

@Serializable
data class ContextualMetric(
    val type: String,
    val timestamp: String,
    val data: ContextualMetricData
)

@Serializable
data class ContextualDataPayload(
    val timestamp: String,
    val metrics: List<ContextualMetric>
)


class ApiClient(private val tokenManager: TokenManager) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }
    private var serverAddress: String? = null

    suspend fun init() {
        serverAddress = tokenManager.getServerAddress()
    }

    suspend fun setServerAddress(address: String) {
        serverAddress = address
        tokenManager.saveServerAddress(address)
    }

    private fun requireServerAddress(): String {
        return serverAddress ?: throw IllegalStateException("Server address has not been set.")
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 900000
            connectTimeoutMillis = 900000
            socketTimeoutMillis = 900000
        }
        expectSuccess = false

        install(Auth) {
            bearer {
                loadTokens {
                    val accessToken = tokenManager.getAccessToken()
                    val refreshToken = tokenManager.getRefreshToken()
                    if (accessToken != null && refreshToken != null) {
                        BearerTokens(accessToken, refreshToken)
                    } else {
                        null
                    }
                }

                refreshTokens {
                    val refreshToken = tokenManager.getRefreshToken() ?: return@refreshTokens null
                    val response = client.post("${requireServerAddress()}/api/token/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(refreshToken))
                        markAsRefreshTokenRequest()
                    }

                    if (response.status.isSuccess()) {
                        val refreshResponse: RefreshResponse = response.body()
                        tokenManager.saveTokens(refreshResponse.access, refreshResponse.refresh)
                        BearerTokens(refreshResponse.access, refreshResponse.refresh)
                    } else {
                        tokenManager.clearSessionData()
                        null
                    }
                }
            }
        }
    }

    suspend fun login(request: LoginRequest): LoginResponse {
        return client.post("${requireServerAddress()}/api/token/pair") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }
    
    suspend fun registerDeviceToken(token: String) {
        try {
            client.post("${requireServerAddress()}/api/notifications/device/register") {
                contentType(ContentType.Application.Json)
                setBody(DeviceTokenRequest(token))
            }
        } catch (e: Exception) {
            // Ignore for now, or log it
        }
    }

    suspend fun getAgents(): List<Agent> {
        return client.get("${requireServerAddress()}/api/chat/agents").body()
    }

    fun chatStream(request: StreamRequest): Flow<StreamData> = flow {
        client.preparePost("${requireServerAddress()}/api/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.startsWith("data: ")) {
                    val jsonStr = line.removePrefix("data: ")
                    try {
                        val data = json.decodeFromString<StreamData>(jsonStr)
                        emit(data)
                    } catch (e: Exception) {
                        // ignore parse errors
                    }
                }
            }
        }
    }

    suspend fun sendHealthData(payload: HealthDataPayload) {
        client.post("${requireServerAddress()}/api/data/health") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body<Unit>()
    }

    suspend fun sendContextualData(payload: ContextualDataPayload) {
        client.post("${requireServerAddress()}/api/data/contextual") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body<Unit>()
    }
}
