use crate::battery_manager::{Battery, BatterySet};
use crate::battery_provider_manager::{
    BatteryProviderManager, IBatteryProviderCallback, IBatteryProviderManager,
};
use crate::bluetooth_gatt::{
    BluetoothGatt, BluetoothGattCharacteristic, BluetoothGattService, IBluetoothGatt,
    IBluetoothGattCallback,
};
use crate::callbacks::Callbacks;
use crate::{uuid, APIMessage, BluetoothAPI, Message, RPCProxy};
use bt_topshim::btif::{BtTransport, DisplayAddress, RawAddress, Uuid};
use bt_topshim::profiles::gatt::{GattStatus, LePhy};
use log::{debug, info};
use std::collections::HashMap;
use std::convert::TryInto;
use std::iter;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc::Sender;

/// The UUID corresponding to the BatteryLevel characteristic defined by the BatteryService
/// specification.
pub const CHARACTERISTIC_BATTERY_LEVEL: &str = "00002A1-9000-0100-0800-000805F9B34FB";

/// The app UUID BAS provides when connecting as a GATT client. Chosen at random.
/// TODO(b/233101174): make dynamic or decide on a static UUID
pub const BATTERY_SERVICE_GATT_CLIENT_APP_ID: &str = "e4d2acffcfaa42198f494606b7412117";

/// Represents the Floss BatteryService implementation.
pub struct BatteryService {
    gatt: Arc<Mutex<Box<BluetoothGatt>>>,
    battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
    battery_provider_id: u32,
    /// Sender for callback communication with the main thread.
    tx: Sender<Message>,
    /// Sender for callback communication with the api message thread.
    api_tx: Sender<APIMessage>,
    callbacks: Callbacks<dyn IBatteryServiceCallback + Send>,
    /// The GATT client ID needed for GATT calls.
    client_id: Option<i32>,
    /// Cached battery info keyed by remote device.
    battery_sets: HashMap<RawAddress, BatterySet>,
    /// Found handles for battery levels. Required for faster
    /// refreshes than initiating another search.
    handles: HashMap<RawAddress, i32>,
}

/// Enum for GATT callbacks to relay messages to the main processing thread. Newly supported
/// callbacks should add a corresponding entry here.
pub enum BatteryServiceActions {
    /// Params: status, client_id
    OnClientRegistered(GattStatus, i32),
    /// Params: status, client_id, connected, addr
    OnClientConnectionState(GattStatus, i32, bool, RawAddress),
    /// Params: addr, services, status
    OnSearchComplete(RawAddress, Vec<BluetoothGattService>, GattStatus),
    /// Params: addr, status, handle, value
    OnCharacteristicRead(RawAddress, GattStatus, i32, Vec<u8>),
    /// Params: addr, handle, value
    OnNotify(RawAddress, i32, Vec<u8>),
}

/// API for Floss implementation of the Bluetooth Battery Service (BAS). BAS is built on GATT and
/// this implementation wraps all of the GATT calls and handles tracking battery information for the
/// client.
pub trait IBatteryService {
    /// Registers a callback for interacting with BatteryService.
    fn register_callback(&mut self, callback: Box<dyn IBatteryServiceCallback + Send>) -> u32;

    /// Unregisters a callback.
    fn unregister_callback(&mut self, callback_id: u32);

    /// Returns the battery info of the remote device if available in BatteryService's cache.
    fn get_battery_info(&self, remote_address: RawAddress) -> Option<BatterySet>;

    /// Forces an explicit read of the device's battery level, including initiating battery level
    /// tracking if not yet performed.
    fn refresh_battery_info(&self, remote_address: RawAddress) -> bool;
}

/// Callback for interacting with BAS.
pub trait IBatteryServiceCallback: RPCProxy {
    /// Called when the status of BatteryService has changed. Trying to read from devices that do
    /// not support BAS will result in this method being called with BatteryServiceNotSupported.
    fn on_battery_service_status_updated(
        &mut self,
        remote_address: RawAddress,
        status: BatteryServiceStatus,
    );

    /// Invoked when battery level for a device has been changed due to notification.
    fn on_battery_info_updated(&mut self, remote_address: RawAddress, battery_info: BatterySet);
}

impl BatteryService {
    /// Construct a new BatteryService with callbacks relaying messages through tx.
    pub fn new(
        gatt: Arc<Mutex<Box<BluetoothGatt>>>,
        battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
        tx: Sender<Message>,
        api_tx: Sender<APIMessage>,
    ) -> BatteryService {
        let tx = tx.clone();
        let callbacks = Callbacks::new(tx.clone(), Message::BatteryServiceCallbackDisconnected);
        let client_id = None;
        let battery_sets = HashMap::new();
        let handles = HashMap::new();
        let battery_provider_id = battery_provider_manager
            .lock()
            .unwrap()
            .register_battery_provider(Box::new(BatteryProviderCallback::new(tx.clone())));
        Self {
            gatt,
            battery_provider_manager,
            battery_provider_id,
            tx,
            api_tx,
            callbacks,
            client_id,
            battery_sets,
            handles,
        }
    }

    /// Must be called after BluetoothGatt's init_profiles method has completed.
    pub fn init(&self) {
        debug!("Registering GATT client for BatteryService");
        self.gatt.lock().unwrap().register_client(
            String::from(BATTERY_SERVICE_GATT_CLIENT_APP_ID),
            Box::new(GattCallback::new(self.tx.clone(), self.api_tx.clone())),
            false,
        );
    }

    /// Handles all callback messages in a central location to avoid deadlocks.
    pub fn handle_action(&mut self, action: BatteryServiceActions) {
        match action {
            BatteryServiceActions::OnClientRegistered(_status, client_id) => {
                debug!("GATT client registered for BAS with id {}", client_id);
                self.client_id = Some(client_id);
            }

            BatteryServiceActions::OnClientConnectionState(status, _client_id, connected, addr) => {
                if !connected || status != GattStatus::Success {
                    info!(
                        "BAS: Dropping {} due to GATT state changed: connected={}, status={:?}",
                        DisplayAddress(&addr),
                        connected,
                        status
                    );
                    self.drop_device(addr);
                    return;
                }
                let client_id = match self.client_id {
                    Some(id) => id,
                    None => {
                        return;
                    }
                };
                self.gatt.lock().unwrap().discover_services(client_id, addr);
            }

            BatteryServiceActions::OnSearchComplete(addr, services, status) => {
                if status != GattStatus::Success {
                    info!(
                        "BAS: Service discovery for {} failed: status={:?}",
                        DisplayAddress(&addr),
                        status
                    );
                    self.drop_device(addr);
                    return;
                }

                let battery_level_char = match self.get_battery_level_char(addr, services) {
                    Ok(battery_level_char) => battery_level_char,
                    Err(status) => {
                        if let Some(BatteryServiceStatus::BatteryServiceNotSupported) = status {
                            self.callbacks.for_all_callbacks(|callback| {
                                callback.on_battery_service_status_updated(
                                    addr,
                                    BatteryServiceStatus::BatteryServiceNotSupported,
                                )
                            });
                        }
                        info!(
                            "BAS: Failed to get battery level char from {}: status={:?}",
                            DisplayAddress(&addr),
                            status
                        );
                        self.drop_device(addr);
                        return;
                    }
                };
                info!("BAS: Found battery level char from {}", DisplayAddress(&addr));
                let client_id = match self.client_id {
                    Some(id) => id,
                    None => {
                        self.drop_device(addr);
                        return;
                    }
                };

                let handle = battery_level_char.instance_id;
                self.handles.insert(addr, handle);

                // Try to register the battery level characteristic notification if the
                // remote server supports the CCCD.
                let cccd_uuid = Uuid::from_string(uuid::CCCD_UUID).unwrap();
                match battery_level_char
                    .descriptors
                    .iter()
                    .find(|descriptor| descriptor.uuid == cccd_uuid)
                {
                    Some(battery_level_char_cccd) => {
                        debug!(
                            "BAS: Found CCCD in battery level char from {}",
                            DisplayAddress(&addr)
                        );

                        // Configure the char to send notification.
                        let cccd_value = vec![1, 0];
                        self.gatt.lock().unwrap().write_descriptor(
                            client_id,
                            addr,
                            battery_level_char_cccd.instance_id,
                            0, // authentication none
                            cccd_value,
                        );
                    }
                    None => {
                        debug!(
                            "BAS: Device {} has no BatteryLevel characteristic CCCD",
                            DisplayAddress(&addr)
                        );
                    }
                };

                self.gatt.lock().unwrap().register_for_notification(client_id, addr, handle, true);

                if self.battery_sets.get(&addr).is_none() {
                    self.gatt.lock().unwrap().read_characteristic(client_id, addr, handle, 0);
                }
            }

            BatteryServiceActions::OnCharacteristicRead(addr, status, handle, value) => {
                if status != GattStatus::Success {
                    return;
                }
                match self.handles.get(&addr) {
                    Some(stored_handle) => {
                        if *stored_handle != handle {
                            return;
                        }
                    }
                    None => {
                        self.drop_device(addr);
                        return;
                    }
                }
                let battery_info = self.set_battery_info(&addr, &value);
                self.callbacks.for_all_callbacks(|callback| {
                    callback.on_battery_info_updated(addr, battery_info.clone());
                });
            }

            BatteryServiceActions::OnNotify(addr, _handle, value) => {
                let battery_info = self.set_battery_info(&addr, &value);
                self.callbacks.for_all_callbacks(|callback| {
                    callback.on_battery_info_updated(addr, battery_info.clone());
                });
            }
        }
    }

    fn set_battery_info(&mut self, remote_address: &RawAddress, value: &Vec<u8>) -> BatterySet {
        let level: Vec<_> = value.iter().cloned().chain(iter::repeat(0_u8)).take(4).collect();
        let level = u32::from_le_bytes(level.try_into().unwrap());
        debug!("BAS received battery level for {}: {}", DisplayAddress(remote_address), level);

        let battery_set = self.battery_sets.entry(*remote_address).or_insert_with(|| {
            BatterySet::new(
                *remote_address,
                uuid::BAS.to_string(),
                "BAS".to_string(),
                vec![Battery { percentage: level, variant: "".to_string() }],
            )
        });

        // or_insert_with does not update the value in the hash map if the key already exists.
        // Update the battery level if the battery set already exists.
        battery_set.batteries[0].percentage = level;

        self.battery_provider_manager
            .lock()
            .unwrap()
            .set_battery_info(self.battery_provider_id, battery_set.clone());
        battery_set.clone()
    }

    pub(crate) fn init_device(&self, remote_address: RawAddress) {
        let client_id = match self.client_id {
            Some(id) => id,
            None => return,
        };
        self.gatt.lock().unwrap().client_connect(
            client_id,
            remote_address,
            false,
            BtTransport::Le,
            false,
            LePhy::Phy1m,
        );
    }

    pub(crate) fn drop_device(&mut self, remote_address: RawAddress) {
        if self.handles.contains_key(&remote_address) {
            // Let BatteryProviderManager know that BAS no longer has a battery for this device.
            self.battery_provider_manager.lock().unwrap().remove_battery_info(
                self.battery_provider_id,
                remote_address,
                uuid::BAS.to_string(),
            );
        }
        self.battery_sets.remove(&remote_address);
        self.handles.remove(&remote_address);
        match self.client_id {
            Some(client_id) => {
                self.gatt.lock().unwrap().client_disconnect(client_id, remote_address);
            }
            None => (),
        }
    }

    fn get_battery_level_char(
        &mut self,
        remote_address: RawAddress,
        services: Vec<BluetoothGattService>,
    ) -> Result<BluetoothGattCharacteristic, Option<BatteryServiceStatus>> {
        let (bas_uuid, battery_level_uuid) =
            match (Uuid::from_string(uuid::BAS), Uuid::from_string(CHARACTERISTIC_BATTERY_LEVEL)) {
                (Some(bas_uuid), Some(battery_level_uuid)) => (bas_uuid, battery_level_uuid),
                _ => {
                    return Err(None);
                }
            };
        // TODO(b/233101174): handle multiple instances of BAS
        let bas = match services.iter().find(|service| service.uuid == bas_uuid) {
            Some(bas) => bas,
            None => return Err(Some(BatteryServiceStatus::BatteryServiceNotSupported)),
        };
        match bas
            .characteristics
            .iter()
            .find(|characteristic| characteristic.uuid == battery_level_uuid)
        {
            Some(battery_level) => Ok(battery_level.clone()),
            None => {
                debug!(
                    "Device {} has no BatteryLevel characteristic",
                    DisplayAddress(&remote_address)
                );
                Err(None)
            }
        }
    }

    /// Perform an explicit read on all devices BAS knows about.
    pub fn refresh_all_devices(&self) {
        self.handles.keys().for_each(|&addr| {
            self.refresh_device(addr);
        });
    }

    fn refresh_device(&self, remote_address: RawAddress) -> bool {
        let client_id = match self.client_id {
            Some(id) => id,
            None => return false,
        };
        let handle = match self.handles.get(&remote_address) {
            Some(id) => *id,
            None => return false,
        };
        self.gatt.lock().unwrap().read_characteristic(client_id, remote_address, handle, 0);
        true
    }

    /// Remove a callback due to disconnection or unregistration.
    pub fn remove_callback(&mut self, callback_id: u32) {
        self.callbacks.remove_callback(callback_id);
    }
}

/// Status enum for relaying the state of BAS or a particular device.
#[derive(Debug)]
pub enum BatteryServiceStatus {
    /// Device does not report support for BAS.
    BatteryServiceNotSupported,
}

impl IBatteryService for BatteryService {
    fn register_callback(&mut self, callback: Box<dyn IBatteryServiceCallback + Send>) -> u32 {
        self.callbacks.add_callback(callback)
    }

    fn unregister_callback(&mut self, callback_id: u32) {
        self.remove_callback(callback_id);
    }

    fn get_battery_info(&self, remote_address: RawAddress) -> Option<BatterySet> {
        self.battery_sets.get(&remote_address).cloned()
    }

    fn refresh_battery_info(&self, remote_address: RawAddress) -> bool {
        self.refresh_device(remote_address)
    }
}

struct BatteryProviderCallback {
    tx: Sender<Message>,
}

impl BatteryProviderCallback {
    fn new(tx: Sender<Message>) -> Self {
        Self { tx }
    }
}

impl IBatteryProviderCallback for BatteryProviderCallback {
    fn refresh_battery_info(&mut self) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ = tx.send(Message::BatteryServiceRefresh).await;
        });
    }
}

impl RPCProxy for BatteryProviderCallback {
    fn get_object_id(&self) -> String {
        "BAS BatteryProvider Callback".to_string()
    }
}

struct GattCallback {
    tx: Sender<Message>,
    api_tx: Sender<APIMessage>,
}

impl GattCallback {
    fn new(tx: Sender<Message>, api_tx: Sender<APIMessage>) -> Self {
        Self { tx, api_tx }
    }
}

impl IBluetoothGattCallback for GattCallback {
    // All callback methods relay messages through the stack receiver to allow BAS to operate on
    // requests serially. This reduces overall complexity including removing the need to share state
    // data with callbacks.

    fn on_client_registered(&mut self, status: GattStatus, client_id: i32) {
        let tx = self.tx.clone();
        let api_tx = self.api_tx.clone();
        tokio::spawn(async move {
            let _ = tx
                .send(Message::BatteryService(BatteryServiceActions::OnClientRegistered(
                    status, client_id,
                )))
                .await;
            let _ = api_tx.send(APIMessage::IsReady(BluetoothAPI::Battery)).await;
        });
    }

    fn on_client_connection_state(
        &mut self,
        status: GattStatus,
        client_id: i32,
        connected: bool,
        addr: RawAddress,
    ) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ = tx
                .send(Message::BatteryService(BatteryServiceActions::OnClientConnectionState(
                    status, client_id, connected, addr,
                )))
                .await;
        });
    }

    fn on_search_complete(
        &mut self,
        addr: RawAddress,
        services: Vec<BluetoothGattService>,
        status: GattStatus,
    ) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ = tx
                .send(Message::BatteryService(BatteryServiceActions::OnSearchComplete(
                    addr, services, status,
                )))
                .await;
        });
    }

    fn on_characteristic_read(
        &mut self,
        addr: RawAddress,
        status: GattStatus,
        handle: i32,
        value: Vec<u8>,
    ) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ = tx
                .send(Message::BatteryService(BatteryServiceActions::OnCharacteristicRead(
                    addr, status, handle, value,
                )))
                .await;
        });
    }

    fn on_notify(&mut self, addr: RawAddress, handle: i32, value: Vec<u8>) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ = tx
                .send(Message::BatteryService(BatteryServiceActions::OnNotify(addr, handle, value)))
                .await;
        });
    }

    fn on_phy_update(
        &mut self,
        _addr: RawAddress,
        _tx_phy: LePhy,
        _rx_phy: LePhy,
        _status: GattStatus,
    ) {
    }

    fn on_phy_read(
        &mut self,
        _addr: RawAddress,
        _tx_phy: LePhy,
        _rx_phy: LePhy,
        _status: GattStatus,
    ) {
    }

    fn on_characteristic_write(&mut self, _addr: RawAddress, _status: GattStatus, _handle: i32) {}

    fn on_execute_write(&mut self, _addr: RawAddress, _status: GattStatus) {}

    fn on_descriptor_read(
        &mut self,
        _addr: RawAddress,
        _status: GattStatus,
        _handle: i32,
        _value: Vec<u8>,
    ) {
    }

    fn on_descriptor_write(&mut self, _addr: RawAddress, _status: GattStatus, _handle: i32) {}

    fn on_read_remote_rssi(&mut self, _addr: RawAddress, _rssi: i32, _status: GattStatus) {}

    fn on_configure_mtu(&mut self, _addr: RawAddress, _mtu: i32, _status: GattStatus) {}

    fn on_connection_updated(
        &mut self,
        _addr: RawAddress,
        _interval: i32,
        _latency: i32,
        _timeout: i32,
        _status: GattStatus,
    ) {
    }

    fn on_service_changed(&mut self, _addr: RawAddress) {}
}

impl RPCProxy for GattCallback {
    fn get_object_id(&self) -> String {
        "BAS Gatt Callback".to_string()
    }
}
