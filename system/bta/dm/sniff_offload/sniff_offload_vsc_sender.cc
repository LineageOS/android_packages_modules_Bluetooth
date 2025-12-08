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

#include "sniff_offload_vsc_sender.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <cstdint>

#include "hci/event_checkers.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"
#include "main/shim/entry.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/main_thread.h"
#include "bluetooth/types/address.h"

namespace bluetooth {
namespace sniff_offload {

class SniffOffloadVscSenderImpl : public SniffOffloadVscSender {

public:
  void WriteSniffOffloadEnable(uint16_t subrate_max_latency, uint16_t subrate_min_remote_timeout,
                               uint16_t subrate_min_local_timeout, bool suppress_mode_change_event,
                               bool suppress_subrating_event,
                               sniff_offload::WriteSniffOffloadEnableCompleteCallback callback) {
    shim::GetHciLayer()->EnqueueCommand(
            hci::WriteSniffOffloadEnableBuilder::Create(
                    true, subrate_max_latency, subrate_min_local_timeout,
                    subrate_min_remote_timeout, suppress_mode_change_event,
                    suppress_subrating_event),
            get_main_thread()->BindOnce(
                    SniffOffloadVscSenderImpl::OnWriteSniffOffloadEnableComplete,
                    std::move(callback)));
  }

  void WriteSniffOffloadParameters(
          uint16_t acl_handle, sniff_offload::SniffOffloadParameters params,
          sniff_offload::WriteSniffOffloadParametersCompleteCallback callback) {
    shim::GetHciLayer()->EnqueueCommand(
            hci::WriteSniffOffloadParametersBuilder::Create(
                    acl_handle, params.sniff_max_interval, params.sniff_min_interval,
                    params.sniff_attempts, params.sniff_timeout, params.link_idle_timeout,
                    params.subrate_max_latency, params.min_local_timeout, params.min_remote_timeout,
                    params.allow_exit_on_rx, params.allow_exit_on_tx),
            get_main_thread()->BindOnce(
                    SniffOffloadVscSenderImpl::OnWriteSniffOffloadParametersComplete,
                    std::move(callback), acl_handle));
  }

private:
  static void OnWriteSniffOffloadEnableComplete(WriteSniffOffloadEnableCompleteCallback callback,
                                                hci::CommandCompleteView complete) {
    check_complete<hci::WriteSniffOffloadEnableCompleteView>(complete);
    auto view = hci::WriteSniffOffloadEnableCompleteView::Create(complete);
    log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
    callback(static_cast<tHCI_STATUS>(view.GetStatus()));
  }

  static void OnWriteSniffOffloadParametersComplete(
          WriteSniffOffloadParametersCompleteCallback callback, uint16_t acl_handle,
          hci::CommandCompleteView complete) {
    check_complete<hci::WriteSniffOffloadParametersCompleteView>(complete);
    auto view = hci::WriteSniffOffloadParametersCompleteView::Create(complete);
    log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
    callback(acl_handle, static_cast<tHCI_STATUS>(view.GetStatus()));
  }
};

SniffOffloadVscSender& GetSniffOffloadVscSender() {
  static SniffOffloadVscSenderImpl instance;
  return instance;
}

} // namespace sniff_offload
} // namespace bluetooth
