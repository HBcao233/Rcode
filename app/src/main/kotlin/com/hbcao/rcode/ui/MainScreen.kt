package com.hbcao.rcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hbcao.rcode.state.MainUiState
import com.hbcao.rcode.state.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Rcode", style = MaterialTheme.typography.headlineMedium)
        Text(
            text =
                when (val s = state) {
                    MainUiState.Loading -> "Loading core…"
                    is MainUiState.Ready -> s.coreVersion
                    is MainUiState.Error -> "Core error: ${s.message}"
                },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
