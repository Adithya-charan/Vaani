package com.vaani.app.viewmodel

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val STATUS = AgentStatus("LAPTOP", aiPlanner = false, approvedFolders = listOf("Documents"))

private fun result(status: CommandStatus = CommandStatus.Success, message: String = "Calculator opened.") =
    CommandResult("id", status, "open_calculator", message)

/** Each call suspends until the test completes the corresponding deferred. */
private class FakeRepository(
    var status: AgentResult<AgentStatus> = AgentResult.Ok(STATUS),
) : AgentRepository {
    val sent = mutableListOf<String>()
    var nextReply = CompletableDeferred<AgentResult<CommandResult>>()

    override suspend fun checkStatus() = status
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> {
        sent += command
        return nextReply.await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(repo: FakeRepository): HomeViewModel =
        HomeViewModel(repo).also { advanceUntilIdle() }

    @Test
    fun `checks connection on start`() = runTest(dispatcher) {
        val vm = newViewModel(FakeRepository())
        assertEquals(ConnectionState.Connected("LAPTOP"), vm.state.value.connection)
    }

    @Test
    fun `shows disconnected when laptop unreachable`() = runTest(dispatcher) {
        val vm = newViewModel(FakeRepository(status = AgentResult.Failed(AgentError.Unreachable)))
        assertEquals(ConnectionState.Disconnected(AgentError.Unreachable), vm.state.value.connection)
    }

    @Test
    fun `blank input is not sent`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = newViewModel(repo)
        vm.onInputChange("   ")
        vm.onSend()
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        assertTrue(vm.state.value.exchanges.isEmpty())
    }

    @Test
    fun `send shows loading then result and clears input`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = newViewModel(repo)
        vm.onInputChange("  Open Calculator  ")
        vm.onSend()
        advanceUntilIdle()

        assertEquals(listOf("Open Calculator"), repo.sent)
        assertEquals("", vm.state.value.input)
        assertTrue(vm.state.value.isSending)
        assertEquals(Reply.Pending, vm.state.value.exchanges.single().reply)

        repo.nextReply.complete(AgentResult.Ok(result()))
        advanceUntilIdle()

        assertFalse(vm.state.value.isSending)
        assertEquals(Reply.Answered(result()), vm.state.value.exchanges.single().reply)
    }

    @Test
    fun `cannot send a second command while one is pending`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = newViewModel(repo)
        vm.onQuickCommand("Open Calculator")
        advanceUntilIdle()
        vm.onInputChange("Open Notepad")
        vm.onSend()
        vm.onQuickCommand("Open Notepad")
        advanceUntilIdle()
        assertEquals(listOf("Open Calculator"), repo.sent)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun `network failure is shown as failure and marks laptop disconnected`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = newViewModel(repo)
        vm.onQuickCommand("Open Calculator")
        repo.nextReply.complete(AgentResult.Failed(AgentError.Timeout))
        advanceUntilIdle()
        assertEquals(Reply.Failed(AgentError.Timeout), vm.state.value.exchanges.single().reply)
        assertEquals(ConnectionState.Disconnected(AgentError.Timeout), vm.state.value.connection)
    }

    @Test
    fun `rejected command keeps connection and shows rejection`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = newViewModel(repo)
        val rejected = result(CommandStatus.Rejected, "This action is not supported.")
        vm.onQuickCommand("delete everything")
        repo.nextReply.complete(AgentResult.Ok(rejected))
        advanceUntilIdle()
        assertEquals(Reply.Answered(rejected), vm.state.value.exchanges.single().reply)
        assertEquals(ConnectionState.Connected("LAPTOP"), vm.state.value.connection)
    }

    @Test
    fun `cancel marks the pending command cancelled`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = newViewModel(repo)
        vm.onQuickCommand("Open Calculator")
        advanceUntilIdle()
        vm.onCancel()
        advanceUntilIdle()
        assertEquals(Reply.Cancelled, vm.state.value.exchanges.single().reply)
        assertFalse(vm.state.value.isSending)
    }

    @Test
    fun `input is capped at max length`() = runTest(dispatcher) {
        val vm = newViewModel(FakeRepository())
        vm.onInputChange("x".repeat(MAX_COMMAND_LENGTH + 100))
        assertEquals(MAX_COMMAND_LENGTH, vm.state.value.input.length)
    }

    @Test
    fun `mic shows a notice until acknowledged`() = runTest(dispatcher) {
        val vm = newViewModel(FakeRepository())
        vm.onMicClick()
        assertTrue(vm.state.value.notice != null)
        vm.onNoticeShown()
        assertEquals(null, vm.state.value.notice)
    }
}
