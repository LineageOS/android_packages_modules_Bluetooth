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

#include "gd/rust/topshim/le_audio/le_audio_shim.h"

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>

#include <vector>

#include "bta/le_audio/le_audio_types.h"
#include "src/profiles/le_audio.rs.h"
#include "types/raw_address.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {
namespace internal {

static LeAudioClientIntf* g_lea_client_if;

static uint8_t from_rust_btle_audio_direction(BtLeAudioDirection direction) {
  switch (direction) {
    case BtLeAudioDirection::Sink:
      return le_audio::types::kLeAudioDirectionSink;
    case BtLeAudioDirection::Source:
      return le_audio::types::kLeAudioDirectionSource;
    case BtLeAudioDirection::Both:
      return le_audio::types::kLeAudioDirectionBoth;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return 0;
}

static le_audio::btle_audio_codec_config_t from_rust_btle_audio_codec_config(
        BtLeAudioCodecConfig codec_config) {
  switch (codec_config.codec_type) {
    case static_cast<int>(BtLeAudioCodecIndex::SrcLc3):
      return le_audio::btle_audio_codec_config_t{
              .codec_type = le_audio::btle_audio_codec_index_t::LE_AUDIO_CODEC_INDEX_SOURCE_LC3};
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return le_audio::btle_audio_codec_config_t{};
}

static BtLeAudioCodecConfig to_rust_btle_audio_codec_config(
        le_audio::btle_audio_codec_config_t codec_config) {
  switch (codec_config.codec_type) {
    case le_audio::btle_audio_codec_index_t::LE_AUDIO_CODEC_INDEX_SOURCE_LC3:
      return BtLeAudioCodecConfig{.codec_type = static_cast<int>(BtLeAudioCodecIndex::SrcLc3)};
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioCodecConfig{};
}

static ::rust::vec<BtLeAudioCodecConfig> to_rust_btle_audio_codec_config_vec(
        std::vector<le_audio::btle_audio_codec_config_t> codec_configs) {
  ::rust::vec<BtLeAudioCodecConfig> rconfigs;
  for (auto c : codec_configs) {
    rconfigs.push_back(to_rust_btle_audio_codec_config(c));
  }
  return rconfigs;
}

static BtLeAudioConnectionState to_rust_btle_audio_connection_state(
        le_audio::ConnectionState state) {
  switch (state) {
    case le_audio::ConnectionState::DISCONNECTED:
      return BtLeAudioConnectionState::Disconnected;
    case le_audio::ConnectionState::CONNECTING:
      return BtLeAudioConnectionState::Connecting;
    case le_audio::ConnectionState::CONNECTED:
      return BtLeAudioConnectionState::Connected;
    case le_audio::ConnectionState::DISCONNECTING:
      return BtLeAudioConnectionState::Disconnecting;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioConnectionState{};
}

static BtLeAudioGroupStatus to_rust_btle_audio_group_status(le_audio::GroupStatus status) {
  switch (status) {
    case le_audio::GroupStatus::INACTIVE:
      return BtLeAudioGroupStatus::Inactive;
    case le_audio::GroupStatus::ACTIVE:
      return BtLeAudioGroupStatus::Active;
    case le_audio::GroupStatus::TURNED_IDLE_DURING_CALL:
      return BtLeAudioGroupStatus::TurnedIdleDuringCall;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioGroupStatus{};
}

static BtLeAudioGroupNodeStatus to_rust_btle_audio_group_node_status(
        le_audio::GroupNodeStatus status) {
  switch (status) {
    case le_audio::GroupNodeStatus::ADDED:
      return BtLeAudioGroupNodeStatus::Added;
    case le_audio::GroupNodeStatus::REMOVED:
      return BtLeAudioGroupNodeStatus::Removed;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioGroupNodeStatus{};
}

static BtLeAudioUnicastMonitorModeStatus to_rust_btle_audio_unicast_monitor_mode_status(
        le_audio::UnicastMonitorModeStatus status) {
  switch (status) {
    case le_audio::UnicastMonitorModeStatus::STREAMING_REQUESTED:
      return BtLeAudioUnicastMonitorModeStatus::StreamingRequested;
    case le_audio::UnicastMonitorModeStatus::STREAMING:
      return BtLeAudioUnicastMonitorModeStatus::Streaming;
    case le_audio::UnicastMonitorModeStatus::STREAMING_SUSPENDED:
      return BtLeAudioUnicastMonitorModeStatus::StreamingSuspended;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioUnicastMonitorModeStatus{};
}

static BtLeAudioDirection to_rust_btle_audio_direction(uint8_t direction) {
  switch (direction) {
    case le_audio::types::kLeAudioDirectionSink:
      return BtLeAudioDirection::Sink;
    case le_audio::types::kLeAudioDirectionSource:
      return BtLeAudioDirection::Source;
    case le_audio::types::kLeAudioDirectionBoth:
      return BtLeAudioDirection::Both;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioDirection{};
}

static BtLeAudioGroupStreamStatus to_rust_btle_audio_group_stream_status(
        le_audio::GroupStreamStatus status) {
  switch (status) {
    case le_audio::GroupStreamStatus::IDLE:
      return BtLeAudioGroupStreamStatus::Idle;
    case le_audio::GroupStreamStatus::STREAMING:
      return BtLeAudioGroupStreamStatus::Streaming;
    case le_audio::GroupStreamStatus::RELEASING:
      return BtLeAudioGroupStreamStatus::Releasing;
    case le_audio::GroupStreamStatus::RELEASING_AUTONOMOUS:
      return BtLeAudioGroupStreamStatus::ReleasingAutonomous;
    case le_audio::GroupStreamStatus::SUSPENDING:
      return BtLeAudioGroupStreamStatus::Suspending;
    case le_audio::GroupStreamStatus::SUSPENDED:
      return BtLeAudioGroupStreamStatus::Suspended;
    case le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS:
      return BtLeAudioGroupStreamStatus::ConfiguredAutonomous;
    case le_audio::GroupStreamStatus::CONFIGURED_BY_USER:
      return BtLeAudioGroupStreamStatus::ConfiguredByUser;
    case le_audio::GroupStreamStatus::DESTROYED:
      return BtLeAudioGroupStreamStatus::Destroyed;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeAudioGroupStreamStatus{};
}

static void initialized_cb() { le_audio_initialized_callback(); }

static void connection_state_cb(le_audio::ConnectionState state, const RawAddress& address) {
  le_audio_connection_state_callback(to_rust_btle_audio_connection_state(state), address);
}

static void group_status_cb(int group_id, le_audio::GroupStatus group_status) {
  le_audio_group_status_callback(group_id, to_rust_btle_audio_group_status(group_status));
}

static void group_node_status_cb(const RawAddress& bd_addr, int group_id,
                                 le_audio::GroupNodeStatus node_status) {
  le_audio_group_node_status_callback(bd_addr, group_id,
                                      to_rust_btle_audio_group_node_status(node_status));
}

static void unicast_monitor_mode_status_cb(uint8_t direction,
                                           le_audio::UnicastMonitorModeStatus status) {
  le_audio_unicast_monitor_mode_status_callback(
          to_rust_btle_audio_direction(direction),
          to_rust_btle_audio_unicast_monitor_mode_status(status));
}

static void group_stream_status_cb(int group_id, le_audio::GroupStreamStatus status) {
  le_audio_group_stream_status_callback(group_id, to_rust_btle_audio_group_stream_status(status));
}

static void audio_conf_cb(uint8_t direction, int group_id,
                          std::optional<std::bitset<32>> snk_audio_location,
                          std::optional<std::bitset<32>> src_audio_location, uint16_t avail_cont) {
  int64_t rs_snk_audio_location = snk_audio_location ? snk_audio_location->to_ulong() : -1l;
  int64_t rs_src_audio_location = src_audio_location ? src_audio_location->to_ulong() : -1l;
  le_audio_audio_conf_callback(direction, group_id, rs_snk_audio_location, rs_src_audio_location,
                               avail_cont);
}

static void sink_audio_location_available_cb(const RawAddress& address,
                                             std::optional<std::bitset<32>> snk_audio_location) {
  int64_t rs_snk_audio_location = snk_audio_location ? snk_audio_location->to_ulong() : -1l;
  le_audio_sink_audio_location_available_callback(address, rs_snk_audio_location);
}

static void audio_local_codec_capabilities_cb(
        std::vector<le_audio::btle_audio_codec_config_t> local_input_capa_codec_conf,
        std::vector<le_audio::btle_audio_codec_config_t> local_output_capa_codec_conf) {
  le_audio_audio_local_codec_capabilities_callback(
          to_rust_btle_audio_codec_config_vec(local_input_capa_codec_conf),
          to_rust_btle_audio_codec_config_vec(local_output_capa_codec_conf));
}

static void audio_group_codec_conf_cb(
        int group_id, le_audio::btle_audio_codec_config_t input_codec_conf,
        le_audio::btle_audio_codec_config_t output_codec_conf,
        std::vector<le_audio::btle_audio_codec_config_t> input_selectable_codec_conf,
        std::vector<le_audio::btle_audio_codec_config_t> output_selectable_codec_conf) {
  le_audio_audio_group_codec_conf_callback(
          group_id, to_rust_btle_audio_codec_config(input_codec_conf),
          to_rust_btle_audio_codec_config(output_codec_conf),
          to_rust_btle_audio_codec_config_vec(input_selectable_codec_conf),
          to_rust_btle_audio_codec_config_vec(output_selectable_codec_conf));
}
}  // namespace internal

class DBusLeAudioClientCallbacks : public le_audio::LeAudioClientCallbacks {
public:
  static le_audio::LeAudioClientCallbacks* GetInstance() {
    static auto instance = new DBusLeAudioClientCallbacks();
    return instance;
  }

  DBusLeAudioClientCallbacks() {}

  void OnInitialized() override {
    log::info("");
    topshim::rust::internal::initialized_cb();
  }

  void OnConnectionState(le_audio::ConnectionState state, const RawAddress& address) override {
    log::info("state={}, address={}", static_cast<int>(state), address);
    topshim::rust::internal::connection_state_cb(state, address);
  }

  void OnGroupStatus(int group_id, le_audio::GroupStatus group_status) override {
    log::info("group_id={}, group_status={}", group_id, static_cast<int>(group_status));
    topshim::rust::internal::group_status_cb(group_id, group_status);
  }

  void OnGroupNodeStatus(const RawAddress& bd_addr, int group_id,
                         le_audio::GroupNodeStatus node_status) {
    log::info("bd_addr={}, group_id={}, node_status={}", bd_addr, group_id,
              static_cast<int>(node_status));
    topshim::rust::internal::group_node_status_cb(bd_addr, group_id, node_status);
  }

  void OnAudioConf(uint8_t direction, int group_id,
                   std::optional<std::bitset<32>> snk_audio_location,
                   std::optional<std::bitset<32>> src_audio_location, uint16_t avail_cont) {
    log::info(
            "direction={}, group_id={}, snk_audio_location={}, src_audio_location={}, "
            "avail_cont={}",
            direction, group_id,
            std::to_string(snk_audio_location ? snk_audio_location->to_ulong() : -1),
            std::to_string(src_audio_location ? src_audio_location->to_ulong() : -1), avail_cont);
    topshim::rust::internal::audio_conf_cb(direction, group_id, snk_audio_location,
                                           src_audio_location, avail_cont);
  }

  void OnSinkAudioLocationAvailable(const RawAddress& address,
                                    std::optional<std::bitset<32>> snk_audio_location) {
    log::info("address={}, snk_audio_locations={}", address,
              std::to_string(snk_audio_location ? snk_audio_location->to_ulong() : -1));
    topshim::rust::internal::sink_audio_location_available_cb(address, snk_audio_location);
  }

  void OnAudioLocalCodecCapabilities(
          std::vector<le_audio::btle_audio_codec_config_t> local_input_capa_codec_conf,
          std::vector<le_audio::btle_audio_codec_config_t> local_output_capa_codec_conf) {
    log::info("");
    topshim::rust::internal::audio_local_codec_capabilities_cb(local_input_capa_codec_conf,
                                                               local_output_capa_codec_conf);
  }

  void OnAudioGroupCodecConf(
          int group_id, le_audio::btle_audio_codec_config_t input_codec_conf,
          le_audio::btle_audio_codec_config_t output_codec_conf,
          std::vector<le_audio::btle_audio_codec_config_t> input_selectable_codec_conf,
          std::vector<le_audio::btle_audio_codec_config_t> output_selectable_codec_conf) {
    log::info("group_id={}", group_id);
    topshim::rust::internal::audio_group_codec_conf_cb(
            group_id, input_codec_conf, output_codec_conf, input_selectable_codec_conf,
            output_selectable_codec_conf);
  }

  void OnAudioGroupCurrentCodecConf(int group_id,
                                    le_audio::btle_audio_codec_config_t input_codec_conf,
                                    le_audio::btle_audio_codec_config_t output_codec_conf) {
    log::info("group_id={}, input_codec_conf={}, output_codec_conf={}", group_id,
              input_codec_conf.ToString(), output_codec_conf.ToString());
  }

  void OnAudioGroupSelectableCodecConf(
          int group_id,
          std::vector<le_audio::btle_audio_codec_config_t> input_selectable_codec_conf,
          std::vector<le_audio::btle_audio_codec_config_t> output_selectable_codec_conf) {
    log::info(
            "group_id={}, input_selectable_codec_conf.size={}, "
            "output_selectable_codec_conf.size={}",
            group_id, input_selectable_codec_conf.size(), output_selectable_codec_conf.size());
  }

  void OnHealthBasedRecommendationAction(const RawAddress& address,
                                         le_audio::LeAudioHealthBasedAction action) {
    log::info("address={}, action={}", address, static_cast<int>(action));
  }

  void OnHealthBasedGroupRecommendationAction(int group_id,
                                              le_audio::LeAudioHealthBasedAction action) {
    log::info("group_id={}, action={}", group_id, static_cast<int>(action));
  }

  void OnUnicastMonitorModeStatus(uint8_t direction, le_audio::UnicastMonitorModeStatus status) {
    log::info("direction={}, status={}", direction, static_cast<int>(status));
    topshim::rust::internal::unicast_monitor_mode_status_cb(direction, status);
  }

  void OnGroupStreamStatus(int group_id, le_audio::GroupStreamStatus status) {
    log::info("group_id={}, status={}", group_id, static_cast<int>(status));
    topshim::rust::internal::group_stream_status_cb(group_id, status);
  }
};

void LeAudioClientIntf::init(/*
     LeAudioClientCallbacks* callbacks,
     const std::vector<le_audio::btle_audio_codec_config_t>& offloading_preference */) {
  return intf_->Initialize(DBusLeAudioClientCallbacks::GetInstance(), {});
}

void LeAudioClientIntf::connect(RawAddress addr) { return intf_->Connect(addr); }

void LeAudioClientIntf::disconnect(RawAddress addr) { return intf_->Disconnect(addr); }

void LeAudioClientIntf::set_enable_state(RawAddress addr, bool enabled) {
  return intf_->SetEnableState(addr, enabled);
}

void LeAudioClientIntf::cleanup() { return intf_->Cleanup(); }

void LeAudioClientIntf::remove_device(RawAddress addr) { return intf_->RemoveDevice(addr); }

void LeAudioClientIntf::group_add_node(int group_id, RawAddress addr) {
  return intf_->GroupAddNode(group_id, addr);
}

void LeAudioClientIntf::group_remove_node(int group_id, RawAddress addr) {
  return intf_->GroupRemoveNode(group_id, addr);
}

void LeAudioClientIntf::group_set_active(int group_id) { return intf_->GroupSetActive(group_id); }

void LeAudioClientIntf::set_codec_config_preference(int group_id,
                                                    BtLeAudioCodecConfig input_codec_config,
                                                    BtLeAudioCodecConfig output_codec_config) {
  return intf_->SetCodecConfigPreference(
          group_id, internal::from_rust_btle_audio_codec_config(input_codec_config),
          internal::from_rust_btle_audio_codec_config(output_codec_config));
}

void LeAudioClientIntf::set_ccid_information(int ccid, int context_type) {
  return intf_->SetCcidInformation(ccid, context_type);
}

void LeAudioClientIntf::set_in_call(bool in_call) { return intf_->SetInCall(in_call); }

void LeAudioClientIntf::send_audio_profile_preferences(int group_id,
                                                       bool is_output_preference_le_audio,
                                                       bool is_duplex_preference_le_audio) {
  return intf_->SendAudioProfilePreferences(group_id, is_output_preference_le_audio,
                                            is_duplex_preference_le_audio);
}

void LeAudioClientIntf::set_unicast_monitor_mode(BtLeAudioDirection direction, bool enable) {
  return intf_->SetUnicastMonitorMode(internal::from_rust_btle_audio_direction(direction), enable);
}

std::unique_ptr<LeAudioClientIntf> GetLeAudioClientProfile(const unsigned char* btif) {
  if (internal::g_lea_client_if) {
    std::abort();
  }

  const bt_interface_t* btif_ = reinterpret_cast<const bt_interface_t*>(btif);

  auto lea_client_if =
          std::make_unique<LeAudioClientIntf>(const_cast<le_audio::LeAudioClientInterface*>(
                  reinterpret_cast<const le_audio::LeAudioClientInterface*>(
                          btif_->get_profile_interface("le_audio"))));

  internal::g_lea_client_if = lea_client_if.get();

  return lea_client_if;
}

bool LeAudioClientIntf::host_start_audio_request() {
  return ::bluetooth::audio::le_audio::HostStartRequest();
}

void LeAudioClientIntf::host_stop_audio_request() {
  ::bluetooth::audio::le_audio::HostStopRequest();
}

bool LeAudioClientIntf::peer_start_audio_request() {
  return ::bluetooth::audio::le_audio::PeerStartRequest();
}

void LeAudioClientIntf::peer_stop_audio_request() {
  ::bluetooth::audio::le_audio::PeerStopRequest();
}

static BtLePcmConfig to_rust_btle_pcm_params(audio::le_audio::btle_pcm_parameters pcm_params) {
  return BtLePcmConfig{
          .data_interval_us = pcm_params.data_interval_us,
          .sample_rate = pcm_params.sample_rate,
          .bits_per_sample = pcm_params.bits_per_sample,
          .channels_count = pcm_params.channels_count,
  };
}

BtLePcmConfig LeAudioClientIntf::get_host_pcm_config() {
  return to_rust_btle_pcm_params(::bluetooth::audio::le_audio::GetHostPcmConfig());
}

BtLePcmConfig LeAudioClientIntf::get_peer_pcm_config() {
  return to_rust_btle_pcm_params(::bluetooth::audio::le_audio::GetPeerPcmConfig());
}

static BtLeStreamStartedStatus to_rust_btle_stream_started_status(
        audio::le_audio::btle_stream_started_status status) {
  switch (status) {
    case audio::le_audio::btle_stream_started_status::CANCELED:
      return BtLeStreamStartedStatus::Canceled;
    case audio::le_audio::btle_stream_started_status::IDLE:
      return BtLeStreamStartedStatus::Idle;
    case audio::le_audio::btle_stream_started_status::STARTED:
      return BtLeStreamStartedStatus::Started;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtLeStreamStartedStatus{};
}

BtLeStreamStartedStatus LeAudioClientIntf::get_host_stream_started() {
  return to_rust_btle_stream_started_status(::bluetooth::audio::le_audio::GetHostStreamStarted());
}

BtLeStreamStartedStatus LeAudioClientIntf::get_peer_stream_started() {
  return to_rust_btle_stream_started_status(::bluetooth::audio::le_audio::GetPeerStreamStarted());
}

void LeAudioClientIntf::source_metadata_changed(::rust::Vec<SourceMetadata> metadata) {
  if (metadata.empty()) {
    log::warn("Received empty metadata.");
    return;
  }

  // This will be referenced across threads.
  static std::vector<playback_track_metadata_v7> tracks;
  tracks.clear();
  tracks.reserve(metadata.size());
  for (auto m : metadata) {
    struct playback_track_metadata_v7 track = {
            .base =
                    {
                            .usage = static_cast<audio_usage_t>(m.usage),
                            .content_type = static_cast<audio_content_type_t>(m.content_type),
                            .gain = static_cast<float>(m.gain),
                    },
            .channel_mask = AUDIO_CHANNEL_NONE,  // unused
            .tags = "",
    };

    tracks.push_back(track);
  }

  source_metadata_v7_t data = {
          .track_count = tracks.size(),
          .tracks = tracks.data(),
  };

  ::bluetooth::audio::le_audio::SourceMetadataChanged(data);
}

void LeAudioClientIntf::sink_metadata_changed(::rust::Vec<SinkMetadata> metadata) {
  if (metadata.empty()) {
    log::warn("Received empty metadata.");
    return;
  }

  // This will be referenced across threads.
  static std::vector<record_track_metadata_v7> tracks;
  tracks.clear();
  tracks.reserve(metadata.size());
  for (auto m : metadata) {
    struct record_track_metadata_v7 track = {
            .base =
                    {
                            .source = static_cast<audio_source_t>(m.source),
                            .gain = static_cast<float>(m.gain),
                            .dest_device = AUDIO_DEVICE_IN_DEFAULT,
                            .dest_device_address = "",  // unused
                    },
            .channel_mask = AUDIO_CHANNEL_NONE,  // unused
            .tags = "",
    };

    tracks.push_back(track);
  }

  const sink_metadata_v7_t data = {
          .track_count = tracks.size(),
          .tracks = tracks.data(),
  };

  ::bluetooth::audio::le_audio::SinkMetadataChanged(data);
}
}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
