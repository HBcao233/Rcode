package com.hbcao.rcode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbcao.rcode.R
import com.hbcao.rcode.editor.EditorView
import com.hbcao.rcode.state.MainUiState
import com.hbcao.rcode.state.MainViewModel
import com.hbcao.rcode.ui.theme.EditorColors

/**
 * Main (and currently only) screen: the M1.0 editor for `untitled.txt`.
 *
 * The document session lives in [MainViewModel] so it survives rotation; the custom
 * [EditorView] only owns rendering state and is re-created from the Rust session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coreStatus = coreStatusText(state)
    val editorColors = editorColors()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.editor_title_untitled),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.editor_title_untitled),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { /* do something */ }) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "Sidebar",
                        )
                    }
                },
                actions = {
                    Text(
                        text = coreStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,  // rgb(73, 69, 79)
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
    ) { innerPadding ->
        AndroidView(
            factory = { context -> EditorView(context, viewModel.editorSession, editorColors) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
private fun coreStatusText(state: MainUiState): String =
    when (state) {
        MainUiState.Loading -> ""
        is MainUiState.Ready -> state.coreVersion
        is MainUiState.Error -> stringResource(R.string.editor_core_error, state.message)
    }

@Composable
private fun editorColors(): EditorColors {
    val scheme = MaterialTheme.colorScheme
    return EditorColors(
        background = Color(242, 241, 247).toArgb(),
        text = scheme.onBackground.toArgb(),  // rgb(29, 27, 32)
        caret = scheme.primary.toArgb(),  // rgb(103, 80, 164)
        composingUnderline = scheme.primary.toArgb(), // rgb(103, 80, 164)
    )
}
