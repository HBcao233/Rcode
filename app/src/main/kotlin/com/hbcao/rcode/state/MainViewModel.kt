package com.hbcao.rcode.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hbcao.rcode.bridge.CoreBridge
import com.hbcao.rcode.editor.DocumentOpenException
import com.hbcao.rcode.editor.EditorSessionApi
import com.hbcao.rcode.editor.EncodedDocument
import com.hbcao.rcode.platform.document.DocumentIo
import com.hbcao.rcode.platform.document.DocumentKey
import com.hbcao.rcode.platform.document.DocumentReadException
import com.hbcao.rcode.platform.document.DocumentSaver
import com.hbcao.rcode.platform.document.SavableDocument
import com.hbcao.rcode.platform.document.SaveResult
import com.hbcao.rcode.platform.document.WritableDocument
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
 * Owns the editing session and the "open file" / "save" flows (T-0012, T-0013).
 *
 * Text itself stays in Rust; this view model keeps only the document handle
 * (`uri` + display name + [SavableDocument]) and the version the text had when it was
 * loaded or last saved, which is how `dirty` is derived (`architecture.md` §3.1,
 * Q-T12-4).
 */
class MainViewModel(
    private val bridge: CoreBridge,
    /** Authoritative editing session; survives configuration changes with this ViewModel. */
    val editorSession: EditorSessionApi,
    private val documentIo: DocumentIo,
    /** Save transaction (T-0013); [com.hbcao.rcode.platform.document.AtomicDocumentSaver] in production. */
    private val documentSaver: DocumentSaver,
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

    /** Transient save state: a running transaction or a pending "save as" picker. */
    private val _saveRequest = MutableStateFlow<SaveRequest?>(null)
    val saveRequest: StateFlow<SaveRequest?> = _saveRequest.asStateFlow()

    /** One-shot result of the last save; dismissed by the UI after it is shown. */
    private val _saveOutcome = MutableStateFlow<SaveOutcome?>(null)
    val saveOutcome: StateFlow<SaveOutcome?> = _saveOutcome.asStateFlow()

    /** `true` while a save transaction runs; open/save-as entries are ignored meanwhile (Q-T13-7). */
    val isSaving: Boolean get() = _saveRequest.value is SaveRequest.Saving

    /** `session.version() != lastSavedVersion`: unsaved edits (Q-T12-4, Q-T13-5). */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** Document the next in-place save writes to; `null` until a file is opened (T-0013). */
    var currentDocument: SavableDocument? = null
        private set

    private var currentKey: DocumentKey? = null

    /** Version of the text that is on disk: the open result or the last successful save. */
    private var lastSavedVersion: Long = editorSession.version()
    private var documentId: Long = 0
    private var openJob: Job? = null

    /** Identifies the active save, so a cancelled job cannot clear a newer request. */
    private var saveJob: Job? = null
    private var saveToken: Long = 0

    /** Distinguishes repeated "save as" requests for the UI's one-shot picker launch. */
    private var saveAsToken: Long = 0

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
     *
     * Opening is refused while a save is pending or running: releasing the target
     * URI's grant in the middle of a save transaction would break it (Q-T13-7, V9).
     */
    fun openDocument(key: DocumentKey) {
        if (openJob?.isActive == true) return
        if (_saveRequest.value != null || saveJob?.isActive == true) {
            logger("open: ignored while a save is pending (${key.uri})", null)
            return
        }
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
     * main source of `dirty` (Q-T12-4). A successful save does not change the version,
     * so [runSave] recomputes it as well.
     */
    fun onDocumentVersionChanged(version: Long) {
        val dirty = version != lastSavedVersion
        if (_dirty.value != dirty) _dirty.value = dirty
    }

    private suspend fun runOpen(
        key: DocumentKey,
        token: Long,
    ) {
        try {
            if (editorSession.version() != lastSavedVersion) {
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
            lastSavedVersion = opened.version
            _dirty.value = false
            documentId += 1
            _documentState.value =
                DocumentUiState.Opened(document.displayName, document.path, document.canWrite, documentId)
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

    // region save flow (T-0013)

    /**
     * Saves the current document in place.
     *
     * Without a writable document (the in-memory `untitled.txt`, or a file opened
     * read-only) this only asks for a location: the request becomes
     * [SaveRequest.SaveAs] and [saveAs] performs the write once the picker returns a
     * URI (Q-T13-2/3/4). Repeated triggers during a save are ignored (V9).
     */
    fun saveDocument() {
        if (!beginSave()) return
        val reason = saveAsReason()
        if (reason != null) {
            requestSaveAs(reason)
            return
        }
        startSave(key = null)
    }

    /** Menu "Save as": always asks for a new location, even for a writable document. */
    fun saveAsNewDocument() {
        if (!beginSave()) return
        requestSaveAs(saveAsReason() ?: SaveAsReason.Manual)
    }

    /** Writes the buffer into [key], the URI returned by `ACTION_CREATE_DOCUMENT` (Q-T13-4). */
    fun saveAs(key: DocumentKey) {
        // A pending SaveAs request is exactly what this call answers, so it does not
        // block: only an open or a running transaction does.
        if (openJob?.isActive == true || saveJob?.isActive == true || isSaving) {
            logger("save as: ignored, another document operation is running", null)
            return
        }
        startSave(key)
    }

    /** Drops a pending [SaveRequest.SaveAs] (picker cancelled); a running save is never cancelled. */
    fun cancelSave() {
        if (isSaving) return
        _saveRequest.value = null
    }

    /** Hides the one-shot [saveOutcome]. */
    fun dismissSaveOutcome() {
        _saveOutcome.value = null
    }

    /** `true` when this save trigger may start: nothing else is opening, saving or picking. */
    private fun beginSave(): Boolean {
        if (openJob?.isActive == true || saveJob?.isActive == true || _saveRequest.value != null) {
            logger("save: ignored, another document operation is running", null)
            return false
        }
        return true
    }

    /** Save target when no new location is needed; `null` means the save must ask for one. */
    private fun saveAsReason(): SaveAsReason? =
        when (val documentState = _documentState.value) {
            DocumentUiState.Untitled -> SaveAsReason.Untitled
            is DocumentUiState.Opened ->
                if (documentState.canWrite && currentDocument != null) {
                    null
                } else {
                    SaveAsReason.ReadOnly
                }
        }

    private fun requestSaveAs(reason: SaveAsReason) {
        _saveRequest.value = SaveRequest.SaveAs(reason, ++saveAsToken)
    }

    private fun startSave(key: DocumentKey?) {
        val token = ++saveToken
        _saveRequest.value = SaveRequest.Saving
        saveJob = viewModelScope.launch { runSave(key, token) }
    }

    /**
     * Encodes the text in Rust, writes it through [documentSaver] and publishes the
     * result. Failures leave the document, the saved version and `dirty` untouched
     * (V4, V6).
     */
    private suspend fun runSave(
        key: DocumentKey?,
        token: Long,
    ) {
        try {
            val target =
                if (key != null) {
                    adoptTarget(key) ?: return
                } else {
                    inPlaceTarget() ?: return publishInternal("save: no document to write")
                }

            // Encoding a 10 MB document allocates and runs the codec in Rust: never on
            // the UI thread (architecture.md §3.4, V10).
            val encodeStarted = System.nanoTime()
            val encoded =
                try {
                    withContext(workDispatcher) { editorSession.encode(editorSession.format()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger("save: encoding failed", e)
                    _saveOutcome.value = SaveOutcome.Failed(SaveFailure.Internal)
                    return
                }
            val encodeNanos = System.nanoTime() - encodeStarted

            val saveStarted = System.nanoTime()
            val result = documentSaver.save(target.savable, encoded.bytes)
            val saveNanos = System.nanoTime() - saveStarted

            when (result) {
                SaveResult.Success ->
                    onSaved(target, encoded, encodeNanos, saveNanos)
                is SaveResult.Failure ->
                    publishSaveFailure(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never swallow: the stack trace goes to logcat and the UI gets a generic
            // message; the current document stays untouched (architecture.md §3.5).
            logger("save: unexpected failure for ${key?.uri}", e)
            _saveOutcome.value = SaveOutcome.Failed(SaveFailure.Internal)
        } finally {
            if (token == saveToken) {
                _saveRequest.value = null
                saveJob = null
            }
        }
    }

    private suspend fun adoptTarget(key: DocumentKey): SaveTarget? =
        try {
            SaveTarget.of(withContext(ioDispatcher) { documentIo.adopt(key) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("save: adopting ${key.uri} failed", e)
            _saveOutcome.value = SaveOutcome.Failed(SaveFailure.Internal)
            null
        }

    private fun inPlaceTarget(): SaveTarget? {
        val savable = currentDocument ?: return null
        val documentState = _documentState.value as? DocumentUiState.Opened ?: return null
        return SaveTarget(
            displayName = documentState.fileName,
            path = documentState.path,
            canWrite = documentState.canWrite,
            savable = savable,
            adopted = null,
        )
    }

    private suspend fun onSaved(
        target: SaveTarget,
        encoded: EncodedDocument,
        encodeNanos: Long,
        saveNanos: Long,
    ) {
        val adopted = target.adopted
        if (adopted != null) {
            // Q-T13-3/7: the text did not change, so the editor keeps its viewport and
            // the old grant is dropped only after the new document is the current one.
            switchTo(adopted)
        }
        lastSavedVersion = encoded.version
        refreshDirty()
        val stillDirty = _dirty.value
        logger(
            "save: encode=${encodeNanos / NANOS_PER_MILLI}ms" +
                " save=${saveNanos / NANOS_PER_MILLI}ms" +
                " bytes=${encoded.bytes.size} version=${encoded.version} stillDirty=$stillDirty",
            null,
        )
        _saveOutcome.value = SaveOutcome.Saved(target.displayName, stillDirty)
    }

    private suspend fun switchTo(adopted: WritableDocument) {
        val previousKey = currentKey
        currentKey = adopted.key
        currentDocument = adopted.savable
        // Same `documentId`: the text is unchanged, so the editor must not reset its
        // viewport, caret or scroll position on a save-as.
        _documentState.value =
            DocumentUiState.Opened(adopted.displayName, adopted.path, adopted.canWrite, documentId)
        if (previousKey != null && previousKey != adopted.key) {
            releaseQuietly(previousKey)
        }
    }

    /**
     * `dirty` after a save: the version on disk versus the version in the buffer. They
     * differ when the user edited during the save (version drift, Q-T13-5, V8).
     */
    private fun refreshDirty() {
        val dirty = editorSession.version() != lastSavedVersion
        if (_dirty.value != dirty) _dirty.value = dirty
    }

    private fun publishSaveFailure(failure: SaveResult.Failure) {
        val backupPath = failure.backupPath
        val mapped =
            if (!failure.originalPreserved && backupPath != null) {
                SaveFailure.BackupLost(failure.stage, backupPath)
            } else {
                // `originalPreserved = false` without a path cannot happen for
                // AtomicDocumentSaver; treat it as a normal write failure.
                SaveFailure.WriteFailed(failure.stage)
            }
        _saveOutcome.value = SaveOutcome.Failed(mapped)
        logger("save failed: stage=${failure.stage} originalPreserved=${failure.originalPreserved}", failure.cause)
        failure.restoreCause?.let { logger("save: restoring the original failed", it) }
    }

    private fun publishInternal(message: String) {
        logger(message, null)
        _saveOutcome.value = SaveOutcome.Failed(SaveFailure.Internal)
    }

    /** Resolved save target: the current document or a freshly created one. */
    private class SaveTarget(
        val displayName: String,
        val path: String,
        val canWrite: Boolean,
        val savable: SavableDocument,
        /** Non-null when the target came from `ACTION_CREATE_DOCUMENT`. */
        val adopted: WritableDocument?,
    ) {
        companion object {
            fun of(document: WritableDocument): SaveTarget =
                SaveTarget(
                    displayName = document.displayName,
                    path = document.path,
                    canWrite = document.canWrite,
                    savable = document.savable,
                    adopted = document,
                )
        }
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
