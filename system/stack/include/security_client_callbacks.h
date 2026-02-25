/*
 * Copyright 2020 The Android Open Source Project
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

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <bluetooth/types/bt_transport.h>

#include <cstdint>

#include "stack/include/bt_dev_class.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/bt_name.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"

/****************************************
 *  Security Manager Callback Functions
 ****************************************/

/* Get PIN for the connection.  Parameters are
 *              BD Address of remote
 *              Device Class of remote
 *              BD Name of remote
 *              Flag indicating the minimum pin code length to be 16 digits
 *              Pairing Algorithm being used
 */
typedef tBTM_STATUS(BtmPinCallback)(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                                    const BD_NAME& bd_name, bool min_16_digit,
                                    PairingAlgorithm pairing_algorithm);

/* New Link Key for the connection.  Parameters are
 *              BD Address of remote
 *              Link Key
 *              Key Type: Combination, Local Unit, or Remote Unit
 */
typedef tBTM_STATUS(BtmLinkKeyCallback)(const RawAddress& bd_addr, const BD_NAME& bd_name,
                                        const LinkKey& key, uint8_t key_type, bool is_ctkd);

/* Authentication complete for the connection.  Parameters are
 *              BD Address of remote
 *              Device Class of remote
 *              BD Name of remote
 *
 */
typedef void(BtmAuthCompleteCallback)(const RawAddress& bd_addr, const BD_NAME& bd_name,
                                      tHCI_REASON reason);

/* Bond Cancel complete. Parameters are
 *              Result of the cancel operation
 *
 */
typedef void(BtmBondCancelCmplCallback)(tBTM_STATUS result);

/* Simple Pairing Events.  Called by the stack when Simple Pairing related
 * events occur.
 */
typedef tBTM_STATUS(BtmSpCallback)(tBTM_SP_EVT event, tBTM_SP_EVT_DATA* p_data);

/* LE Pairing Events. Called by the stack when LE Pairing related events occur.
 */
typedef tBTM_STATUS(BtmLeCallback)(tBTM_LE_EVT event, const RawAddress& bda,
                                   tBTM_LE_EVT_DATA* p_data);

/* New LE identity key for local device.
 */
typedef void(BtmLeKeyCallback)(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key);

/* Request SIRK verification for found member. Parameters are
 *              BD Address of remote
 */
typedef tBTM_STATUS(BtmSirkVerificationCallback)(const RawAddress& bd_addr);

/* Remote Name Resolved.  Parameters are
 *              BD Address of remote
 *              BD Name of remote
 */
typedef void(BtmRemoteNameCallback)(const RawAddress& bd_addr, const BD_NAME& bd_name);

struct BtmAppReg {
  BtmPinCallback& pin_callback;
  BtmLinkKeyCallback& link_key_callback;
  BtmAuthCompleteCallback& auth_complete_callback;
  BtmBondCancelCmplCallback& bond_cancel_cmpl_callback;
  BtmSpCallback& sp_callback;
  BtmLeCallback& le_callback;
  BtmLeKeyCallback& le_key_callback;
  BtmSirkVerificationCallback& sirk_verification_callback;
};
