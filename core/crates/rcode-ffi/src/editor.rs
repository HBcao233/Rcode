//! Minimal in-memory editing session for M1.0 (T-0027).
//!
//! The document is stored in a temporary `String` implementation; T-0007 replaces
//! it with a rope-backed buffer. All offsets exposed through this module are
//! **UTF-8 byte offsets** unless the name or documentation says otherwise.
//!
//! The session is the authoritative owner of text and caret. Kotlin only caches
//! the viewport returned by [`EditorSession::viewport`] for rendering.

use std::sync::{Arc, Mutex, MutexGuard};

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

struct Doc {
    text: String,
    /// Caret as a UTF-8 byte offset; always on a character boundary.
    cursor: usize,
    version: u64,
}

impl Doc {
    fn new() -> Self {
        Self {
            text: String::new(),
            cursor: 0,
            version: 1,
        }
    }

    fn bump_version(&mut self) {
        self.version = self.version.wrapping_add(1);
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
        self.lock().text.clone()
    }

    /// Current document version. Increments on every text change, not on caret moves.
    pub fn version(&self) -> u64 {
        self.lock().version
    }

    /// Number of logical lines (always at least 1).
    pub fn line_count(&self) -> u32 {
        line_starts(&self.lock().text).len() as u32
    }

    /// Current caret position.
    pub fn cursor(&self) -> EditorCursor {
        let doc = self.lock();
        cursor_info(&doc, &line_starts(&doc.text))
    }

    /// Caret position measured in UTF-16 code units (the unit used by Android IME APIs).
    pub fn cursor_utf16_offset(&self) -> u32 {
        let doc = self.lock();
        doc.text[..doc.cursor].encode_utf16().count() as u32
    }

    /// Inserts `text` at the caret and places the caret after it.
    ///
    /// Returns the new document version.
    pub fn insert_text(&self, text: String) -> u64 {
        let mut doc = self.lock();
        let at = doc.cursor;
        doc.text.insert_str(at, &text);
        doc.cursor = at + text.len();
        doc.bump_version();
        doc.version
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
        let (start, end) = validate_range(&doc.text, start_byte, end_byte)?;
        doc.text.replace_range(start..end, &text);
        doc.cursor = start + text.len();
        doc.bump_version();
        Ok(doc.version)
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
        let Some(prev) = prev_char_boundary(&doc.text, cursor) else {
            return doc.version;
        };
        doc.text.replace_range(prev..cursor, "");
        doc.cursor = prev;
        doc.bump_version();
        doc.version
    }

    /// Deletes the character after the caret. No-op at the end of the document.
    /// Returns the new document version.
    pub fn delete_forward(&self) -> u64 {
        let mut doc = self.lock();
        let cursor = doc.cursor;
        let Some(next) = next_char_boundary(&doc.text, cursor) else {
            return doc.version;
        };
        doc.text.replace_range(cursor..next, "");
        doc.bump_version();
        doc.version
    }

    /// Moves the caret to `byte_offset`, clamped to the document and snapped down to
    /// the nearest UTF-8 character boundary. Returns the resulting byte offset.
    pub fn move_cursor(&self, byte_offset: u32) -> u32 {
        let mut doc = self.lock();
        doc.cursor =
            floor_char_boundary(&doc.text, byte_offset.min(doc.text.len() as u32) as usize);
        doc.cursor as u32
    }

    /// Moves the caret by `delta_chars` Unicode scalar values (negative moves left).
    /// Returns the resulting byte offset.
    pub fn move_cursor_horizontal(&self, delta_chars: i32) -> u32 {
        let mut doc = self.lock();
        let target = if delta_chars >= 0 {
            let mut cursor = doc.cursor;
            for _ in 0..delta_chars {
                let Some(next) = next_char_boundary(&doc.text, cursor) else {
                    break;
                };
                cursor = next;
            }
            cursor
        } else {
            let mut cursor = doc.cursor;
            for _ in 0..delta_chars.unsigned_abs() {
                let Some(prev) = prev_char_boundary(&doc.text, cursor) else {
                    break;
                };
                cursor = prev;
            }
            cursor
        };
        doc.cursor = target;
        doc.cursor as u32
    }

    /// Moves the caret by `delta_lines`, keeping the byte column when possible and
    /// clamping to the target line length. Returns the resulting byte offset.
    pub fn move_cursor_vertical(&self, delta_lines: i32) -> u32 {
        let mut doc = self.lock();
        let starts = line_starts(&doc.text);
        let line = line_of_offset(&starts, doc.cursor);
        let column = doc.cursor - starts[line];
        let target =
            (line as i64 + i64::from(delta_lines)).clamp(0, starts.len() as i64 - 1) as usize;
        let start = starts[target];
        let content_end = line_content_end(&doc.text, &starts, target);
        let max_column = content_end - start;
        let column = column.min(max_column);
        doc.cursor = start + floor_char_boundary(&doc.text[start..content_end], column);
        doc.cursor as u32
    }

    /// Returns up to `max_lines` consecutive lines starting at `first_line`.
    ///
    /// `first_line` is clamped to the document, and `max_lines` to `1..=10000`.
    /// One call returns a whole viewport, so Kotlin never loops over lines.
    pub fn viewport(&self, first_line: u32, max_lines: u32) -> EditorViewport {
        let doc = self.lock();
        let starts = line_starts(&doc.text);
        let line_count = starts.len() as u32;
        let first = first_line.min(line_count.saturating_sub(1));
        let count = max_lines
            .clamp(1, MAX_VIEWPORT_LINES)
            .min(line_count - first);
        let lines = (first..first + count)
            .map(|index| {
                let start = starts[index as usize];
                let end = line_content_end(&doc.text, &starts, index as usize);
                EditorLine {
                    index,
                    start_byte: start as u32,
                    text: doc.text[start..end].to_string(),
                }
            })
            .collect();
        EditorViewport {
            version: doc.version,
            line_count,
            first_line: first,
            lines,
            cursor: cursor_info(&doc, &starts),
        }
    }

    /// Returns up to `max_utf16_units` UTF-16 code units of text immediately before
    /// the caret. Used by `InputConnection.getTextBeforeCursor`.
    pub fn context_before_cursor(&self, max_utf16_units: u32) -> String {
        let doc = self.lock();
        utf16_suffix(&doc.text[..doc.cursor], max_utf16_units as usize).to_string()
    }

    /// Returns up to `max_utf16_units` UTF-16 code units of text immediately after
    /// the caret. Used by `InputConnection.getTextAfterCursor`.
    pub fn context_after_cursor(&self, max_utf16_units: u32) -> String {
        let doc = self.lock();
        utf16_prefix(&doc.text[doc.cursor..], max_utf16_units as usize).to_string()
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

/// Byte offsets of the first character of every logical line. Always contains `0`,
/// plus one entry after each `\n`; an empty document has exactly one line.
fn line_starts(text: &str) -> Vec<usize> {
    let mut starts = Vec::with_capacity(8);
    starts.push(0);
    for (index, byte) in text.bytes().enumerate() {
        if byte == b'\n' {
            starts.push(index + 1);
        }
    }
    starts
}

/// Zero-based line index containing `offset`.
fn line_of_offset(starts: &[usize], offset: usize) -> usize {
    match starts.binary_search(&offset) {
        Ok(index) => index,
        Err(index) => index - 1,
    }
}

/// End of the visible content of `line` (the next line start minus the newline and,
/// for CRLF, the preceding carriage return).
fn line_content_end(text: &str, starts: &[usize], line: usize) -> usize {
    let Some(&next) = starts.get(line + 1) else {
        return text.len();
    };
    let mut end = next - 1;
    if end > starts[line] && text.as_bytes()[end - 1] == b'\r' {
        end -= 1;
    }
    end
}

fn cursor_info(doc: &Doc, starts: &[usize]) -> EditorCursor {
    let line = line_of_offset(starts, doc.cursor);
    EditorCursor {
        byte_offset: doc.cursor as u32,
        line: line as u32,
        column_bytes: (doc.cursor - starts[line]) as u32,
    }
}

/// Largest character boundary at or below `offset`.
fn floor_char_boundary(text: &str, offset: usize) -> usize {
    let mut offset = offset.min(text.len());
    while offset > 0 && !text.is_char_boundary(offset) {
        offset -= 1;
    }
    offset
}

fn prev_char_boundary(text: &str, offset: usize) -> Option<usize> {
    text.get(..offset)?
        .chars()
        .next_back()
        .map(|c| offset - c.len_utf8())
}

fn next_char_boundary(text: &str, offset: usize) -> Option<usize> {
    text.get(offset..)?
        .chars()
        .next()
        .map(|c| offset + c.len_utf8())
}

fn validate_range(
    text: &str,
    start_byte: u32,
    end_byte: u32,
) -> Result<(usize, usize), EditorError> {
    let start = start_byte as usize;
    let end = end_byte as usize;
    if start > end || end > text.len() {
        return Err(EditorError::InvalidRange {
            start: start_byte,
            end: end_byte,
        });
    }
    if !text.is_char_boundary(start) {
        return Err(EditorError::InvalidOffset { offset: start_byte });
    }
    if !text.is_char_boundary(end) {
        return Err(EditorError::InvalidOffset { offset: end_byte });
    }
    Ok((start, end))
}

/// Suffix of `text` containing at most `max_units` UTF-16 code units.
fn utf16_suffix(text: &str, max_units: usize) -> &str {
    let mut units = 0usize;
    let mut start = text.len();
    for (index, character) in text.char_indices().rev() {
        let width = character.len_utf16();
        if units + width > max_units {
            break;
        }
        units += width;
        start = index;
    }
    &text[start..]
}

/// Prefix of `text` containing at most `max_units` UTF-16 code units.
fn utf16_prefix(text: &str, max_units: usize) -> &str {
    let mut units = 0usize;
    let mut end = 0usize;
    for (index, character) in text.char_indices() {
        let width = character.len_utf16();
        if units + width > max_units {
            break;
        }
        units += width;
        end = index + character.len_utf8();
    }
    &text[..end]
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
}
