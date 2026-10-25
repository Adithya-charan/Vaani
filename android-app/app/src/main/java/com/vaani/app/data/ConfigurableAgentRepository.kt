package com.vaani.app.data

import com.vaani.app.network.AgentEndpoint
import com.vaani.app.network.HttpAgentRepository

/** Reads the saved settings on each call, so a settings change applies immediately. */
class ConfigurableAgentRepository(
    private val settings: SettingsStore,
    private val factory: (AgentConfig) -> AgentRepository = ::httpRepositoryFor,
) : AgentRepository {

    private var cached: Pair<AgentConfig, AgentRepository>? = null

    private fun current(): AgentRepository {
        val config = settings.load() ?: return UnconfiguredAgentRepository
        cached?.let { (c, repo) -> if (c == config) return repo }
        return factory(config).also { cached = config to it }
    }

    override suspend fun checkStatus() = current().checkStatus()
    override suspend fun sendCommand(command: String) = current().sendCommand(command)
}

fun httpRepositoryFor(config: AgentConfig): AgentRepository =
    when (val parsed = AgentEndpoint.parse(config.address)) {
        is AgentEndpoint.Parsed.Ok -> HttpAgentRepository(parsed.url, config.token)
        is AgentEndpoint.Parsed.Invalid -> UnconfiguredAgentRepository
    }
