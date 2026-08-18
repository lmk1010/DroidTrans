use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

use tauri::{Manager, RunEvent};

struct Backend(Mutex<Option<Child>>);

fn looks_like_web(dir: &Path) -> bool {
    dir.join("app.py").is_file()
}

fn resolve_web_dir(app: &tauri::AppHandle) -> PathBuf {
    if let Ok(res) = app.path().resource_dir() {
        if looks_like_web(&res) {
            return res;
        }
        if looks_like_web(&res.join("web")) {
            return res.join("web");
        }
    }
    let exe = std::env::current_exe().unwrap_or_default();
    let mut dir = exe.parent().unwrap_or(Path::new(".")).to_path_buf();
    for _ in 0..8 {
        if looks_like_web(&dir.join("web")) {
            return dir.join("web");
        }
        if !dir.pop() {
            break;
        }
    }
    PathBuf::from("../web")
}

fn python_bin(web: &Path) -> PathBuf {
    let venv = if cfg!(windows) {
        web.join("venv").join("Scripts").join("python.exe")
    } else {
        web.join("venv").join("bin").join("python")
    };
    if venv.is_file() {
        return venv;
    }
    PathBuf::from(if cfg!(windows) { "python" } else { "python3" })
}

fn adb_path_env() -> String {
    let home = std::env::var("HOME").unwrap_or_default();
    let sdk = format!("{home}/Library/Android/sdk/platform-tools");
    let old = std::env::var("PATH").unwrap_or_default();
    format!("{sdk}:/opt/homebrew/bin:/usr/local/bin:{old}")
}

fn spawn_web(web: &Path) -> Option<Child> {
    let python = python_bin(web);
    let mut cmd = Command::new(&python);
    cmd.arg("app.py")
        .current_dir(web)
        .env("PATH", adb_path_env())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    match cmd.spawn() {
        Ok(child) => Some(child),
        Err(err) => {
            eprintln!("failed to start web backend ({python:?} in {web:?}): {err}");
            None
        }
    }
}

fn wait_for_port(timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if TcpStream::connect_timeout(
            &"127.0.0.1:9500".parse().unwrap(),
            Duration::from_millis(400),
        )
        .is_ok()
        {
            return true;
        }
        thread::sleep(Duration::from_millis(250));
    }
    false
}

fn main() {
    tauri::Builder::default()
        .manage(Backend(Mutex::new(None)))
        .setup(|app| {
            let web = resolve_web_dir(app.handle());
            let child = spawn_web(&web);
            if let Ok(mut slot) = app.state::<Backend>().0.lock() {
                *slot = child;
            }
            let handle = app.handle().clone();
            thread::spawn(move || {
                if wait_for_port(Duration::from_secs(25)) {
                    if let Some(window) = handle.get_webview_window("main") {
                        let _ = window.eval("window.location.replace('http://127.0.0.1:9500')");
                    }
                } else if let Some(window) = handle.get_webview_window("main") {
                    let _ = window.eval(
                        "document.getElementById('status').textContent='本地服务启动失败。请先 cd web && ./start.sh'",
                    );
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building DroidTrans")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Ok(mut slot) = app.state::<Backend>().0.lock() {
                    if let Some(mut child) = slot.take() {
                        let _ = child.kill();
                    }
                }
            }
        });
}
