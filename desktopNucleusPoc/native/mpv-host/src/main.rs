use libloading::Library;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashSet};
use std::env;
use std::ffi::{c_char, c_double, c_int, c_void, CStr, CString};
use std::io::{self, BufRead, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::thread;
use std::time::{Duration, Instant};

const MPV_EVENT_NONE: c_int = 0;
const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_START_FILE: c_int = 6;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_EVENT_FILE_LOADED: c_int = 8;
const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;
const MPV_VOLUME_SCALE: f64 = 100.0;
const EVENT_WAIT_SECONDS: f64 = 0.05;
const POLL_INTERVAL: Duration = Duration::from_millis(250);

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum HostCommand {
    Load {
        url: String,
        #[serde(default)]
        headers: BTreeMap<String, String>,
    },
    Pause {
        paused: bool,
    },
    Volume {
        value: f64,
    },
    Stop,
    Seek {
        #[serde(rename = "positionMs")]
        position_ms: i64,
    },
    Close,
}

#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventStartFile {
    playlist_entry_id: i64,
}

#[repr(C)]
struct MpvEventEndFile {
    reason: c_int,
    error: c_int,
    playlist_entry_id: i64,
    playlist_insert_id: i64,
    playlist_insert_num_entries: c_int,
}

type MpvCreate = unsafe extern "C" fn() -> *mut c_void;
type MpvInitialize = unsafe extern "C" fn(*mut c_void) -> c_int;
type MpvTerminateDestroy = unsafe extern "C" fn(*mut c_void);
type MpvSetOptionString = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int;
type MpvSetPropertyString = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int;
type MpvGetPropertyString = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char;
type MpvFree = unsafe extern "C" fn(*mut c_void);
type MpvCommand = unsafe extern "C" fn(*mut c_void, *const *const c_char) -> c_int;
type MpvWaitEvent = unsafe extern "C" fn(*mut c_void, c_double) -> *const MpvEvent;
type MpvErrorString = unsafe extern "C" fn(c_int) -> *const c_char;

struct MpvApi {
    _library: Library,
    create: MpvCreate,
    initialize: MpvInitialize,
    terminate_destroy: MpvTerminateDestroy,
    set_option_string: MpvSetOptionString,
    set_property_string: MpvSetPropertyString,
    get_property_string: MpvGetPropertyString,
    free: MpvFree,
    command: MpvCommand,
    wait_event: MpvWaitEvent,
    error_string: MpvErrorString,
}

impl MpvApi {
    fn load() -> Result<Self, String> {
        let mut failures = Vec::new();
        for candidate in library_candidates() {
            let library = match unsafe { Library::new(&candidate) } {
                Ok(library) => library,
                Err(error) => {
                    failures.push(format!("{candidate}: {error}"));
                    continue;
                }
            };
            let api = unsafe { Self::from_library(library) };
            match api {
                Ok(api) => {
                    eprintln!("fuoevolve-mpv-host: loaded libmpv from {candidate}");
                    return Ok(api);
                }
                Err(error) => failures.push(format!("{candidate}: {error}")),
            }
        }
        Err(format!(
            "Unable to load libmpv ({})",
            failures.join("; ")
        ))
    }

    unsafe fn from_library(library: Library) -> Result<Self, String> {
        unsafe fn load_symbol<T: Copy>(library: &Library, name: &[u8]) -> Result<T, String> {
            library
                .get::<T>(name)
                .map(|symbol| *symbol)
                .map_err(|error| error.to_string())
        }

        let create = load_symbol(&library, b"mpv_create\0")?;
        let initialize = load_symbol(&library, b"mpv_initialize\0")?;
        let terminate_destroy = load_symbol(&library, b"mpv_terminate_destroy\0")?;
        let set_option_string = load_symbol(&library, b"mpv_set_option_string\0")?;
        let set_property_string = load_symbol(&library, b"mpv_set_property_string\0")?;
        let get_property_string = load_symbol(&library, b"mpv_get_property_string\0")?;
        let free = load_symbol(&library, b"mpv_free\0")?;
        let command = load_symbol(&library, b"mpv_command\0")?;
        let wait_event = load_symbol(&library, b"mpv_wait_event\0")?;
        let error_string = load_symbol(&library, b"mpv_error_string\0")?;

        Ok(Self {
            _library: library,
            create,
            initialize,
            terminate_destroy,
            set_option_string,
            set_property_string,
            get_property_string,
            free,
            command,
            wait_event,
            error_string,
        })
    }

    fn error_message(&self, code: c_int) -> String {
        let pointer = unsafe { (self.error_string)(code) };
        if pointer.is_null() {
            return format!("error {code}");
        }
        unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .into_owned()
    }
}

struct Player {
    api: MpvApi,
    handle: *mut c_void,
    expected_path: Option<String>,
    expected_playlist_entry_id: Option<i64>,
    file_loaded: bool,
}

impl Player {
    fn new() -> Result<Self, String> {
        let api = MpvApi::load()?;
        let handle = unsafe { (api.create)() };
        if handle.is_null() {
            return Err("libmpv mpv_create() returned null".to_string());
        }

        let mut player = Self {
            api,
            handle,
            expected_path: None,
            expected_playlist_entry_id: None,
            file_loaded: false,
        };
        let initialized = (|| {
            player.set_option("config", "no")?;
            player.set_option("terminal", "no")?;
            player.set_option("input-default-bindings", "no")?;
            player.set_option("vid", "no")?;
            player.set_option("ytdl", "no")?;
            if let Ok(audio_output) = env::var("FUOEVOLVE_LIBMPV_AO") {
                if !audio_output.trim().is_empty() {
                    player.set_option("ao", audio_output.trim())?;
                }
            }
            let result = unsafe { (player.api.initialize)(player.handle) };
            player.check(result, "mpv_initialize")
        })();
        if let Err(error) = initialized {
            unsafe { (player.api.terminate_destroy)(player.handle) };
            player.handle = ptr::null_mut();
            return Err(error);
        }
        Ok(player)
    }

    fn load(&mut self, url: String, headers: BTreeMap<String, String>) -> Result<(), String> {
        self.expected_path = Some(url.clone());
        self.expected_playlist_entry_id = None;
        self.file_loaded = false;

        let options = encode_loadfile_options(&headers);
        let result = if options.is_empty() {
            self.command(&["loadfile", &url, "replace"])
        } else {
            self.command(&["loadfile", &url, "replace", "-1", &options])
        };
        if result.is_err() {
            self.clear_request();
            return result;
        }
        self.expected_playlist_entry_id = self.current_playlist_entry_id();
        Ok(())
    }

    fn set_paused(&self, paused: bool) -> Result<(), String> {
        self.set_property("pause", if paused { "yes" } else { "no" })
    }

    fn set_volume(&self, value: f64) -> Result<(), String> {
        if !value.is_finite() {
            return Ok(());
        }
        self.set_property(
            "volume",
            &(value.clamp(0.0, 1.0) * MPV_VOLUME_SCALE).to_string(),
        )
    }

    fn stop(&mut self) -> Result<(), String> {
        self.clear_request();
        self.command(&["stop"])
    }

    fn seek(&self, position_ms: i64) -> Result<(), String> {
        let seconds = (position_ms.max(0) as f64) / 1000.0;
        self.command(&["seek", &seconds.to_string(), "absolute"])
    }

    fn process_event(&mut self, output: &mut BufWriter<io::Stdout>) -> Result<bool, String> {
        let event_ptr = unsafe { (self.api.wait_event)(self.handle, EVENT_WAIT_SECONDS) };
        if event_ptr.is_null() {
            return Err("libmpv mpv_wait_event() returned null".to_string());
        }
        let event = unsafe { &*event_ptr };
        match event.event_id {
            MPV_EVENT_NONE => {}
            MPV_EVENT_SHUTDOWN => return Ok(false),
            MPV_EVENT_START_FILE => {
                if self.expected_path.is_some() && !event.data.is_null() {
                    let start = unsafe { &*(event.data as *const MpvEventStartFile) };
                    let accepted = self
                        .expected_playlist_entry_id
                        .map(|expected| expected == start.playlist_entry_id)
                        .unwrap_or(true);
                    if accepted {
                        self.expected_playlist_entry_id = Some(start.playlist_entry_id);
                        emit(
                            output,
                            json!({
                                "type": "startFile",
                                "playlistEntryId": start.playlist_entry_id,
                            }),
                        )?;
                    }
                }
            }
            MPV_EVENT_FILE_LOADED => {
                if let Some(path) = self.expected_path.clone() {
                    let playlist_entry_id = self
                        .expected_playlist_entry_id
                        .or_else(|| self.current_playlist_entry_id());
                    self.expected_playlist_entry_id = playlist_entry_id;
                    self.file_loaded = true;
                    emit(
                        output,
                        json!({
                            "type": "fileLoaded",
                            "path": path,
                            "playlistEntryId": playlist_entry_id,
                        }),
                    )?;
                }
            }
            MPV_EVENT_PLAYBACK_RESTART => {
                if self.expected_path.is_some() {
                    emit(output, json!({"type": "playbackRestart"}))?;
                }
            }
            MPV_EVENT_END_FILE => {
                if !event.data.is_null() {
                    let end = unsafe { &*(event.data as *const MpvEventEndFile) };
                    let matches = self
                        .expected_playlist_entry_id
                        .map(|expected| expected == end.playlist_entry_id)
                        .unwrap_or(self.file_loaded);
                    if matches {
                        let error_message = if end.error < 0 {
                            Some(self.api.error_message(end.error))
                        } else {
                            None
                        };
                        emit(
                            output,
                            json!({
                                "type": "endFile",
                                "playlistEntryId": end.playlist_entry_id,
                                "reason": end.reason,
                                "errorMessage": error_message,
                            }),
                        )?;
                        self.clear_request();
                    }
                }
            }
            _ => {}
        }
        Ok(true)
    }

    fn poll_properties(&self, output: &mut BufWriter<io::Stdout>) -> Result<(), String> {
        if !self.file_loaded {
            return Ok(());
        }
        for name in POLLED_PROPERTIES {
            if let Some(value) = self.get_property_string(name) {
                emit(
                    output,
                    json!({
                        "type": "property",
                        "name": name,
                        "value": value,
                    }),
                )?;
            }
        }
        Ok(())
    }

    fn current_playlist_entry_id(&self) -> Option<i64> {
        let position = self
            .get_property_string("playlist-playing-pos")?
            .parse::<i64>()
            .ok()?;
        if position < 0 {
            return None;
        }
        self.get_property_string(&format!("playlist/{position}/id"))?
            .parse::<i64>()
            .ok()
    }

    fn set_option(&self, name: &str, value: &str) -> Result<(), String> {
        let name = CString::new(name).map_err(|_| "libmpv option name contains NUL".to_string())?;
        let value = CString::new(value).map_err(|_| "libmpv option value contains NUL".to_string())?;
        let result = unsafe { (self.api.set_option_string)(self.handle, name.as_ptr(), value.as_ptr()) };
        self.check(result, "set option")
    }

    fn set_property(&self, name: &str, value: &str) -> Result<(), String> {
        let name = CString::new(name).map_err(|_| "libmpv property name contains NUL".to_string())?;
        let value = CString::new(value).map_err(|_| "libmpv property value contains NUL".to_string())?;
        let result = unsafe {
            (self.api.set_property_string)(self.handle, name.as_ptr(), value.as_ptr())
        };
        self.check(result, "set property")
    }

    fn get_property_string(&self, name: &str) -> Option<String> {
        let name = CString::new(name).ok()?;
        let pointer = unsafe { (self.api.get_property_string)(self.handle, name.as_ptr()) };
        if pointer.is_null() {
            return None;
        }
        let value = unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .into_owned();
        unsafe { (self.api.free)(pointer as *mut c_void) };
        Some(value)
    }

    fn command(&self, args: &[&str]) -> Result<(), String> {
        let strings = args
            .iter()
            .map(|arg| CString::new(*arg).map_err(|_| "libmpv command contains NUL".to_string()))
            .collect::<Result<Vec<_>, _>>()?;
        let mut pointers = strings.iter().map(|arg| arg.as_ptr()).collect::<Vec<_>>();
        pointers.push(ptr::null());
        let result = unsafe { (self.api.command)(self.handle, pointers.as_ptr()) };
        self.check(result, args.first().copied().unwrap_or("command"))
    }

    fn check(&self, result: c_int, operation: &str) -> Result<(), String> {
        if result >= 0 {
            Ok(())
        } else {
            Err(format!(
                "libmpv {operation} failed: {}",
                self.api.error_message(result)
            ))
        }
    }

    fn clear_request(&mut self) {
        self.expected_path = None;
        self.expected_playlist_entry_id = None;
        self.file_loaded = false;
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe { (self.api.terminate_destroy)(self.handle) };
            self.handle = ptr::null_mut();
        }
    }
}

fn main() {
    if let Err(error) = run() {
        eprintln!("fuoevolve-mpv-host: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    // Rust starts in the C locale on supported desktop targets. Keep the intent explicit for
    // libmpv's numeric parser and for child-process diagnostics.
    env::set_var("LC_NUMERIC", "C");

    let mut output = BufWriter::new(io::stdout());
    let mut player = match Player::new() {
        Ok(player) => player,
        Err(error) => {
            let _ = emit(&mut output, json!({"type": "failure", "message": error}));
            return Err("libmpv initialization failed".to_string());
        }
    };
    emit(&mut output, json!({"type": "ready"}))?;

    let commands = spawn_command_reader();
    let mut next_poll = Instant::now();
    let mut running = true;
    while running {
        loop {
            match commands.try_recv() {
                Ok(Ok(command)) => {
                    running = handle_command(&mut player, &mut output, command)?;
                    if !running {
                        break;
                    }
                }
                Ok(Err(error)) => {
                    emit(
                        &mut output,
                        json!({"type": "failure", "message": format!("Invalid mpv helper command: {error}")}),
                    )?;
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return Ok(()),
            }
        }
        if !running {
            break;
        }

        running = player.process_event(&mut output)?;
        if Instant::now() >= next_poll {
            player.poll_properties(&mut output)?;
            next_poll = Instant::now() + POLL_INTERVAL;
        }
    }
    Ok(())
}

fn handle_command(
    player: &mut Player,
    output: &mut BufWriter<io::Stdout>,
    command: HostCommand,
) -> Result<bool, String> {
    let result = match command {
        HostCommand::Load { url, headers } => player.load(url, headers),
        HostCommand::Pause { paused } => player.set_paused(paused),
        HostCommand::Volume { value } => player.set_volume(value),
        HostCommand::Stop => player.stop(),
        HostCommand::Seek { position_ms } => player.seek(position_ms),
        HostCommand::Close => return Ok(false),
    };
    if let Err(error) = result {
        emit(output, json!({"type": "failure", "message": error}))?;
    }
    Ok(true)
}

fn spawn_command_reader() -> Receiver<Result<HostCommand, String>> {
    let (sender, receiver) = mpsc::channel();
    thread::Builder::new()
        .name("fuoevolve-mpv-host-stdin".to_string())
        .spawn(move || {
            let stdin = io::stdin();
            for line in stdin.lock().lines() {
                let parsed = match line {
                    Ok(line) if line.trim().is_empty() => continue,
                    Ok(line) => serde_json::from_str::<HostCommand>(&line).map_err(|error| error.to_string()),
                    Err(error) => Err(error.to_string()),
                };
                if sender.send(parsed).is_err() {
                    break;
                }
            }
        })
        .expect("failed to start mpv helper stdin thread");
    receiver
}

fn emit(output: &mut BufWriter<io::Stdout>, value: Value) -> Result<(), String> {
    serde_json::to_writer(&mut *output, &value).map_err(|error| error.to_string())?;
    output.write_all(b"\n").map_err(|error| error.to_string())?;
    output.flush().map_err(|error| error.to_string())
}

fn encode_loadfile_options(headers: &BTreeMap<String, String>) -> String {
    let valid = headers
        .iter()
        .filter(|(name, value)| {
            !name.trim().is_empty()
                && !name.contains(['\r', '\n', '\0'])
                && !value.contains(['\r', '\n', '\0'])
        })
        .collect::<Vec<_>>();
    if valid.is_empty() {
        return String::new();
    }

    let user_agent = valid
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case("User-Agent"))
        .map(|(_, value)| value.as_str());
    let header_fields = valid
        .iter()
        .filter(|(name, _)| !name.eq_ignore_ascii_case("User-Agent"))
        .map(|(name, value)| escape_string_list_item(&format!("{name}: {value}")))
        .collect::<Vec<_>>()
        .join(",");

    let mut options = Vec::new();
    if let Some(user_agent) = user_agent {
        options.push(format!("user-agent={}", fixed_length(user_agent)));
    }
    if !header_fields.is_empty() {
        options.push(format!(
            "http-header-fields={}",
            fixed_length(&header_fields)
        ));
    }
    options.join(",")
}

fn escape_string_list_item(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => escaped.push_str("\\\\"),
            ',' => escaped.push_str("\\,"),
            _ => escaped.push(ch),
        }
    }
    escaped
}

fn fixed_length(value: &str) -> String {
    format!("%{}%{}", value.as_bytes().len(), value)
}

fn library_candidates() -> Vec<String> {
    let names: &[&str] = if cfg!(target_os = "windows") {
        &["mpv-2.dll", "mpv.dll"]
    } else if cfg!(target_os = "macos") {
        &["libmpv.2.dylib", "libmpv.dylib"]
    } else {
        &["libmpv.so.2", "libmpv.so"]
    };

    let mut candidates = Vec::new();
    if let Ok(explicit) = env::var("FUOEVOLVE_LIBMPV_PATH") {
        if !explicit.trim().is_empty() {
            candidates.push(explicit);
        }
    }

    if let Ok(executable) = env::current_exe() {
        if let Some(parent) = executable.parent() {
            for ancestor in parent.ancestors().take(6) {
                append_local_candidates(&mut candidates, ancestor, names);
            }
        }
    }
    candidates.extend(names.iter().map(|name| (*name).to_string()));

    let mut seen = HashSet::new();
    candidates
        .into_iter()
        .filter(|candidate| seen.insert(candidate.clone()))
        .collect()
}

fn append_local_candidates(candidates: &mut Vec<String>, root: &Path, names: &[&str]) {
    for name in names {
        for path in [
            root.join(name),
            root.join("lib").join(name),
            root.join("native").join(name),
        ] {
            if path.is_file() {
                candidates.push(path_to_string(path));
            }
        }
    }
}

fn path_to_string(path: PathBuf) -> String {
    path.to_string_lossy().into_owned()
}

const POLLED_PROPERTIES: &[&str] = &[
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "volume",
    "file-format",
    "audio-codec-name",
    "audio-bitrate",
];
