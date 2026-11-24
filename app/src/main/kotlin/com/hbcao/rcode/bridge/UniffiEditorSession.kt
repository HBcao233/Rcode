package com.hbcao.rcode.bridge

import com.hbcao.rcode.editor.EditorCursorState
import com.hbcao.rcode.editor.EditorLineSlice
import com.hbcao.rcode.editor.EditorSessionApi
import com.hbcao.rcode.editor.EditorViewportSnapshot
import uniffi.rcode_ffi.EditorCursor
import uniffi.rcode_ffi.EditorLine
import uniffi.rcode_ffi.EditorSession
import uniffi.rcode_ffi.EditorViewport

/**
 * Production [EditorSessionApi] backed by the UniFFI-generated Rust session.
 *
 * The mapping layer only converts unsigned FFI values to Kotlin `Int`/`Long` and
 * flattens generated records, so the rest of the app never depends on UniFFI types.
 * Offsets are documented as non-negative; callers always obtain them from the session.
 */
class UniffiEditorSession(
    private val native: EditorSession = EditorSession(),
) : EditorSessionApi {
    override fun text(): String = native.text()

    override fun version(): Long = native.version().toLong()

    override fun lineCount(): Int = native.lineCount().toInt()

    override fun cursor(): EditorCursorState = native.cursor().toState()

    override fun cursorUtf16Offset(): Int = native.cursorUtf16Offset().toInt()

    override fun insertText(text: String): Long = native.insertText(text).toLong()

    override fun replaceRange(
        startByte: Int,
        endByte: Int,
        text: String,
    ): Long = native.replaceRange(startByte.offset(), endByte.offset(), text).toLong()

    override fun deleteRange(
        startByte: Int,
        endByte: Int,
    ): Long = native.deleteRange(startByte.offset(), endByte.offset()).toLong()

    override fun deleteBackward(): Long = native.deleteBackward().toLong()

    override fun deleteForward(): Long = native.deleteForward().toLong()

    override fun moveCursor(byteOffset: Int): Int = native.moveCursor(byteOffset.offset()).toInt()

    override fun moveCursorHorizontal(deltaChars: Int): Int = native.moveCursorHorizontal(deltaChars).toInt()

    override fun moveCursorVertical(deltaLines: Int): Int = native.moveCursorVertical(deltaLines).toInt()

    override fun viewport(
        firstLine: Int,
        maxLines: Int,
    ): EditorViewportSnapshot {
        val viewport =
            native.viewport(
                firstLine.coerceAtLeast(0).toUInt(),
                maxLines.coerceIn(1, MAX_VIEWPORT_LINES).toUInt(),
            )
        return viewport.toSnapshot()
    }

    override fun contextBeforeCursor(maxUtf16Units: Int): String = native.contextBeforeCursor(maxUtf16Units.contextUnits())

    override fun contextAfterCursor(maxUtf16Units: Int): String = native.contextAfterCursor(maxUtf16Units.contextUnits())

    override fun close() {
        native.close()
    }

    private fun EditorCursor.toState() =
        EditorCursorState(
            byteOffset = byteOffset.toInt(),
            line = line.toInt(),
            columnBytes = columnBytes.toInt(),
        )

    private fun EditorLine.toSlice() =
        EditorLineSlice(
            index = index.toInt(),
            startByte = startByte.toInt(),
            text = text,
        )

    private fun EditorViewport.toSnapshot() =
        EditorViewportSnapshot(
            version = version.toLong(),
            lineCount = lineCount.toInt(),
            firstLine = firstLine.toInt(),
            lines = lines.map { it.toSlice() },
            cursor = cursor.toState(),
        )

    private fun Int.offset(): UInt = coerceAtLeast(0).toUInt()

    private fun Int.contextUnits(): UInt = coerceIn(0, MAX_CONTEXT_UNITS).toUInt()

    private companion object {
        // Matches the cap in the Rust session; a bad caller must not allocate without bound.
        const val MAX_VIEWPORT_LINES = 10_000

        // IMEs ask for small contexts; cap defensive requests.
        const val MAX_CONTEXT_UNITS = 65_536
    }
}
