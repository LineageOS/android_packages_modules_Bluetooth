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

#define LOG_TAG "BtmSecurity"

#include "stack/btm/btm_security.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "internal_include/stack_config.h"
#include "osi/include/allocator.h"
#include "osi/include/list.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_device_record.h"
#include "stack/include/bt_psm_types.h"

using namespace bluetooth;

BtmSecurity& BtmSecurity::Get() {
  static BtmSecurity control_block;
  return control_block;
}

void BtmSecurity::Init() {
  Init(stack_config_get_interface()->get_pts_secure_only_mode() ? BTM_SEC_MODE_SC
                                                                : BTM_SEC_MODE_SP);
}

void BtmSecurity::Init(uint8_t initial_security_mode) {
  *this = {};

  connecting_bda_ = RawAddress::kEmpty;
  connecting_dc_ = kDevClassEmpty;
  sec_collision_timer_ = alarm_new("btm.sec_collision_timer_");
  pairing_timer_ = alarm_new("btm.pairing_timer_");
  security_mode_ = initial_security_mode;
  log::debug("BtmSecurity initialized with mode: {}",
             security_mode_text(static_cast<tSECURITY_MODE>(initial_security_mode)));
  link_spec_.addrt.bda = RawAddress::kAny;
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    sec_dev_rec_ = list_new([](void* ptr) {
      // Invoke destructor for all record objects and reset to default
      // initialized value so memory may be properly freed
      *((BtmDevice*)ptr) = {};
      osi_free(ptr);
    });
    return;
  }
}

void BtmSecurity::Free() {
  service_access_q_.clear();
  enc_request_q_.clear();

  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    list_free(sec_dev_rec_);
    sec_dev_rec_ = nullptr;
  } else {
    device_records_ = {};
  }

  alarm_free(sec_collision_timer_);
  sec_collision_timer_ = nullptr;

  alarm_free(pairing_timer_);
  pairing_timer_ = nullptr;
}

/*******************************************************************************
 *
 * Function         find_first_serv_rec
 *
 * Description      Look for the first record in the service database
 *                  with specified PSM
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
tBTM_SEC_SERV_REC* BtmSecurity::find_first_serv_rec(bool outgoing, uint16_t psm) {
  tBTM_SEC_SERV_REC* p_serv_rec = &sec_serv_rec_[0];
  int i;

  if (outgoing && p_out_serv_ && p_out_serv_->psm == psm) {
    /* If this is outgoing connection and the PSM matches p_out_serv_,
     * use it as the current service */
    return p_out_serv_;
  }

  /* otherwise, just find the first record with the specified PSM */
  for (i = 0; i < BTM_SEC_MAX_SERVICE_RECORDS; i++, p_serv_rec++) {
    if ((p_serv_rec->security_flags & BTM_SEC_IN_USE) && (p_serv_rec->psm == psm)) {
      return p_serv_rec;
    }
  }
  return NULL;
}

const BtmSecurityRecord* BtmSecurity::findSecRec(const RawAddress bd_addr) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);
  if (p_device != nullptr) {
    return &p_device->sec_rec;
  }
  return nullptr;
}

bool BtmSecurity::IsDeviceEncrypted(const RawAddress bd_addr, tBT_TRANSPORT transport) {
  const BtmSecurityRecord* sec_rec = findSecRec(bd_addr);
  if (sec_rec) {
    if (transport == BT_TRANSPORT_BR_EDR) {
      return sec_rec->is_device_encrypted();
    } else if (transport == BT_TRANSPORT_LE) {
      return sec_rec->is_le_device_encrypted();
    }
    log::error("unknown transport:{}", bt_transport_text(transport));
    return false;
  }

  log::error("unknown device:{}", bd_addr);
  return false;
}

bool BtmSecurity::IsLinkKeyAuthenticated(const RawAddress bd_addr, tBT_TRANSPORT transport) {
  const BtmSecurityRecord* sec_rec = findSecRec(bd_addr);
  if (sec_rec) {
    if (transport == BT_TRANSPORT_BR_EDR) {
      return sec_rec->is_link_key_authenticated();
    } else if (transport == BT_TRANSPORT_LE) {
      return sec_rec->is_le_link_key_authenticated();
    }
    log::error("unknown transport:{}", bt_transport_text(transport));
    return false;
  }

  log::error("unknown device:{}", bd_addr);
  return false;
}

bool BtmSecurity::IsDeviceAuthenticated(const RawAddress bd_addr, tBT_TRANSPORT transport) {
  const BtmSecurityRecord* sec_rec = findSecRec(bd_addr);
  if (sec_rec) {
    if (transport == BT_TRANSPORT_BR_EDR) {
      return sec_rec->is_device_authenticated();
    } else if (transport == BT_TRANSPORT_LE) {
      return sec_rec->is_le_device_authenticated();
    }
    log::error("unknown transport:{}", bt_transport_text(transport));
    return false;
  }

  log::error("unknown device:{}", bd_addr);
  return false;
}

bool BtmSecurity::IsDeviceBonded(const RawAddress bd_addr, tBT_TRANSPORT transport) {
  const BtmSecurityRecord* sec_rec = findSecRec(bd_addr);
  if (sec_rec == nullptr) {
    log::verbose("No record for {}", bd_addr);
    return false;
  }

  return sec_rec->is_bonded(transport);
}

#define BTM_NO_AVAIL_SEC_SERVICES ((uint16_t)0xffff)
bool BtmSecurity::AddService(bool outgoing, const char* p_name, uint8_t service_id,
                             uint16_t sec_level, uint16_t psm, uint32_t mx_proto_id,
                             uint32_t mx_chan_id) {
  tBTM_SEC_SERV_REC* p_srec;
  uint16_t index;
  uint16_t first_unused_record = BTM_NO_AVAIL_SEC_SERVICES;
  bool record_allocated = false;

  log::verbose("sec_level:0x{:x}", sec_level);

  /* See if the record can be reused (same service name, psm, mx_proto_id,
     service_id, and mx_chan_id), or obtain the next unused record */

  p_srec = &sec_serv_rec_[0];

  for (index = 0; index < BTM_SEC_MAX_SERVICE_RECORDS; index++, p_srec++) {
    /* Check if there is already a record for this service */
    if (p_srec->security_flags & BTM_SEC_IN_USE) {
      if (p_srec->psm == psm && p_srec->mx_proto_id == mx_proto_id &&
          service_id == p_srec->service_id && p_name &&
          (!strncmp(p_name, (char*)p_srec->orig_service_name,
                    /* strlcpy replaces end char with termination char*/
                    BT_MAX_SERVICE_NAME_LEN - 1) ||
           !strncmp(p_name, (char*)p_srec->term_service_name,
                    /* strlcpy replaces end char with termination char*/
                    BT_MAX_SERVICE_NAME_LEN - 1))) {
        record_allocated = true;
        break;
      }
    } else if (!record_allocated) {
      /* Mark the first available service record */
      *p_srec = {};
      record_allocated = true;
      first_unused_record = index;
    }
  }

  if (!record_allocated) {
    log::warn("Out of Service Records ({})", BTM_SEC_MAX_SERVICE_RECORDS);
    return record_allocated;
  }

  /* Process the request if service record is valid */
  /* If a duplicate service wasn't found, use the first available */
  if (index >= BTM_SEC_MAX_SERVICE_RECORDS) {
    index = first_unused_record;
    p_srec = &sec_serv_rec_[index];
  }

  p_srec->psm = psm;
  p_srec->service_id = service_id;
  p_srec->mx_proto_id = mx_proto_id;

  if (outgoing) {
    p_srec->orig_mx_chan_id = mx_chan_id;
    osi_strlcpy((char*)p_srec->orig_service_name, p_name, BT_MAX_SERVICE_NAME_LEN + 1);
    /* clear out the old setting, just in case it exists */
    {
      p_srec->security_flags &=
              ~(BTM_SEC_OUT_ENCRYPT | BTM_SEC_OUT_AUTHENTICATE | BTM_SEC_OUT_MITM);
    }

    /* Parameter validation.  Originator should not set requirements for
     * incoming connections */
    sec_level &= ~(BTM_SEC_IN_ENCRYPT | BTM_SEC_IN_AUTHENTICATE | BTM_SEC_IN_MITM |
                   BTM_SEC_IN_MIN_16_DIGIT_PIN);

    if (security_mode_ == BTM_SEC_MODE_SP || security_mode_ == BTM_SEC_MODE_SC) {
      if (sec_level & BTM_SEC_OUT_AUTHENTICATE) {
        sec_level |= BTM_SEC_OUT_MITM;
      }
    }

    /* Make sure the authenticate bit is set, when encrypt bit is set */
    if (sec_level & BTM_SEC_OUT_ENCRYPT) {
      sec_level |= BTM_SEC_OUT_AUTHENTICATE;
    }

    /* outgoing connections usually set the security level right before
     * the connection is initiated.
     * set it to be the outgoing service */
    p_out_serv_ = p_srec;
  } else {
    p_srec->term_mx_chan_id = mx_chan_id;
    osi_strlcpy((char*)p_srec->term_service_name, p_name, BT_MAX_SERVICE_NAME_LEN + 1);
    /* clear out the old setting, just in case it exists */
    {
      p_srec->security_flags &= ~(BTM_SEC_IN_ENCRYPT | BTM_SEC_IN_AUTHENTICATE | BTM_SEC_IN_MITM |
                                  BTM_SEC_IN_MIN_16_DIGIT_PIN);
    }

    /* Parameter validation.  Acceptor should not set requirements for outgoing
     * connections */
    sec_level &= ~(BTM_SEC_OUT_ENCRYPT | BTM_SEC_OUT_AUTHENTICATE | BTM_SEC_OUT_MITM);

    if (security_mode_ == BTM_SEC_MODE_SP || security_mode_ == BTM_SEC_MODE_SC) {
      if (sec_level & BTM_SEC_IN_AUTHENTICATE) {
        sec_level |= BTM_SEC_IN_MITM;
      }
    }

    /* Make sure the authenticate bit is set, when encrypt bit is set */
    if (sec_level & BTM_SEC_IN_ENCRYPT) {
      sec_level |= BTM_SEC_IN_AUTHENTICATE;
    }
  }

  p_srec->security_flags |= (uint16_t)(sec_level | BTM_SEC_IN_USE);

  log::debug(
          "[{}]: id:{}, is_orig:{} psm:0x{:04x} proto_id:{} chan_id:{}  : "
          "sec:0x{:x} service_name:[{}] (up to {} chars saved)",
          index, service_id, outgoing, psm, mx_proto_id, mx_chan_id, p_srec->security_flags, p_name,
          BT_MAX_SERVICE_NAME_LEN);

  return record_allocated;
}

uint8_t BtmSecurity::RemoveServiceById(uint8_t service_id) {
  tBTM_SEC_SERV_REC* p_srec = &sec_serv_rec_[0];
  uint8_t num_freed = 0;
  int i;

  for (i = 0; i < BTM_SEC_MAX_SERVICE_RECORDS; i++, p_srec++) {
    /* Delete services with specified name (if in use and not SDP) */
    if ((p_srec->security_flags & BTM_SEC_IN_USE) && (p_srec->psm != BT_PSM_SDP) &&
        (!service_id || (service_id == p_srec->service_id))) {
      log::verbose("BTM_SEC_CLR[{}]: id:{}", i, service_id);
      p_srec->security_flags = 0;
      num_freed++;
    }
  }
  return num_freed;
}

uint8_t BtmSecurity::RemoveServiceByPsm(uint16_t psm) {
  tBTM_SEC_SERV_REC* p_srec = &sec_serv_rec_[0];
  uint8_t num_freed = 0;
  int i;

  for (i = 0; i < BTM_SEC_MAX_SERVICE_RECORDS; i++, p_srec++) {
    /* Delete services with specified name (if in use and not SDP) */
    if ((p_srec->security_flags & BTM_SEC_IN_USE) && (p_srec->psm == psm)) {
      log::verbose("BTM_SEC_CLR[{}]: id {}", i, p_srec->service_id);
      p_srec->security_flags = 0;
      num_freed++;
    }
  }
  log::verbose("psm:0x{:x} num_freed:{}", psm, num_freed);

  return num_freed;
}

bool BtmSecurityRecord::is_bonded(tBT_TRANSPORT transport) const {
  bool bonded = false;

  // Check BR/EDR bond status if requested transport is BT_TRANSPORT_BR_EDR or BT_TRANSPORT_AUTO
  if (transport != BT_TRANSPORT_LE) {
    log::verbose("BREDR bond status - bond_type: {}, sec_flags: {}", bond_type, sec_flags);
    bonded = is_bond_type_persistent() && is_link_key_known();
  }

  // Check LE bond status if requested transport is BT_TRANSPORT_LE or BT_TRANSPORT_AUTO
  if (transport != BT_TRANSPORT_BR_EDR) {
    log::verbose("BLE bond status - key_type: {}, sec_flags: {}", ble_keys.key_type, sec_flags);
    bonded |= (ble_keys.key_type != BTM_LE_KEY_NONE && is_le_link_key_known());
  }

  return bonded;
}

// TODO: b/444620685 - Remove this function once the flag is fully rolled out, and replace all the
// instances with actual functionality as the callbacks are invoked only from single place so do
// that inline.
// This is similar to list_foreach, but for array.
BtmDevice* BtmSecurity::for_each_dev_rec(sec_dev_rec_iter_cb cb, void* context) {
  log::assert_that(com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec(),
                   "assert failed: flag use_array_instead_list_in_sec_dev_rec is disabled.");
  log::assert_that(cb != NULL, "assert failed: callback is null.");

  for (BtmDevice& device : device_records_) {
    if (device.IsInitialized() && !cb(&device, context)) {
      return &device;
    }
  }
  return nullptr;
}

bool BtmSecurity::ResetLinkKeyRequestTimer() {
  if (lk_req_timer_ != nullptr) {
    log::debug("Resetting Link Key Request Timer");
    alarm_free(lk_req_timer_);
    lk_req_timer_ = nullptr;
    return true;
  }
  return false;
}
