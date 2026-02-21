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

#include <gmock/gmock.h>

#include "hci/le_advertising_manager.h"

// Unit test interfaces
namespace bluetooth {
namespace hci {

namespace testing {

using hci::AdvertiserId;
class MockLeAdvertisingManager : public LeAdvertisingManager {
public:
  MOCK_METHOD(size_t, GetNumberOfAdvertisingInstancesInUse, (), (const override));

  MOCK_METHOD(int, GetAdvertiserRegId, (AdvertiserId advertiser_id), (override));

  MOCK_METHOD(void, ExtendedCreateAdvertiser,
              (uint8_t client_id, int reg_id, const AdvertisingConfig config, uint16_t duration,
               uint8_t max_extended_advertising_events, os::Handler* handler),
              (override));

  MOCK_METHOD(void, StartAdvertising,
              (AdvertiserId advertiser_id, const AdvertisingConfig config, uint16_t duration,
               base::OnceCallback<void(uint8_t /* status */)> status_callback,
               base::OnceCallback<void(uint8_t /* status */)> timeout_callback,
               os::Handler* handler),
              (override));

  MOCK_METHOD(void, GetOwnAddress, (uint8_t advertiser_id), (override));

  MOCK_METHOD(void, RegisterAdvertiser,
              (common::ContextualOnceCallback<void(
                       uint8_t /* inst_id */, AdvertisingCallback::AdvertisingStatus /* status */)>
                       callback),
              (override));

  MOCK_METHOD(void, SetParameters, (AdvertiserId advertiser_id, AdvertisingConfig config),
              (override));

  MOCK_METHOD(void, SetData,
              (AdvertiserId advertiser_id, bool set_scan_rsp, std::vector<GapData> data),
              (override));

  MOCK_METHOD(void, EnableAdvertiser,
              (AdvertiserId advertiser_id, bool enable, uint16_t duration,
               uint8_t max_extended_advertising_events),
              (override));

  MOCK_METHOD(void, SetPeriodicParameters,
              (AdvertiserId advertiser_id,
               PeriodicAdvertisingParameters periodic_advertising_parameters),
              (override));

  MOCK_METHOD(void, SetPeriodicData, (AdvertiserId advertiser_id, std::vector<GapData> data),
              (override));

  MOCK_METHOD(void, EnablePeriodicAdvertising,
              (AdvertiserId advertiser_id, bool enable, bool include_adi), (override));

  MOCK_METHOD(void, RemoveAdvertiser, (AdvertiserId advertiser_id), (override));

  MOCK_METHOD(void, RegisterAdvertisingCallback, (AdvertisingCallback * advertising_callback),
              (override));

  MOCK_METHOD(void, Dump, (int fd), (override));
};

}  // namespace testing
}  // namespace hci
}  // namespace bluetooth
