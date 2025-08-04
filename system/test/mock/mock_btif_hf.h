/*
 * Copyright 2024 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:0
 *
 *  mockcify.pl ver 0.7.0
 */

#include <bluetooth/types/address.h>

#include <functional>

#include "include/hardware/bluetooth_headset_interface.h"

// Original included files, if any
// NOTE: Since this is a mock file with mock definitions some number of
//       include files may not be required.  The include-what-you-use
//       still applies, but crafting proper inclusion is out of scope
//       for this effort.  This compilation unit may compile as-is, or
//       may need attention to prune from (or add to ) the inclusion set.

// Original usings

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace btif_hf {

// Shared state between mocked functions and tests
// Name: GetInterface
// Params:
// Returns: bluetooth::headset::Interface*

struct GetInterface {
  std::function<bluetooth::headset::Interface*()> body{[]() { return nullptr; }};
  bluetooth::headset::Interface* operator()() { return body(); }
};
extern struct GetInterface GetInterface;

struct IsCallIdle {
  std::function<bool()> body{[]() { return false; }};
  bool operator()() { return body(); }
};
extern struct IsCallIdle IsCallIdle;

struct IsDuringVoiceRecognition {
  std::function<bool(RawAddress bd_addr)> body{
          [](RawAddress bd_addr) { return !bd_addr.IsEmpty(); }};
  bool operator()(RawAddress bd_addr) { return body(bd_addr); }
};
extern struct IsDuringVoiceRecognition IsDuringVoiceRecognition;

// Shared state between mocked functions and tests
}  // namespace btif_hf
}  // namespace mock
}  // namespace test

// END mockcify generation
