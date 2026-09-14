extern crate wasmi as wasmi_runtime_crate;

mod wasmi {
    pub use crate::wasmi_runtime_crate::*;
    pub use crate::wasmi_runtime_crate::errors::MemoryError;
}

include!("runtime.rs");
