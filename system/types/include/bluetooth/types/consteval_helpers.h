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

namespace bluetooth {

/// Replacement for static_assert that can be used in consteval functions.
/// Static assert does not follow the control flow and cannot be used to generate
/// compile time failures.
consteval void consteval_assert(bool cond, [[maybe_unused]] char const* message) {
  if (!cond) {
    std::abort();
  }
}

/// Check if a character is valid hexadecimal.
constexpr bool is_hex_char(char c) {
  return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

/// Convert an alphanumerical character to a nibble.
/// Returns 0 if the character is not alphanumerical.
constexpr uint8_t hex_to_nibble(char c) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  }
  if (c >= 'a' && c <= 'f') {
    return c - 'a' + 10;
  }
  if (c >= 'A' && c <= 'F') {
    return c - 'A' + 10;
  }
  // Unreachable if validation is done properly.
  std::abort();
}

/// Convert two alphanumerical characters to a byte.
constexpr uint8_t hex_to_byte(char hi, char lo) {
  return (hex_to_nibble(hi) << 4) | hex_to_nibble(lo);
}

}  // namespace bluetooth
