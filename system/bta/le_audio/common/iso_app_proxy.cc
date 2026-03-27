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

#include "iso_app_proxy.h"

#include <algorithm>
#include <deque>
#include <map>
#include <unordered_set>
#include <vector>

#include "stack/btm/btm_dev.h"
#include "stack/include/hci_error_code.h"

namespace bluetooth {

class IsoAppProxy::impl : public hci::iso_manager::CigCallbacks {
public:
  impl(hci::iso_manager::CigCallbacks* cig_callbacks, hci::iso_manager::BigCallbacks* big_callbacks,
       std::function<void(bool)> iso_traffic_active_callback) {
    iso_app_callbacks_.cig_callbacks = cig_callbacks;
    iso_app_callbacks_.big_callbacks = big_callbacks;
    iso_app_callbacks_.iso_traffic_active_callback = iso_traffic_active_callback;

    hci::IsoManager::GetInstance()->Start();

    iso_client_handle_ =
            hci::IsoManager::GetInstance()->RegisterCallbacks(hci::iso_manager::IsoManagerCallbacks{
                    .cig_callbacks = this,  // proxy callback to track the CIG/CIS states
                    .big_callbacks = big_callbacks,
                    .iso_traffic_active_callback = iso_traffic_active_callback,
            });
    log::debug("Registered to IsoManager with client_handle: {}", iso_client_handle_);
  }

  ~impl() {
    log::debug("Deregistered in IsoManager with client_handle: {}", iso_client_handle_);
    hci::IsoManager::GetInstance()->DeregisterCallbacks(iso_client_handle_);
  }

  /**
   * @brief Disallow cloning due to owning an individual ISO client handle
   */
  impl(const impl&) = delete;
  impl& operator=(const impl&) = delete;

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override {
    auto iter = pending_data_path_req_by_conn_hdl_.find(conn_handle);
    if (iter == pending_data_path_req_by_conn_hdl_.end() || iter->second.empty()) {
      return;
    }

    auto params = iter->second.front();
    iter->second.pop_front();
    if (iter->second.empty()) {
      pending_data_path_req_by_conn_hdl_.erase(iter);
    }

    if (status == HCI_SUCCESS) {
      setup_iso_data_path_req_by_conn_hdl_[conn_handle].push_back(params);
    }
    iso_app_callbacks_.cig_callbacks->OnSetupIsoDataPath(status, conn_handle, cig_id);
  }

  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override {
    auto pending_it = pending_data_path_remove_by_conn_hdl_.find(conn_handle);
    if (pending_it == pending_data_path_remove_by_conn_hdl_.end() || pending_it->second.empty()) {
      return;
    }

    uint8_t data_path_dir = pending_it->second.front();
    pending_it->second.pop_front();

    if (pending_it->second.empty()) {
      pending_data_path_remove_by_conn_hdl_.erase(pending_it);
    }

    if (status == HCI_SUCCESS) {
      auto setup_it = setup_iso_data_path_req_by_conn_hdl_.find(conn_handle);
      if (setup_it != setup_iso_data_path_req_by_conn_hdl_.end()) {
        auto& setup_paths = setup_it->second;
        auto path_to_erase_it = std::find_if(setup_paths.begin(), setup_paths.end(),
                                             [data_path_dir](const auto& params) {
                                               return params.data_path_dir == data_path_dir;
                                             });
        if (path_to_erase_it != setup_paths.end()) {
          setup_paths.erase(path_to_erase_it);
        }

        if (setup_paths.empty()) {
          setup_iso_data_path_req_by_conn_hdl_.erase(setup_it);
        }
      }
    }
    iso_app_callbacks_.cig_callbacks->OnRemoveIsoDataPath(status, conn_handle, cig_id);
  }

  void OnIsoLinkQualityRead(uint16_t conn_handle, uint8_t cig_id, uint32_t tx_unacked_packets,
                            uint32_t tx_flushed_packets, uint32_t tx_last_subevent_packets,
                            uint32_t retransmitted_packets, uint32_t crc_error_packets,
                            uint32_t rx_unreceived_packets, uint32_t duplicate_packets) override {
    iso_app_callbacks_.cig_callbacks->OnIsoLinkQualityRead(
            conn_handle, cig_id, tx_unacked_packets, tx_flushed_packets, tx_last_subevent_packets,
            retransmitted_packets, crc_error_packets, rx_unreceived_packets, duplicate_packets);
  }

  void OnCisEvent(uint8_t event_type, void* data) override {
    switch (event_type) {
      case hci::iso_manager::kIsoEventCisDataAvailable: {
        iso_app_callbacks_.cig_callbacks->OnCisEvent(event_type, data);
      } break;

      case hci::iso_manager::kIsoEventCisEstablishCmpl: {
        const auto* evt = static_cast<hci::iso_manager::cis_establish_cmpl_evt*>(data);

        auto pending_cis_est = pending_cis_establish_conn_handles_.extract(evt->cis_conn_hdl);
        if (pending_cis_est.empty()) {
          return;
        }

        iso_app_callbacks_.cig_callbacks->OnCisEvent(event_type, data);

        if (evt->status == HCI_SUCCESS) {
          cis_established_conn_handles_.insert(evt->cis_conn_hdl);
        }
      } break;

      case hci::iso_manager::kIsoEventCisDisconnected: {
        const auto* evt = static_cast<hci::iso_manager::cis_disconnected_evt*>(data);

        iso_app_callbacks_.cig_callbacks->OnCisEvent(event_type, data);
        setup_iso_data_path_req_by_conn_hdl_.erase(evt->cis_conn_hdl);
        cis_established_conn_handles_.erase(evt->cis_conn_hdl);
      } break;

      case hci::iso_manager::kIsoEventCisRequest: {
        const auto* evt = static_cast<hci::iso_manager::cis_request_evt*>(data);
        auto* peer_device = btm_find_dev_by_handle(evt->acl_conn_hdl);
        if (!peer_device) {
          log::error("Missing security record for acl handle: 0x{:04x}, rejecting CIS request",
                     evt->acl_conn_hdl);
          RejectIncomingCisConnection(evt->cis_conn_hdl, HCI_ERR_HOST_REJECT_SECURITY);
          return;
        }

        log::debug("CIS request event, peer: {}, cig_id: {}, cis_conn_hdl: {}, cis_id: {}",
                   peer_device->ble.pseudo_addr, evt->cig_id, evt->cis_conn_hdl, evt->cis_id);

        pending_cis_establish_conn_handles_.insert(evt->cis_conn_hdl);
        iso_app_callbacks_.cig_callbacks->OnCisEvent(event_type, data);
      } break;

      case hci::iso_manager::kIsoEventCisRequestRejectStatus: {
        iso_app_callbacks_.cig_callbacks->OnCisEvent(event_type, data);
      } break;

      default:
        break;
    }
  }

  void OnCigEvent(uint8_t event, void* data) override {
    iso_app_callbacks_.cig_callbacks->OnCigEvent(event, data);
  }

  void AddIncomingCisEventsListener(const RawAddress& device, uint8_t cig_id, uint8_t cis_id) {
    log::debug("peer: {}, cig_id: {}, cis_id: {}", device, cig_id, cis_id);
    hci::IsoManager::GetInstance()->AddIncomingCisEventsListener(iso_client_handle_, device, cig_id,
                                                                 cis_id);
  }

  void RemoveIncomingCisEventsListener(const RawAddress& device, uint8_t cig_id, uint8_t cis_id) {
    log::debug("peer: {}, cig_id: {}, cis_id: {}", device, cig_id, cis_id);
    hci::IsoManager::GetInstance()->RemoveIncomingCisEventsListener(iso_client_handle_, device,
                                                                    cig_id, cis_id);
  }

  void SetupIsoDataPath(uint16_t conn_handle, hci::iso_manager::iso_data_path_params path_params) {
    pending_data_path_req_by_conn_hdl_[conn_handle].push_back(path_params);
    hci::IsoManager::GetInstance()->SetupIsoDataPath(conn_handle, path_params);
  }

  void RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir) {
    if (HasCisDatapathSetup(conn_handle)) {
      pending_data_path_remove_by_conn_hdl_[conn_handle].push_back(data_path_dir);
      hci::IsoManager::GetInstance()->RemoveIsoDataPath(conn_handle, data_path_dir);
    }
  }

  void SendIsoData(uint16_t conn_handle, const uint8_t* data, uint16_t data_len) {
    if (HasCisConnected(conn_handle) && HasCisDatapathSetup(conn_handle)) {
      hci::IsoManager::GetInstance()->SendIsoData(conn_handle, data, data_len);
    }
  }

  void AcceptIncomingCisConnection(uint16_t conn_handle) {
    if (!IsValidPendingCis(conn_handle)) {
      return;
    }
    hci::IsoManager::GetInstance()->AcceptIncomingCisConnection(conn_handle);
  }

  void RejectIncomingCisConnection(uint16_t conn_handle, uint8_t reason) {
    if (!IsValidPendingCis(conn_handle)) {
      return;
    }
    hci::IsoManager::GetInstance()->RejectIncomingCisConnection(conn_handle, reason);
  }

  bool HasCisConnected(uint16_t cis_conn_handle) const {
    return cis_established_conn_handles_.contains(cis_conn_handle);
  }

  bool HasCisDatapathSetup(uint16_t cis_conn_handle) const {
    return setup_iso_data_path_req_by_conn_hdl_.contains(cis_conn_handle);
  }

  bool IsValidPendingCis(uint16_t conn_handle) const {
    if (!pending_cis_establish_conn_handles_.contains(conn_handle)) {
      log::error("Unknown cis_conn_hdl: {}", conn_handle);
      return false;
    }
    return true;
  }

private:
  hci::iso_manager::IsoClientHandle iso_client_handle_ = hci::iso_manager::kInvalidIsoClientHandle;
  hci::iso_manager::IsoManagerCallbacks iso_app_callbacks_;

  std::unordered_map<uint16_t, std::deque<hci::iso_manager::iso_data_path_params>>
          pending_data_path_req_by_conn_hdl_;
  std::unordered_map<uint16_t, std::vector<hci::iso_manager::iso_data_path_params>>
          setup_iso_data_path_req_by_conn_hdl_;
  std::unordered_map<uint16_t, std::deque<uint8_t>> pending_data_path_remove_by_conn_hdl_;
  std::unordered_set<uint16_t> pending_cis_establish_conn_handles_;
  std::unordered_set<uint16_t> cis_established_conn_handles_;
};

IsoAppProxy::IsoAppProxy(hci::iso_manager::CigCallbacks* cig_callbacks,
                         hci::iso_manager::BigCallbacks* big_callbacks,
                         std::function<void(bool)> iso_traffic_active_callback) {
  pimpl_ = std::make_unique<IsoAppProxy::impl>(cig_callbacks, big_callbacks,
                                               iso_traffic_active_callback);
}

IsoAppProxy::~IsoAppProxy() { pimpl_.reset(); }

void IsoAppProxy::AddIncomingCisEventsListener(const RawAddress& device, uint8_t cig_id,
                                               uint8_t cis_id) {
  pimpl_->AddIncomingCisEventsListener(device, cig_id, cis_id);
}

void IsoAppProxy::RemoveIncomingCisEventsListener(const RawAddress& device, uint8_t cig_id,
                                                  uint8_t cis_id) {
  pimpl_->RemoveIncomingCisEventsListener(device, cig_id, cis_id);
}

void IsoAppProxy::SetupIsoDataPath(uint16_t conn_handle,
                                   struct hci::iso_manager::iso_data_path_params path_params) {
  pimpl_->SetupIsoDataPath(conn_handle, path_params);
}

void IsoAppProxy::RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir) {
  pimpl_->RemoveIsoDataPath(conn_handle, data_path_dir);
}

void IsoAppProxy::SendIsoData(uint16_t conn_handle, const uint8_t* data, uint16_t data_len) {
  pimpl_->SendIsoData(conn_handle, data, data_len);
}

void IsoAppProxy::AcceptIncomingCisConnection(uint16_t conn_handle) {
  pimpl_->AcceptIncomingCisConnection(conn_handle);
}

void IsoAppProxy::RejectIncomingCisConnection(uint16_t conn_handle, uint8_t reason) {
  pimpl_->RejectIncomingCisConnection(conn_handle, reason);
}

bool IsoAppProxy::HasCisConnected(uint16_t cis_conn_handle) const {
  if (cis_conn_handle == INVALID_ACL_HANDLE) {
    return false;
  }
  return pimpl_->HasCisConnected(cis_conn_handle);
}

}  // namespace bluetooth
