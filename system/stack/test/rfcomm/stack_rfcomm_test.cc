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
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "stack/include/bt_psm_types.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/port_api.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/rfcomm/rfc_int.h"
#include "stack/test/common/stack_test_packet_utils.h"
#include "stack/test/rfcomm/stack_rfcomm_test_utils.h"
#include "test/mock/mock_main_shim_entry.h"

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
const RawAddress kRawAddress = RawAddress("11:22:33:44:55:66");
const RawAddress kRawAddress2 = RawAddress("01:02:03:04:05:06");

bluetooth::rfcomm::MockRfcommCallback* rfcomm_callback = nullptr;

class MockCoCallback {
 public:
  MOCK_METHOD(int, Callback, (uint8_t handle, uint8_t* p_buf, uint16_t len, int type));
};

static MockCoCallback* mock_co_callback = nullptr;

void port_mgmt_cback_0(const tPORT_RESULT code, uint8_t port_handle) {
  rfcomm_callback->PortManagementCallback(code, port_handle, 0);
}

void port_mgmt_cback_1(const tPORT_RESULT code, uint8_t port_handle) {
  rfcomm_callback->PortManagementCallback(code, port_handle, 1);
}

void port_event_cback_0(uint32_t code, uint8_t port_handle) {
  rfcomm_callback->PortEventCallback(code, port_handle, 0);
}

void port_event_cback_1(uint32_t code, uint8_t port_handle) {
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

class StackRfcommTest : public ::testing::Test {
public:
  /*
   * Start Collision steps:
   * 1. Open a server port
   * 2. Send a connection request
   * 3. Process peer ConnectInd
   */
  void StartCollision(uint8_t scn, uint16_t mtu, uint16_t out_lcid, uint16_t in_lcid,
                      RawAddress peer_addr, uint8_t& server_handle, uint8_t& client_handle) {
    log::verbose("Step 1");
    int status = RFCOMM_CreateConnectionWithSecurity(UUID_SERIAL_PORT, scn, true, mtu,
                                                     RawAddress::kAny, &server_handle,
                                                     port_mgmt_cback_0, 0, RfcommCfgInfo{});
    ASSERT_EQ(status, PORT_SUCCESS);
    ASSERT_NE(server_handle, 0);

    status = PORT_SetEventMaskAndCallback(server_handle, PORT_EV_RXCHAR, port_event_cback_0);
    ASSERT_EQ(status, PORT_SUCCESS);

    log::verbose("Step 2");
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

    log::verbose("Step 3");
    // Mux collision is when we receive a ConnectInd after sending our own ConnectReq
    l2cap_appl_info_.pL2CA_ConnectInd_Cb(peer_addr, in_lcid, BT_PSM_RFCOMM, L2CAP_CMD_CONFIG_RSP);
    ASSERT_EQ(rfc_cb.port.port[client_handle - 1].p_mcb->state, RFC_MX_STATE_CONFIGURE);
  }

  /*
   * Start Connecting steps:
   * 1. Open a server port
   * 2. Send a connection request
   */
  void StartConnecting(uint8_t scn, uint16_t mtu, uint16_t out_lcid, RawAddress peer_addr,
                       uint8_t& server_handle, uint8_t& client_handle) {
    log::verbose("Step 1");
    int status = RFCOMM_CreateConnectionWithSecurity(UUID_SERIAL_PORT, scn, true, mtu,
                                                     RawAddress::kAny, &server_handle,
                                                     port_mgmt_cback_0, 0, RfcommCfgInfo{});
    ASSERT_EQ(status, PORT_SUCCESS);
    ASSERT_NE(server_handle, 0);

    status = PORT_SetEventMaskAndCallback(server_handle, PORT_EV_RXCHAR, port_event_cback_0);
    ASSERT_EQ(status, PORT_SUCCESS);

    log::verbose("Step 2");
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

    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockController>();
    hci::Address mac_address;
    hci::Address::FromString("11:22:33:44:55:66", mac_address);
    ON_CALL(*bluetooth::hci::testing::mock_controller_, GetMacAddress())
            .WillByDefault(Return(mac_address));

    RFCOMM_Init();
    l2cap_appl_info_ = rfc_cb.rfc.reg_info;
  }
  void TearDown() override {
    bluetooth::hci::testing::mock_controller_.reset();
    bluetooth::testing::stack::l2cap::reset_interface();
  }
};

TEST_F(StackRfcommTest, test_PORT_IsCollisionDetected) {
  RawAddress test_bd_addr(kRawAddress);
  RawAddress different_bd_addr(kRawAddress2);

  rfc_cb.port.rfc_mcb[0].bd_addr = test_bd_addr;
  // no collisions will happen if the bd_addr don't match, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = RfcommMuxState(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(different_bd_addr));
  }

  rfc_cb.port.rfc_mcb[0].is_initiator = false;
  // no collisions will happen if not initiator, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = RfcommMuxState(state_int);
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
  // Null port shouldn't trigger collision
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].p_mcb = &rfc_cb.port.rfc_mcb[0];

  rfc_cb.port.port[0].sm_cb.state = RFC_STATE_CLOSED;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].sm_cb.state = RFC_STATE_SABME_WAIT_UA;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].sm_cb.state = RFC_STATE_TERM_WAIT_SEC_CHECK;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].sm_cb.state = RFC_STATE_OPENED;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_DISC_WAIT_UA;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
}

/*
 * Test steps:
 * 1. Establish collision
 * 2. Receive config request for incoming connection
 * 3. Receive SABME from incoming connection
 * 4. Send UA and PN in response to SABME
 * 5. Verify mux connected
 */
TEST_F(StackRfcommTest, collide_then_establish_incoming_conn) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  log::verbose("Step 1");
  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;

  log::verbose("Step 2");
  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  log::verbose("Step 3 and 4");
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
  log::verbose("Step 5");
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_CONNECTED);
}

/*
 * Test steps:
 * 1. Establish collision
 * 2. Receive config request for incoming connection
 * 3. Receive connection confirmation from peer for outgoing connection
 * 4. Receive config ind from peer for outgoing connection
 * 5. Timeout waiting for SABME for incoming connection
 * 6. Disconnect incoming connection and send SABME for outgoing connection
 * 7. Receive UA in response to SABME
 * 8. Verify mux connected
 */
TEST_F(StackRfcommTest, collide_then_establish_outgoing_conn) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  log::verbose("Step 1");
  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;

  log::verbose("Step 2");
  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  log::verbose("Step 3");
  // outgoing request may be accepted
  l2cap_appl_info_.pL2CA_ConnectCfm_Cb(outgoing_lcid, tL2CAP_CONN::L2CAP_CONN_OK);
  tL2CAP_CFG_INFO local_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  log::verbose("Step 4");
  l2cap_appl_info_.pL2CA_ConfigInd_Cb(outgoing_lcid, &local_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);  // state won't change

  log::verbose("Step 5 and 6");
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

  log::verbose("Step 7 and 8");
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
 * 1. Establish collision
 * 2. Receive config request for incoming connection
 * 3. Receive error for outgoing connection
 * 4. Verify nothing cached anymore
 */
TEST_F(StackRfcommTest, collide_then_err_outgoing_conn) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  log::verbose("Step 1");
  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;

  log::verbose("Step 2");
  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  log::verbose("Step 3");
  l2cap_appl_info_.pL2CA_Error_Cb(outgoing_lcid,
                                  static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_OTHER_ERROR));
  log::verbose("Step 4");
  ASSERT_EQ(p_mcb->collision_outgoing_lcid, 0);
}

/*
 * Test steps:
 * 1. Establish collision
 * 2. Receive config request for incoming connection
 * 3. Receive Disconnect request for outgoing connection
 * 4. Verify nothing cached anymore
 */
TEST_F(StackRfcommTest, collide_then_close_outgoing_conn) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  log::verbose("Step 1");
  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;

  log::verbose("Step 2");
  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  log::verbose("Step 3");
  l2cap_appl_info_.pL2CA_DisconnectInd_Cb(outgoing_lcid, false);
  log::verbose("Step 4");
  ASSERT_EQ(p_mcb->collision_outgoing_lcid, 0);
}

/*
 * Test steps:
 * 1. Establish collision
 * 2. Receive config request for incoming connection
 * 3. Timeout waiting for SABME for incoming connection
 * 4. Disconnect incoming connection
 * 5. Receive error from peer
 * 6. Verify PORT_START_FAILED and mux now IDLE
 */
TEST_F(StackRfcommTest, collide_then_err_outgoing_after_timeout) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  log::verbose("Step 1");
  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;

  log::verbose("Step 2");
  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  log::verbose("Step 3");
  // Timeout may happening waiting for SABME - in this case we attempt cached outgoing connection
  // We will call disconnect on the incoming_lcid and send out own SABME
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(incoming_lcid))
          .Times(1)
          .WillOnce(Return(true));
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_TIMEOUT, nullptr);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_CONN_CNF);

  log::verbose("Step 4");
  EXPECT_CALL(rfcomm_callback_,
              PortManagementCallback(tPORT_RESULT::PORT_START_FAILED, client_handle, 1));
  log::verbose("Step 5");
  l2cap_appl_info_.pL2CA_Error_Cb(outgoing_lcid,
                                  static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_OTHER_ERROR));
  log::verbose("Step 6");
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_IDLE);
}

/*
 * Test steps:
 * 1. Establish collision
 * 2. Receive config request for incoming connection
 * 3. Receive connection confirmation from peer for outgoing connection
 * 4. Receive config ind from peer for outgoing connection
 * 5. Timeout waiting for SABME for incoming connection
 * 6. Disconnect incoming connection
 * 7. Close outgoing connection
 * 8. Verify PORT_PEER_CONNECTION_FAILED and mux now IDLE
 */
TEST_F(StackRfcommTest, collide_then_close_outgoing_after_timeout) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  log::verbose("Step 1");
  ASSERT_NO_FATAL_FAILURE(StartCollision(test_scn, test_mtu, outgoing_lcid, incoming_lcid,
                                         test_peer_addr, server_handle, client_handle));
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;

  log::verbose("Step 2");
  tL2CAP_CFG_INFO peer_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(incoming_lcid, 1, &peer_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);

  log::verbose("Step 3");
  // outgoing request may be accepted
  l2cap_appl_info_.pL2CA_ConnectCfm_Cb(outgoing_lcid, tL2CAP_CONN::L2CAP_CONN_OK);
  tL2CAP_CFG_INFO local_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  log::verbose("Step 4");
  l2cap_appl_info_.pL2CA_ConfigInd_Cb(outgoing_lcid, &local_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_SABME);  // state won't change

  log::verbose("Step 5");
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

  log::verbose("Step 6");
  EXPECT_CALL(rfcomm_callback_,
              PortManagementCallback(tPORT_RESULT::PORT_PEER_CONNECTION_FAILED, client_handle, 1));
  l2cap_appl_info_.pL2CA_DisconnectInd_Cb(outgoing_lcid, false);
  log::verbose("Step 7");
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_IDLE);
}

/*
 * Test steps:
 * 1. Open a server port
 * 2. Send a connection request to a lower-addressed peer (Local wins)
 * 3. Receive ConnectInd from peer
 * 4. Verify incoming connection is rejected (L2CA_DisconnectReq)
 * 5. Verify outgoing connection continues
 */
TEST_F(StackRfcommTest, collide_local_wins) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  // Use a peer address smaller than local address (11:22:33:44:55:66)
  RawAddress peer_addr = kRawAddress2;

  log::verbose("Step 1 and 2");
  StartConnecting(test_scn, test_mtu, outgoing_lcid, peer_addr, server_handle, client_handle);
  tRFC_MCB* p_mcb = rfc_cb.port.port[client_handle - 1].p_mcb;
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_CONN_CNF);

  log::verbose("Step 3 and 4");
  // Mux collision: we receive a ConnectInd after sending our own ConnectReq
  // Since local address > peer address, local wins. We should reject the incoming connection.
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(incoming_lcid))
          .Times(1)
          .WillOnce(Return(true));

  l2cap_appl_info_.pL2CA_ConnectInd_Cb(peer_addr, incoming_lcid, BT_PSM_RFCOMM,
                                       L2CAP_CMD_CONFIG_RSP);

  log::verbose("Step 5");
  // Outgoing connection should remain in WAIT_CONN_CNF
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_WAIT_CONN_CNF);

  // Verify that the outgoing connection can successfully complete.
  l2cap_appl_info_.pL2CA_ConnectCfm_Cb(outgoing_lcid, tL2CAP_CONN::L2CAP_CONN_OK);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_CONFIGURE);

  tL2CAP_CFG_INFO local_cfg_req = {.mtu_present = true, .mtu = test_mtu};
  l2cap_appl_info_.pL2CA_ConfigInd_Cb(outgoing_lcid, &local_cfg_req);
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_CONFIGURE);

  BT_HDR* sabm_channel_0 = AllocateWrappedOutgoingL2capAclPacket(
          CreateQuickSabmPacket(RFCOMM_MX_DLCI, outgoing_lcid, acl_handle));
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_DataWrite(outgoing_lcid, BtHdrEqual(sabm_channel_0)))
          .WillOnce(Return(tL2CAP_DW_RESULT::SUCCESS));

  l2cap_appl_info_.pL2CA_ConfigCfm_Cb(outgoing_lcid, 1, &local_cfg_req);
  osi_free(sabm_channel_0);

  // Since it's the initiator, it sends SABME and waits for UA
  ASSERT_EQ(p_mcb->state, RFC_MX_STATE_SABME_WAIT_UA);
}

TEST_F(StackRfcommTest, rfc_port_sm_state_closed_RFC_PORT_EVENT_TIMEOUT) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  StartConnecting(test_scn, test_mtu, outgoing_lcid, test_peer_addr, server_handle, client_handle);
  tPORT* p_port = &rfc_cb.port.port[client_handle - 1];
  p_port->sm_cb.state = RFC_STATE_CLOSED;

  // Case 1: Flag enabled
  set_com_android_bluetooth_flags_release_port_instead_mux_when_timeout_after_closed(
      true);
  EXPECT_CALL(rfcomm_callback_,
              PortManagementCallback(tPORT_RESULT::PORT_CLOSED, client_handle, 1));
  rfc_port_sm_execute(p_port, RFC_PORT_EVENT_TIMEOUT, nullptr);

  // Re-establish connection for Case 2
  ASSERT_EQ(PORT_SUCCESS, RFCOMM_RemoveConnection(client_handle));
  ASSERT_EQ(PORT_SUCCESS, RFCOMM_RemoveServer(server_handle));

  tRFC_MCB* p_mcb_cleanup = rfc_find_lcid_mcb(outgoing_lcid);
  if (p_mcb_cleanup) {
    rfc_release_multiplexer_channel(p_mcb_cleanup);
  }

  rfc_cb.port.port[client_handle - 1].state = PORT_CONNECTION_STATE_CLOSED;
  rfc_cb.port.port[client_handle - 1].in_use = false;

  StartConnecting(test_scn, test_mtu, outgoing_lcid, test_peer_addr, server_handle, client_handle);
  p_port = &rfc_cb.port.port[client_handle - 1];
  p_port->sm_cb.state = RFC_STATE_CLOSED;

  // Case 2: Flag disabled
  set_com_android_bluetooth_flags_release_port_instead_mux_when_timeout_after_closed(
      false);
  EXPECT_CALL(rfcomm_callback_,
              PortManagementCallback(tPORT_RESULT::PORT_PEER_TIMEOUT, client_handle, 1));
  rfc_port_sm_execute(p_port, RFC_PORT_EVENT_TIMEOUT, nullptr);
}

TEST_F(StackRfcommTest, test_rfc_check_mcb_active_last_dlci) {
  tRFC_MCB mcb = {};
  mcb.is_disc_initiator = true;
  mcb.state = RFC_MX_STATE_CONNECTED;

  // Set only the last DLCI active
  mcb.port_handles[RFCOMM_MAX_DLCI] = 1;

  // The function should return early without starting any timer or closing MX,
  // and it should set is_disc_initiator to false.
  rfc_check_mcb_active(&mcb);

  ASSERT_FALSE(mcb.is_disc_initiator);
  // State should not change because the MX is not closed
  ASSERT_EQ(mcb.state, RFC_MX_STATE_CONNECTED);
}

static int test_co_callback(uint8_t handle, uint8_t* p_buf, uint16_t len, int type) {
  if (mock_co_callback) {
    return mock_co_callback->Callback(handle, p_buf, len, type);
  }
  return -1;
}

TEST_F(StackRfcommTest, test_PORT_WriteDataCO_PartialRead) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  StartConnecting(test_scn, test_mtu, outgoing_lcid, test_peer_addr, server_handle, client_handle);
  tPORT* p_port = &rfc_cb.port.port[client_handle - 1];

  MockCoCallback local_mock;
  mock_co_callback = &local_mock;

  // Set the callback!
  int status = PORT_SetDataCOCallback(client_handle, test_co_callback);
  ASSERT_EQ(status, PORT_SUCCESS);

  // 1. Mock OUTGOING_SIZE call
  EXPECT_CALL(local_mock, Callback(client_handle, _, _, DATA_CO_CALLBACK_TYPE_OUTGOING_SIZE))
          .WillOnce([](uint8_t /* handle */, uint8_t* p_buf, uint16_t /* len */, int /* type */) {
            int* p_size = (int*)p_buf;
            *p_size = 100; // Say we have 100 bytes available
            return 1; // Success
          });

  // 2. Mock OUTGOING call
  // We simulate a situation where we request reading 100 bytes (or up to MTU),
  // but callback returns only 10!
  EXPECT_CALL(local_mock, Callback(client_handle, _, _, DATA_CO_CALLBACK_TYPE_OUTGOING))
          .WillOnce([](uint8_t /* handle */, uint8_t* /* p_buf */,
                       uint16_t /* len */, int /* type */) {
            // Assume len is what it requested. We return less!
            // Let's say we read 10 bytes!
            return 10;
          })
          .WillRepeatedly(::testing::Return(0));
  // Subsequent calls return 0 (no more data or handled)

  int written_len = 0;
  status = PORT_WriteDataCO(client_handle, &written_len);

  ASSERT_EQ(status, PORT_SUCCESS);
  ASSERT_EQ(written_len, 10); // Since we only returned 10 in the first call and 0 in the second!

  // Clean up
  mock_co_callback = nullptr;
}

TEST_F(StackRfcommTest, test_PORT_WriteDataCO_AppendToLastBuffer) {
  uint8_t server_handle = 0;
  uint8_t client_handle = 0;

  StartConnecting(test_scn, test_mtu, outgoing_lcid, test_peer_addr, server_handle, client_handle);

  MockCoCallback local_mock;
  mock_co_callback = &local_mock;

  int status = PORT_SetDataCOCallback(client_handle, test_co_callback);
  ASSERT_EQ(status, PORT_SUCCESS);

  uint8_t* first_call_ptr = nullptr;

  // 1. First call: Write 10 bytes
  EXPECT_CALL(local_mock, Callback(client_handle, _, _, DATA_CO_CALLBACK_TYPE_OUTGOING_SIZE))
          .WillOnce([](uint8_t /* handle */, uint8_t* p_buf, uint16_t /* len */, int /* type */) {
            int* p_size = (int*)p_buf;
            *p_size = 10;
            return 1; // Success
          });

  EXPECT_CALL(local_mock, Callback(client_handle, _, _, DATA_CO_CALLBACK_TYPE_OUTGOING))
          .WillOnce([&first_call_ptr](uint8_t /* handle */, uint8_t* p_buf,
                                      uint16_t /* len */, int /* type */) {
            first_call_ptr = p_buf;
            return 10;
          });

  int written_len = 0;
  status = PORT_WriteDataCO(client_handle, &written_len);
  ASSERT_EQ(status, PORT_SUCCESS);
  ASSERT_EQ(written_len, 10);

  // 2. Second call: Write 20 bytes. It should append to the last buffer!
  EXPECT_CALL(local_mock, Callback(client_handle, _, _, DATA_CO_CALLBACK_TYPE_OUTGOING_SIZE))
          .WillOnce([](uint8_t /* handle */, uint8_t* p_buf, uint16_t /* len */, int /* type */) {
            int* p_size = (int*)p_buf;
            *p_size = 20;
            return 1; // Success
          });

  EXPECT_CALL(local_mock, Callback(client_handle, _, _, DATA_CO_CALLBACK_TYPE_OUTGOING))
          .WillOnce([&first_call_ptr](uint8_t /* handle */, uint8_t* p_buf,
                                      uint16_t /* len */, int /* type */) {
            // Verify that the pointer is shifted by 10!
            EXPECT_EQ(p_buf, first_call_ptr + 10);
            return 20;
          });

  status = PORT_WriteDataCO(client_handle, &written_len);
  ASSERT_EQ(status, PORT_SUCCESS);
  ASSERT_EQ(written_len, 20);

  // Clean up
  mock_co_callback = nullptr;
}

