/*
 * Copyright (C) 2026 The Android Open Source Project
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
 #include <hardware/bt_vap_server.h>

 #include <memory>
 #include <string>

 #include "bta_vap_server_api.h"
 #include "btif_common.h"
 #include "btif_le_audio.h"
 #include "bluetooth/types/address.h"

using base::BindOnce;
using base::Unretained;
using bluetooth::vap::VapServerCallbacks;
using bluetooth::vap::VapServerInterface;

namespace {
std::unique_ptr<VapServerInterface> vap_server_instance;

class VapServerServiceInterfaceImpl : public VapServerInterface, public VapServerCallbacks {
  ~VapServerServiceInterfaceImpl() override = default;

  void Init(VapServerCallbacks* callbacks) override {
    this->callbacks_ = callbacks;
    bluetooth::vap::GetVapServer()->Initialize(this);
  }

  void SetCcid(int ccid) override { bluetooth::vap::GetVapServer()->SetCcid(ccid); }

  void SetVaName(std::string va_name) override {
    bluetooth::vap::GetVapServer()->SetVaName(va_name);
  }

  void Cleanup(void) override { bluetooth::vap::GetVapServer()->Cleanup(); }

  void OnInitialized() override {
    do_in_jni_thread(BindOnce(&VapServerCallbacks::OnInitialized, Unretained(callbacks_)));
  }

  void OnStartVaSession(const RawAddress& addr) override {
    do_in_jni_thread(
            BindOnce(&VapServerCallbacks::OnStartVaSession, Unretained(callbacks_), addr));
  }

  void OnStopVaSession(const RawAddress& addr) override {
    do_in_jni_thread(BindOnce(&VapServerCallbacks::OnStopVaSession, Unretained(callbacks_), addr));
  }

private:
  VapServerCallbacks* callbacks_;
};

} /* namespace */

 VapServerInterface* btif_vap_server_get_interface(void) {
   if (!vap_server_instance) {
     vap_server_instance.reset(new VapServerServiceInterfaceImpl());
   }

   return vap_server_instance.get();
 }
