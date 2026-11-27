package com.hbcao.rcode.editor

/**
 * Result of opening a document with [EditorSessionApi.open].
 *
 * Deliberately contains no text: Kotlin renders from [EditorSessionApi.viewport] and
 * must not keep the full document as authoritative state (`architecture.md` §3.1).
 *
 * @property version document version after loading; `open` always increments it.
 * @property lineCount number of logical lines after loading (always at least 1).
 * @property format format detected by the Rust codec; same as [EditorSessionApi.format].
 * @property cursor caret after loading, always offset 0 on line 0.
 */
data class OpenedDocumentState(
    val version: Long,
    val lineCount: Int,
    val format: DocumentFormat,
    val cursor: EditorCursorState,
)
