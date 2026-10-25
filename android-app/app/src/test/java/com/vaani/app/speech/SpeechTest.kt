package com.vaani.app.speech

import com.vaani.app.data.AgentError
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.FoundFile
import com.vaani.app.data.SearchSummary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {

    @Test
    fun `header is a valid 16 kHz mono PCM wav`() {
        val pcm = ByteArray(32_000) { (it % 7).toByte() } // 1 second
        val wav = Wav.fromPcm16(pcm)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, b.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(1000L, Wav.durationMs(pcm.size))
    }

    @Test
    fun `level reflects peak amplitude`() {
        val silence = ByteArray(200)
        assertEquals(0f, Wav.level(silence, silence.size))
        val loud = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(Short.MIN_VALUE).array()
        assertEquals(1f, Wav.level(loud, loud.size))
        val half = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(16384).array()
        assertEquals(0.5f, Wav.level(half, half.size), 0.001f)
    }
}

class SpokenReplyTest {

    @Test
    fun `plain results are spoken as the message`() {
        val r = CommandResult("r", CommandStatus.Success, "open_calculator", "Calculator opened.")
        assertEquals("Calculator opened.", SpokenReply.of(r))
    }

    @Test
    fun `search results are summarised instead of reading paths`() {
        val r = CommandResult(
            "r", CommandStatus.Success, "find_file", "Found Desktop/B TECH/other/my resume.pdf and 1 more.",
            files = listOf(FoundFile("my resume.pdf", "Desktop/B TECH/other/my resume.pdf", false)),
            search = SearchSummary(2, false, listOf("Desktop")),
        )
        assertEquals("I found 2 matches. The top one is my resume, in Desktop.", SpokenReply.of(r))

        val folder = r.copy(files = listOf(FoundFile("JavaProject", "Documents/JavaProject", true)),
            search = SearchSummary(1, false, listOf("Documents")))
        assertEquals("I found 1 match. The top one is the folder JavaProject, in Documents.", SpokenReply.of(folder))
    }

    @Test
    fun `errors are spoken in plain words`() {
        assertTrue(SpokenReply.of(AgentError.Unreachable).startsWith("Can't reach your laptop"))
    }
}
