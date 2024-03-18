/*
 * Copyright 2019 The Android Open Source Project
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

#define LOG_TAG "bt_shim_controller"

#include "main/shim/controller.h"

#include "btcore/include/module.h"
#include "hci/controller.h"
#include "hci/controller_interface.h"
#include "include/check.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "main/shim/shim.h"
#include "osi/include/future.h"
#include "stack/include/btm_status.h"
#include "types/raw_address.h"

using ::bluetooth::shim::GetController;

constexpr int kMaxSupportedCodecs = 8;  // MAX_LOCAL_SUPPORTED_CODECS_SIZE

constexpr uint8_t kPhyLe1M = 0x01;

constexpr int kHciDataPreambleSize = 4;  // #define HCI_DATA_PREAMBLE_SIZE 4

// Module lifecycle functions
static future_t* start_up(void);
static future_t* shut_down(void);

EXPORT_SYMBOL extern const module_t gd_controller_module = {
    .name = GD_CONTROLLER_MODULE,
    .init = nullptr,
    .start_up = start_up,
    .shut_down = shut_down,
    .clean_up = nullptr,
    .dependencies = {GD_SHIM_MODULE, nullptr}};

struct {
  bool ready;
  RawAddress raw_address;
  bt_version_t bt_version;
  uint8_t local_supported_codecs[kMaxSupportedCodecs];
  uint8_t number_of_local_supported_codecs;
  uint64_t le_supported_states;
  uint8_t phy;
} data_;

static future_t* start_up(void) {
  LOG_INFO("%s Starting up", __func__);
  data_.ready = true;

  std::string string_address = GetController()->GetMacAddress().ToString();
  RawAddress::FromString(string_address, data_.raw_address);

  data_.le_supported_states =
      bluetooth::shim::GetController()->GetLeSupportedStates();

  auto local_version_info =
      bluetooth::shim::GetController()->GetLocalVersionInformation();
  data_.bt_version.hci_version =
      static_cast<uint8_t>(local_version_info.hci_version_);
  data_.bt_version.hci_revision = local_version_info.hci_revision_;
  data_.bt_version.lmp_version =
      static_cast<uint8_t>(local_version_info.lmp_version_);
  data_.bt_version.lmp_subversion = local_version_info.lmp_subversion_;
  data_.bt_version.manufacturer = local_version_info.manufacturer_name_;

  LOG_INFO("Mac address:%s", ADDRESS_TO_LOGGABLE_CSTR(data_.raw_address));

  data_.phy = kPhyLe1M;

  return future_new_immediate(FUTURE_SUCCESS);
}

static future_t* shut_down(void) {
  data_.ready = false;
  return future_new_immediate(FUTURE_SUCCESS);
}

/**
 * Module methods
 */

static bool get_is_ready(void) { return data_.ready; }

static const RawAddress* get_address(void) { return &data_.raw_address; }

static const bt_version_t* get_bt_version(void) { return &data_.bt_version; }

static uint8_t* get_local_supported_codecs(uint8_t* number_of_codecs) {
  CHECK(number_of_codecs != nullptr);
  if (data_.number_of_local_supported_codecs != 0) {
    *number_of_codecs = data_.number_of_local_supported_codecs;
    return data_.local_supported_codecs;
  }
  return (uint8_t*)nullptr;
}

static const uint8_t* get_ble_supported_states(void) {
  return (const uint8_t*)&data_.le_supported_states;
}

#define FORWARD_GETTER(type, legacy, gd) \
  static type legacy(void) { return gd; }

FORWARD_GETTER(
    uint16_t, get_iso_buffer_length,
    GetController()->GetControllerIsoBufferSize().le_data_packet_length_)

static uint16_t get_iso_packet_size(void) {
  return get_iso_buffer_length() + kHciDataPreambleSize;
}

FORWARD_GETTER(uint16_t, get_le_suggested_default_data_length,
               GetController()->GetLeSuggestedDefaultDataLength())

static uint16_t get_le_maximum_tx_data_length(void) {
  ::bluetooth::hci::LeMaximumDataLength le_maximum_data_length =
      GetController()->GetLeMaximumDataLength();
  return le_maximum_data_length.supported_max_tx_octets_;
}

static uint16_t get_le_maximum_tx_time(void) {
  ::bluetooth::hci::LeMaximumDataLength le_maximum_data_length =
      GetController()->GetLeMaximumDataLength();
  return le_maximum_data_length.supported_max_tx_time_;
}

FORWARD_GETTER(uint16_t, get_le_max_advertising_data_length,
               GetController()->GetLeMaximumAdvertisingDataLength())
FORWARD_GETTER(uint8_t, get_le_supported_advertising_sets,
               GetController()->GetLeNumberOfSupportedAdverisingSets())
FORWARD_GETTER(uint8_t, get_le_periodic_advertiser_list_size,
               GetController()->GetLePeriodicAdvertiserListSize())
FORWARD_GETTER(uint16_t, get_acl_buffers,
               GetController()->GetNumAclPacketBuffers())
FORWARD_GETTER(uint8_t, get_le_buffers,
               GetController()->GetLeBufferSize().total_num_le_packets_)
FORWARD_GETTER(
    uint8_t, get_iso_buffers,
    GetController()->GetControllerIsoBufferSize().total_num_le_packets_)
FORWARD_GETTER(uint8_t, get_le_accept_list_size,
               GetController()->GetLeFilterAcceptListSize())

static void set_ble_resolving_list_max_size(int /* resolving_list_max_size */) {
  LOG_DEBUG("UNSUPPORTED");
}

static uint8_t get_le_resolving_list_size(void) {
  return bluetooth::shim::GetController()->GetLeResolvingListSize();
}

static uint8_t get_le_all_initiating_phys() { return data_.phy; }

static uint8_t controller_clear_event_filter() {
  LOG_VERBOSE("Called!");
  bluetooth::shim::GetController()->SetEventFilterClearAll();
  return BTM_SUCCESS;
}

static uint8_t controller_clear_event_mask() {
  LOG_VERBOSE("Called!");
  bluetooth::shim::GetController()->SetEventMask(0);
  bluetooth::shim::GetController()->LeSetEventMask(0);
  return BTM_SUCCESS;
}

static uint8_t controller_le_rand(LeRandCallback cb) {
  LOG_VERBOSE("Called!");
  bluetooth::shim::GetController()->LeRand(std::move(cb));
  return BTM_SUCCESS;
}

static uint8_t controller_set_event_filter_connection_setup_all_devices() {
  bluetooth::shim::GetController()->SetEventFilterConnectionSetupAllDevices(
      bluetooth::hci::AutoAcceptFlag::AUTO_ACCEPT_ON_ROLE_SWITCH_ENABLED);
  return BTM_SUCCESS;
}

static uint8_t controller_set_event_filter_allow_device_connection(
    std::vector<RawAddress> devices) {
  for (const RawAddress& address : devices) {
    bluetooth::shim::GetController()->SetEventFilterConnectionSetupAddress(
        bluetooth::ToGdAddress(address),
        bluetooth::hci::AutoAcceptFlag::AUTO_ACCEPT_OFF);
  }
  return BTM_SUCCESS;
}

static uint8_t controller_set_default_event_mask_except(uint64_t mask,
                                                        uint64_t le_mask) {
  uint64_t applied_mask =
      bluetooth::hci::Controller::kDefaultEventMask & ~(mask);
  uint64_t applied_le_mask =
      bluetooth::hci::Controller::kDefaultLeEventMask & ~(le_mask);

  bluetooth::shim::GetController()->SetEventMask(applied_mask);
  bluetooth::shim::GetController()->LeSetEventMask(applied_le_mask);
  return BTM_SUCCESS;
}

static uint8_t controller_set_event_filter_inquiry_result_all_devices() {
  bluetooth::shim::GetController()->SetEventFilterInquiryResultAllDevices();
  return BTM_SUCCESS;
}

static const controller_t interface = {
    .get_is_ready = get_is_ready,

    .get_address = get_address,
    .get_bt_version = get_bt_version,

    .get_ble_supported_states = get_ble_supported_states,

    .get_iso_data_size = get_iso_buffer_length,

    .get_iso_packet_size = get_iso_packet_size,

    .get_ble_default_data_packet_length = get_le_suggested_default_data_length,
    .get_ble_maximum_tx_data_length = get_le_maximum_tx_data_length,
    .get_ble_maximum_tx_time = get_le_maximum_tx_time,
    .get_ble_maximum_advertising_data_length =
        get_le_max_advertising_data_length,
    .get_ble_number_of_supported_advertising_sets =
        get_le_supported_advertising_sets,
    .get_ble_periodic_advertiser_list_size =
        get_le_periodic_advertiser_list_size,

    .get_acl_buffer_count_classic = get_acl_buffers,
    .get_acl_buffer_count_ble = get_le_buffers,
    .get_iso_buffer_count = get_iso_buffers,

    .get_ble_acceptlist_size = get_le_accept_list_size,

    .get_ble_resolving_list_max_size = get_le_resolving_list_size,
    .set_ble_resolving_list_max_size = set_ble_resolving_list_max_size,
    .get_local_supported_codecs = get_local_supported_codecs,
    .get_le_all_initiating_phys = get_le_all_initiating_phys,
    .clear_event_filter = controller_clear_event_filter,
    .clear_event_mask = controller_clear_event_mask,
    .le_rand = controller_le_rand,
    .set_event_filter_connection_setup_all_devices =
        controller_set_event_filter_connection_setup_all_devices,
    .set_event_filter_allow_device_connection =
        controller_set_event_filter_allow_device_connection,
    .set_default_event_mask_except = controller_set_default_event_mask_except,
    .set_event_filter_inquiry_result_all_devices =
        controller_set_event_filter_inquiry_result_all_devices};

const controller_t* bluetooth::shim::controller_get_interface() {
  static bool loaded = false;
  if (!loaded) {
    loaded = true;
  }
  return &interface;
}

bool bluetooth::shim::controller_is_write_link_supervision_timeout_supported() {
  return bluetooth::shim::GetController()->IsSupported(
      bluetooth::hci::OpCode::WRITE_LINK_SUPERVISION_TIMEOUT);
}
