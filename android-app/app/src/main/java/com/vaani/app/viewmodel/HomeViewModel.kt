package com.vaani.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_COMMAND_LENGTH = 500
private const val MAX_EXCHANGES = 50

sealed interface ConnectionState {
    data object Checking : ConnectionState
    data class Connected(val hostname: String) : ConnectionState
    data class Disconnected(val reason: AgentError) : ConnectionState
}

sealed interface Reply {
    data object Pending : Reply
    data class Answered(val result: CommandResult) : Reply
    data class Failed(val error: AgentError) : Reply
    data object Cancelled : Reply
}

data class Exchange(val id: Long, val command: String, val reply: Reply)

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.Checking,
    val input: String = "",
    val exchanges: List<Exchange> = emptyList(),
    val notice: String? = null,
) {
    val isSending: Boolean get() = exchanges.lastOrNull()?.reply == Reply.Pending
    val canSend: Boolean get() = input.isNotBlank() && !isSending
}

class HomeViewModel(private val repository: AgentRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var sendJob: Job? = null
    private var nextId = 0L

    init {
        refreshConnection()
    }

    fun refreshConnection() {
        _state.update { it.copy(connection = ConnectionState.Checking) }
        viewModelScope.launch {
            val connection = when (val r = repository.checkStatus()) {
                is AgentResult.Ok -> ConnectionState.Connected(r.value.hostname)
                is AgentResult.Failed -> ConnectionState.Disconnected(r.error)
            }
            _state.update { it.copy(connection = connection) }
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text.take(MAX_COMMAND_LENGTH)) }
    }

    fun onQuickCommand(command: String) {
        if (_state.value.isSending) return
        submit(command)
    }

    fun onSend() {
        val state = _state.value
        if (!state.canSend) return
        submit(state.input.trim())
        _state.update { it.copy(input = "") }
    }

    fun onCancel() {
        sendJob?.cancel()
    }

    fun onMicClick() {
        _state.update { it.copy(notice = "Voice input is coming soon. Type your command for now.") }
    }

    fun onNoticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun submit(command: String) {
        val id = nextId++
        _state.update {
            it.copy(exchanges = (it.exchanges + Exchange(id, command, Reply.Pending)).takeLast(MAX_EXCHANGES))
        }
        sendJob = viewModelScope.launch {
            try {
                val reply = when (val r = repository.sendCommand(command)) {
                    is AgentResult.Ok -> Reply.Answered(r.value)
                    is AgentResult.Failed -> Reply.Failed(r.error)
                }
                setReply(id, reply)
                updateConnectionFrom(reply)
            } catch (e: CancellationException) {
                setReply(id, Reply.Cancelled)
                throw e
            }
        }
    }

    private fun setReply(id: Long, reply: Reply) {
        _state.update { s ->
            s.copy(exchanges = s.exchanges.map { if (it.id == id) it.copy(reply = reply) else it })
        }
    }

    private fun updateConnectionFrom(reply: Reply) {
        when (reply) {
            is Reply.Answered -> if (_state.value.connection !is ConnectionState.Connected) refreshConnection()
            is Reply.Failed -> when (reply.error) {
                AgentError.Unreachable, AgentError.Timeout, AgentError.NotConfigured, AgentError.Unauthorized ->
                    _state.update { it.copy(connection = ConnectionState.Disconnected(reply.error)) }
                is AgentError.Server -> Unit
            }
            else -> Unit
        }
    }

    companion object {
        fun factory(repository: AgentRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
