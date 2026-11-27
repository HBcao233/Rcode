package com.hbcao.rcode.editor

/**
 * Caret position in document coordinates. All offsets are UTF-8 bytes unless a name
 * says otherwise; [line] is zero-based.
 */
data class EditorCursorState(
    val byteOffset: Int,
    val line: Int,
    val columnBytes: Int,
)

/** One logical line without its trailing newline, plus its start offset in bytes. */
data class EditorLineSlice(
    val index: Int,
    val startByte: Int,
    val text: String,
)

/** Visible lines and caret for one document version, returned by a single FFI call. */
data class EditorViewportSnapshot(
    val version: Long,
    val lineCount: Int,
    val firstLine: Int,
    val lines: List<EditorLineSlice>,
    val cursor: EditorCursorState,
)

/**
 * Thin, testable facade over the Rust editing session (`EditorSession` via UniFFI).
 *
 * Kotlin must never keep a full text copy as authoritative state: the UI only stores
 * the viewport snapshot for the current version. All offsets are UTF-8 bytes. The
 * Rust side stores the text in a rope (`rcode-buffer`, T-0007).
 */
interface EditorSessionApi : AutoCloseable {
    /**
     * Replaces the current document with [bytes] decoded as UTF-8 text.
     *
     * Decoding happens in Rust (`rcode-fs::codec`): the BOM is stripped, `\r\n` is
     * normalized to `\n`, and the detected format is stored for [format]. This is
     * the only way to load a file; do not insert the text as an edit.
     *
     * Opening is not an undoable step: the undo/redo history is cleared, the caret
     * moves to offset 0, and the version increments exactly once (even when the
     * new text equals the old one). Callers must finish or cancel any IME
     * composition first (`EditorInputHandler`); the composition range becomes
     * invalid. On failure the document, format, caret, version and history are
     * unchanged.
     *
     * **Never call this on the main thread**: decoding 10 MB takes tens of
     * milliseconds (`architecture.md` §3.4). Use `Dispatchers.IO`/`Default`.
     *
     * @throws DocumentOpenException when the bytes are not UTF-8 text.
     */
    fun open(bytes: ByteArray): OpenedDocumentState

    /**
     * Format detected when the current document was opened (BOM + line ending).
     *
     * Pass this to [encode] when saving. A document that was never opened reports
     * [DocumentFormat.NEW_FILE].
     */
    fun format(): DocumentFormat

    /** Full document text. For tests and diagnostics only. */
    fun text(): String

    /**
     * Encodes the current text for saving with [format].
     *
     * The encoding happens in Rust (`rcode-fs::codec`), which owns BOM and newline
     * restoration (`architecture.md` §3.2). Kotlin must not call [text] and encode the
     * bytes itself: that would cache the full text as authoritative state (§3.1) and
     * duplicate the codec. Run this off the UI thread (§3.4); the returned
     * [EncodedDocument.version] identifies the text that was encoded.
     */
    fun encode(format: DocumentFormat): EncodedDocument

    /** Monotonic document version; changes on every text edit and on every [open]. */
    fun version(): Long

    /** Number of logical lines (always at least 1). */
    fun lineCount(): Int

    /** Current caret position. */
    fun cursor(): EditorCursorState

    /** Caret position in UTF-16 code units, the unit Android IME APIs use. */
    fun cursorUtf16Offset(): Int

    /**
     * Inserts [text] at the caret. Returns the new version.
     *
     * An empty [text] is a no-op and returns the current version unchanged.
     */
    fun insertText(text: String): Long

    /**
     * Replaces the byte range `startByte until endByte` with [text]. Returns the new version.
     *
     * Replacing an empty range with an empty string is a no-op and returns the current
     * version unchanged.
     */
    fun replaceRange(
        startByte: Int,
        endByte: Int,
        text: String,
    ): Long

    /** Deletes the byte range `startByte until endByte`. Returns the new version. */
    fun deleteRange(
        startByte: Int,
        endByte: Int,
    ): Long

    /** Deletes the character before the caret. Returns the new version. */
    fun deleteBackward(): Long

    /** Deletes the character after the caret. Returns the new version. */
    fun deleteForward(): Long

    /**
     * Undoes one undo group. Returns the new version, or `null` when there is
     * nothing to undo (the version and caret do not change).
     *
     * After a successful undo the caret is at the position recorded before the
     * group's first edit.
     */
    fun undo(): Long?

    /**
     * Redoes one undo group. Returns the new version, or `null` when there is
     * nothing to redo. The caret ends up just past the redone text.
     */
    fun redo(): Long?

    /** `true` when [undo] would change the document. */
    fun canUndo(): Boolean

    /** `true` when [redo] would change the document. */
    fun canRedo(): Boolean

    /**
     * Clears the undo/redo history without changing the text.
     *
     * [open] already does this, so loading a file cannot become an undoable step.
     * Use this only when text is replaced by some other means.
     */
    fun clearHistory()

    /**
     * Starts an IME composition group. Edits until [endComposition] merge into one
     * undo step regardless of how slowly the user types.
     */
    fun beginComposition()

    /**
     * Ends the IME composition group. A composition that left the text unchanged
     * (cancelled input, or a commit that rewrote identical text) leaves no step.
     */
    fun endComposition()

    /** Moves the caret to [byteOffset] (clamped) and returns the resulting offset. */
    fun moveCursor(byteOffset: Int): Int

    /** Moves the caret by [deltaChars] Unicode scalar values and returns the offset. */
    fun moveCursorHorizontal(deltaChars: Int): Int

    /** Moves the caret by [deltaLines] and returns the offset. */
    fun moveCursorVertical(deltaLines: Int): Int

    /**
     * Returns up to [maxLines] lines starting at [firstLine], plus the caret, in one
     * call.
     *
     * This is the only bulk read API; do not call it once per line or per character
     * (`uniffi-bridge` skill). [firstLine] is clamped to the document, [maxLines] is
     * clamped to `1..=10000`, and each line's [EditorLineSlice.startByte] is a UTF-8
     * byte offset into the buffer. Line text excludes the terminator. [version] and
     * [EditorViewportSnapshot.lines] come from the same snapshot, so a renderer can
     * reuse its layout cache while the version is unchanged.
     */
    fun viewport(
        firstLine: Int,
        maxLines: Int,
    ): EditorViewportSnapshot

    /** Up to [maxUtf16Units] UTF-16 code units of text before the caret. */
    fun contextBeforeCursor(maxUtf16Units: Int): String

    /** Up to [maxUtf16Units] UTF-16 code units of text after the caret. */
    fun contextAfterCursor(maxUtf16Units: Int): String

    override fun close() = Unit
}
