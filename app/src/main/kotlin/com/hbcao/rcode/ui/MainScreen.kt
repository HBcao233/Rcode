package com.hbcao.rcode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbcao.rcode.R
import com.hbcao.rcode.editor.EditorView
import com.hbcao.rcode.icons.Apps
import com.hbcao.rcode.icons.Code
import com.hbcao.rcode.icons.ExitToApp
import com.hbcao.rcode.icons.File
import com.hbcao.rcode.icons.FileDownload
import com.hbcao.rcode.icons.FolderOpen
import com.hbcao.rcode.icons.Help
import com.hbcao.rcode.icons.History
import com.hbcao.rcode.icons.Save
import com.hbcao.rcode.icons.Terminal2
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
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit",
                        )
                    }
                    MoreMenuButton()
                    /*Text(
                        text = coreStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,  // rgb(73, 69, 79)
                        modifier = Modifier.padding(end = 8.dp),
                    )*/
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
        text = scheme.onBackground.toArgb(), // rgb(29, 27, 32)
        caret = scheme.primary.toArgb(), // rgb(103, 80, 164)
        composingUnderline = scheme.primary.toArgb(), // rgb(103, 80, 164)
    )
}

@Composable
private fun MoreMenuButton() {
    var showMenu by remember { mutableStateOf(false) }

    IconButton(onClick = { showMenu = true }) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "更多选项",
        )
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }, // 点击外部/菜单外区域时收起
        modifier = Modifier.width(IntrinsicSize.Max), // 菜单宽度适配内容
    ) {
        DropdownMenuItem(
            text = { Text("创建新文件") },
            onClick = {
                showMenu = false
            },
            trailingIcon = { Icon(File, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("保存") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Save, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("另存为") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(FileDownload, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("打开文件") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(FolderOpen, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("关闭文件") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("最近打开") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(History, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("查找文件") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("控制台") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Code, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("终端") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Terminal2, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("正在运行中的进程") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Apps, contentDescription = null) },
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("设置") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("帮助") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(Help, contentDescription = null) },
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("退出") },
            onClick = { showMenu = false },
            trailingIcon = { Icon(ExitToApp, contentDescription = null) },
        )
    }
}
