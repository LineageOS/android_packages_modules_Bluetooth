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

#include "mock_bta_hf_client_api.h"

#include "btif_status.h"

namespace {
MockBtaHfClientApi* instance;
}

void MockBtaHfClientApi::SetInstance(MockBtaHfClientApi* ptr) { instance = ptr; }

tBTA_STATUS BTA_HfClientEnable(tBTA_HF_CLIENT_CBACK* p_cback, tBTA_HF_CLIENT_FEAT features,
                               const char* p_service_name) {
  return instance ? instance->BTA_HfClientEnable(p_cback, features, p_service_name) : BTA_SUCCESS;
}

void BTA_HfClientAudioClose(uint16_t handle) {
  if (instance) {
    instance->BTA_HfClientAudioClose(handle);
  }
}

void BTA_HfClientAudioOpen(uint16_t handle) {
  if (instance) {
    instance->BTA_HfClientAudioOpen(handle);
  }
}

void BTA_HfClientClose(uint16_t handle) {
  if (instance) {
    instance->BTA_HfClientClose(handle);
  }
}

void BTA_HfClientDisable() {
  if (instance) {
    instance->BTA_HfClientDisable();
  }
}

void BTA_HfClientDumpStatistics(int fd) {
  if (instance) {
    instance->BTA_HfClientDumpStatistics(fd);
  }
}

BtStatus BTA_HfClientOpen(const RawAddress& bd_addr, uint16_t* p_handle) {
  return instance ? instance->BTA_HfClientOpen(bd_addr, p_handle) : BtifStatus();
}

void BTA_HfClientSendAT(uint16_t handle, tBTA_HF_CLIENT_AT_CMD_TYPE at, uint32_t val1,
                        uint32_t val2, const char* str) {
  if (instance) {
    instance->BTA_HfClientSendAT(handle, at, val1, val2, str);
  }
}

int get_default_hf_client_features() {
  return instance ? instance->get_default_hf_client_features() : 0;
}

int get_default_hfp_version() {
  return instance ? instance->get_default_hfp_version() : HFP_VERSION_1_9;
}
