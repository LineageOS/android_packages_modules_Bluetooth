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

 #include <base/functional/bind.h>
 #include <base/location.h>
 #include <hardware/bt_vaps_server.h>

 #include <cstdint>
 #include <memory>
 #include <string>
 #include <utility>
 #include <variant>
 #include <vector>

 #include "bta_vaps_server_api.h"
 #include "btif_common.h"
 #include "btif_le_audio.h"
 #include "btif_profile_storage.h"
 #include "stack/include/main_thread.h"
 #include "bluetooth/types/address.h"

using base::BindOnce;
using base::Unretained;
using bluetooth::vaps::VapsServerCallbacks;
using bluetooth::vaps::VapsServerInterface;

using bluetooth::vaps::VapsServer;

namespace {
std::unique_ptr<VapsServerInterface> vaps_server_instance;

class VapsServerServiceInterfaceImpl : public VapsServerInterface, public VapsServerCallbacks {
  ~VapsServerServiceInterfaceImpl() override = default;

  void Init(VapsServerCallbacks* callbacks) override {
    this->callbacks_ = callbacks;
    bluetooth::vaps::GetVapsServer()->Initialize(this);
  }

  void SetCcid(int ccid) override { bluetooth::vaps::GetVapsServer()->SetCcid(ccid); }

  void SetVaeName(std::string vae_name) override {
    bluetooth::vaps::GetVapsServer()->SetVaeName(vae_name);
  }

  void Cleanup(void) override { bluetooth::vaps::GetVapsServer()->Cleanup(); }

  void OnInitialized() override {
    do_in_jni_thread(BindOnce(&VapsServerCallbacks::OnInitialized, Unretained(callbacks_)));
  }

  void OnStartVaSession(const RawAddress& addr) override {
    do_in_jni_thread(
            BindOnce(&VapsServerCallbacks::OnStartVaSession, Unretained(callbacks_), addr));
  }

  void OnStopVaSession(const RawAddress& addr) override {
    do_in_jni_thread(BindOnce(&VapsServerCallbacks::OnStopVaSession, Unretained(callbacks_), addr));
  }

private:
  VapsServerCallbacks* callbacks_;
};

} /* namespace */

 VapsServerInterface* btif_vaps_server_get_interface(void) {
   if (!vaps_server_instance) {
     vaps_server_instance.reset(new VapsServerServiceInterfaceImpl());
   }

   return vaps_server_instance.get();
 }
