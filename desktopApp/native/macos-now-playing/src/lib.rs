#![cfg(target_os = "macos")]

use std::ffi::{c_char, CStr};
use std::ptr;
use std::time::Duration;

use playwire::{
    Capabilities, Event, MediaControls, PlaybackState, PlayerConfig, Repeat, Track,
};

type EventCallback = extern "C" fn(action: i32, value: i64);

const ACTION_PLAY: i32 = 1;
const ACTION_PAUSE: i32 = 2;
const ACTION_STOP: i32 = 3;
const ACTION_NEXT: i32 = 4;
const ACTION_PREVIOUS: i32 = 5;
const ACTION_SEEK_TO: i32 = 6;
const ACTION_TOGGLE: i32 = 7;

const STATUS_STOPPED: i32 = 0;
const STATUS_PLAYING: i32 = 1;

struct Bridge {
    controls: MediaControls,
}

impl Bridge {
    fn new(callback: EventCallback) -> playwire::Result<Self> {
        let controls = MediaControls::new(PlayerConfig::new("FuoEvolve"), move |event| {
            let (action, value) = match event {
                Event::Play => (ACTION_PLAY, 0),
                Event::Pause => (ACTION_PAUSE, 0),
                Event::PlayPause => (ACTION_TOGGLE, 0),
                Event::Stop => (ACTION_STOP, 0),
                Event::Next => (ACTION_NEXT, 0),
                Event::Previous => (ACTION_PREVIOUS, 0),
                Event::SeekTo(position) => (
                    ACTION_SEEK_TO,
                    position.as_millis().min(i64::MAX as u128) as i64,
                ),
                _ => return,
            };
            callback(action, value);
        })?;
        Ok(Self { controls })
    }

    #[allow(clippy::too_many_arguments)]
    fn update(
        &mut self,
        status: i32,
        position_ms: i64,
        duration_ms: i64,
        has_track: bool,
        _can_play: bool,
        _can_pause: bool,
        can_next: bool,
        can_previous: bool,
        _queue_index: i64,
        _queue_count: i64,
        track_id: String,
        title: String,
        artist: String,
        album: String,
    ) -> playwire::Result<()> {
        let duration = (duration_ms > 0).then(|| Duration::from_millis(duration_ms as u64));
        let state = PlaybackState {
            track: has_track.then(|| Track {
                id: track_id,
                title,
                artists: if artist.is_empty() { Vec::new() } else { vec![artist] },
                album,
                artwork_url: String::new(),
                url: String::new(),
            }),
            playing: status == STATUS_PLAYING,
            position: Duration::from_millis(position_ms.max(0) as u64),
            duration,
            volume: 1.0,
            repeat: Repeat::Off,
            shuffle: false,
            capabilities: Capabilities {
                can_go_next: can_next,
                can_go_previous: can_previous,
                can_seek: has_track && duration_ms > 0,
            },
        };
        self.controls.set_state(&state)
    }

    fn clear(&mut self) -> playwire::Result<()> {
        self.controls.set_state(&PlaybackState::default())
    }
}

#[no_mangle]
pub extern "C" fn fuo_now_playing_create(
    callback: Option<EventCallback>,
    error_buffer: *mut c_char,
    error_capacity: u64,
) -> *mut Bridge {
    let Some(callback) = callback else {
        write_error(error_buffer, error_capacity, "event callback is null");
        return ptr::null_mut();
    };
    match Bridge::new(callback) {
        Ok(bridge) => Box::into_raw(Box::new(bridge)),
        Err(error) => {
            write_error(error_buffer, error_capacity, &error.to_string());
            ptr::null_mut()
        }
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fuo_now_playing_update(
    bridge: *mut Bridge,
    status: i32,
    position_ms: i64,
    duration_ms: i64,
    has_track: i32,
    can_play: i32,
    can_pause: i32,
    can_next: i32,
    can_previous: i32,
    queue_index: i64,
    queue_count: i64,
    track_id: *const c_char,
    title: *const c_char,
    artist: *const c_char,
    album: *const c_char,
) {
    let Some(bridge) = bridge.as_mut() else { return };
    if let Err(error) = bridge.update(
        status,
        position_ms,
        duration_ms,
        has_track != 0,
        can_play != 0,
        can_pause != 0,
        can_next != 0,
        can_previous != 0,
        queue_index,
        queue_count,
        read_utf8(track_id),
        read_utf8(title),
        read_utf8(artist),
        read_utf8(album),
    ) {
        eprintln!("FuoEvolve Now Playing update failed: {error}");
    }
}

#[no_mangle]
pub unsafe extern "C" fn fuo_now_playing_clear(bridge: *mut Bridge) {
    let Some(bridge) = bridge.as_mut() else { return };
    if let Err(error) = bridge.clear() {
        eprintln!("FuoEvolve Now Playing clear failed: {error}");
    }
}

#[no_mangle]
pub unsafe extern "C" fn fuo_now_playing_destroy(bridge: *mut Bridge) {
    if !bridge.is_null() {
        drop(Box::from_raw(bridge));
    }
}

fn read_utf8(value: *const c_char) -> String {
    if value.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(value) }.to_string_lossy().into_owned()
}

fn write_error(buffer: *mut c_char, capacity: u64, message: &str) {
    if buffer.is_null() || capacity == 0 {
        return;
    }
    let capacity = usize::try_from(capacity).unwrap_or(usize::MAX);
    let bytes = message.as_bytes();
    let length = bytes.len().min(capacity.saturating_sub(1));
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), buffer.cast::<u8>(), length);
        *buffer.add(length) = 0;
    }
}
