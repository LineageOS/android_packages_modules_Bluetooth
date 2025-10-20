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

#define CASE_RETURN_STRING(enumerator) \
  case enumerator:                     \
    return std::format(#enumerator "(0x{:x})", static_cast<uint64_t>(enumerator))

#define CASE_RETURN_STRING_HEX04(enumerator) \
  case enumerator:                           \
    return std::format(#enumerator "(0x{:04x})", static_cast<uint64_t>(enumerator))

#define RETURN_UNKNOWN_TYPE_STRING(type, variable) \
  return std::format("Unknown {}(0x{:x})", #type, static_cast<uint64_t>(variable))

// Helper macro to concatenate
#define CAT(A, B) A##B

// Helper macro to select another macro based on the number of arguments
#define SELECT(NAME, NUM) CAT(NAME##_, NUM)

// Helper macro to count the number of arguments (works for 1 or 2 arguments)
#define VA_NARGS_IMPL(_1, _2, N, ...) N
#define VA_NARGS(...) VA_NARGS_IMPL(__VA_ARGS__, 2, 1)

// The main AS_ENUM macro that dispatches to AS_ENUM_1 or AS_ENUM_2
#define AS_ENUM(...) SELECT(AS_ENUM, VA_NARGS(__VA_ARGS__))(__VA_ARGS__)

// AS_ENUM version for single argument X(name)
#define AS_ENUM_1(name) name,

// AS_ENUM version for two arguments X(name, value)
#define AS_ENUM_2(name, value) name = value,

// As string for an enum with any number of arguments
#define AS_STRING(name, ...) \
  case name:                 \
    return #name;
