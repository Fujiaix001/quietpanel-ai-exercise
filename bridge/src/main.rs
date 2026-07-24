#![windows_subsystem = "windows"]

mod actions;
#[cfg(feature = "adb")]
mod adb;
mod apod;
mod display;
mod metrics;
mod protocol;
mod settings;
mod tray;

use std::io::{self, BufRead, BufReader, Write};
#[cfg(not(feature = "adb"))]
use std::net::UdpSocket;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
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
    println!("Mode: Wi-Fi LAN (Port {PORT}, UDP Discover {UDP_BEACON_PORT})");

    while tray.is_running() {
        if let Some(changed) = tray.take_changed() {
            pages = changed;
            settings::save_pages(&pages);
        }

        #[cfg(feature = "adb")]
        let (phone_ip, label) = {
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
            (IpAddr::V4(Ipv4Addr::LOCALHOST), serial)
        };

        #[cfg(not(feature = "adb"))]
        let (phone_ip, label) = {
            match discover_phone_ip() {
                Some(ip) => (ip, ip.to_string()),
                None => {
                    reporter.report("Searching for QuietPanel Android App on Wi-Fi (UDP 27185)...");
                    thread::sleep(RETRY_DELAY);
                    continue;
                }
            }
        };

        reporter.report(&format!("Connecting to Android App ({label})"));
        let address = SocketAddr::new(phone_ip, PORT);
        let stream = match TcpStream::connect_timeout(&address, RETRY_DELAY) {
            Ok(stream) => stream,
            Err(_) => {
                thread::sleep(RETRY_DELAY);
                continue;
            }
        };

        let _ = run_session(
            stream,
            &mut reporter,
            phone_ip,
            &mut display_monitor,
            &tray,
            &mut pages,
        );
        reporter.report(&format!("Waiting for Android app ({label})"));
        thread::sleep(RETRY_DELAY);
    }
}

#[cfg(not(feature = "adb"))]
fn discover_phone_ip() -> Option<IpAddr> {
    if let Some(ip_str) = settings::load_phone_ip() {
        if let Ok(ip) = ip_str.parse::<IpAddr>() {
            return Some(ip);
        }
    }

    let socket = UdpSocket::bind(("0.0.0.0", UDP_BEACON_PORT)).ok()?;
    socket
        .set_read_timeout(Some(Duration::from_millis(2500)))
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
