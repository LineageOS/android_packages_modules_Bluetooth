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

#include <allocator.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gtest/gtest.h>

#include "stack/include/bt_psm_types.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/port_api.h"
#include "stack/rfcomm/rfc_int.h"
#include "stack/test/common/stack_test_packet_utils.h"
#include "stack/test/rfcomm/stack_rfcomm_test_utils.h"
#include "test/mock/mock_stack_l2cap_interface.h"
#include "types/raw_address.h"

#define TEST_BT com::android::bluetooth::flags

#define UUID_SERIAL_PORT 0x1101
#define UUID_AG_HANDSFREE 0x111F

using namespace bluetooth;

using ::testing::_;
using ::testing::Pointee;
using ::testing::Return;
using ::testing::StrictMock;

using bluetooth::rfcomm::CreateQuickPnPacket;
using bluetooth::rfcomm::CreateQuickSabmPacket;
using bluetooth::rfcomm::CreateQuickUaPacket;
using bluetooth::rfcomm::GetDlci;

namespace {
const RawAddress kRawAddress = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const RawAddress kRawAddress2 = RawAddress({0x01, 0x02, 0x03, 0x04, 0x05, 0x06});

bluetooth::rfcomm::MockRfcommCallback* rfcomm_callback = nullptr;

void port_mgmt_cback_0(const tPORT_RESULT code, uint16_t port_handle) {
  rfcomm_callback->PortManagementCallback(code, port_handle, 0);
}

void port_mgmt_cback_1(const tPORT_RESULT code, uint16_t port_handle) {
  rfcomm_callback->PortManagementCallback(code, port_handle, 1);
}

void port_event_cback_0(uint32_t code, uint16_t port_handle) {
  rfcomm_callback->PortEventCallback(code, port_handle, 0);
}

void port_event_cback_1(uint32_t code, uint16_t port_handle) {
  rfcomm_callback->PortEventCallback(code, port_handle, 1);
}
}  // namespace

static std::string DumpByteBufferToString(uint8_t* p_data, size_t len) {
  std::stringstream str;
  str.setf(std::ios_base::hex, std::ios::basefield);
  str.setf(std::ios_base::uppercase);
  str.fill('0');
  for (size_t i = 0; i < len; ++i) {
    str << std::setw(2) << static_cast<uint16_t>(p_data[i]);
    str << " ";
  }
  return str.str();
}

static std::string DumpBtHdrToString(BT_HDR* p_hdr) {
  uint8_t* p_hdr_data = p_hdr->data + p_hdr->offset;
  return DumpByteBufferToString(p_hdr_data, p_hdr->len);
}

MATCHER_P(PointerMemoryEqual, ptr, DumpByteBufferToString((uint8_t*)ptr, sizeof(*ptr))) {
  return memcmp(arg, ptr, sizeof(*ptr)) == 0;
}

MATCHER_P(BtHdrEqual, expected, DumpBtHdrToString(expected)) {
  auto arg_hdr = static_cast<BT_HDR*>(arg);
  uint8_t* arg_data = arg_hdr->data + arg_hdr->offset;
  auto expected_hdr = static_cast<BT_HDR*>(expected);
  uint8_t* expected_data = expected_hdr->data + expected_hdr->offset;
  return memcmp(arg_data, expected_data, sizeof(*expected_data)) == 0;
}

class StackRfcommCollisionTest : public ::testing::Test {
public:
  /*
   * Start Collision steps:
   * - Open a server port
   * - Send a connection request
   * - Process peer ConnectInd
   */
  void StartCollision(uint8_t scn, uint16_t mtu, uint16_t out_lcid, uint16_t in_lcid,
                      RawAddress peer_addr, uint16_t& server_handle, uint16_t& client_handle) {
    // Open a server port
    int status = RFCOMM_CreateConnectionWithSecurity(UUID_SERIAL_PORT, scn, true, mtu,
                                                     RawAddress::kAny, &server_handle,
                                                     port_mgmt_cback_0, 0, RfcommCfgInfo{});
    ASSERT_EQ(status, PORT_SUCCESS);
    ASSERT_NE(server_handle, 0);

    status = PORT_SetEventMaskAndCallback(server_handle, PORT_EV_RXCHAR, port_event_cback_0);
    ASSERT_EQ(status, PORT_SUCCESS);

    // Send a connection request
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReq(BT_PSM_RFCOMM, peer_addr))
            .Times(1)
            .WillOnce(Return(out_lcid));
    status = RFCOMM_CreateConnectionWithSecurity(UUID_SERIAL_PORT, scn, false, mtu, peer_addr,
                                                 &client_handle, port_mgmt_cback_1, 0,
                                                 RfcommCfgInfo{});
    ASSERT_EQ(status, PORT_SUCCESS);
    ASSERT_NE(client_handle, 0);

    status = PORT_SetEventMaskAndCallback(client_handle, PORT_EV_RXCHAR, port_event_cback_1);
    ASSERT_EQ(status, PORT_SUCCESS);

    // Mux collision is when we receive a ConnectInd after sending our own ConnectReq
    l2cap_appl_info_.pL2CA_ConnectInd_Cb(peer_addr, in_lcid, BT_PSM_RFCOMM, L2CAP_CMD_CONFIG_RSP);
    ASSERT_EQ(rfc_cb.port.port[client_handle - 1].rfc.p_mcb->state, RFC_MX_STATE_CONFIGURE);
  }

protected:
  static const uint16_t acl_handle = 0x0008;
  static const uint8_t test_scn = 7;
  static const uint16_t test_mtu = 990;
  static const uint16_t outgoing_lcid = 0x005c;
  static const uint16_t incoming_lcid = 0x004a;
  RawAddress test_peer_addr = kRawAddress;
  StrictMock<bluetooth::testing::stack::l2cap::Mock> mock_stack_l2cap_interface_;
  StrictMock<bluetooth::rfcomm::MockRfcommCallback> rfcomm_callback_;
  tL2CAP_APPL_INFO l2cap_appl_info_;

  void SetUp() override {
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);
    rfcomm_callback = &rfcomm_callback_;
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_Register(BT_PSM_RFCOMM, _, _, _, _, _, _))
            .WillOnce(Return(BT_PSM_RFCOMM));
    RFCOMM_Init();
    l2cap_appl_info_ = rfc_cb.rfc.reg_info;
  }
  void TearDown() override { bluetooth::testing::stack::l2cap::reset_interface(); }
};

// TODO: b/406250389 - Remove when deleting flag donot_collide_with_closed_port
TEST_F(StackRfcommCollisionTest, PORT_IsCollisionDetected_basic) {
  RawAddress test_bd_addr(kRawAddress);
  RawAddress different_bd_addr(kRawAddress2);

  rfc_cb.port.rfc_mcb[0].bd_addr = test_bd_addr;
  // no collisions will happen if the bd_addr don't match, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = tRFC_MX_STATE(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(different_bd_addr));
  }

  rfc_cb.port.rfc_mcb[0].is_initiator = false;
  // no collisions will happen if not initiator, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = tRFC_MX_STATE(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  }

  // possible collisions if bd_addr match and is initiator
  rfc_cb.port.rfc_mcb[0].is_initiator = true;

  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_IDLE;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_WAIT_CONN_CNF;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_CONFIGURE;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_SABME_WAIT_UA;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_WAIT_SABME;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_CONNECTED;

  rfc_cb.port.port[0].rfc.p_mcb = &rfc_cb.port.rfc_mcb[0];
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_OPENED;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_TERM_WAIT_SEC_CHECK;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_DISC_WAIT_UA;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
}

TEST_F_WITH_FLAGS(StackRfcommCollisionTest, test_PORT_IsCollisionDetected,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT, donot_collide_with_closed_port))) {
  RawAddress test_bd_addr(kRawAddress);
  RawAddress different_bd_addr(kRawAddress2);

  rfc_cb.port.rfc_mcb[0].bd_addr = test_bd_addr;
  // no collisions will happen if the bd_addr don't match, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = tRFC_MX_STATE(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(different_bd_addr));
  }

  rfc_cb.port.rfc_mcb[0].is_initiator = false;
  // no collisions will happen if not initiator, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = tRFC_MX_STATE(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  }

  // possible collisions if bd_addr match and is initiator
  rfc_cb.port.rfc_mcb[0].is_initiator = true;

  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_IDLE;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_WAIT_CONN_CNF;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_CONFIGURE;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_SABME_WAIT_UA;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_WAIT_SABME;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));

  // Only some situations where state is CONNECTED can be collisions.
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_CONNECTED;
  rfc_cb.port.port[0].rfc.p_mcb = &rfc_cb.port.rfc_mcb[0];

  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_CLOSED;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_SABME_WAIT_UA;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_TERM_WAIT_SEC_CHECK;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_OPENED;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_DISC_WAIT_UA;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
}

/*
 * Test steps:
 * - Establish collision
 * - Receive config request for incoming connection
 * - Receive SABME from incoming connection
 * - Send UA and PN in response to SABME
 * - Verify mux connected
 */
TEST_F_WITH_FLAGS(StackRfcommCollisionTest, establish_incoming_conn,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      rfcomm_fix_mux_collision_handling))) {
  uint16_t server_handle = 0;
  uint16_t client_handle = 0;

  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].rfc.p_mcb;

  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  // We will send UA and PN cmd in response to SABME from peer
  BT_HDR* ua_channel_0 = AllocateWrappedOutgoingL2capAclPacket(
          CreateQuickUaPacket(RFCOMM_MX_DLCI, incoming_lcid, acl_handle));
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DataWrite(incoming_lcid, BtHdrEqual(ua_channel_0)))
          .WillOnce(Return(tL2CAP_DW_RESULT::SUCCESS));
  BT_HDR* uih_pn_cmd_to_peer = AllocateWrappedOutgoingL2capAclPacket(CreateQuickPnPacket(
          false, GetDlci(true, test_scn), false, test_mtu, RFCOMM_PN_CONV_LAYER_CBFC_R >> 4, 0,
          RFCOMM_K_MAX, incoming_lcid, acl_handle));
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_DataWrite(incoming_lcid, BtHdrEqual(uih_pn_cmd_to_peer)))
          .WillOnce(Return(tL2CAP_DW_RESULT::SUCCESS));
  BT_HDR* sabm_channel_0 = AllocateWrappedIncomingL2capAclPacket(
          CreateQuickSabmPacket(RFCOMM_MX_DLCI, incoming_lcid, acl_handle));
  l2cap_appl_info_.pL2CA_DataInd_Cb(incoming_lcid, sabm_channel_0);
  osi_free(ua_channel_0);
  osi_free(uih_pn_cmd_to_peer);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_CONNECTED);
}

/*
 * Test steps:
 * - Establish collision
 * - Receive config request for incoming connection
 * - Receive connection confirmation from peer for outgoing connection
 * - Receive config ind from peer for outgoing connection
 * - Timeout waiting for SABME for incoming connection
 * - Disconnect incoming connection and send SABME for outgoing connection
 * - Receive UA in response to SABME
 * - Verify mux connected
 */
TEST_F_WITH_FLAGS(StackRfcommCollisionTest, establish_outgoing_conn,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      rfcomm_fix_mux_collision_handling))) {
  uint16_t server_handle = 0;
  uint16_t client_handle = 0;

  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].rfc.p_mcb;

  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  // outgoing request may be accepted
  l2cap_appl_info_.pL2CA_ConnectCfm_Cb(outgoing_lcid, tL2CAP_CONN::L2CAP_CONN_OK);
  tL2CAP_CFG_INFO local_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigInd_Cb(outgoing_lcid, &local_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);  // state won't change

  // Timeout may happening waiting for SABME - in this case we attempt cached outgoing connection
  // We will call disconnect on the incoming_lcid and send out own SABME
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(incoming_lcid))
          .Times(1)
          .WillOnce(Return(true));
  BT_HDR* sabm_channel_0 = AllocateWrappedOutgoingL2capAclPacket(
          CreateQuickSabmPacket(RFCOMM_MX_DLCI, outgoing_lcid, acl_handle));
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_DataWrite(outgoing_lcid, BtHdrEqual(sabm_channel_0)))
          .WillOnce(Return(tL2CAP_DW_RESULT::SUCCESS));
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_TIMEOUT, nullptr);
  osi_free(sabm_channel_0);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_SABME_WAIT_UA);

  BT_HDR* uih_pn_cmd_to_peer = AllocateWrappedOutgoingL2capAclPacket(CreateQuickPnPacket(
          true, GetDlci(true, test_scn), true, test_mtu, RFCOMM_PN_CONV_LAYER_CBFC_I >> 4,
          RFCOMM_PN_PRIORITY_0, RFCOMM_K_MAX, outgoing_lcid, acl_handle));
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_DataWrite(outgoing_lcid, BtHdrEqual(uih_pn_cmd_to_peer)))
          .WillOnce(Return(tL2CAP_DW_RESULT::SUCCESS));
  BT_HDR* ua_channel_0 = AllocateWrappedIncomingL2capAclPacket(
          CreateQuickUaPacket(RFCOMM_MX_DLCI, outgoing_lcid, acl_handle));
  l2cap_appl_info_.pL2CA_DataInd_Cb(outgoing_lcid, ua_channel_0);
  osi_free(uih_pn_cmd_to_peer);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_CONNECTED);
}

/*
 * Test steps:
 * - Establish collision
 * - Receive config request for incoming connection
 * - Receive error for outgoing connection
 * - Verify nothing cached anymore
 */
TEST_F_WITH_FLAGS(StackRfcommCollisionTest, err_outgoing_after_collision,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      rfcomm_fix_mux_collision_handling))) {
  uint16_t server_handle = 0;
  uint16_t client_handle = 0;

  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].rfc.p_mcb;

  l2cap_appl_info_.pL2CA_Error_Cb(outgoing_lcid,
                                  static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_OTHER_ERROR));
  ASSERT_EQ(p_mcb->collision_outgoing_lcid, 0);
}

/*
 * Test steps:
 * - Establish collision
 * - Receive config request for incoming connection
 * - Receive Disconnect request for outgoing connection
 * - Verify nothing cached anymore
 */
TEST_F_WITH_FLAGS(StackRfcommCollisionTest, close_outgoing_after_collision,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      rfcomm_fix_mux_collision_handling))) {
  uint16_t server_handle = 0;
  uint16_t client_handle = 0;

  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].rfc.p_mcb;

  l2cap_appl_info_.pL2CA_DisconnectInd_Cb(outgoing_lcid, false);
  ASSERT_EQ(p_mcb->collision_outgoing_lcid, 0);
}

/*
 * Test steps:
 * - Establish collision
 * - Receive config request for incoming connection
 * - Timeout waiting for SABME for incoming connection
 * - Disconnect incoming connection
 * - Receive error from peer
 * - Verify PORT_START_FAILED and mux now IDLE
 */
TEST_F_WITH_FLAGS(StackRfcommCollisionTest, err_outgoing_after_timeout,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      rfcomm_fix_mux_collision_handling))) {
  uint16_t server_handle = 0;
  uint16_t client_handle = 0;

  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].rfc.p_mcb;

  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  // Timeout may happening waiting for SABME - in this case we attempt cached outgoing connection
  // We will call disconnect on the incoming_lcid and send out own SABME
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(incoming_lcid))
          .Times(1)
          .WillOnce(Return(true));
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_TIMEOUT, nullptr);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_CONN_CNF);

  EXPECT_CALL(rfcomm_callback_,
              PortManagementCallback(tPORT_RESULT::PORT_START_FAILED, client_handle, 1));
  l2cap_appl_info_.pL2CA_Error_Cb(outgoing_lcid,
                                  static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_OTHER_ERROR));
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_IDLE);
}

/*
 * Test steps:
 * - Establish collision
 * - Receive config request for incoming connection
 * - Receive connection confirmation from peer for outgoing connection
 * - Receive config ind from peer for outgoing connection
 * - Timeout waiting for SABME for incoming connection
 * - Disconnect incoming connection
 * - Close outgoing connection
 * - Verify PORT_PEER_CONNECTION_FAILED and mux now IDLE
 */
TEST_F_WITH_FLAGS(StackRfcommCollisionTest, close_outgoing_after_timeout,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      rfcomm_fix_mux_collision_handling))) {
  uint16_t server_handle = 0;
  uint16_t client_handle = 0;

  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].rfc.p_mcb;

  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  // outgoing request may be accepted
  l2cap_appl_info_.pL2CA_ConnectCfm_Cb(outgoing_lcid, tL2CAP_CONN::L2CAP_CONN_OK);
  tL2CAP_CFG_INFO local_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigInd_Cb(outgoing_lcid, &local_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);  // state won't change

  // Timeout may happening waiting for SABME - in this case we attempt cached outgoing connection
  // We will call disconnect on the incoming_lcid and send out own SABME
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(incoming_lcid))
          .Times(1)
          .WillOnce(Return(true));
  BT_HDR* sabm_channel_0 = AllocateWrappedOutgoingL2capAclPacket(
          CreateQuickSabmPacket(RFCOMM_MX_DLCI, outgoing_lcid, acl_handle));
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_DataWrite(outgoing_lcid, BtHdrEqual(sabm_channel_0)))
          .WillOnce(Return(tL2CAP_DW_RESULT::SUCCESS));
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_TIMEOUT, nullptr);
  osi_free(sabm_channel_0);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_SABME_WAIT_UA);
  ASSERT_EQ(p_mcb->collision_outgoing_lcid, 0);

  EXPECT_CALL(rfcomm_callback_,
              PortManagementCallback(tPORT_RESULT::PORT_PEER_CONNECTION_FAILED, client_handle, 1));
  l2cap_appl_info_.pL2CA_DisconnectInd_Cb(outgoing_lcid, false);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_IDLE);
}
