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

 #include <hardware/bluetooth.h>

 #include <variant>
 #include <vector>

 namespace bluetooth {
 namespace vaps {

 class VapsServerCallbacks {
 public:
   virtual ~VapsServerCallbacks() = default;

   /** Callback for VAPS Server profile initialization */
   virtual void OnInitialized(void) = 0;

   /** Callback for start VA session from remote headset */
   virtual void OnStartVaSession(const RawAddress& addr) = 0;

   /** Callback for stop VA session from remote headset */
   virtual void OnStopVaSession(const RawAddress& addr) = 0;
};

 class VapsServerInterface {
 public:
   virtual ~VapsServerInterface() = default;

   /** Register the VAPS Server profile callbacks */
   virtual void Init(VapsServerCallbacks* callbacks) = 0;

   /** Set the CCID for the VAPS Server profile */
   virtual void SetCcid(int ccid) = 0;

   /** Set VAE name */
   virtual void SetVaeName(std::string vae_name) = 0;

   /** Closes the interface */
   virtual void Cleanup(void) = 0;
 };

 }  // namespace vaps
 }  // namespace bluetooth