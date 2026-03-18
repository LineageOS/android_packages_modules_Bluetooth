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
#include "include/bluetooth/types/uuid.h"
#include "osi/include/allocator.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "stack/mock/mock_stack_app.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_security_client_interface.h"
#include "stack/mock/mock_stack_gatt_api.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/mock/mock_stack_rnr_interface.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_main_shim_entry.h"

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

private:
  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

// Setup any default or optional mocks
class BtaWithMocksTest : public BtaWithFakesTest {
protected:
  void SetUp() override {
    BtaWithFakesTest::SetUp();
    set_security_client_interface(mock_security_client_interface_);
    set_mock_btm_client_interface(&mock_btm_client_interface_);
    reset_mock_function_count_map();
    ASSERT_NE(get_btm_client_interface().lifecycle.btm_init, nullptr);
    ASSERT_NE(get_btm_client_interface().lifecycle.btm_free, nullptr);

    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<::testing::NiceMock<bluetooth::hci::testing::MockController>>();
    bluetooth::testing::stack::l2cap::set_interface(&mock_l2cap_interface_);
    bluetooth::testing::stack::rnr::set_interface(&mock_stack_rnr_interface_);

    test::mock::stack_app::appRegister.body =
            [](const bluetooth::Uuid& /*p_app_uuid128*/, const std::string /*name*/,
               const stack::tGATT_CBACK* /*p_cb_info*/,
               bool /*eatt_support*/) -> tGATT_IF { return kGattRegisteredIf; };

    ON_CALL(mock_btm_client_interface_, BTM_GetEirSupportedServices)
            .WillByDefault(::testing::Return(0));
    ON_CALL(mock_btm_client_interface_, BTM_WriteEIR).WillByDefault([](BT_HDR* p_buf) {
      osi_free(p_buf);
      return tBTM_STATUS::BTM_SUCCESS;
    });

    ON_CALL(mock_security_client_interface_, BTM_SecRegister(::testing::_))
      .WillByDefault(::testing::Return(true));
  }

  void TearDown() override {
    test::mock::stack_app::appRegister = {};

    bluetooth::testing::stack::rnr::reset_interface();
    bluetooth::testing::stack::l2cap::reset_interface();
    bluetooth::hci::testing::mock_controller_.reset();
    reset_mock_btm_client_interface();
    reset_mock_security_client_interface();

    BtaWithFakesTest::TearDown();
  }

  bluetooth::testing::stack::l2cap::Mock mock_l2cap_interface_;
  bluetooth::testing::stack::rnr::Mock mock_stack_rnr_interface_;
  MockBtmClientInterface mock_btm_client_interface_;
  MockSecurityClientInterface mock_security_client_interface_;
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
    BTA_dm_on_hw_on("test_name");
  }

  void TearDown() override {
    BTA_dm_on_hw_off();
    BtaWithContextTest::TearDown();
  }
};
