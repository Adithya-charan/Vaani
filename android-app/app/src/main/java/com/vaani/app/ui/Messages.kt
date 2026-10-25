package com.vaani.app.ui

import com.vaani.app.data.AgentError

fun AgentError.userMessage(): String = when (this) {
    AgentError.NotConfigured -> "Laptop not set up yet. Tap the settings icon to add its address and token."
    AgentError.Unreachable -> "Can't reach your laptop. Check it is on, awake, on the same Wi-Fi and running the Vaani agent."
    AgentError.Timeout -> "The laptop took too long to respond."
    AgentError.Unauthorized -> "The laptop rejected this phone's access token."
    is AgentError.Server -> message
}

fun AgentError.shortLabel(): String = when (this) {
    AgentError.NotConfigured -> "Not set up"
    AgentError.Unauthorized -> "Not authorised"
    else -> "Disconnected"
}
