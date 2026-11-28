package com.hbcao.rcode.editor

/**
 * Tap-to-caret mapping for one laid-out line (T-0015).
 *
 * The view converts the touch point to content coordinates, then to x relative to the
 * line start; this object turns that into the UTF-8 byte offset Rust expects, so the
 * interesting math can be unit-tested without an Android `View`.
 */
internal object EditorHitTest {
    /**
     * UTF-8 byte offset in the document for [lineX] (px from the line's left edge).
     *
     * Uses the nearest caret boundary from [LaidOutLine.textIndexForX] and converts the
     * UTF-16 index back to bytes: CJK and emoji therefore never place the caret inside a
     * character. The result is always within [LaidOutLine.text].
     */
    fun byteOffsetFor(
        layout: LaidOutLine,
        lineX: Float,
    ): Int = layout.startByte + Utf8Offsets.utf16ToByteOffset(layout.text, layout.textIndexForX(lineX))
}
