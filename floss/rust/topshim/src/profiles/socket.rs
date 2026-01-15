use num_derive::{FromPrimitive, ToPrimitive};
use std::convert::{TryFrom, TryInto};
use std::ffi::CString;
use std::fs::File;
use std::os::unix::io::FromRawFd;

use topshim_macros::{gen_cxx_extern_trivial_tuple, log_args};

use crate::bindings::root as bindings;
use crate::btif::{BluetoothInterface, BtStatus, RawAddress, Uuid};

#[derive(Clone, Debug, FromPrimitive, ToPrimitive)]
#[repr(u32)]
/// Socket interface type.
pub enum SocketType {
    Rfcomm = 1,
    Sco = 2,
    L2cap = 3,
    L2capLe = 4,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxSocketType(pub bindings::btsock_type_t);

impl From<CxxSocketType> for SocketType {
    fn from(item: CxxSocketType) -> Self {
        match item.0 {
            bindings::btsock_type_t_BTSOCK_RFCOMM => SocketType::Rfcomm,
            bindings::btsock_type_t_BTSOCK_SCO => SocketType::Sco,
            bindings::btsock_type_t_BTSOCK_L2CAP => SocketType::L2cap,
            bindings::btsock_type_t_BTSOCK_L2CAP_LE => SocketType::L2capLe,
            _ => panic!("Unsupported btsock_type_t {}", item.0),
        }
    }
}

impl From<SocketType> for CxxSocketType {
    fn from(item: SocketType) -> Self {
        let i = match item {
            SocketType::Rfcomm => bindings::btsock_type_t_BTSOCK_RFCOMM,
            SocketType::Sco => bindings::btsock_type_t_BTSOCK_SCO,
            SocketType::L2cap => bindings::btsock_type_t_BTSOCK_L2CAP,
            SocketType::L2capLe => bindings::btsock_type_t_BTSOCK_L2CAP_LE,
        };
        CxxSocketType(i)
    }
}

#[derive(Clone, Debug, FromPrimitive, ToPrimitive)]
#[repr(u32)]
pub enum SocketDataPath {
    NoOffload = 0,
    HardwareOffload = 1,
}

#[gen_cxx_extern_trivial_tuple]
struct CxxSocketDataPath(pub bindings::btsock_data_path_t);

impl From<CxxSocketDataPath> for SocketDataPath {
    fn from(item: CxxSocketDataPath) -> Self {
        match item.0 {
            bindings::btsock_data_path_t_BTSOCK_DATA_PATH_NO_OFFLOAD => SocketDataPath::NoOffload,
            bindings::btsock_data_path_t_BTSOCK_DATA_PATH_HARDWARE_OFFLOAD => {
                SocketDataPath::HardwareOffload
            }
            _ => panic!("Unsupported btsock_data_path_t {}", item.0),
        }
    }
}

impl From<SocketDataPath> for CxxSocketDataPath {
    fn from(item: SocketDataPath) -> Self {
        let i = match item {
            SocketDataPath::NoOffload => bindings::btsock_data_path_t_BTSOCK_DATA_PATH_NO_OFFLOAD,
            SocketDataPath::HardwareOffload => {
                bindings::btsock_data_path_t_BTSOCK_DATA_PATH_HARDWARE_OFFLOAD
            }
        };
        CxxSocketDataPath(i)
    }
}

/// Socket flag: No flags (used for insecure connections).
pub const SOCK_FLAG_NONE: i32 = 0;
/// Socket flag: connection must be encrypted.
pub const SOCK_FLAG_ENCRYPT: i32 = 1 << 0;
/// Socket flag: require authentication.
pub const SOCK_FLAG_AUTH: i32 = 1 << 1;
/// Socket flag: don't generate SDP entry for listening socket.
pub const SOCK_FLAG_NO_SDP: i32 = 1 << 2;
/// Socket flag: require authentication with MITM protection.
pub const SOCK_FLAG_AUTH_MITM: i32 = 1 << 3;
/// Socket flag: require a minimum of 16 digits for sec mode 2 connections.
pub const SOCK_FLAG_AUTH_16_DIGIT: i32 = 1 << 4;
/// Socket flag: LE connection oriented channel.
pub const SOCK_FLAG_LE_COC: i32 = 1 << 5;

/// Combination of SOCK_FLAG_ENCRYPT and SOCK_FLAG_AUTH.
pub const SOCK_META_FLAG_SECURE: i32 = SOCK_FLAG_ENCRYPT | SOCK_FLAG_AUTH;

/// Struct showing a completed socket event. This is the first data that should
/// arrive on a connecting socket once it is connected.
pub struct ConnectionComplete {
    pub size: u16,
    pub addr: RawAddress,
    pub channel: i32,
    pub status: i32,
    pub max_tx_packet_size: u16,
    pub max_rx_packet_size: u16,
}

/// Size of connect complete data. This is the packed data length from libbluetooth.
pub const CONNECT_COMPLETE_SIZE: usize = std::mem::size_of::<bindings::sock_connect_signal_t>();

// Convert from raw bytes to struct.
impl TryFrom<&[u8]> for ConnectionComplete {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        if bytes.len() != CONNECT_COMPLETE_SIZE {
            return Err(format!("Wrong number of bytes for Connection Complete: {}", bytes.len()));
        }

        // The ConnectComplete event is constructed within libbluetooth and uses
        // the native endianness of the machine when writing to the socket. When
        // parsing, make sure to use native endianness here.
        let (size_bytes, rest) = bytes.split_at(std::mem::size_of::<u16>());
        if u16::from_ne_bytes(size_bytes.try_into().unwrap()) != (CONNECT_COMPLETE_SIZE as u16) {
            return Err(format!("Wrong size in Connection Complete: {:?}", size_bytes));
        }

        // We know from previous size checks that all these splits will work.
        let (addr_bytes, rest) = rest.split_at(std::mem::size_of::<RawAddress>());
        let (channel_bytes, rest) = rest.split_at(std::mem::size_of::<i32>());
        let (status_bytes, rest) = rest.split_at(std::mem::size_of::<i32>());
        let (max_tx_packet_size_bytes, rest) = rest.split_at(std::mem::size_of::<u16>());
        let (max_rx_packet_size_bytes, _unused) = rest.split_at(std::mem::size_of::<u16>());

        let addr = match RawAddress::from_bytes(addr_bytes) {
            Some(v) => v,
            None => {
                return Err("Invalid address in Connection Complete".into());
            }
        };

        Ok(ConnectionComplete {
            size: CONNECT_COMPLETE_SIZE.try_into().unwrap_or_default(),
            addr,
            channel: i32::from_ne_bytes(channel_bytes.try_into().unwrap()),
            status: i32::from_ne_bytes(status_bytes.try_into().unwrap()),
            max_tx_packet_size: u16::from_ne_bytes(max_tx_packet_size_bytes.try_into().unwrap()),
            max_rx_packet_size: u16::from_ne_bytes(max_rx_packet_size_bytes.try_into().unwrap()),
        })
    }
}

// Rust Socket FFI that matches the C++ Socket Interface defined in /topshim/socket/socket_shim.h
#[cxx::bridge(namespace = "bluetooth::topshim::rust")]
mod ffi {
    unsafe extern "C++" {
        include!("bluetooth/types/address.h");
        include!("bluetooth/types/uuid.h");
        include!("include/hardware/bt_sock.h");
        include!("topshim/socket/socket_shim.h");

        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;

        #[namespace = "bluetooth"]
        type Uuid = crate::btif::Uuid;

        #[namespace = ""]
        #[cxx_name = "btsock_type_t"]
        type SocketType = super::CxxSocketType;

        #[namespace = ""]
        #[cxx_name = "btsock_data_path_t"]
        type SocketDataPath = super::CxxSocketDataPath;

        type BtIntf = crate::btif::ffi::BtIntf;

        type SocketIntf;

        fn GetSocketProfile(btif: &BtIntf) -> UniquePtr<SocketIntf>;

        #[allow(clippy::too_many_arguments)]
        fn listen(
            self: &SocketIntf,
            socket_type: SocketType,
            service_name: Vec<u8>,
            uuid: Uuid,
            channel: i32,
            sock_fd: &mut i32,
            flags: i32,
            app_uid: i32,
            data_path: SocketDataPath,
            socket_name: Vec<u8>,
            hub_id: u64,
            endpoint_id: u64,
            max_rx_packet_size: i32,
        ) -> u32;
        #[allow(clippy::too_many_arguments)]
        fn connect(
            self: &SocketIntf,
            bd_addr: RawAddress,
            socket_type: SocketType,
            uuid: Uuid,
            channel: i32,
            sock_fd: &mut i32,
            flags: i32,
            app_uid: i32,
            data_path: SocketDataPath,
            socket_name: Vec<u8>,
            hub_id: u64,
            endpoint_id: u64,
            max_rx_packet_size: i32,
        ) -> u32;
        fn request_max_tx_data_length(self: &SocketIntf, bd_addr: RawAddress);
        #[allow(clippy::too_many_arguments)]
        fn control_req(
            self: &SocketIntf,
            dlci: u8,
            bd_addr: RawAddress,
            modem_signal: u8,
            break_signal: u8,
            discard_buffers: u8,
            break_signal_seq: u8,
            fc: bool,
        ) -> u32;
        fn disconnect_all(self: &SocketIntf, bd_addr: RawAddress) -> u32;
    }
}

/// Represents the standard BT SOCKET interface.
///
/// For parameter documentation, see the type |sock_connect_signal_t|.
pub type SocketConnectSignal = bindings::sock_connect_signal_t;

/// Bluetooth socket interface wrapper. This allows creation of RFCOMM and L2CAP sockets.
/// For documentation of functions, see definition of |btsock_interface_t|.
pub struct BtSocket {
    internal: cxx::UniquePtr<ffi::SocketIntf>,
}

// Pointers unsafe due to ownership but this is a static pointer so Send is ok.
unsafe impl Send for BtSocket {}

pub type FdError = &'static str;

pub fn try_from_fd(fd: i32) -> Result<File, FdError> {
    if fd >= 0 {
        Ok(unsafe { File::from_raw_fd(fd) })
    } else {
        Err("Invalid FD")
    }
}

impl BtSocket {
    #[log_args]
    pub fn new(intf: &BluetoothInterface) -> Self {
        let sock_intf: cxx::UniquePtr<ffi::SocketIntf> = ffi::GetSocketProfile(intf.as_btif());
        BtSocket { internal: sock_intf }
    }

    #[log_args]
    pub fn listen(
        &self,
        sock_type: SocketType,
        service_name: String,
        service_uuid: Option<Uuid>,
        channel: i32,
        flags: i32,
        calling_uid: i32,
    ) -> (BtStatus, Result<File, FdError>) {
        let mut sockfd: i32 = -1;

        let uuid = service_uuid.unwrap_or(Uuid::from([0; 16]));

        let name = CString::new(service_name).expect("Service name has null in it.");

        let data_path = SocketDataPath::NoOffload;
        let sock_name = CString::new("test").expect("Socket name has null in it");
        let hub_id: u64 = 0;
        let endpoint_id: u64 = 0;
        let max_rx_packet_size: i32 = 0;

        let status: BtStatus = self
            .internal
            .listen(
                sock_type.into(),
                name.into(),
                uuid,
                channel,
                &mut sockfd,
                flags,
                calling_uid,
                data_path.into(),
                sock_name.into(),
                hub_id,
                endpoint_id,
                max_rx_packet_size,
            )
            .into();

        (status, try_from_fd(sockfd))
    }

    #[log_args]
    pub fn connect(
        &self,
        addr: RawAddress,
        sock_type: SocketType,
        service_uuid: Option<Uuid>,
        channel: i32,
        flags: i32,
        calling_uid: i32,
    ) -> (BtStatus, Result<File, FdError>) {
        let mut sockfd: i32 = -1;
        let uuid = service_uuid.unwrap_or(Uuid::from([0; 16]));

        let data_path = SocketDataPath::NoOffload;
        let sock_name = CString::new("test").expect("Socket name has null in it");
        let hub_id: u64 = 0;
        let endpoint_id: u64 = 0;
        let max_rx_packet_size: i32 = 0;

        let status: BtStatus = self
            .internal
            .connect(
                addr,
                sock_type.into(),
                uuid,
                channel,
                &mut sockfd,
                flags,
                calling_uid,
                data_path.into(),
                sock_name.into(),
                hub_id,
                endpoint_id,
                max_rx_packet_size,
            )
            .into();

        (status, try_from_fd(sockfd))
    }

    #[log_args]
    pub fn request_max_tx_data_length(&self, addr: RawAddress) {
        self.internal.request_max_tx_data_length(addr);
    }

    #[log_args]
    pub fn send_msc(&self, dlci: u8, addr: RawAddress) -> BtStatus {
        // PORT_DTRDSR_ON | PORT_CTSRTS_ON | PORT_DCD_ON
        const DEFAULT_MODEM_SIGNAL: u8 = 0x01 | 0x02 | 0x08;

        const DEFAULT_BREAK_SIGNAL: u8 = 0;
        const DEFAULT_DISCARD_BUFFERS: u8 = 0;
        const DEFAULT_BREAK_SIGNAL_SEQ: u8 = 1; // In sequence.

        // In RFCOMM/DEVA-DEVB/RFC/BV-21-C and RFCOMM/DEVA-DEVB/RFC/BV-22-C test flow
        // we are requested to send an MSC command with FC=0.
        const FC: bool = false;

        self.internal
            .control_req(
                dlci,
                addr,
                DEFAULT_MODEM_SIGNAL,
                DEFAULT_BREAK_SIGNAL,
                DEFAULT_DISCARD_BUFFERS,
                DEFAULT_BREAK_SIGNAL_SEQ,
                FC,
            )
            .into()
    }

    #[log_args]
    pub fn disconnect_all(&self, addr: RawAddress) -> BtStatus {
        self.internal.disconnect_all(addr).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_conncomplete_parsing() {
        // Actual slice size doesn't match
        let small_input = [0u8; CONNECT_COMPLETE_SIZE - 1];
        let large_input = [0u8; CONNECT_COMPLETE_SIZE + 1];

        assert_eq!(false, ConnectionComplete::try_from(&small_input[0..]).is_ok());
        assert_eq!(false, ConnectionComplete::try_from(&large_input[0..]).is_ok());

        // Size param in slice doesn't match.
        let mut size_no_match: Vec<u8> = vec![];
        size_no_match.extend(i16::to_ne_bytes((CONNECT_COMPLETE_SIZE - 1) as i16));
        size_no_match.extend([0u8; CONNECT_COMPLETE_SIZE - 2]);

        assert_eq!(false, ConnectionComplete::try_from(size_no_match.as_slice()).is_ok());

        let valid_signal = bindings::sock_connect_signal_t {
            size: CONNECT_COMPLETE_SIZE as i16,
            bd_addr: RawAddress { address: [0x1, 0x2, 0x3, 0x4, 0x5, 0x6] },
            channel: 1_i32,
            status: 5_i32,
            max_tx_packet_size: 16_u16,
            max_rx_packet_size: 17_u16,
            socket_id: 0x1135113511351135_u64,
        };
        // SAFETY: The sock_connect_signal_t type has size CONNECT_COMPLETE_SIZE,
        // and has no padding, so it's safe to convert it to a byte array.
        let valid_raw_data: &[u8] = unsafe {
            std::slice::from_raw_parts(
                (&valid_signal as *const bindings::sock_connect_signal_t) as *const u8,
                CONNECT_COMPLETE_SIZE,
            )
        };

        let result = ConnectionComplete::try_from(valid_raw_data);
        assert_eq!(true, result.is_ok());

        if let Ok(cc) = result {
            assert_eq!(cc.size, CONNECT_COMPLETE_SIZE as u16);
            assert_eq!(cc.addr, RawAddress { address: [0x1, 0x2, 0x3, 0x4, 0x5, 0x6] });
            assert_eq!(cc.channel, 1_i32);
            assert_eq!(cc.status, 5_i32);
            assert_eq!(cc.max_tx_packet_size, 16_u16);
            assert_eq!(cc.max_rx_packet_size, 17_u16);
        }
    }
}
