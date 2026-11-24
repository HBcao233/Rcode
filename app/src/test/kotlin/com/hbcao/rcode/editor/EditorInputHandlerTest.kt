package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputHandlerTest {
    @Test
    fun `setComposingText inserts and marks the composing span`() {
        val session = FakeEditorSession("ab")
        val handler = EditorInputHandler(session)

        handler.setComposingText("n", 1)

        assertEquals("abn", session.text())
        assertTrue(handler.isComposing)
        assertEquals(2, handler.composingStartByte)
        assertEquals(3, handler.composingEndByte)
        assertEquals("abn", session.text())
    }

    @Test
    fun `setComposingText replaces only the previous composing span`() {
        val session = FakeEditorSession("ab")
        val handler = EditorInputHandler(session)

        handler.setComposingText("n", 1)
        handler.setComposingText("ni", 1)

        assertEquals("abni", session.text())
        assertEquals(2, handler.composingStartByte)
        assertEquals(4, handler.composingEndByte)
    }

    @Test
    fun `commitText replaces composing text and clears composing state`() {
        val session = FakeEditorSession("ab")
        val handler = EditorInputHandler(session)

        handler.setComposingText("ni", 1)
        handler.commitText("你", 1)

        assertEquals("ab你", session.text())
        assertFalse(handler.isComposing)
        assertEquals(session.text().length, session.cursorUtf16Offset())
    }

    @Test
    fun `finishComposingText keeps text and clears composing state`() {
        val session = FakeEditorSession("x")
        val handler = EditorInputHandler(session)

        handler.setComposingText("ni", 1)
        val version = session.version()
        handler.finishComposingText()

        assertEquals("xni", session.text())
        assertFalse(handler.isComposing)
        assertEquals(version, session.version())
    }

    @Test
    fun `commitText with newCursorPosition zero leaves the caret before the text`() {
        val session = FakeEditorSession("ab")
        val handler = EditorInputHandler(session)

        handler.commitText("XY", 0)

        assertEquals("abXY", session.text())
        assertEquals(2, session.cursor().byteOffset)
        assertEquals(2, session.cursorUtf16Offset())
    }

    @Test
    fun `commitText with newCursorPosition two places caret inside the text`() {
        val session = FakeEditorSession("")
        val handler = EditorInputHandler(session)

        handler.commitText("abc", 2)

        assertEquals("abc", session.text())
        assertEquals(2, session.cursorUtf16Offset())
    }

    @Test
    fun `committing CJK text uses byte offsets for the composing span`() {
        val session = FakeEditorSession("")
        val handler = EditorInputHandler(session)

        handler.setComposingText("你", 1)
        assertEquals(0, handler.composingStartByte)
        assertEquals(3, handler.composingEndByte)

        handler.setComposingText("你好", 1)
        assertEquals(0, handler.composingStartByte)
        assertEquals(6, handler.composingEndByte)
        assertEquals("你好", session.text())
    }

    @Test
    fun `deleteSurroundingText deletes UTF-16 code units before the caret`() {
        val session = FakeEditorSession("a🙂b")
        val handler = EditorInputHandler(session)

        handler.deleteSurroundingText(1, 0)
        assertEquals("a🙂", session.text())

        handler.deleteSurroundingText(2, 0)
        assertEquals("a", session.text())
    }

    @Test
    fun `deleteSurroundingText deletes CJK characters`() {
        val session = FakeEditorSession("a中")
        val handler = EditorInputHandler(session)

        handler.deleteSurroundingText(1, 0)

        assertEquals("a", session.text())
    }

    @Test
    fun `deleteSurroundingTextInCodePoints removes whole code points`() {
        val session = FakeEditorSession("a🙂b")
        val handler = EditorInputHandler(session)

        handler.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("a🙂", session.text())

        handler.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("a", session.text())
    }

    @Test
    fun `deleteBackward shrinks the composing span`() {
        val session = FakeEditorSession("")
        val handler = EditorInputHandler(session)

        handler.setComposingText("ni", 1)
        handler.deleteBackward()

        assertEquals("n", session.text())
        assertEquals(0, handler.composingStartByte)
        assertEquals(1, handler.composingEndByte)

        handler.deleteBackward()

        assertEquals("", session.text())
        assertEquals(0, handler.composingStartByte)
        assertEquals(0, handler.composingEndByte)
    }

    @Test
    fun `empty setComposingText keeps version and records an empty span`() {
        val session = FakeEditorSession("a")
        val handler = EditorInputHandler(session)
        val version = session.version()

        handler.setComposingText("", 1)

        assertEquals("a", session.text())
        assertEquals(version, session.version())
        assertTrue(handler.isComposing)
        assertEquals(1, handler.composingStartByte)
        assertEquals(1, handler.composingEndByte)
    }

    @Test
    fun `committing after arrow movement inserts at the new caret`() {
        val session = FakeEditorSession("ab")
        val handler = EditorInputHandler(session)
        session.moveCursor(0)

        handler.commitText("X", 1)

        assertEquals("Xab", session.text())
    }
}
