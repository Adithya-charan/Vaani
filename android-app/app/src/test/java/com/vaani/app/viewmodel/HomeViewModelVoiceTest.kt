package com.vaani.app.viewmodel

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.MemoryVoicePrefs
import com.vaani.app.data.Transcription
import com.vaani.app.speech.MAX_RECORDING_MS
import com.vaani.app.speech.Recording
import com.vaani.app.speech.ReplySpeaker
import com.vaani.app.speech.VoiceRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeRecorder(var canStart: Boolean = true, var durationMs: Long = 2_000) : VoiceRecorder {
    override val level: StateFlow<Float> = MutableStateFlow(0f)
    var started = 0
    var cancelled = 0
    var recording = false

    override fun start(): Boolean {
        if (!canStart) return false
        started++
        recording = true
        return true
    }

    override fun stop(): Recording? {
        if (!recording) return null
        recording = false
        return Recording(ByteArray(64) { 1 }, durationMs)
    }

    override fun cancel() {
        recording = false
        cancelled++
    }
}

private class FakeSpeaker : ReplySpeaker {
    val spoken = mutableListOf<String>()
    var stops = 0
    var hold = false

    override suspend fun speak(text: String) {
        spoken += text
        if (hold) awaitCancellation()
    }

    override fun stop() {
        stops++
    }
}

private class VoiceRepo : AgentRepository {
    var transcript: AgentResult<Transcription> = AgentResult.Ok(Transcription("Open calculator, please.", "en-IN"))
    var transcribeGate: CompletableDeferred<Unit>? = null
    val transcribed = mutableListOf<Int>()
    val sent = mutableListOf<String>()
    var commandReply: AgentResult<CommandResult> =
        AgentResult.Ok(CommandResult("id", CommandStatus.Success, "open_calculator", "Calculator opened."))

    override suspend fun checkStatus(): AgentResult<AgentStatus> = AgentResult.Ok(AgentStatus("PC", true, emptyList()))
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> {
        sent += command
        return commandReply
    }

    override suspend fun transcribe(wav: ByteArray, language: String): AgentResult<Transcription> {
        transcribed += wav.size
        transcribeGate?.await()
        return transcript
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelVoiceTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val repo = VoiceRepo()
    private val recorder = FakeRecorder()
    private val speaker = FakeSpeaker()
    private val prefs = MemoryVoicePrefs()

    private fun TestScope.vm() = HomeViewModel(repo, recorder, speaker, prefs).also { advanceUntilIdle() }

    @Test
    fun `tap to record, tap to send runs the transcript as a command and speaks the reply`() = runTest(dispatcher) {
        val vm = vm()
        vm.onMicClick()
        runCurrent()
        assertTrue(vm.state.value.voice is VoiceState.Recording)
        advanceTimeBy(1_550)
        assertEquals(VoiceState.Recording(1_500), vm.state.value.voice)

        vm.onMicClick()
        assertEquals(VoiceState.Transcribing, vm.state.value.voice)
        advanceUntilIdle()

        assertEquals(listOf(64), repo.transcribed)
        assertEquals(listOf("Open calculator, please."), repo.sent)
        val exchange = vm.state.value.exchanges.single()
        assertTrue(exchange.viaVoice)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals(listOf("Calculator opened."), speaker.spoken)
    }

    @Test
    fun `typed commands are not spoken`() = runTest(dispatcher) {
        val vm = vm()
        vm.onQuickCommand("Open Calculator")
        advanceUntilIdle()
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `muted voice commands are not spoken and mute is remembered`() = runTest(dispatcher) {
        val vm = vm()
        vm.onToggleMute()
        assertTrue(prefs.muted)
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000); vm.onMicClick()
        advanceUntilIdle()
        assertEquals(1, repo.sent.size)
        assertTrue(speaker.spoken.isEmpty())

        val again = HomeViewModel(repo, recorder, speaker, prefs)
        assertTrue(again.state.value.muted)
    }

    @Test
    fun `too short recording is not uploaded`() = runTest(dispatcher) {
        recorder.durationMs = 300
        val vm = vm()
        vm.onMicClick(); runCurrent(); vm.onMicClick()
        advanceUntilIdle()
        assertTrue(repo.transcribed.isEmpty())
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun `empty transcript asks to try again without sending`() = runTest(dispatcher) {
        repo.transcript = AgentResult.Ok(Transcription("  ", null))
        val vm = vm()
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000); vm.onMicClick()
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        assertTrue(vm.state.value.notice!!.contains("couldn't make out"))
    }

    @Test
    fun `transcription failure shows the laptop's message and keeps typing available`() = runTest(dispatcher) {
        repo.transcript = AgentResult.Failed(AgentError.Server("The voice service is busy or out of quota."))
        val vm = vm()
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000); vm.onMicClick()
        advanceUntilIdle()
        assertEquals("The voice service is busy or out of quota.", vm.state.value.notice)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        vm.onInputChange("open notepad")
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun `unreachable laptop during transcription marks disconnected`() = runTest(dispatcher) {
        repo.transcript = AgentResult.Failed(AgentError.Unreachable)
        val vm = vm()
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000); vm.onMicClick()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected(AgentError.Unreachable), vm.state.value.connection)
    }

    @Test
    fun `recording stops itself at the time limit`() = runTest(dispatcher) {
        val vm = vm()
        vm.onMicClick()
        advanceTimeBy(MAX_RECORDING_MS + 200)
        advanceUntilIdle()
        assertEquals(1, repo.transcribed.size)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
    }

    @Test
    fun `cancel while recording discards audio`() = runTest(dispatcher) {
        val vm = vm()
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000)
        vm.onCancel()
        advanceUntilIdle()
        assertEquals(1, recorder.cancelled)
        assertTrue(repo.transcribed.isEmpty())
        assertEquals(VoiceState.Idle, vm.state.value.voice)
    }

    @Test
    fun `cancel while transcribing sends nothing`() = runTest(dispatcher) {
        repo.transcribeGate = CompletableDeferred()
        val vm = vm()
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000); vm.onMicClick()
        runCurrent()
        assertEquals(VoiceState.Transcribing, vm.state.value.voice)
        vm.onCancel()
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        assertEquals(VoiceState.Idle, vm.state.value.voice)
    }

    @Test
    fun `microphone that fails to start shows a notice`() = runTest(dispatcher) {
        recorder.canStart = false
        val vm = vm()
        vm.onMicClick()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun `starting to record or muting stops current speech`() = runTest(dispatcher) {
        speaker.hold = true
        val vm = vm()
        vm.onMicClick(); runCurrent(); advanceTimeBy(1_000); vm.onMicClick()
        advanceUntilIdle()
        assertTrue(vm.state.value.speaking)
        val stopsBefore = speaker.stops
        vm.onMicClick() // start a new recording
        runCurrent()
        assertTrue(speaker.stops > stopsBefore)
        assertFalse(vm.state.value.speaking)
    }

    @Test
    fun `permission denied explains how to recover`() = runTest(dispatcher) {
        val vm = vm()
        vm.onMicPermissionDenied(permanently = true)
        assertTrue(vm.state.value.notice!!.contains("Settings"))
        assertEquals(0, recorder.started)
    }
}
