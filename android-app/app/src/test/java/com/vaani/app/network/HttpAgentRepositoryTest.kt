package com.vaani.app.network

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentResult
import com.vaani.app.data.CommandStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

private const val TOKEN = "t0ken-t0ken-t0ken-t0ken-t0ken-t0ken"

class HttpAgentRepositoryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun repo(client: OkHttpClient = defaultHttpClient()) = HttpAgentRepository(server.url("/"), TOKEN, client)

    private fun respond(code: Int = 200, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `sends command with bearer token and parses success`() = runBlocking {
        respond(body = """{"request_id":"abc","status":"success","action":"open_calculator",
            "message":"Calculator opened.","data":null,"planner":"rules","extra":"ignored"}""")

        val r = repo().sendCommand("Open Calculator")

        val result = (r as AgentResult.Ok).value
        assertEquals(CommandStatus.Success, result.status)
        assertEquals("Calculator opened.", result.message)
        assertEquals("open_calculator", result.action)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/commands", req.url.encodedPath)
        assertEquals("Bearer $TOKEN", req.headers["Authorization"])
        assertEquals("""{"command":"Open Calculator"}""", req.body!!.utf8())
    }

    @Test
    fun `parses file search results and statuses`() = runBlocking {
        respond(body = """{"request_id":"x","status":"success","action":"find_file","message":"Found it.",
            "data":{"results":[{"name":"JavaProject","path":"Documents/JavaProject","type":"folder","size":null},
            {"name":"a.pdf","path":"Downloads/a.pdf","type":"file","size":3}],"total_matches":2},"planner":"ai"}""")
        val result = (repo().sendCommand("find java") as AgentResult.Ok).value
        assertEquals(2, result.files.size)
        assertTrue(result.files[0].isFolder)
        assertEquals(3L, result.files[1].sizeBytes)
        assertTrue(result.usedAi)
        assertEquals(2, result.search!!.totalMatches)

        respond(body = """{"request_id":"x","status":"success","action":"find_file","message":"Found.",
            "data":{"results":[{"name":"a.pdf","path":"Downloads/a.pdf","type":"file","size":10,
            "modified":"2026-10-24T09:00:00+00:00"}],"total_matches":57,"truncated":true,
            "searched":["Documents","Desktop","Downloads"]}}""")
        val big = (repo().sendCommand("find a") as AgentResult.Ok).value
        assertEquals("2026-10-24T09:00:00+00:00", big.files.single().modified)
        assertEquals(57, big.search!!.totalMatches)
        assertTrue(big.search!!.truncated)
        assertEquals(listOf("Documents", "Desktop", "Downloads"), big.search!!.searchedFolders)

        respond(body = """{"request_id":"x","status":"not_found","action":"find_file","message":"No matches.",
            "data":{"results":[],"total_matches":0,"truncated":false,"searched":["Desktop"]}}""")
        val none = (repo().sendCommand("find zzz") as AgentResult.Ok).value
        assertEquals(CommandStatus.NotFound, none.status)
        assertEquals(listOf("Desktop"), none.search!!.searchedFolders)

        respond(body = """{"request_id":"x","status":"success","action":"open_notepad","message":"Notepad opened."}""")
        assertEquals(null, (repo().sendCommand("open notepad") as AgentResult.Ok).value.search)

        for ((wire, expected) in listOf(
            "not_found" to CommandStatus.NotFound, "rejected" to CommandStatus.Rejected,
            "error" to CommandStatus.Error, "needs_clarification" to CommandStatus.NeedsClarification,
            "something_new" to CommandStatus.Error,
        )) {
            respond(body = """{"request_id":"x","status":"$wire","message":"m"}""")
            assertEquals(expected, (repo().sendCommand("x") as AgentResult.Ok).value.status)
        }
    }

    @Test
    fun `status endpoint parsed`() = runBlocking {
        respond(body = """{"request_id":"s","status":"success","action":"get_status","message":"Laptop agent is online.",
            "data":{"agent_version":"0.1.0","hostname":"ADITHYA-PC","uptime_seconds":5,"ai_planner":true,
            "approved_folders":["Documents","Downloads"]}}""")
        val status = (repo().checkStatus() as AgentResult.Ok).value
        assertEquals("ADITHYA-PC", status.hostname)
        assertTrue(status.aiPlanner)
        assertEquals(listOf("Documents", "Downloads"), status.approvedFolders)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `401 maps to unauthorized`() = runBlocking {
        respond(401, """{"detail":"Missing or invalid token."}""")
        assertEquals(AgentResult.Failed(AgentError.Unauthorized), repo().sendCommand("x"))
    }

    @Test
    fun `server errors and bad json map to server error, never success`() = runBlocking {
        respond(500, "boom")
        assertTrue((repo().sendCommand("x") as AgentResult.Failed).error is AgentError.Server)
        respond(422, "{}")
        assertTrue((repo().sendCommand("x") as AgentResult.Failed).error is AgentError.Server)
        respond(200, "<html>not json</html>")
        assertTrue((repo().sendCommand("x") as AgentResult.Failed).error is AgentError.Server)
        respond(200, """{"status":"success"}""")
        assertTrue((repo().sendCommand("x") as AgentResult.Failed).error is AgentError.Server)
    }

    @Test
    fun `slow laptop maps to timeout`() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("{}").build())
        val client = defaultHttpClient().newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        assertEquals(AgentResult.Failed(AgentError.Timeout), repo(client).sendCommand("x"))
    }

    @Test
    fun `connections are not kept idle for reuse`() = runBlocking {
        val client = defaultHttpClient()
        respond(body = """{"request_id":"s","status":"success","message":"ok","data":{"hostname":"PC"}}""")
        repo(client).checkStatus()
        respond(body = """{"request_id":"c","status":"success","message":"ok"}""")
        repo(client).sendCommand("Open Calculator")
        assertEquals(0, client.connectionPool.idleConnectionCount())
        assertEquals(0, server.takeRequest().connectionIndex)
        assertEquals("command must use a new connection", 1, server.takeRequest().connectionIndex)
    }

    @Test
    fun `nothing listening maps to unreachable`() = runBlocking {
        val url = server.url("/")
        server.close()
        val r = HttpAgentRepository(url, TOKEN).sendCommand("x")
        assertEquals(AgentResult.Failed(AgentError.Unreachable), r)
    }
}
