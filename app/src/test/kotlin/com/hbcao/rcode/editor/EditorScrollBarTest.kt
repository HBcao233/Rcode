package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the draggable scrollbar thumb math (T-0015 issue 3).
 *
 * The mapping between the thumb position and the scroll offset must round-trip, otherwise
 * dragging the bar jumps; the thumb also has to stay finger-sized on very long documents.
 */
class EditorScrollBarTest {
    @Test
    fun noThumbWithoutScrollableContent() {
        assertNull(EditorScrollBar.thumb(viewLength = 600f, maxScroll = 0f, scroll = 0f, minThumbPx = 24f))
        assertNull(EditorScrollBar.thumb(viewLength = 0f, maxScroll = 100f, scroll = 0f, minThumbPx = 24f))
    }

    @Test
    fun thumbLengthIsTheVisibleFractionOfTheContent() {
        // 1 000 px visible out of 2 000 px of content.
        assertEquals(500f, EditorScrollBar.thumbLength(viewLength = 1_000f, maxScroll = 1_000f, minThumbPx = 24f), 0f)
        // The thumb never gets shorter than the minimum touch size.
        assertEquals(24f, EditorScrollBar.thumbLength(viewLength = 1_000f, maxScroll = 100_000f, minThumbPx = 24f), 0f)
        // ... and never longer than the view (a 1 px range is almost the whole view).
        assertEquals(999.001f, EditorScrollBar.thumbLength(viewLength = 1_000f, maxScroll = 1f, minThumbPx = 24f), 0.01f)
    }

    @Test
    fun thumbTravelsFromTopToBottom() {
        assertEquals(0f, thumbStart(scroll = 0f), 0f)
        // Half the scroll range puts the thumb half-way through its travel.
        assertEquals(250f, thumbStart(scroll = 500f), 0f)
        assertEquals(500f, thumbStart(scroll = 1_000f), 0f)
        // Offsets outside the range clamp to the ends.
        assertEquals(0f, thumbStart(scroll = -50f), 0f)
        assertEquals(500f, thumbStart(scroll = 2_000f), 0f)
    }

    @Test
    fun thumbStartMapsBackToTheScrollOffset() {
        for (scroll in listOf(0f, 123f, 500f, 999f, 1_000f)) {
            val start = thumbStart(scroll)
            assertEquals(scroll, scrollFor(start), 0.01f)
        }
        assertEquals(0f, scrollFor(-100f), 0f)
        assertEquals(1_000f, scrollFor(10_000f), 1_000f)
    }

    @Test
    fun draggingUsesTheSameGeometryAsDrawing() {
        // A thumb dropped at the middle of the track scrolls to the middle of the range.
        val length = EditorScrollBar.thumbLength(viewLength = 600f, maxScroll = 1_800f, minThumbPx = 24f)
        val travel = 600f - length
        val middle = travel / 2f
        assertEquals(900f, EditorScrollBar.scrollForThumbStart(middle, 600f, 1_800f, 24f), 0.01f)
    }

    private fun thumbStart(scroll: Float): Float =
        EditorScrollBar.thumb(viewLength = 1_000f, maxScroll = 1_000f, scroll = scroll, minThumbPx = 24f)!!.start

    private fun scrollFor(thumbStart: Float): Float =
        EditorScrollBar.scrollForThumbStart(thumbStart, viewLength = 1_000f, maxScroll = 1_000f, minThumbPx = 24f)
}
