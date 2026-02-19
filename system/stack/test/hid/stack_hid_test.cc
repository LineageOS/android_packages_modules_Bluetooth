/*
 *  Copyright 2021 The Android Open Source Project
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
 */

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <algorithm>

#include "common/message_loop_thread.h"
#include "osi/include/allocator.h"
#include "stack/hid/hidd_int.h"
#include "stack/hid/hidh_int.h"
#include "stack/include/acl_api.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/main_thread.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "test/common/mock_functions.h"

bluetooth::common::MessageLoopThread* get_main_thread() { return nullptr; }
tHCI_REASON btm_get_acl_disc_reason_code(void) { return HCI_SUCCESS; }

using ::testing::_;
using ::testing::DoAll;
using ::testing::NotNull;
using ::testing::Pointee;
using ::testing::Return;
using ::testing::ReturnArg;
using ::testing::SaveArg;
using ::testing::SaveArgPointee;
using ::testing::StrEq;
using ::testing::StrictMock;
using ::testing::Test;

namespace {

std::array<uint8_t, 32> data32 = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b,
        0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
        0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
};
const RawAddress kRawAddress("11:22:33:44:55:66");

class StackHidTest : public Test {
public:
protected:
  void SetUp() override {
    reset_mock_function_count_map();
    bluetooth::testing::stack::l2cap::set_interface(&l2cap_interface_);
  }
  void TearDown() override {
    bluetooth::testing::stack::l2cap::reset_interface();
    hd_cb.callback = nullptr;
  }

  bluetooth::testing::stack::l2cap::Mock l2cap_interface_;
  const tL2CAP_APPL_INFO* p_cb_info_;
};

TEST_F(StackHidTest, disconnect_bad_cid) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidh_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  l2cap_callbacks.pL2CA_Error_Cb(123, static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES));
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_DATA) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(32 + sizeof(BT_HDR)));
  p_msg->len = static_cast<uint16_t>(data32.size());
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);
  std::copy(data32.begin(), data32.end(), p_data);

  p_data = (uint8_t*)(p_msg + 1);
  uint8_t param = 0x09;
  *p_data = (uint8_t)((HID_TRANS_DATA << 4) | param);

  hd_cb.device.conn.intr_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONFIG;
  hd_cb.device.addr = kRawAddress;
  hd_cb.callback = [](const RawAddress& bd_addr, uint8_t event, uint32_t data, BT_HDR* p_buf) {
    ASSERT_EQ(bd_addr, kRawAddress);
    ASSERT_EQ(event, HID_DHOST_EVT_INTR_DATA);
    ASSERT_EQ(data, (uint32_t)0);
    ASSERT_EQ(p_buf->offset, 1);
    uint8_t* pdata = (uint8_t*)(p_buf + 1);
    ASSERT_TRUE(std::equal(data32.begin() + 1, data32.end(), pdata + 1));
  };

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
  osi_free(p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_DATA_ctrl_cid) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(32 + sizeof(BT_HDR)));
  p_msg->len = static_cast<uint16_t>(data32.size());
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);
  std::copy(data32.begin(), data32.end(), p_data);

  p_data = (uint8_t*)(p_msg + 1);
  uint8_t param = 0x09;
  *p_data = (uint8_t)((HID_TRANS_DATA << 4) | param);

  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.intr_cid = 0;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_GET_REPORT) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(32 + sizeof(BT_HDR)));
  p_msg->len = static_cast<uint16_t>(data32.size());
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);
  std::copy(data32.begin(), data32.end(), p_data);

  p_data = (uint8_t*)(p_msg + 1);
  uint8_t param = HID_PAR_GET_REP_BUFSIZE_FOLLOWS;
  *p_data = (uint8_t)((HID_TRANS_GET_REPORT << 4) | param);

  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.addr = kRawAddress;
  hd_cb.callback = [](const RawAddress& bd_addr, uint8_t event, uint32_t data, BT_HDR* p_buf) {
    ASSERT_EQ(bd_addr, kRawAddress);
    ASSERT_EQ(event, HID_DHOST_EVT_GET_REPORT);
    ASSERT_EQ(data, (uint32_t)1);
    ASSERT_EQ(p_buf->offset, 0);
    uint8_t* pdata = (uint8_t*)(p_buf + 1);
    ASSERT_TRUE(std::equal(data32.begin() + 1, data32.end(), pdata + 1));
  };

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
  osi_free(p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_GET_IDLE) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(1 + sizeof(BT_HDR)));
  p_msg->len = 1;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  *p_data = (uint8_t)(HID_TRANS_GET_IDLE << 4);

  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.idle_time = 100;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_SET_IDLE) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(2 + sizeof(BT_HDR)));
  p_msg->len = 2;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  *p_data = (uint8_t)(HID_TRANS_SET_IDLE << 4);
  *(p_data + 1) = 100;

  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
  ASSERT_EQ((uint8_t)100, hd_cb.device.idle_time);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_GET_PROTOCOL) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(1 + sizeof(BT_HDR)));
  p_msg->len = 1;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  *p_data = (uint8_t)(HID_TRANS_GET_PROTOCOL << 4);

  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.boot_mode = false;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_SET_PROTOCOL) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(1 + sizeof(BT_HDR)));
  p_msg->len = 1;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  uint8_t param = 1;
  *p_data = (uint8_t)((HID_TRANS_SET_PROTOCOL << 4) | param);

  hd_cb.device.addr = kRawAddress;
  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;
  hd_cb.device.boot_mode = true;

  hd_cb.callback = [](const RawAddress& bd_addr, uint8_t event, uint32_t data, BT_HDR*) {
    ASSERT_EQ(bd_addr, kRawAddress);
    ASSERT_EQ(HID_DHOST_EVT_SET_PROTOCOL, event);
    ASSERT_EQ(true, !!data);
  };

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
  ASSERT_EQ(false, hd_cb.device.boot_mode);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_CONTROL_SUSPEND) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(1 + sizeof(BT_HDR)));
  p_msg->len = 1;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  uint8_t param = HID_PAR_CONTROL_SUSPEND;
  *p_data = (uint8_t)((HID_TRANS_CONTROL << 4) | param);

  hd_cb.device.addr = kRawAddress;
  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;

  hd_cb.callback = [](const RawAddress& bd_addr, uint8_t event, uint32_t, BT_HDR*) {
    ASSERT_EQ(bd_addr, kRawAddress);
    ASSERT_EQ(HID_DHOST_EVT_SUSPEND, event);
  };

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_CONTROL_EXIT_SUSPEND) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(1 + sizeof(BT_HDR)));
  p_msg->len = 1;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  uint8_t param = HID_PAR_CONTROL_EXIT_SUSPEND;
  *p_data = (uint8_t)((HID_TRANS_CONTROL << 4) | param);

  hd_cb.device.addr = kRawAddress;
  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;

  hd_cb.callback = [](const RawAddress& bd_addr, uint8_t event, uint32_t, BT_HDR*) {
    ASSERT_EQ(bd_addr, kRawAddress);
    ASSERT_EQ(HID_DHOST_EVT_EXIT_SUSPEND, event);
  };

  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
}

TEST_F(StackHidTest, hd_data_ind_HID_TRANS_VIRTUAL_CABLE_UNPLUG) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  BT_HDR* p_msg = static_cast<BT_HDR*>(osi_calloc(1 + sizeof(BT_HDR)));
  p_msg->len = 1;
  p_msg->offset = 0;
  uint8_t* p_data = (uint8_t*)(p_msg + 1);

  uint8_t param = HID_PAR_CONTROL_VIRTUAL_CABLE_UNPLUG;
  *p_data = (uint8_t)((HID_TRANS_CONTROL << 4) | param);

  hd_cb.device.addr = kRawAddress;
  hd_cb.device.conn.ctrl_cid = cid;
  hd_cb.device.conn.intr_cid = 0;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;
  hd_cb.device.conn.conn_flags |= HID_CONN_FLAGS_CONGESTED;
  hd_cb.pending_vc_unplug = false;

  hd_cb.callback = [](const RawAddress& bd_addr, uint8_t event, uint32_t, BT_HDR*) {
    ASSERT_EQ(bd_addr, kRawAddress);
    ASSERT_EQ(HID_DHOST_EVT_CLOSE, event);
  };

  EXPECT_CALL(l2cap_interface_, L2CA_SetIdleTimeoutByBdAddr(_, _, _)).WillRepeatedly(Return(true));
  EXPECT_CALL(l2cap_interface_, L2CA_DisconnectReq(_)).WillRepeatedly(Return(true));
  l2cap_callbacks.pL2CA_DataInd_Cb(cid, p_msg);
  ASSERT_EQ(true, hd_cb.pending_vc_unplug);
}

TEST_F(StackHidTest, hd_conn_ind_psm_intr) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  uint16_t psm = HID_PSM_INTERRUPT;
  uint8_t id = 0;

  hd_cb.allow_incoming = true;
  hd_cb.device.conn.ctrl_cid = 1;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_CONNECTING_INTR;

  l2cap_callbacks.pL2CA_ConnectInd_Cb(kRawAddress, cid, psm, id);
  ASSERT_EQ(hd_cb.device.conn.conn_state, HID_CONN_STATE_CONFIG);
  ASSERT_EQ(hd_cb.device.conn.intr_cid, cid);
}

TEST_F(StackHidTest, hd_conn_ind_psm_ctrl) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  uint16_t psm = HID_PSM_CONTROL;
  uint8_t id = 0;

  hd_cb.allow_incoming = true;
  hd_cb.device.conn.ctrl_cid = 1;
  hd_cb.device.conn.conn_state = HID_CONN_STATE_UNUSED;

  l2cap_callbacks.pL2CA_ConnectInd_Cb(kRawAddress, cid, psm, id);
  ASSERT_EQ(hd_cb.device.in_use, TRUE);
  ASSERT_EQ(hd_cb.device.addr, kRawAddress);
  ASSERT_EQ(hd_cb.device.state, HIDD_DEV_NO_CONN);

  ASSERT_EQ(hd_cb.device.conn.conn_flags, 0);
  ASSERT_EQ(hd_cb.device.conn.ctrl_cid, cid);
  ASSERT_EQ(hd_cb.device.conn.disc_reason, HID_SUCCESS);
  ASSERT_EQ(hd_cb.device.conn.conn_state, HID_CONN_STATE_CONNECTING_INTR);
}

TEST_F(StackHidTest, hd_conn_ind_invalid_psm) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidd_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  uint16_t cid = 123;
  uint16_t psm = 123;
  uint8_t id = 0;

  hd_cb.allow_incoming = true;

  EXPECT_CALL(l2cap_interface_, L2CA_DisconnectReq(cid)).WillOnce(Return(true));

  l2cap_callbacks.pL2CA_ConnectInd_Cb(kRawAddress, cid, psm, id);
}

}  // namespace
