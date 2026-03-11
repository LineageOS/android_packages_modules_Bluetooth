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

//! Periodic sync manager implementation.

use cxx::UniquePtr;
use log::{error, info, warn};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};
use tokio::time::timeout;
use tokio_stream::wrappers::ReceiverStream;

use crate::le_audio::periodic_sync::ffi::{inner_ffi as pa_ffi, PeriodicSyncCallbacks};
use crate::le_audio::periodic_sync::traits::{
    PaCreateSyncParams, PeriodicSyncError, PeriodicSyncEvent, PeriodicSyncInfo,
    PeriodicSyncManager, Result,
};

// Pending requests for the manager to process.
#[derive(Default)]
pub(super) struct PendingRequests {
    // Maps reg_id to sender.
    pub start_sync: HashMap<i32, oneshot::Sender<Result<PeriodicSyncInfo>>>,
}

// Registration information of periodic synchronization.
#[derive(Default)]
pub(super) struct SyncRegistry {
    // Ongoing PA sync requests.
    pub pending_requests: PendingRequests,
    // Currently established PA sync handles.
    pub active_handles: HashSet<u16>,
    // Active event subscribers.
    pub event_subscribers: Vec<mpsc::Sender<PeriodicSyncEvent>>,
}

impl SyncRegistry {
    // Broadcasts an event to all subscribers.
    pub fn broadcast_event(&mut self, event: PeriodicSyncEvent) {
        self.event_subscribers.retain(|sender| match sender.try_send(event.clone()) {
            Ok(_) => true,
            Err(mpsc::error::TrySendError::Closed(_)) => false,
            Err(mpsc::error::TrySendError::Full(_)) => {
                panic!("Event subscriber buffer full, client is stuck! Event: {:?}", event);
            }
        });
    }
}

/// Concrete implementation of PeriodicSyncManager using the FFI shim.
pub struct PeriodicSyncManagerImpl {
    shim: Mutex<UniquePtr<pa_ffi::BleScannerInterfaceShim>>,
    sync_registry: Arc<Mutex<SyncRegistry>>,
}

impl PeriodicSyncManagerImpl {
    /// Creates a new `PeriodicSyncManager` instance.
    pub fn new() -> Self {
        let shim = Mutex::new(pa_ffi::get_ble_scanner_interface_shim());
        let sync_registry = Arc::new(Mutex::new(SyncRegistry::default()));

        let callbacks = Box::new(PeriodicSyncCallbacks::new(sync_registry.clone()));
        const SCANNER_CLIENT_ID_LE_AUDIO: u8 = 0x01;
        shim.lock()
            .unwrap()
            .pin_mut()
            .register_callbacks_native(callbacks, SCANNER_CLIENT_ID_LE_AUDIO);

        Self { shim, sync_registry }
    }
}

impl Default for PeriodicSyncManagerImpl {
    fn default() -> Self {
        Self::new()
    }
}

const DEFAULT_TIMEOUT: Duration = Duration::from_secs(2);
const HCI_TIMEOUT_UNIT_MS: u128 = 10;
const MPSC_CHANNEL_BUFFER_SIZE: usize = 10;

impl PeriodicSyncManager for PeriodicSyncManagerImpl {
    type EventStream = ReceiverStream<PeriodicSyncEvent>;

    async fn start_sync(&self, params: PaCreateSyncParams) -> Result<PeriodicSyncInfo> {
        // Use broadcast_id (which maps to reg_id in start_sync) for pending request tracking.
        let reg_id = params.broadcast_id as i32;

        info!("start_sync: reg_id: {}, addr: {}", reg_id, params.advertiser_addr);

        let (sender, receiver) = oneshot::channel();

        {
            let mut sync_registry = self.sync_registry.lock().unwrap();
            if sync_registry.pending_requests.start_sync.contains_key(&reg_id) {
                warn!("Sync request for reg_id {} is already in progress", reg_id);
                return Err(PeriodicSyncError::Internal);
            }
            sync_registry.pending_requests.start_sync.insert(reg_id, sender);
        }

        self.shim.lock().unwrap().pin_mut().start_sync(
            params.advertising_sid,
            params.advertiser_addr,
            params.advertiser_addr_type.into(),
            params.skip,
            (params.sync_timeout.as_millis() / HCI_TIMEOUT_UNIT_MS) as u16,
            reg_id,
        );

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(PeriodicSyncError::Internal),
            Err(_) => Err(PeriodicSyncError::Timeout),
        };

        self.sync_registry.lock().unwrap().pending_requests.start_sync.remove(&reg_id);

        if let Err(error) = &result {
            error!("start_sync failed: reg_id: {}, error: {:?}", reg_id, error);
        }
        result
    }

    async fn stop_sync(&self, handle: u16) -> Result<()> {
        info!("stop_sync: handle: {}", handle);
        let mut sync_registry = self.sync_registry.lock().unwrap();
        if !sync_registry.active_handles.remove(&handle) {
            error!("stop_sync failed: invalid handle: {}", handle);
            return Err(PeriodicSyncError::InvalidHandle);
        }
        self.shim.lock().unwrap().pin_mut().stop_sync(handle);
        Ok(())
    }

    fn subscribe_events(&self) -> Self::EventStream {
        let (sender, receiver) = mpsc::channel(MPSC_CHANNEL_BUFFER_SIZE);
        self.sync_registry.lock().unwrap().event_subscribers.push(sender);
        ReceiverStream::new(receiver)
    }
}
