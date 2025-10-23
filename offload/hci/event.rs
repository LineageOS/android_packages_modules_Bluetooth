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

use crate::reader::{Read, Reader};
use crate::writer::{Write, Writer};
use crate::BluetoothAddress;

/// HCI Event Packet, as defined in Part E - 5.4.4
#[derive(Debug)]
pub enum Event {
    /// 7.7.3  Connection Complete
    ConnectionComplete(ConnectionComplete),
    /// 7.7.5   Disconnection Complete
    DisconnectionComplete(DisconnectionComplete),
    /// 7.7.14  Command Complete
    CommandComplete(CommandComplete),
    /// 7.7.15  Command Status
    CommandStatus(CommandStatus),
    /// 7.7.19  Number Of Completed Packets
    NumberOfCompletedPackets(NumberOfCompletedPackets),
    /// 7.7.65.25  LE CIS Established
    LeCisEstablished(LeCisEstablished),
    /// 7.7.65.27  LE Create BIG Complete
    LeCreateBigComplete(LeCreateBigComplete),
    /// 7.7.65.28  LE Terminate BIG Complete
    LeTerminateBigComplete(LeTerminateBigComplete),
    /// Vendor-specific LE ISO Link Feedback
    LeIsoLinkFeedback(LeIsoLinkFeedback),
    /// Unknown Event
    Unknown(Code),
}

impl Event {
    /// Read an HCI Event packet
    pub fn from_bytes(data: &[u8]) -> Result<Self, Option<Code>> {
        fn parse_packet(data: &[u8]) -> Option<(Code, Reader)> {
            let mut r = Reader::new(data);
            let code = r.read_u8()?;
            let len = r.read_u8()? as usize;

            let mut r = Reader::new(r.get(len)?);
            let code = match code {
                Code::LE_META => Code(Code::LE_META, Some(r.read_u8()?)),
                Code::VS_META => Code(Code::VS_META, Some(r.read_u8()?)),
                _ => Code(code, None),
            };

            Some((code, r))
        }

        let Some((code, mut r)) = parse_packet(data) else {
            return Err(None);
        };
        Self::dispatch_read(code, &mut r).ok_or(Some(code))
    }

    fn dispatch_read(code: Code, r: &mut Reader) -> Option<Event> {
        Some(match code {
            CommandComplete::CODE => Self::CommandComplete(r.read()?),
            CommandStatus::CODE => Self::CommandStatus(r.read()?),
            ConnectionComplete::CODE => Self::ConnectionComplete(r.read()?),
            DisconnectionComplete::CODE => Self::DisconnectionComplete(r.read()?),
            NumberOfCompletedPackets::CODE => Self::NumberOfCompletedPackets(r.read()?),
            LeCisEstablished::CODE => Self::LeCisEstablished(r.read()?),
            LeCreateBigComplete::CODE => Self::LeCreateBigComplete(r.read()?),
            LeTerminateBigComplete::CODE => Self::LeTerminateBigComplete(r.read()?),
            LeIsoLinkFeedback::CODE => Self::LeIsoLinkFeedback(r.read()?),
            code => Self::Unknown(code),
        })
    }

    fn to_bytes<T: EventCode + Write>(event: &T) -> Vec<u8> {
        let mut w = Writer::new(Vec::with_capacity(2 + 255));
        w.write_u8(T::CODE.0);
        w.write_u8(0);
        if let Some(sub_code) = T::CODE.1 {
            w.write_u8(sub_code)
        }
        w.write(event);

        let mut vec = w.into_vec();
        vec[1] = (vec.len() - 2).try_into().unwrap();
        vec
    }
}

/// Code of HCI Event, as defined in Part E - 5.4.4
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Code(u8, Option<u8>);

impl Code {
    const LE_META: u8 = 0x3e;
    const VS_META: u8 = 0xff;
}

/// Define event Code
pub trait EventCode {
    /// Code of the event
    const CODE: Code;
}

/// Build event from definition
pub trait EventToBytes: EventCode + Write {
    /// Output the HCI Event packet
    fn to_bytes(&self) -> Vec<u8>
    where
        Self: Sized + EventCode + Write;
}

use crate::command::{OpCode, ReturnParameters};
use crate::derive::{EventToBytes, Read, Write};
use crate::status::Status;

// 7.7.3 Connection Complete
impl EventCode for ConnectionComplete {
    const CODE: Code = Code(0x03, None);
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct ConnectionComplete {
    pub status: Status,
    pub connection_handle: u16,
    pub bd_addr: BluetoothAddress,
    pub link_type: u8,
    pub encryption_enabled: u8,
}

#[test]
fn test_connection_complete() {
    let dump = [0x03, 0x0B, 0x00, 0x60, 0x00, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00, 0x01, 0x00];
    let Ok(Event::ConnectionComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.status, Status::Success);
    assert_eq!(e.connection_handle, 0x60);
    assert_eq!(e.bd_addr, BluetoothAddress::from_be_bytes([0x00, 0x11, 0x22, 0x33, 0x44, 0x55]));
    assert_eq!(e.link_type, 0x01);
    assert_eq!(e.encryption_enabled, 0x00);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.5 Disconnection Complete

impl EventCode for DisconnectionComplete {
    const CODE: Code = Code(0x05, None);
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct DisconnectionComplete {
    pub status: Status,
    pub connection_handle: u16,
    pub reason: u8,
}

#[test]
fn test_disconnection_complete() {
    let dump = [0x05, 0x04, 0x00, 0x60, 0x00, 0x16];
    let Ok(Event::DisconnectionComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.status, Status::Success);
    assert_eq!(e.connection_handle, 0x60);
    assert_eq!(e.reason, 0x16);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.14 Command Complete

impl EventCode for CommandComplete {
    const CODE: Code = Code(0x0e, None);
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct CommandComplete {
    pub num_hci_command_packets: u8,
    pub return_parameters: ReturnParameters,
}

#[test]
fn test_command_complete() {
    let dump = [0x0e, 0x04, 0x01, 0x03, 0x0c, 0x00];
    let Ok(Event::CommandComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.num_hci_command_packets, 1);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.15 Command Status

impl EventCode for CommandStatus {
    const CODE: Code = Code(0x0f, None);
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct CommandStatus {
    pub status: Status,
    pub num_hci_command_packets: u8,
    pub opcode: OpCode,
}

#[test]
fn test_command_status() {
    let dump = [0x0f, 0x04, 0x00, 0x01, 0x01, 0x04];
    let Ok(Event::CommandStatus(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.status, Status::Success);
    assert_eq!(e.num_hci_command_packets, 1);
    assert_eq!(e.opcode, OpCode::from(0x01, 0x001));
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.19 Number Of Completed Packets

impl EventCode for NumberOfCompletedPackets {
    const CODE: Code = Code(0x13, None);
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct NumberOfCompletedPackets {
    pub handles: Vec<NumberOfCompletedPacketsHandle>,
}

#[derive(Debug, Copy, Clone, Read, Write)]
pub struct NumberOfCompletedPacketsHandle {
    pub connection_handle: u16,
    pub num_completed_packets: u16,
}

#[test]
fn test_number_of_completed_packets() {
    let dump = [0x13, 0x09, 0x02, 0x40, 0x00, 0x01, 0x00, 0x41, 0x00, 0x01, 0x00];
    let Ok(Event::NumberOfCompletedPackets(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.handles.len(), 2);
    assert_eq!(e.handles[0].connection_handle, 0x40);
    assert_eq!(e.handles[0].num_completed_packets, 1);
    assert_eq!(e.handles[1].connection_handle, 0x41);
    assert_eq!(e.handles[1].num_completed_packets, 1);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.65.25 LE CIS Established

impl EventCode for LeCisEstablished {
    const CODE: Code = Code(Code::LE_META, Some(0x19));
}

#[derive(Debug, Read, Write, EventToBytes)]
#[rustfmt::skip]
pub struct LeCisEstablished {
    pub status: Status,
    pub connection_handle: u16,
    #[N(3)] pub cig_sync_delay: u32,
    #[N(3)] pub cis_sync_delay: u32,
    #[N(3)] pub transport_latency_c_to_p: u32,
    #[N(3)] pub transport_latency_p_to_c: u32,
    pub phy_c_to_p: u8,
    pub phy_p_to_c: u8,
    pub nse: u8,
    pub bn_c_to_p: u8,
    pub bn_p_to_c: u8,
    pub ft_c_to_p: u8,
    pub ft_p_to_c: u8,
    pub max_pdu_c_to_p: u16,
    pub max_pdu_p_to_c: u16,
    pub iso_interval: u16,
}

#[test]
fn test_le_cis_established() {
    let dump = [
        0x3e, 0x1d, 0x19, 0x00, 0x60, 0x00, 0x40, 0x2c, 0x00, 0x40, 0x2c, 0x00, 0xd0, 0x8b, 0x01,
        0x60, 0x7a, 0x00, 0x02, 0x02, 0x06, 0x02, 0x00, 0x05, 0x01, 0x78, 0x00, 0x00, 0x00, 0x10,
        0x00,
    ];
    let Ok(Event::LeCisEstablished(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.status, Status::Success);
    assert_eq!(e.connection_handle, 0x60);
    assert_eq!(e.cig_sync_delay, 11_328);
    assert_eq!(e.cis_sync_delay, 11_328);
    assert_eq!(e.transport_latency_c_to_p, 101_328);
    assert_eq!(e.transport_latency_p_to_c, 31_328);
    assert_eq!(e.phy_c_to_p, 0x02);
    assert_eq!(e.phy_p_to_c, 0x02);
    assert_eq!(e.nse, 6);
    assert_eq!(e.bn_c_to_p, 2);
    assert_eq!(e.bn_p_to_c, 0);
    assert_eq!(e.ft_c_to_p, 5);
    assert_eq!(e.ft_p_to_c, 1);
    assert_eq!(e.max_pdu_c_to_p, 120);
    assert_eq!(e.max_pdu_p_to_c, 0);
    assert_eq!(e.iso_interval, 16);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.65.27 LE Create BIG Complete

impl EventCode for LeCreateBigComplete {
    const CODE: Code = Code(Code::LE_META, Some(0x1b));
}

#[derive(Debug, Read, Write, EventToBytes)]
#[rustfmt::skip]
pub struct LeCreateBigComplete {
    pub status: Status,
    pub big_handle: u8,
    #[N(3)] pub big_sync_delay: u32,
    #[N(3)] pub big_transport_latency: u32,
    pub phy: u8,
    pub nse: u8,
    pub bn: u8,
    pub pto: u8,
    pub irc: u8,
    pub max_pdu: u16,
    pub iso_interval: u16,
    pub bis_handles: Vec<u16>,
}

#[test]
fn test_le_create_big_complete() {
    let dump = [
        0x3e, 0x17, 0x1b, 0x00, 0x00, 0x46, 0x50, 0x00, 0x66, 0x9e, 0x00, 0x02, 0x0f, 0x03, 0x00,
        0x05, 0x78, 0x00, 0x18, 0x00, 0x02, 0x00, 0x04, 0x01, 0x04,
    ];
    let Ok(Event::LeCreateBigComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.status, Status::Success);
    assert_eq!(e.big_handle, 0x00);
    assert_eq!(e.big_sync_delay, 20_550);
    assert_eq!(e.big_transport_latency, 40_550);
    assert_eq!(e.phy, 0x02);
    assert_eq!(e.nse, 15);
    assert_eq!(e.bn, 3);
    assert_eq!(e.pto, 0);
    assert_eq!(e.irc, 5);
    assert_eq!(e.max_pdu, 120);
    assert_eq!(e.iso_interval, 24);
    assert_eq!(e.bis_handles.len(), 2);
    assert_eq!(e.bis_handles[0], 0x400);
    assert_eq!(e.bis_handles[1], 0x401);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// 7.7.65.28 LE Terminate BIG Complete

impl EventCode for LeTerminateBigComplete {
    const CODE: Code = Code(Code::LE_META, Some(0x1c));
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct LeTerminateBigComplete {
    pub big_handle: u8,
    pub reason: u8,
}

#[test]
fn test_le_terminate_big_complete() {
    let dump = [0x3e, 0x03, 0x1c, 0x00, 0x16];
    let Ok(Event::LeTerminateBigComplete(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.big_handle, 0x00);
    assert_eq!(e.reason, 0x16);
    assert_eq!(e.to_bytes(), &dump[..]);
}

// Vendor-specific LE ISO Link Feedback

impl EventCode for LeIsoLinkFeedback {
    const CODE: Code = Code(Code::VS_META, Some(0x5c));
}

#[derive(Debug, Read, Write, EventToBytes)]
pub struct LeIsoLinkFeedback {
    pub iso_handle: u16,
    pub sequence_number: u16,
    pub anchor_point_delay: u16,
    pub in_status: u16,
    pub tx_status: u16,
}

#[test]
fn test_le_iso_link_feedback() {
    let dump = [0xff, 0x0b, 0x5c, 0x60, 0x00, 0x28, 0x01, 0x25, 0x1c, 0x00, 0x00, 0x00, 0x00];
    let Ok(Event::LeIsoLinkFeedback(e)) = Event::from_bytes(&dump) else { panic!() };
    assert_eq!(e.iso_handle, 0x60);
    assert_eq!(e.sequence_number, 0x128);
    assert_eq!(e.anchor_point_delay, 7_205);
    assert_eq!(e.in_status, 0);
    assert_eq!(e.tx_status, 0);
    assert_eq!(e.to_bytes(), &dump[..]);
}
