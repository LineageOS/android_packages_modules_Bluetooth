use crate::bindings::root as bindings;
use crate::btif::{BluetoothInterface, BtStatus, RawAddress, ToggleableProfile};
use crate::topstack::get_dispatchers;

use num_derive::{FromPrimitive, ToPrimitive};
use std::fmt::{Debug, Formatter, Result};
use std::sync::{Arc, Mutex};
use topshim_macros::{cb_variant, gen_cxx_extern_trivial_tuple, log_args, profile_enabled_or};

use log::warn;

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
/// Represents the various connection states a Hands-Free client would go through.
pub enum BthfClientConnectionState {
    Disconnected = 0,
    Connecting,
    Connected,
    SlcConnected,
    Disconnecting,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBthfClientConnectionState(bindings::bthf_client_connection_state_t);

impl From<CxxBthfClientConnectionState> for BthfClientConnectionState {
    fn from(item: CxxBthfClientConnectionState) -> Self {
        match item.0 {
            bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_DISCONNECTED => {
                BthfClientConnectionState::Disconnected
            }
            bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_CONNECTING => {
                BthfClientConnectionState::Connecting
            }
            bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_CONNECTED => {
                BthfClientConnectionState::Connected
            }
            bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_SLC_CONNECTED => {
                BthfClientConnectionState::SlcConnected
            }
            bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_DISCONNECTING => {
                BthfClientConnectionState::Disconnecting
            }
            _ => panic!("Unsupported bthf_client_connection_state_t {}", item.0),
        }
    }
}

impl From<BthfClientConnectionState> for CxxBthfClientConnectionState {
    fn from(item: BthfClientConnectionState) -> Self {
        let i = match item {
            BthfClientConnectionState::Disconnected => {
                bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_DISCONNECTED
            }
            BthfClientConnectionState::Connecting => {
                bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_CONNECTING
            }
            BthfClientConnectionState::Connected => {
                bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_CONNECTED
            }
            BthfClientConnectionState::SlcConnected => {
                bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_SLC_CONNECTED
            }
            BthfClientConnectionState::Disconnecting => {
                bindings::bthf_client_connection_state_t_BTHF_CLIENT_CONNECTION_STATE_DISCONNECTING
            }
        };
        CxxBthfClientConnectionState(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
/// Represents the various connection states the audio channel for a
/// Hands-Free client would go through.
pub enum BthfClientAudioState {
    Disconnected = 0,
    Connecting,
    Connected,
    ConnectedMsbc,
    ConnectedLc3,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBthfClientAudioState(bindings::bthf_client_audio_state_t);

impl From<CxxBthfClientAudioState> for BthfClientAudioState {
    fn from(item: CxxBthfClientAudioState) -> Self {
        match item.0 {
            bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_DISCONNECTED => {
                BthfClientAudioState::Disconnected
            }
            bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTING => {
                BthfClientAudioState::Connecting
            }
            bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTED => {
                BthfClientAudioState::Connected
            }
            bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTED_MSBC => {
                BthfClientAudioState::ConnectedMsbc
            }
            bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTED_LC3 => {
                BthfClientAudioState::ConnectedLc3
            }
            _ => panic!("Unsupported bthf_client_audio_state_t: {}", item.0),
        }
    }
}

impl From<BthfClientAudioState> for CxxBthfClientAudioState {
    fn from(item: BthfClientAudioState) -> Self {
        let i = match item {
            BthfClientAudioState::Disconnected => {
                bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_DISCONNECTED
            }
            BthfClientAudioState::Connecting => {
                bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTING
            }
            BthfClientAudioState::Connected => {
                bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTED
            }
            BthfClientAudioState::ConnectedMsbc => {
                bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTED_MSBC
            }
            BthfClientAudioState::ConnectedLc3 => {
                bindings::bthf_client_audio_state_t_BTHF_CLIENT_AUDIO_STATE_CONNECTED_LC3
            }
        };
        CxxBthfClientAudioState(i)
    }
}

#[derive(Debug)]
pub enum BthfClientCallbacks {
    /// Callback invoked when the connection state of the client changes.
    /// Params (Address, Connection state, peer features, child features)
    ConnectionState(RawAddress, BthfClientConnectionState, u32, u32),

    /// Callback invoked when the audio connection state of the client changes.
    AudioState(RawAddress, BthfClientAudioState),
}

pub struct BthfClientCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(BthfClientCallbacks) + Send>,
}

impl Debug for BthfClientCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "BthfClientCallbacksDispatcher {{}}")
    }
}

type BthfClientCb = Arc<Mutex<BthfClientCallbacksDispatcher>>;

cb_variant!(BthfClientCb, hf_client_connection_state_cb -> BthfClientCallbacks::ConnectionState,
    RawAddress, CxxBthfClientConnectionState -> BthfClientConnectionState, u32, u32
);

cb_variant!(BthfClientCb, hf_client_audio_state_cb -> BthfClientCallbacks::AudioState,
    RawAddress, CxxBthfClientAudioState -> BthfClientAudioState
);

// Rust HH FFI that matches the C++ HH Interface defined in /topshim/hh/hh_shim.h
#[cxx::bridge(namespace = "bluetooth::topshim::rust")]
mod ffi {
    unsafe extern "C++" {
        include!("bluetooth/types/address.h");
        include!("include/hardware/bt_hf_client.h");
        include!("topshim/hf_client/hf_client_shim.h");

        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;

        #[namespace = ""]
        #[cxx_name = "bthf_client_connection_state_t"]
        type BthfClientConnectionState = super::CxxBthfClientConnectionState;

        #[namespace = ""]
        #[cxx_name = "bthf_client_audio_state_t"]
        type BthfClientAudioState = super::CxxBthfClientAudioState;

        type BtIntf = crate::btif::ffi::BtIntf;

        type HfClientIntf;

        fn GetHfClientProfile(btif: &BtIntf) -> UniquePtr<HfClientIntf>;

        fn init(self: &HfClientIntf) -> u32;
        fn connect(self: &HfClientIntf, addr: RawAddress) -> u32;
        fn disconnect(self: &HfClientIntf, addr: RawAddress) -> u32;
        fn connect_audio(self: &HfClientIntf, addr: RawAddress) -> u32;
        fn disconnect_audio(self: &HfClientIntf, addr: RawAddress) -> u32;
        fn cleanup(self: &HfClientIntf);
    }

    // Callbacks from C++ to Rust. Generated by cb_variant!
    extern "Rust" {
        fn hf_client_connection_state_cb(
            addr: RawAddress,
            state: BthfClientConnectionState,
            peer_feat: u32,
            chld_feat: u32,
        );
        fn hf_client_audio_state_cb(addr: RawAddress, state: BthfClientAudioState);
    }
}

pub struct HfClient {
    internal: cxx::UniquePtr<ffi::HfClientIntf>,
    is_init: bool,
    is_enabled: bool,
}

// SAFETY: The pointer is to a static, thread-safe interface provided by the
// Bluetooth stack. It's safe to send this pointer across threads.
unsafe impl Send for HfClient {}

impl ToggleableProfile for HfClient {
    fn is_enabled(&self) -> bool {
        self.is_enabled
    }

    fn enable(&mut self) -> bool {
        let init = self.internal.init();
        self.is_init = BtStatus::from(init) == BtStatus::Success;
        self.is_enabled = self.is_init;
        true
    }

    #[profile_enabled_or(false)]
    fn disable(&mut self) -> bool {
        self.internal.cleanup();
        self.is_enabled = false;
        true
    }
}

impl HfClient {
    #[log_args]
    pub fn new(intf: &BluetoothInterface) -> HfClient {
        let hf_client_intf: cxx::UniquePtr<ffi::HfClientIntf> =
            ffi::GetHfClientProfile(intf.as_btif());
        HfClient { internal: hf_client_intf, is_init: false, is_enabled: false }
    }

    #[log_args]
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    #[log_args]
    pub fn initialize(&mut self, callbacks: BthfClientCallbacksDispatcher) -> bool {
        // Register dispatcher
        if get_dispatchers().lock().unwrap().set::<BthfClientCb>(Arc::new(Mutex::new(callbacks))) {
            panic!("Tried to set dispatcher for BthfClienCallbacks but it already existed");
        }

        true
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn connect(&self, addr: RawAddress) -> BtStatus {
        self.internal.connect(addr).into()
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn disconnect(&self, addr: RawAddress) -> BtStatus {
        self.internal.disconnect(addr).into()
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn connect_audio(&mut self, addr: RawAddress) -> BtStatus {
        self.internal.connect_audio(addr).into()
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn disconnect_audio(&mut self, addr: RawAddress) -> BtStatus {
        self.internal.disconnect_audio(addr).into()
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn cleanup(&mut self) {
        self.internal.cleanup();
    }
}
