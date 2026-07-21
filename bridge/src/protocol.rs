use serde_json::{json, Value};

pub const VERSION: &str = "7.0.0";
pub const TRANSPORT_TOKEN: &str = "qp7-usb-4d2c8a1f-6be0-49d5-91a3";

#[derive(Debug, PartialEq, Eq)]
pub struct ActionRequest {
    pub id: u64,
    pub action: String,
}

pub fn hello() -> Value {
    json!({
        "v": 1,
        "type": "hello",
        "server": "QuietPanel Bridge",
        "version": VERSION,
        "token": TRANSPORT_TOKEN,
    })
}

pub fn ping() -> Value {
    json!({ "v": 1, "type": "ping" })
}

pub fn display_state(display_on: bool) -> Value {
    json!({ "v": 1, "type": "display_state", "on": display_on })
}

pub fn page_config(pages: &[bool]) -> Value {
    let enabled: Vec<usize> = pages
        .iter()
        .enumerate()
        .filter_map(|(index, is_enabled)| is_enabled.then_some(index))
        .collect();
    json!({ "v": 1, "type": "page_config", "enabled": enabled })
}

pub fn action_result(id: u64, ok: bool, message: &str) -> Value {
    json!({
        "v": 1,
        "type": "action_result",
        "id": id,
        "ok": ok,
        "message": message,
    })
}

pub fn parse_action(line: &str) -> Result<Option<ActionRequest>, String> {
    let value: Value = serde_json::from_str(line).map_err(|error| error.to_string())?;

    if value.get("v").and_then(Value::as_u64) != Some(1) {
        return Err("Unsupported protocol version".to_string());
    }

    if value.get("type").and_then(Value::as_str) != Some("action") {
        return Ok(None);
    }

    let id = value
        .get("id")
        .and_then(Value::as_u64)
        .ok_or_else(|| "Action is missing id".to_string())?;
    let action = value
        .get("action")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| "Action is missing action name".to_string())?;

    Ok(Some(ActionRequest {
        id,
        action: action.to_string(),
    }))
}

#[cfg(test)]
mod tests {
    use super::{hello, page_config, parse_action, ActionRequest, TRANSPORT_TOKEN};

    #[test]
    fn hello_includes_the_pairing_token() {
        assert_eq!(
            hello().get("token").and_then(serde_json::Value::as_str),
            Some(TRANSPORT_TOKEN)
        );
    }

    #[test]
    fn parses_action_message() {
        assert_eq!(
            parse_action(r#"{"v":1,"type":"action","id":42,"action":"toggle_mute"}"#).unwrap(),
            Some(ActionRequest {
                id: 42,
                action: "toggle_mute".to_string(),
            })
        );
    }

    #[test]
    fn ignores_non_action_message() {
        assert_eq!(parse_action(r#"{"v":1,"type":"pong"}"#).unwrap(), None);
    }

    #[test]
    fn builds_page_configuration() {
        assert_eq!(
            page_config(&[true, false, true]),
            serde_json::json!({"v":1,"type":"page_config","enabled":[0,2]})
        );
    }
}
