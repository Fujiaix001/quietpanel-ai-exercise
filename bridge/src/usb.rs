use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

pub const CONTROL_PORT: u16 = 27183;

// Android USB tethering normally assigns the phone one of these gateway addresses.
// The Redmi 1 used for QuietPanel uses 192.168.42.129.
const ANDROID_TETHER_GATEWAYS: [[u8; 4]; 5] = [
    [192, 168, 42, 129],
    [192, 168, 43, 1],
    [192, 168, 42, 1],
    [192, 168, 44, 1],
    [192, 168, 55, 1],
];

pub fn connect(timeout: Duration) -> io::Result<(TcpStream, SocketAddr)> {
    let mut last_error = None;

    for octets in ANDROID_TETHER_GATEWAYS {
        let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), CONTROL_PORT);
        match TcpStream::connect_timeout(&address, timeout) {
            Ok(stream) => return Ok((stream, address)),
            Err(error) => last_error = Some(error),
        }
    }

    Err(last_error.unwrap_or_else(|| io::Error::other("USB tethering unavailable")))
}
