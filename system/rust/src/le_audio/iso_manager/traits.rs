/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! ISO manager traits and event definitions.

use bluetooth_macros::bt_handle;
use futures::Stream;
use std::fmt;
use std::future::Future;
use std::time::Duration;
use thiserror::Error;
#[cfg(test)]
use tokio_stream::wrappers::ReceiverStream;

use crate::pdl::hci::HciStatus;

/// Identifier for a CIS.
#[bt_handle(mask = 0xef)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CisId(u8);

/// Identifier for a CIG.
#[bt_handle(mask = 0xef)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CigId(u8);

/// ISO Connection handle.
#[bt_handle(mask = 0xeff)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct IsoConnectionHandle(u16);

/// ACL Connection handle.
#[bt_handle(mask = 0xeff)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct AclConnectionHandle(u16);

/// CIS configuration used for CigParameters.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CisConfiguration {
    /// CIS ID.
    pub cis_id: CisId,
    /// Maximum SDU size from Central to Peripheral.
    pub max_sdu_c_to_p: u16,
    /// Maximum SDU size from Peripheral to Central.
    pub max_sdu_p_to_c: u16,
    /// PHY from Central to Peripheral.
    pub phy_c_to_p: u8,
    /// PHY from Peripheral to Central.
    pub phy_p_to_c: u8,
    /// Number of retransmissions from Central to Peripheral.
    pub rtn_c_to_p: u8,
    /// Number of retransmissions from Peripheral to Master.
    pub rtn_p_to_c: u8,
}

/// Parameters for setting a CIG parameters.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CigParameters {
    /// SDU interval from Central to Peripheral.
    pub sdu_interval_c_to_p: u32,
    /// SDU interval from Peripheral to Central.
    pub sdu_interval_p_to_c: u32,
    /// Worst-case sleep clock accuracy.
    pub worse_cast_sca: u8,
    /// Packing method.
    pub packing: bool,
    /// Framing method.
    pub framing: bool,
    /// Maximum transport latency from Central to Peripheral.
    pub max_transport_latency_c_to_p: u16,
    /// Maximum transport latency from Peripheral to Central.
    pub max_transport_latency_p_to_c: u16,
    /// CIS configurations.
    pub cis_configurations: Vec<CisConfiguration>,
}

/// Parameters for creating CISes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CreateCisParameters {
    /// Connection handle pairs (CIS handle, ACL handle).
    pub conn_handle_pairs: Vec<(IsoConnectionHandle, AclConnectionHandle)>,
}

/// Codec ID.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct CodecId {
    /// Coding format.
    pub coding_format: u8,
    /// Company identifier values.
    pub company_id: u16,
    /// Vendor-specific codec_ID.
    pub vendor_specific_codec_id: u16,
}

/// Parameters for setting up an ISO data path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SetupIsoDataPathParameters {
    /// Data path direction.
    pub data_path_dir: DataPathDirection,
    /// Data path ID.
    pub data_path_id: DataPathId,
    /// Codec ID.
    pub codec_id: CodecId,
    /// Controller delay.
    pub controller_delay: Duration,
    /// Codec configuration.
    pub codec_configuration: Vec<u8>,
}

/// Direction for setting up an ISO data path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataPathDirection {
    /// Input path.
    Input = 0,
    /// Output path.
    Output = 1,
}

/// Direction for removing an ISO data path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoveIsoDataPathDirection {
    /// Remove input path.
    Input = 1,
    /// Remove output path.
    Output = 2,
    /// Remove both paths.
    Both = 3,
}

/// Identifier for an ISO data path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataPathId {
    /// HCI data path.
    Hci = 0x00,
    /// Platform-specific data path.
    Platform = 0x01,
    /// Software Offload path.
    SoftwareOffload = 0x19,
}

/// ISO link quality statistics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IsoLinkQuality {
    /// Number of packets unacknowledged by the controller.
    pub tx_unacked_packets: u32,
    /// Number of packets flushed by the controller.
    pub tx_flushed_packets: u32,
    /// Number of packets sent in the last subevent.
    pub tx_last_subevent_packets: u32,
    /// Number of retransmitted packets.
    pub retransmitted_packets: u32,
    /// Number of packets with CRC errors.
    pub crc_error_packets: u32,
    /// Number of unreceived packets.
    pub rx_unreceived_packets: u32,
    /// Number of duplicate packets.
    pub duplicate_packets: u32,
}

/// ISO data packet received from the controller.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IsoDataPacket {
    /// Timestamp.
    pub time_stamp: Option<Duration>,
    /// Sequence number.
    pub seq_nb: u16,
    /// The actual ISO data.
    pub data: Vec<u8>,
}

/// Result type for ISO manager operations.
pub type Result<T> = std::result::Result<T, IsoManagerError>;

/// Error type for ISO Manager operations.
#[derive(Error, Debug, Clone, PartialEq, Eq)]
pub enum IsoManagerError {
    /// HCI Error status code.
    #[error("HCI Error: {0:?}")]
    HciError(HciStatus),
}

/// ISO data streams interface.
pub trait IsoDataStream: Send + Sync {
    /// Associated stream type for ISO data packets.
    type DataStream: Stream<Item = IsoDataPacket> + Send + 'static;

    /// Returns the connection handle.
    fn conn_handle(&self) -> IsoConnectionHandle;

    /// Sends ISO data to this connection.
    fn write(&self, data: &[u8]);

    /// Read incoming ISO data packets from this connection.
    fn read(&self) -> Self::DataStream;

    /// Sets up the ISO data path for this stream.
    fn setup_iso_data_path(
        &self,
        path_params: SetupIsoDataPathParameters,
    ) -> impl Future<Output = Result<()>> + Send;

    /// Removes the ISO data path for this stream.
    fn remove_iso_data_path(
        &self,
        data_path_dir: RemoveIsoDataPathDirection,
    ) -> impl Future<Output = Result<()>> + Send;
}

/// Interface for a Connected Isochronous Stream (CIS).
pub trait Cis: IsoDataStream + Send + Sync + fmt::Debug {
    /// Disconnects this CIS.
    fn disconnect(&self, reason: HciStatus) -> impl Future<Output = Result<()>> + Send;

    /// Reads the ISO link quality for this CIS.
    fn read_iso_link_quality(&self) -> impl Future<Output = Result<IsoLinkQuality>> + Send;

    /// Returns a future that completes when the CIS is disconnected by the remote or link loss.
    /// This does NOT trigger if the local Cis object's disconnect() was called.
    fn on_disconnected(&self) -> impl Future<Output = HciStatus> + Send;
}

/// Interface for a Connected Isochronous Group (CIG).
pub trait Cig: Clone + fmt::Debug {
    /// Resource type for individual CIS connections.
    type Cis: Cis;

    /// Returns the CIG identifier.
    fn cig_id(&self) -> CigId;

    /// Returns the CIS connections in this CIG.
    fn cis_connections(&self) -> Vec<Self::Cis>;

    /// Returns a specific CIS connection from this group.
    fn get_cis_connection(&self, cis_conn_handle: IsoConnectionHandle) -> Option<Self::Cis>;

    /// Reconfigure an existing CIG. Previously obtained Cis handles are invalidated.
    fn reconfigure(&self, params: CigParameters) -> impl Future<Output = Result<()>> + Send;

    /// Removed the CIG.
    fn remove(&self, force: bool) -> impl Future<Output = Result<()>> + Send;

    /// Creates the Connected Isochronous Streams in this CIG.
    fn create_cis(
        &self,
        conn_params: CreateCisParameters,
    ) -> impl Future<Output = Result<Vec<Self::Cis>>> + Send;
}

#[cfg(test)]
mockall::mock! {
    pub Cis {}

    impl IsoDataStream for Cis {
        type DataStream = ReceiverStream<IsoDataPacket>;
        fn conn_handle(&self) -> IsoConnectionHandle;
        fn write(&self, data: &[u8]);
        fn read(&self) -> ReceiverStream<IsoDataPacket>;
        fn setup_iso_data_path(
            &self,
            path_params: SetupIsoDataPathParameters,
        ) -> impl Future<Output = Result<()>> + Send;
        fn remove_iso_data_path(
            &self,
            data_path_dir: RemoveIsoDataPathDirection,
        ) -> impl Future<Output = Result<()>> + Send;
    }

    impl Cis for Cis {
        fn disconnect(&self, reason: HciStatus) -> impl Future<Output = Result<()>> + Send;
        fn read_iso_link_quality(&self) -> impl Future<Output = Result<IsoLinkQuality>> + Send;
        fn on_disconnected(&self) -> impl Future<Output = HciStatus> + Send;
    }

    impl Clone for Cis {
        fn clone(&self) -> Self;
    }

    impl fmt::Debug for Cis {
        fn fmt<'a>(&self, f: &mut fmt::Formatter<'a>) -> fmt::Result;
    }
}

#[cfg(test)]
mockall::mock! {
    pub Cig {}

    impl Cig for Cig {
        type Cis = MockCis;

        fn cig_id(&self) -> CigId;
        fn cis_connections(&self) -> Vec<MockCis>;
        fn get_cis_connection(&self, cis_conn_handle: IsoConnectionHandle) -> Option<MockCis>;
        fn reconfigure(
            &self,
            params: CigParameters,
        ) -> impl Future<Output = Result<()>> + Send;
        fn remove(&self, force: bool) -> impl Future<Output = Result<()>> + Send;
        fn create_cis(
            &self,
            conn_params: CreateCisParameters,
        ) -> impl Future<Output = Result<Vec<MockCis>>> + Send;
    }

    impl Clone for Cig {
        fn clone(&self) -> Self;
    }

    impl fmt::Debug for Cig {
        fn fmt<'a>(&self, f: &mut fmt::Formatter<'a>) -> fmt::Result;
    }
}
