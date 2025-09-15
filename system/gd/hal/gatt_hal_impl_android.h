/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "hal/gatt_hal.h"

namespace aidl::android::hardware::bluetooth::gatt {
class IBluetoothGatt;
}

using ::aidl::android::hardware::bluetooth::gatt::IBluetoothGatt;

namespace bluetooth::hal {
class GattAidlCallback;

class GattHalImpl : public GattHal {
public:
  bool IsBound() const { return gatt_hal_instance_ != nullptr; }

  GattHalImpl();
  ~GattHalImpl();

  bool Initialize(hal::GattHalCallback const* callback) override;
  hal::GattCapabilities GetGattCapabilities() const override;
  bool RegisterService(const hal::GattSession& session) const override;
  void UnregisterService(int session_id) const override;
  void ClearServices(int acl_connection_handle) const override;

private:
  std::shared_ptr<IBluetoothGatt> gatt_hal_instance_;
  std::shared_ptr<GattAidlCallback> gatt_aidl_cb_;
  ::ndk::ScopedAIBinder_DeathRecipient death_recipient_;
};

}  // namespace bluetooth::hal
