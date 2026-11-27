package com.hbcao.rcode

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.EditorView
import com.hbcao.rcode.ui.theme.EditorColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** V9: replacing the document resets the viewport to the first line. */
@RunWith(AndroidJUnit4::class)
class EditorDocumentOpenInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun openingADocumentResetsTheViewport() {
        val session = UniffiEditorSession()
        var firstLineAfterOpen = -1
        var firstLineText = ""
        try {
            instrumentation.runOnMainSync {
                session.open(LONG_DOCUMENT.encodeToByteArray())
                val view = EditorView(instrumentation.targetContext, session, colors())
                view.layout(0, 0, 480, 240)

                session.moveCursor(LONG_DOCUMENT.encodeToByteArray().size - 2)
                view.refreshViewport()
                assertTrue("expected a scrolled viewport", view.firstVisibleLine > 0)

                session.open("first\nsecond\n".encodeToByteArray())
                view.showDocument(1)

                firstLineAfterOpen = view.firstVisibleLine
                val viewport = session.viewport(0, 1)
                firstLineText = viewport.lines[0].text
            }
        } finally {
            session.close()
        }

        assertEquals(0, firstLineAfterOpen)
        assertEquals("first", firstLineText)
    }

    private fun colors() =
        EditorColors(
            background = Color.WHITE,
            text = Color.BLACK,
            caret = Color.BLACK,
            composingUnderline = Color.BLACK,
        )

    private companion object {
        val LONG_DOCUMENT = (0 until 200).joinToString("\n") { "line $it" }
    }
}
