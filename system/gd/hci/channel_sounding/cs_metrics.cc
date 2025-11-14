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
#include "cs_metrics.h"

#include <bluetooth/metrics/os_metrics.h>

namespace bluetooth {
namespace hci {
namespace cs {

void LogMetricsChannelSoundingRequesterSessionReported(
        const RequesterSessionMetrics& requester_session_metrics) {
  int32_t setup_latency_ms = 0;
  if (requester_session_metrics.setup_end_timestamp_nanos >
      requester_session_metrics.ranging_start_timestamp_nanos) {
    setup_latency_ms =
            static_cast<int32_t>((requester_session_metrics.setup_end_timestamp_nanos -
                                  requester_session_metrics.ranging_start_timestamp_nanos) /
                                 1e6);
  }
  int32_t duration_seconds =
          static_cast<int32_t>((requester_session_metrics.ranging_end_timestamp_nanos -
                                requester_session_metrics.ranging_start_timestamp_nanos) /
                               1e9);
  bluetooth::metrics::LogMetricsChannelSoundingRequesterSessionReported(
          requester_session_metrics.remote_addr, requester_session_metrics.app_uids,
          requester_session_metrics.security_levels,
          requester_session_metrics.measurement_interval_ms, requester_session_metrics.stop_reason,
          setup_latency_ms, duration_seconds, requester_session_metrics.back_to_back,
          requester_session_metrics.cs_type, requester_session_metrics.min_subevent_len,
          requester_session_metrics.min_subevent_len_count);
}

}  // namespace cs
}  // namespace hci
}  // namespace bluetooth
