//! Anything related to the Admin API (IBluetoothAdmin).

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{Error, ErrorKind, Read, Result, Write};
use std::sync::{Arc, Mutex};

use crate::bluetooth::{Bluetooth, BluetoothDevice, IBluetooth, IBluetoothCallback};
use crate::bluetooth_media::BluetoothMedia;
use crate::callbacks::Callbacks;
use crate::socket_manager::BluetoothSocketManager;
use crate::uuid::{Profile, UuidHelper};
use crate::{APIMessage, BluetoothAPI, Message, RPCProxy};

use bt_topshim::btif::{BtPropertyType, BtSspVariant, RawAddress, Uuid};
use bt_topshim::profiles::sdp::BtSdpRecord;
use log::{info, warn};
use serde_json::{json, Value};
use tokio::sync::mpsc::Sender;

/// Defines the Admin API
pub trait IBluetoothAdmin {
    /// Check if the given UUID is in the allowlist
    fn is_service_allowed(&self, service: Uuid) -> bool;
    /// Overwrite the current settings and store it to a file.
    fn set_allowed_services(&mut self, services: Vec<Uuid>) -> bool;
    /// Get the allowlist in UUIDs
    fn get_allowed_services(&self) -> Vec<Uuid>;
    /// Get the PolicyEffect struct of a device
    fn get_device_policy_effect(&self, device: BluetoothDevice) -> Option<PolicyEffect>;
    /// Register client callback
    fn register_admin_policy_callback(
        &mut self,
        callback: Box<dyn IBluetoothAdminPolicyCallback + Send>,
    ) -> u32;
    /// Unregister client callback via callback ID
    fn unregister_admin_policy_callback(&mut self, callback_id: u32) -> bool;
}

/// Information of the effects to a remote device by the admin policies
#[derive(PartialEq, Clone, Debug)]
pub struct PolicyEffect {
    /// Array of services that are blocked by policy
    pub service_blocked: Vec<Uuid>,
    /// Indicate if the device has an adapter-supported profile that is blocked by the policy
    pub affected: bool,
}

/// A helper struct that tells whether a service or a profile is allowed.
#[derive(Clone)]
pub(crate) struct BluetoothAdminPolicyHelper {
    allowed_services: HashSet<Uuid>,
}

impl Default for BluetoothAdminPolicyHelper {
    fn default() -> Self {
        Self { allowed_services: HashSet::default() }
    }
}

impl BluetoothAdminPolicyHelper {
    pub(crate) fn is_service_allowed(&self, service: &Uuid) -> bool {
        self.allowed_services.is_empty() || self.allowed_services.contains(service)
    }

    pub(crate) fn is_profile_allowed(&self, profile: &Profile) -> bool {
        self.is_service_allowed(UuidHelper::get_profile_uuid(&profile).unwrap())
    }

    fn set_allowed_services(&mut self, services: Vec<Uuid>) -> bool {
        let services: HashSet<Uuid> = services.into_iter().collect();
        if self.allowed_services != services {
            self.allowed_services = services;
            true
        } else {
            false
        }
    }

    fn get_allowed_services(&self) -> Vec<Uuid> {
        self.allowed_services.iter().cloned().collect()
    }

    fn get_blocked_services(&self, remote_uuids: &Vec<Uuid>) -> Vec<Uuid> {
        remote_uuids.iter().filter(|&uu| !self.is_service_allowed(uu)).cloned().collect()
    }
}

pub trait IBluetoothAdminPolicyCallback: RPCProxy {
    /// This gets called when service allowlist changed.
    fn on_service_allowlist_changed(&mut self, allowlist: Vec<Uuid>);
    /// This gets called when
    /// 1. a new device is found by adapter
    /// 2. the policy effect to a device is changed due to
    ///    the remote services changed or
    ///    the service allowlist changed.
    fn on_device_policy_effect_changed(
        &mut self,
        device: BluetoothDevice,
        new_policy_effect: Option<PolicyEffect>,
    );
}

pub struct BluetoothAdmin {
    path: String,
    adapter: Arc<Mutex<Box<Bluetooth>>>,
    bluetooth_media: Arc<Mutex<Box<BluetoothMedia>>>,
    socket_manager: Arc<Mutex<Box<BluetoothSocketManager>>>,
    admin_helper: BluetoothAdminPolicyHelper,
    callbacks: Callbacks<dyn IBluetoothAdminPolicyCallback + Send>,
    device_policy_affect_cache: HashMap<BluetoothDevice, Option<PolicyEffect>>,
    tx: Sender<Message>,
}

impl BluetoothAdmin {
    pub fn new(
        path: String,
        tx: Sender<Message>,
        adapter: Arc<Mutex<Box<Bluetooth>>>,
        bluetooth_media: Arc<Mutex<Box<BluetoothMedia>>>,
        socket_manager: Arc<Mutex<Box<BluetoothSocketManager>>>,
    ) -> Self {
        Self {
            path,
            adapter,
            bluetooth_media,
            socket_manager,
            admin_helper: Default::default(), // By default allowed all services
            callbacks: Callbacks::new(tx.clone(), Message::AdminCallbackDisconnected),
            device_policy_affect_cache: HashMap::new(),
            tx,
        }
    }

    pub fn initialize(&mut self, api_tx: Sender<APIMessage>) {
        if let Err(e) = self.load_config() {
            warn!("Admin: Failed to load config file: {}", e);
        } else {
            info!("Admin: Load settings from {} successfully", &self.path);
        }

        // Listen to the device events from adapter.
        self.adapter
            .lock()
            .unwrap()
            .register_callback(Box::new(BluetoothDeviceCallbacks::new(self.tx.clone())));
        let devices_and_uuids = self.adapter.lock().unwrap().get_all_devices_and_uuids();
        for (remote_device, uuids) in devices_and_uuids.into_iter() {
            self.on_device_found(&remote_device);
            if let Some(uuids) = uuids {
                self.on_device_uuid_changed(&remote_device, uuids);
            }
        }

        // Now toggle the profiles based on the loaded config.
        self.adapter.lock().unwrap().handle_admin_policy_changed(self.admin_helper.clone());
        self.bluetooth_media.lock().unwrap().handle_admin_policy_changed(self.admin_helper.clone());
        self.socket_manager.lock().unwrap().handle_admin_policy_changed(self.admin_helper.clone());

        // DBus API is ready now.
        tokio::spawn(async move {
            let _ = api_tx.send(APIMessage::IsReady(BluetoothAPI::Admin)).await;
        });
    }

    fn get_blocked_services(&self, remote_uuids: &Vec<Uuid>) -> Vec<Uuid> {
        self.admin_helper.get_blocked_services(remote_uuids)
    }

    fn get_affected_status(&self, blocked_services: &Vec<Uuid>) -> bool {
        // return true if a supported profile is in blocked services.
        blocked_services
            .iter()
            .find(|&uuid| {
                UuidHelper::is_known_profile(uuid)
                    .map_or(false, |p| UuidHelper::is_profile_supported(&p))
            })
            .is_some()
    }

    fn load_config(&mut self) -> Result<()> {
        let mut file = File::open(&self.path)?;
        let mut contents = String::new();
        file.read_to_string(&mut contents)?;
        let json = serde_json::from_str::<Value>(contents.as_str())?;
        let allowed_services = Self::get_config_from_json(&json)
            .ok_or(Error::new(ErrorKind::Other, "Failed converting json to config"))?;
        if !self.admin_helper.set_allowed_services(allowed_services) {
            info!("Admin: load_config: Unchanged");
        }
        Ok(())
    }

    fn get_config_from_json(json: &Value) -> Option<Vec<Uuid>> {
        Some(
            json.get("allowed_services")?
                .as_array()?
                .iter()
                .filter_map(|v| Uuid::from_string(v.as_str()?))
                .collect(),
        )
    }

    fn write_config(&self) -> Result<()> {
        let mut f = File::create(&self.path)?;
        f.write_all(
            Self::get_config_json_string(self.admin_helper.get_allowed_services()).as_bytes(),
        )
    }

    fn get_config_json_string(uuids: Vec<Uuid>) -> String {
        serde_json::to_string_pretty(&json!({
            "allowed_services":
                uuids
                    .iter()
                    .map(|uu| uu.to_string())
                    .collect::<Vec<String>>()
        }))
        .ok()
        .unwrap()
    }

    fn new_device_policy_effect(&self, uuids: Option<Vec<Uuid>>) -> Option<PolicyEffect> {
        uuids.map(|uuids| {
            let service_blocked = self.get_blocked_services(&uuids);
            let affected = self.get_affected_status(&service_blocked);
            PolicyEffect { service_blocked, affected }
        })
    }

    pub fn on_device_found(&mut self, remote_device: &BluetoothDevice) {
        self.device_policy_affect_cache.insert(remote_device.clone(), None).or_else(|| {
            self.callbacks.for_all_callbacks(|cb| {
                cb.on_device_policy_effect_changed(remote_device.clone(), None);
            });
            None
        });
    }

    pub fn on_device_cleared(&mut self, remote_device: &BluetoothDevice) {
        self.device_policy_affect_cache.remove(remote_device);
    }

    pub fn on_device_uuid_changed(
        &mut self,
        remote_device: &BluetoothDevice,
        new_uuids: Vec<Uuid>,
    ) {
        let new_effect = self.new_device_policy_effect(Some(new_uuids));
        let cur_effect = self.device_policy_affect_cache.get(remote_device);

        if cur_effect.is_none() || *cur_effect.unwrap() != new_effect.clone() {
            self.callbacks.for_all_callbacks(|cb| {
                cb.on_device_policy_effect_changed(remote_device.clone(), new_effect.clone())
            });
            self.device_policy_affect_cache.insert(remote_device.clone(), new_effect.clone());
        }
    }

    pub(crate) fn handle_action(&mut self, action: AdminActions) {
        match action {
            AdminActions::OnDeviceCleared(remote_device) => self.on_device_cleared(&remote_device),
            AdminActions::OnDeviceFound(remote_device) => self.on_device_found(&remote_device),
            AdminActions::OnDeviceUuidChanged(remote_device) => {
                let new_uuids =
                    self.adapter.lock().unwrap().get_remote_uuids(remote_device.clone());
                self.on_device_uuid_changed(&remote_device, new_uuids);
            }
        }
    }
}

impl IBluetoothAdmin for BluetoothAdmin {
    fn is_service_allowed(&self, service: Uuid) -> bool {
        self.admin_helper.is_service_allowed(&service)
    }

    fn set_allowed_services(&mut self, services: Vec<Uuid>) -> bool {
        if !self.admin_helper.set_allowed_services(services) {
            // Allowlist is not changed.
            return true;
        }

        self.adapter.lock().unwrap().handle_admin_policy_changed(self.admin_helper.clone());
        self.bluetooth_media.lock().unwrap().handle_admin_policy_changed(self.admin_helper.clone());
        self.socket_manager.lock().unwrap().handle_admin_policy_changed(self.admin_helper.clone());

        if let Err(e) = self.write_config() {
            warn!("Admin: Failed to write config: {}", e);
        } else {
            info!("Admin: Write settings into {} successfully", &self.path);
        }

        let allowed_services = self.admin_helper.get_allowed_services();
        self.callbacks.for_all_callbacks(|cb| {
            cb.on_service_allowlist_changed(allowed_services.clone());
        });

        for (device, effect) in self.device_policy_affect_cache.clone().iter() {
            let uuids = self.adapter.lock().unwrap().get_remote_uuids(device.clone());
            let new_effect = self.new_device_policy_effect(Some(uuids));

            if new_effect.clone() != *effect {
                self.callbacks.for_all_callbacks(|cb| {
                    cb.on_device_policy_effect_changed(device.clone(), new_effect.clone())
                });
                self.device_policy_affect_cache.insert(device.clone(), new_effect.clone());
            }
        }

        true
    }

    fn get_allowed_services(&self) -> Vec<Uuid> {
        self.admin_helper.get_allowed_services()
    }

    fn get_device_policy_effect(&self, device: BluetoothDevice) -> Option<PolicyEffect> {
        if let Some(effect) = self.device_policy_affect_cache.get(&device) {
            effect.clone()
        } else {
            warn!("Device not found in cache");
            None
        }
    }

    fn register_admin_policy_callback(
        &mut self,
        callback: Box<dyn IBluetoothAdminPolicyCallback + Send>,
    ) -> u32 {
        self.callbacks.add_callback(callback)
    }

    fn unregister_admin_policy_callback(&mut self, callback_id: u32) -> bool {
        self.callbacks.remove_callback(callback_id)
    }
}

pub enum AdminActions {
    OnDeviceFound(BluetoothDevice),
    OnDeviceCleared(BluetoothDevice),
    OnDeviceUuidChanged(BluetoothDevice),
}

/// Handles the callbacks from Bluetooth Device
struct BluetoothDeviceCallbacks {
    tx: Sender<Message>,
}

impl BluetoothDeviceCallbacks {
    fn new(tx: Sender<Message>) -> Self {
        Self { tx }
    }
}

impl IBluetoothCallback for BluetoothDeviceCallbacks {
    fn on_device_properties_changed(
        &mut self,
        remote_device: BluetoothDevice,
        props: Vec<BtPropertyType>,
    ) {
        if props.contains(&BtPropertyType::Uuids) {
            let tx = self.tx.clone();
            tokio::spawn(async move {
                let _ = tx
                    .send(Message::AdminActions(AdminActions::OnDeviceUuidChanged(remote_device)))
                    .await;
            });
        }
    }

    fn on_device_found(&mut self, remote_device: BluetoothDevice) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ =
                tx.send(Message::AdminActions(AdminActions::OnDeviceFound(remote_device))).await;
        });
    }

    fn on_device_cleared(&mut self, remote_device: BluetoothDevice) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ =
                tx.send(Message::AdminActions(AdminActions::OnDeviceCleared(remote_device))).await;
        });
    }

    // Unused callbacks
    fn on_adapter_property_changed(&mut self, _prop: BtPropertyType) {}
    fn on_address_changed(&mut self, _addr: RawAddress) {}
    fn on_name_changed(&mut self, _name: String) {}
    fn on_discoverable_changed(&mut self, _discoverable: bool) {}
    fn on_device_key_missing(&mut self, remote_device: BluetoothDevice) {}
    fn on_discovering_changed(&mut self, _discovering: bool) {}
    fn on_ssp_request(
        &mut self,
        _remote_device: BluetoothDevice,
        _cod: u32,
        _variant: BtSspVariant,
        _passkey: u32,
    ) {
    }
    fn on_pin_request(&mut self, _remote_device: BluetoothDevice, _cod: u32, _min_16_digit: bool) {}
    fn on_pin_display(&mut self, _remote_device: BluetoothDevice, _pincode: String) {}
    fn on_bond_state_changed(&mut self, _status: u32, _device_address: RawAddress, _state: u32) {}
    fn on_sdp_search_complete(
        &mut self,
        _remote_device: BluetoothDevice,
        _searched_uuid: Uuid,
        _sdp_records: Vec<BtSdpRecord>,
    ) {
    }
    fn on_sdp_record_created(&mut self, _record: BtSdpRecord, _handle: i32) {}
}

impl RPCProxy for BluetoothDeviceCallbacks {
    fn get_object_id(&self) -> String {
        "BluetoothAdmin's Bluetooth Device Callback".to_string()
    }
}

#[cfg(test)]
mod tests {
    use crate::bluetooth_admin::{BluetoothAdmin, BluetoothAdminPolicyHelper};
    use bt_topshim::btif::Uuid;
    use serde_json::{json, Value};

    #[test]
    fn test_set_service_allowed() {
        let mut admin_helper = BluetoothAdminPolicyHelper::default();
        let uuid1: Uuid = [1; 16].into();
        let uuid2: Uuid = [2; 16].into();
        let uuid3: Uuid = [3; 16].into();
        let uuids = vec![uuid1, uuid2, uuid3];

        // Default admin allows everything
        assert!(admin_helper.is_service_allowed(&uuid1));
        assert!(admin_helper.is_service_allowed(&uuid2));
        assert!(admin_helper.is_service_allowed(&uuid3));
        assert_eq!(admin_helper.get_blocked_services(&uuids), Vec::<Uuid>::new());

        admin_helper.set_allowed_services(vec![uuid1, uuid3]);

        // Admin disallows uuid2 now
        assert!(admin_helper.is_service_allowed(&uuid1));
        assert!(!admin_helper.is_service_allowed(&uuid2));
        assert!(admin_helper.is_service_allowed(&uuid3));
        assert_eq!(admin_helper.get_blocked_services(&uuids), vec![uuid2]);

        admin_helper.set_allowed_services(vec![uuid2]);

        // Allowed services were overwritten.
        assert!(!admin_helper.is_service_allowed(&uuid1));
        assert!(admin_helper.is_service_allowed(&uuid2));
        assert!(!admin_helper.is_service_allowed(&uuid3));
        assert_eq!(admin_helper.get_blocked_services(&uuids), vec![uuid1, uuid3]);
    }

    fn get_sorted_allowed_services_from_config(
        admin_helper: &BluetoothAdminPolicyHelper,
    ) -> Vec<String> {
        let mut v = serde_json::from_str::<Value>(
            BluetoothAdmin::get_config_json_string(admin_helper.get_allowed_services()).as_str(),
        )
        .unwrap()
        .get("allowed_services")
        .unwrap()
        .as_array()
        .unwrap()
        .iter()
        .map(|v| String::from(v.as_str().unwrap()))
        .collect::<Vec<String>>();
        v.sort();
        v
    }

    fn get_sorted_allowed_services(admin_helper: &BluetoothAdminPolicyHelper) -> Vec<Uuid> {
        let mut v = admin_helper.get_allowed_services();
        v.sort_by(|lhs, rhs| lhs.uu.cmp(&rhs.uu));
        v
    }

    #[test]
    fn test_config() {
        let mut admin_helper = BluetoothAdminPolicyHelper::default();
        let a2dp_sink_str = "0000110b-0000-1000-8000-00805f9b34fb";
        let a2dp_source_str = "0000110a-0000-1000-8000-00805f9b34fb";

        let a2dp_sink_uuid = Uuid::from_string(a2dp_sink_str).unwrap();
        let a2dp_source_uuid = Uuid::from_string(a2dp_source_str).unwrap();

        let mut allowed_services_str = vec![a2dp_sink_str, a2dp_source_str];

        let mut allowed_services_uuid = vec![a2dp_sink_uuid, a2dp_source_uuid];

        allowed_services_str.sort();
        allowed_services_uuid.sort_by(|lhs, rhs| lhs.uu.cmp(&rhs.uu));

        // valid configuration
        assert_eq!(
            BluetoothAdmin::get_config_from_json(&json!({
                "allowed_services": allowed_services_str.clone()
            }))
            .map(|uuids| admin_helper.set_allowed_services(uuids)),
            Some(true)
        );
        assert_eq!(get_sorted_allowed_services(&admin_helper), allowed_services_uuid);
        assert_eq!(get_sorted_allowed_services_from_config(&admin_helper), allowed_services_str);

        // invalid configuration
        assert_eq!(
            BluetoothAdmin::get_config_from_json(&json!({ "allowed_services": a2dp_sink_str }))
                .map(|uuids| admin_helper.set_allowed_services(uuids)),
            None
        );
        // config should remain unchanged
        assert_eq!(get_sorted_allowed_services(&admin_helper), allowed_services_uuid);
        assert_eq!(get_sorted_allowed_services_from_config(&admin_helper), allowed_services_str);
    }
}
