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

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <bluetooth/types/bt_transport.h>

#include <cstdint>

#include "stack/include/bt_dev_class.h"
#include "stack/include/btm_status.h"

/*****************************************************************************
 *  SECURITY MANAGEMENT FUNCTIONS
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         BTM_SecAddDevice
 *
 * Description      Add/modify device.  This function will be normally called
 *                  during host startup to restore all required information
 *                  stored in the NVRAM.
 *                  dev_class, link_key are NULL if unknown
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecAddDevice(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                      const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                      uint8_t pin_length);

/** Free resources associated with the device associated with |bd_addr| address.
 *
 * *** WARNING ***
 * BtmDevice associated with bd_addr becomes invalid after this function
 * is called, also any of its fields. i.e. if you use p_device->bd_addr, it is
 * no longer valid!
 * *** WARNING ***
 *
 * Returns true if removed OK, false if not found or ACL link is active.
 */
bool BTM_SecDeleteDevice(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SecClearSecurityFlags
 *
 * Description      Reset the security flags (mark as not-paired) for a given
 *                  remove device.
 *
 ******************************************************************************/
void BTM_SecClearSecurityFlags(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_IsBonded
 *
 * Description      Is the specified device is a bonded device
 *
 * Returns          true - dev is bonded
 *
 ******************************************************************************/
bool BTM_IsBonded(const RawAddress& bd_addr, tBT_TRANSPORT transport = BT_TRANSPORT_AUTO);

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
void BTM_BleSirkConfirmDeviceReply(const RawAddress& bd_addr, tBTM_STATUS res);

uint8_t btm_ble_read_sec_key_size(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_hci_delete_stored_link_key
 *
 * Description      Instructs the controller to delete the stored link key for the device.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_hci_delete_stored_link_key(const RawAddress& bd_addr);
