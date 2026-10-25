package com.vaani.app.viewmodel

import com.vaani.app.data.AgentConfig
import com.vaani.app.data.AgentError
import com.vaani.app.data.AgentRepository
import com.vaani.app.data.AgentResult
import com.vaani.app.data.AgentStatus
import com.vaani.app.data.CommandResult
import com.vaani.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val GOOD_TOKEN = "a".repeat(43)

private class MemoryStore(var config: AgentConfig? = null) : SettingsStore {
    override fun load() = config
    override fun save(config: AgentConfig) { this.config = config }
    override fun clear() { config = null }
}

private class StatusRepo(private val result: AgentResult<AgentStatus>) : AgentRepository {
    override suspend fun checkStatus() = result
    override suspend fun sendCommand(command: String): AgentResult<CommandResult> = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val tested = mutableListOf<AgentConfig>()
    private fun vm(store: SettingsStore, result: AgentResult<AgentStatus> = AgentResult.Ok(AgentStatus("PC", false, emptyList()))) =
        SettingsViewModel(store) { tested += it; StatusRepo(result) }

    @Test
    fun `loads saved config`() {
        val v = vm(MemoryStore(AgentConfig("http://192.168.0.7:8765", GOOD_TOKEN)))
        assertEquals("http://192.168.0.7:8765", v.state.value.address)
        assertTrue(v.state.value.hasSavedConfig)
    }

    @Test
    fun `save normalises address and stores token`() {
        val store = MemoryStore()
        val v = vm(store)
        v.onAddressChange("192.168.0.7")
        v.onTokenChange("  $GOOD_TOKEN  ")
        v.save()
        assertEquals(AgentConfig("http://192.168.0.7:8765", GOOD_TOKEN), store.config)
        assertTrue(v.state.value.saved)
    }

    @Test
    fun `invalid input is not saved`() {
        val store = MemoryStore()
        val v = vm(store)
        v.onAddressChange("8.8.8.8")
        v.onTokenChange("short")
        v.save()
        assertNull(store.config)
        assertNotNull(v.state.value.addressError)
        assertNotNull(v.state.value.tokenError)
        assertFalse(v.state.value.saved)
    }

    @Test
    fun `test connection reports success without saving`() = runTest(dispatcher) {
        val store = MemoryStore()
        val v = vm(store)
        v.onAddressChange("192.168.0.7:8765")
        v.onTokenChange(GOOD_TOKEN)
        v.testConnection()
        assertTrue(v.state.value.testing)
        advanceUntilIdle()
        assertEquals(TestOutcome(true, "Connected to PC."), v.state.value.testOutcome)
        assertFalse(v.state.value.testing)
        assertNull(store.config)
        assertEquals(listOf(AgentConfig("http://192.168.0.7:8765", GOOD_TOKEN)), tested)
    }

    @Test
    fun `test connection reports failure`() = runTest(dispatcher) {
        val v = vm(MemoryStore(), AgentResult.Failed(AgentError.Unauthorized))
        v.onAddressChange("192.168.0.7")
        v.onTokenChange(GOOD_TOKEN)
        v.testConnection()
        advanceUntilIdle()
        assertFalse(v.state.value.testOutcome!!.ok)
    }

    @Test
    fun `forget clears everything`() {
        val store = MemoryStore(AgentConfig("http://192.168.0.7:8765", GOOD_TOKEN))
        val v = vm(store)
        v.forget()
        assertNull(store.config)
        assertEquals("", v.state.value.token)
    }
}
