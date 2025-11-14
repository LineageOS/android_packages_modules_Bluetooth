/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <memory>

#include "audio_hal_interface/le_audio_software_host.h"
#include "include/hardware/bt_le_audio.h"
#include "rust/cxx.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace topshim {
namespace rust {

enum class BtLeAudioDirection : uint8_t;
enum class BtLeStreamStartedStatus : int32_t;
struct BtLeAudioCodecConfig;
struct BtLePcmConfig;
struct SourceMetadata;
struct SinkMetadata;

class LeAudioClientIntf {
public:
  LeAudioClientIntf(le_audio::LeAudioClientInterface* intf) : intf_(intf) {}

  void init(
      /*
LeAudioClientCallbacks* callbacks,
const std::vector<le_audio::btle_audio_codec_config_t>& offloading_preference
*/);
  void connect(RawAddress addr);
  void disconnect(RawAddress addr);
  void set_enable_state(RawAddress addr, bool enabled);
  void cleanup();
  void remove_device(RawAddress addr);
  void group_add_node(int group_id, RawAddress addr);
  void group_remove_node(int group_id, RawAddress addr);
  void group_set_active(int group_id);
  void set_codec_config_preference(int group_id, BtLeAudioCodecConfig input_codec_config,
                                   BtLeAudioCodecConfig output_codec_config);
  void set_ccid_information(int ccid, int context_type);
  void set_in_call(bool in_call);
  void send_audio_profile_preferences(int group_id, bool is_output_preference_le_audio,
                                      bool is_duplex_preference_le_audio);
  void set_unicast_monitor_mode(BtLeAudioDirection direction, bool enable);

  // interface for audio server
  bool host_start_audio_request();
  void host_stop_audio_request();
  bool peer_start_audio_request();
  void peer_stop_audio_request();
  BtLePcmConfig get_host_pcm_config();
  BtLePcmConfig get_peer_pcm_config();
  BtLeStreamStartedStatus get_host_stream_started();
  BtLeStreamStartedStatus get_peer_stream_started();
  void source_metadata_changed(::rust::Vec<SourceMetadata> metadata);
  void sink_metadata_changed(::rust::Vec<SinkMetadata> metadata);

private:
  le_audio::LeAudioClientInterface* intf_;
};

std::unique_ptr<LeAudioClientIntf> GetLeAudioClientProfile(const unsigned char* btif);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
