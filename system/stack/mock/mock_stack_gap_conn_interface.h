/*
 * Copyright 2024 The Android Open Source Project
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

#include <vector>

#include "stack/include/gap_api.h"

namespace bluetooth {
namespace testing {
namespace stack {
namespace gap_conn {

class Interface {
public:
  virtual ~Interface() = default;

  virtual uint16_t GAP_ConnOpen(const char* p_serv_name, uint8_t service_id, bool is_server,
                                const RawAddress* p_rem_bda, uint16_t psm, uint16_t le_mps,
                                tL2CAP_CFG_INFO* p_cfg, tL2CAP_ERTM_INFO* ertm_info,
                                uint16_t security, tGAP_CONN_CALLBACK* p_cb,
                                tBT_TRANSPORT transport) = 0;

  virtual const RawAddress* GAP_ConnGetRemoteAddr(uint16_t gap_handle) = 0;
};

class Mock : public Interface {
public:
  ~Mock() = default;

  MOCK_METHOD(uint16_t, GAP_ConnOpen,
              (const char* p_serv_name, uint8_t service_id, bool is_server,
               const RawAddress* p_rem_bda, uint16_t psm, uint16_t le_mps, tL2CAP_CFG_INFO* p_cfg,
               tL2CAP_ERTM_INFO* ertm_info, uint16_t security, tGAP_CONN_CALLBACK* p_cb,
               tBT_TRANSPORT transport));

  MOCK_METHOD(const RawAddress*, GAP_ConnGetRemoteAddr, (uint16_t gap_handle));
};

void reset_interface();
void set_interface(Interface* interface_);
Interface& get_interface();

}  // namespace gap_conn
}  // namespace stack
}  // namespace testing
}  // namespace bluetooth
