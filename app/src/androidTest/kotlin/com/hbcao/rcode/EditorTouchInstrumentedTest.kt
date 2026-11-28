package com.hbcao.rcode

import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.DocumentFormat
import com.hbcao.rcode.editor.EditorCursorState
import com.hbcao.rcode.editor.EditorSessionApi
import com.hbcao.rcode.editor.EditorView
import com.hbcao.rcode.editor.EditorViewportSnapshot
import com.hbcao.rcode.editor.EncodedDocument
import com.hbcao.rcode.editor.OpenedDocumentState
import com.hbcao.rcode.ui.theme.EditorColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-0015 on a device: tap-to-caret, drag-without-caret-move, scroll clamping and the
 * "no FFI while scrolling inside the loaded window" rule.
 *
 * The test drives the view through its internal hooks instead of synthesized
 * `MotionEvent`s, so it stays deterministic; the physical gestures (S1–S4) are part of
 * the manual checklist in the task file.
 */
@RunWith(AndroidJUnit4::class)
class EditorTouchInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun tapPlacesTheCaretOnTheClickedLine() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, document(3))
                assertEquals(0, session.cursor().byteOffset)

                // Tapping the right-hand blank area of line 1 puts the caret at its end.
                view.moveCaretForTest(50_000f, view.lineHeightPx() * 1.5f)
                assertEquals("line 1 is 11 bytes", 11 + LINE_LENGTH, session.cursor().byteOffset)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun tapAfterScrollingUsesTheScrollOffset() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, document(200))
                val lineHeight = view.lineHeightPx()

                view.scrollContentForTest(0f, lineHeight * 3f)
                assertTrue("expected a vertical scroll", view.scrollYPixels > 0f)

                // The same screen position now points at line 3.
                view.moveCaretForTest(0f, lineHeight * 0.5f)
                assertEquals(3 * (LINE_LENGTH + 1), session.cursor().byteOffset)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun tapOnTheBlankAreaMovesToTheDocumentEnd() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, document(2))

                view.moveCaretForTest(0f, (VIEW_HEIGHT - 10).toFloat())
                assertEquals(session.text().length, session.cursor().byteOffset)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun draggingDoesNotMoveTheCaret() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, document(200))
                session.moveCursor(5)
                val before = session.cursor().byteOffset

                view.scrollContentForTest(0f, view.lineHeightPx() * 5f)

                assertTrue("expected a vertical scroll", view.scrollYPixels > 0f)
                assertEquals(before, session.cursor().byteOffset)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun scrollOffsetsAreClampedToTheDocument() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, document(200))

                view.scrollContentForTest(0f, 1_000_000f)
                assertEquals(view.maxScrollYPixels(), view.scrollYPixels, 0f)
                assertTrue(view.scrollYPixels > 0f)

                view.scrollContentForTest(-1_000_000f, -1_000_000f)
                assertEquals(0f, view.scrollXPixels, 0f)
                assertEquals(0f, view.scrollYPixels, 0f)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun horizontalScrollRevealsALongLine() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, "x".repeat(3_000).encodeToByteArray())
                assertTrue("a long line must be scrollable", view.maxScrollXPixels() > 0f)

                // Repeated drag frames grow the layout window in buckets (Q-T15-1).
                repeat(20) { view.scrollContentForTest(400f, 0f) }
                assertTrue("expected a horizontal scroll", view.scrollXPixels > 1_000f)

                // A tap near the right edge now lands well inside the line.
                view.moveCaretForTest((VIEW_WIDTH - 10).toFloat(), view.lineHeightPx() * 0.5f)
                assertTrue(session.cursor().byteOffset > 0)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun caretIsScrolledIntoViewHorizontallyOnALongLine() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, "x".repeat(3_000).encodeToByteArray())
                session.moveCursor(2_500)
                view.refreshViewport()

                assertTrue("expected a horizontal scroll to the caret", view.scrollXPixels > 0f)
                val caretX = view.caretScreenXPixels()
                assertNotNull("the caret line must be loaded", caretX)
                assertTrue(
                    "caret must be inside the view but was $caretX",
                    caretX!! >= 0f && caretX <= VIEW_WIDTH.toFloat(),
                )
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun movingToTheEndOfALongLineKeepsTheLinePainted() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val long = "x".repeat(1_000)
                val view = openSessionView(session, (long + "\ny").encodeToByteArray())

                // Caret at the start of line 1: no horizontal scroll yet.
                session.moveCursor(long.length + 1)
                view.refreshViewport()
                assertEquals(0f, view.scrollXPixels, 0f)

                // Left at a line start jumps to the end of the long previous line.
                session.moveCursorHorizontal(-1)
                view.refreshViewport()
                assertEquals(long.length, session.cursor().byteOffset)
                assertTrue("expected a horizontal scroll", view.scrollXPixels > 0f)

                // Regression: the new window must be laid out, otherwise only the
                // truncated prefix of the line is painted (the reported missing text).
                assertEquals(long, view.visibleTextAt(0))
                val caretX = view.caretScreenXPixels()
                assertNotNull("the caret line must be loaded", caretX)
                assertTrue(caretX!! in 0f..VIEW_WIDTH.toFloat())
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun scrollingPastTheEndShowsHalfAScreenOfBlank() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val lines = 200
                val view = openSessionView(session, document(lines))
                val overflow = lines * view.lineHeightPx() - VIEW_HEIGHT
                assertEquals(overflow + VIEW_HEIGHT / 2f, view.maxScrollYPixels(), 1f)

                view.scrollContentForTest(0f, 1_000_000f)
                assertEquals(view.maxScrollYPixels(), view.scrollYPixels, 0f)
                assertEquals("0123456789", view.visibleTextAt(lines - 1))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun scrollingInsideTheLoadedWindowDoesNotCallTheSession() {
        val session = CountingSession(UniffiEditorSession())
        try {
            instrumentation.runOnMainSync {
                val view = openSessionView(session, document(200))
                val lineHeight = view.lineHeightPx()
                val callsBefore = session.viewportCalls

                // One screen of drag stays inside the two prefetch screens.
                view.scrollContentForTest(0f, lineHeight * 10f)
                assertEquals(callsBefore, session.viewportCalls)

                // Leaving the prefetch window costs exactly one `viewport` call.
                view.scrollContentForTest(0f, lineHeight * 300f)
                assertEquals(callsBefore + 1, session.viewportCalls)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun scrollingDoesNotFocusTheEditorButTappingDoes() {
        val session = UniffiEditorSession()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                session.open(document(200))
                val view = EditorView(activity, session, colors())
                activity.setContentView(view)
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                view.refreshViewport()
                view.clearFocus()
                assertFalse("precondition: the editor is not focused", view.isFocused)

                // A vertical drag: ACTION_DOWN, a move beyond touch slop, ACTION_UP.
                dispatch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
                dispatch(view, MotionEvent.ACTION_MOVE, 100f, 500f)
                dispatch(view, MotionEvent.ACTION_UP, 100f, 500f)
                assertFalse("a scroll gesture must not focus the editor", view.isFocused)

                // A tap at the same point focuses it and asks for the keyboard.
                dispatch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
                dispatch(view, MotionEvent.ACTION_UP, 100f, 100f)
                assertTrue("a tap must focus the editor", view.isFocused)
            }
        } finally {
            scenario.close()
            session.close()
        }
    }

    private fun dispatch(
        view: View,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun openSessionView(
        session: EditorSessionApi,
        bytes: ByteArray,
    ): EditorView {
        session.open(bytes)
        val view = EditorView(instrumentation.targetContext, session, colors())
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
        view.refreshViewport()
        return view
    }

    private fun document(lines: Int): ByteArray = (0 until lines).joinToString("\n") { "0123456789" }.encodeToByteArray()

    private fun colors() =
        EditorColors(
            background = Color.WHITE,
            text = Color.BLACK,
            caret = Color.BLACK,
            composingUnderline = Color.BLACK,
        )

    /** Counts the calls that would run on the UI thread, like T-0014's test helper. */
    private class CountingSession(
        private val delegate: EditorSessionApi,
    ) : EditorSessionApi by delegate {
        var viewportCalls = 0
            private set

        override fun open(bytes: ByteArray): OpenedDocumentState = delegate.open(bytes)

        override fun viewport(
            firstLine: Int,
            maxLines: Int,
        ): EditorViewportSnapshot {
            viewportCalls += 1
            return delegate.viewport(firstLine, maxLines)
        }

        override fun cursor(): EditorCursorState = delegate.cursor()

        override fun version(): Long = delegate.version()

        override fun format(): DocumentFormat = delegate.format()

        override fun encode(format: DocumentFormat): EncodedDocument = delegate.encode(format)
    }

    private companion object {
        const val VIEW_WIDTH = 1_080
        const val VIEW_HEIGHT = 600

        /** "line -N-" is 10 bytes. */
        const val LINE_LENGTH = 10
    }
}
