/*
 * Copyright 2024 The Android Open Source Project
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

#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSounding.h>
#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSoundingSession.h>
#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSoundingSessionCallback.h>
#include <aidl/android/hardware/bluetooth/ranging/IBluetoothChannelSounding.h>
#include <android/binder_manager.h>
#include <bluetooth/log.h>

#include <unordered_map>

// AIDL uses syslog.h, so these defines conflict with log/log.h
#undef LOG_DEBUG
#undef LOG_INFO
#undef LOG_WARNING

#include "ranging_hal.h"

using aidl::android::hardware::bluetooth::ranging::BluetoothChannelSoundingParameters;
using aidl::android::hardware::bluetooth::ranging::BnBluetoothChannelSoundingSessionCallback;
using aidl::android::hardware::bluetooth::ranging::Ch3cShapeType;
using aidl::android::hardware::bluetooth::ranging::ChannelSelectionType;
using aidl::android::hardware::bluetooth::ranging::ChannelSoudingRawData;
using aidl::android::hardware::bluetooth::ranging::ChannelSoundingProcedureData;
using aidl::android::hardware::bluetooth::ranging::ComplexNumber;
using aidl::android::hardware::bluetooth::ranging::Config;
using aidl::android::hardware::bluetooth::ranging::CsSyncPhyType;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSounding;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSoundingSession;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSoundingSessionCallback;
using aidl::android::hardware::bluetooth::ranging::ModeType;
using aidl::android::hardware::bluetooth::ranging::ProcedureEnableConfig;
using aidl::android::hardware::bluetooth::ranging::Role;
using aidl::android::hardware::bluetooth::ranging::RttType;
using aidl::android::hardware::bluetooth::ranging::StepTonePct;
using aidl::android::hardware::bluetooth::ranging::SubModeType;
using aidl::android::hardware::bluetooth::ranging::VendorSpecificData;
// using aidl::android::hardware::bluetooth::ranging::

using aidl::android::hardware::bluetooth::ranging::ChannelSoundingProcedureData;
using aidl::android::hardware::bluetooth::ranging::ModeData;
using aidl::android::hardware::bluetooth::ranging::ModeOneData;
using aidl::android::hardware::bluetooth::ranging::ModeThreeData;
using aidl::android::hardware::bluetooth::ranging::ModeTwoData;
using aidl::android::hardware::bluetooth::ranging::ModeType;
using aidl::android::hardware::bluetooth::ranging::ModeZeroData;
using aidl::android::hardware::bluetooth::ranging::Nadm;
using aidl::android::hardware::bluetooth::ranging::PctIQSample;
using aidl::android::hardware::bluetooth::ranging::ProcedureAbortReason;
using aidl::android::hardware::bluetooth::ranging::RttToaTodData;
using aidl::android::hardware::bluetooth::ranging::StepData;
using aidl::android::hardware::bluetooth::ranging::SubeventAbortReason;
using aidl::android::hardware::bluetooth::ranging::SubeventResultData;

namespace bluetooth {
namespace hal {

class BluetoothChannelSoundingSessionTracker : public BnBluetoothChannelSoundingSessionCallback {
public:
  BluetoothChannelSoundingSessionTracker(uint16_t connection_handle,
                                         RangingHalCallback* ranging_hal_callback,
                                         bool for_vendor_specific_reply, RangingHalVersion hal_ver)
      : connection_handle_(connection_handle),
        ranging_hal_callback_(ranging_hal_callback),
        for_vendor_specific_reply_(for_vendor_specific_reply),
        hal_ver_(hal_ver) {}

  ::ndk::ScopedAStatus onOpened(::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("connection_handle 0x{:04x}, reason {}", connection_handle_, (uint16_t)in_reason);
    if (for_vendor_specific_reply_) {
      ranging_hal_callback_->OnHandleVendorSpecificReplyComplete(connection_handle_, true);
    }
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus onOpenFailed(
          ::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("connection_handle 0x{:04x}, reason {}", connection_handle_, (uint16_t)in_reason);
    bluetooth_channel_sounding_session_ = nullptr;
    if (for_vendor_specific_reply_) {
      ranging_hal_callback_->OnHandleVendorSpecificReplyComplete(connection_handle_, false);
    } else {
      ranging_hal_callback_->OnOpenFailed(connection_handle_);
    }
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus onResult(
          const ::aidl::android::hardware::bluetooth::ranging::RangingResult& in_result) {
    log::verbose("resultMeters {}", in_result.resultMeters);
    hal::RangingResult ranging_result = {
            .result_meters_ = in_result.resultMeters,
            .error_meters_ = in_result.errorMeters,
            .confidence_level_ = in_result.confidenceLevel,
            .delay_spread_meters_ = in_result.delaySpreadMeters,
            .detected_attack_level_ = static_cast<uint8_t>(in_result.detectedAttackLevel),
            .velocity_meters_per_second_ = in_result.velocityMetersPerSecond,
    };
    if (hal_ver_ == V_2) {
      ranging_result.elapsed_timestamp_nanos_ = in_result.timestampNanos;
    }
    ranging_hal_callback_->OnResult(connection_handle_, ranging_result);
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus onClose(::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("reason {}", (uint16_t)in_reason);
    bluetooth_channel_sounding_session_ = nullptr;
    return ::ndk::ScopedAStatus::ok();
  }
  ::ndk::ScopedAStatus onCloseFailed(
          ::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("reason {}", (uint16_t)in_reason);
    return ::ndk::ScopedAStatus::ok();
  }

  std::shared_ptr<IBluetoothChannelSoundingSession>& GetSession() {
    return bluetooth_channel_sounding_session_;
  }

private:
  std::shared_ptr<IBluetoothChannelSoundingSession> bluetooth_channel_sounding_session_ = nullptr;
  uint16_t connection_handle_;
  RangingHalCallback* ranging_hal_callback_;
  bool for_vendor_specific_reply_;
  RangingHalVersion hal_ver_;
};

class RangingHalAndroid : public RangingHal {
public:
  bool IsBound() override { return bluetooth_channel_sounding_ != nullptr; }

  RangingHalVersion GetRangingHalVersion() { return hal_ver_; }

  void RegisterCallback(RangingHalCallback* callback) { ranging_hal_callback_ = callback; }

  std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() override {
    std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics = {};
    if (bluetooth_channel_sounding_ != nullptr) {
      std::optional<std::vector<std::optional<VendorSpecificData>>> vendorSpecificDataOptional;
      bluetooth_channel_sounding_->getVendorSpecificData(&vendorSpecificDataOptional);
      if (vendorSpecificDataOptional.has_value()) {
        for (auto vendor_specific_data : vendorSpecificDataOptional.value()) {
          VendorSpecificCharacteristic vendor_specific_characteristic;
          vendor_specific_characteristic.characteristicUuid_ =
                  vendor_specific_data->characteristicUuid;
          vendor_specific_characteristic.value_ = vendor_specific_data->opaqueValue;
          vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
        }
      }
      log::info("size {}", vendor_specific_characteristics.size());
    } else {
      log::warn("bluetooth_channel_sounding_ is nullptr");
    }

    return vendor_specific_characteristics;
  }

  void OpenSession(uint16_t connection_handle, uint16_t att_handle,
                   const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data) {
    log::info("connection_handle 0x{:04x}, att_handle 0x{:04x} size of vendor_specific_data {}",
              connection_handle, att_handle, vendor_specific_data.size());
    session_trackers_[connection_handle] =
            ndk::SharedRefBase::make<BluetoothChannelSoundingSessionTracker>(
                    connection_handle, ranging_hal_callback_, false, hal_ver_);
    BluetoothChannelSoundingParameters parameters;
    parameters.aclHandle = connection_handle;
    parameters.role = aidl::android::hardware::bluetooth::ranging::Role::INITIATOR;
    parameters.realTimeProcedureDataAttHandle = att_handle;
    CopyVendorSpecificData(vendor_specific_data, parameters.vendorSpecificData);

    auto& tracker = session_trackers_[connection_handle];
    bluetooth_channel_sounding_->openSession(parameters, tracker, &tracker->GetSession());

    if (tracker->GetSession() != nullptr) {
      std::vector<VendorSpecificCharacteristic> vendor_specific_reply = {};
      std::optional<std::vector<std::optional<VendorSpecificData>>> vendorSpecificDataOptional;
      tracker->GetSession()->getVendorSpecificReplies(&vendorSpecificDataOptional);

      if (vendorSpecificDataOptional.has_value()) {
        for (auto& data : vendorSpecificDataOptional.value()) {
          VendorSpecificCharacteristic vendor_specific_characteristic;
          vendor_specific_characteristic.characteristicUuid_ = data->characteristicUuid;
          vendor_specific_characteristic.value_ = data->opaqueValue;
          vendor_specific_reply.emplace_back(vendor_specific_characteristic);
        }
      }
      ranging_hal_callback_->OnOpened(connection_handle, vendor_specific_reply);
    }
  }

  void HandleVendorSpecificReply(
          uint16_t connection_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply) {
    log::info("connection_handle 0x{:04x}", connection_handle);
    session_trackers_[connection_handle] =
            ndk::SharedRefBase::make<BluetoothChannelSoundingSessionTracker>(
                    connection_handle, ranging_hal_callback_, true, hal_ver_);
    BluetoothChannelSoundingParameters parameters;
    parameters.aclHandle = connection_handle;
    parameters.role = aidl::android::hardware::bluetooth::ranging::Role::REFLECTOR;
    CopyVendorSpecificData(vendor_specific_reply, parameters.vendorSpecificData);
    auto& tracker = session_trackers_[connection_handle];
    bluetooth_channel_sounding_->openSession(parameters, tracker, &tracker->GetSession());
  }

  void WriteRawData(uint16_t connection_handle, const ChannelSoundingRawData& raw_data) {
    if (session_trackers_.find(connection_handle) == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return;
    } else if (session_trackers_[connection_handle]->GetSession() == nullptr) {
      log::error("Session not opened");
      return;
    }

    ChannelSoudingRawData hal_raw_data;
    hal_raw_data.numAntennaPaths = raw_data.num_antenna_paths_;
    hal_raw_data.stepChannels = raw_data.step_channel_;
    hal_raw_data.initiatorData.stepTonePcts.emplace(std::vector<std::optional<StepTonePct>>{});
    hal_raw_data.reflectorData.stepTonePcts.emplace(std::vector<std::optional<StepTonePct>>{});
    // Add tone data for mode 2, mode 3
    for (uint8_t i = 0; i < raw_data.tone_pct_initiator_.size(); i++) {
      StepTonePct step_tone_pct;
      for (uint8_t j = 0; j < raw_data.tone_pct_initiator_[i].size(); j++) {
        ComplexNumber complex_number;
        complex_number.imaginary = raw_data.tone_pct_initiator_[i][j].imag();
        complex_number.real = raw_data.tone_pct_initiator_[i][j].real();
        step_tone_pct.tonePcts.emplace_back(complex_number);
      }
      step_tone_pct.toneQualityIndicator = raw_data.tone_quality_indicator_initiator_[i];
      hal_raw_data.initiatorData.stepTonePcts.value().emplace_back(step_tone_pct);
    }
    for (uint8_t i = 0; i < raw_data.tone_pct_reflector_.size(); i++) {
      StepTonePct step_tone_pct;
      for (uint8_t j = 0; j < raw_data.tone_pct_reflector_[i].size(); j++) {
        ComplexNumber complex_number;
        complex_number.imaginary = raw_data.tone_pct_reflector_[i][j].imag();
        complex_number.real = raw_data.tone_pct_reflector_[i][j].real();
        step_tone_pct.tonePcts.emplace_back(complex_number);
      }
      step_tone_pct.toneQualityIndicator = raw_data.tone_quality_indicator_reflector_[i];
      hal_raw_data.reflectorData.stepTonePcts.value().emplace_back(step_tone_pct);
    }
    // Add RTT data for mode 1, mode 3
    if (!raw_data.toa_tod_initiators_.empty()) {
      hal_raw_data.toaTodInitiator = std::vector<int32_t>(raw_data.toa_tod_initiators_.begin(),
                                                          raw_data.toa_tod_initiators_.end());
      hal_raw_data.initiatorData.packetQuality = std::vector<uint8_t>(
              raw_data.packet_quality_initiator.begin(), raw_data.packet_quality_initiator.end());
    }
    if (!raw_data.tod_toa_reflectors_.empty()) {
      hal_raw_data.todToaReflector = std::vector<int32_t>(raw_data.tod_toa_reflectors_.begin(),
                                                          raw_data.tod_toa_reflectors_.end());
      hal_raw_data.reflectorData.packetQuality = std::vector<uint8_t>(
              raw_data.packet_quality_reflector.begin(), raw_data.packet_quality_reflector.end());
    }
    session_trackers_[connection_handle]->GetSession()->writeRawData(hal_raw_data);
  }

  void UpdateChannelSoundingConfig(uint16_t connection_handle,
                                   const hci::LeCsConfigCompleteView& leCsConfigCompleteView,
                                   uint8_t local_supported_sw_time,
                                   uint8_t remote_supported_sw_time,
                                   uint16_t conn_interval) override {
    auto it = session_trackers_.find(connection_handle);
    if (it == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return;
    } else if (it->second->GetSession() == nullptr) {
      log::error("Session not opened");
      return;
    }

    Config csConfig{
            .modeType = static_cast<ModeType>(
                    static_cast<int>(leCsConfigCompleteView.GetMainModeType())),
            .subModeType = static_cast<SubModeType>(
                    static_cast<int>(leCsConfigCompleteView.GetSubModeType())),
            .rttType = static_cast<RttType>(static_cast<int>(leCsConfigCompleteView.GetRttType())),
            .channelMap = leCsConfigCompleteView.GetChannelMap(),
            .minMainModeSteps = leCsConfigCompleteView.GetMinMainModeSteps(),
            .maxMainModeSteps = leCsConfigCompleteView.GetMaxMainModeSteps(),
            .mainModeRepetition =
                    static_cast<int8_t>(leCsConfigCompleteView.GetMainModeRepetition()),
            .mode0Steps = static_cast<int8_t>(leCsConfigCompleteView.GetMode0Steps()),
            .role = static_cast<Role>(static_cast<int>(leCsConfigCompleteView.GetRole())),
            .csSyncPhyType = static_cast<CsSyncPhyType>(
                    static_cast<int>(leCsConfigCompleteView.GetCsSyncPhy())),
            .channelSelectionType = static_cast<ChannelSelectionType>(
                    static_cast<int>(leCsConfigCompleteView.GetChannelSelectionType())),
            .ch3cShapeType = static_cast<Ch3cShapeType>(
                    static_cast<int>(leCsConfigCompleteView.GetCh3cShape())),
            .ch3cJump = static_cast<int8_t>(leCsConfigCompleteView.GetCh3cJump()),
            .channelMapRepetition = leCsConfigCompleteView.GetChannelMapRepetition(),
            .tIp1TimeUs = leCsConfigCompleteView.GetTIp1Time(),
            .tIp2TimeUs = leCsConfigCompleteView.GetTIp2Time(),
            .tFcsTimeUs = leCsConfigCompleteView.GetTFcsTime(),
            .tPmTimeUs = static_cast<int8_t>(leCsConfigCompleteView.GetTPmTime()),
            .tSwTimeUsSupportedByLocal = static_cast<int8_t>(local_supported_sw_time),
            .tSwTimeUsSupportedByRemote = static_cast<int8_t>(remote_supported_sw_time),
            .bleConnInterval = conn_interval,
    };

    it->second->GetSession()->updateChannelSoundingConfig(csConfig);
  }

  void UpdateConnInterval(uint16_t connection_handle, uint16_t conn_interval) override {
    auto it = session_trackers_.find(connection_handle);
    if (it == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return;
    } else if (it->second->GetSession() == nullptr) {
      log::error("Session not opened");
      return;
    }

    it->second->GetSession()->updateBleConnInterval(conn_interval);
  }

  void UpdateProcedureEnableConfig(
          uint16_t connection_handle,
          const hci::LeCsProcedureEnableCompleteView& leCsProcedureEnableCompleteView) override {
    auto it = session_trackers_.find(connection_handle);
    if (it == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return;
    } else if (it->second->GetSession() == nullptr) {
      log::error("Session not opened");
      return;
    }

    ProcedureEnableConfig pConfig{
            .toneAntennaConfigSelection = static_cast<int8_t>(
                    leCsProcedureEnableCompleteView.GetToneAntennaConfigSelection()),
            .subeventLenUs = static_cast<int>(leCsProcedureEnableCompleteView.GetSubeventLen()),
            .subeventsPerEvent =
                    static_cast<int8_t>(leCsProcedureEnableCompleteView.GetSubeventsPerEvent()),
            .subeventInterval = leCsProcedureEnableCompleteView.GetSubeventInterval(),
            .eventInterval = leCsProcedureEnableCompleteView.GetEventInterval(),
            .procedureInterval = leCsProcedureEnableCompleteView.GetProcedureInterval(),
            .procedureCount = leCsProcedureEnableCompleteView.GetProcedureCount(),
            // TODO(b/378942784): update the max procedure len, the current complete view does not
            // have it.
            .maxProcedureLen = 0,
    };

    it->second->GetSession()->updateProcedureEnableConfig(pConfig);
  }

  void WriteProcedureData(const uint16_t connection_handle, hci::CsRole local_cs_role,
                          const ProcedureDataV2& procedure_data,
                          uint16_t procedure_counter) override {
    auto session_it = session_trackers_.find(connection_handle);
    if (session_it == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return;
    } else if (session_it->second->GetSession() == nullptr) {
      log::error("Session not opened");
      return;
    }
    ChannelSoundingProcedureData channel_sounding_procedure_data;
    channel_sounding_procedure_data.procedureCounter = procedure_counter;
    channel_sounding_procedure_data.procedureSequence = procedure_data.procedure_sequence_;

    if (local_cs_role == hci::CsRole::INITIATOR) {
      channel_sounding_procedure_data.initiatorSelectedTxPower =
              static_cast<int8_t>(procedure_data.local_selected_tx_power_);
      channel_sounding_procedure_data.reflectorSelectedTxPower =
              static_cast<int8_t>(procedure_data.remote_selected_tx_power_);
      channel_sounding_procedure_data.initiatorProcedureAbortReason =
              static_cast<ProcedureAbortReason>(procedure_data.local_procedure_abort_reason_);
      channel_sounding_procedure_data.reflectorProcedureAbortReason =
              static_cast<ProcedureAbortReason>(procedure_data.remote_procedure_abort_reason_);
      channel_sounding_procedure_data.initiatorSubeventResultData =
              get_subevent_result_data(procedure_data.local_subevent_data_, hci::CsRole::INITIATOR);
      channel_sounding_procedure_data.reflectorSubeventResultData = get_subevent_result_data(
              procedure_data.remote_subevent_data_, hci::CsRole::REFLECTOR);
    } else {
      channel_sounding_procedure_data.initiatorSelectedTxPower =
              static_cast<int8_t>(procedure_data.remote_selected_tx_power_);
      channel_sounding_procedure_data.reflectorSelectedTxPower =
              static_cast<int8_t>(procedure_data.local_selected_tx_power_);
      channel_sounding_procedure_data.initiatorProcedureAbortReason =
              static_cast<ProcedureAbortReason>(procedure_data.remote_procedure_abort_reason_);
      channel_sounding_procedure_data.reflectorProcedureAbortReason =
              static_cast<ProcedureAbortReason>(procedure_data.local_procedure_abort_reason_);
      channel_sounding_procedure_data.initiatorSubeventResultData = get_subevent_result_data(
              procedure_data.remote_subevent_data_, hci::CsRole::INITIATOR);
      channel_sounding_procedure_data.reflectorSubeventResultData =
              get_subevent_result_data(procedure_data.local_subevent_data_, hci::CsRole::REFLECTOR);
    }
    session_it->second->GetSession()->writeProcedureData(channel_sounding_procedure_data);
  }

  static std::vector<SubeventResultData> get_subevent_result_data(
          const std::vector<std::shared_ptr<SubeventResult>>& subevent_results,
          hci::CsRole cs_role) {
    std::vector<SubeventResultData> hal_subevents;
    for (auto subevent_result : subevent_results) {
      SubeventResultData aidl_subevent_result{
              .startAclConnEventCounter = subevent_result->start_acl_conn_event_counter_,
              .frequencyCompensation = ConvertToSigned<kInitiatorMeasuredOffsetBits>(
                      subevent_result->frequency_compensation_),
              .referencePowerLevelDbm =
                      static_cast<int8_t>(subevent_result->reference_power_level_),
              .numAntennaPaths = static_cast<int8_t>(subevent_result->num_antenna_paths_),
              .subeventAbortReason =
                      static_cast<SubeventAbortReason>(subevent_result->subevent_abort_reason_),
              .stepData = get_group_step_data(subevent_result->step_data_, cs_role),
              .timestampNanos = subevent_result->timestamp_nanos_,
      };
      hal_subevents.push_back(aidl_subevent_result);
    }
    return hal_subevents;
  }

  static std::vector<StepData> get_group_step_data(
          const std::vector<StepSpecificData>& step_specific_data_list, hci::CsRole cs_role) {
    std::vector<StepData> group_step_data;
    for (auto step_specific_data : step_specific_data_list) {
      StepData step_data{
              .stepChannel = static_cast<int8_t>(step_specific_data.step_channel_),
              .stepMode = static_cast<ModeType>(step_specific_data.mode_type_),
      };
      get_step_mode_data(step_specific_data.mode_specific_data_, step_data.stepModeData, cs_role);
      group_step_data.push_back(step_data);
    }
    return group_step_data;
  }

  static void get_step_mode_data(
          std::variant<Mode0Data, Mode1Data, Mode2Data, Mode3Data> mode_specific_data,
          ModeData& mode_data, hci::CsRole cs_role) {
    if (std::holds_alternative<Mode0Data>(mode_specific_data)) {
      auto mode_0_data = std::get<Mode0Data>(mode_specific_data);
      ModeZeroData mode_zero_data{
              .packetQuality = static_cast<int8_t>(mode_0_data.packet_quality_),
              .packetRssiDbm = static_cast<int8_t>(mode_0_data.packet_rssi_),
              .packetAntenna = static_cast<int8_t>(mode_0_data.packet_antenna_),
      };
      mode_data = mode_zero_data;
      return;
    }
    if (std::holds_alternative<Mode1Data>(mode_specific_data)) {
      auto mode_1_data = std::get<Mode1Data>(mode_specific_data);
      mode_data = convert_mode_1_data(mode_1_data, cs_role);
      return;
    }
    if (std::holds_alternative<Mode2Data>(mode_specific_data)) {
      auto mode_2_data = std::get<Mode2Data>(mode_specific_data);
      mode_data = convert_mode_2_data(mode_2_data);
      return;
    }
    if (std::holds_alternative<Mode3Data>(mode_specific_data)) {
      auto mode_3_data = std::get<Mode3Data>(mode_specific_data);
      ModeThreeData mode_three_data{
              .modeOneData = convert_mode_1_data(mode_3_data.mode1_data_, cs_role),
              .modeTwoData = convert_mode_2_data(mode_3_data.mode2_data_),
      };
      mode_data = mode_three_data;
      return;
    }
  }

  static ModeOneData convert_mode_1_data(const Mode1Data& mode_1_data, hci::CsRole cs_role) {
    ModeOneData mode_one_data{
            .packetQuality = static_cast<int8_t>(mode_1_data.packet_quality_),
            .packetNadm = static_cast<Nadm>(mode_1_data.packet_nadm_),
            .packetRssiDbm = static_cast<int8_t>(mode_1_data.packet_rssi_),
            .packetAntenna = static_cast<int8_t>(mode_1_data.packet_antenna_),
    };
    if (cs_role == hci::CsRole::INITIATOR) {
      mode_one_data.rttToaTodData = RttToaTodData::make<RttToaTodData::Tag::toaTodInitiator>(
              static_cast<int16_t>(mode_1_data.rtt_toa_tod_data_));
    } else {
      mode_one_data.rttToaTodData = RttToaTodData::make<RttToaTodData::Tag::todToaReflector>(
              static_cast<int16_t>(mode_1_data.rtt_toa_tod_data_));
    }
    // TODO(b/378942784): once get 32 bits from controller, and check the unavailable data.
    if (mode_1_data.i_packet_pct1_.has_value()) {
      mode_one_data.packetPct1.emplace(
              ConvertToSigned<kIQSampleBits>(mode_1_data.i_packet_pct1_.value()),
              ConvertToSigned<kIQSampleBits>(mode_1_data.q_packet_pct1_.value()));
    }
    if (mode_1_data.i_packet_pct2_.has_value()) {
      mode_one_data.packetPct2.emplace(
              ConvertToSigned<kIQSampleBits>(mode_1_data.i_packet_pct2_.value()),
              ConvertToSigned<kIQSampleBits>(mode_1_data.q_packet_pct2_.value()));
    }
    return mode_one_data;
  }

  static ModeTwoData convert_mode_2_data(const Mode2Data& mode_2_data) {
    ModeTwoData mode_two_data{
            .antennaPermutationIndex = static_cast<int8_t>(mode_2_data.antenna_permutation_index_),
    };
    for (const auto& tone_data_with_quality : mode_2_data.tone_data_with_qualities_) {
      mode_two_data.toneQualityIndicators.emplace_back(
              tone_data_with_quality.tone_quality_indicator_);
      mode_two_data.tonePctIQSamples.emplace_back(
              ConvertToSigned<kIQSampleBits>(tone_data_with_quality.i_sample_),
              ConvertToSigned<kIQSampleBits>(tone_data_with_quality.q_sample_));
    }
    return mode_two_data;
  }

  void CopyVendorSpecificData(const std::vector<hal::VendorSpecificCharacteristic>& source,
                              std::optional<std::vector<std::optional<VendorSpecificData>>>& dist) {
    dist = std::make_optional<std::vector<std::optional<VendorSpecificData>>>();
    for (auto& data : source) {
      VendorSpecificData vendor_specific_data;
      vendor_specific_data.characteristicUuid = data.characteristicUuid_;
      vendor_specific_data.opaqueValue = data.value_;
      dist->push_back(vendor_specific_data);
    }
  }

  bool IsAbortedProcedureRequired(uint16_t connection_handle) override {
    auto it = session_trackers_.find(connection_handle);
    if (it == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return false;
    }
    if (it->second->GetSession() == nullptr) {
      log::error("Session not opened");
      return false;
    }
    bool isRequired = false;
    auto aidl_ret = it->second->GetSession()->isAbortedProcedureRequired(&isRequired);
    if (aidl_ret.isOk()) {
      return isRequired;
    }
    log::error("can not get result for isAbortedProcedureRequired.");
    return false;
  }

protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  RangingHalVersion get_ranging_hal_version() {
    int ver = 0;
    auto aidl_ret = bluetooth_channel_sounding_->getInterfaceVersion(&ver);
    if (aidl_ret.isOk()) {
      log::info("ranging HAL version - {}", ver);
      return static_cast<RangingHalVersion>(ver);
    }
    log::warn("ranging HAL version is not available.");
    return RangingHalVersion::V_UNKNOWN;
  }

  void Start() override {
    std::string instance = std::string() + IBluetoothChannelSounding::descriptor + "/default";
    log::info("AServiceManager_isDeclared {}", AServiceManager_isDeclared(instance.c_str()));
    if (AServiceManager_isDeclared(instance.c_str())) {
      ::ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
      bluetooth_channel_sounding_ = IBluetoothChannelSounding::fromBinder(binder);
      log::info("Bind IBluetoothChannelSounding {}", IsBound() ? "Success" : "Fail");
      if (bluetooth_channel_sounding_ != nullptr) {
        hal_ver_ = get_ranging_hal_version();
      }
    }
  }

  void Stop() override { bluetooth_channel_sounding_ = nullptr; }

  std::string ToString() const override { return std::string("RangingHalAndroid"); }

private:
  std::shared_ptr<IBluetoothChannelSounding> bluetooth_channel_sounding_;
  RangingHalCallback* ranging_hal_callback_;
  std::unordered_map<uint16_t, std::shared_ptr<BluetoothChannelSoundingSessionTracker>>
          session_trackers_;
  RangingHalVersion hal_ver_;
};

const ModuleFactory RangingHal::Factory = ModuleFactory([]() { return new RangingHalAndroid(); });

}  // namespace hal
}  // namespace bluetooth
