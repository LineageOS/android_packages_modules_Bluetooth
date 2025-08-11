/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <cstdint>

#include "bta/sys/bta_sys.h"
#include "sniff_offload_structs.h"
#include "stack/include/hci_error_code.h"
#include "bluetooth/types/address.h"

namespace bluetooth {
namespace sniff_offload {

class SniffOffloadVscSender;
class SniffConfigReader;

static constexpr uint8_t kNumProfileStates = 8;

class SniffOffloadCallbacks {
public:
  virtual ~SniffOffloadCallbacks() = default;

  // Invoked when sniff offload is started with a reason.
  virtual void OnSniffOffloadStarted(tHCI_STATUS reason) = 0;

  // Invoked when link specific parameters are updated.
  virtual void OnLinkParamsUpdated(uint16_t connection_handle, SniffOffloadParameters params,
                                   tHCI_STATUS reason) = 0;
};

class SniffOffload {
public:
  virtual ~SniffOffload() = default;

  virtual void Start(uint16_t subrating_max_latency, uint16_t subrating_min_remote_timeout,
                     uint16_t subrating_min_local_timeout, bool suppress_mode_change_event,
                     bool suppress_subrating_event,
                     std::shared_ptr<sniff_offload::SniffOffloadCallbacks> callbacks) = 0;

  virtual void OnProfileStateChanged(uint16_t connection_handle, uint8_t profile_id, uint8_t app_id,
                                     ProfileState state) = 0;
};

std::shared_ptr<SniffOffload> GetSniffOffloadInstance(
        SniffConfigReader* config_reader, SniffOffloadVscSender* vsc_sender,
        std::chrono::milliseconds sniff_offload_update_delay_);

}  // namespace sniff_offload
}  // namespace bluetooth
