package com.vaani.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

interface ReplySpeaker {
    /** Speaks [text]; suspends until playback finishes. Cancelling stops playback. */
    suspend fun speak(text: String)
    fun stop()
    fun release() {}
}

/**
 * Speaks with Sarvam Bulbul through the laptop. If that fails (laptop or Sarvam
 * unavailable), falls back to Android's built-in TextToSpeech.
 */
class AndroidReplySpeaker(
    context: Context,
    private val repository: AgentRepository,
) : ReplySpeaker {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override suspend fun speak(text: String) {
        stop()
        when (val r = repository.speak(text)) {
            is AgentResult.Ok -> if (playWav(r.value)) return
            is AgentResult.Failed -> Unit
        }
        speakWithAndroidTts(text)
    }

    private suspend fun playWav(wav: ByteArray): Boolean {
        val file = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "vaani-reply.wav").apply { writeBytes(wav) }
        }
        return suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(attributes)
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    releasePlayer(mp)
                    if (cont.isActive) cont.resume(true)
                }
                mp.setOnErrorListener { _, _, _ ->
                    releasePlayer(mp)
                    if (cont.isActive) cont.resume(false)
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                releasePlayer(mp)
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { releasePlayer(mp) }
        }
    }

    private fun releasePlayer(mp: MediaPlayer) {
        if (player === mp) player = null
        runCatching { if (mp.isPlaying) mp.stop() }
        mp.release()
    }

    private suspend fun speakWithAndroidTts(text: String) {
        val engine = androidTts() ?: return
        suspendCancellableCoroutine { cont ->
            val id = "vaani-${System.nanoTime()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resume(Unit)
            }
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    private suspend fun androidTts(): TextToSpeech? {
        val ready = ttsReady ?: CompletableDeferred<Boolean>().also { deferred ->
            ttsReady = deferred
            tts = TextToSpeech(appContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    tts?.setAudioAttributes(attributes)
                    tts?.language = Locale.forLanguageTag("en-IN")
                }
                deferred.complete(ok)
            }
        }
        return if (withTimeoutOrNull(3_000) { ready.await() } == true) tts else null
    }

    override fun stop() {
        player?.let { releasePlayer(it) }
        tts?.stop()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = null
    }
}
