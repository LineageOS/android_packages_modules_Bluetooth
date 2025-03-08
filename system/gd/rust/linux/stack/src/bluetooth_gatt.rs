//! Anything related to the GATT API (IBluetoothGatt).

use btif_macros::{btif_callback, btif_callbacks_dispatcher, log_cb_args};

use bt_topshim::btif::{
    BluetoothInterface, BtStatus, BtTransport, DisplayAddress, DisplayUuid, RawAddress, Uuid,
};
use bt_topshim::profiles::gatt::{
    AdvertisingStatus, AdvertisingTrackInfo, BtGattDbElement, BtGattNotifyParams, BtGattReadParams,
    BtGattResponse, BtGattValue, Gatt, GattAdvCallbacksDispatcher,
    GattAdvInbandCallbacksDispatcher, GattClientCallbacks, GattClientCallbacksDispatcher,
    GattScannerCallbacks, GattScannerCallbacksDispatcher, GattScannerInbandCallbacks,
    GattScannerInbandCallbacksDispatcher, GattServerCallbacks, GattServerCallbacksDispatcher,
    GattStatus, LePhy, MsftAdvMonitor, MsftAdvMonitorAddress, MsftAdvMonitorPattern,
};
use bt_topshim::sysprop;
use bt_utils::{adv_parser, array_utils};

use crate::bluetooth::{Bluetooth, BluetoothDevice};
use crate::bluetooth_adv::{
    AdvertiseData, AdvertiseManager, AdvertiserActions, AdvertisingSetParameters,
    BtifGattAdvCallbacks, IAdvertisingSetCallback, PeriodicAdvertisingParameters,
};
use crate::callbacks::Callbacks;
use crate::{make_message_dispatcher, APIMessage, BluetoothAPI, Message, RPCProxy, SuspendMode};
use log::{debug, error, info, warn};
use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::cast::{FromPrimitive, ToPrimitive};
use rand::rngs::SmallRng;
use rand::{RngCore, SeedableRng};
use std::collections::{HashMap, HashSet, VecDeque};
use std::convert::{TryFrom, TryInto};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc::Sender;

struct Client {
    id: Option<i32>,
    cbid: u32,
    uuid: Uuid,
    is_congested: bool,

    // Queued on_characteristic_write callback.
    congestion_queue: Vec<(RawAddress, GattStatus, i32)>,
}

struct Connection {
    conn_id: i32,
    address: RawAddress,

    // Connections are made to either a client or server
    client_id: i32,
    server_id: i32,
}

struct ContextMap {
    // TODO(b/196635530): Consider using `multimap` for a more efficient implementation of get by
    // multiple keys.
    callbacks: Callbacks<dyn IBluetoothGattCallback + Send>,
    clients: Vec<Client>,
    connections: Vec<Connection>,
}

type GattClientCallback = Box<dyn IBluetoothGattCallback + Send>;

impl ContextMap {
    fn new(tx: Sender<Message>) -> ContextMap {
        ContextMap {
            callbacks: Callbacks::new(tx, Message::GattClientCallbackDisconnected),
            clients: vec![],
            connections: vec![],
        }
    }

    fn get_by_uuid(&self, uuid: &Uuid) -> Option<&Client> {
        self.clients.iter().find(|client| client.uuid == *uuid)
    }

    fn get_by_client_id(&self, client_id: i32) -> Option<&Client> {
        self.clients.iter().find(|client| client.id.is_some() && client.id.unwrap() == client_id)
    }

    fn get_by_client_id_mut(&mut self, client_id: i32) -> Option<&mut Client> {
        self.clients
            .iter_mut()
            .find(|client| client.id.is_some() && client.id.unwrap() == client_id)
    }

    fn get_by_callback_id(&self, callback_id: u32) -> Option<&Client> {
        self.clients.iter().find(|client| client.cbid == callback_id)
    }

    fn get_address_by_conn_id(&self, conn_id: i32) -> Option<RawAddress> {
        self.connections.iter().find(|conn| conn.conn_id == conn_id).map(|conn| conn.address)
    }

    fn get_client_by_conn_id(&self, conn_id: i32) -> Option<&Client> {
        match self.connections.iter().find(|conn| conn.conn_id == conn_id) {
            None => None,
            Some(conn) => self.get_by_client_id(conn.client_id),
        }
    }

    fn get_client_by_conn_id_mut(&mut self, conn_id: i32) -> Option<&mut Client> {
        let client_id = match self.connections.iter().find(|conn| conn.conn_id == conn_id) {
            None => return None,
            Some(conn) => conn.client_id,
        };

        self.get_by_client_id_mut(client_id)
    }

    fn add(&mut self, uuid: &Uuid, callback: GattClientCallback) {
        if self.get_by_uuid(uuid).is_some() {
            return;
        }

        let cbid = self.callbacks.add_callback(callback);

        self.clients.push(Client {
            id: None,
            cbid,
            uuid: *uuid,
            is_congested: false,
            congestion_queue: vec![],
        });
    }

    fn remove(&mut self, id: i32) {
        // Remove any callbacks
        if let Some(c) = self.get_by_client_id(id) {
            let cbid = c.cbid;
            self.remove_callback(cbid);
        }

        self.clients.retain(|client| !(client.id.is_some() && client.id.unwrap() == id));
    }

    fn remove_callback(&mut self, callback_id: u32) {
        self.callbacks.remove_callback(callback_id);
    }

    fn set_client_id(&mut self, uuid: &Uuid, id: i32) {
        if let Some(client) = self.clients.iter_mut().find(|client| client.uuid == *uuid) {
            client.id = Some(id);
        }
    }

    fn add_connection(&mut self, client_id: i32, conn_id: i32, address: &RawAddress) {
        if self.get_conn_id_from_address(client_id, address).is_some() {
            return;
        }

        self.connections.push(Connection { conn_id, address: *address, client_id, server_id: 0 });
    }

    fn remove_connection(&mut self, _client_id: i32, conn_id: i32) {
        self.connections.retain(|conn| conn.conn_id != conn_id);
    }

    fn get_conn_id_from_address(&self, client_id: i32, address: &RawAddress) -> Option<i32> {
        self.connections
            .iter()
            .find(|conn| conn.client_id == client_id && conn.address == *address)
            .map(|conn| conn.conn_id)
    }

    fn get_client_ids_from_address(&self, address: &RawAddress) -> Vec<i32> {
        self.connections
            .iter()
            .filter(|conn| conn.address == *address)
            .map(|conn| conn.client_id)
            .collect()
    }

    fn get_connected_applications_from_address(&self, address: &RawAddress) -> Vec<Uuid> {
        self.get_client_ids_from_address(address)
            .into_iter()
            .filter_map(|id| self.get_by_client_id(id))
            .map(|client| client.uuid)
            .collect()
    }

    fn get_callback_from_callback_id(
        &mut self,
        callback_id: u32,
    ) -> Option<&mut GattClientCallback> {
        self.callbacks.get_by_id_mut(callback_id)
    }
}

struct Server {
    id: Option<i32>,
    cbid: u32,
    uuid: Uuid,
    services: Vec<BluetoothGattService>,
    is_congested: bool,

    // Queued on_notification_sent callback.
    congestion_queue: Vec<(RawAddress, GattStatus)>,
}

struct Request {
    id: i32,
    handle: i32,
}

struct ServerContextMap {
    // TODO(b/196635530): Consider using `multimap` for a more efficient implementation of get by
    // multiple keys.
    callbacks: Callbacks<dyn IBluetoothGattServerCallback + Send>,
    servers: Vec<Server>,
    connections: Vec<Connection>,
    requests: Vec<Request>,
}

type GattServerCallback = Box<dyn IBluetoothGattServerCallback + Send>;

impl ServerContextMap {
    fn new(tx: Sender<Message>) -> ServerContextMap {
        ServerContextMap {
            callbacks: Callbacks::new(tx, Message::GattServerCallbackDisconnected),
            servers: vec![],
            connections: vec![],
            requests: vec![],
        }
    }

    fn get_by_uuid(&self, uuid: &Uuid) -> Option<&Server> {
        self.servers.iter().find(|server| server.uuid == *uuid)
    }

    fn get_by_server_id(&self, server_id: i32) -> Option<&Server> {
        self.servers.iter().find(|server| server.id.map_or(false, |id| id == server_id))
    }

    fn get_mut_by_server_id(&mut self, server_id: i32) -> Option<&mut Server> {
        self.servers.iter_mut().find(|server| server.id.map_or(false, |id| id == server_id))
    }

    fn get_by_callback_id(&self, callback_id: u32) -> Option<&Server> {
        self.servers.iter().find(|server| server.cbid == callback_id)
    }

    fn get_by_conn_id(&self, conn_id: i32) -> Option<&Server> {
        self.connections
            .iter()
            .find(|conn| conn.conn_id == conn_id)
            .and_then(|conn| self.get_by_server_id(conn.server_id))
    }

    fn get_mut_by_conn_id(&mut self, conn_id: i32) -> Option<&mut Server> {
        self.connections
            .iter()
            .find_map(|conn| (conn.conn_id == conn_id).then_some(conn.server_id))
            .and_then(move |server_id| self.get_mut_by_server_id(server_id))
    }

    fn add(&mut self, uuid: &Uuid, callback: GattServerCallback) {
        if self.get_by_uuid(uuid).is_some() {
            return;
        }

        let cbid = self.callbacks.add_callback(callback);

        self.servers.push(Server {
            id: None,
            cbid,
            uuid: *uuid,
            services: vec![],
            is_congested: false,
            congestion_queue: vec![],
        });
    }

    fn remove(&mut self, id: i32) {
        // Remove any callbacks
        if let Some(cbid) = self.get_by_server_id(id).map(|server| server.cbid) {
            self.remove_callback(cbid);
        }

        self.servers.retain(|server| !(server.id.is_some() && server.id.unwrap() == id));
    }

    fn remove_callback(&mut self, callback_id: u32) {
        self.callbacks.remove_callback(callback_id);
    }

    fn set_server_id(&mut self, uuid: &Uuid, id: i32) {
        let server = self.servers.iter_mut().find(|server| server.uuid == *uuid);
        if let Some(s) = server {
            s.id = Some(id);
        }
    }

    fn get_callback_from_callback_id(
        &mut self,
        callback_id: u32,
    ) -> Option<&mut GattServerCallback> {
        self.callbacks.get_by_id_mut(callback_id)
    }

    fn add_connection(&mut self, server_id: i32, conn_id: i32, address: &RawAddress) {
        if self.get_conn_id_from_address(server_id, address).is_some() {
            return;
        }

        self.connections.push(Connection { conn_id, address: *address, client_id: 0, server_id });
    }

    fn remove_connection(&mut self, conn_id: i32) {
        self.connections.retain(|conn| conn.conn_id != conn_id);
    }

    fn get_conn_id_from_address(&self, server_id: i32, address: &RawAddress) -> Option<i32> {
        return self
            .connections
            .iter()
            .find(|conn| conn.server_id == server_id && conn.address == *address)
            .map(|conn| conn.conn_id);
    }

    fn get_server_ids_from_address(&self, address: &RawAddress) -> Vec<i32> {
        self.connections
            .iter()
            .filter(|conn| conn.address == *address)
            .map(|conn| conn.server_id)
            .collect()
    }

    fn get_address_from_conn_id(&self, conn_id: i32) -> Option<RawAddress> {
        self.connections.iter().find_map(|conn| (conn.conn_id == conn_id).then_some(conn.address))
    }

    fn add_service(&mut self, server_id: i32, service: BluetoothGattService) {
        if let Some(s) = self.get_mut_by_server_id(server_id) {
            s.services.push(service)
        }
    }

    fn delete_service(&mut self, server_id: i32, handle: i32) {
        if let Some(s) = self.get_mut_by_server_id(server_id) {
            s.services.retain(|service| service.instance_id != handle)
        }
    }

    fn add_request(&mut self, request_id: i32, handle: i32) {
        self.requests.push(Request { id: request_id, handle });
    }

    fn _delete_request(&mut self, request_id: i32) {
        self.requests.retain(|request| request.id != request_id);
    }

    fn get_request_handle_from_id(&self, request_id: i32) -> Option<i32> {
        self.requests
            .iter()
            .find_map(|request| (request.id == request_id).then_some(request.handle))
    }
}

/// Defines the GATT API.
// TODO(242083290): Split out interfaces.
pub trait IBluetoothGatt {
    // Scanning

    /// Returns whether LE Scan can be performed by hardware offload defined by
    /// [MSFT HCI Extension](https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/microsoft-defined-bluetooth-hci-commands-and-events).
    fn is_msft_supported(&self) -> bool;

    /// Registers an LE scanner callback.
    ///
    /// Returns the callback id.
    fn register_scanner_callback(&mut self, callback: Box<dyn IScannerCallback + Send>) -> u32;

    /// Unregisters an LE scanner callback identified by the given id.
    fn unregister_scanner_callback(&mut self, callback_id: u32) -> bool;

    /// Registers LE scanner.
    ///
    /// `callback_id`: The callback to receive updates about the scanner state.
    /// Returns the UUID of the registered scanner.
    fn register_scanner(&mut self, callback_id: u32) -> Uuid;

    /// Unregisters an LE scanner identified by the given scanner id.
    fn unregister_scanner(&mut self, scanner_id: u8) -> bool;

    /// Activate scan of the given scanner id.
    fn start_scan(
        &mut self,
        scanner_id: u8,
        settings: Option<ScanSettings>,
        filter: Option<ScanFilter>,
    ) -> BtStatus;

    /// Deactivate scan of the given scanner id.
    fn stop_scan(&mut self, scanner_id: u8) -> BtStatus;

    /// Returns the current suspend mode.
    fn get_scan_suspend_mode(&self) -> SuspendMode;

    // Advertising

    /// Registers callback for BLE advertising.
    fn register_advertiser_callback(
        &mut self,
        callback: Box<dyn IAdvertisingSetCallback + Send>,
    ) -> u32;

    /// Unregisters callback for BLE advertising.
    fn unregister_advertiser_callback(&mut self, callback_id: u32) -> bool;

    /// Creates a new BLE advertising set and start advertising.
    ///
    /// Returns the reg_id for the advertising set, which is used in the callback
    /// `on_advertising_set_started` to identify the advertising set started.
    ///
    /// * `parameters` - Advertising set parameters.
    /// * `advertise_data` - Advertisement data to be broadcasted.
    /// * `scan_response` - Scan response.
    /// * `periodic_parameters` - Periodic advertising parameters. If None, periodic advertising
    ///     will not be started.
    /// * `periodic_data` - Periodic advertising data.
    /// * `duration` - Advertising duration, in 10 ms unit. Valid range is from 1 (10 ms) to
    ///     65535 (655.35 sec). 0 means no advertising timeout.
    /// * `max_ext_adv_events` - Maximum number of extended advertising events the controller
    ///     shall attempt to send before terminating the extended advertising, even if the
    ///     duration has not expired. Valid range is from 1 to 255. 0 means event count limitation.
    /// * `callback_id` - Identifies callback registered in register_advertiser_callback.
    fn start_advertising_set(
        &mut self,
        parameters: AdvertisingSetParameters,
        advertise_data: AdvertiseData,
        scan_response: Option<AdvertiseData>,
        periodic_parameters: Option<PeriodicAdvertisingParameters>,
        periodic_data: Option<AdvertiseData>,
        duration: i32,
        max_ext_adv_events: i32,
        callback_id: u32,
    ) -> i32;

    /// Disposes a BLE advertising set.
    fn stop_advertising_set(&mut self, advertiser_id: i32);

    /// Queries address associated with the advertising set.
    fn get_own_address(&mut self, advertiser_id: i32);

    /// Enables or disables an advertising set.
    fn enable_advertising_set(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        duration: i32,
        max_ext_adv_events: i32,
    );

    /// Updates advertisement data of the advertising set.
    fn set_advertising_data(&mut self, advertiser_id: i32, data: AdvertiseData);

    /// Set the advertisement data of the advertising set.
    fn set_raw_adv_data(&mut self, advertiser_id: i32, data: Vec<u8>);

    /// Updates scan response of the advertising set.
    fn set_scan_response_data(&mut self, advertiser_id: i32, data: AdvertiseData);

    /// Updates advertising parameters of the advertising set.
    ///
    /// It must be called when advertising is not active.
    fn set_advertising_parameters(
        &mut self,
        advertiser_id: i32,
        parameters: AdvertisingSetParameters,
    );

    /// Updates periodic advertising parameters.
    fn set_periodic_advertising_parameters(
        &mut self,
        advertiser_id: i32,
        parameters: PeriodicAdvertisingParameters,
    );

    /// Updates periodic advertisement data.
    ///
    /// It must be called after `set_periodic_advertising_parameters`, or after
    /// advertising was started with periodic advertising data set.
    fn set_periodic_advertising_data(&mut self, advertiser_id: i32, data: AdvertiseData);

    /// Enables or disables periodic advertising.
    fn set_periodic_advertising_enable(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        include_adi: bool,
    );

    // GATT Client

    /// Registers a GATT Client.
    fn register_client(
        &mut self,
        app_uuid: String,
        callback: Box<dyn IBluetoothGattCallback + Send>,
        eatt_support: bool,
    );

    /// Unregisters a GATT Client.
    fn unregister_client(&mut self, client_id: i32);

    /// Initiates a GATT connection to a peer device.
    fn client_connect(
        &self,
        client_id: i32,
        addr: RawAddress,
        is_direct: bool,
        transport: BtTransport,
        opportunistic: bool,
        phy: LePhy,
    );

    /// Disconnects a GATT connection.
    fn client_disconnect(&self, client_id: i32, addr: RawAddress);

    /// Clears the attribute cache of a device.
    fn refresh_device(&self, client_id: i32, addr: RawAddress);

    /// Enumerates all GATT services on a connected device.
    fn discover_services(&self, client_id: i32, addr: RawAddress);

    /// Discovers all GATT services on a connected device. Only used by PTS.
    fn btif_gattc_discover_service_by_uuid(&self, client_id: i32, addr: RawAddress, uuid: String);

    /// Search a GATT service on a connected device based on a UUID.
    fn discover_service_by_uuid(&self, client_id: i32, addr: RawAddress, uuid: String);

    /// Reads a characteristic on a remote device.
    fn read_characteristic(&self, client_id: i32, addr: RawAddress, handle: i32, auth_req: i32);

    /// Reads a characteristic on a remote device.
    fn read_using_characteristic_uuid(
        &self,
        client_id: i32,
        addr: RawAddress,
        uuid: String,
        start_handle: i32,
        end_handle: i32,
        auth_req: i32,
    );

    /// Writes a remote characteristic.
    fn write_characteristic(
        &mut self,
        client_id: i32,
        addr: RawAddress,
        handle: i32,
        write_type: GattWriteType,
        auth_req: i32,
        value: Vec<u8>,
    ) -> GattWriteRequestStatus;

    /// Reads the descriptor for a given characteristic.
    fn read_descriptor(&self, client_id: i32, addr: RawAddress, handle: i32, auth_req: i32);

    /// Writes a remote descriptor for a given characteristic.
    fn write_descriptor(
        &self,
        client_id: i32,
        addr: RawAddress,
        handle: i32,
        auth_req: i32,
        value: Vec<u8>,
    );

    /// Registers to receive notifications or indications for a given characteristic.
    fn register_for_notification(
        &self,
        client_id: i32,
        addr: RawAddress,
        handle: i32,
        enable: bool,
    );

    /// Begins reliable write.
    fn begin_reliable_write(&mut self, client_id: i32, addr: RawAddress);

    /// Ends reliable write.
    fn end_reliable_write(&mut self, client_id: i32, addr: RawAddress, execute: bool);

    /// Requests RSSI for a given remote device.
    fn read_remote_rssi(&self, client_id: i32, addr: RawAddress);

    /// Configures the MTU of a given connection.
    fn configure_mtu(&self, client_id: i32, addr: RawAddress, mtu: i32);

    /// Requests a connection parameter update.
    /// This causes |on_connection_updated| to be called if there is already an existing
    /// connection to |addr|; Otherwise the method won't generate any callbacks.
    fn connection_parameter_update(
        &self,
        client_id: i32,
        addr: RawAddress,
        min_interval: i32,
        max_interval: i32,
        latency: i32,
        timeout: i32,
        min_ce_len: u16,
        max_ce_len: u16,
    );

    /// Sets preferred PHY.
    fn client_set_preferred_phy(
        &self,
        client_id: i32,
        addr: RawAddress,
        tx_phy: LePhy,
        rx_phy: LePhy,
        phy_options: i32,
    );

    /// Reads the PHY used by a peer.
    fn client_read_phy(&mut self, client_id: i32, addr: RawAddress);

    // GATT Server

    /// Registers a GATT Server.
    fn register_server(
        &mut self,
        app_uuid: String,
        callback: Box<dyn IBluetoothGattServerCallback + Send>,
        eatt_support: bool,
    );

    /// Unregisters a GATT Server.
    fn unregister_server(&mut self, server_id: i32);

    /// Initiates a GATT connection to the server.
    fn server_connect(
        &self,
        server_id: i32,
        addr: RawAddress,
        is_direct: bool,
        transport: BtTransport,
    ) -> bool;

    /// Disconnects the server GATT connection.
    fn server_disconnect(&self, server_id: i32, addr: RawAddress) -> bool;

    /// Adds a service to the GATT server.
    fn add_service(&self, server_id: i32, service: BluetoothGattService);

    /// Removes a service from the GATT server.
    fn remove_service(&self, server_id: i32, handle: i32);

    /// Clears all services from the GATT server.
    fn clear_services(&self, server_id: i32);

    /// Sends a response to a read/write operation.
    fn send_response(
        &self,
        server_id: i32,
        addr: RawAddress,
        request_id: i32,
        status: GattStatus,
        offset: i32,
        value: Vec<u8>,
    ) -> bool;

    /// Sends a notification to a remote device.
    fn send_notification(
        &self,
        server_id: i32,
        addr: RawAddress,
        handle: i32,
        confirm: bool,
        value: Vec<u8>,
    ) -> bool;

    /// Sets preferred PHY.
    fn server_set_preferred_phy(
        &self,
        server_id: i32,
        addr: RawAddress,
        tx_phy: LePhy,
        rx_phy: LePhy,
        phy_options: i32,
    );

    /// Reads the PHY used by a peer.
    fn server_read_phy(&self, server_id: i32, addr: RawAddress);
}

#[derive(Debug, Default, Clone)]
/// Represents a GATT Descriptor.
pub struct BluetoothGattDescriptor {
    pub uuid: Uuid,
    pub instance_id: i32,
    pub permissions: i32,
}

impl BluetoothGattDescriptor {
    pub fn new(uuid: Uuid, instance_id: i32, permissions: i32) -> BluetoothGattDescriptor {
        BluetoothGattDescriptor { uuid, instance_id, permissions }
    }
}

#[derive(Debug, Default, Clone)]
/// Represents a GATT Characteristic.
pub struct BluetoothGattCharacteristic {
    pub uuid: Uuid,
    pub instance_id: i32,
    pub properties: i32,
    pub permissions: i32,
    pub key_size: i32,
    pub write_type: GattWriteType,
    pub descriptors: Vec<BluetoothGattDescriptor>,
}

impl BluetoothGattCharacteristic {
    // Properties are u8 but i32 in these apis.
    pub const PROPERTY_BROADCAST: i32 = 1 << 0;
    pub const PROPERTY_READ: i32 = 1 << 1;
    pub const PROPERTY_WRITE_NO_RESPONSE: i32 = 1 << 2;
    pub const PROPERTY_WRITE: i32 = 1 << 3;
    pub const PROPERTY_NOTIFY: i32 = 1 << 4;
    pub const PROPERTY_INDICATE: i32 = 1 << 5;
    pub const PROPERTY_SIGNED_WRITE: i32 = 1 << 6;
    pub const PROPERTY_EXTENDED_PROPS: i32 = 1 << 7;

    // Permissions are u16 but i32 in these apis.
    pub const PERMISSION_READ: i32 = 1 << 0;
    pub const PERMISSION_READ_ENCRYPTED: i32 = 1 << 1;
    pub const PERMISSION_READ_ENCRYPED_MITM: i32 = 1 << 2;
    pub const PERMISSION_WRITE: i32 = 1 << 4;
    pub const PERMISSION_WRITE_ENCRYPTED: i32 = 1 << 5;
    pub const PERMISSION_WRITE_ENCRYPTED_MITM: i32 = 1 << 6;
    pub const PERMISSION_WRITE_SIGNED: i32 = 1 << 7;
    pub const PERMISSION_WRITE_SIGNED_MITM: i32 = 1 << 8;

    pub fn new(
        uuid: Uuid,
        instance_id: i32,
        properties: i32,
        permissions: i32,
    ) -> BluetoothGattCharacteristic {
        BluetoothGattCharacteristic {
            uuid,
            instance_id,
            properties,
            permissions,
            write_type: if properties & BluetoothGattCharacteristic::PROPERTY_WRITE_NO_RESPONSE != 0
            {
                GattWriteType::WriteNoRsp
            } else {
                GattWriteType::Write
            },
            key_size: 16,
            descriptors: vec![],
        }
    }
}

#[derive(Debug, Default, Clone)]
/// Represents a GATT Service.
pub struct BluetoothGattService {
    pub uuid: Uuid,
    pub instance_id: i32,
    pub service_type: i32,
    pub characteristics: Vec<BluetoothGattCharacteristic>,
    pub included_services: Vec<BluetoothGattService>,
}

impl BluetoothGattService {
    pub fn new(uuid: Uuid, instance_id: i32, service_type: i32) -> BluetoothGattService {
        BluetoothGattService {
            uuid,
            instance_id,
            service_type,
            characteristics: vec![],
            included_services: vec![],
        }
    }

    fn from_db(
        elements: Vec<BtGattDbElement>,
        with_included_service: bool,
    ) -> Vec<BluetoothGattService> {
        let mut db_out: Vec<BluetoothGattService> = vec![];

        for elem in elements {
            match GattDbElementType::from_u32(elem.type_).unwrap() {
                GattDbElementType::PrimaryService | GattDbElementType::SecondaryService => {
                    db_out.push(BluetoothGattService::new(
                        elem.uuid,
                        elem.attribute_handle as i32,
                        elem.type_ as i32,
                    ));
                    // TODO(b/200065274): Mark restricted services.
                }

                GattDbElementType::Characteristic => {
                    match db_out.last_mut() {
                        Some(s) => s.characteristics.push(BluetoothGattCharacteristic::new(
                            elem.uuid,
                            elem.attribute_handle as i32,
                            elem.properties as i32,
                            elem.permissions as i32,
                        )),
                        None => {
                            // TODO(b/193685325): Log error.
                        }
                    }
                    // TODO(b/200065274): Mark restricted characteristics.
                }

                GattDbElementType::Descriptor => {
                    match db_out.last_mut() {
                        Some(s) => match s.characteristics.last_mut() {
                            Some(c) => c.descriptors.push(BluetoothGattDescriptor::new(
                                elem.uuid,
                                elem.attribute_handle as i32,
                                elem.permissions as i32,
                            )),
                            None => {
                                // TODO(b/193685325): Log error.
                            }
                        },
                        None => {
                            // TODO(b/193685325): Log error.
                        }
                    }
                    // TODO(b/200065274): Mark restricted descriptors.
                }

                GattDbElementType::IncludedService => {
                    if !with_included_service {
                        continue;
                    }
                    match db_out.last_mut() {
                        Some(s) => {
                            s.included_services.push(BluetoothGattService::new(
                                elem.uuid,
                                elem.attribute_handle as i32,
                                elem.type_ as i32,
                            ));
                        }
                        None => {
                            // TODO(b/193685325): Log error.
                        }
                    }
                }
            }
        }

        db_out
    }

    fn into_db(
        service: BluetoothGattService,
        services: &Vec<BluetoothGattService>,
    ) -> Vec<BtGattDbElement> {
        let mut db_out: Vec<BtGattDbElement> = vec![];
        db_out.push(BtGattDbElement {
            id: service.instance_id as u16,
            uuid: service.uuid,
            type_: service.service_type as u32,
            attribute_handle: service.instance_id as u16,
            start_handle: service.instance_id as u16,
            end_handle: 0,
            properties: 0,
            extended_properties: 0,
            permissions: 0,
        });

        for char in service.characteristics {
            db_out.push(BtGattDbElement {
                id: char.instance_id as u16,
                uuid: char.uuid,
                type_: GattDbElementType::Characteristic as u32,
                attribute_handle: char.instance_id as u16,
                start_handle: 0,
                end_handle: 0,
                properties: char.properties as u8,
                extended_properties: 0,
                permissions: (((char.key_size - 7) << 12) + char.permissions) as u16,
            });

            for desc in char.descriptors {
                db_out.push(BtGattDbElement {
                    id: desc.instance_id as u16,
                    uuid: desc.uuid,
                    type_: GattDbElementType::Descriptor as u32,
                    attribute_handle: desc.instance_id as u16,
                    start_handle: 0,
                    end_handle: 0,
                    properties: 0,
                    extended_properties: 0,
                    permissions: (((char.key_size - 7) << 12) + desc.permissions) as u16,
                });
            }
        }

        for included_service in service.included_services {
            if !services.iter().any(|s| {
                s.instance_id == included_service.instance_id && s.uuid == included_service.uuid
            }) {
                log::error!(
                    "Included service with uuid {} not found",
                    DisplayUuid(&included_service.uuid)
                );
                continue;
            }

            db_out.push(BtGattDbElement {
                id: included_service.instance_id as u16,
                uuid: included_service.uuid,
                type_: included_service.service_type as u32,
                attribute_handle: included_service.instance_id as u16,
                start_handle: 0,
                end_handle: 0,
                properties: 0,
                extended_properties: 0,
                permissions: 0,
            });
        }

        // Set end handle of primary/secondary attribute to last element's handle
        if let Some(elem) = db_out.last() {
            db_out[0].end_handle = elem.attribute_handle;
        }

        db_out
    }
}

/// Callback for GATT Client API.
pub trait IBluetoothGattCallback: RPCProxy {
    /// When the `register_client` request is done.
    fn on_client_registered(&mut self, _status: GattStatus, _client_id: i32);

    /// When there is a change in the state of a GATT client connection.
    fn on_client_connection_state(
        &mut self,
        _status: GattStatus,
        _client_id: i32,
        _connected: bool,
        _addr: RawAddress,
    );

    /// When there is a change of PHY.
    fn on_phy_update(
        &mut self,
        _addr: RawAddress,
        _tx_phy: LePhy,
        _rx_phy: LePhy,
        _status: GattStatus,
    );

    /// The completion of IBluetoothGatt::read_phy.
    fn on_phy_read(
        &mut self,
        _addr: RawAddress,
        _tx_phy: LePhy,
        _rx_phy: LePhy,
        _status: GattStatus,
    );

    /// When GATT db is available.
    fn on_search_complete(
        &mut self,
        _addr: RawAddress,
        _services: Vec<BluetoothGattService>,
        _status: GattStatus,
    );

    /// The completion of IBluetoothGatt::read_characteristic.
    fn on_characteristic_read(
        &mut self,
        _addr: RawAddress,
        _status: GattStatus,
        _handle: i32,
        _value: Vec<u8>,
    );

    /// The completion of IBluetoothGatt::write_characteristic.
    fn on_characteristic_write(&mut self, _addr: RawAddress, _status: GattStatus, _handle: i32);

    /// When a reliable write is completed.
    fn on_execute_write(&mut self, _addr: RawAddress, _status: GattStatus);

    /// The completion of IBluetoothGatt::read_descriptor.
    fn on_descriptor_read(
        &mut self,
        _addr: RawAddress,
        _status: GattStatus,
        _handle: i32,
        _value: Vec<u8>,
    );

    /// The completion of IBluetoothGatt::write_descriptor.
    fn on_descriptor_write(&mut self, _addr: RawAddress, _status: GattStatus, _handle: i32);

    /// When notification or indication is received.
    fn on_notify(&mut self, _addr: RawAddress, _handle: i32, _value: Vec<u8>);

    /// The completion of IBluetoothGatt::read_remote_rssi.
    fn on_read_remote_rssi(&mut self, _addr: RawAddress, _rssi: i32, _status: GattStatus);

    /// The completion of IBluetoothGatt::configure_mtu.
    fn on_configure_mtu(&mut self, _addr: RawAddress, _mtu: i32, _status: GattStatus);

    /// When a connection parameter changes.
    fn on_connection_updated(
        &mut self,
        _addr: RawAddress,
        _interval: i32,
        _latency: i32,
        _timeout: i32,
        _status: GattStatus,
    );

    /// When there is an addition, removal, or change of a GATT service.
    fn on_service_changed(&mut self, _addr: RawAddress);
}

/// Callback for GATT Server API.
pub trait IBluetoothGattServerCallback: RPCProxy {
    /// When the `register_server` request is done.
    fn on_server_registered(&mut self, _status: GattStatus, _server_id: i32);

    /// When there is a change in the state of a GATT server connection.
    fn on_server_connection_state(&mut self, _server_id: i32, _connected: bool, _addr: RawAddress);

    /// When there is a service added to the GATT server.
    fn on_service_added(&mut self, _status: GattStatus, _service: BluetoothGattService);

    /// When a service has been removed from the GATT server.
    fn on_service_removed(&mut self, status: GattStatus, handle: i32);

    /// When a remote device has requested to read a characteristic.
    fn on_characteristic_read_request(
        &mut self,
        _addr: RawAddress,
        _trans_id: i32,
        _offset: i32,
        _is_long: bool,
        _handle: i32,
    );

    /// When a remote device has requested to read a descriptor.
    fn on_descriptor_read_request(
        &mut self,
        _addr: RawAddress,
        _trans_id: i32,
        _offset: i32,
        _is_long: bool,
        _handle: i32,
    );

    /// When a remote device has requested to write to a characteristic.
    fn on_characteristic_write_request(
        &mut self,
        _addr: RawAddress,
        _trans_id: i32,
        _offset: i32,
        _len: i32,
        _is_prep: bool,
        _need_rsp: bool,
        _handle: i32,
        _value: Vec<u8>,
    );

    /// When a remote device has requested to write to a descriptor.
    fn on_descriptor_write_request(
        &mut self,
        _addr: RawAddress,
        _trans_id: i32,
        _offset: i32,
        _len: i32,
        _is_prep: bool,
        _need_rsp: bool,
        _handle: i32,
        _value: Vec<u8>,
    );

    /// When a previously prepared write is to be executed.
    fn on_execute_write(&mut self, _addr: RawAddress, _trans_id: i32, _exec_write: bool);

    /// When a notification or indication has been sent to a remote device.
    fn on_notification_sent(&mut self, _addr: RawAddress, _status: GattStatus);

    /// When the MTU for a given connection changes
    fn on_mtu_changed(&mut self, addr: RawAddress, mtu: i32);

    /// When there is a change of PHY.
    fn on_phy_update(&mut self, addr: RawAddress, tx_phy: LePhy, rx_phy: LePhy, status: GattStatus);

    /// The completion of IBluetoothGatt::server_read_phy.
    fn on_phy_read(&mut self, addr: RawAddress, tx_phy: LePhy, rx_phy: LePhy, status: GattStatus);

    /// When the connection parameters for a given connection changes.
    fn on_connection_updated(
        &mut self,
        addr: RawAddress,
        interval: i32,
        latency: i32,
        timeout: i32,
        status: GattStatus,
    );

    /// When the subrate change event for a given connection is received.
    fn on_subrate_change(
        &mut self,
        addr: RawAddress,
        subrate_factor: i32,
        latency: i32,
        cont_num: i32,
        timeout: i32,
        status: GattStatus,
    );
}

/// Interface for scanner callbacks to clients, passed to
/// `IBluetoothGatt::register_scanner_callback`.
pub trait IScannerCallback: RPCProxy {
    /// When the `register_scanner` request is done.
    fn on_scanner_registered(&mut self, uuid: Uuid, scanner_id: u8, status: GattStatus);

    /// When an LE advertisement matching aggregate filters is detected. This callback is shared
    /// among all scanner callbacks and is triggered for *every* advertisement that the controller
    /// receives. For listening to the beginning and end of a specific scanner's advertisements
    /// detected while in RSSI range, use on_advertisement_found and on_advertisement_lost below.
    fn on_scan_result(&mut self, scan_result: ScanResult);

    /// When an LE advertisement matching aggregate filters is found. The criteria of
    /// how a device is considered found is specified by ScanFilter.
    fn on_advertisement_found(&mut self, scanner_id: u8, scan_result: ScanResult);

    /// When an LE advertisement matching aggregate filters is no longer detected. The criteria of
    /// how a device is considered lost is specified by ScanFilter.
    // TODO(b/269343922): Rename this to on_advertisement_lost for symmetry with
    // on_advertisement_found.
    fn on_advertisement_lost(&mut self, scanner_id: u8, scan_result: ScanResult);

    /// When LE Scan module changes suspend mode due to system suspend/resume.
    fn on_suspend_mode_change(&mut self, suspend_mode: SuspendMode);
}

#[derive(Debug, FromPrimitive, ToPrimitive)]
#[repr(u8)]
/// GATT write type.
pub enum GattDbElementType {
    PrimaryService = 0,
    SecondaryService = 1,
    IncludedService = 2,
    Characteristic = 3,
    Descriptor = 4,
}

impl From<GattDbElementType> for i32 {
    fn from(val: GattDbElementType) -> Self {
        val.to_u8().unwrap_or(0).into()
    }
}

#[derive(Debug, Default, FromPrimitive, ToPrimitive, Copy, Clone)]
#[repr(u8)]
/// GATT write type.
pub enum GattWriteType {
    Invalid = 0,
    WriteNoRsp = 1,
    #[default]
    Write = 2,
    WritePrepare = 3,
}

#[derive(Debug, Default, FromPrimitive, ToPrimitive, Clone, PartialEq)]
#[repr(u32)]
/// Scan type configuration.
pub enum ScanType {
    #[default]
    Active = 0,
    Passive = 1,
}

/// Represents scanning configurations to be passed to `IBluetoothGatt::start_scan`.
///
/// This configuration is general and supported on all Bluetooth hardware, irrelevant of the
/// hardware filter offload (APCF or MSFT).
#[derive(Debug, Clone)]
pub struct ScanSettings {
    pub interval: i32,
    pub window: i32,
    pub scan_type: ScanType,
}

impl ScanSettings {
    fn extract_scan_parameters(&self) -> Option<(u8, u16, u16)> {
        let scan_type = match self.scan_type {
            ScanType::Passive => 0x00,
            ScanType::Active => 0x01,
        };
        let interval = match u16::try_from(self.interval) {
            Ok(i) => i,
            Err(e) => {
                println!("Invalid scan interval {}: {}", self.interval, e);
                return None;
            }
        };
        let window = match u16::try_from(self.window) {
            Ok(w) => w,
            Err(e) => {
                println!("Invalid scan window {}: {}", self.window, e);
                return None;
            }
        };
        Some((scan_type, interval, window))
    }
}

/// Represents scan result
#[derive(Debug)]
pub struct ScanResult {
    pub name: String,
    pub address: RawAddress,
    pub addr_type: u8,
    pub event_type: u16,
    pub primary_phy: u8,
    pub secondary_phy: u8,
    pub advertising_sid: u8,
    pub tx_power: i8,
    pub rssi: i8,
    pub periodic_adv_int: u16,
    pub flags: u8,
    pub service_uuids: Vec<Uuid>,
    /// A map of 128-bit UUID and its corresponding service data.
    pub service_data: HashMap<String, Vec<u8>>,
    pub manufacturer_data: HashMap<u16, Vec<u8>>,
    pub adv_data: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct ScanFilterPattern {
    /// Specifies the starting byte position of the pattern immediately following AD Type.
    pub start_position: u8,

    /// Advertising Data type (https://www.bluetooth.com/specifications/assigned-numbers/).
    pub ad_type: u8,

    /// The pattern to be matched for the specified AD Type within the advertisement packet from
    /// the specified starting byte.
    pub content: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct ScanFilterAddress {
    pub addr_type: u8,
    pub bd_addr: RawAddress,
}

#[derive(Debug, Clone)]
#[repr(u8)]
pub enum ScanFilterConditionType {
    /// [MSFT HCI Extension](https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/microsoft-defined-bluetooth-hci-commands-and-events).
    MsftConditionTypeAll = 0x0,
    MsftConditionTypePatterns = 0x1,
    MsftConditionTypeUuid = 0x2,
    MsftConditionTypeIrkResolution = 0x3,
    MsftConditionTypeAddress = 0x4,
}

/// Represents the condition for matching advertisements.
///
/// Only pattern-based matching is implemented.
#[derive(Debug, Clone)]
pub enum ScanFilterCondition {
    /// All advertisements are matched.
    All,

    /// Match by pattern anywhere in the advertisement data. Multiple patterns are "OR"-ed.
    Patterns(Vec<ScanFilterPattern>),

    /// Match by UUID (not implemented).
    Uuid,

    /// Match if the IRK resolves an advertisement (not implemented).
    Irk,

    /// Match by Bluetooth address (not implemented).
    BluetoothAddress(ScanFilterAddress),
}

/// Represents a scan filter to be passed to `IBluetoothGatt::start_scan`.
///
/// This filter is intentionally modelled close to the MSFT hardware offload filter.
/// Reference:
/// https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/microsoft-defined-bluetooth-hci-commands-and-events
#[derive(Debug, Clone)]
pub struct ScanFilter {
    /// Advertisements with RSSI above or equal this value is considered "found".
    pub rssi_high_threshold: u8,

    /// Advertisements with RSSI below or equal this value (for a period of rssi_low_timeout) is
    /// considered "lost".
    pub rssi_low_threshold: u8,

    /// The time in seconds over which the RSSI value should be below rssi_low_threshold before
    /// being considered "lost".
    pub rssi_low_timeout: u8,

    /// The sampling interval in milliseconds.
    pub rssi_sampling_period: u8,

    /// The condition to match advertisements with.
    pub condition: ScanFilterCondition,
}

pub enum GattActions {
    /// Used when we need to invoke an update in this layer e.g. when there's no active connection,
    /// where LibBluetooth accepts the conn_parameter_update call but won't send out a callback.
    /// Params: client_id, address, interval, latency, timeout, status
    InvokeConnectionUpdated(i32, RawAddress, i32, i32, i32, GattStatus),
    /// This disconnects all server and client connections to the device.
    /// Params: remote_device
    Disconnect(BluetoothDevice),
}

enum MsftCommandQueue {
    Add(u8, ScanFilter), // (scanner_id, new_monitor)
    Remove(u8),          // (monitor_handle)
}

#[derive(Debug)]
enum MsftCommandPending {
    NoPending,
    Aborted(u8, ScanFilter), // The pending command is an |Add| and it must be remove.
    Add(u8, ScanFilter),     // (scanner_id, new_monitor)
    Remove(u8),              // (monitor_handle)
    Enable(bool),            // (enabled)
}

#[derive(PartialEq)]
enum ControllerScanType {
    NotScanning,
    ActiveScan,
    PassiveScan,
}

/// Implementation of the GATT API (IBluetoothGatt).
pub struct BluetoothGatt {
    gatt: Arc<Mutex<Gatt>>,
    tx: Sender<Message>,

    context_map: ContextMap,
    server_context_map: ServerContextMap,
    reliable_queue: HashSet<RawAddress>,
    write_characteristic_permits: HashMap<RawAddress, Option<i32>>,
    scanner_callbacks: Callbacks<dyn IScannerCallback + Send>,
    scanners: HashMap<Uuid, ScannerInfo>,

    // Bookmarking the controller state could help avoid the redundant calls and error logs.
    controller_scan_type: ControllerScanType, // The controller LE scan type.
    msft_enabled: bool,                       // The controller MSFT state.

    // The MSFT commands that need to be dispatched by |msft_run_queue_and_update_scan|.
    // After the queue is emptied, |msft_run_queue_and_update_scan| would take care of the MSFT
    // enable state, standard LE scan enable state, and standard LE scan parameters.
    msft_command_queue: VecDeque<MsftCommandQueue>,
    // The MSFT command that LibBluetooth is currently processing.
    // This is only set by |msft_run_queue_and_update_scan| and reset by the MSFT callback handlers.
    msft_command_pending: MsftCommandPending,

    scan_suspend_mode: SuspendMode,
    adv_manager: AdvertiseManager,

    // Used for generating random UUIDs. SmallRng is chosen because it is fast, don't use this for
    // cryptography.
    small_rng: SmallRng,

    enabled: bool,
}

impl BluetoothGatt {
    /// Constructs a new IBluetoothGatt implementation.
    pub fn new(intf: Arc<Mutex<BluetoothInterface>>, tx: Sender<Message>) -> BluetoothGatt {
        BluetoothGatt {
            gatt: Arc::new(Mutex::new(Gatt::new(&intf.lock().unwrap()))),
            tx: tx.clone(),
            context_map: ContextMap::new(tx.clone()),
            server_context_map: ServerContextMap::new(tx.clone()),
            reliable_queue: HashSet::new(),
            write_characteristic_permits: HashMap::new(),
            scanner_callbacks: Callbacks::new(tx.clone(), Message::ScannerCallbackDisconnected),
            scanners: HashMap::new(),
            controller_scan_type: ControllerScanType::NotScanning,
            msft_enabled: false,
            msft_command_queue: VecDeque::new(),
            msft_command_pending: MsftCommandPending::NoPending,
            scan_suspend_mode: SuspendMode::Normal,
            small_rng: SmallRng::from_entropy(),
            adv_manager: AdvertiseManager::new(tx.clone()),
            enabled: false,
        }
    }

    pub fn init_profiles(&mut self, api_tx: Sender<APIMessage>) {
        let gatt_client_callbacks_dispatcher = GattClientCallbacksDispatcher {
            dispatch: make_message_dispatcher(self.tx.clone(), Message::GattClient),
        };
        let gatt_server_callbacks_dispatcher = GattServerCallbacksDispatcher {
            dispatch: make_message_dispatcher(self.tx.clone(), Message::GattServer),
        };
        let gatt_scanner_callbacks_dispatcher = GattScannerCallbacksDispatcher {
            dispatch: make_message_dispatcher(self.tx.clone(), Message::LeScanner),
        };
        let gatt_scanner_inband_callbacks_dispatcher = GattScannerInbandCallbacksDispatcher {
            dispatch: make_message_dispatcher(self.tx.clone(), Message::LeScannerInband),
        };
        let gatt_adv_inband_callbacks_dispatcher = GattAdvInbandCallbacksDispatcher {
            dispatch: make_message_dispatcher(self.tx.clone(), Message::LeAdvInband),
        };
        let gatt_adv_callbacks_dispatcher = GattAdvCallbacksDispatcher {
            dispatch: make_message_dispatcher(self.tx.clone(), Message::LeAdv),
        };

        self.gatt.lock().unwrap().initialize(
            gatt_client_callbacks_dispatcher,
            gatt_server_callbacks_dispatcher,
            gatt_scanner_callbacks_dispatcher,
            gatt_scanner_inband_callbacks_dispatcher,
            gatt_adv_inband_callbacks_dispatcher,
            gatt_adv_callbacks_dispatcher,
        );

        tokio::spawn(async move {
            let _ = api_tx.send(APIMessage::IsReady(BluetoothAPI::Gatt)).await;
        });
    }

    /// Initializes the AdvertiseManager
    /// This needs to be called after Bluetooth is ready because we need to query LE features.
    pub(crate) fn init_adv_manager(&mut self, adapter: Arc<Mutex<Box<Bluetooth>>>) {
        self.adv_manager.initialize(self.gatt.clone(), adapter);
    }

    pub fn enable(&mut self, enabled: bool) {
        self.enabled = enabled;
    }

    /// Remove a scanner callback and unregisters all scanners associated with that callback.
    pub fn remove_scanner_callback(&mut self, callback_id: u32) -> bool {
        let affected_scanner_ids: Vec<u8> = self
            .scanners
            .iter()
            .filter(|(_uuid, scanner)| scanner.callback_id == callback_id)
            .filter_map(|(_uuid, scanner)| scanner.scanner_id)
            .collect();

        // All scanners associated with the callback must be also unregistered.
        for scanner_id in affected_scanner_ids {
            self.unregister_scanner(scanner_id);
        }

        self.scanner_callbacks.remove_callback(callback_id)
    }

    /// Set the suspend mode.
    pub fn set_scan_suspend_mode(&mut self, suspend_mode: SuspendMode) {
        if suspend_mode != self.scan_suspend_mode {
            self.scan_suspend_mode = suspend_mode.clone();

            // Notify current suspend mode to all active callbacks.
            self.scanner_callbacks.for_all_callbacks(|callback| {
                callback.on_suspend_mode_change(suspend_mode.clone());
            });
        }
    }

    /// Enters suspend mode for LE Scan.
    ///
    /// This "pauses" all operations managed by this module to prepare for system suspend. A
    /// callback is triggered to let clients know that this module is in suspend mode and some
    /// subsequent API calls will be blocked in this mode.
    pub fn scan_enter_suspend(&mut self) -> BtStatus {
        if self.get_scan_suspend_mode() != SuspendMode::Normal {
            return BtStatus::Busy;
        }
        self.set_scan_suspend_mode(SuspendMode::Suspending);

        let scanners_to_suspend = self
            .scanners
            .iter()
            .filter_map(
                |(_uuid, scanner)| if scanner.is_enabled { scanner.scanner_id } else { None },
            )
            .collect::<Vec<_>>();
        // Note: We can't simply disable the LE scanning. When a filter is offloaded
        // with the MSFT extension and it is monitoring a device, it sends a
        // `Monitor Device Event` to indicate that monitoring is stopped and this
        // can cause an early wake-up. Until we fix the disable + mask solution, we
        // must remove all monitors before suspend and re-monitor them on resume.
        for scanner_id in scanners_to_suspend {
            self.stop_scan(scanner_id);
            if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
                scanner.is_suspended = true;
            }
        }
        self.set_scan_suspend_mode(SuspendMode::Suspended);
        BtStatus::Success
    }

    /// Exits suspend mode for LE Scan.
    ///
    /// To be called after system resume/wake up. This "unpauses" the operations that were "paused"
    /// due to suspend. A callback is triggered to let clients when this module has exited suspend
    /// mode.
    pub fn scan_exit_suspend(&mut self) -> BtStatus {
        if self.get_scan_suspend_mode() != SuspendMode::Suspended {
            return BtStatus::Busy;
        }
        self.set_scan_suspend_mode(SuspendMode::Resuming);

        let gatt = &mut self.gatt;
        self.scanners.retain(|_uuid, scanner| {
            if let (true, Some(scanner_id)) = (scanner.is_unregistered, scanner.scanner_id) {
                gatt.lock().unwrap().scanner.unregister(scanner_id);
            }
            !scanner.is_unregistered
        });

        let scanners_to_resume = self
            .scanners
            .iter()
            .filter_map(
                |(_uuid, scanner)| if scanner.is_suspended { scanner.scanner_id } else { None },
            )
            .collect::<Vec<_>>();
        for scanner_id in scanners_to_resume {
            let status = self.resume_scan(scanner_id);
            if status != BtStatus::Success {
                error!("Failed to resume scanner {}, status={:?}", scanner_id, status);
            }
            if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
                scanner.is_suspended = false;
            }
        }

        self.set_scan_suspend_mode(SuspendMode::Normal);

        BtStatus::Success
    }

    fn find_scanner_by_id(&mut self, scanner_id: u8) -> Option<&mut ScannerInfo> {
        self.scanners.values_mut().find(|scanner| scanner.scanner_id == Some(scanner_id))
    }

    /// Updates the scan state depending on the states of registered scanners:
    /// 1. Scan is started if there is at least 1 enabled scanner.
    /// 2. If there is an enabled ScanType::Active scanner, prefer its scan settings.
    ///    Otherwise, adopt the settings from any of the enabled scanners.
    fn update_scan(&mut self, force_restart: bool) {
        let mut new_controller_scan_type = ControllerScanType::NotScanning;
        let mut enabled_scanner_id = None;
        let mut enabled_scan_param = None;
        for scanner in self.scanners.values() {
            if !scanner.is_enabled {
                continue;
            }
            new_controller_scan_type = ControllerScanType::PassiveScan;
            if let Some(ss) = &scanner.scan_settings {
                enabled_scanner_id = scanner.scanner_id;
                enabled_scan_param = ss.extract_scan_parameters();
                if ss.scan_type == ScanType::Active {
                    new_controller_scan_type = ControllerScanType::ActiveScan;
                    break;
                }
            }
        }

        if new_controller_scan_type == self.controller_scan_type && !force_restart {
            return;
        }
        // The scan type changed. We should either stop or restart.

        // First, stop if we are currently scanning.
        if self.controller_scan_type != ControllerScanType::NotScanning {
            self.gatt.lock().unwrap().scanner.stop_scan();
        }

        self.controller_scan_type = new_controller_scan_type;
        // If new state is not to scan, then we're done.
        if self.controller_scan_type == ControllerScanType::NotScanning {
            return;
        }

        // Update parameters and start scan.
        if let (Some(scanner_id), Some((scan_type, scan_interval, scan_window))) =
            (enabled_scanner_id, enabled_scan_param)
        {
            self.gatt.lock().unwrap().scanner.set_scan_parameters(
                scan_type,
                scanner_id,
                scan_interval,
                scan_window,
                0,
                0,
                0,
                1,
            );
        } else {
            error!("Starting scan without settings");
        }
        self.gatt.lock().unwrap().scanner.start_scan();
    }

    /// Consumes the MSFT command queue and updates scan at the end.
    ///
    /// "Update scan" includes:
    /// 1. Configure the MSFT enable state
    /// 2. Configure the standard scan parameter and enable state
    ///    - Note that the scan filter policy is automatically selected by LibBluetooth based on the
    ///      MSFT enable state. In Rust layer we only need to restart scan. See:
    ///      system/main/shim/le_scanning_manager.cc BleScannerInterfaceImpl::OnMsftAdvMonitorEnable
    fn msft_run_queue_and_update_scan(&mut self) {
        match &self.msft_command_pending {
            &MsftCommandPending::NoPending => {}
            _ => {
                // If there's already a pending command then just return and wait. After the pending
                // command is done, the MSFT callbacks would reset |self.msft_command_pending| and
                // call this.
                return;
            }
        }
        match self.msft_command_queue.pop_front() {
            None => {
                // The queue is emptied. Configure the MSFT enable and update scan.
                let has_enabled_scanner = self.scanners.values().any(|s| s.is_enabled);
                let has_enabled_unfiltered_scanner =
                    self.scanners.values().any(|s| s.is_enabled && s.filter.is_none());
                let should_enable_msft = has_enabled_scanner && !has_enabled_unfiltered_scanner;
                if should_enable_msft != self.msft_enabled {
                    self.gatt.lock().unwrap().scanner.msft_adv_monitor_enable(should_enable_msft);
                    self.msft_command_pending = MsftCommandPending::Enable(should_enable_msft);
                    // The standard scan will be force updated in MsftAdvMonitorEnableCallback.
                } else {
                    self.update_scan(false); // No need to force restart as MSFT is unchanged.
                }
            }
            Some(MsftCommandQueue::Add(scanner_id, monitor)) => {
                self.gatt.lock().unwrap().scanner.msft_adv_monitor_add(&(&monitor).into());
                self.msft_command_pending = MsftCommandPending::Add(scanner_id, monitor);
            }
            Some(MsftCommandQueue::Remove(monitor_handle)) => {
                self.gatt.lock().unwrap().scanner.msft_adv_monitor_remove(monitor_handle);
                self.msft_command_pending = MsftCommandPending::Remove(monitor_handle);
            }
        }
    }

    fn msft_abort_add_commands_by_scanner_id(&mut self, scanner_id: u8) {
        // For the commands that have not yet dispatched to LibBluetooth, simply remove.
        self.msft_command_queue.retain(|cmd| {
            if let MsftCommandQueue::Add(id, _) = cmd {
                if scanner_id == *id {
                    return false;
                }
            }
            true
        });
        // If the pending command matches the target scanner ID, set Abort flag.
        if let MsftCommandPending::Add(id, monitor) = &self.msft_command_pending {
            if scanner_id == *id {
                self.msft_command_pending = MsftCommandPending::Aborted(*id, monitor.clone());
            }
        }
    }

    fn msft_drain_all_handles_by_scanner_id(&mut self, scanner_id: u8) -> Vec<u8> {
        let mut handles: Vec<u8> = vec![];
        if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
            if let Some(handle) = scanner.monitor_handle.take() {
                handles.push(handle);
            }
            for (_addr, handle) in scanner.addr_handle_map.drain() {
                if let Some(h) = handle {
                    handles.push(h);
                }
            }
        } else {
            warn!("msft_drain_all_handles_by_scanner_id: Scanner {} not found", scanner_id);
        }
        handles
    }

    /// The resume_scan method is used to resume scanning after system suspension.
    /// It assumes that scanner.filter has already had the filter data.
    fn resume_scan(&mut self, scanner_id: u8) -> BtStatus {
        if !self.enabled {
            return BtStatus::UnexpectedState;
        }

        if self.get_scan_suspend_mode() != SuspendMode::Resuming {
            return BtStatus::Busy;
        }

        let filter = {
            if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
                if scanner.is_suspended {
                    scanner.is_suspended = false;
                    scanner.is_enabled = true;
                    // When a scanner resumes from a suspended state, the
                    // scanner.filter has already had the filter data.
                    scanner.filter.clone()
                } else {
                    warn!("This Scanner {} is supposed to resume from suspended state", scanner_id);
                    return BtStatus::UnexpectedState;
                }
            } else {
                warn!("Scanner {} not found", scanner_id);
                return BtStatus::Fail;
            }
        };

        if self.is_msft_supported() {
            if let Some(filter) = filter {
                self.msft_command_queue.push_back(MsftCommandQueue::Add(scanner_id, filter));
            }
            self.msft_run_queue_and_update_scan();
        } else {
            self.update_scan(false); // No need to force restart as MSFT is not supported.
        }
        BtStatus::Success
    }

    fn on_address_monitor_added(
        &mut self,
        scanner_id: u8,
        monitor_handle: u8,
        scan_filter: &ScanFilter,
    ) {
        if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
            // After hci complete event is received, update the monitor_handle.
            // The address monitor handles are needed in stop_scan().
            let addr_info: MsftAdvMonitorAddress = (&scan_filter.condition).into();

            if let std::collections::hash_map::Entry::Occupied(mut e) =
                scanner.addr_handle_map.entry(addr_info.bd_addr)
            {
                e.insert(Some(monitor_handle));
                debug!(
                    "Added adv addr monitor {} and updated bd_addr={} to addr filter map",
                    monitor_handle,
                    DisplayAddress(&addr_info.bd_addr)
                );
                return;
            } else {
                error!(
                    "on_child_monitor_added: bd_addr {} has been removed, removing the addr monitor {}.",
                    DisplayAddress(&addr_info.bd_addr),
                    monitor_handle
                );
            }
        } else {
            error!(
                "on_child_monitor_added: scanner has been removed, removing the addr monitor {}",
                monitor_handle
            );
        }
        self.msft_command_queue.push_back(MsftCommandQueue::Remove(monitor_handle));
    }

    fn on_pattern_monitor_added(&mut self, scanner_id: u8, monitor_handle: u8) {
        if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
            debug!("Added adv pattern monitor handle = {}", monitor_handle);
            scanner.monitor_handle = Some(monitor_handle);
        } else {
            error!(
                "on_pattern_monitor_added: scanner has been removed, removing the addr monitor {}",
                monitor_handle
            );
            self.msft_command_queue.push_back(MsftCommandQueue::Remove(monitor_handle));
        }
    }

    /// Remove an adv_manager callback and unregisters all advertising sets associated with that callback.
    pub fn remove_adv_callback(&mut self, callback_id: u32) -> bool {
        self.adv_manager.get_impl().unregister_callback(callback_id)
    }

    pub fn remove_client_callback(&mut self, callback_id: u32) {
        // Unregister client if client id exists.
        if let Some(client) = self.context_map.get_by_callback_id(callback_id) {
            if let Some(id) = client.id {
                self.unregister_client(id);
            }
        }

        // Always remove callback.
        self.context_map.remove_callback(callback_id);
    }

    pub fn remove_server_callback(&mut self, callback_id: u32) {
        // Unregister server if server id exists.
        if let Some(server) = self.server_context_map.get_by_callback_id(callback_id) {
            if let Some(id) = server.id {
                self.unregister_server(id);
            }
        }

        // Always remove callback.
        self.context_map.remove_callback(callback_id);
    }

    /// Enters suspend mode for LE advertising.
    pub fn advertising_enter_suspend(&mut self) {
        self.adv_manager.get_impl().enter_suspend()
    }

    /// Exits suspend mode for LE advertising.
    pub fn advertising_exit_suspend(&mut self) {
        self.adv_manager.get_impl().exit_suspend()
    }

    /// Start an active scan on given scanner id. This will look up and assign
    /// the correct ScanSettings for it as well.
    pub(crate) fn start_active_scan(&mut self, scanner_id: u8) -> BtStatus {
        let settings = ScanSettings {
            interval: sysprop::get_i32(sysprop::PropertyI32::LeInquiryScanInterval),
            window: sysprop::get_i32(sysprop::PropertyI32::LeInquiryScanWindow),
            scan_type: ScanType::Active,
        };

        self.start_scan(scanner_id, Some(settings), /*filter=*/ None)
    }

    pub(crate) fn stop_active_scan(&mut self, scanner_id: u8) -> BtStatus {
        self.stop_scan(scanner_id)
    }

    pub fn handle_action(&mut self, action: GattActions) {
        match action {
            GattActions::InvokeConnectionUpdated(
                client_id,
                addr,
                interval,
                latency,
                timeout,
                status,
            ) => {
                if let Some(client) = self.context_map.get_by_client_id(client_id) {
                    if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
                        cb.on_connection_updated(addr, interval, latency, timeout, status);
                    }
                }
            }
            GattActions::Disconnect(device) => {
                for client_id in self.context_map.get_client_ids_from_address(&device.address) {
                    if let Some(conn_id) =
                        self.context_map.get_conn_id_from_address(client_id, &device.address)
                    {
                        self.gatt.lock().unwrap().client.disconnect(
                            client_id,
                            &device.address,
                            conn_id,
                        );
                    }
                }
                for server_id in
                    self.server_context_map.get_server_ids_from_address(&device.address)
                {
                    if let Some(conn_id) =
                        self.server_context_map.get_conn_id_from_address(server_id, &device.address)
                    {
                        self.gatt.lock().unwrap().server.disconnect(
                            server_id,
                            &device.address,
                            conn_id,
                        );
                    }
                }
            }
        }
    }

    pub fn handle_adv_action(&mut self, action: AdvertiserActions) {
        self.adv_manager.get_impl().handle_action(action);
    }

    pub(crate) fn get_connected_applications(&self, device_address: &RawAddress) -> Vec<Uuid> {
        self.context_map.get_connected_applications_from_address(device_address)
    }
}

#[derive(Debug, FromPrimitive, ToPrimitive)]
#[repr(u8)]
/// Status of WriteCharacteristic methods.
pub enum GattWriteRequestStatus {
    Success = 0,
    Fail = 1,
    Busy = 2,
}

// This structure keeps track of the lifecycle of a scanner.
#[derive(Debug)]
struct ScannerInfo {
    // The callback to which events about this scanner needs to be sent to.
    // Another purpose of keeping track of the callback id is that when a callback is disconnected
    // or unregistered we need to also unregister all scanners associated with that callback to
    // prevent dangling unowned scanners.
    callback_id: u32,
    // If the scanner is registered successfully, this contains the scanner id, otherwise None.
    scanner_id: Option<u8>,
    // If one of scanners is enabled, we scan.
    is_enabled: bool,
    // Scan filter.
    filter: Option<ScanFilter>,
    // Adv monitor handle, if exists.
    monitor_handle: Option<u8>,
    // If suspended then we need to resume it on exit_suspend.
    is_suspended: bool,
    /// Whether the unregistration of the scanner is held.
    /// This flag is set when a scanner is unregistered while we're not able to do it, such as:
    /// - The system is suspending / suspended
    ///
    /// The scanner would be unregistered after the system exits the suspended state.
    is_unregistered: bool,
    // The scan parameters to use
    scan_settings: Option<ScanSettings>,
    // Whether the MSFT extension monitor tracking by address filter quirk will be used.
    addr_tracking_quirk: bool,
    // Stores all the monitored handles for pattern and address.
    addr_handle_map: HashMap<RawAddress, Option<u8>>,
}

impl ScannerInfo {
    fn new(callback_id: u32) -> Self {
        Self {
            callback_id,
            scanner_id: None,
            is_enabled: false,
            filter: None,
            monitor_handle: None,
            is_suspended: false,
            is_unregistered: false,
            scan_settings: None,
            addr_tracking_quirk: sysprop::get_bool(sysprop::PropertyBool::LeAdvMonRtlQuirk),
            addr_handle_map: HashMap::new(),
        }
    }
}

impl From<&ScanFilterPattern> for MsftAdvMonitorPattern {
    fn from(val: &ScanFilterPattern) -> Self {
        MsftAdvMonitorPattern {
            ad_type: val.ad_type,
            start_byte: val.start_position,
            pattern: val.content.clone(),
        }
    }
}

impl From<&ScanFilterCondition> for Vec<MsftAdvMonitorPattern> {
    fn from(val: &ScanFilterCondition) -> Self {
        match val {
            ScanFilterCondition::Patterns(patterns) => {
                patterns.iter().map(|pattern| pattern.into()).collect()
            }
            _ => vec![],
        }
    }
}

impl From<&ScanFilterAddress> for MsftAdvMonitorAddress {
    fn from(val: &ScanFilterAddress) -> Self {
        MsftAdvMonitorAddress { addr_type: val.addr_type, bd_addr: val.bd_addr }
    }
}

impl From<&ScanFilterCondition> for MsftAdvMonitorAddress {
    fn from(val: &ScanFilterCondition) -> Self {
        match &val {
            ScanFilterCondition::BluetoothAddress(addr_info) => addr_info.into(),
            _ => MsftAdvMonitorAddress { addr_type: 0, bd_addr: RawAddress::empty() },
        }
    }
}

impl From<&ScanFilter> for MsftAdvMonitor {
    fn from(val: &ScanFilter) -> Self {
        let scan_filter_condition_type = match val.condition {
            ScanFilterCondition::Patterns(_) => {
                ScanFilterConditionType::MsftConditionTypePatterns as u8
            }
            ScanFilterCondition::BluetoothAddress(_) => {
                ScanFilterConditionType::MsftConditionTypeAddress as u8
            }
            _ => ScanFilterConditionType::MsftConditionTypeAll as u8,
        };
        MsftAdvMonitor {
            rssi_high_threshold: val.rssi_high_threshold.try_into().unwrap(),
            rssi_low_threshold: val.rssi_low_threshold.try_into().unwrap(),
            rssi_low_timeout: val.rssi_low_timeout.try_into().unwrap(),
            rssi_sampling_period: val.rssi_sampling_period.try_into().unwrap(),
            condition_type: scan_filter_condition_type,
            patterns: (&val.condition).into(),
            addr_info: (&val.condition).into(),
        }
    }
}

impl IBluetoothGatt for BluetoothGatt {
    fn is_msft_supported(&self) -> bool {
        self.gatt.lock().unwrap().scanner.is_msft_supported()
    }

    fn register_scanner_callback(&mut self, callback: Box<dyn IScannerCallback + Send>) -> u32 {
        self.scanner_callbacks.add_callback(callback)
    }

    fn unregister_scanner_callback(&mut self, callback_id: u32) -> bool {
        self.remove_scanner_callback(callback_id)
    }

    fn register_scanner(&mut self, callback_id: u32) -> Uuid {
        if !self.enabled {
            return Uuid::empty();
        }

        let mut bytes: [u8; 16] = [0; 16];
        self.small_rng.fill_bytes(&mut bytes);
        let uuid = Uuid::from(bytes);

        self.scanners.insert(uuid, ScannerInfo::new(callback_id));

        // libbluetooth's register_scanner takes a UUID of the scanning application. This UUID does
        // not correspond to higher level concept of "application" so we use random UUID that
        // functions as a unique identifier of the scanner.
        self.gatt.lock().unwrap().scanner.register_scanner(uuid);

        uuid
    }

    fn unregister_scanner(&mut self, scanner_id: u8) -> bool {
        if self.get_scan_suspend_mode() != SuspendMode::Normal {
            if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
                info!("Deferred scanner unregistration due to suspending");
                scanner.is_unregistered = true;
                return true;
            } else {
                warn!("Scanner {} not found", scanner_id);
                return false;
            }
        }

        self.gatt.lock().unwrap().scanner.unregister(scanner_id);

        // The unregistered scanner must also be stopped.
        self.stop_scan(scanner_id);

        self.scanners.retain(|_uuid, scanner| scanner.scanner_id != Some(scanner_id));

        true
    }

    fn start_scan(
        &mut self,
        scanner_id: u8,
        settings: Option<ScanSettings>,
        filter: Option<ScanFilter>,
    ) -> BtStatus {
        if !self.enabled {
            return BtStatus::UnexpectedState;
        }

        if self.get_scan_suspend_mode() != SuspendMode::Normal {
            return BtStatus::Busy;
        }

        // If the client is not specifying scan settings, the default one will be used.
        let settings = settings.unwrap_or_else(|| {
            // Offloaded filtering + Active scan doesn't work correctly on some QCA chips - It
            // behaves like "Filter policy: Accept all advertisement" and impacts the power
            // consumption. Thus, we by default select Passive scan if the quirk is on and the
            // filter is set.
            // OTOH the clients are still allowed to explicitly set the scan type Active, so in case
            // the scan response data is necessary this quirk will not cause any functionality
            // breakage.
            let scan_type =
                if sysprop::get_bool(sysprop::PropertyBool::LeAdvMonQcaQuirk) && filter.is_some() {
                    ScanType::Passive
                } else {
                    ScanType::default()
                };
            ScanSettings {
                interval: sysprop::get_i32(sysprop::PropertyI32::LeAdvMonScanInterval),
                window: sysprop::get_i32(sysprop::PropertyI32::LeAdvMonScanWindow),
                scan_type,
            }
        });

        // Multiplexing scanners happens at this layer. The implementations of start_scan
        // and stop_scan maintains the state of all registered scanners and based on the states
        // update the scanning and/or filter states of libbluetooth.
        if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
            scanner.is_enabled = true;
            scanner.filter = filter.clone();
            scanner.scan_settings = Some(settings);
        } else {
            warn!("start_scan: Scanner {} not found", scanner_id);
            return BtStatus::Fail;
        }

        if self.is_msft_supported() {
            // Remove all pending filters
            self.msft_abort_add_commands_by_scanner_id(scanner_id);
            // Remove all existing filters
            for handle in self.msft_drain_all_handles_by_scanner_id(scanner_id).into_iter() {
                self.msft_command_queue.push_back(MsftCommandQueue::Remove(handle));
            }
            // Add the new filter
            if let Some(filter) = filter {
                self.msft_command_queue.push_back(MsftCommandQueue::Add(scanner_id, filter));
            }
            self.msft_run_queue_and_update_scan();
        } else {
            self.update_scan(false); // No need to force restart as MSFT is not supported.
        }
        BtStatus::Success
    }

    fn stop_scan(&mut self, scanner_id: u8) -> BtStatus {
        if !self.enabled {
            return BtStatus::UnexpectedState;
        }

        let scan_suspend_mode = self.get_scan_suspend_mode();
        if scan_suspend_mode != SuspendMode::Normal && scan_suspend_mode != SuspendMode::Suspending
        {
            return BtStatus::Busy;
        }

        if let Some(scanner) = self.find_scanner_by_id(scanner_id) {
            scanner.is_enabled = false;
        } else {
            warn!("stop_scan: Scanner {} not found", scanner_id);
            // Clients can assume success of the removal since the scanner does not exist.
            return BtStatus::Success;
        }

        if self.is_msft_supported() {
            // Remove all pending filters
            self.msft_abort_add_commands_by_scanner_id(scanner_id);
            // Remove all existing filters
            for handle in self.msft_drain_all_handles_by_scanner_id(scanner_id).into_iter() {
                self.msft_command_queue.push_back(MsftCommandQueue::Remove(handle));
            }
            self.msft_run_queue_and_update_scan();
        } else {
            self.update_scan(false); // No need to force restart as MSFT is not supported.
        }

        BtStatus::Success
    }

    fn get_scan_suspend_mode(&self) -> SuspendMode {
        self.scan_suspend_mode.clone()
    }

    // Advertising

    fn register_advertiser_callback(
        &mut self,
        callback: Box<dyn IAdvertisingSetCallback + Send>,
    ) -> u32 {
        self.adv_manager.get_impl().register_callback(callback)
    }

    fn unregister_advertiser_callback(&mut self, callback_id: u32) -> bool {
        self.adv_manager.get_impl().unregister_callback(callback_id)
    }

    fn start_advertising_set(
        &mut self,
        parameters: AdvertisingSetParameters,
        advertise_data: AdvertiseData,
        scan_response: Option<AdvertiseData>,
        periodic_parameters: Option<PeriodicAdvertisingParameters>,
        periodic_data: Option<AdvertiseData>,
        duration: i32,
        max_ext_adv_events: i32,
        callback_id: u32,
    ) -> i32 {
        self.adv_manager.get_impl().start_advertising_set(
            parameters,
            advertise_data,
            scan_response,
            periodic_parameters,
            periodic_data,
            duration,
            max_ext_adv_events,
            callback_id,
        )
    }

    fn stop_advertising_set(&mut self, advertiser_id: i32) {
        self.adv_manager.get_impl().stop_advertising_set(advertiser_id)
    }

    fn get_own_address(&mut self, advertiser_id: i32) {
        self.adv_manager.get_impl().get_own_address(advertiser_id);
    }

    fn enable_advertising_set(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        duration: i32,
        max_ext_adv_events: i32,
    ) {
        self.adv_manager.get_impl().enable_advertising_set(
            advertiser_id,
            enable,
            duration,
            max_ext_adv_events,
        );
    }

    fn set_advertising_data(&mut self, advertiser_id: i32, data: AdvertiseData) {
        self.adv_manager.get_impl().set_advertising_data(advertiser_id, data);
    }

    fn set_raw_adv_data(&mut self, advertiser_id: i32, data: Vec<u8>) {
        self.adv_manager.get_impl().set_raw_adv_data(advertiser_id, data);
    }

    fn set_scan_response_data(&mut self, advertiser_id: i32, data: AdvertiseData) {
        self.adv_manager.get_impl().set_scan_response_data(advertiser_id, data);
    }

    fn set_advertising_parameters(
        &mut self,
        advertiser_id: i32,
        parameters: AdvertisingSetParameters,
    ) {
        self.adv_manager.get_impl().set_advertising_parameters(advertiser_id, parameters);
    }

    fn set_periodic_advertising_parameters(
        &mut self,
        advertiser_id: i32,
        parameters: PeriodicAdvertisingParameters,
    ) {
        self.adv_manager.get_impl().set_periodic_advertising_parameters(advertiser_id, parameters);
    }

    fn set_periodic_advertising_data(&mut self, advertiser_id: i32, data: AdvertiseData) {
        self.adv_manager.get_impl().set_periodic_advertising_data(advertiser_id, data);
    }

    fn set_periodic_advertising_enable(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        include_adi: bool,
    ) {
        self.adv_manager.get_impl().set_periodic_advertising_enable(
            advertiser_id,
            enable,
            include_adi,
        );
    }

    // GATT Client

    fn register_client(
        &mut self,
        app_uuid: String,
        callback: Box<dyn IBluetoothGattCallback + Send>,
        eatt_support: bool,
    ) {
        let Some(uuid) = Uuid::from_string(app_uuid.clone()) else {
            warn!("register_client: Uuid is malformed: {}", app_uuid);
            return;
        };
        self.context_map.add(&uuid, callback);
        self.gatt.lock().unwrap().client.register_client(&uuid, eatt_support);
    }

    fn unregister_client(&mut self, client_id: i32) {
        self.context_map.remove(client_id);
        self.gatt.lock().unwrap().client.unregister_client(client_id);
    }

    fn client_connect(
        &self,
        client_id: i32,
        addr: RawAddress,
        is_direct: bool,
        transport: BtTransport,
        opportunistic: bool,
        phy: LePhy,
    ) {
        self.gatt.lock().unwrap().client.connect(
            client_id,
            &addr,
            // Addr type is default PUBLIC.
            0,
            is_direct,
            transport.into(),
            opportunistic,
            phy.into(),
            0,
        );
    }

    fn client_disconnect(&self, client_id: i32, addr: RawAddress) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        self.gatt.lock().unwrap().client.disconnect(client_id, &addr, conn_id);
    }

    fn refresh_device(&self, client_id: i32, addr: RawAddress) {
        self.gatt.lock().unwrap().client.refresh(client_id, &addr);
    }

    fn discover_services(&self, client_id: i32, addr: RawAddress) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        self.gatt.lock().unwrap().client.search_service(conn_id, None);
    }

    fn discover_service_by_uuid(&self, client_id: i32, addr: RawAddress, uuid: String) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        let uuid = Uuid::from_string(uuid);
        if uuid.is_none() {
            return;
        }

        self.gatt.lock().unwrap().client.search_service(conn_id, uuid);
    }

    fn btif_gattc_discover_service_by_uuid(&self, client_id: i32, addr: RawAddress, uuid: String) {
        let conn_id = match self.context_map.get_conn_id_from_address(client_id, &addr) {
            None => return,
            Some(id) => id,
        };
        let Some(uuid) = Uuid::from_string(uuid) else { return };

        self.gatt.lock().unwrap().client.btif_gattc_discover_service_by_uuid(conn_id, &uuid);
    }

    fn read_characteristic(&self, client_id: i32, addr: RawAddress, handle: i32, auth_req: i32) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        // TODO(b/200065274): Perform check on restricted handles.

        self.gatt.lock().unwrap().client.read_characteristic(conn_id, handle as u16, auth_req);
    }

    fn read_using_characteristic_uuid(
        &self,
        client_id: i32,
        addr: RawAddress,
        uuid: String,
        start_handle: i32,
        end_handle: i32,
        auth_req: i32,
    ) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };
        let Some(uuid) = Uuid::from_string(uuid) else { return };

        // TODO(b/200065274): Perform check on restricted handles.

        self.gatt.lock().unwrap().client.read_using_characteristic_uuid(
            conn_id,
            &uuid,
            start_handle as u16,
            end_handle as u16,
            auth_req,
        );
    }

    fn write_characteristic(
        &mut self,
        client_id: i32,
        addr: RawAddress,
        handle: i32,
        mut write_type: GattWriteType,
        auth_req: i32,
        value: Vec<u8>,
    ) -> GattWriteRequestStatus {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return GattWriteRequestStatus::Fail;
        };

        if self.reliable_queue.contains(&addr) {
            write_type = GattWriteType::WritePrepare;
        }

        // TODO(b/200065274): Perform check on restricted handles.

        let permit = self.write_characteristic_permits.entry(addr).or_insert(None);
        if permit.is_none() {
            // Acquire the permit and pass it to BTIF.
            *permit = Some(conn_id);
            self.gatt.lock().unwrap().client.write_characteristic(
                conn_id,
                handle as u16,
                write_type.to_i32().unwrap(),
                auth_req,
                &value,
            );
            GattWriteRequestStatus::Success
        } else {
            GattWriteRequestStatus::Busy
        }
    }

    fn read_descriptor(&self, client_id: i32, addr: RawAddress, handle: i32, auth_req: i32) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        // TODO(b/200065274): Perform check on restricted handles.

        self.gatt.lock().unwrap().client.read_descriptor(conn_id, handle as u16, auth_req);
    }

    fn write_descriptor(
        &self,
        client_id: i32,
        addr: RawAddress,
        handle: i32,
        auth_req: i32,
        value: Vec<u8>,
    ) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        // TODO(b/200065274): Perform check on restricted handles.

        self.gatt.lock().unwrap().client.write_descriptor(conn_id, handle as u16, auth_req, &value);
    }

    fn register_for_notification(
        &self,
        client_id: i32,
        addr: RawAddress,
        handle: i32,
        enable: bool,
    ) {
        let conn_id = self.context_map.get_conn_id_from_address(client_id, &addr);
        if conn_id.is_none() {
            return;
        }

        // TODO(b/200065274): Perform check on restricted handles.

        if enable {
            self.gatt.lock().unwrap().client.register_for_notification(
                client_id,
                &addr,
                handle as u16,
            );
        } else {
            self.gatt.lock().unwrap().client.deregister_for_notification(
                client_id,
                &addr,
                handle as u16,
            );
        }
    }

    fn begin_reliable_write(&mut self, _client_id: i32, addr: RawAddress) {
        self.reliable_queue.insert(addr);
    }

    fn end_reliable_write(&mut self, client_id: i32, addr: RawAddress, execute: bool) {
        self.reliable_queue.remove(&addr);

        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        self.gatt.lock().unwrap().client.execute_write(conn_id, if execute { 1 } else { 0 });
    }

    fn read_remote_rssi(&self, client_id: i32, addr: RawAddress) {
        self.gatt.lock().unwrap().client.read_remote_rssi(client_id, &addr);
    }

    fn configure_mtu(&self, client_id: i32, addr: RawAddress, mtu: i32) {
        let Some(conn_id) = self.context_map.get_conn_id_from_address(client_id, &addr) else {
            return;
        };

        self.gatt.lock().unwrap().client.configure_mtu(conn_id, mtu);
    }

    fn connection_parameter_update(
        &self,
        client_id: i32,
        addr: RawAddress,
        min_interval: i32,
        max_interval: i32,
        latency: i32,
        timeout: i32,
        min_ce_len: u16,
        max_ce_len: u16,
    ) {
        self.gatt.lock().unwrap().client.conn_parameter_update(
            &addr,
            min_interval,
            max_interval,
            latency,
            timeout,
            min_ce_len,
            max_ce_len,
        );
        // If there's no connection for this client, LibBluetooth would not propagate a callback.
        // Thus make up a success callback here.
        if self.context_map.get_conn_id_from_address(client_id, &addr).is_none() {
            let tx = self.tx.clone();
            tokio::spawn(async move {
                let _ = tx
                    .send(Message::GattActions(GattActions::InvokeConnectionUpdated(
                        client_id,
                        addr,
                        max_interval,
                        latency,
                        timeout,
                        GattStatus::Success,
                    )))
                    .await;
            });
        }
    }

    fn client_set_preferred_phy(
        &self,
        client_id: i32,
        addr: RawAddress,
        tx_phy: LePhy,
        rx_phy: LePhy,
        phy_options: i32,
    ) {
        let conn_id = self.context_map.get_conn_id_from_address(client_id, &addr);
        if conn_id.is_none() {
            return;
        }

        self.gatt.lock().unwrap().client.set_preferred_phy(
            &addr,
            tx_phy.to_u8().unwrap(),
            rx_phy.to_u8().unwrap(),
            phy_options as u16,
        );
    }

    fn client_read_phy(&mut self, client_id: i32, addr: RawAddress) {
        self.gatt.lock().unwrap().client.read_phy(client_id, &addr);
    }

    // GATT Server

    fn register_server(
        &mut self,
        app_uuid: String,
        callback: Box<dyn IBluetoothGattServerCallback + Send>,
        eatt_support: bool,
    ) {
        let Some(uuid) = Uuid::from_string(app_uuid.clone()) else {
            warn!("register_server: Uuid is malformed: {}", app_uuid);
            return;
        };
        self.server_context_map.add(&uuid, callback);
        self.gatt.lock().unwrap().server.register_server(&uuid, eatt_support);
    }

    fn unregister_server(&mut self, server_id: i32) {
        self.server_context_map.remove(server_id);
        self.gatt.lock().unwrap().server.unregister_server(server_id);
    }

    fn server_connect(
        &self,
        server_id: i32,
        addr: RawAddress,
        is_direct: bool,
        transport: BtTransport,
    ) -> bool {
        self.gatt.lock().unwrap().server.connect(
            server_id,
            &addr,
            // Addr type is default PUBLIC.
            0,
            is_direct,
            transport.into(),
        );

        true
    }

    fn server_disconnect(&self, server_id: i32, addr: RawAddress) -> bool {
        let conn_id = match self.server_context_map.get_conn_id_from_address(server_id, &addr) {
            None => return false,
            Some(id) => id,
        };

        self.gatt.lock().unwrap().server.disconnect(server_id, &addr, conn_id);

        true
    }

    fn add_service(&self, server_id: i32, service: BluetoothGattService) {
        if let Some(server) = self.server_context_map.get_by_server_id(server_id) {
            self.gatt
                .lock()
                .unwrap()
                .server
                .add_service(server_id, &BluetoothGattService::into_db(service, &server.services));
        } else {
            log::error!("Server id {} is not valid", server_id);
        }
    }

    fn remove_service(&self, server_id: i32, handle: i32) {
        self.gatt.lock().unwrap().server.delete_service(server_id, handle);
    }

    fn clear_services(&self, server_id: i32) {
        if let Some(s) = self.server_context_map.get_by_server_id(server_id) {
            for service in &s.services {
                self.gatt.lock().unwrap().server.delete_service(server_id, service.instance_id);
            }
        }
    }

    fn send_response(
        &self,
        server_id: i32,
        addr: RawAddress,
        request_id: i32,
        status: GattStatus,
        offset: i32,
        value: Vec<u8>,
    ) -> bool {
        (|| {
            let conn_id = self.server_context_map.get_conn_id_from_address(server_id, &addr)?;
            let handle = self.server_context_map.get_request_handle_from_id(request_id)?;
            let len = value.len() as u16;

            let data: [u8; 512] = array_utils::to_sized_array(&value);

            // SAFETY: Initialized all values of the BtGattResponse object
            unsafe {
                self.gatt.lock().unwrap().server.send_response(
                    conn_id,
                    request_id,
                    status as i32,
                    &BtGattResponse {
                        attr_value: BtGattValue {
                            value: data,
                            handle: handle as u16,
                            offset: offset as u16,
                            len,
                            auth_req: 0_u8,
                        },
                    },
                );
            }

            Some(())
        })()
        .is_some()
    }

    fn send_notification(
        &self,
        server_id: i32,
        addr: RawAddress,
        handle: i32,
        confirm: bool,
        value: Vec<u8>,
    ) -> bool {
        let conn_id = match self.server_context_map.get_conn_id_from_address(server_id, &addr) {
            None => return false,
            Some(id) => id,
        };

        self.gatt.lock().unwrap().server.send_indication(
            server_id,
            handle,
            conn_id,
            confirm as i32,
            value.as_ref(),
        );

        true
    }

    fn server_set_preferred_phy(
        &self,
        _server_id: i32,
        addr: RawAddress,
        tx_phy: LePhy,
        rx_phy: LePhy,
        phy_options: i32,
    ) {
        self.gatt.lock().unwrap().server.set_preferred_phy(
            &addr,
            tx_phy.to_u8().unwrap_or_default(),
            rx_phy.to_u8().unwrap_or_default(),
            phy_options as u16,
        );
    }

    fn server_read_phy(&self, server_id: i32, addr: RawAddress) {
        self.gatt.lock().unwrap().server.read_phy(server_id, &addr);
    }
}

#[btif_callbacks_dispatcher(dispatch_gatt_client_callbacks, GattClientCallbacks)]
pub(crate) trait BtifGattClientCallbacks {
    #[btif_callback(RegisterClient)]
    fn register_client_cb(&mut self, status: GattStatus, client_id: i32, app_uuid: Uuid);

    #[btif_callback(Connect)]
    fn connect_cb(&mut self, conn_id: i32, status: GattStatus, client_id: i32, addr: RawAddress);

    #[btif_callback(Disconnect)]
    fn disconnect_cb(&mut self, conn_id: i32, status: GattStatus, client_id: i32, addr: RawAddress);

    #[btif_callback(RegisterForNotification)]
    fn register_for_notification_cb(
        &mut self,
        conn_id: i32,
        registered: i32,
        status: GattStatus,
        handle: u16,
    );

    #[btif_callback(Notify)]
    fn notify_cb(&mut self, conn_id: i32, data: BtGattNotifyParams);

    #[btif_callback(ReadCharacteristic)]
    fn read_characteristic_cb(&mut self, conn_id: i32, status: GattStatus, data: BtGattReadParams);

    #[btif_callback(WriteCharacteristic)]
    fn write_characteristic_cb(
        &mut self,
        conn_id: i32,
        status: GattStatus,
        handle: u16,
        len: u16,
        value: *const u8,
    );

    #[btif_callback(ReadDescriptor)]
    fn read_descriptor_cb(&mut self, conn_id: i32, status: GattStatus, data: BtGattReadParams);

    #[btif_callback(WriteDescriptor)]
    fn write_descriptor_cb(
        &mut self,
        conn_id: i32,
        status: GattStatus,
        handle: u16,
        len: u16,
        value: *const u8,
    );

    #[btif_callback(ExecuteWrite)]
    fn execute_write_cb(&mut self, conn_id: i32, status: GattStatus);

    #[btif_callback(ReadRemoteRssi)]
    fn read_remote_rssi_cb(
        &mut self,
        client_id: i32,
        addr: RawAddress,
        rssi: i32,
        status: GattStatus,
    );

    #[btif_callback(ConfigureMtu)]
    fn configure_mtu_cb(&mut self, conn_id: i32, status: GattStatus, mtu: i32);

    #[btif_callback(Congestion)]
    fn congestion_cb(&mut self, conn_id: i32, congested: bool);

    #[btif_callback(GetGattDb)]
    fn get_gatt_db_cb(&mut self, conn_id: i32, elements: Vec<BtGattDbElement>, count: i32);

    #[btif_callback(PhyUpdated)]
    fn phy_updated_cb(&mut self, conn_id: i32, tx_phy: u8, rx_phy: u8, status: GattStatus);

    #[btif_callback(ConnUpdated)]
    fn conn_updated_cb(
        &mut self,
        conn_id: i32,
        interval: u16,
        latency: u16,
        timeout: u16,
        status: GattStatus,
    );

    #[btif_callback(ServiceChanged)]
    fn service_changed_cb(&mut self, conn_id: i32);

    #[btif_callback(ReadPhy)]
    fn read_phy_cb(
        &mut self,
        client_id: i32,
        addr: RawAddress,
        tx_phy: u8,
        rx_phy: u8,
        status: GattStatus,
    );
}

impl BtifGattClientCallbacks for BluetoothGatt {
    #[log_cb_args]
    fn register_client_cb(&mut self, status: GattStatus, client_id: i32, app_uuid: Uuid) {
        self.context_map.set_client_id(&app_uuid, client_id);

        let client = self.context_map.get_by_uuid(&app_uuid);
        match client {
            Some(c) => {
                let cbid = c.cbid;
                if let Some(cb) = self.context_map.get_callback_from_callback_id(cbid) {
                    cb.on_client_registered(status, client_id);
                }
            }
            None => {
                warn!("Warning: Client not registered for UUID {}", DisplayUuid(&app_uuid));
            }
        }
    }

    #[log_cb_args]
    fn connect_cb(&mut self, conn_id: i32, status: GattStatus, client_id: i32, addr: RawAddress) {
        if status == GattStatus::Success {
            self.context_map.add_connection(client_id, conn_id, &addr);
        }

        let Some(client) = self.context_map.get_by_client_id(client_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_client_connection_state(status, client_id, status == GattStatus::Success, addr);
        }
    }

    #[log_cb_args]
    fn disconnect_cb(
        &mut self,
        conn_id: i32,
        status: GattStatus,
        client_id: i32,
        addr: RawAddress,
    ) {
        let Some(client) = self.context_map.get_by_client_id(client_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_client_connection_state(status, client_id, false, addr);
        }
        self.context_map.remove_connection(client_id, conn_id);

        if self.context_map.get_client_ids_from_address(&addr).is_empty() {
            // Cleaning up as no client connects to this address.
            self.write_characteristic_permits.remove(&addr);
        } else {
            // If the permit is held by this |conn_id|, reset it.
            if let Some(permit) = self.write_characteristic_permits.get_mut(&addr) {
                if *permit == Some(conn_id) {
                    *permit = None;
                }
            }
        }

        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _ = tx.send(Message::ProfileDisconnected(addr)).await;
        });
    }

    #[log_cb_args]
    fn register_for_notification_cb(
        &mut self,
        _conn_id: i32,
        _registered: i32,
        _status: GattStatus,
        _handle: u16,
    ) {
        // No-op.
    }

    #[log_cb_args]
    fn notify_cb(&mut self, conn_id: i32, data: BtGattNotifyParams) {
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_notify(data.bda, data.handle as i32, data.value[0..data.len as usize].to_vec());
        }
    }

    #[log_cb_args]
    fn read_characteristic_cb(&mut self, conn_id: i32, status: GattStatus, data: BtGattReadParams) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_characteristic_read(
                addr,
                status,
                data.handle as i32,
                data.value.value[0..data.value.len as usize].to_vec(),
            );
        }
    }

    #[log_cb_args]
    fn write_characteristic_cb(
        &mut self,
        conn_id: i32,
        mut status: GattStatus,
        handle: u16,
        _len: u16,
        _value: *const u8,
    ) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id_mut(conn_id) else { return };

        // Reset the permit.
        self.write_characteristic_permits.insert(addr, None);

        if client.is_congested {
            if status == GattStatus::Congested {
                status = GattStatus::Success;
            }
            client.congestion_queue.push((addr, status, handle as i32));
            return;
        }

        let cbid = client.cbid;
        if let Some(cb) = self.context_map.get_callback_from_callback_id(cbid) {
            cb.on_characteristic_write(addr, status, handle as i32);
        }
    }

    #[log_cb_args]
    fn read_descriptor_cb(&mut self, conn_id: i32, status: GattStatus, data: BtGattReadParams) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_descriptor_read(
                addr,
                status,
                data.handle as i32,
                data.value.value[0..data.value.len as usize].to_vec(),
            );
        }
    }

    #[log_cb_args]
    fn write_descriptor_cb(
        &mut self,
        conn_id: i32,
        status: GattStatus,
        handle: u16,
        _len: u16,
        _value: *const u8,
    ) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_descriptor_write(addr, status, handle as i32);
        }
    }

    #[log_cb_args]
    fn execute_write_cb(&mut self, conn_id: i32, status: GattStatus) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_execute_write(addr, status);
        }
    }

    #[log_cb_args]
    fn read_remote_rssi_cb(
        &mut self,
        client_id: i32,
        addr: RawAddress,
        rssi: i32,
        status: GattStatus,
    ) {
        let Some(client) = self.context_map.get_by_client_id(client_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_read_remote_rssi(addr, rssi, status);
        }
    }

    #[log_cb_args]
    fn configure_mtu_cb(&mut self, conn_id: i32, status: GattStatus, mtu: i32) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_configure_mtu(addr, mtu, status);
        }
    }

    #[log_cb_args]
    fn congestion_cb(&mut self, conn_id: i32, congested: bool) {
        if let Some(client) = self.context_map.get_client_by_conn_id_mut(conn_id) {
            client.is_congested = congested;
            if !client.is_congested {
                let cbid = client.cbid;
                let mut congestion_queue: Vec<(RawAddress, GattStatus, i32)> = vec![];
                client.congestion_queue.retain(|v| {
                    congestion_queue.push(*v);
                    false
                });

                self.context_map.get_callback_from_callback_id(cbid).map(
                    |cb: &mut GattClientCallback| {
                        for callback in congestion_queue.iter() {
                            cb.on_characteristic_write(callback.0, callback.1, callback.2);
                        }
                    },
                );
            }
        }
    }

    #[log_cb_args]
    fn get_gatt_db_cb(&mut self, conn_id: i32, elements: Vec<BtGattDbElement>, _count: i32) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_search_complete(
                addr,
                BluetoothGattService::from_db(elements, true),
                GattStatus::Success,
            );
        }
    }

    #[log_cb_args]
    fn phy_updated_cb(&mut self, conn_id: i32, tx_phy: u8, rx_phy: u8, status: GattStatus) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_phy_update(
                addr,
                LePhy::from_u8(tx_phy).unwrap(),
                LePhy::from_u8(rx_phy).unwrap(),
                status,
            );
        }
    }

    #[log_cb_args]
    fn read_phy_cb(
        &mut self,
        client_id: i32,
        addr: RawAddress,
        tx_phy: u8,
        rx_phy: u8,
        status: GattStatus,
    ) {
        let Some(client) = self.context_map.get_by_client_id(client_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_phy_read(
                addr,
                LePhy::from_u8(tx_phy).unwrap(),
                LePhy::from_u8(rx_phy).unwrap(),
                status,
            );
        }
    }

    #[log_cb_args]
    fn conn_updated_cb(
        &mut self,
        conn_id: i32,
        interval: u16,
        latency: u16,
        timeout: u16,
        status: GattStatus,
    ) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_connection_updated(addr, interval as i32, latency as i32, timeout as i32, status);
        }
    }

    #[log_cb_args]
    fn service_changed_cb(&mut self, conn_id: i32) {
        let Some(addr) = self.context_map.get_address_by_conn_id(conn_id) else { return };
        let Some(client) = self.context_map.get_client_by_conn_id(conn_id) else { return };
        if let Some(cb) = self.context_map.get_callback_from_callback_id(client.cbid) {
            cb.on_service_changed(addr);
        }
    }
}

#[btif_callbacks_dispatcher(dispatch_gatt_server_callbacks, GattServerCallbacks)]
pub(crate) trait BtifGattServerCallbacks {
    #[btif_callback(RegisterServer)]
    fn register_server_cb(&mut self, status: GattStatus, server_id: i32, app_uuid: Uuid);

    #[btif_callback(Connection)]
    fn connection_cb(&mut self, conn_id: i32, server_id: i32, connected: i32, addr: RawAddress);

    #[btif_callback(ServiceAdded)]
    fn service_added_cb(
        &mut self,
        status: GattStatus,
        server_id: i32,
        elements: Vec<BtGattDbElement>,
        _count: usize,
    );

    #[btif_callback(ServiceDeleted)]
    fn service_deleted_cb(&mut self, status: GattStatus, server_id: i32, handle: i32);

    #[btif_callback(RequestReadCharacteristic)]
    fn request_read_characteristic_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        is_long: bool,
    );

    #[btif_callback(RequestReadDescriptor)]
    fn request_read_descriptor_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        is_long: bool,
    );

    #[btif_callback(RequestWriteCharacteristic)]
    fn request_write_characteristic_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        need_rsp: bool,
        is_prep: bool,
        data: Vec<u8>,
        len: usize,
    );

    #[btif_callback(RequestWriteDescriptor)]
    fn request_write_descriptor_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        need_rsp: bool,
        is_prep: bool,
        data: Vec<u8>,
        len: usize,
    );

    #[btif_callback(RequestExecWrite)]
    fn request_exec_write_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        exec_write: i32,
    );

    #[btif_callback(IndicationSent)]
    fn indication_sent_cb(&mut self, conn_id: i32, status: GattStatus);

    #[btif_callback(Congestion)]
    fn congestion_cb(&mut self, conn_id: i32, congested: bool);

    #[btif_callback(MtuChanged)]
    fn mtu_changed_cb(&mut self, conn_id: i32, mtu: i32);

    #[btif_callback(PhyUpdated)]
    fn phy_updated_cb(&mut self, conn_id: i32, tx_phy: u8, rx_phy: u8, status: GattStatus);

    #[btif_callback(ReadPhy)]
    fn read_phy_cb(
        &mut self,
        server_id: i32,
        addr: RawAddress,
        tx_phy: u8,
        rx_phy: u8,
        status: GattStatus,
    );

    #[btif_callback(ConnUpdated)]
    fn conn_updated_cb(
        &mut self,
        conn_id: i32,
        interval: u16,
        latency: u16,
        timeout: u16,
        status: GattStatus,
    );

    #[btif_callback(SubrateChanged)]
    fn subrate_chg_cb(
        &mut self,
        conn_id: i32,
        subrate_factor: u16,
        latency: u16,
        cont_num: u16,
        timeout: u16,
        status: GattStatus,
    );
}

impl BtifGattServerCallbacks for BluetoothGatt {
    #[log_cb_args]
    fn register_server_cb(&mut self, status: GattStatus, server_id: i32, app_uuid: Uuid) {
        self.server_context_map.set_server_id(&app_uuid, server_id);

        let cbid = self.server_context_map.get_by_uuid(&app_uuid).map(|server| server.cbid);
        match cbid {
            Some(cbid) => {
                if let Some(cb) =
                    self.server_context_map.get_callback_from_callback_id(cbid).as_mut()
                {
                    cb.on_server_registered(status, server_id)
                }
            }
            None => {
                warn!("Warning: No callback found for UUID {}", DisplayUuid(&app_uuid));
            }
        }
    }

    #[log_cb_args]
    fn connection_cb(&mut self, conn_id: i32, server_id: i32, connected: i32, addr: RawAddress) {
        let is_connected = connected != 0;
        if is_connected {
            self.server_context_map.add_connection(server_id, conn_id, &addr);
        } else {
            self.server_context_map.remove_connection(conn_id);
        }

        let cbid = self.server_context_map.get_by_server_id(server_id).map(|server| server.cbid);
        match cbid {
            Some(cbid) => {
                if let Some(cb) =
                    self.server_context_map.get_callback_from_callback_id(cbid).as_mut()
                {
                    cb.on_server_connection_state(server_id, is_connected, addr);
                }
            }
            None => {
                warn!("Warning: No callback found for server ID {}", server_id);
            }
        }
    }

    #[log_cb_args]
    fn service_added_cb(
        &mut self,
        status: GattStatus,
        server_id: i32,
        elements: Vec<BtGattDbElement>,
        _count: usize,
    ) {
        for service in BluetoothGattService::from_db(elements, false) {
            if status == GattStatus::Success {
                self.server_context_map.add_service(server_id, service.clone());
            }

            let cbid =
                self.server_context_map.get_by_server_id(server_id).map(|server| server.cbid);
            match cbid {
                Some(cbid) => {
                    if let Some(cb) =
                        self.server_context_map.get_callback_from_callback_id(cbid).as_mut()
                    {
                        cb.on_service_added(status, service);
                    }
                }
                None => {
                    warn!("Warning: No callback found for server ID {}", server_id);
                }
            }
        }
    }

    #[log_cb_args]
    fn service_deleted_cb(&mut self, status: GattStatus, server_id: i32, handle: i32) {
        if status == GattStatus::Success {
            self.server_context_map.delete_service(server_id, handle);
        }

        let cbid = self.server_context_map.get_by_server_id(server_id).map(|server| server.cbid);

        if let Some(cbid) = cbid {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_service_removed(status, handle);
            }
        }
    }

    #[log_cb_args]
    fn request_read_characteristic_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        is_long: bool,
    ) {
        self.server_context_map.add_request(trans_id, handle);

        if let Some(cbid) =
            self.server_context_map.get_by_conn_id(conn_id).map(|server| server.cbid)
        {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_characteristic_read_request(addr, trans_id, offset, is_long, handle);
            }
        }
    }

    #[log_cb_args]
    fn request_read_descriptor_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        is_long: bool,
    ) {
        self.server_context_map.add_request(trans_id, handle);

        if let Some(cbid) =
            self.server_context_map.get_by_conn_id(conn_id).map(|server| server.cbid)
        {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_descriptor_read_request(addr, trans_id, offset, is_long, handle);
            }
        }
    }

    #[log_cb_args]
    fn request_write_characteristic_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        need_rsp: bool,
        is_prep: bool,
        data: Vec<u8>,
        len: usize,
    ) {
        self.server_context_map.add_request(trans_id, handle);

        if let Some(cbid) =
            self.server_context_map.get_by_conn_id(conn_id).map(|server| server.cbid)
        {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_characteristic_write_request(
                    addr, trans_id, offset, len as i32, is_prep, need_rsp, handle, data,
                );
            }
        }
    }

    #[log_cb_args]
    fn request_write_descriptor_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        handle: i32,
        offset: i32,
        need_rsp: bool,
        is_prep: bool,
        data: Vec<u8>,
        len: usize,
    ) {
        self.server_context_map.add_request(trans_id, handle);

        if let Some(cbid) =
            self.server_context_map.get_by_conn_id(conn_id).map(|server| server.cbid)
        {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_descriptor_write_request(
                    addr, trans_id, offset, len as i32, is_prep, need_rsp, handle, data,
                );
            }
        }
    }

    #[log_cb_args]
    fn request_exec_write_cb(
        &mut self,
        conn_id: i32,
        trans_id: i32,
        addr: RawAddress,
        exec_write: i32,
    ) {
        self.server_context_map.add_request(trans_id, 0);

        if let Some(cbid) =
            self.server_context_map.get_by_conn_id(conn_id).map(|server| server.cbid)
        {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_execute_write(addr, trans_id, exec_write != 0);
            }
        }
    }

    #[log_cb_args]
    fn indication_sent_cb(&mut self, conn_id: i32, mut status: GattStatus) {
        (|| {
            let address = self.server_context_map.get_address_from_conn_id(conn_id)?;
            let server = self.server_context_map.get_mut_by_conn_id(conn_id)?;

            if server.is_congested {
                if status == GattStatus::Congested {
                    status = GattStatus::Success;
                }

                server.congestion_queue.push((address, status));
                return None;
            }

            let cbid = server.cbid;
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_notification_sent(address, status);
            }

            Some(())
        })();
    }

    #[log_cb_args]
    fn congestion_cb(&mut self, conn_id: i32, congested: bool) {
        if let Some(server) = self.server_context_map.get_mut_by_conn_id(conn_id) {
            server.is_congested = congested;
            if !server.is_congested {
                let cbid = server.cbid;
                let congestion_queue: Vec<_> = server.congestion_queue.drain(..).collect();

                if let Some(cb) =
                    self.server_context_map.get_callback_from_callback_id(cbid).as_mut()
                {
                    for callback in congestion_queue {
                        cb.on_notification_sent(callback.0, callback.1);
                    }
                }
            }
        }
    }

    #[log_cb_args]
    fn mtu_changed_cb(&mut self, conn_id: i32, mtu: i32) {
        (|| {
            let address = self.server_context_map.get_address_from_conn_id(conn_id)?;
            let server_cbid = self.server_context_map.get_by_conn_id(conn_id)?.cbid;

            if let Some(cb) =
                self.server_context_map.get_callback_from_callback_id(server_cbid).as_mut()
            {
                cb.on_mtu_changed(address, mtu);
            }

            Some(())
        })();
    }

    #[log_cb_args]
    fn phy_updated_cb(&mut self, conn_id: i32, tx_phy: u8, rx_phy: u8, status: GattStatus) {
        (|| {
            let address = self.server_context_map.get_address_from_conn_id(conn_id)?;
            let server_cbid = self.server_context_map.get_by_conn_id(conn_id)?.cbid;

            if let Some(cb) =
                self.server_context_map.get_callback_from_callback_id(server_cbid).as_mut()
            {
                cb.on_phy_update(
                    address,
                    LePhy::from_u8(tx_phy).unwrap_or_default(),
                    LePhy::from_u8(rx_phy).unwrap_or_default(),
                    status,
                );
            }

            Some(())
        })();
    }

    #[log_cb_args]
    fn read_phy_cb(
        &mut self,
        server_id: i32,
        addr: RawAddress,
        tx_phy: u8,
        rx_phy: u8,
        status: GattStatus,
    ) {
        if let Some(cbid) =
            self.server_context_map.get_by_server_id(server_id).map(|server| server.cbid)
        {
            if let Some(cb) = self.server_context_map.get_callback_from_callback_id(cbid).as_mut() {
                cb.on_phy_read(
                    addr,
                    LePhy::from_u8(tx_phy).unwrap_or_default(),
                    LePhy::from_u8(rx_phy).unwrap_or_default(),
                    status,
                );
            }
        }
    }

    #[log_cb_args]
    fn conn_updated_cb(
        &mut self,
        conn_id: i32,
        interval: u16,
        latency: u16,
        timeout: u16,
        status: GattStatus,
    ) {
        (|| {
            let address = self.server_context_map.get_address_from_conn_id(conn_id)?;
            let server_cbid = self.server_context_map.get_by_conn_id(conn_id)?.cbid;

            if let Some(cb) =
                self.server_context_map.get_callback_from_callback_id(server_cbid).as_mut()
            {
                cb.on_connection_updated(
                    address,
                    interval as i32,
                    latency as i32,
                    timeout as i32,
                    status,
                );
            }

            Some(())
        })();
    }

    #[log_cb_args]
    fn subrate_chg_cb(
        &mut self,
        conn_id: i32,
        subrate_factor: u16,
        latency: u16,
        cont_num: u16,
        timeout: u16,
        status: GattStatus,
    ) {
        (|| {
            let address = self.server_context_map.get_address_from_conn_id(conn_id)?;
            let server_cbid = self.server_context_map.get_by_conn_id(conn_id)?.cbid;

            if let Some(cb) =
                self.server_context_map.get_callback_from_callback_id(server_cbid).as_mut()
            {
                cb.on_subrate_change(
                    address,
                    subrate_factor as i32,
                    latency as i32,
                    cont_num as i32,
                    timeout as i32,
                    status,
                );
            }

            Some(())
        })();
    }
}

#[btif_callbacks_dispatcher(dispatch_le_scanner_callbacks, GattScannerCallbacks)]
pub(crate) trait BtifGattScannerCallbacks {
    #[btif_callback(OnScannerRegistered)]
    fn on_scanner_registered(&mut self, uuid: Uuid, scanner_id: u8, status: GattStatus);

    #[btif_callback(OnScanResult)]
    fn on_scan_result(
        &mut self,
        event_type: u16,
        addr_type: u8,
        bda: RawAddress,
        primary_phy: u8,
        secondary_phy: u8,
        advertising_sid: u8,
        tx_power: i8,
        rssi: i8,
        periodic_adv_int: u16,
        adv_data: Vec<u8>,
    );

    #[btif_callback(OnTrackAdvFoundLost)]
    fn on_track_adv_found_lost(&mut self, adv_track_info: AdvertisingTrackInfo);
}

#[btif_callbacks_dispatcher(dispatch_le_scanner_inband_callbacks, GattScannerInbandCallbacks)]
pub(crate) trait BtifGattScannerInbandCallbacks {
    #[btif_callback(RegisterCallback)]
    fn inband_register_callback(&mut self, app_uuid: Uuid, scanner_id: u8, btm_status: u8);

    #[btif_callback(StatusCallback)]
    fn inband_status_callback(&mut self, scanner_id: u8, btm_status: u8);

    #[btif_callback(EnableCallback)]
    fn inband_enable_callback(&mut self, action: u8, btm_status: u8);

    #[btif_callback(FilterParamSetupCallback)]
    fn inband_filter_param_setup_callback(
        &mut self,
        scanner_id: u8,
        available_space: u8,
        action_type: u8,
        btm_status: u8,
    );

    #[btif_callback(FilterConfigCallback)]
    fn inband_filter_config_callback(
        &mut self,
        filter_index: u8,
        filter_type: u8,
        available_space: u8,
        action: u8,
        btm_status: u8,
    );

    #[btif_callback(MsftAdvMonitorAddCallback)]
    fn inband_msft_adv_monitor_add_callback(&mut self, monitor_handle: u8, status: u8);

    #[btif_callback(MsftAdvMonitorRemoveCallback)]
    fn inband_msft_adv_monitor_remove_callback(&mut self, status: u8);

    #[btif_callback(MsftAdvMonitorEnableCallback)]
    fn inband_msft_adv_monitor_enable_callback(&mut self, status: u8);

    #[btif_callback(StartSyncCallback)]
    fn inband_start_sync_callback(
        &mut self,
        status: u8,
        sync_handle: u16,
        advertising_sid: u8,
        address_type: u8,
        address: RawAddress,
        phy: u8,
        interval: u16,
    );

    #[btif_callback(SyncReportCallback)]
    fn inband_sync_report_callback(
        &mut self,
        sync_handle: u16,
        tx_power: i8,
        rssi: i8,
        status: u8,
        data: Vec<u8>,
    );

    #[btif_callback(SyncLostCallback)]
    fn inband_sync_lost_callback(&mut self, sync_handle: u16);

    #[btif_callback(SyncTransferCallback)]
    fn inband_sync_transfer_callback(&mut self, status: u8, address: RawAddress);
}

impl BtifGattScannerInbandCallbacks for BluetoothGatt {
    #[log_cb_args]
    fn inband_register_callback(&mut self, app_uuid: Uuid, scanner_id: u8, btm_status: u8) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::RegisterCallback(app_uuid, scanner_id, btm_status)
        );
    }

    #[log_cb_args]
    fn inband_status_callback(&mut self, scanner_id: u8, btm_status: u8) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::StatusCallback(scanner_id, btm_status)
        );
    }

    #[log_cb_args]
    fn inband_enable_callback(&mut self, action: u8, btm_status: u8) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::EnableCallback(action, btm_status)
        );
    }

    #[log_cb_args]
    fn inband_filter_param_setup_callback(
        &mut self,
        scanner_id: u8,
        available_space: u8,
        action_type: u8,
        btm_status: u8,
    ) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::FilterParamSetupCallback(
                scanner_id,
                available_space,
                action_type,
                btm_status
            )
        );
    }

    #[log_cb_args]
    fn inband_filter_config_callback(
        &mut self,
        filter_index: u8,
        filter_type: u8,
        available_space: u8,
        action: u8,
        btm_status: u8,
    ) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::FilterConfigCallback(
                filter_index,
                filter_type,
                available_space,
                action,
                btm_status,
            )
        );
    }

    #[log_cb_args]
    fn inband_msft_adv_monitor_add_callback(&mut self, monitor_handle: u8, status: u8) {
        if !self.enabled {
            return;
        }
        let (should_abort, scanner_id, monitor) = match &self.msft_command_pending {
            MsftCommandPending::Aborted(scanner_id, monitor) => {
                (true, *scanner_id, monitor.clone())
            }
            MsftCommandPending::Add(scanner_id, monitor) => (false, *scanner_id, monitor.clone()),
            _ => {
                error!(
                    "Unexpected MsftAdvMonitorAddCallback monitor_handle={} status={}, pending={:?}",
                    monitor_handle, status, self.msft_command_pending
                );
                return;
            }
        };
        self.msft_command_pending = MsftCommandPending::NoPending;

        if status != 0 {
            error!(
                "Error adding advertisement monitor {:?}, scanner_id={}, status={}",
                monitor, scanner_id, status
            );
        } else if should_abort {
            debug!("Aborted MSFT monitor, removing");
            self.msft_command_queue.push_back(MsftCommandQueue::Remove(monitor_handle));
        } else {
            match monitor.condition {
                ScanFilterCondition::Patterns(_) => {
                    self.on_pattern_monitor_added(scanner_id, monitor_handle);
                }
                ScanFilterCondition::BluetoothAddress(_) => {
                    self.on_address_monitor_added(scanner_id, monitor_handle, &monitor);
                }
                _ => error!("Unhandled MSFT monitor added {:?}", monitor),
            }
        }
        self.msft_run_queue_and_update_scan();
    }

    #[log_cb_args]
    fn inband_msft_adv_monitor_remove_callback(&mut self, status: u8) {
        if !self.enabled {
            return;
        }
        let MsftCommandPending::Remove(_monitor_handle) = self.msft_command_pending else {
            error!(
                "Unexpected MsftAdvMonitorRemoveCallback status={}, pending={:?}",
                status, self.msft_command_pending
            );
            return;
        };
        self.msft_command_pending = MsftCommandPending::NoPending;

        if status != 0 {
            error!("Error removing advertisement monitor, status={}", status);
        }
        self.msft_run_queue_and_update_scan();
    }

    #[log_cb_args]
    fn inband_msft_adv_monitor_enable_callback(&mut self, status: u8) {
        if !self.enabled {
            return;
        }
        let MsftCommandPending::Enable(enabled) = self.msft_command_pending else {
            error!(
                "Unexpected MsftAdvMonitorEnableCallback status={}, pending={:?}",
                status, self.msft_command_pending
            );
            return;
        };
        self.msft_command_pending = MsftCommandPending::NoPending;

        if status != 0 {
            error!("Error updating Advertisement Monitor enable, status={}", status);
        } else {
            self.msft_enabled = enabled;
        }

        // Force restart the scan as the MSFT enable just changed.
        self.update_scan(true);

        // We should call this even if the queue is empty to ensure the MSFT enabled state correct.
        // This covers a rare case: If an unfiltered scanner is added/removed when the pending
        // command is MsftCommandPending::Enable, the add/remove become no-op but the info in the
        // pending command could be outdated.
        self.msft_run_queue_and_update_scan();
    }

    #[log_cb_args]
    fn inband_start_sync_callback(
        &mut self,
        status: u8,
        sync_handle: u16,
        advertising_sid: u8,
        address_type: u8,
        address: RawAddress,
        phy: u8,
        interval: u16,
    ) {
        log::debug!(
            "Callback received: StartSyncCallback({}, {}, {}, {}, {}, {}, {})",
            status,
            sync_handle,
            advertising_sid,
            address_type,
            DisplayAddress(&address),
            phy,
            interval
        );
    }

    #[log_cb_args]
    fn inband_sync_report_callback(
        &mut self,
        sync_handle: u16,
        tx_power: i8,
        rssi: i8,
        status: u8,
        data: Vec<u8>,
    ) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::SyncReportCallback(
                sync_handle,
                tx_power,
                rssi,
                status,
                data
            )
        );
    }

    #[log_cb_args]
    fn inband_sync_lost_callback(&mut self, sync_handle: u16) {
        log::debug!(
            "Callback received: {:#?}",
            GattScannerInbandCallbacks::SyncLostCallback(sync_handle,)
        );
    }

    #[log_cb_args]
    fn inband_sync_transfer_callback(&mut self, status: u8, address: RawAddress) {
        log::debug!(
            "Callback received: SyncTransferCallback({}, {})",
            status,
            DisplayAddress(&address)
        );
    }
}

impl BtifGattScannerCallbacks for BluetoothGatt {
    #[log_cb_args]
    fn on_scanner_registered(&mut self, uuid: Uuid, scanner_id: u8, status: GattStatus) {
        debug!(
            "on_scanner_registered UUID = {}, scanner_id = {}, status = {}",
            DisplayUuid(&uuid),
            scanner_id,
            status
        );

        let scanner_info = self.scanners.get_mut(&uuid);

        if let Some(info) = scanner_info {
            info.scanner_id = Some(scanner_id);
            if let Some(cb) = self.scanner_callbacks.get_by_id_mut(info.callback_id) {
                cb.on_scanner_registered(uuid, scanner_id, status);
            } else {
                warn!("There is no callback for scanner UUID {}", DisplayUuid(&uuid));
            }
        } else {
            warn!(
                "Scanner registered callback for non-existent scanner info, UUID = {}",
                DisplayUuid(&uuid)
            );
        }

        if status != GattStatus::Success {
            error!("Error registering scanner UUID {}", DisplayUuid(&uuid));
            self.scanners.remove(&uuid);
        }
    }

    #[log_cb_args]
    fn on_scan_result(
        &mut self,
        event_type: u16,
        addr_type: u8,
        address: RawAddress,
        primary_phy: u8,
        secondary_phy: u8,
        advertising_sid: u8,
        tx_power: i8,
        rssi: i8,
        periodic_adv_int: u16,
        adv_data: Vec<u8>,
    ) {
        self.scanner_callbacks.for_all_callbacks(|callback| {
            callback.on_scan_result(ScanResult {
                name: adv_parser::extract_name(adv_data.as_slice()),
                address,
                addr_type,
                event_type,
                primary_phy,
                secondary_phy,
                advertising_sid,
                tx_power,
                rssi,
                periodic_adv_int,
                flags: adv_parser::extract_flags(adv_data.as_slice()),
                service_uuids: adv_parser::extract_service_uuids(adv_data.as_slice()),
                service_data: adv_parser::extract_service_data(adv_data.as_slice()),
                manufacturer_data: adv_parser::extract_manufacturer_data(adv_data.as_slice()),
                adv_data: adv_data.clone(),
            });
        });
    }

    #[log_cb_args]
    fn on_track_adv_found_lost(&mut self, track_adv_info: AdvertisingTrackInfo) {
        let addr = track_adv_info.advertiser_address;
        let display_addr = DisplayAddress(&addr);
        let mut corresponding_scanner: Option<&mut ScannerInfo> =
            self.scanners.values_mut().find_map(|scanner| {
                if scanner.monitor_handle == Some(track_adv_info.monitor_handle) {
                    Some(scanner)
                } else {
                    None
                }
            });
        if corresponding_scanner.is_none() {
            corresponding_scanner = self.scanners.values_mut().find_map(|scanner| {
                if scanner.addr_handle_map.contains_key(&addr) {
                    Some(scanner)
                } else {
                    None
                }
            });
        }

        let Some(corresponding_scanner) = corresponding_scanner else {
            info!(
                "No scanner having monitor handle {}, already removed?",
                track_adv_info.monitor_handle
            );
            return;
        };
        let Some(scanner_id) = corresponding_scanner.scanner_id else {
            error!(
                "Scanner having monitor handle {} is missing scanner id",
                track_adv_info.monitor_handle
            );
            return;
        };

        let controller_need_separate_pattern_and_address =
            corresponding_scanner.addr_tracking_quirk;

        let mut address_monitor_succeed: bool = false;
        if controller_need_separate_pattern_and_address {
            if track_adv_info.advertiser_state == 0x01 {
                if corresponding_scanner.addr_handle_map.contains_key(&addr) {
                    debug!(
                        "on_track_adv_found_lost: this addr {} is already handled, just return",
                        display_addr
                    );
                    return;
                }
                debug!(
                    "on_track_adv_found_lost: state == 0x01, adding addr {} to map",
                    display_addr
                );
                corresponding_scanner.addr_handle_map.insert(addr, None);

                let scan_filter_addr = ScanFilterAddress {
                    addr_type: track_adv_info.advertiser_address_type,
                    bd_addr: addr,
                };

                if let Some(saved_filter) = corresponding_scanner.filter.clone() {
                    let scan_filter = ScanFilter {
                        rssi_high_threshold: saved_filter.rssi_high_threshold,
                        rssi_low_threshold: saved_filter.rssi_low_threshold,
                        rssi_low_timeout: saved_filter.rssi_low_timeout,
                        rssi_sampling_period: saved_filter.rssi_sampling_period,
                        condition: ScanFilterCondition::BluetoothAddress(scan_filter_addr),
                    };
                    self.msft_command_queue
                        .push_back(MsftCommandQueue::Add(scanner_id, scan_filter));
                    self.msft_run_queue_and_update_scan();
                    address_monitor_succeed = true;
                }
            } else {
                if let Some(handle) = corresponding_scanner.monitor_handle {
                    if handle == track_adv_info.monitor_handle {
                        debug!("pattern filter lost, addr={}", display_addr);
                        return;
                    }
                }

                if corresponding_scanner.addr_handle_map.remove(&addr).is_some() {
                    debug!("on_track_adv_found_lost: removing addr = {} from map", display_addr);
                    self.msft_command_queue
                        .push_back(MsftCommandQueue::Remove(track_adv_info.monitor_handle));
                    self.msft_run_queue_and_update_scan();
                }
            }
        }

        self.scanner_callbacks.for_all_callbacks(|callback| {
            let adv_data =
                [&track_adv_info.adv_packet[..], &track_adv_info.scan_response[..]].concat();

            let scan_result = ScanResult {
                name: adv_parser::extract_name(adv_data.as_slice()),
                address: addr,
                addr_type: track_adv_info.advertiser_address_type,
                event_type: 0, /* not used */
                primary_phy: LePhy::Phy1m as u8,
                secondary_phy: 0,      /* not used */
                advertising_sid: 0xff, /* not present */
                /* A bug in libbluetooth that uses u8 for TX power.
                 * TODO(b/261482382): Fix the data type in C++ layer to use i8 instead of u8. */
                tx_power: track_adv_info.tx_power as i8,
                rssi: track_adv_info.rssi,
                periodic_adv_int: 0, /* not used */
                flags: adv_parser::extract_flags(adv_data.as_slice()),
                service_uuids: adv_parser::extract_service_uuids(adv_data.as_slice()),
                service_data: adv_parser::extract_service_data(adv_data.as_slice()),
                manufacturer_data: adv_parser::extract_manufacturer_data(adv_data.as_slice()),
                adv_data,
            };

            if track_adv_info.advertiser_state == 0x01 {
                if !controller_need_separate_pattern_and_address || address_monitor_succeed {
                    callback.on_advertisement_found(scanner_id, scan_result);
                }
            } else {
                callback.on_advertisement_lost(scanner_id, scan_result);
            }
        });
    }
}

impl BtifGattAdvCallbacks for BluetoothGatt {
    #[log_cb_args]
    fn on_advertising_set_started(
        &mut self,
        reg_id: i32,
        advertiser_id: u8,
        tx_power: i8,
        status: AdvertisingStatus,
    ) {
        self.adv_manager.get_impl().on_advertising_set_started(
            reg_id,
            advertiser_id,
            tx_power,
            status,
        );
    }

    #[log_cb_args]
    fn on_advertising_enabled(&mut self, adv_id: u8, enabled: bool, status: AdvertisingStatus) {
        self.adv_manager.get_impl().on_advertising_enabled(adv_id, enabled, status);
    }

    #[log_cb_args]
    fn on_advertising_data_set(&mut self, adv_id: u8, status: AdvertisingStatus) {
        self.adv_manager.get_impl().on_advertising_data_set(adv_id, status);
    }

    #[log_cb_args]
    fn on_scan_response_data_set(&mut self, adv_id: u8, status: AdvertisingStatus) {
        self.adv_manager.get_impl().on_scan_response_data_set(adv_id, status);
    }

    #[log_cb_args]
    fn on_advertising_parameters_updated(
        &mut self,
        adv_id: u8,
        tx_power: i8,
        status: AdvertisingStatus,
    ) {
        self.adv_manager.get_impl().on_advertising_parameters_updated(adv_id, tx_power, status);
    }

    #[log_cb_args]
    fn on_periodic_advertising_parameters_updated(
        &mut self,
        adv_id: u8,
        status: AdvertisingStatus,
    ) {
        self.adv_manager.get_impl().on_periodic_advertising_parameters_updated(adv_id, status);
    }

    #[log_cb_args]
    fn on_periodic_advertising_data_set(&mut self, adv_id: u8, status: AdvertisingStatus) {
        self.adv_manager.get_impl().on_periodic_advertising_data_set(adv_id, status);
    }

    #[log_cb_args]
    fn on_periodic_advertising_enabled(
        &mut self,
        adv_id: u8,
        enabled: bool,
        status: AdvertisingStatus,
    ) {
        self.adv_manager.get_impl().on_periodic_advertising_enabled(adv_id, enabled, status);
    }

    #[log_cb_args]
    fn on_own_address_read(&mut self, adv_id: u8, addr_type: u8, address: RawAddress) {
        self.adv_manager.get_impl().on_own_address_read(adv_id, addr_type, address);
    }
}

#[cfg(test)]
mod tests {
    struct TestBluetoothGattCallback {
        id: String,
    }

    impl TestBluetoothGattCallback {
        fn new(id: String) -> TestBluetoothGattCallback {
            TestBluetoothGattCallback { id }
        }
    }

    impl IBluetoothGattCallback for TestBluetoothGattCallback {
        fn on_client_registered(&mut self, _status: GattStatus, _client_id: i32) {}
        fn on_client_connection_state(
            &mut self,
            _status: GattStatus,
            _client_id: i32,
            _connected: bool,
            _addr: RawAddress,
        ) {
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

        fn on_search_complete(
            &mut self,
            _addr: RawAddress,
            _services: Vec<BluetoothGattService>,
            _status: GattStatus,
        ) {
        }

        fn on_characteristic_read(
            &mut self,
            _addr: RawAddress,
            _status: GattStatus,
            _handle: i32,
            _value: Vec<u8>,
        ) {
        }

        fn on_characteristic_write(
            &mut self,
            _addr: RawAddress,
            _status: GattStatus,
            _handle: i32,
        ) {
        }

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

        fn on_notify(&mut self, _addr: RawAddress, _handle: i32, _value: Vec<u8>) {}

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

    impl RPCProxy for TestBluetoothGattCallback {
        fn get_object_id(&self) -> String {
            self.id.clone()
        }
    }

    use super::*;

    #[test]
    fn test_uuid_from_string() {
        let uuid = Uuid::from_string("abcdef");
        assert!(uuid.is_none());

        let uuid = Uuid::from_string("0123456789abcdef0123456789abcdef");
        assert!(uuid.is_some());
        let expected: [u8; 16] = [
            0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab,
            0xcd, 0xef,
        ];
        assert_eq!(Uuid::from(expected), uuid.unwrap());
    }

    #[test]
    fn test_context_map_clients() {
        let (tx, _rx) = crate::Stack::create_channel();
        let mut map = ContextMap::new(tx.clone());

        // Add client 1.
        let callback1 = Box::new(TestBluetoothGattCallback::new(String::from("Callback 1")));
        let uuid1 = Uuid::from_string("00000000000000000000000000000001").unwrap();
        map.add(&uuid1, callback1);
        let found = map.get_by_uuid(&uuid1);
        assert!(found.is_some());
        assert_eq!(
            "Callback 1",
            match found {
                Some(c) => {
                    let cbid = c.cbid;
                    map.callbacks.get_by_id(cbid).map(|cb| cb.get_object_id()).unwrap_or_default()
                }
                None => String::new(),
            }
        );

        // Add client 2.
        let callback2 = Box::new(TestBluetoothGattCallback::new(String::from("Callback 2")));
        let uuid2 = Uuid::from_string("00000000000000000000000000000002").unwrap();
        map.add(&uuid2, callback2);
        let found = map.get_by_uuid(&uuid2);
        assert!(found.is_some());
        assert_eq!(
            "Callback 2",
            match found {
                Some(c) => {
                    let cbid = c.cbid;
                    map.callbacks.get_by_id(cbid).map(|cb| cb.get_object_id()).unwrap_or_default()
                }
                None => String::new(),
            }
        );

        // Set client ID and get by client ID.
        map.set_client_id(&uuid1, 3);
        let found = map.get_by_client_id(3);
        assert!(found.is_some());

        // Remove client 1.
        map.remove(3);
        let found = map.get_by_uuid(&uuid1);
        assert!(found.is_none());
    }

    #[test]
    fn test_context_map_connections() {
        let (tx, _rx) = crate::Stack::create_channel();
        let mut map = ContextMap::new(tx.clone());
        let client_id = 1;

        map.add_connection(client_id, 3, &RawAddress::from_string("aa:bb:cc:dd:ee:ff").unwrap());
        map.add_connection(client_id, 4, &RawAddress::from_string("11:22:33:44:55:66").unwrap());

        let found = map.get_conn_id_from_address(
            client_id,
            &RawAddress::from_string("aa:bb:cc:dd:ee:ff").unwrap(),
        );
        assert!(found.is_some());
        assert_eq!(3, found.unwrap());

        let found = map.get_conn_id_from_address(
            client_id,
            &RawAddress::from_string("11:22:33:44:55:66").unwrap(),
        );
        assert!(found.is_some());
        assert_eq!(4, found.unwrap());
    }
}
