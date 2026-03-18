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

#![allow(dead_code)]

use std::sync::{Arc, Mutex};

use crate::le_audio::periodic_sync::manager::SyncRegistry;
use crate::le_audio::periodic_sync::traits::{
    PeriodicSyncError, PeriodicSyncEvent, PeriodicSyncInfo,
};
use crate::pdl::hci::{AddressType, DataStatus, HciStatus};
use crate::Address;

#[cxx::bridge]
#[allow(unused_attributes)]
pub mod inner_ffi {
    #[namespace = "bluetooth::shim"]
    unsafe extern "C++" {
        include!("periodic_sync/periodic_sync_shim.h");

        // A C++ Address (mapped from Rust).
        #[namespace = "ffi"]
        type Address = crate::types::address::Address;

        type BleScannerInterfaceShim;

        #[cxx_name = "GetBleScannerInterfaceShim"]
        fn get_ble_scanner_interface_shim() -> UniquePtr<BleScannerInterfaceShim>;

        #[cxx_name = "StartSync"]
        fn start_sync(
            self: Pin<&mut BleScannerInterfaceShim>,
            advertising_sid: u8,
            advertiser_addr: Address,
            advertiser_addr_type: u8,
            skip: u16,
            sync_timeout: u16,
            reg_id: i32,
        );

        #[cxx_name = "StopSync"]
        fn stop_sync(self: Pin<&mut BleScannerInterfaceShim>, handle: u16);

        #[cxx_name = "RegisterCallbacksNative"]
        fn register_callbacks_native(
            self: Pin<&mut BleScannerInterfaceShim>,
            callbacks: Box<PeriodicSyncCallbacks>,
            client_id: u8,
        );
    }

    #[namespace = "bluetooth::shim::ffi"]
    extern "Rust" {
        type PeriodicSyncCallbacks;

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "OnPeriodicSyncStarted"]
        fn on_periodic_sync_started(
            self: &PeriodicSyncCallbacks,
            reg_id: i32,
            status: u8,
            sync_handle: u16,
            advertising_sid: u8,
            advertiser_addr_type: u8,
            advertiser_addr: Address,
            advertiser_phy: u8,
            periodic_advertising_interval: u16,
        );

        #[cxx_name = "OnPeriodicAdvertisingReport"]
        fn on_periodic_advertising_report(
            self: &PeriodicSyncCallbacks,
            sync_handle: u16,
            tx_power: i8,
            rssi: i8,
            data_status: u8,
            data: &[u8],
        );

        #[cxx_name = "OnPeriodicSyncLost"]
        fn on_periodic_sync_lost(self: &PeriodicSyncCallbacks, sync_handle: u16);

        #[cxx_name = "OnBigInfoAdvertisingReport"]
        fn on_biginfo_advertising_report(
            self: &PeriodicSyncCallbacks,
            sync_handle: u16,
            encryption: bool,
        );
    }
}

// Safety: `BleScannerInterfaceShim` is safe to send between threads.
unsafe impl Send for inner_ffi::BleScannerInterfaceShim {}

pub struct PeriodicSyncCallbacks {
    sync_registry: Arc<Mutex<SyncRegistry>>,
}

impl PeriodicSyncCallbacks {
    pub fn new(sync_registry: Arc<Mutex<SyncRegistry>>) -> Self {
        Self { sync_registry }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn on_periodic_sync_started(
        &self,
        reg_id: i32,
        status_raw: u8,
        sync_handle: u16,
        advertising_sid: u8,
        advertiser_addr_type_raw: u8,
        advertiser_addr: Address,
        advertiser_phy: u8,
        periodic_advertising_interval: u16,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let advertiser_addr_type = AddressType::try_from(advertiser_addr_type_raw)
            .unwrap_or(AddressType::PublicDeviceAddress);

        let mut sync_registry = self.sync_registry.lock().unwrap();

        let result = if status == HciStatus::Success {
            sync_registry.active_handles.insert(sync_handle);
            Ok(PeriodicSyncInfo {
                reg_id,
                sync_handle,
                advertising_sid,
                advertiser_addr_type,
                advertiser_addr,
                advertiser_phy,
                periodic_advertising_interval,
            })
        } else {
            Err(PeriodicSyncError::HciError(status))
        };

        if let Some(sender) = sync_registry.pending_requests.start_sync.remove(&reg_id) {
            let _ = sender.send(result);
        }
    }

    pub fn on_periodic_advertising_report(
        &self,
        sync_handle: u16,
        tx_power: i8,
        rssi: i8,
        data_status_raw: u8,
        data: &[u8],
    ) {
        let data_status = DataStatus::try_from(data_status_raw).unwrap_or(DataStatus::Complete);
        let mut sync_registry = self.sync_registry.lock().unwrap();
        sync_registry.broadcast_event(PeriodicSyncEvent::PeriodicAdvertisingReport {
            sync_handle,
            tx_power,
            rssi,
            data_status,
            data: data.to_vec(),
        });
    }

    pub fn on_periodic_sync_lost(&self, sync_handle: u16) {
        let mut sync_registry = self.sync_registry.lock().unwrap();
        sync_registry.active_handles.remove(&sync_handle);
        sync_registry
            .broadcast_event(PeriodicSyncEvent::PeriodicAdvertisingSyncLost { sync_handle });
    }

    pub fn on_biginfo_advertising_report(&self, sync_handle: u16, encryption: bool) {
        let mut sync_registry = self.sync_registry.lock().unwrap();
        sync_registry.broadcast_event(PeriodicSyncEvent::BigInfoAdvertisingReport {
            sync_handle,
            encryption,
        });
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use googletest::prelude::*;
    use std::time::Duration;
    use tokio::sync::{mpsc, oneshot};
    use tokio::time::timeout;

    const SUBSCRIBER_EVENT_BUFFER: usize = 10;

    #[googletest::test]
    #[tokio::test]
    async fn test_on_periodic_sync_started_broadcasts_event_and_updates_registry_on_success() {
        // Verify that when periodic sync is successfully established, the manager updates its
        // active handle registry, fulfills the pending request, and broadcasts a notification event.
        let sync_registry = Arc::new(Mutex::new(SyncRegistry::default()));
        let callbacks = PeriodicSyncCallbacks::new(sync_registry.clone());

        let (sender, receiver) = oneshot::channel();
        sync_registry.lock().unwrap().pending_requests.start_sync.insert(123, sender);

        let address = Address::from_be_bytes([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]);
        callbacks.on_periodic_sync_started(
            123,
            0, // success
            1, // handle
            2, // sid
            AddressType::RandomDeviceAddress.into(),
            address,
            3, // advertiser_phy
            4, // interval
        );

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(&PeriodicSyncInfo {
                reg_id: eq(123),
                sync_handle: eq(1),
                advertising_sid: eq(2),
                advertiser_addr_type: eq(AddressType::RandomDeviceAddress),
                advertiser_addr: eq(address),
                advertiser_phy: eq(3),
                periodic_advertising_interval: eq(4),
            })))
        );
        expect_that!(sync_registry.lock().unwrap().active_handles.contains(&1), is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_periodic_sync_started_reports_error_on_failure() {
        // Verify that if periodic sync fails to start, the manager reports the HCI error to the
        // requester and broadcasts a notification event without updating the active handles.
        let sync_registry = Arc::new(Mutex::new(SyncRegistry::default()));
        let callbacks = PeriodicSyncCallbacks::new(sync_registry.clone());

        let (sender, receiver) = oneshot::channel();
        sync_registry.lock().unwrap().pending_requests.start_sync.insert(456, sender);

        callbacks.on_periodic_sync_started(
            456,
            HciStatus::UnknownHciCommand.into(), // failure
            0,
            0,
            AddressType::PublicDeviceAddress.into(),
            Address::default(),
            0,
            0,
        );

        expect_that!(
            receiver.await,
            ok(err(matches_pattern!(&PeriodicSyncError::HciError(eq(
                HciStatus::UnknownHciCommand
            )))))
        );
        expect_that!(sync_registry.lock().unwrap().active_handles.is_empty(), is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_periodic_advertising_report_broadcasts_event_with_received_data() {
        // Verify that receiving a periodic advertising report correctly triggers a broadcast
        // event containing the report's metadata and payload.
        let sync_registry = Arc::new(Mutex::new(SyncRegistry::default()));
        let callbacks = PeriodicSyncCallbacks::new(sync_registry.clone());

        let (subscriber_sender, mut subscriber_receiver) = mpsc::unbounded_channel();
        sync_registry.lock().unwrap().event_subscribers.push(subscriber_sender);

        let data = vec![1, 2, 3];
        callbacks.on_periodic_advertising_report(10, 20, -50, DataStatus::Complete.into(), &data);

        expect_that!(
            timeout(Duration::from_secs(2), subscriber_receiver.recv()).await,
            ok(some(matches_pattern!(PeriodicSyncEvent::PeriodicAdvertisingReport {
                sync_handle: eq(&10),
                tx_power: eq(&20),
                rssi: eq(&-50),
                data_status: eq(&DataStatus::Complete),
                data: eq(&vec![1, 2, 3]),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_periodic_sync_lost_removes_handle_and_broadcasts_event() {
        // Verify that when synchronization is lost, the manager removes the handle from it s
        // active set and broadcasts a lost event to all subscribers.
        let sync_registry = Arc::new(Mutex::new(SyncRegistry::default()));
        sync_registry.lock().unwrap().active_handles.insert(99);
        let callbacks = PeriodicSyncCallbacks::new(sync_registry.clone());

        let (subscriber_sender, mut subscriber_receiver) = mpsc::unbounded_channel();
        sync_registry.lock().unwrap().event_subscribers.push(subscriber_sender);

        callbacks.on_periodic_sync_lost(99);

        expect_that!(sync_registry.lock().unwrap().active_handles.is_empty(), is_true());

        expect_that!(
            timeout(Duration::from_secs(2), subscriber_receiver.recv()).await,
            ok(some(matches_pattern!(&PeriodicSyncEvent::PeriodicAdvertisingSyncLost {
                sync_handle: eq(99)
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_biginfo_advertising_report_broadcasts_event_with_encryption_status() {
        // Verify that receiving a BIG Info report correctly triggers a broadcast event with the
        // specified synchronization handle and encryption status.
        let sync_registry = Arc::new(Mutex::new(SyncRegistry::default()));
        let callbacks = PeriodicSyncCallbacks::new(sync_registry.clone());

        let (subscriber_sender, mut subscriber_receiver) = mpsc::unbounded_channel();
        sync_registry.lock().unwrap().event_subscribers.push(subscriber_sender);

        callbacks.on_biginfo_advertising_report(88, true);

        expect_that!(
            timeout(Duration::from_secs(2), subscriber_receiver.recv()).await,
            ok(some(matches_pattern!(&PeriodicSyncEvent::BigInfoAdvertisingReport {
                sync_handle: eq(88),
                encryption: eq(true)
            })))
        );
    }
}
