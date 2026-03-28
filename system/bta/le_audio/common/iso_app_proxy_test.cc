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

#include "bta/le_audio/common/iso_app_proxy.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <vector>

#include "stack/btm/btm_dev.h"
#include "stack/include/btm_iso_api.h"
#include "stack/mock/mock_stack_btm_iso.h"

std::map<uint16_t, BtmDevice> AclHandleToMockBtmDevice = {};
const BtmDevice* btm_find_dev_by_handle(uint16_t handle) {
  return AclHandleToMockBtmDevice.count(handle) ? &AclHandleToMockBtmDevice.at(handle) : nullptr;
}

namespace bluetooth {

using ::testing::_;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SaveArg;
namespace {

class MockCigCallbacks : public bluetooth::hci::iso_manager::CigCallbacks {
public:
  MockCigCallbacks() = default;
  MockCigCallbacks(const MockCigCallbacks&) = delete;
  MockCigCallbacks& operator=(const MockCigCallbacks&) = delete;

  ~MockCigCallbacks() override = default;

  // clang-format off
  MOCK_METHOD((void), OnSetupIsoDataPath, (uint8_t status, uint16_t conn_handle, uint8_t cig_id),
              (override));
  MOCK_METHOD((void), OnRemoveIsoDataPath, (uint8_t status, uint16_t conn_handle, uint8_t cig_id),
              (override));
  MOCK_METHOD((void), OnIsoLinkQualityRead,
              (uint16_t conn_handle, uint8_t cig_id, uint32_t tx_unacked_packets,
               uint32_t tx_flushed_packets, uint32_t tx_last_subevent_packets,
               uint32_t retransmitted_packets, uint32_t crc_error_packets,
               uint32_t rx_unreceived_packets, uint32_t duplicate_packets),
              (override));

  MOCK_METHOD((void), OnCisEvent, (uint8_t event, void* data), (override));
  MOCK_METHOD((void), OnCigEvent, (uint8_t event, void* data), (override));
  // clang-format on
};

constexpr bluetooth::hci::iso_manager::IsoClientHandle kIsoClientHandle = 1;

class IsoAppProxyTest : public testing::Test {
protected:
  void RegisterIsoAppProxy() {
    EXPECT_CALL(*mock_iso_manager_, RegisterCallbacks(_))
            .WillOnce([this](bluetooth::hci::iso_manager::IsoManagerCallbacks callbacks) {
              iso_manager_callbacks_ = callbacks.cig_callbacks;
              return kIsoClientHandle;
            });
    proxy_ = std::make_unique<IsoAppProxy>(app_cig_callbacks_.get());

    ASSERT_NE(proxy_, nullptr);
    ASSERT_NE(iso_manager_callbacks_, nullptr);
  }

  void SetUp() override {
    // This is required to initialize the mock which we get by calling MockIsoManager::GetInstance()
    iso_manager_ = bluetooth::hci::IsoManager::GetInstance();
    ASSERT_NE(iso_manager_, nullptr);
    iso_manager_->Start();

    mock_iso_manager_ = MockIsoManager::GetInstance();
    app_cig_callbacks_ = std::make_unique<NiceMock<MockCigCallbacks>>();

    RegisterIsoAppProxy();
  }

  void TearDown() override {
    iso_manager_->Stop();
    mock_iso_manager_ = nullptr;
    app_cig_callbacks_.reset();
  }

  void TestSetupIsoDataPath(uint16_t conn_handle, uint8_t cig_id, uint8_t data_path_dir) {
    EXPECT_CALL(*app_cig_callbacks_.get(), OnSetupIsoDataPath(0, conn_handle, cig_id)).Times(1);

    EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(conn_handle, _))
            .WillOnce([&, this](uint16_t conn_handle,
                                hci::iso_manager::iso_data_path_params /*params*/) {
              iso_manager_callbacks_->OnSetupIsoDataPath(0, conn_handle, cig_id);
            });

    hci::iso_manager::iso_data_path_params params;
    params.data_path_dir = data_path_dir;
    proxy_->SetupIsoDataPath(conn_handle, params);
  }

  void TestRemoveIsoDataPath(uint16_t conn_handle, uint8_t cig_id, uint8_t data_path_dir) {
    EXPECT_CALL(*app_cig_callbacks_.get(), OnRemoveIsoDataPath(0, conn_handle, cig_id)).Times(1);

    EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(conn_handle, data_path_dir))
            .WillOnce([&, this](uint16_t conn_handle, uint8_t /*data_path_dir*/) {
              iso_manager_callbacks_->OnRemoveIsoDataPath(0, conn_handle, cig_id);
            });

    proxy_->RemoveIsoDataPath(conn_handle, data_path_dir);
  }

  void TestSendIsoData(uint16_t conn_handle, const uint8_t* data, uint16_t data_len) {
    EXPECT_CALL(*mock_iso_manager_, SendIsoData(conn_handle, data, data_len));
    proxy_->SendIsoData(conn_handle, data, data_len);
  }

  void TestAddIncomingCisEventsListener(const RawAddress& device, uint16_t conn_handle,
                                        uint8_t cig_id, uint8_t cis_id) {
    EXPECT_CALL(*mock_iso_manager_, AddIncomingCisEventsListener(_, device, cig_id, cis_id));
    proxy_->AddIncomingCisEventsListener(device, cig_id, cis_id);

    EXPECT_CALL(*app_cig_callbacks_.get(), OnCisEvent(hci::iso_manager::kIsoEventCisRequest, _));

    bluetooth::hci::iso_manager::cis_request_evt evt;
    evt.acl_conn_hdl = 0xD00D;  // Dummy ACL handle
    evt.cis_conn_hdl = conn_handle;
    evt.cig_id = cig_id;
    evt.cis_id = cis_id;

    // Mock the security record
    AclHandleToMockBtmDevice[evt.acl_conn_hdl] = BtmDevice{.ble.pseudo_addr = peer_address_};

    iso_manager_callbacks_->OnCisEvent(hci::iso_manager::kIsoEventCisRequest, &evt);
  }

  void TestRemoveIncomingCisEventsListener(const RawAddress& device, uint16_t conn_handle,
                                           uint8_t cig_id, uint8_t cis_id) {
    (void)conn_handle;
    EXPECT_CALL(*mock_iso_manager_, AddIncomingCisEventsListener(_, device, cig_id, cis_id));
    proxy_->AddIncomingCisEventsListener(device, cig_id, cis_id);

    EXPECT_CALL(*mock_iso_manager_, RemoveIncomingCisEventsListener(_, device, cig_id, cis_id));
    proxy_->RemoveIncomingCisEventsListener(device, cig_id, cis_id);
  }

  void TestAcceptIncomingCisConnection(uint16_t conn_handle, uint8_t status, uint8_t cig_id,
                                       uint8_t cis_id) {
    // Expect CIS established event
    EXPECT_CALL(*app_cig_callbacks_.get(), OnCisEvent(hci::iso_manager::kIsoEventCisRequest, _));
    EXPECT_CALL(*app_cig_callbacks_.get(),
                OnCisEvent(hci::iso_manager::kIsoEventCisEstablishCmpl, _));

    // Expect CIS established event after the CIS request is accepted:
    ON_CALL(*mock_iso_manager_, AcceptIncomingCisConnection(conn_handle))
            .WillByDefault([&, this](uint16_t conn_handle) {
              // Fill the details
              hci::iso_manager::cis_establish_cmpl_evt evt = {.status = status,
                                                              .cis_conn_hdl = conn_handle};
              // Inject CIS established event:
              iso_manager_callbacks_->OnCisEvent(hci::iso_manager::kIsoEventCisEstablishCmpl, &evt);
            });

    // Accept incoming CIS request
    ON_CALL(*app_cig_callbacks_.get(), OnCisEvent(hci::iso_manager::kIsoEventCisRequest, _))
            .WillByDefault([this](uint8_t /*event*/, void* data) {
              auto const* evt = static_cast<hci::iso_manager::cis_request_evt*>(data);
              proxy_->AcceptIncomingCisConnection(evt->cis_conn_hdl);
            });

    // Trigger the mocked call sequence starting with CIS request
    bluetooth::hci::iso_manager::cis_request_evt evt;
    evt.acl_conn_hdl = 0xD00D;  // Dummy ACL handle
    evt.cis_conn_hdl = conn_handle;
    evt.cig_id = cig_id;
    evt.cis_id = cis_id;

    // Mock the security record
    AclHandleToMockBtmDevice[evt.acl_conn_hdl] = BtmDevice{.ble.pseudo_addr = peer_address_};

    iso_manager_callbacks_->OnCisEvent(hci::iso_manager::kIsoEventCisRequest, &evt);
  }

  void TestRejectIncomingCisConnection(uint16_t conn_handle, uint8_t status, uint8_t cig_id,
                                       uint8_t cis_id) {
    EXPECT_CALL(*app_cig_callbacks_.get(), OnCisEvent(hci::iso_manager::kIsoEventCisRequest, _));
    EXPECT_CALL(*app_cig_callbacks_.get(),
                OnCisEvent(hci::iso_manager::kIsoEventCisRequestRejectStatus, _));

    EXPECT_CALL(*mock_iso_manager_, RejectIncomingCisConnection(conn_handle, status))
            .WillOnce([&](uint16_t conn_handle, uint8_t status) {
              hci::iso_manager::reject_cis_request_reject_status evt = {
                      .status = status, .cis_conn_hdl = conn_handle};
              iso_manager_callbacks_->OnCisEvent(hci::iso_manager::kIsoEventCisRequestRejectStatus,
                                                 &evt);
            });

    // Reject incoming CIS request
    ON_CALL(*app_cig_callbacks_.get(), OnCisEvent(hci::iso_manager::kIsoEventCisRequest, _))
            .WillByDefault([&, this](uint8_t /*event*/, void* data) {
              auto const* evt = static_cast<hci::iso_manager::cis_request_evt*>(data);
              proxy_->RejectIncomingCisConnection(evt->cis_conn_hdl, status);
            });

    // Trigger the mocked call sequence starting with CIS request
    bluetooth::hci::iso_manager::cis_request_evt evt;
    evt.acl_conn_hdl = 0xD00D;  // Dummy ACL handle
    evt.cis_conn_hdl = conn_handle;
    evt.cig_id = cig_id;
    evt.cis_id = cis_id;

    // Mock the security record
    AclHandleToMockBtmDevice[evt.acl_conn_hdl] = BtmDevice{.ble.pseudo_addr = peer_address_};

    iso_manager_callbacks_->OnCisEvent(hci::iso_manager::kIsoEventCisRequest, &evt);
  }

protected:
  const RawAddress peer_address_ = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  uint8_t test_cig_id_ = 1;
  uint8_t test_cis_id_ = 2;
  uint16_t test_cis_conn_hdl_ = 0x123;
  std::unique_ptr<IsoAppProxy> proxy_ = nullptr;
  std::unique_ptr<NiceMock<MockCigCallbacks>> app_cig_callbacks_ = nullptr;
  bluetooth::hci::iso_manager::CigCallbacks* iso_manager_callbacks_ = nullptr;
  MockIsoManager* mock_iso_manager_ = nullptr;
  bluetooth::hci::IsoManager* iso_manager_ = nullptr;
};

// Initialization and Registration
TEST_F(IsoAppProxyTest, TestRegisterCallbacks) {}

// Data Path Configuration
TEST_F(IsoAppProxyTest, TestSetupIsoDataPath) {
  TestSetupIsoDataPath(test_cis_conn_hdl_, test_cig_id_, 0x01);
}

// Data Path Removal
TEST_F(IsoAppProxyTest, TestRemoveIsoDataPath) {
  TestSetupIsoDataPath(test_cis_conn_hdl_, test_cig_id_, 0x01 /*direction*/);
  TestRemoveIsoDataPath(test_cis_conn_hdl_, test_cig_id_, 0x01);
}

// ISO Data Sending
TEST_F(IsoAppProxyTest, TestSendIsoData) {
  uint8_t test_data[] = {0x01, 0x02, 0x03};
  uint16_t data_len = sizeof(test_data);

  // Need to have CIS connected and data path setup, to send the data
  TestAcceptIncomingCisConnection(test_cis_conn_hdl_, 0, test_cig_id_, test_cis_id_);
  TestSetupIsoDataPath(test_cis_conn_hdl_, test_cig_id_, 0x01 /*direction*/);

  // Inject the iso data path setup status
  iso_manager_callbacks_->OnSetupIsoDataPath(0, test_cis_conn_hdl_, 0x01 /*CIG ID*/);
  TestSendIsoData(test_cis_conn_hdl_, test_data, data_len);
}

// Incoming CIS Events Listener Registration
TEST_F(IsoAppProxyTest, TestAddIncomingCisEventsListener) {
  TestAddIncomingCisEventsListener(peer_address_, test_cis_conn_hdl_, test_cig_id_, test_cis_id_);
}

// Incoming CIS Events Listener Unregistration
TEST_F(IsoAppProxyTest, TestRemoveIncomingCisEventsListener) {
  TestRemoveIncomingCisEventsListener(peer_address_, test_cis_conn_hdl_, test_cig_id_,
                                      test_cis_id_);
}

// Accepting Incoming CIS Connection
TEST_F(IsoAppProxyTest, TestAcceptIncomingCisConnection) {
  EXPECT_CALL(*mock_iso_manager_,
              AddIncomingCisEventsListener(_, peer_address_, test_cig_id_, test_cis_id_));
  proxy_->AddIncomingCisEventsListener(peer_address_, test_cig_id_,
                                       test_cis_id_);  // This is not needed for the test

  TestAcceptIncomingCisConnection(test_cis_conn_hdl_, 0, test_cig_id_, test_cis_id_);

  // If CIS was established, check the connection status
  EXPECT_EQ(proxy_->HasCisConnected(test_cis_conn_hdl_), true);
}

// Rejecting Incoming CIS Connection
TEST_F(IsoAppProxyTest, TestRejectIncomingCisConnection) {
  EXPECT_CALL(*mock_iso_manager_,
              AddIncomingCisEventsListener(_, peer_address_, test_cig_id_, test_cis_id_));
  proxy_->AddIncomingCisEventsListener(peer_address_, test_cig_id_,
                                       test_cis_id_);  // This is not needed for the test

  TestRejectIncomingCisConnection(test_cis_conn_hdl_, 0x01 /*reason*/, test_cig_id_, test_cis_id_);

  // If CIS was established, check the connection status
  EXPECT_EQ(proxy_->HasCisConnected(test_cis_conn_hdl_), false);
}

// Checking CIS Connection Status - False
TEST_F(IsoAppProxyTest, TestHasCisConnected_False) {
  EXPECT_EQ(proxy_->HasCisConnected(test_cis_conn_hdl_), false);
}

TEST_F(IsoAppProxyTest, TestSetupIsoDataPathTwoDirections) {
  uint8_t direction_input = 0x00;
  uint8_t direction_output = 0x01;
  std::vector<hci::iso_manager::iso_data_path_params> received_params;

  // Expect two calls to the app callback
  EXPECT_CALL(*app_cig_callbacks_.get(), OnSetupIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_))
          .Times(2);

  // Expect two calls to the iso manager, and capture the arguments
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(test_cis_conn_hdl_, _))
          .Times(2)
          .WillRepeatedly(
                  [&](uint16_t /*conn_handle*/, hci::iso_manager::iso_data_path_params params) {
                    received_params.push_back(params);
                  });

  // 1. Queue first request
  hci::iso_manager::iso_data_path_params params_input;
  params_input.data_path_dir = direction_input;
  proxy_->SetupIsoDataPath(test_cis_conn_hdl_, params_input);

  // 2. Queue second request
  hci::iso_manager::iso_data_path_params params_output;
  params_output.data_path_dir = direction_output;
  proxy_->SetupIsoDataPath(test_cis_conn_hdl_, params_output);

  // Verify that IsoManager was called with correct parameters
  ASSERT_EQ(received_params.size(), 2lu);
  EXPECT_EQ(received_params[0].data_path_dir, direction_input);
  EXPECT_EQ(received_params[1].data_path_dir, direction_output);

  // 3. Trigger completion events
  iso_manager_callbacks_->OnSetupIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_);
  iso_manager_callbacks_->OnSetupIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_);

  // 4. Verify data path is setup by trying to remove them.
  //    This should trigger calls to the mock_iso_manager.
  testing::Mock::VerifyAndClearExpectations(mock_iso_manager_);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  proxy_->RemoveIsoDataPath(test_cis_conn_hdl_, direction_input);
  proxy_->RemoveIsoDataPath(test_cis_conn_hdl_, direction_output);
}

TEST_F(IsoAppProxyTest, TestRemoveIsoDataPathTwoDirections) {
  uint8_t direction_input = 0x00;
  uint8_t direction_output = 0x01;

  // 1. Setup two data paths
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(test_cis_conn_hdl_, _)).Times(2);
  hci::iso_manager::iso_data_path_params params_input;
  params_input.data_path_dir = direction_input;
  proxy_->SetupIsoDataPath(test_cis_conn_hdl_, params_input);
  hci::iso_manager::iso_data_path_params params_output;
  params_output.data_path_dir = direction_output;
  proxy_->SetupIsoDataPath(test_cis_conn_hdl_, params_output);

  // Fire completion events for setup to fill the setup map
  iso_manager_callbacks_->OnSetupIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_);
  iso_manager_callbacks_->OnSetupIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_);
  testing::Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Now, test removal
  std::vector<uint8_t> removed_directions;
  EXPECT_CALL(*app_cig_callbacks_.get(), OnRemoveIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_))
          .Times(2);

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(test_cis_conn_hdl_, _))
          .Times(2)
          .WillRepeatedly([&](uint16_t /*conn_handle*/, uint8_t data_path_dir) {
            removed_directions.push_back(data_path_dir);
          });

  // 2. Queue first removal
  proxy_->RemoveIsoDataPath(test_cis_conn_hdl_, direction_input);

  // 3. Queue second removal
  proxy_->RemoveIsoDataPath(test_cis_conn_hdl_, direction_output);

  // Verify that IsoManager was called with correct parameters
  ASSERT_EQ(removed_directions.size(), 2lu);
  EXPECT_EQ(removed_directions[0], direction_input);
  EXPECT_EQ(removed_directions[1], direction_output);

  // 4. Trigger completion events for removal
  iso_manager_callbacks_->OnRemoveIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_);
  iso_manager_callbacks_->OnRemoveIsoDataPath(0, test_cis_conn_hdl_, test_cig_id_);

  // 5. Verify data path is not setup anymore by trying to remove again.
  //    This should not trigger a call to the mock_iso_manager.
  testing::Mock::VerifyAndClearExpectations(mock_iso_manager_);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  proxy_->RemoveIsoDataPath(test_cis_conn_hdl_, direction_input);
}

}  // namespace
}  // namespace bluetooth
