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

#include "discovery/device/bt_property.h"

#include <base/strings/stringprintf.h>

#include <string>

#include "include/hardware/bluetooth.h"
#include "os/log.h"
#include "stack/include/bt_name.h"
#include "types/bluetooth/uuid.h"

std::shared_ptr<BtProperty> BtPropertyFactory::Build(bt_property_type_t type, BD_NAME name) {
  return BtPropertyVector<uint8_t>::Factory(type, name, kBdNameLength);
}

void BtProperty::Serialize(
    const std::vector<std::shared_ptr<BtProperty>>& bt_properties,
    std::vector<bt_property_t>& properties) {
  properties.resize(bt_properties.size());
  std::vector<bt_property_t>::iterator it = properties.begin();
  for (const auto& p : bt_properties) {
    *it++ = {
        .type = p->Type(),
        .len = (int)p->Size(),
        .val = (void*)p->Val(),
    };
  }
}

void BtProperty::Serialize(
    const std::vector<std::shared_ptr<BtProperty>>& bt_properties, bt_property_t* p, size_t size) {
  bt_property_t* properties = p;
  ASSERT(properties != nullptr);
  ASSERT(size >= bt_properties.size());

  for (const auto& prop : bt_properties) {
    properties->type = prop->Type();
    properties->len = (int)prop->Size();
    properties->val = (void*)prop->Val();
    properties++;
  }
}

std::string bt_property_type_text(const bt_property_type_t& type) {
  switch (type) {
    CASE_RETURN_TEXT(BT_PROPERTY_BDNAME);
    CASE_RETURN_TEXT(BT_PROPERTY_BDADDR);
    CASE_RETURN_TEXT(BT_PROPERTY_UUIDS);
    CASE_RETURN_TEXT(BT_PROPERTY_CLASS_OF_DEVICE);
    CASE_RETURN_TEXT(BT_PROPERTY_TYPE_OF_DEVICE);
    CASE_RETURN_TEXT(BT_PROPERTY_SERVICE_RECORD);
    CASE_RETURN_TEXT(BT_PROPERTY_ADAPTER_SCAN_MODE);
    CASE_RETURN_TEXT(BT_PROPERTY_ADAPTER_BONDED_DEVICES);
    CASE_RETURN_TEXT(BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_FRIENDLY_NAME);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_RSSI);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_VERSION_INFO);
    CASE_RETURN_TEXT(BT_PROPERTY_LOCAL_LE_FEATURES);
    CASE_RETURN_TEXT(BT_PROPERTY_LOCAL_IO_CAPS);
    CASE_RETURN_TEXT(BT_PROPERTY_RESERVED_0F);
    CASE_RETURN_TEXT(BT_PROPERTY_DYNAMIC_AUDIO_BUFFER);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_IS_COORDINATED_SET_MEMBER);
    CASE_RETURN_TEXT(BT_PROPERTY_APPEARANCE);
    CASE_RETURN_TEXT(BT_PROPERTY_VENDOR_PRODUCT_INFO);
    CASE_RETURN_TEXT(BT_PROPERTY_WL_MEDIA_PLAYERS_LIST);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_ASHA_CAPABILITY);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_ASHA_TRUNCATED_HISYNCID);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_MODEL_NUM);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_ADDR_TYPE);
    CASE_RETURN_TEXT(BT_PROPERTY_REMOTE_DEVICE_TIMESTAMP);
  }
  return base::StringPrintf("Unknown [%d]", (int)type);
}

std::string bt_property_text(const bt_property_t& property) {
  switch (property.type) {
    case BT_PROPERTY_BDNAME:
      return base::StringPrintf(
          "type:%s name:%s",
          bt_property_type_text(property.type).c_str(),
          (const char*)property.val);
    case BT_PROPERTY_BDADDR:
      return base::StringPrintf(
          "type:%s addr:%s",
          bt_property_type_text(property.type).c_str(),
          ((const RawAddress*)property.val)->ToString().c_str());
    case BT_PROPERTY_UUIDS: {
      std::ostringstream oss;
      const bluetooth::Uuid* it = (const bluetooth::Uuid*)property.val;
      for (size_t i = 0; i < (size_t)property.len; i += sizeof(bluetooth::Uuid), it++) {
        (i == 0) ? oss << *it : oss << " " << *it;
      }
      return base::StringPrintf(
          "type:%s uuids:%s", bt_property_type_text(property.type).c_str(), oss.str().c_str());
    }
    case BT_PROPERTY_CLASS_OF_DEVICE:
      return base::StringPrintf(
          "type:%s cod:0x%x",
          bt_property_type_text(property.type).c_str(),
          *(uint32_t*)(property.val));

    case BT_PROPERTY_TYPE_OF_DEVICE:
      return base::StringPrintf(
          "type:%s type_of_device:%d",
          bt_property_type_text(property.type).c_str(),
          *(uint32_t*)(property.val));

    case BT_PROPERTY_SERVICE_RECORD:
      return base::StringPrintf(
          "type:%s uuid:%s channel:%u name:\"%s\"",
          bt_property_type_text(property.type).c_str(),
          (((bt_service_record_t*)property.val)->uuid).ToString().c_str(),
          (((bt_service_record_t*)property.val)->channel),
          (((bt_service_record_t*)property.val)->name));

    case BT_PROPERTY_ADAPTER_SCAN_MODE:
      return base::StringPrintf(
          "type:%s scan_mode:%u",
          bt_property_type_text(property.type).c_str(),
          *((bt_scan_mode_t*)property.val));

    case BT_PROPERTY_ADAPTER_BONDED_DEVICES: {
      std::ostringstream oss;
      const RawAddress* it = (const RawAddress*)property.val;
      for (size_t i = 0; i < (size_t)property.len; i += sizeof(RawAddress), it++) {
        (i == 0) ? oss << *it : oss << " " << *it;
      }
      return base::StringPrintf(
          "type:%s addrs:%s", bt_property_type_text(property.type).c_str(), oss.str().c_str());
    }
    case BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT:
      return base::StringPrintf(
          "type:%s discoverable_timeout:%u",
          bt_property_type_text(property.type).c_str(),
          *((uint32_t*)property.val));

    case BT_PROPERTY_REMOTE_FRIENDLY_NAME:
      return base::StringPrintf(
          "type:%s remote_friendly_name:%s",
          bt_property_type_text(property.type).c_str(),
          (uint8_t*)property.val);

    case BT_PROPERTY_REMOTE_RSSI:
      return base::StringPrintf(
          "type:%s rssi:%hhd",
          bt_property_type_text(property.type).c_str(),
          *(int8_t*)property.val);

    case BT_PROPERTY_REMOTE_VERSION_INFO:
      return base::StringPrintf(
          "type:%s version:%d sub:%d mfr:%d",
          bt_property_type_text(property.type).c_str(),
          ((bt_remote_version_t*)property.val)->version,
          ((bt_remote_version_t*)property.val)->sub_ver,
          ((bt_remote_version_t*)property.val)->manufacturer);

    case BT_PROPERTY_LOCAL_LE_FEATURES:
      return base::StringPrintf(
          "type:%s version_supported:%d local_privacy_enabled:%d"
          " max_adv_instance:%d rpa_offload_supported:%d max_irk_list_size:%d"
          " max_adv_filter_supported:%d activity_energy_info_supported:%d"
          " scan_result_storage_size:%d total_trackable_advertisers:%d"
          " extended_scan_support:%d debug_logging_supported:%d le_2m_phy_supported:%d"
          " le_coded_phy_supported:%d le_extended_advertising_supported:%d"
          " le_periodic_advertising_supported:%d le_maximum_advertising_data_length:%d"
          " dynamic_audio_buffer_supported:%d "
          "le_periodic_advertising_sync_transfer_sender_supported:%d"
          " le_connected_isochronous_stream_central_supported:%d "
          "le_isochronous_broadcast_supported:%d"
          " le_periodic_advertising_sync_transfer_recipient_supported:%d "
          "adv_filter_extended_features_mask:%d",
          bt_property_type_text(property.type).c_str(),
          ((bt_local_le_features_t*)property.val)->version_supported,
          ((bt_local_le_features_t*)property.val)->local_privacy_enabled,
          ((bt_local_le_features_t*)property.val)->max_adv_instance,
          ((bt_local_le_features_t*)property.val)->rpa_offload_supported,
          ((bt_local_le_features_t*)property.val)->max_irk_list_size,
          ((bt_local_le_features_t*)property.val)->max_adv_filter_supported,
          ((bt_local_le_features_t*)property.val)->activity_energy_info_supported,
          ((bt_local_le_features_t*)property.val)->scan_result_storage_size,
          ((bt_local_le_features_t*)property.val)->total_trackable_advertisers,
          ((bt_local_le_features_t*)property.val)->extended_scan_support,
          ((bt_local_le_features_t*)property.val)->debug_logging_supported,
          ((bt_local_le_features_t*)property.val)->le_2m_phy_supported,
          ((bt_local_le_features_t*)property.val)->le_coded_phy_supported,
          ((bt_local_le_features_t*)property.val)->le_extended_advertising_supported,
          ((bt_local_le_features_t*)property.val)->le_periodic_advertising_supported,
          ((bt_local_le_features_t*)property.val)->le_maximum_advertising_data_length,
          ((bt_local_le_features_t*)property.val)->dynamic_audio_buffer_supported,
          ((bt_local_le_features_t*)property.val)
              ->le_periodic_advertising_sync_transfer_sender_supported,
          ((bt_local_le_features_t*)property.val)
              ->le_connected_isochronous_stream_central_supported,
          ((bt_local_le_features_t*)property.val)->le_isochronous_broadcast_supported,
          ((bt_local_le_features_t*)property.val)
              ->le_periodic_advertising_sync_transfer_recipient_supported,
          ((bt_local_le_features_t*)property.val)->adv_filter_extended_features_mask);

    case BT_PROPERTY_LOCAL_IO_CAPS:
      return base::StringPrintf(
          "type:%s local_io_caps:%d",
          bt_property_type_text(property.type).c_str(),
          *(bt_io_cap_t*)property.val);

    case BT_PROPERTY_RESERVED_0F:
      return base::StringPrintf("type:%s", bt_property_type_text(property.type).c_str());

    case BT_PROPERTY_DYNAMIC_AUDIO_BUFFER:
      return base::StringPrintf("type:%s", bt_property_type_text(property.type).c_str());

    case BT_PROPERTY_REMOTE_IS_COORDINATED_SET_MEMBER:
      return base::StringPrintf(
          "type:%s is_coordinated_set_member:%s",
          bt_property_type_text(property.type).c_str(),
          (*(bool*)property.val) ? "true" : "false");

    case BT_PROPERTY_APPEARANCE:
      return base::StringPrintf(
          "type:%s appearance:0x%x",
          bt_property_type_text(property.type).c_str(),
          (*(uint16_t*)property.val));

    case BT_PROPERTY_VENDOR_PRODUCT_INFO:
      return base::StringPrintf(
          "type:%s vendor_id_src:%hhu vendor_id:%hu product_id:%hu version:%hu",
          bt_property_type_text(property.type).c_str(),
          ((bt_vendor_product_info_t*)property.val)->vendor_id_src,
          ((bt_vendor_product_info_t*)property.val)->vendor_id,
          ((bt_vendor_product_info_t*)property.val)->product_id,
          ((bt_vendor_product_info_t*)property.val)->version);

    case BT_PROPERTY_WL_MEDIA_PLAYERS_LIST:
      return base::StringPrintf("type:%s", bt_property_type_text(property.type).c_str());

    case BT_PROPERTY_REMOTE_ASHA_CAPABILITY:
      return base::StringPrintf(
          "type:%s remote_asha_capability:%hd",
          bt_property_type_text(property.type).c_str(),
          (*(int16_t*)property.val));

    case BT_PROPERTY_REMOTE_ASHA_TRUNCATED_HISYNCID:
      return base::StringPrintf(
          "type:%s remote_asha_truncated_hisyncid:%u",
          bt_property_type_text(property.type).c_str(),
          (*(uint32_t*)property.val));

    case BT_PROPERTY_REMOTE_MODEL_NUM:
      return base::StringPrintf(
          "type:%s remote_model_num:%s",
          bt_property_type_text(property.type).c_str(),
          (char*)property.val);

    case BT_PROPERTY_REMOTE_ADDR_TYPE:
      return base::StringPrintf(
          "type:%s remote_asha_truncated_hisyncid:0x%x",
          bt_property_type_text(property.type).c_str(),
          (*(uint8_t*)property.val));

    case BT_PROPERTY_REMOTE_DEVICE_TIMESTAMP:
      return base::StringPrintf("type:%s", bt_property_type_text(property.type).c_str());
  }
  return std::string("Unknown");
}
