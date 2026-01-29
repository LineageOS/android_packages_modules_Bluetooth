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

#include "mock_stack_btm_iso.h"

#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

#include "stack/include/btm_iso_api.h"
#include "stack/include/btm_iso_api_types.h"

namespace {
MockIsoManager* mock_pimpl_;
}

MockIsoManager* MockIsoManager::GetInstance() {
  bluetooth::hci::IsoManager::GetInstance();
  return mock_pimpl_;
}

namespace bluetooth {
namespace hci {

struct IsoManager::impl : public MockIsoManager {
public:
  impl() = default;
  ~impl() = default;
};

IsoManager::IsoManager() {}

iso_manager::IsoClientHandle IsoManager::RegisterCallbacks(
        iso_manager::IsoManagerCallbacks callbacks) const {
  if (!pimpl_) {
    return iso_manager::kInvalidIsoClientHandle;
  }
  return pimpl_->RegisterCallbacks(callbacks);
}

void IsoManager::DeregisterCallbacks(iso_manager::IsoClientHandle client_handle) const {
  if (!pimpl_) {
    return;
  }
  pimpl_->DeregisterCallbacks(client_handle);
}

void IsoManager::CreateCig(iso_manager::IsoClientHandle client_handle, uint8_t cig_id,
                           struct iso_manager::cig_create_params cig_params) {
  if (!pimpl_) {
    return;
  }
  pimpl_->CreateCig(client_handle, cig_id, std::move(cig_params));
}

void IsoManager::ReconfigureCig(uint8_t cig_id, struct iso_manager::cig_create_params cig_params) {
  if (!pimpl_) {
    return;
  }
  pimpl_->ReconfigureCig(cig_id, std::move(cig_params));
}

void IsoManager::RemoveCig(uint8_t cig_id, bool force) { pimpl_->RemoveCig(cig_id, force); }

void IsoManager::EstablishCis(struct iso_manager::cis_establish_params conn_params) {
  if (!pimpl_) {
    return;
  }
  pimpl_->EstablishCis(std::move(conn_params));
}

void IsoManager::DisconnectCis(uint16_t cis_handle, uint8_t reason) {
  if (!pimpl_) {
    return;
  }
  pimpl_->DisconnectCis(cis_handle, reason);
}

void IsoManager::SetupIsoDataPath(uint16_t conn_handle,
                                  struct iso_manager::iso_data_path_params path_params) {
  if (!pimpl_) {
    return;
  }
  pimpl_->SetupIsoDataPath(conn_handle, std::move(path_params));
}

void IsoManager::RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir) {
  if (!pimpl_) {
    return;
  }
  pimpl_->RemoveIsoDataPath(conn_handle, data_path_dir);
}

void IsoManager::ReadIsoLinkQuality(uint16_t conn_handle) {
  if (!pimpl_) {
    return;
  }
  pimpl_->ReadIsoLinkQuality(conn_handle);
}

void IsoManager::SendIsoData(uint16_t conn_handle, const uint8_t* data, uint16_t data_len) {
  if (!pimpl_) {
    return;
  }
  pimpl_->SendIsoData(conn_handle, data, data_len);
}

void IsoManager::CreateBig(iso_manager::IsoClientHandle client_handle, uint8_t big_handle,
                           struct iso_manager::big_create_params big_params) {
  if (!pimpl_) {
    return;
  }
  pimpl_->CreateBig(client_handle, big_handle, std::move(big_params));
}

void IsoManager::TerminateBig(uint8_t big_handle, uint8_t reason) {
  if (!pimpl_) {
    return;
  }
  pimpl_->TerminateBig(big_handle, reason);
}

void IsoManager::BigCreateSync(iso_manager::IsoClientHandle client_handle,
                               struct iso_manager::big_create_sync_params sync_params) {
  if (!pimpl_) {
    return;
  }
  pimpl_->BigCreateSync(client_handle, std::move(sync_params));
}

void IsoManager::BigTerminateSync(uint8_t big_handle) {
  if (!pimpl_) {
    return;
  }
  pimpl_->BigTerminateSync(big_handle);
}

void IsoManager::HandleIsoData(void* p_msg) {
  if (!pimpl_) {
    return;
  }
  pimpl_->HandleIsoData(static_cast<BT_HDR*>(p_msg));
}

void IsoManager::HandleDisconnect(uint16_t handle, uint8_t reason) {
  if (!pimpl_) {
    return;
  }
  pimpl_->HandleDisconnect(handle, reason);
}

void IsoManager::HandleNumComplDataPkts(uint16_t handle, uint16_t credits) {
  if (!pimpl_) {
    return;
  }
  pimpl_->HandleNumComplDataPkts(handle, credits);
}

void IsoManager::HandleHciEvent(uint8_t sub_code, uint8_t* params, uint16_t length) {
  if (!pimpl_) {
    return;
  }
  pimpl_->HandleHciEvent(sub_code, params, length);
}

void IsoManager::SetBigChannelMapClassificationByConnHandles(uint8_t action, uint8_t big_handle,
                                                             const std::vector<uint16_t>& handles) {
  if (!pimpl_) {
    return;
  }
  pimpl_->SetBigChannelMapClassificationByConnHandles(action, big_handle, handles);
}

void IsoManager::Start() {
  // It is needed here as IsoManager which is a singleton creates it, but in
  // this mock we want to destroy and recreate the mock on each test case.
  if (!pimpl_) {
    pimpl_ = std::make_unique<testing::NiceMock<impl>>();
  }

  mock_pimpl_ = pimpl_.get();
  pimpl_->Start();
}

void IsoManager::Stop() {
  // It is needed here as IsoManager which is a singleton creates it, but in
  // this mock we want to destroy and recreate the mock on each test case.
  if (pimpl_) {
    pimpl_->Stop();
    pimpl_.reset();
  }

  mock_pimpl_ = nullptr;
}

bool IsoManager::AddIncomingCisEventsListener(iso_manager::IsoClientHandle client_handle,
                                              const RawAddress& pseudo_address, uint8_t cig_id,
                                              uint8_t cis_id) {
  if (pimpl_) {
    return pimpl_->AddIncomingCisEventsListener(client_handle, pseudo_address, cig_id, cis_id);
  }
  return false;
}

void IsoManager::RemoveIncomingCisEventsListener(iso_manager::IsoClientHandle client_handle,
                                                 const RawAddress& pseudo_address, uint8_t cig_id,
                                                 uint8_t cis_id) {
  if (pimpl_) {
    pimpl_->RemoveIncomingCisEventsListener(client_handle, pseudo_address, cig_id, cis_id);
  }
}

void IsoManager::AcceptIncomingCisConnection(uint16_t conn_handle) {
  if (pimpl_) {
    pimpl_->AcceptIncomingCisConnection(conn_handle);
  }
}

void IsoManager::RejectIncomingCisConnection(uint16_t conn_handle, uint8_t reason) {
  if (pimpl_) {
    pimpl_->RejectIncomingCisConnection(conn_handle, reason);
  }
}

int IsoManager::GetNumberOfActiveIso() { return pimpl_->GetNumberOfActiveIso(); }

void IsoManager::Dump(int /* fd */) {}

IsoManager::~IsoManager() = default;

IsoManager* IsoManager::GetInstance() {
  static IsoManager* instance = new IsoManager();
  return instance;
}

}  // namespace hci
}  // namespace bluetooth
