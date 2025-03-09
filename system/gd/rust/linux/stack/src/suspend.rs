//! Suspend/Resume API.

use crate::bluetooth::{
    AdapterActions, Bluetooth, BluetoothDevice, BtifBluetoothCallbacks, IBluetooth,
    IBluetoothConnectionCallback,
};
use crate::bluetooth_media::BluetoothMedia;
use crate::callbacks::Callbacks;
use crate::{BluetoothGatt, Message, RPCProxy};
use bt_topshim::btif::{BluetoothInterface, BtStatus, RawAddress};
use bt_topshim::metrics;
use log::warn;
use num_derive::{FromPrimitive, ToPrimitive};
use std::collections::HashSet;
use std::iter::FromIterator;
use std::sync::{Arc, Mutex, MutexGuard};
use tokio::sync::mpsc::Sender;
use tokio::task::JoinHandle;
use tokio::time;
use tokio::time::Duration;

use bt_utils::socket::{BtSocket, HciChannels, MgmtCommand, HCI_DEV_NONE};

/// Defines the Suspend/Resume API.
///
/// This API is exposed by `btadapterd` and independent of the suspend/resume detection mechanism
/// which depends on the actual operating system the daemon runs on. Possible clients of this API
/// include `btmanagerd` with Chrome OS `powerd` integration, `btmanagerd` with systemd Inhibitor
/// interface, or any script hooked to suspend/resume events.
pub trait ISuspend {
    /// Adds an observer to suspend events.
    ///
    /// Returns true if the callback can be registered.
    fn register_callback(&mut self, callback: Box<dyn ISuspendCallback + Send>) -> bool;

    /// Removes an observer to suspend events.
    ///
    /// Returns true if the callback can be removed, false if `callback_id` is not recognized.
    fn unregister_callback(&mut self, callback_id: u32) -> bool;

    /// Prepares the stack for suspend, identified by `suspend_id`.
    ///
    /// Returns a positive number identifying the suspend if it can be started. If there is already
    /// a suspend, that active suspend id is returned.
    fn suspend(&mut self, suspend_type: SuspendType, suspend_id: i32);

    /// Undoes previous suspend preparation identified by `suspend_id`.
    ///
    /// Returns true if suspend can be resumed, and false if there is no suspend to resume.
    fn resume(&mut self) -> bool;
}

/// Suspend events.
pub trait ISuspendCallback: RPCProxy {
    /// Triggered when a callback is registered and given an identifier `callback_id`.
    fn on_callback_registered(&mut self, callback_id: u32);

    /// Triggered when the stack is ready for suspend and tell the observer the id of the suspend.
    fn on_suspend_ready(&mut self, suspend_id: i32);

    /// Triggered when the stack has resumed the previous suspend.
    fn on_resumed(&mut self, suspend_id: i32);
}

/// When we resume, we will want to reconnect audio devices that were previously connected.
/// However, we will need to delay a few seconds to avoid co-ex issues with Wi-Fi reconnection.
const RECONNECT_AUDIO_ON_RESUME_DELAY_MS: u64 = 3000;

/// Delay sending suspend ready signal by some time because HCI commands are async and we could
/// still receive some commands/events after all LibBluetooth functions have returned.
const SUSPEND_READY_DELAY_MS: u64 = 100;

fn notify_suspend_state(hci_index: u16, suspended: bool) {
    log::debug!("Notify kernel suspend status: {} for hci{}", suspended, hci_index);
    let mut btsock = BtSocket::new();
    match btsock.open() {
        -1 => {
            panic!(
                "Bluetooth socket unavailable (errno {}). Try loading the kernel module first.",
                std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
            );
        }
        x => log::debug!("notify suspend Socket open at fd: {}", x),
    }
    // Bind to control channel (which is used for mgmt commands). We provide
    // HCI_DEV_NONE because we don't actually need a valid HCI dev for some MGMT commands.
    match btsock.bind_channel(HciChannels::Control, HCI_DEV_NONE) {
        -1 => {
            panic!(
                "Failed to bind control channel with errno={}",
                std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
            );
        }
        _ => (),
    };

    let command = MgmtCommand::FlossNotifySuspendState(hci_index, suspended);
    let bytes_written = btsock.write_mgmt_packet(command.into());
    if bytes_written <= 0 {
        log::error!("Failed to notify suspend state on hci:{} to {}", hci_index, suspended);
    }
}

pub enum SuspendActions {
    CallbackRegistered(u32),
    CallbackDisconnected(u32),
    SuspendReady(i32),
    ResumeReady(i32),
    AudioReconnectOnResumeComplete,
    DeviceDisconnected(RawAddress),
}

#[derive(Debug, FromPrimitive, ToPrimitive)]
#[repr(u32)]
pub enum SuspendType {
    NoWakesAllowed,
    AllowWakeFromHid,
    Other,
}

struct SuspendState {
    suspend_expected: bool,
    suspend_id: Option<i32>,
    wake_allowed: bool,

    disconnect_expected: HashSet<RawAddress>,
    disconnect_timeout_timer: Option<JoinHandle<()>>,

    delay_timer: Option<JoinHandle<()>>,
}

impl SuspendState {
    fn new() -> SuspendState {
        Self {
            suspend_expected: false,
            suspend_id: None,
            wake_allowed: false,
            disconnect_expected: HashSet::default(),
            disconnect_timeout_timer: None,
            delay_timer: None,
        }
    }

    // The tasks should remove their timer when they are done, so if all are None then we're ready.
    fn ready_to_suspend(&self) -> bool {
        self.delay_timer.is_none() && self.disconnect_timeout_timer.is_none()
    }
}

/// Implementation of the suspend API.
pub struct Suspend {
    bt: Arc<Mutex<Box<Bluetooth>>>,
    intf: Arc<Mutex<BluetoothInterface>>,
    gatt: Arc<Mutex<Box<BluetoothGatt>>>,
    media: Arc<Mutex<Box<BluetoothMedia>>>,
    tx: Sender<Message>,
    callbacks: Callbacks<dyn ISuspendCallback + Send>,

    /// This list keeps track of audio devices that had an audio profile before
    /// suspend so that we can attempt to connect after suspend.
    audio_reconnect_list: Vec<BluetoothDevice>,

    /// Active reconnection attempt after resume.
    audio_reconnect_joinhandle: Option<JoinHandle<()>>,

    suspend_state: Arc<Mutex<SuspendState>>,
}

impl Suspend {
    pub fn new(
        bt: Arc<Mutex<Box<Bluetooth>>>,
        intf: Arc<Mutex<BluetoothInterface>>,
        gatt: Arc<Mutex<Box<BluetoothGatt>>>,
        media: Arc<Mutex<Box<BluetoothMedia>>>,
        tx: Sender<Message>,
    ) -> Suspend {
        bt.lock()
            .unwrap()
            .register_connection_callback(Box::new(BluetoothConnectionCallbacks::new(tx.clone())));
        Self {
            bt,
            intf,
            gatt,
            media,
            tx: tx.clone(),
            callbacks: Callbacks::new(tx.clone(), |id| {
                Message::SuspendActions(SuspendActions::CallbackDisconnected(id))
            }),
            audio_reconnect_list: Vec::new(),
            audio_reconnect_joinhandle: None,
            suspend_state: Arc::new(Mutex::new(SuspendState::new())),
        }
    }

    pub(crate) fn handle_action(&mut self, action: SuspendActions) {
        match action {
            SuspendActions::CallbackRegistered(id) => {
                self.callback_registered(id);
            }
            SuspendActions::CallbackDisconnected(id) => {
                self.remove_callback(id);
            }
            SuspendActions::SuspendReady(suspend_id) => {
                self.suspend_ready(suspend_id);
            }
            SuspendActions::ResumeReady(suspend_id) => {
                self.resume_ready(suspend_id);
            }
            SuspendActions::AudioReconnectOnResumeComplete => {
                self.audio_reconnect_complete();
            }
            SuspendActions::DeviceDisconnected(addr) => {
                self.device_disconnected(addr);
            }
        }
    }

    fn callback_registered(&mut self, id: u32) {
        match self.callbacks.get_by_id_mut(id) {
            Some(callback) => callback.on_callback_registered(id),
            None => warn!("Suspend callback {} does not exist", id),
        }
    }

    fn remove_callback(&mut self, id: u32) -> bool {
        self.callbacks.remove_callback(id)
    }

    fn suspend_ready(&mut self, suspend_id: i32) {
        let mut suspend_state = self.suspend_state.lock().unwrap();
        if !suspend_state.ready_to_suspend() {
            return;
        }
        if !suspend_state.suspend_expected {
            // We might already send out a ready.
            return;
        }
        suspend_state.suspend_expected = false;
        let hci_index = self.bt.lock().unwrap().get_hci_index();
        notify_suspend_state(hci_index, true);
        self.callbacks.for_all_callbacks(|callback| {
            callback.on_suspend_ready(suspend_id);
        });
    }

    fn resume_ready(&mut self, suspend_id: i32) {
        self.callbacks.for_all_callbacks(|callback| {
            callback.on_resumed(suspend_id);
        });
    }

    /// On resume, we attempt to reconnect to any audio devices connected during suspend.
    /// This marks this attempt as completed and we should clear the pending reconnects here.
    fn audio_reconnect_complete(&mut self) {
        self.audio_reconnect_list.clear();
        self.audio_reconnect_joinhandle = None;
    }

    fn device_disconnected(&mut self, addr: RawAddress) {
        let mut suspend_state = self.suspend_state.lock().unwrap();
        if !suspend_state.disconnect_expected.remove(&addr) {
            // Not interested device, or we are not suspending.
            return;
        }
        if !suspend_state.disconnect_expected.is_empty() {
            return;
        }
        if let Some(h) = suspend_state.disconnect_timeout_timer.take() {
            h.abort();
        }
        Self::all_acls_disconnected(
            self.tx.clone(),
            &mut suspend_state,
            self.suspend_state.clone(),
            self.intf.clone(),
        );
    }

    /// Continues the suspend process after all ACLs are disconnected.
    ///
    /// Two SuspendState are passed as arguments here because we want the mutex to be held during
    /// the whole device disconnect process. Thus, the caller of this function should provide a
    /// MutexGuard, while an Arc is also needed for this function to compose an async task.
    ///
    /// After ReentrantLock (https://github.com/rust-lang/rust/issues/121440) is supported, we
    /// shall replace them with a single Arc<ReentrantLock<SuspendState>>.
    fn all_acls_disconnected(
        tx: Sender<Message>,
        suspend_state: &mut MutexGuard<SuspendState>,
        suspend_state_cloned: Arc<Mutex<SuspendState>>,
        intf: Arc<Mutex<BluetoothInterface>>,
    ) {
        let suspend_id = suspend_state
            .suspend_id
            .expect("life cycle of suspend_id must be longer than disconnect_timeout_timer");
        let wake_allowed = suspend_state.wake_allowed;
        suspend_state.disconnect_timeout_timer = Some(tokio::spawn(async move {
            if wake_allowed {
                intf.lock().unwrap().allow_wake_by_hid();
                // Allow wake is async. Wait for a little while.
                time::sleep(Duration::from_millis(SUSPEND_READY_DELAY_MS)).await;
            }
            suspend_state_cloned.lock().unwrap().disconnect_timeout_timer = None;
            let _result =
                tx.send(Message::SuspendActions(SuspendActions::SuspendReady(suspend_id))).await;
        }));
    }

    fn get_connected_audio_devices(&self) -> Vec<BluetoothDevice> {
        let bonded_connected = self.bt.lock().unwrap().get_bonded_and_connected_devices();
        self.media.lock().unwrap().filter_to_connected_audio_devices_from(&bonded_connected)
    }
}

impl ISuspend for Suspend {
    fn register_callback(&mut self, callback: Box<dyn ISuspendCallback + Send>) -> bool {
        let id = self.callbacks.add_callback(callback);

        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _result =
                tx.send(Message::SuspendActions(SuspendActions::CallbackRegistered(id))).await;
        });

        true
    }

    fn unregister_callback(&mut self, callback_id: u32) -> bool {
        self.remove_callback(callback_id)
    }

    fn suspend(&mut self, suspend_type: SuspendType, suspend_id: i32) {
        let mut suspend_state = self.suspend_state.lock().unwrap();
        // Set suspend state as true, prevent an early resume.
        suspend_state.suspend_expected = true;
        suspend_state.suspend_id = Some(suspend_id);
        // Treat Other the same as AllowWakeFromHid
        suspend_state.wake_allowed =
            matches!(suspend_type, SuspendType::AllowWakeFromHid | SuspendType::Other);

        self.bt.lock().unwrap().scan_mode_enter_suspend();
        self.intf.lock().unwrap().clear_event_filter();
        self.intf.lock().unwrap().clear_filter_accept_list();

        self.bt.lock().unwrap().discovery_enter_suspend();
        self.gatt.lock().unwrap().advertising_enter_suspend();
        self.gatt.lock().unwrap().scan_enter_suspend();

        // Track connected audio devices and queue them for reconnect on resume.
        // If we still have the previous reconnect list left-over, do not try
        // to collect a new list here.
        if self.audio_reconnect_list.is_empty() {
            self.audio_reconnect_list = self.get_connected_audio_devices();
        }

        // Cancel any active reconnect task.
        if let Some(joinhandle) = &self.audio_reconnect_joinhandle {
            joinhandle.abort();
            self.audio_reconnect_joinhandle = None;
        }

        // Now we have some async tasks to do and need to wait for some events.
        // For each task we need to schedule a timeout timer to ensure we suspend eventually.
        // When a task is done, it should remove the timer and send a SuspendReady event.
        // |ready_to_suspend| shall check that all tasks have done (removed their timer).

        // This doesn't do anything but simply wait for a short delay to ensure the above functions
        // have finished (LibBluetooth may leave some async tasks).
        if let Some(h) = suspend_state.delay_timer.take() {
            log::warn!("Suspend: Found a leftover timer for delay task");
            h.abort();
        }
        let tx = self.tx.clone();
        let suspend_state_cloned = self.suspend_state.clone();
        suspend_state.delay_timer = Some(tokio::spawn(async move {
            time::sleep(Duration::from_millis(SUSPEND_READY_DELAY_MS)).await;
            suspend_state_cloned.lock().unwrap().delay_timer = None;
            let _result =
                tx.send(Message::SuspendActions(SuspendActions::SuspendReady(suspend_id))).await;
        }));

        // Disconnect all ACLs and wait until all devices have disconnected.
        if let Some(h) = suspend_state.disconnect_timeout_timer.take() {
            log::warn!("Suspend: Found a leftover timer for disconnect");
            h.abort();
        }
        suspend_state.disconnect_expected = HashSet::from_iter(
            self.bt.lock().unwrap().get_connected_devices().iter().map(|d| d.address),
        );
        let tx = self.tx.clone();
        let suspend_state_cloned = self.suspend_state.clone();
        let intf_cloned = self.intf.clone();
        if suspend_state.disconnect_expected.is_empty() {
            // No need to set a timeout timer as no disconnection is expected.
            Self::all_acls_disconnected(tx, &mut suspend_state, suspend_state_cloned, intf_cloned);
        } else {
            self.intf.lock().unwrap().disconnect_all_acls();
            suspend_state.disconnect_timeout_timer = Some(tokio::spawn(async move {
                time::sleep(Duration::from_millis(2000)).await;
                log::error!("Suspend disconnect did not complete in 2s, continuing anyway.");
                let mut suspend_state = suspend_state_cloned.lock().unwrap();
                // Cleanup disconnect_expected so |device_disconnected| won't be triggered.
                suspend_state.disconnect_expected = HashSet::default();
                // Continue the suspend. There might be some disconnection events later and if the
                // device is not in the lid-closed state, the device might be awaken. This shall be
                // a really rare case and it's hard to handle as setting a event mask could break
                // the state machine in LibBluetooth.
                // We could consider increase the timeout in the future if this happens too often.
                Self::all_acls_disconnected(
                    tx,
                    &mut suspend_state,
                    suspend_state_cloned.clone(),
                    intf_cloned,
                );
            }));
        }
    }

    fn resume(&mut self) -> bool {
        let mut suspend_state = self.suspend_state.lock().unwrap();
        // Suspend is not ready (e.g. aborted early), delay cleanup after SuspendReady.
        if suspend_state.suspend_expected {
            log::error!("Suspend is expected but not ready, abort resume.");
            return false;
        }

        // Suspend ID state 0: NoRecord, 1: Recorded
        let suspend_id = match suspend_state.suspend_id {
            None => {
                log::error!("No suspend id saved at resume.");
                metrics::suspend_complete_state(0);
                // If no suspend id is saved here, it means floss did not receive the
                // SuspendImminent signal and as a result, the suspend flow was not run.
                // Skip the resume flow and return after logging the metrics.
                return true;
            }
            Some(id) => {
                metrics::suspend_complete_state(1);
                id
            }
        };

        let hci_index = self.bt.lock().unwrap().get_hci_index();
        notify_suspend_state(hci_index, false);

        self.intf.lock().unwrap().set_default_event_mask_except(0u64, 0u64);

        // Restore event filter and accept list to normal.
        self.intf.lock().unwrap().clear_event_filter();
        self.intf.lock().unwrap().clear_filter_accept_list();
        self.intf.lock().unwrap().restore_filter_accept_list();
        self.bt.lock().unwrap().scan_mode_exit_suspend();

        if !self.audio_reconnect_list.is_empty() {
            let reconnect_list = self.audio_reconnect_list.clone();
            let txl = self.tx.clone();

            // Cancel any existing reconnect attempt.
            if let Some(joinhandle) = &self.audio_reconnect_joinhandle {
                joinhandle.abort();
                self.audio_reconnect_joinhandle = None;
            }

            self.audio_reconnect_joinhandle = Some(tokio::spawn(async move {
                // Wait a few seconds to avoid co-ex issues with wi-fi.
                time::sleep(Duration::from_millis(RECONNECT_AUDIO_ON_RESUME_DELAY_MS)).await;

                // Queue up connections.
                for device in reconnect_list {
                    let _unused: Option<()> = txl
                        .send(Message::AdapterActions(AdapterActions::ConnectAllProfiles(device)))
                        .await
                        .ok();
                }

                // Mark that we're done.
                let _unused: Option<()> = txl
                    .send(Message::SuspendActions(SuspendActions::AudioReconnectOnResumeComplete))
                    .await
                    .ok();
            }));
        }

        self.bt.lock().unwrap().discovery_exit_suspend();
        self.gatt.lock().unwrap().advertising_exit_suspend();
        self.gatt.lock().unwrap().scan_exit_suspend();

        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _result =
                tx.send(Message::SuspendActions(SuspendActions::ResumeReady(suspend_id))).await;
        });

        true
    }
}

struct BluetoothConnectionCallbacks {
    tx: Sender<Message>,
}

impl BluetoothConnectionCallbacks {
    fn new(tx: Sender<Message>) -> Self {
        Self { tx }
    }
}

impl IBluetoothConnectionCallback for BluetoothConnectionCallbacks {
    fn on_device_connected(&mut self, _device: BluetoothDevice) {}

    fn on_device_disconnected(&mut self, device: BluetoothDevice) {
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let _result = tx
                .send(Message::SuspendActions(SuspendActions::DeviceDisconnected(device.address)))
                .await;
        });
    }

    fn on_device_connection_failed(&mut self, _device: BluetoothDevice, _status: BtStatus) {}
}

impl RPCProxy for BluetoothConnectionCallbacks {
    fn get_object_id(&self) -> String {
        "Bluetooth Connection Callback".to_string()
    }
}
