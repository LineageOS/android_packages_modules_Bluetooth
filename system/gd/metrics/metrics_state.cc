/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "metrics_state.h"

#include <bluetooth/log.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/hci/enums.pb.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/le/enums.pb.h>

#include <chrono>
#include <climits>
#include <memory>
#include <unordered_map>
#include <utility>

#include "common/strings.h"
#include "hci/address.h"
#include "metrics/utils.h"
#include "os/log.h"
#include "os/metrics.h"

namespace bluetooth {
namespace metrics {

using android::bluetooth::le::LeConnectionOriginType;
using android::bluetooth::le::LeConnectionState;
using android::bluetooth::le::LeConnectionType;

// const static ClockTimePoint kInvalidTimePoint{};

/*
 * This is the device level metrics state, which will be modified based on
 * incoming state events.
 *
 */
void LEConnectionMetricState::AddStateChangedEvent(
    LeConnectionOriginType origin_type,
    LeConnectionType connection_type,
    LeConnectionState transaction_state,
    std::vector<std::pair<os::ArgumentType, int>> argument_list) {

  ClockTimePoint current_timestamp = std::chrono::high_resolution_clock::now();
  state = transaction_state;

  // Assign the origin of the connection
  if (connection_origin_type == LeConnectionOriginType::ORIGIN_UNSPECIFIED) {
    connection_origin_type = origin_type;
  }

  if (input_connection_type == LeConnectionType::CONNECTION_TYPE_UNSPECIFIED) {
    input_connection_type = connection_type;
  }

  if (start_timepoint == kInvalidTimePoint) {
    start_timepoint = current_timestamp;
  }
  end_timepoint = current_timestamp;

  switch (state) {
    case LeConnectionState::STATE_LE_ACL_START: {
      int connection_type_cid = GetArgumentTypeFromList(argument_list, os::ArgumentType::L2CAP_CID);
      if (connection_type_cid != -1) {
        LeConnectionType connection_type = GetLeConnectionTypeFromCID(connection_type_cid);
        if (connection_type != LeConnectionType::CONNECTION_TYPE_UNSPECIFIED) {
          log::info("LEConnectionMetricsRemoteDevice: Populating the connection type");
          input_connection_type = connection_type;
        }
      }
      break;
    }
    case LeConnectionState::STATE_LE_ACL_END: {
      int acl_status_code_from_args =
          GetArgumentTypeFromList(argument_list, os::ArgumentType::ACL_STATUS_CODE);
      acl_status_code = static_cast<android::bluetooth::hci::StatusEnum>(acl_status_code_from_args);
      acl_state = LeAclConnectionState::LE_ACL_SUCCESS;

      if (acl_status_code != android::bluetooth::hci::StatusEnum::STATUS_SUCCESS) {
        acl_state = LeAclConnectionState::LE_ACL_FAILED;
      }
      break;
    }
    case LeConnectionState::STATE_LE_ACL_TIMEOUT: {
      int acl_status_code_from_args =
          GetArgumentTypeFromList(argument_list, os::ArgumentType::ACL_STATUS_CODE);
      acl_status_code = static_cast<android::bluetooth::hci::StatusEnum>(acl_status_code_from_args);
      acl_state = LeAclConnectionState::LE_ACL_FAILED;
      break;
    }
    case LeConnectionState::STATE_LE_ACL_CANCEL: {
      acl_state = LeAclConnectionState::LE_ACL_FAILED;
      is_cancelled = true;
      break;
    }
      [[fallthrough]];
    default: {
      // do nothing
    }
  }
}

bool LEConnectionMetricState::IsEnded() {
  return acl_state == LeAclConnectionState::LE_ACL_SUCCESS ||
         acl_state == LeAclConnectionState::LE_ACL_FAILED;
}

bool LEConnectionMetricState::IsStarted() {
  return state == LeConnectionState::STATE_LE_ACL_START;
}

bool LEConnectionMetricState::IsCancelled() {
  return is_cancelled;
}

// Initialize the LEConnectionMetricsRemoteDevice
LEConnectionMetricsRemoteDevice::LEConnectionMetricsRemoteDevice() {
  metrics_logger_module = new MetricsLoggerModule();
}

LEConnectionMetricsRemoteDevice::LEConnectionMetricsRemoteDevice(
    BaseMetricsLoggerModule* baseMetricsLoggerModule) {
  metrics_logger_module = baseMetricsLoggerModule;
}

// Uploading the session
void LEConnectionMetricsRemoteDevice::UploadLEConnectionSession(const hci::Address& address) {
  auto it = opened_devices.find(address);
  if (it != opened_devices.end()) {
    os::LEConnectionSessionOptions session_options;
    session_options.acl_connection_state = it->second->acl_state;
    session_options.origin_type = it->second->connection_origin_type;
    session_options.transaction_type = it->second->input_connection_type;
    session_options.latency = bluetooth::metrics::get_timedelta_nanos(
        it->second->start_timepoint, it->second->end_timepoint);
    session_options.remote_address = address;
    session_options.status = it->second->acl_status_code;
    // TODO: keep the acl latency the same as the overall latency for now
    // When more events are added, we will an overall latency
    session_options.acl_latency = session_options.latency;
    session_options.is_cancelled = it->second->is_cancelled;
    metrics_logger_module->LogMetricBluetoothLESession(session_options);
    log::info(
        "LEConnectionMetricsRemoteDevice: The session is uploaded for {}",
        ADDRESS_TO_LOGGABLE_CSTR(address));
    opened_devices.erase(it);
  }
}

// Implementation of metrics per remote device
void LEConnectionMetricsRemoteDevice::AddStateChangedEvent(
    const hci::Address& address,
    LeConnectionOriginType origin_type,
    LeConnectionType connection_type,
    LeConnectionState transaction_state,
    std::vector<std::pair<os::ArgumentType, int>> argument_list) {
  log::info(
      "LEConnectionMetricsRemoteDevice: Transaction State {}, Connection Type {}, Origin Type {}",
      common::ToHexString(transaction_state),
      common::ToHexString(connection_type),
      common::ToHexString(origin_type));

  std::unique_lock<std::mutex> lock(le_connection_metrics_remote_device_guard);
  if (address.IsEmpty()) {
    log::info(
        "LEConnectionMetricsRemoteDevice: Empty Address Cancellation {}, {}, {}",
        common::ToHexString(transaction_state),
        common::ToHexString(connection_type),
        common::ToHexString(transaction_state));
    for (auto& device_metric : device_metrics) {
      if (device_metric->IsStarted() &&
          transaction_state == LeConnectionState::STATE_LE_ACL_CANCEL) {
        log::info("LEConnectionMetricsRemoteDevice: Cancellation Begin");
        // cancel the connection
        device_metric->AddStateChangedEvent(
            origin_type, connection_type, transaction_state, argument_list);
        continue;
      }

      if (device_metric->IsCancelled() &&
          transaction_state == LeConnectionState::STATE_LE_ACL_END) {
        // complete the connection
        device_metric->AddStateChangedEvent(
            origin_type, connection_type, transaction_state, argument_list);
        UploadLEConnectionSession(address);
        continue;
      }
    }
    return;
  }

  auto it = opened_devices.find(address);
  if (it == opened_devices.end()) {
    device_metrics.push_back(std::make_unique<LEConnectionMetricState>(address));
    it = opened_devices.insert(std::begin(opened_devices), {address, device_metrics.back().get()});
  }

  it->second->AddStateChangedEvent(origin_type, connection_type, transaction_state, argument_list);

  // Connection is finished
  if (it->second->IsEnded()) {
    UploadLEConnectionSession(address);
  }
}


// MetricsLoggerModule class
void MetricsLoggerModule::LogMetricBluetoothLESession(
    os::LEConnectionSessionOptions session_options) {
  os::LogMetricBluetoothLEConnection(session_options);
}

// Instance of Metrics Collector for LEConnectionMetricsRemoteDeviceImpl
LEConnectionMetricsRemoteDevice* MetricsCollector::le_connection_metrics_remote_device =
    new LEConnectionMetricsRemoteDevice();

LEConnectionMetricsRemoteDevice* MetricsCollector::GetLEConnectionMetricsCollector() {
  return MetricsCollector::le_connection_metrics_remote_device;
}

}  // namespace metrics

}  // namespace bluetooth
