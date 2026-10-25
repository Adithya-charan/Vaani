package com.vaani.app.viewmodel

import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A laptop that is asleep until [awake] is set. */
private class SleepyLaptop(var awake: Boolean = false, var error: AgentError = AgentError.Unreachable) : AgentRepository {
    var checks = 0
    override suspend fun checkStatus(): AgentResult<AgentStatus> {
        checks++
        return if (awake) AgentResult.Ok(AgentStatus("PC", true, emptyList())) else AgentResult.Failed(error)
    }
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelReconnectTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** Runs [block] with the app in the foreground; always stops the watch loop, even if an
     *  assertion fails, so the endless loop can't keep virtual time running forever. */
    private fun TestScope.foreground(laptop: SleepyLaptop, block: (HomeViewModel) -> Unit) {
        val vm = HomeViewModel(laptop)
        advanceUntilIdle()
        vm.onForeground()
        runCurrent()
        try {
            block(vm)
        } finally {
            vm.onBackground()
        }
    }

    @Test
    fun `reconnects by itself when the laptop wakes up`() = runTest(dispatcher) {
        val laptop = SleepyLaptop()
        foreground(laptop) { vm ->
            assertEquals(ConnectionState.Disconnected(AgentError.Unreachable), vm.state.value.connection)
            advanceTimeBy(RECONNECT_MIN_MS + 100)
            assertEquals(ConnectionState.Disconnected(AgentError.Unreachable), vm.state.value.connection)
            laptop.awake = true
            advanceTimeBy(RECONNECT_MAX_MS + 100)
            assertEquals(ConnectionState.Connected("PC"), vm.state.value.connection)
        }
    }

    @Test
    fun `checks back off while the laptop stays asleep`() = runTest(dispatcher) {
        val laptop = SleepyLaptop()
        foreground(laptop) {
            val before = laptop.checks
            advanceTimeBy(120_000)
            // 5 + 10 + 20 + 30 + 30 + 30 s ... => about 5 checks in two minutes, not 24
            val checks = laptop.checks - before
            assertTrue("unexpected number of checks: $checks", checks in 4..7)
        }
    }

    @Test
    fun `notices the laptop falling asleep while connected`() = runTest(dispatcher) {
        val laptop = SleepyLaptop(awake = true)
        foreground(laptop) { vm ->
            assertEquals(ConnectionState.Connected("PC"), vm.state.value.connection)
            laptop.awake = false
            advanceTimeBy(HEARTBEAT_MS + 100)
            assertEquals(ConnectionState.Disconnected(AgentError.Unreachable), vm.state.value.connection)
            laptop.awake = true
            advanceTimeBy(RECONNECT_MIN_MS + 100)
            assertEquals(ConnectionState.Connected("PC"), vm.state.value.connection)
        }
    }

    @Test
    fun `heartbeat is gentle while connected`() = runTest(dispatcher) {
        val laptop = SleepyLaptop(awake = true)
        foreground(laptop) {
            val before = laptop.checks
            advanceTimeBy(5 * 60_000)
            val checks = laptop.checks - before
            assertTrue("expected one check per 30 s, got $checks in 5 min", checks in 9..10)
        }
    }

    @Test
    fun `no polling in the background`() = runTest(dispatcher) {
        val laptop = SleepyLaptop()
        foreground(laptop) { vm -> vm.onBackground() }
        val before = laptop.checks
        advanceTimeBy(300_000)
        assertEquals(before, laptop.checks)
    }

    @Test
    fun `wrong token or missing setup is not retried`() = runTest(dispatcher) {
        val laptop = SleepyLaptop(error = AgentError.Unauthorized)
        foreground(laptop) {
            val before = laptop.checks
            advanceTimeBy(120_000)
            assertEquals(before, laptop.checks)
        }
    }
}
