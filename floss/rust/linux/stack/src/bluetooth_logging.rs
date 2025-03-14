//! Modify the Bluetooth logging configuration to enable debug logging.
//!
//! There are two logging implementations depending on whether the log is
//! emitted from Rust or C/C++. In order to keep log levels in sync between the
//! two, the |BluetoothLogging| struct will configure both the Rust logging and
//! the C/C++ logging (via topshim).
use bt_topshim::syslog::{set_default_log_level, Level};
use log::LevelFilter;
use syslog::{BasicLogger, Error, Facility, Formatter3164};

use log_panics;

/// API to modify log levels that is exposed via RPC.
pub trait IBluetoothLogging {
    /// Check whether debug logging is enabled.
    fn is_debug_enabled(&self) -> bool;

    /// Change whether debug logging is enabled.
    fn set_debug_logging(&mut self, enabled: bool);

    /// Set the log level.
    fn set_log_level(&mut self, level: Level);

    /// Get the log level.
    fn get_log_level(&self) -> Level;
}

/// Logging related implementation.
pub struct BluetoothLogging {
    /// Current log level
    /// If the level is not verbose, `VERBOSE_ONLY_LOG_TAGS` will be set to emit up to `INFO` only.
    log_level: Level,

    /// Log to stderr?
    is_stderr: bool,

    /// Is logging already initialized?
    is_initialized: bool,
}

// TODO(b/371889111): Don't set log level for tag until b/371889111 is fixed.
/*
const VERBOSE_ONLY_LOG_TAGS: &[&str] = &[
    "bt_bta_av", // AV apis
    "btm_sco",   // SCO data path logs
    "l2c_csm",   // L2CAP state machine
    "l2c_link",  // L2CAP link layer logs
    "sco_hci",   // SCO over HCI
    "uipc",      // Userspace IPC implementation
];
*/

impl BluetoothLogging {
    pub fn new(is_debug: bool, is_verbose_debug: bool, log_output: &str) -> Self {
        let is_stderr = log_output == "stderr";

        let log_level = match (is_debug, is_verbose_debug) {
            (true, true) => Level::Verbose,
            (true, false) => Level::Debug,
            _ => Level::Info,
        };

        Self { log_level, is_stderr, is_initialized: false }
    }

    pub fn initialize(&mut self) -> Result<(), Error> {
        if self.is_stderr {
            env_logger::Builder::new().filter(None, self.get_log_level_filter()).init();
        } else {
            let formatter = Formatter3164 {
                facility: Facility::LOG_USER,
                hostname: None,
                process: "btadapterd".into(),
                pid: 0,
            };

            let logger = syslog::unix(formatter)?;
            let _ = log::set_boxed_logger(Box::new(BasicLogger::new(logger)))
                .map(|()| self.apply_linux_log_level());
            log_panics::init();
        }

        // Set initial log levels and filter out tags if not verbose debug.
        self.apply_libbluetooth_log_level();

        // Initialize the underlying system as well.
        self.is_initialized = true;
        Ok(())
    }

    fn should_enable_debug_mode(&self) -> bool {
        self.log_level == Level::Debug || self.log_level == Level::Verbose
    }

    fn get_log_level_filter(&self) -> LevelFilter {
        match self.should_enable_debug_mode() {
            true => LevelFilter::Debug,
            false => LevelFilter::Info,
        }
    }

    fn apply_linux_log_level(&self) {
        log::set_max_level(self.get_log_level_filter());
    }

    fn apply_libbluetooth_log_level(&self) {
        set_default_log_level(self.log_level);

        // TODO(b/371889111): Don't set log level for tag until b/371889111 is fixed.
        /*
        // Levels for verbose-only tags.
        let level = match self.log_level {
            Level::Verbose => Level::Verbose,
            _ => Level::Info,
        };
        for tag in VERBOSE_ONLY_LOG_TAGS {
            log::info!("Setting log level for tag {} to {:?}", tag, level);
            set_log_level_for_tag(tag, level);
        }
         */
    }
}

impl IBluetoothLogging for BluetoothLogging {
    fn is_debug_enabled(&self) -> bool {
        self.is_initialized && self.should_enable_debug_mode()
    }

    fn set_debug_logging(&mut self, enabled: bool) {
        if enabled {
            match self.log_level {
                Level::Verbose => {
                    self.set_log_level(Level::Verbose);
                }
                _ => {
                    self.set_log_level(Level::Debug);
                }
            }
        } else {
            self.set_log_level(Level::Info);
        }
    }

    fn set_log_level(&mut self, level: Level) {
        if !self.is_initialized {
            return;
        }

        self.log_level = level;

        // Update log level in Linux stack.
        self.apply_linux_log_level();

        // Update log level in libbluetooth.
        self.apply_libbluetooth_log_level();

        // Mark the start of debug logging with a debug print.
        if self.is_debug_enabled() {
            log::debug!("Debug logging successfully enabled!");
        }

        log::info!("Setting log level to {:?}", level);
    }

    fn get_log_level(&self) -> Level {
        self.log_level
    }
}
