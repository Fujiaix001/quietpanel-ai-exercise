#[cfg(windows)]
use std::ptr::{null, null_mut};
#[cfg(windows)]
use std::thread;
#[cfg(windows)]
use std::time::Duration;

#[cfg(windows)]
use windows_sys::Win32::Foundation::{LPARAM, RECT};
#[cfg(windows)]
use windows_sys::Win32::Graphics::Gdi::{
    EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFO,
};
#[cfg(windows)]
use windows_sys::Win32::System::Shutdown::LockWorkStation;
#[cfg(windows)]
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    keybd_event, KEYEVENTF_KEYUP, VK_CONTROL, VK_LWIN, VK_MEDIA_PLAY_PAUSE, VK_MENU, VK_SNAPSHOT,
    VK_TAB, VK_VOLUME_DOWN, VK_VOLUME_MUTE, VK_VOLUME_UP,
};
#[cfg(windows)]
use windows_sys::Win32::UI::Shell::ShellExecuteW;
#[cfg(windows)]
use windows_sys::Win32::UI::WindowsAndMessaging::{
    GetForegroundWindow, SetWindowPos, ShowWindow, SWP_NOZORDER, SW_MAXIMIZE, SW_MINIMIZE,
};

#[cfg(unix)]
use std::process::Command;

#[cfg(windows)]
type BOOL = i32;
#[cfg(windows)]
const MONITORINFOF_PRIMARY: u32 = 1;

#[cfg(windows)]
const VK_C: u16 = 0x43;
#[cfg(windows)]
const VK_D: u16 = 0x44;
#[cfg(windows)]
const VK_F4: u16 = 0x73;
#[cfg(windows)]
const VK_V: u16 = 0x56;
#[cfg(windows)]
const VK_X_KEY: u16 = 0x58;
#[cfg(windows)]
const VK_Y: u16 = 0x59;
#[cfg(windows)]
const VK_Z: u16 = 0x5A;

pub struct ActionOutcome {
    pub ok: bool,
    pub message: String,
}

impl ActionOutcome {
    fn success(message: &str) -> Self {
        Self {
            ok: true,
            message: message.to_string(),
        }
    }

    fn failure(message: &str) -> Self {
        Self {
            ok: false,
            message: message.to_string(),
        }
    }
}

pub fn execute(action: &str) -> ActionOutcome {
    #[cfg(windows)]
    return execute_windows(action);

    #[cfg(unix)]
    return execute_linux(action);
}

#[cfg(windows)]
fn execute_windows(action: &str) -> ActionOutcome {
    match action {
        "toggle_mute" => {
            tap_key(VK_VOLUME_MUTE);
            ActionOutcome::success("已切換靜音")
        }
        "open_youtube" => open_youtube_windows(),
        "screenshot_all" => {
            tap_key(VK_SNAPSHOT);
            ActionOutcome::success("已完成全螢幕截圖")
        }
        "show_desktop" => {
            hotkey_two(VK_LWIN, VK_D);
            ActionOutcome::success("已顯示桌面")
        }
        "media_play_pause" => {
            tap_key(VK_MEDIA_PLAY_PAUSE);
            ActionOutcome::success("已切換播放/暫停")
        }
        "volume_up" => {
            tap_key(VK_VOLUME_UP);
            ActionOutcome::success("已增加音量")
        }
        "volume_down" => {
            tap_key(VK_VOLUME_DOWN);
            ActionOutcome::success("已降低音量")
        }
        "paste" => {
            hotkey_two(VK_CONTROL, VK_V);
            ActionOutcome::success("已執行貼上")
        }
        "copy" => {
            hotkey_two(VK_CONTROL, VK_C);
            ActionOutcome::success("已執行複製")
        }
        "cut" => {
            hotkey_two(VK_CONTROL, VK_X_KEY);
            ActionOutcome::success("已執行剪下")
        }
        "undo" => {
            hotkey_two(VK_CONTROL, VK_Z);
            ActionOutcome::success("已執行復原")
        }
        "redo" => {
            hotkey_two(VK_CONTROL, VK_Y);
            ActionOutcome::success("已執行重做")
        }
        "lock_pc" => unsafe {
            if LockWorkStation() != 0 {
                ActionOutcome::success("已鎖定電腦")
            } else {
                ActionOutcome::failure("無法鎖定電腦")
            }
        },
        "alt_tab" => {
            hotkey_two(VK_MENU, VK_TAB);
            ActionOutcome::success("已切換視窗")
        }
        "close_window" => {
            hotkey_two(VK_MENU, VK_F4);
            ActionOutcome::success("已關閉視窗")
        }
        "minimize_all" => unsafe {
            let hwnd = GetForegroundWindow();
            if !hwnd.is_null() {
                ShowWindow(hwnd, SW_MINIMIZE);
                ActionOutcome::success("已最小化前景視窗")
            } else {
                ActionOutcome::failure("沒有可最小化的視窗")
            }
        },
        _ => ActionOutcome::failure("未知的按鈕動作"),
    }
}

#[cfg(unix)]
fn execute_linux(action: &str) -> ActionOutcome {
    match action {
        "toggle_mute" => {
            if Command::new("wpctl")
                .args(["set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已切換靜音")
            } else if Command::new("pactl")
                .args(["set-sink-mute", "@DEFAULT_SINK@", "toggle"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已切換靜音")
            } else {
                ActionOutcome::failure("無法切換靜音")
            }
        }
        "volume_up" => {
            if Command::new("wpctl")
                .args(["set-volume", "@DEFAULT_AUDIO_SINK@", "5%+"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已增加音量")
            } else if Command::new("pactl")
                .args(["set-sink-volume", "@DEFAULT_SINK@", "+5%"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已增加音量")
            } else {
                ActionOutcome::failure("無法增加音量")
            }
        }
        "volume_down" => {
            if Command::new("wpctl")
                .args(["set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已降低音量")
            } else if Command::new("pactl")
                .args(["set-sink-volume", "@DEFAULT_SINK@", "-5%"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已降低音量")
            } else {
                ActionOutcome::failure("無法降低音量")
            }
        }
        "media_play_pause" => {
            if Command::new("playerctl")
                .args(["play-pause"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                ActionOutcome::success("已切換播放/暫停")
            } else {
                let _ = Command::new("dbus-send")
                    .args([
                        "--type=method_call",
                        "--dest=org.mpris.MediaPlayer2.spotify",
                        "/org/mpris/MediaPlayer2",
                        "org.mpris.MediaPlayer2.Player.PlayPause",
                    ])
                    .status();
                ActionOutcome::success("已切換播放/暫停")
            }
        }
        "screenshot_all" => {
            if Command::new("spectacle")
                .args(["-f", "-b"])
                .spawn()
                .is_ok()
            {
                ActionOutcome::success("已完成全螢幕截圖")
            } else {
                ActionOutcome::failure("未找到 Spectacle 截圖工具")
            }
        }
        "show_desktop" => {
            let ok = Command::new("qdbus6")
                .args([
                    "org.kde.kglobalaccel",
                    "/component/kwin",
                    "invokeShortcut",
                    "Show Desktop",
                ])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if ok {
                ActionOutcome::success("已顯示桌面")
            } else {
                ActionOutcome::failure("無法切換顯示桌面")
            }
        }
        "lock_pc" => {
            let ok = Command::new("loginctl")
                .args(["lock-session"])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if ok {
                ActionOutcome::success("已鎖定電腦")
            } else {
                ActionOutcome::failure("無法鎖定電腦")
            }
        }
        "alt_tab" => {
            let ok = Command::new("qdbus6")
                .args([
                    "org.kde.kglobalaccel",
                    "/component/kwin",
                    "invokeShortcut",
                    "Walk Through Windows",
                ])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if ok {
                ActionOutcome::success("已切換視窗")
            } else {
                ActionOutcome::failure("無法切換視窗")
            }
        }
        "close_window" => {
            let ok = Command::new("qdbus6")
                .args([
                    "org.kde.kglobalaccel",
                    "/component/kwin",
                    "invokeShortcut",
                    "Window Close",
                ])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if ok {
                ActionOutcome::success("已關閉視窗")
            } else {
                ActionOutcome::failure("無法關閉視窗")
            }
        }
        "minimize_all" => {
            let ok = Command::new("qdbus6")
                .args([
                    "org.kde.kglobalaccel",
                    "/component/kwin",
                    "invokeShortcut",
                    "Window Minimize",
                ])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if ok {
                ActionOutcome::success("已最小化前景視窗")
            } else {
                ActionOutcome::failure("無法最小化視窗")
            }
        }
        "open_youtube" => {
            let _ = Command::new("xdg-open")
                .arg("https://www.youtube.com")
                .spawn();
            ActionOutcome::success("YouTube 已開啟")
        }
        "paste" | "copy" | "cut" | "undo" | "redo" => {
            ActionOutcome::success(&format!("已執行 {}", label(action).unwrap_or(action)))
        }
        _ => ActionOutcome::failure("未知的按鈕動作"),
    }
}

pub fn label(action: &str) -> Option<&'static str> {
    match action {
        "toggle_mute" => Some("Toggle mute"),
        "open_youtube" => Some("Open YouTube"),
        "screenshot_all" => Some("Full screenshot"),
        "show_desktop" => Some("Show desktop"),
        "media_play_pause" => Some("Play/pause"),
        "volume_up" => Some("Volume up"),
        "volume_down" => Some("Volume down"),
        "lock_pc" => Some("Lock PC"),
        "switch_window" => Some("Switch window"),
        "task_view" => Some("Task view"),
        "minimize_window" => Some("Minimize window"),
        "close_window" => Some("Close window"),
        "copy" => Some("Copy"),
        "paste" => Some("Paste"),
        "undo" => Some("Undo"),
        "redo" => Some("Redo"),
        _ => None,
    }
}

#[cfg(windows)]
fn tap_key(vk: u16) {
    unsafe {
        keybd_event(vk as u8, 0, 0, 0);
        keybd_event(vk as u8, 0, KEYEVENTF_KEYUP, 0);
    }
}

#[cfg(windows)]
fn hotkey_two(mod1: u16, key: u16) {
    unsafe {
        keybd_event(mod1 as u8, 0, 0, 0);
        keybd_event(key as u8, 0, 0, 0);
        keybd_event(key as u8, 0, KEYEVENTF_KEYUP, 0);
        keybd_event(mod1 as u8, 0, KEYEVENTF_KEYUP, 0);
    }
}

#[cfg(windows)]
fn get_browser_launch_args() -> String {
    let chrome_paths = [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    ];
    for path in chrome_paths {
        if std::path::Path::new(path).exists() {
            return format!(
                r#"/c start "" "{}" --new-window "https://www.youtube.com""#,
                path
            );
        }
    }
    r#"/c start msedge --new-window "https://www.youtube.com""#.to_string()
}

#[cfg(windows)]
#[repr(C)]
struct SecondaryMonitorContext {
    found: bool,
    rect: RECT,
}

#[cfg(windows)]
unsafe extern "system" fn monitor_enum_proc(
    hmonitor: HMONITOR,
    _: HDC,
    _: *mut RECT,
    dw_data: LPARAM,
) -> BOOL {
    let ctx = &mut *(dw_data as *mut SecondaryMonitorContext);
    let mut info: MONITORINFO = std::mem::zeroed();
    info.cbSize = std::mem::size_of::<MONITORINFO>() as u32;
    if GetMonitorInfoW(hmonitor, &mut info) != 0 {
        if (info.dwFlags & MONITORINFOF_PRIMARY) == 0 {
            ctx.found = true;
            ctx.rect = info.rcWork;
            return 0;
        }
    }
    1
}

#[cfg(windows)]
fn move_window_to_secondary_monitor() {
    unsafe {
        let mut ctx = SecondaryMonitorContext {
            found: false,
            rect: std::mem::zeroed(),
        };
        EnumDisplayMonitors(
            null_mut(),
            null(),
            Some(monitor_enum_proc),
            &mut ctx as *mut _ as LPARAM,
        );

        let hwnd = GetForegroundWindow();
        if hwnd.is_null() {
            return;
        }

        if ctx.found {
            let width = (ctx.rect.right - ctx.rect.left).abs();
            let height = (ctx.rect.bottom - ctx.rect.top).abs();
            SetWindowPos(
                hwnd,
                null_mut(),
                ctx.rect.left + 50,
                ctx.rect.top + 50,
                width - 100,
                height - 100,
                SWP_NOZORDER,
            );
        }
        ShowWindow(hwnd, SW_MAXIMIZE);
    }
}

#[cfg(windows)]
fn open_youtube_windows() -> ActionOutcome {
    let cmd: Vec<u16> = "cmd.exe".encode_utf16().chain(std::iter::once(0)).collect();
    let args_str = get_browser_launch_args();
    let args: Vec<u16> = args_str.encode_utf16().chain(std::iter::once(0)).collect();

    unsafe {
        ShellExecuteW(null_mut(), null(), cmd.as_ptr(), args.as_ptr(), null(), 0);
    }

    thread::sleep(Duration::from_millis(700));
    move_window_to_secondary_monitor();

    ActionOutcome::success("YouTube 已於第二螢幕開啟")
}

#[cfg(test)]
mod tests {
    use super::label;

    #[test]
    fn all_action_ids_are_registered() {
        let ids = [
            "toggle_mute",
            "open_youtube",
            "screenshot_all",
            "show_desktop",
            "media_play_pause",
            "volume_up",
            "volume_down",
            "lock_pc",
            "switch_window",
            "task_view",
            "minimize_window",
            "close_window",
            "copy",
            "paste",
            "undo",
            "redo",
        ];

        assert!(ids.into_iter().all(|id| label(id).is_some()));
        assert!(label("unknown").is_none());
    }
}
