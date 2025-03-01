/*
 * Copyright 2023 The Android Open Source Project
 * Copyright 2020 HIMSA II K/S - www.himsa.com. Represented by EHIMA
 * - www.ehima.com
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

/* LeAudioDeviceGroup class represents group of LeAudioDevices and allows to
 * perform operations on them. Group states are ASE states due to nature of
 * group which operates finally of ASEs.
 *
 * Group is created after adding a node to new group id (which is not on list).
 */

#pragma once

#include <map>
#include <memory>
#include <optional>
#include <utility>  // for std::pair
#include <vector>

#include "hardware/bt_le_audio.h"

#ifdef __ANDROID__
#include <android/sysprop/BluetoothProperties.sysprop.h>
#endif

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "common/strings.h"
#include "devices.h"
#include "le_audio_log_history.h"
#include "le_audio_types.h"

namespace bluetooth::le_audio {

class LeAudioDeviceGroup {
public:
  const int group_id_;

  class CigConfiguration {
  public:
    CigConfiguration() = delete;
    CigConfiguration(LeAudioDeviceGroup* group) : group_(group), state_(types::CigState::NONE) {}

    types::CigState GetState(void) const { return state_; }
    void SetState(bluetooth::le_audio::types::CigState state) {
      log::verbose("{} -> {}", bluetooth::common::ToString(state_),
                   bluetooth::common::ToString(state));
      state_ = state;
    }
    void GetCisCount(types::LeAudioContextType context_type, uint8_t& out_cis_count_bidir,
                     uint8_t& out_cis_count_unidir_sink,
                     uint8_t& out_cis_count_unidir_source) const;
    void GenerateCisIds(types::LeAudioContextType context_type);
    bool AssignCisIds(LeAudioDevice* leAudioDevice);
    void AssignCisConnHandles(const std::vector<uint16_t>& conn_handles);
    void UnassignCis(LeAudioDevice* leAudioDevice, uint16_t conn_handle);

    std::vector<struct types::cis> cises;

  private:
    uint8_t GetFirstFreeCisId(types::CisType cis_type) const;

    LeAudioDeviceGroup* group_;
    types::CigState state_;
  } cig;

  bool IsGroupConfiguredTo(const types::AudioSetConfiguration& cfg) {
    if (!stream_conf.conf) {
      return false;
    }
    return cfg == *stream_conf.conf;
  }

  /* Current configuration strategy - recalculated on demand */
  mutable std::optional<types::LeAudioConfigurationStrategy> strategy_ = std::nullopt;

  /* Current audio stream configuration */
  struct stream_configuration stream_conf;
  bool notify_streaming_when_cises_are_ready_;

  uint8_t audio_directions_;
  types::BidirectionalPair<std::optional<types::AudioLocations>> audio_locations_;

  /* Whether LE Audio is preferred for OUTPUT_ONLY and DUPLEX cases */
  bool is_output_preference_le_audio;
  bool is_duplex_preference_le_audio;

  struct {
    DsaMode mode;
    bool active;
  } dsa_;
  bool asymmetric_phy_for_unidirectional_cis_supported;

  explicit LeAudioDeviceGroup(const int group_id)
      : group_id_(group_id),
        cig(this),
        stream_conf({}),
        notify_streaming_when_cises_are_ready_(false),
        audio_directions_(0),
        dsa_({DsaMode::DISABLED, false}),
        asymmetric_phy_for_unidirectional_cis_supported(true),
        is_enabled_(true),
        transport_latency_mtos_us_(0),
        transport_latency_stom_us_(0),
        configuration_context_type_(types::LeAudioContextType::UNINITIALIZED),
        metadata_context_type_(
                {.sink = types::AudioContexts(types::LeAudioContextType::UNINITIALIZED),
                 .source = types::AudioContexts(types::LeAudioContextType::UNINITIALIZED)}),
        streaming_metadata_context_type_(
                {.sink = types::AudioContexts(types::LeAudioContextType::UNINITIALIZED),
                 .source = types::AudioContexts(types::LeAudioContextType::UNINITIALIZED)}),
        group_user_allowed_context_mask_(
                {.sink = types::AudioContexts(types::kLeAudioContextAllTypes),
                 .source = types::AudioContexts(types::kLeAudioContextAllTypes)}),
        preferred_config_({.sink = nullptr, .source = nullptr}),
        target_state_(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
        current_state_(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
        in_transition_(false) {
#ifdef __ANDROID__
    // 22 maps to BluetoothProfile#LE_AUDIO
    is_output_preference_le_audio =
            android::sysprop::BluetoothProperties::getDefaultOutputOnlyAudioProfile() ==
            LE_AUDIO_PROFILE_CONSTANT;
    is_duplex_preference_le_audio =
            android::sysprop::BluetoothProperties::getDefaultDuplexAudioProfile() ==
            LE_AUDIO_PROFILE_CONSTANT;
#else
    is_output_preference_le_audio = true;
    is_duplex_preference_le_audio = true;
#endif
  }
  ~LeAudioDeviceGroup(void);

  void AddNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice);
  void RemoveNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice);
  bool IsEmpty(void) const;
  bool IsAnyDeviceConnected(void) const;
  int Size(void) const;
  int DesiredSize(void) const;
  int NumOfConnected() const;
  int NumOfAvailableForDirection(int direction) const;
  bool Activate(types::LeAudioContextType context_type,
                const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
                types::BidirectionalPair<std::vector<uint8_t>> ccid_lists);
  void Deactivate(void);
  void ClearSinksFromConfiguration(void);
  void ClearSourcesFromConfiguration(void);
  void Cleanup(void);
  LeAudioDevice* GetFirstDevice(void) const;
  LeAudioDevice* GetFirstDeviceWithAvailableContext(types::LeAudioContextType context_type) const;
  types::LeAudioConfigurationStrategy GetGroupSinkStrategy(void) const;
  inline void InvalidateGroupStrategy(void) { strategy_ = std::nullopt; }
  int GetAseCount(uint8_t direction) const;
  LeAudioDevice* GetNextDevice(LeAudioDevice* leAudioDevice) const;
  LeAudioDevice* GetNextDeviceWithAvailableContext(LeAudioDevice* leAudioDevice,
                                                   types::LeAudioContextType context_type) const;
  LeAudioDevice* GetFirstActiveDevice(void) const;
  LeAudioDevice* GetNextActiveDevice(LeAudioDevice* leAudioDevice) const;
  LeAudioDevice* GetFirstActiveDeviceByCisAndDataPathState(
          types::CisState cis_state, types::DataPathState data_path_state) const;
  LeAudioDevice* GetNextActiveDeviceByCisAndDataPathState(
          LeAudioDevice* leAudioDevice, types::CisState cis_state,
          types::DataPathState data_path_state) const;
  int GetNumOfActiveDevices(void) const;
  bool IsDeviceInTheGroup(LeAudioDevice* leAudioDevice) const;
  bool HaveAllActiveDevicesAsesTheSameState(types::AseState state) const;
  bool HaveAnyActiveDeviceInStreamingState() const;
  bool HaveAnyActiveDeviceInUnconfiguredState() const;
  bool IsGroupStreamReady(void) const;
  bool IsGroupReadyToCreateStream(void) const;
  bool IsGroupReadyToSuspendStream(void) const;
  bool HaveAllCisesDisconnected(void) const;
  void ClearAllCises(void);
  void UpdateCisConfiguration(uint8_t direction);
  void AssignCisConnHandlesToAses(LeAudioDevice* leAudioDevice);
  void AssignCisConnHandlesToAses(void);
  bool Configure(types::LeAudioContextType context_type,
                 const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
                 types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {},
                                                                              .source = {}});
  uint32_t GetSduInterval(uint8_t direction) const;
  uint8_t GetSCA(void) const;
  uint8_t GetPacking(void) const;
  uint8_t GetFraming(void) const;
  uint16_t GetMaxTransportLatencyStom(void) const;
  uint16_t GetMaxTransportLatencyMtos(void) const;
  void SetTransportLatency(uint8_t direction, uint32_t transport_latency_us);
  uint8_t GetRtn(uint8_t direction, uint8_t cis_id) const;
  uint16_t GetMaxSduSize(uint8_t direction, uint8_t cis_id) const;
  uint8_t GetPhyBitmask(uint8_t direction) const;
  uint8_t GetTargetPhy(uint8_t direction) const;
  bool GetPresentationDelay(uint32_t* delay, uint8_t direction) const;
  uint16_t GetRemoteDelay(uint8_t direction) const;
  bool UpdateAudioSetConfigurationCache(types::LeAudioContextType ctx_type,
                                        bool use_preferred = false) const;
  CodecManager::UnicastConfigurationRequirements GetAudioSetConfigurationRequirements(
          types::LeAudioContextType ctx_type) const;
  bool SetPreferredAudioSetConfiguration(
          const bluetooth::le_audio::btle_audio_codec_config_t& input_codec_config,
          const bluetooth::le_audio::btle_audio_codec_config_t& output_codec_config) const;
  bool IsUsingPreferredAudioSetConfiguration(const types::LeAudioContextType& context_type) const;
  void ResetPreferredAudioSetConfiguration(void) const;
  bool ReloadAudioLocations(void);
  bool ReloadAudioDirections(void);
  types::AudioContexts GetAllSupportedBidirectionalContextTypes(void) const;
  types::AudioContexts GetAllSupportedSingleDirectionOnlyContextTypes(uint8_t direction) const;
  std::shared_ptr<const types::AudioSetConfiguration> GetActiveConfiguration(void) const;
  bool IsPendingConfiguration(void) const;
  std::shared_ptr<const types::AudioSetConfiguration> GetConfiguration(
          types::LeAudioContextType ctx_type) const;
  std::shared_ptr<const types::AudioSetConfiguration> GetPreferredConfiguration(
          types::LeAudioContextType ctx_type) const;
  std::shared_ptr<const types::AudioSetConfiguration> GetCachedConfiguration(
          types::LeAudioContextType ctx_type) const;
  std::shared_ptr<const types::AudioSetConfiguration> GetCachedPreferredConfiguration(
          types::LeAudioContextType ctx_type) const;
  void InvalidateCachedConfigurations(void);
  void SetPendingConfiguration(void);
  void ClearPendingConfiguration(void);
  void AddToAllowListNotConnectedGroupMembers(int gatt_if);
  void ApplyReconnectionMode(int gatt_if, tBTM_BLE_CONN_TYPE reconnection_mode);
  void Disable(int gatt_if);
  void Enable(int gatt_if, tBTM_BLE_CONN_TYPE reconnection_mode);
  bool IsEnabled(void) const;
  LeAudioCodecConfiguration GetAudioSessionCodecConfigForDirection(
          types::LeAudioContextType group_context_type, uint8_t direction) const;
  bool HasCodecConfigurationForDirection(types::LeAudioContextType group_context_type,
                                         uint8_t direction) const;
  bool IsAudioSetConfigurationAvailable(types::LeAudioContextType group_context_type);
  bool IsMetadataChanged(const types::BidirectionalPair<types::AudioContexts>& context_types,
                         const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists) const;
  bool IsConfiguredForContext(types::LeAudioContextType context_type) const;
  void RemoveCisFromStreamIfNeeded(LeAudioDevice* leAudioDevice, uint16_t cis_conn_hdl);

  inline types::AseState GetState(void) const { return current_state_; }
  void SetState(types::AseState state) {
    log::info("group_id: {} current state: {}, new state {}, in_transition_ {}", group_id_,
              bluetooth::common::ToString(current_state_), bluetooth::common::ToString(state),
              in_transition_);
    LeAudioLogHistory::Get()->AddLogHistory(kLogStateMachineTag, group_id_, RawAddress::kEmpty,
                                            kLogStateChangedOp,
                                            bluetooth::common::ToString(current_state_) + "->" +
                                                    bluetooth::common::ToString(state));
    current_state_ = state;

    if (target_state_ == current_state_) {
      in_transition_ = false;
      log::info("In transition flag cleared");
    }
  }

  inline types::AseState GetTargetState(void) const { return target_state_; }
  inline void SetNotifyStreamingWhenCisesAreReadyFlag(bool value) {
    notify_streaming_when_cises_are_ready_ = value;
  }
  inline bool GetNotifyStreamingWhenCisesAreReadyFlag(void) {
    return notify_streaming_when_cises_are_ready_;
  }
  void SetTargetState(types::AseState state) {
    log::info("group_id: {} target state: {}, new target state: {}, in_transition_ {}", group_id_,
              bluetooth::common::ToString(target_state_), bluetooth::common::ToString(state),
              in_transition_);
    LeAudioLogHistory::Get()->AddLogHistory(
            kLogStateMachineTag, group_id_, RawAddress::kEmpty, kLogTargetStateChangedOp,
            bluetooth::common::ToString(target_state_) + "->" + bluetooth::common::ToString(state));

    target_state_ = state;

    in_transition_ = target_state_ != current_state_;
    log::info("In transition flag  = {}", in_transition_);
  }

  inline void SetConfigurationContextType(types::LeAudioContextType context_type) {
    configuration_context_type_ = context_type;
  }

  inline types::LeAudioContextType GetConfigurationContextType(void) const {
    return configuration_context_type_;
  }

  inline void SetMetadataContexts(const types::BidirectionalPair<types::AudioContexts>& metadata) {
    log::debug("group_id: {}, sink: {}, source: {}", group_id_, common::ToString(metadata.sink),
               common::ToString(metadata.source));
    metadata_context_type_ = metadata;
  }

  inline types::BidirectionalPair<types::AudioContexts> GetMetadataContexts() const {
    return metadata_context_type_;
  }

  inline void SetStreamingMetadataContexts(types::AudioContexts& metadata, int remote_direction) {
    log::debug("group_id: {}, direction: {}, metadata: {}", group_id_,
               remote_direction == types::kLeAudioDirectionSink ? "sink" : "source",
               common::ToString(metadata));
    streaming_metadata_context_type_.get(remote_direction) = metadata;
  }

  inline types::BidirectionalPair<types::AudioContexts> GetStreamingMetadataContexts() const {
    log::debug("group_id: {}, sink: {}, source: {}", group_id_,
               common::ToString(streaming_metadata_context_type_.sink),
               common::ToString(streaming_metadata_context_type_.source));
    return streaming_metadata_context_type_;
  }

  inline void ClearStreamingMetadataContexts() {
    log::debug("group_id: {}", group_id_);
    streaming_metadata_context_type_.sink.clear();
    streaming_metadata_context_type_.source.clear();
  }

  types::AudioContexts GetAvailableContexts(int direction = types::kLeAudioDirectionBoth) const {
    log::assert_that(direction <= (types::kLeAudioDirectionBoth), "Invalid direction used.");

    auto streaming_metadata = GetStreamingMetadataContexts();
    types::BidirectionalPair<types::AudioContexts> available_contexts =
            GetLatestAvailableContexts();

    log::debug(
            "group id: {}, streaming contexts sink: {}, streaming contexts source: {}, available "
            "contexts sink: {}, available contexts source: {}",
            group_id_, streaming_metadata.sink.to_string(), streaming_metadata.source.to_string(),
            available_contexts.sink.to_string(), available_contexts.source.to_string());

    available_contexts.sink |= streaming_metadata.sink;
    available_contexts.source |= streaming_metadata.source;

    if (direction < types::kLeAudioDirectionBoth) {
      return available_contexts.get(direction);
    } else {
      return types::get_bidirectional(available_contexts);
    }
  }

  inline void SetAllowedContextMask(types::BidirectionalPair<types::AudioContexts>& context_types) {
    group_user_allowed_context_mask_ = context_types;
    log::debug("group id: {}, allowed contexts sink: {}, allowed contexts source: {}", group_id_,
               group_user_allowed_context_mask_.sink.to_string(),
               group_user_allowed_context_mask_.source.to_string());
  }

  types::AudioContexts GetAllowedContextMask(int direction = types::kLeAudioDirectionBoth) const {
    log::assert_that(direction <= (types::kLeAudioDirectionBoth), "Invalid direction used.");
    if (direction < types::kLeAudioDirectionBoth) {
      log::debug("group id: {}, allowed contexts sink: {}, allowed contexts source: {}", group_id_,
                 group_user_allowed_context_mask_.sink.to_string(),
                 group_user_allowed_context_mask_.source.to_string());
      return group_user_allowed_context_mask_.get(direction);
    } else {
      return types::get_bidirectional(group_user_allowed_context_mask_);
    }
  }

  types::AudioContexts GetSupportedContexts(int direction = types::kLeAudioDirectionBoth) const;

  DsaModes GetAllowedDsaModes() {
    DsaModes dsa_modes{};
    std::set<DsaMode> dsa_mode_set{};

    for (auto leAudioDevice : leAudioDevices_) {
      if (leAudioDevice.expired()) {
        continue;
      }

      auto device_dsa_modes = leAudioDevice.lock()->GetDsaModes();

      dsa_mode_set.insert(device_dsa_modes.begin(), device_dsa_modes.end());
    }

    dsa_modes.assign(dsa_mode_set.begin(), dsa_mode_set.end());

    return dsa_modes;
  }

  std::vector<DsaModes> GetAllowedDsaModesList() {
    std::vector<DsaModes> dsa_modes_list = {};
    for (auto leAudioDevice : leAudioDevices_) {
      DsaModes dsa_modes = {};

      if (!leAudioDevice.expired()) {
        dsa_modes = leAudioDevice.lock()->GetDsaModes();
      }
      dsa_modes_list.push_back(dsa_modes);
    }
    return dsa_modes_list;
  }

  bool DsaReducedSduSizeSupported() {
    bool reduced_sdu = false;
    for (auto leAudioDevice : leAudioDevices_) {
      if (!leAudioDevice.expired()) {
        reduced_sdu |= leAudioDevice.lock()->DsaReducedSduSizeSupported();
      }
    }
    return reduced_sdu;
  }

  types::BidirectionalPair<types::AudioContexts> GetLatestAvailableContexts(void) const;

  bool IsInTransition(void) const;
  bool IsInTransitionTo(types::AseState state) const {
    return (GetTargetState() == state) && IsInTransition();
  }
  bool IsStreaming(void) const;
  bool IsReleasingOrIdle(void) const;
  bool IsReleasing(void) const;

  void PrintDebugState(void) const;
  void Dump(std::stringstream& stream, int active_group_id) const;

  /* Codec configuration matcher supporting the legacy configuration provider
   * mechanism for the non-vendor and software codecs. Only if the codec
   * parameters are using the common LTV data format, the BT stack can verify
   * them against the remote device capabilities and find the best possible
   * configurations. This will not be used for finding best possible vendor
   * codec configuration.
   */
  std::unique_ptr<types::AudioSetConfiguration> FindFirstSupportedConfiguration(
          const CodecManager::UnicastConfigurationRequirements& requirements,
          const types::AudioSetConfigurations* confs, bool use_preferred) const;

private:
  bool is_enabled_;

  uint32_t transport_latency_mtos_us_;
  uint32_t transport_latency_stom_us_;

  bool ConfigureAses(const types::AudioSetConfiguration* audio_set_conf,
                     types::LeAudioContextType context_type,
                     const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
                     const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists);
  bool IsAudioSetConfigurationSupported(
          const CodecManager::UnicastConfigurationRequirements& requirements,
          const types::AudioSetConfiguration* audio_set_configuratio,
          bool use_preferred = false) const;
  uint32_t GetTransportLatencyUs(uint8_t direction) const;
  bool IsCisPartOfCurrentStream(uint16_t cis_conn_hdl) const;

  /* Current configuration and metadata context types */
  types::LeAudioContextType configuration_context_type_;
  types::BidirectionalPair<types::AudioContexts> metadata_context_type_;
  types::BidirectionalPair<types::AudioContexts> streaming_metadata_context_type_;

  /* Mask of currently allowed context types. Not set a value not set will
   * result in streaming rejection.
   */
  types::BidirectionalPair<types::AudioContexts> group_user_allowed_context_mask_;

  /* Possible configuration cache - refreshed on each group context availability
   * change. Stored as a pair of (is_valid_cache, configuration*). `pair.first`
   * being `false` means that the cached value should be refreshed.
   */
  mutable std::map<types::LeAudioContextType,
                   std::pair<bool, const std::shared_ptr<types::AudioSetConfiguration>>>
          context_to_configuration_cache_map_;

  /* Possible preferred configuration cache - refreshed on each group context
   * availability change. Stored as a pair of (is_valid_cache, configuration*).
   * `pair.first` being `false` means that the cached value should be refreshed.
   */
  mutable std::map<types::LeAudioContextType,
                   std::pair<bool, const std::shared_ptr<types::AudioSetConfiguration>>>
          context_to_preferred_configuration_cache_map_;

  mutable types::BidirectionalPair<
          std::unique_ptr<const bluetooth::le_audio::btle_audio_codec_config_t>>
          preferred_config_;

  types::AseState target_state_;
  types::AseState current_state_;
  bool in_transition_;
  std::vector<std::weak_ptr<LeAudioDevice>> leAudioDevices_;
};

/* LeAudioDeviceGroup class represents a wraper helper over all device groups in
 * le audio implementation. It allows to operate on device group from a list
 * (vector container) using determinants like id.
 */
class LeAudioDeviceGroups {
public:
  LeAudioDeviceGroup* Add(int group_id);
  void Remove(const int group_id);
  LeAudioDeviceGroup* FindById(int group_id) const;
  std::vector<int> GetGroupsIds(void) const;
  size_t Size() const;
  bool IsAnyInTransition() const;
  void Cleanup(void);
  void Dump(std::stringstream& stream, int active_group_id) const;

private:
  std::vector<std::unique_ptr<LeAudioDeviceGroup>> groups_;
};

}  // namespace bluetooth::le_audio
