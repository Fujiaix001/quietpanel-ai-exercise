#[path = "../pan.rs"]
mod pan;

fn main() {
    let target = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "68:DF:DD:0C:C1:AE".to_string());
    println!("QuietPanel PAN probe: {target}");
    match pan::connect_by_name(&target) {
        Ok(outcome) => println!("result: {outcome:?}"),
        Err(error) => println!(
            "error: {error}; raw_os_error={:?}; kind={:?}",
            error.raw_os_error(),
            error.kind()
        ),
    }
}
