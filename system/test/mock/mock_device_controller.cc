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

/*
 * Generated mock file from original source file
 *   Functions generated:1
 *
 *  mockcify.pl ver 0.2
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_device_controller.h"

// Original included files, if any
#include "btcore/include/version.h"
#include "device/include/controller.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/hcidefs.h"
#include "types/raw_address.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any
namespace test {
namespace mock {
namespace device_controller {

RawAddress address;
bt_version_t bt_version = {
    .hci_version = 0,
    .hci_revision = 0,
    .lmp_version = 0,
    .manufacturer = 0,
    .lmp_subversion = 0,
};

uint8_t supported_commands[HCI_SUPPORTED_COMMANDS_ARRAY_SIZE]{0};
bt_device_features_t features_classic[MAX_FEATURES_CLASSIC_PAGE_COUNT] = {{
    .as_array{0},
}};
uint8_t last_features_classic_page_index{0};

uint16_t iso_data_size{0};

uint16_t acl_buffer_count_classic{0};
uint8_t acl_buffer_count_ble{0};
uint8_t iso_buffer_count{0};

uint8_t ble_acceptlist_size{0};
uint8_t ble_resolving_list_max_size{0};
uint8_t ble_supported_states[BLE_SUPPORTED_STATES_SIZE]{0};
bt_device_features_t features_ble{0};
uint16_t ble_suggested_default_data_length{0};
uint16_t ble_supported_max_tx_octets{0};
uint16_t ble_supported_max_tx_time{0};
uint16_t ble_supported_max_rx_octets{0};
uint16_t ble_supported_max_rx_time{0};

uint16_t ble_maximum_advertising_data_length{0};
uint8_t ble_number_of_supported_advertising_sets{0};
uint8_t ble_periodic_advertiser_list_size{0};
uint8_t local_supported_codecs[MAX_LOCAL_SUPPORTED_CODECS_SIZE]{0};
uint8_t number_of_local_supported_codecs{0};

bool readable{false};
bool ble_supported{false};
bool iso_supported{false};
bool simple_pairing_supported{false};
bool secure_connections_supported{false};
bool supports_hold_mode{false};
bool supports_sniff_mode{true};
bool supports_park_mode{false};

bool get_is_ready(void) { return readable; }

const RawAddress* get_address(void) { return &address; }

const bt_version_t* get_bt_version(void) { return &bt_version; }

uint8_t* get_local_supported_codecs(uint8_t* number_of_codecs) {
  if (number_of_local_supported_codecs) {
    *number_of_codecs = number_of_local_supported_codecs;
    return local_supported_codecs;
  }
  return NULL;
}

const uint8_t* get_ble_supported_states(void) { return ble_supported_states; }

uint16_t get_iso_data_size(void) { return iso_data_size; }

uint16_t get_iso_packet_size(void) {
  return iso_data_size + HCI_DATA_PREAMBLE_SIZE;
}

uint16_t get_ble_suggested_default_data_length(void) {
  return ble_suggested_default_data_length;
}

uint16_t get_ble_maximum_tx_data_length(void) {
  return ble_supported_max_tx_octets;
}

uint16_t get_ble_maximum_tx_time(void) { return ble_supported_max_tx_time; }

uint16_t get_ble_maximum_advertising_data_length(void) {
  return ble_maximum_advertising_data_length;
}

uint8_t get_ble_number_of_supported_advertising_sets(void) {
  return ble_number_of_supported_advertising_sets;
}

uint8_t get_ble_periodic_advertiser_list_size(void) {
  return ble_periodic_advertiser_list_size;
}

uint16_t get_acl_buffer_count_classic(void) { return acl_buffer_count_classic; }

uint8_t get_acl_buffer_count_ble(void) { return acl_buffer_count_ble; }

uint8_t get_iso_buffer_count(void) { return iso_buffer_count; }

uint8_t get_ble_acceptlist_size(void) { return ble_acceptlist_size; }

uint8_t get_ble_resolving_list_max_size(void) {
  return ble_resolving_list_max_size;
}

void set_ble_resolving_list_max_size(int resolving_list_max_size) {
  ble_resolving_list_max_size = resolving_list_max_size;
}

uint8_t get_le_all_initiating_phys() {
  uint8_t phy = PHY_LE_1M;
  return phy;
}

tBTM_STATUS clear_event_filter() { return BTM_SUCCESS; }

tBTM_STATUS clear_event_mask() { return BTM_SUCCESS; }

tBTM_STATUS le_rand(LeRandCallback /* cb */) { return BTM_SUCCESS; }
tBTM_STATUS set_event_filter_connection_setup_all_devices() {
  return BTM_SUCCESS;
}
tBTM_STATUS set_event_filter_allow_device_connection(
    std::vector<RawAddress> /* devices */) {
  return BTM_SUCCESS;
}
tBTM_STATUS set_default_event_mask_except(uint64_t /* mask */,
                                          uint64_t /* le_mask */) {
  return BTM_SUCCESS;
}
tBTM_STATUS set_event_filter_inquiry_result_all_devices() {
  return BTM_SUCCESS;
}

const controller_t interface = {
    get_is_ready,

    get_address,
    get_bt_version,

    get_ble_supported_states,

    get_iso_data_size,

    get_iso_packet_size,

    get_ble_suggested_default_data_length,
    get_ble_maximum_tx_data_length,
    get_ble_maximum_tx_time,
    get_ble_maximum_advertising_data_length,
    get_ble_number_of_supported_advertising_sets,
    get_ble_periodic_advertiser_list_size,

    get_acl_buffer_count_classic,
    get_acl_buffer_count_ble,
    get_iso_buffer_count,

    get_ble_acceptlist_size,

    get_ble_resolving_list_max_size,
    set_ble_resolving_list_max_size,
    get_local_supported_codecs,
    get_le_all_initiating_phys,
    clear_event_filter,
    clear_event_mask,
    le_rand,
    set_event_filter_connection_setup_all_devices,
    set_event_filter_allow_device_connection,
    set_default_event_mask_except,
    set_event_filter_inquiry_result_all_devices,
};

}  // namespace device_controller
}  // namespace mock
}  // namespace test

// Mocked functions, if any
const controller_t* controller_get_interface() {
  return &test::mock::device_controller::interface;
}

// END mockcify generation
