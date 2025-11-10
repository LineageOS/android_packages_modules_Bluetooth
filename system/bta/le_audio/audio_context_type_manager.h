/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>

#include "device_groups.h"
#include "le_audio_types.h"

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif  // TARGET_FLOSS

namespace bluetooth::le_audio {
class AudioContextTypeManager {
public:
  static void Cleanup(void);
  static std::shared_ptr<AudioContextTypeManager> Get(void);
  static void DebugDump(int fd);

  virtual ~AudioContextTypeManager(void) = default;

  /* Checks if any metadata has been set.
   * If not then calling GetAudioContextsForTheGroup will return empty contexts */
  virtual bool IsAnyMetadataSet(
          uint8_t local_directions = bluetooth::le_audio::types::kLeAudioDirectionBoth) = 0;

  /* Set encoding session metadata from Audio Framework */
  virtual void SetEncodingSessionMetadata(
          const std::vector<struct playback_track_metadata_v7>& source_metadata) = 0;
  /* Set encoding session metadata from Audio Framework */
  virtual void SetDecodingSessionMetadata(
          const std::vector<record_track_metadata_v7>& sink_metadata) = 0;
  /* Clears all the previous metadata and replace it with the one provided */
  virtual void OverrideContextTypes(
          const types::BidirectionalPair<types::AudioContexts>& local_context_types) = 0;

  /* Provide API to set and get call state. */
  virtual void SetInCall(bool in_call) = 0;
  virtual bool IsInCall(void) = 0;

  virtual void SetInGame(bool in_game) = 0;
  virtual bool IsInGame(void) = 0;

  /* Get the VOIP call state based on the provided metadata */
  virtual bool IsInVoip(void) = 0;

  /* Get remote audio context types based on the session metadata
   * and supported/available contexts in the group along with the configuration context type.
   * The configuration context type is the use case context type based on the Audio Hal metadata.
   */
  virtual std::pair<types::LeAudioContextType, types::BidirectionalPair<types::AudioContexts>>
  GetAudioContextsForTheGroup(
          const LeAudioDeviceGroup* group,
          uint8_t remote_directions = bluetooth::le_audio::types::kLeAudioDirectionBoth) = 0;

  /* This returns remote directions availability for given context.
   * Note: this should be used only when Audio Framework did not set any metadata.
   * When metadata is set, then GetAudioContextsForTheGroup() should be used instead.
   */
  virtual types::BidirectionalPair<bool> GetDirectionsForGivenContext(
          types::LeAudioContextType context_type, const LeAudioDeviceGroup* group) = 0;
};
}  // namespace bluetooth::le_audio
