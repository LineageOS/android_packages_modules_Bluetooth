/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <stdint.h>

#include <vector>

#include "hci/address.h"

using android::bluetooth::ChannelSoundingSecurityLevel;
using android::bluetooth::ChannelSoundingStopReason;
using android::bluetooth::ChannelSoundingType;

namespace bluetooth {
namespace hci {
namespace cs {
struct RequesterSessionMetrics {
  // Locally generated id for event matching
  Address remote_addr;

  // Uids of the apps which request the measurement
  std::vector<int32_t> app_uids;

  // Channel Sounding security levels requested by the apps
  std::vector<int32_t> security_levels;

  // Intervals requested by the apps
  std::vector<int32_t> measurement_interval_ms;

  // The reason about why the session is stopped
  ChannelSoundingStopReason stop_reason = ChannelSoundingStopReason::REASON_UNSPECIFIED;

  int64_t setup_end_timestamp_nanos;

  int64_t ranging_start_timestamp_nanos;
  int64_t ranging_end_timestamp_nanos;

  // If the back to back was detected, the device is used as both requester and responder
  bool back_to_back = false;

  // The channel sounding type used for this session
  ChannelSoundingType cs_type = ChannelSoundingType::CS_BT_CORE60;

  // The minimum number of subevent_len from the procedure_enable_complete command
  int32_t min_subevent_len = 0x01000000;  // max valid value is 0x00FFFFFF;

  // The count of min_subevent_len, use to estimate the duration of min_subevent_len
  int32_t min_subevent_len_count = 0;

  // not part of the metrics, for duplicated reporting check in DM.
  bool reported = false;
};

void LogMetricsChannelSoundingRequesterSessionReported(
        const RequesterSessionMetrics& requester_session_metrics);
}  // namespace cs
}  // namespace hci
}  // namespace bluetooth
