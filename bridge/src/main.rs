#![windows_subsystem = "windows"]

mod actions;
#[cfg(feature = "adb")]
mod adb;
mod apod;
mod connector;
mod display;
mod metrics;
#[cfg(not(feature = "adb"))]
mod pan;
mod protocol;
mod settings;
mod tray;
mod weather;

use std::io::{self, BufRead, BufReader, Write};
use std::net::{IpAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use connector::Connector;
use metrics::Metrics;
use serde::Serialize;

const RETRY_DELAY: Duration = Duration::from_secs(2);
const READ_POLL: Duration = Duration::from_millis(250);
const STATE_INTERVAL: Duration = Duration::from_secs(1);
const PING_INTERVAL: Duration = Duration::from_secs(5);

fn main() {
    println!("QuietPanel Bridge v{}", protocol::VERSION);
    println!("Press Ctrl+C or close this window to stop.");

    let mut connector = connector::ActiveConnector::new();

    let mut reporter = StatusReporter::default();
    let mut display_monitor = display::DisplayMonitor::start();
    let mut pages = settings::load_pages();
    let weather = weather::WeatherService::start(settings::load_weather_config());
    let tray = tray::TrayController::start(pages);
    println!("PC display monitor: active");

    println!("Mode: {}", connector.mode_label());

    while tray.is_running() {
        if let Some(changed) = tray.take_changed() {
            pages = changed;
            settings::save_pages(&pages);
        }

        let connection = match connector.connect() {
            Ok(connection) => connection,
            Err(error) => {
                reporter.report(&error);
                thread::sleep(RETRY_DELAY);
                continue;
            }
        };

        let connector::Connection {
            stream,
            label,
            phone_ip,
        } = connection;
        reporter.report(&format!("已連接至 QuietPanel 裝置 ({label})"));
        let _ = run_session(
            stream,
            &mut reporter,
            phone_ip,
            &mut display_monitor,
            &tray,
            &mut pages,
            &weather,
        );
        reporter.report(&format!("等待 QuietPanel 裝置 ({label})"));
        thread::sleep(RETRY_DELAY);
    }
}

fn run_session(
    mut writer: TcpStream,
    reporter: &mut StatusReporter,
    phone_ip: IpAddr,
    display_monitor: &mut display::DisplayMonitor,
    tray: &tray::TrayController,
    pages: &mut [bool; settings::PAGE_COUNT],
    weather: &weather::WeatherService,
) -> io::Result<()> {
    writer.set_nodelay(true)?;
    writer.set_write_timeout(Some(Duration::from_secs(3)))?;

    let reader_stream = writer.try_clone()?;
    reader_stream.set_read_timeout(Some(READ_POLL))?;
    let mut reader = BufReader::new(reader_stream);

    write_json(&mut writer, &protocol::HelloMessage::new())?;
    write_json(
        &mut writer,
        &protocol::DisplayStateMessage::new(display_monitor.current()),
    )?;
    write_json(&mut writer, &protocol::PageConfigMessage::from_pages(pages))?;
    thread::spawn(move || apod::deliver(phone_ip));

    let mut metrics = Metrics::new();
    let mut last_state = Instant::now() - STATE_INTERVAL;
    let mut last_ping = Instant::now();
    let mut line = String::new();
    let mut handshake_complete = false;
    let mut weather_revision = 0;

    loop {
        if !tray.is_running() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "Bridge stopped"));
        }
        if let Some(changed) = tray.take_changed() {
            *pages = changed;
            settings::save_pages(pages);
            write_json(&mut writer, &protocol::PageConfigMessage::from_pages(pages))?;
        }
        if let Some(display_on) = display_monitor.take_changed() {
            println!("PC display: {}", if display_on { "on" } else { "off" });
            write_json(&mut writer, &protocol::DisplayStateMessage::new(display_on))?;
        }
        if last_state.elapsed() >= STATE_INTERVAL {
            write_json(&mut writer, &metrics.snapshot())?;
            last_state = Instant::now();
        }
        if let Some((revision, snapshot)) = weather.snapshot_after(weather_revision) {
            write_json(&mut writer, &protocol::WeatherStateMessage::new(snapshot))?;
            weather_revision = revision;
        }

        if last_ping.elapsed() >= PING_INTERVAL {
            write_json(&mut writer, &protocol::PingMessage::new())?;
            last_ping = Instant::now();
        }

        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "peer closed")),
            Ok(_) => {
                let message = line.trim();
                match protocol::parse_client(message) {
                    Ok(protocol::ClientMessage::HelloAck) => {
                        if !handshake_complete {
                            handshake_complete = true;
                            reporter.report(&format!("Connected to Android ({phone_ip})"));
                        }
                    }
                    Ok(protocol::ClientMessage::Action(request)) => {
                        let label = actions::label(&request.action).unwrap_or("Unknown action");
                        println!("Action #{}: {}", request.id, label);
                        let outcome = actions::execute(&request.action);
                        write_json(
                            &mut writer,
                            &protocol::ActionResultMessage::new(
                                request.id,
                                outcome.ok,
                                &outcome.message,
                            ),
                        )?;
                    }
                    Ok(protocol::ClientMessage::Pong | protocol::ClientMessage::Other) => {}
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

fn write_json<T: Serialize + ?Sized>(stream: &mut TcpStream, value: &T) -> io::Result<()> {
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
