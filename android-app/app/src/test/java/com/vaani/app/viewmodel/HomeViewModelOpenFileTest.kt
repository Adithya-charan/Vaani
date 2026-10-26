package com.vaani.app.viewmodel

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.FoundFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

private class OpenRepo : AgentRepository {
    val opened = mutableListOf<FoundFile>()
    var reply: AgentResult<CommandResult> =
        AgentResult.Ok(CommandResult("r", CommandStatus.Success, "open_file", "Opening resume.pdf on your laptop."))
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun checkStatus(): AgentResult<AgentStatus> = AgentResult.Ok(AgentStatus("PC", true, emptyList()))
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> {
        gate?.await()
        return AgentResult.Ok(CommandResult("c", CommandStatus.Success, "find_file", "Found."))
    }
    override suspend fun openConfirmed(file: FoundFile): AgentResult<CommandResult> {
        opened += file
        return reply
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelOpenFileTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tapping a result only asks for confirmation`() = runTest(dispatcher) {
        val repo = OpenRepo()
        val vm = HomeViewModel(repo).also { advanceUntilIdle() }
        vm.onResultTapped(RESUME_DESKTOP)
        advanceUntilIdle()
        assertEquals(RESUME_DESKTOP, vm.state.value.pendingOpen)
        assertTrue(repo.opened.isEmpty())
    }

    @Test
    fun `confirming opens exactly the selected duplicate`() = runTest(dispatcher) {
        val repo = OpenRepo()
        val vm = HomeViewModel(repo).also { advanceUntilIdle() }
        vm.onResultTapped(RESUME_DOWNLOADS)
        vm.onConfirmOpen()
        advanceUntilIdle()
        assertEquals(listOf(RESUME_DOWNLOADS), repo.opened)
        assertNull(vm.state.value.pendingOpen)
        val exchange = vm.state.value.exchanges.single()
        assertEquals("Open resume.pdf", exchange.command)
        assertEquals(CommandStatus.Success, (exchange.reply as Reply.Answered).result.status)
    }

    @Test
    fun `cancelling opens nothing`() = runTest(dispatcher) {
        val repo = OpenRepo()
        val vm = HomeViewModel(repo).also { advanceUntilIdle() }
        vm.onResultTapped(RESUME_DESKTOP)
        vm.onDismissOpen()
        vm.onConfirmOpen() // a stray confirm after cancel must do nothing
        advanceUntilIdle()
        assertTrue(repo.opened.isEmpty())
        assertTrue(vm.state.value.exchanges.isEmpty())
    }

    @Test
    fun `refusal from the laptop is shown, not treated as success`() = runTest(dispatcher) {
        val repo = OpenRepo().apply {
            reply = AgentResult.Ok(CommandResult("r", CommandStatus.Rejected, "open_file",
                "resume.pdf has changed since you searched. Search again to open it."))
        }
        val vm = HomeViewModel(repo).also { advanceUntilIdle() }
        vm.onResultTapped(RESUME_DESKTOP); vm.onConfirmOpen()
        advanceUntilIdle()
        val result = (vm.state.value.exchanges.single().reply as Reply.Answered).result
        assertEquals(CommandStatus.Rejected, result.status)
    }

    @Test
    fun `network failure is shown as failure`() = runTest(dispatcher) {
        val repo = OpenRepo().apply { reply = AgentResult.Failed(AgentError.Unreachable) }
        val vm = HomeViewModel(repo).also { advanceUntilIdle() }
        vm.onResultTapped(RESUME_DESKTOP); vm.onConfirmOpen()
        advanceUntilIdle()
        assertEquals(Reply.Failed(AgentError.Unreachable), vm.state.value.exchanges.single().reply)
    }

    @Test
    fun `cannot start opening while another command is running`() = runTest(dispatcher) {
        val repo = OpenRepo().apply { gate = CompletableDeferred() }
        val vm = HomeViewModel(repo).also { advanceUntilIdle() }
        vm.onQuickCommand("find resume")
        advanceUntilIdle()
        vm.onResultTapped(RESUME_DESKTOP)
        assertNull(vm.state.value.pendingOpen)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(repo.opened.isEmpty())
    }
}
