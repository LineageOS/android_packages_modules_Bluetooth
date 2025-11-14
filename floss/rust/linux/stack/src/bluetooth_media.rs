//! Anything related to audio and media API.

use bt_topshim::btif::{
    BluetoothInterface, BtBondState, BtStatus, BtTransport, DisplayAddress, RawAddress,
    ToggleableProfile,
};
use bt_topshim::profiles::a2dp::{
    A2dp, A2dpCallbacks, A2dpCallbacksDispatcher, A2dpCodecBitsPerSample, A2dpCodecChannelMode,
    A2dpCodecConfig, A2dpCodecIndex, A2dpCodecPriority, A2dpCodecSampleRate, BtavAudioState,
    BtavConnectionState, PresentationPosition,
};
use bt_topshim::profiles::avrcp::{
    Avrcp, AvrcpCallbacks, AvrcpCallbacksDispatcher, PlayerMetadata,
};
use bt_topshim::profiles::csis::{
    BtCsisConnectionState, CsisClient, CsisClientCallbacks, CsisClientCallbacksDispatcher,
};
use bt_topshim::profiles::hfp::{interop_disable_hf_profile, interop_insert_call_when_sco_start};
use bt_topshim::profiles::hfp::{
    BthfAudioState, BthfConnectionState, CallHoldCommand, CallInfo, CallState, EscoCodingFormat,
    Hfp, HfpCallbacks, HfpCallbacksDispatcher, HfpCodecBitId, HfpCodecFormat, HfpCodecId,
    PhoneState, TelephonyDeviceStatus,
};
use bt_topshim::profiles::le_audio::{
    BtLeAudioConnectionState, BtLeAudioContentType, BtLeAudioDirection, BtLeAudioGroupNodeStatus,
    BtLeAudioGroupStatus, BtLeAudioGroupStreamStatus, BtLeAudioSource,
    BtLeAudioUnicastMonitorModeStatus, BtLeAudioUsage, BtLePcmConfig, BtLeStreamStartedStatus,
    LeAudioClient, LeAudioClientCallbacks, LeAudioClientCallbacksDispatcher, SinkMetadata,
    SourceMetadata,
};
use bt_topshim::profiles::vc::{
    BtVcConnectionState, VolumeControl, VolumeControlCallbacks, VolumeControlCallbacksDispatcher,
};
use bt_topshim::profiles::ProfileConnectionState;
use bt_topshim::{metrics, topstack};
use bt_utils::at_command_parser::{calculate_battery_percent, parse_at_command_data};
use bt_utils::features;
use bt_utils::uhid_hfp::{
    OutputEvent, UHidHfp, BLUETOOTH_TELEPHONY_UHID_REPORT_ID, UHID_INPUT_DROP,
    UHID_INPUT_HOOK_SWITCH, UHID_INPUT_NONE, UHID_INPUT_PHONE_MUTE, UHID_OUTPUT_MUTE,
    UHID_OUTPUT_NONE, UHID_OUTPUT_OFF_HOOK, UHID_OUTPUT_RING,
};
use bt_utils::uinput::UInput;

use itertools::Itertools;
use log::{debug, info, warn};
use std::collections::{HashMap, HashSet};
use std::convert::{TryFrom, TryInto};
use std::fs::File;
use std::io::Write;
use std::sync::Arc;
use std::sync::Mutex;

use tokio::sync::mpsc::Sender;
use tokio::task::JoinHandle;
use tokio::time::{sleep, Duration, Instant};

use crate::battery_manager::{Battery, BatterySet};
use crate::battery_provider_manager::{
    BatteryProviderManager, IBatteryProviderCallback, IBatteryProviderManager,
};
use crate::bluetooth::{Bluetooth, BluetoothDevice, IBluetooth};
use crate::bluetooth_admin::BluetoothAdminPolicyHelper;
use crate::callbacks::Callbacks;
use crate::uuid;
use crate::uuid::{Profile, UuidHelper};
use crate::{make_message_dispatcher, APIMessage, BluetoothAPI, Message, RPCProxy};

use num_derive::FromPrimitive;

// The timeout we have to wait for all supported profiles to connect after we
// receive the first profile connected event. The host shall disconnect or
// force connect the potentially partially connected device after this many
// seconds of timeout.
const PROFILE_DISCOVERY_TIMEOUT_SEC: u64 = 10;
// The timeout we have to wait for the initiator peer device to complete the
// initial profile connection. After this many seconds, we will begin to
// connect the missing profiles.
// 6s is set to align with Android's default. See "btservice/PhonePolicy".
const CONNECT_MISSING_PROFILES_TIMEOUT_SEC: u64 = 6;
// The duration we assume the role of the initiator, i.e. the side that starts
// the profile connection. If the profile is connected before this many seconds,
// we assume we are the initiator and can keep connecting the remaining
// profiles, otherwise we wait for the peer initiator.
// Set to 5s to align with default page timeout (BT spec vol 4 part E sec 6.6)
const CONNECT_AS_INITIATOR_TIMEOUT_SEC: u64 = 5;

/// The list of profiles we consider as classic audio profiles for media.
const MEDIA_CLASSIC_AUDIO_PROFILES: &[Profile] =
    &[Profile::A2dpSink, Profile::Hfp, Profile::AvrcpController];

/// The list of profiles we consider as LE audio profiles for media.
const MEDIA_LE_AUDIO_PROFILES: &[Profile] =
    &[Profile::LeAudio, Profile::VolumeControl, Profile::CoordinatedSet];

const MEDIA_PROFILE_ENABLE_ORDER: &[Profile] = &[
    Profile::A2dpSource,
    Profile::AvrcpTarget,
    Profile::Hfp,
    Profile::LeAudio,
    Profile::VolumeControl,
    Profile::CoordinatedSet,
];

/// Group ID used to identify unknown/non-existent groups.
pub const LEA_UNKNOWN_GROUP_ID: i32 = -1;

/// Refer to |pairDeviceByCsip| in |CachedBluetoothDeviceManager.java|.
/// Number of attempts for CSIS to bond set members of a connected group.
const CSIS_BONDING_NUM_ATTEMPTS: u32 = 30;
/// The delay for bonding retries when pairing is busy, in milliseconds.
const CSIS_BONDING_RETRY_DELAY_MS: u64 = 500;

pub trait IBluetoothMedia {
    ///
    fn register_callback(&mut self, callback: Box<dyn IBluetoothMediaCallback + Send>) -> bool;

    /// initializes media (both A2dp and AVRCP) stack
    fn initialize(&mut self) -> bool;

    /// Get if the media stack is initialized.
    fn is_initialized(&self) -> bool;

    /// clean up media stack
    fn cleanup(&mut self) -> bool;

    /// connect to available but missing classic media profiles
    fn connect(&mut self, address: RawAddress);

    /// disconnect all profiles from the device
    /// NOTE: do not call this function from outside unless `is_complete_profiles_required`
    fn disconnect(&mut self, address: RawAddress);

    fn connect_lea_group_by_member_address(&mut self, address: RawAddress);
    fn disconnect_lea_group_by_member_address(&mut self, address: RawAddress);

    fn connect_lea(&mut self, address: RawAddress);
    fn disconnect_lea(&mut self, address: RawAddress);
    fn connect_vc(&mut self, address: RawAddress);
    fn disconnect_vc(&mut self, address: RawAddress);
    fn connect_csis(&mut self, address: RawAddress);
    fn disconnect_csis(&mut self, address: RawAddress);

    // Set the device as the active A2DP device
    fn set_active_device(&mut self, address: RawAddress);

    // Reset the active A2DP device
    fn reset_active_device(&mut self);

    // Set the device as the active HFP device
    fn set_hfp_active_device(&mut self, address: RawAddress);

    fn set_audio_config(
        &mut self,
        address: RawAddress,
        codec_type: A2dpCodecIndex,
        sample_rate: A2dpCodecSampleRate,
        bits_per_sample: A2dpCodecBitsPerSample,
        channel_mode: A2dpCodecChannelMode,
    ) -> bool;

    // Set the A2DP/AVRCP volume. Valid volume specified by the spec should be
    // in the range of 0-127.
    fn set_volume(&mut self, volume: u8);

    // Set the HFP speaker volume. Valid volume specified by the HFP spec should
    // be in the range of 0-15.
    fn set_hfp_volume(&mut self, volume: u8, address: RawAddress);
    fn start_audio_request(&mut self, connection_listener: File) -> bool;
    fn stop_audio_request(&mut self, connection_listener: File);

    /// Returns true iff A2DP audio has started.
    fn get_a2dp_audio_started(&mut self, address: RawAddress) -> bool;

    /// Returns the negotiated codec (CVSD=1, mSBC=2, LC3=4) to use if HFP audio has started.
    /// Returns 0 if HFP audio hasn't started.
    fn get_hfp_audio_final_codecs(&mut self, address: RawAddress) -> u8;

    fn get_presentation_position(&mut self) -> PresentationPosition;

    /// Start the SCO setup to connect audio
    fn start_sco_call(
        &mut self,
        address: RawAddress,
        sco_offload: bool,
        disabled_codecs: HfpCodecBitId,
        connection_listener: File,
    ) -> bool;
    fn stop_sco_call(&mut self, address: RawAddress, connection_listener: File);

    /// Set the current playback status: e.g., playing, paused, stopped, etc. The method is a copy
    /// of the existing CRAS API, hence not following Floss API conventions.
    fn set_player_playback_status(&mut self, status: String);
    /// Set the position of the current media in microseconds. The method is a copy of the existing
    /// CRAS API, hence not following Floss API conventions.
    fn set_player_position(&mut self, position: i64);
    /// Set the media metadata, including title, artist, album, and length. The method is a
    /// copy of the existing CRAS API, hence not following Floss API conventions. PlayerMetadata is
    /// a custom data type that requires special handlng.
    fn set_player_metadata(&mut self, metadata: PlayerMetadata);

    // Trigger a debug log dump.
    fn trigger_debug_dump(&mut self);

    /// LE Audio Commands
    fn group_set_active(&mut self, group_id: i32);
    fn host_start_audio_request(&mut self) -> bool;
    fn host_stop_audio_request(&mut self);
    fn peer_start_audio_request(&mut self) -> bool;
    fn peer_stop_audio_request(&mut self);
    fn get_host_pcm_config(&mut self) -> BtLePcmConfig;
    fn get_peer_pcm_config(&mut self) -> BtLePcmConfig;
    fn get_host_stream_started(&mut self) -> BtLeStreamStartedStatus;
    fn get_peer_stream_started(&mut self) -> BtLeStreamStartedStatus;
    fn source_metadata_changed(
        &mut self,
        usage: BtLeAudioUsage,
        content_type: BtLeAudioContentType,
        gain: f64,
    ) -> bool;
    fn sink_metadata_changed(&mut self, source: BtLeAudioSource, gain: f64) -> bool;
    fn get_unicast_monitor_mode_status(
        &mut self,
        direction: BtLeAudioDirection,
    ) -> BtLeAudioUnicastMonitorModeStatus;
    fn get_group_stream_status(&mut self, group_id: i32) -> BtLeAudioGroupStreamStatus;
    fn get_group_status(&mut self, group_id: i32) -> BtLeAudioGroupStatus;

    /// Valid volume range is [0, 255], see 2.3.1.1, VCS v1.
    fn set_group_volume(&mut self, group_id: i32, volume: u8);
}

pub trait IBluetoothMediaCallback: RPCProxy {
    /// Triggered when a Bluetooth audio device is ready to be used. This should
    /// only be triggered once for a device and send an event to clients. If the
    /// device supports both HFP and A2DP, both should be ready when this is
    /// triggered.
    fn on_bluetooth_audio_device_added(&mut self, device: BluetoothAudioDevice);

    ///
    fn on_bluetooth_audio_device_removed(&mut self, addr: RawAddress);

    ///
    fn on_absolute_volume_supported_changed(&mut self, supported: bool);

    /// Triggered when a Bluetooth device triggers an AVRCP/A2DP volume change
    /// event. We need to notify audio client to reflect the change on the audio
    /// stack. The volume should be in the range of 0 to 127.
    fn on_absolute_volume_changed(&mut self, volume: u8);

    /// Triggered when a Bluetooth device triggers a HFP AT command (AT+VGS) to
    /// notify AG about its speaker volume change. We need to notify audio
    /// client to reflect the change on the audio stack. The volume should be
    /// in the range of 0 to 15.
    fn on_hfp_volume_changed(&mut self, volume: u8, addr: RawAddress);

    /// Triggered when HFP audio is disconnected, in which case it could be
    /// waiting for the audio client to issue a reconnection request. We need
    /// to notify audio client of this event for it to do appropriate handling.
    fn on_hfp_audio_disconnected(&mut self, addr: RawAddress);

    /// Triggered when there is a HFP dump is received. This should only be used
    /// for debugging and testing purpose.
    fn on_hfp_debug_dump(
        &mut self,
        active: bool,
        codec_id: u16,
        total_num_decoded_frames: i32,
        pkt_loss_ratio: f64,
        begin_ts: u64,
        end_ts: u64,
        pkt_status_in_hex: String,
        pkt_status_in_binary: String,
    );

    /// Triggered when the first member of the specified LEA group has connected
    /// the LE audio profile. This is the earliest meaningful timing to notify
    /// the audio server that the group as an audio device is available.
    fn on_lea_group_connected(&mut self, group_id: i32, name: String);

    /// Triggered when the last connected member of the specified LEA group has
    /// disconnected the LE audio profile. This is when we should notify the
    /// audio server that the group is no longer available as an audio device.
    fn on_lea_group_disconnected(&mut self, group_id: i32);

    fn on_lea_group_status(&mut self, group_id: i32, status: BtLeAudioGroupStatus);

    fn on_lea_group_node_status(
        &mut self,
        addr: RawAddress,
        group_id: i32,
        status: BtLeAudioGroupNodeStatus,
    );

    fn on_lea_audio_conf(
        &mut self,
        direction: u8,
        group_id: i32,
        snk_audio_location: i64,
        src_audio_location: i64,
        avail_cont: u16,
    );

    fn on_lea_unicast_monitor_mode_status(
        &mut self,
        direction: BtLeAudioDirection,
        status: BtLeAudioUnicastMonitorModeStatus,
    );

    fn on_lea_group_stream_status(&mut self, group_id: i32, status: BtLeAudioGroupStreamStatus);

    fn on_lea_vc_connected(&mut self, addr: RawAddress, group_id: i32);

    fn on_lea_group_volume_changed(&mut self, group_id: i32, volume: u8);
}

pub trait IBluetoothTelephony {
    ///
    fn register_telephony_callback(
        &mut self,
        callback: Box<dyn IBluetoothTelephonyCallback + Send>,
    ) -> bool;

    /// Sets whether the device is connected to the cellular network.
    fn set_network_available(&mut self, network_available: bool);
    /// Sets whether the device is roaming.
    fn set_roaming(&mut self, roaming: bool);
    /// Sets the device signal strength, 0 to 5.
    fn set_signal_strength(&mut self, signal_strength: i32) -> bool;
    /// Sets the device battery level, 0 to 5.
    fn set_battery_level(&mut self, battery_level: i32) -> bool;
    /// Enables/disables phone operations.
    fn set_phone_ops_enabled(&mut self, enable: bool);
    /// Enables/disables phone operations for mps qualification.
    /// The call state is fully reset whenever this is called.
    fn set_mps_qualification_enabled(&mut self, enable: bool);
    /// Acts like the AG received an incoming call.
    fn incoming_call(&mut self, number: String) -> bool;
    /// Acts like dialing a call from the AG.
    fn dialing_call(&mut self, number: String) -> bool;
    /// Acts like answering an incoming/dialing call from the AG.
    fn answer_call(&mut self) -> bool;
    /// Acts like hanging up an active/incoming/dialing call from the AG.
    fn hangup_call(&mut self) -> bool;
    /// Sets/unsets the memory slot. Note that we store at most one memory
    /// number and return it regardless of which slot is specified by HF.
    fn set_memory_call(&mut self, number: Option<String>) -> bool;
    /// Sets/unsets the last call.
    fn set_last_call(&mut self, number: Option<String>) -> bool;
    /// Releases all of the held calls.
    fn release_held(&mut self) -> bool;
    /// Releases the active call and accepts a held call.
    fn release_active_accept_held(&mut self) -> bool;
    /// Holds the active call and accepts a held call.
    fn hold_active_accept_held(&mut self) -> bool;
    /// Establishes an audio connection to <address>.
    fn audio_connect(&mut self, address: RawAddress) -> bool;
    /// Stops the audio connection to <address>.
    fn audio_disconnect(&mut self, address: RawAddress);
}

pub trait IBluetoothTelephonyCallback: RPCProxy {
    fn on_telephony_event(&mut self, addr: RawAddress, event: u8, state: u8);
}

/// Serializable device used in.
#[derive(Debug, Default, Clone)]
pub struct BluetoothAudioDevice {
    pub address: RawAddress,
    pub name: String,
    pub a2dp_caps: Vec<A2dpCodecConfig>,
    pub hfp_cap: HfpCodecFormat,
    pub absolute_volume: bool,
}

impl BluetoothAudioDevice {
    pub(crate) fn new(
        address: RawAddress,
        name: String,
        a2dp_caps: Vec<A2dpCodecConfig>,
        hfp_cap: HfpCodecFormat,
        absolute_volume: bool,
    ) -> Self {
        Self { address, name, a2dp_caps, hfp_cap, absolute_volume }
    }
}
/// Actions that `BluetoothMedia` can take on behalf of the stack.
pub enum MediaActions {
    Connect(RawAddress),
    Disconnect(RawAddress),
    ForceEnterConnected(RawAddress), // Only used for qualification.

    ConnectLeaGroupByMemberAddress(RawAddress),
    DisconnectLeaGroupByMemberAddress(RawAddress),
    ConnectLea(RawAddress),
    DisconnectLea(RawAddress),
    ConnectVc(RawAddress),
    DisconnectVc(RawAddress),
    ConnectCsis(RawAddress),
    DisconnectCsis(RawAddress),
}

#[derive(Debug, Clone, PartialEq)]
enum DeviceConnectionStates {
    Initiating,            // Some profile is connected, initiated from host side
    ConnectingBeforeRetry, // Some profile is connected, probably initiated from peer side
    ConnectingAfterRetry,  // Host initiated requests to missing profiles after timeout
    FullyConnected,        // All profiles (excluding AVRCP) are connected
    Disconnecting,         // Working towards disconnection of each connected profile
    WaitingConnection,     // Waiting for new connections initiated by peer
}

struct UHid {
    pub handle: UHidHfp,
    pub volume: u8,
    pub muted: bool,
    pub is_open: bool,
}

struct LEAAudioConf {
    pub direction: u8,
    pub group_id: i32,
    pub snk_audio_location: i64,
    pub src_audio_location: i64,
    pub avail_cont: u16,
}

#[derive(Default, Clone)]
struct LeAudioGroup {
    pub devices: HashSet<RawAddress>,
    pub status: BtLeAudioGroupStatus,
    pub stream_status: BtLeAudioGroupStreamStatus,
    pub volume: Option<u8>,
}

#[derive(Debug, Copy, Clone, FromPrimitive)]
#[repr(u8)]
enum TelephonyEvent {
    UHidCreate = 0,
    UHidDestroy,
    UHidOpen,
    UHidClose,
    UHidIncomingCall,
    UHidAnswerCall,
    UHidHangupCall,
    UHidPlaceActiveCall,
    UHidMicMute,
    UHidMicUnmute,
    CRASPlaceActiveCall,
    CRASRemoveActiveCall,
    HFAnswerCall,
    HFHangupCall,
    HFMicMute,
    HFMicUnmute,
    HFCurrentCallsQuery,
}

impl From<TelephonyEvent> for u8 {
    fn from(telephony_event: TelephonyEvent) -> Self {
        telephony_event as u8
    }
}

pub struct BluetoothMedia {
    battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
    battery_provider_id: u32,
    initialized: bool,
    callbacks: Arc<Mutex<Callbacks<dyn IBluetoothMediaCallback + Send>>>,
    telephony_callbacks: Arc<Mutex<Callbacks<dyn IBluetoothTelephonyCallback + Send>>>,
    tx: Sender<Message>,
    api_tx: Sender<APIMessage>,
    adapter: Arc<Mutex<Box<Bluetooth>>>,
    a2dp: A2dp,
    avrcp: Avrcp,
    avrcp_states: HashMap<RawAddress, BtavConnectionState>,
    a2dp_states: HashMap<RawAddress, BtavConnectionState>,
    a2dp_audio_state: HashMap<RawAddress, BtavAudioState>,
    a2dp_has_interrupted_stream: bool, // Only used for qualification.
    hfp: Hfp,
    hfp_states: HashMap<RawAddress, BthfConnectionState>,
    hfp_audio_state: HashMap<RawAddress, BthfAudioState>,
    a2dp_caps: HashMap<RawAddress, Vec<A2dpCodecConfig>>,
    hfp_cap: HashMap<RawAddress, HfpCodecFormat>,
    fallback_tasks: Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
    absolute_volume: bool,
    uinput: UInput,
    delay_enable_profiles: HashSet<Profile>,
    connected_profiles: HashMap<RawAddress, HashSet<Profile>>,
    device_states: Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
    delay_volume_update: HashMap<Profile, u8>,
    telephony_device_status: TelephonyDeviceStatus,
    phone_state: PhoneState,
    call_list: Vec<CallInfo>,
    phone_ops_enabled: bool,
    mps_qualification_enabled: bool,
    memory_dialing_number: Option<String>,
    last_dialing_number: Option<String>,
    uhid: HashMap<RawAddress, UHid>,
    le_audio: LeAudioClient,
    le_audio_groups: HashMap<i32, LeAudioGroup>,
    le_audio_node_to_group: HashMap<RawAddress, i32>,
    le_audio_states: HashMap<RawAddress, BtLeAudioConnectionState>,
    le_audio_unicast_monitor_mode_status: HashMap<i32, BtLeAudioUnicastMonitorModeStatus>,
    le_audio_delayed_audio_conf_updates: HashMap<i32, LEAAudioConf>,
    le_audio_delayed_vc_connection_updates: HashSet<RawAddress>,
    vc: VolumeControl,
    vc_states: HashMap<RawAddress, BtVcConnectionState>,
    csis: CsisClient,
    csis_states: HashMap<RawAddress, BtCsisConnectionState>,
    is_le_audio_only_enabled: bool, // TODO: remove this once there is dual mode.
    hfp_audio_connection_listener: Option<File>,
    a2dp_audio_connection_listener: Option<File>,
}

impl BluetoothMedia {
    pub fn new(
        tx: Sender<Message>,
        api_tx: Sender<APIMessage>,
        intf: Arc<Mutex<BluetoothInterface>>,
        adapter: Arc<Mutex<Box<Bluetooth>>>,
        battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
    ) -> BluetoothMedia {
        let a2dp = A2dp::new(&intf.lock().unwrap());
        let avrcp = Avrcp::new(&intf.lock().unwrap());
        let hfp = Hfp::new(&intf.lock().unwrap());
        let le_audio = LeAudioClient::new(&intf.lock().unwrap());
        let vc = VolumeControl::new(&intf.lock().unwrap());
        let csis = CsisClient::new(&intf.lock().unwrap());

        let battery_provider_id = battery_provider_manager
            .lock()
            .unwrap()
            .register_battery_provider(Box::new(BatteryProviderCallback::new()));
        BluetoothMedia {
            battery_provider_manager,
            battery_provider_id,
            initialized: false,
            callbacks: Arc::new(Mutex::new(Callbacks::new(
                tx.clone(),
                Message::MediaCallbackDisconnected,
            ))),
            telephony_callbacks: Arc::new(Mutex::new(Callbacks::new(
                tx.clone(),
                Message::TelephonyCallbackDisconnected,
            ))),
            tx,
            api_tx,
            adapter,
            a2dp,
            avrcp,
            avrcp_states: HashMap::new(),
            a2dp_states: HashMap::new(),
            a2dp_audio_state: HashMap::new(),
            a2dp_has_interrupted_stream: false,
            hfp,
            hfp_states: HashMap::new(),
            hfp_audio_state: HashMap::new(),
            a2dp_caps: HashMap::new(),
            hfp_cap: HashMap::new(),
            fallback_tasks: Arc::new(Mutex::new(HashMap::new())),
            absolute_volume: false,
            uinput: UInput::new(),
            delay_enable_profiles: HashSet::new(),
            connected_profiles: HashMap::new(),
            device_states: Arc::new(Mutex::new(HashMap::new())),
            delay_volume_update: HashMap::new(),
            telephony_device_status: TelephonyDeviceStatus::new(),
            phone_state: PhoneState { num_active: 0, num_held: 0, state: CallState::Idle },
            call_list: vec![],
            phone_ops_enabled: false,
            mps_qualification_enabled: false,
            memory_dialing_number: None,
            last_dialing_number: None,
            uhid: HashMap::new(),
            le_audio,
            le_audio_groups: HashMap::new(),
            le_audio_node_to_group: HashMap::new(),
            le_audio_states: HashMap::new(),
            le_audio_unicast_monitor_mode_status: HashMap::new(),
            le_audio_delayed_audio_conf_updates: HashMap::new(),
            le_audio_delayed_vc_connection_updates: HashSet::new(),
            vc,
            vc_states: HashMap::new(),
            csis,
            csis_states: HashMap::new(),
            is_le_audio_only_enabled: false,
            hfp_audio_connection_listener: None,
            a2dp_audio_connection_listener: None,
        }
    }

    pub fn cleanup(&mut self) -> bool {
        for profile in MEDIA_PROFILE_ENABLE_ORDER.iter().rev() {
            self.disable_profile(&profile);
        }
        self.initialized = false;
        true
    }

    fn is_profile_connected(&self, addr: &RawAddress, profile: &Profile) -> bool {
        self.is_any_profile_connected(addr, &[*profile])
    }

    fn is_any_profile_connected(&self, addr: &RawAddress, profiles: &[Profile]) -> bool {
        if let Some(connected_profiles) = self.connected_profiles.get(addr) {
            return profiles.iter().any(|p| connected_profiles.contains(p));
        }

        false
    }

    pub(crate) fn get_connected_profiles(&self, device_address: &RawAddress) -> HashSet<Profile> {
        self.connected_profiles.get(device_address).cloned().unwrap_or_default()
    }

    fn add_connected_profile(&mut self, addr: RawAddress, profile: Profile) {
        if self.is_profile_connected(&addr, &profile) {
            warn!("[{}]: profile is already connected", DisplayAddress(&addr));
            return;
        }

        self.connected_profiles.entry(addr).or_default().insert(profile);

        self.notify_media_capability_updated(addr);
    }

    fn rm_connected_profile(
        &mut self,
        addr: RawAddress,
        profile: Profile,
        is_profile_critical: bool,
    ) {
        if !self.is_profile_connected(&addr, &profile) {
            warn!("[{}]: profile is already disconnected", DisplayAddress(&addr));
            return;
        }

        if let Some(profiles) = self.connected_profiles.get_mut(&addr) {
            profiles.remove(&profile);
            if profiles.is_empty() {
                self.connected_profiles.remove(&addr);
            }
        }

        self.delay_volume_update.remove(&profile);

        if is_profile_critical && self.is_complete_profiles_required() {
            BluetoothMedia::disconnect_device(self.tx.clone(), addr);
            self.notify_critical_profile_disconnected(addr);
        }

        self.notify_media_capability_updated(addr);
    }

    fn is_group_connected(&self, group: &LeAudioGroup) -> bool {
        group.devices.iter().any(|&addr| {
            *self.le_audio_states.get(&addr).unwrap_or(&BtLeAudioConnectionState::Disconnected)
                == BtLeAudioConnectionState::Connected
        })
    }

    fn remove_device_from_group(&mut self, addr: RawAddress) {
        let group_id = match self.le_audio_node_to_group.get(&addr) {
            Some(group_id) => group_id,
            None => {
                warn!("Cannot remove device {} that belongs to no group", DisplayAddress(&addr));
                return;
            }
        };

        match self.le_audio_groups.get_mut(group_id) {
            Some(group) => {
                group.devices.remove(&addr);
                if group.devices.is_empty() {
                    self.le_audio_groups.remove(group_id);
                }
            }
            None => {
                warn!(
                    "{} claims to be in group {} which does not exist",
                    DisplayAddress(&addr),
                    group_id
                );
            }
        }
    }

    fn write_data_to_listener(&self, mut listener: File, data: Vec<u8>) {
        match listener.write(&data) {
            Ok(nwritten) => {
                if nwritten != data.len() {
                    warn!("Did not write full data into the event listener.");
                }
            }
            Err(e) => {
                warn!("Cannot write data into the event listener: {}", e);
            }
        }
    }

    pub fn enable_profile(&mut self, profile: &Profile) {
        match profile {
            Profile::A2dpSource | Profile::AvrcpTarget | Profile::Hfp => {
                if self.is_le_audio_only_enabled {
                    info!("LeAudioEnableLeAudioOnly is set, skip enabling {:?}", profile);
                    return;
                }
            }
            Profile::LeAudio | Profile::VolumeControl | Profile::CoordinatedSet => {
                if !self.is_le_audio_only_enabled {
                    info!("LeAudioEnableLeAudioOnly is not set, skip enabling {:?}", profile);
                    return;
                }
            }
            _ => {}
        }

        match profile {
            &Profile::A2dpSource => self.a2dp.enable(),
            &Profile::AvrcpTarget => self.avrcp.enable(),
            &Profile::Hfp => self.hfp.enable(),
            &Profile::LeAudio => self.le_audio.enable(),
            &Profile::VolumeControl => self.vc.enable(),
            &Profile::CoordinatedSet => self.csis.enable(),
            _ => {
                warn!("Tried to enable {} in bluetooth_media", profile);
                return;
            }
        };

        if self.is_profile_enabled(profile).unwrap() {
            self.delay_enable_profiles.remove(profile);
        } else {
            self.delay_enable_profiles.insert(*profile);
        }
    }

    pub fn disable_profile(&mut self, profile: &Profile) {
        match profile {
            &Profile::A2dpSource => self.a2dp.disable(),
            &Profile::AvrcpTarget => self.avrcp.disable(),
            &Profile::Hfp => self.hfp.disable(),
            &Profile::LeAudio => self.le_audio.disable(),
            &Profile::VolumeControl => self.vc.disable(),
            &Profile::CoordinatedSet => self.csis.disable(),
            _ => {
                warn!("Tried to disable {} in bluetooth_media", profile);
                return;
            }
        };

        self.delay_enable_profiles.remove(profile);
    }

    pub fn is_profile_enabled(&self, profile: &Profile) -> Option<bool> {
        match profile {
            &Profile::A2dpSource => Some(self.a2dp.is_enabled()),
            &Profile::AvrcpTarget => Some(self.avrcp.is_enabled()),
            &Profile::Hfp => Some(self.hfp.is_enabled()),
            &Profile::LeAudio => Some(self.le_audio.is_enabled()),
            &Profile::VolumeControl => Some(self.vc.is_enabled()),
            &Profile::CoordinatedSet => Some(self.csis.is_enabled()),
            _ => {
                warn!("Tried to query enablement status of {} in bluetooth_media", profile);
                None
            }
        }
    }

    pub(crate) fn handle_admin_policy_changed(&mut self, admin_helper: BluetoothAdminPolicyHelper) {
        for profile in UuidHelper::get_ordered_supported_profiles() {
            match profile {
                Profile::A2dpSource
                | Profile::AvrcpTarget
                | Profile::Hfp
                | Profile::LeAudio
                | Profile::VolumeControl
                | Profile::CoordinatedSet => {}
                _ => continue,
            }
            let profile = &profile;
            match (
                admin_helper.is_profile_allowed(profile),
                self.is_profile_enabled(profile).unwrap(),
            ) {
                (true, false) => self.enable_profile(profile),
                (false, true) => self.disable_profile(profile),
                _ => {}
            }
        }
    }

    pub fn dispatch_csis_callbacks(&mut self, cb: CsisClientCallbacks) {
        match cb {
            CsisClientCallbacks::ConnectionState(addr, state) => {
                if self.csis_states.get(&addr).is_some()
                    && state == *self.csis_states.get(&addr).unwrap()
                {
                    return;
                }

                info!(
                    "CsisClientCallbacks::ConnectionState: [{}]: state={:?}",
                    DisplayAddress(&addr),
                    state
                );

                match state {
                    BtCsisConnectionState::Connected => {
                        self.csis_states.insert(addr, state);
                    }
                    BtCsisConnectionState::Disconnected => {
                        self.csis_states.remove(&addr);
                    }
                    _ => {
                        self.csis_states.insert(addr, state);
                    }
                }
            }
            CsisClientCallbacks::DeviceAvailable(addr, group_id, group_size, rank, uuid) => {
                info!(
                    "CsisClientCallbacks::DeviceAvailable: [{}]: group_id={}, group_size={}, rank={}, uuid={:?}",
                    DisplayAddress(&addr),
                    group_id,
                    group_size,
                    rank,
                    uuid,
                );
            }
            CsisClientCallbacks::SetMemberAvailable(addr, group_id) => {
                info!(
                    "CsisClientCallbacks::SetMemberAvailable: [{}]: group_id={}",
                    DisplayAddress(&addr),
                    group_id
                );
                let device = BluetoothDevice::new(addr, "".to_string());
                let txl = self.tx.clone();
                topstack::get_runtime().spawn(async move {
                    let _ = txl
                        .send(Message::CreateBondWithRetry(
                            device,
                            BtTransport::Le,
                            CSIS_BONDING_NUM_ATTEMPTS,
                            Duration::from_millis(CSIS_BONDING_RETRY_DELAY_MS),
                        ))
                        .await;
                });
            }
            CsisClientCallbacks::GroupLockChanged(group_id, locked, status) => {
                info!(
                    "CsisClientCallbacks::GroupLockChanged: group_id={}, locked={}, status={:?}",
                    group_id, locked, status
                );
            }
        }
    }

    pub fn dispatch_vc_callbacks(&mut self, cb: VolumeControlCallbacks) {
        match cb {
            VolumeControlCallbacks::ConnectionState(state, addr) => {
                if self.vc_states.get(&addr).is_some()
                    && state == *self.vc_states.get(&addr).unwrap()
                {
                    return;
                }

                info!(
                    "VolumeControlCallbacks::ConnectionState: [{}]: state={:?}",
                    DisplayAddress(&addr),
                    state
                );

                match state {
                    BtVcConnectionState::Connected => {
                        self.vc_states.insert(addr, state);

                        let group_id = self.get_group_id(addr);
                        match self.le_audio_groups.get(&group_id) {
                            Some(group) if self.is_group_connected(group) => {
                                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                    callback.on_lea_vc_connected(addr, group_id);
                                });

                                // Sync group volume in case this new member has not been adjusted.
                                if let Some(volume) = group.volume {
                                    self.set_group_volume(group_id, volume);
                                }
                            }
                            _ => {
                                self.le_audio_delayed_vc_connection_updates.insert(addr);
                            }
                        }
                    }
                    BtVcConnectionState::Disconnected => {
                        self.vc_states.remove(&addr);
                    }
                    _ => {
                        self.vc_states.insert(addr, state);
                    }
                }
            }
            VolumeControlCallbacks::VolumeState(addr, volume, mute, is_autonomous) => {
                info!(
                    "VolumeControlCallbacks::VolumeState: [{}]: volume={}, mute={}, is_autonomous={}",
                    DisplayAddress(&addr),
                    volume,
                    mute,
                    is_autonomous
                );
            }
            VolumeControlCallbacks::GroupVolumeState(group_id, volume, mute, is_autonomous) => {
                info!(
                    "VolumeControlCallbacks::GroupVolumeState: group_id={}, volume={}, mute={}, is_autonomous={}",
                    group_id, volume, mute, is_autonomous
                );

                // This can come with ~300ms delay, thus notify only when
                // triggered by the headset. Otherwise expect the audio server
                // to know the expected volume.
                if is_autonomous {
                    self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                        callback.on_lea_group_volume_changed(group_id, volume);
                    });
                }

                self.le_audio_groups.entry(group_id).or_default().volume = Some(volume);
            }
            VolumeControlCallbacks::DeviceAvailable(addr, num_offset) => {
                info!(
                    "VolumeControlCallbacks::DeviceAvailable: [{}]: num_offset={}",
                    DisplayAddress(&addr),
                    num_offset
                );
            }
            VolumeControlCallbacks::ExtAudioOutVolume(addr, ext_output_id, offset) => {
                info!(
                    "VolumeControlCallbacks::ExtAudioOutVolume: [{}]: ext_output_id={}, offset={}",
                    DisplayAddress(&addr),
                    ext_output_id,
                    offset
                );
            }
            VolumeControlCallbacks::ExtAudioOutLocation(addr, ext_output_id, location) => {
                info!(
                    "VolumeControlCallbacks::ExtAudioOutLocation: [{}]: ext_output_id={}, location={}",
                    DisplayAddress(&addr),
                    ext_output_id,
                    location
                );
            }
            VolumeControlCallbacks::ExtAudioOutDescription(addr, ext_output_id, descr) => {
                info!(
                    "VolumeControlCallbacks::ExtAudioOutDescription: [{}]: ext_output_id={}, descr={}",
                    DisplayAddress(&addr),
                    ext_output_id,
                    descr
                );
            }
        }
    }

    pub fn dispatch_le_audio_callbacks(&mut self, cb: LeAudioClientCallbacks) {
        match cb {
            LeAudioClientCallbacks::Initialized() => {
                info!("LeAudioClientCallbacks::Initialized: ");
            }
            LeAudioClientCallbacks::ConnectionState(state, addr) => {
                if self.le_audio_states.get(&addr).is_some()
                    && state == *self.le_audio_states.get(&addr).unwrap()
                {
                    return;
                }

                let group_id = self.get_group_id(addr);
                if group_id == LEA_UNKNOWN_GROUP_ID {
                    warn!(
                        "LeAudioClientCallbacks::ConnectionState: [{}] Ignored dispatching of LeAudio callback on a device with no group",
                        DisplayAddress(&addr)
                    );
                    return;
                }

                let is_only_connected_member = match self.le_audio_groups.get(&group_id) {
                    Some(group) => group.devices.iter().all(|&member_addr| {
                        member_addr == addr
                            || *self
                                .le_audio_states
                                .get(&member_addr)
                                .unwrap_or(&BtLeAudioConnectionState::Disconnected)
                                != BtLeAudioConnectionState::Connected
                    }),
                    _ => true,
                };

                info!(
                    "LeAudioClientCallbacks::ConnectionState: [{}]: state={:?}, group_id={}, is_only_connected_member={}",
                    DisplayAddress(&addr),
                    state,
                    group_id,
                    is_only_connected_member
                );

                match state {
                    BtLeAudioConnectionState::Connected => {
                        if is_only_connected_member {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_lea_group_connected(
                                    group_id,
                                    self.adapter_get_remote_name(addr),
                                );
                            });

                            match self.le_audio_delayed_audio_conf_updates.remove(&group_id) {
                                Some(conf) => {
                                    self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                        callback.on_lea_audio_conf(
                                            conf.direction,
                                            conf.group_id,
                                            conf.snk_audio_location,
                                            conf.src_audio_location,
                                            conf.avail_cont,
                                        );
                                    });
                                }
                                _ => {}
                            }
                        }

                        if self.le_audio_delayed_vc_connection_updates.remove(&addr) {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_lea_vc_connected(addr, group_id);
                            });
                        }

                        self.le_audio_states.insert(addr, state);
                    }
                    BtLeAudioConnectionState::Disconnected => {
                        if self.le_audio_states.remove(&addr).is_some() && is_only_connected_member
                        {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_lea_group_disconnected(group_id);
                            });
                        }

                        // In anticipation that it could possibly never be connected.
                        self.le_audio_delayed_vc_connection_updates.remove(&addr);
                    }
                    _ => {
                        self.le_audio_states.insert(addr, state);
                    }
                }
            }
            LeAudioClientCallbacks::GroupStatus(group_id, status) => {
                if self.le_audio_groups.get(&group_id).is_some()
                    && status == self.le_audio_groups.get(&group_id).unwrap().status
                {
                    return;
                }

                info!(
                    "LeAudioClientCallbacks::GroupStatus: group_id={}, status={:?}",
                    group_id, status
                );

                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_lea_group_status(group_id, status);
                });

                self.le_audio_groups.entry(group_id).or_default().status = status;
            }
            LeAudioClientCallbacks::GroupNodeStatus(addr, group_id, status) => {
                info!(
                    "LeAudioClientCallbacks::GroupNodeStatus: [{}]: group_id={}, status={:?}",
                    DisplayAddress(&addr),
                    group_id,
                    status
                );

                match status {
                    BtLeAudioGroupNodeStatus::Added => {
                        match self.le_audio_node_to_group.get(&addr) {
                            Some(old_group_id) if *old_group_id != group_id => {
                                warn!(
                                    "LeAudioClientCallbacks::GroupNodeStatus: [{}]: node already belongs to another group {}",
                                    DisplayAddress(&addr),
                                    old_group_id,
                                );

                                self.remove_device_from_group(addr);
                            }
                            _ => {}
                        }

                        self.le_audio_node_to_group.insert(addr, group_id);

                        let group = self.le_audio_groups.entry(group_id).or_default();

                        group.devices.insert(addr);

                        if let Some(volume) = group.volume {
                            self.set_group_volume(group_id, volume);
                        }
                    }
                    BtLeAudioGroupNodeStatus::Removed => {
                        match self.le_audio_node_to_group.get(&addr) {
                            Some(old_group_id) if *old_group_id == group_id => {
                                self.remove_device_from_group(addr);
                            }
                            Some(old_group_id) if *old_group_id != group_id => {
                                warn!(
                                    "LeAudioClientCallbacks::GroupNodeStatus: [{}]: cannot remove node from group {} because it is in group {}",
                                    DisplayAddress(&addr),
                                    group_id,
                                    old_group_id,
                                );

                                return;
                            }
                            _ => {}
                        }

                        self.le_audio_node_to_group.remove(&addr);
                    }
                    _ => {
                        warn!("LeAudioClientCallbacks::GroupNodeStatus: Unknown status for GroupNodeStatus {:?}", status);
                    }
                }

                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_lea_group_node_status(addr, group_id, status);
                });
            }
            LeAudioClientCallbacks::AudioConf(
                direction,
                group_id,
                snk_audio_location,
                src_audio_location,
                avail_cont,
            ) => {
                info!(
                    "LeAudioClientCallbacks::AudioConf: direction={}, group_id={}, snk_audio_location={}, src_audio_location={}, avail_cont={}",
                    direction, group_id, snk_audio_location, src_audio_location, avail_cont,
                );

                match self.le_audio_groups.get(&group_id) {
                    Some(group) if self.is_group_connected(group) => {
                        self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                            callback.on_lea_audio_conf(
                                direction,
                                group_id,
                                snk_audio_location,
                                src_audio_location,
                                avail_cont,
                            );
                        });
                    }
                    _ => {
                        self.le_audio_delayed_audio_conf_updates.insert(
                            group_id,
                            LEAAudioConf {
                                direction,
                                group_id,
                                snk_audio_location,
                                src_audio_location,
                                avail_cont,
                            },
                        );
                    }
                }
            }
            LeAudioClientCallbacks::SinkAudioLocationAvailable(addr, snk_audio_locations) => {
                info!("LeAudioClientCallbacks::SinkAudioLocationAvailable: [{}]: snk_audio_locations={:?}", DisplayAddress(&addr), snk_audio_locations);
            }
            LeAudioClientCallbacks::AudioLocalCodecCapabilities(
                local_input_codec_conf,
                local_output_codec_conf,
            ) => {
                info!(
                    "LeAudioClientCallbacks::AudioLocalCodecCapabilities: local_input_codec_conf={:?}, local_output_codec_conf={:?}",
                    local_input_codec_conf, local_output_codec_conf
                );
            }
            LeAudioClientCallbacks::AudioGroupCodecConf(
                group_id,
                input_codec_conf,
                output_codec_conf,
                input_caps,
                output_caps,
            ) => {
                info!("LeAudioClientCallbacks::AudioGroupCodecConf: group_id={}, input_codec_conf={:?}, output_codec_conf={:?}, input_caps={:?}, output_caps={:?}",
                      group_id, input_codec_conf, output_codec_conf, input_caps, output_caps);
            }
            LeAudioClientCallbacks::UnicastMonitorModeStatus(direction, status) => {
                if self.le_audio_unicast_monitor_mode_status.get(&direction.into()).is_some()
                    && status
                        == *self
                            .le_audio_unicast_monitor_mode_status
                            .get(&direction.into())
                            .unwrap()
                {
                    return;
                }

                info!(
                    "LeAudioClientCallbacks::UnicastMonitorModeStatus: direction={:?}, status={:?}",
                    direction, status
                );

                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_lea_unicast_monitor_mode_status(direction, status);
                });

                self.le_audio_unicast_monitor_mode_status.insert(direction.into(), status);
            }
            LeAudioClientCallbacks::GroupStreamStatus(group_id, status) => {
                if self.le_audio_groups.get(&group_id).is_some()
                    && status == self.le_audio_groups.get(&group_id).unwrap().stream_status
                {
                    return;
                }

                info!(
                    "LeAudioClientCallbacks::GroupStreamStatus: group_id={} status {:?}",
                    group_id, status
                );

                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_lea_group_stream_status(group_id, status);
                });

                self.le_audio_groups.entry(group_id).or_default().stream_status = status;
            }
        }
    }

    pub fn dispatch_a2dp_callbacks(&mut self, cb: A2dpCallbacks) {
        match cb {
            A2dpCallbacks::ConnectionState(addr, state, error) => {
                if self.a2dp_states.get(&addr).is_some()
                    && state == *self.a2dp_states.get(&addr).unwrap()
                {
                    return;
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::A2dpSink as u32,
                    error.status,
                    state.clone() as u32,
                );
                match state {
                    BtavConnectionState::Connected => {
                        info!("[{}]: a2dp connected.", DisplayAddress(&addr));

                        if !self.connected_profiles.is_empty()
                            && !self.connected_profiles.contains_key(&addr)
                        {
                            warn!(
                                "Another media connection exists. Disconnect a2dp from {}",
                                DisplayAddress(&addr)
                            );
                            self.a2dp.disconnect(addr);
                            return;
                        }

                        self.a2dp_states.insert(addr, state);
                        self.add_connected_profile(addr, Profile::A2dpSink);
                    }
                    BtavConnectionState::Disconnected => {
                        info!("[{}]: a2dp disconnected.", DisplayAddress(&addr));

                        if !self.connected_profiles.contains_key(&addr) {
                            warn!(
                                "Ignoring non-primary a2dp disconnection from {}",
                                DisplayAddress(&addr)
                            );
                            return;
                        }

                        if self.a2dp_audio_connection_listener.is_some() {
                            let listener = self.a2dp_audio_connection_listener.take().unwrap();
                            let data: Vec<u8> = vec![0];
                            self.write_data_to_listener(listener, data);
                        }

                        self.a2dp_states.remove(&addr);
                        self.a2dp_caps.remove(&addr);
                        self.a2dp_audio_state.remove(&addr);
                        self.rm_connected_profile(addr, Profile::A2dpSink, true);
                    }
                    _ => {
                        self.a2dp_states.insert(addr, state);
                    }
                }
            }
            A2dpCallbacks::AudioState(addr, state) => {
                info!("[{}]: a2dp audio state: {:?}", DisplayAddress(&addr), state);

                let started: u8 = match state {
                    BtavAudioState::Started => 1,
                    _ => 0,
                };

                if self.a2dp_audio_connection_listener.is_some() {
                    let listener = self.a2dp_audio_connection_listener.take().unwrap();
                    let data: Vec<u8> = vec![started];
                    self.write_data_to_listener(listener, data);
                }

                self.a2dp_audio_state.insert(addr, state);
            }
            A2dpCallbacks::AudioConfig(addr, _config, _local_caps, a2dp_caps) => {
                debug!("[{}]: a2dp updated audio config: {:?}", DisplayAddress(&addr), a2dp_caps);
                self.a2dp_caps.insert(addr, a2dp_caps);
            }
            A2dpCallbacks::MandatoryCodecPreferred(_addr) => {}
        }
    }

    fn disconnect_device(txl: Sender<Message>, addr: RawAddress) {
        let device = BluetoothDevice::new(addr, "".to_string());
        topstack::get_runtime().spawn(async move {
            let _ = txl.send(Message::DisconnectDevice(device)).await;
        });
    }

    pub fn dispatch_avrcp_callbacks(&mut self, cb: AvrcpCallbacks) {
        match cb {
            AvrcpCallbacks::AvrcpDeviceConnected(addr, supported) => {
                info!(
                    "[{}]: avrcp connected. Absolute volume support: {}.",
                    DisplayAddress(&addr),
                    supported
                );

                // If is device initiated the AVRCP connection, emit a fake connecting state as
                // stack don't receive one.
                if self.avrcp_states.get(&addr) != Some(&BtavConnectionState::Connecting) {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::AvrcpController as u32,
                    BtStatus::Success,
                    BtavConnectionState::Connected as u32,
                );

                if !self.connected_profiles.is_empty()
                    && !self.connected_profiles.contains_key(&addr)
                {
                    warn!(
                        "Another media connection exists. Disconnect avrcp from {}",
                        DisplayAddress(&addr)
                    );
                    self.avrcp.disconnect(addr);
                    return;
                }

                self.avrcp_states.insert(addr, BtavConnectionState::Connected);

                match self.uinput.create(self.adapter_get_remote_name(addr), addr.to_string()) {
                    Ok(()) => info!("uinput device created for: {}", DisplayAddress(&addr)),
                    Err(e) => warn!("{}", e),
                }

                // Notify change via callback if device is added.
                if self.absolute_volume != supported {
                    let guard = self.fallback_tasks.lock().unwrap();
                    if let Some(task) = guard.get(&addr) {
                        if task.is_none() {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_absolute_volume_supported_changed(supported);
                            });
                        }
                    }
                }

                self.absolute_volume = supported;
                self.add_connected_profile(addr, Profile::AvrcpController);
            }
            AvrcpCallbacks::AvrcpDeviceDisconnected(addr) => {
                info!("[{}]: avrcp disconnected.", DisplayAddress(&addr));

                // If the peer device initiated the AVRCP disconnection, emit a fake connecting
                // state as stack don't receive one.
                if self.avrcp_states.get(&addr) != Some(&BtavConnectionState::Disconnecting) {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Disconnecting as u32,
                    );
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::AvrcpController as u32,
                    BtStatus::Success,
                    BtavConnectionState::Disconnected as u32,
                );

                if !self.connected_profiles.contains_key(&addr) {
                    warn!(
                        "Ignoring non-primary avrcp disconnection from {}",
                        DisplayAddress(&addr)
                    );
                    return;
                }
                self.avrcp_states.remove(&addr);

                self.uinput.close(addr.to_string());

                // TODO: better support for multi-device
                self.absolute_volume = false;

                // This may be considered a critical profile in the extreme case
                // where only AVRCP was connected.
                let is_profile_critical = match self.connected_profiles.get(&addr) {
                    Some(profiles) => *profiles == HashSet::from([Profile::AvrcpController]),
                    None => false,
                };

                self.rm_connected_profile(addr, Profile::AvrcpController, is_profile_critical);
            }
            AvrcpCallbacks::AvrcpAbsoluteVolumeUpdate(volume) => {
                for (addr, state) in self.device_states.lock().unwrap().iter() {
                    info!("[{}]: state {:?}", DisplayAddress(addr), state);
                    match state {
                        DeviceConnectionStates::ConnectingBeforeRetry
                        | DeviceConnectionStates::ConnectingAfterRetry
                        | DeviceConnectionStates::WaitingConnection => {
                            self.delay_volume_update.insert(Profile::AvrcpController, volume);
                        }
                        DeviceConnectionStates::FullyConnected => {
                            self.delay_volume_update.remove(&Profile::AvrcpController);
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_absolute_volume_changed(volume);
                            });
                            return;
                        }
                        _ => {}
                    }
                }
            }
            AvrcpCallbacks::AvrcpSendKeyEvent(key, value) => {
                match self.uinput.send_key(key, value) {
                    Ok(()) => (),
                    Err(e) => warn!("{}", e),
                }

                const AVRCP_ID_PAUSE: u8 = 0x46;
                const AVRCP_STATE_PRESS: u8 = 0;

                // Per MPS v1.0, on receiving a pause key through AVRCP,
                // central should pause the A2DP stream with an AVDTP suspend command.
                if self.mps_qualification_enabled
                    && key == AVRCP_ID_PAUSE
                    && value == AVRCP_STATE_PRESS
                {
                    self.suspend_audio_request_impl();
                }
            }
            AvrcpCallbacks::AvrcpSetActiveDevice(addr) => {
                self.uinput.set_active_device(addr.to_string());
            }
        }
    }

    pub fn dispatch_media_actions(&mut self, action: MediaActions) {
        match action {
            MediaActions::Connect(address) => self.connect(address),
            MediaActions::Disconnect(address) => self.disconnect(address),
            MediaActions::ForceEnterConnected(address) => self.force_enter_connected(address),

            MediaActions::ConnectLea(address) => self.connect_lea(address),
            MediaActions::DisconnectLea(address) => self.disconnect_lea(address),
            MediaActions::ConnectVc(address) => self.connect_vc(address),
            MediaActions::DisconnectVc(address) => self.disconnect_vc(address),
            MediaActions::ConnectCsis(address) => self.connect_csis(address),
            MediaActions::DisconnectCsis(address) => self.disconnect_csis(address),

            MediaActions::ConnectLeaGroupByMemberAddress(address) => {
                self.connect_lea_group_by_member_address(address)
            }
            MediaActions::DisconnectLeaGroupByMemberAddress(address) => {
                self.disconnect_lea_group_by_member_address(address)
            }
        }
    }

    pub fn dispatch_hfp_callbacks(&mut self, cb: HfpCallbacks) {
        match cb {
            HfpCallbacks::ConnectionState(state, addr) => {
                if self.hfp_states.get(&addr).is_some()
                    && state == *self.hfp_states.get(&addr).unwrap()
                {
                    return;
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::Hfp as u32,
                    BtStatus::Success,
                    state.clone() as u32,
                );
                match state {
                    BthfConnectionState::Connected => {
                        info!("[{}]: hfp connected.", DisplayAddress(&addr));
                    }
                    BthfConnectionState::SlcConnected => {
                        info!("[{}]: hfp slc connected.", DisplayAddress(&addr));

                        if !self.connected_profiles.is_empty()
                            && !self.connected_profiles.contains_key(&addr)
                        {
                            warn!(
                                "Another media connection exists. Disconnect hfp from {}",
                                DisplayAddress(&addr)
                            );
                            self.hfp.disconnect(addr);
                            return;
                        }

                        // The device may not support codec-negotiation,
                        // in which case we shall assume it supports CVSD at this point.
                        self.hfp_cap.entry(addr).or_insert(HfpCodecFormat::CVSD);
                        self.add_connected_profile(addr, Profile::Hfp);

                        // Connect SCO if phone operations are enabled and an active call exists.
                        // This is only used for Bluetooth HFP qualification.
                        if self.mps_qualification_enabled && self.phone_state.num_active > 0 {
                            debug!("[{}]: Connect SCO due to active call.", DisplayAddress(&addr));
                            self.start_sco_call_impl(addr, false, HfpCodecBitId::NONE);
                        }

                        if self.phone_ops_enabled {
                            self.uhid_create(addr);
                        }
                    }
                    BthfConnectionState::Disconnected => {
                        info!("[{}]: hfp disconnected.", DisplayAddress(&addr));

                        if !self.connected_profiles.contains_key(&addr) {
                            warn!(
                                "Ignoring non-primary hfp disconnection from {}",
                                DisplayAddress(&addr)
                            );
                            return;
                        }

                        if self.hfp_audio_connection_listener.is_some() {
                            let listener = self.hfp_audio_connection_listener.take().unwrap();
                            let data: Vec<u8> = vec![0];
                            self.write_data_to_listener(listener, data);
                        }

                        self.uhid_destroy(&addr);
                        self.hfp_states.remove(&addr);
                        self.hfp_cap.remove(&addr);
                        self.hfp_audio_state.remove(&addr);
                        self.rm_connected_profile(addr, Profile::Hfp, true);
                    }
                    BthfConnectionState::Connecting => {
                        info!("[{}]: hfp connecting.", DisplayAddress(&addr));
                    }
                    BthfConnectionState::Disconnecting => {
                        info!("[{}]: hfp disconnecting.", DisplayAddress(&addr));
                    }
                }

                self.hfp_states.insert(addr, state);
            }
            HfpCallbacks::AudioState(state, addr) => {
                if self.hfp_states.get(&addr).is_none()
                    || BthfConnectionState::SlcConnected != *self.hfp_states.get(&addr).unwrap()
                {
                    warn!("[{}]: Unknown address hfp or slc not ready", DisplayAddress(&addr));
                    return;
                }

                match state {
                    BthfAudioState::Connected => {
                        info!("[{}]: hfp audio connected.", DisplayAddress(&addr));

                        self.hfp_audio_state.insert(addr, state);

                        if self.hfp_audio_connection_listener.is_some() {
                            let listener = self.hfp_audio_connection_listener.take().unwrap();
                            let codec = self.get_hfp_audio_final_codecs(addr);
                            let data: Vec<u8> = vec![codec];
                            self.write_data_to_listener(listener, data);
                        }

                        if self.should_insert_call_when_sco_start(addr) {
                            // This triggers a +CIEV command to set the call status for HFP devices.
                            // It is required for some devices to provide sound.
                            self.place_active_call();
                            self.notify_telephony_event(&addr, TelephonyEvent::CRASPlaceActiveCall);
                        }
                    }
                    BthfAudioState::Disconnected => {
                        info!("[{}]: hfp audio disconnected.", DisplayAddress(&addr));

                        if self.hfp_audio_connection_listener.is_some() {
                            let listener = self.hfp_audio_connection_listener.take().unwrap();
                            let data: Vec<u8> = vec![0];
                            self.write_data_to_listener(listener, data);
                        }

                        // Ignore disconnected -> disconnected
                        if let Some(BthfAudioState::Connected) =
                            self.hfp_audio_state.insert(addr, state)
                        {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_hfp_audio_disconnected(addr);
                            });
                        }

                        if self.should_insert_call_when_sco_start(addr) {
                            // Remove the only call related to the one added for devices requesting to force +CIEV command
                            self.call_list = vec![];
                            self.phone_state.num_active = 0;
                            self.phone_state_change("".into());
                            self.notify_telephony_event(
                                &addr,
                                TelephonyEvent::CRASRemoveActiveCall,
                            );
                        }

                        // Resume the A2DP stream when a phone call ended (per MPS v1.0).
                        self.try_a2dp_resume();
                    }
                    BthfAudioState::Connecting => {
                        info!("[{}]: hfp audio connecting.", DisplayAddress(&addr));
                    }
                    BthfAudioState::Disconnecting => {
                        info!("[{}]: hfp audio disconnecting.", DisplayAddress(&addr));
                    }
                }
            }
            HfpCallbacks::VolumeUpdate(volume, addr) => {
                if self.hfp_states.get(&addr).is_none()
                    || BthfConnectionState::SlcConnected != *self.hfp_states.get(&addr).unwrap()
                {
                    warn!("[{}]: Unknown address hfp or slc not ready", DisplayAddress(&addr));
                    return;
                }

                let states = self.device_states.lock().unwrap();
                info!(
                    "[{}]: VolumeUpdate state: {:?}",
                    DisplayAddress(&addr),
                    states.get(&addr).unwrap()
                );
                match states.get(&addr).unwrap() {
                    DeviceConnectionStates::ConnectingBeforeRetry
                    | DeviceConnectionStates::ConnectingAfterRetry
                    | DeviceConnectionStates::WaitingConnection => {
                        self.delay_volume_update.insert(Profile::Hfp, volume);
                    }
                    DeviceConnectionStates::FullyConnected => {
                        self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                            callback.on_hfp_volume_changed(volume, addr);
                        });
                    }
                    _ => {}
                }
            }
            HfpCallbacks::MicVolumeUpdate(volume, addr) => {
                if !self.phone_ops_enabled {
                    return;
                }

                if self.hfp_states.get(&addr).is_none()
                    || BthfConnectionState::SlcConnected != *self.hfp_states.get(&addr).unwrap()
                {
                    warn!("[{}]: Unknown address hfp or slc not ready", DisplayAddress(&addr));
                    return;
                }

                if let Some(uhid) = self.uhid.get_mut(&addr) {
                    if volume == 0 && !uhid.muted {
                        // We expect the application to send back UHID output report and
                        // update uhid.mute in dispatch_uhid_hfp_output_callback later.
                        self.uhid_send_phone_mute_input_report(&addr, true);
                        self.notify_telephony_event(&addr, TelephonyEvent::HFMicMute);
                    } else if volume > 0 {
                        uhid.volume = volume;
                        if uhid.muted {
                            // We expect the application to send back UHID output report and
                            // update uhid.mute in dispatch_uhid_hfp_output_callback later.
                            self.uhid_send_phone_mute_input_report(&addr, false);
                            self.notify_telephony_event(&addr, TelephonyEvent::HFMicUnmute);
                        }
                    }
                }
            }
            HfpCallbacks::VendorSpecificAtCommand(at_string, addr) => {
                let at_command = match parse_at_command_data(at_string) {
                    Ok(command) => command,
                    Err(e) => {
                        debug!("{}", e);
                        return;
                    }
                };
                let battery_level = match calculate_battery_percent(at_command.clone()) {
                    Ok(level) => level,
                    Err(e) => {
                        debug!("{}", e);
                        return;
                    }
                };
                let source_info = match at_command.vendor {
                    Some(vendor) => format!("HFP - {}", vendor),
                    _ => "HFP - UnknownAtCommand".to_string(),
                };
                self.battery_provider_manager.lock().unwrap().set_battery_info(
                    self.battery_provider_id,
                    BatterySet::new(
                        addr,
                        uuid::HFP.to_string(),
                        source_info,
                        vec![Battery { percentage: battery_level, variant: "".to_string() }],
                    ),
                );
            }
            HfpCallbacks::BatteryLevelUpdate(battery_level, addr) => {
                let battery_set = BatterySet::new(
                    addr,
                    uuid::HFP.to_string(),
                    "HFP".to_string(),
                    vec![Battery { percentage: battery_level as u32, variant: "".to_string() }],
                );
                self.battery_provider_manager
                    .lock()
                    .unwrap()
                    .set_battery_info(self.battery_provider_id, battery_set);
            }
            HfpCallbacks::WbsCapsUpdate(wbs_supported, addr) => {
                let is_transparent_coding_format_supported = self
                    .adapter
                    .lock()
                    .unwrap()
                    .is_coding_format_supported(EscoCodingFormat::TRANSPARENT);

                let is_msbc_coding_format_supported =
                    self.adapter.lock().unwrap().is_coding_format_supported(EscoCodingFormat::MSBC);

                let mut codec_diff = HfpCodecFormat::NONE;
                if is_transparent_coding_format_supported {
                    codec_diff |= HfpCodecFormat::MSBC_TRANSPARENT;
                }
                if is_msbc_coding_format_supported {
                    codec_diff |= HfpCodecFormat::MSBC;
                }

                if let Some(cur_hfp_cap) = self.hfp_cap.get_mut(&addr) {
                    if wbs_supported {
                        *cur_hfp_cap |= codec_diff;
                    } else {
                        *cur_hfp_cap &= !codec_diff;
                    }
                } else {
                    let new_hfp_cap = match wbs_supported {
                        true => HfpCodecFormat::CVSD | codec_diff,
                        false => HfpCodecFormat::CVSD,
                    };
                    self.hfp_cap.insert(addr, new_hfp_cap);
                }
            }
            HfpCallbacks::SwbCapsUpdate(swb_supported, addr) => {
                // LC3 can be propagated to this point only if adapter supports transparent mode.
                if let Some(cur_hfp_cap) = self.hfp_cap.get_mut(&addr) {
                    if swb_supported {
                        *cur_hfp_cap |= HfpCodecFormat::LC3_TRANSPARENT;
                    } else {
                        *cur_hfp_cap &= !HfpCodecFormat::LC3_TRANSPARENT;
                    }
                } else {
                    let new_hfp_cap = match swb_supported {
                        true => HfpCodecFormat::CVSD | HfpCodecFormat::LC3_TRANSPARENT,
                        false => HfpCodecFormat::CVSD,
                    };
                    self.hfp_cap.insert(addr, new_hfp_cap);
                }
            }
            HfpCallbacks::IndicatorQuery(addr) => {
                debug!(
                    "[{}]: Responding CIND query with device={:?} phone={:?}",
                    DisplayAddress(&addr),
                    self.telephony_device_status,
                    self.phone_state,
                );
                let status = self.hfp.indicator_query_response(
                    self.telephony_device_status,
                    self.phone_state,
                    addr,
                );
                if status != BtStatus::Success {
                    warn!("[{}]: CIND response failed, status={:?}", DisplayAddress(&addr), status);
                }
            }
            HfpCallbacks::CurrentCallsQuery(addr) => {
                debug!(
                    "[{}]: Responding CLCC query with call_list={:?}",
                    DisplayAddress(&addr),
                    self.call_list,
                );
                let status = self.hfp.current_calls_query_response(&self.call_list, addr);
                if status != BtStatus::Success {
                    warn!("[{}]: CLCC response failed, status={:?}", DisplayAddress(&addr), status);
                }
                self.notify_telephony_event(&addr, TelephonyEvent::HFCurrentCallsQuery);
            }
            HfpCallbacks::AnswerCall(addr) => {
                if !self.phone_ops_enabled && !self.mps_qualification_enabled {
                    warn!("Unexpected answer call. phone_ops_enabled and mps_qualification_enabled does not enabled.");
                    return;
                }
                if self.mps_qualification_enabled {
                    // In qualification mode we expect no application to interact with.
                    // So we just jump right in to the telephony ops implementation.
                    let id = BLUETOOTH_TELEPHONY_UHID_REPORT_ID;
                    let mut data = UHID_OUTPUT_NONE;
                    data |= UHID_OUTPUT_OFF_HOOK;
                    self.dispatch_uhid_hfp_output_callback(addr, id, data);
                } else {
                    // We expect the application to send back UHID output report and
                    // trigger dispatch_uhid_hfp_output_callback later.
                    self.uhid_send_hook_switch_input_report(&addr, true);
                    self.notify_telephony_event(&addr, TelephonyEvent::HFAnswerCall);
                }
            }
            HfpCallbacks::HangupCall(addr) => {
                if !self.phone_ops_enabled && !self.mps_qualification_enabled {
                    warn!("Unexpected hangup call. phone_ops_enabled and mps_qualification_enabled does not enabled.");
                    return;
                }
                if self.mps_qualification_enabled {
                    // In qualification mode we expect no application to interact with.
                    // So we just jump right in to the telephony ops implementation.
                    let id = BLUETOOTH_TELEPHONY_UHID_REPORT_ID;
                    let mut data = UHID_OUTPUT_NONE;
                    data &= !UHID_OUTPUT_OFF_HOOK;
                    self.dispatch_uhid_hfp_output_callback(addr, id, data);
                } else {
                    // We expect the application to send back UHID output report and
                    // trigger dispatch_uhid_hfp_output_callback later.
                    self.uhid_send_hook_switch_input_report(&addr, false);
                    self.notify_telephony_event(&addr, TelephonyEvent::HFHangupCall);
                }
            }
            HfpCallbacks::DialCall(number, addr) => {
                if !self.mps_qualification_enabled {
                    warn!("Unexpected dail call. mps_qualification_enabled does not enabled.");
                    self.simple_at_response(false, addr);
                    return;
                }
                let number = if number.is_empty() {
                    self.last_dialing_number.clone()
                } else if number.starts_with('>') {
                    self.memory_dialing_number.clone()
                } else {
                    Some(number)
                };

                if let Some(number) = number {
                    self.dialing_call_impl(number, Some(addr));
                } else {
                    self.simple_at_response(false, addr);
                }
            }
            HfpCallbacks::CallHold(command, addr) => {
                if !self.mps_qualification_enabled {
                    warn!("Unexpected call hold. mps_qualification_enabled does not enabled.");
                    self.simple_at_response(false, addr);
                    return;
                }
                let success = match command {
                    CallHoldCommand::ReleaseHeld => self.release_held_impl(Some(addr)),
                    CallHoldCommand::ReleaseActiveAcceptHeld => {
                        self.release_active_accept_held_impl(Some(addr))
                    }
                    CallHoldCommand::HoldActiveAcceptHeld => {
                        self.hold_active_accept_held_impl(Some(addr))
                    }
                    _ => false, // We only support the 3 operations above.
                };
                if !success {
                    warn!(
                        "[{}]: Unexpected or unsupported CHLD command {:?} from HF",
                        DisplayAddress(&addr),
                        command
                    );
                }
            }
            HfpCallbacks::DebugDump(
                active,
                codec_id,
                total_num_decoded_frames,
                pkt_loss_ratio,
                begin_ts,
                end_ts,
                pkt_status_in_hex,
                pkt_status_in_binary,
            ) => {
                let is_wbs = codec_id == HfpCodecId::MSBC as u16;
                let is_swb = codec_id == HfpCodecId::LC3 as u16;
                debug!("[HFP] DebugDump: active:{}, codec_id:{}", active, codec_id);
                if is_wbs || is_swb {
                    debug!(
                        "total_num_decoded_frames:{} pkt_loss_ratio:{}",
                        total_num_decoded_frames, pkt_loss_ratio
                    );
                    debug!("begin_ts:{} end_ts:{}", begin_ts, end_ts);
                    debug!(
                        "pkt_status_in_hex:{} pkt_status_in_binary:{}",
                        pkt_status_in_hex, pkt_status_in_binary
                    );
                }
                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_hfp_debug_dump(
                        active,
                        codec_id,
                        total_num_decoded_frames,
                        pkt_loss_ratio,
                        begin_ts,
                        end_ts,
                        pkt_status_in_hex.clone(),
                        pkt_status_in_binary.clone(),
                    );
                });
            }
        }
    }

    pub fn remove_callback(&mut self, id: u32) -> bool {
        self.callbacks.lock().unwrap().remove_callback(id)
    }

    pub fn remove_telephony_callback(&mut self, id: u32) -> bool {
        self.telephony_callbacks.lock().unwrap().remove_callback(id)
    }

    fn uhid_create(&mut self, addr: RawAddress) {
        debug!(
            "[{}]: UHID create: PhoneOpsEnabled {}",
            DisplayAddress(&addr),
            self.phone_ops_enabled,
        );
        // To change the value of phone_ops_enabled, you need to toggle the BluetoothFlossTelephony feature flag on chrome://flags.
        if !self.phone_ops_enabled {
            return;
        }
        if self.uhid.contains_key(&addr) {
            warn!("[{}]: UHID create: entry already created", DisplayAddress(&addr));
            return;
        }
        let adapter_addr = self.adapter.lock().unwrap().get_address().to_string().to_lowercase();
        let txl = self.tx.clone();
        self.uhid.insert(
            addr,
            UHid {
                handle: UHidHfp::create(
                    adapter_addr,
                    addr.to_string(),
                    self.adapter_get_remote_name(addr),
                    move |m| {
                        match m {
                            OutputEvent::Close => {
                                txl.blocking_send(Message::UHidTelephonyUseCallback(addr, false))
                                    .unwrap();
                            }
                            OutputEvent::Open => {
                                txl.blocking_send(Message::UHidTelephonyUseCallback(addr, true))
                                    .unwrap();
                            }
                            OutputEvent::Output { data } => {
                                txl.blocking_send(Message::UHidHfpOutputCallback(
                                    addr, data[0], data[1],
                                ))
                                .unwrap();
                            }
                            _ => (),
                        };
                    },
                ),
                volume: 15, // By default use maximum volume in case microphone gain has not been received
                muted: false,
                is_open: false,
            },
        );
        self.notify_telephony_event(&addr, TelephonyEvent::UHidCreate);
    }

    fn uhid_destroy(&mut self, addr: &RawAddress) {
        if let Some(uhid) = self.uhid.get_mut(addr) {
            debug!("[{}]: UHID destroy", DisplayAddress(addr));
            match uhid.handle.destroy() {
                Err(e) => log::error!(
                    "[{}]: UHID destroy: Fail to destroy uhid {}",
                    DisplayAddress(addr),
                    e
                ),
                Ok(_) => (),
            };
            self.uhid.remove(addr);
            self.notify_telephony_event(addr, TelephonyEvent::UHidDestroy);
        } else {
            debug!("[{}]: UHID destroy: not a UHID device", DisplayAddress(addr));
        }
    }

    fn uhid_send_input_event_report(&mut self, addr: &RawAddress, data: u8) {
        if !self.phone_ops_enabled {
            return;
        }
        if let Some(uhid) = self.uhid.get_mut(addr) {
            info!(
                "[{}]: UHID: Send telephony hid input report. hook_switch({}), mute({}), drop({})",
                DisplayAddress(addr),
                (data & UHID_INPUT_HOOK_SWITCH) != 0,
                (data & UHID_INPUT_PHONE_MUTE) != 0,
                (data & UHID_INPUT_DROP) != 0,
            );
            match uhid.handle.send_input(data) {
                Err(e) => log::error!(
                    "[{}]: UHID: Fail to send hid input report. err:{}",
                    DisplayAddress(addr),
                    e
                ),
                Ok(_) => (),
            };
        }
    }

    fn uhid_send_hook_switch_input_report(&mut self, addr: &RawAddress, hook: bool) {
        if !self.phone_ops_enabled {
            return;
        }
        if let Some(uhid) = self.uhid.get(addr) {
            let mut data = UHID_INPUT_NONE;
            if hook {
                data |= UHID_INPUT_HOOK_SWITCH;
            } else if self.phone_state.state == CallState::Incoming {
                data |= UHID_INPUT_DROP;
            }
            // Preserve the muted state when sending the hook switch event.
            if uhid.muted {
                data |= UHID_INPUT_PHONE_MUTE;
            }
            self.uhid_send_input_event_report(addr, data);
        };
    }
    fn uhid_send_phone_mute_input_report(&mut self, addr: &RawAddress, muted: bool) {
        if !self.phone_ops_enabled {
            return;
        }
        if self.uhid.get(addr).is_some() {
            let mut data = UHID_INPUT_NONE;
            // Preserve the hook switch state when sending the microphone mute event.
            let call_active = self.phone_state.num_active > 0;
            if call_active {
                data |= UHID_INPUT_HOOK_SWITCH;
            }
            info!(
                "[{}]: UHID: Send phone_mute({}) hid input report. hook-switch({})",
                DisplayAddress(addr),
                muted,
                call_active
            );
            if muted {
                data |= UHID_INPUT_PHONE_MUTE;
                self.uhid_send_input_event_report(addr, data);
            } else {
                // We follow the same pattern as the USB headset, which sends an
                // additional phone mute=1 event when unmuting the microphone.
                // Based on our testing, Some applications do not respond to phone
                // mute=0 and treat the phone mute=1 event as a toggle rather than
                // an on off control.
                data |= UHID_INPUT_PHONE_MUTE;
                self.uhid_send_input_event_report(addr, data);
                data &= !UHID_INPUT_PHONE_MUTE;
                self.uhid_send_input_event_report(addr, data);
            }
        };
    }

    pub fn dispatch_uhid_hfp_output_callback(&mut self, addr: RawAddress, id: u8, data: u8) {
        if !self.phone_ops_enabled {
            warn!("Unexpected dispatch_uhid_hfp_output_callback uhid output. phone_ops_enabled does not enabled.");
            return;
        }

        debug!(
            "[{}]: UHID: Received output report: id {}, data {}",
            DisplayAddress(&addr),
            id,
            data
        );

        let uhid = match self.uhid.get_mut(&addr) {
            Some(uhid) => uhid,
            None => {
                warn!("[{}]: UHID: No valid UHID", DisplayAddress(&addr));
                return;
            }
        };

        if id == BLUETOOTH_TELEPHONY_UHID_REPORT_ID {
            let mute = data & UHID_OUTPUT_MUTE;
            if mute == UHID_OUTPUT_MUTE && !uhid.muted {
                uhid.muted = true;
                self.set_hfp_mic_volume(0, addr);
                self.notify_telephony_event(&addr, TelephonyEvent::UHidMicMute);
            } else if mute != UHID_OUTPUT_MUTE && uhid.muted {
                uhid.muted = false;
                let saved_volume = uhid.volume;
                self.set_hfp_mic_volume(saved_volume, addr);
                self.notify_telephony_event(&addr, TelephonyEvent::UHidMicUnmute);
            }

            let call_state = data & (UHID_OUTPUT_RING | UHID_OUTPUT_OFF_HOOK);
            if call_state == UHID_OUTPUT_NONE {
                self.hangup_call_impl();
                self.notify_telephony_event(&addr, TelephonyEvent::UHidHangupCall);
            } else if call_state == UHID_OUTPUT_RING {
                self.incoming_call_impl("".into());
                self.notify_telephony_event(&addr, TelephonyEvent::UHidIncomingCall);
            } else if call_state == UHID_OUTPUT_OFF_HOOK {
                if self.phone_state.state == CallState::Incoming {
                    self.answer_call_impl();
                    self.notify_telephony_event(&addr, TelephonyEvent::UHidAnswerCall);
                } else if self.phone_state.state == CallState::Idle {
                    self.place_active_call();
                    self.notify_telephony_event(&addr, TelephonyEvent::UHidPlaceActiveCall);
                }
                self.uhid_send_hook_switch_input_report(&addr, true);
            }
        }
    }

    pub fn dispatch_uhid_telephony_use_callback(&mut self, addr: RawAddress, state: bool) {
        let uhid = match self.uhid.get_mut(&addr) {
            Some(uhid) => uhid,
            None => {
                warn!("[{}]: UHID: No valid UHID", DisplayAddress(&addr));
                return;
            }
        };

        uhid.is_open = state;

        info!("[{}]: UHID: floss telephony device is open: {}", DisplayAddress(&addr), state);
        // A hangup call is necessary both when opening and closing the UHID device,
        // although for different reasons:
        //  - On open: To prevent conflicts with existing SCO calls in CRAS and establish
        //             a clean environment for Bluetooth Telephony operations.
        //  - On close: As there's a HID call for each WebHID call, even if it has been
        //              answered in the app or pre-exists, and that an app which disconnects
        //              from WebHID may not have trigger the UHID_OUTPUT_NONE, we need to
        //              remove all pending HID calls on telephony use release to keep lower
        //              HF layer in sync and not prevent A2DP streaming.
        self.hangup_call_impl();

        if state {
            self.notify_telephony_event(&addr, TelephonyEvent::UHidOpen);
        } else {
            self.notify_telephony_event(&addr, TelephonyEvent::UHidClose);
        }
    }

    fn notify_telephony_event(&mut self, addr: &RawAddress, event: TelephonyEvent) {
        // Simplified call status: Assumes at most one call in the list.
        // Defaults to Idle if no calls are present.
        // Revisit this logic if the system supports multiple concurrent calls in the future (e.g., three-way-call).
        let mut call_state = CallState::Idle;
        self.call_list.first().map(|c| call_state = c.state);
        self.telephony_callbacks.lock().unwrap().for_all_callbacks(|callback| {
            callback.on_telephony_event(*addr, u8::from(event), u8::from(call_state));
        });
    }

    fn set_hfp_mic_volume(&mut self, volume: u8, addr: RawAddress) {
        let vol = match i8::try_from(volume) {
            Ok(val) if val <= 15 => val,
            _ => {
                warn!("[{}]: Ignore invalid mic volume {}", DisplayAddress(&addr), volume);
                return;
            }
        };

        if self.hfp_states.get(&addr).is_none() {
            warn!(
                "[{}]: Ignore mic volume event for unconnected or disconnected HFP device",
                DisplayAddress(&addr)
            );
            return;
        }

        let status = self.hfp.set_mic_volume(vol, addr);
        if status != BtStatus::Success {
            warn!("[{}]: Failed to set mic volume to {}", DisplayAddress(&addr), vol);
        }
    }

    fn notify_critical_profile_disconnected(&mut self, addr: RawAddress) {
        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(&addr),
            DeviceConnectionStates::Disconnecting
        );

        let mut states = self.device_states.lock().unwrap();
        let prev_state = states.insert(addr, DeviceConnectionStates::Disconnecting).unwrap();
        if prev_state != DeviceConnectionStates::Disconnecting {
            let mut guard = self.fallback_tasks.lock().unwrap();
            if let Some(task) = guard.get(&addr) {
                match task {
                    // Abort pending task if there is any.
                    Some((handler, _ts)) => {
                        warn!(
                            "[{}]: Device disconnected a critical profile before it was added.",
                            DisplayAddress(&addr)
                        );
                        handler.abort();
                        guard.insert(addr, None);
                    }
                    // Notify device removal if it has been added.
                    None => {
                        info!(
                            "[{}]: Device disconnected a critical profile, removing the device.",
                            DisplayAddress(&addr)
                        );
                        self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                            callback.on_bluetooth_audio_device_removed(addr);
                        });
                    }
                };
            }
            self.delay_volume_update.clear();
        }
    }

    async fn wait_retry(
        _fallback_tasks: &Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
        device_states: &Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
        txl: &Sender<Message>,
        addr: &RawAddress,
        first_conn_ts: Instant,
    ) {
        let now_ts = Instant::now();
        let total_duration = Duration::from_secs(CONNECT_MISSING_PROFILES_TIMEOUT_SEC);
        let sleep_duration = (first_conn_ts + total_duration).saturating_duration_since(now_ts);
        sleep(sleep_duration).await;

        device_states.lock().unwrap().insert(*addr, DeviceConnectionStates::ConnectingAfterRetry);

        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(addr),
            DeviceConnectionStates::ConnectingAfterRetry
        );

        let _ = txl.send(Message::Media(MediaActions::Connect(*addr))).await;
    }

    async fn wait_disconnect(
        fallback_tasks: &Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
        device_states: &Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
        txl: &Sender<Message>,
        addr: &RawAddress,
        first_conn_ts: Instant,
    ) {
        let now_ts = Instant::now();
        let total_duration = Duration::from_secs(PROFILE_DISCOVERY_TIMEOUT_SEC);
        let sleep_duration = (first_conn_ts + total_duration).saturating_duration_since(now_ts);
        sleep(sleep_duration).await;

        Self::async_disconnect(fallback_tasks, device_states, txl, addr).await;
    }

    async fn async_disconnect(
        fallback_tasks: &Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
        device_states: &Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
        txl: &Sender<Message>,
        addr: &RawAddress,
    ) {
        device_states.lock().unwrap().insert(*addr, DeviceConnectionStates::Disconnecting);
        fallback_tasks.lock().unwrap().insert(*addr, None);

        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(addr),
            DeviceConnectionStates::Disconnecting
        );

        let _ = txl.send(Message::Media(MediaActions::Disconnect(*addr))).await;
    }

    async fn wait_force_enter_connected(
        txl: &Sender<Message>,
        addr: &RawAddress,
        first_conn_ts: Instant,
    ) {
        let now_ts = Instant::now();
        let total_duration = Duration::from_secs(PROFILE_DISCOVERY_TIMEOUT_SEC);
        let sleep_duration = (first_conn_ts + total_duration).saturating_duration_since(now_ts);
        sleep(sleep_duration).await;
        let _ = txl.send(Message::Media(MediaActions::ForceEnterConnected(*addr))).await;
    }

    fn is_bonded(&self, addr: &RawAddress) -> bool {
        BtBondState::Bonded == self.adapter.lock().unwrap().get_bond_state_by_addr(addr)
    }

    fn notify_media_capability_updated(&mut self, addr: RawAddress) {
        let mut guard = self.fallback_tasks.lock().unwrap();
        let mut states = self.device_states.lock().unwrap();
        let mut first_conn_ts = Instant::now();

        let is_profile_cleared = !self.connected_profiles.contains_key(&addr);

        if let Some(task) = guard.get(&addr) {
            if let Some((handler, ts)) = task {
                // Abort the pending task. It may be updated or
                // removed depending on whether all profiles are cleared.
                handler.abort();
                first_conn_ts = *ts;
                guard.insert(addr, None);
            } else {
                // The device is already added or is disconnecting.
                // Ignore unless all profiles are cleared, where we need to do some clean up.
                if !is_profile_cleared {
                    // Unbonded device is special, we need to reject the connection from them.
                    // However, it's rather tricky to distinguish between these two cases:
                    // (1) the unbonded device tries to reconnect some of the profiles.
                    // (2) we just unbond a device, so now the profiles are disconnected one-by-one.
                    // In case of (2), we should not send async_disconnect too soon because doing so
                    // might prevent on_bluetooth_audio_device_removed() from firing, since the conn
                    // state is already "Disconnecting" in notify_critical_profile_disconnected.
                    // Therefore to prevent it, we also check the state is still FullyConnected.
                    if !self.is_bonded(&addr)
                        && states.get(&addr).unwrap() != &DeviceConnectionStates::FullyConnected
                    {
                        let tasks = self.fallback_tasks.clone();
                        let states = self.device_states.clone();
                        let txl = self.tx.clone();
                        let task = topstack::get_runtime().spawn(async move {
                            warn!(
                                "[{}]: Rejecting an unbonded device's attempt to connect media",
                                DisplayAddress(&addr)
                            );
                            BluetoothMedia::async_disconnect(&tasks, &states, &txl, &addr).await;
                        });
                        guard.insert(addr, Some((task, first_conn_ts)));
                    }
                    return;
                }
            }
        }

        // Cleanup if transitioning to empty set.
        if is_profile_cleared {
            info!("[{}]: Device connection state: Disconnected.", DisplayAddress(&addr));
            states.remove(&addr);
            guard.remove(&addr);
            let tx = self.tx.clone();
            tokio::spawn(async move {
                let _ = tx.send(Message::ProfileDisconnected(addr)).await;
            });
            return;
        }

        let available_profiles = self.adapter_get_classic_audio_profiles(addr);
        let connected_profiles = self.connected_profiles.get(&addr).unwrap();
        let missing_profiles =
            available_profiles.difference(connected_profiles).cloned().collect::<HashSet<_>>();

        // Update device states
        if states.get(&addr).is_none() {
            states.insert(addr, DeviceConnectionStates::ConnectingBeforeRetry);
        }

        if states.get(&addr).unwrap() != &DeviceConnectionStates::FullyConnected {
            if available_profiles.is_empty() {
                // Some headsets may start initiating connections to audio profiles before they are
                // exposed to the stack. In this case, wait for either all critical profiles have been
                // connected or some timeout to enter the |FullyConnected| state.
                if connected_profiles.contains(&Profile::Hfp)
                    && connected_profiles.contains(&Profile::A2dpSink)
                {
                    info!(
                        "[{}]: Fully connected, available profiles: {:?}, connected profiles: {:?}.",
                        DisplayAddress(&addr),
                        available_profiles,
                        connected_profiles
                    );

                    states.insert(addr, DeviceConnectionStates::FullyConnected);
                } else {
                    warn!(
                        "[{}]: Connected profiles: {:?}, waiting for peer to initiate remaining connections.",
                        DisplayAddress(&addr),
                        connected_profiles
                    );

                    states.insert(addr, DeviceConnectionStates::WaitingConnection);
                }
            } else if missing_profiles.is_empty()
                || missing_profiles == HashSet::from([Profile::AvrcpController])
            {
                info!(
                    "[{}]: Fully connected, available profiles: {:?}, connected profiles: {:?}.",
                    DisplayAddress(&addr),
                    available_profiles,
                    connected_profiles
                );

                states.insert(addr, DeviceConnectionStates::FullyConnected);
            }
        }

        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(&addr),
            states.get(&addr).unwrap()
        );

        // React on updated device states
        let tasks = self.fallback_tasks.clone();
        let device_states = self.device_states.clone();
        let txl = self.tx.clone();
        let ts = first_conn_ts;
        let is_complete_profiles_required = self.is_complete_profiles_required();
        match states.get(&addr).unwrap() {
            DeviceConnectionStates::Initiating => {
                let task = topstack::get_runtime().spawn(async move {
                    // As initiator we can just immediately start connecting
                    let _ = txl.send(Message::Media(MediaActions::Connect(addr))).await;
                    if !is_complete_profiles_required {
                        BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                        return;
                    }
                    BluetoothMedia::wait_retry(&tasks, &device_states, &txl, &addr, ts).await;
                    BluetoothMedia::wait_disconnect(&tasks, &device_states, &txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
            DeviceConnectionStates::ConnectingBeforeRetry => {
                let task = topstack::get_runtime().spawn(async move {
                    if !is_complete_profiles_required {
                        BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                        return;
                    }
                    BluetoothMedia::wait_retry(&tasks, &device_states, &txl, &addr, ts).await;
                    BluetoothMedia::wait_disconnect(&tasks, &device_states, &txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
            DeviceConnectionStates::ConnectingAfterRetry => {
                let task = topstack::get_runtime().spawn(async move {
                    if !is_complete_profiles_required {
                        BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                        return;
                    }
                    BluetoothMedia::wait_disconnect(&tasks, &device_states, &txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
            DeviceConnectionStates::FullyConnected => {
                // Rejecting the unbonded connection after we finished our profile
                // reconnecting logic to avoid a collision.
                if !self.is_bonded(&addr) {
                    warn!(
                        "[{}]: Rejecting a unbonded device's attempt to connect to media profiles",
                        DisplayAddress(&addr)
                    );

                    let task = topstack::get_runtime().spawn(async move {
                        BluetoothMedia::async_disconnect(&tasks, &device_states, &txl, &addr).await;
                    });
                    guard.insert(addr, Some((task, ts)));
                    return;
                }

                let cur_a2dp_caps = self.a2dp_caps.get(&addr);
                let cur_hfp_cap = self.hfp_cap.get(&addr);
                let name = self.adapter_get_remote_name(addr);
                let absolute_volume = self.absolute_volume;
                let device = BluetoothAudioDevice::new(
                    addr,
                    name.clone(),
                    cur_a2dp_caps.unwrap_or(&Vec::new()).to_vec(),
                    *cur_hfp_cap.unwrap_or(&HfpCodecFormat::NONE),
                    absolute_volume,
                );

                let hfp_volume = self.delay_volume_update.remove(&Profile::Hfp);
                let avrcp_volume = self.delay_volume_update.remove(&Profile::AvrcpController);

                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_bluetooth_audio_device_added(device.clone());
                    if let Some(volume) = hfp_volume {
                        info!("Trigger HFP volume update to {}", DisplayAddress(&addr));
                        callback.on_hfp_volume_changed(volume, addr);
                    }

                    if let Some(volume) = avrcp_volume {
                        info!("Trigger avrcp volume update");
                        callback.on_absolute_volume_changed(volume);
                    }
                });

                guard.insert(addr, None);
            }
            DeviceConnectionStates::Disconnecting => {}
            DeviceConnectionStates::WaitingConnection => {
                let task = topstack::get_runtime().spawn(async move {
                    BluetoothMedia::wait_retry(&tasks, &device_states, &txl, &addr, ts).await;
                    BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
        }
    }

    fn adapter_get_remote_name(&self, addr: RawAddress) -> String {
        let device = BluetoothDevice::new(
            addr,
            // get_remote_name needs a BluetoothDevice just for its address, the
            // name field is unused so construct one with a fake name.
            "Classic Device".to_string(),
        );
        match self.adapter.lock().unwrap().get_remote_name(device).as_str() {
            "" => addr.to_string(),
            name => name.into(),
        }
    }

    fn adapter_get_le_audio_profiles(&self, addr: RawAddress) -> HashSet<Profile> {
        let device = BluetoothDevice::new(addr, "".to_string());
        self.adapter
            .lock()
            .unwrap()
            .get_remote_uuids(device)
            .into_iter()
            .filter_map(|u| UuidHelper::is_known_profile(&u))
            .filter(|u| MEDIA_LE_AUDIO_PROFILES.contains(u))
            .collect()
    }

    fn adapter_get_classic_audio_profiles(&self, addr: RawAddress) -> HashSet<Profile> {
        let name = self.adapter_get_remote_name(addr);
        let device = BluetoothDevice::new(addr, "".to_string());
        let mut profiles: HashSet<_> = self
            .adapter
            .lock()
            .unwrap()
            .get_remote_uuids(device)
            .into_iter()
            .filter_map(|u| UuidHelper::is_known_profile(&u))
            .filter(|u| MEDIA_CLASSIC_AUDIO_PROFILES.contains(u))
            .collect();

        if interop_disable_hf_profile(name) {
            profiles.remove(&Profile::Hfp);
        }

        profiles
    }

    pub fn get_hfp_connection_state(&self) -> ProfileConnectionState {
        if self.hfp_audio_state.values().any(|state| *state == BthfAudioState::Connected) {
            ProfileConnectionState::Active
        } else {
            let mut winning_state = ProfileConnectionState::Disconnected;
            for state in self.hfp_states.values() {
                // Grab any state higher than the current state.
                match state {
                    // Any SLC completed state means the profile is connected.
                    BthfConnectionState::SlcConnected => {
                        winning_state = ProfileConnectionState::Connected;
                    }

                    // Connecting or Connected are both counted as connecting for profile state
                    // since it's not a complete connection.
                    BthfConnectionState::Connecting | BthfConnectionState::Connected
                        if winning_state != ProfileConnectionState::Connected =>
                    {
                        winning_state = ProfileConnectionState::Connecting;
                    }

                    BthfConnectionState::Disconnecting
                        if winning_state == ProfileConnectionState::Disconnected =>
                    {
                        winning_state = ProfileConnectionState::Disconnecting;
                    }

                    _ => (),
                }
            }

            winning_state
        }
    }

    pub fn get_a2dp_connection_state(&self) -> ProfileConnectionState {
        if self.a2dp_audio_state.values().any(|state| *state == BtavAudioState::Started) {
            ProfileConnectionState::Active
        } else {
            let mut winning_state = ProfileConnectionState::Disconnected;
            for state in self.a2dp_states.values() {
                // Grab any state higher than the current state.
                match state {
                    BtavConnectionState::Connected => {
                        winning_state = ProfileConnectionState::Connected;
                    }

                    BtavConnectionState::Connecting
                        if winning_state != ProfileConnectionState::Connected =>
                    {
                        winning_state = ProfileConnectionState::Connecting;
                    }

                    BtavConnectionState::Disconnecting
                        if winning_state == ProfileConnectionState::Disconnected =>
                    {
                        winning_state = ProfileConnectionState::Disconnecting;
                    }

                    _ => (),
                }
            }

            winning_state
        }
    }

    pub fn filter_to_connected_audio_devices_from(
        &self,
        devices: &Vec<BluetoothDevice>,
    ) -> Vec<BluetoothDevice> {
        devices
            .iter()
            .filter(|d| {
                self.is_any_profile_connected(&d.address, MEDIA_CLASSIC_AUDIO_PROFILES)
                    || self.is_any_profile_connected(&d.address, MEDIA_LE_AUDIO_PROFILES)
            })
            .cloned()
            .collect()
    }

    fn start_audio_request_impl(&mut self) -> bool {
        debug!("Start audio request");
        self.a2dp.start_audio_request()
    }

    fn suspend_audio_request_impl(&mut self) {
        self.a2dp.suspend_audio_request();
    }

    fn try_a2dp_resume(&mut self) {
        // Try resume the A2DP stream (per MPS v1.0) on rejecting an incoming call or an
        // outgoing call is rejected.
        // It may fail if a SCO connection is still active (terminate call case), in that
        // case we will retry on SCO disconnected.
        if !self.mps_qualification_enabled {
            return;
        }
        // Make sure there is no any SCO connection and then resume the A2DP stream.
        if self.a2dp_has_interrupted_stream
            && !self.hfp_audio_state.values().any(|state| *state == BthfAudioState::Connected)
        {
            self.a2dp_has_interrupted_stream = false;
            self.start_audio_request_impl();
        }
    }

    fn try_a2dp_suspend(&mut self) {
        // Try suspend the A2DP stream (per MPS v1.0) when receiving an incoming call
        if !self.mps_qualification_enabled {
            return;
        }
        // Suspend the A2DP stream if there is any.
        if self.a2dp_audio_state.values().any(|state| *state == BtavAudioState::Started) {
            self.a2dp_has_interrupted_stream = true;
            self.suspend_audio_request_impl();
        }
    }

    fn start_sco_call_impl(
        &mut self,
        addr: RawAddress,
        sco_offload: bool,
        disabled_codecs: HfpCodecBitId,
    ) -> bool {
        info!("Start sco call for {}", DisplayAddress(&addr));

        let Ok(disabled_codecs) = disabled_codecs.try_into() else {
            warn!("Can't parse disabled_codecs");
            return false;
        };
        if self.hfp.connect_audio(addr, sco_offload, disabled_codecs) != 0 {
            warn!("SCO connect_audio status failed");
            return false;
        }
        info!("SCO connect_audio status success");
        true
    }

    fn stop_sco_call_impl(&mut self, addr: RawAddress) {
        info!("Stop sco call for {}", DisplayAddress(&addr));
        self.hfp.disconnect_audio(addr);
    }

    fn device_status_notification(&mut self) {
        for (addr, state) in self.hfp_states.iter() {
            if *state != BthfConnectionState::SlcConnected {
                continue;
            }
            debug!(
                "[{}]: Device status notification {:?}",
                DisplayAddress(addr),
                self.telephony_device_status
            );
            let status = self.hfp.device_status_notification(self.telephony_device_status, *addr);
            if status != BtStatus::Success {
                warn!(
                    "[{}]: Device status notification failed, status={:?}",
                    DisplayAddress(addr),
                    status
                );
            }
        }
    }

    fn phone_state_change(&mut self, number: String) {
        for (addr, state) in self.hfp_states.iter() {
            if *state != BthfConnectionState::SlcConnected {
                continue;
            }
            debug!(
                "[{}]: Phone state change state={:?} number={}",
                DisplayAddress(addr),
                self.phone_state,
                number
            );
            let status = self.hfp.phone_state_change(self.phone_state, &number, *addr);
            if status != BtStatus::Success {
                warn!(
                    "[{}]: Device status notification failed, status={:?}",
                    DisplayAddress(addr),
                    status
                );
            }
        }
    }

    // Returns the minimum unoccupied index starting from 1.
    fn new_call_index(&self) -> i32 {
        (1..)
            .find(|&index| self.call_list.iter().all(|x| x.index != index))
            .expect("There must be an unoccupied index")
    }

    fn simple_at_response(&mut self, ok: bool, addr: RawAddress) {
        let status = self.hfp.simple_at_response(ok, addr);
        if status != BtStatus::Success {
            warn!("[{}]: AT response failed, status={:?}", DisplayAddress(&addr), status);
        }
    }

    fn incoming_call_impl(&mut self, number: String) -> bool {
        if self.phone_state.state != CallState::Idle {
            return false;
        }

        if self.phone_state.num_active > 0 {
            return false;
        }

        self.call_list.push(CallInfo {
            index: self.new_call_index(),
            dir_incoming: true,
            state: CallState::Incoming,
            number: number.clone(),
        });
        self.phone_state.state = CallState::Incoming;
        self.phone_state_change(number);
        self.try_a2dp_suspend();
        true
    }

    fn answer_call_impl(&mut self) -> bool {
        if self.phone_state.state == CallState::Idle {
            return false;
        }
        // There must be exactly one incoming/dialing call in the list.
        for c in self.call_list.iter_mut() {
            match c.state {
                CallState::Incoming | CallState::Dialing | CallState::Alerting => {
                    c.state = CallState::Active;
                    break;
                }
                _ => {}
            }
        }
        self.phone_state.state = CallState::Idle;
        self.phone_state.num_active += 1;

        self.phone_state_change("".into());

        if self.mps_qualification_enabled {
            // Find a connected HFP and try to establish an SCO.
            if let Some(addr) = self.hfp_states.iter().find_map(|(addr, state)| {
                if *state == BthfConnectionState::SlcConnected {
                    Some(addr)
                } else {
                    None
                }
            }) {
                info!("Start SCO call due to call answered");
                self.start_sco_call_impl(*addr, false, HfpCodecBitId::NONE);
            }
        }

        true
    }

    fn hangup_call_impl(&mut self) -> bool {
        if !self.phone_ops_enabled && !self.mps_qualification_enabled {
            return false;
        }

        match self.phone_state.state {
            CallState::Idle if self.phone_state.num_active > 0 => {
                self.phone_state.num_active -= 1;
            }
            CallState::Incoming | CallState::Dialing | CallState::Alerting => {
                self.phone_state.state = CallState::Idle;
            }
            _ => return false,
        }
        // At this point, there must be exactly one incoming/dialing/alerting/active call to be
        // removed.
        self.call_list.retain(|x| match x.state {
            CallState::Active | CallState::Incoming | CallState::Dialing | CallState::Alerting => {
                false
            }
            _ => true,
        });

        self.phone_state_change("".into());
        self.try_a2dp_resume();

        true
    }

    fn dialing_call_impl(&mut self, number: String, addr: Option<RawAddress>) -> bool {
        if self.phone_state.num_active > 0 || self.phone_state.state != CallState::Idle {
            if let Some(addr) = addr {
                self.simple_at_response(false, addr);
                warn!("[{}]: Unexpected dialing command from HF", DisplayAddress(&addr));
            }
            return false;
        }

        self.call_list.push(CallInfo {
            index: self.new_call_index(),
            dir_incoming: false,
            state: CallState::Dialing,
            number: number.clone(),
        });
        self.phone_state.state = CallState::Dialing;

        if let Some(addr) = addr {
            self.simple_at_response(true, addr);
            warn!("[{}]: Unexpected dialing command from HF", DisplayAddress(&addr));
        }

        // Inform libbluetooth that the state has changed to dialing.
        self.phone_state_change("".into());
        self.try_a2dp_suspend();
        // Change to alerting state and inform libbluetooth.
        self.dialing_to_alerting();
        true
    }

    fn dialing_to_alerting(&mut self) -> bool {
        if !(self.phone_ops_enabled || self.mps_qualification_enabled)
            || self.phone_state.state != CallState::Dialing
        {
            return false;
        }
        for c in self.call_list.iter_mut() {
            if c.state == CallState::Dialing {
                c.state = CallState::Alerting;
                break;
            }
        }
        self.phone_state.state = CallState::Alerting;
        self.phone_state_change("".into());
        true
    }

    fn release_held_impl(&mut self, addr: Option<RawAddress>) -> bool {
        if self.phone_state.state != CallState::Idle {
            if let Some(addr) = addr {
                // Respond ERROR to the HF which sent the command.
                self.simple_at_response(false, addr);
            }
            return false;
        }
        self.call_list.retain(|x| x.state != CallState::Held);
        self.phone_state.num_held = 0;

        if let Some(addr) = addr {
            // This should be called before calling phone_state_change.
            self.simple_at_response(true, addr);
        }
        // Success means the call state has changed. Inform libbluetooth.
        self.phone_state_change("".into());
        true
    }

    fn release_active_accept_held_impl(&mut self, addr: Option<RawAddress>) -> bool {
        self.call_list.retain(|x| x.state != CallState::Active);
        self.phone_state.num_active = 0;
        // Activate the first held call
        if self.phone_state.state != CallState::Idle {
            if let Some(addr) = addr {
                // Respond ERROR to the HF which sent the command.
                self.simple_at_response(false, addr);
            }
            return false;
        }
        for c in self.call_list.iter_mut() {
            if c.state == CallState::Held {
                c.state = CallState::Active;
                self.phone_state.num_held -= 1;
                self.phone_state.num_active += 1;
                break;
            }
        }
        if let Some(addr) = addr {
            // This should be called before calling phone_state_change.
            self.simple_at_response(true, addr);
        }
        // Success means the call state has changed. Inform libbluetooth.
        self.phone_state_change("".into());
        true
    }

    fn hold_active_accept_held_impl(&mut self, addr: Option<RawAddress>) -> bool {
        if self.phone_state.state != CallState::Idle {
            if let Some(addr) = addr {
                // Respond ERROR to the HF which sent the command.
                self.simple_at_response(false, addr);
            }
            return false;
        }
        self.phone_state.num_held += self.phone_state.num_active;
        self.phone_state.num_active = 0;

        for c in self.call_list.iter_mut() {
            match c.state {
                // Activate at most one held call
                CallState::Held if self.phone_state.num_active == 0 => {
                    c.state = CallState::Active;
                    self.phone_state.num_held -= 1;
                    self.phone_state.num_active = 1;
                }
                CallState::Active => {
                    c.state = CallState::Held;
                }
                _ => {}
            }
        }
        if let Some(addr) = addr {
            // This should be called before calling phone_state_change.
            self.simple_at_response(true, addr);
        }
        // Success means the call state has changed. Inform libbluetooth.
        self.phone_state_change("".into());
        true
    }

    // Per MPS v1.0 (Multi-Profile Specification), disconnecting or failing to connect
    // a profile should not affect the others.
    // Allow partial profiles connection during qualification (MPS qualification mode is enabled).
    fn is_complete_profiles_required(&self) -> bool {
        !self.mps_qualification_enabled
    }

    // Force the media enters the FullyConnected state and then triggers a retry.
    // When this function is used for qualification as a replacement of normal retry,
    // PTS could initiate the connection of the necessary profiles, and Floss should
    // notify CRAS of the new audio device regardless of the unconnected profiles.
    // Still retry in the end because some test cases require that.
    fn force_enter_connected(&mut self, addr: RawAddress) {
        self.device_states.lock().unwrap().insert(addr, DeviceConnectionStates::FullyConnected);
        self.notify_media_capability_updated(addr);
        self.connect(addr);
    }
    pub fn add_player(&mut self, name: String, browsing_supported: bool) {
        self.avrcp.add_player(&name, browsing_supported);
    }

    // This function determines if it's safe to send a +CIEV command to an HFP device when SCO starts.

    // The +CIEV command should NOT be sent if:
    //  - MPS qualification mode is enabled, as it may cause qualification failures.
    //  - Uhid device is open, as it may conflict with ongoing telephony operations.

    // The +CIEV command is safe to send if:
    //  - Both MPS qualification and Bluetooth telephony are disabled.
    //  - Uhid device is closed, preventing any telephony conflicts.
    //  - The headset is listed in interop_database.conf, indicating it requires +CIEV for audio.
    fn should_insert_call_when_sco_start(&self, address: RawAddress) -> bool {
        if self.mps_qualification_enabled {
            return false;
        }
        if !self.phone_ops_enabled {
            return true;
        }

        match self.uhid.get(&address) {
            Some(uhid) => {
                if !uhid.is_open {
                    return true;
                }
            }
            None => {
                return true;
            }
        };

        interop_insert_call_when_sco_start(address)
    }
    // Places an active call into the call list and triggers a headset update (+CIEV).
    // Preconditions:
    //   - No active calls in progress (phone_state.num_active == 0)
    //   - Phone state is idle (phone_state.state == CallState::Idle)
    fn place_active_call(&mut self) {
        if self.phone_state.num_active != 0 {
            warn!("Unexpected usage. phone_state.num_active can only be 0 when calling place_active_call");
            return;
        }

        if self.phone_state.state != CallState::Idle {
            warn!("Unexpected usage. phone_state.state can only be idle when calling place_active_call");
            return;
        }

        self.dialing_call_impl("".into(), None);
        self.answer_call_impl();
    }

    pub fn get_group_devices(&self, group_id: i32) -> HashSet<RawAddress> {
        match self.le_audio_groups.get(&group_id) {
            Some(g) => g.devices.clone(),
            _ => HashSet::new(),
        }
    }

    pub fn get_group_id(&self, addr: RawAddress) -> i32 {
        *self.le_audio_node_to_group.get(&addr).unwrap_or(&LEA_UNKNOWN_GROUP_ID)
    }
}

fn get_a2dp_dispatcher(tx: Sender<Message>) -> A2dpCallbacksDispatcher {
    A2dpCallbacksDispatcher { dispatch: make_message_dispatcher(tx, Message::A2dp) }
}

fn get_avrcp_dispatcher(tx: Sender<Message>) -> AvrcpCallbacksDispatcher {
    AvrcpCallbacksDispatcher { dispatch: make_message_dispatcher(tx, Message::Avrcp) }
}

fn get_hfp_dispatcher(tx: Sender<Message>) -> HfpCallbacksDispatcher {
    HfpCallbacksDispatcher { dispatch: make_message_dispatcher(tx, Message::Hfp) }
}

fn get_le_audio_dispatcher(tx: Sender<Message>) -> LeAudioClientCallbacksDispatcher {
    LeAudioClientCallbacksDispatcher {
        dispatch: make_message_dispatcher(tx, Message::LeAudioClient),
    }
}

fn get_vc_dispatcher(tx: Sender<Message>) -> VolumeControlCallbacksDispatcher {
    VolumeControlCallbacksDispatcher {
        dispatch: make_message_dispatcher(tx, Message::VolumeControl),
    }
}

fn get_csis_dispatcher(tx: Sender<Message>) -> CsisClientCallbacksDispatcher {
    CsisClientCallbacksDispatcher { dispatch: make_message_dispatcher(tx, Message::CsisClient) }
}

impl IBluetoothMedia for BluetoothMedia {
    fn register_callback(&mut self, callback: Box<dyn IBluetoothMediaCallback + Send>) -> bool {
        let _id = self.callbacks.lock().unwrap().add_callback(callback);
        true
    }

    fn initialize(&mut self) -> bool {
        if self.initialized {
            return false;
        }
        self.initialized = true;

        self.is_le_audio_only_enabled =
            features::is_feature_enabled("CrOSLateBootBluetoothAudioLEAudioOnly").unwrap_or(false);

        // A2DP
        let a2dp_dispatcher = get_a2dp_dispatcher(self.tx.clone());
        self.a2dp.initialize(a2dp_dispatcher);

        // AVRCP
        let avrcp_dispatcher = get_avrcp_dispatcher(self.tx.clone());
        self.avrcp.initialize(avrcp_dispatcher);

        // HFP
        let hfp_dispatcher = get_hfp_dispatcher(self.tx.clone());
        self.hfp.initialize(hfp_dispatcher);

        // LEA
        let le_audio_dispatcher = get_le_audio_dispatcher(self.tx.clone());
        self.le_audio.initialize(le_audio_dispatcher);

        // VC
        let vc_dispatcher = get_vc_dispatcher(self.tx.clone());
        self.vc.initialize(vc_dispatcher);

        // CSIS
        let csis_dispatcher = get_csis_dispatcher(self.tx.clone());
        self.csis.initialize(csis_dispatcher);

        // TODO(b/284811956) A2DP needs to be enabled before AVRCP otherwise AVRCP gets memset'd.
        // Iterate the delay_enable_profiles hashmap directly when this is fixed.
        for profile in MEDIA_PROFILE_ENABLE_ORDER {
            if self.delay_enable_profiles.contains(&profile) {
                self.enable_profile(&profile);
            }
        }
        let api_tx = self.api_tx.clone();
        tokio::spawn(async move {
            // TODO(b:300202052) make sure media interface is exposed after initialized
            let _ = api_tx.send(APIMessage::IsReady(BluetoothAPI::Media)).await;
        });
        true
    }

    fn connect_lea_group_by_member_address(&mut self, addr: RawAddress) {
        // Note that at this point the scanning of profiles may be incomplete,
        // TODO(b/335780769): connect to available profiles and ensure
        // this function is invoked whenever there is an incremental
        // discovery of LE audio profiles.
        for profile in MEDIA_LE_AUDIO_PROFILES {
            match profile {
                Profile::LeAudio => {
                    self.connect_lea(addr);
                }
                Profile::VolumeControl => {
                    self.connect_vc(addr);
                }
                Profile::CoordinatedSet => {
                    self.connect_csis(addr);
                }
                _ => {}
            }
        }
    }

    fn disconnect_lea_group_by_member_address(&mut self, addr: RawAddress) {
        let group_id = self.get_group_id(addr);
        if group_id == LEA_UNKNOWN_GROUP_ID {
            warn!(
                "disconnect_lea_group_by_member_address: [{}]: address belongs to no group",
                DisplayAddress(&addr)
            );
            return;
        }

        let group = self.le_audio_groups.entry(group_id).or_default().clone();

        let available_profiles = self.adapter_get_le_audio_profiles(addr);

        info!(
            "disconnect_lea_group_by_member_address: [{}]: available profiles: {:?}.",
            DisplayAddress(&addr),
            available_profiles
        );

        for &member_addr in group.devices.iter() {
            for profile in self.adapter_get_le_audio_profiles(addr) {
                match profile {
                    Profile::LeAudio => {
                        self.disconnect_lea(member_addr);
                    }
                    Profile::VolumeControl => {
                        self.disconnect_vc(member_addr);
                    }
                    Profile::CoordinatedSet => {
                        self.disconnect_csis(member_addr);
                    }
                    _ => {}
                }
            }
        }
    }

    fn connect_lea(&mut self, addr: RawAddress) {
        if !self.is_le_audio_only_enabled {
            warn!("connect_lea: LeAudioEnableLeAudioOnly is not set");
            return;
        }

        if *self.le_audio_states.get(&addr).unwrap_or(&BtLeAudioConnectionState::Disconnected)
            == BtLeAudioConnectionState::Connected
        {
            info!("connect_lea: already connected.");
            return;
        }

        let available_profiles = self.adapter_get_le_audio_profiles(addr);

        info!(
            "connect_lea: [{}]: connecting, available profiles: {:?}.",
            DisplayAddress(&addr),
            available_profiles
        );

        self.le_audio.set_enable_state(addr, true);
        self.le_audio.connect(addr);
    }

    fn disconnect_lea(&mut self, addr: RawAddress) {
        if *self.le_audio_states.get(&addr).unwrap_or(&BtLeAudioConnectionState::Disconnected)
            == BtLeAudioConnectionState::Disconnected
        {
            info!("disconnect_lea: [{}]: already disconnected", DisplayAddress(&addr));
            return;
        }

        info!("disconnect_lea: [{}]: disconnecting", DisplayAddress(&addr));

        self.le_audio.set_enable_state(addr, false);
        self.le_audio.disconnect(addr);
    }

    fn connect_vc(&mut self, addr: RawAddress) {
        if !self.is_le_audio_only_enabled {
            warn!("connect_vc: LeAudioEnableLeAudioOnly is not set");
            return;
        }

        if *self.vc_states.get(&addr).unwrap_or(&BtVcConnectionState::Disconnected)
            == BtVcConnectionState::Connected
        {
            info!("connect_vc: already connected");
            return;
        }

        let available_profiles = self.adapter_get_le_audio_profiles(addr);

        info!(
            "connect_vc: [{}]: connecting, available profiles: {:?}.",
            DisplayAddress(&addr),
            available_profiles
        );

        self.vc.connect(addr);
    }

    fn disconnect_vc(&mut self, addr: RawAddress) {
        if *self.vc_states.get(&addr).unwrap_or(&BtVcConnectionState::Disconnected)
            == BtVcConnectionState::Disconnected
        {
            info!("disconnect_vc: already disconnected");
            return;
        }

        info!("disconnect_vc: [{}]: disconnecting", DisplayAddress(&addr));

        self.vc.disconnect(addr);
    }

    fn connect_csis(&mut self, addr: RawAddress) {
        if !self.is_le_audio_only_enabled {
            warn!("connect_csis: LeAudioEnableLeAudioOnly is not set");
            return;
        }

        if *self.csis_states.get(&addr).unwrap_or(&BtCsisConnectionState::Disconnected)
            == BtCsisConnectionState::Connected
        {
            info!("connect_csis: already connected");
            return;
        }

        let available_profiles = self.adapter_get_le_audio_profiles(addr);

        info!(
            "connect_csis: [{}]: connecting, available profiles: {:?}.",
            DisplayAddress(&addr),
            available_profiles
        );

        self.csis.connect(addr);
    }

    fn disconnect_csis(&mut self, addr: RawAddress) {
        if *self.csis_states.get(&addr).unwrap_or(&BtCsisConnectionState::Disconnected)
            == BtCsisConnectionState::Disconnected
        {
            info!("disconnect_csis: already disconnected");
            return;
        }

        info!("disconnect_csis: [{}]: disconnecting", DisplayAddress(&addr));

        self.csis.disconnect(addr);
    }

    fn connect(&mut self, addr: RawAddress) {
        if self.is_le_audio_only_enabled {
            warn!("connect: LeAudioEnableLeAudioOnly is set");
            return;
        }

        let available_profiles = self.adapter_get_classic_audio_profiles(addr);

        info!(
            "[{}]: Connecting to device, available profiles: {:?}.",
            DisplayAddress(&addr),
            available_profiles
        );

        let connected_profiles = self.connected_profiles.get(&addr).cloned().unwrap_or_default();

        // Sort here so the order of connection is always consistent
        let missing_profiles =
            available_profiles.difference(&connected_profiles).sorted().collect::<Vec<_>>();

        // Connect the profiles one-by-one so it won't stuck at the lower layer.
        // Therefore, just connect to one profile for now.
        // connect() will be called again after the first profile is successfully connected.
        let mut is_connect = false;
        for profile in missing_profiles {
            match profile {
                Profile::A2dpSink => {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::A2dpSink as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                    let status = self.a2dp.connect(addr);
                    if BtStatus::Success != status {
                        metrics::profile_connection_state_changed(
                            addr,
                            Profile::A2dpSink as u32,
                            status,
                            BtavConnectionState::Disconnected as u32,
                        );
                    } else {
                        is_connect = true;
                        break;
                    }
                }
                Profile::Hfp => {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::Hfp as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                    let status = self.hfp.connect(addr);
                    if BtStatus::Success != status {
                        metrics::profile_connection_state_changed(
                            addr,
                            Profile::Hfp as u32,
                            status,
                            BthfConnectionState::Disconnected as u32,
                        );
                    } else {
                        is_connect = true;
                        break;
                    }
                }
                Profile::AvrcpController => {
                    // Fluoride will resolve AVRCP as a part of A2DP connection request.
                    // Explicitly connect to it only when it is considered missing, and don't
                    // bother about it when A2DP is not connected.
                    if !connected_profiles.contains(&Profile::A2dpSink) {
                        continue;
                    }

                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                    self.avrcp_states.insert(addr, BtavConnectionState::Connecting);
                    let status = self.avrcp.connect(addr);
                    if BtStatus::Success != status {
                        // Reset direction to unknown.
                        self.avrcp_states.remove(&addr);
                        metrics::profile_connection_state_changed(
                            addr,
                            Profile::AvrcpController as u32,
                            status,
                            BtavConnectionState::Disconnected as u32,
                        );
                    } else {
                        is_connect = true;
                        break;
                    }
                }
                _ => warn!("Unknown profile: {:?}", profile),
            }
        }

        if is_connect {
            let mut tasks = self.fallback_tasks.lock().unwrap();
            let mut states = self.device_states.lock().unwrap();
            if let std::collections::hash_map::Entry::Vacant(e) = tasks.entry(addr) {
                states.insert(addr, DeviceConnectionStates::Initiating);

                let fallback_tasks = self.fallback_tasks.clone();
                let device_states = self.device_states.clone();
                let now_ts = Instant::now();
                let task = topstack::get_runtime().spawn(async move {
                    sleep(Duration::from_secs(CONNECT_AS_INITIATOR_TIMEOUT_SEC)).await;

                    // If here the task is not yet aborted, probably connection is failed,
                    // therefore here we release the states. Even if later the connection is
                    // actually successful, we will just treat this as if the connection is
                    // initiated by the peer and will reconnect the missing profiles after
                    // some time, so it's safe.
                    {
                        device_states.lock().unwrap().remove(&addr);
                        fallback_tasks.lock().unwrap().remove(&addr);
                    }
                });
                e.insert(Some((task, now_ts)));
            }
        }
    }

    fn is_initialized(&self) -> bool {
        self.initialized
    }

    fn cleanup(&mut self) -> bool {
        self.cleanup()
    }

    // This may not disconnect all media profiles at once, but once the stack
    // is notified of the disconnection callback, `disconnect_device` will be
    // invoked as necessary to ensure the device is removed.
    fn disconnect(&mut self, addr: RawAddress) {
        if self.is_le_audio_only_enabled {
            warn!("LeAudioEnableLeAudioOnly is set");
            return;
        }

        let connected_profiles = match self.connected_profiles.get(&addr) {
            Some(profiles) => profiles,
            None => {
                warn!(
                    "[{}]: Ignoring disconnection request since there is no connected profile.",
                    DisplayAddress(&addr)
                );
                return;
            }
        };

        for profile in connected_profiles {
            match profile {
                Profile::A2dpSink => {
                    // Some headsets (b/278963515) will try reconnecting to A2DP
                    // when HFP is running but (requested to be) disconnected.
                    // TODO: Remove this workaround once proper fix lands.
                    if connected_profiles.contains(&Profile::Hfp) {
                        continue;
                    }
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::A2dpSink as u32,
                        BtStatus::Success,
                        BtavConnectionState::Disconnecting as u32,
                    );
                    let status = self.a2dp.disconnect(addr);
                    if BtStatus::Success != status {
                        metrics::profile_connection_state_changed(
                            addr,
                            Profile::A2dpSource as u32,
                            status,
                            BtavConnectionState::Disconnected as u32,
                        );
                    }
                }
                Profile::Hfp => {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::Hfp as u32,
                        BtStatus::Success,
                        BthfConnectionState::Disconnecting as u32,
                    );
                    let status = self.hfp.disconnect(addr);
                    if BtStatus::Success != status {
                        metrics::profile_connection_state_changed(
                            addr,
                            Profile::Hfp as u32,
                            status,
                            BthfConnectionState::Disconnected as u32,
                        );
                    }
                }
                Profile::AvrcpController => {
                    if connected_profiles.contains(&Profile::A2dpSink) {
                        continue;
                    }
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Disconnecting as u32,
                    );
                    self.avrcp_states.insert(addr, BtavConnectionState::Disconnecting);
                    let status = self.avrcp.disconnect(addr);
                    if BtStatus::Success != status {
                        // Reset direction to unknown.
                        self.avrcp_states.remove(&addr);
                        metrics::profile_connection_state_changed(
                            addr,
                            Profile::AvrcpController as u32,
                            status,
                            BtavConnectionState::Disconnected as u32,
                        );
                    }
                }
                _ => warn!("Unknown profile: {:?}", profile),
            }
        }
    }

    fn set_active_device(&mut self, addr: RawAddress) {
        match self.a2dp_states.get(&addr) {
            Some(BtavConnectionState::Connected) => {
                self.a2dp.set_active_device(addr);
                self.uinput.set_active_device(addr.to_string());
            }
            _ => warn!("[{}] Not connected or disconnected A2DP address", DisplayAddress(&addr)),
        };
    }

    fn reset_active_device(&mut self) {
        // During MPS tests, there might be some A2DP stream manipulation unexpected to CRAS.
        // CRAS would then attempt to reset the active device. Ignore it during test.
        if !self.is_complete_profiles_required() {
            return;
        }

        self.a2dp.set_active_device(RawAddress::empty());
        self.uinput.set_active_device(RawAddress::empty().to_string());
    }

    fn set_hfp_active_device(&mut self, addr: RawAddress) {
        if self.hfp_states.get(&addr) == Some(&BthfConnectionState::SlcConnected) {
            self.hfp.set_active_device(addr);
        } else {
            warn!("[{}] Not connected or disconnected HFP address", DisplayAddress(&addr));
        }
    }

    fn set_audio_config(
        &mut self,
        addr: RawAddress,
        codec_type: A2dpCodecIndex,
        sample_rate: A2dpCodecSampleRate,
        bits_per_sample: A2dpCodecBitsPerSample,
        channel_mode: A2dpCodecChannelMode,
    ) -> bool {
        if self.a2dp_states.get(&addr).is_none() {
            warn!(
                "[{}]: Ignore set config event for unconnected or disconnected A2DP device",
                DisplayAddress(&addr)
            );
            return false;
        }

        let caps = self.a2dp_caps.get(&addr).unwrap_or(&Vec::new()).to_vec();

        for cap in &caps {
            if A2dpCodecIndex::from(cap.codec_type) == codec_type {
                if (A2dpCodecSampleRate::from_bits(cap.sample_rate).unwrap() & sample_rate)
                    != sample_rate
                {
                    warn!("Unsupported sample rate {:?}", sample_rate);
                    return false;
                }
                if (A2dpCodecBitsPerSample::from_bits(cap.bits_per_sample).unwrap()
                    & bits_per_sample)
                    != bits_per_sample
                {
                    warn!("Unsupported bit depth {:?}", bits_per_sample);
                    return false;
                }
                if (A2dpCodecChannelMode::from_bits(cap.channel_mode).unwrap() & channel_mode)
                    != channel_mode
                {
                    warn!("Unsupported channel mode {:?}", channel_mode);
                    return false;
                }

                let config = vec![A2dpCodecConfig {
                    codec_type: codec_type as i32,
                    codec_priority: A2dpCodecPriority::Highest as i32,
                    sample_rate: sample_rate.bits(),
                    bits_per_sample: bits_per_sample.bits(),
                    channel_mode: channel_mode.bits(),
                    ..Default::default()
                }];

                self.a2dp.config_codec(addr, config);
                return true;
            }
        }

        warn!("Unsupported codec type {:?}", codec_type);
        false
    }

    fn set_volume(&mut self, volume: u8) {
        // Guard the range 0-127 by the try_from cast from u8 to i8.
        let vol = match i8::try_from(volume) {
            Ok(val) => val,
            _ => {
                warn!("Ignore invalid volume {}", volume);
                return;
            }
        };

        // There is always no more than one active media connection, which
        // implies only one address is connected with AVRCP.
        for (addr, profiles) in &self.connected_profiles {
            if profiles.contains(&Profile::AvrcpController) {
                self.avrcp.set_volume(*addr, vol);
            }
        }
    }

    fn set_hfp_volume(&mut self, volume: u8, addr: RawAddress) {
        let vol = match i8::try_from(volume) {
            Ok(val) if val <= 15 => val,
            _ => {
                warn!("[{}]: Ignore invalid volume {}", DisplayAddress(&addr), volume);
                return;
            }
        };

        if self.hfp_states.get(&addr).is_none() {
            warn!(
                "[{}]: Ignore volume event for unconnected or disconnected HFP device",
                DisplayAddress(&addr)
            );
            return;
        }

        self.hfp.set_volume(vol, addr);
    }

    fn start_audio_request(&mut self, connection_listener: File) -> bool {
        if self.a2dp_audio_connection_listener.is_some() {
            warn!("start_audio_request: replacing an unresolved listener");
        }

        self.a2dp_audio_connection_listener = Some(connection_listener);
        self.start_audio_request_impl()
    }

    fn stop_audio_request(&mut self, connection_listener: File) {
        debug!("Stop audio request");

        if self.a2dp_audio_connection_listener.is_some() {
            warn!("stop_audio_request: replacing an unresolved listener");
        }

        self.a2dp_audio_connection_listener = Some(connection_listener);

        self.a2dp.stop_audio_request();
    }

    fn start_sco_call(
        &mut self,
        address: RawAddress,
        sco_offload: bool,
        disabled_codecs: HfpCodecBitId,
        connection_listener: File,
    ) -> bool {
        if self.hfp_audio_connection_listener.is_some() {
            warn!("start_sco_call: replacing an unresolved listener");
        }

        self.hfp_audio_connection_listener = Some(connection_listener);
        self.start_sco_call_impl(address, sco_offload, disabled_codecs)
    }

    fn stop_sco_call(&mut self, address: RawAddress, listener: File) {
        if self.hfp_audio_connection_listener.is_some() {
            warn!("stop_sco_call: replacing an unresolved listener");
        }

        self.hfp_audio_connection_listener = Some(listener);
        self.stop_sco_call_impl(address)
    }

    fn get_a2dp_audio_started(&mut self, addr: RawAddress) -> bool {
        match self.a2dp_audio_state.get(&addr) {
            Some(BtavAudioState::Started) => true,
            _ => false,
        }
    }

    fn get_hfp_audio_final_codecs(&mut self, addr: RawAddress) -> u8 {
        match self.hfp_audio_state.get(&addr) {
            Some(BthfAudioState::Connected) => match self.hfp_cap.get(&addr) {
                Some(caps)
                    if (*caps & HfpCodecFormat::LC3_TRANSPARENT)
                        == HfpCodecFormat::LC3_TRANSPARENT =>
                {
                    HfpCodecBitId::LC3
                }
                Some(caps) if (*caps & HfpCodecFormat::MSBC) == HfpCodecFormat::MSBC => {
                    HfpCodecBitId::MSBC
                }
                Some(caps)
                    if (*caps & HfpCodecFormat::MSBC_TRANSPARENT)
                        == HfpCodecFormat::MSBC_TRANSPARENT =>
                {
                    HfpCodecBitId::MSBC
                }
                Some(caps) if (*caps & HfpCodecFormat::CVSD) == HfpCodecFormat::CVSD => {
                    HfpCodecBitId::CVSD
                }
                _ => {
                    warn!("hfp_cap not found, fallback to CVSD.");
                    HfpCodecBitId::CVSD
                }
            },
            _ => HfpCodecBitId::NONE,
        }
        .try_into()
        .unwrap()
    }

    fn get_presentation_position(&mut self) -> PresentationPosition {
        let position = self.a2dp.get_presentation_position();
        PresentationPosition {
            remote_delay_report_ns: position.remote_delay_report_ns,
            total_bytes_read: position.total_bytes_read,
            data_position_sec: position.data_position_sec,
            data_position_nsec: position.data_position_nsec,
        }
    }

    fn set_player_playback_status(&mut self, status: String) {
        debug!("AVRCP received player playback status: {}", status);
        self.avrcp.set_playback_status(&status);
    }
    fn set_player_position(&mut self, position_us: i64) {
        debug!("AVRCP received player position: {}", position_us);
        self.avrcp.set_position(position_us);
    }
    fn set_player_metadata(&mut self, metadata: PlayerMetadata) {
        debug!("AVRCP received player metadata: {:?}", metadata);
        self.avrcp.set_metadata(&metadata);
    }

    fn trigger_debug_dump(&mut self) {
        self.hfp.debug_dump();
    }

    fn group_set_active(&mut self, group_id: i32) {
        self.le_audio.group_set_active(group_id);
    }

    fn source_metadata_changed(
        &mut self,
        usage: BtLeAudioUsage,
        content_type: BtLeAudioContentType,
        gain: f64,
    ) -> bool {
        let data = vec![SourceMetadata { usage, content_type, gain }];
        self.le_audio.source_metadata_changed(data);
        true
    }

    fn sink_metadata_changed(&mut self, source: BtLeAudioSource, gain: f64) -> bool {
        let data = vec![SinkMetadata { source, gain }];
        self.le_audio.sink_metadata_changed(data);
        true
    }

    fn host_start_audio_request(&mut self) -> bool {
        self.le_audio.host_start_audio_request()
    }

    fn host_stop_audio_request(&mut self) {
        self.le_audio.host_stop_audio_request();
    }

    fn peer_start_audio_request(&mut self) -> bool {
        self.le_audio.peer_start_audio_request()
    }

    fn peer_stop_audio_request(&mut self) {
        self.le_audio.peer_stop_audio_request();
    }

    fn get_host_pcm_config(&mut self) -> BtLePcmConfig {
        self.le_audio.get_host_pcm_config()
    }

    fn get_peer_pcm_config(&mut self) -> BtLePcmConfig {
        self.le_audio.get_peer_pcm_config()
    }

    fn get_host_stream_started(&mut self) -> BtLeStreamStartedStatus {
        self.le_audio.get_host_stream_started()
    }

    fn get_peer_stream_started(&mut self) -> BtLeStreamStartedStatus {
        self.le_audio.get_peer_stream_started()
    }

    fn get_unicast_monitor_mode_status(
        &mut self,
        direction: BtLeAudioDirection,
    ) -> BtLeAudioUnicastMonitorModeStatus {
        *self
            .le_audio_unicast_monitor_mode_status
            .get(&direction.into())
            .unwrap_or(&BtLeAudioUnicastMonitorModeStatus::StreamingSuspended)
    }

    fn get_group_stream_status(&mut self, group_id: i32) -> BtLeAudioGroupStreamStatus {
        if self.le_audio_groups.get(&group_id).is_none() {
            return BtLeAudioGroupStreamStatus::Idle;
        }

        self.le_audio_groups.get(&group_id).unwrap().stream_status
    }

    fn get_group_status(&mut self, group_id: i32) -> BtLeAudioGroupStatus {
        if self.le_audio_groups.get(&group_id).is_none() {
            return BtLeAudioGroupStatus::Inactive;
        }

        self.le_audio_groups.get(&group_id).unwrap().status
    }

    fn set_group_volume(&mut self, group_id: i32, volume: u8) {
        self.vc.set_volume(group_id, volume);
    }
}

impl IBluetoothTelephony for BluetoothMedia {
    fn register_telephony_callback(
        &mut self,
        callback: Box<dyn IBluetoothTelephonyCallback + Send>,
    ) -> bool {
        let _id = self.telephony_callbacks.lock().unwrap().add_callback(callback);
        true
    }

    fn set_network_available(&mut self, network_available: bool) {
        if self.telephony_device_status.network_available == network_available {
            return;
        }
        self.telephony_device_status.network_available = network_available;
        self.device_status_notification();
    }

    fn set_roaming(&mut self, roaming: bool) {
        if self.telephony_device_status.roaming == roaming {
            return;
        }
        self.telephony_device_status.roaming = roaming;
        self.device_status_notification();
    }

    fn set_signal_strength(&mut self, signal_strength: i32) -> bool {
        if !(0..=5).contains(&signal_strength) {
            warn!("Invalid signal strength, got {}, want 0 to 5", signal_strength);
            return false;
        }
        if self.telephony_device_status.signal_strength == signal_strength {
            return true;
        }

        self.telephony_device_status.signal_strength = signal_strength;
        self.device_status_notification();

        true
    }

    fn set_battery_level(&mut self, battery_level: i32) -> bool {
        if !(0..=5).contains(&battery_level) {
            warn!("Invalid battery level, got {}, want 0 to 5", battery_level);
            return false;
        }
        if self.telephony_device_status.battery_level == battery_level {
            return true;
        }

        self.telephony_device_status.battery_level = battery_level;
        self.device_status_notification();

        true
    }

    fn set_phone_ops_enabled(&mut self, enable: bool) {
        info!("Bluetooth HID telephony mode enabled");
        if self.phone_ops_enabled == enable {
            return;
        }

        self.call_list = vec![];
        self.phone_state.num_active = 0;
        self.phone_state.num_held = 0;
        self.phone_state.state = CallState::Idle;
        self.memory_dialing_number = None;
        self.last_dialing_number = None;
        self.a2dp_has_interrupted_stream = false;

        self.phone_ops_enabled = enable;
        if self.hfp_audio_state.keys().any(|addr| self.should_insert_call_when_sco_start(*addr))
            && self.hfp_audio_state.values().any(|x| x == &BthfAudioState::Connected)
        {
            self.place_active_call();
            return;
        }

        self.phone_state_change("".into());
    }

    fn set_mps_qualification_enabled(&mut self, enable: bool) {
        info!("MPS qualification mode enabled");
        if self.mps_qualification_enabled == enable {
            return;
        }

        self.call_list = vec![];
        self.phone_state.num_active = 0;
        self.phone_state.num_held = 0;
        self.phone_state.state = CallState::Idle;
        self.memory_dialing_number = None;
        self.last_dialing_number = None;
        self.a2dp_has_interrupted_stream = false;
        self.mps_qualification_enabled = enable;

        if self.hfp_audio_state.keys().any(|addr| self.should_insert_call_when_sco_start(*addr))
            && self.hfp_audio_state.values().any(|x| x == &BthfAudioState::Connected)
        {
            self.place_active_call();
            return;
        }

        self.phone_state_change("".into());
    }

    fn incoming_call(&mut self, number: String) -> bool {
        if !self.mps_qualification_enabled {
            warn!("Unexpected incoming_call dbus command. mps_qualification_enabled does not enabled.");
            return false;
        }
        self.incoming_call_impl(number)
    }

    fn dialing_call(&mut self, number: String) -> bool {
        if !self.mps_qualification_enabled {
            warn!("Unexpected incoming_call dbus command. mps_qualification_enabled does not enabled.");
            return false;
        }
        self.dialing_call_impl(number, None)
    }

    fn answer_call(&mut self) -> bool {
        if !self.mps_qualification_enabled {
            warn!(
                "Unexpected answer_call dbus command. mps_qualification_enabled does not enabled."
            );
            return false;
        }
        self.answer_call_impl()
    }

    fn hangup_call(&mut self) -> bool {
        if !self.mps_qualification_enabled {
            warn!(
                "Unexpected hangup_call dbus command. mps_qualification_enabled does not enabled."
            );
            return false;
        }
        self.hangup_call_impl()
    }

    fn set_memory_call(&mut self, number: Option<String>) -> bool {
        if !self.mps_qualification_enabled {
            warn!("Unexpected set_memory_call dbus command. mps_qualification_enabled does not enabled.");
            return false;
        }
        self.memory_dialing_number = number;
        true
    }

    fn set_last_call(&mut self, number: Option<String>) -> bool {
        if !self.mps_qualification_enabled {
            warn!("Unexpected set_last_call dbus command. mps_qualification_enabled does not enabled.");
            return false;
        }
        self.last_dialing_number = number;
        true
    }

    fn release_held(&mut self) -> bool {
        if !self.mps_qualification_enabled {
            warn!(
                "Unexpected release_held dbus command. mps_qualification_enabled does not enabled."
            );
            return false;
        }
        self.release_held_impl(None)
    }

    fn release_active_accept_held(&mut self) -> bool {
        if !self.mps_qualification_enabled {
            warn!("Unexpected release_active_accept_held dbus command. mps_qualification_enabled does not enabled.");
            return false;
        }
        self.release_active_accept_held_impl(None)
    }

    fn hold_active_accept_held(&mut self) -> bool {
        if !self.mps_qualification_enabled {
            warn!("Unexpected hold_active_accept_held dbus command. mps_qualification_enabled does not enabled.");
            return false;
        }
        self.hold_active_accept_held_impl(None)
    }

    fn audio_connect(&mut self, address: RawAddress) -> bool {
        self.start_sco_call_impl(address, false, HfpCodecBitId::NONE)
    }

    fn audio_disconnect(&mut self, address: RawAddress) {
        self.stop_sco_call_impl(address)
    }
}

struct BatteryProviderCallback {}

impl BatteryProviderCallback {
    fn new() -> Self {
        Self {}
    }
}

impl IBatteryProviderCallback for BatteryProviderCallback {
    // We do not support refreshing HFP battery information.
    fn refresh_battery_info(&mut self) {}
}

impl RPCProxy for BatteryProviderCallback {
    fn get_object_id(&self) -> String {
        "HFP BatteryProvider Callback".to_string()
    }
}
