# Rust test regressions

Persisted `proptest` failure seeds, checked in so every run replays them
(see `.agents/skills/testing-qa/SKILL.md`).

- `prop_buffer.txt` — `core/crates/rcode-buffer/tests/prop_buffer.rs`
- `prop_undo.txt` — `core/crates/rcode-buffer/tests/prop_undo.rs`
- `prop_history.txt` — `core/crates/rcode-buffer/tests/prop_history.rs`
- `prop_codec.txt` — `core/crates/rcode-fs/tests/prop_codec.rs`

Do not edit these files by hand. Delete a seed only after the underlying bug
is fixed and the seed no longer reproduces the failure.
