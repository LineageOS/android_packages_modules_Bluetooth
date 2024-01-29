use crate::init_flags::{
    get_log_level_for_tag, LOG_TAG_DEBUG, LOG_TAG_ERROR, LOG_TAG_FATAL, LOG_TAG_INFO,
    LOG_TAG_NOTICE, LOG_TAG_VERBOSE, LOG_TAG_WARN,
};

fn get_log_level() -> log::LevelFilter {
    match get_log_level_for_tag("bluetooth_core") {
        LOG_TAG_FATAL => log::LevelFilter::Error,
        LOG_TAG_ERROR => log::LevelFilter::Error,
        LOG_TAG_WARN => log::LevelFilter::Warn,
        LOG_TAG_NOTICE => log::LevelFilter::Info,
        LOG_TAG_INFO => log::LevelFilter::Info,
        LOG_TAG_DEBUG => log::LevelFilter::Debug,
        LOG_TAG_VERBOSE => log::LevelFilter::Trace,
        _ => log::LevelFilter::Info, // default level
    }
}

/// Inits logging for Android
#[cfg(target_os = "android")]
pub fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default().with_tag("bt").with_max_level(get_log_level()),
    );
    log::set_max_level(get_log_level())
}

/// Inits logging for host
#[cfg(not(target_os = "android"))]
pub fn init_logging() {
    env_logger::Builder::new().filter(None, get_log_level()).parse_default_env().try_init().ok();
    log::set_max_level(get_log_level())
}
