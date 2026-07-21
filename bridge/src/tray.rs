use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;

use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::Shell::{
    Shell_NotifyIconW, NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NOTIFYICONDATAW,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyMenu, DestroyWindow,
    DispatchMessageW, GetCursorPos, GetMessageW, LoadIconW, PostQuitMessage, RegisterClassW,
    SetForegroundWindow, TrackPopupMenu, TranslateMessage, IDI_APPLICATION, MF_CHECKED, MF_GRAYED,
    MF_SEPARATOR, MF_STRING, MSG, TPM_LEFTALIGN, TPM_NONOTIFY, TPM_RETURNCMD, WM_APP, WM_COMMAND,
    WM_DESTROY, WM_LBUTTONUP, WM_RBUTTONUP, WNDCLASSW,
};

use crate::settings::PAGE_COUNT;

const TRAY_MESSAGE: u32 = WM_APP + 1;
const TRAY_ID: u32 = 1;
const FIRST_PAGE_COMMAND: u32 = 1001;
const EXIT_COMMAND: u32 = 1099;
const PAGE_LABELS: [&str; PAGE_COUNT] = [
    "頁面 1：系統監控",
    "頁面 2：磁碟空間",
    "頁面 3：相簿時鐘",
    "頁面 4：NASA APOD",
    "頁面 5：MACRO",
    "頁面 6：快捷工具",
];

struct TrayShared {
    pages: Mutex<[bool; PAGE_COUNT]>,
    sender: Sender<[bool; PAGE_COUNT]>,
    running: Arc<AtomicBool>,
}

static SHARED: OnceLock<TrayShared> = OnceLock::new();

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
    b'T' as u16,
    b'r' as u16,
    b'a' as u16,
    b'y' as u16,
    0,
];

pub struct TrayController {
    receiver: Receiver<[bool; PAGE_COUNT]>,
    running: Arc<AtomicBool>,
}

impl TrayController {
    pub fn start(pages: [bool; PAGE_COUNT]) -> Self {
        let (sender, receiver) = mpsc::channel();
        let running = Arc::new(AtomicBool::new(true));
        let _ = SHARED.set(TrayShared {
            pages: Mutex::new(pages),
            sender,
            running: Arc::clone(&running),
        });
        thread::spawn(|| unsafe { tray_loop() });
        Self { receiver, running }
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }

    pub fn take_changed(&self) -> Option<[bool; PAGE_COUNT]> {
        let mut changed = None;
        while let Ok(pages) = self.receiver.try_recv() {
            changed = Some(pages);
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
    if message == TRAY_MESSAGE {
        let mouse_message = lparam as u32;
        if mouse_message == WM_RBUTTONUP || mouse_message == WM_LBUTTONUP {
            show_menu(hwnd);
            return 0;
        }
    } else if message == WM_COMMAND {
        handle_command((wparam & 0xffff) as u32, hwnd);
        return 0;
    } else if message == WM_DESTROY {
        remove_icon(hwnd);
        PostQuitMessage(0);
        return 0;
    }
    DefWindowProcW(hwnd, message, wparam, lparam)
}

unsafe fn tray_loop() {
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
        std::ptr::null_mut(),
        std::ptr::null_mut(),
        instance,
        std::ptr::null(),
    );
    if window.is_null() {
        return;
    }
    add_icon(window);

    let mut message: MSG = std::mem::zeroed();
    while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}

unsafe fn add_icon(hwnd: HWND) {
    let mut icon = NOTIFYICONDATAW {
        cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: TRAY_ID,
        uFlags: NIF_ICON | NIF_MESSAGE | NIF_TIP,
        uCallbackMessage: TRAY_MESSAGE,
        hIcon: LoadIconW(std::ptr::null_mut(), IDI_APPLICATION),
        ..Default::default()
    };
    copy_utf16(&mut icon.szTip, "QuietPanel Bridge v7.0.0 正在執行");
    Shell_NotifyIconW(NIM_ADD, &icon);
}

unsafe fn remove_icon(hwnd: HWND) {
    let icon = NOTIFYICONDATAW {
        cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: TRAY_ID,
        ..Default::default()
    };
    Shell_NotifyIconW(NIM_DELETE, &icon);
}

unsafe fn show_menu(hwnd: HWND) {
    let menu = CreatePopupMenu();
    if menu.is_null() {
        return;
    }

    let running_label = wide("QuietPanel Bridge v7.0.0 正在執行");
    AppendMenuW(menu, MF_STRING | MF_GRAYED, 0, running_label.as_ptr());
    AppendMenuW(menu, MF_SEPARATOR, 0, std::ptr::null());

    let pages = SHARED
        .get()
        .and_then(|shared| shared.pages.lock().ok().map(|pages| *pages))
        .unwrap_or([true; PAGE_COUNT]);
    let labels: Vec<Vec<u16>> = PAGE_LABELS.iter().map(|label| wide(label)).collect();
    for (index, label) in labels.iter().enumerate() {
        let flags = MF_STRING | if pages[index] { MF_CHECKED } else { 0 };
        AppendMenuW(
            menu,
            flags,
            (FIRST_PAGE_COMMAND + index as u32) as usize,
            label.as_ptr(),
        );
    }
    AppendMenuW(menu, MF_SEPARATOR, 0, std::ptr::null());
    let exit_label = wide("結束 Bridge");
    AppendMenuW(menu, MF_STRING, EXIT_COMMAND as usize, exit_label.as_ptr());

    let mut point = POINT::default();
    GetCursorPos(&mut point);
    SetForegroundWindow(hwnd);
    let command = TrackPopupMenu(
        menu,
        TPM_LEFTALIGN | TPM_NONOTIFY | TPM_RETURNCMD,
        point.x,
        point.y,
        0,
        hwnd,
        std::ptr::null(),
    ) as u32;
    DestroyMenu(menu);

    handle_command(command, hwnd);
}

unsafe fn handle_command(command: u32, hwnd: HWND) {
    if (FIRST_PAGE_COMMAND..FIRST_PAGE_COMMAND + PAGE_COUNT as u32).contains(&command) {
        toggle_page((command - FIRST_PAGE_COMMAND) as usize);
    } else if command == EXIT_COMMAND {
        if let Some(shared) = SHARED.get() {
            shared.running.store(false, Ordering::Relaxed);
        }
        DestroyWindow(hwnd);
    }
}

fn toggle_page(index: usize) {
    let Some(shared) = SHARED.get() else {
        return;
    };
    let Ok(mut pages) = shared.pages.lock() else {
        return;
    };
    if pages[index] && pages.iter().filter(|enabled| **enabled).count() == 1 {
        return;
    }
    pages[index] = !pages[index];
    let changed = *pages;
    drop(pages);
    let _ = shared.sender.send(changed);
}

#[cfg(test)]
fn toggle_for_test(mut pages: [bool; PAGE_COUNT], index: usize) -> [bool; PAGE_COUNT] {
    if !pages[index] || pages.iter().filter(|enabled| **enabled).count() > 1 {
        pages[index] = !pages[index];
    }
    pages
}

fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

fn copy_utf16(target: &mut [u16], text: &str) {
    let limit = target.len().saturating_sub(1);
    for (destination, value) in target.iter_mut().take(limit).zip(text.encode_utf16()) {
        *destination = value;
    }
}

#[cfg(test)]
mod tests {
    use super::toggle_for_test;

    #[test]
    fn page_toggle_keeps_at_least_one_page() {
        assert_eq!(
            toggle_for_test([true, false, false, false, false, false], 0),
            [true, false, false, false, false, false]
        );
    }

    #[test]
    fn page_toggle_can_disable_one_of_many_pages() {
        assert_eq!(
            toggle_for_test([true, true, false, false, false, false], 0),
            [false, true, false, false, false, false]
        );
    }
}
