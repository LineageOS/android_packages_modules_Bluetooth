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
    /// Unknown command
    Unknown(OpCode),
}

/// HCI Command Return Parameters
#[derive(Debug, Read, Write)]
pub enum ReturnParameters {
    /// 7.3.2 Reset Command
    Reset(ResetComplete),
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
pub trait CommandToBytes: CommandOpCode + Write {
    /// Output the HCI Command packet
    fn to_bytes(&self) -> Vec<u8>
    where
        Self: Sized + CommandOpCode + Write;
}

pub use defs::*;

#[allow(missing_docs)]
#[rustfmt::skip]
mod defs {

use super::*;
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
        0x62, 0x20, 0x21, 0x01, 0x10, 0x27, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x64, 0x00, 0x05,
        0x00, 0x02, 0x00, 0x78, 0x00, 0x00, 0x00, 0x02, 0x03, 0x0d, 0x00, 0x01, 0x78, 0x00, 0x00, 0x00,
        0x02, 0x03, 0x0d, 0x00
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
fn test_le_create_cis () {
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
        0x68, 0x20, 0x1f, 0x00, 0x00, 0x02, 0x10, 0x27, 0x00, 0x78, 0x00, 0x3c, 0x00, 0x04, 0x02, 0x00,
        0x00, 0x01, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34, 0x31, 0x32,
        0x33, 0x34
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
    assert_eq!(c.broadcast_code, [
        0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34,
        0x31, 0x32, 0x33, 0x34, 0x31, 0x32, 0x33, 0x34
    ]);
    assert_eq!(c.to_bytes(), &dump[..]);
}


// 7.8.109 LE Setup ISO Data Path

impl CommandOpCode for LeSetupIsoDataPath {
    const OPCODE: OpCode = OpCode::from(0x08, 0x06e);
}

#[derive(Clone, Debug, Read, Write, CommandToBytes)]
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
        0x6e, 0x20, 0x0d, 0x60, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
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

}
