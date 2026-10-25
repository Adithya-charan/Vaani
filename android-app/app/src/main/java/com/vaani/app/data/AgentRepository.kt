package com.vaani.app.data

/** Talks to the Vaani agent on the laptop. All calls are main-safe. */
interface AgentRepository {
    suspend fun checkStatus(): AgentResult<AgentStatus>
    suspend fun sendCommand(command: String): AgentResult<CommandResult>
}

/** Used until the laptop address and token are configured (Phase 3). */
object UnconfiguredAgentRepository : AgentRepository {
    override suspend fun checkStatus(): AgentResult<AgentStatus> = AgentResult.Failed(AgentError.NotConfigured)
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> =
        AgentResult.Failed(AgentError.NotConfigured)
}
