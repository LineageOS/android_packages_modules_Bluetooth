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
use tokio_stream::wrappers::UnboundedReceiverStream;

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
    pub event_subscribers: Vec<mpsc::UnboundedSender<PeriodicSyncEvent>>,
}

impl SyncRegistry {
    // Broadcasts an event to all subscribers.
    pub fn broadcast_event(&mut self, event: PeriodicSyncEvent) {
        self.event_subscribers.retain(|sender| match sender.send(event.clone()) {
            Ok(_) => true,
            Err(mpsc::error::SendError(_)) => false,
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

impl PeriodicSyncManager for PeriodicSyncManagerImpl {
    type EventStream = UnboundedReceiverStream<PeriodicSyncEvent>;

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
        let (sender, receiver) = mpsc::unbounded_channel();
        self.sync_registry.lock().unwrap().event_subscribers.push(sender);
        UnboundedReceiverStream::new(receiver)
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use futures::StreamExt;
    use googletest::prelude::*;
    use tokio::spawn;
    use tokio::time::{sleep, timeout};

    use crate::pdl::hci::AddressType;
    use crate::Address;

    #[googletest::test]
    fn test_new_initializes_manager_with_valid_shim() {
        // Verify that creating a new manager correctly initializes the underlying FFI shim.
        let manager = PeriodicSyncManagerImpl::new();
        expect_that!(manager.shim.lock().unwrap().is_null(), is_false());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_broadcast_event_reaches_all_active_subscribers() {
        // Verify that multiple registered event subscribers all receive generated events.
        let manager = PeriodicSyncManagerImpl::new();
        let mut stream1 = manager.subscribe_events();
        let mut stream2 = manager.subscribe_events();

        // Broadcast a simulated event.
        let event = PeriodicSyncEvent::PaSyncLost { sync_handle: 42 };
        manager.sync_registry.lock().unwrap().broadcast_event(event);

        // Verify both subscribers receive it.
        expect_that!(
            timeout(DEFAULT_TIMEOUT, stream1.next()).await,
            ok(some(matches_pattern!(&PeriodicSyncEvent::PaSyncLost { sync_handle: eq(42) })))
        );
        expect_that!(
            timeout(DEFAULT_TIMEOUT, stream2.next()).await,
            ok(some(matches_pattern!(&PeriodicSyncEvent::PaSyncLost { sync_handle: eq(42) })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_broadcast_event_removes_dropped_subscribers() {
        // Verify that the manager automatically cleans up and removes event subscribers that have
        // been dropped.
        let manager = PeriodicSyncManagerImpl::new();

        {
            let _stream = manager.subscribe_events();
            expect_that!(manager.sync_registry.lock().unwrap().event_subscribers.len(), eq(1));
            // _stream dropped here.
        }

        // Broadcast to trigger cleanup logic.
        manager
            .sync_registry
            .lock()
            .unwrap()
            .broadcast_event(PeriodicSyncEvent::PaSyncLost { sync_handle: 0 });

        // Verify dead subscriber is removed.
        expect_that!(manager.sync_registry.lock().unwrap().event_subscribers.len(), eq(0));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_start_sync_removes_pending_request_on_timeout() {
        // Verify that a synchronization request that times out is properly removed from the
        // pending requests registry.
        let manager = PeriodicSyncManagerImpl::new();
        let params = PaCreateSyncParams {
            broadcast_id: 99,
            advertising_sid: 1,
            advertiser_addr: Address::default(),
            advertiser_addr_type: AddressType::PublicDeviceAddress,
            skip: 0,
            sync_timeout: Duration::from_millis(2000),
        };

        // This will timeout because we don't trigger the FFI callback.
        let result = timeout(DEFAULT_TIMEOUT, manager.start_sync(params)).await;
        expect_that!(result, ok(err(anything())));

        // Verify pending request is removed after timeout.
        expect_that!(
            manager.sync_registry.lock().unwrap().pending_requests.start_sync.is_empty(),
            is_true()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_stop_sync_removes_active_handle_on_success() {
        // Verify that stopping a synchronization with a valid handle successfully removes it from
        // the active handles set.
        let manager = PeriodicSyncManagerImpl::new();
        manager.sync_registry.lock().unwrap().active_handles.insert(123);

        let result = timeout(DEFAULT_TIMEOUT, manager.stop_sync(123)).await;
        expect_that!(result, ok(ok(anything())));
        expect_that!(manager.sync_registry.lock().unwrap().active_handles.is_empty(), is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_stop_sync_returns_error_for_unknown_handle() {
        // Verify that attempting to stop synchronization with a handle that is not active returns
        // an invalid handle error.
        let manager = PeriodicSyncManagerImpl::new();

        let result = timeout(DEFAULT_TIMEOUT, manager.stop_sync(456)).await;
        expect_that!(result, ok(err(eq(&PeriodicSyncError::InvalidHandle))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_start_sync_returns_timeout_error_when_no_response() {
        // Verify that start_sync returns a timeout error if the underlying stack does not respond
        // within the expected duration.
        let manager = PeriodicSyncManagerImpl::new();
        let params = PaCreateSyncParams {
            broadcast_id: 1,
            advertising_sid: 1,
            advertiser_addr: Address::from_be_bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x01]),
            advertiser_addr_type: AddressType::PublicDeviceAddress,
            skip: 0,
            sync_timeout: Duration::from_secs(2),
        };

        let result = timeout(DEFAULT_TIMEOUT, manager.start_sync(params)).await;
        expect_that!(result, ok(err(eq(&PeriodicSyncError::Timeout))));
        expect_that!(
            manager.sync_registry.lock().unwrap().pending_requests.start_sync.is_empty(),
            is_true()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_start_sync_fails_when_duplicate_broadcast_id_provided() {
        // Verify that attempting to start a second synchronization with the same broadcast ID
        // while one is already in progress returns an internal error.
        let manager = Arc::new(PeriodicSyncManagerImpl::new());
        let params = PaCreateSyncParams {
            broadcast_id: 1,
            advertising_sid: 1,
            advertiser_addr: Address::from_be_bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x01]),
            advertiser_addr_type: AddressType::PublicDeviceAddress,
            skip: 0,
            sync_timeout: Duration::from_secs(2),
        };

        let manager_clone = manager.clone();
        let params_clone = params.clone();
        // Start the first sync in a background task.
        spawn(async move {
            let _ = timeout(DEFAULT_TIMEOUT, manager_clone.start_sync(params_clone)).await;
        });

        // Ensure the first task has enough time to acquire the lock and register.
        sleep(Duration::from_millis(20)).await;

        // Attempt a second sync with the same broadcast_id.
        let second_sync_result = timeout(DEFAULT_TIMEOUT, manager.start_sync(params)).await;

        expect_that!(second_sync_result, ok(err(eq(&PeriodicSyncError::Internal))));
    }
}
