use std::fs;
use std::path::PathBuf;

use serde_json::{json, Value};

pub const PAGE_COUNT: usize = 6;
pub const DEFAULT_PAGES: [bool; PAGE_COUNT] = [true; PAGE_COUNT];

pub fn load_pages() -> [bool; PAGE_COUNT] {
    let Ok(text) = fs::read_to_string(settings_path()) else {
        return DEFAULT_PAGES;
    };
    parse_pages(&text).unwrap_or(DEFAULT_PAGES)
}

pub fn save_pages(pages: &[bool; PAGE_COUNT]) {
    if !pages.iter().any(|enabled| *enabled) {
        return;
    }
    let enabled: Vec<usize> = pages
        .iter()
        .enumerate()
        .filter_map(|(index, is_enabled)| is_enabled.then_some(index))
        .collect();
    let value = json!({ "enabledPages": enabled });
    if let Ok(text) = serde_json::to_string_pretty(&value) {
        let _ = fs::write(settings_path(), text);
    }
}

fn parse_pages(text: &str) -> Option<[bool; PAGE_COUNT]> {
    let value: Value = serde_json::from_str(text).ok()?;
    let values = value.get("enabledPages")?.as_array()?;
    let mut pages = [false; PAGE_COUNT];
    for value in values {
        if let Some(index) = value.as_u64().map(|number| number as usize) {
            if index < PAGE_COUNT {
                pages[index] = true;
            }
        }
    }
    pages.iter().any(|enabled| *enabled).then_some(pages)
}

fn settings_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("QuietPanelBridge.json")
}

#[cfg(test)]
mod tests {
    use super::parse_pages;

    #[test]
    fn parses_enabled_page_indices() {
        assert_eq!(
            parse_pages(r#"{"enabledPages":[0,2,5]}"#),
            Some([true, false, true, false, false, true])
        );
    }

    #[test]
    fn rejects_empty_page_selection() {
        assert_eq!(parse_pages(r#"{"enabledPages":[]}"#), None);
    }
}
