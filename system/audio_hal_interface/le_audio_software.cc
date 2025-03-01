/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#define LOG_TAG "BTAudioClientLeAudioStub"

#include "le_audio_software.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <vector>

#include "aidl/android/hardware/bluetooth/audio/AudioContext.h"
#include "aidl/client_interface_aidl.h"
#include "aidl/le_audio_software_aidl.h"
#include "aidl/le_audio_utils.h"
#include "bta/le_audio/codec_manager.h"
#include "bta/le_audio/le_audio_types.h"
#include "hal_version_manager.h"
#include "hidl/le_audio_software_hidl.h"
#include "osi/include/properties.h"

namespace bluetooth {
namespace audio {

using aidl::BluetoothAudioClientInterface;
using aidl::GetAidlLeAudioBroadcastConfigurationRequirementFromStackFormat;
using aidl::GetAidlLeAudioDeviceCapabilitiesFromStackFormat;
using aidl::GetAidlLeAudioUnicastConfigurationRequirementsFromStackFormat;
using aidl::GetStackBroadcastConfigurationFromAidlFormat;
using aidl::GetStackProviderInfoFromAidl;
using aidl::GetStackUnicastConfigurationFromAidlFormat;

namespace le_audio {

namespace {

using ::android::hardware::bluetooth::audio::V2_1::PcmParameters;
using AudioConfiguration_2_1 = ::android::hardware::bluetooth::audio::V2_1::AudioConfiguration;
using AudioConfigurationAIDL = ::aidl::android::hardware::bluetooth::audio::AudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::AudioContext;
using ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider;
using ::aidl::android::hardware::bluetooth::audio::LatencyMode;
using ::aidl::android::hardware::bluetooth::audio::LeAudioCodecConfiguration;
using ::aidl::android::hardware::bluetooth::audio::SessionType;

using ::bluetooth::le_audio::CodecManager;
using ::bluetooth::le_audio::types::AudioSetConfiguration;
using ::bluetooth::le_audio::types::CodecLocation;
}  // namespace

OffloadCapabilities get_offload_capabilities() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return {std::vector<AudioSetConfiguration>(0), std::vector<AudioSetConfiguration>(0)};
  }
  return aidl::le_audio::get_offload_capabilities();
}

static aidl::BluetoothAudioSinkClientInterface* get_aidl_client_interface(bool is_broadcaster) {
  if (is_broadcaster) {
    return aidl::le_audio::LeAudioSinkTransport::interface_broadcast_;
  }

  return aidl::le_audio::LeAudioSinkTransport::interface_unicast_;
}

static aidl::le_audio::LeAudioSinkTransport* get_aidl_transport_instance(bool is_broadcaster) {
  if (is_broadcaster) {
    return aidl::le_audio::LeAudioSinkTransport::instance_broadcast_;
  }

  return aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
}

static bool is_aidl_offload_encoding_session(bool is_broadcaster) {
  return get_aidl_client_interface(is_broadcaster)->GetTransportInstance()->GetSessionType() ==
                 aidl::SessionType::LE_AUDIO_HARDWARE_OFFLOAD_ENCODING_DATAPATH ||
         get_aidl_client_interface(is_broadcaster)->GetTransportInstance()->GetSessionType() ==
                 aidl::SessionType::LE_AUDIO_BROADCAST_HARDWARE_OFFLOAD_ENCODING_DATAPATH;
}

LeAudioClientInterface* LeAudioClientInterface::interface = nullptr;
LeAudioClientInterface* LeAudioClientInterface::Get() {
  if (LeAudioClientInterface::interface == nullptr) {
    LeAudioClientInterface::interface = new LeAudioClientInterface();
  }

  return LeAudioClientInterface::interface;
}

void LeAudioClientInterface::Sink::Cleanup() {
  log::info("HAL transport: 0x{:02x}, is broadcast: {}",
            static_cast<int>(HalVersionManager::GetHalTransport()), is_broadcaster_);

  /* Cleanup transport interface and instance according to type and role */
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    if (hidl::le_audio::LeAudioSinkTransport::interface) {
      delete hidl::le_audio::LeAudioSinkTransport::interface;
      hidl::le_audio::LeAudioSinkTransport::interface = nullptr;
    }
    if (hidl::le_audio::LeAudioSinkTransport::instance) {
      delete hidl::le_audio::LeAudioSinkTransport::instance;
      hidl::le_audio::LeAudioSinkTransport::instance = nullptr;
    }
  } else if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    if (IsBroadcaster()) {
      if (aidl::le_audio::LeAudioSinkTransport::interface_broadcast_) {
        delete aidl::le_audio::LeAudioSinkTransport::interface_broadcast_;
        aidl::le_audio::LeAudioSinkTransport::interface_broadcast_ = nullptr;
      }
      if (aidl::le_audio::LeAudioSinkTransport::instance_broadcast_) {
        delete aidl::le_audio::LeAudioSinkTransport::instance_broadcast_;
        aidl::le_audio::LeAudioSinkTransport::instance_broadcast_ = nullptr;
      }
    } else {
      if (aidl::le_audio::LeAudioSinkTransport::interface_unicast_) {
        delete aidl::le_audio::LeAudioSinkTransport::interface_unicast_;
        aidl::le_audio::LeAudioSinkTransport::interface_unicast_ = nullptr;
      }
      if (aidl::le_audio::LeAudioSinkTransport::instance_unicast_) {
        delete aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
        aidl::le_audio::LeAudioSinkTransport::instance_unicast_ = nullptr;
      }
    }
  } else {
    log::error("Invalid HAL transport: 0x{:02x}",
               static_cast<int>(HalVersionManager::GetHalTransport()));
  }
}

void LeAudioClientInterface::Sink::SetPcmParameters(const PcmParameters& params) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::le_audio::LeAudioSinkTransport::instance->LeAudioSetSelectedHalPcmConfig(
            params.sample_rate, params.bits_per_sample, params.channels_count,
            params.data_interval_us);
  }
  return get_aidl_transport_instance(is_broadcaster_)
          ->LeAudioSetSelectedHalPcmConfig(params.sample_rate, params.bits_per_sample,
                                           params.channels_count, params.data_interval_us);
}

// Update Le Audio delay report to BluetoothAudio HAL
void LeAudioClientInterface::Sink::SetRemoteDelay(uint16_t delay_report_ms) {
  log::info("delay_report_ms={} ms", delay_report_ms);
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSinkTransport::instance->SetRemoteDelay(delay_report_ms);
    return;
  }
  get_aidl_transport_instance(is_broadcaster_)->SetRemoteDelay(delay_report_ms);
}

void LeAudioClientInterface::Sink::StartSession() {
  log::info("");
  if (HalVersionManager::GetHalVersion() == BluetoothAudioHalVersion::VERSION_2_1) {
    AudioConfiguration_2_1 audio_config;
    audio_config.pcmConfig(
            hidl::le_audio::LeAudioSinkTransport::instance->LeAudioGetSelectedHalPcmConfig());
    if (!hidl::le_audio::LeAudioSinkTransport::interface->UpdateAudioConfig_2_1(audio_config)) {
      log::error("cannot update audio config to HAL");
      return;
    }
    hidl::le_audio::LeAudioSinkTransport::interface->StartSession_2_1();
    return;
  } else if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    AudioConfigurationAIDL audio_config;
    if (is_aidl_offload_encoding_session(is_broadcaster_)) {
      if (is_broadcaster_) {
        audio_config.set<AudioConfigurationAIDL::leAudioBroadcastConfig>(
                get_aidl_transport_instance(is_broadcaster_)->LeAudioGetBroadcastConfig());
      } else {
        aidl::le_audio::LeAudioConfiguration le_audio_config = {};
        audio_config.set<AudioConfigurationAIDL::leAudioConfig>(le_audio_config);
      }
    } else {
      audio_config.set<AudioConfigurationAIDL::pcmConfig>(
              get_aidl_transport_instance(is_broadcaster_)->LeAudioGetSelectedHalPcmConfig());
    }
    if (!get_aidl_client_interface(is_broadcaster_)->UpdateAudioConfig(audio_config)) {
      log::error("cannot update audio config to HAL");
      return;
    }
    get_aidl_client_interface(is_broadcaster_)->StartSession();
  }
}

void LeAudioClientInterface::Sink::ConfirmStreamingRequest() {
  auto lambda =
          [&](StartRequestState currect_start_request_state) -> std::pair<StartRequestState, bool> {
    switch (currect_start_request_state) {
      case StartRequestState::IDLE:
        log::warn(", no pending start stream request");
        return std::make_pair(StartRequestState::IDLE, false);
      case StartRequestState::PENDING_BEFORE_RESUME:
        log::info("Response before sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::CONFIRMED, false);
      case StartRequestState::PENDING_AFTER_RESUME:
        log::info("Response after sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::IDLE, true);
      case StartRequestState::CONFIRMED:
      case StartRequestState::CANCELED:
        log::error("Invalid state, start stream already confirmed");
        return std::make_pair(currect_start_request_state, false);
    }
  };

  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    auto hidl_instance = hidl::le_audio::LeAudioSinkTransport::instance;
    if (hidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
      hidl::le_audio::LeAudioSinkTransport::interface->StreamStarted(
              hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
    }

    return;
  }

  auto aidl_instance = get_aidl_transport_instance(is_broadcaster_);
  if (aidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
    get_aidl_client_interface(is_broadcaster_)
            ->StreamStarted(aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
  }
}

void LeAudioClientInterface::Sink::CancelStreamingRequest() {
  auto lambda =
          [&](StartRequestState currect_start_request_state) -> std::pair<StartRequestState, bool> {
    switch (currect_start_request_state) {
      case StartRequestState::IDLE:
        log::warn(", no pending start stream request");
        return std::make_pair(StartRequestState::IDLE, false);
      case StartRequestState::PENDING_BEFORE_RESUME:
        log::info("Response before sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::CANCELED, false);
      case StartRequestState::PENDING_AFTER_RESUME:
        log::info("Response after sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::IDLE, true);
      case StartRequestState::CONFIRMED:
      case StartRequestState::CANCELED:
        log::error("Invalid state, start stream already confirmed");
        return std::make_pair(currect_start_request_state, false);
    }
  };

  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    auto hidl_instance = hidl::le_audio::LeAudioSinkTransport::instance;
    if (hidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
      hidl::le_audio::LeAudioSinkTransport::interface->StreamStarted(
              hidl::BluetoothAudioCtrlAck::FAILURE);
    }
    return;
  }

  auto aidl_instance = get_aidl_transport_instance(is_broadcaster_);
  if (aidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
    get_aidl_client_interface(is_broadcaster_)->StreamStarted(aidl::BluetoothAudioCtrlAck::FAILURE);
  }
}

void LeAudioClientInterface::Sink::StopSession() {
  log::info("sink");
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSinkTransport::instance->ClearStartRequestState();
    hidl::le_audio::LeAudioSinkTransport::interface->EndSession();
    return;
  }
  get_aidl_transport_instance(is_broadcaster_)->ClearStartRequestState();
  get_aidl_client_interface(is_broadcaster_)->EndSession();
}

static inline void dumpOffloadConfig(
        const char* msg, const ::bluetooth::audio::aidl::AudioConfiguration& offload_hal_config) {
  const auto offload_cfg_str = offload_hal_config.toString();

  constexpr size_t linelimit = 940;
  std::string_view str_view(offload_cfg_str);
  for (size_t offset = 0; offset < offload_cfg_str.length(); offset += linelimit) {
    log::debug("{} {}", offset ? "    > " : msg, str_view.substr(offset, linelimit));
  }
}

void LeAudioClientInterface::Sink::UpdateAudioConfigToHal(
        const ::bluetooth::le_audio::stream_config& offload_config) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return;
  }

  if (is_broadcaster_ || !is_aidl_offload_encoding_session(is_broadcaster_)) {
    return;
  }

  auto offload_hal_config = aidl::le_audio::stream_config_to_hal_audio_config(offload_config);
  dumpOffloadConfig("Encoding config:", offload_hal_config);

  get_aidl_client_interface(is_broadcaster_)->UpdateAudioConfig(offload_hal_config);
}

std::optional<::bluetooth::le_audio::broadcaster::BroadcastConfiguration>
LeAudioClientInterface::Sink::GetBroadcastConfig(
        const std::vector<std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>&
                subgroup_quality,
        const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs) const {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return std::nullopt;
  }

  if (!is_broadcaster_ || !is_aidl_offload_encoding_session(is_broadcaster_)) {
    return std::nullopt;
  }

  auto aidl_pacs = GetAidlLeAudioDeviceCapabilitiesFromStackFormat(pacs);
  auto reqs = GetAidlLeAudioBroadcastConfigurationRequirementFromStackFormat(subgroup_quality);

  log::assert_that(aidl::le_audio::LeAudioSinkTransport::interface_broadcast_ != nullptr,
                   "LeAudioSourceTransport::interface should not be null");
  auto aidl_broadcast_config = aidl::le_audio::LeAudioSinkTransport::interface_broadcast_
                                       ->getLeAudioBroadcastConfiguration(aidl_pacs, reqs);

  return GetStackBroadcastConfigurationFromAidlFormat(aidl_broadcast_config);
}

// This API is for requesting a single configuration.
// Note: We need a bulk API as well to get multiple configurations for caching
std::optional<::bluetooth::le_audio::types::AudioSetConfiguration>
LeAudioClientInterface::Sink::GetUnicastConfig(
        const ::bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements& requirements)
        const {
  log::debug("Requirements: {}", requirements);

  auto aidl_sink_pacs = GetAidlLeAudioDeviceCapabilitiesFromStackFormat(requirements.sink_pacs);

  auto aidl_source_pacs = GetAidlLeAudioDeviceCapabilitiesFromStackFormat(requirements.source_pacs);

  std::vector<IBluetoothAudioProvider::LeAudioConfigurationRequirement> reqs;
  reqs.push_back(GetAidlLeAudioUnicastConfigurationRequirementsFromStackFormat(
          requirements.audio_context_type, requirements.sink_requirements,
          requirements.source_requirements, requirements.flags));

  log::debug("Making an AIDL call");
  auto aidl_configs = get_aidl_client_interface(is_broadcaster_)
                              ->GetLeAudioAseConfiguration(aidl_sink_pacs, aidl_source_pacs, reqs);

  log::debug("Received {} configs", aidl_configs.size());

  if (aidl_configs.size() == 0) {
    log::error("Expecting a single configuration, but received none.");
    return std::nullopt;
  }

  /* Given a single requirement we should get a single response config
   * Note: For a bulk request we need to implement GetUnicastConfigs() method
   */
  if (aidl_configs.size() > 1) {
    log::warn("Expected a single configuration, but received {}", aidl_configs.size());
  }
  return GetStackUnicastConfigurationFromAidlFormat(requirements.audio_context_type,
                                                    aidl_configs.at(0));
}

void LeAudioClientInterface::Sink::UpdateBroadcastAudioConfigToHal(
        const ::bluetooth::le_audio::broadcast_offload_config& offload_config) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return;
  }

  if (!is_broadcaster_ || !is_aidl_offload_encoding_session(is_broadcaster_)) {
    return;
  }

  get_aidl_transport_instance(is_broadcaster_)->LeAudioSetBroadcastConfig(offload_config);
  get_aidl_client_interface(is_broadcaster_)
          ->UpdateAudioConfig(aidl::le_audio::broadcast_config_to_hal_audio_config(
                  get_aidl_transport_instance(is_broadcaster_)->LeAudioGetBroadcastConfig()));
}

void LeAudioClientInterface::Sink::SuspendedForReconfiguration() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSinkTransport::interface->StreamSuspended(
            hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
    return;
  }

  get_aidl_client_interface(is_broadcaster_)
          ->StreamSuspended(aidl::BluetoothAudioCtrlAck::SUCCESS_RECONFIGURATION);
}

void LeAudioClientInterface::Sink::ReconfigurationComplete() {
  // This is needed only for AIDL since SuspendedForReconfiguration()
  // already calls StreamSuspended(SUCCESS_FINISHED) for HIDL
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    // FIXME: For now we have to workaround the missing API and use
    //        StreamSuspended() with SUCCESS_FINISHED ack code.
    get_aidl_client_interface(is_broadcaster_)
            ->StreamSuspended(aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
  }
}

size_t LeAudioClientInterface::Sink::Read(uint8_t* p_buf, uint32_t len) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::le_audio::LeAudioSinkTransport::interface->ReadAudioData(p_buf, len);
  }
  return get_aidl_client_interface(is_broadcaster_)->ReadAudioData(p_buf, len);
}

void LeAudioClientInterface::Source::Cleanup() {
  log::info("source");
  if (hidl::le_audio::LeAudioSourceTransport::interface) {
    delete hidl::le_audio::LeAudioSourceTransport::interface;
    hidl::le_audio::LeAudioSourceTransport::interface = nullptr;
  }
  if (hidl::le_audio::LeAudioSourceTransport::instance) {
    delete hidl::le_audio::LeAudioSourceTransport::instance;
    hidl::le_audio::LeAudioSourceTransport::instance = nullptr;
  }
  if (aidl::le_audio::LeAudioSourceTransport::interface) {
    delete aidl::le_audio::LeAudioSourceTransport::interface;
    aidl::le_audio::LeAudioSourceTransport::interface = nullptr;
  }
  if (aidl::le_audio::LeAudioSourceTransport::instance) {
    delete aidl::le_audio::LeAudioSourceTransport::instance;
    aidl::le_audio::LeAudioSourceTransport::instance = nullptr;
  }
}

void LeAudioClientInterface::Source::SetPcmParameters(const PcmParameters& params) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSourceTransport::instance->LeAudioSetSelectedHalPcmConfig(
            params.sample_rate, params.bits_per_sample, params.channels_count,
            params.data_interval_us);
    return;
  }
  return aidl::le_audio::LeAudioSourceTransport::instance->LeAudioSetSelectedHalPcmConfig(
          params.sample_rate, params.bits_per_sample, params.channels_count,
          params.data_interval_us);
}

void LeAudioClientInterface::Source::SetRemoteDelay(uint16_t delay_report_ms) {
  log::info("delay_report_ms={} ms", delay_report_ms);
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSourceTransport::instance->SetRemoteDelay(delay_report_ms);
    return;
  }
  return aidl::le_audio::LeAudioSourceTransport::instance->SetRemoteDelay(delay_report_ms);
}

void LeAudioClientInterface::Source::StartSession() {
  log::info("");
  if (HalVersionManager::GetHalVersion() == BluetoothAudioHalVersion::VERSION_2_1) {
    AudioConfiguration_2_1 audio_config;
    audio_config.pcmConfig(
            hidl::le_audio::LeAudioSourceTransport::instance->LeAudioGetSelectedHalPcmConfig());
    if (!hidl::le_audio::LeAudioSourceTransport::interface->UpdateAudioConfig_2_1(audio_config)) {
      log::error("cannot update audio config to HAL");
      return;
    }
    hidl::le_audio::LeAudioSourceTransport::interface->StartSession_2_1();
    return;
  } else if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    AudioConfigurationAIDL audio_config;
    if (aidl::le_audio::LeAudioSourceTransport::interface->GetTransportInstance()
                ->GetSessionType() ==
        aidl::SessionType::LE_AUDIO_HARDWARE_OFFLOAD_DECODING_DATAPATH) {
      aidl::le_audio::LeAudioConfiguration le_audio_config;
      audio_config.set<AudioConfigurationAIDL::leAudioConfig>(
              aidl::le_audio::LeAudioConfiguration{});
    } else {
      audio_config.set<AudioConfigurationAIDL::pcmConfig>(
              aidl::le_audio::LeAudioSourceTransport::instance->LeAudioGetSelectedHalPcmConfig());
    }

    if (!aidl::le_audio::LeAudioSourceTransport::interface->UpdateAudioConfig(audio_config)) {
      log::error("cannot update audio config to HAL");
      return;
    }
    aidl::le_audio::LeAudioSourceTransport::interface->StartSession();
  }
}

void LeAudioClientInterface::Source::SuspendedForReconfiguration() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSourceTransport::interface->StreamSuspended(
            hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
    return;
  }

  aidl::le_audio::LeAudioSourceTransport::interface->StreamSuspended(
          aidl::BluetoothAudioCtrlAck::SUCCESS_RECONFIGURATION);
}

void LeAudioClientInterface::Source::ReconfigurationComplete() {
  // This is needed only for AIDL since SuspendedForReconfiguration()
  // already calls StreamSuspended(SUCCESS_FINISHED) for HIDL
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    // FIXME: For now we have to workaround the missing API and use
    //        StreamSuspended() with SUCCESS_FINISHED ack code.
    aidl::le_audio::LeAudioSourceTransport::interface->StreamSuspended(
            aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
  }
}

void LeAudioClientInterface::Source::ConfirmStreamingRequest() {
  auto lambda =
          [&](StartRequestState currect_start_request_state) -> std::pair<StartRequestState, bool> {
    switch (currect_start_request_state) {
      case StartRequestState::IDLE:
        log::warn(", no pending start stream request");
        return std::make_pair(StartRequestState::IDLE, false);
      case StartRequestState::PENDING_BEFORE_RESUME:
        log::info("Response before sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::CONFIRMED, false);
      case StartRequestState::PENDING_AFTER_RESUME:
        log::info("Response after sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::IDLE, true);
      case StartRequestState::CONFIRMED:
      case StartRequestState::CANCELED:
        log::error("Invalid state, start stream already confirmed");
        return std::make_pair(currect_start_request_state, false);
    }
  };

  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    auto hidl_instance = hidl::le_audio::LeAudioSourceTransport::instance;

    if (hidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
      hidl::le_audio::LeAudioSourceTransport::interface->StreamStarted(
              hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
    }
    return;
  }

  auto aidl_instance = aidl::le_audio::LeAudioSourceTransport::instance;
  if (aidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
    aidl::le_audio::LeAudioSourceTransport::interface->StreamStarted(
            aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED);
  }
}

void LeAudioClientInterface::Source::CancelStreamingRequest() {
  auto lambda =
          [&](StartRequestState currect_start_request_state) -> std::pair<StartRequestState, bool> {
    switch (currect_start_request_state) {
      case StartRequestState::IDLE:
        log::warn(", no pending start stream request");
        return std::make_pair(StartRequestState::IDLE, false);
      case StartRequestState::PENDING_BEFORE_RESUME:
        log::info("Response before sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::CANCELED, false);
      case StartRequestState::PENDING_AFTER_RESUME:
        log::info("Response after sending PENDING to audio HAL");
        return std::make_pair(StartRequestState::IDLE, true);
      case StartRequestState::CONFIRMED:
      case StartRequestState::CANCELED:
        log::error("Invalid state, start stream already confirmed");
        return std::make_pair(currect_start_request_state, false);
    }
  };

  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    auto hidl_instance = hidl::le_audio::LeAudioSourceTransport::instance;
    if (hidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
      hidl::le_audio::LeAudioSourceTransport::interface->StreamStarted(
              hidl::BluetoothAudioCtrlAck::FAILURE);
    }
    return;
  }

  auto aidl_instance = aidl::le_audio::LeAudioSourceTransport::instance;
  if (aidl_instance->IsRequestCompletedAfterUpdate(lambda)) {
    aidl::le_audio::LeAudioSourceTransport::interface->StreamStarted(
            aidl::BluetoothAudioCtrlAck::FAILURE);
  }
}

void LeAudioClientInterface::Source::StopSession() {
  log::info("source");
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::le_audio::LeAudioSourceTransport::instance->ClearStartRequestState();
    hidl::le_audio::LeAudioSourceTransport::interface->EndSession();
    return;
  }
  aidl::le_audio::LeAudioSourceTransport::instance->ClearStartRequestState();
  aidl::le_audio::LeAudioSourceTransport::interface->EndSession();
}

void LeAudioClientInterface::Source::UpdateAudioConfigToHal(
        const ::bluetooth::le_audio::stream_config& offload_config) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return;
  }

  if (aidl::le_audio::LeAudioSourceTransport::interface->GetTransportInstance()->GetSessionType() !=
      aidl::SessionType::LE_AUDIO_HARDWARE_OFFLOAD_DECODING_DATAPATH) {
    return;
  }

  auto offload_hal_config = aidl::le_audio::stream_config_to_hal_audio_config(offload_config);
  dumpOffloadConfig("Decoding config:", offload_hal_config);

  aidl::le_audio::LeAudioSourceTransport::interface->UpdateAudioConfig(offload_hal_config);
}

size_t LeAudioClientInterface::Source::Write(const uint8_t* p_buf, uint32_t len) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::le_audio::LeAudioSourceTransport::interface->WriteAudioData(p_buf, len);
  }
  return aidl::le_audio::LeAudioSourceTransport::interface->WriteAudioData(p_buf, len);
}

LeAudioClientInterface::Sink* LeAudioClientInterface::GetSink(
        StreamCallbacks stream_cb, bluetooth::common::MessageLoopThread* message_loop,
        bool is_broadcasting_session_type) {
  if (is_broadcasting_session_type &&
      HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    log::warn("No support for broadcasting Le Audio on HIDL");
    return nullptr;
  }

  auto& sink = is_broadcasting_session_type ? broadcast_sink_ : unicast_sink_;
  if (sink == nullptr) {
    sink = new Sink(is_broadcasting_session_type);
  } else {
    log::warn("Sink is already acquired");
    return nullptr;
  }

  log::info("");

  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::SessionType_2_1 session_type = hidl::SessionType_2_1::LE_AUDIO_SOFTWARE_ENCODING_DATAPATH;

    hidl::le_audio::LeAudioSinkTransport::instance =
            new hidl::le_audio::LeAudioSinkTransport(session_type, std::move(stream_cb));
    hidl::le_audio::LeAudioSinkTransport::interface = new hidl::BluetoothAudioSinkClientInterface(
            hidl::le_audio::LeAudioSinkTransport::instance, message_loop);
    if (!hidl::le_audio::LeAudioSinkTransport::interface->IsValid()) {
      log::warn("BluetoothAudio HAL for Le Audio is invalid?!");
      delete hidl::le_audio::LeAudioSinkTransport::interface;
      hidl::le_audio::LeAudioSinkTransport::interface = nullptr;
      delete hidl::le_audio::LeAudioSinkTransport::instance;
      hidl::le_audio::LeAudioSinkTransport::instance = nullptr;
      delete sink;
      sink = nullptr;

      return nullptr;
    }
  } else {
    aidl::SessionType session_type =
            is_broadcasting_session_type
                    ? aidl::SessionType::LE_AUDIO_BROADCAST_SOFTWARE_ENCODING_DATAPATH
                    : aidl::SessionType::LE_AUDIO_SOFTWARE_ENCODING_DATAPATH;
    if (CodecManager::GetInstance()->GetCodecLocation() != CodecLocation::HOST) {
      session_type =
              is_broadcasting_session_type
                      ? aidl::SessionType::LE_AUDIO_BROADCAST_HARDWARE_OFFLOAD_ENCODING_DATAPATH
                      : aidl::SessionType::LE_AUDIO_HARDWARE_OFFLOAD_ENCODING_DATAPATH;
    }

    if (session_type == aidl::SessionType::LE_AUDIO_HARDWARE_OFFLOAD_ENCODING_DATAPATH ||
        session_type == aidl::SessionType::LE_AUDIO_SOFTWARE_ENCODING_DATAPATH) {
      aidl::le_audio::LeAudioSinkTransport::instance_unicast_ =
              new aidl::le_audio::LeAudioSinkTransport(session_type, std::move(stream_cb));
      aidl::le_audio::LeAudioSinkTransport::interface_unicast_ =
              new aidl::BluetoothAudioSinkClientInterface(
                      aidl::le_audio::LeAudioSinkTransport::instance_unicast_);
      if (!aidl::le_audio::LeAudioSinkTransport::interface_unicast_->IsValid()) {
        log::warn("BluetoothAudio HAL for Le Audio is invalid?!");
        delete aidl::le_audio::LeAudioSinkTransport::interface_unicast_;
        aidl::le_audio::LeAudioSinkTransport::interface_unicast_ = nullptr;
        delete aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
        aidl::le_audio::LeAudioSinkTransport::instance_unicast_ = nullptr;
        delete sink;
        sink = nullptr;

        return nullptr;
      }
    } else {
      aidl::le_audio::LeAudioSinkTransport::instance_broadcast_ =
              new aidl::le_audio::LeAudioSinkTransport(session_type, std::move(stream_cb));
      aidl::le_audio::LeAudioSinkTransport::interface_broadcast_ =
              new aidl::BluetoothAudioSinkClientInterface(
                      aidl::le_audio::LeAudioSinkTransport::instance_broadcast_);
      if (!aidl::le_audio::LeAudioSinkTransport::interface_broadcast_->IsValid()) {
        log::warn("BluetoothAudio HAL for Le Audio is invalid?!");
        delete aidl::le_audio::LeAudioSinkTransport::interface_broadcast_;
        aidl::le_audio::LeAudioSinkTransport::interface_broadcast_ = nullptr;
        delete aidl::le_audio::LeAudioSinkTransport::instance_broadcast_;
        aidl::le_audio::LeAudioSinkTransport::instance_broadcast_ = nullptr;
        delete sink;
        sink = nullptr;

        return nullptr;
      }
    }
  }

  return sink;
}

bool LeAudioClientInterface::IsUnicastSinkAcquired() { return unicast_sink_ != nullptr; }
bool LeAudioClientInterface::IsBroadcastSinkAcquired() { return broadcast_sink_ != nullptr; }

bool LeAudioClientInterface::ReleaseSink(LeAudioClientInterface::Sink* sink) {
  if (sink != unicast_sink_ && sink != broadcast_sink_) {
    log::warn("can't release not acquired sink");
    return false;
  }

  if ((hidl::le_audio::LeAudioSinkTransport::interface &&
       hidl::le_audio::LeAudioSinkTransport::instance) ||
      (aidl::le_audio::LeAudioSinkTransport::interface_unicast_ &&
       aidl::le_audio::LeAudioSinkTransport::instance_unicast_) ||
      (aidl::le_audio::LeAudioSinkTransport::interface_broadcast_ &&
       aidl::le_audio::LeAudioSinkTransport::instance_broadcast_)) {
    sink->Cleanup();
  }

  if (sink == unicast_sink_) {
    delete (unicast_sink_);
    unicast_sink_ = nullptr;
  } else if (sink == broadcast_sink_) {
    delete (broadcast_sink_);
    broadcast_sink_ = nullptr;
  }

  return true;
}

LeAudioClientInterface::Source* LeAudioClientInterface::GetSource(
        StreamCallbacks stream_cb, bluetooth::common::MessageLoopThread* message_loop) {
  if (source_ == nullptr) {
    source_ = new Source();
  } else {
    log::warn("Source is already acquired");
    return nullptr;
  }

  log::info("");

  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::SessionType_2_1 session_type = hidl::SessionType_2_1::LE_AUDIO_SOFTWARE_DECODED_DATAPATH;
    if (CodecManager::GetInstance()->GetCodecLocation() != CodecLocation::HOST) {
      session_type = hidl::SessionType_2_1::LE_AUDIO_HARDWARE_OFFLOAD_DECODING_DATAPATH;
    }

    hidl::le_audio::LeAudioSourceTransport::instance =
            new hidl::le_audio::LeAudioSourceTransport(session_type, std::move(stream_cb));
    hidl::le_audio::LeAudioSourceTransport::interface =
            new hidl::BluetoothAudioSourceClientInterface(
                    hidl::le_audio::LeAudioSourceTransport::instance, message_loop);
    if (!hidl::le_audio::LeAudioSourceTransport::interface->IsValid()) {
      log::warn("BluetoothAudio HAL for Le Audio is invalid?!");
      delete hidl::le_audio::LeAudioSourceTransport::interface;
      hidl::le_audio::LeAudioSourceTransport::interface = nullptr;
      delete hidl::le_audio::LeAudioSourceTransport::instance;
      hidl::le_audio::LeAudioSourceTransport::instance = nullptr;
      delete source_;
      source_ = nullptr;

      return nullptr;
    }
  } else {
    aidl::SessionType session_type = aidl::SessionType::LE_AUDIO_SOFTWARE_DECODING_DATAPATH;
    if (CodecManager::GetInstance()->GetCodecLocation() != CodecLocation::HOST) {
      session_type = aidl::SessionType::LE_AUDIO_HARDWARE_OFFLOAD_DECODING_DATAPATH;
    }

    aidl::le_audio::LeAudioSourceTransport::instance =
            new aidl::le_audio::LeAudioSourceTransport(session_type, std::move(stream_cb));
    aidl::le_audio::LeAudioSourceTransport::interface =
            new aidl::BluetoothAudioSourceClientInterface(
                    aidl::le_audio::LeAudioSourceTransport::instance);
    if (!aidl::le_audio::LeAudioSourceTransport::interface->IsValid()) {
      log::warn("BluetoothAudio HAL for Le Audio is invalid?!");
      delete aidl::le_audio::LeAudioSourceTransport::interface;
      aidl::le_audio::LeAudioSourceTransport::interface = nullptr;
      delete aidl::le_audio::LeAudioSourceTransport::instance;
      aidl::le_audio::LeAudioSourceTransport::instance = nullptr;
      delete source_;
      source_ = nullptr;

      return nullptr;
    }
  }

  return source_;
}

bool LeAudioClientInterface::IsSourceAcquired() { return source_ != nullptr; }

bool LeAudioClientInterface::ReleaseSource(LeAudioClientInterface::Source* source) {
  if (source != source_) {
    log::warn("can't release not acquired source");
    return false;
  }

  if ((hidl::le_audio::LeAudioSourceTransport::interface &&
       hidl::le_audio::LeAudioSourceTransport::instance) ||
      (aidl::le_audio::LeAudioSourceTransport::interface &&
       aidl::le_audio::LeAudioSourceTransport::instance)) {
    source->Cleanup();
  }

  delete (source_);
  source_ = nullptr;

  return true;
}

void LeAudioClientInterface::SetAllowedDsaModes(DsaModes dsa_modes) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    if (aidl::le_audio::LeAudioSinkTransport::interface_unicast_ == nullptr ||
        aidl::le_audio::LeAudioSinkTransport::instance_unicast_ == nullptr) {
      log::warn("LeAudioSourceTransport::interface is null");
      return;
    }

    std::vector<LatencyMode> latency_modes = {LatencyMode::FREE};
    for (auto dsa_mode : dsa_modes) {
      switch (dsa_mode) {
        case DsaMode::DISABLED:
          // Already added
          break;
        case DsaMode::ACL:
          latency_modes.push_back(LatencyMode::LOW_LATENCY);
          break;
        case DsaMode::ISO_SW:
          latency_modes.push_back(LatencyMode::DYNAMIC_SPATIAL_AUDIO_SOFTWARE);
          break;
        case DsaMode::ISO_HW:
          latency_modes.push_back(LatencyMode::DYNAMIC_SPATIAL_AUDIO_HARDWARE);
          break;
        default:
          log::warn("Unsupported latency mode ignored: {}", (int)dsa_mode);
          break;
      }
    }
    aidl::le_audio::LeAudioSinkTransport::interface_unicast_->SetAllowedLatencyModes(latency_modes);
  }
}

std::optional<bluetooth::le_audio::ProviderInfo> LeAudioClientInterface::GetCodecConfigProviderInfo(
        void) const {
  if (HalVersionManager::GetHalTransport() != BluetoothAudioHalTransport::AIDL) {
    log::error("Not using an AIDL HAL transport. Provider Info is not available.");
    return std::nullopt;
  }

  auto encoding_provider_info = BluetoothAudioClientInterface::GetProviderInfo(
          SessionType::LE_AUDIO_HARDWARE_OFFLOAD_ENCODING_DATAPATH, nullptr);

  auto decoding_provider_info = BluetoothAudioClientInterface::GetProviderInfo(
          SessionType::LE_AUDIO_HARDWARE_OFFLOAD_DECODING_DATAPATH, nullptr);

  if (!encoding_provider_info.has_value() && !decoding_provider_info.has_value()) {
    log::info("LE Audio offload codec extensibility is enabled, but the provider info is empty");
    return std::nullopt;
  }

  return GetStackProviderInfoFromAidl(encoding_provider_info, decoding_provider_info);
}
}  // namespace le_audio
}  // namespace audio
}  // namespace bluetooth
