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

#include <memory>

#include "include/hardware/bt_csis.h"
#include "rust/cxx.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace topshim {
namespace rust {

class CsisClientIntf {
public:
  CsisClientIntf(csis::CsisClientInterface* intf) : intf_(intf) {}

  void init(/*CsisClientCallbacks* callbacks*/);
  void connect(RawAddress addr);
  void disconnect(RawAddress addr);
  void lock_group(int group_id, bool lock);
  void remove_device(RawAddress addr);
  void cleanup();

private:
  csis::CsisClientInterface* intf_;
};

std::unique_ptr<CsisClientIntf> GetCsisClientProfile(const unsigned char* btif);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
