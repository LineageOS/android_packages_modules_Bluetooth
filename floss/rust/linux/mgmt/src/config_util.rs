use crate::state_machine::{RealHciIndex, VirtualHciIndex};

use num_derive::{FromPrimitive, ToPrimitive};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;

/// File which includes the system release info.
#[cfg(not(test))]
const LSB_RELEASE: &str = "/etc/lsb-release";

/// lsb-release key used for release description entry.
const LSB_RELEASE_CHROMEOS_RELEASE_DESCRIPTION_KEY: &str = "CHROMEOS_RELEASE_DESCRIPTION";

/// File which includes the Aflags that should be enabled on the unstable channels.
const UNSTABLE_AFLAGS_SOURCE: &str = "/var/lib/bluetooth/unstable_aflags.conf";

/// File to store the Aflags that should be enabled on the unstable channels.
const UNSTABLE_AFLAGS_CONF: &str = "/var/lib/bluetooth/sysprops.conf.d/unstable_aflags.conf";

/// Directory for Bluetooth hci devices.
const HCI_DEVICES_DIR: &str = "/sys/class/bluetooth";

/// File to store the Bluetooth daemon to use (bluez or floss).
const BLUETOOTH_DAEMON_CURRENT: &str = "/var/lib/bluetooth/bluetooth-daemon.current";

/// File to store the config for BluetoothManager.
#[cfg(not(test))]
const BTMANAGERD_CONF: &str = "/var/lib/bluetooth/btmanagerd.json";

/// Folder to keep files which override floss configuration.
const FLOSS_SYSPROPS_OVERRIDE_DIR: &str = "/var/lib/bluetooth/sysprops.conf.d";

/// File to persist the address privacy setting.
const FLOSS_ADDRESS_PRIVACY_CONFIG_SAVE: &str =
    "/var/lib/bluetooth/privacy_address_override.conf.save";

/// In the absence of other values, default to hci0.
const DEFAULT_ADAPTER: VirtualHciIndex = VirtualHciIndex(0);

pub fn is_floss_enabled() -> bool {
    match std::fs::read(BLUETOOTH_DAEMON_CURRENT) {
        Ok(v) => {
            let content = std::str::from_utf8(&v);
            match content {
                Ok(version) => version.contains("floss"),
                Err(_) => true,
            }
        }
        Err(_) => true,
    }
}

pub fn write_floss_enabled(enabled: bool) -> bool {
    std::fs::write(
        BLUETOOTH_DAEMON_CURRENT,
        match enabled {
            true => "floss",
            _ => "bluez",
        },
    )
    .is_ok()
}

#[derive(Serialize, Deserialize, Default)]
struct BluetoothManagerConfig {
    #[serde(default)]
    default_adapter: VirtualHciIndex,
    #[serde(default)]
    unstable_aflags_use_mode: UnstableAflagsUseMode,
    #[serde(flatten, default)]
    hci: HashMap<String, BluetoothManagerHciConfig>,
}

impl Default for VirtualHciIndex {
    fn default() -> Self {
        DEFAULT_ADAPTER
    }
}

#[derive(Serialize, Deserialize)]
struct BluetoothManagerHciConfig {
    enabled: bool,
}

#[cfg(test)]
static FAKE_CONF: std::sync::LazyLock<std::sync::Mutex<String>> =
    std::sync::LazyLock::new(|| Default::default());

fn read_config() -> BluetoothManagerConfig {
    #[cfg(not(test))]
    let s = std::fs::read_to_string(BTMANAGERD_CONF);

    #[cfg(test)]
    let s = std::io::Result::Ok(FAKE_CONF.lock().unwrap().clone());

    match s.and_then(|s| serde_json::from_str(s.as_str()).map_err(|e| e.into())) {
        Ok(conf) => conf,
        Err(e) => {
            #[cfg(test)]
            assert!(false, "Failed to parse BluetoothManager config");

            log::error!("Failed to read BluetoothManager config, first boot? {}", e);
            let conf = BluetoothManagerConfig::default();
            if !write_config(&conf) {
                log::error!("Failed to generate an empty config");
            }
            conf
        }
    }
}

fn write_config(conf: &BluetoothManagerConfig) -> bool {
    let s = serde_json::ser::to_string_pretty(&conf).expect("config must be a valid json object");

    #[cfg(not(test))]
    {
        std::fs::write(BTMANAGERD_CONF, s)
            .inspect_err(|e| log::error!("Failed to write BluetoothManager config: {}", e))
            .is_ok()
    }

    #[cfg(test)]
    {
        *FAKE_CONF.lock().unwrap() = s;
        true
    }
}

/// Returns whether hci N is enabled in config; defaults to true.
pub fn is_hci_n_enabled(hci: VirtualHciIndex) -> bool {
    read_config()
        .hci
        .get(&format!("hci{}", hci.to_i32()))
        .map(|hci_conf| hci_conf.enabled)
        .unwrap_or(true)
}

pub fn modify_hci_n_enabled(hci: VirtualHciIndex, enabled: bool) -> bool {
    let mut conf = read_config();
    conf.hci
        .entry(format!("hci{}", hci.to_i32()))
        .and_modify(|hci_conf| hci_conf.enabled = enabled)
        .or_insert(BluetoothManagerHciConfig { enabled });
    write_config(&conf)
}

pub fn get_default_adapter() -> VirtualHciIndex {
    read_config().default_adapter
}

pub fn set_default_adapter(hci: VirtualHciIndex) -> bool {
    let mut conf = read_config();
    conf.default_adapter = hci;
    write_config(&conf)
}

/// Check whether a certain hci device exists in sysfs.
pub fn check_hci_device_exists(hci: RealHciIndex) -> bool {
    Path::new(format!("{}/hci{}", HCI_DEVICES_DIR, hci.to_i32()).as_str()).exists()
}

/// Get the devpath for a given hci index. This gives a stable path that can be
/// used to identify a device even as the hci index fluctuates.
pub fn get_devpath_for_hci(hci: RealHciIndex) -> Option<String> {
    match std::fs::canonicalize(format!("{}/hci{}/device", HCI_DEVICES_DIR, hci.to_i32()).as_str())
    {
        Ok(p) => Some(p.into_os_string().into_string().ok()?),
        Err(e) => {
            log::debug!("Failed to get devpath for {} with error: {}", hci, e);
            None
        }
    }
}

pub fn list_pid_files(pid_dir: &str) -> Vec<String> {
    match std::fs::read_dir(pid_dir) {
        Ok(entries) => entries
            .map(|e| e.unwrap().path().file_name().unwrap().to_str().unwrap().to_string())
            .collect::<Vec<_>>(),
        _ => Vec::new(),
    }
}

/// Calls the reset sysfs entry for an hci device. Returns True if the write succeeds.
pub fn reset_hci_device(hci: RealHciIndex) -> bool {
    let path = format!("/sys/class/bluetooth/hci{}/reset", hci.to_i32());
    std::fs::write(path, "1").is_ok()
}

pub fn read_floss_ll_privacy_enabled() -> std::io::Result<bool> {
    let parent = Path::new(FLOSS_SYSPROPS_OVERRIDE_DIR);
    if !parent.is_dir() {
        return Ok(false);
    }

    let data = std::fs::read_to_string(format!(
        "{}/{}",
        FLOSS_SYSPROPS_OVERRIDE_DIR, "privacy_override.conf"
    ))?;

    Ok(data == "[Sysprops]\nbluetooth.core.gap.le.privacy.enabled=true\n")
}

pub fn write_floss_ll_privacy_enabled(enabled: bool) -> std::io::Result<()> {
    let parent = Path::new(FLOSS_SYSPROPS_OVERRIDE_DIR);

    std::fs::create_dir_all(parent)?;

    let data = format!(
        "[Sysprops]\nbluetooth.core.gap.le.privacy.enabled={}",
        if enabled { "true\n" } else { "false\n" }
    );

    std::fs::write(format!("{}/{}", FLOSS_SYSPROPS_OVERRIDE_DIR, "privacy_override.conf"), data)
}

pub fn read_floss_address_privacy_enabled() -> std::io::Result<bool> {
    let parent = Path::new(FLOSS_SYSPROPS_OVERRIDE_DIR);
    if !parent.is_dir() {
        return Ok(false);
    }

    let data = std::fs::read_to_string(format!(
        "{}/{}",
        FLOSS_SYSPROPS_OVERRIDE_DIR, "privacy_address_override.conf"
    ))?;

    Ok(data == "[Sysprops]\nbluetooth.core.gap.le.privacy.own_address_type.enabled=true\n")
}

pub fn write_floss_address_privacy_enabled(enabled: bool) -> std::io::Result<()> {
    let parent = Path::new(FLOSS_SYSPROPS_OVERRIDE_DIR);

    std::fs::create_dir_all(parent)?;

    let data = format!(
        "[Sysprops]\nbluetooth.core.gap.le.privacy.own_address_type.enabled={}",
        if enabled { "true\n" } else { "false\n" }
    );

    std::fs::write(
        format!("{}/{}", FLOSS_SYSPROPS_OVERRIDE_DIR, "privacy_address_override.conf"),
        data.clone(),
    )?;

    std::fs::write(FLOSS_ADDRESS_PRIVACY_CONFIG_SAVE, data.clone())
}

#[derive(
    Debug, Clone, Copy, Eq, PartialEq, FromPrimitive, ToPrimitive, Serialize, Deserialize, Default,
)]
#[repr(u32)]
pub enum UnstableAflagsUseMode {
    #[default]
    Auto = 0,
    ForceUse = 1,
    ForceNoUse = 2,
}

pub fn get_unstable_aflags_use_mode() -> UnstableAflagsUseMode {
    read_config().unstable_aflags_use_mode
}

pub fn set_unstable_aflags_use_mode(mode: UnstableAflagsUseMode) -> bool {
    let mut conf = read_config();
    conf.unstable_aflags_use_mode = mode;
    write_config(&conf)
}

pub fn setup_unstable_aflags() -> bool {
    let mode = get_unstable_aflags_use_mode();
    let use_unstable = match mode {
        UnstableAflagsUseMode::Auto => {
            [ReleaseChannel::Dev, ReleaseChannel::Beta].contains(&get_release_channel())
        }
        UnstableAflagsUseMode::ForceUse => true,
        UnstableAflagsUseMode::ForceNoUse => false,
    };
    log::info!("Setting up unstable Aflags, mode: {:?}, use_unstable: {}", mode, use_unstable);

    if use_unstable {
        std::fs::copy(UNSTABLE_AFLAGS_SOURCE, UNSTABLE_AFLAGS_CONF)
            .inspect_err(|e| log::error!("Failed to copy unstable Aflags config: {}", e))
            .is_ok()
    } else {
        match std::fs::remove_file(UNSTABLE_AFLAGS_CONF) {
            // Success: The file was either removed or didn't exist to begin with.
            Ok(()) => true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => true,

            // Failure: An unexpected error occurred.
            Err(e) => {
                log::error!("Failed to remove unstable Aflags config: {}", e);
                false
            }
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
enum ReleaseChannel {
    Dev,
    Beta,
    Stable,
    Unknown,
}

#[cfg(test)]
static FAKE_LSB_RELEASE: std::sync::LazyLock<std::sync::Mutex<String>> =
    std::sync::LazyLock::new(|| Default::default());

fn get_release_channel() -> ReleaseChannel {
    #[cfg(not(test))]
    let s = std::fs::read_to_string(LSB_RELEASE);

    #[cfg(test)]
    let s = std::io::Result::Ok(FAKE_LSB_RELEASE.lock().unwrap().clone());

    let desc = match s.and_then(|lsb_release| {
        lsb_release
            .as_str()
            .lines()
            .find_map(|line| match line.split_once('=') {
                Some((lhs, rhs)) if lhs.trim() == LSB_RELEASE_CHROMEOS_RELEASE_DESCRIPTION_KEY => {
                    Some(rhs.trim().to_owned())
                }
                _ => None,
            })
            .ok_or(std::io::Error::other("release description not found"))
    }) {
        Err(e) => {
            log::error!("Failed to get the release description: {}", e);
            return ReleaseChannel::Unknown;
        }
        Ok(desc) => desc,
    };
    match desc.split(' ').find(|tok| tok.ends_with("-channel")) {
        Some("dev-channel") => ReleaseChannel::Dev,
        Some("beta-channel") => ReleaseChannel::Beta,
        Some("stable-channel") => ReleaseChannel::Stable,
        Some(other) => {
            log::warn!("Unknown channel name found in the release description: {}", other);
            ReleaseChannel::Unknown
        }
        None => {
            log::error!("Channel not found in the release description");
            ReleaseChannel::Unknown
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Cargo run tests in parallel but that breaks the mock FAKE_CONF. Protect it with this mutex.
    static TEST_LOCK: std::sync::LazyLock<std::sync::Mutex<()>> =
        std::sync::LazyLock::new(|| Default::default());

    #[test]
    fn parse_hci_enabled() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_CONF.lock().unwrap() = "{\"hci0\":{\"enabled\": true}}".to_string();
        assert!(is_hci_n_enabled(VirtualHciIndex(0)));
        assert!(is_hci_n_enabled(VirtualHciIndex(1)));

        *FAKE_CONF.lock().unwrap() = "{\"hci0\":{\"enabled\": false}}".to_string();
        assert!(!is_hci_n_enabled(VirtualHciIndex(0)));
        assert!(is_hci_n_enabled(VirtualHciIndex(1)));

        *FAKE_CONF.lock().unwrap() = "{\"hci1\":{\"enabled\": false}}".to_string();
        assert!(is_hci_n_enabled(VirtualHciIndex(0)));
        assert!(!is_hci_n_enabled(VirtualHciIndex(1)));
    }

    #[test]
    fn modify_hci_enabled() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_CONF.lock().unwrap() = "{}".to_string();
        assert!(is_hci_n_enabled(VirtualHciIndex(0)));
        assert!(modify_hci_n_enabled(VirtualHciIndex(0), true));
        assert!(is_hci_n_enabled(VirtualHciIndex(0)));
        assert!(modify_hci_n_enabled(VirtualHciIndex(0), false));
        assert!(!is_hci_n_enabled(VirtualHciIndex(0)));

        assert!(is_hci_n_enabled(VirtualHciIndex(1)));
        assert!(modify_hci_n_enabled(VirtualHciIndex(1), false));
        assert!(!is_hci_n_enabled(VirtualHciIndex(1)));
    }

    #[test]
    fn parse_default_adapter() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_CONF.lock().unwrap() = "{}".to_string();
        assert_eq!(get_default_adapter(), VirtualHciIndex(0));

        *FAKE_CONF.lock().unwrap() = "{\"default_adapter\": 0}".to_string();
        assert_eq!(get_default_adapter(), VirtualHciIndex(0));
    }

    #[test]
    fn modify_default_adapter() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_CONF.lock().unwrap() = "{\"default_adapter\": 0}".to_string();
        assert_ne!(get_default_adapter(), VirtualHciIndex(1));
        assert!(set_default_adapter(VirtualHciIndex(1)));
        assert_eq!(get_default_adapter(), VirtualHciIndex(1));
    }

    #[test]
    fn parse_unstable_aflags_use_mode() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_CONF.lock().unwrap() = "{}".to_string();
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::Auto);

        *FAKE_CONF.lock().unwrap() = "{\"unstable_aflags_use_mode\": \"Auto\"}".to_string();
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::Auto);

        *FAKE_CONF.lock().unwrap() = "{\"unstable_aflags_use_mode\": \"ForceUse\"}".to_string();
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::ForceUse);

        *FAKE_CONF.lock().unwrap() = "{\"unstable_aflags_use_mode\": \"ForceNoUse\"}".to_string();
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::ForceNoUse);
    }

    #[test]
    fn modify_unstable_aflags_use_mode() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_CONF.lock().unwrap() = "{}".to_string();
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::Auto);
        assert!(set_unstable_aflags_use_mode(UnstableAflagsUseMode::ForceUse));
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::ForceUse);
        assert!(set_unstable_aflags_use_mode(UnstableAflagsUseMode::Auto));
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::Auto);
        assert!(set_unstable_aflags_use_mode(UnstableAflagsUseMode::ForceNoUse));
        assert_eq!(get_unstable_aflags_use_mode(), UnstableAflagsUseMode::ForceNoUse);
    }

    #[test]
    fn parse_release_channel() {
        let _guard = TEST_LOCK.lock().unwrap();

        *FAKE_LSB_RELEASE.lock().unwrap() =
            "CHROMEOS_RELEASE_DESCRIPTION=16366.0.0 (Official Build) dev-channel zork test\n"
                .to_string();
        assert_eq!(get_release_channel(), ReleaseChannel::Dev);

        *FAKE_LSB_RELEASE.lock().unwrap() =
            "CHROMEOS_RELEASE_DESCRIPTION=16295.51.0 (Official Build) beta-channel rauru\n"
                .to_string();
        assert_eq!(get_release_channel(), ReleaseChannel::Beta);

        *FAKE_LSB_RELEASE.lock().unwrap() =
            "CHROMEOS_RELEASE_DESCRIPTION=16295.54.0 (Official Build) stable-channel brya\n"
                .to_string();
        assert_eq!(get_release_channel(), ReleaseChannel::Stable);

        *FAKE_LSB_RELEASE.lock().unwrap() =
            "CHROMEOS_RELEASE_DESCRIPTION=16295.54.0 (Official Build) ???-channel brya\n"
                .to_string();
        assert_eq!(get_release_channel(), ReleaseChannel::Unknown);

        *FAKE_LSB_RELEASE.lock().unwrap() =
            "CHROMEOS_RELEASE_DESCRIPTION=16373.0.0 (Test Build - root) developer-build zork"
                .to_string();
        assert_eq!(get_release_channel(), ReleaseChannel::Unknown);
    }
}
