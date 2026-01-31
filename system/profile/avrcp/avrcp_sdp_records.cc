/*
 * Copyright 2024 The Android Open Source Project
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

#include "avrcp_sdp_records.h"

#include <bluetooth/log.h>

#include "bta/sys/bta_sys.h"
#include "stack/include/avrc_api.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/sdp_api.h"

using namespace bluetooth::legacy::stack::sdp;

namespace bluetooth::avrcp {

uint16_t AvrcSdpRecordHelper::AddRecord(const AvrcpSdpRecord& sdp_record_reference,
                                        uint16_t& request_id, const bool add_sys_uid) {
  uint16_t result = AVRC_FAIL;
  if (!sdp_record_request_map_.contains(request_id)) {
    request_id = ++request_id_counter_;
    log::debug("Generated request id: {}", request_id);
  }
  sdp_record_request_map_.insert({request_id_counter_, sdp_record_reference});
  AvrcpSdpRecord merged_sdp_records;
  MergeSdpRecords(merged_sdp_records);
  if (sdp_record_handle_ == RECORD_NOT_ASSIGNED) {
    log::debug("Adding a new record for {} with uuid 0x{:x} and categories as 0x{:x}",
               merged_sdp_records.service_name, merged_sdp_records.service_uuid,
               merged_sdp_records.categories);
    sdp_record_handle_ = get_legacy_stack_sdp_api()->SDP_CreateRecord();
    if (add_sys_uid) {
      bta_sys_add_uuid(merged_sdp_records.service_uuid);
    }
    result =
            AVRC_AddRecord(merged_sdp_records.service_uuid, merged_sdp_records.service_name.c_str(),
                           merged_sdp_records.provider_name.c_str(), merged_sdp_records.categories,
                           sdp_record_handle_, merged_sdp_records.browse_supported,
                           merged_sdp_records.profile_version, merged_sdp_records.cover_art_psm);
  } else {
    // SDP record is already present. Update the existing SDP record with the
    // new supported categories.
    result = UpdateRecord(merged_sdp_records.categories);
  }

  return result;
}

uint16_t AvrcSdpRecordHelper::UpdateRecord(const uint16_t new_categories) {
  log::debug("Categories set to 0x{:x}", new_categories);
  uint8_t temp[sizeof(uint16_t)], *p;
  p = temp;
  UINT16_TO_BE_STREAM(p, new_categories);
  return get_legacy_stack_sdp_api()->SDP_AddAttribute(sdp_record_handle_,
                                                      ATTR_ID_SUPPORTED_FEATURES, UINT_DESC_TYPE,
                                                      sizeof(temp), (uint8_t*)temp)
                 ? AVRC_SUCCESS
                 : AVRC_FAIL;
}

uint16_t AvrcSdpRecordHelper::RemoveRecord(const uint16_t request_id) {
  if (!sdp_record_request_map_.contains(request_id)) {
    log::warn("Trying to remove request id: {} that doesn't exist", request_id);
    return AVRC_FAIL;
  }
  const auto& sdp_record_request_pair = sdp_record_request_map_.find(request_id);
  const auto service_uuid = sdp_record_request_pair->second.service_uuid;
  sdp_record_request_map_.erase(request_id);
  AvrcpSdpRecord merged_sdp_records;
  MergeSdpRecords(merged_sdp_records);
  const uint16_t categories = merged_sdp_records.categories;
  log::info("Categories after removing the request_id {} : 0x{:x} for service uuid 0x{:x}",
            request_id, categories, service_uuid);
  if (sdp_record_handle_ != RECORD_NOT_ASSIGNED) {
    if (categories) {
      uint8_t temp[sizeof(uint16_t)], *p;
      p = temp;
      UINT16_TO_BE_STREAM(p, categories);
      return get_legacy_stack_sdp_api()->SDP_AddAttribute(
                     sdp_record_handle_, ATTR_ID_SUPPORTED_FEATURES, UINT_DESC_TYPE, sizeof(temp),
                     (uint8_t*)temp)
                     ? AVRC_SUCCESS
                     : AVRC_FAIL;
    } else {
      log::info("Removing the record for service uuid 0x{:x}", service_uuid);
      bta_sys_remove_uuid(service_uuid);
      auto result = AVRC_RemoveRecord(sdp_record_handle_);
      sdp_record_handle_ = RECORD_NOT_ASSIGNED;
      return result;
    }
  }
  // Nothing to remove.
  return AVRC_SUCCESS;
}

bool AvrcSdpRecordHelper::MergeSdpRecords(AvrcpSdpRecord& merged_sdp_record_reference) {
  if (sdp_record_request_map_.empty()) {
    return false;
  }
  int i = 0;
  merged_sdp_record_reference = sdp_record_request_map_.begin()->second;
  for (const auto& sdp_record_request_pair : sdp_record_request_map_) {
    if (i++ == 0) {
      continue;
    }
    const auto& sdp_record_value = sdp_record_request_pair.second;
    merged_sdp_record_reference.AddToExistingCategories(sdp_record_value.categories);
    // Register the highest profile version.
    if (sdp_record_value.profile_version > merged_sdp_record_reference.profile_version) {
      merged_sdp_record_reference.profile_version = sdp_record_value.profile_version;
    }
    if (sdp_record_value.cover_art_psm != 0) {
      merged_sdp_record_reference.cover_art_psm = sdp_record_value.cover_art_psm;
    }
    // Enable browse supported if any of the requests had browsing enabled.
    merged_sdp_record_reference.browse_supported |= sdp_record_value.browse_supported;
  }
  return true;
}
uint16_t TargetAvrcSdpRecordHelper::EnableCoverArt(uint16_t cover_art_psm, uint16_t request_id) {
  log::debug("Adding cover art support for request id {}", request_id);
  AVRC_RemoveRecord(sdp_record_handle_);
  sdp_record_handle_ = RECORD_NOT_ASSIGNED;
  if (sdp_record_request_map_.contains(request_id)) {
    auto& sdp_record_reference = sdp_record_request_map_.find(request_id)->second;
    sdp_record_reference.cover_art_psm = cover_art_psm;
    sdp_record_reference.AddToExistingCategories(AVRC_SUPF_TG_PLAYER_COVER_ART);
    return AddRecord(sdp_record_reference, request_id, false);
  }
  return AVRC_FAIL;
}

uint16_t TargetAvrcSdpRecordHelper::DisableCoverArt(uint16_t request_id) {
  log::debug("Disabling cover art support for request id {}", request_id);
  AVRC_RemoveRecord(sdp_record_handle_);
  sdp_record_handle_ = RECORD_NOT_ASSIGNED;
  if (sdp_record_request_map_.contains(request_id)) {
    auto& sdp_record_reference = sdp_record_request_map_.find(request_id)->second;
    sdp_record_reference.cover_art_psm = 0;
    sdp_record_reference.RemoveCategory(AVRC_SUPF_TG_PLAYER_COVER_ART);
    return AddRecord(sdp_record_reference, request_id, false);
  }
  return AVRC_FAIL;
}

uint16_t ControlAvrcSdpRecordHelper::UpdateRecord(const uint16_t new_categories) {
  bool result = AvrcSdpRecordHelper::UpdateRecord(new_categories) ? AVRC_SUCCESS : AVRC_FAIL;
  AvrcpSdpRecord merged_sdp_records;
  const bool is_request_available = MergeSdpRecords(merged_sdp_records);
  if (is_request_available) {
    if (merged_sdp_records.profile_version > AVRC_REV_1_3) {
      uint16_t class_list[2], count = 1;
      class_list[0] = merged_sdp_records.service_uuid;
      if (merged_sdp_records.service_uuid == UUID_SERVCLASS_AV_REMOTE_CONTROL) {
        class_list[1] = UUID_SERVCLASS_AV_REM_CTRL_CONTROL;
        count = 2;
      }
      result &= get_legacy_stack_sdp_api()->SDP_AddServiceClassIdList(sdp_record_handle_, count,
                                                                      class_list);
    }
    result &= get_legacy_stack_sdp_api()->SDP_AddProfileDescriptorList(
            sdp_record_handle_, merged_sdp_records.service_uuid,
            merged_sdp_records.profile_version);
  }
  return result ? AVRC_SUCCESS : AVRC_FAIL;
}

uint16_t ControlAvrcSdpRecordHelper::EnableCoverArt(uint16_t /*cover_art_psm*/,
                                                    const uint16_t /*request_id*/) {
  log::warn(
          "Enabling cover art support dynamically is not supported for service "
          "UUID {:x}",
          UUID_SERVCLASS_AV_REM_CTRL_CONTROL);
  return AVRC_FAIL;
}

uint16_t ControlAvrcSdpRecordHelper::DisableCoverArt(const uint16_t /*request_id*/) {
  log::warn(
          "Disabling cover art support dynamically is not supported for service "
          "UUID {:x}",
          UUID_SERVCLASS_AV_REM_CTRL_CONTROL);
  return AVRC_FAIL;
}

}  // namespace bluetooth::avrcp
