use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

use crate::adb::Adb;

use super::{Connection, Connector};

const PORT: u16 = 27183;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

pub struct ActiveConnector {
    adb: Adb,
}

impl ActiveConnector {
    pub fn new() -> Self {
        let adb = Adb::locate();
        println!("ADB: {}", adb.display_path());
        Self { adb }
    }
}

impl Connector for ActiveConnector {
    fn mode_label(&self) -> &'static str {
        "USB ADB (Port 27183)"
    }

    fn connect(&mut self) -> Result<Connection, String> {
        let serial = self.adb.single_device()?;
        self.adb.ensure_forward(&serial)?;

        let phone_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
        let address = SocketAddr::new(phone_ip, PORT);
        let stream = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT)
            .map_err(|_| String::from("等待 QuietPanel Android App 的 ADB 連線..."))?;

        Ok(Connection {
            stream,
            label: serial,
            phone_ip,
        })
    }
}
