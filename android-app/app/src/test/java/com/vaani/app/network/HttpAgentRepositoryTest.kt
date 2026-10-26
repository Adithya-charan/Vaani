package com.vaani.app.network

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.Confirmation
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
    fun `transcribe uploads wav as multipart and parses transcript`() = runBlocking {
        respond(body = """{"transcript":" Open calculator, please. ","language_code":"en-IN"}""")
        val wav = "RIFF".toByteArray() + ByteArray(100)
        val t = (repo().transcribe(wav) as AgentResult.Ok).value
        assertEquals("Open calculator, please.", t.text)
        assertEquals("en-IN", t.languageCode)
        val req = server.takeRequest()
        assertEquals("/voice/transcribe", req.url.encodedPath)
        assertEquals("Bearer $TOKEN", req.headers["Authorization"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = req.body!!.utf8()
        assertTrue(body.contains("name=\"file\"; filename=\"command.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("name=\"language\"\r\nContent-Length: 7\r\n\r\nunknown") || body.contains("unknown"))
    }

    @Test
    fun `speak returns audio bytes`() = runBlocking {
        val wav = "RIFF".toByteArray() + ByteArray(200) { 7 }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "audio/wav")
            .body(okio.Buffer().write(wav)).build())
        val audio = (repo().speak("Calculator opened.") as AgentResult.Ok).value
        assertTrue(audio.contentEquals(wav))
        val req = server.takeRequest()
        assertEquals("/voice/speak", req.url.encodedPath)
        assertEquals("""{"text":"Calculator opened.","language":"en-IN"}""", req.body!!.utf8())
    }

    @Test
    fun `laptop error detail is shown to the user`() = runBlocking {
        respond(503, """{"detail":"Voice is not set up on the laptop (SARVAM_API_KEY is missing)."}""")
        val err = (repo().transcribe(ByteArray(2000)) as AgentResult.Failed).error
        assertEquals(AgentError.Server("Voice is not set up on the laptop (SARVAM_API_KEY is missing)."), err)

        respond(422, """{"detail":[{"loc":["body","command"],"msg":"too long"}]}""")
        assertEquals(AgentError.Server("The laptop couldn't read that request."),
            (repo().sendCommand("x") as AgentResult.Failed).error)

        respond(200, "")
        assertTrue((repo().speak("x") as AgentResult.Failed).error is AgentError.Server)
    }

    private val resume = com.vaani.app.data.FoundFile("my resume.pdf", "Desktop/B TECH/my resume.pdf", false, 199_000,
        "2025-02-04T10:00:00+00:00")
    private val folder = com.vaani.app.data.FoundFile("JavaProject", "Documents/JavaProject", true)

    @Test
    fun `confirmed actions are posted to actions with exact bodies`() = runBlocking {
        val ok = """{"request_id":"o","status":"success","action":"x","message":"ok","planner":"confirmed"}"""
        val cases = listOf(
            Confirmation.OpenFile(resume) to
                """{"action":"open_file","path":"Desktop/B TECH/my resume.pdf","expected_size":199000,""" +
                """"expected_modified":"2025-02-04T10:00:00+00:00","confirmed":true}""",
            Confirmation.AskChatGPT("Explain \"DNS\" simply") to
                """{"action":"chatgpt_prompt","prompt":"Explain \"DNS\" simply","confirmed":true}""",
            Confirmation.SendTelegram(resume, "Amma") to
                """{"action":"telegram_send","path":"Desktop/B TECH/my resume.pdf","expected_size":199000,""" +
                """"expected_modified":"2025-02-04T10:00:00+00:00","recipient":"Amma","as_zip":false,"confirmed":true}""",
            Confirmation.SendTelegram(folder, "Me") to
                """{"action":"telegram_send","path":"Documents/JavaProject","recipient":"Me","as_zip":true,"confirmed":true}""",
        )
        for ((confirmation, expected) in cases) {
            respond(body = ok)
            assertEquals(CommandStatus.Success, (repo().runConfirmed(confirmation) as AgentResult.Ok).value.status)
            val req = server.takeRequest()
            assertEquals("/actions", req.url.encodedPath)
            assertEquals("Bearer $TOKEN", req.headers["Authorization"])
            assertEquals(expected, req.body!!.utf8())
        }
    }

    @Test
    fun `chatgpt proposal is parsed as needs confirmation with its prompt`() = runBlocking {
        respond(body = """{"request_id":"p","status":"needs_confirmation","action":null,
            "message":"Send this prompt to ChatGPT on your laptop?",
            "data":{"confirm":{"action":"chatgpt_prompt","prompt":"What is DNS?"}},"planner":"rules"}""")
        val r = (repo().sendCommand("ask chatgpt What is DNS?") as AgentResult.Ok).value
        assertEquals(CommandStatus.NeedsConfirmation, r.status)
        assertEquals("What is DNS?", r.chatGptPrompt)

        // A confirmation request the app doesn't know how to show safely is treated as an error.
        respond(body = """{"request_id":"p","status":"needs_confirmation","message":"?",
            "data":{"confirm":{"action":"format_disk"}}}""")
        val odd = (repo().sendCommand("x") as AgentResult.Ok).value
        assertEquals(CommandStatus.Error, odd.status)
        assertEquals(null, odd.chatGptPrompt)
    }

    @Test
    fun `status exposes telegram recipients and chatgpt availability`() = runBlocking {
        respond(body = """{"request_id":"s","status":"success","message":"online","data":{"hostname":"PC",
            "telegram_recipients":["Amma","Me"],"chatgpt_automation":true}}""")
        val s = (repo().checkStatus() as AgentResult.Ok).value
        assertEquals(listOf("Amma", "Me"), s.telegramRecipients)
        assertTrue(s.chatGptAutomation)
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
