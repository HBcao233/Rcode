package com.hbcao.rcode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
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

    /** First line currently drawn at the top of the viewport. */
    private var firstLine: Int = 0

    private var renderedLines: List<RenderedLine> = emptyList()
    private var caretRect: RectF? = null
    private var composingUnderlines: List<ComposingUnderline> = emptyList()

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
    ): Boolean = performEditorKey(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = isEditorKey(keyCode) || super.onKeyUp(keyCode, event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!isFocused) {
                requestFocus()
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
        refreshViewport()
    }

    /**
     * Reloads the visible lines from the Rust session. Called after edits and size
     * changes, never from `onDraw`.
     */
    fun refreshViewport() {
        if (width <= 0 || height <= 0) return
        val contentHeight = height - paddingTop - paddingBottom
        if (contentHeight <= 0) return
        val visibleRows = (contentHeight.toFloat() / lineHeight).toInt().coerceAtLeast(1)

        val cursor = session.cursor()
        if (cursor.line < firstLine) {
            firstLine = cursor.line
        } else if (cursor.line >= firstLine + visibleRows) {
            firstLine = cursor.line - visibleRows + 1
        }
        firstLine = firstLine.coerceAtLeast(0)

        // One extra line below the viewport prefetches the next scroll position.
        val viewport = session.viewport(firstLine, visibleRows + 1)
        firstLine = viewport.firstLine

        val left = paddingLeft.toFloat()
        val lines =
            viewport.lines.map { line ->
                val byteLength = Utf8Offsets.utf8Length(line.text)
                RenderedLine(
                    index = line.index,
                    startByte = line.startByte,
                    endByte = line.startByte + byteLength,
                    text = line.text,
                    top = paddingTop + (line.index - viewport.firstLine) * lineHeight,
                )
            }
        renderedLines = lines
        caretRect = caretRect(viewport, lines, left)
        composingUnderlines = composingUnderlines(lines, left)
        invalidate()
    }

    private fun caretRect(
        viewport: EditorViewportSnapshot,
        lines: List<RenderedLine>,
        left: Float,
    ): RectF? {
        val line = lines.firstOrNull { it.index == viewport.cursor.line } ?: return null
        val prefixEnd = Utf8Offsets.byteToUtf16Index(line.text, viewport.cursor.columnBytes)
        val x = left + textPaint.measureText(line.text, 0, prefixEnd)
        return RectF(
            x - caretInset,
            line.top,
            x - caretInset + caretWidth,
            line.top + lineHeight,
        )
    }

    private fun composingUnderlines(
        lines: List<RenderedLine>,
        left: Float,
    ): List<ComposingUnderline> {
        val start = inputHandler.composingStartByte
        val end = inputHandler.composingEndByte
        if (start < 0 || end <= start) return emptyList()
        return lines.mapNotNull { line ->
            val spanStart = maxOf(start, line.startByte)
            val spanEnd = minOf(end, line.endByte)
            if (spanStart >= spanEnd) return@mapNotNull null
            val prefixEnd = Utf8Offsets.byteToUtf16Index(line.text, spanStart - line.startByte)
            val spanEndIndex = Utf8Offsets.byteToUtf16Index(line.text, spanEnd - line.startByte)
            ComposingUnderline(
                startX = left + textPaint.measureText(line.text, 0, prefixEnd),
                endX = left + textPaint.measureText(line.text, 0, spanEndIndex),
                y = line.top + lineHeight - dp(1f),
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(colors.background)
        val baselineOffset = -textPaint.fontMetrics.ascent
        for (line in renderedLines) {
            if (line.text.isEmpty()) continue
            canvas.drawText(line.text, paddingLeft.toFloat(), line.top + baselineOffset, textPaint)
        }
        for (underline in composingUnderlines) {
            canvas.drawLine(underline.startX, underline.y, underline.endX, underline.y, composingPaint)
        }
        caretRect?.let { canvas.drawRect(it, caretPaint) }
    }

    private class RenderedLine(
        val index: Int,
        val startByte: Int,
        val endByte: Int,
        val text: String,
        val top: Float,
    )

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
    }
}
