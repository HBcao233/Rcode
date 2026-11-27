package com.hbcao.rcode.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hbcao.rcode.bridge.CoreBridge
import com.hbcao.rcode.editor.DocumentOpenException
import com.hbcao.rcode.editor.EditorSessionApi
import com.hbcao.rcode.platform.document.DocumentIo
import com.hbcao.rcode.platform.document.DocumentKey
import com.hbcao.rcode.platform.document.DocumentReadException
import com.hbcao.rcode.platform.document.SavableDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the editing session and the "open file" flow (T-0012).
 *
 * Text itself stays in Rust; this view model keeps only the document handle
 * (`uri` + display name + [SavableDocument]) and the version the text had when it was
 * loaded, which is how `dirty` is derived (`architecture.md` §3.1, Q-T12-4).
 */
class MainViewModel(
    private val bridge: CoreBridge,
    /** Authoritative editing session; survives configuration changes with this ViewModel. */
    val editorSession: EditorSessionApi,
    private val documentIo: DocumentIo,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Files larger than this ask for confirmation before opening (Q6). */
    private val largeFileThresholdBytes: Long = LARGE_FILE_THRESHOLD_BYTES,
    /** Receives open timings and failures for logcat; never shown to the user. */
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) : ViewModel() {
    private val _state = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _documentState = MutableStateFlow<DocumentUiState>(DocumentUiState.Untitled)
    val documentState: StateFlow<DocumentUiState> = _documentState.asStateFlow()

    /** Non-null while a confirmation dialog (or a read) is in flight. */
    private val _openRequest = MutableStateFlow<OpenRequest?>(null)
    val openRequest: StateFlow<OpenRequest?> = _openRequest.asStateFlow()

    /** One-shot failure of the last open attempt; dismissed by the UI. */
    private val _openError = MutableStateFlow<OpenFailure?>(null)
    val openError: StateFlow<OpenFailure?> = _openError.asStateFlow()

    /** `session.version() != lastLoadedVersion`: unsaved edits (Q-T12-4). */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** Document the next save writes to; `null` until a file is opened (T-0013). */
    var currentDocument: SavableDocument? = null
        private set

    private var currentKey: DocumentKey? = null
    private var lastLoadedVersion: Long = editorSession.version()
    private var documentId: Long = 0
    private var openJob: Job? = null

    /** Identifies the active open, so a cancelled job cannot clear a newer request. */
    private var openToken: Long = 0
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

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

    // region open flow

    /**
     * Starts opening [key]. Requests during an in-flight open are ignored, so the
     * provider is read and Rust loads at most one document at a time (V8).
     */
    fun openDocument(key: DocumentKey) {
        if (openJob?.isActive == true) return
        val token = ++openToken
        _openError.value = null
        openJob = viewModelScope.launch { runOpen(key, token) }
    }

    /** Accepts the dialog described by [openRequest]: discard edits or open the large file. */
    fun confirmOpen() {
        pendingConfirmation?.complete(true)
    }

    /** Dismisses [openRequest] or cancels the in-flight read, keeping the current document. */
    fun cancelOpen() {
        // A pending prompt resumes with `false` so the coroutine can release the grant
        // it already took; only a read in progress is cancelled outright.
        val confirmation = pendingConfirmation
        if (confirmation != null) {
            confirmation.complete(false)
        } else {
            openJob?.cancel()
        }
    }

    /** Hides the one-shot [openError]. */
    fun dismissOpenError() {
        _openError.value = null
    }

    /**
     * Called by the editor view whenever the document version changes, which is the
     * only source of `dirty` while a document is open (Q-T12-4).
     */
    fun onDocumentVersionChanged(version: Long) {
        val dirty = version != lastLoadedVersion
        if (_dirty.value != dirty) _dirty.value = dirty
    }

    private suspend fun runOpen(
        key: DocumentKey,
        token: Long,
    ) {
        try {
            if (editorSession.version() != lastLoadedVersion) {
                _openRequest.value = OpenRequest.ConfirmDiscard
                if (!awaitConfirmation()) return
            }
            _openRequest.value = OpenRequest.Reading

            val readStarted = System.nanoTime()
            val document =
                try {
                    withContext(ioDispatcher) { documentIo.open(key) }
                } catch (e: DocumentReadException) {
                    publishFailure(readFailure(e), e)
                    return
                }
            val readNanos = System.nanoTime() - readStarted

            if (document.bytes.size > largeFileThresholdBytes) {
                _openRequest.value =
                    OpenRequest.ConfirmLargeFile(document.displayName, document.bytes.size.toLong())
                if (!awaitConfirmation()) {
                    releaseQuietly(key)
                    return
                }
            }

            val openStarted = System.nanoTime()
            val opened =
                try {
                    withContext(workDispatcher) { editorSession.open(document.bytes) }
                } catch (e: DocumentOpenException) {
                    publishFailure(openFailure(e), e)
                    releaseQuietly(key)
                    return
                }
            val openNanos = System.nanoTime() - openStarted

            val previousKey = currentKey
            currentKey = key
            currentDocument = document.savable
            lastLoadedVersion = opened.version
            _dirty.value = false
            documentId += 1
            _documentState.value = DocumentUiState.Opened(document.displayName, document.canWrite, documentId)
            logger(
                "open: read=${readNanos / NANOS_PER_MILLI}ms" +
                    " decode=${openNanos / NANOS_PER_MILLI}ms" +
                    " bytes=${document.bytes.size} format=${opened.format}",
                null,
            )
            if (previousKey != null && previousKey != key) {
                // Q-T12-5: drop the grant of the replaced document.
                releaseQuietly(previousKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never swallow: the stack trace goes to logcat and the UI gets a generic
            // message; the current document stays untouched (architecture.md §3.5).
            logger("open: unexpected failure for ${key.uri}", e)
            publishFailure(OpenFailure.Internal, e)
        } finally {
            if (token == openToken) {
                _openRequest.value = null
                // Accept the next request as soon as the body is done, instead of
                // waiting for the job to be collected.
                openJob = null
            }
        }
    }

    private suspend fun awaitConfirmation(): Boolean {
        val confirmation = CompletableDeferred<Boolean>()
        pendingConfirmation = confirmation
        try {
            return confirmation.await()
        } finally {
            pendingConfirmation = null
        }
    }

    private suspend fun releaseQuietly(key: DocumentKey) {
        if (key == currentKey) return
        try {
            // A cancelled open must still drop the grant it took.
            withContext(NonCancellable + ioDispatcher) { documentIo.release(key) }
        } catch (e: Exception) {
            logger("open: releasing ${key.uri} failed", e)
        }
    }

    private fun publishFailure(
        failure: OpenFailure,
        cause: Throwable,
    ) {
        _openError.value = failure
        logger("open failed: $failure", cause)
    }

    private fun readFailure(e: DocumentReadException): OpenFailure =
        when (e) {
            is DocumentReadException.NotFound -> OpenFailure.FileNotFound
            is DocumentReadException.PermissionDenied -> OpenFailure.PermissionDenied
            is DocumentReadException.TooLarge -> OpenFailure.TooLarge(e.limitBytes)
            is DocumentReadException.Io -> OpenFailure.ReadFailed
        }

    private fun openFailure(e: DocumentOpenException): OpenFailure =
        when (e) {
            is DocumentOpenException.NotUtf8 -> OpenFailure.NotUtf8(e.offset)
            is DocumentOpenException.UnsupportedEncoding -> OpenFailure.UnsupportedEncoding(e.kind)
        }

    // endregion

    override fun onCleared() {
        editorSession.close()
    }

    companion object {
        /** Q6/Q-T12-3: files above 10 MiB ask before opening. */
        const val LARGE_FILE_THRESHOLD_BYTES: Long = 10L * 1024 * 1024

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
