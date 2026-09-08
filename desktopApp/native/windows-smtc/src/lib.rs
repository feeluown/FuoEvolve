#![cfg(target_os = "windows")]

use std::ffi::{c_char, c_void, CStr};
use std::ptr;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::{self, JoinHandle};
use windows::core::{factory, HSTRING, Result as WindowsResult};
use windows::Foundation::{TimeSpan, TypedEventHandler, Uri};
use windows::Media::{
    AutoRepeatModeChangeRequestedEventArgs, MediaPlaybackAutoRepeatMode, MediaPlaybackStatus,
    MediaPlaybackType, PlaybackPositionChangeRequestedEventArgs, ShuffleEnabledChangeRequestedEventArgs,
    SystemMediaTransportControls, SystemMediaTransportControlsButton,
    SystemMediaTransportControlsButtonPressedEventArgs, SystemMediaTransportControlsTimelineProperties,
};
use windows::Storage::Streams::RandomAccessStreamReference;
use windows::Win32::Foundation::HWND;
use windows::Win32::System::WinRT::{
    ISystemMediaTransportControlsInterop, RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED,
};

type EventCallback = extern "C" fn(action: i32, value: i64);

const ACTION_PLAY: i32 = 1;
const ACTION_PAUSE: i32 = 2;
const ACTION_STOP: i32 = 3;
const ACTION_NEXT: i32 = 4;
const ACTION_PREVIOUS: i32 = 5;
const ACTION_SEEK_TO: i32 = 6;
const ACTION_SET_SHUFFLE: i32 = 7;
const ACTION_SET_REPEAT: i32 = 8;

const STATUS_STOPPED: i32 = 0;
const STATUS_PLAYING: i32 = 1;
const STATUS_PAUSED: i32 = 2;
const STATUS_CHANGING: i32 = 3;

const REPEAT_OFF: i32 = 0;
const REPEAT_ONE: i32 = 1;
const REPEAT_ALL: i32 = 2;

const TICKS_PER_MILLISECOND: i64 = 10_000;

struct Bridge {
    commands: Sender<Command>,
    worker: Option<JoinHandle<()>>,
}

enum Command {
    UpdateState(StateUpdate),
    UpdateMetadata(MetadataUpdate),
    ClearMetadata,
    Shutdown,
}

struct StateUpdate {
    status: i32,
    position_ms: i64,
    duration_ms: i64,
    has_track: bool,
    can_play: bool,
    can_pause: bool,
    can_next: bool,
    can_previous: bool,
    repeat_mode: i32,
    shuffle_enabled: bool,
    can_change_playback_mode: bool,
}

struct MetadataUpdate {
    track_id: String,
    title: String,
    artist: String,
    album: String,
    artwork_url: String,
}

struct RoApartment;

impl RoApartment {
    fn initialize() -> WindowsResult<Self> {
        unsafe { RoInitialize(RO_INIT_MULTITHREADED)? };
        Ok(Self)
    }
}

impl Drop for RoApartment {
    fn drop(&mut self) {
        unsafe { RoUninitialize() };
    }
}

struct SmtcWorker {
    controls: SystemMediaTransportControls,
    timeline: SystemMediaTransportControlsTimelineProperties,
    button_token: i64,
    position_token: i64,
    repeat_token: i64,
    shuffle_token: i64,
}

impl SmtcWorker {
    fn new(hwnd: u64, callback: EventCallback) -> WindowsResult<Self> {
        let interop: ISystemMediaTransportControlsInterop =
            factory::<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>()?;
        let controls: SystemMediaTransportControls = unsafe {
            interop.GetForWindow(HWND(hwnd as usize as *mut c_void))?
        };

        controls.SetIsEnabled(true)?;
        controls.SetIsPlayEnabled(false)?;
        controls.SetIsPauseEnabled(false)?;
        controls.SetIsStopEnabled(false)?;
        controls.SetIsNextEnabled(false)?;
        controls.SetIsPreviousEnabled(false)?;
        controls.SetPlaybackStatus(MediaPlaybackStatus::Stopped)?;
        controls.SetAutoRepeatMode(MediaPlaybackAutoRepeatMode::None)?;
        controls.SetShuffleEnabled(false)?;

        let button_callback = callback;
        let button_handler = TypedEventHandler::<
            SystemMediaTransportControls,
            SystemMediaTransportControlsButtonPressedEventArgs,
        >::new(move |_, args| {
            if let Some(args) = args.as_ref() {
                if let Ok(button) = args.Button() {
                    let action = if button == SystemMediaTransportControlsButton::Play {
                        Some(ACTION_PLAY)
                    } else if button == SystemMediaTransportControlsButton::Pause {
                        Some(ACTION_PAUSE)
                    } else if button == SystemMediaTransportControlsButton::Stop {
                        Some(ACTION_STOP)
                    } else if button == SystemMediaTransportControlsButton::Next {
                        Some(ACTION_NEXT)
                    } else if button == SystemMediaTransportControlsButton::Previous {
                        Some(ACTION_PREVIOUS)
                    } else {
                        None
                    };
                    if let Some(action) = action {
                        button_callback(action, 0);
                    }
                }
            }
            Ok(())
        });
        let button_token = controls.ButtonPressed(&button_handler)?;

        let seek_callback = callback;
        let position_handler = TypedEventHandler::<
            SystemMediaTransportControls,
            PlaybackPositionChangeRequestedEventArgs,
        >::new(move |_, args| {
            if let Some(args) = args.as_ref() {
                if let Ok(position) = args.RequestedPlaybackPosition() {
                    seek_callback(
                        ACTION_SEEK_TO,
                        position.Duration.saturating_div(TICKS_PER_MILLISECOND),
                    );
                }
            }
            Ok(())
        });
        let position_token = controls.PlaybackPositionChangeRequested(&position_handler)?;

        let repeat_callback = callback;
        let repeat_handler = TypedEventHandler::<
            SystemMediaTransportControls,
            AutoRepeatModeChangeRequestedEventArgs,
        >::new(move |_, args| {
            if let Some(args) = args.as_ref() {
                if let Ok(mode) = args.RequestedAutoRepeatMode() {
                    let value = if mode == MediaPlaybackAutoRepeatMode::Track {
                        REPEAT_ONE
                    } else if mode == MediaPlaybackAutoRepeatMode::List {
                        REPEAT_ALL
                    } else {
                        REPEAT_OFF
                    };
                    repeat_callback(ACTION_SET_REPEAT, value as i64);
                }
            }
            Ok(())
        });
        let repeat_token = controls.AutoRepeatModeChangeRequested(&repeat_handler)?;

        let shuffle_callback = callback;
        let shuffle_handler = TypedEventHandler::<
            SystemMediaTransportControls,
            ShuffleEnabledChangeRequestedEventArgs,
        >::new(move |_, args| {
            if let Some(args) = args.as_ref() {
                if let Ok(enabled) = args.RequestedShuffleEnabled() {
                    shuffle_callback(ACTION_SET_SHUFFLE, if enabled { 1 } else { 0 });
                }
            }
            Ok(())
        });
        let shuffle_token = controls.ShuffleEnabledChangeRequested(&shuffle_handler)?;

        let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
        timeline.SetStartTime(time_span(0))?;
        timeline.SetMinSeekTime(time_span(0))?;
        timeline.SetPosition(time_span(0))?;
        timeline.SetEndTime(time_span(0))?;
        timeline.SetMaxSeekTime(time_span(0))?;

        Ok(Self {
            controls,
            timeline,
            button_token,
            position_token,
            repeat_token,
            shuffle_token,
        })
    }

    fn update_state(&self, update: StateUpdate) -> WindowsResult<()> {
        let status = match update.status {
            STATUS_PLAYING => MediaPlaybackStatus::Playing,
            STATUS_PAUSED => MediaPlaybackStatus::Paused,
            STATUS_CHANGING => MediaPlaybackStatus::Changing,
            STATUS_STOPPED | _ => MediaPlaybackStatus::Stopped,
        };
        self.controls.SetPlaybackStatus(status)?;
        self.controls.SetIsPlayEnabled(update.can_play)?;
        self.controls.SetIsPauseEnabled(update.can_pause)?;
        self.controls.SetIsStopEnabled(update.has_track)?;
        self.controls.SetIsNextEnabled(update.can_next)?;
        self.controls.SetIsPreviousEnabled(update.can_previous)?;
        if update.can_change_playback_mode {
            self.controls.SetAutoRepeatMode(match update.repeat_mode {
                REPEAT_ONE => MediaPlaybackAutoRepeatMode::Track,
                REPEAT_ALL => MediaPlaybackAutoRepeatMode::List,
                _ => MediaPlaybackAutoRepeatMode::None,
            })?;
            self.controls.SetShuffleEnabled(update.shuffle_enabled)?;
        } else {
            self.controls.SetAutoRepeatMode(MediaPlaybackAutoRepeatMode::None)?;
            self.controls.SetShuffleEnabled(false)?;
        }

        let duration_ms = update.duration_ms.max(0);
        let position_ms = update.position_ms.clamp(0, duration_ms.max(update.position_ms.max(0)));
        let duration_ticks = duration_ms.saturating_mul(TICKS_PER_MILLISECOND);
        let position_ticks = position_ms.saturating_mul(TICKS_PER_MILLISECOND);

        self.timeline.SetStartTime(time_span(0))?;
        self.timeline.SetMinSeekTime(time_span(0))?;
        self.timeline.SetEndTime(time_span(duration_ticks))?;
        self.timeline.SetMaxSeekTime(time_span(duration_ticks))?;
        self.timeline.SetPosition(time_span(position_ticks))?;
        self.controls.UpdateTimelineProperties(&self.timeline)?;
        Ok(())
    }

    fn update_metadata(&self, update: MetadataUpdate) -> WindowsResult<()> {
        let updater = self.controls.DisplayUpdater()?;
        updater.SetType(MediaPlaybackType::Music)?;
        updater.SetAppMediaId(&HSTRING::from(update.track_id))?;
        let properties = updater.MusicProperties()?;
        properties.SetTitle(&HSTRING::from(update.title))?;
        properties.SetArtist(&HSTRING::from(update.artist))?;
        properties.SetAlbumTitle(&HSTRING::from(update.album))?;
        if !update.artwork_url.is_empty() {
            if let Ok(uri) = Uri::CreateUri(&HSTRING::from(update.artwork_url)) {
                if let Ok(thumbnail) = RandomAccessStreamReference::CreateFromUri(&uri) {
                    let _ = updater.SetThumbnail(&thumbnail);
                }
            }
        }
        updater.Update()?;
        Ok(())
    }

    fn clear_metadata(&self) -> WindowsResult<()> {
        let updater = self.controls.DisplayUpdater()?;
        updater.ClearAll()?;
        updater.Update()?;
        Ok(())
    }

    fn shutdown(&self) {
        let _ = self.controls.RemoveButtonPressed(self.button_token);
        let _ = self.controls.RemovePlaybackPositionChangeRequested(self.position_token);
        let _ = self.controls.RemoveAutoRepeatModeChangeRequested(self.repeat_token);
        let _ = self.controls.RemoveShuffleEnabledChangeRequested(self.shuffle_token);
        let _ = self.controls.SetPlaybackStatus(MediaPlaybackStatus::Stopped);
        let _ = self.controls.SetIsEnabled(false);
    }
}

fn time_span(ticks: i64) -> TimeSpan {
    TimeSpan { Duration: ticks }
}

fn worker_main(
    hwnd: u64,
    callback: EventCallback,
    commands: Receiver<Command>,
    ready: Sender<std::result::Result<(), String>>,
) {
    let apartment = match RoApartment::initialize() {
        Ok(apartment) => apartment,
        Err(error) => {
            let _ = ready.send(Err(error.to_string()));
            return;
        }
    };

    let worker = match SmtcWorker::new(hwnd, callback) {
        Ok(worker) => worker,
        Err(error) => {
            let _ = ready.send(Err(error.to_string()));
            drop(apartment);
            return;
        }
    };

    let _ = ready.send(Ok(()));
    while let Ok(command) = commands.recv() {
        match command {
            Command::UpdateState(update) => {
                if let Err(error) = worker.update_state(update) {
                    eprintln!("FuoEvolve SMTC state update failed: {error}");
                }
            }
            Command::UpdateMetadata(update) => {
                if let Err(error) = worker.update_metadata(update) {
                    eprintln!("FuoEvolve SMTC metadata update failed: {error}");
                }
            }
            Command::ClearMetadata => {
                if let Err(error) = worker.clear_metadata() {
                    eprintln!("FuoEvolve SMTC metadata clear failed: {error}");
                }
            }
            Command::Shutdown => break,
        }
    }
    worker.shutdown();
    drop(worker);
    drop(apartment);
}

#[no_mangle]
pub extern "C" fn fuo_smtc_create(
    hwnd: u64,
    callback: Option<EventCallback>,
    error_buffer: *mut c_char,
    error_capacity: u64,
) -> *mut Bridge {
    if hwnd == 0 {
        write_error(error_buffer, error_capacity, "window handle is zero");
        return ptr::null_mut();
    }
    let Some(callback) = callback else {
        write_error(error_buffer, error_capacity, "event callback is null");
        return ptr::null_mut();
    };

    let (commands_tx, commands_rx) = mpsc::channel();
    let (ready_tx, ready_rx) = mpsc::channel();
    let worker = match thread::Builder::new()
        .name("fuoevolve-smtc".to_owned())
        .spawn(move || worker_main(hwnd, callback, commands_rx, ready_tx))
    {
        Ok(worker) => worker,
        Err(error) => {
            write_error(error_buffer, error_capacity, &error.to_string());
            return ptr::null_mut();
        }
    };

    match ready_rx.recv() {
        Ok(Ok(())) => Box::into_raw(Box::new(Bridge {
            commands: commands_tx,
            worker: Some(worker),
        })),
        Ok(Err(error)) => {
            write_error(error_buffer, error_capacity, &error);
            let _ = worker.join();
            ptr::null_mut()
        }
        Err(error) => {
            write_error(error_buffer, error_capacity, &error.to_string());
            let _ = worker.join();
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fuo_smtc_update_state(
    bridge: *mut Bridge,
    status: i32,
    position_ms: i64,
    duration_ms: i64,
    has_track: i32,
    can_play: i32,
    can_pause: i32,
    can_next: i32,
    can_previous: i32,
    repeat_mode: i32,
    shuffle_enabled: i32,
    can_change_playback_mode: i32,
) {
    let Some(bridge) = bridge.as_ref() else { return };
    let _ = bridge.commands.send(Command::UpdateState(StateUpdate {
        status,
        position_ms,
        duration_ms,
        has_track: has_track != 0,
        can_play: can_play != 0,
        can_pause: can_pause != 0,
        can_next: can_next != 0,
        can_previous: can_previous != 0,
        repeat_mode,
        shuffle_enabled: shuffle_enabled != 0,
        can_change_playback_mode: can_change_playback_mode != 0,
    }));
}

#[no_mangle]
pub unsafe extern "C" fn fuo_smtc_update_metadata(
    bridge: *mut Bridge,
    track_id: *const c_char,
    title: *const c_char,
    artist: *const c_char,
    album: *const c_char,
    artwork_url: *const c_char,
) {
    let Some(bridge) = bridge.as_ref() else { return };
    let update = MetadataUpdate {
        track_id: read_utf8(track_id),
        title: read_utf8(title),
        artist: read_utf8(artist),
        album: read_utf8(album),
        artwork_url: read_utf8(artwork_url),
    };
    let _ = bridge.commands.send(Command::UpdateMetadata(update));
}

#[no_mangle]
pub unsafe extern "C" fn fuo_smtc_clear_metadata(bridge: *mut Bridge) {
    let Some(bridge) = bridge.as_ref() else { return };
    let _ = bridge.commands.send(Command::ClearMetadata);
}

#[no_mangle]
pub unsafe extern "C" fn fuo_smtc_destroy(bridge: *mut Bridge) {
    if bridge.is_null() {
        return;
    }
    let mut bridge = Box::from_raw(bridge);
    let _ = bridge.commands.send(Command::Shutdown);
    if let Some(worker) = bridge.worker.take() {
        let _ = worker.join();
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
