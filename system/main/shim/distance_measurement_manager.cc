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

#include "distance_measurement_manager.h"

#include "bta/include/bta_ras_api.h"
#include "btif/include/btif_common.h"
#include "hci/distance_measurement_manager.h"
#include "hci/hci_packets.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "stack/include/acl_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/main_thread.h"

using bluetooth::hci::DistanceMeasurementDetectedAttackLevel;
using bluetooth::hci::DistanceMeasurementErrorCode;
using bluetooth::hci::DistanceMeasurementLocationType;
using bluetooth::hci::DistanceMeasurementMethod;
using bluetooth::hci::DistanceMeasurementSightType;
using namespace bluetooth;

extern BtmDevice* btm_find_dev(const RawAddress& bd_addr);
extern bool BTM_IsEncrypted(const RawAddress& bd_addr, tBT_TRANSPORT transport);

class DistanceMeasurementInterfaceImpl : public DistanceMeasurementInterface,
                                         public bluetooth::hci::DistanceMeasurementCallbacks,
                                         public bluetooth::ras::RasServerCallbacks,
                                         public bluetooth::ras::RasClientCallbacks {
public:
  ~DistanceMeasurementInterfaceImpl() override {}

  void Init() {
    // Register callback
    bluetooth::shim::GetDistanceMeasurementManager()->RegisterDistanceMeasurementCallbacks(this);
    do_in_main_thread(base::BindOnce(&bluetooth::ras::RasServer::RegisterCallbacks,
                                     base::Unretained(bluetooth::ras::GetRasServer()), this));
    do_in_main_thread(base::BindOnce(&bluetooth::ras::RasClient::RegisterCallbacks,
                                     base::Unretained(bluetooth::ras::GetRasClient()), this));
  }

  /**
   * Gets the BLE connection handle, must be called from main_thread.
   * @param bd_addr could be random, rpa or identity address.
   * @return BLE ACL handle
   */
  static uint16_t GetConnectionHandleAndRole(const RawAddress& bd_addr,
                                             hci::Role* hci_role = nullptr) {
    BtmDevice* p_device = btm_find_dev(bd_addr);
    if (p_device != nullptr) {
      if (hci_role != nullptr) {
        *hci_role = p_device->role_central ? hci::Role::CENTRAL : hci::Role::PERIPHERAL;
      }
      return p_device->get_ble_hci_handle();
    }
    return kIllegalConnectionHandle;
  }

  void RegisterDistanceMeasurementCallbacks(::DistanceMeasurementCallbacks* callbacks) {
    distance_measurement_callbacks_ = callbacks;
  }

  void StartDistanceMeasurement(int32_t app_uid, RawAddress identity_addr, uint16_t interval,
                                uint8_t method, uint8_t sight_type, uint8_t location_type) {
    do_in_main_thread(base::BindOnce(&DistanceMeasurementInterfaceImpl::DoStartDistanceMeasurement,
                                     base::Unretained(this), app_uid, identity_addr, interval,
                                     method, sight_type, location_type, 0));
  }

  void DoStartDistanceMeasurement(int32_t app_uid, RawAddress identity_addr, uint16_t interval,
                                  uint8_t method, uint8_t sight_type, uint8_t location_type,
                                  int retries = 0) {
    auto distance_measurement_method = static_cast<DistanceMeasurementMethod>(method);
    auto distance_measurement_sight_type = static_cast<DistanceMeasurementSightType>(sight_type);
    auto distance_measurement_location_type =
            static_cast<DistanceMeasurementLocationType>(location_type);
    hci::Role local_hci_role;
    if (!get_security_client_interface().BTM_IsEncrypted(identity_addr, BT_TRANSPORT_LE) &&
        distance_measurement_method == DistanceMeasurementMethod::METHOD_CS) {
      if (retries < kMaxRetryCount) {
        log::info("Connection is not encrypted, retrying in {} ms", kRetryIntervalMs);
        do_in_main_thread_delayed(
                base::BindOnce(&DistanceMeasurementInterfaceImpl::DoStartDistanceMeasurement,
                               base::Unretained(this), app_uid, identity_addr, interval, method,
                               sight_type, location_type, retries + 1),
                std::chrono::milliseconds(kRetryIntervalMs));
        return;
      }
      log::error("Connection not encrypted after retries, failing distance measurement");
      OnDistanceMeasurementStopped(identity_addr,
                                   DistanceMeasurementErrorCode::REASON_INTERNAL_ERROR,
                                   static_cast<DistanceMeasurementMethod>(method));
      return;
    }
    uint16_t connection_handle = GetConnectionHandleAndRole(identity_addr, &local_hci_role);
    bluetooth::shim::GetDistanceMeasurementManager()->StartDistanceMeasurement(
            app_uid, identity_addr, connection_handle, local_hci_role, interval,
            distance_measurement_method, distance_measurement_sight_type,
            distance_measurement_location_type);
    if (distance_measurement_method == DistanceMeasurementMethod::METHOD_CS) {
      bluetooth::ras::GetRasClient()->Connect(identity_addr);
    }
  }

  void StopDistanceMeasurement(RawAddress identity_addr, uint8_t method) {
    do_in_main_thread(base::BindOnce(&DistanceMeasurementInterfaceImpl::DoStopDistanceMeasurement,
                                     base::Unretained(this), identity_addr, method));
  }

  void DoStopDistanceMeasurement(RawAddress identity_addr, uint8_t method) {
    bluetooth::shim::GetDistanceMeasurementManager()->StopDistanceMeasurement(
            identity_addr, GetConnectionHandleAndRole(identity_addr),
            static_cast<DistanceMeasurementMethod>(method));
  }

  // Callbacks of bluetooth::hci::DistanceMeasurementCallbacks
  void OnDistanceMeasurementStarted(bluetooth::hci::Address address,
                                    DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(&::DistanceMeasurementCallbacks::OnDistanceMeasurementStarted,
                                    base::Unretained(distance_measurement_callbacks_),
                                    bluetooth::ToRawAddress(address),
                                    static_cast<uint8_t>(method)));
  }

  void OnDistanceMeasurementStopped(bluetooth::hci::Address address,
                                    DistanceMeasurementErrorCode reason,
                                    DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(&::DistanceMeasurementCallbacks::OnDistanceMeasurementStopped,
                                    base::Unretained(distance_measurement_callbacks_),
                                    bluetooth::ToRawAddress(address), static_cast<uint8_t>(reason),
                                    static_cast<uint8_t>(method)));
  }

  void OnDistanceMeasurementResult(
          bluetooth::hci::Address address, uint32_t centimeter, uint32_t error_centimeter,
          int azimuth_angle, int error_azimuth_angle, int altitude_angle, int error_altitude_angle,
          uint64_t elapsed_realtime_nanos, int remote_tx_power, int rssi, int8_t confidence_level,
          double delay_spread_meters, DistanceMeasurementDetectedAttackLevel detected_attack_level,
          double velocity_meters_per_second, DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(
            &::DistanceMeasurementCallbacks::OnDistanceMeasurementResult,
            base::Unretained(distance_measurement_callbacks_), bluetooth::ToRawAddress(address),
            centimeter, error_centimeter, azimuth_angle, error_azimuth_angle, altitude_angle,
            error_altitude_angle, elapsed_realtime_nanos, remote_tx_power, rssi, confidence_level,
            delay_spread_meters, static_cast<uint8_t>(detected_attack_level),
            velocity_meters_per_second, static_cast<uint8_t>(method)));
  }

  void OnRasFragmentReady(bluetooth::hci::Address address, uint16_t procedure_counter, bool is_last,
                          std::vector<uint8_t> raw_data) override {
    do_in_main_thread(base::BindOnce(&bluetooth::ras::RasServer::PushProcedureData,
                                     base::Unretained(bluetooth::ras::GetRasServer()),
                                     bluetooth::ToRawAddress(address), procedure_counter, is_last,
                                     std::move(raw_data)));
  }

  void OnRangingHardwareOffloadEnabled() override {
    bluetooth::ras::GetRasClient()->NotifyRangingHardwareOffloadEnabled();
  }

  void OnVendorSpecificCharacteristics(std::vector<bluetooth::hal::VendorSpecificCharacteristic>
                                               vendor_specific_characteristics) override {
    std::vector<bluetooth::ras::VendorSpecificCharacteristic> ras_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_characteristics) {
      bluetooth::ras::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              bluetooth::Uuid::From128BitBE(characteristic.characteristicUuid_);
      vendor_specific_characteristic.value_ = characteristic.value_;
      ras_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }
    do_in_main_thread(base::BindOnce(&bluetooth::ras::RasServer::SetVendorSpecificCharacteristic,
                                     base::Unretained(bluetooth::ras::GetRasServer()),
                                     std::move(ras_vendor_specific_characteristics)));
  }

  void OnVendorSpecificReply(bluetooth::hci::Address address,
                             std::vector<bluetooth::hal::VendorSpecificCharacteristic>
                                     vendor_specific_characteristics) override {
    std::vector<bluetooth::ras::VendorSpecificCharacteristic> ras_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_characteristics) {
      bluetooth::ras::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              bluetooth::Uuid::From128BitBE(characteristic.characteristicUuid_);
      vendor_specific_characteristic.value_ = characteristic.value_;
      ras_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }
    do_in_main_thread(base::BindOnce(&bluetooth::ras::RasClient::SendVendorSpecificReply,
                                     base::Unretained(bluetooth::ras::GetRasClient()),
                                     bluetooth::ToRawAddress(address),
                                     std::move(ras_vendor_specific_characteristics)));
  }

  void OnHandleVendorSpecificReplyComplete(bluetooth::hci::Address address, bool success) override {
    do_in_main_thread(base::BindOnce(&bluetooth::ras::RasServer::HandleVendorSpecificReplyComplete,
                                     base::Unretained(bluetooth::ras::GetRasServer()),
                                     bluetooth::ToRawAddress(address), success));
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasServerCallbacks
  void OnVendorSpecificReply(const RawAddress& address,
                             const std::vector<bluetooth::ras::VendorSpecificCharacteristic>&
                                     vendor_specific_reply) override {
    std::vector<bluetooth::hal::VendorSpecificCharacteristic> hal_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_reply) {
      bluetooth::hal::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              characteristic.characteristicUuid_.To128BitBE();
      vendor_specific_characteristic.value_ = characteristic.reply_value_;
      hal_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }
    bluetooth::shim::GetDistanceMeasurementManager()->HandleVendorSpecificReply(
            address, GetConnectionHandleAndRole(address), hal_vendor_specific_characteristics);
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasServerCallbacks
  void OnRasServerConnected(const RawAddress& identity_address) override {
    hci::Role local_hci_role;
    uint16_t connection_handle = GetConnectionHandleAndRole(identity_address, &local_hci_role);
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRasServerConnected(
            identity_address, connection_handle, local_hci_role);
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasServerCallbacks
  void OnMtuChangedFromServer(const RawAddress& address, uint16_t mtu) override {
    handle_mtu_changed(address, mtu);
  }

  void OnMtuChangedFromClient(const RawAddress& address, uint16_t mtu) override {
    handle_mtu_changed(address, mtu);
  }

  void handle_mtu_changed(const RawAddress& address, uint16_t mtu) {
    uint16_t connection_handle = GetConnectionHandleAndRole(address);
    bluetooth::shim::GetDistanceMeasurementManager()->HandleMtuChanged(connection_handle, mtu);
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasSeverCallbacks
  void OnRasServerDisconnected(const RawAddress& identity_address) override {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRasServerDisconnected(
            identity_address, GetConnectionHandleAndRole(identity_address));
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasClientCallbacks
  void OnConnected(const RawAddress& address, uint16_t att_handle,
                   const std::vector<bluetooth::ras::VendorSpecificCharacteristic>&
                           vendor_specific_characteristics,
                   uint16_t conn_interval) override {
    std::vector<bluetooth::hal::VendorSpecificCharacteristic> hal_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_characteristics) {
      bluetooth::hal::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              characteristic.characteristicUuid_.To128BitBE();
      vendor_specific_characteristic.value_ = characteristic.value_;
      hal_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }

    bluetooth::shim::GetDistanceMeasurementManager()->HandleRasClientConnectedEvent(
            address, GetConnectionHandleAndRole(address), att_handle,
            hal_vendor_specific_characteristics, conn_interval);
  }

  void OnConnIntervalUpdated(const RawAddress& address, uint16_t conn_interval) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleConnIntervalUpdated(
            address, GetConnectionHandleAndRole(address), conn_interval);
  }

  void OnDisconnected(const RawAddress& address,
                      const ras::RasDisconnectReason& ras_disconnect_reason) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRasClientDisconnectedEvent(
            address, ras_disconnect_reason);
  }

  // Must be called from main_thread
  void OnWriteVendorSpecificReplyComplete(const RawAddress& address, bool success) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleVendorSpecificReplyComplete(
            address, GetConnectionHandleAndRole(address), success);
  }

  // Must be called from main_thread
  void OnRemoteData(const RawAddress& address, const std::vector<uint8_t>& data) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRemoteData(
            address, GetConnectionHandleAndRole(address), data);
  }

  // Must be called from main_thread
  void OnRemoteDataTimeout(const RawAddress& address) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRemoteDataTimeout(
            address, GetConnectionHandleAndRole(address));
  }

private:
  ::DistanceMeasurementCallbacks* distance_measurement_callbacks_;
  static constexpr uint16_t kIllegalConnectionHandle = 0xffff;
  static constexpr uint16_t kRetryIntervalMs = 200;
  static constexpr uint16_t kMaxRetryCount = 10;
};

void bluetooth::shim::init_distance_measurement_manager() {
  static_cast<DistanceMeasurementInterfaceImpl*>(
          bluetooth::shim::get_distance_measurement_instance())
          ->Init();
}

DistanceMeasurementInterface* bluetooth::shim::get_distance_measurement_instance() {
  static DistanceMeasurementInterfaceImpl* distance_measurement_instance = nullptr;
  if (distance_measurement_instance == nullptr) {
    distance_measurement_instance = new DistanceMeasurementInterfaceImpl();
  }
  return distance_measurement_instance;
}
