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

#include "hci/distance_measurement_manager_impl.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <string>
#include <vector>

#include "hal/ranging_hal.h"
#include "hal/ranging_hal_mock.h"
#include "hci/acl_manager/acl_manager_le_mock.h"
#include "hci/address.h"
#include "hci/controller_mock.h"
#include "hci/distance_measurement_manager_mock.h"
#include "hci/hci_layer_fake.h"
#include "metrics/mock/metrics_mock.h"
#include "os/fake_timer/fake_timerfd.h"
#include "packet/bit_inserter.h"
#include "packet/packet_view.h"
#include "ras/ras_packets.h"

using android::bluetooth::ChannelSoundingStopReason;
using bluetooth::hal::RangingSessionType;
using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::os::fake_timer::fake_timerfd_reset;
using bluetooth::packet::BitInserter;
using testing::_;
using testing::AtLeast;
using testing::Return;
using testing::Sequence;
using testing::Test;
using testing::TestParamInfo;
using testing::Values;
using testing::WithParamInterface;

namespace {
static constexpr auto kTimeout = std::chrono::seconds(1);
static constexpr uint8_t kMaxRetryCounterForReadRemoteCapability = 0x03;
static constexpr uint8_t kMaxRetryCounterForCreateConfig = 0x03;
static constexpr uint8_t kMaxRetryCounterForSetProcedureParameter = 0x0a;
static constexpr uint8_t kMaxRetryCounterForCsEnable = 0x03;
static constexpr uint8_t kConnInterval = 24;
static constexpr uint16_t kMinProcedureInterval = 0x01;
static constexpr uint16_t kCommandRetryIntervalMs = 300;
// These are standalone constants from the original implementation file,
// needed for validating the test expectations.
static constexpr int kInvalidAzimuthAngleDegree = -1;
static constexpr int kInvalidAltitudeAngleDegree = -91;
static constexpr uint8_t KPacketNadmAttackUnlikely = 0x02;
}  // namespace

namespace bluetooth {
namespace hci {
namespace {

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
  DistanceMeasurementSightType sight_type = DistanceMeasurementSightType::SIGHT_TYPE_UNKNOWN;
  DistanceMeasurementLocationType location_type =
          DistanceMeasurementLocationType::LOCATION_TYPE_UNKNOWN;
  // used to override the CsConfigCompleteEvent
  CsMainModeType main_mode_type = CsMainModeType::MODE_2;
  CsRttType rtt_type = CsRttType::RTT_AA_ONLY;
};

struct CsModule {
  os::Thread* thread_ = nullptr;
  os::Handler* client_handler_ = nullptr;
  std::unique_ptr<HciLayerFake> test_hci_layer_ = nullptr;
  std::unique_ptr<testing::MockController> mock_controller_ = nullptr;
  std::unique_ptr<testing::MockAclManager> mock_acl_manager_ = nullptr;
  std::unique_ptr<hal::testing::MockRangingHal> mock_ranging_hal_ = nullptr;

  DistanceMeasurementManagerImpl* dm_manager_ = nullptr;
  testing::MockDistanceMeasurementCallbacks mock_dm_callbacks_;
  std::unique_ptr<std::promise<void>> dm_session_promise_;
  bool local_capabilities_complete_done_ = false;
  bool remote_capabilities_complete_done_ = false;

  void Start() {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);
    ASSERT_NE(client_handler_, nullptr);

    mock_controller_ = std::make_unique<testing::MockController>();
    mock_ranging_hal_ = std::make_unique<hal::testing::MockRangingHal>();
    mock_acl_manager_ = std::make_unique<testing::MockAclManager>();

    EXPECT_CALL(*mock_controller_, SupportsBleChannelSounding()).WillOnce(Return(true));
    EXPECT_CALL(*mock_ranging_hal_, IsBound()).Times(AtLeast(1)).WillRepeatedly(Return(true));
    EXPECT_CALL(*mock_ranging_hal_, GetRangingHalVersion).WillRepeatedly(Return(hal::V_2));

    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    dm_manager_ = new DistanceMeasurementManagerImpl(
            client_handler_, test_hci_layer_.get(), mock_controller_.get(), mock_acl_manager_.get(),
            mock_ranging_hal_.get());

    test_hci_layer_->GetCommand(OpCode::LE_CS_READ_LOCAL_SUPPORTED_CAPABILITIES);

    dm_manager_->RegisterDistanceMeasurementCallbacks(&mock_dm_callbacks_);
  }

  void Stop() {
    client_handler_->Synchronize(std::chrono::milliseconds(20));

    client_handler_->Clear();
    client_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);

    test_hci_layer_.reset();

    delete client_handler_;
    delete thread_;
  }

  void sync_client_handler() {
    log::assert_that(thread_->GetReactor()->WaitForIdle(kTimeout),
                     "assert failed: thread_->GetReactor()->WaitForIdle(kTimeout)");
  }

  std::future<void> GetDmSessionFuture() {
    log::assert_that(dm_session_promise_ == nullptr, "Promises promises ... Only one at a time");
    dm_session_promise_ = std::make_unique<std::promise<void>>();
    return dm_session_promise_->get_future();
  }

  std::future<void> fake_timer_advance(uint64_t ms) {
    std::promise<void> promise;
    auto future = promise.get_future();
    client_handler_->Post(base::BindOnce(
            [](std::promise<void> promise, uint64_t ms) {
              fake_timerfd_advance(ms);
              promise.set_value();
            },
            base::Passed(std::move(promise)), ms));

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
      return GetCsStepData<LeCsMode0InitiatorData>(LeCsMode0InitiatorData(
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
        return GetCsStepData<LeCsMode1InitiatorDataWithPacketPct>(
                LeCsMode1InitiatorDataWithPacketPct(packet_quality, nadm, packet_rssi,
                                                    toa_tod_initiator, packet_antenna, packet_pct1,
                                                    packet_pct2));
      } else {
        return GetCsStepData<LeCsMode1InitiatorData>(LeCsMode1InitiatorData(
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

  static std::vector<LeCsResultDataStructure> GetInvalidSubeventMode2Data(CsRole role) {
    std::vector<LeCsResultDataStructure> results;
    uint8_t channel = 1;
    results.emplace_back(0, channel++, GetMode0Data(role));
    // Invalid antenna_permutation_index
    std::vector<uint8_t> mode2_data = GetMode2Data(
            /*num_antenna_path=*/2, /*antenna_permutation_index=*/19);
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
    dm_manager_->StartDistanceMeasurement(
            /*app_uid=*/100, params.responder_addr, params.connection_handle, params.req_hci_role,
            params.interval, params.method, params.sight_type, params.location_type);
  }

  void ReceivedReadLocalCapabilitiesComplete() {
    if (local_capabilities_complete_done_) {
      return;
    }
    CsReadCapabilitiesCompleteEvent read_cs_complete_event;
    read_cs_complete_event.num_antennas_supported = 1;  // make the antenna_paths to be 2;
    test_hci_layer_->IncomingEvent(
            GetLocalSupportedCapabilitiesCompleteEvent(read_cs_complete_event));
    local_capabilities_complete_done_ = true;
  }

  void StartMeasurementTillRasConnectedEvent(const StartMeasurementParameters& params) {
    ReceivedReadLocalCapabilitiesComplete();
    EXPECT_CALL(*mock_ranging_hal_, OpenSession(_, _, _, _, _))
            .WillOnce([this](uint16_t connection_handle, uint16_t /*att_handle*/,
                             const std::vector<hal::VendorSpecificCharacteristic>&
                                     vendor_specific_data,
                             uint8_t /* sight_type */, uint8_t /* location_type */) {
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
    remote_capabilities_complete_done_ = true;
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
    dm_manager_->HandleConnIntervalUpdated(params.responder_addr, params.connection_handle,
                                           kConnInterval);
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
                                             DistanceMeasurementMethod::METHOD_CS))
            .RetiresOnSaturation();

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
    // for back2back case, if the read_remote_caps is done for requester, when it works as
    // responder, it don't get the event, as controller had cached it.
    if (!remote_capabilities_complete_done_) {
      CsReadCapabilitiesCompleteEvent read_cs_complete_event;
      test_hci_layer_->IncomingLeMetaEvent(GetRemoteSupportedCapabilitiesCompleteEvent(
              params.connection_handle, read_cs_complete_event));
      // set default settings
      test_hci_layer_->GetCommand(OpCode::LE_CS_SET_DEFAULT_SETTINGS);
      test_hci_layer_->IncomingEvent(LeCsSetDefaultSettingsCompleteBuilder::Create(
              /*num_hci_command_packets=*/static_cast<uint8_t>(0xEE), ErrorCode::SUCCESS,
              params.connection_handle));
    }
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

class DistanceMeasurementManagerTest : public Test {
protected:
  void SetUp() override {
    metrics_ = std::make_shared<metrics::MockMetrics>();
    metrics::MockMetrics::SetInstance(metrics_);
    cs_requester_.Start();
  }

  void TearDown() override {
    cs_requester_.Stop();
    metrics::MockMetrics::SetInstance(nullptr);
    metrics_ = nullptr;
  }

protected:
  CsModule cs_requester_;
  std::shared_ptr<metrics::MockMetrics> metrics_;
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

  EXPECT_CALL(*metrics_,
              LogMetricsChannelSoundingRequesterSessionReported(
                      _, _, _, _, ChannelSoundingStopReason::REASON_RAS_REMOTE_NOT_SUPPORT, _, _,
                      false, _, _, _));
  cs_requester_.StartMeasurement(params);
  cs_requester_.dm_manager_->HandleRasClientDisconnectedEvent(
          params.responder_addr, ras::RasDisconnectReason::SERVER_NOT_AVAILABLE);

  dm_session_future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, ras_client_disconnect_after_session_stopped) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  EXPECT_CALL(*metrics_, LogMetricsChannelSoundingRequesterSessionReported(
                                 _, _, _, _, ChannelSoundingStopReason::REASON_LOCAL_APP_REQUEST, _,
                                 _, _, _, _, _));
  cs_requester_.dm_manager_->StopDistanceMeasurement(params.responder_addr,
                                                     params.connection_handle, METHOD_CS);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_, OnDistanceMeasurementStopped(_, _, _)).Times(0);
  EXPECT_CALL(*metrics_, LogMetricsChannelSoundingRequesterSessionReported(
                                 _, _, _, _, ChannelSoundingStopReason::REASON_LE_DISCONNECT, _, _,
                                 _, _, _, _))
          .Times(0);

  cs_requester_.dm_manager_->HandleRasClientDisconnectedEvent(
          params.responder_addr, ras::RasDisconnectReason::GATT_DISCONNECT);
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

TEST_F(DistanceMeasurementManagerTest, fail_read_remote_cs_caps_complete_with_retry) {
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

  CsReadCapabilitiesCompleteEvent read_cs_complete_event;
  read_cs_complete_event.error_code = ErrorCode::COMMAND_DISALLOWED;
  for (int i = 0; i <= kMaxRetryCounterForReadRemoteCapability; i++) {
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES);
    cs_requester_.test_hci_layer_->IncomingLeMetaEvent(
            CsModule::GetRemoteSupportedCapabilitiesCompleteEvent(params.connection_handle,
                                                                  read_cs_complete_event));
    cs_requester_.sync_client_handler();  // Ensure the event above is processed
    if (i < kMaxRetryCounterForReadRemoteCapability) {
      auto future = cs_requester_.fake_timer_advance(kCommandRetryIntervalMs);
      future.wait_for(kTimeout);
    }
  }
  dm_session_future.wait_for(kTimeout);
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
    cs_requester_.sync_client_handler();  // Ensure event is processed, retry timer is set
    if (i < kMaxRetryCounterForCreateConfig) {
      auto future = cs_requester_.fake_timer_advance(kCommandRetryIntervalMs);
      future.wait_for(kTimeout);
    }
  }
  dm_session_future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, fail_create_config_complete_in_wrong_state_no_retry) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);

  CsConfigCompleteEvent cs_config_complete_event;
  cs_config_complete_event.status = ErrorCode::COMMAND_DISALLOWED;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(
          CsModule::GetConfigCompleteEvent(params.connection_handle, cs_config_complete_event));
  cs_requester_.sync_client_handler();

  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();
}

TEST_F(DistanceMeasurementManagerTest, fail_set_procedure_parameters_with_retry) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSecurityEnable(params);

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

  for (int i = 0; i <= kMaxRetryCounterForSetProcedureParameter; i++) {
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_SET_PROCEDURE_PARAMETERS);
    cs_requester_.test_hci_layer_->IncomingEvent(LeCsSetProcedureParametersCompleteBuilder::Create(
            /*num_hci_command_packets=*/static_cast<uint8_t>(0xEE),
            ErrorCode::INVALID_HCI_COMMAND_PARAMETERS, params.connection_handle));
    cs_requester_.sync_client_handler();  // Ensure event is processed, retry timer is set
    if (i < kMaxRetryCounterForSetProcedureParameter) {
      auto future = cs_requester_.fake_timer_advance(kCommandRetryIntervalMs);
      future.wait_for(kTimeout);
    }
  }
  dm_session_future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, fail_security_enable_complete) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillCreateConfig(params);

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

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_SECURITY_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsSecurityEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS,
          /*num_hci_command_packets=*/0xFF));
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(LeCsSecurityEnableCompleteBuilder::Create(
          ErrorCode::LINK_LAYER_COLLISION, params.connection_handle));

  dm_session_future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, unexpected_fail_security_enable_complete) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_, OnDistanceMeasurementStopped(_, _, _)).Times(0);

  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(LeCsSecurityEnableCompleteBuilder::Create(
          ErrorCode::LINK_LAYER_COLLISION, params.connection_handle));

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
    cs_requester_.sync_client_handler();  // Ensure event is processed, retry timer is set
    auto future = cs_requester_.fake_timer_advance(params.interval + 10);
    future.wait_for(kTimeout);
    cs_requester_.sync_client_handler();
  }
  fake_timerfd_reset();
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest,
       no_retry_procedure_enable_command_error_for_stopped_session) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.dm_manager_->StopDistanceMeasurement(
          params.responder_addr, params.connection_handle, DistanceMeasurementMethod::METHOD_CS);
  CommandView command_view =
          cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  LeCsProcedureEnableView enable_view =
          LeCsProcedureEnableView::Create(DistanceMeasurementCommandView::Create(command_view));
  EXPECT_TRUE(enable_view.IsValid());
  // 'DISABLED' triggered by the 'StopDistanceMeasurement'
  EXPECT_EQ(enable_view.GetProcedureEnable(), Enable::DISABLED);

  cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::LINK_LAYER_COLLISION,
          /*num_hci_command_packets=*/0xff));
  auto future = cs_requester_.fake_timer_advance(params.interval + 10);
  future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();

  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

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
            params.connection_handle, i == 0 ? Enable::ENABLED : Enable::DISABLED, complete_event));
    auto future = cs_requester_.fake_timer_advance(params.interval + 10);
    future.wait_for(kTimeout);
    cs_requester_.sync_client_handler();
  }
  fake_timerfd_reset();
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest,
       no_retry_procedure_enable_complete_error_for_stopped_session) {
  auto dm_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS,
          /*num_hci_command_packets=*/0xff));
  cs_requester_.dm_manager_->StopDistanceMeasurement(
          params.responder_addr, params.connection_handle, DistanceMeasurementMethod::METHOD_CS);
  CommandView command_view =
          cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  LeCsProcedureEnableView enable_view =
          LeCsProcedureEnableView::Create(DistanceMeasurementCommandView::Create(command_view));
  EXPECT_TRUE(enable_view.IsValid());
  // 'DISABLED' triggered by the 'StopDistanceMeasurement'
  EXPECT_EQ(enable_view.GetProcedureEnable(), Enable::DISABLED);

  CsProcedureEnableCompleteEvent complete_event;
  complete_event.status = ErrorCode::LINK_LAYER_COLLISION;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
          params.connection_handle, Enable::DISABLED, complete_event));
  auto future = cs_requester_.fake_timer_advance(params.interval + 10);
  future.wait_for(kTimeout);
  cs_requester_.sync_client_handler();

  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

  fake_timerfd_reset();
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

  subevent_result.procedure_done_status = CsProcedureDoneStatus::ALL_RESULTS_COMPLETE;
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

TEST_F(DistanceMeasurementManagerTest, reschedure_procedure_enable_when_procedures_were_aborted) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  cs_requester_.sync_client_handler();

  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

  uint16_t procedure_counter = 0;
  CsSubeventResultEvent subevent_result;
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

TEST_F(DistanceMeasurementManagerTest, interval_updates_between_2_sessions) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);
  // consume the procedure_enable command
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.dm_manager_->StopDistanceMeasurement(
          params.responder_addr, params.connection_handle, DistanceMeasurementMethod::METHOD_CS);
  // consume the disable command by stop request
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);

  params.interval = 5000;  // LOW frequency
  cs_requester_.StartMeasurement(params);
  // disable after stop
  CommandView command_view =
          cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_SET_PROCEDURE_PARAMETERS);
  LeCsSetProcedureParametersView params_view = LeCsSetProcedureParametersView::Create(
          DistanceMeasurementCommandView::Create(command_view));
  cs_requester_.sync_client_handler();

  EXPECT_EQ(params_view.IsValid(), true);
  EXPECT_EQ(params_view.GetMinProcedureInterval(), kMinProcedureInterval);
}

TEST_F(DistanceMeasurementManagerTest, procedure_enabled_after_stop) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS,
          /*num_hci_command_packets=*/0xff));
  cs_requester_.dm_manager_->StopDistanceMeasurement(
          params.responder_addr, params.connection_handle, DistanceMeasurementMethod::METHOD_CS);
  // disable by stop request
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);

  CsProcedureEnableCompleteEvent complete_event;
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
          params.connection_handle, Enable::ENABLED, complete_event));
  // disable after stop
  CommandView command_view =
          cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  LeCsProcedureEnableView enable_view =
          LeCsProcedureEnableView::Create(DistanceMeasurementCommandView::Create(command_view));
  cs_requester_.sync_client_handler();

  EXPECT_EQ(enable_view.IsValid(), true);
  EXPECT_EQ(enable_view.GetProcedureEnable(), Enable::DISABLED);
}

TEST_F(DistanceMeasurementManagerTest, duplicated_requesting_session) {
  StartMeasurementParameters params;
  // first request
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();
  // second request
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStarted(params.responder_addr, METHOD_CS))
          .RetiresOnSaturation();
  params.interval = 1000;
  cs_requester_.StartMeasurement(params);
  params.interval = 200;
  cs_requester_.sync_client_handler();
  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

  cs_requester_.dm_manager_->StopDistanceMeasurement(
          params.responder_addr, params.connection_handle, DistanceMeasurementMethod::METHOD_CS);
  // disable by stop request
  CommandView command_view =
          cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  LeCsProcedureEnableView enable_view =
          LeCsProcedureEnableView::Create(DistanceMeasurementCommandView::Create(command_view));
  EXPECT_EQ(enable_view.IsValid(), true);
  EXPECT_EQ(enable_view.GetProcedureEnable(), Enable::DISABLED);
  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

  // start a new request after stop
  cs_requester_.StartMeasurement(params);

  cs_requester_.sync_client_handler();

  if (com_android_bluetooth_flags_channel_sounding_26q1_fix()) {
    // Verify that LE_CS_SECURITY_ENABLE is sent upon restart
    command_view = cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_SECURITY_ENABLE);
    auto security_enable_view =
            LeCsSecurityEnableView::Create(DistanceMeasurementCommandView::Create(command_view));
    EXPECT_TRUE(security_enable_view.IsValid());
    EXPECT_EQ(security_enable_view.GetConnectionHandle(), params.connection_handle);

    // Allow the flow to continue to verify the next command
    cs_requester_.test_hci_layer_->IncomingEvent(LeCsSecurityEnableStatusBuilder::Create(
            /*status=*/ErrorCode::SUCCESS,
            /*num_hci_command_packets=*/0xFF));
    cs_requester_.test_hci_layer_->IncomingLeMetaEvent(LeCsSecurityEnableCompleteBuilder::Create(
            ErrorCode::SUCCESS, params.connection_handle));
    cs_requester_.sync_client_handler();
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_SET_PROCEDURE_PARAMETERS);
  } else {
    cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  }
  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();
}

TEST_F(DistanceMeasurementManagerTest, b2b_conflict_before_requester_stop) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);

  // local requester session is stopped by the responder as they share the same config_id
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_REMOTE_REQUEST,
                                           DistanceMeasurementMethod::METHOD_CS));
  // inject the responder event
  cs_requester_.RespondTillProcedureEnableComplete(params);
  cs_requester_.sync_client_handler();

  // make sure the responder still can handle the subevent
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, 0, /*is_last=*/true, _));

  CsSubeventResultEvent resp_subevent_result;
  resp_subevent_result.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(
          CsModule::GetSubeventResultEvent(params.connection_handle, 0, resp_subevent_result));
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, b2b_conflict_after_requester_stop) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillSetProcedureParameters(params);
  cs_requester_.dm_manager_->StopDistanceMeasurement(
          params.responder_addr, params.connection_handle, DistanceMeasurementMethod::METHOD_CS);

  // inject the responder event
  cs_requester_.RespondTillProcedureEnableComplete(params);

  // make sure the responder still can handle the subevent
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, 0, /*is_last=*/true, _));

  CsSubeventResultEvent resp_subevent_result;
  resp_subevent_result.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(
          CsModule::GetSubeventResultEvent(params.connection_handle, 0, resp_subevent_result));
  cs_requester_.sync_client_handler();

  // requester start again, it should use 1 as config_id and trigger create_config command
  cs_requester_.StartMeasurement(params);
  CommandView command_view = cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_CREATE_CONFIG);
  LeCsCreateConfigView create_config_view =
          LeCsCreateConfigView::Create(DistanceMeasurementCommandView::Create(command_view));

  EXPECT_TRUE(create_config_view.IsValid());
  EXPECT_EQ(create_config_view.GetConfigId(), 1);
}

TEST_F(DistanceMeasurementManagerTest, no_trailing_procedure_data) {
  auto req_session_future = cs_requester_.GetDmSessionFuture();
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 10;

  CsSubeventResultEvent req_subevent_result_1;
  req_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::ABORTED;
  req_subevent_result_1.subevent_done_status = CsSubeventDoneStatus::ABORTED;
  req_subevent_result_1.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1));
  CsSubeventResultEvent req_subevent_result_2;
  req_subevent_result_2.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_2));
  cs_requester_.sync_client_handler();
  // construct responder data
  CsModule cs_responder;
  cs_responder.Start();
  cs_responder.RespondTillProcedureEnableComplete(params);
  cs_responder.dm_manager_->HandleMtuChanged(params.connection_handle, 517);

  // use partial result to simulate a trailing counter
  CsSubeventResultEvent resp_subevent_result_0;
  resp_subevent_result_0.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_0.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter + 1, resp_subevent_result_0));

  CsSubeventResultEvent resp_subevent_result_1;
  resp_subevent_result_1.procedure_done_status = CsProcedureDoneStatus::ABORTED;
  resp_subevent_result_1.subevent_done_status = CsSubeventDoneStatus::ABORTED;
  resp_subevent_result_1.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);

  std::vector<uint8_t> segment_data_1;
  EXPECT_CALL(cs_responder.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, procedure_counter, /*is_last=*/true, _))
          .WillOnce([&segment_data_1](Address /*address*/, uint16_t /*procedure_counter*/,
                                      bool /*is_last*/, std::vector<uint8_t> raw_data) {
            segment_data_1 = std::move(raw_data);
          });
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1));
  cs_responder.sync_client_handler();
  cs_requester_.sync_client_handler();
  // make sure the segment_header is correct
  ras::SegmentationHeader segmentation_header;
  PacketView<kLittleEndian> packet_bytes_view_1(
          std::make_shared<std::vector<uint8_t>>(segment_data_1));
  ras::SegmentationHeader::Parse(&segmentation_header, packet_bytes_view_1.begin());
  ASSERT_EQ(segmentation_header.rolling_segment_counter_, 0);

  std::vector<uint8_t> segment_data_2;
  EXPECT_CALL(cs_responder.mock_dm_callbacks_,
              OnRasFragmentReady(params.requester_addr, procedure_counter, /*is_last=*/true, _))
          .WillOnce([&segment_data_2](Address /*address*/, uint16_t /*procedure_counter*/,
                                      bool /*is_last*/, std::vector<uint8_t> raw_data) {
            segment_data_2 = std::move(raw_data);
          });
  CsSubeventResultEvent resp_subevent_result_2;
  resp_subevent_result_2.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_2));
  cs_responder.sync_client_handler();
  // make sure the segment_header is correct
  PacketView<kLittleEndian> packet_bytes_view_2(
          std::make_shared<std::vector<uint8_t>>(segment_data_2));
  ras::SegmentationHeader::Parse(&segmentation_header, packet_bytes_view_2.begin());
  ASSERT_EQ(segmentation_header.rolling_segment_counter_, 0);

  cs_requester_.sync_client_handler();
  cs_responder.Stop();
}

TEST_F(DistanceMeasurementManagerTest, complete_mode2_procedure) {
  StartMeasurementParameters params;
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              UpdateConnInterval(params.connection_handle, kConnInterval));
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              UpdateChannelSoundingConfig(params.connection_handle, _, _, _, kConnInterval));
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              UpdateProcedureEnableConfig(params.connection_handle, _));
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result_1_1;
  req_subevent_result_1_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1_1.subevent_done_status = CsSubeventDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1_1.result_data_structures =
          CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1_1));
  CsSubeventResultEvent req_subevent_result_1_2;
  req_subevent_result_1_2.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1_2.subevent_done_status = CsSubeventDoneStatus::ALL_RESULTS_COMPLETE;
  req_subevent_result_1_2.result_data_structures = CsModule::GetSubeventContinueMode2Data();
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultContinueEvent(
          params.connection_handle, req_subevent_result_1_2));

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
  CsSubeventResultEvent resp_subevent_result_1_1;
  resp_subevent_result_1_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1_1.subevent_done_status = CsSubeventDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1_1.result_data_structures =
          CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1_1));
  CsSubeventResultEvent resp_subevent_result_1_2;
  resp_subevent_result_1_2.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1_2.subevent_done_status = CsSubeventDoneStatus::ALL_RESULTS_COMPLETE;
  resp_subevent_result_1_2.result_data_structures = CsModule::GetSubeventContinueMode2Data();
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultContinueEvent(
          params.connection_handle, resp_subevent_result_1_2));
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

TEST_F(DistanceMeasurementManagerTest, invalid_mode2_procedure) {
  StartMeasurementParameters params;
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              UpdateConnInterval(params.connection_handle, kConnInterval));
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              UpdateChannelSoundingConfig(params.connection_handle, _, _, _, kConnInterval));
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              UpdateProcedureEnableConfig(params.connection_handle, _));
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result_1_1;
  req_subevent_result_1_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1_1.subevent_done_status = CsSubeventDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1_1.result_data_structures =
          CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1_1));
  CsSubeventResultEvent req_subevent_result_1_2;
  req_subevent_result_1_2.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  req_subevent_result_1_2.subevent_done_status = CsSubeventDoneStatus::ALL_RESULTS_COMPLETE;
  req_subevent_result_1_2.result_data_structures = CsModule::GetSubeventContinueMode2Data();
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultContinueEvent(
          params.connection_handle, req_subevent_result_1_2));

  CsSubeventResultEvent req_subevent_result_2;
  req_subevent_result_2.result_data_structures =
          CsModule::GetInvalidSubeventMode2Data(CsRole::INITIATOR);
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
  CsSubeventResultEvent resp_subevent_result_1_1;
  resp_subevent_result_1_1.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1_1.subevent_done_status = CsSubeventDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1_1.result_data_structures =
          CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1_1));
  CsSubeventResultEvent resp_subevent_result_1_2;
  resp_subevent_result_1_2.procedure_done_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
  resp_subevent_result_1_2.subevent_done_status = CsSubeventDoneStatus::ALL_RESULTS_COMPLETE;
  resp_subevent_result_1_2.result_data_structures = CsModule::GetSubeventContinueMode2Data();
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultContinueEvent(
          params.connection_handle, resp_subevent_result_1_2));
  CsSubeventResultEvent resp_subevent_result_2;
  resp_subevent_result_2.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_2));
  cs_responder.sync_client_handler();

  // send responder data
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_,
              WriteProcedureData(params.connection_handle, CsRole::INITIATOR, _, procedure_counter))
          .Times(0);

  cs_requester_.dm_manager_->HandleRemoteData(params.responder_addr, params.connection_handle,
                                              segment_data_1);

  cs_requester_.sync_client_handler();
  cs_responder.Stop();
}

TEST_F(DistanceMeasurementManagerTest, complete_mode2_procedure_with_hal_v1) {
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_, GetRangingHalVersion())
          .WillRepeatedly(Return(hal::V_1));
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  uint16_t procedure_counter = 0;

  CsSubeventResultEvent req_subevent_result_1;
  req_subevent_result_1.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::INITIATOR);
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, req_subevent_result_1));

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
  resp_subevent_result_1.result_data_structures = CsModule::GetSubeventMode2Data(CsRole::REFLECTOR);
  cs_responder.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetSubeventResultEvent(
          params.connection_handle, procedure_counter, resp_subevent_result_1));
  cs_responder.sync_client_handler();

  // send responder data
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_, WriteRawData(params.connection_handle, _));

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
                         Values(InvalidRasTestingItem::RANGING_DONE_STATUS,
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
                         Values(CsRttType::RTT_WITH_32_BIT_SOUNDING_SEQUENCE,
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
  ON_CALL(*cs_requester_.mock_acl_manager_, HACK_GetLeAddress(_))
          .WillByDefault(Return(params.responder_addr));
  auto future = cs_requester_.fake_timer_advance(params.interval);
  future.wait_for(kTimeout);

  cs_requester_.test_hci_layer_->GetCommand(OpCode::READ_RSSI);
  int8_t rssi_drop_off_at_1m = 41;
  double pow_value = (transmit_power_level - rssi - rssi_drop_off_at_1m) / 20.0;
  double distance = pow(10.0, pow_value);
  if (com_android_bluetooth_flags_include_power_and_rssi_in_distance_measurement_result()) {
    EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
                OnDistanceMeasurementResult(params.responder_addr, distance * 100, distance * 100,
                                            _, _, _, _, _, transmit_power_level, rssi, _, _, _, _,
                                            DistanceMeasurementMethod::METHOD_RSSI));
  } else {
    EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
                OnDistanceMeasurementResult(params.responder_addr, distance * 100, distance * 100,
                                            _, _, _, _, _, _, _, _, _, _, _,
                                            DistanceMeasurementMethod::METHOD_RSSI));
  }
  cs_requester_.test_hci_layer_->IncomingEvent(ReadRssiCompleteBuilder::Create(
          /*num_hci_command_packets=*/128, ErrorCode::SUCCESS, params.connection_handle, rssi));
  fake_timerfd_reset();
  cs_requester_.sync_client_handler();
}

struct GetSupportedSessionTypesTestParams {
  std::vector<RangingSessionType> session_types;
  bool expect_offload_enabled_called;
  std::string test_name;
};

class DistanceMeasurementManagerGetSupportedSessionTypesTest
    : public DistanceMeasurementManagerTest,
      public WithParamInterface<GetSupportedSessionTypesTestParams> {};

TEST_P(DistanceMeasurementManagerGetSupportedSessionTypesTest, VerifyOffloadCallback) {
  const auto& params = GetParam();
  EXPECT_CALL(*cs_requester_.mock_ranging_hal_, GetSupportedSessionTypes())
          .WillOnce(Return(params.session_types));
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_, OnRangingHardwareOffloadEnabled())
          .Times(params.expect_offload_enabled_called ? 1 : 0);

  StartMeasurementParameters measurement_params;
  cs_requester_.StartMeasurementTillRasConnectedEvent(measurement_params);

  cs_requester_.sync_client_handler();
}

INSTANTIATE_TEST_SUITE_P(GetSupportedSessionTypesTests,
                         DistanceMeasurementManagerGetSupportedSessionTypesTest,
                         Values(
                                 GetSupportedSessionTypesTestParams{
                                         {RangingSessionType::HARDWARE_OFFLOAD_DATA_PARSING},
                                         true,
                                         "HardwareOffloadEnabled"},
                                 GetSupportedSessionTypesTestParams{
                                         {RangingSessionType::SOFTWARE_STACK_DATA_PARSING},
                                         false,
                                         "SoftwareParsingOnly"},
                                 GetSupportedSessionTypesTestParams{{}, false, "Empty"},
                                 GetSupportedSessionTypesTestParams{
                                         {RangingSessionType::SOFTWARE_STACK_DATA_PARSING,
                                          RangingSessionType::HARDWARE_OFFLOAD_DATA_PARSING},
                                         true,
                                         "HardwareAndSoftware"}),
                         [](const TestParamInfo<GetSupportedSessionTypesTestParams>& info) {
                           return info.param.test_name;
                         });

TEST_F(DistanceMeasurementManagerTest, ranging_hal_on_closed_before_started) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillRasConnectedEvent(params);

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .Times(0);

  cs_requester_.mock_ranging_hal_->GetRangingHalCallback()->OnClosed(params.connection_handle,
                                                                     hal::Reason::ERROR_UNKNOWN);
  cs_requester_.sync_client_handler();
}

TEST_F(DistanceMeasurementManagerTest, ranging_hal_on_closed_after_started) {
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  cs_requester_.sync_client_handler();
  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();

  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementStopped(params.responder_addr,
                                           DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                           DistanceMeasurementMethod::METHOD_CS))
          .Times(1);

  cs_requester_.mock_ranging_hal_->GetRangingHalCallback()->OnClosed(params.connection_handle,
                                                                     hal::Reason::ERROR_UNKNOWN);

  CsProcedureEnableCompleteEvent complete_event;
  cs_requester_.test_hci_layer_->GetCommand(OpCode::LE_CS_PROCEDURE_ENABLE);
  cs_requester_.test_hci_layer_->IncomingEvent(LeCsProcedureEnableStatusBuilder::Create(
          /*status=*/ErrorCode::SUCCESS, /*num_hci_command_packets=*/0xff));
  cs_requester_.test_hci_layer_->IncomingLeMetaEvent(CsModule::GetProcedureEnableCompleteEvent(
          params.connection_handle, Enable::DISABLED, complete_event));

  cs_requester_.sync_client_handler();
  cs_requester_.test_hci_layer_->AssertNoQueuedCommand();
}

TEST_F(DistanceMeasurementManagerTest, ranging_hal_on_result_v2) {
  // 1. Setup: Start a CS session so a tracker exists.
  // This is the step that was missing before.
  StartMeasurementParameters params;
  cs_requester_.StartMeasurementTillProcedureEnableComplete(params);
  cs_requester_.sync_client_handler();

  // 2. Define the mock result from HAL
  hal::RangingResult ranging_result;
  ranging_result.result_meters_ = 10.5;
  ranging_result.error_meters_ = 0.5;
  ranging_result.confidence_level_ = 90;
  ranging_result.delay_spread_meters_ = 1.2;
  ranging_result.detected_attack_level_ = KPacketNadmAttackUnlikely;
  ranging_result.velocity_meters_per_second_ = 0.1;
  ranging_result.elapsed_timestamp_nanos_ = 123456789;  // Specific timestamp for V2

  // 3. Set the expectation on the final callback
  // We expect OnDistanceMeasurementResult to be called with:
  // - Distances converted to centimeters (10.5m -> 1050cm)
  // - The exact timestamp from the V2 HAL result
  // TODO(b/462311235): Add call path for check_cs_procedure_complete so that rssi can be tested.
  EXPECT_CALL(cs_requester_.mock_dm_callbacks_,
              OnDistanceMeasurementResult(
                      params.responder_addr,
                      static_cast<uint32_t>(ranging_result.result_meters_ * 100),  // 1050
                      static_cast<uint32_t>(ranging_result.error_meters_ * 100),   // 50
                      kInvalidAzimuthAngleDegree, kInvalidAzimuthAngleDegree,
                      kInvalidAltitudeAngleDegree, kInvalidAltitudeAngleDegree,
                      ranging_result.elapsed_timestamp_nanos_,  // V2 uses the provided timestamp
                      _, _,
                      ranging_result.confidence_level_,     // 90
                      ranging_result.delay_spread_meters_,  // 1.2
                      static_cast<DistanceMeasurementDetectedAttackLevel>(
                              ranging_result.detected_attack_level_),  // NADM_ATTACK_UNLIKELY
                      ranging_result.velocity_meters_per_second_,      // 0.1
                      DistanceMeasurementMethod::METHOD_CS))
          .Times(1);

  // 4. Trigger the OnResult callback
  // We get the registered callback from the mock HAL and invoke it.
  cs_requester_.mock_ranging_hal_->GetRangingHalCallback()->OnResult(params.connection_handle,
                                                                     ranging_result);

  // 5. Synchronize handler to process the callback
  cs_requester_.sync_client_handler();
}

}  // namespace
}  // namespace hci
}  // namespace bluetooth
