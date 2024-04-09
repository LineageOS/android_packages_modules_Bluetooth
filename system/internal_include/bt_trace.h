/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <stdint.h>

#ifdef __cplusplus

#include <bluetooth/log.h>

#include <array>
#include <iomanip>
#include <sstream>
#include <type_traits>

#include "os/logging/log_adapter.h"

/* Prints integral parameter x as hex string, with '0' fill */
template <typename T>
std::string loghex(T x) {
  static_assert(std::is_integral<T>::value,
                "loghex parameter must be integral.");
  std::stringstream tmp;
  tmp << std::showbase << std::internal << std::hex << std::setfill('0')
      << std::setw((sizeof(T) * 2) + 2) << +x;
  return tmp.str();
}

/* Prints integral array as hex string, with '0' fill */
template <typename T, size_t N>
std::string loghex(std::array<T, N> array) {
  static_assert(std::is_integral<T>::value,
                "type stored in array must be integral.");
  std::stringstream tmp;
  for (const auto& x : array) {
    tmp << std::internal << std::hex << std::setfill('0')
        << std::setw((sizeof(uint8_t) * 2) + 2) << +x;
  }
  return tmp.str();
}

/**
 * Append a field name to a string.
 *
 * The field names are added to the string with "|" in between.
 *
 * @param p_result a pointer to the result string to add the field name to
 * @param append if true the field name will be added
 * @param name the field name to add
 * @return the result string
 */
inline std::string& AppendField(std::string* p_result, bool append,
                                const std::string& name) {
  bluetooth::log::assert_that(p_result != nullptr,
                              "assert failed: p_result != nullptr");
  if (!append) return *p_result;
  if (!p_result->empty()) *p_result += "|";
  *p_result += name;
  return *p_result;
}

#endif  // __cplusplus
