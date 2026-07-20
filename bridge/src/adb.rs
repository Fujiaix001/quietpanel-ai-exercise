use std::env;
use std::io;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Command, Output};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const FORWARDS: [(&str, &str); 2] = [("tcp:27183", "tcp:27183"), ("tcp:27184", "tcp:27184")];

pub struct Adb {
    path: PathBuf,
}

impl Adb {
    pub fn locate() -> Self {
        if let Some(path) = env::var_os("QUIETPANEL_ADB") {
            return Self { path: path.into() };
        }

        if let Ok(mut path) = env::current_exe() {
            path.pop();
            path.push("adb.exe");
            if path.is_file() {
                return Self { path };
            }
        }

        if let Some(local_app_data) = env::var_os("LOCALAPPDATA") {
            let path = PathBuf::from(local_app_data)
                .join("Android")
                .join("Sdk")
                .join("platform-tools")
                .join("adb.exe");
            if path.is_file() {
                return Self { path };
            }
        }

        Self {
            path: PathBuf::from("adb.exe"),
        }
    }

    pub fn display_path(&self) -> String {
        self.path.display().to_string()
    }

    pub fn single_device(&self) -> Result<String, String> {
        let output = self
            .run(&["devices"])
            .map_err(|error| format!("Cannot run adb: {error}"))?;

        if !output.status.success() {
            return Err(command_error("adb devices failed", &output));
        }

        parse_single_device(&String::from_utf8_lossy(&output.stdout))
    }

    pub fn ensure_forward(&self, serial: &str) -> Result<(), String> {
        let list = self
            .run(&["forward", "--list"])
            .map_err(|error| format!("Cannot list adb forwards: {error}"))?;

        let existing = String::from_utf8_lossy(&list.stdout);
        for (local, remote) in FORWARDS {
            if list.status.success() && forward_exists(&existing, serial, local, remote) {
                continue;
            }

            let output = self
                .run(&["-s", serial, "forward", local, remote])
                .map_err(|error| format!("Cannot create adb forward: {error}"))?;

            if !output.status.success() {
                return Err(command_error("adb forward failed", &output));
            }
        }

        Ok(())
    }

    fn run(&self, args: &[&str]) -> io::Result<Output> {
        Command::new(&self.path)
            .args(args)
            .creation_flags(CREATE_NO_WINDOW)
            .output()
    }
}

fn command_error(prefix: &str, output: &Output) -> String {
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if stderr.is_empty() {
        format!("{prefix} (exit {})", output.status)
    } else {
        format!("{prefix}: {stderr}")
    }
}

fn parse_single_device(output: &str) -> Result<String, String> {
    let mut ready = Vec::new();
    let mut blocked = Vec::new();

    for line in output.lines().skip(1) {
        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        let Some(state) = parts.next() else {
            continue;
        };

        match state {
            "device" => ready.push(serial.to_string()),
            "unauthorized" => blocked.push(format!("{serial}: USB authorization required")),
            "offline" => blocked.push(format!("{serial}: offline")),
            _ => {}
        }
    }

    match ready.as_slice() {
        [serial] => Ok(serial.clone()),
        [] if blocked.is_empty() => Err("No Android device detected".to_string()),
        [] => Err(blocked.join(", ")),
        _ => Err(format!(
            "Multiple Android devices detected: {}",
            ready.join(", ")
        )),
    }
}

fn forward_exists(output: &str, serial: &str, local: &str, remote: &str) -> bool {
    output.lines().any(|line| {
        let parts: Vec<&str> = line.split_whitespace().collect();
        parts.len() >= 3 && parts[0] == serial && parts[1] == local && parts[2] == remote
    })
}

#[cfg(test)]
mod tests {
    use super::{forward_exists, parse_single_device};

    #[test]
    fn parses_one_ready_device() {
        let output = "List of devices attached\nABC123\tdevice product:test\n\n";
        assert_eq!(parse_single_device(output).unwrap(), "ABC123");
    }

    #[test]
    fn reports_unauthorized_device() {
        let output = "List of devices attached\nABC123\tunauthorized\n";
        assert!(parse_single_device(output)
            .unwrap_err()
            .contains("authorization"));
    }

    #[test]
    fn rejects_multiple_ready_devices() {
        let output = "List of devices attached\nA\tdevice\nB\tdevice\n";
        assert!(parse_single_device(output)
            .unwrap_err()
            .contains("Multiple"));
    }

    #[test]
    fn matches_only_the_requested_forward() {
        let output = "ABC tcp:27183 tcp:27183\nABC tcp:27184 tcp:27184\n";
        assert!(forward_exists(output, "ABC", "tcp:27183", "tcp:27183"));
        assert!(forward_exists(output, "ABC", "tcp:27184", "tcp:27184"));
        assert!(!forward_exists(output, "NOPE", "tcp:27183", "tcp:27183"));
    }
}
