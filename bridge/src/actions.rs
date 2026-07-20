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
