//! Editing session for the M1 editor, backed by `rcode-buffer` (rope text).
//!
//! The document text lives in a [`TextBuffer`], so viewport, line and offset
//! queries are `O(log n)` instead of scanning the whole text. All offsets exposed
//! through this module are **UTF-8 byte offsets** unless the name or
//! documentation says otherwise.
//!
//! The session is the authoritative owner of text and caret. Kotlin only caches
//! the viewport returned by [`EditorSession::viewport`] for rendering.
//!
//! # Lifecycle
//!
//! One session holds one document (M1). [`EditorSession::new`] starts an empty
//! untitled document; [`EditorSession::open`] replaces it with the decoded bytes
//! of a file, clearing undo history and moving the caret to offset 0. Opening is
//! not an edit, but it does increment the version.
//!
//! # Batch line access
//!
//! [`EditorSession::viewport`] is the only way to read lines: one call returns
//! all visible lines, their start offsets and the caret in one snapshot, so
//! Kotlin never loops per line or per character. `first_line` is clamped to the
//! document and `max_lines` to `1..=10000`; line text excludes the terminator;
//! `version` identifies the snapshot the lines and caret belong to. Prefetching
//! (T-0014) widens the requested range instead of making additional calls.

use std::fmt;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Instant;

use rcode_buffer::{BufferError, History, TextBuffer};
use rcode_fs::codec;

/// Upper bound for one viewport request, so a bad caller cannot allocate without limit.
const MAX_VIEWPORT_LINES: u32 = 10_000;

/// Errors returned by editing-session operations.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EditorError {
    /// An index was past the end of the document.
    ///
    /// `offset` and `len` are expressed in the unit of the failing argument:
    /// bytes for byte-based methods, chars for [`TextBuffer::char_to_byte`],
    /// UTF-16 code units for [`TextBuffer::utf16_to_byte`].
    #[error("offset {offset} is out of bounds for length {len}")]
    OutOfBounds {
        /// Rejected index.
        offset: u32,
        /// Document length in the same unit as `offset`.
        len: u32,
    },
    /// A byte offset fell inside a multi-byte UTF-8 sequence.
    #[error("byte offset {offset} is not on a UTF-8 character boundary")]
    NotCharBoundary {
        /// Rejected byte offset.
        offset: u32,
    },
    /// A byte range had `start > end`.
    #[error("byte range {start}..{end} is invalid")]
    InvalidRange {
        /// Range start in bytes.
        start: u32,
        /// Range end in bytes (exclusive).
        end: u32,
    },
    /// A line index was not in `0..line_count`.
    #[error("line {line} is out of bounds for a document with {line_count} lines")]
    LineOutOfBounds {
        /// Rejected zero-based line index.
        line: u32,
        /// Number of lines in the document (always at least 1).
        line_count: u32,
    },
    /// The bytes passed to [`EditorSession::open`] are not valid UTF-8.
    #[error("input is not valid UTF-8 at byte offset {offset}")]
    NotUtf8 {
        /// Byte offset in the **original** input (BOM included) of the first
        /// byte that does not start a valid UTF-8 sequence.
        offset: u32,
    },
    /// The bytes passed to [`EditorSession::open`] are UTF-16/UTF-32 or binary.
    #[error("unsupported encoding: {kind}")]
    UnsupportedEncoding {
        /// What was detected.
        kind: UnsupportedEncodingKind,
    },
}

impl From<BufferError> for EditorError {
    fn from(error: BufferError) -> Self {
        match error {
            BufferError::OutOfBounds { offset, len } => EditorError::OutOfBounds {
                offset: to_u32(offset),
                len: to_u32(len),
            },
            BufferError::NotCharBoundary { offset } => EditorError::NotCharBoundary {
                offset: to_u32(offset),
            },
            BufferError::InvalidRange { start, end } => EditorError::InvalidRange {
                start: to_u32(start),
                end: to_u32(end),
            },
            BufferError::LineOutOfBounds { line, line_count } => EditorError::LineOutOfBounds {
                line: to_u32(line),
                line_count: to_u32(line_count),
            },
        }
    }
}

impl From<codec::CodecError> for EditorError {
    fn from(error: codec::CodecError) -> Self {
        match error {
            codec::CodecError::NotUtf8 { offset } => EditorError::NotUtf8 {
                offset: to_u32(offset),
            },
            codec::CodecError::UnsupportedEncoding { kind } => {
                EditorError::UnsupportedEncoding { kind: kind.into() }
            }
        }
    }
}

/// Why [`EditorSession::open`] rejected text that is not UTF-8.
///
/// FFI mirror of [`rcode_fs::codec::UnsupportedKind`]; the offsets index the
/// original input bytes, BOM included (same unit as `codec::decode`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UnsupportedEncodingKind {
    /// Input starts with a UTF-16 little-endian BOM (`FF FE`).
    Utf16Le,
    /// Input starts with a UTF-16 big-endian BOM (`FE FF`).
    Utf16Be,
    /// Input starts with a UTF-32 little-endian BOM (`FF FE 00 00`).
    Utf32Le,
    /// Input starts with a UTF-32 big-endian BOM (`00 00 FE FF`).
    Utf32Be,
    /// Input contains a `NUL` byte (`U+0000`), typical of binary files.
    Binary {
        /// Byte offset of the first `NUL` in the original input.
        nul_offset: u32,
    },
}

// Hand-written so `EditorError`'s message never depends on how the codec formats
// its own errors; this is display text for the FFI type only.
impl fmt::Display for UnsupportedEncodingKind {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Utf16Le => formatter.write_str("UTF-16 little-endian text"),
            Self::Utf16Be => formatter.write_str("UTF-16 big-endian text"),
            Self::Utf32Le => formatter.write_str("UTF-32 little-endian text"),
            Self::Utf32Be => formatter.write_str("UTF-32 big-endian text"),
            Self::Binary { nul_offset } => {
                write!(formatter, "binary data (NUL byte at offset {nul_offset})")
            }
        }
    }
}

impl From<codec::UnsupportedKind> for UnsupportedEncodingKind {
    fn from(kind: codec::UnsupportedKind) -> Self {
        match kind {
            codec::UnsupportedKind::Utf16Le => Self::Utf16Le,
            codec::UnsupportedKind::Utf16Be => Self::Utf16Be,
            codec::UnsupportedKind::Utf32Le => Self::Utf32Le,
            codec::UnsupportedKind::Utf32Be => Self::Utf32Be,
            codec::UnsupportedKind::Binary { nul_offset } => Self::Binary {
                nul_offset: to_u32(nul_offset),
            },
        }
    }
}

/// Caret position in document coordinates.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EditorCursor {
    /// Caret offset from the start of the document, in UTF-8 bytes.
    pub byte_offset: u32,
    /// Zero-based logical line index containing the caret.
    pub line: u32,
    /// Caret offset from the start of [`Self::line`], in UTF-8 bytes.
    pub column_bytes: u32,
}

/// One logical line of the document, without its trailing newline.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EditorLine {
    /// Zero-based logical line index.
    pub index: u32,
    /// UTF-8 byte offset of the first character of this line in the document.
    pub start_byte: u32,
    /// Line content, excluding `\n` (and a preceding `\r` for CRLF files).
    pub text: String,
}

/// Snapshot of the visible lines plus the caret, tied to a document version.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EditorViewport {
    /// Document version this snapshot belongs to. Changes on every text edit.
    pub version: u64,
    /// Total number of logical lines in the document (always at least 1).
    pub line_count: u32,
    /// Index of the first line in [`Self::lines`].
    pub first_line: u32,
    /// Consecutive lines starting at [`Self::first_line`].
    pub lines: Vec<EditorLine>,
    /// Caret position at the time of the snapshot.
    pub cursor: EditorCursor,
}

/// Line ending to write for every `\n` of the buffer text.
///
/// FFI mirror of [`rcode_fs::codec::LineEnding`]; M1 supports only these two forms.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LineEnding {
    /// `\n` (U+000A).
    Lf,
    /// `\r\n` (U+000D U+000A).
    CrLf,
}

impl From<LineEnding> for codec::LineEnding {
    fn from(line_ending: LineEnding) -> Self {
        match line_ending {
            LineEnding::Lf => codec::LineEnding::Lf,
            LineEnding::CrLf => codec::LineEnding::CrLf,
        }
    }
}

impl From<codec::LineEnding> for LineEnding {
    fn from(line_ending: codec::LineEnding) -> Self {
        match line_ending {
            codec::LineEnding::Lf => LineEnding::Lf,
            codec::LineEnding::CrLf => LineEnding::CrLf,
        }
    }
}

/// How a file is written back: whether to write a BOM and which line ending to use.
///
/// FFI mirror of [`rcode_fs::codec::DocumentFormat`], filled in by `codec::decode`
/// when opening a file (T-0012) and passed to [`EditorSession::encode`] when saving.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct DocumentFormat {
    /// `true` when the file started with a UTF-8 BOM that must be written back.
    pub bom: bool,
    /// Form written for every `\n` of the buffer text.
    pub line_ending: LineEnding,
}

impl From<DocumentFormat> for codec::DocumentFormat {
    fn from(format: DocumentFormat) -> Self {
        Self {
            bom: format.bom,
            line_ending: format.line_ending.into(),
        }
    }
}

impl From<codec::DocumentFormat> for DocumentFormat {
    fn from(format: codec::DocumentFormat) -> Self {
        Self {
            bom: format.bom,
            line_ending: format.line_ending.into(),
        }
    }
}

impl DocumentFormat {
    /// Format of a document without a file behind it: no BOM, `\n` endings.
    pub(crate) fn new_file() -> Self {
        Self {
            bom: false,
            line_ending: LineEnding::Lf,
        }
    }
}

/// A document loaded by [`EditorSession::open`].
///
/// Contains no text: Kotlin renders from [`EditorSession::viewport`] and never
/// keeps the full document as authoritative state (`architecture.md` §3.1).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OpenedDocument {
    /// New document version; `open` always increments it.
    pub version: u64,
    /// Total logical lines after loading (always at least 1).
    pub line_count: u32,
    /// Format `codec::decode` detected; same as [`EditorSession::format`].
    pub format: DocumentFormat,
    /// Caret after loading: always `byte_offset = 0`, `line = 0`.
    pub cursor: EditorCursor,
}

/// The bytes of one save plus the document version they encode.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EncodedDocument {
    /// Version of the text that was encoded.
    ///
    /// Text edits after this call do not appear in [`Self::bytes`]. A saver compares
    /// it with [`EditorSession::version`] to tell whether the document changed
    /// while saving.
    pub version: u64,
    /// File contents: BOM (when requested) and the text with its line ending.
    pub bytes: Vec<u8>,
}

/// One editing session for a single document. Thread-safe; created from Kotlin as
/// `EditorSession()`, which starts with an empty `untitled.txt`.
#[derive(uniffi::Object)]
pub struct EditorSession {
    inner: Mutex<Doc>,
}

/// Number of bytes above which an `usize` length or offset is reported as
/// `u32::MAX`. M1 files are far below 4 GiB; saturating keeps the mapping
/// total without silently wrapping.
fn to_u32(value: usize) -> u32 {
    value.min(u32::MAX as usize) as u32
}

struct Doc {
    /// Authoritative text; owns the version number.
    buffer: TextBuffer,
    /// Undo/redo groups for [`Self::buffer`], stored under the same lock.
    history: History,
    /// Caret as a UTF-8 byte offset; always on a character boundary.
    cursor: usize,
    /// Format to save the document with, set by `codec::decode` on open.
    format: DocumentFormat,
}

impl Doc {
    fn new() -> Self {
        Self {
            buffer: TextBuffer::new(),
            history: History::default(),
            cursor: 0,
            format: DocumentFormat::new_file(),
        }
    }

    /// Records a successful edit; `caret_before` is the caret before the edit.
    fn record(&mut self, edit: &rcode_buffer::Edit, inserted: &str, caret_before: usize) {
        self.history
            .record(edit, inserted, caret_before, Instant::now());
    }

    fn cursor_info(&self) -> EditorCursor {
        // `cursor` is always a valid boundary, so the fallbacks are unreachable;
        // they only keep this function panic-free.
        let line = self.buffer.byte_to_line(self.cursor).unwrap_or(0);
        let line_start = self.buffer.line_to_byte(line).unwrap_or(self.cursor);
        EditorCursor {
            byte_offset: to_u32(self.cursor),
            line: to_u32(line),
            column_bytes: to_u32(self.cursor.saturating_sub(line_start)),
        }
    }
}

#[uniffi::export]
impl EditorSession {
    /// Creates an empty untitled document with the caret at offset 0.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(Doc::new()),
        })
    }

    /// Replaces the current document with `bytes` decoded as UTF-8 text.
    ///
    /// This is the only way to load a file: the bytes are decoded by
    /// `rcode-fs::codec` inside Rust, so BOM stripping and `\r\n` normalization
    /// happen before the text reaches the buffer, and the detected
    /// [`DocumentFormat`] is stored in the session for [`Self::encode`].
    ///
    /// Opening is not an edit: undo/redo history is cleared, the caret moves to
    /// offset 0, and the version increments exactly once (even when the new text
    /// equals the old one). The call is `O(n)` and must run off the UI thread.
    ///
    /// On failure nothing changes: text, format, caret, version and history stay
    /// exactly as they were, because decoding happens before the lock is taken.
    ///
    /// # Errors
    ///
    /// [`EditorError::NotUtf8`] or [`EditorError::UnsupportedEncoding`] when the
    /// input is not UTF-8 text (Q1: M1 rejects other encodings).
    pub fn open(&self, bytes: Vec<u8>) -> Result<OpenedDocument, EditorError> {
        // Decode outside the lock: it is the expensive part (~45 ms for 10 MB)
        // and a failure must not touch the session at all.
        let decoded = codec::decode(&bytes)?;
        // Release the caller's copy before taking the bigger rope allocation.
        drop(bytes);
        let mut doc = self.lock();
        let version = doc.buffer.reset(&decoded.text);
        drop(decoded.text);
        doc.history.clear();
        doc.cursor = 0;
        doc.format = decoded.format.into();
        Ok(OpenedDocument {
            version,
            line_count: to_u32(doc.buffer.line_count()),
            format: doc.format,
            cursor: doc.cursor_info(),
        })
    }

    /// Format detected when the current document was opened (BOM + line ending).
    ///
    /// Pass this to [`Self::encode`] when saving. An untitled document that was
    /// never opened reports no BOM and `\n` endings.
    pub fn format(&self) -> DocumentFormat {
        self.lock().format
    }

    /// Returns the full document text. Intended for tests and diagnostics; the UI
    /// must use [`Self::viewport`] instead of caching a full copy.
    pub fn text(&self) -> String {
        self.lock().buffer.to_string()
    }

    /// Encodes the current text for saving, using the file's original format.
    ///
    /// The text snapshot and the version are taken under one lock; encoding runs
    /// after the lock is released, so an `O(n)` pass does not block other edits for
    /// longer than the snapshot copy. Callers must run this off the UI thread
    /// (`architecture.md` §3.4) and write the returned bytes themselves.
    ///
    /// The method does not modify the text, caret or version and cannot fail: the
    /// buffer invariant (no BOM, only `\n` line breaks) is established by
    /// `codec::decode` when opening a file (`architecture.md` §3.2).
    pub fn encode(&self, format: DocumentFormat) -> EncodedDocument {
        let (version, text) = {
            let doc = self.lock();
            (doc.buffer.version(), doc.buffer.to_string())
        };
        let mut bytes = Vec::new();
        codec::encode_into(&text, &format.into(), &mut bytes);
        EncodedDocument { version, bytes }
    }

    /// Current document version. Increments on every text change, not on caret moves.
    pub fn version(&self) -> u64 {
        self.lock().buffer.version()
    }

    /// Number of logical lines (always at least 1).
    pub fn line_count(&self) -> u32 {
        to_u32(self.lock().buffer.line_count())
    }

    /// Current caret position.
    pub fn cursor(&self) -> EditorCursor {
        self.lock().cursor_info()
    }

    /// Caret position measured in UTF-16 code units (the unit used by Android IME APIs).
    pub fn cursor_utf16_offset(&self) -> u32 {
        let doc = self.lock();
        doc.buffer.byte_to_utf16(doc.cursor).map_or(0, to_u32)
    }

    /// Inserts `text` at the caret and places the caret after it.
    ///
    /// Returns the new document version.
    pub fn insert_text(&self, text: String) -> u64 {
        let mut doc = self.lock();
        let at = doc.cursor;
        let caret_before = doc.cursor;
        // `at` is always a valid character boundary, so this cannot fail.
        let Ok(edit) = doc.buffer.insert(at, &text) else {
            return doc.buffer.version();
        };
        doc.record(&edit, &text, caret_before);
        doc.cursor = edit.new_end_byte;
        edit.version
    }

    /// Replaces the UTF-8 byte range `start_byte..end_byte` with `text` and places
    /// the caret after the inserted text.
    ///
    /// Returns the new document version.
    pub fn replace_range(
        &self,
        start_byte: u32,
        end_byte: u32,
        text: String,
    ) -> Result<u64, EditorError> {
        let mut doc = self.lock();
        let (start, end) = (start_byte as usize, end_byte as usize);
        let caret_before = doc.cursor;
        let edit = doc.buffer.replace(start..end, &text)?;
        doc.record(&edit, &text, caret_before);
        doc.cursor = edit.new_end_byte;
        Ok(edit.version)
    }

    /// Deletes the UTF-8 byte range `start_byte..end_byte` and places the caret at
    /// `start_byte`. Returns the new document version.
    pub fn delete_range(&self, start_byte: u32, end_byte: u32) -> Result<u64, EditorError> {
        self.replace_range(start_byte, end_byte, String::new())
    }

    /// Deletes the character before the caret. No-op at the start of the document.
    /// Returns the new document version.
    pub fn delete_backward(&self) -> u64 {
        let mut doc = self.lock();
        let cursor = doc.cursor;
        let caret_before = cursor;
        let Some(previous) = doc.buffer.prev_char_boundary(cursor) else {
            return doc.buffer.version();
        };
        match doc.buffer.delete(previous..cursor) {
            Ok(edit) => {
                doc.record(&edit, "", caret_before);
                doc.cursor = previous;
                edit.version
            }
            // Both offsets are character boundaries, so this cannot fail.
            Err(_) => doc.buffer.version(),
        }
    }

    /// Deletes the character after the caret. No-op at the end of the document.
    /// Returns the new document version.
    pub fn delete_forward(&self) -> u64 {
        let mut doc = self.lock();
        let cursor = doc.cursor;
        let caret_before = cursor;
        let Some(next) = doc.buffer.next_char_boundary(cursor) else {
            return doc.buffer.version();
        };
        match doc.buffer.delete(cursor..next) {
            Ok(edit) => {
                doc.record(&edit, "", caret_before);
                edit.version
            }
            // Both offsets are character boundaries, so this cannot fail.
            Err(_) => doc.buffer.version(),
        }
    }

    /// Undoes the newest undo group.
    ///
    /// Returns the new document version, or `None` when there was nothing to undo
    /// (the version and caret are unchanged then). On success the caret is restored
    /// to the offset recorded before the group's first edit.
    ///
    /// An unfinished IME composition is closed first, so a committed word is undone
    /// as a whole.
    pub fn undo(&self) -> Option<u64> {
        let mut doc = self.lock();
        doc.history.end_composition();
        let Doc {
            buffer,
            history,
            cursor,
            ..
        } = &mut *doc;
        let result = history.undo(|group| {
            buffer
                .replace(group.undo_range(), &group.removed)
                .map(|_| ())
        });
        match result {
            Ok(Some(new_cursor)) => {
                *cursor = new_cursor;
                Some(buffer.version())
            }
            // Nothing to undo, or a group that no longer fits the buffer (the
            // latter is unreachable for groups produced by this session).
            Ok(None) | Err(_) => None,
        }
    }

    /// Redoes the newest redo group.
    ///
    /// Returns the new document version, or `None` when there was nothing to redo.
    /// On success the caret is placed just past the redone text.
    pub fn redo(&self) -> Option<u64> {
        let mut doc = self.lock();
        doc.history.end_composition();
        let Doc {
            buffer,
            history,
            cursor,
            ..
        } = &mut *doc;
        let result = history.redo(|group| {
            buffer
                .replace(group.redo_range(), &group.inserted)
                .map(|_| ())
        });
        match result {
            Ok(Some(new_cursor)) => {
                *cursor = new_cursor;
                Some(buffer.version())
            }
            Ok(None) | Err(_) => None,
        }
    }

    /// `true` when [`Self::undo`] would change the document.
    pub fn can_undo(&self) -> bool {
        self.lock().history.can_undo()
    }

    /// `true` when [`Self::redo`] would change the document.
    pub fn can_redo(&self) -> bool {
        self.lock().history.can_redo()
    }

    /// Clears the undo and redo history (both stacks and the open group).
    ///
    /// Call this after loading or replacing a document's text (T-0011/T-0012):
    /// opening a file must not be an undoable step.
    pub fn clear_history(&self) {
        self.lock().history.clear();
    }

    /// Marks the following edits as one IME composition.
    ///
    /// Closes the current group; idempotent while already composing. The next
    /// edits are merged into one group regardless of the 500 ms merge window, so a
    /// slow pinyin sequence undoes as one step.
    pub fn begin_composition(&self) {
        self.lock().history.begin_composition();
    }

    /// Ends an IME composition, closing its group.
    ///
    /// A composition that left the text unchanged (cancelled input, or a commit
    /// that rewrote identical text) leaves no undo step. Idempotent when not
    /// composing.
    pub fn end_composition(&self) {
        self.lock().history.end_composition();
    }

    /// Moves the caret to `byte_offset`, clamped to the document and snapped down to
    /// the nearest UTF-8 character boundary. Returns the resulting byte offset.
    pub fn move_cursor(&self, byte_offset: u32) -> u32 {
        let mut doc = self.lock();
        doc.cursor = doc.buffer.floor_char_boundary(byte_offset as usize);
        to_u32(doc.cursor)
    }

    /// Moves the caret by `delta_chars` Unicode scalar values (negative moves left).
    /// Returns the resulting byte offset.
    pub fn move_cursor_horizontal(&self, delta_chars: i32) -> u32 {
        let mut doc = self.lock();
        let mut cursor = doc.cursor;
        if delta_chars >= 0 {
            for _ in 0..delta_chars {
                let Some(next) = doc.buffer.next_char_boundary(cursor) else {
                    break;
                };
                cursor = next;
            }
        } else {
            for _ in 0..delta_chars.unsigned_abs() {
                let Some(previous) = doc.buffer.prev_char_boundary(cursor) else {
                    break;
                };
                cursor = previous;
            }
        }
        doc.cursor = cursor;
        to_u32(cursor)
    }

    /// Moves the caret by `delta_lines`, keeping the byte column when possible and
    /// clamping to the target line length. Returns the resulting byte offset.
    pub fn move_cursor_vertical(&self, delta_lines: i32) -> u32 {
        let mut doc = self.lock();
        let line = doc.buffer.byte_to_line(doc.cursor).unwrap_or(0);
        let line_start = doc.buffer.line_to_byte(line).unwrap_or(doc.cursor);
        let line_count = doc.buffer.line_count();
        let target =
            (line as i64 + i64::from(delta_lines)).clamp(0, line_count as i64 - 1) as usize;
        let target_start = doc.buffer.line_to_byte(target).unwrap_or(0);
        let content = doc
            .buffer
            .line_content_range(target)
            .unwrap_or(target_start..target_start);
        let column = doc
            .cursor
            .saturating_sub(line_start)
            .min(content.end.saturating_sub(content.start));
        doc.cursor = doc.buffer.floor_char_boundary(target_start + column);
        to_u32(doc.cursor)
    }

    /// Returns up to `max_lines` consecutive lines starting at `first_line`.
    ///
    /// `first_line` is clamped to the document, and `max_lines` to `1..=10000`.
    /// One call returns a whole viewport, so Kotlin never loops over lines.
    pub fn viewport(&self, first_line: u32, max_lines: u32) -> EditorViewport {
        let doc = self.lock();
        let line_count = doc.buffer.line_count();
        let first = (first_line as usize).min(line_count.saturating_sub(1));
        let count = (max_lines.clamp(1, MAX_VIEWPORT_LINES) as usize).min(line_count - first);
        let lines = doc
            .buffer
            .lines(first, count)
            .into_iter()
            .map(|line| EditorLine {
                index: to_u32(line.index),
                start_byte: to_u32(line.start_byte),
                text: doc.buffer.slice_to_string(line.content).unwrap_or_default(),
            })
            .collect();
        EditorViewport {
            version: doc.buffer.version(),
            line_count: to_u32(line_count),
            first_line: to_u32(first),
            lines,
            cursor: doc.cursor_info(),
        }
    }

    /// Returns up to `max_utf16_units` UTF-16 code units of text immediately before
    /// the caret. Used by `InputConnection.getTextBeforeCursor`.
    pub fn context_before_cursor(&self, max_utf16_units: u32) -> String {
        let doc = self.lock();
        doc.buffer
            .utf16_context_before(doc.cursor, max_utf16_units as usize)
            .unwrap_or_default()
    }

    /// Returns up to `max_utf16_units` UTF-16 code units of text immediately after
    /// the caret. Used by `InputConnection.getTextAfterCursor`.
    pub fn context_after_cursor(&self, max_utf16_units: u32) -> String {
        let doc = self.lock();
        doc.buffer
            .utf16_context_after(doc.cursor, max_utf16_units as usize)
            .unwrap_or_default()
    }
}

impl EditorSession {
    fn lock(&self) -> MutexGuard<'_, Doc> {
        // A panic while holding the lock must not poison the session for the app.
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session_with(text: &str) -> Arc<EditorSession> {
        let session = EditorSession::new();
        session.move_cursor(0);
        let version = session.insert_text(text.to_string());
        assert!(version > 1);
        // Put the caret back at the end of the inserted text.
        session.move_cursor(text.len() as u32);
        session
    }

    #[test]
    fn empty_document_has_one_line_and_caret_at_zero() {
        let session = EditorSession::new();
        assert_eq!(session.text(), "");
        assert_eq!(session.version(), 1);
        assert_eq!(session.line_count(), 1);
        assert_eq!(
            session.cursor(),
            EditorCursor {
                byte_offset: 0,
                line: 0,
                column_bytes: 0
            }
        );
        let viewport = session.viewport(0, 10);
        assert_eq!(viewport.line_count, 1);
        assert_eq!(viewport.lines.len(), 1);
        assert_eq!(viewport.lines[0].text, "");
    }

    #[test]
    fn insert_returns_new_version_and_advances_caret() {
        let session = EditorSession::new();
        let v1 = session.version();
        let v2 = session.insert_text("hello".to_string());
        assert!(v2 > v1);
        assert_eq!(session.text(), "hello");
        assert_eq!(session.cursor().byte_offset, 5);
        let v3 = session.insert_text(" world".to_string());
        assert!(v3 > v2);
        assert_eq!(session.text(), "hello world");
        assert_eq!(session.cursor().byte_offset, 11);
    }

    #[test]
    fn multi_byte_insert_and_delete_use_char_boundaries() {
        let session = session_with("a中文🙂");
        assert_eq!(session.cursor().byte_offset, session.text().len() as u32);
        let v = session.delete_backward();
        assert_eq!(session.text(), "a中文");
        assert_eq!(session.cursor().byte_offset, "a中文".len() as u32);
        assert_eq!(session.version(), v);
        session.delete_backward();
        assert_eq!(session.text(), "a中");
        session.move_cursor(1);
        session.delete_forward();
        assert_eq!(session.text(), "a");
    }

    #[test]
    fn delete_at_document_edges_is_noop() {
        let session = EditorSession::new();
        let version = session.version();
        assert_eq!(session.delete_backward(), version);
        assert_eq!(session.delete_forward(), version);
        assert_eq!(session.text(), "");
    }

    #[test]
    fn replace_range_places_caret_after_insertion() {
        let session = session_with("hello world");
        let start = "hello ".len() as u32;
        let end = "hello world".len() as u32;
        let result = session.replace_range(start, end, "中文".to_string());
        assert!(result.is_ok(), "valid range must be accepted: {result:?}");
        let version = result.unwrap_or_default();
        assert_eq!(session.text(), "hello 中文");
        assert_eq!(session.cursor().byte_offset, session.text().len() as u32);
        assert_eq!(session.version(), version);
    }

    #[test]
    fn replace_range_rejects_invalid_offsets() {
        let session = session_with("中");
        let invalid = session.replace_range(1, 3, "x".to_string());
        assert!(matches!(
            invalid,
            Err(EditorError::NotCharBoundary { offset: 1 })
        ));
        let out_of_bounds = session.replace_range(0, 99, "x".to_string());
        assert!(matches!(
            out_of_bounds,
            Err(EditorError::OutOfBounds { .. })
        ));
        let reversed = session.replace_range(3, 0, "x".to_string());
        assert!(matches!(reversed, Err(EditorError::InvalidRange { .. })));
        // The document must be untouched after rejected edits.
        assert_eq!(session.text(), "中");
    }

    #[test]
    fn move_cursor_clamps_and_snaps_to_char_boundary() {
        let session = session_with("a中b");
        assert_eq!(session.move_cursor(2), 1);
        assert_eq!(session.move_cursor(999), 5);
        // Offset 3 is inside the 3-byte CJK character and snaps down to its start.
        assert_eq!(session.move_cursor(3), 1);
    }

    #[test]
    fn horizontal_movement_counts_scalar_values() {
        let session = session_with("a中🙂b");
        session.move_cursor(0);
        assert_eq!(session.move_cursor_horizontal(2), "a中".len() as u32);
        assert_eq!(session.move_cursor_horizontal(1), "a中🙂".len() as u32);
        assert_eq!(session.move_cursor_horizontal(-2), "a".len() as u32);
        assert_eq!(session.move_cursor_horizontal(-50), 0);
        assert_eq!(session.move_cursor_horizontal(50), "a中🙂b".len() as u32);
    }

    #[test]
    fn vertical_movement_preserves_column_and_clamps() {
        let session = session_with("abcdef\nab\nabcdef");
        session.move_cursor(4); // line 0, column 4
        assert_eq!(session.move_cursor_vertical(1), "abcdef\n".len() as u32 + 2);
        let cursor = session.cursor();
        assert_eq!((cursor.line, cursor.column_bytes), (1, 2));
        let end = session.move_cursor_vertical(10);
        assert_eq!(end, "abcdef\nab\n".len() as u32 + 2);
        assert_eq!(session.move_cursor_vertical(-10), 2);
    }

    #[test]
    fn viewport_reports_lines_and_start_offsets() {
        let session = session_with("one\ntwo\r\nthree\n");
        let viewport = session.viewport(0, 10);
        assert_eq!(viewport.line_count, 4);
        assert_eq!(viewport.first_line, 0);
        let texts: Vec<_> = viewport
            .lines
            .iter()
            .map(|line| line.text.as_str())
            .collect();
        assert_eq!(texts, vec!["one", "two", "three", ""]);
        assert_eq!(viewport.lines[1].start_byte, 4);
        assert_eq!(viewport.lines[2].start_byte, 9);
        assert_eq!(viewport.lines[3].start_byte, 15);

        let tail = session.viewport(2, 1);
        assert_eq!(tail.first_line, 2);
        assert_eq!(tail.lines.len(), 1);
        assert_eq!(tail.lines[0].text, "three");

        let clamped = session.viewport(99, 10);
        assert_eq!(clamped.first_line, 3);
        assert_eq!(clamped.lines.len(), 1);
        assert_eq!(clamped.lines[0].text, "");
    }

    #[test]
    fn viewport_is_tied_to_version() {
        let session = session_with("a");
        let before = session.viewport(0, 1);
        session.insert_text("b".to_string());
        let after = session.viewport(0, 1);
        assert!(after.version > before.version);
        assert_eq!(after.lines[0].text, "ab");
    }

    #[test]
    fn contexts_use_utf16_units() {
        let session = session_with("a中🙂b");
        // Caret sits after "b" because the helper moved it to the end.
        assert_eq!(session.cursor_utf16_offset(), 5);
        assert_eq!(session.context_before_cursor(1), "b");
        assert_eq!(session.context_before_cursor(2), "b");
        assert_eq!(session.context_before_cursor(4), "中🙂b");
        assert_eq!(session.context_before_cursor(5), "a中🙂b");
        assert_eq!(session.context_before_cursor(0), "");

        session.move_cursor(1); // between "a" and "中"
        assert_eq!(session.cursor_utf16_offset(), 1);
        assert_eq!(session.context_before_cursor(10), "a");
        assert_eq!(session.context_after_cursor(1), "中");
        assert_eq!(session.context_after_cursor(3), "中🙂");
        assert_eq!(session.context_after_cursor(99), "中🙂b");
        assert_eq!(session.context_after_cursor(0), "");
    }

    #[test]
    fn cursor_moves_do_not_change_version() {
        let session = session_with("abc");
        let version = session.version();
        session.move_cursor(1);
        session.move_cursor_horizontal(1);
        session.move_cursor_vertical(1);
        assert_eq!(session.version(), version);
    }

    #[test]
    fn empty_edits_do_not_change_the_version() {
        // Deliberate change from M1.0 (T-0007): no-op edits no longer bump the
        // version, so Kotlin does not redraw for nothing.
        let session = session_with("abc");
        let version = session.version();
        assert_eq!(session.insert_text(String::new()), version);
        let replaced = session.replace_range(1, 1, String::new());
        assert!(
            replaced.is_ok(),
            "empty replace must be accepted: {replaced:?}"
        );
        assert_eq!(replaced.unwrap_or_default(), version);
        assert_eq!(session.delete_range(2, 2).unwrap_or_default(), version);
        assert_eq!(session.text(), "abc");
        assert_eq!(session.version(), version);
    }

    fn format(bom: bool, line_ending: LineEnding) -> DocumentFormat {
        DocumentFormat { bom, line_ending }
    }

    #[test]
    fn encode_empty_document() {
        let session = EditorSession::new();
        let plain = session.encode(format(false, LineEnding::Lf));
        assert!(plain.bytes.is_empty());
        assert_eq!(plain.version, session.version());

        let bom = session.encode(format(true, LineEnding::Lf));
        assert_eq!(bom.bytes, [0xEF, 0xBB, 0xBF]);
    }

    #[test]
    fn encode_uses_bom_and_crlf() {
        let session = session_with("a\nb\n");
        let encoded = session.encode(format(true, LineEnding::CrLf));
        assert_eq!(
            encoded.bytes,
            [0xEF, 0xBB, 0xBF, b'a', b'\r', b'\n', b'b', b'\r', b'\n']
        );
        assert_eq!(encoded.version, session.version());
    }

    #[test]
    fn encode_does_not_change_document_state() {
        let session = session_with("a\n中🙂");
        session.move_cursor(2);
        let text = session.text();
        let cursor = session.cursor();
        let version = session.version();

        let encoded = session.encode(format(true, LineEnding::CrLf));

        assert_eq!(encoded.version, version);
        assert_eq!(session.text(), text);
        assert_eq!(session.cursor(), cursor);
        assert_eq!(session.version(), version);
    }

    #[test]
    fn encode_version_matches_the_encoded_text() {
        let session = session_with("old");
        let first = session.encode(format(false, LineEnding::Lf));
        assert_eq!(first.bytes, b"old");

        session.insert_text(" new".to_string());
        let second = session.encode(format(false, LineEnding::Lf));
        assert!(second.version > first.version);
        assert_eq!(first.bytes, b"old");
        assert_eq!(second.bytes, b"old new");
    }

    #[test]
    fn format_mapping_round_trips() {
        for bom in [false, true] {
            for line_ending in [LineEnding::Lf, LineEnding::CrLf] {
                let ffi = format(bom, line_ending);
                let codec_format: codec::DocumentFormat = ffi.into();
                assert_eq!(DocumentFormat::from(codec_format), ffi);
            }
        }
        assert_eq!(
            codec::LineEnding::from(LineEnding::CrLf),
            codec::LineEnding::CrLf
        );
        assert_eq!(LineEnding::from(codec::LineEnding::Lf), LineEnding::Lf);
    }

    #[test]
    fn undo_and_redo_restore_text_and_caret() {
        let session = EditorSession::new();
        assert_eq!(session.undo(), None);
        assert_eq!(session.redo(), None);
        assert!(!session.can_undo());
        assert!(!session.can_redo());
        let version = session.version();
        assert_eq!(session.undo(), None);
        assert_eq!(session.version(), version);

        session.insert_text("ab".to_string());
        session.end_composition();
        session.move_cursor(0);
        let inserted_version = session.insert_text("X".to_string());
        assert_eq!(session.text(), "Xab");
        session.move_cursor(2);

        let undo_version = session.undo();
        assert!(undo_version.is_some_and(|version| version > inserted_version));
        assert_eq!(session.text(), "ab");
        // The group recorded the caret before its edit (offset 0).
        assert_eq!(session.cursor().byte_offset, 0);
        assert!(session.can_redo());
        assert!(session.can_undo());

        let redo_version = session.redo();
        assert!(redo_version.is_some_and(|version| version > inserted_version));
        assert_eq!(session.text(), "Xab");
        // Redo puts the caret just past the redone text.
        assert_eq!(session.cursor().byte_offset, 1);
        assert!(!session.can_redo());

        // Version is unchanged when there is nothing to undo.
        let version = session.version();
        session.move_cursor(0);
        session.insert_text("Q".to_string());
        session.undo();
        session.redo();
        assert_eq!(session.text(), "QXab");
        assert!(session.version() > version);
    }

    #[test]
    fn undo_then_new_edit_clears_redo() {
        let session = EditorSession::new();
        session.insert_text("a".to_string());
        session.end_composition();
        session.insert_text("b".to_string());
        session.end_composition();
        assert!(session.undo().is_some());
        assert_eq!(session.text(), "a");
        assert!(session.can_redo());

        session.insert_text("c".to_string());
        assert_eq!(session.text(), "ac");
        assert!(!session.can_redo());
        assert!(session.undo().is_some());
        assert_eq!(session.text(), "a");
    }

    #[test]
    fn a_composition_sequence_undoes_as_one_word() {
        let session = EditorSession::new();
        session.begin_composition();
        session.begin_composition();
        let _ = session.replace_range(0, 0, "n".to_string());
        let _ = session.replace_range(1, 1, "i".to_string());
        let _ = session.replace_range(2, 2, "h".to_string());
        let _ = session.replace_range(3, 3, "a".to_string());
        let _ = session.replace_range(4, 4, "o".to_string());
        // Commit: replace the whole composing span with the chosen word.
        let _ = session.replace_range(0, 5, "你好".to_string());
        session.end_composition();
        assert_eq!(session.text(), "你好");
        assert_eq!(session.cursor().byte_offset, "你好".len() as u32);

        let version = session.undo();
        assert!(version.is_some());
        assert_eq!(session.text(), "");
        assert_eq!(session.cursor().byte_offset, 0);
        assert!(!session.can_undo());
    }

    #[test]
    fn cancelled_composition_leaves_no_undo_step() {
        let session = EditorSession::new();
        session.begin_composition();
        let _ = session.replace_range(0, 0, "ni".to_string());
        let _ = session.replace_range(0, 2, String::new());
        session.end_composition();
        assert_eq!(session.text(), "");
        assert!(!session.can_undo());
        assert_eq!(session.undo(), None);
    }

    #[test]
    fn clear_history_makes_the_current_text_the_base() {
        let session = EditorSession::new();
        session.insert_text("abc".to_string());
        let version = session.version();
        session.clear_history();
        assert!(!session.can_undo());
        assert!(!session.can_redo());
        assert_eq!(session.undo(), None);
        assert_eq!(session.version(), version);
        assert_eq!(session.text(), "abc");
    }

    #[test]
    fn undo_and_redo_a_megabyte_group() {
        let session = EditorSession::new();
        let text = "a".repeat(1024 * 1024);
        session.insert_text(text.clone());
        session.end_composition();
        assert_eq!(session.text().len(), text.len());
        assert!(session.undo().is_some());
        assert_eq!(session.text(), "");
        assert_eq!(session.cursor().byte_offset, 0);
        assert!(session.redo().is_some());
        assert_eq!(session.text(), text);
        assert_eq!(session.cursor().byte_offset, text.len() as u32);
    }

    /// A6: every T-0009 fixture round-trips through
    /// `open -> encode(format())`. Mixed line endings are excluded by
    /// Q-T9-2, so all twelve fixtures here have uniform endings.
    #[test]
    fn a6_fixtures_round_trip_through_the_session() {
        const UTF8_BOM: &[u8] = &[0xEF, 0xBB, 0xBF];
        let mut bom_lf = UTF8_BOM.to_vec();
        bom_lf.extend_from_slice(b"line one\nline two\n");
        let mut bom_crlf = UTF8_BOM.to_vec();
        bom_crlf.extend_from_slice(b"line one\r\nline two\r\n");
        let mut bom_emoji = UTF8_BOM.to_vec();
        bom_emoji.extend_from_slice("中文🙂\r\n日本語🚀\r\n".as_bytes());

        let fixtures: [(&str, &[u8]); 12] = [
            ("utf8_lf", b"line one\nline two\n"),
            ("utf8_crlf", b"line one\r\nline two\r\n"),
            ("bom_lf", &bom_lf),
            ("bom_crlf", &bom_crlf),
            ("bom_cjk_and_emoji", &bom_emoji),
            ("no_trailing_newline", b"line one\r\nline two"),
            ("empty", b""),
            ("only_lf", b"\n\n\n"),
            ("only_crlf", b"\r\n\r\n\r\n"),
            ("lone_cr", b"a\rb\r"),
            ("cjk_and_emoji", "中文🙂\r\n🙂🙂\r\n".as_bytes()),
            ("cr_before_crlf", b"a\r\r\nb\r\n"),
        ];
        for (name, bytes) in fixtures {
            let session = EditorSession::new();
            let opened = open_ok(&session, bytes);
            let encoded = session.encode(session.format());
            assert_eq!(encoded.version, opened.version);
            assert_eq!(
                encoded.bytes, bytes,
                "fixture {name} did not round-trip through the session"
            );
            // The format is the decoded one, and it is what `format()` reports.
            assert_eq!(opened.format, session.format());
        }
    }

    // Test helper: fails the test if opening unexpectedly errors.
    #[allow(clippy::panic)]
    fn open_ok(session: &EditorSession, bytes: &[u8]) -> OpenedDocument {
        match session.open(bytes.to_vec()) {
            Ok(opened) => opened,
            Err(error) => panic!("open failed: {error}"),
        }
    }

    #[test]
    fn a_new_session_is_an_empty_untitled_document() {
        let session = EditorSession::new();
        assert_eq!(session.format(), format(false, LineEnding::Lf));
        assert_eq!(session.text(), "");
        assert_eq!(session.line_count(), 1);
        assert_eq!(session.cursor().byte_offset, 0);
        assert!(!session.can_undo());
        assert!(!session.can_redo());
    }

    #[test]
    fn open_normalizes_crlf_and_strips_the_bom() {
        let session = EditorSession::new();
        let opened = open_ok(&session, b"\xEF\xBB\xBFone\r\ntwo\r\n");
        assert_eq!(session.text(), "one\ntwo\n");
        assert_eq!(opened.line_count, 3);
        assert_eq!(session.line_count(), 3);
        assert_eq!(opened.format, format(true, LineEnding::CrLf));
        assert_eq!(session.format(), format(true, LineEnding::CrLf));
        assert_eq!(opened.cursor, session.cursor());
        assert_eq!(opened.cursor.byte_offset, 0);
        assert_eq!((opened.cursor.line, opened.cursor.column_bytes), (0, 0));
        assert_eq!(opened.version, session.version());
    }

    #[test]
    fn open_reports_lf_empty_and_unterminated_files() {
        let session = EditorSession::new();
        let opened = open_ok(&session, b"one\ntwo");
        assert_eq!(session.text(), "one\ntwo");
        assert_eq!(opened.line_count, 2);
        assert_eq!(opened.format, format(false, LineEnding::Lf));

        let opened = open_ok(&session, b"");
        assert_eq!(session.text(), "");
        assert_eq!(opened.line_count, 1);
        assert_eq!(opened.format, format(false, LineEnding::Lf));
        let viewport = session.viewport(0, 10);
        assert_eq!(viewport.line_count, 1);
        assert_eq!(viewport.lines[0].text, "");

        // A file without a trailing newline has no extra empty last line.
        let opened = open_ok(&session, b"a\nb");
        assert_eq!(opened.line_count, 2);
        assert_eq!(session.viewport(0, 10).lines[1].text, "b");
    }

    #[test]
    fn open_discards_edits_history_and_caret() {
        let session = EditorSession::new();
        session.insert_text("draft".to_string());
        session.end_composition();
        let before = session.version();
        assert!(session.can_undo());
        session.move_cursor(2);

        let opened = open_ok(&session, b"loaded\nfile\n");
        assert!(opened.version > before);
        assert_eq!(opened.version, session.version());
        assert_eq!(session.text(), "loaded\nfile\n");
        assert_eq!(session.cursor().byte_offset, 0);
        assert!(!session.can_undo(), "opening must not be undoable");
        assert!(!session.can_redo());
        assert_eq!(session.undo(), None);
        assert_eq!(session.redo(), None);
    }

    #[test]
    fn opening_identical_bytes_still_bumps_the_version() {
        let session = EditorSession::new();
        let first = open_ok(&session, b"same\n");
        let second = open_ok(&session, b"same\n");
        assert_eq!(second.version, first.version + 1);
        assert_eq!(session.version(), second.version);
        assert_eq!(session.text(), "same\n");
    }

    #[test]
    fn open_reports_the_offset_of_invalid_utf8() {
        let session = EditorSession::new();
        let result = session.open(b"ok\xFFbad".to_vec());
        assert!(
            matches!(result, Err(EditorError::NotUtf8 { offset: 2 })),
            "expected NotUtf8 at offset 2, got {result:?}"
        );
    }

    #[test]
    fn open_rejects_unsupported_encodings_and_binary() {
        let session = EditorSession::new();
        let cases: [(&[u8], UnsupportedEncodingKind); 5] = [
            (&[0xFF, 0xFE, b'a', 0x00], UnsupportedEncodingKind::Utf16Le),
            (&[0xFE, 0xFF, 0x00, b'a'], UnsupportedEncodingKind::Utf16Be),
            (&[0xFF, 0xFE, 0x00, 0x00], UnsupportedEncodingKind::Utf32Le),
            (&[0x00, 0x00, 0xFE, 0xFF], UnsupportedEncodingKind::Utf32Be),
            (
                b"binary\x00data",
                UnsupportedEncodingKind::Binary { nul_offset: 6 },
            ),
        ];
        for (bytes, expected) in cases {
            let kind = match session.open(bytes.to_vec()) {
                Err(EditorError::UnsupportedEncoding { kind }) => Some(kind),
                _ => None,
            };
            assert_eq!(kind, Some(expected), "input {expected:?} must be rejected");
        }
    }

    #[test]
    fn failed_open_keeps_text_format_caret_version_and_history() {
        let session = EditorSession::new();
        open_ok(&session, b"\xEF\xBB\xBFkeep\r\nme\r\n");
        session.end_composition();
        session.insert_text("!".to_string());
        session.end_composition();
        session.move_cursor(1);

        let text = session.text();
        let format = session.format();
        let cursor = session.cursor();
        let version = session.version();
        assert!(session.can_undo());

        for bad in [
            b"\xFF".to_vec(),
            vec![0xFF, 0xFE, 0x00, 0x00],
            b"nul\x00".to_vec(),
        ] {
            assert!(session.open(bad).is_err());
            assert_eq!(session.text(), text);
            assert_eq!(session.format(), format);
            assert_eq!(session.cursor(), cursor);
            assert_eq!(session.version(), version);
            assert!(session.can_undo());
        }

        // The history still belongs to the document that was open before.
        assert_eq!(session.undo(), Some(version + 1));
    }

    #[test]
    fn open_handles_a_megabyte_document() {
        let session = EditorSession::new();
        let text = "a\n中🙂".repeat(256 * 1024);
        let opened = open_ok(&session, text.as_bytes());
        assert_eq!(opened.line_count as usize, 256 * 1024 + 1);
        assert_eq!(session.text(), text);
        assert_eq!(session.viewport(0, 2).lines[0].text, "a");
    }
}
