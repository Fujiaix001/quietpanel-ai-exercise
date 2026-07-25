use std::io::{self, Read, Write};
use std::time::Duration;
use windows_sys::Win32::Foundation::HANDLE;
use windows_sys::Win32::Networking::WinSock::{
    closesocket, connect, recv, send, setsockopt, socket, WSAStartup, SOCKET, SOCKET_ERROR,
    SOCK_STREAM, SOL_SOCKET, SO_RCVTIMEO, SO_SNDTIMEO, WSADATA,
};

pub const AF_BTH: u16 = 32;
pub const BTHPROTO_RFCOMM: u32 = 3;

#[repr(C)]
#[derive(Copy, Clone)]
pub struct SOCKADDR_BTH {
    pub address_family: u16,
    pub bt_addr: u64,
    pub service_class_id: [u8; 16],
    pub port: u32,
}

#[repr(C)]
#[allow(non_snake_case)]
pub struct BLUETOOTH_DEVICE_SEARCH_PARAMS {
    pub dwSize: u32,
    pub fReturnAuthenticated: i32,
    pub fReturnRemembered: i32,
    pub fReturnUnknown: i32,
    pub fReturnConnected: i32,
    pub fIssueInquiry: i32,
    pub cTimeoutMultiplier: u8,
    pub hRadio: HANDLE,
}

#[repr(C)]
#[allow(non_snake_case)]
pub struct BLUETOOTH_DEVICE_INFO {
    pub dwSize: u32,
    pub Address: u64,
    pub ulClassofDevice: u32,
    pub fConnected: i32,
    pub fRemembered: i32,
    pub fAuthenticated: i32,
    pub stLastSeen: [u16; 8],
    pub stLastUsed: [u16; 8],
    pub szName: [u16; 248],
}

#[link(name = "bthprops")]
extern "system" {
    pub fn BluetoothFindFirstDevice(
        pSearchParams: *const BLUETOOTH_DEVICE_SEARCH_PARAMS,
        pbhDIF: *mut BLUETOOTH_DEVICE_INFO,
    ) -> HANDLE;
    pub fn BluetoothFindNextDevice(hFind: HANDLE, pbhDIF: *mut BLUETOOTH_DEVICE_INFO) -> i32;
    pub fn BluetoothFindDeviceClose(hFind: HANDLE) -> i32;
}

pub struct BluetoothStream {
    socket: SOCKET,
}

unsafe impl Send for BluetoothStream {}
unsafe impl Sync for BluetoothStream {}

impl BluetoothStream {
    pub fn discover_and_connect() -> Option<(Self, String)> {
        let devices = discover_paired_devices();
        if devices.is_empty() {
            return None;
        }

        for (addr, name) in devices {
            if let Ok(stream) = Self::connect_spp(addr) {
                return Some((stream, name));
            }
        }

        None
    }

    pub fn connect_spp(mac_addr: u64) -> io::Result<Self> {
        unsafe {
            let mut wsa_data: WSADATA = std::mem::zeroed();
            let res = WSAStartup(0x0202, &mut wsa_data);
            if res != 0 {
                return Err(io::Error::new(
                    io::ErrorKind::Other,
                    format!("WSAStartup failed: {res}"),
                ));
            }

            let s = socket(AF_BTH as i32, SOCK_STREAM as i32, BTHPROTO_RFCOMM as i32);
            if s == !0 {
                return Err(io::Error::last_os_error());
            }

            // SPP UUID: 00001101-0000-1000-8000-00805F9B34FB
            let spp_guid: [u8; 16] = [
                0x01, 0x11, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B,
                0x34, 0xFB,
            ];

            let addr = SOCKADDR_BTH {
                address_family: AF_BTH,
                bt_addr: mac_addr,
                service_class_id: spp_guid,
                port: 0,
            };

            let connect_res = connect(
                s,
                &addr as *const _ as *const _,
                std::mem::size_of::<SOCKADDR_BTH>() as i32,
            );

            if connect_res == SOCKET_ERROR {
                let err = io::Error::last_os_error();
                closesocket(s);
                return Err(err);
            }

            Ok(BluetoothStream { socket: s })
        }
    }

    pub fn try_clone(&self) -> io::Result<Self> {
        Ok(BluetoothStream {
            socket: self.socket,
        })
    }

    pub fn set_read_timeout(&self, dur: Option<Duration>) -> io::Result<()> {
        let ms = match dur {
            Some(d) => d.as_millis() as u32,
            None => 0,
        };
        unsafe {
            setsockopt(
                self.socket,
                SOL_SOCKET as i32,
                SO_RCVTIMEO as i32,
                &ms as *const _ as *const _,
                std::mem::size_of::<u32>() as i32,
            );
        }
        Ok(())
    }

    pub fn set_write_timeout(&self, dur: Option<Duration>) -> io::Result<()> {
        let ms = match dur {
            Some(d) => d.as_millis() as u32,
            None => 0,
        };
        unsafe {
            setsockopt(
                self.socket,
                SOL_SOCKET as i32,
                SO_SNDTIMEO as i32,
                &ms as *const _ as *const _,
                std::mem::size_of::<u32>() as i32,
            );
        }
        Ok(())
    }
}

impl Read for BluetoothStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let res = unsafe { recv(self.socket, buf.as_mut_ptr() as *mut _, buf.len() as i32, 0) };
        if res == SOCKET_ERROR {
            Err(io::Error::last_os_error())
        } else {
            Ok(res as usize)
        }
    }
}

impl Write for BluetoothStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let res = unsafe { send(self.socket, buf.as_ptr() as *const _, buf.len() as i32, 0) };
        if res == SOCKET_ERROR {
            Err(io::Error::last_os_error())
        } else {
            Ok(res as usize)
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub fn discover_paired_devices() -> Vec<(u64, String)> {
    let mut list = Vec::new();
    unsafe {
        let params = BLUETOOTH_DEVICE_SEARCH_PARAMS {
            dwSize: std::mem::size_of::<BLUETOOTH_DEVICE_SEARCH_PARAMS>() as u32,
            fReturnAuthenticated: 1,
            fReturnRemembered: 1,
            fReturnUnknown: 0,
            fReturnConnected: 1,
            fIssueInquiry: 0,
            cTimeoutMultiplier: 1,
            hRadio: std::ptr::null_mut(),
        };

        let mut info: BLUETOOTH_DEVICE_INFO = std::mem::zeroed();
        info.dwSize = std::mem::size_of::<BLUETOOTH_DEVICE_INFO>() as u32;

        let handle = BluetoothFindFirstDevice(&params, &mut info);
        if !handle.is_null() {
            loop {
                let name_len = info.szName.iter().position(|&c| c == 0).unwrap_or(248);
                let name = String::from_utf16_lossy(&info.szName[..name_len]);
                list.push((info.Address, name));

                info = std::mem::zeroed();
                info.dwSize = std::mem::size_of::<BLUETOOTH_DEVICE_INFO>() as u32;
                if BluetoothFindNextDevice(handle, &mut info) == 0 {
                    break;
                }
            }
            BluetoothFindDeviceClose(handle);
        }
    }
    list
}
