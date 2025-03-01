/*
 * Copyright 2019 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#include "btif_le_audio.h"

#include <bluetooth/log.h>
#include <hardware/bt_le_audio.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "bta_le_audio_api.h"
#include "btif_common.h"
#include "btif_profile_storage.h"
#include "stack/include/main_thread.h"
#include "types/raw_address.h"

using base::Bind;
using base::Unretained;
using bluetooth::le_audio::btle_audio_codec_config_t;
using bluetooth::le_audio::ConnectionState;
using bluetooth::le_audio::GroupNodeStatus;
using bluetooth::le_audio::GroupStatus;
using bluetooth::le_audio::GroupStreamStatus;
using bluetooth::le_audio::LeAudioClientCallbacks;
using bluetooth::le_audio::LeAudioClientInterface;
using bluetooth::le_audio::UnicastMonitorModeStatus;
using namespace bluetooth;

namespace {
class LeAudioClientInterfaceImpl;
std::unique_ptr<LeAudioClientInterface> leAudioInstance;
std::atomic_bool initialized = false;

class LeAudioClientInterfaceImpl : public LeAudioClientInterface, public LeAudioClientCallbacks {
  ~LeAudioClientInterfaceImpl() = default;

  void OnInitialized(void) {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnInitialized, Unretained(callbacks)));
  }

  void OnConnectionState(ConnectionState state, const RawAddress& address) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnConnectionState, Unretained(callbacks), state,
                          address));
  }

  void OnGroupStatus(int group_id, GroupStatus group_status) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnGroupStatus, Unretained(callbacks), group_id,
                          group_status));
  }

  void OnGroupNodeStatus(const RawAddress& addr, int group_id,
                         GroupNodeStatus node_status) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnGroupNodeStatus, Unretained(callbacks), addr,
                          group_id, node_status));
  }

  void OnAudioConf(uint8_t direction, int group_id,
                   std::optional<std::bitset<32>> snk_audio_location,
                   std::optional<std::bitset<32>> src_audio_location,
                   uint16_t avail_cont) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnAudioConf, Unretained(callbacks), direction,
                          group_id, snk_audio_location, src_audio_location, avail_cont));
  }

  void OnSinkAudioLocationAvailable(const RawAddress& address,
                                    std::optional<std::bitset<32>> snk_audio_location) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnSinkAudioLocationAvailable,
                          Unretained(callbacks), address, snk_audio_location));
  }

  void OnAudioLocalCodecCapabilities(
          std::vector<btle_audio_codec_config_t> local_input_capa_codec_conf,
          std::vector<btle_audio_codec_config_t> local_output_capa_codec_conf) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnAudioLocalCodecCapabilities,
                          Unretained(callbacks), local_input_capa_codec_conf,
                          local_output_capa_codec_conf));
  }

  void OnAudioGroupCurrentCodecConf(int group_id, btle_audio_codec_config_t input_codec_conf,
                                    btle_audio_codec_config_t output_codec_conf) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnAudioGroupCurrentCodecConf,
                          Unretained(callbacks), group_id, input_codec_conf, output_codec_conf));
  }

  void OnAudioGroupSelectableCodecConf(
          int group_id, std::vector<btle_audio_codec_config_t> input_selectable_codec_conf,
          std::vector<btle_audio_codec_config_t> output_selectable_codec_conf) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnAudioGroupSelectableCodecConf,
                          Unretained(callbacks), group_id, input_selectable_codec_conf,
                          output_selectable_codec_conf));
  }

  void OnHealthBasedRecommendationAction(
          const RawAddress& address,
          bluetooth::le_audio::LeAudioHealthBasedAction action) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnHealthBasedRecommendationAction,
                          Unretained(callbacks), address, action));
  }

  void OnHealthBasedGroupRecommendationAction(
          int group_id, bluetooth::le_audio::LeAudioHealthBasedAction action) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnHealthBasedGroupRecommendationAction,
                          Unretained(callbacks), group_id, action));
  }

  void OnUnicastMonitorModeStatus(uint8_t direction, UnicastMonitorModeStatus status) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnUnicastMonitorModeStatus,
                          Unretained(callbacks), direction, status));
  }

  void OnGroupStreamStatus(int group_id, GroupStreamStatus group_stream_status) override {
    do_in_jni_thread(Bind(&LeAudioClientCallbacks::OnGroupStreamStatus, Unretained(callbacks),
                          group_id, group_stream_status));
  }

  void Initialize(LeAudioClientCallbacks* callbacks,
                  const std::vector<btle_audio_codec_config_t>& offloading_preference) override {
    this->callbacks = callbacks;

    for (auto codec : offloading_preference) {
      log::info("supported codec: {}", codec.ToString());
    }

    do_in_main_thread(
            Bind(&LeAudioClient::Initialize, this,
                 jni_thread_wrapper(Bind(&btif_storage_load_bonded_leaudio)),
                 base::Bind([]() -> bool { return LeAudioHalVerifier::SupportsLeAudio(); }),
                 offloading_preference));

    /* It might be not yet initialized, but setting this flag here is safe,
     * because other calls will check this and the native instance
     */
    initialized = true;
  }

  void Cleanup(void) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    initialized = false;

    do_in_main_thread(Bind(&LeAudioClient::Cleanup));
  }

  void RemoveDevice(const RawAddress& address) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");

      do_in_jni_thread(Bind(&btif_storage_remove_leaudio, address));
      return;
    }

    do_in_main_thread(
            Bind(&LeAudioClient::RemoveDevice, Unretained(LeAudioClient::Get()), address));

    do_in_jni_thread(Bind(&btif_storage_remove_leaudio, address));
  }

  void Connect(const RawAddress& address) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::Connect, Unretained(LeAudioClient::Get()), address));
  }

  void Disconnect(const RawAddress& address) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::Disconnect, Unretained(LeAudioClient::Get()), address));
  }

  void SetEnableState(const RawAddress& address, bool enabled) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::SetEnableState, Unretained(LeAudioClient::Get()),
                           address, enabled));
  }

  void GroupAddNode(const int group_id, const RawAddress& address) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::GroupAddNode, Unretained(LeAudioClient::Get()), group_id,
                           address));
  }

  void GroupRemoveNode(const int group_id, const RawAddress& address) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::GroupRemoveNode, Unretained(LeAudioClient::Get()),
                           group_id, address));
  }

  void GroupSetActive(const int group_id) override {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(
            Bind(&LeAudioClient::GroupSetActive, Unretained(LeAudioClient::Get()), group_id));
  }

  void SetCodecConfigPreference(int group_id, btle_audio_codec_config_t input_codec_config,
                                btle_audio_codec_config_t output_codec_config) {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }
    do_in_main_thread(Bind(&LeAudioClient::SetCodecConfigPreference,
                           Unretained(LeAudioClient::Get()), group_id, input_codec_config,
                           output_codec_config));
  }

  void SetCcidInformation(int ccid, int context_type) {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::SetCcidInformation, Unretained(LeAudioClient::Get()),
                           ccid, context_type));
  }

  void SetInCall(bool in_call) {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::SetInCall, Unretained(LeAudioClient::Get()), in_call));
  }

  void SetUnicastMonitorMode(uint8_t direction, bool enable) {
    log::verbose("enable: {}", enable);
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "Unicast monitoring mode set ignored, due to already"
              " started cleanup procedure or service being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::SetUnicastMonitorMode, Unretained(LeAudioClient::Get()),
                           direction, enable));
  }

  void SendAudioProfilePreferences(int group_id, bool is_output_preference_le_audio,
                                   bool is_duplex_preference_le_audio) {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&LeAudioClient::SendAudioProfilePreferences,
                           Unretained(LeAudioClient::Get()), group_id,
                           is_output_preference_le_audio, is_duplex_preference_le_audio));
  }

  void SetGroupAllowedContextMask(int group_id, int sink_context_types, int source_context_types) {
    if (!initialized || !LeAudioClient::IsLeAudioClientRunning()) {
      log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    log::info("group_id: {}, sink context types: {}, source context types: {}", group_id,
              sink_context_types, source_context_types);

    do_in_main_thread(Bind(&LeAudioClient::SetGroupAllowedContextMask,
                           Unretained(LeAudioClient::Get()), group_id, sink_context_types,
                           source_context_types));
  }

private:
  LeAudioClientCallbacks* callbacks;
};

} /* namespace */

LeAudioClientInterface* btif_le_audio_get_interface() {
  if (!leAudioInstance) {
    leAudioInstance.reset(new LeAudioClientInterfaceImpl());
  }

  return leAudioInstance.get();
}
