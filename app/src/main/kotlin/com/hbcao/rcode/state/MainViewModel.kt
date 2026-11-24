package com.hbcao.rcode.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hbcao.rcode.bridge.CoreBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val bridge: CoreBridge,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    /** Calls the Rust core off the main thread and publishes the result. */
    suspend fun refresh() {
        _state.value =
            try {
                val version = withContext(workDispatcher) { bridge.ping() }
                MainUiState.Ready(version)
            } catch (e: Exception) {
                MainUiState.Error(e.message ?: e::class.java.simpleName)
            }
    }
}
