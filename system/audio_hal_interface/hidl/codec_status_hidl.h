/*
 * Copyright 2019 The Android Open Source Project
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

#include <android/hardware/bluetooth/audio/2.1/types.h>

#include <vector>

#include "audio_hal_interface/a2dp_encoding.h"
#include "stack/include/a2dp_codec_api.h"

namespace bluetooth::audio::hidl::codec {

using ::android::hardware::bluetooth::audio::V2_0::CodecConfiguration;
using ::android::hardware::bluetooth::audio::V2_0::PcmParameters;
using ::bluetooth::audio::a2dp::ahal_codec_configuration;

/// Configure the framework supported offload capabilities.
bool UpdateOffloadingCapabilities(
        const std::vector<btav_a2dp_codec_config_t>& framework_preference);

/// Return the pcm configuration to be used for the software encoding audio
/// session with the Bluetooth Audio HAL. Returns false if the
/// negotiated codec capabilities are not supported by the
/// hardware offload encoding session, true otherwise.
bool getHalPcmConfiguration(const ahal_codec_configuration& config,
                            PcmParameters* pcm_configuration);

/// Return the codec configuration to be used for the encoding audio session
/// with the Bluetooth Audio HAL. Return false if the
/// negotiated codec capabilities are not supported by the
/// hardware offload encoding session, true otherwise.
bool getHalCodecConfiguration(const ahal_codec_configuration& config,
                              CodecConfiguration* codec_configuration);

}  // namespace bluetooth::audio::hidl::codec
