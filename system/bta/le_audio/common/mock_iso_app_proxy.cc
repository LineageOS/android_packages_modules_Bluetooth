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

#include "mock_iso_app_proxy.h"

namespace bluetooth {

struct IsoAppProxy::impl : public MockIsoAppProxy {
public:
  impl() {}
  ~impl() {}
};

IsoAppProxy::IsoAppProxy() {
  if (!pimpl_) {
    pimpl_ = std::make_unique<testing::NiceMock<impl>>();
  }
}

void IsoAppProxy::SetCallbacks(iso_manager::CigCallbacks* cig_callbacks,
                               iso_manager::BigCallbacks* big_callbacks,
                               std::function<void(bool)> iso_traffic_active_callback) {
  if (!pimpl_) {
    return;
  }
  pimpl_->SetCallbacks(cig_callbacks, big_callbacks, iso_traffic_active_callback);
  mock_app_helper_pimpl_ = pimpl_.get();
}

IsoAppProxy::~IsoAppProxy() = default;

void IsoAppProxy::SetupIsoDataPath(uint16_t cis_conn_handle,
                                   struct iso_manager::iso_data_path_params path_params) {
  if (pimpl_) {
    pimpl_->SetupIsoDataPath(cis_conn_handle, path_params);
  }
}

void IsoAppProxy::RemoveIsoDataPath(uint16_t cis_conn_handle, uint8_t data_path_dir) {
  if (pimpl_) {
    pimpl_->RemoveIsoDataPath(cis_conn_handle, data_path_dir);
  }
}

void IsoAppProxy::SendIsoData(uint16_t cis_conn_handle, const uint8_t* data, uint16_t data_len) {
  if (pimpl_) {
    pimpl_->SendIsoData(cis_conn_handle, data, data_len);
  }
}

void IsoAppProxy::AddIncomingCisEventsListener(const RawAddress& pseudo_address, uint8_t cig_id,
                                               uint8_t cis_id) {
  if (pimpl_) {
    pimpl_->AddIncomingCisEventsListener(pseudo_address, cig_id, cis_id);
  }
}

void IsoAppProxy::RemoveIncomingCisEventsListener(const RawAddress& pseudo_address, uint8_t cig_id,
                                                  uint8_t cis_id) {
  if (pimpl_) {
    pimpl_->RemoveIncomingCisEventsListener(pseudo_address, cig_id, cis_id);
  }
}

void IsoAppProxy::AcceptIncomingCisConnection(uint16_t cis_conn_handle) {
  if (pimpl_) {
    pimpl_->AcceptIncomingCisConnection(cis_conn_handle);
  }
}

void IsoAppProxy::RejectIncomingCisConnection(uint16_t cis_conn_handle, uint8_t reason) {
  if (pimpl_) {
    pimpl_->RejectIncomingCisConnection(cis_conn_handle, reason);
  }
}

bool IsoAppProxy::HasCisConnected(uint16_t cis_conn_handle) const {
  if (pimpl_) {
    return pimpl_->HasCisConnected(cis_conn_handle);
  }
  return false;
}

}  // namespace bluetooth
