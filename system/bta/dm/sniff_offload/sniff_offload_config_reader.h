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

#include <cstdint>

#include "sniff_offload_structs.h"

namespace bluetooth {
namespace sniff_offload {

class SniffConfigReader {
public:
  virtual ~SniffConfigReader() = default;

  /* Returns a SniffOffloadConfig for a given profile id, app id and state.
  *  The returned SniffOffloadConfig object has a SniffOffloadParameters and a
  *  priority number, the higher the priority number, the higher is the priority
  *  of this sniff config. The lowest priority is 1. Priority value 0 is kept
  *  reserved for "no priority".
  */
  virtual SniffOffloadConfig ReadSniffConfig(ProfileId profile_id, uint8_t app_id,
                                             ProfileState state) = 0;
};

SniffConfigReader& getSniffConfigReader();

}  // namespace sniff_offload
}  // namespace bluetooth
