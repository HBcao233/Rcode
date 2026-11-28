package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for `LaidOutLine.textIndexForX`, the geometric half of tap-to-caret
 * (T-0015, C1/C2).
 *
 * Advances are faked (ASCII 1 px, CJK/emoji 2 px) so the midpoint rule, tab expansion
 * and surrogate pairs can be asserted exactly.
 */
class LaidOutLineHitTest {
    @Test
    fun asciiPicksTheNearestBoundary() {
        val layout = layout("abc")

        assertEquals(0, layout.textIndexForX(-10f))
        assertEquals(0, layout.textIndexForX(0f))
        assertEquals(0, layout.textIndexForX(0.4f))
        // Exact midpoint prefers the earlier boundary.
        assertEquals(0, layout.textIndexForX(0.5f))
        assertEquals(1, layout.textIndexForX(0.6f))
        assertEquals(1, layout.textIndexForX(1.5f))
        assertEquals(2, layout.textIndexForX(1.6f))
        assertEquals(3, layout.textIndexForX(2.9f))
        assertEquals(3, layout.textIndexForX(1_000f))
    }

    @Test
    fun cjkUsesTheMeasuredWidth() {
        val layout = layout("中")

        assertEquals(0, layout.textIndexForX(0f))
        assertEquals(0, layout.textIndexForX(0.9f))
        assertEquals(1, layout.textIndexForX(1.1f))
        assertEquals(1, layout.textIndexForX(1.9f))
    }

    @Test
    fun surrogatePairNeverStopsInTheMiddle() {
        // 😀 is 2 UTF-16 units / 2 px; only indices 0 and 2 are caret boundaries.
        val layout = layout("😀")

        assertEquals(0, layout.textIndexForX(0.9f))
        assertEquals(0, layout.textIndexForX(1f))
        assertEquals(2, layout.textIndexForX(1.1f))
        assertEquals(2, layout.textIndexForX(50f))
    }

    @Test
    fun mixedTextKeepsCodePointBoundaries() {
        val layout = layout("a中😀b")

        // 0 = a@0, 1 = after a, 2 = after 中, 4 = after 😀, 5 = after b.
        assertEquals(1, layout.textIndexForX(1.4f))
        assertEquals(2, layout.textIndexForX(2.6f))
        assertEquals(4, layout.textIndexForX(4.4f))
        assertEquals(5, layout.textIndexForX(5.6f))
    }

    @Test
    fun tabExpansionSnapsToItsEdges() {
        // a@0, before-tab@1, after-tab@5, after-b@6.
        val layout = layout("a\tb")

        assertEquals(1, layout.textIndexForX(2.9f))
        // Exact midpoint between the tab's two edges: the earlier boundary wins.
        assertEquals(1, layout.textIndexForX(3f))
        assertEquals(2, layout.textIndexForX(3.1f))
        assertEquals(2, layout.textIndexForX(4.9f))
        assertEquals(3, layout.textIndexForX(5.9f))
    }

    @Test
    fun emptyLineHasOnlyIndexZero() {
        assertEquals(0, layout("").textIndexForX(0f))
        assertEquals(0, layout("").textIndexForX(100f))
    }

    @Test
    fun truncatedLineStopsAtTheMeasuredPrefix() {
        val layout = layout("a".repeat(100), window = 10f)

        assertTrue(layout.truncated)
        assertEquals("measured prefix is 11 chars", 11, layout.displayText.length)
        assertEquals(11, layout.textIndexForX(10.6f))
        assertEquals(11, layout.textIndexForX(1_000f))
    }

    @Test
    fun reverseLookupAgreesWithForwardLookup() {
        val layout = layout("a中\t😀b")

        for (index in 0..layout.text.length) {
            if (!isCodePointBoundary(layout.text, index)) continue
            val x = layout.xForTextIndex(index)
            assertEquals("index $index at x $x", index, layout.textIndexForX(x))
        }
    }

    private fun isCodePointBoundary(
        text: String,
        index: Int,
    ): Boolean = index == 0 || !text[index - 1].isHighSurrogate() || !text[index].isLowSurrogate()

    private fun layout(
        text: String,
        window: Float = 10_000f,
    ): LaidOutLine =
        LineLayoutBuilder(FakeMeasurer())
            .layout(EditorLineSlice(index = 0, startByte = 0, text = text), window)

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
