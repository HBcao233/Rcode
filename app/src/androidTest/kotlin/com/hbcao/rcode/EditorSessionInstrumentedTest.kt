package com.hbcao.rcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.EditorInputHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
