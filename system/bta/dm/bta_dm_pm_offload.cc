/*
* Copyright 2025 The Android Open Source Project
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

/******************************************************************************
 *
 *  This file contains the power manager offload.
 *
 ******************************************************************************/

#include "bta/dm/bta_dm_pm_offload.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>

#include <cstdint>
#include <memory>

#include "bluetooth/types/address.h"
#include "bta/dm/bta_dm_int.h"
#include "bta/dm/sniff_offload/sniff_offload.h"
#include "bta/dm/sniff_offload/sniff_offload_config_reader.h"
#include "bta/dm/sniff_offload/sniff_offload_structs.h"
#include "bta/dm/sniff_offload/sniff_offload_vsc_sender.h"
#include "bta/sys/bta_sys.h"
#include "common/circular_buffer.h"
#include "hci/controller.h"
#include "hci/hci_packets.h"
#include "main/shim/dumpsys.h"
#include "main/shim/entry.h"
#include "osi/include/properties.h"
#include "stack/include/btm_client_interface.h"

using namespace bluetooth;
using namespace bluetooth::shim;
using namespace bluetooth::sniff_offload;

namespace {
constexpr char kTimeFormatString[] = "%Y-%m-%d %H:%M:%S";

constexpr unsigned MillisPerSecond = 1000;
std::string EpochMillisToString(uint64_t time_ms) {
  time_t time_sec = time_ms / MillisPerSecond;
  struct tm tm;
  localtime_r(&time_sec, &tm);
  std::string s = bluetooth::common::StringFormatTime(kTimeFormatString, tm);
  return std::format("{}.{:03}", s, time_ms % MillisPerSecond);
}

struct SniffOffloadStartedState {
  bool is_started;
  tHCI_STATUS status_code;
  std::string ToString() const {
    return std::format("is_started:{} status_code:{}", is_started, status_code);
  }
};

SniffOffloadStartedState sniff_offload_started_state_ = {
        .is_started = false,
        .status_code = HCI_SUCCESS,
};

}  // namespace

struct SniffOffloadParametersUpdateEntry {
  tHCI_STATUS status_code;
  uint16_t connection_handle;
  sniff_offload::SniffOffloadParameters parameters;
  std::string ToString() const {
    return std::format("handle: {:#06x}, status_code: {}, parameters: {{ {} }}", connection_handle,
                       status_code, parameters.ToString());
  }
};

static bluetooth::common::TimestampedCircularBuffer<SniffOffloadParametersUpdateEntry>
        sniff_offload_parameter_update_history_(20 /*history size*/);

namespace bluetooth {

namespace {

// BtaDmPmOffload :  An implementation of the SniffOffloadCallbacks that is supplied as a dependency
// to the SniffOffloadImpl by the way of calling its Start(). The BtaDmPmOffload shall also hold an
// owned pointer to the SniffOffloadImpl and is responsible to compose the SniffOffloadImpl instance
// by supplying the SniffOffloadVscSender's and SniffOffloadConfigReader's implementations.
class BtaDmPmOffload : public sniff_offload::SniffOffloadCallbacks,
                       public std::enable_shared_from_this<BtaDmPmOffload> {
public:
  static std::shared_ptr<BtaDmPmOffload> GetInstance(
          std::shared_ptr<sniff_offload::SniffOffload> sniff_offload_instance) {
    static std::shared_ptr<BtaDmPmOffload> instance(new BtaDmPmOffload(sniff_offload_instance));
    return instance;
  }

  void Initialize();

private:
  BtaDmPmOffload(std::shared_ptr<sniff_offload::SniffOffload> sniff_offload_instance);
  static std::shared_ptr<sniff_offload::SniffOffload> sniff_offload_instance_;

  void OnSniffOffloadStarted(tHCI_STATUS reason) override;

  void OnLinkParamsUpdated(uint16_t connection_handle, sniff_offload::SniffOffloadParameters params,
                           tHCI_STATUS reason) override;

  // A static callback function to receive profile status changes from the BTA.
  // It feeds the received profile state changes directly to SniffOffload module by calling
  // the SniffOffload's OnProfileStateChanged() function.
  static void BtaProfileStatusCallback(tBTA_SYS_CONN_STATUS status, const tBTA_SYS_ID id,
                                       uint8_t app_id, const RawAddress& peer_addr) {
    uint16_t acl_handle =
            get_btm_client_interface().peer.BTM_GetHCIConnHandle(peer_addr, BT_TRANSPORT_BR_EDR);
    if (acl_handle != HCI_INVALID_HANDLE) {
      sniff_offload_instance_->OnProfileStateChanged(acl_handle, id, app_id, status);
    } else {
      log::verbose("Not forwarding status update for BTA_SYS_ID = {} over BLE", id);
    }
  }

  static bool IsSniffOffloadSupported() {
    return bluetooth::shim::GetController()->IsSupported(
            bluetooth::hci::OpCode::WRITE_SNIFF_OFFLOAD_ENABLE);
  }
};

}  // namespace

static constexpr uint16_t kSniffOffloadUpdateDelayValueMs = 200;
static constexpr uint16_t kDefaultSubratingMaxLatency = 0;
static constexpr uint16_t kDefaultSubratingMinLocalTimeout = 0;
static constexpr uint16_t kDefaultSubratingMinRemoteTimeout = 0;

static const char kPropertySniffSubratingMaxLatency[] =
        "bluetooth.core.classic.sniff_subrating_max_latency_default";
static const char kPropertySniffSubratingMinLocalTimeout[] =
        "bluetooth.core.classic.sniff_subrating_min_local_timeout_default";
static const char kPropertySniffSubratingMinRemoteTimeout[] =
        "bluetooth.core.classic.sniff_subrating_min_remote_timeout_default";
static const char kPropertySniffOffloadUpdateDelay[] =
        "bluetooth.core.classic.sniff_offload_update_delay";

std::shared_ptr<SniffOffload> BtaDmPmOffload::sniff_offload_instance_;

// Function to initialize the power management offload.
void bta_dm_init_pm_offload() {
  // Compose the Sniff Offload
  uint16_t sniff_offload_update_delay_value =
          osi_property_get_int32(kPropertySniffOffloadUpdateDelay, kSniffOffloadUpdateDelayValueMs);
  std::shared_ptr<sniff_offload::SniffOffload> sniff_offload_instance =
          sniff_offload::GetSniffOffloadInstance(
                  &(sniff_offload::getSniffConfigReader()),
                  &(sniff_offload::GetSniffOffloadVscSender()),
                  std::chrono::milliseconds(sniff_offload_update_delay_value));

  BtaDmPmOffload::GetInstance(sniff_offload_instance)->Initialize();
}

BtaDmPmOffload::BtaDmPmOffload(std::shared_ptr<sniff_offload::SniffOffload> sniff_offload) {
  sniff_offload_instance_ = sniff_offload;
}

// Since sniff is the only actively used power saving mode on a BR/EDR link,
// sniff is the only power mode offloaded here and this function effectively
// is only meant to initialize sniff offload.
void BtaDmPmOffload::Initialize() {
  if (!IsSniffOffloadSupported()) {
    log::error("Sniff Offload not support in BT controller!");
    return;
  }

  // Start Sniff Offload
  uint16_t subrating_max_latency =
          osi_property_get_int32(kPropertySniffSubratingMaxLatency, kDefaultSubratingMaxLatency);
  uint16_t subrating_min_remote_timeout = osi_property_get_int32(
          kPropertySniffSubratingMinRemoteTimeout, kDefaultSubratingMinRemoteTimeout);
  uint16_t subrating_min_local_timeout = osi_property_get_int32(
          kPropertySniffSubratingMinLocalTimeout, kDefaultSubratingMinLocalTimeout);
  sniff_offload_instance_->Start(subrating_max_latency, subrating_min_remote_timeout,
                                 subrating_min_local_timeout, true, true, shared_from_this());
}

void BtaDmPmOffload::OnSniffOffloadStarted(tHCI_STATUS reason) {
  log::info("Sniff Offload Started. Status = {}.", reason);
  if(reason == HCI_SUCCESS) {
    // Register to receive profile status updates from BTA
    bta_sys_pm_register(BtaDmPmOffload::BtaProfileStatusCallback);
    sniff_offload_started_state_ = {.is_started = true, .status_code = HCI_SUCCESS};
  } else {
    log::error("Sniff Offload Start Failed");
    sniff_offload_started_state_ = {.is_started = false, .status_code = reason};
  }
}

void BtaDmPmOffload::OnLinkParamsUpdated(uint16_t connection_handle,
                                          sniff_offload::SniffOffloadParameters params,
                                          tHCI_STATUS reason) {
  log::info("OnLinkParamsUpdated. Handle = {}, Status = {}, Params = {}.",
              connection_handle, reason, params.ToString());
  sniff_offload_parameter_update_history_.Push({
          .status_code = reason,
          .connection_handle = connection_handle,
          .parameters = params,
  });
}

}  // namespace bluetooth

#define DUMPSYS_TAG "shim::legacy::bta::dm::sniff_offload"
void DumpsysBtaDmPmOffload(int fd) {
  auto copy = sniff_offload_parameter_update_history_.Pull();
  LOG_DUMPSYS(fd, " sniff offload started state: %s",
              sniff_offload_started_state_.ToString().c_str());
  LOG_DUMPSYS(fd, " last %zu sniff offload parameters writes", copy.size());

  std::string tag_str = DUMPSYS_TAG;
  for (const auto& it : copy) {
    std::string timestamp_str = EpochMillisToString(it.timestamp);
    size_t indent_length = tag_str.length() + 1 + 3 + timestamp_str.length() + 1;
    std::string indent(indent_length, ' ');
    LOG_DUMPSYS(fd, "   %s %s", timestamp_str.c_str(), it.entry.ToString().c_str());
  }
}
#undef DUMPSYS_TAG
