use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::OnceLock;
use std::thread;

use windows_sys::Win32::Foundation::{HANDLE, HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Power::{RegisterPowerSettingNotification, POWERBROADCAST_SETTING};
use windows_sys::Win32::System::SystemServices::GUID_CONSOLE_DISPLAY_STATE;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, RegisterClassW,
    TranslateMessage, DEVICE_NOTIFY_WINDOW_HANDLE, HWND_MESSAGE, MSG, PBT_POWERSETTINGCHANGE,
    WM_POWERBROADCAST, WNDCLASSW,
};

static DISPLAY_STATE_SENDER: OnceLock<Sender<bool>> = OnceLock::new();

const CLASS_NAME: &[u16] = &[
    b'Q' as u16,
    b'u' as u16,
    b'i' as u16,
    b'e' as u16,
    b't' as u16,
    b'P' as u16,
    b'a' as u16,
    b'n' as u16,
    b'e' as u16,
    b'l' as u16,
    b'D' as u16,
    b'i' as u16,
    b's' as u16,
    b'p' as u16,
    b'l' as u16,
    b'a' as u16,
    b'y' as u16,
    0,
];

pub struct DisplayMonitor {
    receiver: Receiver<bool>,
    display_on: bool,
}

impl DisplayMonitor {
    pub fn start() -> Self {
        let (sender, receiver) = mpsc::channel();
        let _ = DISPLAY_STATE_SENDER.set(sender);
        thread::spawn(|| unsafe { monitor_loop() });
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

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if message == WM_POWERBROADCAST && wparam as u32 == PBT_POWERSETTINGCHANGE {
        let setting = &*(lparam as *const POWERBROADCAST_SETTING);
        if same_guid(setting.PowerSetting, GUID_CONSOLE_DISPLAY_STATE) && setting.DataLength >= 1 {
            // 0 = off, 1 = on, 2 = dimmed.  Dimmed remains visible, so keep the phone awake.
            if let Some(sender) = DISPLAY_STATE_SENDER.get() {
                let _ = sender.send(setting.Data[0] != 0);
            }
        }
    }
    DefWindowProcW(hwnd, message, wparam, lparam)
}

fn same_guid(left: windows_sys::core::GUID, right: windows_sys::core::GUID) -> bool {
    left.data1 == right.data1
        && left.data2 == right.data2
        && left.data3 == right.data3
        && left.data4 == right.data4
}

unsafe fn monitor_loop() {
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
