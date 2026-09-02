#![cfg(target_os = "linux")]

use std::ffi::c_char;
use std::ptr;

use ksni::blocking::TrayMethods;
use ksni::menu::StandardItem;
use ksni::{MenuItem, Status, Tray};

type EventCallback = extern "C" fn(action: i32, value: i64);

const ACTION_SHOW: i32 = 1;
const ACTION_EXIT: i32 = 2;

#[derive(Debug)]
struct FuoTray {
    callback: EventCallback,
}

impl Tray for FuoTray {
    fn id(&self) -> String {
        "FuoEvolve".into()
    }

    fn title(&self) -> String {
        "FuoEvolve".into()
    }

    fn status(&self) -> Status {
        Status::Active
    }

    fn icon_name(&self) -> String {
        "multimedia-player".into()
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        (self.callback)(ACTION_SHOW, 0);
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        vec![
            StandardItem {
                label: "Show FuoEvolve".into(),
                activate: Box::new(|tray: &mut Self| (tray.callback)(ACTION_SHOW, 0)),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Exit".into(),
                icon_name: "application-exit".into(),
                activate: Box::new(|tray: &mut Self| (tray.callback)(ACTION_EXIT, 0)),
                ..Default::default()
            }
            .into(),
        ]
    }
}

struct Bridge {
    handle: ksni::blocking::Handle<FuoTray>,
}

#[no_mangle]
pub extern "C" fn fuo_linux_tray_create(
    callback: Option<EventCallback>,
    error_buffer: *mut c_char,
    error_capacity: u64,
) -> *mut Bridge {
    let Some(callback) = callback else {
        write_error(error_buffer, error_capacity, "event callback is null");
        return ptr::null_mut();
    };

    match FuoTray { callback }.spawn() {
        Ok(handle) => Box::into_raw(Box::new(Bridge { handle })),
        Err(error) => {
            write_error(error_buffer, error_capacity, &error.to_string());
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fuo_linux_tray_destroy(bridge: *mut Bridge) {
    if bridge.is_null() {
        return;
    }
    let bridge = Box::from_raw(bridge);
    bridge.handle.shutdown().wait();
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
