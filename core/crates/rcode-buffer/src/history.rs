//! Undo/redo history for [`TextBuffer`](crate::TextBuffer).
//!
//! The history stores *undo groups*: the smallest unit an undo removes. A group
//! replaces `start_byte..start_byte + inserted.len()` with `removed` when undone,
//! and `start_byte..start_byte + removed.len()` with `inserted` when redone. All
//! offsets are UTF-8 byte offsets and must be character boundaries.
//!
//! # Grouping
//!
//! Consecutive edits are merged into the open group when they are of the same
//! kind and satisfy [`MERGE_WINDOW`], the caret did not jump, the new text has no
//! `\n`, and the merged group stays at most [`MERGE_GROUP_BYTE_LIMIT`]. One paste or
//! one replacement larger than that limit is still a single group: the limit only
//! restricts *merging*. IME composition is grouped explicitly with
//! [`History::begin_composition`] / [`History::end_composition`] and is not subject
//! to the time window or the merge size limit.
//!
//! A group whose `removed` equals its `inserted` is dropped when it closes, so a
//! cancelled composition or an edit that rewrites identical text leaves no undo
//! step.
//!
//! # Memory limit
//!
//! [`History`] counts the text bytes of all undo and redo groups (string headers
//! are not counted). When recording pushes the count above the limit passed to
//! [`History::new`], the oldest undo groups are dropped until it fits, always
//! keeping the newest group. Truncation is intentional and bounded: "undo
//! everything" can only restore the initial text while the history was not
//! truncated.
//!
//! # Clock
//!
//! [`History::record`] takes `now` from the caller. Production passes
//! [`std::time::Instant::now`]; tests pass a controlled clock. Replaying an
//! undo/redo group interacts with the buffer through a closure, so a failed
//! replacement leaves both the text and the stacks unchanged.

use std::collections::VecDeque;
use std::ops::Range;
use std::time::{Duration, Instant};

use crate::{BufferError, Edit};

/// Maximum time between two edits that still merge into one undo group.
pub const MERGE_WINDOW: Duration = Duration::from_millis(500);

/// Maximum text bytes of a group while *merging*; a single edit may exceed it.
pub const MERGE_GROUP_BYTE_LIMIT: usize = 64 * 1024;

/// Default total text-byte limit of an undo history (64 MiB).
pub const UNDO_BYTE_LIMIT: usize = 64 * 1024 * 1024;

/// One undo step.
///
/// Undoing replaces `start_byte..start_byte + inserted.len()` with `removed` and
/// moves the caret to [`Self::cursor_before`]. Redoing replaces
/// `start_byte..start_byte + removed.len()` with `inserted` and moves the caret to
/// `start_byte + inserted.len()`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UndoGroup {
    /// Byte offset where the group's replacement happens.
    pub start_byte: usize,
    /// Text that the group removed from the document.
    pub removed: String,
    /// Text that the group inserted into the document.
    pub inserted: String,
    /// Caret byte offset before the group's first edit.
    pub cursor_before: usize,
    /// `removed.len() + inserted.len()`; the group's text-byte cost.
    pub bytes: usize,
}

impl UndoGroup {
    fn text_bytes(removed: &str, inserted: &str) -> usize {
        removed.len() + inserted.len()
    }

    fn is_noop(&self) -> bool {
        self.removed == self.inserted
    }

    /// Byte range to replace when undoing this group.
    ///
    /// Pass it to [`crate::TextBuffer::replace`] with [`Self::removed`] as the
    /// replacement.
    pub fn undo_range(&self) -> Range<usize> {
        self.start_byte..self.start_byte + self.inserted.len()
    }

    /// Byte range to replace when redoing this group.
    ///
    /// Pass it to [`crate::TextBuffer::replace`] with [`Self::inserted`] as the
    /// replacement.
    pub fn redo_range(&self) -> Range<usize> {
        self.start_byte..self.start_byte + self.removed.len()
    }

    /// Caret byte offset after redoing the group: just past the inserted text.
    pub fn redo_cursor(&self) -> usize {
        self.start_byte + self.inserted.len()
    }
}

/// Kind of the open group; only same-kind edits may merge.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GroupKind {
    Insert,
    Delete,
    Replace,
    Composition,
}

/// The group that is still accepting merges.
struct Pending {
    group: UndoGroup,
    kind: GroupKind,
    /// Time of the last edit in this group, used by [`MERGE_WINDOW`].
    last_edit: Instant,
    /// `true` when any text in the group contains `\n`; such groups never merge.
    has_newline: bool,
}

/// Undo/redo history for one document.
///
/// See the [module documentation](self) for grouping, the byte limit and the
/// replay contract.
pub struct History {
    undo: VecDeque<UndoGroup>,
    redo: VecDeque<UndoGroup>,
    byte_limit: usize,
    /// Text bytes of every group in both stacks plus the pending group.
    byte_len: usize,
    pending: Option<Pending>,
    composing: bool,
}

impl History {
    /// Creates an empty history that keeps at most `byte_limit` text bytes.
    ///
    /// Production uses [`UNDO_BYTE_LIMIT`]; tests inject smaller values. The
    /// newest group is always kept even when it alone exceeds the limit.
    pub fn new(byte_limit: usize) -> Self {
        Self {
            undo: VecDeque::new(),
            redo: VecDeque::new(),
            byte_limit,
            byte_len: 0,
            pending: None,
            composing: false,
        }
    }

    /// Clears both stacks, the open group and the composition state.
    ///
    /// Call this after loading or replacing a document's text: loading must not
    /// become an undoable step.
    pub fn clear(&mut self) {
        self.undo.clear();
        self.redo.clear();
        self.pending = None;
        self.composing = false;
        self.byte_len = 0;
    }

    /// `true` when [`Self::undo`] would change the document.
    ///
    /// An open group that is a no-op (removed text equals inserted text) does not
    /// count.
    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
            || self
                .pending
                .as_ref()
                .is_some_and(|pending| !pending.group.is_noop())
    }

    /// `true` when [`Self::redo`] would change the document.
    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }

    /// Number of closed undo groups.
    pub fn undo_len(&self) -> usize {
        self.undo.len()
    }

    /// Number of redo groups.
    pub fn redo_len(&self) -> usize {
        self.redo.len()
    }

    /// Text bytes currently held by the history (an approximation: string
    /// headers are not counted).
    pub fn byte_len(&self) -> usize {
        self.byte_len
    }

    /// Records the successful edit described by `edit`.
    ///
    /// `inserted` is the text the edit inserted (empty for a deletion) and
    /// `cursor_before` is the caret byte offset *before* the edit. `now` is the
    /// edit's timestamp; it drives [`MERGE_WINDOW`].
    ///
    /// Edits with `edit.changed == false` are ignored: they neither create a
    /// group nor clear the redo stack.
    pub fn record(&mut self, edit: &Edit, inserted: &str, cursor_before: usize, now: Instant) {
        if !edit.changed {
            return;
        }
        // Any real edit invalidates the redo stack (A5).
        self.drop_redo();

        if let Some(mut pending) = self.pending.take() {
            if try_merge(
                &mut pending,
                edit,
                inserted,
                cursor_before,
                now,
                &mut self.byte_len,
            ) {
                self.pending = Some(pending);
                self.truncate();
                return;
            }
            self.finish(pending);
        }

        let kind = if self.composing {
            GroupKind::Composition
        } else {
            classify(edit, inserted)
        };
        let group = UndoGroup {
            start_byte: edit.start_byte,
            removed: edit.removed.clone(),
            inserted: inserted.to_string(),
            cursor_before,
            bytes: UndoGroup::text_bytes(&edit.removed, inserted),
        };
        self.byte_len = self.byte_len.saturating_add(group.bytes);
        self.pending = Some(Pending {
            kind,
            last_edit: now,
            has_newline: inserted.contains('\n') || edit.removed.contains('\n'),
            group,
        });
        self.truncate();
    }

    /// Closes the open group so that subsequent edits start a new one, and marks
    /// the following edits as IME composition.
    ///
    /// Idempotent: calling it while already composing only closes the group.
    pub fn begin_composition(&mut self) {
        self.close_pending();
        self.composing = true;
    }

    /// Ends IME composition, closing the open group.
    ///
    /// A composition group with no net text change is discarded. Idempotent when
    /// not composing.
    pub fn end_composition(&mut self) {
        self.close_pending();
        self.composing = false;
    }

    /// Undoes the newest group and returns the caret byte offset the group
    /// recorded before its first edit.
    ///
    /// `apply` must replay the group's text into the buffer (replace
    /// `group.undo_range()` with `group.removed`). It runs before the stacks are
    /// changed: when it returns an error the stacks stay untouched and the error
    /// is propagated.
    ///
    /// Returns `Ok(None)` when there is nothing to undo.
    ///
    /// # Errors
    ///
    /// Propagates the error returned by `apply`.
    pub fn undo<F>(&mut self, apply: F) -> Result<Option<usize>, BufferError>
    where
        F: FnOnce(&UndoGroup) -> Result<(), BufferError>,
    {
        self.close_pending();
        let Some(group) = self.undo.back() else {
            return Ok(None);
        };
        let cursor = group.cursor_before;
        apply(group)?;
        let Some(group) = self.undo.pop_back() else {
            // Unreachable: the group was just borrowed from this stack.
            return Ok(Some(cursor));
        };
        self.redo.push_back(group);
        Ok(Some(cursor))
    }

    /// Redoes the newest redo group and returns the caret byte offset just past
    /// its inserted text.
    ///
    /// `apply` must replay the group into the buffer (replace `group.redo_range()`
    /// with `group.inserted`). The same all-or-nothing rule as [`Self::undo`]
    /// applies.
    ///
    /// Returns `Ok(None)` when there is nothing to redo.
    ///
    /// # Errors
    ///
    /// Propagates the error returned by `apply`.
    pub fn redo<F>(&mut self, apply: F) -> Result<Option<usize>, BufferError>
    where
        F: FnOnce(&UndoGroup) -> Result<(), BufferError>,
    {
        self.close_pending();
        let Some(group) = self.redo.back() else {
            return Ok(None);
        };
        let cursor = group.redo_cursor();
        apply(group)?;
        let Some(group) = self.redo.pop_back() else {
            // Unreachable: the group was just borrowed from this stack.
            return Ok(Some(cursor));
        };
        self.undo.push_back(group);
        Ok(Some(cursor))
    }

    // ------------------------------------------------------------- internals

    /// Drops all redo groups, keeping `byte_len` accurate.
    fn drop_redo(&mut self) {
        for group in self.redo.drain(..) {
            self.byte_len = self.byte_len.saturating_sub(group.bytes);
        }
    }

    /// Moves the open group to the undo stack (or discards a no-op group).
    fn finish(&mut self, pending: Pending) {
        if pending.group.is_noop() {
            self.byte_len = self.byte_len.saturating_sub(pending.group.bytes);
        } else {
            self.undo.push_back(pending.group);
        }
    }

    fn close_pending(&mut self) {
        if let Some(pending) = self.pending.take() {
            self.finish(pending);
        }
    }

    /// Enforces the byte limit by dropping the oldest undo groups, always keeping
    /// at least the newest group.
    fn truncate(&mut self) {
        let mut groups = self.undo.len() + usize::from(self.pending.is_some());
        while self.byte_len > self.byte_limit && groups > 1 {
            let Some(oldest) = self.undo.pop_front() else {
                break;
            };
            self.byte_len = self.byte_len.saturating_sub(oldest.bytes);
            groups -= 1;
        }
    }
}

impl Default for History {
    /// Creates an empty history with [`UNDO_BYTE_LIMIT`].
    fn default() -> Self {
        Self::new(UNDO_BYTE_LIMIT)
    }
}

fn classify(edit: &Edit, inserted: &str) -> GroupKind {
    if edit.removed.is_empty() && !inserted.is_empty() {
        GroupKind::Insert
    } else if !edit.removed.is_empty() && inserted.is_empty() {
        GroupKind::Delete
    } else {
        GroupKind::Replace
    }
}

/// Tries to merge `edit` into `pending`; returns `false` when a new group must
/// start. Updates `byte_len` on success.
fn try_merge(
    pending: &mut Pending,
    edit: &Edit,
    inserted: &str,
    caret_before: usize,
    now: Instant,
    byte_len: &mut usize,
) -> bool {
    match pending.kind {
        GroupKind::Insert => merge_insert(pending, edit, inserted, caret_before, now, byte_len),
        GroupKind::Delete => merge_delete(pending, edit, inserted, caret_before, now, byte_len),
        GroupKind::Replace => false,
        GroupKind::Composition => merge_composition(pending, edit, inserted, byte_len),
    }
}

fn merge_insert(
    pending: &mut Pending,
    edit: &Edit,
    inserted: &str,
    caret_before: usize,
    now: Instant,
    byte_len: &mut usize,
) -> bool {
    if inserted.is_empty()
        || !edit.removed.is_empty()
        || pending.has_newline
        || inserted.contains('\n')
        || now.saturating_duration_since(pending.last_edit) > MERGE_WINDOW
    {
        return false;
    }
    let end = pending.group.start_byte + pending.group.inserted.len();
    if caret_before != end || edit.start_byte != end {
        return false;
    }
    let merged_len = pending.group.inserted.len().saturating_add(inserted.len());
    if merged_len > MERGE_GROUP_BYTE_LIMIT {
        return false;
    }
    pending.group.inserted.push_str(inserted);
    pending.group.bytes = UndoGroup::text_bytes(&pending.group.removed, &pending.group.inserted);
    pending.last_edit = now;
    *byte_len = byte_len.saturating_add(inserted.len());
    true
}

fn merge_delete(
    pending: &mut Pending,
    edit: &Edit,
    inserted: &str,
    caret_before: usize,
    now: Instant,
    byte_len: &mut usize,
) -> bool {
    if !inserted.is_empty()
        || edit.removed.is_empty()
        || pending.has_newline
        || edit.removed.contains('\n')
        || now.saturating_duration_since(pending.last_edit) > MERGE_WINDOW
        || caret_before != pending.group.start_byte
    {
        return false;
    }
    let group_start = pending.group.start_byte;
    let edit_end = edit.start_byte + edit.removed.len();
    let merged_len = pending
        .group
        .removed
        .len()
        .saturating_add(edit.removed.len());
    if merged_len > MERGE_GROUP_BYTE_LIMIT {
        return false;
    }
    if edit_end == group_start {
        // Backspace: the new deletion sits directly before the group.
        let mut removed = String::with_capacity(merged_len);
        removed.push_str(&edit.removed);
        removed.push_str(&pending.group.removed);
        pending.group.removed = removed;
        pending.group.start_byte = edit.start_byte;
    } else if edit.start_byte == group_start {
        // Forward delete: the removed text is gone, so the next character now
        // sits at the group start and is appended to the removed text.
        pending.group.removed.push_str(&edit.removed);
    } else {
        return false;
    }
    pending.group.bytes = UndoGroup::text_bytes(&pending.group.removed, &pending.group.inserted);
    pending.last_edit = now;
    *byte_len = byte_len.saturating_add(edit.removed.len());
    true
}

fn merge_composition(
    pending: &mut Pending,
    edit: &Edit,
    inserted: &str,
    byte_len: &mut usize,
) -> bool {
    if pending.has_newline || inserted.contains('\n') {
        return false;
    }
    let start = pending.group.start_byte;
    let end = start + pending.group.inserted.len();
    if edit.start_byte < start || edit.old_end_byte > end {
        return false;
    }
    let splice_start = edit.start_byte - start;
    let splice_end = edit.old_end_byte - start;
    let current = &pending.group.inserted;
    if !current.is_char_boundary(splice_start) || !current.is_char_boundary(splice_end) {
        return false;
    }
    if current[splice_start..splice_end] != edit.removed {
        return false;
    }
    let old_len = current.len();
    let mut next =
        String::with_capacity(old_len.saturating_sub(splice_end - splice_start) + inserted.len());
    next.push_str(&current[..splice_start]);
    next.push_str(inserted);
    next.push_str(&current[splice_end..]);
    pending.group.inserted = next;
    pending.group.bytes = UndoGroup::text_bytes(&pending.group.removed, &pending.group.inserted);
    let new_len = pending.group.inserted.len();
    *byte_len = byte_len.saturating_sub(old_len).saturating_add(new_len);
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::TextBuffer;

    // Test helper: fails the test if the edit unexpectedly errors.
    #[allow(clippy::panic)]
    fn edit(result: Result<Edit, BufferError>) -> Edit {
        match result {
            Ok(edit) => edit,
            Err(error) => panic!("edit failed: {error}"),
        }
    }

    /// Applies a buffer edit and records it with `caret_before`.
    fn apply(
        history: &mut History,
        buffer: &mut TextBuffer,
        range: Range<usize>,
        text: &str,
        caret_before: usize,
        now: Instant,
    ) -> Edit {
        let edit = edit(buffer.replace(range, text));
        history.record(&edit, text, caret_before, now);
        edit
    }

    fn undo_one(history: &mut History, buffer: &mut TextBuffer) -> Option<usize> {
        match history.undo(|group| {
            buffer
                .replace(group.undo_range(), &group.removed)
                .map(|_| ())
        }) {
            Ok(cursor) => cursor,
            Err(error) => {
                #[allow(clippy::panic)]
                {
                    panic!("undo failed: {error}");
                }
            }
        }
    }

    fn redo_one(history: &mut History, buffer: &mut TextBuffer) -> Option<usize> {
        match history.redo(|group| {
            buffer
                .replace(group.redo_range(), &group.inserted)
                .map(|_| ())
        }) {
            Ok(cursor) => cursor,
            Err(error) => {
                #[allow(clippy::panic)]
                {
                    panic!("redo failed: {error}");
                }
            }
        }
    }

    fn setup(text: &str) -> (TextBuffer, History, Instant) {
        (
            TextBuffer::from_str(text),
            History::new(UNDO_BYTE_LIMIT),
            Instant::now(),
        )
    }

    #[test]
    fn single_insert_undoes_and_redoes() {
        let (mut buffer, mut history, now) = setup("ab");
        apply(&mut history, &mut buffer, 1..1, "X", 1, now);
        assert_eq!(buffer.to_string(), "aXb");
        assert!(history.can_undo());
        assert!(!history.can_redo());

        assert_eq!(undo_one(&mut history, &mut buffer), Some(1));
        assert_eq!(buffer.to_string(), "ab");
        assert!(!history.can_undo());
        assert!(history.can_redo());

        assert_eq!(redo_one(&mut history, &mut buffer), Some(2));
        assert_eq!(buffer.to_string(), "aXb");
        assert!(history.can_undo());
        assert!(!history.can_redo());
    }

    #[test]
    fn single_delete_and_replace_undo_and_redo() {
        let (mut buffer, mut history, now) = setup("abcd");
        apply(&mut history, &mut buffer, 1..3, "", 1, now);
        assert_eq!(buffer.to_string(), "ad");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(1));
        assert_eq!(buffer.to_string(), "abcd");
        assert_eq!(redo_one(&mut history, &mut buffer), Some(1));
        assert_eq!(buffer.to_string(), "ad");

        apply(
            &mut history,
            &mut buffer,
            0..1,
            "Z",
            0,
            now + MERGE_WINDOW * 2,
        );
        assert_eq!(buffer.to_string(), "Zd");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(0));
        assert_eq!(buffer.to_string(), "ad");
        assert_eq!(redo_one(&mut history, &mut buffer), Some(1));
        assert_eq!(buffer.to_string(), "Zd");
    }

    #[test]
    fn undo_then_new_edit_clears_redo() {
        let (mut buffer, mut history, now) = setup("");
        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..1,
            "b",
            1,
            now + MERGE_WINDOW * 2,
        );
        assert_eq!(undo_one(&mut history, &mut buffer), Some(1));
        assert!(history.can_redo());

        apply(
            &mut history,
            &mut buffer,
            1..1,
            "c",
            1,
            now + MERGE_WINDOW * 4,
        );
        assert!(!history.can_redo());
        assert_eq!(buffer.to_string(), "ac");
    }

    #[test]
    fn insert_merges_within_the_window_and_breaks_after_it() {
        let (mut buffer, mut history, now) = setup("");
        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..1,
            "b",
            1,
            now + Duration::from_millis(499),
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "");
        assert!(!history.can_undo());

        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..1,
            "b",
            1,
            now + Duration::from_millis(501),
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "a");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(0));
        assert_eq!(buffer.to_string(), "");
    }

    #[test]
    fn newline_and_caret_jumps_break_insert_groups() {
        let (mut buffer, mut history, now) = setup("");
        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..1,
            "\n",
            1,
            now + Duration::from_millis(1),
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "a");
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "");

        // A newline-containing group stays closed: a following insert does not join.
        apply(&mut history, &mut buffer, 0..0, "x\ny", 0, now);
        apply(
            &mut history,
            &mut buffer,
            3..3,
            "z",
            3,
            now + Duration::from_millis(1),
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "x\ny");
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "");

        // Caret moved elsewhere: new group even though the edit is adjacent.
        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..1,
            "b",
            0,
            now + Duration::from_millis(1),
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "a");
    }

    #[test]
    fn replace_never_merges() {
        let (mut buffer, mut history, now) = setup("ab");
        apply(&mut history, &mut buffer, 0..1, "x", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..2,
            "y",
            1,
            now + Duration::from_millis(1),
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "xb");
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "ab");
    }

    #[test]
    fn backward_and_forward_deletes_merge() {
        let (mut buffer, mut history, now) = setup("abcd");
        // Backspace twice from the caret at 3: range 2..3 then 1..2.
        apply(&mut history, &mut buffer, 2..3, "", 3, now);
        apply(
            &mut history,
            &mut buffer,
            1..2,
            "",
            2,
            now + Duration::from_millis(1),
        );
        assert_eq!(buffer.to_string(), "ad");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(3));
        assert_eq!(buffer.to_string(), "abcd");

        // Forward delete twice from the start: range 0..1 then 0..1 again.
        let (mut buffer, mut history, now) = setup("abcd");
        apply(&mut history, &mut buffer, 0..1, "", 0, now);
        apply(
            &mut history,
            &mut buffer,
            0..1,
            "",
            0,
            now + Duration::from_millis(1),
        );
        assert_eq!(buffer.to_string(), "cd");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(0));
        assert_eq!(buffer.to_string(), "abcd");
    }

    #[test]
    fn merge_group_byte_limit_breaks_the_group() {
        let limit = History::new(UNDO_BYTE_LIMIT);
        assert!(limit.can_undo().eq(&false));
        let (mut buffer, mut history, now) = setup("");
        let chunk = "x".repeat(MERGE_GROUP_BYTE_LIMIT / 2);
        apply(&mut history, &mut buffer, 0..0, &chunk, 0, now);
        apply(
            &mut history,
            &mut buffer,
            chunk.len()..chunk.len(),
            &chunk,
            chunk.len(),
            now,
        );
        let double = format!("{chunk}{chunk}");
        assert_eq!(buffer.to_string(), double);
        // The merged group is exactly at the limit, so the third insert starts a new group.
        apply(
            &mut history,
            &mut buffer,
            double.len()..double.len(),
            "z",
            double.len(),
            now,
        );
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), double);
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "");
    }

    #[test]
    fn noop_group_is_discarded_and_identical_rewrite_leaves_no_step() {
        let (mut buffer, mut history, now) = setup("a");
        // Replace "a" with "a": changed edit, but the group is net-empty.
        apply(&mut history, &mut buffer, 0..1, "a", 0, now);
        assert!(!history.can_undo());
        history.end_composition();
        assert!(!history.can_undo());
        assert_eq!(history.byte_len(), 0);
        assert!(!history.can_redo());
    }

    #[test]
    fn composition_groups_fast_and_slow_sequences() {
        let (mut buffer, mut history, now) = setup("");
        history.begin_composition();
        apply(&mut history, &mut buffer, 0..0, "n", 0, now);
        apply(
            &mut history,
            &mut buffer,
            1..1,
            "i",
            1,
            now + MERGE_WINDOW * 3,
        );
        apply(
            &mut history,
            &mut buffer,
            2..2,
            "h",
            2,
            now + MERGE_WINDOW * 6,
        );
        apply(
            &mut history,
            &mut buffer,
            0..3,
            "你好",
            3,
            now + MERGE_WINDOW * 9,
        );
        history.end_composition();
        assert_eq!(buffer.to_string(), "你好");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(0));
        assert_eq!(buffer.to_string(), "");
        assert!(!history.can_undo());
    }

    #[test]
    fn cancelled_composition_leaves_no_step() {
        let (mut buffer, mut history, now) = setup("");
        history.begin_composition();
        apply(&mut history, &mut buffer, 0..0, "n", 0, now);
        apply(&mut history, &mut buffer, 1..1, "i", 1, now);
        apply(&mut history, &mut buffer, 0..2, "", 2, now);
        history.end_composition();
        assert_eq!(buffer.to_string(), "");
        assert!(!history.can_undo());
        assert_eq!(history.byte_len(), 0);
    }

    #[test]
    fn begin_composition_is_idempotent_and_ends_open_groups() {
        let (mut buffer, mut history, now) = setup("");
        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        history.begin_composition();
        history.begin_composition();
        apply(&mut history, &mut buffer, 1..1, "b", 1, now);
        history.end_composition();
        history.end_composition();
        assert_eq!(buffer.to_string(), "ab");
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "a");
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "");
    }

    #[test]
    fn undo_and_redo_restore_the_documented_carets() {
        let (mut buffer, mut history, now) = setup("abc");
        apply(&mut history, &mut buffer, 1..1, "中", 1, now);
        assert_eq!(buffer.to_string(), "a中bc");
        assert_eq!(undo_one(&mut history, &mut buffer), Some(1));
        assert_eq!(buffer.to_string(), "abc");
        assert_eq!(redo_one(&mut history, &mut buffer), Some("a中".len()));
        assert_eq!(buffer.to_string(), "a中bc");
    }

    #[test]
    fn byte_limit_drops_oldest_but_keeps_newest() {
        let mut buffer = TextBuffer::from_str("abcdef");
        let mut history = History::new(2);
        let mut now = Instant::now();
        // Each delete removes one byte; a limit of 2 bytes keeps 2 groups.
        apply(&mut history, &mut buffer, 0..1, "", 0, now);
        now += MERGE_WINDOW * 2;
        apply(&mut history, &mut buffer, 0..1, "", 0, now);
        now += MERGE_WINDOW * 2;
        apply(&mut history, &mut buffer, 0..1, "", 0, now);
        assert!(history.byte_len() <= 2);
        // The newest group is still open; two groups remain in total.
        assert_eq!(history.undo_len(), 1);
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert!(!history.can_undo());
        assert_eq!(buffer.to_string(), "bcdef");
    }

    #[test]
    fn newest_group_larger_than_the_limit_is_kept() {
        let mut buffer = TextBuffer::from_str("");
        let mut history = History::new(4);
        let now = Instant::now();
        apply(
            &mut history,
            &mut buffer,
            0..0,
            "0123456789",
            0,
            now + MERGE_WINDOW,
        );
        assert!(history.byte_len() > 4);
        assert!(history.can_undo());
        assert!(undo_one(&mut history, &mut buffer).is_some());
        assert_eq!(buffer.to_string(), "");
    }

    #[test]
    fn clear_resets_everything() {
        let (mut buffer, mut history, now) = setup("");
        apply(&mut history, &mut buffer, 0..0, "a", 0, now);
        history.clear();
        assert!(!history.can_undo());
        assert!(!history.can_redo());
        assert_eq!(history.byte_len(), 0);
        assert_eq!(history.undo_len(), 0);
        assert_eq!(history.redo_len(), 0);
    }

    #[test]
    fn failed_replay_leaves_the_stacks_unchanged() {
        let (mut buffer, mut history, now) = setup("a");
        apply(&mut history, &mut buffer, 0..0, "x", 0, now);
        history.end_composition();
        let before_len = history.undo_len();
        let result = history.undo(|_| Err(BufferError::InvalidRange { start: 3, end: 1 }));
        assert!(result.is_err());
        assert_eq!(history.undo_len(), before_len);
        assert_eq!(buffer.to_string(), "xa");
    }
}
