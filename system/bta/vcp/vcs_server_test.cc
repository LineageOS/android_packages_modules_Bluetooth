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

#include "bta/vcp/vcs_server.h"

#include <android/log.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/mock/bta_gatt_api_mock.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_client_interface.h"

using ::testing::_;
using ::testing::DoAll;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SaveArg;

namespace bluetooth::vcs {

static RawAddress GetTestAddress(uint8_t index) {
  EXPECT_LT(index, UINT8_MAX);
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
  return RawAddress(bytes);
}

class MockVcsCallbacks : public VcsServer::Callbacks {
public:
  // clang-format off
  MOCK_METHOD((void), OnVcsServerRegistered, (), (override));
  MOCK_METHOD((void), OnDeviceConnected, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnDeviceDisconnected, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnVolumeStateChangeRequest,
              (const RawAddress& pseudo_addr, uint8_t volume, MuteState mute_state),
              (override));
  // clang-format on
};

class VcsTestBase : public ::testing::Test {
public:
  std::shared_ptr<VcsServer> vcs_;

  NiceMock<gatt::MockBtaGattServerInterface> gatt_server_interface_;
  MockVcsCallbacks vcs_callbacks_;

  void SetUp(void) override {
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
    vcs_ = InstantiateVcsServer();
  }

  void TearDown(void) override { ReleaseVcsServer(std::move(vcs_)); }
};

TEST_F(VcsTestBase, InstantiateRelease) {
  auto const old_ptr = vcs_.get();
  ASSERT_NE(old_ptr, nullptr);

  // Make sure destructing not-registered client has no side effect
  EXPECT_CALL(gatt_server_interface_, AppDeregister(_)).Times(0);
  ReleaseVcsServer(std::move(vcs_));
  ASSERT_EQ(vcs_.get(), nullptr);

  // Reinstantiate
  vcs_ = InstantiateVcsServer();
  ASSERT_NE(vcs_.get(), nullptr);

  // New instantiating should return the same object
  auto const vcs2 = InstantiateVcsServer();
  ASSERT_EQ(vcs2.get(), vcs_.get());
}

TEST_F(VcsTestBase, RegisterGattService) {
  const stack::tGATT_CBACK* p_gatt_event_source_cb = nullptr;
  bluetooth::Uuid uuid;

  // Check GATT server app registration
  VcsServer::ServiceDescriptor service_descriptor{
          .step_size = 1,
          .initial_volume = 0,
          .initial_mute_state = MuteState::kNotMuted,
          .initial_volume_setting_persisted = VolumeSettingPersisted::kResetVolumeSetting};
  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kVolumeControlServiceUuid, _, false))
          .WillOnce(DoAll(SaveArg<0>(&uuid), testing::SaveArg<1>(&p_gatt_event_source_cb),
                          Return(0xDE)));
  EXPECT_CALL(gatt_server_interface_, AddService(_, _)).WillOnce(Return(GATT_SERVICE_STARTED));
  vcs_->RegisterGattService(service_descriptor, &vcs_callbacks_);
  ASSERT_NE(nullptr, p_gatt_event_source_cb);
  ASSERT_EQ(uuid::kVolumeControlServiceUuid, uuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Ignore second call to register
  EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kVolumeControlServiceUuid, _, false))
          .Times(0);
  vcs_->RegisterGattService(service_descriptor, &vcs_callbacks_);

  // Make sure destructing unregisters the server interface
  EXPECT_CALL(gatt_server_interface_, AppDeregister(0xDE));
}

class VcsTest : public VcsTestBase {
public:
  const stack::tGATT_CBACK* p_gatt_event_source_cb_ = nullptr;
  std::vector<btgatt_db_element_t> service_db_;
  tGATT_IF server_if_;

  uint32_t gatt_trans_id_ = 0x00000001;
  VcsServer::ServiceDescriptor svc_desc_{
          .step_size = 10,
          .initial_volume = 123,
          .initial_mute_state = MuteState::kNotMuted,
          .initial_volume_setting_persisted = VolumeSettingPersisted::kResetVolumeSetting};

  std::map<RawAddress, tCONN_ID> conn_id_by_address_;

  void SetUp(void) override {
    VcsTestBase::SetUp();

    // Mock GATT application registration success
    EXPECT_CALL(gatt_server_interface_, AppRegister(uuid::kVolumeControlServiceUuid, _, false))
            .WillRepeatedly(DoAll(SaveArg<1>(&p_gatt_event_source_cb_), Return(0xDE)));

    // Mock GATT service registration success
    EXPECT_CALL(gatt_server_interface_, AddService(0xDE, _))
            .WillOnce(DoAll(
                    SaveArg<0>(&server_if_),
                    [this](tGATT_IF /*server_if*/, std::vector<btgatt_db_element_t>* service) {
                      // Assign some ATT handles
                      uint16_t handle_idx = 0x2000;
                      for (auto& el : *service) {
                        el.attribute_handle = handle_idx++;
                      }
                      service_db_ = *service;  // Store for using it by mock GATT layer
                      return service->empty() ? tGATT_STATUS::GATT_ERROR
                                              : tGATT_STATUS::GATT_SERVICE_STARTED;
                    }));

    // Register GATT service instance
    EXPECT_CALL(vcs_callbacks_, OnVcsServerRegistered());
    vcs_->RegisterGattService(svc_desc_, &vcs_callbacks_);

    ASSERT_NE(nullptr, p_gatt_event_source_cb_);
  }

  void TearDown(void) override { VcsTestBase::TearDown(); }

  void InjectGattConnectedEvent(const RawAddress& pseudo_addr) {
    static tCONN_ID conn_id = 0x01;

    if (conn_id_by_address_.count(pseudo_addr) == 0) {
      conn_id_by_address_[pseudo_addr] = conn_id;

      p_gatt_event_source_cb_->p_conn_cb(server_if_, pseudo_addr, conn_id++, true, GATT_CONN_OK,
                                         BT_TRANSPORT_LE);
    }
  }

  void InjectGattDisconnectedEvent(const RawAddress& address) {
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

  void InjectCharacteristicReadRequest(const RawAddress& address, const Uuid& char_uuid) {
    if (conn_id_by_address_.count(address) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    for (auto const& el : service_db_) {
      if (el.uuid == char_uuid && el.type == BTGATT_DB_CHARACTERISTIC) {
        handle = el.attribute_handle;
        break;
      }
    }

    auto conn_id = conn_id_by_address_.at(address);
    if (conn_id != GATT_INVALID_CONN_ID) {
      p_gatt_event_source_cb_->p_req_cb->read_characteristic_cb(conn_id, gatt_trans_id_++, address,
                                                                handle, 0, false);
    }
  }

  void InjectCharacteristicWriteRequest(const RawAddress& address, const Uuid& char_uuid,
                                        const std::vector<uint8_t>& value,
                                        bool with_response = true) {
    if (conn_id_by_address_.count(address) == 0) {
      GTEST_FAIL();
    }

    // Find char value handle
    uint16_t handle = 0x0000;
    for (auto const& el : service_db_) {
      if (el.uuid == char_uuid && el.type == BTGATT_DB_CHARACTERISTIC) {
        handle = el.attribute_handle;
        break;
      }
    }

    auto conn_id = conn_id_by_address_.at(address);
    if (conn_id != GATT_INVALID_CONN_ID) {
      p_gatt_event_source_cb_->p_req_cb->write_characteristic_cb(
              conn_id, gatt_trans_id_++, address, handle, 0, with_response, false,
              (uint8_t*)value.data(), value.size());
    }
  }

  void InjectCccDescriptorWriteRequest(const RawAddress& address, const Uuid& char_uuid,
                                       uint16_t cccd_value) {
    if (conn_id_by_address_.count(address) == 0) {
      GTEST_FAIL();
    }

    // First - find the char value attribute
    uint16_t handle = 0x0000;
    for (auto el = service_db_.begin(); el != service_db_.end(); ++el) {
      if (el->uuid != char_uuid) {
        continue;
      }

      // Next- look further for the first CCCD uuid to get the cccd handle
      while (el != service_db_.end()) {
        if (el->uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG)) {
          break;
        }
        ++el;
      }
      if (el != service_db_.end()) {
        handle = el->attribute_handle;
      }
      break;
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
};

TEST_F(VcsTest, ConnectDisconnect) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);

  EXPECT_CALL(vcs_callbacks_, OnDeviceConnected(test_dev1));
  InjectGattConnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);

  EXPECT_CALL(vcs_callbacks_, OnDeviceConnected(test_dev2));
  InjectGattConnectedEvent(test_dev2);
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);

  EXPECT_CALL(vcs_callbacks_, OnDeviceDisconnected(test_dev1));
  InjectGattDisconnectedEvent(test_dev1);
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);

  EXPECT_CALL(vcs_callbacks_, OnDeviceDisconnected(test_dev2));
  InjectGattDisconnectedEvent(test_dev2);
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_RelativeVolumeDown) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Initial state: volume 123, not muted, counter 0

  // Client writes Relative Volume Down
  // Expected: volume = 123 - 10 = 113, mute = kNotMuted, counter = 1
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev, svc_desc_.initial_volume - svc_desc_.step_size,
                                         MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeRelativeVolumeDown, 0x00});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  vcs_->UpdateVolumeState(svc_desc_.initial_volume - svc_desc_.step_size, MuteState::kNotMuted);

  // Client writes Relative Volume Down when volume is near 0 (e.g., 5, step_size 10)
  vcs_->UpdateVolumeState(5, MuteState::kNotMuted);
  // Increment change counter to 2
  // Expected: volume = 0 (clamped), mute = kNotMuted, counter = 3
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(test_dev, 0, MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeRelativeVolumeDown, 0x02});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_RelativeVolumeUp) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Initial state: volume 123, not muted, counter 0

  // Client writes Relative Volume Up
  // Expected: volume = 123 + 10 = 133, mute = kNotMuted, counter = 1
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev, svc_desc_.initial_volume + svc_desc_.step_size,
                                         MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeRelativeVolumeUp, 0x00});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  vcs_->UpdateVolumeState(svc_desc_.initial_volume + svc_desc_.step_size, MuteState::kNotMuted);

  // Client writes Relative Volume Up when volume is near 255 (e.g., 250, step_size 10)
  vcs_->UpdateVolumeState(250, MuteState::kNotMuted);
  // Increment change counter to 2
  // Expected: volume = 255 (clamped), mute = kNotMuted, counter = 3
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(test_dev, 255, MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeRelativeVolumeUp, 0x02});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_UnmuteRelativeVolumeDown) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Set initial state to 100, Muted
  vcs_->UpdateVolumeState(svc_desc_.initial_volume, MuteState::kMuted);
  // Increment change counter to 1 (from initial state change)

  // Client writes Unmute/Relative Volume Down
  // Expected: volume = 123 - 10 = 113, mute = kNotMuted, counter = 2
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev, svc_desc_.initial_volume - svc_desc_.step_size,
                                         MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeUnmuteRelativeVolumeDown, 0x01});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Client writes Unmute/Relative Volume Down when volume is near 0 (e.g., 5, step_size 10)
  vcs_->UpdateVolumeState(5, MuteState::kMuted);
  // Increment change counter to 2
  // Expected: volume = 0 (clamped), mute = kNotMuted, counter = 3
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(test_dev, 0, MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeUnmuteRelativeVolumeDown, 0x02});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_UnmuteRelativeVolumeUp) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Set initial state to 100, Muted
  vcs_->UpdateVolumeState(svc_desc_.initial_volume, MuteState::kMuted);
  // Increment change counter to 1 (from initial state change)

  // Client writes Unmute/Relative Volume Up
  // Expected: volume = 123 + 10 = 133, mute = kNotMuted, counter = 2
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev, svc_desc_.initial_volume + svc_desc_.step_size,
                                         MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeUnmuteRelativeVolumeUp, 0x01});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Client writes Unmute/Relative Volume Up when volume is near 255 (e.g., 250, step_size 10)
  vcs_->UpdateVolumeState(250, MuteState::kMuted);
  // Increment change counter to 2
  // Expected: volume = 255 (clamped), mute = kNotMuted, counter = 3
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(test_dev, 255, MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeUnmuteRelativeVolumeUp, 0x02});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_MuteUnmuteAndAbsoluteVolume) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Client writes Mute
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev, svc_desc_.initial_volume, MuteState::kMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeMute, 0x00});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  vcs_->UpdateVolumeState(svc_desc_.initial_volume, MuteState::kMuted);

  // Client writes Unmute
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev, svc_desc_.initial_volume, MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeUnmute, 0x01});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  vcs_->UpdateVolumeState(svc_desc_.initial_volume, MuteState::kNotMuted);

  // Client writes Absolute Volume
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(test_dev, 150, MuteState::kNotMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeSetAbsoluteVolume, 0x02, 150});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_InvalidChangeCounter) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Initial state: volume 123, Not Muted, counter 0
  // Client writes Mute with incorrect change counter (e.g., 0x01 instead of 0x00)
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(_, _, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, VCS_INVALID_CHANGE_COUNTER, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeMute, 0x01});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_OpcodeNotSupported) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Client writes an unsupported opcode (e.g., 0xFF)
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(_, _, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, VCS_OPCODE_NOT_SUPPORTED, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid, {0xFF, 0x00});
}

TEST_F(VcsTest, RemoteWriteVolumeControlPoint_InvalidLength) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Client writes Set Absolute Volume with incorrect length (e.g., 2 bytes instead of 3)
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(_, _, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_INVALID_ATTR_LEN, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeSetAbsoluteVolume, 0x00});

  // Client writes Mute with incorrect length (e.g., 1 byte instead of 2)
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(_, _, _)).Times(0);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_INVALID_ATTR_LEN, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeMute});
}

TEST_F(VcsTest, RemoteReadVolumeState) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Initial state: volume 123, Not Muted, counter 0
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([this](uint16_t, uint32_t, tGATT_STATUS, std::unique_ptr<tGATTS_RSP> p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, 3);
            ASSERT_EQ(p_msg->attr_value.value[0], svc_desc_.initial_volume);
            ASSERT_EQ(p_msg->attr_value.value[1],
                      static_cast<uint8_t>(svc_desc_.initial_mute_state));
            ASSERT_EQ(p_msg->attr_value.value[2], 0);  // change counter
          });
  InjectCharacteristicReadRequest(test_dev, uuid::kVolumeStateUuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Update state to 100, Muted
  vcs_->UpdateVolumeState(100, MuteState::kMuted);
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([](uint16_t, uint32_t, tGATT_STATUS, std::unique_ptr<tGATTS_RSP> p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, 3);
            ASSERT_EQ(p_msg->attr_value.value[0], 100);
            ASSERT_EQ(p_msg->attr_value.value[1], static_cast<uint8_t>(MuteState::kMuted));
            ASSERT_EQ(p_msg->attr_value.value[2], 1);
          });
  InjectCharacteristicReadRequest(test_dev, uuid::kVolumeStateUuid);
}

TEST_F(VcsTest, RemoteReadInvalidHandle) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Pick a handle that is definitely invalid
  uint16_t invalid_handle = 0xFFFF;

  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_INVALID_HANDLE, _));

  p_gatt_event_source_cb_->p_req_cb->read_characteristic_cb(
          conn_id_by_address_.at(test_dev), gatt_trans_id_++, test_dev, invalid_handle, 0, false);
}

TEST_F(VcsTest, UpdateVolumeStateNoSubscription) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Client does NOT subscribe to notifications

  // Server updates volume state - expect NO notification
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  vcs_->UpdateVolumeState(100, MuteState::kMuted);
}

TEST_F(VcsTest, UpdateVolumeStateNotifies) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Server should send a response to the CCCD write.
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));

  // When client subscribes, it should get an immediate notification with the
  // current value.
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce([this](uint16_t /* conn_id */, uint16_t /* handle */,
                           const std::vector<uint8_t>& value,
                           bool /* indicated */) -> tGATT_STATUS {
            EXPECT_EQ(value.size(), 3u);
            EXPECT_EQ(value[0], svc_desc_.initial_volume);
            EXPECT_EQ(value[1], static_cast<uint8_t>(svc_desc_.initial_mute_state));
            EXPECT_EQ(value[2], 0);  // Initial counter
            return GATT_SUCCESS;
          });
  // Client subscribes to notifications
  InjectCccDescriptorWriteRequest(test_dev, uuid::kVolumeStateUuid, GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Server updates mute state - expect notification
  std::vector<uint8_t> notified_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce(DoAll(SaveArg<2>(&notified_value), Return(GATT_SUCCESS)));
  vcs_->UpdateVolumeState(100, MuteState::kMuted);

  ASSERT_EQ(notified_value.size(), 3u);
  ASSERT_EQ(notified_value[0], 100);
  ASSERT_EQ(notified_value[1], static_cast<uint8_t>(MuteState::kMuted));
  ASSERT_EQ(notified_value[2], 1);
}

TEST_F(VcsTest, UpdateVolumeStateSameValue) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Server should send a response to the CCCD write.
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));

  // When client subscribes, it should get an immediate notification with the
  // current value.
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce([this](uint16_t /* conn_id */, uint16_t /* handle */,
                           const std::vector<uint8_t>& value,
                           bool /* indicated */) -> tGATT_STATUS {
            EXPECT_EQ(value.size(), 3u);
            EXPECT_EQ(value[0], svc_desc_.initial_volume);
            EXPECT_EQ(value[1], static_cast<uint8_t>(svc_desc_.initial_mute_state));
            EXPECT_EQ(value[2], 0);  // Initial counter
            return GATT_SUCCESS;
          });
  // Client subscribes to notifications
  InjectCccDescriptorWriteRequest(test_dev, uuid::kVolumeStateUuid, GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // 1. Update with the same volume and mute state - expect NO notification and no change counter
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  vcs_->UpdateVolumeState(svc_desc_.initial_volume, svc_desc_.initial_mute_state);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // 2. Update with same volume, different mute state - expect notification and counter increment
  std::vector<uint8_t> notified_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce(DoAll(SaveArg<2>(&notified_value), Return(GATT_SUCCESS)));
  vcs_->UpdateVolumeState(svc_desc_.initial_volume, MuteState::kMuted);

  ASSERT_EQ(notified_value.size(), 3u);
  ASSERT_EQ(notified_value[0], svc_desc_.initial_volume);
  ASSERT_EQ(notified_value[1], static_cast<uint8_t>(MuteState::kMuted));
  ASSERT_EQ(notified_value[2], 1);  // Counter incremented
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // 3. Update with different volume, same mute state - expect notification and counter increment
  uint8_t new_volume = svc_desc_.initial_volume + 1;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce(DoAll(SaveArg<2>(&notified_value), Return(GATT_SUCCESS)));
  vcs_->UpdateVolumeState(new_volume, MuteState::kMuted);

  ASSERT_EQ(notified_value.size(), 3u);
  ASSERT_EQ(notified_value[0], new_volume);
  ASSERT_EQ(notified_value[1], static_cast<uint8_t>(MuteState::kMuted));
  ASSERT_EQ(notified_value[2], 2);  // Counter incremented again
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // 4. Read Volume State to verify final change counter is 2
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([new_volume](uint16_t, uint32_t, tGATT_STATUS,
                                 std::unique_ptr<tGATTS_RSP> p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, 3);
            ASSERT_EQ(p_msg->attr_value.value[0], new_volume);
            ASSERT_EQ(p_msg->attr_value.value[1], static_cast<uint8_t>(MuteState::kMuted));
            ASSERT_EQ(p_msg->attr_value.value[2], 2);
          });
  InjectCharacteristicReadRequest(test_dev, uuid::kVolumeStateUuid);
}

TEST_F(VcsTest, ClientWriteNotifiesOtherClients) {
  auto test_dev1 = GetTestAddress(0x10);
  auto test_dev2 = GetTestAddress(0x20);
  InjectGattConnectedEvent(test_dev1);
  InjectGattConnectedEvent(test_dev2);

  // Both clients subscribe to Volume State notifications
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(2);
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false)).Times(2);
  InjectCccDescriptorWriteRequest(test_dev1, uuid::kVolumeStateUuid, GATT_CLT_CONFIG_NOTIFICATION);
  InjectCccDescriptorWriteRequest(test_dev2, uuid::kVolumeStateUuid, GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // dev1 writes Mute (opcode 0x06, counter 0x00)
  EXPECT_CALL(vcs_callbacks_,
              OnVolumeStateChangeRequest(test_dev1, svc_desc_.initial_volume, MuteState::kMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev1, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeMute, 0x00});
  Mock::VerifyAndClearExpectations(&vcs_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Expect notification to dev1 and dev2 when system applies mute state
  EXPECT_CALL(gatt_server_interface_,
              HandleValueIndication(conn_id_by_address_.at(test_dev2), _, _, false));
  EXPECT_CALL(gatt_server_interface_,
              HandleValueIndication(conn_id_by_address_.at(test_dev1), _, _, false));
  vcs_->UpdateVolumeState(svc_desc_.initial_volume, MuteState::kMuted);
}

TEST_F(VcsTest, ChangeCounterWrapAround) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Initial state: volume 0, not muted, counter 0
  // Let's get the counter to 255. Start from initial state.
  for (int i = 1; i <= 255; ++i) {
    vcs_->UpdateVolumeState(i, MuteState::kNotMuted);
  }

  // Verify counter is 255 with a read.
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([](uint16_t, uint32_t, tGATT_STATUS, std::unique_ptr<tGATTS_RSP> p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, 3);
            ASSERT_EQ(p_msg->attr_value.value[2], 255);  // Change counter
          });
  InjectCharacteristicReadRequest(test_dev, uuid::kVolumeStateUuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Now, one more update to wrap it around to 0.
  vcs_->UpdateVolumeState(255, MuteState::kMuted);

  // Verify counter is 0 with a read.
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _))
          .WillOnce([](uint16_t, uint32_t, tGATT_STATUS, std::unique_ptr<tGATTS_RSP> p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, 3);
            ASSERT_EQ(p_msg->attr_value.value[2], 0);  // Change counter
          });
  InjectCharacteristicReadRequest(test_dev, uuid::kVolumeStateUuid);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Client writes Mute with the correct change counter (0)
  EXPECT_CALL(vcs_callbacks_, OnVolumeStateChangeRequest(test_dev, 255, MuteState::kMuted));
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));
  InjectCharacteristicWriteRequest(test_dev, uuid::kVolumeControlPointUuid,
                                   {kControlPointOpcodeMute, 0x00});
}

TEST_F(VcsTest, UpdateVolumeFlagsNotifies) {
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);

  // Server should send a response to the CCCD write.
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _));

  // When client subscribes, it should get an immediate notification with the
  // current value (default is false).
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce([](uint16_t /* conn_id */, uint16_t /* handle */,
                       const std::vector<uint8_t>& value, bool /* indicated */) -> tGATT_STATUS {
            EXPECT_EQ(value.size(), 1u);
            EXPECT_EQ(value[0], 0);  // false
            return GATT_SUCCESS;
          });
  // Client subscribes to notifications
  InjectCccDescriptorWriteRequest(test_dev, uuid::kVolumeFlagsUuid, GATT_CLT_CONFIG_NOTIFICATION);
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Server updates flags to true - expect notification
  std::vector<uint8_t> notified_value;
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, false))
          .WillOnce(DoAll(SaveArg<2>(&notified_value), Return(GATT_SUCCESS)));
  VolumeFlags flags;
  flags.bits.volume_setting_persisted = VolumeSettingPersisted::kUserSetVolumeSetting;
  vcs_->UpdateVolumeFlags(flags);

  ASSERT_EQ(notified_value.size(), 1u);
  ASSERT_EQ(notified_value[0], 1);  // true
  Mock::VerifyAndClearExpectations(&gatt_server_interface_);

  // Update with the same flags - expect NO notification
  EXPECT_CALL(gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  vcs_->UpdateVolumeFlags(flags);
}

TEST_F(VcsTest, DumpNoCrash) {
  std::stringstream stream;

  // Dump with no devices connected
  vcs_->Dump(stream);

  // Dump with a connected device
  auto test_dev = GetTestAddress(0x10);
  InjectGattConnectedEvent(test_dev);
  vcs_->Dump(stream);
}

}  // namespace bluetooth::vcs
