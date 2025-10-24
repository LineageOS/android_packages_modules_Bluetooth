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
 *   Functions generated:69
 *
 *  mockcify.pl ver 0.3.2
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_stack_hcic_hciblecmds.h"

#include <cstdint>

#include "test/common/mock_functions.h"

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_hcic_hciblecmds {

// Function state capture and return values, if needed
struct btsnd_hcic_ble_ltk_req_neg_reply btsnd_hcic_ble_ltk_req_neg_reply;
struct btsnd_hcic_ble_ltk_req_reply btsnd_hcic_ble_ltk_req_reply;
struct btsnd_hcic_ble_rand btsnd_hcic_ble_rand;
struct btsnd_hcic_ble_read_remote_feat btsnd_hcic_ble_read_remote_feat;
struct btsnd_hcic_ble_read_resolvable_addr_peer btsnd_hcic_ble_read_resolvable_addr_peer;
struct btsnd_hcic_ble_receiver_test btsnd_hcic_ble_receiver_test;
struct btsnd_hcic_ble_set_data_length btsnd_hcic_ble_set_data_length;
struct btsnd_hcic_ble_set_extended_scan_enable btsnd_hcic_ble_set_extended_scan_enable;
struct btsnd_hcic_ble_set_extended_scan_params btsnd_hcic_ble_set_extended_scan_params;
struct btsnd_hcic_ble_set_rand_priv_addr_timeout btsnd_hcic_ble_set_rand_priv_addr_timeout;
struct btsnd_hcic_ble_set_scan_enable btsnd_hcic_ble_set_scan_enable;
struct btsnd_hcic_ble_set_scan_params btsnd_hcic_ble_set_scan_params;
struct btsnd_hcic_ble_start_enc btsnd_hcic_ble_start_enc;
struct btsnd_hcic_ble_test_end btsnd_hcic_ble_test_end;
struct btsnd_hcic_ble_transmitter_test btsnd_hcic_ble_transmitter_test;
struct btsnd_hcic_ble_create_big btsnd_hcic_ble_create_big;
struct btsnd_hcic_ble_create_cis btsnd_hcic_ble_create_cis;
struct btsnd_hcic_ble_read_iso_link_quality btsnd_hcic_ble_read_iso_link_quality;
struct btsnd_hcic_ble_remove_cig btsnd_hcic_ble_remove_cig;
struct btsnd_hcic_ble_remove_iso_data_path btsnd_hcic_ble_remove_iso_data_path;
struct btsnd_hcic_ble_req_peer_sca btsnd_hcic_ble_req_peer_sca;
struct btsnd_hcic_ble_set_cig_params btsnd_hcic_ble_set_cig_params;
struct btsnd_hcic_ble_setup_iso_data_path btsnd_hcic_ble_setup_iso_data_path;
struct btsnd_hcic_ble_term_big btsnd_hcic_ble_term_big;
struct btsnd_hcic_ble_set_big_channel_map_classification_vsc
        btsnd_hcic_ble_set_big_channel_map_classification_vsc;
struct btsnd_hcic_ble_big_create_sync btsnd_hcic_ble_big_create_sync;
struct btsnd_hcic_ble_big_terminate_sync btsnd_hcic_ble_big_terminate_sync;
struct btsnd_hcic_ble_accept_cis_req btsnd_hcic_ble_accept_cis_req;
struct btsnd_hcic_ble_reject_cis_req btsnd_hcic_ble_reject_cis_req;

}  // namespace stack_hcic_hciblecmds
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_hcic_hciblecmds {}  // namespace stack_hcic_hciblecmds
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void btsnd_hcic_ble_ltk_req_neg_reply(uint16_t handle) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_ltk_req_neg_reply(handle);
}
void btsnd_hcic_ble_ltk_req_reply(uint16_t handle, const Octet16& ltk) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_ltk_req_reply(handle, ltk);
}
void btsnd_hcic_ble_rand(base::OnceCallback<void(Octet8)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_rand(std::move(cb));
}
void btsnd_hcic_ble_read_remote_feat(uint16_t handle) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_read_remote_feat(handle);
}
void btsnd_hcic_ble_read_resolvable_addr_peer(uint8_t addr_type_peer, const RawAddress& bda_peer) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_read_resolvable_addr_peer(addr_type_peer,
                                                                              bda_peer);
}
void btsnd_hcic_ble_receiver_test(uint8_t rx_freq) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_receiver_test(rx_freq);
}
void btsnd_hcic_ble_set_data_length(uint16_t conn_handle, uint16_t tx_octets, uint16_t tx_time) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_data_length(conn_handle, tx_octets,
                                                                    tx_time);
}
void btsnd_hcic_ble_set_extended_scan_enable(uint8_t enable, uint8_t filter_duplicates,
                                             uint16_t duration, uint16_t period) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_extended_scan_enable(
          enable, filter_duplicates, duration, period);
}
void btsnd_hcic_ble_set_extended_scan_params(uint8_t own_address_type,
                                             uint8_t scanning_filter_policy, uint8_t scanning_phys,
                                             scanning_phy_cfg* phy_cfg) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_extended_scan_params(
          own_address_type, scanning_filter_policy, scanning_phys, phy_cfg);
}
void btsnd_hcic_ble_set_rand_priv_addr_timeout(uint16_t rpa_timeout) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_rand_priv_addr_timeout(rpa_timeout);
}
void btsnd_hcic_ble_set_scan_enable(uint8_t scan_enable, uint8_t duplicate) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_scan_enable(scan_enable, duplicate);
}
void btsnd_hcic_ble_set_scan_params(uint8_t scan_type, uint16_t scan_int, uint16_t scan_win,
                                    uint8_t addr_type_own, uint8_t scan_filter_policy) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_scan_params(
          scan_type, scan_int, scan_win, addr_type_own, scan_filter_policy);
}
void btsnd_hcic_ble_start_enc(uint16_t handle, Octet8 rand, uint16_t ediv, const Octet16& ltk) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_start_enc(handle, rand, ediv, ltk);
}
void btsnd_hcic_ble_test_end(void) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_test_end();
}
void btsnd_hcic_ble_transmitter_test(uint8_t tx_freq, uint8_t test_data_len, uint8_t payload) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_transmitter_test(tx_freq, test_data_len,
                                                                     payload);
}
void btsnd_hcic_ble_create_big(uint8_t big_handle, uint8_t adv_handle, uint8_t num_bis,
                               uint32_t sdu_itv, uint16_t max_sdu_size, uint16_t transport_latency,
                               uint8_t rtn, uint8_t phy, uint8_t packing, uint8_t framing,
                               uint8_t enc, std::array<uint8_t, 16> bcst_code) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_create_big(
          big_handle, adv_handle, num_bis, sdu_itv, max_sdu_size, transport_latency, rtn, phy,
          packing, framing, enc, bcst_code);
}
void btsnd_hcic_ble_create_cis(uint8_t num_cis, const EXT_CIS_CREATE_CFG* cis_cfg,
                               base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_create_cis(num_cis, cis_cfg, std::move(cb));
}
void btsnd_hcic_ble_read_iso_link_quality(uint16_t iso_handle,
                                          base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_read_iso_link_quality(iso_handle,
                                                                          std::move(cb));
}
void btsnd_hcic_ble_remove_cig(uint8_t cig_id, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_remove_cig(cig_id, std::move(cb));
}
void btsnd_hcic_ble_remove_iso_data_path(uint16_t iso_handle, uint8_t data_path_dir,
                                         base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_remove_iso_data_path(iso_handle, data_path_dir,
                                                                         std::move(cb));
}
void btsnd_hcic_ble_req_peer_sca(uint16_t conn_handle) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_req_peer_sca(conn_handle);
}
void btsnd_hcic_ble_set_cig_params(uint8_t cig_id, uint32_t sdu_itv_mtos, uint32_t sdu_itv_stom,
                                   uint8_t sca, uint8_t packing, uint8_t framing,
                                   uint16_t max_trans_lat_stom, uint16_t max_trans_lat_mtos,
                                   uint8_t cis_cnt, const EXT_CIS_CFG* cis_cfg,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_cig_params(
          cig_id, sdu_itv_mtos, sdu_itv_stom, sca, packing, framing, max_trans_lat_stom,
          max_trans_lat_mtos, cis_cnt, cis_cfg, std::move(cb));
}
void btsnd_hcic_ble_setup_iso_data_path(uint16_t iso_handle, uint8_t data_path_dir,
                                        uint8_t data_path_id, uint8_t codec_id_format,
                                        uint16_t codec_id_company, uint16_t codec_id_vendor,
                                        uint32_t controller_delay, std::vector<uint8_t> codec_conf,
                                        base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_setup_iso_data_path(
          iso_handle, data_path_dir, data_path_id, codec_id_format, codec_id_company,
          codec_id_vendor, controller_delay, codec_conf, std::move(cb));
}
void btsnd_hcic_ble_term_big(uint8_t big_handle, uint8_t reason) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_term_big(big_handle, reason);
}
void btsnd_hcic_ble_set_big_channel_map_classification_vsc(uint8_t action, uint8_t big_handle,
                                                           const std::vector<uint16_t>& handles) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_set_big_channel_map_classification_vsc(
          action, big_handle, handles);
}

void btsnd_hcic_ble_big_create_sync(uint8_t big_handle, uint16_t sync_handle, uint8_t encryption,
                                    const std::array<uint8_t, 16>& bcast_code, uint8_t mse,
                                    uint16_t sync_timeout, const std::vector<uint8_t>& bis) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_big_create_sync(
          big_handle, sync_handle, encryption, bcast_code, mse, sync_timeout, bis);
}

void btsnd_hcic_ble_big_terminate_sync(uint8_t big_handle,
                                       base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_big_terminate_sync(big_handle, std::move(cb));
}

void btsnd_hcic_ble_accept_cis_req(uint16_t cis_conn_handle) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_accept_cis_req(cis_conn_handle);
}

void btsnd_hcic_ble_reject_cis_req(uint16_t cis_conn_handle, uint8_t reason,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_hcic_hciblecmds::btsnd_hcic_ble_reject_cis_req(cis_conn_handle, reason,
                                                                   std::move(cb));
}

// Mocked functions complete
// END mockcify generation
