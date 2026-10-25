package com.vaani.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

class Recording(val wav: ByteArray, val durationMs: Long)

interface VoiceRecorder {
    /** Current input level 0..1 while recording. */
    val level: StateFlow<Float>

    /** Starts recording; false if the microphone is unavailable or permission is missing. */
    fun start(): Boolean

    /** Stops and returns the audio, or null if nothing was recorded. */
    fun stop(): Recording?

    /** Stops and discards the audio. */
    fun cancel()
}

/** Records 16 kHz mono 16-bit PCM with AudioRecord and packages it as WAV. */
class AndroidVoiceRecorder : VoiceRecorder {

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission") // the UI requests RECORD_AUDIO before calling start()
    override fun start(): Boolean {
        if (recording) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2) // at least 200 ms
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            r.release()
            return false
        }
        pcm = ByteArrayOutputStream()
        record = r
        recording = true
        val maxBytes = (SAMPLE_RATE * 2 * MAX_RECORDING_MS / 1000).toInt()
        thread = Thread({
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val n = r.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (pcm.size() + n <= maxBytes) pcm.write(buffer, 0, n)
                _level.value = Wav.level(buffer, n)
            }
        }, "vaani-recorder").apply { start() }
        return true
    }

    private fun halt(keepTail: Boolean = false) {
        recording = false
        thread?.join(500)
        thread = null
        record?.run {
            try {
                stop()
                // Audio still buffered when the user tapped stop is the end of their sentence.
                if (keepTail) {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = read(buffer, 0, buffer.size)
                        if (n <= 0) break
                        pcm.write(buffer, 0, n)
                    }
                }
            } catch (e: IllegalStateException) {
                // already stopped
            }
            release()
        }
        record = null
        _level.value = 0f
    }

    override fun stop(): Recording? {
        if (record == null) return null
        halt(keepTail = true)
        val bytes = pcm.toByteArray()
        pcm = ByteArrayOutputStream()
        if (bytes.isEmpty()) return null
        return Recording(Wav.fromPcm16(bytes), Wav.durationMs(bytes.size))
    }

    override fun cancel() {
        halt()
        pcm = ByteArrayOutputStream()
    }
}
