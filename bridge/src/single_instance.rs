use std::io;
use std::ptr;

use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE};
use windows_sys::Win32::System::Threading::CreateMutexW;

const INSTANCE_NAME: &str = "Local\\QuietPanelBridge-redmi42";

pub struct InstanceGuard {
    handle: HANDLE,
}

pub fn acquire() -> io::Result<Option<InstanceGuard>> {
    let mut name: Vec<u16> = INSTANCE_NAME.encode_utf16().collect();
    name.push(0);

    let handle = unsafe { CreateMutexW(ptr::null(), 0, name.as_ptr()) };
    if handle.is_null() {
        return Err(io::Error::last_os_error());
    }

    let already_running = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    if already_running {
        unsafe {
            CloseHandle(handle);
        }
        return Ok(None);
    }

    Ok(Some(InstanceGuard { handle }))
}

impl Drop for InstanceGuard {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.handle);
        }
    }
}
