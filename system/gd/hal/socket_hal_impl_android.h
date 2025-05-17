/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <android/binder_auto_utils.h>

#include "hal/socket_hal.h"

namespace aidl::android::hardware::bluetooth::socket {
class IBluetoothSocket;
}

using ::aidl::android::hardware::bluetooth::socket::IBluetoothSocket;

namespace bluetooth::hal {
class SocketAidlCallback;

class SocketHalImpl : public SocketHal {
public:
  bool IsBound() const { return socket_hal_instance_ != nullptr; }

  SocketHalImpl();
  ~SocketHalImpl();

  hal::SocketCapabilities GetSocketCapabilities() const override;
  bool RegisterCallback(hal::SocketHalCallback const* callback) override;
  bool Opened(const hal::SocketContext& context) const override;
  void Closed(uint64_t socket_id) const override;

private:
  std::shared_ptr<IBluetoothSocket> socket_hal_instance_;
  std::shared_ptr<SocketAidlCallback> socket_aidl_cb_;
  ::ndk::ScopedAIBinder_DeathRecipient death_recipient_;
};

}  // namespace bluetooth::hal
