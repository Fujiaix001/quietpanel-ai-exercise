use serde::{Deserialize, Serialize};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const WIRE_VERSION: u8 = 1;

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct HelloMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
    server: &'static str,
    version: &'static str,
}

impl HelloMessage {
    pub fn new() -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "hello",
            server: "QuietPanel Bridge",
            version: VERSION,
        }
    }
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct PingMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
}

impl PingMessage {
    pub fn new() -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "ping",
        }
    }
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct DisplayStateMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
    on: bool,
}

impl DisplayStateMessage {
    pub fn new(on: bool) -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "display_state",
            on,
        }
    }
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct PageConfigMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
    enabled: Vec<usize>,
}

impl PageConfigMessage {
    pub fn from_pages(pages: &[bool]) -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "page_config",
            enabled: pages
                .iter()
                .enumerate()
                .filter_map(|(index, is_enabled)| is_enabled.then_some(index))
                .collect(),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct WeatherSnapshot {
    pub temperature_c: f64,
    pub code: i32,
    pub is_day: bool,
    pub location: String,
    pub updated_at: u64,
    #[serde(default)]
    pub stale: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sunrise_at_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sunset_at_ms: Option<u64>,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct WeatherStateMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
    weather: WeatherSnapshot,
}

impl WeatherStateMessage {
    pub fn new(weather: WeatherSnapshot) -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "weather_state",
            weather,
        }
    }
}

#[derive(Debug, Serialize, PartialEq)]
pub struct SystemMetrics {
    #[serde(rename = "cpuPercent")]
    pub cpu_percent: f64,
    #[serde(rename = "ramPercent")]
    pub ram_percent: f64,
    #[serde(rename = "networkDownMBps")]
    pub network_down_mbps: f64,
    #[serde(rename = "networkUpMBps")]
    pub network_up_mbps: f64,
    #[serde(rename = "diskReadMBps")]
    pub disk_read_mbps: f64,
    #[serde(rename = "diskWriteMBps")]
    pub disk_write_mbps: f64,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct DiskMetrics {
    pub name: String,
    #[serde(rename = "totalGB")]
    pub total_gb: f64,
    #[serde(rename = "usedGB")]
    pub used_gb: f64,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct StateMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
    system: SystemMetrics,
    disks: Vec<DiskMetrics>,
}

impl StateMessage {
    pub fn new(system: SystemMetrics, disks: Vec<DiskMetrics>) -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "state",
            system,
            disks,
        }
    }
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct ActionResultMessage<'a> {
    v: u8,
    #[serde(rename = "type")]
    message_type: &'static str,
    id: u64,
    ok: bool,
    message: &'a str,
}

impl<'a> ActionResultMessage<'a> {
    pub fn new(id: u64, ok: bool, message: &'a str) -> Self {
        Self {
            v: WIRE_VERSION,
            message_type: "action_result",
            id,
            ok,
            message,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct ActionRequest {
    pub id: u64,
    pub action: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ClientMessage {
    HelloAck,
    Pong,
    Action(ActionRequest),
    Other,
}

#[derive(Deserialize)]
struct ClientEnvelope {
    v: u8,
    #[serde(rename = "type")]
    message_type: String,
}

#[derive(Deserialize)]
struct ActionWireMessage {
    v: u8,
    #[serde(rename = "type")]
    message_type: String,
    id: u64,
    action: String,
}

pub fn parse_client(line: &str) -> Result<ClientMessage, String> {
    let envelope: ClientEnvelope = serde_json::from_str(line).map_err(|error| error.to_string())?;
    if envelope.v != WIRE_VERSION {
        return Err("Unsupported protocol version".to_string());
    }

    match envelope.message_type.as_str() {
        "hello_ack" => Ok(ClientMessage::HelloAck),
        "pong" => Ok(ClientMessage::Pong),
        "action" => {
            let action: ActionWireMessage =
                serde_json::from_str(line).map_err(|error| error.to_string())?;
            if action.v != WIRE_VERSION || action.message_type != "action" {
                return Err("Invalid action envelope".to_string());
            }
            if action.action.trim().is_empty() {
                return Err("Action is missing action name".to_string());
            }
            Ok(ClientMessage::Action(ActionRequest {
                id: action.id,
                action: action.action,
            }))
        }
        _ => Ok(ClientMessage::Other),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        parse_client, ActionRequest, ActionResultMessage, ClientMessage, DiskMetrics,
        DisplayStateMessage, HelloMessage, PageConfigMessage, PingMessage, StateMessage,
        SystemMetrics, WeatherSnapshot, WeatherStateMessage,
    };
    use serde_json::json;

    #[test]
    fn parses_action_message() {
        assert_eq!(
            parse_client(r#"{"v":1,"type":"action","id":42,"action":"toggle_mute"}"#).unwrap(),
            ClientMessage::Action(ActionRequest {
                id: 42,
                action: "toggle_mute".to_string(),
            })
        );
    }

    #[test]
    fn parses_known_non_action_messages() {
        assert_eq!(
            parse_client(r#"{"v":1,"type":"hello_ack"}"#).unwrap(),
            ClientMessage::HelloAck
        );
        assert_eq!(
            parse_client(r#"{"v":1,"type":"pong"}"#).unwrap(),
            ClientMessage::Pong
        );
    }

    #[test]
    fn rejects_empty_action_name() {
        assert!(parse_client(r#"{"v":1,"type":"action","id":1,"action":""}"#).is_err());
    }

    #[test]
    fn rejects_unsupported_wire_version() {
        assert!(parse_client(r#"{"v":2,"type":"pong"}"#).is_err());
    }

    #[test]
    fn preserves_control_message_wire_contract() {
        assert_eq!(
            serde_json::to_value(HelloMessage::new()).unwrap(),
            json!({"v":1,"type":"hello","server":"QuietPanel Bridge","version":super::VERSION})
        );
        assert_eq!(
            serde_json::to_value(PingMessage::new()).unwrap(),
            json!({"v":1,"type":"ping"})
        );
        assert_eq!(
            serde_json::to_value(DisplayStateMessage::new(false)).unwrap(),
            json!({"v":1,"type":"display_state","on":false})
        );
    }

    #[test]
    fn builds_page_configuration() {
        let value =
            serde_json::to_value(PageConfigMessage::from_pages(&[true, false, true])).unwrap();
        assert_eq!(value, json!({"v":1,"type":"page_config","enabled":[0,2]}));
    }

    #[test]
    fn preserves_android_v1_wire_contract() {
        let state = StateMessage::new(
            SystemMetrics {
                cpu_percent: 12.3,
                ram_percent: 45.6,
                network_down_mbps: 1.2,
                network_up_mbps: 0.3,
                disk_read_mbps: 4.5,
                disk_write_mbps: 6.7,
            },
            vec![DiskMetrics {
                name: "C:\\".to_string(),
                total_gb: 100.0,
                used_gb: 25.0,
            }],
        );
        assert_eq!(
            serde_json::to_value(state).unwrap(),
            json!({
                "v":1,
                "type":"state",
                "system":{
                    "cpuPercent":12.3,
                    "ramPercent":45.6,
                    "networkDownMBps":1.2,
                    "networkUpMBps":0.3,
                    "diskReadMBps":4.5,
                    "diskWriteMBps":6.7
                },
                "disks":[{"name":"C:\\","totalGB":100.0,"usedGB":25.0}]
            })
        );

        let weather = WeatherStateMessage::new(WeatherSnapshot {
            temperature_c: 28.0,
            code: 2,
            is_day: true,
            location: "Taipei".to_string(),
            updated_at: 123,
            stale: false,
            sunrise_at_ms: None,
            sunset_at_ms: None,
        });
        assert_eq!(
            serde_json::to_value(weather).unwrap(),
            json!({"v":1,"type":"weather_state","weather":{
                "temperature_c":28.0,"code":2,"is_day":true,"location":"Taipei",
                "updated_at":123,"stale":false
            }})
        );

        let action = ActionResultMessage::new(7, true, "完成");
        assert_eq!(
            serde_json::to_value(action).unwrap(),
            json!({"v":1,"type":"action_result","id":7,"ok":true,"message":"完成"})
        );
    }
}
