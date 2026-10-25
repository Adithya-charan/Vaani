package com.vaani.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vaani.app.data.AgentConfig
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.SettingsStore
import com.vaani.app.network.AgentEndpoint
import com.vaani.app.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MIN_TOKEN_LENGTH = 32

data class TestOutcome(val ok: Boolean, val message: String)

data class SettingsUiState(
    val address: String = "",
    val token: String = "",
    val addressError: String? = null,
    val tokenError: String? = null,
    val testing: Boolean = false,
    val testOutcome: TestOutcome? = null,
    val saved: Boolean = false,
    val hasSavedConfig: Boolean = false,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val repositoryFor: (AgentConfig) -> AgentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var testJob: Job? = null

    init {
        store.load()?.let { c ->
            _state.update { it.copy(address = c.address, token = c.token, hasSavedConfig = true) }
        }
    }

    fun onAddressChange(value: String) =
        _state.update { it.copy(address = value.take(200), addressError = null, testOutcome = null) }

    fun onTokenChange(value: String) =
        _state.update { it.copy(token = value.trim().take(200), tokenError = null, testOutcome = null) }

    /** Returns a validated config, or records field errors and returns null. */
    private fun validated(): AgentConfig? {
        val s = _state.value
        val parsed = AgentEndpoint.parse(s.address)
        val addressError = (parsed as? AgentEndpoint.Parsed.Invalid)?.reason
        val tokenError = when {
            s.token.isEmpty() -> "Paste the VAANI_AUTH_TOKEN from the laptop's .env file."
            s.token.length < MIN_TOKEN_LENGTH -> "That token is too short. It should be at least $MIN_TOKEN_LENGTH characters."
            s.token.any { it.isWhitespace() } -> "The token can't contain spaces."
            else -> null
        }
        _state.update { it.copy(addressError = addressError, tokenError = tokenError) }
        if (parsed !is AgentEndpoint.Parsed.Ok || tokenError != null) return null
        return AgentConfig(parsed.url.toString().trimEnd('/'), s.token)
    }

    fun testConnection() {
        val config = validated() ?: return
        testJob?.cancel()
        _state.update { it.copy(testing = true, testOutcome = null) }
        testJob = viewModelScope.launch {
            val outcome = when (val r = repositoryFor(config).checkStatus()) {
                is AgentResult.Ok -> TestOutcome(true, "Connected to ${r.value.hostname}.")
                is AgentResult.Failed -> TestOutcome(false, r.error.userMessage())
            }
            _state.update { it.copy(testing = false, testOutcome = outcome) }
        }
    }

    fun save() {
        val config = validated() ?: return
        store.save(config)
        _state.update { it.copy(address = config.address, saved = true, hasSavedConfig = true) }
    }

    fun onSavedHandled() = _state.update { it.copy(saved = false) }

    fun forget() {
        store.clear()
        _state.value = SettingsUiState()
    }

    companion object {
        fun factory(store: SettingsStore, repositoryFor: (AgentConfig) -> AgentRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(store, repositoryFor) } }
    }
}
