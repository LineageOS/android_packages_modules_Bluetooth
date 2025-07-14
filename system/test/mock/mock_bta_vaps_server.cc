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

 #include "bta/include/bta_vaps_server_api.h"

 using bluetooth::vaps::VapsServerCallbacks;

 class MockVapsServer : public bluetooth::vaps::VapsServer {
   void Initialize(VapsServerCallbacks* /*callbacks*/) override {}
   void SetCcid(int /*ccid*/) override {}
   void SetVaeName(std::string /*vae_name*/) override {}
   void NotifyVaSessionStarted(std::vector<RawAddress> /*devices*/, bool /*is_success*/) override {}
   void NotifyVaSessionStopped(std::vector<RawAddress> /*devices*/, bool /*is_success*/) override {}
   void DebugDump(int /*fd*/) override {}
   void Cleanup() override {}
 };

 namespace bluetooth {
 namespace vaps {

 VapsServer* GetVapsServer() {
   static MockVapsServer* instance = nullptr;
   if (instance == nullptr) {
     instance = new MockVapsServer();
   }
   return instance;
 }

 }  // namespace vaps
 }  // namespace bluetooth