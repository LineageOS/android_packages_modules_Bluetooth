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

#include "bta/le_audio/pacs/pacs.h"

#include <android/log.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta_gatt_api_mock.h"
#include "btm_api_mock.h"
#include "pacs/pacs_packets.h"
#include "stack/include/btm_client_interface.h"

using ::testing::_;
using ::testing::DoAll;
using ::testing::InSequence;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::SaveArg;

namespace bluetooth::le_audio::test {

static RawAddress GetTestAddress(int index) {
  EXPECT_LT(index, UINT8_MAX);
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, static_cast<uint8_t>(index)};
  return RawAddress(bytes);
}

class MockPacsCallbacks : public Pacs::Callbacks {
public:
  // clang-format off
  MOCK_METHOD((void), OnPacsRegistered, (), (override));
  MOCK_METHOD((void), OnDeviceConnected, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnDeviceDisconnected, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((types::BidirectionalPair<types::AudioContexts>), OnGetAvailableAudioContexts,
              (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnAudioLocationsWritten,
              (const RawAddress& pseudo_addr, uint8_t direction,
               const types::AudioLocations& audio_locations),
              (override));
  // clang-format on
};

class PacsTestsBase : public ::testing::Test {
public:
  std::shared_ptr<Pacs> pacs_;

  NiceMock<gatt::MockBtaGattServerInterface> gatt_server_interface_;
  MockPacsCallbacks pac_callbacks_;

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
    pacs_ = InstantiatePacs();
  }

  virtual void TearDown(void) override { ReleasePacs(std::move(pacs_)); }
};

TEST_F(PacsTestsBase, InstantiateRelease) {
  auto const old_ptr = pacs_.get();
  ASSERT_NE(old_ptr, nullptr);

  // Make sure destructing not-registered client has no side effect
  EXPECT_CALL(gatt_server_interface_, AppDeregister(_)).Times(0);
  ReleasePacs(std::move(pacs_));
  ASSERT_EQ(pacs_.get(), nullptr);

  // Reinstantiate
  pacs_ = InstantiatePacs();
  ASSERT_NE(pacs_.get(), nullptr);
}

TEST_F(PacsTestsBase, RegisterCallbacks) {
  tBTA_GATTS_CBACK* p_gatt_event_source_cb = nullptr;
  tBTA_GATTS p_data;

  // Check GATT server app registration
  Pacs::ServiceDescriptor service_descriptor;
  service_descriptor.pac_sets.sink.push_back({});
  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
          .WillOnce(DoAll(SaveArg<0>(&p_data.reg_oper.uuid), SaveArg<1>(&p_gatt_event_source_cb)));
  pacs_->RegisterGattService(service_descriptor, &pac_callbacks_);
  ASSERT_NE(nullptr, p_gatt_event_source_cb);
  ASSERT_EQ(uuid::kPublishedAudioCapabilityServiceUuid, p_data.reg_oper.uuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Inject the registration success event
  p_data.reg_oper.status = tGATT_STATUS::GATT_SUCCESS;
  p_data.reg_oper.server_if = 0xDE;
  p_gatt_event_source_cb(BTA_GATTS_REG_EVT, &p_data);

  // Ignore second call to register
  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
          .Times(0);
  pacs_->RegisterGattService(service_descriptor, &pac_callbacks_);

  // Make sure destructing unregisters the server interface
  EXPECT_CALL(gatt_server_interface_, AppDeregister(p_data.reg_oper.server_if));
  ReleasePacs(std::move(pacs_));
}

TEST_F(PacsTestsBase, RegisterCallbacksAppRegisterFailsDeathTest) {
  // Check GATT server app registration failure
  Pacs::ServiceDescriptor service_descriptor;
  service_descriptor.pac_sets.sink.push_back({});
  ON_CALL(gatt_server_interface_, AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
          .WillByDefault([](const bluetooth::Uuid&, tBTA_GATTS_CBACK* p_cback, bool) {
            tBTA_GATTS p_data;
            p_data.reg_oper.status = tGATT_STATUS::GATT_ERROR;
            p_cback(BTA_GATTS_REG_EVT, &p_data);
          });

  EXPECT_CALL(pac_callbacks_, OnPacsRegistered()).Times(0);
  ASSERT_DEATH(pacs_->RegisterGattService(service_descriptor, &pac_callbacks_),
               "Failed to register GATT Server");
}

class PacsTests : public PacsTestsBase {
public:
  tBTA_GATTS_CBACK* p_gatt_event_source_cb_ = nullptr;
  std::vector<btgatt_db_element_t> service_db_;
  tGATT_IF server_if_;

  uint32_t gatt_trans_id_ = 0x00000001;
  Pacs::ServiceDescriptor svc_desc_;

  std::map<RawAddress, tCONN_ID> conn_id_by_address_;

  static Pacs::ServiceDescriptor ProvidePacsDescriptor() {
    auto codec_spec_cap =
            types::LeAudioLtvMap()
                    .Add(codec_spec_caps::kLeAudioLtvTypeSupportedSamplingFrequencies,
                         (uint16_t)codec_spec_caps::kLeAudioSamplingFreq48000Hz)
                    .Add(codec_spec_caps::kLeAudioLtvTypeSupportedFrameDurations,
                         (uint8_t)codec_spec_caps::kLeAudioCodecFrameDur10000us)
                    .Add(codec_spec_caps::kLeAudioLtvTypeSupportedAudioChannelCounts,
                         (uint8_t)codec_spec_caps::kLeAudioCodecChannelCountSingleChannel)
                    .Add(codec_spec_caps::kLeAudioLtvTypeSupportedOctetsPerCodecFrame,
                         std::vector<uint8_t>{(codec_spec_caps::kLeAudioCodecFrameLen120 >> 0),
                                              codec_spec_caps::kLeAudioCodecFrameLen120 >> 8,
                                              codec_spec_caps::kLeAudioCodecFrameLen120 >> 0,
                                              (codec_spec_caps::kLeAudioCodecFrameLen120 >> 8)})
                    .Add(codec_spec_caps::kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu,
                         (uint8_t)0x01);
    uint8_t pac_set_id = 0;
    std::vector<Pacs::PacSet> sink_pac_sets = {
            // First characteristic
            {
                    .id = pac_set_id++,
                    .records =
                            {
                                    Pacs::PacRecord(types::LeAudioCodecId(0x06, 0x0000, 0x0000),
                                                    codec_spec_cap.RawPacket(),
                                                    {/*empty metadata*/}),
                            },
            },
            // Second characteristic with one record for now
            {
                    .id = pac_set_id++,
                    .records =
                            {
                                    Pacs::PacRecord(
                                            types::LeAudioCodecId(
                                                    types::kLeAudioCodingFormatVendorSpecific,
                                                    types::kLeAudioVendorCompanyIdGoogle,
                                                    types::kLeAudioVendorCodecIdOpus),
                                            codec_spec_cap.RawPacket(), {/*empty metadata*/}),
                            },
            },
    };
    std::vector<Pacs::PacSet> source_pac_sets = {
            // First characteristic
            {
                    .id = pac_set_id++,
                    .records =
                            {
                                    Pacs::PacRecord(types::LeAudioCodecId(0x06, 0x0000, 0x0000),
                                                    codec_spec_cap.RawPacket(),
                                                    {/*empty metadata*/}),
                            },
            },
            // Second characteristic with one record for now
            {
                    .id = pac_set_id++,
                    .records =
                            {
                                    Pacs::PacRecord(
                                            types::LeAudioCodecId(
                                                    types::kLeAudioCodingFormatVendorSpecific,
                                                    types::kLeAudioVendorCompanyIdGoogle,
                                                    types::kLeAudioVendorCodecIdOpus),
                                            codec_spec_cap.RawPacket(), {/*empty metadata*/}),
                            },
            },
    };
    Pacs::ServiceDescriptor pacs_descriptor{
            .pac_sets = {.sink = sink_pac_sets, .source = source_pac_sets},
            .audio_locations = types::BidirectionalPair<types::AudioLocations>(
                    codec_spec_conf::kLeAudioLocationFrontLeft |
                            codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationMonoAudio),
            .supported_audio_contexts = types::BidirectionalPair<types::AudioContexts>(
                    types::kLeAudioContextAllTypes, types::kLeAudioContextAllTypes),
    };
    return pacs_descriptor;
  }

protected:
  void RegisterService(const Pacs::ServiceDescriptor& desc) {
    // Mock GATT application registration success
    EXPECT_CALL(gatt_server_interface_,
                AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
            .WillRepeatedly(DoAll(SaveArg<1>(&p_gatt_event_source_cb_),
                                  [](const bluetooth::Uuid& /* app_uuid */,
                                     tBTA_GATTS_CBACK* p_cback, bool /* eatt_support */) {
                                    tBTA_GATTS p_data;
                                    p_data.reg_oper.status = (p_cback == nullptr)
                                                                     ? tGATT_STATUS::GATT_ERROR
                                                                     : tGATT_STATUS::GATT_SUCCESS;
                                    p_data.reg_oper.server_if = 0xDE;
                                    p_cback(BTA_GATTS_REG_EVT, &p_data);
                                  }));

    // Mock GATT service registration success
    EXPECT_CALL(gatt_server_interface_, AddService(0xDE, _, _))
            .WillOnce(DoAll(SaveArg<0>(&server_if_),
                            [this](tGATT_IF server_if, std::vector<btgatt_db_element_t> service,
                                   BTA_GATTS_AddServiceCb cb) {
                              // Assign some ATT handles
                              uint16_t handle_idx = 0x2000;
                              service_db_ = service;  // Store for using it by mock GATT layer
                              for (auto& el : service_db_) {
                                el.attribute_handle = handle_idx++;
                              }
                              auto status = service.empty() ? tGATT_STATUS::GATT_ERROR
                                                            : tGATT_STATUS::GATT_SUCCESS;
                              std::move(cb).Run(status, server_if, service_db_);
                            }));

    // Register GATT service instance providing the service descriptor
    EXPECT_CALL(pac_callbacks_, OnPacsRegistered());
    svc_desc_ = desc;
    pacs_->RegisterGattService(svc_desc_, &pac_callbacks_);

    ASSERT_NE(nullptr, p_gatt_event_source_cb_);
  }

  virtual Pacs::ServiceDescriptor GetServiceDescriptorForTest() { return ProvidePacsDescriptor(); }

public:
  void SetUp(void) final {
    PacsTestsBase::SetUp();
    auto desc = GetServiceDescriptorForTest();
    RegisterService(desc);
  }

  virtual void TearDown(void) override { PacsTestsBase::TearDown(); }

  void InjectGattConnectedEvent(RawAddress pseudo_addr) {
    static tCONN_ID conn_id = 0x01;

    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      conn_id_by_address_[pseudo_addr] = conn_id;

      tBTA_GATTS p_data;
      p_data.conn.remote_bda = pseudo_addr;
      p_data.conn.conn_id = conn_id++;
      p_data.conn.server_if = server_if_;
      p_data.conn.transport = BT_TRANSPORT_LE;
      p_gatt_event_source_cb_(BTA_GATTS_CONNECT_EVT, &p_data);
    }
  }

  void InjectGattDisconnectedEvent(RawAddress pseudo_addr) {
    tCONN_ID conn_id = GATT_INVALID_CONN_ID;

    if (conn_id_by_address_.count(pseudo_addr)) {
      conn_id = conn_id_by_address_.at(pseudo_addr);
      conn_id_by_address_.erase(pseudo_addr);
    }

    if (conn_id != GATT_INVALID_CONN_ID) {
      tBTA_GATTS p_data;
      p_data.conn.remote_bda = pseudo_addr;
      p_data.conn.conn_id = conn_id;
      p_data.conn.server_if = server_if_;
      p_data.conn.transport = BT_TRANSPORT_LE;
      p_gatt_event_source_cb_(BTA_GATTS_DISCONNECT_EVT, &p_data);
    }
  }

  void InjectCharacteristicReadRequest(RawAddress pseudo_addr, Uuid char_uuid, uint8_t index = 0) {
    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    tGATT_PERM permissions = 0;
    for (auto const& el : service_db_) {
      if (el.uuid != char_uuid) {
        continue;
      }
      if (index == 0) {
        handle = el.attribute_handle;
        permissions = el.permissions;
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(pseudo_addr);
    if (conn_id != GATT_INVALID_CONN_ID) {
      tGATTS_DATA attribute_data;

      // Simulate GATT layer permission check from gatt_db.cc
      if (!(permissions & GATT_READ_ALLOWED)) {
        gatt_server_interface_.SendRsp(conn_id, gatt_trans_id_++, GATT_READ_NOT_PERMIT, nullptr);
        return;
      }

      attribute_data.read_req.handle = handle;
      attribute_data.read_req.offset = 0x0000;
      attribute_data.read_req.is_long = false;
      attribute_data.read_req.gatt_type = BTGATT_DB_CHARACTERISTIC;

      tBTA_GATTS gatts_data;
      gatts_data.req_data.remote_bda = pseudo_addr;
      gatts_data.req_data.trans_id = gatt_trans_id_++;
      gatts_data.req_data.conn_id = conn_id;
      gatts_data.req_data.p_data = &attribute_data;

      p_gatt_event_source_cb_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &gatts_data);
    }
  }

  void InjectCharacteristicReadRequestWithOffset(RawAddress pseudo_addr, Uuid char_uuid,
                                                 uint16_t offset, uint8_t index = 0) {
    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    tGATT_PERM permissions = 0;
    for (auto const& el : service_db_) {
      if (el.uuid != char_uuid) {
        continue;
      }
      if (index == 0) {
        handle = el.attribute_handle;
        permissions = el.permissions;
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(pseudo_addr);
    if (conn_id != GATT_INVALID_CONN_ID) {
      tGATTS_DATA attribute_data;

      // Simulate GATT layer permission check from gatt_db.cc
      if (!(permissions & GATT_READ_ALLOWED)) {
        gatt_server_interface_.SendRsp(conn_id, gatt_trans_id_++, GATT_READ_NOT_PERMIT, nullptr);
        return;
      }

      attribute_data.read_req.handle = handle;
      attribute_data.read_req.offset = offset;
      attribute_data.read_req.is_long = false;
      attribute_data.read_req.gatt_type = bt_gatt_db_attribute_type_t::BTGATT_DB_CHARACTERISTIC;

      tBTA_GATTS gatts_data;
      gatts_data.req_data.remote_bda = pseudo_addr;
      gatts_data.req_data.trans_id = gatt_trans_id_++;
      gatts_data.req_data.conn_id = conn_id;
      gatts_data.req_data.p_data = &attribute_data;

      p_gatt_event_source_cb_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &gatts_data);
    }
  }

  void InjectCharacteristicWriteRequest(RawAddress pseudo_addr, Uuid char_uuid,
                                        const std::vector<uint8_t>& value,
                                        bool with_response = true, uint8_t index = 0) {
    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    tGATT_PERM permissions = 0;
    for (auto const& el : service_db_) {
      if (el.uuid != char_uuid) {
        continue;
      }
      if (index == 0) {
        handle = el.attribute_handle;
        permissions = el.permissions;
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(pseudo_addr);
    if (conn_id != GATT_INVALID_CONN_ID) {
      // Simulate GATT layer permission check from gatt_db.cc
      if (!(permissions & GATT_WRITE_ALLOWED)) {
        if (with_response) {
          gatt_server_interface_.SendRsp(conn_id, gatt_trans_id_++, GATT_WRITE_NOT_PERMIT, nullptr);
        }
        return;
      }

      tGATTS_DATA attribute_data;
      attribute_data.write_req.handle = handle;
      attribute_data.write_req.offset = 0x0000;
      attribute_data.write_req.need_rsp = with_response;
      attribute_data.write_req.is_prep = false;
      attribute_data.write_req.gatt_type = BTGATT_DB_CHARACTERISTIC;

      attribute_data.write_req.len = value.size();
      std::copy(value.begin(), value.end(), attribute_data.write_req.value);

      tBTA_GATTS gatts_data;
      gatts_data.req_data.remote_bda = pseudo_addr;
      gatts_data.req_data.trans_id = gatt_trans_id_++;
      gatts_data.req_data.conn_id = conn_id;
      gatts_data.req_data.p_data = &attribute_data;

      p_gatt_event_source_cb_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &gatts_data);
    }
  }

  void InjectCCCDescriptorReadRequest(RawAddress pseudo_addr, Uuid char_uuid, uint8_t index = 0) {
    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      GTEST_FAIL();
    }

    // First - find the char value attribute
    uint16_t handle = 0x0000;
    tGATT_PERM permissions = 0;
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
          permissions = el->permissions;
        }
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(pseudo_addr);
    if (conn_id != GATT_INVALID_CONN_ID) {
      tGATTS_DATA attribute_data;

      // Simulate GATT layer permission check from gatt_db.cc
      if (!(permissions & GATT_READ_ALLOWED)) {
        gatt_server_interface_.SendRsp(conn_id, gatt_trans_id_++, GATT_READ_NOT_PERMIT, nullptr);
        return;
      }

      attribute_data.read_req.handle = handle;
      attribute_data.read_req.offset = 0x0000;
      attribute_data.read_req.is_long = false;
      attribute_data.read_req.gatt_type = bt_gatt_db_attribute_type_t::BTGATT_DB_DESCRIPTOR;

      tBTA_GATTS gatts_data;
      gatts_data.req_data.remote_bda = pseudo_addr;
      gatts_data.req_data.trans_id = gatt_trans_id_++;
      gatts_data.req_data.conn_id = conn_id;
      gatts_data.req_data.p_data = &attribute_data;

      p_gatt_event_source_cb_(BTA_GATTS_READ_DESCRIPTOR_EVT, &gatts_data);
    }
  }

  void InjectCccDescriptorWriteRequest(RawAddress pseudo_addr, Uuid char_uuid, uint16_t cccd_value,
                                       uint8_t index = 0) {
    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      GTEST_FAIL();
    }

    // First - find the char value attribute
    uint16_t handle = 0x0000;
    tGATT_PERM permissions = 0;
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
          permissions = el->permissions;
        }
        break;
      }
      --index;
    }

    auto conn_id = conn_id_by_address_.at(pseudo_addr);
    if (conn_id != GATT_INVALID_CONN_ID) {
      // Simulate GATT layer permission check from gatt_db.cc
      if (!(permissions & GATT_WRITE_ALLOWED)) {
        gatt_server_interface_.SendRsp(conn_id, gatt_trans_id_++, GATT_WRITE_NOT_PERMIT, nullptr);
        return;
      }

      tGATTS_DATA attribute_data;
      attribute_data.write_req.handle = handle;
      attribute_data.write_req.offset = 0x0000;
      attribute_data.write_req.need_rsp = true;
      attribute_data.write_req.is_prep = false;
      attribute_data.write_req.gatt_type = BTGATT_DB_DESCRIPTOR;

      auto* pp = attribute_data.write_req.value;
      UINT16_TO_STREAM(pp, cccd_value);
      attribute_data.write_req.len = sizeof(cccd_value);

      tBTA_GATTS gatts_data;
      gatts_data.req_data.remote_bda = pseudo_addr;
      gatts_data.req_data.trans_id = gatt_trans_id_++;
      gatts_data.req_data.conn_id = conn_id;
      gatts_data.req_data.p_data = &attribute_data;

      p_gatt_event_source_cb_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &gatts_data);
    }
  }
};

class PacsRegistrationFailureTests : public PacsTestsBase {
public:
  tGATT_IF server_if_ = 0xDE;

  virtual void SetUp(void) override {
    PacsTestsBase::SetUp();

    // Mock GATT application registration success
    EXPECT_CALL(gatt_server_interface_,
                AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
            .WillRepeatedly([this](const bluetooth::Uuid& /* app_uuid */, tBTA_GATTS_CBACK* p_cback,
                                   bool /* eatt_support */) {
              tBTA_GATTS p_data;
              p_data.reg_oper.status = tGATT_STATUS::GATT_SUCCESS;
              p_data.reg_oper.server_if = server_if_;
              p_cback(BTA_GATTS_REG_EVT, &p_data);
            });
  }
};

TEST_F(PacsTests, RegisterGattService) {
  // See SetUp() for GATT service registration verification
}

TEST_F(PacsTests, ConnectDisconnectSingleDevice) {
  auto test_dev1 = GetTestAddress(0x10);

  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);

  EXPECT_CALL(pac_callbacks_, OnDeviceDisconnected(test_dev1));
  InjectGattDisconnectedEvent(test_dev1);
}

TEST_F(PacsTests, ConnectDisconnect) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);

  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);

  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);

  EXPECT_CALL(pac_callbacks_, OnDeviceDisconnected(test_dev1));
  InjectGattDisconnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);

  EXPECT_CALL(pac_callbacks_, OnDeviceDisconnected(test_dev2));
  InjectGattDisconnectedEvent(test_dev2);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);
}

TEST_F(PacsTests, GetConnectionId) {
  auto test_dev1 = GetTestAddress(0x10);
  auto unknown_dev = GetTestAddress(0xFE);

  // Initially, devices are not connected
  ASSERT_EQ(pacs_->GetConnectionId(test_dev1), GATT_INVALID_CONN_ID);
  ASSERT_EQ(pacs_->GetConnectionId(unknown_dev), GATT_INVALID_CONN_ID);

  // Connect device
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);

  // Verify connection ID is valid
  uint16_t conn_id = pacs_->GetConnectionId(test_dev1);
  ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
  ASSERT_EQ(conn_id, conn_id_by_address_.at(test_dev1));

  // Disconnect device
  EXPECT_CALL(pac_callbacks_, OnDeviceDisconnected(test_dev1));
  InjectGattDisconnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);

  // Verify connection ID is now invalid
  ASSERT_EQ(pacs_->GetConnectionId(test_dev1), GATT_INVALID_CONN_ID);
}

TEST_F(PacsTests, RemoteReadSupportedContexts) {
  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([this](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            log::info("Verify the response against the service descriptor values");
            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view =
                    pacs::AudioContextsCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());
            ASSERT_EQ(char_view.GetSinkContexts(), svc_desc_.supported_audio_contexts.sink.value());
            ASSERT_EQ(char_view.GetSourceContexts(),
                      svc_desc_.supported_audio_contexts.source.value());
          });

  InSequence s;
  // Check dev1
  auto test_dev1 = GetTestAddress(0x10);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev1, uuid::kSupportedAudioContextsCharacteristicUuid);

  // Check dev2
  auto test_dev2 = GetTestAddress(0x20);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev2, uuid::kSupportedAudioContextsCharacteristicUuid);
}

TEST_F(PacsTests, RemoteReadSinkAudioLocations) {
  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([this](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            log::info("Verify the response against the service descriptor values");
            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view =
                    pacs::AudioLocationsCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());
            ASSERT_EQ(char_view.GetAudioLocations(), svc_desc_.audio_locations.sink.to_ulong());
          });

  InSequence s;
  // Check dev1
  auto test_dev1 = GetTestAddress(0x10);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid);

  // Check dev1
  auto test_dev2 = GetTestAddress(0x20);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev2, uuid::kSinkAudioLocationCharacteristicUuid);
}

TEST_F(PacsTests, RemoteReadSourceAudioLocations) {
  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([this](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            log::info("Verify the response against the service descriptor values");
            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view =
                    pacs::AudioLocationsCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());
            ASSERT_EQ(char_view.GetAudioLocations(), svc_desc_.audio_locations.source.to_ulong());
          });

  InSequence s;
  // Check dev1
  auto test_dev1 = GetTestAddress(0x10);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev1, uuid::kSourceAudioLocationCharacteristicUuid);

  // Check dev2
  auto test_dev2 = GetTestAddress(0x20);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev2, uuid::kSourceAudioLocationCharacteristicUuid);
}

class PacsWritableAudioLocationsTest : public PacsTests {
  Pacs::ServiceDescriptor GetServiceDescriptorForTest() override {
    auto desc = ProvidePacsDescriptor();
    desc.audio_locations_writable = {true, true};
    return desc;
  }
};

TEST_F(PacsTests, RemoteWriteAudioLocationsNotPermitted) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Test Sink Audio Location write - should be rejected
  types::AudioLocations sink_locations(codec_spec_conf::kLeAudioLocationFrontLeft);
  auto sink_value = pacs::AudioLocationsCharValueBuilder::Create(sink_locations.to_ullong())
                            ->SerializeToBytes();

  EXPECT_CALL(pac_callbacks_, OnAudioLocationsWritten(_, _, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_WRITE_NOT_PERMIT, _));
  InjectCharacteristicWriteRequest(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                   sink_value);
}

TEST_F(PacsWritableAudioLocationsTest, RemoteWriteAudioLocationsConfirmed) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Confirm the locations write request
  ON_CALL(pac_callbacks_, OnAudioLocationsWritten(test_dev1, _, _))
          .WillByDefault([this](const RawAddress& pseudo_addr, uint8_t /*direction*/,
                                const types::AudioLocations& /*audio_locations*/) {
            pacs_->ConfirmAudioLocationsWritten(pseudo_addr, true);
          });

  // Test Sink Audio Location write - should succeed
  types::AudioLocations sink_locations(codec_spec_conf::kLeAudioLocationFrontLeft);
  auto sink_value = pacs::AudioLocationsCharValueBuilder::Create(sink_locations.to_ullong())
                            ->SerializeToBytes();
  EXPECT_CALL(pac_callbacks_,
              OnAudioLocationsWritten(test_dev1, types::kLeAudioDirectionSink, sink_locations));
  EXPECT_CALL(gatt_server_interface_,
              SendRsp(conn_id_by_address_.at(test_dev1), _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                   sink_value, true);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Test Source Audio Location write with response - should succeed
  types::AudioLocations source_locations(codec_spec_conf::kLeAudioLocationFrontRight);
  auto source_value = pacs::AudioLocationsCharValueBuilder::Create(source_locations.to_ullong())
                              ->SerializeToBytes();
  EXPECT_CALL(pac_callbacks_,
              OnAudioLocationsWritten(test_dev1, types::kLeAudioDirectionSource, source_locations));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev1, uuid::kSourceAudioLocationCharacteristicUuid,
                                   source_value, true);
}

TEST_F(PacsWritableAudioLocationsTest, RemoteWriteAudioLocationsRejected) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Confirm the locations write request
  ON_CALL(pac_callbacks_, OnAudioLocationsWritten(test_dev1, _, _))
          .WillByDefault([this](const RawAddress& pseudo_addr, uint8_t /*direction*/,
                                const types::AudioLocations& /*audio_locations*/) {
            pacs_->ConfirmAudioLocationsWritten(pseudo_addr, false);
          });

  // Test Sink Audio Location write - should succeed
  types::AudioLocations sink_locations(codec_spec_conf::kLeAudioLocationFrontLeft);
  auto sink_value = pacs::AudioLocationsCharValueBuilder::Create(sink_locations.to_ullong())
                            ->SerializeToBytes();
  EXPECT_CALL(pac_callbacks_,
              OnAudioLocationsWritten(test_dev1, types::kLeAudioDirectionSink, sink_locations));
  EXPECT_CALL(gatt_server_interface_,
              SendRsp(conn_id_by_address_.at(test_dev1), _, GATT_WRITE_REQ_REJECTED, _));
  InjectCharacteristicWriteRequest(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                   sink_value, true);
  Mock::VerifyAndClearExpectations(&pac_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Test Source Audio Location write with response - should succeed
  types::AudioLocations source_locations(codec_spec_conf::kLeAudioLocationFrontRight);
  auto source_value = pacs::AudioLocationsCharValueBuilder::Create(source_locations.to_ullong())
                              ->SerializeToBytes();
  EXPECT_CALL(pac_callbacks_,
              OnAudioLocationsWritten(test_dev1, types::kLeAudioDirectionSource, source_locations));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_WRITE_REQ_REJECTED, _));
  InjectCharacteristicWriteRequest(test_dev1, uuid::kSourceAudioLocationCharacteristicUuid,
                                   source_value, true);
}

TEST_F(PacsTests, RemoteReadSinkPacs) {
  uint8_t verified_pac_chars = 0;
  uint8_t pac_char_idx = 0;

  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([&, this](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                   tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            log::info("Verify the response against the service descriptor values");
            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view = pacs::PacCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());

            // Make sure we check the right PAC characteristic at pac_char_idx
            auto const& received_pac_records = char_view.GetPacRecords();
            auto const& expected_pac_records = svc_desc_.pac_sets.sink.at(pac_char_idx).records;
            ASSERT_NE(received_pac_records.size(), 0lu);
            ASSERT_EQ(received_pac_records.size(), expected_pac_records.size());
            verified_pac_chars++;
            for (size_t idx = 0; idx < received_pac_records.size(); ++idx) {
              // Verify the CodecId
              ASSERT_EQ(received_pac_records.at(idx).codec_id_.coding_format_,
                        expected_pac_records.at(idx).codec_id.coding_format);
              ASSERT_EQ(received_pac_records.at(idx).codec_id_.vendor_company_id_,
                        expected_pac_records.at(idx).codec_id.vendor_company_id);
              ASSERT_EQ(received_pac_records.at(idx).codec_id_.vendor_codec_id_,
                        expected_pac_records.at(idx).codec_id.vendor_codec_id);
              // Verify the codec_spec_cap
              ASSERT_EQ(received_pac_records.at(idx).codec_specific_capabilities_,
                        expected_pac_records.at(idx).codec_spec_caps);
              // Verify the metadata
              ASSERT_EQ(received_pac_records.at(idx).metadata_,
                        expected_pac_records.at(idx).metadata);
            }
          });

  InSequence s;
  // Check dev1
  auto test_dev1 = GetTestAddress(0x10);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);

  // Read both PAC characteristics
  pac_char_idx = 0;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(test_dev1, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  pac_char_idx);
  pac_char_idx = 1;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(test_dev1, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  pac_char_idx);

  // Make sure all Sink PAC characteristics were verified
  ASSERT_EQ(svc_desc_.pac_sets.sink.size(), verified_pac_chars);
  verified_pac_chars = 0;

  // Check dev2
  auto test_dev2 = GetTestAddress(0x20);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);

  // Read both PAC characteristics
  pac_char_idx = 0;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(test_dev2, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  pac_char_idx);
  pac_char_idx = 1;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(test_dev2, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  pac_char_idx);

  // Make sure all Sink PAC characteristics were verified
  ASSERT_EQ(svc_desc_.pac_sets.sink.size(), verified_pac_chars);
}

TEST_F(PacsTests, RemoteReadSourcePacs) {
  uint8_t verified_pac_chars = 0;
  uint8_t pac_char_idx = 0;

  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([&, this](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                   tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            log::info("Verify the response against the service descriptor values");
            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view = pacs::PacCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());

            // Make sure we check the right PAC characteristic at pac_char_idx
            auto const& received_pac_records = char_view.GetPacRecords();
            auto const& expected_pac_records = svc_desc_.pac_sets.source.at(pac_char_idx).records;
            ASSERT_NE(received_pac_records.size(), 0lu);
            ASSERT_EQ(received_pac_records.size(), expected_pac_records.size());
            verified_pac_chars++;
            for (size_t idx = 0; idx < received_pac_records.size(); ++idx) {
              // Verify the CodecId
              ASSERT_EQ(received_pac_records.at(idx).codec_id_.coding_format_,
                        expected_pac_records.at(idx).codec_id.coding_format);
              ASSERT_EQ(received_pac_records.at(idx).codec_id_.vendor_company_id_,
                        expected_pac_records.at(idx).codec_id.vendor_company_id);
              ASSERT_EQ(received_pac_records.at(idx).codec_id_.vendor_codec_id_,
                        expected_pac_records.at(idx).codec_id.vendor_codec_id);
              // Verify the codec_spec_cap
              ASSERT_EQ(received_pac_records.at(idx).codec_specific_capabilities_,
                        expected_pac_records.at(idx).codec_spec_caps);
              // Verify the metadata
              ASSERT_EQ(received_pac_records.at(idx).metadata_,
                        expected_pac_records.at(idx).metadata);
            }
          });

  InSequence s;
  // Check dev1
  auto test_dev1 = GetTestAddress(0x10);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);

  // Read both PAC characteristics
  pac_char_idx = 0;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(
          test_dev1, uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, pac_char_idx);
  pac_char_idx = 1;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(
          test_dev1, uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, pac_char_idx);

  // Make sure all Sink PAC characteristics were verified
  ASSERT_EQ(svc_desc_.pac_sets.source.size(), verified_pac_chars);
  verified_pac_chars = 0;

  // Check dev2
  auto test_dev2 = GetTestAddress(0x20);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);

  // Read both PAC characteristics
  pac_char_idx = 0;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(
          test_dev2, uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, pac_char_idx);
  pac_char_idx = 1;
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicReadRequest(
          test_dev2, uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, pac_char_idx);

  // Make sure all Sink PAC characteristics were verified
  ASSERT_EQ(svc_desc_.pac_sets.source.size(), verified_pac_chars);
}

TEST_F(PacsTests, RemoteReadAvailableContexts) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);

  // Make sure we provide individual device context availability when the GATT service asks
  std::map<RawAddress, types::BidirectionalPair<types::AudioContexts>>
          audio_context_availability_by_device = {
                  // Allow one device to stream CONVERSATIONAL and GAME
                  {test_dev1, types::BidirectionalPair<types::AudioContexts>(
                                      types::LeAudioContextType::CONVERSATIONAL |
                                              types::LeAudioContextType::GAME,
                                      types::LeAudioContextType::CONVERSATIONAL |
                                              types::LeAudioContextType::GAME)},
                  // ..and allow the other device to stream only MEDIA ...just because we can.
                  {test_dev2, types::BidirectionalPair<types::AudioContexts>(
                                      types::AudioContexts(types::LeAudioContextType::MEDIA),
                                      types::AudioContexts(types::LeAudioContextType::MEDIA))},
          };
  ON_CALL(pac_callbacks_, OnGetAvailableAudioContexts(_))
          .WillByDefault([&](RawAddress pseudo_addr) {
            return audio_context_availability_by_device.at(pseudo_addr);
          });
  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([&, this](uint16_t conn_id, uint32_t /*trans_id*/, tGATT_STATUS status,
                                   tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view =
                    pacs::AudioContextsCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());

            // Make sure the right value for each device is provided
            bool verified = false;
            for (auto const& [addr, conn] : conn_id_by_address_) {
              if (conn == conn_id) {
                ASSERT_EQ(char_view.GetSinkContexts(),
                          audio_context_availability_by_device.at(addr).sink.value());
                ASSERT_EQ(char_view.GetSourceContexts(),
                          audio_context_availability_by_device.at(addr).source.value());
                verified = true;
                break;
              }
            }
            ASSERT_TRUE(verified);
          });

  InSequence s;
  // Check dev1
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(pac_callbacks_, OnGetAvailableAudioContexts(test_dev1)).Times(1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev1, uuid::kAvailableAudioContextsCharacteristicUuid);

  // Check dev2
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  EXPECT_CALL(pac_callbacks_, OnGetAvailableAudioContexts(test_dev2)).Times(1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev2, uuid::kAvailableAudioContextsCharacteristicUuid);
}

TEST_F(PacsTests, VerifyCccValues) {
  auto test_dev1 = GetTestAddress(0x10);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);

  auto test_dev2 = GetTestAddress(0x20);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);

  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                            tGATTS_RSP* /*p_msg*/) { ASSERT_EQ(GATT_SUCCESS, status); });

  // 1) Check dev1 write results
  const uint16_t expected_cccd_idx0 = GATT_CLT_CONFIG_NOTIFICATION;
  const uint16_t expected_cccd_idx1 = GATT_CLT_CONFIG_INDICATION;
  {
    InSequence s;
    InjectCccDescriptorWriteRequest(test_dev1,
                                    uuid::kSourcePublishedAudioCapabilityCharacteristicUuid,
                                    expected_cccd_idx0, 0);
    InjectCccDescriptorWriteRequest(test_dev1,
                                    uuid::kSourcePublishedAudioCapabilityCharacteristicUuid,
                                    expected_cccd_idx1, 1);
    Mock::VerifyAndClearExpectations(&gatt_server_interface_);
  }

  // 2) Check dev1 has individual CCCD values for all of the same kind characteristics
  {
    uint16_t expected_cccd = 0x0F0F;
    EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
            .WillRepeatedly([&](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                tGATTS_RSP* p_msg) {
              ASSERT_EQ(GATT_SUCCESS, status);

              uint16_t cccd = 0x00FF;
              ASSERT_EQ(sizeof(cccd), p_msg->attr_value.len);
              auto* pp = p_msg->attr_value.value;
              STREAM_TO_UINT16(cccd, pp);

              ASSERT_EQ(expected_cccd, cccd);
            });

    InSequence s2;
    expected_cccd = expected_cccd_idx0;
    InjectCCCDescriptorReadRequest(test_dev1,
                                   uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, 0);
    expected_cccd = expected_cccd_idx1;
    InjectCCCDescriptorReadRequest(test_dev1,
                                   uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, 1);
    Mock::VerifyAndClearExpectations(&gatt_server_interface_);
  }

  // 3) Check dev2 has not registered for CCC
  {
    uint16_t expected_cccd = 0x0F0F;
    EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
            .WillRepeatedly([&](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                                tGATTS_RSP* p_msg) {
              ASSERT_EQ(GATT_SUCCESS, status);

              uint16_t cccd = 0x00FF;
              ASSERT_EQ(sizeof(cccd), p_msg->attr_value.len);
              auto* pp = p_msg->attr_value.value;
              STREAM_TO_UINT16(cccd, pp);

              ASSERT_EQ(expected_cccd, cccd);
            });
    InSequence s3;
    expected_cccd = 0x0000;
    InjectCCCDescriptorReadRequest(test_dev2,
                                   uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, 0);
    InjectCCCDescriptorReadRequest(test_dev2,
                                   uuid::kSourcePublishedAudioCapabilityCharacteristicUuid, 1);
  }
}

TEST_F(PacsTests, UpdateContextAvailability) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);
  auto test_dev3 = GetTestAddress(0x30);

  // Make sure we provide individual device context availability when the GATT service asks
  std::map<RawAddress, types::BidirectionalPair<types::AudioContexts>>
          audio_context_availability_by_device = {
                  // Allow one device to stream CONVERSATIONAL and GAME
                  {test_dev1, types::BidirectionalPair<types::AudioContexts>(
                                      types::LeAudioContextType::CONVERSATIONAL |
                                              types::LeAudioContextType::GAME,
                                      types::LeAudioContextType::CONVERSATIONAL |
                                              types::LeAudioContextType::GAME)},
                  // ..and allow the other device to stream only MEDIA ...just because we can,
                  {test_dev2, types::BidirectionalPair<types::AudioContexts>(
                                      types::AudioContexts(types::LeAudioContextType::MEDIA),
                                      types::AudioContexts(types::LeAudioContextType::MEDIA))},
                  // ..and ALERTS for the third device.
                  {test_dev3, types::BidirectionalPair<types::AudioContexts>(
                                      types::AudioContexts(types::LeAudioContextType::ALERTS),
                                      types::AudioContexts(types::LeAudioContextType::ALERTS))},
          };
  ON_CALL(pac_callbacks_, OnGetAvailableAudioContexts(_))
          .WillByDefault([&](RawAddress pseudo_addr) {
            return audio_context_availability_by_device.at(pseudo_addr);
          });
  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([&, this](uint16_t conn_id, uint32_t /*trans_id*/, tGATT_STATUS status,
                                   tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view =
                    pacs::AudioContextsCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());

            // Make sure the right value for each device is provided
            bool verified = false;
            for (auto const& [addr, conn] : conn_id_by_address_) {
              if (conn == conn_id) {
                ASSERT_EQ(char_view.GetSinkContexts(),
                          audio_context_availability_by_device.at(addr).sink.value());
                ASSERT_EQ(char_view.GetSourceContexts(),
                          audio_context_availability_by_device.at(addr).source.value());
                verified = true;
                break;
              }
            }
            ASSERT_TRUE(verified);
          });

  InSequence s;
  // Check dev1
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(pac_callbacks_, OnGetAvailableAudioContexts(test_dev1)).Times(1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev1, uuid::kAvailableAudioContextsCharacteristicUuid);

  // Check dev2
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  EXPECT_CALL(pac_callbacks_, OnGetAvailableAudioContexts(test_dev2)).Times(1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev2, uuid::kAvailableAudioContextsCharacteristicUuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Check dev3
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev3));
  InjectGattConnectedEvent(test_dev3);
  EXPECT_CALL(pac_callbacks_, OnGetAvailableAudioContexts(test_dev3)).Times(1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  InjectCharacteristicReadRequest(test_dev3, uuid::kAvailableAudioContextsCharacteristicUuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // After reading, check the notifications
  {
    ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
            .WillByDefault([](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                              tGATTS_RSP* /*p_msg*/) { ASSERT_EQ(GATT_SUCCESS, status); });
    InSequence s2;
    // Write the CCC descriptors to receive the notifications
    // Note: test_dev3 is not subscribed to notifications
    InjectCccDescriptorWriteRequest(test_dev1, uuid::kAvailableAudioContextsCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);
    InjectCccDescriptorWriteRequest(test_dev2, uuid::kAvailableAudioContextsCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);

    // Check dev1 - expect no update when the same value is set
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
    pacs_->UpdateAvailableAudioContexts(test_dev1,
                                        audio_context_availability_by_device.at(test_dev1));
    // After that, send a different value and expect the notification
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(1);
    pacs_->UpdateAvailableAudioContexts(test_dev1,
                                        audio_context_availability_by_device.at(test_dev2));

    // Check dev2 - expect no update when the same value is set
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
    pacs_->UpdateAvailableAudioContexts(test_dev2,
                                        audio_context_availability_by_device.at(test_dev2));
    // After that, send a different value and expect the notification
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(1);
    pacs_->UpdateAvailableAudioContexts(test_dev2,
                                        audio_context_availability_by_device.at(test_dev1));

    // Check dev3 (not subscribed to notifications) - expect no update when any value is set
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
    pacs_->UpdateAvailableAudioContexts(test_dev3,
                                        audio_context_availability_by_device.at(test_dev3));
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
    pacs_->UpdateAvailableAudioContexts(test_dev3,
                                        audio_context_availability_by_device.at(test_dev2));
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
    pacs_->UpdateAvailableAudioContexts(test_dev3,
                                        audio_context_availability_by_device.at(test_dev1));
  }
}

TEST_F(PacsTests, UnsubscribeFromNotifications) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // First, subscribe to notifications
  InjectCccDescriptorWriteRequest(test_dev1, uuid::kAvailableAudioContextsCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION);

  // Update should trigger a notification
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(1);
  pacs_->UpdateAvailableAudioContexts(
          test_dev1, types::BidirectionalPair<types::AudioContexts>(
                             types::AudioContexts(types::LeAudioContextType::MEDIA),
                             types::AudioContexts(types::LeAudioContextType::MEDIA)));
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Now, unsubscribe
  InjectCccDescriptorWriteRequest(test_dev1, uuid::kAvailableAudioContextsCharacteristicUuid,
                                  GATT_CLT_CONFIG_NONE);

  // Update should NOT trigger a notification
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  pacs_->UpdateAvailableAudioContexts(
          test_dev1, types::BidirectionalPair<types::AudioContexts>(
                             types::AudioContexts(types::LeAudioContextType::GAME),
                             types::AudioContexts(types::LeAudioContextType::GAME)));
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);
}

TEST_F(PacsTests, UpdatePacSetWithEmptyRecords) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Subscribe dev1 to sink PAC notifications (index 0)
  InjectCccDescriptorWriteRequest(test_dev1, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION, 0);

  // Prepare empty PAC records
  std::vector<Pacs::PacRecord> empty_records;

  // Expect one notification for dev1
  std::vector<uint8_t> sent_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _))
          .WillOnce(SaveArg<2>(&sent_value));

  // Update sink PAC set with ID 0.
  pacs_->UpdatePacSet(0, empty_records);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Verify the content of the notification
  // It should contain a single byte: the number of records, which is 0.
  ASSERT_EQ(sent_value.size(), 1u);
  ASSERT_EQ(sent_value[0], 0);
}

TEST_F(PacsTests, RemoteReadPacsWithEmptyRecords) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Prepare empty PAC records and update a PAC set
  std::vector<Pacs::PacRecord> empty_records;
  pacs_->UpdatePacSet(0, empty_records);

  // Now, read the characteristic
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                       tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);
            // It should contain a single byte: the number of records, which is 0.
            ASSERT_EQ(p_msg->attr_value.len, 1);
            ASSERT_EQ(p_msg->attr_value.value[0], 0);
          });

  InjectCharacteristicReadRequest(test_dev1, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  0);
}

TEST_F(PacsTests, UpdateAudioChannelLocations) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);
  auto test_dev3 = GetTestAddress(0x30);

  std::map<RawAddress, types::BidirectionalPair<types::AudioLocations>>
          expected_audio_locations_by_device = {
                  {test_dev1, svc_desc_.audio_locations},
                  {test_dev2, svc_desc_.audio_locations},
                  {test_dev3, svc_desc_.audio_locations},
          };
  ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillByDefault([&, this](uint16_t conn_id, uint32_t /*trans_id*/, tGATT_STATUS status,
                                   tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);

            auto value = std::make_shared<std::vector<uint8_t>>(
                    p_msg->attr_value.value, p_msg->attr_value.value + p_msg->attr_value.len);
            auto char_view =
                    pacs::AudioLocationsCharValueView::Create(packet::PacketView<true>(value));
            ASSERT_TRUE(char_view.IsValid());

            // Make sure the right value for each device is provided
            bool verified = false;
            for (auto const& [addr, conn] : conn_id_by_address_) {
              if (conn == conn_id) {
                if (char_view.GetAudioLocations() ==
                    expected_audio_locations_by_device.at(addr).sink) {
                  verified = true;
                } else if (char_view.GetAudioLocations() ==
                           expected_audio_locations_by_device.at(addr).source) {
                  verified = true;
                }
                break;
              }
            }
            ASSERT_TRUE(verified);
          });

  InSequence s;
  // Check dev1 - will know the previous value and must be notified
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(2);
  InjectCharacteristicReadRequest(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid);
  InjectCharacteristicReadRequest(test_dev1, uuid::kSourceAudioLocationCharacteristicUuid);

  // Check dev2 - is not subscribed for notifications thus will not be notified
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(2);
  InjectCharacteristicReadRequest(test_dev2, uuid::kSinkAudioLocationCharacteristicUuid);
  InjectCharacteristicReadRequest(test_dev2, uuid::kSourceAudioLocationCharacteristicUuid);

  // Check dev3 - will know the previous value and must be notified
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev3));
  InjectGattConnectedEvent(test_dev3);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(2);
  InjectCharacteristicReadRequest(test_dev3, uuid::kSinkAudioLocationCharacteristicUuid);
  InjectCharacteristicReadRequest(test_dev3, uuid::kSourceAudioLocationCharacteristicUuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // After reading, check the notifications
  {
    ON_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
            .WillByDefault([](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                              tGATTS_RSP* /*p_msg*/) { ASSERT_EQ(GATT_SUCCESS, status); });
    InSequence s2;
    // Write the CCC descriptors to receive the notifications
    // Note: test_dev2 is not subscribed to notifications
    InjectCccDescriptorWriteRequest(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);
    InjectCccDescriptorWriteRequest(test_dev1, uuid::kSourceAudioLocationCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);
    InjectCccDescriptorWriteRequest(test_dev3, uuid::kSinkAudioLocationCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);
    InjectCccDescriptorWriteRequest(test_dev3, uuid::kSourceAudioLocationCharacteristicUuid,
                                    GATT_CLT_CONFIG_NOTIFICATION);

    // Check dev1 - expect no update when the same value is set
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
    pacs_->UpdateAudioChannelLocations(svc_desc_.audio_locations);

    // After that, send a different values - expect only 2 devices notified with 2 char values each
    EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(4);

    const types::BidirectionalPair<types::AudioLocations> new_locations = {
            .sink = codec_spec_conf::kLeAudioLocationBottomFrontCenter,
            .source = codec_spec_conf::kLeAudioLocationLowFreqEffects2};
    pacs_->UpdateAudioChannelLocations(new_locations);
  }
}

TEST_F(PacsTests, UpdatePacSet) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);

  // Connect devices
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  EXPECT_CALL(pac_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);

  // Subscribe dev1 to sink PAC notifications (index 0)
  InjectCccDescriptorWriteRequest(test_dev1, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                                  GATT_CLT_CONFIG_NOTIFICATION, 0);

  // Prepare new PAC records
  std::vector<Pacs::PacRecord> new_records = {
          Pacs::PacRecord(types::LeAudioCodecId(0x06, 0x0000, 0x0000), {0x01, 0x02, 0x03},
                          {/*empty metadata*/}),
  };

  // Expect one notification for dev1
  std::vector<uint8_t> sent_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _))
          .WillOnce(DoAll(SaveArg<2>(&sent_value),
                          [this, test_dev1](uint16_t conn_id, uint16_t handle,
                                            const std::vector<uint8_t>& /*value*/, bool indicated) {
                            ASSERT_EQ(conn_id, conn_id_by_address_.at(test_dev1));

                            // Find the handle for the first sink PAC
                            uint16_t expected_handle = 0;
                            for (const auto& el : service_db_) {
                              if (el.uuid ==
                                  uuid::kSinkPublishedAudioCapabilityCharacteristicUuid) {
                                expected_handle = el.attribute_handle;
                                break;
                              }
                            }
                            ASSERT_NE(expected_handle, 0);
                            ASSERT_EQ(handle, expected_handle);
                            ASSERT_FALSE(indicated);
                          }));

  // Update sink PAC set with ID 0. This corresponds to the first sink PAC set.
  pacs_->UpdatePacSet(0, new_records);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Verify the content of the notification
  std::vector<pacs::PacRecord> pac_gatt_value;
  for (auto const& record : new_records) {
    pac_gatt_value.push_back(pacs::PacRecord(
            pacs::CodecId(record.codec_id.coding_format, record.codec_id.vendor_company_id,
                          record.codec_id.vendor_codec_id),
            record.codec_spec_caps, record.metadata));
  }
  auto expected_pac_value = pacs::PacCharValueBuilder::Create(pac_gatt_value)->SerializeToBytes();
  ASSERT_EQ(sent_value, expected_pac_value);

  // Now test with source PAC set
  // Subscribe dev1 to source PAC notifications (index 0)
  InjectCccDescriptorWriteRequest(test_dev1,
                                  uuid::kSourcePublishedAudioCapabilityCharacteristicUuid,
                                  GATT_CLT_CONFIG_INDICATION, 0);

  // Expect one indication for dev1
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(1);

  // Update source PAC set with ID 2. This corresponds to the first source PAC set.
  pacs_->UpdatePacSet(2, new_records);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Test with non-existent PAC set ID
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  pacs_->UpdatePacSet(99, new_records);
}

TEST_F(PacsRegistrationFailureTests, AddServiceFailsDeathTest) {
  // Mock GATT service registration failure
  ON_CALL(gatt_server_interface_, AddService(server_if_, _, _))
          .WillByDefault([](tGATT_IF server_if, std::vector<btgatt_db_element_t> /* service */,
                            BTA_GATTS_AddServiceCb cb) {
            std::move(cb).Run(tGATT_STATUS::GATT_ERROR, server_if, {});
          });

  // Register GATT service instance providing the service descriptor
  EXPECT_CALL(pac_callbacks_, OnPacsRegistered()).Times(0);
  auto svc_desc = PacsTests::ProvidePacsDescriptor();
  ASSERT_DEATH(pacs_->RegisterGattService(svc_desc, &pac_callbacks_), "Unable to add GATT service");
}

class PacsCustomDescriptorTests : public PacsTestsBase {
protected:
  tBTA_GATTS_CBACK* p_gatt_event_source_cb_ = nullptr;
  tGATT_IF server_if_ = 0xDE;

  void SetUp() override {
    PacsTestsBase::SetUp();

    // Mock GATT application registration success
    EXPECT_CALL(gatt_server_interface_,
                AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
            .WillRepeatedly(DoAll(SaveArg<1>(&p_gatt_event_source_cb_),
                                  [this](const bluetooth::Uuid&, tBTA_GATTS_CBACK* p_cback, bool) {
                                    tBTA_GATTS p_data;
                                    p_data.reg_oper.status = tGATT_STATUS::GATT_SUCCESS;
                                    p_data.reg_oper.server_if = server_if_;
                                    p_cback(BTA_GATTS_REG_EVT, &p_data);
                                  }));
  }
};

TEST_F(PacsCustomDescriptorTests, RegisterGattServiceWithSourcePacsOnly) {
  // Create a service descriptor with only Source PAC sets
  Pacs::ServiceDescriptor service_descriptor = PacsTests::ProvidePacsDescriptor();
  service_descriptor.pac_sets.sink.clear();

  // Mock GATT service registration success
  EXPECT_CALL(gatt_server_interface_, AddService(server_if_, _, _))
          .WillOnce([](tGATT_IF, std::vector<btgatt_db_element_t> service,
                       BTA_GATTS_AddServiceCb cb) {
            // Verify that no Sink PAC characteristics were added, but Source are there
            bool source_pac_found = false;
            bool source_loc_found = false;
            for (const auto& el : service) {
              ASSERT_NE(el.uuid, uuid::kSinkPublishedAudioCapabilityCharacteristicUuid);
              ASSERT_NE(el.uuid, uuid::kSinkAudioLocationCharacteristicUuid);
              if (el.uuid == uuid::kSourcePublishedAudioCapabilityCharacteristicUuid) {
                source_pac_found = true;
              }
              if (el.uuid == uuid::kSourceAudioLocationCharacteristicUuid) {
                source_loc_found = true;
              }
            }
            ASSERT_TRUE(source_pac_found);
            ASSERT_TRUE(source_loc_found);

            // Assign some dummy handles
            uint16_t handle_idx = 0x2000;
            for (auto& el : service) {
              el.attribute_handle = handle_idx++;
            }
            std::move(cb).Run(tGATT_STATUS::GATT_SUCCESS, 0xDE, service);
          });

  // Register GATT service instance
  EXPECT_CALL(pac_callbacks_, OnPacsRegistered());
  pacs_->RegisterGattService(service_descriptor, &pac_callbacks_);
  ASSERT_NE(nullptr, p_gatt_event_source_cb_);
}

TEST_F(PacsCustomDescriptorTests, RegisterGattServiceWithSinkPacsOnly) {
  // Create a service descriptor with only Sink PAC sets
  Pacs::ServiceDescriptor service_descriptor = PacsTests::ProvidePacsDescriptor();
  service_descriptor.pac_sets.source.clear();

  // Mock GATT service registration success
  EXPECT_CALL(gatt_server_interface_, AddService(server_if_, _, _))
          .WillOnce([](tGATT_IF, std::vector<btgatt_db_element_t> service,
                       BTA_GATTS_AddServiceCb cb) {
            // Verify that no Source PAC characteristics were added, but Sink are there
            bool sink_pac_found = false;
            bool sink_loc_found = false;
            for (const auto& el : service) {
              ASSERT_NE(el.uuid, uuid::kSourcePublishedAudioCapabilityCharacteristicUuid);
              ASSERT_NE(el.uuid, uuid::kSourceAudioLocationCharacteristicUuid);
              if (el.uuid == uuid::kSinkPublishedAudioCapabilityCharacteristicUuid) {
                sink_pac_found = true;
              }
              if (el.uuid == uuid::kSinkAudioLocationCharacteristicUuid) {
                sink_loc_found = true;
              }
            }
            ASSERT_TRUE(sink_pac_found);
            ASSERT_TRUE(sink_loc_found);

            // Assign some dummy handles
            uint16_t handle_idx = 0x2000;
            for (auto& el : service) {
              el.attribute_handle = handle_idx++;
            }
            std::move(cb).Run(tGATT_STATUS::GATT_SUCCESS, 0xDE, service);
          });

  // Register GATT service instance
  EXPECT_CALL(pac_callbacks_, OnPacsRegistered());
  pacs_->RegisterGattService(service_descriptor, &pac_callbacks_);
  ASSERT_NE(nullptr, p_gatt_event_source_cb_);
}

TEST_F(PacsCustomDescriptorTests, RegisterGattServiceWithNoPacsShouldFail) {
  // Clear expectations from SetUp to set a new one
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Create a service descriptor with no PAC sets
  Pacs::ServiceDescriptor service_descriptor = PacsTests::ProvidePacsDescriptor();
  service_descriptor.pac_sets.sink.clear();
  service_descriptor.pac_sets.source.clear();

  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, _, _))
          .Times(0);
  EXPECT_CALL(pac_callbacks_, OnPacsRegistered()).Times(0);

  pacs_->RegisterGattService(service_descriptor, &pac_callbacks_);
}

TEST_F(PacsTestsBase, RegisterGattServiceWithDuplicatePacId) {
  // Prepare a service descriptor with duplicate PAC IDs
  Pacs::ServiceDescriptor service_descriptor = PacsTests::ProvidePacsDescriptor();
  ASSERT_FALSE(service_descriptor.pac_sets.sink.empty());
  // Create a duplicate
  service_descriptor.pac_sets.sink.push_back(service_descriptor.pac_sets.sink.front());

  // Expect that AppRegister is never called because of the validation failure
  EXPECT_CALL(gatt_server_interface_, AppRegister(_, _, _)).Times(0);
  EXPECT_CALL(pac_callbacks_, OnPacsRegistered()).Times(0);

  pacs_->RegisterGattService(service_descriptor, &pac_callbacks_);
}

TEST_F(PacsTests, RemoteReadWithValidOffset) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Expect a response with GATT_SUCCESS
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                       tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);
            // The full value is 4 bytes: 0x03, 0x00, 0x00, 0x00
            // With an offset of 1, we expect 3 bytes.
            ASSERT_EQ(p_msg->attr_value.len, 3);
            ASSERT_EQ(p_msg->attr_value.offset, 1);
            // The remaining bytes should be 0.
            ASSERT_EQ(p_msg->attr_value.value[0], 0x00);
            ASSERT_EQ(p_msg->attr_value.value[1], 0x00);
            ASSERT_EQ(p_msg->attr_value.value[2], 0x00);
          });

  // Inject a read request with an offset of 1
  InjectCharacteristicReadRequestWithOffset(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                            1);
}

TEST_F(PacsTests, RemoteReadWithOffsetEqualToLength) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Expect a response with GATT_SUCCESS and 0 length
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([](uint16_t /*conn_id*/, uint32_t /*trans_id*/, tGATT_STATUS status,
                       tGATTS_RSP* p_msg) {
            ASSERT_EQ(GATT_SUCCESS, status);
            // The full value is 4 bytes. With an offset of 4, we expect 0 bytes.
            ASSERT_EQ(p_msg->attr_value.len, 0);
            ASSERT_EQ(p_msg->attr_value.offset, 4);
          });

  // Inject a read request with an offset of 4, which is equal to the value length.
  InjectCharacteristicReadRequestWithOffset(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                            4);
}

TEST_F(PacsTests, RemoteReadWithInvalidOffset) {
  auto test_dev1 = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev1);

  // Expect a response with GATT_INVALID_OFFSET
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_INVALID_OFFSET, _));

  // The full value is 4 bytes. An offset of 5 is invalid.
  InjectCharacteristicReadRequestWithOffset(test_dev1, uuid::kSinkAudioLocationCharacteristicUuid,
                                            5);
}

TEST_F(PacsTests, VerifyPersistentStorage) {
  GTEST_SKIP() << "TODO: Persistent storage is not yet implemented";
}

}  // namespace bluetooth::le_audio::test
