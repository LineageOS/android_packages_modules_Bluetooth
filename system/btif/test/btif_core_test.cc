/*
 * Copyright 2021 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <sys/socket.h>

#include <future>  // NOLINT
#include <map>
#include <memory>
#include <string>

#include "bta/include/bta_ag_api.h"
#include "bta/include/bta_av_api.h"
#include "bta/include/bta_hd_api.h"
#include "bta/include/bta_hf_client_api.h"
#include "bta/include/bta_hh_api.h"
#include "btcore/include/module.h"
#include "btif/include/btif_api.h"
#include "btif/include/btif_bqr.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_jni_task.h"
#include "btif/include/btif_sock.h"
#include "btif/include/btif_util.h"
#include "common/bind.h"
#include "common/contextual_callback.h"
#include "common/postable_context.h"
#include "hci/controller_interface_mock.h"
#include "hci/hci_layer_mock.h"
#include "include/hardware/bluetooth.h"
#include "include/hardware/bt_av.h"
#include "packet/base_packet_builder.h"
#include "packet/bit_inserter.h"
#include "packet/packet_view.h"
#include "packet/raw_builder.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/main_thread.h"
#include "test/common/core_interface.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_osi_properties.h"
#include "test/mock/mock_osi_thread.h"
#include "test/mock/mock_stack_btm_sec.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

namespace bluetooth::testing {
void set_hal_cbacks(bt_callbacks_t* callbacks);
}  // namespace bluetooth::testing

namespace bluetooth::legacy::testing {
void bta_dm_acl_down(const RawAddress& bd_addr, tBT_TRANSPORT transport);
void bta_dm_acl_up(const RawAddress& bd_addr, tBT_TRANSPORT transport, uint16_t acl_handle);
}  // namespace bluetooth::legacy::testing

const tBTA_AG_RES_DATA tBTA_AG_RES_DATA::kEmpty = {};

using bluetooth::Uuid;
using bluetooth::common::BindOnce;
using bluetooth::common::ContextualCallback;
using bluetooth::common::ContextualOnceCallback;
using bluetooth::common::PostableContext;
using bluetooth::hci::BqrA2dpAudioChoppyEventBuilder;
using bluetooth::hci::BqrEventBuilder;
using bluetooth::hci::BqrLinkQualityEventBuilder;
using bluetooth::hci::BqrLmpLlMessageTraceEventBuilder;
using bluetooth::hci::BqrLogDumpEventBuilder;
using bluetooth::hci::BqrPacketType;
using bluetooth::hci::CommandBuilder;
using bluetooth::hci::CommandCompleteBuilder;
using bluetooth::hci::CommandCompleteView;
using bluetooth::hci::CommandStatusBuilder;
using bluetooth::hci::CommandStatusView;
using bluetooth::hci::CommandView;
using bluetooth::hci::ControllerBqrBuilder;
using bluetooth::hci::ControllerBqrCompleteBuilder;
using bluetooth::hci::ControllerBqrCompleteView;
using bluetooth::hci::ControllerBqrView;
using bluetooth::hci::ErrorCode;
using bluetooth::hci::EventView;
using bluetooth::hci::QualityReportId;
using bluetooth::hci::Role;
using bluetooth::hci::VendorCommandView;
using bluetooth::hci::VendorSpecificEventView;
using bluetooth::hci::VseSubeventCode;
using bluetooth::packet::BasePacketBuilder;
using bluetooth::packet::BitInserter;
using bluetooth::packet::kLittleEndian;
using bluetooth::packet::PacketView;
using bluetooth::packet::RawBuilder;
using testing::_;
using testing::DoAll;
using testing::Invoke;
using testing::Matcher;
using testing::Return;
using testing::SaveArg;

module_t bt_utils_module;
module_t gd_controller_module;
module_t gd_shim_module;
module_t osi_module;
module_t rust_module;

namespace {

PacketView<kLittleEndian> BuilderToView(std::unique_ptr<BasePacketBuilder> builder) {
  std::shared_ptr<std::vector<uint8_t>> packet_bytes = std::make_shared<std::vector<uint8_t>>();
  BitInserter it(*packet_bytes);
  builder->Serialize(it);
  return PacketView<kLittleEndian>(packet_bytes);
}

const RawAddress kRawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const uint16_t kHciHandle = 123;

auto timeout_time = std::chrono::seconds(3);

std::map<std::string, std::function<void()>> callback_map_;
#define TESTCB                                             \
  if (callback_map_.find(__func__) != callback_map_.end()) \
    callback_map_[__func__]();

void adapter_state_changed_callback(bt_state_t /* state */) {}
void adapter_properties_callback(bt_status_t /* status */, int /* num_properties */,
                                 bt_property_t* /* properties */) {}
void remote_device_properties_callback(bt_status_t /* status */, RawAddress* /* bd_addr */,
                                       int /* num_properties */, bt_property_t* /* properties */) {}
void device_found_callback(int /* num_properties */, bt_property_t* /* properties */) {}
void discovery_state_changed_callback(bt_discovery_state_t /* state */) {}
void pin_request_callback(RawAddress* /* remote_bd_addr */, bt_bdname_t* /* bd_name */,
                          uint32_t /* cod */, bool /* min_16_digit */) {}
void ssp_request_callback(RawAddress* /* remote_bd_addr */, bt_ssp_variant_t /* pairing_variant */,
                          uint32_t /* pass_key */) {}
void bond_state_changed_callback(bt_status_t /* status */, RawAddress* /* remote_bd_addr */,
                                 bt_bond_state_t /* state */, int /* fail_reason */) {}
void address_consolidate_callback(RawAddress* /* main_bd_addr */,
                                  RawAddress* /* secondary_bd_addr */) {}
void le_address_associate_callback(RawAddress* /* main_bd_addr */,
                                   RawAddress* /* secondary_bd_addr */,
                                   uint8_t /* identity_address_type */) {}
void acl_state_changed_callback(bt_status_t /* status */, RawAddress* /* remote_bd_addr */,
                                bt_acl_state_t /* state */, int /* transport_link_type */,
                                bt_hci_error_code_t /* hci_reason */,
                                bt_conn_direction_t /* direction */, uint16_t /* acl_handle */) {}
void link_quality_report_callback(uint64_t /* timestamp */, int /* report_id */, int /* rssi */,
                                  int /* snr */, int /* retransmission_count */,
                                  int /* packets_not_receive_count */,
                                  int /* negative_acknowledgement_count */) {
  TESTCB;
}
void callback_thread_event(bt_cb_thread_evt /* evt */) { TESTCB; }
void dut_mode_recv_callback(uint16_t /* opcode */, uint8_t* /* buf */, uint8_t /* len */) {}
void le_test_mode_callback(bt_status_t /* status */, uint16_t /* num_packets */) {}
void energy_info_callback(bt_activity_energy_info* /* energy_info */,
                          bt_uid_traffic_t* /* uid_data */) {}
void generate_local_oob_data_callback(tBT_TRANSPORT /* transport */, bt_oob_data_t /* oob_data */) {
}
void switch_buffer_size_callback(bool /* is_low_latency_buffer_size */) {}
void switch_codec_callback(bool /* is_low_latency_buffer_size */) {}
void le_rand_callback(uint64_t /* random */) {}
void key_missing_callback(const RawAddress /* bd_addr */) {}
#undef TESTCB

bt_callbacks_t callbacks = {
        .size = sizeof(bt_callbacks_t),
        .adapter_state_changed_cb = adapter_state_changed_callback,
        .adapter_properties_cb = adapter_properties_callback,
        .remote_device_properties_cb = remote_device_properties_callback,
        .device_found_cb = device_found_callback,
        .discovery_state_changed_cb = discovery_state_changed_callback,
        .pin_request_cb = pin_request_callback,
        .ssp_request_cb = ssp_request_callback,
        .bond_state_changed_cb = bond_state_changed_callback,
        .address_consolidate_cb = address_consolidate_callback,
        .le_address_associate_cb = le_address_associate_callback,
        .acl_state_changed_cb = acl_state_changed_callback,
        .thread_evt_cb = callback_thread_event,
        .dut_mode_recv_cb = dut_mode_recv_callback,
        .le_test_mode_cb = le_test_mode_callback,
        .energy_info_cb = energy_info_callback,
        .link_quality_report_cb = link_quality_report_callback,
        .generate_local_oob_data_cb = generate_local_oob_data_callback,
        .switch_buffer_size_cb = switch_buffer_size_callback,
        .switch_codec_cb = switch_codec_callback,
        .le_rand_cb = le_rand_callback,
        .key_missing_cb = key_missing_callback,
};

}  // namespace

class BtifUtilsTest : public ::testing::Test {};

class BtifCoreTest : public ::testing::Test {
protected:
  void SetUp() override {
    callback_map_.clear();
    bluetooth::hci::testing::mock_controller_ = &controller_;
    bluetooth::testing::set_hal_cbacks(&callbacks);
    auto promise = std::promise<void>();
    auto future = promise.get_future();
    callback_map_["callback_thread_event"] = [&promise]() { promise.set_value(); };
    InitializeCoreInterface();
    ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
    callback_map_.erase("callback_thread_event");
  }

  void TearDown() override {
    auto promise = std::promise<void>();
    auto future = promise.get_future();
    callback_map_["callback_thread_event"] = [&promise]() { promise.set_value(); };
    CleanCoreInterface();
    ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
    bluetooth::hci::testing::mock_controller_ = nullptr;
    callback_map_.erase("callback_thread_event");
  }
  bluetooth::hci::testing::MockControllerInterface controller_;
};

class BtifCoreWithControllerTest : public BtifCoreTest {
protected:
  void SetUp() override {
    BtifCoreTest::SetUp();
    ON_CALL(controller_, SupportsSniffSubrating).WillByDefault(Return(true));
  }

  void TearDown() override { BtifCoreTest::TearDown(); }
};

class BtifCoreWithConnectionTest : public BtifCoreWithControllerTest {
protected:
  void SetUp() override {
    BtifCoreWithControllerTest::SetUp();
    bluetooth::legacy::testing::bta_dm_acl_up(kRawAddress, BT_TRANSPORT_AUTO, kHciHandle);
  }

  void TearDown() override {
    bluetooth::legacy::testing::bta_dm_acl_down(kRawAddress, BT_TRANSPORT_AUTO);
    BtifCoreWithControllerTest::TearDown();
  }
};

std::promise<int> promise0;
static void callback0(int val) { promise0.set_value(val); }

TEST_F(BtifCoreTest, test_nop) {}

TEST_F(BtifCoreTest, test_post_on_bt_simple0) {
  const int val = kHciHandle;
  promise0 = std::promise<int>();
  std::future<int> future0 = promise0.get_future();
  post_on_bt_jni([=]() { callback0(val); });
  ASSERT_EQ(std::future_status::ready, future0.wait_for(timeout_time));
  ASSERT_EQ(val, future0.get());
}

TEST_F(BtifCoreTest, test_post_on_bt_jni_simple1) {
  std::promise<void> promise;
  std::future<void> future = promise.get_future();
  post_on_bt_jni([=, &promise]() { promise.set_value(); });
  ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
}

TEST_F(BtifCoreTest, test_post_on_bt_jni_simple2) {
  std::promise<void> promise;
  std::future<void> future = promise.get_future();
  BtJniClosure closure = [&promise]() { promise.set_value(); };
  post_on_bt_jni(closure);
  ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
}

TEST_F(BtifCoreTest, test_post_on_bt_jni_simple3) {
  const int val = 456;
  std::promise<int> promise;
  auto future = promise.get_future();
  BtJniClosure closure = [&promise, val]() { promise.set_value(val); };
  post_on_bt_jni(closure);
  ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
  ASSERT_EQ(val, future.get());
}

TEST_F(BtifUtilsTest, dump_dm_search_event) {
  std::vector<std::pair<uint16_t, std::string>> events = {
          std::make_pair(BTA_DM_INQ_RES_EVT, "BTA_DM_INQ_RES_EVT"),
          std::make_pair(BTA_DM_INQ_CMPL_EVT, "BTA_DM_INQ_CMPL_EVT"),
          std::make_pair(BTA_DM_DISC_CMPL_EVT, "BTA_DM_DISC_CMPL_EVT"),
          std::make_pair(BTA_DM_SEARCH_CANCEL_CMPL_EVT, "BTA_DM_SEARCH_CANCEL_CMPL_EVT"),
          std::make_pair(BTA_DM_NAME_READ_EVT, "BTA_DM_NAME_READ_EVT"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_dm_search_event(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_dm_search_event(std::numeric_limits<uint16_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_property_type) {
  std::vector<std::pair<bt_property_type_t, std::string>> types = {
          std::make_pair(BT_PROPERTY_BDNAME, "BT_PROPERTY_BDNAME"),
          std::make_pair(BT_PROPERTY_BDADDR, "BT_PROPERTY_BDADDR"),
          std::make_pair(BT_PROPERTY_UUIDS, "BT_PROPERTY_UUIDS"),
          std::make_pair(BT_PROPERTY_CLASS_OF_DEVICE, "BT_PROPERTY_CLASS_OF_DEVICE"),
          std::make_pair(BT_PROPERTY_TYPE_OF_DEVICE, "BT_PROPERTY_TYPE_OF_DEVICE"),
          std::make_pair(BT_PROPERTY_REMOTE_RSSI, "BT_PROPERTY_REMOTE_RSSI"),
          std::make_pair(BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT,
                         "BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT"),
          std::make_pair(BT_PROPERTY_ADAPTER_BONDED_DEVICES, "BT_PROPERTY_ADAPTER_BONDED_DEVICES"),
          std::make_pair(BT_PROPERTY_REMOTE_FRIENDLY_NAME, "BT_PROPERTY_REMOTE_FRIENDLY_NAME"),
          std::make_pair(BT_PROPERTY_UUIDS_LE, "BT_PROPERTY_UUIDS_LE"),
  };
  for (const auto& type : types) {
    EXPECT_TRUE(dump_property_type(type.first).starts_with(type.second));
  }
  EXPECT_TRUE(
          dump_property_type(static_cast<bt_property_type_t>(std::numeric_limits<uint16_t>::max()))
                  .starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_dm_event) {
  std::vector<std::pair<uint8_t, std::string>> events = {
          std::make_pair(BTA_DM_PIN_REQ_EVT, "BTA_DM_PIN_REQ_EVT"),
          std::make_pair(BTA_DM_AUTH_CMPL_EVT, "BTA_DM_AUTH_CMPL_EVT"),
          std::make_pair(BTA_DM_LINK_UP_EVT, "BTA_DM_LINK_UP_EVT"),
          std::make_pair(BTA_DM_LINK_DOWN_EVT, "BTA_DM_LINK_DOWN_EVT"),
          std::make_pair(BTA_DM_BOND_CANCEL_CMPL_EVT, "BTA_DM_BOND_CANCEL_CMPL_EVT"),
          std::make_pair(BTA_DM_SP_CFM_REQ_EVT, "BTA_DM_SP_CFM_REQ_EVT"),
          std::make_pair(BTA_DM_SP_KEY_NOTIF_EVT, "BTA_DM_SP_KEY_NOTIF_EVT"),
          std::make_pair(BTA_DM_BLE_KEY_EVT, "BTA_DM_BLE_KEY_EVT"),
          std::make_pair(BTA_DM_BLE_SEC_REQ_EVT, "BTA_DM_BLE_SEC_REQ_EVT"),
          std::make_pair(BTA_DM_BLE_PASSKEY_NOTIF_EVT, "BTA_DM_BLE_PASSKEY_NOTIF_EVT"),
          std::make_pair(BTA_DM_BLE_PASSKEY_REQ_EVT, "BTA_DM_BLE_PASSKEY_REQ_EVT"),
          std::make_pair(BTA_DM_BLE_OOB_REQ_EVT, "BTA_DM_BLE_OOB_REQ_EVT"),
          std::make_pair(BTA_DM_BLE_SC_OOB_REQ_EVT, "BTA_DM_BLE_SC_OOB_REQ_EVT"),
          std::make_pair(BTA_DM_BLE_LOCAL_IR_EVT, "BTA_DM_BLE_LOCAL_IR_EVT"),
          std::make_pair(BTA_DM_BLE_LOCAL_ER_EVT, "BTA_DM_BLE_LOCAL_ER_EVT"),
          std::make_pair(BTA_DM_BLE_AUTH_CMPL_EVT, "BTA_DM_BLE_AUTH_CMPL_EVT"),
          std::make_pair(BTA_DM_DEV_UNPAIRED_EVT, "BTA_DM_DEV_UNPAIRED_EVT"),
          std::make_pair(BTA_DM_ENER_INFO_READ, "BTA_DM_ENER_INFO_READ"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_dm_event(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_dm_event(std::numeric_limits<uint8_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_hf_event) {
  std::vector<std::pair<uint8_t, std::string>> events = {
          std::make_pair(BTA_AG_ENABLE_EVT, "BTA_AG_ENABLE_EVT"),
          std::make_pair(BTA_AG_REGISTER_EVT, "BTA_AG_REGISTER_EVT"),
          std::make_pair(BTA_AG_OPEN_EVT, "BTA_AG_OPEN_EVT"),
          std::make_pair(BTA_AG_CLOSE_EVT, "BTA_AG_CLOSE_EVT"),
          std::make_pair(BTA_AG_CONN_EVT, "BTA_AG_CONN_EVT"),
          std::make_pair(BTA_AG_AUDIO_OPEN_EVT, "BTA_AG_AUDIO_OPEN_EVT"),
          std::make_pair(BTA_AG_AUDIO_CLOSE_EVT, "BTA_AG_AUDIO_CLOSE_EVT"),
          std::make_pair(BTA_AG_SPK_EVT, "BTA_AG_SPK_EVT"),
          std::make_pair(BTA_AG_MIC_EVT, "BTA_AG_MIC_EVT"),
          std::make_pair(BTA_AG_AT_CKPD_EVT, "BTA_AG_AT_CKPD_EVT"),
          std::make_pair(BTA_AG_DISABLE_EVT, "BTA_AG_DISABLE_EVT"),
          std::make_pair(BTA_AG_CODEC_EVT, "BTA_AG_CODEC_EVT"),
          std::make_pair(BTA_AG_AT_A_EVT, "BTA_AG_AT_A_EVT"),
          std::make_pair(BTA_AG_AT_D_EVT, "BTA_AG_AT_D_EVT"),
          std::make_pair(BTA_AG_AT_CHLD_EVT, "BTA_AG_AT_CHLD_EVT"),
          std::make_pair(BTA_AG_AT_CHUP_EVT, "BTA_AG_AT_CHUP_EVT"),
          std::make_pair(BTA_AG_AT_CIND_EVT, "BTA_AG_AT_CIND_EVT"),
          std::make_pair(BTA_AG_AT_VTS_EVT, "BTA_AG_AT_VTS_EVT"),
          std::make_pair(BTA_AG_AT_BINP_EVT, "BTA_AG_AT_BINP_EVT"),
          std::make_pair(BTA_AG_AT_BLDN_EVT, "BTA_AG_AT_BLDN_EVT"),
          std::make_pair(BTA_AG_AT_BVRA_EVT, "BTA_AG_AT_BVRA_EVT"),
          std::make_pair(BTA_AG_AT_NREC_EVT, "BTA_AG_AT_NREC_EVT"),
          std::make_pair(BTA_AG_AT_CNUM_EVT, "BTA_AG_AT_CNUM_EVT"),
          std::make_pair(BTA_AG_AT_BTRH_EVT, "BTA_AG_AT_BTRH_EVT"),
          std::make_pair(BTA_AG_AT_CLCC_EVT, "BTA_AG_AT_CLCC_EVT"),
          std::make_pair(BTA_AG_AT_COPS_EVT, "BTA_AG_AT_COPS_EVT"),
          std::make_pair(BTA_AG_AT_UNAT_EVT, "BTA_AG_AT_UNAT_EVT"),
          std::make_pair(BTA_AG_AT_CBC_EVT, "BTA_AG_AT_CBC_EVT"),
          std::make_pair(BTA_AG_AT_BAC_EVT, "BTA_AG_AT_BAC_EVT"),
          std::make_pair(BTA_AG_AT_BCS_EVT, "BTA_AG_AT_BCS_EVT"),
          std::make_pair(BTA_AG_AT_BIND_EVT, "BTA_AG_AT_BIND_EVT"),
          std::make_pair(BTA_AG_AT_BIEV_EVT, "BTA_AG_AT_BIEV_EVT"),
          std::make_pair(BTA_AG_AT_BIA_EVT, "BTA_AG_AT_BIA_EVT"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_hf_event(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_hf_event(std::numeric_limits<uint8_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_hf_client_event) {
  std::vector<std::pair<int, std::string>> events = {
          std::make_pair(BTA_HF_CLIENT_ENABLE_EVT, "BTA_HF_CLIENT_ENABLE_EVT"),
          std::make_pair(BTA_HF_CLIENT_REGISTER_EVT, "BTA_HF_CLIENT_REGISTER_EVT"),
          std::make_pair(BTA_HF_CLIENT_OPEN_EVT, "BTA_HF_CLIENT_OPEN_EVT"),
          std::make_pair(BTA_HF_CLIENT_CLOSE_EVT, "BTA_HF_CLIENT_CLOSE_EVT"),
          std::make_pair(BTA_HF_CLIENT_CONN_EVT, "BTA_HF_CLIENT_CONN_EVT"),
          std::make_pair(BTA_HF_CLIENT_AUDIO_OPEN_EVT, "BTA_HF_CLIENT_AUDIO_OPEN_EVT"),
          std::make_pair(BTA_HF_CLIENT_AUDIO_MSBC_OPEN_EVT, "BTA_HF_CLIENT_AUDIO_MSBC_OPEN_EVT"),
          std::make_pair(BTA_HF_CLIENT_AUDIO_LC3_OPEN_EVT, "BTA_HF_CLIENT_AUDIO_LC3_OPEN_EVT"),
          std::make_pair(BTA_HF_CLIENT_AUDIO_CLOSE_EVT, "BTA_HF_CLIENT_AUDIO_CLOSE_EVT"),
          std::make_pair(BTA_HF_CLIENT_SPK_EVT, "BTA_HF_CLIENT_SPK_EVT"),
          std::make_pair(BTA_HF_CLIENT_MIC_EVT, "BTA_HF_CLIENT_MIC_EVT"),
          std::make_pair(BTA_HF_CLIENT_DISABLE_EVT, "BTA_HF_CLIENT_DISABLE_EVT"),
          std::make_pair(BTA_HF_CLIENT_IND_EVT, "BTA_HF_CLIENT_IND_EVT"),
          std::make_pair(BTA_HF_CLIENT_VOICE_REC_EVT, "BTA_HF_CLIENT_VOICE_REC_EVT"),
          std::make_pair(BTA_HF_CLIENT_OPERATOR_NAME_EVT, "BTA_HF_CLIENT_OPERATOR_NAME_EVT"),
          std::make_pair(BTA_HF_CLIENT_CLIP_EVT, "BTA_HF_CLIENT_CLIP_EVT"),
          std::make_pair(BTA_HF_CLIENT_CCWA_EVT, "BTA_HF_CLIENT_CCWA_EVT"),
          std::make_pair(BTA_HF_CLIENT_AT_RESULT_EVT, "BTA_HF_CLIENT_AT_RESULT_EVT"),
          std::make_pair(BTA_HF_CLIENT_CLCC_EVT, "BTA_HF_CLIENT_CLCC_EVT"),
          std::make_pair(BTA_HF_CLIENT_CNUM_EVT, "BTA_HF_CLIENT_CNUM_EVT"),
          std::make_pair(BTA_HF_CLIENT_BTRH_EVT, "BTA_HF_CLIENT_BTRH_EVT"),
          std::make_pair(BTA_HF_CLIENT_BSIR_EVT, "BTA_HF_CLIENT_BSIR_EVT"),
          std::make_pair(BTA_HF_CLIENT_BINP_EVT, "BTA_HF_CLIENT_BINP_EVT"),
          std::make_pair(BTA_HF_CLIENT_RING_INDICATION, "BTA_HF_CLIENT_RING_INDICATION"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_hf_client_event(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_hf_client_event(std::numeric_limits<uint16_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifCoreTest, bta_hh_event_text) {
  std::vector<std::pair<int, std::string>> events = {
          std::make_pair(BTA_HH_EMPTY_EVT, "BTA_HH_EMPTY_EVT"),
          std::make_pair(BTA_HH_ENABLE_EVT, "BTA_HH_ENABLE_EVT"),
          std::make_pair(BTA_HH_DISABLE_EVT, "BTA_HH_DISABLE_EVT"),
          std::make_pair(BTA_HH_OPEN_EVT, "BTA_HH_OPEN_EVT"),
          std::make_pair(BTA_HH_CLOSE_EVT, "BTA_HH_CLOSE_EVT"),
          std::make_pair(BTA_HH_GET_DSCP_EVT, "BTA_HH_GET_DSCP_EVT"),
          std::make_pair(BTA_HH_GET_PROTO_EVT, "BTA_HH_GET_PROTO_EVT"),
          std::make_pair(BTA_HH_GET_RPT_EVT, "BTA_HH_GET_RPT_EVT"),
          std::make_pair(BTA_HH_GET_IDLE_EVT, "BTA_HH_GET_IDLE_EVT"),
          std::make_pair(BTA_HH_SET_PROTO_EVT, "BTA_HH_SET_PROTO_EVT"),
          std::make_pair(BTA_HH_SET_RPT_EVT, "BTA_HH_SET_RPT_EVT"),
          std::make_pair(BTA_HH_SET_IDLE_EVT, "BTA_HH_SET_IDLE_EVT"),
          std::make_pair(BTA_HH_VC_UNPLUG_EVT, "BTA_HH_VC_UNPLUG_EVT"),
          std::make_pair(BTA_HH_ADD_DEV_EVT, "BTA_HH_ADD_DEV_EVT"),
          std::make_pair(BTA_HH_RMV_DEV_EVT, "BTA_HH_RMV_DEV_EVT"),
          std::make_pair(BTA_HH_API_ERR_EVT, "BTA_HH_API_ERR_EVT"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(bta_hh_event_text(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(bta_hh_event_text(std::numeric_limits<uint16_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_hd_event) {
  std::vector<std::pair<uint16_t, std::string>> events = {
          std::make_pair(BTA_HD_ENABLE_EVT, "BTA_HD_ENABLE_EVT"),
          std::make_pair(BTA_HD_DISABLE_EVT, "BTA_HD_DISABLE_EVT"),
          std::make_pair(BTA_HD_REGISTER_APP_EVT, "BTA_HD_REGISTER_APP_EVT"),
          std::make_pair(BTA_HD_UNREGISTER_APP_EVT, "BTA_HD_UNREGISTER_APP_EVT"),
          std::make_pair(BTA_HD_OPEN_EVT, "BTA_HD_OPEN_EVT"),
          std::make_pair(BTA_HD_CLOSE_EVT, "BTA_HD_CLOSE_EVT"),
          std::make_pair(BTA_HD_GET_REPORT_EVT, "BTA_HD_GET_REPORT_EVT"),
          std::make_pair(BTA_HD_SET_REPORT_EVT, "BTA_HD_SET_REPORT_EVT"),
          std::make_pair(BTA_HD_SET_PROTOCOL_EVT, "BTA_HD_SET_PROTOCOL_EVT"),
          std::make_pair(BTA_HD_INTR_DATA_EVT, "BTA_HD_INTR_DATA_EVT"),
          std::make_pair(BTA_HD_VC_UNPLUG_EVT, "BTA_HD_VC_UNPLUG_EVT"),
          std::make_pair(BTA_HD_CONN_STATE_EVT, "BTA_HD_CONN_STATE_EVT"),
          std::make_pair(BTA_HD_API_ERR_EVT, "BTA_HD_API_ERR_EVT"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_hd_event(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_hd_event(std::numeric_limits<uint16_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_thread_evt) {
  std::vector<std::pair<bt_cb_thread_evt, std::string>> events = {
          std::make_pair(ASSOCIATE_JVM, "ASSOCIATE_JVM"),
          std::make_pair(DISASSOCIATE_JVM, "DISASSOCIATE_JVM"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_thread_evt(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_thread_evt(static_cast<bt_cb_thread_evt>(std::numeric_limits<uint16_t>::max()))
                      .starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_av_conn_state) {
  std::vector<std::pair<uint16_t, std::string>> events = {
          std::make_pair(BTAV_CONNECTION_STATE_DISCONNECTED, "BTAV_CONNECTION_STATE_DISCONNECTED"),
          std::make_pair(BTAV_CONNECTION_STATE_CONNECTING, "BTAV_CONNECTION_STATE_CONNECTING"),
          std::make_pair(BTAV_CONNECTION_STATE_CONNECTED, "BTAV_CONNECTION_STATE_CONNECTED"),
          std::make_pair(BTAV_CONNECTION_STATE_DISCONNECTING,
                         "BTAV_CONNECTION_STATE_DISCONNECTING"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_av_conn_state(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_av_conn_state(std::numeric_limits<uint16_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_av_audio_state) {
  std::vector<std::pair<uint16_t, std::string>> events = {
          std::make_pair(BTAV_AUDIO_STATE_REMOTE_SUSPEND, "BTAV_AUDIO_STATE_REMOTE_SUSPEND"),
          std::make_pair(BTAV_AUDIO_STATE_STOPPED, "BTAV_AUDIO_STATE_STOPPED"),
          std::make_pair(BTAV_AUDIO_STATE_STARTED, "BTAV_AUDIO_STATE_STARTED"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_av_audio_state(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_av_audio_state(std::numeric_limits<uint16_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_adapter_scan_mode) {
  std::vector<std::pair<bt_scan_mode_t, std::string>> events = {
          std::make_pair(BT_SCAN_MODE_NONE, "BT_SCAN_MODE_NONE"),
          std::make_pair(BT_SCAN_MODE_CONNECTABLE, "BT_SCAN_MODE_CONNECTABLE"),
          std::make_pair(BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                         "BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_adapter_scan_mode(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_adapter_scan_mode(static_cast<bt_scan_mode_t>(std::numeric_limits<int>::max()))
                      .starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_bt_status) {
  std::vector<std::pair<bt_status_t, std::string>> events = {
          std::make_pair(BT_STATUS_SUCCESS, "BT_STATUS_SUCCESS"),
          std::make_pair(BT_STATUS_FAIL, "BT_STATUS_FAIL"),
          std::make_pair(BT_STATUS_NOT_READY, "BT_STATUS_NOT_READY"),
          std::make_pair(BT_STATUS_NOMEM, "BT_STATUS_NOMEM"),
          std::make_pair(BT_STATUS_BUSY, "BT_STATUS_BUSY"),
          std::make_pair(BT_STATUS_UNSUPPORTED, "BT_STATUS_UNSUPPORTED"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_bt_status(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_bt_status(static_cast<bt_status_t>(std::numeric_limits<int>::max()))
                      .starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_rc_event) {
  std::vector<std::pair<uint8_t, std::string>> events = {
          std::make_pair(BTA_AV_RC_OPEN_EVT, "BTA_AV_RC_OPEN_EVT"),
          std::make_pair(BTA_AV_RC_CLOSE_EVT, "BTA_AV_RC_CLOSE_EVT"),
          std::make_pair(BTA_AV_RC_BROWSE_OPEN_EVT, "BTA_AV_RC_BROWSE_OPEN_EVT"),
          std::make_pair(BTA_AV_RC_BROWSE_CLOSE_EVT, "BTA_AV_RC_BROWSE_CLOSE_EVT"),
          std::make_pair(BTA_AV_REMOTE_CMD_EVT, "BTA_AV_REMOTE_CMD_EVT"),
          std::make_pair(BTA_AV_REMOTE_RSP_EVT, "BTA_AV_REMOTE_RSP_EVT"),
          std::make_pair(BTA_AV_VENDOR_CMD_EVT, "BTA_AV_VENDOR_CMD_EVT"),
          std::make_pair(BTA_AV_VENDOR_RSP_EVT, "BTA_AV_VENDOR_RSP_EVT"),
          std::make_pair(BTA_AV_META_MSG_EVT, "BTA_AV_META_MSG_EVT"),
          std::make_pair(BTA_AV_RC_FEAT_EVT, "BTA_AV_RC_FEAT_EVT"),
          std::make_pair(BTA_AV_RC_PSM_EVT, "BTA_AV_RC_PSM_EVT"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_rc_event(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_rc_event(std::numeric_limits<uint8_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_rc_notification_event_id) {
  std::vector<std::pair<uint8_t, std::string>> events = {
          std::make_pair(AVRC_EVT_PLAY_STATUS_CHANGE, "AVRC_EVT_PLAY_STATUS_CHANGE"),
          std::make_pair(AVRC_EVT_TRACK_CHANGE, "AVRC_EVT_TRACK_CHANGE"),
          std::make_pair(AVRC_EVT_TRACK_REACHED_END, "AVRC_EVT_TRACK_REACHED_END"),
          std::make_pair(AVRC_EVT_TRACK_REACHED_START, "AVRC_EVT_TRACK_REACHED_START"),
          std::make_pair(AVRC_EVT_PLAY_POS_CHANGED, "AVRC_EVT_PLAY_POS_CHANGED"),
          std::make_pair(AVRC_EVT_BATTERY_STATUS_CHANGE, "AVRC_EVT_BATTERY_STATUS_CHANGE"),
          std::make_pair(AVRC_EVT_SYSTEM_STATUS_CHANGE, "AVRC_EVT_SYSTEM_STATUS_CHANGE"),
          std::make_pair(AVRC_EVT_APP_SETTING_CHANGE, "AVRC_EVT_APP_SETTING_CHANGE"),
          std::make_pair(AVRC_EVT_VOLUME_CHANGE, "AVRC_EVT_VOLUME_CHANGE"),
          std::make_pair(AVRC_EVT_ADDR_PLAYER_CHANGE, "AVRC_EVT_ADDR_PLAYER_CHANGE"),
          std::make_pair(AVRC_EVT_AVAL_PLAYERS_CHANGE, "AVRC_EVT_AVAL_PLAYERS_CHANGE"),
          std::make_pair(AVRC_EVT_NOW_PLAYING_CHANGE, "AVRC_EVT_NOW_PLAYING_CHANGE"),
          std::make_pair(AVRC_EVT_UIDS_CHANGE, "AVRC_EVT_UIDS_CHANGE"),
  };
  for (const auto& event : events) {
    ASSERT_TRUE(dump_rc_notification_event_id(event.first).starts_with(event.second));
  }
  ASSERT_TRUE(dump_rc_notification_event_id(std::numeric_limits<uint8_t>::max())
                      .starts_with("Unknown"));
}

TEST_F(BtifUtilsTest, dump_rc_pdu) {
  std::vector<std::pair<uint8_t, std::string>> pdus = {
          std::make_pair(AVRC_PDU_LIST_PLAYER_APP_ATTR, "AVRC_PDU_LIST_PLAYER_APP_ATTR"),
          std::make_pair(AVRC_PDU_LIST_PLAYER_APP_VALUES, "AVRC_PDU_LIST_PLAYER_APP_VALUES"),
          std::make_pair(AVRC_PDU_GET_CUR_PLAYER_APP_VALUE, "AVRC_PDU_GET_CUR_PLAYER_APP_VALUE"),
          std::make_pair(AVRC_PDU_SET_PLAYER_APP_VALUE, "AVRC_PDU_SET_PLAYER_APP_VALUE"),
          std::make_pair(AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT, "AVRC_PDU_GET_PLAYER_APP_ATTR_TEXT"),
          std::make_pair(AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT, "AVRC_PDU_GET_PLAYER_APP_VALUE_TEXT"),
          std::make_pair(AVRC_PDU_INFORM_DISPLAY_CHARSET, "AVRC_PDU_INFORM_DISPLAY_CHARSET"),
          std::make_pair(AVRC_PDU_INFORM_BATTERY_STAT_OF_CT, "AVRC_PDU_INFORM_BATTERY_STAT_OF_CT"),
          std::make_pair(AVRC_PDU_GET_ELEMENT_ATTR, "AVRC_PDU_GET_ELEMENT_ATTR"),
          std::make_pair(AVRC_PDU_GET_PLAY_STATUS, "AVRC_PDU_GET_PLAY_STATUS"),
          std::make_pair(AVRC_PDU_REGISTER_NOTIFICATION, "AVRC_PDU_REGISTER_NOTIFICATION"),
          std::make_pair(AVRC_PDU_REQUEST_CONTINUATION_RSP, "AVRC_PDU_REQUEST_CONTINUATION_RSP"),
          std::make_pair(AVRC_PDU_ABORT_CONTINUATION_RSP, "AVRC_PDU_ABORT_CONTINUATION_RSP"),
          std::make_pair(AVRC_PDU_SET_ABSOLUTE_VOLUME, "AVRC_PDU_SET_ABSOLUTE_VOLUME"),
          std::make_pair(AVRC_PDU_SET_ADDRESSED_PLAYER, "AVRC_PDU_SET_ADDRESSED_PLAYER"),
          std::make_pair(AVRC_PDU_CHANGE_PATH, "AVRC_PDU_CHANGE_PATH"),
          std::make_pair(AVRC_PDU_GET_CAPABILITIES, "AVRC_PDU_GET_CAPABILITIES"),
          std::make_pair(AVRC_PDU_SET_BROWSED_PLAYER, "AVRC_PDU_SET_BROWSED_PLAYER"),
          std::make_pair(AVRC_PDU_GET_FOLDER_ITEMS, "AVRC_PDU_GET_FOLDER_ITEMS"),
          std::make_pair(AVRC_PDU_GET_ITEM_ATTRIBUTES, "AVRC_PDU_GET_ITEM_ATTRIBUTES"),
          std::make_pair(AVRC_PDU_PLAY_ITEM, "AVRC_PDU_PLAY_ITEM"),
          std::make_pair(AVRC_PDU_SEARCH, "AVRC_PDU_SEARCH"),
          std::make_pair(AVRC_PDU_ADD_TO_NOW_PLAYING, "AVRC_PDU_ADD_TO_NOW_PLAYING"),
          std::make_pair(AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS, "AVRC_PDU_GET_TOTAL_NUM_OF_ITEMS"),
          std::make_pair(AVRC_PDU_GENERAL_REJECT, "AVRC_PDU_GENERAL_REJECT"),
  };
  for (const auto& pdu : pdus) {
    ASSERT_TRUE(dump_rc_pdu(pdu.first).starts_with(pdu.second));
  }
  ASSERT_TRUE(dump_rc_pdu(std::numeric_limits<uint8_t>::max()).starts_with("Unknown"));
}

TEST_F(BtifCoreWithControllerTest, btif_dm_get_connection_state__unconnected) {
  ASSERT_EQ(0, btif_dm_get_connection_state(kRawAddress));
}

TEST_F(BtifCoreWithConnectionTest, btif_dm_get_connection_state__connected_no_encryption) {
  test::mock::stack_btm_sec::BTM_IsEncrypted.body = [](const RawAddress& /* bd_addr */,
                                                       tBT_TRANSPORT transport) {
    switch (transport) {
      case BT_TRANSPORT_AUTO:
        return false;
      case BT_TRANSPORT_BR_EDR:
        return false;
      case BT_TRANSPORT_LE:
        return false;
    }
    return false;
  };
  ASSERT_EQ(1, btif_dm_get_connection_state(kRawAddress));
  test::mock::stack_btm_sec::BTM_IsEncrypted = {};
}

TEST_F(BtifCoreWithConnectionTest, btif_dm_get_connection_state__connected_classic_encryption) {
  test::mock::stack_btm_sec::BTM_IsEncrypted.body = [](const RawAddress& /* bd_addr */,
                                                       tBT_TRANSPORT transport) {
    switch (transport) {
      case BT_TRANSPORT_AUTO:
        return false;
      case BT_TRANSPORT_BR_EDR:
        return true;
      case BT_TRANSPORT_LE:
        return false;
    }
    return false;
  };
  ASSERT_EQ(3, btif_dm_get_connection_state(kRawAddress));

  test::mock::stack_btm_sec::BTM_IsEncrypted = {};
}

TEST_F(BtifCoreWithConnectionTest, btif_dm_get_connection_state__connected_le_encryption) {
  test::mock::stack_btm_sec::BTM_IsEncrypted.body = [](const RawAddress& /* bd_addr */,
                                                       tBT_TRANSPORT transport) {
    switch (transport) {
      case BT_TRANSPORT_AUTO:
        return false;
      case BT_TRANSPORT_BR_EDR:
        return false;
      case BT_TRANSPORT_LE:
        return true;
    }
    return false;
  };
  ASSERT_EQ(5, btif_dm_get_connection_state(kRawAddress));
  test::mock::stack_btm_sec::BTM_IsEncrypted = {};
}

TEST_F(BtifCoreWithConnectionTest, btif_dm_get_connection_state__connected_both_encryption) {
  test::mock::stack_btm_sec::BTM_IsEncrypted.body = [](const RawAddress& /* bd_addr */,
                                                       tBT_TRANSPORT transport) {
    switch (transport) {
      case BT_TRANSPORT_AUTO:
        return false;
      case BT_TRANSPORT_BR_EDR:
        return true;
      case BT_TRANSPORT_LE:
        return true;
    }
    return false;
  };
  ASSERT_EQ(7, btif_dm_get_connection_state(kRawAddress));
  test::mock::stack_btm_sec::BTM_IsEncrypted = {};
}

TEST_F(BtifCoreWithConnectionTest, btif_dm_get_connection_state_sync) {
  test::mock::stack_btm_sec::BTM_IsEncrypted.body = [](const RawAddress& /* bd_addr */,
                                                       tBT_TRANSPORT transport) {
    switch (transport) {
      case BT_TRANSPORT_AUTO:
        return false;
      case BT_TRANSPORT_BR_EDR:
        return true;
      case BT_TRANSPORT_LE:
        return true;
    }
    return false;
  };
  ASSERT_EQ(7, btif_dm_get_connection_state_sync(kRawAddress));

  test::mock::stack_btm_sec::BTM_IsEncrypted = {};
}

auto get_properties = [](const char* key, char* value, const char* /* default_value */) -> size_t {
  static bluetooth::bqr::BqrConfiguration config{
          .report_action = bluetooth::bqr::REPORT_ACTION_ADD,
          .quality_event_mask = 0x1ffff,  // Everything
          .minimum_report_interval_ms = 1000,
          .vnd_quality_mask = 29,
          .vnd_trace_mask = 5,
          .report_interval_multiple = 2,
  };
  if (std::string(key) == bluetooth::bqr::kpPropertyEventMask) {
    std::string event_mask = std::to_string(config.quality_event_mask);
    std::copy(event_mask.cbegin(), event_mask.cend(), value);
    return event_mask.size();
  }
  if (std::string(key) == bluetooth::bqr::kpPropertyMinReportIntervalMs) {
    std::string interval = std::to_string(config.minimum_report_interval_ms);
    std::copy(interval.cbegin(), interval.cend(), value);
    return interval.size();
  }
  return 0;
};

TEST_F(BtifCoreWithControllerTest, debug_dump_unconfigured) {
  int fds[2];
  ASSERT_EQ(0, socketpair(AF_LOCAL, SOCK_STREAM | SOCK_NONBLOCK, 0, fds));
  static int write_fd = fds[0];
  static int read_fd = fds[1];
  auto reading_promise = std::make_unique<std::promise<void>>();
  auto reading_done = reading_promise->get_future();

  do_in_main_thread(BindOnce([]() { bluetooth::bqr::DebugDump(write_fd); }));
  do_in_main_thread(BindOnce(
          [](std::unique_ptr<std::promise<void>> done_promise) {
            char line_buf[1024] = "";
            int bytes_read = read(read_fd, line_buf, 1024);
            EXPECT_GT(bytes_read, 0);
            EXPECT_NE(std::string(line_buf).find("Event queue is empty"), std::string::npos);
            done_promise->set_value();
          },
          std::move(reading_promise)));
  EXPECT_EQ(std::future_status::ready, reading_done.wait_for(std::chrono::seconds(1)));
  close(write_fd);
  close(read_fd);
}

class BtifCoreWithVendorSupportTest : public BtifCoreWithControllerTest {
protected:
  void SetUp() override {
    BtifCoreWithControllerTest::SetUp();
    bluetooth::hci::testing::mock_hci_layer_ = &hci_;
    test::mock::osi_properties::osi_property_get.body = get_properties;

    std::promise<void> configuration_promise;
    auto configuration_done = configuration_promise.get_future();
    EXPECT_CALL(hci_,
                EnqueueCommand(_, Matcher<ContextualOnceCallback<void(CommandCompleteView)>>(_)))
            .WillOnce(
                    // Replace with real PDL for 0xfc17
                    [&configuration_promise](
                            std::unique_ptr<CommandBuilder> cmd,
                            ContextualOnceCallback<void(CommandCompleteView)> callback) {
                      auto cmd_view = VendorCommandView::Create(
                              CommandView::Create(BuilderToView(std::move(cmd))));
                      EXPECT_TRUE(cmd_view.IsValid());
                      auto response = CommandCompleteView::Create(
                              EventView::Create(BuilderToView(CommandCompleteBuilder::Create(
                                      1, cmd_view.GetOpCode(), std::make_unique<RawBuilder>()))));
                      EXPECT_TRUE(response.IsValid());
                      callback(response);
                      configuration_promise.set_value();
                    })
            .RetiresOnSaturation();
    EXPECT_CALL(hci_,
                EnqueueCommand(_, Matcher<ContextualOnceCallback<void(CommandCompleteView)>>(_)))
            .WillOnce([](std::unique_ptr<CommandBuilder> cmd,
                         ContextualOnceCallback<void(CommandCompleteView)> callback) {
              auto cmd_view = ControllerBqrView::Create(VendorCommandView::Create(
                      CommandView::Create(BuilderToView(std::move(cmd)))));
              EXPECT_TRUE(cmd_view.IsValid());
              auto response = ControllerBqrCompleteView::Create(CommandCompleteView::Create(
                      EventView::Create(BuilderToView(ControllerBqrCompleteBuilder::Create(
                              1, ErrorCode::SUCCESS, cmd_view.GetBqrQualityEventMask())))));
              EXPECT_TRUE(response.IsValid());
              callback(response);
            })
            .RetiresOnSaturation();
    EXPECT_CALL(hci_, RegisterVendorSpecificEventHandler(VseSubeventCode::BQR_EVENT, _))
            .WillOnce(SaveArg<1>(&this->vse_callback_));
    do_in_main_thread(BindOnce([]() { bluetooth::bqr::EnableBtQualityReport(get_main()); }));
    ASSERT_EQ(std::future_status::ready, configuration_done.wait_for(std::chrono::seconds(1)));
  }

  void TearDown() override {
    std::promise<void> disable_promise;
    auto disable_future = disable_promise.get_future();
    auto set_promise = [&disable_promise]() { disable_promise.set_value(); };
    EXPECT_CALL(hci_, UnregisterVendorSpecificEventHandler(VseSubeventCode::BQR_EVENT));
    EXPECT_CALL(hci_,
                EnqueueCommand(_, Matcher<ContextualOnceCallback<void(CommandCompleteView)>>(_)))
            .WillOnce(Invoke(set_promise))
            .RetiresOnSaturation();
    do_in_main_thread(BindOnce([]() { bluetooth::bqr::DisableBtQualityReport(); }));
    ASSERT_EQ(std::future_status::ready, disable_future.wait_for(std::chrono::seconds(1)));

    bluetooth::hci::testing::mock_hci_layer_ = nullptr;
    BtifCoreWithControllerTest::TearDown();
  }
  bluetooth::hci::testing::MockHciLayer hci_;
  ContextualCallback<void(VendorSpecificEventView)> vse_callback_;
};

TEST_F(BtifCoreWithVendorSupportTest, configure_bqr_test) {}

TEST_F(BtifCoreWithVendorSupportTest, send_a2dp_audio_choppy) {
  std::promise<void> a2dp_event_promise;
  auto event_reported = a2dp_event_promise.get_future();
  callback_map_["link_quality_report_callback"] = [&a2dp_event_promise]() {
    a2dp_event_promise.set_value();
  };
  auto view = VendorSpecificEventView::Create(
          EventView::Create(BuilderToView(BqrLinkQualityEventBuilder::Create(
                  QualityReportId::A2DP_AUDIO_CHOPPY, BqrPacketType::TYPE_3DH3, 0x123,
                  Role::CENTRAL, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                  std::make_unique<RawBuilder>()))));
  EXPECT_TRUE(view.IsValid());
  vse_callback_(view);
  ASSERT_EQ(std::future_status::ready, event_reported.wait_for(std::chrono::seconds(1)));
}

TEST_F(BtifCoreWithVendorSupportTest, send_lmp_ll_trace) {
  auto payload = std::make_unique<RawBuilder>();
  payload->AddOctets({'d', 'a', 't', 'a'});
  auto view = VendorSpecificEventView::Create(EventView::Create(
          BuilderToView(BqrLmpLlMessageTraceEventBuilder::Create(0x123, std::move(payload)))));
  EXPECT_TRUE(view.IsValid());
  vse_callback_(view);
}

class BtifCoreVseWithSocketTest : public BtifCoreWithVendorSupportTest {
protected:
  void SetUp() override {
    BtifCoreWithVendorSupportTest::SetUp();
    int fds[2];
    ASSERT_EQ(0, socketpair(AF_LOCAL, SOCK_STREAM | SOCK_NONBLOCK, 0, fds));
    write_fd_ = fds[0];
    read_fd_ = fds[1];
  }
  void TearDown() override {
    BtifCoreWithVendorSupportTest::TearDown();
    close(write_fd_);
    close(read_fd_);
  }
  int write_fd_;
  int read_fd_;
};

TEST_F(BtifCoreVseWithSocketTest, debug_dump_empty) {
  static int write_fd = write_fd_;
  static int read_fd = read_fd_;
  auto reading_promise = std::make_unique<std::promise<void>>();
  auto reading_done = reading_promise->get_future();

  do_in_main_thread(BindOnce([]() { bluetooth::bqr::DebugDump(write_fd); }));
  do_in_main_thread(BindOnce(
          [](std::unique_ptr<std::promise<void>> done_promise) {
            char line_buf[1024] = "";
            int bytes_read = read(read_fd, line_buf, 1024);
            EXPECT_GT(bytes_read, 0);
            EXPECT_NE(std::string(line_buf).find("Event queue is empty"), std::string::npos);
            done_promise->set_value();
          },
          std::move(reading_promise)));
  EXPECT_EQ(std::future_status::ready, reading_done.wait_for(std::chrono::seconds(1)));
}

TEST_F(BtifCoreVseWithSocketTest, send_lmp_ll_msg) {
  auto payload = std::make_unique<RawBuilder>();
  payload->AddOctets({'d', 'a', 't', 'a'});
  auto view = VendorSpecificEventView::Create(EventView::Create(
          BuilderToView(BqrLmpLlMessageTraceEventBuilder::Create(0x123, std::move(payload)))));
  EXPECT_TRUE(view.IsValid());

  static int read_fd = read_fd_;
  auto reading_promise = std::make_unique<std::promise<void>>();
  auto reading_done = reading_promise->get_future();

  static int write_fd = write_fd_;
  do_in_main_thread(BindOnce([]() { bluetooth::bqr::SetLmpLlMessageTraceLogFd(write_fd); }));
  vse_callback_(view);

  do_in_main_thread(BindOnce(
          [](std::unique_ptr<std::promise<void>> done_promise) {
            char line_buf[1024] = "";
            std::string line;
            int bytes_read = read(read_fd, line_buf, 1024);
            EXPECT_GT(bytes_read, 0);
            line = std::string(line_buf);
            EXPECT_NE(line.find("Handle: 0x0123"), std::string::npos);
            EXPECT_NE(line.find("data"), std::string::npos);
            done_promise->set_value();
          },
          std::move(reading_promise)));
  EXPECT_EQ(std::future_status::ready, reading_done.wait_for(std::chrono::seconds(1)));
}

TEST_F(BtifCoreVseWithSocketTest, debug_dump_a2dp_choppy_no_payload) {
  auto payload = std::make_unique<RawBuilder>();
  auto view = VendorSpecificEventView::Create(
          EventView::Create(BuilderToView(BqrA2dpAudioChoppyEventBuilder::Create(
                  BqrPacketType::TYPE_3DH3, 0x123, Role::CENTRAL, 1, 2 /* rssi */, 3, 4,
                  5 /* afh_select_uni */, 6 /* lsto */, 7, 8, 9, 10, 11 /* last_tx_ack_timestamp */,
                  12, 13, 14, 15 /* buffer_underflow_bytes */, std::move(payload)))));
  EXPECT_TRUE(view.IsValid());
  vse_callback_(view);

  static int write_fd = write_fd_;
  static int read_fd = read_fd_;
  auto reading_promise = std::make_unique<std::promise<void>>();
  auto reading_done = reading_promise->get_future();

  do_in_main_thread(BindOnce([]() { bluetooth::bqr::DebugDump(write_fd); }));
  do_in_main_thread(BindOnce(
          [](std::unique_ptr<std::promise<void>> done_promise) {
            char line_buf[1024] = "";
            std::string line;
            int bytes_read = read(read_fd, line_buf, 1024);
            EXPECT_GT(bytes_read, 0);
            line = std::string(line_buf);
            EXPECT_EQ(line.find("Event queue is empty"), std::string::npos);
            EXPECT_NE(line.find("Handle: 0x0123"), std::string::npos);
            EXPECT_NE(line.find("UndFlow: 15"), std::string::npos);
            EXPECT_NE(line.find("A2DP Choppy"), std::string::npos);
            done_promise->set_value();
          },
          std::move(reading_promise)));
  EXPECT_EQ(std::future_status::ready, reading_done.wait_for(std::chrono::seconds(1)));
}

TEST_F(BtifCoreVseWithSocketTest, debug_dump_a2dp_choppy) {
  auto payload = std::make_unique<RawBuilder>();
  payload->AddOctets({'d', 'a', 't', 'a'});
  auto view = VendorSpecificEventView::Create(
          EventView::Create(BuilderToView(BqrA2dpAudioChoppyEventBuilder::Create(
                  BqrPacketType::TYPE_3DH3, 0x123, Role::CENTRAL, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
                  12, 13, 14, 15, std::move(payload)))));
  EXPECT_TRUE(view.IsValid());
  vse_callback_(view);

  static int write_fd = write_fd_;
  static int read_fd = read_fd_;
  auto reading_promise = std::make_unique<std::promise<void>>();
  auto reading_done = reading_promise->get_future();

  do_in_main_thread(BindOnce([]() { bluetooth::bqr::DebugDump(write_fd); }));
  do_in_main_thread(BindOnce(
          [](std::unique_ptr<std::promise<void>> done_promise) {
            char line_buf[1024] = "";
            std::string line;
            int bytes_read = read(read_fd, line_buf, 1024);
            EXPECT_GT(bytes_read, 0);
            line = std::string(line_buf);
            EXPECT_EQ(line.find("Event queue is empty"), std::string::npos);
            EXPECT_NE(line.find("Handle: 0x0123"), std::string::npos);
            EXPECT_NE(line.find("UndFlow: 15"), std::string::npos);
            EXPECT_NE(line.find("A2DP Choppy"), std::string::npos);
            done_promise->set_value();
          },
          std::move(reading_promise)));
  EXPECT_EQ(std::future_status::ready, reading_done.wait_for(std::chrono::seconds(1)));
}

class BtifCoreSocketTest : public BtifCoreWithControllerTest {
protected:
  void SetUp() override {
    BtifCoreWithControllerTest::SetUp();
    fake_osi_ = std::make_unique<test::fake::FakeOsi>();
    uid_set = uid_set_create();
    thread_t* kThreadPtr = reinterpret_cast<thread_t*>(0xbadbadbad);
    test::mock::osi_thread::thread_new.body = [kThreadPtr](const char* name) -> thread_t* {
      bluetooth::log::info("Explicitly not starting thread {}", name);
      return kThreadPtr;
    };
    test::mock::osi_thread::thread_free.body = [kThreadPtr](thread_t* ptr_to_free) {
      ASSERT_EQ(ptr_to_free, kThreadPtr);
    };
    btif_sock_init(uid_set);
  }

  void TearDown() override {
    test::mock::osi_thread::thread_new = {};
    test::mock::osi_thread::thread_free = {};
    btif_sock_cleanup();
    uid_set_destroy(uid_set);
    BtifCoreWithControllerTest::TearDown();
  }

  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
  uid_set_t* uid_set;
};

TEST_F(BtifCoreSocketTest, empty_test) {}

TEST_F(BtifCoreSocketTest, CreateRfcommServerSocket) {
  static constexpr int kChannelOne = 1;
  static constexpr int kFlags = 2;
  static constexpr int kAppUid = 3;
  const Uuid server_uuid = Uuid::From16Bit(UUID_SERVCLASS_SERIAL_PORT);
  int socket_number = 0;
  btsock_data_path_t data_path = BTSOCK_DATA_PATH_NO_OFFLOAD;
  uint64_t hub_id = 0;
  uint64_t endpoint_id = 0;
  int max_rx_packet_size = 0;
  ASSERT_EQ(BT_STATUS_SUCCESS,
            btif_sock_get_interface()->listen(
                    BTSOCK_RFCOMM, "TestService", &server_uuid, kChannelOne, &socket_number, kFlags,
                    kAppUid, data_path, "TestSocket", hub_id, endpoint_id, max_rx_packet_size));
}

TEST_F(BtifCoreSocketTest, CreateTwoRfcommServerSockets) {
  static constexpr int kChannelOne = 1;
  static constexpr int kFlags = 2;
  static constexpr int kAppUid = 3;
  const Uuid server_uuid = Uuid::From16Bit(UUID_SERVCLASS_SERIAL_PORT);
  int socket_number = 0;
  btsock_data_path_t data_path = BTSOCK_DATA_PATH_NO_OFFLOAD;
  uint64_t hub_id = 0;
  uint64_t endpoint_id = 0;
  int max_rx_packet_size = 0;
  ASSERT_EQ(BT_STATUS_SUCCESS,
            btif_sock_get_interface()->listen(
                    BTSOCK_RFCOMM, "TestService", &server_uuid, kChannelOne, &socket_number, kFlags,
                    kAppUid, data_path, "TestSocket", hub_id, endpoint_id, max_rx_packet_size));
  static constexpr int kChannelTwo = 2;
  static constexpr int kFlagsTwo = 4;
  static constexpr int kAppUidTwo = 6;
  const Uuid server_uuid_two = Uuid::FromString("12345678-1234-2345-3456-456789123456");
  int socket_number_two = 1;
  ASSERT_EQ(BT_STATUS_SUCCESS, btif_sock_get_interface()->listen(
                                       BTSOCK_RFCOMM, "ServiceTwo", &server_uuid_two, kChannelTwo,
                                       &socket_number_two, kFlagsTwo, kAppUidTwo, data_path,
                                       "TestSocket", hub_id, endpoint_id, max_rx_packet_size));
}

TEST_F(BtifCoreSocketTest, CreateManyRfcommServerSockets) {
  char server_uuid_str[] = "____5678-1234-2345-3456-456789123456";
  int number_of_sockets = 20;
  for (int i = 0; i < number_of_sockets; i++) {
    int channel = 11;
    int flags = 0;
    int app_uuid = i + 3;
    int socket_number = 0;
    server_uuid_str[3] = i % 10 + '0';
    server_uuid_str[2] = (i / 10) % 10 + '0';
    server_uuid_str[1] = (i / 100) % 10 + '0';
    server_uuid_str[0] = (i / 1000) % 10 + '0';
    Uuid server_uuid = Uuid::FromString(server_uuid_str);
    btsock_data_path_t data_path = BTSOCK_DATA_PATH_NO_OFFLOAD;
    uint64_t hub_id = 0;
    uint64_t endpoint_id = 0;
    int max_rx_packet_size = 0;
    ASSERT_EQ(BT_STATUS_SUCCESS,
              btif_sock_get_interface()->listen(
                      BTSOCK_RFCOMM, "TestService", &server_uuid, channel, &socket_number, flags,
                      app_uuid, data_path, "TestSocket", hub_id, endpoint_id, max_rx_packet_size));
    ASSERT_EQ(0, close(socket_number));
  }
}
