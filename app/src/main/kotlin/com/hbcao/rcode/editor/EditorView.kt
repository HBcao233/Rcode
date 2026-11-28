package com.hbcao.rcode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.hbcao.rcode.ui.theme.EditorColors
import kotlin.math.roundToInt

/**
 * Custom text editor view for M1.
 *
 * Text lives in the Rust [EditorSessionApi]; this view only caches the visible lines
 * and the caret geometry for the current document version (see ADR-0002). Drawing is
 * pure Canvas work over that cache, so `onDraw` performs no FFI calls and allocates
 * nothing per frame.
 *
 * Since T-0015 the viewport is pixel-based: [scrollX] / [scrollY] are the content
 * offsets, single-finger drags move them, flings animate them through an
 * [OverScroller], and a tap moves the caret through `moveCursor`. Rust remains the
 * owner of the caret position; this view only asks for it.
 */
class EditorView(
    context: Context,
    private val session: EditorSessionApi,
    private val colors: EditorColors,
    /** Receives the new document version after every edit, open or undo/redo. */
    private val onVersionChanged: (Long) -> Unit = {},
    /** Ctrl+S from a hardware keyboard (T-0013, Q-T13-1). */
    private val onSaveRequested: () -> Unit = {},
) : View(context) {
    private val inputHandler = EditorInputHandler(session)

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = sp(DEFAULT_TEXT_SIZE_SP)
            color = colors.text
        }

    private val caretPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colors.caret
        }

    private val composingPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = colors.composingUnderline
        }

    private val lineHeight: Float = textPaint.fontSpacing
    private val caretWidth: Float = dp(2f)
    private val caretInset: Float = dp(1f)

    /** Drawn scrollbar thumb; semi-transparent so it does not hide code underneath. */
    private val scrollBarPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (colors.text and 0x00FFFFFF) or (SCROLLBAR_ALPHA shl 24)
        }
    private val scrollBarThickness: Float = dp(SCROLLBAR_THICKNESS_DP)
    private val scrollBarTouchWidth: Float = dp(SCROLLBAR_TOUCH_WIDTH_DP)
    private val scrollBarMinLength: Float = dp(SCROLLBAR_MIN_LENGTH_DP)
    private val scrollBarInset: Float = dp(SCROLLBAR_INSET_DP)
    private val scrollBarRadius: Float = scrollBarThickness / 2f

    /** Measures advances once per text range; shared by every line of this view. */
    private val layoutBuilder = LineLayoutBuilder(PaintLineMeasurer(textPaint))

    /** Laid-out lines for the current document/version/font; see [EditorLineLayoutCache]. */
    private var layoutCache = EditorLineLayoutCache(layoutBuilder, EditorLineLayoutCache.capacityFor(1))

    /** Pixel offset of the viewport inside the content; `0` is the first line/column. */
    private var scrollX: Float = 0f
    private var scrollY: Float = 0f

    /** Total lines in the last viewport snapshot; bounds the vertical scroll. */
    private var lineCount: Int = 1

    /** Widest measured line for the current document state (T-0015 Q-T15-2). */
    private var maxLineWidth: Float = 0f

    /** Layout window (px) used by [laidOutLines]; a change invalidates every layout. */
    private var layoutWindowPx: Float = 0f

    /** Number of fully visible rows; the prefetch window is one screen on each side. */
    private var visibleRows: Int = 1

    /** Line index of `laidOutLines.first()`; lines before it are prefetch, not drawn. */
    private var layoutFirstLine: Int = 0

    /** Lines returned by the last `viewport` call, kept to re-layout without FFI. */
    private var laidOutSlices: List<EditorLineSlice> = emptyList()

    /** Version of [laidOutLines]; a change resets the accumulated [maxLineWidth]. */
    private var laidOutVersion: Long = -1

    /** Last viewport snapshot; source of the caret and composing geometry. */
    private var viewportSnapshot: EditorViewportSnapshot? = null

    /** Document shown by this view; a change resets the viewport and drops layouts (T-0012). */
    private var documentId: Long = 0

    private var reportedVersion: Long = -1
    private var firstViewportStartedNanos: Long = 0

    private var laidOutLines: List<LaidOutLine> = emptyList()
    private var caretRect: RectF? = null
    private var composingUnderlines: List<ComposingUnderline> = emptyList()

    private val scroller = OverScroller(context)

    /** Which draggable scrollbar currently owns the touch stream (issue 3). */
    private var scrollBarDragAxis: Int = SCROLLBAR_NONE

    /** Distance from the thumb's start edge to the finger when the drag began. */
    private var scrollBarGrabOffset: Float = 0f

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    performClick()
                    // A tap is the only touch gesture that may focus the editor and open
                    // the IME; a drag must not (it would pop the keyboard up on scroll).
                    if (!isFocused) requestFocus()
                    showKeyboard()
                    moveCaretTo(e.x, e.y)
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    // Content follows the finger: swiping up shows the lines below.
                    scrollContentBy(distanceX, distanceY)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    scroller.fling(
                        scrollX.roundToInt(),
                        scrollY.roundToInt(),
                        -velocityX.roundToInt(),
                        -velocityY.roundToInt(),
                        0,
                        maxScrollX().roundToInt(),
                        0,
                        maxScrollY().roundToInt(),
                    )
                    postInvalidateOnAnimation()
                    return true
                }
            },
        )

    /** Everything a line layout depends on besides the text (cache key part, T-0014). */
    private val fontConfig: FontConfig
        get() = FontConfig(System.identityHashCode(textPaint.typeface), textPaint.textSize, lineHeight)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(colors.background)
        // Use the detector only for single taps, drags and flings. `SimpleOnGestureListener`
        // also implements `OnDoubleTapListener`, so the constructor installs it; then a
        // quick second touch after a tap is treated as a double tap, ACTION_MOVE goes to
        // `onDoubleTapEvent` and scrolling is suppressed until the double-tap window
        // expires (tap-then-drag did not scroll). T-0016 can install its own listener.
        gestureDetector.setOnDoubleTapListener(null)
    }

    // region IME / input path

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        val selection = session.cursorUtf16Offset()
        outAttrs.initialSelStart = selection
        outAttrs.initialSelEnd = selection
        return EditorInputConnection(this)
    }

    /** Applies `InputConnection.commitText` to the document. */
    internal fun commitText(
        text: String,
        newCursorPosition: Int,
    ) {
        inputHandler.commitText(text, newCursorPosition)
        refreshViewport()
    }

    /** Applies `InputConnection.setComposingText` to the document. */
    internal fun setComposingText(
        text: String,
        newCursorPosition: Int,
    ) {
        inputHandler.setComposingText(text, newCursorPosition)
        refreshViewport()
    }

    /** Ends composing without changing text. */
    internal fun finishComposingText() {
        inputHandler.finishComposingText()
        refreshViewport()
    }

    /** Applies `InputConnection.deleteSurroundingText` in UTF-16 code units. */
    internal fun deleteSurroundingText(
        beforeUtf16Units: Int,
        afterUtf16Units: Int,
    ) {
        inputHandler.deleteSurroundingText(beforeUtf16Units, afterUtf16Units)
        refreshViewport()
    }

    internal fun deleteSurroundingTextInCodePoints(
        beforeCodePoints: Int,
        afterCodePoints: Int,
    ) {
        inputHandler.deleteSurroundingTextInCodePoints(beforeCodePoints, afterCodePoints)
        refreshViewport()
    }

    internal fun textBeforeCursor(maxUtf16Units: Int): CharSequence =
        session.contextBeforeCursor(maxUtf16Units.coerceIn(0, MAX_CONTEXT_UNITS))

    internal fun textAfterCursor(maxUtf16Units: Int): CharSequence =
        session.contextAfterCursor(maxUtf16Units.coerceIn(0, MAX_CONTEXT_UNITS))

    internal fun handleImeKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            return performEditorKey(event.keyCode)
        }
        return isEditorKey(event.keyCode)
    }

    private fun performEditorKey(keyCode: Int): Boolean {
        if (isArrowKey(keyCode) && inputHandler.isComposing) {
            // Moving away from the composing span ends the IME composition, so the
            // next input starts a new undo group.
            inputHandler.finishComposingText()
        }
        val handled =
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    inputHandler.commitText("\n", 1)
                    true
                }

                KeyEvent.KEYCODE_DEL -> {
                    inputHandler.deleteBackward()
                    true
                }

                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    inputHandler.deleteForward()
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    session.moveCursorHorizontal(-1)
                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    session.moveCursorHorizontal(1)
                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    session.moveCursorVertical(-1)
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    session.moveCursorVertical(1)
                    true
                }

                else -> false
            }
        if (handled) refreshViewport()
        return handled
    }

    private fun isArrowKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> true

            else -> false
        }

    private fun isEditorKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> true

            else -> false
        }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (isSaveShortcut(event)) {
            // Key repeat must not queue several saves; the view model also ignores
            // triggers while a transaction runs.
            if (event.repeatCount == 0) onSaveRequested()
            return true
        }
        return performEditorKey(keyCode) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = isSaveShortcut(event) || isEditorKey(keyCode) || super.onKeyUp(keyCode, event)

    /** Q-T13-1: `Ctrl+S` saves; hardware keys reach this focused view first. */
    private fun isSaveShortcut(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_S && event.isCtrlPressed

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scrollBarDragAxis != SCROLLBAR_NONE) {
            handleScrollBarTouch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && beginScrollBarDrag(event.x, event.y)) {
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Touching the editor stops an ongoing fling (S3). Focus and the IME wait for
            // `onSingleTapUp`: a drag starts with the same ACTION_DOWN and must not open
            // the keyboard.
            scroller.forceFinished(true)
            if (inputHandler.isComposing) {
                // Keep the visible composing text, but do not scroll back to the caret:
                // the user may be starting a drag (C6).
                inputHandler.finishComposingText()
                refreshViewport(followCaret = false)
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Wraps the inertial scroll started by [GestureDetector.SimpleOnGestureListener.onFling]. */
    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        scrollContentTo(scroller.currX.toFloat(), scroller.currY.toFloat())
        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) showKeyboard()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) showKeyboard()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            if (!isFocused) requestFocus()
            showKeyboard()
        }
    }

    private fun showKeyboard() {
        if (!isFocused) return
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // endregion

    // region touch scrolling (T-0015)

    /** Moves the viewport by [dx] / [dy] px, clamped to the scrollable range. */
    private fun scrollContentBy(
        dx: Float,
        dy: Float,
    ) {
        scrollContentTo(scrollX + dx, scrollY + dy)
    }

    private fun scrollContentTo(
        x: Float,
        y: Float,
    ) {
        if (width <= 0 || height <= 0) return
        val clampedX = x.coerceIn(0f, maxScrollX())
        val clampedY = y.coerceIn(0f, maxScrollY())
        if (clampedX == scrollX && clampedY == scrollY) return
        scrollX = clampedX
        scrollY = clampedY
        relayoutIfWindowChanged()
        ensureVisibleLinesLaidOut()
        invalidate()
    }

    private fun maxScrollX(): Float =
        EditorScrollGeometry.maxScrollX(
            maxLineWidth = maxLineWidth,
            caretWidth = caretWidth,
            contentWidth = width - paddingLeft - paddingRight,
        )

    private fun maxScrollY(): Float =
        EditorScrollGeometry.maxScrollY(
            lineCount = lineCount,
            lineHeight = lineHeight,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
            viewHeight = height,
            // Issue 2: after the last line, allow pulling the content up half a view.
            overscrollPx = height / 2f,
        )

    private fun contentHeight(): Float = (height - paddingTop - paddingBottom).toFloat()

    /**
     * Re-lays out the loaded lines when [scrollX] crossed a layout-window bucket.
     *
     * The cache key does not include the window (T-0014), so a different window must
     * drop the old geometry; the slices are still in memory, so no FFI call is needed.
     */
    private fun relayoutIfWindowChanged() {
        if (computeLayoutWindowPx() == layoutWindowPx) return
        // `layoutAllLines` notices the new window, drops the old geometry and re-lays
        // out the slices it already has, so no FFI call is needed here.
        layoutAllLines()
        updateCaretGeometry()
    }

    /** Fetches the three-screen window only when the visible range left it. */
    private fun ensureVisibleLinesLaidOut() {
        if (laidOutSlices.isEmpty()) {
            loadViewport(visibleRows, firstLine)
            return
        }
        val first = firstLine
        // The overscroll region below the last line does not need more lines.
        val last = minOf(first + visibleRows, (lineCount - 1).coerceAtLeast(0))
        val laidOutLast = layoutFirstLine + laidOutSlices.size - 1
        if (first < layoutFirstLine || last > laidOutLast) {
            loadViewport(visibleRows, first)
        }
    }

    /** Clamps both offsets after the document, the view size or the font changed. */
    private fun clampScrollOffsets() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = scrollY.coerceIn(0f, maxScrollY())
        // Always re-check the window: revealing the caret can jump `scrollX` across a
        // layout-window bucket without changing the clamped value, and the stale
        // (truncated) layouts would leave the right part of the line unpainted.
        relayoutIfWindowChanged()
    }

    /**
     * Scrolls horizontally just enough to show the caret.
     *
     * The vertical part is handled by [EditorScrollGeometry.scrollYToRevealRow].
     */
    private fun revealCaretHorizontally() {
        val caret = caretRect ?: return
        val caretX = caret.left + caretInset
        val leftLimit = paddingLeft.toFloat()
        val rightLimit = width - paddingRight - caretWidth
        val screenX = caretX - scrollX
        when {
            screenX < leftLimit -> scrollX = caretX - leftLimit
            screenX > rightLimit -> scrollX = caretX - rightLimit
        }
    }

    private fun computeLayoutWindowPx(): Float =
        EditorScrollGeometry.layoutWindowPx(
            contentWidth = width - paddingLeft - paddingRight,
            scrollX = scrollX,
            lineHeight = lineHeight,
            bucketPx = SCROLL_WINDOW_BUCKET_PX,
            maxWindowPx = MAX_LINE_LAYOUT_WINDOW_PX,
        )

    // endregion

    // region scrollbars (T-0015 issue 3)

    private fun verticalScrollBarThumb(): EditorScrollBar.Thumb? =
        EditorScrollBar.thumb(
            viewLength = contentHeight(),
            maxScroll = maxScrollY(),
            scroll = scrollY,
            minThumbPx = scrollBarMinLength,
        )

    private fun horizontalScrollBarThumb(): EditorScrollBar.Thumb? =
        EditorScrollBar.thumb(
            viewLength = (width - paddingLeft - paddingRight).toFloat(),
            maxScroll = maxScrollX(),
            scroll = scrollX,
            minThumbPx = scrollBarMinLength,
        )

    /**
     * Starts a scrollbar drag when [x] / [y] lands on a bar; returns whether it did.
     *
     * The visible bar is [scrollBarThickness] wide, but the touch target next to the edge
     * is [scrollBarTouchWidth] so it can actually be grabbed with a finger. Touching the
     * track away from the thumb centers the thumb there before dragging.
     */
    private fun beginScrollBarDrag(
        x: Float,
        y: Float,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val vertical = verticalScrollBarThumb()
        if (vertical != null) {
            val touchLeft = width - paddingRight - scrollBarTouchWidth
            if (x >= touchLeft && y >= paddingTop && y <= height - paddingBottom) {
                scroller.forceFinished(true)
                scrollBarDragAxis = SCROLLBAR_VERTICAL
                val thumbTop = paddingTop + vertical.start
                scrollBarGrabOffset =
                    if (y >= thumbTop && y <= thumbTop + vertical.length) {
                        y - thumbTop
                    } else {
                        vertical.length / 2f
                    }
                dragScrollBarTo(y)
                return true
            }
        }
        val horizontal = horizontalScrollBarThumb()
        if (horizontal != null) {
            val touchTop = height - paddingBottom - scrollBarTouchWidth
            if (y >= touchTop && x >= paddingLeft && x <= width - paddingRight) {
                scroller.forceFinished(true)
                scrollBarDragAxis = SCROLLBAR_HORIZONTAL
                val thumbLeft = paddingLeft + horizontal.start
                scrollBarGrabOffset =
                    if (x >= thumbLeft && x <= thumbLeft + horizontal.length) {
                        x - thumbLeft
                    } else {
                        horizontal.length / 2f
                    }
                dragScrollBarTo(x)
                return true
            }
        }
        return false
    }

    /** Moves the viewport so the dragged thumb follows [position] (px on its axis). */
    private fun dragScrollBarTo(position: Float) {
        when (scrollBarDragAxis) {
            SCROLLBAR_VERTICAL -> {
                val thumbStart = position - paddingTop - scrollBarGrabOffset
                val scroll =
                    EditorScrollBar.scrollForThumbStart(
                        thumbStart = thumbStart,
                        viewLength = contentHeight(),
                        maxScroll = maxScrollY(),
                        minThumbPx = scrollBarMinLength,
                    )
                scrollContentTo(scrollX, scroll)
            }

            SCROLLBAR_HORIZONTAL -> {
                val thumbStart = position - paddingLeft - scrollBarGrabOffset
                val scroll =
                    EditorScrollBar.scrollForThumbStart(
                        thumbStart = thumbStart,
                        viewLength = (width - paddingLeft - paddingRight).toFloat(),
                        maxScroll = maxScrollX(),
                        minThumbPx = scrollBarMinLength,
                    )
                scrollContentTo(scroll, scrollY)
            }
        }
    }

    private fun handleScrollBarTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                dragScrollBarTo(if (scrollBarDragAxis == SCROLLBAR_VERTICAL) event.y else event.x)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scrollBarDragAxis = SCROLLBAR_NONE
            else -> Unit
        }
    }

    /** Draws the thumbs on top of the text, outside the scrolled content translation. */
    private fun drawScrollBars(canvas: Canvas) {
        verticalScrollBarThumb()?.let { thumb ->
            val left = width - paddingRight - scrollBarInset - scrollBarThickness
            val top = paddingTop + thumb.start
            canvas.drawRoundRect(
                left,
                top,
                left + scrollBarThickness,
                top + thumb.length,
                scrollBarRadius,
                scrollBarRadius,
                scrollBarPaint,
            )
        }
        horizontalScrollBarThumb()?.let { thumb ->
            val top = height - paddingBottom - scrollBarInset - scrollBarThickness
            val left = paddingLeft + thumb.start
            canvas.drawRoundRect(
                left,
                top,
                left + thumb.length,
                top + scrollBarThickness,
                scrollBarRadius,
                scrollBarRadius,
                scrollBarPaint,
            )
        }
    }

    // endregion

    // region tap-to-caret (T-0015)

    /**
     * Places the caret at a tap: line from the y coordinate, nearest character boundary
     * from the x coordinate, then [EditorSessionApi.moveCursor] (Rust snaps and clamps).
     */
    private fun moveCaretTo(
        x: Float,
        y: Float,
    ) {
        if (width <= 0 || height <= 0) return
        val contentY = y + scrollY - paddingTop
        if (contentY > lineCount * lineHeight) {
            // Blank space below the last line: the end of the document (C3).
            session.moveCursor(Int.MAX_VALUE)
            notifySelectionChanged()
            refreshViewport()
            return
        }
        val lineIndex = EditorScrollGeometry.lineForContentY(contentY, lineHeight, lineCount)
        val slice = sliceForLine(lineIndex) ?: return
        val lineX = x + scrollX - paddingLeft
        val layout = layoutForTap(slice, lineX)
        session.moveCursor(EditorHitTest.byteOffsetFor(layout, lineX))
        notifySelectionChanged()
        refreshViewport()
    }

    /** Slice of [lineIndex] from the loaded window, or a one-line FFI read on a miss. */
    private fun sliceForLine(lineIndex: Int): EditorLineSlice? {
        laidOutSlices.getOrNull(lineIndex - layoutFirstLine)?.let { return it }
        return session.viewport(lineIndex, 1).lines.firstOrNull()
    }

    /**
     * Layout used for a tap. A line that was truncated by the scroll window is measured
     * once with a window that reaches the tap (Q-T15-4); the result is not cached.
     */
    private fun layoutForTap(
        slice: EditorLineSlice,
        lineX: Float,
    ): LaidOutLine {
        val cached = layoutAt(slice.index)
        if (cached != null && (!cached.truncated || lineX <= cached.width)) return cached
        val window =
            maxOf(layoutWindowPx, lineX + lineHeight)
                .coerceIn(lineHeight.coerceAtLeast(1f), MAX_LINE_LAYOUT_WINDOW_PX)
        return layoutBuilder.layout(slice, window)
    }

    /** Tells the IME where the caret is after a tap (C7). */
    private fun notifySelectionChanged() {
        val caret = session.cursorUtf16Offset()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.updateSelection(this, caret, caret, -1, -1)
    }

    // endregion

    // region viewport and rendering

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // A layout depends on the width (the x window it was measured against), not on
        // the height; only a width change invalidates every entry. The IME opening or
        // closing (adjustResize) changes the height alone, so the cache survives it and
        // only the row count / capacity is recomputed (T-0014).
        val rows = visibleRowsFor(height)
        visibleRows = rows
        if (width != oldWidth) {
            layoutCache = EditorLineLayoutCache(layoutBuilder, EditorLineLayoutCache.capacityFor(rows))
            layoutWindowPx = 0f
            maxLineWidth = 0f
        } else {
            ensureCacheCapacity(rows)
        }
        refreshViewport()
    }

    /**
     * Reloads the visible lines from the Rust session. Called after edits and size
     * changes, never from `onDraw` or a scroll frame that stays inside the loaded window.
     *
     * One `viewport` call fetches the visible rows plus one prefetch screen above and
     * below (Q-T14-2); each row is laid out once per document version and font (T-0014).
     */
    fun refreshViewport() {
        refreshViewport(followCaret = true)
    }

    private fun refreshViewport(followCaret: Boolean) {
        if (width <= 0 || height <= 0) return
        if (contentHeight() <= 0f) return
        val rows = updateVisibleRows()

        val anchor = if (followCaret) session.cursor().line else firstLine
        loadViewport(rows, anchor)

        if (followCaret) {
            // S4: after an edit, a caret move or a size change the caret is scrolled into
            // view with the smallest possible movement.
            scrollY =
                EditorScrollGeometry.scrollYToRevealRow(
                    row = anchor,
                    lineHeight = lineHeight,
                    scrollY = scrollY,
                    contentHeight = contentHeight(),
                )
            clampScrollOffsets()
            revealCaretHorizontally()
            clampScrollOffsets()
        }
        invalidate()
    }

    private fun updateVisibleRows(): Int {
        val rows = visibleRowsFor(height)
        ensureCacheCapacity(rows)
        visibleRows = rows
        return rows
    }

    /**
     * Fetches [rows] lines around [anchorLine] (one more screen on each side) and lays
     * them out. Updates [lineCount] and the caret/composing geometry for the snapshot.
     */
    private fun loadViewport(
        rows: Int,
        anchorLine: Int,
    ) {
        val requestFirst = (anchorLine - rows).coerceAtLeast(0)
        val viewport = session.viewport(requestFirst, minOf(rows * SCREENS_LAID_OUT, MAX_VIEWPORT_LINES))
        viewportSnapshot = viewport
        laidOutSlices = viewport.lines
        layoutFirstLine = viewport.firstLine
        lineCount = viewport.lineCount

        if (viewport.version != reportedVersion) {
            reportedVersion = viewport.version
            onVersionChanged(viewport.version)
        }

        layoutAllLines()
        updateCaretGeometry()
        clampScrollOffsets()

        if (firstViewportStartedNanos != 0L) {
            val elapsedMillis = (System.nanoTime() - firstViewportStartedNanos) / NANOS_PER_MILLI
            Log.i(LOG_TAG, "first viewport after open: ${elapsedMillis}ms")
            firstViewportStartedNanos = 0
        }
    }

    /**
     * Lays out [laidOutSlices] with the current [layoutWindowPx] and keeps
     * [maxLineWidth] monotonic for the current version (Q-T15-2).
     */
    private fun layoutAllLines() {
        val snapshot = viewportSnapshot ?: return
        val window = computeLayoutWindowPx()
        if (window != layoutWindowPx) {
            layoutWindowPx = window
            layoutCache.invalidateAll()
        }
        if (laidOutVersion != snapshot.version) {
            laidOutVersion = snapshot.version
            maxLineWidth = 0f
        }
        var widest = maxLineWidth
        val layouts = ArrayList<LaidOutLine>(laidOutSlices.size)
        for (slice in laidOutSlices) {
            val line = layoutCache.layoutFor(documentId, snapshot.version, fontConfig, slice, window)
            if (line.width > widest) widest = line.width
            layouts += line
        }
        maxLineWidth = widest
        laidOutLines = layouts
    }

    private fun updateCaretGeometry() {
        val snapshot = viewportSnapshot ?: return
        caretRect = caretRect(snapshot)
        composingUnderlines = composingUnderlines(snapshot)
    }

    private fun visibleRowsFor(height: Int): Int = ((height - paddingTop - paddingBottom).toFloat() / lineHeight).toInt().coerceAtLeast(1)

    private fun ensureCacheCapacity(rows: Int) {
        layoutCache.ensureCapacity(EditorLineLayoutCache.capacityFor(rows))
    }

    /** Line drawn at the top of the viewport; `0` after a document is opened (V9). */
    val firstVisibleLine: Int
        get() = firstLine

    /** Image-space x offset used by tests and the accessibility scroll description. */
    internal val scrollXPixels: Float
        get() = scrollX

    /** Image-space y offset used by tests. */
    internal val scrollYPixels: Float
        get() = scrollY

    /**
     * Test hook (T-0014): drawable text of [lineIndex] when it is currently drawn.
     *
     * Returns `null` for lines outside the drawn rows (including prefetch lines above or
     * below the viewport), so tests can assert both the drawn content and the range.
     */
    internal fun visibleTextAt(lineIndex: Int): String? {
        if (lineIndex < firstLine || lineIndex >= firstLine + visibleRows + 1) return null
        return layoutAt(lineIndex)?.displayText
    }

    /** Test hook (T-0014): number of cached line layouts. */
    internal fun cachedLayoutCount(): Int = layoutCache.size

    /** Test hook (T-0015): tap without synthesizing a MotionEvent. */
    internal fun moveCaretForTest(
        x: Float,
        y: Float,
    ) {
        moveCaretTo(x, y)
    }

    /** Test hook (T-0015): scroll without synthesizing a MotionEvent. */
    internal fun scrollContentForTest(
        dx: Float,
        dy: Float,
    ) {
        scrollContentBy(dx, dy)
    }

    /** Test hook (T-0015): maximum horizontal offset. */
    internal fun maxScrollXPixels(): Float = maxScrollX()

    /** Test hook (T-0015): maximum vertical offset. */
    internal fun maxScrollYPixels(): Float = maxScrollY()

    /** Test hook (T-0015): baseline-to-baseline distance in px. */
    internal fun lineHeightPx(): Float = lineHeight

    /** Test hook (T-0015): caret x in view coordinates, or `null` when not loaded. */
    internal fun caretScreenXPixels(): Float? = caretRect?.let { it.left + caretInset - scrollX }

    /**
     * Resets rendering and the IME after the session replaced its document (T-0012).
     *
     * The composing range, the surrounding-text context and the selection all become
     * stale when `open` swaps the text, hence `restartInput` (Q-T12-6).
     */
    fun onDocumentOpened() {
        firstViewportStartedNanos = System.nanoTime()
        inputHandler.finishComposingText()
        scrollX = 0f
        scrollY = 0f
        lineCount = 1
        maxLineWidth = 0f
        layoutWindowPx = 0f
        laidOutVersion = -1
        viewportSnapshot = null
        laidOutSlices = emptyList()
        laidOutLines = emptyList()
        layoutFirstLine = 0
        layoutCache.invalidateAll()
        refreshViewport()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.restartInput(this)
    }

    /**
     * Points this view at the document identified by [documentId], resetting the
     * viewport and the layout cache when it differs from the previous one (T-0012).
     *
     * A save-as keeps the same id (T-0013): the text did not change, so both the
     * viewport and the cached layouts stay valid.
     */
    fun showDocument(documentId: Long) {
        if (this.documentId == documentId) return
        this.documentId = documentId
        onDocumentOpened()
    }

    /**
     * Drops every cached line layout and redraws; T-0021 calls this after a font change.
     *
     * The cache key already contains the [FontConfig], so a new font would also miss;
     * clearing here releases the old geometry instead of waiting for eviction.
     */
    fun invalidateAll() {
        layoutCache.invalidateAll()
        layoutWindowPx = 0f
        maxLineWidth = 0f
        refreshViewport()
    }

    private val firstLine: Int
        get() = EditorScrollGeometry.firstLineFor(scrollY, lineHeight, lineCount)

    private fun caretRect(viewport: EditorViewportSnapshot): RectF? {
        val cached = layoutAt(viewport.cursor.line) ?: return null
        val textIndex = Utf8Offsets.byteToUtf16Index(cached.text, viewport.cursor.columnBytes)
        val line = layoutForCaret(cached)
        val x = paddingLeft + line.xForTextIndex(textIndex)
        val top = lineTop(line.index)
        return RectF(
            x - caretInset,
            top,
            x - caretInset + caretWidth,
            top + lineHeight,
        )
    }

    /**
     * Length of the caret line far enough to place the caret (S4).
     *
     * A line truncated by the horizontal layout window reports its width at the window
     * edge, which would pin the caret there and stop the viewport from scrolling to the
     * real column. The caret line is therefore measured once up to the layout cap; only
     * this one line pays the cost, only while it is truncated, and the result is not
     * cached. The extra width also raises `maxLineWidth` so the horizontal scroll range
     * can reach the caret.
     */
    private fun layoutForCaret(cached: LaidOutLine): LaidOutLine {
        if (!cached.truncated) return cached
        val slice = laidOutSlices.getOrNull(cached.index - layoutFirstLine) ?: return cached
        val measured = layoutBuilder.layout(slice, MAX_LINE_LAYOUT_WINDOW_PX)
        if (measured.width > maxLineWidth) maxLineWidth = measured.width
        return measured
    }

    /** Cached layout of [lineIndex], or `null` when it is outside the prefetch window. */
    private fun layoutAt(lineIndex: Int): LaidOutLine? = laidOutLines.getOrNull(lineIndex - layoutFirstLine)

    /** Top edge of [lineIndex] in content coordinates (before the scroll translation). */
    private fun lineTop(lineIndex: Int): Float = paddingTop + lineIndex * lineHeight

    private fun composingUnderlines(viewport: EditorViewportSnapshot): List<ComposingUnderline> {
        val start = inputHandler.composingStartByte
        val end = inputHandler.composingEndByte
        if (start < 0 || end <= start) return emptyList()
        val result = ArrayList<ComposingUnderline>(1)
        val slices = viewport.lines
        for (position in slices.indices) {
            val slice = slices[position]
            // Lines are contiguous and the terminator is one byte, so the next line's
            // startByte - 1 is the last byte of this line.
            val lineEndByte = if (position + 1 < slices.size) slices[position + 1].startByte - 1 else Int.MAX_VALUE
            if (end <= slice.startByte || start > lineEndByte) continue
            val line = layoutAt(slice.index) ?: continue
            val spanStart = maxOf(start, slice.startByte) - slice.startByte
            val spanEnd = minOf(end, lineEndByte) - slice.startByte
            val startIndex = Utf8Offsets.byteToUtf16Index(line.text, spanStart)
            val endIndex = Utf8Offsets.byteToUtf16Index(line.text, spanEnd)
            if (endIndex <= startIndex) continue
            result +=
                ComposingUnderline(
                    startX = paddingLeft + line.xForTextIndex(startIndex),
                    endX = paddingLeft + line.xForTextIndex(endIndex),
                    y = lineTop(line.index) + lineHeight - dp(1f),
                )
        }
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(colors.background)
        val baselineOffset = -textPaint.fontMetrics.ascent
        // Content coordinates are translated once per frame; nothing is allocated.
        canvas.save()
        canvas.translate(-scrollX, -scrollY)
        // Only fully visible rows are drawn; prefetch lines are laid out, not painted.
        val start = maxOf(firstLine, layoutFirstLine)
        val endExclusive = minOf(firstLine + visibleRows + 1, layoutFirstLine + laidOutLines.size)
        for (lineIndex in start until endExclusive) {
            val line = laidOutLines[lineIndex - layoutFirstLine]
            if (line.displayText.isEmpty()) continue
            canvas.drawText(line.displayText, paddingLeft.toFloat(), lineTop(lineIndex) + baselineOffset, textPaint)
        }
        for (underline in composingUnderlines) {
            canvas.drawLine(underline.startX, underline.y, underline.endX, underline.y, composingPaint)
        }
        caretRect?.let { canvas.drawRect(it, caretPaint) }
        canvas.restore()
        drawScrollBars(canvas)
    }

    private class ComposingUnderline(
        val startX: Float,
        val endX: Float,
        val y: Float,
    )

    // endregion

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_TEXT_SIZE_SP = 14f
        const val MAX_CONTEXT_UNITS = 65_536
        const val LOG_TAG = "rcode-editor"
        const val NANOS_PER_MILLI = 1_000_000L

        /** Visible + one prefetch screen above and below (Q-T14-2). */
        const val SCREENS_LAID_OUT = 3

        /** Mirrors the Rust `viewport` clamp (T-0011) so one call never asks for more. */
        const val MAX_VIEWPORT_LINES = 10_000

        /** Q-T15-1: horizontal window grows in these steps while dragging. */
        const val SCROLL_WINDOW_BUCKET_PX = 512f

        /** Q-T15-1: a very long line is never measured past this window. */
        const val MAX_LINE_LAYOUT_WINDOW_PX = 100_000f

        /** Scrollbar geometry (issue 3): thin bar, wide invisible touch target. */
        const val SCROLLBAR_THICKNESS_DP = 4f
        const val SCROLLBAR_TOUCH_WIDTH_DP = 24f
        const val SCROLLBAR_MIN_LENGTH_DP = 24f
        const val SCROLLBAR_INSET_DP = 2f
        const val SCROLLBAR_ALPHA = 96

        const val SCROLLBAR_NONE = 0
        const val SCROLLBAR_VERTICAL = 1
        const val SCROLLBAR_HORIZONTAL = 2
    }
}
