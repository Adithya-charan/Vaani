package com.vaani.app.data

/** Mirrors the laptop agent's `status` field. */
enum class CommandStatus { Success, NotFound, Rejected, Error, NeedsClarification, NeedsConfirmation }

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
    /** Set with [CommandStatus.NeedsConfirmation]: the exact ChatGPT prompt awaiting the user's OK. */
    val chatGptPrompt: String? = null,
)

data class AgentStatus(
    val hostname: String,
    val aiPlanner: Boolean,
    val approvedFolders: List<String>,
    val telegramRecipients: List<String> = emptyList(),
    val chatGptAutomation: Boolean = false,
)

/** Something the laptop does only after the user explicitly confirms it on the phone. */
sealed interface Confirmation {
    data class OpenFile(val file: FoundFile) : Confirmation
    data class AskChatGPT(val prompt: String) : Confirmation
    /** A folder is sent as a ZIP of its contents. */
    data class SendTelegram(val file: FoundFile, val recipient: String) : Confirmation
}

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
