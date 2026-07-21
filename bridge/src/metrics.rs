use std::time::Instant;

use serde_json::{json, Value};
use sysinfo::{Disks, Networks, System};

pub struct Metrics {
    system: System,
    networks: Networks,
    disks: Disks,
    last_rx: u64,
    last_tx: u64,
    last_sample: Instant,
}

impl Metrics {
    pub fn new() -> Self {
        let system = System::new_all();
        let networks = Networks::new_with_refreshed_list();
        let disks = Disks::new_with_refreshed_list();
        let (last_rx, last_tx) = network_totals(&networks);

        Self {
            system,
            networks,
            disks,
            last_rx,
            last_tx,
            last_sample: Instant::now(),
        }
    }

    pub fn snapshot(&mut self) -> Value {
        self.system.refresh_cpu_usage();
        self.system.refresh_memory();
        self.system.refresh_processes();
        self.networks.refresh();
        self.disks.refresh();

        let elapsed = self.last_sample.elapsed().as_secs_f64().max(0.001);
        self.last_sample = Instant::now();

        let cpu_percent = if self.system.cpus().is_empty() {
            0.0
        } else {
            self.system
                .cpus()
                .iter()
                .map(|cpu| cpu.cpu_usage())
                .sum::<f32>()
                / self.system.cpus().len() as f32
        };

        let total_memory = self.system.total_memory();
        let ram_percent = if total_memory == 0 {
            0.0
        } else {
            self.system.used_memory() as f64 / total_memory as f64 * 100.0
        };

        let (current_rx, current_tx) = network_totals(&self.networks);
        let down_mbps = current_rx.saturating_sub(self.last_rx) as f64 / elapsed / 1_048_576.0;
        let up_mbps = current_tx.saturating_sub(self.last_tx) as f64 / elapsed / 1_048_576.0;
        self.last_rx = current_rx;
        self.last_tx = current_tx;

        let (disk_read_bytes, disk_write_bytes) = self
            .system
            .processes()
            .values()
            .fold((0_u64, 0_u64), |(read, written), process| {
                let usage = process.disk_usage();
                (
                    read.saturating_add(usage.read_bytes),
                    written.saturating_add(usage.written_bytes),
                )
            });
        let disk_read_mbps = disk_read_bytes as f64 / elapsed / 1_048_576.0;
        let disk_write_mbps = disk_write_bytes as f64 / elapsed / 1_048_576.0;

        let disks: Vec<Value> = self
            .disks
            .list()
            .iter()
            .map(|disk| {
                let total = disk.total_space() as f64 / 1_073_741_824.0;
                let available = disk.available_space() as f64 / 1_073_741_824.0;
                json!({
                    "name": disk.mount_point().to_string_lossy(),
                    "totalGB": round_one(total),
                    "usedGB": round_one((total - available).max(0.0)),
                })
            })
            .collect();

        json!({
            "v": 1,
            "type": "state",
            "system": {
                "cpuPercent": round_one(cpu_percent as f64),
                "ramPercent": round_one(ram_percent),
                "networkDownMBps": round_one(down_mbps),
                "networkUpMBps": round_one(up_mbps),
                "diskReadMBps": round_one(disk_read_mbps),
                "diskWriteMBps": round_one(disk_write_mbps),
            },
            "disks": disks,
        })
    }
}

fn network_totals(networks: &Networks) -> (u64, u64) {
    networks.iter().fold((0, 0), |(rx, tx), (_, data)| {
        (
            rx.saturating_add(data.total_received()),
            tx.saturating_add(data.total_transmitted()),
        )
    })
}

fn round_one(value: f64) -> f64 {
    (value * 10.0).round() / 10.0
}

#[cfg(test)]
mod tests {
    use super::round_one;

    #[test]
    fn rounds_monitor_values_to_one_decimal() {
        assert_eq!(round_one(12.34), 12.3);
        assert_eq!(round_one(12.35), 12.4);
    }
}
