package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure layout math (T-0014, V1/V5/V6).
 *
 * The measurer is faked with fixed advances, so Tab expansion, CJK, surrogate pairs and
 * the long-line window can be asserted without an Android framework.
 */
class LineLayoutBuilderTest {
    @Test
    fun asciiLineOffsetsAreCumulativeAdvances() {
        val layout = builder().layout(line("abc"), maxWidthPx = 1_000f)

        assertEquals("abc", layout.displayText)
        assertArrayEqualsFloats(floatArrayOf(0f, 1f, 2f, 3f), layout.xOffsets)
        assertEquals(3f, layout.width, 0f)
        assertFalse(layout.truncated)
        assertFalse(layout.hasTabs)
        assertEquals(0f, layout.xForTextIndex(0), 0f)
        assertEquals(2f, layout.xForTextIndex(2), 0f)
    }

    @Test
    fun wideCharactersUseTheirMeasuredAdvance() {
        // 中 is 3 UTF-8 bytes / 1 UTF-16 unit but measures 2px; 😀 is 2 units / 2px.
        val layout = builder().layout(line("中😀"), maxWidthPx = 1_000f)

        assertEquals("中😀", layout.displayText)
        assertArrayEqualsFloats(floatArrayOf(0f, 2f, 4f, 4f), layout.xOffsets)
        assertEquals(4f, layout.width, 0f)
        assertEquals(0f, layout.xForTextIndex(0), 0f)
        assertEquals(2f, layout.xForTextIndex(1), 0f)
        assertEquals(4f, layout.xForTextIndex(3), 0f)
    }

    @Test
    fun tabExpandsToFourSpaces() {
        val layout = builder().layout(line("a\tb"), maxWidthPx = 1_000f)

        assertEquals("a    b", layout.displayText)
        assertTrue(layout.hasTabs)
        assertArrayEqualsFloats(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f), layout.xOffsets)
        // The caret before the tab is at 1; after the tab (offset 2 in the text) at 5.
        assertEquals(1f, layout.xForTextIndex(1), 0f)
        assertEquals(5f, layout.xForTextIndex(2), 0f)
    }

    @Test
    fun tabAtLineStartPushesFollowingText() {
        val layout = builder().layout(line("\tx"), maxWidthPx = 1_000f)

        assertEquals("    x", layout.displayText)
        // Index 1 is past the tab (before 'x'): four space advances.
        assertEquals(4f, layout.xForTextIndex(1), 0f)
        assertEquals(5f, layout.width, 0f)
    }

    @Test
    fun tabWidthFollowsTheMeasuredSpaceAdvance() {
        // A proportional font where a space is 5px: one tab is 4 × 5px.
        val measurer =
            FakeMeasurer { codePoint ->
                if (codePoint == ' '.code) 5f else 1f
            }
        val layout = LineLayoutBuilder(measurer).layout(line("a\tb"), maxWidthPx = 1_000f)

        assertEquals(1f + 4 * 5f + 1f, layout.width, 0f)
        assertEquals(1f + 4 * 5f, layout.xForTextIndex(2), 0f)
    }

    @Test
    fun emptyLineHasASingleOffset() {
        val layout = builder().layout(line(""), maxWidthPx = 1_000f)

        assertEquals("", layout.displayText)
        assertArrayEqualsFloats(floatArrayOf(0f), layout.xOffsets)
        assertEquals(0f, layout.width, 0f)
        assertFalse(layout.truncated)
    }

    @Test
    fun longLineStopsAtTheVisibleWindow() {
        val text = "a".repeat(100_000)
        val measurer = FakeMeasurer()
        val layout = LineLayoutBuilder(measurer).layout(line(text), maxWidthPx = 10f)

        assertTrue(layout.truncated)
        // 11 characters are needed to pass a 10px window; only those are laid out.
        assertEquals(11, layout.displayText.length)
        assertEquals(11, layout.xOffsets.size - 1)
        // One chunk is measured per line regardless of its length (V6).
        assertTrue("measured ${measurer.measuredUnits} units", measurer.measuredUnits <= LineLayoutBuilder.MEASURE_CHUNK)
        assertEquals(1, measurer.measureCalls)
        // The full line is still available for byte-offset mapping (V6).
        assertEquals(100_000, layout.text.length)
        assertEquals(layout.width, layout.xForTextIndex(50_000), 0f)
    }

    @Test
    fun layoutCostDoesNotGrowWithLineLength() {
        val shortMeasurer = FakeMeasurer()
        val longMeasurer = FakeMeasurer()

        LineLayoutBuilder(shortMeasurer).layout(line("a".repeat(100_000)), maxWidthPx = 10f)
        LineLayoutBuilder(longMeasurer).layout(line("a".repeat(1_000_000)), maxWidthPx = 10f)

        assertEquals(shortMeasurer.measuredUnits, longMeasurer.measuredUnits)
        assertEquals(shortMeasurer.measureCalls, longMeasurer.measureCalls)
    }

    @Test
    fun wideWindowMeasuresTheWholeLine() {
        val measurer = FakeMeasurer()
        val layout = LineLayoutBuilder(measurer).layout(line("a".repeat(1_000)), maxWidthPx = 10_000f)

        assertFalse(layout.truncated)
        assertEquals(1_000, layout.displayText.length)
        assertEquals(1_000f, layout.width, 0f)
        assertEquals(1_000, measurer.measuredUnits)
    }

    @Test
    fun theCodePointCrossingTheWindowIsKeptWhole() {
        // maxWidth 0 still measures one code point; the surrogate pair stays intact.
        val layout = builder().layout(line("😀x"), maxWidthPx = 0f)

        assertTrue(layout.truncated)
        assertEquals("😀", layout.displayText)
        assertEquals(2f, layout.width, 0f)
    }

    @Test
    fun combiningMarksAreSeparateUnits() {
        // Known limitation (Q-T14-7): scalar-level layout, not grapheme clusters.
        val layout = builder().layout(line("e\u0301"), maxWidthPx = 1_000f)

        assertEquals("e\u0301", layout.displayText)
        assertArrayEqualsFloats(floatArrayOf(0f, 1f, 2f), layout.xOffsets)
    }

    @Test
    fun tabsAndWideCharactersMix() {
        val layout = builder().layout(line("中\t文"), maxWidthPx = 1_000f)

        assertEquals("中    文", layout.displayText)
        // 中 = 2, tab = 4 spaces × 1, 文 = 2.
        assertEquals(8f, layout.width, 0f)
        assertEquals(2f, layout.xForTextIndex(1), 0f)
        assertEquals(6f, layout.xForTextIndex(2), 0f)
        assertEquals(8f, layout.xForTextIndex(3), 0f)
    }

    @Test
    fun layoutIsStableForTheSameInput() {
        val line = line("mixed 中文\ttext")
        val first = builder().layout(line, maxWidthPx = 500f)
        val second = builder().layout(line, maxWidthPx = 500f)

        assertEquals(first.displayText, second.displayText)
        assertArrayEqualsFloats(first.xOffsets, second.xOffsets)
        assertEquals(first.width, second.width, 0f)
    }

    @Test
    fun emptyLayoutStartsTheCaretAtZero() {
        val layout = LaidOutLine.empty(EditorLineSlice(index = 7, startByte = 42, text = ""))

        assertEquals(7, layout.index)
        assertEquals(42, layout.startByte)
        assertEquals(0f, layout.xForTextIndex(0), 0f)
        assertEquals(0f, layout.xForTextIndex(5), 0f)
    }

    private fun builder(width: (Int) -> Float = DEFAULT_WIDTH): LineLayoutBuilder = LineLayoutBuilder(FakeMeasurer(width))

    private fun line(text: String): EditorLineSlice = EditorLineSlice(index = 0, startByte = 0, text = text)

    private fun assertArrayEqualsFloats(
        expected: FloatArray,
        actual: FloatArray,
    ) {
        assertEquals("size", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("index $index", expected[index], actual[index], 0f)
        }
    }

    private class FakeMeasurer(
        private val widthOf: (Int) -> Float = DEFAULT_WIDTH,
    ) : LineMeasurer {
        var measureCalls = 0
        var measuredUnits = 0

        override fun advances(
            text: CharSequence,
            start: Int,
            end: Int,
            out: FloatArray,
        ) {
            measureCalls++
            var index = start
            while (index < end) {
                val codePoint = Character.codePointAt(text, index)
                val units = Character.charCount(codePoint)
                out[index - start] = widthOf(codePoint)
                if (units == 2) out[index - start + 1] = 0f
                measuredUnits += units
                index += units
            }
        }
    }
}

/** ASCII is 1px, CJK and emoji are 2px; enough to catch units-vs-bytes mistakes. */
private val DEFAULT_WIDTH: (Int) -> Float = { codePoint ->
    when {
        codePoint in 0x2E80..0x9FFF || codePoint in 0xF900..0xFAFF -> 2f
        codePoint > 0x1F000 -> 2f
        else -> 1f
    }
}
