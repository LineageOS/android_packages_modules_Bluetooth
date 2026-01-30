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
#pragma once

#include "iso_app_proxy.h"

namespace bluetooth {

class MockIsoAppProxy : public IsoAppProxy {
public:
  static MockIsoAppProxy* GetInstance();

  MockIsoAppProxy(bluetooth::hci::iso_manager::CigCallbacks* cig_callbacks,
                  bluetooth::hci::iso_manager::BigCallbacks* big_callbacks,
                  std::function<void(bool)> iso_traffic_active_callback)
      : bluetooth::IsoAppProxy(cig_callbacks, big_callbacks, iso_traffic_active_callback) {
    // Allows the test suite to capture the callbacks in ON_CALL/EXPECT_CALL
    SetCallbacks(cig_callbacks, big_callbacks, iso_traffic_active_callback);
  }

  virtual ~MockIsoAppProxy() = default;

  // clang-format off
  MOCK_METHOD((void), SetCallbacks,
              (bluetooth::hci::iso_manager::CigCallbacks * cig_callbacks,
               bluetooth::hci::iso_manager::BigCallbacks* big_callbacks,
               std::function<void(bool)> iso_traffic_active_callback),
              ());
  MOCK_METHOD((void), AddIncomingCisEventsListener,
              (const RawAddress& device, uint8_t cig_id, uint8_t cis_id), (override));
  MOCK_METHOD((void), RemoveIncomingCisEventsListener,
              (const RawAddress& device, uint8_t cig_id, uint8_t cis_id), (override));
  MOCK_METHOD((void), SetupIsoDataPath,
              (uint16_t cis_conn_handle,
               struct bluetooth::hci::iso_manager::iso_data_path_params path_params),
              (override));
  MOCK_METHOD((void), RemoveIsoDataPath, (uint16_t cis_conn_handle, uint8_t data_path_dir),
              (override));
  MOCK_METHOD((void), SendIsoData,
              (uint16_t cis_conn_handle, const uint8_t* data, uint16_t data_len),
              (override));
  MOCK_METHOD((void), AcceptIncomingCisConnection, (uint16_t cis_conn_handle), (override));
  MOCK_METHOD((void), RejectIncomingCisConnection, (uint16_t cis_conn_handle, uint8_t reason),
              (override));
  MOCK_METHOD((bool), HasCisConnected, (uint16_t cis_conn_handle), (const override));
  // clang-format on
};
}  // namespace bluetooth
