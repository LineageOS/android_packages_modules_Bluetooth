/*
 * Copyright 2022 The Android Open Source Project
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
#include "hci/address.h"
#include "hci/hci_packets.h"
#include "module.h"

namespace bluetooth {
namespace hci {

enum DistanceMeasurementMethod {
  METHOD_AUTO,
  METHOD_RSSI,
  METHOD_CS,
};

enum DistanceMeasurementErrorCode {
  REASON_FEATURE_NOT_SUPPORTED_LOCAL,
  REASON_FEATURE_NOT_SUPPORTED_REMOTE,
  REASON_LOCAL_REQUEST,
  REASON_REMOTE_REQUEST,
  REASON_DURATION_TIMEOUT,
  REASON_NO_LE_CONNECTION,
  REASON_INVALID_PARAMETERS,
  REASON_INTERNAL_ERROR,
};

enum DistanceMeasurementDetectedAttackLevel {
  NADM_ATTACK_IS_EXTREMELY_UNLIKELY = 0,
  NADM_ATTACK_IS_VERY_UNLIKELY = 1,
  NADM_ATTACK_IS_UNLIKELY = 2,
  NADM_ATTACK_IS_POSSIBLE = 3,
  NADM_ATTACK_IS_LIKELY = 4,
  NADM_ATTACK_IS_VERY_LIKELY = 5,
  NADM_ATTACK_IS_EXTREMELY_LIKELY = 6,
  NADM_ATTACK_UNKNOWN = 0xFF,
};

class DistanceMeasurementCallbacks {
public:
  virtual ~DistanceMeasurementCallbacks() = default;
  virtual void OnDistanceMeasurementStarted(Address address, DistanceMeasurementMethod method) = 0;
  virtual void OnDistanceMeasurementStopped(Address address, DistanceMeasurementErrorCode reason,
                                            DistanceMeasurementMethod method) = 0;
  virtual void OnDistanceMeasurementResult(
          Address address, uint32_t centimeter, uint32_t error_centimeter, int azimuth_angle,
          int error_azimuth_angle, int altitude_angle, int error_altitude_angle,
          uint64_t elapsed_realtime_nanos, int8_t confidence_level, double delayed_spread_meters,
          DistanceMeasurementDetectedAttackLevel detected_attack_level,
          double velocity_meters_per_second, DistanceMeasurementMethod method) = 0;
  virtual void OnRasFragmentReady(Address address, uint16_t procedure_counter, bool is_last,
                                  std::vector<uint8_t> raw_data) = 0;
  virtual void OnVendorSpecificCharacteristics(
          std::vector<hal::VendorSpecificCharacteristic> vendor_specific_characteristics) = 0;
  virtual void OnVendorSpecificReply(Address address,
                                     std::vector<bluetooth::hal::VendorSpecificCharacteristic>
                                             vendor_specific_characteristics) = 0;
  virtual void OnHandleVendorSpecificReplyComplete(Address address, bool success) = 0;
};

class DistanceMeasurementManager : public bluetooth::Module {
public:
  DistanceMeasurementManager();
  ~DistanceMeasurementManager();
  DistanceMeasurementManager(const DistanceMeasurementManager&) = delete;
  DistanceMeasurementManager& operator=(const DistanceMeasurementManager&) = delete;

  void RegisterDistanceMeasurementCallbacks(DistanceMeasurementCallbacks* callbacks);
  void StartDistanceMeasurement(const Address&, uint16_t connection_handle,
                                hci::Role local_hci_role, uint16_t interval,
                                DistanceMeasurementMethod method);
  void StopDistanceMeasurement(const Address& address, uint16_t connection_handle,
                               DistanceMeasurementMethod method);
  void HandleRasClientConnectedEvent(
          const Address& address, uint16_t connection_handle, uint16_t att_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data,
          uint16_t conn_interval);
  void HandleRasClientDisconnectedEvent(const Address& address,
                                        const ras::RasDisconnectReason& ras_disconnect_reason);
  void HandleVendorSpecificReply(
          const Address& address, uint16_t connection_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply);
  void HandleRasServerConnected(const Address& identity_address, uint16_t connection_handle,
                                hci::Role local_hci_role);
  void HandleMtuChanged(uint16_t connection_handle, uint16_t mtu);
  void HandleRasServerDisconnected(const Address& identity_address, uint16_t connection_handle);
  void HandleVendorSpecificReplyComplete(const Address& address, uint16_t connection_handle,
                                         bool success);
  void HandleRemoteData(const Address& address, uint16_t connection_handle,
                        const std::vector<uint8_t>& raw_data);
  void HandleRemoteDataTimeout(const Address& address, uint16_t connection_handle);
  void HandleConnIntervalUpdated(const Address& address, uint16_t connection_handle,
                                 uint16_t conn_interval);

  static const ModuleFactory Factory;

protected:
  void ListDependencies(ModuleList* list) const override;

  void Start() override;

  void Stop() override;

  std::string ToString() const override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace hci
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::hci::DistanceMeasurementMethod>
    : enum_formatter<bluetooth::hci::DistanceMeasurementMethod> {};
}  // namespace std
