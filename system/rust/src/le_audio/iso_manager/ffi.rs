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
    CisDisconnectedEvent, CisEstablishedEvent, CreateCigCmplEvent, IsoRegistry,
};
use crate::le_audio::iso_manager::traits::{
    CigId, IsoConnectionHandle, IsoDataPacket, IsoLinkQuality, IsoManagerError,
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
            let _ = sender.send(if status == HciStatus::Success {
                Ok(CreateCigCmplEvent {
                    cig_id,
                    cis_conn_handles: cis_conn_handles_raw
                        .into_iter()
                        .map(|conn_handle| IsoConnectionHandle::try_from(conn_handle).unwrap())
                        .collect(),
                })
            } else {
                Err(IsoManagerError::HciError(status))
            });
        }
    }

    pub fn on_remove_cig_cmpl(&self, status_raw: u8, cig_id_raw: u8) {
        let status = HciStatus::try_from(status_raw).unwrap_or(HciStatus::StatusUnknown);
        let cig_id = CigId::try_from(cig_id_raw).unwrap();

        if let Some(sender) =
            self.iso_registry.lock().unwrap().pending_requests.remove_cig.remove(&cig_id)
        {
            let _ = sender.send(if status == HciStatus::Success {
                Ok(())
            } else {
                Err(IsoManagerError::HciError(status))
            });
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
            let _ = sender.send(if status == HciStatus::Success {
                Ok(CisEstablishedEvent {
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
            } else {
                Err(IsoManagerError::HciError(status))
            });
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
                let packet = IsoDataPacket {
                    time_stamp: Some(Duration::from_micros(time_stamp as u64)),

                    seq_nb,
                    data: data.to_vec(),
                };
                iso_registry.dispatch_cis_data(cis_conn_handle, packet);
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
            let _ = sender.send(if status == HciStatus::Success {
                Ok(())
            } else {
                Err(IsoManagerError::HciError(status))
            });
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
            let _ = sender.send(if status == HciStatus::Success {
                Ok(())
            } else {
                Err(IsoManagerError::HciError(status))
            });
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
