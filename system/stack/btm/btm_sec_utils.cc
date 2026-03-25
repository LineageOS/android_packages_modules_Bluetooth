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

#define LOG_TAG "btm_sec_utils"

#include "btm_sec_utils.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "btif/include/btif_storage.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_device_record.h"
#include "stack/include/bt_dev_class.h"

using namespace bluetooth;

bool concurrentPeerAuthIsEnabled() {
  // Was previously named BTM_DISABLE_CONCURRENT_PEER_AUTH.
  // Renamed to ENABLED for homogeneity with system properties
  static const bool sCONCURRENT_PEER_AUTH_IS_ENABLED =
          osi_property_get_bool("bluetooth.btm.sec.concurrent_peer_auth.enabled", true);
  return sCONCURRENT_PEER_AUTH_IS_ENABLED;
}

/**
 * Whether we should handle encryption change events from a peer device, while
 * we are in the IDLE state. This matters if we are waiting to retry encryption
 * following an LMP timeout, and then we get an encryption change event from the
 * peer.
 */
bool handleUnexpectedEncryptionChange() {
  static const bool sHandleUnexpectedEncryptionChange = osi_property_get_bool(
          "bluetooth.btm.sec.handle_unexpected_encryption_change.enabled", false);
  return sHandleUnexpectedEncryptionChange;
}

/*******************************************************************************
 *
 * Function         btm_sec_store_device_sc_support
 *
 * Description      Save Secure Connections support for this device to file
 *
 ******************************************************************************/
void btm_sec_store_device_sc_support(uint16_t hci_handle, bool host_sc_supported,
                                     bool controller_sc_supported) {
  const BtmDevice* p_device = btm_find_dev_by_handle(hci_handle);
  if (p_device == nullptr) {
    return;
  }

  btif_storage_set_remote_host_sc_support(p_device->bd_addr, host_sc_supported);
  btif_storage_set_remote_controller_sc_support(p_device->bd_addr, controller_sc_supported);
}

/*******************************************************************************
 *
 * Function         btm_sec_is_enc_algo_downgrade
 *
 * Description      Check for a stored device record matching the candidate
 *                  device, and return true if we would be downgrading from
 *                  AES-COM to E0.  Otherwise, return false.
 *
 * Returns          bool
 *
 ******************************************************************************/
bool btm_sec_is_enc_algo_downgrade(uint16_t hci_handle, bool host_sc_supported,
                                   bool controller_sc_supported) {

  const BtmDevice* p_device = btm_find_dev_by_handle(hci_handle);
  if (p_device == nullptr) {
    return false;
  }

  bool cached_controller_support =
          btif_storage_get_remote_controller_sc_support(p_device->bd_addr).value_or(false);
  bool cached_host_support =
          btif_storage_get_remote_host_sc_support(p_device->bd_addr).value_or(false);

  // If both the host and controller properties are set to true, then we used AES-CCM previously.
  // If support for either one is false now, then we'd be using E0 and this is a downgrade.
  if (cached_controller_support && cached_host_support) {
    if (!host_sc_supported || !controller_sc_supported) {
      return true;
    }
  }

  return false;
}

/*******************************************************************************
 *
 * Function         btm_sec_is_session_key_size_downgrade
 *
 * Description      Check if there is a stored device record matching this
 *                  handle, and return true if the stored record has a lower
 *                  session key size than the candidate device.
 *
 * Returns          bool
 *
 ******************************************************************************/
bool btm_sec_is_session_key_size_downgrade(uint16_t hci_handle, uint8_t key_size) {
  const BtmDevice* p_device = btm_find_dev_by_handle(hci_handle);
  if (p_device == nullptr) {
    return false;
  }

  uint8_t property_val = 0;
  bt_property_t property = {.type = BT_PROPERTY_REMOTE_MAX_SESSION_KEY_SIZE,
                            .len = sizeof(uint8_t),
                            .val = &property_val};

  bt_status_t cached = btif_storage_get_remote_device_property(p_device->bd_addr, &property);

  if (cached == BT_STATUS_FAIL) {
    return false;
  }

  return property_val > key_size;
}

/*******************************************************************************
 *
 * Function         btm_sec_update_session_key_size
 *
 * Description      Store the max session key size to disk, if possible.
 *
 ******************************************************************************/
void btm_sec_update_session_key_size(uint16_t hci_handle, uint8_t key_size) {
  const BtmDevice* p_device = btm_find_dev_by_handle(hci_handle);
  if (p_device == nullptr) {
    return;
  }

  uint8_t property_val = key_size;
  bt_property_t property = {.type = BT_PROPERTY_REMOTE_MAX_SESSION_KEY_SIZE,
                            .len = sizeof(uint8_t),
                            .val = &property_val};

  btif_storage_set_remote_device_property(p_device->bd_addr, &property);
}

/*******************************************************************************
 *
 * Function         btm_dev_authenticated
 *
 * Description      check device is authenticated on BR/EDR
 *
 * Returns          bool    true or false
 *
 ******************************************************************************/
bool btm_dev_authenticated(const BtmDevice* p_device) {
  return p_device->sec_rec.sec_flags & BTM_SEC_AUTHENTICATED;
}

/*******************************************************************************
 *
 * Function         btm_dev_encrypted
 *
 * Description      check device is encrypted on BR/EDR
 *
 * Returns          bool    true or false
 *
 ******************************************************************************/
bool btm_dev_encrypted(const BtmDevice* p_device) {
  return p_device->sec_rec.sec_flags & BTM_SEC_ENCRYPTED;
}

/*******************************************************************************
 *
 * Function         btm_dev_16_digit_authenticated
 *
 * Description      check device is authenticated by using 16 digit pin or MITM (BR/EDR)
 *
 * Returns          bool    true or false
 *
 ******************************************************************************/
bool btm_dev_16_digit_authenticated(const BtmDevice* p_device) {
  // BTM_SEC_16_DIGIT_PIN_AUTHED is set if MITM or 16 digit pin is used
  return p_device->sec_rec.sec_flags & BTM_SEC_16_DIGIT_PIN_AUTHED;
}

/*******************************************************************************
 *
 * Function         access_secure_service_from_temp_bond
 *
 * Description      a utility function to test whether an access to
 *                  secure service from temp bonding is happening
 *
 * Returns          true if the aforementioned condition holds,
 *                  false otherwise
 *
 ******************************************************************************/
bool access_secure_service_from_temp_bond(const BtmDevice* p_device, bool locally_initiated,
                                          uint16_t security_req) {
  return !locally_initiated && (security_req & BTM_SEC_IN_AUTHENTICATE) &&
         p_device->sec_rec.is_bond_type_temporary();
}

bool BTM_CanReadDiscoverableCharacteristics(const RawAddress& bd_addr) {
  auto p_device = btm_find_dev(bd_addr);
  if (p_device != nullptr) {
    return p_device->can_read_discoverable;
  } else {
    log::error(
            "BTM_CanReadDiscoverableCharacteristics invoked for an invalid "
            "BD_ADDR");
    return false;
  }
}

// Return DEV_CLASS (uint8_t[3]) of bda
DEV_CLASS btm_get_dev_class(const RawAddress& bda) {
  const BtmDevice* p_device = btm_find_dev(bda);

  if (p_device == nullptr) {
    log::error("No record found for bda: {}", bda);
    return kDevClassEmpty;
  }

  return p_device->dev_class;
}

void BTM_update_version_info(const RawAddress& bd_addr,
                             const remote_version_info& remote_version_info) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    return;
  }

  p_device->remote_version_info = remote_version_info;
}

/*******************************************************************************
 *
 * Function         btm_pair_state_descr
 *
 * Description      Return state description for tracing
 *
 ******************************************************************************/
const char* btm_pair_state_descr(tBTM_PAIRING_STATE state) {
  switch (state) {
    case BTM_PAIR_STATE_IDLE:
      return "IDLE";
    case BTM_PAIR_STATE_GET_REM_NAME:
      return "GET_REM_NAME";
    case BTM_PAIR_STATE_WAIT_PIN_REQ:
      return "WAIT_PIN_REQ";
    case BTM_PAIR_STATE_WAIT_LOCAL_PIN:
      return "WAIT_LOCAL_PIN";
    case BTM_PAIR_STATE_WAIT_NUMERIC_CONFIRM:
      return "WAIT_NUM_CONFIRM";
    case BTM_PAIR_STATE_KEY_ENTRY:
      return "KEY_ENTRY";
    case BTM_PAIR_STATE_WAIT_LOCAL_OOB_RSP:
      return "WAIT_LOCAL_OOB_RSP";
    case BTM_PAIR_STATE_WAIT_LOCAL_IOCAPS:
      return "WAIT_LOCAL_IOCAPS";
    case BTM_PAIR_STATE_INCOMING_SSP:
      return "INCOMING_SSP";
    case BTM_PAIR_STATE_WAIT_AUTH_COMPLETE:
      return "WAIT_AUTH_COMPLETE";
    case BTM_PAIR_STATE_WAIT_DISCONNECT:
      return "WAIT_DISCONNECT";
  }

  return "???";
}

/*******************************************************************************
 *
 * Function         is_autonomous_repairing_supported
 *
 * Description      Return true if the autonomous repairing is supported.
 *
 ******************************************************************************/
static bool autonomous_repairing_initiation = false;

bool is_autonomous_repairing_supported() {
  // TODO (b/440298497): Change this to flag and android check once the SDK check CL is in.
  return autonomous_repairing_initiation;
}

void set_autonomous_repairing_supported(bool platform_support_autonomous_repairing_initiation) {
  autonomous_repairing_initiation = com_android_bluetooth_flags_autonomous_repairing_initiation() &&
                                    platform_support_autonomous_repairing_initiation;
}
