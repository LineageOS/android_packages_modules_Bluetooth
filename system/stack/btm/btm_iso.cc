/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <memory>
#include <utility>
#include <vector>

#include "bluetooth/log.h"
#include "btm/btm_iso_impl.h"
#include "include/btm_iso_api.h"
#include "include/btm_iso_api_types.h"
#include "stack/include/bt_hdr.h"

namespace bluetooth {
namespace hci {

using ::bluetooth::hci::iso_manager::big_create_params;
using ::bluetooth::hci::iso_manager::big_create_sync_params;
using ::bluetooth::hci::iso_manager::cig_create_params;
using ::bluetooth::hci::iso_manager::cis_establish_params;
using ::bluetooth::hci::iso_manager::iso_data_path_params;
using ::bluetooth::hci::iso_manager::iso_impl;
using ::bluetooth::hci::iso_manager::IsoClientHandle;
using ::bluetooth::hci::iso_manager::IsoManagerCallbacks;
using ::bluetooth::hci::iso_manager::kInvalidIsoClientHandle;

struct IsoManager::impl {
  explicit impl(const IsoManager& iso_manager) : iso_manager_(iso_manager) {}

  void Start() {
    log::assert_that(iso_impl_ == nullptr, "assert failed: iso_impl_ == nullptr");
    iso_impl_ = std::make_unique<iso_impl>();
  }

  void Stop() {
    log::assert_that(iso_impl_ != nullptr, "assert failed: iso_impl_ != nullptr");
    iso_impl_.reset();
  }

  void Dump(int fd) {
    if (iso_impl_) {
      iso_impl_->dump(fd);
    }
  }

  bool IsRunning() { return iso_impl_ ? true : false; }

  const IsoManager& iso_manager_;
  std::unique_ptr<iso_impl> iso_impl_;
};

IsoManager::IsoManager() : pimpl_(std::make_unique<impl>(*this)) {}

IsoClientHandle IsoManager::RegisterCallbacks(IsoManagerCallbacks callbacks) const {
  if (pimpl_->IsRunning()) {
    return pimpl_->iso_impl_->register_callbacks(callbacks);
  }
  return kInvalidIsoClientHandle;
}

void IsoManager::DeregisterCallbacks(IsoClientHandle client_handle) const {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->deregister_callbacks(client_handle);
  }
}

void IsoManager::CreateCig(IsoClientHandle client_handle, uint8_t cig_id,
                           struct cig_create_params cig_params) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->create_cig(client_handle, cig_id, std::move(cig_params));
  }
}

void IsoManager::ReconfigureCig(uint8_t cig_id, struct cig_create_params cig_params) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->reconfigure_cig(cig_id, std::move(cig_params));
  }
}

void IsoManager::RemoveCig(uint8_t cig_id, bool force) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->remove_cig(cig_id, force);
  }
}

void IsoManager::EstablishCis(struct cis_establish_params conn_params) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->establish_cis(std::move(conn_params));
  }
}

void IsoManager::DisconnectCis(uint16_t cis_handle, uint8_t reason) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->disconnect_cis(cis_handle, reason);
  }
}

int IsoManager::GetNumberOfActiveIso() {
  if (pimpl_->IsRunning()) {
    return pimpl_->iso_impl_->get_number_of_active_iso();
  }
  return 0;
}

void IsoManager::SetupIsoDataPath(uint16_t conn_handle, struct iso_data_path_params path_params) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->setup_iso_data_path(conn_handle, std::move(path_params));
  }
}

void IsoManager::RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->remove_iso_data_path(conn_handle, data_path_dir);
  }
}

void IsoManager::ReadIsoLinkQuality(uint16_t conn_handle) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->read_iso_link_quality(conn_handle);
  }
}

void IsoManager::SendIsoData(uint16_t conn_handle, const uint8_t* data, uint16_t data_len) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->send_iso_data(conn_handle, data, data_len);
  }
}

void IsoManager::CreateBig(IsoClientHandle client_handle, uint8_t big_handle,
                           struct big_create_params big_params) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->create_big(client_handle, big_handle, std::move(big_params));
  }
}

void IsoManager::TerminateBig(uint8_t big_handle, uint8_t reason) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->terminate_big(big_handle, reason);
  }
}

void IsoManager::SetBigChannelMapClassificationByConnHandles(uint8_t action, uint8_t big_handle,
                                                             const std::vector<uint16_t>& handles) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->set_big_channel_map_classification(action, big_handle, handles);
  }
}

void IsoManager::BigCreateSync(IsoClientHandle client_handle,
                               struct big_create_sync_params sync_params) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->big_create_sync(client_handle, std::move(sync_params));
  }
}

void IsoManager::BigTerminateSync(uint8_t big_handle) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->big_terminate_sync(big_handle);
  }
}

void IsoManager::HandleIsoData(void* p_msg) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->handle_iso_data(static_cast<BT_HDR*>(p_msg));
  }
}

void IsoManager::HandleDisconnect(uint16_t handle, uint8_t reason) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->disconnection_complete(handle, reason);
  }
}

void IsoManager::HandleNumComplDataPkts(uint16_t handle, uint16_t credits) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->handle_gd_num_completed_pkts(handle, credits);
  }
}

void IsoManager::HandleHciEvent(uint8_t sub_code, uint8_t* params, uint16_t length) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->on_iso_event(sub_code, params, length);
  }
}

void IsoManager::Start() {
  if (!pimpl_->IsRunning()) {
    pimpl_->Start();
  }
}

void IsoManager::Stop() {
  if (pimpl_->IsRunning()) {
    pimpl_->Stop();
  }
}

void IsoManager::Dump(int fd) {
  if (pimpl_->IsRunning()) {
    pimpl_->Dump(fd);
  }
}

bool IsoManager::AddIncomingCisEventsListener(IsoClientHandle client_handle,
                                              const RawAddress& pseudo_address, uint8_t cig_id,
                                              uint8_t cis_id) {
  if (pimpl_->IsRunning()) {
    return pimpl_->iso_impl_->add_incoming_cis_events_listener(client_handle, pseudo_address,
                                                               cig_id, cis_id);
  }
  return false;
}

void IsoManager::RemoveIncomingCisEventsListener(IsoClientHandle client_handle,
                                                 const RawAddress& pseudo_address, uint8_t cig_id,
                                                 uint8_t cis_id) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->remove_incoming_cis_events_listener(client_handle, pseudo_address, cig_id,
                                                           cis_id);
  }
}

void IsoManager::AcceptIncomingCisConnection(uint16_t conn_handle) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->accept_incoming_cis_connection(conn_handle);
  }
}

void IsoManager::RejectIncomingCisConnection(uint16_t conn_handle, uint8_t reason) {
  if (pimpl_->IsRunning()) {
    pimpl_->iso_impl_->reject_incoming_cis_connection(conn_handle, reason);
  }
}

IsoManager::~IsoManager() = default;

IsoManager* IsoManager::GetInstance() {
  static IsoManager* instance = new IsoManager();
  return instance;
}

}  // namespace hci
}  // namespace bluetooth
