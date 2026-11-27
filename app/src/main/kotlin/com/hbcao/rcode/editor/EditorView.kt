package com.hbcao.rcode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.hbcao.rcode.ui.theme.EditorColors

/**
 * Custom text editor view for M1.0.
 *
 * Text lives in the Rust [EditorSessionApi]; this view only caches the visible lines
 * and the caret geometry for the current document version (see ADR-0002). Drawing is
 * pure Canvas work over that cache, so `onDraw` performs no FFI calls and allocates
 * nothing per frame.
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

    /** Measures advances once per text range; shared by every line of this view. */
    private val layoutBuilder = LineLayoutBuilder(PaintLineMeasurer(textPaint))

    /** Laid-out lines for the current document/version/font; see [EditorLineLayoutCache]. */
    private var layoutCache = EditorLineLayoutCache(layoutBuilder, EditorLineLayoutCache.capacityFor(1))

    /** First line currently drawn at the top of the viewport. */
    private var firstLine: Int = 0

    /** Number of fully visible rows; the prefetch window is one screen on each side. */
    private var visibleRows: Int = 1

    /** Line index of `laidOutLines.first()`; lines before it are prefetch, not drawn. */
    private var layoutFirstLine: Int = 0

    /** Document shown by this view; a change resets the viewport and drops layouts (T-0012). */
    private var documentId: Long = 0

    private var reportedVersion: Long = -1
    private var firstViewportStartedNanos: Long = 0

    private var laidOutLines: List<LaidOutLine> = emptyList()
    private var caretRect: RectF? = null
    private var composingUnderlines: List<ComposingUnderline> = emptyList()

    /** Everything a line layout depends on besides the text (cache key part, T-0014). */
    private val fontConfig: FontConfig
        get() = FontConfig(System.identityHashCode(textPaint.typeface), textPaint.textSize, lineHeight)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(colors.background)
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!isFocused) {
                requestFocus()
            }
            if (inputHandler.isComposing) {
                inputHandler.finishComposingText()
                refreshViewport()
            }
            showKeyboard()
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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

    // region rendering

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // A size change alters the x window every line was laid out for, and the number
        // of rows the cache has to cover, so start with an empty cache (T-0014).
        visibleRows = visibleRowsFor(height)
        layoutCache = EditorLineLayoutCache(layoutBuilder, EditorLineLayoutCache.capacityFor(visibleRows))
        refreshViewport()
    }

    /**
     * Reloads the visible lines from the Rust session. Called after edits and size
     * changes, never from `onDraw`.
     *
     * One `viewport` call fetches the visible rows plus one prefetch screen above and
     * below (Q-T14-2); each row is laid out once per document version and font (T-0014).
     */
    fun refreshViewport() {
        if (width <= 0 || height <= 0) return
        val contentHeight = height - paddingTop - paddingBottom
        if (contentHeight <= 0) return
        val rows = visibleRowsFor(height)
        ensureCacheCapacity(rows)
        visibleRows = rows

        val cursor = session.cursor()
        if (cursor.line < firstLine) {
            firstLine = cursor.line
        } else if (cursor.line >= firstLine + rows) {
            firstLine = cursor.line - rows + 1
        }
        firstLine = firstLine.coerceAtLeast(0)

        val requestFirst = (firstLine - rows).coerceAtLeast(0)
        val viewport = session.viewport(requestFirst, minOf(rows * SCREENS_LAID_OUT, MAX_VIEWPORT_LINES))
        firstLine = firstLine.coerceAtMost((viewport.lineCount - 1).coerceAtLeast(0))

        if (viewport.version != reportedVersion) {
            reportedVersion = viewport.version
            onVersionChanged(viewport.version)
        }

        // `maxWidthPx` bounds the layout work of a very long line (V6, Q-T14-5); the
        // extra line height keeps the character under the right edge measurable.
        val maxWidthPx = (width - paddingLeft - paddingRight).toFloat() + lineHeight
        val font = fontConfig
        laidOutLines =
            viewport.lines.map { line -> layoutCache.layoutFor(documentId, viewport.version, font, line, maxWidthPx) }
        layoutFirstLine = viewport.firstLine
        caretRect = caretRect(viewport)
        composingUnderlines = composingUnderlines(viewport)
        invalidate()

        if (firstViewportStartedNanos != 0L) {
            val elapsedMillis = (System.nanoTime() - firstViewportStartedNanos) / NANOS_PER_MILLI
            Log.i(LOG_TAG, "first viewport after open: ${elapsedMillis}ms")
            firstViewportStartedNanos = 0
        }
    }

    private fun visibleRowsFor(height: Int): Int = ((height - paddingTop - paddingBottom).toFloat() / lineHeight).toInt().coerceAtLeast(1)

    private fun ensureCacheCapacity(rows: Int) {
        val needed = EditorLineLayoutCache.capacityFor(rows)
        if (layoutCache.capacity < needed) {
            layoutCache = EditorLineLayoutCache(layoutBuilder, needed)
        }
    }

    /** Line drawn at the top of the viewport; `0` after a document is opened (V9). */
    val firstVisibleLine: Int
        get() = firstLine

    /**
     * Test hook (T-0014): drawable text of [lineIndex] when it is currently drawn.
     *
     * Returns `null` for lines outside the visible rows, including prefetch lines above
     * or below the viewport, so tests can assert both the drawn content and the range.
     */
    internal fun visibleTextAt(lineIndex: Int): String? {
        if (lineIndex < firstLine || lineIndex >= firstLine + visibleRows) return null
        return layoutAt(lineIndex)?.displayText
    }

    /**
     * Resets rendering and the IME after the session replaced its document (T-0012).
     *
     * The composing range, the surrounding-text context and the selection all become
     * stale when `open` swaps the text, hence `restartInput` (Q-T12-6).
     */
    fun onDocumentOpened() {
        firstViewportStartedNanos = System.nanoTime()
        finishComposingText()
        firstLine = 0
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
        layoutCache.invalidateAll()
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
        refreshViewport()
    }

    private fun caretRect(viewport: EditorViewportSnapshot): RectF? {
        val line = layoutAt(viewport.cursor.line) ?: return null
        val textIndex = Utf8Offsets.byteToUtf16Index(line.text, viewport.cursor.columnBytes)
        val x = paddingLeft + line.xForTextIndex(textIndex)
        val top = lineTop(line.index)
        return RectF(
            x - caretInset,
            top,
            x - caretInset + caretWidth,
            top + lineHeight,
        )
    }

    /** Cached layout of [lineIndex], or `null` when it is outside the prefetch window. */
    private fun layoutAt(lineIndex: Int): LaidOutLine? = laidOutLines.getOrNull(lineIndex - layoutFirstLine)

    private fun lineTop(lineIndex: Int): Float = paddingTop + (lineIndex - firstLine) * lineHeight

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
        // Only fully visible rows are drawn; prefetch lines are laid out, not painted.
        val start = maxOf(firstLine, layoutFirstLine)
        val endExclusive = minOf(firstLine + visibleRows, layoutFirstLine + laidOutLines.size)
        for (lineIndex in start until endExclusive) {
            val line = laidOutLines[lineIndex - layoutFirstLine]
            if (line.displayText.isEmpty()) continue
            canvas.drawText(line.displayText, paddingLeft.toFloat(), lineTop(lineIndex) + baselineOffset, textPaint)
        }
        for (underline in composingUnderlines) {
            canvas.drawLine(underline.startX, underline.y, underline.endX, underline.y, composingPaint)
        }
        caretRect?.let { canvas.drawRect(it, caretPaint) }
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
    }
}
