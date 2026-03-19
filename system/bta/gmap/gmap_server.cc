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

#include "bta/le_audio/gmap_server.h"

#include <android_bluetooth_sysprop.h>
#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>

#include <bitset>
#include <cstdint>
#include <sstream>
#include <unordered_map>
#include <vector>

#include "bta/le_audio/le_audio_types.h"
#include "bta_gatt_api.h"
#include "bta_gatt_queue.h"
#include "hardware/bt_common_types.h"
#include "include/hardware/bt_gmap.h"
#include "osi/include/properties.h"
#include "stack/include/gatt_api.h"

using bluetooth::Uuid;
using namespace bluetooth;
using bluetooth::le_audio::GmapCharacteristic;
using bluetooth::le_audio::GmapServer;
using namespace bluetooth::le_audio::uuid;
using bluetooth::stack::tGATT_REQ_CBACK;

bool GmapServer::is_offloader_support_gmap_ = false;
uint16_t GmapServer::server_if_ = 0;
std::unordered_map<uint16_t, GmapCharacteristic> GmapServer::characteristics_ =
        std::unordered_map<uint16_t, GmapCharacteristic>();
// default role is UGG
std::bitset<8> GmapServer::role_ = 0b0001;
// AOSP's LE Audio source support multi-sink on default
std::bitset<8> GmapServer::UGG_feature_ =
        static_cast<uint8_t>(bluetooth::gmap::UGGFeatureBitMask::MultisinkFeatureSupport);

bool GmapServer::IsGmapServerEnabled() {
  bool system_prop = osi_property_get_bool("bluetooth.profile.gmap.enabled", false);
  bool is_gmap_supported_in_software_datapath =
          android::sysprop::bluetooth::LeAudio::is_software_datapath_supported_test().value_or(
                  false);

  bool result =
          system_prop && (is_gmap_supported_in_software_datapath || is_offloader_support_gmap_);
  log::info(
          "GmapServerEnabled={}, system_prop={}, "
          "is_gmap_supported_in_software_datapath={}, "
          "offloader_support={}",
          result, system_prop, is_gmap_supported_in_software_datapath,
          GmapServer::is_offloader_support_gmap_);
  return result;
}

void GmapServer::UpdateGmapOffloaderSupport(bool value) {
  GmapServer::is_offloader_support_gmap_ = value;
}

void GmapServer::DebugDump(int fd) {
  std::stringstream stream;
  stream << "GmapServer is enabled: " << IsGmapServerEnabled() << "\n";
  if (IsGmapServerEnabled()) {
    stream << "GmapServer Role: " << role_ << ", UGG Feature: " << UGG_feature_ << "\n";
  }

  dprintf(fd, "%s", stream.str().c_str());
}

void GmapServer::Initialize(std::bitset<8> role, std::bitset<8> UGG_feature) {
  GmapServer::role_ = role;
  GmapServer::Initialize(UGG_feature);
}

void GmapServer::Initialize(std::bitset<8> UGG_feature) {
  GmapServer::UGG_feature_ = UGG_feature;
  bool is_gmap_supported_in_software_datapath =
          android::sysprop::bluetooth::LeAudio::is_software_datapath_supported_test().value_or(
                  false);
  if (is_gmap_supported_in_software_datapath) {
    // Enable UGG Features for testing only.
    GmapServer::UGG_feature_ |=
            static_cast<uint8_t>(bluetooth::gmap::UGGFeatureBitMask::MultiplexFeatureSupport) |
            static_cast<uint8_t>(
                    bluetooth::gmap::UGGFeatureBitMask::NinetySixKbpsSourceFeatureSupport);
  }
  log::info("GmapServer initialized, role={}, UGG_feature={}", GmapServer::role_.to_string(),
            UGG_feature.to_string());
  characteristics_.clear();

  static bluetooth::stack::tGATT_REQ_CBACK gmap_req_cb = {
          .read_characteristic_cb = GmapServer::OnReadCharacteristic,
          .read_descriptor_cb = tGATT_REQ_CBACK::do_nothing,
          .write_characteristic_cb = tGATT_REQ_CBACK::do_nothing,
          .write_descriptor_cb = tGATT_REQ_CBACK::do_nothing,
          .exec_write_cb = tGATT_REQ_CBACK::do_nothing,
          .mtu_changed_cb = tGATT_REQ_CBACK::do_nothing,
          .conf_cb = tGATT_REQ_CBACK::do_nothing,
  };

  static const stack::tGATT_CBACK gmap_ops = {
          .p_req_cb = &gmap_req_cb,
  };

  server_if_ = BTA_GATTS_AppRegister(kGamingAudioServiceUuid, &gmap_ops, false);
  log::info("server_if: {}", server_if_);

  if (server_if_ == stack::GATT_IF_INVALID) {
    log::warn("Register Server fail");
    return;
  }

  std::vector service = {
          btgatt_db_element_t{.uuid = kGamingAudioServiceUuid, .type = BTGATT_DB_PRIMARY_SERVICE},
          btgatt_db_element_t{
                  .uuid = kRoleCharacteristicUuid,
                  .type = BTGATT_DB_CHARACTERISTIC,
                  .properties = GATT_CHAR_PROP_BIT_READ,
                  .permissions = GATT_PERM_READ,
          },
          btgatt_db_element_t{
                  .uuid = kUnicastGameGatewayCharacteristicUuid,
                  .type = BTGATT_DB_CHARACTERISTIC,
                  .properties = GATT_CHAR_PROP_BIT_READ,
                  .permissions = GATT_PERM_READ,
          }};
  log::info("add service");
  auto status = BTA_GATTS_AddService(server_if_, &service);
  log::info("status: {}, server_if: {}", gatt_status_text(status), server_if_);
  for (const auto& el : service) {
    uint16_t attribute_handle = el.attribute_handle;
    Uuid uuid = el.uuid;
    if (el.type == BTGATT_DB_CHARACTERISTIC) {
      log::info("Characteristic uuid: 0x{:04x}, handle:0x{:04x}", uuid.As16Bit(), attribute_handle);
      GmapCharacteristic characteristic{.uuid_ = uuid, .attribute_handle_ = attribute_handle};
      characteristics_[attribute_handle] = characteristic;
    }
  }
}

std::bitset<8> GmapServer::GetRole() { return GmapServer::role_; }

uint16_t GmapServer::GetRoleHandle() {
  for (auto& [attribute_handle, characteristic] : characteristics_) {
    if (characteristic.uuid_ == kRoleCharacteristicUuid) {
      return attribute_handle;
    }
  }
  log::warn("no valid UGG feature handle");
  return 0;
}

std::bitset<8> GmapServer::GetUGGFeature() { return GmapServer::UGG_feature_; }

uint16_t GmapServer::GetUGGFeatureHandle() {
  for (auto& [attribute_handle, characteristic] : characteristics_) {
    if (characteristic.uuid_ == kUnicastGameGatewayCharacteristicUuid) {
      return attribute_handle;
    }
  }
  log::warn("no valid UGG feature handle");
  return 0;
}

std::unordered_map<uint16_t, GmapCharacteristic>& GmapServer::GetCharacteristics() {
  return GmapServer::characteristics_;
}

void GmapServer::OnReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                      const RawAddress& /*remote_bda*/, uint16_t handle,
                                      uint16_t /*offset*/, bool /*is_long*/) {
  log::info("read_req_handle: 0x{:04x},", handle);

  std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
  p_msg->attr_value.handle = handle;
  auto it = characteristics_.find(handle);
  if (it == characteristics_.end()) {
    log::error("Invalid handle 0x{:04x}", handle);
    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
    return;
  }

  auto uuid = it->second.uuid_;

  log::info("Read uuid, 0x{:04x}", uuid.As16Bit());
  // Check Characteristic UUID
  if (kRoleCharacteristicUuid == uuid) {
    p_msg->attr_value.len = GmapServer::kGmapRoleLen;
    auto role = GmapServer::GetRole();
    p_msg->attr_value.value[0] = static_cast<uint8_t>(role.to_ulong());
  } else if (kUnicastGameGatewayCharacteristicUuid == uuid) {
    p_msg->attr_value.len = GmapServer::kGmapUGGFeatureLen;
    auto UGGFeature = GmapServer::GetUGGFeature();
    p_msg->attr_value.value[0] = static_cast<uint8_t>(UGGFeature.to_ulong());
  } else {
    log::warn("Unhandled uuid {}", uuid.ToString());
    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
    return;
  }

  BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
}
