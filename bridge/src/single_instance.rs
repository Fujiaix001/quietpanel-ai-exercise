#[cfg(windows)]
use std::io;
#[cfg(windows)]
use std::ptr;

#[cfg(windows)]
use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE};
#[cfg(windows)]
use windows_sys::Win32::System::Threading::CreateMutexW;

#[cfg(windows)]
const INSTANCE_NAME: &str = "Local\\QuietPanelBridge-redmi42";

#[cfg(windows)]
pub struct InstanceGuard {
    handle: HANDLE,
}

#[cfg(windows)]
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

#[cfg(windows)]
impl Drop for InstanceGuard {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.handle);
        }
    }
}

#[cfg(unix)]
use std::fs::{File, OpenOptions};
#[cfg(unix)]
use std::io;
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
#[cfg(unix)]
use std::os::unix::io::AsRawFd;
#[cfg(unix)]
use std::path::PathBuf;

#[cfg(unix)]
pub struct InstanceGuard {
    _file: File,
}

#[cfg(unix)]
pub fn acquire() -> io::Result<Option<InstanceGuard>> {
    let lock_path = if let Ok(runtime_dir) = std::env::var("XDG_RUNTIME_DIR") {
        PathBuf::from(runtime_dir).join("quietpanel-bridge.lock")
    } else {
        std::env::temp_dir().join("quietpanel-bridge.lock")
    };

    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .open(&lock_path)?;

    let fd = file.as_raw_fd();
    let ret = unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) };
    if ret != 0 {
        let err = io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EWOULDBLOCK) || err.raw_os_error() == Some(libc::EAGAIN) {
            return Ok(None);
        }
        return Err(err);
    }

    Ok(Some(InstanceGuard { _file: file }))
}
