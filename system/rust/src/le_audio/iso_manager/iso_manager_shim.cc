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

#include "iso_manager/iso_manager_shim.h"

#include <bluetooth/log.h>
#include <rust/cxx.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

#include "iso_manager/ffi.rs.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/btm_iso_api_types.h"

namespace bluetooth::hci::iso_manager {

namespace iso_manager_ffi = ::bluetooth::hci::iso_manager::ffi;

std::unique_ptr<IsoManagerShim> GetIsoManagerShim() { return std::make_unique<IsoManagerShim>(); }

IsoManagerShim::IsoManagerShim() : client_handle_(0) {
  iso_manager_ = IsoManager::GetInstance();
  iso_manager_->Start();
}

IsoManagerShim::~IsoManagerShim() {
  if (client_handle_ != 0) {
    iso_manager_->DeregisterCallbacks(client_handle_);
  }
}

void IsoManagerShim::RegisterCallbacksNative(
        rust::Box<iso_manager_ffi::IsoCigCallbacks> cig_callbacks,
        rust::Box<iso_manager_ffi::IsoBigCallbacks> big_callbacks) {
  cig_callbacks_shim_ = std::make_unique<CigCallbacksShim>(std::move(cig_callbacks));
  big_callbacks_shim_ = std::make_unique<BigCallbacksShim>(std::move(big_callbacks));
  client_handle_ = iso_manager_->RegisterCallbacks(
          {.cig_callbacks = cig_callbacks_shim_.get(), .big_callbacks = big_callbacks_shim_.get()});
}

void IsoManagerShim::CreateCig(uint8_t cig_id, uint32_t sdu_interval_c_to_p,
                               uint32_t sdu_interval_p_to_c, uint8_t worse_cast_sca, bool packing,
                               bool framing, uint16_t max_transport_latency_c_to_p,
                               uint16_t max_transport_latency_p_to_c, rust::Vec<uint8_t> cis_ids,
                               rust::Vec<uint16_t> max_sdu_c_to_p,
                               rust::Vec<uint16_t> max_sdu_p_to_c, rust::Vec<uint8_t> phy_c_to_p,
                               rust::Vec<uint8_t> phy_p_to_c, rust::Vec<uint8_t> rtn_c_to_p,
                               rust::Vec<uint8_t> rtn_p_to_c) {
  std::vector<EXT_CIS_CFG> cis_configurations;
  cis_configurations.reserve(cis_ids.size());
  for (size_t i = 0; i < cis_ids.size(); ++i) {
    cis_configurations.push_back(EXT_CIS_CFG{
            .cis_id = cis_ids[i],
            .max_sdu_size_c_to_p = max_sdu_c_to_p[i],
            .max_sdu_size_p_to_c = max_sdu_p_to_c[i],
            .phy_c_to_p = phy_c_to_p[i],
            .phy_p_to_c = phy_p_to_c[i],
            .rtn_c_to_p = rtn_c_to_p[i],
            .rtn_p_to_c = rtn_p_to_c[i],
    });
  }

  cig_create_params cpp_params{
          .sdu_itv_c_to_p = sdu_interval_c_to_p,
          .sdu_itv_p_to_c = sdu_interval_p_to_c,
          .sca = worse_cast_sca,
          .packing = static_cast<uint8_t>(packing),
          .framing = static_cast<uint8_t>(framing),
          .max_trans_lat_c_to_p = max_transport_latency_c_to_p,
          .max_trans_lat_p_to_c = max_transport_latency_p_to_c,
          .cis_cfgs = std::move(cis_configurations),
  };
  iso_manager_->CreateCig(client_handle_, cig_id, cpp_params);
}

void IsoManagerShim::ReconfigureCig(uint8_t cig_id, uint32_t sdu_interval_c_to_p,
                                    uint32_t sdu_interval_p_to_c, uint8_t worse_cast_sca,
                                    bool packing, bool framing,
                                    uint16_t max_transport_latency_c_to_p,
                                    uint16_t max_transport_latency_p_to_c,
                                    rust::Vec<uint8_t> cis_ids, rust::Vec<uint16_t> max_sdu_c_to_p,
                                    rust::Vec<uint16_t> max_sdu_p_to_c,
                                    rust::Vec<uint8_t> phy_c_to_p, rust::Vec<uint8_t> phy_p_to_c,
                                    rust::Vec<uint8_t> rtn_c_to_p, rust::Vec<uint8_t> rtn_p_to_c) {
  std::vector<EXT_CIS_CFG> cis_configurations;
  cis_configurations.reserve(cis_ids.size());
  for (size_t i = 0; i < cis_ids.size(); ++i) {
    cis_configurations.push_back(EXT_CIS_CFG{
            .cis_id = cis_ids[i],
            .max_sdu_size_c_to_p = max_sdu_c_to_p[i],
            .max_sdu_size_p_to_c = max_sdu_p_to_c[i],
            .phy_c_to_p = phy_c_to_p[i],
            .phy_p_to_c = phy_p_to_c[i],
            .rtn_c_to_p = rtn_c_to_p[i],
            .rtn_p_to_c = rtn_p_to_c[i],
    });
  }

  cig_create_params cpp_params{
          .sdu_itv_c_to_p = sdu_interval_c_to_p,
          .sdu_itv_p_to_c = sdu_interval_p_to_c,
          .sca = worse_cast_sca,
          .packing = static_cast<uint8_t>(packing),
          .framing = static_cast<uint8_t>(framing),
          .max_trans_lat_c_to_p = max_transport_latency_c_to_p,
          .max_trans_lat_p_to_c = max_transport_latency_p_to_c,
          .cis_cfgs = std::move(cis_configurations),
  };
  iso_manager_->ReconfigureCig(cig_id, cpp_params);
}

void IsoManagerShim::RemoveCig(uint8_t cig_id, bool force) {
  iso_manager_->RemoveCig(cig_id, force);
}

void IsoManagerShim::CreateCis(rust::Vec<uint16_t> cis_conn_handles,
                               rust::Vec<uint16_t> acl_conn_handles) {
  cis_establish_params cpp_params;
  cpp_params.conn_pairs.reserve(cis_conn_handles.size());
  for (size_t i = 0; i < cis_conn_handles.size(); ++i) {
    cpp_params.conn_pairs.push_back(EXT_CIS_CREATE_CFG{.cis_conn_handle = cis_conn_handles[i],
                                                       .acl_conn_handle = acl_conn_handles[i]});
  }

  iso_manager_->EstablishCis(cpp_params);
}

void IsoManagerShim::DisconnectCis(uint16_t conn_handle, uint8_t reason) {
  iso_manager_->DisconnectCis(conn_handle, reason);
}

void IsoManagerShim::CreateBig(uint8_t big_handle, uint8_t advertising_handle, uint8_t num_bis,
                               uint32_t sdu_interval, uint16_t max_sdu_size,
                               uint16_t max_transport_latency, uint8_t rtn, uint8_t phy,
                               bool packing, bool framing, bool encryption,
                               std::array<uint8_t, 16> broadcast_code) {
  big_create_params cpp_params{
          .adv_handle = advertising_handle,
          .num_bis = num_bis,
          .sdu_itv = sdu_interval,
          .max_sdu_size = max_sdu_size,
          .max_transport_latency = max_transport_latency,
          .rtn = rtn,
          .phy = phy,
          .packing = static_cast<uint8_t>(packing),
          .framing = static_cast<uint8_t>(framing),
          .enc = encryption,
          .enc_code = broadcast_code,
  };
  iso_manager_->CreateBig(client_handle_, big_handle, cpp_params);
}

void IsoManagerShim::TerminateBig(uint8_t big_handle, uint8_t reason) {
  iso_manager_->TerminateBig(big_handle, reason);
}

void IsoManagerShim::BigCreateSync(uint8_t big_handle, uint16_t sync_handle, bool encryption,
                                   std::array<uint8_t, 16> broadcast_code, uint8_t mse,
                                   uint16_t big_sync_timeout, rust::Vec<uint8_t> bis_indices) {
  big_create_sync_params cpp_params{
          .big_handle = big_handle,
          .sync_handle = sync_handle,
          .encryption = static_cast<uint8_t>(encryption),
          .broadcast_code = broadcast_code,
          .mse = mse,
          .big_sync_timeout = big_sync_timeout,
          .bis = std::vector<uint8_t>(bis_indices.begin(), bis_indices.end()),
  };

  iso_manager_->BigCreateSync(client_handle_, cpp_params);
}

void IsoManagerShim::BigTerminateSync(uint8_t big_handle) {
  iso_manager_->BigTerminateSync(big_handle);
}

void IsoManagerShim::SetupIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir,
                                      uint8_t data_path_id, uint8_t coding_format,
                                      uint16_t company_id, uint16_t vendor_specific_codec_id,
                                      uint32_t controller_delay,
                                      rust::Vec<uint8_t> codec_configuration) {
  iso_data_path_params cpp_params{
          .data_path_dir = data_path_dir,
          .data_path_id = data_path_id,
          .codec_id_format = coding_format,
          .codec_id_company = company_id,
          .codec_id_vendor = vendor_specific_codec_id,
          .controller_delay = controller_delay,
          .codec_conf =
                  std::vector<uint8_t>(codec_configuration.begin(), codec_configuration.end()),
  };
  iso_manager_->SetupIsoDataPath(conn_handle, cpp_params);
}

void IsoManagerShim::RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir) {
  iso_manager_->RemoveIsoDataPath(conn_handle, data_path_dir);
}

void IsoManagerShim::SendIsoData(uint16_t conn_handle, rust::Slice<const uint8_t> data) {
  iso_manager_->SendIsoData(conn_handle, data.data(), static_cast<uint16_t>(data.size()));
}

void IsoManagerShim::ReadIsoLinkQuality(uint16_t conn_handle) {
  iso_manager_->ReadIsoLinkQuality(conn_handle);
}

//--- CIG Callbacks Implementation ---

CigCallbacksShim::CigCallbacksShim(rust::Box<iso_manager_ffi::IsoCigCallbacks> cb)
    : callbacks_(std::move(cb)) {}

void CigCallbacksShim::OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) {
  callbacks_->OnSetupIsoDataPath(status, conn_handle, cig_id);
}

void CigCallbacksShim::OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) {
  callbacks_->OnRemoveIsoDataPath(status, conn_handle, cig_id);
}

void CigCallbacksShim::OnIsoLinkQualityRead(
        uint16_t conn_handle, uint8_t cig_id, uint32_t tx_unacked_packets,
        uint32_t tx_flushed_packets, uint32_t tx_last_subevent_packets,
        uint32_t retransmitted_packets, uint32_t crc_error_packets, uint32_t rx_unreceived_packets,
        uint32_t duplicate_packets) {
  callbacks_->OnIsoLinkQualityRead(conn_handle, cig_id, tx_unacked_packets, tx_flushed_packets,
                                   tx_last_subevent_packets, retransmitted_packets,
                                   crc_error_packets, rx_unreceived_packets, duplicate_packets);
}

void CigCallbacksShim::OnCisEvent(uint8_t event, void* data) {
  if (!data) {
    return;
  }

  switch (event) {
    case kIsoEventCisEstablishCmpl: {
      auto* evt = static_cast<cis_establish_cmpl_evt*>(data);
      callbacks_->OnCisEstablished(
              evt->status, evt->cig_id, evt->cis_conn_hdl, evt->cig_sync_delay, evt->cis_sync_delay,
              evt->trans_lat_c_to_p, evt->trans_lat_p_to_c, evt->phy_c_to_p, evt->phy_p_to_c,
              evt->nse, evt->bn_c_to_p, evt->bn_p_to_c, evt->ft_c_to_p, evt->ft_p_to_c,
              evt->max_pdu_c_to_p, evt->max_pdu_p_to_c, evt->iso_itv);
      break;
    }
    case kIsoEventCisDisconnected: {
      auto* evt = static_cast<cis_disconnected_evt*>(data);
      callbacks_->OnCisDisconnected(evt->reason, evt->cig_id, evt->cis_conn_hdl);
      break;
    }
    case kIsoEventCisDataAvailable: {
      auto* evt = static_cast<cis_data_evt*>(data);
      callbacks_->OnCisDataAvailable(
              evt->cig_id, evt->cis_conn_hdl, evt->ts, evt->seq_nb,
              rust::Slice<const uint8_t>(evt->p_msg->data + evt->p_msg->offset,
                                         evt->p_msg->len - evt->p_msg->offset));
      break;
    }
    default:
      log::warn("Unhandled CIS event: {}", event);
      break;
  }
}

void CigCallbacksShim::OnCigEvent(uint8_t event, void* data) {
  switch (event) {
    case kIsoEventCigOnCreateCmpl:
    case kIsoEventCigOnReconfigureCmpl: {
      auto* evt = static_cast<cig_create_cmpl_evt*>(data);
      rust::Vec<uint16_t> cis_conn_handles;
      cis_conn_handles.reserve(evt->conn_handles.size());
      for (const auto& handle : evt->conn_handles) {
        cis_conn_handles.push_back(handle);
      }
      callbacks_->OnCreateCigCmpl(evt->status, evt->cig_id, std::move(cis_conn_handles));
      break;
    }
    case kIsoEventCigOnRemoveCmpl: {
      auto* evt = static_cast<cig_remove_cmpl_evt*>(data);
      callbacks_->OnRemoveCigCmpl(evt->status, evt->cig_id);
      break;
    }
    default:
      log::warn("Unhandled CIG event: {}", event);
      break;
  }
}

//--- BIG Callbacks Implementation ---

BigCallbacksShim::BigCallbacksShim(rust::Box<iso_manager_ffi::IsoBigCallbacks> cb)
    : callbacks_(std::move(cb)) {}

void BigCallbacksShim::OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle,
                                          uint8_t big_handle) {
  callbacks_->OnSetupIsoDataPath(status, conn_handle, big_handle);
}

void BigCallbacksShim::OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle,
                                           uint8_t big_handle) {
  callbacks_->OnRemoveIsoDataPath(status, conn_handle, big_handle);
}

void BigCallbacksShim::OnBisEvent(uint8_t event, void* data) {
  switch (event) {
    case kIsoEventBisDataAvailable: {
      auto* evt = static_cast<bis_data_evt*>(data);
      callbacks_->OnBisDataAvailable(
              evt->big_handle, evt->bis_conn_hdl, evt->ts, evt->seq_nb,
              rust::Slice<const uint8_t>(evt->p_msg->data + evt->p_msg->offset,
                                         evt->p_msg->len - evt->p_msg->offset));
      break;
    }
    default:
      log::warn("Unhandled BIS event: {}", event);
      break;
  }
}

void BigCallbacksShim::OnBigSourceEvent(BigSourceEvent event, void* data) {
  switch (event) {
    case BigSourceEvent::kCreateCmpl: {
      auto* evt = static_cast<big_create_cmpl_evt*>(data);
      rust::Vec<uint16_t> conn_handles;
      conn_handles.reserve(evt->conn_handles.size());
      for (const auto& handle : evt->conn_handles) {
        conn_handles.push_back(handle);
      }
      callbacks_->OnCreateBigCmpl(evt->status, evt->big_handle, evt->big_sync_delay,
                                  evt->transport_latency_big, evt->phy, evt->nse, evt->bn, evt->pto,
                                  evt->irc, evt->max_pdu, evt->iso_interval,
                                  std::move(conn_handles));
      break;
    }
    case BigSourceEvent::kTerminateCmpl: {
      auto* evt = static_cast<big_terminate_cmpl_evt*>(data);
      callbacks_->OnTerminateBigCmpl(evt->big_handle, evt->reason);
      break;
    }
    default:
      log::warn("Unhandled BIG event: {}", static_cast<uint8_t>(event));
      break;
  }
}

void BigCallbacksShim::OnBigSinkEvent(BigSinkEvent event, void* data) {
  switch (event) {
    case BigSinkEvent::kSyncEst: {
      auto* evt = static_cast<big_sync_est_evt*>(data);
      rust::Vec<uint16_t> conn_handles;
      conn_handles.reserve(evt->conn_handles.size());
      for (const auto& handle : evt->conn_handles) {
        conn_handles.push_back(handle);
      }
      callbacks_->OnBigSyncEstablished(evt->status, evt->big_handle, evt->transport_latency_big,
                                       evt->nse, evt->bn, evt->pto, evt->irc, evt->max_pdu,
                                       evt->iso_interval, std::move(conn_handles));
      break;
    }
    case BigSinkEvent::kSyncLost: {
      auto* evt = static_cast<big_sync_lost_evt*>(data);
      callbacks_->OnBigSyncLost(evt->big_handle, evt->reason);
      break;
    }
    case BigSinkEvent::kTerminateSyncCmpl: {
      auto* evt = static_cast<big_terminate_sync_cmpl_evt*>(data);
      callbacks_->OnBigTerminateSyncCmpl(evt->status, evt->big_handle);
      break;
    }
    default:
      log::warn("Unhandled BIG event: {}", static_cast<uint8_t>(event));
      break;
  }
}

}  // namespace bluetooth::hci::iso_manager
