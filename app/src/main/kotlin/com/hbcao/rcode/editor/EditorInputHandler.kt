package com.hbcao.rcode.editor

/**
 * Translates Android IME operations into edits on an [EditorSessionApi].
 *
 * The composing span exists only on the Android side: the IME owns it, the Rust
 * document only knows text. Every IME callback is applied to the Rust session first;
 * this class only tracks where the composing span currently is so that subsequent
 * callbacks replace exactly that span (and so the view can underline it).
 *
 * Not thread-safe: all methods must be called on the main thread, like the
 * `InputConnection` callbacks they serve.
 */
class EditorInputHandler(
    private val session: EditorSessionApi,
) {
    /** Start of the composing span in UTF-8 bytes, or -1 when not composing. */
    var composingStartByte: Int = -1
        private set

    /** End of the composing span in UTF-8 bytes (exclusive), or -1 when not composing. */
    var composingEndByte: Int = -1
        private set

    val isComposing: Boolean
        get() = composingStartByte >= 0

    /**
     * Replaces the current composing span (or the caret when not composing) with
     * [text] and clears the composing state. Returns the new document version.
     */
    fun commitText(
        text: String,
        newCursorPosition: Int,
    ): Long {
        val start = if (isComposing) composingStartByte else session.cursor().byteOffset
        val end = if (isComposing) composingEndByte else start
        clearComposing()
        return replaceAndPlaceCaret(start, end, text, newCursorPosition)
    }

    /**
     * Shows [text] as the current composing text, replacing any previous composing
     * span. Returns the new document version.
     */
    fun setComposingText(
        text: String,
        newCursorPosition: Int,
    ): Long {
        val start = if (isComposing) composingStartByte else session.cursor().byteOffset
        val end = if (isComposing) composingEndByte else start
        val version = replaceAndPlaceCaret(start, end, text, newCursorPosition)
        composingStartByte = start
        composingEndByte = start + Utf8Offsets.utf8Length(text)
        return version
    }

    /** Ends composing without changing text: the visible composing text is kept. */
    fun finishComposingText() {
        clearComposing()
    }

    /** Deletes the character before the caret, keeping the composing span consistent. */
    fun deleteBackward(): Long {
        val version = session.deleteBackward()
        clampComposingToCaret()
        return version
    }

    /** Deletes the character after the caret, keeping the composing span consistent. */
    fun deleteForward(): Long {
        val version = session.deleteForward()
        clampComposingToCaret()
        return version
    }

    /**
     * Deletes up to [beforeUtf16Units] code units before the caret and
     * [afterUtf16Units] after it, as `InputConnection.deleteSurroundingText` requests.
     */
    fun deleteSurroundingText(
        beforeUtf16Units: Int,
        afterUtf16Units: Int,
    ): Long {
        val before = session.contextBeforeCursor(beforeUtf16Units.coerceAtLeast(0))
        val after = session.contextAfterCursor(afterUtf16Units.coerceAtLeast(0))
        return deleteAroundCaret(before, after)
    }

    /**
     * Code point variant of `InputConnection.deleteSurroundingTextInCodePoints`, used
     * by IMEs when deleting emoji. Lengths count Unicode code points, not UTF-16 units.
     */
    fun deleteSurroundingTextInCodePoints(
        beforeCodePoints: Int,
        afterCodePoints: Int,
    ): Long {
        val beforeWindow = beforeCodePoints.coerceIn(0, MAX_CODE_POINTS) * 2
        val afterWindow = afterCodePoints.coerceIn(0, MAX_CODE_POINTS) * 2
        val before = takeLastCodePoints(session.contextBeforeCursor(beforeWindow), beforeCodePoints.coerceAtLeast(0))
        val after = takeFirstCodePoints(session.contextAfterCursor(afterWindow), afterCodePoints.coerceAtLeast(0))
        return deleteAroundCaret(before, after)
    }

    private fun deleteAroundCaret(
        before: String,
        after: String,
    ): Long {
        val cursor = session.cursor().byteOffset
        val start = cursor - Utf8Offsets.utf8Length(before)
        val end = cursor + Utf8Offsets.utf8Length(after)
        val version = if (start == end) session.version() else session.deleteRange(start, end)
        clampComposingToCaret()
        return version
    }

    private fun takeLastCodePoints(
        text: String,
        count: Int,
    ): String {
        var index = text.length
        var taken = 0
        while (index > 0 && taken < count) {
            index -= Character.charCount(text.codePointBefore(index))
            taken += 1
        }
        return text.substring(index)
    }

    private fun takeFirstCodePoints(
        text: String,
        count: Int,
    ): String {
        var index = 0
        var taken = 0
        while (index < text.length && taken < count) {
            index += Character.charCount(text.codePointAt(index))
            taken += 1
        }
        return text.substring(0, index)
    }

    private fun replaceAndPlaceCaret(
        start: Int,
        end: Int,
        text: String,
        newCursorPosition: Int,
    ): Long {
        val version =
            if (start == end && text.isEmpty()) {
                session.version()
            } else {
                session.replaceRange(start, end, text)
            }
        val utf16Index = cursorUtf16Index(text, newCursorPosition)
        session.moveCursor(start + Utf8Offsets.utf16ToByteOffset(text, utf16Index))
        return version
    }

    /**
     * Converts `InputConnection`'s `newCursorPosition` into an index inside [text].
     * Positive values count backwards from the end (1 = after the last character);
     * values <= 0 count forwards from the start.
     */
    private fun cursorUtf16Index(
        text: String,
        newCursorPosition: Int,
    ): Int {
        val index =
            if (newCursorPosition > 0) {
                text.length - (newCursorPosition - 1)
            } else {
                -newCursorPosition
            }
        return index.coerceIn(0, text.length)
    }

    private fun clampComposingToCaret() {
        if (!isComposing) return
        val cursor = session.cursor().byteOffset
        composingStartByte = minOf(composingStartByte, cursor)
        composingEndByte = cursor.coerceAtLeast(composingStartByte)
    }

    private fun clearComposing() {
        composingStartByte = -1
        composingEndByte = -1
    }

    private companion object {
        const val MAX_CODE_POINTS = 32_768
    }
}
