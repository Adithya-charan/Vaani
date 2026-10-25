package com.vaani.app.network

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.FoundFile
import com.vaani.app.data.SearchSummary
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

    override suspend fun checkStatus(): AgentResult<AgentStatus> =
        execute(request("status").get().build()) { body ->
            val r = json.decodeFromString<CommandResponseDto>(body)
            AgentStatus(
                hostname = r.data?.hostname ?: "laptop",
                aiPlanner = r.data?.aiPlanner ?: false,
                approvedFolders = r.data?.approvedFolders.orEmpty(),
            )
        }

    override suspend fun sendCommand(command: String): AgentResult<CommandResult> {
        val payload = json.encodeToString(CommandRequestDto.serializer(), CommandRequestDto(command))
        return execute(request("commands").post(payload.toRequestBody(JSON_MEDIA)).build()) { body ->
            json.decodeFromString<CommandResponseDto>(body).toResult()
        }
    }

    private fun request(path: String) = Request.Builder()
        .url(baseUrl.newBuilder().addPathSegment(path).build())
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    private suspend fun <T> execute(request: Request, parse: (String) -> T): AgentResult<T> =
        withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(request).await()
            } catch (e: InterruptedIOException) {
                return@withContext AgentResult.Failed(AgentError.Timeout)
            } catch (e: IOException) {
                return@withContext AgentResult.Failed(AgentError.Unreachable)
            }
            response.use {
                when {
                    it.code == 401 || it.code == 403 -> AgentResult.Failed(AgentError.Unauthorized)
                    it.code == 422 -> AgentResult.Failed(AgentError.Server("The laptop couldn't read that command."))
                    !it.isSuccessful -> AgentResult.Failed(AgentError.Server("The laptop agent returned an error (HTTP ${it.code})."))
                    else -> try {
                        AgentResult.Ok(parse(it.body.string()))
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
private data class FoundFileDto(
    val name: String,
    val path: String,
    val type: String = "file",
    val size: Long? = null,
    val modified: String? = null,
)

@Serializable
private data class ResponseDataDto(
    val hostname: String? = null,
    @kotlinx.serialization.SerialName("ai_planner") val aiPlanner: Boolean? = null,
    @kotlinx.serialization.SerialName("approved_folders") val approvedFolders: List<String>? = null,
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
            else -> CommandStatus.Error
        },
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
