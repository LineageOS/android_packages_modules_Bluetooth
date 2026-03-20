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

use cxx::UniquePtr;
use log::{error, info, warn};
use std::collections::HashMap;
use std::fmt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;
use tokio::spawn;
use tokio::sync::{broadcast, mpsc, oneshot};
use tokio::time::timeout;
use tokio_stream::wrappers::ReceiverStream;

use crate::le_audio::iso_manager::ffi::{inner_ffi as iso_ffi, IsoBigCallbacks, IsoCigCallbacks};
use crate::le_audio::iso_manager::traits::{
    BigCreateSyncParameters, BigHandle, BigSource, BigSync, Bis, Cig, CigId, CigParameters, Cis,
    CreateBigParameters, CreateCisParameters, IsoConnectionHandle, IsoDataPacket, IsoDataStream,
    IsoLinkQuality, IsoManager, IsoManagerError, RemoveIsoDataPathDirection, Result,
    SetupIsoDataPathParameters,
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

// Event data for BIG creation completion.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct CreateBigCmplEvent {
    // BIG handle.
    pub big_handle: BigHandle,
    // Sync delay.
    pub big_sync_delay: u32,
    // Transport latency.
    pub transport_latency_big: u32,
    // PHY.
    pub phy: u8,
    // Number of subevents.
    pub nse: u8,
    // Burst number.
    pub bn: u8,
    // Pre-transmission offset.
    pub pto: u8,
    // Immediate repetition count.
    pub irc: u8,
    // Maximum PDU.
    pub max_pdu: u16,
    // ISO interval.
    pub iso_interval: u16,
    // Connection handles.
    pub bis_conn_handles: Vec<IsoConnectionHandle>,
}

// Event data for BIG sync establishment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct BigSyncEstablishedEvent {
    // BIG handle.
    pub big_handle: BigHandle,
    // Transport latency.
    pub transport_latency_big: u32,
    // Number of subevents.
    pub nse: u8,
    // Burst number.
    pub bn: u8,
    // Pre-transmission offset.
    pub pto: u8,
    // Immediate repetition count.
    pub irc: u8,
    // Maximum PDU.
    pub max_pdu: u16,
    // ISO interval.
    pub iso_interval: u16,
    // Connection handles.
    pub bis_conn_handles: Vec<IsoConnectionHandle>,
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
    // Maps BIG handle to a sender for BIG creation completion results.
    pub create_big: HashMap<BigHandle, oneshot::Sender<Result<CreateBigCmplEvent>>>,
    // Maps BIG handle to a sender for BIG termination completion results.
    pub terminate_big: HashMap<BigHandle, oneshot::Sender<Result<()>>>,
    // Maps BIG handle to a sender for BIG sync establishment results.
    pub big_create_sync: HashMap<BigHandle, oneshot::Sender<Result<BigSyncEstablishedEvent>>>,
    // Maps BIG handle to a sender for BIG sync termination completion results.
    pub big_terminate_sync: HashMap<BigHandle, oneshot::Sender<Result<()>>>,
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
    pub inner: Weak<CisInner>,
    // Sender used to notify listeners when the stream is disconnected externally.
    pub disconnected_sender: Option<broadcast::Sender<HciStatus>>,
}

// Internal state for a Connected Isochronous Group (CIG).
#[derive(Default)]
pub(super) struct CigState {
    // Reference to the internal CIG state for synchronization.
    pub inner: Weak<CigInner>,
}

// Internal state and subscribers for a Broadcast Isochronous Stream (BIS).
#[derive(Default)]
pub(super) struct BisState {
    // Shared termination flag used to synchronize resource state.
    pub data_subscribers: Vec<mpsc::Sender<IsoDataPacket>>,
}

// Internal state and signals for a Broadcast Isochronous Group (BIG).
#[derive(Default)]
pub(super) struct BigState {
    // Reference to the internal BIG state for synchronization.
    pub inner: Weak<BigInner>,
    // Sender used to notify listeners when the stream is lost externally.
    pub lost_sender: Option<broadcast::Sender<HciStatus>>,
}

// --- Inner structs for RAII and proper resource management ---

#[derive(Debug)]
pub(super) struct CisInner {
    // CIS connection handle.
    pub conn_handle: IsoConnectionHandle,
    // Weak reference to the manager to avoid circular dependencies.
    pub manager: Weak<IsoManagerImpl>,
    // Whether the stream has been terminated.
    pub terminated: AtomicBool,
    // Broadcast sender to notify asynchronous listeners of disconnection.
    pub disconnected_sender: broadcast::Sender<HciStatus>,
    // The reason for the disconnection.
    pub disconnect_reason: Mutex<Option<HciStatus>>,
}

impl Drop for CisInner {
    fn drop(&mut self) {
        if self.terminated.swap(true, Ordering::SeqCst) {
            return;
        }
        let Some(manager) = self.manager.upgrade() else {
            return;
        };
        let conn_handle = self.conn_handle;
        spawn(async move {
            info!("drop: cis_conn_handle: {}", conn_handle);
            let _ = manager
                .disconnect_cis_internal(conn_handle, HciStatus::RemoteUserTerminatedConnection)
                .await;
        });
    }
}
#[derive(Debug)]
pub(super) struct CigInner {
    // CIG identifier.
    pub cig_id: CigId,
    // Weak reference to the manager.
    pub manager: Weak<IsoManagerImpl>,
    // The active CIS connections belonging to this group.
    pub cis_connections: Mutex<Vec<CisImpl>>,
    // Whether the group has been terminated.
    pub terminated: AtomicBool,
}

impl Drop for CigInner {
    fn drop(&mut self) {
        if self.terminated.swap(true, Ordering::SeqCst) {
            return;
        }
        let Some(manager) = self.manager.upgrade() else {
            return;
        };
        let cig_id = self.cig_id;
        let cis_connections = self.cis_connections.lock().unwrap().drain(..).collect::<Vec<_>>();
        spawn(async move {
            info!("drop: cig_id: {}", cig_id);
            drop(cis_connections); // Triggers Drop for each CIS.
            let _ = manager.remove_cig_internal(cig_id, true).await;
        });
    }
}

#[derive(Debug)]
pub(super) struct BigInner {
    // BIG handle.
    pub big_handle: BigHandle,
    // Weak reference to the manager.
    pub manager: Weak<IsoManagerImpl>,
    // The active BIS connections belonging to this group.
    pub bis_connections: Vec<BisImpl>,
    // Whether the group has been terminated.
    pub terminated: Arc<AtomicBool>,
    // Broadcast sender to notify asynchronous listeners when the group is lost.
    pub lost_sender: broadcast::Sender<HciStatus>,
    // The reason why the group was lost.
    pub lost_reason: Mutex<Option<HciStatus>>,
    // Whether this BIG is a source or a sync.
    pub is_source: bool,
}

impl Drop for BigInner {
    fn drop(&mut self) {
        if self.terminated.swap(true, Ordering::SeqCst) {
            return;
        }
        let Some(manager) = self.manager.upgrade() else {
            return;
        };
        let big_handle = self.big_handle;
        let is_source = self.is_source;
        spawn(async move {
            info!("drop: big_handle: {}", big_handle);
            if is_source {
                let _ = manager
                    .terminate_big_internal(big_handle, HciStatus::RemoteUserTerminatedConnection)
                    .await;
            } else {
                let _ = manager.big_terminate_sync_internal(big_handle).await;
            }
        });
    }
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
    // Active BIG states.
    pub bigs: HashMap<BigHandle, BigState>,
    // Active BIS states.
    pub bis: HashMap<IsoConnectionHandle, BisState>,
}

impl IsoRegistry {
    pub fn allocate_cid_id(&self) -> Result<u8> {
        // IDs range from 0x00 to 0xEF per Bluetooth specification.
        (0..=0xEF)
            .map(|id| id as u8)
            .find(|&id| {
                !self.cigs.contains_key(&CigId::from_masked(id))
                    && !self.bigs.contains_key(&BigHandle::from_masked(id))
            })
            .ok_or(IsoManagerError::OutOfResources)
    }

    pub fn dispatch_cis_data(
        &mut self,
        cis_conn_handle: IsoConnectionHandle,
        time_stamp: Option<Duration>,
        seq_nb: u16,
        data: &[u8],
    ) {
        if let Some(state) = self.cis.get_mut(&cis_conn_handle) {
            state.data_subscribers.retain(|sender| {
                let packet = IsoDataPacket { time_stamp, seq_nb, data: data.to_vec() };
                match sender.try_send(packet) {
                    Ok(_) => true,
                    Err(mpsc::error::TrySendError::Closed(_)) => false,
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        warn!("CIS {} data buffer full, skipping.", cis_conn_handle);
                        true
                    }
                }
            });
        }
    }

    pub fn dispatch_bis_data(
        &mut self,
        bis_conn_handle: IsoConnectionHandle,
        time_stamp: Option<Duration>,
        seq_nb: u16,
        data: &[u8],
    ) {
        if let Some(state) = self.bis.get_mut(&bis_conn_handle) {
            state.data_subscribers.retain(|sender| {
                let packet = IsoDataPacket { time_stamp, seq_nb, data: data.to_vec() };
                match sender.try_send(packet) {
                    Ok(_) => true,
                    Err(mpsc::error::TrySendError::Closed(_)) => false,
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        warn!("BIS {} data buffer full, skipping.", bis_conn_handle);
                        true
                    }
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

        let Some(inner) = state.inner.upgrade() else {
            return;
        };

        if !inner.terminated.swap(true, Ordering::SeqCst) {
            *inner.disconnect_reason.lock().unwrap() = Some(reason);
            if let Some(sender) = state.disconnected_sender {
                let _ = sender.send(reason);
            }
        }
    }

    pub fn dispatch_big_sync_event(&mut self, big_handle: BigHandle, reason: HciStatus) {
        let Some(state) = self.bigs.remove(&big_handle) else {
            return;
        };

        let Some(inner) = state.inner.upgrade() else {
            return;
        };

        if !inner.terminated.swap(true, Ordering::SeqCst) {
            *inner.lost_reason.lock().unwrap() = Some(reason);
            if let Some(sender) = state.lost_sender {
                let _ = sender.send(reason);
            }
        }
    }
}

/// Concrete implementation of a CIS resource.
#[derive(Clone, Debug)]
pub struct CisImpl(Arc<CisInner>);

impl IsoDataStream for CisImpl {
    type DataStream = ReceiverStream<IsoDataPacket>;

    fn conn_handle(&self) -> IsoConnectionHandle {
        self.0.conn_handle
    }

    fn write(&self, data: &[u8]) {
        if self.0.terminated.load(Ordering::SeqCst) {
            return;
        }
        if let Some(manager) = self.0.manager.upgrade() {
            manager.send_iso_data_internal(self.0.conn_handle, data);
        }
    }

    fn read(&self) -> Self::DataStream {
        if let Some(manager) = self.0.manager.upgrade() {
            manager.subscribe_cis_data(self.0.conn_handle)
        } else {
            let (_, receiver) = mpsc::channel(1);
            ReceiverStream::new(receiver)
        }
    }

    async fn setup_iso_data_path(&self, path_params: SetupIsoDataPathParameters) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("setup_iso_data_path: cis_conn_handle: {}", self.0.conn_handle);
        manager.setup_iso_data_path_internal(self.0.conn_handle, path_params).await
    }

    async fn remove_iso_data_path(&self, data_path_dir: RemoveIsoDataPathDirection) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("remove_iso_data_path: cis_conn_handle: {}", self.0.conn_handle);
        manager.remove_iso_data_path_internal(self.0.conn_handle, data_path_dir).await
    }
}

impl Cis for CisImpl {
    async fn disconnect(&self, reason: HciStatus) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("disconnect: cis_conn_handle: {}", self.0.conn_handle);
        let result = manager.disconnect_cis_internal(self.0.conn_handle, reason).await;
        if result.is_ok() {
            self.0.terminated.store(true, Ordering::SeqCst);
        }
        result
    }

    async fn read_iso_link_quality(&self) -> Result<IsoLinkQuality> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("read_iso_link_quality: cis_conn_handle: {}", self.0.conn_handle);
        manager.read_iso_link_quality_internal(self.0.conn_handle).await
    }

    async fn on_disconnected(&self) -> HciStatus {
        let mut receiver = self.0.disconnected_sender.subscribe();
        if self.0.terminated.load(Ordering::SeqCst) {
            return self.0.disconnect_reason.lock().unwrap().unwrap_or(HciStatus::StatusUnknown);
        }
        match receiver.recv().await {
            Ok(reason) => {
                *self.0.disconnect_reason.lock().unwrap() = Some(reason);
                reason
            }
            Err(_) => HciStatus::StatusUnknown,
        }
    }
}

/// Concrete implementation of a CIG resource.
#[derive(Clone, Debug)]
pub struct CigImpl(Arc<CigInner>);

impl Cig for CigImpl {
    type Cis = CisImpl;

    fn cig_id(&self) -> CigId {
        self.0.cig_id
    }

    fn cis_connections(&self) -> Vec<Self::Cis> {
        let cis_list = self.0.cis_connections.lock().unwrap().clone();
        info!("cis_connections: cig_id: {}, count: {}", self.0.cig_id, cis_list.len());
        cis_list
    }

    fn get_cis_connection(&self, cis_conn_handle: IsoConnectionHandle) -> Option<Self::Cis> {
        self.0
            .cis_connections
            .lock()
            .unwrap()
            .iter()
            .find(|cis_connection| cis_connection.conn_handle() == cis_conn_handle)
            .cloned()
    }

    async fn reconfigure(&self, params: CigParameters) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("reconfigure: cig_id: {}", self.0.cig_id);
        let _event = manager.reconfigure_cig_internal(self.0.cig_id, params).await?;
        Ok(())
    }

    async fn remove(&self, force: bool) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("remove: cig_id: {}", self.0.cig_id);
        let result = manager.remove_cig_internal(self.0.cig_id, force).await;
        if result.is_ok() {
            self.0.terminated.store(true, Ordering::SeqCst);
        }
        result
    }

    async fn create_cis(&self, conn_params: CreateCisParameters) -> Result<Vec<Self::Cis>> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!(
            "create_cis: cig_id: {}, count: {}",
            self.0.cig_id,
            conn_params.conn_handle_pairs.len()
        );
        let events = manager.create_cis_internal(conn_params).await?;
        let mut cis_list = self.0.cis_connections.lock().unwrap();
        let mut result = Vec::new();
        for event in events {
            let (sender, _) = broadcast::channel(1);
            let cis_inner = Arc::new(CisInner {
                conn_handle: event.cis_conn_handle,
                manager: self.0.manager.clone(),
                terminated: AtomicBool::new(false),
                disconnected_sender: sender.clone(),
                disconnect_reason: Mutex::new(None),
            });
            {
                let mut registry = manager.iso_registry.lock().unwrap();
                registry.cis.insert(
                    event.cis_conn_handle,
                    CisState {
                        data_subscribers: Vec::new(),
                        inner: Arc::downgrade(&cis_inner),
                        disconnected_sender: Some(sender),
                    },
                );
            }
            let cis = CisImpl(cis_inner);
            cis_list.push(cis.clone());
            result.push(cis);
        }
        Ok(result)
    }
}

/// Concrete implementation of a BIS resource.
///
/// Note: BisImpl does not use the `Arc<Inner>` pattern because BIS handles are managed
/// collectively by their parent BIG. Unlike CIS, individual BIS do not have independent
/// HCI disconnection commands or complex RAII state that needs to be synchronized
/// across clones. Its lifecycle is effectively tied to the parent BIG's termination flag.
#[derive(Clone, Debug)]
pub struct BisImpl {
    conn_handle: IsoConnectionHandle,
    manager: Weak<IsoManagerImpl>,
    terminated: Arc<AtomicBool>,
}

impl IsoDataStream for BisImpl {
    type DataStream = ReceiverStream<IsoDataPacket>;
    fn conn_handle(&self) -> IsoConnectionHandle {
        self.conn_handle
    }

    fn write(&self, data: &[u8]) {
        if self.terminated.load(Ordering::SeqCst) {
            return;
        }
        if let Some(manager) = self.manager.upgrade() {
            manager.send_iso_data_internal(self.conn_handle, data);
        }
    }

    fn read(&self) -> Self::DataStream {
        if let Some(manager) = self.manager.upgrade() {
            manager.subscribe_bis_data(self.conn_handle)
        } else {
            let (_, receiver) = mpsc::channel(1);
            ReceiverStream::new(receiver)
        }
    }

    async fn setup_iso_data_path(&self, path_params: SetupIsoDataPathParameters) -> Result<()> {
        if self.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("setup_iso_data_path: bis_conn_handle: {}", self.conn_handle);
        manager.setup_iso_data_path_internal(self.conn_handle, path_params).await
    }

    async fn remove_iso_data_path(&self, data_path_dir: RemoveIsoDataPathDirection) -> Result<()> {
        if self.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("remove_iso_data_path: bis_conn_handle: {}", self.conn_handle);
        manager.remove_iso_data_path_internal(self.conn_handle, data_path_dir).await
    }
}

impl Bis for BisImpl {}

/// Concrete implementation of a BIG Source resource.
#[derive(Clone, Debug)]
pub struct BigSourceImpl(Arc<BigInner>);

impl BigSource for BigSourceImpl {
    type Bis = BisImpl;

    fn big_handle(&self) -> BigHandle {
        self.0.big_handle
    }

    fn bis_connections(&self) -> Vec<Self::Bis> {
        info!(
            "bis_connections: big_handle: {}, count: {}",
            self.0.big_handle,
            self.0.bis_connections.len()
        );
        self.0.bis_connections.clone()
    }

    fn get_bis_connection(&self, bis_conn_handle: IsoConnectionHandle) -> Option<Self::Bis> {
        self.0
            .bis_connections
            .iter()
            .find(|bis_connection| bis_connection.conn_handle() == bis_conn_handle)
            .cloned()
    }

    async fn terminate(&self, reason: HciStatus) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("terminate: big_handle: {}, reason: {:?}", self.0.big_handle, reason);
        let result = manager.terminate_big_internal(self.0.big_handle, reason).await;
        if result.is_ok() {
            self.0.terminated.store(true, Ordering::SeqCst);
        }
        result
    }
}

/// Concrete implementation of a BIG Sync resource.
#[derive(Clone, Debug)]
pub struct BigSyncImpl(Arc<BigInner>);

impl BigSync for BigSyncImpl {
    type Bis = BisImpl;

    fn big_handle(&self) -> BigHandle {
        self.0.big_handle
    }

    fn bis_connections(&self) -> Vec<Self::Bis> {
        info!(
            "bis_connections: big_handle: {}, count: {}",
            self.0.big_handle,
            self.0.bis_connections.len()
        );
        self.0.bis_connections.clone()
    }

    fn get_bis_connection(&self, bis_conn_handle: IsoConnectionHandle) -> Option<Self::Bis> {
        self.0
            .bis_connections
            .iter()
            .find(|bis_connection| bis_connection.conn_handle() == bis_conn_handle)
            .cloned()
    }

    async fn terminate(&self) -> Result<()> {
        if self.0.terminated.load(Ordering::SeqCst) {
            return Err(IsoManagerError::Disconnected);
        }
        let manager = self.0.manager.upgrade().ok_or(IsoManagerError::Disconnected)?;
        info!("terminate: big_handle: {}", self.0.big_handle);
        let result = manager.big_terminate_sync_internal(self.0.big_handle).await;
        if result.is_ok() {
            self.0.terminated.store(true, Ordering::SeqCst);
        }
        result
    }

    async fn on_lost(&self) -> HciStatus {
        let mut receiver = self.0.lost_sender.subscribe();
        if self.0.terminated.load(Ordering::SeqCst) {
            return self.0.lost_reason.lock().unwrap().unwrap_or(HciStatus::StatusUnknown);
        }
        match receiver.recv().await {
            Ok(reason) => {
                *self.0.lost_reason.lock().unwrap() = Some(reason);
                reason
            }
            Err(_) => HciStatus::StatusUnknown,
        }
    }
}

/// Concrete implementation of IsoManager using the FFI shim.
pub struct IsoManagerImpl {
    shim: Mutex<UniquePtr<iso_ffi::IsoManagerShim>>,
    iso_registry: Arc<Mutex<IsoRegistry>>,
}

impl fmt::Debug for IsoManagerImpl {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("IsoManagerImpl").finish()
    }
}

const DEFAULT_TIMEOUT: Duration = Duration::from_secs(2);
const HCI_TIMEOUT_UNIT_MS: u128 = 10;
const MPSC_CHANNEL_BUFFER_SIZE: usize = 10;
struct CisConfigs {
    cis_id: Vec<u8>,
    max_sdu_c_to_p: Vec<u16>,
    max_sdu_p_to_c: Vec<u16>,
    phy_c_to_p: Vec<u8>,
    phy_p_to_c: Vec<u8>,
    rtn_c_to_p: Vec<u8>,
    rtn_p_to_c: Vec<u8>,
}

impl IsoManagerImpl {
    /// Creates a new `IsoManagerImpl` instance wrapped in an `Arc`.
    pub fn new() -> Arc<Self> {
        let shim = Mutex::new(iso_ffi::get_iso_manager_shim());
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let cig_cb = Box::new(IsoCigCallbacks::new(iso_registry.clone()));
        let big_cb = Box::new(IsoBigCallbacks::new(iso_registry.clone()));
        shim.lock().unwrap().pin_mut().register_callbacks_native(cig_cb, big_cb);
        Arc::new(Self { shim, iso_registry })
    }

    fn extract_cis_configs(&self, params: &CigParameters) -> CisConfigs {
        let count = params.cis_configurations.len();
        let mut cis_id = Vec::with_capacity(count);
        let mut max_sdu_c_to_p = Vec::with_capacity(count);
        let mut max_sdu_p_to_c = Vec::with_capacity(count);
        let mut phy_c_to_p = Vec::with_capacity(count);
        let mut phy_p_to_c = Vec::with_capacity(count);
        let mut rtn_c_to_p = Vec::with_capacity(count);
        let mut rtn_p_to_c = Vec::with_capacity(count);

        for cis_cfg in &params.cis_configurations {
            cis_id.push(cis_cfg.cis_id.into());
            max_sdu_c_to_p.push(cis_cfg.max_sdu_c_to_p);
            max_sdu_p_to_c.push(cis_cfg.max_sdu_p_to_c);
            phy_c_to_p.push(cis_cfg.phy_c_to_p);
            phy_p_to_c.push(cis_cfg.phy_p_to_c);
            rtn_c_to_p.push(cis_cfg.rtn_c_to_p);
            rtn_p_to_c.push(cis_cfg.rtn_p_to_c);
        }
        CisConfigs {
            cis_id,
            max_sdu_c_to_p,
            max_sdu_p_to_c,
            phy_c_to_p,
            phy_p_to_c,
            rtn_c_to_p,
            rtn_p_to_c,
        }
    }

    async fn create_cig_internal(
        &self,
        cig_id: CigId,
        params: CigParameters,
    ) -> Result<CreateCigCmplEvent> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut reg = self.iso_registry.lock().unwrap();
            if reg.pending_requests.create_cig.contains_key(&cig_id) {
                warn!("CIG create for {} already in progress", cig_id);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            reg.pending_requests.create_cig.insert(cig_id, sender);
        }

        let cis_params = self.extract_cis_configs(&params);
        self.shim.lock().unwrap().pin_mut().create_cig(
            cig_id.into(),
            params.sdu_interval_c_to_p,
            params.sdu_interval_p_to_c,
            params.worse_cast_sca,
            params.packing,
            params.framing,
            params.max_transport_latency_c_to_p,
            params.max_transport_latency_p_to_c,
            cis_params.cis_id,
            cis_params.max_sdu_c_to_p,
            cis_params.max_sdu_p_to_c,
            cis_params.phy_c_to_p,
            cis_params.phy_p_to_c,
            cis_params.rtn_c_to_p,
            cis_params.rtn_p_to_c,
        );

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.create_cig.remove(&cig_id);

        if let Err(error) = &result {
            error!("create_cig failed: cig_id: {}, error: {:?}", cig_id, error);
        }
        result
    }

    async fn reconfigure_cig_internal(
        &self,
        cig_id: CigId,
        params: CigParameters,
    ) -> Result<CreateCigCmplEvent> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut reg = self.iso_registry.lock().unwrap();
            if reg.pending_requests.create_cig.contains_key(&cig_id) {
                warn!("CIG reconfigure for {} already in progress", cig_id);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            reg.pending_requests.create_cig.insert(cig_id, sender);
        }

        let cis_params = self.extract_cis_configs(&params);
        self.shim.lock().unwrap().pin_mut().reconfigure_cig(
            cig_id.into(),
            params.sdu_interval_c_to_p,
            params.sdu_interval_p_to_c,
            params.worse_cast_sca,
            params.packing,
            params.framing,
            params.max_transport_latency_c_to_p,
            params.max_transport_latency_p_to_c,
            cis_params.cis_id,
            cis_params.max_sdu_c_to_p,
            cis_params.max_sdu_p_to_c,
            cis_params.phy_c_to_p,
            cis_params.phy_p_to_c,
            cis_params.rtn_c_to_p,
            cis_params.rtn_p_to_c,
        );

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.create_cig.remove(&cig_id);

        if let Err(error) = &result {
            error!("reconfigure_cig failed: cig_id: {}, error: {:?}", cig_id, error);
        }
        result
    }

    async fn remove_cig_internal(&self, cig_id: CigId, force: bool) -> Result<()> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.remove_cig.contains_key(&cig_id) {
                warn!("CIG remove request for cig_id {} is already in progress", cig_id);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.remove_cig.insert(cig_id, sender);
        }

        self.shim.lock().unwrap().pin_mut().remove_cig(cig_id.into(), force);

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.remove_cig.remove(&cig_id);

        if let Err(error) = &result {
            error!("remove_cig failed: cig_id: {}, error: {:?}", cig_id, error);
        }
        result
    }

    async fn create_cis_internal(
        &self,
        conn_params: CreateCisParameters,
    ) -> Result<Vec<CisEstablishedEvent>> {
        let mut receivers = Vec::new();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            for conn_pair in &conn_params.conn_handle_pairs {
                if iso_registry.pending_requests.create_cis.contains_key(&conn_pair.0) {
                    warn!(
                        "CIS establishment for cis_conn_handle {} is already in progress",
                        conn_pair.0
                    );
                    // Cleanup previously inserted senders in this batch.
                    for pair_to_cleanup in &conn_params.conn_handle_pairs {
                        iso_registry.pending_requests.create_cis.remove(&pair_to_cleanup.0);
                    }
                    return Err(IsoManagerError::AlreadyInProgress);
                }
                let (sender, receiver) = oneshot::channel();
                iso_registry.pending_requests.create_cis.insert(conn_pair.0, sender);
                receivers.push(receiver);
            }
        }

        let cis_conn_handles: Vec<u16> =
            conn_params.conn_handle_pairs.iter().map(|conn_pair| conn_pair.0.into()).collect();
        let acl_conn_handles: Vec<u16> =
            conn_params.conn_handle_pairs.iter().map(|conn_pair| conn_pair.1.into()).collect();

        self.shim.lock().unwrap().pin_mut().create_cis(cis_conn_handles, acl_conn_handles);

        let mut results: Vec<CisEstablishedEvent> = Vec::new();
        for receiver in receivers {
            match timeout(DEFAULT_TIMEOUT, receiver).await {
                Ok(Ok(inner_res)) => {
                    results.push(inner_res?);
                }
                Ok(Err(_)) => return Err(IsoManagerError::ChannelClosed),
                Err(_) => return Err(IsoManagerError::Timeout),
            }
        }

        Ok(results)
    }

    async fn disconnect_cis_internal(
        &self,
        cis_conn_handle: IsoConnectionHandle,
        reason: HciStatus,
    ) -> Result<()> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.disconnect_cis.contains_key(&cis_conn_handle) {
                warn!(
                    "CIS disconnection for cis_conn_handle {} is already in progress",
                    cis_conn_handle
                );
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.disconnect_cis.insert(cis_conn_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().disconnect_cis(cis_conn_handle.into(), reason.into());

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(_)) => Ok(()),
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.disconnect_cis.remove(&cis_conn_handle);

        if let Err(error) = &result {
            error!(
                "disconnect_cis failed: cis_conn_handle: {}, error: {:?}",
                cis_conn_handle, error
            );
        }
        result
    }
    async fn create_big_internal(
        &self,
        big_handle: BigHandle,
        big_params: CreateBigParameters,
    ) -> Result<CreateBigCmplEvent> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.create_big.contains_key(&big_handle) {
                warn!("BIG create request for big_handle {} is already in progress", big_handle);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.create_big.insert(big_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().create_big(
            big_handle.into(),
            big_params.advertising_handle.into(),
            big_params.num_bis,
            big_params.sdu_itv,
            big_params.max_sdu_size,
            big_params.max_transport_latency,
            big_params.rtn,
            big_params.phy,
            big_params.packing,
            big_params.framing,
            big_params.encryption,
            big_params.broadcast_code,
        );

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.create_big.remove(&big_handle);

        if let Err(error) = &result {
            error!("create_big failed: big_handle: {}, error: {:?}", big_handle, error);
        }
        result
    }

    async fn terminate_big_internal(&self, big_handle: BigHandle, reason: HciStatus) -> Result<()> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.terminate_big.contains_key(&big_handle) {
                warn!("BIG terminate request for big_handle {} is already in progress", big_handle);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.terminate_big.insert(big_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().terminate_big(big_handle.into(), reason.into());

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.terminate_big.remove(&big_handle);

        if let Err(error) = &result {
            error!("terminate_big failed: big_handle: {}, error: {:?}", big_handle, error);
        }
        result
    }

    async fn big_create_sync_internal(
        &self,
        big_handle: BigHandle,
        sync_params: BigCreateSyncParameters,
    ) -> Result<BigSyncEstablishedEvent> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.big_create_sync.contains_key(&big_handle) {
                warn!(
                    "BIG sync create request for big_handle {} is already in progress",
                    big_handle
                );
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.big_create_sync.insert(big_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().big_create_sync(
            big_handle.into(),
            sync_params.sync_handle.into(),
            sync_params.encryption,
            sync_params.broadcast_code,
            sync_params.mse,
            (sync_params.big_sync_timeout.as_millis() / HCI_TIMEOUT_UNIT_MS) as u16,
            sync_params.bis_indices,
        );

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.big_create_sync.remove(&big_handle);

        if let Err(error) = &result {
            error!("big_create_sync failed: big_handle: {}, error: {:?}", big_handle, error);
        }
        result
    }

    async fn big_terminate_sync_internal(&self, big_handle: BigHandle) -> Result<()> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.big_terminate_sync.contains_key(&big_handle) {
                warn!(
                    "BIG sync terminate request for big_handle {} is already in progress",
                    big_handle
                );
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.big_terminate_sync.insert(big_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().big_terminate_sync(big_handle.into());

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.big_terminate_sync.remove(&big_handle);

        if let Err(error) = &result {
            error!("big_terminate_sync failed: big_handle: {}, error: {:?}", big_handle, error);
        }
        result
    }

    async fn setup_iso_data_path_internal(
        &self,
        conn_handle: IsoConnectionHandle,
        path_params: SetupIsoDataPathParameters,
    ) -> Result<()> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.setup_iso_data_path.contains_key(&conn_handle) {
                warn!("Setup data path for conn_handle {} is already in progress", conn_handle);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.setup_iso_data_path.insert(conn_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().setup_iso_data_path(
            conn_handle.into(),
            path_params.data_path_dir as u8,
            path_params.data_path_id as u8,
            path_params.codec_id.coding_format,
            path_params.codec_id.company_id,
            path_params.codec_id.vendor_specific_codec_id,
            path_params.controller_delay.as_micros() as u32,
            path_params.codec_configuration,
        );

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry.lock().unwrap().pending_requests.setup_iso_data_path.remove(&conn_handle);

        if let Err(error) = &result {
            error!("setup_iso_data_path failed: conn_handle: {}, error: {:?}", conn_handle, error);
        }
        result
    }

    async fn remove_iso_data_path_internal(
        &self,
        conn_handle: IsoConnectionHandle,
        data_path_dir: RemoveIsoDataPathDirection,
    ) -> Result<()> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.remove_iso_data_path.contains_key(&conn_handle) {
                warn!("Remove data path for conn_handle {} is already in progress", conn_handle);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.remove_iso_data_path.insert(conn_handle, sender);
        }

        self.shim
            .lock()
            .unwrap()
            .pin_mut()
            .remove_iso_data_path(conn_handle.into(), data_path_dir as u8);

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .remove_iso_data_path
            .remove(&conn_handle);

        if let Err(error) = &result {
            error!(
                "remove_iso_data_path failed: conn_handle: {}, data_path_dir: {:?}, \
                error: {:?}",
                conn_handle, data_path_dir, error
            );
        }

        result
    }

    fn send_iso_data_internal(&self, conn_handle: IsoConnectionHandle, data: &[u8]) {
        self.shim.lock().unwrap().pin_mut().send_iso_data(conn_handle.into(), data);
    }

    async fn read_iso_link_quality_internal(
        &self,
        conn_handle: IsoConnectionHandle,
    ) -> Result<IsoLinkQuality> {
        let (sender, receiver) = oneshot::channel();
        {
            let mut iso_registry = self.iso_registry.lock().unwrap();
            if iso_registry.pending_requests.read_iso_link_quality.contains_key(&conn_handle) {
                warn!("Link quality read for conn_handle {} is already in progress", conn_handle);
                return Err(IsoManagerError::AlreadyInProgress);
            }
            iso_registry.pending_requests.read_iso_link_quality.insert(conn_handle, sender);
        }

        self.shim.lock().unwrap().pin_mut().read_iso_link_quality(conn_handle.into());

        let result = match timeout(DEFAULT_TIMEOUT, receiver).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(IsoManagerError::ChannelClosed),
            Err(_) => Err(IsoManagerError::Timeout),
        };

        self.iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .read_iso_link_quality
            .remove(&conn_handle);

        if let Err(error) = &result {
            error!(
                "read_iso_link_quality failed: conn_handle: {}, error: {:?}",
                conn_handle, error
            );
        }
        result
    }

    fn subscribe_cis_data(
        &self,
        cis_conn_handle: IsoConnectionHandle,
    ) -> ReceiverStream<IsoDataPacket> {
        let (sender, receiver) = mpsc::channel(MPSC_CHANNEL_BUFFER_SIZE);
        self.iso_registry
            .lock()
            .unwrap()
            .cis
            .entry(cis_conn_handle)
            .or_default()
            .data_subscribers
            .push(sender);
        ReceiverStream::new(receiver)
    }

    fn subscribe_bis_data(
        &self,
        bis_conn_handle: IsoConnectionHandle,
    ) -> ReceiverStream<IsoDataPacket> {
        let (sender, receiver) = mpsc::channel(MPSC_CHANNEL_BUFFER_SIZE);
        self.iso_registry
            .lock()
            .unwrap()
            .bis
            .entry(bis_conn_handle)
            .or_default()
            .data_subscribers
            .push(sender);
        ReceiverStream::new(receiver)
    }
}

impl IsoManager for Arc<IsoManagerImpl> {
    type Cig = CigImpl;
    type BigSource = BigSourceImpl;
    type BigSync = BigSyncImpl;

    async fn create_cig(&self, params: CigParameters) -> Result<Self::Cig> {
        let cig_id = CigId::from_masked(self.iso_registry.lock().unwrap().allocate_cid_id()?);
        info!("create_cig: cig_id: {}", cig_id);
        let event = self.create_cig_internal(cig_id, params).await?;
        let cig_inner = Arc::new(CigInner {
            cig_id: event.cig_id,
            manager: Arc::downgrade(self),
            cis_connections: Mutex::new(Vec::new()),
            terminated: AtomicBool::new(false),
        });
        self.iso_registry
            .lock()
            .unwrap()
            .cigs
            .insert(event.cig_id, CigState { inner: Arc::downgrade(&cig_inner) });

        Ok(CigImpl(cig_inner))
    }

    async fn create_big(&self, params: CreateBigParameters) -> Result<Self::BigSource> {
        let big_handle =
            BigHandle::from_masked(self.iso_registry.lock().unwrap().allocate_cid_id()?);
        info!("create_big: big_handle: {}", big_handle);
        let event = self.create_big_internal(big_handle, params).await?;
        let (sender, _) = broadcast::channel(1);
        let terminated = Arc::new(AtomicBool::new(false));
        let bis_connections = event
            .bis_conn_handles
            .iter()
            .map(|&conn_handle| BisImpl {
                conn_handle,
                manager: Arc::downgrade(self),
                terminated: terminated.clone(),
            })
            .collect();

        let big_inner = Arc::new(BigInner {
            big_handle: event.big_handle,
            manager: Arc::downgrade(self),
            bis_connections,
            terminated,
            lost_sender: sender,
            lost_reason: Mutex::new(None),
            is_source: true,
        });

        self.iso_registry.lock().unwrap().bigs.insert(
            event.big_handle,
            BigState { inner: Arc::downgrade(&big_inner), lost_sender: None },
        );
        Ok(BigSourceImpl(big_inner))
    }

    async fn big_create_sync(&self, params: BigCreateSyncParameters) -> Result<Self::BigSync> {
        let big_handle =
            BigHandle::from_masked(self.iso_registry.lock().unwrap().allocate_cid_id()?);
        info!("big_create_sync: big_handle: {}", big_handle);
        let event = self.big_create_sync_internal(big_handle, params).await?;
        let (sender, _) = broadcast::channel(1);
        let terminated = Arc::new(AtomicBool::new(false));
        let bis_connections = event
            .bis_conn_handles
            .iter()
            .map(|&conn_handle| BisImpl {
                conn_handle,
                manager: Arc::downgrade(self),
                terminated: terminated.clone(),
            })
            .collect();

        let big_inner = Arc::new(BigInner {
            big_handle: event.big_handle,
            manager: Arc::downgrade(self),
            bis_connections,
            terminated,
            lost_sender: sender.clone(),
            lost_reason: Mutex::new(None),
            is_source: false,
        });

        {
            let mut registry = self.iso_registry.lock().unwrap();
            registry.bigs.insert(
                event.big_handle,
                BigState { inner: Arc::downgrade(&big_inner), lost_sender: Some(sender) },
            );
        }
        Ok(BigSyncImpl(big_inner))
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use googletest::prelude::*;
    use std::sync::atomic::AtomicBool;
    use tokio::spawn;
    use tokio::time::{sleep, timeout};

    use crate::le_audio::iso_manager::traits::{
        AclConnectionHandle, AdvertisingHandle, BigCreateSyncParameters, CigParameters, CodecId,
        CreateBigParameters, CreateCisParameters, DataPathDirection, DataPathId,
    };

    const TEST_BUFFER_SIZE: usize = 10;

    // --- CIS / CIG Tests ---

    #[googletest::test]
    fn test_cis_impl_send_data_fails_when_terminated() {
        // Verify that send_iso_data returns early without calling manager if already terminated.
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle: IsoConnectionHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: AtomicBool::new(true),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));
        cis.write(&[1, 2, 3]);
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_disconnect_fails_when_terminated() {
        // Verify that calling disconnect on an already terminated CIS returns an internal error.
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle: IsoConnectionHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: AtomicBool::new(true),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));
        let result = timeout(DEFAULT_TIMEOUT, cis.disconnect(HciStatus::Success)).await;
        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_setup_data_path_fails_when_terminated() {
        // Verify that setup_iso_data_path fails if the CIS is terminated.
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle: IsoConnectionHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: AtomicBool::new(true),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));
        let result = timeout(
            DEFAULT_TIMEOUT,
            cis.setup_iso_data_path(SetupIsoDataPathParameters {
                data_path_dir: DataPathDirection::Input,
                data_path_id: DataPathId::Hci,
                codec_id: CodecId { coding_format: 0, company_id: 0, vendor_specific_codec_id: 0 },
                controller_delay: Duration::from_micros(0),
                codec_configuration: vec![],
            }),
        )
        .await;
        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_read_link_quality_fails_when_terminated() {
        // Verify that read_iso_link_quality fails if the CIS is terminated.
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle: IsoConnectionHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: AtomicBool::new(true),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));
        let result = timeout(DEFAULT_TIMEOUT, cis.read_iso_link_quality()).await;
        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_on_disconnected_waits_for_broadcast() {
        // Verify that on_disconnected future resolves correctly when the broadcast signal is sent.
        let (sender, _) = broadcast::channel(1);
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle: IsoConnectionHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: AtomicBool::new(false),
            disconnected_sender: sender.clone(),
            disconnect_reason: Mutex::new(None),
        }));

        let sender_clone = sender.clone();
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let _ = sender_clone.send(HciStatus::RemoteUserTerminatedConnection);
        });
        let reason = timeout(DEFAULT_TIMEOUT, cis.on_disconnected()).await;
        expect_that!(reason, ok(eq(&HciStatus::RemoteUserTerminatedConnection)));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_drop_triggers_cleanup() {
        // Verify that dropping a CIS object triggers the asynchronous disconnection logic.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(50);

        {
            let _cis = CisImpl(Arc::new(CisInner {
                conn_handle,
                manager: Arc::downgrade(&manager),
                terminated: AtomicBool::new(false),
                disconnected_sender: broadcast::channel(1).0,
                disconnect_reason: Mutex::new(None),
            }));
            // _cis goes out of scope here. Arc strong count of terminated flag drops to 1,
            // but the internal Drop impl will check count == 1 and proceed.
        }

        let mut cleaned_up = false;
        for _ in 0..10 {
            sleep(Duration::from_millis(50)).await;
            if manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .disconnect_cis
                .contains_key(&conn_handle)
            {
                cleaned_up = true;
                break;
            }
        }
        expect_that!(cleaned_up, is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_drop_after_disconnected_does_not_trigger_cleanup() {
        // Verify that dropping a CIS object after it is already disconnected does not trigger
        // cleanup.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(51);

        {
            let _cis = CisImpl(Arc::new(CisInner {
                conn_handle,
                manager: Arc::downgrade(&manager),
                terminated: AtomicBool::new(true),
                disconnected_sender: broadcast::channel(1).0,
                disconnect_reason: Mutex::new(None),
            }));
        }

        sleep(Duration::from_millis(100)).await;
        expect_that!(
            manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .disconnect_cis
                .contains_key(&conn_handle),
            is_false()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_setup_iso_data_path_success() {
        // Verify that setup_iso_data_path fulfills correctly on success.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(1);
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle,
            manager: Arc::downgrade(&manager),
            terminated: AtomicBool::new(false),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));

        let manager_clone = manager.clone();
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let sender = manager_clone
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .setup_iso_data_path
                .remove(&conn_handle);
            if let Some(sender) = sender {
                let _ = sender.send(Ok(()));
            }
        });

        let result = timeout(
            DEFAULT_TIMEOUT,
            cis.setup_iso_data_path(SetupIsoDataPathParameters {
                data_path_dir: DataPathDirection::Input,
                data_path_id: DataPathId::Hci,
                codec_id: CodecId { coding_format: 0, company_id: 0, vendor_specific_codec_id: 0 },
                controller_delay: Duration::from_micros(0),
                codec_configuration: vec![],
            }),
        )
        .await;
        expect_that!(result, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cis_impl_read_iso_link_quality_success() {
        // Verify that read_iso_link_quality returns correct data on success.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(1);
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle,
            manager: Arc::downgrade(&manager),
            terminated: AtomicBool::new(false),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));

        let expected_quality = IsoLinkQuality {
            tx_unacked_packets: 1,
            tx_flushed_packets: 2,
            tx_last_subevent_packets: 3,
            retransmitted_packets: 4,
            crc_error_packets: 5,
            rx_unreceived_packets: 6,
            duplicate_packets: 7,
        };

        let manager_clone = manager.clone();
        // let quality_clone = expected_quality;
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let sender = manager_clone
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .read_iso_link_quality
                .remove(&conn_handle);
            if let Some(sender) = sender {
                let _ = sender.send(Ok(expected_quality));
            }
        });

        let result = timeout(DEFAULT_TIMEOUT, cis.read_iso_link_quality()).await;
        expect_that!(result, ok(ok(eq(&expected_quality))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cig_impl_get_cis_connection_finds_correct_instance() {
        // Verify that get_cis_connection returns the matching CIS object from the group.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(10);
        let cis = CisImpl(Arc::new(CisInner {
            conn_handle,
            manager: Arc::downgrade(&manager),
            terminated: AtomicBool::new(false),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        }));
        let cig = CigImpl(Arc::new(CigInner {
            cig_id: CigId::from_masked(1),
            manager: Arc::downgrade(&manager),
            cis_connections: Mutex::new(vec![cis.clone()]),
            terminated: AtomicBool::new(false),
        }));

        expect_that!(
            cig.get_cis_connection(conn_handle).map(|cis| cis.conn_handle()),
            some(eq(conn_handle))
        );
        expect_that!(cig.get_cis_connection(IsoConnectionHandle::from_masked(99)), none());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cig_impl_reconfigure_fails_when_terminated() {
        // Verify that reconfiguration fails if the CIG has been terminated.
        let cig = CigImpl(Arc::new(CigInner {
            cig_id: CigId::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            cis_connections: Mutex::new(vec![]),
            terminated: AtomicBool::new(true),
        }));
        let params = CigParameters {
            sdu_interval_c_to_p: 0,
            sdu_interval_p_to_c: 0,
            worse_cast_sca: 0,
            packing: false,
            framing: false,
            max_transport_latency_c_to_p: 0,
            max_transport_latency_p_to_c: 0,
            cis_configurations: vec![],
        };
        let result = timeout(DEFAULT_TIMEOUT, cig.reconfigure(params)).await;
        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cig_impl_drop_triggers_cleanup() {
        // Verify that dropping a CIG object triggers removal logic.
        let manager = IsoManagerImpl::new();
        let cig_id = CigId::from_masked(7);

        {
            let _cig = CigImpl(Arc::new(CigInner {
                cig_id,
                manager: Arc::downgrade(&manager),
                cis_connections: Mutex::new(vec![]),
                terminated: AtomicBool::new(false),
            }));
        }

        let mut cleaned_up = false;
        for _ in 0..10 {
            sleep(Duration::from_millis(50)).await;
            if manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .remove_cig
                .contains_key(&cig_id)
            {
                cleaned_up = true;
                break;
            }
        }
        expect_that!(cleaned_up, is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cig_impl_remove_success() {
        // Verify that remove() correctly marks the CIG as terminated.
        let manager = IsoManagerImpl::new();
        let cig_id = CigId::from_masked(1);
        let cig = CigImpl(Arc::new(CigInner {
            cig_id,
            manager: Arc::downgrade(&manager),
            cis_connections: Mutex::new(vec![]),
            terminated: AtomicBool::new(false),
        }));

        let manager_clone = manager.clone();
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let sender = manager_clone
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .remove_cig
                .remove(&cig_id);
            if let Some(sender) = sender {
                let _ = sender.send(Ok(()));
            }
        });

        let result = timeout(DEFAULT_TIMEOUT, cig.remove(true)).await;
        expect_that!(result, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_cig_impl_drop_after_remove_does_not_trigger_cleanup() {
        // Verify that dropping a CIG object after it is already removed does not trigger cleanup.
        let manager = IsoManagerImpl::new();
        let cig_id = CigId::from_masked(77);

        {
            let _cig = CigImpl(Arc::new(CigInner {
                cig_id,
                manager: Arc::downgrade(&manager),
                cis_connections: Mutex::new(vec![]),
                terminated: AtomicBool::new(true),
            }));
        }

        sleep(Duration::from_millis(100)).await;
        expect_that!(
            manager.iso_registry.lock().unwrap().pending_requests.remove_cig.contains_key(&cig_id),
            is_false()
        );
    }

    // --- BIS / BIG Tests ---

    #[googletest::test]
    #[tokio::test]
    async fn test_bis_impl_returns_correct_handle() {
        // Verify that BisImpl accurately reports its assigned connection conn_handle.
        let bis = BisImpl {
            conn_handle: IsoConnectionHandle::from_masked(5),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: Arc::new(AtomicBool::new(false)),
        };
        expect_that!(bis.conn_handle(), eq(IsoConnectionHandle::from_masked(5)));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_bis_impl_setup_path_fails_when_terminated() {
        // Verify that setup_iso_data_path for BIS fails if terminated.
        let bis = BisImpl {
            conn_handle: IsoConnectionHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            terminated: Arc::new(AtomicBool::new(true)),
        };
        let result = timeout(
            DEFAULT_TIMEOUT,
            bis.setup_iso_data_path(SetupIsoDataPathParameters {
                data_path_dir: DataPathDirection::Input,
                data_path_id: DataPathId::Hci,
                codec_id: CodecId { coding_format: 0, company_id: 0, vendor_specific_codec_id: 0 },
                controller_delay: Duration::from_micros(0),
                codec_configuration: vec![],
            }),
        )
        .await;
        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_source_impl_terminate_fails_when_terminated() {
        // Verify that terminate() returns error if already terminated.
        let big = BigSourceImpl(Arc::new(BigInner {
            big_handle: BigHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(true)),
            lost_sender: broadcast::channel(1).0,
            lost_reason: Mutex::new(None),
            is_source: true,
        }));
        let result = timeout(DEFAULT_TIMEOUT, big.terminate(HciStatus::Success)).await;
        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_source_impl_drop_triggers_cleanup() {
        // Verify that dropping a BIG Source object triggers termination.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(2);

        {
            let _big = BigSourceImpl(Arc::new(BigInner {
                big_handle,
                manager: Arc::downgrade(&manager),
                bis_connections: vec![],
                terminated: Arc::new(AtomicBool::new(false)),
                lost_sender: broadcast::channel(1).0,
                lost_reason: Mutex::new(None),
                is_source: true,
            }));
        }

        let mut cleaned_up = false;
        for _ in 0..10 {
            sleep(Duration::from_millis(50)).await;
            if manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .terminate_big
                .contains_key(&big_handle)
            {
                cleaned_up = true;
                break;
            }
        }
        expect_that!(cleaned_up, is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_source_impl_terminate_success() {
        // Verify that terminate() correctly marks the BIG Source as terminated.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(1);
        let big = BigSourceImpl(Arc::new(BigInner {
            big_handle,
            manager: Arc::downgrade(&manager),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(false)),
            lost_sender: broadcast::channel(1).0,
            lost_reason: Mutex::new(None),
            is_source: true,
        }));

        let manager_clone = manager.clone();
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let sender = manager_clone
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .terminate_big
                .remove(&big_handle);
            if let Some(sender) = sender {
                let _ = sender.send(Ok(()));
            }
        });

        let result = timeout(DEFAULT_TIMEOUT, big.terminate(HciStatus::Success)).await;
        expect_that!(result, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_source_impl_drop_after_terminate_does_not_trigger_cleanup() {
        // Verify that dropping a BIG Source object after it is terminated does not trigger cleanup.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(12);

        {
            let _big = BigSourceImpl(Arc::new(BigInner {
                big_handle,
                manager: Arc::downgrade(&manager),
                bis_connections: vec![],
                terminated: Arc::new(AtomicBool::new(true)),
                lost_sender: broadcast::channel(1).0,
                lost_reason: Mutex::new(None),
                is_source: true,
            }));
        }

        sleep(Duration::from_millis(100)).await;
        expect_that!(
            manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .terminate_big
                .contains_key(&big_handle),
            is_false()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_sync_impl_terminate_success() {
        // Verify that terminate() correctly marks the BIG Sync as terminated.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(1);
        let big = BigSyncImpl(Arc::new(BigInner {
            big_handle,
            manager: Arc::downgrade(&manager),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(false)),
            lost_sender: broadcast::channel(1).0,
            lost_reason: Mutex::new(None),
            is_source: false,
        }));

        let manager_clone = manager.clone();
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let sender = manager_clone
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .big_terminate_sync
                .remove(&big_handle);
            if let Some(sender) = sender {
                let _ = sender.send(Ok(()));
            }
        });

        let result = timeout(DEFAULT_TIMEOUT, big.terminate()).await;
        expect_that!(result, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_sync_impl_on_lost_resolves_correctly() {
        // Verify that BIG Sync on_lost correctly waits for the loss event signal.
        let (sender, _) = broadcast::channel(1);
        let big = BigSyncImpl(Arc::new(BigInner {
            big_handle: BigHandle::from_masked(1),
            manager: Arc::downgrade(&IsoManagerImpl::new()),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(false)),
            lost_sender: sender.clone(),
            lost_reason: Mutex::new(None),
            is_source: false,
        }));

        let tx_clone = sender.clone();
        spawn(async move {
            sleep(Duration::from_millis(10)).await;
            let _ = tx_clone.send(HciStatus::ConnectionTimeout);
        });
        let reason = timeout(DEFAULT_TIMEOUT, big.on_lost()).await;
        expect_that!(reason, ok(eq(&HciStatus::ConnectionTimeout)));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_sync_impl_drop_triggers_cleanup() {
        // Verify that dropping a BIG Sync object triggers termination.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(3);

        {
            let _big = BigSyncImpl(Arc::new(BigInner {
                big_handle,
                manager: Arc::downgrade(&manager),
                bis_connections: vec![],
                terminated: Arc::new(AtomicBool::new(false)),
                lost_sender: broadcast::channel(1).0,
                lost_reason: Mutex::new(None),
                is_source: false,
            }));
        }

        let mut cleaned_up = false;
        for _ in 0..10 {
            sleep(Duration::from_millis(50)).await;
            if manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .big_terminate_sync
                .contains_key(&big_handle)
            {
                cleaned_up = true;
                break;
            }
        }
        expect_that!(cleaned_up, is_true());
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_sync_impl_drop_after_lost_does_not_trigger_cleanup() {
        // Verify that dropping a BIG Sync object after it is lost does not trigger cleanup.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(33);

        {
            let _big = BigSyncImpl(Arc::new(BigInner {
                big_handle,
                manager: Arc::downgrade(&manager),
                bis_connections: vec![],
                terminated: Arc::new(AtomicBool::new(true)),
                lost_sender: broadcast::channel(1).0,
                lost_reason: Mutex::new(None),
                is_source: false,
            }));
        }

        sleep(Duration::from_millis(100)).await;
        expect_that!(
            manager
                .iso_registry
                .lock()
                .unwrap()
                .pending_requests
                .big_terminate_sync
                .contains_key(&big_handle),
            is_false()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_sync_loss_marks_all_bis_as_terminated() {
        // Verify that a BIG Sync loss event correctly terminates all associated BIS objects.
        let manager = IsoManagerImpl::new();
        let big_handle = BigHandle::from_masked(10);
        let bis_handle = IsoConnectionHandle::from_masked(101);
        let (sender, _) = broadcast::channel(1);

        let big_inner = Arc::new(BigInner {
            big_handle,
            manager: Arc::downgrade(&manager),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(false)),
            lost_sender: sender.clone(),
            lost_reason: Mutex::new(None),
            is_source: false,
        });

        // Register the BIG in the registry first.
        manager.iso_registry.lock().unwrap().bigs.insert(
            big_handle,
            BigState { inner: Arc::downgrade(&big_inner), lost_sender: Some(sender) },
        );

        // Use the actual terminated flag from big_inner for the test's BIS object.
        // In reality, BIS objects would be created via manager and share this.
        let bis = BisImpl {
            conn_handle: bis_handle,
            manager: Arc::downgrade(&manager),
            // Manually link to the same flag for validation in this unit test.
            terminated: big_inner.terminated.clone(),
        };

        // Trigger BIG Sync lost.
        manager
            .iso_registry
            .lock()
            .unwrap()
            .dispatch_big_sync_event(big_handle, HciStatus::ConnectionTimeout);

        // Verify BIS is now terminated.
        expect_that!(big_inner.terminated.load(Ordering::SeqCst), is_true());

        // verify calling setup_path returns Disconnected error now.
        let result = timeout(
            DEFAULT_TIMEOUT,
            bis.setup_iso_data_path(SetupIsoDataPathParameters {
                data_path_dir: DataPathDirection::Input,
                data_path_id: DataPathId::Hci,
                codec_id: CodecId { coding_format: 0, company_id: 0, vendor_specific_codec_id: 0 },
                controller_delay: Duration::from_micros(0),
                codec_configuration: vec![],
            }),
        )
        .await;

        expect_that!(result, ok(err(eq(&IsoManagerError::Disconnected))));
    }

    // --- IsoRegistry Tests ---

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_cis_data_reaches_subscriber() {
        // Verify that ISO data packets are correctly delivered to CIS subscribers.
        let mut registry = IsoRegistry::default();
        let conn_handle = IsoConnectionHandle::from_masked(1);
        let (subscriber_sender, mut subscriber_receiver) = mpsc::channel(TEST_BUFFER_SIZE);
        registry.cis.insert(
            conn_handle,
            CisState { data_subscribers: vec![subscriber_sender], ..Default::default() },
        );

        let time_stamp = Some(Duration::from_micros(1));
        let seq_nb = 1;
        let data = vec![0xAA];
        let packet = IsoDataPacket { time_stamp, seq_nb, data: data.clone() };
        registry.dispatch_cis_data(conn_handle, time_stamp, seq_nb, &data);

        expect_that!(
            timeout(DEFAULT_TIMEOUT, subscriber_receiver.recv()).await,
            ok(some(eq(&packet)))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_cis_data_removes_dropped_subscribers() {
        // Verify that the registry automatically cleans up CIS subscribers that have been dropped.
        let mut registry = IsoRegistry::default();
        let conn_handle = IsoConnectionHandle::from_masked(1);

        {
            let (sender, _rx) = mpsc::channel(TEST_BUFFER_SIZE);
            registry.cis.insert(
                conn_handle,
                CisState { data_subscribers: vec![sender], ..Default::default() },
            );
        }

        let data = vec![];
        registry.dispatch_cis_data(conn_handle, Some(Duration::from_micros(0)), 0, &data);

        expect_that!(
            registry.cis.get(&conn_handle).unwrap().data_subscribers.is_empty(),
            is_true()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_bis_data_reaches_subscriber() {
        // Verify that ISO data packets are correctly delivered to BIS subscribers.
        let mut registry = IsoRegistry::default();
        let conn_handle = IsoConnectionHandle::from_masked(2);
        let (subscriber_sender, mut subscriber_receiver) = mpsc::channel(TEST_BUFFER_SIZE);
        registry.bis.insert(conn_handle, BisState { data_subscribers: vec![subscriber_sender] });

        let time_stamp = Some(Duration::from_micros(2));
        let seq_nb = 2;
        let data = vec![0xBB];
        let packet = IsoDataPacket { time_stamp, seq_nb, data: data.clone() };
        registry.dispatch_bis_data(conn_handle, time_stamp, seq_nb, &data);

        expect_that!(
            timeout(DEFAULT_TIMEOUT, subscriber_receiver.recv()).await,
            ok(some(eq(&packet)))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_bis_data_removes_dropped_subscribers() {
        // Verify that the registry automatically cleans up BIS subscribers that have been dropped.
        let mut registry = IsoRegistry::default();
        let conn_handle = IsoConnectionHandle::from_masked(2);

        {
            let (sender, _rx) = mpsc::channel(TEST_BUFFER_SIZE);
            registry.bis.insert(conn_handle, BisState { data_subscribers: vec![sender] });
        }

        let data = vec![];
        registry.dispatch_bis_data(conn_handle, Some(Duration::from_micros(0)), 0, &data);

        expect_that!(
            registry.bis.get(&conn_handle).unwrap().data_subscribers.is_empty(),
            is_true()
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_cis_disconnected_cleans_up() {
        // Verify that disconnection events mark the state as terminated and notify listeners.
        let mut registry = IsoRegistry::default();
        let conn_handle = IsoConnectionHandle::from_masked(3);
        let cis_inner = Arc::new(CisInner {
            conn_handle,
            manager: Weak::new(),
            terminated: AtomicBool::new(false),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: std::sync::Mutex::new(None),
        });
        let (sender, mut receiver) = broadcast::channel(1);
        registry.cis.insert(
            conn_handle,
            CisState {
                data_subscribers: vec![],
                inner: Arc::downgrade(&cis_inner),
                disconnected_sender: Some(sender),
            },
        );

        registry.dispatch_cis_disconnected(conn_handle, HciStatus::ConnectionTimeout);

        expect_that!(registry.cis.contains_key(&conn_handle), is_false());
        expect_that!(cis_inner.terminated.load(Ordering::SeqCst), is_true());
        expect_that!(receiver.recv().await, ok(eq(&HciStatus::ConnectionTimeout)));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_big_sync_event_cleans_up() {
        // Verify that BIG sync events notify listeners and remove the state from registry.
        let mut registry = IsoRegistry::default();
        let conn_handle = BigHandle::from_masked(4);
        let big_inner = Arc::new(BigInner {
            big_handle: conn_handle,
            manager: Weak::new(),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(false)),
            lost_sender: broadcast::channel(1).0,
            lost_reason: Mutex::new(None),
            is_source: false,
        });
        let (sender, mut receiver) = broadcast::channel(1);
        registry.bigs.insert(
            conn_handle,
            BigState { inner: Arc::downgrade(&big_inner), lost_sender: Some(sender) },
        );

        registry.dispatch_big_sync_event(conn_handle, HciStatus::ConnectionFailedEstablishment);

        expect_that!(registry.bigs.contains_key(&conn_handle), is_false());
        expect_that!(big_inner.terminated.load(Ordering::SeqCst), is_true());
        expect_that!(receiver.recv().await, ok(eq(&HciStatus::ConnectionFailedEstablishment)));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_registry_dispatch_data_to_multiple_subscribers() {
        // Verify that ISO data is delivered to all active subscribers of the same stream.
        let mut registry = IsoRegistry::default();
        let conn_handle = IsoConnectionHandle::from_masked(1);
        let (sender1, mut receiver1) = mpsc::channel(TEST_BUFFER_SIZE);
        let (sender2, mut receiver2) = mpsc::channel(TEST_BUFFER_SIZE);

        registry.cis.insert(
            conn_handle,
            CisState { data_subscribers: vec![sender1, sender2], ..Default::default() },
        );

        let time_stamp = Some(Duration::from_micros(100));
        let seq_nb = 5;
        let data = vec![0x11, 0x22];
        let packet = IsoDataPacket { time_stamp, seq_nb, data: data.clone() };
        registry.dispatch_cis_data(conn_handle, time_stamp, seq_nb, &data);

        expect_that!(timeout(DEFAULT_TIMEOUT, receiver1.recv()).await, ok(some(eq(&packet))));
        expect_that!(timeout(DEFAULT_TIMEOUT, receiver2.recv()).await, ok(some(eq(&packet))));
    }

    // --- IsoManagerImpl Logic Tests ---

    #[googletest::test]
    #[tokio::test]
    async fn test_create_cig_fails_if_already_pending() {
        // Verify that starting a CIG creation fails if a request with the same ID is already
        // pending.
        let manager = IsoManagerImpl::new();
        // The first allocate_id() will likely return 0.
        let id = CigId::from_masked(0);
        let (sender, _) = oneshot::channel();
        manager.iso_registry.lock().unwrap().pending_requests.create_cig.insert(id, sender);

        let result = timeout(
            DEFAULT_TIMEOUT,
            manager.create_cig(CigParameters {
                sdu_interval_c_to_p: 0,
                sdu_interval_p_to_c: 0,
                worse_cast_sca: 0,
                packing: false,
                framing: false,
                max_transport_latency_c_to_p: 0,
                max_transport_latency_p_to_c: 0,
                cis_configurations: vec![],
            }),
        )
        .await;
        expect_that!(result, ok(err(eq(&IsoManagerError::AlreadyInProgress))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_create_big_fails_if_already_pending() {
        // Verify that starting a BIG creation fails if a request with the same handle is already
        // pending.
        let manager = IsoManagerImpl::new();
        // The first allocate_id() will likely return 0.
        let conn_handle = BigHandle::from_masked(0);
        let (sender, _) = oneshot::channel();
        manager
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .create_big
            .insert(conn_handle, sender);

        let result = timeout(
            DEFAULT_TIMEOUT,
            manager.create_big(CreateBigParameters {
                advertising_handle: AdvertisingHandle::from_masked(0),
                num_bis: 1,
                sdu_itv: 10000,
                max_sdu_size: 100,
                max_transport_latency: 10,
                rtn: 2,
                phy: 2,
                packing: false,
                framing: false,
                encryption: false,
                broadcast_code: [0; 16],
            }),
        )
        .await;
        expect_that!(result, ok(err(eq(&IsoManagerError::AlreadyInProgress))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_big_create_sync_fails_if_already_pending() {
        // Verify that starting a BIG sync creation fails if a request with the same handle is
        // already pending.
        let manager = IsoManagerImpl::new();
        // The first allocate_id() will likely return 0.
        let conn_handle = BigHandle::from_masked(0);
        let (sender, _) = oneshot::channel();
        manager
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .big_create_sync
            .insert(conn_handle, sender);

        let result = timeout(
            DEFAULT_TIMEOUT,
            manager.big_create_sync(BigCreateSyncParameters {
                sync_handle: 1,
                encryption: false,
                broadcast_code: [0; 16],
                mse: 0,
                big_sync_timeout: Duration::from_millis(1000),
                bis_indices: vec![1],
            }),
        )
        .await;
        expect_that!(result, ok(err(eq(&IsoManagerError::AlreadyInProgress))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_create_cis_fails_if_handle_pending() {
        // Verify that CIS establishment fails if handles are already pending.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(100);
        let (sender, _) = oneshot::channel();
        manager
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .create_cis
            .insert(conn_handle, sender);

        let params = CreateCisParameters {
            conn_handle_pairs: vec![(conn_handle, AclConnectionHandle::from_masked(1))],
        };
        let result = timeout(DEFAULT_TIMEOUT, manager.create_cis_internal(params)).await;
        expect_that!(result, ok(err(eq(&IsoManagerError::AlreadyInProgress))));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_create_cis_cleans_up_on_partial_pending_failure() {
        // Verify that create_cis cleans up successfully registered handles if a later handle
        // in the same batch fails.
        let manager = IsoManagerImpl::new();
        let conn_handle_a = IsoConnectionHandle::from_masked(101);
        let conn_handle_b = IsoConnectionHandle::from_masked(102);

        // Pre-insert handle_b to cause a conflict.
        let (sender_b, _) = oneshot::channel();
        manager
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .create_cis
            .insert(conn_handle_b, sender_b);

        let params = CreateCisParameters {
            conn_handle_pairs: vec![
                (conn_handle_a, AclConnectionHandle::from_masked(1)),
                (conn_handle_b, AclConnectionHandle::from_masked(1)),
            ],
        };

        let result = timeout(DEFAULT_TIMEOUT, manager.create_cis_internal(params)).await;
        expect_that!(result, ok(err(eq(&IsoManagerError::AlreadyInProgress))));

        // Both handle_a and handle_b should have been removed from pending_requests due to
        // 'clear all' cleanup.
        let reg = manager.iso_registry.lock().unwrap();
        expect_that!(reg.pending_requests.create_cis.contains_key(&conn_handle_a), is_false());
        expect_that!(reg.pending_requests.create_cis.contains_key(&conn_handle_b), is_false());
    }

    #[googletest::test]
    fn test_subscribe_cis_data_creates_entry_lazily() {
        // Verify that subscribing to CIS data ensures a registry entry is created.
        let manager = IsoManagerImpl::new();
        let conn_handle = IsoConnectionHandle::from_masked(200);

        {
            let _stream = manager.subscribe_cis_data(conn_handle);
            let reg = manager.iso_registry.lock().unwrap();
            expect_that!(reg.cis.contains_key(&conn_handle), is_true());
            expect_that!(reg.cis.get(&conn_handle).unwrap().data_subscribers.len(), eq(1));
        }
    }
}
