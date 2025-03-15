/*
 * Copyright 2023 The Android Open Source Project
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

#include <bluetooth/log.h>
#include <gmock/gmock.h>

#include "bta/dm/bta_dm_int.h"
#include "bta/include/bta_api.h"
#include "bta/sys/bta_sys.h"
#include "osi/include/allocator.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "test/common/main_handler.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_stack_btm_interface.h"
#include "test/mock/mock_stack_gatt_api.h"
#include "test/mock/mock_stack_rnr_interface.h"

constexpr tGATT_IF kGattRegisteredIf = 5;

extern tBTA_DM_CB bta_dm_cb;

// Set up base mocks and fakes
class BtaWithFakesTest : public ::testing::Test {
protected:
  void SetUp() override {
    bta_dm_cb = {};
    fake_osi_ = std::make_unique<test::fake::FakeOsi>();
  }

  void TearDown() override { fake_osi_.reset(); }
  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

// Setup any default or optional mocks
class BtaWithMocksTest : public BtaWithFakesTest {
protected:
  void SetUp() override {
    BtaWithFakesTest::SetUp();
    reset_mock_function_count_map();
    reset_mock_btm_client_interface();
    ASSERT_NE(get_btm_client_interface().lifecycle.btm_init, nullptr);
    ASSERT_NE(get_btm_client_interface().lifecycle.btm_free, nullptr);

    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockControllerInterface>();
    bluetooth::testing::stack::rnr::set_interface(&mock_stack_rnr_interface_);

    test::mock::stack_gatt_api::GATT_Register.body =
            [](const bluetooth::Uuid& /*p_app_uuid128*/, const std::string /*name*/,
               tGATT_CBACK* /*p_cb_info*/,
               bool /*eatt_support*/) -> tGATT_IF { return kGattRegisteredIf; };
    mock_btm_client_interface.eir.BTM_GetEirSupportedServices =
            [](uint32_t* /*p_eir_uuid*/, uint8_t** /*p*/, uint8_t /*max_num_uuid16*/,
               uint8_t* /*p_num_uuid16*/) -> uint8_t { return 0; };
    mock_btm_client_interface.eir.BTM_WriteEIR = [](BT_HDR* p_buf) -> tBTM_STATUS {
      osi_free(p_buf);
      return tBTM_STATUS::BTM_SUCCESS;
    };
    mock_btm_client_interface.security.BTM_SecRegister =
            [](const tBTM_APPL_INFO* /*p_cb_info*/) -> bool { return true; };
  }

  void TearDown() override {
    test::mock::stack_gatt_api::GATT_Register = {};

    mock_btm_client_interface.eir.BTM_GetEirSupportedServices = {};
    mock_btm_client_interface.eir.BTM_WriteEIR = {};

    bluetooth::testing::stack::rnr::reset_interface();
    bluetooth::hci::testing::mock_controller_.reset();

    BtaWithFakesTest::TearDown();
  }

  bluetooth::testing::stack::rnr::Mock mock_stack_rnr_interface_;
};

class BtaWithContextTest : public BtaWithMocksTest {
protected:
  void SetUp() override {
    BtaWithMocksTest::SetUp();
    main_thread_start_up();
    post_on_bt_main([]() { bluetooth::log::info("Main thread started up"); });
  }
  void TearDown() override {
    post_on_bt_main([]() { bluetooth::log::info("Main thread shutting down"); });
    main_thread_shut_down();
    BtaWithMocksTest::TearDown();
  }
};

class BtaWithHwOnTest : public BtaWithContextTest {
protected:
  void SetUp() override {
    BtaWithContextTest::SetUp();
    BTA_dm_on_hw_on();
  }

  void TearDown() override {
    BTA_dm_on_hw_off();
    BtaWithContextTest::TearDown();
  }
};
