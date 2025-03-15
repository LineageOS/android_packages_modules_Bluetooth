/******************************************************************************
 *
 *  Copyright 2014 Google, Inc.
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

#define LOG_TAG "bt_btif_config"

#include "btif_config.h"

#include <bluetooth/log.h>
#include <openssl/rand.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <unordered_map>

#include "btif_keystore.h"
#include "common/address_obfuscator.h"
#include "main/shim/config.h"
#include "main/shim/metric_id_api.h"
#include "main/shim/metrics_api.h"
#include "main/shim/shim.h"
#include "storage/config_keys.h"
#include "types/raw_address.h"

#define TEMPORARY_SECTION_CAPACITY 10000

#define INFO_SECTION "Info"
#define FILE_TIMESTAMP "TimeCreated"
#define FILE_SOURCE "FileSource"
#define TIME_STRING_LENGTH sizeof("YYYY-MM-DD HH:MM:SS")
#define DISABLED "disabled"

using bluetooth::bluetooth_keystore::BluetoothKeystoreInterface;
using bluetooth::common::AddressObfuscator;
using namespace bluetooth;

// Key attestation
static const std::string ENCRYPTED_STR = "encrypted";
static const std::string CONFIG_FILE_PREFIX = "bt_config-origin";
static const std::string CONFIG_FILE_HASH = "hash";
static const std::string encrypt_key_name_list[] = {"LinkKey",     "LE_KEY_PENC",  "LE_KEY_PID",
                                                    "LE_KEY_LID",  "LE_KEY_PCSRK", "LE_KEY_LENC",
                                                    "LE_KEY_LCSRK"};

/**
 * Read metrics salt from config file, if salt is invalid or does not exist,
 * generate new one and save it to config
 */
static void read_or_set_metrics_salt() {
  AddressObfuscator::Octet32 metrics_salt = {};
  size_t metrics_salt_length = metrics_salt.size();
  if (!btif_config_get_bin(BTIF_STORAGE_SECTION_METRICS, BTIF_STORAGE_KEY_METRICS_SALT_256BIT,
                           metrics_salt.data(), &metrics_salt_length)) {
    log::warn("Failed to read metrics salt from config");
    // Invalidate salt
    metrics_salt.fill(0);
  }
  if (metrics_salt_length != metrics_salt.size()) {
    log::error("Metrics salt length incorrect, {} instead of {}", metrics_salt_length,
               metrics_salt.size());
    // Invalidate salt
    metrics_salt.fill(0);
  }
  if (!AddressObfuscator::IsSaltValid(metrics_salt)) {
    log::info("Metrics salt is invalid, creating new one");
    if (RAND_bytes(metrics_salt.data(), metrics_salt.size()) != 1) {
      log::fatal("Failed to generate salt for metrics");
    }
    if (!btif_config_set_bin(BTIF_STORAGE_SECTION_METRICS, BTIF_STORAGE_KEY_METRICS_SALT_256BIT,
                             metrics_salt.data(), metrics_salt.size())) {
      log::fatal("Failed to write metrics salt to config");
    }
  }
  AddressObfuscator::GetInstance()->Initialize(metrics_salt);
}

/**
 * Initialize metric id allocator by reading metric_id from config by mac
 * address. If there is no metric id for a mac address, then allocate it a new
 * metric id.
 */
static void init_metric_id_allocator() {
  std::unordered_map<RawAddress, int> paired_device_map;

  // When user update the system, there will be devices paired with older
  // version of android without a metric id.
  std::vector<RawAddress> addresses_without_id;

  for (const auto& mac_address : btif_config_get_paired_devices()) {
    auto addr_str = mac_address.ToString();
    // if the section name is a mac address
    bool is_valid_id_found = false;
    if (btif_config_exist(addr_str, BTIF_STORAGE_KEY_METRICS_ID_KEY)) {
      // there is one metric id under this mac_address
      int id = 0;
      btif_config_get_int(addr_str, BTIF_STORAGE_KEY_METRICS_ID_KEY, &id);
      if (bluetooth::shim::IsValidIdFromMetricIdAllocator(id)) {
        paired_device_map[mac_address] = id;
        is_valid_id_found = true;
      }
    }
    if (!is_valid_id_found) {
      addresses_without_id.push_back(mac_address);
    }
  }

  // Initialize MetricIdAllocator
  auto save_device_callback = [](const RawAddress& address, const int id) {
    return btif_config_set_int(address.ToString(), BTIF_STORAGE_KEY_METRICS_ID_KEY, id);
  };
  auto forget_device_callback = [](const RawAddress& address, const int /* id */) {
    return btif_config_remove(address.ToString(), BTIF_STORAGE_KEY_METRICS_ID_KEY);
  };
  if (!bluetooth::shim::InitMetricIdAllocator(paired_device_map, std::move(save_device_callback),
                                              std::move(forget_device_callback))) {
    log::fatal("Failed to initialize MetricIdAllocator");
  }

  // Add device_without_id
  for (auto& address : addresses_without_id) {
    bluetooth::shim::AllocateIdFromMetricIdAllocator(address);
    bluetooth::shim::SaveDeviceOnMetricIdAllocator(address);
  }
}

static std::recursive_mutex config_lock;  // protects operations on |config|.

// Module lifecycle functions

static future_t* init(void) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  // TODO (b/158035889) Migrate metrics module to GD
  read_or_set_metrics_salt();
  init_metric_id_allocator();
  return future_new_immediate(FUTURE_SUCCESS);
}

static future_t* shut_down(void) { return future_new_immediate(FUTURE_SUCCESS); }

static future_t* clean_up(void) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  // GD storage module cleanup by itself
  std::unique_lock<std::recursive_mutex> lock(config_lock);
  bluetooth::shim::CloseMetricIdAllocator();
  return future_new_immediate(FUTURE_SUCCESS);
}

EXPORT_SYMBOL module_t btif_config_module = {.name = BTIF_CONFIG_MODULE,
                                             .init = init,
                                             .start_up = NULL,
                                             .shut_down = shut_down,
                                             .clean_up = clean_up};

bool btif_get_device_clockoffset(const RawAddress& bda, int* p_clock_offset) {
  if (p_clock_offset == NULL) {
    return false;
  }

  std::string addrstr = bda.ToString();
  const char* bd_addr_str = addrstr.c_str();

  if (!btif_config_get_int(bd_addr_str, BTIF_STORAGE_KEY_CLOCK_OFFSET, p_clock_offset)) {
    return false;
  }

  log::debug("Device [{}] clock_offset {}", bda, *p_clock_offset);
  return true;
}

bool btif_set_device_clockoffset(const RawAddress& bda, int clock_offset) {
  std::string addrstr = bda.ToString();
  const char* bd_addr_str = addrstr.c_str();

  if (!btif_config_set_int(bd_addr_str, BTIF_STORAGE_KEY_CLOCK_OFFSET, clock_offset)) {
    return false;
  }

  log::debug("Device [{}] clock_offset {}", bda, clock_offset);
  return true;
}

bool btif_config_exist(const std::string& section, const std::string& key) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::HasProperty(section, key);
}

bool btif_config_get_int(const std::string& section, const std::string& key, int* value) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::GetInt(section, key, value);
}

bool btif_config_set_int(const std::string& section, const std::string& key, int value) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::SetInt(section, key, value);
}

bool btif_config_get_uint64(const std::string& section, const std::string& key, uint64_t* value) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::GetUint64(section, key, value);
}

bool btif_config_set_uint64(const std::string& section, const std::string& key, uint64_t value) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::SetUint64(section, key, value);
}

/*******************************************************************************
 *
 * Function         btif_config_get_str
 *
 * Description      Get the string value associated with a particular section
 *                  and key.
 *
 *                  section : The section name (i.e "Adapter")
 *                  key : The key name (i.e "Address")
 *                  value : A pointer to a buffer where we will store the value
 *                  size_bytes : The size of the buffer we have available to
 *                               write the value into. Will be updated upon
 *                               returning to contain the number of bytes
 *                               written.
 *
 * Returns          True if a value was found, False otherwise.
 *
 ******************************************************************************/

bool btif_config_get_str(const std::string& section, const std::string& key, char* value,
                         int* size_bytes) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::GetStr(section, key, value, size_bytes);
}

bool btif_config_set_str(const std::string& section, const std::string& key,
                         const std::string& value) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::SetStr(section, key, value);
}

bool btif_config_get_bin(const std::string& section, const std::string& key, uint8_t* value,
                         size_t* length) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::GetBin(section, key, value, length);
}

size_t btif_config_get_bin_length(const std::string& section, const std::string& key) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::GetBinLength(section, key);
}

bool btif_config_set_bin(const std::string& section, const std::string& key, const uint8_t* value,
                         size_t length) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::SetBin(section, key, value, length);
}

std::vector<RawAddress> btif_config_get_paired_devices() {
  std::vector<std::string> names;
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  names = bluetooth::shim::BtifConfigInterface::GetPersistentDevices();

  std::vector<RawAddress> result;
  result.reserve(names.size());
  for (const auto& name : names) {
    RawAddress addr = {};
    // Gather up known devices from configuration section names
    if (RawAddress::FromString(name, addr)) {
      result.emplace_back(addr);
    }
  }
  return result;
}

bool btif_config_remove(const std::string& section, const std::string& key) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  return bluetooth::shim::BtifConfigInterface::RemoveProperty(section, key);
}

void btif_config_remove_device(const std::string& section) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  bluetooth::shim::BtifConfigInterface::RemoveSection(section);
}

void btif_config_remove_device_with_key(const std::string& key) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  bluetooth::shim::BtifConfigInterface::RemoveSectionWithProperty(key);
}

bool btif_config_clear(void) {
  log::assert_that(bluetooth::shim::is_gd_stack_started_up(),
                   "assert failed: bluetooth::shim::is_gd_stack_started_up()");
  bluetooth::shim::BtifConfigInterface::Clear();
  return true;
}
