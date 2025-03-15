/******************************************************************************
 *
 *  Copyright 2018 Google, Inc.
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

#include <cstdint>
#include <vector>

#include "common/metrics.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace common {

void LogRemoteVersionInfo(uint16_t /* handle */, uint8_t /* status */, uint8_t /* version */,
                          uint16_t /* manufacturer_name */, uint16_t /* subversion */) {}

void LogLeAudioConnectionSessionReported(
        int32_t /* group_size */, int32_t /* group_metric_id */,
        int64_t /* connection_duration_nanos */,
        const std::vector<int64_t>& /* device_connecting_offset_nanos */,
        const std::vector<int64_t>& /* device_connected_offset_nanos */,
        const std::vector<int64_t>& /* device_connection_duration_nanos */,
        const std::vector<int32_t>& /* device_connection_status */,
        const std::vector<int32_t>& /* device_disconnection_status */,
        const std::vector<RawAddress>& /* device_address */,
        const std::vector<int64_t>& /* streaming_offset_nanos */,
        const std::vector<int64_t>& /* streaming_duration_nanos */,
        const std::vector<int32_t>& /* streaming_context_type */) {}

void LogLeAudioBroadcastSessionReported(int64_t /* duration_nanos */) {}

}  // namespace common
}  // namespace bluetooth
