package com.hbcao.rcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.DocumentFormat
import com.hbcao.rcode.editor.EditorInputHandler
import com.hbcao.rcode.editor.LineEnding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks for the M1.0 editing session through UniFFI on a device.
 * Complements the Rust unit tests and the JVM `EditorInputHandlerTest`.
 */
@RunWith(AndroidJUnit4::class)
class EditorSessionInstrumentedTest {
    @Test
    fun editingSessionRoundTrip() {
        val session = UniffiEditorSession()
        try {
            session.insertText("hello ")
            session.insertText("中文🙂")
            assertEquals("hello 中文🙂", session.text())
            assertEquals(session.text().encodeToByteArray().size, session.cursor().byteOffset)
            assertEquals("hello 中文🙂".length, session.cursorUtf16Offset())

            val viewport = session.viewport(0, 10)
            assertEquals(1, viewport.lineCount)
            assertEquals("hello 中文🙂", viewport.lines[0].text)

            session.deleteBackward()
            assertEquals("hello 中文", session.text())
        } finally {
            session.close()
        }
    }

    @Test
    fun imeComposingRoundTrip() {
        val session = UniffiEditorSession()
        try {
            val handler = EditorInputHandler(session)
            handler.setComposingText("ni", 1)
            handler.setComposingText("nihao", 1)
            assertEquals("nihao", session.text())

            handler.commitText("你好", 1)

            assertEquals("你好", session.text())
            assertFalse(handler.isComposing)
            assertEquals(2, session.cursorUtf16Offset())

            // The whole pinyin sequence is a single undo step.
            assertTrue(session.canUndo())
            assertNotNull(session.undo())
            assertEquals("", session.text())
            assertFalse(session.canUndo())
        } finally {
            session.close()
        }
    }

    @Test
    fun undoRedoRoundTripThroughTheFfi() {
        val session = UniffiEditorSession()
        try {
            assertNull(session.undo())
            assertFalse(session.canUndo())
            assertFalse(session.canRedo())

            session.insertText("hello")
            session.endComposition()
            session.moveCursor(0)
            session.insertText("X")
            assertEquals("Xhello", session.text())
            session.moveCursor(3)

            val undoVersion = session.undo()
            assertNotNull(undoVersion)
            assertEquals("hello", session.text())
            assertEquals(0, session.cursor().byteOffset)
            assertTrue(session.canRedo())

            val redoVersion = session.redo()
            assertNotNull(redoVersion)
            assertEquals("Xhello", session.text())
            assertEquals(1, session.cursor().byteOffset)
            assertTrue(redoVersion!! > undoVersion!!)
        } finally {
            session.close()
        }
    }

    @Test
    fun clearHistoryAndCompositionBoundariesThroughTheFfi() {
        val session = UniffiEditorSession()
        try {
            session.beginComposition()
            session.replaceRange(0, 0, "ni")
            session.replaceRange(0, 2, "你好")
            session.endComposition()
            assertEquals("你好", session.text())
            assertTrue(session.canUndo())
            assertNotNull(session.undo())
            assertEquals("", session.text())

            session.insertText("keep")
            session.clearHistory()
            assertFalse(session.canUndo())
            assertFalse(session.canRedo())
            assertEquals("keep", session.text())
        } finally {
            session.close()
        }
    }

    @Test
    fun newlineCreatesSecondLine() {
        val session = UniffiEditorSession()
        try {
            session.insertText("first\nsecond")
            val viewport = session.viewport(0, 10)
            assertEquals(2, viewport.lineCount)
            assertEquals("first", viewport.lines[0].text)
            assertEquals("second", viewport.lines[1].text)
            assertEquals(1, session.cursor().line)
        } finally {
            session.close()
        }
    }

    @Test
    fun encodeMapsBomAndLineEndingAndKeepsTheVersion() {
        val session = UniffiEditorSession()
        try {
            session.insertText("a\nb\n")
            val version = session.version()

            val lf = session.encode(DocumentFormat(bom = false, lineEnding = LineEnding.LF))
            assertArrayEquals(
                byteArrayOf('a'.code.toByte(), '\n'.code.toByte(), 'b'.code.toByte(), '\n'.code.toByte()),
                lf.bytes,
            )
            assertEquals(version, lf.version)

            val crlf = session.encode(DocumentFormat(bom = true, lineEnding = LineEnding.CRLF))
            assertArrayEquals(
                byteArrayOf(
                    0xEF.toByte(),
                    0xBB.toByte(),
                    0xBF.toByte(),
                    'a'.code.toByte(),
                    '\r'.code.toByte(),
                    '\n'.code.toByte(),
                    'b'.code.toByte(),
                    '\r'.code.toByte(),
                    '\n'.code.toByte(),
                ),
                crlf.bytes,
            )
            assertEquals(version, crlf.version)

            // Encoding is read-only and the version identifies the encoded text.
            assertEquals(version, session.version())
            assertEquals("a\nb\n", session.text())
            session.insertText("later")
            val afterEdit = session.encode(DocumentFormat(bom = false, lineEnding = LineEnding.LF))
            assertTrue(afterEdit.version > crlf.version)
            assertArrayEquals("a\nb\nlater".encodeToByteArray(), afterEdit.bytes)
        } finally {
            session.close()
        }
    }
}
