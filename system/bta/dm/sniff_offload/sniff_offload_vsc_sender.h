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
#include <functional>

#include "sniff_offload_structs.h"
#include "stack/include/hci_error_code.h"

namespace bluetooth {
namespace sniff_offload {

using WriteSniffOffloadEnableCompleteCallback = std::function<void(tHCI_STATUS status)>;
using WriteSniffOffloadParametersCompleteCallback =
        std::function<void(uint16_t acl_handle, tHCI_STATUS status)>;

class SniffOffloadVscSender {
public:
  virtual ~SniffOffloadVscSender() = default;

  virtual void WriteSniffOffloadEnable(uint16_t subrate_max_latency,
                                       uint16_t subrate_min_remote_timeout,
                                       uint16_t subrate_min_local_timeout,
                                       bool suppress_mode_change_event,
                                       bool suppress_subrating_event,
                                       WriteSniffOffloadEnableCompleteCallback callback) = 0;

  virtual void WriteSniffOffloadParameters(
          uint16_t acl_handle, SniffOffloadParameters params,
          WriteSniffOffloadParametersCompleteCallback callback) = 0;
};

SniffOffloadVscSender& GetSniffOffloadVscSender();

}  // namespace sniff_offload
}  // namespace bluetooth
