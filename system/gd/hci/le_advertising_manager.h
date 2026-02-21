/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <base/functional/callback.h>
#include <bluetooth/log.h>

#include <vector>

#include "hci/hci_packets.h"
#include "os/handler.h"

namespace bluetooth {
namespace hci {

class PeriodicAdvertisingParameters {
public:
  bool enable;
  bool include_adi;
  uint16_t min_interval;
  uint16_t max_interval;
  uint16_t properties;
  enum AdvertisingProperty { INCLUDE_TX_POWER = 0x06 };
};

enum class AdvertiserAddressType {
  PUBLIC,
  RESOLVABLE_RANDOM,
  NONRESOLVABLE_RANDOM,
};

class AdvertisingConfig {
public:
  std::vector<GapData> advertisement;
  std::vector<GapData> scan_response;
  uint32_t interval_min;
  uint32_t interval_max;
  AdvertiserAddressType requested_advertiser_address_type;
  PeerAddressType peer_address_type;
  Address peer_address;
  uint8_t channel_map;
  AdvertisingFilterPolicy filter_policy;
  uint8_t tx_power;  // -127 to +20 (0x7f is no preference)
  bool connectable = false;
  bool discoverable = false;
  bool scannable = false;
  bool directed = false;
  bool high_duty_cycle = false;
  bool legacy_pdus = false;
  bool anonymous = false;
  bool include_tx_power = false;
  bool use_le_coded_phy;       // Primary advertisement PHY is LE Coded
  uint8_t secondary_max_skip;  // maximum advertising events to be skipped, 0x0 send AUX_ADV_IND
                               // prior ot the next event
  SecondaryPhyType secondary_advertising_phy;
  uint8_t sid = 0x00;
  Enable enable_scan_request_notifications = Enable::DISABLED;
  std::vector<GapData> periodic_data;
  PeriodicAdvertisingParameters periodic_advertising_parameters;
  AdvertisingConfig() = default;
};

using AdvertiserId = uint8_t;

class AdvertisingCallback {
public:
  enum AdvertisingStatus {
    SUCCESS,
    DATA_TOO_LARGE,
    TOO_MANY_ADVERTISERS,
    ALREADY_STARTED,
    INTERNAL_ERROR,
    FEATURE_UNSUPPORTED,
    TIMEOUT
  };

  virtual ~AdvertisingCallback() = default;
  virtual void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id, int8_t tx_power,
                                       AdvertisingStatus status) = 0;
  virtual void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                    AdvertisingStatus status) = 0;
  virtual void OnAdvertisingDataSet(uint8_t advertiser_id, AdvertisingStatus status) = 0;
  virtual void OnScanResponseDataSet(uint8_t advertiser_id, AdvertisingStatus status) = 0;
  virtual void OnAdvertisingParametersUpdated(uint8_t advertiser_id, int8_t tx_power,
                                              AdvertisingStatus status) = 0;
  virtual void OnPeriodicAdvertisingParametersUpdated(uint8_t advertiser_id,
                                                      AdvertisingStatus status) = 0;
  virtual void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id, AdvertisingStatus status) = 0;
  virtual void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                            AdvertisingStatus status) = 0;
  virtual void OnOwnAddressRead(uint8_t advertiser_id, uint8_t address_type, Address address) = 0;
};

class LeAdvertisingManager {
public:
  virtual ~LeAdvertisingManager() = default;

  virtual size_t GetNumberOfAdvertisingInstancesInUse() const = 0;

  virtual int GetAdvertiserRegId(AdvertiserId advertiser_id) = 0;

  virtual void ExtendedCreateAdvertiser(uint8_t client_id, int reg_id,
                                        const AdvertisingConfig config, uint16_t duration,
                                        uint8_t max_extended_advertising_events,
                                        os::Handler* handler) = 0;

  virtual void StartAdvertising(AdvertiserId advertiser_id, const AdvertisingConfig config,
                                uint16_t duration,
                                base::OnceCallback<void(uint8_t /* status */)> status_callback,
                                base::OnceCallback<void(uint8_t /* status */)> timeout_callback,
                                os::Handler* handler) = 0;

  virtual void GetOwnAddress(uint8_t advertiser_id) = 0;

  virtual void RegisterAdvertiser(
          common::ContextualOnceCallback<void(uint8_t /* inst_id */,
                                              AdvertisingCallback::AdvertisingStatus /* status */)>
                  callback) = 0;

  virtual void SetParameters(AdvertiserId advertiser_id, AdvertisingConfig config) = 0;

  virtual void SetData(AdvertiserId advertiser_id, bool set_scan_rsp,
                       std::vector<GapData> data) = 0;

  virtual void EnableAdvertiser(AdvertiserId advertiser_id, bool enable, uint16_t duration,
                                uint8_t max_extended_advertising_events) = 0;

  virtual void SetPeriodicParameters(
          AdvertiserId advertiser_id,
          PeriodicAdvertisingParameters periodic_advertising_parameters) = 0;

  virtual void SetPeriodicData(AdvertiserId advertiser_id, std::vector<GapData> data) = 0;

  virtual void EnablePeriodicAdvertising(AdvertiserId advertiser_id, bool enable,
                                         bool include_adi) = 0;

  virtual void RemoveAdvertiser(AdvertiserId advertiser_id) = 0;

  virtual void RegisterAdvertisingCallback(AdvertisingCallback* advertising_callback) = 0;

  virtual void Dump(int fd) = 0;
};

}  // namespace hci
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::hci::AdvertiserAddressType>
    : enum_formatter<bluetooth::hci::AdvertiserAddressType> {};
}  // namespace std
