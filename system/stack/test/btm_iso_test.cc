/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <functional>

#include "btm_iso_api.h"
#include "btm_iso_api_types.h"
#include "hci/controller_mock.h"
#include "hci/hci_packets.h"
#include "hci/include/hci_layer.h"
#include "osi/include/allocator.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/hcidefs.h"
#include "stack/mock/mock_stack_hcic_layer.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_main_shim_hci_layer.h"

using bluetooth::hci::IsoManager;
using testing::_;
using testing::AnyNumber;
using testing::AtLeast;
using testing::Eq;
using testing::KilledBySignal;
using testing::Matcher;
using testing::Mock;
using testing::Return;
using testing::SaveArg;
using testing::StrictMock;
using testing::Test;

std::map<uint16_t, BtmDevice> AclHandleToMockBtmDevice = {};
const BtmDevice* btm_find_dev_by_handle(uint16_t handle) {
  return AclHandleToMockBtmDevice.count(handle) ? &AclHandleToMockBtmDevice.at(handle) : nullptr;
}
void BTM_LogHistory(const std::string& /* tag */, const RawAddress& /* bd_addr */,
                    const std::string& /* msg */, const std::string& /* extra */) {}

namespace bluetooth::shim {
class IsoInterface {
public:
  virtual void HciSend(BT_HDR* packet) = 0;
  virtual ~IsoInterface() = default;
};

class MockIsoInterface : public IsoInterface {
public:
  MOCK_METHOD((void), HciSend, (BT_HDR * p_msg), (override));
};

static MockIsoInterface* iso_interface = nullptr;
static void SetMockIsoInterface(MockIsoInterface* interface) { iso_interface = interface; }

static void set_data_cb(base::RepeatingCallback<void(BT_HDR*)> /* send_data_cb */) {
  FAIL() << __func__ << " should never be called";
}

static void transmit_command(const BT_HDR* /* command */,
                             command_complete_cb /* complete_callback */,
                             command_status_cb /* status_cb */, void* /* context */) {
  FAIL() << __func__ << " should never be called";
}

static void transmit_downward(void* data, uint16_t /* iso_Data_size */) {
  iso_interface->HciSend((BT_HDR*)data);
  osi_free(data);
}

static hci_t interface = {.set_data_cb = set_data_cb,
                          .transmit_command = transmit_command,
                          .transmit_downward = transmit_downward};

}  // namespace bluetooth::shim

namespace {
class MockCigCallbacks : public bluetooth::hci::iso_manager::CigCallbacks {
public:
  MockCigCallbacks() = default;
  MockCigCallbacks(const MockCigCallbacks&) = delete;
  MockCigCallbacks& operator=(const MockCigCallbacks&) = delete;

  ~MockCigCallbacks() override = default;

  MOCK_METHOD((void), OnSetupIsoDataPath, (uint8_t status, uint16_t conn_handle, uint8_t cig_id),
              (override));
  MOCK_METHOD((void), OnRemoveIsoDataPath, (uint8_t status, uint16_t conn_handle, uint8_t cig_id),
              (override));
  MOCK_METHOD((void), OnIsoLinkQualityRead,
              (uint16_t conn_handle, uint8_t cig_id, uint32_t tx_unacked_packets,
               uint32_t tx_flushed_packets, uint32_t tx_last_subevent_packets,
               uint32_t retransmitted_packets, uint32_t crc_error_packets,
               uint32_t rx_unreceived_packets, uint32_t duplicate_packets),
              (override));

  MOCK_METHOD((void), OnCisEvent, (uint8_t event, void* data), (override));
  MOCK_METHOD((void), OnCigEvent, (uint8_t event, void* data), (override));
};

class MockBigCallbacks : public bluetooth::hci::iso_manager::BigCallbacks {
public:
  MockBigCallbacks() = default;
  MockBigCallbacks(const MockBigCallbacks&) = delete;
  MockBigCallbacks& operator=(const MockBigCallbacks&) = delete;

  ~MockBigCallbacks() override = default;

  MOCK_METHOD((void), OnSetupIsoDataPath,
              (uint8_t status, uint16_t conn_handle, uint8_t big_handle), (override));
  MOCK_METHOD((void), OnRemoveIsoDataPath,
              (uint8_t status, uint16_t conn_handle, uint8_t big_handle), (override));

  MOCK_METHOD((void), OnBisEvent, (uint8_t event, void* data), (override));
  MOCK_METHOD((void), OnBigSourceEvent,
              (bluetooth::hci::iso_manager::BigSourceEvent event, void* data), (override));
  MOCK_METHOD((void), OnBigSinkEvent, (bluetooth::hci::iso_manager::BigSinkEvent event, void* data),
              (override));
};
}  // namespace

class IsoManagerTest : public Test {
protected:
  void SetUp() override {
    bluetooth::shim::SetMockIsoInterface(&iso_interface_);
    hcic::SetMockHcicInterface(&hcic_interface_);
    bluetooth::shim::testing::hci_layer_set_interface(&bluetooth::shim::interface);
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockController>();

    com_android_bluetooth_flags_reset_flags();
    set_com_android_bluetooth_flags_btm_iso_improve_canceling_iso(true);
    set_com_android_bluetooth_flags_btm_multi_client_support(true);

    big_callbacks_.reset(new MockBigCallbacks());
    cig_callbacks_.reset(new MockCigCallbacks());
    is_iso_active_ = false;
    AclHandleToMockBtmDevice = {};

    iso_sizes_.total_num_le_packets_ = 6;
    iso_sizes_.le_data_packet_length_ = 1024;
    ON_CALL(*bluetooth::hci::testing::mock_controller_, GetControllerIsoBufferSize())
            .WillByDefault(Return(iso_sizes_));

    InitIsoManager();
  }

  void TearDown() override {
    CleanupIsoManager();

    big_callbacks_.reset();
    cig_callbacks_.reset();

    bluetooth::shim::SetMockIsoInterface(nullptr);
    hcic::SetMockHcicInterface(nullptr);
    bluetooth::shim::testing::hci_layer_set_interface(nullptr);
    bluetooth::hci::testing::mock_controller_.reset();
  }

  virtual void InitIsoManager() {
    manager_instance_ = IsoManager::GetInstance();
    manager_instance_->Start();
    iso_callbacks_ = {
            .cig_callbacks = cig_callbacks_.get(),
            .big_callbacks = big_callbacks_.get(),
            .iso_traffic_active_callback = iso_traffic_active_callback_,
    };
    client_handle_ = manager_instance_->RegisterCallbacks(iso_callbacks_);

    // Default mock SetCigParams action
    volatile_test_cig_create_cmpl_evt_ = kDefaultCigParamsEvt;
    ON_CALL(hcic_interface_, SetCigParams)
            .WillByDefault([this](auto cig_id, auto,
                                  base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
              uint8_t hci_mock_rsp_buffer[3 + sizeof(uint16_t) *
                                                      this->volatile_test_cig_create_cmpl_evt_
                                                              .conn_handles.size()];
              uint8_t* p = hci_mock_rsp_buffer;

              UINT8_TO_STREAM(p, this->volatile_test_cig_create_cmpl_evt_.status);
              UINT8_TO_STREAM(p, cig_id);
              UINT8_TO_STREAM(p, this->volatile_test_cig_create_cmpl_evt_.conn_handles.size());
              for (auto handle : this->volatile_test_cig_create_cmpl_evt_.conn_handles) {
                UINT16_TO_STREAM(p, handle);
              }

              std::move(cb).Run(
                      hci_mock_rsp_buffer,
                      3 + sizeof(uint16_t) *
                                      this->volatile_test_cig_create_cmpl_evt_.conn_handles.size());
              return 0;
            });

    // Default mock CreateCis action
    ON_CALL(hcic_interface_, CreateCis)
            .WillByDefault([](uint8_t num_cis, const EXT_CIS_CREATE_CFG* cis_cfg,
                              base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
              for (const EXT_CIS_CREATE_CFG* cis = cis_cfg; num_cis != 0; num_cis--, cis++) {
                std::vector<uint8_t> buf(28);
                uint8_t* p = buf.data();
                UINT8_TO_STREAM(p, HCI_SUCCESS);
                UINT16_TO_STREAM(p, cis->cis_conn_handle);
                UINT24_TO_STREAM(p, 0xEA);    // CIG sync delay
                UINT24_TO_STREAM(p, 0xEB);    // CIS sync delay
                UINT24_TO_STREAM(p, 0xEC);    // transport latency c_to_p
                UINT24_TO_STREAM(p, 0xED);    // transport latency p_to_c
                UINT8_TO_STREAM(p, 0x01);     // phy c_to_p
                UINT8_TO_STREAM(p, 0x02);     // phy p_to_c
                UINT8_TO_STREAM(p, 0x01);     // nse
                UINT8_TO_STREAM(p, 0x02);     // bn c_to_p
                UINT8_TO_STREAM(p, 0x03);     // bn p_to_c
                UINT8_TO_STREAM(p, 0x04);     // ft c_to_p
                UINT8_TO_STREAM(p, 0x05);     // ft p_to_c
                UINT16_TO_STREAM(p, 0x00FA);  // Max PDU c_to_p
                UINT16_TO_STREAM(p, 0x00FB);  // Max PDU p_to_c
                UINT16_TO_STREAM(p, 0x0C60);  // ISO interval

                IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(),
                                                          buf.size());
              }
            });

    // Default mock disconnect action
    ON_CALL(hcic_interface_, Disconnect).WillByDefault([](uint16_t handle, uint8_t reason) {
      IsoManager::GetInstance()->HandleDisconnect(handle, reason);
    });

    // Default mock CreateBig HCI action
    volatile_test_big_params_evt_ = kDefaultBigParamsEvt;
    ON_CALL(hcic_interface_, CreateBig)
            .WillByDefault([this](auto big_handle,
                                  bluetooth::hci::iso_manager::big_create_params big_params) {
              std::vector<uint8_t> buf(big_params.num_bis * sizeof(uint16_t) + 18);
              uint8_t* p = buf.data();
              UINT8_TO_STREAM(p, HCI_SUCCESS);
              UINT8_TO_STREAM(p, big_handle);

              ASSERT_TRUE(big_params.num_bis <= volatile_test_big_params_evt_.conn_handles.size());

              UINT24_TO_STREAM(p, volatile_test_big_params_evt_.big_sync_delay);
              UINT24_TO_STREAM(p, volatile_test_big_params_evt_.transport_latency_big);
              UINT8_TO_STREAM(p, big_params.phy);
              UINT8_TO_STREAM(p, volatile_test_big_params_evt_.nse);
              UINT8_TO_STREAM(p, volatile_test_big_params_evt_.bn);
              UINT8_TO_STREAM(p, volatile_test_big_params_evt_.pto);
              UINT8_TO_STREAM(p, volatile_test_big_params_evt_.irc);
              UINT16_TO_STREAM(p, volatile_test_big_params_evt_.max_pdu);
              UINT16_TO_STREAM(p, volatile_test_big_params_evt_.iso_interval);

              UINT8_TO_STREAM(p, big_params.num_bis);
              for (auto i = 0; i < big_params.num_bis; ++i) {
                UINT16_TO_STREAM(p, volatile_test_big_params_evt_.conn_handles[i]);
              }

              IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT, buf.data(),
                                                        buf.size());
            });

    // Default mock TerminateBig HCI action
    ON_CALL(hcic_interface_, TerminateBig).WillByDefault([](auto big_handle, uint8_t reason) {
      std::vector<uint8_t> buf(2);
      uint8_t* p = buf.data();
      UINT8_TO_STREAM(p, big_handle);
      UINT8_TO_STREAM(p, reason);

      IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT, buf.data(), buf.size());
    });

    // Default mock SetupIsoDataPath HCI action
    ON_CALL(hcic_interface_, SetupIsoDataPath)
            .WillByDefault([](uint16_t conn_handle, uint8_t /* data_path_dir */,
                              uint8_t /* data_path_id */, uint8_t /* codec_id_format */,
                              uint16_t /* codec_id_company */, uint16_t /* codec_id_vendor */,
                              uint32_t /* controller_delay */,
                              std::vector<uint8_t> /* codec_conf */,
                              base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
              std::vector<uint8_t> buf(3);
              uint8_t* p = buf.data();
              UINT8_TO_STREAM(p, HCI_SUCCESS);
              UINT16_TO_STREAM(p, conn_handle);

              std::move(cb).Run(buf.data(), buf.size());
            });

    // Default mock RemoveIsoDataPath HCI action
    ON_CALL(hcic_interface_, RemoveIsoDataPath)
            .WillByDefault([](uint16_t conn_handle, uint8_t /* data_path_dir */,
                              base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
              std::vector<uint8_t> buf(3);
              uint8_t* p = buf.data();
              UINT8_TO_STREAM(p, HCI_SUCCESS);
              UINT16_TO_STREAM(p, conn_handle);

              std::move(cb).Run(buf.data(), buf.size());
            });

    // Default mock RemoveCig action
    ON_CALL(hcic_interface_, RemoveCig)
            .WillByDefault([](auto cig_id, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
              uint8_t hci_mock_rsp_buffer[2];
              uint8_t* p = hci_mock_rsp_buffer;
              UINT8_TO_STREAM(p, HCI_SUCCESS);
              UINT8_TO_STREAM(p, cig_id);
              std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
              return 0;
            });
  }

  virtual void CleanupIsoManager() {
    manager_instance_->DeregisterCallbacks(client_handle_);
    manager_instance_->Stop();
    manager_instance_ = nullptr;
  }

  static const bluetooth::hci::iso_manager::big_create_params kDefaultBigParams;
  static const bluetooth::hci::iso_manager::cig_create_params kDefaultCigParams;
  static const bluetooth::hci::iso_manager::cig_create_params kDefaultCigParams2;
  static const bluetooth::hci::iso_manager::cig_create_cmpl_evt kDefaultCigParamsEvt;
  static const bluetooth::hci::iso_manager::big_create_cmpl_evt kDefaultBigParamsEvt;
  static const bluetooth::hci::iso_manager::iso_data_path_params kDefaultIsoDataPathParams;

  bluetooth::hci::iso_manager::cig_create_cmpl_evt volatile_test_cig_create_cmpl_evt_;
  bluetooth::hci::iso_manager::big_create_cmpl_evt volatile_test_big_params_evt_;

  IsoManager* manager_instance_;
  bluetooth::shim::MockIsoInterface iso_interface_;
  hcic::MockHcicInterface hcic_interface_;
  bluetooth::hci::LeBufferSize iso_sizes_;

  bluetooth::hci::iso_manager::IsoClientHandle client_handle_ =
          bluetooth::hci::iso_manager::kInvalidIsoClientHandle;

  std::unique_ptr<MockBigCallbacks> big_callbacks_;
  std::unique_ptr<MockCigCallbacks> cig_callbacks_;
  bluetooth::hci::iso_manager::IsoManagerCallbacks iso_callbacks_;
  bool is_iso_active_ = false;
  std::function<void(bool)> iso_traffic_active_callback_ = [this](bool active) {
    is_iso_active_ = active;
  };
};

const bluetooth::hci::iso_manager::cig_create_cmpl_evt IsoManagerTest::kDefaultCigParamsEvt = {
        .status = 0x00,
        .cig_id = 128,
        .conn_handles = std::vector<uint16_t>({0x0EFF, 0x00FF}),
};

const bluetooth::hci::iso_manager::big_create_cmpl_evt IsoManagerTest::kDefaultBigParamsEvt = {
        .status = 0x00,
        .big_handle = 0,
        .big_sync_delay = 0x0080de,
        .transport_latency_big = 0x00cefe,
        .phy = 0x02,
        .nse = 4,
        .bn = 1,
        .pto = 0,
        .irc = 4,
        .max_pdu = 108,
        .iso_interval = 6,
        .conn_handles = std::vector<uint16_t>({0x0EFE, 0x0E00}),
};

const bluetooth::hci::iso_manager::iso_data_path_params IsoManagerTest::kDefaultIsoDataPathParams =
        {
                .data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut,
                .data_path_id = bluetooth::hci::iso_manager::kIsoDataPathHci,
                .codec_id_format = 0x06,
                .codec_id_company = 0,
                .codec_id_vendor = 0,
                .controller_delay = 0,
                .codec_conf = {0x02, 0x01, 0x02},
};

const bluetooth::hci::iso_manager::big_create_params IsoManagerTest::kDefaultBigParams = {
        .adv_handle = 0x00,
        .num_bis = 2,
        .sdu_itv = 0x002710,
        .max_sdu_size = 108,
        .max_transport_latency = 0x3c,
        .rtn = 3,
        .phy = 0x02,
        .packing = 0x00,
        .framing = 0x00,
        .enc = 0,
        .enc_code = std::array<uint8_t, 16>({0}),
};

const bluetooth::hci::iso_manager::cig_create_params IsoManagerTest::kDefaultCigParams = {
        .sdu_itv_c_to_p = 0x00002710,
        .sdu_itv_p_to_c = 0x00002711,
        .sca = bluetooth::hci::iso_manager::kIsoSca0To20Ppm,
        .packing = 0x00,
        .framing = 0x01,
        .max_trans_lat_c_to_p = 0x0009,
        .max_trans_lat_p_to_c = 0x000A,
        .cis_cfgs =
                {
                        // CIS #1
                        {
                                .cis_id = 1,
                                .max_sdu_size_c_to_p = 0x0028,
                                .max_sdu_size_p_to_c = 0x0027,
                                .phy_c_to_p = 0x04,
                                .phy_p_to_c = 0x03,
                                .rtn_c_to_p = 0x02,
                                .rtn_p_to_c = 0x01,
                        },
                        // CIS #2
                        {
                                .cis_id = 2,
                                .max_sdu_size_c_to_p = 0x0029,
                                .max_sdu_size_p_to_c = 0x002A,
                                .phy_c_to_p = 0x09,
                                .phy_p_to_c = 0x08,
                                .rtn_c_to_p = 0x07,
                                .rtn_p_to_c = 0x06,
                        },
                },
};

const bluetooth::hci::iso_manager::cig_create_params IsoManagerTest::kDefaultCigParams2 = {
        .sdu_itv_c_to_p = 0x00002709,
        .sdu_itv_p_to_c = 0x00002700,
        .sca = bluetooth::hci::iso_manager::kIsoSca0To20Ppm,
        .packing = 0x01,
        .framing = 0x00,
        .max_trans_lat_c_to_p = 0x0006,
        .max_trans_lat_p_to_c = 0x000B,
        .cis_cfgs =
                {
                        // CIS #1
                        {
                                .cis_id = 1,
                                .max_sdu_size_c_to_p = 0x0022,
                                .max_sdu_size_p_to_c = 0x0022,
                                .phy_c_to_p = 0x01,
                                .phy_p_to_c = 0x02,
                                .rtn_c_to_p = 0x02,
                                .rtn_p_to_c = 0x01,
                        },
                        // CIS #2
                        {
                                .cis_id = 2,
                                .max_sdu_size_c_to_p = 0x002A,
                                .max_sdu_size_p_to_c = 0x002B,
                                .phy_c_to_p = 0x06,
                                .phy_p_to_c = 0x06,
                                .rtn_c_to_p = 0x07,
                                .rtn_p_to_c = 0x07,
                        },
                },
};

class IsoManagerDeathTest : public IsoManagerTest {};


class IsoManagerDeathTestNoCleanup : public IsoManagerTest {
protected:
  void CleanupIsoManager() override { /* DO NOTHING */ }
};

static bool operator==(const EXT_CIS_CFG& x, const EXT_CIS_CFG& y) {
  return (x.cis_id == y.cis_id) && (x.max_sdu_size_c_to_p == y.max_sdu_size_c_to_p) &&
         (x.max_sdu_size_p_to_c == y.max_sdu_size_p_to_c) && (x.phy_c_to_p == y.phy_c_to_p) &&
         (x.phy_p_to_c == y.phy_p_to_c) && (x.rtn_c_to_p == y.rtn_c_to_p) &&
         (x.rtn_p_to_c == y.rtn_p_to_c);
}

static bool operator==(const struct bluetooth::hci::iso_manager::cig_create_params& x,
                       const struct bluetooth::hci::iso_manager::cig_create_params& y) {
  return (x.sdu_itv_c_to_p == y.sdu_itv_c_to_p) && (x.sdu_itv_p_to_c == y.sdu_itv_p_to_c) &&
         (x.sca == y.sca) && (x.packing == y.packing) && (x.framing == y.framing) &&
         (x.max_trans_lat_p_to_c == y.max_trans_lat_p_to_c) &&
         (x.max_trans_lat_c_to_p == y.max_trans_lat_c_to_p) &&
         std::is_permutation(x.cis_cfgs.begin(), x.cis_cfgs.end(), y.cis_cfgs.begin());
}

static bool operator==(const struct bluetooth::hci::iso_manager::big_create_params& x,
                       const struct bluetooth::hci::iso_manager::big_create_params& y) {
  return (x.adv_handle == y.adv_handle) && (x.num_bis == y.num_bis) && (x.sdu_itv == y.sdu_itv) &&
         (x.max_sdu_size == y.max_sdu_size) &&
         (x.max_transport_latency == y.max_transport_latency) && (x.rtn == y.rtn) &&
         (x.phy == y.phy) && (x.packing == y.packing) && (x.framing == y.framing) &&
         (x.enc == y.enc) && (x.enc_code == y.enc_code);
}

namespace iso_matchers {
MATCHER_P(Eq, value, "") { return arg == value; }
MATCHER_P2(EqPointedArray, value, len, "") { return !std::memcmp(arg, value, len); }
}  // namespace iso_matchers

struct BigSyncRaceTestParams {
  uint8_t big_handle;
  uint16_t sync_handle;
  uint8_t sync_established_status;    // Status for HCI_BLE_BIG_SYNC_EST_EVT
  uint8_t terminate_complete_status;  // Status for HCI_BLE_BIG_TERM_SYNC_CMPL_EVT
  bool established_first;  // true: Sync Established Event arrives first; false: Terminate Complete
                           // Event arrives first
  std::string test_name;   // Used to generate friendly test names
};

class BigSyncRaceTest : public IsoManagerTest,
                        public testing::WithParamInterface<BigSyncRaceTestParams> {
protected:
  void SetUp() override {
    IsoManagerTest::SetUp();
    set_com_android_bluetooth_flags_btm_broadcast_sink_support(true);
  }
};

TEST_P(BigSyncRaceTest, RaceConditionHandling) {
  // Get the current parameters
  const auto& params = GetParam();

  // Extract values from parameters
  const uint8_t big_handle = params.big_handle;
  const uint16_t sync_handle = params.sync_handle;

  bluetooth::hci::iso_manager::big_create_sync_params sync_params = {
          .big_handle = big_handle,
          .sync_handle = sync_handle,
  };

  // Assertion: We should only receive the terminate complete event, never the sync established
  // event.
  EXPECT_CALL(*big_callbacks_,
              OnBigSinkEvent(bluetooth::hci::iso_manager::BigSinkEvent::kSyncEst, _))
          .Times(0);

  bluetooth::hci::iso_manager::big_terminate_sync_cmpl_evt term_evt;
  // Assertion: We MUST receive the terminate complete event once.
  EXPECT_CALL(*big_callbacks_,
              OnBigSinkEvent(bluetooth::hci::iso_manager::BigSinkEvent::kTerminateSyncCmpl, _))
          .WillOnce([&term_evt](bluetooth::hci::iso_manager::BigSinkEvent /*type */, void* data) {
            term_evt =
                    *static_cast<bluetooth::hci::iso_manager::big_terminate_sync_cmpl_evt*>(data);
          });

  base::OnceCallback<void(uint8_t*, uint16_t)> captured_cb;
  EXPECT_CALL(hcic_interface_, BigTerminateSync)
          .WillOnce([&captured_cb](uint8_t, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            captured_cb = std::move(cb);
          });

  // 1. Create sync
  IsoManager::GetInstance()->BigCreateSync(client_handle_, sync_params);

  // 2. Terminate sync (race condition initiated)
  IsoManager::GetInstance()->BigTerminateSync(big_handle);

  // --- Simulate Event Race ---

  // Prepare HCI_BLE_BIG_Sync_Established_Event buffer
  std::vector<uint8_t> est_buf(14 + sizeof(uint16_t));
  uint8_t* p = est_buf.data();
  UINT8_TO_STREAM(p, params.sync_established_status);  // Use status from parameter
  UINT8_TO_STREAM(p, big_handle);

  // Prepare HCI_BLE_BIG_TERM_SYNC_CMPL_EVT buffer
  std::vector<uint8_t> term_buf(2);
  p = term_buf.data();
  UINT8_TO_STREAM(p, params.terminate_complete_status);  // Use status from parameter
  UINT8_TO_STREAM(p, big_handle);

  if (params.established_first) {
    // 3. Controller sends HCI_BLE_BIG_Sync_Established_Event (Arrives first)
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_BIG_SYNC_EST_EVT, est_buf.data(),
                                              est_buf.size());

    // 4. Controller sends HCI_BLE_BIG_TERM_SYNC_CMPL_EVT (Arrives second)
    std::move(captured_cb).Run(term_buf.data(), term_buf.size());
  } else {
    // 3. Controller sends HCI_BLE_BIG_TERM_SYNC_CMPL_EVT (Arrives first)
    std::move(captured_cb).Run(term_buf.data(), term_buf.size());

    // 4. Controller sends HCI_BLE_BIG_Sync_Established_Event (Arrives second)
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_BIG_SYNC_EST_EVT, est_buf.data(),
                                              est_buf.size());
  }
}
struct PrintTestName {
  std::string operator()(const testing::TestParamInfo<BigSyncRaceTestParams>& info) const {
    return info.param.test_name;
  }
};

INSTANTIATE_TEST_SUITE_P(
        BigSyncRaceConditions, BigSyncRaceTest,
        testing::Values(
                BigSyncRaceTestParams{
                        .big_handle = 0x23,
                        .sync_handle = 0x1234,
                        .sync_established_status = HCI_SUCCESS,
                        .terminate_complete_status = HCI_SUCCESS,
                        .established_first = true,
                        .test_name = "TerminateWhenSyncEstablishedSuccessAlreadyScheduled"},
                BigSyncRaceTestParams{
                        .big_handle = 0x24,
                        .sync_handle = 0x1235,
                        .sync_established_status = HCI_ERR_CONN_FAILED_ESTABLISHMENT,
                        .terminate_complete_status = HCI_ERR_COMMAND_DISALLOWED,
                        .established_first = true,
                        .test_name = "TerminateWhenSyncEstablishedFailureAlreadyScheduled"},
                BigSyncRaceTestParams{
                        .big_handle = 0x25,
                        .sync_handle = 0x1236,
                        .sync_established_status = HCI_ERR_CANCELLED_BY_LOCAL_HOST,
                        .terminate_complete_status = HCI_SUCCESS,
                        .established_first = true,
                        .test_name = "TerminateDuringSyncAndEstablishedCancelledFirst"},
                BigSyncRaceTestParams{.big_handle = 0x26,
                                      .sync_handle = 0x1237,
                                      .sync_established_status = HCI_ERR_CANCELLED_BY_LOCAL_HOST,
                                      .terminate_complete_status = HCI_SUCCESS,
                                      .established_first = false,
                                      .test_name = "TerminateDuringSyncAndTerminatedFirst"}),
        PrintTestName());

TEST_F(IsoManagerTest, SingletonAccess) {
  auto* iso_mgr = IsoManager::GetInstance();
  ASSERT_EQ(manager_instance_, iso_mgr);
}

TEST_F(IsoManagerTest, RegisterCallbacks) {
  auto* iso_mgr = IsoManager::GetInstance();
  ASSERT_EQ(manager_instance_, iso_mgr);

  bluetooth::hci::iso_manager::IsoManagerCallbacks callbacks = {
          .cig_callbacks = cig_callbacks_.get(),
          .big_callbacks = big_callbacks_.get(),
          .iso_traffic_active_callback = iso_traffic_active_callback_,
  };
  iso_mgr->RegisterCallbacks(callbacks);
}

TEST_F(IsoManagerTest, MultiClientCig) {
  // client_handle_ is the first client, registered in SetUp
  ASSERT_NE(client_handle_, bluetooth::hci::iso_manager::kInvalidIsoClientHandle);

  // Register a second client
  auto cig_callbacks2 = std::make_unique<MockCigCallbacks>();
  bluetooth::hci::iso_manager::IsoManagerCallbacks iso_callbacks2 = {
          .cig_callbacks = cig_callbacks2.get(),
          .big_callbacks = nullptr,
          .iso_traffic_active_callback = nullptr,
  };
  auto client_handle2 = manager_instance_->RegisterCallbacks(iso_callbacks2);
  ASSERT_NE(client_handle2, bluetooth::hci::iso_manager::kInvalidIsoClientHandle);
  ASSERT_NE(client_handle_, client_handle2);

  // CIG events should go to the right client
  uint8_t cig_id1 = 1;
  uint8_t cig_id2 = 2;

  EXPECT_CALL(hcic_interface_, SetCigParams(cig_id1, _, _))
          .WillOnce([&](auto cig_id, auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            uint8_t hci_mock_rsp_buffer[3 + sizeof(uint16_t) * 2];
            uint8_t* p = hci_mock_rsp_buffer;
            UINT8_TO_STREAM(p, HCI_SUCCESS);
            UINT8_TO_STREAM(p, cig_id);
            UINT8_TO_STREAM(p, 2);
            UINT16_TO_STREAM(p, 0x0EFF);
            UINT16_TO_STREAM(p, 0x00FF);
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .Times(1);
  EXPECT_CALL(*cig_callbacks2, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .Times(0);
  manager_instance_->CreateCig(client_handle_, cig_id1, kDefaultCigParams);

  EXPECT_CALL(hcic_interface_, SetCigParams(cig_id2, _, _))
          .WillOnce([&](auto cig_id, auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            uint8_t hci_mock_rsp_buffer[3 + sizeof(uint16_t) * 2];
            uint8_t* p = hci_mock_rsp_buffer;
            UINT8_TO_STREAM(p, HCI_SUCCESS);
            UINT8_TO_STREAM(p, cig_id);
            UINT8_TO_STREAM(p, 2);
            UINT16_TO_STREAM(p, 0x0EFE);
            UINT16_TO_STREAM(p, 0x00FE);
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .Times(0);
  EXPECT_CALL(*cig_callbacks2, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .Times(1);
  manager_instance_->CreateCig(client_handle2, cig_id2, kDefaultCigParams);

  Mock::VerifyAndClearExpectations(cig_callbacks_.get());
  Mock::VerifyAndClearExpectations(cig_callbacks2.get());

  // Remove CIGs
  EXPECT_CALL(hcic_interface_, RemoveCig(cig_id1, _)).Times(1);
  manager_instance_->RemoveCig(cig_id1);
  EXPECT_CALL(hcic_interface_, RemoveCig(cig_id2, _)).Times(1);
  manager_instance_->RemoveCig(cig_id2);
}

TEST_F(IsoManagerTest, MultiClientBig) {
  // client_handle_ is the first client, registered in SetUp
  ASSERT_NE(client_handle_, bluetooth::hci::iso_manager::kInvalidIsoClientHandle);

  // Register a second client
  auto big_callbacks2 = std::make_unique<MockBigCallbacks>();
  bluetooth::hci::iso_manager::IsoManagerCallbacks iso_callbacks2 = {
          .cig_callbacks = nullptr,
          .big_callbacks = big_callbacks2.get(),
          .iso_traffic_active_callback = nullptr,
  };
  auto client_handle2 = manager_instance_->RegisterCallbacks(iso_callbacks2);
  ASSERT_NE(client_handle2, bluetooth::hci::iso_manager::kInvalidIsoClientHandle);
  ASSERT_NE(client_handle_, client_handle2);

  // BIG events should go to the right client
  uint8_t big_id1 = 1;
  uint8_t big_id2 = 2;
  EXPECT_CALL(hcic_interface_, CreateBig(big_id1, _)).WillOnce([&](auto big_id, auto big_params) {
    std::vector<uint8_t> buf(big_params.num_bis * sizeof(uint16_t) + 18);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, HCI_SUCCESS);
    UINT8_TO_STREAM(p, big_id);
    UINT24_TO_STREAM(p, 0x0080de);
    UINT24_TO_STREAM(p, 0x00cefe);
    UINT8_TO_STREAM(p, big_params.phy);
    UINT8_TO_STREAM(p, 4);
    UINT8_TO_STREAM(p, 1);
    UINT8_TO_STREAM(p, 0);
    UINT8_TO_STREAM(p, 4);
    UINT16_TO_STREAM(p, 108);
    UINT16_TO_STREAM(p, 6);
    UINT8_TO_STREAM(p, big_params.num_bis);
    for (size_t i = 0; i < big_params.num_bis; ++i) {
      UINT16_TO_STREAM(p, 0x0E01 + i);
    }
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT, buf.data(), buf.size());
  });
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .Times(1);
  EXPECT_CALL(*big_callbacks2,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .Times(0);
  manager_instance_->CreateBig(client_handle_, big_id1, kDefaultBigParams);

  EXPECT_CALL(hcic_interface_, CreateBig(big_id2, _)).WillOnce([&](auto big_id, auto big_params) {
    std::vector<uint8_t> buf(big_params.num_bis * sizeof(uint16_t) + 18);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, HCI_SUCCESS);
    UINT8_TO_STREAM(p, big_id);
    UINT24_TO_STREAM(p, 0x0080de);
    UINT24_TO_STREAM(p, 0x00cefe);
    UINT8_TO_STREAM(p, big_params.phy);
    UINT8_TO_STREAM(p, 4);
    UINT8_TO_STREAM(p, 1);
    UINT8_TO_STREAM(p, 0);
    UINT8_TO_STREAM(p, 4);
    UINT16_TO_STREAM(p, 108);
    UINT16_TO_STREAM(p, 6);
    UINT8_TO_STREAM(p, big_params.num_bis);
    for (size_t i = 0; i < big_params.num_bis; ++i) {
      UINT16_TO_STREAM(p, 0x0F01 + i);
    }
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT, buf.data(), buf.size());
  });
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .Times(0);
  EXPECT_CALL(*big_callbacks2,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .Times(1);
  manager_instance_->CreateBig(client_handle2, big_id2, kDefaultBigParams);

  Mock::VerifyAndClearExpectations(big_callbacks_.get());
  Mock::VerifyAndClearExpectations(big_callbacks2.get());

  // Terminate BIGs
  EXPECT_CALL(hcic_interface_, TerminateBig(big_id1, _)).Times(1);
  manager_instance_->TerminateBig(big_id1, 0x16);
  EXPECT_CALL(hcic_interface_, TerminateBig(big_id2, _)).Times(1);
  manager_instance_->TerminateBig(big_id2, 0x16);
}

// Verify hci layer being called by the Iso Manager
TEST_F(IsoManagerTest, CreateCigHciCall) {
  for (uint8_t i = 220; i != 60; ++i) {
    EXPECT_CALL(hcic_interface_, SetCigParams(i, iso_matchers::Eq(kDefaultCigParams), _))
            .Times(1)
            .RetiresOnSaturation();
    IsoManager::GetInstance()->CreateCig(client_handle_, i, kDefaultCigParams);
  }
}

// Check handling create cig request twice with the same CIG id
TEST_F(IsoManagerDeathTest, CreateSameCigTwice) {
  bluetooth::hci::iso_manager::cig_create_cmpl_evt evt;
  evt.status = 0x01;
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .WillOnce([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_create_cmpl_evt*>(data);
            return 0;
          });

  volatile_test_cig_create_cmpl_evt_.cig_id = 127;
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ASSERT_EQ(evt.status, HCI_SUCCESS);

  // Second call with the same CIG ID should fail
  ASSERT_EXIT(IsoManager::GetInstance()->CreateCig(
                      client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id, kDefaultCigParams),
              KilledBySignal(SIGABRT), "already exists");
}

// Check for handling invalid length response from the faulty controller
TEST_F(IsoManagerDeathTest, CreateCigCallbackInvalidRspPacket) {
  uint8_t hci_mock_rsp_buffer[] = {0x00, 0x00};
  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&hci_mock_rsp_buffer](auto, auto,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });

  ASSERT_EXIT(IsoManager::GetInstance()->CreateCig(client_handle_, 128, kDefaultCigParams),
              KilledBySignal(SIGABRT), "Invalid packet length");
}

// Check for handling invalid length response from the faulty controller
TEST_F(IsoManagerDeathTest, CreateCigCallbackInvalidRspPacket2) {
  uint8_t hci_mock_rsp_buffer[] = {0x00, 0x00, 0x02, 0x01, 0x00};
  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&hci_mock_rsp_buffer](auto, auto,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });

  ASSERT_EXIT(IsoManager::GetInstance()->CreateCig(client_handle_, 128, kDefaultCigParams),
              KilledBySignal(SIGABRT), "Invalid CIS count");
}

// Check if IsoManager properly handles error responses from HCI layer
TEST_F(IsoManagerTest, CreateCigCallbackInvalidStatus) {
  uint8_t rsp_cig_id = 128;
  uint8_t rsp_status = 0x01;
  uint8_t rsp_cis_cnt = 3;
  uint8_t hci_mock_rsp_buffer[] = {rsp_status, rsp_cig_id, rsp_cis_cnt};

  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&hci_mock_rsp_buffer](auto, auto,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });

  bluetooth::hci::iso_manager::cig_create_cmpl_evt evt;
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .WillOnce([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_create_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->CreateCig(client_handle_, rsp_cig_id, kDefaultCigParams);
  ASSERT_EQ(evt.cig_id, rsp_cig_id);
  ASSERT_EQ(evt.status, rsp_status);
  ASSERT_TRUE(evt.conn_handles.empty());
}

// Check valid callback response
TEST_F(IsoManagerTest, CreateCigCallbackValid) {
  bluetooth::hci::iso_manager::cig_create_cmpl_evt evt;
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl, _))
          .WillOnce([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_create_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ASSERT_EQ(evt.cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
  ASSERT_EQ(evt.status, volatile_test_cig_create_cmpl_evt_.status);
  ASSERT_EQ(evt.conn_handles.size(), 2u);
  ASSERT_TRUE(std::is_permutation(evt.conn_handles.begin(), evt.conn_handles.end(),
                                  std::vector<uint16_t>({0x0EFF, 0x00FF}).begin()));
}

TEST_F(IsoManagerTest, CreateCigLateArrivingCallback) {
  // Catch the callback
  base::OnceCallback<void(uint8_t*, uint16_t)> iso_cb;
  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault(
                  [&](auto /* cig_id */, auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
                    iso_cb = std::move(cb);
                  });

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Stop the IsoManager before calling the callback
  IsoManager::GetInstance()->Stop();

  // Call the callback and expect no call
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(_, _)).Times(0);
  ASSERT_FALSE(iso_cb.is_null());

  uint8_t hci_mock_rsp_buffer[3 + sizeof(uint16_t) *
                                          volatile_test_cig_create_cmpl_evt_.conn_handles.size()];
  uint8_t* p = hci_mock_rsp_buffer;
  UINT8_TO_STREAM(p, volatile_test_cig_create_cmpl_evt_.status);
  UINT8_TO_STREAM(p, volatile_test_cig_create_cmpl_evt_.cig_id);
  UINT8_TO_STREAM(p, volatile_test_cig_create_cmpl_evt_.conn_handles.size());
  for (auto handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    UINT16_TO_STREAM(p, handle);
  }
  std::move(iso_cb).Run(
          hci_mock_rsp_buffer,
          3 + sizeof(uint16_t) * volatile_test_cig_create_cmpl_evt_.conn_handles.size());
}

// Check if CIG reconfigure triggers HCI layer call
TEST_F(IsoManagerTest, ReconfigureCigHciCall) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  EXPECT_CALL(hcic_interface_, SetCigParams(volatile_test_cig_create_cmpl_evt_.cig_id,
                                            iso_matchers::Eq(kDefaultCigParams), _))
          .Times(1);
  IsoManager::GetInstance()->ReconfigureCig(volatile_test_cig_create_cmpl_evt_.cig_id,
                                            kDefaultCigParams);
}

// Verify handlidng invalid call - reconfiguring invalid CIG
TEST_F(IsoManagerDeathTest, ReconfigureCigWithNoSuchCig) {
  ASSERT_EXIT(IsoManager::GetInstance()->ReconfigureCig(128, kDefaultCigParams),
              KilledBySignal(SIGABRT), "No such cig");
}

TEST_F(IsoManagerDeathTest, ReconfigureCigInvalidRspPacket) {
  uint8_t hci_mock_rsp_buffer[] = {0x00, 0x00};

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&hci_mock_rsp_buffer](auto, auto,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });
  ASSERT_EXIT(IsoManager::GetInstance()->ReconfigureCig(volatile_test_cig_create_cmpl_evt_.cig_id,
                                                        kDefaultCigParams),
              KilledBySignal(SIGABRT), "Invalid packet length");
}

TEST_F(IsoManagerDeathTest, ReconfigureCigInvalidRspPacket2) {
  uint8_t hci_mock_rsp_buffer[] = {0x00, 0x00, 0x02, 0x01, 0x00};

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&hci_mock_rsp_buffer](auto, auto,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });
  ASSERT_EXIT(IsoManager::GetInstance()->ReconfigureCig(volatile_test_cig_create_cmpl_evt_.cig_id,
                                                        kDefaultCigParams2),
              KilledBySignal(SIGABRT), "Invalid CIS count");
}

TEST_F(IsoManagerTest, ReconfigureCigInvalidStatus) {
  uint8_t rsp_cig_id = 128;
  uint8_t rsp_status = 0x01;
  uint8_t rsp_cis_cnt = 3;
  uint8_t hci_mock_rsp_buffer[] = {rsp_status, rsp_cig_id, rsp_cis_cnt};

  IsoManager::GetInstance()->CreateCig(client_handle_, rsp_cig_id, kDefaultCigParams);

  // Set-up the invalid response
  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&hci_mock_rsp_buffer](auto, auto,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });

  bluetooth::hci::iso_manager::cig_create_cmpl_evt evt;
  EXPECT_CALL(*cig_callbacks_,
              OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnReconfigureCmpl, _))
          .WillOnce([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_create_cmpl_evt*>(data);
            return 0;
          });
  IsoManager::GetInstance()->ReconfigureCig(rsp_cig_id, kDefaultCigParams2);

  ASSERT_EQ(evt.cig_id, rsp_cig_id);
  ASSERT_EQ(evt.status, rsp_status);
  ASSERT_TRUE(evt.conn_handles.empty());
}

TEST_F(IsoManagerTest, ReconfigureCigValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cig_create_cmpl_evt evt;
  EXPECT_CALL(*cig_callbacks_,
              OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnReconfigureCmpl, _))
          .WillOnce([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_create_cmpl_evt*>(data);
            return 0;
          });

  // Verify valid reconfiguration request
  IsoManager::GetInstance()->ReconfigureCig(volatile_test_cig_create_cmpl_evt_.cig_id,
                                            kDefaultCigParams2);
  ASSERT_EQ(evt.cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
  ASSERT_EQ(evt.status, volatile_test_cig_create_cmpl_evt_.status);
  ASSERT_TRUE(std::is_permutation(evt.conn_handles.begin(), evt.conn_handles.end(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.begin()));
}

TEST_F(IsoManagerTest, ReconfigureCigLateArrivingCallback) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Catch the callback
  base::OnceCallback<void(uint8_t*, uint16_t)> iso_cb;
  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault(
                  [&](auto /* cig_id */, auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
                    iso_cb = std::move(cb);
                  });
  IsoManager::GetInstance()->ReconfigureCig(volatile_test_cig_create_cmpl_evt_.cig_id,
                                            kDefaultCigParams2);

  // Stop the IsoManager before calling the callback
  IsoManager::GetInstance()->Stop();

  // Call the callback and expect no call
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(_, _)).Times(0);
  ASSERT_FALSE(iso_cb.is_null());

  uint8_t hci_mock_rsp_buffer[3 + sizeof(uint16_t) *
                                          volatile_test_cig_create_cmpl_evt_.conn_handles.size()];
  uint8_t* p = hci_mock_rsp_buffer;
  UINT8_TO_STREAM(p, volatile_test_cig_create_cmpl_evt_.status);
  UINT8_TO_STREAM(p, volatile_test_cig_create_cmpl_evt_.cig_id);
  UINT8_TO_STREAM(p, volatile_test_cig_create_cmpl_evt_.conn_handles.size());
  for (auto handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    UINT16_TO_STREAM(p, handle);
  }
  std::move(iso_cb).Run(
          hci_mock_rsp_buffer,
          3 + sizeof(uint16_t) * volatile_test_cig_create_cmpl_evt_.conn_handles.size());
}

TEST_F(IsoManagerTest, RemoveCigHciCall) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  EXPECT_CALL(hcic_interface_, RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id, _)).Times(1);
  IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id);
}

TEST_F(IsoManagerDeathTest, RemoveCigWithNoSuchCig) {
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id),
              KilledBySignal(SIGABRT), "No such cig");
}

TEST_F(IsoManagerDeathTest, RemoveCigForceNoSuchCig) {
  ASSERT_DEATH(
          IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id, true),
          "Invalid iso_client for cig");
}

TEST_F(IsoManagerDeathTest, DeregisterDuringCigCreation) {
  base::OnceCallback<void(uint8_t*, uint16_t)> captured_callback;
  ON_CALL(hcic_interface_, SetCigParams)
          .WillByDefault([&](uint8_t, auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            captured_callback = std::move(cb);
            return 0;
          });

  uint8_t cig_id = 1;
  IsoManager::GetInstance()->CreateCig(client_handle_, cig_id, kDefaultCigParams);

  IsoManager::GetInstance()->DeregisterCallbacks(client_handle_);

  ASSERT_DEATH(
          {
            uint8_t hci_mock_rsp_buffer[3 + sizeof(uint16_t) * kDefaultCigParams.cis_cfgs.size()];
            uint8_t* p = hci_mock_rsp_buffer;
            UINT8_TO_STREAM(p, HCI_SUCCESS);
            UINT8_TO_STREAM(p, cig_id);
            UINT8_TO_STREAM(p, kDefaultCigParams.cis_cfgs.size());
            for (size_t i = 0; i < kDefaultCigParams.cis_cfgs.size(); i++) {
              UINT16_TO_STREAM(p, 0x0EFF + i);
            }
            std::move(captured_callback).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
          },
          "Invalid iso_client for cig");
}

TEST_F(IsoManagerDeathTest, DeregisterDuringBigCreation) {
  ON_CALL(hcic_interface_, CreateBig).WillByDefault([](auto, auto) {
    /* We override default mock. Nothing to do here */
  });

  uint8_t big_handle = 1;
  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);

  IsoManager::GetInstance()->DeregisterCallbacks(client_handle_);

  ASSERT_DEATH(
          {
            std::vector<uint8_t> buf(kDefaultBigParams.num_bis * sizeof(uint16_t) + 18);
            uint8_t* p = buf.data();
            UINT8_TO_STREAM(p, HCI_SUCCESS);
            UINT8_TO_STREAM(p, big_handle);
            UINT24_TO_STREAM(p, 0x0080de);
            UINT24_TO_STREAM(p, 0x00cefe);
            UINT8_TO_STREAM(p, kDefaultBigParams.phy);
            UINT8_TO_STREAM(p, 4);
            UINT8_TO_STREAM(p, 1);
            UINT8_TO_STREAM(p, 0);
            UINT8_TO_STREAM(p, 4);
            UINT16_TO_STREAM(p, 108);
            UINT16_TO_STREAM(p, 6);
            UINT8_TO_STREAM(p, kDefaultBigParams.num_bis);
            for (size_t i = 0; i < kDefaultBigParams.num_bis; ++i) {
              UINT16_TO_STREAM(p, 0x0EFE + i);
            }
            IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT, buf.data(),
                                                      buf.size());
          },
          "Cannot find client callbacks for big");
}

TEST_F(IsoManagerDeathTest, RemoveSameCigTwice) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  ON_CALL(hcic_interface_, RemoveCig)
          .WillByDefault([this](auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            uint8_t hci_mock_rsp_buffer[2];
            uint8_t* p = hci_mock_rsp_buffer;

            UINT8_TO_STREAM(p, HCI_SUCCESS);
            UINT8_TO_STREAM(p, this->volatile_test_cig_create_cmpl_evt_.cig_id);

            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });

  IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id);

  ASSERT_EXIT(IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id),
              KilledBySignal(SIGABRT), "No such cig");
}

TEST_F(IsoManagerDeathTest, RemoveCigInvalidRspPacket) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  ON_CALL(hcic_interface_, RemoveCig)
          .WillByDefault([](auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            uint8_t hci_mock_rsp_buffer[] = {0x00};  // status byte only

            std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
            return 0;
          });
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id),
              KilledBySignal(SIGABRT), "Invalid packet length");
}

TEST_F(IsoManagerTest, RemoveCigInvalidStatus) {
  uint8_t rsp_status = 0x02;
  uint8_t hci_mock_rsp_buffer[] = {rsp_status, volatile_test_cig_create_cmpl_evt_.cig_id};

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  ON_CALL(hcic_interface_, RemoveCig)
          .WillByDefault(
                  [&hci_mock_rsp_buffer](auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
                    std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
                    return 0;
                  });

  bluetooth::hci::iso_manager::cig_remove_cmpl_evt evt;
  ON_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnRemoveCmpl, _))
          .WillByDefault([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_remove_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id);
  ASSERT_EQ(evt.cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
  ASSERT_EQ(evt.status, rsp_status);
}

TEST_F(IsoManagerTest, RemoveCigValid) {
  uint8_t hci_mock_rsp_buffer[] = {HCI_SUCCESS, volatile_test_cig_create_cmpl_evt_.cig_id};

  ASSERT_EQ(is_iso_active_, false);
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ASSERT_EQ(is_iso_active_, true);

  ON_CALL(hcic_interface_, RemoveCig)
          .WillByDefault(
                  [&hci_mock_rsp_buffer](auto, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
                    std::move(cb).Run(hci_mock_rsp_buffer, sizeof(hci_mock_rsp_buffer));
                    return 0;
                  });

  bluetooth::hci::iso_manager::cig_remove_cmpl_evt evt;
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(bluetooth::hci::iso_manager::kIsoEventCigOnRemoveCmpl, _))
          .WillOnce([&evt](uint8_t /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::cig_remove_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id);
  ASSERT_EQ(evt.cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
  ASSERT_EQ(evt.status, HCI_SUCCESS);
  ASSERT_EQ(is_iso_active_, false);
}

TEST_F(IsoManagerTest, EstablishCisHciCall) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }

  EXPECT_CALL(hcic_interface_,
              CreateCis(2,
                        iso_matchers::EqPointedArray(
                                params.conn_pairs.data(),
                                params.conn_pairs.size() * sizeof(params.conn_pairs.data()[0])),
                        _))
          .Times(1);
  IsoManager::GetInstance()->EstablishCis(params);
}

TEST_F(IsoManagerDeathTest, EstablishCisWithNoSuchCis) {
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }

  ASSERT_EXIT(IsoManager::GetInstance()->IsoManager::GetInstance()->EstablishCis(params),
              KilledBySignal(SIGABRT), "No such cis");
}

TEST_F(IsoManagerDeathTest, ConnectSameCisTwice) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  ASSERT_EXIT(IsoManager::GetInstance()->IsoManager::GetInstance()->EstablishCis(params),
              KilledBySignal(SIGABRT), "already connected/connecting/cancelled");
}

TEST_F(IsoManagerDeathTest, EstablishCisInvalidResponsePacket) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([this](uint8_t /* num_cis */, const EXT_CIS_CREATE_CFG* /* cis_cfg */,
                                base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
            for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
              std::vector<uint8_t> buf(27);
              uint8_t* p = buf.data();
              UINT8_TO_STREAM(p, HCI_SUCCESS);
              UINT16_TO_STREAM(p, handle);
              UINT24_TO_STREAM(p, 0xEA);    // CIG sync delay
              UINT24_TO_STREAM(p, 0xEB);    // CIS sync delay
              UINT24_TO_STREAM(p, 0xEC);    // transport latency c_to_p
              UINT24_TO_STREAM(p, 0xED);    // transport latency p_to_c
              UINT8_TO_STREAM(p, 0x01);     // phy c_to_p
              UINT8_TO_STREAM(p, 0x02);     // phy p_to_c
              UINT8_TO_STREAM(p, 0x01);     // nse
              UINT8_TO_STREAM(p, 0x02);     // bn c_to_p
              UINT8_TO_STREAM(p, 0x03);     // bn p_to_c
              UINT8_TO_STREAM(p, 0x04);     // ft c_to_p
              UINT8_TO_STREAM(p, 0x05);     // ft p_to_c
              UINT16_TO_STREAM(p, 0x00FA);  // Max PDU c_to_p
              UINT16_TO_STREAM(p, 0x00FB);  // Max PDU p_to_c

              IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(),
                                                        buf.size());
            }
          });

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }

  ASSERT_EXIT(IsoManager::GetInstance()->IsoManager::GetInstance()->EstablishCis(params),
              KilledBySignal(SIGABRT), "Invalid packet length");
}

TEST_F(IsoManagerTest, EstablishCisInvalidCommandStatus) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  uint16_t invalid_status = 0x0001;

  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([invalid_status](uint8_t /* num_cis */,
                                          const EXT_CIS_CREATE_CFG* /* cis_cfg */,
                                          base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::move(cb).Run((uint8_t*)&invalid_status, sizeof(invalid_status));
            return 0;
          });

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this, invalid_status](uint8_t /* type */, void* data) {
            bluetooth::hci::iso_manager::cis_establish_cmpl_evt* evt =
                    static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(data);

            ASSERT_EQ(evt->status, invalid_status);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  evt->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  // Establish all CISes
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);
}

TEST_F(IsoManagerTest, EstablishCisInvalidStatus) {
  uint8_t cig_id = volatile_test_cig_create_cmpl_evt_.cig_id;
  IsoManager::GetInstance()->CreateCig(client_handle_, cig_id, kDefaultCigParams);
  uint8_t invalid_status = 0x01;

  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([this, invalid_status](
                                 uint8_t /* num_cis */, const EXT_CIS_CREATE_CFG* /* cis_cfg */,
                                 base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
            for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
              std::vector<uint8_t> buf(28);
              uint8_t* p = buf.data();
              UINT8_TO_STREAM(p, invalid_status);
              UINT16_TO_STREAM(p, handle);
              UINT24_TO_STREAM(p, 0xEA);    // CIG sync delay
              UINT24_TO_STREAM(p, 0xEB);    // CIS sync delay
              UINT24_TO_STREAM(p, 0xEC);    // transport latency c_to_p
              UINT24_TO_STREAM(p, 0xED);    // transport latency p_to_c
              UINT8_TO_STREAM(p, 0x01);     // phy c_to_p
              UINT8_TO_STREAM(p, 0x02);     // phy p_to_c
              UINT8_TO_STREAM(p, 0x01);     // nse
              UINT8_TO_STREAM(p, 0x02);     // bn c_to_p
              UINT8_TO_STREAM(p, 0x03);     // bn p_to_c
              UINT8_TO_STREAM(p, 0x04);     // ft c_to_p
              UINT8_TO_STREAM(p, 0x05);     // ft p_to_c
              UINT16_TO_STREAM(p, 0x00FA);  // Max PDU c_to_p
              UINT16_TO_STREAM(p, 0x00FB);  // Max PDU p_to_c
              UINT16_TO_STREAM(p, 0x0C60);  // ISO interval

              IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(),
                                                        buf.size());
            }
          });

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this, invalid_status, cig_id](uint8_t /* type */, void* data) {
            bluetooth::hci::iso_manager::cis_establish_cmpl_evt* evt =
                    static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(data);

            ASSERT_EQ(evt->status, invalid_status);
            ASSERT_EQ(evt->cig_id, cig_id);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  evt->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);
}

TEST_F(IsoManagerTest, EstablishCisValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            bluetooth::hci::iso_manager::cis_establish_cmpl_evt* evt =
                    static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(data);

            ASSERT_EQ(evt->status, HCI_SUCCESS);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  evt->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);
}

TEST_F(IsoManagerTest, EstablishCisLateArrivingCallback) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Catch the callback
  base::OnceCallback<void(uint8_t*, uint16_t)> iso_cb;
  EXT_CIS_CREATE_CFG cis_create_cfg;
  uint8_t cis_num = 0;
  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([&](uint8_t num_cis, const EXT_CIS_CREATE_CFG* cis_cfg,
                             base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            cis_create_cfg = *cis_cfg;
            cis_num = num_cis;
            iso_cb = std::move(cb);
          });

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  // Stop the IsoManager before calling the callback
  IsoManager::GetInstance()->Stop();

  // Call the callback and expect no call
  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(0);
  ASSERT_FALSE(iso_cb.is_null());

  // Command complete with error will trigger the callback without
  // injecting any additional HCI events
  std::vector<uint8_t> buf(1);
  uint8_t* p = buf.data();
  UINT8_TO_STREAM(p, 0x01);  // status
  std::move(iso_cb).Run(buf.data(), buf.size());
}

TEST_F(IsoManagerTest, CancelPendingCreateCis_EstablishedThenDisconnected) {
  /**
   * Verify the HCI Disconnect command will cancel pending CIS creation.
   * As the Core is not strict about event order, in this scenario HCI CIS Established event comes
   * before HCI Disconnection Complete event.
   *
   * Scenario:
   * 1. Issue the HCI LE Create CIS command.
   * 2. Issue HCI Disconnect command with CIS connection handle parameter before the HCI CIS
   *    Established event is received.
   * 3. Verify the kIsoEventCisEstablishCmpl event is generated once HCI CIS Established event is
   *    received with Operation Cancelled By Local Host error.
   * 4. Verify the kIsoEventCisDisconnected event is generated once HCI Disconnection Complete event
   *    is received.
   */

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([](uint8_t, const EXT_CIS_CREATE_CFG*,
                            base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
            /* We override default mock. Nothing to do here */
          });

  ON_CALL(hcic_interface_, Disconnect).WillByDefault([](uint16_t, uint8_t) {
    /* We override default mock. Nothing to do here */
  });

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(0);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);
            ASSERT_EQ(event->reason, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
            ASSERT_EQ(event->cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  event->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  EXPECT_CALL(hcic_interface_, CreateCis).Times(1);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  EXPECT_CALL(hcic_interface_, Disconnect).Times(kDefaultCigParams.cis_cfgs.size());

  /* Cancel pending HCI LE Create CIS command */
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->DisconnectCis(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    std::vector<uint8_t> buf(28, 0);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, HCI_ERR_CANCELLED_BY_LOCAL_HOST);
    UINT16_TO_STREAM(p, handle);

    /* inject HCI LE CIS Established event */
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(), buf.size());

    /* followed by HCI Disconnection Complete event */
    IsoManager::GetInstance()->HandleDisconnect(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }
}

TEST_F(IsoManagerTest, CancelPendingCreateCis_DisconnectedThenEstablished) {
  /**
   * Verify the HCI Disconnect command will cancel pending CIS creation.
   * As the Core is not strict about event order, in this scenario HCI Disconnection Complete event
   * comes before HCI CIS Established event.
   *
   * Scenario:
   * 1. Issue the HCI LE Create CIS command.
   * 2. Issue HCI Disconnect command with CIS connection handle parameter before the HCI CIS
   *    Established event is received.
   * 3. Verify the kIsoEventCisEstablishCmpl event is generated once HCI CIS Established event is
   *    received with Operation Cancelled By Local Host error.
   * 4. Verify the kIsoEventCisDisconnected event is generated once HCI Disconnection Complete event
   *    is received.
   */

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([](uint8_t, const EXT_CIS_CREATE_CFG*,
                            base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
            /* We override default mock. Nothing to do here */
          });

  ON_CALL(hcic_interface_, Disconnect).WillByDefault([](uint16_t, uint8_t) {
    /* We override default mock. Nothing to do here */
  });

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(0);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);
            ASSERT_EQ(event->reason, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
            ASSERT_EQ(event->cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  event->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  EXPECT_CALL(hcic_interface_, CreateCis).Times(1);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  EXPECT_CALL(hcic_interface_, Disconnect).Times(kDefaultCigParams.cis_cfgs.size());

  /* Cancel pending HCI LE Create CIS command */
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->DisconnectCis(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    /* inject HCI Disconnection Complete event */
    IsoManager::GetInstance()->HandleDisconnect(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);

    std::vector<uint8_t> buf(28, 0);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, HCI_ERR_CANCELLED_BY_LOCAL_HOST);
    UINT16_TO_STREAM(p, handle);

    /* followed by inject HCI LE CIS Established event */
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(), buf.size());
  }
}

TEST_F(IsoManagerTest, CancelPendingCreateCis_Race_EstablishedSucceedJustAfterSendingDisconnect) {
  /**
   * Verify the HCI Disconnect command will cancel pending CIS creation.
   * As the Core is not strict about event order, in this scenario HCI CIS Established event comes
   * before HCI Disconnection Complete event.
   *
   * Scenario:
   * 1. Issue the HCI LE Create CIS command.
   * 2. Issue HCI Disconnect command with CIS connection handle parameter before the HCI CIS
   *    Established event is received.
   * 3. Controller sends kIsoEventCisEstablishCmpl event with a SUCCESS.
   * 4. Verify the kIsoEventCisDisconnected event is generated once HCI Disconnection Complete event
   *    is received and kIsoEventCisEstablishCmpl is not generated
   */

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([](uint8_t, const EXT_CIS_CREATE_CFG*,
                            base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
            /* We override default mock. Nothing to do here */
          });

  ON_CALL(hcic_interface_, Disconnect).WillByDefault([](uint16_t, uint8_t) {
    /* We override default mock. Nothing to do here */
  });

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(0);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);
            ASSERT_EQ(event->reason, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
            ASSERT_EQ(event->cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  event->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  EXPECT_CALL(hcic_interface_, CreateCis).Times(1);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  EXPECT_CALL(hcic_interface_, Disconnect).Times(kDefaultCigParams.cis_cfgs.size());

  /* Cancel pending HCI LE Create CIS command */
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->DisconnectCis(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    std::vector<uint8_t> buf(28, 0);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, HCI_SUCCESS);
    UINT16_TO_STREAM(p, handle);

    /* inject HCI LE CIS Established event */
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(), buf.size());

    /* followed by HCI Disconnection Complete event */
    IsoManager::GetInstance()->HandleDisconnect(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }
}

TEST_F(IsoManagerTest, CancelPendingCreateCis_Race_EstablishedFailedJustAfterSendingDisconnect) {
  /**
   * Verify the HCI Disconnect command will cancel pending CIS creation.
   * As the Core is not strict about event order, in this scenario HCI CIS Established event comes
   * before HCI Disconnection Complete event.
   *
   * Scenario:
   * 1. Issue the HCI LE Create CIS command.
   * 2. Issue HCI Disconnect command with CIS connection handle parameter before the HCI CIS
   *    Established event is received.
   * 3. Controller sends kIsoEventCisEstablishCmpl event with a Failure.
   * 4. Verify the kIsoEventCisDisconnected event is generated once HCI Disconnection Complete event
   *    is received and kIsoEventCisEstablishCmpl is not generated
   */

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  ON_CALL(hcic_interface_, CreateCis)
          .WillByDefault([](uint8_t, const EXT_CIS_CREATE_CFG*,
                            base::OnceCallback<void(uint8_t*, uint16_t)> /* cb */) {
            /* We override default mock. Nothing to do here */
          });

  ON_CALL(hcic_interface_, Disconnect).WillByDefault([](uint16_t, uint8_t) {
    /* We override default mock. Nothing to do here */
  });

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(0);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);
            ASSERT_EQ(event->reason, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
            ASSERT_EQ(event->cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  event->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  EXPECT_CALL(hcic_interface_, CreateCis).Times(1);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  EXPECT_CALL(hcic_interface_, Disconnect).Times(kDefaultCigParams.cis_cfgs.size());

  /* Cancel pending HCI LE Create CIS command */
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->DisconnectCis(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    std::vector<uint8_t> buf(28, 0);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, HCI_ERR_CONN_FAILED_ESTABLISHMENT);
    UINT16_TO_STREAM(p, handle);

    /* inject HCI LE CIS Established event */
    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(), buf.size());

    /* followed by HCI Disconnection Complete event */
    IsoManager::GetInstance()->HandleDisconnect(handle, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }
}

TEST_F(IsoManagerTest, ReconnectCisValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  // trigger HCI disconnection event
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->HandleDisconnect(handle, 0x16);
  }

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            bluetooth::hci::iso_manager::cis_establish_cmpl_evt* evt =
                    static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(data);

            ASSERT_EQ(evt->status, HCI_SUCCESS);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  evt->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });
  IsoManager::GetInstance()->EstablishCis(params);
}

TEST_F(IsoManagerTest, DisconnectCisHciCall) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    EXPECT_CALL(hcic_interface_, Disconnect(handle, 0x16)).Times(1).RetiresOnSaturation();
    IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, 0x16);
  }
}

TEST_F(IsoManagerDeathTest, DisconnectCisWithNoSuchCis) {
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    ASSERT_EXIT(IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, 0x16),
                KilledBySignal(SIGABRT), "No such cis");
  }
}

TEST_F(IsoManagerDeathTest, DisconnectSameCisTwice) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, 0x16);
  }

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    ASSERT_EXIT(IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, 0x16),
                KilledBySignal(SIGABRT), "Not connected");
  }
}

TEST_F(IsoManagerTest, DisconnectCisValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  uint8_t disconnect_reason = 0x16;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    EXPECT_CALL(*cig_callbacks_, OnCisEvent)
            .WillOnce([this, handle, disconnect_reason](uint8_t event_code, void* data) {
              ASSERT_EQ(event_code, bluetooth::hci::iso_manager::kIsoEventCisDisconnected);
              auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);
              ASSERT_EQ(event->reason, disconnect_reason);
              ASSERT_EQ(event->cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
              ASSERT_EQ(event->cis_conn_hdl, handle);
            })
            .RetiresOnSaturation();
    IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, disconnect_reason);
  }
}

// Check if we properly ignore not ISO related disconnect events
TEST_F(IsoManagerDeathTest, DisconnectCisInvalidResponse) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  // Make the HCI layer send invalid handles in disconnect event
  ON_CALL(hcic_interface_, Disconnect).WillByDefault([](uint16_t handle, uint8_t reason) {
    IsoManager::GetInstance()->HandleDisconnect(handle + 1, reason);
  });

  // We don't expect any calls as these are not ISO handles
  ON_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .WillByDefault([](uint8_t /* event_code */, void* /* data */) { FAIL(); });

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, 0x16);
  }
}

TEST_F(IsoManagerTest, CreateBigHciCall) {
  for (uint8_t i = 220; i != 60; ++i) {
    EXPECT_CALL(hcic_interface_, CreateBig(i, iso_matchers::Eq(kDefaultBigParams)))
            .Times(1)
            .RetiresOnSaturation();
    IsoManager::GetInstance()->CreateBig(client_handle_, i, kDefaultBigParams);
  }
}

TEST_F(IsoManagerTest, CreateBigValid) {
  bluetooth::hci::iso_manager::big_create_cmpl_evt evt;
  evt.status = 0x01;
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .WillOnce([&evt](bluetooth::hci::iso_manager::BigSourceEvent /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::big_create_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->CreateBig(client_handle_, 0x01, kDefaultBigParams);
  ASSERT_EQ(evt.status, HCI_SUCCESS);
}

TEST_F(IsoManagerDeathTest, CreateBigInvalidResponsePacket) {
  ON_CALL(hcic_interface_, CreateBig)
          .WillByDefault(
                  [](auto big_handle, bluetooth::hci::iso_manager::big_create_params big_params) {
                    std::vector<uint8_t> buf(18);
                    uint8_t* p = buf.data();
                    UINT8_TO_STREAM(p, 0x00);
                    UINT8_TO_STREAM(p, big_handle);

                    UINT24_TO_STREAM(p, 0x0080de);       // big_sync_delay
                    UINT24_TO_STREAM(p, 0x00cefe);       // transport_latency_big
                    UINT8_TO_STREAM(p, big_params.phy);  // phy
                    UINT8_TO_STREAM(p, 4);               // nse
                    UINT8_TO_STREAM(p, 1);               // bn
                    UINT8_TO_STREAM(p, 0);               // pto
                    UINT8_TO_STREAM(p, 4);               // irc
                    UINT16_TO_STREAM(p, 108);            // max_pdu
                    UINT16_TO_STREAM(p, 6);              // iso_interval
                    UINT8_TO_STREAM(p, 0);               // num BISes

                    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT,
                                                              buf.data(), buf.size());
                  });

  ASSERT_EXIT(IsoManager::GetInstance()->CreateBig(client_handle_, 0x01, kDefaultBigParams),
              KilledBySignal(SIGABRT), "Bis count is 0");
}

TEST_F(IsoManagerDeathTest, CreateBigInvalidResponsePacket2) {
  ON_CALL(hcic_interface_, CreateBig)
          .WillByDefault(
                  [](auto big_handle, bluetooth::hci::iso_manager::big_create_params big_params) {
                    std::vector<uint8_t> buf(18);
                    uint8_t* p = buf.data();
                    UINT8_TO_STREAM(p, 0x00);
                    UINT8_TO_STREAM(p, big_handle);

                    UINT24_TO_STREAM(p, 0x0080de);       // big_sync_delay
                    UINT24_TO_STREAM(p, 0x00cefe);       // transport_latency_big
                    UINT8_TO_STREAM(p, big_params.phy);  // phy
                    UINT8_TO_STREAM(p, 4);               // nse
                    UINT8_TO_STREAM(p, 1);               // bn
                    UINT8_TO_STREAM(p, 0);               // pto
                    UINT8_TO_STREAM(p, 4);               // irc
                    UINT16_TO_STREAM(p, 108);            // max_pdu
                    UINT16_TO_STREAM(p, 6);              // iso_interval
                    UINT8_TO_STREAM(p, big_params.num_bis);

                    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT,
                                                              buf.data(), buf.size());
                  });

  ASSERT_EXIT(IsoManager::GetInstance()->CreateBig(client_handle_, 0x01, kDefaultBigParams),
              KilledBySignal(SIGABRT), "Invalid packet length");
}

TEST_F(IsoManagerTest, CreateBigInvalidStatus) {
  bluetooth::hci::iso_manager::big_create_cmpl_evt evt;
  evt.status = 0x00;
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .WillOnce([&evt](bluetooth::hci::iso_manager::BigSourceEvent /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::big_create_cmpl_evt*>(data);
            return 0;
          });

  ON_CALL(hcic_interface_, CreateBig)
          .WillByDefault(
                  [](auto big_handle, bluetooth::hci::iso_manager::big_create_params big_params) {
                    std::vector<uint8_t> buf(big_params.num_bis * sizeof(uint16_t) + 18);
                    uint8_t* p = buf.data();
                    UINT8_TO_STREAM(p, 0x01);
                    UINT8_TO_STREAM(p, big_handle);

                    UINT24_TO_STREAM(p, 0x0080de);       // big_sync_delay
                    UINT24_TO_STREAM(p, 0x00cefe);       // transport_latency_big
                    UINT8_TO_STREAM(p, big_params.phy);  // phy
                    UINT8_TO_STREAM(p, 4);               // nse
                    UINT8_TO_STREAM(p, 1);               // bn
                    UINT8_TO_STREAM(p, 0);               // pto
                    UINT8_TO_STREAM(p, 4);               // irc
                    UINT16_TO_STREAM(p, 108);            // max_pdu
                    UINT16_TO_STREAM(p, 6);              // iso_interval

                    UINT8_TO_STREAM(p, big_params.num_bis);
                    static uint8_t conn_hdl = 0x01;
                    for (auto i = 0; i < big_params.num_bis; ++i) {
                      UINT16_TO_STREAM(p, conn_hdl++);
                    }

                    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT,
                                                              buf.data(), buf.size());
                  });

  IsoManager::GetInstance()->CreateBig(client_handle_, 0x01, kDefaultBigParams);
  ASSERT_EQ(evt.status, 0x01);
  ASSERT_EQ(evt.big_handle, 0x01);
  ASSERT_EQ(evt.conn_handles.size(), kDefaultBigParams.num_bis);
}

TEST_F(IsoManagerDeathTest, CreateSameBigTwice) {
  bluetooth::hci::iso_manager::big_create_cmpl_evt evt;
  evt.status = 0x01;
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _))
          .WillOnce([&evt](bluetooth::hci::iso_manager::BigSourceEvent /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::big_create_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->CreateBig(client_handle_, 0x01, kDefaultBigParams);
  ASSERT_EQ(evt.status, HCI_SUCCESS);
  ASSERT_EQ(evt.big_handle, 0x01);
  ASSERT_EQ(evt.conn_handles.size(), kDefaultBigParams.num_bis);

  ASSERT_DEATH(IsoManager::GetInstance()->CreateBig(client_handle_, 0x01, kDefaultBigParams),
               "already exists");
}

TEST_F(IsoManagerTest, TerminateBigHciCall) {
  const uint8_t big_handle = 0x22;
  const uint8_t reason = 0x16;  // Terminated by local host

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);
  EXPECT_CALL(hcic_interface_, TerminateBig(big_handle, reason)).Times(1);
  IsoManager::GetInstance()->TerminateBig(big_handle, reason);
}

TEST_F(IsoManagerTest, AddMultipleIncomingCisEventsListeners) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  set_com_android_bluetooth_flags_btm_multi_client_support(true);
  set_com_android_bluetooth_flags_leaudio_peripheral_feature(true);

  // Register an alternative client
  auto second_cig_callbacks = std::make_unique<MockCigCallbacks>();
  bluetooth::hci::iso_manager::IsoManagerCallbacks second_iso_callbacks = {
          .cig_callbacks = second_cig_callbacks.get(),
          .big_callbacks = nullptr,
          .iso_traffic_active_callback = nullptr,
  };
  auto second_client_handle = manager_instance_->RegisterCallbacks(second_iso_callbacks);
  ASSERT_NE(client_handle_, second_client_handle);

  IsoManager::GetInstance()->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                          cis_id);
  IsoManager::GetInstance()->AddIncomingCisEventsListener(second_client_handle, test_address,
                                                          cig_id + 1, cis_id + 1);

  // Fake the BtmDevice for the btm_find_dev_by_handle(acl_conn_handle)
  BtmDevice mock_btm_device = BtmDevice();
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  // Expect a callback only for the registered as a listener
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .WillOnce([&](uint8_t /*evt_code*/, void* event) {
            bluetooth::hci::iso_manager::cis_request_evt* cis_request_evt =
                    static_cast<bluetooth::hci::iso_manager::cis_request_evt*>(event);

            ASSERT_EQ(cis_request_evt->acl_conn_hdl, acl_conn_handle);
            ASSERT_EQ(cis_request_evt->cis_conn_hdl, cis_conn_handle);
            ASSERT_EQ(cis_request_evt->cig_id, cig_id);
            ASSERT_EQ(cis_request_evt->cis_id, cis_id);
          });
  EXPECT_CALL(*second_cig_callbacks,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .Times(0);

  // Inject the CIS request event
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);
  IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());

  manager_instance_->DeregisterCallbacks(second_client_handle);
}

TEST_F(IsoManagerTest, RemoveIncomingCisEventsListener) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  set_com_android_bluetooth_flags_btm_multi_client_support(true);
  set_com_android_bluetooth_flags_leaudio_peripheral_feature(true);

  IsoManager::GetInstance()->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                          cis_id);
  IsoManager::GetInstance()->RemoveIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                             cis_id);

  // Fake the BtmDevice for the btm_find_dev_by_handle(acl_conn_handle)
  BtmDevice mock_btm_device;
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  // Expect no callback when not registered as a listener
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .Times(0);

  // Inject the CIS request event
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);
  IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());
}

TEST_F(IsoManagerTest, AcceptIncomingCisConnectionHciCall) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  set_com_android_bluetooth_flags_btm_multi_client_support(true);
  set_com_android_bluetooth_flags_leaudio_peripheral_feature(true);

  IsoManager::GetInstance()->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                          cis_id);

  // Fake the BtmDevice for the btm_find_dev_by_handle(acl_conn_handle)
  BtmDevice mock_btm_device;
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  // Expect a callback when registered as a listener
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .WillOnce([&](uint8_t /*evt_code*/, void* event) {
            bluetooth::hci::iso_manager::cis_request_evt* cis_request_evt =
                    static_cast<bluetooth::hci::iso_manager::cis_request_evt*>(event);
            ASSERT_EQ(cis_request_evt->acl_conn_hdl, acl_conn_handle);
            ASSERT_EQ(cis_request_evt->cis_conn_hdl, cis_conn_handle);
            ASSERT_EQ(cis_request_evt->cig_id, cig_id);
            ASSERT_EQ(cis_request_evt->cis_id, cis_id);
          });

  // Inject the CIS request event
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);
  IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());

  // Expect a successful call to HCI interface
  EXPECT_CALL(hcic_interface_, AcceptCis(cis_conn_handle)).Times(1);
  IsoManager::GetInstance()->AcceptIncomingCisConnection(cis_conn_handle);
}

TEST_F(IsoManagerTest, RejectIncomingCisConnectionHciCall) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  set_com_android_bluetooth_flags_btm_multi_client_support(true);
  set_com_android_bluetooth_flags_leaudio_peripheral_feature(true);

  IsoManager::GetInstance()->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                          cis_id);

  // Fake the BtmDevice for the btm_find_dev_by_handle(acl_conn_handle)
  BtmDevice mock_btm_device;
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  // Expect a callback when registered as a listener
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .WillOnce([&](uint8_t /*evt_code*/, void* event) {
            bluetooth::hci::iso_manager::cis_request_evt* cis_request_evt =
                    static_cast<bluetooth::hci::iso_manager::cis_request_evt*>(event);
            ASSERT_EQ(cis_request_evt->acl_conn_hdl, acl_conn_handle);
            ASSERT_EQ(cis_request_evt->cis_conn_hdl, cis_conn_handle);
            ASSERT_EQ(cis_request_evt->cig_id, cig_id);
            ASSERT_EQ(cis_request_evt->cis_id, cis_id);
          });

  // Inject the CIS request event
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);
  IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());

  // Expect a successful call to HCI interface
  EXPECT_CALL(hcic_interface_, RejectCis(cis_conn_handle, _, _))
          .WillOnce([&](uint16_t cis_conn_handle, uint8_t reason,
                        base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            std::vector<uint8_t> buf(3);
            uint8_t* p = buf.data();
            UINT8_TO_STREAM(p, reason);
            UINT16_TO_STREAM(p, cis_conn_handle);
            std::move(cb).Run(buf.data(), buf.size());
          });
  // Expect a reject status
  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequestRejectStatus, _))
          .WillOnce([&](uint8_t /*evt_code*/, void* event) {
            bluetooth::hci::iso_manager::reject_cis_request_reject_status*
                    reject_cis_request_reject_status = static_cast<
                            bluetooth::hci::iso_manager::reject_cis_request_reject_status*>(event);
            ASSERT_EQ(reject_cis_request_reject_status->status, HCI_ERR_HOST_REJECT_RESOURCES);
            ASSERT_EQ(reject_cis_request_reject_status->cis_conn_hdl, cis_conn_handle);
          });
  IsoManager::GetInstance()->RejectIncomingCisConnection(cis_conn_handle,
                                                         HCI_ERR_HOST_REJECT_RESOURCES);
}

TEST_F(IsoManagerDeathTest, TerminateSameBigTwice) {
  const uint8_t big_handle = 0x22;
  const uint8_t reason = 0x16;  // Terminated by local host

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kTerminateCmpl, _));

  IsoManager::GetInstance()->TerminateBig(big_handle, reason);
  ASSERT_EXIT(IsoManager::GetInstance()->TerminateBig(big_handle, reason), KilledBySignal(SIGABRT),
              "No such big");
}

TEST_F(IsoManagerDeathTest, TerminateBigNoSuchBig) {
  const uint8_t big_handle = 0x01;
  const uint8_t reason = 0x16;  // Terminated by local host

  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kCreateCmpl, _));
  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);

  ASSERT_EXIT(IsoManager::GetInstance()->TerminateBig(big_handle + 1, reason),
              KilledBySignal(SIGABRT), "No such big");
}

TEST_F(IsoManagerDeathTest, TerminateBigInvalidResponsePacket) {
  ON_CALL(hcic_interface_, TerminateBig).WillByDefault([](auto /* big_handle */, uint8_t reason) {
    std::vector<uint8_t> buf(1);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, reason);

    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT, buf.data(), buf.size());
  });

  const uint8_t big_handle = 0x22;
  const uint8_t reason = 0x16;  // Terminated by local host

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);
  ASSERT_EXIT(IsoManager::GetInstance()->TerminateBig(big_handle, reason), KilledBySignal(SIGABRT),
              "Invalid packet length");
}

TEST_F(IsoManagerDeathTest, TerminateBigInvalidResponsePacket2) {
  const uint8_t big_handle = 0x22;
  const uint8_t reason = 0x16;  // Terminated by local host

  ON_CALL(hcic_interface_, TerminateBig).WillByDefault([](auto /* big_handle */, uint8_t reason) {
    std::vector<uint8_t> buf(3);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, reason);

    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT, buf.data(), buf.size());
  });

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);
  ASSERT_EXIT(IsoManager::GetInstance()->TerminateBig(big_handle, reason), KilledBySignal(SIGABRT),
              "Invalid packet length");
}

TEST_F(IsoManagerTest, TerminateBigInvalidResponseBigId) {
  const uint8_t big_handle = 0x22;
  const uint8_t reason = 0x16;  // Terminated by local host

  ON_CALL(hcic_interface_, TerminateBig).WillByDefault([](auto big_handle, uint8_t reason) {
    std::vector<uint8_t> buf(2);
    uint8_t* p = buf.data();
    UINT8_TO_STREAM(p, reason);
    UINT8_TO_STREAM(p, big_handle + 1);

    IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT, buf.data(), buf.size());
  });

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);
  ASSERT_EXIT(IsoManager::GetInstance()->TerminateBig(big_handle, reason), KilledBySignal(SIGABRT),
              "No such big");
}

TEST_F(IsoManagerTest, TerminateBigValid) {
  const uint8_t big_handle = 0x22;
  const uint8_t reason = 0x16;  // Terminated by local host
  bluetooth::hci::iso_manager::big_terminate_cmpl_evt evt;
  ASSERT_EQ(is_iso_active_, false);

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);
  ASSERT_EQ(is_iso_active_, true);

  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kTerminateCmpl, _))
          .WillOnce([&evt](bluetooth::hci::iso_manager::BigSourceEvent /* type */, void* data) {
            evt = *static_cast<bluetooth::hci::iso_manager::big_terminate_cmpl_evt*>(data);
            return 0;
          });

  IsoManager::GetInstance()->TerminateBig(big_handle, reason);
  ASSERT_EQ(evt.big_handle, big_handle);
  ASSERT_EQ(evt.reason, reason);
  ASSERT_EQ(is_iso_active_, false);
}

TEST_F(IsoManagerTest, BigSyncAndTerminate) {
  set_com_android_bluetooth_flags_btm_broadcast_sink_support(true);

  constexpr uint8_t big_handle = 0x23;
  constexpr uint16_t sync_handle = 0x1234;
  constexpr uint16_t bis_conn_handle = 0x0EFE;

  bluetooth::hci::iso_manager::big_create_sync_params sync_params = {
          .big_handle = big_handle,
          .sync_handle = sync_handle,
          .encryption = 0,
          .broadcast_code = {},
          .mse = 1,
          .big_sync_timeout = 100,
          .bis = {1},
  };

  EXPECT_CALL(hcic_interface_, BigCreateSync(big_handle, sync_handle, sync_params.encryption, _,
                                             sync_params.mse, sync_params.big_sync_timeout, _))
          .Times(1);
  IsoManager::GetInstance()->BigCreateSync(client_handle_, sync_params);

  // Simulate sync established event
  bluetooth::hci::iso_manager::big_sync_est_evt est_evt;
  EXPECT_CALL(*big_callbacks_,
              OnBigSinkEvent(bluetooth::hci::iso_manager::BigSinkEvent::kSyncEst, _))
          .WillOnce([&est_evt](bluetooth::hci::iso_manager::BigSinkEvent /* type */, void* data) {
            est_evt = *static_cast<bluetooth::hci::iso_manager::big_sync_est_evt*>(data);
          });

  std::vector<uint8_t> est_buf(14 + sizeof(uint16_t));
  uint8_t* p = est_buf.data();
  UINT8_TO_STREAM(p, HCI_SUCCESS);
  UINT8_TO_STREAM(p, big_handle);
  UINT24_TO_STREAM(p, 0x123456);  // transport_latency_big
  UINT8_TO_STREAM(p, 1);          // nse
  UINT8_TO_STREAM(p, 2);          // bn
  UINT8_TO_STREAM(p, 3);          // pto
  UINT8_TO_STREAM(p, 4);          // irc
  UINT16_TO_STREAM(p, 251);       // max_pdu
  UINT16_TO_STREAM(p, 10000);     // iso_interval
  UINT8_TO_STREAM(p, 1);          // num_bis
  UINT16_TO_STREAM(p, bis_conn_handle);
  IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_BIG_SYNC_EST_EVT, est_buf.data(),
                                            est_buf.size());

  ASSERT_EQ(est_evt.status, HCI_SUCCESS);
  ASSERT_EQ(est_evt.big_handle, big_handle);
  ASSERT_EQ(est_evt.conn_handles.size(), 1u);
  ASSERT_EQ(est_evt.conn_handles[0], bis_conn_handle);

  // Simulate BIS data
  bluetooth::hci::iso_manager::bis_data_evt data_evt;
  EXPECT_CALL(*big_callbacks_,
              OnBisEvent(bluetooth::hci::iso_manager::kIsoEventBisDataAvailable, _))
          .WillOnce([&data_evt](uint8_t /* type */, void* data) {
            data_evt = *static_cast<bluetooth::hci::iso_manager::bis_data_evt*>(data);
          });

  std::vector<uint8_t> data_buf(18);
  p = data_buf.data();
  UINT16_TO_STREAM(p, BT_EVT_TO_BTU_HCI_ISO);
  UINT16_TO_STREAM(p, 10);  // len
  UINT16_TO_STREAM(p, 0);   // offset
  UINT16_TO_STREAM(p, 0);   // layer_specific
  UINT16_TO_STREAM(p, bis_conn_handle);
  IsoManager::GetInstance()->HandleIsoData(data_buf.data());

  ASSERT_EQ(data_evt.big_handle, big_handle);
  ASSERT_EQ(data_evt.bis_conn_hdl, bis_conn_handle);

  // Terminate sync and simulate terminate complete
  bluetooth::hci::iso_manager::big_terminate_sync_cmpl_evt term_evt;
  EXPECT_CALL(*big_callbacks_,
              OnBigSinkEvent(bluetooth::hci::iso_manager::BigSinkEvent::kTerminateSyncCmpl, _))
          .WillOnce([&term_evt](bluetooth::hci::iso_manager::BigSinkEvent /* type */, void* data) {
            term_evt =
                    *static_cast<bluetooth::hci::iso_manager::big_terminate_sync_cmpl_evt*>(data);
          });

  base::OnceCallback<void(uint8_t*, uint16_t)> captured_cb;
  EXPECT_CALL(hcic_interface_, BigTerminateSync)
          .WillOnce([&captured_cb](uint8_t, base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            captured_cb = std::move(cb);
          });
  IsoManager::GetInstance()->BigTerminateSync(big_handle);

  std::vector<uint8_t> term_buf(2);
  p = term_buf.data();
  UINT8_TO_STREAM(p, HCI_SUCCESS);
  UINT8_TO_STREAM(p, big_handle);
  std::move(captured_cb).Run(term_buf.data(), term_buf.size());

  ASSERT_EQ(term_evt.status, HCI_SUCCESS);
  ASSERT_EQ(term_evt.big_handle, big_handle);
}

TEST_F(IsoManagerTest, SetupIsoDataPathValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;

  // Setup data paths for all CISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionIn;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    EXPECT_CALL(*cig_callbacks_,
                OnSetupIsoDataPath(HCI_SUCCESS, handle, volatile_test_cig_create_cmpl_evt_.cig_id))
            .Times(1)
            .RetiresOnSaturation();

    path_params.data_path_dir = (bluetooth::hci::iso_manager::kIsoDataPathDirectionIn + handle) % 2;

    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }

  // Setup data paths for all BISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut;
  for (auto& handle : volatile_test_big_params_evt_.conn_handles) {
    std::cerr << "setting up BIS data path on conn_hdl: " << int{handle};
    EXPECT_CALL(*big_callbacks_,
                OnSetupIsoDataPath(HCI_SUCCESS, handle, volatile_test_big_params_evt_.big_handle))
            .Times(1)
            .RetiresOnSaturation();

    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }
}

TEST_F(IsoManagerTest, SetupIsoDataPathTwice) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  // Establish CISes
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  // Setup data paths for all CISes twice
  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
    // Should be possible to reconfigure
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }

  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  // Setup data paths for all BISes twice
  for (auto& handle : volatile_test_big_params_evt_.conn_handles) {
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
    // Should be possible to reconfigure
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }
}

TEST_F(IsoManagerTest, SetupIsoDataPathInvalidStatus) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;

  uint8_t setup_datapath_rsp_status = HCI_SUCCESS;
  ON_CALL(hcic_interface_, SetupIsoDataPath)
          .WillByDefault(
                  [&setup_datapath_rsp_status](uint16_t conn_handle, uint8_t, uint8_t, uint8_t,
                                               uint16_t, uint16_t, uint32_t, std::vector<uint8_t>,
                                               base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
                    std::vector<uint8_t> buf(3);
                    uint8_t* p = buf.data();
                    UINT8_TO_STREAM(p, setup_datapath_rsp_status);
                    UINT16_TO_STREAM(p, conn_handle);

                    std::move(cb).Run(buf.data(), buf.size());
                  });

  // Try to setup data paths for all CISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionIn;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    // Mock the response with status != HCI_SUCCESS
    EXPECT_CALL(*cig_callbacks_,
                OnSetupIsoDataPath(0x11, handle, volatile_test_cig_create_cmpl_evt_.cig_id))
            .Times(1)
            .RetiresOnSaturation();
    setup_datapath_rsp_status = 0x11;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    // It should be possible to retry on the same handle after the first
    // failure
    EXPECT_CALL(*cig_callbacks_,
                OnSetupIsoDataPath(HCI_SUCCESS, handle, volatile_test_cig_create_cmpl_evt_.cig_id))
            .Times(1)
            .RetiresOnSaturation();
    setup_datapath_rsp_status = HCI_SUCCESS;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }

  // Try to setup data paths for all BISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut;
  for (auto& handle : volatile_test_big_params_evt_.conn_handles) {
    EXPECT_CALL(*big_callbacks_,
                OnSetupIsoDataPath(0x11, handle, volatile_test_big_params_evt_.big_handle))
            .Times(1)
            .RetiresOnSaturation();
    setup_datapath_rsp_status = 0x11;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    EXPECT_CALL(*big_callbacks_,
                OnSetupIsoDataPath(HCI_SUCCESS, handle, volatile_test_big_params_evt_.big_handle))
            .Times(1)
            .RetiresOnSaturation();
    setup_datapath_rsp_status = HCI_SUCCESS;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }
}

TEST_F(IsoManagerTest, SetupIsoDataPathLateArrivingCallback) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;

  // Catch the callback
  base::OnceCallback<void(uint8_t*, uint16_t)> iso_cb;
  ON_CALL(hcic_interface_, SetupIsoDataPath)
          .WillByDefault([&iso_cb](uint16_t /* conn_handle */, uint8_t /* data_path_dir */,
                                   uint8_t /* data_path_id */, uint8_t /* codec_id_format */,
                                   uint16_t /* codec_id_company */, uint16_t /* codec_id_vendor */,
                                   uint32_t /* controller_delay */,
                                   std::vector<uint8_t> /* codec_conf */,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            iso_cb = std::move(cb);
          });
  // Setup and remove data paths for all CISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
  auto& handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

  // Stop the IsoManager before calling the callback
  IsoManager::GetInstance()->Stop();

  // Call the callback and expect no call
  EXPECT_CALL(*cig_callbacks_, OnSetupIsoDataPath(_, handle, _)).Times(0);
  ASSERT_FALSE(iso_cb.is_null());

  std::vector<uint8_t> buf(3);
  uint8_t* p = buf.data();
  UINT8_TO_STREAM(p, HCI_SUCCESS);
  UINT16_TO_STREAM(p, handle);
  std::move(iso_cb).Run(buf.data(), buf.size());
}

TEST_F(IsoManagerTest, DisconnectCisWhileSettingDataPath) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;

  ON_CALL(hcic_interface_, SetupIsoDataPath)
          .WillByDefault([](uint16_t /*conn_handle*/, uint8_t /* data_path_dir */,
                            uint8_t /* data_path_id */, uint8_t /* codec_id_format */,
                            uint16_t /* codec_id_company */, uint16_t /* codec_id_vendor */,
                            uint32_t /* controller_delay */, std::vector<uint8_t> /* codec_conf */,
                            base::OnceCallback<void(uint8_t*, uint16_t)> /*cb*/) {});

  // Send Setup data paths but wait with response.
  path_params.data_path_dir = bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);
  }

  /* Inject Disconnect Complete. This is not very relevat but to keep error flow lets do this
   * followed by HCI Disconnection Complete event */
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->HandleDisconnect(handle, HCI_ERR_REMOTE_LOW_RESOURCE);
  }

  // Now simulate what host is doing base on this which is Remove data path. Expect no crash

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    EXPECT_CALL(hcic_interface_, RemoveIsoDataPath(handle, _, _)).Times(1);
    IsoManager::GetInstance()->RemoveIsoDataPath(handle, path_params.data_path_dir);
  }
}

TEST_F(IsoManagerTest, RemoveIsoDataPathValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;

  // Setup and remove data paths for all CISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    EXPECT_CALL(*cig_callbacks_,
                OnRemoveIsoDataPath(HCI_SUCCESS, handle, volatile_test_cig_create_cmpl_evt_.cig_id))
            .Times(1)
            .RetiresOnSaturation();
    IsoManager::GetInstance()->RemoveIsoDataPath(handle, path_params.data_path_dir);
  }

  // Setup and remove data paths for all BISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut;
  for (auto& handle : volatile_test_big_params_evt_.conn_handles) {
    std::cerr << "setting up BIS data path on conn_hdl: " << int{handle};
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    EXPECT_CALL(*big_callbacks_,
                OnRemoveIsoDataPath(HCI_SUCCESS, handle, volatile_test_big_params_evt_.big_handle))
            .Times(1)
            .RetiresOnSaturation();
    IsoManager::GetInstance()->RemoveIsoDataPath(handle, path_params.data_path_dir);
  }
}

TEST_F(IsoManagerDeathTest, RemoveIsoDataPathNoSuchPath) {
  // Check on CIS
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  uint16_t conn_handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveIsoDataPath(
                      conn_handle, bluetooth::hci::iso_manager::kIsoDataPathDirectionOut),
              KilledBySignal(SIGABRT), "path not set");

  IsoManager::GetInstance()->EstablishCis({.conn_pairs = {{conn_handle, 1}}});
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveIsoDataPath(
                      conn_handle, bluetooth::hci::iso_manager::kIsoDataPathDirectionOut),
              KilledBySignal(SIGABRT), "path not set");

  // Check on BIS
  conn_handle = volatile_test_big_params_evt_.conn_handles[0];
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveIsoDataPath(
                      conn_handle, bluetooth::hci::iso_manager::kIsoDataPathDirectionOut),
              KilledBySignal(SIGABRT), "path not set");
}

TEST_F(IsoManagerDeathTest, RemoveIsoDataPathTwice) {
  // Check on CIS
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  uint16_t conn_handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({.conn_pairs = {{conn_handle, 1}}});
  IsoManager::GetInstance()->SetupIsoDataPath(conn_handle, kDefaultIsoDataPathParams);
  IsoManager::GetInstance()->RemoveIsoDataPath(conn_handle,
                                               kDefaultIsoDataPathParams.data_path_dir);
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveIsoDataPath(
                      conn_handle, bluetooth::hci::iso_manager::kIsoDataPathDirectionOut),
              KilledBySignal(SIGABRT), "path not set");

  // Check on BIS
  conn_handle = volatile_test_big_params_evt_.conn_handles[0];
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->SetupIsoDataPath(conn_handle, kDefaultIsoDataPathParams);
  IsoManager::GetInstance()->RemoveIsoDataPath(conn_handle,
                                               kDefaultIsoDataPathParams.data_path_dir);
  ASSERT_EXIT(IsoManager::GetInstance()->RemoveIsoDataPath(
                      conn_handle, bluetooth::hci::iso_manager::kIsoDataPathDirectionOut),
              KilledBySignal(SIGABRT), "path not set");
}

// Check if HCI status other than HCI_SUCCESS is being propagated to the caller
TEST_F(IsoManagerTest, RemoveIsoDataPathInvalidStatus) {
  // Mock invalid status response
  uint8_t remove_datapath_rsp_status = 0x12;
  ON_CALL(hcic_interface_, RemoveIsoDataPath)
          .WillByDefault(
                  [&remove_datapath_rsp_status](uint16_t conn_handle, uint8_t /* data_path_dir */,
                                                base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
                    std::vector<uint8_t> buf(3);
                    uint8_t* p = buf.data();
                    UINT8_TO_STREAM(p, remove_datapath_rsp_status);
                    UINT16_TO_STREAM(p, conn_handle);

                    std::move(cb).Run(buf.data(), buf.size());
                  });

  // Check on CIS
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  uint16_t conn_handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({.conn_pairs = {{conn_handle, 1}}});
  IsoManager::GetInstance()->SetupIsoDataPath(conn_handle, kDefaultIsoDataPathParams);

  EXPECT_CALL(*cig_callbacks_, OnRemoveIsoDataPath(remove_datapath_rsp_status, conn_handle,
                                                   volatile_test_cig_create_cmpl_evt_.cig_id))
          .Times(1);
  IsoManager::GetInstance()->RemoveIsoDataPath(conn_handle,
                                               kDefaultIsoDataPathParams.data_path_dir);

  // Check on BIS
  conn_handle = volatile_test_big_params_evt_.conn_handles[0];
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->SetupIsoDataPath(conn_handle, kDefaultIsoDataPathParams);

  EXPECT_CALL(*big_callbacks_, OnRemoveIsoDataPath(remove_datapath_rsp_status, conn_handle,
                                                   volatile_test_big_params_evt_.big_handle))
          .Times(1);
  IsoManager::GetInstance()->RemoveIsoDataPath(conn_handle,
                                               kDefaultIsoDataPathParams.data_path_dir);
}

TEST_F(IsoManagerTest, RemoveIsoDataPathLateArrivingCallback) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  // Establish all CISes before setting up their data paths
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;

  // Setup and remove data paths for all CISes
  path_params.data_path_dir = bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
  auto& handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

  // Catch the callback
  base::OnceCallback<void(uint8_t*, uint16_t)> iso_cb;
  ON_CALL(hcic_interface_, RemoveIsoDataPath)
          .WillByDefault([&iso_cb](uint16_t /* conn_handle */, uint8_t /* data_path_dir */,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            iso_cb = std::move(cb);
          });
  IsoManager::GetInstance()->RemoveIsoDataPath(handle, path_params.data_path_dir);

  // Stop the IsoManager before calling the callback
  IsoManager::GetInstance()->Stop();

  // Call the callback and expect no call
  EXPECT_CALL(*cig_callbacks_,
              OnRemoveIsoDataPath(HCI_SUCCESS, handle, volatile_test_cig_create_cmpl_evt_.cig_id))
          .Times(0);
  ASSERT_FALSE(iso_cb.is_null());

  std::vector<uint8_t> buf(3);
  uint8_t* p = buf.data();
  UINT8_TO_STREAM(p, HCI_SUCCESS);
  UINT16_TO_STREAM(p, handle);
  std::move(iso_cb).Run(buf.data(), buf.size());
}

TEST_F(IsoManagerTest, SendIsoDataWithNoCigConnected) {
  std::vector<uint8_t> data_vec(108, 0);
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());
  EXPECT_CALL(iso_interface_, HciSend).Times(0);
}

TEST_F(IsoManagerTest, SendIsoDataCigValid) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;
    path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    for (uint8_t num_pkts = 2; num_pkts != 0; num_pkts--) {
      constexpr uint8_t data_len = 108;

      EXPECT_CALL(iso_interface_, HciSend)
              .WillOnce([handle, data_len](BT_HDR* p_msg) {
                uint8_t* p = p_msg->data;
                uint16_t msg_handle;
                uint16_t iso_load_len;

                ASSERT_NE(p_msg, nullptr);
                ASSERT_EQ(p_msg->len,
                          data_len + ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? 12 : 8));

                // Verify packet internals
                STREAM_TO_UINT16(msg_handle, p);
                ASSERT_EQ(msg_handle, handle);

                STREAM_TO_UINT16(iso_load_len, p);
                ASSERT_EQ(iso_load_len,
                          data_len + ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? 8 : 4));

                if (p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) {
                  STREAM_SKIP_UINT16(p);  // skip ts LSB halfword
                  STREAM_SKIP_UINT16(p);  // skip ts MSB halfword
                }
                STREAM_SKIP_UINT16(p);  // skip seq_nb

                uint16_t msg_data_len;
                STREAM_TO_UINT16(msg_data_len, p);
                ASSERT_EQ(msg_data_len, data_len);
              })
              .RetiresOnSaturation();

      std::vector<uint8_t> data_vec(data_len, 0);
      IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());
    }
  }
}

TEST_F(IsoManagerTest, SendReceiveIsoDataSequenceNumberCheck) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;
    path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    constexpr uint8_t data_len = 108;
    uint16_t seq_num = 0xFFFF;

    EXPECT_CALL(iso_interface_, HciSend)
            .WillRepeatedly([handle, data_len, &seq_num](BT_HDR* p_msg) {
              uint8_t* p = p_msg->data;
              uint16_t msg_handle;
              uint16_t iso_load_len;

              ASSERT_NE(p_msg, nullptr);
              ASSERT_EQ(p_msg->len,
                        data_len + ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? 12 : 8));

              // Verify packet internals
              STREAM_TO_UINT16(msg_handle, p);
              ASSERT_EQ(msg_handle, handle);

              STREAM_TO_UINT16(iso_load_len, p);
              ASSERT_EQ(iso_load_len,
                        data_len + ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? 8 : 4));

              if (p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) {
                STREAM_SKIP_UINT16(p);  // skip ts LSB halfword
                STREAM_SKIP_UINT16(p);  // skip ts MSB halfword
              }
              // store the seq_nb
              STREAM_TO_UINT16(seq_num, p);

              uint16_t msg_data_len;
              STREAM_TO_UINT16(msg_data_len, p);
              ASSERT_EQ(msg_data_len, data_len);
            })
            .RetiresOnSaturation();

    // Send Iso data and verify the sequence number
    std::vector<uint8_t> data_vec(data_len, 0);
    IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());
    IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());
    ASSERT_NE(0xFFFF, seq_num);

    // Check the receiving iso packet
    // EXPECT_CALL(*cig_callbacks_, OnCisEvent).Times(1);
    EXPECT_CALL(*cig_callbacks_,
                OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDataAvailable, _))
            .WillOnce([](uint8_t /*evt_code*/, void* event) {
              bluetooth::hci::iso_manager::cis_data_evt* cis_data_evt =
                      static_cast<bluetooth::hci::iso_manager::cis_data_evt*>(event);
              // Make sure no event lost is reported due to seq_nb being shared between two
              // directions
              ASSERT_EQ(cis_data_evt->evt_lost, 0);
            });

    std::vector<uint8_t> dummy_msg(18);
    uint8_t* p = dummy_msg.data();
    UINT16_TO_STREAM(p, BT_EVT_TO_BTU_HCI_ISO);
    UINT16_TO_STREAM(p, 10);  // .len
    UINT16_TO_STREAM(p, 0);   // .offset
    UINT16_TO_STREAM(p, 0);   // .layer_specific
    UINT16_TO_STREAM(p, handle);
    IsoManager::GetInstance()->HandleIsoData(dummy_msg.data());
  }
}

TEST_F(IsoManagerTest, SendIsoDataBigValid) {
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  for (auto& handle : volatile_test_big_params_evt_.conn_handles) {
    IsoManager::GetInstance()->SetupIsoDataPath(handle, kDefaultIsoDataPathParams);
    for (uint8_t num_pkts = 2; num_pkts != 0; num_pkts--) {
      constexpr uint8_t data_len = 108;

      EXPECT_CALL(iso_interface_, HciSend)
              .WillOnce([handle, data_len](BT_HDR* p_msg) {
                uint8_t* p = p_msg->data;
                uint16_t msg_handle;
                uint16_t iso_load_len;

                ASSERT_NE(p_msg, nullptr);
                ASSERT_EQ(p_msg->len,
                          data_len + ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? 12 : 8));

                // Verify packet internals
                STREAM_TO_UINT16(msg_handle, p);
                ASSERT_EQ(msg_handle, handle);

                STREAM_TO_UINT16(iso_load_len, p);
                ASSERT_EQ(iso_load_len,
                          data_len + ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? 8 : 4));

                uint16_t msg_data_len;
                uint16_t msg_dummy;
                if (p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) {
                  STREAM_TO_UINT16(msg_dummy, p);  // skip ts LSB halfword
                  STREAM_TO_UINT16(msg_dummy, p);  // skip ts MSB halfword
                }
                STREAM_TO_UINT16(msg_dummy, p);  // skip seq_nb

                STREAM_TO_UINT16(msg_data_len, p);
                ASSERT_EQ(msg_data_len, data_len);
              })
              .RetiresOnSaturation();

      std::vector<uint8_t> data_vec(data_len, 0);
      IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());
    }
  }
}

TEST_F(IsoManagerTest, SendIsoDataNoCredits) {
  uint8_t num_buffers = bluetooth::hci::testing::mock_controller_->GetControllerIsoBufferSize()
                                .total_num_le_packets_;
  std::vector<uint8_t> data_vec(108, 0);

  // Check on CIG
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  IsoManager::GetInstance()->SetupIsoDataPath(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                              kDefaultIsoDataPathParams);

  /* Try sending twice as much data as we can ignoring the credit limits and
   * expect the redundant packets to be ignored and not propagated down to the
   * HCI.
   */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (2 * num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }

  // Return all credits for this one handle
  IsoManager::GetInstance()->HandleNumComplDataPkts(
          volatile_test_cig_create_cmpl_evt_.conn_handles[0], num_buffers);

  // Check on BIG
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->SetupIsoDataPath(volatile_test_big_params_evt_.conn_handles[0],
                                              kDefaultIsoDataPathParams);

  /* Try sending twice as much data as we can ignoring the credit limits and
   * expect the redundant packets to be ignored and not propagated down to the
   * HCI.
   */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers);
  for (uint8_t i = 0; i < (2 * num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_big_params_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }
}

TEST_F(IsoManagerTest, SendIsoDataCreditsReturned) {
  uint8_t num_buffers = bluetooth::hci::testing::mock_controller_->GetControllerIsoBufferSize()
                                .total_num_le_packets_;
  std::vector<uint8_t> data_vec(108, 0);

  // Check on CIG
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  IsoManager::GetInstance()->SetupIsoDataPath(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                              kDefaultIsoDataPathParams);

  /* Try sending twice as much data as we can, ignoring the credits limit and
   * expect the redundant packets to be ignored and not propagated down to the
   * HCI.
   */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (2 * num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }

  // Return all credits for this one handle
  IsoManager::GetInstance()->HandleNumComplDataPkts(
          volatile_test_cig_create_cmpl_evt_.conn_handles[0], num_buffers);

  // Expect some more events go down the HCI
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (2 * num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }

  // Return all credits for this one handle
  IsoManager::GetInstance()->HandleNumComplDataPkts(
          volatile_test_cig_create_cmpl_evt_.conn_handles[0], num_buffers);

  // Check on BIG
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->SetupIsoDataPath(volatile_test_big_params_evt_.conn_handles[0],
                                              kDefaultIsoDataPathParams);

  /* Try sending twice as much data as we can, ignoring the credits limit and
   * expect the redundant packets to be ignored and not propagated down to the
   * HCI.
   */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (2 * num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_big_params_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }

  // Return all credits for this one handle
  IsoManager::GetInstance()->HandleNumComplDataPkts(volatile_test_big_params_evt_.conn_handles[0],
                                                    num_buffers);

  // Expect some more events go down the HCI
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (2 * num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_big_params_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }
}

TEST_F(IsoManagerTest, SendIsoDataCreditsReturnedByDisconnection) {
  uint8_t num_buffers = bluetooth::hci::testing::mock_controller_->GetControllerIsoBufferSize()
                                .total_num_le_packets_;
  std::vector<uint8_t> data_vec(108, 0);

  // Check on CIG
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->SetupIsoDataPath(handle, kDefaultIsoDataPathParams);
  }

  /* Sending lot of ISO data to first ISO and getting all the credits */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < num_buffers; i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }

  /* Return all credits by disconnecting CIS */
  IsoManager::GetInstance()->HandleDisconnect(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                              16);

  /* Try to send ISO data on the second ISO. Expect credits being available.*/
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < num_buffers; i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_cig_create_cmpl_evt_.conn_handles[1],
                                           data_vec.data(), data_vec.size());
  }
}

TEST_F(IsoManagerTest, SendIsoDataCreditsReturnedByBigTermination) {
  uint8_t num_buffers = bluetooth::hci::testing::mock_controller_->GetControllerIsoBufferSize()
                                .total_num_le_packets_;
  std::vector<uint8_t> data_vec(108, 0);

  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->SetupIsoDataPath(volatile_test_big_params_evt_.conn_handles[0],
                                              kDefaultIsoDataPathParams);

  /* Use all the credits and symulater Controller is not sending number of completed packets */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_big_params_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }

  /* Terminate BIG and credits should be returned  */
  IsoManager::GetInstance()->TerminateBig(volatile_test_big_params_evt_.big_handle, 0x16);

  /* Create new BIG and expect credits are available */
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->SetupIsoDataPath(volatile_test_big_params_evt_.conn_handles[0],
                                              kDefaultIsoDataPathParams);

  /* Expect we can send ISO data as credits were returned after BIG Termination */
  EXPECT_CALL(iso_interface_, HciSend).Times(num_buffers).RetiresOnSaturation();
  for (uint8_t i = 0; i < (num_buffers); i++) {
    IsoManager::GetInstance()->SendIsoData(volatile_test_big_params_evt_.conn_handles[0],
                                           data_vec.data(), data_vec.size());
  }
}

TEST_F(IsoManagerDeathTest, SendIsoDataWithNoDataPath) {
  std::vector<uint8_t> data_vec(108, 0);

  // Check on CIG
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& conn_handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({conn_handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  EXPECT_CALL(iso_interface_, HciSend).Times(0);
  IsoManager::GetInstance()->SendIsoData(volatile_test_cig_create_cmpl_evt_.conn_handles[0],
                                         data_vec.data(), data_vec.size());

  // Check on BIG
  IsoManager::GetInstance()->CreateBig(client_handle_, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);

  EXPECT_CALL(iso_interface_, HciSend).Times(0);
  IsoManager::GetInstance()->SendIsoData(volatile_test_big_params_evt_.conn_handles[0],
                                         data_vec.data(), data_vec.size());
}

TEST_F(IsoManagerDeathTest, SendIsoDataWithNoCigBigHandle) {
  std::vector<uint8_t> data_vec(108, 0);
  ASSERT_EXIT(IsoManager::GetInstance()->SendIsoData(134, data_vec.data(), data_vec.size()),
              KilledBySignal(SIGABRT), "No such iso");
}

TEST_F(IsoManagerTest, HandleDisconnectNoSuchHandle) {
  // Don't expect any callbacks when connection handle is not for ISO.
  EXPECT_CALL(*cig_callbacks_, OnCigEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCisEvent).Times(0);
  EXPECT_CALL(*big_callbacks_, OnBigSourceEvent).Times(0);

  IsoManager::GetInstance()->HandleDisconnect(123, 16);
}

TEST_F(IsoManagerTest, HandleDisconnectValidCig) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  EXPECT_CALL(*big_callbacks_, OnBigSourceEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCigEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCisEvent).Times(0);

  // Expect disconnect event exactly once
  EXPECT_CALL(*cig_callbacks_, OnCisEvent).WillOnce([this, handle](uint8_t event_code, void* data) {
    ASSERT_EQ(event_code, bluetooth::hci::iso_manager::kIsoEventCisDisconnected);
    auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);
    ASSERT_EQ(event->reason, 16);
    ASSERT_EQ(event->cig_id, volatile_test_cig_create_cmpl_evt_.cig_id);
    ASSERT_EQ(event->cis_conn_hdl, handle);
  });

  IsoManager::GetInstance()->HandleDisconnect(handle, 16);
}

TEST_F(IsoManagerTest, HandleDisconnectDisconnectedCig) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  EXPECT_CALL(*big_callbacks_, OnBigSourceEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCigEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCisEvent).Times(0);

  // Expect disconnect event exactly once
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(1)
          .RetiresOnSaturation();
  IsoManager::GetInstance()->HandleDisconnect(handle, 16);

  // This one was once connected - expect no events
  IsoManager::GetInstance()->HandleDisconnect(handle, 16);

  // This one was never connected - expect no events
  handle = volatile_test_cig_create_cmpl_evt_.conn_handles[1];
  IsoManager::GetInstance()->HandleDisconnect(handle, 16);
}

TEST_F(IsoManagerTest, HandleDisconnectLateArrivingCallback) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  EXPECT_CALL(*big_callbacks_, OnBigSourceEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCigEvent).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCisEvent).Times(0);

  // Expect disconnect event exactly once
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(0);

  // Expect no callback on late arriving event
  IsoManager::GetInstance()->Stop();
  IsoManager::GetInstance()->HandleDisconnect(handle, 16);
}

TEST_F(IsoManagerTest, HandleIsoData) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDataAvailable, _))
          .Times(1);

  std::vector<uint8_t> dummy_msg(18);
  uint8_t* p = dummy_msg.data();
  UINT16_TO_STREAM(p, BT_EVT_TO_BTU_HCI_ISO);
  UINT16_TO_STREAM(p, 10);  // .len
  UINT16_TO_STREAM(p, 0);   // .offset
  UINT16_TO_STREAM(p, 0);   // .layer_specific
  UINT16_TO_STREAM(p, handle);
  IsoManager::GetInstance()->HandleIsoData(dummy_msg.data());
}

/* This test case simulates HCI thread scheduling events on the main thread,
 * without knowing the we are already shutting down the stack and Iso Manager
 * is already stopped.
 */
TEST_F(IsoManagerDeathTestNoCleanup, HandleLateArivingEventHandleIsoData) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  // Stop iso manager before trying to call the HCI callbacks
  IsoManager::GetInstance()->Stop();

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDataAvailable, _))
          .Times(0);

  // Expect no assert on this call - should be gracefully ignored
  std::vector<uint8_t> dummy_msg(18);
  uint8_t* p = dummy_msg.data();
  UINT16_TO_STREAM(p, BT_EVT_TO_BTU_HCI_ISO);
  UINT16_TO_STREAM(p, 10);  // .len
  UINT16_TO_STREAM(p, 0);   // .offset
  UINT16_TO_STREAM(p, 0);   // .layer_specific
  UINT16_TO_STREAM(p, handle);
  IsoManager::GetInstance()->HandleIsoData(dummy_msg.data());
}

/* This test case simulates HCI thread scheduling events on the main thread,
 * without knowing the we are already shutting down the stack and Iso Manager
 * is already stopped.
 */
TEST_F(IsoManagerDeathTestNoCleanup, HandleLateArivingEventHandleDisconnect) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  // Stop iso manager before trying to call the HCI callbacks
  IsoManager::GetInstance()->Stop();

  // Expect no event when callback is being called on a stopped iso manager
  EXPECT_CALL(*cig_callbacks_, OnCisEvent).Times(0);
  // Expect no assert on this call - should be gracefully ignored
  IsoManager::GetInstance()->HandleDisconnect(handle, 16);
}

/* This test case simulates HCI thread scheduling events on the main thread,
 * without knowing the we are already shutting down the stack and Iso Manager
 * is already stopped.
 */
TEST_F(IsoManagerDeathTestNoCleanup, HandleLateArivingEventHandleNumComplDataPkts) {
  uint8_t num_buffers = bluetooth::hci::testing::mock_controller_->GetControllerIsoBufferSize()
                                .total_num_le_packets_;

  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  // Stop iso manager before trying to call the HCI callbacks
  IsoManager::GetInstance()->Stop();

  // Expect no assert on this call - should be gracefully ignored
  IsoManager::GetInstance()->HandleNumComplDataPkts(handle, num_buffers);
}

/* This test case simulates HCI thread scheduling events on the main thread,
 * without knowing the we are already shutting down the stack and Iso Manager
 * is already stopped.
 */
TEST_F(IsoManagerDeathTestNoCleanup, HandleLateArivingEventHandleHciEvent) {
  const uint8_t big_handle = 0x22;

  IsoManager::GetInstance()->CreateBig(client_handle_, big_handle, kDefaultBigParams);

  // Stop iso manager before trying to call the HCI callbacks
  IsoManager::GetInstance()->Stop();
  EXPECT_CALL(*big_callbacks_,
              OnBigSourceEvent(bluetooth::hci::iso_manager::BigSourceEvent::kTerminateCmpl, _))
          .Times(0);

  // Expect no assert on this call - should be gracefully ignored
  std::vector<uint8_t> buf(2);
  uint8_t* p = buf.data();
  UINT8_TO_STREAM(p, big_handle);
  UINT8_TO_STREAM(p, 16);  // Terminated by local host
  IsoManager::GetInstance()->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT, buf.data(), buf.size());
}

/* This test makes sure we do not crash when calling into a non-started Iso Manager
 */
TEST_F(IsoManagerDeathTestNoCleanup, HandleApiCallsWhenStopped) {
  IsoManager::GetInstance()->Stop();
  bluetooth::hci::iso_manager::IsoManagerCallbacks callbacks = {
          .cig_callbacks = cig_callbacks_.get(),
          .big_callbacks = big_callbacks_.get(),
          .iso_traffic_active_callback = iso_traffic_active_callback_,
  };
  auto client_handle = IsoManager::GetInstance()->RegisterCallbacks(callbacks);

  IsoManager::GetInstance()->CreateCig(client_handle, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);
  IsoManager::GetInstance()->ReconfigureCig(volatile_test_cig_create_cmpl_evt_.cig_id,
                                            kDefaultCigParams);

  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  bluetooth::hci::iso_manager::iso_data_path_params path_params = kDefaultIsoDataPathParams;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    path_params.data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionIn;
    IsoManager::GetInstance()->SetupIsoDataPath(handle, path_params);

    path_params.data_path_dir = bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
    IsoManager::GetInstance()->RemoveIsoDataPath(handle, path_params.data_path_dir);
  }

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->ReadIsoLinkQuality(handle);

  std::vector<uint8_t> data_vec(108, 0);
  IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());
  IsoManager::GetInstance()->SendIsoData(handle, data_vec.data(), data_vec.size());

  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    IsoManager::GetInstance()->IsoManager::GetInstance()->DisconnectCis(handle, 0x16);
  }

  IsoManager::GetInstance()->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id);
  (void)IsoManager::GetInstance()->GetNumberOfActiveIso();

  IsoManager::GetInstance()->CreateBig(client_handle, volatile_test_big_params_evt_.big_handle,
                                       kDefaultBigParams);
  IsoManager::GetInstance()->TerminateBig(volatile_test_big_params_evt_.big_handle, 0x16);
}

TEST_F(IsoManagerTest, HandleIsoDataSameSeqNb) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->EstablishCis({{{handle, 1}}});

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDataAvailable, _))
          .Times(2);

  std::vector<uint8_t> dummy_msg(18);
  uint8_t* p = dummy_msg.data();
  UINT16_TO_STREAM(p, BT_EVT_TO_BTU_HCI_ISO);
  UINT16_TO_STREAM(p, 10);  // .len
  UINT16_TO_STREAM(p, 0);   // .offset
  UINT16_TO_STREAM(p, 0);   // .layer_specific
  UINT16_TO_STREAM(p, handle);

  IsoManager::GetInstance()->HandleIsoData(dummy_msg.data());
  IsoManager::GetInstance()->HandleIsoData(dummy_msg.data());
}

TEST_F(IsoManagerTest, ReadIsoLinkQualityLateArrivingCallback) {
  IsoManager::GetInstance()->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                                       kDefaultCigParams);

  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _))
          .Times(kDefaultCigParams.cis_cfgs.size())
          .WillRepeatedly([this](uint8_t /* type */, void* data) {
            bluetooth::hci::iso_manager::cis_establish_cmpl_evt* evt =
                    static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(data);

            ASSERT_EQ(evt->status, HCI_SUCCESS);
            ASSERT_TRUE(std::find(volatile_test_cig_create_cmpl_evt_.conn_handles.begin(),
                                  volatile_test_cig_create_cmpl_evt_.conn_handles.end(),
                                  evt->cis_conn_hdl) !=
                        volatile_test_cig_create_cmpl_evt_.conn_handles.end());
          });

  // Establish all CISes
  bluetooth::hci::iso_manager::cis_establish_params params;
  for (auto& handle : volatile_test_cig_create_cmpl_evt_.conn_handles) {
    params.conn_pairs.push_back({handle, 1});
  }
  IsoManager::GetInstance()->EstablishCis(params);

  // Catch the callback
  base::OnceCallback<void(uint8_t*, uint16_t)> iso_cb;
  ON_CALL(hcic_interface_, ReadIsoLinkQuality)
          .WillByDefault([&iso_cb](uint16_t /* conn_handle */,
                                   base::OnceCallback<void(uint8_t*, uint16_t)> cb) {
            iso_cb = std::move(cb);
          });
  auto handle = volatile_test_cig_create_cmpl_evt_.conn_handles[0];
  IsoManager::GetInstance()->ReadIsoLinkQuality(handle);

  // Stop the IsoManager before calling the callback
  IsoManager::GetInstance()->Stop();

  // Call the callback and expect no call
  EXPECT_CALL(*cig_callbacks_, OnIsoLinkQualityRead(handle, _, _, _, _, _, _, _, _)).Times(0);
  ASSERT_FALSE(iso_cb.is_null());

  std::vector<uint8_t> buf(31);
  uint8_t* p = buf.data();
  UINT8_TO_STREAM(p, HCI_SUCCESS);
  UINT16_TO_STREAM(p, handle);
  UINT32_TO_STREAM(p, 0);
  UINT32_TO_STREAM(p, 0);
  UINT32_TO_STREAM(p, 0);
  UINT32_TO_STREAM(p, 0);
  UINT32_TO_STREAM(p, 0);
  UINT32_TO_STREAM(p, 0);
  UINT32_TO_STREAM(p, 0);
  std::move(iso_cb).Run(buf.data(), buf.size());
}

/**
 * Test case to verify that SetBigChannelMapClassification forwards the call
 * to the underlying implementation when the IsoManager is running.
 *
 * This test ensures that the IsoManager correctly delegates the operation
 * to its implementation layer, which is the expected behavior under normal
 * operating conditions.
 */
TEST_F(IsoManagerTest, SetBigChannelMapClassificationHciCall) {
  // --- Test Data ---
  // Define sample parameters for the function call.
  uint8_t action = 1;
  uint8_t big_handle = 2;
  std::vector<uint16_t> handles = {0x0041, 0x0042};

  // --- Expectations ---
  // Expect that the LeSetBigChannelMapClassification HCI command is called once
  // with the parameters we defined above. This verifies that the IsoManager
  // correctly processes the request and sends the appropriate command to the controller.
  EXPECT_CALL(hcic_interface_,
              SetBigChannelMapClassificationByConnHandles(action, big_handle, handles))
          .Times(1);

  // --- Execution ---
  // Call the function on the IsoManager that we are testing.
  IsoManager::GetInstance()->SetBigChannelMapClassificationByConnHandles(action, big_handle,
                                                                         handles);
}

TEST_F(IsoManagerTest, NotifyIsoTrafficActiveOnRemoveCigCreateBigDeadlock) {
  bool reentrancy_success = false;

  auto reentrant_callback = [&](bool is_active) {
    if (!is_active) {
      uint8_t new_big_handle = 0xFE;
      manager_instance_->CreateBig(client_handle_, new_big_handle, kDefaultBigParams);

      reentrancy_success = true;
    }
  };

  manager_instance_->DeregisterCallbacks(client_handle_);

  bluetooth::hci::iso_manager::IsoManagerCallbacks callbacks = {
          .cig_callbacks = cig_callbacks_.get(),
          .big_callbacks = big_callbacks_.get(),
          .iso_traffic_active_callback = reentrant_callback,
  };
  client_handle_ = manager_instance_->RegisterCallbacks(callbacks);

  // Create CIG triggers is_active = true
  manager_instance_->CreateCig(client_handle_, volatile_test_cig_create_cmpl_evt_.cig_id,
                               kDefaultCigParams);

  // Remove CIG triggers is_active = false and callback does module reentrance with Create BIG
  manager_instance_->RemoveCig(volatile_test_cig_create_cmpl_evt_.cig_id);

  ASSERT_TRUE(reentrancy_success)
          << "Deadlock detected or callback not fired during RemoveCig -> CreateBig transition!";
}

TEST_F(IsoManagerTest, StrayCisEstablishedEvt) {
  uint16_t cis_conn_handle = 0x2345;
  uint8_t status = HCI_SUCCESS;

  EXPECT_CALL(hcic_interface_, Disconnect(cis_conn_handle, HCI_ERR_CANCELLED_BY_LOCAL_HOST))
          .Times(1);

  std::vector<uint8_t> buf(28);
  uint8_t* p = buf.data();
  UINT8_TO_STREAM(p, status);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT24_TO_STREAM(p, 0);  // CIG_Sync_Delay
  UINT24_TO_STREAM(p, 0);  // CIS_Sync_Delay
  UINT24_TO_STREAM(p, 0);  // Transport_Latency_M_To_S
  UINT24_TO_STREAM(p, 0);  // Transport_Latency_S_To_M
  UINT8_TO_STREAM(p, 0);   // PHY_M_To_S
  UINT8_TO_STREAM(p, 0);   // PHY_S_To_M
  UINT8_TO_STREAM(p, 0);   // NSE
  UINT8_TO_STREAM(p, 0);   // BN_M_To_S
  UINT8_TO_STREAM(p, 0);   // BN_S_To_M
  UINT8_TO_STREAM(p, 0);   // FT_M_To_S
  UINT8_TO_STREAM(p, 0);   // FT_S_To_M
  UINT16_TO_STREAM(p, 0);  // Max_PDU_M_To_S
  UINT16_TO_STREAM(p, 0);  // Max_PDU_S_To_M
  UINT16_TO_STREAM(p, 0);  // ISO_Interval

  // No CIG/CIS events should be generated for a stray connection
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(_, _)).Times(0);
  EXPECT_CALL(*cig_callbacks_, OnCigEvent(_, _)).Times(0);

  manager_instance_->HandleHciEvent(HCI_BLE_CIS_EST_EVT, buf.data(), buf.size());
}

class IncomingCisTest : public IsoManagerTest {
protected:
  void SetUp() override {
    IsoManagerTest::SetUp();
    com_android_bluetooth_flags_reset_flags();
    set_com_android_bluetooth_flags_leaudio_peripheral_feature(true);

    ::testing::FLAGS_gtest_death_test_style = "threadsafe";
  }
};

TEST_F(IncomingCisTest, AddIncomingCisEventsListenerHappy) {
  RawAddress test_address;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));
}

TEST_F(IncomingCisTest, AddIncomingCisEventsListenerRejectOtherClient) {
  RawAddress test_address;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  auto other_client_callbacks = std::make_unique<MockCigCallbacks>();
  auto other_client_handle = manager_instance_->RegisterCallbacks({
          .cig_callbacks = other_client_callbacks.get(),
  });

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));
  ASSERT_FALSE(manager_instance_->AddIncomingCisEventsListener(other_client_handle, test_address,
                                                               cig_id, cis_id));
}

TEST_F(IncomingCisTest, AddIncomingCisEventsListenerRejectOverrideValidHandle) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  BtmDevice mock_btm_device;
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));

  // Simulate CIS request to associate a handle
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .Times(1);
  manager_instance_->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());

  // Try to re-register, should fail because handle is now valid
  ASSERT_FALSE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                               cis_id));
}

TEST_F(IncomingCisTest, AddIncomingCisEventsListenerReregister) {
  RawAddress test_address;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));
  // Re-registering should be idempotent and succeed
  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));
}

TEST_F(IncomingCisTest, AddIncomingCisEventsListenerSameCigDifferentCis) {
  RawAddress test_address;
  uint8_t cig_id = 1;
  uint8_t cis_id_1 = 2;
  uint8_t cis_id_2 = 3;

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id_1));
  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id_2));
}

TEST_F(IncomingCisTest, AddIncomingCisEventsListenerSameCisDifferentCig) {
  RawAddress test_address;
  uint8_t cig_id_1 = 1;
  uint8_t cig_id_2 = 2;
  uint8_t cis_id = 3;

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address,
                                                              cig_id_1, cis_id));
  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address,
                                                              cig_id_2, cis_id));
}

TEST_F(IncomingCisTest, RemoveIncomingCisEventsListenerRejectWhenConnected) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  BtmDevice mock_btm_device;
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));

  // Simulate CIS request to associate a handle
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .Times(1);
  manager_instance_->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());

  // Try to remove the listener, should fail because it is "connected"
  ASSERT_DEATH(manager_instance_->RemoveIncomingCisEventsListener(client_handle_, test_address,
                                                                  cig_id, cis_id),
               ".*");
}

TEST_F(IncomingCisTest, RemoveListenerAfterRemoteDisconnect) {
  RawAddress test_address;
  uint16_t acl_conn_handle = 1;
  uint16_t cis_conn_handle = 2;
  uint8_t cig_id = 1;
  uint8_t cis_id = 2;

  BtmDevice mock_btm_device;
  mock_btm_device.ble.pseudo_addr = test_address;
  AclHandleToMockBtmDevice = {{acl_conn_handle, mock_btm_device}};

  ASSERT_TRUE(manager_instance_->AddIncomingCisEventsListener(client_handle_, test_address, cig_id,
                                                              cis_id));

  // Simulate CIS request to associate a handle
  std::vector<uint8_t> buf(6);
  uint8_t* p = buf.data();
  UINT16_TO_STREAM(p, acl_conn_handle);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT8_TO_STREAM(p, cig_id);
  UINT8_TO_STREAM(p, cis_id);

  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisRequest, _))
          .Times(1);
  manager_instance_->HandleHciEvent(HCI_BLE_CIS_REQ_EVT, buf.data(), buf.size());

  EXPECT_CALL(hcic_interface_, AcceptCis(cis_conn_handle)).Times(1);
  manager_instance_->AcceptIncomingCisConnection(cis_conn_handle);

  /* Send CIS establish complete event */
  std::vector<uint8_t> est_buf(28);
  p = est_buf.data();
  UINT8_TO_STREAM(p, HCI_SUCCESS);
  UINT16_TO_STREAM(p, cis_conn_handle);
  UINT24_TO_STREAM(p, 0);  // CIG_Sync_Delay
  UINT24_TO_STREAM(p, 0);  // CIS_Sync_Delay
  UINT24_TO_STREAM(p, 0);  // Transport_Latency_M_To_S
  UINT24_TO_STREAM(p, 0);  // Transport_Latency_S_To_M
  UINT8_TO_STREAM(p, 0);   // PHY_M_To_S
  UINT8_TO_STREAM(p, 0);   // PHY_S_To_M
  UINT8_TO_STREAM(p, 0);   // NSE
  UINT8_TO_STREAM(p, 0);   // BN_M_To_S
  UINT8_TO_STREAM(p, 0);   // BN_S_To_M
  UINT8_TO_STREAM(p, 0);   // FT_M_To_S
  UINT8_TO_STREAM(p, 0);   // FT_S_To_M
  UINT16_TO_STREAM(p, 0);  // Max_PDU_M_To_S
  UINT16_TO_STREAM(p, 0);  // Max_PDU_S_To_M
  UINT16_TO_STREAM(p, 0);  // ISO_Interval

  /* We should get a CIG event now */
  EXPECT_CALL(*cig_callbacks_,
              OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl, _));
  manager_instance_->HandleHciEvent(HCI_BLE_CIS_EST_EVT, est_buf.data(), est_buf.size());

  // Expect that the callback is NOT called.
  EXPECT_CALL(*cig_callbacks_, OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, _))
          .Times(1);
  // Remote disconnects
  uint8_t reason = 0x13;  // remote user terminated connection
  manager_instance_->HandleDisconnect(cis_conn_handle, reason);

  // Unregister the event listener
  manager_instance_->RemoveIncomingCisEventsListener(client_handle_, test_address, cig_id, cis_id);
}
