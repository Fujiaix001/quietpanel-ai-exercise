use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::Value;

use crate::protocol::WeatherSnapshot;

const REFRESH_INTERVAL: Duration = Duration::from_secs(60 * 60);
const RETRY_INTERVAL: Duration = Duration::from_secs(10 * 60);
const MAX_AGE_SECONDS: u64 = 6 * 60 * 60;
const RESPONSE_LIMIT: usize = 64 * 1024;

#[derive(Clone, Debug)]
pub struct WeatherConfig {
    pub enabled: bool,
    pub location: String,
    pub latitude: f64,
    pub longitude: f64,
}

#[derive(Clone, Default)]
pub struct WeatherService {
    state: Arc<Mutex<WeatherState>>,
}

#[derive(Default)]
struct WeatherState {
    revision: u64,
    weather: Option<WeatherSnapshot>,
}

impl WeatherService {
    pub fn start(config: WeatherConfig) -> Self {
        let service = Self::default();
        if !config.enabled {
            return service;
        }

        if let Some(cached) = load_cache() {
            service.store(cached);
        }

        let worker = service.clone();
        thread::spawn(move || loop {
            let delay = match download(&config) {
                Ok(weather) => {
                    let _ = save_cache(&weather);
                    worker.store(weather);
                    REFRESH_INTERVAL
                }
                Err(error) => {
                    eprintln!("Weather refresh failed: {error}");
                    RETRY_INTERVAL
                }
            };
            thread::sleep(delay);
        });
        service
    }

    pub fn snapshot_after(&self, revision: u64) -> Option<(u64, WeatherSnapshot)> {
        let state = self.state.lock().ok()?;
        let mut weather = state.weather.clone()?;
        let stale = unix_now().saturating_sub(weather.updated_at) > MAX_AGE_SECONDS;
        let effective_revision = state.revision.saturating_mul(2) + u64::from(stale);
        if effective_revision == revision {
            return None;
        }
        weather.stale = stale;
        Some((effective_revision, weather))
    }

    fn store(&self, weather: WeatherSnapshot) {
        if let Ok(mut state) = self.state.lock() {
            state.revision = state.revision.wrapping_add(1).max(1);
            state.weather = Some(weather);
        }
    }
}

fn download(config: &WeatherConfig) -> Result<WeatherSnapshot, String> {
    if !config.latitude.is_finite() || !config.longitude.is_finite() {
        return Err("invalid latitude or longitude".to_string());
    }
    let url = format!(
        "https://api.open-meteo.com/v1/forecast?latitude={:.6}&longitude={:.6}&current=temperature_2m,weather_code,is_day&temperature_unit=celsius&timezone=auto",
        config.latitude, config.longitude
    );
    let response = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(8))
        .timeout_read(Duration::from_secs(15))
        .timeout_write(Duration::from_secs(8))
        .build()
        .get(&url)
        .set(
            "User-Agent",
            &format!("QuietPanel/{}", crate::protocol::VERSION),
        )
        .call()
        .map_err(|error| error.to_string())?;
    let mut reader = response.into_reader().take((RESPONSE_LIMIT + 1) as u64);
    let mut bytes = Vec::new();
    use std::io::Read;
    reader
        .read_to_end(&mut bytes)
        .map_err(|error| error.to_string())?;
    if bytes.len() > RESPONSE_LIMIT {
        return Err("response exceeds limit".to_string());
    }
    parse_response(&bytes, &config.location)
}

fn parse_response(bytes: &[u8], location: &str) -> Result<WeatherSnapshot, String> {
    let root: Value = serde_json::from_slice(bytes).map_err(|error| error.to_string())?;
    let current = root
        .get("current")
        .and_then(Value::as_object)
        .ok_or_else(|| "missing current weather".to_string())?;
    let temperature = current
        .get("temperature_2m")
        .and_then(Value::as_f64)
        .ok_or_else(|| "missing temperature".to_string())?;
    let code = current
        .get("weather_code")
        .and_then(Value::as_i64)
        .ok_or_else(|| "missing weather code".to_string())?;
    let is_day = current.get("is_day").and_then(Value::as_i64).unwrap_or(1) != 0;
    let code = i32::try_from(code).map_err(|_| "weather code is out of range".to_string())?;
    Ok(WeatherSnapshot {
        temperature_c: temperature,
        code,
        is_day,
        location: location.to_string(),
        updated_at: unix_now(),
        stale: false,
    })
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn cache_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("QuietPanelWeatherCache.json")
}

fn load_cache() -> Option<WeatherSnapshot> {
    let text = fs::read_to_string(cache_path()).ok()?;
    serde_json::from_str(&text).ok()
}

fn save_cache(weather: &WeatherSnapshot) -> Result<(), String> {
    let text = serde_json::to_string_pretty(weather).map_err(|error| error.to_string())?;
    fs::write(cache_path(), text).map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::parse_response;

    #[test]
    fn parses_open_meteo_current_weather() {
        let value = parse_response(
            br#"{"current":{"temperature_2m":29.4,"weather_code":2,"is_day":1}}"#,
            "Taipei",
        )
        .unwrap();
        assert_eq!(value.temperature_c, 29.4);
        assert_eq!(value.code, 2);
        assert_eq!(value.location, "Taipei");
        assert!(value.is_day);
    }
}
