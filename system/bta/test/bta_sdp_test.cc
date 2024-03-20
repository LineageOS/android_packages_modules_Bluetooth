/*
 * Copyright 2022 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <string>

#include "bta/dm/bta_dm_disc_int.h"
#include "bta/test/bta_test_fixtures.h"
#include "osi/include/allocator.h"
#include "test/common/main_handler.h"
#include "test/mock/mock_stack_btm_interface.h"
#include "test/mock/mock_stack_gatt_api.h"

void BTA_dm_on_hw_on();
void BTA_dm_on_hw_off();

namespace {
const char kName[] = "Hello";
}

namespace bluetooth {
namespace legacy {
namespace testing {

const tBTA_DM_SEARCH_CB& bta_dm_disc_search_cb();
tBTA_DM_SEARCH_CB bta_dm_disc_get_search_cb();
void bta_dm_disc_search_cb(const tBTA_DM_SEARCH_CB& search_cb);
void bta_dm_sdp_result(tBTA_DM_MSG* p_data);

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

class BtaSdpTest : public BtaWithHwOnTest {
 protected:
  void SetUp() override { BtaWithHwOnTest::SetUp(); }

  void TearDown() override { BtaWithHwOnTest::TearDown(); }
};

class BtaSdpRegisteredTest : public BtaSdpTest {
 protected:
  void SetUp() override {
    BtaSdpTest::SetUp();
    bta_sys_register(BTA_ID_DM_SEARCH, &bta_sys_reg);
  }

  void TearDown() override {
    bta_sys_deregister(BTA_ID_DM_SEARCH);
    BtaSdpTest::TearDown();
  }

  tBTA_SYS_REG bta_sys_reg = {
      .evt_hdlr = [](const BT_HDR_RIGID* p_msg) -> bool {
        osi_free((void*)p_msg);
        return false;
      },
      .disable = []() {},
  };
};

TEST_F(BtaSdpTest, nop) {}

TEST_F(BtaSdpRegisteredTest, bta_dm_sdp_result_SDP_SUCCESS) {
  tBTA_DM_SEARCH_CB search_cb =
      bluetooth::legacy::testing::bta_dm_disc_get_search_cb();
  search_cb.service_index = BTA_MAX_SERVICE_ID;
  bluetooth::legacy::testing::bta_dm_disc_search_cb(search_cb);

  tBTA_DM_MSG msg = {
      .sdp_event =
          {
              .hdr = {},
              .sdp_result = SDP_SUCCESS,
          },
  };
  mock_btm_client_interface.security.BTM_SecReadDevName =
      [](const RawAddress& bd_addr) -> const char* { return kName; };
  mock_btm_client_interface.security.BTM_SecDeleteRmtNameNotifyCallback =
      [](tBTM_RMT_NAME_CALLBACK*) -> bool { return true; };
  bluetooth::legacy::testing::bta_dm_sdp_result(&msg);
}
