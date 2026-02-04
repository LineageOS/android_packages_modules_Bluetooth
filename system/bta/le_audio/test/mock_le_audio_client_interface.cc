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

#include "mock_le_audio_client_interface.h"

namespace bluetooth::audio::le_audio {

// Define the global mock pointer. Tests will set this to their mock instance.
MockLeAudioClientInterface* mock_le_audio_client_interface = nullptr;

// Fake implementations of LeAudioClientInterface methods that redirect to the
// global mock instance.
LeAudioClientInterface* LeAudioClientInterface::Get() { return mock_le_audio_client_interface; }

LeAudioClientInterface::Sink* LeAudioClientInterface::GetSink(
        StreamCallbacks cb, bluetooth::common::MessageLoopThread* loop, bool is_broadcasting) {
  if (mock_le_audio_client_interface) {
    return mock_le_audio_client_interface->GetSink(cb, loop, is_broadcasting);
  }
  return nullptr;
}

bool LeAudioClientInterface::ReleaseSink(LeAudioClientInterface::Sink* sink) {
  if (mock_le_audio_client_interface) {
    return mock_le_audio_client_interface->ReleaseSink(sink);
  }
  return false;
}

LeAudioClientInterface::Source* LeAudioClientInterface::GetSource(
        StreamCallbacks cb, bluetooth::common::MessageLoopThread* loop) {
  if (mock_le_audio_client_interface) {
    return mock_le_audio_client_interface->GetSource(cb, loop);
  }
  return nullptr;
}

bool LeAudioClientInterface::ReleaseSource(LeAudioClientInterface::Source* source) {
  if (mock_le_audio_client_interface) {
    return mock_le_audio_client_interface->ReleaseSource(source);
  }
  return false;
}

// Non-virtual methods and free functions must have a fake implementation to
// satisfy the linker.
std::optional<bluetooth::le_audio::ProviderInfo> LeAudioClientInterface::GetCodecConfigProviderInfo(
        void) const {
  return std::nullopt;
}

void LeAudioClientInterface::SetAllowedDsaModes(DsaModes) {}
OffloadCapabilities get_offload_capabilities() { return OffloadCapabilities{}; }

// Empty stub implementations for all other non-virtual/static methods to
// prevent linker errors in the test suite.
void LeAudioClientInterface::Sink::Cleanup() {}
void LeAudioClientInterface::Sink::SetPcmParameters(const PcmParameters&) {}
void LeAudioClientInterface::Sink::SetRemoteDelay(uint16_t) {}
void LeAudioClientInterface::Sink::StartSession() {}
void LeAudioClientInterface::Sink::StopSession() {}
void LeAudioClientInterface::Sink::ConfirmStreamingRequest() {}
void LeAudioClientInterface::Sink::CancelStreamingRequest() {}
void LeAudioClientInterface::Sink::StreamSuspended() {}
void LeAudioClientInterface::Sink::SetCodecPriority(
        const ::bluetooth::le_audio::types::LeAudioCodecId&, int32_t) {}
void LeAudioClientInterface::Sink::UpdateAudioConfigToHal(
        const ::bluetooth::le_audio::stream_config&) {}
void LeAudioClientInterface::Sink::UpdateBroadcastAudioConfigToHal(
        const ::bluetooth::le_audio::broadcast_offload_config&) {}
std::optional<::bluetooth::le_audio::broadcaster::BroadcastConfiguration>
LeAudioClientInterface::Sink::GetBroadcastConfig(
        const std::vector<std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>&,
        const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>&) const {
  return std::nullopt;
}
std::optional<::bluetooth::le_audio::types::AudioSetConfiguration>
LeAudioClientInterface::Sink::GetUnicastConfig(
        const ::bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements&) const {
  return std::nullopt;
}
void LeAudioClientInterface::Sink::SuspendedForReconfiguration() {}
void LeAudioClientInterface::Sink::ReconfigurationComplete() {}
void LeAudioClientInterface::Source::Cleanup() {}
void LeAudioClientInterface::Source::SetPcmParameters(const PcmParameters&) {}
void LeAudioClientInterface::Source::SetRemoteDelay(uint16_t) {}
void LeAudioClientInterface::Source::StartSession() {}
void LeAudioClientInterface::Source::StopSession() {}
void LeAudioClientInterface::Source::ConfirmStreamingRequest() {}
void LeAudioClientInterface::Source::CancelStreamingRequest() {}
void LeAudioClientInterface::Source::StreamSuspended() {}
void LeAudioClientInterface::Source::SetCodecPriority(
        const ::bluetooth::le_audio::types::LeAudioCodecId&, int32_t) {}
void LeAudioClientInterface::Source::UpdateAudioConfigToHal(
        const ::bluetooth::le_audio::stream_config&) {}
void LeAudioClientInterface::Source::SuspendedForReconfiguration() {}
void LeAudioClientInterface::Source::ReconfigurationComplete() {}
size_t LeAudioClientInterface::Source::Write(const uint8_t*, uint32_t) { return 0; }
size_t LeAudioClientInterface::Sink::Read(uint8_t*, uint32_t) { return 0; }
bool LeAudioClientInterface::IsUnicastSinkAcquired() { return false; }
bool LeAudioClientInterface::IsBroadcastSinkAcquired() { return false; }
bool LeAudioClientInterface::IsUnicastSourceAcquired() { return false; }

}  // namespace bluetooth::audio::le_audio
