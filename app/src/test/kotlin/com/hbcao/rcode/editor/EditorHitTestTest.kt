package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for `EditorHitTest.byteOffsetFor`: the byte offset Rust receives after a
 * tap (T-0015, C1/C4). UTF-16 indices must be converted back to UTF-8 bytes and the
 * line's `startByte` must be added.
 */
class EditorHitTestTest {
    @Test
    fun asciiOffsetsAreOneByteEach() {
        val layout = layout("abc", startByte = 100)

        assertEquals(100, EditorHitTest.byteOffsetFor(layout, 0f))
        assertEquals(101, EditorHitTest.byteOffsetFor(layout, 1.1f))
        assertEquals(102, EditorHitTest.byteOffsetFor(layout, 2.1f))
        assertEquals(103, EditorHitTest.byteOffsetFor(layout, 5f))
    }

    @Test
    fun cjkAndEmojiUseTheirUtf8Length() {
        // a = 1 byte, 中 = 3 bytes, 😀 = 4 bytes.
        val layout = layout("a中😀", startByte = 100)

        assertEquals(100, EditorHitTest.byteOffsetFor(layout, 0.4f))
        assertEquals(101, EditorHitTest.byteOffsetFor(layout, 0.6f))
        // 中 spans x 1..3, 😀 spans x 3..5.
        assertEquals(104, EditorHitTest.byteOffsetFor(layout, 2.6f))
        assertEquals(108, EditorHitTest.byteOffsetFor(layout, 4.6f))
        assertEquals(108, EditorHitTest.byteOffsetFor(layout, 50f))
    }

    @Test
    fun emojiNeverPlacesTheCaretInsideTheSurrogatePair() {
        val layout = layout("😀", startByte = 0)

        // Exactly at the middle of the pair the earlier boundary wins.
        assertEquals(0, EditorHitTest.byteOffsetFor(layout, 1f))
        assertEquals(4, EditorHitTest.byteOffsetFor(layout, 1.1f))
    }

    @Test
    fun tabsAreOneByte() {
        val layout = layout("a\tb", startByte = 0)

        assertEquals(1, EditorHitTest.byteOffsetFor(layout, 1f))
        assertEquals(2, EditorHitTest.byteOffsetFor(layout, 4.6f))
        assertEquals(3, EditorHitTest.byteOffsetFor(layout, 5.9f))
    }

    @Test
    fun emptyLineReturnsItsStartByte() {
        assertEquals(42, EditorHitTest.byteOffsetFor(layout("", startByte = 42), 100f))
    }

    private fun layout(
        text: String,
        startByte: Int,
    ): LaidOutLine =
        LineLayoutBuilder(FakeMeasurer())
            .layout(EditorLineSlice(index = 0, startByte = startByte, text = text), maxWidthPx = 10_000f)

    private class FakeMeasurer : LineMeasurer {
        override fun advances(
            text: CharSequence,
            start: Int,
            end: Int,
            out: FloatArray,
        ) {
            var index = start
            while (index < end) {
                val codePoint = Character.codePointAt(text, index)
                val units = Character.charCount(codePoint)
                out[index - start] = if (isWide(codePoint)) 2f else 1f
                if (units == 2) out[index - start + 1] = 0f
                index += units
            }
        }

        private fun isWide(codePoint: Int): Boolean = codePoint in 0x2E80..0x9FFF || codePoint > 0x1F000
    }
}
