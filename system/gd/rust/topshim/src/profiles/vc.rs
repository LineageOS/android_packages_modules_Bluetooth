use crate::btif::{BluetoothInterface, RawAddress, ToggleableProfile};
use crate::topstack::get_dispatchers;

use std::sync::{Arc, Mutex};
use topshim_macros::{cb_variant, profile_enabled_or};

use log::warn;

#[cxx::bridge(namespace = bluetooth::topshim::rust)]
pub mod ffi {
    unsafe extern "C++" {
        include!("types/raw_address.h");
        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtVcConnectionState {
        Disconnected = 0,
        Connecting,
        Connected,
        Disconnecting,
    }

    unsafe extern "C++" {
        include!("vc/vc_shim.h");

        type VolumeControlIntf;

        unsafe fn GetVolumeControlProfile(btif: *const u8) -> UniquePtr<VolumeControlIntf>;

        fn init(self: Pin<&mut VolumeControlIntf>);
        fn cleanup(self: Pin<&mut VolumeControlIntf>);
        fn connect(self: Pin<&mut VolumeControlIntf>, addr: RawAddress);
        fn disconnect(self: Pin<&mut VolumeControlIntf>, addr: RawAddress);
        fn remove_device(self: Pin<&mut VolumeControlIntf>, addr: RawAddress);
        fn set_volume(self: Pin<&mut VolumeControlIntf>, group_id: i32, volume: u8);
        fn mute(self: Pin<&mut VolumeControlIntf>, addr: RawAddress);
        fn unmute(self: Pin<&mut VolumeControlIntf>, addr: RawAddress);
        fn get_ext_audio_out_volume_offset(
            self: Pin<&mut VolumeControlIntf>,
            addr: RawAddress,
            ext_output_id: u8,
        );
        fn set_ext_audio_out_volume_offset(
            self: Pin<&mut VolumeControlIntf>,
            addr: RawAddress,
            ext_output_id: u8,
            offset_val: i16,
        );
        fn get_ext_audio_out_location(
            self: Pin<&mut VolumeControlIntf>,
            addr: RawAddress,
            ext_output_id: u8,
        );
        fn set_ext_audio_out_location(
            self: Pin<&mut VolumeControlIntf>,
            addr: RawAddress,
            ext_output_id: u8,
            location: u32,
        );
        fn get_ext_audio_out_description(
            self: Pin<&mut VolumeControlIntf>,
            addr: RawAddress,
            ext_output_id: u8,
        );
        unsafe fn set_ext_audio_out_description(
            self: Pin<&mut VolumeControlIntf>,
            addr: RawAddress,
            ext_output_id: u8,
            descr: *const c_char,
        );
    }

    extern "Rust" {
        fn vc_connection_state_callback(state: BtVcConnectionState, addr: RawAddress);
        fn vc_volume_state_callback(
            address: RawAddress,
            volume: u8,
            mute: bool,
            is_autonomous: bool,
        );
        fn vc_group_volume_state_callback(
            group_id: i32,
            volume: u8,
            mute: bool,
            is_autonomous: bool,
        );
        fn vc_device_available_callback(address: RawAddress, num_offset: u8);
        fn vc_ext_audio_out_volume_offset_callback(
            address: RawAddress,
            ext_output_id: u8,
            offset: i16,
        );
        fn vc_ext_audio_out_location_callback(
            address: RawAddress,
            ext_output_id: u8,
            location: u32,
        );
        fn vc_ext_audio_out_description_callback(
            address: RawAddress,
            ext_output_id: u8,
            descr: String,
        );
    }
}

pub type BtVcConnectionState = ffi::BtVcConnectionState;

#[derive(Debug)]
pub enum VolumeControlCallbacks {
    ConnectionState(BtVcConnectionState, RawAddress),
    VolumeState(RawAddress, u8, bool, bool),
    GroupVolumeState(i32, u8, bool, bool),
    DeviceAvailable(RawAddress, u8),
    ExtAudioOutVolume(RawAddress, u8, i16),
    ExtAudioOutLocation(RawAddress, u8, u32),
    ExtAudioOutDescription(RawAddress, u8, String),
}

pub struct VolumeControlCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(VolumeControlCallbacks) + Send>,
}

type VolumeControlCb = Arc<Mutex<VolumeControlCallbacksDispatcher>>;

cb_variant!(VolumeControlCb,
            vc_connection_state_callback -> VolumeControlCallbacks::ConnectionState,
            BtVcConnectionState, RawAddress);

cb_variant!(VolumeControlCb,
            vc_volume_state_callback -> VolumeControlCallbacks::VolumeState,
            RawAddress, u8, bool, bool);

cb_variant!(VolumeControlCb,
            vc_group_volume_state_callback -> VolumeControlCallbacks::GroupVolumeState,
            i32, u8, bool, bool);

cb_variant!(VolumeControlCb,
            vc_device_available_callback -> VolumeControlCallbacks::DeviceAvailable,
            RawAddress, u8);

cb_variant!(VolumeControlCb,
            vc_ext_audio_out_volume_offset_callback -> VolumeControlCallbacks::ExtAudioOutVolume,
            RawAddress, u8, i16);

cb_variant!(VolumeControlCb,
            vc_ext_audio_out_location_callback -> VolumeControlCallbacks::ExtAudioOutLocation,
            RawAddress, u8, u32);

cb_variant!(VolumeControlCb,
            vc_ext_audio_out_description_callback -> VolumeControlCallbacks::ExtAudioOutDescription,
            RawAddress, u8, String);

pub struct VolumeControl {
    internal: cxx::UniquePtr<ffi::VolumeControlIntf>,
    is_init: bool,
    is_enabled: bool,
}

// For *const u8 opaque btif
// SAFETY: `VolumeControlIntf` is thread-safe to make calls from.
unsafe impl Send for VolumeControl {}

impl ToggleableProfile for VolumeControl {
    fn is_enabled(&self) -> bool {
        self.is_enabled
    }

    fn enable(&mut self) -> bool {
        if self.is_enabled {
            warn!("VolumeControl is already enabled.");
            return false;
        }

        self.internal.pin_mut().init();
        self.is_enabled = true;
        true
    }

    #[profile_enabled_or(false)]
    fn disable(&mut self) -> bool {
        if !self.is_enabled {
            warn!("VolumeControl is already disabled.");
            return false;
        }

        self.internal.pin_mut().cleanup();
        self.is_enabled = false;
        true
    }
}

impl VolumeControl {
    pub fn new(intf: &BluetoothInterface) -> VolumeControl {
        let vc_if: cxx::UniquePtr<ffi::VolumeControlIntf>;

        // SAFETY: `intf.as_raw_ptr()` is a valid pointer to a `BluetoothInterface`
        vc_if = unsafe { ffi::GetVolumeControlProfile(intf.as_raw_ptr()) };

        VolumeControl { internal: vc_if, is_init: false, is_enabled: false }
    }

    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    // `internal.init` is invoked during `ToggleableProfile::enable`
    pub fn initialize(&mut self, callbacks: VolumeControlCallbacksDispatcher) -> bool {
        if self.is_init {
            warn!("VolumeControl has already been initialized");
            return false;
        }

        if get_dispatchers().lock().unwrap().set::<VolumeControlCb>(Arc::new(Mutex::new(callbacks)))
        {
            panic!("Tried to set dispatcher for VolumeControl callbacks while it already exists");
        }

        self.is_init = true;

        true
    }

    #[profile_enabled_or]
    pub fn cleanup(&mut self) {
        self.internal.pin_mut().cleanup();
    }

    #[profile_enabled_or]
    pub fn connect(&mut self, addr: RawAddress) {
        self.internal.pin_mut().connect(addr);
    }

    #[profile_enabled_or]
    pub fn disconnect(&mut self, addr: RawAddress) {
        self.internal.pin_mut().disconnect(addr);
    }

    #[profile_enabled_or]
    pub fn remove_device(&mut self, addr: RawAddress) {
        self.internal.pin_mut().remove_device(addr);
    }

    #[profile_enabled_or]
    pub fn set_volume(&mut self, group_id: i32, volume: u8) {
        self.internal.pin_mut().set_volume(group_id, volume);
    }

    #[profile_enabled_or]
    pub fn mute(&mut self, addr: RawAddress) {
        self.internal.pin_mut().mute(addr);
    }

    #[profile_enabled_or]
    pub fn unmute(&mut self, addr: RawAddress) {
        self.internal.pin_mut().unmute(addr);
    }

    #[profile_enabled_or]
    pub fn get_ext_audio_out_volume_offset(&mut self, addr: RawAddress, ext_output_id: u8) {
        self.internal.pin_mut().get_ext_audio_out_volume_offset(addr, ext_output_id);
    }

    #[profile_enabled_or]
    pub fn set_ext_audio_out_volume_offset(
        &mut self,
        addr: RawAddress,
        ext_output_id: u8,
        offset_val: i16,
    ) {
        self.internal.pin_mut().set_ext_audio_out_volume_offset(addr, ext_output_id, offset_val);
    }

    #[profile_enabled_or]
    pub fn get_ext_audio_out_location(&mut self, addr: RawAddress, ext_output_id: u8) {
        self.internal.pin_mut().get_ext_audio_out_location(addr, ext_output_id);
    }

    #[profile_enabled_or]
    pub fn set_ext_audio_out_location(
        &mut self,
        addr: RawAddress,
        ext_output_id: u8,
        location: u32,
    ) {
        self.internal.pin_mut().set_ext_audio_out_location(addr, ext_output_id, location);
    }

    #[profile_enabled_or]
    pub fn get_ext_audio_out_description(&mut self, addr: RawAddress, ext_output_id: u8) {
        self.internal.pin_mut().get_ext_audio_out_description(addr, ext_output_id);
    }

    #[profile_enabled_or]
    pub fn set_ext_audio_out_description(
        &mut self,
        addr: RawAddress,
        ext_output_id: u8,
        descr: String,
    ) {
        let c_descr = std::ffi::CString::new(descr).unwrap();
        unsafe {
            // SAFETY: calling an FFI where the pointer is const, no modification.
            self.internal.pin_mut().set_ext_audio_out_description(
                addr,
                ext_output_id,
                c_descr.as_ptr(),
            );
        }
    }
}
