package com.hbcao.rcode.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbcao.rcode.R
import com.hbcao.rcode.editor.EditorView
import com.hbcao.rcode.editor.UnsupportedEncodingKind
import com.hbcao.rcode.icons.Apps
import com.hbcao.rcode.icons.Code
import com.hbcao.rcode.icons.ExitToApp
import com.hbcao.rcode.icons.File
import com.hbcao.rcode.icons.FileDownload
import com.hbcao.rcode.icons.FolderOpen
import com.hbcao.rcode.icons.Help
import com.hbcao.rcode.icons.History
import com.hbcao.rcode.icons.KeyboardArrowDown
import com.hbcao.rcode.icons.KeyboardArrowLeft
import com.hbcao.rcode.icons.KeyboardArrowRight
import com.hbcao.rcode.icons.KeyboardArrowUp
import com.hbcao.rcode.icons.KeyboardTab
import com.hbcao.rcode.icons.MoreHoriz
import com.hbcao.rcode.icons.Redo
import com.hbcao.rcode.icons.Save
import com.hbcao.rcode.icons.Terminal2
import com.hbcao.rcode.icons.Undo
import com.hbcao.rcode.platform.document.toDocumentKey
import com.hbcao.rcode.state.DocumentUiState
import com.hbcao.rcode.state.MainUiState
import com.hbcao.rcode.state.MainViewModel
import com.hbcao.rcode.state.OpenFailure
import com.hbcao.rcode.state.OpenRequest
import com.hbcao.rcode.ui.theme.EditorColors

/**
 * Main (and currently only) screen: the M1 editor.
 *
 * The document session lives in [MainViewModel] so it survives rotation; the custom
 * [EditorView] only owns rendering state and is re-created from the Rust session. The
 * SAF picker entry point, the document title and the open-flow prompts live here
 * (T-0012).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documentState by viewModel.documentState.collectAsStateWithLifecycle()
    val openRequest by viewModel.openRequest.collectAsStateWithLifecycle()
    val openError by viewModel.openError.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val coreStatus = coreStatusText(state)
    val editorColors = editorColors()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val openDocumentLauncher =
        rememberLauncherForActivityResult(OpenDocumentWithWriteAccess()) { uri ->
            if (uri != null) viewModel.openDocument(uri.toDocumentKey())
        }
    val documentId = (documentState as? DocumentUiState.Opened)?.documentId ?: 0L
    val failureMessage = openError?.let { openFailureText(it, context) }

    // Open failures are one-shot: show them, then let the view model drop them so a
    // rotation does not re-show a stale message.
    LaunchedEffect(openError) {
        val message = failureMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissOpenError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = documentTitle(documentState, dirty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        documentSubtitle(openRequest = openRequest, documentState = documentState)
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
                    MoreMenuButton(
                        openEnabled = openRequest == null,
                        onOpenFile = { openDocumentLauncher.launch(ANY_MIME_TYPE) },
                    )
                    /*Text(
                        text = coreStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,  // rgb(73, 69, 79)
                        modifier = Modifier.padding(end = 8.dp),
                    )*/
                },
            )
        },
        bottomBar = {
            BottomActionBar(
                row1Items = remember { provideRow1Items() },
                row2Items = remember { provideRow2Items() },
                modifier =
                    Modifier
                        .navigationBarsPadding() // 避让导航栏
                        .imePadding(),
                // 输入法避让
            )
        },
    ) { innerPadding ->
        AndroidView(
            factory = { viewContext ->
                EditorView(
                    viewContext,
                    viewModel.editorSession,
                    editorColors,
                    onVersionChanged = viewModel::onDocumentVersionChanged,
                )
            },
            update = { view -> view.showDocument(documentId) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    OpenRequestDialogs(openRequest = openRequest, viewModel = viewModel, context = context)
}

/** `OpenDocument` that also asks for a write grant so the picked file can be saved in place. */
private class OpenDocumentWithWriteAccess : ActivityResultContracts.OpenDocument() {
    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent = super.createIntent(context, input).addFlags(GRANT_FLAGS)

    private companion object {
        // Q-T12-2: without this the app can read the file but never save into it.
        val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** Q-T12-1: no MIME filter; the Rust UTF-8 check rejects non-text documents with a message. */
private val ANY_MIME_TYPE = arrayOf("*/*")

@Composable
private fun OpenRequestDialogs(
    openRequest: OpenRequest?,
    viewModel: MainViewModel,
    context: Context,
) {
    when (openRequest) {
        OpenRequest.ConfirmDiscard ->
            AlertDialog(
                onDismissRequest = viewModel::cancelOpen,
                title = { Text(stringResource(R.string.editor_discard_title)) },
                text = { Text(stringResource(R.string.editor_discard_message)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmOpen) {
                        Text(stringResource(R.string.editor_discard_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelOpen) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )

        is OpenRequest.ConfirmLargeFile ->
            AlertDialog(
                onDismissRequest = viewModel::cancelOpen,
                title = { Text(stringResource(R.string.editor_large_file_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.editor_large_file_message,
                            openRequest.fileName,
                            Formatter.formatShortFileSize(context, openRequest.sizeBytes),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmOpen) {
                        Text(stringResource(R.string.editor_large_file_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelOpen) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )

        OpenRequest.Reading, null -> Unit
    }
}

@Composable
private fun documentTitle(
    documentState: DocumentUiState,
    dirty: Boolean,
): String {
    val name =
        when (documentState) {
            DocumentUiState.Untitled -> stringResource(R.string.editor_title_untitled)
            is DocumentUiState.Opened -> documentState.fileName
        }
    return if (dirty) stringResource(R.string.editor_title_modified, name) else name
}

@Composable
private fun documentSubtitle(
    openRequest: OpenRequest?,
    documentState: DocumentUiState,
) {
    val style = MaterialTheme.typography.bodySmall
    when {
        openRequest == OpenRequest.Reading ->
            Text(text = stringResource(R.string.editor_reading), style = style, maxLines = 1)

        documentState is DocumentUiState.Opened && !documentState.canWrite ->
            Row {
                // The marker keeps its own Text so the ellipsis can never eat it.
                Text(
                    text = stringResource(R.string.editor_read_only_prefix),
                    style = style,
                    maxLines = 1,
                )
                PathText(
                    path = documentState.path,
                    style = style,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

        documentState is DocumentUiState.Opened ->
            PathText(path = documentState.path, style = style)
    }
}

/** Front-ellipsized document location: long paths keep their end (the file name). */
@Composable
private fun PathText(
    path: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = path,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
        modifier = modifier,
    )
}

@Composable
private fun openFailureText(
    failure: OpenFailure,
    context: Context,
): String =
    when (failure) {
        OpenFailure.FileNotFound -> stringResource(R.string.editor_open_failed_not_found)
        OpenFailure.PermissionDenied -> stringResource(R.string.editor_open_failed_permission)
        is OpenFailure.TooLarge ->
            stringResource(
                R.string.editor_open_failed_too_large,
                Formatter.formatShortFileSize(context, failure.limitBytes),
            )
        OpenFailure.ReadFailed -> stringResource(R.string.editor_open_failed_read)
        is OpenFailure.NotUtf8 -> stringResource(R.string.editor_open_failed_not_utf8)
        is OpenFailure.UnsupportedEncoding ->
            stringResource(R.string.editor_open_failed_unsupported, encodingName(failure.kind))
        OpenFailure.Internal -> stringResource(R.string.editor_open_failed_internal)
    }

/** Encoding names are technical identifiers, not translated prose. */
private fun encodingName(kind: UnsupportedEncodingKind): String =
    when (kind) {
        UnsupportedEncodingKind.Utf16Le -> "UTF-16 LE"
        UnsupportedEncodingKind.Utf16Be -> "UTF-16 BE"
        UnsupportedEncodingKind.Utf32Le -> "UTF-32 LE"
        UnsupportedEncodingKind.Utf32Be -> "UTF-32 BE"
        is UnsupportedEncodingKind.Binary -> "binary"
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
        background = scheme.surfaceContainer.toArgb(), // rgb(243, 237, 247)
        text = scheme.onBackground.toArgb(), // rgb(29, 27, 32)
        caret = scheme.primary.toArgb(), // rgb(103, 80, 164)
        composingUnderline = scheme.primary.toArgb(), // rgb(103, 80, 164)
    )
}

@Composable
private fun MoreMenuButton(
    openEnabled: Boolean,
    onOpenFile: () -> Unit,
) {
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
            enabled = openEnabled,
            onClick = {
                showMenu = false
                onOpenFile()
            },
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

// 数据模型，后续可从设置/ViewModel 动态提供
data class ActionItem(
    val id: String,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
)

private fun provideRow1Items(): List<ActionItem> =
    listOf(
        ActionItem(
            id = "row1_0",
            icon = {
                Text(
                    text = "CTRL",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_1",
            icon = {
                Icon(
                    KeyboardTab,
                    contentDescription = "Tab",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_2",
            icon = {
                Text(
                    text = "SHFT",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_3",
            icon = {
                Icon(
                    Undo,
                    contentDescription = "Undo",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_4",
            icon = {
                Icon(
                    Redo,
                    contentDescription = "Redo",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_5",
            icon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_6",
            icon = {
                Icon(
                    Save,
                    contentDescription = "Save",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row1_7",
            icon = {
                Icon(
                    MoreHoriz,
                    contentDescription = "More",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
    )

private fun provideRow2Items(): List<ActionItem> =
    listOf(
        ActionItem(
            id = "row2_0",
            icon = {
                Icon(
                    KeyboardArrowLeft,
                    contentDescription = "Left",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_1",
            icon = {
                Icon(
                    KeyboardArrowRight,
                    contentDescription = "Right",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_2",
            icon = {
                Icon(
                    KeyboardArrowUp,
                    contentDescription = "Up",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_3",
            icon = {
                Icon(
                    KeyboardArrowDown,
                    contentDescription = "Down",
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_4",
            icon = {
                Text(
                    text = "{",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_5",
            icon = {
                Text(
                    text = "}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_6",
            icon = {
                Text(
                    text = "[",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
            onClick = {},
        ),
        ActionItem(
            id = "row2_7",
            icon = {
                Text(
                    text = "]",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
            onClick = {},
        ),
    )

@Composable
private fun BottomActionBar(
    row1Items: List<ActionItem>,
    row2Items: List<ActionItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(row1Items) { item ->
                IconButton(
                    onClick = item.onClick,
                    modifier = Modifier.fillParentMaxWidth(1f / 8f).height(36.dp),
                ) {
                    item.icon()
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(row2Items) { item ->
                IconButton(
                    onClick = item.onClick,
                    modifier = Modifier.fillParentMaxWidth(1f / 8f).height(36.dp),
                ) {
                    item.icon()
                }
            }
        }
    }
}
