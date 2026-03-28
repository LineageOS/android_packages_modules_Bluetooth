// Copyright 2024, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#![allow(missing_docs)]

use crate::derive::{Read, Write};
use crate::reader::{Read, Reader};
use crate::writer::{pack, Write, Writer};

/// HCI Command, as defined in Part E - 5.4.1
#[derive(Debug)]
pub enum Command {
    /// 7.3.2 Reset Command
    Reset(Reset),
    /// 7.8.97 LE Set CIG Parameters
    LeSetCigParameters(LeSetCigParameters),
    /// 7.8.99 LE Create CIS
    LeCreateCis(LeCreateCis),
    /// 7.8.100 LE Remove CIG
    LeRemoveCig(LeRemoveCig),
    /// 7.8.103 LE Create BIG
    LeCreateBig(LeCreateBig),
    /// 7.8.109 LE Setup ISO Data Path
    LeSetupIsoDataPath(LeSetupIsoDataPath),
    /// 7.8.110 LE Remove ISO Data Path
    LeRemoveIsoDataPath(LeRemoveIsoDataPath),
    /// Vendor-specific LE Get Vendor Capabilities
    LeGetVendorCapabilities(LeGetVendorCapabilities),
    /// Vendor A2dp Hardware Offload
    A2dpHardwareOffload(A2dpHardwareOffload),
    /// Unknown command
    Unknown(OpCode),
}

/// HCI Command Return Parameters
#[derive(Debug, Read, Write)]
pub enum ReturnParameters {
    /// 7.3.2 Reset Command
    Reset(ResetComplete),
    /// 7.4.5 Read Buffer Size
    ReadBufferSize(ReadBufferSizeComplete),
    /// 7.8.2 LE Read Buffer Size [V1]
    LeReadBufferSizeV1(LeReadBufferSizeV1Complete),
    /// 7.8.2 LE Read Buffer Size [V2]
    LeReadBufferSizeV2(LeReadBufferSizeV2Complete),
    /// 7.8.97 LE Set CIG Parameters
    LeSetCigParameters(LeSetCigParametersComplete),
    /// 7.8.100 LE Remove CIG
    LeRemoveCig(LeRemoveCigComplete),
    /// 7.8.109 LE Setup ISO Data Path
    LeSetupIsoDataPath(LeIsoDataPathComplete),
    /// 7.8.110 LE Remove ISO Data Path
    LeRemoveIsoDataPath(LeIsoDataPathComplete),
    /// Vendor-specific LE Get Vendor Capabilities
    LeGetVendorCapabilities(LeGetVendorCapabilitiesComplete),
    /// Vendor A2dp Hardware Offload
    A2dpHardwareOffload(A2dpHardwareOffloadComplete),
    /// Unknown command
    Unknown(OpCode),
}

impl Command {
    /// Read an HCI Command packet
    pub fn from_bytes(data: &[u8]) -> Result<Self, Option<OpCode>> {
        fn parse_packet(data: &[u8]) -> Option<(OpCode, Reader)> {
            let mut r = Reader::new(data);
            let opcode = r.read()?;
            let len = r.read_u8()? as usize;
            Some((opcode, Reader::new(r.get(len)?)))
        }

        let Some((opcode, mut r)) = parse_packet(data) else {
            return Err(None);
        };

        Self::dispatch_read(opcode, &mut r).ok_or(Some(opcode))
    }

    fn dispatch_read(opcode: OpCode, r: &mut Reader) -> Option<Command> {
        Some(match opcode {
            Reset::OPCODE => Self::Reset(r.read()?),
            LeSetCigParameters::OPCODE => Self::LeSetCigParameters(r.read()?),
            LeCreateCis::OPCODE => Self::LeCreateCis(r.read()?),
            LeRemoveCig::OPCODE => Self::LeRemoveCig(r.read()?),
            LeCreateBig::OPCODE => Self::LeCreateBig(r.read()?),
            LeSetupIsoDataPath::OPCODE => Self::LeSetupIsoDataPath(r.read()?),
            LeRemoveIsoDataPath::OPCODE => Self::LeRemoveIsoDataPath(r.read()?),
            LeGetVendorCapabilities::OPCODE => Self::LeGetVendorCapabilities(r.read()?),
            A2dpHardwareOffload::OPCODE => Self::A2dpHardwareOffload(r.read()?),
            opcode => Self::Unknown(opcode),
        })
    }

    fn to_bytes<T: CommandOpCode + Write>(command: &T) -> Vec<u8> {
        let mut w = Writer::new(Vec::with_capacity(3 + 255));
        w.write(&T::OPCODE);
        w.write_u8(0);
        w.write(command);

        let mut vec = w.into_vec();
        vec[2] = (vec.len() - 3).try_into().unwrap();
        vec
    }
}

/// OpCode of HCI Command, as defined in Part E - 5.4.1
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct OpCode(u16);

impl OpCode {
    /// OpCode from OpCode Group Field (OGF) and OpCode Command Field (OCF).
    pub const fn from(ogf: u16, ocf: u16) -> Self {
        Self(pack!((ocf, 10), (ogf, 6)))
    }
}

impl From<u16> for OpCode {
    fn from(v: u16) -> Self {
        OpCode(v)
    }
}

impl Read for OpCode {
    fn read(r: &mut Reader) -> Option<Self> {
        Some(r.read_u16()?.into())
    }
}

impl Write for OpCode {
    fn write(&self, w: &mut Writer) {
        w.write_u16(self.0)
    }
}

/// Define command OpCode
pub trait CommandOpCode {
    /// OpCode of the command
    const OPCODE: OpCode;
}

/// Build command from definition
pub trait CommandToBytes: CommandOpCode + Sized + Write {
    /// Output the HCI Command packet
    fn to_bytes(&self) -> Vec<u8>;
}

use crate::derive::CommandToBytes;
use crate::status::*;

#[cfg(test)]
use crate::{Event, EventToBytes};

// 7.3.2 Reset Command

impl CommandOpCode for Reset {
    const OPCODE: OpCode = OpCode::from(0x03, 0x003);
}

#[derive(Debug, Read, Write, CommandToBytes)]
pub struct Reset {}

#[derive(Debug, Read, Write)]
pub struct ResetComplete {
    pub status: Status,
}

#[test]
fn test_reset() {
    let dump = [0x03, 0x0c, 0x00];
    let Ok(Command::Reset(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.to_bytes(), &dump[..]);
}

#[test]
fn test_reset_complete() {
    let dump = [0x0e, 0x04, 0x01, 0x03, 0x0c, 0x00];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::Reset(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.4.5 Read Buffer Size

impl CommandOpCode for ReadBufferSize {
    const OPCODE: OpCode = OpCode::from(0x04, 0x0005);
}

#[derive(Debug)]
pub struct ReadBufferSize;

#[derive(Debug, Read, Write)]
pub struct ReadBufferSizeComplete {
    pub status: Status,
    pub acl_data_packet_length: u16,
    pub synchronous_data_packet_length: u8,
    pub total_num_acl_data_packets: u16,
    pub total_num_synchronous_data_packets: u16,
}

#[test]
fn test_read_buffer_size_complete() {
    let dump = [0x0e, 0x0b, 0x01, 0x05, 0x10, 0x00, 0xff, 0x03, 0xff, 0x0a, 0x00, 0x0a, 0x00];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::ReadBufferSize(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.acl_data_packet_length, 1023);
    assert_eq!(p.synchronous_data_packet_length, 255);
    assert_eq!(p.total_num_acl_data_packets, 10);
    assert_eq!(p.total_num_synchronous_data_packets, 10);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.8.2 LE Read Buffer Size

impl CommandOpCode for LeReadBufferSizeV1 {
    const OPCODE: OpCode = OpCode::from(0x08, 0x002);
}

#[derive(Debug)]
pub struct LeReadBufferSizeV1;

#[derive(Debug, Read, Write)]
pub struct LeReadBufferSizeV1Complete {
    pub status: Status,
    pub le_acl_data_packet_length: u16,
    pub total_num_le_acl_data_packets: u8,
}

#[test]
fn test_le_read_buffer_size_v1_complete() {
    let dump = [0x0e, 0x07, 0x01, 0x02, 0x20, 0x00, 0xfb, 0x00, 0x0f];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::LeReadBufferSizeV1(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.le_acl_data_packet_length, 251);
    assert_eq!(p.total_num_le_acl_data_packets, 15);
    assert_eq!(e.to_bytes(), &dump[..]);
}

impl CommandOpCode for LeReadBufferSizeV2 {
    const OPCODE: OpCode = OpCode::from(0x08, 0x060);
}

#[derive(Debug)]
pub struct LeReadBufferSizeV2;

#[derive(Debug, Read, Write)]
pub struct LeReadBufferSizeV2Complete {
    pub status: Status,
    pub le_acl_data_packet_length: u16,
    pub total_num_le_acl_data_packets: u8,
    pub iso_data_packet_length: u16,
    pub total_num_iso_data_packets: u8,
}

#[test]
fn test_le_read_buffer_size_v2_complete() {
    let dump = [0x0e, 0x0a, 0x01, 0x60, 0x20, 0x00, 0xfb, 0x00, 0x0f, 0xfd, 0x03, 0x18];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::LeReadBufferSizeV2(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.le_acl_data_packet_length, 251);
    assert_eq!(p.total_num_le_acl_data_packets, 15);
    assert_eq!(p.iso_data_packet_length, 1021);
    assert_eq!(p.total_num_iso_data_packets, 24);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.8.97 LE Set CIG Parameters

impl CommandOpCode for LeSetCigParameters {
    const OPCODE: OpCode = OpCode::from(0x08, 0x062);
}

#[derive(Debug, Read, Write, CommandToBytes)]
#[rustfmt::skip]
pub struct LeSetCigParameters {
    pub cig_id: u8,
    #[N(3)] pub sdu_interval_c_to_p: u32,
    #[N(3)] pub sdu_interval_p_to_c: u32,
    pub worst_case_sca: u8,
    pub packing: u8,
    pub framing: u8,
    pub max_transport_latency_c_to_p: u16,
    pub max_transport_latency_p_to_c: u16,
    pub cis: Vec<LeCisInCigParameters>,
}

#[derive(Debug, Read, Write)]
pub struct LeCisInCigParameters {
    pub cis_id: u8,
    pub max_sdu_c_to_p: u16,
    pub max_sdu_p_to_c: u16,
    pub phy_c_to_p: u8,
    pub phy_p_to_c: u8,
    pub rtn_c_to_p: u8,
    pub rtn_p_to_c: u8,
}

#[test]
fn test_le_set_cig_parameters() {
    let dump = [
        0x62, 0x20, 0x21, 0x01, 0x10, 0x27, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x64, 0x00,
        0x05, 0x00, 0x02, 0x00, 0x78, 0x00, 0x00, 0x00, 0x02, 0x03, 0x0d, 0x00, 0x01, 0x78, 0x00,
        0x00, 0x00, 0x02, 0x03, 0x0d, 0x00,
    ];
    let Ok(Command::LeSetCigParameters(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.cig_id, 0x01);
    assert_eq!(c.sdu_interval_c_to_p, 10_000);
    assert_eq!(c.sdu_interval_p_to_c, 0);
    assert_eq!(c.worst_case_sca, 1);
    assert_eq!(c.packing, 0);
    assert_eq!(c.framing, 0);
    assert_eq!(c.max_transport_latency_c_to_p, 100);
    assert_eq!(c.max_transport_latency_p_to_c, 5);
    assert_eq!(c.cis.len(), 2);
    assert_eq!(c.cis[0].cis_id, 0);
    assert_eq!(c.cis[0].max_sdu_c_to_p, 120);
    assert_eq!(c.cis[0].max_sdu_p_to_c, 0);
    assert_eq!(c.cis[0].phy_c_to_p, 0x02);
    assert_eq!(c.cis[0].phy_p_to_c, 0x03);
    assert_eq!(c.cis[0].rtn_c_to_p, 13);
    assert_eq!(c.cis[0].rtn_p_to_c, 0);
    assert_eq!(c.cis[1].cis_id, 1);
    assert_eq!(c.cis[1].max_sdu_c_to_p, 120);
    assert_eq!(c.cis[1].max_sdu_p_to_c, 0);
    assert_eq!(c.cis[1].phy_c_to_p, 0x02);
    assert_eq!(c.cis[1].phy_p_to_c, 0x03);
    assert_eq!(c.cis[1].rtn_c_to_p, 13);
    assert_eq!(c.cis[1].rtn_p_to_c, 0);
    assert_eq!(c.to_bytes(), &dump[..]);
}

#[derive(Debug, Read, Write)]
pub struct LeSetCigParametersComplete {
    pub status: Status,
    pub cig_id: u8,
    pub connection_handles: Vec<u16>,
}

#[test]
fn test_le_set_cig_parameters_complete() {
    let dump = [0x0e, 0x0a, 0x01, 0x62, 0x20, 0x00, 0x01, 0x02, 0x60, 0x00, 0x61, 0x00];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::LeSetCigParameters(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.cig_id, 1);
    assert_eq!(p.connection_handles.len(), 2);
    assert_eq!(p.connection_handles[0], 0x60);
    assert_eq!(p.connection_handles[1], 0x61);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.8.99 LE Create CIS

impl CommandOpCode for LeCreateCis {
    const OPCODE: OpCode = OpCode::from(0x08, 0x064);
}

#[derive(Debug, Read, Write, CommandToBytes)]
pub struct LeCreateCis {
    pub connection_handles: Vec<CisAclConnectionHandle>,
}

#[derive(Debug, Read, Write)]
pub struct CisAclConnectionHandle {
    pub cis: u16,
    pub acl: u16,
}

#[test]
fn test_le_create_cis() {
    let dump = [0x64, 0x20, 0x09, 0x02, 0x60, 0x00, 0x40, 0x00, 0x61, 0x00, 0x41, 0x00];
    let Ok(Command::LeCreateCis(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.connection_handles.len(), 2);
    assert_eq!(c.connection_handles[0].cis, 0x60);
    assert_eq!(c.connection_handles[0].acl, 0x40);
    assert_eq!(c.connection_handles[1].cis, 0x61);
    assert_eq!(c.connection_handles[1].acl, 0x41);
    assert_eq!(c.to_bytes(), &dump[..]);
}

// 7.8.100 LE Remove CIG

impl CommandOpCode for LeRemoveCig {
    const OPCODE: OpCode = OpCode::from(0x08, 0x065);
}

#[derive(Debug, Read, Write, CommandToBytes)]
pub struct LeRemoveCig {
    pub cig_id: u8,
}

#[derive(Debug, Read, Write)]
pub struct LeRemoveCigComplete {
    pub status: Status,
    pub cig_id: u8,
}

#[test]
fn test_le_remove_cig() {
    let dump = [0x65, 0x20, 0x01, 0x01];
    let Ok(Command::LeRemoveCig(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.cig_id, 0x01);
    assert_eq!(c.to_bytes(), &dump[..]);
}

#[test]
fn test_le_remove_cig_complete() {
    let dump = [0x0e, 0x05, 0x01, 0x65, 0x20, 0x00, 0x01];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::LeRemoveCig(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.cig_id, 0x01);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.8.103 LE Create BIG

impl CommandOpCode for LeCreateBig {
    const OPCODE: OpCode = OpCode::from(0x08, 0x068);
}

#[derive(Debug, Read, Write, CommandToBytes)]
#[rustfmt::skip]
pub struct LeCreateBig {
    pub big_handle: u8,
    pub advertising_handle: u8,
    pub num_bis: u8,
    #[N(3)] pub sdu_interval: u32,
    pub max_sdu: u16,
    pub max_transport_latency: u16,
    pub rtn: u8,
    pub phy: u8,
    pub packing: u8,
    pub framing: u8,
    pub encryption: u8,
    pub broadcast_code: [u8; 16],
}

#[test]
fn test_le_create_big() {
    let dump = [
        0x68, 0x20, 0x1f, 0x00, 0x00, 0x02, 0x10, 0x27, 0x00, 0x78, 0x00, 0x3c, 0x00, 0x04, 0x02,
        0x00, 0x00, 0x01, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34,
        0x31, 0x32, 0x33, 0x34,
    ];
    let Ok(Command::LeCreateBig(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.big_handle, 0x00);
    assert_eq!(c.advertising_handle, 0x00);
    assert_eq!(c.num_bis, 2);
    assert_eq!(c.sdu_interval, 10_000);
    assert_eq!(c.max_sdu, 120);
    assert_eq!(c.max_transport_latency, 60);
    assert_eq!(c.rtn, 4);
    assert_eq!(c.phy, 0x02);
    assert_eq!(c.packing, 0x00);
    assert_eq!(c.framing, 0x00);
    assert_eq!(c.encryption, 1);
    assert_eq!(
        c.broadcast_code,
        [
            0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32,
            0x33, 0x34
        ]
    );
    assert_eq!(c.to_bytes(), &dump[..]);
}

// 7.8.109 LE Setup ISO Data Path

impl CommandOpCode for LeSetupIsoDataPath {
    const OPCODE: OpCode = OpCode::from(0x08, 0x06e);
}

#[derive(Clone, Debug, Read, Write, CommandToBytes)]
#[rustfmt::skip]
pub struct LeSetupIsoDataPath {
    pub connection_handle: u16,
    pub data_path_direction: LeDataPathDirection,
    pub data_path_id: u8,
    pub codec_id: LeCodecId,
    #[N(3)] pub controller_delay: u32,
    pub codec_configuration: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Read, Write)]
pub enum LeDataPathDirection {
    Input = 0x00,
    Output = 0x01,
}

#[derive(Clone, Debug, Read, Write)]
pub struct LeCodecId {
    pub coding_format: CodingFormat,
    pub company_id: u16,
    pub vendor_id: u16,
}

#[derive(Clone, Debug, PartialEq, Read, Write)]
pub enum CodingFormat {
    ULawLog = 0x00,
    ALawLog = 0x01,
    Cvsd = 0x02,
    Transparent = 0x03,
    LinearPcm = 0x04,
    MSbc = 0x05,
    Lc3 = 0x06,
    G729A = 0x07,
    VendorSpecific = 0xff,
}

#[derive(Debug, Read, Write)]
pub struct LeIsoDataPathComplete {
    pub status: Status,
    pub connection_handle: u16,
}

#[test]
fn test_le_setup_iso_data_path() {
    let dump = [
        0x6e, 0x20, 0x0d, 0x60, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00,
    ];
    let Ok(Command::LeSetupIsoDataPath(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.connection_handle, 0x60);
    assert_eq!(c.data_path_direction, LeDataPathDirection::Input);
    assert_eq!(c.data_path_id, 0x00);
    assert_eq!(c.codec_id.coding_format, CodingFormat::Transparent);
    assert_eq!(c.codec_id.company_id, 0);
    assert_eq!(c.codec_id.vendor_id, 0);
    assert_eq!(c.controller_delay, 0);
    assert_eq!(c.codec_configuration.len(), 0);
    assert_eq!(c.to_bytes(), &dump[..]);
}

#[test]
fn test_le_setup_iso_data_path_complete() {
    let dump = [0x0e, 0x06, 0x01, 0x6e, 0x20, 0x00, 0x60, 0x00];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::LeSetupIsoDataPath(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.connection_handle, 0x60);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.8.110 LE Remove ISO Data Path

impl CommandOpCode for LeRemoveIsoDataPath {
    const OPCODE: OpCode = OpCode::from(0x08, 0x06f);
}

#[derive(Debug, Read, Write, CommandToBytes)]
pub struct LeRemoveIsoDataPath {
    pub connection_handle: u16,
    pub data_path_direction: u8,
}

#[test]
fn test_le_remove_iso_data_path() {
    let dump = [0x6f, 0x20, 0x03, 0x60, 0x00, 0x01];
    let Ok(Command::LeRemoveIsoDataPath(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.connection_handle, 0x60);
    assert_eq!(c.data_path_direction, 0x01);
    assert_eq!(c.to_bytes(), &dump[..]);
}

// Vendor-specific LE Get Vendor Capabilities

impl CommandOpCode for LeGetVendorCapabilities {
    const OPCODE: OpCode = OpCode::from(0x3f, 0x153);
}

#[derive(Debug, Read, Write, CommandToBytes)]
pub struct LeGetVendorCapabilities {}

#[derive(Clone, Debug)]
pub struct LeGetVendorCapabilitiesComplete {
    pub status: Status,
    pub max_advt_instances: u8,
    pub offloaded_resolution_of_private_address: u8,
    pub total_scan_results_storage: u16,
    pub max_irk_list_sz: u8,
    pub filtering_support: u8,
    pub max_filter: u8,
    pub activity_energy_info_support: u8,
    pub version_supported: Option<u16>,
    pub total_num_of_advt_tracked: Option<u16>,
    pub extended_scan_support: Option<u8>,
    pub debug_logging_supported: Option<u8>,
    pub le_address_generation_offloading_support: Option<u8>,
    pub a2dp_source_offload_capability_mask: Option<u32>,
    pub bluetooth_quality_report_support: Option<u8>,
    pub dynamic_audio_buffer_support: Option<u32>,
    pub a2dp_offload_v2_support: Option<u8>,
    pub iso_link_feedback_support: Option<u8>,
    pub sniff_offload_support: Option<u8>,
    pub big_set_channel_map_classification_support: Option<u16>,
    pub vendor_connection_handle_min: Option<u16>,
    pub vendor_connection_handle_max: Option<u16>,
}

impl Read for LeGetVendorCapabilitiesComplete {
    fn read(r: &mut Reader) -> Option<Self> {
        Some(Self {
            status: r.read()?,
            max_advt_instances: r.read_u8()?,
            offloaded_resolution_of_private_address: r.read_u8()?,
            total_scan_results_storage: r.read_u16()?,
            max_irk_list_sz: r.read_u8()?,
            filtering_support: r.read_u8()?,
            max_filter: r.read_u8()?,
            activity_energy_info_support: r.read_u8()?,
            version_supported: r.read_u16(),
            total_num_of_advt_tracked: r.read_u16(),
            extended_scan_support: r.read_u8(),
            debug_logging_supported: r.read_u8(),
            le_address_generation_offloading_support: r.read_u8(),
            a2dp_source_offload_capability_mask: r.read_u32::<4>(),
            bluetooth_quality_report_support: r.read_u8(),
            dynamic_audio_buffer_support: r.read_u32::<4>(),
            a2dp_offload_v2_support: r.read_u8(),
            iso_link_feedback_support: r.read_u8(),
            sniff_offload_support: r.read_u8(),
            big_set_channel_map_classification_support: r.read_u16(),
            vendor_connection_handle_min: r.read_u16(),
            vendor_connection_handle_max: r.read_u16(),
        })
    }
}

impl Write for LeGetVendorCapabilitiesComplete {
    fn write(&self, w: &mut Writer) {
        w.write(&self.status);
        w.write_u8(self.max_advt_instances);
        w.write_u8(self.offloaded_resolution_of_private_address);
        w.write_u16(self.total_scan_results_storage);
        w.write_u8(self.max_irk_list_sz);
        w.write_u8(self.filtering_support);
        w.write_u8(self.max_filter);
        w.write_u8(self.activity_energy_info_support);
        w.write_u16(self.version_supported.unwrap_or(0));
        w.write_u16(self.total_num_of_advt_tracked.unwrap_or(0));
        w.write_u8(self.extended_scan_support.unwrap_or(0));
        w.write_u8(self.debug_logging_supported.unwrap_or(0));
        w.write_u8(self.le_address_generation_offloading_support.unwrap_or(0));
        w.write_u32::<4>(self.a2dp_source_offload_capability_mask.unwrap_or(0));
        w.write_u8(self.bluetooth_quality_report_support.unwrap_or(0));
        w.write_u32::<4>(self.dynamic_audio_buffer_support.unwrap_or(0));
        w.write_u8(self.a2dp_offload_v2_support.unwrap_or(0));
        w.write_u8(self.iso_link_feedback_support.unwrap_or(0));
        w.write_u8(self.sniff_offload_support.unwrap_or(0));
        w.write_u16(self.big_set_channel_map_classification_support.unwrap_or(0));
        w.write_u16(self.vendor_connection_handle_min.unwrap_or(0));
        w.write_u16(self.vendor_connection_handle_max.unwrap_or(0));
    }
}

#[test]
fn test_le_get_vendor_capabilities() {
    let dump = [0x53, 0xfd, 0x00];
    let Ok(Command::LeGetVendorCapabilities(c)) = Command::from_bytes(&dump) else { panic!() };
    assert_eq!(c.to_bytes(), &dump[..]);
}

#[test]
fn test_le_get_vendor_capabilities_complete() {
    let dump = [
        0xe,  /* command complete */
        0x25, /* len */
        0x1,  /* num_hci_command_packets */
        0x53, 0xfd, /* opcode */
        0x0,  /* status */
        0x1,  /* max_advt_instances */
        0x1,  /* offloaded_resolution_of_private_address */
        0x0, 0x0,  /* total_scan_results_storage */
        0x10, /* max_irk_list_sz */
        0x1,  /* filtering_support */
        0x10, /* max_filter */
        0x0,  /* activity_energy_info_support */
        0x6, 0x1, /* version_supported */
        0x10, 0x0, /* total_num_of_advt_tracked */
        0x0, /* extended_scan_support */
        0x0, /* debug_logging_supported */
        0x0, /* le_address_generation_offloading_support */
        0x0, 0x0, 0x0, 0x0, /* a2dp_source_offload_capability_mask */
        0x1, /* bluetooth_quality_report_support */
        0x0, 0x0, 0x0, 0x0, /* dynamic_audio_buffer_support */
        0x1, /* a2dp_offload_v2_support */
        0x0, /* iso_link_feedback_support */
        0x0, /* sniff_offload_support */
        0x0, 0x0, /* big_set_channel_map_classification_support */
        0x0, 0x3, /* vendor_connection_handle_min */
        0xff, 0x3, /* vendor_connection_handle_max */
    ];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::LeGetVendorCapabilities(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.max_advt_instances, 1);
    assert_eq!(p.offloaded_resolution_of_private_address, 1);
    assert_eq!(p.total_scan_results_storage, 0);
    assert_eq!(p.max_irk_list_sz, 16);
    assert_eq!(p.filtering_support, 1);
    assert_eq!(p.max_filter, 16);
    assert_eq!(p.activity_energy_info_support, 0);
    assert_eq!(p.version_supported, Some(0x0106));
    assert_eq!(p.total_num_of_advt_tracked, Some(16));
    assert_eq!(p.extended_scan_support, Some(0));
    assert_eq!(p.debug_logging_supported, Some(0));
    assert_eq!(p.le_address_generation_offloading_support, Some(0));
    assert_eq!(p.a2dp_source_offload_capability_mask, Some(0x00000000));
    assert_eq!(p.bluetooth_quality_report_support, Some(1));
    assert_eq!(p.dynamic_audio_buffer_support, Some(0x00000000));
    assert_eq!(p.a2dp_offload_v2_support, Some(1));
    assert_eq!(p.iso_link_feedback_support, Some(0));
    assert_eq!(p.sniff_offload_support, Some(0));
    assert_eq!(p.big_set_channel_map_classification_support, Some(0x0000));
    assert_eq!(p.vendor_connection_handle_min, Some(0x0300));
    assert_eq!(p.vendor_connection_handle_max, Some(0x03ff));
    assert_eq!(e.to_bytes(), &dump[..]);
}

// A2dp Hardware Offload

#[derive(Debug, CommandToBytes)]
pub enum A2dpHardwareOffload {
    StartA2dpOffload(StartA2dpOffload),
    StopA2dpOffload(StopA2dpOffload),
    Unknown(u8),
}

#[derive(Debug, Read, Write)]
pub struct A2dpHardwareOffloadComplete {
    pub status: Status,
    pub sub_opcode: u8,
}

impl A2dpHardwareOffloadComplete {
    /// Creates a new `A2dpHardwareOffloadComplete` event.
    pub fn new(status: Status, sub_opcode: u8) -> Self {
        Self { status, sub_opcode }
    }
}

impl CommandOpCode for A2dpHardwareOffload {
    const OPCODE: OpCode = OpCode::from(0x3f, 0x15d);
}

impl Read for A2dpHardwareOffload {
    fn read(r: &mut Reader) -> Option<Self> {
        let sub_opcode = r.read_u8()?;
        Some(match sub_opcode {
            StartA2dpOffload::OPCODE => Self::StartA2dpOffload(r.read()?),
            StopA2dpOffload::OPCODE => Self::StopA2dpOffload(r.read()?),
            _ => Self::Unknown(sub_opcode),
        })
    }
}

impl Write for A2dpHardwareOffload {
    fn write(&self, w: &mut Writer) {
        match self {
            A2dpHardwareOffload::StartA2dpOffload(c) => {
                w.write_u8(StartA2dpOffload::OPCODE);
                w.write(c);
            }
            A2dpHardwareOffload::StopA2dpOffload(c) => {
                w.write_u8(StopA2dpOffload::OPCODE);
                w.write(c);
            }
            A2dpHardwareOffload::Unknown(opc) => {
                w.write_u8(*opc);
            }
        }
    }
}

#[derive(Debug, Read, Write)]
pub struct StartA2dpOffload {
    pub connection_handle: u16,
    pub l2cap_channel_id: u16,
    pub data_path_direction: u8,
    pub peer_mtu: u16,
    pub cp_enable_scms_t: u8,
    pub cp_header_scms_t: u8,
    pub vendor_specific_parameters: Vec<u8>,
}

impl StartA2dpOffload {
    const OPCODE: u8 = 0x03;

    /// Returns the opcode of the command.
    pub fn opcode(&self) -> u8 {
        Self::OPCODE
    }
}

#[derive(Debug, Read, Write)]
pub struct StopA2dpOffload {
    pub connection_handle: u16,
    pub l2cap_channel_id: u16,
    pub data_path_direction: u8,
}

impl StopA2dpOffload {
    const OPCODE: u8 = 0x04;

    /// Returns the opcode of the command.
    pub fn opcode(&self) -> u8 {
        Self::OPCODE
    }
}

#[test]
fn test_start_a2dp_offload() {
    let dump = [
        0x5d, 0xfd, 0x0e, 0x03, 0x23, 0x01, 0x67, 0x45, 0x00, 0x00, 0x01, 0x00, 0x42, 0x03, 0x01,
        0x02, 0x03,
    ];
    let Ok(Command::A2dpHardwareOffload(A2dpHardwareOffload::StartA2dpOffload(c))) =
        Command::from_bytes(&dump)
    else {
        panic!()
    };
    assert_eq!(c.connection_handle, 0x0123);
    assert_eq!(c.l2cap_channel_id, 0x4567);
    assert_eq!(c.data_path_direction, 0);
    assert_eq!(c.peer_mtu, 0x0100);
    assert_eq!(c.cp_enable_scms_t, 0);
    assert_eq!(c.cp_header_scms_t, 0x42);
    assert_eq!(c.vendor_specific_parameters, &[0x01, 0x02, 0x03]);
    assert_eq!(A2dpHardwareOffload::StartA2dpOffload(c).to_bytes(), &dump[..]);
}

#[test]
fn test_start_a2dp_complete() {
    let dump = [0x0e, 0x05, 0x01, 0x5d, 0xfd, 0x00, 0x03];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::A2dpHardwareOffload(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.sub_opcode, StartA2dpOffload::OPCODE);
    assert_eq!(e.to_bytes(), &dump[..]);
}

#[test]
fn test_stop_a2dp_offload() {
    let dump = [0x5d, 0xfd, 0x06, 0x04, 0x23, 0x01, 0x67, 0x45, 0x00];
    let Ok(Command::A2dpHardwareOffload(A2dpHardwareOffload::StopA2dpOffload(c))) =
        Command::from_bytes(&dump)
    else {
        panic!()
    };
    assert_eq!(c.connection_handle, 0x0123);
    assert_eq!(c.l2cap_channel_id, 0x4567);
    assert_eq!(c.data_path_direction, 0);
    assert_eq!(A2dpHardwareOffload::StopA2dpOffload(c).to_bytes(), &dump[..]);
}

#[test]
fn test_stop_a2dp_complete() {
    let dump = [0x0e, 0x05, 0x01, 0x5d, 0xfd, 0x00, 0x04];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    let ReturnParameters::A2dpHardwareOffload(ref p) = e.return_parameters else { panic!() };
    assert_eq!(p.status, Status::Success);
    assert_eq!(p.sub_opcode, StopA2dpOffload::OPCODE);
    assert_eq!(e.to_bytes(), &dump[..]);
}
