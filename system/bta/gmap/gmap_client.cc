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

#include "bta/le_audio/gmap_client.h"

#include <android_bluetooth_sysprop.h>
#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>

#include <bitset>
#include <cstdint>
#include <sstream>

#include "bta_gatt_queue.h"
#include "osi/include/properties.h"
#include "stack/include/bt_types.h"

using namespace bluetooth;
using bluetooth::le_audio::GmapClient;
bool GmapClient::is_offloader_support_gmap_ = false;

void GmapClient::AddFromStorage(uint8_t role, uint16_t role_handle, uint8_t UGT_feature,
                                uint16_t UGT_feature_handle) {
  role_ = role;
  role_handle_ = role_handle;
  UGT_feature_ = UGT_feature;
  UGT_feature_handle_ = UGT_feature_handle;
}

void GmapClient::DebugDump(std::stringstream &stream) {
  if (!IsGmapClientEnabled()) {
    stream << "GmapClient not enabled\n";
    return;
  }
  stream << "GmapClient device: " << addr_.ToRedactedStringForLogging() << ", Role: " << role_
         << ", ";
  stream << "UGT Feature: " << UGT_feature_ << "\n";
}

bool GmapClient::IsGmapClientEnabled() {
  bool system_prop = osi_property_get_bool("bluetooth.profile.gmap.enabled", false);
  bool is_gmap_supported_in_software_datapath =
          android::sysprop::bluetooth::LeAudio::is_software_datapath_supported_test().value_or(
                  false);

  bool result =
          system_prop && (is_gmap_supported_in_software_datapath || is_offloader_support_gmap_);
  log::info(
          "GmapClientEnabled={}, system_prop={}, "
          "is_gmap_supported_in_software_datapath={}, "
          "offloader_support={}",
          result, system_prop, is_gmap_supported_in_software_datapath,
          GmapClient::is_offloader_support_gmap_);
  return result;
}

void GmapClient::UpdateGmapOffloaderSupport(bool value) {
  GmapClient::is_offloader_support_gmap_ = value;
}

bool GmapClient::parseAndSaveGmapRole(uint16_t len, const uint8_t *value) {
  if (len != GmapClient::kGmapRoleLen) {
    log::error("device: {}, Wrong len of GMAP Role characteristic", addr_);
    return false;
  }

  STREAM_TO_UINT8(role_, value);
  log::info("GMAP device: {}, Role: {}", addr_, role_.to_string());
  return true;
}

bool GmapClient::parseAndSaveUGTFeature(uint16_t len, const uint8_t *value) {
  if (len != kGmapUGTFeatureLen) {
    log::error("device: {}, Wrong len of GMAP UGT Feature characteristic", addr_);
    return false;
  }
  STREAM_TO_UINT8(UGT_feature_, value);
  log::info("GMAP device: {}, Feature: {}", addr_, UGT_feature_.to_string());
  return true;
}

std::bitset<8> GmapClient::getRole() const { return role_; }

uint16_t GmapClient::getRoleHandle() const { return role_handle_; }

void GmapClient::setRoleHandle(uint16_t handle) { role_handle_ = handle; }

std::bitset<8> GmapClient::getUGTFeature() const { return UGT_feature_; }

uint16_t GmapClient::getUGTFeatureHandle() const { return UGT_feature_handle_; }

void GmapClient::setUGTFeatureHandle(uint16_t handle) { UGT_feature_handle_ = handle; }
