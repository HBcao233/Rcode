//! Rope-backed text buffer for Rcode.
//!
//! This crate owns the text of one document and the conversions between the
//! offset units the editor needs. It does not depend on UniFFI; `rcode-ffi`
//! wraps it for Kotlin.
//!
//! # Units
//!
//! | Name | Definition |
//! |---|---|
//! | byte offset | UTF-8 byte count from the start of the document (`usize`). Valid offsets are character boundaries. |
//! | char offset | Count of Unicode scalar values from the start of the document (`usize`). |
//! | UTF-16 offset | Count of UTF-16 code units from the start of the document. Only used for Android IME conversions. |
//! | line | Zero-based logical line index. Only `\n` is a line break. |
//!
//! A line's content runs from its start byte to the next `\n`, excluding the
//! newline and a `\r` directly before it (so CRLF files show the same content as
//! LF files). A lone `\r` is an ordinary character. The number of lines is the
//! number of `\n` bytes plus one; an empty document has exactly one line.
//!
//! # Versions
//!
//! A buffer starts at version 1. Every edit that actually changes the text
//! increments the version by exactly one. Queries, no-op edits (an empty range
//! replaced by an empty string) and rejected edits leave it unchanged.
//!
//! # Complexity
//!
//! All offset conversions, line lookups and single edits are `O(log n)`.
//! [`TextBuffer::lines`] and the UTF-16 context helpers are `O(log n + output)`.
//! Only [`Display`](std::fmt::Display) (full text) is `O(n)`.
//!
//! # Panics
//!
//! No public method panics for any input: every index is validated before it
//! reaches `ropey`, and only the non-panicking `ropey` APIs are called.

use std::fmt;
use std::ops::Range;

use ropey::Rope;
use thiserror::Error;

/// Errors returned by [`TextBuffer`].
///
/// No buffer method mutates the text when it returns an error.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum BufferError {
    /// An index was past the end of the document.
    ///
    /// `offset` and `len` are expressed in the unit of the failing argument:
    /// bytes for byte-based methods, chars for [`TextBuffer::char_to_byte`],
    /// UTF-16 code units for [`TextBuffer::utf16_to_byte`].
    #[error("offset {offset} is out of bounds for length {len}")]
    OutOfBounds {
        /// Rejected index.
        offset: usize,
        /// Document length in the same unit as `offset`.
        len: usize,
    },
    /// A byte offset fell inside a multi-byte UTF-8 sequence.
    #[error("byte offset {offset} is not on a UTF-8 character boundary")]
    NotCharBoundary {
        /// Rejected byte offset.
        offset: usize,
    },
    /// A byte range had `start > end`.
    #[error("byte range {start}..{end} is invalid")]
    InvalidRange {
        /// Range start in bytes.
        start: usize,
        /// Range end in bytes (exclusive).
        end: usize,
    },
    /// A line index was not in `0..line_count`.
    #[error("line {line} is out of bounds for a document with {line_count} lines")]
    LineOutOfBounds {
        /// Rejected zero-based line index.
        line: usize,
        /// Number of lines in the document (always at least 1).
        line_count: usize,
    },
}

/// Record of one successful edit, shaped for undo and incremental parsing.
///
/// `start_byte`, `new_end_byte` and `removed` describe the document *after* the
/// edit; `old_end_byte` refers to the document *before* the edit. The pre-edit
/// text can be rebuilt as
/// `new_text[..start_byte] + removed + new_text[new_end_byte..]`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Edit {
    /// Byte offset where the edit starts (unchanged by the edit).
    pub start_byte: usize,
    /// Byte offset just past the replaced text in the document before the edit.
    pub old_end_byte: usize,
    /// Byte offset just past the inserted text in the document after the edit.
    pub new_end_byte: usize,
    /// Text that was replaced, empty for a pure insertion.
    pub removed: String,
    /// Buffer version after the edit; equal to the previous version for a no-op.
    pub version: u64,
    /// `false` when the edit was a no-op and the text and version did not change.
    pub changed: bool,
}

/// One logical line, without its trailing `\n` (and preceding `\r`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BufferLine {
    /// Zero-based line index.
    pub index: usize,
    /// Byte offset of the first character of the line.
    pub start_byte: usize,
    /// Byte range of the line content, excluding the line terminator.
    pub content: Range<usize>,
}

/// A text document stored as a [rope](https://en.wikipedia.org/wiki/Rope_(data_structure)).
///
/// See the [crate documentation](crate) for the offset units and version rules.
#[derive(Debug, Clone)]
pub struct TextBuffer {
    rope: Rope,
    version: u64,
}

impl TextBuffer {
    /// Creates an empty document at version 1.
    pub fn new() -> Self {
        Self {
            rope: Rope::new(),
            version: 1,
        }
    }

    /// Creates a document containing `text` at version 1.
    // Named like the standard `FromStr` constructor used by `ropey`; the buffer
    // constructor is infallible, so it returns `Self` instead of a `Result`.
    #[allow(clippy::should_implement_trait)]
    pub fn from_str(text: &str) -> Self {
        Self {
            rope: Rope::from_str(text),
            version: 1,
        }
    }

    /// Current document version; starts at 1 and increments on every real edit.
    pub fn version(&self) -> u64 {
        self.version
    }

    /// Document length in UTF-8 bytes.
    pub fn len_bytes(&self) -> usize {
        self.rope.len_bytes()
    }

    /// Document length in Unicode scalar values.
    pub fn len_chars(&self) -> usize {
        self.rope.len_chars()
    }

    /// Document length in UTF-16 code units.
    pub fn len_utf16(&self) -> usize {
        self.rope.len_utf16_cu()
    }

    /// Number of logical lines (`\n` count + 1; never 0).
    pub fn line_count(&self) -> usize {
        self.rope.len_lines()
    }

    /// `true` when the document has no bytes.
    pub fn is_empty(&self) -> bool {
        self.len_bytes() == 0
    }

    // ---------------------------------------------------------------- edits

    /// Inserts `text` at byte offset `byte`.
    ///
    /// An empty `text` is a no-op: the returned [`Edit`] has `changed == false`
    /// and the version does not change.
    ///
    /// # Errors
    ///
    /// [`BufferError::OutOfBounds`] or [`BufferError::NotCharBoundary`] if `byte`
    /// is not a character boundary in `0..=len_bytes()`.
    pub fn insert(&mut self, byte: usize, text: &str) -> Result<Edit, BufferError> {
        self.check_byte(byte)?;
        if text.is_empty() {
            return Ok(self.noop(byte..byte));
        }
        let char_index = self.char_index(byte)?;
        self.rope
            .try_insert(char_index, text)
            .map_err(|error| self.map_error(error))?;
        self.bump_version();
        Ok(Edit {
            start_byte: byte,
            old_end_byte: byte,
            new_end_byte: byte + text.len(),
            removed: String::new(),
            version: self.version,
            changed: true,
        })
    }

    /// Deletes the byte range `range`.
    ///
    /// An empty range is a no-op: the returned [`Edit`] has `changed == false`
    /// and the version does not change.
    ///
    /// # Errors
    ///
    /// [`BufferError::InvalidRange`] if `start > end`,
    /// [`BufferError::OutOfBounds`] if `end > len_bytes()`, or
    /// [`BufferError::NotCharBoundary`] if an endpoint is not a character boundary.
    pub fn delete(&mut self, range: Range<usize>) -> Result<Edit, BufferError> {
        self.replace(range, "")
    }

    /// Replaces the byte range `range` with `text`.
    ///
    /// Replacing an empty range with an empty string is a no-op: the returned
    /// [`Edit`] has `changed == false` and the version does not change.
    ///
    /// # Errors
    ///
    /// [`BufferError::InvalidRange`] if `start > end`,
    /// [`BufferError::OutOfBounds`] if `end > len_bytes()`, or
    /// [`BufferError::NotCharBoundary`] if an endpoint is not a character boundary.
    pub fn replace(&mut self, range: Range<usize>, text: &str) -> Result<Edit, BufferError> {
        self.check_range(&range)?;
        if range.is_empty() && text.is_empty() {
            return Ok(self.noop(range));
        }
        let removed = self.slice_to_string(range.clone())?;
        let start_char = self.char_index(range.start)?;
        let end_char = self.char_index(range.end)?;
        // Both endpoints are validated above, so these `try_*` calls can only
        // fail if the rope is inconsistent with its own reported lengths.
        self.rope
            .try_remove(start_char..end_char)
            .map_err(|error| self.map_error(error))?;
        if !text.is_empty() {
            self.rope
                .try_insert(start_char, text)
                .map_err(|error| self.map_error(error))?;
        }
        self.bump_version();
        Ok(Edit {
            start_byte: range.start,
            old_end_byte: range.end,
            new_end_byte: range.start + text.len(),
            removed,
            version: self.version,
            changed: true,
        })
    }

    fn noop(&self, range: Range<usize>) -> Edit {
        Edit {
            start_byte: range.start,
            old_end_byte: range.end,
            new_end_byte: range.start,
            removed: String::new(),
            version: self.version,
            changed: false,
        }
    }

    fn bump_version(&mut self) {
        self.version = self.version.saturating_add(1);
    }

    // ----------------------------------------------------------- conversions

    /// Returns `true` if `byte` is a UTF-8 character boundary in `0..=len_bytes()`.
    pub fn is_char_boundary(&self, byte: usize) -> bool {
        if byte > self.len_bytes() {
            return false;
        }
        match self.rope.try_byte_to_char(byte) {
            Ok(char_index) => self
                .rope
                .try_char_to_byte(char_index)
                .is_ok_and(|start| start == byte),
            Err(_) => false,
        }
    }

    /// Returns the largest character boundary at or below `byte`.
    ///
    /// Values past the end of the document are clamped to `len_bytes()`.
    pub fn floor_char_boundary(&self, byte: usize) -> usize {
        let byte = byte.min(self.len_bytes());
        (byte.saturating_sub(3)..=byte)
            .rev()
            .find(|&candidate| self.is_char_boundary(candidate))
            .unwrap_or(0)
    }

    /// Returns the previous character boundary before `byte`, if any.
    ///
    /// Returns `None` at the start of the document. If `byte` is not a character
    /// boundary (or is past the end) this also returns `None`.
    pub fn prev_char_boundary(&self, byte: usize) -> Option<usize> {
        if !self.is_char_boundary(byte) {
            return None;
        }
        let char_index = self.rope.try_byte_to_char(byte).ok()?;
        let previous = self.rope.get_char(char_index.checked_sub(1)?)?;
        Some(byte - previous.len_utf8())
    }

    /// Returns the next character boundary after `byte`, if any.
    ///
    /// Returns `None` at the end of the document. If `byte` is not a character
    /// boundary (or is past the end) this also returns `None`.
    pub fn next_char_boundary(&self, byte: usize) -> Option<usize> {
        if !self.is_char_boundary(byte) {
            return None;
        }
        let char_index = self.rope.try_byte_to_char(byte).ok()?;
        let next = self.rope.get_char(char_index)?;
        Some(byte + next.len_utf8())
    }

    /// Converts byte offset `byte` to a Unicode scalar index.
    pub fn byte_to_char(&self, byte: usize) -> Result<usize, BufferError> {
        self.check_byte(byte)?;
        self.rope
            .try_byte_to_char(byte)
            .map_err(|error| self.map_error(error))
    }

    /// Converts Unicode scalar index `char_index` to a byte offset.
    pub fn char_to_byte(&self, char_index: usize) -> Result<usize, BufferError> {
        self.rope
            .try_char_to_byte(char_index)
            .map_err(|error| self.map_error(error))
    }

    /// Converts byte offset `byte` to a zero-based line index.
    pub fn byte_to_line(&self, byte: usize) -> Result<usize, BufferError> {
        self.check_byte(byte)?;
        self.rope
            .try_byte_to_line(byte)
            .map_err(|error| self.map_error(error))
    }

    /// Converts zero-based line index `line` to the byte offset of its first character.
    pub fn line_to_byte(&self, line: usize) -> Result<usize, BufferError> {
        self.check_line(line)?;
        self.rope
            .try_line_to_byte(line)
            .map_err(|error| self.map_error(error))
    }

    /// Converts byte offset `byte` to a UTF-16 code unit index.
    pub fn byte_to_utf16(&self, byte: usize) -> Result<usize, BufferError> {
        let char_index = self.byte_to_char(byte)?;
        self.rope
            .try_char_to_utf16_cu(char_index)
            .map_err(|error| self.map_error(error))
    }

    /// Converts UTF-16 code unit index `utf16` to a byte offset.
    ///
    /// An index in the middle of a surrogate pair is floored to the start of the
    /// character that owns it.
    ///
    /// # Errors
    ///
    /// [`BufferError::OutOfBounds`] if `utf16 > len_utf16()`.
    pub fn utf16_to_byte(&self, utf16: usize) -> Result<usize, BufferError> {
        let char_index = self
            .rope
            .try_utf16_cu_to_char(utf16)
            .map_err(|error| self.map_error(error))?;
        self.rope
            .try_char_to_byte(char_index)
            .map_err(|error| self.map_error(error))
    }

    // ------------------------------------------------------------ line query

    /// Returns the byte range of the content of `line`, excluding the terminator.
    pub fn line_content_range(&self, line: usize) -> Result<Range<usize>, BufferError> {
        self.check_line(line)?;
        Ok(self.content_range_unchecked(line))
    }

    /// Returns the content of `line` as a string, excluding the terminator.
    pub fn line_text(&self, line: usize) -> Result<String, BufferError> {
        let range = self.line_content_range(line)?;
        self.slice_to_string(range)
    }

    /// Returns up to `max_lines` consecutive lines starting at `first_line`.
    ///
    /// `first_line` is clamped to the document and fewer lines are returned at
    /// the end. `max_lines == 0` returns an empty vector. Intended for viewport
    /// rendering: each line costs `O(log n)`, not a scan from the document start.
    pub fn lines(&self, first_line: usize, max_lines: usize) -> Vec<BufferLine> {
        let line_count = self.line_count();
        if first_line >= line_count || max_lines == 0 {
            return Vec::new();
        }
        let end_line = first_line.saturating_add(max_lines).min(line_count);
        (first_line..end_line)
            .map(|index| BufferLine {
                index,
                start_byte: self.line_start_unchecked(index),
                content: self.content_range_unchecked(index),
            })
            .collect()
    }

    // ------------------------------------------------------------- fragments

    /// Returns the text in byte range `range`.
    ///
    /// # Errors
    ///
    /// The same errors as [`Self::replace`] for an invalid range.
    pub fn slice_to_string(&self, range: Range<usize>) -> Result<String, BufferError> {
        self.check_range(&range)?;
        let Some(slice) = self.rope.get_byte_slice(range) else {
            // Unreachable after `check_range`; kept to avoid indexing/panics.
            return Ok(String::new());
        };
        Ok(slice.to_string())
    }

    /// Returns up to `max_utf16_units` UTF-16 code units of text immediately
    /// before byte offset `byte`, never splitting a surrogate pair.
    ///
    /// Runs in `O(log n + max_utf16_units)`.
    pub fn utf16_context_before(
        &self,
        byte: usize,
        max_utf16_units: usize,
    ) -> Result<String, BufferError> {
        self.check_byte(byte)?;
        let mut char_index = self.char_index(byte)?;
        let mut units = 0usize;
        for character in self.rope.chars_at(char_index).reversed() {
            let width = character.len_utf16();
            if units + width > max_utf16_units {
                break;
            }
            units += width;
            char_index -= 1;
        }
        let start = self
            .rope
            .try_char_to_byte(char_index)
            .map_err(|error| self.map_error(error))?;
        self.slice_to_string(start..byte)
    }

    /// Returns up to `max_utf16_units` UTF-16 code units of text immediately
    /// after byte offset `byte`, never splitting a surrogate pair.
    ///
    /// Runs in `O(log n + max_utf16_units)`.
    pub fn utf16_context_after(
        &self,
        byte: usize,
        max_utf16_units: usize,
    ) -> Result<String, BufferError> {
        self.check_byte(byte)?;
        let char_index = self.char_index(byte)?;
        let mut end_index = char_index;
        let mut units = 0usize;
        for character in self.rope.chars_at(char_index) {
            let width = character.len_utf16();
            if units + width > max_utf16_units {
                break;
            }
            units += width;
            end_index += 1;
        }
        let end = self
            .rope
            .try_char_to_byte(end_index)
            .map_err(|error| self.map_error(error))?;
        self.slice_to_string(byte..end)
    }

    // ------------------------------------------------------------- internals

    fn check_byte(&self, byte: usize) -> Result<(), BufferError> {
        let len = self.len_bytes();
        if byte > len {
            return Err(BufferError::OutOfBounds { offset: byte, len });
        }
        if !self.is_char_boundary(byte) {
            return Err(BufferError::NotCharBoundary { offset: byte });
        }
        Ok(())
    }

    fn check_range(&self, range: &Range<usize>) -> Result<(), BufferError> {
        if range.start > range.end {
            return Err(BufferError::InvalidRange {
                start: range.start,
                end: range.end,
            });
        }
        let len = self.len_bytes();
        if range.end > len {
            return Err(BufferError::OutOfBounds {
                offset: range.end,
                len,
            });
        }
        self.check_byte(range.start)?;
        self.check_byte(range.end)
    }

    fn check_line(&self, line: usize) -> Result<(), BufferError> {
        let line_count = self.line_count();
        if line >= line_count {
            return Err(BufferError::LineOutOfBounds { line, line_count });
        }
        Ok(())
    }

    fn char_index(&self, byte: usize) -> Result<usize, BufferError> {
        self.rope
            .try_byte_to_char(byte)
            .map_err(|error| self.map_error(error))
    }

    /// Byte offset of the first character of `line`; `line` must be in range.
    fn line_start_unchecked(&self, line: usize) -> usize {
        self.rope
            .try_line_to_byte(line)
            .unwrap_or_else(|_| self.len_bytes())
    }

    /// Content byte range of `line`; `line` must be in range.
    fn content_range_unchecked(&self, line: usize) -> Range<usize> {
        let start = self.line_start_unchecked(line);
        // Only a line that ends with `\n` can have a `\r` to drop; the last
        // line has no terminator, so a trailing `\r` there is content.
        let (end, terminated) = if line + 1 < self.line_count() {
            (
                self.line_start_unchecked(line + 1)
                    .saturating_sub(1)
                    .max(start),
                true,
            )
        } else {
            (self.len_bytes(), false)
        };
        let end = if terminated && end > start && self.rope.get_byte(end - 1) == Some(b'\r') {
            end - 1
        } else {
            end
        };
        start..end
    }

    fn map_error(&self, error: ropey::Error) -> BufferError {
        match error {
            ropey::Error::ByteIndexOutOfBounds(offset, len)
            | ropey::Error::CharIndexOutOfBounds(offset, len)
            | ropey::Error::Utf16IndexOutOfBounds(offset, len) => {
                BufferError::OutOfBounds { offset, len }
            }
            ropey::Error::LineIndexOutOfBounds(line, line_count) => {
                BufferError::LineOutOfBounds { line, line_count }
            }
            ropey::Error::ByteIndexNotCharBoundary(offset) => {
                BufferError::NotCharBoundary { offset }
            }
            ropey::Error::ByteRangeNotCharBoundary(start, end) => BufferError::NotCharBoundary {
                offset: start.or(end).unwrap_or(0),
            },
            ropey::Error::ByteRangeInvalid(start, end) => BufferError::InvalidRange { start, end },
            ropey::Error::CharRangeInvalid(start, end)
            | ropey::Error::ByteRangeOutOfBounds(Some(start), Some(end), _)
            | ropey::Error::CharRangeOutOfBounds(Some(start), Some(end), _) => {
                BufferError::InvalidRange { start, end }
            }
            _ => BufferError::OutOfBounds {
                offset: self.len_bytes(),
                len: self.len_bytes(),
            },
        }
    }
}

impl Default for TextBuffer {
    fn default() -> Self {
        Self::new()
    }
}

/// Writes the full document text. Intended for tests and diagnostics (`O(n)`);
/// editor code should use [`TextBuffer::lines`] instead of copying the document.
impl fmt::Display for TextBuffer {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(&self.rope, formatter)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Test helper: builds a range from runtime values so reversed ranges used to
    // exercise error paths are not linted as literal mistakes.
    fn byte_range(start: usize, end: usize) -> Range<usize> {
        start..end
    }

    // Test helper: fails the test if the edit unexpectedly returns an error.
    #[allow(clippy::panic)]
    fn edit(result: Result<Edit, BufferError>) -> Edit {
        match result {
            Ok(edit) => edit,
            Err(error) => panic!("edit failed: {error}"),
        }
    }

    #[test]
    fn empty_document_has_one_line() {
        let buffer = TextBuffer::new();
        assert_eq!(buffer.version(), 1);
        assert!(buffer.is_empty());
        assert_eq!(buffer.len_bytes(), 0);
        assert_eq!(buffer.len_chars(), 0);
        assert_eq!(buffer.len_utf16(), 0);
        assert_eq!(buffer.line_count(), 1);
        assert_eq!(buffer.line_content_range(0), Ok(0..0));
        assert_eq!(buffer.line_text(0), Ok(String::new()));
        assert_eq!(
            buffer.lines(0, 10),
            vec![BufferLine {
                index: 0,
                start_byte: 0,
                content: 0..0
            }]
        );
    }

    #[test]
    fn only_newlines_and_trailing_newline() {
        let buffer = TextBuffer::from_str("\n");
        assert_eq!(buffer.line_count(), 2);
        assert_eq!(
            buffer
                .lines(0, 2)
                .iter()
                .map(|line| line.content.clone())
                .collect::<Vec<_>>(),
            vec![0..0, 1..1]
        );

        let buffer = TextBuffer::from_str("a\nb\n");
        assert_eq!(buffer.line_count(), 3);
        assert_eq!(buffer.line_text(0), Ok("a".to_string()));
        assert_eq!(buffer.line_text(1), Ok("b".to_string()));
        assert_eq!(buffer.line_text(2), Ok(String::new()));
        assert_eq!(buffer.line_to_byte(2), Ok(4));
    }

    #[test]
    fn crlf_is_one_line_break_and_lone_cr_is_text() {
        let buffer = TextBuffer::from_str("one\r\ntwo\rthree");
        assert_eq!(buffer.line_count(), 2);
        assert_eq!(buffer.line_text(0), Ok("one".to_string()));
        assert_eq!(buffer.line_text(1), Ok("two\rthree".to_string()));
        assert_eq!(buffer.line_content_range(0), Ok(0..3));

        let buffer = TextBuffer::from_str("a\r\n\r\nb");
        assert_eq!(buffer.line_count(), 3);
        assert_eq!(buffer.line_text(0), Ok("a".to_string()));
        assert_eq!(buffer.line_text(1), Ok(String::new()));
        assert_eq!(buffer.line_text(2), Ok("b".to_string()));

        let buffer = TextBuffer::from_str("\r");
        assert_eq!(buffer.line_count(), 1);
        assert_eq!(buffer.line_text(0), Ok("\r".to_string()));
    }

    #[test]
    fn utf16_offsets_use_code_units() {
        let buffer = TextBuffer::from_str("a中🙂b");
        assert_eq!(buffer.len_utf16(), 5);
        assert_eq!(buffer.byte_to_utf16(0), Ok(0));
        assert_eq!(buffer.byte_to_utf16(1), Ok(1));
        assert_eq!(buffer.byte_to_utf16(4), Ok(2));
        // The emoji is a surrogate pair: two UTF-16 code units.
        assert_eq!(buffer.byte_to_utf16(8), Ok(4));
        assert_eq!(buffer.byte_to_utf16(9), Ok(5));
        assert_eq!(buffer.utf16_to_byte(1), Ok(1));
        assert_eq!(buffer.utf16_to_byte(2), Ok(4));
        assert_eq!(buffer.utf16_to_byte(4), Ok(8));
        assert_eq!(buffer.utf16_to_byte(5), Ok(9));
        // Inside a surrogate pair: floor to the start of the character.
        assert_eq!(buffer.utf16_to_byte(3), Ok(4));
        assert_eq!(
            buffer.utf16_to_byte(6),
            Err(BufferError::OutOfBounds { offset: 6, len: 5 })
        );
    }

    #[test]
    fn multibyte_offsets_must_be_char_boundaries() {
        let mut buffer = TextBuffer::from_str("中");
        assert!(buffer.is_char_boundary(0));
        assert!(!buffer.is_char_boundary(1));
        assert!(!buffer.is_char_boundary(2));
        assert!(buffer.is_char_boundary(3));
        assert!(!buffer.is_char_boundary(4));

        assert_eq!(
            buffer.byte_to_char(1),
            Err(BufferError::NotCharBoundary { offset: 1 })
        );
        assert_eq!(
            buffer.byte_to_line(1),
            Err(BufferError::NotCharBoundary { offset: 1 })
        );
        assert_eq!(
            buffer.byte_to_utf16(1),
            Err(BufferError::NotCharBoundary { offset: 1 })
        );
        assert_eq!(
            buffer.insert(1, "x"),
            Err(BufferError::NotCharBoundary { offset: 1 })
        );
        assert_eq!(
            buffer.delete(0..1),
            Err(BufferError::NotCharBoundary { offset: 1 })
        );
        assert_eq!(buffer.to_string(), "中");
        assert_eq!(buffer.version(), 1);

        assert_eq!(buffer.floor_char_boundary(1), 0);
        assert_eq!(buffer.floor_char_boundary(2), 0);
        assert_eq!(buffer.floor_char_boundary(3), 3);
        assert_eq!(buffer.floor_char_boundary(99), 3);
        assert_eq!(buffer.prev_char_boundary(3), Some(0));
        assert_eq!(buffer.prev_char_boundary(0), None);
        assert_eq!(buffer.prev_char_boundary(1), None);
        assert_eq!(buffer.next_char_boundary(0), Some(3));
        assert_eq!(buffer.next_char_boundary(3), None);
    }

    #[test]
    fn out_of_bounds_errors_are_reported() {
        let mut buffer = TextBuffer::from_str("abc");
        assert_eq!(
            buffer.insert(4, "x"),
            Err(BufferError::OutOfBounds { offset: 4, len: 3 })
        );
        assert_eq!(
            buffer.delete(0..4),
            Err(BufferError::OutOfBounds { offset: 4, len: 3 })
        );
        assert_eq!(
            buffer.replace(byte_range(2, 1), "x"),
            Err(BufferError::InvalidRange { start: 2, end: 1 })
        );
        assert_eq!(
            buffer.line_to_byte(1),
            Err(BufferError::LineOutOfBounds {
                line: 1,
                line_count: 1
            })
        );
        assert_eq!(
            buffer.line_content_range(1),
            Err(BufferError::LineOutOfBounds {
                line: 1,
                line_count: 1
            })
        );
        assert_eq!(
            buffer.char_to_byte(4),
            Err(BufferError::OutOfBounds { offset: 4, len: 3 })
        );
        assert_eq!(buffer.to_string(), "abc");
        assert_eq!(buffer.version(), 1);
    }

    #[test]
    fn noop_edits_do_not_bump_version() {
        let mut buffer = TextBuffer::from_str("abc");
        let inserted = edit(buffer.insert(1, ""));
        assert!(!inserted.changed);
        assert_eq!(inserted.version, 1);
        assert_eq!(
            (
                inserted.start_byte,
                inserted.old_end_byte,
                inserted.new_end_byte
            ),
            (1, 1, 1)
        );
        assert_eq!(buffer.version(), 1);

        let deleted = edit(buffer.delete(2..2));
        assert!(!deleted.changed);
        assert_eq!(buffer.version(), 1);

        let replaced = edit(buffer.replace(1..1, ""));
        assert!(!replaced.changed);
        assert_eq!(buffer.version(), 1);
        assert_eq!(buffer.to_string(), "abc");
    }

    #[test]
    fn edits_bump_version_and_describe_the_change() {
        let mut buffer = TextBuffer::from_str("ab");
        let inserted = edit(buffer.insert(1, "中"));
        assert!(inserted.changed);
        assert_eq!(inserted.start_byte, 1);
        assert_eq!(inserted.old_end_byte, 1);
        assert_eq!(inserted.new_end_byte, 4);
        assert_eq!(inserted.removed, "");
        assert_eq!(inserted.version, 2);
        assert_eq!(buffer.version(), 2);

        let replaced = edit(buffer.replace(1..4, "🙂"));
        assert_eq!(replaced.removed, "中");
        assert_eq!(
            (
                replaced.start_byte,
                replaced.old_end_byte,
                replaced.new_end_byte
            ),
            (1, 4, 5)
        );
        assert_eq!(replaced.version, 3);
        assert_eq!(buffer.to_string(), "a🙂b");

        let deleted = edit(buffer.delete(1..5));
        assert_eq!(deleted.removed, "🙂");
        assert_eq!(deleted.version, 4);
        assert_eq!(buffer.to_string(), "ab");
        assert_eq!(buffer.version(), 4);
    }

    #[test]
    fn utf16_context_never_splits_a_surrogate_pair() {
        let buffer = TextBuffer::from_str("a中🙂b");
        assert_eq!(buffer.utf16_context_before(9, 0), Ok(String::new()));
        assert_eq!(buffer.utf16_context_before(9, 1), Ok("b".to_string()));
        assert_eq!(buffer.utf16_context_before(9, 2), Ok("b".to_string()));
        assert_eq!(buffer.utf16_context_before(9, 3), Ok("🙂b".to_string()));
        assert_eq!(buffer.utf16_context_before(9, 5), Ok("a中🙂b".to_string()));
        assert_eq!(buffer.utf16_context_before(9, 99), Ok("a中🙂b".to_string()));

        assert_eq!(buffer.utf16_context_after(0, 0), Ok(String::new()));
        assert_eq!(buffer.utf16_context_after(0, 1), Ok("a".to_string()));
        assert_eq!(buffer.utf16_context_after(0, 2), Ok("a中".to_string()));
        assert_eq!(buffer.utf16_context_after(0, 3), Ok("a中".to_string()));
        assert_eq!(buffer.utf16_context_after(1, 99), Ok("中🙂b".to_string()));
        assert_eq!(
            buffer.utf16_context_after(2, 1),
            Err(BufferError::NotCharBoundary { offset: 2 })
        );
    }

    #[test]
    fn lines_clamp_and_are_contiguous() {
        let buffer = TextBuffer::from_str("a\nbc\r\nd");
        let lines = buffer.lines(1, 99);
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].index, 1);
        assert_eq!(lines[0].start_byte, 2);
        assert_eq!(lines[0].content, 2..4);
        assert_eq!(lines[1].content, 6..7);
        assert!(buffer.lines(99, 5).is_empty());
        assert!(buffer.lines(0, 0).is_empty());
        assert_eq!(buffer.slice_to_string(2..4), Ok("bc".to_string()));
    }

    #[test]
    fn slice_to_string_validates_the_range() {
        let buffer = TextBuffer::from_str("中");
        assert_eq!(buffer.slice_to_string(0..3), Ok("中".to_string()));
        assert_eq!(
            buffer.slice_to_string(1..3),
            Err(BufferError::NotCharBoundary { offset: 1 })
        );
        assert_eq!(
            buffer.slice_to_string(0..4),
            Err(BufferError::OutOfBounds { offset: 4, len: 3 })
        );
        assert_eq!(
            buffer.slice_to_string(byte_range(2, 1)),
            Err(BufferError::InvalidRange { start: 2, end: 1 })
        );
    }

    #[test]
    fn display_is_the_full_text() {
        let buffer = TextBuffer::from_str("a\n中🙂");
        assert_eq!(buffer.to_string(), "a\n中🙂");
    }
}
