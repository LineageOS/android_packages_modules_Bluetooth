/******************************************************************************
 *
 *  Copyright 2016 Google, Inc.
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

#include "common/metrics.h"

#include <bluetooth/log.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/le/enums.pb.h>
#include <statslog_bt.h>

#include <cstdint>
#include <vector>

#include "main/shim/metric_id_api.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace common {

void LogRemoteVersionInfo(uint16_t handle, uint8_t status, uint8_t version,
                          uint16_t manufacturer_name, uint16_t subversion) {
  int ret = stats_write(BLUETOOTH_REMOTE_VERSION_INFO_REPORTED, handle, status, version,
                        manufacturer_name, subversion);
  if (ret < 0) {
    log::warn(
            "failed for handle {}, status 0x{:x}, version 0x{:x}, "
            "manufacturer_name 0x{:x}, subversion 0x{:x}, error {}",
            handle, status, version, manufacturer_name, subversion, ret);
  }
}

void LogLeAudioConnectionSessionReported(
        int32_t group_size, int32_t group_metric_id, int64_t connection_duration_nanos,
        const std::vector<int64_t>& device_connecting_offset_nanos,
        const std::vector<int64_t>& device_connected_offset_nanos,
        const std::vector<int64_t>& device_connection_duration_nanos,
        const std::vector<int32_t>& device_connection_status,
        const std::vector<int32_t>& device_disconnection_status,
        const std::vector<RawAddress>& device_address,
        const std::vector<int64_t>& streaming_offset_nanos,
        const std::vector<int64_t>& streaming_duration_nanos,
        const std::vector<int32_t>& streaming_context_type) {
  std::vector<int32_t> device_metric_id(device_address.size());
  for (uint64_t i = 0; i < device_address.size(); i++) {
    if (!device_address[i].IsEmpty()) {
      device_metric_id[i] = bluetooth::shim::AllocateIdFromMetricIdAllocator(device_address[i]);
    } else {
      device_metric_id[i] = 0;
    }
  }
  int ret = stats_write(LE_AUDIO_CONNECTION_SESSION_REPORTED, group_size, group_metric_id,
                        connection_duration_nanos, device_connecting_offset_nanos,
                        device_connected_offset_nanos, device_connection_duration_nanos,
                        device_connection_status, device_disconnection_status, device_metric_id,
                        streaming_offset_nanos, streaming_duration_nanos, streaming_context_type);
  if (ret < 0) {
    log::warn(
            "failed for group {}device_connecting_offset_nanos[{}], "
            "device_connected_offset_nanos[{}], "
            "device_connection_duration_nanos[{}], device_connection_status[{}], "
            "device_disconnection_status[{}], device_metric_id[{}], "
            "streaming_offset_nanos[{}], streaming_duration_nanos[{}], "
            "streaming_context_type[{}]",
            group_metric_id, device_connecting_offset_nanos.size(),
            device_connected_offset_nanos.size(), device_connection_duration_nanos.size(),
            device_connection_status.size(), device_disconnection_status.size(),
            device_metric_id.size(), streaming_offset_nanos.size(), streaming_duration_nanos.size(),
            streaming_context_type.size());
  }
}

void LogLeAudioBroadcastSessionReported(int64_t duration_nanos) {
  int ret = stats_write(LE_AUDIO_BROADCAST_SESSION_REPORTED, duration_nanos);
  if (ret < 0) {
    log::warn("failed for duration={}", duration_nanos);
  }
}

}  // namespace common
}  // namespace bluetooth
