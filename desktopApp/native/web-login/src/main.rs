use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::error::Error;
use std::io::{self, Read, Write};
use std::time::{Duration, Instant};
use tao::{
    dpi::LogicalSize,
    event::{Event, StartCause, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use wry::{NewWindowResponse, WebViewBuilder};

const COOKIE_POLL_INTERVAL: Duration = Duration::from_millis(500);

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

fn main() {
    match read_request().and_then(run) {
        Ok(()) => {}
        Err(error) => {
            emit_response(&LoginResponse::Error {
                message: error.to_string(),
            });
            std::process::exit(1);
        }
    }
}

fn read_request() -> Result<LoginRequest, Box<dyn Error>> {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input)?;
    if input.trim().is_empty() {
        return Err("missing login request on stdin".into());
    }
    let request: LoginRequest = serde_json::from_str(&input)?;
    if request.provider_id.trim().is_empty() {
        return Err("providerId is required".into());
    }
    if request.login_url.trim().is_empty() {
        return Err("loginUrl is required".into());
    }
    Ok(request)
}

fn run(request: LoginRequest) -> Result<(), Box<dyn Error>> {
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
                            emit_response(&LoginResponse::Success { cookies });
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
                    emit_response(&LoginResponse::Cancelled);
                    finished = true;
                }
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
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

fn emit_response(response: &LoginResponse) {
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
}
