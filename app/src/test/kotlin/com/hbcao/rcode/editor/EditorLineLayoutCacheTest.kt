package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * JVM tests for the layout cache (T-0014, V2): key composition, invalidation, eviction.
 */
class EditorLineLayoutCacheTest {
    private val font = FontConfig(typefaceId = 1, textSizePx = 14f, lineHeightPx = 18f)

    @Test
    fun sameDocumentVersionAndLineIsLaidOutOnce() {
        val measurer = CountingMeasurer()
        val cache = EditorLineLayoutCache(LineLayoutBuilder(measurer))

        val first = cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)
        val second = cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)

        assertSame(first, second)
        assertEquals(1, measurer.measureCalls)
        assertEquals(1, cache.size)
    }

    @Test
    fun aNewVersionRelaysOutOnlyTheRequestedLine() {
        val measurer = CountingMeasurer()
        val cache = EditorLineLayoutCache(LineLayoutBuilder(measurer))

        cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)
        cache.layoutFor(1, 1, font, line(1, "def"), 1_000f)
        val edited = cache.layoutFor(1, 2, font, line(0, "abc!"), 1_000f)

        assertEquals(3, measurer.measureCalls)
        assertEquals(3, cache.size)
        assertEquals("abc!", edited.displayText)
    }

    @Test
    fun anotherDocumentDoesNotReuseTheLayout() {
        val measurer = CountingMeasurer()
        val cache = EditorLineLayoutCache(LineLayoutBuilder(measurer))

        val first = cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)
        // Opening a second document bumps `documentId`, not necessarily the version.
        val second = cache.layoutFor(2, 1, font, line(0, "abc"), 1_000f)

        assertNotSame(first, second)
        assertEquals(2, measurer.measureCalls)
    }

    @Test
    fun aNewFontConfigDoesNotReuseTheLayout() {
        val measurer = CountingMeasurer()
        val cache = EditorLineLayoutCache(LineLayoutBuilder(measurer))

        cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)
        val larger = font.copy(textSizePx = 24f)
        cache.layoutFor(1, 1, larger, line(0, "abc"), 1_000f)

        assertEquals(2, measurer.measureCalls)
        assertEquals(2, cache.size)
    }

    @Test
    fun invalidateAllForcesARelayout() {
        val measurer = CountingMeasurer()
        val cache = EditorLineLayoutCache(LineLayoutBuilder(measurer))
        cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)

        cache.invalidateAll()
        assertEquals(0, cache.size)
        cache.layoutFor(1, 1, font, line(0, "abc"), 1_000f)

        assertEquals(2, measurer.measureCalls)
    }

    @Test
    fun theLeastRecentlyUsedLineIsEvictedPastCapacity() {
        val measurer = CountingMeasurer()
        val cache = EditorLineLayoutCache(LineLayoutBuilder(measurer), capacity = 2)

        val a = cache.layoutFor(1, 1, font, line(0, "a"), 1_000f)
        cache.layoutFor(1, 1, font, line(1, "b"), 1_000f)
        // Touch A so B becomes the least recently used entry.
        assertSame(a, cache.layoutFor(1, 1, font, line(0, "a"), 1_000f))
        cache.layoutFor(1, 1, font, line(2, "c"), 1_000f)

        assertEquals(2, cache.size)
        val callsBefore = measurer.measureCalls
        cache.layoutFor(1, 1, font, line(0, "a"), 1_000f)
        assertEquals("A should still be cached", callsBefore, measurer.measureCalls)
        cache.layoutFor(1, 1, font, line(1, "b"), 1_000f)
        assertEquals("B should have been evicted", callsBefore + 1, measurer.measureCalls)
    }

    @Test
    fun capacityCoversTheViewportAndBothPrefetchScreens() {
        assertEquals(EditorLineLayoutCache.MIN_ENTRIES, EditorLineLayoutCache.capacityFor(10))
        assertEquals(600, EditorLineLayoutCache.capacityFor(200))
    }

    private fun line(
        index: Int,
        text: String,
    ): EditorLineSlice = EditorLineSlice(index = index, startByte = index * 100, text = text)

    private class CountingMeasurer : LineMeasurer {
        var measureCalls = 0

        override fun advances(
            text: CharSequence,
            start: Int,
            end: Int,
            out: FloatArray,
        ) {
            measureCalls++
            for (index in start until end) out[index - start] = 1f
        }
    }
}
