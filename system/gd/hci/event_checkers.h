/*
 * Copyright 2020 The Android Open Source Project
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

#include <bluetooth/log.h>

#include "hci/hci_packets.h"
#include "os/log.h"

namespace bluetooth {
namespace hci {

template <class T>
void check_complete(CommandCompleteView view) {
  log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
  auto status_view = T::Create(view);
  if (!status_view.IsValid()) {
    log::error("Invalid packet, opcode {}", OpCodeText(view.GetCommandOpCode()));
    return;
  }
  ErrorCode status = status_view.GetStatus();
  OpCode op_code = status_view.GetCommandOpCode();
  if (status != ErrorCode::SUCCESS) {
    log::error("Error code {}, opcode {}", ErrorCodeText(status), OpCodeText(op_code));
    return;
  }
}

template <class T>
void check_status(CommandStatusView view) {
  log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
  auto status_view = T::Create(view);
  if (!status_view.IsValid()) {
    log::error("Invalid packet, opcode {}", OpCodeText(view.GetCommandOpCode()));
    return;
  }
  ErrorCode status = status_view.GetStatus();
  OpCode op_code = status_view.GetCommandOpCode();
  if (status != ErrorCode::SUCCESS) {
    log::error("Error code {}, opcode {}", ErrorCodeText(status), OpCodeText(op_code));
    return;
  }
}

}  // namespace hci
}  // namespace bluetooth
