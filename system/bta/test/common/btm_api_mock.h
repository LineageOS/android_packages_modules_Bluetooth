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
#pragma once

#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/bt_octets.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/hci_role.h>
#include <gmock/gmock.h>

#include <cstdint>
#include <optional>

#include "stack/btm/btm_device_record.h"
#include "stack/btm/neighbor_inquiry.h"

namespace bluetooth {
namespace manager {

class BtmInterface {
public:
  virtual BtmDevice* FindDevice(const RawAddress& bd_addr) = 0;
  virtual void AclDisconnectFromHandle(uint16_t handle, tHCI_STATUS reason) = 0;

  virtual bool MaybeResolveAddress(RawAddress* bda, tBLE_ADDR_TYPE* bda_type) = 0;
  virtual bool BTM_RandomPseudoToIdentityAddr(RawAddress* random_pseudo,
                                              uint8_t* p_static_addr_type) = 0;
  virtual bool AclPeerSupportsBleConnectionSubrating(const RawAddress& random_pseudo) = 0;
  virtual bool AclPeerSupportsBleConnectionSubratingHost(const RawAddress& random_pseudo) = 0;

  virtual ~BtmInterface() = default;
};

class MockBtmInterface : public BtmInterface {
public:
  MOCK_METHOD((BtmDevice*), FindDevice, (const RawAddress& bd_addr), (override));
  MOCK_METHOD((void), AclDisconnectFromHandle, (uint16_t handle, tHCI_STATUS reason), (override));

  MOCK_METHOD((bool), MaybeResolveAddress, (RawAddress* bda, tBLE_ADDR_TYPE* bda_type), (override));
  MOCK_METHOD((bool), BTM_RandomPseudoToIdentityAddr,
              (RawAddress* random_pseudo, uint8_t* p_static_addr_type), (override));
  MOCK_METHOD((bool), AclPeerSupportsBleConnectionSubrating, (const RawAddress& bd_addr), (override));
  MOCK_METHOD((bool), AclPeerSupportsBleConnectionSubratingHost, (const RawAddress& bd_addr), (override));
};

/**
 * Set the {@link MockBtmInterface} for testing
 *
 * @param mock_btm_interface pointer to mock btm interface, could be null
 */
void SetMockBtmInterface(MockBtmInterface* mock_btm_interface);

}  // namespace manager
}  // namespace bluetooth
