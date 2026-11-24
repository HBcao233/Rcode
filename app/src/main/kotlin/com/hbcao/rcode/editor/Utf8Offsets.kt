package com.hbcao.rcode.editor

/**
 * UTF-8 byte offset helpers.
 *
 * Rust speaks byte offsets, Android IME callbacks speak UTF-16 code units, and Kotlin
 * strings are UTF-16. These helpers convert between the two without assuming that one
 * character is one unit (CJK is 3 UTF-8 bytes, emoji are 4 bytes / 2 UTF-16 units).
 */
internal object Utf8Offsets {
    /** UTF-8 byte length of one Unicode code point. */
    fun utf8Length(codePoint: Int): Int =
        when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }

    /** UTF-8 byte length of [text]. */
    fun utf8Length(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            bytes += utf8Length(codePoint)
            index += Character.charCount(codePoint)
        }
        return bytes
    }

    /**
     * UTF-16 index for a UTF-8 [byteOffset], rounded down to a code point boundary.
     * Offsets past the end clamp to [text] length.
     */
    fun byteToUtf16Index(
        text: String,
        byteOffset: Int,
    ): Int {
        if (byteOffset <= 0) return 0
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val nextBytes = bytes + utf8Length(codePoint)
            if (nextBytes > byteOffset) break
            bytes = nextBytes
            index += Character.charCount(codePoint)
        }
        return index
    }

    /**
     * UTF-8 byte offset for a UTF-16 [utf16Index], rounded down to a code point
     * boundary. Indices past the end clamp to [text] byte length.
     */
    fun utf16ToByteOffset(
        text: String,
        utf16Index: Int,
    ): Int {
        if (utf16Index <= 0) return 0
        var bytes = 0
        var index = 0
        while (index < text.length && index < utf16Index) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (index + charCount > utf16Index) break
            bytes += utf8Length(codePoint)
            index += charCount
        }
        return bytes
    }
}
