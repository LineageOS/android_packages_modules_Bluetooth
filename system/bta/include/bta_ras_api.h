/*
 * Copyright 2024 The Android Open Source Project
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
#include <bluetooth/types/uuid.h>

#include <cstdint>
#include <vector>

namespace bluetooth {
namespace ras {

enum class RasDisconnectReason {
  GATT_DISCONNECT,
  SERVER_NOT_AVAILABLE,
  FATAL_ERROR,
};

struct VendorSpecificCharacteristic {
  bluetooth::Uuid characteristicUuid_;
  std::vector<uint8_t> value_;
  std::vector<uint8_t> reply_value_;
};

class RasServerCallbacks {
public:
  virtual ~RasServerCallbacks() = default;
  virtual void OnVendorSpecificReply(
          const RawAddress& address,
          const std::vector<VendorSpecificCharacteristic>& vendor_specific_reply) = 0;
  virtual void OnRasServerConnected(const RawAddress& identity_address) = 0;
  virtual void OnMtuChangedFromServer(const RawAddress& address, uint16_t mtu) = 0;
  virtual void OnRasServerDisconnected(const RawAddress& identity_address) = 0;
};

class RasServer {
public:
  virtual ~RasServer() = default;
  virtual void Initialize() = 0;
  virtual void RegisterCallbacks(RasServerCallbacks* callbacks) = 0;
  virtual void SetVendorSpecificCharacteristic(
          const std::vector<VendorSpecificCharacteristic>& vendor_specific_characteristics) = 0;
  virtual void HandleVendorSpecificReplyComplete(RawAddress address, bool success) = 0;
  virtual void PushProcedureData(RawAddress address, uint16_t procedure_count, bool is_last,
                                 std::vector<uint8_t> data) = 0;
};

RasServer* GetRasServer();

class RasClientCallbacks {
public:
  virtual ~RasClientCallbacks() = default;
  virtual void OnConnected(
          const RawAddress& address, uint16_t att_handle,
          const std::vector<VendorSpecificCharacteristic>& vendor_specific_characteristics,
          uint16_t conn_interval) = 0;
  virtual void OnConnIntervalUpdated(const RawAddress& address, uint16_t conn_interval) = 0;
  virtual void OnDisconnected(const RawAddress& address,
                              const RasDisconnectReason& ras_disconnect_reason) = 0;
  virtual void OnWriteVendorSpecificReplyComplete(const RawAddress& address, bool success) = 0;
  virtual void OnRemoteData(const RawAddress& address, const std::vector<uint8_t>& data) = 0;
  virtual void OnRemoteDataTimeout(const RawAddress& address) = 0;
  virtual void OnMtuChangedFromClient(const RawAddress& address, uint16_t mtu) = 0;
};

class RasClient {
public:
  virtual ~RasClient() = default;
  virtual void Initialize() = 0;
  virtual void RegisterCallbacks(RasClientCallbacks* callbacks) = 0;
  virtual void Connect(const RawAddress& address) = 0;
  virtual void SendVendorSpecificReply(
          const RawAddress& address,
          const std::vector<VendorSpecificCharacteristic>& vendor_specific_data) = 0;
  virtual void NotifyRangingHardwareOffloadEnabled() = 0;
};

RasClient* GetRasClient();

}  // namespace ras
}  // namespace bluetooth
