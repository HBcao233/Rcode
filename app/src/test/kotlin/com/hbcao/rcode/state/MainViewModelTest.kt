package com.hbcao.rcode.state

import com.hbcao.rcode.bridge.CoreBridge
import com.hbcao.rcode.editor.FakeEditorSession
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
            val viewModel =
                MainViewModel(
                    FakeBridge { "rcode-core 0.1.0" },
                    FakeEditorSession(),
                    StandardTestDispatcher(testScheduler),
                )
            viewModel.refresh()
            assertEquals(MainUiState.Ready("rcode-core 0.1.0"), viewModel.state.value)
        }

    @Test
    fun `bridge failure publishes Error`() =
        runTest {
            val viewModel =
                MainViewModel(
                    FakeBridge { throw IllegalStateException("boom") },
                    FakeEditorSession(),
                    StandardTestDispatcher(testScheduler),
                )
            viewModel.refresh()
            assertEquals(MainUiState.Error("boom"), viewModel.state.value)
        }

    @Test
    fun `editor session is exposed for the screen`() {
        val session = FakeEditorSession("hello")
        val viewModel =
            MainViewModel(
                FakeBridge { "rcode-core 0.1.0" },
                session,
                StandardTestDispatcher(),
            )

        assertEquals("hello", viewModel.editorSession.text())
    }
}
