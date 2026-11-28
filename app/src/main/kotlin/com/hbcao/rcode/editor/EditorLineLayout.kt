package com.hbcao.rcode.editor

import android.graphics.Paint
import kotlin.math.abs

/**
 * Font metrics that make two line layouts incompatible (T-0014, `editor-rendering` skill).
 *
 * The layout cache key includes this value, so changing the typeface, size or line height
 * can never reuse geometry measured with the old font. It stays a plain data class on
 * purpose: the layout layer and its JVM tests must not depend on `android.graphics`.
 *
 * @property typefaceId identity of the typeface in use; M1 never changes it.
 * @property textSizePx text size in pixels (not sp).
 * @property lineHeightPx baseline-to-baseline distance in pixels.
 */
internal data class FontConfig(
    val typefaceId: Int,
    val textSizePx: Float,
    val lineHeightPx: Float,
)

/**
 * Advance widths of one text range, injected so [LineLayoutBuilder] is testable on the JVM.
 *
 * Implementations must fill `out[0 until end - start]` with the advance of every UTF-16
 * unit; a surrogate pair may put its whole advance on the first unit and `0` on the
 * second. Shaping (kerning, ligatures) is out of scope for M1: advances are measured per
 * code point, like `Paint.getTextWidths` does.
 */
internal interface LineMeasurer {
    fun advances(
        text: CharSequence,
        start: Int,
        end: Int,
        out: FloatArray,
    )
}

/** Production [LineMeasurer] backed by the editor's [Paint]. */
internal class PaintLineMeasurer(
    private val paint: Paint,
) : LineMeasurer {
    override fun advances(
        text: CharSequence,
        start: Int,
        end: Int,
        out: FloatArray,
    ) {
        if (end <= start) return
        paint.getTextWidths(text, start, end, out)
    }
}

/**
 * Geometry of one line for one document version, independent of `Canvas` (T-0014).
 *
 * @property index zero-based line number in the document.
 * @property startByte UTF-8 byte offset of the line start in the buffer.
 * @property text the line as returned by `viewport`, without terminator and tabs expanded.
 * @property displayText what is drawn: [text] with each `\t` replaced by [TAB_SPACES]; a
 *   new string only when the line has tabs or was truncated to the visible window.
 * @property xOffsets x (px, from the line start) of every UTF-16 boundary of
 *   [displayText]; size is `displayText.length + 1`. Valid code point boundaries are the
 *   ones a caret or selection can use.
 * @property width measured width of [displayText] in px; at least the requested window
 *   width when [truncated].
 * @property truncated `true` when layout stopped at the visible window, so [displayText]
 *   is only the measured prefix of [text].
 * @property hasTabs `true` when [text] contains tabs, so text indices and display
 *   indices differ.
 */
internal class LaidOutLine(
    val index: Int,
    val startByte: Int,
    val text: String,
    val displayText: String,
    val xOffsets: FloatArray,
    val width: Float,
    val truncated: Boolean,
    val hasTabs: Boolean,
) {
    /**
     * x offset in px for the [textIndex]-th UTF-16 unit of [text] (clamped).
     *
     * The caller maps a UTF-8 byte offset to a UTF-16 index first
     * ([Utf8Offsets.byteToUtf16Index]); this method only expands tabs and looks the value
     * up. A caret past the measured window (truncated line) clamps to [width].
     */
    fun xForTextIndex(textIndex: Int): Float {
        if (textIndex <= 0) return 0f
        val displayIndex = if (hasTabs) displayIndexFor(textIndex) else textIndex
        return xOffsets[displayIndex.coerceIn(0, xOffsets.lastIndex)]
    }

    /**
     * UTF-16 index in [text] nearest to [x] (px from the line start; T-0015).
     *
     * Only caret boundaries are candidates: never an index inside a surrogate pair and
     * never a position inside an expanded tab. Ties prefer the earlier index, matching
     * the midpoint rule used by tap-to-caret. For a [truncated] line only the measured
     * prefix is considered, so callers that need the rest must lay the line out with a
     * wider window first (Q-T15-4).
     */
    fun textIndexForX(x: Float): Int {
        val measuredUnits = xOffsets.size - 1
        if (measuredUnits <= 0 || text.isEmpty()) return 0
        var displayIndex = 0
        var textIndex = 0
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        while (true) {
            val boundaryX = xOffsets[displayIndex.coerceIn(0, xOffsets.lastIndex)]
            val distance = abs(boundaryX - x)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = textIndex
            }
            if (textIndex >= text.length) break
            val codePoint = text.codePointAt(textIndex)
            val units = Character.charCount(codePoint)
            val displayStep = if (codePoint == TAB_CODE_POINT) TAB_SIZE else units
            if (displayIndex + displayStep > measuredUnits) break
            displayIndex += displayStep
            textIndex += units
        }
        return bestIndex
    }

    private fun displayIndexFor(textIndex: Int): Int {
        var display = 0
        var index = 0
        val limit = minOf(textIndex, text.length)
        while (index < limit) {
            display += if (text[index] == '\t') TAB_SIZE else 1
            index++
        }
        // Indices past the end (only possible for stale callers) stay at the end.
        return display + maxOf(0, textIndex - text.length)
    }

    companion object {
        /** Tab expansion width in spaces (Q-T14-3); no settings in M1. */
        const val TAB_SIZE = 4

        /** `\t` as a code point, compared per code point during hit testing. */
        private const val TAB_CODE_POINT = '\t'.code

        /** Drawing replacement of one tab. */
        const val TAB_SPACES = "    "

        /** Layout of an empty line; shared because it is immutable. */
        val EMPTY =
            LaidOutLine(
                index = 0,
                startByte = 0,
                text = "",
                displayText = "",
                xOffsets = FloatArray(1),
                width = 0f,
                truncated = false,
                hasTabs = false,
            )

        /** Empty line with the real index/offset of [line]. */
        fun empty(line: EditorLineSlice): LaidOutLine =
            if (line.text.isEmpty()) {
                LaidOutLine(
                    index = line.index,
                    startByte = line.startByte,
                    text = "",
                    displayText = "",
                    xOffsets = FloatArray(1),
                    width = 0f,
                    truncated = false,
                    hasTabs = false,
                )
            } else {
                throw IllegalArgumentException("line ${line.index} is not empty")
            }
    }
}

/**
 * Turns one [EditorLineSlice] into a [LaidOutLine] (T-0014).
 *
 * Layout stops once the measured width passes [layout]'s `maxWidthPx` window, so the cost
 * of a very long line depends on the visible width instead of the line length (V6,
 * Q-T14-5). Tabs are expanded to [LaidOutLine.TAB_SPACES]; every UTF-16 boundary gets an
 * x offset so the caret and selection never re-measure the line from the start.
 *
 * The measurer is injected: production uses [PaintLineMeasurer] (one
 * `Paint.getTextWidths` call per [MEASURE_CHUNK] units), JVM tests use a fixed-width fake.
 */
internal class LineLayoutBuilder(
    private val measurer: LineMeasurer,
) {
    private var tabAdvances: FloatArray? = null

    /**
     * Lays [line] out, measuring at most the text that fits in `maxWidthPx`.
     *
     * @param maxWidthPx visible width plus a small margin; `<= 0` still measures one
     *   code point so the line is not empty.
     */
    fun layout(
        line: EditorLineSlice,
        maxWidthPx: Float,
    ): LaidOutLine {
        val text = line.text
        if (text.isEmpty()) return LaidOutLine.empty(line)

        val limit = maxWidthPx.coerceAtLeast(0f)
        val offsets = FloatList(text.length + 1)
        offsets.add(0f)
        val advances = FloatArray(MEASURE_CHUNK)
        var display: StringBuilder? = null
        var chunkStart = 0
        var chunkEnd = 0
        var x = 0f
        var truncated = false
        var truncatedAt = text.length
        var hasTabs = false
        var index = 0

        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val units = Character.charCount(codePoint)
            if (index < chunkStart || index + units > chunkEnd) {
                chunkStart = index
                chunkEnd = minOf(text.length, index + MEASURE_CHUNK)
                measurer.advances(text, chunkStart, chunkEnd, advances)
            }
            if (codePoint == TAB_CODE_POINT) {
                hasTabs = true
                display = appendPrefix(display, text, index).append(LaidOutLine.TAB_SPACES)
                val tab = tabAdvances()
                for (space in 0 until LaidOutLine.TAB_SIZE) {
                    x += tab[space]
                    offsets.add(x)
                }
            } else {
                if (display != null) display.append(text, index, index + units)
                val first = advances[index - chunkStart]
                x += first
                offsets.add(x)
                if (units == 2) {
                    // Surrogate pair: record the (unusable) boundary between the units
                    // so xOffsets stays aligned with displayText indices.
                    x += advances[index - chunkStart + 1]
                    offsets.add(x)
                }
            }
            index += units
            if (x > limit && index < text.length) {
                truncated = true
                truncatedAt = index
                break
            }
        }

        val displayText =
            when {
                display != null -> display.toString()
                truncated -> text.substring(0, truncatedAt)
                else -> text
            }
        return LaidOutLine(
            index = line.index,
            startByte = line.startByte,
            text = text,
            displayText = displayText,
            xOffsets = offsets.toArray(),
            width = x,
            truncated = truncated,
            hasTabs = hasTabs,
        )
    }

    /** Advances of the four spaces a tab expands to; measured once per builder. */
    private fun tabAdvances(): FloatArray =
        tabAdvances ?: FloatArray(LaidOutLine.TAB_SIZE).also { tabs ->
            measurer.advances(LaidOutLine.TAB_SPACES, 0, LaidOutLine.TAB_SIZE, tabs)
            tabAdvances = tabs
        }

    private fun appendPrefix(
        display: StringBuilder?,
        text: String,
        end: Int,
    ): StringBuilder = display ?: StringBuilder(end + LaidOutLine.TAB_SIZE).append(text, 0, end)

    private class FloatList(
        initialCapacity: Int,
    ) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(2))
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    internal companion object {
        /** Units measured per `getTextWidths` call: large enough to keep JNI overhead low. */
        internal const val MEASURE_CHUNK = 256

        private const val TAB_CODE_POINT = '\t'.code
    }
}

/**
 * Per-document, per-version line layout cache (T-0014, `editor-rendering` skill).
 *
 * Key: `(documentId, version, lineIndex, fontConfig)`. Entries are never reused across
 * document versions — a miss lays the line out again — and the map evicts the least
 * recently used entry past [capacity] (`editor-rendering` Q-T14-1).
 *
 * `maxWidthPx` is **not** part of the key: the editor width is fixed between size
 * changes, and the horizontal layout window only grows in 512 px buckets while the user
 * scrolls right (T-0015). `EditorView` drops the whole cache when that window bucket or
 * the view width changes, so an entry can never be reused with a narrower window.
 *
 * @property capacity maximum number of cached lines; `max(3 × visible rows, [MIN_ENTRIES])`
 *   in production, so one screen plus both prefetch screens survive a scroll. It only
 *   ever grows ([ensureCapacity]): a height change (IME opening) keeps the entries.
 */
internal class EditorLineLayoutCache(
    private val builder: LineLayoutBuilder,
    capacity: Int = MIN_ENTRIES,
) {
    private data class Key(
        val documentId: Long,
        val version: Long,
        val fontConfig: FontConfig,
        val lineIndex: Int,
    )

    var capacity: Int = capacity.coerceAtLeast(1)
        private set

    private val entries =
        object : LinkedHashMap<Key, LaidOutLine>(this.capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, LaidOutLine>): Boolean = size > capacity
        }

    /** Number of cached lines; the JVM tests assert hits and eviction with it. */
    val size: Int get() = entries.size

    /**
     * Raises the entry limit to [entries] without dropping anything; a smaller value is
     * ignored, so a view that got shorter (keyboard opened) keeps its layouts.
     */
    fun ensureCapacity(entries: Int) {
        if (entries > capacity) capacity = entries
    }

    /**
     * Returns the layout of [line] for the given document state, laying it out on a miss.
     */
    fun layoutFor(
        documentId: Long,
        version: Long,
        fontConfig: FontConfig,
        line: EditorLineSlice,
        maxWidthPx: Float,
    ): LaidOutLine {
        val key = Key(documentId, version, fontConfig, line.index)
        entries[key]?.let { return it }
        val layout = builder.layout(line, maxWidthPx)
        entries[key] = layout
        return layout
    }

    /** Drops every entry; called when the document, the font or the view width changes. */
    fun invalidateAll() {
        entries.clear()
    }

    companion object {
        /** Lower bound for the cache size (`editor-rendering` recommendation). */
        const val MIN_ENTRIES = 256

        private const val LOAD_FACTOR = 0.75f

        /** Entries kept for a view showing [visibleRows] rows: the viewport plus both prefetch screens. */
        fun capacityFor(visibleRows: Int): Int = maxOf(visibleRows * 3, MIN_ENTRIES)
    }
}
