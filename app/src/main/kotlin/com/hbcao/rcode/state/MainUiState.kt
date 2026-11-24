package com.hbcao.rcode.state

/** UI state for the M0 start screen. */
sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(
        val coreVersion: String,
    ) : MainUiState

    data class Error(
        val message: String,
    ) : MainUiState
}
