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

/******************************************************************************
 *
 *  This file contains function of the HCIC unit to format and send HCI
 *  commands.
 *
 ******************************************************************************/

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <stddef.h>

#include <bitset>

#include "hcimsgs.h"
#include "internal_include/bt_target.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"
#include "stack/include/btu_hcif.h"
#include "stack/include/hcidefs.h"

/*******************************************************************************
 * BLE Commands
 *      Note: "local_controller_id" is for transport, not counted in HCI
 *             message size
 ******************************************************************************/
#define HCIC_BLE_RAND_DI_SIZE 8
#define HCIC_BLE_IRK_SIZE 16

#define HCIC_PARAM_SIZE_SET_USED_FEAT_CMD 8
#define HCIC_PARAM_SIZE_WRITE_RANDOM_ADDR_CMD 6
#define HCIC_PARAM_SIZE_BLE_WRITE_SCAN_RSP 31
#define HCIC_PARAM_SIZE_BLE_WRITE_SCAN_PARAM 7
#define HCIC_PARAM_SIZE_BLE_WRITE_SCAN_ENABLE 2
#define HCIC_PARAM_SIZE_BLE_CREATE_LL_CONN 25
#define HCIC_PARAM_SIZE_BLE_CREATE_CONN_CANCEL 0
#define HCIC_PARAM_SIZE_CLEAR_ACCEPTLIST 0
#define HCIC_PARAM_SIZE_ADD_ACCEPTLIST 7
#define HCIC_PARAM_SIZE_REMOVE_ACCEPTLIST 7
#define HCIC_PARAM_SIZE_BLE_UPD_LL_CONN_PARAMS 14
#define HCIC_PARAM_SIZE_SET_HOST_CHNL_CLASS 5
#define HCIC_PARAM_SIZE_READ_CHNL_MAP 2
#define HCIC_PARAM_SIZE_BLE_READ_REMOTE_FEAT 2
#define HCIC_PARAM_SIZE_BLE_ENCRYPT 32
#define HCIC_PARAM_SIZE_WRITE_LE_HOST_SUPPORTED 2

#define HCIC_BLE_RAND_DI_SIZE 8
#define HCIC_BLE_ENCRYPT_KEY_SIZE 16
#define HCIC_PARAM_SIZE_BLE_START_ENC (4 + HCIC_BLE_RAND_DI_SIZE + HCIC_BLE_ENCRYPT_KEY_SIZE)
#define HCIC_PARAM_SIZE_LTK_REQ_REPLY (2 + HCIC_BLE_ENCRYPT_KEY_SIZE)
#define HCIC_PARAM_SIZE_LTK_REQ_NEG_REPLY 2
#define HCIC_BLE_CHNL_MAP_SIZE 5

#define HCIC_PARAM_SIZE_BLE_ADD_DEV_RESOLVING_LIST (7 + HCIC_BLE_IRK_SIZE * 2)
#define HCIC_PARAM_SIZE_BLE_RM_DEV_RESOLVING_LIST 7
#define HCIC_PARAM_SIZE_BLE_SET_PRIVACY_MODE 8
#define HCIC_PARAM_SIZE_BLE_CLEAR_RESOLVING_LIST 0
#define HCIC_PARAM_SIZE_BLE_READ_RESOLVING_LIST_SIZE 0
#define HCIC_PARAM_SIZE_BLE_READ_RESOLVABLE_ADDR_PEER 7
#define HCIC_PARAM_SIZE_BLE_READ_RESOLVABLE_ADDR_LOCAL 7
#define HCIC_PARAM_SIZE_BLE_SET_ADDR_RESOLUTION_ENABLE 1
#define HCIC_PARAM_SIZE_BLE_SET_RAND_PRIV_ADDR_TIMEOUT 2

#define HCIC_PARAM_SIZE_BLE_READ_PHY 2
#define HCIC_PARAM_SIZE_BLE_SET_DEFAULT_PHY 3
#define HCIC_PARAM_SIZE_BLE_SET_PHY 7
#define HCIC_PARAM_SIZE_BLE_ENH_RX_TEST 3
#define HCIC_PARAM_SIZE_BLE_ENH_TX_TEST 4

#define HCIC_PARAM_SIZE_BLE_SET_DATA_LENGTH 6
#define HCIC_PARAM_SIZE_BLE_WRITE_EXTENDED_SCAN_PARAM 11

#define HCIC_PARAM_SIZE_BLE_RC_PARAM_REQ_REPLY 14
#define HCIC_PARAM_SIZE_BLE_RC_PARAM_REQ_NEG_REPLY 3

#define HCIC_PARAM_SIZE_SET_CIG_PARAMS_BASE_LEN 15
#define HCIC_PARAM_SIZE_SET_CIG_PARAMS_PER_CIS_LEN 9
#define HCIC_PARAM_SIZE_CREATE_CIS_BASE_LEN 1
#define HCIC_PARAM_SIZE_CREATE_CIS_PER_CIS_LEN 4
#define HCIC_PARAM_SIZE_BLE_SETUP_ISO_DATA_PATH_BASE_LEN 13

#define HCIC_PARAM_SIZE_SET_BIG_CHANNEL_MAP_CLASSIFICATION_VSC_BASE 4

constexpr uint8_t kMaxParametersSize = 255;

void btsnd_hcic_ble_set_scan_params(uint8_t scan_type, uint16_t scan_int, uint16_t scan_win,
                                    uint8_t addr_type_own, uint8_t scan_filter_policy) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_WRITE_SCAN_PARAM;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_WRITE_SCAN_PARAMS);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_WRITE_SCAN_PARAM);

  UINT8_TO_STREAM(pp, scan_type);
  UINT16_TO_STREAM(pp, scan_int);
  UINT16_TO_STREAM(pp, scan_win);
  UINT8_TO_STREAM(pp, addr_type_own);
  UINT8_TO_STREAM(pp, scan_filter_policy);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_set_scan_enable(uint8_t scan_enable, uint8_t duplicate) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_WRITE_SCAN_ENABLE;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_WRITE_SCAN_ENABLE);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_WRITE_SCAN_ENABLE);

  UINT8_TO_STREAM(pp, scan_enable);
  UINT8_TO_STREAM(pp, duplicate);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_read_remote_feat(uint16_t handle) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_READ_REMOTE_FEAT;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_READ_REMOTE_FEAT);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_READ_REMOTE_FEAT);

  UINT16_TO_STREAM(pp, handle);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_rand(base::OnceCallback<void(Octet8)> cb) {
  btu_hcif_send_cmd_with_cb(
          HCI_BLE_RAND, nullptr, 0,
          base::BindOnce(
                  [](base::OnceCallback<void(Octet8)> cb,
                     bluetooth::hci::CommandCompleteView view) {
                    auto complete_view = bluetooth::hci::LeRandCompleteView::Create(view);
                    if (!complete_view.IsValid()) {
                      bluetooth::log::error("Invalid LE Rand complete view");
                      return;
                    }
                    if (complete_view.GetStatus() != bluetooth::hci::ErrorCode::SUCCESS) {
                      bluetooth::log::error(
                              "LE Rand command failed: {}",
                              bluetooth::hci::ErrorCodeText(complete_view.GetStatus()));
                      return;
                    }
                    auto random_number = complete_view.GetRandomNumber();
                    Octet8 rand;
                    for (int i = 0; i < 8; i++) {
                      rand[i] = (uint8_t)(random_number >> (i * 8));
                    }
                    std::move(cb).Run(rand);
                  },
                  std::move(cb)));
}

void btsnd_hcic_ble_start_enc(uint16_t handle, Octet8 rand, uint16_t ediv, const Octet16& ltk) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_START_ENC;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_START_ENC);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_START_ENC);

  UINT16_TO_STREAM(pp, handle);
  ARRAY_TO_STREAM(pp, rand, HCIC_BLE_RAND_DI_SIZE);
  UINT16_TO_STREAM(pp, ediv);
  ARRAY_TO_STREAM(pp, ltk.data(), HCIC_BLE_ENCRYPT_KEY_SIZE);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_ltk_req_reply(uint16_t handle, const Octet16& ltk) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_LTK_REQ_REPLY;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_LTK_REQ_REPLY);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_LTK_REQ_REPLY);

  UINT16_TO_STREAM(pp, handle);
  ARRAY_TO_STREAM(pp, ltk.data(), HCIC_BLE_ENCRYPT_KEY_SIZE);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_ltk_req_neg_reply(uint16_t handle) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_LTK_REQ_NEG_REPLY;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_LTK_REQ_NEG_REPLY);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_LTK_REQ_NEG_REPLY);

  UINT16_TO_STREAM(pp, handle);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_receiver_test(uint8_t rx_freq) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_WRITE_PARAM1;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_RECEIVER_TEST);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_WRITE_PARAM1);

  UINT8_TO_STREAM(pp, rx_freq);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_transmitter_test(uint8_t tx_freq, uint8_t test_data_len, uint8_t payload) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_WRITE_PARAM3;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_TRANSMITTER_TEST);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_WRITE_PARAM3);

  UINT8_TO_STREAM(pp, tx_freq);
  UINT8_TO_STREAM(pp, test_data_len);
  UINT8_TO_STREAM(pp, payload);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_test_end(void) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_READ_CMD;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_TEST_END);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_READ_CMD);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_read_resolvable_addr_peer(uint8_t addr_type_peer, const RawAddress& bda_peer) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_READ_RESOLVABLE_ADDR_PEER;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_READ_RESOLVABLE_ADDR_PEER);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_READ_RESOLVABLE_ADDR_PEER);
  UINT8_TO_STREAM(pp, addr_type_peer);
  BDADDR_TO_STREAM(pp, bda_peer);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_set_rand_priv_addr_timeout(uint16_t rpa_timeout) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_SET_RAND_PRIV_ADDR_TIMEOUT;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_SET_RAND_PRIV_ADDR_TIMEOUT);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_SET_RAND_PRIV_ADDR_TIMEOUT);
  UINT16_TO_STREAM(pp, rpa_timeout);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_set_data_length(uint16_t conn_handle, uint16_t tx_octets, uint16_t tx_time) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + HCIC_PARAM_SIZE_BLE_SET_DATA_LENGTH;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_BLE_SET_DATA_LENGTH);
  UINT8_TO_STREAM(pp, HCIC_PARAM_SIZE_BLE_SET_DATA_LENGTH);

  UINT16_TO_STREAM(pp, conn_handle);
  UINT16_TO_STREAM(pp, tx_octets);
  UINT16_TO_STREAM(pp, tx_time);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_set_extended_scan_params(uint8_t own_address_type,
                                             uint8_t scanning_filter_policy, uint8_t scanning_phys,
                                             scanning_phy_cfg* phy_cfg) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  int phy_cnt = std::bitset<std::numeric_limits<uint8_t>::digits>(scanning_phys).count();

  uint16_t param_len = 3 + (5 * phy_cnt);
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_SET_EXTENDED_SCAN_PARAMETERS);
  UINT8_TO_STREAM(pp, param_len);

  UINT8_TO_STREAM(pp, own_address_type);
  UINT8_TO_STREAM(pp, scanning_filter_policy);
  UINT8_TO_STREAM(pp, scanning_phys);

  for (int i = 0; i < phy_cnt; i++) {
    UINT8_TO_STREAM(pp, phy_cfg[i].scan_type);
    UINT16_TO_STREAM(pp, phy_cfg[i].scan_int);
    UINT16_TO_STREAM(pp, phy_cfg[i].scan_win);
  }

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_set_extended_scan_enable(uint8_t enable, uint8_t filter_duplicates,
                                             uint16_t duration, uint16_t period) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  const int param_len = 6;
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_SET_EXTENDED_SCAN_ENABLE);
  UINT8_TO_STREAM(pp, param_len);

  UINT8_TO_STREAM(pp, enable);
  UINT8_TO_STREAM(pp, filter_duplicates);
  UINT16_TO_STREAM(pp, duration);
  UINT16_TO_STREAM(pp, period);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_set_cig_params(uint8_t cig_id, uint32_t sdu_itv_c_to_p, uint32_t sdu_itv_p_to_c,
                                   uint8_t sca, uint8_t packing, uint8_t framing,
                                   uint16_t max_trans_lat_c_to_p, uint16_t max_trans_lat_p_to_c,
                                   uint8_t cis_cnt, const EXT_CIS_CFG* cis_cfg,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  const int params_len = HCIC_PARAM_SIZE_SET_CIG_PARAMS_BASE_LEN +
                         cis_cnt * HCIC_PARAM_SIZE_SET_CIG_PARAMS_PER_CIS_LEN;
  bluetooth::log::assert_that(params_len <= kMaxParametersSize,
                              "assert failed: params_len={} <= kMaxParametersSize={}", params_len,
                              kMaxParametersSize);
  uint8_t param[kMaxParametersSize];
  uint8_t* pp = param;

  UINT8_TO_STREAM(pp, cig_id);
  UINT24_TO_STREAM(pp, sdu_itv_c_to_p);
  UINT24_TO_STREAM(pp, sdu_itv_p_to_c);
  UINT8_TO_STREAM(pp, sca);
  UINT8_TO_STREAM(pp, packing);
  UINT8_TO_STREAM(pp, framing);
  UINT16_TO_STREAM(pp, max_trans_lat_c_to_p);
  UINT16_TO_STREAM(pp, max_trans_lat_p_to_c);
  UINT8_TO_STREAM(pp, cis_cnt);

  for (int i = 0; i < cis_cnt; i++) {
    UINT8_TO_STREAM(pp, cis_cfg[i].cis_id);
    UINT16_TO_STREAM(pp, cis_cfg[i].max_sdu_size_c_to_p);
    UINT16_TO_STREAM(pp, cis_cfg[i].max_sdu_size_p_to_c);
    UINT8_TO_STREAM(pp, cis_cfg[i].phy_c_to_p);
    UINT8_TO_STREAM(pp, cis_cfg[i].phy_p_to_c);
    UINT8_TO_STREAM(pp, cis_cfg[i].rtn_c_to_p);
    UINT8_TO_STREAM(pp, cis_cfg[i].rtn_p_to_c);
  }

  btu_hcif_send_cmd_with_cb(HCI_LE_SET_CIG_PARAMS, param, params_len,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}

void btsnd_hcic_ble_create_cis(uint8_t num_cis, const EXT_CIS_CREATE_CFG* cis_cfg,
                               base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  const int params_len =
          HCIC_PARAM_SIZE_CREATE_CIS_BASE_LEN + num_cis * HCIC_PARAM_SIZE_CREATE_CIS_PER_CIS_LEN;
  bluetooth::log::assert_that(params_len <= kMaxParametersSize,
                              "assert failed: params_len={} <= kMaxParametersSize={}", params_len,
                              kMaxParametersSize);
  uint8_t param[kMaxParametersSize];
  uint8_t* pp = param;

  UINT8_TO_STREAM(pp, num_cis);

  for (int i = 0; i < num_cis; i++) {
    UINT16_TO_STREAM(pp, cis_cfg[i].cis_conn_handle);
    UINT16_TO_STREAM(pp, cis_cfg[i].acl_conn_handle);
  }

  btu_hcif_send_cmd_with_cb(HCI_LE_CREATE_CIS, param, params_len,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}

void btsnd_hcic_ble_remove_cig(uint8_t cig_id, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  constexpr int kParamsLen = 1;
  uint8_t param[kParamsLen];
  uint8_t* pp = param;

  UINT8_TO_STREAM(pp, cig_id);

  btu_hcif_send_cmd_with_cb(HCI_LE_REMOVE_CIG, param, kParamsLen,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}

void btsnd_hcic_ble_req_peer_sca(uint16_t conn_handle) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  const int param_len = 2;
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_REQ_PEER_SCA);
  UINT8_TO_STREAM(pp, param_len);
  UINT16_TO_STREAM(pp, conn_handle);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_create_big(uint8_t big_handle, uint8_t adv_handle, uint8_t num_bis,
                               uint32_t sdu_itv, uint16_t max_sdu_size, uint16_t transport_latency,
                               uint8_t rtn, uint8_t phy, uint8_t packing, uint8_t framing,
                               uint8_t enc, std::array<uint8_t, 16> bcst_code) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  const int param_len = 31;
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_CREATE_BIG);
  UINT8_TO_STREAM(pp, param_len);

  UINT8_TO_STREAM(pp, big_handle);
  UINT8_TO_STREAM(pp, adv_handle);
  UINT8_TO_STREAM(pp, num_bis);
  UINT24_TO_STREAM(pp, sdu_itv);
  UINT16_TO_STREAM(pp, max_sdu_size);
  UINT16_TO_STREAM(pp, transport_latency);
  UINT8_TO_STREAM(pp, rtn);
  UINT8_TO_STREAM(pp, phy);
  UINT8_TO_STREAM(pp, packing);
  UINT8_TO_STREAM(pp, framing);
  UINT8_TO_STREAM(pp, enc);

  uint8_t* buf_ptr = bcst_code.data();
  ARRAY_TO_STREAM(pp, buf_ptr, bcst_code.size());

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_term_big(uint8_t big_handle, uint8_t reason) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  const int param_len = 2;
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_TERM_BIG);
  UINT8_TO_STREAM(pp, param_len);

  UINT8_TO_STREAM(pp, big_handle);
  UINT8_TO_STREAM(pp, reason);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_big_create_sync(uint8_t big_handle, uint16_t sync_handle, uint8_t encryption,
                                    const std::array<uint8_t, 16>& bcast_code, uint8_t mse,
                                    uint16_t sync_timeout, const std::vector<uint8_t>& bis) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  constexpr uint8_t kBigCreateSyncCommandBaseSize = 24;  // Command size excluding bis[i] field.
  auto param_len = kBigCreateSyncCommandBaseSize + bis.size();
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_BIG_CREATE_SYNC);
  UINT8_TO_STREAM(pp, param_len);

  UINT8_TO_STREAM(pp, big_handle);
  UINT16_TO_STREAM(pp, sync_handle);
  UINT8_TO_STREAM(pp, encryption);
  ARRAY_TO_STREAM(pp, bcast_code.data(), 16);
  UINT8_TO_STREAM(pp, mse);
  UINT16_TO_STREAM(pp, sync_timeout);
  UINT8_TO_STREAM(pp, bis.size());
  ARRAY_TO_STREAM(pp, bis.data(), bis.size());

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_big_terminate_sync(uint8_t big_handle,
                                       base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  uint8_t param[1];
  uint8_t* pp = param;

  UINT8_TO_STREAM(pp, big_handle);

  btu_hcif_send_cmd_with_cb(HCI_LE_BIG_TERM_SYNC, param, 1,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}
void btsnd_hcic_ble_setup_iso_data_path(uint16_t iso_handle, uint8_t data_path_dir,
                                        uint8_t data_path_id, uint8_t codec_id_format,
                                        uint16_t codec_id_company, uint16_t codec_id_vendor,
                                        uint32_t controller_delay, std::vector<uint8_t> codec_conf,
                                        base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  const int params_len = HCIC_PARAM_SIZE_BLE_SETUP_ISO_DATA_PATH_BASE_LEN + codec_conf.size();
  bluetooth::log::assert_that(params_len <= kMaxParametersSize,
                              "assert failed: params_len={} <= kMaxParametersSize={}", params_len,
                              kMaxParametersSize);
  uint8_t param[kMaxParametersSize];
  uint8_t* pp = param;

  UINT16_TO_STREAM(pp, iso_handle);
  UINT8_TO_STREAM(pp, data_path_dir);
  UINT8_TO_STREAM(pp, data_path_id);
  UINT8_TO_STREAM(pp, codec_id_format);
  UINT16_TO_STREAM(pp, codec_id_company);
  UINT16_TO_STREAM(pp, codec_id_vendor);
  UINT24_TO_STREAM(pp, controller_delay);
  UINT8_TO_STREAM(pp, codec_conf.size());
  ARRAY_TO_STREAM(pp, codec_conf.data(), static_cast<int>(codec_conf.size()));

  btu_hcif_send_cmd_with_cb(HCI_LE_SETUP_ISO_DATA_PATH, param, params_len,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}

void btsnd_hcic_ble_remove_iso_data_path(uint16_t iso_handle, uint8_t data_path_dir,
                                         base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  constexpr int kParamsLen = 3;
  uint8_t param[kParamsLen];
  uint8_t* pp = param;

  UINT16_TO_STREAM(pp, iso_handle);
  UINT8_TO_STREAM(pp, data_path_dir);

  btu_hcif_send_cmd_with_cb(HCI_LE_REMOVE_ISO_DATA_PATH, param, kParamsLen,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}

void btsnd_hcic_ble_read_iso_link_quality(uint16_t iso_handle,
                                          base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  constexpr int kParamsLen = 2;
  uint8_t param[kParamsLen];
  uint8_t* pp = param;

  UINT16_TO_STREAM(pp, iso_handle);

  btu_hcif_send_cmd_with_cb(HCI_LE_READ_ISO_LINK_QUALITY, param, kParamsLen,
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}

void btsnd_hcic_ble_set_big_channel_map_classification_vsc(uint8_t action, uint8_t big_handle,
                                                           const std::vector<uint16_t>& handles) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  const uint8_t param_len =
          HCIC_PARAM_SIZE_SET_BIG_CHANNEL_MAP_CLASSIFICATION_VSC_BASE + (handles.size() * 2);
  p->len = HCIC_PREAMBLE_SIZE + param_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, HCI_LE_SET_BIG_CHANNEL_MAP_CLASSIFICATION_OPCODE);
  UINT8_TO_STREAM(pp, param_len);

  UINT8_TO_STREAM(pp, SET_BIG_MAP_BY_CONNECTION_HANDLE);
  UINT8_TO_STREAM(pp, action);
  UINT8_TO_STREAM(pp, big_handle);
  UINT8_TO_STREAM(pp, handles.size());
  for (uint16_t handle : handles) {
    UINT16_TO_STREAM(pp, handle);
  }

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_accept_cis_req(uint16_t cis_conn_handle) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  p->offset = 0;
  p->len = HCIC_PREAMBLE_SIZE + sizeof(cis_conn_handle);

  uint8_t* pp = (uint8_t*)(p + 1);
  UINT16_TO_STREAM(pp, HCI_LE_ACCEPT_CIS_REQ);
  UINT8_TO_STREAM(pp, sizeof(cis_conn_handle));
  UINT16_TO_STREAM(pp, cis_conn_handle);

  btu_hcif_send_cmd(LOCAL_BR_EDR_CONTROLLER_ID, p);
}

void btsnd_hcic_ble_reject_cis_req(uint16_t cis_conn_handle, uint8_t reason,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
  uint8_t param[sizeof(cis_conn_handle) + sizeof(reason)];
  uint8_t* pp = param;

  UINT16_TO_STREAM(pp, cis_conn_handle);
  UINT8_TO_STREAM(pp, reason);
  btu_hcif_send_cmd_with_cb(HCI_LE_REJ_CIS_REQ, param, sizeof(param),
                            base::BindOnce(
                                    [](base::OnceCallback<void(uint8_t*, uint16_t)> cb,
                                       bluetooth::hci::CommandCompleteView view) {
                                      auto payload = view.GetPayload();
                                      std::vector<uint8_t> data(payload.begin(), payload.end());
                                      std::move(cb).Run(data.data(), data.size());
                                    },
                                    std::move(cb)));
}
