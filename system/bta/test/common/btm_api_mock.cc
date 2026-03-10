/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include "btm_api_mock.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>

#include <optional>

#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/acl_api.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_sec_api.h"
#include "stack/mock/mock_stack_btm_interface.h"

using namespace bluetooth;

static bluetooth::manager::MockBtmInterface* btm_interface = nullptr;

void bluetooth::manager::SetMockBtmInterface(MockBtmInterface* mock_btm_interface) {
  btm_interface = mock_btm_interface;
}

const BtmDevice* btm_find_dev(const RawAddress& bd_addr) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->FindDevice(bd_addr);
}

BtmDevice* btm_get_dev(const RawAddress& bd_addr) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->FindDevice(bd_addr);
}

bool maybe_resolve_address(RawAddress* bda, tBLE_ADDR_TYPE* bda_type) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->MaybeResolveAddress(bda, bda_type);
}

bool btm_random_pseudo_to_identity_addr(RawAddress* random_pseudo, uint8_t* p_static_addr_type) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->BTM_RandomPseudoToIdentityAddr(random_pseudo, p_static_addr_type);
}

void acl_disconnect_from_handle(uint16_t handle, tHCI_STATUS reason, std::string /*comment*/) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->AclDisconnectFromHandle(handle, reason);
}

bool acl_peer_supports_ble_connection_subrating(const RawAddress& random_pseudo) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->AclPeerSupportsBleConnectionSubrating(random_pseudo);
}

bool acl_peer_supports_ble_connection_subrating_host(const RawAddress& random_pseudo) {
  log::assert_that(btm_interface != nullptr, "Mock btm interface not set!");
  return btm_interface->AclPeerSupportsBleConnectionSubratingHost(random_pseudo);
}
