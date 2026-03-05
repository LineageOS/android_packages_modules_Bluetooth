//! Shim for `bt_interface_t`, providing access to libbluetooth.
//!
//! This is a shim interface for calling the C++ bluetooth interface via Rust.

use crate::bindings::root as bindings;
use crate::topstack::get_dispatchers;
use crate::utils::LTCheckedPtrMut;
use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::cast::{FromPrimitive, ToPrimitive};
use std::convert::TryFrom;
use std::fmt::{Debug, Display, Formatter, Result};
use std::hash::{Hash, Hasher};
use std::os::fd::RawFd;
use std::sync::{Arc, Mutex};
use std::vec::Vec;
use std::{cmp, mem};
use topshim_macros::{cb_variant, gen_cxx_extern_trivial, gen_cxx_extern_trivial_tuple};

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtState {
    Off = 0,
    On,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtState(bindings::bt_state_t);

impl From<CxxBtState> for BtState {
    fn from(item: CxxBtState) -> Self {
        match item.0 {
            bindings::bt_state_t_BT_STATE_OFF => BtState::Off,
            bindings::bt_state_t_BT_STATE_ON => BtState::On,
            _ => panic!("Unsupported bt_state_t {}", item.0),
        }
    }
}

impl From<BtState> for CxxBtState {
    fn from(item: BtState) -> Self {
        let i = match item {
            BtState::Off => bindings::bt_state_t_BT_STATE_OFF,
            BtState::On => bindings::bt_state_t_BT_STATE_ON,
        };
        CxxBtState(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd, Copy)]
#[repr(u32)]
pub enum BtTransport {
    Auto = 0,
    Bredr,
    Le,
}

#[gen_cxx_extern_trivial_tuple]
pub(crate) struct CxxBtTransport(pub bindings::tBT_TRANSPORT);

impl From<CxxBtTransport> for BtTransport {
    fn from(item: CxxBtTransport) -> Self {
        match item.0 {
            bindings::tBT_TRANSPORT_BT_TRANSPORT_AUTO => BtTransport::Auto,
            bindings::tBT_TRANSPORT_BT_TRANSPORT_BR_EDR => BtTransport::Bredr,
            bindings::tBT_TRANSPORT_BT_TRANSPORT_LE => BtTransport::Le,
            _ => panic!("Unsupported tBT_TRANSPORT {}", item.0),
        }
    }
}

impl From<BtTransport> for CxxBtTransport {
    fn from(item: BtTransport) -> Self {
        let i = match item {
            BtTransport::Auto => bindings::tBT_TRANSPORT_BT_TRANSPORT_AUTO,
            BtTransport::Bredr => bindings::tBT_TRANSPORT_BT_TRANSPORT_BR_EDR,
            BtTransport::Le => bindings::tBT_TRANSPORT_BT_TRANSPORT_LE,
        };
        CxxBtTransport(i)
    }
}

impl From<u8> for BtTransport {
    fn from(item: u8) -> Self {
        BtTransport::from_u8(item).unwrap()
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtAddrType {
    Public,
    Random,
    PublicId,
    RandomId,
    Unknown = 0xfe,
    Anonymous = 0xff,
}

#[gen_cxx_extern_trivial_tuple]
pub(crate) struct CxxBtAddrType(bindings::tBLE_ADDR_TYPE);

// TODO(@sarveshkalwit): Update once tBLE_ADDR_TYPE is updated to an enum
impl From<CxxBtAddrType> for BtAddrType {
    fn from(item: CxxBtAddrType) -> Self {
        BtAddrType::from_u8(item.0).unwrap_or(BtAddrType::Unknown)
    }
}

impl From<BtAddrType> for CxxBtAddrType {
    fn from(item: BtAddrType) -> Self {
        CxxBtAddrType(item.to_u8().unwrap_or(0))
    }
}

impl From<u8> for BtAddrType {
    fn from(item: u8) -> Self {
        BtAddrType::from_u8(item).unwrap_or(BtAddrType::Unknown)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum PairingVariant {
    PasskeyConfirmation = 0,
    PasskeyEntry,
    Consent,
    PasskeyNotification,
    Participation,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxPairingVariant(bindings::PairingVariant);

impl From<CxxPairingVariant> for PairingVariant {
    fn from(item: CxxPairingVariant) -> Self {
        match item.0 {
            bindings::PairingVariant_PASSKEY_CONFIRMATION => PairingVariant::PasskeyConfirmation,
            bindings::PairingVariant_PASSKEY_ENTRY => PairingVariant::PasskeyEntry,
            bindings::PairingVariant_CONSENT => PairingVariant::Consent,
            bindings::PairingVariant_PASSKEY_NOTIFICATION => PairingVariant::PasskeyNotification,
            bindings::PairingVariant_PARTICIPATION => PairingVariant::Participation,
            _ => panic!("Unsupported PairingVariant {}", item.0),
        }
    }
}

impl From<PairingVariant> for CxxPairingVariant {
    fn from(item: PairingVariant) -> Self {
        let i = match item {
            PairingVariant::PasskeyConfirmation => bindings::PairingVariant_PASSKEY_CONFIRMATION,
            PairingVariant::PasskeyEntry => bindings::PairingVariant_PASSKEY_ENTRY,
            PairingVariant::Consent => bindings::PairingVariant_CONSENT,
            PairingVariant::PasskeyNotification => bindings::PairingVariant_PASSKEY_NOTIFICATION,
            PairingVariant::Participation => bindings::PairingVariant_PARTICIPATION,
        };
        CxxPairingVariant(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtBondState {
    NotBonded = 0,
    Bonding,
    Bonded,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtBondState(bindings::bt_bond_state_t);

impl From<CxxBtBondState> for BtBondState {
    fn from(item: CxxBtBondState) -> Self {
        match item.0 {
            bindings::bt_bond_state_t_BT_BOND_STATE_NONE => BtBondState::NotBonded,
            bindings::bt_bond_state_t_BT_BOND_STATE_BONDING => BtBondState::Bonding,
            bindings::bt_bond_state_t_BT_BOND_STATE_BONDED => BtBondState::Bonded,
            _ => panic!("Unsupported bt_bond_state_t {}", item.0),
        }
    }
}

impl From<BtBondState> for CxxBtBondState {
    fn from(item: BtBondState) -> Self {
        let i = match item {
            BtBondState::NotBonded => bindings::bt_bond_state_t_BT_BOND_STATE_NONE,
            BtBondState::Bonding => bindings::bt_bond_state_t_BT_BOND_STATE_BONDING,
            BtBondState::Bonded => bindings::bt_bond_state_t_BT_BOND_STATE_BONDED,
        };
        CxxBtBondState(i)
    }
}

// Needed for conversion from DBus
impl From<u32> for BtBondState {
    fn from(item: u32) -> Self {
        BtBondState::from_u32(item).unwrap()
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtConnectionState {
    NotConnected = 0,
    ConnectedOnly = 1,
    EncryptedBredr = 3,
    EncryptedLe = 5,
    EncryptedBoth = 7,
}

impl From<i32> for BtConnectionState {
    fn from(item: i32) -> Self {
        let fallback = if item > 0 {
            BtConnectionState::ConnectedOnly
        } else {
            BtConnectionState::NotConnected
        };

        BtConnectionState::from_i32(item).unwrap_or(fallback)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtAclState {
    Connected = 0,
    Disconnected,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtAclState(bindings::bt_acl_state_t);

impl From<CxxBtAclState> for BtAclState {
    fn from(item: CxxBtAclState) -> Self {
        match item.0 {
            bindings::bt_acl_state_t_BT_ACL_STATE_CONNECTED => BtAclState::Connected,
            bindings::bt_acl_state_t_BT_ACL_STATE_DISCONNECTED => BtAclState::Disconnected,
            _ => panic!("Unsupported bt_acl_state_t {}", item.0),
        }
    }
}

impl From<BtAclState> for CxxBtAclState {
    fn from(item: BtAclState) -> Self {
        let i = match item {
            BtAclState::Connected => bindings::bt_acl_state_t_BT_ACL_STATE_CONNECTED,
            BtAclState::Disconnected => bindings::bt_acl_state_t_BT_ACL_STATE_DISCONNECTED,
        };
        CxxBtAclState(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtDeviceType {
    Unknown = 0,
    Bredr,
    Ble,
    Dual,
}

/// This is part of the DBus API, so avoid making change on it.
#[derive(Clone, Debug, Eq, Hash, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtPropertyType {
    BdName = 0x01,
    BdAddr = 0x02,
    Uuids = 0x03,
    ClassOfDevice = 0x04,
    TypeOfDevice = 0x05,
    AdapterBondedDevices = 0x08,
    RemoteFriendlyName = 0x0A,
    RemoteRssi = 0x0B,
    LocalLeFeatures = 0x0D,
    Appearance = 0x12,
    VendorProductInfo = 0x13,
    RemoteAddrType = 0x18,
    Unknown = 0xFE,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtPropertyType(bindings::bt_property_type_t);

impl From<BtPropertyType> for CxxBtPropertyType {
    fn from(item: BtPropertyType) -> Self {
        let i = match item {
            BtPropertyType::BdName => bindings::bt_property_type_t_BT_PROPERTY_BDNAME,
            BtPropertyType::BdAddr => bindings::bt_property_type_t_BT_PROPERTY_BDADDR,
            BtPropertyType::Uuids => bindings::bt_property_type_t_BT_PROPERTY_UUIDS,
            BtPropertyType::ClassOfDevice => {
                bindings::bt_property_type_t_BT_PROPERTY_CLASS_OF_DEVICE
            }
            BtPropertyType::TypeOfDevice => bindings::bt_property_type_t_BT_PROPERTY_TYPE_OF_DEVICE,
            BtPropertyType::AdapterBondedDevices => {
                bindings::bt_property_type_t_BT_PROPERTY_ADAPTER_BONDED_DEVICES
            }
            BtPropertyType::RemoteFriendlyName => {
                bindings::bt_property_type_t_BT_PROPERTY_REMOTE_FRIENDLY_NAME
            }
            BtPropertyType::RemoteRssi => bindings::bt_property_type_t_BT_PROPERTY_REMOTE_RSSI,
            BtPropertyType::LocalLeFeatures => {
                bindings::bt_property_type_t_BT_PROPERTY_LOCAL_LE_FEATURES
            }
            BtPropertyType::Appearance => bindings::bt_property_type_t_BT_PROPERTY_APPEARANCE,
            BtPropertyType::VendorProductInfo => {
                bindings::bt_property_type_t_BT_PROPERTY_VENDOR_PRODUCT_INFO
            }
            BtPropertyType::RemoteAddrType => {
                bindings::bt_property_type_t_BT_PROPERTY_REMOTE_ADDR_TYPE
            }
            BtPropertyType::Unknown => panic!("Converting BtPropertyType::Unknown to CXX"),
        };
        CxxBtPropertyType(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtDiscoveryState {
    Stopped = 0x0,
    Started,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtDiscoveryState(bindings::bt_discovery_state_t);

impl From<CxxBtDiscoveryState> for BtDiscoveryState {
    fn from(item: CxxBtDiscoveryState) -> Self {
        match item.0 {
            bindings::bt_discovery_state_t_BT_DISCOVERY_STOPPED => BtDiscoveryState::Stopped,
            bindings::bt_discovery_state_t_BT_DISCOVERY_STARTED => BtDiscoveryState::Started,
            _ => panic!("Unsupported bt_discovery_state_t {}", item.0),
        }
    }
}

impl From<BtDiscoveryState> for CxxBtDiscoveryState {
    fn from(item: BtDiscoveryState) -> Self {
        let i = match item {
            BtDiscoveryState::Stopped => bindings::bt_discovery_state_t_BT_DISCOVERY_STOPPED,
            BtDiscoveryState::Started => bindings::bt_discovery_state_t_BT_DISCOVERY_STARTED,
        };
        CxxBtDiscoveryState(i)
    }
}

#[derive(Clone, Copy, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtStatus {
    Success = 0,
    Fail,
    NotReady,
    NoMemory,
    Busy,
    Done,
    Unsupported,
    InvalidParam,
    Unhandled,
    AuthFailure,
    RemoteDeviceDown,
    AuthRejected,
    JniEnvironmentError,
    JniThreadAttachError,
    WakeLockError,
    Timeout,
    DeviceNotFound,
    UnexpectedState,
    SocketError,

    // Any statuses that couldn't be cleanly converted
    Unknown = 0xff,
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtConnectionDirection {
    Unknown = 0,
    Outgoing,
    Incoming,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtConnectionDirection(bindings::bt_conn_direction_t);

impl From<CxxBtConnectionDirection> for BtConnectionDirection {
    fn from(item: CxxBtConnectionDirection) -> Self {
        match item.0 {
            bindings::bt_conn_direction_t_BT_CONN_DIRECTION_UNKNOWN => {
                BtConnectionDirection::Unknown
            }
            bindings::bt_conn_direction_t_BT_CONN_DIRECTION_OUTGOING => {
                BtConnectionDirection::Outgoing
            }
            bindings::bt_conn_direction_t_BT_CONN_DIRECTION_INCOMING => {
                BtConnectionDirection::Incoming
            }
            _ => panic!("Unsupported bt_conn_direction_t {}", item.0),
        }
    }
}

impl From<BtConnectionDirection> for CxxBtConnectionDirection {
    fn from(item: BtConnectionDirection) -> Self {
        let i = match item {
            BtConnectionDirection::Unknown => {
                bindings::bt_conn_direction_t_BT_CONN_DIRECTION_UNKNOWN
            }
            BtConnectionDirection::Outgoing => {
                bindings::bt_conn_direction_t_BT_CONN_DIRECTION_OUTGOING
            }
            BtConnectionDirection::Incoming => {
                bindings::bt_conn_direction_t_BT_CONN_DIRECTION_INCOMING
            }
        };
        CxxBtConnectionDirection(i)
    }
}

pub fn ascii_to_string(data: &[u8], length: usize) -> String {
    // We need to reslice data because from_utf8 tries to interpret the
    // whole slice and not just what is before the null terminated portion
    let ascii = data
        .iter()
        .enumerate()
        .take_while(|&(pos, &c)| c != 0 && pos < length)
        .map(|(_pos, &x)| x)
        .collect::<Vec<u8>>();

    String::from_utf8(ascii).unwrap_or_default()
}

fn u32_from_bytes(item: &[u8]) -> u32 {
    let mut u: [u8; 4] = [0; 4];
    let len = std::cmp::min(item.len(), 4);
    u[0..len].copy_from_slice(item);
    u32::from_ne_bytes(u)
}

fn u16_from_bytes(item: &[u8]) -> u16 {
    let mut u: [u8; 2] = [0; 2];
    let len = std::cmp::min(item.len(), 2);
    u[0..len].copy_from_slice(item);
    u16::from_ne_bytes(u)
}

impl From<bindings::bt_status_t> for BtStatus {
    fn from(item: bindings::bt_status_t) -> Self {
        match BtStatus::from_u32(item) {
            Some(x) => x,
            _ => BtStatus::Unknown,
        }
    }
}

impl From<BtStatus> for u32 {
    fn from(val: BtStatus) -> Self {
        val.to_u32().unwrap_or_default()
    }
}

impl From<BtStatus> for i32 {
    fn from(val: BtStatus) -> Self {
        val.to_i32().unwrap_or_default()
    }
}

#[derive(Debug, Clone)]
pub struct BtServiceRecord {
    pub uuid: bindings::bluetooth::Uuid,
    pub channel: u16,
    pub name: String,
}

impl From<bindings::bt_service_record_t> for BtServiceRecord {
    fn from(item: bindings::bt_service_record_t) -> Self {
        let name = item.name.iter().map(|&x| x as u8).collect::<Vec<u8>>();

        BtServiceRecord {
            uuid: item.uuid,
            channel: item.channel,
            name: ascii_to_string(name.as_slice(), name.len()),
        }
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtScanMode {
    None_,
    Connectable,
    ConnectableDiscoverable,
    ConnectableLimitedDiscoverable,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtScanMode(bindings::bt_scan_mode_t);

impl From<CxxBtScanMode> for BtScanMode {
    fn from(item: CxxBtScanMode) -> Self {
        match item.0 {
            bindings::bt_scan_mode_t_BT_SCAN_MODE_NONE => BtScanMode::None_,
            bindings::bt_scan_mode_t_BT_SCAN_MODE_CONNECTABLE => BtScanMode::Connectable,
            bindings::bt_scan_mode_t_BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE => {
                BtScanMode::ConnectableDiscoverable
            }
            bindings::bt_scan_mode_t_BT_SCAN_MODE_CONNECTABLE_LIMITED_DISCOVERABLE => {
                BtScanMode::ConnectableLimitedDiscoverable
            }
            _ => panic!("Unsupported bt_scan_mode_t {}", item.0),
        }
    }
}

impl From<BtScanMode> for CxxBtScanMode {
    fn from(item: BtScanMode) -> Self {
        let i = match item {
            BtScanMode::None_ => bindings::bt_scan_mode_t_BT_SCAN_MODE_NONE,
            BtScanMode::Connectable => bindings::bt_scan_mode_t_BT_SCAN_MODE_CONNECTABLE,
            BtScanMode::ConnectableDiscoverable => {
                bindings::bt_scan_mode_t_BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE
            }
            BtScanMode::ConnectableLimitedDiscoverable => {
                bindings::bt_scan_mode_t_BT_SCAN_MODE_CONNECTABLE_LIMITED_DISCOVERABLE
            }
        };
        CxxBtScanMode(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtDiscMode {
    // reference to system/stack/btm/neighbor_inquiry.h
    NonDiscoverable = 0,
    LimitedDiscoverable = 1,
    GeneralDiscoverable = 2,
}

impl From<u32> for BtDiscMode {
    fn from(num: u32) -> Self {
        BtDiscMode::from_u32(num).unwrap_or(BtDiscMode::NonDiscoverable)
    }
}

impl From<BtDiscMode> for u32 {
    fn from(val: BtDiscMode) -> Self {
        val.to_u32().unwrap_or(0)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtThreadEvent {
    Associate = 0,
    Disassociate,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxBtThreadEvent(bindings::bt_cb_thread_evt);

impl From<CxxBtThreadEvent> for BtThreadEvent {
    fn from(item: CxxBtThreadEvent) -> Self {
        match item.0 {
            bindings::bt_cb_thread_evt_ASSOCIATE_JVM => BtThreadEvent::Associate,
            bindings::bt_cb_thread_evt_DISASSOCIATE_JVM => BtThreadEvent::Disassociate,
            _ => panic!("Unsupported bt_cb_thread_evt {}", item.0),
        }
    }
}

pub type BtLocalLeFeatures = bindings::bt_local_le_features_t;
pub type BtRemoteVersion = bindings::bt_remote_version_t;
pub type BtVendorProductInfo = bindings::bt_vendor_product_info_t;

pub type BtHciErrorCode = u8;

#[gen_cxx_extern_trivial_tuple]
pub(crate) struct CxxBtHciErrorCode(pub bindings::bt_hci_error_code_t);

impl From<CxxBtHciErrorCode> for BtHciErrorCode {
    fn from(item: CxxBtHciErrorCode) -> Self {
        item.0
    }
}

#[gen_cxx_extern_trivial]
pub type BtPinCode = bindings::bt_pin_code_t;

impl Display for BtVendorProductInfo {
    fn fmt(&self, f: &mut Formatter) -> Result {
        write!(
            f,
            "{}:v{:04X}p{:04X}d{:04X}",
            match self.vendor_id_src {
                1 => "bluetooth",
                2 => "usb",
                _ => "unknown",
            },
            self.vendor_id,
            self.product_id,
            self.version
        )
    }
}

impl TryFrom<Uuid> for Vec<u8> {
    type Error = &'static str;

    fn try_from(value: Uuid) -> std::result::Result<Self, Self::Error> {
        Ok(value.uu.to_vec())
    }
}

impl TryFrom<Vec<u8>> for Uuid {
    type Error = &'static str;

    fn try_from(value: Vec<u8>) -> std::result::Result<Self, Self::Error> {
        // base UUID defined in the Bluetooth specification
        let mut uu: [u8; 16] =
            [0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x10, 0x0, 0x80, 0x0, 0x0, 0x80, 0x5f, 0x9b, 0x34, 0xfb];
        match value.len() {
            2 => {
                uu[2..4].copy_from_slice(&value[0..2]);
                Ok(Uuid::from(uu))
            }
            4 => {
                uu[0..4].copy_from_slice(&value[0..4]);
                Ok(Uuid::from(uu))
            }
            16 => {
                uu.copy_from_slice(&value[0..16]);
                Ok(Uuid::from(uu))
            }
            _ => {
                Err("Vector size must be exactly 2 (16 bit UUID), 4 (32 bit UUID), or 16 (128 bit UUID).")
            }
        }
    }
}

impl From<[u8; 16]> for Uuid {
    fn from(value: [u8; 16]) -> Self {
        Self { uu: value }
    }
}

impl From<Uuid> for [u8; 16] {
    fn from(uuid: Uuid) -> Self {
        uuid.uu
    }
}

impl Hash for Uuid {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.uu.hash(state);
    }
}

impl Uuid {
    const BASE_UUID_NUM: u128 = 0x0000000000001000800000805f9b34fbu128;
    const BASE_UUID_MASK: u128 = !(0xffffffffu128 << 96);

    /// Creates a Uuid from little endian slice of bytes
    pub fn try_from_little_endian(value: &[u8]) -> std::result::Result<Uuid, &'static str> {
        Uuid::try_from(value.iter().rev().cloned().collect::<Vec<u8>>())
    }

    pub fn empty() -> Uuid {
        unsafe { bindings::bluetooth::Uuid_kEmpty }
    }

    pub fn from_string<S: Into<String>>(raw: S) -> Option<Self> {
        let raw: String = raw.into();

        let raw = raw.chars().filter(|c| c.is_ascii_hexdigit()).collect::<String>();
        let s = raw.as_str();
        if s.len() != 32 {
            return None;
        }

        let mut uu = [0; 16];
        for i in 0..16 {
            uu[i] = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()?;
        }

        Some(uu.into())
    }

    /// Parses an 128-bit UUID into a byte array of shortest representation.
    pub fn get_shortest_slice(&self) -> &[u8] {
        if self.in_16bit_uuid_range() {
            &self.uu[2..4]
        } else if self.in_32bit_uuid_range() {
            &self.uu[0..4]
        } else {
            &self.uu[..]
        }
    }

    /// Checks whether the UUID value is in the 16-bit Bluetooth UUID range.
    fn in_16bit_uuid_range(&self) -> bool {
        if !self.in_32bit_uuid_range() {
            return false;
        }
        self.uu[0] == 0 && self.uu[1] == 0
    }

    /// Checks whether the UUID value is in the 32-bit Bluetooth UUID range.
    fn in_32bit_uuid_range(&self) -> bool {
        let num = u128::from_be_bytes(self.uu);
        (num & Self::BASE_UUID_MASK) == Self::BASE_UUID_NUM
    }
}

/// Formats this UUID to a human-readable representation.
impl Display for Uuid {
    fn fmt(&self, f: &mut Formatter) -> Result {
        write!(
            f,
            "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
            self.uu[0], self.uu[1], self.uu[2], self.uu[3],
            self.uu[4], self.uu[5],
            self.uu[6], self.uu[7],
            self.uu[8], self.uu[9],
            self.uu[10], self.uu[11], self.uu[12], self.uu[13], self.uu[14], self.uu[15]
        )
    }
}

/// UUID that is safe to display in logs.
pub struct DisplayUuid<'a>(pub &'a Uuid);
impl Display for DisplayUuid<'_> {
    fn fmt(&self, f: &mut Formatter) -> Result {
        write!(
            f,
            "{:02x}{:02x}{:02x}{:02x}-xxxx-xxxx-xxxx-xxxx{:02x}{:02x}{:02x}{:02x}",
            self.0.uu[0],
            self.0.uu[1],
            self.0.uu[2],
            self.0.uu[3],
            self.0.uu[12],
            self.0.uu[13],
            self.0.uu[14],
            self.0.uu[15]
        )
    }
}

/// All supported Bluetooth properties after conversion.
#[derive(Debug, Clone)]
pub enum BluetoothProperty {
    BdName(String),
    BdAddr(RawAddress),
    Uuids(Vec<Uuid>),
    ClassOfDevice(u32),
    TypeOfDevice(BtDeviceType),
    AdapterBondedDevices(Vec<RawAddress>),
    RemoteFriendlyName(String),
    RemoteRssi(i8),
    LocalLeFeatures(BtLocalLeFeatures),
    Appearance(u16),
    VendorProductInfo(BtVendorProductInfo),
    RemoteAddrType(BtAddrType),
    Unknown,
}

/// Unknown or invalid RSSI value.
/// Per Core v5.3, Vol 4, E, 7.5.4. Valid RSSI is represent in 1-byte with the range:
/// BR/EDR: -128 to 127
/// LE: -127 to 20, 127
/// Set 127 as invalid value also aligns with bluez.
pub const INVALID_RSSI: i8 = 127;

/// Wherever names are sent in bindings::bt_property_t, the size of the character
/// arrays are 256. Keep one extra byte for null termination.
const PROPERTY_NAME_MAX: usize = 255;

/// This is the length of tBLE_BD_ADDR_SERIALIZED, the prop format of AdapterBondedDevices.
/// Instead of using mem::size_of::<bindings::tBLE_BD_ADDR_SERIALIZED>(), we hardcode this so that
/// we can discover this easier (crash!) when the layout is changed.
const TYPED_ADDR_LENGTH: usize = bindings::RawAddress_kLength as usize + 1;

impl BluetoothProperty {
    pub fn get_type(&self) -> BtPropertyType {
        match self {
            BluetoothProperty::BdName(_) => BtPropertyType::BdName,
            BluetoothProperty::BdAddr(_) => BtPropertyType::BdAddr,
            BluetoothProperty::Uuids(_) => BtPropertyType::Uuids,
            BluetoothProperty::ClassOfDevice(_) => BtPropertyType::ClassOfDevice,
            BluetoothProperty::TypeOfDevice(_) => BtPropertyType::TypeOfDevice,
            BluetoothProperty::AdapterBondedDevices(_) => BtPropertyType::AdapterBondedDevices,
            BluetoothProperty::RemoteFriendlyName(_) => BtPropertyType::RemoteFriendlyName,
            BluetoothProperty::RemoteRssi(_) => BtPropertyType::RemoteRssi,
            BluetoothProperty::LocalLeFeatures(_) => BtPropertyType::LocalLeFeatures,
            BluetoothProperty::Appearance(_) => BtPropertyType::Appearance,
            BluetoothProperty::VendorProductInfo(_) => BtPropertyType::VendorProductInfo,
            BluetoothProperty::RemoteAddrType(_) => BtPropertyType::RemoteAddrType,
            BluetoothProperty::Unknown => BtPropertyType::Unknown,
        }
    }

    /// Returns the length when converted to bt_property_t
    fn get_len(&self) -> usize {
        match self {
            BluetoothProperty::ClassOfDevice(_) => mem::size_of::<u32>(),
            BluetoothProperty::RemoteFriendlyName(name) => {
                cmp::min(PROPERTY_NAME_MAX, name.len() + 1)
            }
            _ => panic!("Converting unsupported BluetoothProperty {:?} to CXX", self),
        }
    }

    /// Given a mutable array, this will copy the data to that array and return a
    /// LTCheckedPtrMut to it.
    ///
    /// The lifetime of the returned pointer is tied to that of the slice given.
    fn get_data_ptr<'a>(&self, data: &'a mut [u8]) -> LTCheckedPtrMut<'a, u8> {
        let len = self.get_len();
        match self {
            BluetoothProperty::ClassOfDevice(cod) => {
                data.copy_from_slice(&cod.to_ne_bytes());
            }
            BluetoothProperty::RemoteFriendlyName(name) => {
                let copy_len = len - 1;
                data[0..copy_len].copy_from_slice(&name.as_bytes()[0..copy_len]);
                data[copy_len] = 0;
            }
            _ => panic!("Converting unsupported BluetoothProperty {:?} to CXX", self),
        };

        data.into()
    }
}

#[gen_cxx_extern_trivial]
pub type CxxBluetoothProperty = bindings::bt_property_t;

// TODO(abps) - Check that sizes are correct when given a BtProperty
impl From<CxxBluetoothProperty> for BluetoothProperty {
    fn from(prop: CxxBluetoothProperty) -> Self {
        let slice = ffi::get_property_bytes(&prop);
        let len = slice.len();

        match prop.type_ {
            bindings::bt_property_type_t_BT_PROPERTY_BDNAME => {
                BluetoothProperty::BdName(ascii_to_string(slice, len))
            }
            bindings::bt_property_type_t_BT_PROPERTY_BDADDR => {
                BluetoothProperty::BdAddr(RawAddress::from_bytes(slice).unwrap_or_default())
            }
            bindings::bt_property_type_t_BT_PROPERTY_UUIDS
            | bindings::bt_property_type_t_BT_PROPERTY_UUIDS_LE
            | bindings::bt_property_type_t_BT_PROPERTY_UUIDS_FROM_EXTENDED_INQUIRY_RESPONSE
            | bindings::bt_property_type_t_BT_PROPERTY_UUIDS_FROM_LE_ADVERTISING_DATA => {
                let count = len / mem::size_of::<Uuid>();
                BluetoothProperty::Uuids(ptr_to_vec(prop.val as *const Uuid, count))
            }
            bindings::bt_property_type_t_BT_PROPERTY_CLASS_OF_DEVICE => {
                BluetoothProperty::ClassOfDevice(u32_from_bytes(slice))
            }
            bindings::bt_property_type_t_BT_PROPERTY_TYPE_OF_DEVICE => {
                BluetoothProperty::TypeOfDevice(
                    BtDeviceType::from_u32(u32_from_bytes(slice)).unwrap_or(BtDeviceType::Unknown),
                )
            }
            bindings::bt_property_type_t_BT_PROPERTY_ADAPTER_BONDED_DEVICES => {
                assert!(
                    len.is_multiple_of(TYPED_ADDR_LENGTH),
                    "Invalid AdapterBondedDevices prop len: {}",
                    len
                );
                let count = len / TYPED_ADDR_LENGTH;
                BluetoothProperty::AdapterBondedDevices(
                    (0..count)
                        .map(|idx| {
                            // The prop is an array of tBLE_BD_ADDR_SERIALIZED which has length
                            // TYPED_ADDR_LENGTH. The first 6 bytes are the RawAddress and the 7th
                            // is the address type (public / random / etc), while we don't care
                            // about it for now.
                            let start = idx * TYPED_ADDR_LENGTH;
                            let end = start + mem::size_of::<RawAddress>();
                            RawAddress::from_bytes(&slice[start..end]).unwrap_or_default()
                        })
                        .collect(),
                )
            }
            bindings::bt_property_type_t_BT_PROPERTY_REMOTE_FRIENDLY_NAME => {
                BluetoothProperty::RemoteFriendlyName(ascii_to_string(slice, len))
            }
            bindings::bt_property_type_t_BT_PROPERTY_REMOTE_RSSI => {
                BluetoothProperty::RemoteRssi(slice[0] as i8)
            }
            bindings::bt_property_type_t_BT_PROPERTY_LOCAL_LE_FEATURES => {
                let v = unsafe { (prop.val as *const BtLocalLeFeatures).read_unaligned() };
                BluetoothProperty::LocalLeFeatures(v)
            }
            bindings::bt_property_type_t_BT_PROPERTY_APPEARANCE => {
                BluetoothProperty::Appearance(u16_from_bytes(slice))
            }
            bindings::bt_property_type_t_BT_PROPERTY_VENDOR_PRODUCT_INFO => {
                let v = unsafe { (prop.val as *const BtVendorProductInfo).read_unaligned() };
                BluetoothProperty::VendorProductInfo(BtVendorProductInfo::from(v))
            }
            bindings::bt_property_type_t_BT_PROPERTY_REMOTE_ADDR_TYPE => {
                BluetoothProperty::RemoteAddrType(BtAddrType::from(CxxBtAddrType(slice[0])))
            }
            _ => BluetoothProperty::Unknown,
        }
    }
}

/// TODO(b/446827362): Consider to strongly tie the lifetime of the data and CxxBluetoothProperty.
impl From<BluetoothProperty> for (Box<[u8]>, CxxBluetoothProperty) {
    fn from(prop: BluetoothProperty) -> Self {
        let dvec: Vec<u8> = vec![0; prop.get_len()];
        let mut data: Box<[u8]> = dvec.into_boxed_slice();
        let prop = CxxBluetoothProperty {
            type_: prop.get_type() as u32,
            len: prop.get_len() as i32,
            val: prop.get_data_ptr(&mut data).cast_into::<std::os::raw::c_void>(),
        };

        (data, prop)
    }
}

/// Generate impl cxx::ExternType for RawAddress and Uuid.
///
/// To make use of RawAddress and Uuid in cxx::bridge C++ blocks,
/// include the following snippet in the ffi module.
/// ```ignore
/// #[cxx::bridge(namespace = bluetooth::topshim::rust)]
/// mod ffi {
///     unsafe extern "C++" {
///         include!("bluetooth/types/address.h");
///         include!("bluetooth/types/uuid.h");
///
///         #[namespace = ""]
///         type RawAddress = crate::btif::RawAddress;
///
///         #[namespace = "bluetooth"]
///         type Uuid = crate::btif::Uuid;
///     }
///     // Place you shared stuff here.
/// }
/// ```
#[gen_cxx_extern_trivial]
pub type RawAddress = bindings::RawAddress;
#[gen_cxx_extern_trivial]
pub type Uuid = bindings::bluetooth::Uuid;

impl Hash for RawAddress {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.address.hash(state);
    }
}

impl Display for RawAddress {
    fn fmt(&self, f: &mut Formatter) -> Result {
        write!(
            f,
            "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
            self.address[0],
            self.address[1],
            self.address[2],
            self.address[3],
            self.address[4],
            self.address[5]
        )
    }
}

impl RawAddress {
    /// Constructs a RawAddress from a slice of 6 bytes.
    pub fn from_bytes(raw_addr: &[u8]) -> Option<RawAddress> {
        if raw_addr.len() != 6 {
            return None;
        }
        let mut raw: [u8; 6] = [0; 6];
        raw.copy_from_slice(raw_addr);
        Some(RawAddress { address: raw })
    }

    pub fn from_string<S: Into<String>>(addr: S) -> Option<RawAddress> {
        let addr: String = addr.into();
        let s = addr.split(':').collect::<Vec<&str>>();

        if s.len() != 6 {
            return None;
        }

        let mut raw: [u8; 6] = [0; 6];
        for i in 0..s.len() {
            raw[i] = match u8::from_str_radix(s[i], 16) {
                Ok(res) => res,
                Err(_) => {
                    return None;
                }
            };
        }

        Some(RawAddress { address: raw })
    }

    pub fn to_byte_arr(&self) -> [u8; 6] {
        self.address
    }

    pub fn empty() -> RawAddress {
        unsafe { bindings::RawAddress_kEmpty }
    }
}

/// Address that is safe to display in logs.
pub struct DisplayAddress<'a>(pub &'a RawAddress);
impl Display for DisplayAddress<'_> {
    fn fmt(&self, f: &mut Formatter) -> Result {
        if self.0.address.iter().all(|&x| x == 0x00) {
            write!(f, "00:00:00:00:00:00")
        } else if self.0.address.iter().all(|&x| x == 0xff) {
            write!(f, "ff:ff:ff:ff:ff:ff")
        } else {
            write!(f, "xx:xx:xx:xx:{:02x}:{:02x}", &self.0.address[4], &self.0.address[5])
        }
    }
}

#[gen_cxx_extern_trivial]
pub type AclLinkSpec = bindings::AclLinkSpec;

#[gen_cxx_extern_trivial]
type CxxPairingType = bindings::PairingType;

#[gen_cxx_extern_trivial_tuple]
struct CxxPairingInitiator(bindings::PairingInitiator);

/// An enum representing `bt_callbacks_t` from btif.
#[derive(Clone, Debug)]
pub enum BaseCallbacks {
    AdapterState(BtState),
    AdapterProperties(BtStatus, Vec<BluetoothProperty>),
    RemoteDeviceProperties(BtStatus, RawAddress, u8, Vec<BluetoothProperty>),
    DeviceFound(Vec<BluetoothProperty>),
    DiscoveryState(BtDiscoveryState),
    PinRequest(RawAddress, String, u32, bool),
    SspRequest(RawAddress, PairingVariant, u32),
    BondState(BtStatus, RawAddress, BtBondState, i32),
    AclState(BtStatus, AclLinkSpec, BtAclState, BtHciErrorCode, BtConnectionDirection, u16),
    ThreadEvent(BtThreadEvent),
    KeyMissing(RawAddress, u8),
}

pub struct BaseCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(BaseCallbacks) + Send>,
}

type BaseCb = Arc<Mutex<BaseCallbacksDispatcher>>;

cb_variant!(BaseCb, adapter_state_cb -> BaseCallbacks::AdapterState, CxxBtState -> BtState);
cb_variant!(BaseCb, adapter_properties_cb -> BaseCallbacks::AdapterProperties,
u32 -> BtStatus, &[CxxBluetoothProperty] -> Vec::<BluetoothProperty>, {
    let _1: Vec<BluetoothProperty> = _1.iter().map(|prop| (*prop).into()).collect();
});
cb_variant!(BaseCb, remote_device_properties_cb -> BaseCallbacks::RemoteDeviceProperties,
    u32 -> BtStatus, RawAddress, u8, &[CxxBluetoothProperty] -> Vec::<BluetoothProperty>, {
    let _3: Vec<BluetoothProperty> = _3.iter().map(|prop| (*prop).into()).collect();
});
cb_variant!(BaseCb, device_found_cb -> BaseCallbacks::DeviceFound,
    &[CxxBluetoothProperty] -> Vec::<BluetoothProperty>, {
    let _0: Vec<BluetoothProperty> = _0.iter().map(|prop| (*prop).into()).collect();
});
cb_variant!(BaseCb, discovery_state_cb -> BaseCallbacks::DiscoveryState,
    CxxBtDiscoveryState -> BtDiscoveryState
);
cb_variant!(BaseCb, pin_request_cb -> BaseCallbacks::PinRequest,
    RawAddress, String, u32, bool, i32 -> _
);
cb_variant!(BaseCb, ssp_request_cb -> BaseCallbacks::SspRequest,
    RawAddress,
    i32 -> _,
    CxxPairingVariant -> PairingVariant,
    u32,
    i32 -> _
);
cb_variant!(BaseCb, bond_state_cb -> BaseCallbacks::BondState,
    u32 -> BtStatus,
    RawAddress,
    CxxBtTransport -> _,
    CxxBtBondState -> BtBondState,
    CxxPairingType -> _,
    i32,
    CxxPairingInitiator -> _
);
cb_variant!(BaseCb, thread_evt_cb -> BaseCallbacks::ThreadEvent, CxxBtThreadEvent -> BtThreadEvent);
cb_variant!(BaseCb, acl_state_cb -> BaseCallbacks::AclState,
    u32 -> BtStatus,
    AclLinkSpec,
    CxxBtAclState -> BtAclState,
    CxxBtHciErrorCode -> BtHciErrorCode,
    CxxBtConnectionDirection -> BtConnectionDirection,
    u16 -> u16
);
cb_variant!(BaseCb, key_missing_cb -> BaseCallbacks::KeyMissing, RawAddress, u8);

// Rust Btif FFI that matches the C++ Btif Interface defined in /topshim/btif/btif_shim.h
#[cxx::bridge(namespace = "bluetooth::topshim::rust")]
pub(crate) mod ffi {
    unsafe extern "C++" {
        #![allow(private_interfaces)]

        include!("bluetooth/types/address.h");
        include!("include/hardware/bluetooth.h");
        include!("topshim/btif/btif_shim.h");

        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;

        #[namespace = ""]
        #[cxx_name = "bt_property_type_t"]
        type BtPropertyType = super::CxxBtPropertyType;

        #[namespace = ""]
        #[cxx_name = "bt_scan_mode_t"]
        type BtScanMode = super::CxxBtScanMode;

        #[namespace = ""]
        #[cxx_name = "bt_property_t"]
        type BluetoothProperty = super::CxxBluetoothProperty;

        #[namespace = ""]
        #[cxx_name = "PairingVariant"]
        type PairingVariant = super::CxxPairingVariant;

        #[namespace = ""]
        #[cxx_name = "tBT_TRANSPORT"]
        type BtTransport = super::CxxBtTransport;

        #[namespace = ""]
        #[cxx_name = "bt_state_t"]
        type BtState = super::CxxBtState;

        #[namespace = ""]
        #[cxx_name = "bt_acl_state_t"]
        type BtAclState = super::CxxBtAclState;

        #[namespace = ""]
        #[cxx_name = "bt_bond_state_t"]
        type BtBondState = super::CxxBtBondState;

        #[namespace = ""]
        #[cxx_name = "bt_discovery_state_t"]
        type BtDiscoveryState = super::CxxBtDiscoveryState;

        #[namespace = ""]
        #[cxx_name = "AclLinkSpec"]
        type AclLinkSpec = super::AclLinkSpec;

        #[namespace = ""]
        #[cxx_name = "bt_pin_code_t"]
        type BtPinCode = super::BtPinCode;

        #[namespace = ""]
        #[cxx_name = "bt_hci_error_code_t"]
        type BtHciErrorCode = super::CxxBtHciErrorCode;

        #[namespace = ""]
        #[cxx_name = "bt_conn_direction_t"]
        type BtConnectionDirection = super::CxxBtConnectionDirection;

        #[namespace = ""]
        #[cxx_name = "PairingType"]
        type PairingType = super::CxxPairingType;

        #[namespace = ""]
        #[cxx_name = "PairingInitiator"]
        type PairingInitiator = super::CxxPairingInitiator;

        #[namespace = ""]
        #[cxx_name = "bt_cb_thread_evt"]
        type BtThreadEvent = super::CxxBtThreadEvent;

        fn get_property_bytes(prop: &BluetoothProperty) -> &[u8];

        type BtIntf;

        fn GetBtIntf() -> UniquePtr<BtIntf>;

        fn set_adapter_index(self: &BtIntf, adapter_index: i32);
        fn bluetooth_init(
            self: &BtIntf,
            guest_mode: bool,
            is_common_criteria_mode: bool,
            config_compare_result: i32,
            is_atv: bool,
            hci_instance_name: String,
        );
        fn bluetooth_enable(self: &BtIntf, local_name: String);
        fn bluetooth_disable(self: &BtIntf);
        fn bluetooth_cleanup(self: &BtIntf);
        fn get_adapter_property(self: &BtIntf, prop_type: BtPropertyType) -> i32;
        fn set_scan_mode(self: &BtIntf, mode: BtScanMode);
        fn set_local_name(self: &BtIntf, local_name: String);
        fn set_adapter_property(self: &BtIntf, property: BluetoothProperty) -> i32;
        fn set_remote_device_property(
            self: &BtIntf,
            remote_addr: RawAddress,
            property: BluetoothProperty,
        ) -> i32;
        fn get_remote_services(self: &BtIntf, remote_addr: RawAddress, transport: i32) -> i32;
        fn start_discovery(self: &BtIntf) -> i32;
        fn cancel_discovery(self: &BtIntf) -> i32;
        fn create_bond(self: &BtIntf, bd_addr: RawAddress, transport: i32) -> i32;
        fn remove_bond(self: &BtIntf, bd_addr: RawAddress) -> i32;
        fn cancel_bond(self: &BtIntf, bd_addr: RawAddress) -> i32;
        fn pairing_is_busy(self: &BtIntf) -> bool;
        fn get_connection_state(self: &BtIntf, bd_addr: RawAddress) -> i32;
        fn pin_reply(
            self: &BtIntf,
            bd_addr: RawAddress,
            accept: u8,
            pin_len: u8,
            pin_code: &mut BtPinCode,
        ) -> i32;
        fn ssp_reply(
            self: &BtIntf,
            bd_addr: RawAddress,
            variant: PairingVariant,
            accept: u8,
            passkey: u32,
        ) -> i32;
        fn dump(self: &BtIntf, fd: i32);
        fn generate_local_oob_data(self: &BtIntf, transport: BtTransport) -> i32;
        fn clear_event_filter(self: &BtIntf) -> i32;
        fn clear_event_mask(self: &BtIntf) -> i32;
        fn clear_filter_accept_list(self: &BtIntf) -> i32;
        fn disconnect_all_acls(self: &BtIntf) -> i32;
        fn disconnect_acl(self: &BtIntf, bd_addr: RawAddress, transport: i32) -> i32;
        fn le_rand(self: &BtIntf) -> i32;
        fn set_event_filter_inquiry_result_all_devices(self: &BtIntf) -> i32;
        fn set_default_event_mask_except(self: &BtIntf, mask: u64, le_mask: u64) -> i32;
        fn restore_filter_accept_list(self: &BtIntf) -> i32;
        fn allow_wake_by_hid(self: &BtIntf) -> i32;
        fn set_event_filter_connection_setup_all_devices(self: &BtIntf) -> i32;
        fn set_suspend_state(self: &BtIntf, suspend: bool) -> i32;
        fn get_wbs_supported(self: &BtIntf) -> bool;
        fn get_swb_supported(self: &BtIntf) -> bool;
        fn is_coding_format_supported(self: &BtIntf, coding_format: u8) -> bool;
    }

    // Callbacks from C++ to Rust. Generated by cb_variant!
    extern "Rust" {
        fn adapter_state_cb(state: BtState);
        fn adapter_properties_cb(status: u32, properties: &[BluetoothProperty]);
        fn remote_device_properties_cb(
            status: u32,
            remote_addr: RawAddress,
            address_type: u8,
            properties: &[BluetoothProperty],
        );
        fn device_found_cb(properties: &[BluetoothProperty]);
        fn discovery_state_cb(state: BtDiscoveryState);
        fn pin_request_cb(
            remote_addr: RawAddress,
            bdname: String,
            passkey: u32,
            accept: bool,
            pairing_alg: i32,
        );
        fn ssp_request_cb(
            remote_addr: RawAddress,
            transport: i32,
            variant: PairingVariant,
            passkey: u32,
            pairing_alg: i32,
        );
        fn bond_state_cb(
            status: u32,
            remote_addr: RawAddress,
            transport: BtTransport,
            state: BtBondState,
            pairing_type: PairingType,
            bond_result: i32,
            pairing_initiator: PairingInitiator,
        );
        fn thread_evt_cb(evt: BtThreadEvent);
        fn acl_state_cb(
            status: u32,
            link_spec: AclLinkSpec,
            state: BtAclState,
            hci_reason: BtHciErrorCode,
            direction: BtConnectionDirection,
            handle: u16,
        );
        fn key_missing_cb(remote_addr: RawAddress, cod: u8);
    }
}

#[no_mangle]
extern "C" fn wake_lock_noop(_: *const ::std::os::raw::c_char) -> ::std::os::raw::c_int {
    // The wakelock mechanism is not available on this platform,
    // so just returning success to avoid error log.
    0
}

/// Rust wrapper around `bt_interface_t`.
pub struct BluetoothInterface {
    internal: cxx::UniquePtr<ffi::BtIntf>,

    /// Set to true after `initialize` is called.
    pub is_init: bool,
}

// SAFETY: The pointer is to a static, thread-safe interface provided by the
// Bluetooth stack. It's safe to send this pointer across threads.
unsafe impl Send for BluetoothInterface {}

#[gen_cxx_extern_trivial]
pub(crate) type CxxBluetoothInterface = bindings::bt_interface_t;

impl BluetoothInterface {
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    /// Initialize the Bluetooth interface by setting up the underlying interface.
    ///
    /// # Arguments
    ///
    /// * `callbacks` - Dispatcher struct that accepts [`BaseCallbacks`]
    /// * `hci_index` - Index of the hci adapter in use
    pub fn initialize(&mut self, callbacks: BaseCallbacksDispatcher, hci_index: i32) {
        if get_dispatchers().lock().unwrap().set::<BaseCb>(Arc::new(Mutex::new(callbacks))) {
            panic!("Tried to set dispatcher for BaseCallbacks but it already existed");
        }

        let (guest_mode, is_common_criteria_mode, config_compare_result, is_atv) =
            (false, false, 0, false);

        let hci_instance_name = String::from("default");

        self.internal.set_adapter_index(hci_index);
        self.internal.bluetooth_init(
            guest_mode,
            is_common_criteria_mode,
            config_compare_result,
            is_atv,
            hci_instance_name,
        );

        self.is_init = true;
    }

    pub fn cleanup(&self) {
        self.internal.bluetooth_cleanup()
    }

    pub fn enable(&self, local_name: String) {
        self.internal.bluetooth_enable(local_name)
    }

    pub fn disable(&self) {
        self.internal.bluetooth_disable()
    }

    pub fn get_adapter_property(&self, prop: BtPropertyType) -> i32 {
        self.internal.get_adapter_property(prop.into())
    }

    pub fn set_adapter_property(&self, prop: BluetoothProperty) -> i32 {
        let (_data, prop): (Box<[u8]>, CxxBluetoothProperty) = prop.into();
        self.internal.set_adapter_property(prop)
    }

    pub fn set_scan_mode(&self, mode: BtScanMode) {
        self.internal.set_scan_mode(mode.into())
    }

    pub fn set_local_name(&self, local_name: String) {
        self.internal.set_local_name(local_name)
    }

    pub fn set_remote_device_property(&self, addr: RawAddress, prop: BluetoothProperty) -> i32 {
        let (_data, prop): (Box<[u8]>, CxxBluetoothProperty) = prop.into();
        self.internal.set_remote_device_property(addr, prop)
    }

    pub fn get_remote_services(&self, addr: RawAddress, transport: BtTransport) -> i32 {
        self.internal.get_remote_services(addr, transport.to_i32().unwrap())
    }

    pub fn start_discovery(&self) -> i32 {
        self.internal.start_discovery()
    }

    pub fn cancel_discovery(&self) -> i32 {
        self.internal.cancel_discovery()
    }

    pub fn pairing_is_busy(&self) -> bool {
        self.internal.pairing_is_busy()
    }

    pub fn create_bond(&self, addr: RawAddress, transport: BtTransport) -> i32 {
        self.internal.create_bond(addr, transport as i32)
    }

    pub fn remove_bond(&self, addr: RawAddress) -> i32 {
        self.internal.remove_bond(addr)
    }

    pub fn cancel_bond(&self, addr: RawAddress) -> i32 {
        self.internal.cancel_bond(addr)
    }

    pub fn get_connection_state(&self, addr: RawAddress) -> BtConnectionState {
        self.internal.get_connection_state(addr).into()
    }

    pub fn pin_reply(
        &self,
        addr: RawAddress,
        accept: u8,
        pin_len: u8,
        pin_code: &mut BtPinCode,
    ) -> i32 {
        self.internal.pin_reply(addr, accept, pin_len, pin_code)
    }

    pub fn ssp_reply(
        &self,
        addr: RawAddress,
        variant: PairingVariant,
        accept: u8,
        passkey: u32,
    ) -> i32 {
        self.internal.ssp_reply(addr, variant.into(), accept, passkey)
    }

    pub fn clear_event_filter(&self) -> i32 {
        self.internal.clear_event_filter()
    }

    pub fn clear_event_mask(&self) -> i32 {
        self.internal.clear_event_mask()
    }

    pub fn clear_filter_accept_list(&self) -> i32 {
        self.internal.clear_filter_accept_list()
    }

    pub fn disconnect_all_acls(&self) -> i32 {
        self.internal.disconnect_all_acls()
    }

    pub fn allow_wake_by_hid(&self) -> i32 {
        self.internal.allow_wake_by_hid()
    }

    pub fn get_wbs_supported(&self) -> bool {
        self.internal.get_wbs_supported()
    }

    pub fn get_swb_supported(&self) -> bool {
        self.internal.get_swb_supported()
    }

    pub fn is_coding_format_supported(&self, coding_format: u8) -> bool {
        self.internal.is_coding_format_supported(coding_format)
    }

    pub fn le_rand(&self) -> i32 {
        self.internal.le_rand()
    }

    pub fn generate_local_oob_data(&self, transport: BtTransport) -> i32 {
        self.internal.generate_local_oob_data(transport.into())
    }

    pub fn restore_filter_accept_list(&self) -> i32 {
        self.internal.restore_filter_accept_list()
    }

    pub fn set_default_event_mask_except(&self, mask: u64, le_mask: u64) -> i32 {
        self.internal.set_default_event_mask_except(mask, le_mask)
    }

    pub fn set_event_filter_inquiry_result_all_devices(&self) -> i32 {
        self.internal.set_event_filter_inquiry_result_all_devices()
    }

    pub fn set_event_filter_connection_setup_all_devices(&self) -> i32 {
        self.internal.set_event_filter_connection_setup_all_devices()
    }

    pub fn set_suspend_state(&self, suspend: bool) -> i32 {
        self.internal.set_suspend_state(suspend)
    }

    pub(crate) fn as_btif(&self) -> &ffi::BtIntf {
        self.internal.as_ref().unwrap()
    }

    pub fn dump(&self, fd: RawFd) {
        self.internal.dump(fd)
    }
}

impl Debug for BluetoothInterface {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "BluetoothInterface {{ is_init: {:?} }}", self.is_init)
    }
}

pub trait ToggleableProfile {
    fn is_enabled(&self) -> bool;
    fn enable(&mut self) -> bool;
    fn disable(&mut self) -> bool;
}

pub fn get_btinterface() -> BluetoothInterface {
    let intf: cxx::UniquePtr<ffi::BtIntf> = ffi::GetBtIntf();
    BluetoothInterface { internal: intf, is_init: false }
}

// Turns C-array T[] to Vec<U>.
pub(crate) fn ptr_to_vec<T: Copy, U: From<T>>(start: *const T, length: usize) -> Vec<U> {
    unsafe { (0..length).map(|i| U::from(start.add(i).read_unaligned())).collect::<Vec<U>>() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ptr_to_vec() {
        let arr: [i32; 3] = [1, 2, 3];
        let vec: Vec<i32> = ptr_to_vec(arr.as_ptr(), arr.len());
        let expected: Vec<i32> = vec![1, 2, 3];
        assert_eq!(expected, vec);
    }

    #[test]
    fn test_display_address() {
        assert_eq!(
            format!("{}", DisplayAddress(&RawAddress::from_string("00:00:00:00:00:00").unwrap())),
            String::from("00:00:00:00:00:00")
        );
        assert_eq!(
            format!("{}", DisplayAddress(&RawAddress::from_string("ff:ff:ff:ff:ff:ff").unwrap())),
            String::from("ff:ff:ff:ff:ff:ff")
        );
        assert_eq!(
            format!("{}", DisplayAddress(&RawAddress::from_string("1a:2b:1a:2b:1a:2b").unwrap())),
            String::from("xx:xx:xx:xx:1a:2b")
        );
        assert_eq!(
            format!("{}", DisplayAddress(&RawAddress::from_string("3C:4D:3C:4D:3C:4D").unwrap())),
            String::from("xx:xx:xx:xx:3c:4d")
        );
        assert_eq!(
            format!("{}", DisplayAddress(&RawAddress::from_string("11:35:11:35:11:35").unwrap())),
            String::from("xx:xx:xx:xx:11:35")
        );
    }

    #[test]
    fn test_get_shortest_slice() {
        let uuid_16 = Uuid::from_string("0000fef3-0000-1000-8000-00805f9b34fb").unwrap();
        assert_eq!(uuid_16.get_shortest_slice(), [0xfe, 0xf3]);

        let uuid_32 = Uuid::from_string("00112233-0000-1000-8000-00805f9b34fb").unwrap();
        assert_eq!(uuid_32.get_shortest_slice(), [0x00, 0x11, 0x22, 0x33]);

        let uuid_128 = Uuid::from_string("00112233-4455-6677-8899-aabbccddeeff").unwrap();
        assert_eq!(
            uuid_128.get_shortest_slice(),
            [
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
                0xee, 0xff
            ]
        );
    }
}
