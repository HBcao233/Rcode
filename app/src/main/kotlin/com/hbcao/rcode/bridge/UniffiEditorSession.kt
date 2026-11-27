package com.hbcao.rcode.bridge

import com.hbcao.rcode.editor.DocumentFormat
import com.hbcao.rcode.editor.DocumentOpenException
import com.hbcao.rcode.editor.EditorCursorState
import com.hbcao.rcode.editor.EditorLineSlice
import com.hbcao.rcode.editor.EditorSessionApi
import com.hbcao.rcode.editor.EditorViewportSnapshot
import com.hbcao.rcode.editor.EncodedDocument
import com.hbcao.rcode.editor.LineEnding
import com.hbcao.rcode.editor.OpenedDocumentState
import com.hbcao.rcode.editor.UnsupportedEncodingKind
import uniffi.rcode_ffi.EditorCursor
import uniffi.rcode_ffi.EditorException
import uniffi.rcode_ffi.EditorLine
import uniffi.rcode_ffi.EditorSession
import uniffi.rcode_ffi.EditorViewport
import uniffi.rcode_ffi.OpenedDocument
import uniffi.rcode_ffi.DocumentFormat as NativeDocumentFormat
import uniffi.rcode_ffi.LineEnding as NativeLineEnding
import uniffi.rcode_ffi.UnsupportedEncodingKind as NativeUnsupportedEncodingKind

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
    override fun open(bytes: ByteArray): OpenedDocumentState =
        try {
            native.open(bytes).toState()
        } catch (error: EditorException.NotUtf8) {
            throw DocumentOpenException.NotUtf8(offset = error.offset.toInt())
        } catch (error: EditorException.UnsupportedEncoding) {
            throw DocumentOpenException.UnsupportedEncoding(kind = error.kind.toState())
        }

    override fun format(): DocumentFormat = native.format().toState()

    override fun text(): String = native.text()

    override fun encode(format: DocumentFormat): EncodedDocument {
        val encoded = native.encode(format.toNative())
        return EncodedDocument(version = encoded.version.toLong(), bytes = encoded.bytes)
    }

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

    override fun undo(): Long? = native.undo()?.toLong()

    override fun redo(): Long? = native.redo()?.toLong()

    override fun canUndo(): Boolean = native.canUndo()

    override fun canRedo(): Boolean = native.canRedo()

    override fun clearHistory() {
        native.clearHistory()
    }

    override fun beginComposition() {
        native.beginComposition()
    }

    override fun endComposition() {
        native.endComposition()
    }

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

    private fun DocumentFormat.toNative() =
        NativeDocumentFormat(
            bom = bom,
            lineEnding =
                when (lineEnding) {
                    LineEnding.LF -> NativeLineEnding.LF
                    LineEnding.CRLF -> NativeLineEnding.CR_LF
                },
        )

    private fun NativeDocumentFormat.toState() =
        DocumentFormat(
            bom = bom,
            lineEnding =
                when (lineEnding) {
                    NativeLineEnding.LF -> LineEnding.LF
                    NativeLineEnding.CR_LF -> LineEnding.CRLF
                },
        )

    private fun NativeUnsupportedEncodingKind.toState() =
        when (this) {
            NativeUnsupportedEncodingKind.Utf16Le -> UnsupportedEncodingKind.Utf16Le
            NativeUnsupportedEncodingKind.Utf16Be -> UnsupportedEncodingKind.Utf16Be
            NativeUnsupportedEncodingKind.Utf32Le -> UnsupportedEncodingKind.Utf32Le
            NativeUnsupportedEncodingKind.Utf32Be -> UnsupportedEncodingKind.Utf32Be
            is NativeUnsupportedEncodingKind.Binary -> UnsupportedEncodingKind.Binary(nulOffset = nulOffset.toInt())
        }

    private fun OpenedDocument.toState() =
        OpenedDocumentState(
            version = version.toLong(),
            lineCount = lineCount.toInt(),
            format = format.toState(),
            cursor = cursor.toState(),
        )

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
