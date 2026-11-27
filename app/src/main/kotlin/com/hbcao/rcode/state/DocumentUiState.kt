package com.hbcao.rcode.state

import com.hbcao.rcode.editor.UnsupportedEncodingKind

/**
 * Which document the editor is showing (T-0012).
 *
 * Only the stable states live here; reading, confirmations and failures are
 * transient and exposed separately so that cancelling an open leaves this state
 * untouched (V4, V5, V6).
 */
sealed interface DocumentUiState {
    /** Startup state: an in-memory `untitled.txt` that has no file yet. */
    data object Untitled : DocumentUiState

    /**
     * A file opened through SAF.
     *
     * @property fileName provider display name, or the URI's last path segment.
     * @property canWrite `false` when only a read grant was obtained (read-only file).
     * @property documentId increments on every successful open, so the editor view can
     * reset its viewport even when the same file is opened twice.
     */
    data class Opened(
        val fileName: String,
        val canWrite: Boolean,
        val documentId: Long,
    ) : DocumentUiState
}

/** Transient state of an in-flight "open file" request. */
sealed interface OpenRequest {
    /** Reading the URI and/or decoding the bytes in Rust. */
    data object Reading : OpenRequest

    /** The current document has unsaved edits; the user must confirm discarding them. */
    data object ConfirmDiscard : OpenRequest

    /** The picked file exceeds the size threshold; the user must confirm (Q6). */
    data class ConfirmLargeFile(
        val fileName: String,
        val sizeBytes: Long,
    ) : OpenRequest
}

/**
 * What went wrong while opening a document, mapped to a user-facing string by the UI.
 *
 * The original exception is logged, never shown: messages are user text
 * (`strings.xml`), and no exception detail leaks into the screen.
 */
sealed interface OpenFailure {
    /** The provider has no document for the URI. */
    data object FileNotFound : OpenFailure

    /** The app does not hold a read grant for the URI. */
    data object PermissionDenied : OpenFailure

    /** The stream is longer than [limitBytes] (`ContentResolverDocumentIo` hard limit). */
    data class TooLarge(
        val limitBytes: Long,
    ) : OpenFailure

    /** Any other provider/stream failure (cause logged). */
    data object ReadFailed : OpenFailure

    /** The bytes are not valid UTF-8; [offset] indexes the original file bytes. */
    data class NotUtf8(
        val offset: Int,
    ) : OpenFailure

    /** UTF-16/UTF-32 text or binary data; M1 only opens UTF-8 (Q1). */
    data class UnsupportedEncoding(
        val kind: UnsupportedEncodingKind,
    ) : OpenFailure

    /** Unexpected failure; logged with its stack trace and never swallowed silently. */
    data object Internal : OpenFailure
}
