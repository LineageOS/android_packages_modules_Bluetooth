/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
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

/******************************************************************************
 *
 *  This file contains functions for the Bluetooth Device Manager
 *
 ******************************************************************************/

#define LOG_TAG "btm_dev"

#include "stack/btm/btm_dev.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <com_android_bluetooth_flags.h>

#include <string>

#include "btif/include/btif_config.h"
#include "btif/include/btif_storage.h"
#include "btm_sec_api.h"
#include "btm_security.h"
#include "connection_manager/connection_manager.h"
#include "internal_include/bt_target.h"
#include "main/shim/dumpsys.h"
#include "osi/include/allocator.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/acl_api.h"
#include "stack/include/btm_ble_privacy.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/gatt_api.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;

namespace {

constexpr char kBtmLogTag[] = "BOND";

}

static void wipe_secrets_and_remove(BtmDevice* p_device) {
  if (!is_main_thread()) {
    log::error("From non-main thread");
  }
  p_device->sec_rec.link_key.fill(0);
  memset(&p_device->sec_rec.ble_keys, 0, sizeof(tBTM_SEC_BLE_KEYS));
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    list_remove(BtmSecurity::Get().sec_dev_rec_, p_device);
  } else {
    // As p_device is a pointer to element of BtmSecurity::Get().device_records_, we don't
    // need to process the complete array to find and remove. This is safe.
    if (p_device != nullptr) {
      *p_device = {};
    }
  }
}

static inline void validate_bredr_pairing_type(const RawAddress& bd_addr,
                                               const PairingType& pairing_type) {
  switch (pairing_type.algorithm) {
    case PairingAlgorithm::NONE:
      log::error("{} pairing algorithm is NONE", bd_addr);
      return;
    case PairingAlgorithm::BREDR_LEGACY:
      if (pairing_type.legacy_variant != LegacyPairingVariant::PIN &&
          pairing_type.legacy_variant != LegacyPairingVariant::PIN_16) {
        log::error("{} invalid legacy pairing variant {}", bd_addr, pairing_type.legacy_variant);
        return;
      }
      break;
    case PairingAlgorithm::SSP:
    case PairingAlgorithm::SC:
      if (pairing_type.variant != PairingVariant::CONSENT &&
          pairing_type.variant != PairingVariant::PASSKEY_ENTRY &&
          pairing_type.variant != PairingVariant::PASSKEY_NOTIFICATION &&
          pairing_type.variant != PairingVariant::PASSKEY_CONFIRMATION) {
        log::error("{} invalid SSP pairing variant {}", bd_addr, pairing_type.variant);
        return;
      }
      break;
    default:
      log::error("{} unknown pairing algorithm {}", bd_addr, pairing_type.algorithm);
      return;
  }

  log::info("{} pairing type: {}", bd_addr, pairing_type);
}

/*******************************************************************************
 *
 * Function         btm_sec_add_device
 *
 * Description      Add/modify device.  This function will be normally called
 *                  during host startup to restore all required information
 *                  stored in the NVRAM.
 *
 * Parameters:      bd_addr          - BD address of the peer
 *                  dev_class        - Device Class
 *                  link_key         - Connection link key. NULL if unknown.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_add_device(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                        const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                        uint8_t pin_length) {
  BtmDevice* p_device = btm_get_dev(bd_addr);

  if (p_device == nullptr) {
    p_device = btm_sec_allocate_dev_rec(bd_addr);

    if (p_device == nullptr) {
      log::warn("device record allocation failed bd_addr:{}", bd_addr);
      return;
    }

    log::info(
            "Caching new record from config file device: {}, dev_class: {:02x}:{:02x}:{:02x}, "
            "link_key_type: 0x{:x}",
            bd_addr, dev_class[0], dev_class[1], dev_class[2], key_type);

    p_device->hci_handle =
            get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_BR_EDR);

    /* use default value for background connection params */
    /* update conn params, use default value for background connection params */
    memset(&p_device->conn_params, 0xff, sizeof(tBTM_LE_CONN_PRAMS));

    if (btif_storage_get_stored_remote_name(bd_addr,
                                            reinterpret_cast<char*>(&p_device->sec_bd_name))) {
      p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
    }
  } else {
    log::info(
            "Caching existing record from config file device: {},"
            " dev_class: {:02x}:{:02x}:{:02x}, link_key_type: 0x{:x}",
            bd_addr, dev_class[0], dev_class[1], dev_class[2], key_type);

    /* "Bump" timestamp for existing record */
    p_device->timestamp = BtmSecurity::Get().dev_rec_count_++;
  }

  if (dev_class != kDevClassEmpty) {
    p_device->dev_class = dev_class;
  }

  validate_bredr_pairing_type(bd_addr, pairing_type);

  p_device->sec_rec.sec_flags |= BTM_SEC_LINK_KEY_KNOWN;
  p_device->sec_rec.link_key = link_key;
  p_device->sec_rec.link_key_type = key_type;
  p_device->sec_rec.pin_code_length = pin_length;
  p_device->sec_rec.pairing_algorithm = pairing_type.algorithm;

  p_device->sec_rec.bond_type = BOND_TYPE_PERSISTENT;
  p_device->clock_offset = BTM_GetCachedClockOffset(bd_addr);

  if (pin_length >= 16 || key_type == BTM_LKEY_TYPE_AUTH_COMB ||
      key_type == BTM_LKEY_TYPE_AUTH_COMB_P_256) {
    // Set the flag if the link key was made by using either a 16 digit
    // pin or MITM.
    p_device->sec_rec.sec_flags |= BTM_SEC_16_DIGIT_PIN_AUTHED | BTM_SEC_LINK_KEY_AUTHED;
  }

  p_device->sec_rec.rmt_io_caps = BtIoCap::DISPLAY_ONLY;
  p_device->device_type |= BT_DEVICE_TYPE_BREDR;
}

uint16_t BTM_GetCachedClockOffset(const RawAddress& bd_addr) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);
  if (p_device != nullptr && (p_device->clock_offset & BTM_CLOCK_OFFSET_VALID) != 0) {
    return p_device->clock_offset;
  }

  tBTM_INQ_INFO* inq = BTM_InqDbRead(bd_addr);
  if (inq != nullptr && (inq->results.clock_offset & BTM_CLOCK_OFFSET_VALID) != 0) {
    return inq->results.clock_offset;
  }

  int clock_offset = 0;
  btif_get_device_clockoffset(bd_addr, &clock_offset);
  return (clock_offset & BTM_CLOCK_OFFSET_VALID) ? static_cast<uint16_t>(clock_offset) : 0;
}

/** Free resources associated with the device associated with |bd_addr| address.
 *
 * *** WARNING ***
 * BtmDevice associated with bd_addr becomes invalid after this function
 * is called, also any of its fields. i.e. if you use p_device->bd_addr, it is
 * no longer valid!
 * *** WARNING ***
 *
 * Returns true if removed successfully, false if not found.
 */
bool btm_sec_delete_device(const RawAddress& bd_addr) {
  if (com_android_bluetooth_flags_btm_disconnect_on_remove()) {
    // BTA may not know about the connection if BTM is still reading remote features and version.
    // If so, just disconnect the link here.
    uint16_t handle = BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);
    if (handle != HCI_INVALID_HANDLE) {
      log::warn("Disconnecting unreported LE connection {}", bd_addr);
      acl_disconnect_after_role_switch(handle, HCI_SUCCESS, "btm_sec_delete_device");
    }
    handle = BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_BR_EDR);
    if (handle != HCI_INVALID_HANDLE) {
      log::warn("Disconnecting unreported BR/EDR connection {}", bd_addr);
      acl_disconnect_after_role_switch(handle, HCI_SUCCESS, "btm_sec_delete_device");
    }
  }

  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    log::warn("Unknown device {}", bd_addr);
    return false;
  }

  /* Invalidate bonded status */
  p_device->sec_rec.sec_flags &= ~BTM_SEC_LINK_KEY_KNOWN;
  p_device->sec_rec.sec_flags &= ~BTM_SEC_LE_LINK_KEY_KNOWN;

  if (!com_android_bluetooth_flags_btm_disconnect_on_remove()) {
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE) ||
        get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_BR_EDR)) {
      log::warn("FAILED: Cannot Delete when connection to {} is active", bd_addr);
      return false;
    }
  }

  log::info("Remove device {} from filter accept list before delete record", bd_addr);
  connection_manager::remove_unconditional(bd_addr);

  /* Tell controller to get rid of the link key, if it has one stored */
  get_security_client_interface().BTM_SecHciDeleteStoredLinkKey(p_device->bd_addr);
  BTM_LogHistory(kBtmLogTag, bd_addr, "Device removed",
                 std::format("device_type:{} bond_type:{}", DeviceTypeText(p_device->device_type),
                             bond_type_text(p_device->sec_rec.bond_type)));

  /* Clear out any saved BLE keys */
  btm_sec_clear_ble_keys(p_device);
  wipe_secrets_and_remove(p_device);

  return true;
}

/*******************************************************************************
 *
 * Function         btm_sec_clear_security_flags
 *
 * Description      Reset the security flags (mark as not-paired) for a given
 *                  remove device.
 *
 ******************************************************************************/
void btm_sec_clear_security_flags(const RawAddress& bd_addr) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    log::warn("Unable to clear security flags for unknown device {}", bd_addr);
    return;
  }

  p_device->sec_rec.sec_flags = 0;
  p_device->sec_rec.le_link = tSECURITY_STATE::IDLE;
  p_device->sec_rec.classic_link = tSECURITY_STATE::IDLE;
  p_device->sm4 = BTM_SM4_UNKNOWN;
}

/*******************************************************************************
 *
 * Function         btm_sec_read_dev_name
 *
 * Description      Looks for the device name in the security database for the
 *                  specified BD address.
 *
 * Returns          Pointer to the name or NULL
 *
 ******************************************************************************/
const char* btm_sec_read_dev_name(const RawAddress& bd_addr) {
  const char* p_name = NULL;
  const BtmDevice* p_srec;

  p_srec = btm_find_dev(bd_addr);
  if (p_srec != NULL) {
    p_name = (const char*)p_srec->sec_bd_name;
  }

  return p_name;
}

/*******************************************************************************
 *
 * Function         btm_sec_read_dev_class
 *
 * Description      Looks for the class of device in the security database for
 *                  the specified BD address.
 *
 * Returns          Class of device or kDevClassEmpty
 *
 ******************************************************************************/
DEV_CLASS btm_sec_read_dev_class(const RawAddress& bd_addr) {
  const BtmDevice* p_srec = btm_find_dev(bd_addr);
  if (p_srec != nullptr) {
    return p_srec->dev_class;
  }

  return kDevClassEmpty;
}

/*******************************************************************************
 *
 * Function         btm_sec_alloc_dev
 *
 * Description      Allocate a security device record with specified address,
 *                  fill device type and device class from inquiry database or
 *                  btm_sec_cb (if the address is the connecting device)
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_sec_alloc_dev(const RawAddress& bd_addr) {
  tBTM_INQ_INFO* p_inq_info;

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);

  if (p_device == nullptr) {
    log::warn("device record allocation failed bd_addr:{}", bd_addr);
    return nullptr;
  }

  log::debug("Allocated device record bd_addr:{}", bd_addr);

  /* Check with the BT manager if details about remote device are known */
  /* outgoing connection */
  p_inq_info = BTM_InqDbRead(bd_addr);
  if (p_inq_info != nullptr) {
    p_device->dev_class = p_inq_info->results.dev_class;

    p_device->device_type = p_inq_info->results.device_type;
    if (is_ble_addr_type_known(p_inq_info->results.ble_addr_type)) {
      p_device->ble.SetAddressType(p_inq_info->results.ble_addr_type);
    } else {
      log::warn("Please do not update device record from anonymous le advertisement");
    }

  } else if (bd_addr == BtmSecurity::Get().connecting_bda_) {
    p_device->dev_class = BtmSecurity::Get().connecting_dc_;
  }

  /* update conn params, use default value for background connection params */
  memset(&p_device->conn_params, 0xff, sizeof(tBTM_LE_CONN_PRAMS));

  p_device->ble_hci_handle =
          get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);
  p_device->hci_handle =
          get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_BR_EDR);

  p_device->clock_offset = BTM_GetCachedClockOffset(bd_addr);

  return p_device;
}

static bool is_handle_equal(void* data, void* context) {
  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  uint16_t* handle = static_cast<uint16_t*>(context);

  if (p_device->hci_handle == *handle || p_device->ble_hci_handle == *handle) {
    return false;
  }

  return true;
}

/*******************************************************************************
 *
 * Function         btm_find_dev_by_handle
 *
 * Description      Look for the record in the device database for the record
 *                  with specified handle
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
static BtmDevice* btm_find_dev_by_handle_(uint16_t handle) {
  if (handle == HCI_INVALID_HANDLE) {
    return nullptr;
  }

  if (com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    // Get the security device record with matching handle, and return directly.
    if (!BtmSecurity::Get().IsSecCBInitialized()) {
      return nullptr;
    }

    return BtmSecurity::Get().for_each_dev_rec(is_handle_equal, &handle);
  }

  if (BtmSecurity::Get().sec_dev_rec_ == nullptr) {
    return nullptr;
  }

  list_node_t* n = list_foreach(BtmSecurity::Get().sec_dev_rec_, is_handle_equal, &handle);
  if (n) {
    return static_cast<BtmDevice*>(list_node(n));
  }

  return nullptr;
}

const BtmDevice* btm_find_dev_by_handle(uint16_t handle) { return btm_find_dev_by_handle_(handle); }

BtmDevice* btm_get_dev_by_handle(uint16_t handle) {
  if (!com_android_bluetooth_flags_fix_sec_dev_rec_access()) {
    return btm_find_dev_by_handle_(handle);  // non-const return
  }

  return get_main_thread()->DoInThreadSynchronously(&btm_find_dev_by_handle_, handle);
}

static bool is_not_same_identity_or_pseudo_address(void* data, void* context) {
  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  const RawAddress* bd_addr = ((RawAddress*)context);

  if (p_device->bd_addr == *bd_addr) {
    return false;
  }
  // If a LE random address is looking for device record
  if (p_device->ble.pseudo_addr == *bd_addr) {
    return false;
  }

  return true;
}

static bool is_rpa_unresolvable(void* data, void* context) {
  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  const RawAddress* bd_addr = ((RawAddress*)context);

  if (btm_ble_addr_resolvable(*bd_addr, p_device)) {
    return false;
  }
  return true;
}
/*******************************************************************************
 *
 * Function         btm_find_dev
 *
 * Description      Look for the record in the device database for the record
 *                  with specified BD address
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
// TODO(b/444620685): Remove when use_array_instead_list_in_sec_dev_rec is shipped.
static BtmDevice* find_dev_from_list(const RawAddress& bd_addr) {
  if (BtmSecurity::Get().sec_dev_rec_ == nullptr) {
    return nullptr;
  }

  // Find by matching identity address or pseudo address.
  list_node_t* n = list_foreach(BtmSecurity::Get().sec_dev_rec_,
                                is_not_same_identity_or_pseudo_address, (void*)&bd_addr);
  if (n != nullptr) {
    return static_cast<BtmDevice*>(list_node(n));
  }

  // If not found by matching identity address or pseudo address, find by RPA
  n = list_foreach(BtmSecurity::Get().sec_dev_rec_, is_rpa_unresolvable, (void*)&bd_addr);
  if (n != nullptr) {
    BtmDevice* p_device = static_cast<BtmDevice*>(list_node(n));
    log::warn("Found via address resolution bd_addr:{}, pseudo_addr:{}, identity_addr:{}", bd_addr,
              p_device->ble.pseudo_addr, p_device->bd_addr);
    return p_device;
  }

  return nullptr;
}

static BtmDevice* find_dev(const RawAddress& bd_addr) {
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    return find_dev_from_list(bd_addr);  // finds device from sec_dev_rec list
  }

  if (!BtmSecurity::Get().IsSecCBInitialized()) {
    return nullptr;
  }

  BtmDevice* p_device = BtmSecurity::Get().for_each_dev_rec(is_not_same_identity_or_pseudo_address,
                                                            (void*)&bd_addr);
  if (p_device == nullptr) {
    // If not found by matching identity address or pseudo address, find by RPA.
    p_device = BtmSecurity::Get().for_each_dev_rec(is_rpa_unresolvable, (void*)&bd_addr);
    if (p_device != nullptr) {
      log::warn("Found via address resolution bd_addr:{}, pseudo_addr:{}, identity_addr:{}",
                bd_addr, p_device->ble.pseudo_addr, p_device->bd_addr);
    }
  }

  return p_device;
}

const BtmDevice* btm_find_dev(const RawAddress& bd_addr) { return find_dev(bd_addr); }

BtmDevice* btm_get_dev(const RawAddress& bd_addr) {
  if (!com_android_bluetooth_flags_fix_sec_dev_rec_access()) {
    return find_dev(bd_addr);
  }

  return get_main_thread()->DoInThreadSynchronously(&find_dev, bd_addr);
}

static bool has_lenc_and_address_is_equal(void* data, void* context) {
  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  if (!(p_device->sec_rec.ble_keys.key_type & BTM_LE_KEY_LENC)) {
    return true;
  }

  return is_not_same_identity_or_pseudo_address(data, context);
}

/*******************************************************************************
 *
 * Function         btm_find_dev_with_lenc
 *
 * Description      Look for the record in the device database with LTK and
 *                  specified BD address
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
static BtmDevice* find_dev_with_lenc(const RawAddress& bd_addr) {
  if (com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    if (!BtmSecurity::Get().IsSecCBInitialized()) {
      return nullptr;
    }

    return BtmSecurity::Get().for_each_dev_rec(has_lenc_and_address_is_equal, (void*)&bd_addr);
  }

  if (BtmSecurity::Get().sec_dev_rec_ == nullptr) {
    return nullptr;
  }

  list_node_t* n = list_foreach(BtmSecurity::Get().sec_dev_rec_, has_lenc_and_address_is_equal,
                                (void*)&bd_addr);
  if (n) {
    return static_cast<BtmDevice*>(list_node(n));
  }

  return NULL;
}

const BtmDevice* btm_find_dev_with_lenc(const RawAddress& bd_addr) {
  return find_dev_with_lenc(bd_addr);
}

BtmDevice* btm_get_dev_with_lenc(const RawAddress& bd_addr) {
  if (!com_android_bluetooth_flags_fix_sec_dev_rec_access()) {
    return find_dev_with_lenc(bd_addr);
  }

  return get_main_thread()->DoInThreadSynchronously(&find_dev_with_lenc, bd_addr);
}

/*******************************************************************************
 *
 * Function         btm_consolidate_dev
 *
 * Description      combine security records if identified as same peer
 *
 * Returns          none
 *
 ******************************************************************************/
// TODO(b/444620685): Remove when use_array_instead_list_in_sec_dev_rec is shipped.
static void consolidate_dev(BtmDevice* p_target, BtmDevice* p_device) {
  BtmDevice temp_dev = *p_target;

  if (p_device->bd_addr != p_target->bd_addr) {
    /* an RPA device entry is a duplicate of the target record */
    if (btm_ble_addr_resolvable(p_device->bd_addr, p_target)) {
      if (p_target->ble.pseudo_addr == p_device->bd_addr) {
        log::warn("Having a duplicate RPA device entry for {}", p_device->bd_addr);
        p_target->ble.SetAddressType(p_device->ble.AddressType());
        p_target->device_type |= p_device->device_type;

        /* remove the combined record */
        wipe_secrets_and_remove(p_device);
      }
    }
    return;
  }

  log::info("Consolidating records for {}", p_device->bd_addr);

  *p_target = *p_device;
  p_target->ble = temp_dev.ble;
  p_target->sec_rec.ble_keys = temp_dev.sec_rec.ble_keys;
  p_target->ble_hci_handle = temp_dev.ble_hci_handle;
  p_target->sec_rec.enc_key_size = temp_dev.sec_rec.enc_key_size;
  p_target->sec_rec.le_enc_key_size = temp_dev.sec_rec.le_enc_key_size;
  p_target->conn_params = temp_dev.conn_params;
  p_target->device_type |= temp_dev.device_type;
  p_target->sec_rec.sec_flags |= temp_dev.sec_rec.sec_flags;

  p_target->sec_rec.bredr_sc_enc_reason = temp_dev.sec_rec.bredr_sc_enc_reason;
  p_target->sec_rec.bond_type = temp_dev.sec_rec.bond_type;

  /* remove the combined record */
  wipe_secrets_and_remove(p_device);
  // p_device gets freed in list_remove, we should not  access it further
}

void btm_consolidate_dev(BtmDevice* p_target) {
  if (com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    for (BtmDevice& device : BtmSecurity::Get().device_records_) {
      if (device.IsInitialized() && (p_target != &device)) {
        consolidate_dev(p_target, &device);
      }
    }

    return;
  }

  list_node_t* end = list_end(BtmSecurity::Get().sec_dev_rec_);
  list_node_t* node = list_begin(BtmSecurity::Get().sec_dev_rec_);
  while (node != end) {
    BtmDevice* p_device = static_cast<BtmDevice*>(list_node(node));

    // we do list_remove in some cases, must grab next before removing
    node = list_next(node);
    if (p_target != p_device) {
      consolidate_dev(p_target, p_device);
    }
  }
}

static BTM_CONSOLIDATION_CB* btm_consolidate_cb = nullptr;

void BTM_SetConsolidationCallback(BTM_CONSOLIDATION_CB* cb) { btm_consolidate_cb = cb; }

// TODO(b/444620685): Remove when use_array_instead_list_in_sec_dev_rec is shipped.
static void consolidate_existing_dev(BtmDevice* p_target, BtmDevice* p_device,
                                     const RawAddress& bd_addr) {
  if (p_target == p_device) {
    return;
  }

  /* an RPA device entry is a duplicate of the target record */
  if (btm_ble_addr_resolvable(p_device->bd_addr, p_target)) {
    if (p_device->ble_hci_handle == HCI_INVALID_HANDLE) {
      log::info("already disconnected - erasing entry {}", p_device->bd_addr);
      wipe_secrets_and_remove(p_device);
      return;
    }

    log::info(
            "Found existing LE connection to just bonded device on {} handle "
            "0x{:04x}",
            p_device->bd_addr, p_device->ble_hci_handle);

    RawAddress ble_conn_addr = p_device->bd_addr;
    p_target->ble_hci_handle = p_device->ble_hci_handle;

    /* remove the old LE record */
    wipe_secrets_and_remove(p_device);

    btm_acl_consolidate(bd_addr, ble_conn_addr);
    stack::l2cap::get_interface().L2CA_Consolidate(bd_addr, ble_conn_addr);
    gatt_consolidate(bd_addr, ble_conn_addr);
    if (btm_consolidate_cb) {
      btm_consolidate_cb(bd_addr, ble_conn_addr);
    }

    /* To avoid race conditions between central/peripheral starting encryption
     * at same time, initiate it just from central. */
    if (stack::l2cap::get_interface().L2CA_GetBleConnRole(ble_conn_addr) == HCI_ROLE_CENTRAL) {
      log::info("Will encrypt existing connection");
      btm_set_encryption(bd_addr, BT_TRANSPORT_LE, nullptr, nullptr, BTM_BLE_SEC_ENCRYPT);
    }
  }
}

/* combine security records of established LE connections after Classic pairing
 * succeeded. */
void btm_dev_consolidate_existing_connections(const RawAddress& bd_addr) {
  BtmDevice* p_target = btm_get_dev(bd_addr);
  if (!p_target) {
    log::error("No security record for just bonded device!?!?");
    return;
  }

  if (p_target->ble_hci_handle != HCI_INVALID_HANDLE) {
    log::info("Not consolidating - already have LE connection");
    return;
  }

  log::info("{}", bd_addr);

  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    list_node_t* end = list_end(BtmSecurity::Get().sec_dev_rec_);
    list_node_t* node = list_begin(BtmSecurity::Get().sec_dev_rec_);
    while (node != end) {
      BtmDevice* p_device = static_cast<BtmDevice*>(list_node(node));

      // we do list_remove in some cases, must grab next before removing
      node = list_next(node);
      consolidate_existing_dev(p_target, p_device, bd_addr);
    }
    return;
  }

  for (BtmDevice& device : BtmSecurity::Get().device_records_) {
    if (device.IsInitialized()) {
      consolidate_existing_dev(p_target, &device, bd_addr);
    }
  }
}

/*******************************************************************************
 *
 * Function         btm_find_or_alloc_dev
 *
 * Description      Look for the record in the device database for the record
 *                  with specified BD address, if not found, allocate a new
 *                  record
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_find_or_alloc_dev(const RawAddress& bd_addr) {
  BtmDevice* p_device;
  p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    /* Allocate a new device record or reuse the oldest one */
    p_device = btm_sec_alloc_dev(bd_addr);
  }
  return p_device;
}

/*******************************************************************************
 *
 * Function         btm_find_oldest_dev_rec
 *
 * Description      Locates the oldest device record suitable for removal. It first looks for
 *                  the oldest non-bonded and non-connected device. If all devices are bonded, it
 *                  lookes for the oldest connected device. Else, it returns the oldest bonded
 *                  device.
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
static BtmDevice* btm_find_oldest_dev_rec(void) {
  BtmDevice* oldest = nullptr;            // Oldest non-bonded, non-connected device
  BtmDevice* oldest_connected = nullptr;  // Oldest non-bonded, connected device
  BtmDevice* oldest_bonded = nullptr;     // Oldest bonded device

  auto process_record = [&](BtmDevice* p_device) {
    if (p_device->sec_rec.is_bonded()) {  // Device is bonded
      if (oldest_bonded == nullptr || p_device->timestamp < oldest_bonded->timestamp) {
        oldest_bonded = p_device;
      }
    } else if (p_device->get_br_edr_hci_handle() != HCI_INVALID_HANDLE ||
               p_device->get_ble_hci_handle() != HCI_INVALID_HANDLE) {  // Device is connected
      if (oldest_connected == nullptr || p_device->timestamp < oldest_connected->timestamp) {
        oldest_connected = p_device;
      }
    } else {  // Device is neither bonded nor connected
      if (oldest == nullptr || p_device->timestamp < oldest->timestamp) {
        oldest = p_device;
      }
    }
  };

  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    list_node_t* end = list_end(BtmSecurity::Get().sec_dev_rec_);
    for (list_node_t* node = list_begin(BtmSecurity::Get().sec_dev_rec_); node != end;
         node = list_next(node)) {
      process_record(static_cast<BtmDevice*>(list_node(node)));
    }
  } else {
    for (BtmDevice& device : BtmSecurity::Get().device_records_) {
      if (device.IsInitialized()) {
        process_record(&device);
      }
    }
  }

  if (oldest != nullptr) {
    return oldest;
  }

  if (oldest_connected != nullptr) {
    log::warn("No non-connected device found: {}", oldest_connected->bd_addr);
    return oldest_connected;
  }

  if (oldest_bonded != nullptr) {
    log::warn("No non-bonded, non-connected device found: {}", oldest_bonded->bd_addr);
    return oldest_bonded;
  }

  log::error("No suitable device found!");
  return nullptr;
}

/*******************************************************************************
 *
 * Function         btm_sec_allocate_dev_rec
 *
 * Description      Attempts to allocate a new device record. If we have
 *                  exceeded the maximum number of allowable records to
 *                  allocate, the oldest record will be deleted to make room
 *                  for the new record.
 *
 * Returns          Pointer to the newly allocated record
 *
 ******************************************************************************/
BtmDevice* btm_sec_allocate_dev_rec(const RawAddress& bd_addr) {
  if (!is_main_thread()) {
    log::error("Called from non-main thread");
  }
  BtmDevice* p_device = nullptr;

  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    if (BtmSecurity::Get().sec_dev_rec_ == nullptr) {
      log::warn("Unable to allocate device record with destructed device record list");
      return nullptr;
    }

    if (list_length(BtmSecurity::Get().sec_dev_rec_) > BTM_SEC_MAX_DEVICE_RECORDS) {
      p_device = btm_find_oldest_dev_rec();
      wipe_secrets_and_remove(p_device);
    }

    p_device = static_cast<BtmDevice*>(osi_calloc(sizeof(BtmDevice)));
    list_append(BtmSecurity::Get().sec_dev_rec_, p_device);
  } else {
    if (!BtmSecurity::Get().IsSecCBInitialized()) {
      log::warn("Security CB is not initialized");
      return nullptr;
    }

    for (BtmDevice& device : BtmSecurity::Get().device_records_) {
      if (!device.IsInitialized()) {
        p_device = &device;
        break;
      }
    }

    if (p_device == nullptr) {
      // The array is completely allocated, need to clean the oldest one.
      p_device = btm_find_oldest_dev_rec();
      wipe_secrets_and_remove(p_device);
    }
  }

  // Initialize defaults
  p_device->sec_rec.sec_flags = BTM_SEC_IN_USE;
  p_device->sec_rec.bond_type = BOND_TYPE_UNKNOWN;
  p_device->sec_rec.bredr_sc_enc_reason = BtmSecurityRecord::BrEdrScEncReason::OTHER;
  p_device->timestamp = BtmSecurity::Get().dev_rec_count_++;
  p_device->sec_rec.rmt_io_caps = BtIoCap::IO_CAP_UNKNOWN;
  p_device->suggested_tx_octets = 0;
  p_device->bd_addr = bd_addr;

  return p_device;
}

/*******************************************************************************
 *
 * Function         btm_set_bond_type_dev
 *
 * Description      Set the bond type for a device in the device database
 *                  with specified BD address
 *
 * Returns          true on success, otherwise false
 *
 ******************************************************************************/
bool btm_set_bond_type_dev(const RawAddress& bd_addr, tBTM_BOND_TYPE bond_type) {
  BtmDevice* p_device = btm_get_dev(bd_addr);

  if (p_device == nullptr) {
    log::warn("No security record for device {}", bd_addr);
    return false;
  }

  if (p_device->sec_rec.bond_type != bond_type) {
    log::info("{} bond_type changed: {} -> {}", bd_addr,
              bond_type_text(p_device->sec_rec.bond_type), bond_type_text(bond_type));
  }

  p_device->sec_rec.bond_type = bond_type;
  return true;
}

/*******************************************************************************
 *
 * Function         btm_get_sec_dev_rec
 *
 * Description      Get all security device records
 *
 * Returns          A vector containing pointers to all security device records
 *
 ******************************************************************************/
std::vector<BtmDevice*> btm_get_sec_dev_rec() {
  std::vector<BtmDevice*> result{};

  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    if (BtmSecurity::Get().sec_dev_rec_ != nullptr) {
      list_node_t* end = list_end(BtmSecurity::Get().sec_dev_rec_);
      for (list_node_t* node = list_begin(BtmSecurity::Get().sec_dev_rec_); node != end;
           node = list_next(node)) {
        BtmDevice* p_device = static_cast<BtmDevice*>(list_node(node));
        result.push_back(p_device);
      }
    }

    return result;
  }

  for (BtmDevice& device : BtmSecurity::Get().device_records_) {
    if (device.IsInitialized()) {
      result.push_back(&device);
    }
  }
  return result;
}

/*******************************************************************************
 *
 * Function         BTM_Sec_AddressKnown
 *
 * Description      Query the secure device database and check
 *                  whether the device associated with address has
 *                  its address resolved
 *
 * Returns          True if
 *                     - the device is unknown, or
 *                     - the device is classic, or
 *                     - the device is ble and has a public address
 *                     - the device is ble with a resolved identity address
 *                  False, otherwise
 *
 ******************************************************************************/
bool BTM_Sec_AddressKnown(const RawAddress& address) {
  const BtmDevice* p_device = btm_find_dev(address);

  // not a known device, we assume public address
  if (p_device == nullptr) {
    log::warn("{}, unknown device", address);
    return true;
  }
  // a classic device, we assume public address
  if ((p_device->device_type & BT_DEVICE_TYPE_BLE) == 0) {
    log::warn("{}, device type not BLE: 0x{:02x}", address, p_device->device_type);
    return true;
  }

  // bonded device with identity address known
  if (!p_device->ble.identity_address_with_type.bda.IsEmpty()) {
    return true;
  }

  // Public address, Random Static, or Random Non-Resolvable Address known
  if (p_device->ble.AddressType() == BLE_ADDR_PUBLIC || !BTM_BLE_IS_RESOLVE_BDA(address)) {
    return true;
  }

  log::warn("{}, the address type is 0x{:02x}", address, p_device->ble.AddressType());

  // Only Resolvable Private Address (RPA) is known, we don't allow it into
  // the background connection procedure.
  return false;
}

const tBLE_BD_ADDR BTM_Sec_GetAddressWithType(const RawAddress& bd_addr) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);
  if (p_device == nullptr || !p_device->is_device_type_has_ble()) {
    return {
            .type = BLE_ADDR_PUBLIC,
            .bda = bd_addr,
    };
  }

  if (p_device->ble.identity_address_with_type.bda.IsEmpty()) {
    return {
            .type = p_device->ble.AddressType(),
            .bda = bd_addr,
    };
  } else {
    // Floss doesn't support LL Privacy (yet). To expedite ARC testing, always
    // connect to the latest LE random address (if available and LL Privacy is
    // not enabled) rather than redesign.
    // TODO(b/235218533): Remove when LL Privacy is implemented.
#if TARGET_FLOSS
    if (!p_device->ble.cur_rand_addr.IsEmpty() &&
        btm_cb.ble_ctr_cb.privacy_mode < BTM_PRIVACY_1_2) {
      return {
              .type = BLE_ADDR_RANDOM,
              .bda = p_device->ble.cur_rand_addr,
      };
    }
#endif
    return p_device->ble.identity_address_with_type;
  }
}

#define DUMPSYS_TAG "shim::record"
// TODO(b/444620685): Remove when use_array_instead_list_in_sec_dev_rec is shipped.
static void DumpsysRecord_(int fd) {
  LOG_DUMPSYS_TITLE(fd, DUMPSYS_TAG);

  if (BtmSecurity::Get().sec_dev_rec_ == nullptr) {
    LOG_DUMPSYS(fd, "Record is empty - no devices");
    return;
  }

  unsigned cnt = 0;
  list_node_t* end = list_end(BtmSecurity::Get().sec_dev_rec_);
  for (list_node_t* node = list_begin(BtmSecurity::Get().sec_dev_rec_); node != end;
       node = list_next(node)) {
    BtmDevice* p_device = static_cast<BtmDevice*>(list_node(node));
    // TODO: handle in BtmDevice.ToString
    LOG_DUMPSYS(fd, "%03u %s", ++cnt, p_device->ToString().c_str());
  }
}

void DumpsysRecord(int fd) {
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    DumpsysRecord_(fd);
    return;
  }

  if (!BtmSecurity::Get().IsSecCBInitialized()) {
    LOG_DUMPSYS(fd, "Record is empty - no devices");
    return;
  }

  unsigned cnt = 0;
  for (const BtmDevice& device : BtmSecurity::Get().device_records_) {
    if (device.IsInitialized()) {
      // TODO: We should add more details to dump here.
      LOG_DUMPSYS(fd, "%03u %s", ++cnt, device.ToString().c_str());
    }
  }
}
#undef DUMPSYS_TAG

namespace bluetooth {
namespace legacy {
namespace testing {

void wipe_secrets_and_remove(BtmDevice* p_device) { ::wipe_secrets_and_remove(p_device); }

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth
