/*
 * Copyright 2023 The Android Open Source Project
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

#include <android-base/properties.h>
#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <memory>
#include <string>

#include "bta/ag/bta_ag_int.h"
#include "bta/include/bta_ag_api.h"
#include "bta/include/bta_ag_swb_aptx.h"
#include "bta/include/bta_hfp_api.h"
#include "bta/mock/mock_bta_sys_main.h"
#include "btif_status.h"
#include "hci/controller_mock.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "stack/include/sdp_api.h"
#include "stack/mock/mock_stack_acl.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_device_esco_parameters.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_osi_alarm.h"

#define TEST_BT com::android::bluetooth::flags

using ::testing::_;
using ::testing::MockFunction;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::Test;

using namespace bluetooth;

namespace {

bool bta_ag_hdl_event(const BT_HDR_RIGID* /*p_msg*/) { return true; }
void BTA_AgDisable() {}

const tBTA_SYS_REG bta_ag_reg = {bta_ag_hdl_event, BTA_AgDisable};

}  // namespace

const std::string kBtCodecAptxVoiceEnabled = "bluetooth.hfp.codec_aptx_voice.enabled";

static bool enable_aptx_voice_property(bool enable) {
  const std::string value = enable ? "true" : "false";
  return android::base::SetProperty(kBtCodecAptxVoiceEnabled, value);
}

class BtaAgTest : public Test {
protected:
  void SetUp() override {
    reset_mock_function_count_map();
    memset(&bta_ag_cb, 0, sizeof(bta_ag_cb));
    fake_osi_ = std::make_unique<test::fake::FakeOsi>();
    set_mock_btm_client_interface(&btm_client_interface_);
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<NiceMock<bluetooth::hci::testing::MockController>>();

    main_thread_start_up();
    post_on_bt_main([]() { log::info("Main thread started up"); });

    bta_sys_register(BTA_ID_AG, &bta_ag_reg);

    bta_ag_cb.p_cback = [](tBTA_AG_EVT /*event*/, tBTA_AG* /*p_data*/) {};
    addr = RawAddress::FromString("00:11:22:33:44:55").value();
    test::mock::device_esco_parameters::esco_parameters_for_codec.body = [this](esco_codec_t codec,
                                                                                bool /*offload*/) {
      this->codec = codec;
      return enh_esco_params_t{};
    };
  }
  void TearDown() override {
    reset_mock_btm_client_interface();
    test::mock::device_esco_parameters::esco_parameters_for_codec = {};
    bta_sys_deregister(BTA_ID_AG);
    post_on_bt_main([]() { log::info("Main thread shutting down"); });
    main_thread_shut_down();
    bluetooth::hci::testing::mock_controller_.reset();
  }

  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
  MockBtmClientInterface btm_client_interface_;
  const char test_strings[5][13] = {"0,4,6,7", "4,6,7", "test,0,4", "9,8,7", "4,6,7,test"};
  uint32_t tmp_num = 0xFFFF;
  RawAddress addr;
  esco_codec_t codec;
};

class BtaAgSwbTest : public BtaAgTest {
protected:
  void SetUp() override { BtaAgTest::SetUp(); }
  void TearDown() override { BtaAgTest::TearDown(); }
};

TEST_F(BtaAgSwbTest, parse_qac_at_command) {
  tBTA_AG_PEER_CODEC codec = bta_ag_parse_qac((char*)test_strings[0]);
  codec = bta_ag_parse_qac((char*)test_strings[0]);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q1_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q2_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q3_MASK);

  codec = bta_ag_parse_qac((char*)test_strings[1]);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q1_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q2_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q3_MASK);

  codec = bta_ag_parse_qac((char*)test_strings[2]);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q1_MASK);

  codec = bta_ag_parse_qac((char*)test_strings[3]);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q3_MASK);

  codec = bta_ag_parse_qac((char*)test_strings[4]);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q1_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q2_MASK);
  ASSERT_TRUE(codec & BTA_AG_SCO_APTX_SWB_SETTINGS_Q3_MASK);
}

TEST_F(BtaAgSwbTest, enable_swb_codec) {
  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  ASSERT_TRUE(get_swb_codec_status(bluetooth::headset::BTHF_SWB_CODEC_VENDOR_APTX, addr));
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

class BtaAgActTest : public BtaAgTest {
protected:
  void SetUp() override { BtaAgTest::SetUp(); }
  void TearDown() override { BtaAgTest::TearDown(); }
};

TEST_F(BtaAgActTest, set_codec_q0_success) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  const tBTA_AG_DATA data = {.api_setcodec.codec = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0};

  bta_ag_cb.p_cback = [](tBTA_AG_EVT /*event*/, tBTA_AG* p_data) {
    tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
    ASSERT_EQ(val->num, BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
    ASSERT_EQ(val->hdr.status, BTA_AG_SUCCESS);
  };

  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
  p_scb->sco_codec = BTM_SCO_CODEC_NONE;
  p_scb->codec_updated = false;

  bta_ag_setcodec(p_scb, data);
  ASSERT_EQ(p_scb->sco_codec, BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
}

TEST_F(BtaAgActTest, set_codec_q1_fail_unsupported) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  const tBTA_AG_DATA data = {.api_setcodec.codec = BTA_AG_SCO_APTX_SWB_SETTINGS_Q1};

  ASSERT_TRUE(enable_aptx_voice_property(true));

  bta_ag_cb.p_cback = [](tBTA_AG_EVT /*event*/, tBTA_AG* p_data) {
    tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
    ASSERT_EQ(val->num, BTA_AG_SCO_APTX_SWB_SETTINGS_Q1);
    ASSERT_EQ(val->hdr.status, BTA_AG_FAIL_RESOURCES);
  };

  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
  p_scb->sco_codec = BTM_SCO_CODEC_NONE;
  p_scb->codec_updated = false;

  bta_ag_setcodec(p_scb, data);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

TEST_F(BtaAgActTest, rfc_fail_releases_rfcomm_port) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->serv_handle[0] = 12;
  p_scb->serv_handle[1] = 18;
  p_scb->state = BTA_AG_OPENING_ST;
  p_scb->reg_services |= (1 << BTA_HSP_SERVICE_ID) | (1 << BTA_HFP_SERVICE_ID);

  bta_ag_rfc_fail(p_scb, tBTA_AG_DATA::kEmpty);

  ASSERT_EQ(2, get_func_call_count("RFCOMM_RemoveServer"));
  ASSERT_EQ(2, get_func_call_count("RFCOMM_CreateConnectionWithSecurity"));

  ASSERT_EQ(p_scb->state, BTA_AG_INIT_ST);
}

class BtaAgCmdTest : public BtaAgTest {
protected:
  void SetUp() override { BtaAgTest::SetUp(); }
  void TearDown() override { BtaAgTest::TearDown(); }
};

TEST_F(BtaAgCmdTest, check_flag_guarding_with_prop) {
  ASSERT_TRUE(enable_aptx_voice_property(false));
  ASSERT_FALSE(is_hfp_aptx_voice_enabled());

  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_TRUE(is_hfp_aptx_voice_enabled());
}

TEST_F(BtaAgCmdTest, at_hfp_cback__qac_ev_codec_disabled) {
  tBTA_AG_SCB p_scb = {
          .peer_addr = addr,
          .app_id = 0,
  };

  ASSERT_TRUE(enable_aptx_voice_property(false));

  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QAC_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  ASSERT_FALSE(p_scb.codec_updated);
  ASSERT_FALSE(p_scb.is_aptx_swb_codec);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdTest, at_hfp_cback__qac_ev_codec_enabled) {
  tBTA_AG_SCB p_scb = {
          .peer_addr = addr, .app_id = 0, .peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK};

  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QAC_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  ASSERT_TRUE(p_scb.codec_updated);
  ASSERT_TRUE(p_scb.is_aptx_swb_codec);
  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(p_scb.sco_codec, BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

TEST_F(BtaAgCmdTest, at_hfp_cback__qcs_ev_codec_disabled) {
  tBTA_AG_SCB p_scb = {
          .peer_addr = addr,
          .app_id = 0,
  };

  ASSERT_TRUE(enable_aptx_voice_property(false));

  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QCS_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  ASSERT_FALSE(p_scb.codec_updated);
  ASSERT_FALSE(p_scb.is_aptx_swb_codec);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdTest, at_hfp_cback__qcs_ev_codec_q0_enabled) {
  EXPECT_CALL(btm_client_interface_, BTM_SetEScoMode(_)).WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));
  EXPECT_CALL(btm_client_interface_, BTM_CreateSco(_, _, _, _, _, _))
          .WillOnce(Return(tBTM_STATUS::BTM_CMD_STARTED));

  tBTA_AG_SCB p_scb = {.peer_addr = addr,
                       .sco_idx = BTM_INVALID_SCO_INDEX,
                       .app_id = 0,
                       .sco_codec = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0,
                       .is_aptx_swb_codec = true};

  ASSERT_TRUE(enable_aptx_voice_property(true));

  bta_ag_cb.sco.state = BTA_AG_SCO_CODEC_ST;
  bta_ag_api_set_active_device(addr);
  ASSERT_EQ(addr, bta_ag_get_active_device());

  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QCS_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(1, get_func_call_count("esco_parameters_for_codec"));
  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  ASSERT_EQ(this->codec, ESCO_CODEC_SWB_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

TEST_F(BtaAgCmdTest, handle_swb_at_event__qcs_ev_codec_q1_fallback_to_q0) {
  EXPECT_CALL(btm_client_interface_, BTM_SetEScoMode(_)).WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));
  EXPECT_CALL(btm_client_interface_, BTM_CreateSco(_, _, _, _, _, _))
          .WillOnce(Return(tBTM_STATUS::BTM_CMD_STARTED));

  tBTA_AG_SCB p_scb = {.peer_addr = addr,
                       .sco_idx = BTM_INVALID_SCO_INDEX,
                       .app_id = 0,
                       .sco_codec = BTA_AG_SCO_APTX_SWB_SETTINGS_Q1,
                       .codec_fallback = false,
                       .is_aptx_swb_codec = true};

  ASSERT_TRUE(enable_aptx_voice_property(true));

  bta_ag_cb.sco.state = BTA_AG_SCO_CODEC_ST;
  bta_ag_api_set_active_device(addr);
  ASSERT_EQ(addr, bta_ag_get_active_device());

  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QCS_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q1);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(1, get_func_call_count("esco_parameters_for_codec"));
  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  ASSERT_EQ(this->codec, ESCO_CODEC_SWB_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

namespace {
uint8_t data[3] = {1, 2, 3};
}  // namespace

class BtaAgScoTest : public BtaAgTest {
protected:
  void SetUp() override { BtaAgTest::SetUp(); }

  void TearDown() override {
    reset_mock_btm_client_interface();
    BtaAgTest::TearDown();
  }
};

TEST_F(BtaAgScoTest, codec_negotiate__aptx_state_on) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->app_id = 0;
  p_scb->peer_addr = addr;
  p_scb->codec_negotiation_timer = alarm_new("bta_ag.scb_codec_negotiation_timer");
  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK;
  p_scb->is_aptx_swb_codec = false;

  EXPECT_CALL(btm_client_interface_, BTM_ReadRemoteFeatures(_)).WillOnce(Return(data));

  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(true, addr));
  bta_ag_codec_negotiate(p_scb);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(p_scb->is_aptx_swb_codec);
  ASSERT_EQ(p_scb->sco_codec, BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));

  bta_ag_deregister(p_scb, tBTA_AG_DATA::kEmpty);
}

TEST_F(BtaAgScoTest, codec_negotiate__aptx_state_off) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->app_id = 0;
  p_scb->peer_addr = addr;
  p_scb->codec_negotiation_timer = alarm_new("bta_ag.scb_codec_negotiation_timer");
  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK;
  p_scb->is_aptx_swb_codec = true;

  EXPECT_CALL(btm_client_interface_, BTM_ReadRemoteFeatures(_)).WillOnce(Return(data));

  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_EQ(BtifStatus(), enable_aptx_swb_codec(false, addr));
  bta_ag_codec_negotiate(p_scb);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_FALSE(p_scb->is_aptx_swb_codec);
  ASSERT_EQ(p_scb->sco_codec, BTM_SCO_CODEC_MSBC);
  ASSERT_TRUE(enable_aptx_voice_property(false));

  bta_ag_deregister(p_scb, tBTA_AG_DATA::kEmpty);
}

TEST_F(BtaAgScoTest, codec_negotiate__aptx_disabled) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->app_id = 0;
  p_scb->peer_addr = addr;
  p_scb->codec_negotiation_timer = alarm_new("bta_ag.scb_codec_negotiation_timer");
  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK;
  p_scb->is_aptx_swb_codec = true;
  p_scb->codec_updated = true;

  EXPECT_CALL(btm_client_interface_, BTM_ReadRemoteFeatures(_)).WillOnce(Return(data));

  ASSERT_TRUE(enable_aptx_voice_property(false));
  ASSERT_EQ(BtifStatus(FAIL), enable_aptx_swb_codec(false, addr));
  bta_ag_codec_negotiate(p_scb);
  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(0, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_FALSE(p_scb->codec_updated);

  bta_ag_deregister(p_scb, tBTA_AG_DATA::kEmpty);
}

TEST_F_WITH_FLAGS(BtaAgScoTest, ag_sco_shutdown,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                        sco_state_machine_cleanup))) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  bta_ag_cb.sco.state = BTA_AG_SCO_OPENING_ST;
  bta_ag_cb.sco.p_curr_scb = p_scb;
  ASSERT_NE(bta_ag_cb.sco.p_curr_scb, nullptr);
  bta_ag_sco_shutdown(p_scb, tBTA_AG_DATA::kEmpty);
  ASSERT_EQ(bta_ag_cb.sco.state, BTA_AG_SCO_SHUTDOWN_ST);
  ASSERT_EQ(bta_ag_cb.sco.p_curr_scb, nullptr);
}

class BtaAgCmdAtHfpCbackTest : public BtaAgTest {
protected:
  void SetUp() override {
    BtaAgTest::SetUp();
    p_scb = &bta_ag_cb.scb[0];
    p_scb->in_use = true;
    p_scb->app_id = 0;
    p_scb->peer_addr = addr;
    p_scb->conn_service = BTA_AG_HFP;
    bta_ag_cb.p_cback = [](tBTA_AG_EVT event, tBTA_AG* p_data) { event_cb.Call(event, p_data); };
  }

  void TearDown() override {
    p_scb->in_use = false;
    BtaAgTest::TearDown();
    ::testing::Mock::VerifyAndClearExpectations(&event_cb);
  }

  tBTA_AG_SCB* p_scb;
  inline static MockFunction<void(tBTA_AG_EVT, tBTA_AG*)> event_cb;
};

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_a_evt) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_A_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_A_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_spk_evt) {
  char p_arg[] = "10";
  EXPECT_CALL(event_cb, Call(BTA_AG_SPK_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->num, 10U);
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_SPK_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 10);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_mic_evt) {
  char p_arg[] = "8";
  EXPECT_CALL(event_cb, Call(BTA_AG_MIC_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->num, 8U);
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_MIC_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 8);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_chup_evt) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CHUP_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CHUP_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_cbc_evt) {
  char p_arg[] = "50";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CBC_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->num, 50U);
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CBC_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 50);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bldn_evt) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BLDN_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BLDN_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_d_evt_mem_dial_ok) {
  char p_arg[] = ">12345";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_D_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_D_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_d_evt_mem_dial_fail) {
  char p_arg[] = ">123a5";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_D_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_d_evt_voip_ok) {
  char p_arg[] = "V12345";
  p_scb->peer_features |= BTA_AG_PEER_FEAT_VOIP;
  p_scb->features |= BTA_AG_FEAT_VOIP;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_D_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_D_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_d_evt_voip_fail) {
  char p_arg[] = "V12345";
  p_scb->peer_features &= ~BTA_AG_PEER_FEAT_VOIP;
  p_scb->features &= ~BTA_AG_FEAT_VOIP;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_D_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_d_evt_dial_ok) {
  char p_arg[] = "12345#*+";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_D_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_D_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_d_evt_dial_fail) {
  char p_arg[] = "12345g";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_D_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

extern std::function<int(uint8_t, RawAddress*, uint16_t*)> PORT_CheckConnection_Fn;
extern std::function<int(uint8_t)> RFCOMM_RemoveConnection_Fn;
extern std::function<int(uint8_t)> RFCOMM_RemoveServer_Fn;

class BtaAgRfcTest : public BtaAgTest {
protected:
  void SetUp() override {
    BtaAgTest::SetUp();
    PORT_CheckConnection_Fn = {};
    RFCOMM_RemoveConnection_Fn = {};
    RFCOMM_RemoveServer_Fn = {};
  }
  void TearDown() override {
    PORT_CheckConnection_Fn = {};
    RFCOMM_RemoveConnection_Fn = {};
    RFCOMM_RemoveServer_Fn = {};
    if (original_SDP_InitDiscoveryDb) {
      auto sdp_api = const_cast<bluetooth::legacy::stack::sdp::tSdpApi*>(
              bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api());
      sdp_api->SDP_InitDiscoveryDb = original_SDP_InitDiscoveryDb;
      original_SDP_InitDiscoveryDb = nullptr;
    }
    if (original_SDP_ServiceSearchAttributeRequest) {
      auto sdp_api = const_cast<bluetooth::legacy::stack::sdp::tSdpApi*>(
              bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api());
      sdp_api->SDP_ServiceSearchAttributeRequest = original_SDP_ServiceSearchAttributeRequest;
      original_SDP_ServiceSearchAttributeRequest = nullptr;
    }
    BtaAgTest::TearDown();
  }

  bool (*original_SDP_InitDiscoveryDb)(tSDP_DISCOVERY_DB*, uint32_t, uint16_t,
                                       const bluetooth::Uuid*, uint16_t, const uint16_t*) = nullptr;
  bool (*original_SDP_ServiceSearchAttributeRequest)(const RawAddress&, tSDP_DISCOVERY_DB*,
                                                     tSDP_DISC_CMPL_CB*) = nullptr;
};

TEST_F_WITH_FLAGS(BtaAgRfcTest, rfc_acp_open__setup_and_open_no_collision,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                        hfp_ag_rfc_race_condition_random_timer))) {
  auto sdp_api = const_cast<bluetooth::legacy::stack::sdp::tSdpApi*>(
          bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api());
  original_SDP_InitDiscoveryDb = sdp_api->SDP_InitDiscoveryDb;
  original_SDP_ServiceSearchAttributeRequest = sdp_api->SDP_ServiceSearchAttributeRequest;

  sdp_api->SDP_InitDiscoveryDb = [](tSDP_DISCOVERY_DB*, uint32_t, uint16_t, const bluetooth::Uuid*,
                                    uint16_t, const uint16_t*) -> bool { return true; };
  sdp_api->SDP_ServiceSearchAttributeRequest =
          [](const RawAddress&, tSDP_DISCOVERY_DB*, tSDP_DISC_CMPL_CB*) -> bool { return true; };

  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->in_use = true;
  p_scb->serv_handle[1] = 100;
  p_scb->reg_services = BTA_HFP_SERVICE_MASK;
  p_scb->state = BTA_AG_OPENING_ST;
  p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");
  p_scb->collision_timer = alarm_new("bta_ag.scb_collision_timer");

  tBTA_AG_DATA data = {};
  data.rfc.port_handle = 100;

  // Mock PORT_CheckConnection
  PORT_CheckConnection_Fn = [&](uint8_t handle, RawAddress* bd_addr, uint16_t* p_lcid) {
    if (handle == 100) {
      *bd_addr = addr;
      if (p_lcid) *p_lcid = 1;
      return 0; // PORT_SUCCESS
    }
    return 1; // PORT_ERR
  };

  bta_ag_rfc_acp_open(p_scb, data);

  ASSERT_EQ(p_scb->peer_addr, addr);
  ASSERT_EQ(p_scb->conn_handle, 100);
  ASSERT_EQ(p_scb->conn_service, BTA_AG_HFP);

  alarm_free(p_scb->ring_timer);
  alarm_free(p_scb->collision_timer);
}

TEST_F_WITH_FLAGS(BtaAgRfcTest, rfc_acp_open__collision_timer,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                        hfp_ag_rfc_race_condition_random_timer))) {
  // Setup Outgoing SCB
  tBTA_AG_SCB* p_scb_outgoing = &bta_ag_cb.scb[0];
  p_scb_outgoing->in_use = true;
  p_scb_outgoing->peer_addr = addr;
  p_scb_outgoing->conn_handle = 200;

  // Setup Incoming SCB
  tBTA_AG_SCB* p_scb_incoming = &bta_ag_cb.scb[1];
  p_scb_incoming->in_use = true;
  p_scb_incoming->state = BTA_AG_OPENING_ST;
  p_scb_incoming->collision_timer = alarm_new("test_collision_timer");
  p_scb_incoming->ring_timer = alarm_new("test_ring_timer");
  p_scb_incoming->serv_handle[1] = 30; // HFP
  p_scb_incoming->reg_services = BTA_HFP_SERVICE_MASK;

  // Incoming data
  tBTA_AG_DATA data = {};
  data.rfc.port_handle = 30;

  // Mock PORT_CheckConnection
  PORT_CheckConnection_Fn = [&](uint8_t handle, RawAddress* bd_addr, uint16_t* p_lcid) {
    if (handle == 30) {
      *bd_addr = addr; // Collision
      if (p_lcid) *p_lcid = 2;
      return 0;
    }
    return 1;
  };

  // Capture timer callback
  alarm_callback_t stored_cb = nullptr;
  void* stored_data = nullptr;
  test::mock::osi_alarm::alarm_set_on_mloop.body =
      [&](alarm_t* /* alarm */, uint64_t /* interval */, alarm_callback_t cb,
          void* data) {
        stored_cb = cb;
        stored_data = data;
      };

  bta_ag_rfc_acp_open(p_scb_incoming, data);

  // Verify Timer Set
  ASSERT_TRUE(stored_cb != nullptr);
  ASSERT_EQ(p_scb_incoming->peer_addr, addr);

  // Mock RFCOMM_RemoveConnection
  bool remove_called = false;
  RFCOMM_RemoveConnection_Fn = [&](uint8_t handle) {
    if (handle == 200) remove_called = true;
    return 0;
  };

  // Trigger Callback (Collision resolution)
  stored_cb(stored_data);

  ASSERT_TRUE(remove_called); // Outgoing closed
  ASSERT_EQ(p_scb_incoming->conn_handle, 30); // Incoming opened
  ASSERT_EQ(p_scb_incoming->conn_service, 1); // BTA_AG_HFP

  alarm_free(p_scb_incoming->collision_timer);
  alarm_free(p_scb_incoming->ring_timer);
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_chld_evt_test) {
  char p_arg[] = "";
  p_scb->peer_version = 0x0105;  // HFP_VERSION_1_5
  p_scb->features |= BTA_AG_FEAT_ECC;
  p_scb->peer_features |= BTA_AG_PEER_FEAT_ECC;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CHLD_EVT, _)).Times(0);
  EXPECT_CALL(event_cb, Call(::testing::Ne(BTA_AG_AT_CHLD_EVT), _)).Times(::testing::AnyNumber());

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CHLD_EVT, BTA_AG_AT_TEST, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_chld_evt_set_ok) {
  char p_arg[] = "1";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CHLD_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CHLD_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_chld_evt_set_fail) {
  char p_arg[] = "a";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CHLD_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bind_evt_set_ok) {
  char p_arg[] = "1,2";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BIND_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIND_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(p_scb->peer_hf_indicators[0].ind_id, 1);
  ASSERT_EQ(p_scb->peer_hf_indicators[1].ind_id, 2);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bind_evt_set_fail) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIND_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bind_evt_test) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIND_EVT, BTA_AG_AT_TEST, p_arg, p_arg + strlen(p_arg), 0);

  // bta_ag_local_hf_ind_cfg has 3 supported indicators by default
  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_biev_evt_ok) {
  char p_arg[] = "1,1";
  p_scb->local_hf_indicators[0].ind_id = 1;
  p_scb->local_hf_indicators[0].is_supported = true;
  p_scb->local_hf_indicators[0].is_enable = true;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BIEV_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->lidx, 1U);
            ASSERT_EQ(val->num, 1U);
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIEV_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_biev_evt_fail) {
  char p_arg[] = "1,5";  // value out of range
  p_scb->local_hf_indicators[0].ind_id = 1;
  p_scb->local_hf_indicators[0].is_supported = true;
  p_scb->local_hf_indicators[0].is_enable = true;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIEV_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_cind_evt_test) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CIND_EVT, BTA_AG_AT_TEST, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_cind_evt_read) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CIND_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CIND_EVT, BTA_AG_AT_READ, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_vts_evt_ok) {
  char p_arg[] = "1";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_VTS_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_VTS_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_vts_evt_fail) {
  char p_arg[] = "12";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_VTS_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_binp_evt_ok) {
  char p_arg[] = "1";
  p_scb->features |= BTA_AG_FEAT_VTAG;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BINP_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BINP_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_binp_evt_fail) {
  char p_arg[] = "1";
  p_scb->features &= ~BTA_AG_FEAT_VTAG;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BINP_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bvra_evt_ok) {
  char p_arg[] = "1";
  p_scb->features |= BTA_AG_FEAT_VREC;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BVRA_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BVRA_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bvra_evt_fail) {
  char p_arg[] = "1";
  p_scb->features &= ~BTA_AG_FEAT_VREC;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BVRA_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_nrec_evt_ok) {
  char p_arg[] = "1";
  p_scb->features |= BTA_AG_FEAT_ECNR;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_NREC_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_NREC_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_TRUE(p_scb->nrec_enabled);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_nrec_evt_fail) {
  char p_arg[] = "1";
  p_scb->features &= ~BTA_AG_FEAT_ECNR;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_NREC_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_btrh_evt_set_ok) {
  char p_arg[] = "1";
  p_scb->features |= BTA_AG_FEAT_BTRH;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BTRH_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BTRH_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_btrh_evt_read_ok) {
  char p_arg[] = "";
  p_scb->features |= BTA_AG_FEAT_BTRH;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BTRH_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->num, (uint32_t)BTA_AG_BTRH_READ);
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BTRH_EVT, BTA_AG_AT_READ, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_btrh_evt_fail) {
  char p_arg[] = "1";
  p_scb->features &= ~BTA_AG_FEAT_BTRH;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BTRH_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 1);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_cops_evt_set) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_COPS_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_cops_evt_read) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_COPS_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_COPS_EVT, BTA_AG_AT_READ, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bia_evt_ok) {
  char p_arg[] = "1,1,0";
  p_scb->bia_masked_out = 0;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BIA_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->num, (uint32_t)(1 << 3));
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIA_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(p_scb->bia_masked_out, (uint32_t)(1 << 3));
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bia_evt_fail) {
  char p_arg[] = "1,2,0";  // invalid char '2'
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BIA_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_cnum_evt) {
  char p_arg[] = "";
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CNUM_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CNUM_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_clcc_evt_ok) {
  char p_arg[] = "";
  p_scb->features |= BTA_AG_FEAT_ECS;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_CLCC_EVT, _));

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CLCC_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_clcc_evt_fail) {
  char p_arg[] = "";
  p_scb->features &= ~BTA_AG_FEAT_ECS;
  EXPECT_CALL(event_cb, Call(_, _)).Times(0);

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_CLCC_EVT, 0, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}

TEST_F(BtaAgCmdAtHfpCbackTest, bta_ag_bac_evt) {
  char p_arg[] = "1,2";  // CVSD, mSBC
  p_scb->peer_features |= BTA_AG_PEER_FEAT_CODEC;
  p_scb->features |= BTA_AG_FEAT_CODEC;
  EXPECT_CALL(event_cb, Call(BTA_AG_AT_BAC_EVT, _))
          .WillOnce([](tBTA_AG_EVT /* event */, tBTA_AG* p_data) {
            tBTA_AG_VAL* val = (tBTA_AG_VAL*)p_data;
            ASSERT_EQ(val->num, (uint32_t)(BTM_SCO_CODEC_CVSD | BTM_SCO_CODEC_MSBC));
          });

  bta_ag_at_hfp_cback(p_scb, BTA_AG_AT_BAC_EVT, BTA_AG_AT_SET, p_arg, p_arg + strlen(p_arg), 0);

  ASSERT_TRUE(p_scb->received_at_bac);
  ASSERT_TRUE(p_scb->codec_updated);
  ASSERT_EQ(p_scb->peer_codecs, BTM_SCO_CODEC_CVSD | BTM_SCO_CODEC_MSBC);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // OK
}

class BtaAgCmdResultTest : public BtaAgTest {
protected:
  void SetUp() override {
    BtaAgTest::SetUp();
    p_scb = &bta_ag_cb.scb[0];
    *p_scb = {};
    p_scb->in_use = true;
    p_scb->app_id = 0;
    p_scb->peer_addr = addr;
    p_scb->conn_service = BTA_AG_HFP;
    bta_ag_cb.p_cback = [](tBTA_AG_EVT event, tBTA_AG* p_data) { event_cb.Call(event, p_data); };
  }

  void TearDown() override {
    p_scb->in_use = false;
    BtaAgTest::TearDown();
    ::testing::Mock::VerifyAndClearExpectations(&event_cb);
  }

  tBTA_AG_SCB* p_scb;
  inline static MockFunction<void(tBTA_AG_EVT, tBTA_AG*)> event_cb;
};

TEST_F(BtaAgCmdResultTest, bta_ag_spk_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_SPK_RES, .data = {.num = 10}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdResultTest, bta_ag_mic_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_MIC_RES, .data = {.num = 8}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdResultTest, bta_ag_in_call_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_IN_CALL_RES}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "1234567890");
  p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));  // ring_timer
  alarm_free(p_scb->ring_timer);
}

TEST_F(BtaAgCmdResultTest, bta_ag_in_call_conn_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_IN_CALL_CONN_RES,
                                            .data = {.audio_handle = bta_ag_scb_to_idx(p_scb)}}};
  p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_ACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_NONE);
  alarm_free(p_scb->ring_timer);
}

TEST_F(BtaAgCmdResultTest, bta_ag_out_call_orig_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_OUT_CALL_ORIG_RES,
                                            .data = {.audio_handle = bta_ag_scb_to_idx(p_scb)}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_INACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_OUTGOING);
}

TEST_F(BtaAgCmdResultTest, bta_ag_end_call_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_END_CALL_RES}};
  p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_INACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_NONE);
  alarm_free(p_scb->ring_timer);
}

TEST_F(BtaAgCmdResultTest, bta_ag_inband_ring_res) {
  const tBTA_AG_DATA data = {
          .api_result = {.result = BTA_AG_INBAND_RING_RES, .data = {.state = true}}};

  bta_ag_result(p_scb, data);

  ASSERT_TRUE(p_scb->inband_enabled);
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdResultTest, bta_ag_unat_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_UNAT_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "AT+X=1");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_in_call_held_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_IN_CALL_HELD_RES}};
  p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_ACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_NONE);
  alarm_free(p_scb->ring_timer);
}

TEST_F(BtaAgCmdResultTest, bta_ag_out_call_alert_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_OUT_CALL_ALERT_RES,
                                            .data = {.audio_handle = bta_ag_scb_to_idx(p_scb)}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_INACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_ALERTING);
}

TEST_F(BtaAgCmdResultTest, bta_ag_multi_call_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_MULTI_CALL_RES,
                                            .data = {.audio_handle = bta_ag_scb_to_idx(p_scb)}}};

  bta_ag_result(p_scb, data);

  // No state change, just opens sco
}

TEST_F(BtaAgCmdResultTest, bta_ag_out_call_conn_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_OUT_CALL_CONN_RES,
                                            .data = {.audio_handle = bta_ag_scb_to_idx(p_scb)}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_ACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_NONE);
}

TEST_F(BtaAgCmdResultTest, bta_ag_call_cancel_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_CALL_CANCEL_RES}};
  p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_INACTIVE);
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_NONE);
  alarm_free(p_scb->ring_timer);
}

TEST_F(BtaAgCmdResultTest, bta_ag_cind_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_CIND_RES}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "1,2,3,4,5,6,7");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(p_scb->call_ind, 1);
  ASSERT_EQ(p_scb->callsetup_ind, 2);
  ASSERT_EQ(p_scb->service_ind, 3);
  ASSERT_EQ(p_scb->signal_ind, 4);
  ASSERT_EQ(p_scb->roam_ind, 5);
  ASSERT_EQ(p_scb->battchg_ind, 6);
  ASSERT_EQ(p_scb->callheld_ind, 7);
  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_binp_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_BINP_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "12345");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_cnum_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_CNUM_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "12345");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_clcc_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_CLCC_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "1");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_cops_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_COPS_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "T-Mobile");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_call_wait_res) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_CALL_WAIT_RES}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "1234567890");
  p_scb->ccwa_enabled = true;

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(p_scb->callsetup_ind, BTA_AG_CALLSETUP_INCOMING);
}

TEST_F(BtaAgCmdResultTest, bta_ag_ind_res) {
  const tBTA_AG_DATA data = {
          .api_result = {.result = BTA_AG_IND_RES,
                         .data = {.ind = {.id = BTA_AG_IND_CALL, .value = BTA_AG_CALL_ACTIVE}}}};
  p_scb->cmer_enabled = true;

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(p_scb->call_ind, BTA_AG_CALL_ACTIVE);
}

TEST_F(BtaAgCmdResultTest, bta_ag_bvra_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_BVRA_RES, .data = {.state = true}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdResultTest, bta_ag_btrh_res) {
  const tBTA_AG_DATA data = {
          .api_result = {
                  .result = BTA_AG_BTRH_RES,
                  .data = {.num = BTA_AG_BTRH_SET_HOLD, .ok_flag = BTA_AG_OK_DONE},
          }};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdResultTest, bta_ag_bind_res) {
  const tBTA_AG_DATA data = {
          .api_result = {.result = BTA_AG_BIND_RES, .data = {.ind = {.id = 1, .on_demand = true}}}};
  p_scb->local_hf_indicators[0].ind_id = 1;
  p_scb->local_hf_indicators[0].is_enable = false;
  p_scb->peer_hf_indicators[0].ind_id = 1;

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_TRUE(p_scb->local_hf_indicators[0].is_enable);
}

class BtaAgCmdHspResultTest : public BtaAgTest {
protected:
  void SetUp() override {
    BtaAgTest::SetUp();
    p_scb = &bta_ag_cb.scb[0];
    *p_scb = {};
    p_scb->in_use = true;
    p_scb->app_id = 0;
    p_scb->peer_addr = addr;
    p_scb->conn_service = BTA_AG_HSP;
    bta_ag_cb.p_cback = [](tBTA_AG_EVT event, tBTA_AG* p_data) { event_cb.Call(event, p_data); };
    p_scb->ring_timer = alarm_new("bta_ag.scb_ring_timer");
  }

  void TearDown() override {
    alarm_free(p_scb->ring_timer);
    p_scb->in_use = false;
    BtaAgTest::TearDown();
    ::testing::Mock::VerifyAndClearExpectations(&event_cb);
  }

  tBTA_AG_SCB* p_scb;
  inline static MockFunction<void(tBTA_AG_EVT, tBTA_AG*)> event_cb;
};

TEST_F(BtaAgCmdHspResultTest, bta_ag_spk_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_SPK_RES, .data = {.num = 10}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_mic_res) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_MIC_RES, .data = {.num = 8}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_in_call_res_sco_already_open) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_IN_CALL_RES}};
  p_scb->sco_idx = 0;  // sco is open

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // For RING
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_in_call_res_no_inband_ring) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_IN_CALL_RES}};
  p_scb->sco_idx = BTM_INVALID_SCO_INDEX;
  p_scb->inband_enabled = false;

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // For RING
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_in_call_res_nosco_feature) {
  const tBTA_AG_DATA data = {.api_result = {.result = BTA_AG_IN_CALL_RES}};
  p_scb->sco_idx = BTM_INVALID_SCO_INDEX;
  p_scb->inband_enabled = true;
  p_scb->features |= BTA_AG_FEAT_NOSCO;

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // For RING
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_inband_ring_res) {
  const tBTA_AG_DATA data = {
          .api_result = {.result = BTA_AG_INBAND_RING_RES, .data = {.state = true}}};

  bta_ag_result(p_scb, data);

  ASSERT_TRUE(p_scb->inband_enabled);
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_unat_res_ok_done) {
  tBTA_AG_DATA data{.api_result = {.result = BTA_AG_UNAT_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};
  snprintf(data.api_result.data.str, BTA_AG_AT_MAX_LEN, "AT+X=1");

  bta_ag_result(p_scb, data);

  ASSERT_EQ(2, get_func_call_count("PORT_WriteData"));  // result + OK
}

TEST_F(BtaAgCmdHspResultTest, bta_ag_unat_res_ok_error) {
  const tBTA_AG_DATA data = {
          .api_result = {.result = BTA_AG_UNAT_RES, .data = {.ok_flag = BTA_AG_OK_DONE}}};

  bta_ag_result(p_scb, data);

  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));  // ERROR
}
