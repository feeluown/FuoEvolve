use serde::{Deserialize, Serialize};
use std::borrow::Cow;
use std::collections::BTreeMap;
use std::error::Error;
use std::io::{self, Read, Write};
use std::sync::mpsc;
use std::time::{Duration, Instant};
use tao::{
    dpi::LogicalSize,
    event::{Event, StartCause, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use wry::{
    http::{header::CONTENT_TYPE, Response},
    NewWindowResponse, WebViewBuilder,
};

const COOKIE_POLL_INTERVAL: Duration = Duration::from_millis(500);
const FINGERPRINT_POLL_INTERVAL: Duration = Duration::from_millis(20);
const FINGERPRINT_TIMEOUT: Duration = Duration::from_secs(20);
const RUNTIME_HTML: &[u8] = include_bytes!(
    "../../../../shared/src/commonMain/resources/audio_recognition/runtime.html"
);
const FINGERPRINT_JS: &[u8] = include_bytes!(
    "../../../../shared/src/commonMain/resources/audio_recognition/fingerprint.js"
);
const AFP_WASM: &[u8] = include_bytes!(
    "../../../../shared/src/commonMain/resources/audio_recognition/afp.wasm"
);

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum HelperRequest {
    Fingerprint(FingerprintRequest),
    Login(LoginRequest),
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FingerprintRequest {
    samples_base64: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LoginRequest {
    provider_id: String,
    provider_name: String,
    login_url: String,
    cookie_key_groups: Vec<Vec<String>>,
    user_agent: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
enum LoginResponse {
    Success { cookies: BTreeMap<String, String> },
    Cancelled,
    Error { message: String },
}

#[derive(Debug, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
enum FingerprintResponse {
    Success { fingerprint: String },
    Error { message: String },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FingerprintBridgeMessage {
    #[serde(default)]
    runtime_ready: bool,
    #[serde(default)]
    request_id: String,
    #[serde(default)]
    fingerprint: String,
    #[serde(default)]
    error: String,
}

fn main() {
    let result = read_request().and_then(|request| match request {
        HelperRequest::Login(request) => run_login(request),
        HelperRequest::Fingerprint(request) => run_fingerprint(request),
    });
    if let Err(error) = result {
        emit_json(&FingerprintResponse::Error {
            message: error.to_string(),
        });
        std::process::exit(1);
    }
}

fn read_request() -> Result<HelperRequest, Box<dyn Error>> {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input)?;
    if input.trim().is_empty() {
        return Err("missing helper request on stdin".into());
    }
    Ok(serde_json::from_str(&input)?)
}

fn run_login(request: LoginRequest) -> Result<(), Box<dyn Error>> {
    if request.provider_id.trim().is_empty() {
        return Err("providerId is required".into());
    }
    if request.login_url.trim().is_empty() {
        return Err("loginUrl is required".into());
    }

    let event_loop = EventLoop::new();
    let title = if request.provider_name.trim().is_empty() {
        format!("FuoEvolve 登录 - {}", request.provider_id)
    } else {
        format!("FuoEvolve 登录 - {}", request.provider_name)
    };
    let window = WindowBuilder::new()
        .with_title(title)
        .with_inner_size(LogicalSize::new(1024.0, 720.0))
        .with_min_inner_size(LogicalSize::new(720.0, 520.0))
        .build(&event_loop)?;

    let mut builder = WebViewBuilder::new()
        .with_url(request.login_url.clone())
        .with_incognito(true)
        .with_clipboard(true)
        .with_new_window_req_handler(|_, _| NewWindowResponse::Allow);
    if let Some(user_agent) = request.user_agent.as_deref().filter(|value| !value.is_empty()) {
        builder = builder.with_user_agent(user_agent);
    }

    #[cfg(target_os = "linux")]
    let webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.gtk_window())?
    };

    #[cfg(not(target_os = "linux"))]
    let webview = builder.build(&window)?;

    let cookie_key_groups = request.cookie_key_groups;
    let mut next_cookie_poll = Instant::now();
    let mut finished = false;

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::WaitUntil(next_cookie_poll);

        match event {
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) | Event::MainEventsCleared => {
                if finished || Instant::now() < next_cookie_poll {
                    return;
                }
                next_cookie_poll = Instant::now() + COOKIE_POLL_INTERVAL;
                match webview.cookies() {
                    Ok(cookies) => {
                        let cookies = cookies
                            .into_iter()
                            .filter(|cookie| !cookie.name().is_empty() && !cookie.value().is_empty())
                            .map(|cookie| (cookie.name().to_owned(), cookie.value().to_owned()))
                            .collect::<BTreeMap<_, _>>();
                        if has_required_cookies(&cookies, &cookie_key_groups) {
                            emit_json(&LoginResponse::Success { cookies });
                            finished = true;
                            *control_flow = ControlFlow::Exit;
                        }
                    }
                    Err(error) => {
                        // Diagnostics must never include cookie values.
                        eprintln!("unable to read WebView cookies: {error}");
                    }
                }
            }
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                if !finished {
                    emit_json(&LoginResponse::Cancelled);
                    finished = true;
                }
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
}

fn run_fingerprint(request: FingerprintRequest) -> Result<(), Box<dyn Error>> {
    if request.samples_base64.trim().is_empty() {
        return Err("samplesBase64 is required".into());
    }

    let event_loop = EventLoop::new();
    let window = WindowBuilder::new()
        .with_title("FuoEvolve Audio Fingerprint")
        .with_visible(false)
        .with_inner_size(LogicalSize::new(1.0, 1.0))
        .build(&event_loop)?;
    let (bridge_sender, bridge_receiver) = mpsc::channel::<FingerprintBridgeMessage>();

    let builder = WebViewBuilder::new()
        .with_visible(false)
        .with_incognito(true)
        .with_custom_protocol("fuofingerprint".into(), |_webview_id, request| {
            fingerprint_resource_response(request.uri().path())
        })
        .with_ipc_handler(move |request| {
            if let Ok(message) = serde_json::from_str::<FingerprintBridgeMessage>(request.body()) {
                let _ = bridge_sender.send(message);
            }
        })
        .with_initialization_script(
            "window.addEventListener('load', () => { void globalThis.fuoFingerprint.verifyRuntime(); });",
        )
        .with_url("fuofingerprint://localhost/runtime.html");

    #[cfg(target_os = "linux")]
    let webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.gtk_window())?
    };

    #[cfg(not(target_os = "linux"))]
    let webview = builder.build(&window)?;

    let samples_base64 = request.samples_base64;
    let started_at = Instant::now();
    let mut next_poll = Instant::now();
    let mut generate_started = false;
    let mut finished = false;

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::WaitUntil(next_poll);
        match event {
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) | Event::MainEventsCleared => {
                if finished || Instant::now() < next_poll {
                    return;
                }
                next_poll = Instant::now() + FINGERPRINT_POLL_INTERVAL;

                if started_at.elapsed() >= FINGERPRINT_TIMEOUT {
                    emit_json(&FingerprintResponse::Error {
                        message: "音频指纹运行时初始化超时".to_string(),
                    });
                    finished = true;
                    *control_flow = ControlFlow::Exit;
                    return;
                }

                while let Ok(message) = bridge_receiver.try_recv() {
                    if message.runtime_ready {
                        if !message.error.is_empty() {
                            emit_json(&FingerprintResponse::Error {
                                message: format!("音频指纹运行时初始化失败：{}", message.error),
                            });
                            finished = true;
                            *control_flow = ControlFlow::Exit;
                            return;
                        }
                        if !generate_started {
                            generate_started = true;
                            let script = format!(
                                "void globalThis.fuoFingerprint.generate('desktop', {});",
                                serde_json::to_string(&samples_base64).unwrap_or_else(|_| "\"\"".into()),
                            );
                            if let Err(error) = webview.evaluate_script(&script) {
                                emit_json(&FingerprintResponse::Error {
                                    message: format!("无法执行音频指纹运行时：{error}"),
                                });
                                finished = true;
                                *control_flow = ControlFlow::Exit;
                                return;
                            }
                        }
                    } else if message.request_id == "desktop" {
                        if message.error.is_empty() && !message.fingerprint.is_empty() {
                            emit_json(&FingerprintResponse::Success {
                                fingerprint: message.fingerprint,
                            });
                        } else {
                            emit_json(&FingerprintResponse::Error {
                                message: if message.error.is_empty() {
                                    "音频指纹结果为空".to_string()
                                } else {
                                    format!("音频指纹生成失败：{}", message.error)
                                },
                            });
                        }
                        finished = true;
                        *control_flow = ControlFlow::Exit;
                        return;
                    }
                }
            }
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                if !finished {
                    emit_json(&FingerprintResponse::Error {
                        message: "音频指纹窗口意外关闭".to_string(),
                    });
                    finished = true;
                }
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
}

fn fingerprint_resource_response(path: &str) -> Response<Cow<'static, [u8]>> {
    let (body, content_type): (&'static [u8], &'static str) = match path {
        "/runtime.html" | "/" => (RUNTIME_HTML, "text/html; charset=utf-8"),
        "/fingerprint.js" => (FINGERPRINT_JS, "application/javascript; charset=utf-8"),
        "/afp.wasm" => (AFP_WASM, "application/wasm"),
        _ => (b"not found", "text/plain; charset=utf-8"),
    };
    Response::builder()
        .header(CONTENT_TYPE, content_type)
        .body(Cow::Borrowed(body))
        .expect("valid embedded recognition response")
}

fn has_required_cookies(
    cookies: &BTreeMap<String, String>,
    cookie_key_groups: &[Vec<String>],
) -> bool {
    !cookie_key_groups.is_empty()
        && cookie_key_groups.iter().any(|group| {
            !group.is_empty()
                && group
                    .iter()
                    .all(|key| cookies.get(key).is_some_and(|value| !value.is_empty()))
        })
}

fn emit_json<T: Serialize>(response: &T) {
    let stdout = io::stdout();
    let mut output = stdout.lock();
    if serde_json::to_writer(&mut output, response).is_ok() {
        let _ = writeln!(output);
        let _ = output.flush();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn required_cookie_group_matches_any_complete_group() {
        let cookies = BTreeMap::from([
            ("MUSIC_U".to_string(), "secret".to_string()),
            ("other".to_string(), "value".to_string()),
        ]);
        assert!(has_required_cookies(
            &cookies,
            &[vec!["MUSIC_U".to_string()], vec!["a".to_string(), "b".to_string()]],
        ));
    }

    #[test]
    fn incomplete_cookie_groups_do_not_match() {
        let cookies = BTreeMap::from([("a".to_string(), "value".to_string())]);
        assert!(!has_required_cookies(
            &cookies,
            &[vec!["a".to_string(), "b".to_string()]],
        ));
    }

    #[test]
    fn helper_request_keeps_legacy_login_shape() {
        let request: HelperRequest = serde_json::from_str(
            r#"{"providerId":"netease","providerName":"NetEase","loginUrl":"https://example.test","cookieKeyGroups":[["MUSIC_U"]]}"#,
        )
        .unwrap();
        assert!(matches!(request, HelperRequest::Login(_)));
    }

    #[test]
    fn helper_request_accepts_fingerprint_shape() {
        let request: HelperRequest = serde_json::from_str(r#"{"samplesBase64":"AQID"}"#).unwrap();
        assert!(matches!(request, HelperRequest::Fingerprint(_)));
    }

    #[test]
    fn embedded_fingerprint_resources_have_expected_mime_types() {
        assert_eq!(
            fingerprint_resource_response("/afp.wasm")
                .headers()
                .get(CONTENT_TYPE)
                .unwrap(),
            "application/wasm",
        );
        assert!(!RUNTIME_HTML.is_empty());
        assert!(!FINGERPRINT_JS.is_empty());
        assert!(!AFP_WASM.is_empty());
    }
}
