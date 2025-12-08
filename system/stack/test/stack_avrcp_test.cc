/*
 * Copyright 2020 The Android Open Source Project
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

#include <arpa/inet.h>  // htons
#include <dlfcn.h>
#include <gtest/gtest.h>
#include <sys/types.h>

#include "allocator.h"
#include "stack/include/avrc_api.h"
#include "stack/include/avrc_defs.h"
#include "stack/include/bt_types.h"

class StackAvrcpTest : public ::testing::Test {
protected:
  StackAvrcpTest() = default;

  virtual ~StackAvrcpTest() = default;
};

TEST_F(StackAvrcpTest, test_avrcp_ctrl_parse_vendor_rsp) {
  uint8_t scratch_buf[512]{};
  uint16_t scratch_buf_len = 512;
  tAVRC_MSG msg{};
  tAVRC_RESPONSE result{};
  uint8_t vendor_rsp_buf[512]{};

  msg.hdr.opcode = AVRC_OP_VENDOR;
  msg.hdr.ctype = AVRC_CMD_STATUS;

  memset(vendor_rsp_buf, 0, sizeof(vendor_rsp_buf));
  vendor_rsp_buf[0] = AVRC_PDU_GET_ELEMENT_ATTR;
  uint8_t* p = &vendor_rsp_buf[2];
  UINT16_TO_BE_STREAM(p, 0x0009);   // parameter length
  UINT8_TO_STREAM(p, 0x01);         // number of attributes
  UINT32_TO_STREAM(p, 0x00000000);  // attribute ID
  UINT16_TO_STREAM(p, 0x0000);      // character set ID
  UINT16_TO_STREAM(p, 0xffff);      // attribute value length
  msg.vendor.p_vendor_data = vendor_rsp_buf;
  msg.vendor.vendor_len = 13;
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, scratch_buf, &scratch_buf_len),
            AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, test_avrcp_parse_browse_rsp) {
  uint8_t scratch_buf[512]{};
  uint16_t scratch_buf_len = 512;
  tAVRC_MSG msg{};
  tAVRC_RESPONSE result{};
  uint8_t browse_rsp_buf[512]{};

  msg.hdr.opcode = AVRC_OP_BROWSE;

  memset(browse_rsp_buf, 0, sizeof(browse_rsp_buf));
  browse_rsp_buf[0] = AVRC_PDU_GET_ITEM_ATTRIBUTES;
  uint8_t* p = &browse_rsp_buf[1];
  UINT16_TO_BE_STREAM(p, 0x000a);   // parameter length;
  UINT8_TO_STREAM(p, 0x04);         // status
  UINT8_TO_STREAM(p, 0x01);         // number of attribute
  UINT32_TO_STREAM(p, 0x00000000);  // attribute ID
  UINT16_TO_STREAM(p, 0x0000);      // character set ID
  UINT16_TO_STREAM(p, 0xffff);      // attribute value length
  msg.browse.p_browse_data = browse_rsp_buf;
  msg.browse.browse_len = 13;
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, scratch_buf, &scratch_buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, test_avrcp_parse_browse_cmd) {
  uint8_t scratch_buf[512]{};
  tAVRC_MSG msg{};
  tAVRC_COMMAND result{};
  uint8_t browse_cmd_buf[512]{};

  msg.hdr.opcode = AVRC_OP_BROWSE;
  msg.browse.p_browse_data = browse_cmd_buf;
  msg.browse.browse_len = 2;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  memset(browse_cmd_buf, 0, sizeof(browse_cmd_buf));
  browse_cmd_buf[0] = AVRC_PDU_SET_BROWSED_PLAYER;
  msg.browse.browse_len = 3;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  msg.browse.browse_len = 5;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_NO_ERROR);

  memset(browse_cmd_buf, 0, sizeof(browse_cmd_buf));
  browse_cmd_buf[0] = AVRC_PDU_GET_FOLDER_ITEMS;
  msg.browse.browse_len = 3;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  msg.browse.browse_len = 13;
  uint8_t* p = &browse_cmd_buf[3];
  UINT8_TO_STREAM(p, AVRC_SCOPE_NOW_PLAYING);  // scope
  UINT32_TO_STREAM(p, 0x00000001);             // start_item
  UINT32_TO_STREAM(p, 0x00000002);             // end_item
  browse_cmd_buf[12] = 0;                      // attr_count
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_NO_ERROR);

  memset(browse_cmd_buf, 0, sizeof(browse_cmd_buf));
  browse_cmd_buf[0] = AVRC_PDU_CHANGE_PATH;
  msg.browse.browse_len = 3;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  msg.browse.browse_len = 14;
  p = &browse_cmd_buf[3];
  UINT16_TO_STREAM(p, 0x1234);      // uid_counter
  UINT8_TO_STREAM(p, AVRC_DIR_UP);  // direction
  UINT8_TO_STREAM(p, 0);            // attr_count
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_NO_ERROR);

  memset(browse_cmd_buf, 0, sizeof(browse_cmd_buf));
  browse_cmd_buf[0] = AVRC_PDU_GET_ITEM_ATTRIBUTES;
  msg.browse.browse_len = 3;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  msg.browse.browse_len = 15;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_NO_ERROR);

  memset(browse_cmd_buf, 0, sizeof(browse_cmd_buf));
  browse_cmd_buf[0] = AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS;
  msg.browse.browse_len = 3;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  msg.browse.browse_len = 4;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_NO_ERROR);

  memset(browse_cmd_buf, 0, sizeof(browse_cmd_buf));
  browse_cmd_buf[0] = AVRC_PDU_SEARCH;
  msg.browse.browse_len = 3;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_BAD_CMD);

  p = &browse_cmd_buf[3];
  UINT16_TO_STREAM(p, 0x0000);  // charset_id
  UINT16_TO_STREAM(p, 0x0000);  // str_len
  msg.browse.browse_len = 7;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, scratch_buf, sizeof(scratch_buf)), AVRC_STS_NO_ERROR);
}

TEST_F(StackAvrcpTest, test_avrcp_pdu_register_notification) {
  ASSERT_EQ(htons(0x500), 5);

  struct {
    uint8_t pdu;
    uint8_t reserved;
    uint16_t len;
    struct {
      uint8_t event_id;
      uint32_t param;
    } payload;
  } data = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0,  // reserved
          htons(sizeof(data.payload)),
          .payload =
                  {
                          .event_id = 0,
                          .param = 0x1234,
                  },
  };

  tAVRC_MSG msg = {
          .vendor =
                  {
                          .hdr =
                                  {
                                          .ctype = AVRC_CMD_NOTIF,
                                          .opcode = AVRC_OP_VENDOR,
                                  },
                          .p_vendor_data = (uint8_t*)&data,
                          .vendor_len = sizeof(data),
                  },
  };
  tAVRC_COMMAND result{};

  // Run through all possible event ids
  uint8_t id = 0;
  do {
    data.payload.event_id = id;
    ASSERT_EQ((id == 0 || id > AVRC_NUM_NOTIF_EVENTS) ? AVRC_STS_BAD_PARAM : AVRC_STS_NO_ERROR,
              AVRC_Ctrl_ParsCommand(&msg, &result));
  } while (++id != 0);
}

TEST_F(StackAvrcpTest, BldResponse_GetCapabilityRsp_CompanyId) {
  tAVRC_RESPONSE resp = {
          .get_caps =
                  {
                          .pdu = AVRC_PDU_GET_CAPABILITIES,
                          .status = AVRC_STS_NO_ERROR,
                          .capability_id = AVRC_CAP_COMPANY_ID,
                          .count = 2,
                          .param =
                                  {
                                          .company_id =
                                                  {
                                                          0x112233,
                                                          0x445566,
                                                  },
                                  },
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  // pdu(1) + reserved(1) + len(2) + cap_id(1) + count(1) + 2*company_id(6) = 12
  uint8_t expected_data[] = {
          AVRC_PDU_GET_CAPABILITIES,
          0x00,  // reserved
          0x00,
          0x08,  // parameter length (8)
          AVRC_CAP_COMPANY_ID,
          0x02,  // count
          0x11,
          0x22,
          0x33,  // company_id[0] (0x112233)
          0x44,
          0x55,
          0x66  // company_id[1] (0x445566)
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetCapabilityRsp_EventsSupported) {
  tAVRC_RESPONSE resp = {
          .get_caps =
                  {
                          .pdu = AVRC_PDU_GET_CAPABILITIES,
                          .status = AVRC_STS_NO_ERROR,
                          .capability_id = AVRC_CAP_EVENTS_SUPPORTED,
                          .count = 2,
                          .param =
                                  {
                                          .event_id =
                                                  {
                                                          AVRC_EVT_PLAY_STATUS_CHANGE,
                                                          AVRC_EVT_TRACK_CHANGE,
                                                  },
                                  },
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  // pdu(1) + reserved(1) + len(2) + cap_id(1) + count(1) + 2*event_id(2) = 8
  uint8_t expected_data[] = {AVRC_PDU_GET_CAPABILITIES,
                             0x00,  // reserved
                             0x00,
                             0x04,  // parameter length (4)
                             AVRC_CAP_EVENTS_SUPPORTED,
                             0x02,  // count
                             AVRC_EVT_PLAY_STATUS_CHANGE,
                             AVRC_EVT_TRACK_CHANGE};
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetCapabilityRsp_InvalidCapabilityId) {
  tAVRC_RESPONSE resp = {
          .get_caps =
                  {
                          .pdu = AVRC_PDU_GET_CAPABILITIES,
                          .status = AVRC_STS_NO_ERROR,
                          .capability_id = 0xFF,  // Invalid capability ID
                          .count = 0,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_ListAppSettingsAttrRsp) {
  tAVRC_RESPONSE resp = {
          .list_app_attr =
                  {
                          .pdu = AVRC_PDU_LIST_PLAYER_APP_ATTR,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attr = 2,
                          .attrs = {AVRC_PLAYER_SETTING_REPEAT, AVRC_PLAYER_SETTING_SHUFFLE,
                                    0xFF /* invalid*/},
                  },
  };

  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  // pdu(1) + reserved(1) + len(2) + num_attr(1) + 2*attr_id(2) = 7
  uint8_t expected_data[] = {
          AVRC_PDU_LIST_PLAYER_APP_ATTR,
          0x00,  // reserved
          0x00,
          0x03,  // parameter length
          0x02,  // num_attr, should be 2 as one is invalid
          AVRC_PLAYER_SETTING_REPEAT,
          AVRC_PLAYER_SETTING_SHUFFLE,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_ListAppSettingsValuesRsp) {
  tAVRC_RESPONSE resp = {
          .list_app_values =
                  {
                          .pdu = AVRC_PDU_LIST_PLAYER_APP_VALUES,
                          .status = AVRC_STS_NO_ERROR,
                          .num_val = 2,
                          .vals = {AVRC_PLAYER_VAL_OFF, AVRC_PLAYER_VAL_ON},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  // pdu(1) + reserved(1) + len(2) + num_val(1) + 2*vals(2) = 7
  uint8_t expected_data[] = {
          AVRC_PDU_LIST_PLAYER_APP_VALUES,
          0x00,  // reserved
          0x00,
          0x03,  // parameter length
          0x02,  // num_val
          AVRC_PLAYER_VAL_OFF,
          AVRC_PLAYER_VAL_ON,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetCurAppSettingValueRsp_Null) {
  tAVRC_RESPONSE resp = {
          .get_cur_app_val =
                  {
                          .pdu = AVRC_PDU_GET_CUR_PLAYER_APP_VALUE,
                          .status = AVRC_STS_NO_ERROR,
                          .p_vals = NULL,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_GetCurAppSettingValueRsp) {
  tAVRC_APP_SETTING vals[] = {{AVRC_PLAYER_SETTING_REPEAT, AVRC_PLAYER_VAL_OFF},
                              {AVRC_PLAYER_SETTING_SHUFFLE, AVRC_PLAYER_VAL_ON}};
  tAVRC_RESPONSE resp = {
          .get_cur_app_val =
                  {
                          .pdu = AVRC_PDU_GET_CUR_PLAYER_APP_VALUE,
                          .status = AVRC_STS_NO_ERROR,
                          .num_val = 2,
                          .p_vals = vals,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  // pdu(1) + reserved(1) + len(2) + num_val(1) + 2*vals(4) = 9
  uint8_t expected_data[] = {
          AVRC_PDU_GET_CUR_PLAYER_APP_VALUE,
          0x00,  // reserved
          0x00,
          0x05,  // parameter length
          0x02,  // num_val
          AVRC_PLAYER_SETTING_REPEAT,
          AVRC_PLAYER_VAL_OFF,
          AVRC_PLAYER_SETTING_SHUFFLE,
          AVRC_PLAYER_VAL_ON,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_SetAppSettingValueRsp) {
  tAVRC_RESPONSE resp = {
          .set_app_val =
                  {
                          .pdu = AVRC_PDU_SET_PLAYER_APP_VALUE,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content: should be empty
  uint8_t expected_data[] = {
          AVRC_PDU_SET_PLAYER_APP_VALUE,
          0x00,  // reserved
          0x00,
          0x00,  // parameter length
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetPlayStatusRsp) {
  tAVRC_RESPONSE resp = {
          .get_play_status =
                  {
                          .pdu = AVRC_PDU_GET_PLAY_STATUS,
                          .status = AVRC_STS_NO_ERROR,
                          .song_len = 0x12345678,
                          .song_pos = 0x87654321,
                          .play_status = AVRC_PLAYSTATE_PLAYING,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  // pdu(1) + rsvd(1) + len(2) + song_len(4) + song_pos(4) + play_status(1) = 13
  uint8_t expected_data[] = {
          AVRC_PDU_GET_PLAY_STATUS,
          0x00,  // reserved
          0x00,
          0x09,  // parameter length
          0x12,
          0x34,
          0x56,
          0x78,  // song_len
          0x87,
          0x65,
          0x43,
          0x21,  // song_pos
          AVRC_PLAYSTATE_PLAYING,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_PlayStatusChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_PLAY_STATUS_CHANGE,
                          .param = {.play_status = AVRC_PLAYSTATE_PAUSED},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x02,  // parameter length
          AVRC_EVT_PLAY_STATUS_CHANGE,
          AVRC_PLAYSTATE_PAUSED,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_TrackChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_TRACK_CHANGE,
                          .param = {.track = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x09,  // parameter length
          AVRC_EVT_TRACK_CHANGE,
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08  // track
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_PlayPosChanged) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_PLAY_POS_CHANGED,
                          .param = {.play_pos = 0x12345678},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x05,  // parameter length
          AVRC_EVT_PLAY_POS_CHANGED,
          0x12,
          0x34,
          0x56,
          0x78  // play_pos
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_RejectedRsp) {
  tAVRC_RESPONSE resp = {
          .rsp =
                  {
                          .pdu = AVRC_PDU_GET_CAPABILITIES,
                          .status = AVRC_STS_BAD_PARAM,
                          .opcode = AVRC_OP_VENDOR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  uint8_t expected_data[] = {
          AVRC_PDU_GET_CAPABILITIES,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_STS_BAD_PARAM,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GroupNavigationRsp) {
  tAVRC_RESPONSE resp = {
          .rsp =
                  {
                          .pdu = AVRC_PDU_NEXT_GROUP,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // The packet for this is a simple Pass Through command.
  uint8_t expected_data[] = {
          0x00,
          0x00  // The PDU ID itself
  };

  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetItemAttributesRsp_Null) {
  tAVRC_RESPONSE resp = {
          .get_attrs =
                  {
                          .pdu = AVRC_PDU_GET_ITEM_ATTRIBUTES,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attrs = 0,
                          .p_attrs = NULL,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_GetItemAttributesRsp_Success) {
  tAVRC_ATTR_ENTRY attr_entry;
  attr_entry.attr_id = AVRC_MEDIA_ATTR_ID_TITLE;
  char title[] = "Test Title";
  attr_entry.name.charset_id = AVRC_CHARSET_ID_UTF8;
  attr_entry.name.str_len = strlen(title);
  attr_entry.name.p_str = (uint8_t*)title;

  tAVRC_RESPONSE resp = {
          .get_attrs =
                  {
                          .pdu = AVRC_PDU_GET_ITEM_ATTRIBUTES,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attrs = 1,
                          .p_attrs = &attr_entry,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Expected packet content:
  uint8_t expected_data[] = {
          AVRC_PDU_GET_ITEM_ATTRIBUTES,
          0x00,
          0x14,  // parameter length 20
          AVRC_STS_NO_ERROR,
          0x01,  // num_attrs
          0x00,
          0x00,
          0x00,
          0x01,  // attr_id AVRC_MEDIA_ATTR_ID_TITLE
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x00,
          0x0a,  // str_len
          'T',
          'e',
          's',
          't',
          ' ',
          'T',
          'i',
          't',
          'l',
          'e',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetItemAttributesRsp_Continuation) {
  BT_HDR* p_pkt = NULL;

  // First part of response
  tAVRC_ATTR_ENTRY attr_entry1;
  attr_entry1.attr_id = AVRC_MEDIA_ATTR_ID_TITLE;
  char title[] = "Test Title";
  attr_entry1.name.charset_id = AVRC_CHARSET_ID_UTF8;
  attr_entry1.name.str_len = strlen(title);
  attr_entry1.name.p_str = (uint8_t*)title;
  tAVRC_RESPONSE resp1 = {
          .get_attrs =
                  {
                          .pdu = AVRC_PDU_GET_ITEM_ATTRIBUTES,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attrs = 1,
                          .p_attrs = &attr_entry1,
                  },
  };

  tAVRC_STS status = AVRC_BldResponse(0, &resp1, &p_pkt);
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Second part of response (continuation)
  tAVRC_ATTR_ENTRY attr_entry2;
  attr_entry2.attr_id = AVRC_MEDIA_ATTR_ID_ARTIST;
  char artist[] = "Test Artist";
  attr_entry2.name.charset_id = AVRC_CHARSET_ID_UTF8;
  attr_entry2.name.str_len = strlen(artist);
  attr_entry2.name.p_str = (uint8_t*)artist;
  tAVRC_RESPONSE resp2 = {
          .get_attrs =
                  {
                          .pdu = AVRC_PDU_GET_ITEM_ATTRIBUTES,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attrs = 1,
                          .p_attrs = &attr_entry2,
                  },
  };

  status = AVRC_BldResponse(0, &resp2, &p_pkt);
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check final packet content
  uint8_t expected_data[] = {
          AVRC_PDU_GET_ITEM_ATTRIBUTES,
          0x00,
          0x27,  // parameter length 39
          AVRC_STS_NO_ERROR,
          0x02,  // num_attrs
          0x00,
          0x00,
          0x00,
          0x01,  // attr_id AVRC_MEDIA_ATTR_ID_TITLE
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x00,
          0x0a,  // str_len
          'T',
          'e',
          's',
          't',
          ' ',
          'T',
          'i',
          't',
          'l',
          'e',
          0x00,
          0x00,
          0x00,
          0x02,  // attr_id AVRC_MEDIA_ATTR_ID_ARTIST
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x00,
          0x0b,  // str_len
          'T',
          'e',
          's',
          't',
          ' ',
          'A',
          'r',
          't',
          'i',
          's',
          't',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetTotalNumberOfItemsRsp_Success) {
  tAVRC_RESPONSE resp = {
          .get_num_of_items =
                  {
                          .pdu = AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS,
                          .status = AVRC_STS_NO_ERROR,
                          .uid_counter = 0x1234,
                          .num_items = 0x56789abc,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  uint8_t expected_data[] = {
          AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS,
          0x00,
          0x07,  // parameter length
          AVRC_STS_NO_ERROR,
          0x12,
          0x34,  // uid_counter
          0x56,
          0x78,
          0x9a,
          0xbc,  // num_items
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_SearchRsp) {
  tAVRC_RESPONSE resp = {
          .search =
                  {
                          .pdu = AVRC_PDU_SEARCH,
                          .status = AVRC_STS_NO_ERROR,
                          .uid_counter = 0x4321,
                          .num_items = 0xcba98765,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  uint8_t expected_data[] = {
          AVRC_PDU_SEARCH,
          0x00,
          0x07,  // parameter length
          AVRC_STS_NO_ERROR,
          0x43,
          0x21,  // uid_counter
          0xcb,
          0xa9,
          0x87,
          0x65,  // num_items
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_SetBrowsedPlayerRsp) {
  tAVRC_NAME folder;
  char folder_name[] = "Test Folder";
  folder.str_len = strlen(folder_name);
  folder.p_str = (uint8_t*)folder_name;
  tAVRC_RESPONSE resp = {
          .br_player =
                  {
                          .pdu = AVRC_PDU_SET_BROWSED_PLAYER,
                          .status = AVRC_STS_NO_ERROR,
                          .uid_counter = 0x1234,
                          .num_items = 0x56789abc,
                          .charset_id = AVRC_CHARSET_ID_UTF8,
                          .folder_depth = 1,
                          .p_folders = &folder,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + len(2) + status(1) + uid_counter(2) + num_items(4) +
  // charset_id(2) + folder_depth(1) + folder_len(2) + folder_name(11) = 26
  uint8_t expected_data[] = {
          AVRC_PDU_SET_BROWSED_PLAYER,
          0x00,
          0x17,  // parameter length 23
          AVRC_STS_NO_ERROR,
          0x12,
          0x34,  // uid_counter
          0x56,
          0x78,
          0x9a,
          0xbc,  // num_items
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x01,  // folder_depth
          0x00,
          0x0b,  // folder_len
          'T',
          'e',
          's',
          't',
          ' ',
          'F',
          'o',
          'l',
          'd',
          'e',
          'r',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetFolderItemsRsp_PlayerItem) {
  tAVRC_ITEM item = {
          .item_type = AVRC_ITEM_PLAYER,
          .u.player =
                  {
                          .player_id = 0x0001,
                          .major_type = AVRC_MJ_TYPE_AUDIO,
                          .sub_type = AVRC_SUB_TYPE_AUDIO_BOOK,
                          .play_status = AVRC_PLAYSTATE_PLAYING,
                          .features = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
                                       0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10},
                          .name =
                                  {
                                          .charset_id = AVRC_CHARSET_ID_UTF8,
                                          .str_len = sizeof("Test Player") - 1,
                                          .p_str = (uint8_t*)"Test Player",
                                  },
                  },
  };

  tAVRC_RESPONSE resp = {
          .get_items =
                  {
                          .pdu = AVRC_PDU_GET_FOLDER_ITEMS,
                          .status = AVRC_STS_NO_ERROR,
                          .uid_counter = 0x1234,
                          .item_count = 1,
                          .p_item_list = &item,
                  },
  };
  BT_HDR* p_pkt = NULL;
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  // pdu(1) + len(2) + status(1) + uid_counter(2) + item_count(2) +
  // item_type(1) + item_len(2) + player_id(2) + major_type(1) + sub_type(4) +
  // play_status(1) + features(16) + charset_id(2) + name_len(2) + name(11) = 50
  // Using a string literal here ensures the size is correct and handles the null terminator.
  const uint8_t expected_data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,  // PDU ID
          0x00,
          0x2f,               // len (42 bytes)
          AVRC_STS_NO_ERROR,  // status
          0x12,
          0x34,  // uid_counter
          0x00,
          0x01,              // item_count
          AVRC_ITEM_PLAYER,  // item_type
          0x00,
          0x27,  // item_len (39 bytes)
          0x00,
          0x01,                // player_id
          AVRC_MJ_TYPE_AUDIO,  // major_type
          0x00,
          0x00,
          0x00,
          0x01,                    // sub_type
          AVRC_PLAYSTATE_PLAYING,  // play_status
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,
          0x09,
          0x0a,
          0x0b,
          0x0c,
          0x0d,
          0x0e,
          0x0f,
          0x10,  // features
          0x00,
          0x6a,  // charset_id
          0x00,
          0x0b,  // str_len
          'T',
          'e',
          's',
          't',
          ' ',
          'P',
          'l',
          'a',
          'y',
          'e',
          'r'  // player_name
  };
  // Replace the original test code with a single EXPECT_EQ check
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));  // -1 for the PDU ID
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  EXPECT_EQ(p_pkt->len, 50);

  osi_free(p_pkt);
}
TEST_F(StackAvrcpTest, BldResponse_GetFolderItemsRsp_FolderItem) {
  tAVRC_ITEM item = {
          .item_type = AVRC_ITEM_FOLDER,
          .u.folder = {.uid = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08},
                       .type = AVRC_FOLDER_TYPE_ALNUMS,
                       .playable = 1,
                       .name = {.charset_id = AVRC_CHARSET_ID_UTF8,
                                .str_len = sizeof("Test Folder") - 1,
                                .p_str = (uint8_t*)"Test Folder"}},
  };

  tAVRC_RESPONSE resp = {
          .get_items =
                  {
                          .pdu = AVRC_PDU_GET_FOLDER_ITEMS,
                          .status = AVRC_STS_NO_ERROR,
                          .uid_counter = 0x1234,
                          .item_count = 1,
                          .p_item_list = &item,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + len(2) + status(1) + uid_counter(2) + item_count(2) +
  // item_type(1) + item_len(2) + uid(8) + type(1) + playable(1) +
  // charset_id(2) + name_len(2) + name(11) = 36
  uint8_t expected_data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,
          0x00,
          0x21,  // parameter length 33
          AVRC_STS_NO_ERROR,
          0x12,
          0x34,  // uid_counter
          0x00,
          0x01,  // item_count
          AVRC_ITEM_FOLDER,
          0x00,
          0x19,  // item_len 25
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,  // uid
          AVRC_FOLDER_TYPE_ALNUMS,
          0x01,  // playable
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x00,
          0x0b,  // name_len
          'T',
          'e',
          's',
          't',
          ' ',
          'F',
          'o',
          'l',
          'd',
          'e',
          'r',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetFolderItemsRsp_MediaItem) {
  tAVRC_ATTR_ENTRY attr = {
          .attr_id = AVRC_MEDIA_ATTR_ID_TITLE,
          .name = {.charset_id = AVRC_CHARSET_ID_UTF8,
                   .str_len = sizeof("Test Title") - 1,
                   .p_str = (uint8_t*)"Test Title"},
  };

  tAVRC_ITEM item = {
          .item_type = AVRC_ITEM_MEDIA,
          .u.media =
                  {
                          .uid = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08},
                          .type = AVRC_MEDIA_TYPE_AUDIO,
                          .name =
                                  {
                                          .charset_id = AVRC_CHARSET_ID_UTF8,
                                          .str_len = sizeof("Test Song") - 1,
                                          .p_str = (uint8_t*)"Test Song",
                                  },
                          .attr_count = 1,
                          .p_attr_list = &attr,
                  },
  };

  tAVRC_RESPONSE resp = {
          .get_items =
                  {
                          .pdu = AVRC_PDU_GET_FOLDER_ITEMS,
                          .status = AVRC_STS_NO_ERROR,
                          .uid_counter = 0x1234,
                          .item_count = 1,
                          .p_item_list = &item,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + len(2) + status(1) + uid_counter(2) + item_count(2) +
  // item_type(1) + item_len(2) + uid(8) + type(1) + charset_id(2) +
  // name_len(2) + name(9) + attr_count(1) = 34
  uint8_t expected_data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,
          0x00,
          0x1f,  // parameter length 31
          AVRC_STS_NO_ERROR,
          0x12,
          0x34,  // uid_counter
          0x00,
          0x01,  // item_count
          AVRC_ITEM_MEDIA,
          0x00,
          0x17,  // item_len 23
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,  // uid
          AVRC_MEDIA_TYPE_AUDIO,
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x00,
          0x09,  // name_len
          'T',
          'e',
          's',
          't',
          ' ',
          'S',
          'o',
          'n',
          'g',
          0x00,  // attr_count
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_ChangePathRsp) {
  tAVRC_RESPONSE resp = {
          .chg_path =
                  {
                          .pdu = AVRC_PDU_CHANGE_PATH,
                          .status = AVRC_STS_NO_ERROR,
                          .num_items = 0x12345678,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + len(2) + status(1) + num_items(4) = 8
  uint8_t expected_data[] = {
          AVRC_PDU_CHANGE_PATH, 0x00, 0x05,              // parameter length
          AVRC_STS_NO_ERROR,    0x12, 0x34, 0x56, 0x78,  // num_items
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_PlayItemRsp) {
  tAVRC_RESPONSE resp = {
          .play_item =
                  {
                          .pdu = AVRC_PDU_PLAY_ITEM,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + status(1) = 5
  uint8_t expected_data[] = {
          AVRC_PDU_PLAY_ITEM,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_STS_NO_ERROR,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_SetAbsoluteVolumeRsp) {
  tAVRC_RESPONSE resp = {
          .volume =
                  {
                          .pdu = AVRC_PDU_SET_ABSOLUTE_VOLUME,
                          .status = AVRC_STS_NO_ERROR,
                          .volume = 0x5A,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + volume(1) = 5
  uint8_t expected_data[] = {
          AVRC_PDU_SET_ABSOLUTE_VOLUME,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          0x5A,  // volume
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_AddToNowPlayingRsp) {
  tAVRC_RESPONSE resp = {
          .add_to_play =
                  {
                          .pdu = AVRC_PDU_ADD_TO_NOW_PLAYING,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + status(1) = 5
  uint8_t expected_data[] = {
          AVRC_PDU_ADD_TO_NOW_PLAYING,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_STS_NO_ERROR,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_RequestContinuationRsp) {
  // Arrange
  tAVRC_RESPONSE resp = {
          .continu =
                  {
                          .pdu = AVRC_PDU_REQUEST_CONTINUATION_RSP,
                          .status = AVRC_STS_NO_ERROR,
                          .target_pdu = 0x04,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  uint8_t expected_data[] = {
          AVRC_PDU_REQUEST_CONTINUATION_RSP,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          0x04,  // target_pdu
  };

  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_AbortContinuationRsp) {
  // Arrange
  tAVRC_RESPONSE resp = {
          .abort =
                  {
                          .pdu = AVRC_PDU_ABORT_CONTINUATION_RSP,
                          .status = AVRC_STS_NO_ERROR,
                          .target_pdu = 0x04,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  uint8_t expected_data[] = {
          AVRC_PDU_ABORT_CONTINUATION_RSP,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          0x04,  // target_pdu
  };

  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_SetAddressedPlayerRsp) {
  // Arrange
  tAVRC_RESPONSE resp = {
          .addr_player =
                  {
                          .pdu = AVRC_PDU_SET_ADDRESSED_PLAYER,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + status(1) = 5
  uint8_t expected_data[] = {
          AVRC_PDU_SET_ADDRESSED_PLAYER,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_STS_NO_ERROR,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetPlayerAppAttrTextRsp) {
  // Arrange
  tAVRC_APP_SETTING_TEXT attr_text = {
          .attr_id = AVRC_PLAYER_SETTING_REPEAT,
          .charset_id = AVRC_CHARSET_ID_UTF8,
          .str_len = sizeof("Repeat Mode") - 1,  // `sizeof` is a compile-time constant
          .p_str = (uint8_t*)"Repeat Mode",
  };
  tAVRC_RESPONSE resp = {
          .get_app_attr_txt =
                  {
                          .pdu = AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attr = 1,
                          .p_attrs = &attr_text,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + num_attr(1) + attr_id(1) + charset(2) +
  // strlen(1) + str(11) = 20
  uint8_t expected_data[] = {
          AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT,
          0x00,  // reserved
          0x00,
          0x10,  // parameter length 16
          0x01,  // num_attr
          AVRC_PLAYER_SETTING_REPEAT,
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x0b,  // str_len
          'R',
          'e',
          'p',
          'e',
          'a',
          't',
          ' ',
          'M',
          'o',
          'd',
          'e',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetPlayerAppValueTextRsp) {
  // Arrange
  tAVRC_APP_SETTING_TEXT val_text = {
          .attr_id = AVRC_PLAYER_VAL_ON,
          .charset_id = AVRC_CHARSET_ID_UTF8,
          .str_len = sizeof("On") - 1,  // `sizeof` is a compile-time constant
          .p_str = (uint8_t*)"On",
  };

  tAVRC_RESPONSE resp = {
          .get_app_val_txt =
                  {
                          .pdu = AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attr = 1,
                          .p_attrs = &val_text,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + num_attr(1) + val_id(1) + charset(2) +
  // strlen(1) + str(2) = 11
  uint8_t expected_data[] = {
          AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT,
          0x00,  // reserved
          0x00,
          0x07,  // parameter length 7
          0x01,  // num_attr
          AVRC_PLAYER_VAL_ON,
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x02,  // str_len
          'O',
          'n',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_InformDisplayCharsetRsp) {
  // Arrange
  tAVRC_RESPONSE resp = {
          .inform_charset =
                  {
                          .pdu = AVRC_PDU_INFORM_DISPLAY_CHARSET,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content - should be empty
  uint8_t expected_data[] = {
          AVRC_PDU_INFORM_DISPLAY_CHARSET,
          0x00,  // reserved
          0x00,
          0x00,  // parameter length
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_InformBatteryStatusOfCtRsp) {
  // Arrange
  tAVRC_RESPONSE resp = {
          .inform_battery_status =
                  {
                          .pdu = AVRC_PDU_INFORM_BATTERY_STAT_OF_CT,
                          .status = AVRC_STS_NO_ERROR,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content - should be empty
  uint8_t expected_data[] = {
          AVRC_PDU_INFORM_BATTERY_STAT_OF_CT,
          0x00,  // reserved
          0x00,
          0x00,  // parameter length
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_GetElementAttrRsp) {
  // Arrange
  tAVRC_ATTR_ENTRY attr_entry = {
          .attr_id = AVRC_MEDIA_ATTR_ID_TITLE,
          .name =
                  {
                          .charset_id = AVRC_CHARSET_ID_UTF8,
                          .str_len = sizeof("A Great Song") - 1,
                          .p_str = (uint8_t*)"A Great Song",
                  },
  };
  tAVRC_RESPONSE resp = {
          .get_attrs =
                  {
                          .pdu = AVRC_PDU_GET_ELEMENT_ATTR,
                          .status = AVRC_STS_NO_ERROR,
                          .num_attrs = 1,
                          .p_attrs = &attr_entry,
                  },
  };
  BT_HDR* p_pkt = NULL;

  // Act
  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  // Assert
  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // Check packet content
  // pdu(1) + rsvd(1) + len(2) + num_attrs(1) + attr_id(4) + charset(2) +
  // strlen(2) + str(12) = 25
  uint8_t expected_data[] = {
          AVRC_PDU_GET_ELEMENT_ATTR,
          0x00,  // reserved
          0x00,
          0x15,  // parameter length 21
          0x01,  // num_attrs
          0x00,
          0x00,
          0x00,
          0x01,  // attr_id AVRC_MEDIA_ATTR_ID_TITLE
          0x00,
          0x6a,  // charset_id AVRC_CHARSET_ID_UTF8
          0x00,
          0x0c,  // str_len
          'A',
          ' ',
          'G',
          'r',
          'e',
          'a',
          't',
          ' ',
          'S',
          'o',
          'n',
          'g',
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_TrackReachedEnd) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_TRACK_REACHED_END,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // pdu(1) + rsvd(1) + len(2) + event_id(1) = 5
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_EVT_TRACK_REACHED_END,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_TrackReachedStart) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_TRACK_REACHED_START,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_EVT_TRACK_REACHED_START,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_NowPlayingChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_NOW_PLAYING_CHANGE,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_EVT_NOW_PLAYING_CHANGE,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_AvailablePlayersChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_AVAL_PLAYERS_CHANGE,
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_EVT_AVAL_PLAYERS_CHANGE,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_BatteryStatusChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_BATTERY_STATUS_CHANGE,
                          .param = {.battery_status = AVRC_BATTERY_STATUS_EXTERNAL},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // pdu(1) + rsvd(1) + len(2) + event_id(1) + status(1) = 6
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x02,  // parameter length
          AVRC_EVT_BATTERY_STATUS_CHANGE,
          AVRC_BATTERY_STATUS_EXTERNAL,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_BatteryStatusChange_Invalid) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_BATTERY_STATUS_CHANGE,
                          .param = {.battery_status = 0xFF},  // Invalid status
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_SystemStatusChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_SYSTEM_STATUS_CHANGE,
                          .param = {.system_status = AVRC_SYSTEMSTATE_PWR_UNPLUGGED},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x02,  // parameter length
          AVRC_EVT_SYSTEM_STATUS_CHANGE,
          AVRC_SYSTEMSTATE_PWR_UNPLUGGED,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_SystemStatusChange_Invalid) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_SYSTEM_STATUS_CHANGE,
                          .param = {.system_status = 0xFF},  // Invalid status
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_AppSettingChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_APP_SETTING_CHANGE,
                          .param =
                                  {
                                          .player_setting =
                                                  {
                                                          .num_attr = 2,
                                                          .attr_id = {AVRC_PLAYER_SETTING_REPEAT,
                                                                      AVRC_PLAYER_SETTING_SHUFFLE},
                                                          .attr_value =
                                                                  {AVRC_PLAYER_VAL_ON,
                                                                   AVRC_PLAYER_VAL_ALL_SHUFFLE},
                                                  },
                                  },
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // pdu(1) + rsvd(1) + len(2) + event_id(1) + num_attr(1) + 2*attr(4) = 10
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x06,  // parameter length
          AVRC_EVT_APP_SETTING_CHANGE,
          0x02,  // num_attr
          AVRC_PLAYER_SETTING_REPEAT,
          AVRC_PLAYER_VAL_ON,
          AVRC_PLAYER_SETTING_SHUFFLE,
          AVRC_PLAYER_VAL_ALL_SHUFFLE,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_AppSettingChange_InvalidAttr) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_APP_SETTING_CHANGE,
                          .param =
                                  {
                                          .player_setting =
                                                  {
                                                          .num_attr = 1,
                                                          .attr_id = {0x00},
                                                          .attr_value = {0x00},
                                                  },
                                  },
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_AppSettingChange_NoAttrs) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_APP_SETTING_CHANGE,
                          .param = {.player_setting = {.num_attr = 0}},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_VolumeChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_VOLUME_CHANGE,
                          .param = {.volume = 0x5A},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x02,  // parameter length
          AVRC_EVT_VOLUME_CHANGE,
          0x5A & AVRC_MAX_VOLUME,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_AddressedPlayerChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_ADDR_PLAYER_CHANGE,
                          .param =
                                  {
                                          .addr_player =
                                                  {
                                                          .player_id = 0x1234,
                                                          .uid_counter = 0x5678,
                                                  },
                                  },
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // pdu(1) + rsvd(1) + len(2) + event_id(1) + player_id(2) + uid_counter(2) = 9
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x05,  // parameter length
          AVRC_EVT_ADDR_PLAYER_CHANGE,
          0x12,
          0x34,  // player_id
          0x56,
          0x78,  // uid_counter
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_UidsChange) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = AVRC_EVT_UIDS_CHANGE,
                          .param = {.uid_counter = 0xABCD},
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_NO_ERROR);
  ASSERT_NE(p_pkt, nullptr);

  // pdu(1) + rsvd(1) + len(2) + event_id(1) + uid_counter(2) = 7
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x03,  // parameter length
          AVRC_EVT_UIDS_CHANGE,
          0xAB,
          0xCD,  // uid_counter
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);

  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldResponse_NotifyRsp_UnknownEvent) {
  tAVRC_RESPONSE resp = {
          .reg_notif =
                  {
                          .pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                          .status = AVRC_STS_NO_ERROR,
                          .event_id = 0xFF,  // Unknown event
                  },
  };
  BT_HDR* p_pkt = NULL;

  tAVRC_STS status = AVRC_BldResponse(0, &resp, &p_pkt);

  ASSERT_EQ(status, AVRC_STS_BAD_PARAM);
  ASSERT_EQ(p_pkt, nullptr);
}

TEST_F(StackAvrcpTest, BldSetAddressedPlayerCmd) {
  tAVRC_COMMAND cmd = {.addr_player = {.pdu = AVRC_PDU_SET_ADDRESSED_PLAYER,
                                       .status = AVRC_STS_NO_ERROR,
                                       .player_id = 0x5678}};
  BT_HDR* p_pkt = NULL;
  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + player_id(2)
  uint8_t expected_data[] = {
          AVRC_PDU_SET_ADDRESSED_PLAYER,
          0x00,  // reserved
          0x00,
          0x02,  // parameter length
          0x56,
          0x78,  // player_id
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldSetBrowsedPlayerCmd) {
  tAVRC_COMMAND cmd = {.br_player = {.pdu = AVRC_PDU_SET_BROWSED_PLAYER,
                                     .status = AVRC_STS_NO_ERROR,
                                     .player_id = 0x1234}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + len(2) + player_id(2)
  uint8_t expected_data[] = {
          AVRC_PDU_SET_BROWSED_PLAYER,
          0x00,
          0x02,  // parameter length
          0x12,
          0x34,  // player_id
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetItemAttributesCmd) {
  uint32_t attrs[] = {0x00000001, 0x00000002, 0x00000003, 0x00000004,
                      0x00000005, 0x00000006, 0x00000007, 0x00000008};
  tAVRC_COMMAND cmd = {.get_attrs = {.pdu = AVRC_PDU_GET_ITEM_ATTRIBUTES,
                                     .status = AVRC_STS_NO_ERROR,
                                     .scope = 0x01,
                                     .uid = {1, 2, 3, 4, 5, 6, 7, 8},
                                     .uid_counter = 0x1234,
                                     .attr_count = 2,
                                     .p_attr_list = attrs}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + len(2) + scope(1) + uid(8) + uid_counter(2) + attr_count(1) +
  // attrs(8)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_ITEM_ATTRIBUTES,  // PDU ID (0x73)
          0x00,
          0x14,  // Parameter Length (20 bytes)
          0x01,  // Scope (0x01)
          8,
          7,
          6,
          5,
          4,
          3,
          2,
          1,  // UID (8 bytes)
          0x12,
          0x34,  // UID Counter (0x1234)
          0x02,  // Number of Attributes (2)
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,
          // Attribute 2 ID (2, Big-Endian)
  };
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldChangeFolderCmd) {
  tAVRC_COMMAND cmd = {.chg_path = {.pdu = AVRC_PDU_CHANGE_PATH,
                                    .status = AVRC_STS_NO_ERROR,
                                    .uid_counter = 0x1234,
                                    .direction = 0x01,
                                    .folder_uid = {1, 2, 3, 4, 5, 6, 7, 8}}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + len(2) + uid_counter(2) + direction(1) + uid(8)
  uint8_t expected_data[] = {
          AVRC_PDU_CHANGE_PATH,
          0x00,
          0x0b,  // parameter length
          0x12,
          0x34,  // uid_counter
          0x01,  // direction
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,  // uid
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetFolderItemsCmd) {
  tAVRC_COMMAND cmd = {.get_items = {.pdu = AVRC_PDU_GET_FOLDER_ITEMS,
                                     .status = AVRC_STS_NO_ERROR,
                                     .scope = 0x01,
                                     .start_item = 10,
                                     .end_item = 20}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + len(2) + scope(1) + start(4) + end(4) + attr_count(1)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,
          0x00,
          0x0a,  // parameter length
          0x01,  // scope
          0x00,
          0x00,
          0x00,
          0x0a,  // start_item
          0x00,
          0x00,
          0x00,
          0x14,  // end_item
          0x00,  // attr_count
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetPlayStatusCmd) {
  tAVRC_COMMAND cmd = {.pdu = AVRC_PDU_GET_PLAY_STATUS};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_PLAY_STATUS,
          0x00,  // reserved
          0x00,
          0x00,  // parameter length
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldPlayItemCmd) {
  tAVRC_COMMAND cmd = {.play_item = {.pdu = AVRC_PDU_PLAY_ITEM,
                                     .scope = 0x01,
                                     .uid = {1, 2, 3, 4, 5, 6, 7, 8},
                                     .uid_counter = 0x1234}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + scope(1) + uid(8) + uid_counter(2)
  uint8_t expected_data[] = {
          AVRC_PDU_PLAY_ITEM,
          0x00,  // reserved
          0x00,
          0x0b,  // parameter length
          0x01,  // scope
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,  // uid
          0x12,
          0x34,  // uid_counter
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetElementAttrCmd) {
  tAVRC_COMMAND cmd = {.get_elem_attrs = {.pdu = AVRC_PDU_GET_ELEMENT_ATTR,
                                          .num_attr = 2,
                                          .attrs = {0x00000001, 0x00000002}}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + identifier(8) + num_attr(1) + attrs(8)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_ELEMENT_ATTR,
          0x00,  // reserved
          0x00,
          0x11,  // parameter length
          0x00,
          0x00,
          0x00,
          0x00,  // identifier high
          0x00,
          0x00,
          0x00,
          0x00,  // identifier low
          0x02,  // num_attr
          0x00,
          0x00,
          0x00,
          0x01,  // attr1
          0x00,
          0x00,
          0x00,
          0x02,  // attr2
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetPlayerAppSettingValueTextCmd) {
  tAVRC_COMMAND cmd = {.get_app_val_txt = {.pdu = AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT,
                                           .num_val = 2,
                                           .vals = {0x0A, 0x0B}}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + num_val(1) + vals(2)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT,
          0x00,  // reserved
          0x00,
          0x03,  // parameter length
          0x02,  // num_val
          0x0A,
          0x0B,  // vals
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetPlayerAppSettingAttrTextCmd) {
  tAVRC_COMMAND cmd = {.get_app_attr_txt = {.pdu = AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT,
                                            .num_attr = 2,
                                            .attrs = {0x01, 0x02}}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + num_attr(1) + attrs(2)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT,
          0x00,  // reserved
          0x00,
          0x03,  // parameter length
          0x02,  // num_attr
          0x01,
          0x02,  // attrs
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldSetCurrentPlayerAppValuesCmd) {
  tAVRC_APP_SETTING vals[] = {{0x01, 0x0A}, {0x02, 0x0B}};
  tAVRC_COMMAND cmd = {
          .set_app_val = {.pdu = AVRC_PDU_SET_PLAYER_APP_VALUE, .num_val = 2, .p_vals = vals}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + num_val(1) + vals(4)
  uint8_t expected_data[] = {
          AVRC_PDU_SET_PLAYER_APP_VALUE,
          0x00,  // reserved
          0x00,
          0x05,  // parameter length
          0x02,  // num_val
          0x01,
          0x0A,  // val1
          0x02,
          0x0B,  // val2
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetCurrentPlayerAppValuesCmd) {
  tAVRC_COMMAND cmd = {.get_cur_app_val = {.pdu = AVRC_PDU_GET_CUR_PLAYER_APP_VALUE,
                                           .num_attr = 2,
                                           .attrs = {0x01, 0x02}}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + num_attr(1) + attrs(2)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_CUR_PLAYER_APP_VALUE,
          0x00,  // reserved
          0x00,
          0x03,  // parameter length
          0x02,  // num_attr
          0x01,
          0x02,  // attrs
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldListPlayerAppValuesCmd) {
  tAVRC_COMMAND cmd = {
          .list_app_values = {.pdu = AVRC_PDU_LIST_PLAYER_APP_VALUES, .attr_id = 0x01}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + attr_id(1)
  uint8_t expected_data[] = {
          AVRC_PDU_LIST_PLAYER_APP_VALUES,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          0x01,  // attr_id
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldListPlayerAppAttrCmd) {
  tAVRC_COMMAND cmd = {.pdu = AVRC_PDU_LIST_PLAYER_APP_ATTR};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2)
  uint8_t expected_data[] = {
          AVRC_PDU_LIST_PLAYER_APP_ATTR,
          0x00,  // reserved
          0x00,
          0x00,  // parameter length
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldGetCapabilityCmd) {
  tAVRC_COMMAND cmd = {
          .get_caps = {.pdu = AVRC_PDU_GET_CAPABILITIES, .capability_id = AVRC_CAP_COMPANY_ID}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + cap_id(1)
  uint8_t expected_data[] = {
          AVRC_PDU_GET_CAPABILITIES,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          AVRC_CAP_COMPANY_ID,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldRegisterNotifn) {
  tAVRC_COMMAND cmd = {.reg_notif = {.pdu = AVRC_PDU_REGISTER_NOTIFICATION,
                                     .event_id = AVRC_EVT_PLAY_STATUS_CHANGE,
                                     .param = 0}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + event_id(1) + param(4)
  uint8_t expected_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x05,  // parameter length
          AVRC_EVT_PLAY_STATUS_CHANGE,
          0x00,
          0x00,
          0x00,
          0x00,  // param
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldSetAbsVolumeCmd) {
  tAVRC_COMMAND cmd = {.volume = {.pdu = AVRC_PDU_SET_ABSOLUTE_VOLUME, .volume = 0x5A}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + volume(1)
  uint8_t expected_data[] = {
          AVRC_PDU_SET_ABSOLUTE_VOLUME,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          0x5A & AVRC_MAX_VOLUME,
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, BldNextCmd) {
  tAVRC_COMMAND cmd = {.continu = {.pdu = AVRC_PDU_REQUEST_CONTINUATION_RSP, .target_pdu = 0x42}};
  BT_HDR* p_pkt = NULL;

  AVRC_BldCommand(&cmd, &p_pkt);

  ASSERT_NE(p_pkt, nullptr);
  // pdu(1) + rsvd(1) + len(2) + target_pdu(1)
  uint8_t expected_data[] = {
          AVRC_PDU_REQUEST_CONTINUATION_RSP,
          0x00,  // reserved
          0x00,
          0x01,  // parameter length
          0x42,  // target_pdu
  };
  EXPECT_EQ(p_pkt->len, sizeof(expected_data));
  uint8_t* p_data = (uint8_t*)(p_pkt + 1) + p_pkt->offset;
  EXPECT_EQ(memcmp(p_data, expected_data, sizeof(expected_data)), 0);
  osi_free(p_pkt);
}

TEST_F(StackAvrcpTest, PassCmd_NullMsg) {
  uint8_t handle = 0;
  uint8_t label = 0;
  tAVRC_MSG_PASS* p_msg = NULL;
  uint16_t result = AVRC_PassCmd(handle, label, p_msg);
  EXPECT_EQ(result, AVRC_BAD_PARAM);
}

TEST_F(StackAvrcpTest, PassCmd_BadHandle) {
  uint8_t handle = 0;
  uint8_t label = 0;
  tAVRC_MSG_PASS msg = {
          .hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_PASS_THRU},
          .op_id = AVRC_ID_PLAY,
          .state = AVRC_STATE_PRESS,
          .p_pass_data = NULL,
          .pass_len = 0,
  };

  uint16_t result = AVRC_PassCmd(handle, label, &msg);
  EXPECT_EQ(result, 0x02 /*AVCT_BAD_HANDLE*/);
}

TEST_F(StackAvrcpTest, PassRsp_NullMsg) {
  uint8_t handle = 0;
  uint8_t label = 0;
  tAVRC_MSG_PASS* p_msg = NULL;
  uint16_t result = AVRC_PassRsp(handle, label, p_msg);
  EXPECT_EQ(result, AVRC_BAD_PARAM);
}

TEST_F(StackAvrcpTest, PassRsp_BadHandle) {
  uint8_t handle = 0;
  uint8_t label = 0;
  tAVRC_MSG_PASS msg = {
          .hdr = {.ctype = AVRC_RSP_ACCEPT, .opcode = AVRC_OP_PASS_THRU},
          .op_id = AVRC_ID_PLAY,
          .state = AVRC_STATE_PRESS,
          .p_pass_data = NULL,
          .pass_len = 0,
  };
  // This will fail because the handle is not open.
  uint16_t result = AVRC_PassRsp(handle, label, &msg);
  EXPECT_EQ(result, 0x02 /*AVCT_BAD_HANDLE*/);
}

TEST_F(StackAvrcpTest, MsgReq_NullPacket) {
  uint8_t handle = 0;
  uint8_t label = 0;
  uint8_t ctype = AVRC_CMD_CTRL;
  BT_HDR* p_pkt = NULL;
  bool is_new_avrcp = false;
  uint16_t result = AVRC_MsgReq(handle, label, ctype, p_pkt, is_new_avrcp);
  EXPECT_EQ(result, AVRC_BAD_PARAM);
}

TEST_F(StackAvrcpTest, MsgReq_BadHandle) {
  uint8_t handle = 0;
  uint8_t label = 0;
  uint8_t ctype = AVRC_CMD_CTRL;
  bool is_new_avrcp = false;
  BT_HDR* p_pkt = (BT_HDR*)osi_calloc(AVRC_CMD_BUF_SIZE);
  p_pkt->offset = AVCT_MSG_OFFSET;
  p_pkt->layer_specific = AVCT_DATA_CTRL;
  p_pkt->event = AVRC_OP_VENDOR;
  // This will fail because the handle is not open.
  // AVRC_MsgReq will call AVCT_MsgReq, which will return AVCT_BAD_HANDLE
  // and free p_pkt.
  uint16_t result = AVRC_MsgReq(handle, label, ctype, p_pkt, is_new_avrcp);
  EXPECT_EQ(result, 0x02 /*AVCT_BAD_HANDLE*/);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_null_message) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(nullptr, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_null_result) {
  tAVRC_MSG msg{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, nullptr, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_UnknownOpcode) {
  tAVRC_MSG msg{};
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  msg.hdr.opcode = 0xFF;  // Unknown opcode
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_short_message) {
  tAVRC_MSG msg{
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .vendor_len = 3},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_invalid_ctype) {
  uint8_t vendor_data[] = {AVRC_PDU_GET_PLAY_STATUS, 0, 0, 0};
  tAVRC_MSG msg{
          .vendor = {.hdr = {.ctype = AVRC_CMD_NOTIF, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 4},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_get_caps) {
  uint8_t vendor_data[] = {AVRC_PDU_GET_CAPABILITIES, 0, 0, 1, AVRC_CAP_COMPANY_ID};
  tAVRC_MSG msg{
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 5},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_CAPABILITIES);
  EXPECT_EQ(result.get_caps.capability_id, AVRC_CAP_COMPANY_ID);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_list_app_attr) {
  uint8_t vendor_data[] = {AVRC_PDU_LIST_PLAYER_APP_ATTR, 0, 0, 0};
  tAVRC_MSG msg{
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 4},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_LIST_PLAYER_APP_ATTR);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_list_app_values) {
  uint8_t vendor_data[] = {AVRC_PDU_LIST_PLAYER_APP_VALUES, 0, 0, 1, AVRC_PLAYER_SETTING_REPEAT};
  tAVRC_MSG msg{
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 5},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_LIST_PLAYER_APP_VALUES);
  EXPECT_EQ(result.list_app_values.attr_id, AVRC_PLAYER_SETTING_REPEAT);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_get_cur_app_value) {
  uint8_t vendor_data[] = {AVRC_PDU_GET_CUR_PLAYER_APP_VALUE  // PDU
                           ,
                           0,  // reserved
                           0,
                           2,  // len
                           1,  // num_attr
                           AVRC_PLAYER_SETTING_REPEAT};
  tAVRC_MSG msg{
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 6},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_CUR_PLAYER_APP_VALUE);
  EXPECT_EQ(result.get_cur_app_val.num_attr, 1);
  EXPECT_EQ(result.get_cur_app_val.attrs[0], AVRC_PLAYER_SETTING_REPEAT);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_set_app_value) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {AVRC_PDU_SET_PLAYER_APP_VALUE,
                           0,
                           0,
                           3,
                           1,
                           AVRC_PLAYER_SETTING_REPEAT,
                           AVRC_PLAYER_VAL_ON};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 7},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_PLAYER_APP_VALUE);
  EXPECT_EQ(result.set_app_val.num_val, 1);
  EXPECT_EQ(result.set_app_val.p_vals[0].attr_id, AVRC_PLAYER_SETTING_REPEAT);
  EXPECT_EQ(result.set_app_val.p_vals[0].attr_val, AVRC_PLAYER_VAL_ON);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_get_element_attr) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {AVRC_PDU_GET_ELEMENT_ATTR, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0,
                           AVRC_MEDIA_ATTR_ID_TITLE};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 17},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_ELEMENT_ATTR);
  EXPECT_EQ(result.get_elem_attrs.num_attr, 1);
  EXPECT_EQ(result.get_elem_attrs.attrs[0], static_cast<uint32_t>(AVRC_MEDIA_ATTR_ID_TITLE));
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_get_play_status) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {AVRC_PDU_GET_PLAY_STATUS, 0, 0, 0};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 4},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_PLAY_STATUS);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_register_notification) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION, 0, 0, 5, AVRC_EVT_PLAY_STATUS_CHANGE, 0, 0, 0, 0};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_NOTIF, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 9},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_REGISTER_NOTIFICATION);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_PLAY_STATUS_CHANGE);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_set_absolute_volume) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0, 0, 1, 0x50};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 5},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_ABSOLUTE_VOLUME);
  EXPECT_EQ(result.volume.volume, 0x50);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_set_addressed_player) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {AVRC_PDU_SET_ADDRESSED_PLAYER, 0, 0, 2, 0x12, 0x34};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 6},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_ADDRESSED_PLAYER);
  EXPECT_EQ(result.addr_player.player_id, 0x1234);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_play_item) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_PLAY_ITEM, 0, 0, 11, AVRC_SCOPE_NOW_PLAYING, 1, 2, 3, 4, 5, 6, 7, 8, 0x12, 0x34};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 15},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_PLAY_ITEM);
  EXPECT_EQ(result.play_item.scope, AVRC_SCOPE_NOW_PLAYING);
  for (int i = 0; i < AVRC_UID_SIZE; i++) {
    EXPECT_EQ(result.play_item.uid[i], i + 1);
  }
  EXPECT_EQ(result.play_item.uid_counter, 0x1234);
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_get_player_value_text) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT,
          0x00,  // reserved
          0x00,
          0x05,                        // length of parameters (2 bytes)
          AVRC_PLAYER_SETTING_REPEAT,  // attr_id (1 byte)
          0x03,                        // num_val (1 byte), indicating there are 2 attribute values
          0x01,
          0x02,
          0x03
          // vals (2 bytes), the two attribute values
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 9},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, static_cast<uint8_t>(AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT));
  EXPECT_EQ(result.get_app_val_txt.attr_id, AVRC_PLAYER_SETTING_REPEAT);
  EXPECT_EQ(result.get_app_val_txt.num_val, static_cast<uint8_t>(3));
  EXPECT_EQ(result.get_app_val_txt.vals[0], static_cast<uint8_t>(1));
  EXPECT_EQ(result.get_app_val_txt.vals[1], static_cast<uint8_t>(2));
  EXPECT_EQ(result.get_app_val_txt.vals[2], static_cast<uint8_t>(3));
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_inform_charset) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_INFORM_DISPLAY_CHARSET,
          0,  // reserved
          0,
          5,  // length of parameters (1 byte num_id + 2*2 bytes charsets)
          2,  // num_id
          0x00,
          0x01,  // First charset ID
          0x00,
          0x6A  // Second charset ID (UTF-8)
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 9},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, static_cast<uint8_t>(AVRC_PDU_INFORM_DISPLAY_CHARSET));
  EXPECT_EQ(result.inform_charset.num_id, static_cast<uint8_t>(2));
  EXPECT_EQ(result.inform_charset.charsets[0], static_cast<uint16_t>(1));
  EXPECT_EQ(result.inform_charset.charsets[1], static_cast<uint16_t>(0x6A));  // UTF-8
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_inform_battery_stat) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_INFORM_BATTERY_STAT_OF_CT,
          0,                          // reserved
          0, 1,                       // length of parameters (1 byte for battery status)
          AVRC_BATTERY_STATUS_NORMAL  // battery status
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 5},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, static_cast<uint8_t>(AVRC_PDU_INFORM_BATTERY_STAT_OF_CT));
  EXPECT_EQ(result.inform_battery_status.battery_status,
            static_cast<uint8_t>(AVRC_BATTERY_STATUS_NORMAL));
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_req_continuation_rsp) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_REQUEST_CONTINUATION_RSP,
          0,                         // reserved
          0, 1,                      // length of parameters (1 byte for target_pdu)
          AVRC_PDU_GET_ELEMENT_ATTR  // target_pdu
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 5},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, static_cast<uint8_t>(AVRC_PDU_REQUEST_CONTINUATION_RSP));
  EXPECT_EQ(result.continu.target_pdu, static_cast<uint8_t>(AVRC_PDU_GET_ELEMENT_ATTR));
}

TEST_F(StackAvrcpTest, avrc_pars_vendor_cmd_abort_continuation_rsp) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[] = {
          AVRC_PDU_ABORT_CONTINUATION_RSP,
          0,                         // reserved
          0, 1,                      // length of parameters (1 byte for target_pdu)
          AVRC_PDU_GET_ELEMENT_ATTR  // target_pdu
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 5},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, static_cast<uint8_t>(AVRC_PDU_ABORT_CONTINUATION_RSP));
  EXPECT_EQ(result.abort.target_pdu, static_cast<uint8_t>(AVRC_PDU_GET_ELEMENT_ATTR));
}

TEST_F(StackAvrcpTest, avrc_pars_browsing_cmd_set_browsed_player) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t browse_data[] = {AVRC_PDU_SET_BROWSED_PLAYER, 0, 2, 0x12, 0x34};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_BROWSE},
                     .p_browse_data = browse_data,
                     .browse_len = sizeof(browse_data)},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_BROWSED_PLAYER);
  EXPECT_EQ(result.br_player.player_id, 0x1234);
}

TEST_F(StackAvrcpTest, avrc_pars_browsing_cmd_get_folder_items_with_attrs) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];

  uint8_t browse_data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,
          0,
          18,  // parameter length
          AVRC_SCOPE_NOW_PLAYING,
          0,
          0,
          0,
          1,  // start item
          0,
          0,
          0,
          2,  // end item
          2,  // attr count
          0,
          0,
          0,
          AVRC_MEDIA_ATTR_ID_TITLE,
          0,
          0,
          0,
          AVRC_MEDIA_ATTR_ID_ARTIST,
  };
  tAVRC_MSG msg = {
          .browse = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_BROWSE},
                     .p_browse_data = browse_data,
                     .browse_len = sizeof(browse_data)},
  };

  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_FOLDER_ITEMS);
  EXPECT_EQ(result.get_items.scope, AVRC_SCOPE_NOW_PLAYING);
  EXPECT_EQ(result.get_items.start_item, 1u);
  EXPECT_EQ(result.get_items.end_item, 2u);
  EXPECT_EQ(result.get_items.attr_count, 2);
  EXPECT_EQ(result.get_items.p_attr_list[0], (uint32_t)AVRC_MEDIA_ATTR_ID_TITLE);
  EXPECT_EQ(result.get_items.p_attr_list[1], (uint32_t)AVRC_MEDIA_ATTR_ID_ARTIST);
}

TEST_F(StackAvrcpTest, avrc_pars_pass_thru_vendor_group_valid) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t vendor_data[5] = {};
  tAVRC_MSG msg = {
          .pass =
                  {
                          .hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_PASS_THRU},
                          .op_id = AVRC_ID_VENDOR,
                          .state = AVRC_STATE_PRESS,
                          .p_pass_data = vendor_data,
                          .pass_len = 5,
                  },
  };

  uint8_t* p = vendor_data;
  uint32_t company_id = AVRC_CO_METADATA;
  *p++ = (uint8_t)(company_id >> 16);
  *p++ = (uint8_t)(company_id >> 8);
  *p++ = (uint8_t)company_id;
  *p++ = 0;  // reserved
  *p = AVRC_PDU_PREV_GROUP;

  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, static_cast<uint8_t>(AVRC_PDU_PREV_GROUP));
}

TEST_F(StackAvrcpTest, avrc_pars_browsing_cmd_change_path) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t browse_data[] = {
          AVRC_PDU_CHANGE_PATH, 0, 11, 0x12, 0x34, AVRC_DIR_UP, 1, 2, 3, 4, 5, 6, 7, 8};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_BROWSE},
                     .p_browse_data = browse_data,
                     .browse_len = sizeof(browse_data)},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_CHANGE_PATH);
  EXPECT_EQ(result.chg_path.uid_counter, 0x1234);
  EXPECT_EQ(result.chg_path.direction, AVRC_DIR_UP);
}

TEST_F(StackAvrcpTest, avrc_pars_browsing_cmd_get_item_attributes) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t browse_data[] = {AVRC_PDU_GET_ITEM_ATTRIBUTES,
                           0,
                           16,
                           AVRC_SCOPE_NOW_PLAYING,
                           1,
                           2,
                           3,
                           4,
                           5,
                           6,
                           7,
                           8,
                           0x12,
                           0x34,
                           1,
                           0,
                           0,
                           0,
                           AVRC_MEDIA_ATTR_ID_TITLE};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_BROWSE},
                     .p_browse_data = browse_data,
                     .browse_len = sizeof(browse_data)},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_ITEM_ATTRIBUTES);
  EXPECT_EQ(result.get_attrs.scope, AVRC_SCOPE_NOW_PLAYING);
  EXPECT_EQ(result.get_attrs.uid_counter, 0x1234);
  EXPECT_EQ(result.get_attrs.attr_count, 1);
  EXPECT_EQ(result.get_attrs.p_attr_list[0], static_cast<uint32_t>(AVRC_MEDIA_ATTR_ID_TITLE));
}

TEST_F(StackAvrcpTest, avrc_pars_browsing_cmd_get_total_num_of_items) {
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint8_t browse_data[] = {AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS, 0, 1, AVRC_SCOPE_NOW_PLAYING};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_BROWSE},
                     .p_browse_data = browse_data,
                     .browse_len = sizeof(browse_data)},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS);
  EXPECT_EQ(result.get_num_of_items.scope, AVRC_SCOPE_NOW_PLAYING);
}

TEST_F(StackAvrcpTest, avrc_pars_browsing_cmd_search) {
  tAVRC_COMMAND result{};
  uint8_t buf[255] = "test";
  uint8_t browse_data[] = {AVRC_PDU_SEARCH, 0, 8, 0, 0x6a, 0, 4, 't', 'e', 's', 't'};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_BROWSE},
                     .p_browse_data = browse_data,
                     .browse_len = sizeof(browse_data)},
  };
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, sizeof(buf)), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SEARCH);
  EXPECT_EQ(result.search.string.charset_id, 0x6a);
  EXPECT_EQ(result.search.string.str_len, 4);
  EXPECT_EQ(strncmp((char*)result.search.string.p_str, "test", 4), 0);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsCommand_SetAbsoluteVolume) {
  tAVRC_COMMAND result{};
  uint8_t vendor_data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0, 0, 1, 0x50};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_CTRL, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = sizeof(vendor_data)},
  };

  EXPECT_EQ(AVRC_Ctrl_ParsCommand(&msg, &result), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_ABSOLUTE_VOLUME);
  EXPECT_EQ(result.volume.volume, 0x50);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsCommand_RegisterNotification) {
  tAVRC_COMMAND result{};
  uint8_t vendor_data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,  // PDU
          0x00,                            // reserved
          0x00,
          0x05,                         // len
          AVRC_EVT_PLAY_STATUS_CHANGE,  // event id
          0x00,
          0x00,
          0x00,
          0x00,
          // param
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_CMD_NOTIF, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = vendor_data,
                     .vendor_len = 9},
  };
  EXPECT_EQ(AVRC_Ctrl_ParsCommand(&msg, &result), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_REGISTER_NOTIFICATION);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_PLAY_STATUS_CHANGE);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsCommand_too_short_vendor_message) {
  // Test case for a vendor-dependent message with a length less than the minimum required 4 bytes.
  tAVRC_COMMAND result{};
  uint8_t data[] = {0x00, 0x00, 0x00};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 3,
                   }};
  EXPECT_EQ(AVRC_Ctrl_ParsCommand(&msg, &result), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsCommand_set_absolute_volume_incorrect_len) {
  // Test for a "Set Absolute Volume" command with an incorrect payload length.
  uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x02, 0x10, 0x20};
  tAVRC_COMMAND result{};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_RSP_ACCEPT, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  msg.vendor.p_vendor_data = data;
  EXPECT_EQ(AVRC_Ctrl_ParsCommand(&msg, &result), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsCommand_register_notification_too_short) {
  // Test for a "Register Notification" command with a payload that is too short.
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00};
  tAVRC_COMMAND result{};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_RSP_ACCEPT, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  msg.vendor.p_vendor_data = data;
  EXPECT_EQ(AVRC_Ctrl_ParsCommand(&msg, &result), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsCommand_register_notification_bad_event_id) {
  // Test for a "Register Notification" command with an invalid event ID (0x00).
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00};
  tAVRC_COMMAND result{};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_RSP_ACCEPT, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 9,
                   }};
  msg.vendor.p_vendor_data = data;
  EXPECT_EQ(AVRC_Ctrl_ParsCommand(&msg, &result), AVRC_STS_BAD_PARAM);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_vendor_too_short) {
  // Test for a vendor-dependent command with a length less than the minimum.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {0x00, 0x00, 0x00};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 3,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_vendor_incorrect_length) {
  // Test for a vendor message where the specified length does not match the total message length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x02, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_capabilities_bad_param) {
  // Test for "Get Capabilities" with an invalid capability ID.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x01, 0xff};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  msg.vendor.p_vendor_data = data;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_PARAM);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_list_player_app_attr_internal_err) {
  // Test for "List Player Application Attributes" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_LIST_PLAYER_APP_ATTR, 0x00, 0x00, 0x01, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_list_player_app_values_internal_err) {
  // Test for "List Player Application Values" with a length of zero.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_LIST_PLAYER_APP_VALUES, 0x00, 0x00, 0x00};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 4,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_list_player_app_values_len_mismatch) {
  // Test for "List Player Application Values" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_LIST_PLAYER_APP_VALUES, 0x00, 0x00, 0x02, 0x01, 0x02};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 6,
                   }};

  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_cur_player_app_value_internal_err) {
  // Test for "Get Current Player Application Value" with a length of zero.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_CUR_PLAYER_APP_VALUE, 0x00, 0x00, 0x00};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 4,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_player_app_value_text_internal_err) {
  // Test for "Get Player Application Value Text" with a length less than the minimum.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT, 0x00, 0x00, 0x02, 0x01, 0x02};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 6,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_inform_battery_stat_internal_err) {
  // Test for "Inform Battery Status" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_INFORM_BATTERY_STAT_OF_CT, 0x00, 0x00, 0x02, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};

  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_elem_attr_internal_err) {
  // Test for "Get Element Attributes" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_ELEMENT_ATTR,
                    0x00,
                    0x00,
                    0x09,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x01,
                    0x02,
                    0x03,
                    0x04};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 13,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_elem_attr_not_found) {
  // Test for "Get Element Attributes" with a non-zero UID, which is not supported.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_ELEMENT_ATTR,
                    0x00,
                    0x00,
                    0x09,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 13,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_NOT_FOUND);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_play_status_internal_err) {
  // Test for "Get Play Status" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_PLAY_STATUS, 0x00, 0x00, 0x01, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_register_notification_internal_err) {
  // Test for "Register Notification" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x02, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_set_absolute_volume_internal_err) {
  // Test for "Set Absolute Volume" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x02, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_request_continuation_rsp_internal_err) {
  // Test for "Request Continuation Response" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_REQUEST_CONTINUATION_RSP, 0x00, 0x00, 0x02, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_abort_continuation_rsp_internal_err) {
  // Test for "Abort Continuation Response" with an incorrect length.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_ABORT_CONTINUATION_RSP, 0x00, 0x00, 0x02, 0x01};
  tAVRC_MSG msg = {.vendor = {
                           .hdr = {.ctype = AVRC_CMD_STATUS, .opcode = AVRC_OP_VENDOR},
                           .p_vendor_data = data,
                           .vendor_len = 5,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_browsing_too_short) {
  // Test for a browsing message with a length less than the minimum required 3 bytes.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {0x00, 0x00};
  tAVRC_MSG msg = {.browse = {
                           .hdr = {.opcode = AVRC_OP_BROWSE},
                           .p_browse_data = data,
                           .browse_len = 2,

                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_set_browsed_player_too_short) {
  // Test for "Set Browsed Player" with a message length that is too short.
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_SET_BROWSED_PLAYER, 0x00, 0x01, 0x01};
  tAVRC_MSG msg = {.browse = {
                           .hdr = {.opcode = AVRC_OP_BROWSE},
                           .p_browse_data = data,
                           .browse_len = 4,
                   }};
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_folder_items_too_short) {
  static uint8_t data[] = {AVRC_PDU_GET_FOLDER_ITEMS,
                           0x00,
                           0x09,
                           0x00,
                           0x01,
                           0x02,
                           0x03,
                           0x04,
                           0x05,
                           0x06,
                           0x07,
                           0x08};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 12},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_folder_items_bad_range) {
  static uint8_t data[] = {AVRC_PDU_GET_FOLDER_ITEMS,
                           0x00,
                           0x0a,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x02,
                           0x00,
                           0x00,
                           0x00,
                           0x01,
                           0x00};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 13},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_RANGE);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_change_path_too_short) {
  static uint8_t data[] = {AVRC_PDU_CHANGE_PATH,
                           0x00,
                           0x0a,
                           0x00,
                           0x01,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 13},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_change_path_bad_dir) {
  static uint8_t data[] = {AVRC_PDU_CHANGE_PATH,
                           0x00,
                           0x0b,
                           0x00,
                           0x01,
                           0xff,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 14},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_DIR);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_item_attributes_too_short) {
  static uint8_t data[] = {AVRC_PDU_GET_ITEM_ATTRIBUTES,
                           0x00,
                           0x0b,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x01};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 14},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_item_attributes_attr_too_short) {
  static uint8_t data[] = {
          AVRC_PDU_GET_ITEM_ATTRIBUTES, 0x00, 0x11, 0x01, 0x00, 0x00, 0x00, 0x00,
  };
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 8},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = 4;
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_get_total_num_of_items_bad_scope) {
  static uint8_t data[] = {AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS, 0x00, 0x01, 0xff};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 4},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_SCOPE);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_search_too_short) {
  static uint8_t data[] = {AVRC_PDU_SEARCH, 0x00, 0x03, 0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 6},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsCommand_search_msg_too_short) {
  static uint8_t data[] = {AVRC_PDU_SEARCH, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 6},
  };
  tAVRC_COMMAND result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  EXPECT_EQ(AVRC_ParsCommand(&msg, &result, buf, buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_null_message) {
  // Test parsing with a null message pointer.
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_ParsResponse(nullptr, &result, p_buf, buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_null_result) {
  // Test parsing with a null result pointer.
  tAVRC_MSG msg{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_ParsResponse(&msg, nullptr, p_buf, buf_len), AVRC_STS_INTERNAL_ERR);
}
TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_zero_len) {
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = (uint8_t*)"",
                     .vendor_len = 0},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_NO_ERROR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_null_data) {
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = nullptr, .vendor_len = 1},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_too_short) {
  static uint8_t data[] = {0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 3},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_payload_too_short) {
  static uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x02, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 5},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_reject_too_short) {
  static uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_RSP_REJ, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = data,
                     .vendor_len = 4},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_set_absolute_volume_not_enabled) {
  static uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x01, 0x05};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 5},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_NO_ERROR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_set_absolute_volume_bad_len) {
  static uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x02, 0x05, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 6},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_register_notification_too_short) {
  static uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 4},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_register_notification_vol_chg_too_short) {
  static uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x01,
                           AVRC_EVT_VOLUME_CHANGE};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_RSP_CHANGED, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = data,
                     .vendor_len = 5},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_default_case) {
  static uint8_t data[] = {0xFF, 0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 4},
  };
  tAVRC_RESPONSE result{};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_BAD_CMD);
}
TEST_F(StackAvrcpTest, AVRC_ParsResponse_unhandled_opcode) {
  // Test an unhandled opcode in the main switch statement.
  tAVRC_MSG msg{};
  tAVRC_RESPONSE result{};
  msg.hdr.opcode = AVRC_OP_UNIT_INFO;
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_null_message) {
  // Test parsing with a null message pointer.
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(nullptr, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_null_result) {
  // Test parsing with a null result pointer.
  tAVRC_MSG msg{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, nullptr, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}
TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_vendor_too_short) {
  static uint8_t data[] = {0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 3},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_vendor_payload_too_short) {
  static uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x02, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 5},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_vendor_reject_too_short) {
  static uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.ctype = AVRC_RSP_REJ, .opcode = AVRC_OP_VENDOR},
                     .p_vendor_data = data,
                     .vendor_len = 4},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_register_notification_len_err) {
  static uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x01,
                           AVRC_EVT_PLAY_STATUS_CHANGE};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 5},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_capabilities_too_short) {
  static uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x01, AVRC_CAP_COMPANY_ID};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 5},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_capabilities_company_id_len_err) {
  static uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x03,
                           AVRC_CAP_COMPANY_ID,       0x01, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 7},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_capabilities_events_len_err) {
  static uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x02,
                           AVRC_CAP_EVENTS_SUPPORTED, 0x01, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 7},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_list_app_attr_len_err) {
  static uint8_t data[] = {AVRC_PDU_LIST_PLAYER_APP_ATTR, 0x00, 0x00, 0x01, 0x01, 0x02};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 6},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_list_app_values_len_err) {
  static uint8_t data[] = {AVRC_PDU_LIST_PLAYER_APP_VALUES,  // PDU
                           0x00,                             // reserved
                           0x00,
                           0x02,  // len
                           0x02,  // num_val
                           0x02};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 6},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_cur_app_value_len_err) {
  static uint8_t data[] = {AVRC_PDU_GET_CUR_PLAYER_APP_VALUE, 0x00, 0x00, 0x02, 0x01, 0x02};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 6},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_app_attr_text_len_err) {
  static uint8_t data[] = {
          AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x01};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 8},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_app_val_text_len_err) {
  static uint8_t data[] = {
          AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x01};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 8},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_app_player_value_text) {
  static uint8_t data[] = {AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT,  // PDU
                           0x00,                                // reserved,
                           0x00,
                           0x09,  // len
                           0x01,  // num_vals
                           0x00,  // attr_id
                           0x00,
                           0x01,  // charaet_id
                           0x01,  // str_len
                           0x30};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 13},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_NO_ERROR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_elem_attr_len_err) {
  static uint8_t data[] = {AVRC_PDU_GET_ELEMENT_ATTR, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x01};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 8},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_play_status_len_err) {
  static uint8_t data[] = {AVRC_PDU_GET_PLAY_STATUS,
                           0x00,
                           0x00,
                           0x08,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00,
                           0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 12},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_set_addressed_player_bad_len) {
  static uint8_t data[] = {AVRC_PDU_SET_ADDRESSED_PLAYER, 0x00, 0x00, 0x02, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 6},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_browsing_too_short) {
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE},
                     .p_browse_data = (uint8_t*)"\x00\x00",
                     .browse_len = 2},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_BAD_PARAM);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_browse_pkt_len_too_short) {
  static uint8_t data[] = {AVRC_PDU_CHANGE_PATH, 0x00, 0x03, 0x00, 0x00};
  static tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 5},
  };
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_browse_default_case) {
  // Test a browsing response with an unknown PDU.
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  uint8_t data[] = {0x00, 0x00, 0x00};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 3}};
  msg.browse.p_browse_data = data;
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_NO_ERROR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_unhandled_opcode) {
  // Test an unhandled opcode in the main switch statement.
  tAVRC_RESPONSE result{};
  uint8_t p_buf[255];
  uint16_t buf_len = sizeof(p_buf);
  tAVRC_MSG msg = {.hdr = {.opcode = AVRC_OP_UNIT_INFO}};
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, p_buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_set_absolute_volume_valid) {
  // Test a valid Set Absolute Volume response.
  tAVRC_RESPONSE result{};
  uint8_t data[] = {AVRC_PDU_SET_ABSOLUTE_VOLUME, 0x00, 0x00, 0x01, 0x40};
  tAVRC_MSG msg = {.vendor = {.hdr = {.ctype = AVRC_RSP_ACCEPT, .opcode = AVRC_OP_VENDOR},
                              .p_vendor_data = data,
                              .vendor_len = 5}};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_ABSOLUTE_VOLUME);
  EXPECT_EQ(result.volume.volume, 0x40);
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_register_notification_interim) {
  // Test a valid Interim response for a Volume Change notification.
  tAVRC_RESPONSE result{};
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x03,
                    AVRC_EVT_VOLUME_CHANGE,         0x01, 0x40};
  tAVRC_MSG msg = {.vendor = {.hdr = {.ctype = AVRC_RSP_INTERIM, .opcode = AVRC_OP_VENDOR},
                              .p_vendor_data = data,
                              .vendor_len = 7}};

  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_REGISTER_NOTIFICATION);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_VOLUME_CHANGE);
  EXPECT_EQ(result.reg_notif.param.volume, static_cast<uint8_t>(1));
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_register_notification_changed) {
  // Test a valid Changed response for a Volume Change notification.
  tAVRC_RESPONSE result{};
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x03,
                    AVRC_EVT_VOLUME_CHANGE,         0x01, 0x50};
  tAVRC_MSG msg = {.vendor = {.hdr = {.ctype = AVRC_RSP_CHANGED, .opcode = AVRC_OP_VENDOR},
                              .p_vendor_data = data,
                              .vendor_len = 7}};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_REGISTER_NOTIFICATION);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_VOLUME_CHANGE);
  EXPECT_EQ(result.reg_notif.param.volume, static_cast<uint8_t>(1));
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_vendor_reject_valid) {
  // Test a valid Rejected vendor response.
  tAVRC_RESPONSE result{};
  uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES, 0x00, 0x00, 0x01, AVRC_STS_BAD_CMD};
  tAVRC_MSG msg = {.vendor = {.hdr = {.ctype = AVRC_RSP_REJ, .opcode = AVRC_OP_VENDOR},
                              .p_vendor_data = data,
                              .vendor_len = 5}};

  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, nullptr, 0), AVRC_STS_BAD_CMD);
  EXPECT_EQ(result.rsp.status, AVRC_STS_BAD_CMD);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_reg_notif_play_status_change) {
  // Test parsing a valid Register Notification response for Play Status Change.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION, 0x00, 0x00, 0x02, AVRC_EVT_PLAY_STATUS_CHANGE,
                    AVRC_PLAYSTATE_PLAYING};
  tAVRC_MSG msg = {.vendor = {.hdr = {.ctype = AVRC_RSP_INTERIM, .opcode = AVRC_OP_VENDOR},
                              .p_vendor_data = data,
                              .vendor_len = 6}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_REGISTER_NOTIFICATION);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_PLAY_STATUS_CHANGE);
  EXPECT_EQ(result.reg_notif.param.play_status, AVRC_PLAYSTATE_PLAYING);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_capabilities_company_id) {
  // Test a valid Get Capabilities response with company IDs.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES,
                    0x00,
                    0x00,
                    0x07,
                    AVRC_CAP_COMPANY_ID,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 11}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_CAPABILITIES);
  EXPECT_EQ(result.get_caps.capability_id, AVRC_CAP_COMPANY_ID);
  EXPECT_EQ(result.get_caps.count, 0x01);
  EXPECT_EQ(result.get_caps.param.company_id[0], static_cast<uint32_t>(0));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_capabilities_events) {
  // Test a valid Get Capabilities response with supported events.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES,  0x00, 0x00, 0x03, AVRC_CAP_EVENTS_SUPPORTED, 0x01,
                    AVRC_EVT_PLAY_STATUS_CHANGE};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 7}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_CAPABILITIES);
  EXPECT_EQ(result.get_caps.capability_id, AVRC_CAP_EVENTS_SUPPORTED);
  EXPECT_EQ(result.get_caps.count, 0x01);
  EXPECT_EQ(result.get_caps.param.event_id[0], AVRC_EVT_PLAY_STATUS_CHANGE);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_play_status) {
  // Test a valid Get Play Status response.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_PLAY_STATUS,
                    0x00,
                    0x00,
                    0x09,
                    0x00,
                    0x00,
                    0x00,
                    0x10,
                    0x00,
                    0x00,
                    0x00,
                    0x05,
                    0x01};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 13}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_PLAY_STATUS);
  EXPECT_EQ(result.get_play_status.song_len, static_cast<uint32_t>(16));
  EXPECT_EQ(result.get_play_status.song_pos, static_cast<uint32_t>(5));
  EXPECT_EQ(result.get_play_status.play_status, static_cast<uint8_t>(1));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_folder_items_player) {
  // Test a valid Get Folder Items response with a player item.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);               // The correct length is 37, not 34.
  uint8_t data[] = {AVRC_PDU_GET_FOLDER_ITEMS,  // PDU
                    0x00,
                    0x21,               // Packet Length (33 bytes)
                    AVRC_STS_NO_ERROR,  // Status
                    0x00,
                    0x00,
                    0x00,
                    0x01,  // UID Counter
                    0x00,
                    0x01,              // Item Count
                    AVRC_ITEM_PLAYER,  // Item Type
                    0x00,
                    0x1A,  // Player Item Length (26 bytes)
                    0x00,
                    0x01,  // Player ID
                    0x01,  // Major Type
                    0x00,
                    0x00,
                    0x00,
                    0x01,  // Sub Type
                    0x01,  // Play Status
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,  // Features
                    0x00,
                    0x04,  // Charset ID
                    0x00,
                    0x04,  // String Length
                    0x42,
                    0x61,
                    0x63,
                    0x6b};  // "Back" in ASCII
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 37}};
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_INTERNAL_ERR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_FOLDER_ITEMS);
  EXPECT_EQ(result.get_items.item_count, static_cast<uint16_t>(1));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_change_path) {
  // Test a valid Change Path response.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_CHANGE_PATH, 0x00, 0x05, AVRC_STS_NO_ERROR, 0x00, 0x00, 0x00, 0x0A};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 8}};
  msg.browse.p_browse_data = data;

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_CHANGE_PATH);
  EXPECT_EQ(result.chg_path.status, AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.chg_path.num_items, static_cast<uint16_t>(10));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_set_browsed_player) {
  // Test a valid Set Browsed Player response with a single folder name
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_SET_BROWSED_PLAYER,
                    0x00,
                    0x0F,
                    AVRC_STS_NO_ERROR,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x00,
                    0x00,
                    0x01,
                    0x00,
                    0x04,
                    0x46,
                    0x6f,
                    0x6c,
                    0x64,
                    0x65,
                    0x72};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 22}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_BROWSED_PLAYER);
  EXPECT_EQ(result.br_player.status, AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.br_player.num_items, static_cast<uint32_t>(0));
  EXPECT_EQ(result.br_player.folder_depth, static_cast<uint8_t>(0));
}

TEST_F(StackAvrcpTest, AVRC_ParsResponse_pass_thru_correct) {
  // Test a valid pass-through command.
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {0x00, 0x19, 0x58, 0x00, 0x01};
  tAVRC_MSG msg = {.pass = {
                           .hdr = {.opcode = AVRC_OP_PASS_THRU},
                           .op_id = AVRC_ID_VENDOR,
                           .state = AVRC_STATE_PRESS,
                           .p_pass_data = data,
                           .pass_len = AVRC_PASS_THRU_GROUP_LEN,
                   }};
  EXPECT_EQ(AVRC_ParsResponse(&msg, &result, buf, buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_ID_UP);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_vendor_reject_valid_payload) {
  // Test a rejected vendor response with a valid payload length (line 566, 567).
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_CAPABILITIES,  // PDU
                    0x00,                       // reserved
                    0x00,
                    0x01,  // len
                    AVRC_STS_BAD_CMD};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 5}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_INTERNAL_ERR);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_reg_notif_track_change) {
  // Test a valid Register Notification response for Track Change (line 150-156).
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION,
                    0x00,
                    0x00,
                    0x09,
                    AVRC_EVT_TRACK_CHANGE,
                    0x01,
                    0x02,
                    0x03,
                    0x04,
                    0x05,
                    0x06,
                    0x07,
                    0x08};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 13}};
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_TRACK_CHANGE);
  // No direct assertion on the array, as BE_STREAM_TO_ARRAY is a macro.
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_reg_notif_app_setting_change) {
  // Test a valid Register Notification for App Setting Change (line 158-175).

  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {
          AVRC_PDU_REGISTER_NOTIFICATION,
          0x00,  // reserved
          0x00,
          0x05,                         // len
          AVRC_EVT_APP_SETTING_CHANGE,  /// event_id
          0x01,                         // num_attr
          0x01,                         // attr_id
          0x02                          // attr_value
  };
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 9}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_APP_SETTING_CHANGE);
  EXPECT_EQ(result.reg_notif.param.player_setting.num_attr, static_cast<uint8_t>(1));
  EXPECT_EQ(result.reg_notif.param.player_setting.attr_id[0], static_cast<uint8_t>(1));
  EXPECT_EQ(result.reg_notif.param.player_setting.attr_value[0], static_cast<uint8_t>(2));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_reg_notif_play_pos_changed) {
  // Test a valid Register Notification for Play Position Changed (line 192-198)
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_REGISTER_NOTIFICATION,
                    0x00,
                    0x00,
                    0x05,
                    AVRC_EVT_PLAY_POS_CHANGED,
                    0x00,
                    0x00,
                    0x00,
                    0x1E};
  tAVRC_MSG msg = {
          .vendor = {.hdr = {.opcode = AVRC_OP_VENDOR}, .p_vendor_data = data, .vendor_len = 9}};
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.reg_notif.event_id, AVRC_EVT_PLAY_POS_CHANGED);
  EXPECT_EQ(result.reg_notif.param.play_pos, static_cast<uint32_t>(30));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParseResponse_get_items_item_player) {
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_FOLDER_ITEMS,  // PDU
                    0x00,
                    0x26,               // len
                    AVRC_STS_NO_ERROR,  // status
                    0x00,
                    0x00,  // uid counter
                    0x00,
                    0x01,              // item count
                    AVRC_ITEM_PLAYER,  // item type
                    0x00,
                    0x1A,  // player item length
                    0x00,
                    0x01,  // player id
                    0x01,  // major type
                    0x00,
                    0x00,
                    0x00,
                    0x01,  // sub type
                    0x01,  // play status
                    0x01,
                    0x02,
                    0x03,
                    0x04,
                    0x05,
                    0x06,
                    0x07,
                    0x08,
                    0x09,
                    0x0a,
                    0x0b,
                    0x0c,
                    0x0d,
                    0x0e,
                    0x0f,
                    0x10,  // features
                    0x00,
                    0x01,  // charset id
                    0x00,
                    0x01,  // string length
                    0x30};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 54}};
  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.get_items.p_item_list[0].item_type, AVRC_ITEM_PLAYER);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_items_folder_item) {
  // Test a valid Get Folder Items response with a folder item (line 323-351)
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,  // PDU
          0x00,
          0x1F,  // length
          AVRC_STS_NO_ERROR,
          0x00,
          0x00,  // UID Counter
          0x00,
          0x01,  // Item Count
          AVRC_ITEM_FOLDER,
          0x00,
          0x1A,  // folder item length
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,  // uid
          0x01,  // folder type
          0x01,  // folder playable
          0x00,
          0x04,  // folder->name.charset_id
          0x00,
          0x04,  // foldr->name.string_length
          0x46,
          0x6f,
          0x6c,
          0x64,  // folder->name.p_sr
  };
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 34}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.get_items.p_item_list[0].item_type, AVRC_ITEM_FOLDER);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_items_media_item) {
  // Test a valid Get Folder Items response with a media item (line 353-405)
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {
          AVRC_PDU_GET_FOLDER_ITEMS,  // PDU
          0x00,
          0x25,  // len
          AVRC_STS_NO_ERROR,
          0x00,
          0x01,  // ctr
          0x00,
          0x01,             // item count
          AVRC_ITEM_MEDIA,  // item type
          0x00,
          0x2A,  // media item length
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,  // uid
          0x00,  // media type
          0x00,
          0x01,  // charset id
          0x00,
          0x01,  // string length
          0x54,  // media->name.p_str
          0x01,  // attribute count
          0x00,
          0x00,
          0x00,
          0x01,  // attribute id
          0x00,
          0x01,  // entry_name->charset_id
          0x00,
          0x01,  // entry_name->string_length
          0x30,
  };
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 54}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.get_items.p_item_list[0].item_type, AVRC_ITEM_MEDIA);
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_get_item_attributes_multi) {
  // Test a valid Get Item Attributes response (line 438-468).
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {AVRC_PDU_GET_ITEM_ATTRIBUTES,
                    0x00,
                    0x25,
                    AVRC_STS_NO_ERROR,
                    0x02,
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x00,
                    0x04,
                    0x00,
                    0x04,
                    0x4e,
                    0x61,
                    0x6d,
                    0x65,
                    0x00,
                    0x00,
                    0x00,
                    0x02,
                    0x00,
                    0x04,
                    0x00,
                    0x05,
                    0x41,
                    0x72,
                    0x74,
                    0x69,
                    0x73,
                    0x74};
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 40}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_GET_ITEM_ATTRIBUTES);
  EXPECT_EQ(result.get_attrs.num_attrs, static_cast<uint8_t>(2));
}

TEST_F(StackAvrcpTest, AVRC_Ctrl_ParsResponse_set_browsed_player_multi_folders) {
  // Test a valid Set Browsed Player response with multiple folders (line 470-515).
  tAVRC_RESPONSE result{};
  uint8_t buf[255];
  uint16_t buf_len = sizeof(buf);
  uint8_t data[] = {
          AVRC_PDU_SET_BROWSED_PLAYER,
          0x00,
          0x1A,
          AVRC_STS_NO_ERROR,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x02,
          0x00,
          0x02,
          0x04,
          0x46,
          0x00,
          0x02,
          0x64,
          0x0C,
  };
  tAVRC_MSG msg = {
          .browse = {.hdr = {.opcode = AVRC_OP_BROWSE}, .p_browse_data = data, .browse_len = 29}};

  EXPECT_EQ(AVRC_Ctrl_ParsResponse(&msg, &result, buf, &buf_len), AVRC_STS_NO_ERROR);
  EXPECT_EQ(result.pdu, AVRC_PDU_SET_BROWSED_PLAYER);
  EXPECT_EQ(result.br_player.folder_depth, static_cast<uint8_t>(2));
}