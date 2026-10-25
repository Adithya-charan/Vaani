package com.vaani.app.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000
const val MAX_RECORDING_MS = 30_000L

object Wav {
    private const val HEADER_SIZE = 44

    /** Wraps 16-bit little-endian mono PCM in a RIFF/WAVE header. */
    fun fromPcm16(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // audio format: PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort()) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()
        return header + pcm
    }

    fun durationMs(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE): Long = pcmBytes * 1000L / (sampleRate * 2)

    /** Peak level 0..1 of a 16-bit LE PCM buffer, for the mic meter. */
    fun level(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val abs = kotlin.math.abs(sample.toShort().toInt())
            if (abs > peak) peak = abs
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }
}
