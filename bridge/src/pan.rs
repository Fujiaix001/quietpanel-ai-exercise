use std::ffi::CStr;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};

use windows_sys::Win32::Foundation::{CloseHandle, FreeLibrary, HANDLE, HMODULE};
use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

type PanHandle = isize;
type FindFirstNetwork = unsafe extern "system" fn(*mut PanHandle, *mut PanHandle) -> u32;
type FindNextNetwork = unsafe extern "system" fn(PanHandle, *mut PanHandle) -> u32;
type FindNetworkClose = unsafe extern "system" fn(PanHandle) -> u32;
type CloseNetworkHandle = unsafe extern "system" fn(PanHandle) -> u32;
type GetNetworkAddress = unsafe extern "system" fn(PanHandle, *mut u64) -> u32;
type GetNetworkName = unsafe extern "system" fn(PanHandle, *mut u16, *mut u32) -> u32;
type ConnectToNetwork = unsafe extern "system" fn(PanHandle, u32) -> u32;

type FindFirstRadio =
    unsafe extern "system" fn(*const BluetoothFindRadioParams, *mut HANDLE) -> isize;
type FindRadioClose = unsafe extern "system" fn(isize) -> i32;
type FindFirstDevice = unsafe extern "system" fn(
    *const BluetoothDeviceSearchParams,
    *mut BluetoothDeviceInfo,
) -> isize;
type FindNextDevice = unsafe extern "system" fn(isize, *mut BluetoothDeviceInfo) -> i32;
type FindDeviceClose = unsafe extern "system" fn(isize) -> i32;
type EnumerateInstalledServices = unsafe extern "system" fn(
    HANDLE,
    *const BluetoothDeviceInfo,
    *mut u32,
    *mut BluetoothGuid,
) -> u32;
type SetServiceState =
    unsafe extern "system" fn(HANDLE, *const BluetoothDeviceInfo, *const BluetoothGuid, u32) -> u32;

const ERROR_NO_MORE_ITEMS: u32 = 259;
const ERROR_MORE_DATA: u32 = 234;
const ERROR_NOT_FOUND: u32 = 1168;
const BLUETOOTH_SERVICE_ENABLE: u32 = 1;
// bthpanapi maps this bit to the remote Network Access Point (NAP) role.
const REMOTE_ROLE_NAP: u32 = 2;
const NAP_SERVICE_CLASS: BluetoothGuid = BluetoothGuid {
    data1: 0x0000_1116,
    data2: 0x0000,
    data3: 0x1000,
    data4: [0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb],
};

static NAP_SERVICE_ENABLED: AtomicBool = AtomicBool::new(false);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectOutcome {
    Connected,
}

#[repr(C)]
struct BluetoothFindRadioParams {
    size: u32,
}

#[repr(C)]
struct BluetoothAddress {
    value: u64,
}

#[repr(C)]
struct SystemTime {
    year: u16,
    month: u16,
    day_of_week: u16,
    day: u16,
    hour: u16,
    minute: u16,
    second: u16,
    milliseconds: u16,
}

#[repr(C)]
struct BluetoothDeviceInfo {
    size: u32,
    address: BluetoothAddress,
    class_of_device: u32,
    connected: i32,
    remembered: i32,
    authenticated: i32,
    last_seen: SystemTime,
    last_used: SystemTime,
    name: [u16; 248],
}

#[repr(C)]
struct BluetoothDeviceSearchParams {
    size: u32,
    return_authenticated: i32,
    return_remembered: i32,
    return_unknown: i32,
    return_connected: i32,
    issue_inquiry: i32,
    timeout_multiplier: u8,
    radio: HANDLE,
}

#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq)]
struct BluetoothGuid {
    data1: u32,
    data2: u16,
    data3: u16,
    data4: [u8; 8],
}

struct BluetoothApi {
    module: HMODULE,
    find_first_radio: FindFirstRadio,
    find_radio_close: FindRadioClose,
    find_first_device: FindFirstDevice,
    find_next_device: FindNextDevice,
    find_device_close: FindDeviceClose,
    enumerate_installed_services: EnumerateInstalledServices,
    set_service_state: SetServiceState,
}

impl BluetoothApi {
    fn load() -> io::Result<Self> {
        let library: Vec<u16> = "BluetoothApis.dll\0".encode_utf16().collect();
        let module = unsafe { LoadLibraryW(library.as_ptr()) };
        if module.is_null() {
            return Err(io::Error::last_os_error());
        }

        unsafe {
            Ok(Self {
                module,
                find_first_radio: std::mem::transmute(get_export(
                    module,
                    c"BluetoothFindFirstRadio",
                )?),
                find_radio_close: std::mem::transmute(get_export(
                    module,
                    c"BluetoothFindRadioClose",
                )?),
                find_first_device: std::mem::transmute(get_export(
                    module,
                    c"BluetoothFindFirstDevice",
                )?),
                find_next_device: std::mem::transmute(get_export(
                    module,
                    c"BluetoothFindNextDevice",
                )?),
                find_device_close: std::mem::transmute(get_export(
                    module,
                    c"BluetoothFindDeviceClose",
                )?),
                enumerate_installed_services: std::mem::transmute(get_export(
                    module,
                    c"BluetoothEnumerateInstalledServices",
                )?),
                set_service_state: std::mem::transmute(get_export(
                    module,
                    c"BluetoothSetServiceState",
                )?),
            })
        }
    }
}

impl Drop for BluetoothApi {
    fn drop(&mut self) {
        unsafe {
            FreeLibrary(self.module);
        }
    }
}

struct PanApi {
    module: HMODULE,
    find_first: FindFirstNetwork,
    find_next: FindNextNetwork,
    find_close: FindNetworkClose,
    close_network: CloseNetworkHandle,
    get_address: GetNetworkAddress,
    get_name: GetNetworkName,
    connect: ConnectToNetwork,
}

impl PanApi {
    fn load() -> io::Result<Self> {
        let library: Vec<u16> = "bthpanapi.dll\0".encode_utf16().collect();
        let module = unsafe { LoadLibraryW(library.as_ptr()) };
        if module.is_null() {
            return Err(io::Error::last_os_error());
        }

        unsafe {
            let result = Self {
                module,
                find_first: std::mem::transmute(get_export(module, c"BluetoothFindFirstNetwork")?),
                find_next: std::mem::transmute(get_export(module, c"BluetoothFindNextNetwork")?),
                find_close: std::mem::transmute(get_export(module, c"BluetoothFindNetworkClose")?),
                close_network: std::mem::transmute(get_export(
                    module,
                    c"BluetoothCloseNetworkHandle",
                )?),
                get_address: std::mem::transmute(get_export(
                    module,
                    c"BluetoothGetNetworkAddress",
                )?),
                get_name: std::mem::transmute(get_export(module, c"BluetoothGetNetworkName")?),
                connect: std::mem::transmute(get_export(module, c"BluetoothConnectToNetwork")?),
            };
            Ok(result)
        }
    }
}

impl Drop for PanApi {
    fn drop(&mut self) {
        unsafe {
            FreeLibrary(self.module);
        }
    }
}

unsafe fn get_export(
    module: HMODULE,
    name: &'static CStr,
) -> io::Result<unsafe extern "system" fn() -> isize> {
    GetProcAddress(module, name.as_ptr().cast()).ok_or_else(io::Error::last_os_error)
}

struct FindGuard<'a> {
    api: &'a PanApi,
    handle: PanHandle,
}

impl Drop for FindGuard<'_> {
    fn drop(&mut self) {
        if self.handle != 0 {
            unsafe {
                (self.api.find_close)(self.handle);
            }
        }
    }
}

struct NetworkGuard<'a> {
    api: &'a PanApi,
    handle: PanHandle,
}

impl Drop for NetworkGuard<'_> {
    fn drop(&mut self) {
        if self.handle != 0 {
            unsafe {
                (self.api.close_network)(self.handle);
            }
        }
    }
}

struct RadioGuard<'a> {
    api: &'a BluetoothApi,
    find_handle: isize,
    radio: HANDLE,
}

impl Drop for RadioGuard<'_> {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.radio);
            (self.api.find_radio_close)(self.find_handle);
        }
    }
}

struct DeviceFindGuard<'a> {
    api: &'a BluetoothApi,
    handle: isize,
}

impl Drop for DeviceFindGuard<'_> {
    fn drop(&mut self) {
        unsafe {
            (self.api.find_device_close)(self.handle);
        }
    }
}

pub fn connect_by_name(target: &str) -> io::Result<ConnectOutcome> {
    if !NAP_SERVICE_ENABLED.load(Ordering::Acquire) {
        enable_nap_service(target)?;
        NAP_SERVICE_ENABLED.store(true, Ordering::Release);
    }

    let api = PanApi::load()?;
    let mut find_handle = 0;
    let mut network_handle = 0;
    let first_error = unsafe { (api.find_first)(&mut find_handle, &mut network_handle) };
    if first_error != 0 {
        return Err(win32_error(first_error));
    }
    let _find_guard = FindGuard {
        api: &api,
        handle: find_handle,
    };

    loop {
        let network = NetworkGuard {
            api: &api,
            handle: network_handle,
        };
        if network_matches(&api, network.handle, target) {
            let connect_error = unsafe { (api.connect)(network.handle, REMOTE_ROLE_NAP) };
            return if connect_error == 0 {
                Ok(ConnectOutcome::Connected)
            } else {
                Err(win32_error(connect_error))
            };
        }
        drop(network);

        network_handle = 0;
        let next_error = unsafe { (api.find_next)(find_handle, &mut network_handle) };
        if next_error == ERROR_NO_MORE_ITEMS {
            break;
        }
        if next_error != 0 {
            return Err(win32_error(next_error));
        }
    }

    Err(win32_error(ERROR_NOT_FOUND))
}

fn enable_nap_service(target: &str) -> io::Result<()> {
    let api = BluetoothApi::load()?;
    let params = BluetoothFindRadioParams {
        size: std::mem::size_of::<BluetoothFindRadioParams>() as u32,
    };
    let mut radio = std::ptr::null_mut();
    let radio_find = unsafe { (api.find_first_radio)(&params, &mut radio) };
    if radio_find == 0 || radio.is_null() {
        return Err(io::Error::last_os_error());
    }
    let _radio_guard = RadioGuard {
        api: &api,
        find_handle: radio_find,
        radio,
    };

    let search = BluetoothDeviceSearchParams {
        size: std::mem::size_of::<BluetoothDeviceSearchParams>() as u32,
        return_authenticated: 1,
        return_remembered: 1,
        return_unknown: 0,
        return_connected: 1,
        issue_inquiry: 0,
        timeout_multiplier: 0,
        radio,
    };
    let mut device = empty_device_info();
    let device_find = unsafe { (api.find_first_device)(&search, &mut device) };
    if device_find == 0 {
        return Err(io::Error::last_os_error());
    }
    let _device_guard = DeviceFindGuard {
        api: &api,
        handle: device_find,
    };

    loop {
        if device_matches(&device, target) {
            if nap_service_is_enabled(&api, radio, &device)? {
                return Ok(());
            }
            let error = unsafe {
                (api.set_service_state)(
                    radio,
                    &device,
                    &NAP_SERVICE_CLASS,
                    BLUETOOTH_SERVICE_ENABLE,
                )
            };
            return if error == 0 {
                Ok(())
            } else {
                Err(win32_error(error))
            };
        }

        device = empty_device_info();
        if unsafe { (api.find_next_device)(device_find, &mut device) } == 0 {
            break;
        }
    }

    Err(win32_error(ERROR_NOT_FOUND))
}

fn nap_service_is_enabled(
    api: &BluetoothApi,
    radio: HANDLE,
    device: &BluetoothDeviceInfo,
) -> io::Result<bool> {
    let mut count = 0;
    let error = unsafe {
        (api.enumerate_installed_services)(radio, device, &mut count, std::ptr::null_mut())
    };
    if error != 0 && error != ERROR_MORE_DATA {
        return Err(win32_error(error));
    }
    if count == 0 {
        return Ok(false);
    }

    let mut services = vec![
        BluetoothGuid {
            data1: 0,
            data2: 0,
            data3: 0,
            data4: [0; 8],
        };
        count as usize
    ];
    let error = unsafe {
        (api.enumerate_installed_services)(radio, device, &mut count, services.as_mut_ptr())
    };
    if error != 0 {
        return Err(win32_error(error));
    }
    services.truncate(count as usize);
    Ok(services.contains(&NAP_SERVICE_CLASS))
}

fn empty_device_info() -> BluetoothDeviceInfo {
    let mut info: BluetoothDeviceInfo = unsafe { std::mem::zeroed() };
    info.size = std::mem::size_of::<BluetoothDeviceInfo>() as u32;
    info
}

fn device_matches(device: &BluetoothDeviceInfo, target: &str) -> bool {
    let normalized_target = normalize_address(target);
    if normalized_target.len() == 12
        && format!("{:012X}", device.address.value) == normalized_target
    {
        return true;
    }

    let end = device
        .name
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(device.name.len());
    let name = String::from_utf16_lossy(&device.name[..end]);
    name.trim().eq_ignore_ascii_case(target.trim()) || name.trim() == target.trim()
}

fn network_matches(api: &PanApi, handle: PanHandle, target: &str) -> bool {
    let normalized_target = normalize_address(target);
    if normalized_target.len() == 12 {
        let mut address = 0;
        let error = unsafe { (api.get_address)(handle, &mut address) };
        if error == 0 && format!("{address:012X}") == normalized_target {
            return true;
        }
    }

    let mut buffer = [0u16; 248];
    let mut capacity = buffer.len() as u32;
    let error = unsafe { (api.get_name)(handle, buffer.as_mut_ptr(), &mut capacity) };
    if error != 0 {
        return false;
    }
    let end = buffer
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(buffer.len());
    let name = String::from_utf16_lossy(&buffer[..end]);
    name.trim().eq_ignore_ascii_case(target.trim()) || name.trim() == target.trim()
}

fn normalize_address(value: &str) -> String {
    value
        .chars()
        .filter(|character| character.is_ascii_hexdigit())
        .map(|character| character.to_ascii_uppercase())
        .collect()
}

fn win32_error(code: u32) -> io::Error {
    io::Error::from_raw_os_error(code as i32)
}

#[cfg(test)]
mod tests {
    use super::{normalize_address, BluetoothApi, PanApi};

    #[test]
    fn normalizes_common_bluetooth_address_formats() {
        assert_eq!(normalize_address("68:df:dd:0c:c1:ae"), "68DFDD0CC1AE");
        assert_eq!(normalize_address("68-DF-DD-0C-C1-AE"), "68DFDD0CC1AE");
    }

    #[test]
    fn loads_windows_pan_exports() {
        PanApi::load().expect("Windows Bluetooth PAN API should be available");
        BluetoothApi::load().expect("Windows Bluetooth service API should be available");
    }
}
