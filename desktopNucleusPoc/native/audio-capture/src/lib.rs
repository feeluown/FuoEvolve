use std::collections::VecDeque;
use std::fmt::Display;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex, MutexGuard, OnceLock};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{
    Data, FromSample, InputCallbackInfo, Sample, SampleFormat, SizedSample, SupportedStreamConfig,
};
use jni::objects::{JFloatArray, JObject};
use jni::sys::{jfloatArray, jint, jlong, jstring};
use jni::{errors::ThrowRuntimeExAndDefault, EnvUnowned};

const TARGET_SAMPLE_RATE: u32 = 48_000;
const RING_CAPACITY_SAMPLES: usize = TARGET_SAMPLE_RATE as usize * 2;
const CALLBACK_MAX_FRAMES: usize = 8_192;
const OUTPUT_CHUNK_SAMPLES: usize = 8_192;
const MAX_PENDING_SAMPLES: usize = TARGET_SAMPLE_RATE as usize * 2;
const READ_TIMEOUT: Duration = Duration::from_millis(250);
const MAX_ERROR_BYTES: usize = 512;
const MAX_DEVICE_ATTEMPTS: usize = 4;
const MAX_PULSE_DEVICES: usize = 64;
const READ_CANCELLED: jint = -1;
const READ_FAILED: jint = -2;

static LAST_ERROR: OnceLock<Mutex<Option<String>>> = OnceLock::new();

fn last_error() -> &'static Mutex<Option<String>> {
    LAST_ERROR.get_or_init(|| Mutex::new(None))
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn bounded_message(message: impl Display) -> String {
    let mut message = message.to_string();
    if message.len() > MAX_ERROR_BYTES {
        let suffix = "...";
        let mut end = MAX_ERROR_BYTES.saturating_sub(suffix.len());
        while !message.is_char_boundary(end) {
            end -= 1;
        }
        message.truncate(end);
        message.push_str(suffix);
    }
    message
}

fn set_last_error(message: impl Display) {
    *lock(last_error()) = Some(bounded_message(message));
}

fn clear_last_error() {
    *lock(last_error()) = None;
}

fn take_last_error() -> Option<String> {
    lock(last_error()).take()
}

struct CaptureState {
    samples: Mutex<VecDeque<f32>>,
    wake: Condvar,
    cancelled: AtomicBool,
    error: Mutex<Option<String>>,
}

impl CaptureState {
    fn new() -> Self {
        Self {
            samples: Mutex::new(VecDeque::with_capacity(RING_CAPACITY_SAMPLES)),
            wake: Condvar::new(),
            cancelled: AtomicBool::new(false),
            error: Mutex::new(None),
        }
    }

    fn push(&self, samples: &[f32]) -> bool {
        if samples.is_empty() || self.cancelled.load(Ordering::Acquire) {
            return false;
        }

        let mut buffer = lock(&self.samples);
        if buffer.len().saturating_add(samples.len()) > RING_CAPACITY_SAMPLES {
            drop(buffer);
            self.fail("系统音频采集缓冲区溢出");
            return false;
        }
        buffer.extend(samples.iter().copied());
        drop(buffer);
        self.wake.notify_one();
        true
    }

    fn read(&self, max_samples: usize) -> ReadResult {
        let mut buffer = lock(&self.samples);
        loop {
            if !buffer.is_empty() {
                let count = max_samples.min(buffer.len());
                return ReadResult::Samples(buffer.drain(..count).collect());
            }
            if let Some(message) = lock(&self.error).clone() {
                return ReadResult::Failed(message);
            }
            if self.cancelled.load(Ordering::Acquire) {
                return ReadResult::Cancelled;
            }

            let (next_buffer, wait_result) = self
                .wake
                .wait_timeout(buffer, READ_TIMEOUT)
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            buffer = next_buffer;
            if wait_result.timed_out() {
                return ReadResult::Timeout;
            }
        }
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    fn fail(&self, message: impl Display) {
        let mut error = lock(&self.error);
        if error.is_none() {
            *error = Some(bounded_message(message));
        }
        drop(error);
        self.cancelled.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    fn error(&self) -> Option<String> {
        lock(&self.error).clone()
    }
}

enum ReadResult {
    Samples(Vec<f32>),
    Timeout,
    Cancelled,
    Failed(String),
}

struct CaptureHandle {
    state: std::sync::Arc<CaptureState>,
    _stream: cpal::Stream,
}

impl CaptureHandle {
    fn open() -> Result<Self, String> {
        let (device, supported_config) = select_capture_device()?;
        let channels = supported_config.channels() as usize;
        if channels == 0 {
            return Err("系统音频设备返回了无效的声道数".to_string());
        }

        let sample_format = supported_config.sample_format();
        let sample_rate = supported_config.sample_rate();
        let config = supported_config.into();
        let state = std::sync::Arc::new(CaptureState::new());
        let callback_state = state.clone();
        let mut resampler = LinearResampler::new(sample_rate);
        let data_callback = move |data: &Data, _info: &InputCallbackInfo| {
            process_audio_data(data, channels, &mut resampler, &callback_state);
        };
        let error_state = state.clone();
        let error_callback = move |error| {
            error_state.fail(format!("系统音频采集失败：{}", bounded_message(error)));
        };
        let stream = device
            .build_input_stream_raw(
                config,
                sample_format,
                data_callback,
                error_callback,
                Some(READ_TIMEOUT),
            )
            .map_err(|error| format!("无法打开系统音频采集流：{}", bounded_message(error)))?;
        stream
            .play()
            .map_err(|error| format!("无法启动系统音频采集流：{}", bounded_message(error)))?;

        Ok(Self {
            state,
            _stream: stream,
        })
    }

    fn cancel(&self) {
        self.state.cancel();
    }
}

struct LinearResampler {
    step: f64,
    position: f64,
    pending: Vec<f32>,
}

impl LinearResampler {
    fn new(input_sample_rate: u32) -> Self {
        Self {
            step: input_sample_rate as f64 / TARGET_SAMPLE_RATE as f64,
            position: 0.0,
            pending: Vec::with_capacity(CALLBACK_MAX_FRAMES + 1),
        }
    }

    fn push(&mut self, input: &[f32], state: &CaptureState) {
        if input.is_empty() || state.cancelled.load(Ordering::Acquire) {
            return;
        }
        self.pending.extend_from_slice(input);
        if self.pending.len() > MAX_PENDING_SAMPLES {
            state.fail("系统音频重采样缓冲区溢出");
            return;
        }

        let mut output = Vec::with_capacity(OUTPUT_CHUNK_SAMPLES);
        while self.position + 1.0 < self.pending.len() as f64 {
            let index = self.position.floor() as usize;
            let fraction = (self.position - index as f64) as f32;
            let sample =
                self.pending[index] * (1.0 - fraction) + self.pending[index + 1] * fraction;
            output.push(sample.clamp(-1.0, 1.0));
            self.position += self.step;

            if output.len() == OUTPUT_CHUNK_SAMPLES {
                if !state.push(&output) {
                    return;
                }
                output.clear();
            }
        }

        let consumed = self.position.floor() as usize;
        if consumed > 0 {
            self.pending.drain(..consumed);
            self.position -= consumed as f64;
        }
        if !output.is_empty() {
            state.push(&output);
        }
    }
}

fn process_audio_data(
    data: &Data,
    channels: usize,
    resampler: &mut LinearResampler,
    state: &CaptureState,
) {
    match data.sample_format() {
        SampleFormat::I8 => feed_typed::<i8>(data, channels, resampler, state),
        SampleFormat::I16 => feed_typed::<i16>(data, channels, resampler, state),
        SampleFormat::I24 => feed_typed::<cpal::I24>(data, channels, resampler, state),
        SampleFormat::I32 => feed_typed::<i32>(data, channels, resampler, state),
        SampleFormat::I64 => feed_typed::<i64>(data, channels, resampler, state),
        SampleFormat::U8 => feed_typed::<u8>(data, channels, resampler, state),
        SampleFormat::U16 => feed_typed::<u16>(data, channels, resampler, state),
        SampleFormat::U24 => feed_typed::<cpal::U24>(data, channels, resampler, state),
        SampleFormat::U32 => feed_typed::<u32>(data, channels, resampler, state),
        SampleFormat::U64 => feed_typed::<u64>(data, channels, resampler, state),
        SampleFormat::F32 => feed_typed::<f32>(data, channels, resampler, state),
        SampleFormat::F64 => feed_typed::<f64>(data, channels, resampler, state),
        _ => state.fail(format!("系统音频格式 {} 暂不支持", data.sample_format())),
    }
}

fn feed_typed<T>(
    data: &Data,
    channels: usize,
    resampler: &mut LinearResampler,
    state: &CaptureState,
) where
    T: SizedSample + Copy,
    f32: FromSample<T>,
{
    let Some(samples) = data.as_slice::<T>() else {
        state.fail("系统音频采集返回了不匹配的样本格式");
        return;
    };

    for chunk in samples.chunks(CALLBACK_MAX_FRAMES * channels) {
        let mut mono = Vec::with_capacity(chunk.len() / channels);
        for frame in chunk.chunks_exact(channels) {
            let sum = frame
                .iter()
                .map(|sample| f32::from_sample(*sample))
                .sum::<f32>();
            let average = (sum / channels as f32).clamp(-1.0, 1.0);
            mono.push(if average.is_finite() { average } else { 0.0 });
        }
        resampler.push(&mono, state);
        if state.cancelled.load(Ordering::Acquire) {
            return;
        }
    }
}

fn add_attempt(attempts: &mut Vec<String>, message: impl Display) {
    if attempts.len() < MAX_DEVICE_ATTEMPTS {
        attempts.push(bounded_message(message));
    }
}

fn open_device_config(
    device: cpal::Device,
    description: &str,
) -> Result<(cpal::Device, SupportedStreamConfig), String> {
    let config = device.default_input_config().map_err(|error| {
        format!(
            "{} 的输入配置不可用：{}",
            description,
            bounded_message(error)
        )
    })?;
    Ok((device, config))
}

#[cfg(target_os = "linux")]
fn select_capture_device() -> Result<(cpal::Device, SupportedStreamConfig), String> {
    let mut attempts = Vec::new();

    match cpal::host_from_id(cpal::HostId::PipeWire) {
        Ok(host) => match host.devices() {
            Ok(devices) => {
                let devices = devices.collect::<Vec<_>>();
                // PipeWire's default_output device is output-only. The synthetic sink_default
                // device is the input-capable loopback endpoint and sets STREAM_CAPTURE_SINK when
                // CPAL opens an input stream on it.
                let default_sink = devices
                    .iter()
                    .find(|device| {
                        device
                            .id()
                            .map(|id| id.id() == "sink_default")
                            .unwrap_or(false)
                    })
                    .cloned()
                    .or_else(|| {
                        devices
                            .into_iter()
                            .find(|device| device.supports_input() && device.supports_output())
                    });
                match default_sink {
                    Some(device) => match open_device_config(device, "PipeWire 默认输出监视器")
                    {
                        Ok(result) => return Ok(result),
                        Err(error) => add_attempt(&mut attempts, error),
                    },
                    None => add_attempt(&mut attempts, "PipeWire 没有可回采的默认输出设备"),
                }
            }
            Err(error) => add_attempt(
                &mut attempts,
                format!("PipeWire 音频设备不可用：{}", bounded_message(error)),
            ),
        },
        Err(error) => add_attempt(
            &mut attempts,
            format!("PipeWire 不可用：{}", bounded_message(error)),
        ),
    }

    match cpal::host_from_id(cpal::HostId::PulseAudio) {
        Ok(host) => match host.input_devices() {
            Ok(devices) => {
                for device in devices.take(MAX_PULSE_DEVICES) {
                    let display_name = device.to_string();
                    let device_id = device.id().map(|id| id.to_string()).unwrap_or_default();
                    if !display_name.ends_with(".monitor") && !device_id.ends_with(".monitor") {
                        continue;
                    }
                    match open_device_config(device, "PulseAudio 输出监视器") {
                        Ok(result) => return Ok(result),
                        Err(error) => add_attempt(&mut attempts, error),
                    }
                }
            }
            Err(error) => add_attempt(
                &mut attempts,
                format!("PulseAudio 输入设备不可用：{}", bounded_message(error)),
            ),
        },
        Err(error) => add_attempt(
            &mut attempts,
            format!("PulseAudio 不可用：{}", bounded_message(error)),
        ),
    }

    let detail = if attempts.is_empty() {
        "没有找到系统输出监视器".to_string()
    } else {
        attempts.join("；")
    };
    Err(format!(
        "系统音频采集不可用：{}。请确认 PipeWire/PulseAudio 正在运行并存在默认输出设备",
        bounded_message(detail),
    ))
}

#[cfg(not(target_os = "linux"))]
fn select_capture_device() -> Result<(cpal::Device, SupportedStreamConfig), String> {
    let host = cpal::default_host();
    let device = host.default_output_device().ok_or_else(|| {
        if cfg!(target_os = "windows") {
            "WASAPI 没有默认输出设备".to_string()
        } else if cfg!(target_os = "macos") {
            "CoreAudio 没有默认输出设备".to_string()
        } else {
            "当前平台没有默认输出设备".to_string()
        }
    })?;
    let description = if cfg!(target_os = "windows") {
        "WASAPI 默认输出设备"
    } else if cfg!(target_os = "macos") {
        "CoreAudio 默认输出设备"
    } else {
        "默认输出设备"
    };
    open_device_config(device, description)
}

fn handle_from_jlong(value: jlong) -> Option<&'static CaptureHandle> {
    if value == 0 {
        return None;
    }
    // The pointer is owned by the Java-side capture session and remains valid until nativeClose.
    unsafe { (value as usize as *const CaptureHandle).as_ref() }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_feeluown_mobile_DesktopAudioCaptureNative_nativeOpen<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> jlong {
    unowned_env
        .with_env(|_| -> jni::errors::Result<jlong> {
            clear_last_error();
            match CaptureHandle::open() {
                Ok(handle) => Ok(Box::into_raw(Box::new(handle)) as jlong),
                Err(error) => {
                    set_last_error(error);
                    Ok(0)
                }
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Reads captured mono samples into the Java float array supplied by the JVM.
///
/// # Safety
///
/// The JVM must pass a valid `jfloatArray` and a handle returned by
/// `nativeOpen` that has not been closed. The Java-side capture session
/// serializes reads and close operations for that handle.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_feeluown_mobile_DesktopAudioCaptureNative_nativeRead<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle_value: jlong,
    target: jfloatArray,
    offset: jint,
    length: jint,
) -> jint {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jint> {
            let Some(handle) = handle_from_jlong(handle_value) else {
                set_last_error("系统音频采集句柄无效");
                return Ok(READ_FAILED);
            };
            if target.is_null() || offset < 0 || length <= 0 {
                set_last_error("系统音频采集缓冲区参数无效");
                return Ok(READ_FAILED);
            }

            let array = unsafe { JFloatArray::from_raw(env, target) };
            let array_length = array.len(env)?;
            let end = (offset as usize).checked_add(length as usize);
            if end.is_none_or(|end| end > array_length) {
                set_last_error("系统音频采集缓冲区范围无效");
                return Ok(READ_FAILED);
            }

            match handle.state.read(length as usize) {
                ReadResult::Samples(samples) => {
                    array.set_region(env, offset, &samples)?;
                    Ok(samples.len() as jint)
                }
                ReadResult::Timeout => Ok(0),
                ReadResult::Cancelled => Ok(READ_CANCELLED),
                ReadResult::Failed(error) => {
                    set_last_error(error);
                    Ok(READ_FAILED)
                }
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_feeluown_mobile_DesktopAudioCaptureNative_nativeCancel<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle_value: jlong,
) {
    unowned_env
        .with_env(|_| -> jni::errors::Result<()> {
            if let Some(handle) = handle_from_jlong(handle_value) {
                handle.cancel();
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_feeluown_mobile_DesktopAudioCaptureNative_nativeClose<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle_value: jlong,
) {
    unowned_env
        .with_env(|_| -> jni::errors::Result<()> {
            if handle_value != 0 {
                let handle = unsafe { Box::from_raw(handle_value as usize as *mut CaptureHandle) };
                handle.cancel();
                drop(handle);
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_feeluown_mobile_DesktopAudioCaptureNative_nativeLastError<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle_value: jlong,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let message = if let Some(handle) = handle_from_jlong(handle_value) {
                handle.state.error()
            } else {
                take_last_error()
            };
            match message {
                Some(message) => Ok(env.new_string(message)?.into_raw()),
                None => Ok(std::ptr::null_mut()),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn downmix_averages_interleaved_channels() {
        let input = [1.0_f32, -1.0, 0.25, 0.75, 0.5, 0.5];
        let state = CaptureState::new();
        let mut resampler = LinearResampler::new(TARGET_SAMPLE_RATE);
        feed_typed::<f32>(
            &unsafe { Data::from_parts(input.as_ptr() as *mut (), input.len(), SampleFormat::F32) },
            2,
            &mut resampler,
            &state,
        );
        let output: Vec<f32> = lock(&state.samples).iter().copied().collect();
        assert_eq!(output, vec![0.0, 0.5]);
    }

    #[test]
    fn resampler_keeps_output_at_target_rate() {
        let state = CaptureState::new();
        let mut resampler = LinearResampler::new(24_000);
        resampler.push(&[0.0, 1.0, 0.0, -1.0], &state);
        let output: Vec<f32> = lock(&state.samples).iter().copied().collect();
        assert_eq!(output.len(), 6);
        assert_eq!(output[0], 0.0);
        assert_eq!(output[1], 0.5);
        assert_eq!(output[2], 1.0);
    }
}
