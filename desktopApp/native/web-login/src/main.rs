// Keep the legacy packaged executable name for now, but compile the actual
// headless audio-fingerprint runtime directly in Rust.
mod wasmi {
    pub use ::wasmi::*;
    pub use ::wasmi::errors::MemoryError;
}

include!("../../audio-fingerprint/src/runtime.rs");
