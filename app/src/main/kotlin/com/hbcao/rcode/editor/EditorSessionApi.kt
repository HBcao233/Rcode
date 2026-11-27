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

    /** Monotonic document version; changes on every text edit. */
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
     * Load or replace the document text first, then call this: opening a file must
     * not become an undoable step (T-0011/T-0012).
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

    /** Returns up to [maxLines] lines starting at [firstLine]. */
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
