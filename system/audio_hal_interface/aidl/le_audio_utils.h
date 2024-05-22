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

#pragma once

#include <hardware/audio.h>

#include <vector>

#include "audio_aidl_interfaces.h"
#include "bta/le_audio/broadcaster/broadcaster_types.h"
#include "bta/le_audio/codec_manager.h"
#include "bta/le_audio/le_audio_types.h"

namespace bluetooth {
namespace audio {
namespace aidl {

::aidl::android::hardware::bluetooth::audio::CodecId
GetAidlCodecIdFromStackFormat(
    const ::bluetooth::le_audio::types::LeAudioCodecId& codec_id);

::bluetooth::le_audio::types::LeAudioCodecId GetStackCodecIdFromAidlFormat(
    const ::aidl::android::hardware::bluetooth::audio::CodecId& codec_id);

std::optional<std::vector<
    std::optional<::aidl::android::hardware::bluetooth::audio::MetadataLtv>>>
GetAidlMetadataFromStackFormat(const std::vector<uint8_t>& vec);

bluetooth::le_audio::types::LeAudioLtvMap GetStackMetadataFromAidlFormat(
    const std::vector<std::optional<
        ::aidl::android::hardware::bluetooth::audio::MetadataLtv>>& source);

std::optional<std::vector<
    std::optional<::aidl::android::hardware::bluetooth::audio::
                      IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>
GetAidlLeAudioDeviceCapabilitiesFromStackFormat(
    const std::optional<
        std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs);

::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
    LeAudioBroadcastConfigurationRequirement
    GetAidlLeAudioBroadcastConfigurationRequirementFromStackFormat(
        const std::vector<std::pair<
            ::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>&
            subgroup_quality);

::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
    LeAudioConfigurationRequirement
    GetAidlLeAudioUnicastConfigurationRequirementsFromStackFormat(
        ::bluetooth::le_audio::types::LeAudioContextType context_type,
        const std::optional<std::vector<
            ::bluetooth::le_audio::CodecManager::
                UnicastConfigurationRequirements::DeviceDirectionRequirements>>&
            sink_reqs,
        const std::optional<std::vector<
            ::bluetooth::le_audio::CodecManager::
                UnicastConfigurationRequirements::DeviceDirectionRequirements>>&
            source_reqs);
::bluetooth::le_audio::types::LeAudioLtvMap GetStackLeAudioLtvMapFromAidlFormat(
    const std::vector<::aidl::android::hardware::bluetooth::audio::
                          CodecSpecificConfigurationLtv>& aidl_config_ltvs);

::bluetooth::le_audio::broadcaster::BroadcastSubgroupBisCodecConfig
GetStackBisConfigFromAidlFormat(
    const ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
        LeAudioSubgroupBisConfiguration& aidl_cfg,
    ::bluetooth::le_audio::types::LeAudioCodecId& out_codec_id);

std::vector<::bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig>
GetStackSubgroupsFromAidlFormat(
    const std::vector<
        ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
            LeAudioBroadcastSubgroupConfiguration>& aidl_subgroups);

struct ::bluetooth::le_audio::types::DataPathConfiguration
GetStackDataPathFromAidlFormat(
    const ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
        LeAudioDataPathConfiguration& dp);

std::optional<::bluetooth::le_audio::broadcaster::BroadcastConfiguration>
GetStackBroadcastConfigurationFromAidlFormat(
    const ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
        LeAudioBroadcastConfigurationSetting& setting);

std::optional<::bluetooth::le_audio::set_configurations::AudioSetConfiguration>
GetStackUnicastConfigurationFromAidlFormat(
    ::bluetooth::le_audio::types::LeAudioContextType ctx_type,
    const ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
        LeAudioAseConfigurationSetting& config);

}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
