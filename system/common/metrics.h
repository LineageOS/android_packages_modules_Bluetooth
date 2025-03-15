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

#pragma once

#include <cstdint>
#include <vector>

#include "types/raw_address.h"

namespace bluetooth {
namespace common {

/**
 * Unknown connection handle for metrics purpose
 */
static const uint32_t kUnknownConnectionHandle = 0xFFFF;

/**
 * Logs when we receive Bluetooth Read Remote Version Information Complete
 * Event from the remote device, as documented by the Bluetooth Core HCI
 * specification
 *
 * Reference: 5.0 Core Specification, Vol 2, Part E, Page 1118
 *
 * @param handle handle of associated ACL connection
 * @param status HCI command status of this event
 * @param version version code from read remote version complete event
 * @param manufacturer_name manufacturer code from read remote version complete
 *                          event
 * @param subversion subversion code from read remote version complete event
 */
void LogRemoteVersionInfo(uint16_t handle, uint8_t status, uint8_t version,
                          uint16_t manufacturer_name, uint16_t subversion);

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
        const std::vector<int32_t>& streaming_context_type);

void LogLeAudioBroadcastSessionReported(int64_t duration_nanos);

}  // namespace common
}  // namespace bluetooth
