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

#include "ase_manager.h"

#ifdef __BIONIC__
#include <sys/system_properties.h>
#else
#include <android-base/properties.h>
#endif

#include <base/test/bind_test_util.h>
#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <numeric>
#include <variant>

#include "ascs.h"
#include "ascs_types.h"
#include "ase_state_machine.h"
#include "bta/le_audio/common/mock_iso_app_proxy.h"
#include "bta/le_audio/le_audio_utils.h"
#include "osi/include/properties.h"
#include "stack/include/btm_iso_api.h"
#include "stack/mock/mock_stack_btm_dev.h"
#include "stack/mock/mock_stack_btm_iso.h"
#include "stack/mock/mock_stack_hcic_layer.h"
#include "test/common/sync_main_handler.h"
#include "test/mock/mock_main_shim_entry.h"

static constexpr char kIsPeripheralCachingSupportedProperty[] =
        "bluetooth.le_audio.peripheral.caching.enabled";

extern std::map<uint16_t, BtmDevice> AclHandleToMockBtmDevice;

const tBLE_BD_ADDR BTM_Sec_GetAddressWithType(const RawAddress& bd_addr) {
  return tBLE_BD_ADDR{.type = BLE_ADDR_PUBLIC, .bda = bd_addr};
}

namespace bluetooth::le_audio::test {

using ::testing::_;
using ::testing::DoAll;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SaveArg;

// clang-format off
class MockAscs : public Ascs {
public:
  MockAscs() {};
  MOCK_METHOD((void), Dump, (std::stringstream & stream), (const, override));
  MOCK_METHOD((void), RegisterGattService,
              (const ServiceDescriptor& service_descriptor, Callbacks* callbacks), (override));
  MOCK_METHOD((void), UpdateAseState,
              (const RawAddress& pseudo_addr, uint8_t ase_id, const AseState& ase_state), (override));
  MOCK_METHOD((void), AseCtpRequestResponse,
              (const RawAddress& pseudo_addr, const AseCtpResponse& response), (override));
  MOCK_METHOD((uint16_t), GetConnectionId, (const RawAddress& pseudo_addr), (const, override));
};

class MockAscsAseStateMachine : public AscsAseStateMachine {
public:
  MockAscsAseStateMachine(bool is_source_ase, uint8_t ase_id, const RawAddress& peer,
                          ServiceCallbacks* callbacks)
      : AscsAseStateMachine(is_source_ase, ase_id, peer, callbacks), sm_callbacks_(callbacks) {}
  MOCK_METHOD((bool), ProcessEvent, (Events event, void* p_data), (override));

  ServiceCallbacks* sm_callbacks_ = nullptr;
};

static RawAddress GetTestAddress(uint8_t index) {
  return std::array<uint8_t, 6>{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
}

class MockAseManagerCallbacks : public AseManager::Callbacks {
public:
  MOCK_METHOD((void), OnClientConnected, (const RawAddress& address), (override));
  MOCK_METHOD((void), OnClientDisconnected, (const RawAddress& address), (override));
  MOCK_METHOD((void), OnAsesRegistered, (std::set<uint8_t> sink_ases, std::set<uint8_t> source_ases), (override));
  MOCK_METHOD((void), OnAllSinkAsesInIdle, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((void), OnAllSourceAsesInIdle, (const RawAddress& pseudo_addr), (override));
  MOCK_METHOD((std::map<uint8_t, std::variant<ascs::AseStateCodecConfiguration,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>),
              OnCodecConfigRequest,
              (const RawAddress& pseudo_address, const std::vector<ascs::AseCodecConfigurationReq>& requests), (override));
  MOCK_METHOD(
          (std::map<uint8_t,
                    std::variant<ascs::DataPathConfiguration,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>),
          OnSetQosParameters,
          (const RawAddress& pseudo_address,
           (std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                         ascs::AseStateQosConfiguration>>)),
          (override));
  MOCK_METHOD(
          (std::map<uint8_t,
                    std::variant<std::vector<uint8_t>,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>),
          OnUpdateMetadata,
          (const RawAddress& pseudo_address, (std::map<uint8_t, std::vector<uint8_t>> params)),
          (override));
  MOCK_METHOD((bool), IsDecodingSessionReady, (), (const override));
  MOCK_METHOD((bool), IsEncodingSessionReady, (), (const override));
  MOCK_METHOD((void), OnDecodingIsoChannelParametersUpdated,
              (const RawAddress& pseudo_address, const tBLE_BD_ADDR address_with_type, int ase_id,
               uint16_t cis_conn_hdl,
               const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
               const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
               uint8_t target_latency, const std::optional<std::vector<uint8_t>>& metadata),
              (override));
  MOCK_METHOD((void), OnEncodingIsoChannelParametersUpdated,
              (uint16_t cis_conn_hdl, const RawAddress& pseudo_address, const tBLE_BD_ADDR,
               const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
               const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
               uint8_t target_latency, const std::optional<std::vector<uint8_t>>& metadata),
              (override));
  MOCK_METHOD((void), OnIsoDataReceived,
              (uint8_t ase_id, const hci::iso_manager::cis_data_evt* event,
               const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
               const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
               const std::optional<std::vector<uint8_t>>& metadata),
              (override));
  MOCK_METHOD((void), OnAseEnableRequest, (const RawAddress&, const std::vector<bluetooth::le_audio::AseEnableRequest>&), (override));
  MOCK_METHOD((void), OnAseStreamStarted,
              (const RawAddress& pseudo_address, uint8_t ase_id, uint32_t audio_context_type),
              (override));
  MOCK_METHOD((void), OnAseStopped, (const RawAddress& pseudo_address, uint8_t ase_id),
              (override));
  MOCK_METHOD((void), OnAseMetadataUpdated, (const RawAddress&, uint8_t, uint32_t), (override));
};
// clang-format on

class AseManagerTest : public ::testing::Test {
public:
  void SetUp(void) override {
    main_thread_start_up();
    post_on_bt_main([]() { log::info("Main thread started up"); });

    ON_CALL(mock_ase_manager_cb_, OnClientDisconnected(_))
            .WillByDefault([this](const RawAddress& address) {
              if (mock_iso_state_machines_by_device_and_ase_id_.count(address)) {
                mock_iso_state_machines_by_device_and_ase_id_.erase(address);
              }
            });

    // Configure the mock controller
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<NiceMock<bluetooth::hci::testing::MockController>>();
    ON_CALL(*bluetooth::hci::testing::mock_controller_,
            IsSupported(bluetooth::hci::OpCode::CONFIGURE_DATA_PATH))
            .WillByDefault(Return(true));

    // Set up the mock for the legacy HCI interface
    hcic::SetMockHcicInterface(&legacy_hci_mock_);

    // Set up the mock ASCS
    mock_ascs_ = std::make_shared<MockAscs>();

    // Set up the mock ISO manager
    iso_manager_ = hci::IsoManager::GetInstance();
    ASSERT_NE(iso_manager_, nullptr);
    iso_manager_->Start();
    mock_iso_manager_ = MockIsoManager::GetInstance();
    ASSERT_NE(mock_iso_manager_, nullptr);

    // Mock the security record
    AclHandleToMockBtmDevice = {};
  }

  void SetSystemPropertyForTest(const char* key, const char* value) {
    std::string current_value;

#ifdef __BIONIC__
    char buf[PROP_VALUE_MAX] = {};
    __system_property_get(key, buf);
    current_value = buf;
#else
    current_value = android::base::GetProperty(key, "");
#endif

    if (current_value.empty()) {
      char osi_buf[PROPERTY_VALUE_MAX] = {};
      osi_property_get(key, osi_buf, "");
      current_value = osi_buf;
    }

    if (property_backup_.find(key) == property_backup_.end()) {
      property_backup_[key] = current_value;
    }

#ifdef __BIONIC__
    __system_property_set(key, value);
#else
    android::base::SetProperty(key, value);
#endif
    osi_property_set(key, value);
  }

  void RecoverSystemPropertyFromTest() {
    for (auto const& [key, val] : property_backup_) {
#ifdef __BIONIC__
      __system_property_set(key.c_str(), val.c_str());
#else
      android::base::SetProperty(key, val);
#endif
      osi_property_set(key.c_str(), val.c_str());
    }
    property_backup_.clear();
  }

  std::vector<uint8_t> GetSinkAses() const {
    std::vector<uint8_t> v(ascs_svc_descriptor_.num_sink_ases);
    std::iota(std::begin(v), std::end(v), uint8_t(1));
    return v;
  }

  std::set<uint8_t> GetSinkAsesAsSet() const {
    auto v = GetSinkAses();
    return std::set<uint8_t>(std::make_move_iterator(v.begin()), std::make_move_iterator(v.end()));
  }

  std::vector<uint8_t> GetSourceAses() const {
    std::vector<uint8_t> v(ascs_svc_descriptor_.num_source_ases);
    std::iota(std::begin(v), std::end(v), uint8_t(ascs_svc_descriptor_.num_sink_ases + 1));
    return v;
  }

  std::set<uint8_t> GetSourceAsesAsSet() const {
    auto v = GetSourceAses();
    return std::set<uint8_t>(std::make_move_iterator(v.begin()), std::make_move_iterator(v.end()));
  }

  uint8_t GetMockedAclHandleForAddress(const RawAddress& address) { return address.address[5]; }

  void TestInitialize(uint8_t num_sink_ases = 2, uint8_t num_source_ases = 2) {
    auto sm_factory = [this](bool is_source_ase, uint8_t id, const RawAddress& address,
                             AscsAseStateMachine::ServiceCallbacks* cb)
            -> std::unique_ptr<AscsAseStateMachine> {
      auto sm = std::make_unique<NiceMock<MockAscsAseStateMachine>>(is_source_ase, id, address, cb);
      MockAscsAseStateMachine* mock_sm = sm.get();
      ON_CALL(*mock_sm, ProcessEvent(_, _))
              .WillByDefault([mock_sm](AscsAseStateMachine::Events event, void* p_data) {
                // Call the original code instead of trying to replicate the logic
                return mock_sm->AscsAseStateMachine::ProcessEvent(event, p_data);
              });

      mock_iso_state_machines_by_device_and_ase_id_[address][id] = sm.get();
      return sm;
    };

    auto iso_app_factory =
            [this](hci::iso_manager::CigCallbacks* cig_callbacks) -> std::unique_ptr<IsoAppProxy> {
      auto iso_app = std::make_unique<MockIsoAppProxy>(cig_callbacks, nullptr, nullptr);
      cig_callbacks_to_ase_manager_ = cig_callbacks;
      mock_iso_app_proxy_ = iso_app.get();
      return iso_app;
    };

    ase_manager_ = std::make_unique<AseManager>(mock_ascs_, base::BindLambdaForTesting(sm_factory),
                                                base::BindLambdaForTesting(iso_app_factory));

    EXPECT_CALL(*mock_ascs_, RegisterGattService(_, _))
            .WillOnce(DoAll(SaveArg<0>(&ascs_svc_descriptor_), SaveArg<1>(&ascs_callbacks_)));
    ase_manager_->Initialize({.num_sink_ases = num_sink_ases, .num_source_ases = num_source_ases},
                             &mock_ase_manager_cb_);

    // Verify if AseManager have registered his callbacks to ASCS service instance
    ASSERT_NE(ascs_callbacks_, nullptr);

    auto sink_ases = GetSinkAses();
    auto source_ases = GetSourceAses();
    std::vector<int> ase_intersection;
    std::set_intersection(sink_ases.begin(), sink_ases.end(), source_ases.begin(),
                          source_ases.end(), std::back_inserter(ase_intersection));

    /*  Make sure the mocked ASE ids are unique */
    ASSERT_TRUE(ase_intersection.empty());
  }

  virtual void TearDown(void) override {
    RecoverSystemPropertyFromTest();

    post_on_bt_main([]() { log::info("Main thread shutting down"); });
    main_thread_shut_down();

    mock_ascs_.reset();
    iso_manager_->Stop();

    bluetooth::hci::testing::mock_controller_.reset();
  }

  ascs::AseStateCodecConfiguration GetAseCodecConfiguredStateFromRequest(
          const ascs::CodecConfigurationReq& request) {
    ascs::AseStateCodecConfiguration configuration;
    configuration.codec_id = request.codec_id;
    configuration.codec_spec_conf = request.codec_spec_conf;
    configuration.preferred_phy = utils::GetPreferredPhyFromTargetPhy(request.target_phy);

    // WARNING: pres_delay_min == 0 (not set) will prevent the state machine from going
    //          back to CODEC_CONFIGURED on RELEASED (caching).
    configuration.pres_delay_min = 50;

    // The rest is not important for tests if those differ in any other way (e.g. codec_spec_conf)
    return configuration;
  }

  std::shared_ptr<MockAscs> mock_ascs_ = nullptr;
  Ascs::Callbacks* ascs_callbacks_ = nullptr;

  hci::IsoManager* iso_manager_ = nullptr;
  MockIsoManager* mock_iso_manager_ = nullptr;
  MockIsoAppProxy* mock_iso_app_proxy_ = nullptr;
  hci::iso_manager::CigCallbacks* cig_callbacks_to_ase_manager_ = nullptr;
  std::map<RawAddress, std::map<uint8_t, MockAscsAseStateMachine*>>
          mock_iso_state_machines_by_device_and_ase_id_;

  std::unique_ptr<AseManager> ase_manager_;
  MockAseManagerCallbacks mock_ase_manager_cb_;
  Ascs::ServiceDescriptor ascs_svc_descriptor_;

  hcic::MockHcicInterface legacy_hci_mock_;

  RawAddress test_address1_ = GetTestAddress(1);
  RawAddress test_address2_ = GetTestAddress(2);
  std::map<std::string, std::string> property_backup_;

  void TestConfigureCodec(uint8_t ase_id, const RawAddress& address) {
    ascs::AseCodecConfigurationReq codec_req = {
            .ase_id = ase_id,
            .codec_configuration = {
                    .target_latency = 1,
                    .target_phy = 2,
                    .codec_id = le_audio::types::LeAudioCodecIdLc3,
                    // This makes each device codec configurations unique
                    .codec_spec_conf = {0x02, ase_id, address.address[5]},
            }};
    Ascs::AseCtpRequest request = {
            .opcode = ascs::AseCtpOpcode::CONFIG_CODEC,
            .request_params = std::vector<ascs::AseCodecConfigurationReq>{codec_req},
    };

    // Simulate BAP accepting the AseManager request for codec configuration
    EXPECT_CALL(mock_ase_manager_cb_, OnCodecConfigRequest(address, _))
            .WillOnce(Return(std::map<uint8_t, std::variant<ascs::AseStateCodecConfiguration,
                                                            std::pair<ascs::AseCtpResponseCode,
                                                                      ascs::AseCtpResponseReason>>>{
                    {ase_id,
                     GetAseCodecConfiguredStateFromRequest(codec_req.codec_configuration)}}));

    // Expect success response sent via ASCSs control point
    EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(address, _)).Times(1);
    ascs_callbacks_->OnAseControlPointRequest(address, request);
    sync_main_handler();

    testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());
    testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);

    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
    auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
    ASSERT_NE(sm, nullptr);
    ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(test_address1_));

    auto codec_config_state = ascs_callbacks_->OnGetAseState(address, ase_id);
    ASSERT_EQ(codec_config_state.state, ascs::AseState::CODEC_CONFIGURED);

    auto codec_config =
            std::get_if<ascs::AseStateCodecConfiguration>(&codec_config_state.state_params);
    ASSERT_NE(codec_config, nullptr);
  }

  void TestConfigureQos(uint8_t ase_id, const RawAddress& address, uint8_t cig_id, uint8_t cis_id) {
    // Generate unique QoS configuration
    ascs::AseQosConfigurationReq qos_req = {.ase_id = ase_id,
                                            .qos_configuration = {
                                                    .cig_id = cig_id,
                                                    .cis_id = cis_id,
                                                    .retrans_nb = address.address[5],
                                                    .max_transport_latency = 100,
                                            }};
    Ascs::AseCtpRequest request = {
            .opcode = ascs::AseCtpOpcode::CONFIG_QOS,
            .request_params = std::vector<ascs::AseQosConfigurationReq>{qos_req},
    };

    // Simulate BAP accepting the AseManager request for QoS configuration
    // Generate unique data path configuration
    EXPECT_CALL(mock_ase_manager_cb_, OnSetQosParameters(address, _))
            .WillOnce(Return(std::map<uint8_t, std::variant<ascs::DataPathConfiguration,
                                                            std::pair<ascs::AseCtpResponseCode,
                                                                      ascs::AseCtpResponseReason>>>{
                    {ase_id, ascs::DataPathConfiguration{
                                     .dataPathId = address.address[5],
                                     .dataPathConfig = {cig_id, cis_id},
                             }}}));

    // Expect success response sent via ASCSs control point
    EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(address, _)).Times(1);
    ascs_callbacks_->OnAseControlPointRequest(address, request);
    sync_main_handler();

    testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());
    testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);

    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
    auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
    ASSERT_NE(sm, nullptr);
    ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(address));

    auto ase_state = ascs_callbacks_->OnGetAseState(address, ase_id);
    ASSERT_EQ(ase_state.state, ascs::AseState::QOS_CONFIGURED);

    auto ase_config = std::get_if<ascs::AseStateQosConfiguration>(&ase_state.state_params);
    ASSERT_NE(ase_config, nullptr);
  }

  void TestEnable(uint8_t ase_id, const RawAddress& address, bool confirm = true,
                  Ascs::AseCtpResponse* out_response = nullptr) {
    le_audio::types::LeAudioLtvMap ltv;
    ltv.Add(le_audio::types::kLeAudioMetadataTypeStreamingAudioContext,
            (uint16_t)le_audio::types::LeAudioContextType::MEDIA);

    ascs::AseEnableReq enable_req = {.ase_id = ase_id, .metadata = ltv.RawPacket()};
    Ascs::AseCtpRequest request = {
            .opcode = ascs::AseCtpOpcode::ENABLE,
            .request_params = std::vector<ascs::AseEnableReq>{enable_req},
    };

    // Expect the request to come up to the mock, and immediately confirm it
    EXPECT_CALL(mock_ase_manager_cb_, OnAseEnableRequest(address, _))
            .WillOnce([&](const RawAddress& peer_address,
                          const std::vector<bluetooth::le_audio::AseEnableRequest>&) {
              ase_manager_->ConfirmAseEnableRequest(peer_address, confirm);
            });

    if (out_response) {
      // Keep the response for verification
      EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(address, _))
              .WillOnce(SaveArg<1>(out_response));
    } else {
      EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(address, _)).Times(1);
    }

    ascs_callbacks_->OnAseControlPointRequest(address, request);
    sync_main_handler();

    testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());
    testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);

    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
    auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
    ASSERT_NE(sm, nullptr);
    if (confirm) {
      // Go to ENABLING when enable is accepted
      ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::ENABLING);
    } else {
      // Stay in QOS_CONFIGURED when enable is denied
      ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
    }

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(address));
  }

  void TestEstablishCis(uint8_t ase_id, const RawAddress& address, uint8_t cig_id, uint8_t cis_id,
                        uint16_t cis_conn_hdl, bool accept_cis_request = true) {
    if (accept_cis_request) {
      EXPECT_CALL(*mock_iso_app_proxy_, AcceptIncomingCisConnection(_)).Times(1);
    } else {
      EXPECT_CALL(*mock_iso_app_proxy_, RejectIncomingCisConnection(_, _)).Times(1);
    }

    hci::iso_manager::cis_request_evt cis_request_evt;
    cis_request_evt.acl_conn_hdl = GetMockedAclHandleForAddress(address);
    cis_request_evt.cis_conn_hdl = cis_conn_hdl;
    cis_request_evt.cig_id = cig_id;
    cis_request_evt.cis_id = cis_id;

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(address));

    // In this test, the mock BTM device table is populated just before the
    // CIS request event. This is because the ACL handle is not available
    // when the device connection is simulated earlier in the test.
    AclHandleToMockBtmDevice[cis_request_evt.acl_conn_hdl] = BtmDevice{.ble.pseudo_addr = address};

    cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisRequest,
                                              &cis_request_evt);
    testing::Mock::VerifyAndClearExpectations(mock_iso_app_proxy_);

    if (!accept_cis_request) {
      // The state should not change
      auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
      ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::ENABLING);
      return;
    }

    EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(_, _, _)).Times(1);
    auto is_sink = ase_manager_->IsSinkAse(ase_id);
    if (is_sink) {
      EXPECT_CALL(mock_ase_manager_cb_,
                  OnDecodingIsoChannelParametersUpdated(_, _, _, _, _, _, _, _))
              .Times(1);
    } else {
      EXPECT_CALL(mock_ase_manager_cb_, OnEncodingIsoChannelParametersUpdated(_, _, _, _, _, _, _))
              .Times(1);
    }

    // Inject CIS established event
    ON_CALL(*mock_iso_app_proxy_, HasCisConnected(cis_conn_hdl)).WillByDefault(Return(true));
    hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt;
    cis_establish_evt.status = 0x00;
    cis_establish_evt.cis_conn_hdl = cis_conn_hdl;
    cis_establish_evt.cig_id = cig_id;
    cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisEstablishCmpl,
                                              &cis_establish_evt);

    if (is_sink) {
      ase_manager_->OnDecodingSessionReady(address);
    } else {
      // Must stay in enabling state until the remote is ready to receive the data
      ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
      ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
      auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
      ASSERT_NE(sm, nullptr);
      ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::ENABLING);

      // Expect success response sent via ASCSs control point
      EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(address, _)).Times(1);
      Ascs::AseCtpRequest request = {
              .opcode = ascs::AseCtpOpcode::RECEIVER_START_READY,
              .request_params = std::vector<uint8_t>{ase_id},
      };
      ascs_callbacks_->OnAseControlPointRequest(address, request);
      sync_main_handler();

      // We should trigger this once we get the receiver start ready for the source ASE
      testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());
      ase_manager_->OnEncodingSessionReady(address);
    }
    sync_main_handler();

    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
    auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
    ASSERT_NE(sm, nullptr);
    ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::STREAMING);

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(address));
    testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);
  }

  void TestEstablishCis(std::array<uint8_t, 2> ase_ids, const RawAddress& address, uint8_t cig_id,
                        uint8_t cis_id, uint16_t cis_conn_hdl, bool accept_cis_request = true) {
    if (accept_cis_request) {
      EXPECT_CALL(*mock_iso_app_proxy_, AcceptIncomingCisConnection(_)).Times(1);
    } else {
      EXPECT_CALL(*mock_iso_app_proxy_, RejectIncomingCisConnection(_, _)).Times(1);
    }

    hci::iso_manager::cis_request_evt cis_request_evt;
    cis_request_evt.acl_conn_hdl = GetMockedAclHandleForAddress(address);
    cis_request_evt.cis_conn_hdl = cis_conn_hdl;
    cis_request_evt.cig_id = cig_id;
    cis_request_evt.cis_id = cis_id;

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(address));

    // In this test, the mock BTM device table is populated just before the
    // CIS request event. This is because the ACL handle is not available
    // when the device connection is simulated earlier in the test.
    AclHandleToMockBtmDevice[cis_request_evt.acl_conn_hdl] = BtmDevice{.ble.pseudo_addr = address};

    cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisRequest,
                                              &cis_request_evt);
    testing::Mock::VerifyAndClearExpectations(mock_iso_app_proxy_);

    if (!accept_cis_request) {
      // The state should not change
      for (auto const& ase_id : ase_ids) {
        auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
        ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::ENABLING);
      }
      return;
    }

    for (auto const& ase_id : ase_ids) {
      auto is_sink = ase_manager_->IsSinkAse(ase_id);
      if (is_sink) {
        EXPECT_CALL(legacy_hci_mock_,
                    ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST, _, _))
                .Times(1);
        EXPECT_CALL(mock_ase_manager_cb_,
                    OnDecodingIsoChannelParametersUpdated(_, _, _, _, _, _, _, _))
                .Times(1);
      } else {
        EXPECT_CALL(legacy_hci_mock_,
                    ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER, _, _))
                .Times(1);
        EXPECT_CALL(mock_ase_manager_cb_,
                    OnEncodingIsoChannelParametersUpdated(_, _, _, _, _, _, _))
                .Times(1);
      }
    }

    // Inject CIS established event
    ON_CALL(*mock_iso_app_proxy_, HasCisConnected(cis_conn_hdl)).WillByDefault(Return(true));
    hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt;
    cis_establish_evt.status = 0x00;
    cis_establish_evt.cis_conn_hdl = cis_conn_hdl;
    cis_establish_evt.cig_id = cig_id;
    cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisEstablishCmpl,
                                              &cis_establish_evt);

    for (auto const& ase_id : ase_ids) {
      auto is_sink = ase_manager_->IsSinkAse(ase_id);
      if (is_sink) {
        ase_manager_->OnDecodingSessionReady(address);
      } else {
        // Must stay in enabling state until the remote is ready to receive the data
        ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
        ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
        auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
        ASSERT_NE(sm, nullptr);
        ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::ENABLING);

        // Expect success response sent via ASCSs control point
        EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(address, _)).Times(1);
        Ascs::AseCtpRequest request = {
                .opcode = ascs::AseCtpOpcode::RECEIVER_START_READY,
                .request_params = std::vector<uint8_t>{ase_id},
        };
        ascs_callbacks_->OnAseControlPointRequest(address, request);
        sync_main_handler();

        // We should trigger this once we get the receiver start ready for the source ASE
        testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());
        ase_manager_->OnEncodingSessionReady(address);
      }

      sync_main_handler();
      ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
      ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
      auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
      ASSERT_NE(sm, nullptr);
      ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::STREAMING);
    }

    ASSERT_TRUE(ase_manager_->GetNonIdlePeerDevices().contains(address));
    testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);
  }

  void testDisconnectCis(uint8_t ase_id, const RawAddress& address, uint16_t cis_conn_hdl) {
    uint8_t acl_conn_hdl = GetMockedAclHandleForAddress(address);

    // Verify the mapping exists before disconnection
    ASSERT_NE(AclHandleToMockBtmDevice.find(acl_conn_hdl), AclHandleToMockBtmDevice.end());

    // Now disconnect CIS
    hci::iso_manager::cis_disconnected_evt disconn_evt;
    disconn_evt.cis_conn_hdl = cis_conn_hdl;
    disconn_evt.reason = 0x00;

    cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisDisconnected,
                                              &disconn_evt);
    sync_main_handler();

    // Verify state is back to QOS_CONFIGURED
    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(address), 0lu);
    ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(address).count(ase_id), 0lu);
    auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(address).at(ase_id);
    ASSERT_NE(sm, nullptr);
    ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);

    // Simulate ACL disconnection and verify the mock BTM device mapping is removed.
    AclHandleToMockBtmDevice.erase(acl_conn_hdl);
    ASSERT_EQ(AclHandleToMockBtmDevice.find(acl_conn_hdl), AclHandleToMockBtmDevice.end());
  }
};

TEST_F(AseManagerTest, Initialize) {
  TestInitialize();
  ASSERT_NE(ase_manager_.get(), nullptr);
}

TEST_F(AseManagerTest, IsSourceAse) {
  TestInitialize();
  ASSERT_FALSE(GetSourceAses().empty());

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered({}, GetSourceAsesAsSet());
  ascs_callbacks_->OnDeviceConnected(GetTestAddress(1));
  for (auto ase_id : GetSourceAses()) {
    ASSERT_FALSE(ase_manager_->GetNonIdlePeerDevices().contains(GetTestAddress(1)));
    EXPECT_TRUE(ase_manager_->IsSourceAse(ase_id));
  }
}

TEST_F(AseManagerTest, IsSinkAse) {
  TestInitialize();
  ASSERT_FALSE(GetSinkAses().empty());

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered(GetSinkAsesAsSet(), {});
  ascs_callbacks_->OnDeviceConnected(GetTestAddress(1));
  for (auto ase_id : GetSinkAses()) {
    ASSERT_FALSE(ase_manager_->GetNonIdlePeerDevices().contains(GetTestAddress(1)));
    EXPECT_TRUE(ase_manager_->IsSinkAse(ase_id));
  }
}

TEST_F(AseManagerTest, ConfigureCodec) {
  TestInitialize();

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet());
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  for (auto ase_id : GetSinkAses()) {
    TestConfigureCodec(ase_id, test_address1_);
  }
  for (auto ase_id : GetSourceAses()) {
    TestConfigureCodec(ase_id, test_address1_);
  }
}

TEST_F(AseManagerTest, ConfigureQoS) {
  TestInitialize();

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet());
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  // Start with CONFIG_CODEC
  for (auto ase_id : GetSinkAses()) {
    TestConfigureCodec(ase_id, test_address1_);
  }
  for (auto ase_id : GetSourceAses()) {
    TestConfigureCodec(ase_id, test_address1_);
  }

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;

  for (auto ase_id : GetSinkAses()) {
    TestConfigureQos(ase_id, test_address1_, cig_id, cis_id++);
  }
  for (auto ase_id : GetSourceAses()) {
    TestConfigureQos(ase_id, test_address1_, cig_id, cis_id++);
  }
}

TEST_F(AseManagerTest, Enabling) {
  TestInitialize();

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet());
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  // Start with CONFIG_CODEC
  for (auto ase_id : GetSinkAses()) {
    TestConfigureCodec(ase_id, test_address1_);
  }
  for (auto ase_id : GetSourceAses()) {
    TestConfigureCodec(ase_id, test_address1_);
  }

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;

  for (auto ase_id : GetSinkAses()) {
    TestConfigureQos(ase_id, test_address1_, cig_id, cis_id++);
  }
  for (auto ase_id : GetSourceAses()) {
    TestConfigureQos(ase_id, test_address1_, cig_id, cis_id++);
  }

  // Proceed to ENABLING using
  for (auto ase_id : GetSinkAses()) {
    TestEnable(ase_id, test_address1_);
  }
  for (auto ase_id : GetSourceAses()) {
    TestEnable(ase_id, test_address1_);
  }
}

TEST_F(AseManagerTest, OnDecodingSessionReady) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);
}

TEST_F(AseManagerTest, OnEncodingSessionReady) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({}, {ase_id});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);
}

TEST_F(AseManagerTest, OnEncodingAndDecodingSessionReadyUnidirCises) {
  TestInitialize();

  uint8_t sink_ase_id = 1;
  uint8_t source_ase_id = 2;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({sink_ase_id}, {source_ase_id});

  // Start with CONFIG_CODEC for both sink and source
  TestConfigureCodec(sink_ase_id, test_address1_);
  TestConfigureCodec(source_ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED for both sink and source
  uint8_t cig_id = 0x03;
  uint8_t sink_cis_id = 0x04;
  uint8_t source_cis_id = 0x05;
  TestConfigureQos(sink_ase_id, test_address1_, cig_id, sink_cis_id);
  TestConfigureQos(source_ase_id, test_address1_, cig_id, source_cis_id);

  // Proceed to ENABLING for both sink and source
  TestEnable(sink_ase_id, test_address1_);
  TestEnable(source_ase_id, test_address1_);

  // Proceed to STREAMING for both sink and source
  uint16_t sink_cis_conn_hdl = 0x02;
  uint16_t source_cis_conn_hdl = 0x03;
  TestEstablishCis(sink_ase_id, test_address1_, cig_id, sink_cis_id, sink_cis_conn_hdl);
  TestEstablishCis(source_ase_id, test_address1_, cig_id, source_cis_id, source_cis_conn_hdl);
}

TEST_F(AseManagerTest, OnEncodingAndDecodingSessionReadyBidirCis) {
  TestInitialize();

  uint8_t sink_ase_id = 1;
  uint8_t source_ase_id = 2;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({sink_ase_id}, {source_ase_id});

  // Start with CONFIG_CODEC for both sink and source
  TestConfigureCodec(sink_ase_id, test_address1_);
  TestConfigureCodec(source_ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED for both sink and source
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(sink_ase_id, test_address1_, cig_id, cis_id);
  TestConfigureQos(source_ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING for both sink and source
  TestEnable(sink_ase_id, test_address1_);
  TestEnable(source_ase_id, test_address1_);

  // Proceed to STREAMING for both sink and source
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis({sink_ase_id, source_ase_id}, test_address1_, cig_id, cis_id, cis_conn_hdl);
}

TEST_F(AseManagerTest, IsKnownPeerDevice) {
  TestInitialize();

  EXPECT_CALL(*mock_ascs_, GetConnectionId(test_address1_)).WillOnce(Return(1));
  EXPECT_TRUE(ase_manager_->IsKnownPeerDevice(test_address1_));
}

TEST_F(AseManagerTest, TwoDevicesConfigureAsesWithDifferentConfigsDifferentAses) {
  TestInitialize(4, 4);

  EXPECT_CALL(mock_ase_manager_cb_, OnAsesRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet()));
  ascs_callbacks_->OnAscsRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet());

  EXPECT_CALL(mock_ase_manager_cb_, OnClientConnected(_)).Times(2);
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnDeviceConnected(test_address2_);
  testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);

  auto const all_sink_ases = GetSinkAses();
  auto const all_source_ases = GetSourceAses();

  std::size_t const half_sink_size = all_sink_ases.size() / 2;
  std::size_t const half_source_size = all_source_ases.size() / 2;

  std::vector<uint8_t> d1_sink_ases(all_sink_ases.begin(), all_sink_ases.begin() + half_sink_size);
  std::vector<uint8_t> d2_sink_ases(all_sink_ases.begin() + half_sink_size, all_sink_ases.end());

  std::vector<uint8_t> d1_source_ases(all_source_ases.begin(),
                                      all_source_ases.begin() + half_source_size);
  std::vector<uint8_t> d2_source_ases(all_source_ases.begin() + half_source_size,
                                      all_source_ases.end());

  /* Configure Codec for Device 1 (lower half of all ASEs) */
  for (auto ase_id : d1_sink_ases) {
    TestConfigureCodec(ase_id, test_address1_);
  }
  for (auto ase_id : d1_source_ases) {
    TestConfigureCodec(ase_id, test_address1_);
  }

  /* Configure Codec for Device 2 (upper half of all ASES) */
  for (auto ase_id : d2_sink_ases) {
    TestConfigureCodec(ase_id, test_address2_);
  }
  for (auto ase_id : d2_source_ases) {
    TestConfigureCodec(ase_id, test_address2_);
  }

  /* Make sure that the device got their unique configurations */
  ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, d1_sink_ases[0]),
            ascs_callbacks_->OnGetAseState(test_address2_, d2_sink_ases[0]));
  ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, d1_source_ases[0]),
            ascs_callbacks_->OnGetAseState(test_address2_, d2_source_ases[0]));

  /* Make sure device 2 sees ASEs configured by device 1 still as IDLE */
  for (auto ase_id : d1_sink_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address2_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }
  for (auto ase_id : d1_source_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address2_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }

  /* Make sure device 1 sees ASEs configured by device 2 still as IDLE */
  for (auto ase_id : d2_sink_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address1_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }
  for (auto ase_id : d2_source_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address1_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }

  /* Configure QoS for Device 1 */
  uint8_t d1_cig_id = 1;
  uint8_t d1_sink_cis_id = 1;
  for (auto ase_id : d1_sink_ases) {
    TestConfigureQos(ase_id, test_address1_, d1_cig_id, d1_sink_cis_id++);
  }
  /* Make sure bidirectional CISes are handled correctly */
  uint8_t d1_source_cis_id = 1;
  for (auto ase_id : d1_source_ases) {
    TestConfigureQos(ase_id, test_address1_, d1_cig_id, d1_source_cis_id++);
  }

  /* Configure QoS for Device 2 */
  /* Make sure we properly handle second device using same CIG and CIS identifiers */
  uint8_t d2_cig_id = 1;
  uint8_t d2_sink_cis_id = 1;
  for (auto ase_id : d2_sink_ases) {
    TestConfigureQos(ase_id, test_address2_, d2_cig_id, d2_sink_cis_id++);
  }
  /* Make sure bidirectional CISes are handled correctly */
  uint8_t d2_source_cis_id = 1;
  for (auto ase_id : d2_source_ases) {
    TestConfigureQos(ase_id, test_address2_, d2_cig_id, d2_source_cis_id++);
  }

  /* Make sure that the device got their unique configurations */
  ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, d1_sink_ases[0]),
            ascs_callbacks_->OnGetAseState(test_address2_, d2_sink_ases[0]));
  ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, d1_source_ases[0]),
            ascs_callbacks_->OnGetAseState(test_address2_, d2_source_ases[0]));

  /* Make sure device 2 sees ASEs configured by device 1 still as IDLE */
  for (auto ase_id : d1_sink_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address2_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }
  for (auto ase_id : d1_source_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address2_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }

  /* Make sure device 1 sees ASEs configured by device 2 still as IDLE */
  for (auto ase_id : d2_sink_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address1_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }
  for (auto ase_id : d2_source_ases) {
    auto unconfigured = ascs_callbacks_->OnGetAseState(test_address1_, ase_id);
    ASSERT_EQ(unconfigured.state, ascs::AseState::IDLE);
  }
}

TEST_F(AseManagerTest, TwoDevicesConfigureAsesWithDifferentConfigsSameAses) {
  TestInitialize(4, 4);

  EXPECT_CALL(mock_ase_manager_cb_, OnAsesRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet()));
  ascs_callbacks_->OnAscsRegistered(GetSinkAsesAsSet(), GetSourceAsesAsSet());

  EXPECT_CALL(mock_ase_manager_cb_, OnClientConnected(_)).Times(2);
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnDeviceConnected(test_address2_);
  testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);

  // Make sure that both devices try to configure same ASES - this is a correct behavior
  auto const all_sink_ases = GetSinkAses();
  auto const all_source_ases = GetSourceAses();
  std::vector<uint8_t> d1_sink_ases(all_sink_ases.begin(), all_sink_ases.end());
  std::vector<uint8_t> d2_sink_ases(all_sink_ases.begin(), all_sink_ases.end());
  std::vector<uint8_t> d1_source_ases(all_source_ases.begin(), all_source_ases.end());
  std::vector<uint8_t> d2_source_ases(all_source_ases.begin(), all_source_ases.end());

  /* Configure Codec for Device 1 (lower half of all ASEs) */
  for (auto ase_id : d1_sink_ases) {
    TestConfigureCodec(ase_id, test_address1_);
  }
  for (auto ase_id : d1_source_ases) {
    TestConfigureCodec(ase_id, test_address1_);
  }

  /* Configure Codec for Device 2 (upper half of all ASES) */
  for (auto ase_id : d2_sink_ases) {
    TestConfigureCodec(ase_id, test_address2_);
  }
  for (auto ase_id : d2_source_ases) {
    TestConfigureCodec(ase_id, test_address2_);
  }

  /* Make sure that the devices got their unique configurations */
  for (auto sink_ase : d1_sink_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, sink_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, sink_ase));
  }
  for (auto source_ase : d1_source_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, source_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, source_ase));
  }
  for (auto sink_ase : d2_sink_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, sink_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, sink_ase));
  }
  for (auto source_ase : d2_source_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, source_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, source_ase));
  }

  /* Configure QoS for Device 1 */
  uint8_t d1_cig_id = 1;
  uint8_t d1_sink_cis_id = 1;
  for (auto ase_id : d1_sink_ases) {
    TestConfigureQos(ase_id, test_address1_, d1_cig_id, d1_sink_cis_id++);
  }
  /* Make sure bidirectional CISes are handled correctly */
  uint8_t d1_source_cis_id = 1;
  for (auto ase_id : d1_source_ases) {
    TestConfigureQos(ase_id, test_address1_, d1_cig_id, d1_source_cis_id++);
  }

  /* Configure QoS for Device 2 */
  /* Make sure we properly handle second device using same CIG and CIS identifiers */
  uint8_t d2_cig_id = 1;
  uint8_t d2_sink_cis_id = 1;
  for (auto ase_id : d2_sink_ases) {
    TestConfigureQos(ase_id, test_address2_, d2_cig_id, d2_sink_cis_id++);
  }
  /* Make sure bidirectional CISes are handled correctly */
  uint8_t d2_source_cis_id = 1;
  for (auto ase_id : d2_source_ases) {
    TestConfigureQos(ase_id, test_address2_, d2_cig_id, d2_source_cis_id++);
  }

  /* Make sure that the devices got their unique configurations */
  for (auto sink_ase : d1_sink_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, sink_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, sink_ase));
  }
  for (auto source_ase : d1_source_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, source_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, source_ase));
  }
  for (auto sink_ase : d2_sink_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, sink_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, sink_ase));
  }
  for (auto source_ase : d2_source_ases) {
    ASSERT_NE(ascs_callbacks_->OnGetAseState(test_address1_, source_ase),
              ascs_callbacks_->OnGetAseState(test_address2_, source_ase));
  }
}

TEST_F(AseManagerTest, OnCisDisconnected) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // Disconnect CIS and verify
  testDisconnectCis(ase_id, test_address1_, cis_conn_hdl);
}

TEST_F(AseManagerTest, OnDeviceDisconnected) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Verify that the ASE is in CONFIGURED state
  auto codec_config_state = ascs_callbacks_->OnGetAseState(test_address1_, ase_id);
  ASSERT_EQ(codec_config_state.state, ascs::AseState::CODEC_CONFIGURED);

  // Now disconnect the device
  EXPECT_CALL(mock_ase_manager_cb_, OnClientDisconnected(test_address1_));
  ascs_callbacks_->OnDeviceDisconnected(test_address1_);
  sync_main_handler();

  // Verify that the ASE for the disconnected device is back to IDLE state
  auto idle_state = ascs_callbacks_->OnGetAseState(test_address1_, ase_id);
  ASSERT_EQ(idle_state.state, ascs::AseState::IDLE);

  // Also verify that the state machine for this device and ASE is gone.
  ASSERT_EQ(mock_iso_state_machines_by_device_and_ase_id_.count(test_address1_), 0lu);
}

TEST_F(AseManagerTest, Disable) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // Now disable the ASE
  Ascs::AseCtpRequest request = {
          .opcode = ascs::AseCtpOpcode::DISABLE,
          .request_params = std::vector<uint8_t>{ase_id},
  };

  // Expect success response sent via ASCSs control point
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, request);
  sync_main_handler();

  // Verify state is back to QOS_CONFIGURED
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(test_address1_), 0lu);
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).count(ase_id), 0lu);
  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_NE(sm, nullptr);

  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
}

TEST_F(AseManagerTest, ReleaseNoCaching) {
  SetSystemPropertyForTest(kIsPeripheralCachingSupportedProperty, "false");
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // Now release the ASE
  Ascs::AseCtpRequest request = {
          .opcode = ascs::AseCtpOpcode::RELEASE,
          .request_params = std::vector<uint8_t>{ase_id},
  };

  // Expect success response sent via ASCSs control point
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, request);
  sync_main_handler();
  sync_main_handler();

  // Verify state is back to IDLE
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(test_address1_), 0lu);
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).count(ase_id), 0lu);
  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_NE(sm, nullptr);

  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::IDLE);
}

TEST_F(AseManagerTest, OnAseEnableRequestCallback) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);
}

TEST_F(AseManagerTest, OnAseStopTriggeredByDisableAndStopReady) {
  TestInitialize(0, 1);

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({}, {ase_id});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // Now disable the ASE
  Ascs::AseCtpRequest request = {
          .opcode = ascs::AseCtpOpcode::DISABLE,
          .request_params = std::vector<uint8_t>{ase_id},
  };

  // Expect success response sent via ASCSs control point
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, request);
  sync_main_handler();

  // Verify state is back to DISABLING
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(test_address1_), 0lu);
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).count(ase_id), 0lu);
  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_NE(sm, nullptr);
  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::DISABLING);
  testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());

  // Now simulate Receiver Stop Ready to trigger the callback
  Ascs::AseCtpRequest stop_ready_request = {
          .opcode = ascs::AseCtpOpcode::RECEIVER_STOP_READY,
          .request_params = std::vector<uint8_t>{ase_id},
  };

  // Expect the callback to be called
  EXPECT_CALL(mock_ase_manager_cb_, OnAseStopped(test_address1_, ase_id));

  // Expect success response sent via ASCSs control point
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, stop_ready_request);
  sync_main_handler();
}

TEST_F(AseManagerTest, OnAseStreamStartedCallback) {
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Expect the callback to be called
  EXPECT_CALL(mock_ase_manager_cb_, OnAseStreamStarted(test_address1_, ase_id, _));

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);
}

TEST_F(AseManagerTest, OnAseStreamStoppedCallback) {
  SetSystemPropertyForTest(kIsPeripheralCachingSupportedProperty, "false");
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // Now release the ASE
  Ascs::AseCtpRequest request = {
          .opcode = ascs::AseCtpOpcode::RELEASE,
          .request_params = std::vector<uint8_t>{ase_id},
  };

  // Expect the callback to be called
  EXPECT_CALL(mock_ase_manager_cb_, OnAseStopped(test_address1_, ase_id));

  // Expect success response sent via ASCSs control point
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, request);
  sync_main_handler();
  sync_main_handler();

  // Verify state is back to IDLE
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(test_address1_), 0lu);
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).count(ase_id), 0lu);
  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_NE(sm, nullptr);

  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::IDLE);
}

TEST_F(AseManagerTest, AcceptAseEnableRequestAllowed) {
  TestInitialize(0, 1);

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({}, {ase_id});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // The state machine should be in STREAMING state
  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::STREAMING);
}

TEST_F(AseManagerTest, AcceptAseEnableRequestDenied) {
  TestInitialize(0, 1);

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({}, {ase_id});

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Reject the enable request
  Ascs::AseCtpResponse response;
  TestEnable(ase_id, test_address1_, false, &response);

  // Verify the response
  ASSERT_EQ(response.opcode, ascs::AseCtpOpcode::ENABLE);
  ASSERT_NE(response.entries.size(), 0lu);
  ASSERT_EQ(response.entries.at(0).ase_id, ase_id);
  ASSERT_NE(response.entries.at(0).response_code, ascs::AseCtpResponseCode::SUCCESS);
  ASSERT_TRUE(std::holds_alternative<ascs::AseCtpResponseReason>(response.entries.at(0).reason));
}

TEST_F(AseManagerTest, ReleaseWithCaching) {
  SetSystemPropertyForTest(kIsPeripheralCachingSupportedProperty, "true");
  TestInitialize();

  uint8_t ase_id = 1;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});
  ascs_callbacks_->OnDeviceConnected(test_address1_);

  // Start with CONFIG_CODEC
  TestConfigureCodec(ase_id, test_address1_);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Proceed to STREAMING using
  uint16_t cis_conn_hdl = 0x02;
  TestEstablishCis(ase_id, test_address1_, cig_id, cis_id, cis_conn_hdl);

  // Now release the ASE
  Ascs::AseCtpRequest request = {
          .opcode = ascs::AseCtpOpcode::RELEASE,
          .request_params = std::vector<uint8_t>{ase_id},
  };

  // Expect success response sent via ASCSs control point
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, request);

  // FIXME: Need a helper to wait for all the scheduled tasks
  sync_main_handler();
  sync_main_handler();

  // Verify state is back to CODEC_CONFIGURED
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.count(test_address1_), 0lu);
  ASSERT_NE(mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).count(ase_id), 0lu);
  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_NE(sm, nullptr);

  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);
}

TEST_F(AseManagerTest, ConfigureCodecAndVerifyTargetLatency) {
  TestInitialize();

  uint8_t ase_id = 1;
  uint8_t target_latency = 3;

  // Simulate device connection and ASE registration
  ascs_callbacks_->OnDeviceConnected(test_address1_);
  ascs_callbacks_->OnAscsRegistered({ase_id}, {});

  // Configure codec
  ascs::AseCodecConfigurationReq codec_req = {
          .ase_id = ase_id,
          .codec_configuration = {
                  .target_latency = target_latency,
                  .target_phy = 2,
                  .codec_id = le_audio::types::LeAudioCodecIdLc3,
                  .codec_spec_conf = {0x02, ase_id, test_address1_.address[5]},
          }};
  Ascs::AseCtpRequest codec_config_request = {
          .opcode = ascs::AseCtpOpcode::CONFIG_CODEC,
          .request_params = std::vector<ascs::AseCodecConfigurationReq>{codec_req},
  };

  EXPECT_CALL(mock_ase_manager_cb_, OnCodecConfigRequest(test_address1_, _))
          .WillOnce(Return(std::map<uint8_t, std::variant<ascs::AseStateCodecConfiguration,
                                                          std::pair<ascs::AseCtpResponseCode,
                                                                    ascs::AseCtpResponseReason>>>{
                  {ase_id, GetAseCodecConfiguredStateFromRequest(codec_req.codec_configuration)}}));
  EXPECT_CALL(*mock_ascs_, AseCtpRequestResponse(test_address1_, _)).Times(1);
  ascs_callbacks_->OnAseControlPointRequest(test_address1_, codec_config_request);
  sync_main_handler();
  testing::Mock::VerifyAndClearExpectations(mock_ascs_.get());
  testing::Mock::VerifyAndClearExpectations(&mock_ase_manager_cb_);

  auto& sm = mock_iso_state_machines_by_device_and_ase_id_.at(test_address1_).at(ase_id);
  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);
  ASSERT_TRUE(sm->target_latency.has_value());
  ASSERT_EQ(sm->target_latency.value(), target_latency);

  // Proceed to QOS_CONFIGURED using
  uint8_t cig_id = 0x03;
  uint8_t cis_id = 0x04;
  TestConfigureQos(ase_id, test_address1_, cig_id, cis_id);

  // Proceed to ENABLING using
  TestEnable(ase_id, test_address1_);

  // Now establish CIS and verify latency
  uint16_t cis_conn_hdl = 0x02;

  EXPECT_CALL(*mock_iso_app_proxy_, AcceptIncomingCisConnection(_)).Times(1);

  hci::iso_manager::cis_request_evt cis_request_evt;
  cis_request_evt.acl_conn_hdl = GetMockedAclHandleForAddress(test_address1_);
  cis_request_evt.cis_conn_hdl = cis_conn_hdl;
  cis_request_evt.cig_id = cig_id;
  cis_request_evt.cis_id = cis_id;

  AclHandleToMockBtmDevice[cis_request_evt.acl_conn_hdl] =
          BtmDevice{.ble.pseudo_addr = test_address1_};

  cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisRequest,
                                            &cis_request_evt);
  testing::Mock::VerifyAndClearExpectations(mock_iso_app_proxy_);

  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(_, _, _)).Times(1);
  EXPECT_CALL(mock_ase_manager_cb_, OnDecodingIsoChannelParametersUpdated(
                                            _, _, ase_id, cis_conn_hdl, _, _, target_latency, _))
          .Times(1);

  // Inject CIS established event
  ON_CALL(*mock_iso_app_proxy_, HasCisConnected(cis_conn_hdl)).WillByDefault(Return(true));
  hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt;
  cis_establish_evt.status = 0x00;
  cis_establish_evt.cis_conn_hdl = cis_conn_hdl;
  cis_establish_evt.cig_id = cig_id;
  cig_callbacks_to_ase_manager_->OnCisEvent(hci::iso_manager::kIsoEventCisEstablishCmpl,
                                            &cis_establish_evt);

  ase_manager_->OnDecodingSessionReady(test_address1_);
  sync_main_handler();

  ASSERT_EQ(sm->GetStateId(), AscsAseStateMachine::StateId::STREAMING);
}

}  // namespace bluetooth::le_audio::test
