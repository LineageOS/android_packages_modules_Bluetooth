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

//! ISO manager implementation.

#![allow(dead_code)]

use log::warn;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, Weak};
use tokio::sync::{broadcast, mpsc, oneshot};

use crate::le_audio::iso_manager::traits::{
    CigId, IsoConnectionHandle, IsoDataPacket, IsoLinkQuality, Result,
};
use crate::pdl::hci::HciStatus;

// Event data for CIS establishment completion.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct CisEstablishedEvent {
    // CIG ID.
    pub cig_id: CigId,
    // CIS connection handle.
    pub cis_conn_handle: IsoConnectionHandle,
    // CIG sync delay.
    pub cig_sync_delay: u32,
    // CIS sync delay.
    pub cis_sync_delay: u32,
    // Transport latency (Central to Peripheral).
    pub transport_latency_c_to_p: u32,
    // Transport latency (Peripheral to Central).
    pub transport_latency_p_to_c: u32,
    // PHY (Central to Peripheral).
    pub phy_c_to_p: u8,
    // PHY (Peripheral to Central).
    pub phy_p_to_c: u8,
    // Number of subevents.
    pub nse: u8,
    // Burst number (Central to Peripheral).
    pub bn_c_to_p: u8,
    // Burst number (Peripheral to Central).
    pub bn_p_to_c: u8,
    // Flush timeout (Central to Peripheral).
    pub ft_c_to_p: u8,
    // Flush timeout (Peripheral to Central).
    pub ft_p_to_c: u8,
    // Maximum PDU (Central to Peripheral).
    pub max_pdu_c_to_p: u16,
    // Maximum PDU (Peripheral to Central).
    pub max_pdu_p_to_c: u16,
    // ISO interval.
    pub iso_interval: u16,
}

// Event data for CIS disconnection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct CisDisconnectedEvent {
    // Reason for disconnection.
    pub reason: HciStatus,
    // CIG ID.
    pub cig_id: CigId,
    // CIS connection handle.
    pub cis_conn_handle: IsoConnectionHandle,
}

// Event data for setting CIG parameters completion.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct CreateCigCmplEvent {
    // CIG ID.
    pub cig_id: CigId,
    // ISO Connection handles for CIS.
    pub cis_conn_handles: Vec<IsoConnectionHandle>,
}

// Internal state for tracking asynchronous pending requests.
#[derive(Default)]
pub(super) struct PendingRequests {
    // Maps CIG ID to a sender for CIG creation completion results.
    pub create_cig: HashMap<CigId, oneshot::Sender<Result<CreateCigCmplEvent>>>,
    // Maps CIG ID to a sender for CIG removal completion results.
    pub remove_cig: HashMap<CigId, oneshot::Sender<Result<()>>>,
    // Maps CIS connection handle to a sender for CIS establishment completion results.
    pub create_cis: HashMap<IsoConnectionHandle, oneshot::Sender<Result<CisEstablishedEvent>>>,
    // Maps CIS connection handle to a sender for CIS disconnection results.
    pub disconnect_cis: HashMap<IsoConnectionHandle, oneshot::Sender<Result<CisDisconnectedEvent>>>,
    // Maps connection handle to a sender for ISO data path setup results.
    pub setup_iso_data_path: HashMap<IsoConnectionHandle, oneshot::Sender<Result<()>>>,
    // Maps connection handle to a sender for ISO data path removal results.
    pub remove_iso_data_path: HashMap<IsoConnectionHandle, oneshot::Sender<Result<()>>>,
    // Maps connection handle to a sender for ISO link quality results.
    pub read_iso_link_quality:
        HashMap<IsoConnectionHandle, oneshot::Sender<Result<IsoLinkQuality>>>,
}

// Internal state and subscribers for a Connected Isochronous Stream (CIS).
#[derive(Default)]
pub(super) struct CisState {
    // Active data subscribers for this stream.
    pub data_subscribers: Vec<mpsc::Sender<IsoDataPacket>>,
    // Reference to the internal CIS state for synchronization.
    pub inner: Option<Weak<CisInner>>,
    // Sender used to notify listeners when the stream is disconnected externally.
    pub disconnected_sender: Option<broadcast::Sender<HciStatus>>,
}

// Internal state for a Connected Isochronous Group (CIG).
#[derive(Default)]
pub(super) struct CigState {
    // Reference to the internal CIG state for synchronization.
    pub inner: Option<Weak<CigInner>>,
}

// --- Inner structs for RAII and proper resource management ---

#[derive(Debug)]
pub(super) struct CisInner {
    // CIS connection handle.
    pub conn_handle: IsoConnectionHandle,
    // Whether the stream has been terminated.
    pub terminated: AtomicBool,
    // Broadcast sender to notify asynchronous listeners of disconnection.
    pub disconnected_sender: broadcast::Sender<HciStatus>,
    // The reason for the disconnection.
    pub disconnect_reason: Mutex<Option<HciStatus>>,
}

#[derive(Debug)]
pub(super) struct CigInner {
    // CIG identifier.
    pub cig_id: CigId,
    // Whether the group has been terminated.
    pub terminated: AtomicBool,
}

// Registry for tracking pending requests and active event subscribers.
#[derive(Default)]
pub(super) struct IsoRegistry {
    // Ongoing asynchronous requests awaiting a response from the Native IsoManager.
    pub pending_requests: PendingRequests,
    // Active CIG states.
    pub cigs: HashMap<CigId, CigState>,
    // Active CIS states.
    pub cis: HashMap<IsoConnectionHandle, CisState>,
}

impl IsoRegistry {
    pub fn dispatch_cis_data(
        &mut self,
        cis_conn_handle: IsoConnectionHandle,
        packet: IsoDataPacket,
    ) {
        if let Some(state) = self.cis.get_mut(&cis_conn_handle) {
            state.data_subscribers.retain(|sender| match sender.try_send(packet.clone()) {
                Ok(_) => true,
                Err(mpsc::error::TrySendError::Closed(_)) => false,
                Err(mpsc::error::TrySendError::Full(_)) => {
                    warn!("CIS {} data buffer full, skipping.", cis_conn_handle);
                    true
                }
            });
        }
    }

    pub fn dispatch_cis_disconnected(
        &mut self,
        cis_conn_handle: IsoConnectionHandle,
        reason: HciStatus,
    ) {
        let Some(state) = self.cis.remove(&cis_conn_handle) else {
            return;
        };

        let Some(inner) = state.inner.and_then(|weak_inner| weak_inner.upgrade()) else {
            return;
        };

        if !inner.terminated.swap(true, Ordering::SeqCst) {
            *inner.disconnect_reason.lock().unwrap() = Some(reason);
            if let Some(sender) = state.disconnected_sender {
                let _ = sender.send(reason);
            }
        }
    }
}
