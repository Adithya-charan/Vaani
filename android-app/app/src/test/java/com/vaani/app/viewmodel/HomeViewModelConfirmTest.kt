package com.vaani.app.viewmodel

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.Confirmation
import com.vaani.app.data.FoundFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val RESUME_DESKTOP = FoundFile("resume.pdf", "Desktop/resume.pdf", false, 1000, "2026-10-25T10:00:00+00:00")
private val RESUME_DOWNLOADS = FoundFile("resume.pdf", "Downloads/resume.pdf", false, 2000, "2026-10-20T10:00:00+00:00")
private val PROJECT = FoundFile("JavaProject", "Documents/JavaProject", true)

private class ConfirmRepo(private val recipients: List<String> = emptyList()) : AgentRepository {
    val confirmed = mutableListOf<Confirmation>()
    var commandReply: CommandResult = CommandResult("c", CommandStatus.Success, "find_file", "Found.")
    var reply: AgentResult<CommandResult> =
        AgentResult.Ok(CommandResult("r", CommandStatus.Success, "open_file", "Done."))
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun checkStatus(): AgentResult<AgentStatus> =
        AgentResult.Ok(AgentStatus("PC", true, emptyList(), telegramRecipients = recipients, chatGptAutomation = true))

    override suspend fun sendCommand(command: String): AgentResult<CommandResult> {
        gate?.await()
        return AgentResult.Ok(commandReply)
    }

    override suspend fun runConfirmed(confirmation: Confirmation): AgentResult<CommandResult> {
        confirmed += confirmation
        return reply
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelConfirmTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(repo: ConfirmRepo) = HomeViewModel(repo).also { advanceUntilIdle() }

    // --- open a file -------------------------------------------------------------------

    @Test
    fun `without recipients a tap goes straight to open confirmation and sends nothing`() = runTest(dispatcher) {
        val repo = ConfirmRepo()
        val vm = vm(repo)
        vm.onResultTapped(RESUME_DESKTOP)
        advanceUntilIdle()
        assertEquals(Confirmation.OpenFile(RESUME_DESKTOP), vm.state.value.pendingConfirm)
        assertTrue(repo.confirmed.isEmpty())
    }

    @Test
    fun `confirming opens exactly the selected duplicate`() = runTest(dispatcher) {
        val repo = ConfirmRepo()
        val vm = vm(repo)
        vm.onResultTapped(RESUME_DOWNLOADS)
        vm.onConfirm()
        advanceUntilIdle()
        assertEquals(listOf<Confirmation>(Confirmation.OpenFile(RESUME_DOWNLOADS)), repo.confirmed)
        assertNull(vm.state.value.pendingConfirm)
        assertEquals("Open resume.pdf", vm.state.value.exchanges.single().command)
    }

    @Test
    fun `cancel sends nothing, even with a stray confirm afterwards`() = runTest(dispatcher) {
        val repo = ConfirmRepo()
        val vm = vm(repo)
        vm.onResultTapped(RESUME_DESKTOP)
        vm.onDismissConfirm()
        vm.onConfirm()
        advanceUntilIdle()
        assertTrue(repo.confirmed.isEmpty())
        assertTrue(vm.state.value.exchanges.isEmpty())
    }

    @Test
    fun `refusals and failures are shown, never as success`() = runTest(dispatcher) {
        val repo = ConfirmRepo().apply {
            reply = AgentResult.Ok(CommandResult("r", CommandStatus.Rejected, "open_file", "resume.pdf has changed."))
        }
        val vm = vm(repo)
        vm.onResultTapped(RESUME_DESKTOP); vm.onConfirm()
        advanceUntilIdle()
        assertEquals(CommandStatus.Rejected, (vm.state.value.exchanges.last().reply as Reply.Answered).result.status)

        repo.reply = AgentResult.Failed(AgentError.Timeout)
        vm.onResultTapped(RESUME_DESKTOP); vm.onConfirm()
        advanceUntilIdle()
        assertEquals(Reply.Failed(AgentError.Timeout), vm.state.value.exchanges.last().reply)
    }

    @Test
    fun `cannot pick a result while another command is running`() = runTest(dispatcher) {
        val repo = ConfirmRepo().apply { gate = CompletableDeferred() }
        val vm = vm(repo)
        vm.onQuickCommand("find resume")
        advanceUntilIdle()
        vm.onResultTapped(RESUME_DESKTOP)
        assertNull(vm.state.value.pendingConfirm)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(repo.confirmed.isEmpty())
    }

    // --- Telegram ------------------------------------------------------------------------

    @Test
    fun `with recipients a tap offers choices first`() = runTest(dispatcher) {
        val vm = vm(ConfirmRepo(listOf("Amma", "Me")))
        assertEquals(listOf("Amma", "Me"), vm.state.value.telegramRecipients)
        vm.onResultTapped(RESUME_DESKTOP)
        assertEquals(RESUME_DESKTOP, vm.state.value.fileChoice)
        assertNull(vm.state.value.pendingConfirm)
        vm.onChooseOpen()
        assertEquals(Confirmation.OpenFile(RESUME_DESKTOP), vm.state.value.pendingConfirm)
    }

    @Test
    fun `send to a recipient needs its own confirmation`() = runTest(dispatcher) {
        val repo = ConfirmRepo(listOf("Amma"))
        val vm = vm(repo)
        vm.onResultTapped(RESUME_DESKTOP)
        vm.onChooseTelegram("Amma")
        assertNull(vm.state.value.fileChoice)
        assertEquals(Confirmation.SendTelegram(RESUME_DESKTOP, "Amma"), vm.state.value.pendingConfirm)
        assertTrue(repo.confirmed.isEmpty())
        vm.onConfirm()
        advanceUntilIdle()
        assertEquals(listOf<Confirmation>(Confirmation.SendTelegram(RESUME_DESKTOP, "Amma")), repo.confirmed)
        assertEquals("Send resume.pdf to Amma", vm.state.value.exchanges.single().command)
    }

    @Test
    fun `folders are labelled as ZIP and unknown recipients are ignored`() = runTest(dispatcher) {
        val repo = ConfirmRepo(listOf("Amma"))
        val vm = vm(repo)
        vm.onResultTapped(PROJECT)
        vm.onChooseTelegram("Stranger")
        assertNull(vm.state.value.pendingConfirm)
        vm.onChooseTelegram("Amma")
        vm.onConfirm()
        advanceUntilIdle()
        assertEquals("Send JavaProject (ZIP) to Amma", vm.state.value.exchanges.single().command)
    }

    // --- ChatGPT -------------------------------------------------------------------------

    @Test
    fun `a ChatGPT proposal opens the confirmation with the exact prompt`() = runTest(dispatcher) {
        val repo = ConfirmRepo().apply {
            commandReply = CommandResult("p", CommandStatus.NeedsConfirmation, null,
                "Send this prompt to ChatGPT on your laptop?", chatGptPrompt = "What is DNS?")
        }
        val vm = vm(repo)
        vm.onQuickCommand("ask chatgpt What is DNS?")
        advanceUntilIdle()
        assertEquals(Confirmation.AskChatGPT("What is DNS?"), vm.state.value.pendingConfirm)
        assertTrue(repo.confirmed.isEmpty())

        vm.onConfirm()
        advanceUntilIdle()
        assertEquals(listOf<Confirmation>(Confirmation.AskChatGPT("What is DNS?")), repo.confirmed)
    }

    @Test
    fun `declining a ChatGPT prompt sends nothing`() = runTest(dispatcher) {
        val repo = ConfirmRepo().apply {
            commandReply = CommandResult("p", CommandStatus.NeedsConfirmation, null, "Send?", chatGptPrompt = "hi")
        }
        val vm = vm(repo)
        vm.onQuickCommand("ask chatgpt hi")
        advanceUntilIdle()
        vm.onDismissConfirm()
        advanceUntilIdle()
        assertTrue(repo.confirmed.isEmpty())
    }
}
