package pub.smartnet.lifepal

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pub.smartnet.lifepal.data.TokenManager
import pub.smartnet.lifepal.data.remote.*

class MainViewModel(private val tokenManager: TokenManager) : ViewModel() {

    private val apiClient = ApiClient(tokenManager)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState = _authState.asStateFlow()

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

    private fun checkAuth() {
        viewModelScope.launch {
            val accessToken = tokenManager.getAccessToken()
            if (accessToken != null) {
                _authState.value = AuthState.Authenticated
                loadAgents()
            } else {
                _authState.value = AuthState.Unauthenticated
            }
        }
    }

    fun login(username: String, password: String, serverAddress: String) {
        _messages.value = emptyList()
        conversationId = null
        selectedAgent.value = null
        apiClient.setServerAddress(serverAddress)
        viewModelScope.launch {
            try {
                val response = apiClient.login(LoginRequest(username, password))
                tokenManager.saveTokens(response.access, response.refresh)
                _authState.value = AuthState.Authenticated
                loadAgents()
            } catch (e: Exception) {
                _eventFlow.emit(UIEvent.ShowToast("Login failed: ${e.message}"))
            }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                _agents.value = apiClient.getAgents()
            } catch (e: Exception) {
                _eventFlow.emit(UIEvent.ShowToast("Failed to load agents: ${e.message}"))
            }
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
                val currentMessages = _messages.value.toMutableList()
                val lastMessage = currentMessages.last()
                val updatedMessage = lastMessage.copy(text = "Error: ${e.message}", isTyping = false)
                currentMessages[currentMessages.size - 1] = updatedMessage
                _messages.value = currentMessages
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            tokenManager.clearTokens()
            _messages.value = emptyList()
            conversationId = null
            selectedAgent.value = null
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun startNewConversation() {
        _messages.value = emptyList()
        conversationId = null
    }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(TokenManager(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

sealed class AuthState {
    object Loading : AuthState()
    object Authenticated : AuthState()
    object Unauthenticated : AuthState()
}

sealed class UIEvent {
    data class ShowToast(val message: String) : UIEvent()
}

data class ChatMessage(val text: String, val author: String, val isTyping: Boolean = false, val agentName: String? = null)
