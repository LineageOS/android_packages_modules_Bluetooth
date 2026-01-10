/******************************************************************************
 *
 *  Copyright 2025 The Android Open Source Project
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
#include <bluetooth/types/address.h>
#include <bluetooth/types/hci_role.h>
#include <bluetooth/types/uuid.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdarg.h>

#include <string>
#include <vector>

#include "osi/include/allocator.h"
#include "stack/bnep/bnep_int.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"

using testing::_;
using testing::DoAll;
using testing::Invoke;
using testing::SaveArg;

// Mock class for BNEP callbacks
class MockBnepCallbacks {
public:
  MOCK_METHOD(void, ConnState,
              (uint16_t handle, const RawAddress& rem_bda, tBNEP_RESULT result,
               bool is_role_change));
  MOCK_METHOD(void, ConnInd,
              (uint16_t handle, const RawAddress& rem_bda, const bluetooth::Uuid& remote_uuid,
               const bluetooth::Uuid& local_uuid, bool is_role_change));
  MOCK_METHOD(void, DataInd,
              (uint16_t handle, const RawAddress& src, const RawAddress& dst, uint16_t protocol,
               uint8_t* p_data, uint16_t len, bool fw_ext_present));
  MOCK_METHOD(void, DataBuf,
              (uint16_t handle, const RawAddress& src, const RawAddress& dst, uint16_t protocol,
               BT_HDR* p_buf, bool fw_ext_present));
  MOCK_METHOD(void, FilterInd,
              (uint16_t handle, bool is_rcv, tBNEP_RESULT result, uint16_t num_filters,
               uint8_t* p_filters));
  MOCK_METHOD(void, MFilterInd,
              (uint16_t handle, bool is_rcv, tBNEP_RESULT result, uint16_t num_filters,
               uint8_t* p_filters));
  MOCK_METHOD(void, TxDataFlow, (uint16_t handle, uint8_t result));
};

// Global mock object
static MockBnepCallbacks* g_mock_callbacks = nullptr;

// Trampoline functions
static void conn_state_cb(uint16_t handle, const RawAddress& rem_bda, tBNEP_RESULT result,
                          bool is_role_change) {
  if (g_mock_callbacks) {
    g_mock_callbacks->ConnState(handle, rem_bda, result, is_role_change);
  }
}
static void conn_ind_cb(uint16_t handle, const RawAddress& rem_bda,
                        const bluetooth::Uuid& remote_uuid, const bluetooth::Uuid& local_uuid,
                        bool is_role_change) {
  if (g_mock_callbacks) {
    g_mock_callbacks->ConnInd(handle, rem_bda, remote_uuid, local_uuid, is_role_change);
  }
}
static void data_ind_cb(uint16_t handle, const RawAddress& src, const RawAddress& dst,
                        uint16_t protocol, uint8_t* p_data, uint16_t len, bool fw_ext_present) {
  if (g_mock_callbacks) {
    g_mock_callbacks->DataInd(handle, src, dst, protocol, p_data, len, fw_ext_present);
  }
}
static void data_buf_cb(uint16_t handle, const RawAddress& src, const RawAddress& dst,
                        uint16_t protocol, BT_HDR* p_buf, bool fw_ext_present) {
  if (g_mock_callbacks) {
    g_mock_callbacks->DataBuf(handle, src, dst, protocol, p_buf, fw_ext_present);
  } else {
    osi_free(p_buf);
  }
}
static void filter_ind_cb(uint16_t handle, bool is_rcv, tBNEP_RESULT result, uint16_t num_filters,
                          uint8_t* p_filters) {
  if (g_mock_callbacks) {
    g_mock_callbacks->FilterInd(handle, is_rcv, result, num_filters, p_filters);
  }
}
static void mfilter_ind_cb(uint16_t handle, bool is_rcv, tBNEP_RESULT result, uint16_t num_filters,
                           uint8_t* p_filters) {
  if (g_mock_callbacks) {
    g_mock_callbacks->MFilterInd(handle, is_rcv, result, num_filters, p_filters);
  }
}
static void tx_data_flow_cb(uint16_t handle, uint8_t result) {
  if (g_mock_callbacks) {
    g_mock_callbacks->TxDataFlow(handle, result);
  }
}

class StackBnepTest : public testing::Test {
protected:
  void SetUp() override { BNEP_Init(); }
  void TearDown() override {}

public:
};

// Test: bnep_register_with_l2cap
// Verify that BNEP can successfully register its PSM with L2CAP.
TEST_F(StackBnepTest, bnep_register_with_l2cap) {
  ASSERT_EQ(bnep_register_with_l2cap(), BNEP_SUCCESS);
}

// Test: BNEP_Init
// Verify that BNEP_Init resets the control block to its default state.
TEST_F(StackBnepTest, BNEP_Init) {
  tBNEP_REGISTER reg_info;
  reg_info.p_conn_state_cb = conn_state_cb;
  BNEP_Register(&reg_info);
  ASSERT_TRUE(bnep_cb.profile_registered);

  BNEP_Init();
  ASSERT_FALSE(bnep_cb.profile_registered);
  ASSERT_EQ(bnep_cb.p_conn_ind_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_conn_state_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_data_ind_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_data_buf_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_filter_ind_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_mfilter_ind_cb, nullptr);
}

// Test: BNEP_Register
// Verify BNEP_Register with valid and invalid parameters.
TEST_F(StackBnepTest, BNEP_Register) {
  tBNEP_REGISTER reg_info;

  // Test with null reg_info
  ASSERT_EQ(BNEP_Register(NULL), BNEP_SECURITY_FAIL);

  // Test with null conn_state_cb
  memset(&reg_info, 0, sizeof(reg_info));
  ASSERT_EQ(BNEP_Register(&reg_info), BNEP_SECURITY_FAIL);

  // Test with valid registration
  reg_info.p_conn_state_cb = conn_state_cb;
  reg_info.p_conn_ind_cb = conn_ind_cb;
  reg_info.p_data_ind_cb = data_ind_cb;
  reg_info.p_data_buf_cb = data_buf_cb;
  reg_info.p_filter_ind_cb = filter_ind_cb;
  reg_info.p_mfilter_ind_cb = mfilter_ind_cb;
  reg_info.p_tx_data_flow_cb = (tBNEP_TX_DATA_FLOW_CB*)tx_data_flow_cb;

  ASSERT_EQ(BNEP_Register(&reg_info), BNEP_SUCCESS);
  ASSERT_TRUE(bnep_cb.profile_registered);
  ASSERT_EQ(bnep_cb.p_conn_state_cb, conn_state_cb);
  ASSERT_EQ(bnep_cb.p_conn_ind_cb, conn_ind_cb);
  ASSERT_EQ(bnep_cb.p_data_ind_cb, data_ind_cb);
  ASSERT_EQ(bnep_cb.p_data_buf_cb, data_buf_cb);
  ASSERT_EQ(bnep_cb.p_filter_ind_cb, filter_ind_cb);
  ASSERT_EQ(bnep_cb.p_mfilter_ind_cb, mfilter_ind_cb);
  ASSERT_EQ(bnep_cb.p_tx_data_flow_cb, (tBNEP_TX_DATA_FLOW_CB*)tx_data_flow_cb);
}

// Test: BNEP_Deregister
// Verify that BNEP_Deregister clears the registration and callbacks.
TEST_F(StackBnepTest, BNEP_Deregister) {
  tBNEP_REGISTER reg_info;
  reg_info.p_conn_state_cb = conn_state_cb;
  ASSERT_EQ(BNEP_Register(&reg_info), BNEP_SUCCESS);
  ASSERT_TRUE(bnep_cb.profile_registered);

  BNEP_Deregister();
  ASSERT_FALSE(bnep_cb.profile_registered);
  ASSERT_EQ(bnep_cb.p_conn_ind_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_conn_state_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_data_ind_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_data_buf_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_filter_ind_cb, nullptr);
  ASSERT_EQ(bnep_cb.p_mfilter_ind_cb, nullptr);
}

// Test: BNEP_Connect_NotRegistered
// Verify that BNEP_Connect fails if the profile is not registered.
TEST_F(StackBnepTest, BNEP_Connect_NotRegistered) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  bluetooth::Uuid src_uuid = bluetooth::Uuid::From16Bit(0x110F);  // PANU
  bluetooth::Uuid dst_uuid = bluetooth::Uuid::From16Bit(0x1116);  // NAP
  uint16_t handle;

  ASSERT_EQ(BNEP_Connect(bd_addr, src_uuid, dst_uuid, &handle, 0), BNEP_WRONG_STATE);
}

// Test: BNEP_Connect_L2capFail
// Verify that BNEP_Connect fails when L2CAP connection fails.
TEST_F(StackBnepTest, DISABLED_BNEP_Connect_L2capFail) {
  tBNEP_REGISTER reg_info;
  reg_info.p_conn_state_cb = conn_state_cb;
  ASSERT_EQ(BNEP_Register(&reg_info), BNEP_SUCCESS);

  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  bluetooth::Uuid src_uuid = bluetooth::Uuid::From16Bit(0x110F);  // PANU
  bluetooth::Uuid dst_uuid = bluetooth::Uuid::From16Bit(0x1116);  // NAP
  uint16_t handle;

  // This will fail because L2CA_ConnectReqWithSecurity will return 0 without a
  // running peer.
  ASSERT_EQ(BNEP_Connect(bd_addr, src_uuid, dst_uuid, &handle, 0), BNEP_CONN_FAILED);
}

// Test: BNEP_ConnectResp_InvalidHandle
// Verify that BNEP_ConnectResp fails with an invalid handle.
TEST_F(StackBnepTest, BNEP_ConnectResp_InvalidHandle) {
  ASSERT_EQ(BNEP_ConnectResp(0, BNEP_SUCCESS), BNEP_WRONG_HANDLE);
  ASSERT_EQ(BNEP_ConnectResp(BNEP_MAX_CONNECTIONS + 1, BNEP_SUCCESS), BNEP_WRONG_HANDLE);
}

// Test: BNEP_ConnectResp_WrongState
// Verify that BNEP_ConnectResp fails if the connection is in the wrong state.
TEST_F(StackBnepTest, BNEP_ConnectResp_WrongState) {
  ASSERT_EQ(BNEP_ConnectResp(1, BNEP_SUCCESS), BNEP_WRONG_STATE);
}

// Test: BNEP_ConnectResp_Responses
// Verify handling of different response codes in BNEP_ConnectResp.
TEST_F(StackBnepTest, BNEP_ConnectResp_Responses) {
  // Setup a BCB to be in the correct state
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);
  ASSERT_NE(p_bcb, nullptr);

  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  ASSERT_EQ(BNEP_ConnectResp(p_bcb->handle, BNEP_CONN_FAILED_SRC_UUID), BNEP_SUCCESS);

  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  ASSERT_EQ(BNEP_ConnectResp(p_bcb->handle, BNEP_CONN_FAILED_DST_UUID), BNEP_SUCCESS);

  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  ASSERT_EQ(BNEP_ConnectResp(p_bcb->handle, BNEP_CONN_FAILED_UUID_SIZE), BNEP_SUCCESS);

  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  ASSERT_EQ(BNEP_ConnectResp(p_bcb->handle, BNEP_CONN_FAILED), BNEP_SUCCESS);

  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  ASSERT_EQ(BNEP_ConnectResp(p_bcb->handle, BNEP_SUCCESS), BNEP_SUCCESS);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_CONNECTED);
}

// Test: BNEP_Disconnect_InvalidHandle
// Verify that BNEP_Disconnect fails with an invalid handle.
TEST_F(StackBnepTest, BNEP_Disconnect_InvalidHandle) {
  ASSERT_EQ(BNEP_Disconnect(0), BNEP_WRONG_HANDLE);
  ASSERT_EQ(BNEP_Disconnect(BNEP_MAX_CONNECTIONS + 1), BNEP_WRONG_HANDLE);
}

// Test: BNEP_Disconnect_WrongState
// Verify that BNEP_Disconnect fails if there is no active connection.
TEST_F(StackBnepTest, BNEP_Disconnect_WrongState) {
  ASSERT_EQ(BNEP_Disconnect(1), BNEP_WRONG_HANDLE);
}

// Test: BNEP_WriteBuf_InvalidHandle
// Verify that BNEP_WriteBuf fails with an invalid handle.
TEST_F(StackBnepTest, BNEP_WriteBuf_InvalidHandle) {
  RawAddress dest_addr = RawAddress("11:22:33:44:55:66");
  BT_HDR* p_buf = (BT_HDR*)osi_malloc(100);
  p_buf->len = 10;
  p_buf->offset = 0;
  ASSERT_EQ(BNEP_WriteBuf(0, dest_addr, p_buf, 0, RawAddress::kEmpty, false), BNEP_WRONG_HANDLE);

  p_buf = (BT_HDR*)osi_malloc(100);
  p_buf->len = 10;
  p_buf->offset = 0;
  ASSERT_EQ(BNEP_WriteBuf(BNEP_MAX_CONNECTIONS + 1, dest_addr, p_buf, 0, RawAddress::kEmpty, false),
            BNEP_WRONG_HANDLE);
}

// Test: BNEP_WriteBuf_MtuExceeded
// Verify that BNEP_WriteBuf fails if the buffer size exceeds MTU.
TEST_F(StackBnepTest, BNEP_WriteBuf_MtuExceeded) {
  RawAddress dest_addr = RawAddress("11:22:33:44:55:66");
  BT_HDR* p_buf = (BT_HDR*)osi_malloc(sizeof(BT_HDR) + BNEP_MTU_SIZE + 1);
  p_buf->len = BNEP_MTU_SIZE + 1;
  p_buf->offset = 0;
  // A valid handle is needed to pass the first check.
  // The bcb at this handle is not used before the MTU check.
  ASSERT_EQ(BNEP_WriteBuf(1, dest_addr, p_buf, 0, RawAddress::kEmpty, false), BNEP_MTU_EXCEEDED);
}

// Test: BNEP_Write_InvalidHandle
// Verify that BNEP_Write fails with an invalid handle.
TEST_F(StackBnepTest, BNEP_Write_InvalidHandle) {
  RawAddress dest_addr = RawAddress("11:22:33:44:55:66");
  uint8_t data[10];
  ASSERT_EQ(BNEP_Write(0, dest_addr, data, sizeof(data), 0, RawAddress::kEmpty, false),
            BNEP_WRONG_HANDLE);
  ASSERT_EQ(BNEP_Write(BNEP_MAX_CONNECTIONS + 1, dest_addr, data, sizeof(data), 0,
                       RawAddress::kEmpty, false),
            BNEP_WRONG_HANDLE);
}

// Test: BNEP_Write_MtuExceeded
// Verify that BNEP_Write fails if the data size exceeds MTU.
TEST_F(StackBnepTest, BNEP_Write_MtuExceeded) {
  RawAddress dest_addr = RawAddress("11:22:33:44:55:66");
  uint8_t data[BNEP_MTU_SIZE + 1];
  ASSERT_EQ(BNEP_Write(1, dest_addr, data, sizeof(data), 0, RawAddress::kEmpty, false),
            BNEP_MTU_EXCEEDED);
}

// Test: bnep_is_packet_allowed
// Verify the packet filtering logic for protocol and multicast filters.
TEST_F(StackBnepTest, bnep_is_packet_allowed) {
  tBNEP_CONN* p_bcb = &bnep_cb.bcb[0];
  RawAddress dest_addr = RawAddress("11:22:33:44:55:66");
  uint16_t protocol = 0x0800;  // IP
  uint8_t data[10];

  // No filters, should be allowed
  ASSERT_EQ(bnep_is_packet_allowed(p_bcb, dest_addr, protocol, false, data, sizeof(data)),
            BNEP_SUCCESS);

  // Protocol filter allows
  p_bcb->rcvd_num_filters = 1;
  p_bcb->rcvd_prot_filter_start[0] = 0x0800;
  p_bcb->rcvd_prot_filter_end[0] = 0x0806;
  ASSERT_EQ(bnep_is_packet_allowed(p_bcb, dest_addr, protocol, false, data, sizeof(data)),
            BNEP_SUCCESS);

  // Protocol filter blocks
  p_bcb->rcvd_prot_filter_start[0] = 0x0801;
  p_bcb->rcvd_prot_filter_end[0] = 0x0806;
  ASSERT_EQ(bnep_is_packet_allowed(p_bcb, dest_addr, protocol, false, data, sizeof(data)),
            BNEP_IGNORE_CMD);
  p_bcb->rcvd_num_filters = 0;  // reset

  // Multicast filter allows
  RawAddress multicast_addr = RawAddress("01:00:5e:00:00:01");
  p_bcb->rcvd_mcast_filters = 1;
  p_bcb->rcvd_mcast_filter_start[0] = RawAddress("01:00:5e:00:00:00");
  p_bcb->rcvd_mcast_filter_end[0] = RawAddress("01:00:5e:7f:ff:ff");
  ASSERT_EQ(bnep_is_packet_allowed(p_bcb, multicast_addr, protocol, false, data, sizeof(data)),
            BNEP_SUCCESS);

  // Multicast filter blocks
  RawAddress blocked_multicast_addr = RawAddress("01:00:5f:00:00:01");
  ASSERT_EQ(bnep_is_packet_allowed(p_bcb, blocked_multicast_addr, protocol, false, data,
                                   sizeof(data)),
            BNEP_IGNORE_CMD);
}

// Test: bnep_process_control_packet
// Verify handling of control packets with bad length or unknown commands.
TEST_F(StackBnepTest, bnep_process_control_packet) {
  tBNEP_CONN* p_bcb = &bnep_cb.bcb[0];
  uint8_t packet[50];
  uint16_t rem_len;

  // Test bad length
  rem_len = 0;
  ASSERT_EQ(bnep_process_control_packet(p_bcb, packet, &rem_len, false), nullptr);

  // Test unknown command
  packet[0] = 0xFF;  // Unknown command
  rem_len = 1;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);

  // Test BNEP_CONTROL_COMMAND_NOT_UNDERSTOOD
  packet[0] = BNEP_CONTROL_COMMAND_NOT_UNDERSTOOD;
  packet[1] = 0x01;  // command that was not understood
  rem_len = 2;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);
}

// Test: bnepu_find_bcb
// Verify finding a BNEP connection control block by CID or BD_ADDR.
TEST_F(StackBnepTest, bnepu_find_bcb) {
  RawAddress bd_addr1 = RawAddress("11:22:33:44:55:66");
  RawAddress bd_addr2 = RawAddress("AA:BB:CC:DD:EE:FF");
  tBNEP_CONN* p_bcb1 = bnepu_allocate_bcb(bd_addr1);
  p_bcb1->l2cap_cid = 1;
  p_bcb1->con_state = BNEP_STATE_CONNECTED;

  tBNEP_CONN* p_bcb2 = bnepu_allocate_bcb(bd_addr2);
  p_bcb2->l2cap_cid = 2;
  p_bcb2->con_state = BNEP_STATE_CONNECTED;

  // Test find by CID
  ASSERT_EQ(bnepu_find_bcb_by_cid(1), p_bcb1);
  ASSERT_EQ(bnepu_find_bcb_by_cid(2), p_bcb2);
  ASSERT_EQ(bnepu_find_bcb_by_cid(3), nullptr);

  // Test find by BD_ADDR
  ASSERT_EQ(bnepu_find_bcb_by_bd_addr(bd_addr1), p_bcb1);
  ASSERT_EQ(bnepu_find_bcb_by_bd_addr(bd_addr2), p_bcb2);
  ASSERT_EQ(bnepu_find_bcb_by_bd_addr(RawAddress::kEmpty), nullptr);

  bnepu_release_bcb(p_bcb1);
  bnepu_release_bcb(p_bcb2);
}

// Test: bnepu_allocate_and_release_bcb
// Verify allocation and release of BNEP connection control blocks.
TEST_F(StackBnepTest, bnepu_allocate_and_release_bcb) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);

  ASSERT_NE(p_bcb, nullptr);
  ASSERT_EQ(p_bcb->handle, 1);
  ASSERT_EQ(p_bcb->rem_bda, bd_addr);
  ASSERT_NE(p_bcb->conn_timer, nullptr);
  ASSERT_NE(p_bcb->xmit_q, nullptr);

  bnepu_release_bcb(p_bcb);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_IDLE);
  ASSERT_EQ(p_bcb->conn_timer, nullptr);
  ASSERT_EQ(p_bcb->xmit_q, nullptr);

  // Allocate all connections
  std::vector<tBNEP_CONN*> bcbs;
  for (int i = 0; i < BNEP_MAX_CONNECTIONS; i++) {
    p_bcb = bnepu_allocate_bcb(bd_addr);
    ASSERT_NE(p_bcb, nullptr);
    p_bcb->con_state = BNEP_STATE_CONNECTED;
    bcbs.push_back(p_bcb);
  }

  // Try to allocate one more
  p_bcb = bnepu_allocate_bcb(bd_addr);
  ASSERT_EQ(p_bcb, nullptr);

  // Release all
  for (auto bcb : bcbs) {
    bnepu_release_bcb(bcb);
  }
}

class StackBnepWithCallbacksTest : public StackBnepTest {
protected:
  MockBnepCallbacks mock_callbacks_;

  void SetUp() override {
    StackBnepTest::SetUp();
    g_mock_callbacks = &mock_callbacks_;
    tBNEP_REGISTER reg_info;
    reg_info.p_conn_state_cb = conn_state_cb;
    reg_info.p_conn_ind_cb = conn_ind_cb;
    reg_info.p_data_ind_cb = data_ind_cb;
    reg_info.p_data_buf_cb = data_buf_cb;
    reg_info.p_filter_ind_cb = filter_ind_cb;
    reg_info.p_mfilter_ind_cb = mfilter_ind_cb;
    reg_info.p_tx_data_flow_cb = tx_data_flow_cb;
    BNEP_Register(&reg_info);
  }

  void TearDown() override {
    g_mock_callbacks = nullptr;
    StackBnepTest::TearDown();
  }
};

// Test: bnep_process_setup_conn_req
// Verify handling of an incoming connection setup request.
TEST_F(StackBnepWithCallbacksTest, bnep_process_setup_conn_req) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;

  uint8_t setup_req[4];
  uint8_t* p = setup_req;
  uint16_t dst_uuid16 = 0x1116;
  uint16_t src_uuid16 = 0x110F;
  UINT16_TO_BE_STREAM(p, dst_uuid16);
  UINT16_TO_BE_STREAM(p, src_uuid16);

  EXPECT_CALL(mock_callbacks_, ConnInd(p_bcb->handle, bd_addr, _, _, false));
  bnep_process_setup_conn_req(p_bcb, setup_req, 2);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_CONN_SETUP);
  ASSERT_TRUE(p_bcb->con_flags & BNEP_FLAGS_SETUP_RCVD);
  ASSERT_EQ(p_bcb->src_uuid, bluetooth::Uuid::From16Bit(dst_uuid16));
  ASSERT_EQ(p_bcb->dst_uuid, bluetooth::Uuid::From16Bit(src_uuid16));

  // Test bad state
  p_bcb->con_state = BNEP_STATE_IDLE;
  bnep_process_setup_conn_req(p_bcb, setup_req, 2);

  // Test duplicate request
  p_bcb->con_state = BNEP_STATE_SEC_CHECKING;
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  bnep_process_setup_conn_req(p_bcb, setup_req, 2);

  bnepu_release_bcb(p_bcb);
}

// Test: bnep_process_setup_conn_response
// Verify processing of a connection setup response.
TEST_F(StackBnepWithCallbacksTest, bnep_process_setup_conn_response) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_IS_ORIG;

  uint8_t setup_rsp[2];
  uint8_t* p = setup_rsp;
  UINT16_TO_BE_STREAM(p, BNEP_SETUP_CONN_OK);

  EXPECT_CALL(mock_callbacks_, ConnState(p_bcb->handle, bd_addr, BNEP_SUCCESS, false));
  bnep_process_setup_conn_response(p_bcb, setup_rsp);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_CONNECTED);

  // Test failure response
  p_bcb = bnepu_allocate_bcb(bd_addr);
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_IS_ORIG;
  p = setup_rsp;
  UINT16_TO_BE_STREAM(p, BNEP_SETUP_INVALID_DEST_UUID);
  EXPECT_CALL(mock_callbacks_, ConnState(p_bcb->handle, bd_addr, BNEP_CONN_FAILED_DST_UUID, false));
  bnep_process_setup_conn_response(p_bcb, setup_rsp);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_IDLE);

  // Re-allocate for next test
  p_bcb = bnepu_allocate_bcb(bd_addr);
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_IS_ORIG;

  // Test wrong state
  p_bcb->con_state = BNEP_STATE_IDLE;
  bnep_process_setup_conn_response(p_bcb, setup_rsp);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_IDLE);

  bnepu_release_bcb(p_bcb);
}

// Test: bnep_connected
// Verify the bnep_connected function updates state and calls callbacks.
TEST_F(StackBnepWithCallbacksTest, bnep_connected) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;

  EXPECT_CALL(mock_callbacks_, ConnState(p_bcb->handle, bd_addr, BNEP_SUCCESS, false));
  bnep_connected(p_bcb);

  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_CONNECTED);
  ASSERT_TRUE(p_bcb->con_flags & BNEP_FLAGS_CONN_COMPLETED);
  ASSERT_EQ(p_bcb->re_transmits, 0);

  bnepu_release_bcb(p_bcb);
}

// Test: bnep_sec_check_complete
// Verify the security check completion handler.
TEST_F(StackBnepWithCallbacksTest, bnep_sec_check_complete) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);
  p_bcb->con_state = BNEP_STATE_SEC_CHECKING;

  // Test incoming connection (SETUP_RCVD flag is set)
  p_bcb->con_flags = BNEP_FLAGS_SETUP_RCVD;
  EXPECT_CALL(mock_callbacks_, ConnInd(p_bcb->handle, bd_addr, _, _, false));
  bnep_sec_check_complete(&bd_addr, BT_TRANSPORT_BR_EDR, p_bcb);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_CONN_SETUP);

  // Test outgoing connection
  p_bcb->con_state = BNEP_STATE_SEC_CHECKING;
  p_bcb->con_flags = BNEP_FLAGS_IS_ORIG;
  bnep_sec_check_complete(&bd_addr, BT_TRANSPORT_BR_EDR, p_bcb);
  ASSERT_EQ(p_bcb->con_state, BNEP_STATE_CONN_SETUP);

  bnepu_release_bcb(p_bcb);
}

// Test: bnep_process_control_packet_more_cases
// Verify processing of more control packet types.
TEST_F(StackBnepTest, bnep_process_control_packet_more_cases) {
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  tBNEP_CONN* p_bcb = bnepu_allocate_bcb(bd_addr);
  uint8_t packet[50];
  uint16_t rem_len;

  // Test BNEP_SETUP_CONNECTION_REQUEST_MSG
  uint8_t* p = packet;
  *p++ = BNEP_SETUP_CONNECTION_REQUEST_MSG;
  *p++ = 2;  // len
  UINT16_TO_BE_STREAM(p, 0x1116);
  UINT16_TO_BE_STREAM(p, 0x110F);
  rem_len = 1 + 1 + 4;
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);

  // Test BNEP_SETUP_CONNECTION_RESPONSE_MSG
  p = packet;
  *p++ = BNEP_SETUP_CONNECTION_RESPONSE_MSG;
  UINT16_TO_BE_STREAM(p, BNEP_SETUP_CONN_OK);
  rem_len = 1 + 2;
  p_bcb->con_state = BNEP_STATE_CONN_SETUP;
  p_bcb->con_flags = BNEP_FLAGS_IS_ORIG;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);

  // Test BNEP_FILTER_NET_TYPE_SET_MSG
  p = packet;
  *p++ = BNEP_FILTER_NET_TYPE_SET_MSG;
  UINT16_TO_BE_STREAM(p, 4);  // len
  UINT16_TO_BE_STREAM(p, 0x0800);
  UINT16_TO_BE_STREAM(p, 0x0806);
  rem_len = 1 + 2 + 4;
  p_bcb->con_state = BNEP_STATE_CONNECTED;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);

  // Test BNEP_FILTER_NET_TYPE_RESPONSE_MSG
  p = packet;
  *p++ = BNEP_FILTER_NET_TYPE_RESPONSE_MSG;
  UINT16_TO_BE_STREAM(p, BNEP_FILTER_CRL_OK);
  rem_len = 1 + 2;
  p_bcb->con_state = BNEP_STATE_CONNECTED;
  p_bcb->con_flags = BNEP_FLAGS_FILTER_RESP_PEND;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);

  // Test BNEP_FILTER_MULTI_ADDR_SET_MSG
  p = packet;
  *p++ = BNEP_FILTER_MULTI_ADDR_SET_MSG;
  UINT16_TO_BE_STREAM(p, 12);  // len
  memset(p, 0, 12);
  p += 12;
  rem_len = 1 + 2 + 12;
  p_bcb->con_state = BNEP_STATE_CONNECTED;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);

  // Test BNEP_FILTER_MULTI_ADDR_RESPONSE_MSG
  p = packet;
  *p++ = BNEP_FILTER_MULTI_ADDR_RESPONSE_MSG;
  UINT16_TO_BE_STREAM(p, BNEP_FILTER_CRL_OK);
  rem_len = 1 + 2;
  p_bcb->con_state = BNEP_STATE_CONNECTED;
  p_bcb->con_flags = BNEP_FLAGS_MULTI_RESP_PEND;
  bnep_process_control_packet(p_bcb, packet, &rem_len, false);
  ASSERT_EQ(rem_len, 0);
}
