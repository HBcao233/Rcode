package com.hbcao.rcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.DocumentFormat
import com.hbcao.rcode.editor.DocumentOpenException
import com.hbcao.rcode.editor.EditorInputHandler
import com.hbcao.rcode.editor.LineEnding
import com.hbcao.rcode.editor.UnsupportedEncodingKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks for the M1.0 editing session through UniFFI on a device.
 * Complements the Rust unit tests and the JVM `EditorInputHandlerTest`.
 */
@RunWith(AndroidJUnit4::class)
class EditorSessionInstrumentedTest {
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

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

    @Test
    fun openDecodesBomAndCrlfAndStoresTheFormat() {
        val session = UniffiEditorSession()
        try {
            val before = session.version()
            val bytes = utf8Bom + "alpha\r\nbeta\r\n".encodeToByteArray()

            val opened = session.open(bytes)

            assertTrue(opened.version > before)
            assertEquals(opened.version, session.version())
            assertEquals(3, opened.lineCount)
            assertEquals(DocumentFormat(bom = true, lineEnding = LineEnding.CRLF), opened.format)
            assertEquals(opened.format, session.format())
            assertEquals(0, opened.cursor.byteOffset)
            assertEquals(0, opened.cursor.line)
            assertEquals(0, session.cursorUtf16Offset())
            assertFalse(session.canUndo())
            assertFalse(session.canRedo())

            val viewport = session.viewport(0, 10)
            assertEquals(opened.version, viewport.version)
            assertEquals(listOf("alpha", "beta", ""), viewport.lines.map { it.text })
            assertEquals(listOf(0, 6, 11), viewport.lines.map { it.startByte })
            assertArrayEquals(bytes, session.encode(session.format()).bytes)
        } finally {
            session.close()
        }
    }

    @Test
    fun openThenEditUndoAndViewport() {
        val session = UniffiEditorSession()
        try {
            val opened = session.open("one\ntwo\n".encodeToByteArray())
            assertEquals(3, opened.lineCount)

            session.moveCursor(4) // start of line 1
            session.insertText("X")
            val edited = session.version()
            assertEquals("one\nXtwo\n", session.text())
            assertEquals(5, session.cursor().byteOffset)

            val undone = session.undo()
            assertNotNull(undone)
            assertTrue(undone!! > edited)
            assertEquals("one\ntwo\n", session.text())
            assertEquals(4, session.cursor().byteOffset)

            val viewport = session.viewport(0, 10)
            assertEquals(undone, viewport.version)
            assertEquals(listOf("one", "two", ""), viewport.lines.map { it.text })
            assertEquals(4, viewport.cursor.byteOffset)
            assertEquals(1, viewport.cursor.line)
        } finally {
            session.close()
        }
    }

    @Test
    fun openDiscardsTheHistoryOfThePreviousDocument() {
        val session = UniffiEditorSession()
        try {
            session.insertText("draft")
            val draftVersion = session.version()
            assertTrue(session.canUndo())

            val opened = session.open("loaded".encodeToByteArray())

            assertTrue(opened.version > draftVersion)
            assertFalse(session.canUndo())
            assertFalse(session.canRedo())
            assertNull(session.undo())
            assertEquals("loaded", session.text())
        } finally {
            session.close()
        }
    }

    @Test
    fun failedOpenKeepsTheDocumentAndIsReported() {
        val session = UniffiEditorSession()
        try {
            session.open(utf8Bom + "keep\r\n".encodeToByteArray())
            session.insertText("!")
            session.endComposition()
            session.moveCursor(2)

            val text = session.text()
            val format = session.format()
            val cursor = session.cursor()
            val version = session.version()
            assertTrue(session.canUndo())

            val notUtf8 =
                assertThrows(DocumentOpenException.NotUtf8::class.java) {
                    session.open(byteArrayOf(0xC3.toByte()))
                }
            assertEquals(0, notUtf8.offset)

            val utf16 =
                assertThrows(DocumentOpenException.UnsupportedEncoding::class.java) {
                    session.open(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0x00))
                }
            assertEquals(UnsupportedEncodingKind.Utf16Le, utf16.kind)

            val binary =
                assertThrows(DocumentOpenException.UnsupportedEncoding::class.java) {
                    session.open("ab\u0000cd".encodeToByteArray())
                }
            assertEquals(UnsupportedEncodingKind.Binary(nulOffset = 2), binary.kind)

            assertEquals(text, session.text())
            assertEquals(format, session.format())
            assertEquals(cursor, session.cursor())
            assertEquals(version, session.version())
            assertTrue(session.canUndo())
        } finally {
            session.close()
        }
    }

    @Test
    fun repeatedOpenBumpsTheVersionAndEmptyFileHasOneLine() {
        val session = UniffiEditorSession()
        try {
            val first = session.open("same".encodeToByteArray())
            val second = session.open("same".encodeToByteArray())
            assertTrue(second.version > first.version)

            val empty = session.open(ByteArray(0))
            assertTrue(empty.version > second.version)
            assertEquals(1, empty.lineCount)
            assertEquals(DocumentFormat(bom = false, lineEnding = LineEnding.LF), empty.format)

            val viewport = session.viewport(0, 10)
            assertEquals(1, viewport.lineCount)
            assertEquals("", viewport.lines.single().text)
            assertEquals(0, viewport.cursor.byteOffset)
        } finally {
            session.close()
        }
    }
}
