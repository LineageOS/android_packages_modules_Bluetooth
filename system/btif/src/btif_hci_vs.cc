/*
 * Copyright 2024 The Android Open Source Project
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

#include "btif_hci_vs.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "btif_common.h"
#include "btif_jni_task.h"
#include "hardware/bt_hci_vs.h"
#include "hci/controller.h"
#include "hci/hci_interface.h"
#include "main/shim/entry.h"
#include "packet/raw_builder.h"
#include "stack/include/main_thread.h"

namespace bluetooth {
namespace hci_vs {

using hci::CommandCompleteView;
using hci::CommandStatusOrCompleteView;
using hci::CommandStatusView;
using hci::OpCode;
using hci::VendorSpecificEventView;

static std::unique_ptr<BluetoothHciVendorSpecificInterface> hciVendorSpecificInterface;

static void CommandStatusOrCompleteCallback(BluetoothHciVendorSpecificCallbacks* callbacks,
                                            Cookie cookie,
                                            CommandStatusOrCompleteView status_or_complete) {
  if (std::holds_alternative<CommandStatusView>(status_or_complete)) {
    auto view = std::get<CommandStatusView>(status_or_complete);
    auto ocf = static_cast<uint16_t>(view.GetCommandOpCode()) & 0x3ff;
    auto status = static_cast<uint8_t>(view.GetStatus());
    do_in_jni_thread(base::BindOnce(&BluetoothHciVendorSpecificCallbacks::onCommandStatus,
                                    base::Unretained(callbacks), ocf, status, cookie));

  } else if (std::holds_alternative<CommandCompleteView>(status_or_complete)) {
    auto view = std::get<CommandCompleteView>(status_or_complete);
    auto ocf = static_cast<uint16_t>(view.GetCommandOpCode()) & 0x3ff;
    std::vector<uint8_t> return_parameters(view.GetPayload().begin(), view.GetPayload().end());
    do_in_jni_thread(base::BindOnce(&BluetoothHciVendorSpecificCallbacks::onCommandComplete,
                                    base::Unretained(callbacks), ocf, return_parameters, cookie));
  }
}

static void EventCallback(BluetoothHciVendorSpecificCallbacks* callbacks,
                          VendorSpecificEventView view) {
  const uint8_t aosp_reserved_codes_range[] = {0x52, 0x60};
  auto code = static_cast<uint8_t>(view.GetSubeventCode());
  if (code >= aosp_reserved_codes_range[0] && code < aosp_reserved_codes_range[1]) {
    return;
  }

  std::vector<uint8_t> data(view.GetPayload().begin(), view.GetPayload().end());
  do_in_jni_thread(base::BindOnce(&BluetoothHciVendorSpecificCallbacks::onEvent,
                                  base::Unretained(callbacks), code, data));
}

class BluetoothHciVendorSpecificInterfaceImpl
    : public bluetooth::hci_vs::BluetoothHciVendorSpecificInterface {
  ~BluetoothHciVendorSpecificInterfaceImpl() override = default;

  void init(BluetoothHciVendorSpecificCallbacks* callbacks) override {
    log::info("BluetoothHciVendorSpecificInterfaceImpl");
    log::assert_that(callbacks != nullptr, "callbacks cannot be null");
    callbacks_ = callbacks;

    shim::GetHciLayer()->RegisterDefaultVendorSpecificEventHandler(
            get_main()->Bind(EventCallback, callbacks_));

    if (com_android_bluetooth_flags_report_vendor_events_from_acl()) {
      auto* controller = shim::GetController();
      auto vendor_capabilities = controller->GetVendorCapabilities();
      if (vendor_capabilities.vendor_connection_handle_min_ > 0) {
        shim::GetHciLayer()->SetVendorAclHandleRange(
                vendor_capabilities.vendor_connection_handle_min_,
                vendor_capabilities.vendor_connection_handle_max_);
        shim::GetHciLayer()->RegisterVendorSpecificAclHandler(get_jni()->Bind(
                &BluetoothHciVendorSpecificCallbacks::onAclEvent, base::Unretained(callbacks_)));
      }
    }
  }

  void sendCommand(uint16_t ocf, std::vector<uint8_t> parameters, Cookie cookie) override {
    if (callbacks_ == nullptr) {
      log::error("not initialized");
      return;
    }

    if (ocf & ~0x3ff) {
      log::error("invalid vendor-specific op-code");
      return;
    }

    const uint16_t ogf_vendor_specific = 0x3f;
    auto op_code = static_cast<OpCode>((ogf_vendor_specific << 10) | ocf);

    shim::GetHciLayer()->EnqueueCommand(
            hci::CommandBuilder::Create(
                    op_code, std::make_unique<packet::RawBuilder>(std::move(parameters))),
            get_main()->BindOnce(CommandStatusOrCompleteCallback, callbacks_, std::move(cookie)));
  }

private:
  BluetoothHciVendorSpecificCallbacks* callbacks_ = nullptr;
};

BluetoothHciVendorSpecificInterface* getBluetoothHciVendorSpecificInterface() {
  if (!hciVendorSpecificInterface) {
    hciVendorSpecificInterface.reset(new BluetoothHciVendorSpecificInterfaceImpl());
  }

  return hciVendorSpecificInterface.get();
}

}  // namespace hci_vs
}  // namespace bluetooth
