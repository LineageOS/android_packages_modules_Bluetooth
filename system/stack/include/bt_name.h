/*
 * Copyright 2021 The Android Open Source Project
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
#include <cstring>

#include "osi/include/compat.h"  // strlcpy

#define BD_NAME_LEN 248
typedef uint8_t BD_NAME[BD_NAME_LEN + 1]; /* Device name */

inline constexpr BD_NAME kBtmBdNameEmpty = {};
constexpr size_t kBdNameLength = static_cast<size_t>(BD_NAME_LEN);
constexpr uint8_t kBdNameDelim = (uint8_t)NULL;

inline size_t bd_name_copy(BD_NAME bd_name_dest, const BD_NAME bd_name_src) {
  return strlcpy(reinterpret_cast<char*>(bd_name_dest),
                 reinterpret_cast<const char*>(bd_name_src), kBdNameLength + 1);
}
inline void bd_name_clear(BD_NAME bd_name) { *bd_name = {0}; }
inline bool bd_name_is_empty(const BD_NAME bd_name) {
  return bd_name[0] == '\0';
}

inline void bd_name_from_char_pointer(BD_NAME bd_name_dest,
                                      const char* bd_name_char) {
  if (bd_name_char == nullptr) {
    bd_name_clear(bd_name_dest);
    return;
  }

  size_t src_len = strlcpy(reinterpret_cast<char*>(bd_name_dest), bd_name_char,
                           sizeof(BD_NAME));
  if (src_len < sizeof(BD_NAME) - 1) {
    /* Zero the remaining destination memory */
    memset(bd_name_dest + src_len, 0, sizeof(BD_NAME) - src_len);
  }
}
inline bool bd_name_is_equal(const BD_NAME bd_name1, const BD_NAME bd_name2) {
  return memcmp(reinterpret_cast<void*>(const_cast<uint8_t*>(bd_name1)),
                reinterpret_cast<void*>(const_cast<uint8_t*>(bd_name2)),
                kBdNameLength + 1) == 0;
}
