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

#include <bluetooth/types/address.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <cstdint>

#include "stack/include/bt_name.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/rnr_interface.h"

namespace bluetooth {
namespace testing {
namespace stack {
namespace rnr {

class Mock : public ::bluetooth::stack::rnr::Interface {
public:
  Mock() = default;

  MOCK_METHOD(void, BTM_SecAddRmtNameNotifyCallback, (BtmRemoteNameCallback&));
  MOCK_METHOD(bool, BTM_IsRemoteNameKnown, (const RawAddress& bd_addr, tBT_TRANSPORT transport));
  MOCK_METHOD(tBTM_STATUS, BTM_ReadRemoteDeviceName,
              (const RawAddress& bd_addr, tBTM_NAME_CMPL_CB* p_callback, tBT_TRANSPORT transport));
  MOCK_METHOD(tBTM_STATUS, BTM_CancelRemoteDeviceName, ());
  MOCK_METHOD(void, btm_process_remote_name,
              (const RawAddress* bd_addr, const BD_NAME bd_name, uint16_t evt_len,
               tHCI_STATUS hci_status));
};

void reset_interface();
void set_interface(bluetooth::stack::rnr::Interface* interface_);

}  // namespace rnr
}  // namespace stack
}  // namespace testing
}  // namespace bluetooth
