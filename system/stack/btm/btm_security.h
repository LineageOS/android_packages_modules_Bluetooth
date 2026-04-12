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

#pragma once

#include <bluetooth/types/acl_link_spec.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>

#include <cstdint>
#include <list>

#include "internal_include/bt_target.h"
#include "osi/include/alarm.h"
#include "osi/include/list.h"
#include "stack/btm/btm_device_record.h"
#include "stack/btm/btm_sec_int_types.h"
#include "stack/include/security_client_callbacks.h"

// TODO: b/446803190 - Fix the arguments by making them const references.
typedef bool (*sec_dev_rec_iter_cb)(void* dev_rec, void* context);

class BtmSecurity {
public:
  /*
   * Get the singleton instance of BtmSecurity.
   */
  static BtmSecurity& Get();

  tBTM_CFG cfg_; /* Device configuration */

  /*****************************************************
  **     Local Device control block (on security)
  *****************************************************/
  tBTM_SEC_DEVCB devcb_;

  uint16_t enc_handle_{0};
  Octet8 enc_rand_;  /* received rand value from LTK request*/
  uint16_t ediv_{0}; /* received ediv value from LTK request */
  uint8_t key_size_{0};

public:
  /*****************************************************
  **      Security Management
  *****************************************************/
  const BtmAppReg* app_;

  BtmDevice* p_collided_dev_{nullptr};
  alarm_t* sec_collision_timer_{nullptr};
  uint64_t collision_start_time_{0};
  uint32_t dev_rec_count_{0}; /* Counter used for device record timestamp */
  uint8_t security_mode_{0};
  bool pairing_disabled_{false};

  // TODO : Remove when the flag local_pin_key_type is shipped
  bool pin_type_changed_{false}; /* pin type changed during bonding */

  bool l2c_service_access_pending_{false}; /* If an L2CAP service access request is pending */

  // TODO(b/460502961): Remove once the flag security_mode_3_pairing is shipped
  bool security_mode_changed_{false}; /* mode changed during bonding */
  uint8_t pin_code_len_{0};           /* for legacy devices */
  PinCode pin_code_;                  /* for legacy devices */

  tBTM_PAIRING_STATE pairing_state_{BTM_PAIR_STATE_IDLE}; /* The current pairing state    */
  uint8_t pairing_flags_{0};                              /* The current pairing flags    */
  AclLinkSpec link_spec_;                                 /* The device currently pairing.
                                                             Address type is ignored currently */
  alarm_t* pairing_timer_{nullptr};                       /* Timer for pairing process    */
  alarm_t* lk_req_timer_{nullptr}; /* To wait for CTKD to complete when Link Key is requested */

  // TODO(b/444620685): Remove when use_array_instead_list_in_sec_dev_rec is shipped.
  list_t* sec_dev_rec_{nullptr}; /* list of BtmDevice */
  std::array<BtmDevice, BTM_SEC_MAX_DEVICE_RECORDS + 1> device_records_ = {};
  tBTM_SEC_SERV_REC* p_out_serv_{nullptr};

  RawAddress connecting_bda_;

  // Pending service access requests in tBTM_SERVICE_ACCESS_REQ format
  std::list<tBTM_SERVICE_ACCESS_REQ> service_access_q_ = {};

  // Pending encryption requests
  std::list<tBTM_SEC_REQ> enc_request_q_ = {};

  tBTM_SEC_SERV_REC sec_serv_rec_[BTM_SEC_MAX_SERVICE_RECORDS];

  DEV_CLASS connecting_dc_;

  void Init();
  void Init(uint8_t initial_security_mode);
  void Free();

  tBTM_SEC_SERV_REC* find_first_serv_rec(bool outgoing, uint16_t psm);

  bool IsDeviceBonded(const RawAddress bd_addr, tBT_TRANSPORT transport = BT_TRANSPORT_AUTO);
  bool IsDeviceEncrypted(const RawAddress bd_addr, tBT_TRANSPORT transport);
  bool IsDeviceAuthenticated(const RawAddress bd_addr, tBT_TRANSPORT transport);
  bool IsLinkKeyAuthenticated(const RawAddress bd_addr, tBT_TRANSPORT transport);
  bool IsSecCBInitialized() {
    return sec_collision_timer_ != nullptr; /* re-using the timer as init indicator */
  }

  const BtmSecurityRecord* findSecRec(const RawAddress bd_addr);

  bool AddService(bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
                  uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id);
  uint8_t RemoveServiceById(uint8_t service_id);
  uint8_t RemoveServiceByPsm(uint16_t psm);

  void change_pairing_state(tBTM_PAIRING_STATE new_state);
  BtmDevice* for_each_dev_rec(sec_dev_rec_iter_cb cb, void* context);
  bool ResetLinkKeyRequestTimer();
};

#define BTM_BLE_SEC_CALLBACK(event_, bda_, data_)                                            \
  do {                                                                                       \
    tBTM_STATUS status_ = (BtmSecurity::Get().app_->le_callback)((event_), (bda_), (data_)); \
    if (status_ != tBTM_STATUS::BTM_SUCCESS) {                                               \
      log::warn("Security callback failed {} for {}", btm_status_text(status_), (bda_));     \
    }                                                                                        \
  } while (0)
