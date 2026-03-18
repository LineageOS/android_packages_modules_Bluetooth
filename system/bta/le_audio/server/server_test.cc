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

#include <base/test/bind_test_util.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bluetooth/log.h"
#include "bta/le_audio/audio_hal_client/mock_peripheral_audio_hal.h"
#include "bta/le_audio/common/mock_iso_app_proxy.h"
#include "bta/le_audio/test/mock_ascs.h"
#include "bta/le_audio/test/mock_ase_manager.h"
#include "bta/le_audio/test/mock_le_audio_server_config_manager.h"
#include "bta/le_audio/test/mock_pacs.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "bta_le_audio_api.h"
#include "bta_le_audio_server_api.h"
#include "hardware/bt_le_audio.h"
#include "hardware/bt_le_audio_server.h"
#include "hci/controller_mock.h"
#include "main/shim/entry.h"
#include "main/shim/le_advertising_manager.h"
#include "stack/include/btm_client_interface.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "test/mock/mock_main_shim_entry.h"

using testing::_;
using testing::DoAll;
using testing::Return;
using testing::Test;

// FAKE DEPENDENCIES
namespace bluetooth {
namespace audio {
namespace le_audio {
// This is a fake implementation to resolve linker errors in tests.
LeAudioClientInterface* LeAudioClientInterface::Get() { return nullptr; }

std::optional<bluetooth::le_audio::ProviderInfo> LeAudioClientInterface::GetCodecConfigProviderInfo(
        void) const {
  return std::nullopt;
}
}  // namespace le_audio
}  // namespace audio

namespace le_audio {
std::ostream& operator<<(std::ostream& os, const CodecManager::UnicastConfigurationRequirements&) {
  return os;
}
namespace broadcaster {
std::ostream& operator<<(std::ostream& os, const BroadcastConfiguration&) { return os; }
}  // namespace broadcaster

static bool operator==(const AseEnableRequest& a, const AseEnableRequest& b) {
  return a.ase_id == b.ase_id && a.direction == b.direction &&
         a.audio_context_type == b.audio_context_type &&
         a.codec_id.coding_format == b.codec_id.coding_format &&
         a.codec_id.vendor_company_id == b.codec_id.vendor_company_id &&
         a.codec_id.vendor_codec_id == b.codec_id.vendor_codec_id;
}

}  // namespace le_audio

namespace shim {
class MockBleAdvertiserInterface : public BleAdvertiserInterface {
public:
  MOCK_METHOD(void, RegisterAdvertiser, (IdStatusCallback cb));
  MOCK_METHOD(void, GetOwnAddress, (uint8_t advertiser_id, GetAddressCallback cb));
  MOCK_METHOD(void, SetParameters,
              (uint8_t advertiser_id, AdvertiseParameters params, ParametersCallback cb));
  MOCK_METHOD(void, SetData,
              (int advertiser_id, bool set_scan_rsp, std::vector<uint8_t> data, StatusCallback cb));
  MOCK_METHOD(void, Enable,
              (uint8_t advertiser_id, bool enable, StatusCallback cb, uint16_t duration,
               uint8_t maxExtAdvEvents, StatusCallback timeout_cb));
  MOCK_METHOD(void, Unregister, (uint8_t advertiser_id));
  MOCK_METHOD(void, StartAdvertising,
              (uint8_t advertiser_id, StatusCallback cb, AdvertiseParameters params,
               std::vector<uint8_t> advertise_data, std::vector<uint8_t> scan_response_data,
               int timeout_s, StatusCallback timeout_cb));
  MOCK_METHOD(void, StartAdvertisingSet,
              (uint8_t, int, IdTxPowerStatusCallback, AdvertiseParameters, std::vector<uint8_t>,
               std::vector<uint8_t>, PeriodicAdvertisingParameters, std::vector<uint8_t>, uint16_t,
               uint8_t, IdStatusCallback));
  MOCK_METHOD(void, SetPeriodicAdvertisingParameters,
              (int advertiser_id, PeriodicAdvertisingParameters periodic_params,
               StatusCallback cb));
  MOCK_METHOD(void, SetPeriodicAdvertisingData,
              (int advertiser_id, std::vector<uint8_t> data, StatusCallback cb));
  MOCK_METHOD(void, SetPeriodicAdvertisingEnable,
              (int advertiser_id, bool enable, bool include_adi, StatusCallback cb));
  MOCK_METHOD(void, RegisterCallbacks, (AdvertisingCallbacks * callbacks));
  MOCK_METHOD(void, RegisterCallbacksNative, (AdvertisingCallbacks * callbacks, uint8_t client_id));
};
MockBleAdvertiserInterface* mock_ble_advertiser_interface_;
BleAdvertiserInterface* get_ble_advertiser_instance() { return mock_ble_advertiser_interface_; }
}  // namespace shim

namespace le_audio {
namespace {
class MockAscsAseStateMachine : public AscsAseStateMachine {
public:
  MockAscsAseStateMachine(bool is_source_ase, uint8_t ase_id, const RawAddress& peer,
                          ServiceCallbacks* callbacks)
      : AscsAseStateMachine(is_source_ase, ase_id, peer, callbacks) {}
  MOCK_METHOD(bool, ProcessEvent, (Events event, void* p_data), (override));
};
}  // namespace
}  // namespace le_audio
}  // namespace bluetooth

namespace {
// TEST CODE
using namespace bluetooth;

class MockLeAudioServerCallbacks : public le_audio::LeAudioServerCallbacks {
public:
  MOCK_METHOD(void, OnInitialized, (), (override));
  MOCK_METHOD(void, OnConnectionStateChanged,
              (const RawAddress& address, le_audio::GattConnectionState state), (override));
  MOCK_METHOD(void, OnStreamStartRequest,
              (const RawAddress& address, const std::vector<le_audio::AseEnableRequest>& requests),
              (override));
  MOCK_METHOD(void, OnStreamStarted,
              (const RawAddress& address, uint8_t stream_id, uint32_t audio_context_type),
              (override));
  MOCK_METHOD(void, OnStreamMetadataUpdated,
              (const RawAddress& address, uint8_t stream_id, uint32_t audio_context_type),
              (override));
  MOCK_METHOD(void, OnSinkStreamReady, (const RawAddress& address), (override));
  MOCK_METHOD(void, OnSourceStreamReady, (const RawAddress& address), (override));
  MOCK_METHOD(void, OnStreamStopped, (const RawAddress& address, uint8_t stream_id), (override));
};

class LeAudioServerTest : public Test {
protected:
  void SetUp() override {
    mock_pacs_ = std::make_shared<le_audio::MockPacs>();
    mock_ascs_ = std::make_shared<le_audio::MockAscs>();

    auto sm_factory = [](bool, uint8_t, const RawAddress&,
                         le_audio::AscsAseStateMachine::ServiceCallbacks*)
            -> std::unique_ptr<le_audio::AscsAseStateMachine> {
      return std::make_unique<le_audio::MockAscsAseStateMachine>(false, 0, RawAddress(), nullptr);
    };

    auto iso_app_factory =
            [](hci::iso_manager::CigCallbacks*) -> std::unique_ptr<bluetooth::IsoAppProxy> {
      return std::make_unique<bluetooth::MockIsoAppProxy>(nullptr, nullptr, nullptr);
    };

    mock_ase_manager_ = std::make_shared<le_audio::MockAseManager>(
            mock_ascs_, base::BindLambdaForTesting(sm_factory),
            base::BindLambdaForTesting(iso_app_factory));
    mock_config_manager_ = std::make_shared<le_audio::MockLeAudioServerConfigManager>();
    shim::mock_ble_advertiser_interface_ = new shim::MockBleAdvertiserInterface();
    hci::testing::mock_controller_ =
            std::make_unique<testing::NiceMock<hci::testing::MockController>>();
    set_mock_btm_client_interface(&mock_btm_interface_);
    gatt::SetMockBtaGattServerInterface(&mock_gatt_server_if_);

    // New mock setup
    mock_audio_factory_ = new MockPeripheralAudioSessionFactory();
    mock_audio_provider_factory_ = new MockPeripheralAudioProviderFactory();
    mock_audio_out_ = new MockPeripheralAudioOut();
    mock_audio_in_ = new MockPeripheralAudioIn();

    ON_CALL(*mock_audio_provider_factory_, GetPlaybackSessionAudioProvider).WillByDefault([]() {
      auto mock_provider = std::make_unique<MockPeripheralAudioProvider>();
      ON_CALL(*mock_provider, GetProviderCapabilities)
              .WillByDefault(Return(std::vector<audio::le_audio::AudioHalCapability>()));
      return mock_provider;
    });
    ON_CALL(*mock_audio_provider_factory_, GetRecordingSessionAudioProvider).WillByDefault([]() {
      auto mock_provider = std::make_unique<MockPeripheralAudioProvider>();
      ON_CALL(*mock_provider, GetProviderCapabilities)
              .WillByDefault(Return(std::vector<audio::le_audio::AudioHalCapability>()));
      return mock_provider;
    });
    ON_CALL(*mock_audio_factory_, AcquirePlaybackSession).WillByDefault([this](auto&, auto*) {
      return std::unique_ptr<MockPeripheralAudioOut>(mock_audio_out_);
    });
    ON_CALL(*mock_audio_factory_, AcquireRecordingSession).WillByDefault([this](auto&, auto*) {
      return std::unique_ptr<MockPeripheralAudioIn>(mock_audio_in_);
    });
    ON_CALL(*mock_audio_factory_, ReleasePlaybackSession)
            .WillByDefault([this](std::unique_ptr<audio::le_audio::IPeripheralAudioOut> session) {
              session.release();
              mock_audio_out_ = nullptr;
            });
    ON_CALL(*mock_audio_factory_, ReleaseRecordingSession)
            .WillByDefault([this](std::unique_ptr<audio::le_audio::IPeripheralAudioIn> session) {
              session.release();
              mock_audio_in_ = nullptr;
            });
  }

  void Initialize() {
    auto dependencies = std::make_unique<le_audio::LeAudioServerDependencies>();
    dependencies->config_manager_factory = [this]() { return mock_config_manager_; };
    dependencies->pacs_factory = [this]() { return mock_pacs_; };
    dependencies->ascs_factory = [this]() { return mock_ascs_; };
    dependencies->ase_manager_factory = [this](auto) { return mock_ase_manager_; };
    dependencies->peripheral_audio_session_factory = [this]() { return mock_audio_factory_; };
    dependencies->peripheral_audio_provider_factory = [this]() {
      return mock_audio_provider_factory_;
    };

    ON_CALL(*hci::testing::mock_controller_, SupportsBleConnectedIsochronousStreamCentral)
            .WillByDefault(Return(true));
    ON_CALL(*hci::testing::mock_controller_, SupportsBleConnectedIsochronousStreamPeripheral)
            .WillByDefault(Return(true));

    EXPECT_CALL(*mock_pacs_, RegisterGattService(_, _));
    EXPECT_CALL(*mock_audio_provider_factory_, GetPlaybackSessionAudioProvider());
    EXPECT_CALL(*mock_audio_provider_factory_, GetRecordingSessionAudioProvider());
    EXPECT_CALL(*mock_ase_manager_, Initialize(_, _))
            .WillOnce(testing::DoAll(testing::SaveArg<0>(&asc_svc_descriptor_),
                                     testing::SaveArg<1>(&ase_manager_callbacks_)));
    EXPECT_CALL(mock_callbacks_, OnInitialized());
    ON_CALL(*mock_config_manager_, GetAscsDescriptor())
            .WillByDefault(Return(le_audio::Ascs::ServiceDescriptor()));
    ON_CALL(*mock_config_manager_, GetPacsDescriptor(_, _))
            .WillByDefault(Return(le_audio::Pacs::ServiceDescriptor()));

    le_audio::LeAudioServer::Initialize(&mock_callbacks_, std::move(dependencies));
    ASSERT_NE(ase_manager_callbacks_, nullptr);
  }

  void TearDown() override {
    le_audio::LeAudioServer::Cleanup();
    delete shim::mock_ble_advertiser_interface_;
    delete mock_audio_factory_;
    delete mock_audio_provider_factory_;
    delete mock_audio_out_;
    delete mock_audio_in_;
    mock_audio_factory_ = nullptr;
    mock_audio_in_ = nullptr;
    mock_audio_out_ = nullptr;
    hci::testing::mock_controller_.reset();
    reset_mock_btm_client_interface();
    gatt::SetMockBtaGattServerInterface(nullptr);
  }

  testing::NiceMock<MockBtmClientInterface> mock_btm_interface_;
  testing::NiceMock<gatt::MockBtaGattServerInterface> mock_gatt_server_if_;
  MockLeAudioServerCallbacks mock_callbacks_;
  std::shared_ptr<le_audio::MockLeAudioServerConfigManager> mock_config_manager_;
  std::shared_ptr<le_audio::MockPacs> mock_pacs_;
  std::shared_ptr<le_audio::MockAscs> mock_ascs_;
  std::shared_ptr<le_audio::MockAseManager> mock_ase_manager_;
  le_audio::AseManager::Callbacks* ase_manager_callbacks_ = nullptr;
  le_audio::Ascs::ServiceDescriptor asc_svc_descriptor_;
  MockPeripheralAudioSessionFactory* mock_audio_factory_;
  MockPeripheralAudioProviderFactory* mock_audio_provider_factory_;
  MockPeripheralAudioOut* mock_audio_out_;
  MockPeripheralAudioIn* mock_audio_in_;
};

TEST_F(LeAudioServerTest, Initialize) { Initialize(); }

TEST_F(LeAudioServerTest, InitializeFailsIfNoIsoSupport) {
  auto dependencies = std::make_unique<le_audio::LeAudioServerDependencies>();
  dependencies->config_manager_factory = [this]() { return mock_config_manager_; };
  dependencies->pacs_factory = [this]() { return mock_pacs_; };
  dependencies->ascs_factory = [this]() { return mock_ascs_; };
  dependencies->ase_manager_factory = [this](auto) { return mock_ase_manager_; };
  dependencies->peripheral_audio_session_factory = []() { return nullptr; };
  dependencies->peripheral_audio_provider_factory = []() { return nullptr; };

  ON_CALL(*hci::testing::mock_controller_, SupportsBleConnectedIsochronousStreamCentral)
          .WillByDefault(Return(false));
  ON_CALL(*hci::testing::mock_controller_, SupportsBleConnectedIsochronousStreamPeripheral)
          .WillByDefault(Return(false));

  EXPECT_CALL(mock_callbacks_, OnInitialized()).Times(0);
  le_audio::LeAudioServer::Initialize(&mock_callbacks_, std::move(dependencies));
}

TEST_F(LeAudioServerTest, DoubleInitializeIsHarmless) {
  Initialize();
  // Expect that the second Initialize() is a no-op and does not crash.
  auto dependencies = std::make_unique<le_audio::LeAudioServerDependencies>();
  dependencies->config_manager_factory = [this]() { return mock_config_manager_; };
  dependencies->pacs_factory = [this]() { return mock_pacs_; };
  dependencies->ascs_factory = [this]() { return mock_ascs_; };
  dependencies->ase_manager_factory = [this](auto) { return mock_ase_manager_; };
  dependencies->peripheral_audio_session_factory = []() { return nullptr; };
  dependencies->peripheral_audio_provider_factory = []() { return nullptr; };

  le_audio::LeAudioServer::Initialize(&mock_callbacks_, std::move(dependencies));
}

TEST_F(LeAudioServerTest, CleanupWithoutInitializeIsHarmless) {
  // Intentionally not calling Initialize()
  // This should not crash and just log an error.
  le_audio::LeAudioServer::Cleanup();
}

TEST_F(LeAudioServerTest, GetFailsBeforeInitialize) {
  // Intentionally not calling Initialize()
  ASSERT_DEATH(le_audio::LeAudioServer::Get(), "");
}

TEST_F(LeAudioServerTest, OnClientDisconnected) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

  EXPECT_CALL(mock_callbacks_,
              OnConnectionStateChanged(addr, le_audio::GattConnectionState::DISCONNECTED));
  ase_manager_callbacks_->OnClientDisconnected(addr);
}

TEST_F(LeAudioServerTest, OnClientConnected_AsPeripheral) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

  ON_CALL(mock_btm_interface_, BTM_GetRole(_, _, _))
          .WillByDefault([](const RawAddress&, tBT_TRANSPORT, tHCI_ROLE* p_role) {
            *p_role = HCI_ROLE_PERIPHERAL;
            return tBTM_STATUS::BTM_SUCCESS;
          });

  EXPECT_CALL(*mock_ase_manager_, IsKnownPeerDevice(addr)).WillOnce(Return(true));
  ON_CALL(*mock_ascs_, GetConnectionId(addr)).WillByDefault(Return(1));
  EXPECT_CALL(mock_callbacks_,
              OnConnectionStateChanged(addr, le_audio::GattConnectionState::CONNECTED));

  ase_manager_callbacks_->OnClientConnected(addr);
}

TEST_F(LeAudioServerTest, OnClientConnected_AsCentralFails) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

  ON_CALL(mock_btm_interface_, BTM_GetRole(_, _, _))
          .WillByDefault([](const RawAddress&, tBT_TRANSPORT, tHCI_ROLE* p_role) {
            *p_role = HCI_ROLE_CENTRAL;
            return tBTM_STATUS::BTM_SUCCESS;
          });

  EXPECT_CALL(*mock_ase_manager_, IsKnownPeerDevice(addr)).WillOnce(Return(true));
  ON_CALL(*mock_ascs_, GetConnectionId(addr)).WillByDefault(Return(1));
  EXPECT_CALL(mock_callbacks_, OnConnectionStateChanged(addr, _)).Times(0);

  ase_manager_callbacks_->OnClientConnected(addr);
}

TEST_F(LeAudioServerTest, ConfirmStreamStartRequest_Allowed) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

  EXPECT_CALL(*mock_ase_manager_, ConfirmAseEnableRequest(addr, true));
  le_audio::LeAudioServer::ConfirmStreamStartRequest(addr, true);
}

TEST_F(LeAudioServerTest, ConfirmStreamStartRequest_Denied) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

  EXPECT_CALL(*mock_ase_manager_, ConfirmAseEnableRequest(addr, false));
  le_audio::LeAudioServer::ConfirmStreamStartRequest(addr, false);
}

TEST_F(LeAudioServerTest, StopStream) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  uint8_t stream_id = 1;

  EXPECT_CALL(*mock_ase_manager_, ReleaseAse(addr, stream_id));
  le_audio::LeAudioServer::StopStream(addr, stream_id);
}

TEST_F(LeAudioServerTest, OnAseEnableRequest) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  std::vector<le_audio::AseEnableRequest> requests = {le_audio::AseEnableRequest{
          .ase_id = 1, .direction = le_audio::types::kLeAudioDirectionSink}};

  EXPECT_CALL(mock_callbacks_, OnStreamStartRequest(addr, requests));
  ase_manager_callbacks_->OnAseEnableRequest(addr, requests);
}

TEST_F(LeAudioServerTest, StreamStartSequence_ForSink) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  uint8_t stream_id = 1;
  uint32_t audio_context_type = 123;

  // Setup for OnDecodingIsoChannelParametersUpdated
  tBLE_BD_ADDR addr_with_type = {};
  addr_with_type.bda = addr;
  addr_with_type.type = BLE_ADDR_RANDOM;
  uint16_t cis_conn_hdl = 1234;
  std::optional<le_audio::ascs::AseStateCodecConfiguration> codec_config =
          std::make_optional<le_audio::ascs::AseStateCodecConfiguration>();
  std::optional<le_audio::ascs::AseStateQosConfiguration> qos_config =
          std::make_optional<le_audio::ascs::AseStateQosConfiguration>();
  uint8_t target_latency = 0;
  std::optional<std::vector<uint8_t>> metadata = std::make_optional<std::vector<uint8_t>>();

  // To call OnSinkStreamReady, is_decoding_session_started_ must be true.
  // This is set in StartDecodingAudioSession, which is called from OnCodecConfigRequest.
  // So we need to call OnCodecConfigRequest first to start the decoding session.
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = stream_id, .codec_configuration = {.codec_id = {.coding_format = 1}}}};
  ON_CALL(*mock_ase_manager_, IsSinkAse(stream_id)).WillByDefault(Return(true));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[stream_id] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_out_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Expectations
  EXPECT_CALL(mock_callbacks_, OnSinkStreamReady(addr));
  EXPECT_CALL(mock_callbacks_, OnStreamStarted(addr, stream_id, audio_context_type));

  // Call sequence
  ase_manager_callbacks_->OnDecodingIsoChannelParametersUpdated(
          addr, addr_with_type, stream_id, cis_conn_hdl, codec_config, qos_config, target_latency,
          metadata);
  ase_manager_callbacks_->OnAseStreamStarted(addr, stream_id, audio_context_type);
}

TEST_F(LeAudioServerTest, StreamStartSequence_ForSource) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  uint8_t stream_id = 1;
  uint32_t audio_context_type = 123;

  ON_CALL(*mock_ase_manager_, IsSourceAse(stream_id)).WillByDefault(Return(true));

  // Setup for OnEncodingIsoChannelParametersUpdated
  tBLE_BD_ADDR addr_with_type = {};
  addr_with_type.bda = addr;
  addr_with_type.type = BLE_ADDR_RANDOM;
  uint16_t cis_conn_hdl = 1234;
  std::optional<le_audio::ascs::AseStateCodecConfiguration> codec_config =
          std::make_optional<le_audio::ascs::AseStateCodecConfiguration>();
  std::optional<le_audio::ascs::AseStateQosConfiguration> qos_config =
          std::make_optional<le_audio::ascs::AseStateQosConfiguration>();
  uint8_t target_latency = 0;
  std::optional<std::vector<uint8_t>> metadata = std::make_optional<std::vector<uint8_t>>();

  // To call OnEncodingIsoChannelParametersUpdated, encoding_session_ must exist.
  // This is created in StartEncodingAudioSession, which is called from OnCodecConfigRequest.
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = stream_id, .codec_configuration = {.codec_id = {.coding_format = 1}}}};
  ON_CALL(*mock_ase_manager_, IsSinkAse(stream_id)).WillByDefault(Return(false));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[stream_id] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_in_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Expectations
  EXPECT_CALL(mock_callbacks_, OnStreamStarted(addr, stream_id, audio_context_type));
  EXPECT_CALL(mock_callbacks_, OnSourceStreamReady(addr));

  // Call sequence
  ase_manager_callbacks_->OnEncodingIsoChannelParametersUpdated(
          cis_conn_hdl, addr, addr_with_type, codec_config, qos_config, target_latency, metadata);
  ase_manager_callbacks_->OnAseStreamStarted(addr, stream_id, audio_context_type);
}

TEST_F(LeAudioServerTest, OnAseStopped) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  uint8_t stream_id = 1;

  EXPECT_CALL(mock_callbacks_, OnStreamStopped(addr, stream_id));

  ase_manager_callbacks_->OnAseStopped(addr, stream_id);
}

TEST_F(LeAudioServerTest, OnAseMetadataUpdated) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  uint8_t stream_id = 1;
  uint32_t audio_context_type = 456;

  EXPECT_CALL(mock_callbacks_, OnStreamMetadataUpdated(addr, stream_id, audio_context_type));

  ase_manager_callbacks_->OnAseMetadataUpdated(addr, stream_id, audio_context_type);
}

TEST_F(LeAudioServerTest, OnCodecConfigRequestConfiguresAses) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = 1, .codec_configuration = {.codec_id = {.coding_format = 1}}}};

  ON_CALL(*mock_ase_manager_, IsSinkAse(1)).WillByDefault(Return(true));

  audio::le_audio::endpoint_config_rsp rsp;
  rsp[1] = le_audio::ascs::AseStateCodecConfiguration{};

  EXPECT_CALL(*mock_audio_out_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));

  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);
}

TEST_F(LeAudioServerTest, OnAllSinkAsesInIdleStopsDecodingSession) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = 1, .codec_configuration = {.codec_id = {.coding_format = 1}}}};

  ON_CALL(*mock_ase_manager_, IsSinkAse(1)).WillByDefault(Return(true));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[1] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_out_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));

  // Start the session
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Now set expectation on the created mock
  EXPECT_CALL(*mock_audio_out_, Stop());
  EXPECT_CALL(*mock_ase_manager_, GetNonIdlePeerDevices()).WillOnce(Return(std::set<RawAddress>()));

  // Trigger idle
  ase_manager_callbacks_->OnAllSinkAsesInIdle(addr);
}

TEST_F(LeAudioServerTest, OnAllSinkAsesInIdleButOneIsStillActive) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  RawAddress other_addr = RawAddress::FromString("AA:BB:CC:DD:EE:FF").value();
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = 1, .codec_configuration = {.codec_id = {.coding_format = 1}}}};

  ON_CALL(*mock_ase_manager_, IsSinkAse(1)).WillByDefault(Return(true));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[1] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_out_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));

  // Start the session
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Now set expectation on the created mock
  EXPECT_CALL(*mock_audio_out_, Stop()).Times(0);
  EXPECT_CALL(*mock_ase_manager_, GetNonIdlePeerDevices())
          .WillOnce(Return(std::set<RawAddress>({other_addr})));
  EXPECT_CALL(*mock_ase_manager_, IsActiveSinkStream(other_addr)).WillOnce(Return(true));

  // Trigger idle for the first device
  ase_manager_callbacks_->OnAllSinkAsesInIdle(addr);
}

TEST_F(LeAudioServerTest, OnAllSourceAsesInIdleStopsEncodingSession) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = 1, .codec_configuration = {.codec_id = {.coding_format = 1}}}};

  ON_CALL(*mock_ase_manager_, IsSourceAse(1)).WillByDefault(Return(true));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[1] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_in_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));

  // Start the session
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Now set expectation on the created mock
  EXPECT_CALL(*mock_audio_in_, Stop());
  EXPECT_CALL(*mock_ase_manager_, GetNonIdlePeerDevices()).WillOnce(Return(std::set<RawAddress>()));

  // Trigger idle
  ase_manager_callbacks_->OnAllSourceAsesInIdle(addr);
}

TEST_F(LeAudioServerTest, OnSinkAsesInIdleDoesNotStopEncodingSession) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = 1, .codec_configuration = {.codec_id = {.coding_format = 1}}}};

  ON_CALL(*mock_ase_manager_, IsSinkAse(1)).WillByDefault(Return(true));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[1] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_out_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));

  // Start the session
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Now set expectation on the created mock
  EXPECT_CALL(*mock_audio_out_, Stop());
  EXPECT_CALL(*mock_audio_in_, Stop()).Times(0);
  EXPECT_CALL(*mock_ase_manager_, GetNonIdlePeerDevices()).WillOnce(Return(std::set<RawAddress>()));

  // Trigger idle for sink ases
  ase_manager_callbacks_->OnAllSinkAsesInIdle(addr);
}

TEST_F(LeAudioServerTest, OnAllAsesInIdleStopsBothSessions_ForCall) {
  Initialize();
  RawAddress addr = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  std::vector<le_audio::ascs::AseCodecConfigurationReq> reqs = {
          {.ase_id = 1, .codec_configuration = {.codec_id = {.coding_format = 1}}},
          {.ase_id = 2, .codec_configuration = {.codec_id = {.coding_format = 1}}}};

  ON_CALL(*mock_ase_manager_, IsSinkAse(1)).WillByDefault(Return(true));
  ON_CALL(*mock_ase_manager_, IsSourceAse(2)).WillByDefault(Return(true));
  audio::le_audio::endpoint_config_rsp rsp;
  rsp[1] = le_audio::ascs::AseStateCodecConfiguration{};
  rsp[2] = le_audio::ascs::AseStateCodecConfiguration{};
  EXPECT_CALL(*mock_audio_out_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));
  EXPECT_CALL(*mock_audio_in_, RequestAseConfigurations(_, _)).WillOnce(Return(rsp));

  // Start the session
  ase_manager_callbacks_->OnCodecConfigRequest(addr, reqs);

  // Now set expectation on the created mock
  EXPECT_CALL(*mock_audio_out_, Stop());
  EXPECT_CALL(*mock_audio_in_, Stop());
  EXPECT_CALL(*mock_ase_manager_, GetNonIdlePeerDevices())
          .WillRepeatedly(Return(std::set<RawAddress>()));

  // Trigger idle for all ases
  ase_manager_callbacks_->OnAllSinkAsesInIdle(addr);
  ase_manager_callbacks_->OnAllSourceAsesInIdle(addr);
}
}  // namespace
