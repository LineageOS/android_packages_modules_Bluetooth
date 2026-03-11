/*
 * Copyright 2021 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:16
 */

#include "stack/mock/mock_stack_btm_dev.h"

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <stddef.h>
#include <stdlib.h>

#include "stack/btm/btm_dev.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_sec_api.h"
#include "test/common/mock_functions.h"

namespace test {
namespace mock {
namespace stack_btm_dev {

struct btm_find_dev btm_find_dev;
struct btm_get_dev btm_get_dev;
struct BTM_Sec_AddressKnown BTM_Sec_AddressKnown;

struct maybe_resolve_address maybe_resolve_address;
}  // namespace stack_btm_dev
}  // namespace mock
}  // namespace test

bool btm_set_bond_type_dev(const RawAddress& /* bd_addr */, tBTM_BOND_TYPE /* bond_type */) {
  inc_func_call_count(__func__);
  return false;
}
const BtmDevice* btm_find_dev(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_dev::btm_find_dev.body(bd_addr);
}
BtmDevice* btm_get_dev(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_dev::btm_get_dev.body(bd_addr);
}
const BtmDevice* btm_find_dev_by_handle(uint16_t /* handle */) {
  inc_func_call_count(__func__);
  return nullptr;
}
BtmDevice* btm_find_or_alloc_dev(const RawAddress& /* bd_addr */) {
  inc_func_call_count(__func__);
  return nullptr;
}
BtmDevice* btm_sec_alloc_dev(const RawAddress& /* bd_addr */) {
  inc_func_call_count(__func__);
  return nullptr;
}
BtmDevice* btm_sec_allocate_dev_rec(const RawAddress& /* bd_addr */) {
  inc_func_call_count(__func__);
  return nullptr;
}
void btm_consolidate_dev(BtmDevice* /* p_target_rec */) { inc_func_call_count(__func__); }
void btm_dev_consolidate_existing_connections(const RawAddress& /* bd_addr */) {
  inc_func_call_count(__func__);
}
std::vector<BtmDevice*> btm_get_sec_dev_rec() {
  inc_func_call_count(__func__);
  return {};
}
bool BTM_Sec_AddressKnown(const RawAddress& address) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_dev::BTM_Sec_AddressKnown(address);
}

bool maybe_resolve_address(RawAddress* bda, tBLE_ADDR_TYPE* bda_type) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_dev::maybe_resolve_address(bda, bda_type);
}
const tBLE_BD_ADDR BTM_Sec_GetAddressWithType(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return tBLE_BD_ADDR{.type = BLE_ADDR_PUBLIC, .bda = bd_addr};
}

uint16_t BTM_GetCachedClockOffset(const RawAddress& /* bd_addr */) {
  inc_func_call_count(__func__);
  return 0;
}

void DumpsysRecord(int /* fd */) { inc_func_call_count(__func__); }
