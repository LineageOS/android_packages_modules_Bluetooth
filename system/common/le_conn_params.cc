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

#define LOG_TAG "le_conn_params"

#include "common/le_conn_params.h"

#include <android_bluetooth_sysprop.h>
#include <bluetooth/log.h>

#include <cstdint>

#include "os/system_properties.h"
#include "stack/include/btm_ble_api_types.h"

using namespace bluetooth;

const std::string LeConnectionParameters::kPropertyAggressiveConnThreshold =
        "bluetooth.core.le.aggressive_connection_threshold";
const std::string LeConnectionParameters::kPropertyMinConnIntervalAggressive =
        "bluetooth.core.le.min_connection_interval_aggressive";
const std::string LeConnectionParameters::kPropertyMaxConnIntervalAggressive =
        "bluetooth.core.le.max_connection_interval_aggressive";
const std::string LeConnectionParameters::kPropertyMinConnIntervalRelaxed =
        "bluetooth.core.le.min_connection_interval_relaxed";
const std::string LeConnectionParameters::kPropertyMaxConnIntervalRelaxed =
        "bluetooth.core.le.max_connection_interval_relaxed";

bool LeConnectionParameters::initialized = false;
uint32_t LeConnectionParameters::aggressive_conn_threshold = kAggressiveConnThreshold;
uint32_t LeConnectionParameters::min_conn_interval_aggressive = kMinConnIntervalAggressive;
uint32_t LeConnectionParameters::max_conn_interval_aggressive = kMaxConnIntervalAggressive;
uint32_t LeConnectionParameters::min_conn_interval_relaxed = kMinConnIntervalRelaxed;
uint32_t LeConnectionParameters::max_conn_interval_relaxed = kMaxConnIntervalRelaxed;
uint32_t LeConnectionParameters::iso_aggressive_conn_threshold = kLeIsoAggressiveConnThreshold;
uint32_t LeConnectionParameters::min_conn_interval_iso_aggressive = kMinConnIntervalLeIsoAggressive;
uint32_t LeConnectionParameters::max_conn_interval_iso_aggressive = kMaxConnIntervalLeIsoAggressive;

void LeConnectionParameters::InitConnParamsWithSystemProperties() {
  aggressive_conn_threshold =
          os::GetSystemPropertyUint32(kPropertyAggressiveConnThreshold, kAggressiveConnThreshold);
  min_conn_interval_aggressive = os::GetSystemPropertyUint32(kPropertyMinConnIntervalAggressive,
                                                             kMinConnIntervalAggressive);
  max_conn_interval_aggressive = os::GetSystemPropertyUint32(kPropertyMaxConnIntervalAggressive,
                                                             kMaxConnIntervalAggressive);
  min_conn_interval_relaxed =
          os::GetSystemPropertyUint32(kPropertyMinConnIntervalRelaxed, kMinConnIntervalRelaxed);
  max_conn_interval_relaxed =
          os::GetSystemPropertyUint32(kPropertyMaxConnIntervalRelaxed, kMaxConnIntervalRelaxed);

  iso_aggressive_conn_threshold =
          android::sysprop::bluetooth::Ble::iso_aggressive_connection_threshold().value_or(
                  kLeIsoAggressiveConnThreshold);
  min_conn_interval_iso_aggressive =
          android::sysprop::bluetooth::Ble::iso_min_connection_interval().value_or(
                  kMinConnIntervalLeIsoAggressive);
  max_conn_interval_iso_aggressive =
          android::sysprop::bluetooth::Ble::iso_max_connection_interval().value_or(
                  kMaxConnIntervalLeIsoAggressive);

  log::debug(
          "Before checking validity: threshold={}, aggressive={}/{}, relaxed={}/{}, "
          "le_iso_threshold={}, le_iso_aggressive={}/{}",
          aggressive_conn_threshold, min_conn_interval_aggressive, max_conn_interval_aggressive,
          min_conn_interval_relaxed, max_conn_interval_relaxed, iso_aggressive_conn_threshold,
          min_conn_interval_iso_aggressive, max_conn_interval_iso_aggressive);

  // Check validity of each values
  if (aggressive_conn_threshold < 0) {
    log::warn("Invalid aggressive connection threshold. Using default value.",
              aggressive_conn_threshold);
    aggressive_conn_threshold = kAggressiveConnThreshold;
  }

  // Check validity of each values
  if (iso_aggressive_conn_threshold <= 0) {
    log::warn("Invalid ISO aggressive connection threshold. Using default value.",
              iso_aggressive_conn_threshold);
    iso_aggressive_conn_threshold = kLeIsoAggressiveConnThreshold;
  }

  if (min_conn_interval_aggressive < BTM_BLE_CONN_INT_MIN ||
      min_conn_interval_aggressive > BTM_BLE_CONN_INT_MAX ||
      max_conn_interval_aggressive < BTM_BLE_CONN_INT_MIN ||
      max_conn_interval_aggressive > BTM_BLE_CONN_INT_MAX ||
      max_conn_interval_aggressive < min_conn_interval_aggressive) {
    log::warn("Invalid aggressive connection intervals. Using default values.");
    min_conn_interval_aggressive = kMinConnIntervalAggressive;
    max_conn_interval_aggressive = kMaxConnIntervalAggressive;
  }

  if (min_conn_interval_relaxed < BTM_BLE_CONN_INT_MIN ||
      min_conn_interval_relaxed > BTM_BLE_CONN_INT_MAX ||
      max_conn_interval_relaxed < BTM_BLE_CONN_INT_MIN ||
      max_conn_interval_relaxed > BTM_BLE_CONN_INT_MAX ||
      max_conn_interval_relaxed < min_conn_interval_relaxed) {
    log::warn("Invalid relaxed connection intervals. Using default values.");
    min_conn_interval_relaxed = kMinConnIntervalRelaxed;
    max_conn_interval_relaxed = kMaxConnIntervalRelaxed;
  }

  if (min_conn_interval_iso_aggressive < BTM_BLE_CONN_INT_MIN ||
      min_conn_interval_iso_aggressive > BTM_BLE_CONN_INT_MAX ||
      max_conn_interval_iso_aggressive < BTM_BLE_CONN_INT_MIN ||
      max_conn_interval_iso_aggressive > BTM_BLE_CONN_INT_MAX ||
      max_conn_interval_iso_aggressive < min_conn_interval_iso_aggressive) {
    log::warn("Invalid ISO aggressive connection intervals. Using default values.");
    min_conn_interval_iso_aggressive = kMinConnIntervalLeIsoAggressive;
    max_conn_interval_iso_aggressive = kMaxConnIntervalLeIsoAggressive;
  }

  if ((min_conn_interval_aggressive > min_conn_interval_relaxed) &&
      (max_conn_interval_aggressive > max_conn_interval_relaxed)) {
    log::warn(
            "Relaxed connection intervals are more aggressive than aggressive ones."
            " Setting all intervals to default values.");
    min_conn_interval_aggressive = kMinConnIntervalAggressive;
    max_conn_interval_aggressive = kMaxConnIntervalAggressive;
    min_conn_interval_relaxed = kMinConnIntervalRelaxed;
    max_conn_interval_relaxed = kMaxConnIntervalRelaxed;
  }

  log::debug(
          "After checking validity: threshold={}, aggressive={}/{}, relaxed={}/{}, "
          "le_iso_threshold={}, le_iso_aggressive={}/{}",
          aggressive_conn_threshold, min_conn_interval_aggressive, max_conn_interval_aggressive,
          min_conn_interval_relaxed, max_conn_interval_relaxed, iso_aggressive_conn_threshold,
          min_conn_interval_iso_aggressive, max_conn_interval_iso_aggressive);

  initialized = true;
}

uint32_t LeConnectionParameters::GetAggressiveConnThreshold() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return aggressive_conn_threshold;
}

uint32_t LeConnectionParameters::GetMinConnIntervalAggressive() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return min_conn_interval_aggressive;
}

uint32_t LeConnectionParameters::GetMaxConnIntervalAggressive() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return max_conn_interval_aggressive;
}

uint32_t LeConnectionParameters::GetMinConnIntervalRelaxed() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return min_conn_interval_relaxed;
}

uint32_t LeConnectionParameters::GetMaxConnIntervalRelaxed() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return max_conn_interval_relaxed;
}

uint32_t LeConnectionParameters::GetLeIsoAggressiveConnThreshold() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return iso_aggressive_conn_threshold;
}

uint32_t LeConnectionParameters::GetMinConnIntervalLeIsoAggressive() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return min_conn_interval_iso_aggressive;
}

uint32_t LeConnectionParameters::GetMaxConnIntervalLeIsoAggressive() {
  if (!initialized) {
    InitConnParamsWithSystemProperties();
  }
  return max_conn_interval_iso_aggressive;
}
