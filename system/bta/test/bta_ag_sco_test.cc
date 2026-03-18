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

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <string>
#include <tuple>
#include <vector>

#include "bta/ag/bta_ag_int.h"
#include "bta/include/bta_le_audio_api.h"
#include "hci/controller_mock.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "test/mock/mock_audio_hal_interface_hfp_client_interface.h"
#include "test/mock/mock_device_esco_parameters.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_osi_alarm.h"
#include "test/mock/mock_osi_properties.h"

using ::testing::Eq;
using ::testing::Field;
using ::testing::NiceMock;
using ::testing::Pointee;
using ::testing::Return;
using ::testing::Test;
using ::testing::TestWithParam;
using ::testing::ValuesIn;

tBTM_CB btm_cb;
LeAudioClient* LeAudioClient::Get() { return nullptr; }
bool LeAudioClient::IsLeAudioClientInStreaming() { return false; }

const RawAddress kRawAddress("11:22:33:44:55:66");

class BtaAgScoParameterSelectionTest
    : public TestWithParam<std::tuple<tBTA_AG_FEAT, tBTA_AG_PEER_FEAT, bool>> {
protected:
  void SetUp() override {
    test::mock::device_esco_parameters::esco_parameters_for_codec.body =
            [this](esco_codec_t codec, bool /* offload */) {
              this->codec = codec;
              return enh_esco_params_t{};
            };
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<NiceMock<bluetooth::hci::testing::MockController>>();
  }
  void TearDown() override {
    bluetooth::hci::testing::mock_controller_.reset();
    test::mock::device_esco_parameters::esco_parameters_for_codec = {};
  }
  esco_codec_t codec;
};

TEST_P(BtaAgScoParameterSelectionTest, create_sco_cvsd) {
  bta_ag_api_set_active_device(kRawAddress);

  const auto [feature, peer_feature, is_local] = GetParam();
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = feature,
          .peer_features = peer_feature,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD,
  };

  this->codec = ESCO_CODEC_UNKNOWN;
  bta_ag_create_sco(&scb, is_local);
  if ((scb.features & BTA_AG_FEAT_ESCO_S4) && (scb.peer_features & BTA_AG_PEER_FEAT_ESCO_S4)) {
    ASSERT_EQ(this->codec, ESCO_CODEC_CVSD_S4);
  } else {
    ASSERT_EQ(this->codec, ESCO_CODEC_CVSD_S3);
  }
  bta_clear_active_device();

  ASSERT_EQ(RawAddress::kEmpty, bta_ag_get_active_device());
}

TEST_P(BtaAgScoParameterSelectionTest, create_pending_sco_cvsd) {
  bta_ag_api_set_active_device(kRawAddress);

  const auto [feature, peer_feature, is_local] = GetParam();
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = feature,
          .peer_features = peer_feature,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD,
  };

  this->codec = ESCO_CODEC_UNKNOWN;
  if (is_local) {
    bta_ag_create_sco(&scb, true);
  } else {
    // empty data, not used in the function
    tBTM_ESCO_CONN_REQ_EVT_DATA data;
    bta_ag_sco_conn_rsp(&scb, &data);
  }
  if ((scb.features & BTA_AG_FEAT_ESCO_S4) && (scb.peer_features & BTA_AG_PEER_FEAT_ESCO_S4)) {
    ASSERT_EQ(this->codec, ESCO_CODEC_CVSD_S4);
  } else {
    ASSERT_EQ(this->codec, ESCO_CODEC_CVSD_S3);
  }
  bta_clear_active_device();

  ASSERT_EQ(RawAddress::kEmpty, bta_ag_get_active_device());
}

static std::vector<std::tuple<tBTA_AG_FEAT, tBTA_AG_PEER_FEAT, bool>>
BtaAgScoParameterSelectionTestParameters() {
  tBTA_AG_FEAT features[] = {0, BTA_AG_FEAT_ESCO_S4};
  tBTA_AG_PEER_FEAT peer_features[] = {0, BTA_AG_PEER_FEAT_ESCO_S4};
  bool is_local_or_orig[] = {false, true};
  std::vector<std::tuple<tBTA_AG_FEAT, tBTA_AG_PEER_FEAT, bool>> params;

  for (auto i : features) {
    for (auto j : peer_features) {
      for (auto k : is_local_or_orig) {
        params.push_back({i, j, k});
      }
    }
  }
  return params;
}

INSTANTIATE_TEST_SUITE_P(BtaAgScoParameterSelectionTests, BtaAgScoParameterSelectionTest,
                         ValuesIn(BtaAgScoParameterSelectionTestParameters()));

using bluetooth::audio::hfp::testing::mock_hfp_client_interface::mock_decode_;
using bluetooth::audio::hfp::testing::mock_hfp_client_interface::mock_encode_;
using bluetooth::audio::hfp::testing::mock_hfp_client_interface::mock_offload_;
using bluetooth::audio::hfp::testing::mock_hfp_client_interface::MockDecode;
using bluetooth::audio::hfp::testing::mock_hfp_client_interface::MockEncode;
using bluetooth::audio::hfp::testing::mock_hfp_client_interface::MockOffload;
const std::string kPropHfpSoftwarePathEnabled = "bluetooth.hfp.software_datapath.enabled";

class BtaAgScoupdateCodecParametersFromProviderInfoTest : public Test {
protected:
  void SetUp() override {
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockController>();
    mock_decode_ = std::make_unique<MockDecode>();
    mock_encode_ = std::make_unique<MockEncode>();
    mock_offload_ = std::make_unique<MockOffload>();
    set_mock_btm_client_interface(&btm_client_interface_);

    test::mock::osi_properties::osi_property_get_bool.body = [this](const char* key,
                                                                    bool default_value) {
      return key == kPropHfpSoftwarePathEnabled ? prop_hfp_software_path_enabled_return_
                                                : default_value;
    };
  }

  void TearDown() override {
    // Disable sco_managed_by_audio as the rest of the unittests expect this.
    bta_ag_set_is_sco_managed_by_audio(false);
    bta_ag_release_hfp_client_interface();

    bluetooth::hci::testing::mock_controller_.reset();
    mock_decode_.reset();
    mock_encode_.reset();
    mock_offload_.reset();

    test::mock::osi_properties::osi_property_get_bool = {};
    com_android_bluetooth_flags_reset_flags();

    reset_mock_btm_client_interface();
  }

  bool prop_hfp_software_path_enabled_return_;
  MockBtmClientInterface btm_client_interface_;
};

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, msbc_offload_path) {
  EXPECT_CALL(*mock_offload_, GetHfpScoConfig())
          .WillOnce(Return(std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config>{
                  {tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC,
                   {
                           .inputDataPath = ESCO_DATA_PATH_PCM,
                           .outputDataPath = ESCO_DATA_PATH_PCM,
                           .useControllerCodec = true,
                   }},
          }));

  EXPECT_CALL(*mock_decode_, StartSession()).Times(0);
  EXPECT_CALL(*mock_encode_, StartSession()).Times(0);
  EXPECT_CALL(*mock_offload_, StartSession()).Times(1);

  EXPECT_CALL(*mock_decode_, StopSession()).Times(0);
  EXPECT_CALL(*mock_encode_, StopSession()).Times(0);
  EXPECT_CALL(*mock_offload_, StopSession()).Times(1);

  EXPECT_CALL(*bluetooth::hci::testing::mock_controller_,
              IsSupported(bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))
          .WillOnce(Return(true));

  EXPECT_CALL(
          btm_client_interface_,
          BTM_SetEScoMode(AllOf(
                  Pointee(Field(&enh_esco_params_t::input_data_path, Eq(ESCO_DATA_PATH_PCM))),
                  Pointee(Field(&enh_esco_params_t::output_data_path, Eq(ESCO_DATA_PATH_PCM))))))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  prop_hfp_software_path_enabled_return_ = false;
  bta_ag_set_is_sco_managed_by_audio(true);  // This calls bta_ag_init_hfp_client_interface
  bta_ag_api_set_active_device(kRawAddress);
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_ESCO_S4,
          .peer_features = BTA_AG_FEAT_ESCO_S4,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .sco_codec = BTM_SCO_CODEC_MSBC,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE,
  };
  bta_ag_create_sco(&scb, /* is_local = */ true);
  bta_clear_active_device();

  ASSERT_TRUE(bta_ag_get_wbs_supported());
  ASSERT_EQ(scb.inuse_codec, tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC);
}

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, lc3_offload_path) {
  EXPECT_CALL(*mock_offload_, GetHfpScoConfig())
          .WillOnce(Return(std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config>{
                  {tBTA_AG_UUID_CODEC::UUID_CODEC_LC3,
                   {
                           .inputDataPath = ESCO_DATA_PATH_PCM,
                           .outputDataPath = ESCO_DATA_PATH_PCM,
                           .useControllerCodec = true,
                   }},
          }));

  EXPECT_CALL(*mock_decode_, StartSession()).Times(0);
  EXPECT_CALL(*mock_encode_, StartSession()).Times(0);
  EXPECT_CALL(*mock_offload_, StartSession()).Times(1);

  EXPECT_CALL(*mock_decode_, StopSession()).Times(0);
  EXPECT_CALL(*mock_encode_, StopSession()).Times(0);
  EXPECT_CALL(*mock_offload_, StopSession()).Times(1);

  EXPECT_CALL(*bluetooth::hci::testing::mock_controller_,
              IsSupported(bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))
          .WillOnce(Return(true));

  EXPECT_CALL(
          btm_client_interface_,
          BTM_SetEScoMode(AllOf(
                  Pointee(Field(&enh_esco_params_t::input_data_path, Eq(ESCO_DATA_PATH_PCM))),
                  Pointee(Field(&enh_esco_params_t::output_data_path, Eq(ESCO_DATA_PATH_PCM))))))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  prop_hfp_software_path_enabled_return_ = false;
  bta_ag_set_is_sco_managed_by_audio(true);  // This calls bta_ag_init_hfp_client_interface
  bta_ag_api_set_active_device(kRawAddress);
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_ESCO_S4,
          .peer_features = BTA_AG_FEAT_ESCO_S4,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .sco_codec = BTM_SCO_CODEC_LC3,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE,
  };
  bta_ag_create_sco(&scb, /* is_local = */ true);
  bta_clear_active_device();

  ASSERT_TRUE(bta_ag_get_swb_supported());
  ASSERT_EQ(scb.inuse_codec, tBTA_AG_UUID_CODEC::UUID_CODEC_LC3);
}

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, cvsd_software_path) {
  EXPECT_CALL(*mock_offload_, GetHfpScoConfig()).Times(0);

  EXPECT_CALL(*mock_decode_, StartSession()).Times(1);
  EXPECT_CALL(*mock_encode_, StartSession()).Times(1);
  EXPECT_CALL(*mock_offload_, StartSession()).Times(0);

  EXPECT_CALL(*mock_decode_, StopSession()).Times(1);
  EXPECT_CALL(*mock_encode_, StopSession()).Times(1);
  EXPECT_CALL(*mock_offload_, StopSession()).Times(0);

  EXPECT_CALL(*bluetooth::hci::testing::mock_controller_,
              IsSupported(bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))
          .WillOnce(Return(true));

  EXPECT_CALL(
          btm_client_interface_,
          BTM_SetEScoMode(AllOf(
                  Pointee(Field(&enh_esco_params_t::input_data_path, Eq(ESCO_DATA_PATH_HCI))),
                  Pointee(Field(&enh_esco_params_t::output_data_path, Eq(ESCO_DATA_PATH_HCI))))))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  prop_hfp_software_path_enabled_return_ = true;
  bta_ag_set_is_sco_managed_by_audio(true);  // This calls bta_ag_init_hfp_client_interface
  bta_ag_api_set_active_device(kRawAddress);
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_ESCO_S4,
          .peer_features = BTA_AG_FEAT_ESCO_S4,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .sco_codec = BTM_SCO_CODEC_CVSD,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE,
  };
  bta_ag_create_sco(&scb, /* is_local = */ true);
  bta_clear_active_device();

  ASSERT_EQ(scb.inuse_codec, tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD);
}

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, msbc_software_path) {
  EXPECT_CALL(*mock_offload_, GetHfpScoConfig()).Times(0);

  EXPECT_CALL(*mock_decode_, StartSession()).Times(1);
  EXPECT_CALL(*mock_encode_, StartSession()).Times(1);
  EXPECT_CALL(*mock_offload_, StartSession()).Times(0);

  EXPECT_CALL(*mock_decode_, StopSession()).Times(1);
  EXPECT_CALL(*mock_encode_, StopSession()).Times(1);
  EXPECT_CALL(*mock_offload_, StopSession()).Times(0);

  EXPECT_CALL(*bluetooth::hci::testing::mock_controller_,
              IsSupported(bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))
          .WillOnce(Return(true));

  EXPECT_CALL(btm_client_interface_,
              BTM_SetEScoMode(AllOf(
                      Pointee(Field(&enh_esco_params_t::input_data_path, Eq(ESCO_DATA_PATH_HCI))),
                      Pointee(Field(&enh_esco_params_t::output_data_path, Eq(ESCO_DATA_PATH_HCI))),
                      Pointee(Field(&enh_esco_params_t::input_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))),
                      Pointee(Field(&enh_esco_params_t::output_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))),
                      Pointee(Field(&enh_esco_params_t::transmit_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))),
                      Pointee(Field(&enh_esco_params_t::receive_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))))))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  prop_hfp_software_path_enabled_return_ = true;
  bta_ag_set_is_sco_managed_by_audio(true);  // This calls bta_ag_init_hfp_client_interface
  bta_ag_api_set_active_device(kRawAddress);
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_ESCO_S4,
          .peer_features = BTA_AG_FEAT_ESCO_S4,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .sco_codec = BTM_SCO_CODEC_MSBC,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE,
  };
  bta_ag_create_sco(&scb, /* is_local = */ true);
  bta_clear_active_device();

  ASSERT_TRUE(bta_ag_get_wbs_supported());
  ASSERT_EQ(scb.inuse_codec, tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC);
}

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, lc3_software_path) {
  EXPECT_CALL(*mock_offload_, GetHfpScoConfig()).Times(0);

  EXPECT_CALL(*mock_decode_, StartSession()).Times(1);
  EXPECT_CALL(*mock_encode_, StartSession()).Times(1);
  EXPECT_CALL(*mock_offload_, StartSession()).Times(0);

  EXPECT_CALL(*mock_decode_, StopSession()).Times(1);
  EXPECT_CALL(*mock_encode_, StopSession()).Times(1);
  EXPECT_CALL(*mock_offload_, StopSession()).Times(0);

  EXPECT_CALL(*bluetooth::hci::testing::mock_controller_,
              IsSupported(bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))
          .WillOnce(Return(true));

  EXPECT_CALL(btm_client_interface_,
              BTM_SetEScoMode(AllOf(
                      Pointee(Field(&enh_esco_params_t::input_data_path, Eq(ESCO_DATA_PATH_HCI))),
                      Pointee(Field(&enh_esco_params_t::output_data_path, Eq(ESCO_DATA_PATH_HCI))),
                      Pointee(Field(&enh_esco_params_t::input_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))),
                      Pointee(Field(&enh_esco_params_t::output_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))),
                      Pointee(Field(&enh_esco_params_t::transmit_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))),
                      Pointee(Field(&enh_esco_params_t::receive_coding_format,
                                    Field(&esco_coding_id_format_t::coding_format,
                                          Eq(ESCO_CODING_FORMAT_TRANSPNT)))))))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  prop_hfp_software_path_enabled_return_ = true;
  bta_ag_set_is_sco_managed_by_audio(true);  // This calls bta_ag_init_hfp_client_interface
  bta_ag_api_set_active_device(kRawAddress);
  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_ESCO_S4,
          .peer_features = BTA_AG_FEAT_ESCO_S4,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .sco_codec = BTM_SCO_CODEC_LC3,
          .inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE,
  };
  bta_ag_create_sco(&scb, /* is_local = */ true);
  bta_clear_active_device();

  ASSERT_TRUE(bta_ag_get_swb_supported());
  ASSERT_EQ(scb.inuse_codec, tBTA_AG_UUID_CODEC::UUID_CODEC_LC3);
}

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, codec_negotiation_timeout_software_path) {
  prop_hfp_software_path_enabled_return_ = true;
  bta_ag_set_is_sco_managed_by_audio(true);
  bta_ag_api_set_active_device(kRawAddress);

  // Intercept the alarm setup to capture the timeout callback
  alarm_callback_t codec_negotiation_cb = nullptr;
  void* codec_negotiation_data = nullptr;
  test::mock::osi_alarm::alarm_set_on_mloop.body =
      [&](alarm_t* /* alarm */, uint64_t /* interval_ms */, alarm_callback_t cb, void* data) {
        codec_negotiation_cb = cb;
        codec_negotiation_data = data;
      };

  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_CODEC,
          .peer_features = BTA_AG_PEER_FEAT_CODEC,
          .peer_sdp_features = BTA_AG_FEAT_WBS_SUPPORT,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .codec_updated = true,
  };

  // Mock remote features to allow codec negotiation to proceed
  uint8_t dummy_features[8] = {0};
  constexpr uint8_t kHciLmpTranspntSupported = 0x08;
  dummy_features[2] |= kHciLmpTranspntSupported;
  EXPECT_CALL(btm_client_interface_, BTM_ReadRemoteFeatures(kRawAddress))
      .WillOnce(Return(dummy_features));

  // Provide a dummy callback to prevent crashes when the timeout handler notifies the app
  bta_ag_cb.p_cback = [](tBTA_AG_EVT /* event */, tBTA_AG* /* p_data */) {};

  // Trigger codec negotiation, which will set the alarm
  bta_ag_codec_negotiate(&scb);
  ASSERT_NE(codec_negotiation_cb, nullptr);

  // Verify that CancelStreamingRequest is called on the software interfaces
  EXPECT_CALL(*mock_encode_, CancelStreamingRequest()).Times(1);
  EXPECT_CALL(*mock_decode_, CancelStreamingRequest()).Times(1);
  EXPECT_CALL(*mock_offload_, CancelStreamingRequest()).Times(0);

  // Simulate the timeout
  codec_negotiation_cb(codec_negotiation_data);

  bta_clear_active_device();
  test::mock::osi_alarm::alarm_set_on_mloop.body =
      [](alarm_t*, uint64_t, alarm_callback_t, void*) {};
}

TEST_F(BtaAgScoupdateCodecParametersFromProviderInfoTest, codec_negotiation_timeout_offload_path) {
  EXPECT_CALL(*mock_offload_, GetHfpScoConfig())
          .WillOnce(Return(std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config>{
                  {tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD,
                   {
                           .inputDataPath = ESCO_DATA_PATH_PCM,
                           .outputDataPath = ESCO_DATA_PATH_PCM,
                           .useControllerCodec = true,
                   }},
          }));

  prop_hfp_software_path_enabled_return_ = false;
  bta_ag_set_is_sco_managed_by_audio(true);
  bta_ag_api_set_active_device(kRawAddress);

  alarm_callback_t codec_negotiation_cb = nullptr;
  void* codec_negotiation_data = nullptr;
  test::mock::osi_alarm::alarm_set_on_mloop.body =
      [&](alarm_t* /* alarm */, uint64_t /* interval_ms */, alarm_callback_t cb, void* data) {
        codec_negotiation_cb = cb;
        codec_negotiation_data = data;
      };

  tBTA_AG_SCB scb{
          .peer_addr = kRawAddress,
          .features = BTA_AG_FEAT_CODEC,
          .peer_features = BTA_AG_PEER_FEAT_CODEC,
          .peer_sdp_features = BTA_AG_FEAT_WBS_SUPPORT,
          .sco_idx = BTM_INVALID_SCO_INDEX,
          .codec_updated = true,
  };

  uint8_t dummy_features[8] = {0};
  constexpr uint8_t kHciLmpTranspntSupported = 0x08;
  dummy_features[2] |= kHciLmpTranspntSupported;
  EXPECT_CALL(btm_client_interface_, BTM_ReadRemoteFeatures(kRawAddress))
      .WillOnce(Return(dummy_features));

  bta_ag_cb.p_cback = [](tBTA_AG_EVT /* event */, tBTA_AG* /* p_data */) {};

  bta_ag_codec_negotiate(&scb);
  ASSERT_NE(codec_negotiation_cb, nullptr);

  // Verify that CancelStreamingRequest is called on the offload interface
  EXPECT_CALL(*mock_encode_, CancelStreamingRequest()).Times(0);
  EXPECT_CALL(*mock_decode_, CancelStreamingRequest()).Times(0);
  EXPECT_CALL(*mock_offload_, CancelStreamingRequest()).Times(1);

  // Simulate the timeout
  codec_negotiation_cb(codec_negotiation_data);

  bta_clear_active_device();
  test::mock::osi_alarm::alarm_set_on_mloop.body =
      [](alarm_t*, uint64_t, alarm_callback_t, void*) {};
}
