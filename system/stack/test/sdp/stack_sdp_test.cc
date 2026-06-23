/*
 * Copyright 2022 The Android Open Source Project
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
#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdlib.h>

#include <cstddef>
#include <vector>

#include "stack/include/bt_types.h"
#include "stack/include/sdp_api.h"
#include "stack/sdp/sdpint.h"
#include "test/mock/mock_osi_allocator.h"
#include "test/mock/mock_stack_l2cap_api.h"

#ifndef BT_DEFAULT_BUFFER_SIZE
#define BT_DEFAULT_BUFFER_SIZE (4096 + 16)
#endif

static int L2CA_ConnectReq2_cid = 0x42;
static RawAddress addr = RawAddress({0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6});
static tSDP_DISCOVERY_DB* sdp_db = nullptr;

class StackSdpMainTest : public ::testing::Test {
 protected:
  void SetUp() override {
    sdp_init();
    test::mock::stack_l2cap_api::L2CA_ConnectReq2.body =
        [](uint16_t psm, const RawAddress& p_bd_addr, uint16_t sec_level) {
          return ++L2CA_ConnectReq2_cid;
        };
    test::mock::stack_l2cap_api::L2CA_DataWrite.body = [](uint16_t cid,
                                                          BT_HDR* p_data) {
      osi_free_and_reset((void**)&p_data);
      return 0;
    };
    test::mock::stack_l2cap_api::L2CA_DisconnectReq.body = [](uint16_t cid) {
      return true;
    };
    test::mock::stack_l2cap_api::L2CA_Register2.body =
        [](uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, bool enable_snoop,
           tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu,
           uint16_t required_remote_mtu, uint16_t sec_level) {
          return 42;  // return non zero
        };
    test::mock::osi_allocator::osi_malloc.body = [](size_t size) {
      return malloc(size);
    };
    test::mock::osi_allocator::osi_free.body = [](void* ptr) { free(ptr); };
    test::mock::osi_allocator::osi_free_and_reset.body = [](void** ptr) {
      free(*ptr);
      *ptr = nullptr;
    };
    sdp_db = (tSDP_DISCOVERY_DB*)osi_malloc(BT_DEFAULT_BUFFER_SIZE);
  }

  void TearDown() override {
    osi_free(sdp_db);
    test::mock::stack_l2cap_api::L2CA_ConnectReq2 = {};
    test::mock::stack_l2cap_api::L2CA_Register2 = {};
    test::mock::stack_l2cap_api::L2CA_DataWrite = {};
    test::mock::stack_l2cap_api::L2CA_DisconnectReq = {};
    test::mock::osi_allocator::osi_malloc = {};
    test::mock::osi_allocator::osi_free = {};
    test::mock::osi_allocator::osi_free_and_reset = {};
  }
};

TEST_F(StackSdpMainTest, sdp_service_search_request) {
  ASSERT_TRUE(SDP_ServiceSearchRequest(addr, sdp_db, nullptr));
  int cid = L2CA_ConnectReq2_cid;
  tCONN_CB* p_ccb = sdpu_find_ccb_by_cid(cid);
  ASSERT_NE(p_ccb, nullptr);
  ASSERT_EQ(p_ccb->con_state, SDP_STATE_CONN_SETUP);

  tL2CAP_CFG_INFO cfg;
  sdp_cb.reg_info.pL2CA_ConfigCfm_Cb(p_ccb->connection_id, 0, &cfg);

  ASSERT_EQ(p_ccb->con_state, SDP_STATE_CONNECTED);

  sdp_disconnect(p_ccb, SDP_SUCCESS);
  sdp_cb.reg_info.pL2CA_DisconnectCfm_Cb(p_ccb->connection_id, 0);

  ASSERT_EQ(p_ccb->con_state, SDP_STATE_IDLE);
}

tCONN_CB* find_ccb(uint16_t cid, uint8_t state) {
  uint16_t xx;
  tCONN_CB* p_ccb;

  // Look through each connection control block
  for (xx = 0, p_ccb = sdp_cb.ccb; xx < SDP_MAX_CONNECTIONS; xx++, p_ccb++) {
    if ((p_ccb->con_state == state) && (p_ccb->connection_id == cid)) {
      return p_ccb;
    }
  }
  return nullptr;  // not found
}

TEST_F(StackSdpMainTest, sdp_service_search_request_queuing) {
  ASSERT_TRUE(SDP_ServiceSearchRequest(addr, sdp_db, nullptr));
  const int cid = L2CA_ConnectReq2_cid;
  tCONN_CB* p_ccb1 = find_ccb(cid, SDP_STATE_CONN_SETUP);
  ASSERT_NE(p_ccb1, nullptr);
  ASSERT_EQ(p_ccb1->con_state, SDP_STATE_CONN_SETUP);

  ASSERT_TRUE(SDP_ServiceSearchRequest(addr, sdp_db, nullptr));
  tCONN_CB* p_ccb2 = find_ccb(cid, SDP_STATE_CONN_PEND);
  ASSERT_NE(p_ccb2, nullptr);
  ASSERT_NE(p_ccb2, p_ccb1);
  ASSERT_EQ(p_ccb2->con_state, SDP_STATE_CONN_PEND);

  tL2CAP_CFG_INFO cfg;
  sdp_cb.reg_info.pL2CA_ConfigCfm_Cb(p_ccb1->connection_id, 0, &cfg);

  ASSERT_EQ(p_ccb1->con_state, SDP_STATE_CONNECTED);
  ASSERT_EQ(p_ccb2->con_state, SDP_STATE_CONN_PEND);

  p_ccb1->disconnect_reason = SDP_SUCCESS;
  sdp_disconnect(p_ccb1, SDP_SUCCESS);

  ASSERT_EQ(p_ccb1->con_state, SDP_STATE_IDLE);
  ASSERT_EQ(p_ccb2->con_state, SDP_STATE_CONNECTED);

  sdp_disconnect(p_ccb2, SDP_SUCCESS);
  sdp_cb.reg_info.pL2CA_DisconnectCfm_Cb(p_ccb2->connection_id, 0);

  ASSERT_EQ(p_ccb1->con_state, SDP_STATE_IDLE);
  ASSERT_EQ(p_ccb2->con_state, SDP_STATE_IDLE);
}

void sdp_callback(tSDP_RESULT result) {
  if (result == SDP_SUCCESS) {
    ASSERT_TRUE(SDP_ServiceSearchRequest(addr, sdp_db, nullptr));
  }
}

TEST_F(StackSdpMainTest, sdp_service_search_request_queuing_race_condition) {
  // start first request
  ASSERT_TRUE(SDP_ServiceSearchRequest(addr, sdp_db, sdp_callback));
  const int cid1 = L2CA_ConnectReq2_cid;
  tCONN_CB* p_ccb1 = find_ccb(cid1, SDP_STATE_CONN_SETUP);
  ASSERT_NE(p_ccb1, nullptr);
  ASSERT_EQ(p_ccb1->con_state, SDP_STATE_CONN_SETUP);

  tL2CAP_CFG_INFO cfg;
  sdp_cb.reg_info.pL2CA_ConfigCfm_Cb(p_ccb1->connection_id, 0, &cfg);

  ASSERT_EQ(p_ccb1->con_state, SDP_STATE_CONNECTED);

  sdp_disconnect(p_ccb1, SDP_SUCCESS);
  sdp_cb.reg_info.pL2CA_DisconnectCfm_Cb(p_ccb1->connection_id, 0);

  const int cid2 = L2CA_ConnectReq2_cid;
  ASSERT_NE(cid1, cid2);  // The callback a queued a new request
  tCONN_CB* p_ccb2 = find_ccb(cid2, SDP_STATE_CONN_SETUP);
  ASSERT_NE(p_ccb2, nullptr);
  // If race condition, this will be stuck in PEND
  ASSERT_EQ(p_ccb2->con_state, SDP_STATE_CONN_SETUP);

  sdp_disconnect(p_ccb2, SDP_SUCCESS);
}

TEST_F(StackSdpMainTest, write_read_max_attr_len_slicing) {
  uint32_t handle = SDP_CreateRecord();
  ASSERT_NE(handle, 0u);

  // Write max size attribute payload into DB
  std::vector<uint8_t> val(SDP_MAX_ATTR_LEN, 'A');
  ASSERT_TRUE(SDP_AddAttribute(handle, 0x1234, TEXT_STR_DESC_TYPE, val.size(), val.data()));

  // Prepare Server CCB
  tCONN_CB* p_ccb = sdpu_allocate_ccb();
  ASSERT_NE(p_ccb, nullptr);
  p_ccb->con_state = SDP_STATE_CONNECTED;
  p_ccb->connection_id = L2CA_ConnectReq2_cid;
  p_ccb->rem_mtu_size = 512;

  std::vector<uint8_t> accumulated_attr_list;
  bool hit_continuation = false;
  uint16_t cont_offset = 0;

  for (int i = 0; i < 50; ++i) { // max 50 chunks safety limit
    // Allocate request msg buffer
    BT_HDR* p_req_msg = (BT_HDR*)osi_malloc(SDP_DATA_BUF_SIZE);
    p_req_msg->offset = L2CAP_MIN_OFFSET;
    uint8_t* p_req = (uint8_t*)(p_req_msg + 1) + L2CAP_MIN_OFFSET;

    // Build SDP request header
    UINT8_TO_BE_STREAM(p_req, SDP_PDU_SERVICE_ATTR_REQ);
    UINT16_TO_BE_STREAM(p_req, 1); // trans_num = 1

    // Skip param_len for now
    uint8_t* p_param_len = p_req;
    p_req += 2;

    uint8_t* p_param_start = p_req;

    UINT32_TO_BE_STREAM(p_req, handle);
    UINT16_TO_BE_STREAM(p_req, 50); // max_list_len = 50 to force slicing

    // Attribute ID list containing 0x1234
    UINT8_TO_BE_STREAM(p_req, 0x35); // DATA_ELE_SEQ_DESC_TYPE, next byte len
    UINT8_TO_BE_STREAM(p_req, 0x03); // sequence length
    UINT8_TO_BE_STREAM(p_req, 0x09); // UINT, 2 bytes
    UINT16_TO_BE_STREAM(p_req, 0x1234); // attribute ID

    // Continuation state
    if (hit_continuation) {
      UINT8_TO_BE_STREAM(p_req, 2);
      UINT16_TO_BE_STREAM(p_req, cont_offset);
    } else {
      UINT8_TO_BE_STREAM(p_req, 0);
    }

    uint16_t param_len = p_req - p_param_start;
    p_req = p_param_len;
    UINT16_TO_BE_STREAM(p_req, param_len);

    p_req_msg->len = (p_param_start - (uint8_t*)p_req_msg) + param_len -
                     sizeof(BT_HDR) - L2CAP_MIN_OFFSET;

    BT_HDR* p_rsp_msg = nullptr;
    test::mock::stack_l2cap_api::L2CA_DataWrite.body =
        [&p_rsp_msg](uint16_t /* cid */, BT_HDR* p_data) {
          p_rsp_msg = p_data;
          return 0;
        };

    sdp_server_handle_client_req(p_ccb, p_req_msg);
    osi_free(p_req_msg);

    ASSERT_NE(p_rsp_msg, nullptr);
    uint8_t* p_rsp = (uint8_t*)(p_rsp_msg + 1) + p_rsp_msg->offset;
    uint8_t pdu_id;
    BE_STREAM_TO_UINT8(pdu_id, p_rsp);

    ASSERT_EQ(pdu_id, SDP_PDU_SERVICE_ATTR_RSP);

    uint16_t rsp_trans_num, rsp_param_len;
    BE_STREAM_TO_UINT16(rsp_trans_num, p_rsp);
    BE_STREAM_TO_UINT16(rsp_param_len, p_rsp);

    uint16_t attr_list_bytes;
    BE_STREAM_TO_UINT16(attr_list_bytes, p_rsp);

    accumulated_attr_list.insert(accumulated_attr_list.end(), p_rsp, p_rsp + attr_list_bytes);
    p_rsp += attr_list_bytes;

    uint8_t rsp_cont_len;
    BE_STREAM_TO_UINT8(rsp_cont_len, p_rsp);
    if (rsp_cont_len == 2) {
      hit_continuation = true;
      BE_STREAM_TO_UINT16(cont_offset, p_rsp);
    } else {
      hit_continuation = false;
    }

    osi_free(p_rsp_msg);

    if (!hit_continuation) {
      break;
    }
  }

  ASSERT_FALSE(hit_continuation);

  // Verification
  ASSERT_GE(accumulated_attr_list.size(), 3u + 3u + 400u);

  uint8_t* p_acc = accumulated_attr_list.data();
  uint8_t seq_descr;
  BE_STREAM_TO_UINT8(seq_descr, p_acc);
  // 0x36: DATA_ELE_SEQ_DESC_TYPE | SIZE_IN_NEXT_WORD ((6 << 3) | 6)
  ASSERT_EQ(seq_descr, 0x36);
  uint16_t seq_len;
  BE_STREAM_TO_UINT16(seq_len, p_acc);
  // Total sequence nested length: 3 (Attr ID element) + 3 (string val header) +
  // 400 (payload) = 406
  ASSERT_EQ(seq_len, 406);

  uint8_t id_descr;
  BE_STREAM_TO_UINT8(id_descr, p_acc);
  // 0x09: UINT_DESC_TYPE | SIZE_TWO_BYTES ((1 << 3) | 1)
  ASSERT_EQ(id_descr, 0x09);
  uint16_t returned_id;
  BE_STREAM_TO_UINT16(returned_id, p_acc);
  // Check the element returned has attribute ID 0x1234
  ASSERT_EQ(returned_id, 0x1234);

  uint8_t val_descr;
  BE_STREAM_TO_UINT8(val_descr, p_acc);
  // 0x26: TEXT_STR_DESC_TYPE | SIZE_IN_NEXT_WORD ((4 << 3) | 6)
  ASSERT_EQ(val_descr, 0x26);
  uint16_t val_len;
  BE_STREAM_TO_UINT16(val_len, p_acc);
  // 400 bytes matching stored SDP_MAX_ATTR_LEN (and not truncated to < 400)
  ASSERT_EQ(val_len, 400);

  // Validate the payload bytes match our expected write data
  for (int i = 0; i < 400; ++i) {
    ASSERT_EQ(p_acc[i], 'A');
  }

  sdpu_release_ccb(*p_ccb);
  SDP_DeleteRecord(handle);
}

TEST_F(StackSdpMainTest, SDP_AddAttribute__exceed_max_attr_len_truncation) {
  uint32_t record_handle = SDP_CreateRecord();
  ASSERT_NE((uint32_t)0, record_handle);

  // Create a buffer larger than SDP_MAX_ATTR_LEN
  uint32_t attr_len = SDP_MAX_ATTR_LEN + 10;
  std::vector<uint8_t> attr_val(attr_len, 'a');
  attr_val[attr_len - 1] = '\0';

  ASSERT_TRUE(SDP_AddAttribute(
          record_handle, ATTR_ID_SERVICE_NAME, TEXT_STR_DESC_TYPE, attr_len, attr_val.data()));

  tSDP_RECORD* record = sdp_db_find_record(record_handle);
  ASSERT_TRUE(record != nullptr);

  const tSDP_ATTRIBUTE* attribute =
          sdp_db_find_attr_in_rec(record, ATTR_ID_SERVICE_NAME, ATTR_ID_SERVICE_NAME);
  ASSERT_TRUE(attribute != nullptr);

  // Ensure that the attribute length was truncated to SDP_MAX_ATTR_LEN
  ASSERT_EQ((uint32_t)SDP_MAX_ATTR_LEN, attribute->len);
  ASSERT_EQ(sizeof(uint32_t) + SDP_MAX_ATTR_LEN, record->free_pad_ptr);

  ASSERT_TRUE(SDP_DeleteRecord(record_handle));
}
