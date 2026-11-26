//! Platform-independent file content handling for Rcode.
//!
//! `rcode-fs` owns the parts of file I/O that do not need Android APIs: turning
//! the bytes of a text file into the buffer's text plus the format needed to
//! write it back, and the reverse. Reading bytes through SAF and writing them
//! atomically stay in Kotlin/`rcode-ffi` (T-0012, T-0010).
//!
//! The crate does not depend on UniFFI and is not exposed to Kotlin directly.
//!
//! Currently the only module is [`codec`].

pub mod codec;
