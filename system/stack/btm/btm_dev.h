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
#include <bluetooth/types/ble_address_with_type.h>

#include <vector>

#include "stack/btm/btm_device_record.h"

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
bool btm_sec_delete_device(const RawAddress& bd_addr);
void btm_sec_add_device(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                        const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                        uint8_t pin_length);

/*******************************************************************************
 *
 * Function         btm_sec_clear_security_flags
 *
 * Description      Reset the security flags (mark as not-paired) for a given
 *                  remove device.
 *
 ******************************************************************************/
void btm_sec_clear_security_flags(const RawAddress& bd_addr);

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
const char* btm_sec_read_dev_name(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_read_dev_class
 *
 * Description      Looks for the device name in the security database for the
 *                  class of device of the specified BD address.
 *
 * Returns          The class of the device
 *
 ******************************************************************************/
DEV_CLASS btm_sec_read_dev_class(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_alloc_dev
 *
 * Description      Allocate a record in the device database
 *                  with specified address
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_sec_alloc_dev(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_get_dev_by_handle (read/write version)
 *
 * Description      Look for the record in the device database for the record
 *                  with specified handle
 * Note: This is a blocking call, as it will post the get to the main thread (if not already in the
 * main thread), and then wait for it to complete.
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_get_dev_by_handle(uint16_t handle);

/*******************************************************************************
 *
 * Function         btm_find_dev_by_handle (read-only version of btm_get_dev_by_handle)
 *
 * Description      Look for the record in the device database for the record
 *                  with specified handle
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
const BtmDevice* btm_find_dev_by_handle(uint16_t handle);

/*******************************************************************************
 *
 * Function         btm_get_dev
 *
 * Description      Look for the record in the device database for the record
 *                  with specified BD address
 * Note: This is a blocking call, as it will post the get to the main thread (if not already in the
 * main thread), and then wait for it to complete.
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_get_dev(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_find_dev (read-only version of btm_get_dev)
 *
 * Description      Look for the record in the device database for the record
 *                  with specified BD address
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
const BtmDevice* btm_find_dev(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_get_dev_with_lenc (read/write version)
 *
 * Description      Look for the record in the device database with LTK and
 *                  specified BD address
 * Note: This is a blocking call, as it will post the get to the main thread (if not already in the
 * main thread), and then wait for it to complete.
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_get_dev_with_lenc(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_find_dev_with_lenc (read-only version of
 *                                            btm_get_dev_with_lenc)
 *
 * Description      Look for the record in the device database with LTK and
 *                  specified BD address
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
const BtmDevice* btm_find_dev_with_lenc(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_consolidate_dev
 *
 * Description      combine security records if identified as same peer
 *
 * Returns          none
 *
 ******************************************************************************/
void btm_consolidate_dev(BtmDevice* p_target);

/*******************************************************************************
 *
 * Function         btm_consolidate_dev
 *
 * Description      When pairing is finished (i.e. on BR/EDR), this function
 *                  checks if there are existing LE connections to same device
 *                  that can now be encrypted and used for profiles requiring
 *                  encryption.
 *
 * Returns          none
 *
 ******************************************************************************/
void btm_dev_consolidate_existing_connections(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_find_or_alloc_dev
 *
 * Description      Look for the record in the device database for the record
 *                  with specified BD address
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
BtmDevice* btm_find_or_alloc_dev(const RawAddress& bd_addr);

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
BtmDevice* btm_sec_allocate_dev_rec(const RawAddress& bd_addr);

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
bool btm_set_bond_type_dev(const RawAddress& bd_addr, tBTM_BOND_TYPE bond_type);

/*******************************************************************************
 *
 * Function         btm_get_sec_dev_rec
 *
 * Description      Get security device records satisfying given filter
 *
 * Returns          A vector containing pointers of security device records
 *
 ******************************************************************************/
std::vector<BtmDevice*> btm_get_sec_dev_rec();

/*******************************************************************************
 *
 * Function         BTM_GetCachedClockOffset
 *
 * Description      Get the cached clock offset for a device in the device
 *                  database with specified BD address.
 *
 * Returns          The cached clock offset if known, otherwise 0.
 *
 ******************************************************************************/
uint16_t BTM_GetCachedClockOffset(const RawAddress& bd_addr);

bool BTM_Sec_AddressKnown(const RawAddress& address);
const tBLE_BD_ADDR BTM_Sec_GetAddressWithType(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         DumpsysRecord
 *
 * Description      Provides dumpsys access to device records.
 *
 * Returns          void
 *
 ******************************************************************************/
void DumpsysRecord(int fd);

namespace bluetooth::legacy::testing {
void wipe_secrets_and_remove(BtmDevice* p_device);
}  // namespace bluetooth::legacy::testing
