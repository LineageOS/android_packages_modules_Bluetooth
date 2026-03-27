/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#include "state_machine.h"

#include <bluetooth/log.h>
#include <bluetooth/types/bt_transport.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include <functional>
#include <utility>

#include "bta/le_audio/content_control_id_keeper.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "bta_gatt_queue_mock.h"
#include "btm_api_mock.h"
#include "client_parser.h"
#include "fake_osi.h"
#include "hci/controller_mock.h"
#include "internal_include/stack_config.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_set_configuration_provider.h"
#include "mock_codec_manager.h"
#include "mock_csis_client.h"
#include "mock_test_sync_main_handler.h"
#include "stack/include/bt_types.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_btm_iso.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"

using ::bluetooth::le_audio::DeviceConnectState;
using ::bluetooth::le_audio::StateMachineInvalidStatus;
using ::bluetooth::le_audio::codec_spec_caps::kLeAudioCodecChannelCountSingleChannel;
using ::bluetooth::le_audio::codec_spec_caps::kLeAudioCodecChannelCountTwoChannel;
using ::bluetooth::le_audio::types::LeAudioContextType;
using ::testing::_;
using ::testing::AnyNumber;
using ::testing::AtLeast;
using ::testing::DoAll;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SaveArg;
using ::testing::Test;

extern struct fake_osi_alarm_set_on_mloop fake_osi_alarm_set_on_mloop_;

constexpr uint8_t media_ccid = 0xC0;
constexpr auto media_context = LeAudioContextType::MEDIA;

constexpr uint8_t call_ccid = 0xD0;
constexpr auto call_context = LeAudioContextType::CONVERSATIONAL;

const std::string kSmpOptions("mock smp options");
static bool get_pts_avrcp_test(void) { return false; }
static bool get_pts_secure_only_mode(void) { return false; }
static bool get_pts_conn_updates_disabled(void) { return false; }
static bool get_pts_crosskey_sdp_disable(void) { return false; }
static const std::string* get_pts_smp_options(void) { return &kSmpOptions; }
static int get_pts_smp_failure_case(void) { return 123; }
static bool get_pts_force_eatt_for_notifications(void) { return false; }
static bool get_pts_connect_eatt_unconditionally(void) { return false; }
static bool get_pts_connect_eatt_before_encryption(void) { return false; }
static bool get_pts_unencrypt_broadcast(void) { return false; }
static bool get_pts_eatt_peripheral_collision_support(void) { return false; }
static bool get_pts_force_le_audio_multiple_contexts_metadata(void) { return false; }
static bool get_pts_le_audio_disable_ases_before_stopping(void) { return false; }

stack_config_t mock_stack_config{
        .get_pts_avrcp_test = get_pts_avrcp_test,
        .get_pts_secure_only_mode = get_pts_secure_only_mode,
        .get_pts_conn_updates_disabled = get_pts_conn_updates_disabled,
        .get_pts_crosskey_sdp_disable = get_pts_crosskey_sdp_disable,
        .get_pts_smp_options = get_pts_smp_options,
        .get_pts_smp_failure_case = get_pts_smp_failure_case,
        .get_pts_force_eatt_for_notifications = get_pts_force_eatt_for_notifications,
        .get_pts_connect_eatt_unconditionally = get_pts_connect_eatt_unconditionally,
        .get_pts_connect_eatt_before_encryption = get_pts_connect_eatt_before_encryption,
        .get_pts_unencrypt_broadcast = get_pts_unencrypt_broadcast,
        .get_pts_eatt_peripheral_collision_support = get_pts_eatt_peripheral_collision_support,
        .get_pts_force_le_audio_multiple_contexts_metadata =
                get_pts_force_le_audio_multiple_contexts_metadata,
        .get_pts_le_audio_disable_ases_before_stopping =
                get_pts_le_audio_disable_ases_before_stopping,
};
const stack_config_t* stack_config_get_interface(void) { return &mock_stack_config; }

namespace bluetooth::le_audio {
namespace internal {

// Just some arbitrary initial handles - it has no real meaning
#define ATTR_HANDLE_ASCS_POOL_START (0x0000 | 32)
#define ATTR_HANDLE_PACS_POOL_START (0xFF00 | 64)

constexpr LeAudioContextType kContextTypeUnspecified = static_cast<LeAudioContextType>(0x0001);
constexpr LeAudioContextType kContextTypeConversational = static_cast<LeAudioContextType>(0x0002);
constexpr LeAudioContextType kContextTypeMedia = static_cast<LeAudioContextType>(0x0004);
constexpr LeAudioContextType kContextTypeLive = static_cast<LeAudioContextType>(0x0040);
constexpr LeAudioContextType kContextTypeSoundEffects = static_cast<LeAudioContextType>(0x0080);
constexpr LeAudioContextType kContextTypeRingtone = static_cast<LeAudioContextType>(0x0200);

namespace codec_specific {

constexpr uint8_t kLc3CodingFormat = 0x06;

// Reference Codec Capabilities values to test against
constexpr uint8_t kCapTypeSupportedSamplingFrequencies = 0x01;
constexpr uint8_t kCapTypeSupportedFrameDurations = 0x02;
constexpr uint8_t kCapTypeAudioChannelCount = 0x03;
constexpr uint8_t kCapTypeSupportedOctetsPerCodecFrame = 0x04;
constexpr uint8_t kCapTypeSupportedLc3CodecFramesPerSdu = 0x05;

// constexpr uint8_t kCapSamplingFrequency8000Hz = 0x0001;
// constexpr uint8_t kCapSamplingFrequency11025Hz = 0x0002;
constexpr uint8_t kCapSamplingFrequency16000Hz = 0x0004;
// constexpr uint8_t kCapSamplingFrequency22050Hz = 0x0008;
// constexpr uint8_t kCapSamplingFrequency24000Hz = 0x0010;
constexpr uint8_t kCapSamplingFrequency32000Hz = 0x0020;
// constexpr uint8_t kCapSamplingFrequency44100Hz = 0x0040;
constexpr uint8_t kCapSamplingFrequency48000Hz = 0x0080;
// constexpr uint8_t kCapSamplingFrequency88200Hz = 0x0100;
// constexpr uint8_t kCapSamplingFrequency96000Hz = 0x0200;
// constexpr uint8_t kCapSamplingFrequency176400Hz = 0x0400;
// constexpr uint8_t kCapSamplingFrequency192000Hz = 0x0800;
// constexpr uint8_t kCapSamplingFrequency384000Hz = 0x1000;

constexpr uint8_t kCapFrameDuration7p5ms = 0x01;
constexpr uint8_t kCapFrameDuration10ms = 0x02;
// constexpr uint8_t kCapFrameDuration7p5msPreferred = 0x10;
constexpr uint8_t kCapFrameDuration10msPreferred = 0x20;
}  // namespace codec_specific

namespace ascs {
constexpr uint8_t kAseStateIdle = 0x00;
constexpr uint8_t kAseStateCodecConfigured = 0x01;
constexpr uint8_t kAseStateQoSConfigured = 0x02;
constexpr uint8_t kAseStateEnabling = 0x03;
constexpr uint8_t kAseStateStreaming = 0x04;
constexpr uint8_t kAseStateDisabling = 0x05;
constexpr uint8_t kAseStateReleasing = 0x06;

// constexpr uint8_t kAseParamDirectionServerIsAudioSink = 0x01;
// constexpr uint8_t kAseParamDirectionServerIsAudioSource = 0x02;

constexpr uint8_t kAseParamFramingUnframedSupported = 0x00;
// constexpr uint8_t kAseParamFramingUnframedNotSupported = 0x01;

// constexpr uint8_t kAseParamPreferredPhy1M = 0x01;
// constexpr uint8_t kAseParamPreferredPhy2M = 0x02;
// constexpr uint8_t kAseParamPreferredPhyCoded = 0x04;

constexpr uint8_t kAseCtpOpcodeMaxVal = client_parser::ascs::kCtpOpcodeRelease;

}  // namespace ascs

static RawAddress GetTestAddress(uint8_t index) {
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
  return RawAddress(bytes);
}

class MockLeAudioGroupStateMachineCallbacks : public LeAudioGroupStateMachine::Callbacks {
public:
  MockLeAudioGroupStateMachineCallbacks() = default;
  MockLeAudioGroupStateMachineCallbacks(const MockLeAudioGroupStateMachineCallbacks&) = delete;
  MockLeAudioGroupStateMachineCallbacks& operator=(const MockLeAudioGroupStateMachineCallbacks&) =
          delete;

  ~MockLeAudioGroupStateMachineCallbacks() override = default;
  MOCK_METHOD((uint8_t), OnGetEnabledDirections, (int group_id), (override));
  MOCK_METHOD((void), StatusReportCb, (int group_id, bluetooth::le_audio::GroupStreamStatus status),
              (override));
  MOCK_METHOD((void), OnStateTransitionTimeout, (int group_id), (override));
  MOCK_METHOD((void), OnUpdatedCisConfiguration, (int group_id, uint8_t direction), (override));
  MOCK_METHOD((void), OnStateMachineInvalidStatusCb,
              (int group_id, StateMachineInvalidStatus invalid_status), (override));
};

class MockAseRemoteStateMachine {
public:
  MockAseRemoteStateMachine() = default;
  MockAseRemoteStateMachine& operator=(const MockAseRemoteStateMachine&) = delete;
  ~MockAseRemoteStateMachine() = default;
  MOCK_METHOD((void), AseCtpConfigureCodecHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpConfigureQosHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpEnableHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpReceiverStartReadyHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpDisableHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpReceiverStopReadyHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpUpdateMetadataHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
  MOCK_METHOD((void), AseCtpReleaseHandler,
              (LeAudioDevice * device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
               void* cb_data));
};

class StateMachineTestBase : public Test {
protected:
  uint8_t ase_id_last_assigned = types::ase::kAseIdInvalid;
  uint8_t additional_snk_ases = 0;
  uint8_t additional_src_ases = 0;
  uint8_t channel_count_ = kLeAudioCodecChannelCountSingleChannel;
  uint8_t codec_frame_blocks_per_sdu_ = 1;
  uint16_t sample_freq_ = codec_specific::kCapSamplingFrequency16000Hz |
                          codec_specific::kCapSamplingFrequency32000Hz;
  uint8_t channel_allocations_sink_ =
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontRight;
  uint8_t channel_allocations_source_ =
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontRight;

  /* Use to simulated error status on Cis creation */
  int num_of_simulated_cis_events_;
  uint8_t remove_cig_simulated_status_;
  bool overwrite_cis_status_;
  bool use_cis_retry_cnt_;
  int retry_cis_established_cnt_;
  bool do_not_send_cis_establish_event_;
  bool do_not_send_cis_disconnected_event_;
  bool do_not_send_setup_iso_data_path_event_;
  bool do_not_send_remove_iso_data_path_event_;
  uint8_t overwrite_cis_status_idx_;
  std::vector<uint8_t> cis_status_;
  uint8_t enabled_directions_;
  bool group_is_suspending_;
  uint8_t iso_client_handle_ = 1;

  /* Control test specific sdu configuration */
  typedef struct {
    bool inject_sdu_interval;
    uint32_t sdu_interval;
    bool inject_max_sdu;
    uint16_t max_sdu;
  } test_sdu_config_t;

  test_sdu_config_t test_remote_sink_sdu_config_;
  test_sdu_config_t test_remote_source_sdu_config_;

  /* Control test specific presentation delays */
  typedef struct {
    uint32_t min;
    uint32_t max;
    uint32_t pref_min;
    uint32_t pref_max;
  } test_presentation_delay_t;

  std::vector<std::pair<RawAddress, test_presentation_delay_t>>
          test_remote_sink_presentation_delay_vec_;
  std::vector<std::pair<RawAddress, test_presentation_delay_t>>
          test_remote_source_presentation_delay_vec_;

  std::vector<std::pair<RawAddress, uint16_t>> test_remote_sink_max_transport_latency_vec_;
  std::vector<std::pair<RawAddress, uint16_t>> test_remote_source_max_transport_latency_vec_;

  /* Needed for tests when one set member is bonded */
  int overrided_group_size_;

  /* Keep ASE in releasing state */
  bool stay_in_releasing_state_;
  /* Do not response immediately on Release CTP for the devices in the list*/
  std::vector<RawAddress> block_releasing_state_device_list_;

  /* Use for single test to simulate late ASE notifications */
  bool stop_inject_configured_ase_after_first_ase_configured_;

  uint16_t attr_handle = ATTR_HANDLE_ASCS_POOL_START;
  uint16_t pacs_attr_handle_next = ATTR_HANDLE_PACS_POOL_START;

  virtual void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_DEBUG);
    com_android_bluetooth_flags_reset_flags();
    set_com_android_bluetooth_flags_leaudio_always_use_group_size_to_check_audio_config(true);
    set_com_android_bluetooth_flags_leaudio_fix_allocation_in_codec_config(true);
    set_com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig(true);
    com::android::bluetooth::flags::provider_->leaudio_fix_qos_reconfiguration(true);

    init_message_loop_thread();
    reset_mock_function_count_map();
    set_mock_btm_client_interface(&btm_interface);
    gatt::SetMockBtaGattInterface(&gatt_interface);
    gatt::SetMockBtaGattQueue(&gatt_queue);

    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockController>();

    remove_cig_simulated_status_ = 0;
    overwrite_cis_status_idx_ = 0;
    use_cis_retry_cnt_ = false;
    retry_cis_established_cnt_ = 0;
    overwrite_cis_status_ = false;
    do_not_send_cis_establish_event_ = false;
    do_not_send_cis_disconnected_event_ = false;
    do_not_send_setup_iso_data_path_event_ = false;
    do_not_send_remove_iso_data_path_event_ = false;
    stay_in_releasing_state_ = false;
    block_releasing_state_device_list_.clear();
    stop_inject_configured_ase_after_first_ase_configured_ = false;
    cis_status_.clear();
    enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;
    group_is_suspending_ = false;
    overrided_group_size_ = -1;
    test_remote_sink_sdu_config_ = {};
    test_remote_source_sdu_config_ = {};

    test_remote_sink_presentation_delay_vec_.clear();
    test_remote_source_presentation_delay_vec_.clear();

    test_remote_sink_max_transport_latency_vec_.clear();
    test_remote_source_max_transport_latency_vec_.clear();

    LeAudioGroupStateMachine::Initialize(&mock_callbacks_, iso_client_handle_);

    ContentControlIdKeeper::GetInstance()->Start();

    ON_CALL(mock_callbacks_, OnStateMachineInvalidStatusCb(_, _))
            .WillByDefault(Invoke([this](int group_id,
                                         bluetooth::le_audio::StateMachineInvalidStatus
                                                 invalid_status) {
              log::debug("[Testing] OnStateMachineInvalidStatusCb: group id: {}, invalid_status: {}",
                         group_id, invalid_status);
              auto& group = le_audio_device_groups_[group_id];

              do_in_main_thread(base::BindOnce(
                      [](LeAudioDeviceGroup* group, LeAudioGroupStateMachine* state_machine) {
                        state_machine->StopStream(group);
                      },
                      group.get(), LeAudioGroupStateMachine::Get()));
            }));
    ON_CALL(mock_callbacks_, StatusReportCb(_, _))
            .WillByDefault(Invoke([](int group_id, bluetooth::le_audio::GroupStreamStatus status) {
              log::debug("[Testing] StatusReportCb: group id: {}, status: {}", group_id,
                         common::ToString(status));
            }));

    ON_CALL(mock_callbacks_, OnGetEnabledDirections(_)).WillByDefault(Invoke([this](int group_id) {
      log::debug("[Testing] OnGetEnabledDirections: group_id: {}, directions: {:#x}", group_id,
                 enabled_directions_);
      /* Note: By default enabled_directions_ is Both.
       * `group_is_suspendig_ `is used when SuspendStream() was called and group does not use
       *  dynamic direction feature and this is needed to make sure that Group will stay in QoS
       * configure state and not get enabled automatically. */
      return group_is_suspending_ ? 0 : enabled_directions_;
    }));

    MockCsisClient::SetMockInstanceForTesting(&mock_csis_client_module_);
    ON_CALL(mock_csis_client_module_, Get()).WillByDefault(Return(&mock_csis_client_module_));
    ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));
    ON_CALL(mock_csis_client_module_, GetDeviceList(_))
            .WillByDefault(Invoke([this](int /*group_id*/) { return addresses_; }));
    ON_CALL(mock_csis_client_module_, GetDesiredSize(_))
            .WillByDefault(Invoke([this](int /*group_id*/) {
              if (overrided_group_size_ < 0) {
                return (int)(addresses_.size());
              }

              return overrided_group_size_;
            }));

    // Support 2M Phy
    ON_CALL(btm_interface, BTM_IsPhy2mSupported(_, _)).WillByDefault(Return(true));
    ON_CALL(btm_interface, BTM_GetHCIConnHandle(_, _))
            .WillByDefault(Invoke([](RawAddress const& remote_bda, tBT_TRANSPORT /*transport*/) {
              return remote_bda.IsEmpty()
                             ? HCI_INVALID_HANDLE
                             : ((uint16_t)(remote_bda.address[0] ^ remote_bda.address[1] ^
                                           remote_bda.address[2]))
                                               << 8 |
                                       (remote_bda.address[3] ^ remote_bda.address[4] ^
                                        remote_bda.address[5]);
            }));

    ON_CALL(gatt_queue, WriteCharacteristic(_, _, _, GATT_WRITE_NO_RSP, _, _))
            .WillByDefault(Invoke(
                    [this](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                           tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
                      for (auto& dev : le_audio_devices_) {
                        if (dev->conn_id_ == conn_id) {
                          // Control point write handler
                          if (dev->ctp_hdls_.val_hdl == handle) {
                            HandleCtpOperation(dev.get(), value, cb, cb_data);
                          }
                          break;
                        }
                      }
                    }));

    ConfigureIsoManagerMock();
  }

  void setTestSduInterval(uint32_t sdu_interval, uint8_t direction = types::kLeAudioDirectionBoth) {
    if (direction & types::kLeAudioDirectionSink) {
      test_remote_sink_sdu_config_.inject_sdu_interval = true;
      test_remote_sink_sdu_config_.sdu_interval = sdu_interval;
    }

    if (direction & types::kLeAudioDirectionSource) {
      test_remote_source_sdu_config_.inject_sdu_interval = true;
      test_remote_source_sdu_config_.sdu_interval = sdu_interval;
    }
  }

  void setTestMaxSdu(uint16_t max_sdu, uint8_t direction = types::kLeAudioDirectionBoth) {
    if (direction & types::kLeAudioDirectionSink) {
      test_remote_sink_sdu_config_.inject_max_sdu = true;
      test_remote_sink_sdu_config_.max_sdu = max_sdu;
    }

    if (direction & types::kLeAudioDirectionSource) {
      test_remote_source_sdu_config_.inject_max_sdu = true;
      test_remote_source_sdu_config_.max_sdu = max_sdu;
    }
  }

  void setTestPrefPresentationDelay(uint32_t min, uint32_t max, uint32_t pref_min,
                                    uint32_t pref_max, RawAddress addr = RawAddress::kEmpty,
                                    uint8_t direction = types::kLeAudioDirectionBoth) {
    log::debug(
            "setTestPrefPresentationDelay: min {:#x}, max {:#x}, pref_min {:#x}, "
            "pref_max {:#x}",
            min, max, pref_min, pref_max);
    if (direction & types::kLeAudioDirectionSink) {
      test_remote_sink_presentation_delay_vec_.push_back(
              std::make_pair(addr, test_presentation_delay_t{min, max, pref_min, pref_max}));
    }

    if (direction & types::kLeAudioDirectionSource) {
      test_remote_source_presentation_delay_vec_.push_back(
              std::make_pair(addr, test_presentation_delay_t{min, max, pref_min, pref_max}));
    }
  }

  void setTestMaxTransportLatency(uint16_t max_transport_latency,
                                  RawAddress addr = RawAddress::kEmpty,
                                  uint8_t direction = types::kLeAudioDirectionBoth) {
    if (direction & types::kLeAudioDirectionSink) {
      test_remote_sink_max_transport_latency_vec_.push_back(
              std::make_pair(addr, max_transport_latency));
    }
    if (direction & types::kLeAudioDirectionSource) {
      test_remote_source_max_transport_latency_vec_.push_back(
              std::make_pair(addr, max_transport_latency));
    }
  }

  void HandleCtpOperation(LeAudioDevice* device, std::vector<uint8_t> value, GATT_WRITE_OP_CB cb,
                          void* cb_data) {
    auto opcode = value[0];

    // Verify against valid opcode range
    ASSERT_LT(opcode, ascs::kAseCtpOpcodeMaxVal + 1);
    ASSERT_NE(opcode, 0);

    switch (opcode) {
      case client_parser::ascs::kCtpOpcodeCodecConfiguration:
        ase_ctp_handler.AseCtpConfigureCodecHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeQosConfiguration:
        ase_ctp_handler.AseCtpConfigureQosHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeEnable:
        ase_ctp_handler.AseCtpEnableHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeReceiverStartReady:
        ase_ctp_handler.AseCtpReceiverStartReadyHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeDisable:
        ase_ctp_handler.AseCtpDisableHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeReceiverStopReady:
        ase_ctp_handler.AseCtpReceiverStopReadyHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeUpdateMetadata:
        ase_ctp_handler.AseCtpUpdateMetadataHandler(device, std::move(value), cb, cb_data);
        break;
      case client_parser::ascs::kCtpOpcodeRelease:
        ase_ctp_handler.AseCtpReleaseHandler(device, std::move(value), cb, cb_data);
        break;
      default:
        break;
    };
  }

/* Helper function to make a deterministic (and unique on the entire device)
 * connection handle for a given cis.
 */
#define UNIQUE_CIS_CONN_HANDLE(cig_id, cis_index) (cig_id << 8 | cis_index)

  void ConfigureIsoManagerMock() {
    iso_manager_ = bluetooth::hci::IsoManager::GetInstance();
    ASSERT_NE(iso_manager_, nullptr);
    iso_manager_->Start();

    mock_iso_manager_ = MockIsoManager::GetInstance();
    ASSERT_NE(mock_iso_manager_, nullptr);

    ON_CALL(*mock_iso_manager_, CreateCig)
            .WillByDefault(
                    [this](bluetooth::hci::iso_manager::IsoClientHandle /*client_handle*/,
                           uint8_t cig_id, bluetooth::hci::iso_manager::cig_create_params p) {
                      log::debug("CreateCig");
                      last_cig_params_ = p;

                      auto& group = le_audio_device_groups_[cig_id];
                      if (group) {
                        std::vector<uint16_t> conn_handles;
                        // Fake connection ID for each cis in a request
                        for (auto i = 0u; i < p.cis_cfgs.size(); ++i) {
                          conn_handles.push_back(UNIQUE_CIS_CONN_HANDLE(cig_id, i));
                        }
                        auto status = HCI_SUCCESS;
                        if (group_create_command_disallowed_) {
                          group_create_command_disallowed_ = false;
                          status = HCI_ERR_COMMAND_DISALLOWED;
                        }

                        do_in_main_thread(base::BindOnce(
                                [](LeAudioDeviceGroup* group,
                                   LeAudioGroupStateMachine* state_machine, int status,
                                   uint8_t cig_id, std::vector<uint16_t> conn_handles) {
                                  state_machine->ProcessHciNotifOnCigCreate(group, status, cig_id,
                                                                            conn_handles);
                                },
                                group.get(), LeAudioGroupStateMachine::Get(), status, cig_id,
                                conn_handles));
                      }
                    });

    ON_CALL(*mock_iso_manager_, RemoveCig).WillByDefault([this](uint8_t cig_id, bool /*force*/) {
      log::debug("CreateRemove");

      auto& group = le_audio_device_groups_[cig_id];
      if (group) {
        do_in_main_thread(base::BindOnce(
                [](LeAudioDeviceGroup* group, LeAudioGroupStateMachine* state_machine, int status) {
                  state_machine->ProcessHciNotifOnCigRemove(status, group);
                },
                group.get(), LeAudioGroupStateMachine::Get(), remove_cig_simulated_status_));
      }
    });

    ON_CALL(*mock_iso_manager_, SetupIsoDataPath)
            .WillByDefault([this](uint16_t conn_handle,
                                  bluetooth::hci::iso_manager::iso_data_path_params p) {
              log::debug("SetupIsoDataPath");
              last_datapath_params_ = p;

              ASSERT_NE(conn_handle, kInvalidCisConnHandle);

              if (do_not_send_setup_iso_data_path_event_) {
                log::debug("Don't setup ISO data path event");
                return;
              }

              auto dev_it = std::find_if(le_audio_devices_.begin(), le_audio_devices_.end(),
                                         [&conn_handle](auto& dev) {
                                           auto ases = dev->GetAsesByCisConnHdl(conn_handle);
                                           return ases.sink || ases.source;
                                         });
              if (dev_it == le_audio_devices_.end()) {
                log::error("Device not found");
                return;
              }

              for (auto& kv_pair : le_audio_device_groups_) {
                auto& group = kv_pair.second;
                if (group->IsDeviceInTheGroup(dev_it->get())) {
                  InjectHciNotifySetupIsoDataPath(group.get(), dev_it->get(), 0, conn_handle);

                  return;
                }
              }
            });

    ON_CALL(*mock_iso_manager_, RemoveIsoDataPath)
            .WillByDefault([this](uint16_t conn_handle, uint8_t /*iso_direction*/) {
              log::debug("RemoveIsoDataPath");

              ASSERT_NE(conn_handle, kInvalidCisConnHandle);

              if (do_not_send_remove_iso_data_path_event_) {
                log::debug("Don't send remove ISO data path event");
                return;
              }

              auto dev_it = std::find_if(le_audio_devices_.begin(), le_audio_devices_.end(),
                                         [&conn_handle](auto& dev) {
                                           auto ases = dev->GetAsesByCisConnHdl(conn_handle);
                                           return ases.sink || ases.source;
                                         });
              if (dev_it == le_audio_devices_.end()) {
                log::error("Device not found");
                return;
              }

              for (auto& kv_pair : le_audio_device_groups_) {
                auto& group = kv_pair.second;
                if (group->IsDeviceInTheGroup(dev_it->get())) {
                  InjectHciNotifyRemoveIsoDataPath(group.get(), dev_it->get(), 0, conn_handle);

                  return;
                }
              }
            });

    ON_CALL(*mock_iso_manager_, EstablishCis)
            .WillByDefault([this](bluetooth::hci::iso_manager::cis_establish_params conn_params) {
              log::debug("EstablishCis");

              for (auto& pair : conn_params.conn_pairs) {
                ASSERT_NE(pair.cis_conn_handle, kInvalidCisConnHandle);

                if (do_not_send_cis_establish_event_) {
                  log::debug("Don't send cis establish event");
                  continue;
                }

                auto dev_it = std::find_if(
                        le_audio_devices_.begin(), le_audio_devices_.end(), [&pair](auto& dev) {
                          auto ases = dev->GetAsesByCisConnHdl(pair.cis_conn_handle);
                          return ases.sink || ases.source;
                        });
                if (dev_it == le_audio_devices_.end()) {
                  log::error("Device not found");
                  return;
                }

                for (auto& kv_pair : le_audio_device_groups_) {
                  auto& group = kv_pair.second;
                  if (group->IsDeviceInTheGroup(dev_it->get())) {
                    bluetooth::hci::iso_manager::cis_establish_cmpl_evt evt;

                    // Fill proper values if needed
                    if (use_cis_retry_cnt_) {
                      if (retry_cis_established_cnt_ > 0) {
                        evt.status = HCI_ERR_CONN_FAILED_ESTABLISHMENT;
                        retry_cis_established_cnt_--;
                      } else {
                        evt.status = 0;
                      }
                    } else if (overwrite_cis_status_) {
                      evt.status = cis_status_[overwrite_cis_status_idx_++];
                      /* Reset the index */
                      if (cis_status_.size() == overwrite_cis_status_idx_) {
                        overwrite_cis_status_idx_ = 0;
                      }
                    } else {
                      evt.status = 0;
                    }

                    evt.cig_id = group->group_id_;
                    evt.cis_conn_hdl = pair.cis_conn_handle;
                    evt.cig_sync_delay = 0;
                    evt.cis_sync_delay = 0;
                    evt.trans_lat_c_to_p = 0;
                    evt.trans_lat_p_to_c = 0;
                    evt.phy_c_to_p = 0;
                    evt.phy_p_to_c = 0;
                    evt.nse = 0;
                    evt.bn_c_to_p = 0;
                    evt.bn_p_to_c = 0;
                    evt.ft_c_to_p = 0;
                    evt.ft_p_to_c = 0;
                    evt.max_pdu_c_to_p = 0;
                    evt.max_pdu_p_to_c = 0;
                    evt.iso_itv = 0;

                    InjectHciNotifyCisEstablished(group.get(), dev_it->get(), evt);

                    break;
                  }
                }
              }
            });

    ON_CALL(*mock_iso_manager_, DisconnectCis)
            .WillByDefault([this](uint16_t cis_handle, uint8_t reason) {
              log::debug("DisconnectCis");

              ASSERT_NE(cis_handle, kInvalidCisConnHandle);

              if (do_not_send_cis_disconnected_event_) {
                log::debug("Don't send cis disconnected event");
                return;
              }

              auto dev_it = std::find_if(le_audio_devices_.begin(), le_audio_devices_.end(),
                                         [&cis_handle](auto& dev) {
                                           auto ases = dev->GetAsesByCisConnHdl(cis_handle);
                                           return ases.sink || ases.source;
                                         });
              if (dev_it == le_audio_devices_.end()) {
                log::error("Device not found");
                return;
              }

              // When we disconnect the remote with HCI_ERR_PEER_USER, we
              // should be getting HCI_ERR_CONN_CAUSE_LOCAL_HOST from HCI.
              if (reason == HCI_ERR_PEER_USER) {
                reason = HCI_ERR_CONN_CAUSE_LOCAL_HOST;
              }

              for (auto& kv_pair : le_audio_device_groups_) {
                auto& group = kv_pair.second;
                if (group->IsDeviceInTheGroup(dev_it->get())) {
                  bluetooth::hci::iso_manager::cis_disconnected_evt evt{
                          .reason = reason,
                          .cig_id = static_cast<uint8_t>(group->group_id_),
                          .cis_conn_hdl = cis_handle,
                  };

                  InjectHciNotifyCisDisconnected(group.get(), dev_it->get(), evt);

                  return;
                }
              }
            });
  }

  void ConfigCodecManagerMock(types::CodecLocation location) {
    codec_manager_ = bluetooth::le_audio::CodecManager::GetInstance();
    ASSERT_NE(codec_manager_, nullptr);
    std::vector<bluetooth::le_audio::btle_audio_codec_config_t> mock_offloading_preference(0);
    codec_manager_->Start(mock_offloading_preference);
    mock_codec_manager_ = MockCodecManager::GetInstance();
    ASSERT_NE(mock_codec_manager_, nullptr);
    ON_CALL(*mock_codec_manager_, GetCodecLocation()).WillByDefault(Return(location));
    // Regardless of the codec location, return all the possible configurations
    ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(true));
    ON_CALL(*mock_codec_manager_, CheckCodecConfigIsBiDirSwb)
            .WillByDefault(Invoke([](const types::AudioSetConfiguration& config) -> bool {
              return AudioSetConfigurationProvider::Get()->CheckConfigurationIsBiDirSwb(config);
            }));
    ON_CALL(*mock_codec_manager_, GetCodecConfig)
            .WillByDefault(
                    Invoke([this](const bluetooth::le_audio::CodecManager::
                                          UnicastConfigurationRequirements& requirements,
                                  bluetooth::le_audio::CodecManager::UnicastConfigurationProvider
                                          provider) {
                      auto configs = *bluetooth::le_audio::AudioSetConfigurationProvider::Get()
                                              ->GetConfigurations(requirements.audio_context_type);
                      // Note: This dual bidir SWB exclusion logic has to match the
                      // CodecManager::GetCodecConfig() implementation.
                      if (!CodecManager::GetInstance()->IsDualBiDirSwbSupported()) {
                        configs.erase(
                                std::remove_if(configs.begin(), configs.end(),
                                               [](auto const& el) {
                                                 if (el->confs.source.empty()) {
                                                   return false;
                                                 }
                                                 return AudioSetConfigurationProvider::Get()
                                                         ->CheckConfigurationIsDualBiDirSwb(*el);
                                               }),
                                configs.end());
                      }
                      auto config = provider(requirements, &configs);

                      /* Inject SDU Interval if needed */
                      if (test_remote_sink_sdu_config_.inject_sdu_interval) {
                        for (auto& el : config->confs.sink) {
                          el.qos.sduIntervalUs = test_remote_sink_sdu_config_.sdu_interval;
                        }
                      }
                      if (test_remote_source_sdu_config_.inject_sdu_interval) {
                        for (auto& el : config->confs.source) {
                          el.qos.sduIntervalUs = test_remote_source_sdu_config_.sdu_interval;
                        }
                      }

                      /* Inject MAX SDU if needed */
                      if (test_remote_sink_sdu_config_.inject_max_sdu) {
                        for (auto& el : config->confs.sink) {
                          el.qos.maxSdu = test_remote_sink_sdu_config_.sdu_interval;
                        }
                      }
                      if (test_remote_source_sdu_config_.inject_max_sdu) {
                        for (auto& el : config->confs.source) {
                          el.qos.maxSdu = test_remote_source_sdu_config_.max_sdu;
                        }
                      }

                      // Inject the DSA channel configuration for the remote source direction
                      if (requirements.flags & CodecManager::Flags::SPATIAL_AUDIO) {
                        config->confs.source = config->confs.sink;
                        for (auto& el : config->confs.source) {
                          el.codec.id = types::kLeAudioCodecHeadtracking;
                          el.qos.max_transport_latency = 0xFF;
                          el.qos.sduIntervalUs = 0xFF;
                          el.qos.maxSdu = 0xFF;
                          el.qos.retransmission_number = 2;
                          el.qos.target_latency = 0xA;

                          el.data_path_configuration.dataPathId =
                                  bluetooth::hci::iso_manager::kIsoDataPathHci;
                          el.data_path_configuration.isoDataPathConfig.codecId =
                                  types::kLeAudioCodecHeadtracking;
                          el.data_path_configuration.isoDataPathConfig.isTransparent = false;
                          el.data_path_configuration.isoDataPathConfig.controllerDelayUs = 0;
                        }
                        config->name += "-Headtracking";
                      }

                      return config;
                    }));
  }

  void TearDown() override {
    /* Clear the alarm on tear down in case test case ends when the
     * alarm is scheduled
     */
    alarm_cancel(nullptr);
    cleanup_message_loop_thread();

    iso_manager_->Stop();
    mock_iso_manager_ = nullptr;
    codec_manager_->Stop();
    mock_codec_manager_ = nullptr;

    gatt::SetMockBtaGattQueue(nullptr);
    gatt::SetMockBtaGattInterface(nullptr);
    reset_mock_btm_client_interface();

    le_audio_devices_.clear();
    le_audio_device_groups_.clear();
    addresses_.clear();
    cached_codec_configuration_map_.clear();
    cached_qos_configuration_map_.clear();
    cached_ase_to_cis_id_map_.clear();
    cached_remote_qos_configuration_for_ase_.clear();
    LeAudioGroupStateMachine::Cleanup();
    ::bluetooth::le_audio::AudioSetConfigurationProvider::Cleanup();
    bluetooth::hci::testing::mock_controller_.release();
  }

  std::shared_ptr<LeAudioDevice> PrepareConnectedDevice(uint8_t id,
                                                        DeviceConnectState initial_connect_state,
                                                        uint8_t num_ase_snk, uint8_t num_ase_src) {
    auto leAudioDevice = std::make_shared<LeAudioDevice>(GetTestAddress(id), initial_connect_state);
    leAudioDevice->conn_id_ = id;
    leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED);

    leAudioDevice->audio_avail_hdls_.val_hdl = attr_handle++;
    leAudioDevice->audio_avail_hdls_.ccc_hdl = attr_handle++;
    leAudioDevice->audio_supp_cont_hdls_.val_hdl = attr_handle++;
    leAudioDevice->audio_supp_cont_hdls_.ccc_hdl = attr_handle++;
    leAudioDevice->ctp_hdls_.val_hdl = attr_handle++;
    leAudioDevice->ctp_hdls_.ccc_hdl = attr_handle++;

    // Add some Sink ASEs
    while (num_ase_snk) {
      types::ase ase(0, 0, 0x01);
      ase.hdls.val_hdl = attr_handle++;
      ase.hdls.ccc_hdl = attr_handle++;

      leAudioDevice->ases_.push_back(std::move(ase));
      num_ase_snk--;
    }

    // Add some Source ASEs
    while (num_ase_src) {
      types::ase ase(0, 0, 0x02);
      ase.hdls.val_hdl = attr_handle++;
      ase.hdls.ccc_hdl = attr_handle++;

      leAudioDevice->ases_.push_back(std::move(ase));
      num_ase_src--;
    }

    le_audio_devices_.push_back(leAudioDevice);
    addresses_.push_back(leAudioDevice->address_);

    return leAudioDevice;
  }

  LeAudioDeviceGroup* GroupFindById(int group_id) {
    return le_audio_device_groups_.count(group_id) ? le_audio_device_groups_[group_id].get()
                                                   : nullptr;
  }

  LeAudioDeviceGroup* GroupTheDevice(int group_id,
                                     const std::shared_ptr<LeAudioDevice>& leAudioDevice) {
    if (le_audio_device_groups_.count(group_id) == 0) {
      le_audio_device_groups_[group_id] = std::make_unique<LeAudioDeviceGroup>(group_id);
    }

    auto& group = le_audio_device_groups_[group_id];

    group->AddNode(leAudioDevice);
    if (group->IsEmpty()) {
      return nullptr;
    }

    return &(*group);
  }

  void InjectAclConnected(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                          uint16_t conn_id) {
    // Do what the client.cc does when handling the disconnection event
    leAudioDevice->conn_id_ = conn_id;
    leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED);

    /* Update all stuff on the group when device got connected */
    group->ReloadAudioLocations();
    group->ReloadAudioDirections();
    group->InvalidateCachedConfigurations();
    group->InvalidateGroupStrategy();
  }

  void InjectAclDisconnected_andWait(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    // Do what the client.cc does when handling the disconnection event
    leAudioDevice->conn_id_ = GATT_INVALID_CONN_ID;
    leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);
    InjectHciNotifyAclDisconnected_andWait(group, leAudioDevice);
  }

  void InjectReleasingAndIdleState(LeAudioDeviceGroup* group, LeAudioDevice* device,
                                   bool release = true, bool idle = true) {
    for (auto& ase : device->ases_) {
      if (ase.id == bluetooth::le_audio::types::ase::kAseIdInvalid) {
        continue;
      }
      // Simulate autonomus RELEASE and moving to IDLE state
      if (release) {
        InjectAseStateNotification(&ase, device, group, ascs::kAseStateReleasing, nullptr);
      }
      if (idle) {
        InjectAseStateNotification(&ase, device, group, ascs::kAseStateIdle, nullptr);
      }
    }
  }

  void InjectReleasingAndIdleState_andWait(LeAudioDeviceGroup* group, LeAudioDevice* device,
                                           bool release = true, bool idle = true) {
    InjectReleasingAndIdleState(group, device, release, idle);
    SyncOnMainLoop();
  }

  void InjectReleaseAndIdleStateForAGroup_andWait(LeAudioDeviceGroup* group, bool release = true,
                                                  bool idle = true) {
    auto leAudioDevice = group->GetFirstActiveDevice();
    while (leAudioDevice) {
      log::info("Group : {},  dev: {}", group->group_id_, leAudioDevice->address_);
      InjectReleasingAndIdleState(group, leAudioDevice, release, idle);
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
    }
    SyncOnMainLoop();
  }

  void InjectCachedConfigurationForActiveAses(LeAudioDeviceGroup* group, LeAudioDevice* device) {
    for (auto& ase : device->ases_) {
      if (!ase.active) {
        continue;
      }
      log::info("ID : {},  status {}", ase.id, bluetooth::common::ToString(ase.state));

      InjectAseStateNotification(&ase, device, group, ascs::kAseStateCodecConfigured,
                                 &cached_codec_configuration_map_[ase.id]);
    }
  }

  void InjectCachedConfigurationForActiveAses_andWait(LeAudioDeviceGroup* group,
                                                      LeAudioDevice* device) {
    InjectCachedConfigurationForActiveAses(group, device);
    SyncOnMainLoop();
  }

  void InjectCachedConfigurationForGroup_andWait(LeAudioDeviceGroup* group) {
    auto leAudioDevice = group->GetFirstActiveDevice();
    while (leAudioDevice) {
      log::info("Group : {},  dev: {}", group->group_id_, leAudioDevice->address_);
      InjectCachedConfigurationForActiveAses(group, leAudioDevice);
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
    }
    SyncOnMainLoop();
  }

  void InjectStreamingStateFroActiveAses_andWait(LeAudioDeviceGroup* group, LeAudioDevice* device) {
    for (auto& ase : device->ases_) {
      if (!ase.active) {
        continue;
      }
      log::info("ID : {},  status {}", ase.id, bluetooth::common::ToString(ase.state));
      client_parser::ascs::ase_transient_state_params params;

      InjectAseStateNotification(&ase, device, group, ascs::kAseStateStreaming, &params);
    }
    SyncOnMainLoop();
  }

  void InjectEnablingStateFroActiveAses_andWait(LeAudioDeviceGroup* group, LeAudioDevice* device) {
    for (auto& ase : device->ases_) {
      if (!ase.active) {
        continue;
      }
      log::info("ID : {},  status {}", ase.id, bluetooth::common::ToString(ase.state));
      client_parser::ascs::ase_transient_state_params enable_params;

      InjectAseStateNotification(&ase, device, group, ascs::kAseStateEnabling, &enable_params);
    }
    SyncOnMainLoop();
  }

  void InjectQoSConfigurationForActiveAses(LeAudioDeviceGroup* group, LeAudioDevice* device) {
    for (auto& ase : device->ases_) {
      if (!ase.active) {
        continue;
      }
      log::info("ID : {},  status {}", ase.id, bluetooth::common::ToString(ase.state));

      if (ase.direction == ::bluetooth::le_audio::types::kLeAudioDirectionSource) {
        client_parser::ascs::ase_transient_state_params disabling_params = {.metadata = {}};
        InjectAseStateNotification(&ase, device, group, ascs::kAseStateDisabling,
                                   &disabling_params);
      }

      InjectAseStateNotification(&ase, device, group, ascs::kAseStateQoSConfigured,
                                 &cached_qos_configuration_map_[ase.id]);
    }
  }

  void InjectQoSConfigurationForActiveAses_andWait(LeAudioDeviceGroup* group,
                                                   LeAudioDevice* device) {
    InjectQoSConfigurationForActiveAses(group, device);
    SyncOnMainLoop();
  }

  void InjectQoSConfigurationForGroupActiveAses_andWait(LeAudioDeviceGroup* group) {
    auto leAudioDevice = group->GetFirstActiveDevice();
    while (leAudioDevice) {
      log::info("Group : {},  dev: {}", group->group_id_, leAudioDevice->address_);
      InjectQoSConfigurationForActiveAses(group, leAudioDevice);
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
    }
    SyncOnMainLoop();
  }

  void InjectAseStateNotification(types::ase* ase, LeAudioDevice* device, LeAudioDeviceGroup* group,
                                  uint8_t new_state, void* new_state_params) {
    // Prepare additional params
    switch (new_state) {
      case ascs::kAseStateCodecConfigured: {
        client_parser::ascs::ase_codec_configured_state_params* conf =
                static_cast<client_parser::ascs::ase_codec_configured_state_params*>(
                        new_state_params);
        std::vector<uint8_t> notif_value(25 + conf->codec_spec_conf.size());
        auto* p = notif_value.data();

        UINT8_TO_STREAM(p, ase->id == types::ase::kAseIdInvalid ? ++ase_id_last_assigned : ase->id);
        UINT8_TO_STREAM(p, new_state);

        UINT8_TO_STREAM(p, conf->framing);
        UINT8_TO_STREAM(p, conf->preferred_phy);
        UINT8_TO_STREAM(p, conf->preferred_retrans_nb);
        UINT16_TO_STREAM(p, conf->max_transport_latency);
        UINT24_TO_STREAM(p, conf->pres_delay_min);
        UINT24_TO_STREAM(p, conf->pres_delay_max);
        UINT24_TO_STREAM(p, conf->preferred_pres_delay_min);
        UINT24_TO_STREAM(p, conf->preferred_pres_delay_max);

        // CodecID:
        UINT8_TO_STREAM(p, conf->codec_id.coding_format);
        UINT16_TO_STREAM(p, conf->codec_id.vendor_company_id);
        UINT16_TO_STREAM(p, conf->codec_id.vendor_codec_id);

        // Codec Spec. Conf. Length and Data
        UINT8_TO_STREAM(p, conf->codec_spec_conf.size());
        memcpy(p, conf->codec_spec_conf.data(), conf->codec_spec_conf.size());

        InjectGattNotifEvent(notif_value, ase, device, group);
      } break;

      case ascs::kAseStateQoSConfigured: {
        client_parser::ascs::ase_qos_configured_state_params* conf =
                static_cast<client_parser::ascs::ase_qos_configured_state_params*>(
                        new_state_params);
        std::vector<uint8_t> notif_value(17);
        auto* p = notif_value.data();

        // Prepare header
        UINT8_TO_STREAM(p, ase->id == types::ase::kAseIdInvalid ? ++ase_id_last_assigned : ase->id);
        UINT8_TO_STREAM(p, new_state);

        UINT8_TO_STREAM(p, conf->cig_id);
        UINT8_TO_STREAM(p, conf->cis_id);
        UINT24_TO_STREAM(p, conf->sdu_interval);
        UINT8_TO_STREAM(p, conf->framing);
        UINT8_TO_STREAM(p, conf->phy);
        UINT16_TO_STREAM(p, conf->max_sdu);
        UINT8_TO_STREAM(p, conf->retrans_nb);
        UINT16_TO_STREAM(p, conf->max_transport_latency);
        UINT24_TO_STREAM(p, conf->pres_delay);

        cached_remote_qos_configuration_for_ase_[ase] = notif_value;

        InjectGattNotifEvent(notif_value, ase, device, group);
      } break;

      case ascs::kAseStateEnabling:
        // fall-through
      case ascs::kAseStateStreaming:
        // fall-through
      case ascs::kAseStateDisabling: {
        client_parser::ascs::ase_transient_state_params* params =
                static_cast<client_parser::ascs::ase_transient_state_params*>(new_state_params);
        std::vector<uint8_t> notif_value(5 + params->metadata.size());
        auto* p = notif_value.data();

        // Prepare header
        UINT8_TO_STREAM(p, ase->id == types::ase::kAseIdInvalid ? ++ase_id_last_assigned : ase->id);

        UINT8_TO_STREAM(p, new_state);

        UINT8_TO_STREAM(p, group->group_id_);
        UINT8_TO_STREAM(p, ase->cis_id);
        UINT8_TO_STREAM(p, params->metadata.size());
        memcpy(p, params->metadata.data(), params->metadata.size());

        InjectGattNotifEvent(notif_value, ase, device, group);
      } break;

      case ascs::kAseStateReleasing:
        // fall-through
      case ascs::kAseStateIdle: {
        std::vector<uint8_t> notif_value(2);
        auto* p = notif_value.data();

        // Prepare header
        UINT8_TO_STREAM(p, ase->id == types::ase::kAseIdInvalid ? ++ase_id_last_assigned : ase->id);
        UINT8_TO_STREAM(p, new_state);

        InjectGattNotifEvent(notif_value, ase, device, group);
      } break;

      default:
        break;
    };
  }

  static void InsertPacRecord(
          std::vector<types::acs_ac_record>& recs, uint16_t sampling_frequencies_bitfield,
          uint8_t supported_frame_durations_bitfield, uint8_t audio_channel_count_bitfield,
          uint16_t supported_octets_per_codec_frame_min,
          uint16_t supported_octets_per_codec_frame_max, uint8_t codec_frame_blocks_per_sdu_ = 1,
          uint8_t coding_format = codec_specific::kLc3CodingFormat,
          uint16_t vendor_company_id = 0x0000, uint16_t vendor_codec_id = 0x0000,
          types::LeAudioLtvMap metadata = types::LeAudioLtvMap()) {
    auto ltv_map = types::LeAudioLtvMap({
            {codec_specific::kCapTypeSupportedSamplingFrequencies,
             {(uint8_t)(sampling_frequencies_bitfield),
              (uint8_t)(sampling_frequencies_bitfield >> 8)}},
            {codec_specific::kCapTypeSupportedFrameDurations, {supported_frame_durations_bitfield}},
            {codec_specific::kCapTypeAudioChannelCount, {audio_channel_count_bitfield}},
            {codec_specific::kCapTypeSupportedOctetsPerCodecFrame,
             {
                     // Min
                     (uint8_t)(supported_octets_per_codec_frame_min),
                     (uint8_t)(supported_octets_per_codec_frame_min >> 8),
                     // Max
                     (uint8_t)(supported_octets_per_codec_frame_max),
                     (uint8_t)(supported_octets_per_codec_frame_max >> 8),
             }},
    });
    ltv_map.Add(codec_specific::kCapTypeSupportedLc3CodecFramesPerSdu,
                (uint8_t)codec_frame_blocks_per_sdu_);
    recs.push_back({
            .codec_id =
                    {
                            .coding_format = coding_format,
                            .vendor_company_id = vendor_company_id,
                            .vendor_codec_id = vendor_codec_id,
                    },
            .codec_spec_caps = ltv_map,
            .codec_spec_caps_raw = ltv_map.RawPacket(),
            .metadata = std::move(metadata),
    });
  }

  void InjectInitialIdleNotification(LeAudioDeviceGroup* group) {
    for (auto* device = group->GetFirstDevice(); device != nullptr;
         device = group->GetNextDevice(device)) {
      for (auto& ase : device->ases_) {
        InjectAseStateNotification(&ase, device, group, ascs::kAseStateIdle, nullptr);
      }
    }
    SyncOnMainLoop();
  }

  void InjectInitialConfiguredNotification(LeAudioDeviceGroup* group) {
    for (auto* device = group->GetFirstDevice(); device != nullptr;
         device = group->GetNextDevice(device)) {
      for (auto& ase : device->ases_) {
        client_parser::ascs::ase_codec_configured_state_params codec_configured_state_params;
        InjectAseStateNotification(&ase, device, group, ascs::kAseStateCodecConfigured,
                                   &codec_configured_state_params);
      }
    }
    SyncOnMainLoop();
  }

  void InjectInitialIdleAndConfiguredNotification(LeAudioDeviceGroup* group) {
    for (auto* device = group->GetFirstDevice(); device != nullptr;
         device = group->GetNextDevice(device)) {
      int i = 0;
      for (auto& ase : device->ases_) {
        if (i % 2 == 1) {
          InjectAseStateNotification(&ase, device, group, ascs::kAseStateIdle, nullptr);
        } else {
          client_parser::ascs::ase_codec_configured_state_params codec_configured_state_params;
          InjectAseStateNotification(&ase, device, group, ascs::kAseStateCodecConfigured,
                                     &codec_configured_state_params);
        }
        i++;
      }
    }
    SyncOnMainLoop();
  }

  void InjectInitialInvalidNotification(LeAudioDeviceGroup* group) {
    for (auto* device = group->GetFirstDevice(); device != nullptr;
         device = group->GetNextDevice(device)) {
      int i = 0;
      for (auto& ase : device->ases_) {
        if (i % 2 == 1) {
          client_parser::ascs::ase_qos_configured_state_params qos_configured_state_params;
          InjectAseStateNotification(&ase, device, group, ascs::kAseStateQoSConfigured,
                                     &qos_configured_state_params);
        } else {
          client_parser::ascs::ase_transient_state_params enable_params;
          InjectAseStateNotification(&ase, device, group, ascs::kAseStateEnabling, &enable_params);
        }
        i++;
      }
    }
    SyncOnMainLoop();
  }

  void DeviceContextsUpdate(LeAudioDevice* leAudioDevice, uint8_t direction,
                            types::AudioContexts contexts_available,
                            types::AudioContexts contexts_supported) {
    types::AudioContexts snk_contexts_available;
    types::AudioContexts src_contexts_available;
    types::AudioContexts snk_contexts_supported;
    types::AudioContexts src_contexts_supported;
    /* Ensure Unspecified context is supported as per spec */
    contexts_supported.set(kContextTypeUnspecified);

    if ((direction & types::kLeAudioDirectionSink) > 0) {
      snk_contexts_available = contexts_available;
      snk_contexts_supported = contexts_supported;
    } else {
      snk_contexts_available = leAudioDevice->GetAvailableContexts(types::kLeAudioDirectionSink);
      snk_contexts_supported = leAudioDevice->GetSupportedContexts(types::kLeAudioDirectionSink);
    }

    if ((direction & types::kLeAudioDirectionSource) > 0) {
      src_contexts_available = contexts_available;
      src_contexts_supported = contexts_supported;
    } else {
      src_contexts_available = leAudioDevice->GetAvailableContexts(types::kLeAudioDirectionSource);
      src_contexts_supported = leAudioDevice->GetSupportedContexts(types::kLeAudioDirectionSource);
    }

    leAudioDevice->SetSupportedContexts(
            {.sink = snk_contexts_supported, .source = src_contexts_supported});
    leAudioDevice->SetAvailableContexts(
            {.sink = snk_contexts_available, .source = src_contexts_available});

    auto group = GroupFindById(leAudioDevice->group_id_);
    if (group) {
      bool group_conf_changed = group->ReloadAudioLocations();
      group_conf_changed |= group->ReloadAudioDirections();
      if (group_conf_changed) {
        /* All the configurations should be recalculated for the new conditions */
        group->InvalidateCachedConfigurations();
        group->InvalidateGroupStrategy();
      }
    }
  }

  void DevicePacsInit(LeAudioDevice* leAudioDevice, uint8_t direction, uint8_t audio_locations,
                      types::AudioContexts contexts_available,
                      types::AudioContexts contexts_supported) {
    if ((direction & types::kLeAudioDirectionSink) > 0) {
      // Set target ASE configurations
      std::vector<types::acs_ac_record> pac_recs;

      InsertPacRecord(pac_recs, sample_freq_,
                      codec_specific::kCapFrameDuration10ms |
                              codec_specific::kCapFrameDuration7p5ms |
                              codec_specific::kCapFrameDuration10msPreferred,
                      channel_count_, 30, 120, codec_frame_blocks_per_sdu_);

      types::hdl_pair handle_pair;
      handle_pair.val_hdl = pacs_attr_handle_next++;
      handle_pair.ccc_hdl = pacs_attr_handle_next++;

      leAudioDevice->snk_pacs_.emplace_back(std::make_tuple(std::move(handle_pair), pac_recs));

      auto val_hdl = attr_handle++;
      auto ccc_hdl = attr_handle++;
      leAudioDevice->audio_locations_.sink.emplace(types::hdl_pair(val_hdl, ccc_hdl),
                                                   types::AudioLocations(audio_locations));
    }

    if ((direction & types::kLeAudioDirectionSource) > 0) {
      // Set target ASE configurations
      std::vector<types::acs_ac_record> pac_recs;

      InsertPacRecord(pac_recs,
                      codec_specific::kCapSamplingFrequency16000Hz |
                              codec_specific::kCapSamplingFrequency32000Hz,
                      codec_specific::kCapFrameDuration10ms |
                              codec_specific::kCapFrameDuration7p5ms |
                              codec_specific::kCapFrameDuration10msPreferred,
                      0b00000001, 30, 120, codec_frame_blocks_per_sdu_);

      types::hdl_pair handle_pair;
      handle_pair.val_hdl = pacs_attr_handle_next++;
      handle_pair.ccc_hdl = pacs_attr_handle_next++;

      leAudioDevice->src_pacs_.emplace_back(std::make_tuple(std::move(handle_pair), pac_recs));

      auto val_hdl = attr_handle++;
      auto ccc_hdl = attr_handle++;
      leAudioDevice->audio_locations_.source.emplace(types::hdl_pair(val_hdl, ccc_hdl),
                                                     types::AudioLocations(audio_locations));
    }

    DeviceContextsUpdate(leAudioDevice, direction, contexts_available, contexts_supported);
  }

  std::shared_ptr<LeAudioDevice> AddDeviceToExistingGroup(
          int leaudio_group_id, LeAudioContextType context_type,
          types::AudioContexts update_contexts = types::AudioContexts(),
          bool insert_default_pac_records = true) {
    auto group = GroupFindById(leaudio_group_id);
    if (!group) {
      log::error("No group_id: {}", leaudio_group_id);
      return nullptr;
    }

    if (group->Size() == group->DesiredSize()) {
      log::error("Group_id: {} is already completed", leaudio_group_id, group->Size());
      return nullptr;
    }

    // Prepare fake connected device group
    DeviceConnectState initial_connect_state = DeviceConnectState::CONNECTING_BY_USER;

    uint8_t num_ase_snk;
    uint8_t num_ase_src;
    switch (context_type) {
      case kContextTypeRingtone:
        num_ase_snk = 1 + additional_snk_ases;
        num_ase_src = 0 + additional_src_ases;
        break;

      case kContextTypeMedia:
        num_ase_snk = 2 + additional_snk_ases;
        num_ase_src = 0 + additional_src_ases;
        break;

      case kContextTypeConversational:
        num_ase_snk = 1 + additional_snk_ases;
        num_ase_src = 1 + additional_src_ases;
        break;

      case kContextTypeLive:
        num_ase_snk = 1 + additional_snk_ases;
        num_ase_src = 1 + additional_src_ases;
        break;

      default:
        log::error("Unknown context type: {}", bluetooth::common::ToString(context_type));
        return nullptr;
    }
    auto id = group->Size() + 1;
    auto leAudioDevice =
            PrepareConnectedDevice(id, initial_connect_state, num_ase_snk, num_ase_src);

    group->AddNode(leAudioDevice);
    if (!insert_default_pac_records) {
      return leAudioDevice;
    }

    // Prepare Sink Published Audio Capability records
    if ((kContextTypeRingtone | kContextTypeMedia | kContextTypeConversational | kContextTypeLive)
                .test(context_type)) {
      auto snk_context_type = update_contexts;
      snk_context_type.set(context_type);

      DevicePacsInit(leAudioDevice.get(), types::kLeAudioDirectionSink, channel_allocations_sink_,
                     snk_context_type, snk_context_type);
    }

    // Prepare Source Published Audio Capability records
    if ((context_type == kContextTypeConversational) || (context_type == kContextTypeLive)) {
      auto src_context_type = update_contexts;
      src_context_type.set(context_type);

      DevicePacsInit(leAudioDevice.get(), types::kLeAudioDirectionSource,
                     channel_allocations_source_, src_context_type, src_context_type);
    }

    group->UpdateAudioSetConfigurationCache(context_type);
    return leAudioDevice;
  }

  void MultipleTestDevicePrepare(int leaudio_group_id, LeAudioContextType context_type,
                                 const uint16_t total_devices, types::AudioContexts update_contexts,
                                 bool insert_default_pac_records = true,
                                 bool second_device_0_ases = false) {
    // Prepare fake connected device group
    DeviceConnectState initial_connect_state = DeviceConnectState::CONNECTING_BY_USER;

    uint8_t num_ase_snk;
    uint8_t num_ase_src;
    switch (context_type) {
      case kContextTypeRingtone:
        num_ase_snk = 1 + additional_snk_ases;
        num_ase_src = 0 + additional_src_ases;
        break;

      case kContextTypeMedia:
        num_ase_snk = 2 + additional_snk_ases;
        num_ase_src = 0 + additional_src_ases;
        break;

      case kContextTypeConversational:
        num_ase_snk = 1 + additional_snk_ases;
        num_ase_src = 1 + additional_src_ases;
        break;

      case kContextTypeLive:
        num_ase_snk = 1 + additional_snk_ases;
        num_ase_src = 1 + additional_src_ases;
        break;

      default:
        ASSERT_TRUE(false);
    }

    for (uint8_t device_cnt = 0; device_cnt < total_devices; device_cnt++) {
      std::shared_ptr<LeAudioDevice> leAudioDevice;
      bluetooth::le_audio::LeAudioDeviceGroup* group;

      if (device_cnt == 1 && second_device_0_ases == true) {
        leAudioDevice = PrepareConnectedDevice(device_cnt, initial_connect_state, 0, 0);
      } else {
        leAudioDevice =
                PrepareConnectedDevice(device_cnt, initial_connect_state, num_ase_snk, num_ase_src);
      }

      group = GroupTheDevice(leaudio_group_id, std::move(leAudioDevice));
      ASSERT_NE(group, nullptr);
      ASSERT_EQ(group->Size(), device_cnt + 1);

      if (insert_default_pac_records) {
        // Prepare Sink Published Audio Capability records
        if ((kContextTypeRingtone | kContextTypeMedia | kContextTypeConversational |
             kContextTypeLive)
                    .test(context_type)) {
          auto snk_context_type = update_contexts;
          snk_context_type.set(context_type);

          DevicePacsInit(leAudioDevice.get(), types::kLeAudioDirectionSink,
                         channel_allocations_sink_, snk_context_type, snk_context_type);
        }

        // Prepare Source Published Audio Capability records
        if ((context_type == kContextTypeConversational) || (context_type == kContextTypeLive)) {
          auto src_context_type = update_contexts;
          src_context_type.set(context_type);

          DevicePacsInit(leAudioDevice.get(), types::kLeAudioDirectionSource,
                         channel_allocations_source_, src_context_type, src_context_type);
        }
      }
    }

    auto group = GroupFindById(leaudio_group_id);
    ASSERT_NE(group, nullptr);

    group->UpdateAudioSetConfigurationCache(context_type);
    ASSERT_EQ(group->Size(), total_devices);
  }

  LeAudioDeviceGroup* PrepareSingleTestDeviceGroup(
          int leaudio_group_id, LeAudioContextType context_type, uint16_t device_cnt = 1,
          types::AudioContexts update_contexts = types::AudioContexts(),
          bool second_device_0_ases = false) {
    MultipleTestDevicePrepare(leaudio_group_id, context_type, device_cnt, update_contexts, true,
                              second_device_0_ases);
    SyncOnMainLoop();
    return le_audio_device_groups_.count(leaudio_group_id)
                   ? le_audio_device_groups_[leaudio_group_id].get()
                   : nullptr;
  }

  void ClearCodecConfigureCodecHandler(void) {
    ON_CALL(ase_ctp_handler, AseCtpConfigureCodecHandler)
            .WillByDefault(Invoke([](LeAudioDevice* /*device*/, std::vector<uint8_t> /*value*/,
                                     GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              log::info("Codec Configured Handler is empty");
            }));
  }

  void PrepareConfigureCodecHandler(LeAudioDeviceGroup* group, int verify_ase_count = 0,
                                    bool caching = false, bool inject_configured = true) {
    ON_CALL(ase_ctp_handler, AseCtpConfigureCodecHandler)
            .WillByDefault(Invoke([group, verify_ase_count, caching, inject_configured, this](
                                          LeAudioDevice* device, std::vector<uint8_t> value,
                                          GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }

              // Inject Configured ASE state notification for each requested ASE
              auto* ase_p = &value[2];
              for (auto i = 0u; i < num_ase; ++i) {
                client_parser::ascs::ase_codec_configured_state_params
                        codec_configured_state_params;

                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());
                const auto ase = &(*it);

                ASSERT_EQ(ase->expected_state,
                          types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

                std::vector<std::pair<RawAddress, test_presentation_delay_t>>
                        directional_test_delays_vec;
                std::vector<std::pair<RawAddress, uint16_t>> directional_test_max_tl_vec;

                if (ase->direction == types::kLeAudioDirectionSink) {
                  directional_test_delays_vec = test_remote_sink_presentation_delay_vec_;
                  directional_test_max_tl_vec = test_remote_sink_max_transport_latency_vec_;
                } else {
                  directional_test_delays_vec = test_remote_source_presentation_delay_vec_;
                  directional_test_max_tl_vec = test_remote_source_max_transport_latency_vec_;
                }

                // Skip target latency param
                ase_p++;

                codec_configured_state_params.preferred_phy = *ase_p++;
                codec_configured_state_params.codec_id.coding_format = ase_p[0];
                codec_configured_state_params.codec_id.vendor_company_id =
                        (uint16_t)(ase_p[1] << 8 | ase_p[2]),
                codec_configured_state_params.codec_id.vendor_codec_id =
                        (uint16_t)(ase_p[3] << 8 | ase_p[4]),
                ase_p += 5;

                auto codec_spec_param_len = *ase_p++;
                auto num_handled_bytes = ase_p - value.data();
                codec_configured_state_params.codec_spec_conf = std::vector<uint8_t>(
                        value.begin() + num_handled_bytes,
                        value.begin() + num_handled_bytes + codec_spec_param_len);
                ase_p += codec_spec_param_len;

                // Some initial QoS settings
                codec_configured_state_params.framing = ascs::kAseParamFramingUnframedSupported;
                codec_configured_state_params.preferred_retrans_nb = 0x04;

                // Set test max tl
                codec_configured_state_params.max_transport_latency = 0;
                uint16_t default_max_tl = 0x0020;
                int test_common_max_tl = -1;
                for (auto [addr, max_tl] : directional_test_max_tl_vec) {
                  if (addr == device->address_) {
                    codec_configured_state_params.max_transport_latency = max_tl;
                    break;
                  }
                  if (addr == RawAddress::kEmpty) {
                    test_common_max_tl = max_tl;
                  }
                }
                if (codec_configured_state_params.max_transport_latency == 0) {
                  codec_configured_state_params.max_transport_latency =
                          test_common_max_tl < 0 ? default_max_tl : test_common_max_tl;
                }

                // Set test presentation delays
                test_presentation_delay_t default_delays = {0xABABAB, 0xCDCDCD,
                                                            types::kPresDelayNoPreference,
                                                            types::kPresDelayNoPreference};
                test_presentation_delay_t* test_common_presentation_delays = nullptr;
                test_presentation_delay_t* used_presentation_delays = nullptr;

                for (auto [addr, delays] : directional_test_delays_vec) {
                  if (addr == device->address_) {
                    used_presentation_delays = &delays;
                    break;
                  }
                  if (addr == RawAddress::kEmpty) {
                    test_common_presentation_delays = &delays;
                  }
                }

                if (!used_presentation_delays) {
                  /* Use either common test data of the default one. */
                  used_presentation_delays = test_common_presentation_delays
                                                     ? test_common_presentation_delays
                                                     : &default_delays;
                }

                codec_configured_state_params.pres_delay_min = used_presentation_delays->min;
                codec_configured_state_params.pres_delay_max = used_presentation_delays->max;
                codec_configured_state_params.preferred_pres_delay_min =
                        used_presentation_delays->pref_min;
                codec_configured_state_params.preferred_pres_delay_max =
                        used_presentation_delays->pref_max;

                if (caching) {
                  cached_codec_configuration_map_[ase_id] = codec_configured_state_params;
                }

                InjectCtpNotification(group, device, value);

                if (inject_configured) {
                  InjectAseStateNotification(ase, device, group, ascs::kAseStateCodecConfigured,
                                             &codec_configured_state_params);
                }

                if (stop_inject_configured_ase_after_first_ase_configured_) {
                  return;
                }
              }
            }));
  }

  void PrepareConfigureQosHandler(LeAudioDeviceGroup* group, int verify_ase_count = 0,
                                  bool caching = false, bool inject_qos_configured = true) {
    ON_CALL(ase_ctp_handler, AseCtpConfigureQosHandler)
            .WillByDefault(Invoke([group, verify_ase_count, caching, inject_qos_configured, this](
                                          LeAudioDevice* device, std::vector<uint8_t> value,
                                          GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              InjectCtpNotification(group, device, value);
              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }

              // Inject Configured QoS state notification for each requested ASE
              auto* ase_p = &value[2];
              std::vector<struct types::ase*> ases;
              std::vector<client_parser::ascs::ase_qos_configured_state_params>
                      qos_configured_state_params_vec;
              for (auto i = 0u; i < num_ase; ++i) {
                client_parser::ascs::ase_qos_configured_state_params qos_configured_state_params;

                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());
                const auto ase = &(*it);

                qos_configured_state_params.cig_id = *ase_p++;
                qos_configured_state_params.cis_id = *ase_p++;

                qos_configured_state_params.sdu_interval =
                        (uint32_t)((ase_p[0] << 16) | (ase_p[1] << 8) | ase_p[2]);
                ase_p += 3;

                qos_configured_state_params.framing = *ase_p++;
                qos_configured_state_params.phy = *ase_p++;
                qos_configured_state_params.max_sdu = (uint16_t)((ase_p[0] << 8) | ase_p[1]);
                ase_p += 2;

                qos_configured_state_params.retrans_nb = *ase_p++;
                qos_configured_state_params.max_transport_latency =
                        (uint16_t)((ase_p[0] << 8) | ase_p[1]);
                ase_p += 2;

                qos_configured_state_params.pres_delay =
                        (uint16_t)((ase_p[0] << 16) | (ase_p[1] << 8) | ase_p[2]);
                ase_p += 3;
                ASSERT_EQ(ase->expected_state,
                          types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
                ases.push_back(ase);

                if (caching) {
                  log::info("Device: {}", device->address_);
                  if (cached_ase_to_cis_id_map_.count(device->address_) > 0) {
                    auto ase_list = cached_ase_to_cis_id_map_.at(device->address_);
                    if (ase_list.count(ase_id) > 0) {
                      auto cis_id = ase_list.at(ase->id);
                      ASSERT_EQ(cis_id, qos_configured_state_params.cis_id);
                    } else {
                      ase_list[ase_id] = qos_configured_state_params.cis_id;
                    }
                  } else {
                    std::map<int, int> ase_map;
                    ase_map[ase_id] = qos_configured_state_params.cis_id;

                    cached_ase_to_cis_id_map_[device->address_] = ase_map;
                  }
                  cached_qos_configuration_map_[ase_id] = qos_configured_state_params;
                }

                qos_configured_state_params_vec.push_back(qos_configured_state_params);
              }

              for (int i = 0; i < static_cast<int>(ases.size()); i++) {
                if (inject_qos_configured) {
                  InjectAseStateNotification(ases[i], device, group, ascs::kAseStateQoSConfigured,
                                             &qos_configured_state_params_vec[i]);
                }
              }
            }));
  }

  void InjectCtpNotification(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                             std::vector<uint8_t>& ctp_command, uint8_t response_code = 0x00,
                             uint8_t reason = 0x00) {
    auto opcode = ctp_command[0];
    auto num_ase = ctp_command[1];
    std::vector<uint8_t> notif_value(2 +
                                     num_ase * sizeof(struct client_parser::ascs::ctp_ase_entry));
    auto* p = notif_value.data();

    UINT8_TO_STREAM(p, opcode);
    UINT8_TO_STREAM(p, num_ase);

    auto* ase_p = &ctp_command[2];
    for (auto i = 0u; i < num_ase; ++i) {
      /* Check if this is a valid ASE ID  */
      auto ase_id = *ase_p++;

      /* Do additional verification with the device ASE only when opcode is different than codec
       * config. This is because, device will get ASE id when Codec Configured Notification arrives.
       */
      if (opcode != client_parser::ascs::kCtpOpcodeCodecConfiguration) {
        auto it = std::find_if(leAudioDevice->ases_.begin(), leAudioDevice->ases_.end(),
                               [ase_id](auto& ase) { return ase.id == ase_id; });
        ASSERT_NE(it, leAudioDevice->ases_.end());
      }

      switch (opcode) {
        case client_parser::ascs::kCtpOpcodeCodecConfiguration: {
          ase_p += 7;
          auto codec_spec_len = *ase_p++;
          ase_p += codec_spec_len;
        } break;
        case client_parser::ascs::kCtpOpcodeQosConfiguration:
          ase_p += 15;
          break;
        case client_parser::ascs::kCtpOpcodeUpdateMetadata:
        case client_parser::ascs::kCtpOpcodeEnable: {
          auto meta_len = *ase_p++;
          ase_p += meta_len;
        } break;
        case client_parser::ascs::kCtpOpcodeReceiverStartReady:
        case client_parser::ascs::kCtpOpcodeDisable:
        case client_parser::ascs::kCtpOpcodeReceiverStopReady:
        case client_parser::ascs::kCtpOpcodeRelease:
        default:
          break;
      }

      // Inject error response
      UINT8_TO_STREAM(p, ase_id);
      UINT8_TO_STREAM(p, response_code);
      UINT8_TO_STREAM(p, reason);
    }
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice, std::vector<uint8_t> value) {
              state_machine->ProcessGattCtpNotification(group, leAudioDevice, value.data(),
                                                        value.size());
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, std::move(notif_value)));
  }

  void PrepareCtpNotificationError(LeAudioDeviceGroup* group, uint8_t opcode, uint8_t response_code,
                                   uint8_t reason) {
    auto foo = [group, response_code, reason, this](LeAudioDevice* device,
                                                    std::vector<uint8_t> value,
                                                    GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
      InjectCtpNotification(group, device, value, response_code, reason);
    };

    switch (opcode) {
      case client_parser::ascs::kCtpOpcodeCodecConfiguration:
        ON_CALL(ase_ctp_handler, AseCtpConfigureCodecHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeQosConfiguration:
        ON_CALL(ase_ctp_handler, AseCtpConfigureQosHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeEnable:
        ON_CALL(ase_ctp_handler, AseCtpEnableHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeReceiverStartReady:
        ON_CALL(ase_ctp_handler, AseCtpReceiverStartReadyHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeDisable:
        ON_CALL(ase_ctp_handler, AseCtpDisableHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeReceiverStopReady:
        ON_CALL(ase_ctp_handler, AseCtpReceiverStopReadyHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeUpdateMetadata:
        ON_CALL(ase_ctp_handler, AseCtpUpdateMetadataHandler).WillByDefault(Invoke(foo));
        break;
      case client_parser::ascs::kCtpOpcodeRelease:
        ON_CALL(ase_ctp_handler, AseCtpReleaseHandler).WillByDefault(Invoke(foo));
        break;
      default:
        break;
    };
  }

  void PrepareEnableHandler(
          LeAudioDeviceGroup* group, int verify_ase_count = 0, bool inject_enabling = true,
          bool incject_streaming = true,
          types::AudioContexts streaming_context_to_verify = types::AudioContexts()) {
    ON_CALL(ase_ctp_handler, AseCtpEnableHandler)
            .WillByDefault(Invoke([group, verify_ase_count, inject_enabling, incject_streaming,
                                   streaming_context_to_verify,
                                   this](LeAudioDevice* device, std::vector<uint8_t> value,
                                         GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              InjectCtpNotification(group, device, value);

              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }

              // Inject Streaming ASE state notification for each requested ASE
              auto* ase_p = &value[2];
              std::vector<struct types::ase*> ases;
              std::vector<client_parser::ascs::ase_transient_state_params> enable_params_vec;
              for (auto i = 0u; i < num_ase; ++i) {
                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());
                const auto ase = &(*it);
                ases.push_back(ase);

                ASSERT_EQ(ase->expected_state, types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);
                auto meta_len = *ase_p++;
                auto num_handled_bytes = ase_p - value.data();
                ase_p += meta_len;

                client_parser::ascs::ase_transient_state_params enable_params = {
                        .metadata =
                                std::vector<uint8_t>(value.begin() + num_handled_bytes,
                                                     value.begin() + num_handled_bytes + meta_len)};

                enable_params_vec.push_back(enable_params);
                if (streaming_context_to_verify.any()) {
                  types::LeAudioLtvMap meta;
                  meta.Parse(enable_params.metadata.data(), enable_params.metadata.size());

                  auto received_streaming_contexts =
                          meta.GetAsLeAudioMetadata().streaming_audio_context;
                  ASSERT_TRUE(received_streaming_contexts == streaming_context_to_verify);
                }
              }

              for (int i = 0; i < static_cast<int>(ases.size()); i++) {
                // Server does the 'ReceiverStartReady' on its own - goes to
                // Streaming, when in Sink role
                auto ase = ases[i];
                auto enable_params = enable_params_vec[i];

                if (ase->direction & bluetooth::le_audio::types::kLeAudioDirectionSink) {
                  if (inject_enabling) {
                    InjectAseStateNotification(ase, device, group, ascs::kAseStateEnabling,
                                               &enable_params);
                  }
                  if (incject_streaming) {
                    InjectAseStateNotification(ase, device, group, ascs::kAseStateStreaming,
                                               &enable_params);
                  }
                } else {
                  if (inject_enabling) {
                    InjectAseStateNotification(ase, device, group, ascs::kAseStateEnabling,
                                               &enable_params);
                  }
                }
              }
            }));
  }

  void PrepareEnableHandlerWithMetadataverify(LeAudioDeviceGroup* group,
                                              types::AudioContexts streaming_context_to_verify) {
    PrepareEnableHandler(group, 0, true, true, streaming_context_to_verify);
  }

  void PrepareDisableHandler(LeAudioDeviceGroup* group, int verify_ase_count = 0) {
    ON_CALL(ase_ctp_handler, AseCtpDisableHandler)
            .WillByDefault(Invoke([group, verify_ase_count, this](
                                          LeAudioDevice* device, std::vector<uint8_t> value,
                                          GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              InjectCtpNotification(group, device, value);
              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }
              ASSERT_EQ(value.size(), 2ul + num_ase);

              // Inject Disabling & QoS Conf. ASE state notification for each ASE
              auto* ase_p = &value[2];
              std::vector<struct types::ase*> ases;
              for (auto i = 0u; i < num_ase; ++i) {
                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());
                const auto ase = &(*it);

                ASSERT_EQ(ase->expected_state, types::AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING);
                ases.push_back(ase);
              }

              for (auto ase : ases) {
                // The Disabling state is present for Source ASE
                if (ase->direction & bluetooth::le_audio::types::kLeAudioDirectionSource) {
                  client_parser::ascs::ase_transient_state_params disabling_params = {
                          .metadata = {}};
                  InjectAseStateNotification(ase, device, group, ascs::kAseStateDisabling,
                                             &disabling_params);
                }

                // Server does the 'ReceiverStopReady' on its own - goes to
                // Streaming, when in Sink role
                if (ase->direction & bluetooth::le_audio::types::kLeAudioDirectionSink) {
                  // FIXME: For now our fake peer does not remember qos params
                  client_parser::ascs::ase_qos_configured_state_params qos_configured_state_params;
                  InjectAseStateNotification(ase, device, group, ascs::kAseStateQoSConfigured,
                                             &qos_configured_state_params);
                }
              }
            }));
  }

  void PrepareReceiverStartReadyHandler(LeAudioDeviceGroup* group, int verify_ase_count = 0) {
    ON_CALL(ase_ctp_handler, AseCtpReceiverStartReadyHandler)
            .WillByDefault(Invoke([group, verify_ase_count, this](
                                          LeAudioDevice* device, std::vector<uint8_t> value,
                                          GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              InjectCtpNotification(group, device, value);
              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }

              // Inject Streaming ASE state notification for each Source ASE
              auto* ase_p = &value[2];
              std::vector<struct types::ase*> ases;
              for (auto i = 0u; i < num_ase; ++i) {
                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());

                // Once we did the 'ReceiverStartReady' the server goes to
                // Streaming, when in Source role
                const auto& ase = &(*it);

                ASSERT_EQ(ase->expected_state, types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
                ases.push_back(ase);
              }

              for (auto ase : ases) {
                client_parser::ascs::ase_transient_state_params streaming_params = {
                        .metadata = ase->metadata.RawPacket()};
                InjectAseStateNotification(ase, device, group, ascs::kAseStateStreaming,
                                           &streaming_params);
              }
            }));
  }

  void PrepareReceiverStopReady(LeAudioDeviceGroup* group, int verify_ase_count = 0) {
    ON_CALL(ase_ctp_handler, AseCtpReceiverStopReadyHandler)
            .WillByDefault(Invoke([group, verify_ase_count, this](
                                          LeAudioDevice* device, std::vector<uint8_t> value,
                                          GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              InjectCtpNotification(group, device, value);
              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }

              // Inject QoS configured ASE state notification for each Source
              // ASE
              auto* ase_p = &value[2];
              std::vector<struct types::ase*> ases;
              for (auto i = 0u; i < num_ase; ++i) {
                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());

                const auto& ase = &(*it);
                ASSERT_EQ(ase->expected_state,
                          types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
                ases.push_back(ase);
              }

              for (auto ase : ases) {
                // FIXME: For now our fake peer does not remember qos params
                client_parser::ascs::ase_qos_configured_state_params qos_configured_state_params;
                InjectAseStateNotification(ase, device, group, ascs::kAseStateQoSConfigured,
                                           &qos_configured_state_params);
              }
            }));
  }

  void PrepareReleaseHandler(LeAudioDeviceGroup* group, int verify_ase_count = 0,
                             bool inject_disconnect_device = false, LeAudioDevice* dev = nullptr,
                             bool inject_releasing = true) {
    ON_CALL(ase_ctp_handler, AseCtpReleaseHandler)
            .WillByDefault(Invoke([group, verify_ase_count, inject_disconnect_device, dev,
                                   inject_releasing,
                                   this](LeAudioDevice* device, std::vector<uint8_t> value,
                                         GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
              if (dev != nullptr && device != dev) {
                log::info("Do nothing for {}", dev->address_);
                return;
              }
              InjectCtpNotification(group, device, value);
              auto num_ase = value[1];

              // Verify ase count if needed
              if (verify_ase_count) {
                ASSERT_EQ(verify_ase_count, num_ase);
              }
              ASSERT_EQ(value.size(), 2ul + num_ase);

              if (inject_disconnect_device) {
                InjectAclDisconnected_andWait(group, device);
                return;
              }

              // Inject Releasing & Idle ASE state notification for each ASE
              auto* ase_p = &value[2];
              std::vector<struct types::ase*> ases;

              // Find ASES and check state
              for (auto i = 0u; i < num_ase; ++i) {
                /* Check if this is a valid ASE ID  */
                auto ase_id = *ase_p++;
                auto it = std::find_if(device->ases_.begin(), device->ases_.end(),
                                       [ase_id](auto& ase) { return ase.id == ase_id; });
                ASSERT_NE(it, device->ases_.end());
                const auto ase = &(*it);

                ASSERT_EQ(ase->expected_state, types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
                ases.push_back(ase);
              }

              // execute operation
              for (auto ase : ases) {
                // Prevent RELEASING notification for the whole group
                if (!inject_releasing) {
                  continue;
                }

                // Prevent RELEASING notification for single devices in the group
                auto iter = std::find(block_releasing_state_device_list_.begin(),
                                      block_releasing_state_device_list_.end(), device->address_);
                if (iter != block_releasing_state_device_list_.end()) {
                  continue;
                }

                InjectAseStateNotification(ase, device, group, ascs::kAseStateReleasing, nullptr);

                if (stay_in_releasing_state_) {
                  continue;
                }

                /* Check if codec configuration is cached */
                if (cached_codec_configuration_map_.count(ase->id) > 0) {
                  InjectAseStateNotification(ase, device, group, ascs::kAseStateCodecConfigured,
                                             &cached_codec_configuration_map_[ase->id]);
                } else {
                  // Release - no caching
                  InjectAseStateNotification(ase, device, group, ascs::kAseStateIdle, nullptr);
                }
              }
            }));
  }

  void StartStream_onMainloop(
          LeAudioDeviceGroup* group, LeAudioContextType context_type,
          const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
          types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {}, .source = {}}) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioContextType context_type,
               const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
               types::BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
              state_machine->StartStream(group, context_type, metadata_context_types, ccid_lists);
            },
            LeAudioGroupStateMachine::Get(), group, context_type, std::move(metadata_context_types),
            std::move(ccid_lists)));
    SyncOnMainLoop();
  }

  void AttachToStream_onMainloop(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                 types::BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice,
               types::BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
              state_machine->AttachToStream(group, leAudioDevice, ccid_lists);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, std::move(ccid_lists)));

    SyncOnMainLoop();
  }

  void ConfigureStream_onMainloop(
          LeAudioDeviceGroup* group, LeAudioContextType context_type,
          const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
          types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {}, .source = {}},
          bool configure_qos = false) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioContextType context_type,
               const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
               types::BidirectionalPair<std::vector<uint8_t>> ccid_lists, bool configure_qos) {
              state_machine->ConfigureStream(group, context_type, metadata_context_types,
                                             ccid_lists, configure_qos);
            },
            LeAudioGroupStateMachine::Get(), group, context_type, std::move(metadata_context_types),
            std::move(ccid_lists), configure_qos));
    SyncOnMainLoop();
  }

  void StopStream_onMainloop(LeAudioDeviceGroup* group) {
    do_in_main_thread(
            base::BindOnce([](LeAudioGroupStateMachine* state_machine,
                              LeAudioDeviceGroup* group) { state_machine->StopStream(group); },
                           LeAudioGroupStateMachine::Get(), group));
    SyncOnMainLoop();
  }

  void SuspendStream_onMainloop(LeAudioDeviceGroup* group) {
    do_in_main_thread(
            base::BindOnce([](LeAudioGroupStateMachine* state_machine,
                              LeAudioDeviceGroup* group) { state_machine->SuspendStream(group); },
                           LeAudioGroupStateMachine::Get(), group));
    SyncOnMainLoop();
  }

  void EnableStreamDirection_onMainloop(LeAudioDeviceGroup* group, uint8_t direction) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               uint8_t direction) { state_machine->EnableStreamingDirection(group, direction); },
            LeAudioGroupStateMachine::Get(), group, direction));
    SyncOnMainLoop();
  }

  void DisableStreamDirection_onMainloop(LeAudioDeviceGroup* group, uint8_t direction) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               uint8_t direction) { state_machine->DisableStreamingDirection(group, direction); },
            LeAudioGroupStateMachine::Get(), group, direction));
    SyncOnMainLoop();
  }

  void InjectHciNotifyRemoveIsoDataPath(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                        int status, uint16_t conn_handle) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice, int status, uint16_t conn_handle) {
              state_machine->ProcessHciNotifRemoveIsoDataPath(group, leAudioDevice, status,
                                                              conn_handle);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, status, conn_handle));
  }

  void InjectHciNotifySetupIsoDataPath(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                       int status, uint16_t conn_handle) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice, int status, uint16_t conn_handle) {
              state_machine->ProcessHciNotifSetupIsoDataPath(group, leAudioDevice, status,
                                                             conn_handle);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, status, conn_handle));
  }

  void InjectHciNotifyCisDisconnected(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                      bluetooth::hci::iso_manager::cis_disconnected_evt evt) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice,
               bluetooth::hci::iso_manager::cis_disconnected_evt evt) {
              state_machine->ProcessHciNotifCisDisconnected(group, leAudioDevice, &evt);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, std::move(evt)));
  }

  void InjectCisDisconnected_andWait(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                     uint8_t reason, bool first_cis_disconnect_only = false) {
    bluetooth::hci::iso_manager::cis_disconnected_evt event;

    std::set<uint16_t> disconnected_cis;

    for (auto const& ase : leAudioDevice->ases_) {
      if (ase.cis_state != types::CisState::ASSIGNED && ase.cis_state != types::CisState::IDLE) {
        event.reason = reason;
        event.cig_id = group->group_id_;
        event.cis_conn_hdl = ase.cis_conn_hdl;
        if (disconnected_cis.contains(ase.cis_conn_hdl)) {
          log::debug("{}, cis_handle: {:#x} already disconnected", leAudioDevice->address_,
                     ase.cis_conn_hdl);
          continue;
        }
        disconnected_cis.insert(ase.cis_conn_hdl);
        InjectHciNotifyCisDisconnected(group, leAudioDevice, event);
        if (first_cis_disconnect_only) {
          break;
        }
      }
    }
    SyncOnMainLoop();
  }

  void InjectHciNotifyCisEstablished(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                     bluetooth::hci::iso_manager::cis_establish_cmpl_evt evt) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice,
               bluetooth::hci::iso_manager::cis_establish_cmpl_evt evt) {
              state_machine->ProcessHciNotifCisEstablished(group, leAudioDevice, &evt);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, std::move(evt)));
  }

  void InjectHciNotifyCigCreate(LeAudioDeviceGroup* group, int status, int cig_id,
                                std::vector<uint16_t> conn_handles) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group, int status,
               int cig_id, std::vector<uint16_t> conn_handles) {
              state_machine->ProcessHciNotifOnCigCreate(group, status, cig_id, conn_handles);
            },
            LeAudioGroupStateMachine::Get(), group, status, cig_id, std::move(conn_handles)));
  }

  void InjectGattNotifEvent(std::vector<uint8_t> value, struct types::ase* ase,
                            LeAudioDevice* leAudioDevice, LeAudioDeviceGroup* group) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice, struct types::ase* ase, std::vector<uint8_t> value) {
              state_machine->ProcessGattNotifEvent(value.data(), value.size(), ase, leAudioDevice,
                                                   group);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice, ase, std::move(value)));
  }

  void InjectHciNotifyAclDisconnected_andWait(LeAudioDeviceGroup* group,
                                              LeAudioDevice* leAudioDevice) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioGroupStateMachine* state_machine, LeAudioDeviceGroup* group,
               LeAudioDevice* leAudioDevice) {
              state_machine->ProcessHciNotifAclDisconnected(group, leAudioDevice);
            },
            LeAudioGroupStateMachine::Get(), group, leAudioDevice));
    SyncOnMainLoop();
  }

  MockCsisClient mock_csis_client_module_;
  NiceMock<MockBtmClientInterface> btm_interface;
  gatt::MockBtaGattInterface gatt_interface;
  gatt::MockBtaGattQueue gatt_queue;

  bluetooth::hci::IsoManager* iso_manager_;
  bluetooth::hci::iso_manager::cig_create_params last_cig_params_;
  bluetooth::hci::iso_manager::iso_data_path_params last_datapath_params_;
  MockIsoManager* mock_iso_manager_;
  bluetooth::le_audio::CodecManager* codec_manager_;
  MockCodecManager* mock_codec_manager_;

  MockAseRemoteStateMachine ase_ctp_handler;
  std::map<int, client_parser::ascs::ase_codec_configured_state_params>
          cached_codec_configuration_map_;
  std::map<int, client_parser::ascs::ase_qos_configured_state_params> cached_qos_configuration_map_;

  std::map<RawAddress, std::map<int, int>> cached_ase_to_cis_id_map_;
  std::map<types::ase*, std::vector<uint8_t>> cached_remote_qos_configuration_for_ase_;

  MockLeAudioGroupStateMachineCallbacks mock_callbacks_;
  std::vector<std::shared_ptr<LeAudioDevice>> le_audio_devices_;
  std::vector<RawAddress> addresses_;
  std::map<uint8_t, std::unique_ptr<LeAudioDeviceGroup>> le_audio_device_groups_;
  bool group_create_command_disallowed_ = false;
};

class StateMachineTest : public StateMachineTestBase {
  void SetUp() override {
    ConfigCodecManagerMock(types::CodecLocation::HOST);
    ::bluetooth::le_audio::AudioSetConfigurationProvider::Initialize(
            ::bluetooth::le_audio::types::CodecLocation::HOST);
    StateMachineTestBase::SetUp();
  }
};

class StateMachineTestNoSwb : public StateMachineTestBase {
  void SetUp() override {
    ConfigCodecManagerMock(types::CodecLocation::HOST);
    ::bluetooth::le_audio::AudioSetConfigurationProvider::Initialize(
            ::bluetooth::le_audio::types::CodecLocation::HOST);
    ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(false));
    StateMachineTestBase::SetUp();
  }
};

class StateMachineTestAdsp : public StateMachineTestBase {
  void SetUp() override {
    ConfigCodecManagerMock(types::CodecLocation::ADSP);
    ::bluetooth::le_audio::AudioSetConfigurationProvider::Initialize(
            ::bluetooth::le_audio::types::CodecLocation::ADSP);
    StateMachineTestBase::SetUp();
  }
};

TEST_F(StateMachineTest, testInit) { ASSERT_NE(LeAudioGroupStateMachine::Get(), nullptr); }

TEST_F(StateMachineTest, testCleanup) {
  ASSERT_NE(LeAudioGroupStateMachine::Get(), nullptr);
  LeAudioGroupStateMachine::Cleanup();
  EXPECT_DEATH(LeAudioGroupStateMachine::Get(), "");
}

TEST_F(StateMachineTest, testConfigureCodecSingle) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 2;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  auto* leAudioDevice = group->GetFirstDevice();
  PrepareConfigureCodecHandler(group, 1);

  /* Start the configuration and stream Media content.
   * Expect 1 time for the Codec Config call only. */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  /* Do nothing on the CigCreate, so the state machine stays in the configure
   * state */
  ON_CALL(*mock_iso_manager_, CreateCig).WillByDefault(Return());
  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);

  InjectInitialIdleNotification(group);

  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  /* Cancel is called when group goes to streaming. */
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testConfigureCodecSingleFb2) {
  codec_frame_blocks_per_sdu_ = 2;
  bool is_fb2_passed_as_sink_requirement = false;
  bool is_fb2_passed_as_source_requirement = false;

  ON_CALL(*mock_codec_manager_, GetCodecConfig)
          .WillByDefault(Invoke([&](const bluetooth::le_audio::CodecManager::
                                            UnicastConfigurationRequirements& requirements,
                                    bluetooth::le_audio::CodecManager::UnicastConfigurationProvider
                                            provider) {
            auto configs =
                    *bluetooth::le_audio::AudioSetConfigurationProvider::Get()->GetConfigurations(
                            requirements.audio_context_type);
            // Note: This dual bidir SWB exclusion logic has to match the
            // CodecManager::GetCodecConfig() implementation.
            if (!CodecManager::GetInstance()->IsDualBiDirSwbSupported()) {
              configs.erase(std::remove_if(configs.begin(), configs.end(),
                                           [](auto const& el) {
                                             if (el->confs.source.empty()) {
                                               return false;
                                             }
                                             return AudioSetConfigurationProvider::Get()
                                                     ->CheckConfigurationIsDualBiDirSwb(*el);
                                           }),
                            configs.end());
            }

            auto cfg = provider(requirements, &configs);
            if (cfg == nullptr) {
              return std::unique_ptr<bluetooth::le_audio::types::AudioSetConfiguration>(nullptr);
            }

            if (requirements.sink_pacs.has_value()) {
              for (auto const& rec : requirements.sink_pacs.value()) {
                auto caps = rec.codec_spec_caps.GetAsCoreCodecCapabilities();
                if (caps.HasSupportedMaxCodecFramesPerSdu()) {
                  if (caps.supported_max_codec_frames_per_sdu.value() ==
                      codec_frame_blocks_per_sdu_) {
                    // Scale by Codec Frames Per SDU = 2
                    for (auto& entry : cfg->confs.sink) {
                      entry.codec.params.Add(codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                                             (uint8_t)codec_frame_blocks_per_sdu_);
                      entry.qos.maxSdu *= codec_frame_blocks_per_sdu_;
                      entry.qos.sduIntervalUs *= codec_frame_blocks_per_sdu_;
                      entry.qos.max_transport_latency *= codec_frame_blocks_per_sdu_;
                    }
                    is_fb2_passed_as_sink_requirement = true;
                  }
                }
              }
            }
            if (requirements.source_pacs.has_value()) {
              for (auto const& rec : requirements.source_pacs.value()) {
                auto caps = rec.codec_spec_caps.GetAsCoreCodecCapabilities();
                if (caps.HasSupportedMaxCodecFramesPerSdu()) {
                  if (caps.supported_max_codec_frames_per_sdu.value() ==
                      codec_frame_blocks_per_sdu_) {
                    // Scale by Codec Frames Per SDU = 2
                    for (auto& entry : cfg->confs.source) {
                      entry.codec.params.Add(codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                                             (uint8_t)codec_frame_blocks_per_sdu_);
                      entry.qos.maxSdu *= codec_frame_blocks_per_sdu_;
                      entry.qos.sduIntervalUs *= codec_frame_blocks_per_sdu_;
                      entry.qos.max_transport_latency *= codec_frame_blocks_per_sdu_;
                    }
                    is_fb2_passed_as_source_requirement = true;
                  }
                }
              }
            }

            return cfg;
          }));

  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 2;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  /* Prepare the fake connected device group */
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  auto* leAudioDevice = group->GetFirstDevice();
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);

  /* Start the configuration and stream Media content.
   * Expect 3 times: for Codec Configure & QoS Configure & Enable */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  /* Check if group has transitioned to a proper state */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  /* Cancel is called when group goes to streaming. */
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  ASSERT_TRUE(is_fb2_passed_as_sink_requirement);

  /* Make sure that data interval is based on the codec frame blocks count */
  auto data_interval = group->GetActiveConfiguration()->confs.sink.at(0).codec.GetDataIntervalUs();
  ASSERT_EQ(data_interval, group->GetActiveConfiguration()
                                           ->confs.sink.at(0)
                                           .codec.params.GetAsCoreCodecConfig()
                                           .GetFrameDurationUs() *
                                   codec_frame_blocks_per_sdu_);

  /* Verify CIG parameters */
  auto channel_count =
          group->GetActiveConfiguration()->confs.sink.at(0).codec.GetChannelCountPerIsoStream();
  auto frame_octets = group->GetActiveConfiguration()->confs.sink.at(0).codec.GetOctetsPerFrame();
  ASSERT_NE(last_cig_params_.cis_cfgs.size(), 0lu);
  ASSERT_EQ(last_cig_params_.sdu_itv_c_to_p, data_interval);
  ASSERT_EQ(last_cig_params_.cis_cfgs.at(0).max_sdu_size_c_to_p,
            codec_frame_blocks_per_sdu_ * channel_count * frame_octets);
}

TEST_F(StateMachineTest, testConfigureCodecMulti) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 2;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);

  auto expected_devices_written = 0;
  auto* leAudioDevice = group->GetFirstDevice();
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(1));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  InjectInitialIdleNotification(group);
  /* Do nothing on the CigCreate, so the state machine stays in the configure
   * state */
  ON_CALL(*mock_iso_manager_, CreateCig).WillByDefault(Return());
  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);

  // Start the configuration and stream the content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  /* Cancel is called when group goes to streaming. */
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testConfigureQosSingle) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional + 1xunidirectional CIS)
   */
  additional_snk_ases = 1;
  additional_src_ases = 1;
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 3;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  auto* leAudioDevice = group->GetFirstDevice();
  PrepareConfigureCodecHandler(group, 2);
  PrepareConfigureQosHandler(group, 2);

  // Start the configuration and stream Media content
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testConfigureQosSingleRecoverCig) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional + 1xunidirectional CIS)
   */
  additional_snk_ases = 1;
  additional_src_ases = 1;
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 3;

  /* Assume that on previous BT OFF CIG was not removed */
  group_create_command_disallowed_ = true;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  auto* leAudioDevice = group->GetFirstDevice();
  PrepareConfigureCodecHandler(group, 2);
  PrepareConfigureQosHandler(group, 2);

  // Start the configuration and stream Media content
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testConfigureQosMultiple) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 3;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(2));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);
  SyncOnMainLoop();
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testConfigureQosFailed) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 3;
  const auto num_devices = 2;

  // Check if CIG is properly cleared when QoS failed

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareCtpNotificationError(
          group, client_parser::ascs::kCtpOpcodeQosConfiguration,
          client_parser::ascs::kCtpResponseCodeInvalidConfigurationParameterValue,
          client_parser::ascs::kCtpResponsePhy);

  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    // We will inject state after manually for test porpuse
    block_releasing_state_device_list_.push_back(leAudioDevice->address_);

    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(2));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  InjectReleaseAndIdleStateForAGroup_andWait(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  // During error only one cancel will happen when all devices will go down to IDLE
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testDeviceDisconnectedWhileCigCreated) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 3;
  const auto num_devices = 1;

  // verify proper cleaning when group is disconnected while CIG is creating.

  // Prepare fake connected device in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);

  ON_CALL(*mock_iso_manager_, CreateCig).WillByDefault(Return());

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  InjectAclDisconnected_andWait(group, leAudioDevice);
  std::vector<uint16_t> conn_handles = {0x0001, 0x0002};
  int cig_id = 1;

  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);
  InjectHciNotifyCigCreate(group, HCI_SUCCESS, cig_id, conn_handles);
  SyncOnMainLoop();
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testStreamCreationError) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Ringtone with channel count 1 for single device and 1 ASE sink will
   * end up with 1 Sink ASE being configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareCtpNotificationError(group, client_parser::ascs::kCtpOpcodeEnable,
                              client_parser::ascs::kCtpResponseCodeUnspecifiedError,
                              client_parser::ascs::kCtpResponseNoReason);
  PrepareReleaseHandler(group);

  auto leAudioDevice = group->GetFirstDevice();

  /* To avoid the loop. Will Inject release later. */
  block_releasing_state_device_list_.push_back(leAudioDevice->address_);

  /*
   * 1 - Configure ASE
   * 2 - QoS ASE
   * 3 - Enable ASE
   * 4 - Release ASE
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  InjectReleaseAndIdleStateForAGroup_andWait(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamSingle) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Ringtone with channel count 1 for single device and 1 ASE sink will
   * end up with 1 Sink ASE being configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStreamSingleRetryCisFailure) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Ringtone with channel count 1 for single device and 1 ASE sink will
   * end up with 1 Sink ASE being configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);
  PrepareReleaseHandler(group);

  use_cis_retry_cnt_ = true;
  retry_cis_established_cnt_ = 4;

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(3);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(2, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamSingleRetryCisSuccess) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Ringtone with channel count 1 for single device and 1 ASE sink will
   * end up with 1 Sink ASE being configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);

  use_cis_retry_cnt_ = true;
  retry_cis_established_cnt_ = 2;

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(3);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamSkipEnablingSink) {
  /* Device is banded headphones with 2x snk + none src ase
   * (2x unidirectional CIS)
   */

  /* Not, that when remote device skip Enabling it is considered as an error and
   * group will not be able to go to Streaming state.
   * It is because, Android is not creating CISes before all ASEs gets into
   * Enabling state, therefore it is impossible to remote device to skip
   * Enabling state.
   */
  const auto context_type = kContextTypeMedia;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* For Media context type with channel count 1 and two ASEs,
   * there should have be 2 Ases configured configured.
   */
  PrepareConfigureCodecHandler(group, 2);
  PrepareConfigureQosHandler(group, 2);
  PrepareEnableHandler(group, 2, false);

  /*
   * 1. Configure
   * 2. QoS Config
   * 3. Enable
   * 4. Release
   */
  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamSkipEnablingSinkSource) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional CIS)
   */
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  additional_snk_ases = 1;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Conversional context in mind,
   * 2 Sink ASEs and 1 Source ASE should have been configured.
   */
  PrepareConfigureCodecHandler(group, 3);
  PrepareConfigureQosHandler(group, 3);
  PrepareEnableHandler(group, 3, false);
  PrepareReceiverStartReadyHandler(group, 1);

  /*
   * 1. Codec Config
   * 2. Qos Config
   * 3. Enable
   * 4. Release
   */
  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamMultipleMedia_OneMemberHasNoAses) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group. This time one device
  // has 0 Ases
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             types::AudioContexts(), true);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  /* Check there are two devices*/
  auto* leAudioDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(leAudioDevice);
  /*
   * Second set member has no ASEs, no operations on control point are expected
   * 0
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  /*
   * First device will be configured for Streaming. Expecting 3 operations:
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamMultipleMedia_OneMemberHasNoAsesAndNotConnected) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group. This time one device
  // has 0 Ases
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             types::AudioContexts(), true);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  /* Check there are two devices*/
  auto* leAudioDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(leAudioDevice);
  /*
   * Second set member has no ASEs, no operations on control point are expected
   * 0
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  /* Device with 0 Ases is disconnected */
  InjectAclDisconnected_andWait(group, secondDevice);

  /*
   * First device will be configured for Streaming. Expecting 3 operations:
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  // Validate direcions
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_FALSE(source_is_enabled);
}

TEST_F(StateMachineTest, testStreamSingleConversational_TwsWithTwoBidirectional) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 1;

  /* Conversational to single device which has 4 ASE Sink and 2 ASE Source and channel count 1.
   * This should result with CIG configured with 2 bidirectional channels .
   */

  additional_snk_ases = 3;
  additional_src_ases = 1;

  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(4);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  // Validate direcions
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);
}

TEST_F(StateMachineTest, testStreamMultipleConversational) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(4);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

MATCHER_P(dataPathDirIsEq, expected, "") { return arg.data_path_dir == expected; }

TEST_F(StateMachineTest, testFailedStreamMultipleConversational) {
  /* Testing here CIS Failed to be established */
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;
  overwrite_cis_status_ = true;

  cis_status_.resize(2);
  cis_status_[0] = 0x00;
  cis_status_[1] = 0x0e;  // Failed to be established

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReleaseHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));

  /* Bidirectional CIS data path is configured in tw ocalls and removed for both
   * directions with a single call.
   */
  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(
                      _, dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionIn)))
          .Times(1);
  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(
                      _, dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionOut)))
          .Times(1);
  EXPECT_CALL(*mock_iso_manager_,
              RemoveIsoDataPath(
                      _, bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput |
                                 bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput))
          .Times(1);

  /* This check is the major one in this test, as we want to make sure,
   * it will not be called twice but only once (when both bidirectional ASEs are
   * not in the STREAMING or ENABLING state)
   */
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);

  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();

  /* First device Control Point actions
   * Codec Config
   * QoS Config
   * Enable
   * Receiver ready
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(5);
  leAudioDevice = group->GetNextDevice(leAudioDevice);

  /* Second device Control Point actions
   * Codec Config
   * QoS Config
   * Enable (failed on CIS established - therefore no Receiver Ready)
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  /* Called twice. One when change target state from Streaming to IDLE,
   * and second time, when state machine entered IDLE.
   */
  ASSERT_EQ(2, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testFailedStreamCreationAndCigRemovalDisallowed) {
  /* Testing here CIS Failed to be established */
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  do_not_send_cis_establish_event_ = true;
  remove_cig_simulated_status_ = HCI_ERR_COMMAND_DISALLOWED;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, true, false);  // Make sure we stay in Enabling state
  PrepareReceiverStartReadyHandler(group);
  PrepareReleaseHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));

  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(AtLeast(1));

  InjectInitialIdleNotification(group);

  auto* firstDevice = group->GetFirstDevice();

  /* First device Control Point actions
   * Codec Config
   * QoS Config
   * Enable
   * Release
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(4);
  auto* secondDevice = group->GetNextDevice(firstDevice);

  /* Second device Control Point actions
   * Codec Config
   * QoS Config
   * Enable
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  auto* firstDeviceAse = firstDevice->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(firstDeviceAse == nullptr);

  log::debug("ASE STATE {}", common::ToString(firstDeviceAse->state));

  // make sure the ASE is in correct state, required in this scenario
  ASSERT_TRUE(firstDeviceAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

  log::debug("Inject CIS Established Event failed");
  bluetooth::hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt = {
          .status = 0x1e,  // Invalid LMP Parameters / Invalid LL Parameters (0x1e),
          .cig_id = leaudio_group_id,
          .cis_conn_hdl = firstDeviceAse->cis_conn_hdl,
  };

  InjectHciNotifyCisEstablished(group, firstDevice, cis_establish_evt);
  SyncOnMainLoop();

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  testing::Mock::VerifyAndClearExpectations(&gatt_queue);
  testing::Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::debug("Start stream again - CIG was not removed and we should reuse it");

  do_not_send_cis_establish_event_ = false;
  PrepareEnableHandler(group);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(3);
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(0);

  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  testing::Mock::VerifyAndClearExpectations(&gatt_queue);
  testing::Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testAttachToStreamWhileFirstDeviceIsStartingStream) {
  /* Testing here CIS Failed to be established */
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, true /* inject enabling */, false /* inject streaming*/);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  auto firstDevice = group->GetFirstDevice();
  auto lastDevice = group->GetNextDevice(firstDevice);

  /* Disconnect first device */
  InjectAclDisconnected_andWait(group, firstDevice);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Now, group is not yet in the streaming state. Let's simulated the other
  // device got connected
  firstDevice->conn_id_ = 1;
  firstDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  for (auto& ase : lastDevice->ases_) {
    std::vector<uint8_t> params{};
    if (ase.active) {
      InjectAseStateNotification(&ase, lastDevice, group, ascs::kAseStateStreaming, &params);
    }
  }
  SyncOnMainLoop();
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testFailedStreamCreation) {
  /* Testing here different error than CIS Failed to be established */
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, true /* inject enabling */, false /* inject streaming*/);
  PrepareReleaseHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  /* Prepare DisconnectCis mock to not symulate CisDisconnection */
  ON_CALL(*mock_iso_manager_, EstablishCis).WillByDefault(Return());

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();

  /* First device Control Point actions
   * Codec Config
   * QoS Config
   * Enable
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);
  leAudioDevice = group->GetNextDevice(leAudioDevice);

  /* Second device Control Point actions
   * Codec Config
   * QoS Config
   * Enable (failed on CIS established - therefore no Receiver Ready)
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  bluetooth::hci::iso_manager::cis_establish_cmpl_evt evt;
  evt.status = HCI_ERR_LMP_RESPONSE_TIMEOUT;

  InjectHciNotifyCisEstablished(group, leAudioDevice, evt);
  SyncOnMainLoop();

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  /* Called twice. One when change target state from Streaming to IDLE,
   * and second time, when state machine entered IDLE.
   */
  ASSERT_EQ(2, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, remoteRejectsEnable) {
  /* Testing here CIS Failed to be established */
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareCtpNotificationError(group, client_parser::ascs::kCtpOpcodeEnable,
                              client_parser::ascs::kCtpResponseCodeUnspecifiedError,
                              client_parser::ascs::kCtpResponseNoReason);
  PrepareReleaseHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  auto leAudioDevice = group->GetFirstDevice();
  block_releasing_state_device_list_.push_back(leAudioDevice->address_);

  /* First device Control Point actions
   * Codec Config
   * QoS Config
   * Enable
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  leAudioDevice = group->GetNextDevice(leAudioDevice);
  block_releasing_state_device_list_.push_back(leAudioDevice->address_);

  /* Second device Control Point actions
   * Codec Config
   * QoS Config
   * Enable
   * Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  InjectReleaseAndIdleStateForAGroup_andWait(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamMultiple) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testUpdateMetadataMultiple) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  auto supported_contexts = types::AudioContexts(kContextTypeMedia | kContextTypeSoundEffects);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             supported_contexts);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&gatt_queue);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Make sure all devices get the metadata update
  leAudioDevice = group->GetFirstDevice();
  expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(1);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  const auto metadata_context_type = kContextTypeMedia | kContextTypeSoundEffects;
  StartStream_onMainloop(group, context_type,
                         {.sink = metadata_context_type, .source = metadata_context_type});

  /* This is just update metadata - watchdog is not used */
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testUpdateMetadataMultiple_NoUpdatesOnKeyTouch) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  /* Only Media is supported and available, */
  auto supported_contexts = types::AudioContexts(kContextTypeMedia);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             supported_contexts);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&gatt_queue);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Make sure all devices get the metadata update
  leAudioDevice = group->GetFirstDevice();
  expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(0);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  const auto metadata_context_type = kContextTypeMedia | kContextTypeSoundEffects;
  StartStream_onMainloop(group, context_type,
                         {.sink = metadata_context_type, .source = metadata_context_type});

  /* This is just update metadata - watchdog is not used */
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testDisableSingle) {
  /* Device is banded headphones with 2x snk + 0x src ase
   * (2xunidirectional CIS)
   */
  additional_snk_ases = 1;
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Ringtone context plus additional ASE with channel count 1
   * gives us 2 ASE which should have been configured.
   */
  PrepareConfigureCodecHandler(group, 2);
  PrepareConfigureQosHandler(group, 2);
  PrepareEnableHandler(group, 2);
  PrepareDisableHandler(group, 2);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_,
              RemoveIsoDataPath(_, bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput))
          .Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::SUSPENDING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::SUSPENDED));

  // Suspend the stream
  group_is_suspending_ = true;
  SuspendStream_onMainloop(group);

  // Check if group has transition to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testDisableMultiple) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(4));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_,
              RemoveIsoDataPath(_, bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput))
          .Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::SUSPENDING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::SUSPENDED));

  // Suspend the stream
  group_is_suspending_ = true;
  SuspendStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testDisableBidirectional) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional + 1xunidirectional CIS)
   */
  additional_snk_ases = 1;
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  InjectInitialIdleNotification(group);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 3);
  PrepareConfigureQosHandler(group, 3);
  PrepareEnableHandler(group, 3);
  PrepareDisableHandler(group, 3);
  PrepareReceiverStartReadyHandler(group, 1);
  PrepareReceiverStopReady(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(4));

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(3);
  bool removed_bidirectional = false;
  bool removed_unidirectional = false;

  /* Check data path removal */
  ON_CALL(*mock_iso_manager_, RemoveIsoDataPath)
          .WillByDefault(Invoke([&removed_bidirectional, &removed_unidirectional, this](
                                        uint16_t conn_handle, uint8_t data_path_dir) {
            /* Set flags for verification */
            if (data_path_dir == (bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput |
                                  bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput)) {
              removed_bidirectional = true;
            } else if (data_path_dir ==
                       bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput) {
              removed_unidirectional = true;
            }

            /* Copied from default handler of RemoveIsoDataPath*/
            auto dev_it = std::find_if(le_audio_devices_.begin(), le_audio_devices_.end(),
                                       [&conn_handle](auto& dev) {
                                         auto ases = dev->GetAsesByCisConnHdl(conn_handle);
                                         return ases.sink || ases.source;
                                       });
            if (dev_it == le_audio_devices_.end()) {
              return;
            }

            for (auto& kv_pair : le_audio_device_groups_) {
              auto& group = kv_pair.second;
              if (group->IsDeviceInTheGroup(dev_it->get())) {
                InjectHciNotifyRemoveIsoDataPath(group.get(), dev_it->get(), 0, conn_handle);

                return;
              }
            }
            /* End of copy */
          }));

  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::SUSPENDING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::SUSPENDED));

  // Suspend the stream
  group_is_suspending_ = true;
  SuspendStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
  ASSERT_EQ(removed_bidirectional, true);
  ASSERT_EQ(removed_unidirectional, true);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testTwoBidirectionalAses) {
  /* Device is banded headphones with 2x snk + 2x src ase
   * (2x bidirectional)
   */
  additional_snk_ases = 1;
  additional_src_ases = 1;
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  InjectInitialIdleNotification(group);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 4);
  PrepareConfigureQosHandler(group, 4);
  PrepareEnableHandler(group, 4);
  PrepareDisableHandler(group, 4);
  PrepareReceiverStartReadyHandler(group, 2);
  PrepareReceiverStopReady(group, 2);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(4));

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);

  /* Just store requested CIS HANDLES and simulate that all the ASEs do have CIS in CONNECTED state
   * and data_path_state in CONFIGURING state which triggers the issue
   */
  std::vector<uint16_t> cis_handles_vec;
  ON_CALL(*mock_iso_manager_, SetupIsoDataPath)
          .WillByDefault(
                  [&cis_handles_vec](uint16_t conn_handle,
                                     bluetooth::hci::iso_manager::iso_data_path_params /*p*/) {
                    log::debug("SetupIsoDataPath");
                    ASSERT_NE(conn_handle, kInvalidCisConnHandle);
                    cis_handles_vec.push_back(conn_handle);
                  });

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  for (auto& cis_handle : cis_handles_vec) {
    log::debug("[TESTING] ProcessHciNotifSetupIsoDataPath. Expect StatusReportCb to be called");
    InjectHciNotifySetupIsoDataPath(group, leAudioDevice, 0, cis_handle);
  }
  SyncOnMainLoop();

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();
}

TEST_F(StateMachineTest, testReleaseSingle) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo)
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);
  PrepareDisableHandler(group, 1);
  PrepareReleaseHandler(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  EXPECT_CALL(*mock_codec_manager_, UpdateCisConfiguration(_, _, _)).Times(0);
  EXPECT_CALL(*mock_codec_manager_, ClearCisConfiguration(_)).Times(0);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  reset_mock_function_count_map();
  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  // Stop the stream
  EXPECT_CALL(*mock_codec_manager_, UpdateCisConfiguration(_, _, _)).Times(0);

  /* ClearCisConfiguration is called for each direction unconditionaly when stream goes to idle.
   * In addition, it is called when handling CIS disconnection and here we want Sink to be called.
   */
  EXPECT_CALL(*mock_codec_manager_,
              ClearCisConfiguration(bluetooth::le_audio::types::kLeAudioDirectionSink))
          .Times(2);
  EXPECT_CALL(*mock_codec_manager_,
              ClearCisConfiguration(bluetooth::le_audio::types::kLeAudioDirectionSource))
          .Times(1);

  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(StateMachineTest, testReleaseCachingSingle) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS)
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  PrepareConfigureCodecHandler(group, 1, true);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);
  PrepareDisableHandler(group, 1);
  PrepareReleaseHandler(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStreamCaching_NoReconfigurationNeeded_SingleDevice) {
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  additional_snk_ases = 2;
  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind and with no Source
   * ASEs, therefor only one ASE should have been configured.
   */
  PrepareConfigureCodecHandler(group, 1, true);
  PrepareConfigureQosHandler(group, 1, true);
  PrepareEnableHandler(group, 1);
  PrepareDisableHandler(group, 1);
  PrepareReleaseHandler(group, 1);

  /* Ctp messages we expect:
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   * 4. Release
   * 5. QoS Config (because device stays in Configured state)
   * 6. Enable
   */
  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(6);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(2);

  // Start the configuration and stream Ringtone content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, test_StreamCaching_ReconfigureForContextChange_SingleDevice) {
  auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  additional_snk_ases = 2;
  /* Prepare fake connected device group with update of Media and Conversational
   * contexts
   */
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, 1,
                                             kContextTypeConversational | kContextTypeMedia);

  /* Don't validate ASE here, as after reconfiguration different ASE number
   * will be used.
   * For the first configuration (CONVERSTATIONAL) there will be 2 ASEs (Sink
   * and Source) After reconfiguration (MEDIA) there will be single ASE.
   */
  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group, 0, true);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReleaseHandler(group);

  /* Ctp messages we expect:
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   * 4. Release
   * 5. Codec Config
   * 6. QoS Config
   * 7. Enable
   */
  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(8);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(2);

  /* 2 times for first configuration (1 Sink, 1 Source), 1 time for second
   * configuration (1 Sink)*/
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(3);

  uint8_t value = bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput |
                  bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, value)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(2);

  // Start the configuration and stream Conversational content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Start the configuration and stream Media content
  context_type = kContextTypeMedia;
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testReleaseMultiple) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(4));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testStartAndStopStreamConversational_VerifyCodecManagerCallsOnCisRemoval) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // This is called when 1 CIS got disconnected.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateCisConfiguration(_, _, bluetooth::le_audio::types::kLeAudioDirectionSink))
          .Times(1);
  EXPECT_CALL(*mock_codec_manager_,
              UpdateCisConfiguration(_, _, bluetooth::le_audio::types::kLeAudioDirectionSource))
          .Times(1);
  EXPECT_CALL(*mock_codec_manager_, ClearCisConfiguration(_)).Times(0);

  InjectCisDisconnected_andWait(group, leAudioDevice, HCI_ERR_PEER_USER);

  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  // Stop the stream
  EXPECT_CALL(*mock_codec_manager_, UpdateCisConfiguration(_, _, _)).Times(0);
  EXPECT_CALL(*mock_codec_manager_,
              ClearCisConfiguration(bluetooth::le_audio::types::kLeAudioDirectionSink))
          .Times(2);
  EXPECT_CALL(*mock_codec_manager_,
              ClearCisConfiguration(bluetooth::le_audio::types::kLeAudioDirectionSource))
          .Times(2);

  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testReleaseMultiple_CisDisconnectedBeforeGettingToIdleState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Test Scenario:
   * 1. Start stream
   * 2. Stop the stream
   * 3. While stopping, make sure that CISes are disconnected before current state is IDLE - verify
   * watchdog keeps running
   * 4. Move to IDLE, make sure watchdog is cleared
   */

  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  stay_in_releasing_state_ = true;

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(4));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  // Watchdog shall not be cancled here.
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  InjectReleaseAndIdleStateForAGroup_andWait(group, false, true);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testReleaseMultiple_CisDisconnectedBeforeGettingToConfiguredState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Test Scenario:
   * 1. Start stream
   * 2. Stop the stream
   * 3. While stopping, make sure that CISes are disconnected before current state is CONFIGURED -
   * verify watchdog keeps running
   * 4. Move to CONFIGURED, make sure watchdog is cleared
   */

  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group, 0, true);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  stay_in_releasing_state_ = true;

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(4));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  // Watchdog shall not be cancled here.
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  InjectCachedConfigurationForGroup_andWait(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testAutonomousReleaseMultiple) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* secondDevice;

  /*
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   */
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    secondDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::RELEASING_AUTONOMOUS))
          .Times(1);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(0);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE))
          .Times(1);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  // Do not take any actions on DisconnectCis. Later it will be injected.
  ON_CALL(*mock_iso_manager_, DisconnectCis).WillByDefault(Return());

  log::info("Inject Release of all ASEs");

  // Inject Release state from remove
  InjectReleaseAndIdleStateForAGroup_andWait(group, true, false);

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);

  log::info("Inject CIS Disconnected Event");

  // Inject CIS Disconnection from remote
  InjectCisDisconnected_andWait(group, firstDevice, HCI_ERR_PEER_USER);
  InjectCisDisconnected_andWait(group, secondDevice, HCI_ERR_PEER_USER);

  // Inject Idle ASE
  InjectReleaseAndIdleStateForAGroup_andWait(group, false, true);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testReleaseMultiple_DeviceDisconnectedDuringRelease) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);

  /* Here we inject device disconnection during release */
  PrepareReleaseHandler(group, 0, true);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(4));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);
  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testReleaseBidirectional) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional + 1xunidirectional CIS)
   */
  additional_snk_ases = 1;
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 3);
  PrepareConfigureQosHandler(group, 3);
  PrepareEnableHandler(group, 3);
  PrepareDisableHandler(group, 3);
  PrepareReceiverStartReadyHandler(group, 1);
  PrepareReleaseHandler(group, 3);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(4));

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(3);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  // 1 for Sink and 1 for Source
  EXPECT_CALL(*mock_codec_manager_, UpdateCisConfiguration(_, _, _)).Times(0);
  EXPECT_CALL(*mock_codec_manager_, ClearCisConfiguration(_)).Times(0);

  InjectInitialIdleNotification(group);
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();
  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  group->PrintDebugState();

  // Stop the stream
  // This will be called once after first CIS is disconnected
  EXPECT_CALL(*mock_codec_manager_, UpdateCisConfiguration(_, _, _)).Times(1);

  /* ClearCisConfiguration is called for each direction unconditionaly when stream goes to idle.
   * In addition, it is called when handling CIS disconnection and here we want Sink and Source to
   * be called.
   */
  EXPECT_CALL(*mock_codec_manager_,
              ClearCisConfiguration(bluetooth::le_audio::types::kLeAudioDirectionSink))
          .Times(2);
  EXPECT_CALL(*mock_codec_manager_,
              ClearCisConfiguration(bluetooth::le_audio::types::kLeAudioDirectionSource))
          .Times(2);

  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(StateMachineTest, testDisableAndReleaseBidirectional) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional + 1xunidirectional CIS)
   */
  additional_snk_ases = 1;
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  InjectInitialIdleNotification(group);
  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 3);
  PrepareConfigureQosHandler(group, 3);
  PrepareEnableHandler(group, 3);
  PrepareDisableHandler(group, 3);
  PrepareReceiverStartReadyHandler(group, 1);
  PrepareReceiverStopReady(group, 1);
  PrepareReleaseHandler(group, 3);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(4));

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(3);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Suspend the stream
  group_is_suspending_ = true;
  SuspendStream_onMainloop(group);

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
}

TEST_F(StateMachineTest, testAseIdAssignmentIdle) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  // Should not trigger any action on our side
  EXPECT_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  for (auto* device = group->GetFirstDevice(); device != nullptr;
       device = group->GetNextDevice(device)) {
    for (auto& ase : device->ases_) {
      ASSERT_EQ(ase.id, bluetooth::le_audio::types::ase::kAseIdInvalid);
      InjectAseStateNotification(&ase, device, group, ascs::kAseStateIdle, nullptr);
      SyncOnMainLoop();
      ASSERT_EQ(ase.id, ase_id_last_assigned);
    }
  }
}

TEST_F(StateMachineTest, testAseIdAssignmentCodecConfigured) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  // Should not trigger any action on our side
  EXPECT_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  for (auto* device = group->GetFirstDevice(); device != nullptr;
       device = group->GetNextDevice(device)) {
    for (auto& ase : device->ases_) {
      client_parser::ascs::ase_codec_configured_state_params codec_configured_state_params;

      ASSERT_EQ(ase.id, bluetooth::le_audio::types::ase::kAseIdInvalid);
      InjectAseStateNotification(&ase, device, group, ascs::kAseStateCodecConfigured,
                                 &codec_configured_state_params);
      SyncOnMainLoop();

      ASSERT_EQ(ase.id, ase_id_last_assigned);
    }
  }
}

TEST_F(StateMachineTest, testAseAutonomousRelease) {
  /* Device is banded headphones with 2x snk + 1x src ase
   * (1x bidirectional + 1xunidirectional CIS)
   */
  additional_snk_ases = 1;
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 3);
  PrepareConfigureQosHandler(group, 3);
  PrepareEnableHandler(group, 3);
  PrepareDisableHandler(group, 3);
  PrepareReceiverStartReadyHandler(group, 1);
  PrepareReceiverStopReady(group, 1);
  PrepareReleaseHandler(group, 3);

  InjectInitialIdleNotification(group);

  // Validate initial GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Validate new GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::RELEASING_AUTONOMOUS))
          .Times(1);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE))
          .Times(AtLeast(1));

  /* Single disconnect as it is bidirectional Cis*/
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  for (auto* device = group->GetFirstDevice(); device != nullptr;
       device = group->GetNextDevice(device)) {
    for (auto& ase : device->ases_) {
      client_parser::ascs::ase_codec_configured_state_params codec_configured_state_params;

      ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

      // Each one does the autonomous release
      InjectAseStateNotification(&ase, device, group, ascs::kAseStateReleasing,
                                 &codec_configured_state_params);
      InjectAseStateNotification(&ase, device, group, ascs::kAseStateIdle,
                                 &codec_configured_state_params);
    }
  }
  SyncOnMainLoop();

  // Verify we've handled the release and updated all states
  for (auto* device = group->GetFirstDevice(); device != nullptr;
       device = group->GetNextDevice(device)) {
    for (auto& ase : device->ases_) {
      ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    }
  }

  // When target state is moving to IDLE it starts watchdog which is later canceled.
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testAseAutonomousRelease2Devices) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  const int num_of_devices = 2;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_of_devices);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  // Validate initial GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  /* Check streaming will continue. Streaming status should be send up so the user
   * can update e.g. CIS count
   */
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE))
          .Times(0);

  /* Single disconnect as it is bidirectional Cis*/
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);

  auto device = group->GetFirstDevice();
  for (auto& ase : device->ases_) {
    client_parser::ascs::ase_codec_configured_state_params codec_configured_state_params;

    ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

    // Simulate autonomus release for one device.
    InjectAseStateNotification(&ase, device, group, ascs::kAseStateReleasing,
                               &codec_configured_state_params);
    InjectAseStateNotification(&ase, device, group, ascs::kAseStateIdle,
                               &codec_configured_state_params);
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(&mock_callbacks_);
  }
}

TEST_F(StateMachineTest, testHandlingAutonomousCodecConfigStateOnConnection) {
  /* Scenario
   * 1. After connection remote device has different ASE configurations
   * 2. Try to start stream and make sure it is configured well.
   */

  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  const int num_of_devices = 2;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_of_devices);

  auto* firstDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(firstDevice);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);

  /* Number of control point calls
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   * 4. Receiver Start Ready
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  InjectInitialIdleAndConfiguredNotification(group);
  // Call it second time to make sure we get into state that current_state_ is
  // different then target_state_ even group is not in transition.
  InjectInitialIdleAndConfiguredNotification(group);

  ASSERT_TRUE(group->GetTargetState() != group->GetState());
  ASSERT_FALSE(group->IsInTransition());

  // Validate initial GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testHandlingInvalidRemoteAseStateHandling) {
  /* Scenario
   * 1. After connection remote device has different ASE configurations
   * 2. Try to start stream and make sure it is configured well.
   */

  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  const int num_of_devices = 2;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_of_devices);

  auto* firstDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(firstDevice);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);

  /* Number of control point calls
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   * 4. Receiver Start Ready
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  /* Inject invalid states*/
  InjectInitialInvalidNotification(group);

  ASSERT_FALSE(group->IsInTransition());

  // Validate initial GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testHandlingCachedCodecConfig2Devices) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  const int num_of_devices = 2;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_of_devices);

  auto* firstDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(firstDevice);

  /* Since we prepared device with Conversional context in mind, Sink and Source
   * ASEs should have been configured.
   */
  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);
  PrepareReleaseHandler(group);

  stay_in_releasing_state_ = true;

  /* Number of control point calls
   * 1. Codec Config
   * 2. QoS Config
   * 3. Enable
   * 4. Receiver Start Ready
   * 5. Release*/
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(5);

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(5);

  InjectInitialIdleNotification(group);

  // Validate initial GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  /* Two disconnect as it is two bidirectional Cises */
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);

  // Validate initial GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(1);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  for (auto& ase : firstDevice->ases_) {
    log::debug("{} , {}, {}", firstDevice->address_, ase.id,
               bluetooth::common::ToString(ase.state));
    ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
    // Simulate autonomus configured state.
    InjectAseStateNotification(&ase, firstDevice, group, ascs::kAseStateCodecConfigured,
                               &cached_codec_configuration_map_[ase.id]);
  }
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  /* When ALL devices got inactive, we should got the proper group status */
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS))
          .Times(1);
  for (auto& ase : secondDevice->ases_) {
    log::debug("{} , {}, {}", firstDevice->address_, ase.id,
               bluetooth::common::ToString(ase.state));
    ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
    // Simulate autonomus configured state.
    InjectAseStateNotification(&ase, secondDevice, group, ascs::kAseStateCodecConfigured,
                               &cached_codec_configuration_map_[ase.id]);
  }
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStateTransitionTimeoutOnIdleState) {
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Disconnect device
  // in client.cc before this function is called, state of device is changed.
  leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);
  InjectHciNotifyAclDisconnected_andWait(group, leAudioDevice);

  // Make sure timeout is cleared
  ASSERT_TRUE(fake_osi_alarm_set_on_mloop_.cb == nullptr);
}

TEST_F(StateMachineTest, testStateIdleNotifyAclDisconnectedRemoveCig) {
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  group->cig.SetState(types::CigState::CREATED);

  // Assert current state
  ASSERT_TRUE(group->GetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_FALSE(group->IsInTransition());
  ASSERT_TRUE(group->cig.GetState() == types::CigState::CREATED);

  // Expect RemoveCig to be called
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(group->group_id_, _)).Times(1);

  // Disconnect device
  auto* leAudioDevice = group->GetFirstDevice();
  InjectHciNotifyAclDisconnected_andWait(group, leAudioDevice);

  // Assert Cig state transition to NONE after REMOVING
  ASSERT_TRUE(group->cig.GetState() == types::CigState::NONE);
}

TEST_F(StateMachineTest, testStateTransitionTimeout) {
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if timeout is fired
  EXPECT_CALL(mock_callbacks_, OnStateTransitionTimeout(leaudio_group_id));

  // simulate timeout seconds passed, alarm executing
  fake_osi_alarm_expired(fake_osi_alarm_set_on_mloop_);
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
}

TEST_F(StateMachineTest, testStateTransitionTimeoutAndDisconnectWhenConfigured) {
  const auto context_type = kContextTypeMedia;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  InjectInitialConfiguredNotification(group);

  group->PrintDebugState();

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  group->PrintDebugState();

  // Check if timeout is fired
  EXPECT_CALL(mock_callbacks_, OnStateTransitionTimeout(leaudio_group_id));

  // simulate timeout seconds passed, alarm executing
  fake_osi_alarm_expired(fake_osi_alarm_set_on_mloop_);
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  log::info("OnStateTransitionTimeout");

  /* Simulate On State timeout */
  group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  group->ClearAllCises();
  group->PrintDebugState();

  InjectAclDisconnected_andWait(group, leAudioDevice);

  /* Verify that all ASEs are inactive and reconfiguration flag is cleared.*/
  for (const auto& ase : leAudioDevice->ases_) {
    ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    ASSERT_EQ(ase.cis_state, types::CisState::IDLE);
    ASSERT_EQ(ase.data_path_state, types::DataPathState::IDLE);
    ASSERT_EQ(ase.reconfigure, 0);
  }
}

TEST_F(StateMachineTest, testStateTransitionTimeoutAndDisconnectWhenQoSConfigured) {
  const auto context_type = kContextTypeMedia;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  PrepareConfigureCodecHandler(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(2);

  InjectInitialConfiguredNotification(group);

  group->PrintDebugState();

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  group->PrintDebugState();

  // Check if timeout is fired
  EXPECT_CALL(mock_callbacks_, OnStateTransitionTimeout(leaudio_group_id));

  // simulate timeout seconds passed, alarm executing
  fake_osi_alarm_expired(fake_osi_alarm_set_on_mloop_);
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  log::info("OnStateTransitionTimeout");

  /* Simulate On State timeout */
  group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  group->ClearAllCises();
  group->PrintDebugState();

  InjectAclDisconnected_andWait(group, leAudioDevice);

  /* Verify that all ASEs are inactive and reconfiguration flag is cleared.*/
  for (const auto& ase : leAudioDevice->ases_) {
    ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    ASSERT_EQ(ase.cis_state, types::CisState::IDLE);
    ASSERT_EQ(ase.data_path_state, types::DataPathState::IDLE);
    ASSERT_EQ(ase.reconfigure, 0);
  }
}

TEST_F(StateMachineTest, testStateTransitionTimeoutAndDisconnectWhenEnabling) {
  const auto context_type = kContextTypeMedia;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);

  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  InjectInitialConfiguredNotification(group);

  group->PrintDebugState();

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  group->PrintDebugState();

  // Check if timeout is fired
  EXPECT_CALL(mock_callbacks_, OnStateTransitionTimeout(leaudio_group_id));

  // simulate timeout seconds passed, alarm executing
  fake_osi_alarm_expired(fake_osi_alarm_set_on_mloop_);
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  log::info("OnStateTransitionTimeout");

  /* Simulate On State timeout */
  group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  group->ClearAllCises();
  group->PrintDebugState();

  InjectAclDisconnected_andWait(group, leAudioDevice);

  /* Verify that all ASEs are inactive and reconfiguration flag is cleared.*/
  for (const auto& ase : leAudioDevice->ases_) {
    ASSERT_EQ(ase.state, types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    ASSERT_EQ(ase.cis_state, types::CisState::IDLE);
    ASSERT_EQ(ase.data_path_state, types::DataPathState::IDLE);
    ASSERT_EQ(ase.reconfigure, 0);
  }
}

TEST_F(StateMachineTest, testInjectReleasingStateWhenEnabling) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  PrepareConfigureCodecHandler(group, 2);
  PrepareConfigureQosHandler(group, 2);
  PrepareEnableHandler(group, 0, true, false);

  InjectInitialConfiguredNotification(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  // Stub Establish Cis and Remove CIG
  ON_CALL(*mock_iso_manager_, EstablishCis).WillByDefault(Return());
  ON_CALL(*mock_iso_manager_, RemoveCig).WillByDefault(Return());

  group->PrintDebugState();

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  group->PrintDebugState();

  log::info("Inject Release of all ASEs");

  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);

  // Stub DisconnectCis to trigger the issue.
  ON_CALL(*mock_iso_manager_, DisconnectCis).WillByDefault(Return());

  InjectReleaseAndIdleStateForAGroup_andWait(group, true, false);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

MATCHER_P(dataPathIsEq, expected, "") { return arg.data_path_id == expected; }

TEST_F(StateMachineTest, testConfigureDataPathForHost) {
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  /* Can be called for every context when fetching the configuration
   */
  EXPECT_CALL(*mock_codec_manager_, GetCodecConfig(_, _)).Times(AtLeast(1));

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);

  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(_, dataPathIsEq(bluetooth::hci::iso_manager::kIsoDataPathHci)))
          .Times(1);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
}

TEST_F(StateMachineTest, testRemoveDataPathWhenSingleBudDisconnectsOnGattTimeout) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  const auto num_devices = 2;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  /* Scenario
   * 1. Two buds are streaming
   * 2. There is a GATT timeout on one of the device which cause disconnection but profile will get
   * fist GATT Close and later CIS Disconnection Timeout
   *
   * 3. Verify that Data Path is removed for the disconnected CIS
   */

  ContentControlIdKeeper::GetInstance()->SetCcid(kContextTypeConversational, call_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(_, dataPathIsEq(bluetooth::hci::iso_manager::kIsoDataPathHci)))
          .Times(4);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  EXPECT_CALL(*mock_iso_manager_,
              RemoveIsoDataPath(
                      _, bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput |
                                 bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput))
          .Times(1);

  auto device = group->GetFirstDevice();
  InjectAclDisconnected_andWait(group, device);
  InjectCisDisconnected_andWait(group, device, HCI_ERR_CONN_CAUSE_LOCAL_HOST);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTestAdsp, testConfigureDataPathForAdsp) {
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  /* Can be called for every context when fetching the configuration
   */
  EXPECT_CALL(*mock_codec_manager_, GetCodecConfig(_, _)).Times(AtLeast(1));

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  EXPECT_CALL(mock_callbacks_,
              OnUpdatedCisConfiguration(group->group_id_,
                                        bluetooth::le_audio::types::kLeAudioDirectionSink))
          .WillOnce([group](int group_id, uint8_t direction) {
            ASSERT_EQ(group_id, group->group_id_);

            auto const& params = group->stream_conf.stream_params.get(direction);
            ASSERT_NE(params.audio_channel_allocation, 0u);
            ASSERT_NE(params.num_of_channels, 0u);
            ASSERT_NE(params.num_of_devices, 0);

            auto stream_config = params.stream_config;
            ASSERT_NE(stream_config.bits_per_sample, 0u);
            ASSERT_NE(stream_config.sampling_frequency_hz, 0u);
            ASSERT_NE(stream_config.frame_duration_us, 0u);
            ASSERT_NE(stream_config.octets_per_codec_frame, 0u);
            ASSERT_NE(stream_config.codec_frames_blocks_per_sdu, 0u);
            ASSERT_NE(stream_config.peer_delay_ms, 0u);
            ASSERT_NE(stream_config.stream_map.size(), 0lu);

            for (auto const& info : stream_config.stream_map) {
              ASSERT_TRUE(info.is_stream_active);
              ASSERT_EQ(codec_specific::kLc3CodingFormat, info.codec_config.id.coding_format);
              ASSERT_EQ(0lu, info.codec_config.id.vendor_company_id);
              ASSERT_EQ(0lu, info.codec_config.id.vendor_codec_id);
              ASSERT_NE(info.address, RawAddress::kEmpty);
              ASSERT_NE(info.stream_handle, 0);
              ASSERT_NE(info.codec_config.params.Size(), 0lu);
              ASSERT_NE(info.target_latency, 0);
              ASSERT_NE(info.target_phy, 0);
              ASSERT_NE(info.metadata.Size(), 0lu);
            }
          });

  /* Since we prepared device with Ringtone context in mind, only one ASE
   * should have been configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);

  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(
                      _, dataPathIsEq(bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault)))
          .Times(1);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
}

TEST_F(StateMachineTestAdsp, testStreamConfigurationAdspDownMix) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;
  const int num_devices = 2;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             types::AudioContexts(kContextTypeConversational));

  EXPECT_CALL(mock_callbacks_,
              OnUpdatedCisConfiguration(group->group_id_,
                                        bluetooth::le_audio::types::kLeAudioDirectionSink))
          .WillOnce([group](int group_id, uint8_t direction) {
            ASSERT_EQ(group_id, group->group_id_);

            auto const& params = group->stream_conf.stream_params.get(direction);
            ASSERT_NE(params.audio_channel_allocation, 0u);
            ASSERT_NE(params.num_of_channels, 0u);
            ASSERT_NE(params.num_of_devices, 0);

            auto stream_config = params.stream_config;
            ASSERT_NE(stream_config.bits_per_sample, 0u);
            ASSERT_NE(stream_config.sampling_frequency_hz, 0u);
            ASSERT_NE(stream_config.frame_duration_us, 0u);
            ASSERT_NE(stream_config.octets_per_codec_frame, 0u);
            ASSERT_NE(stream_config.codec_frames_blocks_per_sdu, 0u);
            ASSERT_NE(stream_config.peer_delay_ms, 0u);
            ASSERT_NE(stream_config.stream_map.size(), 0lu);

            for (auto const& info : stream_config.stream_map) {
              ASSERT_TRUE(info.is_stream_active);
              ASSERT_EQ(codec_specific::kLc3CodingFormat, info.codec_config.id.coding_format);
              ASSERT_EQ(0lu, info.codec_config.id.vendor_company_id);
              ASSERT_EQ(0lu, info.codec_config.id.vendor_codec_id);
              ASSERT_NE(info.address, RawAddress::kEmpty);
              ASSERT_NE(info.stream_handle, 0);
              ASSERT_NE(info.codec_config.params.Size(), 0lu);
              ASSERT_NE(info.target_latency, 0);
              ASSERT_NE(info.target_phy, 0);
              ASSERT_NE(info.metadata.Size(), 0lu);
            }
          });
  EXPECT_CALL(mock_callbacks_,
              OnUpdatedCisConfiguration(group->group_id_,
                                        bluetooth::le_audio::types::kLeAudioDirectionSource))
          .WillOnce([group](int group_id, uint8_t direction) {
            ASSERT_EQ(group_id, group->group_id_);

            auto const& params = group->stream_conf.stream_params.get(direction);
            ASSERT_NE(params.audio_channel_allocation, 0u);
            ASSERT_NE(params.num_of_channels, 0u);
            ASSERT_NE(params.num_of_devices, 0);

            auto stream_config = params.stream_config;
            ASSERT_NE(stream_config.bits_per_sample, 0u);
            ASSERT_NE(stream_config.sampling_frequency_hz, 0u);
            ASSERT_NE(stream_config.frame_duration_us, 0u);
            ASSERT_NE(stream_config.octets_per_codec_frame, 0u);
            ASSERT_NE(stream_config.codec_frames_blocks_per_sdu, 0u);
            ASSERT_NE(stream_config.peer_delay_ms, 0u);
            ASSERT_NE(stream_config.stream_map.size(), 0lu);

            for (auto const& info : stream_config.stream_map) {
              ASSERT_TRUE(info.is_stream_active);
              ASSERT_EQ(codec_specific::kLc3CodingFormat, info.codec_config.id.coding_format);
              ASSERT_EQ(0lu, info.codec_config.id.vendor_company_id);
              ASSERT_EQ(0lu, info.codec_config.id.vendor_codec_id);
              ASSERT_NE(info.address, RawAddress::kEmpty);
              ASSERT_NE(info.stream_handle, 0);
              ASSERT_NE(info.codec_config.params.Size(), 0lu);
              ASSERT_NE(info.target_latency, 0);
              ASSERT_NE(info.target_phy, 0);
              ASSERT_NE(info.metadata.Size(), 0lu);
            }
          });

  /* Can be called for every context when fetching the configuration
   */
  EXPECT_CALL(*mock_codec_manager_, GetCodecConfig(_, _)).Times(AtLeast(1));

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  InjectAclDisconnected_andWait(group, leAudioDevice);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Note: The actual channel mixing is verified by the CodecManager unit tests.
}

TEST_F(StateMachineTest, testAttachDeviceToTheStream) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testAttachDeviceToTheStreamV2) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Scenario
   * 1. Both devices streaming
   * 2. One device disconnects
   * 3. Audio configuration resume and configuration cache is rebuilt
   * 4. Device attached
   */
  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  /* Force update configuration which is what happens when stream stops
   * and starts while streaming to single dev. This will rebuild cache,
   * which is what we need in this test.
   */
  group->UpdateAudioSetConfigurationCache(context_type);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testStreamingContextMechanism) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  auto test_context_type = kContextTypeUnspecified | kContextTypeConversational;
  firstDevice->SetAvailableContexts({.sink = test_context_type, .source = test_context_type});
  lastDevice->SetAvailableContexts({.sink = test_context_type, .source = test_context_type});

  auto expected_sink_context_type =
          kContextTypeUnspecified | kContextTypeConversational | kContextTypeMedia;
  auto expected_source_context_type = kContextTypeUnspecified | kContextTypeConversational;

  ASSERT_EQ(group->GetAvailableContexts(types::kLeAudioDirectionSink), expected_sink_context_type);
  ASSERT_EQ(group->GetAvailableContexts(types::kLeAudioDirectionSource),
            expected_source_context_type);
}

TEST_F(StateMachineTest, testAttachDeviceToTheStreamDeviceNoAvailableContext) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Connect the disconnected device BUT remove MEDIA from available Contex
  // Types
  lastDevice->conn_id_ = 3;
  auto test_context_type = kContextTypeUnspecified | kContextTypeConversational;
  lastDevice->SetAvailableContexts({.sink = test_context_type, .source = test_context_type});
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(0));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  ASSERT_EQ(LeAudioGroupStateMachine::Get()->AttachToStream(group, lastDevice,
                                                            {.sink = {media_ccid}, .source = {}}),
            false);

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testQoSConfigureWhileStreaming) {
  /* Device is banded headphones with 1x snk  0x src ase
   * (1xunidirectional CIS) with channel count 2 (for stereo
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  /* Ringtone with channel count 1 for single device and 1 ASE sink will
   * end up with 1 Sink ASE being configured.
   */
  PrepareConfigureCodecHandler(group, 1);
  PrepareConfigureQosHandler(group, 1);
  PrepareEnableHandler(group, 1);
  PrepareReleaseHandler(group, 1);

  /*
   * Device got to streaming state and sends QoSConfigured state.
   * Num of GATT operations
   * 1. Config
   * 2. QoS Config
   * 3. Enable
   * 4. Release
   */
  auto* leAudioDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::info(" Moving to QoS state");

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  InjectQoSConfigurationForGroupActiveAses_andWait(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, testReleaseStreamWithLateAttachToStream_CodecConfigState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Scenario
   * 1. Start streaming
   * 2. Stop stream on one device
   * 3. Reconnect
   * 4. Trigger attach the stream
   * 6. StopStream while getting to Codec Configured State on attaching device
   * 7. Check that Attaching device will not get Release CMD
   */

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::info("Stream is started for group {}, disconnect {}", group->group_id_,
            lastDevice->address_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  /* Set device is getting ready for the connection */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  log::info("Set device {} to CONNECTED state", lastDevice->address_);
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  /*
   * 1. Codec Configure for attaching device
   * 2. Release for streaming device only  as the attaching one is still not in Codec Configured
   * state.
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  log::info("Block Codec Configured Notification");
  PrepareConfigureCodecHandler(group, 0, true, false);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  log::info("Stop the stream");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  // Stop the stream
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  log::info(
          "Inject Codec Configured Notification and make sure there is no QoS "
          "Config sent");

  InjectCachedConfigurationForActiveAses_andWait(group, lastDevice);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  // Check if group is still in Streaming state - it will change when Release
  // notification will arrive.
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  log::info("Inject Release for a group");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectReleaseAndIdleStateForAGroup_andWait(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
}

TEST_F(StateMachineTest, testReleaseStreamWithLateAttachToStream_QoSConfigState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Scenario
   * 1. Start streaming
   * 2. Stop stream on one device
   * 3. Reconnect
   * 4. Trigger attach the stream
   * 6. StopStream while getting to QoS Configured state on attaching device
   * 7. Check that Attaching device will also go to IDLE
   */

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info("Stream is started for group {}, disconnect {}", group->group_id_,
            lastDevice->address_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  /* Set device is getting ready for the connection */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  log::info("Set device {} to CONNECTED state", lastDevice->address_);
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  /*
   * 1. Codec Configured for attaching device
   * 2. QoS Configured State for attaching device
   * 3. Release for both
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(3);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  log::info("Block QoS Configured Notification");
  PrepareConfigureQosHandler(group, 0, true, false);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  log::info("Stop the stream");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  // Stop the stream
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  log::info(
          "Inject QoS Config Notification and make sure that Enable Command is not "
          "sent");

  InjectQoSConfigurationForActiveAses_andWait(group, lastDevice);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  // Check if group is still in Streaming state - it will change when Release
  // notification will arrive.
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  log::info("Inject Release for a group");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectReleaseAndIdleStateForAGroup_andWait(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
}

TEST_F(StateMachineTest, testReleaseStreamWithLateAttachToStream_EnablingState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Scenario
   * 1. Start streaming
   * 2. Stop stream on one device
   * 3. Reconnect
   * 4. Trigger attach the stream
   * 6. StopStream while getting to Enable state on attaching device
   * 7. Check that Attaching device will also go to IDLE
   */

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info("Stream is started for group {}, disconnect {}", group->group_id_,
            lastDevice->address_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  /* Set device is getting ready for the connection */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  log::info("Set device {} to CONNECTED state", lastDevice->address_);
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  /*
   * 1. Codec Configured for attaching device
   * 2. QoS Configured State for attaching device
   * 3. Enable for attaching device
   * 3. Release for both
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(4);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  log::info("Block Enable Notification");
  PrepareEnableHandler(group, 0, false, false);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  log::info("Stop the stream");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  // Stop the stream
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  log::info("Inject Enabling Notification, don't create CIS");

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  ON_CALL(*mock_iso_manager_, EstablishCis).WillByDefault(Return());

  InjectEnablingStateFroActiveAses_andWait(group, lastDevice);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  // Check if group is still in Streaming state - it will change when Release
  // notification will arrive.
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info("Inject Release for a group");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectReleaseAndIdleStateForAGroup_andWait(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
}

TEST_F(StateMachineTest, testReleaseStreamWithLateAttachToStream_BeforeStreamingState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Scenario
   * 1. Start streaming
   * 2. Stop stream on one device
   * 3. Reconnect
   * 4. Trigger attach the stream
   * 6. StopStream while getting to Streaming state on attaching device
   * 7. Check that Attaching device will also go to IDLE
   */

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info("Stream is started for group {}, disconnect {}", group->group_id_,
            lastDevice->address_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  /* Set device is getting ready for the connection */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  log::info("Set device {} to CONNECTED state", lastDevice->address_);
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  /*
   * 1. Codec Configured for attaching device
   * 2. QoS Configured State for attaching device
   * 3. Enable for attaching device
   * 3. Release for both
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(4);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  log::info("Block Streaming Notification");

  PrepareEnableHandler(group, 0, true, false);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  log::info("Stop the stream");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  // Stop the stream
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  log::info("Inject Streaming Notification");

  InjectStreamingStateFroActiveAses_andWait(group, lastDevice);
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Check if group is still in Streaming state - it will change when Release
  // notification will arrive.
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  log::info("Inject Release for a group");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectReleaseAndIdleStateForAGroup_andWait(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
}

TEST_F(StateMachineTest, testAutonomousConfiguredAndAttachToStream) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /* Scenario
   * 1. Start streaming
   * 2. Stop stream on one device
   * 3. Reconnect
   * 4. Autonomous Configured state
   * 5. Make sure QoS Configure is not send out
   * 6. Trigger attach the stream
   * 7. Make sure stream is up
   */

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  /* Set device is getting ready for the connection */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  // Symulate remote autonomous CONFIGURE state
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  int num_of_notifications = 0;
  for (auto& ase : lastDevice->ases_) {
    if (ase.id == bluetooth::le_audio::types::ase::kAseIdInvalid) {
      continue;
    }
    log::error("ID : {},  status {}", ase.id, bluetooth::common::ToString(ase.state));
    num_of_notifications++;
    InjectAseStateNotification(&ase, lastDevice, group, ascs::kAseStateCodecConfigured,
                               &cached_codec_configuration_map_[ase.id]);
    break;
  }
  SyncOnMainLoop();

  ASSERT_EQ(num_of_notifications, 1);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  // Now device is connected. Attach it to the stream

  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testAttachDeviceToTheStream_autonomusQoSConfiguredState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         {.sink = std::vector<uint8_t>(1, media_ccid),
                          .source = std::vector<uint8_t>(1, media_ccid)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  for (auto& ase : lastDevice->ases_) {
    if (cached_remote_qos_configuration_for_ase_.count(&ase) > 0) {
      InjectAseStateNotification(&ase, lastDevice, group, ascs::kAseStateQoSConfigured,
                                 &(cached_remote_qos_configuration_for_ase_[&ase]));
    }
  }
  SyncOnMainLoop();

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testAttachDeviceToTheStream_remoteDoesNotResponseOnCodecConfig) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         {.sink = std::vector<uint8_t>(1, media_ccid),
                          .source = std::vector<uint8_t>(1, media_ccid)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info(" Inject ACL disconnection of last device {} ", lastDevice->address_);
  uint16_t conn_id = lastDevice->conn_id_;

  InjectAclDisconnected_andWait(group, lastDevice);

  log::info("Check if group keeps streaming");

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  log::info("Make sure ASE with disconnected CIS are not left in STREAMING");

  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  log::info(
          "Now, group is not yet in the streaming state. Let's simulated the other device got "
          "connected");

  lastDevice->conn_id_ = conn_id;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);

  log::info(" Block configured state");
  PrepareConfigureCodecHandler(group, 0, false, false);

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::info("Inject ACL disconnect and reconnect again");
  InjectAclDisconnected_andWait(group, lastDevice);
  lastDevice->conn_id_ = conn_id;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  log::info("allow codec configured state");
  PrepareConfigureCodecHandler(group);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testAttachDeviceToTheStreamDoNotAttach) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;

  while (leAudioDevice) {
    lastDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  InjectInitialIdleNotification(group);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Start the configuration and stream Media content
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  StopStream_onMainloop(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_FALSE(LeAudioGroupStateMachine::Get()->AttachToStream(group, lastDevice,
                                                               {.sink = {}, .source = {}}));
}

TEST_F(StateMachineTest, testReconfigureAfterLateDeviceAttached) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  while (leAudioDevice) {
    lastDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  InjectInitialIdleNotification(group);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  /* First device connected. Configure it to stream media */

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  types::BidirectionalPair<std::vector<uint8_t>> ccids_list = {.sink = {media_ccid},
                                                               .source = {media_ccid}};

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  /* Stop  the stream and let first device to stay in configured state (caching
   * is on)*/
  StopStream_onMainloop(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  /* Verify state in the configured state */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  /* Now when stream is stopped, connect second device. */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  group->UpdateAudioSetConfigurationCache(context_type);

  /* Start stream, make sure 2 devices are started. */

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that both devicse receives the right CCID list and both are
  // streaming
  auto ase = lastDevice->GetFirstActiveAse();

  // FIXME: No ASE was activated - that's bad
  ASSERT_NE(nullptr, ase);
  auto ccids = ase->metadata.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(nullptr, ase);
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testReconfigureAfterLateDeviceAttachedConversationalSwb) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  while (leAudioDevice) {
    lastDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  InjectInitialIdleNotification(group);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  /* First device connected. Configure it to stream media */
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  types::BidirectionalPair<std::vector<uint8_t>> ccids_list = {.sink = {media_ccid},
                                                               .source = {media_ccid}};

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  auto current_config = group->GetCachedConfiguration(context_type);
  ASSERT_NE(nullptr, current_config.get());

  // For the complete bonded group we always expect dual bidir SWB config
  ASSERT_TRUE(AudioSetConfigurationProvider::Get()->CheckConfigurationIsBiDirSwb(
          *current_config.get()));
  ASSERT_TRUE(AudioSetConfigurationProvider::Get()->CheckConfigurationIsDualBiDirSwb(
          *current_config.get()));

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  /* Stop  the stream and let first device to stay in configured state (caching
   * is on)*/
  StopStream_onMainloop(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  /* Verify state in the configured state */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  /* Now when stream is stopped, connect second device. */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  group->UpdateAudioSetConfigurationCache(context_type);

  /* Start stream, make sure 2 devices are started. */
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that both devicse receives the right CCID list and both are
  // streaming
  auto ase = lastDevice->GetFirstActiveAse();

  // No ASE was activated - that's bad
  ASSERT_NE(nullptr, ase);
  auto ccids = ase->metadata.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(nullptr, ase);
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);

  // For the complete bonded group we always expect dual bidir SWB config
  current_config = group->GetCachedConfiguration(context_type);
  ASSERT_NE(nullptr, current_config.get());
  ASSERT_TRUE(AudioSetConfigurationProvider::Get()->CheckConfigurationIsDualBiDirSwb(
          *current_config.get()));
}

TEST_F(StateMachineTestNoSwb, testReconfigureAfterLateDeviceAttachedConversationalNoSwb) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  while (leAudioDevice) {
    lastDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  InjectInitialIdleNotification(group);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  /* First device connected. Configure it to stream media */
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  types::BidirectionalPair<std::vector<uint8_t>> ccids_list = {.sink = {media_ccid},
                                                               .source = {media_ccid}};

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  auto current_config = group->GetCachedConfiguration(context_type);
  ASSERT_NE(nullptr, current_config.get());
  // With a single device there shall be no bidir SWB, as we expect the 2nd
  // device to join the stream seamlessly while dual bidir SWB is disabled.
  ASSERT_FALSE(AudioSetConfigurationProvider::Get()->CheckConfigurationIsBiDirSwb(
          *current_config.get()));
  ASSERT_FALSE(AudioSetConfigurationProvider::Get()->CheckConfigurationIsDualBiDirSwb(
          *current_config.get()));

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  /* Stop  the stream and let first device to stay in configured state (caching
   * is on)*/
  StopStream_onMainloop(group);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  /* Verify state in the configured state */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  /* Now when stream is stopped, connect second device. */
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  group->UpdateAudioSetConfigurationCache(context_type);

  /* Start stream, make sure 2 devices are started. */
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that both devicse receives the right CCID list and both are
  // streaming
  auto ase = lastDevice->GetFirstActiveAse();

  // No ASE was activated - that's bad
  ASSERT_NE(nullptr, ase);
  auto ccids = ase->metadata.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(nullptr, ase);
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);

  // With both devices we still should not get the dual bidir SWB configuration
  // as it is currently disabled.
  current_config = group->GetCachedConfiguration(context_type);
  ASSERT_NE(nullptr, current_config.get());
  ASSERT_FALSE(AudioSetConfigurationProvider::Get()->CheckConfigurationIsDualBiDirSwb(
          *current_config.get()));
}

TEST_F(StateMachineTest, testConfigurationForOneDeviceBonded) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;
  overrided_group_size_ = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare 1 bonded connected devices in a group
  auto group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  /* First device connected. Configure it to stream media */
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  types::BidirectionalPair<std::vector<uint8_t>> ccids_list = {.sink = {media_ccid},
                                                               .source = {media_ccid}};

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testConfigurationForOneDeviceBondedThenAttachSecondOne) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;
  overrided_group_size_ = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare 1 bonded connected devices in a group
  auto group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  /* First device connected. Configure it to stream media */
  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  types::BidirectionalPair<std::vector<uint8_t>> ccids_list = {.sink = {media_ccid},
                                                               .source = {media_ccid}};

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         ccids_list);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));
  // Bond another device
  auto second_device = AddDeviceToExistingGroup(leaudio_group_id, context_type);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  AttachToStream_onMainloop(group, second_device.get(), {.sink = {media_ccid}, .source = {}});
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStreamToGettingReadyDevice) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 666;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(call_context, call_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);

  // Simulate the 2nd device still getting ready
  auto* firstDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(firstDevice);
  secondDevice->SetConnectionState(DeviceConnectState::CONNECTED_BY_USER_GETTING_READY);

  group->UpdateAudioSetConfigurationCache(context_type);

  ASSERT_EQ(group->Size(), num_devices);
  ASSERT_EQ(1, group->NumOfConnected());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  /* Three Writes:
   * 1: Codec Config
   * 2: Codec QoS
   * 3: Enabling
   */
  // Expect actions only on the already prepared device
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and the stream
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state with one device still
  // being in the `CONNECTED_BY_USER_GETTING_READY` state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, testAttachDeviceToTheConversationalStream) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(call_context, call_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* firstDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);
  ASSERT_NE(nullptr, firstDevice);
  ASSERT_NE(nullptr, lastDevice);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);

  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(
                      _, dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionIn)))
          .Times(2);

  // Make sure the Out data path is set before we declare that we are ready
  {
    ::testing::InSequence seq;
    EXPECT_CALL(*mock_iso_manager_,
                SetupIsoDataPath(
                        UNIQUE_CIS_CONN_HANDLE(leaudio_group_id, 0),
                        dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionOut)))
            .Times(1);
    EXPECT_CALL(ase_ctp_handler, AseCtpReceiverStartReadyHandler(firstDevice, _, _, _)).Times(1);
  }
  {
    ::testing::InSequence seq;
    EXPECT_CALL(*mock_iso_manager_,
                SetupIsoDataPath(
                        UNIQUE_CIS_CONN_HANDLE(leaudio_group_id, 1),
                        dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionOut)))
            .Times(1);
    EXPECT_CALL(ase_ctp_handler, AseCtpReceiverStartReadyHandler(lastDevice, _, _, _)).Times(1);
  }

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Conversational content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Verify data path removal on the second bidirectional CIS
  EXPECT_CALL(
          *mock_iso_manager_,
          RemoveIsoDataPath(UNIQUE_CIS_CONN_HANDLE(leaudio_group_id, 1),
                            bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput |
                                    bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput))
          .Times(1);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_,
              SetupIsoDataPath(
                      _, dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionIn)))
          .Times(1);
  // Make sure the Out data path is set before we declare that we are ready
  {
    ::testing::InSequence seq;
    EXPECT_CALL(*mock_iso_manager_,
                SetupIsoDataPath(
                        UNIQUE_CIS_CONN_HANDLE(leaudio_group_id, 1),
                        dataPathDirIsEq(bluetooth::hci::iso_manager::kIsoDataPathDirectionOut)))
            .Times(1);
    EXPECT_CALL(ase_ctp_handler, AseCtpReceiverStartReadyHandler(lastDevice, _, _, _)).Times(1);
  }

  AttachToStream_onMainloop(group, lastDevice, {.sink = {call_ccid}, .source = {call_ccid}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), call_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = firstDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);

  // Make sure ASEs with reconnected CIS are in STREAMING state
  ASSERT_TRUE(lastDevice->HaveAllActiveAsesSameState(
          types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING));
}

TEST_F(StateMachineTest, ReconfigureGroupWhenSecondDeviceConnectsAndFirstIsInQoSConfiguredState) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /**
   * Scenario
   * 1. One set member is connected and configured to QoS
   * 2. Second set member connects and group is configured after that
   * 3. CIG does not need to change and test verifies that
   * 3. Execute StartStream and expect CIG to stay and both devices in STREAMING state.
   *
   */
  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* secondDevice = group->GetNextDevice(leAudioDevice);
  uint16_t stored_conn_id = secondDevice->conn_id_;

  log::info("Inject disconnect second device");
  InjectAclDisconnected_andWait(group, secondDevice);

  /* Three Writes:
   * 1. Codec configure
   * 2. Codec QoS
   * 3. Enable
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         {.sink = {}, .source = {}});

  /* Check if group has transitioned to a proper state */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info("Inject connecting second device");
  InjectAclConnected(group, secondDevice, stored_conn_id);

  PrepareEnableHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig).Times(0);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  /* Three Writes:
   * 1. Codec configure
   * 2. Codec QoS
   * 3. Enable
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(3);
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         {.sink = {}, .source = {}});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest,
       ReconfigureGroupWhenSecondDeviceConnectsAndFirstIsInQoSConfiguredState_changesToConv) {
  const auto context_type = kContextTypeMedia;
  const auto new_context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  /**
   * Scenario
   * 1. One set member is connected and configured to QoS
   * 2. Second set member connects and group is configured after that
   * 3. While being in QoS Configure state, the configuration changed to CONVERSTATIONAL
   * 4. Verify that CIG configuration is changed
   * 5. Execute StartStream and expect CIG reconfig and Streaming state.
   *
   */
  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  auto contexts = context_type | new_context_type;
  // Prepare multiple fake connected devices in a group. Use Conversational to make sure that device
  // has sufficient ASEs.
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, kContextTypeConversational,
                                             num_devices, contexts);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* secondDevice = group->GetNextDevice(leAudioDevice);
  uint16_t stored_conn_id = secondDevice->conn_id_;

  log::info("Inject disconnect second device");
  InjectAclDisconnected_andWait(group, secondDevice);

  /* Three Writes:
   * 1. Codec configure
   * 2. Codec QoS
   * 3. Enable
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(3);

  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)},
                         {.sink = {}, .source = {}});

  /* Check if group has transitioned to a proper state */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info("Inject connecting second device");
  InjectAclConnected(group, secondDevice, stored_conn_id);

  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_, RemoveCig).Times(1);
  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  /* Three Writes:
   * 1. Codec configure
   * 2. Codec QoS
   * 3. Enable
   * 4. Receiver Start Ready
   */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(4);
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(4);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, new_context_type,
                         {.sink = types::AudioContexts(new_context_type),
                          .source = types::AudioContexts(new_context_type)},
                         {.sink = {}, .source = {}});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, StartStreamAfterConfigureToQoS) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)},
                             {.sink = {}, .source = {}}, true);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StartStreamAfterConfigureToQoS_invalidateCacheInBetween) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3. Codec Configure - again due to invalided context
     * 4: Codec QoS
     * 5: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(5);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)},
                             {.sink = {}, .source = {}}, true);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  log::info("Block Codec Configured Notification");
  PrepareConfigureCodecHandler(group, 0, true, false);
  log::info("Block QoS Configured Notification");
  PrepareConfigureQosHandler(group, 0, true, false);

  log::debug("Invalidate cache before moving to STREAMING from QoS Configured");
  group->InvalidateCachedConfigurations(context_type);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  InjectCachedConfigurationForGroup_andWait(group);

  PrepareEnableHandler(group);
  InjectQoSConfigurationForGroupActiveAses_andWait(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, StartStreamAfterConfigureToQoS_UnknownMetatadaDuringConfiguration) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media context but with unknown metadata.
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(), .source = types::AudioContexts()},
                             {.sink = {}, .source = {}}, true);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration with updated metadata.
  types::AudioContexts metadata = types::AudioContexts(context_type);

  PrepareEnableHandlerWithMetadataverify(group, metadata);
  StartStream_onMainloop(group, context_type, {.sink = metadata, .source = metadata});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Verify that metadata were stored in the group object.
  auto group_metadata = group->GetMetadataContexts();
  ASSERT_EQ(group_metadata.sink, metadata);
  ASSERT_EQ(group_metadata.source, metadata);
}

TEST_F(StateMachineTest, StartStreamAfterConfigureToQoS_ConfigurationCaching) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialConfiguredNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)},
                             {.sink = {}, .source = {}}, true);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StopStreamAfterConfigureToQoS) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Release
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  group->SetPendingConfiguration();
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)},
                             {.sink = {}, .source = {}}, true);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  group->ClearPendingConfiguration();
  // Validate GroupStreamStatus (since caching is on CONFIGURE_AUTONOMOUS will be the last state)
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));

  // Start the configuration and stream Media content
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StartStreamBidirectional_enableOnlyRemoteSinkFirst) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionSink;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario e.g. LIVE but start with Remote SINK directions
   * 2. Enable Remote Source Directions
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling for Sink
     * 4. Enabling For Source
     * 5. Receiver Start Ready
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(5);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // validate group status. Times 2 is only because this unitest does not support mainloop.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure there are Active QOS Configured ASES in Remote Source Direction");
  ASSERT_EQ(group->GetActiveQoSConfiguredDirections(),
            bluetooth::le_audio::types::kLeAudioDirectionSource);

  log::debug("Enable Remote Source");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;
  EnableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSource);
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StartStreamBidirectional_enableOnlyRemoteSourceFirst) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionSource;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario e.g. LIVE but start with Remote Source directions
   * 2. Enable Remote Sink Directions
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling for Sink
     * 4. Enabling For Source
     * 5. Receiver Start Ready
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(5);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure there are Active QOS Configured ASES in Remote Sink Direction");
  ASSERT_EQ(group->GetActiveQoSConfiguredDirections(),
            bluetooth::le_audio::types::kLeAudioDirectionSink);

  log::debug("Enable Remote Sink");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;
  EnableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSink);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, StartStreamBidirectional_DisableAndEnableSink) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario for Live
   * 2. Disable Remote Sink Directions
   * 3. Enable Remote Sink Directions
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling Both Directions;
     * 4. Receiver Start Ready
     * 5. Disable Sink
     * 6, Enable Sink
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(6);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // validate group status. Times 2 is only because this unitest does not support mainloop.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure all directions are enabled");
  ASSERT_EQ(group->GetActiveEnabledDirections(), bluetooth::le_audio::types::kLeAudioDirectionBoth);

  log::debug("Disable Remote Sink");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionSource;
  DisableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSink);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure there are Active QOS Configured ASES in Remote Sink Direction");
  ASSERT_EQ(group->GetActiveQoSConfiguredDirections(),
            bluetooth::le_audio::types::kLeAudioDirectionSink);
  log::debug("Enable Remote Sink");

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;
  EnableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSink);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, StartStreamBidirectional_DisableAndEnableSource) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario for Live
   * 2. Disable Remote Source Directions
   * 3. Enable Remote Source Directions
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling Both Directions;
     * 4. Receiver Start Ready
     * 5. Disable Source
     * 6. ReceiverStopReady
     * 7, Enable Source
     * 8. ReceiverStartReady
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(8);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // validate group status. Times 2 is only because this unitest does not support mainloop.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure all directions are enabled");
  ASSERT_EQ(group->GetActiveEnabledDirections(), bluetooth::le_audio::types::kLeAudioDirectionBoth);

  log::debug("Disable Remote Sink");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionSink;
  DisableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSource);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure there are Active QOS Configured ASES in Remote Source Direction");
  ASSERT_EQ(group->GetActiveQoSConfiguredDirections(),
            bluetooth::le_audio::types::kLeAudioDirectionSource);
  log::debug("Enable Remote Source");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;
  EnableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSource);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, StartStreamBidirectional_DisableAndReleaseSource) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario for Live
   * 2. Disable Remote Source Directions
   * 3. Release whole stream
   * 4. Verify Streaming contexts types are cleared.
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling Both Directions;
     * 4. Receiver Start Ready
     * 5. Disable Remote Source
     * 6. ReceiverStopReady
     * 7, Release Source
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(7);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // validate group status. Times 2 is only because this unitest does not support mainloop.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  auto streaming_contexts = group->GetStreamingMetadataContexts();

  ASSERT_NE(streaming_contexts.sink, types::AudioContexts());
  ASSERT_NE(streaming_contexts.source, types::AudioContexts());

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure all directions are enabled");
  ASSERT_EQ(group->GetActiveEnabledDirections(), bluetooth::le_audio::types::kLeAudioDirectionBoth);

  log::debug("Disable Remote Source");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionSink;
  DisableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSource);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure there are Active QOS Configured ASES in Remote Source Direction");
  ASSERT_EQ(group->GetActiveQoSConfiguredDirections(),
            bluetooth::le_audio::types::kLeAudioDirectionSource);

  log::debug("Stop the stream, make sure streaming context is cleared");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(1);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS))
          .Times(1);
  StopStream_onMainloop(group);

  streaming_contexts = group->GetStreamingMetadataContexts();

  ASSERT_EQ(streaming_contexts.sink, types::AudioContexts());
  ASSERT_EQ(streaming_contexts.source, types::AudioContexts());

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, StartStreamBidirectional_QuickDisableAndEnableSink) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario for Live
   * 2. Disable Remote Sink Directions
   * 3. Enable Remote Sink Directions
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling Both Directions;
     * 4. Receiver Start Ready
     * 5. Disable Sink
     * 6, Enable Sink
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(6);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure all directions are enabled");
  ASSERT_EQ(group->GetActiveEnabledDirections(), bluetooth::le_audio::types::kLeAudioDirectionBoth);

  log::debug("Disable and Enable quickly the Remote Sink");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  DisableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSink);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StartStreamBidirectional_QuickDisableAndEnableSource) {
  const auto context_type = kContextTypeLive;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;
  enabled_directions_ = bluetooth::le_audio::types::kLeAudioDirectionBoth;

  /* Scenario:
   * 1. Start Streaming bidirectional scenario for Live
   * 2. Disable Remote Source Directions
   * 3. Quick Enable Remote Source Directions
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReceiverStopReady(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling Both Directions;
     * 4. Receiver Start Ready
     * 5. Disable Source
     * 6. ReceiverStopReady
     * 7, Enable Source
     * 8. ReceiverStartReady
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(8);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // validate group status. Times 2 is only because this unitest does not support mainloop.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::info("Start the configuration and stream LIVE content");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::debug("Make sure both directions are configured");
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [sink_is_enabled, source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(sink_is_enabled);
  ASSERT_TRUE(source_is_enabled);

  log::debug("Make sure all directions are enabled");
  ASSERT_EQ(group->GetActiveEnabledDirections(), bluetooth::le_audio::types::kLeAudioDirectionBoth);

  log::debug("Disable and quick enable Remote Source");
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  DisableStreamDirection_onMainloop(group, bluetooth::le_audio::types::kLeAudioDirectionSource);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, StartStreamAfterConfigure) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  group->SetPendingConfiguration();
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  group->ClearPendingConfiguration();
  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StartStreamCachedConfig) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec config
     * 2: Codec QoS (+1 after restart)
     * 3: Enabling (+1 after restart)
     * 4: Release (1)
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(6);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  // Start the configuration and stream Media content
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Restart stream
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, StartStreamCachedConfigReconfigInvalidBehavior) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  /* Scenario
   * 1. Start stream and stop stream so ASEs stays in Configured State
   * 2. Reconfigure ASEs localy, so the QoS parameters are zeroed
   * 3. Inject one ASE 2 to be in Releasing state
   * 4. Start stream and Incject ASE 1 to go into Codec Configured state
   * 5. IN such case CIG shall not be created and fallback to Release and
   * Configure stream should happen. Before fix CigCreate with invalid
   * parameters were called */
  ContentControlIdKeeper::GetInstance()->SetCcid(call_context, call_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReceiverStartReadyHandler(group);
  PrepareReleaseHandler(group);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  EXPECT_CALL(*mock_iso_manager_, CreateCig).Times(1);

  // Start the configuration and stream call content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  // Start the configuration and stream Media content
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  stop_inject_configured_ase_after_first_ase_configured_ = true;

  auto device = group->GetFirstDevice();
  int i = 0;
  for (auto& ase : device->ases_) {
    if (i++ == 0) {
      continue;
    }

    // Simulate autonomus release for one ASE
    InjectAseStateNotification(&ase, device, group, ascs::kAseStateReleasing, nullptr);
  }

  // Restart stream and expect it will not be created.
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(0);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(0);

  // Block the fallback Release which will happen when CreateCig will fail
  stay_in_releasing_state_ = true;

  // Start the configuration and stream Live content
  bool result = LeAudioGroupStateMachine::Get()->StartStream(
          group, kContextTypeLive,
          {.sink = types::AudioContexts(kContextTypeLive),
           .source = types::AudioContexts(kContextTypeLive)});

  // Group internally in releasing state. StartStrean should faile.
  ASSERT_FALSE(result);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, BoundedHeadphonesConversationalToMediaChannelCount_2) {
  const auto initial_context_type = kContextTypeConversational;
  const auto new_context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  sample_freq_ |= codec_specific::kCapSamplingFrequency48000Hz |
                  codec_specific::kCapSamplingFrequency32000Hz;
  additional_snk_ases = 3;
  additional_src_ases = 1;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);
  ContentControlIdKeeper::GetInstance()->SetCcid(call_context, call_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, initial_context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* 8 Writes:
     * 1: Codec config (+1 after reconfig)
     * 2: Codec QoS (+1 after reconfig)
     * 3: Enabling (+1 after reconfig)
     * 4: ReceiverStartReady (only for conversational)
     * 5: Release
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(8);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, initial_context_type,
                         {.sink = types::AudioContexts(initial_context_type),
                          .source = types::AudioContexts(initial_context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  auto current_config = group->GetCachedConfiguration(initial_context_type);
  ASSERT_NE(nullptr, current_config);
  ASSERT_EQ(1lu, current_config->confs.sink.size());
  ASSERT_EQ(1lu, current_config->confs.source.size());

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  // Start the configuration and stream Media content
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Restart stream
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, new_context_type,
                         {.sink = types::AudioContexts(new_context_type),
                          .source = types::AudioContexts(new_context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  current_config = group->GetCachedConfiguration(new_context_type);
  ASSERT_NE(nullptr, current_config);
  ASSERT_EQ(1lu, current_config->confs.sink.size());
  ASSERT_EQ(0lu, current_config->confs.source.size());
}

TEST_F(StateMachineTest, BoundedHeadphonesConversationalToMediaChannelCount_1_MonoMic) {
  const auto initial_context_type = kContextTypeConversational;
  const auto new_context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;
  // Single audio allocation for the mono source
  channel_allocations_source_ = ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontLeft;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel;

  sample_freq_ |= codec_specific::kCapSamplingFrequency48000Hz |
                  codec_specific::kCapSamplingFrequency32000Hz;
  additional_snk_ases = 3;
  additional_src_ases = 1;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);
  ContentControlIdKeeper::GetInstance()->SetCcid(call_context, call_ccid);

  // Prepare one fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, initial_context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  // Cannot verify here as we will change the number of ases on reconfigure
  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* 8 Writes:
     * 1: Codec config (+1 after reconfig)
     * 2: Codec QoS (+1 after reconfig)
     * 3: Enabling (+1 after reconfig)
     * 4: ReceiverStartReady (only for conversational)
     * 5: Release
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(8);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, initial_context_type,
                         {.sink = types::AudioContexts(initial_context_type),
                          .source = types::AudioContexts(initial_context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  auto current_config = group->GetCachedConfiguration(initial_context_type);
  ASSERT_NE(nullptr, current_config);
  // sink has two locations
  ASSERT_EQ(2lu, current_config->confs.sink.size());
  // source has a single location
  ASSERT_EQ(1lu, current_config->confs.source.size());

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  // Start the configuration and stream Media content
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Restart stream
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, new_context_type,
                         {.sink = types::AudioContexts(new_context_type),
                          .source = types::AudioContexts(new_context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  current_config = group->GetCachedConfiguration(new_context_type);
  ASSERT_NE(nullptr, current_config);
  ASSERT_EQ(2lu, current_config->confs.sink.size());
  ASSERT_EQ(0lu, current_config->confs.source.size());
}

TEST_F(StateMachineTest, DISABLED_BoundedHeadphonesConversationalToMediaChannelCount_1_StereoMic) {
  const auto initial_context_type = kContextTypeConversational;
  const auto new_context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;
  channel_allocations_source_ = ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                                ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontRight;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel;

  sample_freq_ |= codec_specific::kCapSamplingFrequency48000Hz |
                  codec_specific::kCapSamplingFrequency32000Hz;
  additional_snk_ases = 3;
  additional_src_ases = 1;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);
  ContentControlIdKeeper::GetInstance()->SetCcid(call_context, call_ccid);

  // Prepare one fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, initial_context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  // Cannot verify here as we will change the number of ases on reconfigure
  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* 8 Writes:
     * 1: Codec config (+1 after reconfig)
     * 2: Codec QoS (+1 after reconfig)
     * 3: Enabling (+1 after reconfig)
     * 4: ReceiverStartReady (only for conversational)
     * 5: Release
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(8);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, initial_context_type,
                         {.sink = types::AudioContexts(initial_context_type),
                          .source = types::AudioContexts(initial_context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  auto current_config = group->GetCachedConfiguration(initial_context_type);
  ASSERT_NE(nullptr, current_config);
  ASSERT_EQ(2lu, current_config->confs.sink.size());
  ASSERT_EQ(2lu, current_config->confs.source.size());

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));
  // Start the configuration and stream Media content
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  // Restart stream
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, new_context_type,
                         {.sink = types::AudioContexts(new_context_type),
                          .source = types::AudioContexts(new_context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  current_config = group->GetCachedConfiguration(new_context_type);
  ASSERT_NE(nullptr, current_config);
  ASSERT_EQ(2lu, current_config->confs.sink.size());
  ASSERT_EQ(0lu, current_config->confs.source.size());
}

TEST_F(StateMachineTest, lateCisDisconnectedEvent_DuringReconfiguration) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;

  /* Three Writes:
   * 1: Codec Config
   * 2: Codec QoS
   * 3: Enabling
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));
  expected_devices_written++;

  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  /* Prepare DisconnectCis mock to not symulate CisDisconnection */
  ON_CALL(*mock_iso_manager_, DisconnectCis).WillByDefault(Return());

  /* Do reconfiguration */
  group->SetPendingConfiguration();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS))
          .Times(0);
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, leAudioDevice, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, lateCisDisconnectedEvent_AutonomousConfigured) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;

  /* Three Writes:
   * 1: Codec Config
   * 2: Codec QoS
   * 3: Enabling
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));
  expected_devices_written++;

  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();

  /* Prepare DisconnectCis mock to not symulate CisDisconnection */
  ON_CALL(*mock_iso_manager_, DisconnectCis).WillByDefault(Return());

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_AUTONOMOUS));

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, leAudioDevice, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, lateCisDisconnectedEvent_Idle) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 1;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;

  /* Three Writes:
   * 1: Codec Config
   * 2: Codec QoS
   * 3: Enabling
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));
  expected_devices_written++;

  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  reset_mock_function_count_map();
  /* Prepare DisconnectCis mock to not symulate CisDisconnection */
  ON_CALL(*mock_iso_manager_, DisconnectCis).WillByDefault(Return());

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE))
          .Times(0);

  // Stop the stream
  StopStream_onMainloop(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, leAudioDevice, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, StreamReconfigureAfterCisLostTwoDevices) {
  auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  /* Prepare DisconnectCis mock to not symulate CisDisconnection */
  ON_CALL(*mock_iso_manager_, DisconnectCis).WillByDefault(Return());

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(3);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  context_type = kContextTypeMedia;
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  // Device disconnects due to timeout of CIS
  leAudioDevice = group->GetFirstDevice();
  while (leAudioDevice) {
    InjectCisDisconnected_andWait(group, leAudioDevice, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
    // Disconnect device
    InjectHciNotifyAclDisconnected_andWait(group, leAudioDevice);

    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  group->ReloadAudioLocations();
  group->ReloadAudioDirections();

  // Start conversational scenario
  leAudioDevice = group->GetFirstDevice();
  int device_cnt = num_devices;
  while (leAudioDevice) {
    leAudioDevice->conn_id_ = device_cnt--;
    leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED);
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  InjectInitialIdleNotification(group);

  group->ReloadAudioLocations();
  group->ReloadAudioDirections();

  leAudioDevice = group->GetFirstDevice();
  expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(4);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Conversational content
  context_type = kContextTypeConversational;
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(2, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StreamClearAfterReleaseAndConnectionTimeout) {
  auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  /* Scenario
  1. Streaming to 2 device
  2. Stream suspend
  3. One device got to IDLE
  4. Second device Connection Timeout
  */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(1);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto* firstDevice = leAudioDevice;
  auto* lastDevice = leAudioDevice;

  while (leAudioDevice) {
    lastDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  context_type = kContextTypeMedia;
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  /* Prepare release handler only for first device. */
  PrepareReleaseHandler(group, 0, false, firstDevice);
  StopStream_onMainloop(group);

  /* Second device will disconnect because of timeout. Do not bother
   * with remove data path response from the controller. In test we are doing it
   * in a test thread which breaks things. */
  ON_CALL(*mock_iso_manager_, RemoveIsoDataPath).WillByDefault(Return());
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, DisconnectGroupMemberWhileEnablingStream) {
  auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  /* Scenario
  1. Initiate streaming to 1 device but stay in QOS_CONFIGURED due to started enabling ASEs
  2. Second device is attached and immediately disconnected
  4. Groups should not go to IDLE as the first device is about to stream
  5. Continue streaming with the first device
  */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto* firstDevice = leAudioDevice;
  auto* lastDevice = leAudioDevice;

  while (leAudioDevice) {
    lastDevice = leAudioDevice;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }

  InjectInitialIdleNotification(group);

  // Start the configuration up to the ENABLING state
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  ASSERT_EQ(group->NumOfConnected(), 2);

  // Inject second device disconnection
  InjectAclDisconnected_andWait(group, lastDevice);

  // Expect the group to not go to IDLE, as the first device is enabling
  ASSERT_NE(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

  // Resume the interrupted enabling process
  InjectEnablingStateFroActiveAses_andWait(group, firstDevice);
  InjectStreamingStateFroActiveAses_andWait(group, firstDevice);

  // Verify we go to STREAMING
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, VerifyThereIsNoDoubleDataPathRemoval) {
  auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 4;
  const auto num_devices = 1;

  /* Symulate banded headphonse */
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  /* Scenario
  1. Phone call to 1 device
  2. Stop the stream
  3. Get both ASE sink and Source to releasing
  4. Verify only 1 RemoveDataPath is called
  */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);

  /*Test ends before full clean*/
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  /* Do not trigger any action on removeIsoData path.*/
  ON_CALL(*mock_iso_manager_, RemoveIsoDataPath).WillByDefault(Return());

  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
}

TEST_F(StateMachineTest, StreamStartWithDifferentContextFromConfiguredState) {
  auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             kContextTypeConversational | kContextTypeMedia);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(4);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  group->SetPendingConfiguration();
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  group->ClearPendingConfiguration();
  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  context_type = kContextTypeMedia;
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, StreamStartWithSameContextFromConfiguredStateButNewMetadata) {
  auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices,
                                             kContextTypeConversational | kContextTypeLive);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstActiveDevice = leAudioDevice;
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1. Codec configure
     * 2: Codec QoS
     * 3: Enabling
     */
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(4);
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER));

  // Start the configuration and stream Media content
  group->SetPendingConfiguration();
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  group->ClearPendingConfiguration();
  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  auto metadata_context_type = kContextTypeLive;
  types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {media_ccid},
                                                               .source = {media_ccid}};

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(metadata_context_type),
                          .source = types::AudioContexts(metadata_context_type)},
                         ccid_lists);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  // Verify that the joining device receives the right CCID list
  auto ccids = firstActiveDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());
}

TEST_F(StateMachineTest, testAttachDeviceToTheStreamCisFailure) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  do_not_send_cis_establish_event_ = true;

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testAttachDeviceToTheStreamDataPathFailure) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* fistDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  do_not_send_setup_iso_data_path_event_ = true;

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Verify that the joining device receives the right CCID list
  auto ccids = lastDevice->GetFirstActiveAse()->metadata.Find(
          bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccids.has_value());
  ASSERT_NE(std::find(ccids->begin(), ccids->end(), media_ccid), ccids->end());

  /* Verify that ASE of first device are still good*/
  auto ase = fistDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);
}

TEST_F(StateMachineTest, testAttachDeviceWhileSecondDeviceDisconnects) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* lastDevice;
  LeAudioDevice* firstDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enable
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  // Inject CIS and ACL disconnection of first device
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT);
  InjectAclDisconnected_andWait(group, lastDevice);

  log::info(" Device B - Disconnected ");

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // Set second device is connected now.
  lastDevice->conn_id_ = 3;
  lastDevice->SetConnectionState(DeviceConnectState::CONNECTED);

  // Make sure ASE with disconnected CIS are not left in STREAMING
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);
  ASSERT_EQ(lastDevice->GetFirstAseWithState(::bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING),
            nullptr);

  // Expect just Codec Configure on ASCS Control Point
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(1));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);

  // Remove Configuration incjection but cache configuration for future
  // injection
  PrepareConfigureCodecHandler(group, 0, true, false);

  log::info("Device B - Attaching to the stream");

  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  /* Verify that ASE of first device are still good*/
  auto ase = firstDevice->GetFirstActiveAse();
  ASSERT_NE(ase->qos_config.max_transport_latency, 0);
  ASSERT_NE(ase->qos_config.retrans_nb, 0);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::info("Device A is disconnecting while Device B is attaching to the stream");

  InjectCisDisconnected_andWait(group, firstDevice, HCI_ERR_CONNECTION_TOUT);
  InjectReleasingAndIdleState_andWait(group, firstDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(group->GetTargetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  ASSERT_EQ(group->cig.GetState(), types::CigState::CREATED);

  log::info("Device B continues configuration and streaming");

  // Expect QoS config and Enable on ASCS Control Point
  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(2));

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(1);

  InjectCachedConfigurationForActiveAses_andWait(group, lastDevice);

  // Check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(StateMachineTest, testAclDropWithoutApriorCisDisconnection) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice = leAudioDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  /* Separate CIS  for dual CIS device is treated as sink device */
  ASSERT_EQ(group->stream_conf.stream_params.sink.num_of_devices, 2);
  ASSERT_EQ(group->stream_conf.stream_params.sink.num_of_channels, 2);

  // Inject CIS and ACL disconnection of first device
  InjectAclDisconnected_andWait(group, firstDevice);

  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  InjectAclDisconnected_andWait(group, lastDevice);

  ASSERT_EQ(group->stream_conf.stream_params.sink.num_of_devices, 0);
  ASSERT_EQ(group->stream_conf.stream_params.sink.num_of_channels, 0);
}

TEST_F(StateMachineTest, testAutonomousDisableOneDeviceAndGoBackToStream_CisDisconnectedOnDisable) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();

  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Conversational content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  /* First timer started for transition to streaming state */
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info(" Phone call stream created");

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);

  /* First timer finished when group achieves streaming state */
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  /* Remote initiates autonomous Disable operation */
  auto ase = lastDevice->GetFirstActiveAseByDirection(
          ::bluetooth::le_audio::types::kLeAudioDirectionSink);

  log::info(" Inject ASE state changed to QoS for  {} ", lastDevice->address_);
  InjectAseStateNotification(ase, lastDevice, group, ascs::kAseStateQoSConfigured,
                             &cached_qos_configuration_map_[ase->id]);
  SyncOnMainLoop();
  /* No action on timer in this moment. */
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  log::info(" Disconnect CIS for   {} ", lastDevice->address_);
  // Inject CIS disconnection of first device, check that group keeps streaming
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_PEER_USER);

  /* First device keeps streaming */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::info(" {} should have all ASEs in QoS State ", lastDevice->address_);
  /* Now lets try to attach the device back to the stream (Enabling and Receiver
   * Start ready to be called)*/

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(2);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(2);

  log::info(" Attach {} to the stream, need to establish CIS", lastDevice->address_);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  ase = lastDevice->GetFirstActiveAse();
  ASSERT_EQ(ase->state, types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testAutonomousDisableOneDeviceAndGoBackToStream_CisConnectedOnDisable) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();

  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Conversational content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  /* First timer started for transition to streaming state */
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info(" Phone call stream created");

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);

  /* First timer finished when group achieves streaming state */
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  /* Remote initiates autonomous Disable operation */
  auto ase = lastDevice->GetFirstActiveAseByDirection(
          ::bluetooth::le_audio::types::kLeAudioDirectionSink);

  log::info(" Inject ASE state changed to QoS for  {} ", lastDevice->address_);
  InjectQoSConfigurationForActiveAses_andWait(group, lastDevice);

  /* No action on timer in this moment. */
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  /* First device keeps streaming */
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::info(" {} should have all ASEs in QoS State ", lastDevice->address_);

  ase = lastDevice->GetFirstActiveAse();
  ASSERT_TRUE(ase != nullptr);

  group->PrintDebugState();

  /* Now lets try to attach the device back to the stream (Enabling and Receiver
   * Start ready to be called)*/

  EXPECT_CALL(gatt_queue, WriteCharacteristic(lastDevice->conn_id_, lastDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(2);

  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);

  log::info(" Attach {} to the stream, need to establish CIS", lastDevice->address_);
  AttachToStream_onMainloop(group, lastDevice, {.sink = {media_ccid}, .source = {}});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  ase = lastDevice->GetFirstActiveAse();
  ASSERT_TRUE(ase != nullptr);
  ASSERT_EQ(ase->state, types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testAutonomousDisable_GoToIdle) {
  const auto context_type = kContextTypeConversational;
  const auto leaudio_group_id = 6;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);
  PrepareReceiverStartReadyHandler(group);

  auto* leAudioDevice = group->GetFirstDevice();
  LeAudioDevice* firstDevice = leAudioDevice;
  LeAudioDevice* lastDevice;

  auto expected_devices_written = 0;
  while (leAudioDevice) {
    /* Three Writes:
     * 1: Codec Config
     * 2: Codec QoS
     * 3: Enabling
     */
    lastDevice = leAudioDevice;
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Conversational content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  log::info(" group {} is streaming ", group->group_id_);

  /* First timer started for transition to streaming state */
  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);

  log::info(" Incjecting QoS configured for  {} ", lastDevice->address_);

  /* Remote initiates autonomous Disable operation */
  InjectQoSConfigurationForActiveAses_andWait(group, lastDevice);

  // Check if group still streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  log::info("{} in QoS configured state, disconnect CIS ", lastDevice->address_);

  // Validate GroupStreamStatus or maybe update CIS should be called

  /* Inject CIS disconnection of first device, disconnect only first CIS because
   * while processing first disconnection test will try to bring up this ASEs
   * to STREAMING state and connect CISes again.
   */
  InjectCisDisconnected_andWait(group, lastDevice, HCI_ERR_CONNECTION_TOUT, true);

  // Check if group still streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  log::info("{} in QoS configured state ", lastDevice->address_);
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::info(" device {} also goes to QoS state ", firstDevice->address_);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(1);

  InjectQoSConfigurationForActiveAses_andWait(group, firstDevice);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStopStreamBeforeCodecConfigureIsArrived) {
  /* Device is banded headphones with 1x snk + 0x src ase
   * (1xunidirectional CIS with channel count 2 for stereo)
   */
  const auto context_type = kContextTypeRingtone;
  const int leaudio_group_id = 4;
  channel_count_ = kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel;

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);

  auto* leAudioDevice = group->GetFirstDevice();

  /*
   * 1 - Configure ASE
   * 2 - Release ASE (we are not Release in such a case)
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(1);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  // Validate GroupStreamStatus and we should just received IDLE state as There is no Release CMD
  // sent to the remote
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::RELEASING))
          .Times(0);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::IDLE));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Stop the stream before Codec Configured arrived
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&gatt_queue);

  InjectCachedConfigurationForActiveAses_andWait(group, leAudioDevice);
  InjectReleaseAndIdleStateForAGroup_andWait(group);

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));
}

TEST_F(StateMachineTest, testAutonomousReleaseFromEnablingState) {
  const auto context_type = kContextTypeMedia;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  auto* earbudLeft = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudLeft->conn_id_, earbudLeft->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  auto* earbudRight = group->GetNextDevice(earbudLeft);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudRight->conn_id_, earbudRight->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  // let us decide when the HCI Disconnection Complete event and HCI Connection
  // Established events will be reported
  do_not_send_cis_disconnected_event_ = true;
  do_not_send_cis_establish_event_ = true;

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, true, /* inject_streaming */ false);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  log::debug("[TESTING] StartStream action initiated by upper layer");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  log::debug("[TESTING] left earbud indicates there are no available context at the time");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  auto* earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudLeftAse == nullptr);

  // make sure the ASE is in correct state, required in this scenario
  ASSERT_TRUE(earbudLeftAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

  log::debug("[TESTING] left earbud performs autonomous ASE state transition to Releasing state");
  InjectAseStateNotification(earbudLeftAse, earbudLeft, group, ascs::kAseStateReleasing, nullptr);

  log::debug(
          "[TESTING] left earbud performs autonomous ASE state transition to Codec Configured "
          "state (caching)");
  auto* codec_configured_params = &cached_codec_configuration_map_[earbudLeftAse->id];
  InjectAseStateNotification(earbudLeftAse, earbudLeft, group, ascs::kAseStateCodecConfigured,
                             codec_configured_params);
  SyncOnMainLoop();
  auto* earbudRightAse = earbudRight->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudRightAse == nullptr);

  // make sure the ASE is in correct state, required in this scenario
  ASSERT_TRUE(earbudRightAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

  bluetooth::hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt = {
          .status = 0,
          .cig_id = group_id,
          .cis_conn_hdl = earbudRightAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports right earbud CIS has been successfully established");
  InjectHciNotifyCisEstablished(group, earbudRight, cis_establish_evt);

  std::vector<uint8_t> streaming_params{};
  log::debug("[TESTING] InjectAseStateNotification earbudRight kAseStateStreaming");
  InjectAseStateNotification(earbudRightAse, earbudRight, group, ascs::kAseStateStreaming,
                             &streaming_params);
  SyncOnMainLoop();
  bluetooth::hci::iso_manager::cis_disconnected_evt cis_disconnected_evt = {
          .reason = HCI_ERR_PEER_USER,
          .cig_id = group_id,
          .cis_conn_hdl = earbudLeftAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports left earbud CIS has been disconnected");
  InjectHciNotifyCisDisconnected(group, earbudLeft, cis_disconnected_evt);

  // check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  log::debug("[TESTING] the available contexts are back");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  // reset the handlers to default
  PrepareEnableHandler(group);
  do_not_send_cis_establish_event_ = false;
  do_not_send_cis_disconnected_event_ = false;

  log::debug("[TESTING] once the contexts are back, the upper layer calls AttachToStream");
  AttachToStream_onMainloop(group, earbudLeft, {.sink = {media_ccid}, .source = {}});

  // check if group keeps streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  log::debug("[TESTING] check if both are streaming");
  earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudLeftAse == nullptr);
  ASSERT_TRUE(earbudLeftAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  earbudRightAse = earbudRight->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudRightAse == nullptr);
  ASSERT_TRUE(earbudRightAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testLateSetupIsoDatPathCompleteEvent) {
  const auto context_type = kContextTypeRingtone;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  /**
   * Scenario:
   * 1. Having set of 2 devices start streaming to 1 device.
   * 2. Verify the group is streaming.
   * 3. Attach the other device.
   * 4. Device sends ASE Streaming state notification.
   * 5. The Data Path is not been set up yet, so the StatusReportCb is not called yet.
   * 6. Once the Data Path is set up, the StatusReportCb is called so that the new configuration is
   *    applied.
   */

  log::debug("[TESTING] Prepare 2 fake connected devices in a group");
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_NE(nullptr, group);
  ASSERT_EQ(group->Size(), num_devices);

  auto* firstDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));
  ASSERT_NE(nullptr, firstDevice);

  auto* secondDevice = group->GetNextDevice(firstDevice);
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  log::debug("[TESTING] firstDevice notifies there are no available context at the time");
  DeviceContextsUpdate(firstDevice, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, true, /* inject_streaming */ true);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  // StartStream action initiated by upper layer
  log::debug("[TESTING] StartStream. Expect STREAMING state to be not reported");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // let us decide when the ISO Data Path Setup Complete event
  do_not_send_setup_iso_data_path_event_ = true;

  uint16_t cis_conn_handle;
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(::testing::_, ::testing::_))
          .WillOnce(::testing::SaveArg<0>(&cis_conn_handle));

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(0);

  log::debug("[TESTING] firstDevice notifies the available context are back");
  DeviceContextsUpdate(firstDevice, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  log::debug("[TESTING] ProcessHciNotifSetupIsoDataPath. Expect StatusReportCb to be not called");
  AttachToStream_onMainloop(group, firstDevice, {.sink = {media_ccid}, .source = {}});

  Mock::VerifyAndClearExpectations(&mock_callbacks_);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  log::debug("[TESTING] ProcessHciNotifSetupIsoDataPath. Expect StatusReportCb to be called");
  InjectHciNotifySetupIsoDataPath(group, firstDevice, 0, cis_conn_handle);
}

TEST_F(StateMachineTest, testRemoveIsoDataPathOnCisDisconnection) {
  const auto context_type = kContextTypeRingtone;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  /**
   * Scenario:
   * 1. Having set of 2 devices start streaming to 1 device.
   * 2. Verify the group is streaming.
   * 3. Attach the other device.
   * 4. Central successfully creates CIS and issues HCI LE Setup ISO Data Path command.
   * 5. The Data Path is not been set up yet. while Peripheral notifies ASE Releasing state.
   * 6. The Central disconnects CIS and issues HCI LE Remove ISO Data Path command.
   */

  log::debug("[TESTING] Prepare 2 fake connected devices in a group");
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_NE(nullptr, group);
  ASSERT_EQ(group->Size(), num_devices);

  auto* firstDevice = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue, WriteCharacteristic(firstDevice->conn_id_, firstDevice->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));
  ASSERT_NE(nullptr, firstDevice);

  auto* secondDevice = group->GetNextDevice(firstDevice);
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(secondDevice->conn_id_, secondDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  log::debug("[TESTING] firstDevice notifies there are no available context at the time");
  DeviceContextsUpdate(firstDevice, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, /* inject_enabling */ true, /* inject_streaming */ true);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  // StartStream action initiated by upper layer
  log::debug("[TESTING] StartStream. Expect STREAMING state to be not reported");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  PrepareEnableHandler(group, 0, /* inject_enabling */ true, /* inject_streaming */ false);

  uint16_t cis_conn_handle;
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(::testing::_, ::testing::_))
          .WillOnce(::testing::SaveArg<0>(&cis_conn_handle));
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(1);

  log::debug("[TESTING] firstDevice notifies the available context are back");
  DeviceContextsUpdate(firstDevice, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  log::debug("[TESTING] ProcessHciNotifSetupIsoDataPath. Expect StatusReportCb to be not called");
  AttachToStream_onMainloop(group, firstDevice, {.sink = {media_ccid}, .source = {}});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  auto* firstDeviceAse = firstDevice->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_NE(nullptr, firstDeviceAse);

  /* Expect the Data Path in CONFIGURING state */
  ASSERT_EQ(types::DataPathState::CONFIGURING, firstDeviceAse->data_path_state);

  // let us decide when the ISO Data Path Setup Complete and CIS Disconnection Complete event
  do_not_send_setup_iso_data_path_event_ = true;
  do_not_send_remove_iso_data_path_event_ = true;
  do_not_send_cis_disconnected_event_ = true;

  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(cis_conn_handle, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(cis_conn_handle, _)).Times(1);

  log::debug("[TESTING] first device performs autonomous ASE state transition to Releasing state");
  InjectAseStateNotification(firstDeviceAse, firstDevice, group, ascs::kAseStateReleasing, nullptr);
  SyncOnMainLoop();
  log::debug("[TESTING] ProcessHciNotifSetupIsoDataPath");
  InjectHciNotifySetupIsoDataPath(group, firstDevice, 0, cis_conn_handle);
  SyncOnMainLoop();
  bluetooth::hci::iso_manager::cis_disconnected_evt cis_disconnected_evt = {
          .reason = HCI_ERR_PEER_USER,
          .cig_id = group_id,
          .cis_conn_hdl = firstDeviceAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports first device CIS has been disconnected eventually");
  InjectHciNotifyCisDisconnected(group, firstDevice, cis_disconnected_evt);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::debug("[TESTING] ProcessHciNotifRemoveIsoDataPath");
  InjectHciNotifyRemoveIsoDataPath(group, firstDevice, 0, cis_conn_handle);
  SyncOnMainLoop();
  ASSERT_EQ(types::DataPathState::IDLE, firstDeviceAse->data_path_state);
}

TEST_F(StateMachineTest, testDoNotQoSConfiguredIfNotStreaming) {
  const auto context_type = kContextTypeMedia;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  auto* earbudLeft = group->GetFirstDevice();
  auto* earbudRight = group->GetNextDevice(earbudLeft);

  log::debug("[TESTING] Inject initial ASE state notification");
  InjectInitialConfiguredNotification(group);

  log::debug("[TESTING] right earbud indicates there are available context");
  DeviceContextsUpdate(earbudRight, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  log::debug("[TESTING] left earbud indicates there are no available context at the time");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, /* inject_enabling */ true, /* inject_streaming */ true);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  log::debug("[TESTING] StartStream action initiated by upper layer");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // check if group is streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // make sure the ASEs is in correct state, required in this scenario
  auto* earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_EQ(nullptr, earbudLeftAse);
  auto* earbudRightAse = earbudRight->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_NE(nullptr, earbudRightAse);

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::debug("[TESTING] left earbud available contexts are back");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  PrepareConfigureCodecHandler(group, 0, false, /* inject_configured */ false);
  PrepareReleaseHandler(group, 0, false, nullptr, /* inject_releasing */ false);

  log::debug("[TESTING] AttachToStream, start Codec Configure procedure.");
  AttachToStream_onMainloop(group, earbudLeft, {.sink = {media_ccid}, .source = {}});

  earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudLeftAse == nullptr);

  log::debug("[TESTING] Upper Layer stop the stream in the meantime");
  StopStream_onMainloop(group);

  Mock::VerifyAndClearExpectations(&gatt_queue);

  log::debug("[TESTING] Expect the stack will not QoS configure as the stream is about to stop");
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudLeft->conn_id_, earbudLeft->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  log::debug("[TESTING] left earbud notifies ASE Codec Configured state, as expected");
  auto* codec_configured_params = &cached_codec_configuration_map_[earbudRightAse->id];
  InjectAseStateNotification(earbudLeftAse, earbudLeft, group, ascs::kAseStateCodecConfigured,
                             codec_configured_params);
}

TEST_F(StateMachineTest, testUnexpectedCisEstablishedEvent) {
  const auto context_type = kContextTypeMedia;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  /**
   * Scenario:
   * 1. Start stream to set of 2 devices
   * 2. The CIS Create is issued to 2 devices
   * 3. One of the devices reports Releasing state before CIS Established event.
   * 4. Phone Cancel the CIS Create by sending HCI Disconnect
   * 5. The scheduled CIS Established event is reported by the controller.
   * 5. The CIS Disconnection Complete event is reported later on.
   * 6. Verify we keep streaming.
   */

  // Prepare multiple fake connected devices in a group
  log::debug("[TESTING] PrepareSingleTestDeviceGroup");
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  log::debug("[TESTING] group->GetFirstDevice()");
  auto* earbudLeft = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudLeft->conn_id_, earbudLeft->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  log::debug("[TESTING] group->GetNextDevice(earbudLeft)");
  auto* earbudRight = group->GetNextDevice(earbudLeft);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudRight->conn_id_, earbudRight->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  // let us decide when the HCI Disconnection Complete event and HCI Connection
  // Established events will be reported
  do_not_send_cis_disconnected_event_ = true;
  do_not_send_cis_establish_event_ = true;

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, true, /* inject_streaming */ false);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  // StartStream action initiated by upper layer
  log::debug("[TESTING] StartStream");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  log::debug("[TESTING] left earbud indicates there are no available context at the time");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  log::debug("[TESTING] GetFirstActiveAseByDirection earbudLeftAse");
  auto* earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudLeftAse == nullptr);

  // make sure the ASE is in correct state, required in this scenario
  ASSERT_TRUE(earbudLeftAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

  log::debug("[TESTING] left earbud performs autonomous ASE state transition to Releasing state");
  InjectAseStateNotification(earbudLeftAse, earbudLeft, group, ascs::kAseStateReleasing, nullptr);
  SyncOnMainLoop();
  //
  log::debug(
          "[TESTING] left earbud performs autonomous ASE state transition to Codec Configured "
          "state (caching)");
  auto* codec_configured_params = &cached_codec_configuration_map_[earbudLeftAse->id];
  InjectAseStateNotification(earbudLeftAse, earbudLeft, group, ascs::kAseStateCodecConfigured,
                             codec_configured_params);
  SyncOnMainLoop();
  log::debug("[TESTING] GetFirstActiveAseByDirection earbudRightAse");
  auto* earbudRightAse = earbudRight->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudRightAse == nullptr);

  // make sure the ASE is in correct state, required in this scenario
  ASSERT_TRUE(earbudRightAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

  bluetooth::hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt = {
          .status = 0,
          .cig_id = group_id,
          .cis_conn_hdl = earbudRightAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports right earbud CIS has been successfully established");
  InjectHciNotifyCisEstablished(group, earbudRight, cis_establish_evt);
  SyncOnMainLoop();
  std::vector<uint8_t> streaming_params{};
  log::debug("[TESTING] InjectAseStateNotification earbudRight kAseStateStreaming");
  InjectAseStateNotification(earbudRightAse, earbudRight, group, ascs::kAseStateStreaming,
                             &streaming_params);
  SyncOnMainLoop();
  cis_establish_evt = {
          .status = 0,
          .cig_id = group_id,
          .cis_conn_hdl = earbudLeftAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports left earbud CIS has been successfully established");
  InjectHciNotifyCisEstablished(group, earbudLeft, cis_establish_evt);
  SyncOnMainLoop();
  bluetooth::hci::iso_manager::cis_disconnected_evt cis_disconnected_evt = {
          .reason = HCI_ERR_PEER_USER,
          .cig_id = group_id,
          .cis_conn_hdl = earbudLeftAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports left earbud CIS has been disconnected eventually");
  InjectHciNotifyCisDisconnected(group, earbudLeft, cis_disconnected_evt);
  SyncOnMainLoop();
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testKeepStreamingWhenCisCreateOperationCancelled) {
  const auto context_type = kContextTypeMedia;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  auto* earbudLeft = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudLeft->conn_id_, earbudLeft->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  auto* earbudRight = group->GetNextDevice(earbudLeft);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudRight->conn_id_, earbudRight->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  log::debug("[TESTING] right earbud indicates there are available context");
  DeviceContextsUpdate(earbudRight, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  log::debug("[TESTING] left earbud indicates there are no available context at the time");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, /* inject_enabling */ true, /* inject_streaming */ true);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  log::debug("[TESTING] StartStream action initiated by upper layer");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // check if group is streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  // make sure the ASEs is in correct state, required in this scenario
  auto* earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_TRUE(earbudLeftAse == nullptr);
  auto* earbudRightAse = earbudRight->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudRightAse == nullptr);
  ASSERT_TRUE(earbudRightAse->state == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  log::debug("[TESTING] the available contexts are back");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  // let us decide when the HCI Disconnection Complete event and HCI Connection
  // Established events will be reported
  do_not_send_cis_disconnected_event_ = true;
  do_not_send_cis_establish_event_ = true;

  PrepareEnableHandler(group, 0, /* inject_enabling */ true, /* inject_streaming */ false);

  log::debug("[TESTING] once the contexts are back, the upper layer calls AttachToStream");
  AttachToStream_onMainloop(group, earbudLeft, {.sink = {media_ccid}, .source = {}});

  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(1);

  earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudLeftAse == nullptr);

  log::debug("[TESTING] left earbud performs autonomous ASE state transition to Releasing state ");
  InjectAseStateNotification(earbudLeftAse, earbudLeft, group, ascs::kAseStateReleasing, nullptr);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_iso_manager_);

  log::debug("[TESTING] Expect the CIS cancelled operation won't trigger stack to stop streaming");

  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  bluetooth::hci::iso_manager::cis_establish_cmpl_evt cis_establish_evt = {
          .status = 0x44,
          .cig_id = group_id,
          .cis_conn_hdl = earbudLeftAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports left earbud CIS establishment has been cancelled");
  InjectHciNotifyCisEstablished(group, earbudLeft, cis_establish_evt);
  SyncOnMainLoop();
  bluetooth::hci::iso_manager::cis_disconnected_evt cis_disconnected_evt = {
          .reason = HCI_ERR_PEER_USER,
          .cig_id = group_id,
          .cis_conn_hdl = earbudLeftAse->cis_conn_hdl,
  };
  log::debug("[TESTING] controller reports first device CIS has been disconnected");
  InjectHciNotifyCisDisconnected(group, earbudLeft, cis_disconnected_evt);
  SyncOnMainLoop();
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testDoNotCodecConfigureDeviceWithoutContextsAvailable) {
  const auto context_type = kContextTypeMedia;
  const auto audio_contexts = types::AudioContexts(context_type);
  const auto group_id = 4;
  const auto num_devices = 2;

  ContentControlIdKeeper::GetInstance()->SetCcid(media_context, media_ccid);

  /**
   * Scenario:
   * 1. Have a set of 2 devices, including one that is not available for stream
   *    (available contexts are 0).
   * 2. Start streaming.
   * 3. Verify only one device (available for stream) is active.
   * 4. Verify the group is streaming.
   */

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  auto* earbudLeft = group->GetFirstDevice();
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudLeft->conn_id_, earbudLeft->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(0);

  auto* earbudRight = group->GetNextDevice(earbudLeft);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(earbudRight->conn_id_, earbudRight->ctp_hdls_.val_hdl,
                                              _, GATT_WRITE_NO_RSP, _, _))
          .Times(AtLeast(3));

  log::debug("[TESTING] Inject initial ASE state notification");
  InjectInitialConfiguredNotification(group);

  log::debug("[TESTING] right earbud indicates there are available context");
  DeviceContextsUpdate(earbudRight, types::kLeAudioDirectionSink, audio_contexts, audio_contexts);

  log::debug("[TESTING] left earbud indicates there are no available context at the time");
  DeviceContextsUpdate(earbudLeft, types::kLeAudioDirectionSink, types::AudioContexts(),
                       audio_contexts);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group, 0, /* inject_enabling */ true, /* inject_streaming */ true);
  PrepareDisableHandler(group);
  PrepareReleaseHandler(group);

  log::debug("[TESTING] StartStream action initiated by upper layer");
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // make sure the ASEs is in correct state, required in this scenario
  auto* earbudLeftAse = earbudLeft->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_TRUE(earbudLeftAse == nullptr);
  auto* earbudRightAse = earbudRight->GetFirstActiveAseByDirection(types::kLeAudioDirectionSink);
  ASSERT_FALSE(earbudRightAse == nullptr);

  // check if group is streaming
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
}

TEST_F(StateMachineTest, testStreamMultipleDsa) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 4;
  const auto num_devices = 2;

  // Prepare multiple connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  // Set the DSA mode and force refreshing the MEDIA scenario config
  group->InvalidateCachedConfigurations(context_type);
  group->dsa_.mode = DsaMode::ISO_SW;

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(AtLeast(1));
  // Called 4 times: 2 devices x 2 directions
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(4);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveCig(_, _)).Times(0);

  // Warning: Google specific metadata is similar to LTV format but the length field does not count
  // the type octet!
  auto const dsa_vendor_metadata = std::vector<uint8_t>{
          0x01,  // Length: 1 (see the Warning above!)
          0x01,  // Type: Headtracking codec supported transport parameter
          types::kLeAudioMetadataHeadtrackerTransportLeAcl |
                  types::kLeAudioMetadataHeadtrackerTransportLeIso,  // Value: Headtracking
                                                                     // supported transports bitmask
  };
  auto dsa_pac = types::acs_ac_record{
          .codec_id = types::kLeAudioCodecHeadtracking,
  };
  dsa_pac.metadata.Add(types::kLeAudioMetadataTypeVendorSpecific,
                       types::kLeAudioVendorCompanyIdGoogle, dsa_vendor_metadata);

  auto* leAudioDevice = group->GetFirstDevice();
  auto expected_devices_written = 0;
  while (leAudioDevice) {
    EXPECT_CALL(gatt_queue,
                WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                    GATT_WRITE_NO_RSP, _, _))
            .Times(AtLeast(3));
    expected_devices_written++;
    ASSERT_NE(leAudioDevice->snk_pacs_.size(), 0lu);

    // Apply the DSA pac record
    if (leAudioDevice->snk_pacs_.size()) {
      auto& dest_pac_hdl_value_tuple = std::get<1>(*leAudioDevice->snk_pacs_.rbegin());
      auto new_pac_hdl_value_tuple = dest_pac_hdl_value_tuple;
      new_pac_hdl_value_tuple.push_back(dsa_pac);
      leAudioDevice->RegisterPACs(&dest_pac_hdl_value_tuple, &new_pac_hdl_value_tuple);
    }
    if (leAudioDevice->src_pacs_.size()) {
      auto& dest_pac_hdl_value_tuple = std::get<1>(*leAudioDevice->src_pacs_.rbegin());
      auto new_pac_hdl_value_tuple = dest_pac_hdl_value_tuple;
      new_pac_hdl_value_tuple.push_back(dsa_pac);
      leAudioDevice->RegisterPACs(&dest_pac_hdl_value_tuple, &new_pac_hdl_value_tuple);
    }
    leAudioDevice = group->GetNextDevice(leAudioDevice);
  }
  ASSERT_EQ(expected_devices_written, num_devices);

  InjectInitialIdleNotification(group);
  SyncOnMainLoop();
  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  // Check if the config has DSA channel configuration
  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  ASSERT_TRUE(group_config->hasDsaBackChannel());

  // Verify that the CIG has proper parameters for the back channel
  ASSERT_NE(last_cig_params_.sdu_itv_p_to_c, 0lu);
  ASSERT_NE(last_cig_params_.max_trans_lat_p_to_c, 0lu);
  for (auto const& cfg : last_cig_params_.cis_cfgs) {
    ASSERT_NE(cfg.max_sdu_size_p_to_c, 0lu);
    ASSERT_NE(cfg.rtn_p_to_c, 0lu);
  }

  // Verify data path
  ASSERT_EQ(last_datapath_params_.data_path_dir,
            bluetooth::hci::iso_manager::kIsoDataPathDirectionOut);
  ASSERT_EQ(last_datapath_params_.codec_id_format, types::kLeAudioCodingFormatVendorSpecific);
  ASSERT_EQ(last_datapath_params_.codec_id_company, types::kLeAudioVendorCompanyIdGoogle);
  ASSERT_EQ(last_datapath_params_.codec_id_vendor, types::kLeAudioVendorCodecIdHeadtracking);
}

TEST_F(StateMachineTest, testStreamFailureOnCigCreateDueToSduInterval) {
  const auto context_type = kContextTypeMedia;
  const int leaudio_group_id = 4;

  // Just for testing to trigger CIG Create failure.
  setTestSduInterval(0);

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AnyNumber());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              OnStateMachineInvalidStatusCb(leaudio_group_id,
                                            StateMachineInvalidStatus::FAILED_TO_CREATE_CIG));
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStreamFailureOnCigCreateWhenSduIntevalIsInvalidForOneDirection) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  // Just for testing to trigger CIG Create failure.
  setTestSduInterval(0, types::kLeAudioDirectionSink);

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AnyNumber());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              OnStateMachineInvalidStatusCb(leaudio_group_id,
                                            StateMachineInvalidStatus::FAILED_TO_CREATE_CIG));
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStreamFailureOnCigCreateDueToMaxSdu) {
  const auto context_type = kContextTypeMedia;
  const int leaudio_group_id = 4;

  // Just for testing to trigger CIG Create failure.
  setTestMaxSdu(0);

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AnyNumber());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              OnStateMachineInvalidStatusCb(leaudio_group_id,
                                            StateMachineInvalidStatus::FAILED_TO_CREATE_CIG));
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStreamFailureOnCigCreateWhenMaxSduIsInvalidForOneDirection) {
  const auto context_type = kContextTypeConversational;
  const int leaudio_group_id = 4;

  // Just for testing to trigger CIG Create failure.
  setTestMaxSdu(0, types::kLeAudioDirectionSink);

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AnyNumber());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              OnStateMachineInvalidStatusCb(leaudio_group_id,
                                            StateMachineInvalidStatus::FAILED_TO_CREATE_CIG));
  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testStreamPreferredPresentationDelay) {
  auto context_type = kContextTypeConversational;
  int leaudio_group_id = 4;
  uint32_t preferred_remote_sink_delay = 0xAAAAAA;
  uint32_t preferred_remote_source_delay = 0xBBBBBB;

  setTestPrefPresentationDelay(1, 0xFFFFFF, preferred_remote_sink_delay,
                               preferred_remote_sink_delay, RawAddress::kEmpty,
                               types::kLeAudioDirectionSink);
  setTestPrefPresentationDelay(1, 0xFFFFFF, preferred_remote_source_delay,
                               preferred_remote_source_delay, RawAddress::kEmpty,
                               types::kLeAudioDirectionSource);

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AnyNumber());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  uint32_t pd = 0;
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSink));
  ASSERT_EQ(pd, preferred_remote_sink_delay);
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSource));
  ASSERT_EQ(pd, preferred_remote_source_delay);
}

TEST_F(StateMachineTest, testStreamPreferredPresentationDelayOutsidePresentationDelay) {
  auto context_type = kContextTypeConversational;
  int leaudio_group_id = 4;
  uint32_t delay = 0x00AAAA;
  uint32_t preferred_delay = 0xBBBBBB;

  setTestPrefPresentationDelay(delay, delay, preferred_delay, preferred_delay);

  // Prepare fake connected device group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type);
  auto* leAudioDevice = group->GetFirstDevice();

  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(AnyNumber());

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  uint32_t pd = 0;
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSink));
  ASSERT_EQ(pd, delay);
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSource));
  ASSERT_EQ(pd, delay);
}

TEST_F(StateMachineTest, testStreamDifferentPresentationDelayMinOfMultipleDevices) {
  auto context_type = kContextTypeMedia;
  auto leaudio_group_id = 2;
  auto num_devices = 2;

  uint32_t first_device_delay_min = 0xAAAAAA;
  uint32_t second_device_delay_min = 0xAAAABB;
  uint32_t no_delay_set = 0;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  auto* firstDevice = group->GetFirstDevice();
  setTestPrefPresentationDelay(first_device_delay_min, 0xFFFFFF, 0, 0, firstDevice->address_);

  auto* secondDevice = group->GetNextDevice(firstDevice);
  setTestPrefPresentationDelay(second_device_delay_min, 0xFFFFFF, 0, 0, secondDevice->address_);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream the content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  uint32_t pd = 0;
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSink));
  ASSERT_EQ(pd, second_device_delay_min);
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSource));
  ASSERT_EQ(pd, no_delay_set);
}

TEST_F(StateMachineTest, testStreamPreferredPresentationDelayMultipleDevices) {
  auto context_type = kContextTypeMedia;
  auto leaudio_group_id = 2;
  auto num_devices = 2;

  uint32_t first_device_delay_min = 0xAAAAAA;
  uint32_t second_device_delay_min = 0xAAAABB;
  uint32_t preferred_delay_min = 0xBBBBBB;
  uint32_t no_delay_set = 0;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  auto* firstDevice = group->GetFirstDevice();
  setTestPrefPresentationDelay(first_device_delay_min, 0xFFFFFF, preferred_delay_min,
                               preferred_delay_min, firstDevice->address_);

  auto* secondDevice = group->GetNextDevice(firstDevice);
  setTestPrefPresentationDelay(second_device_delay_min, 0xFFFFFF, preferred_delay_min,
                               preferred_delay_min, secondDevice->address_);

  InjectInitialIdleNotification(group);

  // Start the configuration and stream the content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  // Check if group has transitioned to a proper state
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  uint32_t pd = 0;
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSink));
  ASSERT_EQ(pd, preferred_delay_min);
  ASSERT_TRUE(group->GetPresentationDelay(&pd, types::kLeAudioDirectionSource));
  ASSERT_EQ(pd, no_delay_set);
}

TEST_F(StateMachineTest, testStreamDifferentRangeOfPresentationDelayMultipleDevices) {
  auto context_type = kContextTypeMedia;
  auto leaudio_group_id = 2;
  auto num_devices = 2;

  uint32_t first_device_delay = 0xAAAAAA;
  uint32_t second_device_delay = 0xAAAABB;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);

  auto* firstDevice = group->GetFirstDevice();
  setTestPrefPresentationDelay(first_device_delay, first_device_delay, 0, 0, firstDevice->address_);

  auto* secondDevice = group->GetNextDevice(firstDevice);
  setTestPrefPresentationDelay(second_device_delay, second_device_delay, 0, 0,
                               secondDevice->address_);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              OnStateMachineInvalidStatusCb(
                      leaudio_group_id, StateMachineInvalidStatus::INVALID_DEVICE_CONFIGURATION))
          .Times(1);
  // Start the configuration and stream the content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testCigCreateFailedDueToInvalidMaxTL) {
  const auto context_type = kContextTypeMedia;
  const auto leaudio_group_id = 3;
  const auto num_devices = 1;

  setTestMaxTransportLatency(0x0005);

  // Prepare fake connected device in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);

  ON_CALL(*mock_iso_manager_, CreateCig).WillByDefault(Return());

  auto* leAudioDevice = group->GetFirstDevice();
  /*  1. Codec Config
   *  2. Release
   */
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl, _,
                                  GATT_WRITE_NO_RSP, _, _))
          .Times(2);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, EstablishCis(_)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, SetupIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, RemoveIsoDataPath(_, _)).Times(0);
  EXPECT_CALL(*mock_iso_manager_, DisconnectCis(_, _)).Times(0);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(mock_callbacks_,
              OnStateMachineInvalidStatusCb(leaudio_group_id,
                                            StateMachineInvalidStatus::FAILED_TO_CREATE_CIG));

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testSuccessfulCigCreateForMultipleDevicesWhenOneDeviceProvidedBadTL) {
  auto context_type = kContextTypeMedia;
  auto leaudio_group_id = 2;
  auto num_devices = 2;

  uint16_t invalid_tl = 0x01;
  uint16_t test_tl = 0x00AA;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group);

  /* One device provides invalid Max TL, but Android tries to create stream based on the TL provided
   * by the other device from the CSIS group. */
  auto* firstDevice = group->GetFirstDevice();
  setTestMaxTransportLatency(invalid_tl, firstDevice->address_);

  auto* secondDevice = group->GetNextDevice(firstDevice);
  setTestMaxTransportLatency(test_tl, secondDevice->address_);

  InjectInitialIdleNotification(group);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);

  // Start the configuration and stream the content
  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});
  Mock::VerifyAndClearExpectations(mock_iso_manager_);
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
  ASSERT_EQ(group->GetMaxTransportLatencyCToP(), test_tl);
}

TEST_F(StateMachineTest, testReconfigureWhenOneDeviceIsInQoSConfiguredState) {
  auto context_type = kContextTypeMedia;
  auto leaudio_group_id = 2;
  auto num_devices = 2;

  /* Scenario:
   * 1. Put one set member to QoS Configured state
   * 2. Reconfigure two devices, in this stage do not response on Codec Configure command.
   * 3. Inject Codec Configured state for device not being in QoS Configured state.
   * 4. Make sure, Android will wait for response on the other device.
   * 5. Inject Codec Configured state from device being previously in QoS Configured state
   * 6. Verify QoS Config is send out to both devices
   */

  channel_count_ = kLeAudioCodecChannelCountTwoChannel;

  // Prepare multiple fake connected devices in a group
  auto* group = PrepareSingleTestDeviceGroup(leaudio_group_id, context_type, num_devices);
  ASSERT_NE(group, nullptr);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0, true);
  PrepareConfigureQosHandler(group, 0, true);
  PrepareEnableHandler(group, 0);

  auto* first_device = group->GetFirstDevice();
  ASSERT_NE(first_device, nullptr);
  auto* second_device = group->GetNextDevice(first_device);
  ASSERT_NE(second_device, nullptr);

  EXPECT_CALL(*mock_iso_manager_, CreateCig(_, _, _)).Times(1);
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER))
          .Times(1);

  InjectInitialIdleNotification(group);

  auto* firstDevice = group->GetFirstDevice();
  auto* secondDevice = group->GetNextDevice(firstDevice);

  log::debug("Step 1: Put one set member to QoS Configured state and the other to IDLE");
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)},
                             {.sink = {}, .source = {}}, false);

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  auto stored_conn_id = firstDevice->conn_id_;
  InjectAclDisconnected_andWait(group, firstDevice);

  // Start the configuration and stream Media content
  ConfigureStream_onMainloop(group, context_type,
                             {.sink = types::AudioContexts(context_type),
                              .source = types::AudioContexts(context_type)},
                             {.sink = {}, .source = {}}, true);

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

  log::info("Inject connecting second device");
  InjectAclConnected(group, firstDevice, stored_conn_id);

  log::debug(
          "Step 2: Reconfigure two devices, in this stage do not response on Codec Configure "
          "command.");
  ClearCodecConfigureCodecHandler();

  // Validate GroupStreamStatus
  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  StartStream_onMainloop(group, context_type,
                         {.sink = types::AudioContexts(context_type),
                          .source = types::AudioContexts(context_type)});

  log::debug("Step 3: Inject Codec Configured state for device not being in QoS Configured state.");
  InjectCachedConfigurationForActiveAses(group, firstDevice);
  SyncOnMainLoop();

  log::debug("Step 4: Make sure, Android will wait for response on the other device");
  ASSERT_TRUE(group->HaveAnyActiveDeviceInUnconfiguredState());

  log::debug(
          "Step 5: Inject Codec Configured state from device being previously in QoS Configured "
          "state");
  InjectCachedConfigurationForActiveAses(group, secondDevice);
  SyncOnMainLoop();

  log::debug("Step 6: Verify QoS Config is send out to both devices and stream moved to STREAMING");
  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

TEST_F(StateMachineTest, testReconfigureFromMediaToConversationalBeforeCigWasCreated) {
  auto leaudio_group_id = 2;
  auto num_devices = 2;

  channel_count_ = kLeAudioCodecChannelCountSingleChannel;

  /* Scenario:
   * 1. Configure group for MEDIA
   * 2. Do not start the stream
   * 3. Configure group to Conversational
   * 4. Make sure, group is configured properly
   */

  // Prepare multiple fake connected devices in a group
  auto* group =
          PrepareSingleTestDeviceGroup(leaudio_group_id, kContextTypeConversational, num_devices,
                                       kContextTypeConversational | kContextTypeMedia);
  ASSERT_NE(group, nullptr);
  ASSERT_EQ(group->Size(), num_devices);

  PrepareConfigureCodecHandler(group, 0);
  PrepareConfigureQosHandler(group);
  PrepareEnableHandler(group);
  PrepareReceiverStartReadyHandler(group);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id,
                             bluetooth::le_audio::GroupStreamStatus::CONFIGURED_BY_USER))
          .Times(1);

  EXPECT_CALL(mock_callbacks_,
              StatusReportCb(leaudio_group_id, bluetooth::le_audio::GroupStreamStatus::STREAMING))
          .Times(1);

  InjectInitialIdleNotification(group);

  /* Do reconfiguration */
  group->SetPendingConfiguration();
  ConfigureStream_onMainloop(group, kContextTypeMedia,
                             {.sink = types::AudioContexts(kContextTypeMedia),
                              .source = types::AudioContexts(kContextTypeMedia)},
                             {.sink = {}, .source = {}}, false);

  SyncOnMainLoop();
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  auto group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [media_sink_is_enabled, media_source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(media_sink_is_enabled);
  ASSERT_FALSE(media_source_is_enabled);

  // Start the configuration and stream Media content
  StartStream_onMainloop(group, kContextTypeConversational,
                         {.sink = types::AudioContexts(kContextTypeConversational),
                          .source = types::AudioContexts(kContextTypeConversational)},
                         {.sink = {}, .source = {}});
  SyncOnMainLoop();

  group_config = group->GetActiveConfiguration();
  ASSERT_NE(group_config, nullptr);
  auto [conv_sink_is_enabled, conv_source_is_enabled] = group_config->getDirections();
  ASSERT_TRUE(conv_sink_is_enabled);
  ASSERT_TRUE(conv_source_is_enabled);
  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

  Mock::VerifyAndClearExpectations(&mock_callbacks_);
}

}  // namespace internal
}  // namespace bluetooth::le_audio
