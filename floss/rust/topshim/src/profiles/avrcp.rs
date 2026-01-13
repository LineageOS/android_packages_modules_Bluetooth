use crate::btif::{BluetoothInterface, BtStatus, RawAddress, ToggleableProfile};
use crate::topstack::get_dispatchers;

use std::fmt::{Debug, Formatter, Result};
use std::sync::{Arc, Mutex};
use topshim_macros::{cb_variant, log_args, profile_enabled_or};

use log::warn;

#[derive(Debug, Default)]
pub struct PlayerMetadata {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub length_us: i64,
}

#[cxx::bridge(namespace = bluetooth::topshim::rust)]
pub mod ffi {
    unsafe extern "C++" {
        include!("bluetooth/types/address.h");
        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;
    }

    unsafe extern "C++" {
        include!("topshim/btav/btav_shim.h");

        type BtIntf = crate::btif::ffi::BtIntf;

        type AvrcpIntf;

        fn GetAvrcpProfile(btif: &BtIntf) -> UniquePtr<AvrcpIntf>;

        fn init(self: Pin<&mut AvrcpIntf>);
        fn cleanup(self: Pin<&mut AvrcpIntf>);
        fn connect(self: Pin<&mut AvrcpIntf>, bt_addr: RawAddress) -> u32;
        fn disconnect(self: Pin<&mut AvrcpIntf>, bt_addr: RawAddress) -> u32;
        fn set_volume(self: Pin<&mut AvrcpIntf>, bt_addr: RawAddress, volume: i8);
        fn set_playback_status(self: Pin<&mut AvrcpIntf>, status: &String);
        fn set_position(self: Pin<&mut AvrcpIntf>, position_us: i64);
        fn set_metadata(
            self: Pin<&mut AvrcpIntf>,
            title: &String,
            artist: &String,
            album: &String,
            length_us: i64,
        );
        fn add_player(self: Pin<&mut AvrcpIntf>, name: &String, browsing_supported: bool) -> u16;

    }
    extern "Rust" {
        fn avrcp_device_connected(addr: RawAddress, absolute_volume_enabled: bool);
        fn avrcp_device_disconnected(addr: RawAddress);
        fn avrcp_absolute_volume_update(volume: u8);
        fn avrcp_send_key_event(key: u8, state: u8);
        fn avrcp_set_active_device(addr: RawAddress);
    }
}

#[derive(Debug)]
pub enum AvrcpCallbacks {
    /// Emitted when avrcp completes connection.
    /// Params: Device address, Absolute Volume Enabled
    AvrcpDeviceConnected(RawAddress, bool),
    /// Emitted when avrcp device disconnected.
    /// Params: Device address
    AvrcpDeviceDisconnected(RawAddress),
    /// Emitted when the absolute volume of a connected AVRCP device changed
    /// Params: Volume
    AvrcpAbsoluteVolumeUpdate(u8),
    /// Emitted when received a key event from a connected AVRCP device
    /// Params: Key, Value
    AvrcpSendKeyEvent(u8, u8),
    /// Emitted when received request from AVRCP interface to set a device to active
    /// Params: Device address
    AvrcpSetActiveDevice(RawAddress),
}

pub struct AvrcpCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(AvrcpCallbacks) + Send>,
}

impl Debug for AvrcpCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "AvrcpCallbacksDispatcher {{}}")
    }
}

type AvrcpCb = Arc<Mutex<AvrcpCallbacksDispatcher>>;

cb_variant!(
    AvrcpCb,
    avrcp_device_connected -> AvrcpCallbacks::AvrcpDeviceConnected,
    RawAddress, bool);

cb_variant!(
    AvrcpCb,
    avrcp_device_disconnected -> AvrcpCallbacks::AvrcpDeviceDisconnected,
    RawAddress);

cb_variant!(
    AvrcpCb,
    avrcp_absolute_volume_update -> AvrcpCallbacks::AvrcpAbsoluteVolumeUpdate,
    u8, {}
);

cb_variant!(
    AvrcpCb,
    avrcp_send_key_event -> AvrcpCallbacks::AvrcpSendKeyEvent,
    u8, u8, {}
);

cb_variant!(
    AvrcpCb,
    avrcp_set_active_device -> AvrcpCallbacks::AvrcpSetActiveDevice,
    RawAddress);

pub struct Avrcp {
    internal: cxx::UniquePtr<ffi::AvrcpIntf>,
    is_init: bool,
    is_enabled: bool,
}

// For *const u8 opaque btif
unsafe impl Send for Avrcp {}

impl ToggleableProfile for Avrcp {
    fn is_enabled(&self) -> bool {
        self.is_enabled
    }

    fn enable(&mut self) -> bool {
        self.internal.pin_mut().init();
        self.is_enabled = true;
        true
    }

    #[profile_enabled_or(false)]
    fn disable(&mut self) -> bool {
        self.internal.pin_mut().cleanup();
        self.is_enabled = false;
        true
    }
}

impl Avrcp {
    pub fn new(intf: &BluetoothInterface) -> Avrcp {
        let avrcpif: cxx::UniquePtr<ffi::AvrcpIntf> = ffi::GetAvrcpProfile(intf.as_btif());

        Avrcp { internal: avrcpif, is_init: false, is_enabled: false }
    }

    #[log_args]
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    #[log_args]
    pub fn initialize(&mut self, callbacks: AvrcpCallbacksDispatcher) -> bool {
        if get_dispatchers().lock().unwrap().set::<AvrcpCb>(Arc::new(Mutex::new(callbacks))) {
            panic!("Tried to set dispatcher for Avrcp callbacks while it already exists");
        }
        self.is_init = true;
        true
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn connect(&mut self, addr: RawAddress) -> BtStatus {
        self.internal.pin_mut().connect(addr).into()
    }

    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn disconnect(&mut self, addr: RawAddress) -> BtStatus {
        self.internal.pin_mut().disconnect(addr).into()
    }

    #[profile_enabled_or]
    pub fn set_volume(&mut self, addr: RawAddress, volume: i8) {
        self.internal.pin_mut().set_volume(addr, volume);
    }

    #[profile_enabled_or(false)]
    pub fn cleanup(&mut self) -> bool {
        self.internal.pin_mut().cleanup();
        true
    }

    #[profile_enabled_or]
    pub fn set_playback_status(&mut self, status: &String) {
        self.internal.pin_mut().set_playback_status(status);
    }

    #[profile_enabled_or]
    pub fn set_position(&mut self, position_us: i64) {
        self.internal.pin_mut().set_position(position_us);
    }

    #[profile_enabled_or]
    pub fn set_metadata(&mut self, metadata: &PlayerMetadata) {
        self.internal.pin_mut().set_metadata(
            &metadata.title,
            &metadata.artist,
            &metadata.album,
            metadata.length_us,
        );
    }

    #[profile_enabled_or]
    pub fn add_player(&mut self, name: &String, browsing_supported: bool) {
        self.internal.pin_mut().add_player(name, browsing_supported);
    }
}
