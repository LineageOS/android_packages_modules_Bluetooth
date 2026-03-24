/*
 * Copyright 2023 The Android Open Source Project
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
 */

#define LOG_TAG "ble_sec"

#include "stack/btm/btm_ble_sec.h"

#include <android_bluetooth_sysprop.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>
#include <optional>

#include "btif/include/btif_storage.h"
#include "btm_security_record.h"
#include "crypto_toolbox/crypto_toolbox.h"
#include "device/include/interop.h"
#include "hci/controller.h"
#include "main/shim/entry.h"
#include "osi/include/allocator.h"
#include "osi/include/properties.h"
#include "platform_ssl_mem.h"
#include "stack/btm/btm_ble_int.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_device_record.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_sec_int_types.h"
#include "stack/btm/btm_security.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/eatt/eatt.h"
#include "stack/include/acl_api.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_name.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_ble_privacy.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/gatt_api.h"
#include "stack/include/l2cap_security_interface.h"
#include "stack/include/smp_api.h"
#include "stack/include/smp_api_types.h"
#include "stack/l2cap/l2c_api.h"
#include "stack/l2cap/l2c_int.h"

using namespace bluetooth;

namespace {
constexpr char kBtmLogTag[] = "SEC";
}

static constexpr char kPropertyCtkdDisableCsrkDistribution[] =
        "bluetooth.core.smp.le.ctkd.quirk_disable_csrk_distribution";

/******************************************************************************/
/* External Function to be called by other modules                            */
/******************************************************************************/
void btm_sec_add_ble_device(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type,
                            tBLE_ADDR_TYPE addr_type) {
  log::debug("dev_type=0x{:x}", dev_type);

  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (!p_device) {
    p_device = btm_sec_allocate_dev_rec(bd_addr);

    if (p_device == nullptr) {
      log::warn("device record allocation failed bd_addr:{}", bd_addr);
      return;
    }

    p_device->hci_handle =
            get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_BR_EDR);
    p_device->ble_hci_handle =
            get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);

    /* update conn params, use default value for background connection params */
    p_device->conn_params.min_conn_int = BTM_BLE_CONN_PARAM_UNDEF;
    p_device->conn_params.max_conn_int = BTM_BLE_CONN_PARAM_UNDEF;
    p_device->conn_params.supervision_tout = BTM_BLE_CONN_PARAM_UNDEF;
    p_device->conn_params.peripheral_latency = BTM_BLE_CONN_PARAM_UNDEF;

    log::debug("Device added, handle=0x{:x}, p_device={}, bd_addr={}", p_device->ble_hci_handle,
               std::format_ptr(p_device), bd_addr);

    if (btif_storage_get_stored_remote_name(bd_addr,
                                            reinterpret_cast<char*>(&p_device->sec_bd_name))) {
      p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
    }

    uint32_t cod = 0;
    if (btif_storage_get_cod(bd_addr, &cod)) {
      DEV_CLASS dev_class = {};
      dev_class[2] = (uint8_t)cod;
      dev_class[1] = (uint8_t)(cod >> 8);
      dev_class[0] = (uint8_t)(cod >> 16);
      p_device->dev_class = dev_class;
    }
  }

  p_device->device_type |= dev_type;
  if (is_ble_addr_type_known(addr_type)) {
    p_device->ble.SetAddressType(addr_type);
  } else {
    log::warn("Please do not update device record from anonymous le advertisement");
  }

  /* sync up with the Inq Data base*/
  tBTM_INQ_INFO* p_info = BTM_InqDbRead(bd_addr);
  if (p_info) {
    p_info->results.ble_addr_type = p_device->ble.AddressType();
    p_device->device_type |= p_info->results.device_type;
    log::debug("InqDb device_type =0x{:x} addr_type=0x{:x}", p_device->device_type,
               p_info->results.ble_addr_type);
    p_info->results.device_type = p_device->device_type;
  }

  p_device->clock_offset = BTM_GetCachedClockOffset(bd_addr);
}

/*******************************************************************************
 *
 * Function         btm_sec_add_ble_key
 *
 * Description      Add/modify LE device information.  This function will be
 *                  normally called during host startup to restore all required
 *                  information stored in the NVRAM.
 *
 * Parameters:      bd_addr          - BD address of the peer
 *                  key_type         - LE SMP key type.
 *                  key              - LE key value
 *
 ******************************************************************************/
void btm_sec_add_ble_key(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                         const tBTM_LE_KEY_VALUE& key) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr ||
      (key_type != BTM_LE_KEY_PENC && key_type != BTM_LE_KEY_PID && key_type != BTM_LE_KEY_PCSRK &&
       key_type != BTM_LE_KEY_LENC && key_type != BTM_LE_KEY_LCSRK && key_type != BTM_LE_KEY_LID)) {
    log::warn("Wrong Type, or No Device record for bdaddr:{}, Type:0{}", bd_addr, key_type);
    return;
  }

  log::debug("Adding BLE key device:{} key_type:{}", bd_addr, key_type);

  btm_sec_save_le_key(bd_addr, key_type, key, false);
  // Only set peer irk. Local irk is always the same.
  if (key_type == BTM_LE_KEY_PID) {
    btm_ble_resolving_list_load_dev(*p_device);
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_load_local_keys
 *
 * Description      Local local identity key, encryption root or sign counter.
 *
 * Parameters:      key_type: type of key, can be BTM_BLE_KEY_TYPE_ID,
 *                                                BTM_BLE_KEY_TYPE_ER
 *                                             or BTM_BLE_KEY_TYPE_COUNTER.
 *                  p_key: pointer to the key.
 *
 * Returns          non2.
 *
 ******************************************************************************/
void btm_ble_load_local_keys(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key) {
  tBTM_SEC_DEVCB* p_devcb = &BtmSecurity::Get().devcb_;
  log::verbose("type:{}", key_type);
  if (p_key != NULL) {
    switch (key_type) {
      case BTM_BLE_KEY_TYPE_ID:
        memcpy(&p_devcb->id_keys, &p_key->id_keys, sizeof(tBTM_BLE_LOCAL_ID_KEYS));
        break;

      case BTM_BLE_KEY_TYPE_ER:
        p_devcb->ble_encryption_key_value = p_key->er;
        break;

      default:
        log::error("unknown key type:{}", key_type);
        break;
    }
  }
}

/** Returns local device encryption root (ER) */
const Octet16& btm_get_device_enc_root() {
  return BtmSecurity::Get().devcb_.ble_encryption_key_value;
}

/** Returns local device identity root (IR). */
const Octet16& btm_get_device_id_root() { return BtmSecurity::Get().devcb_.id_keys.irk; }

/** Return local device DHK. */
const Octet16& btm_get_device_dhk() { return BtmSecurity::Get().devcb_.id_keys.dhk; }

/*******************************************************************************
 *
 * Function         BTM_SecurityGrant
 *
 * Description      This function is called to grant security process.
 *
 * Parameters       bd_addr - peer device bd address.
 *                  res     - result of the operation tBTM_STATUS::BTM_SUCCESS if success.
 *                            Otherwise, BTM_REPEATED_ATTEMPTS if too many
 *                            attempts.
 *
 * Returns          None
 *
 ******************************************************************************/
void btm_security_grant(const RawAddress& bd_addr, tBTM_STATUS res) {
  const tSMP_STATUS res_smp =
          (res == tBTM_STATUS::BTM_SUCCESS) ? SMP_SUCCESS : SMP_REPEATED_ATTEMPTS;
  log::verbose("bd_addr:{}, res:{}", bd_addr, smp_status_text(res_smp));
  BTM_LogHistory(kBtmLogTag, bd_addr, "Granted",
                 std::format("passkey_status:{}", smp_status_text(res_smp)));

  SMP_SecurityGrant(bd_addr, res_smp);
}

/*******************************************************************************
 *
 * Function         BTM_BlePasskeyReply
 *
 * Description      This function is called after Security Manager submitted
 *                  passkey request to the application.
 *
 * Parameters:      bd_addr - Address of the device for which passkey was
 *                            requested
 *                  res     - result of the operation tBTM_STATUS::BTM_SUCCESS if success
 *                  key_len - length in bytes of the Passkey
 *                  p_passkey    - pointer to array with the passkey
 *
 ******************************************************************************/
void btm_ble_passkey_reply(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  log::verbose("bd_addr:{}, res:{}", bd_addr, res);
  if (p_device == nullptr) {
    log::error("Unknown device:{}", bd_addr);
    return;
  }

  const tSMP_STATUS res_smp =
          (res == tBTM_STATUS::BTM_SUCCESS) ? SMP_SUCCESS : SMP_PASSKEY_ENTRY_FAIL;
  BTM_LogHistory(kBtmLogTag, bd_addr, "Passkey reply",
                 std::format("transport:{} authenticate_status:{}",
                             bt_transport_text(BT_TRANSPORT_LE), smp_status_text(res_smp)));

  p_device->sec_rec.sec_flags |= BTM_SEC_LE_AUTHENTICATED;
  SMP_PasskeyReply(bd_addr, res_smp, passkey);
}

/*******************************************************************************
 *
 * Function         BTM_BleConfirmReply
 *
 * Description      This function is called after Security Manager submitted
 *                  numeric comparison request to the application.
 *
 * Parameters:      bd_addr      - Address of the device with which numeric
 *                                 comparison was requested
 *                  res          - comparison result tBTM_STATUS::BTM_SUCCESS if success
 *
 ******************************************************************************/
void btm_ble_confirm_reply(const RawAddress& bd_addr, tBTM_STATUS res) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  log::verbose("bd_addr:{}, res:{}", bd_addr, res);
  if (p_device == nullptr) {
    log::error("Unknown device:{}", bd_addr);
    return;
  }
  const tSMP_STATUS res_smp =
          (res == tBTM_STATUS::BTM_SUCCESS) ? SMP_SUCCESS : SMP_PASSKEY_ENTRY_FAIL;

  BTM_LogHistory(kBtmLogTag, bd_addr, "Confirm reply",
                 std::format("transport:{} numeric_comparison_authenticate_status:{}",
                             bt_transport_text(BT_TRANSPORT_LE), smp_status_text(res_smp)));

  p_device->sec_rec.sec_flags |= BTM_SEC_LE_AUTHENTICATED;
  SMP_ConfirmReply(bd_addr, res_smp);
}

/*******************************************************************************
 *
 * Function         BTM_BleOobDataReply
 *
 * Description      This function is called to provide the OOB data for
 *                  SMP in response to BTM_LE_OOB_REQ_EVT
 *
 * Parameters:      bd_addr     - Address of the peer device
 *                  res         - result of the operation SMP_SUCCESS if success
 *                  p_data      - oob data, depending on transport and
 *                                capabilities.
 *                                Might be "Simple Pairing Randomizer", or
 *                                "Security Manager TK Value".
 *
 ******************************************************************************/
void btm_ble_oob_data_reply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len,
                            uint8_t* p_data) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    log::error("Unknown device:{}", bd_addr);
    return;
  }

  const tSMP_STATUS res_smp = (res == tBTM_STATUS::BTM_SUCCESS) ? SMP_SUCCESS : SMP_OOB_FAIL;
  BTM_LogHistory(kBtmLogTag, bd_addr, "Oob data reply",
                 std::format("transport:{} authenticate_status:{}",
                             bt_transport_text(BT_TRANSPORT_LE), smp_status_text(res_smp)));

  p_device->sec_rec.sec_flags |= BTM_SEC_LE_AUTHENTICATED;
  SMP_OobDataReply(bd_addr, res_smp, len, p_data);
}

/*******************************************************************************
 *
 * Function         BTM_BleSecureConnectionOobDataReply
 *
 * Description      This function is called to provide the OOB data for
 *                  SMP in response to BTM_LE_OOB_REQ_EVT when secure connection
 *                  data is available
 *
 * Parameters:      bd_addr     - Address of the peer device
 *                  p_c         - pointer to Confirmation.
 *                  p_r         - pointer to Randomizer
 *
 ******************************************************************************/
void btm_ble_secure_connection_oob_data_reply(const RawAddress& bd_addr, uint8_t* p_c,
                                              uint8_t* p_r) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    log::error("Unknown device:{}", bd_addr);
    return;
  }

  BTM_LogHistory(kBtmLogTag, bd_addr, "Oob data reply",
                 std::format("transport:{}", bt_transport_text(BT_TRANSPORT_LE)));

  p_device->sec_rec.sec_flags |= BTM_SEC_LE_AUTHENTICATED;

  tSMP_SC_OOB_DATA oob = {};

  oob.peer_oob_data.present = true;
  memcpy(oob.peer_oob_data.randomizer.data(), p_r, oob.peer_oob_data.randomizer.size());
  memcpy(oob.peer_oob_data.commitment.data(), p_c, oob.peer_oob_data.commitment.size());
  oob.peer_oob_data.addr_rcvd_from.type = p_device->ble.AddressType();
  oob.peer_oob_data.addr_rcvd_from.bda = bd_addr;

  SMP_SecureConnectionOobDataReply((uint8_t*)&oob);
}

/*******************************************************************************
 *
 * Function         btm_ble_determine_security_act
 *
 * Description      This function checks the security of current LE link
 *                  and returns the appropriate action that needs to be
 *                  taken to achieve the required security.
 *
 * Parameter        outgoing: True if outgoing connection
 *                  bdaddr: remote device address
 *                  security_required: Security required for the service.
 *
 * Returns          The appropriate security action required.
 *
 ******************************************************************************/
static tBTM_SEC_ACTION btm_ble_determine_security_act(bool outgoing, const RawAddress& bdaddr,
                                                      uint16_t security_required) {
  tBTM_LE_AUTH_REQ auth_req = 0x00;

  if (outgoing) {
    if ((security_required & BTM_SEC_OUT_FLAGS) == 0 &&
        (security_required & BTM_SEC_OUT_MITM) == 0) {
      log::info("No security required for outgoing connection");
      return BTM_SEC_OK;
    }

    if (security_required & BTM_SEC_OUT_MITM) {
      auth_req |= BTM_LE_AUTH_REQ_MITM;
    }
  } else {
    if ((security_required & BTM_SEC_IN_FLAGS) == 0 && (security_required & BTM_SEC_IN_MITM) == 0) {
      log::verbose("No security required for incoming connection");
      return BTM_SEC_OK;
    }

    if (security_required & BTM_SEC_IN_MITM) {
      auth_req |= BTM_LE_AUTH_REQ_MITM;
    }
  }

  tBTM_BLE_SEC_REQ_ACT ble_sec_act = btm_ble_link_sec_check(bdaddr, auth_req);

  log::verbose("bdaddr:{} auth_req:{:#x} ble_sec_act:{}", bdaddr, auth_req, ble_sec_act);

  if (ble_sec_act == BTM_BLE_SEC_REQ_ACT_DISCARD) {
    return BTM_SEC_ENC_PENDING;
  }

  if (ble_sec_act == BTM_BLE_SEC_REQ_ACT_NONE) {
    return BTM_SEC_OK;
  }

  bool is_link_encrypted = get_security_client_interface().BTM_IsEncrypted(bdaddr, BT_TRANSPORT_LE);
  bool is_key_mitm = btm_is_link_key_authed(bdaddr, BT_TRANSPORT_LE);

  if (auth_req & BTM_LE_AUTH_REQ_MITM) {
    if (!is_key_mitm) {
      return BTM_SEC_ENCRYPT_MITM;
    } else {
      if (is_link_encrypted) {
        return BTM_SEC_OK;
      } else {
        return BTM_SEC_ENCRYPT;
      }
    }
  } else {
    if (is_link_encrypted) {
      return BTM_SEC_OK;
    } else {
      return BTM_SEC_ENCRYPT_NO_MITM;
    }
  }

  return BTM_SEC_OK;
}

/*******************************************************************************
 *
 * Function         btm_ble_start_sec_check
 *
 * Description      This function is to check and set the security required for
 *                  LE link for LE COC.
 *
 * Parameter        bdaddr: remote device address.
 *                  psm : PSM of the LE COC service.
 *                  outgoing: true if outgoing connection.
 *                  p_callback : Pointer to the callback function.
 *                  p_ref_data : Pointer to be returned along with the callback.
 *
 * Returns          Returns  - tBTM_STATUS
 *
 ******************************************************************************/
tBTM_STATUS btm_ble_start_sec_check(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                    tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
  /* Find the service record for the PSM */
  tBTM_SEC_SERV_REC* p_serv_rec = BtmSecurity::Get().find_first_serv_rec(outgoing, psm);

  /* If there is no application registered with this PSM do not allow connection
   */
  if (!p_serv_rec) {
    log::warn("PSM: {} no application registered", psm);
    (*p_callback)(bd_addr, BT_TRANSPORT_LE, p_ref_data, tBTM_STATUS::BTM_MODE_UNSUPPORTED);
    return tBTM_STATUS::BTM_ILLEGAL_VALUE;
  }

  bool is_encrypted = get_security_client_interface().BTM_IsEncrypted(bd_addr, BT_TRANSPORT_LE);
  bool is_bonded = get_security_client_interface().BTM_IsBonded(bd_addr, BT_TRANSPORT_LE);

  if (!outgoing) {
    if ((p_serv_rec->security_flags & BTM_SEC_IN_ENCRYPT) && !is_encrypted) {
      log::error("BTM_NOT_ENCRYPTED. service security_flags=0x{:x}", p_serv_rec->security_flags);
      return tBTM_STATUS::BTM_NOT_ENCRYPTED;
    } else if ((p_serv_rec->security_flags & BTM_SEC_IN_AUTHENTICATE) && !(is_bonded)) {
      log::error("tBTM_STATUS::BTM_NOT_AUTHENTICATED. service security_flags=0x{:x}",
                 p_serv_rec->security_flags);
      return tBTM_STATUS::BTM_NOT_AUTHENTICATED;
    }
    /* TODO: When security is required, then must check that the key size of our
       service is equal or smaller than the incoming connection key size. */
  }

  tBTM_SEC_ACTION sec_act =
          btm_ble_determine_security_act(outgoing, bd_addr, p_serv_rec->security_flags);

  tBTM_BLE_SEC_ACT ble_sec_act = BTM_BLE_SEC_NONE;

  switch (sec_act) {
    case BTM_SEC_OK:
      log::debug("Security met");
      p_callback(bd_addr, BT_TRANSPORT_LE, p_ref_data, tBTM_STATUS::BTM_SUCCESS);
      break;

    case BTM_SEC_ENCRYPT:
      log::debug("Encryption needs to be done");
      ble_sec_act = BTM_BLE_SEC_ENCRYPT;
      break;

    case BTM_SEC_ENCRYPT_MITM:
      log::debug("Pairing with MITM needs to be done");
      ble_sec_act = BTM_BLE_SEC_ENCRYPT_MITM;
      break;

    case BTM_SEC_ENCRYPT_NO_MITM:
      log::debug("Pairing with No MITM needs to be done");
      ble_sec_act = BTM_BLE_SEC_ENCRYPT_NO_MITM;
      break;

    case BTM_SEC_ENC_PENDING:
      log::debug("Encryption pending");
      break;
  }

  if (ble_sec_act == BTM_BLE_SEC_NONE && sec_act != BTM_SEC_ENC_PENDING) {
    return tBTM_STATUS::BTM_SUCCESS;
  }

  l2cble_update_sec_act(bd_addr, sec_act);

  get_security_client_interface().BTM_SetEncryption(bd_addr, BT_TRANSPORT_LE, p_callback,
                                                    p_ref_data, ble_sec_act);

  return tBTM_STATUS::BTM_SUCCESS;
}

/*******************************************************************************
 *
 * Function         increment_sign_counter
 *
 * Description      This method is to increment the (local or peer) sign counter
 * Returns         None
 *
 ******************************************************************************/
void BtmSecurityRecord::increment_sign_counter(bool local) {
  if (local) {
    ble_keys.local_counter++;
  } else {
    ble_keys.counter++;
  }

  log::verbose("local={} local sign counter={} peer sign counter={}", local, ble_keys.local_counter,
               ble_keys.counter);
}

/*******************************************************************************
 *
 * Function         btm_ble_get_enc_key_type
 *
 * Description      This function is to get the BLE key type that has been
 *                  exchanged between the local device and the peer device.
 *
 * Returns          p_key_type: output parameter to carry the key type value.
 *
 ******************************************************************************/
bool btm_ble_get_enc_key_type(const RawAddress& bd_addr, uint8_t* p_key_types) {
  const BtmDevice* p_device;

  log::verbose("bd_addr:{}", bd_addr);

  p_device = btm_find_dev(bd_addr);
  if (p_device != NULL) {
    *p_key_types = p_device->sec_rec.ble_keys.key_type;
    return true;
  }
  return false;
}

/*******************************************************************************
 *
 * Function         btm_get_local_div
 *
 * Description      This function is called to read the local DIV
 *
 * Returns          TRUE - if a valid DIV is available
 ******************************************************************************/
bool btm_get_local_div(const RawAddress& bd_addr, uint16_t* p_div) {
  const BtmDevice* p_device;
  bool status = false;

  *p_div = 0;
  p_device = btm_find_dev(bd_addr);

  if (p_device && p_device->sec_rec.ble_keys.div) {
    status = true;
    *p_div = p_device->sec_rec.ble_keys.div;
  }
  log::verbose("status={} (1-OK) DIV=0x{:x}", status, *p_div);
  return status;
}

/*******************************************************************************
 *
 * Function         btm_sec_save_le_key
 *
 * Description      This function is called by the SMP to update
 *                  an  BLE key.  SMP is internal, whereas all the keys shall
 *                  be sent to the application.  The function is also called
 *                  when application passes ble key stored in NVRAM to the
 *                  btm_sec.
 *                  pass_to_application parameter is false in this case.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_save_le_key(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                         const tBTM_LE_KEY_VALUE& key, bool pass_to_application) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    log::warn("Key type 0x{:x} for unknown device {}", key_type, bd_addr);
    return;
  }

  btm_ble_init_pseudo_addr(p_device, bd_addr);

  switch (key_type) {
    case BTM_LE_KEY_PENC:
      p_device->sec_rec.ble_keys.pltk = key.penc_key.ltk;
      p_device->sec_rec.ble_keys.rand = key.penc_key.rand;
      p_device->sec_rec.ble_keys.sec_level = key.penc_key.sec_level;
      p_device->sec_rec.ble_keys.ediv = key.penc_key.ediv;
      p_device->sec_rec.ble_keys.key_size = key.penc_key.key_size;
      p_device->sec_rec.ble_keys.key_type |= BTM_LE_KEY_PENC;
      p_device->sec_rec.sec_flags |= BTM_SEC_LE_LINK_KEY_KNOWN;
      if (key.penc_key.sec_level == SMP_SEC_AUTHENTICATED) {
        p_device->sec_rec.sec_flags |= BTM_SEC_LE_LINK_KEY_AUTHED;
      } else {
        p_device->sec_rec.sec_flags &= ~BTM_SEC_LE_LINK_KEY_AUTHED;
      }

      if (key.pairing_algorithm != PairingAlgorithm::LE_LEGACY &&
          key.pairing_algorithm != PairingAlgorithm::SC) {
        log::error("Invalid pairing algorithm: {} for bd_addr: {}", key.pairing_algorithm, bd_addr);
      }
      p_device->sec_rec.ble_pairing_algorithm = key.pairing_algorithm;
      log::verbose("BTM_LE_KEY_PENC key_type=0x{:x} sec_flags=0x{:x} sec_leve=0x{:x} for {}",
                   p_device->sec_rec.ble_keys.key_type, p_device->sec_rec.sec_flags,
                   p_device->sec_rec.ble_keys.sec_level, bd_addr);
      break;

    case BTM_LE_KEY_PID:
      p_device->sec_rec.ble_keys.irk = key.pid_key.irk;
      p_device->ble.identity_address_with_type.bda = key.pid_key.identity_addr;
      p_device->ble.identity_address_with_type.type = key.pid_key.identity_addr_type;
      p_device->sec_rec.ble_keys.key_type |= BTM_LE_KEY_PID;
      log::verbose(
              "BTM_LE_KEY_PID key_type=0x{:x} save peer IRK, change bd_addr={} "
              "to id_addr={} id_addr_type=0x{:x}",
              p_device->sec_rec.ble_keys.key_type, p_device->bd_addr, key.pid_key.identity_addr,
              key.pid_key.identity_addr_type);
      /* update device record address as identity address */
      p_device->bd_addr = key.pid_key.identity_addr;
      /* combine DUMO device security record if needed */
      btm_consolidate_dev(p_device);
      break;

    case BTM_LE_KEY_PCSRK:
      p_device->sec_rec.ble_keys.pcsrk = key.pcsrk_key.csrk;
      p_device->sec_rec.ble_keys.srk_sec_level = key.pcsrk_key.sec_level;
      p_device->sec_rec.ble_keys.counter = key.pcsrk_key.counter;
      p_device->sec_rec.ble_keys.key_type |= BTM_LE_KEY_PCSRK;
      p_device->sec_rec.sec_flags |= BTM_SEC_LE_LINK_KEY_KNOWN;

      log::verbose(
              "BTM_LE_KEY_PCSRK key_type=0x{:x} sec_flags=0x{:x} "
              "sec_level=0x{:x} peer_counter={} for {}",
              p_device->sec_rec.ble_keys.key_type, p_device->sec_rec.sec_flags,
              p_device->sec_rec.ble_keys.srk_sec_level, p_device->sec_rec.ble_keys.counter,
              bd_addr);
      break;

    case BTM_LE_KEY_LENC:
      p_device->sec_rec.ble_keys.lltk = key.lenc_key.ltk;
      p_device->sec_rec.ble_keys.div = key.lenc_key.div; /* update DIV */
      p_device->sec_rec.ble_keys.sec_level = key.lenc_key.sec_level;
      p_device->sec_rec.ble_keys.key_size = key.lenc_key.key_size;
      p_device->sec_rec.ble_keys.key_type |= BTM_LE_KEY_LENC;

      if (key.pairing_algorithm != PairingAlgorithm::LE_LEGACY &&
          key.pairing_algorithm != PairingAlgorithm::SC) {
        log::error("Invalid pairing algorithm: {} for bd_addr: {}", key.pairing_algorithm, bd_addr);
      }
      p_device->sec_rec.ble_pairing_algorithm = key.pairing_algorithm;

      log::verbose(
              "BTM_LE_KEY_LENC key_type=0x{:x} DIV=0x{:x} key_size=0x{:x} "
              "sec_level=0x{:x} for {}",
              p_device->sec_rec.ble_keys.key_type, p_device->sec_rec.ble_keys.div,
              p_device->sec_rec.ble_keys.key_size, p_device->sec_rec.ble_keys.sec_level, bd_addr);
      break;

    case BTM_LE_KEY_LCSRK: /* local CSRK has been delivered */
      p_device->sec_rec.ble_keys.lcsrk = key.lcsrk_key.csrk;
      p_device->sec_rec.ble_keys.div = key.lcsrk_key.div; /* update DIV */
      p_device->sec_rec.ble_keys.local_csrk_sec_level = key.lcsrk_key.sec_level;
      p_device->sec_rec.ble_keys.local_counter = key.lcsrk_key.counter;
      p_device->sec_rec.ble_keys.key_type |= BTM_LE_KEY_LCSRK;
      log::verbose(
              "BTM_LE_KEY_LCSRK key_type=0x{:x} DIV=0x{:x} scrk_sec_level=0x{:x} "
              "local_counter={} for {}",
              p_device->sec_rec.ble_keys.key_type, p_device->sec_rec.ble_keys.div,
              p_device->sec_rec.ble_keys.local_csrk_sec_level,
              p_device->sec_rec.ble_keys.local_counter, bd_addr);
      break;

    case BTM_LE_KEY_LID:
      p_device->sec_rec.ble_keys.key_type |= BTM_LE_KEY_LID;
      log::verbose("BTM_LE_KEY_LID for {}", bd_addr);
      break;
    default:
      log::warn("Unknown key type 0x{:02x} for {}", key_type, bd_addr);
      return;
  }

  /* Notify the application that one of the BLE keys has been updated.
     If link key is in progress, it will get sent later.*/
  if (pass_to_application) {
    tBTM_LE_EVT_DATA cb_data = {};
    cb_data.key.p_key_value = &key;
    cb_data.key.key_type = key_type;

    BTM_BLE_SEC_CALLBACK(BTM_LE_KEY_EVT, bd_addr, &cb_data);
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_update_sec_key_size
 *
 * Description      update the current link encryption key size
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_update_sec_key_size(const RawAddress& bd_addr, uint8_t enc_key_size) {
  BtmDevice* p_device;

  log::verbose("bd_addr:{}, enc_key_size={}", bd_addr, enc_key_size);

  p_device = btm_get_dev(bd_addr);
  if (p_device != NULL) {
    p_device->sec_rec.le_enc_key_size = enc_key_size;
  }
}

/*******************************************************************************
 *
 * Function         BTM_BleReadSecKeySize
 *
 * Description      update the current link encryption key size
 *
 * Returns          void
 *
 ******************************************************************************/
uint8_t btm_ble_read_sec_key_size(const RawAddress& bd_addr) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);

  if (p_device != NULL) {
    return p_device->sec_rec.le_enc_key_size;
  } else {
    return 0;
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_link_sec_check
 *
 * Description      Check BLE link security level match.
 *
 * Returns          true: check is OK and the *p_sec_req_act contain the action
 *
 ******************************************************************************/
tBTM_BLE_SEC_REQ_ACT btm_ble_link_sec_check(const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);
  if (p_device == nullptr) {
    log::error("Unknown device {}", bd_addr);
    return BTM_BLE_SEC_REQ_ACT_NONE;
  }

  // Discard the security request if the link is encrypting or authenticating.
  if (p_device->sec_rec.is_security_state_encrypting() ||
      p_device->sec_rec.le_link == tSECURITY_STATE::AUTHENTICATING) {
    log::warn(
            "Discarding security request while central is encrypting the link, bd_addr={}, "
            "auth_req=0x{:x}",
            bd_addr, auth_req);
    return BTM_BLE_SEC_REQ_ACT_DISCARD;
  }

  // Requested security level
  uint8_t req_sec_level =
          (auth_req & BTM_LE_AUTH_REQ_MITM) ? SMP_SEC_AUTHENTICATED : SMP_SEC_UNAUTHENTICATE;

  // Get the current security level of the link
  uint8_t cur_sec_level = SMP_SEC_NONE;
  if (p_device->sec_rec.is_le_device_encrypted()) {
    if (p_device->sec_rec.is_le_device_authenticated()) {
      cur_sec_level = SMP_SEC_AUTHENTICATED;
    } else {
      cur_sec_level = SMP_SEC_UNAUTHENTICATE;
    }
  } else if (p_device->sec_rec.ble_keys.key_type & BTM_LE_KEY_PENC) {  // Bonded
    cur_sec_level = p_device->sec_rec.ble_keys.sec_level;
  } else {
    cur_sec_level = SMP_SEC_NONE;
  }

  // Encrypt if the current security level meets the requirements. Otherwise, pair.
  tBTM_BLE_SEC_REQ_ACT action =
          (cur_sec_level >= req_sec_level) ? BTM_BLE_SEC_REQ_ACT_ENCRYPT : BTM_BLE_SEC_REQ_ACT_PAIR;

  log::debug("addr:{}, auth_req=0x{:x}, cur_sec_level=0x{:x} req_sec_level={} sec_req_act={}",
             bd_addr, auth_req, cur_sec_level, req_sec_level, action);
  return action;
}

/*******************************************************************************
 *
 * Function         btm_ble_set_encryption
 *
 * Description      This function is called to ensure that LE connection is
 *                  encrypted.  Should be called only on an open connection.
 *                  Typically only needed for connections that first want to
 *                  bring up unencrypted links, then later encrypt them.
 *
 * Returns          void
 *                  the local device ER is copied into er
 *
 ******************************************************************************/
tBTM_STATUS btm_ble_set_encryption(const RawAddress& bd_addr, tBTM_BLE_SEC_ACT sec_act,
                                   uint8_t link_role) {
  tBTM_STATUS cmd = tBTM_STATUS::BTM_NO_RESOURCES;
  BtmDevice* p_device = btm_get_dev(bd_addr);

  if (p_device == NULL) {
    log::warn("NULL device record!! sec_act=0x{:x}", sec_act);
    return tBTM_STATUS::BTM_WRONG_MODE;
  }

  log::verbose("sec_act=0x{:x} role_central={}", sec_act, p_device->role_central);

  if (sec_act == BTM_BLE_SEC_ENCRYPT_MITM) {
    p_device->sec_rec.security_required |= BTM_SEC_IN_MITM;
  }

  switch (sec_act) {
    case BTM_BLE_SEC_ENCRYPT:
      if (p_device->sec_rec.is_le_device_encrypted()) {
        return tBTM_STATUS::BTM_SUCCESS;
      }

      if (link_role == HCI_ROLE_CENTRAL) {
        /* start link layer encryption using the security info stored */
        cmd = btm_ble_start_encrypt(bd_addr, false, NULL);
        break;
      }
      /* if salve role then fall through to call SMP_Pair below which will send
         a sec_request to request the central to encrypt the link */
      FALLTHROUGH_INTENDED; /* FALLTHROUGH */
    case BTM_BLE_SEC_ENCRYPT_NO_MITM:
    case BTM_BLE_SEC_ENCRYPT_MITM: {
      tBTM_LE_AUTH_REQ auth_req = (sec_act == BTM_BLE_SEC_ENCRYPT_NO_MITM)
                                          ? SMP_AUTH_BOND
                                          : (SMP_AUTH_BOND | SMP_AUTH_YN_BIT);
      tBTM_BLE_SEC_REQ_ACT sec_req_act = btm_ble_link_sec_check(bd_addr, auth_req);

      if (sec_req_act == BTM_BLE_SEC_REQ_ACT_NONE || sec_req_act == BTM_BLE_SEC_REQ_ACT_DISCARD) {
        log::verbose("no action needed. Ignore");
        cmd = tBTM_STATUS::BTM_SUCCESS;
        break;
      }
      if (link_role == HCI_ROLE_CENTRAL) {
        if (sec_req_act == BTM_BLE_SEC_REQ_ACT_ENCRYPT) {
          cmd = btm_ble_start_encrypt(bd_addr, false, NULL);
          break;
        }
      }

      if (SMP_Pair(bd_addr) == SMP_STARTED) {
        cmd = tBTM_STATUS::BTM_CMD_STARTED;
        p_device->sec_rec.le_link = tSECURITY_STATE::AUTHENTICATING;
      }
      break;
    }
    default:
      cmd = tBTM_STATUS::BTM_WRONG_MODE;
      break;
  }
  return cmd;
}

/*******************************************************************************
 *
 * Function         btm_ble_ltk_request
 *
 * Description      This function is called when encryption request is received
 *                  on a peripheral device.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_ltk_request(uint16_t handle, Octet8 rand, uint16_t ediv) {
  const BtmDevice* p_device = btm_find_dev_by_handle(handle);

  BtmSecurity::Get().ediv_ = ediv;
  BtmSecurity::Get().enc_rand_ = rand;

  if (p_device == NULL) {
    log::warn("No device found for handle 0x{:x}", handle);
    return;
  } else if (smp_proc_ltk_request(p_device->bd_addr)) {
    log::warn("Failed to process LTK request for device {}, handle {}", p_device->bd_addr, handle);
    return;
  }

  log::verbose("handle 0x{:x}", handle);
  btm_ble_ltk_request_reply(p_device->bd_addr, false, Octet16{0});
}

/** This function is called to start LE encryption.
 * Returns tBTM_STATUS::BTM_SUCCESS if encryption was started successfully
 */
tBTM_STATUS btm_ble_start_encrypt(const RawAddress& bda, bool use_stk, Octet16* p_stk) {
  BtmDevice* p_device = btm_get_dev(bda);
  Octet8 dummy_rand = {0};

  log::verbose("bd_addr:{}, use_stk:{}", bda, use_stk);

  if (!p_device) {
    log::error("Link is not active, can not encrypt!");
    return tBTM_STATUS::BTM_WRONG_MODE;
  }

  if (p_device->sec_rec.is_security_state_le_encrypting()) {
    log::warn("LE link encryption is active, Busy!");
    return tBTM_STATUS::BTM_BUSY;
  }

  // Some controllers may not like encrypting both transports at the same time
  bool allow_le_enc_with_bredr = android::sysprop::bluetooth::Ble::allow_enc_with_bredr();
  if (!allow_le_enc_with_bredr && p_device->sec_rec.is_security_state_bredr_encrypting()) {
    log::warn("BR/EDR link encryption is active, Busy!");
    return tBTM_STATUS::BTM_BUSY;
  }

  BtmSecurity::Get().enc_handle_ = p_device->ble_hci_handle;

  if (use_stk) {
    btsnd_hcic_ble_start_enc(p_device->ble_hci_handle, dummy_rand, 0, *p_stk);
  } else if (p_device->sec_rec.ble_keys.key_type & BTM_LE_KEY_PENC) {
    btsnd_hcic_ble_start_enc(p_device->ble_hci_handle, p_device->sec_rec.ble_keys.rand,
                             p_device->sec_rec.ble_keys.ediv, p_device->sec_rec.ble_keys.pltk);
  } else {
    log::error("No key available to encrypt the link");
    return tBTM_STATUS::BTM_ERR_KEY_MISSING;
  }

  if (p_device->sec_rec.le_link == tSECURITY_STATE::IDLE) {
    p_device->sec_rec.le_link = tSECURITY_STATE::ENCRYPTING;
  }

  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         btm_ble_notify_enc_cmpl
 *
 * Description      This function is called to connect EATT and notify GATT to
 *                  send data if any request is pending. This either happens on
 *                  encryption complete event, or if bond is pending, after SMP
 *                  notifies that bonding is complete.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_notify_enc_cmpl(const RawAddress& bd_addr, bool encr_enable) {
  if (encr_enable) {
    uint8_t remote_lmp_version = 0;
    if (!get_btm_client_interface().peer.BTM_ReadRemoteVersion(bd_addr, &remote_lmp_version,
                                                               nullptr, nullptr) ||
        remote_lmp_version == 0) {
      log::warn("BLE Unable to determine remote version");
    }

    if (remote_lmp_version == 0 || remote_lmp_version >= HCI_PROTO_VERSION_5_0) {
      /* Link is encrypted, start EATT if remote LMP version is unknown, or 5.2
       * or greater */
      bluetooth::eatt::EattExtension::GetInstance()->Connect(bd_addr);
    }
  }

  /* to notify GATT to send data if any request is pending */
  gatt_notify_enc_cmpl(bd_addr);
}

/*******************************************************************************
 *
 * Function         btm_ble_link_encrypted
 *
 * Description      This function is called when LE link encryption status is
 *                  changed.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_link_encrypted(const RawAddress& bd_addr, uint8_t encr_enable) {
  BtmDevice* p_device = btm_get_dev(bd_addr);
  bool enc_cback;

  log::verbose("bd_addr:{}, encr_enable={}", bd_addr, encr_enable);

  if (!p_device) {
    log::warn("No Device Found!");
    return;
  }

  enc_cback = p_device->sec_rec.is_security_state_le_encrypting();

  smp_link_encrypted(bd_addr, encr_enable);

  log::verbose("p_device->sec_rec.sec_flags=0x{:x}", p_device->sec_rec.sec_flags);

  if (encr_enable && p_device->sec_rec.le_enc_key_size == 0) {
    p_device->sec_rec.le_enc_key_size = p_device->sec_rec.ble_keys.key_size;
  }

  p_device->sec_rec.le_link = tSECURITY_STATE::IDLE;
  if (p_device->sec_rec.p_callback && enc_cback) {
    if (encr_enable) {
      btm_sec_dev_rec_cback_event(p_device, tBTM_STATUS::BTM_SUCCESS, true);
    } else if (p_device->role_central && (p_device->sec_rec.sec_status == HCI_ERR_KEY_MISSING)) {
      /* LTK missing on peripheral */
      btm_sec_dev_rec_cback_event(p_device, tBTM_STATUS::BTM_ERR_KEY_MISSING, true);
    } else if (!(p_device->sec_rec.sec_flags & BTM_SEC_LE_LINK_KEY_KNOWN)) {
      btm_sec_dev_rec_cback_event(p_device, tBTM_STATUS::BTM_FAILED_ON_SECURITY, true);
    } else if (p_device->role_central) {
      btm_sec_dev_rec_cback_event(p_device, tBTM_STATUS::BTM_ERR_PROCESSING, true);
    }
  }

  BD_NAME remote_name = {};
  /* to notify GATT to send data if any request is pending,
  or if IOP matched, delay notifying until SMP_CMPLT_EVT */
  if (BTM_GetRemoteDeviceName(p_device->ble.pseudo_addr, remote_name) &&
      interop_match_name(INTEROP_SUSPEND_ATT_TRAFFIC_DURING_PAIRING, (const char*)remote_name) &&
      (BtmSecurity::Get().pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE) &&
      BtmSecurity::Get().link_spec_.addrt.bda == p_device->ble.pseudo_addr) {
    log::info(
            "INTEROP_DELAY_ATT_TRAFFIC_DURING_PAIRING: Waiting for bonding to "
            "complete to notify enc complete");
  } else {
    btm_ble_notify_enc_cmpl(p_device->ble.pseudo_addr, encr_enable);
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_ltk_request_reply
 *
 * Description      This function is called to send a LTK request reply on a
 *                  peripheral
 *                  device.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_ltk_request_reply(const RawAddress& bda, bool use_stk, const Octet16& stk) {
  const BtmDevice* p_device = btm_find_dev(bda);

  log::debug("bd_addr:{},use_stk:{}", bda, use_stk);

  if (p_device == NULL) {
    log::error("unknown device");
    return;
  }

  BtmSecurity::Get().enc_handle_ = p_device->ble_hci_handle;
  BtmSecurity::Get().key_size_ = p_device->sec_rec.ble_keys.key_size;

  log::error("key size={}", p_device->sec_rec.ble_keys.key_size);
  if (use_stk) {
    btsnd_hcic_ble_ltk_req_reply(BtmSecurity::Get().enc_handle_, stk);
    return;
  }
  /* calculate LTK using peer device  */
  if (p_device->sec_rec.ble_keys.key_type & BTM_LE_KEY_LENC) {
    btsnd_hcic_ble_ltk_req_reply(BtmSecurity::Get().enc_handle_, p_device->sec_rec.ble_keys.lltk);
    return;
  }

  p_device = btm_find_dev_with_lenc(bda);
  if (!p_device) {
    btsnd_hcic_ble_ltk_req_neg_reply(BtmSecurity::Get().enc_handle_);
    return;
  }

  log::info("Found second sec_dev_rec for device that have LTK");
  /* This can happen when remote established LE connection using RPA to this
   * device, but then pair with us using Classing transport while still keeping
   * LE connection. If remote attempts to encrypt the LE connection, we might
   * end up here. We will eventually consolidate both entries, this is to avoid
   * race conditions. */

  log::assert_that(p_device->sec_rec.ble_keys.key_type & BTM_LE_KEY_LENC,
                   "local encryption key not present");
  BtmSecurity::Get().key_size_ = p_device->sec_rec.ble_keys.key_size;
  btsnd_hcic_ble_ltk_req_reply(BtmSecurity::Get().enc_handle_, p_device->sec_rec.ble_keys.lltk);
}

static void btm_ble_get_auth_req(const BtmDevice* p_device, tBTM_LE_AUTH_REQ* p_auth_req) {
  // If the device is bonded and we are trying to encrypt the link with it as a
  // peripheral, then we need to ensure that the authentication requirements
  // match what was agreed upon during bonding.
  if (BtmSecurity::Get().link_spec_.addrt.bda != p_device->bd_addr &&
      BtmSecurity::Get().link_spec_.addrt.bda != p_device->ble.pseudo_addr) {  // Not pairing
    if (!p_device->role_central && p_device->sec_rec.is_le_link_key_known() &&
        p_device->sec_rec.ble_keys.key_type != BTM_LE_KEY_NONE &&
        p_device->sec_rec.le_link == tSECURITY_STATE::AUTHENTICATING) {
      // Trying to encrypt the link with already bonded device in peripheral role
      if ((p_device->sec_rec.security_required & BTM_SEC_IN_MITM) ||
          p_device->sec_rec.ble_keys.sec_level == SMP_SEC_AUTHENTICATED) {
        // Authentication required or existing bond record was authenticated
        *p_auth_req |= BTM_LE_AUTH_REQ_MITM;
      } else {
        // No authentication required and no bond record
        *p_auth_req &= ~BTM_LE_AUTH_REQ_MITM;
      }

      // Request Secure Connections only if the remote device claim support earlier
      if (p_device->SupportsSecureConnections()) {
        *p_auth_req |= BTM_LE_AUTH_REQ_SC_ONLY;
      } else {
        *p_auth_req &= ~BTM_LE_AUTH_REQ_SC_ONLY;
      }
      return;
    }
  }

  /* Authentication requested? */
  if (p_device->sec_rec.security_required & BTM_SEC_IN_MITM) {
    *p_auth_req |= BTM_LE_AUTH_REQ_MITM;
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_io_capabilities_req
 *
 * Description      This function is called to handle SMP get IO capability
 *                  request.
 *
 * Returns          void
 *
 ******************************************************************************/
static tBTM_STATUS btm_ble_io_capabilities_req(BtmDevice* p_device, tBTM_LE_IO_REQ* p_data) {
  log::verbose("p_device->bd_addr:{}", p_device->bd_addr);
  tBTM_STATUS status = (BtmSecurity::Get().app_->le_callback)(BTM_LE_IO_REQ_EVT, p_device->bd_addr,
                                                              (tBTM_LE_EVT_DATA*)p_data);
  if (status != tBTM_STATUS::BTM_SUCCESS) {
    log::warn("Security callback failed {} for {}", btm_status_text(status), p_device->bd_addr);
    return status;
  }

  if (BTM_OOB_UNKNOWN == p_data->oob_data) {
    return tBTM_STATUS::BTM_SUCCESS;
  }

  p_data->auth_req &= BTM_LE_AUTH_REQ_MASK;
  log::verbose("1:p_device->sec_rec.security_required={}, auth_req:{}",
               p_device->sec_rec.security_required, p_data->auth_req);
  log::verbose("2:i_keys=0x{:x} r_keys=0x{:x} (bit 0-LTK 1-IRK 2-CSRK)", p_data->init_keys,
               p_data->resp_keys);

  btm_ble_get_auth_req(p_device, &p_data->auth_req);

  if (!(p_data->auth_req & SMP_AUTH_BOND)) {
    log::verbose("Non bonding: No keys should be exchanged");
    p_data->init_keys = 0;
    p_data->resp_keys = 0;
  }

  log::verbose("3:auth_req:{}", p_data->auth_req);
  log::verbose("4:i_keys=0x{:x} r_keys=0x{:x}", p_data->init_keys, p_data->resp_keys);

  log::verbose("5:p_data->io_cap={} auth_req:{}", p_data->io_cap, p_data->auth_req);

  /* remove MITM protection requirement if IO cap does not allow it */
  if (p_data->io_cap == BtIoCap::NO_INPUT_NO_OUTPUT && p_data->oob_data == SMP_OOB_NONE) {
    p_data->auth_req &= ~BTM_LE_AUTH_REQ_MITM;
  }

  if (!(p_data->auth_req & SMP_SC_SUPPORT_BIT)) {
    /* if Secure Connections are not supported then remove LK derivation,
    ** and keypress notifications.
    */
    log::verbose("SC not supported -> No LK derivation, no keypress notifications");
    p_data->auth_req &= ~SMP_KP_SUPPORT_BIT;
    p_data->init_keys &= ~SMP_SEC_KEY_TYPE_LK;
    p_data->resp_keys &= ~SMP_SEC_KEY_TYPE_LK;
  }

  log::verbose("6:IO_CAP:{} oob_data:{} auth_req:0x{:02x}", p_data->io_cap, p_data->oob_data,
               p_data->auth_req);

  return tBTM_STATUS::BTM_SUCCESS;
}

/*******************************************************************************
 *
 * Function         btm_ble_br_keys_req
 *
 * Description      This function is called to handle SMP request for keys sent
 *                  over BR/EDR.
 *
 * Returns          void
 *
 ******************************************************************************/
static tBTM_STATUS btm_ble_br_keys_req(BtmDevice* p_device, tBTM_LE_IO_REQ* p_data) {
  tBTM_STATUS callback_rc = tBTM_STATUS::BTM_SUCCESS;
  log::verbose("p_device->bd_addr:{}", p_device->bd_addr);
  *p_data = tBTM_LE_IO_REQ{
          .io_cap = BtIoCap::IO_CAP_UNKNOWN,
          .oob_data = false,
          .auth_req = BTM_LE_AUTH_REQ_SC_MITM_BOND,
          .max_key_size = BTM_BLE_MAX_KEY_SIZE,
          .init_keys = SMP_BR_SEC_DEFAULT_KEY,
          .resp_keys = SMP_BR_SEC_DEFAULT_KEY,
  };

  if (osi_property_get_bool(kPropertyCtkdDisableCsrkDistribution, false)) {
    p_data->init_keys &= (~SMP_SEC_KEY_TYPE_CSRK);
    p_data->resp_keys &= (~SMP_SEC_KEY_TYPE_CSRK);
  }

  return callback_rc;
}

/*******************************************************************************
 *
 * Function         btm_ble_connected
 *
 * Description      This function is called on LE connection
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_connected(const RawAddress& bda, uint16_t handle, uint8_t /* enc_mode */, uint8_t role,
                       tBLE_ADDR_TYPE addr_type, bool can_read_discoverable_characteristics) {
  BtmDevice* p_device = btm_find_or_alloc_dev(bda);

  log::info("Update timestamp for ble connection:{}", bda);
  // TODO () Why is timestamp a counter ?
  p_device->timestamp = BtmSecurity::Get().dev_rec_count_++;

  if (is_ble_addr_type_known(addr_type)) {
    p_device->ble.SetAddressType(addr_type);
  } else {
    log::warn("Please do not update device record from anonymous le advertisement");
  }

  p_device->ble.pseudo_addr = bda;
  p_device->ble_hci_handle = handle;
  p_device->device_type |= BT_DEVICE_TYPE_BLE;
  p_device->role_central = (role == HCI_ROLE_CENTRAL) ? true : false;
  p_device->can_read_discoverable = can_read_discoverable_characteristics;

  if (!p_device->sec_rec.is_bonded(BT_TRANSPORT_LE)) {
    p_device->ble.active_addr_type = BTM_BLE_ADDR_PSEUDO;
    if (p_device->ble.AddressType() == BLE_ADDR_RANDOM) {
      p_device->ble.cur_rand_addr = bda;
    }
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_connection_established
 *
 * Description      This function when LE connection is established
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_connection_established(const RawAddress& bda) {
  const BtmDevice* p_device = btm_find_dev(bda);
  if (p_device == nullptr) {
    log::warn("No security record for {}", bda);
    return;
  }

  // Encrypt the link if device is bonded
  if (p_device->sec_rec.is_le_link_key_known()) {
    btm_ble_set_encryption(bda, BTM_BLE_SEC_ENCRYPT,
                           p_device->role_central ? HCI_ROLE_CENTRAL : HCI_ROLE_PERIPHERAL);
  }

  // Read device name if it is not known already, we may need it for pairing
  if (!p_device->sec_rec.is_name_known()) {
    btm_ble_read_remote_name(bda, nullptr);
  }

  if (p_device != nullptr) {
    // Unknown device
    if (p_device->dev_class == kDevClassEmpty || p_device->dev_class == kDevClassUnclassified) {
      // Class of device not known, read appearance characteristic ...
      // Unless it is one of those devices which don't respond to this request
      BD_NAME remote_name = {};
      if (p_device->sec_rec.is_name_known() && BTM_GetRemoteDeviceName(bda, remote_name) &&
          interop_match_name(INTEROP_DISABLE_READ_LE_APPEARANCE, (const char*)remote_name)) {
        log::warn("Name {} matches IOP database, not reading appearance for {}",
                  (const char*)remote_name, bda);
      } else if (interop_match_manufacturer(INTEROP_DISABLE_READ_LE_APPEARANCE, p_device->remote_version_info.manufacturer)) {
        log::warn("Manufacturer {} matches IOP database, not reading appearance for {}",
                  p_device->remote_version_info.manufacturer, bda);
      } else {
        btm_ble_read_remote_cod(bda);
      }
    }
  }
}

static bool btm_ble_complete_evt_ignore(const BtmDevice* p_device, const tBTM_LE_EVT_DATA* p_data) {
  // Peripheral role: Encryption request results in SMP Security request. SMP may generate a
  // SMP_COMPLT_EVT failure event cases like below:
  // 1) Some central devices don't handle cross-over between encryption and SMP security request
  // 2) Link may get disconnected after the SMP security request was sent.
  //
  // Central role: SMP may generate a SMP_COMPLT_EVT if encryption refresh fails.
  if (p_data->complt.reason != SMP_SUCCESS &&
      BtmSecurity::Get().link_spec_.addrt.bda != p_device->bd_addr &&
      BtmSecurity::Get().link_spec_.addrt.bda != p_device->ble.pseudo_addr &&
      p_device->sec_rec.is_le_link_key_known() &&
      p_device->sec_rec.ble_keys.key_type != BTM_LE_KEY_NONE) {
    if (p_device->sec_rec.is_le_device_encrypted()) {
      log::warn("Bonded device {} is already encrypted, ignoring SMP failure", p_device->bd_addr);
      return true;
    } else if (p_data->complt.reason == SMP_CONN_TOUT) {
      log::warn("Bonded device {} disconnected while waiting for encryption, ignoring SMP failure",
                p_device->bd_addr);
      l2cu_start_post_bond_timer(p_device->ble_hci_handle);
      return true;
    } else if (!p_device->role_central) {
      log::warn("Peripheral encryption request failed for the bonded device {} with reason {}",
                p_device->bd_addr, smp_status_text(p_data->complt.reason));
      btm_sec_disconnect(p_device->ble_hci_handle, HCI_ERR_AUTH_FAILURE,
                         smp_status_text(p_data->complt.reason));
      return true;
    }
  }

  return false;
}

static void btm_ble_user_confirmation_req(const RawAddress& bd_addr, BtmDevice* p_device,
                                          tBTM_LE_EVT event, tBTM_LE_EVT_DATA* p_data) {
  if (com_android_bluetooth_flags_prevent_btm_sec_cb_overwrite_during_pairing() &&
      BtmSecurity::Get().pairing_state_ != BTM_PAIR_STATE_IDLE &&
      (bd_addr != BtmSecurity::Get().link_spec_.addrt.bda ||
       BT_TRANSPORT_LE != BtmSecurity::Get().link_spec_.transport)) {
    log::warn("Already in pairing state, ignoring user confirmation request from {}", bd_addr);
    return;
  }
  p_device->sec_rec.sec_flags |= BTM_SEC_LE_AUTHENTICATED;
  p_device->sec_rec.le_link = tSECURITY_STATE::AUTHENTICATING;
  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;
  BtmSecurity::Get().pairing_flags_ |= BTM_PAIR_FLAGS_LE_ACTIVE;
  BTM_BLE_SEC_CALLBACK(event, bd_addr, p_data);
}

static void btm_ble_sec_req(const RawAddress& bd_addr, BtmDevice* p_device,
                            tBTM_LE_EVT_DATA* p_data) {
  if (BtmSecurity::Get().pairing_state_ != BTM_PAIR_STATE_IDLE) {
    log::warn("Already in pairing state, ignoring pairing request from {}", bd_addr);
    return;
  }
  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;
  p_device->sec_rec.le_link = tSECURITY_STATE::AUTHENTICATING;
  BtmSecurity::Get().pairing_flags_ |= BTM_PAIR_FLAGS_LE_ACTIVE;
  BTM_BLE_SEC_CALLBACK(BTM_LE_SEC_REQUEST_EVT, bd_addr, p_data);
}

static void btm_ble_consent_req(const RawAddress& bd_addr, tBTM_LE_EVT_DATA* p_data) {
  if (com_android_bluetooth_flags_prevent_btm_sec_cb_overwrite_during_pairing() &&
      BtmSecurity::Get().pairing_state_ != BTM_PAIR_STATE_IDLE &&
      (bd_addr != BtmSecurity::Get().link_spec_.addrt.bda ||
       BT_TRANSPORT_LE != BtmSecurity::Get().link_spec_.transport)) {
    log::warn("Already in pairing state, ignoring pairing request from {}", bd_addr);
    return;
  }
  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;
  BtmSecurity::Get().pairing_flags_ |= BTM_PAIR_FLAGS_LE_ACTIVE;
  BTM_BLE_SEC_CALLBACK(BTM_LE_CONSENT_REQ_EVT, bd_addr, p_data);
}

static void btm_ble_complete_evt(const RawAddress& bd_addr, BtmDevice* p_device,
                                 tBTM_LE_EVT_DATA* p_data) {
  if (btm_ble_complete_evt_ignore(p_device, p_data)) {
    return;
  }

  BTM_BLE_SEC_CALLBACK(BTM_LE_COMPLT_EVT, bd_addr, p_data);

  if (p_data->complt.smp_over_br) {
    log::verbose("SMP over BR completed");
    p_device->sec_rec.bredr_sc_enc_reason = BtmSecurityRecord::BrEdrScEncReason::OTHER;
  }

  /* Reset BTM state if the callback address matches pairing address */
  if (bd_addr == BtmSecurity::Get().link_spec_.addrt.bda) {
    BtmSecurity::Get().change_pairing_state(BTM_PAIR_STATE_IDLE);
    BtmSecurity::Get().link_spec_ = {};
    BtmSecurity::Get().link_spec_.addrt.bda = RawAddress::kAny;
    BtmSecurity::Get().pairing_flags_ = 0;
    BtmSecurity::Get().ResetLinkKeyRequestTimer();
  }

  p_device = btm_get_dev(bd_addr);  // BTM_LE_COMPLT_EVT event may have removed the device
  if (p_device == nullptr) {
    log::warn("Device record removed {}", bd_addr);
    return;
  }

  if (p_device->role_switch_pending == BtmDevice::RoleSwitchPending::kAfterCtkd &&
      p_data->complt.smp_over_br) {
    tHCI_ROLE role = HCI_ROLE_UNKNOWN;
    if (get_btm_client_interface().link_policy.BTM_GetRole(bd_addr, BT_TRANSPORT_BR_EDR, &role) !=
        tBTM_STATUS::BTM_SUCCESS) {
      log::warn("Unable to get link policy role peer:{}", bd_addr);
    }

    if (role == HCI_ROLE_PERIPHERAL) {
      if (get_btm_client_interface().link_policy.BTM_SwitchRoleToCentral(
                  p_device->RemoteAddress()) == tBTM_STATUS::BTM_CMD_STARTED) {
        log::info("Trying to switch role to central peer: {}", bd_addr);
      } else {
        log::warn("Unable to switch role to central peer:{}", bd_addr);
      }
    }
    p_device->role_switch_pending = BtmDevice::RoleSwitchPending::kNone;
  }

  log::verbose("before update sec_level=0x{:x} sec_flags=0x{:x}", p_data->complt.sec_level,
               p_device->sec_rec.sec_flags);

  tBTM_STATUS res = (p_data->complt.reason == SMP_SUCCESS) ? tBTM_STATUS::BTM_SUCCESS
                                                           : tBTM_STATUS::BTM_ERR_PROCESSING;

  log::verbose("after update result={} sec_level=0x{:x} sec_flags=0x{:x}", res,
               p_data->complt.sec_level, p_device->sec_rec.sec_flags);

  if (p_data->complt.is_pair_cancel) {
    log::verbose("Pairing Cancel completed");
    (BtmSecurity::Get().app_->bond_cancel_cmpl_callback)(tBTM_STATUS::BTM_SUCCESS);
  }

  if (res != tBTM_STATUS::BTM_SUCCESS && p_data->complt.reason != SMP_CONN_TOUT) {
    log::verbose("Pairing failed - prepare to remove ACL");

    if (p_data->complt.reason == SMP_RSP_TIMEOUT) {
      stack::l2cap::get_interface().L2CA_SetIdleTimeoutByBdAddr(p_device->bd_addr, 0,
                                                                BT_TRANSPORT_LE);
    }

    l2cu_start_post_bond_timer(p_device->ble_hci_handle);
  }

  log::verbose("BtmSecurity::Get().pairing_state_={:x} pairing_flags={:x} ",
               BtmSecurity::Get().pairing_state_, BtmSecurity::Get().pairing_flags_);

  if (res == tBTM_STATUS::BTM_SUCCESS) {
    p_device->sec_rec.le_link = tSECURITY_STATE::IDLE;

    if (p_device->sec_rec.bond_type != BOND_TYPE_TEMPORARY) {
      // Add all bonded device into resolving list if IRK is available.
      btm_ble_resolving_list_load_dev(*p_device);
    } else if (p_device->ble_hci_handle == HCI_INVALID_HANDLE) {
      // At this point LTK should have been dropped by btif.
      // Reset the flags here if LE is not connected (over BR),
      // otherwise they would be reset on disconnected.
      log::debug(
              "SMP over BR triggered by temporary bond has completed, "
              "resetting the LK flags");
      p_device->sec_rec.sec_flags &= ~(BTM_SEC_LE_LINK_KEY_KNOWN);
      p_device->sec_rec.ble_keys.key_type = BTM_LE_KEY_NONE;
    }
  }
  BD_NAME remote_name = {};
  if (BTM_GetRemoteDeviceName(p_device->ble.pseudo_addr, remote_name) &&
      interop_match_name(INTEROP_SUSPEND_ATT_TRAFFIC_DURING_PAIRING, (const char*)remote_name)) {
    log::debug("Notifying encryption cmpl delayed due to IOP match");
    btm_ble_notify_enc_cmpl(p_device->ble.pseudo_addr, true);
  }

  btm_sec_dev_rec_cback_event(p_device, res, true);
}

static tBTM_STATUS btm_ble_sirk_verification_req(const RawAddress& bd_addr) {
  tBTM_STATUS res = (BtmSecurity::Get().app_->sirk_verification_callback)(bd_addr);
  if (res == tBTM_STATUS::BTM_CMD_STARTED) {
    res = tBTM_STATUS::BTM_SUCCESS;
  } else {
    log::warn("SMP SIRK verification status:{}", btm_status_text(res));
  }
  return res;
}

/*****************************************************************************
 *  Function        btm_proc_smp_cback
 *
 *  Description     This function is the SMP callback handler.
 *
 *****************************************************************************/
tBTM_STATUS btm_proc_smp_cback(tSMP_EVT event, const RawAddress& bd_addr, tSMP_EVT_DATA* p_data) {
  log::verbose("bd_addr:{}, event={}", bd_addr, smp_evt_to_text(event));

  if (event == SMP_SC_LOC_OOB_DATA_UP_EVT) {
    btm_sec_cr_loc_oob_data_cback_event(RawAddress{}, p_data->loc_oob_data);
    return tBTM_STATUS::BTM_SUCCESS;
  }

  BtmDevice* p_device = btm_get_dev(bd_addr);

  if (p_device == nullptr) {
    log::warn("Unexpected event '{}' for unknown device.", smp_evt_to_text(event));
    if (bd_addr == BtmSecurity::Get().link_spec_.addrt.bda && event == SMP_COMPLT_EVT) {
      BtmSecurity::Get().change_pairing_state(BTM_PAIR_STATE_IDLE);
      BtmSecurity::Get().link_spec_ = {};
      BtmSecurity::Get().link_spec_.addrt.bda = RawAddress::kAny;
      BtmSecurity::Get().pairing_flags_ = 0;
      BtmSecurity::Get().ResetLinkKeyRequestTimer();
    }
    return tBTM_STATUS::BTM_UNKNOWN_ADDR;
  }

  tBTM_STATUS status = tBTM_STATUS::BTM_SUCCESS;
  switch (event) {
    case SMP_IO_CAP_REQ_EVT:
      btm_ble_io_capabilities_req(p_device, reinterpret_cast<tBTM_LE_IO_REQ*>(&p_data->io_req));
      break;

    case SMP_BR_KEYS_REQ_EVT:
      btm_ble_br_keys_req(p_device, reinterpret_cast<tBTM_LE_IO_REQ*>(&p_data->io_req));
      break;

    case SMP_PASSKEY_REQ_EVT:
    case SMP_PASSKEY_NOTIF_EVT:
    case SMP_OOB_REQ_EVT:
    case SMP_NC_REQ_EVT:
    case SMP_SC_OOB_REQ_EVT:
      btm_ble_user_confirmation_req(bd_addr, p_device, static_cast<tBTM_LE_EVT>(event),
                                    reinterpret_cast<tBTM_LE_EVT_DATA*>(p_data));
      break;

    case SMP_SEC_REQUEST_EVT:
      btm_ble_sec_req(bd_addr, p_device, reinterpret_cast<tBTM_LE_EVT_DATA*>(p_data));
      break;

    case SMP_CONSENT_REQ_EVT:
      btm_ble_consent_req(bd_addr, reinterpret_cast<tBTM_LE_EVT_DATA*>(p_data));
      break;

    case SMP_COMPLT_EVT:
      btm_ble_complete_evt(bd_addr, p_device, reinterpret_cast<tBTM_LE_EVT_DATA*>(p_data));
      break;

    case SMP_LE_ADDR_ASSOC_EVT:
      BTM_BLE_SEC_CALLBACK(static_cast<tBTM_LE_EVT>(event), bd_addr,
                           reinterpret_cast<tBTM_LE_EVT_DATA*>(p_data));
      break;

    case SMP_SIRK_VERIFICATION_REQ_EVT:
      status = btm_ble_sirk_verification_req(bd_addr);
      break;

    default:
      log::verbose("unknown event={}", smp_evt_to_text(event));
      break;
  }

  return status;
}

/*******************************************************************************
 *
 * Function         BTM_BleDataSignature
 *
 * Description      This function is called to sign the data using AES128 CMAC
 *                  algorithm.
 *
 * Parameter        bd_addr: target device the data to be signed for.
 *                  p_text: singing data
 *                  len: length of the data to be signed.
 *                  signature: output parameter where data signature is going to
 *                             be stored.
 *
 * Returns          true if signing successful, otherwise false.
 *
 ******************************************************************************/
bool btm_ble_data_signature(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                            BLE_SIGNATURE signature) {
  BtmDevice* p_device = btm_get_dev(bd_addr);

  if (p_device == NULL) {
    log::error("data signing can not be done from unknown device");
    return false;
  }

  uint8_t* p_mac = (uint8_t*)signature;
  uint8_t* pp;
  uint8_t* p_buf = (uint8_t*)osi_malloc(len + 4);

  pp = p_buf;
  /* prepare plain text */
  if (p_text) {
    memcpy(p_buf, p_text, len);
    pp = (p_buf + len);
  }

  UINT32_TO_STREAM(pp, p_device->sec_rec.ble_keys.local_counter);
  UINT32_TO_STREAM(p_mac, p_device->sec_rec.ble_keys.local_counter);

  crypto_toolbox::aes_cmac(p_device->sec_rec.ble_keys.lcsrk, p_buf, (uint16_t)(len + 4),
                           BTM_CMAC_TLEN_SIZE, p_mac);
  p_device->sec_rec.increment_sign_counter(true);

  log::verbose("p_mac = {}", std::format_ptr(p_mac));
  log::verbose("p_mac[0]=0x{:02x} p_mac[1]=0x{:02x} p_mac[2]=0x{:02x} p_mac[3]=0x{:02x}", *p_mac,
               *(p_mac + 1), *(p_mac + 2), *(p_mac + 3));
  log::verbose("p_mac[4]=0x{:02x} p_mac[5]=0x{:02x} p_mac[6]=0x{:02x} p_mac[7]=0x{:02x}",
               *(p_mac + 4), *(p_mac + 5), *(p_mac + 6), *(p_mac + 7));
  osi_free(p_buf);
  return true;
}

/*******************************************************************************
 *
 * Function         BTM_BleVerifySignature
 *
 * Description      This function is called to verify the data signature
 *
 * Parameter        bd_addr: target device the data to be signed for.
 *                  p_orig:  original data before signature.
 *                  len: length of the signing data
 *                  counter: counter used when doing data signing
 *                  p_comp: signature to be compared against.

 * Returns          true if signature verified correctly; otherwise false.
 *
 ******************************************************************************/
bool btm_ble_verify_signature(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len,
                              uint32_t counter, uint8_t* p_comp) {
  bool verified = false;
  BtmDevice* p_device = btm_get_dev(bd_addr);
  uint8_t p_mac[BTM_CMAC_TLEN_SIZE];

  if (p_device == NULL || (p_device && !(p_device->sec_rec.ble_keys.key_type & BTM_LE_KEY_PCSRK))) {
    log::error("can not verify signature for unknown device");
  } else if (counter < p_device->sec_rec.ble_keys.counter) {
    log::error("signature received with out dated sign counter");
  } else if (p_orig == NULL) {
    log::error("No signature to verify");
  } else {
    log::verbose("rcv_cnt={} >= expected_cnt={}", counter, p_device->sec_rec.ble_keys.counter);

    crypto_toolbox::aes_cmac(p_device->sec_rec.ble_keys.pcsrk, p_orig, len, BTM_CMAC_TLEN_SIZE,
                             p_mac);
    if (CRYPTO_memcmp(p_mac, p_comp, BTM_CMAC_TLEN_SIZE) == 0) {
      p_device->sec_rec.increment_sign_counter(false);
      verified = true;
    }
  }
  return verified;
}

/*******************************************************************************
 *
 * Function         BTM_BleSirkConfirmDeviceReply
 *
 * Description      This procedure confirms requested to validate set device.
 *
 * Parameter        bd_addr     - BD address of the peer
 *                  res         - confirmation result tBTM_STATUS::BTM_SUCCESS if success
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_sirk_confirm_device_reply(const RawAddress& bd_addr, tBTM_STATUS res) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);
  tSMP_STATUS res_smp = (res == tBTM_STATUS::BTM_SUCCESS) ? SMP_SUCCESS : SMP_FAIL;

  log::info("bd_addr:{}, result:{}", bd_addr, smp_status_text(res_smp));

  if (p_device == nullptr) {
    log::error("Confirmation of Unknown device");
    return;
  }

  BTM_LogHistory(kBtmLogTag, bd_addr, "SIRK confirmation",
                 std::format("status:{}", smp_status_text(res_smp)));
  SMP_SirkConfirmDeviceReply(bd_addr, res_smp);
}

/*******************************************************************************
 *  Utility functions for LE device IR/ER generation
 ******************************************************************************/
/** This function is to notify application new keys have been generated. */
static void btm_notify_new_key(uint8_t key_type) {
  if (BtmSecurity::Get().app_ == nullptr) {
    log::warn("No app registered");
    return;
  }

  tBTM_BLE_LOCAL_KEYS* p_local_keys = nullptr;

  log::verbose("key_type={}", key_type);

  switch (key_type) {
    case BTM_BLE_KEY_TYPE_ID:
      log::verbose("BTM_BLE_KEY_TYPE_ID");
      p_local_keys = (tBTM_BLE_LOCAL_KEYS*)&BtmSecurity::Get().devcb_.id_keys;
      break;

    case BTM_BLE_KEY_TYPE_ER:
      log::verbose("BTM_BLE_KEY_TYPE_ER");
      p_local_keys = (tBTM_BLE_LOCAL_KEYS*)&BtmSecurity::Get().devcb_.ble_encryption_key_value;
      break;

    default:
      log::error("unknown key type: {}", key_type);
      break;
  }

  if (p_local_keys != nullptr) {
    (BtmSecurity::Get().app_->le_key_callback)(key_type, p_local_keys);
  }
}

/** implementation of btm_ble_reset_id */
static void btm_ble_reset_id_impl(const Octet16& rand1, const Octet16& rand2) {
  /* Regenerate Identity Root */
  BtmSecurity::Get().devcb_.id_keys.ir = rand1;
  Octet16 btm_ble_dhk_pt{};
  btm_ble_dhk_pt[0] = 0x03;

  /* generate DHK= Eir({0x03, 0x00, 0x00 ...}) */
  BtmSecurity::Get().devcb_.id_keys.dhk =
          crypto_toolbox::aes_128(BtmSecurity::Get().devcb_.id_keys.ir, btm_ble_dhk_pt);

  Octet16 btm_ble_irk_pt{};
  btm_ble_irk_pt[0] = 0x01;
  /* IRK = D1(IR, 1) */
  BtmSecurity::Get().devcb_.id_keys.irk =
          crypto_toolbox::aes_128(BtmSecurity::Get().devcb_.id_keys.ir, btm_ble_irk_pt);

  btm_notify_new_key(BTM_BLE_KEY_TYPE_ID);

  /* proceed generate ER */
  BtmSecurity::Get().devcb_.ble_encryption_key_value = rand2;
  btm_notify_new_key(BTM_BLE_KEY_TYPE_ER);

  /* if privacy is enabled, update the irk and RPA in the LE address manager */
  if (btm_cb.ble_ctr_cb.privacy_mode != BTM_PRIVACY_NONE) {
    BTM_BleConfigPrivacy(true);
  }
}

struct reset_id_data {
  Octet16 rand1;
  Octet16 rand2;
};

/** This function is called to reset LE device identity. */
void btm_ble_reset_id(void) {
  log::verbose("btm_ble_reset_id");

  /* In order to reset identity, we need four random numbers. Make four nested
   * calls to generate them first, then proceed to perform the actual reset in
   * btm_ble_reset_id_impl. */
  btsnd_hcic_ble_rand(base::BindOnce([](Octet8 rand) {
    reset_id_data tmp;
    memcpy(tmp.rand1.data(), rand.data(), kOctet8Length);
    btsnd_hcic_ble_rand(base::BindOnce(
            [](reset_id_data tmp, Octet8 rand) {
              memcpy(tmp.rand1.data() + kOctet8Length, rand.data(), kOctet8Length);
              btsnd_hcic_ble_rand(base::BindOnce(
                      [](reset_id_data tmp, Octet8 rand) {
                        memcpy(tmp.rand2.data(), rand.data(), kOctet8Length);
                        btsnd_hcic_ble_rand(base::BindOnce(
                                [](reset_id_data tmp, Octet8 rand) {
                                  memcpy(tmp.rand2.data() + kOctet8Length, rand.data(),
                                         kOctet8Length);
                                  // when all random numbers are ready, do the actual reset.
                                  btm_ble_reset_id_impl(tmp.rand1, tmp.rand2);
                                },
                                tmp));
                      },
                      tmp));
            },
            tmp));
  }));
}

/*******************************************************************************
 *
 * Function         btm_ble_get_acl_remote_addr
 *
 * Description      This function reads the active remote address used for the
 *                  connection.
 *
 * Returns          success return true, otherwise false.
 *
 ******************************************************************************/
bool btm_ble_get_acl_remote_addr(uint16_t hci_handle, RawAddress& conn_addr,
                                 tBLE_ADDR_TYPE* p_addr_type) {
  const BtmDevice* p_device = btm_find_dev_by_handle(hci_handle);
  if (p_device == nullptr) {
    log::warn("Unable to find security device record hci_handle:{}", hci_handle);
    // TODO Release acl resource
    return false;
  }

  bool st = true;

  switch (p_device->ble.active_addr_type) {
    case BTM_BLE_ADDR_PSEUDO:
      conn_addr = p_device->bd_addr;
      *p_addr_type = p_device->ble.AddressType();
      break;

    case BTM_BLE_ADDR_RRA:
      conn_addr = p_device->ble.cur_rand_addr;
      *p_addr_type = BLE_ADDR_RANDOM;
      break;

    case BTM_BLE_ADDR_STATIC:
      conn_addr = p_device->ble.identity_address_with_type.bda;
      *p_addr_type = p_device->ble.identity_address_with_type.type;
      break;

    default:
      log::warn("Unable to find record with active address type:{}",
                p_device->ble.active_addr_type);
      st = false;
      break;
  }
  return st;
}

std::optional<Octet16> btm_ble_get_peer_ltk(const RawAddress address) {
  const BtmDevice* p_device = btm_find_dev(address);
  if (p_device == nullptr) {
    return std::nullopt;
  }

  return p_device->sec_rec.ble_keys.pltk;
}

std::optional<Octet16> btm_ble_get_peer_irk(const RawAddress address) {
  const BtmDevice* p_device = btm_find_dev(address);
  if (p_device == nullptr) {
    return std::nullopt;
  }

  return p_device->sec_rec.ble_keys.irk;
}

std::optional<tBLE_BD_ADDR> btm_ble_get_identity_address(const RawAddress address) {
  const BtmDevice* p_device = btm_find_dev(address);
  if (p_device == nullptr) {
    return std::nullopt;
  }

  return p_device->ble.identity_address_with_type;
}
