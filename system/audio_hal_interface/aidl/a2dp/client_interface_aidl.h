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

#pragma once

#include <fmq/AidlMessageQueue.h>
#include <hardware/audio.h>

#include <ctime>
#include <mutex>
#include <vector>

#include "audio_aidl_interfaces.h"
#include "bluetooth_audio_port_impl.h"
#include "bta/le_audio/broadcaster/broadcaster_types.h"
#include "bta/le_audio/le_audio_types.h"

// Keep after audio_aidl_interfaces.h because of <base/logging.h>
// conflicting definitions.
#include "a2dp_aidl_transport.h"
#include "a2dp_encoding.h"

namespace bluetooth {
namespace audio {
namespace aidl {
namespace a2dp {

using ::aidl::android::hardware::bluetooth::audio::A2dpConfiguration;
using ::aidl::android::hardware::bluetooth::audio::A2dpConfigurationHint;
using ::aidl::android::hardware::bluetooth::audio::A2dpRemoteCapabilities;
using ::aidl::android::hardware::bluetooth::audio::A2dpStatus;
using ::aidl::android::hardware::bluetooth::audio::AudioCapabilities;
using ::aidl::android::hardware::bluetooth::audio::AudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::BluetoothAudioStatus;
using ::aidl::android::hardware::bluetooth::audio::CodecId;
using ::aidl::android::hardware::bluetooth::audio::CodecInfo;
using ::aidl::android::hardware::bluetooth::audio::CodecParameters;
using ::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv;
using ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv;
using ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioPort;
using ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider;
using ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProviderFactory;
using ::aidl::android::hardware::bluetooth::audio::LatencyMode;
using ::aidl::android::hardware::bluetooth::audio::MetadataLtv;
using ::aidl::android::hardware::bluetooth::audio::PcmConfiguration;
using ::aidl::android::hardware::bluetooth::audio::SessionType;

using ::aidl::android::hardware::common::fmq::MQDescriptor;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::android::AidlMessageQueue;

using ::bluetooth::audio::a2dp::Status;

using MqDataType = int8_t;
using MqDataMode = SynchronizedReadWrite;
using DataMQ = AidlMessageQueue<MqDataType, MqDataMode>;
using DataMQDesc = MQDescriptor<MqDataType, MqDataMode>;

inline BluetoothAudioStatus StatusToHalStatus(Status ack) {
  switch (ack) {
    case Status::SUCCESS:
      return BluetoothAudioStatus::SUCCESS;
    case Status::UNSUPPORTED_CODEC_CONFIGURATION:
      return BluetoothAudioStatus::UNSUPPORTED_CODEC_CONFIGURATION;
    case Status::RECONFIGURATION:
      return BluetoothAudioStatus::RECONFIGURATION;
    case Status::PENDING:
    case Status::FAILURE:
    default:
      return BluetoothAudioStatus::FAILURE;
  }
}

/***
 * The client interface connects an A2dpTransport to
 * IBluetoothAudioProvider and helps to route callbacks to
 * A2dpTransport
 ***/
class BluetoothAudioClientInterface {
public:
  BluetoothAudioClientInterface(SessionType sessionType, StreamCallbacks const* stream_callbacks);
  virtual ~BluetoothAudioClientInterface();

  bool IsValid() const;
  std::shared_ptr<A2dpTransport> GetTransportInstance() const { return transport_; }

  static std::vector<AudioCapabilities> GetAudioCapabilities(SessionType session_type);
  static std::optional<IBluetoothAudioProviderFactory::ProviderInfo> GetProviderInfo(
          SessionType session_type,
          std::shared_ptr<IBluetoothAudioProviderFactory> provider_factory = nullptr);

  std::optional<A2dpStatus> ParseA2dpConfiguration(const CodecId& codec_id,
                                                   const std::vector<uint8_t>& configuration,
                                                   CodecParameters* codec_parameters) const;

  std::optional<A2dpConfiguration> GetA2dpConfiguration(
          std::vector<A2dpRemoteCapabilities> const& remote_capabilities,
          A2dpConfigurationHint const& hint) const;

  void StreamStarted(const Status& ack);

  void StreamSuspended(const Status& ack);

  int StartSession();

  /***
   * Renew the connection and usually is used when aidl restarted
   ***/
  void RenewAudioProviderAndSession();

  int EndSession();

  bool UpdateAudioConfig(const AudioConfiguration& audioConfig);

  bool SetAllowedLatencyModes(std::vector<LatencyMode> latency_modes);

  /** Read PCM data from audio FMQ. */
  size_t ReadAudioData(uint8_t* p_buf, size_t len);
  size_t ReadAudioDataExact(uint8_t* p_buf, size_t len);

  /** Flush all the PCM data present in the audio FMQ. */
  void FlushAudioData();

  static constexpr PcmConfiguration kInvalidPcmConfiguration = {};

  static bool is_aidl_available();

protected:
  mutable std::mutex internal_mutex_;
  /***
   * Helper function to connect to an IBluetoothAudioProvider
   ***/
  void FetchAudioProvider();

  /***
   * Invoked when binder died
   ***/
  static void binderDiedCallbackAidl(void* cookie_ptr);

  std::shared_ptr<IBluetoothAudioProvider> provider_;
  std::shared_ptr<IBluetoothAudioProviderFactory> provider_factory_;

  bool session_started_;
  std::unique_ptr<DataMQ> data_mq_;

  static constexpr size_t kFmqBufferCapacity = 15360;

  uint8_t fmq_buffer_[kFmqBufferCapacity];
  size_t fmq_buffer_size_ = 0;

  ::ndk::ScopedAIBinder_DeathRecipient death_recipient_;
  // static constexpr const char* kDefaultAudioProviderFactoryInterface =
  //     "android.hardware.bluetooth.audio.IBluetoothAudioProviderFactory/default";
  static inline const std::string kDefaultAudioProviderFactoryInterface =
          std::string() + IBluetoothAudioProviderFactory::descriptor + "/default";

private:
  std::shared_ptr<A2dpTransport> transport_;
  std::vector<AudioCapabilities> capabilities_;
  std::vector<LatencyMode> latency_modes_;

  static constexpr int kDefaultDataReadTimeoutMs = 10;
  static constexpr int kDefaultDataReadPollIntervalMs = 1;
};

}  // namespace a2dp
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
