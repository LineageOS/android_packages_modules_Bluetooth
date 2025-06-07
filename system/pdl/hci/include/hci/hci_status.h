/******************************************************************************
 *
 *  Copyright 2025 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include "bt_status.h"
#include "hci/hci_packets.h"

using bluetooth::hci::ErrorCode;

static const std::string toStringHci(BtStatusCode code) {
  return ErrorCodeText(static_cast<ErrorCode>(code));
}

class HciStatus : public BtStatus {
public:
  HciStatus()
      : BtStatus(static_cast<BtStatusCode>(ErrorCode::SUCCESS), BtStatusOrigin::HCI, toStringHci) {}
  HciStatus(ErrorCode code)
      : BtStatus(static_cast<BtStatusCode>(code), BtStatusOrigin::HCI, toStringHci) {}
};
