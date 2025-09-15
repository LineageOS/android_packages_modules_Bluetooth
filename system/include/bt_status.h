//
//  Copyright 2025 Google, Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

#pragma once

#include <bluetooth/log.h>

#include <cassert>
#include <iostream>
#include <string>

#ifndef TARGET_FLOSS
// Exclude from the Floss build to avoid introducing unnecessary JNI code.
#include <jni.h>
#endif

#include "bt_status_origin.h"

#define BT_UNIVERSAL_SUCCESS 0
typedef uint16_t BtStatusCode;

// The base class for all Bluetooth status codes.
class BtStatus {
public:
  BtStatus() = delete;
  BtStatus(const BtStatus& other)
      : code_(other.code_), origin_(other.origin_), toString_(other.toString_) {}
  BtStatus& operator=(const BtStatus& other) {
    if (this != &other) {
      code_ = other.code_;
      origin_ = other.origin_;
      toString_ = other.toString_;
    }
    return *this;
  }

  friend std::ostream& operator<<(std::ostream& os, const BtStatus& status);

  BtStatusCode code() { return code_; }
  BtStatusOrigin origin() { return origin_; }

  bool isSuccess() const { return code_ == BT_UNIVERSAL_SUCCESS; }

  // To easily pass around between stacks and compare
  uint32_t toUint32() const {
    if (code_ == BT_UNIVERSAL_SUCCESS) {
      return BT_UNIVERSAL_SUCCESS;
    }
    return static_cast<uint16_t>(origin_) << 16 | static_cast<uint16_t>(code_);
  }
  operator uint32_t() const { return toUint32(); }

// Exclude from the Floss build to avoid introducing unnecessary JNI code.
#ifndef TARGET_FLOSS
  // For now, BtStatus objects are for native stack use only. As a result, when
  // they are being converted to jints to be passed to the upper Java layer, only
  // pass the internal code to preserve functionality.
  operator jint() const { return (jint)static_cast<uint32_t>(code_); }
#endif

  // To compare against other statuses
  bool operator==(const BtStatus& other) const {
    return origin_ == other.origin_ && code_ == other.code_;
  }

  // Used to cast to bool, true if is success, false otherwise.
  operator bool() const { return code_ == BT_UNIVERSAL_SUCCESS; }

  // To allow use as map keys
  bool operator<(const BtStatus& other) const { return toUint32() < other.toUint32(); }

  // Used for logging
  const std::string toString() const {
    if (code_ == BT_UNIVERSAL_SUCCESS) {
      // If successful, return generic success string
      return "BT_SUCCESS";
    }

    if (toString_ != nullptr) {
      // If custom toString_ function was provided, use it
      return toString_(code_);
    }

    // Return some tolerable string value
    return toStringBtStatusOrigin(origin_) + "_" + std::to_string(code_);
  }

protected:
  BtStatusCode code_;
  BtStatusOrigin origin_;
  const std::string (*toString_)(BtStatusCode);

  BtStatus(BtStatusCode c, BtStatusOrigin o, const std::string (*s)(BtStatusCode))
      : code_(c), origin_(o), toString_(s) {}
};

// Define the overloaded operator<<
inline std::ostream& operator<<(std::ostream& os, const BtStatus& status) {
  os << status.toString();
  return os;
}

// To allow use as map keys
template <>
struct std::hash<BtStatus> {
  size_t operator()(const BtStatus& status) const { return status.toUint32(); }
};

// All std::formatter specializations must be inside the std namespace
namespace std {

// Concept to identify any class that inherits from BtStatus
template <typename T>
concept IsBtStatusDerived = std::derived_from<T, BtStatus>;

// The primary formatter specialization for the base class, BtStatus.
// This formatter will handle the core logic.
template <>
struct formatter<BtStatus> : formatter<string_view> {
  template <typename FormatContext>
  auto format(const BtStatus& status, FormatContext& ctx) const {
    return formatter<string_view>::format(status.toString(), ctx);
  }
};

// A constrained partial specialization for any class T that derives from BtStatus.
// This formatter simply inherits from the base class formatter, reusing its logic.
template <IsBtStatusDerived T>
struct formatter<T> : formatter<BtStatus> {};

}  // namespace std
