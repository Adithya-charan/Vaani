package com.vaani.app.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val DEFAULT_AGENT_PORT = 8765

/**
 * Validates the laptop address the user typed.
 *
 * Plain http is only allowed to private/LAN, Tailscale (100.64.0.0/10) and loopback
 * addresses; anything else must use https so the token never crosses the internet in clear.
 */
object AgentEndpoint {

    sealed interface Parsed {
        data class Ok(val url: HttpUrl) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    fun parse(input: String): Parsed {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return Parsed.Invalid("Enter your laptop's address, e.g. 192.168.0.7:8765")
        val withScheme = if ("://" in text) text else "http://$text"
        val url = withScheme.toHttpUrlOrNull()
            ?: return Parsed.Invalid("That doesn't look like an address. Example: 192.168.0.7:8765")
        if (url.encodedPath != "/" || url.query != null || url.username.isNotEmpty()) {
            return Parsed.Invalid("Enter only the address and port, without a path.")
        }
        val withPort = if (url.port == HttpUrl.defaultPort(url.scheme) && !hasExplicitPort(withScheme)) {
            url.newBuilder().port(DEFAULT_AGENT_PORT).build()
        } else url
        if (withPort.scheme == "http" && !isPrivateHost(withPort.host)) {
            return Parsed.Invalid("Plain http is only allowed on your home network or Tailscale. Use https:// for other addresses.")
        }
        return Parsed.Ok(withPort)
    }

    private fun hasExplicitPort(url: String): Boolean =
        Regex("^[a-z]+://(\\[[^]]+]|[^/:]+):\\d+", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) // Tailscale CGNAT range
    }
}
