package com.hbcao.rcode.editor

/**
 * The bytes of one save plus the document version they encode.
 *
 * A plain class on purpose: [bytes] is a `ByteArray`, and a `data class` would compare
 * it by reference, which is never what a caller wants.
 */
class EncodedDocument(
    /**
     * Version of the text that was encoded.
     *
     * Edits after the [EditorSessionApi.encode] call do not appear in [bytes]; compare
     * this with [EditorSessionApi.version] when saving to tell whether the document
     * changed in the meantime (T-0013).
     */
    val version: Long,
    /** File contents: BOM (when requested) and the text with its line ending. */
    val bytes: ByteArray,
)
