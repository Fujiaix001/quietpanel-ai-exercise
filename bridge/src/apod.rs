use std::env;
use std::fs;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, SystemTime};

use serde_json::{json, Value};

use crate::protocol::TRANSPORT_TOKEN;

const APOD_PORT: u16 = 27184;
const API_URL: &str = "https://api.nasa.gov/planetary/apod";
const CACHE_MAX_AGE: Duration = Duration::from_secs(6 * 60 * 60);
const METADATA_LIMIT: usize = 64 * 1024;
const IMAGE_LIMIT: usize = 6 * 1024 * 1024;
const CONNECT_RETRIES: usize = 5;

struct Payload {
    metadata: Vec<u8>,
    image: Vec<u8>,
}

pub fn deliver(address: SocketAddr) {
    match load_or_refresh() {
        Ok(payload) => match push_to_android(address, &payload) {
            Ok(()) => println!("NASA APOD delivered to Android"),
            Err(error) => eprintln!("NASA APOD delivery skipped: {error}"),
        },
        Err(error) => eprintln!("NASA APOD unavailable: {error}"),
    }
}

fn load_or_refresh() -> Result<Payload, String> {
    let directory = cache_directory()?;
    let metadata_path = directory.join("apod.json");
    let image_path = directory.join("apod-image.bin");

    if is_fresh(&metadata_path) {
        if let Ok(payload) = load_cache(&metadata_path, &image_path) {
            return Ok(payload);
        }
    }

    match download() {
        Ok(payload) => {
            fs::create_dir_all(&directory)
                .map_err(|error| format!("cannot create APOD cache: {error}"))?;
            fs::write(&metadata_path, &payload.metadata)
                .map_err(|error| format!("cannot cache APOD metadata: {error}"))?;
            fs::write(&image_path, &payload.image)
                .map_err(|error| format!("cannot cache APOD image: {error}"))?;
            Ok(payload)
        }
        Err(download_error) => load_cache(&metadata_path, &image_path).map_err(|cache_error| {
            format!("{download_error}; cached copy unavailable: {cache_error}")
        }),
    }
}

fn download() -> Result<Payload, String> {
    let api_key = env::var("QUIETPANEL_NASA_API_KEY")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "DEMO_KEY".to_string());
    let request_url = format!("{API_URL}?api_key={api_key}&thumbs=true");
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(8))
        .timeout_read(Duration::from_secs(20))
        .timeout_write(Duration::from_secs(8))
        .build();

    let response = agent
        .get(&request_url)
        .set("User-Agent", "QuietPanel/6.3")
        .call()
        .map_err(|error| format!("NASA API request failed: {error}"))?;
    let api_bytes = read_limited(response.into_reader(), METADATA_LIMIT)
        .map_err(|error| format!("NASA API response invalid: {error}"))?;
    let api: Value = serde_json::from_slice(&api_bytes)
        .map_err(|error| format!("NASA API JSON invalid: {error}"))?;

    let media_type = required_text(&api, "media_type")?;
    let image_url = if media_type == "video" {
        required_text(&api, "thumbnail_url")?
    } else {
        required_text(&api, "url")?
    };
    if !image_url.starts_with("https://") {
        return Err("NASA image URL is not HTTPS".to_string());
    }

    let image_response = agent
        .get(image_url)
        .set("User-Agent", "QuietPanel/6.3")
        .call()
        .map_err(|error| format!("NASA image request failed: {error}"))?;
    let content_type = image_response
        .header("Content-Type")
        .unwrap_or("image/jpeg")
        .split(';')
        .next()
        .unwrap_or("image/jpeg")
        .trim()
        .to_string();
    if !content_type.starts_with("image/") {
        return Err(format!("NASA response is not an image ({content_type})"));
    }
    let image = read_limited(image_response.into_reader(), IMAGE_LIMIT)
        .map_err(|error| format!("NASA image invalid: {error}"))?;
    if image.is_empty() {
        return Err("NASA image is empty".to_string());
    }

    let metadata = json!({
        "date": required_text(&api, "date")?,
        "title": required_text(&api, "title")?,
        "explanation": api.get("explanation").and_then(Value::as_str).unwrap_or(""),
        "copyright": api.get("copyright").and_then(Value::as_str).unwrap_or(""),
        "mediaType": media_type,
        "sourceUrl": api.get("url").and_then(Value::as_str).unwrap_or(""),
        "contentType": content_type,
    });
    let metadata = serde_json::to_vec(&metadata)
        .map_err(|error| format!("cannot encode APOD metadata: {error}"))?;

    Ok(Payload { metadata, image })
}

fn required_text<'a>(value: &'a Value, field: &str) -> Result<&'a str, String> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("NASA response is missing {field}"))
}

fn push_to_android(mut address: SocketAddr, payload: &Payload) -> io::Result<()> {
    address.set_port(APOD_PORT);
    let mut last_error = None;

    for _ in 0..CONNECT_RETRIES {
        match TcpStream::connect_timeout(&address, Duration::from_secs(2)) {
            Ok(mut stream) => {
                stream.set_write_timeout(Some(Duration::from_secs(15)))?;
                stream.set_nodelay(true)?;
                stream.write_all(b"QPAP")?;
                stream.write_all(TRANSPORT_TOKEN.as_bytes())?;
                stream.write_all(&(payload.metadata.len() as u32).to_be_bytes())?;
                stream.write_all(&(payload.image.len() as u32).to_be_bytes())?;
                stream.write_all(&payload.metadata)?;
                stream.write_all(&payload.image)?;
                stream.flush()?;
                return Ok(());
            }
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(500));
            }
        }
    }

    Err(last_error.unwrap_or_else(|| io::Error::other("APOD connection failed")))
}

fn cache_directory() -> Result<PathBuf, String> {
    if let Some(local_app_data) = env::var_os("LOCALAPPDATA") {
        return Ok(PathBuf::from(local_app_data)
            .join("QuietPanel")
            .join("cache"));
    }

    let mut path = env::current_exe().map_err(|error| error.to_string())?;
    path.pop();
    Ok(path.join("cache"))
}

fn is_fresh(path: &Path) -> bool {
    fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .and_then(|modified| {
            SystemTime::now()
                .duration_since(modified)
                .map_err(io::Error::other)
        })
        .map(|age| age < CACHE_MAX_AGE)
        .unwrap_or(false)
}

fn load_cache(metadata_path: &Path, image_path: &Path) -> Result<Payload, String> {
    let metadata = fs::read(metadata_path).map_err(|error| error.to_string())?;
    let image = fs::read(image_path).map_err(|error| error.to_string())?;
    if metadata.is_empty() || metadata.len() > METADATA_LIMIT {
        return Err("cached metadata has an invalid size".to_string());
    }
    if image.is_empty() || image.len() > IMAGE_LIMIT {
        return Err("cached image has an invalid size".to_string());
    }
    serde_json::from_slice::<Value>(&metadata)
        .map_err(|error| format!("cached metadata is invalid: {error}"))?;
    Ok(Payload { metadata, image })
}

fn read_limited<R: Read>(reader: R, limit: usize) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader.take((limit + 1) as u64).read_to_end(&mut bytes)?;
    if bytes.len() > limit {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("content exceeds {limit} bytes"),
        ));
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::read_limited;

    #[test]
    fn reads_content_within_limit() {
        assert_eq!(read_limited(Cursor::new(b"abc"), 3).unwrap(), b"abc");
    }

    #[test]
    fn rejects_content_over_limit() {
        assert!(read_limited(Cursor::new(b"abcd"), 3).is_err());
    }
}
