package com.vaani.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEndpointTest {

    private fun ok(input: String) = (AgentEndpoint.parse(input) as AgentEndpoint.Parsed.Ok).url.toString()

    @Test fun `bare ip gets http and default port`() = assertEquals("http://192.168.0.7:8765/", ok("192.168.0.7"))
    @Test fun `explicit port kept`() = assertEquals("http://192.168.0.7:9000/", ok(" 192.168.0.7:9000/ "))
    @Test fun `explicit port 80 kept`() = assertEquals("http://192.168.0.7/", ok("http://192.168.0.7:80"))
    @Test fun `tailscale ip allowed over http`() = assertEquals("http://100.101.102.103:8765/", ok("100.101.102.103"))
    @Test fun `https public host allowed`() = assertEquals("https://vaani.example.com:8765/", ok("https://vaani.example.com"))

    @Test
    fun `http to public address rejected`() {
        assertTrue(AgentEndpoint.parse("8.8.8.8:8765") is AgentEndpoint.Parsed.Invalid)
        assertTrue(AgentEndpoint.parse("http://example.com") is AgentEndpoint.Parsed.Invalid)
        assertTrue(AgentEndpoint.parse("172.32.0.1") is AgentEndpoint.Parsed.Invalid)
    }

    @Test
    fun `garbage and paths rejected`() {
        listOf("", "   ", "not a url at all", "192.168.0.7:8765/commands", "ftp://192.168.0.7", "user@192.168.0.7")
            .forEach { assertTrue(it, AgentEndpoint.parse(it) is AgentEndpoint.Parsed.Invalid) }
    }

    @Test
    fun `private host detection`() {
        listOf("10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.1", "127.0.0.1", "localhost", "100.64.0.1")
            .forEach { assertTrue(it, AgentEndpoint.isPrivateHost(it)) }
        listOf("8.8.8.8", "172.15.0.1", "100.128.0.1", "192.169.0.1", "example.com", "256.1.1.1")
            .forEach { assertFalse(it, AgentEndpoint.isPrivateHost(it)) }
    }
}
