//! UniFFI export layer for Rcode.
//!
//! This is the only crate that Kotlin may call. Exported functions must never
//! panic across the FFI boundary (see `.agents/conventions.md` §3).

mod editor;

pub use editor::{EditorCursor, EditorError, EditorLine, EditorSession, EditorViewport};

use std::sync::Once;

uniffi::setup_scaffolding!();

/// Core library version, taken from `Cargo.toml` at compile time.
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

static INIT_LOGGING: Once = Once::new();

/// Installs the tracing subscriber once. On Android, events go to logcat
/// with tag `rcode-core`. Elsewhere this is a no-op.
fn init_logging() {
    INIT_LOGGING.call_once(|| {
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::prelude::*;
            let layer = tracing_android::layer("rcode-core").ok();
            if let Some(layer) = layer {
                let _ = tracing_subscriber::registry().with(layer).try_init();
            }
        }
    });
}

/// Returns a liveness string of the form `rcode-core <version>`.
///
/// Used by the app at startup to verify the Rust <-> Kotlin round trip.
#[uniffi::export]
pub fn ping() -> String {
    init_logging();
    tracing::debug!(version = CORE_VERSION, "ping");
    format!("rcode-core {CORE_VERSION}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ping_contains_version() {
        assert_eq!(ping(), format!("rcode-core {}", env!("CARGO_PKG_VERSION")));
    }
}
