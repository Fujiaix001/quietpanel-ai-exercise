pub struct ActionOutcome {
    pub ok: bool,
    pub message: String,
}

impl ActionOutcome {
    pub fn success(message: &str) -> Self {
        Self {
            ok: true,
            message: message.to_string(),
        }
    }

    pub fn failure(message: &str) -> Self {
        Self {
            ok: false,
            message: message.to_string(),
        }
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
pub fn execute(action: &str) -> ActionOutcome {
    use std::ptr::{null, null_mut};
    use std::thread;
    use std::time::Duration;

    use windows_sys::Win32::System::Shutdown::LockWorkStation;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        keybd_event, KEYEVENTF_KEYUP, VK_CONTROL, VK_LWIN, VK_MEDIA_PLAY_PAUSE, VK_MENU, VK_SNAPSHOT,
        VK_TAB, VK_VOLUME_DOWN, VK_VOLUME_MUTE, VK_VOLUME_UP,
    };
    use windows_sys::Win32::UI::Shell::ShellExecuteW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        GetForegroundWindow, ShowWindow, SW_MINIMIZE, SW_SHOWNORMAL,
    };

    const VK_C: u16 = 0x43;
    const VK_D: u16 = 0x44;
    const VK_F4: u16 = 0x73;
    const VK_V: u16 = 0x56;
    const VK_Y: u16 = 0x59;
    const VK_Z: u16 = 0x5A;

    fn tap_key(key: u16) {
        unsafe {
            keybd_event(key as u8, 0, 0, 0);
            thread::sleep(Duration::from_millis(20));
            keybd_event(key as u8, 0, KEYEVENTF_KEYUP, 0);
        }
    }

    fn hotkey(modifier: u16, key: u16) {
        unsafe {
            keybd_event(modifier as u8, 0, 0, 0);
            thread::sleep(Duration::from_millis(35));
            keybd_event(key as u8, 0, 0, 0);
            thread::sleep(Duration::from_millis(35));
            keybd_event(key as u8, 0, KEYEVENTF_KEYUP, 0);
            keybd_event(modifier as u8, 0, KEYEVENTF_KEYUP, 0);
        }
    }

    fn open_youtube() -> ActionOutcome {
        let url: Vec<u16> = "https://www.youtube.com"
            .encode_utf16()
            .chain(std::iter::once(0))
            .collect();

        let result = unsafe {
            ShellExecuteW(
                null_mut(),
                null(),
                url.as_ptr(),
                null(),
                null(),
                SW_SHOWNORMAL,
            )
        };

        if result as usize > 32 {
            ActionOutcome::success("已開啟 YouTube")
        } else {
            ActionOutcome::failure("無法開啟 YouTube")
        }
    }

    fn minimize_foreground_window() -> ActionOutcome {
        let window = unsafe { GetForegroundWindow() };
        if window.is_null() {
            return ActionOutcome::failure("找不到目前視窗");
        }

        unsafe {
            ShowWindow(window, SW_MINIMIZE);
        }
        ActionOutcome::success("目前視窗已最小化")
    }

    match action {
        "toggle_mute" => {
            tap_key(VK_VOLUME_MUTE);
            ActionOutcome::success("已切換靜音")
        }
        "open_youtube" => open_youtube(),
        "screenshot_all" => {
            hotkey(VK_LWIN, VK_SNAPSHOT);
            ActionOutcome::success("全螢幕截圖已儲存")
        }
        "show_desktop" => {
            hotkey(VK_LWIN, VK_D);
            ActionOutcome::success("已切換桌面")
        }
        "media_play_pause" => {
            tap_key(VK_MEDIA_PLAY_PAUSE);
            ActionOutcome::success("已切換播放／暫停")
        }
        "volume_up" => {
            tap_key(VK_VOLUME_UP);
            ActionOutcome::success("音量已提高")
        }
        "volume_down" => {
            tap_key(VK_VOLUME_DOWN);
            ActionOutcome::success("音量已降低")
        }
        "lock_pc" => unsafe {
            if LockWorkStation() != 0 {
                ActionOutcome::success("電腦已鎖定")
            } else {
                ActionOutcome::failure("鎖定電腦失敗")
            }
        },
        "switch_window" => {
            hotkey(VK_MENU, VK_TAB);
            ActionOutcome::success("已切換視窗")
        }
        "task_view" => {
            hotkey(VK_LWIN, VK_TAB);
            ActionOutcome::success("已開啟工作檢視")
        }
        "minimize_window" => minimize_foreground_window(),
        "close_window" => {
            hotkey(VK_MENU, VK_F4);
            ActionOutcome::success("已送出關閉視窗")
        }
        "copy" => {
            hotkey(VK_CONTROL, VK_C);
            ActionOutcome::success("已複製")
        }
        "paste" => {
            hotkey(VK_CONTROL, VK_V);
            ActionOutcome::success("已貼上")
        }
        "undo" => {
            hotkey(VK_CONTROL, VK_Z);
            ActionOutcome::success("已復原")
        }
        "redo" => {
            hotkey(VK_CONTROL, VK_Y);
            ActionOutcome::success("已重做")
        }
        _ => ActionOutcome::failure("未知的 Macro 指令"),
    }
}

#[cfg(not(windows))]
pub fn execute(action: &str) -> ActionOutcome {
    use std::env;
    use std::process::Command;
    use std::time::{SystemTime, UNIX_EPOCH};

    // ponytail: fallback chain for Linux audio, screenshot, Wayland uinput keys, and GNOME DBus
    fn run_cmd(program: &str, args: &[&str], success_msg: &str) -> ActionOutcome {
        match Command::new(program).args(args).status() {
            Ok(status) if status.success() => ActionOutcome::success(success_msg),
            Ok(_) => ActionOutcome::failure(&format!("{program} 執行傳回非零狀態")),
            Err(e) => ActionOutcome::failure(&format!("無法執行 {program}: {e}")),
        }
    }

    fn ydotool_key(key_combo: &str, xdotool_fallback: &str, success_msg: &str) -> ActionOutcome {
        let mut cmd = Command::new("ydotool");
        cmd.arg("key").arg("--key-delay").arg("30").arg(key_combo);
        match cmd.status() {
            Ok(status) if status.success() => ActionOutcome::success(success_msg),
            _ => run_cmd("xdotool", &["key", xdotool_fallback], success_msg),
        }
    }

    fn take_screenshot() -> ActionOutcome {
        let home = env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
        let secs = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let path = format!("{home}/Pictures/Screenshot_{secs}.png");

        let cap_ok = Command::new("gnome-screenshot")
            .args(&["-f", &path])
            .status()
            .map(|s| s.success())
            .unwrap_or(false);

        if cap_ok {
            let sh_cmd = format!("wl-copy -t image/png < '{path}' 2>/dev/null || xclip -selection clipboard -t image/png -i '{path}' 2>/dev/null || true");
            let _ = Command::new("sh").args(&["-c", &sh_cmd]).status();
            ActionOutcome::success("全螢幕截圖已儲存並寫入剪貼簿")
        } else {
            ydotool_key("Print", "Print", "全螢幕截圖已儲存")
        }
    }

    match action {
        "toggle_mute" => {
            if run_cmd("wpctl", &["set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"], "已切換靜音").ok {
                ActionOutcome::success("已切換靜音")
            } else if run_cmd("pactl", &["set-sink-mute", "@DEFAULT_SINK@", "toggle"], "已切換靜音").ok {
                ActionOutcome::success("已切換靜音")
            } else {
                ydotool_key("XF86AudioMute", "XF86AudioMute", "已切換靜音")
            }
        }
        "open_youtube" => run_cmd("xdg-open", &["https://www.youtube.com"], "已開啟 YouTube"),
        "screenshot_all" => take_screenshot(),
        "show_desktop" => {
            static SHOW_DESKTOP_STATE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
            let is_showing = SHOW_DESKTOP_STATE.fetch_xor(true, std::sync::atomic::Ordering::Relaxed);
            let arg = if !is_showing { "on" } else { "off" };
            if run_cmd("wmctrl", &["-k", arg], "已切換桌面").ok {
                ActionOutcome::success("已切換桌面")
            } else {
                ydotool_key("ctrl+alt+d", "ctrl+alt+d", "已切換桌面")
            }
        }
        "media_play_pause" => {
            if run_cmd("playerctl", &["play-pause"], "已切換播放／暫停").ok {
                ActionOutcome::success("已切換播放／暫停")
            } else {
                ydotool_key("XF86AudioPlay", "XF86AudioPlay", "已切換播放／暫停")
            }
        }
        "volume_up" => {
            if run_cmd("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", "5%+" ], "音量已提高").ok {
                ActionOutcome::success("音量已提高")
            } else if run_cmd("pactl", &["set-sink-volume", "@DEFAULT_SINK@", "+5%"], "音量已提高").ok {
                ActionOutcome::success("音量已提高")
            } else {
                ydotool_key("XF86AudioRaiseVolume", "XF86AudioRaiseVolume", "音量已提高")
            }
        }
        "volume_down" => {
            if run_cmd("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"], "音量已降低").ok {
                ActionOutcome::success("音量已降低")
            } else if run_cmd("pactl", &["set-sink-volume", "@DEFAULT_SINK@", "-5%"], "音量已降低").ok {
                ActionOutcome::success("音量已降低")
            } else {
                ydotool_key("XF86AudioLowerVolume", "XF86AudioLowerVolume", "音量已降低")
            }
        }
        "lock_pc" => {
            if run_cmd("loginctl", &["lock-session"], "電腦已鎖定").ok {
                ActionOutcome::success("電腦已鎖定")
            } else if run_cmd("xdg-screensaver", &["lock"], "電腦已鎖定").ok {
                ActionOutcome::success("電腦已鎖定")
            } else {
                ActionOutcome::failure("鎖定電腦失敗")
            }
        }
        "switch_window" => ydotool_key("alt+Tab", "alt+Tab", "已切換視窗"),
        "task_view" => ydotool_key("super", "super", "已開啟工作檢視"),
        "minimize_window" => {
            let res = ydotool_key("super+h", "super+h", "目前視窗已最小化");
            if res.ok {
                res
            } else {
                run_cmd("xdotool", &["getactivewindow", "windowminimize"], "目前視窗已最小化")
            }
        }
        "close_window" => ydotool_key("alt+F4", "alt+F4", "已送出關閉視窗"),
        "copy" => ydotool_key("ctrl+c", "ctrl+c", "已複製"),
        "paste" => ydotool_key("ctrl+v", "ctrl+v", "已貼上"),
        "undo" => ydotool_key("ctrl+z", "ctrl+z", "已復原"),
        "redo" => ydotool_key("ctrl+y", "ctrl+y", "已重做"),
        _ => ActionOutcome::failure("未知的 Macro 指令"),
    }
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
