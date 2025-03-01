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

#include "device_groups.h"

#include <bluetooth/log.h>
#include <stdio.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <iterator>
#include <memory>
#include <optional>
#include <sstream>
#include <utility>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/le_audio/gmap_server.h"
#include "bta_csis_api.h"
#include "bta_groups.h"
#include "btif/include/btif_profile_storage.h"
#include "btm_ble_api_types.h"
#include "btm_iso_api.h"
#include "btm_iso_api_types.h"
#include "client_parser.h"
#include "com_android_bluetooth_flags.h"
#include "common/strings.h"
#include "gatt_api.h"
#include "hardware/bt_le_audio.h"
#include "hci/controller_interface.h"
#include "hci_error_code.h"
#include "internal_include/bt_trace.h"
#include "le_audio/codec_manager.h"
#include "le_audio/devices.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_utils.h"
#include "main/shim/entry.h"
#include "metrics_collector.h"
#include "stack/include/btm_client_interface.h"
#include "types/bt_transport.h"

namespace bluetooth::le_audio {

using bluetooth::le_audio::types::ase;
using types::AseState;
using types::AudioContexts;
using types::AudioLocations;
using types::BidirectionalPair;
using types::CisState;
using types::CisType;
using types::DataPathState;
using types::LeAudioContextType;

/* LeAudioDeviceGroup Class methods implementation */
void LeAudioDeviceGroup::AddNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice) {
  leAudioDevice->group_id_ = group_id_;
  leAudioDevices_.push_back(std::weak_ptr<LeAudioDevice>(leAudioDevice));
  MetricsCollector::Get()->OnGroupSizeUpdate(group_id_, leAudioDevices_.size());
}

void LeAudioDeviceGroup::RemoveNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice) {
  /* Group information cleaning in the device. */
  leAudioDevice->group_id_ = bluetooth::groups::kGroupUnknown;
  for (auto ase : leAudioDevice->ases_) {
    ase.active = false;
    ase.cis_conn_hdl = kInvalidCisConnHandle;
  }

  leAudioDevices_.erase(
          std::remove_if(leAudioDevices_.begin(), leAudioDevices_.end(),
                         [&leAudioDevice](auto& d) { return d.lock() == leAudioDevice; }),
          leAudioDevices_.end());
  MetricsCollector::Get()->OnGroupSizeUpdate(group_id_, leAudioDevices_.size());
}

bool LeAudioDeviceGroup::IsEmpty(void) const { return leAudioDevices_.size() == 0; }

bool LeAudioDeviceGroup::IsAnyDeviceConnected(void) const { return NumOfConnected() != 0; }

int LeAudioDeviceGroup::Size(void) const { return leAudioDevices_.size(); }

int LeAudioDeviceGroup::DesiredSize(void) const {
  int group_size = 0;
  if (bluetooth::csis::CsisClient::IsCsisClientRunning()) {
    group_size = bluetooth::csis::CsisClient::Get()->GetDesiredSize(group_id_);
  }

  return group_size > 0 ? group_size : leAudioDevices_.size();
}

int LeAudioDeviceGroup::NumOfConnected() const {
  /* return number of connected devices from the set*/
  return std::count_if(leAudioDevices_.begin(), leAudioDevices_.end(), [](auto& iter) {
    auto dev = iter.lock();
    if (dev) {
      return (dev->conn_id_ != GATT_INVALID_CONN_ID) &&
             (dev->GetConnectionState() == DeviceConnectState::CONNECTED);
    }
    return false;
  });
}

int LeAudioDeviceGroup::NumOfAvailableForDirection(int direction) const {
  bool check_ase_count = direction < types::kLeAudioDirectionBoth;

  /* return number of connected devices from the set with supported context */
  return std::count_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&](auto& iter) {
    auto dev = iter.lock();
    if (dev) {
      if (check_ase_count && (dev->GetAseCount(direction) == 0)) {
        return false;
      }
      return (dev->conn_id_ != GATT_INVALID_CONN_ID) &&
             (dev->GetConnectionState() == DeviceConnectState::CONNECTED);
    }
    return false;
  });
}

void LeAudioDeviceGroup::ClearSinksFromConfiguration(void) {
  log::info("Group {}, group_id {}", std::format_ptr(this), group_id_);

  auto direction = types::kLeAudioDirectionSink;
  stream_conf.stream_params.get(direction).clear();
  CodecManager::GetInstance()->ClearCisConfiguration(direction);
}

void LeAudioDeviceGroup::ClearSourcesFromConfiguration(void) {
  log::info("Group {}, group_id {}", std::format_ptr(this), group_id_);

  auto direction = types::kLeAudioDirectionSource;
  stream_conf.stream_params.get(direction).clear();
  CodecManager::GetInstance()->ClearCisConfiguration(direction);
}

void LeAudioDeviceGroup::ClearAllCises(void) {
  log::info("group_id: {}", group_id_);
  cig.cises.clear();
  ClearSinksFromConfiguration();
  ClearSourcesFromConfiguration();
}

void LeAudioDeviceGroup::UpdateCisConfiguration(uint8_t direction) {
  CodecManager::GetInstance()->UpdateCisConfiguration(
          cig.cises, stream_conf.stream_params.get(direction), direction);
}

void LeAudioDeviceGroup::Cleanup(void) {
  /* Bluetooth is off while streaming - disconnect CISes and remove CIG */
  if (GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
    auto& sink_stream_locations = stream_conf.stream_params.sink.stream_config.stream_map;
    auto& source_stream_locations = stream_conf.stream_params.source.stream_config.stream_map;

    if (!sink_stream_locations.empty()) {
      for (const auto info : sink_stream_locations) {
        auto cis_handle = info.stream_handle;
        bluetooth::hci::IsoManager::GetInstance()->DisconnectCis(cis_handle, HCI_ERR_PEER_USER);

        /* Check the other direction if disconnecting bidirectional CIS */
        if (source_stream_locations.empty()) {
          continue;
        }
        source_stream_locations.erase(
                std::remove_if(
                        source_stream_locations.begin(), source_stream_locations.end(),
                        [&cis_handle](auto& inf) { return inf.stream_handle == cis_handle; }),
                source_stream_locations.end());
      }
    }

    /* Take care of the non-bidirectional CISes */
    if (!source_stream_locations.empty()) {
      for (auto info : source_stream_locations) {
        bluetooth::hci::IsoManager::GetInstance()->DisconnectCis(info.stream_handle,
                                                                 HCI_ERR_PEER_USER);
      }
    }
  }

  /* Note: CIG will stay in the controller. We cannot remove it here, because
   * Cises are not yet disconnected.
   * When user start Bluetooth, HCI Reset should remove it
   */

  leAudioDevices_.clear();
  ClearAllCises();
}

void LeAudioDeviceGroup::Deactivate(void) {
  for (auto* leAudioDevice = GetFirstActiveDevice(); leAudioDevice;
       leAudioDevice = GetNextActiveDevice(leAudioDevice)) {
    for (auto* ase = leAudioDevice->GetFirstActiveAse(); ase;
         ase = leAudioDevice->GetNextActiveAse(ase)) {
      ase->active = false;
      ase->reconfigure = 0;
    }
  }
}

bool LeAudioDeviceGroup::Activate(LeAudioContextType context_type,
                                  const BidirectionalPair<AudioContexts>& metadata_context_types,
                                  BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
  bool is_activate = false;
  for (auto leAudioDevice : leAudioDevices_) {
    if (leAudioDevice.expired()) {
      continue;
    }

    bool activated = leAudioDevice.lock()->ActivateConfiguredAses(
            context_type, metadata_context_types, ccid_lists);
    log::info("Device {} is {}", leAudioDevice.lock().get()->address_,
              activated ? "activated" : " not activated");
    if (activated) {
      if (!cig.AssignCisIds(leAudioDevice.lock().get())) {
        return false;
      }
      is_activate = true;
      SetMetadataContexts(metadata_context_types);
    }
  }
  return is_activate;
}

AudioContexts LeAudioDeviceGroup::GetSupportedContexts(int direction) const {
  AudioContexts context;
  for (auto& device : leAudioDevices_) {
    auto shared_dev = device.lock();
    if (shared_dev) {
      context |= shared_dev->GetSupportedContexts(direction);
    }
  }
  return context;
}

LeAudioDevice* LeAudioDeviceGroup::GetFirstDevice(void) const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(),
                           [](auto& iter) { return !iter.expired(); });

  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  return (iter->lock()).get();
}

LeAudioDevice* LeAudioDeviceGroup::GetFirstDeviceWithAvailableContext(
        LeAudioContextType context_type) const {
  auto iter =
          std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&context_type](auto& iter) {
            if (iter.expired()) {
              return false;
            }
            return iter.lock()->GetAvailableContexts().test(context_type);
          });

  if ((iter == leAudioDevices_.end()) || (iter->expired())) {
    return nullptr;
  }

  return (iter->lock()).get();
}

LeAudioDevice* LeAudioDeviceGroup::GetNextDevice(LeAudioDevice* leAudioDevice) const {
  auto iter =
          std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&leAudioDevice](auto& d) {
            if (d.expired()) {
              return false;
            } else {
              return (d.lock()).get() == leAudioDevice;
            }
          });

  /* If reference device not found */
  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  std::advance(iter, 1);
  /* If reference device is last in group */
  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  if (iter->expired()) {
    return nullptr;
  }

  return (iter->lock()).get();
}

LeAudioDevice* LeAudioDeviceGroup::GetNextDeviceWithAvailableContext(
        LeAudioDevice* leAudioDevice, LeAudioContextType context_type) const {
  auto iter =
          std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&leAudioDevice](auto& d) {
            if (d.expired()) {
              return false;
            } else {
              return (d.lock()).get() == leAudioDevice;
            }
          });

  /* If reference device not found */
  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  std::advance(iter, 1);
  /* If reference device is last in group */
  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  iter = std::find_if(iter, leAudioDevices_.end(), [&context_type](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return d.lock()->GetAvailableContexts().test(context_type);
    };
  });

  return (iter == leAudioDevices_.end()) ? nullptr : (iter->lock()).get();
}

bool LeAudioDeviceGroup::IsDeviceInTheGroup(LeAudioDevice* leAudioDevice) const {
  auto iter =
          std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&leAudioDevice](auto& d) {
            if (d.expired()) {
              return false;
            } else {
              return (d.lock()).get() == leAudioDevice;
            }
          });

  if ((iter == leAudioDevices_.end()) || (iter->expired())) {
    return false;
  }

  return true;
}

bool LeAudioDeviceGroup::IsGroupReadyToCreateStream(void) const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return !(((d.lock()).get())->IsReadyToCreateStream());
    }
  });

  return iter == leAudioDevices_.end();
}

bool LeAudioDeviceGroup::IsGroupReadyToSuspendStream(void) const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return !(((d.lock()).get())->IsReadyToSuspendStream());
    }
  });

  return iter == leAudioDevices_.end();
}

bool LeAudioDeviceGroup::HaveAnyActiveDeviceInStreamingState() const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return ((d.lock()).get())->HaveAnyStreamingAses();
    }
  });

  return iter != leAudioDevices_.end();
}

bool LeAudioDeviceGroup::HaveAnyActiveDeviceInUnconfiguredState() const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return ((d.lock()).get())->HaveAnyUnconfiguredAses();
    }
  });

  return iter != leAudioDevices_.end();
}

bool LeAudioDeviceGroup::HaveAllActiveDevicesAsesTheSameState(AseState state) const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&state](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return !(((d.lock()).get())->HaveAllActiveAsesSameState(state));
    }
  });

  return iter == leAudioDevices_.end();
}

LeAudioDevice* LeAudioDeviceGroup::GetFirstActiveDevice(void) const {
  auto iter = std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return ((d.lock()).get())->HaveActiveAse();
    }
  });

  if (iter == leAudioDevices_.end() || iter->expired()) {
    return nullptr;
  }

  return (iter->lock()).get();
}

LeAudioDevice* LeAudioDeviceGroup::GetNextActiveDevice(LeAudioDevice* leAudioDevice) const {
  auto iter =
          std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&leAudioDevice](auto& d) {
            if (d.expired()) {
              return false;
            } else {
              return (d.lock()).get() == leAudioDevice;
            }
          });

  if (iter == leAudioDevices_.end() || std::distance(iter, leAudioDevices_.end()) < 1) {
    return nullptr;
  }

  iter = std::find_if(std::next(iter, 1), leAudioDevices_.end(), [](auto& d) {
    if (d.expired()) {
      return false;
    } else {
      return ((d.lock()).get())->HaveActiveAse();
    }
  });

  return (iter == leAudioDevices_.end()) ? nullptr : (iter->lock()).get();
}

int LeAudioDeviceGroup::GetNumOfActiveDevices(void) const {
  int result = 0;
  for (auto dev = GetFirstActiveDevice(); dev; dev = GetNextActiveDevice(dev)) {
    result++;
  }
  return result;
}

LeAudioDevice* LeAudioDeviceGroup::GetFirstActiveDeviceByCisAndDataPathState(
        CisState cis_state, DataPathState data_path_state) const {
  auto iter = std::find_if(
          leAudioDevices_.begin(), leAudioDevices_.end(), [&data_path_state, &cis_state](auto& d) {
            if (d.expired()) {
              return false;
            }

            return ((d.lock()).get())
                           ->GetFirstActiveAseByCisAndDataPathState(cis_state, data_path_state) !=
                   nullptr;
          });

  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  return iter->lock().get();
}

LeAudioDevice* LeAudioDeviceGroup::GetNextActiveDeviceByCisAndDataPathState(
        LeAudioDevice* leAudioDevice, CisState cis_state, DataPathState data_path_state) const {
  auto iter =
          std::find_if(leAudioDevices_.begin(), leAudioDevices_.end(), [&leAudioDevice](auto& d) {
            if (d.expired()) {
              return false;
            }

            return d.lock().get() == leAudioDevice;
          });

  if (std::distance(iter, leAudioDevices_.end()) < 1) {
    return nullptr;
  }

  iter = std::find_if(
          std::next(iter, 1), leAudioDevices_.end(), [&cis_state, &data_path_state](auto& d) {
            if (d.expired()) {
              return false;
            }

            return ((d.lock()).get())
                           ->GetFirstActiveAseByCisAndDataPathState(cis_state, data_path_state) !=
                   nullptr;
          });

  if (iter == leAudioDevices_.end()) {
    return nullptr;
  }

  return iter->lock().get();
}

uint32_t LeAudioDeviceGroup::GetSduInterval(uint8_t direction) const {
  for (LeAudioDevice* leAudioDevice = GetFirstActiveDevice(); leAudioDevice != nullptr;
       leAudioDevice = GetNextActiveDevice(leAudioDevice)) {
    struct ase* ase = leAudioDevice->GetFirstActiveAseByDirection(direction);
    if (!ase) {
      continue;
    }
    return ase->qos_config.sdu_interval;
  }

  return 0;
}

uint8_t LeAudioDeviceGroup::GetSCA(void) const {
  uint8_t sca = bluetooth::hci::iso_manager::kIsoSca0To20Ppm;

  for (const auto& leAudioDevice : leAudioDevices_) {
    uint8_t dev_sca = get_btm_client_interface().peer.BTM_GetPeerSCA(leAudioDevice.lock()->address_,
                                                                     BT_TRANSPORT_LE);

    /* If we could not read SCA from the peer device or sca is 0,
     * then there is no reason to continue.
     */
    if ((dev_sca == 0xFF) || (dev_sca == 0)) {
      return 0;
    }

    /* The Slaves_Clock_Accuracy parameter shall be the worst-case sleep clock
     *accuracy of all the slaves that will participate in the CIG.
     */
    if (dev_sca < sca) {
      sca = dev_sca;
    }
  }

  return sca;
}

uint8_t LeAudioDeviceGroup::GetPacking(void) const {
  if (!stream_conf.conf) {
    log::error("No stream configuration has been set.");
    return bluetooth::hci::kIsoCigPackingSequential;
  }
  return stream_conf.conf->packing;
}

uint8_t LeAudioDeviceGroup::GetFraming(void) const {
  LeAudioDevice* leAudioDevice = GetFirstActiveDevice();
  log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

  do {
    struct ase* ase = leAudioDevice->GetFirstActiveAse();
    if (!ase) {
      continue;
    }

    do {
      if (ase->qos_preferences.supported_framing == types::kFramingUnframedPduUnsupported) {
        return bluetooth::hci::kIsoCigFramingFramed;
      }
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));
  } while ((leAudioDevice = GetNextActiveDevice(leAudioDevice)));

  return bluetooth::hci::kIsoCigFramingUnframed;
}

/* TODO: Preferred parameter may be other than minimum */
static uint16_t find_max_transport_latency(const LeAudioDeviceGroup* group, uint8_t direction) {
  uint16_t max_transport_latency = 0;

  for (LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice(); leAudioDevice != nullptr;
       leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
    for (ase* ase = leAudioDevice->GetFirstActiveAseByDirection(direction); ase != nullptr;
         ase = leAudioDevice->GetNextActiveAseWithSameDirection(ase)) {
      if (!ase) {
        break;
      }

      if (max_transport_latency == 0) {
        // first assignment
        max_transport_latency = ase->qos_config.max_transport_latency;
      } else if (ase->qos_config.max_transport_latency < max_transport_latency) {
        if (ase->qos_config.max_transport_latency != 0) {
          max_transport_latency = ase->qos_config.max_transport_latency;
        } else {
          log::warn("Trying to set latency back to 0, ASE ID {}", ase->id);
        }
      }
    }
  }

  if (max_transport_latency < types::kMaxTransportLatencyMin) {
    max_transport_latency = types::kMaxTransportLatencyMin;
  } else if (max_transport_latency > types::kMaxTransportLatencyMax) {
    max_transport_latency = types::kMaxTransportLatencyMax;
  }

  return max_transport_latency;
}

uint16_t LeAudioDeviceGroup::GetMaxTransportLatencyStom(void) const {
  return find_max_transport_latency(this, types::kLeAudioDirectionSource);
}

uint16_t LeAudioDeviceGroup::GetMaxTransportLatencyMtos(void) const {
  return find_max_transport_latency(this, types::kLeAudioDirectionSink);
}

uint32_t LeAudioDeviceGroup::GetTransportLatencyUs(uint8_t direction) const {
  if (direction == types::kLeAudioDirectionSink) {
    return transport_latency_mtos_us_;
  } else if (direction == types::kLeAudioDirectionSource) {
    return transport_latency_stom_us_;
  } else {
    log::error("invalid direction");
    return 0;
  }
}

void LeAudioDeviceGroup::SetTransportLatency(uint8_t direction, uint32_t new_transport_latency_us) {
  uint32_t* transport_latency_us;

  if (direction == types::kLeAudioDirectionSink) {
    transport_latency_us = &transport_latency_mtos_us_;
  } else if (direction == types::kLeAudioDirectionSource) {
    transport_latency_us = &transport_latency_stom_us_;
  } else {
    log::error("invalid direction");
    return;
  }

  if (*transport_latency_us == new_transport_latency_us) {
    return;
  }

  if ((*transport_latency_us != 0) && (*transport_latency_us != new_transport_latency_us)) {
    log::warn("Different transport latency for group:  old: {} [us], new: {} [us]",
              static_cast<int>(*transport_latency_us), static_cast<int>(new_transport_latency_us));
    return;
  }

  log::info("updated group {} transport latency: {} [us]", static_cast<int>(group_id_),
            static_cast<int>(new_transport_latency_us));
  *transport_latency_us = new_transport_latency_us;
}

uint8_t LeAudioDeviceGroup::GetRtn(uint8_t direction, uint8_t cis_id) const {
  LeAudioDevice* leAudioDevice = GetFirstActiveDevice();
  log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

  do {
    auto ases_pair = leAudioDevice->GetAsesByCisId(cis_id);

    if (ases_pair.sink && direction == types::kLeAudioDirectionSink) {
      return ases_pair.sink->qos_config.retrans_nb;
    } else if (ases_pair.source && direction == types::kLeAudioDirectionSource) {
      return ases_pair.source->qos_config.retrans_nb;
    }
  } while ((leAudioDevice = GetNextActiveDevice(leAudioDevice)));

  return 0;
}

uint16_t LeAudioDeviceGroup::GetMaxSduSize(uint8_t direction, uint8_t cis_id) const {
  LeAudioDevice* leAudioDevice = GetFirstActiveDevice();
  log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

  do {
    auto ases_pair = leAudioDevice->GetAsesByCisId(cis_id);

    if (ases_pair.sink && direction == types::kLeAudioDirectionSink) {
      return ases_pair.sink->qos_config.max_sdu_size;
    } else if (ases_pair.source && direction == types::kLeAudioDirectionSource) {
      return ases_pair.source->qos_config.max_sdu_size;
    }
  } while ((leAudioDevice = GetNextActiveDevice(leAudioDevice)));

  return 0;
}

uint8_t LeAudioDeviceGroup::GetPhyBitmask(uint8_t direction) const {
  LeAudioDevice* leAudioDevice = GetFirstActiveDevice();
  log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

  // local supported PHY's
  uint8_t phy_bitfield = bluetooth::hci::kIsoCigPhy1M;
  auto controller = bluetooth::shim::GetController();
  if (controller && controller->SupportsBle2mPhy()) {
    phy_bitfield |= bluetooth::hci::kIsoCigPhy2M;
  }

  if (!leAudioDevice) {
    log::error("No active leaudio device for direction?: {}", direction);
    return phy_bitfield;
  }

  do {
    struct ase* ase = leAudioDevice->GetFirstActiveAseByDirection(direction);
    if (!ase) {
      return phy_bitfield;
    }

    do {
      if (direction == ase->direction) {
        phy_bitfield &= leAudioDevice->GetPhyBitmask();

        // A value of 0x00 denotes no preference
        if (ase->qos_preferences.preferred_phy &&
            (phy_bitfield & ase->qos_preferences.preferred_phy)) {
          phy_bitfield &= ase->qos_preferences.preferred_phy;
          log::debug("Using ASE preferred phy 0x{:02x}", static_cast<int>(phy_bitfield));
        } else {
          log::warn(
                  "ASE preferred 0x{:02x} has nothing common with phy_bitfield "
                  "0x{:02x}",
                  static_cast<int>(ase->qos_preferences.preferred_phy),
                  static_cast<int>(phy_bitfield));
        }
      }
    } while ((ase = leAudioDevice->GetNextActiveAseWithSameDirection(ase)));
  } while ((leAudioDevice = GetNextActiveDevice(leAudioDevice)));

  return phy_bitfield;
}

uint8_t LeAudioDeviceGroup::GetTargetPhy(uint8_t direction) const {
  uint8_t phy_bitfield = GetPhyBitmask(direction);

  // prefer to use 2M if supported
  if (phy_bitfield & bluetooth::hci::kIsoCigPhy2M) {
    return types::kTargetPhy2M;
  } else if (phy_bitfield & bluetooth::hci::kIsoCigPhy1M) {
    return types::kTargetPhy1M;
  } else {
    return 0;
  }
}

bool LeAudioDeviceGroup::GetPresentationDelay(uint32_t* delay, uint8_t direction) const {
  uint32_t delay_min = 0;
  uint32_t delay_max = UINT32_MAX;
  uint32_t preferred_delay_min = delay_min;
  uint32_t preferred_delay_max = delay_max;

  LeAudioDevice* leAudioDevice = GetFirstActiveDevice();
  log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

  do {
    struct ase* ase = leAudioDevice->GetFirstActiveAseByDirection(direction);
    if (!ase) {
      continue;  // device has no active ASEs in this direction
    }

    do {
      /* No common range check */
      if (ase->qos_preferences.pres_delay_min > delay_max ||
          ase->qos_preferences.pres_delay_max < delay_min) {
        return false;
      }

      if (ase->qos_preferences.pres_delay_min > delay_min) {
        delay_min = ase->qos_preferences.pres_delay_min;
      }
      if (ase->qos_preferences.pres_delay_max < delay_max) {
        delay_max = ase->qos_preferences.pres_delay_max;
      }
      if (ase->qos_preferences.preferred_pres_delay_min > preferred_delay_min) {
        preferred_delay_min = ase->qos_preferences.preferred_pres_delay_min;
      }
      if (ase->qos_preferences.preferred_pres_delay_max < preferred_delay_max &&
          ase->qos_preferences.preferred_pres_delay_max != types::kPresDelayNoPreference) {
        preferred_delay_max = ase->qos_preferences.preferred_pres_delay_max;
      }
    } while ((ase = leAudioDevice->GetNextActiveAseWithSameDirection(ase)));
  } while ((leAudioDevice = GetNextActiveDevice(leAudioDevice)));

  if (preferred_delay_min <= preferred_delay_max && preferred_delay_min >= delay_min &&
      preferred_delay_min <= delay_max) {
    *delay = preferred_delay_min;
  } else {
    *delay = delay_min;
  }

  return true;
}

uint16_t LeAudioDeviceGroup::GetRemoteDelay(uint8_t direction) const {
  uint16_t remote_delay_ms = 0;
  uint32_t presentation_delay;

  if (!GetFirstActiveDevice() || !GetPresentationDelay(&presentation_delay, direction)) {
    /* This should never happens at stream request time but to be safe return
     * some sample value to not break streaming
     */
    log::error("No active device available. Default value used.");
    return 100;
  }

  /* us to ms */
  remote_delay_ms = presentation_delay / 1000;
  remote_delay_ms += GetTransportLatencyUs(direction) / 1000;

  return remote_delay_ms;
}

CodecManager::UnicastConfigurationRequirements
LeAudioDeviceGroup::GetAudioSetConfigurationRequirements(types::LeAudioContextType ctx_type) const {
  auto new_req = CodecManager::UnicastConfigurationRequirements{
          .audio_context_type = ctx_type,
          .flags = CodecManager::Flags::NONE,
  };

  bool remote_has_gmap = false;

  // Define a requirement for each location. Knowing codec specific
  // capabilities (i.e. multiplexing capability) the config provider can
  // determine the number of ASEs to activate.
  for (auto const& weak_dev_ptr : leAudioDevices_) {
    auto device = weak_dev_ptr.lock();
    BidirectionalPair<bool> has_location = {false, false};

    for (auto direction : {types::kLeAudioDirectionSink, types::kLeAudioDirectionSource}) {
      if (!device->audio_locations_.get(direction)) {
        log::debug("Device {} has no audio allocation for direction: {}", device->address_,
                   (int)direction);
        continue;
      }

      // Do not put any requirements on the Source if Sink only scenario is used
      // Note: With the RINGTONE we should already prepare for a call.
      if ((direction == types::kLeAudioDirectionSource) &&
          ((types::kLeAudioContextAllRemoteSinkOnly.test(ctx_type) &&
            (ctx_type != types::LeAudioContextType::RINGTONE)) ||
           ctx_type == types::LeAudioContextType::UNSPECIFIED)) {
        log::debug("Skipping the remote source requirements.");
        continue;
      }

      if (device->GetAseCount(direction) == 0) {
        log::warn("Device {} has no ASEs for direction: {}", device->address_, (int)direction);
        continue;
      }

      if ((com::android::bluetooth::flags::le_audio_support_unidirectional_voice_assistant() &&
           ctx_type == types::LeAudioContextType::VOICEASSISTANTS) ||
          ctx_type == types::LeAudioContextType::GAME) {
        // For GAME and VOICE ASSISTANT, ignore direction if it is not supported only on a single
        // direction.
        auto group_contexts = GetSupportedContexts(types::kLeAudioDirectionBoth);
        if (group_contexts.test(ctx_type)) {
          auto direction_contexs = device->GetSupportedContexts(direction);
          if (!direction_contexs.test(ctx_type)) {
            log::warn("Device {} has no {} context support", device->address_,
                      common::ToString(ctx_type));
            continue;
          }
        }
      }

      auto const& dev_locations = device->audio_locations_.get(direction);
      if (dev_locations == std::nullopt) {
        log::warn("Device {} has no specified locations for direction: {}", device->address_,
                  (int)direction);
      }

      has_location.get(direction) = true;
      auto& direction_req = (direction == types::kLeAudioDirectionSink)
                                    ? new_req.sink_requirements
                                    : new_req.source_requirements;
      if (!direction_req) {
        direction_req = std::vector<
                CodecManager::UnicastConfigurationRequirements::DeviceDirectionRequirements>();
      }

      // Pass the audio channel allocation requirement according to TMAP
      auto locations =
              dev_locations->value.to_ulong() & (codec_spec_conf::kLeAudioLocationFrontLeft |
                                                 codec_spec_conf::kLeAudioLocationFrontRight);
      CodecManager::UnicastConfigurationRequirements::DeviceDirectionRequirements config_req;
      config_req.params.Add(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
                            (uint32_t)locations);
      if (preferred_config_.get(direction) &&
          preferred_config_.get(direction)->codec_priority != -1) {
        config_req.params.Add(
                codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                UINT8_TO_VEC_UINT8(codec_spec_conf::SingleSamplingFreqCapability2Config(
                        preferred_config_.get(direction)->sample_rate)));
        config_req.params.Add(
                codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                UINT8_TO_VEC_UINT8(codec_spec_conf::SingleFrameDurationCapability2Config(
                        preferred_config_.get(direction)->frame_duration)));
        config_req.params.Add(
                codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
                UINT16_TO_VEC_UINT8(preferred_config_.get(direction)->octets_per_frame));
      }
      config_req.target_latency = utils::GetTargetLatencyForAudioContext(ctx_type);
      log::warn("Device {} pushes requirement, location: {}, direction: {}", device->address_,
                (int)locations, (int)direction);
      direction_req->push_back(std::move(config_req));
    }

    // Push sink PACs if there are some sink requirements
    if (has_location.sink && !device->snk_pacs_.empty()) {
      if (!new_req.sink_pacs) {
        new_req.sink_pacs = std::vector<types::acs_ac_record>{};
      }
      for (auto const& [_, pac_char] : device->snk_pacs_) {
        for (auto const& pac_record : pac_char) {
          new_req.sink_pacs->push_back(pac_record);
        }
      }
    }

    // Push source PACs if there are some source requirements
    if (has_location.source && !device->src_pacs_.empty()) {
      if (!new_req.source_pacs) {
        new_req.source_pacs = std::vector<types::acs_ac_record>{};
      }
      for (auto& [_, pac_char] : device->src_pacs_) {
        for (auto const& pac_record : pac_char) {
          new_req.source_pacs->push_back(pac_record);
        }
      }
    }

    if (device->gmap_client_) {
      remote_has_gmap = true;
    }
  }

  if ((ctx_type == ::bluetooth::le_audio::types::LeAudioContextType::GAME) &&
      GmapClient::IsGmapClientEnabled() && GmapServer::IsGmapServerEnabled() && remote_has_gmap) {
    // Allow asymmetric configurations for the low latency GAME scenarios
    new_req.flags = CodecManager::Flags(CodecManager::Flags::ALLOW_ASYMMETRIC |
                                        CodecManager::Flags::LOW_LATENCY);
    log::debug(
            "GMAP is enabled. Set asymmetric flag for the GAME audio context configuration "
            "requests.");
  } else {
    log::debug(
            "GMAP is disabled, remote_has_gmap: {}, gmap_client_enabled: {}, gmap_server_enabled: "
            "{}",
            remote_has_gmap, GmapClient::IsGmapClientEnabled(), GmapServer::IsGmapServerEnabled());
  }

  return new_req;
}

bool LeAudioDeviceGroup::UpdateAudioSetConfigurationCache(LeAudioContextType ctx_type,
                                                          bool use_preference) const {
  auto requirements = GetAudioSetConfigurationRequirements(ctx_type);
  auto new_conf = CodecManager::GetInstance()->GetCodecConfig(
          requirements, std::bind(&LeAudioDeviceGroup::FindFirstSupportedConfiguration, this,
                                  std::placeholders::_1, std::placeholders::_2, use_preference));
  auto update_config = true;

  auto& cached_map = use_preference ? context_to_preferred_configuration_cache_map_
                                    : context_to_configuration_cache_map_;

  if (cached_map.count(ctx_type) != 0) {
    auto& [is_valid, existing_conf] = cached_map.at(ctx_type);
    update_config = (new_conf.get() != existing_conf.get());
    /* Just mark it as still valid */
    if (!update_config && !is_valid) {
      cached_map.at(ctx_type).first = true;
      return false;
    }
  }

  if (update_config) {
    log::info("config: {} -> {}, use_preference: {}", ToHexString(ctx_type),
              (new_conf ? new_conf->name.c_str() : "(none)"), use_preference);
    cached_map.erase(ctx_type);
    if (new_conf) {
      cached_map.insert(std::make_pair(ctx_type, std::make_pair(true, std::move(new_conf))));
    }
  }

  return update_config;
}

bool LeAudioDeviceGroup::SetPreferredAudioSetConfiguration(
        const bluetooth::le_audio::btle_audio_codec_config_t& input_codec_config,
        const bluetooth::le_audio::btle_audio_codec_config_t& output_codec_config) const {
  if (input_codec_config.codec_priority == -1 || output_codec_config.codec_priority == -1) {
    log::info("Clear codec config");
    ResetPreferredAudioSetConfiguration();
    return true;
  }

  preferred_config_.sink = std::make_unique<btle_audio_codec_config_t>(output_codec_config);
  preferred_config_.source = std::make_unique<btle_audio_codec_config_t>(input_codec_config);

  bool is_updated = false;

  for (LeAudioContextType ctx_type : types::kLeAudioContextAllTypesArray) {
    is_updated |= UpdateAudioSetConfigurationCache(ctx_type, true);
  }

  return is_updated;
}

bool LeAudioDeviceGroup::IsUsingPreferredAudioSetConfiguration(
        const LeAudioContextType& context_type) const {
  if (!preferred_config_.sink || !preferred_config_.source) {
    log::assert_that(!preferred_config_.sink && !preferred_config_.source,
                     "Preferred config should be null for both direction");
    return false;
  }

  if (preferred_config_.sink->codec_priority == -1 ||
      preferred_config_.source->codec_priority == -1) {
    return false;
  }

  return GetPreferredConfiguration(context_type).get();
}

void LeAudioDeviceGroup::ResetPreferredAudioSetConfiguration(void) const {
  log::info("Reset preferred configuration cached for all cotexts.");
  context_to_preferred_configuration_cache_map_.clear();
  preferred_config_.sink = nullptr;
  preferred_config_.source = nullptr;
}

void LeAudioDeviceGroup::InvalidateCachedConfigurations(void) {
  log::info("Group id: {}", group_id_);
  context_to_configuration_cache_map_.clear();
  ResetPreferredAudioSetConfiguration();
}

types::BidirectionalPair<AudioContexts> LeAudioDeviceGroup::GetLatestAvailableContexts() const {
  types::BidirectionalPair<AudioContexts> contexts;
  for (const auto& device : leAudioDevices_) {
    auto shared_ptr = device.lock();
    if (shared_ptr && shared_ptr->GetConnectionState() == DeviceConnectState::CONNECTED) {
      contexts.sink |= shared_ptr->GetAvailableContexts(types::kLeAudioDirectionSink);
      contexts.source |= shared_ptr->GetAvailableContexts(types::kLeAudioDirectionSource);
    }
  }
  return contexts;
}

bool LeAudioDeviceGroup::ReloadAudioLocations(void) {
  types::BidirectionalPair<std::optional<AudioLocations>> updated_audio_locations = {
          .sink = std::nullopt, .source = std::nullopt};

  for (const auto& device_locked : leAudioDevices_) {
    auto device = device_locked.lock();
    if (device && device->GetConnectionState() == DeviceConnectState::CONNECTED) {
      if (device->audio_locations_.sink) {
        updated_audio_locations.sink =
                updated_audio_locations.sink.value_or(0) | device->audio_locations_.sink->value;
      }
      if (device->audio_locations_.source) {
        updated_audio_locations.source =
                updated_audio_locations.source.value_or(0) | device->audio_locations_.source->value;
      }
    }
  }

  /* Nothing has changed */
  if (updated_audio_locations == audio_locations_) {
    return false;
  }

  audio_locations_ = updated_audio_locations;
  return true;
}

bool LeAudioDeviceGroup::ReloadAudioDirections(void) {
  uint8_t updated_audio_directions = 0x00;

  for (const auto& device : leAudioDevices_) {
    if (device.expired() ||
        (device.lock().get()->GetConnectionState() != DeviceConnectState::CONNECTED)) {
      continue;
    }
    updated_audio_directions |= device.lock().get()->audio_directions_;
  }

  /* Nothing has changed */
  if (updated_audio_directions == audio_directions_) {
    return false;
  }

  audio_directions_ = updated_audio_directions;

  return true;
}

AudioContexts LeAudioDeviceGroup::GetAllSupportedBidirectionalContextTypes(void) const {
  auto result = GetSupportedContexts(types::kLeAudioDirectionSink) &
                GetSupportedContexts(types::kLeAudioDirectionSource);

  result &= types::kLeAudioContextAllBidir;

  return result;
}

AudioContexts LeAudioDeviceGroup::GetAllSupportedSingleDirectionOnlyContextTypes(
        uint8_t remote_direction) const {
  AudioContexts result;

  /* Remote device present supported context types on the different directions.
   * It might happen that some "single directional" contexts are exposed on both
   * directions on the remote side.
   * Android takes the decision on the stream configuration based on the contexts therefore
   * there is defined list of host bidirectional and host single directional context
   * types. This function helps to filter out some missconfigurations on the remote side and return
   * single directional context types.
   * One of the use cases we want to handle here is is that usually VoiceAssistant and GAME are
   * bidirectional but some devices might remove it on purpose from one direction.
   */
  auto group_single_dir_only_contexts =
          GetSupportedContexts(remote_direction) & ~GetAllSupportedBidirectionalContextTypes();

  if (remote_direction == types::kLeAudioDirectionSink) {
    auto host_all_sink_contexts =
            types::kLeAudioContextAllRemoteSinkOnly | types::kLeAudioContextAllBidir;
    result = host_all_sink_contexts & group_single_dir_only_contexts;

  } else {
    auto host_all_source_contexts =
            types::kLeAudioContextAllRemoteSource | types::kLeAudioContextAllBidir;
    result = host_all_source_contexts & group_single_dir_only_contexts;
  }

  return result;
}

bool LeAudioDeviceGroup::IsInTransition(void) const { return in_transition_; }

bool LeAudioDeviceGroup::IsStreaming(void) const {
  return current_state_ == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING;
}

bool LeAudioDeviceGroup::IsReleasingOrIdle(void) const {
  /* If target state is IDLE then for sure group is either releasing or idle.
   * Otherwise, we have "idle states" - Idle or Configured when caching is
   * supported on the remote side. In both cases to check it is to make sure
   * group is not in transition.
   */
  return target_state_ == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE ||
         ((current_state_ == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE ||
           current_state_ == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) &&
          !in_transition_);
}

bool LeAudioDeviceGroup::IsReleasing(void) const {
  return (target_state_ == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) && in_transition_;
}

bool LeAudioDeviceGroup::IsGroupStreamReady(void) const {
  bool is_device_ready = false;

  /* All connected devices must be ready */
  for (auto& weak : leAudioDevices_) {
    auto dev = weak.lock();
    if (!dev) {
      return false;
    }

    /* We are interested here in devices which are connected on profile level
     * and devices which are configured (meaning, have actived ASE(s))*/
    if (dev->GetConnectionState() == DeviceConnectState::CONNECTED && dev->HaveActiveAse()) {
      if (!dev->IsReadyToStream()) {
        return false;
      }
      is_device_ready = true;
    }
  }
  return is_device_ready;
}

bool LeAudioDeviceGroup::HaveAllCisesDisconnected(void) const {
  for (auto const dev : leAudioDevices_) {
    if (dev.expired()) {
      continue;
    }
    if (dev.lock().get()->HaveAnyCisConnected()) {
      return false;
    }
  }
  return true;
}

uint8_t LeAudioDeviceGroup::CigConfiguration::GetFirstFreeCisId(CisType cis_type) const {
  log::info("Group: {}, group_id: {} cis_type: {}", std::format_ptr(group_), group_->group_id_,
            static_cast<int>(cis_type));
  for (size_t id = 0; id < cises.size(); id++) {
    if (cises[id].addr.IsEmpty() && cises[id].type == cis_type) {
      return id;
    }
  }
  return kInvalidCisId;
}

types::LeAudioConfigurationStrategy LeAudioDeviceGroup::GetGroupSinkStrategy() const {
  /* Update the strategy if not set yet or was invalidated */
  if (!strategy_) {
    /* Choose the group configuration strategy based on PAC records */
    auto strategy_selector = [&, this](uint8_t direction) {
      int expected_group_size = Size();

      if (!audio_locations_.get(direction)) {
        log::error("No audio locations for direction: {} available in the group", +direction);
        return types::LeAudioConfigurationStrategy::RFU;
      }

      /* Simple strategy picker */
      log::debug("Group {} size {}", group_id_, expected_group_size);
      if (expected_group_size > 1) {
        return types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE;
      }

      /* Check supported audio locations */
      auto const& locations = audio_locations_.get(direction).value();

      log::verbose("audio location 0x{:04x}", locations.to_ulong());
      if (!(locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyLeft) ||
          !(locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyRight) || locations.none()) {
        return types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE;
      }

      auto device = GetFirstDevice();
      /* Note: Currently, the audio channel counts LTV is only mandatory for
       * LC3. */
      auto channel_count_bitmap = device->GetSupportedAudioChannelCounts(direction);
      log::debug("Supported channel counts for group {} (device {}) is {}", group_id_,
                 device->address_, channel_count_bitmap);
      if (channel_count_bitmap == 1) {
        return types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE;
      }

      return types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE;
    };
    strategy_ = strategy_selector(types::kLeAudioDirectionSink);
    if (strategy_ == types::LeAudioConfigurationStrategy::RFU) {
      log::warn("Unable to find the proper remote sink strategy. Trying source direction instead");
      strategy_ = strategy_selector(types::kLeAudioDirectionSource);
    }

    log::info("Group strategy set to: {}", [](types::LeAudioConfigurationStrategy strategy) {
      switch (strategy) {
        case types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE:
          return "MONO_ONE_CIS_PER_DEVICE";
        case types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE:
          return "STEREO_TWO_CISES_PER_DEVICE";
        case types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE:
          return "STEREO_ONE_CIS_PER_DEVICE";
        default:
          return "RFU";
      }
    }(*strategy_));
  }
  return *strategy_;
}

int LeAudioDeviceGroup::GetAseCount(uint8_t direction) const {
  int result = 0;
  for (const auto& device_iter : leAudioDevices_) {
    result += device_iter.lock()->GetAseCount(direction);
  }

  return result;
}

/* Calculate the total number of sink, source and bidirectional CISes required by the CIG,
 * for the given configuration audio context.
 */
void LeAudioDeviceGroup::CigConfiguration::GetCisCount(LeAudioContextType context_type,
                                                       uint8_t& out_cis_count_bidir,
                                                       uint8_t& out_cis_count_unidir_sink,
                                                       uint8_t& out_cis_count_unidir_source) const {
  auto expected_device_cnt = group_->DesiredSize();
  auto avail_group_ase_snk_cnt = group_->GetAseCount(types::kLeAudioDirectionSink);
  auto avail_group_ase_src_count = group_->GetAseCount(types::kLeAudioDirectionSource);
  auto strategy = group_->GetGroupSinkStrategy();

  bool is_bidirectional = group_->GetAllSupportedBidirectionalContextTypes().test(context_type);
  bool is_source_only = !is_bidirectional && group_->GetAllSupportedSingleDirectionOnlyContextTypes(
                                                           types::kLeAudioDirectionSource)
                                                     .test(context_type);
  log::debug(
          "{} {}, strategy {}, group avail sink ases: {}, "
          "group avail source ases {} "
          "expected_device_count {}",
          bluetooth::common::ToString(context_type),
          is_bidirectional ? "is bidirectional"
                           : (is_source_only ? "is source only" : "is sink only"),
          static_cast<int>(strategy), avail_group_ase_snk_cnt, avail_group_ase_src_count,
          expected_device_cnt);

  switch (strategy) {
    case types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE:
    /* This strategy is for the CSIS topology, e.g. two earbuds which are both
     * connected with a Phone
     */
    case types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE:
      /* This strategy is for e.g. the banded headphones */
      if (is_bidirectional) {
        if ((avail_group_ase_snk_cnt > 0) && (avail_group_ase_src_count) > 0) {
          /* Prepare CIG to enable all microphones */
          out_cis_count_bidir = expected_device_cnt;
        } else {
          if (avail_group_ase_snk_cnt > 0) {
            out_cis_count_unidir_sink = expected_device_cnt;
          } else if (avail_group_ase_src_count > 0) {
            out_cis_count_unidir_source = expected_device_cnt;
          }
        }
      } else if (is_source_only) {
        out_cis_count_unidir_source = expected_device_cnt;
      } else {
        out_cis_count_unidir_sink = expected_device_cnt;
      }

      break;
    case types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE:
      /* This strategy is for the old TWS topology. e.g. one earbud connected to
       * the Phone but each channel is carried in separate CIS
       */
      if (is_bidirectional) {
        if ((avail_group_ase_snk_cnt > 0) && (avail_group_ase_src_count) > 0) {
          /* Prepare CIG to enable all microphones per device */
          out_cis_count_bidir = expected_device_cnt;
          if (avail_group_ase_src_count > 1) {
            out_cis_count_bidir++;
          } else {
            out_cis_count_unidir_sink = expected_device_cnt;
          }
        } else {
          if (avail_group_ase_snk_cnt > 0) {
            out_cis_count_unidir_sink = 2 * expected_device_cnt;
          } else if (avail_group_ase_src_count > 0) {
            out_cis_count_unidir_source = 2 * expected_device_cnt;
          }
        }
      } else if (is_source_only) {
        out_cis_count_unidir_source = 2 * expected_device_cnt;
      } else {
        out_cis_count_unidir_sink = 2 * expected_device_cnt;
      }
      break;
    case types::LeAudioConfigurationStrategy::RFU:
      log::error("Should not happen;");
      break;
  }

  log::info(
          "Required cis count: Bi-Directional: {}, Uni-Directional Sink: {}, "
          "Uni-Directional Source: {}",
          out_cis_count_bidir, out_cis_count_unidir_sink, out_cis_count_unidir_source);
}

void LeAudioDeviceGroup::CigConfiguration::GenerateCisIds(LeAudioContextType context_type) {
  log::info("Group {}, group_id: {}, context_type: {}", std::format_ptr(group_), group_->group_id_,
            bluetooth::common::ToString(context_type));

  if (cises.size() > 0) {
    log::info("CIS IDs already generated");
    return;
  }

  uint8_t cis_count_bidir = 0;
  uint8_t cis_count_unidir_sink = 0;
  uint8_t cis_count_unidir_source = 0;
  GetCisCount(context_type, cis_count_bidir, cis_count_unidir_sink, cis_count_unidir_source);

  uint8_t idx = 0;
  while (cis_count_bidir > 0) {
    struct bluetooth::le_audio::types::cis cis_entry = {
            .id = idx,
            .type = CisType::CIS_TYPE_BIDIRECTIONAL,
            .conn_handle = 0,
            .addr = RawAddress::kEmpty,
    };
    cises.push_back(cis_entry);
    cis_count_bidir--;
    idx++;
  }

  while (cis_count_unidir_sink > 0) {
    struct bluetooth::le_audio::types::cis cis_entry = {
            .id = idx,
            .type = CisType::CIS_TYPE_UNIDIRECTIONAL_SINK,
            .conn_handle = 0,
            .addr = RawAddress::kEmpty,
    };
    cises.push_back(cis_entry);
    cis_count_unidir_sink--;
    idx++;
  }

  while (cis_count_unidir_source > 0) {
    struct bluetooth::le_audio::types::cis cis_entry = {
            .id = idx,
            .type = CisType::CIS_TYPE_UNIDIRECTIONAL_SOURCE,
            .conn_handle = 0,
            .addr = RawAddress::kEmpty,
    };
    cises.push_back(cis_entry);
    cis_count_unidir_source--;
    idx++;
  }
}

bool LeAudioDeviceGroup::CigConfiguration::AssignCisIds(LeAudioDevice* leAudioDevice) {
  log::assert_that(leAudioDevice, "invalid device");
  log::info("device: {}", leAudioDevice->address_);

  struct ase* ase = leAudioDevice->GetFirstActiveAse();
  if (!ase) {
    log::error("Device {} shouldn't be called without an active ASE", leAudioDevice->address_);
    return false;
  }

  for (; ase != nullptr; ase = leAudioDevice->GetNextActiveAse(ase)) {
    uint8_t cis_id = kInvalidCisId;
    /* CIS ID already set */
    if (ase->cis_id != kInvalidCisId) {
      log::info("ASE ID: {}, is already assigned CIS ID: {}, type {}", ase->id, ase->cis_id,
                cises[ase->cis_id].type);
      if (!cises[ase->cis_id].addr.IsEmpty()) {
        log::info("Bi-Directional CIS already assigned");
        continue;
      }
      /* Reuse existing CIS ID if available*/
      cis_id = ase->cis_id;
    }

    /* First check if we have bidirectional ASEs. If so, assign same CIS ID.*/
    struct ase* matching_bidir_ase = leAudioDevice->GetNextActiveAseWithDifferentDirection(ase);

    for (; matching_bidir_ase != nullptr;
         matching_bidir_ase =
                 leAudioDevice->GetNextActiveAseWithSameDirection(matching_bidir_ase)) {
      if ((matching_bidir_ase->cis_id != kInvalidCisId) && (matching_bidir_ase->cis_id != cis_id)) {
        log::info("Bi-Directional CIS is already used. ASE Id: {} cis_id={}",
                  matching_bidir_ase->id, matching_bidir_ase->cis_id);
        continue;
      }
      break;
    }

    if (matching_bidir_ase) {
      if (cis_id == kInvalidCisId) {
        cis_id = GetFirstFreeCisId(CisType::CIS_TYPE_BIDIRECTIONAL);
      }

      if (cis_id != kInvalidCisId) {
        ase->cis_id = cis_id;
        matching_bidir_ase->cis_id = cis_id;
        cises[cis_id].addr = leAudioDevice->address_;

        log::info("ASE ID: {} and ASE ID: {}, assigned Bi-Directional CIS ID: {}", ase->id,
                  matching_bidir_ase->id, ase->cis_id);
        continue;
      }

      log::warn(
              "ASE ID: {}, unable to get free Bi-Directional CIS ID but maybe "
              "thats fine. Try using unidirectional.",
              ase->id);
    }

    if (ase->direction == types::kLeAudioDirectionSink) {
      if (cis_id == kInvalidCisId) {
        cis_id = GetFirstFreeCisId(CisType::CIS_TYPE_UNIDIRECTIONAL_SINK);
      }

      if (cis_id == kInvalidCisId) {
        log::warn(
                "Unable to get free Uni-Directional Sink CIS ID - maybe there is "
                "bi-directional available");
        /* This could happen when scenarios for given context type allows for
         * Sink and Source configuration but also only Sink configuration.
         */
        cis_id = GetFirstFreeCisId(CisType::CIS_TYPE_BIDIRECTIONAL);
        if (cis_id == kInvalidCisId) {
          log::error("Unable to get free Bi-Directional CIS ID for Sink ASE");
          return false;
        }
        log::info("ASE ID: {}, assigned Bi-Directional CIS ID: {} for Sink ASE", ase->id, cis_id);
      } else {
        log::info("ASE ID: {}, assigned Uni-Directional CIS ID: {} for Sink ASE", ase->id, cis_id);
      }

      ase->cis_id = cis_id;
      cises[cis_id].addr = leAudioDevice->address_;
      continue;
    }

    /* Source direction */
    log::assert_that(ase->direction == types::kLeAudioDirectionSource,
                     "Expected Source direction, actual={}", ase->direction);

    if (cis_id == kInvalidCisId) {
      cis_id = GetFirstFreeCisId(CisType::CIS_TYPE_UNIDIRECTIONAL_SOURCE);
    }

    if (cis_id == kInvalidCisId) {
      /* This could happen when scenarios for given context type allows for
       * Sink and Source configuration but also only Sink configuration.
       */
      log::warn(
              "Unable to get free Uni-Directional Source CIS ID - maybe there is "
              "bi-directional available");
      cis_id = GetFirstFreeCisId(CisType::CIS_TYPE_BIDIRECTIONAL);
      if (cis_id == kInvalidCisId) {
        log::error("Unable to get free Bi-Directional CIS ID for Source ASE");
        return false;
      }
      log::info("ASE ID: {}, assigned Bi-Directional CIS ID: {} for Source ASE", ase->id, cis_id);
    } else {
      log::info("ASE ID: {}, assigned Uni-Directional CIS ID: {} for Source ASE", ase->id, cis_id);
    }

    ase->cis_id = cis_id;
    cises[cis_id].addr = leAudioDevice->address_;
  }

  return true;
}

void LeAudioDeviceGroup::CigConfiguration::AssignCisConnHandles(
        const std::vector<uint16_t>& conn_handles) {
  log::info("num of cis handles {}", static_cast<int>(conn_handles.size()));
  for (size_t i = 0; i < cises.size(); i++) {
    cises[i].conn_handle = conn_handles[i];
    log::info("assigning cis[{}] conn_handle: {}", cises[i].id, cises[i].conn_handle);
  }
}

void LeAudioDeviceGroup::AssignCisConnHandlesToAses(LeAudioDevice* leAudioDevice) {
  log::assert_that(leAudioDevice, "Invalid device");
  log::info("group: {}, group_id: {}, device: {}", std::format_ptr(this), group_id_,
            leAudioDevice->address_);

  /* Assign all CIS connection handles to ases */
  struct bluetooth::le_audio::types::ase* ase =
          leAudioDevice->GetFirstActiveAseByCisAndDataPathState(CisState::IDLE,
                                                                DataPathState::IDLE);
  if (!ase) {
    log::warn("No active ASE with Cis and Data path state set to IDLE");
    return;
  }

  for (; ase != nullptr; ase = leAudioDevice->GetFirstActiveAseByCisAndDataPathState(
                                 CisState::IDLE, DataPathState::IDLE)) {
    auto ases_pair = leAudioDevice->GetAsesByCisId(ase->cis_id);

    if (ases_pair.sink && ases_pair.sink->active) {
      ases_pair.sink->cis_conn_hdl = cig.cises[ase->cis_id].conn_handle;
      ases_pair.sink->cis_state = CisState::ASSIGNED;
    }
    if (ases_pair.source && ases_pair.source->active) {
      ases_pair.source->cis_conn_hdl = cig.cises[ase->cis_id].conn_handle;
      ases_pair.source->cis_state = CisState::ASSIGNED;
    }
  }
}

void LeAudioDeviceGroup::AssignCisConnHandlesToAses(void) {
  LeAudioDevice* leAudioDevice = GetFirstActiveDevice();
  log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

  log::info("Group {}, group_id {}", std::format_ptr(this), group_id_);

  /* Assign all CIS connection handles to ases */
  for (; leAudioDevice != nullptr; leAudioDevice = GetNextActiveDevice(leAudioDevice)) {
    AssignCisConnHandlesToAses(leAudioDevice);
  }
}

void LeAudioDeviceGroup::CigConfiguration::UnassignCis(LeAudioDevice* leAudioDevice,
                                                       uint16_t conn_handle) {
  log::assert_that(leAudioDevice, "Invalid device");

  log::info("Group {}, group_id {}, device: {}, conn_handle: {:#x}", std::format_ptr(group_),
            group_->group_id_, leAudioDevice->address_, conn_handle);

  for (struct bluetooth::le_audio::types::cis& cis_entry : cises) {
    if (cis_entry.conn_handle == conn_handle && cis_entry.addr == leAudioDevice->address_) {
      cis_entry.addr = RawAddress::kEmpty;
    }
  }
}

static bool CheckIfStrategySupported(types::LeAudioConfigurationStrategy strategy,
                                     const types::AseConfiguration& conf, uint8_t direction,
                                     const LeAudioDevice& device) {
  if (strategy == types::LeAudioConfigurationStrategy::RFU) {
    log::error("Device {}: No valid strategy for direction: {}", device.address_, +direction);
    return false;
  }

  if (!device.audio_locations_.get(direction)) {
    log::error("Device {}: No valid audio locations for direction: {}", device.address_,
               +direction);
    return false;
  }

  /* Check direction and if audio location allows to create more cises to a
   * single device.
   */
  auto const& audio_locations = device.audio_locations_.get(direction)->value;
  log::debug("Device {}: strategy: {}, locations: {}", device.address_, (int)strategy,
             audio_locations.to_ulong());

  switch (strategy) {
    case types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE:
      return true;
    case types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE:
      if ((audio_locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyLeft) &&
          (audio_locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyRight)) {
        return true;
      } else {
        return false;
      }
    case types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE: {
      if (!(audio_locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyLeft) ||
          !(audio_locations.to_ulong() & codec_spec_conf::kLeAudioLocationAnyRight)) {
        return false;
      }

      auto channel_count_mask = device.GetSupportedAudioChannelCounts(direction);
      auto requested_channel_count = conf.codec.GetChannelCountPerIsoStream();
      log::debug("Requested channel count: {}, supp. channel counts: 0x{:x}",
                 requested_channel_count, channel_count_mask);

      /* Return true if requested channel count is set in the supported channel
       * counts. In the channel_count_mask, bit 0 is set when 1 channel is
       * supported.
       */
      return (1 << (requested_channel_count - 1)) & channel_count_mask;
    }
    default:
      return false;
  }

  return false;
}

/* This method check if group support given audio configuration
 * requirement for connected devices in the group and available ASEs
 * (no matter on the ASE state) and for given context type
 */
bool LeAudioDeviceGroup::IsAudioSetConfigurationSupported(
        const CodecManager::UnicastConfigurationRequirements& requirements,
        const types::AudioSetConfiguration* audio_set_conf, bool use_preference) const {
  /* TODO For now: set ase if matching with first pac.
   * 1) We assume as well that devices will match requirements in order
   *    e.g. 1 Device - 1 Requirement, 2 Device - 2 Requirement etc.
   * 2) ASEs should be active only if best (according to priority list) full
   *    scenarion will be covered.
   * 3) ASEs should be filled according to performance profile.
   */
  auto required_snk_strategy = GetGroupSinkStrategy();
  bool status = false;
  for (auto direction : {types::kLeAudioDirectionSink, types::kLeAudioDirectionSource}) {
    log::debug("Looking for configuration: {} - {}", audio_set_conf->name,
               direction == types::kLeAudioDirectionSink ? "Sink" : "Source");
    auto const& ase_confs = audio_set_conf->confs.get(direction);
    if (ase_confs.empty()) {
      log::debug("No configurations for direction {}, skip it.", (int)direction);
      continue;
    }

    if (com::android::bluetooth::flags::le_audio_support_unidirectional_voice_assistant()) {
      // Verify the direction requirements.
      if (direction == types::kLeAudioDirectionSink &&
          requirements.sink_requirements->size() == 0) {
        log::debug("There is no requirement for Sink direction.");
        return false;
      }

      if (direction == types::kLeAudioDirectionSource &&
          requirements.source_requirements->size() == 0) {
        log::debug("There is no requirement for source direction.");
        return false;
      }
    }

    // Match with requirement first if we have
    if (use_preference) {
      auto& direction_req = (direction == types::kLeAudioDirectionSink)
                                    ? requirements.sink_requirements
                                    : requirements.source_requirements;
      if (!direction_req.has_value() || !preferred_config_.get(direction)) {
        return false;
      }
      if (!utils::IsAseConfigMatchedWithPreferredRequirements(
                  ase_confs, direction_req.value(),
                  codec_spec_conf::SingleChannelCountCapability2Config(
                          preferred_config_.get(direction)->channel_count))) {
        return false;
      }
    }

    // In some tests we expect the configuration to be there even when the
    // contexts are not supported. Then we might want to configure the device
    // but use UNSPECIFIED which is always supported (but can be unavailable)
    auto device_cnt = NumOfAvailableForDirection(direction);
    if (device_cnt == 0) {
      device_cnt = DesiredSize();
      if (device_cnt == 0) {
        log::error("Device count is 0");
        continue;
      }
    }

    auto const ase_cnt = ase_confs.size();
    if (ase_cnt == 0) {
      log::error("ASE count is 0");
      continue;
    }

    uint8_t const max_required_ase_per_dev = ase_cnt / device_cnt + (ase_cnt % device_cnt);

    // Use strategy for the whole group (not only the connected devices)
    auto const strategy = utils::GetStrategyForAseConfig(ase_confs, device_cnt);

    log::debug(
            "Number of devices: {}, number of ASEs: {},  Max ASE per device: {} "
            "config strategy: {}, group strategy: {}",
            device_cnt, ase_cnt, max_required_ase_per_dev, static_cast<int>(strategy),
            (int)required_snk_strategy);

    if (direction == types::kLeAudioDirectionSink && strategy != required_snk_strategy) {
      log::debug("Sink strategy mismatch group!=cfg.entry ({}!={})",
                 static_cast<int>(required_snk_strategy), static_cast<int>(strategy));
      return false;
    }

    uint8_t required_device_cnt = device_cnt;
    uint8_t active_ase_cnt = 0;
    for (auto* device = GetFirstDevice(); device != nullptr && required_device_cnt > 0;
         device = GetNextDevice(device)) {
      if (device->ases_.empty()) {
        log::error("Device has no ASEs.");
        continue;
      }

      int needed_ase_per_dev = std::min(static_cast<int>(max_required_ase_per_dev),
                                        static_cast<int>(ase_cnt - active_ase_cnt));

      for (auto const& ent : ase_confs) {
        // Verify PACS only if this is transparent LTV format
        auto const& pacs =
                (direction == types::kLeAudioDirectionSink) ? device->snk_pacs_ : device->src_pacs_;
        if (utils::IsCodecUsingLtvFormat(ent.codec.id) &&
            !utils::GetConfigurationSupportedPac(pacs, ent.codec)) {
          log::debug("Insufficient PAC for {}",
                     direction == types::kLeAudioDirectionSink ? "sink" : "source");
          continue;
        }

        if (!CheckIfStrategySupported(strategy, ent, direction, *device)) {
          log::debug("Strategy not supported");
          continue;
        }
        for (auto& ase : device->ases_) {
          if (ase.direction != direction) {
            continue;
          }

          active_ase_cnt++;
          needed_ase_per_dev--;

          if (needed_ase_per_dev == 0) {
            break;
          }
        }
      }

      if (needed_ase_per_dev > 0) {
        log::debug("Not enough ASEs on the device (needs {} more).", needed_ase_per_dev);
        return false;
      }

      required_device_cnt--;
    }

    if (required_device_cnt > 0) {
      /* Don't left any active devices if requirements are not met */
      log::debug("Could not configure all the devices for direction: {}",
                 direction == types::kLeAudioDirectionSink ? "Sink" : "Source");
      return false;
    }

    // At least one direction can be configured
    status = true;
  }

  /* when disabling 32k dual mic, for later join case, we need to
   * make sure the device is always choosing the config that its
   * sampling rate matches with the sampling rate which is used
   * when all devices in the group are connected.
   */
  bool dual_bidirection_swb_supported_ = CodecManager::GetInstance()->IsDualBiDirSwbSupported();
  if (DesiredSize() > 1 &&
      CodecManager::GetInstance()->CheckCodecConfigIsBiDirSwb(*audio_set_conf)) {
    if (!dual_bidirection_swb_supported_) {
      return false;
    }
  }

  if (status) {
    log::debug("Chosen ASE Configuration for group: {}, configuration: {}", group_id_,
               audio_set_conf->name);
  } else {
    log::error("Could not configure either direction for group {}", group_id_);
  }
  return status;
}

/* This method should choose aproperiate ASEs to be active and set a cached
 * configuration for codec and qos.
 */
bool LeAudioDeviceGroup::ConfigureAses(
        const types::AudioSetConfiguration* audio_set_conf, LeAudioContextType context_type,
        const types::BidirectionalPair<AudioContexts>& metadata_context_types,
        const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists) {
  bool reuse_cis_id = GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;

  /* TODO For now: set ase if matching with first pac.
   * 1) We assume as well that devices will match requirements in order
   *    e.g. 1 Device - 1 Requirement, 2 Device - 2 Requirement etc.
   * 2) ASEs should be active only if best (according to priority list) full
   *    scenarion will be covered.
   * 3) ASEs should be filled according to performance profile.
   */

  // WARNING: This may look like the results stored here are unused, but it
  //          actually shares the intermediate values between the multiple
  //          configuration calls within the configuration loop.
  BidirectionalPair<types::AudioLocations> group_audio_locations_memo = {.sink = 0, .source = 0};

  for (auto direction : {types::kLeAudioDirectionSink, types::kLeAudioDirectionSource}) {
    auto direction_str = (direction == types::kLeAudioDirectionSink ? "Sink" : "Source");
    log::debug("{}: Looking for requirements: {}", direction_str, audio_set_conf->name);

    if (audio_set_conf->confs.get(direction).empty()) {
      log::warn("No {} configuration available.", direction_str);
      continue;
    }

    auto const max_required_device_cnt = NumOfAvailableForDirection(direction);
    auto required_device_cnt = max_required_device_cnt;
    log::debug("Maximum {} device(s) required for {}", max_required_device_cnt, direction_str);

    uint8_t active_ase_cnt = 0;

    log::debug("Required device count: {}", required_device_cnt);
    if (required_device_cnt == 0) {
      return false;
    }

    std::vector<LeAudioDevice*> configuredDevices;

    auto configuration_closure = [&](LeAudioDevice* dev, LeAudioContextType context_type) -> bool {
      /* For the moment, we configure only connected devices and when it is
       * ready to stream i.e. All ASEs are discovered and dev is reported as
       * connected
       */
      if (dev->GetConnectionState() != DeviceConnectState::CONNECTED) {
        log::warn("Device {}, in the state {}", dev->address_,
                  bluetooth::common::ToString(dev->GetConnectionState()));
        return false;
      }

      if (!dev->GetAvailableContexts().test(context_type)) {
        log::debug("Device {} not available for context {}", dev->address_,
                   bluetooth::common::ToString(context_type));
        return false;
      }

      return dev->ConfigureAses(audio_set_conf, max_required_device_cnt, direction, context_type,
                                &active_ase_cnt, group_audio_locations_memo.get(direction),
                                metadata_context_types.get(direction), ccid_lists.get(direction),
                                reuse_cis_id);
    };

    auto group_configuration_closure = [&](LeAudioContextType context_type) -> void {
      for (const auto& dev_iter : leAudioDevices_) {
        auto dev = dev_iter.lock();
        if (dev == nullptr) {
          continue;
        }

        if (std::find(configuredDevices.begin(), configuredDevices.end(), dev.get()) !=
            configuredDevices.end()) {
          continue;
        }

        if (configuration_closure(dev.get(), context_type)) {
          configuredDevices.push_back(dev.get());
          required_device_cnt--;
        }

        if (required_device_cnt == 0) {
          break;
        }
      }
    };

    // First use the devices claiming proper support
    if (required_device_cnt > 0) {
      group_configuration_closure(context_type);
    }
    // In case some devices do not support this scenario - us them anyway if
    // they are required for the scenario - we will not put this context into
    // their metadata anyway
    if (required_device_cnt > 0) {
      group_configuration_closure(LeAudioContextType::UNSPECIFIED);
    }

    if (configuredDevices.empty()) {
      log::error("could not configure any device");
      Deactivate();
      return false;
    }

    log::info("Configured {}/{}", configuredDevices.size(), max_required_device_cnt);
  }

  log::info("Choosed ASE Configuration for group: {}, configuration: {}", group_id_,
            audio_set_conf->name);

  configuration_context_type_ = context_type;
  SetMetadataContexts(metadata_context_types);
  return true;
}

std::shared_ptr<const types::AudioSetConfiguration> LeAudioDeviceGroup::GetCachedConfiguration(
        LeAudioContextType context_type) const {
  if (context_to_configuration_cache_map_.count(context_type) != 0) {
    return context_to_configuration_cache_map_.at(context_type).second;
  }
  return nullptr;
}

std::shared_ptr<const types::AudioSetConfiguration>
LeAudioDeviceGroup::GetCachedPreferredConfiguration(LeAudioContextType context_type) const {
  if (context_to_preferred_configuration_cache_map_.count(context_type) != 0) {
    return context_to_preferred_configuration_cache_map_.at(context_type).second;
  }
  return nullptr;
}

std::shared_ptr<const types::AudioSetConfiguration> LeAudioDeviceGroup::GetActiveConfiguration(
        void) const {
  return IsUsingPreferredAudioSetConfiguration(configuration_context_type_)
                 ? GetCachedPreferredConfiguration(configuration_context_type_)
                 : GetCachedConfiguration(configuration_context_type_);
}

std::shared_ptr<const types::AudioSetConfiguration> LeAudioDeviceGroup::GetConfiguration(
        LeAudioContextType context_type) const {
  if (context_type == LeAudioContextType::UNINITIALIZED) {
    return nullptr;
  }

  if (IsUsingPreferredAudioSetConfiguration(context_type)) {
    log::debug("Using preferred codec config: {}", common::ToString(context_type));
    return GetCachedPreferredConfiguration(context_type);
  }

  const types::AudioSetConfiguration* conf = nullptr;
  bool is_valid = false;

  /* Refresh the cache if there is no valid configuration */
  if (context_to_configuration_cache_map_.count(context_type) != 0) {
    auto& valid_config_pair = context_to_configuration_cache_map_.at(context_type);
    is_valid = valid_config_pair.first;
    conf = valid_config_pair.second.get();
  }
  if (!is_valid || (conf == nullptr)) {
    UpdateAudioSetConfigurationCache(context_type);
  }

  return GetCachedConfiguration(context_type);
}

std::shared_ptr<const types::AudioSetConfiguration> LeAudioDeviceGroup::GetPreferredConfiguration(
        LeAudioContextType context_type) const {
  if (context_type == LeAudioContextType::UNINITIALIZED) {
    return nullptr;
  }

  const types::AudioSetConfiguration* conf = nullptr;
  bool is_valid = false;

  if (context_to_preferred_configuration_cache_map_.count(context_type) != 0) {
    auto& valid_config_pair = context_to_preferred_configuration_cache_map_.at(context_type);
    is_valid = valid_config_pair.first;
    conf = valid_config_pair.second.get();
  }
  if (!is_valid || conf == nullptr) {
    UpdateAudioSetConfigurationCache(context_type, true);
  }

  return GetCachedPreferredConfiguration(context_type);
}

LeAudioCodecConfiguration LeAudioDeviceGroup::GetAudioSessionCodecConfigForDirection(
        LeAudioContextType context_type, uint8_t direction) const {
  auto audio_set_conf = GetConfiguration(context_type);
  if (!audio_set_conf) {
    return {0, 0, 0, 0};
  }

  auto group_config = utils::GetAudioSessionCodecConfigFromAudioSetConfiguration(
          *audio_set_conf.get(), direction);
  return group_config;
}

bool LeAudioDeviceGroup::HasCodecConfigurationForDirection(types::LeAudioContextType context_type,
                                                           uint8_t direction) const {
  auto audio_set_conf = GetConfiguration(context_type);
  return audio_set_conf ? !audio_set_conf->confs.get(direction).empty() : false;
}

bool LeAudioDeviceGroup::IsAudioSetConfigurationAvailable(LeAudioContextType group_context_type) {
  return GetConfiguration(group_context_type) != nullptr;
}

bool LeAudioDeviceGroup::IsMetadataChanged(
        const BidirectionalPair<AudioContexts>& context_types,
        const BidirectionalPair<std::vector<uint8_t>>& ccid_lists) const {
  for (auto* leAudioDevice = GetFirstActiveDevice(); leAudioDevice;
       leAudioDevice = GetNextActiveDevice(leAudioDevice)) {
    if (leAudioDevice->IsMetadataChanged(context_types, ccid_lists)) {
      return true;
    }
  }

  return false;
}

bool LeAudioDeviceGroup::IsCisPartOfCurrentStream(uint16_t cis_conn_hdl) const {
  auto& sink_stream_locations = stream_conf.stream_params.sink.stream_config.stream_map;
  auto iter =
          std::find_if(sink_stream_locations.begin(), sink_stream_locations.end(),
                       [cis_conn_hdl](auto& info) { return cis_conn_hdl == info.stream_handle; });

  if (iter != sink_stream_locations.end()) {
    return true;
  }

  auto& source_stream_locations = stream_conf.stream_params.source.stream_config.stream_map;
  iter = std::find_if(source_stream_locations.begin(), source_stream_locations.end(),
                      [cis_conn_hdl](auto& info) { return cis_conn_hdl == info.stream_handle; });

  return iter != source_stream_locations.end();
}

void LeAudioDeviceGroup::RemoveCisFromStreamIfNeeded(LeAudioDevice* leAudioDevice,
                                                     uint16_t cis_conn_hdl) {
  log::info("CIS Connection Handle: {}", cis_conn_hdl);

  if (!IsCisPartOfCurrentStream(cis_conn_hdl)) {
    cig.UnassignCis(leAudioDevice, cis_conn_hdl);
    return;
  }

  /* Cache the old values for comparison */
  auto old_sink_channels = stream_conf.stream_params.sink.num_of_channels;
  auto old_source_channels = stream_conf.stream_params.source.num_of_channels;

  for (auto dir : {types::kLeAudioDirectionSink, types::kLeAudioDirectionSource}) {
    auto& params = stream_conf.stream_params.get(dir);
    params.stream_config.stream_map.erase(
            std::remove_if(params.stream_config.stream_map.begin(),
                           params.stream_config.stream_map.end(),
                           [leAudioDevice, &cis_conn_hdl, &params, dir](auto& info) {
                             if (!cis_conn_hdl) {
                               cis_conn_hdl = info.stream_handle;
                             }
                             auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(cis_conn_hdl);
                             if (ases_pair.get(dir) && cis_conn_hdl == info.stream_handle) {
                               params.num_of_devices--;
                               params.num_of_channels -=
                                       ases_pair.get(dir)
                                               ->codec_config.channel_count_per_iso_stream;
                               params.audio_channel_allocation &= ~info.audio_channel_allocation;
                             }
                             return ases_pair.get(dir) && cis_conn_hdl == info.stream_handle;
                           }),
            params.stream_config.stream_map.end());
  }

  log::info(
          "Sink Number Of Devices: {}, Sink Number Of Channels: {}, Source Number "
          "Of Devices: {}, Source Number Of Channels: {}",
          stream_conf.stream_params.sink.num_of_devices,
          stream_conf.stream_params.sink.num_of_channels,
          stream_conf.stream_params.source.num_of_devices,
          stream_conf.stream_params.source.num_of_channels);

  cig.UnassignCis(leAudioDevice, cis_conn_hdl);

  if (old_sink_channels > 0) {
    if (stream_conf.stream_params.sink.num_of_channels == 0) {
      ClearSinksFromConfiguration();
    } else if (old_sink_channels > stream_conf.stream_params.sink.num_of_channels) {
      CodecManager::GetInstance()->UpdateCisConfiguration(
              cig.cises,
              stream_conf.stream_params.get(bluetooth::le_audio::types::kLeAudioDirectionSink),
              bluetooth::le_audio::types::kLeAudioDirectionSink);
    }
  }

  if (old_source_channels > 0) {
    if (stream_conf.stream_params.source.num_of_channels == 0) {
      ClearSourcesFromConfiguration();
    } else if (old_source_channels > stream_conf.stream_params.source.num_of_channels) {
      CodecManager::GetInstance()->UpdateCisConfiguration(
              cig.cises,
              stream_conf.stream_params.get(bluetooth::le_audio::types::kLeAudioDirectionSource),
              bluetooth::le_audio::types::kLeAudioDirectionSource);
    }
  }
}

bool LeAudioDeviceGroup::IsPendingConfiguration(void) const {
  log::verbose("group {}, is pending: {} ", group_id_, stream_conf.pending_configuration);
  return stream_conf.pending_configuration;
}

void LeAudioDeviceGroup::SetPendingConfiguration(void) {
  log::verbose("group {}, is pending from {} to true", group_id_,
               stream_conf.pending_configuration);
  stream_conf.pending_configuration = true;
}

void LeAudioDeviceGroup::ClearPendingConfiguration(void) {
  log::verbose("group {}, is pending from {} to false", group_id_,
               stream_conf.pending_configuration);
  stream_conf.pending_configuration = false;
}

void LeAudioDeviceGroup::Disable(int gatt_if) {
  is_enabled_ = false;

  for (auto& device_iter : leAudioDevices_) {
    if (!device_iter.lock()->autoconnect_flag_) {
      continue;
    }

    auto connection_state = device_iter.lock()->GetConnectionState();
    auto address = device_iter.lock()->address_;

    btif_storage_set_leaudio_autoconnect(address, false);
    device_iter.lock()->autoconnect_flag_ = false;

    log::info("Group {} in state {}. Removing {} from background connect", group_id_,
              bluetooth::common::ToString(GetState()), address);

    BTA_GATTC_CancelOpen(gatt_if, address, false);

    if (connection_state == DeviceConnectState::CONNECTING_AUTOCONNECT) {
      device_iter.lock()->SetConnectionState(DeviceConnectState::DISCONNECTED);
    }
  }
}

void LeAudioDeviceGroup::Enable(int gatt_if, tBTM_BLE_CONN_TYPE reconnection_mode) {
  is_enabled_ = true;
  for (auto& device_iter : leAudioDevices_) {
    if (device_iter.lock()->autoconnect_flag_) {
      continue;
    }

    auto address = device_iter.lock()->address_;
    auto connection_state = device_iter.lock()->GetConnectionState();

    btif_storage_set_leaudio_autoconnect(address, true);
    device_iter.lock()->autoconnect_flag_ = true;

    log::info("Group {} in state {}. Adding {} from background connect", group_id_,
              bluetooth::common::ToString(GetState()), address);

    if (connection_state == DeviceConnectState::DISCONNECTED) {
      BTA_GATTC_Open(gatt_if, address, reconnection_mode, false);
      device_iter.lock()->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);
    }
  }
}

bool LeAudioDeviceGroup::IsEnabled(void) const { return is_enabled_; }

void LeAudioDeviceGroup::AddToAllowListNotConnectedGroupMembers(int gatt_if) {
  for (const auto& device_iter : leAudioDevices_) {
    auto connection_state = device_iter.lock()->GetConnectionState();
    if (connection_state == DeviceConnectState::CONNECTED ||
        connection_state == DeviceConnectState::CONNECTING_BY_USER ||
        connection_state == DeviceConnectState::CONNECTED_BY_USER_GETTING_READY ||
        connection_state == DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY) {
      continue;
    }

    auto address = device_iter.lock()->address_;
    log::info("Group {} in state {}. Adding {} to allow list", group_id_,
              bluetooth::common::ToString(GetState()), address);

    /* When adding set members to allow list, let use direct connect first.
     * When it fails (i.e. device is not advertising), it will go to background
     * connect. We are doing that because for background connect, stack is using
     * slow scan parameters for connection which might delay connecting
     * available members.
     */
    BTA_GATTC_CancelOpen(gatt_if, address, false);
    BTA_GATTC_Open(gatt_if, address, BTM_BLE_DIRECT_CONNECTION, false);
    device_iter.lock()->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);
  }
}

void LeAudioDeviceGroup::ApplyReconnectionMode(int gatt_if, tBTM_BLE_CONN_TYPE reconnection_mode) {
  for (const auto& device_iter : leAudioDevices_) {
    BTA_GATTC_CancelOpen(gatt_if, device_iter.lock()->address_, false);
    BTA_GATTC_Open(gatt_if, device_iter.lock()->address_, reconnection_mode, false);
    log::info("Group {} in state {}. Adding {} to default reconnection mode", group_id_,
              bluetooth::common::ToString(GetState()), device_iter.lock()->address_);
    device_iter.lock()->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);
  }
}

bool LeAudioDeviceGroup::IsConfiguredForContext(LeAudioContextType context_type) const {
  /* Check if all connected group members are configured */
  if (GetConfigurationContextType() != context_type) {
    return false;
  }

  if (!stream_conf.conf) {
    return false;
  }

  /* Check if used configuration is same as the active one.*/
  return stream_conf.conf.get() == GetActiveConfiguration().get();
}

std::unique_ptr<types::AudioSetConfiguration> LeAudioDeviceGroup::FindFirstSupportedConfiguration(
        const CodecManager::UnicastConfigurationRequirements& requirements,
        const types::AudioSetConfigurations* confs, bool use_preference) const {
  log::assert_that(confs != nullptr, "confs should not be null");

  log::debug("context type: {},  number of connected devices: {}",
             bluetooth::common::ToString(requirements.audio_context_type), NumOfConnected());

  /* Filter out device set for each end every scenario */
  for (const auto& conf : *confs) {
    log::assert_that(conf != nullptr, "confs should not be null");
    if (IsAudioSetConfigurationSupported(requirements, conf, use_preference)) {
      log::debug("found: {}", conf->name);
      return std::make_unique<types::AudioSetConfiguration>(*conf);
    }
  }

  return nullptr;
}

/* This method should choose aproperiate ASEs to be active and set a cached
 * configuration for codec and qos.
 */
bool LeAudioDeviceGroup::Configure(
        LeAudioContextType context_type,
        const types::BidirectionalPair<AudioContexts>& metadata_context_types,
        types::BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
  auto conf = GetConfiguration(context_type);
  if (!conf) {
    log::error("Requested context type: {} , is in mismatch with cached available contexts",
               bluetooth::common::ToString(context_type));
    return false;
  }

  log::debug("setting context type: {}", bluetooth::common::ToString(context_type));

  if (!ConfigureAses(conf.get(), context_type, metadata_context_types, ccid_lists)) {
    log::error(
            ", requested context type: {}, is in mismatch with cached available "
            "contexts",
            bluetooth::common::ToString(context_type));
    return false;
  }

  /* Store selected configuration at once it is chosen.
   * It might happen it will get unavailable in some point of time
   */
  stream_conf.conf = conf;
  return true;
}

LeAudioDeviceGroup::~LeAudioDeviceGroup(void) { this->Cleanup(); }

void LeAudioDeviceGroup::PrintDebugState(void) const {
  auto active_conf = GetActiveConfiguration();
  std::stringstream debug_str;

  debug_str << "\n Groupd id: " << group_id_ << (is_enabled_ ? " enabled" : " disabled")
            << ", state: " << bluetooth::common::ToString(GetState())
            << ", target state: " << bluetooth::common::ToString(GetTargetState())
            << ", cig state: " << bluetooth::common::ToString(cig.GetState())
            << ", \n group supported contexts: "
            << bluetooth::common::ToString(GetSupportedContexts())
            << ", \n group available contexts: "
            << bluetooth::common::ToString(GetAvailableContexts())
            << ", \n group user allowed contexts: "
            << bluetooth::common::ToString(GetAllowedContextMask())
            << ", \n configuration context type: "
            << bluetooth::common::ToString(GetConfigurationContextType())
            << ", \n active configuration name: " << (active_conf ? active_conf->name : " not set");

  if (cig.cises.size() > 0) {
    log::info("\n Allocated CISes: {}", static_cast<int>(cig.cises.size()));
    for (auto cis : cig.cises) {
      log::info("\n cis id: {}, type: {}, conn_handle {}, addr: {}", cis.id, cis.type,
                cis.conn_handle, cis.addr.ToString());
    }
  }

  if (GetFirstActiveDevice() != nullptr) {
    uint32_t sink_delay = 0;
    uint32_t source_delay = 0;
    GetPresentationDelay(&sink_delay, bluetooth::le_audio::types::kLeAudioDirectionSink);
    GetPresentationDelay(&source_delay, bluetooth::le_audio::types::kLeAudioDirectionSource);
    auto phy_mtos = GetPhyBitmask(bluetooth::le_audio::types::kLeAudioDirectionSink);
    auto phy_stom = GetPhyBitmask(bluetooth::le_audio::types::kLeAudioDirectionSource);
    auto max_transport_latency_mtos = GetMaxTransportLatencyMtos();
    auto max_transport_latency_stom = GetMaxTransportLatencyStom();
    auto sdu_mts = GetSduInterval(bluetooth::le_audio::types::kLeAudioDirectionSink);
    auto sdu_stom = GetSduInterval(bluetooth::le_audio::types::kLeAudioDirectionSource);

    debug_str << "\n presentation_delay for sink (speaker): " << +sink_delay
              << " us, presentation_delay for source (microphone): " << +source_delay
              << "us, \n MtoS transport latency:  " << +max_transport_latency_mtos
              << ", StoM transport latency: " << +max_transport_latency_stom
              << ", \n MtoS Phy: " << loghex(phy_mtos) << ", MtoS sdu: " << loghex(phy_stom)
              << " \n MtoS sdu: " << +sdu_mts << ", StoM sdu: " << +sdu_stom;
  }

  log::info("{}", debug_str.str());

  for (const auto& device_iter : leAudioDevices_) {
    device_iter.lock()->PrintDebugState();
  }
}

void LeAudioDeviceGroup::Dump(std::stringstream& stream, int active_group_id) const {
  bool is_active = (group_id_ == active_group_id);
  auto active_conf = GetActiveConfiguration();

  stream << "    ■ Group id: " << group_id_ << ", " << (is_enabled_ ? "Enabled" : "Disabled")
         << ", " << (is_active ? "Active\n" : "Inactive\n") << "      Current state: " << GetState()
         << ",\ttarget state: " << GetTargetState() << ",\tcig state: " << cig.GetState() << "\n"
         << "      Group supported contexts: " << GetSupportedContexts() << "\n"
         << "      Group available contexts: " << GetAvailableContexts() << "\n"
         << "      Group user allowed contexts: " << GetAllowedContextMask() << "\n"
         << "      Configuration context type: "
         << bluetooth::common::ToString(GetConfigurationContextType()).c_str() << "\n"
         << "      Active configuration name:\t" << (active_conf ? active_conf->name : "Not set")
         << "\n"
         << "      Stream configuration:\t\t"
         << (stream_conf.conf != nullptr ? stream_conf.conf->name : "Not set ") << "\n"
         << "      Codec ID: " << +(stream_conf.codec_id.coding_format)
         << ",\tpending reconfiguration: " << stream_conf.pending_configuration << "\n"
         << "      Num of devices:\t" << Size() << " (" << NumOfConnected() << " connected)\n"
         << "      Num of sinks:\t" << stream_conf.stream_params.sink.num_of_devices << " ("
         << stream_conf.stream_params.sink.stream_config.stream_map.size() << " connected)\n"
         << "      Num of sources:\t" << stream_conf.stream_params.source.num_of_devices << " ("
         << stream_conf.stream_params.source.stream_config.stream_map.size() << " connected)";

  if (GetFirstActiveDevice() != nullptr) {
    uint32_t sink_delay;
    if (GetPresentationDelay(&sink_delay, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
      stream << "\n      presentation_delay for sink (speaker): " << sink_delay << " us";
    }

    uint32_t source_delay;
    if (GetPresentationDelay(&source_delay, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
      stream << "\n      presentation_delay for source (microphone): " << source_delay << " us";
    }
  }
  stream << "\n";

  stream << "      == CISes (" << static_cast<int>(cig.cises.size()) << "):";
  if (cig.cises.size() > 0) {
    for (auto cis : cig.cises) {
      stream << "\n\t cis id: " << static_cast<int>(cis.id)
             << ",\ttype: " << static_cast<int>(cis.type)
             << ",\tconn_handle: " << static_cast<int>(cis.conn_handle)
             << ",\taddr: " << cis.addr.ToRedactedStringForLogging();
    }
  }
  stream << "\n";

  for (const auto& device_iter : leAudioDevices_) {
    device_iter.lock()->Dump(stream);
  }

  for (const auto& device_iter : leAudioDevices_) {
    device_iter.lock()->DumpPacsDebugState(stream);
  }
  stream << "\n";
}

LeAudioDeviceGroup* LeAudioDeviceGroups::Add(int group_id) {
  /* Get first free group id */
  if (FindById(group_id)) {
    log::error("group already exists, id: 0x{:x}", group_id);
    return nullptr;
  }

  return (groups_.emplace_back(std::make_unique<LeAudioDeviceGroup>(group_id))).get();
}

void LeAudioDeviceGroups::Remove(int group_id) {
  auto iter = std::find_if(groups_.begin(), groups_.end(),
                           [&group_id](auto const& group) { return group->group_id_ == group_id; });

  if (iter == groups_.end()) {
    log::error("no such group_id: {}", group_id);
    return;
  }

  groups_.erase(iter);
}

LeAudioDeviceGroup* LeAudioDeviceGroups::FindById(int group_id) const {
  auto iter = std::find_if(groups_.begin(), groups_.end(),
                           [&group_id](auto const& group) { return group->group_id_ == group_id; });

  return (iter == groups_.end()) ? nullptr : iter->get();
}

void LeAudioDeviceGroups::Cleanup(void) {
  for (auto& g : groups_) {
    g->Cleanup();
  }

  groups_.clear();
}

void LeAudioDeviceGroups::Dump(std::stringstream& stream, int active_group_id) const {
  /* Dump first active group */
  stream << "  == Active Groups:\n";
  for (auto& g : groups_) {
    if (g->group_id_ == active_group_id) {
      g->Dump(stream, active_group_id);
      break;
    }
  }

  /* Dump non active group */
  stream << "  == Inactive Groups:\n";
  for (auto& g : groups_) {
    if (g->group_id_ != active_group_id) {
      g->Dump(stream, active_group_id);
    }
  }
}

bool LeAudioDeviceGroups::IsAnyInTransition(void) const {
  for (auto& g : groups_) {
    if (g->IsInTransition()) {
      log::debug("group: {} is in transition", g->group_id_);
      return true;
    }
  }
  return false;
}

size_t LeAudioDeviceGroups::Size() const { return groups_.size(); }

std::vector<int> LeAudioDeviceGroups::GetGroupsIds(void) const {
  std::vector<int> result;

  for (auto const& group : groups_) {
    result.push_back(group->group_id_);
  }

  return result;
}

}  // namespace bluetooth::le_audio
