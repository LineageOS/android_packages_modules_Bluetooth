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

//! Periodic sync manager traits and event definitions.

use bluetooth_macros::bt_handle;
#[cfg(test)]
use mockall::automock;
use std::future::Future;
use std::time::Duration;
use thiserror::Error;
#[cfg(test)]
use tokio_stream::wrappers::ReceiverStream;

use crate::pdl::hci::{AddressType, DataStatus, HciStatus};
use crate::Address;

/// Sync handle for periodic advertising synchronization.
#[bt_handle(mask = 0xeff)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct SyncHandle(u16);

/// Advertising Set ID (SID).
#[bt_handle(mask = 0xf)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Default)]
#[repr(transparent)]
pub struct AdvertisingSid(u8);

/// Represents the parameters for starting PA sync.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeriodicAdvertisingCreateSyncParameters {
    /// The unique identifier for the broadcast source.
    /// It would be used as registration id for native gd periodic synchronization.
    pub broadcast_id: u32,
    /// The advertising SID.
    pub advertising_sid: AdvertisingSid,
    /// The address type of the broadcaster.
    pub advertiser_addr_type: AddressType,
    /// The address of the broadcaster.
    pub advertiser_addr: Address,
    /// The skip interval (number of PA events that can be skipped).
    pub skip: u16,
    /// The synchronization timeout.
    /// While the underlying Bluetooth HCI layer uses 10ms intervals, the unit used here is
    /// milliseconds.
    pub sync_timeout: Duration,
}

/// Information for Periodic Sync establishment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeriodicAdvertisingSyncInfo {
    /// Registration ID.
    pub reg_id: i32,
    /// Identify the periodic advertising train.
    pub sync_handle: SyncHandle,
    /// Advertising SID.
    pub advertising_sid: AdvertisingSid,
    /// Address type.
    pub advertiser_addr_type: AddressType,
    /// Address.
    pub advertiser_addr: Address,
    /// Phy.
    pub advertiser_phy: u8,
    /// Interval.
    pub periodic_advertising_interval: Duration,
}

/// Event types for Periodic Sync.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PeriodicAdvertisingSyncEvent {
    /// Report carries periodic advertising data.
    PeriodicAdvertisingReport {
        /// Identify the periodic advertising train.
        sync_handle: SyncHandle,
        /// TX power.
        tx_power: i8,
        /// RSSI.
        rssi: i8,
        /// Data status.
        data_status: DataStatus,
        /// Data.
        data: Vec<u8>,
    },
    /// Periodic advertising sync lost.
    PeriodicAdvertisingSyncLost {
        /// Identify the periodic advertising train.
        sync_handle: SyncHandle,
    },
    /// Report carries BIG Info advertising data.
    BigInfoAdvertisingReport {
        /// Identify the periodic advertising train.
        sync_handle: SyncHandle,
        /// Indicate whether BIG carries encrypted data.
        encryption: bool,
    },
}

/// Errors returned by PeriodicAdvertisingSyncManager operations.
#[derive(Error, Debug, Clone, PartialEq, Eq)]
pub enum PeriodicAdvertisingSyncError {
    /// Operation timed out.
    #[error("operation timed out")]
    Timeout,
    /// A communication channel (oneshot/mpsc) was closed unexpectedly.
    #[error("channel closed")]
    ChannelClosed,
    /// A synchronization request for this source is already in progress.
    #[error("already in progress")]
    AlreadyInProgress,
    /// HCI Error status code.
    #[error("HCI error: {0:?}")]
    HciError(HciStatus),
    /// Invalid handle provided.
    #[error("invalid handle")]
    InvalidHandle,
}

/// A specialized Result type for PeriodicAdvertisingSyncManager operations.
pub type Result<T> = std::result::Result<T, PeriodicAdvertisingSyncError>;

/// Trait defining the interface for Periodic Sync Manager.
///
/// TODO: b/488209682 - Refactor this into an object-oriented API where `start_sync` returns a
/// `PeriodicAdvertisingSync` instance. This instance should implement a trait providing its own
/// `reports()` stream and `lost()` future, enabling RAII for resource management
/// and removing the need for manual handle-based event demuxing in the worker.
#[cfg_attr(test, automock(type EventStream = ReceiverStream<PeriodicAdvertisingSyncEvent>;))]
pub trait PeriodicAdvertisingSyncManager: Send + Sync {
    /// The type of the stream returned by subscribe_events.
    type EventStream: futures::Stream<Item = PeriodicAdvertisingSyncEvent> + Send + 'static;

    /// Starts synchronization with a periodic advertiser.
    /// This method is procedural: it waits for the synchronization to be physically
    /// established on the radio (triggered by the `OnPeriodicAdvertisingSyncStarted` callback
    /// with status success) or for the sync timeout to expire.
    /// Returns the sync handle and other details on success.
    fn start_sync(
        &self,
        params: PeriodicAdvertisingCreateSyncParameters,
    ) -> impl Future<Output = Result<PeriodicAdvertisingSyncInfo>> + Send;

    /// Stops a periodic synchronization.
    /// This method only waits for the command status: it returns immediately once
    /// the Bluetooth stack has accepted the request to stop. It DOES NOT wait
    /// for a radio-level confirmation event.
    fn stop_sync(&self, handle: SyncHandle) -> impl Future<Output = Result<()>> + Send;

    /// Subscribes to periodic sync events.
    fn subscribe_events(&self) -> Self::EventStream;
}
