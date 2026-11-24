package com.hbcao.rcode.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8OffsetsTest {
    @Test
    fun `utf8Length counts bytes rather than chars`() {
        assertEquals(0, Utf8Offsets.utf8Length(""))
        assertEquals(1, Utf8Offsets.utf8Length("a"))
        assertEquals(3, Utf8Offsets.utf8Length("中"))
        assertEquals(4, Utf8Offsets.utf8Length("🙂"))
        assertEquals(8, Utf8Offsets.utf8Length("a中🙂"))
    }

    @Test
    fun `byteToUtf16Index rounds down to codepoint boundaries`() {
        val text = "a中🙂"
        assertEquals(0, Utf8Offsets.byteToUtf16Index(text, 0))
        assertEquals(1, Utf8Offsets.byteToUtf16Index(text, 1))
        assertEquals(1, Utf8Offsets.byteToUtf16Index(text, 2))
        assertEquals(1, Utf8Offsets.byteToUtf16Index(text, 3))
        assertEquals(2, Utf8Offsets.byteToUtf16Index(text, 4))
        assertEquals(2, Utf8Offsets.byteToUtf16Index(text, 7))
        assertEquals(4, Utf8Offsets.byteToUtf16Index(text, 8))
        assertEquals(4, Utf8Offsets.byteToUtf16Index(text, 99))
    }

    @Test
    fun `utf16ToByteOffset rounds down to codepoint boundaries`() {
        val text = "a中🙂"
        assertEquals(0, Utf8Offsets.utf16ToByteOffset(text, 0))
        assertEquals(1, Utf8Offsets.utf16ToByteOffset(text, 1))
        assertEquals(4, Utf8Offsets.utf16ToByteOffset(text, 2))
        assertEquals(4, Utf8Offsets.utf16ToByteOffset(text, 3))
        assertEquals(8, Utf8Offsets.utf16ToByteOffset(text, 4))
        assertEquals(8, Utf8Offsets.utf16ToByteOffset(text, 99))
    }

    @Test
    fun `negative offsets clamp to zero`() {
        val text = "中"
        assertEquals(0, Utf8Offsets.byteToUtf16Index(text, -1))
        assertEquals(0, Utf8Offsets.utf16ToByteOffset(text, -1))
    }
}
