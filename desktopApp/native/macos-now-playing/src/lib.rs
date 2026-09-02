#![cfg(target_os = "macos")]

use std::ffi::{c_char, CStr};
use std::ptr;

use mediaplayer::prelude::{
    CommandToken, HandlerStatus, NowPlayingInfo, NowPlayingInfoCenter, NowPlayingMediaType,
    PlaybackState, RemoteCommandCenter,
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
const STATUS_PAUSED: i32 = 2;
const STATUS_LOADING: i32 = 3;

struct Bridge {
    center: NowPlayingInfoCenter,
    remote: RemoteCommandCenter,
    _tokens: Vec<CommandToken>,
}

impl Bridge {
    fn new(callback: EventCallback) -> Self {
        let center = NowPlayingInfoCenter::default_center();
        let remote = RemoteCommandCenter::shared();

        let play = remote.on_play(move |_| {
            callback(ACTION_PLAY, 0);
            HandlerStatus::Success
        });
        let pause = remote.on_pause(move |_| {
            callback(ACTION_PAUSE, 0);
            HandlerStatus::Success
        });
        let stop = remote.on_stop(move |_| {
            callback(ACTION_STOP, 0);
            HandlerStatus::Success
        });
        let toggle = remote.on_toggle_play_pause(move |_| {
            callback(ACTION_TOGGLE, 0);
            HandlerStatus::Success
        });
        let next = remote.on_next_track(move |_| {
            callback(ACTION_NEXT, 0);
            HandlerStatus::Success
        });
        let previous = remote.on_previous_track(move |_| {
            callback(ACTION_PREVIOUS, 0);
            HandlerStatus::Success
        });
        let seek = remote.on_change_playback_position(move |event| {
            if let Some(position) = event.position {
                let position_ms = (position.max(0.0) * 1000.0).round();
                callback(ACTION_SEEK_TO, position_ms.min(i64::MAX as f64) as i64);
            }
            HandlerStatus::Success
        });

        remote.play_command().set_enabled(false);
        remote.pause_command().set_enabled(false);
        remote.stop_command().set_enabled(false);
        remote.toggle_play_pause_command().set_enabled(false);
        remote.next_track_command().set_enabled(false);
        remote.previous_track_command().set_enabled(false);
        remote.change_playback_position_command().set_enabled(false);
        center.clear();
        center.set_playback_state(PlaybackState::Stopped);

        Self {
            center,
            remote,
            _tokens: vec![play, pause, stop, toggle, next, previous, seek],
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn update(
        &self,
        status: i32,
        position_ms: i64,
        duration_ms: i64,
        has_track: bool,
        can_play: bool,
        can_pause: bool,
        can_next: bool,
        can_previous: bool,
        queue_index: i64,
        queue_count: i64,
        track_id: String,
        title: String,
        artist: String,
        album: String,
    ) {
        self.remote.play_command().set_enabled(can_play);
        self.remote.pause_command().set_enabled(can_pause);
        self.remote.stop_command().set_enabled(has_track);
        self.remote
            .toggle_play_pause_command()
            .set_enabled(can_play || can_pause);
        self.remote.next_track_command().set_enabled(can_next);
        self.remote
            .previous_track_command()
            .set_enabled(can_previous);
        self.remote
            .change_playback_position_command()
            .set_enabled(has_track && duration_ms > 0);

        let playback_state = match status {
            STATUS_PLAYING => PlaybackState::Playing,
            STATUS_PAUSED | STATUS_LOADING => PlaybackState::Paused,
            STATUS_STOPPED | _ => PlaybackState::Stopped,
        };
        self.center.set_playback_state(playback_state);

        if !has_track {
            self.center.clear();
            return;
        }

        let mut info = NowPlayingInfo::new()
            .title(title)
            .artist(artist)
            .album_title(album)
            .external_content_identifier(track_id)
            .media_type(NowPlayingMediaType::Audio)
            .elapsed_playback_time(position_ms.max(0) as f64 / 1000.0)
            .playback_rate(if status == STATUS_PLAYING { 1.0 } else { 0.0 })
            .default_playback_rate(1.0);
        if duration_ms > 0 {
            info = info.playback_duration(duration_ms as f64 / 1000.0);
        }
        if queue_count > 0 {
            info = info.playback_queue_count(queue_count as u64);
            if queue_index >= 0 {
                info = info.playback_queue_index(queue_index as u64);
            }
        }
        self.center.set_now_playing_info(&info);
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        self.remote.play_command().set_enabled(false);
        self.remote.pause_command().set_enabled(false);
        self.remote.stop_command().set_enabled(false);
        self.remote.toggle_play_pause_command().set_enabled(false);
        self.remote.next_track_command().set_enabled(false);
        self.remote.previous_track_command().set_enabled(false);
        self.remote.change_playback_position_command().set_enabled(false);
        self.center.set_playback_state(PlaybackState::Stopped);
        self.center.clear();
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
    Box::into_raw(Box::new(Bridge::new(callback)))
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
    let Some(bridge) = bridge.as_ref() else { return };
    bridge.update(
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
    );
}

#[no_mangle]
pub unsafe extern "C" fn fuo_now_playing_clear(bridge: *mut Bridge) {
    let Some(bridge) = bridge.as_ref() else { return };
    bridge.center.set_playback_state(PlaybackState::Stopped);
    bridge.center.clear();
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
