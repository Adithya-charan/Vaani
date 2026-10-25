package com.vaani.app.speech

import com.vaani.app.data.AgentError
import com.vaani.app.data.CommandResult
import com.vaani.app.ui.userMessage

/** What Vaani says out loud. File paths read badly, so search results get a summary. */
object SpokenReply {

    fun of(result: CommandResult): String {
        val top = result.files.firstOrNull() ?: return result.message
        val total = result.search?.totalMatches ?: result.files.size
        val name = if (top.isFolder) "the folder ${top.name}" else top.name.substringBeforeLast('.').ifEmpty { top.name }
        val where = top.path.substringBefore('/')
        val count = if (total == 1) "1 match" else "$total matches"
        return "I found $count. The top one is $name, in $where."
    }

    fun of(error: AgentError): String = error.userMessage()
}
