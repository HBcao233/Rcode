package com.hbcao.rcode.editor

/**
 * Pure thumb geometry for the draggable scrollbars (T-0015).
 *
 * A scrollbar is drawn and hit-tested only while the content overflows; the thumb length
 * is proportional to the visible part and its travel maps linearly to the scroll offset.
 * Keeping the arithmetic here lets the JVM tests cover the mapping without a `View`.
 *
 * Coordinates are px along the scroll axis, relative to the content area (padding
 * excluded). `scroll` is the current `scrollX` / `scrollY`.
 */
internal object EditorScrollBar {
    /** Position and length of a thumb along one axis. */
    data class Thumb(
        val start: Float,
        val length: Float,
    )

    /**
     * Thumb for a scrollable axis, or `null` when [maxScroll] is zero.
     *
     * The thumb is never shorter than [minThumbPx] (so it stays draggable) and never
     * longer than [viewLength].
     */
    fun thumb(
        viewLength: Float,
        maxScroll: Float,
        scroll: Float,
        minThumbPx: Float,
    ): Thumb? {
        if (viewLength <= 0f || maxScroll <= 0f) return null
        val length = thumbLength(viewLength, maxScroll, minThumbPx)
        val travel = viewLength - length
        val start = if (travel <= 0f) 0f else (scroll.coerceIn(0f, maxScroll) / maxScroll) * travel
        return Thumb(start, length)
    }

    /**
     * Scroll offset that places the thumb's top/left edge at [thumbStart].
     *
     * The inverse of [thumb], used while dragging the bar; clamped to `0..maxScroll`.
     */
    fun scrollForThumbStart(
        thumbStart: Float,
        viewLength: Float,
        maxScroll: Float,
        minThumbPx: Float,
    ): Float {
        if (viewLength <= 0f || maxScroll <= 0f) return 0f
        val length = thumbLength(viewLength, maxScroll, minThumbPx)
        val travel = viewLength - length
        if (travel <= 0f) return 0f
        return ((thumbStart / travel) * maxScroll).coerceIn(0f, maxScroll)
    }

    /**
     * Length of the thumb for [viewLength] of visible content and [maxScroll] of range.
     *
     * Shared with the drag logic so the hit target and the drawn bar agree.
     */
    fun thumbLength(
        viewLength: Float,
        maxScroll: Float,
        minThumbPx: Float,
    ): Float {
        if (viewLength <= 0f || maxScroll <= 0f) return 0f
        val visibleRatio = viewLength / (viewLength + maxScroll)
        return (visibleRatio * viewLength).coerceIn(minThumbPx.coerceAtMost(viewLength), viewLength)
    }
}
