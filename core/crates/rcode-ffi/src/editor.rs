//! Editing session for the M1 editor, backed by `rcode-buffer` (rope text).
//!
//! The document text lives in a [`TextBuffer`], so viewport, line and offset
//! queries are `O(log n)` instead of scanning the whole text. All offsets exposed
//! through this module are **UTF-8 byte offsets** unless the name or
//! documentation says otherwise.
//!
//! The session is the authoritative owner of text and caret. Kotlin only caches
//! the viewport returned by [`EditorSession::viewport`] for rendering.

use std::sync::{Arc, Mutex, MutexGuard};

use rcode_buffer::{BufferError, TextBuffer};
use thiserror::Error;

/// Upper bound for one viewport request, so a bad caller cannot allocate without limit.
const MAX_VIEWPORT_LINES: u32 = 10_000;

/// Errors returned by editing-session operations.
#[derive(Debug, Error, uniffi::Error)]
pub enum EditorError {
    /// A byte offset was not on a UTF-8 character boundary.
    #[error("byte offset {offset} is not on a UTF-8 character boundary")]
    InvalidOffset {
        /// Offending UTF-8 byte offset.
        offset: u32,
    },
    /// The requested byte range was reversed or outside the document.
    #[error("byte range {start}..{end} is invalid")]
    InvalidRange {
        /// Range start in UTF-8 bytes.
        start: u32,
        /// Range end in UTF-8 bytes (exclusive).
        end: u32,
    },
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

/// Maps a buffer error for a range operation to the FFI error, keeping the
/// range the caller passed in the `InvalidRange` payload.
fn range_error(start: usize, end: usize, error: BufferError) -> EditorError {
    match error {
        BufferError::NotCharBoundary { offset } => EditorError::InvalidOffset {
            offset: to_u32(offset),
        },
        _ => EditorError::InvalidRange {
            start: to_u32(start),
            end: to_u32(end),
        },
    }
}

impl From<BufferError> for EditorError {
    fn from(error: BufferError) -> Self {
        match error {
            BufferError::NotCharBoundary { offset } => EditorError::InvalidOffset {
                offset: to_u32(offset),
            },
            BufferError::InvalidRange { start, end } => EditorError::InvalidRange {
                start: to_u32(start),
                end: to_u32(end),
            },
            BufferError::OutOfBounds { offset, .. } => EditorError::InvalidRange {
                start: to_u32(offset),
                end: to_u32(offset),
            },
            BufferError::LineOutOfBounds { .. } => EditorError::InvalidRange { start: 0, end: 0 },
        }
    }
}

struct Doc {
    /// Authoritative text; owns the version number.
    buffer: TextBuffer,
    /// Caret as a UTF-8 byte offset; always on a character boundary.
    cursor: usize,
}

impl Doc {
    fn new() -> Self {
        Self {
            buffer: TextBuffer::new(),
            cursor: 0,
        }
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

    /// Returns the full document text. Intended for tests and diagnostics; the UI
    /// must use [`Self::viewport`] instead of caching a full copy.
    pub fn text(&self) -> String {
        self.lock().buffer.to_string()
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
        // `at` is always a valid character boundary, so this cannot fail.
        let Ok(edit) = doc.buffer.insert(at, &text) else {
            return doc.buffer.version();
        };
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
        let edit = doc
            .buffer
            .replace(start..end, &text)
            .map_err(|error| range_error(start, end, error))?;
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
        let Some(previous) = doc.buffer.prev_char_boundary(cursor) else {
            return doc.buffer.version();
        };
        match doc.buffer.delete(previous..cursor) {
            Ok(edit) => {
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
        let Some(next) = doc.buffer.next_char_boundary(cursor) else {
            return doc.buffer.version();
        };
        match doc.buffer.delete(cursor..next) {
            Ok(edit) => edit.version,
            // Both offsets are character boundaries, so this cannot fail.
            Err(_) => doc.buffer.version(),
        }
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
            Err(EditorError::InvalidOffset { offset: 1 })
        ));
        let out_of_bounds = session.replace_range(0, 99, "x".to_string());
        assert!(matches!(
            out_of_bounds,
            Err(EditorError::InvalidRange { .. })
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
}
