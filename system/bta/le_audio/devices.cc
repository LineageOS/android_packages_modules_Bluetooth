/*
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

#include "devices.h"

#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <iomanip>
#include <ios>
#include <iterator>
#include <memory>
#include <optional>
#include <ostream>
#include <sstream>
#include <string>
#include <vector>

#include "acl_api.h"
#include "bta_gatt_api.h"
#include "bta_gatt_queue.h"
#include "btif/include/btif_storage.h"
#include "btm_ble_api_types.h"
#include "btm_iso_api_types.h"
#include "common/strings.h"
#include "gatt_api.h"
#include "hardware/bluetooth.h"
#include "hci/controller_interface.h"
#include "hci_error_code.h"
#include "hcidefs.h"
#include "internal_include/bt_trace.h"
#include "le_audio/codec_manager.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_log_history.h"
#include "le_audio_utils.h"
#include "main/shim/entry.h"
#include "osi/include/alarm.h"
#include "osi/include/properties.h"
#include "stack/include/btm_client_interface.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

using bluetooth::hci::kIsoCigPhy1M;
using bluetooth::hci::kIsoCigPhy2M;
using bluetooth::le_audio::DeviceConnectState;
using bluetooth::le_audio::types::ase;
using bluetooth::le_audio::types::AseState;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::AudioLocations;
using bluetooth::le_audio::types::BidirectionalPair;
using bluetooth::le_audio::types::CisState;
using bluetooth::le_audio::types::DataPathState;
using bluetooth::le_audio::types::LeAudioContextType;

namespace bluetooth::le_audio {
std::ostream& operator<<(std::ostream& os, const DeviceConnectState& state) {
  const char* char_value_ = "UNKNOWN";

  switch (state) {
    case DeviceConnectState::CONNECTED:
      char_value_ = "CONNECTED";
      break;
    case DeviceConnectState::DISCONNECTED:
      char_value_ = "DISCONNECTED";
      break;
    case DeviceConnectState::REMOVING:
      char_value_ = "REMOVING";
      break;
    case DeviceConnectState::DISCONNECTING:
      char_value_ = "DISCONNECTING";
      break;
    case DeviceConnectState::DISCONNECTING_AND_RECOVER:
      char_value_ = "DISCONNECTING_AND_RECOVER";
      break;
    case DeviceConnectState::CONNECTING_BY_USER:
      char_value_ = "CONNECTING_BY_USER";
      break;
    case DeviceConnectState::CONNECTED_BY_USER_GETTING_READY:
      char_value_ = "CONNECTED_BY_USER_GETTING_READY";
      break;
    case DeviceConnectState::CONNECTING_AUTOCONNECT:
      char_value_ = "CONNECTING_AUTOCONNECT";
      break;
    case DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY:
      char_value_ = "CONNECTED_AUTOCONNECT_GETTING_READY";
      break;
  }

  os << char_value_ << " (" << "0x" << std::setfill('0') << std::setw(2) << static_cast<int>(state)
     << ")";
  return os;
}

static uint32_t GetFirstLeft(const AudioLocations& audio_locations) {
  uint32_t audio_location_ulong = audio_locations.to_ulong();

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationFrontLeft) {
    return codec_spec_conf::kLeAudioLocationFrontLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationBackLeft) {
    return codec_spec_conf::kLeAudioLocationBackLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationFrontLeftOfCenter) {
    return codec_spec_conf::kLeAudioLocationFrontLeftOfCenter;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationSideLeft) {
    return codec_spec_conf::kLeAudioLocationSideLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationTopFrontLeft) {
    return codec_spec_conf::kLeAudioLocationTopFrontLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationTopBackLeft) {
    return codec_spec_conf::kLeAudioLocationTopBackLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationTopSideLeft) {
    return codec_spec_conf::kLeAudioLocationTopSideLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationBottomFrontLeft) {
    return codec_spec_conf::kLeAudioLocationBottomFrontLeft;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationFrontLeftWide) {
    return codec_spec_conf::kLeAudioLocationFrontLeftWide;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationLeftSurround) {
    return codec_spec_conf::kLeAudioLocationLeftSurround;
  }

  return 0;
}

static uint32_t GetFirstRight(const AudioLocations& audio_locations) {
  uint32_t audio_location_ulong = audio_locations.to_ulong();

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationFrontRight) {
    return codec_spec_conf::kLeAudioLocationFrontRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationBackRight) {
    return codec_spec_conf::kLeAudioLocationBackRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationFrontRightOfCenter) {
    return codec_spec_conf::kLeAudioLocationFrontRightOfCenter;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationSideRight) {
    return codec_spec_conf::kLeAudioLocationSideRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationTopFrontRight) {
    return codec_spec_conf::kLeAudioLocationTopFrontRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationTopBackRight) {
    return codec_spec_conf::kLeAudioLocationTopBackRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationTopSideRight) {
    return codec_spec_conf::kLeAudioLocationTopSideRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationBottomFrontRight) {
    return codec_spec_conf::kLeAudioLocationBottomFrontRight;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationFrontRightWide) {
    return codec_spec_conf::kLeAudioLocationFrontRightWide;
  }

  if (audio_location_ulong & codec_spec_conf::kLeAudioLocationRightSurround) {
    return codec_spec_conf::kLeAudioLocationRightSurround;
  }

  return 0;
}

static uint32_t PickAudioLocation(
        types::LeAudioConfigurationStrategy strategy, uint8_t direction,
        const types::BidirectionalPair<
                std::optional<types::hdl_pair_wrapper<types::AudioLocations>>>&
                device_audio_locations,
        AudioLocations& group_locations) {
  if (!device_audio_locations.get(direction)) {
    log::error("No valid location is available for direction {}", +direction);
    return 0;
  }
  auto const device_locations = device_audio_locations.get(direction)->value;

  log::debug("strategy: {}, locations: 0x{:x}, input group locations: 0x{:x}", (int)strategy,
             device_locations.to_ulong(), group_locations.to_ulong());

  auto is_left_not_yet_assigned =
          !(group_locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyLeft);
  auto is_right_not_yet_assigned =
          !(group_locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyRight);
  uint32_t left_device_loc = GetFirstLeft(device_locations);
  uint32_t right_device_loc = GetFirstRight(device_locations);

  /* Sink locations should be either Left or Right - TMAP 1.0 Sec. 3.5.1.2.1 */
  if (direction == le_audio::types::kLeAudioDirectionSink && left_device_loc == 0 &&
      right_device_loc == 0) {
    log::warn("Can't find device able to render left  and right audio channel");
  }

  switch (strategy) {
    case types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE:
    case types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE:
      if (left_device_loc && is_left_not_yet_assigned) {
        group_locations |= left_device_loc;
        return left_device_loc;
      }

      if (right_device_loc && is_right_not_yet_assigned) {
        group_locations |= right_device_loc;
        return right_device_loc;
      }
      break;

    case types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE:
      if (left_device_loc && right_device_loc) {
        group_locations |= left_device_loc | right_device_loc;
        return left_device_loc | right_device_loc;
      }
      break;
    default:
      log::fatal("Unknown strategy: {}", strategy);
      return 0;
  }

  /* Return either any left or any right audio location. It might result with
   * multiple devices within the group having the same location.
   */
  auto location = left_device_loc ? left_device_loc : right_device_loc;

  if (direction == le_audio::types::kLeAudioDirectionSink) {
    log::error(
            "Can't find device for left/right channel. Strategy: {}, device_locations: {:x}, "
            "output group_locations: {:x}, chosen location: {}.",
            strategy, device_locations.to_ulong(), group_locations.to_ulong(), location);
  } else {
    log::debug(
            "No left or right audio location available. Strategy: {}, device_locations: {:x}, "
            "output group_locations: {:x}, chosen location: {}.",
            strategy, device_locations.to_ulong(), group_locations.to_ulong(), location);
  }
  return location;
}

bool LeAudioDevice::IsAudioSetConfigurationSupported(
        const types::AudioSetConfiguration* audio_set_conf) const {
  for (auto direction :
       {le_audio::types::kLeAudioDirectionSink, le_audio::types::kLeAudioDirectionSource}) {
    const auto& confs = audio_set_conf->confs.get(direction);
    if (confs.size() == 0) {
      continue;
    }

    log::info("Looking for requirements: {} - {}", audio_set_conf->name,
              direction == 1 ? "snk" : "src");

    auto const& pacs = (direction == types::kLeAudioDirectionSink) ? snk_pacs_ : src_pacs_;
    for (const auto& ent : confs) {
      if (!utils::GetConfigurationSupportedPac(pacs, ent.codec)) {
        log::info("Configuration is NOT supported by device {}", address_);
        return false;
      }
    }
  }

  log::info("Configuration is supported by device {}", address_);
  return true;
}

bool LeAudioDevice::ConfigureAses(const types::AudioSetConfiguration* audio_set_conf,
                                  uint8_t num_of_devices, uint8_t direction,
                                  LeAudioContextType context_type,
                                  uint8_t* number_of_already_active_group_ase,
                                  AudioLocations& group_audio_locations_memo,
                                  const AudioContexts& metadata_context_types,
                                  const std::vector<uint8_t>& ccid_lists, bool reuse_cis_id) {
  if (num_of_devices == 0) {
    log::error("No devices available for configuration.");
    return false;
  }

  auto direction_str = (direction == types::kLeAudioDirectionSink ? "Sink" : "Source");
  /* First try to use the already configured ASE */
  auto ase = GetFirstActiveAseByDirection(direction);
  if (ase) {
    log::info("{}, using an already active {} ASE id={}", address_, direction_str, ase->id);
  } else {
    ase = GetFirstInactiveAse(direction, reuse_cis_id);
  }

  if (!ase) {
    log::error("{}, unable to find a {} ASE to configure", address_, direction_str);
    PrintDebugState();
    return false;
  }

  if (!audio_locations_.get(direction)) {
    log::error("{}, unable to find a {} audio allocation", address_, direction_str);
    return false;
  }

  auto const& audio_locations = audio_locations_.get(direction)->value;
  auto const& group_ase_configs = audio_set_conf->confs.get(direction);
  std::vector<types::AseConfiguration> ase_configs;
  std::copy_if(group_ase_configs.cbegin(), group_ase_configs.cend(),
               std::back_inserter(ase_configs), [&audio_locations](auto const& cfg) {
                 /* Pass as matching if config has no allocation to match
                  * (the legacy json config provider). Otherwise, with the codec
                  * extensibility feature enabled, we receive ASE configurations
                  * for the whole group and we should filter them by audio
                  * allocations to match with the locations supported by a
                  * particular device.
                  */
                 auto config = cfg.codec.params.GetAsCoreCodecConfig();
                 if (!config.audio_channel_allocation.has_value()) {
                   return true;
                 }

                 // No locations bits means mono audio
                 if (audio_locations.none()) {
                   return true;
                 }

                 // Filter-out not matching audio locations
                 return (cfg.codec.params.GetAsCoreCodecConfig().audio_channel_allocation.value() &
                         audio_locations.to_ulong()) != 0;
               });

  auto const& pacs = (direction == types::kLeAudioDirectionSink) ? snk_pacs_ : src_pacs_;
  for (size_t i = 0; i < ase_configs.size() && ase; ++i) {
    auto const& ase_cfg = ase_configs.at(i);
    if (utils::IsCodecUsingLtvFormat(ase_cfg.codec.id) &&
        !utils::GetConfigurationSupportedPac(pacs, ase_cfg.codec)) {
      log::error("{}, No {} PAC found matching codec: {}. Stop the activation.", address_,
                 direction_str, common::ToString(ase_cfg.codec));
      return false;
    }
  }

  /* The number_of_already_active_group_ase keeps all the active ases
   * in other devices in the group for the given direction.
   * This function counts active ases only for this device, and we count here
   * new active ases and already active ases which we want to reuse in the
   * scenario
   */
  uint8_t active_ases = *number_of_already_active_group_ase;

  // Before we activate the ASEs, make sure we have the right configuration
  // Check for matching PACs only if we know that the LTV format is being used.
  uint8_t max_required_ase_per_dev =
          ase_configs.size() / num_of_devices + (ase_configs.size() % num_of_devices);
  int needed_ase = std::min((int)(max_required_ase_per_dev), (int)(ase_configs.size()));
  log::debug("{}, {} {} ASE(s) required for this configuration.", address_, needed_ase,
             direction_str);

  for (int i = 0; i < needed_ase; ++i) {
    auto const& ase_cfg = ase_configs.at(i);
    if (utils::IsCodecUsingLtvFormat(ase_cfg.codec.id) &&
        !utils::GetConfigurationSupportedPac(pacs, ase_cfg.codec)) {
      log::error("{}, No {} PAC found matching codec: {}. Stop the activation.", address_,
                 direction_str, common::ToString(ase_cfg.codec));
      return false;
    }
  }

  auto strategy = utils::GetStrategyForAseConfig(group_ase_configs, num_of_devices);

  // Make sure we configure a single microphone if Dual Bidir SWB is not
  // supported.
  if (direction == types::kLeAudioDirectionSource &&
      !CodecManager::GetInstance()->IsDualBiDirSwbSupported() && (active_ases != 0)) {
    if (CodecManager::GetInstance()->CheckCodecConfigIsDualBiDirSwb(*audio_set_conf)) {
      log::error(
              "{}, trying to configure the dual bidir SWB, but the feature is "
              "disabled. This should not happen! Skipping ASE activation.",
              address_);
      return true;
    }
  }

  for (int i = 0; i < needed_ase && ase; ++i) {
    auto const& ase_cfg = ase_configs.at(i);
    ase->active = true;
    ase->configured_for_context_type = context_type;
    ase->data_path_configuration = ase_cfg.data_path_configuration;
    active_ases++;

    /* In case of late connect, we could be here for STREAMING ase.
     * in such case, it is needed to mark ase as known active ase which
     * is important to validate scenario and is done already few lines above.
     * Nothing more to do is needed here.
     */
    if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      if (ase->state == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) {
        ase->reconfigure = true;
      }

      ase->target_latency = ase_cfg.qos.target_latency;
      ase->codec_config = ase_cfg.codec;

      /* Let's choose audio channel allocation if not set */
      ase->codec_config.params.Add(
              codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
              PickAudioLocation(strategy, direction, audio_locations_, group_audio_locations_memo));

      /* Get default value if no requirement for specific frame blocks per sdu
       */
      if (utils::IsCodecUsingLtvFormat(ase->codec_config.id) &&
          !ase->codec_config.params.Find(codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu)) {
        ase->codec_config.params.Add(
                codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                GetMaxCodecFramesPerSduFromPac(
                        utils::GetConfigurationSupportedPac(pacs, ase_cfg.codec)));
      }

      ase->qos_config.sdu_interval = ase_cfg.qos.sduIntervalUs;
      ase->qos_config.max_sdu_size = ase_cfg.qos.maxSdu;
      ase->qos_config.retrans_nb = ase_cfg.qos.retransmission_number;
      ase->qos_config.max_transport_latency = ase_cfg.qos.max_transport_latency;

      SetMetadataToAse(ase, ase_cfg.metadata, metadata_context_types, ccid_lists);
    }

    log::debug(
            "device={}, activated ASE id={}, direction={}, max_sdu_size={}, "
            "cis_id={}, target_latency={}",
            address_, ase->id, direction_str, ase->qos_config.max_sdu_size, ase->cis_id,
            ase_cfg.qos.target_latency);

    /* Try to use the already active ASE */
    ase = GetNextActiveAseWithSameDirection(ase);
    if (ase == nullptr) {
      ase = GetFirstInactiveAse(direction, reuse_cis_id);
    }
  }

  *number_of_already_active_group_ase = active_ases;
  return true;
}

/* LeAudioDevice Class methods implementation */
void LeAudioDevice::SetConnectionState(DeviceConnectState state) {
  log::debug("{}, {} --> {}", address_, bluetooth::common::ToString(connection_state_),
             bluetooth::common::ToString(state));
  LeAudioLogHistory::Get()->AddLogHistory(kLogConnectionTag, group_id_, address_,
                                          bluetooth::common::ToString(connection_state_) + " -> ",
                                          "->" + bluetooth::common::ToString(state));
  connection_state_ = state;
}

DeviceConnectState LeAudioDevice::GetConnectionState(void) { return connection_state_; }

void LeAudioDevice::ClearPACs(void) {
  snk_pacs_.clear();
  src_pacs_.clear();
}

LeAudioDevice::~LeAudioDevice(void) {
  alarm_free(link_quality_timer);
  this->ClearPACs();
}

void LeAudioDevice::ParseHeadtrackingCodec(const struct types::acs_ac_record& pac) {
  if (pac.codec_id == types::kLeAudioCodecHeadtracking) {
    log::info("Headtracking supported");

    // Assume LE-ISO is supported if metadata is not available
    dsa_.modes = {
            DsaMode::DISABLED,
            DsaMode::ISO_SW,
            DsaMode::ISO_HW,
    };

    /*
     * Android Headtracker Codec Metadata description
     *   length: 5
     *   type: 0xFF
     *   value: {
     *     vendorId: 0x00E0 (Google)
     *     vendorSpecificMetadata: {
     *       length: 1
     *       type: 1 (Headtracker supported transports)
     *       value: x
     *     }
     *   }
     */
    std::vector<uint8_t> ltv = pac.metadata.RawPacket();
    if (ltv.size() < 7) {
      log::info("{}, headtracker codec does not have metadata", address_);
      return;
    }

    if (ltv[0] < 5 || ltv[1] != types::kLeAudioMetadataTypeVendorSpecific ||
        ltv[2] != (types::kLeAudioVendorCompanyIdGoogle & 0xFF) ||
        ltv[3] != (types::kLeAudioVendorCompanyIdGoogle >> 8) ||
        ltv[4] != types::kLeAudioMetadataHeadtrackerTransportLen ||
        ltv[5] != types::kLeAudioMetadataHeadtrackerTransportVal) {
      log::warn("{}, headtracker codec metadata invalid", address_);
      return;
    }

    // Valid headtracker codec metadata available, so it must support reduced sdu size
    dsa_.reduced_sdu = true;

    uint8_t supported_transports = ltv[6];
    DsaModes dsa_modes = {DsaMode::DISABLED};

    if ((supported_transports & types::kLeAudioMetadataHeadtrackerTransportLeAcl) != 0) {
      log::debug("{}, headtracking supported over LE-ACL", address_);
      dsa_modes.push_back(DsaMode::ACL);
    }

    if ((supported_transports & types::kLeAudioMetadataHeadtrackerTransportLeIso) != 0) {
      log::debug("{}, headtracking supported over LE-ISO", address_);
      dsa_modes.push_back(DsaMode::ISO_SW);
      dsa_modes.push_back(DsaMode::ISO_HW);
    }

    dsa_.modes = dsa_modes;
  }
}

void LeAudioDevice::RegisterPACs(std::vector<struct types::acs_ac_record>* pac_db,
                                 std::vector<struct types::acs_ac_record>* pac_recs) {
  /* Clear PAC database for characteristic in case if re-read, indicated */
  if (!pac_db->empty()) {
    log::debug("{}, upgrade PACs for characteristic", address_);
    pac_db->clear();
  }

  dsa_.modes = {DsaMode::DISABLED};

  /* TODO wrap this logging part with debug flag */
  for (const struct types::acs_ac_record& pac : *pac_recs) {
    std::stringstream debug_str;
    debug_str << "Registering PAC" << "\n\tCoding format: " << loghex(pac.codec_id.coding_format)
              << "\n\tVendor codec company ID: " << loghex(pac.codec_id.vendor_company_id)
              << "\n\tVendor codec ID: " << loghex(pac.codec_id.vendor_codec_id)
              << "\n\tCodec spec caps:\n";
    if (utils::IsCodecUsingLtvFormat(pac.codec_id) && !pac.codec_spec_caps.IsEmpty()) {
      debug_str << pac.codec_spec_caps.ToString("", types::CodecCapabilitiesLtvFormat);
    } else {
      debug_str << base::HexEncode(pac.codec_spec_caps_raw.data(), pac.codec_spec_caps_raw.size());
    }

    debug_str << "\n\tMetadata: " << pac.metadata.ToString();
    log::debug("{}", debug_str.str());

    ParseHeadtrackingCodec(pac);
  }

  pac_db->insert(pac_db->begin(), pac_recs->begin(), pac_recs->end());
}

struct ase* LeAudioDevice::GetAseByValHandle(uint16_t val_hdl) {
  auto iter = std::find_if(ases_.begin(), ases_.end(),
                           [&val_hdl](const auto& ase) { return ase.hdls.val_hdl == val_hdl; });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

int LeAudioDevice::GetAseCount(uint8_t direction) {
  return std::count_if(ases_.begin(), ases_.end(),
                       [direction](const auto& a) { return a.direction == direction; });
}

struct ase* LeAudioDevice::GetFirstAseWithState(uint8_t direction, AseState state) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [direction, state](const auto& ase) {
    return (ase.direction == direction) && (ase.state == state);
  });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetFirstActiveAse(void) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) { return ase.active; });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetFirstActiveAseByDirection(uint8_t direction) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [direction](const auto& ase) {
    return ase.active && (ase.direction == direction);
  });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetNextActiveAseWithSameDirection(struct ase* base_ase) {
  auto iter = std::find_if(ases_.begin(), ases_.end(),
                           [&base_ase](auto& ase) { return base_ase == &ase; });

  /* Invalid ase given */
  if (iter == ases_.end() || std::distance(iter, ases_.end()) < 1) {
    return nullptr;
  }

  iter = std::find_if(std::next(iter, 1), ases_.end(), [&iter](const auto& ase) {
    return ase.active && (*iter).direction == ase.direction;
  });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetNextActiveAseWithDifferentDirection(struct ase* base_ase) {
  auto iter = std::find_if(ases_.begin(), ases_.end(),
                           [&base_ase](auto& ase) { return base_ase == &ase; });

  /* Invalid ase given */
  if (std::distance(iter, ases_.end()) < 1) {
    log::debug("{}, ASE {} does not use bidirectional CIS", address_, base_ase->id);
    return nullptr;
  }

  iter = std::find_if(std::next(iter, 1), ases_.end(), [&iter](const auto& ase) {
    return ase.active && iter->direction != ase.direction;
  });

  if (iter == ases_.end()) {
    return nullptr;
  }

  return &(*iter);
}

struct ase* LeAudioDevice::GetFirstActiveAseByCisAndDataPathState(CisState cis_state,
                                                                  DataPathState data_path_state) {
  auto iter =
          std::find_if(ases_.begin(), ases_.end(), [cis_state, data_path_state](const auto& ase) {
            return ase.active && (ase.data_path_state == data_path_state) &&
                   (ase.cis_state == cis_state);
          });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetFirstInactiveAse(uint8_t direction, bool reuse_cis_id) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [direction, reuse_cis_id](const auto& ase) {
    if (ase.active || (ase.direction != direction)) {
      return false;
    }

    if (!reuse_cis_id) {
      return true;
    }

    return ase.cis_id != kInvalidCisId;
  });
  /* If ASE is found, return it */
  if (iter != ases_.end()) {
    return &(*iter);
  }

  /* If reuse was not set, that means there is no inactive ASE available. */
  if (!reuse_cis_id) {
    return nullptr;
  }

  /* Since there is no ASE with assigned CIS ID, it means new configuration
   * needs more ASEs then it was configured before.
   * Let's find just inactive one */
  iter = std::find_if(ases_.begin(), ases_.end(), [direction](const auto& ase) {
    if (ase.active || (ase.direction != direction)) {
      return false;
    }
    return true;
  });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetNextActiveAse(struct ase* base_ase) {
  auto iter = std::find_if(ases_.begin(), ases_.end(),
                           [&base_ase](auto& ase) { return base_ase == &ase; });

  /* Invalid ase given */
  if (iter == ases_.end() || std::distance(iter, ases_.end()) < 1) {
    return nullptr;
  }

  iter = std::find_if(std::next(iter, 1), ases_.end(), [](const auto& ase) { return ase.active; });

  return (iter == ases_.end()) ? nullptr : &(*iter);
}

struct ase* LeAudioDevice::GetAseToMatchBidirectionCis(struct ase* base_ase) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [&base_ase](auto& ase) {
    return (base_ase->cis_conn_hdl == ase.cis_conn_hdl) && (base_ase->direction != ase.direction);
  });
  return (iter == ases_.end()) ? nullptr : &(*iter);
}

BidirectionalPair<struct ase*> LeAudioDevice::GetAsesByCisConnHdl(uint16_t conn_hdl) {
  BidirectionalPair<struct ase*> ases = {nullptr, nullptr};

  for (auto& ase : ases_) {
    if (ase.cis_conn_hdl == conn_hdl) {
      if (ase.direction == types::kLeAudioDirectionSink) {
        ases.sink = &ase;
      } else {
        ases.source = &ase;
      }
    }
  }

  return ases;
}

BidirectionalPair<struct ase*> LeAudioDevice::GetAsesByCisId(uint8_t cis_id) {
  BidirectionalPair<struct ase*> ases = {nullptr, nullptr};

  for (auto& ase : ases_) {
    if (ase.cis_id == cis_id) {
      if (ase.direction == types::kLeAudioDirectionSink) {
        ases.sink = &ase;
      } else {
        ases.source = &ase;
      }
    }
  }

  return ases;
}

bool LeAudioDevice::HaveActiveAse(void) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) { return ase.active; });

  return iter != ases_.end();
}

bool LeAudioDevice::HaveAnyReleasingAse(void) {
  /* In configuring state when active in Idle or Configured and reconfigure */
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) {
    if (!ase.active) {
      return false;
    }
    return ase.state == AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING;
  });

  return iter != ases_.end();
}

bool LeAudioDevice::HaveAnyStreamingAses(void) {
  /* In configuring state when active in Idle or Configured and reconfigure */
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) {
    if (!ase.active) {
      return false;
    }

    if (ase.state == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      return true;
    }

    return false;
  });

  return iter != ases_.end();
}

bool LeAudioDevice::HaveAnyUnconfiguredAses(void) {
  /* In configuring state when active in Idle or Configured and reconfigure */
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) {
    if (!ase.active) {
      return false;
    }

    if (ase.state == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE ||
        ((ase.state == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) && ase.reconfigure)) {
      return true;
    }

    return false;
  });

  return iter != ases_.end();
}

bool LeAudioDevice::HaveAllActiveAsesSameState(AseState state) {
  log::verbose("{}", address_);
  auto iter = std::find_if(ases_.begin(), ases_.end(), [&state](const auto& ase) {
    log::verbose("ASE id: {}, active: {}, state: {}", ase.id, ase.active,
                 bluetooth::common::ToString(ase.state));
    return ase.active && (ase.state != state);
  });

  return iter == ases_.end();
}

bool LeAudioDevice::HaveAllActiveAsesSameDataPathState(types::DataPathState state) const {
  log::verbose("{}", address_);
  auto iter = std::find_if(ases_.begin(), ases_.end(), [&state](const auto& ase) {
    log::verbose("ASE id: {}, active: {}, state: {}", ase.id, ase.active,
                 bluetooth::common::ToString(ase.data_path_state));
    return ase.active && (ase.data_path_state != state);
  });

  return iter == ases_.end();
}

bool LeAudioDevice::IsReadyToCreateStream(void) {
  log::verbose("{}", address_);
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) {
    if (!ase.active) {
      return false;
    }

    log::verbose("ASE id: {}, state: {}, direction: {}", ase.id,
                 bluetooth::common::ToString(ase.state), ase.direction);
    if (ase.direction == types::kLeAudioDirectionSink &&
        (ase.state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING &&
         ase.state != AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING)) {
      return true;
    }

    if (ase.direction == types::kLeAudioDirectionSource &&
        ase.state != AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING) {
      return true;
    }

    return false;
  });

  return iter == ases_.end();
}

bool LeAudioDevice::IsReadyToSuspendStream(void) {
  auto iter = std::find_if(ases_.begin(), ases_.end(), [](const auto& ase) {
    if (!ase.active) {
      return false;
    }

    if (ase.direction == types::kLeAudioDirectionSink &&
        ase.state != AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
      return true;
    }

    if (ase.direction == types::kLeAudioDirectionSource &&
        ase.state != AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
      return true;
    }

    return false;
  });

  return iter == ases_.end();
}

bool LeAudioDevice::HaveAllActiveAsesCisEst(void) const {
  if (ases_.empty()) {
    log::warn("No ases for device {}", address_);
    /* If there is no ASEs at all, it means we are good here - meaning, it is
     * not waiting for any CIS to be established.
     */
    return true;
  }

  log::verbose("{}", address_);

  bool has_active_ase = false;
  auto iter = std::find_if(ases_.begin(), ases_.end(), [&](const auto& ase) {
    if (!has_active_ase && ase.active) {
      has_active_ase = true;
    }
    log::verbose("ASE id: {}, cis_state: {}, direction: {}", ase.id,
                 bluetooth::common::ToString(ase.cis_state), ase.direction);

    return ase.active && (ase.cis_state != CisState::CONNECTED);
  });

  return iter == ases_.end() && has_active_ase;
}

bool LeAudioDevice::HaveAnyCisConnected(void) {
  /* Pending and Disconnecting is considered as connected in this function */
  for (auto const ase : ases_) {
    if (ase.cis_state == CisState::CONNECTED || ase.cis_state == CisState::CONNECTING ||
        ase.cis_state == CisState::DISCONNECTING) {
      return true;
    }
  }
  return false;
}

uint8_t LeAudioDevice::GetSupportedAudioChannelCounts(uint8_t direction) const {
  auto& pacs = direction == types::kLeAudioDirectionSink ? snk_pacs_ : src_pacs_;

  if (pacs.size() == 0) {
    log::error("{}, missing PAC for direction {}", address_, direction);
    return 0;
  }

  for (const auto& pac_tuple : pacs) {
    /* Get PAC records from tuple as second element from tuple */
    auto& pac_recs = std::get<1>(pac_tuple);

    for (const auto pac : pac_recs) {
      if (!utils::IsCodecUsingLtvFormat(pac.codec_id)) {
        log::warn(" {} Unknown codec PAC record for codec: {}", address_,
                  bluetooth::common::ToString(pac.codec_id));
        continue;
      }
      log::assert_that(!pac.codec_spec_caps.IsEmpty(),
                       "Codec specific capabilities are not parsed appropriately.");

      auto supported_channel_count_ltv =
              pac.codec_spec_caps.Find(codec_spec_caps::kLeAudioLtvTypeSupportedAudioChannelCounts);

      if (supported_channel_count_ltv == std::nullopt ||
          supported_channel_count_ltv->size() == 0L) {
        return 1;
      }

      return VEC_UINT8_TO_UINT8(supported_channel_count_ltv.value());
    };
  }

  return 0;
}

/**
 * Returns supported PHY's bitfield
 */
uint8_t LeAudioDevice::GetPhyBitmask(void) const {
  uint8_t phy_bitfield = kIsoCigPhy1M;

  if (get_btm_client_interface().peer.BTM_IsPhy2mSupported(address_, BT_TRANSPORT_LE)) {
    phy_bitfield |= kIsoCigPhy2M;
  }

  return phy_bitfield;
}

void LeAudioDevice::PrintDebugState(void) {
  std::stringstream debug_str;

  debug_str << " Address: " << address_ << ", " << bluetooth::common::ToString(connection_state_)
            << ", conn_id: " << +conn_id_ << ", mtu: " << +mtu_
            << ", num_of_ase: " << static_cast<int>(ases_.size());

  if (ases_.size() > 0) {
    debug_str << "\n  == ASEs == ";
    for (auto& ase : ases_) {
      debug_str << "  id: " << +ase.id << ", active: " << ase.active
                << ", dir: " << (ase.direction == types::kLeAudioDirectionSink ? "sink" : "source")
                << ", state: " << bluetooth::common::ToString(ase.state)
                << ", cis_id: " << +ase.cis_id << ", cis_handle: " << +ase.cis_conn_hdl
                << ", cis_state: " << bluetooth::common::ToString(ase.cis_state)
                << ", data_path_state: " << bluetooth::common::ToString(ase.data_path_state)
                << "\n ase max_latency: " << +ase.qos_config.max_transport_latency
                << ", rtn: " << +ase.qos_config.retrans_nb
                << ", max_sdu: " << +ase.qos_config.max_sdu_size
                << ", sdu_interval: " << +ase.qos_config.sdu_interval
                << ", presentation_delay: " << +ase.qos_config.presentation_delay
                << ", framing: " << +ase.qos_config.framing << ", phy: " << +ase.qos_config.phy
                << ", target latency: " << +ase.target_latency
                << ", reconfigure: " << ase.reconfigure << "\n\n";
    }
  }

  log::info("{}", debug_str.str());
}

uint8_t LeAudioDevice::GetPreferredPhyBitmask(uint8_t preferred_phy) const {
  // Start with full local phy support
  uint8_t phy_bitmask = bluetooth::hci::kIsoCigPhy1M;
  if (bluetooth::shim::GetController()->SupportsBle2mPhy()) {
    phy_bitmask |= bluetooth::hci::kIsoCigPhy2M;
  }
  if (bluetooth::shim::GetController()->SupportsBleCodedPhy()) {
    phy_bitmask |= bluetooth::hci::kIsoCigPhyC;
  }

  // Check against the remote device support
  phy_bitmask &= GetPhyBitmask();

  // Take the preferences if possible
  if (preferred_phy && (phy_bitmask & preferred_phy)) {
    phy_bitmask &= preferred_phy;
    log::debug("{},  using ASE preferred phy 0x{:02x}", address_, static_cast<int>(phy_bitmask));
  } else {
    log::warn(
            " {}, ASE preferred 0x{:02x} has nothing common with phy_bitfield  "
            "0x{:02x}",
            address_, static_cast<int>(preferred_phy), static_cast<int>(phy_bitmask));
  }
  return phy_bitmask;
}

void LeAudioDevice::DumpPacsDebugState(std::stringstream& stream,
                                       types::PublishedAudioCapabilities pacs) {
  if (pacs.size() > 0) {
    for (auto& pac : pacs) {
      stream << "\t    • Value handle: " << loghex(std::get<0>(pac).val_hdl)
             << ", CCC handle: " << loghex(std::get<0>(pac).ccc_hdl);

      for (auto& record : std::get<1>(pac)) {
        stream << "\n\t\t· CodecId (Coding format: " << loghex(record.codec_id.coding_format)
               << ", Vendor company ID: " << loghex(record.codec_id.vendor_company_id)
               << ", Vendor codec ID: " << loghex(record.codec_id.vendor_codec_id) << ")";
        stream << "\n\t\t    Codec specific capabilities:\n";
        if (utils::IsCodecUsingLtvFormat(record.codec_id)) {
          stream << record.codec_spec_caps.ToString("\t\t\t", types::CodecCapabilitiesLtvFormat);
        } else {
          stream << "\t\t\t"
                 << base::HexEncode(record.codec_spec_caps_raw.data(),
                                    record.codec_spec_caps_raw.size())
                 << "\n";
        }
        stream << "\t\t    Metadata: " << record.metadata.ToString();
      }
      stream << "\n";
    }
  }
}

void LeAudioDevice::DumpPacsDebugState(std::stringstream& stream) {
  stream << "      ● Device PACS, address: " << address_.ToRedactedStringForLogging() << "\n";
  stream << "\t  == Sink PACs:\n";
  DumpPacsDebugState(stream, snk_pacs_);
  stream << "\t  == Source PACs:\n";
  DumpPacsDebugState(stream, src_pacs_);
}

static std::string locationToString(uint32_t location) {
  if (location & codec_spec_conf::kLeAudioLocationAnyLeft &&
      location & codec_spec_conf::kLeAudioLocationAnyRight) {
    return "left/right";
  } else if (location & codec_spec_conf::kLeAudioLocationAnyLeft) {
    return "left";
  } else if (location & codec_spec_conf::kLeAudioLocationAnyRight) {
    return "right";
  } else if (location == codec_spec_conf::kLeAudioLocationMonoAudio) {
    return "mono";
  }
  return "unknown location";
}

void LeAudioDevice::Dump(std::stringstream& stream) {
  uint16_t acl_handle =
          get_btm_client_interface().peer.BTM_GetHCIConnHandle(address_, BT_TRANSPORT_LE);
  std::string snk_location = audio_locations_.sink
                                     ? locationToString(audio_locations_.sink->value.to_ulong())
                                     : "None";
  std::string src_location = audio_locations_.source
                                     ? locationToString(audio_locations_.source->value.to_ulong())
                                     : "None";

  stream << "      ● Device address: " << address_.ToRedactedStringForLogging() << ", "
         << connection_state_
         << ", conn_id: " << (conn_id_ == GATT_INVALID_CONN_ID ? "-1" : std::to_string(conn_id_))
         << ", acl_handle: " << std::to_string(acl_handle) << ", snk_location: " << snk_location
         << ", src_location: " << src_location << ", mtu: " << std::to_string(mtu_) << ", "
         << (encrypted_ ? "Encrypted" : "Unecrypted")
         << "\n\t  Sink avail. contexts: " << common::ToString(avail_contexts_.sink)
         << "\n\t  Source avail. contexts: " << common::ToString(avail_contexts_.source) << "\n";

  if (gmap_client_ != nullptr) {
    stream << "\t  ";
    gmap_client_->DebugDump(stream);
  } else {
    stream << "\t  ";
    stream << "GmapClient not initialized\n";
  }

  if (ases_.size() > 0) {
    stream << "\t  == ASEs (" << static_cast<int>(ases_.size()) << "):\n";
    stream << "\t    id  active dir     cis_id  cis_handle  sdu  latency rtn  "
              "cis_state            data_path_state\n";
    for (auto& ase : ases_) {
      stream << std::setfill('\x20') << "\t    " << std::left << std::setw(4)
             << static_cast<int>(ase.id) << std::left << std::setw(7)
             << (ase.active ? "true" : "false") << std::left << std::setw(8)
             << (ase.direction == types::kLeAudioDirectionSink ? "sink" : "source") << std::left
             << std::setw(8) << static_cast<int>(ase.cis_id) << std::left << std::setw(12)
             << ase.cis_conn_hdl << std::left << std::setw(5) << ase.qos_config.max_sdu_size
             << std::left << std::setw(8) << ase.qos_config.max_transport_latency << std::left
             << std::setw(5) << static_cast<int>(ase.qos_config.retrans_nb) << std::left
             << std::setw(21) << bluetooth::common::ToString(ase.cis_state) << std::setw(19)
             << bluetooth::common::ToString(ase.data_path_state) << "\n";
    }
  }
}

void LeAudioDevice::DisconnectAcl(void) {
  if (conn_id_ == GATT_INVALID_CONN_ID) {
    return;
  }

  uint16_t acl_handle =
          get_btm_client_interface().peer.BTM_GetHCIConnHandle(address_, BT_TRANSPORT_LE);
  if (acl_handle != HCI_INVALID_HANDLE) {
    acl_disconnect_from_handle(acl_handle, HCI_ERR_PEER_USER,
                               "bta::bluetooth::le_audio::client disconnect");
  }
}

void LeAudioDevice::SetAvailableContexts(BidirectionalPair<AudioContexts> contexts) {
  log::debug(
          "{}: \n\t previous_contexts_.sink: {} \n\t previous_contexts_.source: {} "
          " "
          "\n\t new_contexts.sink: {} \n\t new_contexts.source: {} \n\t",
          address_, avail_contexts_.sink.to_string(), avail_contexts_.source.to_string(),
          contexts.sink.to_string(), contexts.source.to_string());

  avail_contexts_.sink = contexts.sink;
  avail_contexts_.source = contexts.source;
}

void LeAudioDevice::SetMetadataToAse(struct types::ase* ase,
                                     const types::LeAudioLtvMap& base_metadata,
                                     const AudioContexts& metadata_context_types,
                                     const std::vector<uint8_t>& ccid_lists) {
  /* Filter multidirectional audio context for each ase direction */
  auto directional_audio_context = metadata_context_types & GetAvailableContexts(ase->direction);
  /* Tha base metadata will be extended (or partially overridden if any key already exist) */
  ase->metadata = base_metadata;
  if (directional_audio_context.any()) {
    ase->metadata.Append(GetMetadata(directional_audio_context, ccid_lists));
  } else {
    ase->metadata.Append(
            GetMetadata(AudioContexts(LeAudioContextType::UNSPECIFIED), std::vector<uint8_t>()));
  }
}

bool LeAudioDevice::ActivateConfiguredAses(
        LeAudioContextType context_type,
        const BidirectionalPair<AudioContexts>& metadata_context_types,
        BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
  if (conn_id_ == GATT_INVALID_CONN_ID) {
    log::warn("Device {} is not connected", address_);
    return false;
  }

  bool ret = false;

  log::info("Configuring device {}", address_);
  for (auto& ase : ases_) {
    if (ase.state == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED &&
        ase.configured_for_context_type == context_type) {
      log::info(
              "conn_id: {}, ase id {}, cis id {}, cis_handle 0x{:04x} is "
              "activated.",
              conn_id_, ase.id, ase.cis_id, ase.cis_conn_hdl);
      ase.active = true;
      ret = true;
      /* update the already set metadata */
      SetMetadataToAse(&ase, ase.metadata, metadata_context_types.get(ase.direction),
                       ccid_lists.get(ase.direction));
    }
  }

  return ret;
}

void LeAudioDevice::DeactivateAllAses(void) {
  for (auto& ase : ases_) {
    if (ase.active == false && ase.cis_state != CisState::IDLE &&
        ase.data_path_state != DataPathState::IDLE) {
      log::warn(
              "{}, ase_id: {}, ase.cis_id: {}, cis_handle: 0x{:02x}, "
              "ase.cis_state={}, ase.data_path_state={}",
              address_, ase.id, ase.cis_id, ase.cis_conn_hdl,
              bluetooth::common::ToString(ase.cis_state),
              bluetooth::common::ToString(ase.data_path_state));
    }

    log::verbose("{}, ase_id {}", address_, ase.id);

    ase.state = AseState::BTA_LE_AUDIO_ASE_STATE_IDLE;
    ase.cis_state = CisState::IDLE;
    ase.data_path_state = DataPathState::IDLE;
    ase.active = false;
    ase.reconfigure = 0;
    ase.cis_id = bluetooth::le_audio::kInvalidCisId;
    ase.cis_conn_hdl = bluetooth::le_audio::kInvalidCisConnHandle;
  }
}

types::LeAudioLtvMap LeAudioDevice::GetMetadata(AudioContexts context_type,
                                                const std::vector<uint8_t>& ccid_list) {
  types::LeAudioLtvMap metadata;
  metadata.Add(types::kLeAudioMetadataTypeStreamingAudioContext, context_type.value());
  if (ccid_list.size()) {
    metadata.Add(types::kLeAudioMetadataTypeCcidList, ccid_list);
  }
  return metadata;
}

bool LeAudioDevice::IsMetadataChanged(const BidirectionalPair<AudioContexts>& context_types,
                                      const BidirectionalPair<std::vector<uint8_t>>& ccid_lists) {
  for (auto* ase = this->GetFirstActiveAse(); ase; ase = this->GetNextActiveAse(ase)) {
    if (this->GetMetadata(context_types.get(ase->direction), ccid_lists.get(ase->direction)) !=
        ase->metadata) {
      return true;
    }
  }

  return false;
}

void LeAudioDevice::GetDeviceModelName(void) {
  bt_property_t prop_name;
  bt_bdname_t prop_value = {0};
  // Retrieve model name from storage
  BTIF_STORAGE_FILL_PROPERTY(&prop_name, BT_PROPERTY_REMOTE_MODEL_NUM, sizeof(bt_bdname_t),
                             &prop_value);
  if (btif_storage_get_remote_device_property(&address_, &prop_name) == BT_STATUS_SUCCESS) {
    model_name_.assign((char*)prop_value.name);
  }
}

void LeAudioDevice::UpdateDeviceAllowlistFlag(void) {
  char allow_list[PROPERTY_VALUE_MAX] = {0};
  GetDeviceModelName();
  osi_property_get(kLeAudioDeviceAllowListProp, allow_list, "");
  if (allow_list[0] == '\0' || model_name_ == "") {
    // if device allow list is empty or no remote model name available
    // return allowlist_flag_ as default false
    return;
  }

  std::istringstream stream(allow_list);
  std::string token;
  while (std::getline(stream, token, ',')) {
    if (token.compare(model_name_) == 0) {
      allowlist_flag_ = true;
      return;
    }
  }
}

DsaModes LeAudioDevice::GetDsaModes(void) { return dsa_.modes; }

bool LeAudioDevice::DsaReducedSduSizeSupported() { return dsa_.reduced_sdu; }

types::DataPathState LeAudioDevice::GetDsaDataPathState(void) { return dsa_.state; }

void LeAudioDevice::SetDsaDataPathState(types::DataPathState state) { dsa_.state = state; }

uint16_t LeAudioDevice::GetDsaCisHandle(void) { return dsa_.cis_handle; }

void LeAudioDevice::SetDsaCisHandle(uint16_t cis_handle) { dsa_.cis_handle = cis_handle; }

/* LeAudioDevices Class methods implementation */
void LeAudioDevices::Add(const RawAddress& address, DeviceConnectState state, int group_id) {
  auto device = FindByAddress(address);
  if (device != nullptr) {
    log::error("address: {} is already assigned to group: {}", address, device->group_id_);
    return;
  }

  leAudioDevices_.emplace_back(std::make_shared<LeAudioDevice>(address, state, group_id));
}

void LeAudioDevices::Remove(const RawAddress& address) {
  auto iter = std::find_if(
          leAudioDevices_.begin(), leAudioDevices_.end(),
          [&address](auto const& leAudioDevice) { return leAudioDevice->address_ == address; });

  if (iter == leAudioDevices_.end()) {
    log::error("no such address: {}", address);
    return;
  }

  leAudioDevices_.erase(iter);
}

LeAudioDevice* LeAudioDevices::FindByAddress(const RawAddress& address) const {
  auto iter = std::find_if(
          leAudioDevices_.begin(), leAudioDevices_.end(),
          [&address](auto const& leAudioDevice) { return leAudioDevice->address_ == address; });

  return (iter == leAudioDevices_.end()) ? nullptr : iter->get();
}

std::shared_ptr<LeAudioDevice> LeAudioDevices::GetByAddress(const RawAddress& address) const {
  auto iter = std::find_if(
          leAudioDevices_.begin(), leAudioDevices_.end(),
          [&address](auto const& leAudioDevice) { return leAudioDevice->address_ == address; });

  return (iter == leAudioDevices_.end()) ? nullptr : *iter;
}

LeAudioDevice* LeAudioDevices::FindByConnId(tCONN_ID conn_id) const {
  auto iter = std::find_if(
          leAudioDevices_.begin(), leAudioDevices_.end(),
          [&conn_id](auto const& leAudioDevice) { return leAudioDevice->conn_id_ == conn_id; });

  return (iter == leAudioDevices_.end()) ? nullptr : iter->get();
}

LeAudioDevice* LeAudioDevices::FindByCisConnHdl(uint8_t cig_id, uint16_t conn_hdl) const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(),
                           [&conn_hdl, &cig_id](auto& d) {
                             LeAudioDevice* dev;
                             BidirectionalPair<struct ase*> ases;

                             dev = d.get();
                             if (dev->group_id_ != cig_id) {
                               return false;
                             }

                             ases = dev->GetAsesByCisConnHdl(conn_hdl);
                             if (ases.sink || ases.source) {
                               return true;
                             } else {
                               return false;
                             }
                           });

  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  return iter->get();
}

void LeAudioDevices::SetInitialGroupAutoconnectState(int group_id, int gatt_if,
                                                     tBTM_BLE_CONN_TYPE /*reconnection_mode*/,
                                                     bool current_dev_autoconnect_flag) {
  if (!current_dev_autoconnect_flag) {
    /* If current device autoconnect flag is false, check if there is other
     * device in the group which is in autoconnect mode.
     * If yes, assume whole group is in autoconnect.
     */
    auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&group_id](auto& d) {
      LeAudioDevice* dev;
      dev = d.get();
      if (dev->group_id_ != group_id) {
        return false;
      }
      return dev->autoconnect_flag_;
    });

    current_dev_autoconnect_flag = !(iter == leAudioDevices_.end());
  }

  if (!current_dev_autoconnect_flag) {
    return;
  }

  /* This function is called when bluetooth started, therefore here we will
   * try direct connection, if that failes, we fallback to background connection
   */
  for (auto dev : leAudioDevices_) {
    if ((dev->group_id_ == group_id) &&
        (dev->GetConnectionState() == DeviceConnectState::DISCONNECTED)) {
      dev->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);
      dev->autoconnect_flag_ = true;
      btif_storage_set_leaudio_autoconnect(dev->address_, true);
      BTA_GATTC_Open(gatt_if, dev->address_, BTM_BLE_DIRECT_CONNECTION, false);
    }
  }
}

size_t LeAudioDevices::Size() const { return leAudioDevices_.size(); }

void LeAudioDevices::Dump(std::stringstream& stream, int group_id) const {
  for (auto const& device : leAudioDevices_) {
    if (device->group_id_ == group_id) {
      device->Dump(stream);

      stream << "\tAddress: " << device->address_ << "\n";
      device->DumpPacsDebugState(stream);
      stream << "\n";
    }
  }
}

void LeAudioDevices::Cleanup(tGATT_IF client_if) {
  for (auto const& device : leAudioDevices_) {
    auto connection_state = device->GetConnectionState();
    if (connection_state == DeviceConnectState::DISCONNECTED ||
        connection_state == DeviceConnectState::DISCONNECTING) {
      continue;
    }

    // For connecting or connected device always remove background connect
    BTA_GATTC_CancelOpen(client_if, device->address_, false);

    if (connection_state == DeviceConnectState::CONNECTING_BY_USER) {
      // When connecting by user, remove direct connect
      BTA_GATTC_CancelOpen(client_if, device->address_, true);
    } else if (connection_state != DeviceConnectState::CONNECTING_AUTOCONNECT) {
      // If connected, close the connection
      BtaGattQueue::Clean(device->conn_id_);
      BTA_GATTC_Close(device->conn_id_);
      device->DisconnectAcl();
    }
  }
  leAudioDevices_.clear();
}

}  // namespace bluetooth::le_audio
