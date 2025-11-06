/*
 * Copyright (C) 2025 The Android Open Source Project
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

#pragma once

#include <bluetooth/log.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/remote_version.h>

#include <cstdint>
#include <string>

#include "stack/btm/btm_security_record.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/bt_name.h"

typedef struct {
  uint16_t min_conn_int;
  uint16_t max_conn_int;
  uint16_t peripheral_latency;
  uint16_t supervision_tout;
} tBTM_LE_CONN_PRAMS;

/* The MSB of the clock offset field indicates whether the offset is valid. */
#define BTM_CLOCK_OFFSET_VALID 0x8000

// TODO: move it to btm_ble_addr.h
enum tBLE_RAND_ADDR_TYPE : uint8_t {
  BTM_BLE_ADDR_PSEUDO = 0,
  BTM_BLE_ADDR_RRA = 1,
  BTM_BLE_ADDR_STATIC = 2,
};

class tBTM_BLE_ADDR_INFO {
public:
  RawAddress pseudo_addr; /* LE pseudo address of the device if different from device address */
public:
  tBLE_ADDR_TYPE AddressType() const { return ble_addr_type_; }
  void SetAddressType(tBLE_ADDR_TYPE ble_addr_type) {
    if (is_ble_addr_type_known(ble_addr_type)) {
      ble_addr_type_ = ble_addr_type;
    } else {
      bluetooth::log::error("Unknown address type:0x{:x}", ble_addr_type);
    }
  }

  tBLE_BD_ADDR identity_address_with_type;

#define BTM_RESOLVING_LIST_BIT 0x02
  uint8_t in_controller_list; /* in controller resolving list or not */
  uint8_t resolving_list_index;
  RawAddress cur_rand_addr; /* current random address */

  tBLE_RAND_ADDR_TYPE active_addr_type;

private:
  tBLE_ADDR_TYPE ble_addr_type_; /* LE device type: public or random address */
};

class BtmDevice {
public:
  RawAddress RemoteAddress() const { return bd_addr; }

  /* Data length extension */
  void set_suggested_tx_octect(uint16_t octets) { suggested_tx_octets = octets; }

  uint16_t get_suggested_tx_octets() const { return suggested_tx_octets; }
  bool IsLocallyInitiated() const { return outgoing; }

  uint16_t get_br_edr_hci_handle() const { return hci_handle; }
  uint16_t get_ble_hci_handle() const { return ble_hci_handle; }

  bool is_device_type_br_edr() const { return device_type == BT_DEVICE_TYPE_BREDR; }
  bool is_device_type_ble() const { return device_type == BT_DEVICE_TYPE_BLE; }
  bool is_device_type_dual_mode() const { return device_type == BT_DEVICE_TYPE_DUMO; }

  bool is_device_type_has_ble() const { return device_type & BT_DEVICE_TYPE_BLE; }

  bool HostSupportsSecureConnections() const { return remote_host_supports_secure_connections; }
  bool ControllerSupportsSecureConnections() const {
    return remote_controller_supports_secure_connections;
  }

  bool SupportsSecureConnections() const {
    return HostSupportsSecureConnections() && ControllerSupportsSecureConnections();
  }

  bool IsInitialized() const { return !bd_addr.IsEmpty(); }

  std::string ToString() const {
    return std::format(
            "{} {:6s} cod:{} remote_info:{:<14s} sm4:0x{:02x} SecureConn:{:c} "
            "name:\"{}\" sec_prop:{}, in_resolving_list: {}",
            bd_addr, DeviceTypeText(device_type), dev_class_text(dev_class),
            remote_version_info.ToString(), sm4,
            remote_host_supports_secure_connections ? 'T' : 'F',
            reinterpret_cast<char const*>(sec_bd_name), sec_rec.ToString(),
            (ble.in_controller_list & BTM_RESOLVING_LIST_BIT) ? 'T' : 'F');
  }

public:
  RawAddress bd_addr; /* BD_ADDR of the device */
  tBTM_BLE_ADDR_INFO ble;
  BD_NAME sec_bd_name; /* User friendly name of the device. (may be
                               truncated to save space in dev_rec table) */
  DEV_CLASS dev_class; /* DEV_CLASS of the device */
  tBT_DEVICE_TYPE device_type;

  uint32_t timestamp;      /* Timestamp of the last connection */
  uint16_t hci_handle;     /* Handle to BR/EDR ACL connection when exists */
  uint16_t ble_hci_handle; /* use in DUMO connection */

  uint16_t suggested_tx_octets; /* Recently suggested tx octets for data length
                                   extension */
  uint16_t clock_offset;        /* Latest known clock offset          */
  // whether the peer device can read GAP characteristics only visible in
  // "discoverable" mode
  bool can_read_discoverable{true};

  bool remote_features_needed; /* set to true if the local device is in */
  /* "Secure Connections Only" mode and it receives */
  /* HCI_IO_CAPABILITY_REQUEST_EVT from the peer before */
  /* it knows peer's support for Secure Connections */
  uint8_t sm4; /* BTM_SM4_TRUE, if the peer supports SM4 */
  bool remote_supports_hci_role_switch = false;
  bool remote_supports_bredr;
  bool remote_supports_ble;
  bool remote_host_supports_secure_connections;
  bool remote_controller_supports_secure_connections;
  bool remote_feature_received = false;

  tREMOTE_VERSION_INFO remote_version_info;

  bool role_central; /* true if current mode is central (BLE) */
  bool outgoing;     /* true if device is originating ACL connection */
  enum class RoleSwitchPending { kNone = 0, kAfterEnc, kAfterCtkd } role_switch_pending;

  // BLE connection parameters
  tBTM_LE_CONN_PRAMS conn_params;
  // security related properties
  BtmSecurityRecord sec_rec;

  bool bond_lost;  /* indicates if the bond is lost */
};

namespace std {
template <>
struct formatter<tBLE_RAND_ADDR_TYPE> : enum_formatter<tBLE_RAND_ADDR_TYPE> {};
}  // namespace std
