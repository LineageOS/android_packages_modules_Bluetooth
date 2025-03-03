/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "bt_octets.h"
#include "bta/include/bta_le_audio_broadcaster_api.h"
#include "bta/le_audio/broadcaster/state_machine.h"
#include "bta/le_audio/codec_interface.h"
#include "bta/le_audio/content_control_id_keeper.h"
#include "bta/le_audio/le_audio_types.h"
#include "bta/le_audio/le_audio_utils.h"
#include "bta/le_audio/metrics_collector.h"
#include "bta_le_audio_api.h"
#include "btm_iso_api_types.h"
#include "common/strings.h"
#include "hardware/ble_advertiser.h"
#include "hardware/bt_le_audio.h"
#include "hci/controller_interface.h"
#include "hcidefs.h"
#include "hcimsgs.h"
#include "internal_include/stack_config.h"
#include "le_audio/audio_hal_client/audio_hal_client.h"
#include "le_audio/broadcaster/broadcaster_types.h"
#include "main/shim/entry.h"
#include "osi/include/alarm.h"
#include "osi/include/properties.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_iso_api.h"

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif  // TARGET_FLOSS

using bluetooth::common::ToString;
using bluetooth::hci::IsoManager;
using bluetooth::hci::iso_manager::big_create_cmpl_evt;
using bluetooth::hci::iso_manager::big_terminate_cmpl_evt;
using bluetooth::hci::iso_manager::BigCallbacks;
using bluetooth::le_audio::BasicAudioAnnouncementData;
using bluetooth::le_audio::BasicAudioAnnouncementSubgroup;
using bluetooth::le_audio::BroadcastId;
using bluetooth::le_audio::CodecManager;
using bluetooth::le_audio::ContentControlIdKeeper;
using bluetooth::le_audio::DsaMode;
using bluetooth::le_audio::LeAudioSourceAudioHalClient;
using bluetooth::le_audio::PublicBroadcastAnnouncementData;
using bluetooth::le_audio::broadcaster::BigConfig;
using bluetooth::le_audio::broadcaster::BroadcastConfiguration;
using bluetooth::le_audio::broadcaster::BroadcastStateMachine;
using bluetooth::le_audio::broadcaster::BroadcastStateMachineConfig;
using bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig;
using bluetooth::le_audio::broadcaster::IBroadcastStateMachineCallbacks;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::CodecLocation;
using bluetooth::le_audio::types::LeAudioContextType;
using bluetooth::le_audio::types::LeAudioLtvMap;
using bluetooth::le_audio::utils::GetAudioContextsFromSourceMetadata;

using namespace bluetooth;

namespace {
class LeAudioBroadcasterImpl;
LeAudioBroadcasterImpl* instance;
std::mutex instance_mutex;

/* Class definitions */

/* LeAudioBroadcasterImpl class represents main implementation class for le
 * audio broadcaster feature in the stack.
 *
 * This class may be bonded with Test socket which allows to drive an instance
 * for test purposes.
 */
class LeAudioBroadcasterImpl : public LeAudioBroadcaster, public BigCallbacks {
  enum class AudioState { STOPPED, SUSPENDED, ACTIVE };

public:
  LeAudioBroadcasterImpl(bluetooth::le_audio::LeAudioBroadcasterCallbacks* callbacks_)
      : callbacks_(callbacks_),
        current_phy_(PHY_LE_2M),
        le_audio_source_hal_client_(nullptr),
        audio_state_(AudioState::SUSPENDED),
        big_terminate_timer_(alarm_new("BigTerminateTimer")),
        broadcast_stop_timer_(alarm_new("BroadcastStopTimer")) {
    log::info("");

    /* Register State machine callbacks */
    BroadcastStateMachine::Initialize(&state_machine_callbacks_, &state_machine_adv_callbacks_);

    GenerateBroadcastIds();
  }

  ~LeAudioBroadcasterImpl() override {
    alarm_free(big_terminate_timer_);
    alarm_free(broadcast_stop_timer_);
  }

  void GenerateBroadcastIds(void) {
    btsnd_hcic_ble_rand(base::Bind([](BT_OCTET8 rand) {
      if (!instance) {
        return;
      }

      /* LE Rand returns 8 octets. Lets' make 2 outstanding Broadcast Ids out
       * of it */
      for (int i = 0; i < 8; i += 4) {
        BroadcastId broadcast_id = 0;
        /* Broadcast ID should be 3 octets long (BAP v1.0 spec.) */
        STREAM_TO_UINT24(broadcast_id, rand);
        if (broadcast_id == bluetooth::le_audio::kBroadcastIdInvalid) {
          continue;
        }
        instance->available_broadcast_ids_.emplace_back(broadcast_id);
      }

      if (instance->available_broadcast_ids_.empty()) {
        log::fatal("Unable to generate proper broadcast identifiers.");
      }
    }));
  }

  void CleanUp() {
    log::info("Broadcaster");
    broadcasts_.clear();
    callbacks_ = nullptr;
    is_iso_running_ = false;

    if (!LeAudioClient::IsLeAudioClientRunning()) {
      IsoManager::GetInstance()->Stop();
    }

    queued_start_broadcast_request_ = std::nullopt;
    queued_create_broadcast_request_ = std::nullopt;

    if (le_audio_source_hal_client_) {
      le_audio_source_hal_client_->Stop();
      CodecManager::GetInstance()->UpdateActiveBroadcastAudioHalClient(
              le_audio_source_hal_client_.get(), false);
      le_audio_source_hal_client_.reset();
    }
    audio_state_ = AudioState::SUSPENDED;
    cancelBroadcastTimers();
  }

  void Stop() {
    log::info("Broadcaster");

    for (auto& sm_pair : broadcasts_) {
      StopAudioBroadcast(sm_pair.first);
    }
  }

  static PublicBroadcastAnnouncementData preparePublicAnnouncement(uint8_t features,
                                                                   const LeAudioLtvMap& metadata) {
    PublicBroadcastAnnouncementData announcement;

    /* Prepare the announcement */
    announcement.features = features;
    announcement.metadata = metadata.Values();
    return announcement;
  }

  static BasicAudioAnnouncementData prepareBasicAnnouncement(
          const std::vector<BroadcastSubgroupCodecConfig>& subgroup_configs,
          const std::vector<LeAudioLtvMap>& metadata_group) {
    BasicAudioAnnouncementData announcement;

    /* Prepare the announcement */
    announcement.presentation_delay_us = 40000; /* us */

    log::assert_that(subgroup_configs.size() == metadata_group.size(),
                     "The number of metadata subgroups {} does not match the "
                     "number of subgroup configurations {}.",
                     metadata_group.size(), subgroup_configs.size());

    uint8_t subgroup_idx = 0;
    uint8_t bis_index = 0;
    while (subgroup_idx < subgroup_configs.size() && subgroup_idx < metadata_group.size()) {
      const auto& subgroup_config = subgroup_configs.at(subgroup_idx);
      const auto& metadata = metadata_group.at(subgroup_idx);

      auto const& codec_id = subgroup_config.GetLeAudioCodecId();
      auto const subgroup_codec_spec = subgroup_config.GetCommonBisCodecSpecData();
      auto opt_vendor_spec_data = subgroup_config.GetVendorCodecSpecData();

      /* Note: Currently we have a single audio source configured with a one
       *       set of codec/pcm parameters thus we can use a single subgroup
       *       for all the BISes. Configure common BIS codec params at the
       *       subgroup level.
       */
      BasicAudioAnnouncementSubgroup config = {
              .codec_config =
                      {
                              .codec_id = codec_id.coding_format,
                              .vendor_company_id = codec_id.vendor_company_id,
                              .vendor_codec_id = codec_id.vendor_codec_id,
                              .codec_specific_params =
                                      opt_vendor_spec_data.has_value()
                                              ? std::map<uint8_t, std::vector<uint8_t>>{}
                                              : subgroup_codec_spec.Values(),
                              .vendor_codec_specific_params = std::move(opt_vendor_spec_data),
                      },
              .metadata = metadata.Values(),
              .bis_configs = {},
      };

      for (uint8_t bis_cfg_idx = 0; bis_cfg_idx < subgroup_config.GetAllBisConfigCount();
           ++bis_cfg_idx) {
        auto bis_cfg_num_of_bises = subgroup_config.GetNumBis(bis_cfg_idx);
        for (uint8_t bis_num = 0; bis_num < bis_cfg_num_of_bises; ++bis_num) {
          // Internally BISes are indexed from 0 in each subgroup, but the BT
          // spec requires the indices to start from 1 in the entire BIG.
          ++bis_index;

          // Check for vendor byte array
          bluetooth::le_audio::BasicAudioAnnouncementBisConfig bis_config;
          auto vendor_config = subgroup_config.GetBisVendorCodecSpecData(bis_num);
          if (vendor_config) {
            bis_config.vendor_codec_specific_params = vendor_config.value();
          }

          // Check for non vendor LTVs
          auto config_ltv = subgroup_config.GetBisCodecSpecData(bis_num, bis_cfg_idx);
          if (config_ltv) {
            // Remove the part which is common with the parent subgroup
            // parameters
            config_ltv->RemoveAllTypes(subgroup_codec_spec);
            bis_config.codec_specific_params = config_ltv->Values();
          }

          bis_config.bis_index = bis_index;
          config.bis_configs.push_back(std::move(bis_config));
        }
      }

      announcement.subgroup_configs.push_back(config);
      ++subgroup_idx;
    }

    return announcement;
  }

  void UpdateStreamingContextTypeOnAllSubgroups(const AudioContexts& contexts) {
    log::debug("context_type_map={}", contexts.to_string());

    auto ccids = ContentControlIdKeeper::GetInstance()->GetAllCcids(contexts);
    if (ccids.empty()) {
      log::warn("No content providers available for context_type_map={}.", contexts.to_string());
    }

    std::vector<uint8_t> stream_context_vec(2);
    auto pp = stream_context_vec.data();
    UINT16_TO_STREAM(pp, contexts.value());

    for (auto const& kv_it : broadcasts_) {
      auto& broadcast = kv_it.second;
      if (broadcast->GetState() == BroadcastStateMachine::State::STREAMING) {
        auto announcement = broadcast->GetBroadcastAnnouncement();
        bool broadcast_update = false;

        // Replace context type and CCID list
        for (auto& subgroup : announcement.subgroup_configs) {
          auto subgroup_ltv = LeAudioLtvMap(subgroup.metadata);
          bool subgroup_update = false;

          auto existing_context = subgroup_ltv.Find(
                  bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext);
          if (existing_context) {
            if (memcmp(stream_context_vec.data(), existing_context->data(),
                       existing_context->size()) != 0) {
              subgroup_ltv.Add(
                      bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext,
                      stream_context_vec);
              subgroup_update = true;
            }
          } else {
            subgroup_ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext,
                             stream_context_vec);
            subgroup_update = true;
          }

          auto existing_ccid_list =
                  subgroup_ltv.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
          if (existing_ccid_list) {
            if (ccids.empty()) {
              subgroup_ltv.Remove(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
              subgroup_update = true;

            } else if (!std::is_permutation(ccids.begin(), ccids.end(),
                                            existing_ccid_list->begin())) {
              subgroup_ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList, ccids);
              subgroup_update = true;
            }
          } else if (!ccids.empty()) {
            subgroup_ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList, ccids);
            subgroup_update = true;
          }

          if (subgroup_update) {
            subgroup.metadata = subgroup_ltv.Values();
            broadcast_update = true;
          }
        }

        if (broadcast_update) {
          broadcast->UpdateBroadcastAnnouncement(std::move(announcement));
        }
      }
    }
  }

  void UpdateAudioActiveStateInPublicAnnouncement() {
    for (auto const& kv_it : broadcasts_) {
      auto& broadcast = kv_it.second;

      bool audio_active_state = (audio_state_ == AudioState::ACTIVE) &&
                                (broadcast->GetState() == BroadcastStateMachine::State::STREAMING);

      log::info("broadcast_id={}, audio_active_state={}", broadcast->GetBroadcastId(),
                audio_active_state);

      auto updateLtv = [](bool audio_active_state, LeAudioLtvMap& ltv) -> bool {
        auto existing_audio_active_state =
                ltv.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState);

        if (existing_audio_active_state && !existing_audio_active_state->empty()) {
          if (audio_active_state != static_cast<bool>(existing_audio_active_state->at(0))) {
            ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState,
                    audio_active_state);
            return true;
          }
        } else {
          ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState,
                  audio_active_state);
          return true;
        }

        return false;
      };

      auto public_announcement = broadcast->GetPublicBroadcastAnnouncement();
      auto public_ltv = LeAudioLtvMap(public_announcement.metadata);

      if (updateLtv(audio_active_state, public_ltv)) {
        public_announcement.metadata = public_ltv.Values();
        broadcast->UpdatePublicBroadcastAnnouncement(
                broadcast->GetBroadcastId(), broadcast->GetBroadcastName(), public_announcement);
      }
    }
  }

  void UpdateMetadata(uint32_t broadcast_id, const std::string& broadcast_name,
                      const std::vector<uint8_t>& public_metadata,
                      const std::vector<std::vector<uint8_t>>& subgroup_metadata) override {
    std::vector<LeAudioLtvMap> subgroup_ltvs;

    if (broadcasts_.count(broadcast_id) == 0) {
      log::error("No such broadcast_id={}", broadcast_id);
      return;
    }

    log::info("For broadcast_id={}", broadcast_id);

    for (const std::vector<uint8_t>& metadata : subgroup_metadata) {
      /* Prepare the announcement format */
      bool is_metadata_valid;
      auto ltv = LeAudioLtvMap::Parse(metadata.data(), metadata.size(), is_metadata_valid);
      if (!is_metadata_valid) {
        log::error("Invalid metadata provided.");
        return;
      }

      auto context_type = AudioContexts(LeAudioContextType::MEDIA);

      /* Adds multiple contexts and CCIDs regardless of the incoming audio
       * context. Android has only two CCIDs, one for Media and one for
       * Conversational context. Even though we are not broadcasting
       * Conversational streams, some PTS test cases wants multiple CCIDs.
       */
      if (stack_config_get_interface()->get_pts_force_le_audio_multiple_contexts_metadata()) {
        context_type = LeAudioContextType::MEDIA | LeAudioContextType::CONVERSATIONAL;
        auto stream_context_vec =
                ltv.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext);
        if (stream_context_vec) {
          auto pp = stream_context_vec.value().data();
          if (stream_context_vec.value().size() < 2) {
            log::error("stream_context_vec.value() size < 2");
            return;
          }
          UINT16_TO_STREAM(pp, context_type.value());
        }
      }

      auto stream_context_vec =
              ltv.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext);
      if (stream_context_vec) {
        auto pp = stream_context_vec.value().data();
        if (stream_context_vec.value().size() < 2) {
          log::error("stream_context_vec.value() size < 2");
          return;
        }
        STREAM_TO_UINT16(context_type.value_ref(), pp);
      }

      // Append the CCID list
      auto ccid_vec = ContentControlIdKeeper::GetInstance()->GetAllCcids(context_type);
      if (!ccid_vec.empty()) {
        ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList, ccid_vec);
      }

      // Push to subgroup ltvs
      subgroup_ltvs.push_back(ltv);
    }

    if (broadcasts_[broadcast_id]->IsPublicBroadcast()) {
      // Only update broadcast name and public metadata if current broadcast is
      // public Otherwise ignore those fields
      bool is_public_metadata_valid;
      LeAudioLtvMap public_ltv = LeAudioLtvMap::Parse(
              public_metadata.data(), public_metadata.size(), is_public_metadata_valid);
      if (!is_public_metadata_valid) {
        log::error("Invalid public metadata provided.");
        return;
      }

      if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        // Append the Audio Active State
        bool audio_active_state =
                (audio_state_ == AudioState::ACTIVE) &&
                (broadcasts_[broadcast_id]->GetState() == BroadcastStateMachine::State::STREAMING);
        public_ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState,
                       audio_active_state);
      }

      PublicBroadcastAnnouncementData pb_announcement = preparePublicAnnouncement(
              broadcasts_[broadcast_id]->GetPublicBroadcastAnnouncement().features, public_ltv);

      broadcasts_[broadcast_id]->UpdatePublicBroadcastAnnouncement(broadcast_id, broadcast_name,
                                                                   pb_announcement);
    }

    auto& subgroup_configs = broadcasts_[broadcast_id]->GetCodecConfig();
    BasicAudioAnnouncementData announcement =
            prepareBasicAnnouncement(subgroup_configs, subgroup_ltvs);

    broadcasts_[broadcast_id]->UpdateBroadcastAnnouncement(std::move(announcement));
  }

  /* Choose the dominating audio context when multiple contexts are mixed */
  LeAudioContextType ChooseConfigurationContextType(AudioContexts audio_contexts) {
    log::debug("Got contexts={}", bluetooth::common::ToString(audio_contexts));

    /* Prioritize the most common use cases. */
    if (audio_contexts.any()) {
      LeAudioContextType context_priority_list[] = {
              LeAudioContextType::LIVE,          LeAudioContextType::GAME,
              LeAudioContextType::MEDIA,         LeAudioContextType::EMERGENCYALARM,
              LeAudioContextType::ALERTS,        LeAudioContextType::INSTRUCTIONAL,
              LeAudioContextType::NOTIFICATIONS, LeAudioContextType::SOUNDEFFECTS,
      };
      for (auto ct : context_priority_list) {
        if (audio_contexts.test(ct)) {
          log::debug("Selecting configuration context type: {}", ToString(ct));
          return ct;
        }
      }
    }

    auto fallback_config = LeAudioContextType::MEDIA;
    log::debug("Selecting configuration context type: {}", ToString(fallback_config));
    return fallback_config;
  }

  void CreateAudioBroadcast(bool is_public, const std::string& broadcast_name,
                            const std::optional<bluetooth::le_audio::BroadcastCode>& broadcast_code,
                            const std::vector<uint8_t>& public_metadata,
                            const std::vector<uint8_t>& subgroup_quality,
                            const std::vector<std::vector<uint8_t>>& subgroup_metadata) override {
    uint8_t public_features = 0;
    LeAudioLtvMap public_ltv;
    std::vector<LeAudioLtvMap> subgroup_ltvs;

    if (broadcast_code && std::all_of(broadcast_code->begin(), broadcast_code->end(),
                                      [](uint8_t byte) { return byte == 0xFF; })) {
      // As suggested by BASS ES-23366, all 0xFF broadcast code should be avoided for security
      log::error("Invalid all 0xFF broadcast code provided.");
      callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
      return;
    }

    if (queued_create_broadcast_request_) {
      log::error("Not processed yet queued broadcast");
      callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
      return;
    }

    if (is_public) {
      // Prepare public broadcast announcement format
      bool is_metadata_valid;
      public_ltv = LeAudioLtvMap::Parse(public_metadata.data(), public_metadata.size(),
                                        is_metadata_valid);
      if (!is_metadata_valid) {
        log::error("Invalid metadata provided.");
        callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
        return;
      }

      if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        // Append the Audio Active State
        public_ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState, false);
      }
      // Prepare public features byte
      // bit 0 Encryption broadcast stream encrypted or not
      // bit 1 Standard quality audio configuration present or not
      // bit 2 High quality audio configuration present or not
      // bit 3-7 RFU
      public_features = static_cast<uint8_t>(broadcast_code ? 1 : 0);
    }

    auto broadcast_id = available_broadcast_ids_.back();
    available_broadcast_ids_.pop_back();
    if (available_broadcast_ids_.size() == 0) {
      GenerateBroadcastIds();
    }

    auto context_type = AudioContexts(LeAudioContextType::MEDIA);

    /* Adds multiple contexts and CCIDs regardless of the incoming audio
     * context. Android has only two CCIDs, one for Media and one for
     * Conversational context. Even though we are not broadcasting
     * Conversational streams, some PTS test cases wants multiple CCIDs.
     */
    if (stack_config_get_interface()->get_pts_force_le_audio_multiple_contexts_metadata()) {
      context_type = LeAudioContextType::MEDIA | LeAudioContextType::CONVERSATIONAL;
    }

    for (const uint8_t quality : subgroup_quality) {
      if (quality == bluetooth::le_audio::QUALITY_STANDARD) {
        public_features |= bluetooth::le_audio::kLeAudioQualityStandard;
      } else if (quality == bluetooth::le_audio::QUALITY_HIGH) {
        public_features |= bluetooth::le_audio::kLeAudioQualityHigh;
      }
    }

    for (const std::vector<uint8_t>& metadata : subgroup_metadata) {
      /* Prepare the announcement format */
      bool is_metadata_valid;
      auto ltv = LeAudioLtvMap::Parse(metadata.data(), metadata.size(), is_metadata_valid);
      if (!is_metadata_valid) {
        log::error("Invalid metadata provided.");
        callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
        return;
      }

      if (stack_config_get_interface()->get_pts_force_le_audio_multiple_contexts_metadata()) {
        auto stream_context_vec =
                ltv.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext);
        if (stream_context_vec) {
          if (stream_context_vec.value().size() < 2) {
            log::error("kLeAudioMetadataTypeStreamingAudioContext size < 2");
            callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
            return;
          }
          auto pp = stream_context_vec.value().data();
          UINT16_TO_STREAM(pp, context_type.value());
        }
      }

      auto stream_context_vec =
              ltv.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext);
      if (stream_context_vec) {
        if (stream_context_vec.value().size() < 2) {
          log::error("kLeAudioMetadataTypeStreamingAudioContext size < 2");
          callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
          return;
        }

        auto pp = stream_context_vec.value().data();
        STREAM_TO_UINT16(context_type.value_ref(), pp);
      }

      // Append the CCID list
      auto ccid_vec = ContentControlIdKeeper::GetInstance()->GetAllCcids(context_type);
      if (!ccid_vec.empty()) {
        ltv.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList, ccid_vec);
      }

      // Push to subgroup ltvs
      subgroup_ltvs.push_back(ltv);
    }

    // Prepare the configuration requirements for each subgroup.
    // Note: For now, each subgroup contains exactly the same content, but
    // differs in codec configuration.
    CodecManager::BroadcastConfigurationRequirements requirements;
    for (auto& idx : subgroup_quality) {
      requirements.subgroup_quality.push_back({ChooseConfigurationContextType(context_type), idx});
    }

    if (!le_audio_source_hal_client_) {
      le_audio_source_hal_client_ = LeAudioSourceAudioHalClient::AcquireBroadcast();
      if (!le_audio_source_hal_client_) {
        log::error("Could not acquire le audio");
        return;
      }
      auto result = CodecManager::GetInstance()->UpdateActiveBroadcastAudioHalClient(
              le_audio_source_hal_client_.get(), true);
      log::assert_that(result, "Could not update session in codec manager");
    }

    auto config = CodecManager::GetInstance()->GetBroadcastConfig(requirements);
    if (!config) {
      log::error("No valid broadcast offload config");
      callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
      return;
    }

    if (public_features & bluetooth::le_audio::kLeAudioQualityHigh &&
        config->GetSamplingFrequencyHzMax() < 48000) {
      log::warn("Preferred quality isn't supported. Fallback to standard audio quality");
      public_features &= (0xFFFF & ~bluetooth::le_audio::kLeAudioQualityHigh);
      public_features |= bluetooth::le_audio::kLeAudioQualityStandard;
    }

    BroadcastStateMachineConfig msg = {
            .is_public = is_public,
            .broadcast_id = broadcast_id,
            .broadcast_name = broadcast_name,
            .streaming_phy = GetStreamingPhy(),
            .config = *config,
            .announcement = prepareBasicAnnouncement(config->subgroups, subgroup_ltvs),
            .broadcast_code = std::move(broadcast_code)};
    if (is_public) {
      msg.public_announcement = preparePublicAnnouncement(public_features, public_ltv);
    }

    /* Prepare Broadcast audio session */
    if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
      const auto& broadcast_config = msg.config;
      auto is_started = instance->le_audio_source_hal_client_->Start(
              broadcast_config.GetAudioHalClientConfig(), &audio_receiver_);
      callbacks_->OnBroadcastAudioSessionCreated(is_started);
      if (!is_started) {
        log::error("Broadcast audio session can't be started");
        callbacks_->OnBroadcastCreated(broadcast_id, false);
        return;
      }
    }

    // If there is ongoing ISO traffic, it might be a unicast stream
    if (is_iso_running_) {
      log::info("Iso is still active. Queueing broadcast creation for later.");
      if (queued_create_broadcast_request_) {
        log::warn("Already queued. Updating queued broadcast creation with the new configuration.");
      }
      queued_create_broadcast_request_ = std::move(msg);
      return;
    }

    InstantiateBroadcast(std::move(msg));
  }

  void InstantiateBroadcast(BroadcastStateMachineConfig msg) {
    log::info("CreateAudioBroadcast");

    /* Put the new broadcast on the initialization queue, notify the error and
     * drop the pending broadcast data if init fails.
     */
    pending_broadcasts_.push_back(BroadcastStateMachine::CreateInstance(std::move(msg)));
    if (!pending_broadcasts_.back()->Initialize()) {
      pending_broadcasts_.pop_back();
      callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false);
    }
  }

  void SuspendAudioBroadcast(uint32_t broadcast_id) override {
    log::info("broadcast_id={}", broadcast_id);

    if (broadcasts_.count(broadcast_id) != 0) {
      if (!com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        log::info("Stopping AudioHalClient");
        if (le_audio_source_hal_client_) {
          le_audio_source_hal_client_->Stop();
        }
      }

      /* Block AF requests to resume/suspend stream */
      audio_state_ = AudioState::STOPPED;
      broadcasts_[broadcast_id]->SetMuted(true);
      broadcasts_[broadcast_id]->ProcessMessage(BroadcastStateMachine::Message::SUSPEND, nullptr);
    } else {
      log::error("No such broadcast_id={}", broadcast_id);
    }
  }

  static bool IsAnyoneStreaming() {
    if (!instance) {
      return false;
    }

    auto const& iter = std::find_if(
            instance->broadcasts_.cbegin(), instance->broadcasts_.cend(), [](auto const& sm) {
              return sm.second->GetState() == BroadcastStateMachine::State::STREAMING;
            });
    return iter != instance->broadcasts_.cend();
  }

  void StartAudioBroadcast(uint32_t broadcast_id) override {
    log::info("Starting broadcast_id={}", broadcast_id);

    if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
      /* Enable possibility for AF to drive stream */
      audio_state_ = AudioState::SUSPENDED;
    } else {
      if (queued_start_broadcast_request_) {
        log::error("Not processed yet start broadcast request");
        return;
      }

      if (is_iso_running_) {
        queued_start_broadcast_request_ = broadcast_id;
        return;
      }

      if (IsAnyoneStreaming()) {
        log::error("Stop the other broadcast first!");
        return;
      }

      if (broadcasts_.count(broadcast_id) != 0) {
        if (!le_audio_source_hal_client_) {
          le_audio_source_hal_client_ = LeAudioSourceAudioHalClient::AcquireBroadcast();
          if (!le_audio_source_hal_client_) {
            log::error("Could not acquire le audio");
            return;
          }

          auto result = CodecManager::GetInstance()->UpdateActiveBroadcastAudioHalClient(
                  le_audio_source_hal_client_.get(), true);
          log::assert_that(result, "Could not update session in codec manager");
        }

        broadcasts_[broadcast_id]->ProcessMessage(BroadcastStateMachine::Message::START, nullptr);
        bluetooth::le_audio::MetricsCollector::Get()->OnBroadcastStateChanged(true);
      } else {
        log::error("No such broadcast_id={}", broadcast_id);
      }
    }
  }

  void StopAudioBroadcast(uint32_t broadcast_id) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      log::error("no such broadcast_id={}", broadcast_id);
      return;
    }

    log::info("Stopping AudioHalClient, broadcast_id={}", broadcast_id);

    if (le_audio_source_hal_client_) {
      le_audio_source_hal_client_->Stop();
    }
    audio_state_ = AudioState::SUSPENDED;
    broadcasts_[broadcast_id]->SetMuted(true);
    broadcasts_[broadcast_id]->ProcessMessage(BroadcastStateMachine::Message::STOP, nullptr);
    bluetooth::le_audio::MetricsCollector::Get()->OnBroadcastStateChanged(false);
  }

  void DestroyAudioBroadcast(uint32_t broadcast_id) override {
    log::info("Destroying broadcast_id={}", broadcast_id);
    broadcasts_.erase(broadcast_id);

    if (le_audio_source_hal_client_) {
      le_audio_source_hal_client_->Stop();
    }

    if (broadcasts_.empty() && le_audio_source_hal_client_) {
      auto result = CodecManager::GetInstance()->UpdateActiveBroadcastAudioHalClient(
              le_audio_source_hal_client_.get(), false);
      log::assert_that(result, "Could not update session in codec manager");
      le_audio_source_hal_client_.reset();
    }
  }

  std::optional<bluetooth::le_audio::BroadcastMetadata> GetBroadcastMetadataOpt(
          bluetooth::le_audio::BroadcastId broadcast_id) {
    bluetooth::le_audio::BroadcastMetadata metadata;
    for (auto const& kv_it : broadcasts_) {
      if (kv_it.second->GetBroadcastId() == broadcast_id) {
        metadata.is_public = kv_it.second->IsPublicBroadcast();
        metadata.broadcast_id = kv_it.second->GetBroadcastId();
        metadata.broadcast_name = kv_it.second->GetBroadcastName();
        metadata.adv_sid = kv_it.second->GetAdvertisingSid();
        metadata.pa_interval = kv_it.second->GetPaInterval();
        metadata.addr = kv_it.second->GetOwnAddress();
        metadata.addr_type = kv_it.second->GetOwnAddressType();
        metadata.broadcast_code = kv_it.second->GetBroadcastCode();
        metadata.basic_audio_announcement = kv_it.second->GetBroadcastAnnouncement();
        metadata.public_announcement = kv_it.second->GetPublicBroadcastAnnouncement();
        return metadata;
      }
    }
    return std::nullopt;
  }

  void GetBroadcastMetadata(uint32_t broadcast_id) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      log::error("No such broadcast_id={}", broadcast_id);
      return;
    }

    auto meta = GetBroadcastMetadataOpt(broadcast_id);
    if (!meta) {
      log::error("No metadata for broadcast_id={}", broadcast_id);
      return;
    }
    callbacks_->OnBroadcastMetadataChanged(broadcast_id, std::move(meta.value()));
  }

  void GetAllBroadcastStates(void) override {
    for (auto const& kv_it : broadcasts_) {
      callbacks_->OnBroadcastStateChanged(
              kv_it.second->GetBroadcastId(),
              static_cast<bluetooth::le_audio::BroadcastState>(kv_it.second->GetState()));
    }
  }

  void IsValidBroadcast(uint32_t broadcast_id, uint8_t addr_type, RawAddress addr,
                        base::Callback<void(uint8_t /* broadcast_id */, uint8_t /* addr_type */,
                                            RawAddress /* addr */, bool /* is_local */)>
                                cb) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      log::error("No such broadcast_id={}", broadcast_id);
      std::move(cb).Run(broadcast_id, addr_type, addr, false);
      return;
    }

    broadcasts_[broadcast_id]->RequestOwnAddress(base::Bind(
            [](uint32_t broadcast_id, uint8_t req_address_type, RawAddress req_address,
               base::Callback<void(uint8_t /* broadcast_id */, uint8_t /* addr_type */,
                                   RawAddress /* addr */, bool /* is_local */)>
                       cb,
               uint8_t rcv_address_type, RawAddress rcv_address) {
              bool is_local =
                      (req_address_type == rcv_address_type) && (req_address == rcv_address);
              std::move(cb).Run(broadcast_id, req_address_type, req_address, is_local);
            },
            broadcast_id, addr_type, addr, std::move(cb)));
  }

  void SetStreamingPhy(uint8_t phy) override { current_phy_ = phy; }

  uint8_t GetStreamingPhy(void) const override { return current_phy_; }

  BroadcastId BroadcastIdFromBigHandle(uint8_t big_handle) const {
    auto pair_it =
            std::find_if(broadcasts_.begin(), broadcasts_.end(), [big_handle](auto const& entry) {
              return entry.second->GetAdvertisingSid() == big_handle;
            });
    if (pair_it != broadcasts_.end()) {
      return pair_it->second->GetBroadcastId();
    }
    return bluetooth::le_audio::kBroadcastIdInvalid;
  }

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t big_handle) override {
    auto broadcast_id = BroadcastIdFromBigHandle(big_handle);
    log::assert_that(broadcasts_.count(broadcast_id) != 0,
                     "assert failed: broadcasts_.count(broadcast_id) != 0");
    broadcasts_[broadcast_id]->OnSetupIsoDataPath(status, conn_handle);
  }

  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t big_handle) override {
    auto broadcast_id = BroadcastIdFromBigHandle(big_handle);
    log::assert_that(broadcasts_.count(broadcast_id) != 0,
                     "assert failed: broadcasts_.count(broadcast_id) != 0");
    broadcasts_[broadcast_id]->OnRemoveIsoDataPath(status, conn_handle);
  }

  void OnBigEvent(uint8_t event, void* data) override {
    switch (event) {
      case bluetooth::hci::iso_manager::kIsoEventBigOnCreateCmpl: {
        auto* evt = static_cast<big_create_cmpl_evt*>(data);
        auto broadcast_id = BroadcastIdFromBigHandle(evt->big_id);
        log::assert_that(broadcasts_.count(broadcast_id) != 0,
                         "assert failed: broadcasts_.count(broadcast_id) != 0");
        broadcasts_[broadcast_id]->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT, evt);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventBigOnTerminateCmpl: {
        auto* evt = static_cast<big_terminate_cmpl_evt*>(data);
        auto broadcast_id = BroadcastIdFromBigHandle(evt->big_id);
        log::assert_that(broadcasts_.count(broadcast_id) != 0,
                         "assert failed: broadcasts_.count(broadcast_id) != 0");
        broadcasts_[broadcast_id]->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT, evt);
        if (!com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
          auto result = CodecManager::GetInstance()->UpdateActiveBroadcastAudioHalClient(
                  le_audio_source_hal_client_.get(), false);
          log::assert_that(result, "Could not update session in codec manager");
          le_audio_source_hal_client_.reset();
        }
      } break;
      default:
        log::error("Invalid event={}", event);
    }
  }

  void IsoTrafficEventCb(bool is_active) {
    is_iso_running_ = is_active;
    log::info("is_iso_running: {}", is_iso_running_);
    if (!is_iso_running_) {
      if (!com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        if (queued_start_broadcast_request_) {
          auto broadcast_id = *queued_start_broadcast_request_;
          queued_start_broadcast_request_ = std::nullopt;

          log::info("Start queued broadcast.");
          StartAudioBroadcast(broadcast_id);
        }
      } else {
        // If audio resumes before ISO release, trigger broadcast start
        if (audio_state_ == AudioState::ACTIVE) {
          cancelBroadcastTimers();
          UpdateAudioActiveStateInPublicAnnouncement();

          for (auto& broadcast_pair : broadcasts_) {
            auto& broadcast = broadcast_pair.second;
            broadcast->ProcessMessage(BroadcastStateMachine::Message::START, nullptr);
          }
        }
      }

      if (queued_create_broadcast_request_) {
        auto broadcast_msg = std::move(*queued_create_broadcast_request_);
        queued_create_broadcast_request_ = std::nullopt;

        log::info("Create queued broadcast.");
        InstantiateBroadcast(std::move(broadcast_msg));
      }
    }
  }

  void Dump(int fd) {
    std::stringstream stream;

    stream << "    Number of broadcasts: " << broadcasts_.size() << "\n";
    for (auto& broadcast_pair : broadcasts_) {
      auto& broadcast = broadcast_pair.second;
      if (broadcast) {
        stream << *broadcast;
      }
    }

    dprintf(fd, "%s", stream.str().c_str());
  }

private:
  void SuspendAudioBroadcasts() {
    log::info("");
    for (auto& broadcast_pair : broadcasts_) {
      auto& broadcast = broadcast_pair.second;
      broadcast->SetMuted(true);
      broadcast->ProcessMessage(BroadcastStateMachine::Message::SUSPEND, nullptr);
    }
  }

  void StopAudioBroadcasts() {
    log::info("");
    if (le_audio_source_hal_client_) {
      le_audio_source_hal_client_->Stop();
    }
    for (auto& broadcast_pair : broadcasts_) {
      auto& broadcast = broadcast_pair.second;
      broadcast->SetMuted(true);
      broadcast->ProcessMessage(BroadcastStateMachine::Message::STOP, nullptr);
    }
    bluetooth::le_audio::MetricsCollector::Get()->OnBroadcastStateChanged(false);
  }

  void setBroadcastTimers() {
    log::info(" Started");
    alarm_set_on_mloop(
            big_terminate_timer_, kBigTerminateTimeoutMs,
            [](void*) {
              if (instance) {
                instance->SuspendAudioBroadcasts();
              }
            },
            nullptr);

    alarm_set_on_mloop(
            broadcast_stop_timer_, kBroadcastStopTimeoutMs,
            [](void*) {
              if (instance) {
                instance->StopAudioBroadcasts();
              }
            },
            nullptr);
  }

  void cancelBroadcastTimers() {
    log::info("");
    alarm_cancel(big_terminate_timer_);
    alarm_cancel(broadcast_stop_timer_);
  }

  static class BroadcastStateMachineCallbacks : public IBroadcastStateMachineCallbacks {
    void OnStateMachineCreateStatus(uint32_t broadcast_id, bool initialized) override {
      auto pending_broadcast = std::find_if(
              instance->pending_broadcasts_.begin(), instance->pending_broadcasts_.end(),
              [broadcast_id](auto& sm) { return sm->GetBroadcastId() == broadcast_id; });
      log::assert_that(pending_broadcast != instance->pending_broadcasts_.end(),
                       "assert failed: pending_broadcast != "
                       "instance->pending_broadcasts_.end()");
      log::assert_that(instance->broadcasts_.count(broadcast_id) == 0,
                       "assert failed: instance->broadcasts_.count(broadcast_id) == 0");

      if (initialized) {
        const uint32_t broadcast_id = (*pending_broadcast)->GetBroadcastId();
        log::info("broadcast_id={} state={}", broadcast_id,
                  ToString((*pending_broadcast)->GetState()));

        instance->broadcasts_[broadcast_id] = std::move(*pending_broadcast);
      } else {
        log::error("Failed creating broadcast!");
      }
      instance->pending_broadcasts_.erase(pending_broadcast);
      instance->callbacks_->OnBroadcastCreated(broadcast_id, initialized);
    }

    void OnStateMachineDestroyed(uint32_t broadcast_id) override {
      /* This is a special case when state machine destructor calls this
       * callback. It may happen during the Cleanup() call when all state
       * machines are erased and instance can already be set to null to avoid
       * unnecessary calls.
       */
      if (instance) {
        instance->callbacks_->OnBroadcastDestroyed(broadcast_id);
      }
    }

    static int getStreamerCount() {
      return std::count_if(
              instance->broadcasts_.begin(), instance->broadcasts_.end(), [](auto const& sm) {
                log::verbose("broadcast_id={}, state={}", sm.second->GetBroadcastId(),
                             ToString(sm.second->GetState()));
                return sm.second->GetState() == BroadcastStateMachine::State::STREAMING;
              });
    }

    void OnStateMachineEvent(uint32_t broadcast_id, BroadcastStateMachine::State state,
                             const void* /*data*/) override {
      log::info("broadcast_id={} state={}", broadcast_id, ToString(state));

      switch (state) {
        case BroadcastStateMachine::State::STOPPED:
          break;
        case BroadcastStateMachine::State::CONFIGURING:
          break;
        case BroadcastStateMachine::State::CONFIGURED:
          if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
            instance->UpdateAudioActiveStateInPublicAnnouncement();
          }
          break;
        case BroadcastStateMachine::State::ENABLING:
          break;
        case BroadcastStateMachine::State::DISABLING:
          break;
        case BroadcastStateMachine::State::STOPPING:
          break;
        case BroadcastStateMachine::State::STREAMING:
          if (getStreamerCount() == 1) {
            log::info("Starting AudioHalClient");

            if (instance->broadcasts_.count(broadcast_id) != 0) {
              const auto& broadcast = instance->broadcasts_.at(broadcast_id);
              const auto& broadcast_config = broadcast->GetBroadcastConfig();

              // Reconfigure encoder instances for the new stream requirements
              audio_receiver_.CheckAndReconfigureEncoders(broadcast_config);

              broadcast->SetMuted(false);
              if (!com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
                auto is_started = instance->le_audio_source_hal_client_->Start(
                        broadcast_config.GetAudioHalClientConfig(), &audio_receiver_);
                if (!is_started) {
                  /* Audio Source setup failed - stop the broadcast */
                  instance->StopAudioBroadcast(broadcast_id);
                  return;
                }
              } else {
                instance->UpdateAudioActiveStateInPublicAnnouncement();
              }
            }
          }
          break;
      };

      instance->callbacks_->OnBroadcastStateChanged(
              broadcast_id, static_cast<bluetooth::le_audio::BroadcastState>(state));
    }

    void OnOwnAddressResponse(uint32_t /*broadcast_id*/, uint8_t /*addr_type*/,
                              RawAddress /*addr*/) override {
      /* Not used currently */
    }

    void OnBigCreated(const std::vector<uint16_t>& conn_handle) {
      CodecManager::GetInstance()->UpdateBroadcastConnHandle(
              conn_handle,
              std::bind(&LeAudioSourceAudioHalClient::UpdateBroadcastAudioConfigToHal,
                        instance->le_audio_source_hal_client_.get(), std::placeholders::_1));

      if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        instance->le_audio_source_hal_client_->ConfirmStreamingRequest();
      }
    }

    void OnAnnouncementUpdated(uint32_t broadcast_id) {
      instance->GetBroadcastMetadata(broadcast_id);
    }
  } state_machine_callbacks_;

  static class BroadcastAdvertisingCallbacks : public ::AdvertisingCallbacks {
    void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id, int8_t tx_power,
                                 uint8_t status) {
      if (!instance) {
        return;
      }

      if (reg_id == BroadcastStateMachine::kLeAudioBroadcastRegId &&
          !instance->pending_broadcasts_.empty()) {
        instance->pending_broadcasts_.back()->OnCreateAnnouncement(advertiser_id, tx_power, status);
      } else {
        log::warn("Ignored OnAdvertisingSetStarted callback reg_id:{} advertiser_id:{}", reg_id,
                  advertiser_id);
      }
    }

    void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable, uint8_t status) {
      if (!instance) {
        return;
      }

      auto const& iter = std::find_if(instance->broadcasts_.cbegin(), instance->broadcasts_.cend(),
                                      [advertiser_id](auto const& sm) {
                                        return sm.second->GetAdvertisingSid() == advertiser_id;
                                      });
      if (iter != instance->broadcasts_.cend()) {
        iter->second->OnEnableAnnouncement(enable, status);
      } else {
        log::warn("Ignored OnAdvertisingEnabled callback advertiser_id:{}", advertiser_id);
      }
    }

    void OnAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
      if (!instance) {
        return;
      }

      auto const& iter = std::find_if(instance->broadcasts_.cbegin(), instance->broadcasts_.cend(),
                                      [advertiser_id](auto const& sm) {
                                        return sm.second->GetAdvertisingSid() == advertiser_id;
                                      });
      if (iter != instance->broadcasts_.cend()) {
        iter->second->OnUpdateAnnouncement(status);
      } else {
        log::warn("Ignored OnAdvertisingDataSet callback advertiser_id:{}", advertiser_id);
      }
    }

    void OnScanResponseDataSet(uint8_t advertiser_id, uint8_t /*status*/) {
      log::warn("Not being used, ignored OnScanResponseDataSet callback advertiser_id:{}",
                advertiser_id);
    }

    void OnAdvertisingParametersUpdated(uint8_t advertiser_id, int8_t /*tx_power*/,
                                        uint8_t /*status*/) {
      log::warn("Not being used, ignored OnAdvertisingParametersUpdated callback advertiser_id:{}",
                advertiser_id);
    }

    void OnPeriodicAdvertisingParametersUpdated(uint8_t advertiser_id, uint8_t /*status*/) {
      log::warn(
              "Not being used, ignored OnPeriodicAdvertisingParametersUpdated "
              "callback advertiser_id:{}",
              advertiser_id);
    }

    void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
      if (!instance) {
        return;
      }

      auto const& iter = std::find_if(instance->broadcasts_.cbegin(), instance->broadcasts_.cend(),
                                      [advertiser_id](auto const& sm) {
                                        return sm.second->GetAdvertisingSid() == advertiser_id;
                                      });
      if (iter != instance->broadcasts_.cend()) {
        iter->second->OnUpdateAnnouncement(status);
      } else {
        log::warn("Ignored OnPeriodicAdvertisingDataSet callback advertiser_id:{}", advertiser_id);
      }
    }

    void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool /*enable*/, uint8_t /*status*/) {
      log::warn("Not being used, ignored OnPeriodicAdvertisingEnabled callback advertiser_id:{}",
                advertiser_id);
    }

    void OnOwnAddressRead(uint8_t advertiser_id, uint8_t /*address_type*/, RawAddress /*address*/) {
      log::warn("Not being used, ignored OnOwnAddressRead callback advertiser_id:{}",
                advertiser_id);
    }
  } state_machine_adv_callbacks_;

  static class LeAudioSourceCallbacksImpl : public LeAudioSourceAudioHalClient::Callbacks {
  public:
    LeAudioSourceCallbacksImpl() = default;
    void CheckAndReconfigureEncoders(const BroadcastConfiguration& broadcast_config) {
      /* TODO: Move software codec instance management to the Codec Manager */
      if (CodecManager::GetInstance()->GetCodecLocation() == CodecLocation::ADSP) {
        return;
      }

      auto codec_config = broadcast_config.GetAudioHalClientConfig();

      /* Note: Currently we support only a single subgroup software encoding.
       * In future consider mirroring the same data in a different quality
       * subgroups.
       */
      auto const& subgroup_config = broadcast_config.subgroups.at(0);

      auto const& codec_id = subgroup_config.GetLeAudioCodecId();
      /* TODO: We should act smart and reuse current configurations */
      sw_enc_.clear();
      while (sw_enc_.size() != subgroup_config.GetNumChannelsTotal()) {
        auto codec = bluetooth::le_audio::CodecInterface::CreateInstance(codec_id);

        auto codec_status = codec->InitEncoder(codec_config, codec_config);
        if (codec_status != bluetooth::le_audio::CodecInterface::Status::STATUS_OK) {
          log::error("Channel {} codec setup failed with err: {}", (uint32_t)sw_enc_.size(),
                     codec_status);
          return;
        }

        sw_enc_.emplace_back(std::move(codec));
      }

      broadcast_config_ = broadcast_config;
    }

    static void sendBroadcastData(
            const std::unique_ptr<BroadcastStateMachine>& broadcast,
            std::vector<std::unique_ptr<bluetooth::le_audio::CodecInterface>>& encoders) {
      auto const& config = broadcast->GetBigConfig();
      if (config == std::nullopt) {
        log::error("Broadcast broadcast_id={} has no valid BIS configurations in state={}",
                   broadcast->GetBroadcastId(), ToString(broadcast->GetState()));
        return;
      }

      if (config->connection_handles.size() < encoders.size()) {
        log::error("Not enough BIS'es to broadcast all channels!");
        return;
      }

      for (uint8_t chan = 0; chan < encoders.size(); ++chan) {
        IsoManager::GetInstance()->SendIsoData(
                config->connection_handles[chan],
                (const uint8_t*)encoders[chan]->GetDecodedSamples().data(),
                encoders[chan]->GetDecodedSamples().size() * 2);
      }
    }

    virtual void OnAudioDataReady(const std::vector<uint8_t>& data) override {
      if (!instance) {
        return;
      }

      log::verbose("Received {} bytes.", data.size());

      if (instance->audio_state_ == AudioState::STOPPED) {
        log::warn("audio stopped, skip audio data ready callback");
        return;
      }

      if (!broadcast_config_.has_value() || (broadcast_config_->subgroups.size() == 0)) {
        log::error("Codec was not configured properly");
        return;
      }

      /* Note: Currently we support only a single subgroup.
       * In future consider mirroring the same data in a different quality
       * subgroups.
       */
      auto const& subgroup_config = broadcast_config_->subgroups.at(0);

      /* Constants for the channel data configuration */
      const auto num_bis = subgroup_config.GetNumBis();
      const auto bytes_per_sample = (subgroup_config.GetBitsPerSample() / 8);

      /* Prepare encoded data for all channels */
      for (uint8_t bis_idx = 0; bis_idx < num_bis; ++bis_idx) {
        auto initial_channel_offset = bis_idx * bytes_per_sample;
        sw_enc_[bis_idx]->Encode(data.data() + initial_channel_offset, num_bis,
                                 subgroup_config.GetBisOctetsPerCodecFrame(bis_idx));
      }

      /* Currently there is no way to broadcast multiple distinct streams.
       * We just receive all system sounds mixed into a one stream and each
       * broadcast gets the same data.
       */
      for (auto& broadcast_pair : instance->broadcasts_) {
        auto& broadcast = broadcast_pair.second;
        if ((broadcast->GetState() == BroadcastStateMachine::State::STREAMING) &&
            !broadcast->IsMuted()) {
          sendBroadcastData(broadcast, sw_enc_);
        }
      }
      log::verbose("All data sent.");
    }

    virtual void OnAudioSuspend(void) override {
      log::info("");
      if (!instance) {
        return;
      }

      if (instance->audio_state_ == AudioState::STOPPED) {
        log::warn("audio stopped, skip suspend request");
        return;
      }

      instance->audio_state_ = AudioState::SUSPENDED;
      if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        instance->UpdateAudioActiveStateInPublicAnnouncement();
        instance->setBroadcastTimers();
      }
    }

    virtual void OnAudioResume(void) override {
      log::info("");
      if (!instance) {
        return;
      }

      if (instance->audio_state_ == AudioState::STOPPED) {
        log::warn("audio stopped, skip resume request");
        instance->le_audio_source_hal_client_->CancelStreamingRequest();
        return;
      }

      instance->audio_state_ = AudioState::ACTIVE;
      if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
        if (instance->broadcasts_.empty()) {
          log::warn("No broadcasts are ready to resume (pending: {} broadcasts)",
                    instance->pending_broadcasts_.size());
          instance->le_audio_source_hal_client_->CancelStreamingRequest();
          return;
        }

        /* If there is ongoing ISO traffic, it might be not torn down unicast stream. Resume of
         * broadcast stream would be triggered from IsoTrafficEventCb context, once ISO would be
         * released.
         */
        if (!IsAnyoneStreaming() && instance->is_iso_running_) {
          log::debug("iso is busy, skip resume request");
          return;
        }

        instance->cancelBroadcastTimers();
        instance->UpdateAudioActiveStateInPublicAnnouncement();

        /* In case of double call of resume when broadcasts are already in streaming states */
        if (IsAnyoneStreaming()) {
          log::debug("broadcasts are already streaming");
          instance->le_audio_source_hal_client_->ConfirmStreamingRequest();
          return;
        }

        for (auto& broadcast_pair : instance->broadcasts_) {
          auto& broadcast = broadcast_pair.second;
          broadcast->ProcessMessage(BroadcastStateMachine::Message::START, nullptr);
        }
      } else {
        if (!IsAnyoneStreaming()) {
          instance->le_audio_source_hal_client_->CancelStreamingRequest();
          return;
        }

        instance->le_audio_source_hal_client_->ConfirmStreamingRequest();
      }
    }

    virtual void OnAudioMetadataUpdate(
            const std::vector<struct playback_track_metadata_v7> source_metadata,
            DsaMode /*dsa_mode*/) override {
      log::info("");
      if (!instance) {
        return;
      }

      /* TODO: Should we take supported contexts from ASCS? */
      auto contexts = GetAudioContextsFromSourceMetadata(source_metadata);
      if (contexts.any()) {
        /* NOTICE: We probably don't want to change the stream configuration
         * on each metadata change, so just update the context type metadata.
         * Since we are not able to identify individual track streams and
         * they are all mixed inside a single data stream, we will update
         * the metadata of all BIS subgroups with the same combined context.
         */
        instance->UpdateStreamingContextTypeOnAllSubgroups(contexts);
      }
    }

  private:
    std::optional<BroadcastConfiguration> broadcast_config_;
    std::vector<std::unique_ptr<bluetooth::le_audio::CodecInterface>> sw_enc_;
  } audio_receiver_;

  bluetooth::le_audio::LeAudioBroadcasterCallbacks* callbacks_;
  std::map<uint32_t, std::unique_ptr<BroadcastStateMachine>> broadcasts_;
  std::vector<std::unique_ptr<BroadcastStateMachine>> pending_broadcasts_;
  std::optional<BroadcastStateMachineConfig> queued_create_broadcast_request_;
  std::optional<uint32_t> queued_start_broadcast_request_;

  /* Some BIG params are set globally */
  uint8_t current_phy_;
  std::unique_ptr<LeAudioSourceAudioHalClient> le_audio_source_hal_client_;
  std::vector<BroadcastId> available_broadcast_ids_;

  // Current state of audio playback
  AudioState audio_state_;

  // Flag to track iso state
  bool is_iso_running_ = false;

  static constexpr uint64_t kBigTerminateTimeoutMs = 10 * 1000;
  static constexpr uint64_t kBroadcastStopTimeoutMs = 30 * 60 * 1000;
  alarm_t* big_terminate_timer_;
  alarm_t* broadcast_stop_timer_;
};

/* Static members definitions */
LeAudioBroadcasterImpl::BroadcastStateMachineCallbacks
        LeAudioBroadcasterImpl::state_machine_callbacks_;
LeAudioBroadcasterImpl::LeAudioSourceCallbacksImpl LeAudioBroadcasterImpl::audio_receiver_;
LeAudioBroadcasterImpl::BroadcastAdvertisingCallbacks
        LeAudioBroadcasterImpl::state_machine_adv_callbacks_;
} /* namespace */

void LeAudioBroadcaster::Initialize(bluetooth::le_audio::LeAudioBroadcasterCallbacks* callbacks,
                                    base::Callback<bool()> audio_hal_verifier) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  log::info("");
  if (instance) {
    log::error("Already initialized");
    return;
  }

  if (!bluetooth::shim::GetController()->SupportsBleIsochronousBroadcaster() &&
      !osi_property_get_bool("persist.bluetooth.fake_iso_support", false)) {
    log::warn("Isochronous Broadcast not supported by the controller!");
    return;
  }

  if (!std::move(audio_hal_verifier).Run()) {
    log::fatal("HAL requirements not met. Init aborted.");
  }

  IsoManager::GetInstance()->Start();

  instance = new LeAudioBroadcasterImpl(callbacks);
  /* Register HCI event handlers */
  IsoManager::GetInstance()->RegisterBigCallbacks(instance);
  /* Register for active traffic */
  IsoManager::GetInstance()->RegisterOnIsoTrafficActiveCallback([](bool is_active) {
    if (instance) {
      instance->IsoTrafficEventCb(is_active);
    }
  });
}

bool LeAudioBroadcaster::IsLeAudioBroadcasterRunning() { return instance; }

LeAudioBroadcaster* LeAudioBroadcaster::Get(void) {
  log::info("");
  log::assert_that(instance != nullptr, "assert failed: instance != nullptr");
  return instance;
}

void LeAudioBroadcaster::Stop(void) {
  log::info("");

  if (instance) {
    instance->Stop();
  }
}

void LeAudioBroadcaster::Cleanup(void) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  log::info("");

  if (instance == nullptr) {
    return;
  }

  LeAudioBroadcasterImpl* ptr = instance;
  instance = nullptr;

  ptr->CleanUp();
  delete ptr;
}

void LeAudioBroadcaster::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  dprintf(fd, "Le Audio Broadcaster:\n");
  if (instance) {
    instance->Dump(fd);
  }
  dprintf(fd, "\n");
}
