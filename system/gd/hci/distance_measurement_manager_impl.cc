/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <math.h>
#include <utils/SystemClock.h>

#include <chrono>
#include <complex>
#include <unordered_map>

#include "acl_manager/assembler.h"
#include "channel_sounding/cs_metrics.h"
#include "hal/ranging_hal.h"
#include "hci/acl_manager/acl_manager_le.h"
#include "hci/controller.h"
#include "hci/distance_measurement_interface.h"
#include "hci/event_checkers.h"
#include "hci/hci_interface.h"
#include "os/alarm.h"
#include "os/handler.h"
#include "os/repeating_alarm.h"
#include "packet/packet_view.h"
#include "ras/ras_packets.h"

using namespace bluetooth::ras;
using android::bluetooth::ChannelSoundingSecurityLevel;
using android::bluetooth::ChannelSoundingStopReason;
using bluetooth::hal::ProcedureDataV2;
using bluetooth::hal::RangingSessionType;
using bluetooth::hci::acl_manager::PacketViewForRecombination;

namespace bluetooth {
namespace hci {
// valid azimuth angle degree value is from 0 to 360.
static constexpr int kInvalidAzimuthAngleDegree = -1;
// valid altitude angle degree value is from -90 to 90
static constexpr int kInvalidAltitudeAngleDegree = -91;
static constexpr double kInvalidDelayedSpreadMeters = -1.0;
static constexpr int8_t kInvalidConfidenceLevel = -1;
static constexpr int8_t kInvalidRemoteTxPower = 127;
static constexpr int8_t kInvalidRssi = 127;
static constexpr double kInvalidVelocityMetersPerSecond = -1.0;
static constexpr uint16_t kIllegalConnectionHandle = 0xffff;
static constexpr uint8_t kTxPowerNotAvailable = 0xfe;
static constexpr int8_t kRSSIDropOffAt1M = 41;
static constexpr uint8_t kCsMaxTxPower = 10;  // 10 dBm
static constexpr CsSyncAntennaSelection kCsSyncAntennaSelection =
        CsSyncAntennaSelection::ANTENNAS_IN_ORDER;
static constexpr uint8_t kMinMainModeSteps = 0x02;
static constexpr uint8_t kMaxMainModeSteps = 0x05;
static constexpr uint8_t kMainModeRepetition = 0x00;  // No repetition
static constexpr uint8_t kMode0Steps =
        0x03;  // Maximum number of mode-0 steps to increase success subevent rate
static constexpr uint8_t kChannelMapRepetition = 0x01;  // No repetition
static constexpr uint8_t kCh3cJump = 0x03;              // Skip 3 Channels
static constexpr uint16_t kMaxProcedureLen = 0x2710;    // 6.25s
static constexpr uint16_t kMinProcedureInterval = 0x01;
static constexpr uint16_t kMaxProcedureInterval = 0xFF;
static constexpr uint32_t kMinSubeventLen = 0x0004E2;  // 1250us
static constexpr uint32_t kMaxSubeventLen = 0x1E8480;  // 2s
static constexpr uint8_t kTxPwrDelta = 0x00;
static constexpr uint8_t kProcedureDataBufferSize = 0x10;  // Buffer size of Procedure data
static constexpr uint16_t kRangingCounterMask = 0x0FFF;
static constexpr uint8_t kInvalidConfigId = 0xFF;
static constexpr uint8_t kMinConfigId = 0;
static constexpr uint8_t kMaxConfigId = 3;
static constexpr uint16_t kDefaultIntervalMs = 1000;  // 1s
static constexpr uint8_t kMaxRetryCounterForReadRemoteCapability = 0x03;
static constexpr uint8_t kMaxRetryCounterForCreateConfig = 0x03;
static constexpr uint8_t kMaxRetryCounterForSetProcedureParameter = 0x0a;
static constexpr uint8_t kMaxRetryCounterForCsEnable = 0x03;
static constexpr uint16_t kCommandRetryIntervalMs = 300;  // 300 ms
static constexpr uint16_t kInvalidConnInterval = 0;  // valid value is from 0x0006 to 0x0C80
static constexpr uint16_t kDefaultRasMtu = 247;      // Section 3.1.2 of RAP 1.0
static constexpr uint8_t kAttHeaderSize = 5;         // Section 3.2.2.1 of RAS 1.0
static constexpr uint8_t kRasSegmentHeaderSize = 1;
static constexpr uint16_t kEnableSecurityTimeoutMs = 10000;  // 10s
static constexpr uint16_t kProcedureScheduleGuardMs = 1000;  // 1s
static constexpr double kConnIntervalUnitMs = 1.25;          // 1.25 ms

struct DistanceMeasurementManagerImpl::impl : bluetooth::hal::RangingHalCallback {
  struct CsProcedureData {
    CsProcedureData(uint16_t procedure_counter, uint8_t num_antenna_paths, uint8_t configuration_id,
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
      // Updated to 'Previously used' per Erratum 26610; kept original logic for backward
      // compatibility
      ranging_header_.antenna_paths_mask_ = 0;
      for (uint8_t i = 0; i < num_antenna_paths; i++) {
        ranging_header_.antenna_paths_mask_ |= (1 << i);
      }
      procedure_data_v2_.local_selected_tx_power_ = selected_tx_power;
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
    std::vector<int8_t> packet_quality_initiator;
    std::vector<int8_t> packet_quality_reflector;
    std::vector<int16_t> toa_tod_initiators;
    std::vector<int16_t> tod_toa_reflectors;
    std::vector<int8_t> rssi_reflector;
    bool contains_sounding_sequence_local_;
    bool contains_sounding_sequence_remote_;
    CsProcedureDoneStatus local_status;
    CsProcedureDoneStatus remote_status;
    // If any subevent is received with a Subevent_Done_Status of 0x0 (All results complete for the
    // CS subevent)
    bool contains_complete_subevent_ = false;
    bool contains_invalid_data_ = false;
    // RAS data
    SegmentationHeader segmentation_header_;
    RangingHeader ranging_header_;
    std::vector<uint8_t> ras_raw_data_;  // raw data for multi_subevents;
    uint16_t ras_raw_data_index_ = 0;
    RasSubeventHeader ras_subevent_header_;
    std::vector<uint8_t> ras_subevent_data_;
    uint8_t ras_subevent_counter_ = 0;

    // procedure data for HAL v2
    ProcedureDataV2 procedure_data_v2_;
  };
  struct RSSITracker {
    uint16_t handle;
    uint16_t interval_ms;
    uint8_t remote_tx_power;
    bool started;
    std::unique_ptr<os::RepeatingAlarm> repeating_alarm;
  };

  // TODO: use state machine to manage the tracker.
  enum class CsTrackerState : uint8_t {
    UNSPECIFIED = 0x00,
    STOPPED = 1 << 0,
    INIT = 1 << 1,
    RAS_CONNECTED = 1 << 2,
    WAIT_FOR_CONFIG_COMPLETE = 1 << 3,
    WAIT_FOR_SECURITY_ENABLED = 1 << 4,
    WAIT_FOR_PROCEDURE_ENABLED = 1 << 5,
    STARTED = 1 << 6,
  };

  struct CsTracker {
    CsTrackerState state = CsTrackerState::STOPPED;
    Address address;
    hci::Role local_hci_role = hci::Role::CENTRAL;
    uint16_t procedure_counter = 0;
    uint16_t procedure_counting_after_enable = 0;
    CsRole role = CsRole::INITIATOR;
    bool local_start = false;  // If the CS was started by the local device.
    // TODO: clean up, replace the measurement_ongoing with STOPPED
    bool measurement_ongoing = false;
    bool ras_connected = false;
    bool setup_complete = false;
    uint8_t retry_counter_for_read_remote_capability = 0;
    uint8_t retry_counter_for_create_config = 0;
    uint8_t retry_counter_for_set_procedure_parameter = 0;
    uint8_t retry_counter_for_cs_enable = 0;
    uint16_t n_procedure_count = 0;
    CsMainModeType main_mode_type = CsMainModeType::MODE_2;
    CsSubModeType sub_mode_type = CsSubModeType::UNUSED;
    CsRttType rtt_type = CsRttType::RTT_AA_ONLY;
    bool remote_support_phase_based_ranging = false;
    uint8_t remote_num_antennas_supported_ = 0x01;
    uint8_t remote_supported_sw_time_ = 0;
    uint8_t remote_max_antenna_paths_supported_ = 0x01;
    // sending from host to controller with CS config command, request the controller to use it.
    uint8_t requesting_config_id = kInvalidConfigId;
    // received from controller to host with CS config complete event, it will be used
    // for the following measurement.
    uint8_t used_config_id = kInvalidConfigId;
    uint8_t selected_tx_power = 0;
    std::vector<CsProcedureData> procedure_data_list = {};
    uint16_t interval_ms = kDefaultIntervalMs;
    uint16_t max_procedure_count = 1;
    bool waiting_for_start_callback = false;
    std::unique_ptr<os::Alarm> procedure_schedule_guard_alarm = nullptr;
    int reflector_rssi_sum = 0;
    int reflector_rssi_count = 0;
    // RAS data
    RangingHeader ranging_header_;
    PacketViewForRecombination segment_data_;
    uint16_t conn_interval_ = kInvalidConnInterval;
    uint8_t procedure_sequence_after_enable = -1;
    std::unique_ptr<os::Alarm> enable_security_timeout_alarm = nullptr;
    bool sent_procedure_disable_after_stopping = false;
    DistanceMeasurementSightType sight_type = DistanceMeasurementSightType::SIGHT_TYPE_UNKNOWN;
    DistanceMeasurementLocationType location_type =
            DistanceMeasurementLocationType::LOCATION_TYPE_UNKNOWN;
    std::unique_ptr<cs::RequesterSessionMetrics> requester_metrics_ = nullptr;
  };

  bool get_free_config_id(uint16_t connection_handle, uint8_t& config_id) {
    uint8_t requester_used_config_id = kInvalidConfigId;
    if (cs_requester_trackers_.find(connection_handle) != cs_requester_trackers_.end()) {
      requester_used_config_id = cs_requester_trackers_[connection_handle].used_config_id;
    }
    uint8_t responder_used_config_id = kInvalidConfigId;
    if (cs_responder_trackers_.find(connection_handle) != cs_responder_trackers_.end()) {
      responder_used_config_id = cs_responder_trackers_[connection_handle].used_config_id;
    }

    for (auto i = kMinConfigId; i <= kMaxConfigId; i++) {
      if (i != requester_used_config_id && i != responder_used_config_id) {
        config_id = i;
        return true;
      }
    }
    log::warn("config ids are used up.");
    return false;
  }

  void OnOpened(
          uint16_t connection_handle,
          const std::vector<bluetooth::hal::VendorSpecificCharacteristic>& vendor_specific_reply) {
    log::info("connection_handle:0x{:04x}, vendor_specific_reply size:{}", connection_handle,
              vendor_specific_reply.size());
    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::error("Can't find CS tracker for connection_handle {}", connection_handle);
      return;
    }

    auto& tracker = cs_requester_trackers_[connection_handle];
    if (!vendor_specific_reply.empty()) {
      // Send reply to remote
      distance_measurement_callbacks_->OnVendorSpecificReply(tracker.address,
                                                             vendor_specific_reply);
      return;
    }

    start_distance_measurement_with_cs(tracker.address, connection_handle, false);
  }

  void OnOpenFailed(uint16_t connection_handle) {
    log::info("connection_handle:0x{:04x}", connection_handle);
    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::error("Can't find CS tracker for connection_handle {}", connection_handle);
      return;
    }
    distance_measurement_callbacks_->OnDistanceMeasurementStopped(
            cs_requester_trackers_[connection_handle].address, REASON_INTERNAL_ERROR, METHOD_CS);
    report_session_metrics_on_stop(*cs_requester_trackers_[connection_handle].requester_metrics_,
                                   ChannelSoundingStopReason::REASON_HAL_OPEN_FAILED);
  }

  void OnClosed(uint16_t connection_handle, hal::Reason reason) {
    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::error("Can't find CS tracker for connection_handle {}", connection_handle);
      return;
    }
    log::info("Session closed, connection_handle: {}, reason: {}", connection_handle,
              static_cast<uint8_t>(reason));
    auto& tracker = cs_requester_trackers_[connection_handle];
    if (tracker.measurement_ongoing && tracker.local_start) {
      cs_requester_trackers_[connection_handle].procedure_schedule_guard_alarm->Cancel();
      send_le_cs_procedure_enable(connection_handle, Enable::DISABLED);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(
              tracker.address, REASON_INTERNAL_ERROR, METHOD_CS);
    }
    reset_tracker_on_stopped(tracker);
    // TODO: b/425866868 - Add ChannelSoundingStopReason for session close.
    report_session_metrics_on_stop(*tracker.requester_metrics_,
                                   ChannelSoundingStopReason::REASON_UNSPECIFIED);
  }

  void OnHandleVendorSpecificReplyComplete(uint16_t connection_handle, bool success) {
    log::info("connection_handle:0x{:04x}, success:{}", connection_handle, success);
    auto it = cs_responder_trackers_.find(connection_handle);
    if (it == cs_responder_trackers_.end()) {
      log::error("Can't find CS tracker for connection_handle {}", connection_handle);
      return;
    }
    distance_measurement_callbacks_->OnHandleVendorSpecificReplyComplete(it->second.address,
                                                                         success);
  }

  void OnResult(uint16_t connection_handle, const bluetooth::hal::RangingResult& ranging_result) {
    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::warn("Can't find CS tracker for connection_handle {}", connection_handle);
      return;
    }
    uint64_t elapsedRealtimeNanos = ::android::elapsedRealtimeNano();
    if (is_hal_v2() && ranging_result.elapsed_timestamp_nanos_ != 0) {
      elapsedRealtimeNanos = ranging_result.elapsed_timestamp_nanos_;
    }
    log::info("address:{}, resultMeters:{}, confidence_level_:{}, elapsedRealtimeNanos:{}",
              cs_requester_trackers_[connection_handle].address, ranging_result.result_meters_,
              ranging_result.confidence_level_, elapsedRealtimeNanos);

    int reflector_rssi = kInvalidRssi;
    if (com_android_bluetooth_flags_include_power_and_rssi_in_distance_measurement_result()) {
      int rssi_count = cs_requester_trackers_[connection_handle].reflector_rssi_count;
      if (rssi_count > 0) {
        reflector_rssi = cs_requester_trackers_[connection_handle].reflector_rssi_sum / rssi_count;
      }
      cs_requester_trackers_[connection_handle].reflector_rssi_sum = 0;
      cs_requester_trackers_[connection_handle].reflector_rssi_count = 0;
    }

    distance_measurement_callbacks_->OnDistanceMeasurementResult(
            cs_requester_trackers_[connection_handle].address, ranging_result.result_meters_ * 100,
            ranging_result.error_meters_ * 100, kInvalidAzimuthAngleDegree,
            kInvalidAzimuthAngleDegree, kInvalidAltitudeAngleDegree, kInvalidAltitudeAngleDegree,
            elapsedRealtimeNanos, kInvalidRemoteTxPower, reflector_rssi,
            ranging_result.confidence_level_, ranging_result.delay_spread_meters_,
            static_cast<DistanceMeasurementDetectedAttackLevel>(
                    ranging_result.detected_attack_level_),
            ranging_result.velocity_meters_per_second_, DistanceMeasurementMethod::METHOD_CS);
  }

  impl(os::Handler* handler, hci::HciInterface* hci_layer, hci::Controller* controller,
       hci::AclManagerLe* acl_manager, hal::RangingHal* ranging_hal) {
    handler_ = handler;
    controller_ = controller;
    ranging_hal_ = ranging_hal;
    hci_layer_ = hci_layer;
    acl_manager_ = acl_manager;
    hci_layer_->RegisterLeEventHandler(hci::SubeventCode::TRANSMIT_POWER_REPORTING,
                                       handler_->BindOn(this, &impl::on_transmit_power_reporting));
    if (!controller_->SupportsBleChannelSounding()) {
      log::info("The controller doesn't support Channel Sounding feature.");
      return;
    }
    distance_measurement_interface_ = hci_layer_->GetDistanceMeasurementInterface(
            handler_->BindOn(this, &DistanceMeasurementManagerImpl::impl::handle_event));
    distance_measurement_interface_->EnqueueCommand(
            LeCsReadLocalSupportedCapabilitiesBuilder::Create(),
            handler_->BindOnceOn(this, &impl::on_cs_read_local_supported_capabilities));
    if (ranging_hal_->IsBound()) {
      ranging_hal_->RegisterCallback(this);
    }
  }

  ~impl() {
    stop();
  }

  void stop() {
    hci_layer_->ReleaseDistanceMeasurementInterface();

    hci_layer_->UnregisterLeEventHandler(hci::SubeventCode::TRANSMIT_POWER_REPORTING);
    cs_requester_trackers_.clear();
    cs_responder_trackers_.clear();
  }

  void register_distance_measurement_callbacks(DistanceMeasurementCallbacks* callbacks) {
    distance_measurement_callbacks_ = callbacks;
    if (ranging_hal_->IsBound()) {
      auto vendor_specific_data = ranging_hal_->GetVendorSpecificCharacteristics();
      if (!vendor_specific_data.empty()) {
        distance_measurement_callbacks_->OnVendorSpecificCharacteristics(vendor_specific_data);
      }
    }
  }

  void start_distance_measurement(int32_t app_uid, const Address address,
                                  uint16_t connection_handle, hci::Role local_hci_role,
                                  uint16_t interval, DistanceMeasurementMethod method,
                                  DistanceMeasurementSightType sight_type,
                                  DistanceMeasurementLocationType location_type) {
    log::info("app_uid: {}, Address:{}, method:{}", app_uid, address, method);

    // Remove this check if we support any connection less method
    if (connection_handle == kIllegalConnectionHandle) {
      log::warn("Can't find any LE connection for {}", address);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(
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
          rssi_trackers[address].repeating_alarm =
                  std::make_unique<os::RepeatingAlarm>(&handler_->thread());
          hci_layer_->EnqueueCommand(
                  LeReadRemoteTransmitPowerLevelBuilder::Create(connection_handle, 0x01),
                  handler_->BindOnceOn(this, &impl::on_read_remote_transmit_power_level_status,
                                       address));
        } else {
          rssi_trackers[address].interval_ms = interval;
        }
      } break;
      case METHOD_CS: {
        bool has_updated_procedure_params = false;
        if (init_cs_requester_tracker(app_uid, address, connection_handle, local_hci_role, interval,
                                      &has_updated_procedure_params, sight_type, location_type)) {
          start_distance_measurement_with_cs(address, connection_handle,
                                             has_updated_procedure_params);
        }
      } break;
    }
  }

  static int64_t get_elapsed_realtime_nanos() {
    using namespace std::chrono;
    return duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
  }

  bool init_cs_requester_tracker(int32_t app_uid, const Address& cs_remote_address,
                                 uint16_t connection_handle, hci::Role local_hci_role,
                                 uint16_t interval_ms, bool* has_updated_procedure_params,
                                 DistanceMeasurementSightType sight_type,
                                 DistanceMeasurementLocationType location_type) {
    *has_updated_procedure_params = false;
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it != cs_requester_trackers_.end()) {
      if (it->second.address != cs_remote_address) {
        log::debug("replace old tracker as {}", cs_remote_address);
        it->second = CsTracker();
      }
    } else {
      cs_requester_trackers_[connection_handle] = CsTracker();
      it = cs_requester_trackers_.find(connection_handle);
    }
    if (it->second.state != CsTrackerState::STOPPED) {
      it->second.requester_metrics_->app_uids.push_back(app_uid);
      it->second.requester_metrics_->measurement_interval_ms.push_back(interval_ms);
      log::info("reuse the current ongoing session");
      if (!it->second.waiting_for_start_callback) {
        distance_measurement_callbacks_->OnDistanceMeasurementStarted(cs_remote_address, METHOD_CS);
      }
      return false;
    } else {
      it->second.requester_metrics_ = std::make_unique<cs::RequesterSessionMetrics>();
      it->second.requester_metrics_->security_levels.push_back(
              static_cast<uint8_t>(ChannelSoundingSecurityLevel::LEVEL_ONE));
      it->second.requester_metrics_->remote_addr = cs_remote_address;
      it->second.requester_metrics_->app_uids.push_back(app_uid);
      it->second.requester_metrics_->measurement_interval_ms.push_back(interval_ms);
      it->second.requester_metrics_->ranging_start_timestamp_nanos = get_elapsed_realtime_nanos();
    }

    it->second.address = cs_remote_address;
    if (it->second.used_config_id == kInvalidConfigId) {
      uint8_t config_id;
      if (get_free_config_id(connection_handle, config_id)) {
        it->second.requesting_config_id = config_id;
      } else {
        log::error("No config id available, stop");
        distance_measurement_callbacks_->OnDistanceMeasurementStopped(
                cs_remote_address, REASON_INTERNAL_ERROR, METHOD_CS);
        report_session_metrics_on_stop(*it->second.requester_metrics_,
                                       ChannelSoundingStopReason::REASON_CONFIG_ID_RUN_OUT);
        return false;
      }
    }
    // make sure the repeating_alarm is initialized.
    if (it->second.procedure_schedule_guard_alarm == nullptr) {
      it->second.procedure_schedule_guard_alarm = std::make_unique<os::Alarm>(&handler_->thread());
    }
    it->second.state = CsTrackerState::INIT;

    if (interval_ms != it->second.interval_ms) {
      log::info("Update interval to {}", interval_ms);
      uint16_t max_procedure_count = 1;
      if (interval_ms < 1000) {
        max_procedure_count = 5;
      }
      it->second.max_procedure_count = max_procedure_count;
      *has_updated_procedure_params = true;
    }
    it->second.interval_ms = interval_ms;
    it->second.local_start = true;
    it->second.measurement_ongoing = true;
    it->second.waiting_for_start_callback = true;
    it->second.local_hci_role = local_hci_role;
    it->second.retry_counter_for_read_remote_capability = 0;
    it->second.retry_counter_for_create_config = 0;
    it->second.retry_counter_for_set_procedure_parameter = 0;
    it->second.retry_counter_for_cs_enable = 0;
    it->second.sent_procedure_disable_after_stopping = false;
    it->second.sight_type = sight_type;
    it->second.location_type = location_type;
    return true;
  }

  void start_distance_measurement_with_cs(const Address& cs_remote_address,
                                          uint16_t connection_handle,
                                          bool has_updated_procedure_params) {
    log::info("connection_handle: {}, address: {}, is_hal_v2: {}", connection_handle,
              cs_remote_address, is_hal_v2());
    if (!is_local_cs_ready_) {
      log::error("Channel Sounding is not enabled");
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(
              cs_remote_address, REASON_INTERNAL_ERROR, METHOD_CS);
      report_session_metrics_on_stop(*cs_requester_trackers_[connection_handle].requester_metrics_,
                                     ChannelSoundingStopReason::REASON_LOCAL_CS_STACK_NOT_READY);
      return;
    }

    if (!cs_requester_trackers_[connection_handle].ras_connected) {
      log::info("Waiting for RAS connected");
      return;
    }

    if (!cs_requester_trackers_[connection_handle].setup_complete) {
      send_le_cs_read_remote_supported_capabilities(connection_handle);
      return;
    }
    if (cs_requester_trackers_[connection_handle].used_config_id == kInvalidConfigId) {
      send_le_cs_create_config(connection_handle,
                               cs_requester_trackers_[connection_handle].requesting_config_id);
      return;
    }

    cs_requester_trackers_[connection_handle].procedure_schedule_guard_alarm->Cancel();
    if (has_updated_procedure_params) {
      send_le_cs_set_procedure_parameters(
              connection_handle, cs_requester_trackers_[connection_handle].used_config_id,
              cs_requester_trackers_[connection_handle].remote_num_antennas_supported_,
              cs_requester_trackers_[connection_handle].remote_max_antenna_paths_supported_);
    } else if (com_android_bluetooth_flags_channel_sounding_26q1_fix() &&
               cs_requester_trackers_[connection_handle].local_hci_role == hci::Role::CENTRAL) {
      cs_requester_trackers_[connection_handle].state = CsTrackerState::WAIT_FOR_SECURITY_ENABLED;
      send_le_cs_security_enable(connection_handle, true);
    } else {
      send_le_cs_procedure_enable(connection_handle, Enable::ENABLED);
    }
  }

  void stop_distance_measurement(const Address address, uint16_t connection_handle,
                                 DistanceMeasurementMethod method) {
    log::info("Address:{}, method:{}", address, method);
    switch (method) {
      case METHOD_AUTO:
      case METHOD_RSSI: {
        auto it = rssi_trackers.find(address);
        if (it == rssi_trackers.end()) {
          log::warn("Can't find rssi tracker for {}", address);
        } else {
          hci_layer_->EnqueueCommand(
                  LeSetTransmitPowerReportingEnableBuilder::Create(it->second.handle, 0x00, 0x00),
                  handler_->BindOnce(
                          check_complete<LeSetTransmitPowerReportingEnableCompleteView>));
          it->second.repeating_alarm->Cancel();
          it->second.repeating_alarm.reset();
          rssi_trackers.erase(address);
        }
      } break;
      case METHOD_CS: {
        auto it = cs_requester_trackers_.find(connection_handle);
        if (it == cs_requester_trackers_.end()) {
          log::warn("Can't find CS tracker for {}", address);
        } else if (it->second.measurement_ongoing) {
          it->second.procedure_schedule_guard_alarm->Cancel();
          send_le_cs_procedure_enable(connection_handle, Enable::DISABLED);
          // does not depend on the 'disable' command result.
          reset_tracker_on_stopped(it->second);
          report_session_metrics_on_stop(*it->second.requester_metrics_,
                                         ChannelSoundingStopReason::REASON_LOCAL_APP_REQUEST);
        }
      } break;
    }
  }

  static void report_session_metrics_on_stop(cs::RequesterSessionMetrics& session_metrics,
                                             ChannelSoundingStopReason stop_reason) {
    session_metrics.ranging_end_timestamp_nanos = get_elapsed_realtime_nanos();
    session_metrics.stop_reason = stop_reason;
    if (!session_metrics.reported) {
      cs::LogMetricsChannelSoundingRequesterSessionReported(session_metrics);
      session_metrics.reported = true;
    } else {
      log::warn("ATTENTION! Unexpected duplicated session metrics report.");
    }
  }

  void handle_ras_client_connected_event(
          const Address address, uint16_t connection_handle, uint16_t att_handle,
          const std::vector<hal::VendorSpecificCharacteristic> vendor_specific_data,
          uint16_t conn_interval) {
    log::info(
            "address:{}, connection_handle 0x{:04x}, att_handle 0x{:04x}, size of "
            "vendor_specific_data {}, conn_interval {}",
            address, connection_handle, att_handle, vendor_specific_data.size(), conn_interval);

    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      log::warn("can't find tracker for 0x{:04x}", connection_handle);
      return;
    }
    if (it->second.ras_connected) {
      log::debug("Already connected");
      return;
    }
    it->second.conn_interval_ = conn_interval;
    it->second.ras_connected = true;
    it->second.state = CsTrackerState::RAS_CONNECTED;

    if (ranging_hal_->IsBound()) {
      auto session_types = ranging_hal_->GetSupportedSessionTypes();
      for (auto session_type : session_types) {
        if (session_type == RangingSessionType::HARDWARE_OFFLOAD_DATA_PARSING) {
          distance_measurement_callbacks_->OnRangingHardwareOffloadEnabled();
        }
      }
      ranging_hal_->OpenSession(connection_handle, att_handle, vendor_specific_data,
                                static_cast<uint8_t>(it->second.sight_type),
                                static_cast<uint8_t>(it->second.location_type));
      return;
    }
    start_distance_measurement_with_cs(it->second.address, connection_handle, false);
  }

  void handle_conn_interval_updated(const Address& address, uint16_t connection_handle,
                                    uint16_t conn_interval) {
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      log::warn("can't find tracker for 0x{:04x}, address - {} ", connection_handle, address);
      return;
    }
    log::info("interval updated as {}", conn_interval);
    it->second.conn_interval_ = conn_interval;
    if (is_hal_v2() && it->second.state >= CsTrackerState::WAIT_FOR_CONFIG_COMPLETE) {
      log::info("send conn interval {} to HAL", conn_interval);
      ranging_hal_->UpdateConnInterval(connection_handle, conn_interval);
      // should the measurement be started over?
    }
  }

  void handle_ras_client_disconnected_event(const Address address,
                                            const ras::RasDisconnectReason& ras_disconnect_reason) {
    log::info("address:{}", address);
    for (auto it = cs_requester_trackers_.begin(); it != cs_requester_trackers_.end();) {
      if (it->second.address == address) {
        if (it->second.procedure_schedule_guard_alarm != nullptr) {
          it->second.procedure_schedule_guard_alarm->Cancel();
          it->second.procedure_schedule_guard_alarm.reset();
        }
        if (it->second.state != CsTrackerState::STOPPED) {
          DistanceMeasurementErrorCode reason = REASON_NO_LE_CONNECTION;
          ChannelSoundingStopReason stop_reason = ChannelSoundingStopReason::REASON_LE_DISCONNECT;
          if (ras_disconnect_reason == ras::RasDisconnectReason::SERVER_NOT_AVAILABLE) {
            reason = REASON_FEATURE_NOT_SUPPORTED_REMOTE;
            stop_reason = ChannelSoundingStopReason::REASON_RAS_REMOTE_NOT_SUPPORT;
          } else if (ras_disconnect_reason == ras::RasDisconnectReason::FATAL_ERROR) {
            reason = REASON_INTERNAL_ERROR;
            stop_reason = ChannelSoundingStopReason::REASON_RAS_FATAL_ERROR;
          }
          distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, reason, METHOD_CS);
          report_session_metrics_on_stop(*it->second.requester_metrics_, stop_reason);
        }

        gatt_mtus_.erase(it->first);
        it = cs_requester_trackers_.erase(it);  // erase and get the next iterator
      } else {
        ++it;
      }
    }
  }

  void handle_ras_server_vendor_specific_reply(
          const Address& address, uint16_t connection_handle,
          const std::vector<hal::VendorSpecificCharacteristic> vendor_specific_reply) {
    auto it = cs_responder_trackers_.find(connection_handle);
    if (it == cs_responder_trackers_.end()) {
      log::info("no cs tracker found for {}", connection_handle);
      return;
    }
    if (it->second.address != address) {
      log::info("the cs tracker address was changed as {}, not {}.", it->second.address, address);
      return;
    }
    if (ranging_hal_->IsBound()) {
      ranging_hal_->HandleVendorSpecificReply(connection_handle, vendor_specific_reply);
      return;
    }
  }

  void handle_ras_server_connected(const Address& identity_address, uint16_t connection_handle,
                                   hci::Role local_hci_role) {
    log::info("initialize the responder tracker for {} with {}", connection_handle,
              identity_address);
    // create CS tracker to serve the ras_server
    auto it = cs_responder_trackers_.find(connection_handle);
    if (it != cs_responder_trackers_.end()) {
      if (it->second.address != identity_address) {
        log::debug("Remove old tracker for {}", identity_address);
        it->second = CsTracker();
      }
    } else {
      cs_responder_trackers_[connection_handle] = CsTracker();
      it = cs_responder_trackers_.find(connection_handle);
    }
    it->second.state = CsTrackerState::RAS_CONNECTED;
    it->second.address = identity_address;
    it->second.local_start = false;
    it->second.local_hci_role = local_hci_role;
  }

  void handle_mtu_changed(uint16_t connection_handle, uint16_t mtu) {
    log::info("gatt mtu is changed as {}", mtu);
    gatt_mtus_[connection_handle] = mtu;
  }

  uint16_t get_ras_raw_payload_size(uint16_t connection_handle) {
    auto it = gatt_mtus_.find(connection_handle);
    uint16_t mtu = kDefaultRasMtu;
    if (it != gatt_mtus_.end()) {
      mtu = gatt_mtus_[connection_handle];
    }
    return mtu - kAttHeaderSize - kRasSegmentHeaderSize;
  }

  void handle_ras_server_disconnected(const Address& identity_address, uint16_t connection_handle) {
    auto it = cs_responder_trackers_.find(connection_handle);
    if (it == cs_responder_trackers_.end()) {
      log::info("no CS tracker available.");
      return;
    }
    if (it->second.address != identity_address) {
      log::info("cs tracker connection is associated with device {}, not device {}",
                it->second.address, identity_address);
      return;
    }
    cs_responder_trackers_.erase(connection_handle);
    gatt_mtus_.erase(connection_handle);
  }

  void handle_vendor_specific_reply_complete(const Address address, uint16_t connection_handle,
                                             bool success) {
    log::info("address:{}, connection_handle:0x{:04x}, success:{}", address, connection_handle,
              success);
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      log::warn("can't find tracker for 0x{:04x}", connection_handle);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_CS);
      return;
    }

    if (!success) {
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_CS);
      report_session_metrics_on_stop(
              *it->second.requester_metrics_,
              ChannelSoundingStopReason::REASON_VENDOR_SPECIFIC_REPLY_FAILED);
      return;
    }

    start_distance_measurement_with_cs(it->second.address, connection_handle, false);
  }

  void send_read_rssi(const Address& address, uint16_t connection_handle) {
    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      log::warn("Can't find rssi tracker for {}", address);
      return;
    }
    Address connection_address = acl_manager_->HACK_GetLeAddress(connection_handle);
    if (connection_address.IsEmpty()) {
      log::warn("Can't find connection for {}", address);
      if (rssi_trackers.find(address) != rssi_trackers.end()) {
        distance_measurement_callbacks_->OnDistanceMeasurementStopped(
                address, REASON_NO_LE_CONNECTION, METHOD_RSSI);
        rssi_trackers[address].repeating_alarm->Cancel();
        rssi_trackers[address].repeating_alarm.reset();
        rssi_trackers.erase(address);
      }
      return;
    }

    hci_layer_->EnqueueCommand(ReadRssiBuilder::Create(connection_handle),
                               handler_->BindOnceOn(this, &impl::on_read_rssi_complete, address));
  }

  void handle_event(LeMetaEventView event) {
    if (!event.IsValid()) {
      log::error("Received invalid LeMetaEventView");
      return;
    }
    switch (event.GetSubeventCode()) {
      case hci::SubeventCode::LE_CS_TEST_END_COMPLETE: {
        log::warn("Unhandled subevent {}", hci::SubeventCodeText(event.GetSubeventCode()));
      } break;
      case hci::SubeventCode::LE_CS_READ_REMOTE_FAE_TABLE_COMPLETE: {
        on_cs_read_remote_fae_table_complete(LeCsReadRemoteFaeTableCompleteView::Create(event));
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
    log::info("connection_handle:0x{:04x}", connection_handle);
    hci_layer_->EnqueueCommand(
            LeCsReadRemoteSupportedCapabilitiesBuilder::Create(connection_handle),
            handler_->BindOnceOn(
                    this, &impl::on_cs_setup_command_status_cb, connection_handle,
                    ChannelSoundingStopReason::REASON_READ_REMOTE_CAP_COMMAND_STATUS_ERROR));
  }

  void send_le_cs_security_enable(uint16_t connection_handle, bool local_start) {
    log::info("connection_handle:0x{:04x}, local_start:{}", connection_handle, local_start);
    if (local_start) {
      auto req_it = cs_requester_trackers_.find(connection_handle);
      if (req_it == cs_requester_trackers_.end()) {
        log::error("no requester tracker. something wrong.");
      } else if (req_it->second.state == CsTrackerState::WAIT_FOR_CONFIG_COMPLETE) {
        req_it->second.state = CsTrackerState::WAIT_FOR_SECURITY_ENABLED;
      } else if (req_it->second.state != CsTrackerState::WAIT_FOR_SECURITY_ENABLED) {
        log::error("Unexpected state {}", static_cast<uint16_t>(req_it->second.state));
      }
    } else {
      auto res_it = cs_responder_trackers_.find(connection_handle);
      if (res_it != cs_responder_trackers_.end() &&
          res_it->second.state == CsTrackerState::WAIT_FOR_CONFIG_COMPLETE) {
        res_it->second.state = CsTrackerState::WAIT_FOR_SECURITY_ENABLED;
      } else {
        log::error("no responder tracker. something wrong.");
      }
    }

    hci_layer_->EnqueueCommand(
            LeCsSecurityEnableBuilder::Create(connection_handle),
            handler_->BindOnceOn(
                    this, &impl::on_cs_setup_command_status_cb, connection_handle,
                    ChannelSoundingStopReason::REASON_SECURITY_ENABLE_COMMAND_STATUS_ERROR));
  }

  void send_le_cs_set_default_settings(uint16_t connection_handle,
                                       CsSyncAntennaSelection selection) {
    log::info("connection_handle:0x{:04x}", connection_handle);
    uint8_t role_enable = (1 << (uint8_t)CsRole::INITIATOR) | 1 << ((uint8_t)CsRole::REFLECTOR);
    hci_layer_->EnqueueCommand(
            LeCsSetDefaultSettingsBuilder::Create(connection_handle, role_enable, selection,
                                                  kCsMaxTxPower),
            handler_->BindOnceOn(this, &impl::on_cs_set_default_settings_complete, selection));
  }

  void send_le_cs_read_remote_fae_table(uint16_t connection_handle) const {
    log::info("connection_handle:0x{:04x}", connection_handle);
    hci_layer_->EnqueueCommand(LeCsReadRemoteFaeTableBuilder::Create(connection_handle),
                               handler_->BindOnce(check_status<LeCsReadRemoteFaeTableStatusView>));
  }

  void send_le_cs_create_config(uint16_t connection_handle, uint8_t config_id) {
    log::info("connection_handle:0x{:04x}, config_id:{}", connection_handle, config_id);
    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::warn("no cs tracker found for {}", connection_handle);
    }
    log::debug("send cs create config");
    cs_requester_trackers_[connection_handle].state = CsTrackerState::WAIT_FOR_CONFIG_COMPLETE;
    auto channel_vector = common::FromHexString("1FFFFFFFFFFFFC7FFFFC");  // use all 72 Channels
    // If the interval is less than or equal to 1 second, then use half channels
    if (cs_requester_trackers_[connection_handle].interval_ms <= 1000) {
      channel_vector = common::FromHexString("15555555555554555554");
    }
    std::array<uint8_t, 10> channel_map;
    std::copy(channel_vector->begin(), channel_vector->end(), channel_map.begin());
    std::reverse(channel_map.begin(), channel_map.end());
    hci_layer_->EnqueueCommand(
            LeCsCreateConfigBuilder::Create(
                    connection_handle, config_id, CsCreateContext::BOTH_LOCAL_AND_REMOTE_CONTROLLER,
                    CsMainModeType::MODE_2, CsSubModeType::UNUSED, kMinMainModeSteps,
                    kMaxMainModeSteps, kMainModeRepetition, kMode0Steps, CsRole::INITIATOR,
                    CsConfigRttType::RTT_AA_ONLY, CsSyncPhy::LE_1M_PHY, channel_map,
                    kChannelMapRepetition, CsChannelSelectionType::TYPE_3B, CsCh3cShape::HAT_SHAPE,
                    kCh3cJump),
            handler_->BindOnceOn(
                    this, &impl::on_cs_setup_command_status_cb, connection_handle,
                    ChannelSoundingStopReason::REASON_CREATE_CONFIG_COMMAND_STATUS_ERROR));
  }

  /**
   * Selects the best Antenna Configuration Index (ACI) based on the mutually
   * supported maximum number of antenna paths and the number of antennas
   * available on the local and remote devices.
   *
   * The logic prioritizes configurations that use more paths. It uses fallthrough
   * to check for less capable configurations if a more capable one isn't supported
   * by the available antennas.
   */
  uint8_t get_tone_antenna_config_selection(uint8_t remote_num_antennas_supported,
                                            uint8_t max_antenna_paths_supported) {
    if (com_android_bluetooth_flags_channel_sounding_26q1_fix()) {
      return cs_tone_antenna_config_mapping_table_[num_antennas_supported_ - 1]
                                                  [remote_num_antennas_supported - 1];
    }
    switch (max_antenna_paths_supported) {
      case 4:
        // Check for 4-path configurations first.
        if (num_antennas_supported_ >= 2 && remote_num_antennas_supported >= 2) {
          // Prefer the symmetric 2x2 configuration if both devices have at least 2 antennas.
          return 7;  // ACI 7: 2x2 configuration
        } else if (num_antennas_supported_ >= 4) {
          // Check for 4x1 if the local device has 4+ antennas.
          return 3;  // ACI 3: 4x1 configuration
        } else if (remote_num_antennas_supported >= 4) {
          // Check for 1x4 if the remote device has 4+ antennas.
          return 6;  // ACI 6: 1x4 configuration
        }
        // If no 4-path configuration is possible with the available antennas,
        // fall through to check for 3-path configurations.
        ABSL_FALLTHROUGH_INTENDED;
      case 3:
        // Check for 3-path configurations.
        if (num_antennas_supported_ >= 3) {
          return 2;  // ACI 2: 3x1 configuration
        } else if (remote_num_antennas_supported >= 3) {
          return 5;  // ACI 5: 1x3 configuration
        }
        // Fall through to check for 2-path configurations.
        ABSL_FALLTHROUGH_INTENDED;
      case 2:
        // Check for 2-path configurations.
        if (num_antennas_supported_ >= 2) {
          return 1;  // ACI 1: 2x1 configuration
        } else if (remote_num_antennas_supported >= 2) {
          return 4;  // ACI 4: 1x2 configuration
        }
        // Fall through to the default 1-path configuration.
        ABSL_FALLTHROUGH_INTENDED;
      default:
        // This is the baseline 1-path configuration (1x1).
        return 0;  // ACI 0: 1x1 configuration
    }
  }

  void send_le_cs_set_procedure_parameters(uint16_t connection_handle, uint8_t config_id,
                                           uint8_t remote_num_antennas_supported,
                                           uint8_t remote_max_antenna_paths_supported) {
    uint8_t max_antenna_paths_supported =
            std::min(local_max_antenna_paths_supported_, remote_max_antenna_paths_supported);
    uint8_t tone_antenna_config_selection = get_tone_antenna_config_selection(
            remote_num_antennas_supported, max_antenna_paths_supported);
    uint8_t preferred_peer_antenna_value =
            cs_preferred_peer_antenna_mapping_table_[tone_antenna_config_selection];
    log::info(
            "num_antennas_supported:{}, remote_num_antennas_supported:{}, "
            "max_antenna_paths_supported:{},"
            "tone_antenna_config_selection:{}, preferred_peer_antenna:{}",
            num_antennas_supported_, remote_num_antennas_supported, max_antenna_paths_supported,
            tone_antenna_config_selection, preferred_peer_antenna_value);
    CsPreferredPeerAntenna preferred_peer_antenna;
    preferred_peer_antenna.use_first_ordered_antenna_element_ = preferred_peer_antenna_value & 0x01;
    preferred_peer_antenna.use_second_ordered_antenna_element_ =
            (preferred_peer_antenna_value >> 1) & 0x01;
    preferred_peer_antenna.use_third_ordered_antenna_element_ =
            (preferred_peer_antenna_value >> 2) & 0x01;
    preferred_peer_antenna.use_fourth_ordered_antenna_element_ =
            (preferred_peer_antenna_value >> 3) & 0x01;

    // only change the min_procedure_interval, leave the flexibility to the controller
    uint16_t min_procedure_interval = kMinProcedureInterval;
    if (cs_requester_trackers_[connection_handle].max_procedure_count != 1 &&
        cs_requester_trackers_[connection_handle].interval_ms > 100) {
      // TODO(b/398253048): keep the burst mode for 'HIGH' for now. allow app to disable it.
      uint16_t measurement_interval_ms = cs_requester_trackers_[connection_handle].interval_ms;
      min_procedure_interval = static_cast<uint16_t>(std::round(
              (double)measurement_interval_ms /
              (cs_requester_trackers_[connection_handle].conn_interval_ * kConnIntervalUnitMs)));
    }
    log::info("min_procedure_interval:{}, conn_interval:{}", min_procedure_interval,
              cs_requester_trackers_[connection_handle].conn_interval_);
    hci_layer_->EnqueueCommand(
            LeCsSetProcedureParametersBuilder::Create(
                    connection_handle, config_id, kMaxProcedureLen, min_procedure_interval,
                    kMaxProcedureInterval,
                    cs_requester_trackers_[connection_handle].max_procedure_count, kMinSubeventLen,
                    kMaxSubeventLen, tone_antenna_config_selection, CsPhy::LE_1M_PHY, kTxPwrDelta,
                    preferred_peer_antenna, CsSnrControl::NOT_APPLIED, CsSnrControl::NOT_APPLIED),
            handler_->BindOnceOn(this, &impl::on_cs_set_procedure_parameters));
  }

  static void reset_tracker_on_stopped(CsTracker& cs_tracker) {
    cs_tracker.measurement_ongoing = false;
    cs_tracker.state = CsTrackerState::STOPPED;
    cs_tracker.procedure_data_list.clear();
  }

  void handle_cs_setup_failure(
          uint16_t connection_handle, DistanceMeasurementErrorCode errorCode,
          ChannelSoundingStopReason stop_reason = ChannelSoundingStopReason::REASON_UNSPECIFIED) {
    // responder is stateless. only requester needs to handle the set up failure.
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      log::info("no requester tracker is found for {}.", connection_handle);
      return;
    }
    if (it->second.measurement_ongoing) {
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(it->second.address, errorCode,
                                                                    METHOD_CS);
      it->second.procedure_schedule_guard_alarm->Cancel();
      it->second.procedure_schedule_guard_alarm.reset();
    }
    reset_tracker_on_stopped(it->second);
    // the cs_tracker should be kept until the connection is disconnected
    report_session_metrics_on_stop(*it->second.requester_metrics_, stop_reason);
  }

  void send_le_cs_procedure_enable(uint16_t connection_handle, Enable enable) {
    log::debug("cmd {}", enable);
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      log::warn("Can't find cs tracker for connection {}", connection_handle);
      return;
    }

    if (enable == Enable::ENABLED) {
      if (it->second.state == CsTrackerState::STOPPED) {
        log::error("safe guard, error state, no local measurement request.");
        if (it->second.procedure_schedule_guard_alarm) {
          it->second.procedure_schedule_guard_alarm->Cancel();
        }
        return;
      }

      it->second.state = CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED;
    } else {  // Enable::DISABLE
      if (it->second.state != CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED &&
          it->second.state != CsTrackerState::STARTED &&
          it->second.state != CsTrackerState::STOPPED) {
        log::info("no procedure disable command needed for state {}.", (int)it->second.state);
        return;
      }
    }

    hci_layer_->EnqueueCommand(
            LeCsProcedureEnableBuilder::Create(connection_handle, it->second.used_config_id,
                                               enable),
            handler_->BindOnceOn(this, &impl::on_cs_procedure_enable_command_status_cb,
                                 connection_handle, enable));
  }

  void on_cs_procedure_enable_command_status_cb(uint16_t connection_handle, Enable enable,
                                                CommandStatusView status_view) {
    ErrorCode status = status_view.GetStatus();
    // controller may send error if the procedure instance has finished all scheduled procedures.
    if (enable == Enable::DISABLED && status == ErrorCode::COMMAND_DISALLOWED) {
      log::info("ignored the procedure disable command disallow error.");
      if (cs_requester_trackers_.find(connection_handle) != cs_requester_trackers_.end()) {
        reset_tracker_on_stopped(cs_requester_trackers_[connection_handle]);
      }
    } else if (enable == Enable::ENABLED && status_view.GetStatus() != ErrorCode::SUCCESS) {
      auto req_it = cs_requester_trackers_.find(connection_handle);
      if (req_it == cs_requester_trackers_.end() ||
          req_it->second.state != CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED) {
        log::info("Don't expect procedure_enable, ignore the ENABLED with error.");
        return;
      }

      log::error("Error code {} for connection_handle {}. Retry counter {}", ErrorCodeText(status),
                 connection_handle, req_it->second.retry_counter_for_cs_enable);
      if (req_it->second.retry_counter_for_cs_enable++ >= kMaxRetryCounterForCsEnable) {
        handle_cs_setup_failure(
                connection_handle, REASON_INTERNAL_ERROR,
                ChannelSoundingStopReason::REASON_PROCEDURE_ENABLE_COMMAND_STATUS_ERROR);
      } else {
        req_it->second.procedure_schedule_guard_alarm->Cancel();
        log::info("schedule next procedure enable after {} ms", req_it->second.interval_ms);
        req_it->second.procedure_schedule_guard_alarm->Schedule(
                common::Bind(&impl::send_le_cs_procedure_enable, common::Unretained(this),
                             connection_handle, Enable::ENABLED),
                std::chrono::milliseconds(cs_requester_trackers_[connection_handle].interval_ms));
      }
    }
  }

  void on_cs_setup_command_status_cb(uint16_t connection_handle,
                                     ChannelSoundingStopReason potential_stop_reason,
                                     CommandStatusView status_view) {
    ErrorCode status = status_view.GetStatus();
    OpCode op_code = status_view.GetCommandOpCode();
    if (status != ErrorCode::SUCCESS) {
      log::error("Error code {}, opcode {} for connection_handle {}", ErrorCodeText(status),
                 OpCodeText(op_code), connection_handle);
      handle_cs_setup_failure(connection_handle, REASON_INTERNAL_ERROR, potential_stop_reason);
    }
  }

  void on_cs_read_local_supported_capabilities(CommandCompleteView view) {
    auto complete_view = LeCsReadLocalSupportedCapabilitiesCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Get invalid LeCsReadLocalSupportedCapabilitiesComplete");
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn("Received LeCsReadLocalSupportedCapabilitiesComplete with error code {}",
                error_code);
      return;
    }
    cs_subfeature_supported_ = complete_view.GetOptionalSubfeaturesSupported();
    num_antennas_supported_ = complete_view.GetNumAntennasSupported();
    local_support_phase_based_ranging_ = cs_subfeature_supported_.phase_based_ranging_ == 0x01;
    local_supported_sw_time_ = complete_view.GetTSwTimeSupported();
    local_max_antenna_paths_supported_ = complete_view.GetMaxAntennaPathsSupported();
    is_local_cs_ready_ = true;
  }

  void on_cs_read_remote_supported_capabilities_complete(
          LeCsReadRemoteSupportedCapabilitiesCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsReadRemoteSupportedCapabilitiesCompleteView");
      return;
    }
    uint16_t connection_handle = event_view.GetConnectionHandle();
    if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn("Received LeCsReadRemoteSupportedCapabilitiesCompleteView with error code {}",
                error_code);

      if (cs_requester_trackers_[connection_handle].retry_counter_for_read_remote_capability <
          kMaxRetryCounterForReadRemoteCapability) {
        // Get a reference to the tracker
        auto& tracker = cs_requester_trackers_[connection_handle];
        tracker.retry_counter_for_read_remote_capability++;
        log::info(
                "Scheduling retry for send_le_cs_read_remote_supported_capabilities after {} ms, "
                "retry counter {}",
                kCommandRetryIntervalMs, tracker.retry_counter_for_read_remote_capability);

        // Cancel any pending task and schedule the retry with a delay
        tracker.procedure_schedule_guard_alarm->Cancel();
        tracker.procedure_schedule_guard_alarm->Schedule(
                common::Bind(&impl::send_le_cs_read_remote_supported_capabilities,
                             common::Unretained(this), connection_handle),
                std::chrono::milliseconds(kCommandRetryIntervalMs));
      } else {
        handle_cs_setup_failure(connection_handle, REASON_INTERNAL_ERROR,
                                ChannelSoundingStopReason::REASON_READ_REMOTE_CAP_COMPLETE_FAILED);
      }
      return;
    }
    auto res_it = cs_responder_trackers_.find(connection_handle);
    if (res_it != cs_responder_trackers_.end()) {
      res_it->second.remote_support_phase_based_ranging =
              event_view.GetOptionalSubfeaturesSupported().phase_based_ranging_ == 0x01;
    }
    send_le_cs_set_default_settings(connection_handle, kCsSyncAntennaSelection);

    auto req_it = cs_requester_trackers_.find(connection_handle);
    if (req_it != cs_requester_trackers_.end() && req_it->second.measurement_ongoing) {
      req_it->second.remote_support_phase_based_ranging =
              event_view.GetOptionalSubfeaturesSupported().phase_based_ranging_ == 0x01;
      req_it->second.remote_num_antennas_supported_ = event_view.GetNumAntennasSupported();
      req_it->second.retry_counter_for_create_config = 0;
      req_it->second.remote_supported_sw_time_ = event_view.GetTSwTimeSupported();
      req_it->second.remote_max_antenna_paths_supported_ = event_view.GetMaxAntennaPathsSupported();

      if (event_view.GetOptionalSubfeaturesSupported().no_frequency_actuation_error_ == 0) {
        log::debug("read remote fae as the no_fae is false.");
        send_le_cs_read_remote_fae_table(connection_handle);
      }

      send_le_cs_create_config(connection_handle, req_it->second.requesting_config_id);
    }
    log::info(
            "connection_handle:{}, num_antennas_supported:{}, max_antenna_paths_supported:{}, "
            "roles_supported:{}, phase_based_ranging_supported: {}",
            event_view.GetConnectionHandle(), event_view.GetNumAntennasSupported(),
            event_view.GetMaxAntennaPathsSupported(), event_view.GetRolesSupported().ToString(),
            event_view.GetOptionalSubfeaturesSupported().phase_based_ranging_);
  }

  void on_cs_set_default_settings_complete(CsSyncAntennaSelection selection,
                                           CommandCompleteView view) {
    auto complete_view = LeCsSetDefaultSettingsCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Get invalid LeCsSetDefaultSettingsComplete");
      return;
    }
    if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn("Received LeCsSetDefaultSettingsComplete with error code {}", error_code);
      uint16_t connection_handle = complete_view.GetConnectionHandle();

      if (selection == CsSyncAntennaSelection::ANTENNAS_IN_ORDER) {
        log::info("Retry with NO_RECOMMENDATION");
        send_le_cs_set_default_settings(connection_handle,
                                        CsSyncAntennaSelection::NO_RECOMMENDATION);
        return;
      }
      handle_cs_setup_failure(
              connection_handle, REASON_INTERNAL_ERROR,
              ChannelSoundingStopReason::REASON_SET_DEFAULT_SETTINGS_COMPLETE_FAILED);
      return;
    }
  }

  static void on_cs_read_remote_fae_table_complete(LeCsReadRemoteFaeTableCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsReadRemoteFaeTableCompleteView");
      return;
    }
    if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      log::warn("Received LeCsReadRemoteFaeTableCompleteView with error code {}",
                ErrorCodeText(event_view.GetStatus()));
      // not critical, do nothing here.
    }
  }

  void on_cs_security_enable_complete(LeCsSecurityEnableCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsSecurityEnableCompleteView");
      return;
    }
    uint16_t connection_handle = event_view.GetConnectionHandle();
    auto req_it = cs_requester_trackers_.find(connection_handle);
    bool is_expected_by_requester = false;
    if (req_it != cs_requester_trackers_.end() &&
        req_it->second.state == CsTrackerState::WAIT_FOR_SECURITY_ENABLED) {
      is_expected_by_requester = true;
    }
    // cancel the timer on either success or fail
    if (is_expected_by_requester && req_it->second.enable_security_timeout_alarm != nullptr) {
      log::debug("cancel alarm for security enable cmd.");
      req_it->second.enable_security_timeout_alarm->Cancel();
      req_it->second.enable_security_timeout_alarm = nullptr;
    }
    if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn("Received LeCsSecurityEnableCompleteView with error code {}", error_code);
      if (is_expected_by_requester) {
        handle_cs_setup_failure(connection_handle, REASON_INTERNAL_ERROR,
                                ChannelSoundingStopReason::REASON_SECURITY_ENABLE_COMPLETE_FAILED);
      }
      return;
    }

    if (is_expected_by_requester) {
      send_le_cs_set_procedure_parameters(event_view.GetConnectionHandle(),
                                          req_it->second.used_config_id,
                                          req_it->second.remote_num_antennas_supported_,
                                          req_it->second.remote_max_antenna_paths_supported_);
    }
    auto res_it = cs_responder_trackers_.find(connection_handle);
    if (res_it != cs_responder_trackers_.end() &&
        res_it->second.state == CsTrackerState::WAIT_FOR_SECURITY_ENABLED) {
      res_it->second.state = CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED;
      if (is_expected_by_requester) {
        log::warn("both requester and responder were expecting the security_enable_complete!");
      }
    }
  }

  void on_cs_config_complete(LeCsConfigCompleteView event_view) {
    if (!event_view.IsValid()) {
      log::warn("Get invalid LeCsConfigCompleteView");
      return;
    }
    uint16_t connection_handle = event_view.GetConnectionHandle();
    if (event_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(event_view.GetStatus());
      log::warn("Received LeCsConfigCompleteView with error code {}", error_code);
      // The Create Config LL packet may arrive before the remote side has finished setting default
      // settings, which will result in create config failure. Retry to ensure the remote side has
      // completed its setup.
      if (cs_requester_trackers_.find(connection_handle) != cs_requester_trackers_.end() &&
          cs_requester_trackers_[connection_handle].state ==
                  CsTrackerState::WAIT_FOR_CONFIG_COMPLETE) {
        if (cs_requester_trackers_[connection_handle].retry_counter_for_create_config <
            kMaxRetryCounterForCreateConfig) {
          log::info("Failed Create_Config_Complete with config id - {}", event_view.GetConfigId());
          // Get a reference to the tracker
          auto& tracker = cs_requester_trackers_[connection_handle];
          tracker.retry_counter_for_create_config++;
          log::info("Scheduling retry for send_le_cs_create_config after {} ms, retry counter {}",
                    kCommandRetryIntervalMs, tracker.retry_counter_for_create_config);

          // Cancel any pending task and schedule the retry with a delay
          tracker.procedure_schedule_guard_alarm->Cancel();
          tracker.procedure_schedule_guard_alarm->Schedule(
                  common::Bind(&impl::send_le_cs_create_config, common::Unretained(this),
                               connection_handle, tracker.requesting_config_id),
                  std::chrono::milliseconds(kCommandRetryIntervalMs));
        } else {
          handle_cs_setup_failure(connection_handle, REASON_INTERNAL_ERROR,
                                  ChannelSoundingStopReason::REASON_CREATE_CONFIG_COMPLETE_FAILED);
        }
      }
      return;
    }

    uint8_t config_id = event_view.GetConfigId();
    if (event_view.GetAction() == CsAction::CONFIG_REMOVED) {
      on_cs_config_removed(connection_handle, config_id);
      return;
    }
    check_and_handle_conflict(connection_handle, config_id,
                              CsTrackerState::WAIT_FOR_CONFIG_COMPLETE);
    auto valid_requester_states = static_cast<uint8_t>(CsTrackerState::WAIT_FOR_CONFIG_COMPLETE);
    // any state, as the remote can start over at any time.
    auto valid_responder_states = static_cast<uint8_t>(CsTrackerState::UNSPECIFIED);

    CsTracker* live_tracker = get_live_tracker(connection_handle, config_id, valid_requester_states,
                                               valid_responder_states);
    if (live_tracker == nullptr) {
      log::warn("Can't find cs tracker for connection_handle {}", connection_handle);
      return;
    }
    if (!live_tracker->local_start) {
      // reset the responder state, as no other event to set the state.
      live_tracker->state = CsTrackerState::WAIT_FOR_CONFIG_COMPLETE;
    }

    live_tracker->used_config_id = config_id;
    log::info("Get {}", event_view.ToString());
    live_tracker->role = event_view.GetRole();
    live_tracker->main_mode_type = event_view.GetMainModeType();
    live_tracker->sub_mode_type = event_view.GetSubModeType();
    live_tracker->rtt_type = event_view.GetRttType();
    if (live_tracker->local_start && is_hal_v2()) {
      ranging_hal_->UpdateChannelSoundingConfig(
              connection_handle, event_view, local_supported_sw_time_,
              live_tracker->remote_supported_sw_time_, live_tracker->conn_interval_);
    }
    if (live_tracker->local_hci_role == hci::Role::CENTRAL) {
      // send the cmd from the BLE central only.
      send_le_cs_security_enable(connection_handle, live_tracker->local_start);
    } else {
      live_tracker->state = CsTrackerState::WAIT_FOR_SECURITY_ENABLED;
      if (live_tracker->local_start) {
        if (live_tracker->enable_security_timeout_alarm == nullptr) {
          live_tracker->enable_security_timeout_alarm =
                  std::make_unique<os::Alarm>(&handler_->thread());
        }
        live_tracker->enable_security_timeout_alarm->Schedule(
                common::Bind(&impl::le_cs_enable_security_timeout, common::Unretained(this),
                             connection_handle),
                std::chrono::milliseconds(kEnableSecurityTimeoutMs));
      }
    }
  }

  void le_cs_enable_security_timeout(uint16_t connection_handle) {
    auto req_it = cs_requester_trackers_.find(connection_handle);
    if (req_it != cs_requester_trackers_.end()) {
      log::info("security enable cmd is timeout, stop current session.");
      handle_cs_setup_failure(connection_handle,
                              DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                              ChannelSoundingStopReason::REASON_SECURITY_ENABLE_TIMEOUT);
    }
  }

  void on_cs_config_removed(uint16_t connection_handle, uint8_t config_id) {
    // suppose it only has 1 requester and 1 responder per ACL.
    auto req_it = cs_requester_trackers_.find(connection_handle);
    if (req_it != cs_requester_trackers_.end() && req_it->second.used_config_id == config_id) {
      req_it->second.used_config_id = kInvalidConfigId;
      return;
    }
    auto res_it = cs_responder_trackers_.find(connection_handle);
    if (res_it != cs_responder_trackers_.end() && res_it->second.used_config_id == config_id) {
      res_it->second.used_config_id = kInvalidConfigId;
      return;
    }
    log::warn("The removed config was not used, something was wrong.");
  }

  void on_cs_set_procedure_parameters(CommandCompleteView view) {
    auto complete_view = LeCsSetProcedureParametersCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Get Invalid LeCsSetProcedureParametersCompleteView");
      return;
    }
    uint16_t connection_handle = complete_view.GetConnectionHandle();
    if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn("Received LeCsSetProcedureParametersCompleteView with error code {}", error_code);
      if (cs_requester_trackers_[connection_handle].retry_counter_for_set_procedure_parameter <
          kMaxRetryCounterForSetProcedureParameter) {
        // Get a reference to the tracker
        auto& tracker = cs_requester_trackers_[connection_handle];
        tracker.retry_counter_for_set_procedure_parameter++;
        log::info(
                "Scheduling retry for send_le_cs_set_procedure_parameters after {} ms, "
                "retry counter {}",
                kCommandRetryIntervalMs, tracker.retry_counter_for_set_procedure_parameter);
        // Cancel any pending task and schedule the retry with a delay
        tracker.procedure_schedule_guard_alarm->Cancel();
        tracker.procedure_schedule_guard_alarm->Schedule(
                common::Bind(&impl::send_le_cs_set_procedure_parameters, common::Unretained(this),
                             connection_handle, tracker.used_config_id,
                             tracker.remote_num_antennas_supported_,
                             tracker.remote_max_antenna_paths_supported_),
                std::chrono::milliseconds(kCommandRetryIntervalMs));
      } else {
        handle_cs_setup_failure(
                connection_handle, REASON_INTERNAL_ERROR,
                ChannelSoundingStopReason::REASON_SET_PROCEDURE_PARAMETERS_COMPLETE_FAILED);
      }
      return;
    }
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      log::warn("Can't find cs tracker for connection_handle {}", connection_handle);
      return;
    }

    if (it->second.measurement_ongoing) {
      log::info("cs set up succeed");
      it->second.retry_counter_for_set_procedure_parameter = 0;
      it->second.setup_complete = true;
      send_le_cs_procedure_enable(connection_handle, Enable::ENABLED);
    }
  }

  CsTracker* get_live_tracker(uint16_t connection_handle, uint8_t config_id,
                              uint8_t valid_requester_states, uint8_t valid_responder_states) {
    // CAVEAT: if the remote is sending request with the same config id, the behavior is undefined.
    auto req_it = cs_requester_trackers_.find(connection_handle);
    if (req_it != cs_requester_trackers_.end() && req_it->second.state != CsTrackerState::STOPPED &&
        (valid_requester_states & static_cast<uint8_t>(req_it->second.state)) != 0) {
      uint8_t req_config_id = req_it->second.used_config_id;
      if (req_it->second.state == CsTrackerState::WAIT_FOR_CONFIG_COMPLETE) {
        req_config_id = req_it->second.requesting_config_id;
      }
      if (req_config_id == config_id) {
        return &(req_it->second);
      }
    }

    auto res_it = cs_responder_trackers_.find(connection_handle);
    if (res_it != cs_responder_trackers_.end() &&
        (res_it->second.used_config_id == kInvalidConfigId ||
         res_it->second.used_config_id == config_id) &&
        (valid_responder_states == static_cast<uint8_t>(CsTrackerState::UNSPECIFIED) ||
         (valid_responder_states & static_cast<uint8_t>(res_it->second.state)) != 0)) {
      if (req_it != cs_requester_trackers_.end() &&
          req_it->second.state != CsTrackerState::STOPPED) {
        req_it->second.requester_metrics_->back_to_back = true;
      }
      return &(res_it->second);
    }
    log::error("no valid tracker to handle the event.");
    return nullptr;
  }

  void check_and_handle_conflict(uint16_t connection_handle, uint8_t event_config_id,
                                 CsTrackerState expected_requester_state) {
    // If the local and remote were triggering the event at the same time, and the controller
    // allows that happen, the things may still get messed; If the spec can differentiate the
    // local event or remote event, that would be clearer.
    auto it = cs_requester_trackers_.find(connection_handle);
    if (it == cs_requester_trackers_.end()) {
      return;
    }
    if (event_config_id != it->second.used_config_id) {
      return;
    }
    if (it->second.state == expected_requester_state) {
      return;
    }
    log::warn("unexpected request from remote, which is conflict with the local measurement.");
    if (it->second.state != CsTrackerState::STOPPED) {
      stop_distance_measurement(it->second.address, connection_handle,
                                DistanceMeasurementMethod::METHOD_CS);
      // TODO: clean up the stopped callback, it should be called within stop_distance_measurement.
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(
              it->second.address, REASON_REMOTE_REQUEST, METHOD_CS);
      it->second.requester_metrics_->back_to_back = true;
      report_session_metrics_on_stop(*it->second.requester_metrics_,
                                     ChannelSoundingStopReason::REASON_B2B_CONFLICT);
    }
    it->second.used_config_id = kInvalidConfigId;
  }

  void on_cs_procedure_enable_complete(LeCsProcedureEnableCompleteView event_view) {
    log::assert_that(event_view.IsValid(), "assert failed: event_view.IsValid()");
    uint16_t connection_handle = event_view.GetConnectionHandle();
    log::debug("Procedure enabled, {}", event_view.ToString());

    uint8_t config_id = event_view.GetConfigId();

    CsTracker* live_tracker = nullptr;
    if (event_view.GetState() == Enable::ENABLED) {
      auto resq_it = cs_responder_trackers_.find(connection_handle);
      auto req_it = cs_requester_trackers_.find(connection_handle);
      // corner case, the procedure state is still enabled after stop
      // the procedure_enable not finished before stopped.
      if (req_it != cs_requester_trackers_.end() &&
          req_it->second.state == CsTrackerState::STOPPED &&
          req_it->second.used_config_id == config_id &&
          !req_it->second.sent_procedure_disable_after_stopping) {
        if (resq_it != cs_responder_trackers_.end() &&
            resq_it->second.used_config_id == config_id &&
            resq_it->second.state >= CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED) {
          // maybe dead code, may never hit this branch as the config_id of requester should
          // be reset if there is back2back with create_config_complete.
          log::warn("conflict, the procedure_enable is most likely for the responder.");
        } else {
          // send disable as the controller is still enabled after stopping.
          req_it->second.sent_procedure_disable_after_stopping = true;
          send_le_cs_procedure_enable(connection_handle, Enable::DISABLED);
          return;
        }
      }
      if (resq_it != cs_responder_trackers_.end() && resq_it->second.used_config_id == config_id &&
          resq_it->second.state >= CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED) {
        if (req_it != cs_requester_trackers_.end() &&
            req_it->second.state != CsTrackerState::STOPPED) {
          req_it->second.requester_metrics_->back_to_back = true;
        }
        check_and_handle_conflict(connection_handle, config_id,
                                  CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED);
      }
      uint8_t valid_requester_states =
              static_cast<uint8_t>(CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED);
      uint8_t valid_responder_states =
              static_cast<uint8_t>(CsTrackerState::STOPPED) |
              static_cast<uint8_t>(CsTrackerState::INIT) |
              static_cast<uint8_t>(CsTrackerState::STARTED) |
              static_cast<uint8_t>(CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED);
      live_tracker = get_live_tracker(connection_handle, config_id, valid_requester_states,
                                      valid_responder_states);
      if (live_tracker == nullptr) {
        log::error("enable - no tracker is available for {}", connection_handle);
        return;
      }
      if (live_tracker->used_config_id != config_id) {
        log::warn("config_id {} doesn't match the assigned one {}.", config_id,
                  live_tracker->used_config_id);
        return;
      }

      // maybe dead code, leave it here for safe. controller may never send 'ENABLED' with error.
      if (live_tracker->local_start && event_view.GetStatus() != ErrorCode::SUCCESS) {
        log::warn("Received ENABLED procedure with error code {}. Retry counter {}",
                  ErrorCodeText(event_view.GetStatus()), live_tracker->retry_counter_for_cs_enable);
        if (live_tracker->retry_counter_for_cs_enable++ >= kMaxRetryCounterForCsEnable) {
          handle_cs_setup_failure(connection_handle, REASON_INTERNAL_ERROR);
        } else {
          live_tracker->procedure_schedule_guard_alarm->Cancel();
          log::info("schedule next procedure enable after {} ms", live_tracker->interval_ms);
          live_tracker->procedure_schedule_guard_alarm->Schedule(
                  common::Bind(&impl::send_le_cs_procedure_enable, common::Unretained(this),
                               connection_handle, Enable::ENABLED),
                  std::chrono::milliseconds(live_tracker->interval_ms));
        }
        return;
      }
      live_tracker->state = CsTrackerState::STARTED;
      live_tracker->selected_tx_power = event_view.GetSelectedTxPower();
      live_tracker->n_procedure_count = event_view.GetProcedureCount();
      live_tracker->retry_counter_for_cs_enable = 0;
      live_tracker->procedure_counting_after_enable = 0;
      if (live_tracker->local_start) {
        uint32_t schedule_interval = live_tracker->interval_ms;
        if (live_tracker->n_procedure_count > 1) {
          schedule_interval = live_tracker->n_procedure_count * event_view.GetProcedureInterval() *
                                      live_tracker->conn_interval_ * kConnIntervalUnitMs +
                              kProcedureScheduleGuardMs;
          log::debug("guard interval is {} ms", schedule_interval);
        }
        if (live_tracker->n_procedure_count >= 1) {
          live_tracker->procedure_schedule_guard_alarm->Cancel();
          log::info("schedule next procedure enable after {} ms", schedule_interval);
          live_tracker->procedure_schedule_guard_alarm->Schedule(
                  common::Bind(&impl::send_le_cs_procedure_enable, common::Unretained(this),
                               connection_handle, Enable::ENABLED),
                  std::chrono::milliseconds(schedule_interval));
        }
        uint16_t subevent_len = event_view.GetSubeventLen();
        if (live_tracker->requester_metrics_->min_subevent_len > subevent_len) {
          live_tracker->requester_metrics_->min_subevent_len = subevent_len;
          live_tracker->requester_metrics_->min_subevent_len_count = 1;
        } else if (live_tracker->requester_metrics_->min_subevent_len == subevent_len) {
          live_tracker->requester_metrics_->min_subevent_len_count += 1;
        }

        if (live_tracker->waiting_for_start_callback) {
          live_tracker->waiting_for_start_callback = false;
          distance_measurement_callbacks_->OnDistanceMeasurementStarted(live_tracker->address,
                                                                        METHOD_CS);
          live_tracker->requester_metrics_->setup_end_timestamp_nanos =
                  get_elapsed_realtime_nanos();
        }
        if (is_hal_v2()) {
          // reset the procedure sequence
          live_tracker->procedure_sequence_after_enable = -1;
          ranging_hal_->UpdateProcedureEnableConfig(connection_handle, event_view);
        }
      }
    } else if (event_view.GetState() == Enable::DISABLED) {
      if (event_view.GetStatus() == ErrorCode::SUCCESS) {
        // local or remote host requested it.
        uint8_t valid_requester_states = static_cast<uint8_t>(CsTrackerState::STARTED);
        valid_requester_states |= static_cast<uint8_t>(CsTrackerState::STOPPED);
        uint8_t valid_responder_states = static_cast<uint8_t>(CsTrackerState::STARTED);
        live_tracker = get_live_tracker(connection_handle, config_id, valid_requester_states,
                                        valid_responder_states);
        if (live_tracker == nullptr) {
          log::error("disable - no tracker is available for {}", connection_handle);
          return;
        }
        reset_tracker_on_stopped(*live_tracker);
      } else {
        auto req_it = cs_requester_trackers_.find(connection_handle);
        if (req_it != cs_requester_trackers_.end() &&
            req_it->second.state == CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED &&
            config_id == req_it->second.used_config_id) {
          log::warn("Received DISABLED procedure with error code {}. Retry counter {}",
                    ErrorCodeText(event_view.GetStatus()),
                    req_it->second.retry_counter_for_cs_enable);
          if (req_it->second.retry_counter_for_cs_enable++ >= kMaxRetryCounterForCsEnable) {
            handle_cs_setup_failure(
                    connection_handle, REASON_INTERNAL_ERROR,
                    ChannelSoundingStopReason::REASON_PROCEDURE_ENABLE_COMPLETE_FAILED);
          } else {
            req_it->second.procedure_schedule_guard_alarm->Cancel();
            log::info("schedule next procedure enable after {} ms", req_it->second.interval_ms);
            req_it->second.procedure_schedule_guard_alarm->Schedule(
                    common::Bind(&impl::send_le_cs_procedure_enable, common::Unretained(this),
                                 connection_handle, Enable::ENABLED),
                    std::chrono::milliseconds(req_it->second.interval_ms));
          }
        }
      }
    }
  }

  bool is_hal_v2() const { return ranging_hal_->GetRangingHalVersion() == hal::V_2; }

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
    CsTracker* live_tracker = nullptr;
    CsProcedureData* procedure_data = nullptr;
    uint8_t valid_requester_states = static_cast<uint8_t>(CsTrackerState::STARTED);
    // TODO(b/384928509): Prevent sending CS enable if procedures are not yet complete.
    valid_requester_states |= static_cast<uint8_t>(CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED);
    uint8_t valid_responder_states = static_cast<uint8_t>(CsTrackerState::STARTED);
    if (event.GetSubeventCode() == SubeventCode::LE_CS_SUBEVENT_RESULT) {
      auto cs_event_result = LeCsSubeventResultView::Create(event);
      if (!cs_event_result.IsValid()) {
        log::warn("Get invalid LeCsSubeventResultView");
        return;
      }
      connection_handle = cs_event_result.GetConnectionHandle();
      live_tracker = get_live_tracker(connection_handle, cs_event_result.GetConfigId(),
                                      valid_requester_states, valid_responder_states);
      if (live_tracker == nullptr) {
        log::error("no live tracker is available for {}", connection_handle);
        return;
      }
      procedure_done_status = cs_event_result.GetProcedureDoneStatus();
      subevent_done_status = cs_event_result.GetSubeventDoneStatus();
      procedure_abort_reason = cs_event_result.GetProcedureAbortReason();
      subevent_abort_reason = cs_event_result.GetSubeventAbortReason();
      result_data_structures = cs_event_result.GetResultDataStructures();

      procedure_data = init_cs_procedure_data(live_tracker, cs_event_result.GetProcedureCounter(),
                                              cs_event_result.GetNumAntennaPaths());
      if (live_tracker->role == CsRole::INITIATOR) {
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
      if (live_tracker->local_start && is_hal_v2()) {
        // cache all subevent result
        auto subevent_result = std::make_shared<hal::SubeventResult>();
        subevent_result->start_acl_conn_event_counter_ = cs_event_result.GetStartAclConnEvent();
        subevent_result->frequency_compensation_ = cs_event_result.GetFrequencyCompensation();
        subevent_result->reference_power_level_ = cs_event_result.GetReferencePowerLevel();
        subevent_result->num_antenna_paths_ = cs_event_result.GetNumAntennaPaths();
        subevent_result->timestamp_nanos_ =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::system_clock::now().time_since_epoch())
                        .count();
        procedure_data->procedure_data_v2_.local_subevent_data_.emplace_back(subevent_result);
      }
    } else {
      auto cs_event_result = LeCsSubeventResultContinueView::Create(event);
      if (!cs_event_result.IsValid()) {
        log::warn("Get invalid LeCsSubeventResultContinueView");
        return;
      }
      connection_handle = cs_event_result.GetConnectionHandle();
      live_tracker = get_live_tracker(connection_handle, cs_event_result.GetConfigId(),
                                      valid_requester_states, valid_responder_states);
      procedure_done_status = cs_event_result.GetProcedureDoneStatus();
      subevent_done_status = cs_event_result.GetSubeventDoneStatus();
      procedure_abort_reason = cs_event_result.GetProcedureAbortReason();
      subevent_abort_reason = cs_event_result.GetSubeventAbortReason();
      result_data_structures = cs_event_result.GetResultDataStructures();
      if (live_tracker == nullptr) {
        log::warn("Can't find any tracker for {}", connection_handle);
        return;
      }
      procedure_data = get_procedure_data(live_tracker, live_tracker->procedure_counter);
      if (procedure_data == nullptr) {
        log::warn("no procedure data for counter {} of connection {}",
                  live_tracker->procedure_counter, connection_handle);
        return;
      }
    }
    // Update procedure status
    procedure_data->local_status = procedure_done_status;
    procedure_data->procedure_data_v2_.local_procedure_abort_reason_ = procedure_abort_reason;
    post_handle_local_subevent_result(connection_handle, live_tracker, procedure_data,
                                      result_data_structures, subevent_done_status,
                                      subevent_abort_reason);
    if (live_tracker->local_start && is_hal_v2()) {
      if (procedure_data->procedure_data_v2_.local_subevent_data_.empty()) {
        log::error("no subevent result is available for subevent continue event");
      } else {
        auto last_subevent_result = procedure_data->procedure_data_v2_.local_subevent_data_.back();
        last_subevent_result->subevent_abort_reason_ = subevent_abort_reason;
      }
    }
  }

  void post_handle_local_subevent_result(
          uint16_t connection_handle, CsTracker* live_tracker, CsProcedureData* procedure_data,
          const std::vector<LeCsResultDataStructure>& result_data_structures,
          const CsSubeventDoneStatus& subevent_done_status,
          const SubeventAbortReason& subevent_abort_reason) {
    uint16_t counter = live_tracker->procedure_counter;
    CsProcedureDoneStatus procedure_done_status = procedure_data->local_status;

    if (live_tracker->local_start) {
      bool should_schedule_procedure_enable = false;
      if (live_tracker->n_procedure_count > 1 &&
          procedure_done_status == CsProcedureDoneStatus::ALL_RESULTS_COMPLETE &&
          ++live_tracker->procedure_counting_after_enable == live_tracker->n_procedure_count) {
        should_schedule_procedure_enable = true;
        log::debug("enable procedure after finishing the last procedure");
      }

      if (should_schedule_procedure_enable ||
          procedure_done_status == CsProcedureDoneStatus::ABORTED) {
        live_tracker->procedure_schedule_guard_alarm->Cancel();
        send_le_cs_procedure_enable(connection_handle, Enable::ENABLED);
      }
    }
    ProcedureAbortReason procedure_abort_reason =
            procedure_data->procedure_data_v2_.local_procedure_abort_reason_;
    log::debug(
            "Connection_handle {}, procedure_done_status: {}, subevent_done_status: {}, counter: "
            "{}",
            connection_handle, CsProcedureDoneStatusText(procedure_done_status),
            CsSubeventDoneStatusText(subevent_done_status), counter);

    if (procedure_done_status == CsProcedureDoneStatus::ABORTED ||
        subevent_done_status == CsSubeventDoneStatus::ABORTED) {
      log::warn(
              "Received CS Subevent with procedure_abort_reason:{}, subevent_abort_reason:{}, "
              "connection_handle:{}, counter:{}",
              ProcedureAbortReasonText(procedure_abort_reason),
              SubeventAbortReasonText(subevent_abort_reason), connection_handle, counter);
    }
    procedure_data->ras_subevent_header_.num_steps_reported_ += result_data_structures.size();
    if (subevent_done_status == CsSubeventDoneStatus::ALL_RESULTS_COMPLETE) {
      procedure_data->contains_complete_subevent_ = true;
    }

    procedure_data->ras_subevent_header_.ranging_abort_reason_ =
            static_cast<RangingAbortReason>(procedure_abort_reason);
    procedure_data->ras_subevent_header_.subevent_abort_reason_ =
            static_cast<bluetooth::ras::SubeventAbortReason>(subevent_abort_reason);

    parse_cs_result_data(result_data_structures, *procedure_data, live_tracker->role);

    if (live_tracker->local_start) {
      check_cs_procedure_complete(live_tracker, procedure_data, connection_handle);
      // Skip to send remote
      return;
    }

    // Send data to RAS server
    if (subevent_done_status != CsSubeventDoneStatus::PARTIAL_RESULTS) {
      if (!procedure_data->contains_invalid_data_) {
        procedure_data->ras_subevent_header_.ranging_done_status_ =
                static_cast<RangingDoneStatus>(procedure_done_status);
        procedure_data->ras_subevent_header_.subevent_done_status_ =
                static_cast<SubeventDoneStatus>(subevent_done_status);
        auto builder = RasSubeventBuilder::Create(procedure_data->ras_subevent_header_,
                                                  procedure_data->ras_subevent_data_);
        auto subevent_raw = builder_to_bytes(std::move(builder));
        append_vector(procedure_data->ras_raw_data_, subevent_raw);
        // erase buffer
        procedure_data->ras_subevent_data_.clear();
        send_on_demand_data(live_tracker->address, procedure_data,
                            get_ras_raw_payload_size(connection_handle));
      }
      // remove procedure data sent previously
      if (procedure_done_status != CsProcedureDoneStatus::PARTIAL_RESULTS) {
        delete_consumed_procedure_data(live_tracker, live_tracker->procedure_counter);
      }
    }
  }

  void send_on_demand_data(Address address, CsProcedureData* procedure_data,
                           uint16_t raw_payload_size) {
    // Check is last segment or not.
    uint16_t unsent_data_size =
            procedure_data->ras_raw_data_.size() - procedure_data->ras_raw_data_index_;
    if (procedure_data->local_status != CsProcedureDoneStatus::PARTIAL_RESULTS &&
        unsent_data_size <= raw_payload_size) {
      procedure_data->segmentation_header_.last_segment_ = 1;
    } else if (unsent_data_size < raw_payload_size) {
      log::verbose("waiting for more data, current unsent data size {}", unsent_data_size);
      return;
    }

    // Create raw data for segment_data;
    uint16_t copy_size = unsent_data_size < raw_payload_size ? unsent_data_size : raw_payload_size;
    auto copy_start = procedure_data->ras_raw_data_.begin() + procedure_data->ras_raw_data_index_;
    auto copy_end = copy_start + copy_size;
    std::vector<uint8_t> subevent_data(copy_start, copy_end);
    procedure_data->ras_raw_data_index_ += copy_size;

    auto builder =
            RangingDataSegmentBuilder::Create(procedure_data->segmentation_header_, subevent_data);
    auto segment_data = builder_to_bytes(std::move(builder));

    log::debug("counter: {}, size:{}", procedure_data->counter, (uint16_t)segment_data.size());
    distance_measurement_callbacks_->OnRasFragmentReady(
            address, procedure_data->counter, procedure_data->segmentation_header_.last_segment_,
            segment_data);

    procedure_data->segmentation_header_.first_segment_ = 0;
    procedure_data->segmentation_header_.rolling_segment_counter_++;
    procedure_data->segmentation_header_.rolling_segment_counter_ %= 64;
    if (procedure_data->segmentation_header_.last_segment_) {
      // last segment sent, clear buffer
      procedure_data->ras_raw_data_.clear();
    } else if (unsent_data_size > raw_payload_size) {
      send_on_demand_data(address, procedure_data, raw_payload_size);
    }
  }

  void handle_remote_data(const Address address, uint16_t connection_handle,
                          const std::vector<uint8_t> raw_data) {
    log::debug("address:{}, connection_handle 0x{:04x}, size:{}", address.ToString(),
               connection_handle, raw_data.size());

    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::warn("can't find tracker for 0x{:04x}", connection_handle);
      return;
    }
    if (cs_requester_trackers_[connection_handle].state != CsTrackerState::STARTED &&
        cs_requester_trackers_[connection_handle].state !=
                CsTrackerState::WAIT_FOR_PROCEDURE_ENABLED) {
      log::warn("The measurement for {} is stopped, ignore the remote data.", connection_handle);
      return;
    }
    auto& tracker = cs_requester_trackers_[connection_handle];

    SegmentationHeader segmentation_header;
    PacketView<kLittleEndian> packet_bytes_view(std::make_shared<std::vector<uint8_t>>(raw_data));
    auto after = SegmentationHeader::Parse(&segmentation_header, packet_bytes_view.begin());
    if (after == packet_bytes_view.begin()) {
      log::warn("Invalid segment data");
      return;
    }

    log::debug("Receive segment for segment counter {}, size {}",
               segmentation_header.rolling_segment_counter_, raw_data.size());

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

  void handle_remote_data_timeout(const Address address, uint16_t connection_handle) {
    log::warn("address:{}, connection_handle 0x{:04x}", address.ToString(), connection_handle);

    if (cs_requester_trackers_.find(connection_handle) == cs_requester_trackers_.end()) {
      log::error("Can't find CS tracker for connection_handle {}", connection_handle);
      return;
    }
    auto& tracker = cs_requester_trackers_[connection_handle];
    if (tracker.measurement_ongoing && tracker.local_start) {
      cs_requester_trackers_[connection_handle].procedure_schedule_guard_alarm->Cancel();
      send_le_cs_procedure_enable(connection_handle, Enable::DISABLED);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(
              tracker.address, REASON_INTERNAL_ERROR, METHOD_CS);
    }
    reset_tracker_on_stopped(tracker);
    report_session_metrics_on_stop(*tracker.requester_metrics_,
                                   ChannelSoundingStopReason::REASON_REMOTE_TIMEOUT);
  }

  bool is_valid_ras_subevent_header(RasSubeventHeader subevent_header) {
    switch (subevent_header.ranging_done_status_) {
      case RangingDoneStatus::ALL_RESULTS_COMPLETE:
      case RangingDoneStatus::PARTIAL_RESULTS:
      case RangingDoneStatus::ABORTED:
        break;
      default:
        log::error("invalid ras RangingDoneStatus: {}", subevent_header.ranging_done_status_);
        return false;
    }

    switch (subevent_header.subevent_done_status_) {
      case SubeventDoneStatus::ALL_RESULTS_COMPLETE:
      case SubeventDoneStatus::ABORTED:
        break;
      default:
        log::error("invalid ras SubeventDoneStatus: {}", subevent_header.subevent_done_status_);
        return false;
    }

    switch (subevent_header.ranging_abort_reason_) {
      case RangingAbortReason::NO_ABORT:
      case RangingAbortReason::LOCAL_HOST_OR_REMOTE:
      case RangingAbortReason::INSUFFICIENT_FILTERED_CHANNELS:
      case RangingAbortReason::INSTANT_HAS_PASSED:
      case RangingAbortReason::UNSPECIFIED:
        break;
      default:
        log::error("invalid ras RangingAbortReason: {}", subevent_header.ranging_abort_reason_);
        return false;
    }

    switch (subevent_header.subevent_abort_reason_) {
      case ras::SubeventAbortReason::NO_ABORT:
      case ras::SubeventAbortReason::LOCAL_HOST_OR_REMOTE:
      case ras::SubeventAbortReason::NO_CS_SYNC_RECEIVED:
      case ras::SubeventAbortReason::SCHEDULING_CONFLICTS_OR_LIMITED_RESOURCES:
      case ras::SubeventAbortReason::UNSPECIFIED:
        break;
      default:
        log::error("invalid ras SubeventAbortReason: {}", subevent_header.subevent_abort_reason_);
        return false;
    }
    return true;
  }

  void parse_ras_segments(RangingHeader ranging_header, PacketViewForRecombination& segment_data,
                          uint16_t connection_handle) {
    log::debug("Data size {}, Ranging_header {}", segment_data.size(), ranging_header.ToString());
    auto procedure_data =
            get_procedure_data_for_ras(connection_handle, ranging_header.ranging_counter_);
    if (procedure_data == nullptr) {
      return;
    }

    uint8_t num_antenna_paths = procedure_data->num_antenna_paths;

    // Get role of the remote device
    CsRole remote_role = cs_requester_trackers_[connection_handle].role == CsRole::INITIATOR
                                 ? CsRole::REFLECTOR
                                 : CsRole::INITIATOR;

    auto parse_index = segment_data.begin();
    uint16_t remaining_data_size = std::distance(parse_index, segment_data.end());
    int subevent_sequence = -1;
    procedure_data->procedure_data_v2_.remote_selected_tx_power_ =
            static_cast<int8_t>(ranging_header.selected_tx_power_);
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
      if (!is_valid_ras_subevent_header(subevent_header)) {
        log::error("fatal, invalid RAS segment data from the remote device.");
        handle_cs_setup_failure(connection_handle,
                                DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                ChannelSoundingStopReason::REASON_REMOTE_PROCEDURE_DATA_BROKEN);
        return;
      }
      subevent_sequence++;
      auto remote_subevent_result = std::make_shared<hal::SubeventResult>();
      std::shared_ptr<hal::SubeventResult> local_subevent_result = nullptr;
      if (is_hal_v2()) {
        if (subevent_sequence <
            static_cast<int>(procedure_data->procedure_data_v2_.local_subevent_data_.size())) {
          local_subevent_result =
                  procedure_data->procedure_data_v2_.local_subevent_data_[subevent_sequence];
        } else {
          log::error("there is no local subevent result.");
          return;
        }
        remote_subevent_result->start_acl_conn_event_counter_ =
                subevent_header.start_acl_conn_event_;
        remote_subevent_result->reference_power_level_ =
                static_cast<int8_t>(subevent_header.reference_power_level_);
        remote_subevent_result->num_antenna_paths_ = num_antenna_paths;
        remote_subevent_result->subevent_abort_reason_ =
                static_cast<SubeventAbortReason>(subevent_header.subevent_abort_reason_);
        remote_subevent_result->frequency_compensation_ = subevent_header.frequency_compensation_;
        // get data from local
        remote_subevent_result->timestamp_nanos_ = local_subevent_result->timestamp_nanos_;
        procedure_data->procedure_data_v2_.remote_subevent_data_.emplace_back(
                remote_subevent_result);
        procedure_data->procedure_data_v2_.remote_procedure_abort_reason_ =
                static_cast<ProcedureAbortReason>(subevent_header.ranging_abort_reason_);
      }

      // Parse step data
      for (uint8_t i = 0; i < subevent_header.num_steps_reported_; i++) {
        uint8_t step_channel = 0;
        if (is_hal_v2() && local_subevent_result) {
          if (i < local_subevent_result->step_data_.size()) {
            step_channel = local_subevent_result->step_data_[i].step_channel_;
          } else {
            log::warn("The local subevent has less steps then the remote one.");
          }
        }
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
        uint8_t mode = step_mode.mode_type_;
        switch (mode) {
          case 0: {
            if (remote_role == CsRole::INITIATOR) {
              LeCsMode0InitiatorData tone_data;
              after = LeCsMode0InitiatorData::Parse(&tone_data, parse_index);
              if (after == parse_index) {
                log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                          CsRoleText(remote_role));
                return;
              }
              if (is_hal_v2()) {
                remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                hal::Mode0Data(tone_data));
              }
              parse_index = after;
            } else {
              LeCsMode0ReflectorData tone_data;
              after = LeCsMode0ReflectorData::Parse(&tone_data, parse_index);
              if (after == parse_index) {
                log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                          CsRoleText(remote_role));
                return;
              }
              if (is_hal_v2()) {
                remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                hal::Mode0Data(tone_data));
              }
            }
            parse_index = after;
          } break;
          case 1: {
            if (remote_role == CsRole::INITIATOR) {
              if (procedure_data->contains_sounding_sequence_remote_) {
                LeCsMode1InitiatorDataWithPacketPct tone_data;
                after = LeCsMode1InitiatorDataWithPacketPct::Parse(&tone_data, parse_index);
                if (after == parse_index) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index = after;
                procedure_data->toa_tod_initiators.emplace_back(tone_data.toa_tod_initiator_);
                procedure_data->packet_quality_initiator.emplace_back(tone_data.packet_quality_);
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode1Data(tone_data));
                }
              } else {
                LeCsMode1InitiatorData tone_data;
                after = LeCsMode1InitiatorData::Parse(&tone_data, parse_index);
                if (after == parse_index) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index = after;
                procedure_data->toa_tod_initiators.emplace_back(tone_data.toa_tod_initiator_);
                procedure_data->packet_quality_initiator.emplace_back(tone_data.packet_quality_);
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode1Data(tone_data));
                }
              }
            } else {
              if (procedure_data->contains_sounding_sequence_remote_) {
                LeCsMode1ReflectorDataWithPacketPct tone_data;
                after = LeCsMode1ReflectorDataWithPacketPct::Parse(&tone_data, parse_index);
                if (after == parse_index) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index = after;
                procedure_data->tod_toa_reflectors.emplace_back(tone_data.tod_toa_reflector_);
                procedure_data->packet_quality_reflector.emplace_back(tone_data.packet_quality_);
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode1Data(tone_data));
                }
              } else {
                LeCsMode1ReflectorData tone_data;
                after = LeCsMode1ReflectorData::Parse(&tone_data, parse_index);
                if (after == parse_index) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index = after;
                procedure_data->tod_toa_reflectors.emplace_back(tone_data.tod_toa_reflector_);
                procedure_data->packet_quality_reflector.emplace_back(tone_data.packet_quality_);
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode1Data(tone_data));
                }
              }
            }
          } break;
          case 2: {
            uint8_t num_tone_data = num_antenna_paths + 1;
            uint8_t data_len = 1 + (4 * num_tone_data);
            remaining_data_size = std::distance(parse_index, segment_data.end());
            if (remaining_data_size < data_len) {
              log::warn(
                      "insufficient length for LeCsMode2Data, num_tone_data {}, "
                      "remaining_data_size {}",
                      num_tone_data, remaining_data_size);
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
              log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                        CsRoleText(remote_role));
              return;
            }
            parse_index += data_len;
            uint8_t permutation_index = tone_data.antenna_permutation_index_;
            if (is_hal_v2()) {
              remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                              hal::Mode2Data(tone_data));
            }
            if (!is_valid_antenna_permutation_data(permutation_index, num_antenna_paths)) {
              log::error(
                      "Received invalid antenna permutation data (index: {}, paths: {}) for Mode 2 "
                      "data",
                      permutation_index, num_antenna_paths);
              procedure_data->contains_invalid_data_ = true;
              return;  // Skip following data
            }
            // Parse in ascending order of antenna position with tone extension data at the end
            for (uint8_t k = 0; k < num_tone_data; k++) {
              uint8_t antenna_path =
                      k == num_antenna_paths
                              ? num_antenna_paths
                              : cs_antenna_permutation_array_[permutation_index][k] - 1;
              double i_value = get_iq_value(tone_data.tone_data_[k].i_sample_);
              double q_value = get_iq_value(tone_data.tone_data_[k].q_sample_);
              uint8_t tone_quality_indicator = tone_data.tone_data_[k].tone_quality_indicator_;
              log::verbose("antenna_path {}, {:f}, {:f}", (uint16_t)(antenna_path + 1), i_value,
                           q_value);
              if (remote_role == CsRole::INITIATOR) {
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
          case 3: {
            uint8_t num_tone_data = num_antenna_paths + 1;
            uint8_t data_len = 7 + (4 * num_tone_data);
            if (procedure_data->contains_sounding_sequence_local_) {
              data_len += 8;  // 4 bytes for each packet_pct1, packet_pct2
            }
            remaining_data_size = std::distance(parse_index, segment_data.end());
            if (remaining_data_size < data_len) {
              log::warn(
                      "insufficient length for LeCsMode2Data, num_tone_data {}, "
                      "remaining_data_size {}",
                      num_tone_data, remaining_data_size);
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
            uint8_t permutation_index = 0;
            std::vector<LeCsToneDataWithQuality> view_tone_data = {};
            if (remote_role == CsRole::INITIATOR) {
              if (procedure_data->contains_sounding_sequence_local_) {
                LeCsMode3InitiatorDataWithPacketPct tone_data_view;
                after = LeCsMode3InitiatorDataWithPacketPct::Parse(&tone_data_view,
                                                                   packet_bytes_view.begin());
                if (after == packet_bytes_view.begin()) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index += data_len;
                log::verbose("step_data: {}", tone_data_view.ToString());
                permutation_index = tone_data_view.antenna_permutation_index_;
                procedure_data->toa_tod_initiators.emplace_back(tone_data_view.toa_tod_initiator_);
                procedure_data->packet_quality_initiator.emplace_back(
                        tone_data_view.packet_quality_);
                auto tone_data = tone_data_view.tone_data_;
                view_tone_data.reserve(tone_data.size());
                view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode3Data(tone_data_view));
                }
              } else {
                LeCsMode3InitiatorData tone_data_view;
                after = LeCsMode3InitiatorData::Parse(&tone_data_view, packet_bytes_view.begin());
                if (after == packet_bytes_view.begin()) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index += data_len;
                log::verbose("step_data: {}", tone_data_view.ToString());
                permutation_index = tone_data_view.antenna_permutation_index_;
                procedure_data->toa_tod_initiators.emplace_back(tone_data_view.toa_tod_initiator_);
                procedure_data->packet_quality_initiator.emplace_back(
                        tone_data_view.packet_quality_);
                auto tone_data = tone_data_view.tone_data_;
                view_tone_data.reserve(tone_data.size());
                view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode3Data(tone_data_view));
                }
              }
            } else {
              if (procedure_data->contains_sounding_sequence_local_) {
                LeCsMode3ReflectorDataWithPacketPct tone_data_view;
                after = LeCsMode3ReflectorDataWithPacketPct::Parse(&tone_data_view,
                                                                   packet_bytes_view.begin());
                if (after == packet_bytes_view.begin()) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index += data_len;
                log::verbose("step_data: {}", tone_data_view.ToString());
                permutation_index = tone_data_view.antenna_permutation_index_;
                procedure_data->rssi_reflector.emplace_back(tone_data_view.packet_rssi_);
                procedure_data->tod_toa_reflectors.emplace_back(tone_data_view.tod_toa_reflector_);
                procedure_data->packet_quality_reflector.emplace_back(
                        tone_data_view.packet_quality_);
                auto tone_data = tone_data_view.tone_data_;
                view_tone_data.reserve(tone_data.size());
                view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode3Data(tone_data_view));
                }
              } else {
                LeCsMode3ReflectorData tone_data_view;
                after = LeCsMode3ReflectorData::Parse(&tone_data_view, packet_bytes_view.begin());
                if (after == packet_bytes_view.begin()) {
                  log::warn("Error invalid mode {} data, role:{}", step_mode.mode_type_,
                            CsRoleText(remote_role));
                  return;
                }
                parse_index += data_len;
                log::verbose("step_data: {}", tone_data_view.ToString());
                permutation_index = tone_data_view.antenna_permutation_index_;
                procedure_data->rssi_reflector.emplace_back(tone_data_view.packet_rssi_);
                procedure_data->tod_toa_reflectors.emplace_back(tone_data_view.tod_toa_reflector_);
                procedure_data->packet_quality_reflector.emplace_back(
                        tone_data_view.packet_quality_);
                auto tone_data = tone_data_view.tone_data_;
                view_tone_data.reserve(tone_data.size());
                view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
                if (is_hal_v2()) {
                  remote_subevent_result->step_data_.emplace_back(step_channel, mode,
                                                                  hal::Mode3Data(tone_data_view));
                }
              }
            }
            if (!is_valid_antenna_permutation_data(permutation_index, num_antenna_paths)) {
              log::error(
                      "Received invalid antenna permutation data (index: {}, paths: {}) for Mode 3 "
                      "data",
                      permutation_index, num_antenna_paths);
              procedure_data->contains_invalid_data_ = true;
              return;  // Skip following data
            }
            // Parse in ascending order of antenna position with tone extension data at the end
            for (uint16_t k = 0; k < num_tone_data; k++) {
              uint8_t antenna_path =
                      k == num_antenna_paths
                              ? num_antenna_paths
                              : cs_antenna_permutation_array_[permutation_index][k] - 1;
              double i_value = get_iq_value(view_tone_data[k].i_sample_);
              double q_value = get_iq_value(view_tone_data[k].q_sample_);
              uint8_t tone_quality_indicator = view_tone_data[k].tone_quality_indicator_;
              log::verbose("antenna_path {}, {:f}, {:f}", (uint16_t)(antenna_path + 1), i_value,
                           q_value);
              if (remote_role == CsRole::INITIATOR) {
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
    check_cs_procedure_complete(&cs_requester_trackers_[connection_handle], procedure_data,
                                connection_handle);
  }

  CsProcedureData* init_cs_procedure_data(CsTracker* live_tracker, uint16_t procedure_counter,
                                          uint8_t num_antenna_paths) {
    // Update procedure count
    live_tracker->procedure_counter = procedure_counter;
    std::vector<CsProcedureData>& data_list = live_tracker->procedure_data_list;
    for (auto& data : data_list) {
      if (data.counter == procedure_counter) {
        // Data already exists, return
        log::warn("duplicated procedure counter - {}.", procedure_counter);
        return &data;
      }
    }

    log::info("Create data for procedure_counter: {}", procedure_counter);
    data_list.emplace_back(procedure_counter, num_antenna_paths, live_tracker->used_config_id,
                           live_tracker->selected_tx_power);

    // Check if sounding phase-based ranging is supported, and RTT type contains a sounding
    // sequence
    bool rtt_contains_sounding_sequence = false;
    if (live_tracker->rtt_type == CsRttType::RTT_WITH_32_BIT_SOUNDING_SEQUENCE ||
        live_tracker->rtt_type == CsRttType::RTT_WITH_96_BIT_SOUNDING_SEQUENCE) {
      rtt_contains_sounding_sequence = true;
    }
    data_list.back().contains_sounding_sequence_local_ =
            local_support_phase_based_ranging_ && rtt_contains_sounding_sequence;
    data_list.back().contains_sounding_sequence_remote_ =
            live_tracker->remote_support_phase_based_ranging && rtt_contains_sounding_sequence;

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

  CsProcedureData* get_procedure_data(CsTracker* live_tracker, uint16_t counter) {
    std::vector<CsProcedureData>& data_list = live_tracker->procedure_data_list;
    CsProcedureData* procedure_data = nullptr;
    for (uint8_t i = 0; i < data_list.size(); i++) {
      if (data_list[i].counter == counter) {
        procedure_data = &data_list[i];
        break;
      }
    }
    if (procedure_data == nullptr) {
      log::warn("Can't find data for counter: {}", counter);
    }
    return procedure_data;
  }

  CsProcedureData* get_procedure_data_for_ras(uint16_t connection_handle,
                                              uint16_t ranging_counter) {
    std::vector<CsProcedureData>& data_list =
            cs_requester_trackers_[connection_handle].procedure_data_list;
    CsProcedureData* procedure_data = nullptr;
    for (auto& i : data_list) {
      if ((i.counter & kRangingCounterMask) == ranging_counter) {
        procedure_data = &i;
        break;
      }
    }
    if (procedure_data == nullptr) {
      log::warn("Can't find data for connection_handle:{}, ranging_counter: {}", connection_handle,
                ranging_counter);
    }
    return procedure_data;
  }

  void try_send_data_to_hal(uint16_t connection_handle, const CsTracker* live_tracker,
                            const CsProcedureData* procedure_data) const {
    if (!ranging_hal_->IsBound() || procedure_data->contains_invalid_data_) {
      return;
    }
    bool should_send_to_hal = false;
    if (ranging_hal_->IsAbortedProcedureRequired(connection_handle)) {
      should_send_to_hal = procedure_data->local_status != CsProcedureDoneStatus::PARTIAL_RESULTS &&
                           procedure_data->remote_status != CsProcedureDoneStatus::PARTIAL_RESULTS;
    } else {
      should_send_to_hal =
              procedure_data->local_status == CsProcedureDoneStatus::ALL_RESULTS_COMPLETE &&
              procedure_data->remote_status == CsProcedureDoneStatus::ALL_RESULTS_COMPLETE &&
              procedure_data->contains_complete_subevent_;
    }
    if (should_send_to_hal) {
      log::debug("Procedure complete counter:{} data size:{}", (uint16_t)procedure_data->counter,
                 procedure_data->step_channel.size());
      if (is_hal_v2()) {
        ranging_hal_->WriteProcedureData(connection_handle, live_tracker->role,
                                         procedure_data->procedure_data_v2_,
                                         procedure_data->counter);
      } else {
        // Use algorithm in the HAL
        bluetooth::hal::ChannelSoundingRawData raw_data;
        raw_data.num_antenna_paths_ = procedure_data->num_antenna_paths;
        raw_data.step_channel_ = procedure_data->step_channel;
        raw_data.tone_pct_initiator_ = procedure_data->tone_pct_initiator;
        raw_data.tone_quality_indicator_initiator_ =
                procedure_data->tone_quality_indicator_initiator;
        raw_data.tone_pct_reflector_ = procedure_data->tone_pct_reflector;
        raw_data.tone_quality_indicator_reflector_ =
                procedure_data->tone_quality_indicator_reflector;
        raw_data.toa_tod_initiators_ = procedure_data->toa_tod_initiators;
        raw_data.tod_toa_reflectors_ = procedure_data->tod_toa_reflectors;
        raw_data.packet_quality_initiator = procedure_data->packet_quality_initiator;
        raw_data.packet_quality_reflector = procedure_data->packet_quality_reflector;
        ranging_hal_->WriteRawData(connection_handle, raw_data);
      }
    }
  }

  void check_cs_procedure_complete(CsTracker* live_tracker, CsProcedureData* procedure_data,
                                   uint16_t connection_handle) const {
    if (is_hal_v2() && procedure_data->local_status != CsProcedureDoneStatus::PARTIAL_RESULTS &&
        procedure_data->remote_status != CsProcedureDoneStatus::PARTIAL_RESULTS) {
      live_tracker->procedure_sequence_after_enable++;
      log::debug("procedure sequence after enabled is {}",
                 live_tracker->procedure_sequence_after_enable);
      procedure_data->procedure_data_v2_.procedure_sequence_ =
              live_tracker->procedure_sequence_after_enable;
    }

    if (com_android_bluetooth_flags_include_power_and_rssi_in_distance_measurement_result()) {
      for (size_t i = 0; i < procedure_data->rssi_reflector.size(); i++) {
        live_tracker->reflector_rssi_sum += procedure_data->rssi_reflector[i];
      }
      live_tracker->reflector_rssi_count += procedure_data->rssi_reflector.size();
    }

    try_send_data_to_hal(connection_handle, live_tracker, procedure_data);

    // If the procedure is completed or aborted, delete all previous data
    if (procedure_data->local_status != CsProcedureDoneStatus::PARTIAL_RESULTS &&
        procedure_data->remote_status != CsProcedureDoneStatus::PARTIAL_RESULTS) {
      delete_consumed_procedure_data(live_tracker, procedure_data->counter);
    }
  }

  static void delete_consumed_procedure_data(CsTracker* live_tracker, uint16_t current_counter) {
    std::vector<CsProcedureData>& data_list = live_tracker->procedure_data_list;
    if (!live_tracker->local_start) {
      // do this only for responder, as the procedure_counter for initiator could be rewind when it
      // was waiting for the remote data; and the data should be cleared on stop.
      while (data_list.size() > 0 && data_list.begin()->counter > current_counter) {
        log::debug("remove the trailing procedures from the last session for safe.");
        data_list.erase(data_list.begin());
      }
    }
    while (data_list.size() > 0 && data_list.begin()->counter <= current_counter) {
      log::debug("Delete obsolete procedure data, counter:{}", data_list.begin()->counter);
      data_list.erase(data_list.begin());
    }
  }

  void parse_cs_result_data(const std::vector<LeCsResultDataStructure>& result_data_structures,
                            CsProcedureData& procedure_data, CsRole role) {
    std::shared_ptr<hal::SubeventResult> local_subevent_data = nullptr;
    if (is_hal_v2()) {
      if (!procedure_data.procedure_data_v2_.local_subevent_data_.empty()) {
        local_subevent_data = procedure_data.procedure_data_v2_.local_subevent_data_.back();
      } else {
        log::error("no subevent data is available to attach");
      }
    }
    uint8_t num_antenna_paths = procedure_data.num_antenna_paths;
    auto& ras_data = procedure_data.ras_subevent_data_;
    for (auto& result_data_structure : result_data_structures) {
      uint8_t mode = result_data_structure.step_mode_;
      uint8_t step_channel = result_data_structure.step_channel_;
      uint16_t data_length = result_data_structure.step_data_.size();
      log::verbose("mode: {}, channel: {}, data_length: {}", mode, step_channel,
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
      bytes->insert(bytes->end(), result_data_structure.step_data_.begin(),
                    result_data_structure.step_data_.end());
      Iterator<packet::kLittleEndian> iterator(bytes);
      switch (mode) {
        case 0: {
          if (role == CsRole::INITIATOR) {
            LeCsMode0InitiatorData tone_data_view;
            auto after = LeCsMode0InitiatorData::Parse(&tone_data_view, iterator);
            if (after == iterator) {
              log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
              print_raw_data(result_data_structure.step_data_);
              continue;
            }
            log::verbose("step_data: {}", tone_data_view.ToString());
            procedure_data.measured_freq_offset.push_back(tone_data_view.measured_freq_offset_);
            if (is_hal_v2() && local_subevent_data) {
              local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                           hal::Mode0Data(tone_data_view));
            }
          } else {
            LeCsMode0ReflectorData tone_data_view;
            auto after = LeCsMode0ReflectorData::Parse(&tone_data_view, iterator);
            if (after == iterator) {
              log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
              print_raw_data(result_data_structure.step_data_);
              continue;
            }
            log::verbose("step_data: {}", tone_data_view.ToString());
            if (is_hal_v2() && local_subevent_data) {
              local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                           hal::Mode0Data(tone_data_view));
            }
          }
        } break;
        case 1: {
          if (role == CsRole::INITIATOR) {
            if (procedure_data.contains_sounding_sequence_local_) {
              LeCsMode1InitiatorDataWithPacketPct tone_data_view;
              auto after = LeCsMode1InitiatorDataWithPacketPct::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              procedure_data.toa_tod_initiators.emplace_back(tone_data_view.toa_tod_initiator_);
              procedure_data.packet_quality_initiator.emplace_back(tone_data_view.packet_quality_);
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode1Data(tone_data_view));
              }
            } else {
              LeCsMode1InitiatorData tone_data_view;
              auto after = LeCsMode1InitiatorData::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              procedure_data.toa_tod_initiators.emplace_back(tone_data_view.toa_tod_initiator_);
              procedure_data.packet_quality_initiator.emplace_back(tone_data_view.packet_quality_);
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode1Data(tone_data_view));
              }
            }
            procedure_data.step_channel.push_back(step_channel);
          } else {
            if (procedure_data.contains_sounding_sequence_local_) {
              LeCsMode1ReflectorDataWithPacketPct tone_data_view;
              auto after = LeCsMode1ReflectorDataWithPacketPct::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              procedure_data.rssi_reflector.emplace_back(tone_data_view.packet_rssi_);
              procedure_data.tod_toa_reflectors.emplace_back(tone_data_view.tod_toa_reflector_);
              procedure_data.packet_quality_reflector.emplace_back(tone_data_view.packet_quality_);
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode1Data(tone_data_view));
              }
            } else {
              LeCsMode1ReflectorData tone_data_view;
              auto after = LeCsMode1ReflectorData::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              procedure_data.rssi_reflector.emplace_back(tone_data_view.packet_rssi_);
              procedure_data.tod_toa_reflectors.emplace_back(tone_data_view.tod_toa_reflector_);
              procedure_data.packet_quality_reflector.emplace_back(tone_data_view.packet_quality_);
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode1Data(tone_data_view));
              }
            }
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
          if (is_hal_v2() && local_subevent_data) {
            local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                         hal::Mode2Data(tone_data_view));
          }
          log::verbose("step_data: {}", tone_data_view.ToString());
          if (role == CsRole::INITIATOR) {
            procedure_data.step_channel.push_back(step_channel);
          }
          auto tone_data = tone_data_view.tone_data_;
          // Validate permutation index based on num_antenna_paths
          uint8_t permutation_index = tone_data_view.antenna_permutation_index_;
          if (!is_valid_antenna_permutation_data(permutation_index, num_antenna_paths)) {
            log::error(
                    "Received invalid antenna permutation data (index: {}, paths: {}) for Mode 2 "
                    "data",
                    permutation_index, num_antenna_paths);
            procedure_data.contains_invalid_data_ = true;
            return;  // Skip following data
          }

          // Parse in ascending order of antenna position with tone extension data at the end
          uint16_t num_tone_data = num_antenna_paths + 1;
          for (uint16_t k = 0; k < num_tone_data; k++) {
            uint8_t antenna_path =
                    k == num_antenna_paths
                            ? num_antenna_paths
                            : cs_antenna_permutation_array_[permutation_index][k] - 1;
            double i_value = get_iq_value(tone_data[k].i_sample_);
            double q_value = get_iq_value(tone_data[k].q_sample_);
            uint8_t tone_quality_indicator = tone_data[k].tone_quality_indicator_;
            log::verbose("antenna_path {}, {:f}, {:f}", (uint16_t)(antenna_path + 1), i_value,
                         q_value);
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
        case 3: {
          uint8_t permutation_index = 0;
          std::vector<LeCsToneDataWithQuality> view_tone_data = {};
          if (role == CsRole::INITIATOR) {
            if (procedure_data.contains_sounding_sequence_local_) {
              LeCsMode3InitiatorDataWithPacketPct tone_data_view;
              auto after = LeCsMode3InitiatorDataWithPacketPct::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode3Data(tone_data_view));
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              permutation_index = tone_data_view.antenna_permutation_index_;
              procedure_data.toa_tod_initiators.emplace_back(tone_data_view.toa_tod_initiator_);
              procedure_data.packet_quality_initiator.emplace_back(tone_data_view.packet_quality_);
              auto tone_data = tone_data_view.tone_data_;
              view_tone_data.reserve(tone_data.size());
              view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
            } else {
              LeCsMode3InitiatorData tone_data_view;
              auto after = LeCsMode3InitiatorData::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode3Data(tone_data_view));
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              permutation_index = tone_data_view.antenna_permutation_index_;
              procedure_data.toa_tod_initiators.emplace_back(tone_data_view.toa_tod_initiator_);
              procedure_data.packet_quality_initiator.emplace_back(tone_data_view.packet_quality_);
              auto tone_data = tone_data_view.tone_data_;
              view_tone_data.reserve(tone_data.size());
              view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
            }
            procedure_data.step_channel.push_back(step_channel);
          } else {
            if (procedure_data.contains_sounding_sequence_local_) {
              LeCsMode3ReflectorDataWithPacketPct tone_data_view;
              auto after = LeCsMode3ReflectorDataWithPacketPct::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode3Data(tone_data_view));
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              permutation_index = tone_data_view.antenna_permutation_index_;
              procedure_data.rssi_reflector.emplace_back(tone_data_view.packet_rssi_);
              procedure_data.tod_toa_reflectors.emplace_back(tone_data_view.tod_toa_reflector_);
              procedure_data.packet_quality_reflector.emplace_back(tone_data_view.packet_quality_);
              auto tone_data = tone_data_view.tone_data_;
              view_tone_data.reserve(tone_data.size());
              view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
            } else {
              LeCsMode3ReflectorData tone_data_view;
              auto after = LeCsMode3ReflectorData::Parse(&tone_data_view, iterator);
              if (after == iterator) {
                log::warn("Received invalid mode {} data, role:{}", mode, CsRoleText(role));
                print_raw_data(result_data_structure.step_data_);
                continue;
              }
              if (is_hal_v2() && local_subevent_data) {
                local_subevent_data->step_data_.emplace_back(step_channel, mode,
                                                             hal::Mode3Data(tone_data_view));
              }
              log::verbose("step_data: {}", tone_data_view.ToString());
              permutation_index = tone_data_view.antenna_permutation_index_;
              procedure_data.rssi_reflector.emplace_back(tone_data_view.packet_rssi_);
              procedure_data.tod_toa_reflectors.emplace_back(tone_data_view.tod_toa_reflector_);
              procedure_data.packet_quality_reflector.emplace_back(tone_data_view.packet_quality_);
              auto tone_data = tone_data_view.tone_data_;
              view_tone_data.reserve(tone_data.size());
              view_tone_data.insert(view_tone_data.end(), tone_data.begin(), tone_data.end());
            }
          }

          // Validate permutation index based on num_antenna_paths
          if (!is_valid_antenna_permutation_data(permutation_index, num_antenna_paths)) {
            log::error(
                    "Received invalid antenna permutation data (index: {}, paths: {}) for Mode 3 "
                    "data",
                    permutation_index, num_antenna_paths);
            procedure_data.contains_invalid_data_ = true;
            return;  // Skip following data
          }

          // Parse in ascending order of antenna position with tone extension data at the end
          uint16_t num_tone_data = num_antenna_paths + 1;
          for (uint16_t k = 0; k < num_tone_data; k++) {
            uint8_t antenna_path =
                    k == num_antenna_paths
                            ? num_antenna_paths
                            : cs_antenna_permutation_array_[permutation_index][k] - 1;
            double i_value = get_iq_value(view_tone_data[k].i_sample_);
            double q_value = get_iq_value(view_tone_data[k].q_sample_);
            uint8_t tone_quality_indicator = view_tone_data[k].tone_quality_indicator_;
            log::verbose("antenna_path {}, {:f}, {:f}", (uint16_t)(antenna_path + 1), i_value,
                         q_value);
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
        default: {
          log::warn("Invalid mode {}", mode);
        }
      }
    }
  }

  bool is_valid_antenna_permutation_data(uint8_t permutation_index, uint8_t num_antenna_paths) {
    if (num_antenna_paths < 1 || num_antenna_paths > 4) {
      return false;
    }
    uint8_t max_valid_permutation_index = max_valid_permutation_index_table_[num_antenna_paths - 1];
    return permutation_index <= max_valid_permutation_index;
  }

  double get_iq_value(uint16_t sample) {
    int16_t signed_sample = hal::ConvertToSigned<12>(sample);
    double value = 1.0 * signed_sample / 2048;
    return value;
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
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_RSSI);
      rssi_trackers.erase(address);
    } else if (status_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(status_view.GetStatus());
      log::warn("Received LeReadRemoteTransmitPowerLevelStatus with error code {}", error_code);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_RSSI);
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
                LeSetTransmitPowerReportingEnableBuilder::Create(event_view.GetConnectionHandle(),
                                                                 0x00, 0x01),
                handler_->BindOnceOn(this, &impl::on_set_transmit_power_reporting_enable_complete,
                                     address, event_view.GetConnectionHandle()));
      } else {
        log::warn("Read remote transmit power level fail");
        distance_measurement_callbacks_->OnDistanceMeasurementStopped(
                address, REASON_INTERNAL_ERROR, METHOD_RSSI);
        rssi_trackers.erase(address);
      }
    }
  }

  void on_set_transmit_power_reporting_enable_complete(Address address, uint16_t connection_handle,
                                                       CommandCompleteView view) {
    auto complete_view = LeSetTransmitPowerReportingEnableCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      log::warn("Invalid LeSetTransmitPowerReportingEnableComplete event");
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_RSSI);
      rssi_trackers.erase(address);
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      log::warn("Received LeSetTransmitPowerReportingEnableComplete with error code {}",
                error_code);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_RSSI);
      rssi_trackers.erase(address);
      return;
    }

    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      log::warn("Can't find rssi tracker for {}", address);
      distance_measurement_callbacks_->OnDistanceMeasurementStopped(address, REASON_INTERNAL_ERROR,
                                                                    METHOD_RSSI);
      rssi_trackers.erase(address);
    } else {
      log::info("Track rssi for address {}", address);
      rssi_trackers[address].started = true;
      distance_measurement_callbacks_->OnDistanceMeasurementStarted(address, METHOD_RSSI);
      rssi_trackers[address].repeating_alarm->Schedule(
              common::Bind(&impl::send_read_rssi, common::Unretained(this), address,
                           connection_handle),
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
      log::warn("Can't find rssi tracker for {}", address);
      return;
    }
    double remote_tx_power = (int8_t)rssi_trackers[address].remote_tx_power;
    int8_t rssi = complete_view.GetRssi();
    double pow_value = (remote_tx_power - rssi - kRSSIDropOffAt1M) / 20.0;
    double distance = pow(10.0, pow_value);

    uint64_t elapsedRealtimeNanos = ::android::elapsedRealtimeNano();
    distance_measurement_callbacks_->OnDistanceMeasurementResult(
            address, distance * 100, distance * 100, kInvalidAzimuthAngleDegree,
            kInvalidAzimuthAngleDegree, kInvalidAltitudeAngleDegree, kInvalidAltitudeAngleDegree,
            elapsedRealtimeNanos, remote_tx_power, rssi, kInvalidConfidenceLevel,
            kInvalidDelayedSpreadMeters,
            DistanceMeasurementDetectedAttackLevel::NADM_ATTACK_UNKNOWN,
            kInvalidVelocityMetersPerSecond, DistanceMeasurementMethod::METHOD_RSSI);
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

  os::Handler* handler_ = nullptr;
  hal::RangingHal* ranging_hal_ = nullptr;
  hci::Controller* controller_ = nullptr;
  hci::HciInterface* hci_layer_ = nullptr;
  hci::AclManagerLe* acl_manager_ = nullptr;
  hci::DistanceMeasurementInterface* distance_measurement_interface_ = nullptr;
  std::unordered_map<Address, RSSITracker> rssi_trackers;
  std::unordered_map<uint16_t, CsTracker> cs_requester_trackers_;
  std::unordered_map<uint16_t, CsTracker> cs_responder_trackers_;
  std::unordered_map<uint16_t, uint16_t> gatt_mtus_;
  DistanceMeasurementCallbacks* distance_measurement_callbacks_ = nullptr;
  CsOptionalSubfeaturesSupported cs_subfeature_supported_;
  uint8_t num_antennas_supported_ = 0x01;
  bool local_support_phase_based_ranging_ = false;
  uint8_t local_supported_sw_time_ = 0;
  uint8_t local_max_antenna_paths_supported_ = 0x01;
  bool is_local_cs_ready_ = false;
  // A table that maps num_antennas_supported and remote_num_antennas_supported to Antenna
  // Configuration Index.
  uint8_t cs_tone_antenna_config_mapping_table_[4][4] = {
          {0, 4, 5, 6}, {1, 7, 7, 7}, {2, 7, 7, 7}, {3, 7, 7, 7}};
  // A table that maps Antenna Configuration Index to Preferred Peer Antenna.
  uint8_t cs_preferred_peer_antenna_mapping_table_[8] = {1, 1, 1, 1, 3, 7, 15, 3};
  // A table that maps the maximum valid permutation index based on num_antenna_paths.
  // The total number of permutations for N items is N! (index start from 0).
  uint8_t max_valid_permutation_index_table_[4] = {0, 1, 5, 23};
  // Antenna path permutations. See Channel Sounding CR_PR for the details.
  uint8_t cs_antenna_permutation_array_[24][4] = {
          {1, 2, 3, 4}, {2, 1, 3, 4}, {1, 3, 2, 4}, {3, 1, 2, 4}, {3, 2, 1, 4}, {2, 3, 1, 4},
          {1, 2, 4, 3}, {2, 1, 4, 3}, {1, 4, 2, 3}, {4, 1, 2, 3}, {4, 2, 1, 3}, {2, 4, 1, 3},
          {1, 4, 3, 2}, {4, 1, 3, 2}, {1, 3, 4, 2}, {3, 1, 4, 2}, {3, 4, 1, 2}, {4, 3, 1, 2},
          {4, 2, 3, 1}, {2, 4, 3, 1}, {4, 3, 2, 1}, {3, 4, 2, 1}, {3, 2, 4, 1}, {2, 3, 4, 1}};
};

DistanceMeasurementManagerImpl::DistanceMeasurementManagerImpl(os::Handler* handler,
                                                               hci::HciInterface* hci_layer,
                                                               hci::Controller* controller,
                                                               hci::AclManagerLe* acl_manager,
                                                               hal::RangingHal* ranging_hal) {
  pimpl_ = std::make_unique<impl>(handler, hci_layer, controller, acl_manager, ranging_hal);
  log::verbose("DistanceMeasurementManager module started !!");
}

DistanceMeasurementManagerImpl::~DistanceMeasurementManagerImpl() {
  log::verbose("DistanceMeasurementManager module stopped !!");
};

void DistanceMeasurementManagerImpl::RegisterDistanceMeasurementCallbacks(
        DistanceMeasurementCallbacks* callbacks) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::register_distance_measurement_callbacks, callbacks);
}

void DistanceMeasurementManagerImpl::StartDistanceMeasurement(
        int32_t app_uid, const Address& address, uint16_t connection_handle,
        hci::Role local_hci_role, uint16_t interval, DistanceMeasurementMethod method,
        DistanceMeasurementSightType sight_type, DistanceMeasurementLocationType location_type) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::start_distance_measurement, app_uid, address,
                           connection_handle, local_hci_role, interval, method, sight_type,
                           location_type);
}

void DistanceMeasurementManagerImpl::StopDistanceMeasurement(const Address& address,
                                                             uint16_t connection_handle,
                                                             DistanceMeasurementMethod method) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::stop_distance_measurement, address,
                           connection_handle, method);
}

void DistanceMeasurementManagerImpl::HandleRasClientConnectedEvent(
        const Address& address, uint16_t connection_handle, uint16_t att_handle,
        const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data,
        uint16_t conn_interval) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_ras_client_connected_event, address,
                           connection_handle, att_handle, vendor_specific_data, conn_interval);
}

void DistanceMeasurementManagerImpl::HandleConnIntervalUpdated(const Address& address,
                                                               uint16_t connection_handle,
                                                               uint16_t conn_interval) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_conn_interval_updated, address,
                           connection_handle, conn_interval);
}

void DistanceMeasurementManagerImpl::HandleRasClientDisconnectedEvent(
        const Address& address, const ras::RasDisconnectReason& ras_disconnect_reason) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_ras_client_disconnected_event, address,
                           ras_disconnect_reason);
}

void DistanceMeasurementManagerImpl::HandleVendorSpecificReply(
        const Address& address, uint16_t connection_handle,
        const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_ras_server_vendor_specific_reply, address,
                           connection_handle, vendor_specific_reply);
}

void DistanceMeasurementManagerImpl::HandleRasServerConnected(const Address& identity_address,
                                                              uint16_t connection_handle,
                                                              hci::Role local_hci_role) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_ras_server_connected, identity_address,
                           connection_handle, local_hci_role);
}

void DistanceMeasurementManagerImpl::HandleMtuChanged(uint16_t connection_handle, uint16_t mtu) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_mtu_changed, connection_handle, mtu);
}

void DistanceMeasurementManagerImpl::HandleRasServerDisconnected(
        const bluetooth::hci::Address& identity_address, uint16_t connection_handle) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_ras_server_disconnected, identity_address,
                           connection_handle);
}

void DistanceMeasurementManagerImpl::HandleVendorSpecificReplyComplete(const Address& address,
                                                                       uint16_t connection_handle,
                                                                       bool success) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_vendor_specific_reply_complete, address,
                           connection_handle, success);
}

void DistanceMeasurementManagerImpl::HandleRemoteData(const Address& address,
                                                      uint16_t connection_handle,
                                                      const std::vector<uint8_t>& raw_data) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_remote_data, address, connection_handle,
                           raw_data);
}

void DistanceMeasurementManagerImpl::HandleRemoteDataTimeout(const Address& address,
                                                             uint16_t connection_handle) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::handle_remote_data_timeout, address,
                           connection_handle);
}

}  // namespace hci
}  // namespace bluetooth
