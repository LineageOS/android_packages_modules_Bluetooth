/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "bta/le_audio/ascs/ascs.h"

#include <android/log.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "ascs/ascs_packets.h"
#include "ascs_types.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "btm_api_mock.h"
#include "stack/include/btm_client_interface.h"

using ::testing::_;
using ::testing::DoAll;
using ::testing::InSequence;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SaveArg;

namespace bluetooth::le_audio::test {

static RawAddress GetTestAddress(int index) {
  EXPECT_LT(index, UINT8_MAX);
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, static_cast<uint8_t>(index)};
  return RawAddress(bytes);
}

class MockAscsCallbacks : public Ascs::Callbacks {
public:
  // clang-format off
  MOCK_METHOD((void), OnAscsRegistered, (std::set<uint8_t> sink_ases,
                                         std::set<uint8_t> source_ases), (override));
  MOCK_METHOD((void), OnDeviceConnected, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnDeviceDisconnected, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnAseControlPointRequest, (const RawAddress& pseudo_addr,
                                                 const Ascs::AseCtpRequest& request), (override));
  MOCK_METHOD((Ascs::AseState), OnGetAseState, (const RawAddress& pseudo_addr, uint8_t ase_id), (override));
  // clang-format on
};

class AscsTestsBase : public ::testing::Test {
public:
  std::shared_ptr<Ascs> ascs_;

  NiceMock<gatt::MockBtaGattServerInterface> gatt_server_interface_;
  MockAscsCallbacks asc_callbacks_;

  virtual void SetUp(void) override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com_android_bluetooth_flags_reset_flags();

    // Use peripheral role by default
    get_btm_client_interface().link_policy.BTM_GetRole = [](const RawAddress& /* remote_bd_addr */,
                                                            tBT_TRANSPORT /* transport */,
                                                            tHCI_ROLE* p_role) -> tBTM_STATUS {
      *p_role = HCI_ROLE_PERIPHERAL;
      return tBTM_STATUS::BTM_SUCCESS;
    };

    gatt::SetMockBtaGattServerInterface(&gatt_server_interface_);
    ON_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _))
            .WillByDefault(Return(GATT_SUCCESS));
    ascs_ = InstantiateAscs();
  }

  virtual void TearDown(void) override { ReleaseAscs(std::move(ascs_)); }
};

TEST_F(AscsTestsBase, InstantiateRelease) {
  auto const old_ptr = ascs_.get();
  ASSERT_NE(old_ptr, nullptr);

  // Make sure destructing not-registered client has no side effect
  EXPECT_CALL(gatt_server_interface_, AppDeregister(_)).Times(0);
  ReleaseAscs(std::move(ascs_));
  ASSERT_EQ(ascs_.get(), nullptr);

  // Reinstantiate
  ascs_ = InstantiateAscs();
  ASSERT_NE(ascs_.get(), nullptr);
}

TEST_F(AscsTestsBase, RegisterCallbacks) {
  const stack::tGATT_CBACK* p_gatt_event_source_cb = nullptr;
  bluetooth::Uuid uuid;

  // Check GATT server app registration
  Ascs::ServiceDescriptor service_descriptor;
  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kAudioStreamControlServiceUuid, _, _))
          .WillOnce(DoAll(SaveArg<0>(&uuid), SaveArg<1>(&p_gatt_event_source_cb), Return(0xDE)));
  EXPECT_CALL(gatt_server_interface_, AddService(_, _)).WillOnce(Return(GATT_SERVICE_STARTED));
  ascs_->RegisterGattService(service_descriptor, &asc_callbacks_);
  ASSERT_NE(nullptr, p_gatt_event_source_cb);
  ASSERT_EQ(uuid::kAudioStreamControlServiceUuid, uuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Ignore second call to register
  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kAudioStreamControlServiceUuid, _, _))
          .Times(0);
  ascs_->RegisterGattService(service_descriptor, &asc_callbacks_);

  // Make sure destructing unregisters the server interface
  EXPECT_CALL(gatt_server_interface_, AppDeregister(0xDE));
}

class AscsTests : public AscsTestsBase {
public:
  const types::LeAudioCodecId kStackCodecVendor1 = {
          .coding_format = types::kLeAudioCodingFormatVendorSpecific,
          .vendor_company_id = 0xC0DE,
          .vendor_codec_id = 0xF00D,
  };
  const ascs::AseStateCodecConfiguration kStackCodecVendor1Configuration = {
          .framing = 1,
          .preferred_phy = 2,
          .preferred_retrans_nb = 3,
          .max_transport_latency = 4,
          .pres_delay_min = 5,
          .pres_delay_max = 6,
          .preferred_pres_delay_min = 7,
          .preferred_pres_delay_max = 8,
          .codec_id = kStackCodecVendor1,
          .codec_spec_conf = {0xF0, 0x0D},
  };
  const ascs::AseStateQosConfiguration kStackCodecVendor1QosConfiguration = {
          .cig_id = (uint8_t)1,
          .cis_id = (uint8_t)2,
          .sdu_interval = (uint32_t)3,
          .framing = (uint8_t)4,
          .phy = (uint8_t)5,
          .max_sdu = (uint16_t)6,
          .retrans_nb = (uint8_t)7,
          .max_transport_latency = (uint16_t)8,
          .pres_delay = (uint32_t)9,
  };
  const types::LeAudioLtvMap kStackCodecVendor1MetadataLtv = types::LeAudioLtvMap({
          {types::kLeAudioMetadataTypeVendorSpecific,
           std::vector<uint8_t>{
                   0x01,  // Length: 1
                   0x01,  // Type: Headtracking codec supported transport
                   types::kLeAudioMetadataHeadtrackerTransportLeAcl |
                           types::kLeAudioMetadataHeadtrackerTransportLeIso,
           }},
  });

  const stack::tGATT_CBACK* p_gatt_event_source_cb_ = nullptr;
  std::vector<btgatt_db_element_t> service_db_;
  tGATT_IF server_if_;

  uint32_t gatt_trans_id_ = 0x00000001;
  Ascs::ServiceDescriptor svc_desc_;

  std::map<RawAddress, tCONN_ID> conn_id_by_address_;
  std::set<uint8_t> sink_ase_ids_;
  std::set<uint8_t> source_ase_ids_;

  static Ascs::ServiceDescriptor ProvideAscsDescriptor() {
    Ascs::ServiceDescriptor ascs_descriptor{
            .num_sink_ases = 5,
            .num_source_ases = 3,
    };
    return ascs_descriptor;
  }

  virtual void SetUp(void) override {
    AscsTestsBase::SetUp();

    // Mock GATT application registration success
    EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kAudioStreamControlServiceUuid, _, _))
            .WillRepeatedly(DoAll(SaveArg<1>(&p_gatt_event_source_cb_), Return(0xDE)));

    // Mock GATT service registration success
    EXPECT_CALL(gatt_server_interface_, AddService(0xDE, _))
            .WillOnce(DoAll(SaveArg<0>(&server_if_),
                            [this](tGATT_IF /*server_if*/,
                                   std::vector<btgatt_db_element_t>* service) -> tGATT_STATUS {
                              // Assign some ATT handles
                              uint16_t handle_idx = 0x2000;
                              for (auto& el : *service) {
                                el.attribute_handle = handle_idx++;
                              }
                              service_db_ = *service;  // Store for using it by mock GATT layer
                              return service->empty() ? tGATT_STATUS::GATT_ERROR
                                                      : tGATT_STATUS::GATT_SERVICE_STARTED;
                            }));

    // Register GATT service instance providing the service descriptor
    ON_CALL(asc_callbacks_, OnAscsRegistered(_, _))
            .WillByDefault([&](std::set<uint8_t> sink_ases, std::set<uint8_t> source_ases) {
              sink_ase_ids_ = sink_ases;
              source_ase_ids_ = source_ases;
            });
    svc_desc_ = ProvideAscsDescriptor();
    ascs_->RegisterGattService(svc_desc_, &asc_callbacks_);

    // Check for the unique ASE IDs
    ASSERT_EQ(sink_ase_ids_.size(), 5lu);
    ASSERT_EQ(source_ase_ids_.size(), 3lu);

    auto all_ase_ids = sink_ase_ids_;
    all_ase_ids.merge(source_ase_ids_);
    ASSERT_EQ(all_ase_ids.size(), 8lu);

    ASSERT_NE(nullptr, p_gatt_event_source_cb_);
  }

  virtual void TearDown(void) override { AscsTestsBase::TearDown(); }

  void InjectGattConnectedEvent(RawAddress pseudo_addr) {
    static tCONN_ID conn_id = 0x01;

    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      conn_id_by_address_[pseudo_addr] = conn_id;

      p_gatt_event_source_cb_->p_conn_cb(server_if_, pseudo_addr, conn_id++, true, GATT_CONN_OK,
                                         BT_TRANSPORT_LE);
    }
  }

  void InjectGattDisconnectedEvent(RawAddress address) {
    tCONN_ID conn_id = GATT_INVALID_CONN_ID;

    if (conn_id_by_address_.count(address)) {
      conn_id = conn_id_by_address_.at(address);
      conn_id_by_address_.erase(address);
    }

    if (conn_id != GATT_INVALID_CONN_ID) {
      p_gatt_event_source_cb_->p_conn_cb(server_if_, address, conn_id, false, GATT_CONN_OK,
                                         BT_TRANSPORT_LE);
    }
  }

  void InjectCharacteristicReadRequest(RawAddress address, Uuid char_uuid, uint8_t index = 0) {
    if (conn_id_by_address_.count(address) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    for (auto const& el : service_db_) {
      if (el.uuid != char_uuid) {
        continue;
      }
      if (index == 0) {
        handle = el.attribute_handle;
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(address);
    if (conn_id != GATT_INVALID_CONN_ID) {
      p_gatt_event_source_cb_->p_req_cb->read_characteristic_cb(conn_id, gatt_trans_id_++, address,
                                                                handle, 0, false);
    }
  }

  void InjectCccDescriptorWriteRequest(RawAddress address, Uuid char_uuid, uint16_t cccd_value,
                                       uint8_t index = 0) {
    if (conn_id_by_address_.count(address) == 0) {
      // This can happen if the device is not connected.
      // In a real scenario, the stack would reject this, but for testing,
      // we can just fail the test to indicate a problem in the test logic.
      GTEST_FAIL();
    }

    // First - find the char value attribute
    uint16_t handle = 0x0000;
    for (auto el = service_db_.begin(); el != service_db_.end(); ++el) {
      if (el->uuid != char_uuid) {
        continue;
      }
      if (index == 0) {
        // Next- look further for the first CCCD uuid to get the cccd handle
        while (el != service_db_.end()) {
          if (el->uuid == Uuid("00002902-0000-1000-8000-00805F9B34FB")) {
            break;
          }
          ++el;
        }
        if (el != service_db_.end()) {
          handle = el->attribute_handle;
        }
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(address);
    if (conn_id != GATT_INVALID_CONN_ID) {
      uint8_t value[2];
      uint8_t* pp = value;
      UINT16_TO_STREAM(pp, cccd_value);

      p_gatt_event_source_cb_->p_req_cb->write_descriptor_cb(
              conn_id, gatt_trans_id_++, address, handle, 0, true, false, value, sizeof(value));
    }
  }

  void InjectAseCtpWriteRequest(RawAddress address, std::vector<uint8_t> value, bool with_rsp) {
    if (conn_id_by_address_.count(address) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    for (auto const& el : service_db_) {
      if (el.uuid != uuid::kAudioStreamEndpointControlPointCharacteristicUuid) {
        continue;
      }
      handle = el.attribute_handle;
      break;
    }

    auto conn_id = conn_id_by_address_.at(address);
    if (conn_id != GATT_INVALID_CONN_ID) {
      p_gatt_event_source_cb_->p_req_cb->write_characteristic_cb(
              conn_id, gatt_trans_id_++, address, handle, 0, with_rsp, false,
              (uint8_t*)value.data(), value.size());
    }
  }

  void testAseState(Ascs::AseState state, std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView>&
                                                  out_individual_ase_state_views) {
    ON_CALL(asc_callbacks_, OnGetAseState(_, _)).WillByDefault([&]() { return state; });
    ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
            .WillByDefault([&](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                               std::unique_ptr<tGATTS_RSP> p_msg) {
              ASSERT_EQ(GATT_SUCCESS, status);

              // Detects response to CCC descriptor write request
              if (p_msg == nullptr || (p_msg->attr_value.len == 0)) {
                log::debug("Most likely just a descriptor write response");
                return;
              }

              log::info("Verify the response against the service descriptor values");
              auto value = std::make_shared<std::vector<uint8_t>>(
                      p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
              auto char_view = bluetooth::ascs::AseCharValueBaseView::Create(
                      packet::PacketView<true>(value));
              ASSERT_TRUE(char_view.IsValid());
              out_individual_ase_state_views.insert({char_view.GetAseId(), char_view});
            });

    const size_t num_devices = 11;
    for (auto dev = num_devices; dev; --dev) {
      auto test_dev = GetTestAddress(dev);
      EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
      InjectGattConnectedEvent(test_dev);

      // Client subscribes to notifications on the ASE Control Point
      EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
      InjectCccDescriptorWriteRequest(test_dev,
                                      uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                      GATT_CLT_CONFIG_NOTIFICATION);

      // Verify the Sink ASE states are fetched
      for (uint8_t idx = 0; idx < svc_desc_.num_sink_ases; ++idx) {
        InSequence s;
        // In this test it is ok to return the default-constructed IDLE state
        EXPECT_CALL(asc_callbacks_, OnGetAseState(test_dev, _));
        EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
        InjectCharacteristicReadRequest(test_dev, uuid::kSinkAudioStreamEndpointUuid, idx);
      }

      // Verify the Source ASE states are fetched
      for (uint8_t idx = 0; idx < svc_desc_.num_source_ases; ++idx) {
        InSequence s;
        // In this test it is ok to return the default-constructed IDLE state
        EXPECT_CALL(asc_callbacks_, OnGetAseState(test_dev, _));
        EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
        InjectCharacteristicReadRequest(test_dev, uuid::kSourceAudioStreamEndpointUuid, idx);
      }
    }

    // Disconnect all the devices
    for (auto dev = num_devices; dev; --dev) {
      auto test_dev = GetTestAddress(dev);
      EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev));
      InjectGattDisconnectedEvent(test_dev);
    }

    // Make sure all ases were read
    ASSERT_EQ(out_individual_ase_state_views.size(),
              (size_t)svc_desc_.num_sink_ases + svc_desc_.num_source_ases);
  }
};

TEST_F(AscsTests, RegisterGattService) {
  // See SetUp() for GATT service registration verification
}

TEST_F(AscsTests, ConnectDisconnectSingleDevice) {
  auto test_dev1 = GetTestAddress(0x10);

  InjectGattConnectedEvent(test_dev1);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev1));
  InjectCccDescriptorWriteRequest(test_dev1,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
  ASSERT_NE(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev1));

  // Client unsubscribes
  EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev1));
  InjectCccDescriptorWriteRequest(test_dev1,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NONE);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);

  // GATT transport disconnects
  InjectGattDisconnectedEvent(test_dev1);
  ASSERT_EQ(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev1));
}

TEST_F(AscsTests, ConnectDisconnect) {
  const size_t num_devices = 11;
  for (auto dev = num_devices; dev; --dev) {
    auto test_dev = GetTestAddress(dev);
    InjectGattConnectedEvent(test_dev);

    // Client subscribes to notifications on the ASE Control Point
    EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
    InjectCccDescriptorWriteRequest(test_dev,
                                    uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);

    ASSERT_NE(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev));
    Mock::VerifyAndClearExpectations(&asc_callbacks_);
  }

  ASSERT_EQ(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(GetTestAddress(num_devices + 1)));

  for (auto dev = num_devices; dev; --dev) {
    auto test_dev = GetTestAddress(dev);

    // Client unsubscribes
    EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev));
    InjectCccDescriptorWriteRequest(test_dev,
                                    uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                    GATT_CLT_CONFIG_NONE);
    Mock::VerifyAndClearExpectations(&asc_callbacks_);

    // GATT transport disconnects
    InjectGattDisconnectedEvent(test_dev);
    ASSERT_EQ(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev));
    Mock::VerifyAndClearExpectations(&asc_callbacks_);
  }
}

TEST_F(AscsTests, ConnectNoOp) {
  auto test_dev = GetTestAddress(0x10);

  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
  ASSERT_NE(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev));

  // Client subscribes again with the same value - no new connection cb
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev)).Times(0);
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);

  // Client unsubscribes
  EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev));
  InjectCccDescriptorWriteRequest(
          test_dev, uuid::kAudioStreamEndpointControlPointCharacteristicUuid, GATT_CLT_CONFIG_NONE);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);

  // Client unsubscribes again with the same value - no new disconnection cb
  EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev)).Times(0);
  InjectCccDescriptorWriteRequest(
          test_dev, uuid::kAudioStreamEndpointControlPointCharacteristicUuid, GATT_CLT_CONFIG_NONE);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
}

TEST_F(AscsTests, CccpReSubscribeDoesNotTriggerCallbacks) {
  auto test_dev = GetTestAddress(0x10);

  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
  ASSERT_NE(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev));

  // Client re-subscribes with indications - no new connection cb
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev)).Times(0);
  EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev)).Times(0);
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_INDICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);

  // Client re-subscribes with notifications - no new connection cb
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev)).Times(0);
  EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev)).Times(0);
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
}

TEST_F(AscsTests, DisconnectTransportWhileSubscribed) {
  auto test_dev = GetTestAddress(0x10);

  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
  ASSERT_NE(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev));

  // Disconnect transport without unsubscribing
  EXPECT_CALL(asc_callbacks_, OnDeviceDisconnected(test_dev));
  InjectGattDisconnectedEvent(test_dev);
  ASSERT_EQ(GATT_INVALID_CONN_ID, ascs_->GetConnectionId(test_dev));
  Mock::VerifyAndClearExpectations(&asc_callbacks_);
}

TEST_F(AscsTests, RemoteReadAseStateIdle) {
  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::IDLE}, individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::IDLE, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseIdleCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());
  }
}

TEST_F(AscsTests, RemoteReadAseStateCodecConfigured) {
  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::CODEC_CONFIGURED,
                              .state_params = kStackCodecVendor1Configuration},
               individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::CODEC_CONFIGURED, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseCodecConfiguredCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());

    ASSERT_EQ(kStackCodecVendor1Configuration.framing, (uint8_t)state_view.GetFraming());
    ASSERT_EQ(kStackCodecVendor1Configuration.preferred_phy, (uint8_t)state_view.GetPreferredPhy());
    ASSERT_EQ(kStackCodecVendor1Configuration.preferred_retrans_nb, state_view.GetPreferredRtn());
    ASSERT_EQ(kStackCodecVendor1Configuration.max_transport_latency,
              state_view.GetMaxTransportLatency());
    ASSERT_EQ(kStackCodecVendor1Configuration.pres_delay_min, state_view.GetPresentationDelayMin());
    ASSERT_EQ(kStackCodecVendor1Configuration.pres_delay_max, state_view.GetPresentationDelayMax());
    ASSERT_EQ(kStackCodecVendor1Configuration.preferred_pres_delay_min,
              state_view.GetPreferredPresentationDelayMin());
    ASSERT_EQ(kStackCodecVendor1Configuration.preferred_pres_delay_max,
              state_view.GetPreferredPresentationDelayMax());

    auto codec = state_view.GetCodecId();
    ASSERT_EQ(kStackCodecVendor1.coding_format, codec.coding_format_);
    ASSERT_EQ(kStackCodecVendor1.vendor_company_id, codec.vendor_company_id_);
    ASSERT_EQ(kStackCodecVendor1.vendor_codec_id, codec.vendor_codec_id_);
  }
}

TEST_F(AscsTests, RemoteReadAseStateQosConfigured) {
  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::QOS_CONFIGURED,
                              .state_params = kStackCodecVendor1QosConfiguration},
               individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::QOS_CONFIGURED, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseQosConfiguredCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());

    ASSERT_EQ(kStackCodecVendor1QosConfiguration.cig_id, state_view.GetCigId());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.cis_id, state_view.GetCisId());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.sdu_interval, state_view.GetSduInterval());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.framing, (uint8_t)state_view.GetFraming());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.phy, (uint8_t)state_view.GetPhy());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.max_sdu, state_view.GetMaxSdu());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.retrans_nb, state_view.GetRtn());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.max_transport_latency,
              state_view.GetMaxTransportLatency());
    ASSERT_EQ(kStackCodecVendor1QosConfiguration.pres_delay, state_view.GetPresentationDelay());
  }
}

TEST_F(AscsTests, RemoteReadAseStateEnabling) {
  ascs::AseStateTransientParams kStackCodecVendor1TransientParams = {
          .cig_id = (uint8_t)1,
          .cis_id = (uint8_t)2,
          .metadata = kStackCodecVendor1MetadataLtv.RawPacket(),
  };

  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::ENABLING,
                              .state_params = kStackCodecVendor1TransientParams},
               individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::ENABLING, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseEnablingCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());

    ASSERT_EQ(kStackCodecVendor1TransientParams.cig_id, state_view.GetCigId());
    ASSERT_EQ(kStackCodecVendor1TransientParams.cis_id, state_view.GetCisId());
    ASSERT_EQ(kStackCodecVendor1TransientParams.metadata, state_view.GetMetadata());
  }
}

TEST_F(AscsTests, RemoteReadAseStateStreaming) {
  ascs::AseStateTransientParams kStackCodecVendor1TransientParams = {
          .cig_id = (uint8_t)1,
          .cis_id = (uint8_t)2,
          .metadata = kStackCodecVendor1MetadataLtv.RawPacket(),
  };

  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::STREAMING,
                              .state_params = kStackCodecVendor1TransientParams},
               individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::STREAMING, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseStreamingCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());

    ASSERT_EQ(kStackCodecVendor1TransientParams.cig_id, state_view.GetCigId());
    ASSERT_EQ(kStackCodecVendor1TransientParams.cis_id, state_view.GetCisId());
    ASSERT_EQ(kStackCodecVendor1TransientParams.metadata, state_view.GetMetadata());
  }
}

TEST_F(AscsTests, RemoteReadAseStateDisabling) {
  ascs::AseStateTransientParams kStackCodecVendor1TransientParams = {
          .cig_id = (uint8_t)1,
          .cis_id = (uint8_t)2,
          .metadata = kStackCodecVendor1MetadataLtv.RawPacket(),
  };

  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::DISABLING,
                              .state_params = kStackCodecVendor1TransientParams},
               individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::DISABLING, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseDisablingCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());

    ASSERT_EQ(kStackCodecVendor1TransientParams.cig_id, state_view.GetCigId());
    ASSERT_EQ(kStackCodecVendor1TransientParams.cis_id, state_view.GetCisId());
    ASSERT_EQ(kStackCodecVendor1TransientParams.metadata, state_view.GetMetadata());
  }
}

TEST_F(AscsTests, RemoteReadAseStateReleasing) {
  std::map<uint16_t, bluetooth::ascs::AseCharValueBaseView> individual_ase_state_views;
  testAseState(Ascs::AseState{.state = ascs::AseState::RELEASING}, individual_ase_state_views);

  // Verify the state view content
  for (auto const& [ase_id, base_view] : individual_ase_state_views) {
    ASSERT_EQ(bluetooth::ascs::AseStateValue::RELEASING, base_view.GetAseState());
    ASSERT_EQ(ase_id, base_view.GetAseId());
    auto state_view = bluetooth::ascs::AseReleasingCharValueView::Create(base_view);
    ASSERT_TRUE(state_view.IsValid());
  }
}

TEST_F(AscsTests, RemoteWriteAseCtpConfigCodec) {
  auto test_dev = GetTestAddress(0x10);

  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);

  // Prepare Config Codec command
  const uint8_t ase_id = 0x01;
  std::vector<bluetooth::ascs::AseControlPointConfigCodecRequestAseEntry> entries;
  entries.emplace_back(ase_id, bluetooth::ascs::Targetlatency::LOW_LATENCY,
                       bluetooth::ascs::TargetPhy::LE_2M_PHY,
                       bluetooth::ascs::CodecId(kStackCodecVendor1.coding_format,
                                                kStackCodecVendor1.vendor_company_id,
                                                kStackCodecVendor1.vendor_codec_id),
                       std::vector<uint8_t>{0xDE, 0xAD});
  auto builder = bluetooth::ascs::AseControlPointConfigCodecRequestBuilder::Create(entries);
  auto ctp_value = builder->SerializeToBytes();

  // Expect the request callback
  Ascs::AseCtpRequest received_request;
  EXPECT_CALL(asc_callbacks_, OnAseControlPointRequest(test_dev, _))
          .WillOnce(SaveArg<1>(&received_request));

  // Client sends the command (with response)
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectAseCtpWriteRequest(test_dev, ctp_value, true);

  // Verify the received request
  ASSERT_EQ(received_request.opcode, ascs::AseCtpOpcode::CONFIG_CODEC);
  auto& req_params =
          std::get<std::vector<ascs::AseCodecConfigurationReq>>(received_request.request_params);
  ASSERT_EQ(req_params.size(), 1u);
  ASSERT_EQ(req_params[0].ase_id, ase_id);
  ASSERT_EQ(req_params[0].codec_configuration.codec_id, kStackCodecVendor1);
  ASSERT_EQ(req_params[0].codec_configuration.codec_spec_conf, (std::vector<uint8_t>{0xDE, 0xAD}));

  // Upper layer responds to the request
  Ascs::AseCtpResponse response = {
          .opcode = ascs::AseCtpOpcode::CONFIG_CODEC,
          .entries = {{
                  .ase_id = ase_id,
                  .response_code = ascs::AseCtpResponseCode::SUCCESS,
                  .reason = ascs::AseCtpResponseReason::NO_REASON,
          }},
  };

  // Expect a notification on the control point
  std::vector<uint8_t> notified_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _))
          .WillOnce(DoAll(SaveArg<2>(&notified_value), Return(GATT_SUCCESS)));
  ascs_->AseCtpRequestResponse(test_dev, response);

  // Verify the notification
  auto notification_view = bluetooth::ascs::AseControlPointNotificationView::Create(
          packet::PacketView<packet::kLittleEndian>(
                  std::make_shared<std::vector<uint8_t>>(notified_value)));
  ASSERT_TRUE(notification_view.IsValid());
  ASSERT_EQ(notification_view.GetOpcode(), bluetooth::ascs::AseOpcode::CONFIG_CODEC);
  ASSERT_EQ(notification_view.GetAseEntries().size(), 1u);
  auto entry = *notification_view.GetAseEntries().begin();
  ASSERT_EQ(entry.ase_id_, ase_id);
  ASSERT_EQ(entry.response_code_, bluetooth::ascs::ResponseCode::SUCCESS);
  ASSERT_EQ(entry.reason_, static_cast<uint8_t>(ascs::AseCtpResponseReason::NO_REASON));
}

TEST_F(AscsTests, UpdateAseStateNotifies) {
  auto test_dev = GetTestAddress(0x10);
  const uint8_t sink_ase_id = 0x01;

  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);

  // Client subscribes to notifications on the Sink ASE
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev, uuid::kSinkAudioStreamEndpointUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION, 0 /* first sink ase */);

  // Upper layer updates the ASE state
  Ascs::AseState new_state = {.state = ascs::AseState::IDLE};

  // Expect a notification on the ASE characteristic
  std::vector<uint8_t> notified_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _))
          .WillOnce(DoAll(SaveArg<2>(&notified_value), Return(GATT_SUCCESS)));
  ascs_->UpdateAseState(test_dev, sink_ase_id, new_state);

  // Verify the notification
  auto state_view = bluetooth::ascs::AseIdleCharValueView::Create(
          bluetooth::ascs::AseCharValueBaseView::Create(packet::PacketView<packet::kLittleEndian>(
                  std::make_shared<std::vector<uint8_t>>(notified_value))));
  ASSERT_TRUE(state_view.IsValid());
  ASSERT_EQ(state_view.GetAseId(), sink_ase_id);
  ASSERT_EQ(state_view.GetAseState(), bluetooth::ascs::AseStateValue::IDLE);
}

TEST_F(AscsTests, RemoteWriteAseCtpEmptyPayload) {
  auto test_dev = GetTestAddress(0x10);

  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);

  // Client sends an empty write request
  EXPECT_CALL(asc_callbacks_, OnAseControlPointRequest(_, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_INVALID_ATTR_LEN, _));
  InjectAseCtpWriteRequest(test_dev, {}, true);
}

TEST_F(AscsTests, RemoteWriteAseCtpInvalidPacket) {
  auto test_dev = GetTestAddress(0x10);

  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);

  // Client sends a malformed packet (invalid opcode)
  std::vector<uint8_t> invalid_packet = {0xFF /* invalid opcode */, 0x01 /* num ases */,
                                         0x01 /* ase id */};

  EXPECT_CALL(asc_callbacks_, OnAseControlPointRequest(_, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_VALUE_NOT_ALLOWED, _));
  InjectAseCtpWriteRequest(test_dev, invalid_packet, true);
}

TEST_F(AscsTests, UpdateAseStateNoSubscription) {
  auto test_dev = GetTestAddress(0x10);
  const uint8_t sink_ase_id = 0x01;

  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev)).Times(0);
  InjectGattConnectedEvent(test_dev);

  // Do NOT subscribe to notifications

  // Upper layer updates the ASE state
  Ascs::AseState new_state = {.state = ascs::AseState::IDLE};

  // Expect NO notification
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  ascs_->UpdateAseState(test_dev, sink_ase_id, new_state);
}

TEST_F(AscsTests, UpdateAseStateTwice) {
  auto test_dev = GetTestAddress(0x10);
  const uint8_t sink_ase_id = 0x01;

  EXPECT_CALL(asc_callbacks_, OnDeviceConnected(test_dev));
  InjectGattConnectedEvent(test_dev);

  // Client subscribes to notifications on the ASE Control Point
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev,
                                  uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);

  // Client subscribes to notifications on the Sink ASE
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCccDescriptorWriteRequest(test_dev, uuid::kSinkAudioStreamEndpointUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION, 0 /* first sink ase */);

  // Upper layer updates the ASE state to IDLE
  Ascs::AseState idle_state = {.state = ascs::AseState::IDLE};

  // Expect one notification for the first update
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(1);
  ascs_->UpdateAseState(test_dev, sink_ase_id, idle_state);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Update again with the same state - expect another notification even if value is the same
  // Note: This behavior is mandated by ASCS V1.0, Sec. 5
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(1);
  ascs_->UpdateAseState(test_dev, sink_ase_id, idle_state);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);
}

TEST_F(AscsTests, VerifyPersistentStorage) {
  GTEST_SKIP() << "TODO: Persistent storage of CCCD values is not yet implemented";
}

}  // namespace bluetooth::le_audio::test
