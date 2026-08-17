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
            let ok = Command::new("qdbus6")
                .args([
                    "org.kde.kglobalaccel",
                    "/component/mediacontrol",
                    "invokeShortcut",
                    "playpausemedia",
                ])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if ok || uinput::send_single_key(uinput::KEY_PLAYPAUSE) {
                ActionOutcome::success("已切換播放/暫停")
            } else {
                ActionOutcome::failure("無法控制媒體播放")
            }
        }
        "screenshot_all" => {
            if Command::new("spectacle")
                .args(["-f", "-b", "-c"])
                .spawn()
                .is_ok()
            {
                ActionOutcome::success("已完成全螢幕截圖並複製到剪貼簿")
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
        "paste" => {
            if uinput::send_hotkey_ctrl(uinput::KEY_V) {
                ActionOutcome::success("已執行貼上")
            } else {
                ActionOutcome::failure("無法模擬貼上按鍵 (/dev/uinput 不可用)")
            }
        }
        "copy" => {
            if uinput::send_hotkey_ctrl(uinput::KEY_C) {
                ActionOutcome::success("已執行複製")
            } else {
                ActionOutcome::failure("無法模擬複製按鍵 (/dev/uinput 不可用)")
            }
        }
        "cut" => {
            if uinput::send_hotkey_ctrl(uinput::KEY_X) {
                ActionOutcome::success("已執行剪下")
            } else {
                ActionOutcome::failure("無法模擬剪下按鍵 (/dev/uinput 不可用)")
            }
        }
        "undo" => {
            if uinput::send_hotkey_ctrl(uinput::KEY_Z) {
                ActionOutcome::success("已執行復原")
            } else {
                ActionOutcome::failure("無法模擬復原按鍵 (/dev/uinput 不可用)")
            }
        }
        "redo" => {
            if uinput::send_hotkey_ctrl(uinput::KEY_Y) {
                ActionOutcome::success("已執行重做")
            } else {
                ActionOutcome::failure("無法模擬重做按鍵 (/dev/uinput 不可用)")
            }
        }
        _ => ActionOutcome::failure("未知的按鈕動作"),
    }
}

#[cfg(unix)]
mod uinput {
    use std::fs::{File, OpenOptions};
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::unix::io::AsRawFd;
    use std::sync::{Mutex, OnceLock};
    use std::thread::sleep;
    use std::time::Duration;

    pub const KEY_LEFTCTRL: u16 = 29;
    pub const KEY_LEFTSHIFT: u16 = 42;
    pub const KEY_LEFTALT: u16 = 56;
    pub const KEY_V: u16 = 47;
    pub const KEY_C: u16 = 46;
    pub const KEY_X: u16 = 45;
    pub const KEY_Z: u16 = 44;
    pub const KEY_Y: u16 = 21;
    pub const KEY_PLAYPAUSE: u16 = 164;

    const EV_SYN: u16 = 0x00;
    const EV_KEY: u16 = 0x01;
    const SYN_REPORT: u16 = 0;

    const UI_SET_EVBIT: libc::c_ulong = 0x40045564;
    const UI_SET_KEYBIT: libc::c_ulong = 0x40045565;
    const UI_DEV_CREATE: libc::c_ulong = 0x5501;
    const UI_DEV_DESTROY: libc::c_ulong = 0x5502;

    #[repr(C)]
    struct UinputUserDev {
        name: [libc::c_char; 80],
        id: InputId,
        ff_effects_max: u32,
        absmax: [i32; 64],
        absmin: [i32; 64],
        absfuzz: [i32; 64],
        absflat: [i32; 64],
    }

    #[repr(C)]
    struct InputId {
        bustype: u16,
        vendor: u16,
        product: u16,
        version: u16,
    }

    #[repr(C)]
    struct InputEvent {
        time: libc::timeval,
        type_: u16,
        code: u16,
        value: i32,
    }

    pub struct UinputKeyboard {
        file: File,
    }

    impl UinputKeyboard {
        fn new() -> Result<Self, String> {
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .custom_flags(libc::O_NONBLOCK)
                .open("/dev/uinput")
                .map_err(|e| format!("Failed to open /dev/uinput: {}", e))?;

            let fd = file.as_raw_fd();
            unsafe {
                if libc::ioctl(fd, UI_SET_EVBIT, EV_KEY as libc::c_int) < 0
                    || libc::ioctl(fd, UI_SET_EVBIT, EV_SYN as libc::c_int) < 0
                {
                    return Err("Failed to set EVBIT on uinput".into());
                }

                let keys = [
                    KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_LEFTALT,
                    KEY_V, KEY_C, KEY_X, KEY_Z, KEY_Y,
                    KEY_PLAYPAUSE,
                ];
                for &k in &keys {
                    libc::ioctl(fd, UI_SET_KEYBIT, k as libc::c_int);
                }

                let mut dev: UinputUserDev = std::mem::zeroed();
                let name = b"QuietPanel Virtual Keyboard\0";
                for (i, &b) in name.iter().enumerate() {
                    dev.name[i] = b as libc::c_char;
                }
                dev.id.bustype = 0x03; // BUS_USB
                dev.id.vendor = 0x1234;
                dev.id.product = 0x5678;
                dev.id.version = 1;

                let dev_slice = std::slice::from_raw_parts(
                    &dev as *const _ as *const u8,
                    std::mem::size_of::<UinputUserDev>(),
                );
                use std::io::Write;
                let mut f = &file;
                f.write_all(dev_slice).map_err(|e| format!("Failed to write dev: {}", e))?;

                if libc::ioctl(fd, UI_DEV_CREATE) < 0 {
                    return Err("Failed to create uinput device".into());
                }
            }
            sleep(Duration::from_millis(50));
            Ok(Self { file })
        }

        fn write_event(&mut self, type_: u16, code: u16, value: i32) {
            let ev = InputEvent {
                time: libc::timeval { tv_sec: 0, tv_usec: 0 },
                type_,
                code,
                value,
            };
            let slice = unsafe {
                std::slice::from_raw_parts(
                    &ev as *const _ as *const u8,
                    std::mem::size_of::<InputEvent>(),
                )
            };
            use std::io::Write;
            let _ = self.file.write_all(slice);
        }

        #[allow(dead_code)]
        pub fn send_key(&mut self, code: u16) {
            self.write_event(EV_KEY, code, 1);
            self.write_event(EV_SYN, SYN_REPORT, 0);
            sleep(Duration::from_millis(15));
            self.write_event(EV_KEY, code, 0);
            self.write_event(EV_SYN, SYN_REPORT, 0);
        }

        pub fn send_combo(&mut self, mod_code: u16, key_code: u16) {
            self.write_event(EV_KEY, mod_code, 1);
            self.write_event(EV_SYN, SYN_REPORT, 0);
            sleep(Duration::from_millis(15));
            self.write_event(EV_KEY, key_code, 1);
            self.write_event(EV_SYN, SYN_REPORT, 0);
            sleep(Duration::from_millis(25));
            self.write_event(EV_KEY, key_code, 0);
            self.write_event(EV_SYN, SYN_REPORT, 0);
            sleep(Duration::from_millis(15));
            self.write_event(EV_KEY, mod_code, 0);
            self.write_event(EV_SYN, SYN_REPORT, 0);
        }
    }

    impl Drop for UinputKeyboard {
        fn drop(&mut self) {
            unsafe {
                libc::ioctl(self.file.as_raw_fd(), UI_DEV_DESTROY);
            }
        }
    }

    static KEYBOARD: OnceLock<Mutex<Option<UinputKeyboard>>> = OnceLock::new();

    fn get_keyboard() -> &'static Mutex<Option<UinputKeyboard>> {
        KEYBOARD.get_or_init(|| {
            Mutex::new(UinputKeyboard::new().ok())
        })
    }

    pub fn send_hotkey_ctrl(key_code: u16) -> bool {
        let lock = get_keyboard();
        if let Ok(mut guard) = lock.lock() {
            if guard.is_none() {
                *guard = UinputKeyboard::new().ok();
            }
            if let Some(ref mut kb) = *guard {
                kb.send_combo(KEY_LEFTCTRL, key_code);
                return true;
            }
        }
        false
    }

    #[allow(dead_code)]
    pub fn send_single_key(key_code: u16) -> bool {
        let lock = get_keyboard();
        if let Ok(mut guard) = lock.lock() {
            if guard.is_none() {
                *guard = UinputKeyboard::new().ok();
            }
            if let Some(ref mut kb) = *guard {
                kb.send_key(key_code);
                return true;
            }
        }
        false
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
