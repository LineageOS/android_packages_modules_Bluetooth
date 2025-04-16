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

#include "audio_context_type_manager.h"

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>

#include <mutex>
#include <utility>
#include <vector>

#include "common/strings.h"
#include "le_audio_utils.h"

using bluetooth::common::ToString;

using bluetooth::le_audio::AudioContextTypeManager;
using bluetooth::le_audio::LeAudioDeviceGroup;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::BidirectionalPair;
using bluetooth::le_audio::types::kLeAudioDirectionSink;
using bluetooth::le_audio::types::kLeAudioDirectionSource;
using bluetooth::le_audio::types::LeAudioContextType;
using bluetooth::le_audio::utils::AudioContentToLeAudioContext;
using bluetooth::le_audio::utils::audioSourceToStr;
using bluetooth::le_audio::utils::contentTypeToString;
using bluetooth::le_audio::utils::isMetadataTagPresent;
using bluetooth::le_audio::utils::usageToString;

namespace {
using namespace bluetooth;

class AudioContextTypeManagerImpl;
std::shared_ptr<AudioContextTypeManagerImpl> instance;
std::mutex instance_mutex;

class AudioContextTypeManagerImpl : public AudioContextTypeManager {
public:
  AudioContextTypeManagerImpl(void) {
    local_encoding_contexts_types_ = {AudioContexts(), AudioContexts()};
    local_decoding_context_types_ = AudioContexts();
  }

  void SetEncodingSessionMetadata(
          const std::vector<struct playback_track_metadata_v7>& encoding_metadata) {
    /* From encoding metadata it is possible to figure out metadata for both directions. */
    local_encoding_contexts_types_.sink.clear();
    local_encoding_contexts_types_.source.clear();

    if (encoding_metadata.empty()) {
      log::verbose("Clear encoding metadata");
      return;
    }

    for (const auto& entry : encoding_metadata) {
      auto track = entry.base;
      if (track.content_type == 0 && track.usage == 0) {
        log::debug("Empty metadata...skip");
        continue;
      }

      log::info("usage={}({}), content_type={}({}), gain={:f}, tag:{}", usageToString(track.usage),
                track.usage, contentTypeToString(track.content_type), track.content_type,
                track.gain, entry.tags);

      auto context_type = AudioContentToLeAudioContext(track.content_type, track.usage);
      if (isMetadataTagPresent(entry.tags, "VX_AOSP_SAMPLESOUND")) {
        context_type = LeAudioContextType::SOUNDEFFECTS;
      }

      local_encoding_contexts_types_.source.set(context_type);

      if (bluetooth::le_audio::types::kLeAudioContextAllBidir.test(context_type)) {
        /* Some of the bidirectional context needs to be allowed also by Audio Framework */
        if (!isBidirectionalControlledByAudioFramework(context_type) ||
            isMetadataTagPresent(entry.tags, "bidirectional")) {
          local_encoding_contexts_types_.sink.set(context_type);
        }
      }
    }

    updateVoipState();

    printCurrentState("SetEncodingSession:");
  }

  void SetDecodingSessionMetadata(const std::vector<record_track_metadata_v7>& sink_metadata) {
    local_decoding_context_types_.clear();

    if (sink_metadata.empty()) {
      log::verbose("Clear decoding metadata.");
      return;
    }

    for (const auto& entry : sink_metadata) {
      auto track = entry.base;
      if (track.source == AUDIO_SOURCE_INVALID) {
        log::debug("AUDIO_SOURCE_INVALID");
        continue;
      }

      LeAudioContextType track_context;

      log::debug(
              "source={}(0x{:02x}), gain={:f}, destination device=0x{:08x}, "
              "destination device address={:32s}",
              audioSourceToStr(track.source), track.source, track.gain, track.dest_device,
              track.dest_device_address);

      if (track.source == AUDIO_SOURCE_MIC) {
        track_context = LeAudioContextType::LIVE;
      } else if (track.source == AUDIO_SOURCE_VOICE_COMMUNICATION) {
        track_context = LeAudioContextType::CONVERSATIONAL;
      } else {
        /* Fallback to voice assistant
         * This will handle also a case when the device is
         * AUDIO_SOURCE_VOICE_RECOGNITION
         */
        track_context = LeAudioContextType::VOICEASSISTANTS;
        log::warn(
                "Could not match the recording track type to group available "
                "context. Using context {}.",
                ToString(track_context));
      }

      local_decoding_context_types_.set(track_context);
    }
    printCurrentState("SetDecodingSession:");
  }

  void OverrideContextTypes(const BidirectionalPair<AudioContexts>& local_context_types) {
    local_encoding_contexts_types_.sink.clear();
    local_encoding_contexts_types_.source = local_context_types.source;
    local_decoding_context_types_ = local_context_types.sink;
    updateVoipState();
    printCurrentState("Override");
  }

  void SetInCall(bool in_call) {
    log::info("{}", in_call);
    inCallState = in_call;
    printCurrentState("SetInCall");
  }

  bool IsInCall(void) { return inCallState; }
  bool IsInVoip(void) { return inVoipState; }

  bool IsAnyMetadataSet(void) {
    log::info("");
    return !(local_decoding_context_types_.none() && local_encoding_contexts_types_.sink.none() &&
             local_encoding_contexts_types_.source.none());
  }

  BidirectionalPair<bool> GetDirectionsForGivenContext(LeAudioContextType context_type,
                                                       const LeAudioDeviceGroup* group) {
    BidirectionalPair<bool> remote_directions = {false, false};
    if (group == nullptr) {
      log::error("Group is null");
      return remote_directions;
    }

    if (context_type == LeAudioContextType::UNSPECIFIED) {
      /* For unspecified consider only SINK direction. This needs to be tight with
       * GetAudioContextsForTheGroup() when it is called before metadata are set. */
      remote_directions.sink = true;
      remote_directions.source = false;
      return remote_directions;
    }

    auto bidirectional_context = group->GetAllSupportedBidirectionalContextTypes();
    auto remote_sink_only_context_types =
            group->GetAllSupportedSingleDirectionOnlyContextTypes(kLeAudioDirectionSink);
    auto remote_source_only_context_types =
            group->GetAllSupportedSingleDirectionOnlyContextTypes(kLeAudioDirectionSource);
    log::debug(
            "context_type: {} -> remote_sink_only_context_types : {}, "
            "remote_source_only_context_types {} "
            "bidirectional_context {}",
            ToString(context_type), ToString(remote_sink_only_context_types),
            ToString(remote_source_only_context_types), ToString(bidirectional_context));

    if (bidirectional_context.test(context_type)) {
      remote_directions.sink = true;
      remote_directions.source = true;
      return remote_directions;
    }

    bool is_gmap_and_recording = (context_type == LeAudioContextType::GAME) &&
                                 group->IsGmapEnabled() && remote_source_only_context_types.any();
    if (remote_sink_only_context_types.test(context_type)) {
      remote_directions.sink = true;
    }

    if (remote_source_only_context_types.test(context_type) || is_gmap_and_recording) {
      remote_directions.source = true;
    }

    log::info(
            "context: {}, remote sink support: {}, remote source supporte: {}, "
            "is_gmap_and_recording: {}",
            ToString(context_type), remote_directions.sink, remote_directions.source,
            is_gmap_and_recording);
    return remote_directions;
  }

  std::pair<LeAudioContextType, BidirectionalPair<AudioContexts>> GetAudioContextsForTheGroup(
          const LeAudioDeviceGroup* group) {
    if (group == nullptr) {
      log::error("Group is null");
      BidirectionalPair<AudioContexts> empty_metadata = {AudioContexts(), AudioContexts()};
      return std::make_pair(LeAudioContextType::UNINITIALIZED, empty_metadata);
    }

    log::info(
            "inCallState: {}, local_encoding_contexts_types_.source: {}, "
            "local_encoding_contexts_types_.sink: {}, "
            "local_decoding_context_types_: {}",
            inCallState, ToString(local_encoding_contexts_types_.source),
            ToString(local_encoding_contexts_types_.sink), ToString(local_decoding_context_types_));

    /* If there is no metadata set but call is happening, we can move forward. Othwerise lets return
     * here.*/
    if (!IsAnyMetadataSet() && !IsInCall()) {
      log::error(
              "Called for group_id: {}, when HAL did not set any metadata. using Unspecified only "
              "for SINK",
              group->group_id_);
      BidirectionalPair<AudioContexts> unspecified_metadata = {
              AudioContexts(LeAudioContextType::UNSPECIFIED), AudioContexts()};
      return std::make_pair(LeAudioContextType::UNSPECIFIED, unspecified_metadata);
    }

    LeAudioContextType configuration_context_type = LeAudioContextType::UNINITIALIZED;
    auto conversational_context_if_needed = AudioContexts();
    if (IsInCall() || IsInVoip()) {
      conversational_context_if_needed.set(LeAudioContextType::CONVERSATIONAL);
      if (!(group->IsGmapEnabled() &&
            local_encoding_contexts_types_.source.test(LeAudioContextType::GAME))) {
        configuration_context_type = LeAudioContextType::CONVERSATIONAL;
      }
      log::info("Adding {}, isInCall: {}, inInVoip: {}", ToString(conversational_context_if_needed),
                IsInCall(), IsInVoip());
    }

    BidirectionalPair<AudioContexts> remote_supported_contexts;
    remote_supported_contexts.sink = group->GetSupportedContexts(kLeAudioDirectionSink);
    remote_supported_contexts.source = group->GetSupportedContexts(kLeAudioDirectionSource);

    /* Note that Available contains also Streaming metadata */
    BidirectionalPair<AudioContexts> remote_available_contexts;
    remote_available_contexts.sink = group->GetAvailableContexts(kLeAudioDirectionSink);
    remote_available_contexts.source = group->GetAvailableContexts(kLeAudioDirectionSource);

    auto expected_remote_context_types = remote_available_contexts;

    /* Need to adjust decoding_context_types Bidirectional cases.
     * i.e. if context type is bidirectional, and decoding session is enabled, we should remove
     * LIVE context and replace it with bidirectional one
     */
    auto adjusted_dec_context_types = local_decoding_context_types_;
    auto bidirectional_context = group->GetAllSupportedBidirectionalContextTypes();
    auto used_bidirectional_on_encoding =
            bidirectional_context &
            (local_encoding_contexts_types_.sink | conversational_context_if_needed);

    /* If decoding session is started, let's check if we should replace LIVE context with another
     * one. This can happen, because metadata on the decoding sessions are limited and we need to do
     * some guessing what the metadata should be by looking into encoding session metadata.
     */
    if (adjusted_dec_context_types.test(LeAudioContextType::LIVE)) {
      log::info("used_bidirectional_on_encoding: {}, local_encoding_contexts_types_.source: {}",
                ToString(used_bidirectional_on_encoding),
                ToString(local_encoding_contexts_types_.source));
      if (used_bidirectional_on_encoding.any()) {
        adjusted_dec_context_types.clear();
        adjusted_dec_context_types.set_all(used_bidirectional_on_encoding);
      } else if (remote_available_contexts.sink.none() &&
                 local_encoding_contexts_types_.source.any()) {
        log::info("Source only devices");
        /* For source only devices, we might need a support for choosing context type based on the
         * encoding session metadata.
         */
        adjusted_dec_context_types = local_encoding_contexts_types_.source;
      }
    }

    /* Here we choose the configuration context type which is somehow usecase context type.
     * It means, the context type which is main from the Audio Framework and Bluetooth point of
     * view, despite remote supported contexts. We want to keep it, to not lose the use case.
     */
    if (configuration_context_type == LeAudioContextType::UNINITIALIZED) {
      configuration_context_type = getConfigurationContextType(
              get_bidirectional(local_encoding_contexts_types_) | adjusted_dec_context_types,
              group->IsGmapEnabled());
    }
    /* Let's calculate expected contex types. Note, that here Local Source becomes Remote Sink  */
    expected_remote_context_types.sink &=
            (local_encoding_contexts_types_.source | adjusted_dec_context_types |
             conversational_context_if_needed);
    expected_remote_context_types.source &=
            (local_encoding_contexts_types_.sink | adjusted_dec_context_types |
             conversational_context_if_needed);

    /* Let's check if we should replace unsupported context with UNSPECIFIED. */
    if (expected_remote_context_types.sink.none()) {
      if (local_encoding_contexts_types_.source.any() &&
          !remote_supported_contexts.sink.test_any(local_encoding_contexts_types_.source) &&
          remote_available_contexts.sink.test(LeAudioContextType::UNSPECIFIED)) {
        expected_remote_context_types.sink.set(LeAudioContextType::UNSPECIFIED);
      }
    }
    /* Same as above to other directions. */
    if (expected_remote_context_types.source.none() && local_decoding_context_types_.any() &&
        remote_available_contexts.source.test(LeAudioContextType::UNSPECIFIED)) {
      auto decoding = local_encoding_contexts_types_.sink | adjusted_dec_context_types |
                      conversational_context_if_needed;
      if (decoding.any() && !remote_supported_contexts.source.test_any(decoding)) {
        expected_remote_context_types.source.set(LeAudioContextType::UNSPECIFIED);
      }
    }

    log::info(
            "group_id: {}, configuration_context_type: {}, remote_available_contexts.sink: {}, "
            "remote_available_contexts.source: {}, expected_remote_context_types.sink: {}, "
            "expected_context_types.source: {} ",
            group->group_id_, ToString(configuration_context_type),
            ToString(remote_available_contexts.sink), ToString(remote_available_contexts.source),
            ToString(expected_remote_context_types.sink),
            ToString(expected_remote_context_types.source));

    return std::make_pair(configuration_context_type, expected_remote_context_types);
  }

  void DebugDump(int fd) {
    std::stringstream stream;

    stream << std::format(
            "AudioContextTypeManager: \n inCallState: {}, inVoipState: {}\n, "
            "local_encoding_contexts_types_.source: {}, local_encoding_contexts_types_.sink: {}\n, "
            "local_decoding_context_types_(sink): {} \n",
            inCallState, inVoipState, ToString(local_encoding_contexts_types_.source),
            ToString(local_encoding_contexts_types_.sink), ToString(local_decoding_context_types_));
    dprintf(fd, "%s\n", stream.str().c_str());
  }

private:
  LeAudioContextType getConfigurationContextType(AudioContexts contexts, bool gmap_available) {
    /* Mini policy - always prioritize sink+source configurations so that we are
     * sure that for a mixed content we enable all the needed directions.
     */
    if (contexts.any()) {
      std::list<LeAudioContextType> context_priority_list = {
              /* Highest priority first */
              LeAudioContextType::CONVERSATIONAL, LeAudioContextType::RINGTONE,
              LeAudioContextType::LIVE,           LeAudioContextType::VOICEASSISTANTS,
              LeAudioContextType::GAME,           LeAudioContextType::MEDIA,
              LeAudioContextType::EMERGENCYALARM, LeAudioContextType::ALERTS,
              LeAudioContextType::INSTRUCTIONAL,  LeAudioContextType::NOTIFICATIONS,
              LeAudioContextType::SOUNDEFFECTS,
      };

      // Prioritize GMAP if available
      if (gmap_available) {
        context_priority_list.push_front(LeAudioContextType::GAME);
      }

      for (auto ct : context_priority_list) {
        if (contexts.test(ct)) {
          log::debug("Selecting configuration context type: {}", ToString(ct));
          return ct;
        }
      }
    }

    return LeAudioContextType::UNSPECIFIED;
  }

  void updateVoipState(void) {
    constexpr AudioContexts possible_voip_contexts =
            LeAudioContextType::RINGTONE | LeAudioContextType::CONVERSATIONAL;
    if (local_encoding_contexts_types_.source.test_any(possible_voip_contexts)) {
      if (!inCallState) {
        /* Consider VOIP call */
        inVoipState = true;
      }
    } else if (inVoipState) {
      inVoipState = false;
    }
  }
  void printCurrentState(std::string prefix) {
    log::info(
            "{}: inCallState: {}, inVoipState: {}, local_encoding_contexts_types_.source: {}, "
            "local_encoding_contexts_types_.sink: {}, "
            "local_decoding_context_types_(sink): {}",
            prefix, inCallState, inVoipState, ToString(local_encoding_contexts_types_.source),
            ToString(local_encoding_contexts_types_.sink), ToString(local_decoding_context_types_));
  }

  bool isBidirectionalControlledByAudioFramework(LeAudioContextType context) {
    switch (context) {
      case LeAudioContextType::GAME:
        return true;
      default:
        break;
    }
    return false;
  }

  /* Those two keeps the context types Bluetooth receives from the Audio Framework.
   * local_encoding_contexts_types_.source -> audio context type being sent out to remote for
   * encoding session metadata
   * local_encoding_contexts_types_.sink -> possible context for the other
   * direction based on the encoding session metadata
   */
  BidirectionalPair<AudioContexts> local_encoding_contexts_types_;
  /* local_decoding_context_types_ -> audio context type based on the decoding session metadata */
  AudioContexts local_decoding_context_types_;

  bool inCallState = false;
  bool inVoipState = false;
};
}  // namespace

namespace bluetooth::le_audio {
void AudioContextTypeManager::Initialize(void) {
  if (instance) {
    log::error("Already initialized");
    return;
  }

  log::info("");
  instance = std::make_shared<AudioContextTypeManagerImpl>();
}

void AudioContextTypeManager::Cleanup() {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!instance) {
    return;
  }
  log::info("");
  instance.reset();
}

void AudioContextTypeManager::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    instance->DebugDump(fd);
  }
}

std::shared_ptr<AudioContextTypeManager> AudioContextTypeManager::Get() {
  log::assert_that(instance != nullptr, "assert failed: instance != nullptr");
  return instance;
}
}  // namespace bluetooth::le_audio
