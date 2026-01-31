use crate::bindings::root as bindings;
use crate::btif::{
    BluetoothInterface, BtAddrType, BtStatus, BtTransport, CxxBtAddrType, CxxBtTransport,
    RawAddress, ToggleableProfile,
};
use crate::topstack::get_dispatchers;

use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::cast::FromPrimitive;
use std::fmt::{Debug, Formatter, Result};
use std::sync::{Arc, Mutex};
use topshim_macros::{
    cb_variant, gen_cxx_extern_trivial, gen_cxx_extern_trivial_tuple, log_args, profile_enabled_or,
};

use log::warn;

#[derive(Debug, FromPrimitive, PartialEq, PartialOrd, Copy, Clone)]
#[repr(u32)]
pub enum BthhConnectionState {
    Connected = 0,
    Connecting,
    Disconnected,
    Disconnecting,
    Accepting,
    Unknown = 0xff,
}

impl From<bindings::bthh_connection_state_t> for BthhConnectionState {
    fn from(item: bindings::bthh_connection_state_t) -> Self {
        match item {
            bindings::bthh_connection_state_t_BTHH_CONN_STATE_CONNECTED => {
                BthhConnectionState::Connected
            }
            bindings::bthh_connection_state_t_BTHH_CONN_STATE_CONNECTING => {
                BthhConnectionState::Connecting
            }
            bindings::bthh_connection_state_t_BTHH_CONN_STATE_DISCONNECTED => {
                BthhConnectionState::Disconnected
            }
            bindings::bthh_connection_state_t_BTHH_CONN_STATE_DISCONNECTING => {
                BthhConnectionState::Disconnecting
            }
            bindings::bthh_connection_state_t_BTHH_CONN_STATE_ACCEPTING => {
                BthhConnectionState::Accepting
            }
            bindings::bthh_connection_state_t_BTHH_CONN_STATE_UNKNOWN => {
                BthhConnectionState::Unknown
            }
            _ => panic!("Unsupported bthh_connection_state_t {}", item),
        }
    }
}

#[derive(Debug, PartialEq, PartialOrd)]
#[repr(u8)]
pub enum BthhStatus {
    Ok = 0,
    HsHidNotReady,
    HsInvalidRptId,
    HsTransNotSpt,
    HsInvalidParam,
    HsError,
    Error,
    ErrSdp,
    ErrProto,
    ErrDbFull,
    ErrTodUnspt,
    ErrNoRes,
    ErrAuthFailed,
    ErrHdl,
    ErrSec,
    ErrServiceChanged,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBthhStatus(pub bindings::bthh_status_t);

impl From<CxxBthhStatus> for BthhStatus {
    fn from(item: CxxBthhStatus) -> Self {
        match item.0 {
            bindings::bthh_status_t_BTHH_OK => BthhStatus::Ok,
            bindings::bthh_status_t_BTHH_HS_HID_NOT_READY => BthhStatus::HsHidNotReady,
            bindings::bthh_status_t_BTHH_HS_INVALID_RPT_ID => BthhStatus::HsInvalidRptId,
            bindings::bthh_status_t_BTHH_HS_TRANS_NOT_SPT => BthhStatus::HsTransNotSpt,
            bindings::bthh_status_t_BTHH_HS_INVALID_PARAM => BthhStatus::HsInvalidParam,
            bindings::bthh_status_t_BTHH_HS_ERROR => BthhStatus::HsError,
            bindings::bthh_status_t_BTHH_ERR => BthhStatus::Error,
            bindings::bthh_status_t_BTHH_ERR_SDP => BthhStatus::ErrSdp,
            bindings::bthh_status_t_BTHH_ERR_PROTO => BthhStatus::ErrProto,
            bindings::bthh_status_t_BTHH_ERR_DB_FULL => BthhStatus::ErrDbFull,
            bindings::bthh_status_t_BTHH_ERR_TOD_UNSPT => BthhStatus::ErrTodUnspt,
            bindings::bthh_status_t_BTHH_ERR_NO_RES => BthhStatus::ErrNoRes,
            bindings::bthh_status_t_BTHH_ERR_AUTH_FAILED => BthhStatus::ErrAuthFailed,
            bindings::bthh_status_t_BTHH_ERR_HDL => BthhStatus::ErrHdl,
            bindings::bthh_status_t_BTHH_ERR_SEC => BthhStatus::ErrSec,
            bindings::bthh_status_t_BTHH_ERR_SERVICE_CHANGED => BthhStatus::ErrServiceChanged,
            _ => panic!("Unsupported bthh_status_t {}", item.0),
        }
    }
}

impl From<BthhStatus> for CxxBthhStatus {
    fn from(item: BthhStatus) -> Self {
        let i = match item {
            BthhStatus::Ok => bindings::bthh_status_t_BTHH_OK,
            BthhStatus::HsHidNotReady => bindings::bthh_status_t_BTHH_HS_HID_NOT_READY,
            BthhStatus::HsInvalidRptId => bindings::bthh_status_t_BTHH_HS_INVALID_RPT_ID,
            BthhStatus::HsTransNotSpt => bindings::bthh_status_t_BTHH_HS_TRANS_NOT_SPT,
            BthhStatus::HsInvalidParam => bindings::bthh_status_t_BTHH_HS_INVALID_PARAM,
            BthhStatus::HsError => bindings::bthh_status_t_BTHH_HS_ERROR,
            BthhStatus::Error => bindings::bthh_status_t_BTHH_ERR,
            BthhStatus::ErrSdp => bindings::bthh_status_t_BTHH_ERR_SDP,
            BthhStatus::ErrProto => bindings::bthh_status_t_BTHH_ERR_PROTO,
            BthhStatus::ErrDbFull => bindings::bthh_status_t_BTHH_ERR_DB_FULL,
            BthhStatus::ErrTodUnspt => bindings::bthh_status_t_BTHH_ERR_TOD_UNSPT,
            BthhStatus::ErrNoRes => bindings::bthh_status_t_BTHH_ERR_AUTH_FAILED,
            BthhStatus::ErrAuthFailed => bindings::bthh_status_t_BTHH_ERR_AUTH_FAILED,
            BthhStatus::ErrHdl => bindings::bthh_status_t_BTHH_ERR_HDL,
            BthhStatus::ErrSec => bindings::bthh_status_t_BTHH_ERR_SEC,
            BthhStatus::ErrServiceChanged => bindings::bthh_status_t_BTHH_ERR_SERVICE_CHANGED,
        };
        CxxBthhStatus(i)
    }
}

#[gen_cxx_extern_trivial]
pub type BthhHidInfo = bindings::bthh_hid_info_t;

#[derive(Debug, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BthhProtocolMode {
    ReportMode = 0,
    BootMode = 1,
    UnsupportedMode = 0xff,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBthhProtocolMode(pub bindings::bthh_protocol_mode_t);

impl From<CxxBthhProtocolMode> for BthhProtocolMode {
    fn from(item: CxxBthhProtocolMode) -> Self {
        match item.0 {
            bindings::bthh_protocol_mode_t_BTHH_REPORT_MODE => BthhProtocolMode::ReportMode,
            bindings::bthh_protocol_mode_t_BTHH_BOOT_MODE => BthhProtocolMode::BootMode,
            bindings::bthh_protocol_mode_t_BTHH_UNSUPPORTED_MODE => {
                BthhProtocolMode::UnsupportedMode
            }
            _ => panic!("Unsupported bthh_protocol_mode_t {}", item.0),
        }
    }
}

impl From<BthhProtocolMode> for CxxBthhProtocolMode {
    fn from(item: BthhProtocolMode) -> Self {
        let i = match item {
            BthhProtocolMode::ReportMode => bindings::bthh_protocol_mode_t_BTHH_REPORT_MODE,
            BthhProtocolMode::BootMode => bindings::bthh_protocol_mode_t_BTHH_BOOT_MODE,
            BthhProtocolMode::UnsupportedMode => {
                bindings::bthh_protocol_mode_t_BTHH_UNSUPPORTED_MODE
            }
        };
        CxxBthhProtocolMode(i)
    }
}

#[derive(Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BthhReportType {
    InputReport = 1,
    OutputReport = 2,
    FeatureReport = 3,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBthhReportType(pub bindings::bthh_report_type_t);

impl From<CxxBthhReportType> for BthhReportType {
    fn from(item: CxxBthhReportType) -> Self {
        match item.0 {
            bindings::bthh_report_type_t_BTHH_INPUT_REPORT => BthhReportType::InputReport,
            bindings::bthh_report_type_t_BTHH_OUTPUT_REPORT => BthhReportType::OutputReport,
            bindings::bthh_report_type_t_BTHH_FEATURE_REPORT => BthhReportType::FeatureReport,
            _ => panic!("Unsupported bthh_report_type_t {}", item.0),
        }
    }
}

impl From<BthhReportType> for CxxBthhReportType {
    fn from(item: BthhReportType) -> Self {
        let i = match item {
            BthhReportType::InputReport => bindings::bthh_report_type_t_BTHH_INPUT_REPORT,
            BthhReportType::OutputReport => bindings::bthh_report_type_t_BTHH_OUTPUT_REPORT,
            BthhReportType::FeatureReport => bindings::bthh_report_type_t_BTHH_FEATURE_REPORT,
        };
        CxxBthhReportType(i)
    }
}

#[derive(Debug, PartialEq, PartialOrd)]
pub enum BthhReconnectPolicy {
    Allowed,
    NotAllowedTemporary,
    NotAllowed,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBthhReconnectPolicy(pub bindings::bthh_reconnect_policy_t);

impl From<CxxBthhReconnectPolicy> for BthhReconnectPolicy {
    fn from(item: CxxBthhReconnectPolicy) -> Self {
        match item.0 {
            bindings::bthh_reconnect_policy_t_RECONNECT_ALLOWED => BthhReconnectPolicy::Allowed,
            bindings::bthh_reconnect_policy_t_RECONNECT_NOT_ALLOWED_TEMPORARY => {
                BthhReconnectPolicy::NotAllowedTemporary
            }
            bindings::bthh_reconnect_policy_t_RECONNECT_NOT_ALLOWED => {
                BthhReconnectPolicy::NotAllowed
            }
            _ => panic!("Unsupported bthh_reconnect_policy_t {}", item.0),
        }
    }
}

impl From<BthhReconnectPolicy> for CxxBthhReconnectPolicy {
    fn from(item: BthhReconnectPolicy) -> Self {
        let i = match item {
            BthhReconnectPolicy::Allowed => bindings::bthh_reconnect_policy_t_RECONNECT_ALLOWED,
            BthhReconnectPolicy::NotAllowedTemporary => {
                bindings::bthh_reconnect_policy_t_RECONNECT_NOT_ALLOWED_TEMPORARY
            }
            BthhReconnectPolicy::NotAllowed => {
                bindings::bthh_reconnect_policy_t_RECONNECT_NOT_ALLOWED
            }
        };
        CxxBthhReconnectPolicy(i)
    }
}

fn convert_report(count: i32, raw: *mut u8) -> Vec<u8> {
    let mut v: Vec<u8> = Vec::new();
    for i in 0..isize::from_i32(count).unwrap() {
        let p: *const u8 = unsafe { raw.offset(i) };
        v.push(unsafe { *p });
    }
    v
}

#[derive(Debug)]
pub enum HHCallbacks {
    ConnectionState(RawAddress, BtAddrType, BtTransport, BthhConnectionState, BthhStatus),
    VirtualUnplug(RawAddress, BtAddrType, BtTransport, BthhStatus),
    HidInfo(RawAddress, BtAddrType, BtTransport, Box<BthhHidInfo>),
    ProtocolMode(RawAddress, BtAddrType, BtTransport, BthhStatus, BthhProtocolMode),
    IdleTime(RawAddress, BtAddrType, BtTransport, BthhStatus, i32),
    GetReport(RawAddress, BtAddrType, BtTransport, BthhStatus, Vec<u8>, i32),
    Handshake(RawAddress, BtAddrType, BtTransport, BthhStatus),
}

pub struct HHCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(HHCallbacks) + Send>,
}

impl Debug for HHCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "HHCallbacksDispatcher {{}}")
    }
}

type HHCb = Arc<Mutex<HHCallbacksDispatcher>>;

cb_variant!(HHCb, connection_state_cb -> HHCallbacks::ConnectionState,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, bindings::bthh_connection_state_t -> BthhConnectionState, CxxBthhStatus -> BthhStatus);
cb_variant!(HHCb, virtual_unplug_cb -> HHCallbacks::VirtualUnplug,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, CxxBthhStatus -> BthhStatus);
cb_variant!(HHCb, hid_info_cb -> HHCallbacks::HidInfo,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, bindings::bthh_hid_info_t -> Box::<BthhHidInfo>);
cb_variant!(HHCb, protocol_mode_cb -> HHCallbacks::ProtocolMode,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, CxxBthhStatus -> BthhStatus,
CxxBthhProtocolMode -> BthhProtocolMode);
cb_variant!(HHCb, idle_time_cb -> HHCallbacks::IdleTime,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, CxxBthhStatus -> BthhStatus, i32);
cb_variant!(HHCb, get_report_cb -> HHCallbacks::GetReport,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, CxxBthhStatus -> BthhStatus, *mut u8, i32, {
    let _4 = convert_report(_5, _4);
});
cb_variant!(HHCb, handshake_cb -> HHCallbacks::Handshake,
RawAddress, CxxBtAddrType -> BtAddrType, CxxBtTransport -> BtTransport, CxxBthhStatus -> BthhStatus);

// Rust HH FFI that matches the C++ HH Interface defined in /topshim/hh/hh_shim.h
#[cxx::bridge(namespace = "bluetooth::topshim::rust")]
mod ffi {
    unsafe extern "C++" {
        include!("bluetooth/types/address.h");
        include!("bluetooth/types/bt_transport.h");
        include!("include/hardware/bt_hh.h");
        include!("topshim/hh/hh_shim.h");

        #[namespace = ""]
        #[cxx_name = "bthh_protocol_mode_t"]
        type BthhProtocolMode = super::CxxBthhProtocolMode;

        #[namespace = ""]
        #[cxx_name = "bthh_status_t"]
        type BthhStatus = super::CxxBthhStatus;

        #[namespace = ""]
        #[cxx_name = "tBLE_ADDR_TYPE"]
        type BtAddrType = super::CxxBtAddrType;

        #[namespace = ""]
        #[cxx_name = "tBT_TRANSPORT"]
        type BtTransport = super::CxxBtTransport;

        #[namespace = ""]
        #[cxx_name = "bthh_hid_info_t"]
        type BthhHidInfo = super::BthhHidInfo;

        #[namespace = ""]
        #[cxx_name = "bthh_report_type_t"]
        type BthhReportType = super::CxxBthhReportType;

        #[namespace = ""]
        #[cxx_name = "bthh_reconnect_policy_t"]
        type BthhReconnectPolicy = super::CxxBthhReconnectPolicy;

        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;

        type BtIntf = crate::btif::ffi::BtIntf;

        type HhIntf;

        fn GetHhProfile(btif: &BtIntf) -> UniquePtr<HhIntf>;

        fn init(self: &HhIntf) -> u32;
        fn connect(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            direct: bool,
        ) -> u32;
        fn disconnect(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            reconnect_policy: BthhReconnectPolicy,
        ) -> u32;
        fn virtual_unplug(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
        ) -> u32;
        fn get_idle_time(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
        ) -> u32;
        fn set_idle_time(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            idle_time: u8,
        ) -> u32;
        fn set_info(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hid_info: BthhHidInfo,
        ) -> u32;
        fn get_protocol(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            protocol_mode: BthhProtocolMode,
        ) -> u32;
        fn set_protocol(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            protocol_mode: BthhProtocolMode,
        ) -> u32;
        fn get_report(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            report_type: BthhReportType,
            report_id: u8,
            buffer_size: i32,
        ) -> u32;
        fn get_report_reply(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            status: BthhStatus,
            report: Vec<u8>,
            size: u16,
        ) -> u32;
        fn set_report(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            report_type: BthhReportType,
            report: Vec<u8>,
        ) -> u32;
        fn send_data(
            self: &HhIntf,
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            data_ptr: Vec<u8>,
        ) -> u32;
        fn cleanup(self: &HhIntf);
        fn configure_enabled_profiles(self: &HhIntf, enable_hidp: bool, enable_hogp: bool);
    }

    // Callbacks from C++ to Rust. Generated by cb_variant!
    extern "Rust" {
        fn connection_state_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            state: u32,
            hh_status: BthhStatus,
        );
        fn hid_info_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hid_info: BthhHidInfo,
        );
        fn protocol_mode_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hh_status: BthhStatus,
            mode: BthhProtocolMode,
        );
        fn idle_time_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hh_status: BthhStatus,
            idle_rate: i32,
        );
        /// # Safety
        ///
        /// The caller must ensure that `rpt_data` is a valid pointer to an array
        /// of a non-negative `rpt_size` number of bytes. The memory pointed to
        /// by `rpt_data` must be valid for the duration of this call.
        unsafe fn get_report_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hh_status: BthhStatus,
            rpt_data: *mut u8,
            rpt_size: i32,
        );
        fn virtual_unplug_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hh_status: BthhStatus,
        );
        fn handshake_cb(
            addr: RawAddress,
            addr_type: BtAddrType,
            transport: BtTransport,
            hh_status: BthhStatus,
        );
    }
}

pub struct HidHost {
    internal: cxx::UniquePtr<ffi::HhIntf>,
    is_init: bool,
    _is_enabled: bool,
    pub is_hogp_activated: bool,
    pub is_hidp_activated: bool,
    pub is_profile_updated: bool,
}

// SAFETY: The pointer is to a static, thread-safe interface provided by the
// Bluetooth stack. It's safe to send this pointer across threads.
unsafe impl Send for HidHost {}

impl ToggleableProfile for HidHost {
    fn is_enabled(&self) -> bool {
        self._is_enabled
    }

    fn enable(&mut self) -> bool {
        let init = self.internal.init();
        self.is_init = BtStatus::from(init) == BtStatus::Success;
        self._is_enabled = self.is_init;
        true
    }

    #[profile_enabled_or(false)]
    fn disable(&mut self) -> bool {
        self.internal.cleanup();
        self._is_enabled = false;
        true
    }
}

impl HidHost {
    #[log_args]
    pub fn new(intf: &BluetoothInterface) -> HidHost {
        let hh_intf: cxx::UniquePtr<ffi::HhIntf> = ffi::GetHhProfile(intf.as_btif());

        HidHost {
            internal: hh_intf,
            is_init: false,
            _is_enabled: false,
            is_hogp_activated: false,
            is_hidp_activated: false,
            is_profile_updated: false,
        }
    }

    #[log_args]
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    #[log_args]
    pub fn initialize(&mut self, callbacks: HHCallbacksDispatcher) -> bool {
        // Register dispatcher
        if get_dispatchers().lock().unwrap().set::<HHCb>(Arc::new(Mutex::new(callbacks))) {
            panic!("Tried to set dispatcher for HHCallbacks but it already existed");
        }

        true
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn connect(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        direct: bool,
    ) -> BtStatus {
        BtStatus::from(self.internal.connect(addr, address_type.into(), transport.into(), direct))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn disconnect(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        reconnect_policy: BthhReconnectPolicy,
    ) -> BtStatus {
        BtStatus::from(self.internal.disconnect(
            addr,
            address_type.into(),
            transport.into(),
            reconnect_policy.into(),
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn virtual_unplug(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
    ) -> BtStatus {
        BtStatus::from(self.internal.virtual_unplug(addr, address_type.into(), transport.into()))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn set_info(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        info: BthhHidInfo,
    ) -> BtStatus {
        BtStatus::from(self.internal.set_info(addr, address_type.into(), transport.into(), info))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn get_protocol(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        mode: BthhProtocolMode,
    ) -> BtStatus {
        BtStatus::from(self.internal.get_protocol(
            addr,
            address_type.into(),
            transport.into(),
            mode.into(),
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn set_protocol(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        mode: BthhProtocolMode,
    ) -> BtStatus {
        BtStatus::from(self.internal.set_protocol(
            addr,
            address_type.into(),
            transport.into(),
            mode.into(),
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn get_idle_time(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
    ) -> BtStatus {
        BtStatus::from(self.internal.get_idle_time(addr, address_type.into(), transport.into()))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn set_idle_time(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        idle_time: u8,
    ) -> BtStatus {
        BtStatus::from(self.internal.set_idle_time(
            addr,
            address_type.into(),
            transport.into(),
            idle_time,
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn get_report(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        report_type: BthhReportType,
        report_id: u8,
        buffer_size: i32,
    ) -> BtStatus {
        BtStatus::from(self.internal.get_report(
            addr,
            address_type.into(),
            transport.into(),
            report_type.into(),
            report_id,
            buffer_size,
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn get_report_reply(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        status: BthhStatus,
        report: &mut [u8],
        size: u16,
    ) -> BtStatus {
        BtStatus::from(self.internal.get_report_reply(
            addr,
            address_type.into(),
            transport.into(),
            status.into(),
            report.to_vec(),
            size,
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn set_report(
        &self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        report_type: BthhReportType,
        report: &mut [u8],
    ) -> BtStatus {
        BtStatus::from(self.internal.set_report(
            addr,
            address_type.into(),
            transport.into(),
            report_type.into(),
            report.to_vec(),
        ))
    }

    #[log_args]
    #[profile_enabled_or(BtStatus::NotReady)]
    pub fn send_data(
        &mut self,
        addr: RawAddress,
        address_type: BtAddrType,
        transport: BtTransport,
        data: &mut [u8],
    ) -> BtStatus {
        BtStatus::from(self.internal.send_data(
            addr,
            address_type.into(),
            transport.into(),
            data.to_vec(),
        ))
    }

    /// return true if we need to restart hh
    #[log_args]
    pub fn configure_enabled_profiles(&mut self) -> bool {
        let needs_restart = self.is_profile_updated;
        if self.is_profile_updated {
            self.internal
                .configure_enabled_profiles(self.is_hidp_activated, self.is_hogp_activated);
            self.is_profile_updated = false;
        }
        needs_restart
    }

    #[log_args]
    pub fn activate_hogp(&mut self, active: bool) {
        if self.is_hogp_activated != active {
            self.is_hogp_activated = active;
            self.is_profile_updated = true;
        }
    }

    #[log_args]
    pub fn activate_hidp(&mut self, active: bool) {
        if self.is_hidp_activated != active {
            self.is_hidp_activated = active;
            self.is_profile_updated = true;
        }
    }

    #[log_args]
    #[profile_enabled_or]
    pub fn cleanup(&mut self) {
        self.internal.cleanup();
    }
}
