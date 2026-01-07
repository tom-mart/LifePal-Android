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

enum class VerificationStatus {
    VALID,
    INVALID
}

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

class ApiClient(private val tokenManager: TokenManager) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        encodeDefaults = false 
    }
    private var serverAddress: String = "http://192.168.1.229:8080"

    fun setServerAddress(address: String) {
        serverAddress = address
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 60000
            socketTimeoutMillis = 60000
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
                    val response: RefreshResponse = client.post("$serverAddress/api/token/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(refreshToken))
                        markAsRefreshTokenRequest()
                    }.body()

                    tokenManager.saveTokens(response.access, response.refresh)
                    BearerTokens(response.access, response.refresh)
                }
            }
        }
    }

    suspend fun login(request: LoginRequest): LoginResponse {
        return client.post("$serverAddress/api/token/pair") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun refresh(request: RefreshRequest): RefreshResponse {
        return client.post("$serverAddress/api/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun verify(request: VerifyRequest): VerificationStatus {
        return try {
            val response = client.post("$serverAddress/api/token/verify") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val bodyAsText = response.bodyAsText()

            if (response.status == HttpStatusCode.OK && bodyAsText.trim() == "[]") {
                VerificationStatus.VALID
            } else {
                VerificationStatus.INVALID
            }
        } catch (e: Exception) {
            VerificationStatus.INVALID
        }
    }

    suspend fun updateDeviceToken(token: String) {
        try {
            client.post("$serverAddress/api/device/token") {
                contentType(ContentType.Application.Json)
                setBody(DeviceTokenRequest(token))
            }
        } catch (e: Exception) {
            // Ignore for now, or log it
        }
    }

    suspend fun getAgents(): List<Agent> {
        return client.get("$serverAddress/api/chat/agents").body()
    }

    fun chatStream(request: StreamRequest): Flow<StreamData> = flow {
        client.preparePost("$serverAddress/api/chat/stream") {
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
}
