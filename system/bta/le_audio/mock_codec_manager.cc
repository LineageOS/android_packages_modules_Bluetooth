/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "mock_codec_manager.h"

#include "le_audio/codec_manager.h"
#include "le_audio/le_audio_types.h"

MockCodecManager* mock_codec_manager_pimpl_;
MockCodecManager* MockCodecManager::GetInstance() {
  bluetooth::le_audio::CodecManager::GetInstance();
  return mock_codec_manager_pimpl_;
}

namespace bluetooth::le_audio {

struct CodecManager::impl : public MockCodecManager {
public:
  impl() = default;
  ~impl() = default;
};

CodecManager::CodecManager() {}

types::CodecLocation CodecManager::GetCodecLocation() const {
  if (!pimpl_) {
    return types::CodecLocation::HOST;
  }
  return pimpl_->GetCodecLocation();
}

std::optional<ProviderInfo> CodecManager::GetCodecConfigProviderInfo(void) const {
  if (!pimpl_) {
    return std::nullopt;
  }
  return pimpl_->GetCodecConfigProviderInfo();
}

bool CodecManager::IsDualBiDirSwbSupported(void) const {
  if (!pimpl_) {
    return false;
  }

  return pimpl_->IsDualBiDirSwbSupported();
}

bool CodecManager::UpdateActiveUnicastAudioHalClient(
        LeAudioSourceAudioHalClient* source_unicast_client,
        LeAudioSinkAudioHalClient* sink_unicast_client, bool is_active) {
  if (pimpl_) {
    return pimpl_->UpdateActiveUnicastAudioHalClient(source_unicast_client, sink_unicast_client,
                                                     is_active);
  }
  return true;
}

bool CodecManager::UpdateActiveBroadcastAudioHalClient(
        LeAudioSourceAudioHalClient* source_broadcast_client, bool is_active) {
  if (pimpl_) {
    return pimpl_->UpdateActiveBroadcastAudioHalClient(source_broadcast_client, is_active);
  }
  return true;
}

void CodecManager::UpdateActiveAudioConfig(
        const types::BidirectionalPair<stream_parameters>& stream_params,
        std::function<void(const ::bluetooth::le_audio::stream_config& config, uint8_t direction)>
                update_receiver,
        uint8_t directions_to_update) {
  if (pimpl_) {
    return pimpl_->UpdateActiveAudioConfig(stream_params, update_receiver, directions_to_update);
  }
}

std::unique_ptr<types::AudioSetConfiguration> CodecManager::GetCodecConfig(
        const CodecManager::UnicastConfigurationRequirements& requirements,
        CodecManager::UnicastConfigurationProvider verifier) {
  if (!pimpl_) {
    return nullptr;
  }
  return pimpl_->GetCodecConfig(requirements, verifier);
}

std::unique_ptr<::bluetooth::le_audio::broadcaster::BroadcastConfiguration>
CodecManager::GetBroadcastConfig(
        const bluetooth::le_audio::CodecManager::BroadcastConfigurationRequirements& requirements)
        const {
  if (!pimpl_) {
    return std::unique_ptr<bluetooth::le_audio::broadcaster::BroadcastConfiguration>(nullptr);
  }
  return pimpl_->GetBroadcastConfig(requirements);
}

bool CodecManager::CheckCodecConfigIsBiDirSwb(
        const bluetooth::le_audio::types::AudioSetConfiguration& config) const {
  if (!pimpl_) {
    return false;
  }
  return pimpl_->CheckCodecConfigIsBiDirSwb(config);
}

bool CodecManager::CheckCodecConfigIsDualBiDirSwb(
        const bluetooth::le_audio::types::AudioSetConfiguration& config) const {
  if (!pimpl_) {
    return false;
  }
  return pimpl_->CheckCodecConfigIsDualBiDirSwb(config);
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
CodecManager::GetLocalAudioOutputCodecCapa() {
  if (!pimpl_) {
    return std::vector<bluetooth::le_audio::btle_audio_codec_config_t>{};
  }
  return pimpl_->GetLocalAudioOutputCodecCapa();
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
CodecManager::GetLocalAudioInputCodecCapa() {
  if (!pimpl_) {
    return std::vector<bluetooth::le_audio::btle_audio_codec_config_t>{};
  }
  return pimpl_->GetLocalAudioInputCodecCapa();
}

void CodecManager::UpdateBroadcastConnHandle(
        const std::vector<uint16_t>& conn_handle,
        std::function<void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
                update_receiver) {
  if (pimpl_) {
    return pimpl_->UpdateBroadcastConnHandle(conn_handle, update_receiver);
  }
}

void CodecManager::Start(const std::vector<bluetooth::le_audio::btle_audio_codec_config_t>&
                         /*offloading_preference*/) {
  // It is needed here as CodecManager which is a singleton creates it, but in
  // this mock we want to destroy and recreate the mock on each test case.
  if (!pimpl_) {
    pimpl_ = std::make_unique<testing::NiceMock<impl>>();
  }

  mock_codec_manager_pimpl_ = pimpl_.get();
  pimpl_->Start();
}

void CodecManager::Stop() {
  // It is needed here as CodecManager which is a singleton creates it, but in
  // this mock we want to destroy and recreate the mock on each test case.
  if (pimpl_) {
    pimpl_->Stop();
    pimpl_.reset();
  }

  mock_codec_manager_pimpl_ = nullptr;
}

bool CodecManager::UpdateCisConfiguration(const std::vector<struct types::cis>& cises,
                                          const stream_parameters& stream_params,
                                          uint8_t direction) {
  if (pimpl_) {
    return pimpl_->UpdateCisConfiguration(cises, stream_params, direction);
  }
  return false;
}

void CodecManager::ClearCisConfiguration(uint8_t direction) {
  if (pimpl_) {
    return pimpl_->ClearCisConfiguration(direction);
  }
}

bool CodecManager::IsUsingCodecExtensibility() const {
  if (pimpl_) {
    return pimpl_->IsUsingCodecExtensibility();
  }
  return false;
}

std::ostream& operator<<(std::ostream& os, const CodecManager::UnicastConfigurationRequirements&) {
  return os;
}

// CodecManager::~CodecManager() = default;

}  // namespace bluetooth::le_audio
