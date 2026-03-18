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

#include <gmock/gmock.h>

#include "bta/include/bta_hf_client_api.h"
#include "bta/include/bta_hfp_api.h"

class MockBtaHfClientApi {
public:
  // clang-format off
  MOCK_METHOD(tBTA_STATUS, BTA_HfClientEnable,
              (tBTA_HF_CLIENT_CBACK* p_cback, tBTA_HF_CLIENT_FEAT features,
               const char* p_service_name));
  MOCK_METHOD(void, BTA_HfClientAudioClose, (uint16_t handle));
  MOCK_METHOD(void, BTA_HfClientAudioOpen, (uint16_t handle));
  MOCK_METHOD(void, BTA_HfClientClose, (uint16_t handle));
  MOCK_METHOD(void, BTA_HfClientDisable, ());
  MOCK_METHOD(void, BTA_HfClientDumpStatistics, (int fd));
  MOCK_METHOD(BtStatus, BTA_HfClientOpen, (const RawAddress& bd_addr, uint16_t* p_handle));
  MOCK_METHOD(void, BTA_HfClientSendAT,
              (uint16_t handle, tBTA_HF_CLIENT_AT_CMD_TYPE at, uint32_t val1, uint32_t val2,
               const char* str));
  MOCK_METHOD(int, get_default_hf_client_features, ());
  MOCK_METHOD(int, get_default_hfp_version, ());
  // clang-format on

  static void SetInstance(MockBtaHfClientApi* ptr);
};

