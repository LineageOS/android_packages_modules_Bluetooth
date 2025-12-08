/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <bluetooth/log.h>

#include "bta/include/bta_ras_api.h"
#include "hal/ranging_hal.h"
#include "hci/acl_manager/acl_manager_le.h"
#include "hci/address.h"
#include "hci/controller.h"
#include "hci/distance_measurement_manager.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"

namespace bluetooth {
namespace hci {

class DistanceMeasurementManagerImpl : public DistanceMeasurementManager {
public:
  DistanceMeasurementManagerImpl(os::Handler* handler, hci::HciInterface* hci_layer,
                                 hci::Controller* controller, hci::AclManagerLe* acl_manager,
                                 hal::RangingHal* ranging_hal);
  ~DistanceMeasurementManagerImpl();
  DistanceMeasurementManagerImpl(const DistanceMeasurementManagerImpl&) = delete;
  DistanceMeasurementManagerImpl& operator=(const DistanceMeasurementManagerImpl&) = delete;

  void RegisterDistanceMeasurementCallbacks(DistanceMeasurementCallbacks* callbacks) override;
  void StartDistanceMeasurement(int32_t app_uid, const Address&, uint16_t connection_handle,
                                hci::Role local_hci_role, uint16_t interval,
                                DistanceMeasurementMethod method,
                                DistanceMeasurementSightType sight_type,
                                DistanceMeasurementLocationType location_type) override;
  void StopDistanceMeasurement(const Address& address, uint16_t connection_handle,
                               DistanceMeasurementMethod method) override;
  void HandleRasClientConnectedEvent(
          const Address& address, uint16_t connection_handle, uint16_t att_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data,
          uint16_t conn_interval) override;
  void HandleRasClientDisconnectedEvent(
          const Address& address, const ras::RasDisconnectReason& ras_disconnect_reason) override;
  void HandleVendorSpecificReply(
          const Address& address, uint16_t connection_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply) override;
  void HandleRasServerConnected(const Address& identity_address, uint16_t connection_handle,
                                hci::Role local_hci_role) override;
  void HandleMtuChanged(uint16_t connection_handle, uint16_t mtu) override;
  void HandleRasServerDisconnected(const Address& identity_address,
                                   uint16_t connection_handle) override;
  void HandleVendorSpecificReplyComplete(const Address& address, uint16_t connection_handle,
                                         bool success) override;
  void HandleRemoteData(const Address& address, uint16_t connection_handle,
                        const std::vector<uint8_t>& raw_data) override;
  void HandleRemoteDataTimeout(const Address& address, uint16_t connection_handle) override;
  void HandleConnIntervalUpdated(const Address& address, uint16_t connection_handle,
                                 uint16_t conn_interval) override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace hci
}  // namespace bluetooth
