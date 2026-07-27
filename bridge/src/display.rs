use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::OnceLock;
use std::thread;

static DISPLAY_STATE_SENDER: OnceLock<Sender<bool>> = OnceLock::new();

pub struct DisplayMonitor {
    receiver: Receiver<bool>,
    display_on: bool,
}

impl DisplayMonitor {
    pub fn start() -> Self {
        let (sender, receiver) = mpsc::channel();
        let _ = DISPLAY_STATE_SENDER.set(sender);

        #[cfg(windows)]
        thread::spawn(|| unsafe { win_monitor_loop() });

        #[cfg(not(windows))]
        thread::spawn(|| linux_monitor_loop());

        Self {
            receiver,
            display_on: true,
        }
    }

    pub fn current(&mut self) -> bool {
        self.take_changed();
        self.display_on
    }

    pub fn take_changed(&mut self) -> Option<bool> {
        let mut changed = None;
        while let Ok(display_on) = self.receiver.try_recv() {
            self.display_on = display_on;
            changed = Some(display_on);
        }
        changed
    }
}

#[cfg(windows)]
const CLASS_NAME: &[u16] = &[
    b'Q' as u16, b'u' as u16, b'i' as u16, b'e' as u16, b't' as u16, b'P' as u16,
    b'a' as u16, b'n' as u16, b'e' as u16, b'l' as u16, b'D' as u16, b'i' as u16,
    b's' as u16, b'p' as u16, b'l' as u16, b'a' as u16, b'y' as u16, 0,
];

#[cfg(windows)]
unsafe extern "system" fn window_proc(
    hwnd: windows_sys::Win32::Foundation::HWND,
    message: u32,
    wparam: windows_sys::Win32::Foundation::WPARAM,
    lparam: windows_sys::Win32::Foundation::LPARAM,
) -> windows_sys::Win32::Foundation::LRESULT {
    use windows_sys::Win32::System::Power::POWERBROADCAST_SETTING;
    use windows_sys::Win32::System::SystemServices::GUID_CONSOLE_DISPLAY_STATE;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        DefWindowProcW, PBT_POWERSETTINGCHANGE, WM_POWERBROADCAST,
    };

    if message == WM_POWERBROADCAST && wparam as u32 == PBT_POWERSETTINGCHANGE {
        let setting = &*(lparam as *const POWERBROADCAST_SETTING);
        if same_guid(setting.PowerSetting, GUID_CONSOLE_DISPLAY_STATE) && setting.DataLength >= 1 {
            if let Some(sender) = DISPLAY_STATE_SENDER.get() {
                let _ = sender.send(setting.Data[0] != 0);
            }
        }
    }
    DefWindowProcW(hwnd, message, wparam, lparam)
}

#[cfg(windows)]
fn same_guid(left: windows_sys::core::GUID, right: windows_sys::core::GUID) -> bool {
    left.data1 == right.data1
        && left.data2 == right.data2
        && left.data3 == right.data3
        && left.data4 == right.data4
}

#[cfg(windows)]
unsafe fn win_monitor_loop() {
    use windows_sys::Win32::Foundation::HANDLE;
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::System::Power::RegisterPowerSettingNotification;
    use windows_sys::Win32::System::SystemServices::GUID_CONSOLE_DISPLAY_STATE;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DispatchMessageW, GetMessageW, RegisterClassW, TranslateMessage,
        DEVICE_NOTIFY_WINDOW_HANDLE, HWND_MESSAGE, MSG, WNDCLASSW,
    };

    let instance = GetModuleHandleW(std::ptr::null());
    let mut window_class: WNDCLASSW = std::mem::zeroed();
    window_class.lpfnWndProc = Some(window_proc);
    window_class.hInstance = instance;
    window_class.lpszClassName = CLASS_NAME.as_ptr();
    if RegisterClassW(&window_class) == 0 {
        return;
    }

    let window = CreateWindowExW(
        0,
        CLASS_NAME.as_ptr(),
        CLASS_NAME.as_ptr(),
        0,
        0,
        0,
        0,
        0,
        HWND_MESSAGE,
        std::ptr::null_mut(),
        instance,
        std::ptr::null(),
    );
    if window.is_null() {
        return;
    }

    if RegisterPowerSettingNotification(
        window as HANDLE,
        &GUID_CONSOLE_DISPLAY_STATE,
        DEVICE_NOTIFY_WINDOW_HANDLE,
    ) == 0
    {
        return;
    }

    let mut message: MSG = std::mem::zeroed();
    while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}

#[cfg(not(windows))]
fn linux_monitor_loop() {
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};

    let child = Command::new("dbus-monitor")
        .args(["--session", "type='signal',interface='org.gnome.ScreenSaver'"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn();

    let mut child = match child {
        Ok(c) => c,
        Err(_) => return,
    };

    if let Some(stdout) = child.stdout.take() {
        let reader = BufReader::new(stdout);
        for line in reader.lines().map_while(Result::ok) {
            let trimmed = line.trim();
            if trimmed.contains("boolean true") {
                if let Some(sender) = DISPLAY_STATE_SENDER.get() {
                    let _ = sender.send(false);
                }
            } else if trimmed.contains("boolean false") {
                if let Some(sender) = DISPLAY_STATE_SENDER.get() {
                    let _ = sender.send(true);
                }
            }
        }
    }
    let _ = child.wait();
}
