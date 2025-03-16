/*
 * Copyright (C) 2022 The Android Open Source Project
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
#define LOG_TAG "BluetoothMetrics"

#include "metrics/metrics.h"

#include <bluetooth/log.h>
#include <metrics/structured_events.h>

#include "common/time_util.h"
#include "metrics/chromeos/metrics_allowlist.h"
#include "metrics/chromeos/metrics_event.h"
#include "metrics/utils.h"

namespace bluetooth {
namespace metrics {

static constexpr uint32_t DEVICE_MAJOR_CLASS_MASK = 0x1F00;
static constexpr uint32_t DEVICE_MAJOR_CLASS_BIT_OFFSET = 8;
static constexpr uint32_t DEVICE_CATEGORY_MASK = 0xFFC0;
static constexpr uint32_t DEVICE_CATEGORY_BIT_OFFSET = 6;

void LogMetricsAdapterStateChanged(uint32_t state) {
  int64_t adapter_state;
  int64_t boot_time;
  std::string boot_id;

  if (!GetBootId(&boot_id)) {
    return;
  }

  adapter_state = (int64_t)ToAdapterState(state);
  boot_time = bluetooth::common::time_get_os_boottime_us();

  log::debug("AdapterStateChanged: {}, {}, {}", boot_id, boot_time, adapter_state);

  ::metrics::structured::events::bluetooth::BluetoothAdapterStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetIsFloss(true)
          .SetAdapterState(adapter_state)
          .Record();

  LogMetricsChipsetInfoReport();
}

void LogMetricsBondCreateAttempt(RawAddress* addr, uint32_t device_type) {
  ConnectionType connection_type;
  int64_t boot_time;
  std::string addr_string;
  std::string boot_id;

  if (!GetBootId(&boot_id)) {
    return;
  }

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();
  connection_type = ToPairingDeviceType(addr_string, device_type);

  log::debug("PairingStateChanged: {}, {}, {}, {}, {}", boot_id, (int)boot_time, *addr,
             (int)connection_type, (int)PairingState::PAIR_STARTING);

  ::metrics::structured::events::bluetooth::BluetoothPairingStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetDeviceId(addr_string)
          .SetDeviceType((int64_t)connection_type)
          .SetPairingState((int64_t)PairingState::PAIR_STARTING)
          .Record();
}

void LogMetricsBondStateChanged(RawAddress* addr, uint32_t device_type, uint32_t status,
                                uint32_t bond_state, int32_t fail_reason) {
  ConnectionType connection_type;
  int64_t boot_time;
  PairingState pairing_state;
  std::string addr_string;
  std::string boot_id;

  if (!GetBootId(&boot_id)) {
    return;
  }

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();
  connection_type = ToPairingDeviceType(addr_string, device_type);
  pairing_state = ToPairingState(status, bond_state, fail_reason);

  // Ignore the start of pairing event as its logged separated above.
  if (pairing_state == PairingState::PAIR_STARTING) {
    return;
  }

  // Ignore absurd state.
  if (pairing_state == PairingState::PAIR_FAIL_END) {
    return;
  }

  log::debug("PairingStateChanged: {}, {}, {}, {}, {}", boot_id, (int)boot_time, *addr,
             (int)connection_type, (int)pairing_state);

  ::metrics::structured::events::bluetooth::BluetoothPairingStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetDeviceId(addr_string)
          .SetDeviceType((int64_t)connection_type)
          .SetPairingState((int64_t)pairing_state)
          .Record();
}

void LogMetricsDeviceInfoReport(RawAddress* addr, uint32_t device_type, uint32_t class_of_device,
                                uint32_t appearance, uint32_t vendor_id, uint32_t vendor_id_src,
                                uint32_t product_id, uint32_t version) {
  int64_t boot_time;
  std::string addr_string;
  std::string boot_id;
  uint32_t major_class;
  uint32_t category;

  if (!GetBootId(&boot_id)) {
    return;
  }

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();

  major_class = (class_of_device & DEVICE_MAJOR_CLASS_MASK) >> DEVICE_MAJOR_CLASS_BIT_OFFSET;
  category = (appearance & DEVICE_CATEGORY_MASK) >> DEVICE_CATEGORY_BIT_OFFSET;

  log::debug("DeviceInfoReport {} {} {} {} {} {} {} {} {} {}", boot_id, (int)boot_time, *addr,
             (int)device_type, (int)major_class, (int)category, (int)vendor_id, (int)vendor_id_src,
             (int)product_id, (int)version);

  if (!IsDeviceInfoInAllowlist(vendor_id_src, vendor_id, product_id)) {
    vendor_id_src = 0;
    vendor_id = 0;
    product_id = 0;
    version = 0;
  }

  ::metrics::structured::events::bluetooth::BluetoothDeviceInfoReport()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetDeviceId(addr_string)
          .SetDeviceType(device_type)
          .SetDeviceClass(major_class)
          .SetDeviceCategory(category)
          .SetVendorId(vendor_id)
          .SetVendorIdSource(vendor_id_src)
          .SetProductId(product_id)
          .SetProductVersion(version)
          .Record();
}

void LogMetricsProfileConnectionStateChanged(RawAddress* addr, uint32_t profile, uint32_t status,
                                             uint32_t state) {
  int64_t boot_time;
  std::string addr_string;
  std::string boot_id;

  if (!GetBootId(&boot_id)) {
    return;
  }

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();

  ProfileConnectionEvent event = ToProfileConnectionEvent(addr_string, profile, status, state);

  if (Profile::UNKNOWN == (Profile)event.profile) {
    return;
  }

  log::debug("ProfileConnectionStateChanged: {}, {}, {}, {}, {}, {}", boot_id, (int)boot_time,
             *addr, (int)event.type, (int)event.profile, (int)event.state);

  ::metrics::structured::events::bluetooth::BluetoothProfileConnectionStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetDeviceId(addr_string)
          .SetStateChangeType((int64_t)event.type)
          .SetProfile((int64_t)event.profile)
          .SetProfileConnectionState((int64_t)event.state)
          .Record();
}

void LogMetricsAclConnectAttempt(RawAddress* addr, uint32_t acl_state) {
  int64_t boot_time = bluetooth::common::time_get_os_boottime_us();
  std::string addr_string = addr->ToString();

  // At this time we don't know the transport layer, therefore pending on sending the event
  PendingAclConnectAttemptEvent(addr_string, boot_time, acl_state);
}

void LogMetricsAclConnectionStateChanged(RawAddress* addr, uint32_t transport, uint32_t acl_status,
                                         uint32_t acl_state, uint32_t direction,
                                         uint32_t hci_reason) {
  int64_t boot_time;
  std::string addr_string;
  std::string boot_id;
  bool attempt_found;
  AclConnectionEvent event;

  boot_time = bluetooth::common::time_get_os_boottime_us();
  addr_string = addr->ToString();

  event = ToAclConnectionEvent(addr_string, boot_time, acl_status, acl_state, direction,
                               hci_reason);

  if (!GetBootId(&boot_id)) {
    return;
  }

  log::debug("AclConnectionStateChanged: {}, {}, {}, {}, {}, {}, {}, {}", boot_id,
             (int)event.start_time, *addr, (int)transport, (int)event.direction,
             (int)event.initiator, (int)event.state, (int)event.start_status);

  ::metrics::structured::events::bluetooth::BluetoothAclConnectionStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(event.start_time)
          .SetIsFloss(true)
          .SetDeviceId(addr_string)
          .SetDeviceType(transport)
          .SetConnectionDirection(event.direction)
          .SetConnectionInitiator(event.initiator)
          .SetStateChangeType(event.state)
          .SetAclConnectionState(event.start_status)
          .Record();

  log::debug("AclConnectionStateChanged: {}, {}, {}, {}, {}, {}, {}, {}", boot_id, (int)boot_time,
             *addr, (int)transport, (int)event.direction, (int)event.initiator, (int)event.state,
             (int)event.status);

  ::metrics::structured::events::bluetooth::BluetoothAclConnectionStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetIsFloss(true)
          .SetDeviceId(addr_string)
          .SetDeviceType(transport)
          .SetConnectionDirection(event.direction)
          .SetConnectionInitiator(event.initiator)
          .SetStateChangeType(event.state)
          .SetAclConnectionState(event.status)
          .Record();

  LogMetricsChipsetInfoReport();
}

void LogMetricsChipsetInfoReport() {
  static MetricsChipsetInfo* info = NULL;
  uint64_t chipset_string_hval = 0;
  std::string boot_id;

  if (!info) {
    info = (MetricsChipsetInfo*)calloc(1, sizeof(MetricsChipsetInfo));
    *info = GetMetricsChipsetInfo();
  }

  if (!GetBootId(&boot_id)) {
    return;
  }

  log::debug("ChipsetInfoReport: 0x{:x} 0x{:x} {} {}", info->vid, info->pid, info->transport,
             info->chipset_string);

  if (IsChipsetInfoInAllowList(info->vid, info->pid, info->transport, info->chipset_string.c_str(),
                               &chipset_string_hval)) {
    ::metrics::structured::events::bluetooth::BluetoothChipsetInfoReport()
            .SetBootId(boot_id.c_str())
            .SetVendorId(info->vid)
            .SetProductId(info->pid)
            .SetTransport(info->transport)
            .SetChipsetStringHashValue(chipset_string_hval);
  }
}

void LogMetricsSuspendIdState(uint32_t state) {
  int64_t suspend_id_state = 0;
  int64_t boot_time;
  std::string boot_id;

  if (!GetBootId(&boot_id)) {
    return;
  }

  boot_time = bluetooth::common::time_get_os_boottime_us();

  suspend_id_state = (int64_t)ToSuspendIdState(state);
  log::debug("SuspendIdState: {}, {}, {}", boot_id, boot_time, suspend_id_state);

  ::metrics::structured::events::bluetooth::BluetoothSuspendIdStateChanged()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetSuspendIdState(suspend_id_state)
          .Record();
}

void LogMetricsLLPrivacyState(uint32_t llp_state, uint32_t rpa_state) {
  int64_t ll_privacy_state = 0;
  int64_t addr_privacy_state = 0;
  int64_t boot_time;
  std::string boot_id;

  if (!GetBootId(&boot_id)) {
    return;
  }

  boot_time = bluetooth::common::time_get_os_boottime_us();

  ll_privacy_state = (int64_t)ToLLPrivacyState(llp_state);
  addr_privacy_state = (int64_t)ToAddressPrivacyState(rpa_state);
  log::debug("LLPrivacyState: {}, {}, {}, {}", boot_id, boot_time, ll_privacy_state,
             addr_privacy_state);

  ::metrics::structured::events::bluetooth::BluetoothLLPrivacyState()
          .SetBootId(boot_id)
          .SetSystemTime(boot_time)
          .SetLLPrivacyState(ll_privacy_state)
          .SetAddressPrivacyState(addr_privacy_state)
          .Record();
}

}  // namespace metrics
}  // namespace bluetooth
