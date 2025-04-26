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

#include "btif/include/btif_storage.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/security_device_record.h"
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
void btm_sec_store_device_sc_support(uint16_t hci_handle, bool secure_connections_supported) {
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev_by_handle(hci_handle);
  if (p_dev_rec == nullptr) {
    return;
  }

  uint8_t property_val = (uint8_t)secure_connections_supported;
  bt_property_t property = {.type = BT_PROPERTY_REMOTE_SECURE_CONNECTIONS_SUPPORTED,
                            .len = sizeof(uint8_t),
                            .val = &property_val};

  btif_storage_set_remote_device_property(&p_dev_rec->bd_addr, &property);
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
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev_by_handle(hci_handle);
  if (p_dev_rec == nullptr) {
    return false;
  }

  uint8_t property_val = 0;
  bt_property_t property = {.type = BT_PROPERTY_REMOTE_MAX_SESSION_KEY_SIZE,
                            .len = sizeof(uint8_t),
                            .val = &property_val};

  bt_status_t cached = btif_storage_get_remote_device_property(&p_dev_rec->bd_addr, &property);

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
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev_by_handle(hci_handle);
  if (p_dev_rec == nullptr) {
    return;
  }

  uint8_t property_val = key_size;
  bt_property_t property = {.type = BT_PROPERTY_REMOTE_MAX_SESSION_KEY_SIZE,
                            .len = sizeof(uint8_t),
                            .val = &property_val};

  btif_storage_set_remote_device_property(&p_dev_rec->bd_addr, &property);
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
bool btm_dev_authenticated(const tBTM_SEC_DEV_REC* p_dev_rec) {
  return p_dev_rec->sec_rec.sec_flags & BTM_SEC_AUTHENTICATED;
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
bool btm_dev_encrypted(const tBTM_SEC_DEV_REC* p_dev_rec) {
  return p_dev_rec->sec_rec.sec_flags & BTM_SEC_ENCRYPTED;
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
bool btm_dev_16_digit_authenticated(const tBTM_SEC_DEV_REC* p_dev_rec) {
  // BTM_SEC_16_DIGIT_PIN_AUTHED is set if MITM or 16 digit pin is used
  return p_dev_rec->sec_rec.sec_flags & BTM_SEC_16_DIGIT_PIN_AUTHED;
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
bool access_secure_service_from_temp_bond(const tBTM_SEC_DEV_REC* p_dev_rec, bool locally_initiated,
                                          uint16_t security_req) {
  return !locally_initiated && (security_req & BTM_SEC_IN_AUTHENTICATE) &&
         p_dev_rec->sec_rec.is_bond_type_temporary();
}

bool BTM_CanReadDiscoverableCharacteristics(const RawAddress& bd_addr) {
  auto p_dev_rec = btm_find_dev(bd_addr);
  if (p_dev_rec != nullptr) {
    return p_dev_rec->can_read_discoverable;
  } else {
    log::error(
            "BTM_CanReadDiscoverableCharacteristics invoked for an invalid "
            "BD_ADDR");
    return false;
  }
}

// Return DEV_CLASS (uint8_t[3]) of bda. If record doesn't exist, create one.
DEV_CLASS btm_get_dev_class(const RawAddress& bda) {
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_or_alloc_dev(bda);

  if (p_dev_rec == nullptr) {
    log::error("No memory to allocate new p_dev_rec");
    return kDevClassEmpty;
  }

  return p_dev_rec->dev_class;
}

void BTM_update_version_info(const RawAddress& bd_addr,
                             const remote_version_info& remote_version_info) {
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev(bd_addr);
  if (p_dev_rec == nullptr) {
    return;
  }

  p_dev_rec->remote_version_info = remote_version_info;
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
