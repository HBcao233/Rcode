package com.hbcao.rcode.editor

/**
 * In-memory [EditorSessionApi] used by JVM unit tests.
 *
 * It mirrors the Rust session's byte-offset contract closely enough to exercise the
 * IME state machine: the caret is stored as a UTF-16 index internally, while every
 * public offset is a UTF-8 byte offset. The real Rust session is covered by
 * instrumented tests.
 */
class FakeEditorSession(
    initialText: String = "",
) : EditorSessionApi {
    private var document: String = initialText
    private var caret: Int = initialText.length
    private var documentVersion: Long = 1
    private var documentFormat: DocumentFormat = DocumentFormat.NEW_FILE

    private data class Snapshot(
        val text: String,
        val caret: Int,
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private var composing: Boolean = false
    private var compositionStart: Snapshot? = null

    override fun text(): String = document

    // Test fake: the real decoding (BOM, newline detection, encoding errors) lives in
    // Rust. This mirrors the state changes of `open` only, so JVM tests can drive the
    // open -> edit -> undo flow; invalid input and format detection are covered by the
    // instrumented tests against the real FFI.
    override fun open(bytes: ByteArray): OpenedDocumentState {
        document = bytes.toString(Charsets.UTF_8)
        caret = 0
        documentVersion += 1
        clearHistory()
        return OpenedDocumentState(
            version = documentVersion,
            lineCount = lineStarts().size,
            format = documentFormat,
            cursor = cursor(),
        )
    }

    override fun format(): DocumentFormat = documentFormat

    // Test fake: mirrors the Rust codec closely enough for the IME tests. Production
    // encoding always goes through EditorSessionApi -> Rust (T-0010).
    override fun encode(format: DocumentFormat): EncodedDocument {
        val newline =
            when (format.lineEnding) {
                LineEnding.LF -> "\n"
                LineEnding.CRLF -> "\r\n"
            }
        val body = document.replace("\n", newline).toByteArray()
        val bytes = if (format.bom) UTF8_BOM + body else body
        return EncodedDocument(version = documentVersion, bytes = bytes)
    }

    override fun version(): Long = documentVersion

    override fun lineCount(): Int = lineStarts().size

    override fun cursor(): EditorCursorState {
        val lineStart = lineStartIndex(caret)
        return EditorCursorState(
            byteOffset = Utf8Offsets.utf8Length(document.substring(0, caret)),
            line = lineOf(caret),
            columnBytes = Utf8Offsets.utf8Length(document.substring(lineStart, caret)),
        )
    }

    override fun cursorUtf16Offset(): Int = caret

    override fun insertText(text: String): Long {
        if (text.isEmpty()) return documentVersion
        val before = Snapshot(document, caret)
        recordEditStart()
        document = document.substring(0, caret) + text + document.substring(caret)
        caret += text.length
        documentVersion += 1
        recordEditEnd(before)
        return documentVersion
    }

    override fun replaceRange(
        startByte: Int,
        endByte: Int,
        text: String,
    ): Long {
        val startIndex = Utf8Offsets.byteToUtf16Index(document, startByte)
        val endIndex = Utf8Offsets.byteToUtf16Index(document, endByte)
        if (startIndex == endIndex && text.isEmpty()) return documentVersion
        val before = Snapshot(document, caret)
        recordEditStart()
        val next = document.substring(0, startIndex) + text + document.substring(endIndex)
        val changed = next != document
        document = next
        caret = startIndex + text.length
        documentVersion += 1
        recordEditEnd(before, changed)
        return documentVersion
    }

    override fun deleteRange(
        startByte: Int,
        endByte: Int,
    ): Long = replaceRange(startByte, endByte, "")

    override fun deleteBackward(): Long {
        if (caret == 0) return documentVersion
        val before = Snapshot(document, caret)
        recordEditStart()
        val previous = document.offsetByCodePoints(caret, -1)
        document = document.substring(0, previous) + document.substring(caret)
        caret = previous
        documentVersion += 1
        recordEditEnd(before)
        return documentVersion
    }

    override fun deleteForward(): Long {
        if (caret >= document.length) return documentVersion
        val before = Snapshot(document, caret)
        recordEditStart()
        val next = document.offsetByCodePoints(caret, 1)
        document = document.substring(0, caret) + document.substring(next)
        documentVersion += 1
        recordEditEnd(before)
        return documentVersion
    }

    override fun undo(): Long? {
        endComposition()
        if (undoStack.isEmpty()) return null
        redoStack.addLast(Snapshot(document, caret))
        val snapshot = undoStack.removeLast()
        document = snapshot.text
        caret = snapshot.caret
        documentVersion += 1
        return documentVersion
    }

    override fun redo(): Long? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(Snapshot(document, caret))
        val snapshot = redoStack.removeLast()
        document = snapshot.text
        caret = snapshot.caret
        documentVersion += 1
        return documentVersion
    }

    override fun canUndo(): Boolean =
        undoStack.isNotEmpty() ||
            (composing && compositionStart?.let { it.text != document } == true)

    override fun canRedo(): Boolean = redoStack.isNotEmpty()

    override fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        composing = false
        compositionStart = null
    }

    override fun beginComposition() {
        if (composing) return
        composing = true
        compositionStart = Snapshot(document, caret)
    }

    override fun endComposition() {
        if (!composing) return
        composing = false
        val start = compositionStart
        compositionStart = null
        // A cancelled composition (or an identical commit) leaves no step.
        if (start != null && start.text != document) {
            undoStack.addLast(start)
        }
    }

    private fun recordEditStart() {
        redoStack.clear()
    }

    private fun recordEditEnd(
        before: Snapshot,
        changed: Boolean = true,
    ) {
        if (!changed) return
        if (composing) {
            // The group starts at the first edit, before composition began.
            if (compositionStart == null) compositionStart = before
        } else {
            undoStack.addLast(before)
        }
    }

    override fun moveCursor(byteOffset: Int): Int {
        caret = Utf8Offsets.byteToUtf16Index(document, byteOffset.coerceAtLeast(0))
        return cursor().byteOffset
    }

    override fun moveCursorHorizontal(deltaChars: Int): Int {
        var index = caret
        repeat(kotlin.math.abs(deltaChars)) {
            if (deltaChars > 0) {
                if (index < document.length) index = document.offsetByCodePoints(index, 1)
            } else if (index > 0) {
                index = document.offsetByCodePoints(index, -1)
            }
        }
        caret = index
        return cursor().byteOffset
    }

    override fun moveCursorVertical(deltaLines: Int): Int {
        val starts = lineStarts()
        val line = lineOf(caret)
        val column = caret - starts[line]
        val target = (line + deltaLines).coerceIn(0, starts.lastIndex)
        val lineEnd = if (target + 1 < starts.size) starts[target + 1] - 1 else document.length
        caret = starts[target] + column.coerceAtMost(lineEnd - starts[target])
        return cursor().byteOffset
    }

    override fun viewport(
        firstLine: Int,
        maxLines: Int,
    ): EditorViewportSnapshot {
        val starts = lineStarts()
        val first = firstLine.coerceIn(0, starts.lastIndex)
        val count = maxLines.coerceAtLeast(1).coerceAtMost(starts.size - first)
        val lines =
            (first until first + count).map { index ->
                val start = starts[index]
                val end = if (index + 1 < starts.size) starts[index + 1] - 1 else document.length
                EditorLineSlice(
                    index = index,
                    startByte = Utf8Offsets.utf8Length(document.substring(0, start)),
                    text = document.substring(start, end),
                )
            }
        return EditorViewportSnapshot(
            version = documentVersion,
            lineCount = starts.size,
            firstLine = first,
            lines = lines,
            cursor = cursor(),
        )
    }

    override fun contextBeforeCursor(maxUtf16Units: Int): String = utf16Suffix(document.substring(0, caret), maxUtf16Units)

    override fun contextAfterCursor(maxUtf16Units: Int): String = utf16Prefix(document.substring(caret), maxUtf16Units)

    private fun lineStarts(): List<Int> {
        val starts = mutableListOf(0)
        document.forEachIndexed { index, character ->
            if (character == '\n') starts.add(index + 1)
        }
        return starts
    }

    private fun lineOf(index: Int): Int = document.substring(0, index).count { it == '\n' }

    private fun lineStartIndex(index: Int): Int = document.lastIndexOf('\n', index - 1) + 1

    private fun utf16Suffix(
        text: String,
        maxUnits: Int,
    ): String {
        var units = 0
        var index = text.length
        while (index > 0) {
            val codePoint = text.codePointBefore(index)
            val width = Character.charCount(codePoint)
            if (units + width > maxUnits) break
            units += width
            index -= width
        }
        return text.substring(index)
    }

    private fun utf16Prefix(
        text: String,
        maxUnits: Int,
    ): String {
        var units = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (units + width > maxUnits) break
            units += width
            index += width
        }
        return text.substring(0, index)
    }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
