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
 * the viewport snapshot for the current version. All offsets are UTF-8 bytes. This is
 * the temporary M1.0 String-backed session; T-0007 replaces it with a rope.
 */
interface EditorSessionApi : AutoCloseable {
    /** Full document text. For tests and diagnostics only. */
    fun text(): String

    /** Monotonic document version; changes on every text edit. */
    fun version(): Long

    /** Number of logical lines (always at least 1). */
    fun lineCount(): Int

    /** Current caret position. */
    fun cursor(): EditorCursorState

    /** Caret position in UTF-16 code units, the unit Android IME APIs use. */
    fun cursorUtf16Offset(): Int

    /** Inserts [text] at the caret. Returns the new version. */
    fun insertText(text: String): Long

    /** Replaces the byte range `startByte until endByte` with [text]. Returns the new version. */
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
