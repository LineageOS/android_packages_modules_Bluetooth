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

 #pragma once

 #include <cstdint>
 #include <vector>

 #include "bluetooth/types/uuid.h"
 #include "bluetooth/types/address.h"

 #include "hardware/bt_vaps_server.h"

 namespace bluetooth {
 namespace vaps {

 class VapsServer {
 public:
   virtual ~VapsServer() = default;
   virtual void Initialize(VapsServerCallbacks* callbacks) = 0;
   virtual void SetCcid(int ccid) = 0;
   virtual void SetVaeName(std::string vae_name) = 0;
   virtual void NotifyVaSessionStarted(std::vector<RawAddress> devices, bool is_success) = 0;
   virtual void NotifyVaSessionStopped(std::vector<RawAddress> devices, bool is_success) = 0;
   virtual void DebugDump(int fd) = 0;
   virtual void Cleanup() = 0;
 };

 VapsServer* GetVapsServer();
 }  // namespace vaps
 }  // namespace bluetooth
