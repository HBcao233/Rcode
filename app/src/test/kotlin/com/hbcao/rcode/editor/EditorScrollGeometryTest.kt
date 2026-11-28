package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the viewport arithmetic of T-0015 (S1, S2 and the caret-follow rule).
 *
 * The values are px, matching `EditorView`'s pixel scroll offsets; no Android framework
 * is involved, so a scroll boundary that is wrong can be caught without a device.
 */
class EditorScrollGeometryTest {
    @Test
    fun verticalRangeIsZeroWhenTheDocumentFits() {
        // 10 lines × 20 px = 200 px of content in a 300 px view.
        assertEquals(0f, maxScrollY(lineCount = 10, viewHeight = 300), 0f)
        // 14 lines × 20 px + 20 px padding is exactly one screen (300 px).
        assertEquals(0f, maxScrollY(lineCount = 14, viewHeight = 300), 0f)
    }

    @Test
    fun verticalRangeShowsTheLastLineAtTheBottom() {
        // 100 lines × 20 px = 2 000 px of content, plus 20 px padding, in a 300 px view.
        assertEquals(1_720f, maxScrollY(lineCount = 100, viewHeight = 300), 0f)
        // Padding is part of the content extent: 1 000 + 10 + 10 − 300.
        assertEquals(720f, maxScrollY(lineCount = 50, viewHeight = 300), 0f)
    }

    @Test
    fun emptyDocumentNeverScrolls() {
        assertEquals(0f, maxScrollY(lineCount = 0, viewHeight = 300), 0f)
        assertEquals(0f, maxScrollY(lineCount = 1, viewHeight = 300), 0f)
    }

    @Test
    fun horizontalRangeIsZeroWhenEveryLineFits() {
        assertEquals(0f, maxScrollX(maxLineWidth = 1_000f, caretWidth = 2f, contentWidth = 1_080), 0f)
        // A line exactly as wide as the window still needs room for the caret.
        assertEquals(2f, maxScrollX(maxLineWidth = 1_080f, caretWidth = 2f, contentWidth = 1_080), 0f)
    }

    @Test
    fun horizontalRangeRevealsTheWidestLineAndItsCaret() {
        assertEquals(1_000f, maxScrollX(maxLineWidth = 2_080f, caretWidth = 0f, contentWidth = 1_080), 0f)
        // 2 000 px of line + caret in a 1 080 px window.
        assertEquals(922f, maxScrollX(maxLineWidth = 2_000f, caretWidth = 2f, contentWidth = 1_080), 0f)
    }

    @Test
    fun horizontalRangeIsZeroWithoutContent() {
        assertEquals(0f, maxScrollX(maxLineWidth = 0f, caretWidth = 2f, contentWidth = 1_080), 0f)
        assertEquals(0f, maxScrollX(maxLineWidth = 500f, caretWidth = 2f, contentWidth = 0), 0f)
    }

    @Test
    fun firstLineFollowsThePixelOffset() {
        assertEquals(0, EditorScrollGeometry.firstLineFor(scrollY = 0f, lineHeight = 20f, lineCount = 100))
        assertEquals(0, EditorScrollGeometry.firstLineFor(scrollY = 19.9f, lineHeight = 20f, lineCount = 100))
        assertEquals(1, EditorScrollGeometry.firstLineFor(scrollY = 20f, lineHeight = 20f, lineCount = 100))
        assertEquals(7, EditorScrollGeometry.firstLineFor(scrollY = 150f, lineHeight = 20f, lineCount = 100))
    }

    @Test
    fun firstLineNeverExceedsTheDocument() {
        assertEquals(99, EditorScrollGeometry.firstLineFor(scrollY = 99_999f, lineHeight = 20f, lineCount = 100))
        assertEquals(0, EditorScrollGeometry.firstLineFor(scrollY = -5f, lineHeight = 20f, lineCount = 100))
        assertEquals(0, EditorScrollGeometry.firstLineFor(scrollY = 100f, lineHeight = 20f, lineCount = 0))
    }

    @Test
    fun revealingARowAboveScrollsToItsTop() {
        assertEquals(
            200f,
            EditorScrollGeometry.scrollYToRevealRow(row = 10, lineHeight = 20f, scrollY = 400f, contentHeight = 300f),
            0f,
        )
    }

    @Test
    fun revealingARowBelowScrollsJustEnough() {
        // Row 20 spans 400..420; the top of the view must be at 420 − 300 = 120.
        assertEquals(
            120f,
            EditorScrollGeometry.scrollYToRevealRow(row = 20, lineHeight = 20f, scrollY = 0f, contentHeight = 300f),
            0f,
        )
    }

    @Test
    fun revealingAVisibleRowDoesNotScroll() {
        assertEquals(
            180f,
            EditorScrollGeometry.scrollYToRevealRow(row = 12, lineHeight = 20f, scrollY = 180f, contentHeight = 300f),
            0f,
        )
        // A row entirely inside the viewport is left alone.
        assertEquals(
            190f,
            EditorScrollGeometry.scrollYToRevealRow(row = 12, lineHeight = 20f, scrollY = 190f, contentHeight = 300f),
            0f,
        )
    }

    @Test
    fun tapLineComesFromTheContentCoordinate() {
        assertEquals(0, EditorScrollGeometry.lineForContentY(contentY = -10f, lineHeight = 20f, lineCount = 50))
        assertEquals(0, EditorScrollGeometry.lineForContentY(contentY = 0f, lineHeight = 20f, lineCount = 50))
        assertEquals(0, EditorScrollGeometry.lineForContentY(contentY = 19.9f, lineHeight = 20f, lineCount = 50))
        assertEquals(1, EditorScrollGeometry.lineForContentY(contentY = 20f, lineHeight = 20f, lineCount = 50))
        assertEquals(49, EditorScrollGeometry.lineForContentY(contentY = 9_999f, lineHeight = 20f, lineCount = 50))
    }

    @Test
    fun layoutWindowGrowsInBucketsAndStopsAtTheCap() {
        // 1 080 px content, 20 px line height, 512 px buckets.
        assertEquals(1_100f, layoutWindow(contentWidth = 1_080, scrollX = 0f), 0f)
        assertEquals(1_612f, layoutWindow(contentWidth = 1_080, scrollX = 1f), 0f)
        assertEquals(1_612f, layoutWindow(contentWidth = 1_080, scrollX = 512f), 0f)
        assertEquals(2_124f, layoutWindow(contentWidth = 1_080, scrollX = 513f), 0f)
        // Past the 100 000 px cap the window stops growing.
        assertEquals(100_000f, layoutWindow(contentWidth = 1_080, scrollX = 500_000f), 0f)
    }

    private fun maxScrollY(
        lineCount: Int,
        viewHeight: Int,
    ): Float =
        EditorScrollGeometry.maxScrollY(
            lineCount = lineCount,
            lineHeight = 20f,
            paddingTop = 10,
            paddingBottom = 10,
            viewHeight = viewHeight,
        )

    private fun maxScrollX(
        maxLineWidth: Float,
        caretWidth: Float,
        contentWidth: Int,
    ): Float =
        EditorScrollGeometry.maxScrollX(
            maxLineWidth = maxLineWidth,
            caretWidth = caretWidth,
            contentWidth = contentWidth,
        )

    private fun layoutWindow(
        contentWidth: Int,
        scrollX: Float,
    ): Float =
        EditorScrollGeometry.layoutWindowPx(
            contentWidth = contentWidth,
            scrollX = scrollX,
            lineHeight = 20f,
            bucketPx = 512f,
            maxWindowPx = 100_000f,
        )
}
