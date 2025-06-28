/*
 * Copyright 2025 The Android Open Source Project
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

#include <cstdint>
#include <vector>

#include "a2dp_encoding.h"
#include "audio_aidl_interfaces.h"
#include "hardware/bt_av.h"

namespace bluetooth::audio::aidl::a2dp {

btav_a2dp_codec_channel_mode_t convertChannelMode(
        ::aidl::android::hardware::bluetooth::audio::ChannelMode channel_mode);

btav_a2dp_codec_channel_mode_t convertChannelMode(
        const std::vector<::aidl::android::hardware::bluetooth::audio::ChannelMode>& channel_mode);

btav_a2dp_codec_sample_rate_t convertSampleRate(int32_t sampling_frequency_hz);

btav_a2dp_codec_sample_rate_t convertSampleRate(const std::vector<int32_t>& sampling_frequency_hz);

btav_a2dp_codec_bits_per_sample_t convertBitsPerSample(int32_t bitdepth);

btav_a2dp_codec_bits_per_sample_t convertBitsPerSample(const std::vector<int32_t>& bitdepth);

bool convertCodecCapabilities(const ::aidl::android::hardware::bluetooth::audio::CodecId& codec_id,
                              const std::vector<uint8_t>& capabilities, uint8_t* codec_info);

::bluetooth::audio::a2dp::provider::a2dp_configuration convertA2dpConfiguration(
        const ::aidl::android::hardware::bluetooth::audio::A2dpConfiguration& config);

void convertCodecParameters(
        const ::aidl::android::hardware::bluetooth::audio::CodecParameters& aidl_params,
        btav_a2dp_codec_config_t* stack_params);

std::optional<::aidl::android::hardware::bluetooth::audio::CodecId> convertCodecId(
        bluetooth::a2dp::CodecId codec_id);

std::optional<bluetooth::a2dp::CodecId> convertCodecId(
        ::aidl::android::hardware::bluetooth::audio::CodecId codec_id);

std::optional<btav_a2dp_codec_info_t> convertCodecInfo(
        const ::aidl::android::hardware::bluetooth::audio::CodecInfo& codec_info);

}  // namespace bluetooth::audio::aidl::a2dp
