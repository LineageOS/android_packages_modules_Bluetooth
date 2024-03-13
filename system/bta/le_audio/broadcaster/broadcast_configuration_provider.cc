/*
 * Copyright 2024 The Android Open Source Project
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

#include "broadcast_configuration_provider.h"

#include "internal_include/stack_config.h"

namespace bluetooth::le_audio {
namespace broadcaster {
/* Software codec configuration provider */
BroadcastConfiguration GetBroadcastConfig(
    const std::vector<std::pair<types::LeAudioContextType, uint8_t>>&
        subgroup_quality) {
  // Select the SW codec parameters based on the first subgroup audio context
  // Note that the HW offloader may support more quality subgroups.
  // TODO: Unify the quality selection logic with GetBroadcastOffloadConfig()
  auto context = types::AudioContexts(subgroup_quality.at(0).first);

  const std::string* options =
      stack_config_get_interface()->get_pts_broadcast_audio_config_options();
  if (options) {
    if (!options->compare("lc3_stereo_48_1_2")) return lc3_stereo_48_1_2;
    if (!options->compare("lc3_stereo_48_2_2")) return lc3_stereo_48_2_2;
    if (!options->compare("lc3_stereo_48_3_2")) return lc3_stereo_48_3_2;
    if (!options->compare("lc3_stereo_48_4_2")) return lc3_stereo_48_4_2;
  }

  // High quality, Low Latency
  if (context.test_any(types::LeAudioContextType::GAME |
                       types::LeAudioContextType::LIVE))
    return lc3_stereo_24_2_1;

  // Standard quality, Low Latency
  if (context.test(types::LeAudioContextType::INSTRUCTIONAL))
    return lc3_mono_16_2_1;

  // Standard quality, High Reliability
  if (context.test_any(types::LeAudioContextType::SOUNDEFFECTS |
                       types::LeAudioContextType::UNSPECIFIED))
    return lc3_stereo_16_2_2;

  if (context.test_any(types::LeAudioContextType::ALERTS |
                       types::LeAudioContextType::NOTIFICATIONS |
                       types::LeAudioContextType::EMERGENCYALARM))
    return lc3_mono_16_2_2;

  // High quality, High Reliability
  if (context.test(types::LeAudioContextType::MEDIA)) return lc3_stereo_24_2_2;

  // Defaults: Standard quality, High Reliability
  return lc3_mono_16_2_2;
}
}  // namespace broadcaster
}  // namespace bluetooth::le_audio