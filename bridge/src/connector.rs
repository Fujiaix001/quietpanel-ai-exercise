use std::net::{IpAddr, TcpStream};

pub struct Connection {
    pub stream: TcpStream,
    pub label: String,
    pub phone_ip: IpAddr,
}

pub trait Connector {
    fn mode_label(&self) -> &'static str;
    fn connect(&mut self) -> Result<Connection, String>;
}

#[cfg(feature = "adb")]
mod adb_connector;
#[cfg(feature = "adb")]
pub use adb_connector::ActiveConnector;

#[cfg(not(feature = "adb"))]
mod wireless_connector;
#[cfg(not(feature = "adb"))]
pub use wireless_connector::ActiveConnector;
