package com.hbcao.rcode.editor

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure viewport math for touch scrolling (T-0015).
 *
 * `EditorView` owns the pixel scroll offsets; this object only answers questions about
 * them, so the arithmetic that decides whether a fling stops at the end of the document
 * or where a tap lands can be tested on the JVM without `android.graphics`.
 *
 * Content coordinates exclude the view's padding: `scrollY = 0` puts the first line at
 * `paddingTop`, and `scrollX = 0` puts column 0 at `paddingLeft`.
 */
internal object EditorScrollGeometry {
    /**
     * Largest valid vertical scroll offset in px.
     *
     * At the maximum the last line's bottom edge sits on the view's bottom padding, so
     * no blank space is scrolled into view.
     */
    fun maxScrollY(
        lineCount: Int,
        lineHeight: Float,
        paddingTop: Int,
        paddingBottom: Int,
        viewHeight: Int,
        overscrollPx: Float = 0f,
    ): Float {
        if (lineHeight <= 0f) return 0f
        val contentBottom = paddingTop + lineCount.coerceAtLeast(1) * lineHeight + paddingBottom
        val overflow = contentBottom - viewHeight
        // always allow one extra half screen so the last lines can be pulled up and
        // blank space appears below them.
        return (overflow + overscrollPx).coerceAtLeast(0f)
    }

    /**
     * Largest valid horizontal scroll offset in px.
     *
     * [caretWidth] is included so a caret at the end of the widest line can be seen, not
     * only the glyphs; `contentWidth` is the view width minus horizontal padding.
     */
    fun maxScrollX(
        maxLineWidth: Float,
        caretWidth: Float,
        contentWidth: Int,
    ): Float {
        if (maxLineWidth <= 0f || contentWidth <= 0) return 0f
        return (maxLineWidth + caretWidth - contentWidth).coerceAtLeast(0f)
    }

    /** Line index drawn at the top of the viewport for [scrollY]. */
    fun firstLineFor(
        scrollY: Float,
        lineHeight: Float,
        lineCount: Int,
    ): Int {
        if (scrollY <= 0f || lineHeight <= 0f) return 0
        return floor(scrollY / lineHeight).toInt().coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    }

    /**
     * Minimal [scrollY] that fully shows [row], or the current one when it already is.
     *
     * Used after edits, caret moves and size changes: the viewport never scrolls farther
     * than needed to reveal the cursor row.
     */
    fun scrollYToRevealRow(
        row: Int,
        lineHeight: Float,
        scrollY: Float,
        contentHeight: Float,
    ): Float {
        if (lineHeight <= 0f || contentHeight <= 0f) return scrollY
        val top = row.coerceAtLeast(0) * lineHeight
        val bottom = top + lineHeight
        return when {
            top < scrollY -> top
            bottom > scrollY + contentHeight -> bottom - contentHeight
            else -> scrollY
        }
    }

    /**
     * Line index under a content-space y coordinate (the result of a tap).
     *
     * Values above the last line clamp to it; the caller detects the blank area below
     * the text and moves the cursor to the end of the document instead.
     */
    fun lineForContentY(
        contentY: Float,
        lineHeight: Float,
        lineCount: Int,
    ): Int {
        if (lineHeight <= 0f) return 0
        return floor(contentY / lineHeight).toInt().coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    }

    /**
     * Layout window for the current [scrollX], rounded up to [bucketPx].
     *
     * T-0015 Q-T15-1: lines are laid out only as far right as the user can see. Growing
     * the window in coarse buckets keeps the cache stable during a drag while still
     * bounding the measured prefix of a very long line. The returned value is clamped to
     * [maxWindowPx]; text past it is unreachable by scrolling (known limitation).
     */
    fun layoutWindowPx(
        contentWidth: Int,
        scrollX: Float,
        lineHeight: Float,
        bucketPx: Float,
        maxWindowPx: Float,
    ): Float {
        if (contentWidth <= 0 || bucketPx <= 0f) return 0f
        val bucket = ceil(scrollX.coerceAtLeast(0f) / bucketPx) * bucketPx
        return (contentWidth + bucket + lineHeight.coerceAtLeast(0f)).coerceAtMost(maxWindowPx)
    }
}
