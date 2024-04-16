/*
 * Copyright 2022 The Android Open Source Project
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

#include <android_bluetooth_flags.h>
#include <bluetooth/log.h>
#include <math.h>

#include <complex>
#include <unordered_map>

#include "acl_manager/assembler.h"
#include "common/strings.h"
#include "hci/acl_manager.h"
#include "hci/distance_measurement_interface.h"
#include "hci/event_checkers.h"
#include "hci/hci_layer.h"
#include "module.h"
#include "os/handler.h"
#include "os/log.h"
#include "os/repeating_alarm.h"
#include "packet/packet_view.h"
#include "ras/ras_packets.h"

using namespace bluetooth::ras;
using bluetooth::hci::acl_manager::PacketViewForRecombination;

namespace bluetooth {
namespace hci {

const ModuleFactory DistanceMeasurementManager::Factory =
    ModuleFactory([]() { return new DistanceMeasurementManager(); });
static constexpr uint16_t kIllegalConnectionHandle = 0xffff;
static constexpr uint8_t kTxPowerNotAvailable = 0xfe;
static constexpr int8_t kRSSIDropOffAt1M = 41;
static constexpr uint8_t kCsMaxTxPower = 12;  // 12 dBm
static constexpr CsSyncAntennaSelection kCsSyncAntennaSelection = CsSyncAntennaSelection::ANTENNA_2;
static constexpr uint8_t kConfigId = 0x01;  // Use 0x01 to create config and enable procedure
static constexpr uint8_t kMinMainModeSteps = 0x02;
static constexpr uint8_t kMaxMainModeSteps = 0x05;
static constexpr uint8_t kMainModeRepetition = 0x00;  // No repetition
static constexpr uint8_t kMode0Steps =
    0x03;  // Maximum number of mode-0 steps to increase success subevent rate
static constexpr uint8_t kChannelMapRepetition = 0x01;  // No repetition
static constexpr uint8_t kCh3cJump = 0x03;              // Skip 3 Channels
static constexpr uint16_t kMaxProcedureLen = 0xFFFF;    // 40.959375s
static constexpr uint16_t kMinProcedureInterval = 0x01;
static constexpr uint16_t kMaxProcedureInterval = 0xFF;
static constexpr uint16_t kMaxProcedureCount = 0x01;
static constexpr uint32_t kMinSubeventLen = 0x0004E2;         // 1250us
static constexpr uint32_t kMaxSubeventLen = 0x3d0900;         // 4s
static constexpr uint8_t kToneAntennaConfigSelection = 0x07;  // 2x2
static constexpr uint8_t kTxPwrDelta = 0x00;
static constexpr uint8_t kProcedureDataBufferSize = 0x10;  // Buffer size of Procedure data
static constexpr uint16_t kMtuForRasData = 507;            // 512 - 5
static constexpr uint16_t kRangingCounterMask = 0x0FFF;

struct DistanceMeasurementManager::impl {
  struct CsProcedureData {
    CsProcedureData(
        uint16_t procedure_counter,
        uint8_t num_antenna_paths,
        uint8_t configuration_id,
        uint8_t selected_tx_power)
        : counter(procedure_counter), num_antenna_paths(num_antenna_paths) {
      local_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
      remote_status = CsProcedureDoneStatus::PARTIAL_RESULTS;
      // In ascending order of antenna position with tone extension data at the end
      uint16_t num_tone_data = num_antenna_paths + 1;
      for (uint8_t i = 0; i < num_tone_data; i++) {
        std::vector<std::complex<double>> empty_complex_vector;
        tone_pct_initiator.push_back(empty_complex_vector);
        tone_pct_reflector.push_back(empty_complex_vector);
        std::vector<uint8_t> empty_vector;
        tone_quality_indicator_initiator.push_back(empty_vector);
        tone_quality_indicator_reflector.push_back(empty_vector);
      }
      // RAS data
      segmentation_header_.first_segment_ = 1;
      segmentation_header_.last_segment_ = 0;
      segmentation_header_.rolling_segment_counter_ = 0;
      ranging_header_.ranging_counter_ = counter;
      ranging_header_.configuration_id_ = configuration_id;
      ranging_header_.selected_tx_power_ = selected_tx_power;
      ranging_header_.antenna_paths_mask_ = 0;
      for (uint8_t i = 0; i < num_antenna_paths; i++) {
        ranging_header_.antenna_paths_mask_ |= (1 << i);
      }
      ranging_header_.pct_format_ = PctFormat::IQ;
    }
    // Procedure counter
    uint16_t counter;
    // Number of antenna paths (1 to 4) reported in the procedure
    uint8_t num_antenna_paths;
    // Frequency Compensation indicates fractional frequency offset (FFO) value of initiator, in
    // 0.01ppm
    std::vector<uint16_t> frequency_compensation;
    // The channel indices of every step in a CS procedure (in time order)
    std::vector<uint8_t> step_channel;
    // Measured Frequency Offset from mode 0, relative to the remote device, in 0.01ppm
    std::vector<uint16_t> measured_freq_offset;
    // Initiator's PCT (complex value) measured from mode-2 or mode-3 steps in a CS procedure (in
    // time order)
    std::vector<std::vector<std::complex<double>>> tone_pct_initiator;
    // Reflector's PCT (complex value) measured from mode-2 or mode-3 steps in a CS procedure (in
    // time order)
    std::vector<std::vector<std::complex<double>>> tone_pct_reflector;
    std::vector<std::vector<uint8_t>> tone_quality_indicator_initiator;
    std::vector<std::vector<uint8_t>> tone_quality_indicator_reflector;
    CsProcedureDoneStatus local_status;
    CsProcedureDoneStatus remote_status;
    // If the procedure is aborted by either the local or remote side.
    bool aborted = false;
    // RAS data
    SegmentationHeader segmentation_header_;
    RangingHeader ranging_header_;
    std::vector<uint8_t> ras_raw_data_;  // raw data for multi_subevents;
    uint16_t ras_raw_data_index_ = 0;
    RasSubeventHeader ras_subevent_header_;
    std::vector<uint8_t> ras_subevent_data_;
    uint8_t ras_subevent_counter_ = 0;
  };

  ~impl() {}
  void start(os::Handler* handler, hci::HciLayer* hci_layer, hci::AclManager* acl_manager) {
    handler_ = handler;
    hci_layer_ = hci_layer;
    acl_manager_ = acl_manager;
    hci_layer_->RegisterLeEventHandler(
        hci::SubeventCode::TRANSMIT_POWER_REPORTING,
        handler_->BindOn(this, &impl::on_transmit_power_reporting));
    if (!IS_FLAG_ENABLED(channel_sounding_in_stack)) {
      log::info("IS_FLAG_ENABLED channel_sounding_in_stack: false");
      return;
    }
    distance_measurement_interface_ = hci_layer_->GetDistanceMeasurementInterface(
        handler_->BindOn(this, &DistanceMeasurementManager::impl::handle_event));
    distance_measurement_interface_->EnqueueCommand(
        LeCsReadLocalSupportedCapabilitiesBuilder::Create(),
        handler_->BindOnceOn(this, &impl::on_cs_read_local_supported_capabilities));
  }

  void stop() {
    hci_layer_->UnregisterLeEventHandler(hci::SubeventCode::TRANSMIT_POWER_REPORTING);
  }

  void register_distance_measurement_callbacks(DistanceMeasurementCallbacks* callbacks) {
    distance_measurement_callbacks_ = callbacks;
  }

  void start_distance_measurement(
      const Address& address, uint16_t interval, DistanceMeasurementMethod method) {
    log::info("Address:{}, method:{}", ADDRESS_TO_LOGGABLE_CSTR(address), method);
    uint16_t connection_handle = acl_manager_->HACK_GetLeHandle(address);

    // Remove this check if we support any connection less method
    if (connection_handle == kIllegalConnectionHandle) {
      log::warn("Can't find any LE connection for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_NO_LE_CONNECTION, method);
      return;
    }

    switch (method) {
      case METHOD_AUTO:
      case METHOD_RSSI: {
        if (rssi_trackers.find(address) == rssi_trackers.end()) {
          rssi_trackers[address].handle = connection_handle;
          rssi_trackers[address].interval_ms = interval;
          rssi_trackers[address].remote_tx_power = kTxPowerNotAvailable;
          rssi_trackers[address].started = false;
          rssi_trackers[address].repeating_alarm = std::make_unique<os::RepeatingAlarm>(handler_);
          hci_layer_->EnqueueCommand(
              LeReadRemoteTransmitPowerLevelBuilder::Create(
                  acl_manager_->HACK_GetLeHandle(address), 0x01),
              handler_->BindOnceOn(
                  this, &impl::on_read_remote_transmit_power_level_status, address));
        } else {
          rssi_trackers[address].interval_ms = interval;
        }
      } break;
      case METHOD_CS: {
        start_distance_measurement_with_cs(address, connection_handle, interval);
      } break;
    }
  }

  void start_distance_measurement_with_cs(
      const Address& cs_remote_address, uint16_t connection_handle, uint16_t interval) {
    log::info(
        "connection_handle: {}, address: {}",
        connection_handle,
        ADDRESS_TO_LOGGABLE_CSTR(cs_remote_address));
    if (!IS_FLAG_ENABLED(channel_sounding_in_stack)) {
      log::error("Channel Sounding is not enabled");
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          cs_remote_address, REASON_INTERNAL_ERROR, METHOD_CS);
      return;
    }

    if (cs_trackers_.find(connection_handle) != cs_trackers_.end() &&
        cs_trackers_[connection_handle].address != cs_remote_address) {
      log::warn("Remove old tracker for {}", ADDRESS_TO_LOGGABLE_CSTR(cs_remote_address));
      cs_trackers_.erase(connection_handle);
    }

    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      // Create a cs tracker with role initiator
      cs_trackers_[connection_handle].address = cs_remote_address;
      // TODO: Check ROLE via CS config. (b/304295768)
      cs_trackers_[connection_handle].role = CsRole::INITIATOR;
      cs_trackers_[connection_handle].repeating_alarm =
          std::make_unique<os::RepeatingAlarm>(handler_);
    }
    cs_trackers_[connection_handle].interval_ms = interval;
    cs_trackers_[connection_handle].waiting_for_start_callback = true;

    if (!cs_trackers_[connection_handle].setup_complete) {
      send_le_cs_read_remote_supported_capabilities(connection_handle);
      return;
    }
    if (!cs_trackers_[connection_handle].config_set) {
      send_le_cs_create_config(connection_handle);
      return;
    }
    log::info(
        "enable cs procedure regularly with interval: {} ms",
        cs_trackers_[connection_handle].interval_ms);
    cs_trackers_[connection_handle].repeating_alarm->Cancel();
    send_le_cs_procedure_enable(connection_handle, Enable::ENABLED);
    cs_trackers_[connection_handle].repeating_alarm->Schedule(
        common::Bind(
            &impl::send_le_cs_procedure_enable,
            common::Unretained(this),
            connection_handle,
            Enable::ENABLED),
        std::chrono::milliseconds(cs_trackers_[connection_handle].interval_ms));
  }

  void stop_distance_measurement(const Address& address, DistanceMeasurementMethod method) {
    log::info("Address:{}, method:{}", ADDRESS_TO_LOGGABLE_CSTR(address), method);
    switch (method) {
      case METHOD_AUTO:
      case METHOD_RSSI: {
        if (rssi_trackers.find(address) == rssi_trackers.end()) {
          log::warn("Can't find rssi tracker for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
        } else {
          hci_layer_->EnqueueCommand(
              LeSetTransmitPowerReportingEnableBuilder::Create(
                  rssi_trackers[address].handle, 0x00, 0x00),
              handler_->BindOnce(check_complete<LeSetTransmitPowerReportingEnableCompleteView>));
          rssi_trackers[address].repeating_alarm->Cancel();
          rssi_trackers[address].repeating_alarm.reset();
          rssi_trackers.erase(address);
        }
      } break;
      case METHOD_CS: {
        uint16_t connection_handle = acl_manager_->HACK_GetLeHandle(address);
        if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
          log::warn("Can't find CS tracker for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
        } else {
          cs_trackers_[connection_handle].repeating_alarm->Cancel();
          cs_trackers_[connection_handle].repeating_alarm.reset();
          send_le_cs_procedure_enable(connection_handle, Enable::DISABLED);
          cs_trackers_.erase(connection_handle);
        }
      } break;
    }
  }

  void send_read_rssi(const Address& address) {
    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      log::warn("Can't find rssi tracker for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      return;
    }
    uint16_t connection_handle = acl_manager_->HACK_GetLeHandle(address);
    if (connection_handle == kIllegalConnectionHandle) {
      log::warn("Can't find connection for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      if (rssi_trackers.find(address) != rssi_trackers.end()) {
        distance_measurement_callbacks_->OnDistanceMeasurementStopped(
            address, REASON_NO_LE_CONNECTION, METHOD_RSSI);
        rssi_trackers[address].repeating_alarm->Cancel();
        rssi_trackers[address].repeating_alarm.reset();
        rssi_trackers.erase(address);
      }
      return;
    }

    hci_layer_->EnqueueCommand(
        ReadRssiBuilder::Create(connection_handle),
        handler_->BindOnceOn(this, &impl::on_read_rssi_complete, address));
  }

  void handle_event(LeMetaEventView event) {
    if (!event.IsValid()) {
      log::error("Received invalid LeMetaEventView");
      return;
    }
    switch (event.GetSubeventCode()) {
      case hci::SubeventCode::LE_CS_TEST_END_COMPLETE:
      case hci::SubeventCode::LE_CS_READ_REMOTE_FAE_TABLE_COMPLETE: {
        log::warn("Unhandled subevent {}", hci::SubeventCodeText(event.GetSubeventCode()));
      } break;
      case hci::SubeventCode::LE_CS_SUBEVENT_RESULT_CONTINUE:
      case hci::SubeventCode::LE_CS_SUBEVENT_RESULT: {
        on_cs_subevent(event);
      } break;
      case hci::SubeventCode::LE_CS_PROCEDURE_ENABLE_COMPLETE: {
        on_cs_procedure_enable_complete(LeCsProcedureEnableCompleteView::Create(event));
      } break;
      case hci::SubeventCode::LE_CS_CONFIG_COMPLETE: {
        on_cs_config_complete(LeCsConfigCompleteView::Create(event));
      } break;
      case hci::SubeventCode::LE_CS_SECURITY_ENABLE_COMPLETE: {
        on_cs_security_enable_complete(LeCsSecurityEnableCompleteView::Create(event));
      } break;
      case hci::SubeventCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES_COMPLETE: {
        on_cs_read_remote_supported_capabilities_complete(
            LeCsReadRemoteSupportedCapabilitiesCompleteView::Create(event));
      } break;
      default:
        log::info("Unknown subevent {}", hci::SubeventCodeText(event.GetSubeventCode()));
    }
  }

  void send_le_cs_read_remote_supported_capabilities(uint16_t connection_handle) {
    hci_layer_->EnqueueCommand(
        LeCsReadRemoteSupportedCapabilitiesBuilder::Create(connection_handle),
        handler_->BindOnce(check_status<LeCsReadRemoteSupportedCapabilitiesStatusView>));
  }

  void send_le_cs_security_enable(uint16_t connection_handle) {
    hci_layer_->EnqueueCommand(
        LeCsSecurityEnableBuilder::Create(connection_handle),
        handler_->BindOnce(check_status<LeCsSecurityEnableStatusView>));
  }

  void send_le_cs_set_default_settings(uint16_t connection_handle) {
    uint8_t role_enable = (1 << (uint8_t)CsRole::INITIATOR) | 1 << ((uint8_t)CsRole::REFLECTOR);
    hci_layer_->EnqueueCommand(
        LeCsSetDefaultSettingsBuilder::Create(
            connection_handle,
            role_enable,
            kCsSyncAntennaSelection,
            kCsMaxTxPower  // max_tx_power
            ),
        handler_->BindOnceOn(this, &impl::on_cs_set_default_settings_complete));
  }

  void send_le_cs_create_config(uint16_t connection_handle) {
    auto channel_vector = common::FromHexString("1FFFFFFFFFFFFC7FFFFC");  // use all 72 Channel
    std::array<uint8_t, 10> channel_map;
    std::copy(channel_vector->begin(), channel_vector->end(), channel_map.begin());
    std::reverse(channel_map.begin(), channel_map.end());
    hci_layer_->EnqueueCommand(
        LeCsCreateConfigBuilder::Create(
            connection_handle,
            kConfigId,
            CsCreateContext::BOTH_LOCAL_AND_REMOTE_CONTROLLER,
            CsMainModeType::MODE_2,
            CsSubModeType::UNUSED,
            kMinMainModeSteps,
            kMaxMainModeSteps,
            kMainModeRepetition,
            kMode0Steps,
            CsRole::INITIATOR,
            CsConfigRttType::RTT_WITH_128_BIT_RANDOM_SEQUENCE,
            CsSyncPhy::LE_1M_PHY,
            channel_map,
            kChannelMapRepetition,
            CsChannelSelectionType::TYPE_3B,
            CsCh3cShape::HAT_SHAPE,
            kCh3cJump,
            Enable::DISABLED),
        handler_->BindOnce(check_status<LeCsCreateConfigStatusView>));
  }

  void send_le_cs_set_procedure_parameters(uint16_t connection_handle) {
    CsPreferredPeerAntenna preferred_peer_antenna;
    hci_layer_->EnqueueCommand(
        LeCsSetProcedureParametersBuilder::Create(
            connection_handle,
            kConfigId,
            kMaxProcedureLen,
            kMinProcedureInterval,
            kMaxProcedureInterval,
            kMaxProcedureCount,
            kMinSubeventLen,
            kMaxSubeventLen,
            kToneAntennaConfigSelection,
            CsPhy::LE_1M_PHY,
            kTxPwrDelta,
            preferred_peer_antenna),
        handler_->BindOnceOn(this, &impl::on_cs_set_procedure_parameters));
  }

  void send_le_cs_procedure_enable(uint16_t connection_handle, Enable enable) {
    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      log::warn("Can't find cs tracker for connection {}", connection_handle);
      return;
    }
    Address address = cs_trackers_[connection_handle].address;
    // Check if the connection still exists
    uint16_t connection_handle_from_acl_manager = acl_manager_->HACK_GetLeHandle(address);
    if (connection_handle_from_acl_manager == kIllegalConnectionHandle) {
      log::warn("Can't find connection for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(
          address, REASON_NO_LE_CONNECTION, METHOD_CS);
      cs_trackers_[connection_handle].repeating_alarm->Cancel();
      cs_trackers_[connection_handle].repeating_alarm.reset();
      cs_trackers_.erase(connection_handle);
      return;
    }
    hci_layer_->EnqueueCommand(
        LeCsProcedureEnableBuilder::Create(connection_handle, kConfigId, enable),
        handler_->BindOnce(check_status<LeCsProcedureEnableStatusView>));
  }

  void on_cs_read_local_supported_capabilities(CommandCompleteView view) {
    auto complete_view = LeCsReadLocalSupportedCapabilitiesCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Get invalid LeCsReadLocalSupportedCapabilitiesComplete");
      is_channel_sounding_supported_ = false;
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn(
          "Received LeCsReadLocalSupportedCapabilitiesComplete with error code {}", error_code);
      is_channel_sounding_supported_ = false;
      return;
    }
    is_channel_sounding_supported_ = true;
    cs_subfeature_supported_ = complete_view.GetOptionalSubfeaturesSupported();
  }

  void on_cs_read_remote_supported_capabilities_complete(
      LeCsReadRemoteSupportedCapabilitiesCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsReadRemoteSupportedCapabilitiesCompleteView");
      return;
    } else if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn(
          "Received LeCsReadRemoteSupportedCapabilitiesCompleteView with error code {}",
          error_code);
      return;
    }
    uint16_t connection_handle = event_view.GetConnectionHandle();
    send_le_cs_set_default_settings(event_view.GetConnectionHandle());
    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      // Create a cs tracker with role reflector
      // TODO: Check ROLE via CS config. (b/304295768)
      cs_trackers_[connection_handle].role = CsRole::REFLECTOR;
      cs_trackers_[connection_handle].address = acl_manager_->HACK_GetLeAddress(connection_handle);
    } else {
      send_le_cs_security_enable(connection_handle);
    }

    if (event_view.GetOptionalSubfeaturesSupported().phase_based_ranging_ == 0x01) {
      cs_trackers_[connection_handle].remote_support_phase_based_ranging = true;
    }
    log::info(
        "connection_handle:{}, num_antennas_supported:{}, max_antenna_paths_supported:{}, "
        "roles_supported:{}, phase_based_ranging_supported: {}",
        event_view.GetConnectionHandle(),
        event_view.GetNumAntennasSupported(),
        event_view.GetMaxAntennaPathsSupported(),
        event_view.GetRolesSupported().ToString(),
        event_view.GetOptionalSubfeaturesSupported().phase_based_ranging_);
  }

  void on_cs_set_default_settings_complete(CommandCompleteView view) {
    auto complete_view = LeCsSetDefaultSettingsCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Get invalid LeCsSetDefaultSettingsComplete");
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn("Received LeCsSetDefaultSettingsComplete with error code {}", error_code);
      return;
    }
  }

  void on_cs_security_enable_complete(LeCsSecurityEnableCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsSecurityEnableCompleteView");
      return;
    } else if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn("Received LeCsSecurityEnableCompleteView with error code {}", error_code);
      return;
    }
    uint16_t connection_handle = event_view.GetConnectionHandle();
    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      log::warn("Can't find cs tracker for connection_handle {}", connection_handle);
      return;
    }
    cs_trackers_[connection_handle].setup_complete = true;
    log::info(
        "Setup phase complete, connection_handle: {}, address: {}",
        connection_handle,
        ADDRESS_TO_LOGGABLE_CSTR(cs_trackers_[connection_handle].address));
    if (cs_trackers_[connection_handle].role == CsRole::INITIATOR) {
      send_le_cs_create_config(connection_handle);
    }
  }

  void on_cs_config_complete(LeCsConfigCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsConfigCompleteView");
      return;
    } else if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn("Received LeCsConfigCompleteView with error code {}", error_code);
      return;
    }
    uint16_t connection_handle = event_view.GetConnectionHandle();
    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      log::warn("Can't find cs tracker for connection_handle {}", connection_handle);
      return;
    }
    if (event_view.GetAction() == CsAction::CONFIG_REMOVED) {
      return;
    }
    log::info("Get {}", event_view.ToString());
    cs_trackers_[connection_handle].role = event_view.GetRole();
    cs_trackers_[connection_handle].config_set = true;
    cs_trackers_[connection_handle].main_mode_type = event_view.GetMainModeType();
    cs_trackers_[connection_handle].sub_mode_type = event_view.GetSubModeType();
    cs_trackers_[connection_handle].rtt_type = event_view.GetRttType();

    if (cs_trackers_[connection_handle].role == CsRole::INITIATOR) {
      send_le_cs_set_procedure_parameters(event_view.GetConnectionHandle());
    }
  }

  void on_cs_set_procedure_parameters(CommandCompleteView view) {
    auto complete_view = LeCsSetProcedureParametersCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Get Invalid LeCsSetProcedureParametersCompleteView");
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn("Received LeCsSetProcedureParametersCompleteView with error code {}", error_code);
      return;
    }
    uint16_t connection_handle = complete_view.GetConnectionHandle();
    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      log::warn("Can't find cs tracker for connection_handle {}", connection_handle);
      return;
    }

    if (cs_trackers_[connection_handle].role == CsRole::INITIATOR) {
      log::info(
          "enable cs procedure regularly with interval: {} ms",
          cs_trackers_[connection_handle].interval_ms);
      cs_trackers_[connection_handle].repeating_alarm->Cancel();
      send_le_cs_procedure_enable(connection_handle, Enable::ENABLED);
      cs_trackers_[connection_handle].repeating_alarm->Schedule(
          common::Bind(
              &impl::send_le_cs_procedure_enable,
              common::Unretained(this),
              connection_handle,
              Enable::ENABLED),
          std::chrono::milliseconds(cs_trackers_[connection_handle].interval_ms));
    }
  }

  void on_cs_procedure_enable_complete(LeCsProcedureEnableCompleteView event_view) {
    log::assert_that(event_view.IsValid(), "assert failed: event_view.IsValid()");
    uint16_t connection_handle = event_view.GetConnectionHandle();
    if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn("Received LeCsProcedureEnableCompleteView with error code {}", error_code);
      if (cs_trackers_.find(connection_handle) != cs_trackers_.end() &&
          cs_trackers_[connection_handle].waiting_for_start_callback) {
        cs_trackers_[connection_handle].waiting_for_start_callback = false;
        distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
            cs_trackers_[connection_handle].address, REASON_INTERNAL_ERROR, METHOD_CS);
      }
      return;
    }

    if (event_view.GetState() == Enable::ENABLED) {
      log::debug("Procedure enabled, {}", event_view.ToString());
      if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
        return;
      }
      cs_trackers_[connection_handle].config_id = event_view.GetConfigId();
      cs_trackers_[connection_handle].selected_tx_power = event_view.GetSelectedTxPower();

      if (cs_trackers_[connection_handle].waiting_for_start_callback) {
        cs_trackers_[connection_handle].waiting_for_start_callback = false;
        distance_measurement_callbacks_->OnDistanceMeasurementStarted(
            cs_trackers_[connection_handle].address, METHOD_CS);
      }
    }
    cs_delete_obsolete_data(event_view.GetConnectionHandle());
  }

  void on_cs_subevent(LeMetaEventView event) {
    if (!event.IsValid()) {
      log::error("Received invalid LeMetaEventView");
      return;
    }

    // Common data for LE_CS_SUBEVENT_RESULT and LE_CS_SUBEVENT_RESULT_CONTINUE,
    uint16_t connection_handle = 0;
    CsProcedureDoneStatus procedure_done_status;
    CsSubeventDoneStatus subevent_done_status;
    ProcedureAbortReason procedure_abort_reason;
    SubeventAbortReason subevent_abort_reason;
    std::vector<LeCsResultDataStructure> result_data_structures;
    if (event.GetSubeventCode() == SubeventCode::LE_CS_SUBEVENT_RESULT) {
      auto cs_event_result = LeCsSubeventResultView::Create(event);
      if (!cs_event_result.IsValid()) {
        log::warn("Get invalid LeCsSubeventResultView");
        return;
      }
      connection_handle = cs_event_result.GetConnectionHandle();
      procedure_done_status = cs_event_result.GetProcedureDoneStatus();
      subevent_done_status = cs_event_result.GetSubeventDoneStatus();
      procedure_abort_reason = cs_event_result.GetProcedureAbortReason();
      subevent_abort_reason = cs_event_result.GetSubeventAbortReason();
      result_data_structures = cs_event_result.GetResultDataStructures();
      if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
        log::warn("Can't find any tracker for {}", connection_handle);
        return;
      }
      CsProcedureData* procedure_data = init_cs_procedure_data(
          connection_handle,
          cs_event_result.GetProcedureCounter(),
          cs_event_result.GetNumAntennaPaths(),
          true);
      if (cs_trackers_[connection_handle].role == CsRole::INITIATOR) {
        procedure_data->frequency_compensation.push_back(
            cs_event_result.GetFrequencyCompensation());
      }
      // RAS
      log::debug("RAS Update subevent_header counter:{}", procedure_data->ras_subevent_counter_++);
      auto& ras_subevent_header = procedure_data->ras_subevent_header_;
      ras_subevent_header.start_acl_conn_event_ = cs_event_result.GetStartAclConnEvent();
      ras_subevent_header.frequency_compensation_ = cs_event_result.GetFrequencyCompensation();
      ras_subevent_header.reference_power_level_ = cs_event_result.GetReferencePowerLevel();
      ras_subevent_header.num_steps_reported_ = 0;
    } else {
      auto cs_event_result = LeCsSubeventResultContinueView::Create(event);
      if (!cs_event_result.IsValid()) {
        log::warn("Get invalid LeCsSubeventResultContinueView");
        return;
      }
      connection_handle = cs_event_result.GetConnectionHandle();
      procedure_done_status = cs_event_result.GetProcedureDoneStatus();
      subevent_done_status = cs_event_result.GetSubeventDoneStatus();
      procedure_abort_reason = cs_event_result.GetProcedureAbortReason();
      subevent_abort_reason = cs_event_result.GetSubeventAbortReason();
      result_data_structures = cs_event_result.GetResultDataStructures();
      if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
        log::warn("Can't find any tracker for {}", connection_handle);
        return;
      }
    }

    uint16_t counter = cs_trackers_[connection_handle].local_counter;
    log::debug(
        "Connection_handle {}, procedure_done_status: {}, subevent_done_status: {}, counter: {}",
        connection_handle,
        CsProcedureDoneStatusText(procedure_done_status),
        CsSubeventDoneStatusText(subevent_done_status),
        counter);

    if (procedure_done_status == CsProcedureDoneStatus::ABORTED ||
        subevent_done_status == CsSubeventDoneStatus::ABORTED) {
      log::warn(
          "Received CS Subevent with procedure_abort_reason:{}, subevent_abort_reason:{}, "
          "connection_handle:{}, counter:{}",
          ProcedureAbortReasonText(procedure_abort_reason),
          SubeventAbortReasonText(subevent_abort_reason),
          connection_handle,
          counter);
    }

    CsProcedureData* procedure_data = get_procedure_data(connection_handle, counter);
    if (procedure_data == nullptr) {
      return;
    }
    procedure_data->ras_subevent_header_.num_steps_reported_ += result_data_structures.size();

    if (procedure_abort_reason != ProcedureAbortReason::NO_ABORT ||
        subevent_abort_reason != SubeventAbortReason::NO_ABORT) {
      // Even the procedure is aborted, we should keep following process and
      // handle it when all corresponding remote data received.
      procedure_data->aborted = true;
      procedure_data->ras_subevent_header_.ranging_abort_reason_ =
          static_cast<RangingAbortReason>(procedure_abort_reason);
      procedure_data->ras_subevent_header_.subevent_abort_reason_ =
          static_cast<bluetooth::ras::SubeventAbortReason>(subevent_abort_reason);
    }
    parse_cs_result_data(
        result_data_structures, *procedure_data, cs_trackers_[connection_handle].role);
    // Update procedure status
    procedure_data->local_status = procedure_done_status;
    check_cs_procedure_complete(procedure_data, connection_handle);

    if (cs_trackers_[connection_handle].role == CsRole::INITIATOR) {
      // Skip to send remote
      return;
    }

    // Send data to RAS server
    if (subevent_done_status != CsSubeventDoneStatus::PARTIAL_RESULTS) {
      procedure_data->ras_subevent_header_.ranging_done_status_ =
          static_cast<RangingDoneStatus>(procedure_done_status);
      procedure_data->ras_subevent_header_.subevent_done_status_ =
          static_cast<SubeventDoneStatus>(subevent_done_status);
      auto builder = RasSubeventBuilder::Create(
          procedure_data->ras_subevent_header_, procedure_data->ras_subevent_data_);
      auto subevent_raw = builder_to_bytes(std::move(builder));
      append_vector(procedure_data->ras_raw_data_, subevent_raw);
      // erase buffer
      procedure_data->ras_subevent_data_.clear();
      send_on_demand_data(cs_trackers_[connection_handle].address, procedure_data);
    }
  }

  void send_on_demand_data(Address address, CsProcedureData* procedure_data) {
    // Check is last segment or not.
    uint16_t unsent_data_size =
        procedure_data->ras_raw_data_.size() - procedure_data->ras_raw_data_index_;
    if (procedure_data->local_status != CsProcedureDoneStatus::PARTIAL_RESULTS &&
        unsent_data_size <= kMtuForRasData) {
      procedure_data->segmentation_header_.last_segment_ = 1;
    } else if (procedure_data->ras_raw_data_.size() < kMtuForRasData) {
      log::verbose("waiting for more data, current size {}", procedure_data->ras_raw_data_.size());
      return;
    }

    // Create raw data for segment_data;
    uint16_t copy_size = unsent_data_size < kMtuForRasData ? unsent_data_size : kMtuForRasData;
    auto copy_start = procedure_data->ras_raw_data_.begin() + procedure_data->ras_raw_data_index_;
    auto copy_end = copy_start + copy_size;
    std::vector<uint8_t> subevent_data(copy_start, copy_end);
    procedure_data->ras_raw_data_index_ += copy_size;

    auto builder =
        RangingDataSegmentBuilder::Create(procedure_data->segmentation_header_, subevent_data);
    auto segment_data = builder_to_bytes(std::move(builder));

    log::debug("counter: {}, size:{}", procedure_data->counter, (uint16_t)segment_data.size());
    distance_measurement_callbacks_->OnRasFragmentReady(
        address,
        procedure_data->counter,
        procedure_data->segmentation_header_.last_segment_,
        segment_data);

    procedure_data->segmentation_header_.first_segment_ = 0;
    procedure_data->segmentation_header_.rolling_segment_counter_++;
    procedure_data->segmentation_header_.rolling_segment_counter_ %= 64;
    if (procedure_data->segmentation_header_.last_segment_) {
      // last segment sent, clear buffer
      procedure_data->ras_raw_data_.clear();
    } else if (unsent_data_size > kMtuForRasData) {
      send_on_demand_data(address, procedure_data);
    }
  }

  void handle_remote_data(const Address& address, const std::vector<uint8_t> raw_data) {
    uint16_t connection_handle = acl_manager_->HACK_GetLeHandle(address);
    log::debug(
        "address:{}, connection_handle 0x{:04x}, size:{}",
        address.ToString().c_str(),
        connection_handle,
        raw_data.size());

    if (cs_trackers_.find(connection_handle) == cs_trackers_.end()) {
      log::warn("can't find tracker for 0x{:04x}", connection_handle);
      return;
    }
    auto& tracker = cs_trackers_[connection_handle];

    SegmentationHeader segmentation_header;
    PacketView<kLittleEndian> packet_bytes_view(std::make_shared<std::vector<uint8_t>>(raw_data));
    auto after = SegmentationHeader::Parse(&segmentation_header, packet_bytes_view.begin());
    if (after == packet_bytes_view.begin()) {
      log::warn("Invalid segment data");
      return;
    }

    log::debug(
        "Receive segment for segment counter {}, size {}",
        segmentation_header.rolling_segment_counter_,
        raw_data.size());

    PacketView<kLittleEndian> segment_data(std::make_shared<std::vector<uint8_t>>(raw_data));
    if (segmentation_header.first_segment_) {
      auto segment = FirstRangingDataSegmentView::Create(segment_data);
      if (!segment.IsValid()) {
        log::warn("Invalid segment data");
        return;
      }
      tracker.ranging_header_ = segment.GetRangingHeader();

      auto begin = segment.GetSegmentationHeader().size() + segment.GetRangingHeader().size();
      tracker.segment_data_ =
          PacketViewForRecombination(segment.GetLittleEndianSubview(begin, segment.size()));
    } else {
      auto segment = RangingDataSegmentView::Create(segment_data);
      if (!segment.IsValid()) {
        log::warn("Invalid segment data");
        return;
      }
      tracker.segment_data_.AppendPacketView(
          segment.GetLittleEndianSubview(segmentation_header.size(), segment.size()));
    }

    if (segmentation_header.last_segment_) {
      parse_ras_segments(tracker.ranging_header_, tracker.segment_data_, connection_handle);
    }
  }

  void parse_ras_segments(
      RangingHeader ranging_header,
      PacketViewForRecombination& segment_data,
      uint16_t connection_handle) {
    log::debug("Data size {}, Ranging_header {}", segment_data.size(), ranging_header.ToString().c_str());
    auto procedure_data =
        get_procedure_data_for_ras(connection_handle, ranging_header.ranging_counter_);
    if (procedure_data == nullptr) {
      return;
    }

    uint8_t num_antenna_paths = 0;
    for (uint8_t i = 0; i < 4; i++) {
      if ((ranging_header.antenna_paths_mask_ & (1 << i)) != 0) {
        num_antenna_paths++;
      }
    }

    // Get role of the remote device
    CsRole role = cs_trackers_[connection_handle].role == CsRole::INITIATOR ? CsRole::REFLECTOR
                                                                            : CsRole::INITIATOR;

    auto parse_index = segment_data.begin();
    uint16_t remaining_data_size = std::distance(parse_index, segment_data.end());

    // Parse subevents
    while (remaining_data_size > 0) {
      RasSubeventHeader subevent_header;
      // Parse header
      auto after = RasSubeventHeader::Parse(&subevent_header, parse_index);
      if (after == parse_index) {
        log::warn("Received invalid subevent_header data");
        return;
      }
      parse_index = after;
      log::debug("subevent_header: {}", subevent_header.ToString());

      // Parse step data
      for (uint8_t i = 0; i < subevent_header.num_steps_reported_; i++) {
        StepMode step_mode;
        after = StepMode::Parse(&step_mode, parse_index);
        if (after == parse_index) {
          log::warn("Received invalid step_mode data");
          return;
        }
        parse_index = after;
        log::verbose("step:{}, {}", (uint16_t)i, step_mode.ToString());
        if (step_mode.aborted_) {
          continue;
        }

        switch (step_mode.mode_type_) {
          case 0: {
            if (role == CsRole::INITIATOR) {
              LeCsMode0InitatorData tone_data;
              after = LeCsMode0InitatorData::Parse(&tone_data, parse_index);
              if (after == parse_index) {
                log::warn(
                    "Error invalid mode {} data, role:{}",
                    step_mode.mode_type_,
                    CsRoleText(role).c_str());
                return;
              }
              parse_index = after;
            } else {
              LeCsMode0ReflectorData tone_data;
              after = LeCsMode0ReflectorData::Parse(&tone_data, parse_index);
              if (after == parse_index) {
                log::warn(
                    "Error invalid mode {} data, role:{}",
                    step_mode.mode_type_,
                    CsRoleText(role).c_str());
                return;
              }
            }
            parse_index = after;
          } break;
          case 2: {
            uint8_t num_tone_data = num_antenna_paths + 1;
            uint8_t data_len = 1 + (4 * num_tone_data);
            remaining_data_size = std::distance(parse_index, segment_data.end());
            if (remaining_data_size < data_len) {
              log::warn(
                  "insufficient length for LeCsMode2Data, num_tone_data {}, remaining_data_size {}",
                  num_tone_data,
                  remaining_data_size);
              return;
            }
            std::vector<uint8_t> vector_for_num_tone_data = {num_tone_data};
            PacketView<kLittleEndian> packet_view_for_num_tone_data(
                std::make_shared<std::vector<uint8_t>>(vector_for_num_tone_data));
            PacketViewForRecombination packet_bytes_view =
                PacketViewForRecombination(packet_view_for_num_tone_data);
            auto subview_begin = std::distance(segment_data.begin(), parse_index);
            packet_bytes_view.AppendPacketView(
                segment_data.GetLittleEndianSubview(subview_begin, subview_begin + data_len));
            LeCsMode2Data tone_data;
            after = LeCsMode2Data::Parse(&tone_data, packet_bytes_view.begin());
            if (after == packet_bytes_view.begin()) {
              log::warn(
                  "Error invalid mode {} data, role:{}",
                  step_mode.mode_type_,
                  CsRoleText(role).c_str());
              return;
            }
            parse_index += data_len;
            uint8_t permutation_index = tone_data.antenna_permutation_index_;

            // Parse in ascending order of antenna position with tone extension data at the end
            for (uint8_t k = 0; k < num_tone_data; k++) {
              uint8_t antenna_path = k == num_antenna_paths
                                         ? num_antenna_paths
                                         : cs_antenna_permutation_array_[permutation_index][k] - 1;
              double i_value = get_iq_value(tone_data.tone_data_[k].i_sample_);
              double q_value = get_iq_value(tone_data.tone_data_[k].q_sample_);
              uint8_t tone_quality_indicator = tone_data.tone_data_[k].tone_quality_indicator_;
              log::debug(
                  "antenna_path {}, {:f}, {:f}", (uint16_t)(antenna_path + 1), i_value, q_value);
              if (role == CsRole::INITIATOR) {
                procedure_data->tone_pct_initiator[antenna_path].emplace_back(i_value, q_value);
                procedure_data->tone_quality_indicator_initiator[antenna_path].emplace_back(
                    tone_quality_indicator);
              } else {
                procedure_data->tone_pct_reflector[antenna_path].emplace_back(i_value, q_value);
                procedure_data->tone_quality_indicator_reflector[antenna_path].emplace_back(
                    tone_quality_indicator);
              }
            }
          } break;
          default:
            log::error("Unexpect mode: {}", step_mode.mode_type_);
            return;
        }
      }
      remaining_data_size = std::distance(parse_index, segment_data.end());
      log::debug("Parse subevent done with remaining data size {}", remaining_data_size);
      procedure_data->remote_status = (CsProcedureDoneStatus)subevent_header.ranging_done_status_;
    }
    check_cs_procedure_complete(procedure_data, connection_handle);
  }

  CsProcedureData* init_cs_procedure_data(
      uint16_t connection_handle,
      uint16_t procedure_counter,
      uint8_t num_antenna_paths,
      bool local) {
    // Update procedure count
    if (local) {
      cs_trackers_[connection_handle].local_counter = procedure_counter;
    } else {
      cs_trackers_[connection_handle].remote_counter = procedure_counter;
    }

    std::vector<CsProcedureData>& data_list = cs_trackers_[connection_handle].procedure_data_list;
    for (auto& data : data_list) {
      if (data.counter == procedure_counter) {
        // Data already exists, return
        return &data;
      }
    }
    log::info("Create data for procedure_counter: {}", procedure_counter);
    data_list.emplace_back(
        procedure_counter,
        num_antenna_paths,
        cs_trackers_[connection_handle].config_id,
        cs_trackers_[connection_handle].selected_tx_power);

    // Append ranging header raw data
    std::vector<uint8_t> ranging_header_raw = {};
    BitInserter bi(ranging_header_raw);
    data_list.back().ranging_header_.Serialize(bi);
    append_vector(data_list.back().ras_raw_data_, ranging_header_raw);

    if (data_list.size() > kProcedureDataBufferSize) {
      log::warn("buffer full, drop procedure data with counter: {}", data_list.front().counter);
      data_list.erase(data_list.begin());
    }
    return &data_list.back();
  }

  void cs_delete_obsolete_data(uint16_t connection_handle) {
    std::vector<CsProcedureData>& data_list = cs_trackers_[connection_handle].procedure_data_list;
    while (!data_list.empty()) {
      data_list.erase(data_list.begin());
    }
  }

  CsProcedureData* get_procedure_data(uint16_t connection_handle, uint16_t counter) {
    std::vector<CsProcedureData>& data_list = cs_trackers_[connection_handle].procedure_data_list;
    CsProcedureData* procedure_data = nullptr;
    for (uint8_t i = 0; i < data_list.size(); i++) {
      if (data_list[i].counter == counter) {
        procedure_data = &data_list[i];
        break;
      }
    }
    if (procedure_data == nullptr) {
      log::warn(
          "Can't find data for connection_handle:{}, counter: {}", connection_handle, counter);
    }
    return procedure_data;
  }

  CsProcedureData* get_procedure_data_for_ras(
      uint16_t connection_handle, uint16_t ranging_counter) {
    std::vector<CsProcedureData>& data_list = cs_trackers_[connection_handle].procedure_data_list;
    CsProcedureData* procedure_data = nullptr;
    for (uint8_t i = 0; i < data_list.size(); i++) {
      if ((data_list[i].counter & kRangingCounterMask) == ranging_counter) {
        procedure_data = &data_list[i];
        break;
      }
    }
    if (procedure_data == nullptr) {
      log::warn(
          "Can't find data for connection_handle:{}, ranging_counter: {}",
          connection_handle,
          ranging_counter);
    }
    return procedure_data;
  }

  void check_cs_procedure_complete(CsProcedureData* procedure_data, uint16_t connection_handle) {
    if (procedure_data->local_status == CsProcedureDoneStatus::ALL_RESULTS_COMPLETE &&
        procedure_data->remote_status == CsProcedureDoneStatus::ALL_RESULTS_COMPLETE &&
        !procedure_data->aborted) {
      log::debug(
          "Procedure complete counter:{} data size:{}, main_mode_type:{}, sub_mode_type:{}",
          (uint16_t)procedure_data->counter,
          (uint16_t)procedure_data->step_channel.size(),
          (uint16_t)cs_trackers_[connection_handle].main_mode_type,
          (uint16_t)cs_trackers_[connection_handle].sub_mode_type);
    }

    // If the procedure is completed or aborted, delete all previous data
    if (procedure_data->local_status != CsProcedureDoneStatus::PARTIAL_RESULTS &&
        procedure_data->remote_status != CsProcedureDoneStatus::PARTIAL_RESULTS) {
      std::vector<CsProcedureData>& data_list = cs_trackers_[connection_handle].procedure_data_list;
      uint16_t counter = procedure_data->counter;  // Get value from pointer first.
      while (data_list.begin()->counter < counter) {
        log::debug("Delete obsolete procedure data, counter:{}", data_list.begin()->counter);
        data_list.erase(data_list.begin());
      }
    }
  }

  void parse_cs_result_data(
      std::vector<LeCsResultDataStructure> result_data_structures,
      CsProcedureData& procedure_data,
      CsRole role) {
    uint8_t num_antenna_paths = procedure_data.num_antenna_paths;
    auto& ras_data = procedure_data.ras_subevent_data_;
    for (auto result_data_structure : result_data_structures) {
      uint16_t mode = result_data_structure.step_mode_;
      uint16_t step_channel = result_data_structure.step_channel_;
      uint16_t data_length = result_data_structure.step_data_.size();
      log::verbose(
          "mode: {}, channel: {}, data_length: {}",
          mode,
          step_channel,
          (uint16_t)result_data_structure.step_data_.size());
      ras_data.emplace_back(mode);
      if (data_length == 0) {
        ras_data.back() |= (1 << 7);  // set step aborted
        continue;
      }
      append_vector(ras_data, result_data_structure.step_data_);

      // Parse data into structs from an iterator
      auto bytes = std::make_shared<std::vector<uint8_t>>();
      if (mode == 0x02 || mode == 0x03) {
        // Add one byte for the length of Tone_PCT[k], Tone_Quality_Indicator[k]
        bytes->emplace_back(num_antenna_paths + 1);
      }
      bytes->reserve(bytes->size() + result_data_structure.step_data_.size());
      bytes->insert(
          bytes->end(),
          result_data_structure.step_data_.begin(),
          result_data_structure.step_data_.end());
      Iterator<packet::kLittleEndian> iterator(bytes);
      switch (mode) {
        case 0: {
          if (role == CsRole::INITIATOR) {
            LeCsMode0InitatorData tone_data_view;
            auto after = LeCsMode0InitatorData::Parse(&tone_data_view, iterator);
            if (after == iterator) {
              log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
              print_raw_data(result_data_structure.step_data_);
              continue;
            }
            log::verbose("step_data: {}", tone_data_view.ToString());
            procedure_data.measured_freq_offset.push_back(tone_data_view.measured_freq_offset_);
          } else {
            LeCsMode0ReflectorData tone_data_view;
            auto after = LeCsMode0ReflectorData::Parse(&tone_data_view, iterator);
            if (after == iterator) {
              log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
              print_raw_data(result_data_structure.step_data_);
              continue;
            }
            log::verbose("step_data: {}", tone_data_view.ToString());
          }
        } break;
        case 2: {
          LeCsMode2Data tone_data_view;
          auto after = LeCsMode2Data::Parse(&tone_data_view, iterator);
          if (after == iterator) {
            log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
            print_raw_data(result_data_structure.step_data_);
            continue;
          }
          log::verbose("step_data: {}", tone_data_view.ToString());
          if (role == CsRole::INITIATOR) {
            procedure_data.step_channel.push_back(step_channel);
          }
          auto tone_data = tone_data_view.tone_data_;
          uint8_t permutation_index = tone_data_view.antenna_permutation_index_;
          // Parse in ascending order of antenna position with tone extension data at the end
          uint16_t num_tone_data = num_antenna_paths + 1;
          for (uint16_t k = 0; k < num_tone_data; k++) {
            uint8_t antenna_path = k == num_antenna_paths
                                       ? num_antenna_paths
                                       : cs_antenna_permutation_array_[permutation_index][k] - 1;
            double i_value = get_iq_value(tone_data[k].i_sample_);
            double q_value = get_iq_value(tone_data[k].q_sample_);
            uint8_t tone_quality_indicator = tone_data[k].tone_quality_indicator_;
            log::verbose(
                "antenna_path {}, {:f}, {:f}", (uint16_t)(antenna_path + 1), i_value, q_value);
            if (role == CsRole::INITIATOR) {
              procedure_data.tone_pct_initiator[antenna_path].emplace_back(i_value, q_value);
              procedure_data.tone_quality_indicator_initiator[antenna_path].emplace_back(
                  tone_quality_indicator);
            } else {
              procedure_data.tone_pct_reflector[antenna_path].emplace_back(i_value, q_value);
              procedure_data.tone_quality_indicator_reflector[antenna_path].emplace_back(
                  tone_quality_indicator);
            }
          }
        } break;
        case 1:
        case 3:
          log::debug("Unsupported mode: {}", mode);
          break;
        default: {
          log::warn("Invalid mode {}", mode);
        }
      }
    }
  }

  double get_iq_value(uint16_t sample) {
    int16_t signed_sample = convert_to_signed(sample, 12);
    double value = 1.0 * signed_sample / 2048;
    return value;
  }

  int16_t convert_to_signed(uint16_t num_unsigned, uint8_t bits) {
    unsigned msb_mask = 1 << (bits - 1);  // setup a mask for most significant bit
    int16_t num_signed = num_unsigned;
    if ((num_signed & msb_mask) != 0) {
      num_signed |= ~(msb_mask - 1);  // extend the MSB
    }
    return num_signed;
  }

  void print_raw_data(std::vector<uint8_t> raw_data) {
    std::string raw_data_str = "";
    auto for_end = raw_data.size() - 1;
    for (size_t i = 0; i < for_end; i++) {
      char buff[10];
      snprintf(buff, sizeof(buff), "%02x ", (uint8_t)raw_data[i]);
      std::string buffAsStdStr = buff;
      raw_data_str.append(buffAsStdStr);
      if (i % 100 == 0 && i != 0) {
        log::verbose("{}", raw_data_str);
        raw_data_str = "";
      }
    }
    char buff[10];
    snprintf(buff, sizeof(buff), "%02x", (uint8_t)raw_data[for_end]);
    std::string buffAsStdStr = buff;
    raw_data_str.append(buffAsStdStr);
    log::verbose("{}", raw_data_str);
  }

  void on_read_remote_transmit_power_level_status(Address address, CommandStatusView view) {
    auto status_view = LeReadRemoteTransmitPowerLevelStatusView::Create(view);
    if (!status_view.IsValid()) {
      log::warn("Invalid LeReadRemoteTransmitPowerLevelStatus event");
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
    } else if (status_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(status_view.GetStatus());
      log::warn("Received LeReadRemoteTransmitPowerLevelStatus with error code {}", error_code);
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
    }
  }

  void on_transmit_power_reporting(LeMetaEventView event) {
    auto event_view = LeTransmitPowerReportingView::Create(event);
    if (!event_view.IsValid()) {
      log::warn("Dropping invalid LeTransmitPowerReporting event");
      return;
    }

    if (event_view.GetReason() == ReportingReason::LOCAL_TRANSMIT_POWER_CHANGED) {
      log::warn("Dropping local LeTransmitPowerReporting event");
      return;
    }

    Address address = Address::kEmpty;
    for (auto& rssi_tracker : rssi_trackers) {
      if (rssi_tracker.second.handle == event_view.GetConnectionHandle()) {
        address = rssi_tracker.first;
      }
    }

    if (address.IsEmpty()) {
      log::warn("Can't find rssi tracker for connection {}", event_view.GetConnectionHandle());
      return;
    }

    auto status = event_view.GetStatus();
    if (status != ErrorCode::SUCCESS) {
      log::warn("Received LeTransmitPowerReporting with error code {}", ErrorCodeText(status));
    } else {
      rssi_trackers[address].remote_tx_power = event_view.GetTransmitPowerLevel();
    }

    if (event_view.GetReason() == ReportingReason::READ_COMMAND_COMPLETE &&
        !rssi_trackers[address].started) {
      if (status == ErrorCode::SUCCESS) {
        hci_layer_->EnqueueCommand(
            LeSetTransmitPowerReportingEnableBuilder::Create(
                event_view.GetConnectionHandle(), 0x00, 0x01),
            handler_->BindOnceOn(
                this, &impl::on_set_transmit_power_reporting_enable_complete, address));
      } else {
        log::warn("Read remote transmit power level fail");
        distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
            address, REASON_INTERNAL_ERROR, METHOD_RSSI);
        rssi_trackers.erase(address);
      }
    }
  }

  void on_set_transmit_power_reporting_enable_complete(Address address, CommandCompleteView view) {
    auto complete_view = LeSetTransmitPowerReportingEnableCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Invalid LeSetTransmitPowerReportingEnableComplete event");
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn(
          "Received LeSetTransmitPowerReportingEnableComplete with error code {}", error_code);
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
      return;
    }

    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      log::warn("Can't find rssi tracker for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
    } else {
      log::info("Track rssi for address {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      rssi_trackers[address].started = true;
      distance_measurement_callbacks_->OnDistanceMeasurementStarted(address, METHOD_RSSI);
      rssi_trackers[address].repeating_alarm->Schedule(
          common::Bind(&impl::send_read_rssi, common::Unretained(this), address),
          std::chrono::milliseconds(rssi_trackers[address].interval_ms));
    }
  }

  void on_read_rssi_complete(Address address, CommandCompleteView view) {
    auto complete_view = ReadRssiCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Dropping invalid read RSSI complete event");
      return;
    }
    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      log::warn("Can't find rssi tracker for {}", ADDRESS_TO_LOGGABLE_CSTR(address));
      return;
    }
    double remote_tx_power = (int8_t)rssi_trackers[address].remote_tx_power;
    int8_t rssi = complete_view.GetRssi();
    double pow_value = (remote_tx_power - rssi - kRSSIDropOffAt1M) / 20.0;
    double distance = pow(10.0, pow_value);
    distance_measurement_callbacks_->OnDistanceMeasurementResult(
        address,
        distance * 100,
        distance * 100,
        -1,
        -1,
        -1,
        -1,
        DistanceMeasurementMethod::METHOD_RSSI);
  }

  std::vector<uint8_t> builder_to_bytes(std::unique_ptr<PacketBuilder<true>> builder) {
    std::shared_ptr<std::vector<uint8_t>> bytes = std::make_shared<std::vector<uint8_t>>();
    BitInserter bi(*bytes);
    builder->Serialize(bi);
    return *bytes;
  }

  void append_vector(std::vector<uint8_t>& v1, const std::vector<uint8_t>& v2) {
    v1.reserve(v2.size());
    v1.insert(v1.end(), v2.begin(), v2.end());
  }

  struct RSSITracker {
    uint16_t handle;
    uint16_t interval_ms;
    uint8_t remote_tx_power;
    bool started;
    std::unique_ptr<os::RepeatingAlarm> repeating_alarm;
  };

  struct CsTracker {
    Address address;
    uint16_t local_counter;
    uint16_t remote_counter;
    CsRole role;
    bool setup_complete = false;
    bool config_set = false;
    CsMainModeType main_mode_type;
    CsSubModeType sub_mode_type;
    CsRttType rtt_type;
    bool remote_support_phase_based_ranging = false;
    uint8_t config_id = 0;
    uint8_t selected_tx_power = 0;
    std::vector<CsProcedureData> procedure_data_list;
    uint16_t interval_ms;
    bool waiting_for_start_callback = false;
    std::unique_ptr<os::RepeatingAlarm> repeating_alarm;
    // RAS data
    RangingHeader ranging_header_;
    PacketViewForRecombination segment_data_;
  };

  os::Handler* handler_;
  hci::HciLayer* hci_layer_;
  hci::AclManager* acl_manager_;
  bool is_channel_sounding_supported_ = false;
  hci::DistanceMeasurementInterface* distance_measurement_interface_;
  std::unordered_map<Address, RSSITracker> rssi_trackers;
  std::unordered_map<uint16_t, CsTracker> cs_trackers_;
  DistanceMeasurementCallbacks* distance_measurement_callbacks_;
  CsOptionalSubfeaturesSupported cs_subfeature_supported_;
  // Antenna path permutations. See Channel Sounding CR_PR for the details.
  uint8_t cs_antenna_permutation_array_[24][4] = {
      {1, 2, 3, 4}, {2, 1, 3, 4}, {1, 3, 2, 4}, {3, 1, 2, 4}, {3, 2, 1, 4}, {2, 3, 1, 4},
      {1, 2, 4, 3}, {2, 1, 4, 3}, {1, 4, 2, 3}, {4, 1, 2, 3}, {4, 2, 1, 3}, {2, 4, 1, 3},
      {1, 4, 3, 2}, {4, 1, 3, 2}, {1, 3, 4, 2}, {3, 1, 4, 2}, {3, 4, 1, 2}, {4, 3, 1, 2},
      {4, 2, 3, 1}, {2, 4, 3, 1}, {4, 3, 2, 1}, {3, 4, 2, 1}, {3, 2, 4, 1}, {2, 3, 4, 1}};
};

DistanceMeasurementManager::DistanceMeasurementManager() {
  pimpl_ = std::make_unique<impl>();
}

DistanceMeasurementManager::~DistanceMeasurementManager() = default;

void DistanceMeasurementManager::ListDependencies(ModuleList* list) const {
  list->add<hci::HciLayer>();
  list->add<hci::AclManager>();
}

void DistanceMeasurementManager::Start() {
  pimpl_->start(GetHandler(), GetDependency<hci::HciLayer>(), GetDependency<AclManager>());
}

void DistanceMeasurementManager::Stop() {
  pimpl_->stop();
}

std::string DistanceMeasurementManager::ToString() const {
  return "Distance Measurement Manager";
}

void DistanceMeasurementManager::RegisterDistanceMeasurementCallbacks(
    DistanceMeasurementCallbacks* callbacks) {
  CallOn(pimpl_.get(), &impl::register_distance_measurement_callbacks, callbacks);
}

void DistanceMeasurementManager::StartDistanceMeasurement(
    const Address& address, uint16_t interval, DistanceMeasurementMethod method) {
  CallOn(pimpl_.get(), &impl::start_distance_measurement, address, interval, method);
}

void DistanceMeasurementManager::StopDistanceMeasurement(
    const Address& address, DistanceMeasurementMethod method) {
  CallOn(pimpl_.get(), &impl::stop_distance_measurement, address, method);
}

void DistanceMeasurementManager::HandleRemoteData(
    const Address& address, const std::vector<uint8_t> raw_data) {
  CallOn(pimpl_.get(), &impl::handle_remote_data, address, raw_data);
}

}  // namespace hci
}  // namespace bluetooth
