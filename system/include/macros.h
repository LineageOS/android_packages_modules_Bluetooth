/*
 * Copyright 2023 The Android Open Source Project
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

#include <bluetooth/log.h>

#include <cstdint>
#include <string>

#define CASE_RETURN_TEXT(code) \
  case code:                   \
    return #code

#define CASE_RETURN_STRING(enumerator)         \
  case enumerator:                             \
    return fmt::format(#enumerator "(0x{:x})", \
                       static_cast<uint64_t>(enumerator))

#define CASE_RETURN_STRING_HEX04(enumerator)     \
  case enumerator:                               \
    return fmt::format(#enumerator "(0x{:04x})", \
                       static_cast<uint64_t>(enumerator))

#define RETURN_UNKNOWN_TYPE_STRING(type, variable) \
  return fmt::format("Unknown {}(0x{:x})", #type,  \
                     static_cast<uint64_t>(variable))
