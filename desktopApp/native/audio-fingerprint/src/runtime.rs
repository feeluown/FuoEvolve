use std::collections::{HashMap, HashSet};
use std::f64::consts::PI;
use std::io::{self, Read};

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use wasmi::{
    Caller, Engine, Error as WasmiError, Extern, ExternType, Func, Instance, Linker, Memory,
    Module, Store, Table, Val, ValType,
};

const AFP_WASM: &[u8] =
    include_bytes!("../../../../shared/src/commonMain/resources/audio_recognition/afp.wasm");
const EXPECTED_SELF_TEST_FINGERPRINT: &str = "Oxx8fV/EFTodkOd6OGfINlloG4c6o/Pl/brdhHqA/CD/u/mJHB/w7MHuD7isMM5qQHPDgkTSuB5ibZmJuLbl5UIpsOf/RwaY3JYBIH/WviQGnAEo3+0WfrOAtljkY4X9T95hnU5gv/fVE4Tsx9Kybjv1wORt1HIG3X0NzvS8PPfj3/RylFOTa2ADTOAkuA5nNOJZaHjd36dExYZy5Uuo8QvyJhbycR/XgOqEjQuegHA23iIeZgjsKt82VjlCJSB5uwaJ6ukC//LFmAGBEqw/n2n7gLLeUa0USNEFGQVBccw=";

#[derive(Debug, Deserialize)]
struct FingerprintRequest {
    #[serde(rename = "samplesBase64")]
    samples_base64: String,
}

#[derive(Debug, Serialize)]
struct FingerprintResponse<'a> {
    status: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    fingerprint: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<&'a str>,
}

#[derive(Clone, Debug)]
struct FunctionRegistration {
    arg_types: Vec<u32>,
    invoker: u32,
    context: u32,
}

#[derive(Clone, Debug)]
struct ClassRegistration {
    destructor: u32,
}

#[derive(Clone, Copy, Debug)]
struct IntegerType {
    size: u32,
    signed: bool,
}

struct HostState {
    string_types: HashSet<u32>,
    integer_types: HashMap<u32, IntegerType>,
    emval_types: HashSet<u32>,
    emval_values: HashMap<u32, i64>,
    next_emval_handle: u32,
    class_for_type: HashMap<u32, u32>,
    classes: HashMap<u32, ClassRegistration>,
    functions: HashMap<String, FunctionRegistration>,
    methods: HashMap<(u32, String), FunctionRegistration>,
}

impl Default for HostState {
    fn default() -> Self {
        Self {
            string_types: HashSet::new(),
            integer_types: HashMap::new(),
            emval_types: HashSet::new(),
            emval_values: HashMap::new(),
            // Emscripten reserves handles 1..=4 for undefined/null/true/false.
            next_emval_handle: 5,
            class_for_type: HashMap::new(),
            classes: HashMap::new(),
            functions: HashMap::new(),
            methods: HashMap::new(),
        }
    }
}

struct FingerprintRuntime {
    store: Store<HostState>,
    _instance: Instance,
    table: Table,
    memory: Memory,
    malloc: wasmi::TypedFunc<i32, i32>,
    free: wasmi::TypedFunc<i32, ()>,
}

fn main() {
    if let Err(error) = run() {
        emit_error(&error);
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut runtime = FingerprintRuntime::new()?;
    if std::env::args().skip(1).any(|arg| arg == "--self-test") {
        runtime.self_test()?;
        println!("audio fingerprint runtime self-test passed");
        return Ok(());
    }

    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .map_err(|error| format!("unable to read fingerprint request: {error}"))?;
    let request: FingerprintRequest = serde_json::from_str(&input)
        .map_err(|error| format!("unable to decode fingerprint request: {error}"))?;
    if request.samples_base64.is_empty() {
        return Err("samplesBase64 is required".to_string());
    }
    let samples = BASE64
        .decode(request.samples_base64)
        .map_err(|error| format!("invalid samplesBase64: {error}"))?;
    if samples.is_empty() || samples.len() % 4 != 0 {
        return Err("audio samples must contain non-empty Float32 bytes".to_string());
    }

    let fingerprint = runtime.generate(&samples)?;
    let response = FingerprintResponse {
        status: "success",
        fingerprint: Some(&fingerprint),
        message: None,
    };
    println!(
        "{}",
        serde_json::to_string(&response).map_err(|error| error.to_string())?
    );
    Ok(())
}

fn emit_error(error: &str) {
    let response = FingerprintResponse {
        status: "error",
        fingerprint: None,
        message: Some(error),
    };
    if let Ok(json) = serde_json::to_string(&response) {
        println!("{json}");
    } else {
        println!("{}", r#"{"status":"error","message":"audio fingerprint runtime failed"}"#);
    }
}

impl FingerprintRuntime {
    fn new() -> Result<Self, String> {
        let engine = Engine::default();
        let module = Module::new(&engine, AFP_WASM)
            .map_err(|error| format!("unable to compile audio fingerprint wasm: {error}"))?;
        let mut linker = Linker::<HostState>::new(&engine);

        for import in module.imports() {
            if import.module() != "a" {
                return Err(format!(
                    "unsupported audio fingerprint wasm import module {:?}",
                    import.module()
                ));
            }
            let ExternType::Func(func_type) = import.ty() else {
                return Err(format!("unsupported non-function wasm import {}", import.name()));
            };
            let name = import.name().to_string();
            let dispatch_name = name.clone();
            linker
                .func_new(
                    "a",
                    &name,
                    func_type.clone(),
                    move |caller, params, results| {
                        dispatch_import(&dispatch_name, caller, params, results)
                    },
                )
                .map_err(|error| format!("unable to link wasm import {name}: {error}"))?;
        }

        let mut store = Store::new(&engine, HostState::default());
        let instance = linker
            .instantiate_and_start(&mut store, &module)
            .map_err(|error| format!("unable to instantiate audio fingerprint wasm: {error}"))?;

        let memory = instance
            .get_memory(&store, "B")
            .ok_or_else(|| "audio fingerprint wasm is missing memory export B".to_string())?;
        let table = instance
            .get_table(&store, "D")
            .ok_or_else(|| "audio fingerprint wasm is missing function table export D".to_string())?;
        let malloc = instance
            .get_typed_func::<i32, i32>(&store, "E")
            .map_err(|error| format!("audio fingerprint wasm malloc export is invalid: {error}"))?;
        let free = instance
            .get_typed_func::<i32, ()>(&store, "F")
            .map_err(|error| format!("audio fingerprint wasm free export is invalid: {error}"))?;
        let ctors = instance
            .get_typed_func::<(), ()>(&store, "C")
            .map_err(|error| format!("audio fingerprint wasm ctor export is invalid: {error}"))?;
        ctors
            .call(&mut store, ())
            .map_err(|error| format!("unable to initialize audio fingerprint wasm: {error}"))?;

        if !store.data().functions.contains_key("ExtractQueryFP") {
            return Err("audio fingerprint wasm did not register ExtractQueryFP".to_string());
        }

        Ok(Self {
            store,
            _instance: instance,
            table,
            memory,
            malloc,
            free,
        })
    }

    fn generate(&mut self, float_bytes: &[u8]) -> Result<String, String> {
        let registration = self
            .store
            .data()
            .functions
            .get("ExtractQueryFP")
            .cloned()
            .ok_or_else(|| "ExtractQueryFP is not registered".to_string())?;
        if registration.arg_types.len() != 2 {
            return Err(format!(
                "ExtractQueryFP has unexpected Embind arity {}",
                registration.arg_types.len().saturating_sub(1)
            ));
        }
        let input_type = registration.arg_types[1];
        if !self.store.data().string_types.contains(&input_type) {
            return Err("ExtractQueryFP input is not an Embind std::string".to_string());
        }

        let input_ptr = self.alloc_embind_string(float_bytes)?;
        let result = self.call_invoker(&registration, &[input_ptr]);
        self.free
            .call(&mut self.store, input_ptr as i32)
            .map_err(|error| format!("unable to release fingerprint input: {error}"))?;
        let object_ptr = result?;

        let return_type = registration.arg_types[0];
        let class_type = *self
            .store
            .data()
            .class_for_type
            .get(&return_type)
            .ok_or_else(|| format!("ExtractQueryFP returned unknown Embind type {return_type}"))?;

        let result = self.read_fingerprint_vector(class_type, object_ptr);
        let destroy_result = self.destroy_class_instance(class_type, object_ptr);
        match (result, destroy_result) {
            (Ok(bytes), Ok(())) => {
                if bytes.len() <= 64 {
                    return Err(format!(
                        "fingerprint output is unexpectedly short: {} bytes",
                        bytes.len()
                    ));
                }
                Ok(BASE64.encode(bytes))
            }
            (Err(error), _) => Err(error),
            (Ok(_), Err(error)) => Err(error),
        }
    }

    fn read_fingerprint_vector(
        &mut self,
        class_type: u32,
        object_ptr: u32,
    ) -> Result<Vec<u8>, String> {
        let size_registration = self.method(class_type, "size")?;
        let size = self.call_method(&size_registration, object_ptr, &[])?;
        if size > 16 * 1024 {
            return Err(format!("fingerprint output is unexpectedly large: {size} bytes"));
        }

        let get_registration = self.method(class_type, "get")?;
        let mut bytes = Vec::with_capacity(size as usize);
        for index in 0..size {
            let value = self.call_method(&get_registration, object_ptr, &[index])?;
            bytes.push(value as u8);
        }
        Ok(bytes)
    }

    fn method(&self, class_type: u32, name: &str) -> Result<FunctionRegistration, String> {
        self.store
            .data()
            .methods
            .get(&(class_type, name.to_string()))
            .cloned()
            .ok_or_else(|| format!("fingerprint result class is missing method {name}"))
    }

    fn call_method(
        &mut self,
        registration: &FunctionRegistration,
        object_ptr: u32,
        args: &[u32],
    ) -> Result<u32, String> {
        let mut wire_args = Vec::with_capacity(args.len() + 1);
        wire_args.push(object_ptr);
        wire_args.extend_from_slice(args);
        let raw_result = self.call_invoker(registration, &wire_args)?;
        let return_type = registration
            .arg_types
            .first()
            .copied()
            .ok_or_else(|| "Embind method registration is missing a return type".to_string())?;

        if self.store.data().emval_types.contains(&return_type) {
            let value = self
                .store
                .data_mut()
                .emval_values
                .remove(&raw_result)
                .ok_or_else(|| format!("Embind emval handle {raw_result} is missing"))?;
            Ok(value as u32)
        } else {
            Ok(raw_result)
        }
    }

    fn call_invoker(
        &mut self,
        registration: &FunctionRegistration,
        wire_args: &[u32],
    ) -> Result<u32, String> {
        let func = table_func(&self.table, &self.store, registration.invoker)?;
        let ty = func.ty(&self.store);
        if ty.params().len() != wire_args.len() + 1 {
            return Err(format!(
                "Embind invoker {} expected {} wire arguments, got {}",
                registration.invoker,
                ty.params().len().saturating_sub(1),
                wire_args.len()
            ));
        }

        let mut params = Vec::with_capacity(ty.params().len());
        params.push(value_for_type(ty.params()[0], registration.context)?);
        for (value_type, value) in ty.params()[1..].iter().zip(wire_args) {
            params.push(value_for_type(*value_type, *value)?);
        }
        let mut results = ty
            .results()
            .iter()
            .copied()
            .map(Val::default)
            .collect::<Vec<_>>();
        func.call(&mut self.store, &params, &mut results)
            .map_err(|error| format!("Embind invoker {} failed: {error}", registration.invoker))?;

        match results.as_slice() {
            [value] => value
                .i32()
                .map(|value| value as u32)
                .ok_or_else(|| "Embind invoker returned a non-i32 value".to_string()),
            _ => Err("Embind invoker returned an unexpected result signature".to_string()),
        }
    }

    fn alloc_embind_string(&mut self, bytes: &[u8]) -> Result<u32, String> {
        let total = bytes
            .len()
            .checked_add(5)
            .ok_or_else(|| "audio sample buffer is too large".to_string())?;
        if total > i32::MAX as usize {
            return Err("audio sample buffer is too large".to_string());
        }
        let ptr = self
            .malloc
            .call(&mut self.store, total as i32)
            .map_err(|error| format!("unable to allocate fingerprint input: {error}"))?
            as u32;
        self.memory
            .write(
                &mut self.store,
                ptr as usize,
                &(bytes.len() as u32).to_le_bytes(),
            )
            .map_err(|error| format!("unable to write fingerprint input length: {error}"))?;
        self.memory
            .write(&mut self.store, ptr as usize + 4, bytes)
            .map_err(|error| format!("unable to write fingerprint input: {error}"))?;
        self.memory
            .write(&mut self.store, ptr as usize + 4 + bytes.len(), &[0])
            .map_err(|error| format!("unable to terminate fingerprint input: {error}"))?;
        Ok(ptr)
    }

    fn destroy_class_instance(&mut self, class_type: u32, ptr: u32) -> Result<(), String> {
        let class = self
            .store
            .data()
            .classes
            .get(&class_type)
            .cloned()
            .ok_or_else(|| format!("unknown Embind class type {class_type}"))?;
        let destructor = table_func(&self.table, &self.store, class.destructor)?;
        let ty = destructor.ty(&self.store);
        if ty.params() != [ValType::I32] || !ty.results().is_empty() {
            return Err("fingerprint result destructor has an unexpected signature".to_string());
        }
        destructor
            .call(&mut self.store, &[Val::I32(ptr as i32)], &mut [])
            .map_err(|error| format!("unable to destroy fingerprint result: {error}"))
    }

    fn self_test(&mut self) -> Result<(), String> {
        const SAMPLE_COUNT: usize = 48_000;
        let mut bytes = Vec::with_capacity(SAMPLE_COUNT * 4);
        for index in 0..SAMPLE_COUNT {
            let value = (0.5 * (2.0 * PI * 440.0 * index as f64 / 8000.0).sin()) as f32;
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        let actual = self.generate(&bytes)?;
        if actual != EXPECTED_SELF_TEST_FINGERPRINT {
            return Err(format!(
                "fixed audio fingerprint verification mismatch: got {actual}"
            ));
        }
        Ok(())
    }
}

fn table_func(table: &Table, store: &Store<HostState>, index: u32) -> Result<Func, String> {
    let value = table
        .get(store, index as u64)
        .ok_or_else(|| format!("WASM function table index {index} is out of range"))?;
    let Val::FuncRef(function_ref) = value else {
        return Err(format!("WASM table entry {index} is not a function reference"));
    };
    function_ref
        .val()
        .copied()
        .ok_or_else(|| format!("WASM function table entry {index} is null"))
}

fn value_for_type(value_type: ValType, value: u32) -> Result<Val, String> {
    match value_type {
        ValType::I32 => Ok(Val::I32(value as i32)),
        ValType::I64 => Ok(Val::I64(value as i64)),
        other => Err(format!("unsupported Embind wire parameter type {other:?}")),
    }
}

fn dispatch_import(
    name: &str,
    mut caller: Caller<'_, HostState>,
    params: &[Val],
    results: &mut [Val],
) -> Result<(), WasmiError> {
    match name {
        "a" | "e" | "j" | "p" | "y" | "z" => Ok(()),
        "b" => register_integer(&mut caller, params),
        "c" => register_class_function(&mut caller, params),
        "d" => Err(WasmiError::new(format!(
            "audio fingerprint wasm assertion failed: {}",
            read_c_string(&caller, param_u32(params, 0)?)?
        ))),
        "f" => Err(WasmiError::new("audio fingerprint wasm threw a C++ exception")),
        "g" => allocate_exception(&mut caller, params, results),
        "h" => Err(WasmiError::new("audio fingerprint wasm aborted")),
        "i" => fd_write(&mut caller, params, results),
        "k" => register_std_string(&mut caller, params),
        "l" => register_function(&mut caller, params),
        "m" | "n" | "q" | "w" => Ok(()),
        "o" => emval_take_value(&mut caller, params, results),
        "r" => memcpy(&mut caller, params, results),
        "s" | "t" | "u" => {
            set_first_i32(results, 0);
            Ok(())
        }
        "v" => environ_sizes_get(&mut caller, params, results),
        "x" => register_emval(&mut caller, params),
        "A" => register_class(&mut caller, params),
        other => Err(WasmiError::new(format!(
            "unsupported minified afp.wasm import {other:?}"
        ))),
    }
}

fn register_integer(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
) -> Result<(), WasmiError> {
    if params.len() != 5 {
        return Err(WasmiError::new(format!(
            "unexpected _embind_register_integer arity {}",
            params.len()
        )));
    }
    let raw_type = param_u32(params, 0)?;
    let size = param_u32(params, 2)?;
    let signed = param_i32(params, 3)? != 0;
    if !matches!(size, 1 | 2 | 4) {
        return Err(WasmiError::new(format!(
            "unsupported Embind integer size {size}"
        )));
    }
    caller
        .data_mut()
        .integer_types
        .insert(raw_type, IntegerType { size, signed });
    Ok(())
}

fn register_emval(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
) -> Result<(), WasmiError> {
    if params.len() != 2 {
        return Err(WasmiError::new(format!(
            "unexpected _embind_register_emval arity {}",
            params.len()
        )));
    }
    caller.data_mut().emval_types.insert(param_u32(params, 0)?);
    Ok(())
}

fn emval_take_value(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
    results: &mut [Val],
) -> Result<(), WasmiError> {
    if params.len() != 2 {
        return Err(WasmiError::new(format!(
            "unexpected _emval_take_value arity {}",
            params.len()
        )));
    }
    let raw_type = param_u32(params, 0)?;
    let pointer = param_u32(params, 1)? as usize;
    let integer_type = caller
        .data()
        .integer_types
        .get(&raw_type)
        .copied()
        .ok_or_else(|| WasmiError::new(format!("_emval_take_value has unknown type {raw_type}")))?;
    let value = read_integer_value(caller, pointer, integer_type)?;

    let state = caller.data_mut();
    let handle = state.next_emval_handle;
    state.next_emval_handle = state.next_emval_handle.checked_add(1).unwrap_or(5);
    state.emval_values.insert(handle, value);
    set_first_i32(results, handle);
    Ok(())
}

fn read_integer_value(
    caller: &Caller<'_, HostState>,
    pointer: usize,
    integer_type: IntegerType,
) -> Result<i64, WasmiError> {
    let memory = wasm_memory(caller)?;
    match (integer_type.size, integer_type.signed) {
        (1, true) => {
            let mut bytes = [0u8; 1];
            memory.read(caller, pointer, &mut bytes).map_err(memory_read_error)?;
            Ok(i8::from_le_bytes(bytes) as i64)
        }
        (1, false) => {
            let mut bytes = [0u8; 1];
            memory.read(caller, pointer, &mut bytes).map_err(memory_read_error)?;
            Ok(bytes[0] as i64)
        }
        (2, true) => {
            let mut bytes = [0u8; 2];
            memory.read(caller, pointer, &mut bytes).map_err(memory_read_error)?;
            Ok(i16::from_le_bytes(bytes) as i64)
        }
        (2, false) => {
            let mut bytes = [0u8; 2];
            memory.read(caller, pointer, &mut bytes).map_err(memory_read_error)?;
            Ok(u16::from_le_bytes(bytes) as i64)
        }
        (4, true) => {
            let mut bytes = [0u8; 4];
            memory.read(caller, pointer, &mut bytes).map_err(memory_read_error)?;
            Ok(i32::from_le_bytes(bytes) as i64)
        }
        (4, false) => {
            let mut bytes = [0u8; 4];
            memory.read(caller, pointer, &mut bytes).map_err(memory_read_error)?;
            Ok(u32::from_le_bytes(bytes) as i64)
        }
        _ => Err(WasmiError::new("unsupported Embind integer representation")),
    }
}

fn memory_read_error(error: wasmi::MemoryError) -> WasmiError {
    WasmiError::new(format!("unable to read Embind value from wasm memory: {error}"))
}

fn register_std_string(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
) -> Result<(), WasmiError> {
    caller.data_mut().string_types.insert(param_u32(params, 0)?);
    Ok(())
}

fn register_function(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
) -> Result<(), WasmiError> {
    if params.len() != 6 {
        return Err(WasmiError::new(format!(
            "unexpected _embind_register_function arity {}",
            params.len()
        )));
    }
    let name = read_c_string(caller, param_u32(params, 0)?)?;
    let arg_count = param_u32(params, 1)? as usize;
    let arg_types = read_u32_array(caller, param_u32(params, 2)?, arg_count)?;
    caller.data_mut().functions.insert(
        name,
        FunctionRegistration {
            arg_types,
            invoker: param_u32(params, 4)?,
            context: param_u32(params, 5)?,
        },
    );
    Ok(())
}

fn register_class_function(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
) -> Result<(), WasmiError> {
    if params.len() != 8 {
        return Err(WasmiError::new(format!(
            "unexpected legacy _embind_register_class_function arity {}",
            params.len()
        )));
    }
    let raw_class_type = param_u32(params, 0)?;
    let name = read_c_string(caller, param_u32(params, 1)?)?;
    let arg_count = param_u32(params, 2)? as usize;
    let arg_types = read_u32_array(caller, param_u32(params, 3)?, arg_count)?;
    caller.data_mut().methods.insert(
        (raw_class_type, name),
        FunctionRegistration {
            arg_types,
            invoker: param_u32(params, 5)?,
            context: param_u32(params, 6)?,
        },
    );
    Ok(())
}

fn register_class(caller: &mut Caller<'_, HostState>, params: &[Val]) -> Result<(), WasmiError> {
    if params.len() != 13 {
        return Err(WasmiError::new(format!(
            "unexpected _embind_register_class arity {}",
            params.len()
        )));
    }
    let raw_class_type = param_u32(params, 0)?;
    let raw_pointer_type = param_u32(params, 1)?;
    let raw_const_pointer_type = param_u32(params, 2)?;
    let destructor = param_u32(params, 12)?;
    let state = caller.data_mut();
    state.class_for_type.insert(raw_class_type, raw_class_type);
    state.class_for_type.insert(raw_pointer_type, raw_class_type);
    state
        .class_for_type
        .insert(raw_const_pointer_type, raw_class_type);
    state
        .classes
        .insert(raw_class_type, ClassRegistration { destructor });
    Ok(())
}

fn allocate_exception(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
    results: &mut [Val],
) -> Result<(), WasmiError> {
    let size = param_u32(params, 0)?;
    let malloc = caller
        .get_export("E")
        .and_then(Extern::into_func)
        .ok_or_else(|| WasmiError::new("malloc is unavailable while allocating a C++ exception"))?;
    let mut output = [Val::I32(0)];
    malloc.call(
        &mut *caller,
        &[Val::I32(size.saturating_add(16) as i32)],
        &mut output,
    )?;
    let ptr = output[0]
        .i32()
        .ok_or_else(|| WasmiError::new("malloc returned a non-i32 exception pointer"))?
        as u32;
    set_first_i32(results, ptr.saturating_add(16));
    Ok(())
}

fn memcpy(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
    results: &mut [Val],
) -> Result<(), WasmiError> {
    let destination = param_u32(params, 0)? as usize;
    let source = param_u32(params, 1)? as usize;
    let length = param_u32(params, 2)? as usize;
    let memory = wasm_memory(caller)?;
    let mut bytes = vec![0u8; length];
    memory
        .read(&*caller, source, &mut bytes)
        .map_err(|error| WasmiError::new(format!("emscripten memcpy source is invalid: {error}")))?;
    memory
        .write(&mut *caller, destination, &bytes)
        .map_err(|error| {
            WasmiError::new(format!("emscripten memcpy destination is invalid: {error}"))
        })?;
    set_first_i32(results, destination as u32);
    Ok(())
}

fn environ_sizes_get(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
    results: &mut [Val],
) -> Result<(), WasmiError> {
    let count_ptr = param_u32(params, 0)? as usize;
    let size_ptr = param_u32(params, 1)? as usize;
    let memory = wasm_memory(caller)?;
    memory
        .write(&mut *caller, count_ptr, &0u32.to_le_bytes())
        .map_err(|error| WasmiError::new(format!("unable to write environ count: {error}")))?;
    memory
        .write(&mut *caller, size_ptr, &0u32.to_le_bytes())
        .map_err(|error| WasmiError::new(format!("unable to write environ size: {error}")))?;
    set_first_i32(results, 0);
    Ok(())
}

fn fd_write(
    caller: &mut Caller<'_, HostState>,
    params: &[Val],
    results: &mut [Val],
) -> Result<(), WasmiError> {
    let iovs = param_u32(params, 1)? as usize;
    let iovs_len = param_u32(params, 2)? as usize;
    let written_ptr = param_u32(params, 3)? as usize;
    let memory = wasm_memory(caller)?;
    let mut total = 0u32;
    for index in 0..iovs_len {
        let entry = iovs + index * 8;
        let ptr = read_u32(&memory, &*caller, entry)? as usize;
        let len = read_u32(&memory, &*caller, entry + 4)? as usize;
        let mut bytes = vec![0u8; len];
        memory
            .read(&*caller, ptr, &mut bytes)
            .map_err(|error| WasmiError::new(format!("fd_write data is invalid: {error}")))?;
        eprint!("{}", String::from_utf8_lossy(&bytes));
        total = total.saturating_add(len as u32);
    }
    memory
        .write(&mut *caller, written_ptr, &total.to_le_bytes())
        .map_err(|error| WasmiError::new(format!("fd_write result pointer is invalid: {error}")))?;
    set_first_i32(results, 0);
    Ok(())
}

fn wasm_memory(caller: &Caller<'_, HostState>) -> Result<Memory, WasmiError> {
    caller
        .get_export("B")
        .and_then(Extern::into_memory)
        .ok_or_else(|| WasmiError::new("audio fingerprint wasm memory export B is unavailable"))
}

fn read_c_string(caller: &Caller<'_, HostState>, pointer: u32) -> Result<String, WasmiError> {
    let memory = wasm_memory(caller)?;
    let data = memory.data(caller);
    let tail = data
        .get(pointer as usize..)
        .ok_or_else(|| WasmiError::new("Embind string pointer is outside wasm memory"))?;
    let length = tail
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| WasmiError::new("unterminated Embind string"))?;
    std::str::from_utf8(&tail[..length])
        .map(|value| value.to_owned())
        .map_err(|error| WasmiError::new(format!("invalid Embind UTF-8 string: {error}")))
}

fn read_u32_array(
    caller: &Caller<'_, HostState>,
    pointer: u32,
    count: usize,
) -> Result<Vec<u32>, WasmiError> {
    let memory = wasm_memory(caller)?;
    let mut result = Vec::with_capacity(count);
    for index in 0..count {
        result.push(read_u32(
            &memory,
            caller,
            pointer as usize + index * 4,
        )?);
    }
    Ok(result)
}

fn read_u32(
    memory: &Memory,
    caller: &Caller<'_, HostState>,
    offset: usize,
) -> Result<u32, WasmiError> {
    let mut bytes = [0u8; 4];
    memory
        .read(caller, offset, &mut bytes)
        .map_err(|error| WasmiError::new(format!("unable to read wasm memory: {error}")))?;
    Ok(u32::from_le_bytes(bytes))
}

fn param_u32(params: &[Val], index: usize) -> Result<u32, WasmiError> {
    params
        .get(index)
        .and_then(Val::i32)
        .map(|value| value as u32)
        .ok_or_else(|| WasmiError::new(format!("WASM import parameter {index} is not i32")))
}

fn param_i32(params: &[Val], index: usize) -> Result<i32, WasmiError> {
    params
        .get(index)
        .and_then(Val::i32)
        .ok_or_else(|| WasmiError::new(format!("WASM import parameter {index} is not i32")))
}

fn set_first_i32(results: &mut [Val], value: u32) {
    if let Some(result) = results.first_mut() {
        *result = Val::I32(value as i32);
    }
}
