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
#include <bluetooth/types/string_helpers.h>
#include <hardware/bluetooth.h>

#include <mutex>
#include <utility>
#include <vector>

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
      updateVoipState();
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
            isMetadataTagPresent(entry.tags, "VX_AOSP_BIDIRECTIONAL")) {
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
      LeAudioContextType track_context;

      log::debug(
              "source={}(0x{:02x}), gain={:f}, destination device=0x{:08x}, "
              "destination device address={:32s}",
              audioSourceToStr(track.source), track.source, track.gain, track.dest_device,
              track.dest_device_address);

      switch (track.source) {
        case AUDIO_SOURCE_VOICE_CALL:
        case AUDIO_SOURCE_VOICE_COMMUNICATION:
          track_context = LeAudioContextType::CONVERSATIONAL;
          break;
        case AUDIO_SOURCE_VOICE_PERFORMANCE:
          track_context = LeAudioContextType::GAME;
          break;
        case AUDIO_SOURCE_VOICE_RECOGNITION:
          track_context = LeAudioContextType::VOICEASSISTANTS;
          break;
        case AUDIO_SOURCE_REMOTE_SUBMIX:
        case AUDIO_SOURCE_CAMCORDER:
        case AUDIO_SOURCE_MIC:
        case AUDIO_SOURCE_VOICE_UPLINK:
        case AUDIO_SOURCE_VOICE_DOWNLINK:
        case AUDIO_SOURCE_UNPROCESSED:
        case AUDIO_SOURCE_ECHO_REFERENCE:
        case AUDIO_SOURCE_FM_TUNER:
        case AUDIO_SOURCE_HOTWORD:
        case AUDIO_SOURCE_DEFAULT:
        case AUDIO_SOURCE_ULTRASOUND:
          track_context = LeAudioContextType::LIVE;
          break;
        case AUDIO_SOURCE_INVALID:
          log::debug("AUDIO_SOURCE_INVALID");
          continue;
      }

      local_decoding_context_types_.set(track_context);
    }
    if (com_android_bluetooth_flags_leaudio_game_detector()) {
      updateVoipState();
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
    in_call_ = in_call;
    printCurrentState("SetInCall");
  }

  bool IsInCall(void) { return in_call_; }
  bool IsInVoip(void) { return in_voip_; }
  bool IsInGame(void) { return in_game_; }

  void SetInGame(bool in_game) {
    log::info("{}", in_game);
    in_game_ = in_game;
    printCurrentState("SetInGame");
  }

  bool IsAnyMetadataSet(
          uint8_t local_directions = bluetooth::le_audio::types::kLeAudioDirectionBoth) {
    log::info("local_direcions: {:#x}", local_directions);

    if (local_directions == bluetooth::le_audio::types::kLeAudioDirectionBoth) {
      return !(local_decoding_context_types_.none() && local_encoding_contexts_types_.sink.none() &&
               local_encoding_contexts_types_.source.none());
    }

    if (local_directions == bluetooth::le_audio::types::kLeAudioDirectionSink) {
      return !(local_decoding_context_types_.none() && local_encoding_contexts_types_.sink.none());
    }

    return !local_encoding_contexts_types_.source.none();
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

    // For Android RINGTONE is already bidirectional use case.
    if (bidirectional_context.test(context_type) || context_type == LeAudioContextType::RINGTONE) {
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

    if (remote_directions.sink == false && remote_directions.source == false) {
      log::warn(
              "Context is not supported on the remote side. Fallback to UNSPECIFIED and for this "
              "we enable only remote sink direction as for now.");
      remote_directions.sink = true;
    }

    return remote_directions;
  }

  std::pair<LeAudioContextType, BidirectionalPair<AudioContexts>> GetAudioContextsForTheGroup(
          const LeAudioDeviceGroup* group, uint8_t remote_directions) {
    if (group == nullptr) {
      log::error("Group is null");
      BidirectionalPair<AudioContexts> empty_metadata = {AudioContexts(), AudioContexts()};
      return std::make_pair(LeAudioContextType::UNINITIALIZED, empty_metadata);
    }

    log::info(
            "IsInCall: {}, IsInVoip: {}, IsInGame: {}, local_encoding_contexts_types_.source: {}, "
            "local_encoding_contexts_types_.sink: {}, "
            "local_decoding_context_types_: {}, remote_directions: {}",
            IsInCall(), IsInVoip(), IsInGame(), ToString(local_encoding_contexts_types_.source),
            ToString(local_encoding_contexts_types_.sink), ToString(local_decoding_context_types_),
            ToString(remote_directions));

    auto copy_local_encoding_ctxs = local_encoding_contexts_types_;
    auto copy_local_decoding_ctxs = local_decoding_context_types_;

    if (remote_directions != bluetooth::le_audio::types::kLeAudioDirectionBoth) {
      log::warn("Some directions are omitted by the user. Remote directions are: {}",
                ToString(remote_directions));
      if (!(remote_directions & bluetooth::le_audio::types::kLeAudioDirectionSink)) {
        copy_local_encoding_ctxs.sink.clear();
        copy_local_encoding_ctxs.source.clear();
      }

      if (!(remote_directions & bluetooth::le_audio::types::kLeAudioDirectionSource)) {
        copy_local_decoding_ctxs.clear();
      }
    }

    /* If there is no metadata set but call or game is happening, we can move forward. Othwerise
     * lets return here.*/
    if (!IsAnyMetadataSet() && !IsInCall() && !IsInGame()) {
      log::error(
              "Called for group_id: {}, when HAL did not set any metadata. using Unspecified only "
              "for SINK",
              group->group_id_);
      BidirectionalPair<AudioContexts> unspecified_metadata = {
              AudioContexts(LeAudioContextType::UNSPECIFIED), AudioContexts()};
      return std::make_pair(LeAudioContextType::UNSPECIFIED, unspecified_metadata);
    }

    LeAudioContextType configuration_context_type = LeAudioContextType::UNINITIALIZED;
    BidirectionalPair<AudioContexts> additional_local_contexts_based_on_states = {AudioContexts(),
                                                                                  AudioContexts()};
    if (IsInGame()) {
      if (copy_local_encoding_ctxs.source.none() && copy_local_decoding_ctxs.none()) {
        log::info(
                "Adding game Mode to remote Sink as Audio Hal doesn't specify metadata during the "
                "game mode");
        copy_local_encoding_ctxs.source.set(LeAudioContextType::GAME);
        additional_local_contexts_based_on_states.source.set(LeAudioContextType::GAME);
      } else {
        if (copy_local_encoding_ctxs.source.any()) {
          log::info("Adding game Mode to remote Sink");
          copy_local_encoding_ctxs.source.set(LeAudioContextType::GAME);
          additional_local_contexts_based_on_states.source.set(LeAudioContextType::GAME);
        }

        if (copy_local_decoding_ctxs.any()) {
          log::info("Adding game Mode to remote Source");
          copy_local_decoding_ctxs.set(LeAudioContextType::GAME);
          additional_local_contexts_based_on_states.sink.set(LeAudioContextType::GAME);
        }
      }
    }

    if (IsInCall() || IsInVoip()) {
      additional_local_contexts_based_on_states.sink.set(LeAudioContextType::CONVERSATIONAL);
      additional_local_contexts_based_on_states.source.set(LeAudioContextType::CONVERSATIONAL);

      if (!(group->IsGmapEnabled() &&
            copy_local_encoding_ctxs.source.test(LeAudioContextType::GAME))) {
        configuration_context_type = LeAudioContextType::CONVERSATIONAL;
      }

      log::info("Adding local sink: {} source: {}, IsInCall: {}, IsInVoip: {}",
                ToString(additional_local_contexts_based_on_states.sink),
                ToString(additional_local_contexts_based_on_states.source), IsInCall(), IsInVoip());
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
    auto adjusted_dec_context_types = copy_local_decoding_ctxs;
    auto bidirectional_context = group->GetAllSupportedBidirectionalContextTypes();
    auto used_bidirectional_on_encoding =
            bidirectional_context &
            (copy_local_encoding_ctxs.sink | additional_local_contexts_based_on_states.sink);

    /* If decoding session is started, let's check if we should replace LIVE context with another
     * one. This can happen, because metadata on the decoding sessions are limited and we need to do
     * some guessing what the metadata should be by looking into encoding session metadata.
     */
    if (adjusted_dec_context_types.test(LeAudioContextType::LIVE)) {
      log::info("used_bidirectional_on_encoding: {}, local_encoding_contexts_types_.source: {}",
                ToString(used_bidirectional_on_encoding),
                ToString(copy_local_encoding_ctxs.source));
      if (used_bidirectional_on_encoding.any()) {
        adjusted_dec_context_types.clear();
        adjusted_dec_context_types.set_all(used_bidirectional_on_encoding);
      } else if (remote_available_contexts.sink.none() && copy_local_encoding_ctxs.source.any()) {
        log::info("Source only devices");
        /* For source only devices, we might need a support for choosing context type based on the
         * encoding session metadata.
         */
        adjusted_dec_context_types = copy_local_encoding_ctxs.source;
      }
    }

    /* Here we choose the configuration context type which is somehow usecase context type.
     * It means, the context type which is main from the Audio Framework and Bluetooth point of
     * view, despite remote supported contexts. We want to keep it, to not lose the use case.
     */
    if (configuration_context_type == LeAudioContextType::UNINITIALIZED) {
      configuration_context_type = getConfigurationContextType(
              get_bidirectional(copy_local_encoding_ctxs) | adjusted_dec_context_types,
              group->IsGmapEnabled());
    }
    /* Let's calculate expected contex types. Note, that here Local Source becomes Remote Sink  */
    expected_remote_context_types.sink &=
            (local_encoding_contexts_types_.source | adjusted_dec_context_types |
             additional_local_contexts_based_on_states.source);
    expected_remote_context_types.source &=
            (local_encoding_contexts_types_.sink | adjusted_dec_context_types |
             additional_local_contexts_based_on_states.sink);

    /* Let's check if we should replace unsupported context with UNSPECIFIED. */
    if (expected_remote_context_types.sink.none()) {
      if (local_encoding_contexts_types_.source.any() &&
          !remote_supported_contexts.sink.test_any(local_encoding_contexts_types_.source) &&
          remote_available_contexts.sink.test(LeAudioContextType::UNSPECIFIED)) {
        expected_remote_context_types.sink.set(LeAudioContextType::UNSPECIFIED);
      }
    }
    /* Same as above to other directions. */
    if (expected_remote_context_types.source.none() && copy_local_decoding_ctxs.any() &&
        remote_available_contexts.source.test(LeAudioContextType::UNSPECIFIED)) {
      auto decoding = local_encoding_contexts_types_.sink | adjusted_dec_context_types |
                      additional_local_contexts_based_on_states.sink;
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
            "AudioContextTypeManager:\n IsInCall: {}, IsInVoip: {}, IsInGame: {}\n "
            "local_encoding_contexts_types_.source: {}\n local_encoding_contexts_types_.sink: {}\n "
            "local_decoding_context_types_(sink): {}\n",
            IsInCall(), IsInVoip(), IsInGame(), ToString(local_encoding_contexts_types_.source),
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

      log::debug("gmap_available: {}, in_game_: {}, in_voip_: {}", gmap_available, in_game_,
                 in_voip_);

      // Prioritize GMAP if available
      if (gmap_available || (in_game_ && in_voip_)) {
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
    constexpr AudioContexts possible_voip_contexts_on_encoding =
            LeAudioContextType::RINGTONE | LeAudioContextType::CONVERSATIONAL;
    if (local_encoding_contexts_types_.source.test_any(possible_voip_contexts_on_encoding) ||
        local_decoding_context_types_.test(LeAudioContextType::CONVERSATIONAL)) {
      if (!in_call_) {
        /* Consider VOIP call */
        in_voip_ = true;
      }
    } else if (in_voip_) {
      in_voip_ = false;
    }
  }
  void printCurrentState(std::string prefix) {
    log::info(
            "{}: IsInCall: {}, IsInVoip: {}, IsInGame: {}, "
            "local_encoding_contexts_types_.source: {}, "
            "local_encoding_contexts_types_.sink: {}, "
            "local_decoding_context_types_(sink): {}",
            prefix, IsInCall(), IsInVoip(), IsInGame(),
            ToString(local_encoding_contexts_types_.source),
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

  bool in_call_ = false;
  bool in_voip_ = false;
  bool in_game_ = false;
};
}  // namespace

namespace bluetooth::le_audio {
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
  log::info("");
  if (!instance) {
    instance = std::make_shared<AudioContextTypeManagerImpl>();
  }
  return instance;
}
}  // namespace bluetooth::le_audio
