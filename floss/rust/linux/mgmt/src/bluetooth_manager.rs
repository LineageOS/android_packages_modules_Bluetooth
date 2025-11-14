use log::{error, warn};

use std::collections::HashMap;
use std::process::Command;
use std::sync::{Arc, Mutex};

use configparser::ini::Ini;
use glob::glob;

use crate::powerd_suspend_manager::SuspendManagerContext;

use crate::iface_bluetooth_experimental::IBluetoothExperimental;
use crate::iface_bluetooth_manager::{
    AdapterWithEnabled, IBluetoothManager, IBluetoothManagerCallback,
};
use crate::state_machine::{
    state_to_enabled, AdapterState, Message, ProcessState, StateMachineProxy, VirtualHciIndex,
};
use crate::{config_util, migrate};

const BLUEZ_INIT_TARGET: &str = "bluetoothd";
const INVALID_VER: u16 = 0xffff;

/// Implementation of IBluetoothManager.
pub struct BluetoothManager {
    proxy: StateMachineProxy,
    callbacks: HashMap<u32, Box<dyn IBluetoothManagerCallback + Send>>,
    suspend_manager_context: Option<Arc<Mutex<SuspendManagerContext>>>,
}

impl BluetoothManager {
    pub fn new(proxy: StateMachineProxy) -> BluetoothManager {
        BluetoothManager { proxy, callbacks: HashMap::new(), suspend_manager_context: None }
    }

    pub fn set_suspend_manager_context(&mut self, context: Arc<Mutex<SuspendManagerContext>>) {
        self.suspend_manager_context = Some(context);
    }

    fn is_adapter_enabled(&self, hci_device: VirtualHciIndex) -> bool {
        state_to_enabled(self.proxy.get_process_state(hci_device))
    }

    fn is_adapter_present(&self, hci_device: VirtualHciIndex) -> bool {
        self.proxy.get_state(hci_device, move |a| Some(a.present)).unwrap_or(false)
    }

    pub(crate) fn callback_hci_device_change(&mut self, hci: VirtualHciIndex, present: bool) {
        if present {
            warn!("Presence added: {}", hci);
        } else {
            warn!("Presence removed: {}", hci);
        }
        for callback in self.callbacks.values_mut() {
            callback.on_hci_device_changed(hci.to_i32(), present);
        }
    }

    pub(crate) fn callback_hci_enabled_change(&mut self, hci: VirtualHciIndex, enabled: bool) {
        if enabled {
            warn!("Started {}", hci);
        } else {
            warn!("Stopped {}", hci);
        }

        for callback in self.callbacks.values_mut() {
            callback.on_hci_enabled_changed(hci.to_i32(), enabled);
        }
    }

    pub(crate) fn callback_default_adapter_change(&mut self, hci: VirtualHciIndex) {
        for callback in self.callbacks.values_mut() {
            callback.on_default_adapter_changed(hci.to_i32());
        }
    }

    pub(crate) fn callback_disconnected(&mut self, id: u32) {
        self.callbacks.remove(&id);
    }

    /// Restarts all TurningOn/On adapters to make sure the configuration is reloaded.
    pub(crate) fn restart_adapters(&mut self) {
        self.proxy
            .get_adapters()
            .iter()
            .filter(|a| a.state == ProcessState::TurningOn || a.state == ProcessState::On)
            .for_each(|a| self.proxy.restart_bluetooth(a.virt_hci));
    }
}

impl IBluetoothManager for BluetoothManager {
    fn start(&mut self, hci: i32) {
        let hci = VirtualHciIndex(hci);
        warn!("Starting {}", hci);

        if !config_util::modify_hci_n_enabled(hci, true) {
            error!("{}: Config is not successfully modified", hci);
        }

        // Store that this adapter is meant to be started in state machine.
        self.proxy.modify_state(hci, move |a: &mut AdapterState| a.config_enabled = true);

        // Ignore the request if adapter is already enabled or not present.
        if self.is_adapter_enabled(hci) {
            warn!("{} is already enabled.", hci);
            return;
        }

        if !self.is_adapter_present(hci) {
            warn!("{} is not present.", hci);
            return;
        }

        self.proxy.start_bluetooth(hci);
    }

    fn stop(&mut self, hci: i32) {
        let hci = VirtualHciIndex(hci);
        warn!("Stopping {}", hci);

        if !config_util::modify_hci_n_enabled(hci, false) {
            error!("{}: Config is not successfully modified", hci);
        }

        // Store that this adapter is meant to be stopped in state machine.
        self.proxy.modify_state(hci, move |a: &mut AdapterState| a.config_enabled = false);

        // Ignore the request if adapter is already disabled.
        if !self.is_adapter_enabled(hci) {
            warn!("{} is already stopped", hci);
            return;
        }

        self.proxy.stop_bluetooth(hci);
    }

    fn get_adapter_enabled(&mut self, hci_interface: i32) -> bool {
        self.is_adapter_enabled(VirtualHciIndex(hci_interface))
    }

    fn register_callback(&mut self, mut callback: Box<dyn IBluetoothManagerCallback + Send>) {
        let tx = self.proxy.get_tx();

        let id = callback.register_disconnect(Box::new(move |cb_id| {
            let tx = tx.clone();
            tokio::spawn(async move {
                let _result = tx.send(Message::CallbackDisconnected(cb_id)).await;
            });
        }));

        self.callbacks.insert(id, callback);
    }

    fn get_floss_enabled(&mut self) -> bool {
        self.proxy.get_floss_enabled()
    }

    fn set_floss_enabled(&mut self, enabled: bool) {
        warn!("Set Floss Enabeld={}", enabled);
        let prev = self.proxy.set_floss_enabled(enabled);
        config_util::write_floss_enabled(enabled);

        if prev != enabled && enabled {
            if let Err(e) = Command::new("initctl").args(["stop", BLUEZ_INIT_TARGET]).output() {
                warn!("Failed to stop bluetoothd: {}", e);
            }
            migrate::migrate_bluez_devices();
            for hci in self.proxy.get_valid_adapters().iter().map(|a| a.virt_hci) {
                if config_util::is_hci_n_enabled(hci) {
                    self.proxy.start_bluetooth(hci);
                }
            }
        } else if prev != enabled {
            for hci in self.proxy.get_valid_adapters().iter().map(|a| a.virt_hci) {
                if config_util::is_hci_n_enabled(hci) {
                    self.proxy.stop_bluetooth(hci);
                }
            }
            migrate::migrate_floss_devices();
            if let Err(e) = Command::new("initctl").args(["start", BLUEZ_INIT_TARGET]).output() {
                warn!("Failed to start bluetoothd: {}", e);
            }
        }
    }

    fn get_available_adapters(&mut self) -> Vec<AdapterWithEnabled> {
        self.proxy
            .get_valid_adapters()
            .iter()
            // Don't present the queued device to the user.
            .filter(|a| !a.has_queued_present)
            .map(|a| AdapterWithEnabled {
                hci_interface: a.virt_hci.to_i32(),
                enabled: state_to_enabled(a.state),
            })
            .collect::<Vec<AdapterWithEnabled>>()
    }

    fn get_default_adapter(&mut self) -> i32 {
        self.proxy.get_default_adapter().to_i32()
    }

    fn set_desired_default_adapter(&mut self, adapter_index: i32) {
        self.proxy.set_desired_default_adapter(VirtualHciIndex(adapter_index));
    }

    fn get_floss_api_version(&mut self) -> u32 {
        let major = env!("CARGO_PKG_VERSION_MAJOR").parse::<u16>().unwrap_or(INVALID_VER);
        let minor = env!("CARGO_PKG_VERSION_MINOR").parse::<u16>().unwrap_or(INVALID_VER);
        ((major as u32) << 16) | (minor as u32)
    }

    fn set_tablet_mode(&mut self, tablet_mode: bool) {
        match &self.suspend_manager_context {
            Some(ctx) => ctx.lock().unwrap().tablet_mode = tablet_mode,
            None => warn!("Context not available to set tablet mode."),
        }
    }
}

/// Helper function that check if there is at least one LE device in Floss config file.
fn config_with_le_device_entry(filename: &str) -> bool {
    let mut floss_conf = Ini::new_cs();
    let floss_map = match floss_conf.load(filename) {
        Ok(map) => map,
        Err(err) => {
            warn!("Error opening ini file while loading Floss devices for {}: {}", filename, err);
            return false;
        }
    };
    for (sec, props) in floss_map {
        // Skip all the non-device sections
        if !sec.contains(':') {
            continue;
        }
        // Invalid entries have no DevType
        if !props.contains_key("DevType") {
            continue;
        }
        for (k, v) in props {
            if k == "DevType" {
                let val = v.unwrap_or_default().to_string();
                // "1" BREDR, "2" LE, "3" DUAL
                if val != "1" {
                    return true;
                }
            }
        }
    }
    false
}

/// Check if there are any LE Floss devices in storage.
fn floss_have_le_devices() -> bool {
    let globbed = match glob(migrate::FLOSS_CONF_FILE) {
        Ok(v) => v,
        Err(_) => {
            warn!("Didn't find Floss conf file to search devices");
            return false;
        }
    };

    for entry in globbed {
        if config_with_le_device_entry(entry.unwrap_or_default().to_str().unwrap_or_default()) {
            return true;
        }
    }
    false
}

/// Implementation of IBluetoothExperimental
impl IBluetoothExperimental for BluetoothManager {
    fn set_ll_privacy(&mut self, enabled: bool) -> bool {
        warn!("Set Floss LL Privacy={}", enabled);
        let current_status = matches!(config_util::read_floss_ll_privacy_enabled(), Ok(true));
        let current_address_status =
            matches!(config_util::read_floss_address_privacy_enabled(), Ok(true));

        let mut need_restart = current_status != enabled;

        if current_status != enabled {
            if let Err(e) = config_util::write_floss_ll_privacy_enabled(enabled) {
                error!("Failed to write ll privacy status: {}", e);
                return false;
            }
        }

        // Make change only when LL privacy status is not consistent with address policy and
        // there is no LE devices in storage.
        if current_address_status != enabled && !floss_have_le_devices() {
            // Keep address policy aligned with LL privacy status.
            if let Err(e) = config_util::write_floss_address_privacy_enabled(enabled) {
                error!("Failed to write address privacy status {}: {}", enabled, e);
            } else {
                need_restart = true;
            }
        }

        if need_restart {
            self.restart_adapters();
        }

        true
    }
}
