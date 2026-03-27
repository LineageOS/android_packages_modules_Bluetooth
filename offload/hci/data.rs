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

use crate::reader::{unpack, Read, Reader};
use crate::writer::{pack, Write, Writer};

/// Bluetooth Address
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct BluetoothAddress([u8; 6]);

/// Bluetooth address, represented in Big Endian format.
impl BluetoothAddress {
    /// Create new Bluetooth Address from bytes in Big Endian order
    pub const fn from_be_bytes(bytes: [u8; 6]) -> Self {
        Self(bytes)
    }

    /// Convert to bytes
    pub fn to_bytes(&self) -> [u8; 6] {
        self.0
    }

    /// Convert to bytes reference
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

impl From<[u8; 6]> for BluetoothAddress {
    fn from(bytes: [u8; 6]) -> Self {
        Self(bytes)
    }
}

impl From<BluetoothAddress> for [u8; 6] {
    fn from(addr: BluetoothAddress) -> Self {
        addr.0
    }
}

impl std::fmt::Display for BluetoothAddress {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let b = self.0;
        write!(f, "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}", b[0], b[1], b[2], b[3], b[4], b[5])
    }
}

impl Write for BluetoothAddress {
    fn write(&self, w: &mut Writer) {
        let mut b = self.0;
        // Reverse bytes to Little Endian order
        b.reverse();
        w.put(&b);
    }
}

impl Read for BluetoothAddress {
    fn read(r: &mut Reader) -> Option<Self> {
        let mut b: [u8; 6] = r.get(6)?.try_into().ok()?;
        // Reverse bytes to Big Endian order
        b.reverse();
        Some(Self(b))
    }
}

#[test]
fn test_bluetooth_address() {
    let dump = [0x06, 0x05, 0x04, 0x03, 0x02, 0x01];
    let mut reader = Reader::new(&dump);
    let Some(addr) = BluetoothAddress::read(&mut reader) else { panic!() };

    assert_eq!(addr.to_bytes(), [0x01, 0x02, 0x03, 0x04, 0x05, 0x06]);
    assert_eq!(format!("{addr}"), "01:02:03:04:05:06");

    let mut w = Writer::new(Vec::new());
    w.write(&addr);
    assert_eq!(w.into_vec(), &dump[..]);
}

#[test]
fn test_bluetooth_address_methods() {
    let bytes = [0x11, 0x22, 0x33, 0x44, 0x55, 0x66];
    let addr = BluetoothAddress::from_be_bytes(bytes);

    assert_eq!(addr.as_bytes(), &bytes);

    let addr_from = BluetoothAddress::from(bytes);
    assert_eq!(addr, addr_from);

    let bytes_into: [u8; 6] = addr.into();
    assert_eq!(bytes, bytes_into);
}

/// 5.4.2 ACL Data packets

/// Exchange of data between the Host and Controller
#[derive(Debug)]
pub struct AclData<'a> {
    /// Identify the connection
    pub connection_handle: u16,
    /// ACL packet type
    pub acl_type: AclType,
    /// Payload
    pub payload: &'a [u8],
}

/// Fragmentation indication of the ACL
#[derive(Debug)]
pub enum AclType {
    /// First packet
    First {
        /// Is automatically flushable
        is_flushable: bool,
        /// Is broadcast or point-to-point
        is_broadcast: bool,
    },
    /// Continuing fragment
    Continue {
        /// Is broadcast or point-to-point
        is_broadcast: bool,
    },
}

impl<'a> AclData<'a> {
    /// Read an HCI ACL Data packet
    pub fn from_bytes(data: &'a [u8]) -> Option<Self> {
        Self::parse(&mut Reader::new(data))
    }

    /// Output the HCI ACL Data packet
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut w = Writer::new(Vec::with_capacity(4 + self.payload.len()));
        w.write(self);
        w.into_vec()
    }

    /// New ACL Data packet, first, flushable and point-to-point
    pub fn new(connection_handle: u16, acl_type: AclType, data: &'a [u8]) -> Self {
        Self { connection_handle, acl_type, payload: data }
    }

    fn parse(r: &mut Reader<'a>) -> Option<Self> {
        let (connection_handle, pb_flag, bc_flag) = unpack!(r.read_u16()?, (12, 2, 2));
        let data_len = r.read_u16()? as usize;

        let acl_type = match pb_flag {
            0b00 => AclType::First { is_flushable: false, is_broadcast: bc_flag == 1 },
            0b10 => AclType::First { is_flushable: true, is_broadcast: bc_flag == 1 },
            0b01 => AclType::Continue { is_broadcast: bc_flag == 1 },
            _ => panic!("Invalid PB Flag value: {pb_flag}"),
        };

        Some(Self { connection_handle, acl_type, payload: r.get(data_len)? })
    }
}

impl Write for AclData<'_> {
    fn write(&self, w: &mut Writer) {
        let (pb_flag, bc_flag) = match self.acl_type {
            AclType::First { is_flushable: false, is_broadcast: false } => (0b00, 0b00),
            AclType::First { is_flushable: false, is_broadcast: true } => (0b00, 0b01),
            AclType::First { is_flushable: true, is_broadcast: false } => (0b10, 0b00),
            AclType::First { is_flushable: true, is_broadcast: true } => (0b10, 0b01),
            AclType::Continue { is_broadcast: false } => (0b01, 0b00),
            AclType::Continue { is_broadcast: true } => (0b01, 0b01),
        };

        w.write_u16(pack!((self.connection_handle, 12), (pb_flag, 2), (bc_flag, 2)));

        let packet_len = self.payload.len();
        w.write_u16(
            u16::try_from(packet_len).expect("ACL Data payload length exceeds maximum u16 value"),
        );
        w.put(self.payload);
    }
}

#[test]
fn test_acl_data() {
    let dump = [
        0x0b, 0x20, 0x55, 0x00, 0x51, 0x00, 0x40, 0xa0, 0x80, 0xe0, 0x00, 0x03, 0x00, 0x00, 0x08,
        0x00, 0x00, 0x00, 0x00, 0x02, 0x47, 0xfc, 0x00, 0x00, 0xb0, 0x90, 0x80, 0x03, 0x00, 0x3b,
        0x21, 0x1b, 0xd3, 0x90, 0x06, 0x90, 0x83, 0x0f, 0x91, 0xbe, 0x4d, 0x4e, 0x36, 0xa5, 0xb2,
        0xc1, 0x17, 0xbb, 0x43, 0xfc, 0x00, 0x3c, 0xf2, 0xf9, 0xc7, 0xbc, 0xd7, 0x92, 0xf4, 0x6e,
        0xa3, 0xac, 0x77, 0xed, 0xf7, 0x88, 0xe6, 0xdd, 0x66, 0xdd, 0xa1, 0xe3, 0x78, 0x96, 0x9d,
        0x3b, 0x0d, 0x59, 0x80, 0xfc, 0x16, 0xcb, 0x04, 0x5e, 0xed, 0x0f, 0xf0, 0x00, 0xf7,
    ];
    let Some(pkt) = AclData::from_bytes(&dump) else { panic!() };
    assert_eq!(pkt.connection_handle, 0x00b);

    let AclType::First { is_flushable: true, is_broadcast: false } = pkt.acl_type else { panic!() };

    assert_eq!(
        pkt.payload,
        &[
            0x51, 0x00, 0x40, 0xa0, 0x80, 0xe0, 0x00, 0x03, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x00, 0x02, 0x47, 0xfc, 0x00, 0x00, 0xb0, 0x90, 0x80, 0x03, 0x00, 0x3b, 0x21, 0x1b,
            0xd3, 0x90, 0x06, 0x90, 0x83, 0x0f, 0x91, 0xbe, 0x4d, 0x4e, 0x36, 0xa5, 0xb2, 0xc1,
            0x17, 0xbb, 0x43, 0xfc, 0x00, 0x3c, 0xf2, 0xf9, 0xc7, 0xbc, 0xd7, 0x92, 0xf4, 0x6e,
            0xa3, 0xac, 0x77, 0xed, 0xf7, 0x88, 0xe6, 0xdd, 0x66, 0xdd, 0xa1, 0xe3, 0x78, 0x96,
            0x9d, 0x3b, 0x0d, 0x59, 0x80, 0xfc, 0x16, 0xcb, 0x04, 0x5e, 0xed, 0x0f, 0xf0, 0x00,
            0xf7
        ]
    );
    assert_eq!(pkt.to_bytes(), &dump[..]);
}

/// 5.4.5 ISO Data Packets

/// Exchange of Isochronous Data between the Host and Controller
#[derive(Debug)]
pub struct IsoData<'a> {
    /// Identify the connection
    pub connection_handle: u16,
    /// Fragmentation of the packet
    pub sdu_fragment: IsoSduFragment,
    /// Payload
    pub payload: &'a [u8],
}

/// Fragmentation indication of the SDU
#[derive(Debug)]
pub enum IsoSduFragment {
    /// First SDU Fragment
    First {
        /// SDU Header
        hdr: IsoSduHeader,
        /// Last SDU fragment indication
        is_last: bool,
    },
    /// Continuous fragment
    Continue {
        /// Last SDU fragment indication
        is_last: bool,
    },
}

/// SDU Header information, when ISO Data in a first SDU fragment
#[derive(Debug, Default)]
pub struct IsoSduHeader {
    /// Optional timestamp in microseconds
    pub timestamp: Option<u32>,
    /// Sequence number of the SDU
    pub sequence_number: u16,
    /// Total length of the SDU (sum of all fragments)
    pub sdu_length: u16,
    /// Only valid from Controller, indicate valid SDU data when 0
    pub status: u16,
}

impl<'a> IsoData<'a> {
    /// Read an HCI ISO Data packet
    pub fn from_bytes(data: &'a [u8]) -> Option<Self> {
        Self::parse(&mut Reader::new(data))
    }

    /// Output the HCI ISO Data packet
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut w = Writer::new(Vec::with_capacity(12 + self.payload.len()));
        w.write(self);
        w.into_vec()
    }

    /// New ISO Data packet, including a complete SDU
    pub fn new(connection_handle: u16, sequence_number: u16, data: &'a [u8]) -> Self {
        Self {
            connection_handle,
            sdu_fragment: IsoSduFragment::First {
                hdr: IsoSduHeader {
                    sequence_number,
                    sdu_length: data.len().try_into().unwrap(),
                    ..Default::default()
                },
                is_last: true,
            },
            payload: data,
        }
    }

    fn parse(r: &mut Reader<'a>) -> Option<Self> {
        let (connection_handle, pb_flag, ts_present) = unpack!(r.read_u16()?, (12, 2, 1));
        let data_len = unpack!(r.read_u16()?, 14) as usize;

        let sdu_fragment = match pb_flag {
            0b00 => IsoSduFragment::First {
                hdr: IsoSduHeader::parse(r, ts_present != 0)?,
                is_last: false,
            },
            0b10 => IsoSduFragment::First {
                hdr: IsoSduHeader::parse(r, ts_present != 0)?,
                is_last: true,
            },
            0b01 => IsoSduFragment::Continue { is_last: false },
            0b11 => IsoSduFragment::Continue { is_last: true },
            _ => unreachable!(),
        };
        let sdu_header_len = Self::sdu_header_len(&sdu_fragment);
        if data_len < sdu_header_len {
            return None;
        }

        Some(Self { connection_handle, sdu_fragment, payload: r.get(data_len - sdu_header_len)? })
    }

    fn sdu_header_len(sdu_fragment: &IsoSduFragment) -> usize {
        match sdu_fragment {
            IsoSduFragment::First { ref hdr, .. } => 4 * (1 + hdr.timestamp.is_some() as usize),
            IsoSduFragment::Continue { .. } => 0,
        }
    }
}

impl Write for IsoData<'_> {
    fn write(&self, w: &mut Writer) {
        let (pb_flag, hdr) = match self.sdu_fragment {
            IsoSduFragment::First { ref hdr, is_last: false } => (0b00, Some(hdr)),
            IsoSduFragment::First { ref hdr, is_last: true } => (0b10, Some(hdr)),
            IsoSduFragment::Continue { is_last: false } => (0b01, None),
            IsoSduFragment::Continue { is_last: true } => (0b11, None),
        };

        let ts_present = hdr.is_some() && hdr.unwrap().timestamp.is_some();
        w.write_u16(pack!((self.connection_handle, 12), (pb_flag, 2), ((ts_present as u16), 1)));

        let packet_len = Self::sdu_header_len(&self.sdu_fragment) + self.payload.len();
        w.write_u16(pack!(u16::try_from(packet_len).unwrap(), 14));

        if let Some(hdr) = hdr {
            w.write(hdr);
        }
        w.put(self.payload);
    }
}

impl IsoSduHeader {
    fn parse(r: &mut Reader, ts_present: bool) -> Option<Self> {
        let timestamp = match ts_present {
            true => Some(r.read_u32::<4>()?),
            false => None,
        };
        let sequence_number = r.read_u16()?;
        let (sdu_length, _, status) = unpack!(r.read_u16()?, (12, 2, 2));
        Some(Self { timestamp, sequence_number, sdu_length, status })
    }
}

impl Write for IsoSduHeader {
    fn write(&self, w: &mut Writer) {
        if let Some(timestamp) = self.timestamp {
            w.write_u32::<4>(timestamp);
        };
        w.write_u16(self.sequence_number);
        w.write_u16(pack!((self.sdu_length, 12), (0, 2), (self.status, 2)));
    }
}

#[test]
fn test_iso_data() {
    let dump = [
        0x60, 0x60, 0x80, 0x00, 0x4d, 0xc8, 0xd0, 0x2f, 0x19, 0x03, 0x78, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0xe0, 0x93, 0xe5, 0x28, 0x34, 0x00, 0x00, 0x04,
    ];
    let Some(pkt) = IsoData::from_bytes(&dump) else { panic!() };
    assert_eq!(pkt.connection_handle, 0x060);

    let IsoSduFragment::First { ref hdr, is_last } = pkt.sdu_fragment else { panic!() };
    assert_eq!(hdr.timestamp, Some(802_211_917));
    assert_eq!(hdr.sequence_number, 793);
    assert_eq!(hdr.sdu_length, 120);
    assert!(is_last);

    assert_eq!(
        pkt.payload,
        &[
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xe0, 0x93, 0xe5, 0x28, 0x34, 0x00, 0x00, 0x04
        ]
    );
    assert_eq!(pkt.to_bytes(), &dump[..]);
}
