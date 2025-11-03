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
#pragma once

#include "hal/hci_hal.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"
#include "hci/le_scanning_callback.h"

struct MsftAdvMonitor;

namespace bluetooth {
namespace hci {

class MsftExtensionManager {
public:
  MsftExtensionManager(os::Handler* handler, hal::HciHal* hal, hci::HciInterface* hci_layer);
  MsftExtensionManager(const MsftExtensionManager&) = delete;
  MsftExtensionManager& operator=(const MsftExtensionManager&) = delete;
  virtual ~MsftExtensionManager();

  using MsftAdvMonitorAddCallback =
          base::OnceCallback<void(uint8_t /* monitor_handle */, ErrorCode /* status */)>;
  using MsftAdvMonitorRemoveCallback = base::OnceCallback<void(ErrorCode /* status */)>;
  using MsftAdvMonitorEnableCallback = base::OnceCallback<void(ErrorCode /* status */)>;

  virtual bool SupportsMsftExtensions();
  void MsftAdvMonitorAdd(const MsftAdvMonitor& monitor, MsftAdvMonitorAddCallback cb);
  void MsftAdvMonitorRemove(uint8_t monitor_handle, MsftAdvMonitorRemoveCallback cb);
  void MsftAdvMonitorEnable(bool enable, MsftAdvMonitorEnableCallback cb);
  void SetScanningCallback(ScanningCallback* callbacks);

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace hci
}  // namespace bluetooth
