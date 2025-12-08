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

namespace bluetooth {
namespace sniff_offload {

typedef tBTA_SYS_CONN_STATUS ProfileState;
typedef tBTA_SYS_ID ProfileId;

struct SniffOffloadParameters {
  uint16_t sniff_max_interval;
  uint16_t sniff_min_interval;
  uint16_t sniff_attempts;
  uint16_t sniff_timeout;
  uint16_t link_idle_timeout;
  uint16_t subrate_max_latency;
  uint16_t min_remote_timeout;
  uint16_t min_local_timeout;
  uint8_t allow_exit_on_rx;
  uint8_t allow_exit_on_tx;

  std::string ToString() const {
    return std::format(
            "sniff_max_interval: {}, sniff_min_interval: {}, "
            "sniff_attempts: {}, sniff_timeout: {}, "
            "link_idle_timeout: {}, subrate_max_latency: {}, "
            "min_remote_timeout: {}, min_local_timeout: {}, "
            "allow_exit_on_rx: {}, allow_exit_on_tx: {}",
            sniff_max_interval, sniff_min_interval, sniff_attempts, sniff_timeout,
            link_idle_timeout, subrate_max_latency, min_remote_timeout, min_local_timeout,
            allow_exit_on_rx, allow_exit_on_tx);
  }

  bool operator==(const SniffOffloadParameters& other) const {
    return sniff_max_interval == other.sniff_max_interval &&
           sniff_min_interval == other.sniff_min_interval &&
           sniff_attempts == other.sniff_attempts && sniff_timeout == other.sniff_timeout &&
           link_idle_timeout == other.link_idle_timeout &&
           subrate_max_latency == other.subrate_max_latency &&
           min_remote_timeout == other.min_remote_timeout &&
           min_local_timeout == other.min_local_timeout &&
           allow_exit_on_rx == other.allow_exit_on_rx && allow_exit_on_tx == other.allow_exit_on_tx;
  }
};

enum class Priority : uint8_t {
  kNoPriority = 0,
  kPriority1,
  kPriority2,
  kPriority3,
  kPriority4,
  kPriority5,
  kPriority6,
  kPriority7,
  kPriority8,
  kPriorityHighest = 100,
};

struct SniffOffloadConfig {
  SniffOffloadParameters parameters_;
  Priority priority_;
  bool allow_subrating_update_;
};

}  // namespace sniff_offload
}  // namespace bluetooth
