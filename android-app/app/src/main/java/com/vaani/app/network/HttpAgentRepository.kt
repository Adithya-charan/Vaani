package com.vaani.app.network

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.FoundFile
import com.vaani.app.data.Confirmation
import com.vaani.app.data.SearchSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.vaani.app.data.Transcription
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA = "application/json".toMediaType()
private val WAV_MEDIA = "audio/wav".toMediaType()

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS) // the AI planner may take up to ~15 s
    .callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false) // never silently repeat a command
    // No idle connection reuse: the agent (uvicorn) drops idle connections after 5 s, and a
    // stale pooled connection would fail the next command since we refuse to retry it.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .build()

/** Talks to the FastAPI agent. The token goes only in the Authorization header and is never logged. */
class HttpAgentRepository(
    private val baseUrl: HttpUrl,
    private val token: String,
    private val client: OkHttpClient = defaultHttpClient(),
) : AgentRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(wav: ByteArray, language: String): AgentResult<Transcription> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "command.wav", wav.toRequestBody(WAV_MEDIA))
            .addFormDataPart("language", language)
            .build()
        return execute(request("voice", "transcribe").post(body).build()) { response ->
            val dto = json.decodeFromString<TranscriptDto>(response.string())
            Transcription(dto.transcript.trim(), dto.languageCode)
        }
    }

    // ChatGPT automation and Telegram uploads (up to 50 MB) take longer than a normal command.
    private val confirmedClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(170, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    }

    override suspend fun runConfirmed(confirmation: Confirmation): AgentResult<CommandResult> {
        val payload = confirmationJson(confirmation).toString()
        return execute(request("actions").post(payload.toRequestBody(JSON_MEDIA)).build(), confirmedClient) { response ->
            json.decodeFromString<CommandResponseDto>(response.string()).toResult()
        }
    }

    override suspend fun speak(text: String, language: String): AgentResult<ByteArray> {
        val payload = json.encodeToString(SpeakRequestDto.serializer(), SpeakRequestDto(text.take(500), language))
        return execute(request("voice", "speak").post(payload.toRequestBody(JSON_MEDIA)).build()) { response ->
            response.bytes().also { require(it.size > 44) { "empty audio" } }
        }
    }

    override suspend fun checkStatus(): AgentResult<AgentStatus> =
        execute(request("status").get().build()) { response ->
            val body = response.string()
            val r = json.decodeFromString<CommandResponseDto>(body)
            AgentStatus(
                hostname = r.data?.hostname ?: "laptop",
                aiPlanner = r.data?.aiPlanner ?: false,
                approvedFolders = r.data?.approvedFolders.orEmpty(),
                telegramRecipients = r.data?.telegramRecipients.orEmpty(),
                chatGptAutomation = r.data?.chatGptAutomation ?: false,
            )
        }

    override suspend fun sendCommand(command: String): AgentResult<CommandResult> {
        val payload = json.encodeToString(CommandRequestDto.serializer(), CommandRequestDto(command))
        return execute(request("commands").post(payload.toRequestBody(JSON_MEDIA)).build()) { response ->
            json.decodeFromString<CommandResponseDto>(response.string()).toResult()
        }
    }

    private fun request(vararg path: String) = Request.Builder()
        .url(baseUrl.newBuilder().apply { path.forEach { addPathSegment(it) } }.build())
        .header("Authorization", "Bearer $token")

    /** FastAPI errors look like {"detail": "..."}; validation errors have a list instead. */
    private fun errorDetail(body: String): String? = try {
        (json.parseToJsonElement(body).jsonObject["detail"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun <T> execute(
        request: Request,
        http: OkHttpClient = client,
        parse: (ResponseBody) -> T,
    ): AgentResult<T> =
        withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request).await()
            } catch (e: InterruptedIOException) {
                return@withContext AgentResult.Failed(AgentError.Timeout)
            } catch (e: IOException) {
                return@withContext AgentResult.Failed(AgentError.Unreachable)
            }
            response.use {
                when {
                    it.code == 401 || it.code == 403 -> AgentResult.Failed(AgentError.Unauthorized)
                    !it.isSuccessful -> {
                        val detail = try { errorDetail(it.body.string()) } catch (e: IOException) { null }
                        AgentResult.Failed(AgentError.Server(detail ?: when (it.code) {
                            422 -> "The laptop couldn't read that request."
                            else -> "The laptop agent returned an error (HTTP ${it.code})."
                        }))
                    }
                    else -> try {
                        AgentResult.Ok(parse(it.body))
                    } catch (e: SerializationException) {
                        AgentResult.Failed(AgentError.Server("The laptop sent a reply this app doesn't understand."))
                    } catch (e: IllegalArgumentException) {
                        AgentResult.Failed(AgentError.Server("The laptop sent a reply this app doesn't understand."))
                    } catch (e: IOException) {
                        AgentResult.Failed(AgentError.Unreachable)
                    }
                }
            }
        }
}

/** Cancelling the coroutine cancels the HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

@Serializable
private data class CommandRequestDto(val command: String)

@Serializable
private data class SpeakRequestDto(val text: String, val language: String)

/** Body for POST /actions. `confirmed: true` is only ever sent from a confirmation dialog. */
internal fun confirmationJson(c: Confirmation): JsonObject = buildJsonObject {
    fun selected(file: FoundFile) {
        put("path", file.path)
        // The laptop refuses the file if it changed since the user picked it.
        if (!file.isFolder) {
            file.sizeBytes?.let { put("expected_size", it) }
            file.modified?.let { put("expected_modified", it) }
        }
    }
    when (c) {
        is Confirmation.OpenFile -> {
            put("action", "open_file")
            selected(c.file)
        }
        is Confirmation.AskChatGPT -> {
            put("action", "chatgpt_prompt")
            put("prompt", c.prompt)
        }
        is Confirmation.SendTelegram -> {
            put("action", "telegram_send")
            selected(c.file)
            put("recipient", c.recipient)
            put("as_zip", c.file.isFolder)
        }
    }
    put("confirmed", true)
}

@Serializable
private data class TranscriptDto(
    val transcript: String,
    @kotlinx.serialization.SerialName("language_code") val languageCode: String? = null,
)

@Serializable
private data class FoundFileDto(
    val name: String,
    val path: String,
    val type: String = "file",
    val size: Long? = null,
    val modified: String? = null,
)

@Serializable
private data class ConfirmDto(val action: String? = null, val prompt: String? = null)

@Serializable
private data class ResponseDataDto(
    val hostname: String? = null,
    @kotlinx.serialization.SerialName("ai_planner") val aiPlanner: Boolean? = null,
    @kotlinx.serialization.SerialName("approved_folders") val approvedFolders: List<String>? = null,
    @kotlinx.serialization.SerialName("telegram_recipients") val telegramRecipients: List<String>? = null,
    @kotlinx.serialization.SerialName("chatgpt_automation") val chatGptAutomation: Boolean? = null,
    val confirm: ConfirmDto? = null,
    val results: List<FoundFileDto>? = null,
    @kotlinx.serialization.SerialName("total_matches") val totalMatches: Int? = null,
    val truncated: Boolean? = null,
    val searched: List<String>? = null,
)

@Serializable
private data class CommandResponseDto(
    @kotlinx.serialization.SerialName("request_id") val requestId: String,
    val status: String,
    val action: String? = null,
    val message: String,
    val data: ResponseDataDto? = null,
    val planner: String? = null,
) {
    fun toResult() = CommandResult(
        requestId = requestId,
        status = when (status) {
            "success" -> CommandStatus.Success
            "not_found" -> CommandStatus.NotFound
            "rejected" -> CommandStatus.Rejected
            "needs_clarification" -> CommandStatus.NeedsClarification
            // Only a ChatGPT proposal with its prompt counts; anything else can't be confirmed safely.
            "needs_confirmation" -> if (data?.confirm?.action == "chatgpt_prompt" && !data.confirm.prompt.isNullOrBlank())
                CommandStatus.NeedsConfirmation else CommandStatus.Error
            else -> CommandStatus.Error
        },
        chatGptPrompt = data?.confirm?.takeIf { status == "needs_confirmation" && it.action == "chatgpt_prompt" }?.prompt,
        action = action,
        message = message,
        files = data?.results.orEmpty().map {
            FoundFile(it.name, it.path, it.type == "folder", it.size, it.modified)
        },
        usedAi = planner == "ai",
        search = data?.results?.let {
            SearchSummary(
                totalMatches = data.totalMatches ?: it.size,
                truncated = data.truncated ?: false,
                searchedFolders = data.searched.orEmpty(),
            )
        },
    )
}
