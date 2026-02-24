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

/*
 * Generated mock file from original source file
 *   Functions generated:69
 *
 *  mockcify.pl ver 0.3.2
 */

#include <cstdint>
#include <functional>
#include <vector>

// Original included files, if any
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>

#include "base/callback.h"
#include "stack/include/hcimsgs.h"

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace stack_hcic_hciblecmds {

// Name: btsnd_hcic_ble_add_device_resolving_list
// Params: uint8_t addr_type_peer, const RawAddress& bda_peer, const Octet16&
// irk_peer, const Octet16& irk_local Return: void
struct btsnd_hcic_ble_add_device_resolving_list {
  std::function<void(uint8_t addr_type_peer, const RawAddress& bda_peer, const Octet16& irk_peer,
                     const Octet16& irk_local)>
          body{[](uint8_t /* addr_type_peer */, const RawAddress& /* bda_peer */,
                  const Octet16& /* irk_peer */, const Octet16& /* irk_local */) {}};
  void operator()(uint8_t addr_type_peer, const RawAddress& bda_peer, const Octet16& irk_peer,
                  const Octet16& irk_local) {
    body(addr_type_peer, bda_peer, irk_peer, irk_local);
  }
};
extern struct btsnd_hcic_ble_add_device_resolving_list btsnd_hcic_ble_add_device_resolving_list;

// Name: btsnd_hcic_ble_clear_resolving_list
// Params: void
// Return: void
struct btsnd_hcic_ble_clear_resolving_list {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btsnd_hcic_ble_clear_resolving_list btsnd_hcic_ble_clear_resolving_list;

// Name: btsnd_hcic_ble_ltk_req_neg_reply
// Params: uint16_t handle
// Return: void
struct btsnd_hcic_ble_ltk_req_neg_reply {
  std::function<void(uint16_t handle)> body{[](uint16_t /* handle */) {}};
  void operator()(uint16_t handle) { body(handle); }
};
extern struct btsnd_hcic_ble_ltk_req_neg_reply btsnd_hcic_ble_ltk_req_neg_reply;

// Name: btsnd_hcic_ble_ltk_req_reply
// Params: uint16_t handle, const Octet16& ltk
// Return: void
struct btsnd_hcic_ble_ltk_req_reply {
  std::function<void(uint16_t handle, const Octet16& ltk)> body{
          [](uint16_t /* handle */, const Octet16& /* ltk */) {}};
  void operator()(uint16_t handle, const Octet16& ltk) { body(handle, ltk); }
};
extern struct btsnd_hcic_ble_ltk_req_reply btsnd_hcic_ble_ltk_req_reply;

// Name: btsnd_hcic_ble_rand
// Params: base::OnceCallback<void(Octet8)>
// Return: void
struct btsnd_hcic_ble_rand {
  std::function<void(base::OnceCallback<void(Octet8)>)> body{
          [](base::OnceCallback<void(Octet8)> /* cb */) {}};
  void operator()(base::OnceCallback<void(Octet8)> cb) { body(std::move(cb)); }
};
extern struct btsnd_hcic_ble_rand btsnd_hcic_ble_rand;

// Name: btsnd_hcic_ble_read_remote_feat
// Params: uint16_t handle
// Return: void
struct btsnd_hcic_ble_read_remote_feat {
  std::function<void(uint16_t handle)> body{[](uint16_t /* handle */) {}};
  void operator()(uint16_t handle) { body(handle); }
};
extern struct btsnd_hcic_ble_read_remote_feat btsnd_hcic_ble_read_remote_feat;

// Name: btsnd_hcic_ble_read_resolvable_addr_peer
// Params: uint8_t addr_type_peer, const RawAddress& bda_peer
// Return: void
struct btsnd_hcic_ble_read_resolvable_addr_peer {
  std::function<void(uint8_t addr_type_peer, const RawAddress& bda_peer)> body{
          [](uint8_t /* addr_type_peer */, const RawAddress& /* bda_peer */) {}};
  void operator()(uint8_t addr_type_peer, const RawAddress& bda_peer) {
    body(addr_type_peer, bda_peer);
  }
};
extern struct btsnd_hcic_ble_read_resolvable_addr_peer btsnd_hcic_ble_read_resolvable_addr_peer;

// Name: btsnd_hcic_ble_receiver_test
// Params: uint8_t rx_freq
// Return: void
struct btsnd_hcic_ble_receiver_test {
  std::function<void(uint8_t rx_freq)> body{[](uint8_t /* rx_freq */) {}};
  void operator()(uint8_t rx_freq) { body(rx_freq); }
};
extern struct btsnd_hcic_ble_receiver_test btsnd_hcic_ble_receiver_test;

// Name: btsnd_hcic_ble_rm_device_resolving_list
// Params: uint8_t addr_type_peer, const RawAddress& bda_peer
// Return: void
struct btsnd_hcic_ble_rm_device_resolving_list {
  std::function<void(uint8_t addr_type_peer, const RawAddress& bda_peer)> body{
          [](uint8_t /* addr_type_peer */, const RawAddress& /* bda_peer */) {}};
  void operator()(uint8_t addr_type_peer, const RawAddress& bda_peer) {
    body(addr_type_peer, bda_peer);
  }
};
extern struct btsnd_hcic_ble_rm_device_resolving_list btsnd_hcic_ble_rm_device_resolving_list;

// Name: btsnd_hcic_ble_set_data_length
// Params: uint16_t conn_handle, uint16_t tx_octets, uint16_t tx_time
// Return: void
struct btsnd_hcic_ble_set_data_length {
  std::function<void(uint16_t conn_handle, uint16_t tx_octets, uint16_t tx_time)> body{
          [](uint16_t /* conn_handle */, uint16_t /* tx_octets */, uint16_t /* tx_time */) {}};
  void operator()(uint16_t conn_handle, uint16_t tx_octets, uint16_t tx_time) {
    body(conn_handle, tx_octets, tx_time);
  }
};
extern struct btsnd_hcic_ble_set_data_length btsnd_hcic_ble_set_data_length;

// Name: btsnd_hcic_ble_set_extended_scan_enable
// Params: uint8_t enable, uint8_t filter_duplicates, uint16_t duration,
// uint16_t period Return: void
struct btsnd_hcic_ble_set_extended_scan_enable {
  std::function<void(uint8_t enable, uint8_t filter_duplicates, uint16_t duration, uint16_t period)>
          body{[](uint8_t /* enable */, uint8_t /* filter_duplicates */, uint16_t /* duration */,
                  uint16_t /* period */) {}};
  void operator()(uint8_t enable, uint8_t filter_duplicates, uint16_t duration, uint16_t period) {
    body(enable, filter_duplicates, duration, period);
  }
};
extern struct btsnd_hcic_ble_set_extended_scan_enable btsnd_hcic_ble_set_extended_scan_enable;

// Name: btsnd_hcic_ble_set_extended_scan_params
// Params: uint8_t own_address_type, uint8_t scanning_filter_policy, uint8_t
// scanning_phys, scanning_phy_cfg* phy_cfg Return: void
struct btsnd_hcic_ble_set_extended_scan_params {
  std::function<void(uint8_t own_address_type, uint8_t scanning_filter_policy,
                     uint8_t scanning_phys, scanning_phy_cfg* phy_cfg)>
          body{[](uint8_t /* own_address_type */, uint8_t /* scanning_filter_policy */,
                  uint8_t /* scanning_phys */, scanning_phy_cfg* /* phy_cfg */) {}};
  void operator()(uint8_t own_address_type, uint8_t scanning_filter_policy, uint8_t scanning_phys,
                  scanning_phy_cfg* phy_cfg) {
    body(own_address_type, scanning_filter_policy, scanning_phys, phy_cfg);
  }
};
extern struct btsnd_hcic_ble_set_extended_scan_params btsnd_hcic_ble_set_extended_scan_params;

// Name: btsnd_hcic_ble_set_rand_priv_addr_timeout
// Params: uint16_t rpa_timeout
// Return: void
struct btsnd_hcic_ble_set_rand_priv_addr_timeout {
  std::function<void(uint16_t rpa_timeout)> body{[](uint16_t /* rpa_timeout */) {}};
  void operator()(uint16_t rpa_timeout) { body(rpa_timeout); }
};
extern struct btsnd_hcic_ble_set_rand_priv_addr_timeout btsnd_hcic_ble_set_rand_priv_addr_timeout;

// Name: btsnd_hcic_ble_set_scan_enable
// Params: uint8_t scan_enable, uint8_t duplicate
// Return: void
struct btsnd_hcic_ble_set_scan_enable {
  std::function<void(uint8_t scan_enable, uint8_t duplicate)> body{
          [](uint8_t /* scan_enable */, uint8_t /* duplicate */) {}};
  void operator()(uint8_t scan_enable, uint8_t duplicate) { body(scan_enable, duplicate); }
};
extern struct btsnd_hcic_ble_set_scan_enable btsnd_hcic_ble_set_scan_enable;

// Name: btsnd_hcic_ble_set_scan_params
// Params: uint8_t scan_type, uint16_t scan_int, uint16_t scan_win, uint8_t
// addr_type_own, uint8_t scan_filter_policy Return: void
struct btsnd_hcic_ble_set_scan_params {
  std::function<void(uint8_t scan_type, uint16_t scan_int, uint16_t scan_win, uint8_t addr_type_own,
                     uint8_t scan_filter_policy)>
          body{[](uint8_t /* scan_type */, uint16_t /* scan_int */, uint16_t /* scan_win */,
                  uint8_t /* addr_type_own */, uint8_t /* scan_filter_policy */) {}};
  void operator()(uint8_t scan_type, uint16_t scan_int, uint16_t scan_win, uint8_t addr_type_own,
                  uint8_t scan_filter_policy) {
    body(scan_type, scan_int, scan_win, addr_type_own, scan_filter_policy);
  }
};
extern struct btsnd_hcic_ble_set_scan_params btsnd_hcic_ble_set_scan_params;

// Name: btsnd_hcic_ble_start_enc
// Params: uint16_t handle, Octet8 rand, uint16_t ediv,
// const Octet16& ltk Return: void
struct btsnd_hcic_ble_start_enc {
  std::function<void(uint16_t handle, Octet8 rand, uint16_t ediv, const Octet16& ltk)> body{
          [](uint16_t /* handle */, Octet8 /* rand */, uint16_t /* ediv */,
             const Octet16& /* ltk */) {}};
  void operator()(uint16_t handle, Octet8 rand, uint16_t ediv, const Octet16& ltk) {
    body(handle, rand, ediv, ltk);
  }
};
extern struct btsnd_hcic_ble_start_enc btsnd_hcic_ble_start_enc;

// Name: btsnd_hcic_ble_test_end
// Params: void
// Return: void
struct btsnd_hcic_ble_test_end {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btsnd_hcic_ble_test_end btsnd_hcic_ble_test_end;

// Name: btsnd_hcic_ble_transmitter_test
// Params: uint8_t tx_freq, uint8_t test_data_len, uint8_t payload
// Return: void
struct btsnd_hcic_ble_transmitter_test {
  std::function<void(uint8_t tx_freq, uint8_t test_data_len, uint8_t payload)> body{
          [](uint8_t /* tx_freq */, uint8_t /* test_data_len */, uint8_t /* payload */) {}};
  void operator()(uint8_t tx_freq, uint8_t test_data_len, uint8_t payload) {
    body(tx_freq, test_data_len, payload);
  }
};
extern struct btsnd_hcic_ble_transmitter_test btsnd_hcic_ble_transmitter_test;

// Name: btsnd_hcic_ble_req_peer_sca
// Params: uint16_t conn_handle
// Return: void
struct btsnd_hcic_ble_req_peer_sca {
  std::function<void(uint16_t)> body{[](uint16_t /* conn_handle */) {}};
  void operator()(uint16_t conn_handle) { body(conn_handle); }
};
extern struct btsnd_hcic_ble_req_peer_sca btsnd_hcic_ble_req_peer_sca;

}  // namespace stack_hcic_hciblecmds
}  // namespace mock
}  // namespace test

// END mockcify generation
