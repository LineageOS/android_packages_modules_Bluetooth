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

#include "hci/distance_measurement_manager.h"

#include <bluetooth/log.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "common/bind.h"
#include "common/strings.h"
#include "hal/ranging_hal.h"
#include "hal/ranging_hal_mock.h"
#include "hci/acl_manager_mock.h"
#include "hci/address.h"
#include "hci/controller.h"
#include "hci/controller_mock.h"
#include "hci/distance_measurement_manager_mock.h"
#include "hci/hci_layer.h"
#include "hci/hci_layer_fake.h"
#include "module.h"
#include "os/fake_timer/fake_timerfd.h"
#include "packet/packet_view.h"
#include "ras/ras_packets.h"

using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::os::fake_timer::fake_timerfd_reset;
using testing::_;
using testing::AtLeast;
using testing::Return;

namespace {
static constexpr auto kTimeout = std::chrono::seconds(1);
static constexpr uint8_t kMaxRetryCounterForCreateConfig = 0x03;
static constexpr uint8_t kMaxRetryCounterForCsEnable = 0x03;
}

namespace bluetooth {
namespace hci {
namespace {
class TestController : public testing::MockController {
protected:
  void Start() override {}
  void Stop() override {}
  void ListDependencies(ModuleList* /* list */) const override {}
};

class TestAclManager : public testing::MockAclManager {
public:
  void AddDeviceToRelaxedConnectionIntervalList(const Address /*address*/) override {}

protected:
  void Start() override {}
  void Stop() override {}
  void ListDependencies(ModuleList* /* list */) const override {}
};

struct CsReadCapabilitiesCompleteEvent {
  ErrorCode error_code = ErrorCode::SUCCESS;
  uint8_t num_config_supported = 4;
  uint16_t max_consecutive_procedures_supported = 0;
  uint8_t num_antennas_supported = 2;
  uint8_t max_antenna_paths_supported = 4;
  CsRoleSupported roles_supported = {/*initiator=*/1, /*reflector=*/1};
  unsigned char modes_supported = {/*mode_3=*/1};
  CsRttCapability rtt_capability = {/*rtt_aa_only_n=*/1, /*rtt_sounding_n=*/1,
                                    /*rtt_random_payload_n=*/1};
  uint8_t rtt_aa_only_n = 1;
  uint8_t rtt_sounding_n = 1;
  uint8_t rtt_random_payload_n = 1;
  CsOptionalNadmSoundingCapability nadm_sounding_capability = {
          /*normalized_attack_detector_metric=*/1};
  CsOptionalNadmRandomCapability nadm_random_capability = {/*normalized_attack_detector_metric=*/1};
  CsOptionalCsSyncPhysSupported cs_sync_phys_supported = {/*le_2m_phy=*/1, /*le_2m_2bt_phy=*/0};
  CsOptionalSubfeaturesSupported subfeatures_supported = {/*no_frequency_actuation_error=*/1,
                                                          /*channel_selection_algorithm=*/1,
                                                          /*phase_based_ranging=*/1};
  CsOptionalTIp1TimesSupported t_ip1_times_supported = {
          /*support_10_microsecond=*/1, /*support_20_microsecond=*/1,
          /*support_30_microsecond=*/1, /*support_40_microsecond=*/1,
          /*support_50_microsecond=*/1, /*support_60_microsecond=*/1,
          /*support_80_microsecond=*/1};
  CsOptionalTIp2TimesSupported t_ip2_times_supported = {
          /*support_10_microsecond=*/1, /*support_20_microsecond=*/1,
          /*support_30_microsecond=*/1,
          /*support_40_microsecond=*/1, /*support_50_microsecond=*/1,
          /*support_60_microsecond=*/1, /*support_80_microsecond=*/1};
  CsOptionalTFcsTimesSupported t_fcs_times_supported = {
          /*support_15_microsecond=*/1,  /*support_20_microsecond=*/1,
          /*support_30_microsecond=*/1,  /*support_40_microsecond=*/1,
          /*support_50_microsecond=*/1,
          /*support_60_microsecond=*/1,  /*support_80_microsecond=*/1,
          /*support_100_microsecond=*/1,
          /*support_120_microsecond=*/1};
  CsOptionalTPmTimesSupported t_pm_times_supported = {/*support_10_microsecond=*/1,
                                                      /*support_20_microsecond=*/1};
  uint8_t t_sw_time_supported = 1;
  uint8_t tx_snr_capability = 1;
};

struct CsConfigCompleteEvent {
  ErrorCode status = ErrorCode::SUCCESS;
  uint8_t config_id = 0;
  CsAction action = CsAction::CONFIG_CREATED;
  CsMainModeType main_mode_type = CsMainModeType::MODE_2;
  CsSubModeType sub_mode_type = CsSubModeType::UNUSED;
  uint8_t min_main_mode_steps = 3;    // 0x02 to 0xFF
  uint8_t max_main_mode_steps = 100;  // 0x02 to 0xFF
  uint8_t main_mode_repetition = 0;   // 0x00 to 0x03
  uint8_t mode_0_steps = 1;           // 0x01 to 0x03
  CsRole cs_role = CsRole::INITIATOR;
  CsRttType rtt_type = CsRttType::RTT_AA_ONLY;
  CsSyncPhy sync_phy = CsSyncPhy::LE_2M_PHY;
  std::array<uint8_t, 10> channel_map = GetChannelMap("1FFFFFFFFFFFFC7FFFFC");
  uint8_t channel_map_repetition = 1;  // 0x01 to 0xFF
  CsChannelSelectionType channel_selection_type = CsChannelSelectionType::TYPE_3C;
  CsCh3cShape ch3c_shape = CsCh3cShape::HAT_SHAPE;
  uint8_t ch3c_jump = 2;      // 0x02 to 0x08
  uint8_t t_ip1_time = 0x0A;  // 0x0A, 0x14, 0x1E, 0x28, 0x32, 0x3C, 0x50, or 0x91
  uint8_t t_ip2_time = 0x0A;  // 0x0A, 0x14, 0x1E, 0x28, 0x32, 0x3C, 0x50, or 0x91
  uint8_t t_fcs_time = 0x0F;  // 0x0F, 0x14, 0x1E, 0x28, 0x32, 0x3C, 0x50, 0x64, 0x78, or 0x96
  uint8_t t_pm_time = 0x0A;   // 0x0A, 0x14, or 0x28

  static const std::array<uint8_t, 10> GetChannelMap(const std::string& hex_string) {
    assert(hex_stinrg.length() == 20);
    auto channel_vector = common::FromHexString(hex_string);
    std::array<uint8_t, 10> channel_map{};
    std::copy(channel_vector->begin(), channel_vector->end(), channel_map.begin());
    std::reverse(channel_map.begin(), channel_map.end());
    return channel_map;
  }
};

struct CsProcedureEnableCompleteEvent {
  ErrorCode status = ErrorCode::SUCCESS;
  uint8_t config_id = 0;
  uint8_t tone_antenna_config_selection = 0;
  uint8_t selected_tx_power = 0;    // -127 to 20 dBm
  uint32_t subevent_len = 2500;     // 1250us to 4s
  uint8_t subevents_per_event = 1;  // 0x01 to 0x20
  uint16_t subevent_interval = 1;   // N x 0.625ms
  uint16_t event_interval = 0;      // number of acl conn interval
  uint16_t procedure_interval = 2;  // number of acl conn interval
  uint16_t procedure_count = 5;     // 0x0001 to 0xFFFF
  uint16_t max_procedure_len = 10;  // N x 0.625 ms
};

struct StartMeasurementParameters {
  Address remote_address = Address::FromString("12:34:56:78:9a:bc").value();
  uint16_t connection_handle = 64;
  Role local_hci_role = Role::CENTRAL;
  uint16_t interval = 200;  // 200ms
  DistanceMeasurementMethod method = DistanceMeasurementMethod::METHOD_CS;
};

class DistanceMeasurementManagerTest : public ::testing::Test {
protected:
  void SetUp() override {
    test_hci_layer_ = new HciLayerFake;                    // Ownership is transferred to registry
    mock_controller_ = new TestController;                 // Ownership is transferred to registry
    mock_ranging_hal_ = new hal::testing::MockRangingHal;  // Ownership is transferred to registry
    mock_acl_manager_ = new TestAclManager;                // Ownership is transferred to registry
    fake_registry_.InjectTestModule(&hal::RangingHal::Factory, mock_ranging_hal_);
    fake_registry_.InjectTestModule(&Controller::Factory, mock_controller_);
    fake_registry_.InjectTestModule(&HciLayer::Factory, test_hci_layer_);
    fake_registry_.InjectTestModule(&AclManager::Factory, mock_acl_manager_);

    client_handler_ = fake_registry_.GetTestModuleHandler(&HciLayer::Factory);
    ASSERT_NE(client_handler_, nullptr);

    EXPECT_CALL(*mock_controller_, SupportsBleChannelSounding()).WillOnce(Return(true));
    EXPECT_CALL(*mock_ranging_hal_, IsBound()).Times(AtLeast(1)).WillRepeatedly(Return(true));
    EXPECT_CALL(*mock_ranging_hal_, GetRangingHalVersion).WillRepeatedly(Return(hal::V_2));

    handler_ = fake_registry_.GetTestHandler();
    dm_manager_ = fake_registry_.Start<DistanceMeasurementManager>(&thread_, handler_);

    dm_manager_->RegisterDistanceMeasurementCallbacks(&mock_dm_callbacks_);
  }

  void TearDown() override {
    fake_registry_.SynchronizeModuleHandler(&DistanceMeasurementManager::Factory,
                                            std::chrono::milliseconds(20));
    fake_registry_.StopAll();
  }

  std::future<void> GetDmSessionFuture() {
    log::assert_that(dm_session_promise_ == nullptr, "Promises promises ... Only one at a time");
    dm_session_promise_ = std::make_unique<std::promise<void>>();
    return dm_session_promise_->get_future();
  }

  std::future<void> fake_timer_advance(uint64_t ms) {
    std::promise<void> promise;
    auto future = promise.get_future();
    handler_->Post(common::BindOnce(
            [](std::promise<void> promise, uint64_t ms) {
              fake_timerfd_advance(ms);
              promise.set_value();
            },
            common::Passed(std::move(promise)), ms));

    return future;
  }

  void sync_client_handler() {
    log::assert_that(thread_.GetReactor()->WaitForIdle(kTimeout),
                     "assert failed: thread_.GetReactor()->WaitForIdle(kTimeout)");
  }

  static std::unique_ptr<LeCsReadLocalSupportedCapabilitiesCompleteBuilder>
  GetLocalSupportedCapabilitiesCompleteEvent(
          const CsReadCapabilitiesCompleteEvent& cs_cap_complete_event) {
    return LeCsReadLocalSupportedCapabilitiesCompleteBuilder::Create(
            /*num_hci_command_packets=*/0xFF, cs_cap_complete_event.error_code,
            cs_cap_complete_event.num_config_supported,
            cs_cap_complete_event.max_consecutive_procedures_supported,
            cs_cap_complete_event.num_antennas_supported,
            cs_cap_complete_event.max_antenna_paths_supported,
            cs_cap_complete_event.roles_supported, cs_cap_complete_event.modes_supported,
            cs_cap_complete_event.rtt_capability, cs_cap_complete_event.rtt_aa_only_n,
            cs_cap_complete_event.rtt_sounding_n, cs_cap_complete_event.rtt_random_payload_n,
            cs_cap_complete_event.nadm_sounding_capability,
            cs_cap_complete_event.nadm_random_capability,
            cs_cap_complete_event.cs_sync_phys_supported,
            cs_cap_complete_event.subfeatures_supported,
            cs_cap_complete_event.t_ip1_times_supported,
            cs_cap_complete_event.t_ip2_times_supported,
            cs_cap_complete_event.t_fcs_times_supported, cs_cap_complete_event.t_pm_times_supported,
            cs_cap_complete_event.t_sw_time_supported, cs_cap_complete_event.tx_snr_capability);
  }

  static std::unique_ptr<LeCsReadRemoteSupportedCapabilitiesCompleteBuilder>
  GetRemoteSupportedCapabilitiesCompleteEvent(
          uint16_t connection_handle,
          const CsReadCapabilitiesCompleteEvent& cs_cap_complete_event) {
    return LeCsReadRemoteSupportedCapabilitiesCompleteBuilder::Create(
            cs_cap_complete_event.error_code, connection_handle,
            cs_cap_complete_event.num_config_supported,
            cs_cap_complete_event.max_consecutive_procedures_supported,
            cs_cap_complete_event.num_antennas_supported,
            cs_cap_complete_event.max_antenna_paths_supported,
            cs_cap_complete_event.roles_supported, cs_cap_complete_event.modes_supported,
            cs_cap_complete_event.rtt_capability, cs_cap_complete_event.rtt_aa_only_n,
            cs_cap_complete_event.rtt_sounding_n, cs_cap_complete_event.rtt_random_payload_n,
            cs_cap_complete_event.nadm_sounding_capability,
            cs_cap_complete_event.nadm_random_capability,
            cs_cap_complete_event.cs_sync_phys_supported,
            cs_cap_complete_event.subfeatures_supported,
            cs_cap_complete_event.t_ip1_times_supported,
            cs_cap_complete_event.t_ip2_times_supported,
            cs_cap_complete_event.t_fcs_times_supported, cs_cap_complete_event.t_pm_times_supported,
            cs_cap_complete_event.t_sw_time_supported, cs_cap_complete_event.tx_snr_capability);
  }

  static std::unique_ptr<LeCsConfigCompleteBuilder> GetConfigCompleteEvent(
          uint16_t connection_handle, CsConfigCompleteEvent complete_event) {
    return LeCsConfigCompleteBuilder::Create(
            complete_event.status, connection_handle, complete_event.config_id,
            complete_event.action, complete_event.main_mode_type, complete_event.sub_mode_type,
            complete_event.min_main_mode_steps, complete_event.max_main_mode_steps,
            complete_event.main_mode_repetition, complete_event.mode_0_steps,
            complete_event.cs_role, complete_event.rtt_type, complete_event.sync_phy,
            complete_event.channel_map, complete_event.channel_map_repetition,
            complete_event.channel_selection_type, complete_event.ch3c_shape,
            complete_event.ch3c_jump, complete_event.t_ip1_time, complete_event.t_ip2_time,
            complete_event.t_fcs_time, complete_event.t_pm_time);
  }

  static std::unique_ptr<LeCsProcedureEnableCompleteBuilder> GetProcedureEnableCompleteEvent(
          uint16_t connection_handle, Enable enable,
          CsProcedureEnableCompleteEvent complete_event) {
    return LeCsProcedureEnableCompleteBuilder::Create(
            complete_event.status, connection_handle, complete_event.config_id, enable,
            complete_event.tone_antenna_config_selection, complete_event.selected_tx_power,
            complete_event.subevent_len, complete_event.subevents_per_event,
            complete_event.subevent_interval, complete_event.event_interval,
            complete_event.procedure_interval, complete_event.procedure_count,
            complete_event.max_procedure_len);
  }

  void StartMeasurement(const StartMeasurementParameters& params) {
    dm_manager_->StartDistanceMeasurement(params.remote_address, params.connection_handle,
                                          params.local_hci_role, params.interval, params.method);
  }

  void ReceivedReadLocalCapabilitiesComplete() {
    CsReadCapabilitiesCompleteEvent read_cs_complete_event;
    test_hci_layer_->IncomingEvent(
            GetLocalSupportedCapabilitiesCompleteEvent(read_cs_complete_event));
  }

  void StartMeasurementTillRasConnectedEvent(const StartMeasurementParameters& params) {
    ReceivedReadLocalCapabilitiesComplete();
    EXPECT_CALL(*mock_ranging_hal_, OpenSession(_, _, _))
            .WillOnce([this](uint16_t connection_handle, uint16_t /*att_handle*/,
                             const std::vector<hal::VendorSpecificCharacteristic>&
                                     vendor_specific_data) {
              mock_ranging_hal_->GetRangingHalCallback()->OnOpened(connection_handle,
                                                                   vendor_specific_data);
            });
    StartMeasurement(params);
    dm_manager_->HandleRasClientConnectedEvent(
            params.remote_address, params.connection_handle,
            /*att_handle=*/0,
            /*vendor_specific_data=*/std::vector<hal::VendorSpecificCharacteristic>(),
            /*conn_interval=*/24);
  }

  void StartMeasurementTillReadRemoteCaps(const StartMeasurementParameters& params) {
    StartMeasurementTillRasConnectedEvent(params);

    test_hci_layer_->GetCommand(OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES);
    CsReadCapabilitiesCompleteEvent read_cs_complete_event;
    test_hci_layer_->IncomingEvent(LeCsReadRemoteSupportedCapabilitiesStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS,
            /*num_hci_command_packets=*/0xFF));
    test_hci_layer_->IncomingLeMetaEvent(GetRemoteSupportedCapabilitiesCompleteEvent(
            params.connection_handle, read_cs_complete_event));

    test_hci_layer_->GetCommand(OpCode::LE_CS_SET_DEFAULT_SETTINGS);
    test_hci_layer_->IncomingEvent(LeCsSetDefaultSettingsCompleteBuilder::Create(
            /*num_hci_command_packets=*/static_cast<uint8_t>(0xEE), ErrorCode::SUCCESS,
            params.connection_handle));
  }

  void StartMeasurementTillCreateConfig(const StartMeasurementParameters& params) {
    StartMeasurementTillReadRemoteCaps(params);

    CsConfigCompleteEvent cs_config_complete_event;
    test_hci_layer_->GetCommand(OpCode::LE_CS_CREATE_CONFIG);
    test_hci_layer_->IncomingEvent(LeCsCreateConfigStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS,
            /*num_hci_command_packets=*/0xFF));
    test_hci_layer_->IncomingLeMetaEvent(
            GetConfigCompleteEvent(params.connection_handle, cs_config_complete_event));
  }

  void StartMeasurementTillSecurityEnable(const StartMeasurementParameters& params) {
    StartMeasurementTillCreateConfig(params);

    test_hci_layer_->GetCommand(OpCode::LE_CS_SECURITY_ENABLE);
    test_hci_layer_->IncomingEvent(LeCsSecurityEnableStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS,
            /*num_hci_command_packets=*/0xFF));
    test_hci_layer_->IncomingLeMetaEvent(LeCsSecurityEnableCompleteBuilder::Create(
            ErrorCode::SUCCESS, params.connection_handle));
  }

  void StartMeasurementTillSetProcedureParameters(const StartMeasurementParameters& params) {
    StartMeasurementTillSecurityEnable(params);

    test_hci_layer_->GetCommand(OpCode::LE_CS_SET_PROCEDURE_PARAMETERS);
    test_hci_layer_->IncomingEvent(LeCsSetProcedureParametersCompleteBuilder::Create(
            /*num_hci_command_packets=*/static_cast<uint8_t>(0xEE), ErrorCode::SUCCESS,
            params.connection_handle));
  }

protected:
  TestModuleRegistry fake_registry_;
  HciLayerFake* test_hci_layer_ = nullptr;
  TestController* mock_controller_ = nullptr;
  TestAclManager* mock_acl_manager_ = nullptr;
  hal::testing::MockRangingHal* mock_ranging_hal_ = nullptr;
  os::Thread& thread_ = fake_registry_.GetTestThread();
  os::Handler* client_handler_ = nullptr;
  os::Handler* handler_ = nullptr;

  DistanceMeasurementManager* dm_manager_ = nullptr;
  testing::MockDistanceMeasurementCallbacks mock_dm_callbacks_;
  std::unique_ptr<std::promise<void>> dm_session_promise_;
};

TEST_F(DistanceMeasurementManagerTest, setup_teardown) {
  EXPECT_NE(mock_ranging_hal_->GetRangingHalCallback(), nullptr);
}

TEST_F(DistanceMeasurementManagerTest, fail_read_local_cs_capabilities) {
  StartMeasurementParameters params;
  auto dm_session_future = GetDmSessionFuture();
  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  CsReadCapabilitiesCompleteEvent read_cs_complete_event;
  read_cs_complete_event.error_code = ErrorCode::COMMAND_DISALLOWED;
  test_hci_layer_->IncomingEvent(
          GetLocalSupportedCapabilitiesCompleteEvent(read_cs_complete_event));

  StartMeasurement(params);

  dm_session_future.wait_for(kTimeout);
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, ras_remote_not_support) {
  ReceivedReadLocalCapabilitiesComplete();
  StartMeasurementParameters params;
  auto dm_session_future = GetDmSessionFuture();
  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(
                      params.remote_address,
                      DistanceMeasurementErrorCode::REASON_FEATURE_NOT_SUPPORTED_REMOTE,
                      DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  StartMeasurement(params);
  dm_manager_->HandleRasClientDisconnectedEvent(params.remote_address,
                                                ras::RasDisconnectReason::SERVER_NOT_AVAILABLE);

  dm_session_future.wait_for(kTimeout);
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, error_read_remote_cs_caps_command) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillRasConnectedEvent(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  test_hci_layer_->GetCommand(OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES);
  test_hci_layer_->IncomingEvent(LeCsReadRemoteSupportedCapabilitiesStatusBuilder::Create(
          /*status=*/ErrorCode::COMMAND_DISALLOWED,
          /*num_hci_command_packets=*/0xff));
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, fail_read_remote_cs_caps_complete) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillRasConnectedEvent(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  test_hci_layer_->GetCommand(OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES);
  CsReadCapabilitiesCompleteEvent read_cs_complete_event;
  read_cs_complete_event.error_code = ErrorCode::COMMAND_DISALLOWED;
  test_hci_layer_->IncomingLeMetaEvent(GetRemoteSupportedCapabilitiesCompleteEvent(
          params.connection_handle, read_cs_complete_event));
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, error_create_config_command) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillReadRemoteCaps(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  test_hci_layer_->GetCommand(OpCode::LE_CS_CREATE_CONFIG);
  test_hci_layer_->IncomingEvent(LeCsCreateConfigStatusBuilder::Create(
          /*status=*/ErrorCode::COMMAND_DISALLOWED,
          /*num_hci_command_packets=*/0xff));
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, fail_create_config_complete) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillReadRemoteCaps(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  CsConfigCompleteEvent cs_config_complete_event;
  cs_config_complete_event.status = ErrorCode::COMMAND_DISALLOWED;
  for (int i = 0; i <= kMaxRetryCounterForCreateConfig; i++) {
    test_hci_layer_->GetCommand(OpCode::LE_CS_CREATE_CONFIG);
    test_hci_layer_->IncomingLeMetaEvent(
            GetConfigCompleteEvent(params.connection_handle, cs_config_complete_event));
  }
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, retry_fail_procedure_enable_command) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillSetProcedureParameters(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  for (int i = 0; i <= kMaxRetryCounterForCsEnable; i++) {
    test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
    test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
            /*status=*/ErrorCode::COMMAND_DISALLOWED,
            /*num_hci_command_packets=*/0xff));
    auto future = fake_timer_advance(params.interval + 10);
    future.wait_for(kTimeout);
    sync_client_handler();
  }
  fake_timerfd_reset();
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, retry_fail_procedure_enable_complete) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillSetProcedureParameters(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  CsProcedureEnableCompleteEvent complete_event;
  complete_event.status = ErrorCode::LINK_LAYER_COLLISION;
  for (int i = 0; i <= kMaxRetryCounterForCsEnable; i++) {
    test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
    test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS,
            /*num_hci_command_packets=*/0xff));
    test_hci_layer_->IncomingLeMetaEvent(GetProcedureEnableCompleteEvent(
            params.connection_handle, Enable::ENABLED, complete_event));
    auto future = fake_timer_advance(params.interval + 10);
    future.wait_for(kTimeout);
    sync_client_handler();
  }
  fake_timerfd_reset();
  sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, unexpected_procedure_enable_complete_as_disable) {
  auto dm_session_future = GetDmSessionFuture();
  StartMeasurementParameters params;
  StartMeasurementTillSetProcedureParameters(params);

  EXPECT_CALL(mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.remote_address,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(dm_session_promise_, nullptr);
            dm_session_promise_->set_value();
            dm_session_promise_.reset();
          });

  test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS,
          /*num_hci_command_packets=*/0xff));
  CsProcedureEnableCompleteEvent complete_event;
  complete_event.status = ErrorCode::LINK_LAYER_COLLISION;
  test_hci_layer_->IncomingLeMetaEvent(GetProcedureEnableCompleteEvent(
          params.connection_handle, Enable::DISABLED, complete_event));

  sync_client_handler();
}

}  // namespace
}  // namespace hci
}  // namespace bluetooth
