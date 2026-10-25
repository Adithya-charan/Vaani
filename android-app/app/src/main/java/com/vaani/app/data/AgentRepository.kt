package com.vaani.app.data

data class Transcription(val text: String, val languageCode: String?)

/** Talks to the Vaani agent on the laptop. All calls are main-safe. */
interface AgentRepository {
    suspend fun checkStatus(): AgentResult<AgentStatus>
    suspend fun sendCommand(command: String): AgentResult<CommandResult>

    /** Speech-to-text via the laptop (Sarvam Saaras). [wav] is a complete WAV file. */
    suspend fun transcribe(wav: ByteArray, language: String = "unknown"): AgentResult<Transcription> =
        AgentResult.Failed(AgentError.NotConfigured)

    /** Text-to-speech via the laptop (Sarvam Bulbul). Returns WAV bytes. */
    suspend fun speak(text: String, language: String = "en-IN"): AgentResult<ByteArray> =
        AgentResult.Failed(AgentError.NotConfigured)
}

/** Used until the laptop address and token are configured. */
object UnconfiguredAgentRepository : AgentRepository {
    override suspend fun checkStatus(): AgentResult<AgentStatus> = AgentResult.Failed(AgentError.NotConfigured)
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> =
        AgentResult.Failed(AgentError.NotConfigured)
}
