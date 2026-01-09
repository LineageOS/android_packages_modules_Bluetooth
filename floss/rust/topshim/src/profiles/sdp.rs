use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::cast::{FromPrimitive, ToPrimitive};
use std::convert::TryFrom;
use std::fmt::{Debug, Formatter, Result};
use std::os::raw::c_char;
use std::sync::{Arc, Mutex};
use std::vec::Vec;

use crate::bindings::root as bindings;
use crate::btif::{ascii_to_string, ptr_to_vec, BluetoothInterface, BtStatus, RawAddress, Uuid};
use crate::topstack::get_dispatchers;
use crate::utils::{LTCheckedPtr, LTCheckedPtrMut};
use topshim_macros::{cb_variant, gen_cxx_extern_trivial_tuple, log_args};

#[derive(Clone, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum BtSdpType {
    Raw = 0,
    MapMas,
    MapMns,
    PbapPse,
    PbapPce,
    OppServer,
    SapServer,
    Dip,
    Mps,
}

impl From<bindings::bluetooth_sdp_types> for BtSdpType {
    fn from(item: bindings::bluetooth_sdp_types) -> Self {
        BtSdpType::from_u32(item).unwrap_or(BtSdpType::Raw)
    }
}

impl From<&BtSdpRecord> for BtSdpType {
    fn from(record: &BtSdpRecord) -> Self {
        match record {
            BtSdpRecord::HeaderOverlay(header) => header.sdp_type.clone(),
            BtSdpRecord::MapMas(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::MapMns(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::PbapPse(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::PbapPce(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::OppServer(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::SapServer(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::Dip(record) => record.hdr.sdp_type.clone(),
            BtSdpRecord::Mps(record) => record.hdr.sdp_type.clone(),
        }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpHeaderOverlay {
    pub sdp_type: BtSdpType,
    pub uuid: Uuid,
    pub service_name_length: u32,
    pub service_name: String,
    pub rfcomm_channel_number: i32,
    pub l2cap_psm: i32,
    pub profile_version: i32,

    pub user1_len: i32,
    pub user1_data: Vec<u8>,
    pub user2_len: i32,
    pub user2_data: Vec<u8>,
}

impl From<bindings::_bluetooth_sdp_hdr_overlay> for BtSdpHeaderOverlay {
    fn from(item: bindings::_bluetooth_sdp_hdr_overlay) -> Self {
        let user1_len = item.user1_ptr_len;
        let user1_data = unsafe {
            std::slice::from_raw_parts(item.user1_ptr, item.user1_ptr_len as usize).to_vec()
        };
        let user2_len = item.user2_ptr_len;
        let user2_data = unsafe {
            std::slice::from_raw_parts(item.user2_ptr, item.user2_ptr_len as usize).to_vec()
        };

        let sdp_hdr = unsafe {
            *((&item as *const bindings::_bluetooth_sdp_hdr_overlay)
                as *const bindings::_bluetooth_sdp_hdr)
        };
        let sdp_type = BtSdpType::from(sdp_hdr.type_);
        let uuid = sdp_hdr.uuid;
        let service_name_length = sdp_hdr.service_name_length;
        let service_name = ascii_to_string(
            unsafe {
                std::slice::from_raw_parts(
                    sdp_hdr.service_name as *const u8,
                    sdp_hdr.service_name_length as usize,
                )
            },
            sdp_hdr.service_name_length as usize,
        );
        let rfcomm_channel_number = sdp_hdr.rfcomm_channel_number;
        let l2cap_psm = sdp_hdr.l2cap_psm;
        let profile_version = sdp_hdr.profile_version;
        BtSdpHeaderOverlay {
            sdp_type,
            uuid,
            service_name_length,
            service_name,
            rfcomm_channel_number,
            l2cap_psm,
            profile_version,
            user1_len,
            user1_data,
            user2_len,
            user2_data,
        }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpMasRecord {
    pub hdr: BtSdpHeaderOverlay,
    pub mas_instance_id: u32,
    pub supported_features: u32,
    pub supported_message_types: u32,
}

impl From<bindings::_bluetooth_sdp_mas_record> for BtSdpMasRecord {
    fn from(item: bindings::_bluetooth_sdp_mas_record) -> Self {
        BtSdpMasRecord {
            hdr: BtSdpHeaderOverlay::from(item.hdr),
            mas_instance_id: item.mas_instance_id,
            supported_features: item.supported_features,
            supported_message_types: item.supported_message_types,
        }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpMnsRecord {
    pub hdr: BtSdpHeaderOverlay,
    pub supported_features: u32,
}

impl From<bindings::_bluetooth_sdp_mns_record> for BtSdpMnsRecord {
    fn from(item: bindings::_bluetooth_sdp_mns_record) -> Self {
        BtSdpMnsRecord {
            hdr: BtSdpHeaderOverlay::from(item.hdr),
            supported_features: item.supported_features,
        }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpPseRecord {
    pub hdr: BtSdpHeaderOverlay,
    pub supported_features: u32,
    pub supported_repositories: u32,
}

impl From<bindings::_bluetooth_sdp_pse_record> for BtSdpPseRecord {
    fn from(item: bindings::_bluetooth_sdp_pse_record) -> Self {
        BtSdpPseRecord {
            hdr: BtSdpHeaderOverlay::from(item.hdr),
            supported_features: item.supported_features,
            supported_repositories: item.supported_repositories,
        }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpPceRecord {
    pub hdr: BtSdpHeaderOverlay,
}

impl From<bindings::_bluetooth_sdp_pce_record> for BtSdpPceRecord {
    fn from(item: bindings::_bluetooth_sdp_pce_record) -> Self {
        BtSdpPceRecord { hdr: BtSdpHeaderOverlay::from(item.hdr) }
    }
}

pub type SupportedFormatsList = [u8; 15usize];

#[derive(Clone, Debug)]
pub struct BtSdpOpsRecord {
    pub hdr: BtSdpHeaderOverlay,
    pub supported_formats_list_len: i32,
    pub supported_formats_list: SupportedFormatsList,
}

impl From<bindings::_bluetooth_sdp_ops_record> for BtSdpOpsRecord {
    fn from(item: bindings::_bluetooth_sdp_ops_record) -> Self {
        BtSdpOpsRecord {
            hdr: BtSdpHeaderOverlay::from(item.hdr),
            supported_formats_list_len: item.supported_formats_list_len,
            supported_formats_list: item.supported_formats_list,
        }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpSapRecord {
    pub hdr: BtSdpHeaderOverlay,
}

impl From<bindings::_bluetooth_sdp_sap_record> for BtSdpSapRecord {
    fn from(item: bindings::_bluetooth_sdp_sap_record) -> Self {
        BtSdpSapRecord { hdr: BtSdpHeaderOverlay::from(item.hdr) }
    }
}

#[derive(Clone, Debug)]
pub struct BtSdpDipRecord {
    pub hdr: BtSdpHeaderOverlay,
    pub spec_id: u16,
    pub vendor: u16,
    pub vendor_id_source: u16,
    pub product: u16,
    pub version: u16,
    pub primary_record: bool,
}

impl From<bindings::_bluetooth_sdp_dip_record> for BtSdpDipRecord {
    fn from(item: bindings::_bluetooth_sdp_dip_record) -> Self {
        BtSdpDipRecord {
            hdr: BtSdpHeaderOverlay::from(item.hdr),
            spec_id: item.spec_id,
            vendor: item.vendor,
            vendor_id_source: item.vendor_id_source,
            product: item.product,
            version: item.version,
            primary_record: item.primary_record,
        }
    }
}

pub type SupportedScenarios = [u8; 8usize];
pub type SupportedDependencies = [u8; 2usize];

#[derive(Clone, Debug)]
pub struct BtSdpMpsRecord {
    pub hdr: BtSdpHeaderOverlay,
    pub supported_scenarios_mpsd: SupportedScenarios, // LibBluetooth expects big endian data
    pub supported_scenarios_mpmd: SupportedScenarios, // LibBluetooth expects big endian data
    pub supported_dependencies: SupportedDependencies, // LibBluetooth expects big endian data
}

impl Default for BtSdpMpsRecord {
    fn default() -> Self {
        let empty_uuid = Uuid::try_from(vec![0x0, 0x0]).unwrap();
        BtSdpMpsRecord {
            hdr: BtSdpHeaderOverlay {
                sdp_type: BtSdpType::Mps,
                uuid: empty_uuid,            // Not used
                service_name_length: 0,      // Not used
                service_name: String::new(), // Not used
                rfcomm_channel_number: 0,    // Not used
                l2cap_psm: 0,                // Not used
                profile_version: 0x0100,
                user1_len: 0,       // Not used
                user1_data: vec![], // Not used
                user2_len: 0,       // Not used
                user2_data: vec![], // Not used
            },
            // LibBluetooth accepts big endian data. CrOS supports:
            // - 0 Answer Incoming Call during Audio Streaming (HFP-AG_A2DP-SRC)
            // - 2 Outgoing Call during Audio Streaming (HFP-AG_A2DP-SRC)
            // - 4 Reject/Ignore Incoming Call during Audio Streaming (HFP-AG_A2DP-SRC)
            // - 6 HFP call termination during AVP connection (HFP-AG_A2DP-SRC)
            // - 8 Press Play on Audio Player during active call (HFP-AG_A2DP-SRC)
            // - 10 Start Audio Streaming after AVRCP Play Command (HFP-AG_A2DP-SRC)
            // - 12 Suspend Audio Streaming after AVRCP Pause/Stop (HFP-AG_A2DP-SRC)
            supported_scenarios_mpsd: [0, 0, 0, 0, 0, 0, 0b_0001_0101, 0b_0101_0101],
            supported_scenarios_mpmd: [0; 8],
            // LibBluetooth accepts big endian data. CrOS supports:
            // - 1 Sniff Mode During Streaming
            // - 3 (Dis-)Connection Order / Behavior
            supported_dependencies: [0, 0b_1010],
        }
    }
}

impl From<bindings::_bluetooth_sdp_mps_record> for BtSdpMpsRecord {
    fn from(item: bindings::_bluetooth_sdp_mps_record) -> Self {
        BtSdpMpsRecord {
            hdr: BtSdpHeaderOverlay::from(item.hdr),
            supported_scenarios_mpsd: item.supported_scenarios_mpsd,
            supported_scenarios_mpmd: item.supported_scenarios_mpmd,
            supported_dependencies: item.supported_dependencies,
        }
    }
}

#[derive(Clone, Debug)]
pub enum BtSdpRecord {
    HeaderOverlay(BtSdpHeaderOverlay),
    MapMas(BtSdpMasRecord),
    MapMns(BtSdpMnsRecord),
    PbapPse(BtSdpPseRecord),
    PbapPce(BtSdpPceRecord),
    OppServer(BtSdpOpsRecord),
    SapServer(BtSdpSapRecord),
    Dip(BtSdpDipRecord),
    Mps(BtSdpMpsRecord),
}

#[derive(Clone, Copy)]
#[gen_cxx_extern_trivial_tuple]
struct CxxBtSdpRecord(bindings::bluetooth_sdp_record);

impl From<CxxBtSdpRecord> for BtSdpRecord {
    fn from(item: CxxBtSdpRecord) -> Self {
        let i = item.0;

        // SAFETY: Accessing union fields is unsafe. The C-style union
        // `bluetooth_sdp_record` uses `hdr.type_` to indicate the valid field.
        // We can safely access `hdr.type_` because `hdr` is a common field.
        //
        // Based on `sdp_type`, we access the corresponding union field.
        // This is safe because `sdp_type` guarantees which field is active.
        unsafe {
            let sdp_type = BtSdpType::from(i.hdr.type_);
            match sdp_type {
                BtSdpType::Raw => BtSdpRecord::HeaderOverlay(BtSdpHeaderOverlay::from(i.hdr)),
                BtSdpType::MapMas => BtSdpRecord::MapMas(BtSdpMasRecord::from(i.mas)),
                BtSdpType::MapMns => BtSdpRecord::MapMns(BtSdpMnsRecord::from(i.mns)),
                BtSdpType::PbapPse => BtSdpRecord::PbapPse(BtSdpPseRecord::from(i.pse)),
                BtSdpType::PbapPce => BtSdpRecord::PbapPce(BtSdpPceRecord::from(i.pce)),
                BtSdpType::OppServer => BtSdpRecord::OppServer(BtSdpOpsRecord::from(i.ops)),
                BtSdpType::SapServer => BtSdpRecord::SapServer(BtSdpSapRecord::from(i.sap)),
                BtSdpType::Dip => BtSdpRecord::Dip(BtSdpDipRecord::from(i.dip)),
                BtSdpType::Mps => BtSdpRecord::Mps(BtSdpMpsRecord::from(i.mps)),
            }
        }
    }
}

impl BtSdpRecord {
    // TODO(b/446827362): Do not directly returns structures containing pointers, which is unsafe.
    fn convert_header(hdr: &mut BtSdpHeaderOverlay) -> bindings::bluetooth_sdp_hdr_overlay {
        let srv_name_ptr = LTCheckedPtrMut::from(&mut hdr.service_name);
        let user1_ptr = LTCheckedPtr::from(&hdr.user1_data);
        let user2_ptr = LTCheckedPtr::from(&hdr.user2_data);
        bindings::bluetooth_sdp_hdr_overlay {
            type_: hdr.sdp_type.to_u32().unwrap(),
            uuid: hdr.uuid,
            service_name_length: hdr.service_name_length,
            service_name: srv_name_ptr.cast_into::<c_char>(),
            rfcomm_channel_number: hdr.rfcomm_channel_number,
            l2cap_psm: hdr.l2cap_psm,
            profile_version: hdr.profile_version,
            user1_ptr_len: hdr.user1_len,
            user1_ptr: user1_ptr.into(),
            user2_ptr_len: hdr.user2_len,
            user2_ptr: user2_ptr.into(),
        }
    }

    // Get sdp record with lifetime tied to self
    // TODO(b/446827362): Do not directly returns structures containing pointers, which is unsafe.
    fn get_unsafe_record(&mut self) -> bindings::bluetooth_sdp_record {
        match self {
            BtSdpRecord::HeaderOverlay(ref mut hdr) => {
                bindings::bluetooth_sdp_record { hdr: BtSdpRecord::convert_header(hdr) }
            }
            BtSdpRecord::MapMas(mas) => bindings::bluetooth_sdp_record {
                mas: bindings::_bluetooth_sdp_mas_record {
                    hdr: BtSdpRecord::convert_header(&mut mas.hdr),
                    mas_instance_id: mas.mas_instance_id,
                    supported_features: mas.supported_features,
                    supported_message_types: mas.supported_message_types,
                },
            },
            BtSdpRecord::MapMns(mns) => bindings::bluetooth_sdp_record {
                mns: bindings::_bluetooth_sdp_mns_record {
                    hdr: BtSdpRecord::convert_header(&mut mns.hdr),
                    supported_features: mns.supported_features,
                },
            },
            BtSdpRecord::PbapPse(pse) => bindings::bluetooth_sdp_record {
                pse: bindings::_bluetooth_sdp_pse_record {
                    hdr: BtSdpRecord::convert_header(&mut pse.hdr),
                    supported_features: pse.supported_features,
                    supported_repositories: pse.supported_repositories,
                },
            },
            BtSdpRecord::PbapPce(pce) => bindings::bluetooth_sdp_record {
                pce: bindings::_bluetooth_sdp_pce_record {
                    hdr: BtSdpRecord::convert_header(&mut pce.hdr),
                },
            },
            BtSdpRecord::OppServer(ops) => bindings::bluetooth_sdp_record {
                ops: bindings::_bluetooth_sdp_ops_record {
                    hdr: BtSdpRecord::convert_header(&mut ops.hdr),
                    supported_formats_list_len: ops.supported_formats_list_len,
                    supported_formats_list: ops.supported_formats_list,
                },
            },
            BtSdpRecord::SapServer(sap) => bindings::bluetooth_sdp_record {
                sap: bindings::_bluetooth_sdp_sap_record {
                    hdr: BtSdpRecord::convert_header(&mut sap.hdr),
                },
            },
            BtSdpRecord::Dip(dip) => bindings::bluetooth_sdp_record {
                dip: bindings::_bluetooth_sdp_dip_record {
                    hdr: BtSdpRecord::convert_header(&mut dip.hdr),
                    spec_id: dip.spec_id,
                    vendor: dip.vendor,
                    vendor_id_source: dip.vendor_id_source,
                    product: dip.product,
                    version: dip.version,
                    primary_record: dip.primary_record,
                },
            },
            BtSdpRecord::Mps(mps) => bindings::bluetooth_sdp_record {
                mps: bindings::_bluetooth_sdp_mps_record {
                    hdr: BtSdpRecord::convert_header(&mut mps.hdr),
                    supported_scenarios_mpsd: mps.supported_scenarios_mpsd,
                    supported_scenarios_mpmd: mps.supported_scenarios_mpmd,
                    supported_dependencies: mps.supported_dependencies,
                },
            },
        }
    }
}

#[derive(Debug)]
pub enum SdpCallbacks {
    SdpSearch(BtStatus, RawAddress, Uuid, Vec<BtSdpRecord>),
}

pub struct SdpCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(SdpCallbacks) + Send>,
}

impl Debug for SdpCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "SdpCallbacksDispatcher {{}}")
    }
}

type SdpCb = Arc<Mutex<SdpCallbacksDispatcher>>;

cb_variant!(SdpCb, sdp_search_cb -> SdpCallbacks::SdpSearch,
  u32 -> BtStatus, RawAddress, Uuid, i32 -> _, *const CxxBtSdpRecord, {
      let _4: Vec<BtSdpRecord> = ptr_to_vec(_4, _3 as usize);
  }
);

// Rust Sdp FFI that matches the C++ Sdp Interface defined in /topshim/sdp/sdp_shim.h
#[cxx::bridge(namespace = "bluetooth::topshim::rust")]
mod ffi {
    unsafe extern "C++" {
        include!("bluetooth/types/uuid.h");
        include!("include/hardware/bt_sdp.h");
        include!("topshim/sdp/sdp_shim.h");

        #[namespace = ""]
        #[cxx_name = "bluetooth_sdp_record"]
        type BtSdpRecord = super::CxxBtSdpRecord;

        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;

        #[namespace = "bluetooth"]
        type Uuid = crate::btif::Uuid;

        type BtIntf = crate::btif::ffi::BtIntf;

        type SdpIntf;

        fn GetSdpProfile(btif: &BtIntf) -> UniquePtr<SdpIntf>;

        fn init(self: &SdpIntf) -> u32;
        #[allow(dead_code)]
        fn deinit(self: &SdpIntf) -> u32;
        fn sdp_search(self: &SdpIntf, addr: RawAddress, uuid: Uuid) -> u32;
        fn create_sdp_record(self: &SdpIntf, record: BtSdpRecord, record_handle: &mut i32) -> u32;
        fn remove_sdp_record(self: &SdpIntf, sdp_handle: i32) -> u32;
    }

    // Callbacks from C++ to Rust. Generated by cb_variant!
    extern "Rust" {
        fn sdp_search_cb(
            status: u32,
            bd_addr: RawAddress,
            uuid: Uuid,
            num_records: i32,
            records: &mut BtSdpRecord,
        );
    }
}

pub struct Sdp {
    internal: cxx::UniquePtr<ffi::SdpIntf>,
    is_init: bool,
}

// SAFETY: The pointer is to a static, thread-safe interface provided by the
// Bluetooth stack. It's safe to send this pointer across threads.
unsafe impl Send for Sdp {}

impl Sdp {
    #[log_args]
    pub fn new(intf: &BluetoothInterface) -> Sdp {
        let sdp_intf: cxx::UniquePtr<ffi::SdpIntf> = ffi::GetSdpProfile(intf.as_btif());

        Sdp { internal: sdp_intf, is_init: false }
    }

    #[log_args]
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    #[log_args]
    pub fn initialize(&mut self, callbacks: SdpCallbacksDispatcher) -> bool {
        if get_dispatchers().lock().unwrap().set::<SdpCb>(Arc::new(Mutex::new(callbacks))) {
            panic!("Tried to set dispatcher for SdpCallbacks but it already existed");
        }

        let init = self.internal.init();
        self.is_init = BtStatus::from(init) == BtStatus::Success;
        true
    }

    #[log_args]
    pub fn sdp_search(&self, address: RawAddress, uuid: Uuid) -> BtStatus {
        self.internal.sdp_search(address, uuid).into()
    }

    #[log_args]
    pub fn create_sdp_record(&self, mut record: BtSdpRecord, handle: &mut i32) -> BtStatus {
        self.internal.create_sdp_record(CxxBtSdpRecord(record.get_unsafe_record()), handle).into()
    }

    #[log_args]
    pub fn remove_sdp_record(&self, handle: i32) -> BtStatus {
        self.internal.remove_sdp_record(handle).into()
    }
}
