package com.hbcao.rcode.state

import com.hbcao.rcode.bridge.CoreBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private class FakeBridge(
        private val result: () -> String,
    ) : CoreBridge {
        override fun ping(): String = result()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh publishes core version as Ready`() =
        runTest {
            val vm = MainViewModel(FakeBridge { "rcode-core 0.1.0" }, StandardTestDispatcher(testScheduler))
            vm.refresh()
            assertEquals(MainUiState.Ready("rcode-core 0.1.0"), vm.state.value)
        }

    @Test
    fun `bridge failure publishes Error`() =
        runTest {
            val vm = MainViewModel(FakeBridge { throw IllegalStateException("boom") }, StandardTestDispatcher(testScheduler))
            vm.refresh()
            assertEquals(MainUiState.Error("boom"), vm.state.value)
        }
}
