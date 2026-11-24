package com.hbcao.rcode.editor

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * `InputConnection` for [EditorView], per ADR-0002.
 *
 * Every text mutation is forwarded to the Rust session through [EditorView]; this
 * class does not keep a text copy and does not build an `Editable`. Lengths coming
 * from the IME (`deleteSurroundingText`) are UTF-16 code units, handled by
 * [EditorInputHandler].
 */
internal class EditorInputConnection(
    private val editor: EditorView,
) : BaseInputConnection(editor, false) {
    override fun getEditable(): android.text.Editable? = null

    override fun commitText(
        text: CharSequence?,
        newCursorPosition: Int,
    ): Boolean {
        editor.commitText(text?.toString().orEmpty(), newCursorPosition)
        return true
    }

    override fun setComposingText(
        text: CharSequence?,
        newCursorPosition: Int,
    ): Boolean {
        editor.setComposingText(text?.toString().orEmpty(), newCursorPosition)
        return true
    }

    override fun finishComposingText(): Boolean {
        editor.finishComposingText()
        return true
    }

    override fun deleteSurroundingText(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean {
        editor.deleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean {
        editor.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        return true
    }

    override fun getTextBeforeCursor(
        length: Int,
        flags: Int,
    ): CharSequence = editor.textBeforeCursor(length)

    override fun getTextAfterCursor(
        length: Int,
        flags: Int,
    ): CharSequence = editor.textAfterCursor(length)

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun setSelection(
        start: Int,
        end: Int,
    ): Boolean = false

    override fun setComposingRegion(
        start: Int,
        end: Int,
    ): Boolean = false

    override fun sendKeyEvent(event: KeyEvent): Boolean = editor.handleImeKeyEvent(event)

    override fun beginBatchEdit(): Boolean = true

    override fun endBatchEdit(): Boolean = true

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun closeConnection() {
        super.closeConnection()
    }
}
