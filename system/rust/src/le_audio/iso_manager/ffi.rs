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
use std::time::Duration;

use crate::le_audio::iso_manager::manager::{
    BigSyncEstablishedEvent, CisDisconnectedEvent, CisEstablishedEvent, CreateBigCmplEvent,
    CreateCigCmplEvent, IsoRegistry,
};
use crate::le_audio::iso_manager::traits::{
    BigHandle, CigId, IsoConnectionHandle, IsoLinkQuality, IsoManagerError,
};
use crate::pdl::hci::HciStatus;

#[cxx::bridge]
pub mod inner_ffi {
    #[namespace = "bluetooth::hci::iso_manager"]
    unsafe extern "C++" {
        include!("iso_manager/iso_manager_shim.h");

        type IsoManagerShim;

        #[cxx_name = "GetIsoManagerShim"]
        fn get_iso_manager_shim() -> UniquePtr<IsoManagerShim>;

        #[cxx_name = "RegisterCallbacksNative"]
        fn register_callbacks_native(
            self: Pin<&mut IsoManagerShim>,
            cig_callbacks: Box<IsoCigCallbacks>,
            big_callbacks: Box<IsoBigCallbacks>,
        );

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "CreateCig"]
        fn create_cig(
            self: Pin<&mut IsoManagerShim>,
            cig_id: u8,
            sdu_interval_c_to_p: u32,
            sdu_interval_p_to_c: u32,
            worse_cast_sca: u8,
            packing: bool,
            framing: bool,
            max_trans_lat_p_to_c: u16,
            max_trans_lat_c_to_p: u16,
            cis_ids: Vec<u8>,
            max_sdu_c_to_p: Vec<u16>,
            max_sdu_p_to_c: Vec<u16>,
            phy_c_to_p: Vec<u8>,
            phy_p_to_c: Vec<u8>,
            rtn_c_to_p: Vec<u8>,
            rtn_p_to_c: Vec<u8>,
        );

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "ReconfigureCig"]
        fn reconfigure_cig(
            self: Pin<&mut IsoManagerShim>,
            cig_id: u8,
            sdu_interval_c_to_p: u32,
            sdu_interval_p_to_c: u32,
            worse_cast_sca: u8,
            packing: bool,
            framing: bool,
            max_trans_lat_p_to_c: u16,
            max_trans_lat_c_to_p: u16,
            cis_ids: Vec<u8>,
            max_sdu_c_to_p: Vec<u16>,
            max_sdu_p_to_c: Vec<u16>,
            phy_c_to_p: Vec<u8>,
            phy_p_to_c: Vec<u8>,
            rtn_c_to_p: Vec<u8>,
            rtn_p_to_c: Vec<u8>,
        );

        #[cxx_name = "RemoveCig"]
        fn remove_cig(self: Pin<&mut IsoManagerShim>, cig_id: u8, force: bool);

        #[cxx_name = "CreateCis"]
        fn create_cis(
            self: Pin<&mut IsoManagerShim>,
            cis_conn_handles: Vec<u16>,
            acl_conn_handles: Vec<u16>,
        );

        #[cxx_name = "DisconnectCis"]
        fn disconnect_cis(self: Pin<&mut IsoManagerShim>, cis_conn_handle: u16, reason: u8);

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "CreateBig"]
        fn create_big(
            self: Pin<&mut IsoManagerShim>,
            big_handle: u8,
            advertising_handle: u8,
            num_bis: u8,
            sdu_itv: u32,
            max_sdu_size: u16,
            max_transport_latency: u16,
            rtn: u8,
            phy: u8,
            packing: bool,
            framing: bool,
            encryption: bool,
            broadcast_code: [u8; 16],
        );

        #[cxx_name = "TerminateBig"]
        fn terminate_big(self: Pin<&mut IsoManagerShim>, big_handle: u8, reason: u8);

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "BigCreateSync"]
        fn big_create_sync(
            self: Pin<&mut IsoManagerShim>,
            big_handle: u8,
            sync_handle: u16,
            encryption: bool,
            broadcast_code: [u8; 16],
            mse: u8,
            big_sync_timeout: u16,
            bis_indices: Vec<u8>,
        );

        #[cxx_name = "BigTerminateSync"]
        fn big_terminate_sync(self: Pin<&mut IsoManagerShim>, big_handle: u8);

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "SetupIsoDataPath"]
        fn setup_iso_data_path(
            self: Pin<&mut IsoManagerShim>,
            conn_handle: u16,
            data_path_dir: u8,
            data_path_id: u8,
            coding_format: u8,
            company_id: u16,
            vendor_specific_codec_id: u16,
            controller_delay: u32,
            codec_configuration: Vec<u8>,
        );

        #[cxx_name = "RemoveIsoDataPath"]
        fn remove_iso_data_path(
            self: Pin<&mut IsoManagerShim>,
            conn_handle: u16,
            data_path_dir: u8,
        );

        #[cxx_name = "SendIsoData"]
        fn send_iso_data(self: Pin<&mut IsoManagerShim>, conn_handle: u16, data: &[u8]);

        #[cxx_name = "ReadIsoLinkQuality"]
        fn read_iso_link_quality(self: Pin<&mut IsoManagerShim>, conn_handle: u16);
    }

    #[namespace = "bluetooth::hci::iso_manager::ffi"]
    extern "Rust" {
        type IsoCigCallbacks;

        #[cxx_name = "OnCreateCigCmpl"]
        fn on_create_cig_cmpl(
            self: &IsoCigCallbacks,
            status: u8,
            cig_id: u8,
            cis_conn_handles: Vec<u16>,
        );

        #[cxx_name = "OnRemoveCigCmpl"]
        fn on_remove_cig_cmpl(self: &IsoCigCallbacks, status: u8, cig_id: u8);

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "OnCisEstablished"]
        fn on_cis_established(
            self: &IsoCigCallbacks,
            status: u8,
            cig_id: u8,
            cis_conn_handle: u16,
            cig_sync_delay: u32,
            cis_sync_delay: u32,
            transport_latency_c_to_p: u32,
            transport_latency_p_to_c: u32,
            phy_c_to_p: u8,
            phy_p_to_c: u8,
            nse: u8,
            bn_c_to_p: u8,
            bn_p_to_c: u8,
            ft_c_to_p: u8,
            ft_p_to_c: u8,
            max_pdu_c_to_p: u16,
            max_pdu_p_to_c: u16,
            iso_interval: u16,
        );

        #[cxx_name = "OnCisDisconnected"]
        fn on_cis_disconnected(
            self: &IsoCigCallbacks,
            reason: u8,
            cig_id: u8,
            cis_conn_handle: u16,
        );

        #[cxx_name = "OnCisDataAvailable"]
        fn on_cis_data_available(
            self: &IsoCigCallbacks,
            cig_id: u8,
            cis_conn_handle: u16,
            time_stamp: u32,
            seq_nb: u16,
            data: &[u8],
        );

        #[cxx_name = "OnSetupIsoDataPath"]
        fn on_setup_iso_data_path_cig(
            self: &IsoCigCallbacks,
            status: u8,
            cis_conn_handle: u16,
            cig_id: u8,
        );

        #[cxx_name = "OnRemoveIsoDataPath"]
        fn on_remove_iso_data_path_cig(
            self: &IsoCigCallbacks,
            status: u8,
            cis_conn_handle: u16,
            cig_id: u8,
        );

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "OnIsoLinkQualityRead"]
        fn on_iso_link_quality_read(
            self: &IsoCigCallbacks,
            cis_conn_handle: u16,
            cig_id: u8,
            tx_unacked_packets: u32,
            tx_flushed_packets: u32,
            tx_last_subevent_packets: u32,
            retransmitted_packets: u32,
            crc_error_packets: u32,
            rx_unreceived_packets: u32,
            duplicate_packets: u32,
        );
    }

    #[namespace = "bluetooth::hci::iso_manager::ffi"]
    extern "Rust" {
        type IsoBigCallbacks;

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "OnCreateBigCmpl"]
        fn on_create_big_cmpl(
            self: &IsoBigCallbacks,
            status: u8,
            big_handle: u8,
            big_sync_delay: u32,
            transport_latency_big: u32,
            phy: u8,
            nse: u8,
            bn: u8,
            pto: u8,
            irc: u8,
            max_pdu: u16,
            iso_interval: u16,
            bis_conn_handles: Vec<u16>,
        );

        #[cxx_name = "OnTerminateBigCmpl"]
        fn on_terminate_big_cmpl(self: &IsoBigCallbacks, big_handle: u8, reason: u8);

        #[allow(clippy::too_many_arguments)]
        #[cxx_name = "OnBigSyncEstablished"]
        fn on_big_sync_established(
            self: &IsoBigCallbacks,
            status: u8,
            big_handle: u8,
            transport_latency_big: u32,
            nse: u8,
            bn: u8,
            pto: u8,
            irc: u8,
            max_pdu: u16,
            iso_interval: u16,
            conn_handles: Vec<u16>,
        );

        #[cxx_name = "OnBigTerminateSyncCmpl"]
        fn on_big_terminate_sync_cmpl(self: &IsoBigCallbacks, status: u8, big_handle: u8);

        #[cxx_name = "OnBigSyncLost"]
        fn on_big_sync_lost(self: &IsoBigCallbacks, big_handle: u8, reason: u8);

        #[cxx_name = "OnBisDataAvailable"]
        fn on_bis_data_available(
            self: &IsoBigCallbacks,
            big_handle: u8,
            bis_conn_handle: u16,
            time_stamp: u32,
            seq_nb: u16,
            data: &[u8],
        );

        #[cxx_name = "OnSetupIsoDataPath"]
        fn on_setup_iso_data_path_big(
            self: &IsoBigCallbacks,
            status: u8,
            bis_conn_handle: u16,
            big_handle: u8,
        );

        #[cxx_name = "OnRemoveIsoDataPath"]
        fn on_remove_iso_data_path_big(
            self: &IsoBigCallbacks,
            status: u8,
            bis_conn_handle: u16,
            big_handle: u8,
        );
    }
}

// Safety: `IsoManagerShim` is safe to send between threads.
unsafe impl Send for inner_ffi::IsoManagerShim {}

pub struct IsoCigCallbacks {
    iso_registry: Arc<Mutex<IsoRegistry>>,
}

impl IsoCigCallbacks {
    pub fn new(iso_registry: Arc<Mutex<IsoRegistry>>) -> Self {
        Self { iso_registry }
    }

    pub fn on_create_cig_cmpl(
        &self,
        status_raw: u8,
        cig_id_raw: u8,
        cis_conn_handles_raw: Vec<u16>,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let cig_id = CigId::try_from(cig_id_raw).unwrap();

        if let Some(sender) =
            self.iso_registry.lock().unwrap().pending_requests.create_cig.remove(&cig_id)
        {
            let _ = sender.send(
                status
                    .err_or_else(|| CreateCigCmplEvent {
                        cig_id,
                        cis_conn_handles: cis_conn_handles_raw
                            .into_iter()
                            .map(|conn_handle| IsoConnectionHandle::try_from(conn_handle).unwrap())
                            .collect(),
                    })
                    .map_err(IsoManagerError::HciError),
            );
        }
    }

    pub fn on_remove_cig_cmpl(&self, status_raw: u8, cig_id_raw: u8) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let cig_id = CigId::try_from(cig_id_raw).unwrap();

        if let Some(sender) =
            self.iso_registry.lock().unwrap().pending_requests.remove_cig.remove(&cig_id)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn on_cis_established(
        &self,
        status_raw: u8,
        cig_id: u8,
        cis_conn_handle_raw: u16,
        cig_sync_delay: u32,
        cis_sync_delay: u32,
        transport_latency_c_to_p: u32,
        transport_latency_p_to_c: u32,
        phy_c_to_p: u8,
        phy_p_to_c: u8,
        nse: u8,
        bn_c_to_p: u8,
        bn_p_to_c: u8,
        ft_c_to_p: u8,
        ft_p_to_c: u8,
        max_pdu_c_to_p: u16,
        max_pdu_p_to_c: u16,
        iso_interval: u16,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let cis_conn_handle = IsoConnectionHandle::try_from(cis_conn_handle_raw).unwrap();

        let mut iso_registry = self.iso_registry.lock().unwrap();
        if let Some(sender) = iso_registry.pending_requests.create_cis.remove(&cis_conn_handle) {
            let _ = sender.send(
                status
                    .err_or_else(|| CisEstablishedEvent {
                        cig_id: CigId::try_from(cig_id).unwrap(),
                        cis_conn_handle,
                        cig_sync_delay,
                        cis_sync_delay,
                        transport_latency_c_to_p,
                        transport_latency_p_to_c,
                        phy_c_to_p,
                        phy_p_to_c,
                        nse,
                        bn_c_to_p,
                        bn_p_to_c,
                        ft_c_to_p,
                        ft_p_to_c,
                        max_pdu_c_to_p,
                        max_pdu_p_to_c,
                        iso_interval,
                    })
                    .map_err(IsoManagerError::HciError),
            );
        }
    }
    pub fn on_cis_disconnected(&self, reason_raw: u8, cig_id: u8, cis_conn_handle_raw: u16) {
        let reason = HciStatus::try_from(reason_raw).unwrap_or(HciStatus::StatusUnknown);
        let cis_conn_handle = IsoConnectionHandle::try_from(cis_conn_handle_raw).unwrap();

        let mut iso_registry = self.iso_registry.lock().unwrap();
        iso_registry.dispatch_cis_disconnected(cis_conn_handle, reason);
        if let Some(sender) = iso_registry.pending_requests.disconnect_cis.remove(&cis_conn_handle)
        {
            let _ = sender.send(Ok(CisDisconnectedEvent {
                reason,
                cig_id: CigId::try_from(cig_id).unwrap(),
                cis_conn_handle,
            }));
        }
    }

    pub fn on_cis_data_available(
        &self,
        _cig_id: u8,
        cis_conn_handle_raw: u16,
        time_stamp: u32,
        seq_nb: u16,
        data: &[u8],
    ) {
        let cis_conn_handle = IsoConnectionHandle::try_from(cis_conn_handle_raw).unwrap();
        let mut iso_registry = self.iso_registry.lock().unwrap();

        if let Some(state) = iso_registry.cis.get(&cis_conn_handle) {
            if !state.data_subscribers.is_empty() {
                iso_registry.dispatch_cis_data(
                    cis_conn_handle,
                    Some(Duration::from_micros(time_stamp as u64)),
                    seq_nb,
                    data,
                );
            }
        }
    }

    pub fn on_setup_iso_data_path_cig(
        &self,
        status_raw: u8,
        cis_conn_handle_raw: u16,
        _cig_id: u8,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let cis_conn_handle = IsoConnectionHandle::try_from(cis_conn_handle_raw).unwrap();

        if let Some(sender) = self
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .setup_iso_data_path
            .remove(&cis_conn_handle)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }

    pub fn on_remove_iso_data_path_cig(
        &self,
        status_raw: u8,
        cis_conn_handle_raw: u16,
        _cig_id: u8,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let cis_conn_handle = IsoConnectionHandle::try_from(cis_conn_handle_raw).unwrap();

        if let Some(sender) = self
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .remove_iso_data_path
            .remove(&cis_conn_handle)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn on_iso_link_quality_read(
        &self,
        cis_conn_handle_raw: u16,
        _cig_id: u8,
        tx_unacked_packets: u32,
        tx_flushed_packets: u32,
        tx_last_subevent_packets: u32,
        retransmitted_packets: u32,
        crc_error_packets: u32,
        rx_unreceived_packets: u32,
        duplicate_packets: u32,
    ) {
        let cis_conn_handle = IsoConnectionHandle::try_from(cis_conn_handle_raw).unwrap();

        if let Some(sender) = self
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .read_iso_link_quality
            .remove(&cis_conn_handle)
        {
            let _ = sender.send(Ok(IsoLinkQuality {
                tx_unacked_packets,
                tx_flushed_packets,
                tx_last_subevent_packets,
                retransmitted_packets,
                crc_error_packets,
                rx_unreceived_packets,
                duplicate_packets,
            }));
        }
    }
}

pub struct IsoBigCallbacks {
    iso_registry: Arc<Mutex<IsoRegistry>>,
}

impl IsoBigCallbacks {
    pub fn new(iso_registry: Arc<Mutex<IsoRegistry>>) -> Self {
        Self { iso_registry }
    }

    pub fn on_setup_iso_data_path_big(
        &self,
        status_raw: u8,
        bis_conn_handle_raw: u16,
        _big_handle: u8,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let bis_conn_handle = IsoConnectionHandle::try_from(bis_conn_handle_raw).unwrap();

        if let Some(sender) = self
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .setup_iso_data_path
            .remove(&bis_conn_handle)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }

    pub fn on_remove_iso_data_path_big(
        &self,
        status_raw: u8,
        bis_conn_handle_raw: u16,
        _big_handle: u8,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let bis_conn_handle = IsoConnectionHandle::try_from(bis_conn_handle_raw).unwrap();

        if let Some(sender) = self
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .remove_iso_data_path
            .remove(&bis_conn_handle)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }

    pub fn on_bis_data_available(
        &self,
        _big_handle: u8,
        bis_conn_handle_raw: u16,
        time_stamp: u32,
        seq_nb: u16,
        data: &[u8],
    ) {
        let bis_conn_handle = IsoConnectionHandle::try_from(bis_conn_handle_raw).unwrap();
        let mut iso_registry = self.iso_registry.lock().unwrap();

        if let Some(state) = iso_registry.bis.get(&bis_conn_handle) {
            if !state.data_subscribers.is_empty() {
                iso_registry.dispatch_bis_data(
                    bis_conn_handle,
                    Some(Duration::from_micros(time_stamp as u64)),
                    seq_nb,
                    data,
                );
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn on_create_big_cmpl(
        &self,
        status_raw: u8,
        big_handle_raw: u8,
        big_sync_delay: u32,
        transport_latency_big: u32,
        phy: u8,
        nse: u8,
        bn: u8,
        pto: u8,
        irc: u8,
        max_pdu: u16,
        iso_interval: u16,
        bis_conn_handles_raw: Vec<u16>,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let big_handle = BigHandle::try_from(big_handle_raw).unwrap();
        if let Some(sender) =
            self.iso_registry.lock().unwrap().pending_requests.create_big.remove(&big_handle)
        {
            let _ = sender.send(
                status
                    .err_or_else(|| CreateBigCmplEvent {
                        big_handle,
                        big_sync_delay,
                        transport_latency_big,
                        phy,
                        nse,
                        bn,
                        pto,
                        irc,
                        max_pdu,
                        iso_interval,
                        bis_conn_handles: bis_conn_handles_raw
                            .into_iter()
                            .map(|conn_handle| IsoConnectionHandle::try_from(conn_handle).unwrap())
                            .collect(),
                    })
                    .map_err(IsoManagerError::HciError),
            );
        }
    }

    pub fn on_terminate_big_cmpl(&self, big_handle_raw: u8, status_raw: u8) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let big_handle = BigHandle::try_from(big_handle_raw).unwrap();

        if let Some(sender) =
            self.iso_registry.lock().unwrap().pending_requests.terminate_big.remove(&big_handle)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn on_big_sync_established(
        &self,
        status_raw: u8,
        big_handle_raw: u8,
        transport_latency_big: u32,
        nse: u8,
        bn: u8,
        pto: u8,
        irc: u8,
        max_pdu: u16,
        iso_interval: u16,
        bis_conn_handles_raw: Vec<u16>,
    ) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let big_handle = BigHandle::try_from(big_handle_raw).unwrap();

        if let Some(sender) =
            self.iso_registry.lock().unwrap().pending_requests.big_create_sync.remove(&big_handle)
        {
            let _ = sender.send(
                status
                    .err_or_else(|| BigSyncEstablishedEvent {
                        big_handle,
                        transport_latency_big,
                        nse,
                        bn,
                        pto,
                        irc,
                        max_pdu,
                        iso_interval,
                        bis_conn_handles: bis_conn_handles_raw
                            .into_iter()
                            .map(|conn_handle| IsoConnectionHandle::try_from(conn_handle).unwrap())
                            .collect(),
                    })
                    .map_err(IsoManagerError::HciError),
            );
        }
    }

    pub fn on_big_sync_lost(&self, big_handle_raw: u8, reason_raw: u8) {
        let reason = HciStatus::try_from(reason_raw).unwrap_or(HciStatus::StatusUnknown);
        self.iso_registry
            .lock()
            .unwrap()
            .dispatch_big_sync_event(BigHandle::try_from(big_handle_raw).unwrap(), reason);
    }

    pub fn on_big_terminate_sync_cmpl(&self, status_raw: u8, big_handle_raw: u8) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let big_handle = BigHandle::try_from(big_handle_raw).unwrap();

        if let Some(sender) = self
            .iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .big_terminate_sync
            .remove(&big_handle)
        {
            let _ = sender.send(status.err_or(()).map_err(IsoManagerError::HciError));
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use googletest::prelude::*;
    use std::sync::atomic::AtomicBool;
    use std::sync::Weak;
    use std::time::Duration;
    use tokio::sync::{broadcast, mpsc, oneshot};
    use tokio::time::timeout;

    use crate::le_audio::iso_manager::manager::{BigInner, BigState, BisState, CisInner, CisState};
    use crate::le_audio::iso_manager::traits::IsoDataPacket;

    const TEST_TIMEOUT: Duration = Duration::from_secs(1);
    const SUBSCRIBER_EVENT_BUFFER: usize = 10;

    #[googletest::test]
    #[tokio::test]
    async fn test_on_create_cig_cmpl_fulfills_pending_request_on_success() {
        // Verify that when a CIG is successfully created, the manager fulfills the pending
        // request with the appropriate completion event containing the allocated connection handles.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.create_cig.insert(cig_id, sender);

        callbacks.on_create_cig_cmpl(HciStatus::Success.into(), cig_id.into(), vec![10, 11]);

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(CreateCigCmplEvent {
                cig_id: eq(&cig_id),
                cis_conn_handles: eq(&vec![
                    IsoConnectionHandle::try_from(10).unwrap(),
                    IsoConnectionHandle::try_from(11).unwrap()
                ]),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_remove_cig_cmpl_fulfills_pending_request() {
        // Verify that when a CIG is removed, the manager notifies the requester of the success
        // status via the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(2).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.remove_cig.insert(cig_id, sender);

        callbacks.on_remove_cig_cmpl(HciStatus::Success.into(), cig_id.into());

        expect_that!(receiver.await, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_cis_established_fulfills_pending_request() {
        // Verify that when a CIS is established, the manager fulfills the specific pending
        // request for that connection handle with all the extracted ISO parameters.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();
        let cis_conn_handle = IsoConnectionHandle::try_from(100).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.create_cis.insert(cis_conn_handle, sender);

        callbacks.on_cis_established(
            HciStatus::Success.into(),
            cig_id.into(),
            cis_conn_handle.into(),
            1000,
            1100,
            2000,
            2100,
            1,
            2,
            3,
            1,
            2,
            0,
            1,
            128,
            256,
            10,
        );

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(&CisEstablishedEvent {
                cig_id: eq(cig_id),
                cis_conn_handle: eq(cis_conn_handle),
                cig_sync_delay: eq(1000),
                cis_sync_delay: eq(1100),
                transport_latency_c_to_p: eq(2000),
                transport_latency_p_to_c: eq(2100),
                phy_c_to_p: eq(1),
                phy_p_to_c: eq(2),
                nse: eq(3),
                bn_c_to_p: eq(1),
                bn_p_to_c: eq(2),
                ft_c_to_p: eq(0),
                ft_p_to_c: eq(1),
                max_pdu_c_to_p: eq(128),
                max_pdu_p_to_c: eq(256),
                iso_interval: eq(10),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_cis_disconnected_fulfills_pending_request() {
        // Verify that a CIS disconnection correctly triggers a notification to the requester
        // with the appropriate reason status.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();
        let cis_conn_handle = IsoConnectionHandle::try_from(100).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .disconnect_cis
            .insert(cis_conn_handle, sender);

        callbacks.on_cis_disconnected(
            HciStatus::RemoteUserTerminatedConnection.into(),
            cig_id.into(),
            cis_conn_handle.into(),
        );

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(&CisDisconnectedEvent {
                reason: eq(HciStatus::RemoteUserTerminatedConnection),
                cig_id: eq(cig_id),
                cis_conn_handle: eq(cis_conn_handle),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_cis_data_available_broadcasts_event_to_subscribers() {
        // Verify that when ISO data is available for a CIS, the manager correctly broadcasts
        // an event containing the payload and its metadata to specific data subscribers.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();
        let cis_conn_handle = IsoConnectionHandle::try_from(100).unwrap();

        let (subscriber_sender, mut subscriber_receiver) = mpsc::channel(SUBSCRIBER_EVENT_BUFFER);
        let cis_inner = Arc::new(CisInner {
            conn_handle: cis_conn_handle,
            manager: Weak::new(),
            terminated: AtomicBool::new(false),
            disconnected_sender: broadcast::channel(1).0,
            disconnect_reason: Mutex::new(None),
        });

        iso_registry.lock().unwrap().cis.insert(
            cis_conn_handle,
            CisState {
                data_subscribers: vec![subscriber_sender],
                inner: Arc::downgrade(&cis_inner),
                ..Default::default()
            },
        );

        let data = vec![0x01, 0x02, 0x03, 0x04];
        callbacks.on_cis_data_available(cig_id.into(), cis_conn_handle.into(), 1234, 56, &data);

        expect_that!(
            timeout(TEST_TIMEOUT, subscriber_receiver.recv()).await,
            ok(some(matches_pattern!(IsoDataPacket {
                time_stamp: eq(&Some(Duration::from_micros(1234))),
                seq_nb: eq(&56),
                data: eq(&data),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_setup_iso_data_path_cig_fulfills_pending_request() {
        // Verify that successful data path setup for a CIG-based CIS notifies the requester
        // by fulfilling the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();
        let cis_conn_handle = IsoConnectionHandle::try_from(100).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .setup_iso_data_path
            .insert(cis_conn_handle, sender);

        callbacks.on_setup_iso_data_path_cig(
            HciStatus::Success.into(),
            cis_conn_handle.into(),
            cig_id.into(),
        );

        expect_that!(receiver.await, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_remove_iso_data_path_cig_fulfills_pending_request() {
        // Verify that successful data path removal for a CIG-based CIS notifies the requester
        // by fulfilling the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();
        let cis_conn_handle = IsoConnectionHandle::try_from(100).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .remove_iso_data_path
            .insert(cis_conn_handle, sender);

        callbacks.on_remove_iso_data_path_cig(
            HciStatus::Success.into(),
            cis_conn_handle.into(),
            cig_id.into(),
        );

        expect_that!(receiver.await, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_iso_link_quality_read_fulfills_pending_request_with_stats() {
        // Verify that reading link quality correctly extracts all statistics from the callback
        // and provides them to the requester through the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoCigCallbacks::new(iso_registry.clone());

        let cig_id = CigId::try_from(1).unwrap();
        let cis_conn_handle = IsoConnectionHandle::try_from(200).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .read_iso_link_quality
            .insert(cis_conn_handle, sender);

        callbacks.on_iso_link_quality_read(
            cis_conn_handle.into(),
            cig_id.into(),
            10,
            20,
            30,
            40,
            50,
            60,
            70,
        );

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(&IsoLinkQuality {
                tx_unacked_packets: eq(10),
                tx_flushed_packets: eq(20),
                tx_last_subevent_packets: eq(30),
                retransmitted_packets: eq(40),
                crc_error_packets: eq(50),
                rx_unreceived_packets: eq(60),
                duplicate_packets: eq(70),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_create_big_cmpl_fulfills_pending_request() {
        // Verify that when a BIG is created, the manager fulfills the pending request with all
        // extracted parameters, including connection handles for the broadcast streams.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.create_big.insert(big_handle, sender);

        callbacks.on_create_big_cmpl(
            HciStatus::Success.into(),
            big_handle.into(),
            1000,
            2000,
            1,
            2,
            1,
            0,
            1,
            128,
            10,
            vec![200, 201],
        );

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(CreateBigCmplEvent {
                big_handle: eq(&big_handle),
                big_sync_delay: eq(&1000),
                transport_latency_big: eq(&2000),
                phy: eq(&1),
                nse: eq(&2),
                bn: eq(&1),
                pto: eq(&0),
                irc: eq(&1),
                max_pdu: eq(&128),
                iso_interval: eq(&10),
                bis_conn_handles: eq(&vec![
                    IsoConnectionHandle::try_from(200).unwrap(),
                    IsoConnectionHandle::try_from(201).unwrap()
                ]),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_terminate_big_cmpl_fulfills_pending_request() {
        // Verify that when a BIG is terminated, the manager notifies the requester of the success
        // status via the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.terminate_big.insert(big_handle, sender);

        callbacks.on_terminate_big_cmpl(1, HciStatus::Success.into());

        expect_that!(receiver.await, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_big_sync_established_fulfills_pending_request() {
        // Verify that when synchronization to a BIG is established, the manager fulfills the
        // pending request with the extracted transport parameters and connection handles.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.big_create_sync.insert(big_handle, sender);

        callbacks.on_big_sync_established(
            HciStatus::Success.into(),
            big_handle.into(),
            2000,
            2,
            1,
            0,
            1,
            128,
            10,
            vec![15, 16],
        );

        expect_that!(
            receiver.await,
            ok(ok(matches_pattern!(BigSyncEstablishedEvent {
                big_handle: eq(&big_handle),
                transport_latency_big: eq(&2000),
                nse: eq(&2),
                bn: eq(&1),
                pto: eq(&0),
                irc: eq(&1),
                max_pdu: eq(&128),
                iso_interval: eq(&10),
                bis_conn_handles: eq(&vec![
                    IsoConnectionHandle::try_from(15).unwrap(),
                    IsoConnectionHandle::try_from(16).unwrap()
                ]),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_big_sync_lost_broadcasts_event_to_subscribers() {
        // Verify that when synchronization to a BIG is lost, the manager correctly broadcasts
        // a sync lost event with the appropriate reason to specific event subscribers.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();

        let (lost_sender, mut lost_receiver) = broadcast::channel(SUBSCRIBER_EVENT_BUFFER);
        let big_inner = Arc::new(BigInner {
            big_handle,
            manager: Weak::new(),
            bis_connections: vec![],
            terminated: Arc::new(AtomicBool::new(false)),
            lost_sender: lost_sender.clone(),
            lost_reason: Mutex::new(None),
            is_source: false,
        });

        iso_registry.lock().unwrap().bigs.insert(
            big_handle,
            BigState { lost_sender: Some(lost_sender), inner: Arc::downgrade(&big_inner) },
        );

        callbacks
            .on_big_sync_lost(big_handle.into(), HciStatus::ConnectionFailedEstablishment.into());

        expect_that!(
            timeout(TEST_TIMEOUT, lost_receiver.recv()).await,
            ok(ok(eq(&HciStatus::ConnectionFailedEstablishment)))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_big_terminate_sync_cmpl_fulfills_pending_request() {
        // Verify that when BIG sync termination completes, the manager notifies the requester
        // by fulfilling the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry.lock().unwrap().pending_requests.big_terminate_sync.insert(big_handle, sender);

        callbacks.on_big_terminate_sync_cmpl(HciStatus::Success.into(), big_handle.into());

        expect_that!(receiver.await, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_bis_data_available_broadcasts_event_to_subscribers() {
        // Verify that when ISO data is available for a BIS, the manager correctly broadcasts
        // an event containing the payload and its metadata to specific data subscribers.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();
        let bis_conn_handle = IsoConnectionHandle::try_from(200).unwrap();

        let (subscriber_sender, mut subscriber_receiver) = mpsc::channel(SUBSCRIBER_EVENT_BUFFER);
        iso_registry
            .lock()
            .unwrap()
            .bis
            .insert(bis_conn_handle, BisState { data_subscribers: vec![subscriber_sender] });

        let data = vec![0xAA, 0xBB, 0xCC, 0xDD];
        callbacks.on_bis_data_available(big_handle.into(), bis_conn_handle.into(), 5678, 99, &data);

        expect_that!(
            timeout(TEST_TIMEOUT, subscriber_receiver.recv()).await,
            ok(some(matches_pattern!(IsoDataPacket {
                time_stamp: eq(&Some(Duration::from_micros(5678))),
                seq_nb: eq(&99),
                data: eq(&data),
            })))
        );
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_setup_iso_data_path_big_fulfills_pending_request() {
        // Verify that successful data path setup for a BIG-based BIS notifies the requester
        // by fulfilling the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();
        let bis_conn_handle = IsoConnectionHandle::try_from(200).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .setup_iso_data_path
            .insert(bis_conn_handle, sender);

        callbacks.on_setup_iso_data_path_big(
            HciStatus::Success.into(),
            bis_conn_handle.into(),
            big_handle.into(),
        );

        expect_that!(receiver.await, ok(ok(anything())));
    }

    #[googletest::test]
    #[tokio::test]
    async fn test_on_remove_iso_data_path_big_fulfills_pending_request() {
        // Verify that successful data path removal for a BIG-based BIS notifies the requester
        // by fulfilling the pending request channel.
        let iso_registry = Arc::new(Mutex::new(IsoRegistry::default()));
        let callbacks = IsoBigCallbacks::new(iso_registry.clone());

        let big_handle = BigHandle::try_from(1).unwrap();
        let bis_conn_handle = IsoConnectionHandle::try_from(200).unwrap();

        let (sender, receiver) = oneshot::channel();
        iso_registry
            .lock()
            .unwrap()
            .pending_requests
            .remove_iso_data_path
            .insert(bis_conn_handle, sender);

        callbacks.on_remove_iso_data_path_big(
            HciStatus::Success.into(),
            bis_conn_handle.into(),
            big_handle.into(),
        );

        expect_that!(receiver.await, ok(ok(anything())));
    }
}
