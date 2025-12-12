//! Utilities that are not specific to a particular module

pub mod owned_handle;

#[cfg(test)]
pub mod task;

/// Inits logging for Android
#[cfg(target_os = "android")]
pub fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("bluetooth")
            .with_max_level(log::LevelFilter::Trace),
    );
}

/// Inits logging for host
#[cfg(not(target_os = "android"))]
pub fn init_logging() {
    env_logger::Builder::new().parse_default_env().try_init().ok();
}
