package com.hbcao.rcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-0014 on a device: first-frame measurement (A1 first data point) and drawing through
 * 200 adjacent viewports, with a session that counts every FFI call on the UI thread.
 *
 * The measured values are written to logcat (`rcode-editor-t14`) and to the task file's
 * handoff record; the device and its Android version are part of the report.
 */
@RunWith(AndroidJUnit4::class)
class EditorRenderingInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun tenMegabyteFirstFrameIsMeasured() {
        val bytes = syntheticDocument(TEN_MIB)
        var openNanos = 0L
        var layoutNanos = 0L
        var drawNanos = 0L
        var visibleText: String? = null
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                openNanos = measureNanos { session.open(bytes) }
                val view = EditorView(instrumentation.targetContext, session, colors())
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)

                layoutNanos = measureNanos { view.refreshViewport() }
                val canvas = Canvas(Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888))
                drawNanos = measureNanos { view.draw(canvas) }

                visibleText = view.visibleTextAt(0)
            }
        } finally {
            session.close()
        }

        Log.i(
            LOG_TAG,
            "A1 first frame: open=${openNanos / NANOS_PER_MILLI}ms" +
                " layout=${layoutNanos / NANOS_PER_MILLI}ms" +
                " draw=${drawNanos / NANOS_PER_MILLI}ms" +
                " bytes=${bytes.size}",
        )
        assertNotNull("the first line must be drawn", visibleText)
        assertTrue("open should take measurable time", openNanos > 0L)
        assertTrue("layout should take measurable time", layoutNanos > 0L)
        // Sanity bound only: A1's formal < 500 ms judgement belongs to T-0025.
        assertTrue(
            "first frame took too long: ${(openNanos + layoutNanos + drawNanos) / NANOS_PER_MILLI}ms",
            openNanos + layoutNanos + drawNanos < FIRST_FRAME_BUDGET_NANOS,
        )
    }

    @Test
    fun twoHundredAdjacentViewportsDrawTheCursorLine() {
        val document = (0 until 1_000).joinToString("\n") { "line $it\twith tab" }
        val session = CountingSession(UniffiEditorSession())
        try {
            instrumentation.runOnMainSync {
                session.open(document.encodeToByteArray())
                val view = EditorView(instrumentation.targetContext, session, colors())
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                view.refreshViewport()

                val canvas = Canvas(Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888))
                for (targetLine in 0 until 200) {
                    val startByte =
                        session
                            .viewport(targetLine, 1)
                            .lines
                            .first()
                            .startByte
                    session.moveCursor(startByte)
                    val viewportsBefore = session.viewportCalls
                    view.refreshViewport()
                    // V3: one `viewport` call per refresh, prefetch included.
                    assertEquals(1, session.viewportCalls - viewportsBefore)

                    // onDraw must not call into Rust (V3): count around the draw.
                    val callsBeforeDraw = session.ffiCalls
                    view.draw(canvas)
                    assertEquals("onDraw called the session", callsBeforeDraw, session.ffiCalls)

                    assertEquals(
                        "line $targetLine\twith tab".replace("\t", "    "),
                        view.visibleTextAt(targetLine),
                    )
                    // Only visible rows are drawn; a prefetch line above is null.
                    if (view.firstVisibleLine > 0) {
                        assertNull(view.visibleTextAt(view.firstVisibleLine - 1))
                    }
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun viewportBoundariesAreClamped() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                val view = EditorView(instrumentation.targetContext, session, colors())
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)

                // A document shorter than one screen never scrolls.
                session.open("only one line".encodeToByteArray())
                view.refreshViewport()
                assertEquals(0, view.firstVisibleLine)
                assertEquals("only one line", view.visibleTextAt(0))
                assertNull(view.visibleTextAt(1))

                // An empty document still has one (empty) line.
                session.open(ByteArray(0))
                view.showDocument(1)
                assertEquals(0, view.firstVisibleLine)
                assertEquals("", view.visibleTextAt(0))

                // Last screen: the cursor line is visible and nothing past it is drawn.
                session.open((0 until 500).joinToString("\n") { "l$it" }.encodeToByteArray())
                view.showDocument(2)
                val lastStart =
                    session
                        .viewport(499, 1)
                        .lines
                        .first()
                        .startByte
                session.moveCursor(lastStart)
                view.refreshViewport()
                assertTrue("expected a scrolled viewport", view.firstVisibleLine > 0)
                assertEquals("l499", view.visibleTextAt(499))
                assertNull(view.visibleTextAt(500))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun imeSizedHeightChangeKeepsTheLayoutCache() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                session.open((0 until 1_000).joinToString("\n") { "line $it" }.encodeToByteArray())
                val view = EditorView(instrumentation.targetContext, session, colors())
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                view.refreshViewport()
                val cachedBefore = view.cachedLayoutCount()
                assertTrue("expected prefetched layouts", cachedBefore > 0)

                // The IME opens: adjustResize shrinks the height, the width stays, so
                // the x window did not change and every layout is still valid.
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT / 2)
                view.refreshViewport()
                assertEquals(cachedBefore, view.cachedLayoutCount())

                // A width change (rotation / split screen) does change the x window.
                view.layout(0, 0, VIEW_WIDTH / 2, VIEW_HEIGHT / 2)
                view.refreshViewport()
                assertTrue(
                    "a width change should drop the old geometry",
                    view.cachedLayoutCount() < cachedBefore,
                )
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun openingAnotherDocumentDropsTheCachedLayout() {
        val session = UniffiEditorSession()
        try {
            instrumentation.runOnMainSync {
                session.open("first\nsecond\n".encodeToByteArray())
                val view = EditorView(instrumentation.targetContext, session, colors())
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                view.refreshViewport()
                assertEquals("first", view.visibleTextAt(0))

                // Same line index and (by chance) version numbers must not leak text
                // across documents: the new document id clears the cache.
                session.open("alpha\nbeta\n".encodeToByteArray())
                view.showDocument(1)

                assertEquals("alpha", view.visibleTextAt(0))
            }
        } finally {
            session.close()
        }
    }

    private fun colors() =
        EditorColors(
            background = Color.WHITE,
            text = Color.BLACK,
            caret = Color.BLACK,
            composingUnderline = Color.BLACK,
        )

    private inline fun measureNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    /**
     * Wraps the real session and counts every call the editor could make per frame, so a
     * test can prove that `onDraw` is pure cache reads (V3).
     */
    private class CountingSession(
        private val delegate: EditorSessionApi,
    ) : EditorSessionApi by delegate {
        var ffiCalls = 0
            private set

        var viewportCalls = 0
            private set

        override fun open(bytes: ByteArray): OpenedDocumentState = counted { delegate.open(bytes) }

        override fun viewport(
            firstLine: Int,
            maxLines: Int,
        ): EditorViewportSnapshot {
            viewportCalls += 1
            return counted { delegate.viewport(firstLine, maxLines) }
        }

        override fun cursor(): EditorCursorState = counted { delegate.cursor() }

        override fun version(): Long = counted { delegate.version() }

        override fun lineCount(): Int = counted { delegate.lineCount() }

        override fun format(): DocumentFormat = counted { delegate.format() }

        override fun encode(format: DocumentFormat): EncodedDocument = counted { delegate.encode(format) }

        private inline fun <T> counted(block: () -> T): T {
            ffiCalls += 1
            return block()
        }
    }

    private companion object {
        const val LOG_TAG = "rcode-editor-t14"
        const val NANOS_PER_MILLI = 1_000_000L
        const val TEN_MIB = 10 * 1024 * 1024
        const val VIEW_WIDTH = 1_080
        const val VIEW_HEIGHT = 1_920

        /** Generous sanity bound; T-0025 owns the formal A1 threshold. */
        const val FIRST_FRAME_BUDGET_NANOS = 5_000L * NANOS_PER_MILLI

        /**
         * Synthetic 10 MB document shaped like the Rust benchmarks: ~70% ASCII, ~25% CJK,
         * ~5% emoji, average line about 80 bytes.
         */
        fun syntheticDocument(targetBytes: Int): ByteArray {
            val ascii = "the quick brown fox jumps over the lazy dog 0123456789".encodeToByteArray()
            val cjk = "代码编辑器缓冲区渲染性能测试".encodeToByteArray()
            val emoji = "😀".encodeToByteArray()
            val output = ByteArray(targetBytes)
            var position = 0
            var lineBytes = 0
            while (position < targetBytes) {
                when {
                    lineBytes >= 80 -> {
                        output[position++] = '\n'.code.toByte()
                        lineBytes = 0
                    }

                    lineBytes % 20 == 19 && position + cjk.size <= targetBytes -> {
                        cjk.copyInto(output, position)
                        position += cjk.size
                        lineBytes += cjk.size
                    }

                    lineBytes % 40 == 39 && position + emoji.size <= targetBytes -> {
                        emoji.copyInto(output, position)
                        position += emoji.size
                        lineBytes += emoji.size
                    }

                    else -> {
                        val chunk = ascii.size.coerceAtMost(targetBytes - position)
                        ascii.copyInto(output, position, 0, chunk)
                        position += chunk
                        lineBytes += chunk
                    }
                }
            }
            return output
        }
    }
}
