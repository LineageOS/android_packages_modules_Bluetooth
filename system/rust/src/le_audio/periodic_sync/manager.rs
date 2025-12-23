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

use std::collections::{HashMap, HashSet};
use tokio::sync::{mpsc, oneshot};

use crate::le_audio::periodic_sync::traits::{PeriodicSyncEvent, PeriodicSyncInfo, Result};

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
