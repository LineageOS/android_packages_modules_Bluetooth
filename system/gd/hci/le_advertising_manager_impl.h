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

#include <memory>
#include <vector>

#include "hci/controller.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"
#include "hci/le_address_manager.h"
#include "hci/le_advertising_manager.h"
#include "hci/le_on_advertising_set_terminated_interface.h"

namespace bluetooth {
namespace hci {

class LeAdvertisingManagerImpl : public LeAdvertisingManager {
public:
  static constexpr AdvertiserId kInvalidId = 0xFF;
  static constexpr uint8_t kInvalidHandle = 0xFF;
  static constexpr uint8_t kAdvertisingSetIdMask = 0x0F;
  static constexpr uint16_t kLeMaximumLegacyAdvertisingDataLength = 31;
  static constexpr uint16_t kLeMaximumFragmentLength = 251;
  static constexpr uint16_t kLeMaximumPeriodicDataFragmentLength = 252;
  static constexpr uint16_t kLeMaximumGapDataLength = 255;
  static constexpr FragmentPreference kFragment_preference =
          FragmentPreference::CONTROLLER_SHOULD_NOT;
  LeAdvertisingManagerImpl(os::Handler* handler, hci::HciInterface* hci_layer,
                           hci::Controller* controller, hci::LeAddressManager* le_address_manager,
                           hci::OnAdvertisingSetTerminatedInterface* on_set_terminated);
  LeAdvertisingManagerImpl(const LeAdvertisingManagerImpl&) = delete;
  LeAdvertisingManagerImpl& operator=(const LeAdvertisingManagerImpl&) = delete;
  ~LeAdvertisingManagerImpl() override;

  size_t GetNumberOfAdvertisingInstancesInUse() const override;

  int GetAdvertiserRegId(AdvertiserId advertiser_id) override;

  void ExtendedCreateAdvertiser(uint8_t client_id, int reg_id, const AdvertisingConfig config,
                                uint16_t duration, uint8_t max_extended_advertising_events,
                                os::Handler* handler) override;

  void StartAdvertising(AdvertiserId advertiser_id, const AdvertisingConfig config,
                        uint16_t duration,
                        base::OnceCallback<void(uint8_t /* status */)> status_callback,
                        base::OnceCallback<void(uint8_t /* status */)> timeout_callback,
                        os::Handler* handler) override;

  void GetOwnAddress(uint8_t advertiser_id) override;

  void RegisterAdvertiser(
          common::ContextualOnceCallback<void(uint8_t /* inst_id */,
                                              AdvertisingCallback::AdvertisingStatus /* status */)>
                  callback) override;

  void SetParameters(AdvertiserId advertiser_id, AdvertisingConfig config) override;

  void SetData(AdvertiserId advertiser_id, bool set_scan_rsp, std::vector<GapData> data) override;

  void EnableAdvertiser(AdvertiserId advertiser_id, bool enable, uint16_t duration,
                        uint8_t max_extended_advertising_events) override;

  void SetPeriodicParameters(
          AdvertiserId advertiser_id,
          PeriodicAdvertisingParameters periodic_advertising_parameters) override;

  void SetPeriodicData(AdvertiserId advertiser_id, std::vector<GapData> data) override;

  void EnablePeriodicAdvertising(AdvertiserId advertiser_id, bool enable,
                                 bool include_adi) override;

  void RemoveAdvertiser(AdvertiserId advertiser_id) override;

  void RegisterAdvertisingCallback(AdvertisingCallback* advertising_callback) override;

  void Dump(int fd) override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace hci
}  // namespace bluetooth
