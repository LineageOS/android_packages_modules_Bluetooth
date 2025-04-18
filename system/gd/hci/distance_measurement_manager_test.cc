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
#include "packet/bit_inserter.h"
#include "packet/packet_view.h"
#include "ras/ras_packets.h"

using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::os::fake_timer::fake_timerfd_reset;
using bluetooth::packet::BitInserter;
using testing::_;
using testing::AtLeast;
using testing::Return;
using testing::WithParamInterface;

namespace {
static constexpr auto kTimeout = std::chrono::seconds(1);
static constexpr uint8_t kMaxRetryCounterForCreateConfig = 0x03;
static constexpr uint8_t kMaxRetryCounterForCsEnable = 0x03;
static constexpr uint8_t kConnInterval = 24;
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
  Address HACK_GetLeAddress(uint16_t /*connection_handle*/) override { return target_address_; }

protected:
  void Start() override {}
  void Stop() override {}
  void ListDependencies(ModuleList* /* list */) const override {}

public:
  Address target_address_;
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
  CsRttType rtt_type = CsRttType::RTT_WITH_32_BIT_SOUNDING_SEQUENCE;
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

struct CsSubeventResultEvent {
  uint8_t config_id = 0;                 // 0 to 3
  uint16_t start_acl_conn_event = 1000;  // 0x0000 to 0xFFFF
  uint16_t frequency_compensation = 0;   // 0x58F0(-100ppm) to 0x2710(100ppm) x 0.01ppm
  uint8_t reference_power_level = 0;     // -127dBm to 20dBm
  CsProcedureDoneStatus procedure_done_status = CsProcedureDoneStatus::ALL_RESULTS_COMPLETE;
  CsSubeventDoneStatus subevent_done_status = CsSubeventDoneStatus::ALL_RESULTS_COMPLETE;
  ProcedureAbortReason procedure_abort_reason = ProcedureAbortReason::NO_ABORT;
  SubeventAbortReason subevent_abort_reason = SubeventAbortReason::NO_ABORT;
  uint8_t num_antenna_paths = 2;  //  normal: 0x01 to 0x04, 0x00: no PCT CS step
  std::vector<LeCsResultDataStructure> result_data_structures;
};

struct StartMeasurementParameters {
  Address responder_addr = Address::FromString("12:34:56:78:9a:bc").value();
  Address requester_addr = Address::FromString("bc:9a:78:56:34:12").value();
  uint16_t connection_handle = 64;
  Role req_hci_role = Role::CENTRAL;
  Role resp_hci_role = Role::PERIPHERAL;
  uint16_t interval = 200;  // 200ms
  DistanceMeasurementMethod method = DistanceMeasurementMethod::METHOD_CS;
  // used to override the CsConfigCompleteEvent
  CsMainModeType main_mode_type = CsMainModeType::MODE_2;
  CsRttType rtt_type = CsRttType::RTT_AA_ONLY;
};

struct CsModule {
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

  void Start() {
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

    test_hci_layer_->GetCommand(OpCode::LE_CS_READ_LOCAL_SUPPORTED_CAPABILITIES);

    dm_manager_->RegisterDistanceMeasurementCallbacks(&mock_dm_callbacks_);
  }

  void Stop() {
    fake_registry_.SynchronizeModuleHandler(&DistanceMeasurementManager::Factory,
                                            std::chrono::milliseconds(20));
    fake_registry_.StopAll();
  }

  void sync_client_handler() {
    log::assert_that(thread_.GetReactor()->WaitForIdle(kTimeout),
                     "assert failed: thread_.GetReactor()->WaitForIdle(kTimeout)");
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

  static std::unique_ptr<LeCsSubeventResultBuilder> GetSubeventResultEvent(
          uint16_t connection_handle, uint16_t procedure_counter,
          CsSubeventResultEvent subevent_result) {
    return LeCsSubeventResultBuilder::Create(
            connection_handle, subevent_result.config_id, subevent_result.start_acl_conn_event,
            procedure_counter, subevent_result.frequency_compensation,
            subevent_result.reference_power_level, subevent_result.procedure_done_status,
            subevent_result.subevent_done_status, subevent_result.procedure_abort_reason,
            subevent_result.subevent_abort_reason, subevent_result.num_antenna_paths,
            subevent_result.result_data_structures);
  }

  static std::unique_ptr<LeCsSubeventResultContinueBuilder> GetSubeventResultContinueEvent(
          uint16_t connection_handle, CsSubeventResultEvent subevent_result) {
    return LeCsSubeventResultContinueBuilder::Create(
            connection_handle, subevent_result.config_id, subevent_result.procedure_done_status,
            subevent_result.subevent_done_status, subevent_result.procedure_abort_reason,
            subevent_result.subevent_abort_reason, subevent_result.num_antenna_paths,
            subevent_result.result_data_structures);
  }

  template <typename T>
  static std::vector<uint8_t> GetCsStepData(const T& step_data) {
    static_assert(std::is_base_of<bluetooth::packet::PacketStruct<true>, T>::value,
                  "Constraint failed: Type T must be derived from Base.");
    std::vector<uint8_t> bytes;
    BitInserter bit_inserter(bytes);
    step_data.Serialize(bit_inserter);
    return bytes;
  }

  static std::vector<uint8_t> GetMode0Data(CsRole role) {
    uint8_t packet_quality = 0;  // no error
    uint8_t packet_rssi = 0;     // -127 to 20 dBm
    uint8_t packet_antenna = 1;  // 0x01 to 0x04
    if (role == CsRole::INITIATOR) {
      uint16_t measured_freq_offset = 0;
      return GetCsStepData<LeCsMode0InitatorData>(LeCsMode0InitatorData(
              packet_quality, packet_rssi, packet_antenna, measured_freq_offset));
    }
    // reflector
    return GetCsStepData<LeCsMode0ReflectorData>(
            LeCsMode0ReflectorData(packet_quality, packet_rssi, packet_antenna));
  }

  static std::vector<uint8_t> GetMode2Data(uint8_t num_antenna_path,
                                           uint8_t antenna_permutation_index) {
    uint16_t i_sample = 0x0A;
    uint16_t q_sample = 0x1A;
    uint8_t quality_indicator = 0;
    std::vector<LeCsToneDataWithQuality> tone_data;
    for (int i = 0; i <= num_antenna_path; i++) {
      tone_data.emplace_back(i_sample++, q_sample++, quality_indicator);
    }
    std::vector<uint8_t> mode2_data =
            GetCsStepData<LeCsMode2Data>(LeCsMode2Data(antenna_permutation_index, tone_data));
    // remove the 1st byte of count
    mode2_data.erase(mode2_data.begin());
    return mode2_data;
  }

  static std::vector<uint8_t> GetMode1Data(CsRole cs_role, CsRttType rtt_type) {
    uint8_t packet_quality = 0;  // no error
    uint8_t packet_rssi = 0;     // -127 to +20 dBm
    uint8_t packet_antenna = 1;  // 0x01 to 0x04
    CsPacketNadm nadm = CsPacketNadm::ATTACK_IS_EXTREMELY_UNLIKELY;
    uint16_t toa_tod_initiator = 10;  // x*0.5 nanos
    uint16_t tod_toa_reflector = 10;  // x*0.5 nanos
    LeCsPacketPct packet_pct1(/*i_sample=*/0x0A, /*q_sample=*/0x1A);
    LeCsPacketPct packet_pct2(/*i_sample=*/0x0B, /*q_sample=*/0x1B);
    bool has_packet_pct = false;
    if (rtt_type == CsRttType::RTT_WITH_32_BIT_SOUNDING_SEQUENCE ||
        rtt_type == CsRttType::RTT_WITH_96_BIT_SOUNDING_SEQUENCE) {
      has_packet_pct = true;
    }
    if (cs_role == CsRole::INITIATOR) {
      if (has_packet_pct) {
        return GetCsStepData<LeCsMode1InitatorDataWithPacketPct>(LeCsMode1InitatorDataWithPacketPct(
                packet_quality, nadm, packet_rssi, toa_tod_initiator, packet_antenna, packet_pct1,
                packet_pct2));
      } else {
        return GetCsStepData<LeCsMode1InitatorData>(LeCsMode1InitatorData(
                packet_quality, nadm, packet_rssi, toa_tod_initiator, packet_antenna));
      }
    } else {
      if (has_packet_pct) {
        return GetCsStepData<LeCsMode1ReflectorDataWithPacketPct>(
                LeCsMode1ReflectorDataWithPacketPct(packet_quality, nadm, packet_rssi,
                                                    tod_toa_reflector, packet_antenna, packet_pct1,
                                                    packet_pct2));
      } else {
        return GetCsStepData<LeCsMode1ReflectorData>(LeCsMode1ReflectorData(
                packet_quality, nadm, packet_rssi, tod_toa_reflector, packet_antenna));
      }
    }
  }

  static std::vector<uint8_t> GetMode3Data(uint8_t num_antenna_path,
                                           uint8_t antenna_permutation_index, CsRole cs_role,
                                           CsRttType rtt_type) {
    std::vector<uint8_t> mode3_data;
    std::vector<uint8_t> mode1_data = GetMode1Data(cs_role, rtt_type);
    std::vector<uint8_t> mode2_data = GetMode2Data(num_antenna_path, antenna_permutation_index);

    mode3_data.insert(mode3_data.end(), std::make_move_iterator(mode1_data.begin()),
                      std::make_move_iterator(mode1_data.end()));
    mode3_data.insert(mode3_data.end(), std::make_move_iterator(mode2_data.begin()),
                      std::make_move_iterator(mode2_data.end()));

    return mode3_data;
  }

  static std::vector<LeCsResultDataStructure> GetSubeventMode2Data(CsRole role) {
    std::vector<LeCsResultDataStructure> results;
    uint8_t channel = 1;
    results.emplace_back(0, channel++, GetMode0Data(role));
    // antenna_permutation_index is A1A2
    std::vector<uint8_t> mode2_data = GetMode2Data(
            /*num_antenna_path=*/2, /*antenna_permutation_index=*/0);
    results.emplace_back(2, channel++, mode2_data);
    results.emplace_back(2, channel++, mode2_data);
    return results;
  }

  static std::vector<LeCsResultDataStructure> GetSubeventContinueMode2Data() {
    std::vector<LeCsResultDataStructure> results;
    uint8_t channel = 10;
    // antenna_permutation_index is A1A2
    std::vector<uint8_t> mode2_data = GetMode2Data(
            /*num_antenna_path=*/2, /*antenna_permutation_index=*/0);
    results.emplace_back(2, channel++, mode2_data);
    results.emplace_back(2, channel++, mode2_data);
    return results;
  }

  static std::vector<LeCsResultDataStructure> GetSubeventMode1Data(
          CsRole role, CsRttType rtt_type = CsRttType::RTT_AA_ONLY) {
    std::vector<LeCsResultDataStructure> results;
    uint8_t channel = 1;
    results.emplace_back(0, channel++, GetMode0Data(role));
    std::vector<uint8_t> mode1_data = GetMode1Data(role, rtt_type);
    results.emplace_back(/*mode=*/1, channel++, mode1_data);
    results.emplace_back(/*mode=*/1, channel++, mode1_data);
    return results;
  }

  static std::vector<LeCsResultDataStructure> GetSubeventMode3Data(
          CsRole role, CsRttType rtt_type = CsRttType::RTT_AA_ONLY) {
    std::vector<LeCsResultDataStructure> results;
    uint8_t channel = 1;
    results.emplace_back(0, channel++, GetMode0Data(role));
    std::vector<uint8_t> mode3_data = GetMode3Data(
            /*num_antenna_path=*/2, /*antenna_permutation_index=*/0, role, rtt_type);
    results.emplace_back(/*mode=*/3, channel++, mode3_data);
    results.emplace_back(/*mode=*/3, channel++, mode3_data);
    return results;
  }

  void StartMeasurement(const StartMeasurementParameters& params) {
    dm_manager_->StartDistanceMeasurement(params.responder_addr, params.connection_handle,
                                          params.req_hci_role, params.interval, params.method);
  }

  void ReceivedReadLocalCapabilitiesComplete() {
    CsReadCapabilitiesCompleteEvent read_cs_complete_event;
    read_cs_complete_event.num_antennas_supported = 1;  // make the antenna_paths to be 2;
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
            params.responder_addr, params.connection_handle,
            /*att_handle=*/0,
            /*vendor_specific_data=*/std::vector<hal::VendorSpecificCharacteristic>(),
            /*conn_interval=*/kConnInterval);
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
    cs_config_complete_event.main_mode_type = params.main_mode_type;
    cs_config_complete_event.rtt_type = params.rtt_type;
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

    auto command_view =
            LeCsSetProcedureParametersView::Create(DistanceMeasurementCommandView::Create(
                    test_hci_layer_->GetCommand(OpCode::LE_CS_SET_PROCEDURE_PARAMETERS)));
    EXPECT_EQ(command_view.IsValid(), true);
    auto expected_min_procedure_interval =
            static_cast<uint16_t>(std::round(params.interval / (kConnInterval * 1.25)));
    EXPECT_EQ(command_view.GetMinProcedureInterval(), expected_min_procedure_interval);
    test_hci_layer_->IncomingEvent(LeCsSetProcedureParametersCompleteBuilder::Create(
            /*num_hci_command_packets=*/static_cast<uint8_t>(0xEE), ErrorCode::SUCCESS,
            params.connection_handle));
  }

  void StartMeasurementTillProcedureEnableComplete(const StartMeasurementParameters& params) {
    StartMeasurementTillSetProcedureParameters(params);
    EXPECT_CALL(mock_dm_callbacks_,
                OnDistanceMeasurementStarted(params.responder_addr,
                                             DistanceMeasurementMethod::METHOD_CS));

    CsProcedureEnableCompleteEvent complete_event;
    test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
    test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS, /*num_hci_command_packets=*/0xff));
    test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
            params.connection_handle, Enable::ENABLED, complete_event));
  }

  void RespondTillProcedureEnableComplete(const StartMeasurementParameters& params) {
    ReceivedReadLocalCapabilitiesComplete();
    // ras server connect
    dm_manager_->HandleRasServerConnected(params.requester_addr, params.connection_handle,
                                          params.resp_hci_role);
    // remote capabilities
    CsReadCapabilitiesCompleteEvent read_cs_complete_event;
    test_hci_layer_->IncomingLeMetaEvent(GetRemoteSupportedCapabilitiesCompleteEvent(
            params.connection_handle, read_cs_complete_event));
    // set default settings
    test_hci_layer_->GetCommand(OpCode::LE_CS_SET_DEFAULT_SETTINGS);
    test_hci_layer_->IncomingEvent(LeCsSetDefaultSettingsCompleteBuilder::Create(
            /*num_hci_command_packets=*/static_cast<uint8_t>(0xEE), ErrorCode::SUCCESS,
            params.connection_handle));
    // CS config
    CsConfigCompleteEvent cs_config_complete_event;
    cs_config_complete_event.main_mode_type = params.main_mode_type;
    cs_config_complete_event.rtt_type = params.rtt_type;
    cs_config_complete_event.cs_role = CsRole::REFLECTOR;
    test_hci_layer_->IncomingLeMetaEvent(
            GetConfigCompleteEvent(params.connection_handle, cs_config_complete_event));
    // CS security enable
    test_hci_layer_->IncomingLeMetaEvent(LeCsSecurityEnableCompleteBuilder::Create(
            ErrorCode::SUCCESS, params.connection_handle));
    // CS Procedure Enable
    CsProcedureEnableCompleteEvent complete_event;
    test_hci_layer_->IncomingLeMetaEvent(GetProcedureEnableCompleteEvent(
            params.connection_handle, Enable::ENABLED, complete_event));

    sync_client_handler();
  }
};

class DistanceMeasurementManagerTest : public ::testing::Test {
protected:
  void SetUp() override { cs_requester_.Start(); }

  void TearDown() override { cs_requester_.Stop(); }

protected:
  CsModule cs_requester_;
};

TEST_F(DistanceMeasurementManagerTest, setup_teardown) {
  EXPECT_NE(cs_requester_.mock_ranging_hal_->GetRangingHalCallback(), nullptr);
}

TEST_F(DistanceMeasurementManagerTest, fail_read_local_cs_capabilities) {
  StartMeasurementParameters params;
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  CsReadCapabilitiesCompleteEvent read_cs_complete_event;
  read_cs_complete_event.error_code = ErrorCode::COMMAND_DISALLOWED;
  cs_requester_.test_hci_layer_->IncomingEvent(
          CsModule::GetLocalSupportedCapabilitiesCompleteEvent(read_cs_complete_event));

  cs_requester_.StartMeasurement(params);

  dm_session_future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, ras_remote_not_support) {
  cs_requester_.ReceivedReadLocalCapabilitiesComplete();
  StartMeasurementParameters params;
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(
                      params.responder_addr,
                      DistanceMeasurementErrorCode::REASON_FEATURE_NOT_SUPPORTED_REMOTE,
                      DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  cs_requester_.StartMeasurement(params);
  cs_requester_.dm_manager_->HandleRasClientDisconnectedEvent(
          params.responder_addr, ras::RasDisconnectReason::SERVER_NOT_AVAILABLE);

  dm_session_future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, error_read_remote_cs_caps_command) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillRasConnectedEvent(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES);
  cs_requester_.test_hci_layer_->IncomingEvent(
          LeCsReadRemoteSupportedCapabilitiesStatusBuilder::Create(
                  /*status=*/ErrorCode::COMMAND_DISALLOWED,
                  /*num_hci_command_packets=*/0xff));
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, fail_read_remote_cs_caps_complete) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillRasConnectedEvent(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES);
  CsReadCapabilitiesCompleteEvent read_cs_complete_event;
  read_cs_complete_event.error_code = ErrorCode::COMMAND_DISALLOWED;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(
          CsModule::GetRemoteSupportedCapabilitiesCompleteEvent(params.connection_handle,
                                                                read_cs_complete_event));
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, error_create_config_command) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillReadRemoteCaps(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_CREATE_CONFIG);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsCreateConfigStatusBuilder::Create(
          /*status=*/ErrorCode::COMMAND_DISALLOWED,
          /*num_hci_command_packets=*/0xff));
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, fail_create_config_complete) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillReadRemoteCaps(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  CsConfigCompleteEvent cs_config_complete_event;
  cs_config_complete_event.status = ErrorCode::COMMAND_DISALLOWED;
  for (int i = 0; i <= kMaxRetryCounterForCreateConfig; i++) {
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_CREATE_CONFIG);
    cs_requester_.test_hci_layer_->IncomingLeMetaEvent(
            CsModule::GetConfigCompleteEvent(params.connection_handle, cs_config_complete_event));
  }
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, retry_fail_procedure_enable_command) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  for (int i = 0; i <= kMaxRetryCounterForCsEnable; i++) {
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
    cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
            /*status=*/ErrorCode::COMMAND_DISALLOWED,
            /*num_hci_command_packets=*/0xff));
    auto future = cs_requester_.fake_timer_advance(params.interval + 10);
    future.wait_for(kTimeout);
    cs_requester_.sync_client_handler();
  }
  fake_timerfd_reset();
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, retry_fail_procedure_enable_complete) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  CsProcedureEnableCompleteEvent complete_event;
  complete_event.status = ErrorCode::LINK_LAYER_COLLISION;
  for (int i = 0; i <= kMaxRetryCounterForCsEnable; i++) {
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
    cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS,
            /*num_hci_command_packets=*/0xff));
    cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
            params.connection_handle, Enable::ENABLED, complete_event));
    auto future = cs_requester_.fake_timer_advance(params.interval + 10);
    future.wait_for(kTimeout);
    cs_requester_.sync_client_handler();
  }
  fake_timerfd_reset();
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, unexpected_procedure_enable_complete_as_disable) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementErrorCode /*error_code*/,
                           DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS,
          /*num_hci_command_packets=*/0xff));
  CsProcedureEnableCompleteEvent complete_event;
  complete_event.status = ErrorCode::LINK_LAYER_COLLISION;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
          params.connection_handle, Enable::DISABLED, complete_event));

  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, schedule_next_cs_procedures) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);
  EXPECT_CALL(
          cs_requester_.mock_dm_callbacks_,
          OnDistanceMeasurementStarted(params.responder_addr, DistanceMeasurementMethod::METHOD_CS))
          .WillOnce([this](const Address& /*address*/, DistanceMeasurementMethod /*method*/) {
            ASSERT_NE(cs_requester_.dm_session_promise_, nullptr);
            cs_requester_.dm_session_promise_->set_value();
            cs_requester_.dm_session_promise_.reset();
          });

  CsProcedureEnableCompleteEvent complete_event;
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS, /*num_hci_command_packets=*/0xff));
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
          params.connection_handle, Enable::ENABLED, complete_event));
  uint16_t procedure_counter = 0;
  CsSubeventResultEvent subevent_result;
  for (int i = 0; i < 4; i++) {
    cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
            params.connection_handle, procedure_counter, subevent_result));
    procedure_counter += 1;
  }
  subevent_result.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, subevent_result));
  cs_requester_.sync_client_handler();

  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

  subevent_result.procedure_done_status = CsProcedureDoneStatus::ABORTED;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, subevent_result));
  cs_requester_.sync_client_handler();

  CommandView command_view =
          cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  LeCsProcedureEnableView enable_view =
          LeCsProcedureEnableView::Create(DistanceMeasurementCommandView::Create(command_view));

  EXPECT_EQ(enable_view.IsValid(), true);
  EXPECT_EQ(enable_view.GetProcedureEnable(), Enable::ENABLED);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, complete_mode2_procedure) {
  auto req_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result_1;
  req_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1.subevent_done_status = CsSubeventDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1));
  req_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1.subevent_done_status = CsSubeventDoneStatus::ALL_RESULTS_COMPLETE;
  req_subevent_result_1.result_data_structures = CsModule::GetSubeventContinueMode2Data();
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultContinueEvent(
          params.connection_handle, req_subevent_result_1));

  CsSubeventResultEvent req_subevent_result_2;
  req_subevent_result_2.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_2));
  cs_requester_.sync_client_handler();
  // construct responder data
  log::info("start responder");
  CsModule cs_responder;
  cs_responder.Start();
  cs_responder.RespondTillProcedureEnableComplete(params);
  cs_responder.dm_manager_->HandleMtuChanged(params.connection_handle, 517);
  std::vector<uint8_t> segment_data_1;
  EXPECT_CALL(cs_responder.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, procedure_counter, /*is_last=*/true, _))
          .WillOnce([&segment_data_1](Address /*address*/, uint16_t /*procedure_counter*/,
                                      bool /*is_last*/, std::vector<uint8_t> raw_data) {
            segment_data_1 = std::move(raw_data);
          });
  CsSubeventResultEvent resp_subevent_result_1;
  resp_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1));
  CsSubeventResultEvent resp_subevent_result_2;
  resp_subevent_result_2.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_2));
  cs_responder.sync_client_handler();

  // send responder data
  EXPECT_CALL(
          *cs_requester_.mock_ranging_hal_,
          WriteProcedureData(params.connection_handle, CsRole::INITIATOR, _, procedure_counter));
  cs_requester_.dm_manager_->HandleRemoteData(params.responder_addr, params.connection_handle,
                                              segment_data_1);

  cs_requester_.sync_client_handler();
  cs_responder.Stop();
}

enum InvalidRasTestingItem {
  RANGING_DONE_STATUS,
  SUBEVENT_DONE_STATUS,
  RANGING_ABORT_REASON,
  SUBEVENT_ABORT_REASON,
};

struct InvalidRasSegmentParams {
  InvalidRasTestingItem testing_item_;
};

class DistanceMeasurementManagerInvalidRasTest
    : public DistanceMeasurementManagerTest,
      public WithParamInterface<InvalidRasSegmentParams> {
public:
  static void make_invalid_testing_segment(std::vector<uint8_t>& segment_data,
                                           InvalidRasTestingItem testing_item) {
    uint8_t origin_value = 0;
    switch (testing_item) {
      case RANGING_DONE_STATUS:
        segment_data.at(9) = (segment_data.at(9) & 0xF0) | 0x02;
        break;
      case SUBEVENT_DONE_STATUS:
        segment_data.at(9) = (segment_data.at(9) & 0x0F) | 0x10;
        break;
      case RANGING_ABORT_REASON:
        segment_data.at(10) = (segment_data.at(10) & 0xF0) | 0x04;
        break;
      case SUBEVENT_ABORT_REASON:
        segment_data.at(10) = (segment_data.at(10) & 0x0F) | 0x40;
        break;
    }
  }
};

TEST_P(DistanceMeasurementManagerInvalidRasTest, invalid_ras_segment_data) {
  auto req_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result;
  req_subevent_result.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result));
  cs_requester_.sync_client_handler();
  // construct responder data
  log::info("start responder");
  CsModule cs_responder;
  cs_responder.Start();
  cs_responder.RespondTillProcedureEnableComplete(params);
  cs_responder.dm_manager_->HandleMtuChanged(params.connection_handle, 517);
  std::vector<uint8_t> segment_data;
  EXPECT_CALL(cs_responder.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, procedure_counter, /*is_last=*/true, _))
          .WillOnce([&segment_data](Address /*address*/, uint16_t /*procedure_counter*/,
                                    bool /*is_last*/, std::vector<uint8_t> raw_data) {
            segment_data = std::move(raw_data);
          });
  CsSubeventResultEvent resp_subevent_result;
  resp_subevent_result.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result));
  cs_responder.sync_client_handler();

  // send responder data
  make_invalid_testing_segment(segment_data, GetParam().testing_item_);
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS));
  cs_requester_.dm_manager_->HandleRemoteData(params.responder_addr, params.connection_handle,
                                              segment_data);

  cs_requester_.sync_client_handler();
  cs_responder.Stop();
}

INSTANTIATE_TEST_SUITE_P(invalid_ras_segment, DistanceMeasurementManagerInvalidRasTest,
                         ::testing::Values(InvalidRasTestingItem::RANGING_DONE_STATUS,
                                           InvalidRasTestingItem::SUBEVENT_DONE_STATUS,
                                           InvalidRasTestingItem::RANGING_ABORT_REASON,
                                           InvalidRasTestingItem::SUBEVENT_ABORT_REASON));

struct RttTypeParams {
  CsRttType rtt_type;
};

class DistanceMeasurementManagerRttTest : public DistanceMeasurementManagerTest,
                                          public WithParamInterface<RttTypeParams> {};

TEST_P(DistanceMeasurementManagerRttTest, complete_mode1_procedure) {
  auto req_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  params.main_mode_type = CsMainModeType::MODE_1;
  params.rtt_type = GetParam().rtt_type;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result_1;
  req_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1.result_data_structures =
          CsModule::GetSubeventMode1Data(CsRole::INITIATOR, GetParam().rtt_type);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1));
  CsSubeventResultEvent req_subevent_result_2;
  req_subevent_result_2.result_data_structures =
          CsModule::GetSubeventMode1Data(CsRole::INITIATOR, GetParam().rtt_type);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_2));
  cs_requester_.sync_client_handler();
  // construct responder data
  CsModule cs_responder;
  cs_responder.Start();
  cs_responder.RespondTillProcedureEnableComplete(params);
  cs_responder.dm_manager_->HandleMtuChanged(params.connection_handle, 517);
  std::vector<uint8_t> segment_data_1;
  EXPECT_CALL(cs_responder.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, procedure_counter, /*is_last=*/true, _))
          .WillOnce([&segment_data_1](Address /*address*/, uint16_t /*procedure_counter*/,
                                      bool /*is_last*/, std::vector<uint8_t> raw_data) {
            segment_data_1 = std::move(raw_data);
          });
  CsSubeventResultEvent resp_subevent_result_1;
  resp_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1.result_data_structures =
          CsModule::GetSubeventMode1Data(CsRole::REFLECTOR, GetParam().rtt_type);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1));
  CsSubeventResultEvent resp_subevent_result_2;
  resp_subevent_result_2.result_data_structures =
          CsModule::GetSubeventMode1Data(CsRole::REFLECTOR, GetParam().rtt_type);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_2));
  cs_responder.sync_client_handler();

  // send responder data
  EXPECT_CALL(
          *cs_requester_.mock_ranging_hal_,
          WriteProcedureData(params.connection_handle, CsRole::INITIATOR, _, procedure_counter));
  cs_requester_.dm_manager_->HandleRemoteData(params.responder_addr, params.connection_handle,
                                              segment_data_1);

  cs_requester_.sync_client_handler();
  cs_responder.Stop();
}

TEST_P(DistanceMeasurementManagerRttTest, complete_mode3_procedure) {
  auto req_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  params.main_mode_type = CsMainModeType::MODE_3;
  params.rtt_type = GetParam().rtt_type;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result_1;
  req_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1.result_data_structures =
          CsModule::GetSubeventMode3Data(CsRole::INITIATOR, GetParam().rtt_type);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1));
  CsSubeventResultEvent req_subevent_result_2;
  req_subevent_result_2.result_data_structures =
          CsModule::GetSubeventMode3Data(CsRole::INITIATOR, GetParam().rtt_type);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_2));
  cs_requester_.sync_client_handler();
  // construct responder data
  CsModule cs_responder;
  cs_responder.Start();
  cs_responder.RespondTillProcedureEnableComplete(params);
  cs_responder.dm_manager_->HandleMtuChanged(params.connection_handle, 517);
  std::vector<uint8_t> segment_data_1;
  EXPECT_CALL(cs_responder.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, procedure_counter, /*is_last=*/true, _))
          .WillOnce([&segment_data_1](Address /*address*/, uint16_t /*procedure_counter*/,
                                      bool /*is_last*/, std::vector<uint8_t> raw_data) {
            segment_data_1 = std::move(raw_data);
          });
  CsSubeventResultEvent resp_subevent_result_1;
  resp_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1.result_data_structures =
          CsModule::GetSubeventMode3Data(CsRole::REFLECTOR, GetParam().rtt_type);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1));
  CsSubeventResultEvent resp_subevent_result_2;
  resp_subevent_result_2.result_data_structures =
          CsModule::GetSubeventMode3Data(CsRole::REFLECTOR, GetParam().rtt_type);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_2));
  cs_responder.sync_client_handler();

  // send responder data
  EXPECT_CALL(
          *cs_requester_.mock_ranging_hal_,
          WriteProcedureData(params.connection_handle, CsRole::INITIATOR, _, procedure_counter));
  cs_requester_.dm_manager_->HandleRemoteData(params.responder_addr, params.connection_handle,
                                              segment_data_1);

  cs_requester_.sync_client_handler();
  cs_responder.Stop();
}

INSTANTIATE_TEST_SUITE_P(complete_mode1_mode3_procedure, DistanceMeasurementManagerRttTest,
                         ::testing::Values(CsRttType::RTT_WITH_32_BIT_SOUNDING_SEQUENCE,
                                           CsRttType::RTT_WITH_96_BIT_SOUNDING_SEQUENCE,
                                           CsRttType::RTT_AA_ONLY,
                                           CsRttType::RTT_WITH_32_BIT_RANDOM_SEQUENCE));

TEST_F(DistanceMeasurementManagerTest, get_rssi_result_success) {
  cs_requester_.ReceivedReadLocalCapabilitiesComplete();

  StartMeasurementParameters params;
  params.method = DistanceMeasurementMethod::METHOD_RSSI;
  cs_requester_.StartMeasurement(params);

  uint8_t transmit_power_level = 20;
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_READ_REMOTE_TRANSMIT_POWER_LEVEL);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(LeTransmitPowerReportingBuilder::Create(
          ErrorCode::SUCCESS, params.connection_handle, ReportingReason::READ_COMMAND_COMPLETE,
          /*phy=*/1, transmit_power_level, /*transmit_power_level_flag=*/0, /*delta*/ 0));

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStarted(params.responder_addr,
                                           DistanceMeasurementMethod::METHOD_RSSI));
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_SET_TRANSMIT_POWER_REPORTING_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(
          LeSetTransmitPowerReportingEnableCompleteBuilder::Create(
                  /*num_hci_command_packets=*/128, ErrorCode::SUCCESS, params.connection_handle));

  cs_requester_.sync_client_handler();
  uint8_t rssi = 10;  // dBm
  cs_requester_.mock_acl_manager_->target_address_ = params.responder_addr;
  auto future = cs_requester_.fake_timer_advance(params.interval);
  future.wait_for(kTimeout);

  cs_requester_.test_hci_layer_->GetCommand(OpCode::READ_RSSI);
  int8_t rssi_drop_off_at_1m = 41;
  double pow_value = (transmit_power_level - rssi - rssi_drop_off_at_1m) / 20.0;
  double distance = pow(10.0, pow_value);
  EXPECT_CALL(
          cs_requester_.mock_dm_callbacks_,
          OnDistanceMeasurementResult(params.responder_addr, distance * 100, distance * 100, _, _,
                                      _, _, _, _, _, _, _, DistanceMeasurementMethod::METHOD_RSSI));
  cs_requester_.test_hci_layer_->IncomingEvent(ReadRssiCompleteBuilder::Create(
          /*num_hci_command_packets=*/128, ErrorCode::SUCCESS, params.connection_handle, rssi));
  fake_timerfd_reset();
  cs_requester_.sync_client_handler();
}

}  // namespace
}  // namespace hci
}  // namespace bluetooth
