// Keep the legacy packaged executable name for now, but compile the actual
// headless audio-fingerprint runtime directly in Rust.
extern crate wasmi as wasmi_runtime_crate;

mod wasmi {
    pub use crate::wasmi_runtime_crate::*;
    pub use crate::wasmi_runtime_crate::errors::MemoryError;
}

include!("../../audio-fingerprint/src/runtime.rs");
