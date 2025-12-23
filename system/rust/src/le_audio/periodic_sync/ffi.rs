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
            phy: u8,
            sync_interval: u16,
        );

        #[cxx_name = "OnPeriodicSyncReport"]
        fn on_periodic_sync_report(
            self: &PeriodicSyncCallbacks,
            sync_handle: u16,
            tx_power: i8,
            rssi: i8,
            data_status: u8,
            data: &[u8],
        );

        #[cxx_name = "OnPeriodicSyncLost"]
        fn on_periodic_sync_lost(self: &PeriodicSyncCallbacks, sync_handle: u16);

        #[cxx_name = "OnBigInfoReport"]
        fn on_big_info_report(self: &PeriodicSyncCallbacks, sync_handle: u16, encrypted: bool);
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
        phy: u8,
        sync_interval: u16,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let advertiser_addr_type = AddressType::try_from(advertiser_addr_type_raw)
            .unwrap_or(AddressType::PublicDeviceAddress);

        let mut sync_registry = self.sync_registry.lock().unwrap();

        let result = if status == HciStatus::Success {
            sync_registry.active_handles.insert(sync_handle);
            Ok(PeriodicSyncInfo {
                reg_id,
                status,
                sync_handle,
                advertising_sid,
                advertiser_addr_type,
                advertiser_addr,
                phy,
                sync_interval,
            })
        } else {
            Err(PeriodicSyncError::HciError(status))
        };

        if let Some(sender) = sync_registry.pending_requests.start_sync.remove(&reg_id) {
            let _ = sender.send(result);
        }
    }

    pub fn on_periodic_sync_report(
        &self,
        sync_handle: u16,
        tx_power: i8,
        rssi: i8,
        data_status_raw: u8,
        data: &[u8],
    ) {
        let data_status = DataStatus::try_from(data_status_raw).unwrap_or(DataStatus::Complete);
        let mut sync_registry = self.sync_registry.lock().unwrap();
        sync_registry.broadcast_event(PeriodicSyncEvent::PaReport {
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
        sync_registry.broadcast_event(PeriodicSyncEvent::PaSyncLost { sync_handle });
    }

    pub fn on_big_info_report(&self, sync_handle: u16, encrypted: bool) {
        let mut sync_registry = self.sync_registry.lock().unwrap();
        sync_registry.broadcast_event(PeriodicSyncEvent::BigInfoReport { sync_handle, encrypted });
    }
}
