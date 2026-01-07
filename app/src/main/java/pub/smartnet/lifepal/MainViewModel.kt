package pub.smartnet.lifepal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import pub.smartnet.lifepal.data.*
import pub.smartnet.lifepal.data.remote.*
import java.util.concurrent.TimeUnit

@Suppress("StaticFieldLeak") // Context is application context, not activity
class MainViewModel(
    private val context: Context,
    val healthConnectManager: HealthConnectManager,
    val contextualDataManager: ContextualDataManager,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val apiClient = ApiClient(tokenManager)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState = _authState.asStateFlow()

    private val _username = MutableStateFlow("")
    val username = _username.asStateFlow()

    private val _lastServerAddress = MutableStateFlow("")
    val lastServerAddress = _lastServerAddress.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents = _agents.asStateFlow()

    val selectedAgent = mutableStateOf<Agent?>(null)
    val userInput = mutableStateOf("")

    private val _eventFlow = MutableSharedFlow<UIEvent>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val eventFlow = _eventFlow.asSharedFlow()

    private var conversationId: Int? = null

    init {
        checkAuth()
    }

    private fun handleApiError(e: Exception, message: String = "An error occurred") {
        viewModelScope.launch {
            if (e is ClientRequestException && e.response.status == HttpStatusCode.Unauthorized) {
                logout()
                _eventFlow.emit(UIEvent.ShowToast("Session expired. Please log in again."))
            } else {
                _eventFlow.emit(UIEvent.ShowToast("$message: ${e.message}"))
            }
        }
    }

    private fun checkAuth() {
        viewModelScope.launch {
            delay(1000) // Artificial delay for splash screen

            val accessToken = tokenManager.getAccessToken()
            val serverAddress = tokenManager.getServerAddress()

            _username.value = tokenManager.getUsername() ?: ""
            _lastServerAddress.value = serverAddress ?: ""

            if (accessToken != null && serverAddress != null) {
                apiClient.init()
                try {
                    loadAgents()
                    registerDevice()
                    
                    _authState.value = AuthState.Authenticated
                } catch (e: Exception) {
                    handleApiError(e, "Authentication check failed")
                }
            } else {
                _authState.value = AuthState.Unauthenticated
            }
        }
    }

    fun login(username: String, password: String, serverAddress: String) {
        _messages.value = emptyList()
        conversationId = null
        selectedAgent.value = null

        viewModelScope.launch {
            _authState.value = AuthState.LoggingIn
            try {
                apiClient.setServerAddress(serverAddress)
                tokenManager.saveServerAddress(serverAddress)
                val response = apiClient.login(LoginRequest(username, password))
                tokenManager.saveTokens(response.access, response.refresh)
                tokenManager.saveUsername(response.username)
                _username.value = response.username

                loadAgents()
                registerDevice()
                _authState.value = AuthState.Authenticated
            } catch (e: Exception) {
                _eventFlow.emit(UIEvent.ShowToast("Login failed: ${e.message}"))
                _authState.value = AuthState.Unauthenticated
            }
        }
    }

    private suspend fun registerDevice() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d("FCM", "Attempting to register token: $token")
            apiClient.registerDeviceToken(token)
            Log.d("FCM", "Token registration successful.")
        } catch (e: Exception) {
            Log.e("FCM", "Failed to retrieve or register token", e)
            handleApiError(e, "Could not register for notifications")
        }
    }

    private suspend fun loadAgents() {
        try {
            _agents.value = apiClient.getAgents()
        } catch (e: Exception) {
            handleApiError(e, "Failed to load agents")
            throw e // Re-throw to be caught by checkAuth
        }
    }

    fun sendMessage() {
        val message = userInput.value
        if (message.isBlank()) return

        _messages.value = _messages.value + ChatMessage(message, "user") + ChatMessage("", "bot", true)
        userInput.value = ""

        viewModelScope.launch {
            try {
                val request = StreamRequest(
                    message = message,
                    agent_id = selectedAgent.value?.id,
                    conversation_id = conversationId
                )

                apiClient.chatStream(request).collect { data ->
                    val currentMessages = _messages.value.toMutableList()
                    val lastMessage = currentMessages.last()

                    when (data.type) {
                        "start" -> {
                            if (data.conversation_id != null) {
                                conversationId = data.conversation_id
                            }
                            val agentName = data.agent_name ?: "Bot"
                            val updatedMessage = lastMessage.copy(agentName = agentName)
                            currentMessages[currentMessages.size - 1] = updatedMessage
                            _messages.value = currentMessages
                        }
                        "content" -> {
                            val newText = lastMessage.text + (data.content ?: "")
                            val updatedMessage = lastMessage.copy(text = newText)
                            currentMessages[currentMessages.size - 1] = updatedMessage
                            _messages.value = currentMessages
                        }
                        "end" -> {
                            val updatedMessage = lastMessage.copy(isTyping = false)
                            currentMessages[currentMessages.size - 1] = updatedMessage
                            _messages.value = currentMessages
                        }
                    }
                }
            } catch (e: Exception) {
                handleApiError(e, "Error sending message")
                val currentMessages = _messages.value.toMutableList()
                if (currentMessages.isNotEmpty()) {
                    val lastMessage = currentMessages.last()
                    val updatedMessage = lastMessage.copy(text = "Error: ${e.message}", isTyping = false)
                    currentMessages[currentMessages.size - 1] = updatedMessage
                    _messages.value = currentMessages
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            tokenManager.clearSessionData()
            _messages.value = emptyList()
            conversationId = null
            selectedAgent.value = null
            _username.value = ""
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun startNewConversation() {
        _messages.value = emptyList()
        conversationId = null
    }

    suspend fun checkAndRequestHealthPermissions() {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        Log.d("HealthDebug", "Health Connect Status: $sdkStatus")
        when (healthConnectManager.checkAvailability()) {
            HealthConnectAvailability.NOT_SUPPORTED -> {
                _eventFlow.emit(UIEvent.ShowToast("Health Connect is not supported on this device."))
            }
            HealthConnectAvailability.NOT_INSTALLED, HealthConnectAvailability.NEEDS_UPDATE -> {
                val uri = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
                _eventFlow.emit(UIEvent.InstallHealthConnect(uri))
            }
            HealthConnectAvailability.AVAILABLE -> {
                // Always go to permission screen if not all permissions are granted
                _eventFlow.emit(UIEvent.RequestHealthConnectPermissions(healthConnectManager.permissions))
            }
        }
    }

    fun onHealthPermissionsResult(@Suppress("UNUSED_PARAMETER") grantedPermissions: Set<String>) {
        viewModelScope.launch {
            if (healthConnectManager.hasAllPermissions()) {
                _eventFlow.emit(UIEvent.ShowToast("Health permissions granted"))
            }
        }
    }

    fun requestContextualPermissions() {
        viewModelScope.launch {
             // Always go to permission screen if permission is not granted
            if (!contextualDataManager.hasUsageStatsPermission()) {
                _eventFlow.emit(UIEvent.RequestUsageStatsPermission)
            }
        }
    }

    fun onUsageStatsPermissionResult() {
        viewModelScope.launch {
            if (contextualDataManager.hasUsageStatsPermission()) {
                _eventFlow.emit(UIEvent.ShowToast("Usage stats permission granted"))
            } else {
                _eventFlow.emit(UIEvent.ShowToast("Usage stats permission not granted."))
            }
        }
    }

    fun triggerFullSync() {
        viewModelScope.launch {
            _eventFlow.emit(UIEvent.ShowToast("Starting full sync (7 days)..."))
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val fullSyncWork = OneTimeWorkRequestBuilder<HealthDataWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf("DAYS_TO_SYNC" to 7)
                )
                .build()

            WorkManager.getInstance(context).enqueue(fullSyncWork)
            Log.d("MainViewModel", "Full sync worker queued (7 days)")
        }
    }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(
                context,
                HealthConnectManager(context),
                ContextualDataManager(context),
                TokenManager(context)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

sealed class AuthState {
    object Loading : AuthState()
    object LoggingIn : AuthState()
    object Authenticated : AuthState()
    object Unauthenticated : AuthState()
}

sealed class UIEvent {
    data class ShowToast(val message: String) : UIEvent()
    data class RequestHealthConnectPermissions(val permissions: Set<String>) : UIEvent()
    object RequestUsageStatsPermission : UIEvent()
    data class InstallHealthConnect(val uri: String) : UIEvent()
}

data class ChatMessage(val text: String, val author: String, val isTyping: Boolean = false, val agentName: String? = null)
