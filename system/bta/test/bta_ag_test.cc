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
#include "bta/include/bta_ag_swb_aptx.h"
#include "hci/controller_interface_mock.h"
#include "stack/include/btm_status.h"
#include "test/common/main_handler.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_bta_sys_main.h"
#include "test/mock/mock_device_esco_parameters.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_osi_alarm.h"
#include "test/mock/mock_stack_acl.h"
#include "test/mock/mock_stack_btm_interface.h"

#define TEST_BT com::android::bluetooth::flags

using namespace bluetooth;

namespace {

bool bta_ag_hdl_event(const BT_HDR_RIGID* /*p_msg*/) { return true; }
void BTA_AgDisable() { bta_sys_deregister(BTA_ID_AG); }

const tBTA_SYS_REG bta_ag_reg = {bta_ag_hdl_event, BTA_AgDisable};

}  // namespace

const std::string kBtCodecAptxVoiceEnabled = "bluetooth.hfp.codec_aptx_voice.enabled";

static bool enable_aptx_voice_property(bool enable) {
  const std::string value = enable ? "true" : "false";
  return android::base::SetProperty(kBtCodecAptxVoiceEnabled, value);
}

class BtaAgTest : public testing::Test {
protected:
  void SetUp() override {
    reset_mock_function_count_map();
    fake_osi_ = std::make_unique<test::fake::FakeOsi>();
    bluetooth::hci::testing::mock_controller_ = &controller_;

    main_thread_start_up();
    post_on_bt_main([]() { log::info("Main thread started up"); });

    bta_sys_register(BTA_ID_AG, &bta_ag_reg);

    bta_ag_cb.p_cback = [](tBTA_AG_EVT /*event*/, tBTA_AG* /*p_data*/) {};
    RawAddress::FromString("00:11:22:33:44:55", addr);
    test::mock::device_esco_parameters::esco_parameters_for_codec.body = [this](esco_codec_t codec,
                                                                                bool /*offload*/) {
      this->codec = codec;
      return enh_esco_params_t{};
    };
  }
  void TearDown() override {
    test::mock::device_esco_parameters::esco_parameters_for_codec = {};
    bta_sys_deregister(BTA_ID_AG);
    post_on_bt_main([]() { log::info("Main thread shutting down"); });
    main_thread_shut_down();
    bluetooth::hci::testing::mock_controller_ = nullptr;
  }

  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
  const char test_strings[5][13] = {"0,4,6,7", "4,6,7", "test,0,4", "9,8,7", "4,6,7,test"};
  uint32_t tmp_num = 0xFFFF;
  RawAddress addr;
  esco_codec_t codec;
  bluetooth::hci::testing::MockControllerInterface controller_;
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
  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
  ASSERT_TRUE(get_swb_codec_status(bluetooth::headset::BTHF_SWB_CODEC_VENDOR_APTX, &addr));
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
  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
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
  reset_mock_btm_client_interface();
  mock_btm_client_interface.sco.BTM_SetEScoMode =
          [](enh_esco_params_t* /* p_params */) -> tBTM_STATUS {
    inc_func_call_count("BTM_SetEScoMode");
    return tBTM_STATUS::BTM_SUCCESS;
  };
  mock_btm_client_interface.sco.BTM_CreateSco =
          [](const RawAddress* /* remote_bda */, bool /* is_orig */, uint16_t /* pkt_types */,
             uint16_t* /* p_sco_inx */, tBTM_SCO_CB* /* p_conn_cb */,
             tBTM_SCO_CB* /* p_disc_cb */) -> tBTM_STATUS {
    inc_func_call_count("BTM_CreateSco");
    return tBTM_STATUS::BTM_CMD_STARTED;
  };

  tBTA_AG_SCB p_scb = {.peer_addr = addr,
                       .sco_idx = BTM_INVALID_SCO_INDEX,
                       .app_id = 0,
                       .sco_codec = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0,
                       .is_aptx_swb_codec = true};

  ASSERT_TRUE(enable_aptx_voice_property(true));

  bta_ag_cb.sco.state = BTA_AG_SCO_CODEC_ST;
  bta_ag_api_set_active_device(addr);
  ASSERT_EQ(addr, bta_ag_get_active_device());

  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QCS_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(1, get_func_call_count("esco_parameters_for_codec"));
  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
  ASSERT_EQ(1, get_func_call_count("BTM_SetEScoMode"));
  ASSERT_EQ(1, get_func_call_count("BTM_CreateSco"));
  ASSERT_EQ(this->codec, ESCO_CODEC_SWB_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

TEST_F(BtaAgCmdTest, handle_swb_at_event__qcs_ev_codec_q1_fallback_to_q0) {
  reset_mock_btm_client_interface();
  mock_btm_client_interface.sco.BTM_SetEScoMode =
          [](enh_esco_params_t* /*p_params*/) -> tBTM_STATUS {
    inc_func_call_count("BTM_SetEScoMode");
    return tBTM_STATUS::BTM_SUCCESS;
  };
  mock_btm_client_interface.sco.BTM_CreateSco =
          [](const RawAddress* /* remote_bda */, bool /* is_orig */, uint16_t /* pkt_types */,
             uint16_t* /* p_sco_inx */, tBTM_SCO_CB* /* p_conn_cb */,
             tBTM_SCO_CB* /* p_disc_cb */) -> tBTM_STATUS {
    inc_func_call_count("BTM_CreateSco");
    return tBTM_STATUS::BTM_CMD_STARTED;
  };

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

  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
  bta_ag_at_hfp_cback(&p_scb, BTA_AG_AT_QCS_EVT, 0, (char*)&test_strings[0][0],
                      (char*)&test_strings[0][12], BTA_AG_SCO_APTX_SWB_SETTINGS_Q1);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  ASSERT_EQ(1, get_func_call_count("esco_parameters_for_codec"));
  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
  ASSERT_EQ(1, get_func_call_count("BTM_SetEScoMode"));
  ASSERT_EQ(1, get_func_call_count("BTM_CreateSco"));
  ASSERT_EQ(this->codec, ESCO_CODEC_SWB_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

namespace {
uint8_t data[3] = {1, 2, 3};
}  // namespace

class BtaAgScoTest : public BtaAgTest {
protected:
  void SetUp() override {
    BtaAgTest::SetUp();
    reset_mock_btm_client_interface();
    mock_btm_client_interface.peer.BTM_ReadRemoteFeatures = [](const RawAddress& /*addr*/) {
      inc_func_call_count("BTM_ReadRemoteFeatures");
      return data;
    };
  }
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

  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(true, &addr));
  bta_ag_codec_negotiate(p_scb);
  ASSERT_EQ(1, get_func_call_count("BTM_ReadRemoteFeatures"));
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(p_scb->is_aptx_swb_codec);
  ASSERT_EQ(p_scb->sco_codec, BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

TEST_F(BtaAgScoTest, codec_negotiate__aptx_state_off) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->app_id = 0;
  p_scb->peer_addr = addr;
  p_scb->codec_negotiation_timer = alarm_new("bta_ag.scb_codec_negotiation_timer");
  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK;
  p_scb->is_aptx_swb_codec = true;

  ASSERT_TRUE(enable_aptx_voice_property(true));
  ASSERT_EQ(BT_STATUS_SUCCESS, enable_aptx_swb_codec(false, &addr));
  bta_ag_codec_negotiate(p_scb);
  ASSERT_EQ(1, get_func_call_count("BTM_ReadRemoteFeatures"));
  ASSERT_EQ(1, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_FALSE(p_scb->is_aptx_swb_codec);
  ASSERT_EQ(p_scb->sco_codec, BTM_SCO_CODEC_MSBC);
  ASSERT_TRUE(enable_aptx_voice_property(false));
}

TEST_F(BtaAgScoTest, codec_negotiate__aptx_disabled) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  p_scb->app_id = 0;
  p_scb->peer_addr = addr;
  p_scb->codec_negotiation_timer = alarm_new("bta_ag.scb_codec_negotiation_timer");
  p_scb->peer_codecs = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK;
  p_scb->is_aptx_swb_codec = true;
  p_scb->codec_updated = true;

  ASSERT_TRUE(enable_aptx_voice_property(false));
  ASSERT_EQ(BT_STATUS_FAIL, enable_aptx_swb_codec(false, &addr));
  bta_ag_codec_negotiate(p_scb);
  ASSERT_EQ(1, get_func_call_count("BTM_ReadRemoteFeatures"));
  ASSERT_EQ(0, get_func_call_count("PORT_WriteData"));
  ASSERT_EQ(0, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_FALSE(p_scb->codec_updated);
}

TEST_F_WITH_FLAGS(BtaAgScoTest, ag_sco_shutdown,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT, sco_state_machine_cleanup))) {
  tBTA_AG_SCB* p_scb = &bta_ag_cb.scb[0];
  bta_ag_cb.sco.state = BTA_AG_SCO_OPENING_ST;
  bta_ag_cb.sco.p_curr_scb = p_scb;
  ASSERT_NE(bta_ag_cb.sco.p_curr_scb, nullptr);
  bta_ag_sco_shutdown(p_scb, tBTA_AG_DATA::kEmpty);
  ASSERT_EQ(bta_ag_cb.sco.state, BTA_AG_SCO_SHUTDOWN_ST);
  ASSERT_EQ(bta_ag_cb.sco.p_curr_scb, nullptr);
}
