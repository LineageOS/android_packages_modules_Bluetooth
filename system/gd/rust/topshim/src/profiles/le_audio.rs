use crate::btif::{BluetoothInterface, RawAddress, ToggleableProfile};
use crate::topstack::get_dispatchers;

use std::sync::{Arc, Mutex};
use topshim_macros::{cb_variant, profile_enabled_or, profile_enabled_or_default};

use log::warn;

#[cxx::bridge(namespace = bluetooth::topshim::rust)]
pub mod ffi {
    unsafe extern "C++" {
        include!("types/raw_address.h");
        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioCodecIndex {
        SrcLc3 = 0,
        SrcInvalid = 1_000_000,
    }

    #[derive(Debug, Copy, Clone)]
    pub struct BtLeAudioCodecConfig {
        pub codec_type: i32,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioConnectionState {
        Disconnected = 0,
        Connecting,
        Connected,
        Disconnecting,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioGroupStatus {
        Inactive = 0,
        Active,
        TurnedIdleDuringCall,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioGroupNodeStatus {
        Added = 1,
        Removed,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioUsage {
        AudioUsageUnknown = 0,
        AudioUsageMedia = 1,
        AudioUsageVoiceCommunication = 2,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioContentType {
        AudioContentTypeUnknown = 0,
        AudioContentTypeSpeech = 1,
        AudioContentTypeMusic = 2,
        AudioContentTypeMovie = 3,
        AudioContentTypeSonification = 4,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioSource {
        AudioSourceDefault = 0,
        AudioSourceMic = 1,
        AudioSourceVoiceUplink = 2,
        AudioSourceVoiceDownlink = 3,
        AudioSourceVoiceCall = 4,
        AudioSourceCamcorder = 5,
        AudioSourceVoiceRecognition = 6,
        AudioSourceVoiceCommunication = 7,
    }

    #[derive(Debug, Copy, Clone)]
    #[repr(i32)]
    pub enum BtLeStreamStartedStatus {
        Canceled = -1,
        Idle = 0,
        Started = 1,
    }

    #[derive(Debug, Default)]
    pub struct BtLePcmConfig {
        pub data_interval_us: u32,
        pub sample_rate: u32,
        pub bits_per_sample: u8,
        pub channels_count: u8,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioUnicastMonitorModeStatus {
        StreamingRequested = 0,
        Streaming = 1,
        StreamingSuspended = 2,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioDirection {
        Sink = 1,
        Source = 2,
        Both = 3,
    }

    #[derive(Debug, Copy, Clone)]
    pub enum BtLeAudioGroupStreamStatus {
        Idle = 0,
        Streaming,
        Releasing,
        Suspending,
        Suspended,
        ConfiguredAutonomous,
        ConfiguredByUser,
        Destroyed,
    }

    #[derive(Debug)]
    pub struct SourceMetadata {
        pub usage: BtLeAudioUsage,
        pub content_type: BtLeAudioContentType,
        pub gain: f64,
    }

    #[derive(Debug)]
    pub struct SinkMetadata {
        pub source: BtLeAudioSource,
        pub gain: f64,
    }

    unsafe extern "C++" {
        include!("le_audio/le_audio_shim.h");

        type LeAudioClientIntf;

        unsafe fn GetLeAudioClientProfile(btif: *const u8) -> UniquePtr<LeAudioClientIntf>;

        fn init(self: Pin<&mut LeAudioClientIntf>);
        fn connect(self: Pin<&mut LeAudioClientIntf>, addr: RawAddress);
        fn disconnect(self: Pin<&mut LeAudioClientIntf>, addr: RawAddress);
        fn set_enable_state(self: Pin<&mut LeAudioClientIntf>, addr: RawAddress, enabled: bool);
        fn cleanup(self: Pin<&mut LeAudioClientIntf>);
        fn remove_device(self: Pin<&mut LeAudioClientIntf>, addr: RawAddress);
        fn group_add_node(self: Pin<&mut LeAudioClientIntf>, group_id: i32, addr: RawAddress);
        fn group_remove_node(self: Pin<&mut LeAudioClientIntf>, group_id: i32, addr: RawAddress);
        fn group_set_active(self: Pin<&mut LeAudioClientIntf>, group_id: i32);
        fn set_codec_config_preference(
            self: Pin<&mut LeAudioClientIntf>,
            group_id: i32,
            input_codec_config: BtLeAudioCodecConfig,
            output_codec_config: BtLeAudioCodecConfig,
        );
        fn set_ccid_information(self: Pin<&mut LeAudioClientIntf>, ccid: i32, context_type: i32);
        fn set_in_call(self: Pin<&mut LeAudioClientIntf>, in_call: bool);
        fn send_audio_profile_preferences(
            self: Pin<&mut LeAudioClientIntf>,
            group_id: i32,
            is_output_preference_le_audio: bool,
            is_duplex_preference_le_audio: bool,
        );
        fn set_unicast_monitor_mode(
            self: Pin<&mut LeAudioClientIntf>,
            direction: BtLeAudioDirection,
            enable: bool,
        );

        fn host_start_audio_request(self: Pin<&mut LeAudioClientIntf>) -> bool;
        fn host_stop_audio_request(self: Pin<&mut LeAudioClientIntf>);
        fn peer_start_audio_request(self: Pin<&mut LeAudioClientIntf>) -> bool;
        fn peer_stop_audio_request(self: Pin<&mut LeAudioClientIntf>);
        fn get_host_pcm_config(self: Pin<&mut LeAudioClientIntf>) -> BtLePcmConfig;
        fn get_peer_pcm_config(self: Pin<&mut LeAudioClientIntf>) -> BtLePcmConfig;
        fn get_host_stream_started(self: Pin<&mut LeAudioClientIntf>) -> BtLeStreamStartedStatus;
        fn get_peer_stream_started(self: Pin<&mut LeAudioClientIntf>) -> BtLeStreamStartedStatus;
        fn source_metadata_changed(
            self: Pin<&mut LeAudioClientIntf>,
            metadata: Vec<SourceMetadata>,
        );
        fn sink_metadata_changed(self: Pin<&mut LeAudioClientIntf>, metadata: Vec<SinkMetadata>);
    }

    extern "Rust" {
        fn le_audio_initialized_callback();
        fn le_audio_connection_state_callback(state: BtLeAudioConnectionState, addr: RawAddress);
        fn le_audio_group_status_callback(group_id: i32, group_status: BtLeAudioGroupStatus);
        fn le_audio_group_node_status_callback(
            bd_addr: RawAddress,
            group_id: i32,
            node_status: BtLeAudioGroupNodeStatus,
        );
        fn le_audio_audio_conf_callback(
            direction: u8,
            group_id: i32,
            snk_audio_location: u32,
            src_audio_location: u32,
            avail_cont: u16,
        );
        fn le_audio_sink_audio_location_available_callback(
            addr: RawAddress,
            snk_audio_locations: u32,
        );
        fn le_audio_audio_local_codec_capabilities_callback(
            local_input_capa_codec_conf: &Vec<BtLeAudioCodecConfig>,
            local_output_capa_codec_conf: &Vec<BtLeAudioCodecConfig>,
        );
        fn le_audio_audio_group_codec_conf_callback(
            group_id: i32,
            input_codec_conf: BtLeAudioCodecConfig,
            output_codec_conf: BtLeAudioCodecConfig,
            input_selectable_codec_conf: &Vec<BtLeAudioCodecConfig>,
            output_selectable_codec_conf: &Vec<BtLeAudioCodecConfig>,
        );
        fn le_audio_unicast_monitor_mode_status_callback(
            direction: BtLeAudioDirection,
            status: BtLeAudioUnicastMonitorModeStatus,
        );

        fn le_audio_group_stream_status_callback(group_id: i32, status: BtLeAudioGroupStreamStatus);
    }
}

pub type BtLeAudioCodecConfig = ffi::BtLeAudioCodecConfig;
pub type BtLeAudioCodecIndex = ffi::BtLeAudioCodecIndex;
pub type BtLeAudioConnectionState = ffi::BtLeAudioConnectionState;
pub type BtLeAudioDirection = ffi::BtLeAudioDirection;
pub type BtLeAudioGroupStatus = ffi::BtLeAudioGroupStatus;
pub type BtLeAudioGroupNodeStatus = ffi::BtLeAudioGroupNodeStatus;
pub type BtLePcmConfig = ffi::BtLePcmConfig;
pub type BtLeStreamStartedStatus = ffi::BtLeStreamStartedStatus;
pub type BtLeAudioUsage = ffi::BtLeAudioUsage;
pub type BtLeAudioContentType = ffi::BtLeAudioContentType;
pub type BtLeAudioSource = ffi::BtLeAudioSource;
pub type BtLeAudioUnicastMonitorModeStatus = ffi::BtLeAudioUnicastMonitorModeStatus;
pub type BtLeAudioGroupStreamStatus = ffi::BtLeAudioGroupStreamStatus;
pub type SourceMetadata = ffi::SourceMetadata;
pub type SinkMetadata = ffi::SinkMetadata;

impl From<BtLeAudioGroupStatus> for i32 {
    fn from(value: BtLeAudioGroupStatus) -> Self {
        match value {
            BtLeAudioGroupStatus::Inactive => 0,
            BtLeAudioGroupStatus::Active => 1,
            BtLeAudioGroupStatus::TurnedIdleDuringCall => 2,
            _ => panic!("Invalid value {:?} to BtLeAudioGroupStatus", value),
        }
    }
}

impl From<i32> for BtLeAudioGroupStatus {
    fn from(value: i32) -> Self {
        match value {
            0 => BtLeAudioGroupStatus::Inactive,
            1 => BtLeAudioGroupStatus::Active,
            2 => BtLeAudioGroupStatus::TurnedIdleDuringCall,
            _ => panic!("Invalid value {} for BtLeAudioGroupStatus", value),
        }
    }
}

impl Default for BtLeAudioGroupStatus {
    fn default() -> Self {
        BtLeAudioGroupStatus::Inactive
    }
}

impl From<BtLeAudioGroupNodeStatus> for i32 {
    fn from(value: BtLeAudioGroupNodeStatus) -> Self {
        match value {
            BtLeAudioGroupNodeStatus::Added => 1,
            BtLeAudioGroupNodeStatus::Removed => 2,
            _ => panic!("Invalid value {:?} to BtLeAudioGroupNodeStatus", value),
        }
    }
}

impl From<i32> for BtLeAudioGroupNodeStatus {
    fn from(value: i32) -> Self {
        match value {
            1 => BtLeAudioGroupNodeStatus::Added,
            2 => BtLeAudioGroupNodeStatus::Removed,
            _ => panic!("Invalid value {} for BtLeAudioGroupNodeStatus", value),
        }
    }
}

impl From<BtLeAudioUsage> for i32 {
    fn from(value: BtLeAudioUsage) -> Self {
        match value {
            BtLeAudioUsage::AudioUsageUnknown => 0,
            BtLeAudioUsage::AudioUsageMedia => 1,
            BtLeAudioUsage::AudioUsageVoiceCommunication => 2,
            _ => panic!("Invalid value {:?} for BtLeAudioUsage", value),
        }
    }
}

impl From<i32> for BtLeAudioUsage {
    fn from(value: i32) -> Self {
        match value {
            0 => BtLeAudioUsage::AudioUsageUnknown,
            1 => BtLeAudioUsage::AudioUsageMedia,
            2 => BtLeAudioUsage::AudioUsageVoiceCommunication,
            _ => panic!("Invalid value {} for BtLeAudioUsage", value),
        }
    }
}

impl From<BtLeAudioContentType> for i32 {
    fn from(value: BtLeAudioContentType) -> Self {
        match value {
            BtLeAudioContentType::AudioContentTypeUnknown => 0,
            BtLeAudioContentType::AudioContentTypeSpeech => 1,
            BtLeAudioContentType::AudioContentTypeMusic => 2,
            BtLeAudioContentType::AudioContentTypeMovie => 3,
            BtLeAudioContentType::AudioContentTypeSonification => 4,
            _ => panic!("Invalid value {:?} for BtLeAudioContentType", value),
        }
    }
}

impl From<i32> for BtLeAudioContentType {
    fn from(value: i32) -> Self {
        match value {
            0 => BtLeAudioContentType::AudioContentTypeUnknown,
            1 => BtLeAudioContentType::AudioContentTypeSpeech,
            2 => BtLeAudioContentType::AudioContentTypeMusic,
            3 => BtLeAudioContentType::AudioContentTypeMovie,
            4 => BtLeAudioContentType::AudioContentTypeSonification,
            _ => panic!("Invalid value {} for BtLeAudioContentType", value),
        }
    }
}

impl From<BtLeAudioSource> for i32 {
    fn from(value: BtLeAudioSource) -> Self {
        match value {
            BtLeAudioSource::AudioSourceDefault => 0,
            BtLeAudioSource::AudioSourceMic => 1,
            BtLeAudioSource::AudioSourceVoiceUplink => 2,
            BtLeAudioSource::AudioSourceVoiceDownlink => 3,
            BtLeAudioSource::AudioSourceVoiceCall => 4,
            BtLeAudioSource::AudioSourceCamcorder => 5,
            BtLeAudioSource::AudioSourceVoiceRecognition => 6,
            BtLeAudioSource::AudioSourceVoiceCommunication => 7,
            _ => panic!("Invalid value {:?} for BtLeAudioSource", value),
        }
    }
}

impl From<i32> for BtLeAudioSource {
    fn from(value: i32) -> Self {
        match value {
            0 => BtLeAudioSource::AudioSourceDefault,
            1 => BtLeAudioSource::AudioSourceMic,
            2 => BtLeAudioSource::AudioSourceVoiceUplink,
            3 => BtLeAudioSource::AudioSourceVoiceDownlink,
            4 => BtLeAudioSource::AudioSourceVoiceCall,
            5 => BtLeAudioSource::AudioSourceCamcorder,
            6 => BtLeAudioSource::AudioSourceVoiceRecognition,
            7 => BtLeAudioSource::AudioSourceVoiceCommunication,
            _ => panic!("Invalid value {} for BtLeAudioSource", value),
        }
    }
}

impl From<BtLeStreamStartedStatus> for i32 {
    fn from(value: BtLeStreamStartedStatus) -> Self {
        match value {
            BtLeStreamStartedStatus::Canceled => -1,
            BtLeStreamStartedStatus::Idle => 0,
            BtLeStreamStartedStatus::Started => 1,
            _ => panic!("Invalid value {:?} for BtLeStreamStartedStatus", value),
        }
    }
}

impl From<i32> for BtLeStreamStartedStatus {
    fn from(value: i32) -> Self {
        match value {
            -1 => BtLeStreamStartedStatus::Canceled,
            0 => BtLeStreamStartedStatus::Idle,
            1 => BtLeStreamStartedStatus::Started,
            _ => panic!("Invalid value {} for BtLeStreamStartedStatus", value),
        }
    }
}
impl From<BtLeAudioUnicastMonitorModeStatus> for i32 {
    fn from(value: BtLeAudioUnicastMonitorModeStatus) -> Self {
        match value {
            BtLeAudioUnicastMonitorModeStatus::StreamingRequested => 0,
            BtLeAudioUnicastMonitorModeStatus::Streaming => 1,
            BtLeAudioUnicastMonitorModeStatus::StreamingSuspended => 2,
            _ => panic!("Invalid value {:?} to BtLeAudioUnicastMonitorModeStatus", value),
        }
    }
}

impl From<i32> for BtLeAudioUnicastMonitorModeStatus {
    fn from(value: i32) -> Self {
        match value {
            0 => BtLeAudioUnicastMonitorModeStatus::StreamingRequested,
            1 => BtLeAudioUnicastMonitorModeStatus::Streaming,
            2 => BtLeAudioUnicastMonitorModeStatus::StreamingSuspended,
            _ => panic!("Invalid value {} for BtLeAudioUnicastMonitorModeStatus", value),
        }
    }
}

impl From<BtLeAudioGroupStreamStatus> for i32 {
    fn from(value: BtLeAudioGroupStreamStatus) -> Self {
        match value {
            BtLeAudioGroupStreamStatus::Idle => 0,
            BtLeAudioGroupStreamStatus::Streaming => 1,
            BtLeAudioGroupStreamStatus::Releasing => 2,
            BtLeAudioGroupStreamStatus::Suspending => 3,
            BtLeAudioGroupStreamStatus::Suspended => 4,
            BtLeAudioGroupStreamStatus::ConfiguredAutonomous => 5,
            BtLeAudioGroupStreamStatus::ConfiguredByUser => 6,
            BtLeAudioGroupStreamStatus::Destroyed => 7,
            _ => panic!("Invalid value {:?} to BtLeAudioGroupStreamStatus", value),
        }
    }
}

impl From<i32> for BtLeAudioGroupStreamStatus {
    fn from(value: i32) -> Self {
        match value {
            0 => BtLeAudioGroupStreamStatus::Idle,
            1 => BtLeAudioGroupStreamStatus::Streaming,
            2 => BtLeAudioGroupStreamStatus::Releasing,
            3 => BtLeAudioGroupStreamStatus::Suspending,
            4 => BtLeAudioGroupStreamStatus::Suspended,
            5 => BtLeAudioGroupStreamStatus::ConfiguredAutonomous,
            6 => BtLeAudioGroupStreamStatus::ConfiguredByUser,
            7 => BtLeAudioGroupStreamStatus::Destroyed,
            _ => panic!("Invalid value {} to BtLeAudioGroupStreamStatus", value),
        }
    }
}

impl Default for BtLeAudioGroupStreamStatus {
    fn default() -> Self {
        BtLeAudioGroupStreamStatus::Idle
    }
}

impl From<BtLeAudioDirection> for i32 {
    fn from(value: BtLeAudioDirection) -> Self {
        match value {
            BtLeAudioDirection::Sink => 1,
            BtLeAudioDirection::Source => 2,
            BtLeAudioDirection::Both => 3,
            _ => panic!("Invalid value {:?} to BtLeAudioDirection", value),
        }
    }
}

impl From<i32> for BtLeAudioDirection {
    fn from(value: i32) -> Self {
        match value {
            1 => BtLeAudioDirection::Sink,
            2 => BtLeAudioDirection::Source,
            3 => BtLeAudioDirection::Both,
            _ => panic!("Invalid value {} for BtLeAudioDirection", value),
        }
    }
}

#[derive(Debug)]
pub enum LeAudioClientCallbacks {
    Initialized(),
    ConnectionState(BtLeAudioConnectionState, RawAddress),
    GroupStatus(i32, BtLeAudioGroupStatus),
    GroupNodeStatus(RawAddress, i32, BtLeAudioGroupNodeStatus),
    AudioConf(u8, i32, u32, u32, u16),
    SinkAudioLocationAvailable(RawAddress, u32),
    AudioLocalCodecCapabilities(Vec<BtLeAudioCodecConfig>, Vec<BtLeAudioCodecConfig>),
    AudioGroupCodecConf(
        i32,
        BtLeAudioCodecConfig,
        BtLeAudioCodecConfig,
        Vec<BtLeAudioCodecConfig>,
        Vec<BtLeAudioCodecConfig>,
    ),
    UnicastMonitorModeStatus(BtLeAudioDirection, BtLeAudioUnicastMonitorModeStatus),
    GroupStreamStatus(i32, BtLeAudioGroupStreamStatus),
}

pub struct LeAudioClientCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(LeAudioClientCallbacks) + Send>,
}

type LeAudioClientCb = Arc<Mutex<LeAudioClientCallbacksDispatcher>>;

cb_variant!(LeAudioClientCb,
            le_audio_initialized_callback -> LeAudioClientCallbacks::Initialized);

cb_variant!(LeAudioClientCb,
            le_audio_connection_state_callback -> LeAudioClientCallbacks::ConnectionState,
            BtLeAudioConnectionState, RawAddress);

cb_variant!(LeAudioClientCb,
            le_audio_group_status_callback -> LeAudioClientCallbacks::GroupStatus,
            i32, BtLeAudioGroupStatus);

cb_variant!(LeAudioClientCb,
            le_audio_group_node_status_callback -> LeAudioClientCallbacks::GroupNodeStatus,
            RawAddress, i32, BtLeAudioGroupNodeStatus);

cb_variant!(LeAudioClientCb,
            le_audio_audio_conf_callback -> LeAudioClientCallbacks::AudioConf,
            u8, i32, u32, u32, u16);

cb_variant!(LeAudioClientCb,
            le_audio_sink_audio_location_available_callback -> LeAudioClientCallbacks::SinkAudioLocationAvailable,
            RawAddress, u32);

cb_variant!(LeAudioClientCb,
            le_audio_unicast_monitor_mode_status_callback -> LeAudioClientCallbacks::UnicastMonitorModeStatus,
            BtLeAudioDirection, BtLeAudioUnicastMonitorModeStatus);

cb_variant!(LeAudioClientCb,
            le_audio_group_stream_status_callback -> LeAudioClientCallbacks::GroupStreamStatus,
            i32, BtLeAudioGroupStreamStatus);

cb_variant!(LeAudioClientCb,
le_audio_audio_local_codec_capabilities_callback -> LeAudioClientCallbacks::AudioLocalCodecCapabilities,
&Vec<BtLeAudioCodecConfig>, &Vec<BtLeAudioCodecConfig>,
{
    let _0: Vec<BtLeAudioCodecConfig> = _0.to_vec();
    let _1: Vec<BtLeAudioCodecConfig> = _1.to_vec();
});

cb_variant!(LeAudioClientCb,
le_audio_audio_group_codec_conf_callback -> LeAudioClientCallbacks::AudioGroupCodecConf,
i32, BtLeAudioCodecConfig, BtLeAudioCodecConfig,
&Vec<BtLeAudioCodecConfig>, &Vec<BtLeAudioCodecConfig>,
{
    let _3: Vec<BtLeAudioCodecConfig> = _3.to_vec();
    let _4: Vec<BtLeAudioCodecConfig> = _4.to_vec();
});

pub struct LeAudioClient {
    internal: cxx::UniquePtr<ffi::LeAudioClientIntf>,
    is_init: bool,
    is_enabled: bool,
}

// For *const u8 opaque btif
// SAFETY: `LeAudioClientIntf` is thread-safe to make calls from.
unsafe impl Send for LeAudioClient {}

impl ToggleableProfile for LeAudioClient {
    fn is_enabled(&self) -> bool {
        self.is_enabled
    }

    fn enable(&mut self) -> bool {
        if self.is_enabled {
            warn!("LeAudioClient is already enabled.");
            return false;
        }

        self.internal.pin_mut().init();
        self.is_enabled = true;
        true
    }

    #[profile_enabled_or(false)]
    fn disable(&mut self) -> bool {
        if !self.is_enabled {
            warn!("LeAudioClient is already disabled.");
            return false;
        }

        self.internal.pin_mut().cleanup();
        self.is_enabled = false;
        true
    }
}

impl LeAudioClient {
    pub fn new(intf: &BluetoothInterface) -> LeAudioClient {
        let lea_client_if: cxx::UniquePtr<ffi::LeAudioClientIntf>;

        // SAFETY: `intf.as_raw_ptr()` is a valid pointer to a `BluetoothInterface`
        lea_client_if = unsafe { ffi::GetLeAudioClientProfile(intf.as_raw_ptr()) };

        LeAudioClient { internal: lea_client_if, is_init: false, is_enabled: false }
    }

    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    // `internal.init` is invoked during `ToggleableProfile::enable`
    pub fn initialize(&mut self, callbacks: LeAudioClientCallbacksDispatcher) -> bool {
        if self.is_init {
            warn!("LeAudioClient has already been initialized");
            return false;
        }

        if get_dispatchers().lock().unwrap().set::<LeAudioClientCb>(Arc::new(Mutex::new(callbacks)))
        {
            panic!("Tried to set dispatcher for LeAudioClient callbacks while it already exists");
        }

        self.is_init = true;

        true
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
    pub fn set_enable_state(&mut self, addr: RawAddress, enabled: bool) {
        self.internal.pin_mut().set_enable_state(addr, enabled);
    }

    #[profile_enabled_or]
    pub fn cleanup(&mut self) {
        self.internal.pin_mut().cleanup();
    }

    #[profile_enabled_or]
    pub fn remove_device(&mut self, addr: RawAddress) {
        self.internal.pin_mut().remove_device(addr);
    }

    #[profile_enabled_or]
    pub fn group_add_node(&mut self, group_id: i32, addr: RawAddress) {
        self.internal.pin_mut().group_add_node(group_id, addr);
    }

    #[profile_enabled_or]
    pub fn group_remove_node(&mut self, group_id: i32, addr: RawAddress) {
        self.internal.pin_mut().group_remove_node(group_id, addr);
    }

    #[profile_enabled_or]
    pub fn group_set_active(&mut self, group_id: i32) {
        self.internal.pin_mut().group_set_active(group_id);
    }

    #[profile_enabled_or]
    pub fn set_codec_config_preference(
        &mut self,
        group_id: i32,
        input_codec_config: BtLeAudioCodecConfig,
        output_codec_config: BtLeAudioCodecConfig,
    ) {
        self.internal.pin_mut().set_codec_config_preference(
            group_id,
            input_codec_config,
            output_codec_config,
        );
    }

    #[profile_enabled_or]
    pub fn set_ccid_information(&mut self, ccid: i32, context_type: i32) {
        self.internal.pin_mut().set_ccid_information(ccid, context_type);
    }

    #[profile_enabled_or]
    pub fn set_in_call(&mut self, in_call: bool) {
        self.internal.pin_mut().set_in_call(in_call);
    }

    #[profile_enabled_or]
    pub fn send_audio_profile_preferences(
        &mut self,
        group_id: i32,
        is_output_preference_le_audio: bool,
        is_duplex_preference_le_audio: bool,
    ) {
        self.internal.pin_mut().send_audio_profile_preferences(
            group_id,
            is_output_preference_le_audio,
            is_duplex_preference_le_audio,
        );
    }

    #[profile_enabled_or]
    pub fn set_unicast_monitor_mode(&mut self, direction: BtLeAudioDirection, enable: bool) {
        self.internal.pin_mut().set_unicast_monitor_mode(direction, enable);
    }

    #[profile_enabled_or(false)]
    pub fn host_start_audio_request(&mut self) -> bool {
        self.internal.pin_mut().host_start_audio_request()
    }

    #[profile_enabled_or]
    pub fn host_stop_audio_request(&mut self) {
        self.internal.pin_mut().host_stop_audio_request();
    }

    #[profile_enabled_or(false)]
    pub fn peer_start_audio_request(&mut self) -> bool {
        self.internal.pin_mut().peer_start_audio_request()
    }

    #[profile_enabled_or]
    pub fn peer_stop_audio_request(&mut self) {
        self.internal.pin_mut().peer_stop_audio_request();
    }

    #[profile_enabled_or_default]
    pub fn get_host_pcm_config(&mut self) -> BtLePcmConfig {
        self.internal.pin_mut().get_host_pcm_config()
    }

    #[profile_enabled_or_default]
    pub fn get_peer_pcm_config(&mut self) -> BtLePcmConfig {
        self.internal.pin_mut().get_peer_pcm_config()
    }

    #[profile_enabled_or(BtLeStreamStartedStatus::Idle)]
    pub fn get_host_stream_started(&mut self) -> BtLeStreamStartedStatus {
        self.internal.pin_mut().get_host_stream_started()
    }

    #[profile_enabled_or(BtLeStreamStartedStatus::Idle)]
    pub fn get_peer_stream_started(&mut self) -> BtLeStreamStartedStatus {
        self.internal.pin_mut().get_peer_stream_started()
    }

    #[profile_enabled_or]
    pub fn source_metadata_changed(&mut self, metadata: Vec<SourceMetadata>) {
        self.internal.pin_mut().source_metadata_changed(metadata);
    }

    #[profile_enabled_or]
    pub fn sink_metadata_changed(&mut self, metadata: Vec<SinkMetadata>) {
        self.internal.pin_mut().sink_metadata_changed(metadata);
    }
}
