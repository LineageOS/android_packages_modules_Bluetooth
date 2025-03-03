use crate::btif::{BluetoothInterface, RawAddress, ToggleableProfile, Uuid};
use crate::topstack::get_dispatchers;

use std::fmt::{Debug, Formatter};
use std::sync::{Arc, Mutex};
use topshim_macros::{cb_variant, log_args, profile_enabled_or};

use log::warn;

#[cxx::bridge(namespace = bluetooth::topshim::rust)]
pub mod ffi {
    unsafe extern "C++" {
        include!("types/raw_address.h");
        include!("types/bluetooth/uuid.h");
        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;
        #[namespace = "bluetooth"]
        type Uuid = crate::btif::Uuid;
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtCsisConnectionState {
        Disconnected = 0,
        Connecting,
        Connected,
        Disconnecting,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtCsisGroupLockStatus {
        Success = 0,
        FailedInvalidGroup,
        FailedGroupEmpty,
        FailedGroupNotConnected,
        FailedLockedByOther,
        FailedOtherReason,
        LockedGroupMemberLost,
    }

    unsafe extern "C++" {
        include!("csis/csis_shim.h");

        type CsisClientIntf;

        unsafe fn GetCsisClientProfile(btif: *const u8) -> UniquePtr<CsisClientIntf>;

        fn init(self: Pin<&mut CsisClientIntf>);
        fn connect(self: Pin<&mut CsisClientIntf>, addr: RawAddress);
        fn disconnect(self: Pin<&mut CsisClientIntf>, addr: RawAddress);
        fn lock_group(self: Pin<&mut CsisClientIntf>, group_id: i32, lock: bool);
        fn remove_device(self: Pin<&mut CsisClientIntf>, addr: RawAddress);
        fn cleanup(self: Pin<&mut CsisClientIntf>);
    }

    extern "Rust" {
        fn csis_connection_state_callback(addr: RawAddress, state: BtCsisConnectionState);
        fn csis_device_available_callback(
            addr: RawAddress,
            group_id: i32,
            group_size: i32,
            rank: i32,
            uuid: Uuid,
        );
        fn csis_set_member_available_callback(addr: RawAddress, group_id: i32);
        fn csis_group_lock_changed_callback(
            group_id: i32,
            locked: bool,
            status: BtCsisGroupLockStatus,
        );
    }
}

pub type BtCsisConnectionState = ffi::BtCsisConnectionState;
pub type BtCsisGroupLockStatus = ffi::BtCsisGroupLockStatus;

#[derive(Debug)]
pub enum CsisClientCallbacks {
    ConnectionState(RawAddress, BtCsisConnectionState),
    DeviceAvailable(RawAddress, i32, i32, i32, Uuid),
    SetMemberAvailable(RawAddress, i32),
    GroupLockChanged(i32, bool, BtCsisGroupLockStatus),
}

pub struct CsisClientCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(CsisClientCallbacks) + Send>,
}

impl Debug for CsisClientCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result<(), std::fmt::Error> {
        write!(f, "CsisClientCallbacksDispatcher {{}}")
    }
}

type CsisClientCb = Arc<Mutex<CsisClientCallbacksDispatcher>>;

cb_variant!(CsisClientCb,
            csis_connection_state_callback -> CsisClientCallbacks::ConnectionState,
            RawAddress, BtCsisConnectionState);

cb_variant!(CsisClientCb,
            csis_device_available_callback -> CsisClientCallbacks::DeviceAvailable,
            RawAddress, i32, i32, i32, Uuid);

cb_variant!(CsisClientCb,
            csis_set_member_available_callback -> CsisClientCallbacks::SetMemberAvailable,
            RawAddress, i32);

cb_variant!(CsisClientCb,
            csis_group_lock_changed_callback -> CsisClientCallbacks::GroupLockChanged,
            i32, bool, BtCsisGroupLockStatus);

pub struct CsisClient {
    internal: cxx::UniquePtr<ffi::CsisClientIntf>,
    is_init: bool,
    is_enabled: bool,
}

// For *const u8 opaque btif
// SAFETY: `CsisClientIntf` is thread-safe to make calls from.
unsafe impl Send for CsisClient {}

impl ToggleableProfile for CsisClient {
    fn is_enabled(&self) -> bool {
        self.is_enabled
    }

    fn enable(&mut self) -> bool {
        if self.is_enabled {
            warn!("CsisClient is already enabled.");
            return false;
        }

        self.internal.pin_mut().init();
        self.is_enabled = true;
        true
    }

    #[profile_enabled_or(false)]
    fn disable(&mut self) -> bool {
        if !self.is_enabled {
            warn!("CsisClient is already disabled.");
            return false;
        }

        self.internal.pin_mut().cleanup();
        self.is_enabled = false;
        true
    }
}

impl CsisClient {
    #[log_args]
    pub fn new(intf: &BluetoothInterface) -> CsisClient {
        // SAFETY: `intf.as_raw_ptr()` is a valid pointer to a `BluetoothInterface`
        let csis_if: cxx::UniquePtr<ffi::CsisClientIntf> =
            unsafe { ffi::GetCsisClientProfile(intf.as_raw_ptr()) };

        CsisClient { internal: csis_if, is_init: false, is_enabled: false }
    }

    #[log_args]
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    // `internal.init` is invoked during `ToggleableProfile::enable`
    #[log_args]
    pub fn initialize(&mut self, callbacks: CsisClientCallbacksDispatcher) -> bool {
        if self.is_init {
            warn!("CsisClient has already been initialized");
            return false;
        }

        if get_dispatchers().lock().unwrap().set::<CsisClientCb>(Arc::new(Mutex::new(callbacks))) {
            panic!("Tried to set dispatcher for CsisClient callbacks while it already exists");
        }

        self.is_init = true;

        true
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn cleanup(&mut self) {
        self.internal.pin_mut().cleanup();
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn connect(&mut self, addr: RawAddress) {
        self.internal.pin_mut().connect(addr);
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn disconnect(&mut self, addr: RawAddress) {
        self.internal.pin_mut().disconnect(addr);
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn lock_group(&mut self, group_id: i32, lock: bool) {
        self.internal.pin_mut().lock_group(group_id, lock);
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn remove_device(&mut self, addr: RawAddress) {
        self.internal.pin_mut().remove_device(addr);
    }
}
