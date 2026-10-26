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
import com.vaani.app.data.FoundFile
import com.vaani.app.data.MemoryVoicePrefs
import com.vaani.app.data.VoicePrefs
import com.vaani.app.speech.MAX_RECORDING_MS
import com.vaani.app.speech.ReplySpeaker
import com.vaani.app.speech.SpokenReply
import com.vaani.app.speech.VoiceRecorder
import com.vaani.app.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_COMMAND_LENGTH = 500
private const val MAX_EXCHANGES = 50
const val MIN_RECORDING_MS = 600L
const val RECONNECT_MIN_MS = 5_000L
const val RECONNECT_MAX_MS = 30_000L
const val HEARTBEAT_MS = 30_000L

/** Worth retrying on its own; a missing setup or wrong token needs the user. */
fun AgentError.isTransient(): Boolean = when (this) {
    AgentError.Unreachable, AgentError.Timeout, is AgentError.Server -> true
    AgentError.NotConfigured, AgentError.Unauthorized -> false
}

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

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(val elapsedMs: Long) : VoiceState
    data object Transcribing : VoiceState
}

data class Exchange(val id: Long, val command: String, val reply: Reply, val viaVoice: Boolean = false)

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.Checking,
    val input: String = "",
    val exchanges: List<Exchange> = emptyList(),
    val notice: String? = null,
    val voice: VoiceState = VoiceState.Idle,
    val muted: Boolean = false,
    val speaking: Boolean = false,
    /** Search result waiting for the user to confirm opening it on the laptop. */
    val pendingOpen: FoundFile? = null,
) {
    val isSending: Boolean get() = exchanges.lastOrNull()?.reply == Reply.Pending
    val isBusy: Boolean get() = isSending || voice != VoiceState.Idle
    val canSend: Boolean get() = input.isNotBlank() && !isBusy
}

class HomeViewModel(
    private val repository: AgentRepository,
    private val recorder: VoiceRecorder? = null,
    private val speaker: ReplySpeaker? = null,
    private val prefs: VoicePrefs = MemoryVoicePrefs(),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(muted = prefs.muted))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    val micLevel: StateFlow<Float>? = recorder?.level

    private var sendJob: Job? = null
    private var voiceJob: Job? = null
    private var speakJob: Job? = null
    private var watchJob: Job? = null
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

    /**
     * While the app is on screen, quietly re-check a laptop that went away (asleep, off
     * Wi-Fi, agent restarting) so the status turns green again without the user tapping.
     */
    fun onForeground() {
        if (_state.value.connection != ConnectionState.Checking) refreshConnection()
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            var wait = RECONNECT_MIN_MS
            while (true) {
                when (val c = _state.value.connection) {
                    // Heartbeat: notice a laptop that went to sleep while we showed "Connected".
                    is ConnectionState.Connected -> {
                        delay(HEARTBEAT_MS)
                        if (_state.value.connection !is ConnectionState.Connected || _state.value.isBusy) continue
                        val r = repository.checkStatus()
                        if (r is AgentResult.Failed && r.error.isTransient() &&
                            _state.value.connection is ConnectionState.Connected
                        ) {
                            _state.update { it.copy(connection = ConnectionState.Disconnected(r.error)) }
                            wait = RECONNECT_MIN_MS
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        if (!c.reason.isTransient()) {
                            delay(RECONNECT_MIN_MS) // needs the user (settings); just watch for changes
                            continue
                        }
                        delay(wait)
                        if (_state.value.connection !is ConnectionState.Disconnected) continue
                        when (val r = repository.checkStatus()) {
                            is AgentResult.Ok -> {
                                if (_state.value.connection is ConnectionState.Disconnected) {
                                    _state.update { it.copy(connection = ConnectionState.Connected(r.value.hostname)) }
                                }
                                wait = RECONNECT_MIN_MS
                            }
                            is AgentResult.Failed -> wait = (wait * 2).coerceAtMost(RECONNECT_MAX_MS)
                        }
                    }
                    ConnectionState.Checking -> delay(500)
                }
            }
        }
    }

    fun onBackground() {
        watchJob?.cancel()
        watchJob = null
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text.take(MAX_COMMAND_LENGTH)) }
    }

    fun onQuickCommand(command: String) {
        if (_state.value.isBusy) return
        submit(command, viaVoice = false)
    }

    fun onSend() {
        val state = _state.value
        if (!state.canSend) return
        submit(state.input.trim(), viaVoice = false)
        _state.update { it.copy(input = "") }
    }

    fun onCancel() {
        when (_state.value.voice) {
            is VoiceState.Recording -> {
                voiceJob?.cancel()
                recorder?.cancel()
                _state.update { it.copy(voice = VoiceState.Idle) }
            }
            VoiceState.Transcribing -> voiceJob?.cancel()
            VoiceState.Idle -> sendJob?.cancel()
        }
    }

    fun onNoticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun notice(text: String) = _state.update { it.copy(notice = text) }

    // --- voice input ------------------------------------------------------------

    /** Called only after RECORD_AUDIO has been granted. Tap to start, tap again to send. */
    fun onMicClick() {
        when (_state.value.voice) {
            VoiceState.Idle -> startRecording()
            is VoiceState.Recording -> finishRecording()
            VoiceState.Transcribing -> Unit
        }
    }

    fun onMicPermissionDenied(permanently: Boolean) {
        notice(
            if (permanently) "Microphone access is off for Vaani. Turn it on in Android Settings > Apps > Vaani > Permissions, or type your command."
            else "Vaani needs the microphone to hear commands. You can still type."
        )
    }

    private fun startRecording() {
        val rec = recorder ?: return notice("Voice input isn't available on this phone. Type your command instead.")
        if (_state.value.isSending) return
        stopSpeaking()
        if (!rec.start()) return notice("Couldn't start the microphone. Another app may be using it.")
        _state.update { it.copy(voice = VoiceState.Recording(0)) }
        voiceJob = viewModelScope.launch {
            var elapsed = 0L
            while (true) {
                delay(100)
                elapsed += 100
                _state.update { it.copy(voice = VoiceState.Recording(elapsed)) }
                if (elapsed >= MAX_RECORDING_MS) {
                    finishRecording()
                    break
                }
            }
        }
    }

    private fun finishRecording() {
        voiceJob?.cancel()
        val recording = recorder?.stop()
        if (recording == null || recording.durationMs < MIN_RECORDING_MS) {
            _state.update { it.copy(voice = VoiceState.Idle) }
            return notice("I didn't hear anything. Tap the mic, speak, then tap again to send.")
        }
        _state.update { it.copy(voice = VoiceState.Transcribing) }
        voiceJob = viewModelScope.launch {
            try {
                when (val r = repository.transcribe(recording.wav)) {
                    is AgentResult.Ok -> {
                        _state.update { it.copy(voice = VoiceState.Idle) }
                        val text = r.value.text.take(MAX_COMMAND_LENGTH)
                        if (text.isBlank()) notice("I couldn't make out any words. Try again or type.")
                        else submit(text, viaVoice = true)
                    }
                    is AgentResult.Failed -> {
                        _state.update { it.copy(voice = VoiceState.Idle) }
                        notice(r.error.userMessage())
                        disconnectIfNetwork(r.error)
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(voice = VoiceState.Idle) }
                throw e
            }
        }
    }

    // --- spoken replies -----------------------------------------------------------

    fun onToggleMute() {
        val muted = !_state.value.muted
        prefs.muted = muted
        _state.update { it.copy(muted = muted) }
        if (muted) stopSpeaking()
    }

    private fun speak(text: String) {
        val s = speaker ?: return
        if (_state.value.muted) return
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            _state.update { it.copy(speaking = true) }
            try {
                s.speak(text)
            } finally {
                _state.update { it.copy(speaking = false) }
            }
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speaker?.stop()
    }

    // --- commands -------------------------------------------------------------------

    // --- opening a search result (needs explicit confirmation) ----------------------------

    fun onResultTapped(file: FoundFile) {
        if (_state.value.isBusy) return
        _state.update { it.copy(pendingOpen = file) }
    }

    fun onDismissOpen() {
        _state.update { it.copy(pendingOpen = null) }
    }

    /** Sends exactly the file shown in the dialog; nothing is opened without this call. */
    fun onConfirmOpen() {
        val file = _state.value.pendingOpen ?: return
        _state.update { it.copy(pendingOpen = null) }
        if (_state.value.isBusy) return
        submit("Open ${file.name}", viaVoice = false) { repository.openConfirmed(file) }
    }

    private fun submit(
        command: String,
        viaVoice: Boolean,
        call: suspend () -> AgentResult<CommandResult> = { repository.sendCommand(command) },
    ) {
        stopSpeaking()
        val id = nextId++
        _state.update {
            it.copy(exchanges = (it.exchanges + Exchange(id, command, Reply.Pending, viaVoice)).takeLast(MAX_EXCHANGES))
        }
        sendJob = viewModelScope.launch {
            try {
                val reply = when (val r = call()) {
                    is AgentResult.Ok -> Reply.Answered(r.value)
                    is AgentResult.Failed -> Reply.Failed(r.error)
                }
                setReply(id, reply)
                updateConnectionFrom(reply)
                if (viaVoice) {
                    when (reply) {
                        is Reply.Answered -> speak(SpokenReply.of(reply.result))
                        is Reply.Failed -> speak(SpokenReply.of(reply.error))
                        else -> Unit
                    }
                }
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

    private fun disconnectIfNetwork(error: AgentError) {
        when (error) {
            AgentError.Unreachable, AgentError.Timeout, AgentError.NotConfigured, AgentError.Unauthorized ->
                _state.update { it.copy(connection = ConnectionState.Disconnected(error)) }
            is AgentError.Server -> Unit
        }
    }

    private fun updateConnectionFrom(reply: Reply) {
        when (reply) {
            is Reply.Answered -> if (_state.value.connection !is ConnectionState.Connected) refreshConnection()
            is Reply.Failed -> disconnectIfNetwork(reply.error)
            else -> Unit
        }
    }

    override fun onCleared() {
        recorder?.cancel()
        speaker?.release()
    }

    companion object {
        fun factory(
            repository: AgentRepository,
            recorder: VoiceRecorder? = null,
            speaker: ReplySpeaker? = null,
            prefs: VoicePrefs = MemoryVoicePrefs(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository, recorder, speaker, prefs) }
        }
    }
}
