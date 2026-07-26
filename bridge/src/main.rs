#![windows_subsystem = "windows"]

mod actions;
#[cfg(feature = "adb")]
mod adb;
mod apod;
mod display;
mod metrics;
#[cfg(not(feature = "adb"))]
mod pan;
mod protocol;
mod settings;
mod tray;

use std::io::{self, BufRead, BufReader, Write};
#[cfg(feature = "adb")]
use std::net::Ipv4Addr;
#[cfg(not(feature = "adb"))]
use std::net::UdpSocket;
use std::net::{IpAddr, SocketAddr, TcpStream};
#[cfg(not(feature = "adb"))]
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

#[cfg(feature = "adb")]
use adb::Adb;
use metrics::Metrics;
use serde_json::Value;

const PORT: u16 = 27183;
#[cfg(not(feature = "adb"))]
const UDP_BEACON_PORT: u16 = 27185;
const RETRY_DELAY: Duration = Duration::from_secs(2);
const READ_POLL: Duration = Duration::from_millis(250);
const STATE_INTERVAL: Duration = Duration::from_secs(1);
const PING_INTERVAL: Duration = Duration::from_secs(5);
#[cfg(not(feature = "adb"))]
const PAN_RESCUE_DELAY: Duration = Duration::from_secs(8);

#[cfg(not(feature = "adb"))]
struct PanAttempt {
    sender: mpsc::Sender<io::Result<pan::ConnectOutcome>>,
    receiver: mpsc::Receiver<io::Result<pan::ConnectOutcome>>,
    started: Instant,
    workers: usize,
    rescue_started: bool,
    last_error: Option<String>,
}

#[cfg(not(feature = "adb"))]
enum PanProgress {
    Connected,
    Failed(String),
}

fn main() {
    println!("QuietPanel Bridge v{}", protocol::VERSION);
    println!("Press Ctrl+C or close this window to stop.");

    #[cfg(feature = "adb")]
    let adb = Adb::locate();
    #[cfg(feature = "adb")]
    println!("ADB: {}", adb.display_path());

    let mut reporter = StatusReporter::default();
    let mut display_monitor = display::DisplayMonitor::start();
    let mut pages = settings::load_pages();
    let tray = tray::TrayController::start(pages);
    println!("PC display monitor: active");

    #[cfg(feature = "adb")]
    println!("Mode: USB ADB (Port {PORT})");
    #[cfg(not(feature = "adb"))]
    println!("Mode: Wi-Fi LAN / Bluetooth Dual Mode (Port {PORT})");

    #[cfg(not(feature = "adb"))]
    let bluetooth_target = settings::load_bluetooth_device();
    #[cfg(not(feature = "adb"))]
    let mut next_pan_attempt = Instant::now();
    #[cfg(not(feature = "adb"))]
    let mut pan_backoff = Duration::from_secs(8);
    #[cfg(not(feature = "adb"))]
    let mut pan_attempt: Option<PanAttempt> = None;

    while tray.is_running() {
        if let Some(changed) = tray.take_changed() {
            pages = changed;
            settings::save_pages(&pages);
        }

        #[cfg(not(feature = "adb"))]
        {
            let mut progress = None;
            if let Some(attempt) = pan_attempt.as_mut() {
                while let Ok(result) = attempt.receiver.try_recv() {
                    attempt.workers = attempt.workers.saturating_sub(1);
                    match result {
                        Ok(pan::ConnectOutcome::Connected) => {
                            progress = Some(PanProgress::Connected);
                        }
                        Err(error) => attempt.last_error = Some(error.to_string()),
                    }
                }

                // This Windows 10 / Intel stack can leave the first bthpanapi
                // call blocked until one duplicate call reports error 548.
                // Launch at most one rescue call, never periodic blind workers.
                if progress.is_none()
                    && !attempt.rescue_started
                    && attempt.started.elapsed() >= PAN_RESCUE_DELAY
                {
                    spawn_pan_worker(bluetooth_target.clone(), attempt.sender.clone());
                    attempt.workers += 1;
                    attempt.rescue_started = true;
                    reporter.report("Bluetooth PAN 連線等待中；送出一次受控喚醒");
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
                    reporter.report(&format!("Bluetooth PAN 已連接至 {bluetooth_target}"));
                    pan_attempt = None;
                    pan_backoff = Duration::from_secs(8);
                    next_pan_attempt = Instant::now() + Duration::from_secs(30);
                }
                Some(PanProgress::Failed(error)) => {
                    reporter.report(&format!(
                        "Bluetooth PAN {bluetooth_target}: {error}；稍後重試"
                    ));
                    pan_attempt = None;
                    next_pan_attempt = Instant::now() + pan_backoff;
                    pan_backoff = std::cmp::min(pan_backoff * 2, Duration::from_secs(300));
                }
                None => {}
            }
        }

        #[cfg(feature = "adb")]
        let (stream, label, phone_ip) = {
            let serial = match adb.single_device() {
                Ok(serial) => serial,
                Err(error) => {
                    reporter.report(&error);
                    thread::sleep(RETRY_DELAY);
                    continue;
                }
            };
            if let Err(error) = adb.ensure_forward(&serial) {
                reporter.report(&error);
                thread::sleep(RETRY_DELAY);
                continue;
            }
            let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), PORT);
            let s = match TcpStream::connect_timeout(&address, RETRY_DELAY) {
                Ok(s) => s,
                Err(_) => {
                    thread::sleep(RETRY_DELAY);
                    continue;
                }
            };
            (s, serial, IpAddr::V4(Ipv4Addr::LOCALHOST))
        };

        #[cfg(not(feature = "adb"))]
        let (stream, label, phone_ip) = {
            match discover_connection() {
                Some(connection) => connection,
                None => {
                    if pan_attempt.is_none() && Instant::now() >= next_pan_attempt {
                        reporter.report(&format!(
                            "正在建立 Bluetooth PAN ({bluetooth_target})；請留意手機授權提示"
                        ));
                        pan_attempt = Some(start_pan_attempt(bluetooth_target.clone()));
                    } else {
                        reporter.report("等待 QuietPanel Android 裝置 (藍牙 PAN / Wi-Fi)...");
                    }
                    thread::sleep(RETRY_DELAY);
                    continue;
                }
            }
        };

        reporter.report(&format!("已連接至 QuietPanel 裝置 ({label})"));
        let _ = run_session(
            stream,
            &mut reporter,
            phone_ip,
            &mut display_monitor,
            &tray,
            &mut pages,
        );
        reporter.report(&format!("等待 QuietPanel 裝置 ({label})"));
        thread::sleep(RETRY_DELAY);
    }
}

#[cfg(not(feature = "adb"))]
fn start_pan_attempt(target: String) -> PanAttempt {
    let (sender, receiver) = mpsc::channel();
    spawn_pan_worker(target, sender.clone());
    PanAttempt {
        sender,
        receiver,
        started: Instant::now(),
        workers: 1,
        rescue_started: false,
        last_error: None,
    }
}

#[cfg(not(feature = "adb"))]
fn spawn_pan_worker(target: String, sender: mpsc::Sender<io::Result<pan::ConnectOutcome>>) {
    thread::spawn(move || {
        let _ = sender.send(pan::connect_by_name(&target));
    });
}

#[cfg(not(feature = "adb"))]
fn discover_connection() -> Option<(TcpStream, String, IpAddr)> {
    if let Some(ip_str) = settings::load_phone_ip() {
        if let Ok(ip) = ip_str.parse::<IpAddr>() {
            if let Some(connection) = connect_to_phone(ip) {
                return Some(connection);
            }
        }
    }

    if let Some(ip) = receive_phone_beacon() {
        connect_to_phone(ip)
    } else {
        None
    }
}

#[cfg(not(feature = "adb"))]
fn connect_to_phone(ip: IpAddr) -> Option<(TcpStream, String, IpAddr)> {
    let address = SocketAddr::new(ip, PORT);
    if let Ok(stream) = TcpStream::connect_timeout(&address, Duration::from_millis(1500)) {
        return Some((stream, format!("IP {ip} (Wi-Fi / Bluetooth PAN)"), ip));
    }
    None
}

#[cfg(not(feature = "adb"))]
fn receive_phone_beacon() -> Option<IpAddr> {
    let socket = UdpSocket::bind(("0.0.0.0", UDP_BEACON_PORT)).ok()?;
    socket
        .set_read_timeout(Some(Duration::from_millis(1500)))
        .ok()?;
    let mut buf = [0u8; 128];
    let (len, src_addr) = socket.recv_from(&mut buf).ok()?;
    let msg = std::str::from_utf8(&buf[..len]).ok()?;
    if msg.starts_with("QUIETPANEL_ANDROID_V8") {
        Some(src_addr.ip())
    } else {
        None
    }
}

fn run_session(
    mut writer: TcpStream,
    reporter: &mut StatusReporter,
    phone_ip: IpAddr,
    display_monitor: &mut display::DisplayMonitor,
    tray: &tray::TrayController,
    pages: &mut [bool; settings::PAGE_COUNT],
) -> io::Result<()> {
    writer.set_nodelay(true)?;
    writer.set_write_timeout(Some(Duration::from_secs(3)))?;

    let reader_stream = writer.try_clone()?;
    reader_stream.set_read_timeout(Some(READ_POLL))?;
    let mut reader = BufReader::new(reader_stream);

    write_json(&mut writer, &protocol::hello())?;
    write_json(
        &mut writer,
        &protocol::display_state(display_monitor.current()),
    )?;
    write_json(&mut writer, &protocol::page_config(pages))?;
    thread::spawn(move || apod::deliver(phone_ip));

    let mut metrics = Metrics::new();
    let mut last_state = Instant::now() - STATE_INTERVAL;
    let mut last_ping = Instant::now();
    let mut line = String::new();
    let mut handshake_complete = false;

    loop {
        if !tray.is_running() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "Bridge stopped"));
        }
        if let Some(changed) = tray.take_changed() {
            *pages = changed;
            settings::save_pages(pages);
            write_json(&mut writer, &protocol::page_config(pages))?;
        }
        if let Some(display_on) = display_monitor.take_changed() {
            println!("PC display: {}", if display_on { "on" } else { "off" });
            write_json(&mut writer, &protocol::display_state(display_on))?;
        }
        if last_state.elapsed() >= STATE_INTERVAL {
            write_json(&mut writer, &metrics.snapshot())?;
            last_state = Instant::now();
        }

        if last_ping.elapsed() >= PING_INTERVAL {
            write_json(&mut writer, &protocol::ping())?;
            last_ping = Instant::now();
        }

        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "peer closed")),
            Ok(_) => {
                let message = line.trim();
                if !handshake_complete {
                    if let Ok(value) = serde_json::from_str::<Value>(message) {
                        if value.get("type").and_then(Value::as_str) == Some("hello_ack") {
                            handshake_complete = true;
                            reporter.report(&format!("Connected to Android ({phone_ip})"));
                        }
                    }
                }

                match protocol::parse_action(message) {
                    Ok(Some(request)) => {
                        let label = actions::label(&request.action).unwrap_or("Unknown action");
                        println!("Action #{}: {}", request.id, label);
                        let outcome = actions::execute(&request.action);
                        write_json(
                            &mut writer,
                            &protocol::action_result(request.id, outcome.ok, &outcome.message),
                        )?;
                    }
                    Ok(None) => {}
                    Err(error) => eprintln!("Ignored invalid message: {error}"),
                }
            }
            Err(error)
                if error.kind() == io::ErrorKind::WouldBlock
                    || error.kind() == io::ErrorKind::TimedOut => {}
            Err(error) => return Err(error),
        }
    }
}

fn write_json(stream: &mut TcpStream, value: &Value) -> io::Result<()> {
    serde_json::to_writer(&mut *stream, value).map_err(io::Error::other)?;
    stream.write_all(b"\n")?;
    stream.flush()
}

#[derive(Default)]
struct StatusReporter {
    last: String,
}

impl StatusReporter {
    fn report(&mut self, message: &str) {
        if self.last != message {
            println!("Status: {message}");
            self.last.clear();
            self.last.push_str(message);
        }
    }
}
