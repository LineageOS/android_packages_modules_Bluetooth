/*
 * Copyright 2021 The Android Open Source Project
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

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif

#include <functional>
#include <optional>

#include "bta/le_audio/codec_manager.h"
#include "bta/le_audio/le_audio_types.h"
#include "common/message_loop_thread.h"

namespace bluetooth {
namespace audio {
namespace le_audio {

using ::bluetooth::le_audio::DsaMode;
using ::bluetooth::le_audio::DsaModes;

enum class BluetoothRequest {
  RESUME = 0x00,
};

enum class BluetoothRequestState {
  IDLE = 0x00,
  PENDING_BEFORE_REQUEST,
  PENDING_AFTER_REQUEST,
  CONFIRMED,
  CANCELED,
};

constexpr uint8_t kChannelNumberMono = 1;
constexpr uint8_t kChannelNumberStereo = 2;

constexpr uint32_t kSampleRate384000 = 384000;
constexpr uint32_t kSampleRate192000 = 192000;
constexpr uint32_t kSampleRate176400 = 176400;
constexpr uint32_t kSampleRate96000 = 96000;
constexpr uint32_t kSampleRate88200 = 88200;
constexpr uint32_t kSampleRate48000 = 48000;
constexpr uint32_t kSampleRate44100 = 44100;
constexpr uint32_t kSampleRate32000 = 32000;
constexpr uint32_t kSampleRate24000 = 24000;
constexpr uint32_t kSampleRate22050 = 22050;
constexpr uint32_t kSampleRate16000 = 16000;
constexpr uint32_t kSampleRate11025 = 11025;
constexpr uint32_t kSampleRate8000 = 8000;

constexpr uint8_t kBitsPerSample16 = 16;
constexpr uint8_t kBitsPerSample24 = 24;
constexpr uint8_t kBitsPerSample32 = 32;

struct StreamCallbacks {
  std::function<bool(bool start_media_task)> on_resume_;
  std::function<bool(void)> on_suspend_;
  std::function<bool(const source_metadata_v7_t&, DsaMode)> on_metadata_update_;
  std::function<bool(const sink_metadata_v7_t&)> on_sink_metadata_update_;
};

struct OffloadCapabilities {
  std::vector<bluetooth::le_audio::types::AudioSetConfiguration> unicast_offload_capabilities;
  std::vector<bluetooth::le_audio::types::AudioSetConfiguration> broadcast_offload_capabilities;
};

OffloadCapabilities get_offload_capabilities();

class LeAudioClientInterface {
public:
  struct PcmParameters {
    uint32_t data_interval_us;
    uint32_t sample_rate;
    uint8_t bits_per_sample;
    uint8_t channels_count;
  };

private:
  class IClientInterfaceEndpoint {
  public:
    virtual ~IClientInterfaceEndpoint() = default;
    virtual void Cleanup() = 0;
    virtual void SetPcmParameters(const PcmParameters& params) = 0;
    virtual void SetRemoteDelay(uint16_t delay_report_ms) = 0;
    virtual void StartSession() = 0;
    virtual void StopSession() = 0;
    virtual void ConfirmStreamingRequest() = 0;
    virtual void CancelStreamingRequest() = 0;
    virtual void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) = 0;
    virtual void SetCodecPriority(const ::bluetooth::le_audio::types::LeAudioCodecId& codecId,
                                  int32_t priority) = 0;
    virtual void SuspendedForReconfiguration() = 0;
    virtual void ReconfigurationComplete() = 0;
    virtual void StreamSuspended() = 0;
  };

public:
  class Sink : public IClientInterfaceEndpoint {
  public:
    Sink(bool is_broadcaster = false) : is_broadcaster_(is_broadcaster) {}
    virtual ~Sink() = default;

    void Cleanup() override;
    void SetPcmParameters(const PcmParameters& params) override;
    void SetRemoteDelay(uint16_t delay_report_ms) override;
    void StartSession() override;
    void StopSession() override;
    void ConfirmStreamingRequest() override;
    void CancelStreamingRequest() override;
    void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) override;
    void SetCodecPriority(const ::bluetooth::le_audio::types::LeAudioCodecId& codecId,
                          int32_t priority) override;
    void UpdateBroadcastAudioConfigToHal(
            const ::bluetooth::le_audio::broadcast_offload_config& config);
    void SuspendedForReconfiguration() override;
    void ReconfigurationComplete() override;
    void StreamSuspended() override;
    // Read the stream of bytes sinked to us by the upper layers
    size_t Read(uint8_t* p_buf, uint32_t len);
    bool IsBroadcaster() { return is_broadcaster_; }
    std::optional<::bluetooth::le_audio::broadcaster::BroadcastConfiguration> GetBroadcastConfig(
            const std::vector<std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>&
                    subgroup_quality,
            const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs)
            const;
    std::optional<::bluetooth::le_audio::types::AudioSetConfiguration> GetUnicastConfig(
            const ::bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements&
                    requirements) const;

  private:
    bool is_broadcaster_ = false;
  };
  class Source : public IClientInterfaceEndpoint {
  public:
    Source(bool is_broadcast_sink = false) : is_broadcast_sink_(is_broadcast_sink) {}
    virtual ~Source() = default;

    void Cleanup() override;
    void SetPcmParameters(const PcmParameters& params) override;
    void SetRemoteDelay(uint16_t delay_report_ms) override;
    void StartSession() override;
    void StopSession() override;
    void ConfirmStreamingRequest() override;
    void CancelStreamingRequest() override;
    void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) override;
    void SetCodecPriority(const ::bluetooth::le_audio::types::LeAudioCodecId& codecId,
                          int32_t priority) override;
    void UpdateBroadcastAudioConfigToHal(
            const ::bluetooth::le_audio::broadcast_offload_config& config);
    void SuspendedForReconfiguration() override;
    void ReconfigurationComplete() override;
    void StreamSuspended() override;
    // Source the given stream of bytes to be sinked into the upper layers
    size_t Write(const uint8_t* p_buf, uint32_t len);
    bool IsBroadcastSink() { return is_broadcast_sink_; }
    std::optional<::bluetooth::le_audio::broadcaster::BroadcastConfiguration> GetBroadcastConfig(
            const std::vector<std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>&
                    subgroup_quality,
            const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs)
            const;

  private:
    bool is_broadcast_sink_ = false;
  };

  // Get LE Audio sink client interface if it's not previously acquired and not
  // yet released.
  Sink* GetSink(StreamCallbacks stream_cb, bluetooth::common::MessageLoopThread* message_loop,
                bool is_broadcasting_session_type);
  // This should be called before trying to get unicast sink interface
  bool IsUnicastSinkAcquired();
  // This should be called before trying to get broadcast sink interface
  bool IsBroadcastSinkAcquired();
  // Release sink interface if belongs to LE audio client interface
  bool ReleaseSink(Sink* sink);

  // Get LE Audio source client interface if it's not previously acquired and
  // not yet released.
  Source* GetSource(StreamCallbacks stream_cb, bluetooth::common::MessageLoopThread* message_loop,
                    bool is_broadcasting_session_type = false);
  // This should be called before trying to get unicast source interface
  bool IsUnicastSourceAcquired();
  // This should be called before trying to get broadcast source interface
  bool IsBroadcastSourceAcquired();
  // Release source interface if belongs to LE audio client interface
  bool ReleaseSource(Source* source);

  // Sets Dynamic Spatial Audio modes supported by the remote device
  void SetAllowedDsaModes(DsaModes dsa_modes);

  // Get interface, if previously not initialized - it'll initialize
  // singleton.
  static LeAudioClientInterface* Get();

  // Get the Codec Configuration Provider info
  std::optional<bluetooth::le_audio::ProviderInfo> GetCodecConfigProviderInfo(void) const;

private:
  static LeAudioClientInterface* interface;
  Sink* unicast_sink_ = nullptr;
  Sink* broadcast_sink_ = nullptr;
  Source* unicast_source_ = nullptr;
  Source* broadcast_source_ = nullptr;
};

}  // namespace le_audio
}  // namespace audio
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::audio::le_audio::BluetoothRequest>
    : enum_formatter<bluetooth::audio::le_audio::BluetoothRequest> {};
template <>
struct formatter<bluetooth::audio::le_audio::BluetoothRequestState>
    : enum_formatter<bluetooth::audio::le_audio::BluetoothRequestState> {};
}  // namespace std
