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
#include "gatt_api.h"
#include "hardware/bt_common_types.h"
#include "include/hardware/bt_gmap.h"
#include "osi/include/properties.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"

using bluetooth::Uuid;
using namespace bluetooth;
using bluetooth::le_audio::GmapCharacteristic;
using bluetooth::le_audio::GmapServer;

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
  // for UGG, both GMAP Server and Client are needed. So server and client share the same flag.
  bool flag = com::android::bluetooth::flags::leaudio_gmap_client();
  bool system_prop = osi_property_get_bool("bluetooth.profile.gmap.enabled", false);
  bool is_gmap_supported_in_software_datapath =
          android::sysprop::bluetooth::LeAudio::is_gmap_supported_in_software_datapath().value_or(
                  false);

  bool result = flag && system_prop &&
                (is_gmap_supported_in_software_datapath || is_offloader_support_gmap_);
  log::info(
          "GmapServerEnabled={}, flag={}, system_prop={}, "
          "is_gmap_supported_in_software_datapath={}, "
          "offloader_support={}",
          result, flag, system_prop, is_gmap_supported_in_software_datapath,
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
          android::sysprop::bluetooth::LeAudio::is_gmap_supported_in_software_datapath().value_or(
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

  BTA_GATTS_AppRegister(
          bluetooth::le_audio::uuid::kGamingAudioServiceUuid,
          [](tBTA_GATTS_EVT event, tBTA_GATTS *p_data) {
            if (p_data) {
              GmapServer::GattsCallback(event, p_data);
            }
          },
          false);
}

std::bitset<8> GmapServer::GetRole() { return GmapServer::role_; }

uint16_t GmapServer::GetRoleHandle() {
  for (auto &[attribute_handle, characteristic] : characteristics_) {
    if (characteristic.uuid_ == bluetooth::le_audio::uuid::kRoleCharacteristicUuid) {
      return attribute_handle;
    }
  }
  log::warn("no valid UGG feature handle");
  return 0;
}

std::bitset<8> GmapServer::GetUGGFeature() { return GmapServer::UGG_feature_; }

uint16_t GmapServer::GetUGGFeatureHandle() {
  for (auto &[attribute_handle, characteristic] : characteristics_) {
    if (characteristic.uuid_ == bluetooth::le_audio::uuid::kUnicastGameGatewayCharacteristicUuid) {
      return attribute_handle;
    }
  }
  log::warn("no valid UGG feature handle");
  return 0;
}

std::unordered_map<uint16_t, GmapCharacteristic> &GmapServer::GetCharacteristics() {
  return GmapServer::characteristics_;
}

void GmapServer::GattsCallback(tBTA_GATTS_EVT event, tBTA_GATTS *p_data) {
  log::info("event: {}", gatt_server_event_text(event));
  switch (event) {
    case BTA_GATTS_CONNECT_EVT: {
      OnGattConnect(p_data);
      break;
    }
    case BTA_GATTS_DEREG_EVT: {
      BTA_GATTS_AppDeregister(server_if_);
      break;
    }
    case BTA_GATTS_DISCONNECT_EVT: {
      OnGattDisconnect(p_data);
      break;
    }
    case BTA_GATTS_REG_EVT: {
      OnGattServerRegister(p_data);
      break;
    }
    case BTA_GATTS_READ_CHARACTERISTIC_EVT: {
      OnReadCharacteristic(p_data);
      break;
    }
    default:
      log::warn("Unhandled event {}", gatt_server_event_text(event));
  }
}

void GmapServer::OnGattConnect(tBTA_GATTS *p_data) {
  if (p_data == nullptr) {
    log::warn("invalid p_data");
  }
  auto address = p_data->conn.remote_bda;
  log::info("Address: {}, conn_id:{}", address, p_data->conn.conn_id);
  if (p_data->conn.transport == BT_TRANSPORT_BR_EDR) {
    log::warn("Skip BE/EDR connection");
    return;
  }
}

void GmapServer::OnGattDisconnect(tBTA_GATTS *p_data) {
  if (p_data == nullptr) {
    log::warn("invalid p_data");
  }
  auto address = p_data->conn.remote_bda;
  log::info("Address: {}, conn_id:{}", address, p_data->conn.conn_id);
}

void GmapServer::OnGattServerRegister(tBTA_GATTS *p_data) {
  if (p_data == nullptr) {
    log::warn("invalid p_data");
  }
  tGATT_STATUS status = p_data->reg_oper.status;
  log::info("status: {}", gatt_status_text(p_data->reg_oper.status));

  if (status != tGATT_STATUS::GATT_SUCCESS) {
    log::warn("Register Server fail");
    return;
  }
  server_if_ = p_data->reg_oper.server_if;

  std::vector<btgatt_db_element_t> service;

  // GMAP service
  btgatt_db_element_t gmap_service;
  gmap_service.uuid = bluetooth::le_audio::uuid::kGamingAudioServiceUuid;
  gmap_service.type = BTGATT_DB_PRIMARY_SERVICE;
  service.push_back(gmap_service);

  // GMAP role
  btgatt_db_element_t role_characteristic;
  role_characteristic.uuid = bluetooth::le_audio::uuid::kRoleCharacteristicUuid;
  role_characteristic.type = BTGATT_DB_CHARACTERISTIC;
  role_characteristic.properties = GATT_CHAR_PROP_BIT_READ;
  role_characteristic.permissions = GATT_PERM_READ;
  service.push_back(role_characteristic);

  // GMAP UGG feature
  btgatt_db_element_t UGG_feature_characteristic;
  UGG_feature_characteristic.uuid =
          bluetooth::le_audio::uuid::kUnicastGameGatewayCharacteristicUuid;
  UGG_feature_characteristic.type = BTGATT_DB_CHARACTERISTIC;
  UGG_feature_characteristic.properties = GATT_CHAR_PROP_BIT_READ;
  UGG_feature_characteristic.permissions = GATT_PERM_READ;
  service.push_back(UGG_feature_characteristic);

  log::info("add service");
  BTA_GATTS_AddService(server_if_, service,
                       base::BindRepeating([](tGATT_STATUS status, int server_if,
                                              std::vector<btgatt_db_element_t> service) {
                         OnServiceAdded(status, server_if, service);
                       }));
}

void GmapServer::OnServiceAdded(tGATT_STATUS status, int server_if,
                                std::vector<btgatt_db_element_t> services) {
  log::info("status: {}, server_if: {}", gatt_status_text(status), server_if);
  for (const auto &service : services) {
    uint16_t attribute_handle = service.attribute_handle;
    Uuid uuid = service.uuid;
    if (service.type == BTGATT_DB_CHARACTERISTIC) {
      log::info("Characteristic uuid: 0x{:04x}, handle:0x{:04x}", uuid.As16Bit(), attribute_handle);
      GmapCharacteristic characteristic{.uuid_ = uuid, .attribute_handle_ = attribute_handle};
      characteristics_[attribute_handle] = characteristic;
    }
  }
}

void GmapServer::OnReadCharacteristic(tBTA_GATTS *p_data) {
  uint16_t read_req_handle = p_data->req_data.p_data->read_req.handle;
  log::info("read_req_handle: 0x{:04x},", read_req_handle);

  tGATTS_RSP p_msg;
  p_msg.attr_value.handle = read_req_handle;
  auto it = characteristics_.find(read_req_handle);
  if (it == characteristics_.end()) {
    log::error("Invalid handle 0x{:04x}", read_req_handle);
    BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id, GATT_INVALID_HANDLE,
                      &p_msg);
    return;
  }

  auto uuid = it->second.uuid_;

  log::info("Read uuid, 0x{:04x}", uuid.As16Bit());
  // Check Characteristic UUID
  if (bluetooth::le_audio::uuid::kRoleCharacteristicUuid == uuid) {
    p_msg.attr_value.len = GmapServer::kGmapRoleLen;
    auto role = GmapServer::GetRole();
    p_msg.attr_value.value[0] = static_cast<uint8_t>(role.to_ulong());
  } else if (bluetooth::le_audio::uuid::kUnicastGameGatewayCharacteristicUuid == uuid) {
    p_msg.attr_value.len = GmapServer::kGmapUGGFeatureLen;
    auto UGGFeature = GmapServer::GetUGGFeature();
    p_msg.attr_value.value[0] = static_cast<uint8_t>(UGGFeature.to_ulong());
  } else {
    log::warn("Unhandled uuid {}", uuid.ToString());
    BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id, GATT_ILLEGAL_PARAMETER,
                      &p_msg);
    return;
  }

  BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
}
