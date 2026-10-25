package com.vaani.app.data

/** Mirrors the laptop agent's `status` field. */
enum class CommandStatus { Success, NotFound, Rejected, Error, NeedsClarification }

data class FoundFile(
    val name: String,
    /** Alias-relative path from the agent, e.g. "Documents/Projects/report.pdf". */
    val path: String,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    /** ISO-8601 timestamp from the agent. */
    val modified: String? = null,
)

data class SearchSummary(
    val totalMatches: Int,
    val truncated: Boolean,
    val searchedFolders: List<String>,
)

data class CommandResult(
    val requestId: String,
    val status: CommandStatus,
    val action: String?,
    val message: String,
    val files: List<FoundFile> = emptyList(),
    val usedAi: Boolean = false,
    val search: SearchSummary? = null,
)

data class AgentStatus(val hostname: String, val aiPlanner: Boolean, val approvedFolders: List<String>)

/** Why a request never produced a [CommandResult]. Never shown as success. */
sealed interface AgentError {
    data object NotConfigured : AgentError
    data object Unreachable : AgentError
    data object Timeout : AgentError
    data object Unauthorized : AgentError
    data class Server(val message: String) : AgentError
}

sealed interface AgentResult<out T> {
    data class Ok<T>(val value: T) : AgentResult<T>
    data class Failed(val error: AgentError) : AgentResult<Nothing>
}
