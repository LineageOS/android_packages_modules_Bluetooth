/******************************************************************************
 *
 *  Copyright 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "storage_helper.h"

#include <bluetooth/log.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <tuple>
#include <vector>

#include "client_parser.h"
#include "devices.h"
#include "le_audio_types.h"
#include "stack/include/bt_types.h"

using bluetooth::le_audio::types::hdl_pair;

namespace bluetooth::le_audio {
static constexpr uint8_t LEAUDIO_PACS_STORAGE_CURRENT_LAYOUT_MAGIC = 0x00;
static constexpr uint8_t LEAUDIO_ASE_STORAGE_CURRENT_LAYOUT_MAGIC = 0x00;
static constexpr uint8_t LEAUDIO_HANDLES_STORAGE_CURRENT_LAYOUT_MAGIC = 0x00;
static constexpr uint8_t LEAUDIO_CODEC_ID_SZ = 5;

static constexpr size_t LEAUDIO_STORAGE_MAGIC_SZ = sizeof(uint8_t) /* magic is always uint8_t */;

static constexpr size_t LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ =
        LEAUDIO_STORAGE_MAGIC_SZ + sizeof(uint8_t); /* num_of_entries */

static constexpr size_t LEAUDIO_PACS_ENTRY_HDR_SZ =
        sizeof(uint16_t) /*handle*/ + sizeof(uint16_t) /*ccc handle*/ +
        sizeof(uint8_t) /* number of pack records in single characteristic */;

static constexpr size_t LEAUDIO_PACS_ENTRY_SZ =
        sizeof(uint8_t) /* size of single pac record */ + LEAUDIO_CODEC_ID_SZ /*codec id*/ +
        sizeof(uint8_t) /*codec capabilities len*/ + sizeof(uint8_t) /*metadata len*/;

static constexpr size_t LEAUDIO_ASES_ENTRY_SZ =
        sizeof(uint16_t) /*handle*/ + sizeof(uint16_t) /*ccc handle*/ +
        sizeof(uint8_t) /*direction*/ + sizeof(uint8_t) /*ase id*/;

static constexpr size_t LEAUDIO_STORAGE_HANDLES_ENTRIES_SZ =
        LEAUDIO_STORAGE_MAGIC_SZ + sizeof(uint16_t) /*control point handle*/ +
        sizeof(uint16_t) /*ccc handle*/ + sizeof(uint16_t) /*sink audio location handle*/ +
        sizeof(uint16_t) /*ccc handle*/ + sizeof(uint16_t) /*source audio location handle*/ +
        sizeof(uint16_t) /*ccc handle*/ + sizeof(uint16_t) /*supported context type handle*/ +
        sizeof(uint16_t) /*ccc handle*/ + sizeof(uint16_t) /*available context type handle*/ +
        sizeof(uint16_t) /*ccc handle*/ + sizeof(uint16_t) /* tmas handle */;

static bool serializePacs(const bluetooth::le_audio::types::PublishedAudioCapabilities& pacs,
                          std::vector<uint8_t>& out) {
  auto num_of_pacs = pacs.size();
  if (num_of_pacs == 0 || (num_of_pacs > std::numeric_limits<uint8_t>::max())) {
    log::warn("No pacs available");
    return false;
  }

  /* Calculate the total size */
  auto pac_bin_size = LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ;
  for (auto pac_tuple : pacs) {
    auto& pac_recs = std::get<1>(pac_tuple);
    pac_bin_size += LEAUDIO_PACS_ENTRY_HDR_SZ;
    for (const auto& pac : pac_recs) {
      pac_bin_size += LEAUDIO_PACS_ENTRY_SZ;
      pac_bin_size += pac.metadata.RawPacketSize();
      pac_bin_size += pac.codec_spec_caps_raw.size();
    }
  }

  out.resize(pac_bin_size);
  auto* ptr = out.data();

  /* header */
  UINT8_TO_STREAM(ptr, LEAUDIO_PACS_STORAGE_CURRENT_LAYOUT_MAGIC);
  UINT8_TO_STREAM(ptr, num_of_pacs);

  /* pacs entries */
  for (auto pac_tuple : pacs) {
    auto& pac_recs = std::get<1>(pac_tuple);
    uint16_t handle = std::get<0>(pac_tuple).val_hdl;
    uint16_t ccc_handle = std::get<0>(pac_tuple).ccc_hdl;

    UINT16_TO_STREAM(ptr, handle);
    UINT16_TO_STREAM(ptr, ccc_handle);
    UINT8_TO_STREAM(ptr, pac_recs.size());

    log::verbose("Handle: 0x{:04x}, ccc handle: 0x{:04x}, pac count: {}", handle, ccc_handle,
                 static_cast<int>(pac_recs.size()));

    for (const auto& pac : pac_recs) {
      /* Pac len */
      auto pac_len =
              LEAUDIO_PACS_ENTRY_SZ + pac.codec_spec_caps_raw.size() + pac.metadata.RawPacketSize();
      log::verbose("Pac size {}", static_cast<int>(pac_len));
      UINT8_TO_STREAM(ptr, pac_len - 1 /* Minus size */);

      /* Codec ID*/
      UINT8_TO_STREAM(ptr, pac.codec_id.coding_format);
      UINT16_TO_STREAM(ptr, pac.codec_id.vendor_company_id);
      UINT16_TO_STREAM(ptr, pac.codec_id.vendor_codec_id);

      /* Codec caps */
      log::verbose("Codec capability size {}", static_cast<int>(pac.codec_spec_caps_raw.size()));
      UINT8_TO_STREAM(ptr, pac.codec_spec_caps_raw.size());
      if (pac.codec_spec_caps_raw.size() > 0) {
        ARRAY_TO_STREAM(ptr, pac.codec_spec_caps_raw.data(),
                        static_cast<int>(pac.codec_spec_caps_raw.size()));
      }

      /* Metadata */
      auto raw_metadata = pac.metadata.RawPacket();
      log::verbose("Metadata size {}", static_cast<int>(raw_metadata.size()));
      UINT8_TO_STREAM(ptr, raw_metadata.size());
      if (raw_metadata.size() > 0) {
        ARRAY_TO_STREAM(ptr, raw_metadata.data(), (int)raw_metadata.size());
      }
    }
  }
  return true;
}

bool SerializeSinkPacs(const bluetooth::le_audio::LeAudioDevice* leAudioDevice,
                       std::vector<uint8_t>& out) {
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }
  log::verbose("Device {}, num of PAC characteristics: {}", leAudioDevice->address_,
               static_cast<int>(leAudioDevice->snk_pacs_.size()));
  return serializePacs(leAudioDevice->snk_pacs_, out);
}

bool SerializeSourcePacs(const bluetooth::le_audio::LeAudioDevice* leAudioDevice,
                         std::vector<uint8_t>& out) {
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }
  log::verbose("Device {}, num of PAC characteristics: {}", leAudioDevice->address_,
               static_cast<int>(leAudioDevice->src_pacs_.size()));
  return serializePacs(leAudioDevice->src_pacs_, out);
}

static bool deserializePacs(LeAudioDevice* leAudioDevice,
                            types::PublishedAudioCapabilities& pacs_db,
                            const std::vector<uint8_t>& in) {
  if (in.size() < LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ + LEAUDIO_PACS_ENTRY_SZ) {
    log::warn("There is not single PACS stored");
    return false;
  }

  auto* ptr = in.data();

  uint8_t magic;
  STREAM_TO_UINT8(magic, ptr);

  if (magic != LEAUDIO_PACS_STORAGE_CURRENT_LAYOUT_MAGIC) {
    log::error("Invalid magic ({}!={}) for device {}", magic,
               LEAUDIO_PACS_STORAGE_CURRENT_LAYOUT_MAGIC, leAudioDevice->address_);
    return false;
  }

  uint8_t num_of_pacs_chars;
  STREAM_TO_UINT8(num_of_pacs_chars, ptr);

  if (in.size() <
      LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ + (num_of_pacs_chars * LEAUDIO_PACS_ENTRY_SZ)) {
    log::error("Invalid persistent storage data for device {}", leAudioDevice->address_);
    return false;
  }

  /* pacs entries */
  while (num_of_pacs_chars--) {
    struct hdl_pair hdl_pair;
    uint8_t pac_count;

    STREAM_TO_UINT16(hdl_pair.val_hdl, ptr);
    STREAM_TO_UINT16(hdl_pair.ccc_hdl, ptr);
    STREAM_TO_UINT8(pac_count, ptr);

    log::verbose("Handle: 0x{:04x}, ccc handle: 0x{:04x}, pac_count: {}", hdl_pair.val_hdl,
                 hdl_pair.ccc_hdl, pac_count);

    pacs_db.push_back(std::make_tuple(
            hdl_pair, std::vector<struct bluetooth::le_audio::types::acs_ac_record>()));

    auto hdl = hdl_pair.val_hdl;
    auto pac_tuple_iter = std::find_if(pacs_db.begin(), pacs_db.end(), [&hdl](auto& pac_ent) {
      return std::get<0>(pac_ent).val_hdl == hdl;
    });

    std::vector<struct bluetooth::le_audio::types::acs_ac_record> pac_recs;
    while (pac_count--) {
      uint8_t pac_len;
      STREAM_TO_UINT8(pac_len, ptr);
      log::verbose("Pac len {}", pac_len);

      if (client_parser::pacs::ParseSinglePac(pac_recs, pac_len, ptr) < 0) {
        log::error("Cannot parse stored PACs (impossible)");
        return false;
      }
      ptr += pac_len;
    }
    leAudioDevice->RegisterPACs(&std::get<1>(*pac_tuple_iter), &pac_recs);
  }

  return true;
}

bool DeserializeSinkPacs(bluetooth::le_audio::LeAudioDevice* leAudioDevice,
                         const std::vector<uint8_t>& in) {
  log::verbose("");
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }
  return deserializePacs(leAudioDevice, leAudioDevice->snk_pacs_, in);
}

bool DeserializeSourcePacs(bluetooth::le_audio::LeAudioDevice* leAudioDevice,
                           const std::vector<uint8_t>& in) {
  log::verbose("");
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }
  return deserializePacs(leAudioDevice, leAudioDevice->src_pacs_, in);
}

bool SerializeAses(const bluetooth::le_audio::LeAudioDevice* leAudioDevice,
                   std::vector<uint8_t>& out) {
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }

  auto num_of_ases = leAudioDevice->ases_.size();
  log::debug("device: {}, number of ases {}", leAudioDevice->address_,
             static_cast<int>(num_of_ases));

  if (num_of_ases == 0 || (num_of_ases > std::numeric_limits<uint8_t>::max())) {
    log::warn("No ases available for device {}", leAudioDevice->address_);
    return false;
  }

  /* Calculate the total size */
  auto ases_bin_size = LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ + num_of_ases * LEAUDIO_ASES_ENTRY_SZ;
  out.resize(ases_bin_size);
  auto* ptr = out.data();

  /* header */
  UINT8_TO_STREAM(ptr, LEAUDIO_ASE_STORAGE_CURRENT_LAYOUT_MAGIC);
  UINT8_TO_STREAM(ptr, num_of_ases);

  /* pacs entries */
  for (const auto& ase : leAudioDevice->ases_) {
    log::verbose(
            "Storing ASE ID: {}, direction {}, handle 0x{:04x}, ccc_handle 0x{:04x}", ase.id,
            ase.direction == bluetooth::le_audio::types::kLeAudioDirectionSink ? "sink " : "source",
            ase.hdls.val_hdl, ase.hdls.ccc_hdl);

    UINT16_TO_STREAM(ptr, ase.hdls.val_hdl);
    UINT16_TO_STREAM(ptr, ase.hdls.ccc_hdl);
    UINT8_TO_STREAM(ptr, ase.id);
    UINT8_TO_STREAM(ptr, ase.direction);
  }

  return true;
}

bool DeserializeAses(bluetooth::le_audio::LeAudioDevice* leAudioDevice,
                     const std::vector<uint8_t>& in) {
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }

  if (in.size() < LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ + LEAUDIO_ASES_ENTRY_SZ) {
    log::warn("There is not single ASE stored for device {}", leAudioDevice->address_);
    return false;
  }

  auto* ptr = in.data();

  uint8_t magic;
  STREAM_TO_UINT8(magic, ptr);

  if (magic != LEAUDIO_ASE_STORAGE_CURRENT_LAYOUT_MAGIC) {
    log::error("Invalid magic ({}!={}", magic, LEAUDIO_PACS_STORAGE_CURRENT_LAYOUT_MAGIC);
    return false;
  }

  uint8_t num_of_ases;
  STREAM_TO_UINT8(num_of_ases, ptr);

  if (in.size() < LEAUDIO_STORAGE_HEADER_WITH_ENTRIES_SZ + (num_of_ases * LEAUDIO_ASES_ENTRY_SZ)) {
    log::error("Invalid persistent storage data for device {}", leAudioDevice->address_);
    return false;
  }

  log::debug("Loading {} Ases for device {}", num_of_ases, leAudioDevice->address_);
  /* sets entries */
  while (num_of_ases--) {
    uint16_t handle;
    uint16_t ccc_handle;
    uint8_t direction;
    uint8_t ase_id;

    STREAM_TO_UINT16(handle, ptr);
    STREAM_TO_UINT16(ccc_handle, ptr);
    STREAM_TO_UINT8(ase_id, ptr);
    STREAM_TO_UINT8(direction, ptr);

    leAudioDevice->ases_.emplace_back(handle, ccc_handle, direction, ase_id);
    log::verbose(
            "Loading ASE ID: {}, direction {}, handle 0x{:04x}, ccc_handle 0x{:04x}", ase_id,
            direction == bluetooth::le_audio::types::kLeAudioDirectionSink ? "sink " : "source",
            handle, ccc_handle);
  }

  return true;
}

bool SerializeHandles(const LeAudioDevice* leAudioDevice, std::vector<uint8_t>& out) {
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }

  /* Calculate the total size */
  out.resize(LEAUDIO_STORAGE_HANDLES_ENTRIES_SZ);
  auto* ptr = out.data();

  /* header */
  UINT8_TO_STREAM(ptr, LEAUDIO_HANDLES_STORAGE_CURRENT_LAYOUT_MAGIC);

  if (leAudioDevice->ctp_hdls_.val_hdl == 0 || leAudioDevice->ctp_hdls_.ccc_hdl == 0) {
    log::warn("Invalid control point handles for device {}", leAudioDevice->address_);
    return false;
  }

  UINT16_TO_STREAM(ptr, leAudioDevice->ctp_hdls_.val_hdl);
  UINT16_TO_STREAM(ptr, leAudioDevice->ctp_hdls_.ccc_hdl);

  UINT16_TO_STREAM(ptr, leAudioDevice->audio_locations_.sink
                                ? leAudioDevice->audio_locations_.sink->handles.val_hdl
                                : 0);
  UINT16_TO_STREAM(ptr, leAudioDevice->audio_locations_.sink
                                ? leAudioDevice->audio_locations_.sink->handles.ccc_hdl
                                : 0);

  UINT16_TO_STREAM(ptr, leAudioDevice->audio_locations_.source
                                ? leAudioDevice->audio_locations_.source->handles.val_hdl
                                : 0);
  UINT16_TO_STREAM(ptr, leAudioDevice->audio_locations_.source
                                ? leAudioDevice->audio_locations_.source->handles.ccc_hdl
                                : 0);

  UINT16_TO_STREAM(ptr, leAudioDevice->audio_supp_cont_hdls_.val_hdl);
  UINT16_TO_STREAM(ptr, leAudioDevice->audio_supp_cont_hdls_.ccc_hdl);

  UINT16_TO_STREAM(ptr, leAudioDevice->audio_avail_hdls_.val_hdl);
  UINT16_TO_STREAM(ptr, leAudioDevice->audio_avail_hdls_.ccc_hdl);

  UINT16_TO_STREAM(ptr, leAudioDevice->tmap_role_hdl_);

  return true;
}

bool DeserializeHandles(LeAudioDevice* leAudioDevice, const std::vector<uint8_t>& in) {
  if (leAudioDevice == nullptr) {
    log::warn("Skipping unknown device");
    return false;
  }

  if (in.size() != LEAUDIO_STORAGE_HANDLES_ENTRIES_SZ) {
    log::warn("There is not single ASE stored for device {}", leAudioDevice->address_);
    return false;
  }

  auto* ptr = in.data();

  uint8_t magic;
  STREAM_TO_UINT8(magic, ptr);

  if (magic != LEAUDIO_HANDLES_STORAGE_CURRENT_LAYOUT_MAGIC) {
    log::error("Invalid magic ({}!={}) for device {}", magic,
               LEAUDIO_PACS_STORAGE_CURRENT_LAYOUT_MAGIC, leAudioDevice->address_);
    return false;
  }

  STREAM_TO_UINT16(leAudioDevice->ctp_hdls_.val_hdl, ptr);
  STREAM_TO_UINT16(leAudioDevice->ctp_hdls_.ccc_hdl, ptr);
  log::verbose("ctp.val_hdl: 0x{:04x}, ctp.ccc_hdl: 0x{:04x}", leAudioDevice->ctp_hdls_.val_hdl,
               leAudioDevice->ctp_hdls_.ccc_hdl);

  uint16_t val_hdl, ccc_hdl;
  STREAM_TO_UINT16(val_hdl, ptr);
  STREAM_TO_UINT16(ccc_hdl, ptr);
  log::verbose(
          "snk_audio_locations_hdls_.val_hdl: 0x{:04x},snk_audio_locations_hdls_.ccc_hdl: 0x{:04x}",
          val_hdl, ccc_hdl);
  if (val_hdl) {
    leAudioDevice->audio_locations_.sink.emplace(hdl_pair(val_hdl, ccc_hdl),
                                                 types::AudioLocations(0));
  }

  STREAM_TO_UINT16(val_hdl, ptr);
  STREAM_TO_UINT16(ccc_hdl, ptr);
  log::verbose(
          "src_audio_locations_hdls_.val_hdl: 0x{:04x},src_audio_locations_hdls_.ccc_hdl: 0x{:04x}",
          val_hdl, ccc_hdl);
  if (val_hdl) {
    leAudioDevice->audio_locations_.source.emplace(hdl_pair(val_hdl, ccc_hdl),
                                                   types::AudioLocations(0));
  }

  STREAM_TO_UINT16(leAudioDevice->audio_supp_cont_hdls_.val_hdl, ptr);
  STREAM_TO_UINT16(leAudioDevice->audio_supp_cont_hdls_.ccc_hdl, ptr);
  log::verbose("audio_supp_cont_hdls_.val_hdl: 0x{:04x},audio_supp_cont_hdls_.ccc_hdl: 0x{:04x}",
               leAudioDevice->audio_supp_cont_hdls_.val_hdl,
               leAudioDevice->audio_supp_cont_hdls_.ccc_hdl);

  STREAM_TO_UINT16(leAudioDevice->audio_avail_hdls_.val_hdl, ptr);
  STREAM_TO_UINT16(leAudioDevice->audio_avail_hdls_.ccc_hdl, ptr);
  log::verbose("audio_avail_hdls_.val_hdl: 0x{:04x},audio_avail_hdls_.ccc_hdl: 0x{:04x}",
               leAudioDevice->audio_avail_hdls_.val_hdl, leAudioDevice->audio_avail_hdls_.ccc_hdl);

  STREAM_TO_UINT16(leAudioDevice->tmap_role_hdl_, ptr);
  log::verbose("tmap_role_hdl_: 0x{:04x}", leAudioDevice->tmap_role_hdl_);

  leAudioDevice->known_service_handles_ = true;
  return true;
}

static constexpr uint8_t LEAUDIO_GMAP_STORAGE_V1_LAYOUT_MAGIC = 0x01;
static constexpr uint8_t LEAUDIO_GMAP_STORAGE_V1_LAYOUT_SZ = 7;
static constexpr uint8_t LEAUDIO_GMAP_STORAGE_CURRENT_LAYOUT_MAGIC =
        LEAUDIO_GMAP_STORAGE_V1_LAYOUT_MAGIC;

static bool SerializeGmapV1(const GmapClient* gmapClient, std::vector<uint8_t>& out) {
  if (gmapClient == nullptr) {
    log::warn("GMAP client not available");
    return false;
  }

  /* The total size */
  out.resize(LEAUDIO_GMAP_STORAGE_V1_LAYOUT_SZ);
  auto* ptr = out.data();

  /* header */
  UINT8_TO_STREAM(ptr, LEAUDIO_GMAP_STORAGE_V1_LAYOUT_MAGIC);

  /* handles */
  UINT16_TO_STREAM(ptr, gmapClient->getRoleHandle());
  UINT16_TO_STREAM(ptr, gmapClient->getUGTFeatureHandle());

  /* role & features */
  UINT8_TO_STREAM(ptr, gmapClient->getRole().to_ulong());
  UINT8_TO_STREAM(ptr, gmapClient->getUGTFeature().to_ulong());

  return true;
}

bool SerializeGmap(const GmapClient* gmapClient, std::vector<uint8_t>& out) {
  if (gmapClient == nullptr) {
    log::warn("GMAP client not available");
    return false;
  }

  if (LEAUDIO_GMAP_STORAGE_CURRENT_LAYOUT_MAGIC == LEAUDIO_GMAP_STORAGE_V1_LAYOUT_MAGIC) {
    return SerializeGmapV1(gmapClient, out);
  }

  log::warn("Invalid GMAP storage magic number {}", +LEAUDIO_GMAP_STORAGE_CURRENT_LAYOUT_MAGIC);
}

bool DeserializeGmapV1(GmapClient* gmapClient, const std::vector<uint8_t>& in) {
  if (in.size() != LEAUDIO_GMAP_STORAGE_V1_LAYOUT_SZ) {
    log::warn("Invalid storage size for GMAP data. Got {}, expected {}", in.size(),
              +LEAUDIO_GMAP_STORAGE_V1_LAYOUT_SZ);
    return false;
  }

  // Skip the magic number
  auto* ptr = in.data() + 1;

  /* handles */
  uint16_t role_handle, ugt_feature_handle;
  STREAM_TO_UINT16(role_handle, ptr);
  STREAM_TO_UINT16(ugt_feature_handle, ptr);

  uint8_t role, ugt_feature;
  STREAM_TO_UINT8(role, ptr);
  STREAM_TO_UINT8(ugt_feature, ptr);

  gmapClient->AddFromStorage(role, role_handle, ugt_feature, ugt_feature_handle);
  return true;
}

bool DeserializeGmap(GmapClient* gmapClient, const std::vector<uint8_t>& in) {
  if (gmapClient == nullptr) {
    log::warn("GMAP client not available");
    return false;
  }

  if (in.size() < 1) {
    log::warn("GMAP storage is not available");
    return false;
  }

  auto* ptr = in.data();
  uint8_t magic;
  STREAM_TO_UINT8(magic, ptr);

  if (magic == LEAUDIO_GMAP_STORAGE_V1_LAYOUT_MAGIC) {
    return DeserializeGmapV1(gmapClient, in);
  }

  log::warn("Invalid GMAP storage magic number. Got {}, current {}", +magic,
            +LEAUDIO_GMAP_STORAGE_CURRENT_LAYOUT_MAGIC);
  return false;
}

}  // namespace bluetooth::le_audio
