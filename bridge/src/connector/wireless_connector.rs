use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use crate::{pan, settings};

use super::{Connection, Connector};

const PORT: u16 = 27183;
const UDP_BEACON_PORT: u16 = 27185;
const CONNECT_TIMEOUT: Duration = Duration::from_millis(1500);
const PAN_RESCUE_DELAY: Duration = Duration::from_secs(8);

struct PanAttempt {
    sender: mpsc::Sender<io::Result<pan::ConnectOutcome>>,
    receiver: mpsc::Receiver<io::Result<pan::ConnectOutcome>>,
    started: Instant,
    workers: usize,
    rescue_started: bool,
    last_error: Option<String>,
}

enum PanProgress {
    Connected,
    Failed(String),
}

pub struct ActiveConnector {
    bluetooth_target: String,
    next_pan_attempt: Instant,
    pan_backoff: Duration,
    pan_attempt: Option<PanAttempt>,
}

impl ActiveConnector {
    pub fn new() -> Self {
        Self {
            bluetooth_target: settings::load_bluetooth_device(),
            next_pan_attempt: Instant::now(),
            pan_backoff: Duration::from_secs(8),
            pan_attempt: None,
        }
    }

    fn update_pan_attempt(&mut self) -> Option<String> {
        let mut progress = None;
        if let Some(attempt) = self.pan_attempt.as_mut() {
            while let Ok(result) = attempt.receiver.try_recv() {
                attempt.workers = attempt.workers.saturating_sub(1);
                match result {
                    Ok(pan::ConnectOutcome::Connected) => progress = Some(PanProgress::Connected),
                    Err(error) => attempt.last_error = Some(error.to_string()),
                }
            }

            // Some Windows Bluetooth stacks leave the first bthpanapi call
            // blocked.  One controlled rescue attempt is enough to wake it;
            // never create periodic blind workers.
            if progress.is_none()
                && !attempt.rescue_started
                && attempt.started.elapsed() >= PAN_RESCUE_DELAY
            {
                spawn_pan_worker(self.bluetooth_target.clone(), attempt.sender.clone());
                attempt.workers += 1;
                attempt.rescue_started = true;
                return Some(String::from("Bluetooth PAN 連線等待中；送出一次受控喚醒"));
            }

            if progress.is_none() && attempt.workers == 0 {
                progress = Some(PanProgress::Failed(
                    attempt
                        .last_error
                        .take()
                        .unwrap_or_else(|| String::from("未知錯誤")),
                ));
            }
        }

        match progress {
            Some(PanProgress::Connected) => {
                self.pan_attempt = None;
                self.pan_backoff = Duration::from_secs(8);
                self.next_pan_attempt = Instant::now() + Duration::from_secs(30);
                Some(format!("Bluetooth PAN 已連接至 {}", self.bluetooth_target))
            }
            Some(PanProgress::Failed(error)) => {
                self.pan_attempt = None;
                self.next_pan_attempt = Instant::now() + self.pan_backoff;
                self.pan_backoff = std::cmp::min(self.pan_backoff * 2, Duration::from_secs(300));
                Some(format!(
                    "Bluetooth PAN {}: {error}；稍後重試",
                    self.bluetooth_target
                ))
            }
            None => None,
        }
    }

    fn start_pan_attempt(&mut self) {
        let (sender, receiver) = mpsc::channel();
        spawn_pan_worker(self.bluetooth_target.clone(), sender.clone());
        self.pan_attempt = Some(PanAttempt {
            sender,
            receiver,
            started: Instant::now(),
            workers: 1,
            rescue_started: false,
            last_error: None,
        });
    }
}

impl Connector for ActiveConnector {
    fn mode_label(&self) -> &'static str {
        "Wi-Fi LAN / Bluetooth PAN Dual Mode (Port 27183)"
    }

    fn connect(&mut self) -> Result<Connection, String> {
        if let Some(connection) = discover_connection() {
            return Ok(connection);
        }

        if let Some(status) = self.update_pan_attempt() {
            return Err(status);
        }

        if self.pan_attempt.is_none() && Instant::now() >= self.next_pan_attempt {
            self.start_pan_attempt();
            return Err(format!(
                "正在建立 Bluetooth PAN ({})；請留意手機授權提示",
                self.bluetooth_target
            ));
        }

        Err(String::from(
            "等待 QuietPanel Android 裝置 (藍牙 PAN / Wi-Fi)...",
        ))
    }
}

fn spawn_pan_worker(target: String, sender: mpsc::Sender<io::Result<pan::ConnectOutcome>>) {
    thread::spawn(move || {
        let _ = sender.send(pan::connect_by_name(&target));
    });
}

fn discover_connection() -> Option<Connection> {
    if let Some(ip_str) = settings::load_phone_ip() {
        if let Ok(ip) = ip_str.parse::<IpAddr>() {
            if let Some(connection) = connect_to_phone(ip) {
                return Some(connection);
            }
        }
    }

    receive_phone_beacon().and_then(connect_to_phone)
}

fn connect_to_phone(ip: IpAddr) -> Option<Connection> {
    let address = SocketAddr::new(ip, PORT);
    let stream = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT).ok()?;
    Some(Connection {
        stream,
        label: format!("IP {ip} (Wi-Fi / Bluetooth PAN)"),
        phone_ip: ip,
    })
}

fn receive_phone_beacon() -> Option<IpAddr> {
    let socket = UdpSocket::bind(("0.0.0.0", UDP_BEACON_PORT)).ok()?;
    socket.set_read_timeout(Some(CONNECT_TIMEOUT)).ok()?;
    let mut buf = [0u8; 128];
    let (len, source) = socket.recv_from(&mut buf).ok()?;
    let message = std::str::from_utf8(&buf[..len]).ok()?;
    message
        .starts_with("QUIETPANEL_ANDROID_V8")
        .then_some(source.ip())
}
