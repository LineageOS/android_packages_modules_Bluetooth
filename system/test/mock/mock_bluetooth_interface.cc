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

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <sys/types.h>

#include <cstdint>

#include "btif/include/btif_common.h"
#include "btif/include/stack_manager_t.h"
#include "hardware/bluetooth.h"

void invoke_adapter_state_changed_cb(bt_state_t /* state */) {}
void invoke_adapter_properties_cb(bt_status_t /* status */, int /* num_properties */,
                                  bt_property_t* /* properties */) {}
void invoke_remote_device_properties_cb(bt_status_t /* status */, RawAddress /* bd_addr */,
                                        uint8_t /* address_type */, int /* num_properties */,
                                        bt_property_t* /* properties */) {}
void invoke_device_found_cb(int /* num_properties */, bt_property_t* /* properties */) {}
void invoke_discovery_state_changed_cb(bt_discovery_state_t /* state */) {}
void invoke_pin_request_cb(RawAddress /* bd_addr */, bt_bdname_t /* bd_name */, uint32_t /* cod */,
                           bool /* min_16_digit */, int /* pairing_algorithm */) {}
void invoke_ssp_request_cb(RawAddress /* bd_addr */, int /* transport */,
                           PairingVariant /* pairing_variant */, uint32_t /* pass_key */,
                           int /* pairing_algorithm */) {}
void invoke_oob_data_request_cb(tBT_TRANSPORT /* t */, bool /* valid */, Octet16 /* c */,
                                Octet16 /* r */, RawAddress /* raw_address */,
                                uint8_t /* address_type */) {}
void invoke_bond_state_changed_cb(bt_status_t /* status */, RawAddress /* bd_addr */,
                                  tBT_TRANSPORT /* transport */, bt_bond_state_t /* state */,
                                  PairingType /* pairing_type */, int /* fail_reason */,
                                  PairingInitiator /* pairing_initiator */) {}
void invoke_address_consolidate_cb(RawAddress /* main_bd_addr */,
                                   RawAddress /* secondary_bd_addr */) {}
void invoke_le_address_associate_cb(RawAddress /* main_bd_addr */,
                                    RawAddress /* secondary_bd_addr */,
                                    uint8_t /* identity_address_type */) {}
void invoke_acl_state_changed_cb(bt_status_t /* status */, AclLinkSpec& /* link_spec */,
                                 bt_acl_state_t /* state */, bt_hci_error_code_t /* hci_reason */,
                                 bt_conn_direction_t /* direction */, uint16_t /* acl_handle */) {}
void invoke_thread_evt_cb(bt_cb_thread_evt /* event */) {}

void invoke_le_test_mode_cb(bt_status_t /* status */, uint16_t /* count */) {}

void invoke_energy_info_cb(bt_activity_energy_info /* energy_info */,
                           bt_uid_traffic_t* /* uid_data */) {}
void invoke_link_quality_report_cb(uint64_t /* timestamp */, int /* report_id */, int /* rssi */,
                                   int /* snr */, int /* retransmission_count */,
                                   int /* packets_not_receive_count */,
                                   int /* negative_acknowledgement_count */) {}
void invoke_key_missing_cb(tBTA_DM_KEY_MISSING /* key_missing */) {}
void invoke_encryption_change_cb(bt_encryption_change_evt /* bd_addr */) {}
void stack_init(bluetooth::core::CoreInterface* /* interface */) {}
void stack_cleanup() {}
bool stack_is_running() { return true; }
