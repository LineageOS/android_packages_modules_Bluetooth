//! Floss Bluetooth stack.
//!
//! This crate provides the API implementation of the Fluoride/GD Bluetooth
//! stack, independent of any RPC projection.

pub mod battery_manager;
pub mod battery_provider_manager;
pub mod battery_service;
pub mod bluetooth;
pub mod bluetooth_admin;
pub mod bluetooth_adv;
pub mod bluetooth_gatt;
pub mod bluetooth_logging;
pub mod bluetooth_media;
pub mod bluetooth_qa;
pub mod callbacks;
pub mod dis;
pub mod socket_manager;
pub mod suspend;
pub mod uuid;

use bluetooth_qa::{BluetoothQA, IBluetoothQA};
use log::{debug, info};
use num_derive::{FromPrimitive, ToPrimitive};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc::{channel, Receiver, Sender};
use tokio::time::{sleep, Duration};

use crate::battery_manager::{BatteryManager, BatterySet};
use crate::battery_provider_manager::BatteryProviderManager;
use crate::battery_service::{
    BatteryService, BatteryServiceActions, BATTERY_SERVICE_GATT_CLIENT_APP_ID,
};
use crate::bluetooth::{
    dispatch_base_callbacks, dispatch_hid_host_callbacks, dispatch_sdp_callbacks, AdapterActions,
    Bluetooth, BluetoothDevice, IBluetooth,
};
use crate::bluetooth_admin::{AdminActions, BluetoothAdmin, IBluetoothAdmin};
use crate::bluetooth_adv::{dispatch_le_adv_callbacks, AdvertiserActions};
use crate::bluetooth_gatt::{
    dispatch_gatt_client_callbacks, dispatch_gatt_server_callbacks, dispatch_le_scanner_callbacks,
    dispatch_le_scanner_inband_callbacks, BluetoothGatt, GattActions,
};
use crate::bluetooth_media::{BluetoothMedia, IBluetoothMedia, MediaActions};
use crate::dis::{DeviceInformation, ServiceCallbacks};
use crate::socket_manager::{BluetoothSocketManager, SocketActions};
use crate::suspend::{Suspend, SuspendActions};
use bt_topshim::btif::{
    BaseCallbacks, BtAclState, BtBondState, BtTransport, DisplayAddress, RawAddress, Uuid,
};
use bt_topshim::profiles::a2dp::A2dpCallbacks;
use bt_topshim::profiles::avrcp::AvrcpCallbacks;
use bt_topshim::profiles::csis::CsisClientCallbacks;
use bt_topshim::profiles::gatt::{
    GattAdvCallbacks, GattAdvInbandCallbacks, GattClientCallbacks, GattScannerCallbacks,
    GattScannerInbandCallbacks, GattServerCallbacks,
};
use bt_topshim::profiles::hfp::HfpCallbacks;
use bt_topshim::profiles::hid_host::{BthhReportType, HHCallbacks};
use bt_topshim::profiles::le_audio::LeAudioClientCallbacks;
use bt_topshim::profiles::sdp::SdpCallbacks;
use bt_topshim::profiles::vc::VolumeControlCallbacks;

/// Message types that are sent to the stack main dispatch loop.
pub enum Message {
    /// Remove the DBus API. Call it before other AdapterShutdown.
    InterfaceShutdown,
    /// Disable the adapter by calling btif disable.
    AdapterShutdown,
    /// Clean up the adapter by calling btif cleanup.
    Cleanup,
    /// Clean up the media by calling profile cleanup.
    CleanupProfiles,

    // Adapter is enabled and ready.
    AdapterReady,

    // Callbacks from libbluetooth
    A2dp(A2dpCallbacks),
    Avrcp(AvrcpCallbacks),
    Base(BaseCallbacks),
    GattClient(GattClientCallbacks),
    GattServer(GattServerCallbacks),
    LeAudioClient(LeAudioClientCallbacks),
    LeScanner(GattScannerCallbacks),
    LeScannerInband(GattScannerInbandCallbacks),
    LeAdvInband(GattAdvInbandCallbacks),
    LeAdv(GattAdvCallbacks),
    HidHost(HHCallbacks),
    Hfp(HfpCallbacks),
    Sdp(SdpCallbacks),
    VolumeControl(VolumeControlCallbacks),
    CsisClient(CsisClientCallbacks),
    CreateBondWithRetry(BluetoothDevice, BtTransport, u32, Duration),

    // Actions within the stack
    Media(MediaActions),
    MediaCallbackDisconnected(u32),
    TelephonyCallbackDisconnected(u32),

    // Client callback disconnections
    AdapterCallbackDisconnected(u32),
    ConnectionCallbackDisconnected(u32),

    AdapterActions(AdapterActions),

    // Follows IBluetooth's on_device_(dis)connected and bond_state callbacks
    // but doesn't require depending on Bluetooth.
    // Params: Address, BR/EDR ACL state, BLE ACL state, bond state, transport
    OnDeviceConnectionOrBondStateChanged(
        RawAddress,
        BtAclState,
        BtAclState,
        BtBondState,
        BtTransport,
    ),

    // Suspend related
    SuspendActions(SuspendActions),

    // Scanner related
    ScannerCallbackDisconnected(u32),

    // Advertising related
    AdvertiserCallbackDisconnected(u32),
    AdvertiserActions(AdvertiserActions),

    SocketManagerActions(SocketActions),
    SocketManagerCallbackDisconnected(u32),

    // Battery related
    BatteryProviderManagerCallbackDisconnected(u32),
    BatteryProviderManagerBatteryUpdated(RawAddress, BatterySet),
    BatteryServiceCallbackDisconnected(u32),
    BatteryService(BatteryServiceActions),
    BatteryServiceRefresh,
    BatteryManagerCallbackDisconnected(u32),

    GattActions(GattActions),
    GattClientCallbackDisconnected(u32),
    GattServerCallbackDisconnected(u32),

    // Admin policy related
    AdminCallbackDisconnected(u32),
    AdminActions(AdminActions),
    HidHostEnable,

    // Dis callbacks
    Dis(ServiceCallbacks),

    // Device removal
    DisconnectDevice(BluetoothDevice),

    // Qualification Only
    QaCallbackDisconnected(u32),
    QaAddMediaPlayer(String, bool),
    QaRfcommSendMsc(u8, RawAddress),
    QaFetchDiscoverableMode,
    QaFetchConnectable,
    QaSetConnectable(bool),
    QaFetchAlias,
    QaGetHidReport(RawAddress, BthhReportType, u8),
    QaSetHidReport(RawAddress, BthhReportType, String),
    QaSendHidData(RawAddress, String),
    QaSendHidVirtualUnplug(RawAddress),

    // UHid callbacks
    UHidHfpOutputCallback(RawAddress, u8, u8),
    UHidTelephonyUseCallback(RawAddress, bool),

    // This message is sent when either HID, media, or GATT client, is disconnected.
    // Note that meida sends this when the profiles are disconnected as a whole, that is, it will
    // not be called when AVRCP is disconnected but not A2DP, as an example.
    ProfileDisconnected(RawAddress),
}

/// Returns a callable object that dispatches a BTIF callback to Message
///
/// The returned object would make sure the order of how the callbacks arrive the same as how they
/// goes to Message.
///
/// Example
/// ```ignore
/// // Create a dispatcher in btstack
/// let gatt_client_callbacks_dispatcher = topshim::gatt::GattClientCallbacksDispatcher {
///     dispatch: make_message_dispatcher(tx.clone(), Message::GattClient),
/// };
///
/// // Register the dispatcher to topshim
/// bt_topshim::topstack::get_dispatchers()
///     .lock()
///     .unwrap()
///     .set::<topshim::gatt::GattClientCb>(Arc::new(Mutex::new(gatt_client_callbacks_dispatcher)))
/// ```
pub(crate) fn make_message_dispatcher<F, Cb>(tx: Sender<Message>, f: F) -> Box<dyn Fn(Cb) + Send>
where
    Cb: Send + 'static,
    F: Fn(Cb) -> Message + Send + Copy + 'static,
{
    let async_mutex = Arc::new(tokio::sync::Mutex::new(()));
    let dispatch_queue = Arc::new(Mutex::new(std::collections::VecDeque::new()));

    Box::new(move |cb| {
        let tx = tx.clone();
        let async_mutex = async_mutex.clone();
        let dispatch_queue = dispatch_queue.clone();
        // Enqueue the callbacks at the synchronized block to ensure the order.
        dispatch_queue.lock().unwrap().push_back(cb);
        bt_topshim::topstack::get_runtime().spawn(async move {
            // Acquire the lock first to ensure |pop_front| and |tx.send| not
            // interrupted by the other async threads.
            let _guard = async_mutex.lock().await;
            // Consume exactly one callback.
            let cb = dispatch_queue.lock().unwrap().pop_front().unwrap();
            let _ = tx.send(f(cb)).await;
        });
    })
}

pub enum BluetoothAPI {
    Adapter,
    Admin,
    Battery,
    Media,
    Gatt,
}

/// Message types that are sent to the InterfaceManager's dispatch loop.
pub enum APIMessage {
    /// Indicates a subcomponent is ready to receive DBus messages.
    IsReady(BluetoothAPI),
    /// Indicates bluetooth is shutting down, so we remove all DBus endpoints.
    ShutDown,
}

/// Represents suspend mode of a module.
///
/// Being in suspend mode means that the module pauses some activities if required for suspend and
/// some subsequent API calls will be blocked with a retryable error.
#[derive(FromPrimitive, ToPrimitive, Debug, PartialEq, Clone)]
pub enum SuspendMode {
    Normal = 0,
    Suspending = 1,
    Suspended = 2,
    Resuming = 3,
}

/// Umbrella class for the Bluetooth stack.
pub struct Stack {}

impl Stack {
    /// Creates an mpsc channel for passing messages to the main dispatch loop.
    pub fn create_channel() -> (Sender<Message>, Receiver<Message>) {
        channel::<Message>(1)
    }

    /// Runs the main dispatch loop.
    pub async fn dispatch(
        mut rx: Receiver<Message>,
        tx: Sender<Message>,
        api_tx: Sender<APIMessage>,
        bluetooth: Arc<Mutex<Box<Bluetooth>>>,
        bluetooth_gatt: Arc<Mutex<Box<BluetoothGatt>>>,
        battery_service: Arc<Mutex<Box<BatteryService>>>,
        battery_manager: Arc<Mutex<Box<BatteryManager>>>,
        battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
        bluetooth_media: Arc<Mutex<Box<BluetoothMedia>>>,
        suspend: Arc<Mutex<Box<Suspend>>>,
        bluetooth_socketmgr: Arc<Mutex<Box<BluetoothSocketManager>>>,
        bluetooth_admin: Arc<Mutex<Box<BluetoothAdmin>>>,
        bluetooth_dis: Arc<Mutex<Box<DeviceInformation>>>,
        bluetooth_qa: Arc<Mutex<Box<BluetoothQA>>>,
    ) {
        loop {
            let m = rx.recv().await;

            if m.is_none() {
                eprintln!("Message dispatch loop quit");
                break;
            }

            match m.unwrap() {
                Message::InterfaceShutdown => {
                    let txl = api_tx.clone();
                    tokio::spawn(async move {
                        let _ = txl.send(APIMessage::ShutDown).await;
                    });
                }

                Message::AdapterShutdown => {
                    bluetooth_gatt.lock().unwrap().enable(false);
                    bluetooth.lock().unwrap().disable();
                }

                Message::Cleanup => {
                    bluetooth.lock().unwrap().cleanup();
                }

                Message::CleanupProfiles => {
                    bluetooth_media.lock().unwrap().cleanup();
                }

                Message::AdapterReady => {
                    // Initialize objects that need the adapter to be fully
                    // enabled before running.

                    // Init Media and pass it to Bluetooth.
                    bluetooth_media.lock().unwrap().initialize();
                    bluetooth.lock().unwrap().set_media(bluetooth_media.clone());
                    // Init Gatt and pass it to Bluetooth.
                    bluetooth_gatt.lock().unwrap().init_profiles(api_tx.clone());
                    bluetooth_gatt.lock().unwrap().enable(true);
                    bluetooth.lock().unwrap().set_gatt_and_init_scanner(bluetooth_gatt.clone());
                    // Init AdvertiseManager. It selects the stack per is_le_ext_adv_supported
                    // so it can only be done after Adapter is ready.
                    bluetooth_gatt.lock().unwrap().init_adv_manager(bluetooth.clone());
                    // Battery service and device information service are on top of Gatt.
                    // Only initialize them after GATT is ready.
                    bluetooth_dis.lock().unwrap().initialize();
                    battery_service.lock().unwrap().init();
                    // Initialize Admin. This toggles the enabled profiles.
                    bluetooth_admin.lock().unwrap().initialize(api_tx.clone());
                }

                Message::A2dp(a) => {
                    bluetooth_media.lock().unwrap().dispatch_a2dp_callbacks(a);
                }

                Message::Avrcp(av) => {
                    bluetooth_media.lock().unwrap().dispatch_avrcp_callbacks(av);
                }

                Message::Base(b) => {
                    dispatch_base_callbacks(bluetooth.lock().unwrap().as_mut(), b);
                }

                // When pairing is busy for any reason, the bond cannot be created.
                // Allow retries until it is ready for bonding.
                Message::CreateBondWithRetry(device, bt_transport, num_attempts, retry_delay) => {
                    if num_attempts == 0 {
                        continue;
                    }

                    let mut bt = bluetooth.lock().unwrap();
                    if !bt.is_pairing_busy() {
                        bt.create_bond(device, bt_transport);
                        continue;
                    }

                    let txl = tx.clone();
                    tokio::spawn(async move {
                        sleep(retry_delay).await;
                        let _ = txl
                            .send(Message::CreateBondWithRetry(
                                device,
                                bt_transport,
                                num_attempts - 1,
                                retry_delay,
                            ))
                            .await;
                    });
                }

                Message::GattClient(m) => {
                    dispatch_gatt_client_callbacks(bluetooth_gatt.lock().unwrap().as_mut(), m);
                }

                Message::GattServer(m) => {
                    dispatch_gatt_server_callbacks(bluetooth_gatt.lock().unwrap().as_mut(), m);
                }

                Message::LeAudioClient(a) => {
                    bluetooth_media.lock().unwrap().dispatch_le_audio_callbacks(a);
                }

                Message::VolumeControl(a) => {
                    bluetooth_media.lock().unwrap().dispatch_vc_callbacks(a);
                }

                Message::CsisClient(a) => {
                    bluetooth_media.lock().unwrap().dispatch_csis_callbacks(a);
                }

                Message::LeScanner(m) => {
                    dispatch_le_scanner_callbacks(bluetooth_gatt.lock().unwrap().as_mut(), m);
                }

                Message::LeScannerInband(m) => {
                    dispatch_le_scanner_inband_callbacks(
                        bluetooth_gatt.lock().unwrap().as_mut(),
                        m,
                    );
                }

                Message::LeAdvInband(m) => {
                    debug!("Received LeAdvInband message: {:?}. This is unexpected!", m);
                }

                Message::LeAdv(m) => {
                    dispatch_le_adv_callbacks(bluetooth_gatt.lock().unwrap().as_mut(), m);
                }

                Message::Hfp(hf) => {
                    bluetooth_media.lock().unwrap().dispatch_hfp_callbacks(hf);
                }

                Message::HidHost(h) => {
                    dispatch_hid_host_callbacks(bluetooth.lock().unwrap().as_mut(), h);
                }

                Message::Sdp(s) => {
                    dispatch_sdp_callbacks(bluetooth.lock().unwrap().as_mut(), s);
                }

                Message::Media(action) => {
                    bluetooth_media.lock().unwrap().dispatch_media_actions(action);
                }

                Message::MediaCallbackDisconnected(cb_id) => {
                    bluetooth_media.lock().unwrap().remove_callback(cb_id);
                }

                Message::TelephonyCallbackDisconnected(cb_id) => {
                    bluetooth_media.lock().unwrap().remove_telephony_callback(cb_id);
                }

                Message::AdapterCallbackDisconnected(id) => {
                    bluetooth.lock().unwrap().adapter_callback_disconnected(id);
                }

                Message::ConnectionCallbackDisconnected(id) => {
                    bluetooth.lock().unwrap().connection_callback_disconnected(id);
                }

                Message::AdapterActions(action) => {
                    bluetooth.lock().unwrap().handle_actions(action);
                }

                // Any service needing an updated list of devices can have an update method
                // triggered from here rather than needing a reference to Bluetooth.
                Message::OnDeviceConnectionOrBondStateChanged(
                    addr,
                    _bredr_acl_state,
                    ble_acl_state,
                    bond_state,
                    _transport,
                ) => {
                    if ble_acl_state == BtAclState::Connected && bond_state == BtBondState::Bonded {
                        info!("BAS: Connecting to {}", DisplayAddress(&addr));
                        battery_service.lock().unwrap().init_device(addr);
                    }
                }

                Message::SuspendActions(action) => {
                    suspend.lock().unwrap().handle_action(action);
                }

                Message::ScannerCallbackDisconnected(id) => {
                    bluetooth_gatt.lock().unwrap().remove_scanner_callback(id);
                }

                Message::AdvertiserCallbackDisconnected(id) => {
                    bluetooth_gatt.lock().unwrap().remove_adv_callback(id);
                }

                Message::AdvertiserActions(action) => {
                    bluetooth_gatt.lock().unwrap().handle_adv_action(action);
                }

                Message::SocketManagerActions(action) => {
                    bluetooth_socketmgr.lock().unwrap().handle_actions(action);
                }
                Message::SocketManagerCallbackDisconnected(id) => {
                    bluetooth_socketmgr.lock().unwrap().remove_callback(id);
                }
                Message::BatteryProviderManagerBatteryUpdated(remote_address, battery_set) => {
                    battery_manager
                        .lock()
                        .unwrap()
                        .handle_battery_updated(remote_address, battery_set);
                }
                Message::BatteryProviderManagerCallbackDisconnected(id) => {
                    battery_provider_manager.lock().unwrap().remove_battery_provider_callback(id);
                }
                Message::BatteryServiceCallbackDisconnected(id) => {
                    battery_service.lock().unwrap().remove_callback(id);
                }
                Message::BatteryService(action) => {
                    battery_service.lock().unwrap().handle_action(action);
                }
                Message::BatteryServiceRefresh => {
                    battery_service.lock().unwrap().refresh_all_devices();
                }
                Message::BatteryManagerCallbackDisconnected(id) => {
                    battery_manager.lock().unwrap().remove_callback(id);
                }
                Message::GattActions(action) => {
                    bluetooth_gatt.lock().unwrap().handle_action(action);
                }
                Message::GattClientCallbackDisconnected(id) => {
                    bluetooth_gatt.lock().unwrap().remove_client_callback(id);
                }
                Message::GattServerCallbackDisconnected(id) => {
                    bluetooth_gatt.lock().unwrap().remove_server_callback(id);
                }
                Message::AdminCallbackDisconnected(id) => {
                    bluetooth_admin.lock().unwrap().unregister_admin_policy_callback(id);
                }
                Message::AdminActions(action) => {
                    bluetooth_admin.lock().unwrap().handle_action(action);
                }
                Message::HidHostEnable => {
                    bluetooth.lock().unwrap().enable_hidhost();
                }
                Message::Dis(callback) => {
                    bluetooth_dis.lock().unwrap().handle_callbacks(&callback);
                }
                Message::DisconnectDevice(addr) => {
                    bluetooth.lock().unwrap().disconnect_all_enabled_profiles(addr);
                }
                // Qualification Only
                Message::QaAddMediaPlayer(name, browsing_supported) => {
                    bluetooth_media.lock().unwrap().add_player(name, browsing_supported);
                }
                Message::QaRfcommSendMsc(dlci, addr) => {
                    bluetooth_socketmgr.lock().unwrap().rfcomm_send_msc(dlci, addr);
                }
                Message::QaCallbackDisconnected(id) => {
                    bluetooth_qa.lock().unwrap().unregister_qa_callback(id);
                }
                Message::QaFetchDiscoverableMode => {
                    let mode = bluetooth.lock().unwrap().get_discoverable_mode_internal();
                    bluetooth_qa.lock().unwrap().on_fetch_discoverable_mode_completed(mode);
                }
                Message::QaFetchConnectable => {
                    let connectable = bluetooth.lock().unwrap().get_connectable_internal();
                    bluetooth_qa.lock().unwrap().on_fetch_connectable_completed(connectable);
                }
                Message::QaSetConnectable(mode) => {
                    let succeed = bluetooth.lock().unwrap().set_connectable_internal(mode);
                    bluetooth_qa.lock().unwrap().on_set_connectable_completed(succeed);
                }
                Message::QaFetchAlias => {
                    let alias = bluetooth.lock().unwrap().get_alias_internal();
                    bluetooth_qa.lock().unwrap().on_fetch_alias_completed(alias);
                }
                Message::QaGetHidReport(addr, report_type, report_id) => {
                    let status = bluetooth.lock().unwrap().get_hid_report_internal(
                        addr,
                        report_type,
                        report_id,
                    );
                    bluetooth_qa.lock().unwrap().on_get_hid_report_completed(status);
                }
                Message::QaSetHidReport(addr, report_type, report) => {
                    let status = bluetooth.lock().unwrap().set_hid_report_internal(
                        addr,
                        report_type,
                        report,
                    );
                    bluetooth_qa.lock().unwrap().on_set_hid_report_completed(status);
                }
                Message::QaSendHidData(addr, data) => {
                    let status = bluetooth.lock().unwrap().send_hid_data_internal(addr, data);
                    bluetooth_qa.lock().unwrap().on_send_hid_data_completed(status);
                }
                Message::QaSendHidVirtualUnplug(addr) => {
                    let status = bluetooth.lock().unwrap().send_hid_virtual_unplug_internal(addr);
                    bluetooth_qa.lock().unwrap().on_send_hid_virtual_unplug_completed(status);
                }

                // UHid callbacks
                Message::UHidHfpOutputCallback(addr, id, data) => {
                    bluetooth_media
                        .lock()
                        .unwrap()
                        .dispatch_uhid_hfp_output_callback(addr, id, data);
                }

                Message::UHidTelephonyUseCallback(addr, state) => {
                    bluetooth_media
                        .lock()
                        .unwrap()
                        .dispatch_uhid_telephony_use_callback(addr, state);
                }

                Message::ProfileDisconnected(addr) => {
                    let bas_app_uuid =
                        Uuid::from_string(String::from(BATTERY_SERVICE_GATT_CLIENT_APP_ID))
                            .expect("BAS Uuid failed to be parsed");
                    // Ideally we would also check that there are no open sockets for this device
                    // but Floss does not manage socket state so there is no reasonable way for us
                    // to know whether a socket is open or not.
                    if bluetooth_gatt.lock().unwrap().get_connected_applications(&addr)
                        == vec![bas_app_uuid]
                        && !bluetooth.lock().unwrap().is_initiated_hh_connection(&addr)
                        && bluetooth_media.lock().unwrap().get_connected_profiles(&addr).is_empty()
                    {
                        info!(
                            "BAS: Disconnecting from {} since it's the last active profile",
                            DisplayAddress(&addr)
                        );
                        battery_service.lock().unwrap().drop_device(addr);
                    }
                }
            }
        }
    }
}

/// Signifies that the object may be a proxy to a remote RPC object.
///
/// An object that implements RPCProxy trait signifies that the object may be a proxy to a remote
/// RPC object. Therefore the object may be disconnected and thus should implement
/// `register_disconnect` to let others observe the disconnection event.
pub trait RPCProxy {
    /// Registers disconnect observer that will be notified when the remote object is disconnected.
    fn register_disconnect(&mut self, _f: Box<dyn Fn(u32) + Send>) -> u32 {
        0
    }

    /// Returns the ID of the object. For example this would be an object path in D-Bus RPC.
    fn get_object_id(&self) -> String {
        String::from("")
    }

    /// Unregisters callback with this id.
    fn unregister(&mut self, _id: u32) -> bool {
        false
    }

    /// Makes this object available for remote call.
    fn export_for_rpc(self: Box<Self>) {}
}
