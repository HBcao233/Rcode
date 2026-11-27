package com.hbcao.rcode.editor

/**
 * What the Rust codec detected instead of UTF-8 text.
 *
 * Mirrors `rcode_fs::codec::UnsupportedKind`; M1 rejects every encoding but UTF-8
 * (backlog Q1). The offsets index the **original** file bytes, BOM included.
 */
sealed class UnsupportedEncodingKind {
    /** UTF-16 little-endian text (`FF FE`). */
    data object Utf16Le : UnsupportedEncodingKind()

    /** UTF-16 big-endian text (`FE FF`). */
    data object Utf16Be : UnsupportedEncodingKind()

    /** UTF-32 little-endian text (`FF FE 00 00`). */
    data object Utf32Le : UnsupportedEncodingKind()

    /** UTF-32 big-endian text (`00 00 FE FF`). */
    data object Utf32Be : UnsupportedEncodingKind()

    /** Binary data: [nulOffset] is the byte offset of the first `NUL` in the file. */
    data class Binary(
        val nulOffset: Int,
    ) : UnsupportedEncodingKind()
}

/**
 * Why [EditorSessionApi.open] refused the bytes.
 *
 * Thrown by the bridge for the two `EditorError` variants `open` can produce; the
 * Rust side leaves the current document untouched, so callers can keep editing after
 * catching this. T-0012 maps these to user-facing strings.
 */
sealed class DocumentOpenException(
    message: String,
) : Exception(message) {
    /** The bytes are not valid UTF-8; [offset] indexes the original file bytes. */
    class NotUtf8(
        val offset: Int,
    ) : DocumentOpenException("input is not valid UTF-8 at byte offset $offset")

    /** The bytes are UTF-16/UTF-32 text or binary data. */
    class UnsupportedEncoding(
        val kind: UnsupportedEncodingKind,
    ) : DocumentOpenException("unsupported encoding: $kind")
}
