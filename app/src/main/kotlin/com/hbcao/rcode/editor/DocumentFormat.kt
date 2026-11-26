package com.hbcao.rcode.editor

/**
 * Line ending written for every `\n` of the buffer text.
 *
 * The buffer stores only `\n` (`architecture.md` §3.2); this reports which form the
 * file used so `EditorSession.encode` can restore it. M1 supports only these two.
 */
enum class LineEnding {
    /** `\n` (U+000A). */
    LF,

    /** `\r\n` (U+000D U+000A). */
    CRLF,
}

/**
 * How a document is saved: whether the file had a UTF-8 BOM and which line ending it
 * uses.
 *
 * Produced by the codec when opening a file (T-0012) and passed to
 * [EditorSessionApi.encode]; Kotlin never derives it from the buffer text.
 */
data class DocumentFormat(
    /** `true` when the file started with a UTF-8 BOM that must be written back. */
    val bom: Boolean,
    /** Form written for every `\n` of the buffer text. */
    val lineEnding: LineEnding,
) {
    companion object {
        /** Untitled/new document: no BOM and LF line endings (`DocumentFormat::new_file`). */
        val NEW_FILE = DocumentFormat(bom = false, lineEnding = LineEnding.LF)
    }
}
