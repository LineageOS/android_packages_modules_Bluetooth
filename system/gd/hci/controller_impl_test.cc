/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "hci/controller_impl.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gtest/gtest.h>
#include <unistd.h>

#include <chrono>
#include <future>
#include <memory>
#include <sstream>

#include "common/bind.h"
#include "hci/address.h"
#include "hci/hci_layer_fake.h"
#include "os/thread.h"
#include "packet/raw_builder.h"

using namespace bluetooth;
using namespace std::chrono_literals;

using packet::kLittleEndian;
using packet::PacketView;
using packet::RawBuilder;

namespace bluetooth {
namespace hci {

namespace {

constexpr uint16_t kHandle1 = 0x123;
constexpr uint16_t kCredits1 = 0x78;
constexpr uint16_t kHandle2 = 0x456;
constexpr uint16_t kCredits2 = 0x9a;
constexpr uint64_t kRandomNumber = 0x123456789abcdef0;
/*sbc_supported= 1, aac_supported= 1, aptx_supported= 0, aptx_hd_supported= 0, ldac_supported= 1 */
constexpr uint32_t kDynamicAudioBufferSupport = 0x13;
uint16_t feature_spec_version = 55;

}  // namespace

namespace {

uint64_t kDefaultLeFeatures =
        (uint64_t)LLFeaturesBits::LE_ENCRYPTION |
        (uint64_t)LLFeaturesBits::CONNECTION_PARAMETERS_REQUEST_PROCEDURE |
        (uint64_t)LLFeaturesBits::EXTENDED_REJECT_INDICATION |
        (uint64_t)LLFeaturesBits::PERIPHERAL_INITIATED_FEATURES_EXCHANGE |
        (uint64_t)LLFeaturesBits::LE_PING |
        (uint64_t)LLFeaturesBits::LE_DATA_PACKET_LENGTH_EXTENSION |
        (uint64_t)LLFeaturesBits::LL_PRIVACY |
        (uint64_t)LLFeaturesBits::EXTENDED_SCANNER_FILTER_POLICIES |
        (uint64_t)LLFeaturesBits::LE_2M_PHY |
        (uint64_t)LLFeaturesBits::STABLE_MODULATION_INDEX_TRANSMITTER |
        (uint64_t)LLFeaturesBits::STABLE_MODULATION_INDEX_RECEIVER |
        (uint64_t)LLFeaturesBits::LE_CODED_PHY | (uint64_t)LLFeaturesBits::LE_EXTENDED_ADVERTISING |
        (uint64_t)LLFeaturesBits::LE_PERIODIC_ADVERTISING |
        (uint64_t)LLFeaturesBits::CHANNEL_SELECTION_ALGORITHM_2 |
        (uint64_t)LLFeaturesBits::LE_POWER_CLASS_1 |
        (uint64_t)LLFeaturesBits::MINIMUM_NUMBER_OF_USED_CHANNELS_PROCEDURE |
        (uint64_t)LLFeaturesBits::CONNECTION_CTE_REQUEST |
        (uint64_t)LLFeaturesBits::CONNECTION_CTE_RESPONSE |
        (uint64_t)LLFeaturesBits::CONNECTIONLESS_CTE_TRANSMITTER |
        (uint64_t)LLFeaturesBits::CONNECTIONLESS_CTE_RECEIVER |
        (uint64_t)LLFeaturesBits::ANTENNA_SWITCHING_DURING_CTE_TRANSMISSION |
        (uint64_t)LLFeaturesBits::ANTENNA_SWITCHING_DURING_CTE_RECEPTION |
        (uint64_t)LLFeaturesBits::RECEIVING_CONSTANT_TONE_EXTENSIONS |
        (uint64_t)LLFeaturesBits::PERIODIC_ADVERTISING_SYNC_TRANSFER_SENDER |
        (uint64_t)LLFeaturesBits::PERIODIC_ADVERTISING_SYNC_TRANSFER_RECIPIENT |
        (uint64_t)LLFeaturesBits::SLEEP_CLOCK_ACCURACY_UPDATES |
        (uint64_t)LLFeaturesBits::REMOTE_PUBLIC_KEY_VALIDATION |
        (uint64_t)LLFeaturesBits::CONNECTED_ISOCHRONOUS_STREAM_CENTRAL |
        (uint64_t)LLFeaturesBits::CONNECTED_ISOCHRONOUS_STREAM_PERIPHERAL |
        (uint64_t)LLFeaturesBits::ISOCHRONOUS_BROADCASTER |
        (uint64_t)LLFeaturesBits::SYNCHRONIZED_RECEIVER |
        (uint64_t)LLFeaturesBits::CONNECTED_ISOCHRONOUS_STREAM_HOST_SUPPORT |
        (uint64_t)LLFeaturesBits::LE_POWER_CONTROL_REQUEST |
        (uint64_t)LLFeaturesBits::LE_POWER_CONTROL_REQUEST_BIS |
        (uint64_t)LLFeaturesBits::LE_PATH_LOSS_MONITORING |
        (uint64_t)LLFeaturesBits::PERIODIC_ADVERTISING_ADI_SUPPORT |
        (uint64_t)LLFeaturesBits::CONNECTION_SUBRATING |
        (uint64_t)LLFeaturesBits::CONNECTION_SUBRATING_HOST_SUPPORT |
        (uint64_t)LLFeaturesBits::CHANNEL_CLASSIFICATION;

class HciLayerFakeForController : public HciLayerFake {
public:
  HciLayerFakeForController(os::Handler* handler) : HciLayerFake(handler) {}

  void EnqueueCommand(
          std::unique_ptr<CommandBuilder> command,
          common::ContextualOnceCallback<void(CommandCompleteView)> on_complete) override {
    handler_->Post(common::BindOnce(&HciLayerFakeForController::HandleCommand,
                                    common::Unretained(this), std::move(command),
                                    std::move(on_complete)));
  }

  void EnqueueCommand(
          std::unique_ptr<CommandBuilder> /* command */,
          common::ContextualOnceCallback<void(CommandStatusView)> /* on_status */) override {
    FAIL() << "ControllerImpl properties should not generate Command Status";
  }

  void EnqueueCommand(std::unique_ptr<CommandBuilder> /* command */,
                      common::ContextualOnceCallback<void(
                              CommandStatusOrCompleteView)> /* on_status_or_complete */) override {
    FAIL() << "ControllerImpl properties should not generate Command Status";
  }

  void SetVendorAclHandleRange(uint16_t min, uint16_t max) override {
    HciLayerFake::SetVendorAclHandleRange(min, max);
  }

  void RegisterVendorSpecificAclHandler(
          common::ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler) override {
    HciLayerFake::RegisterVendorSpecificAclHandler(handler);
  }

  void UnregisterVendorSpecificAclHandler() override {
    HciLayerFake::UnregisterVendorSpecificAclHandler();
  }

  void HandleCommand(std::unique_ptr<CommandBuilder> command_builder,
                     common::ContextualOnceCallback<void(CommandCompleteView)> on_complete) {
    auto bytes = std::make_shared<std::vector<uint8_t>>();
    BitInserter i(*bytes);
    bytes->reserve((command_builder)->size());
    command_builder->Serialize(i);
    auto packet_view = packet::PacketView<packet::kLittleEndian>(bytes);
    CommandView command = CommandView::Create(packet_view);
    ASSERT_TRUE(command.IsValid());

    uint8_t num_packets = 1;
    std::unique_ptr<packet::BasePacketBuilder> event_builder;
    switch (command.GetOpCode()) {
      case (OpCode::READ_LOCAL_NAME): {
        std::array<uint8_t, 248> local_name = {'D', 'U', 'T', '\0'};
        event_builder =
                ReadLocalNameCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, local_name);
      } break;
      case (OpCode::SET_MIN_ENCRYPTION_KEY_SIZE): {
        event_builder =
                SetMinEncryptionKeySizeCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS);
      } break;
      case (OpCode::READ_LOCAL_VERSION_INFORMATION): {
        LocalVersionInformation local_version_information;
        local_version_information.hci_version_ = HciVersion::V_5_0;
        local_version_information.hci_revision_ = 0x1234;
        local_version_information.lmp_version_ = LmpVersion::V_4_2;
        local_version_information.manufacturer_name_ = 0xBAD;
        local_version_information.lmp_subversion_ = 0x5678;
        event_builder = ReadLocalVersionInformationCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, local_version_information);
      } break;
      case (OpCode::READ_LOCAL_SUPPORTED_COMMANDS): {
        std::array<uint8_t, 64> supported_commands;
        for (int i = 0; i < 64; i++) {
          supported_commands[i] = 0xff;
        }
        supported_commands[37] = 0xf9;
        event_builder = ReadLocalSupportedCommandsCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, supported_commands);
      } break;
      case (OpCode::READ_LOCAL_SUPPORTED_CODECS_V1): {
        std::vector<uint8_t> supported_codecs{0, 1, 2, 3, 4, 5, 6};
        std::vector<uint32_t> supported_vendor_codecs;
        event_builder = ReadLocalSupportedCodecsV1CompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, supported_codecs, supported_vendor_codecs);
      } break;
      case (OpCode::READ_LOCAL_EXTENDED_FEATURES): {
        ReadLocalExtendedFeaturesView read_command = ReadLocalExtendedFeaturesView::Create(command);
        ASSERT_TRUE(read_command.IsValid());
        uint8_t page_number = read_command.GetPageNumber();
        uint64_t lmp_features = 0x012345678abcdef;
        lmp_features += page_number;
        event_builder = ReadLocalExtendedFeaturesCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, page_number, 0x02, lmp_features);
      } break;
      case (OpCode::READ_BUFFER_SIZE): {
        event_builder = ReadBufferSizeCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, acl_data_packet_length,
                synchronous_data_packet_length, total_num_acl_data_packets,
                total_num_synchronous_data_packets);
      } break;
      case (OpCode::READ_BD_ADDR): {
        event_builder =
                ReadBdAddrCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, Address::kAny);
      } break;
      case (OpCode::LE_READ_BUFFER_SIZE_V1): {
        LeBufferSize le_buffer_size;
        le_buffer_size.le_data_packet_length_ = 0x16;
        le_buffer_size.total_num_le_packets_ = 0x08;
        event_builder = LeReadBufferSizeV1CompleteBuilder::Create(num_packets, ErrorCode::SUCCESS,
                                                                  le_buffer_size);
      } break;
      case (OpCode::LE_READ_BUFFER_SIZE_V2): {
        LeBufferSize le_buffer_size;
        le_buffer_size.le_data_packet_length_ = le_data_packet_length_v2;
        le_buffer_size.total_num_le_packets_ = le_total_num_packets_v2;
        LeBufferSize iso_buffer_size;
        iso_buffer_size.le_data_packet_length_ = iso_data_packet_length_v2;
        iso_buffer_size.total_num_le_packets_ = iso_total_num_packets_v2;
        event_builder = LeReadBufferSizeV2CompleteBuilder::Create(num_packets, ErrorCode::SUCCESS,
                                                                  le_buffer_size, iso_buffer_size);
      } break;
      case (OpCode::LE_READ_LOCAL_SUPPORTED_FEATURES): {
        event_builder = LeReadLocalSupportedFeaturesCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, le_features_to_return);
      } break;
      case (OpCode::LE_READ_SUPPORTED_STATES): {
        event_builder = LeReadSupportedStatesCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, 0x001f123456789abe);
      } break;
      case (OpCode::LE_READ_MAXIMUM_DATA_LENGTH): {
        LeMaximumDataLength le_maximum_data_length;
        le_maximum_data_length.supported_max_tx_octets_ = 0x12;
        le_maximum_data_length.supported_max_tx_time_ = 0x34;
        le_maximum_data_length.supported_max_rx_octets_ = 0x56;
        le_maximum_data_length.supported_max_rx_time_ = 0x78;
        event_builder = LeReadMaximumDataLengthCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, le_maximum_data_length);
      } break;
      case (OpCode::LE_READ_MAXIMUM_ADVERTISING_DATA_LENGTH): {
        event_builder = LeReadMaximumAdvertisingDataLengthCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, 0x0672);
      } break;
      case (OpCode::LE_READ_NUMBER_OF_SUPPORTED_ADVERTISING_SETS): {
        event_builder = LeReadNumberOfSupportedAdvertisingSetsCompleteBuilder::Create(
                num_packets, ErrorCode::SUCCESS, 0xF0);
      } break;
      case (OpCode::LE_GET_VENDOR_CAPABILITIES):
        if (vendor_capabilities_ == nullptr) {
          BaseVendorCapabilities base_vendor_capabilities;
          base_vendor_capabilities.max_advt_instances_ = 0x10;
          base_vendor_capabilities.offloaded_resolution_of_private_address_ = 0x01;
          base_vendor_capabilities.total_scan_results_storage_ = 0x2800;
          base_vendor_capabilities.max_irk_list_sz_ = 0x20;
          base_vendor_capabilities.filtering_support_ = 0x01;
          base_vendor_capabilities.max_filter_ = 0x10;
          base_vendor_capabilities.activity_energy_info_support_ = 0x01;

          auto payload = std::make_unique<RawBuilder>();
          if (feature_spec_version > 55) {
            std::vector<uint8_t> payload_bytes = {0x20, 0x00, 0x01, 0x00, 0x00,
                                                  0x1f, 0x00, 0x00, 0x00, 0x00};
            payload->AddOctets2(feature_spec_version);
            payload->AddOctets(payload_bytes);
          }
          event_builder = LeGetVendorCapabilitiesCompleteBuilder::Create(
                  num_packets, ErrorCode::SUCCESS, base_vendor_capabilities, std::move(payload));
        } else {
          event_builder = std::move(vendor_capabilities_);
          vendor_capabilities_.reset();
        }
        break;
      case (OpCode::DYNAMIC_AUDIO_BUFFER): {
        auto dab_command =
                DynamicAudioBufferView::CreateOptional(VendorCommandView::Create(command));
        if (dab_command->GetDabCommand() == DabCommand::GET_AUDIO_BUFFER_TIME_CAPABILITY) {
          std::array<DynamicAudioBufferCodecCapability, 32> capabilities{};
          capabilities[0] =
                  DynamicAudioBufferCodecCapability(0x123, 0x103, 0x1234);  // sbc_capabilities
          capabilities[1] =
                  DynamicAudioBufferCodecCapability(0x223, 0x123, 0x2340);  // aac_capabilities
          capabilities[4] =
                  DynamicAudioBufferCodecCapability(0x323, 0x223, 0x3456);  // ldac_capabilities
          event_builder = DabGetAudioBufferTimeCapabilityCompleteBuilder::Create(
                  1, ErrorCode::SUCCESS, kDynamicAudioBufferSupport, capabilities);
        } else {
          auto set_command = DabSetAudioBufferTimeView::CreateOptional(*dab_command);
          dynamic_audio_buffer_time = set_command->GetBufferTimeMs();
          event_builder = DabSetAudioBufferTimeCompleteBuilder::Create(1, ErrorCode::SUCCESS,
                                                                       dynamic_audio_buffer_time);
        }
      } break;
      case (OpCode::SET_EVENT_MASK): {
        auto view = SetEventMaskView::Create(command);
        ASSERT_TRUE(view.IsValid());
        event_mask = view.GetEventMask();
        event_builder = SetEventMaskCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS);
      } break;
      case (OpCode::LE_SET_EVENT_MASK): {
        auto view = LeSetEventMaskView::Create(command);
        ASSERT_TRUE(view.IsValid());
        le_event_mask = view.GetLeEventMask();
        event_builder = LeSetEventMaskCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS);
      } break;

      case (OpCode::LE_RAND): {
        auto view = LeRandView::Create(LeSecurityCommandView::Create(command));
        ASSERT_TRUE(view.IsValid());
        event_builder =
                LeRandCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, kRandomNumber);
      } break;

      // Let the test check and handle these commands.
      case (OpCode::RESET):
      case (OpCode::SET_EVENT_FILTER):
      case (OpCode::HOST_BUFFER_SIZE):
        HciLayerFake::EnqueueCommand(std::move(command_builder), std::move(on_complete));
        return;

      default:
        log::info("Dropping unhandled packet ({})", OpCodeText(command.GetOpCode()));
        return;
    }
    auto packet = GetPacketView(std::move(event_builder));
    EventView event = EventView::Create(packet);
    ASSERT_TRUE(event.IsValid());
    CommandCompleteView command_complete = CommandCompleteView::Create(event);
    ASSERT_TRUE(command_complete.IsValid());
    on_complete(std::move(command_complete));
  }

  void IncomingCredit() {
    std::vector<CompletedPackets> completed_packets;
    CompletedPackets cp;
    cp.host_num_of_completed_packets_ = kCredits1;
    cp.connection_handle_ = kHandle1;
    completed_packets.push_back(cp);
    cp.host_num_of_completed_packets_ = kCredits2;
    cp.connection_handle_ = kHandle2;
    completed_packets.push_back(cp);
    IncomingEvent(NumberOfCompletedPacketsBuilder::Create(completed_packets));
  }

  std::unique_ptr<EventBuilder> vendor_capabilities_ = nullptr;
  uint64_t le_features_to_return = kDefaultLeFeatures;
  constexpr static uint16_t acl_data_packet_length = 1024;
  constexpr static uint8_t synchronous_data_packet_length = 60;
  constexpr static uint16_t total_num_acl_data_packets = 10;
  constexpr static uint16_t total_num_synchronous_data_packets = 12;
  constexpr static uint16_t le_data_packet_length_v2 = 0x016;
  constexpr static uint8_t le_total_num_packets_v2 = 8;
  constexpr static uint16_t iso_data_packet_length_v2 = 0x80;
  constexpr static uint8_t iso_total_num_packets_v2 = 0x10;
  uint64_t event_mask = 0;
  uint64_t le_event_mask = 0;
  uint16_t dynamic_audio_buffer_time = 0;
};

class ControllerTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);

    feature_spec_version = feature_spec_version_;
    test_hci_layer_ = std::make_unique<HciLayerFakeForController>(client_handler_);
    test_hci_layer_->vendor_capabilities_ = std::move(vendor_capabilities_);
    vendor_capabilities_.reset();
    controller_ = std::make_unique<ControllerImpl>(client_handler_, test_hci_layer_.get());
  }

  void TearDown() override {
    client_handler_->Clear();
    client_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);

    controller_.reset();
    test_hci_layer_.reset();

    delete client_handler_;
    delete thread_;
  }

  os::Thread* thread_ = nullptr;
  os::Handler* client_handler_ = nullptr;
  std::unique_ptr<HciLayerFakeForController> test_hci_layer_ = nullptr;
  std::unique_ptr<ControllerImpl> controller_ = nullptr;
  uint16_t feature_spec_version_ = 98;
  std::unique_ptr<EventBuilder> vendor_capabilities_ = nullptr;
};
}  // namespace

class Controller055Test : public ControllerTest {
protected:
  void SetUp() override {
    feature_spec_version_ = 55;
    ControllerTest::SetUp();
  }
};

class Controller095Test : public ControllerTest {
protected:
  void SetUp() override {
    feature_spec_version_ = 95;
    ControllerTest::SetUp();
  }
};

class Controller096Test : public ControllerTest {
protected:
  void SetUp() override {
    feature_spec_version_ = 96;
    ControllerTest::SetUp();
  }
};

class Controller103Test : public ControllerTest {
protected:
  void SetUp() override {
    feature_spec_version_ = 0x100 + 0x03;
    BaseVendorCapabilities base_vendor_capabilities;
    base_vendor_capabilities.max_advt_instances_ = 0x10;
    base_vendor_capabilities.offloaded_resolution_of_private_address_ = 0x01;
    base_vendor_capabilities.total_scan_results_storage_ = 0x2800;
    base_vendor_capabilities.max_irk_list_sz_ = 0x20;
    base_vendor_capabilities.filtering_support_ = 0x01;
    base_vendor_capabilities.max_filter_ = 0x10;
    base_vendor_capabilities.activity_energy_info_support_ = 0x01;
    vendor_capabilities_ = LeGetVendorCapabilitiesComplete103Builder::Create(
            1, ErrorCode::SUCCESS, base_vendor_capabilities, feature_spec_version_, 0x102,
            /*extended_scan_support=*/1,
            /*debug_logging_supported=*/1,
            /*le_address_generation_offloading_support=*/0,
            /*a2dp_source_offload_capability_mask=*/0x4,
            /*bluetooth_quality_report_support=*/1, kDynamicAudioBufferSupport,
            std::make_unique<RawBuilder>());
    ControllerTest::SetUp();
  }
};

class Controller104Test : public ControllerTest {
protected:
  void SetUp() override {
    feature_spec_version_ = 0x100 + 0x04;
    BaseVendorCapabilities base_vendor_capabilities;
    base_vendor_capabilities.max_advt_instances_ = 0x10;
    base_vendor_capabilities.offloaded_resolution_of_private_address_ = 0x01;
    base_vendor_capabilities.total_scan_results_storage_ = 0x2800;
    base_vendor_capabilities.max_irk_list_sz_ = 0x20;
    base_vendor_capabilities.filtering_support_ = 0x01;
    base_vendor_capabilities.max_filter_ = 0x10;
    base_vendor_capabilities.activity_energy_info_support_ = 0x01;
    vendor_capabilities_ = LeGetVendorCapabilitiesComplete104Builder::Create(
            1, ErrorCode::SUCCESS, base_vendor_capabilities, feature_spec_version_, 0x102,
            /*extended_scan_support=*/1,
            /*debug_logging_supported=*/1,
            /*le_address_generation_offloading_support=*/0,
            /*a2dp_source_offload_capability_mask=*/0x4,
            /*bluetooth_quality_report_support=*/1, kDynamicAudioBufferSupport,
            /*a2dp_offload_v2_support=*/1, std::make_unique<RawBuilder>());
    ControllerTest::SetUp();
  }
};

class Controller105Test : public ControllerTest {
protected:
  void SetUp() override {
    feature_spec_version_ = 0x100 + 0x05;
    BaseVendorCapabilities base_vendor_capabilities;
    base_vendor_capabilities.max_advt_instances_ = 0x10;
    base_vendor_capabilities.offloaded_resolution_of_private_address_ = 0x01;
    base_vendor_capabilities.total_scan_results_storage_ = 0x2800;
    base_vendor_capabilities.max_irk_list_sz_ = 0x20;
    base_vendor_capabilities.filtering_support_ = 0x01;
    base_vendor_capabilities.max_filter_ = 0x10;
    base_vendor_capabilities.activity_energy_info_support_ = 0x01;
    vendor_capabilities_ = LeGetVendorCapabilitiesComplete105Builder::Create(
            1, ErrorCode::SUCCESS, base_vendor_capabilities, feature_spec_version_, 0x102,
            /*extended_scan_support=*/1,
            /*debug_logging_supported=*/1,
            /*le_address_generation_offloading_support=*/0,
            /*a2dp_source_offload_capability_mask=*/0x4,
            /*bluetooth_quality_report_support=*/1, kDynamicAudioBufferSupport,
            /*a2dp_offload_v2_support=*/1,
            /*iso_link_feedback_support=*/1,
            /*sniff_offload_support=*/1, std::make_unique<RawBuilder>());
    ControllerTest::SetUp();
  }
};

class Controller106Test : public ControllerTest {
protected:
  void SetUp() override {
    // Set Flags of v1.06 as true
    set_com_android_bluetooth_flags_report_vendor_events_from_acl(true);
    set_com_android_bluetooth_flags_leaudio_broadcast_source_channel_map_classification_improvement(
            true);
    feature_spec_version_ = 0x100 + 0x06;
    BaseVendorCapabilities base_vendor_capabilities;
    base_vendor_capabilities.max_advt_instances_ = 0x10;
    base_vendor_capabilities.offloaded_resolution_of_private_address_ = 0x01;
    base_vendor_capabilities.total_scan_results_storage_ = 0x2800;
    base_vendor_capabilities.max_irk_list_sz_ = 0x20;
    base_vendor_capabilities.filtering_support_ = 0x01;
    base_vendor_capabilities.max_filter_ = 0x10;
    base_vendor_capabilities.activity_energy_info_support_ = 0x01;
    vendor_capabilities_ = LeGetVendorCapabilitiesComplete106Builder::Create(
            1, ErrorCode::SUCCESS, base_vendor_capabilities, feature_spec_version_, 0x102,
            /*extended_scan_support=*/1,
            /*debug_logging_supported=*/1,
            /*le_address_generation_offloading_support=*/0,
            /*a2dp_source_offload_capability_mask=*/0x4,
            /*bluetooth_quality_report_support=*/1, kDynamicAudioBufferSupport,
            /*a2dp_offload_v2_support=*/1,
            /*iso_link_feedback_support=*/1,
            /*sniff_offload_support=*/1,
            /*big_set_channel_map_classification_support=*/0x0001,
            /*vendor_connection_handle_min=*/0x0000,
            /*vendor_connection_handle_max=*/0x0000,
            /*connection_proximity_threshold_support=*/0x01, std::make_unique<RawBuilder>());
    ControllerTest::SetUp();
  }
};

TEST_F(ControllerTest, startup_teardown) {}

TEST_F(ControllerTest, read_controller_info) {
  ASSERT_EQ(controller_->GetAclPacketLength(), test_hci_layer_->acl_data_packet_length);
  ASSERT_EQ(controller_->GetNumAclPacketBuffers(), test_hci_layer_->total_num_acl_data_packets);
  ASSERT_EQ(controller_->GetScoPacketLength(), test_hci_layer_->synchronous_data_packet_length);
  ASSERT_EQ(controller_->GetNumScoPacketBuffers(),
            test_hci_layer_->total_num_synchronous_data_packets);
  ASSERT_EQ(controller_->GetMacAddress(), Address::kAny);
  LocalVersionInformation local_version_information = controller_->GetLocalVersionInformation();
  ASSERT_EQ(HciVersion::V_5_0, local_version_information.hci_version_);
  ASSERT_EQ(0x1234, local_version_information.hci_revision_);
  ASSERT_EQ(LmpVersion::V_4_2, local_version_information.lmp_version_);
  ASSERT_EQ(0xBAD, local_version_information.manufacturer_name_);
  ASSERT_EQ(0x5678, local_version_information.lmp_subversion_);
  ASSERT_EQ(0x16, controller_->GetLeBufferSize().le_data_packet_length_);
  ASSERT_EQ(0x08, controller_->GetLeBufferSize().total_num_le_packets_);
  ASSERT_EQ(0x001f123456789abeUL, controller_->GetLeSupportedStates());
  ASSERT_EQ(0x12, controller_->GetLeMaximumDataLength().supported_max_tx_octets_);
  ASSERT_EQ(0x34, controller_->GetLeMaximumDataLength().supported_max_tx_time_);
  ASSERT_EQ(0x56, controller_->GetLeMaximumDataLength().supported_max_rx_octets_);
  ASSERT_EQ(0x78, controller_->GetLeMaximumDataLength().supported_max_rx_time_);
  ASSERT_EQ(0x0672, controller_->GetLeMaximumAdvertisingDataLength());
  ASSERT_EQ(0xF0, controller_->GetLeNumberOfSupportedAdvertisingSets());
  ASSERT_GT(controller_->GetLocalSupportedBrEdrCodecIds().size(), 0u);
}

TEST_F(ControllerTest, send_set_event_mask_command) {
  uint64_t new_event_mask = test_hci_layer_->event_mask - 1;
  controller_->SetEventMask(new_event_mask);
  // Send another command to make sure it was applied
  controller_->Reset();
  auto packet = test_hci_layer_->GetCommand(OpCode::RESET);
  ASSERT_EQ(new_event_mask, test_hci_layer_->event_mask);
}

TEST_F(ControllerTest, send_reset_command) {
  controller_->Reset();
  auto packet = test_hci_layer_->GetCommand(OpCode::RESET);
  auto command = ResetView::Create(packet);
  ASSERT_TRUE(command.IsValid());
}

TEST_F(ControllerTest, send_set_event_filter_command) {
  controller_->SetEventFilterInquiryResultAllDevices();
  auto packet = test_hci_layer_->GetCommand(OpCode::SET_EVENT_FILTER);
  auto set_event_filter_view1 = SetEventFilterView::Create(packet);
  auto set_event_filter_inquiry_result_view1 =
          SetEventFilterInquiryResultView::Create(set_event_filter_view1);
  auto command1 =
          SetEventFilterInquiryResultAllDevicesView::Create(set_event_filter_inquiry_result_view1);
  ASSERT_TRUE(command1.IsValid());

  ClassOfDevice class_of_device({0xab, 0xcd, 0xef});
  ClassOfDevice class_of_device_mask({0x12, 0x34, 0x56});
  controller_->SetEventFilterInquiryResultClassOfDevice(class_of_device, class_of_device_mask);
  packet = test_hci_layer_->GetCommand(OpCode::SET_EVENT_FILTER);
  auto set_event_filter_view2 = SetEventFilterView::Create(packet);
  auto set_event_filter_inquiry_result_view2 =
          SetEventFilterInquiryResultView::Create(set_event_filter_view2);
  auto command2 = SetEventFilterInquiryResultClassOfDeviceView::Create(
          set_event_filter_inquiry_result_view2);
  ASSERT_TRUE(command2.IsValid());
  ASSERT_EQ(command2.GetClassOfDevice(), class_of_device);

  Address bdaddr({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  controller_->SetEventFilterConnectionSetupAddress(
          bdaddr, AutoAcceptFlag::AUTO_ACCEPT_ON_ROLE_SWITCH_ENABLED);
  packet = test_hci_layer_->GetCommand(OpCode::SET_EVENT_FILTER);
  auto set_event_filter_view3 = SetEventFilterView::Create(packet);
  auto set_event_filter_connection_setup_view =
          SetEventFilterConnectionSetupView::Create(set_event_filter_view3);
  auto command3 =
          SetEventFilterConnectionSetupAddressView::Create(set_event_filter_connection_setup_view);
  ASSERT_TRUE(command3.IsValid());
  ASSERT_EQ(command3.GetAddress(), bdaddr);
}

TEST_F(ControllerTest, send_host_buffer_size_command) {
  controller_->HostBufferSize(0xFF00, 0xF1, 0xFF02, 0xFF03);
  auto packet = test_hci_layer_->GetCommand(OpCode::HOST_BUFFER_SIZE);
  auto command = HostBufferSizeView::Create(packet);
  ASSERT_TRUE(command.IsValid());
  ASSERT_EQ(command.GetHostAclDataPacketLength(), 0xFF00);
  ASSERT_EQ(command.GetHostSynchronousDataPacketLength(), 0xF1);
  ASSERT_EQ(command.GetHostTotalNumAclDataPackets(), 0xFF02);
  ASSERT_EQ(command.GetHostTotalNumSynchronousDataPackets(), 0xFF03);
}

TEST_F(ControllerTest, send_le_set_event_mask_command) {
  uint64_t new_le_event_mask = test_hci_layer_->event_mask - 1;
  controller_->LeSetEventMask(new_le_event_mask);
  // Send another command to make sure it was applied
  controller_->Reset();
  auto packet = test_hci_layer_->GetCommand(OpCode::RESET);
  ASSERT_EQ(new_le_event_mask, test_hci_layer_->le_event_mask);
}

TEST_F(ControllerTest, is_supported_test) {
  ASSERT_TRUE(controller_->IsSupported(OpCode::INQUIRY));
  ASSERT_TRUE(controller_->IsSupported(OpCode::REJECT_CONNECTION_REQUEST));
  ASSERT_TRUE(controller_->IsSupported(OpCode::ACCEPT_CONNECTION_REQUEST));
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_REMOVE_ADVERTISING_SET));
  ASSERT_FALSE(controller_->IsSupported(OpCode::LE_CLEAR_ADVERTISING_SETS));
  ASSERT_FALSE(controller_->IsSupported(OpCode::LE_SET_PERIODIC_ADVERTISING_PARAMETERS));
}

TEST_F(Controller055Test, feature_spec_version_055_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 55);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
}

TEST_F(Controller095Test, feature_spec_version_095_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 95);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
}

TEST_F(Controller096Test, feature_spec_version_096_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 96);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
}

TEST_F(ControllerTest, feature_spec_version_098_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 98);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_FALSE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
}

TEST_F(ControllerTest, feature_spec_version_098_no_dab_test) {
  ASSERT_FALSE(controller_->IsSupported(OpCode::DYNAMIC_AUDIO_BUFFER));
}

TEST_F(ControllerTest, set_dynamic_audio_buffer_time) {
  controller_->SetDabAudioBufferTime(123);
  thread_->GetReactor()->WaitForIdle(std::chrono::seconds(1));
  ASSERT_EQ(0, test_hci_layer_->dynamic_audio_buffer_time);
}

TEST_F(Controller103Test, feature_spec_version_103_dab_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 0x100 + 3);
  ASSERT_FALSE(controller_->GetVendorCapabilities().a2dp_offload_v2_support_);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
  ASSERT_TRUE(controller_->IsSupported(OpCode::DYNAMIC_AUDIO_BUFFER));
  ASSERT_EQ(controller_->GetDabSupportedCodecs(), kDynamicAudioBufferSupport);
  for (size_t bit = 0; bit < 32; bit++) {
    if (kDynamicAudioBufferSupport & (1u << bit)) {
      ASSERT_GT(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0) << " bit " << bit;
    } else {
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].minimum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].default_time_ms_, 0);
    }
  }
}

TEST_F(Controller103Test, set_dynamic_audio_buffer_time) {
  controller_->SetDabAudioBufferTime(123);
  thread_->GetReactor()->WaitForIdle(std::chrono::seconds(1));
  ASSERT_EQ(123, test_hci_layer_->dynamic_audio_buffer_time);
}

TEST_F(Controller104Test, feature_spec_version_104_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 0x100 + 4);
  ASSERT_TRUE(controller_->GetVendorCapabilities().a2dp_offload_v2_support_);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
  ASSERT_TRUE(controller_->IsSupported(OpCode::DYNAMIC_AUDIO_BUFFER));
  ASSERT_EQ(controller_->GetDabSupportedCodecs(), kDynamicAudioBufferSupport);
  for (size_t bit = 0; bit < 32; bit++) {
    if (kDynamicAudioBufferSupport & (1u << bit)) {
      ASSERT_GT(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0) << " bit " << bit;
    } else {
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].minimum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].default_time_ms_, 0);
    }
  }
}

TEST_F(Controller105Test, feature_spec_version_105_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 0x100 + 5);
  ASSERT_TRUE(controller_->GetVendorCapabilities().a2dp_offload_v2_support_);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
  ASSERT_TRUE(controller_->IsSupported(OpCode::DYNAMIC_AUDIO_BUFFER));
  ASSERT_TRUE(controller_->IsSupported(OpCode::WRITE_SNIFF_OFFLOAD_ENABLE));
  ASSERT_TRUE(controller_->IsSupported(OpCode::WRITE_SNIFF_OFFLOAD_PARAMETERS));
  ASSERT_EQ(controller_->GetDabSupportedCodecs(), kDynamicAudioBufferSupport);

  for (size_t bit = 0; bit < 32; bit++) {
    if (kDynamicAudioBufferSupport & (1u << bit)) {
      ASSERT_GT(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0) << " bit " << bit;
    } else {
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].minimum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].default_time_ms_, 0);
    }
  }
}

TEST_F(Controller106Test, feature_spec_version_106_test) {
  ASSERT_EQ(controller_->GetVendorCapabilities().version_supported_, 0x100 + 6);
  ASSERT_TRUE(controller_->GetVendorCapabilities().a2dp_offload_v2_support_);
  ASSERT_TRUE(controller_->IsSupported(OpCode::LE_MULTI_ADVT));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_DEBUG_INFO));
  ASSERT_TRUE(controller_->IsSupported(OpCode::CONTROLLER_A2DP_OPCODE));
  ASSERT_TRUE(controller_->IsSupported(OpCode::DYNAMIC_AUDIO_BUFFER));
  ASSERT_TRUE(controller_->IsSupported(OpCode::WRITE_SNIFF_OFFLOAD_ENABLE));
  ASSERT_TRUE(controller_->IsSupported(OpCode::WRITE_SNIFF_OFFLOAD_PARAMETERS));
  ASSERT_EQ(controller_->GetDabSupportedCodecs(), kDynamicAudioBufferSupport);
  ASSERT_EQ(controller_->GetVendorCapabilities().big_set_channel_map_classification_support_,
            0x0001);
  ASSERT_EQ(controller_->GetVendorCapabilities().connection_proximity_threshold_support_, 0x01);
  ASSERT_TRUE(controller_->IsSupported(
          OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST_WITH_PROXIMITY_THRESHOLD));

  for (size_t bit = 0; bit < 32; bit++) {
    if (kDynamicAudioBufferSupport & (1u << bit)) {
      ASSERT_GT(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0) << " bit " << bit;
    } else {
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].maximum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].minimum_time_ms_, 0);
      ASSERT_EQ(controller_->GetDabCodecCapabilities()[bit].default_time_ms_, 0);
    }
  }
}

std::promise<void> credits1_set;
std::promise<void> credits2_set;

static void CheckReceivedCredits(uint16_t handle, uint16_t credits) {
  switch (handle) {
    case (kHandle1):
      ASSERT_EQ(kCredits1, credits);
      credits1_set.set_value();
      break;
    case (kHandle2):
      ASSERT_EQ(kCredits2, credits);
      credits2_set.set_value();
      break;
    default:
      log::fatal("Unknown handle 0x{:0x} with 0x{:0x} credits", handle, credits);
  }
}

TEST_F(ControllerTest, aclCreditCallbacksTest) {
  credits1_set = std::promise<void>();
  credits2_set = std::promise<void>();

  auto credits1_set_future = credits1_set.get_future();
  auto credits2_set_future = credits2_set.get_future();

  controller_->RegisterCompletedAclPacketsCallback(client_handler_->Bind(&CheckReceivedCredits));

  test_hci_layer_->IncomingCredit();

  ASSERT_EQ(std::future_status::ready, credits1_set_future.wait_for(2s));
  ASSERT_EQ(std::future_status::ready, credits2_set_future.wait_for(2s));
}

TEST_F(ControllerTest, aclCreditCallbackListenerUnregistered) {
  os::Thread thread("test_thread", os::Thread::Priority::NORMAL);
  os::Handler handler(&thread);
  controller_->RegisterCompletedAclPacketsCallback(handler.Bind(&CheckReceivedCredits));

  handler.Clear();
  handler.WaitUntilStopped(std::chrono::milliseconds(100));
  controller_->UnregisterCompletedAclPacketsCallback();

  test_hci_layer_->IncomingCredit();
}

std::promise<uint64_t> le_rand_set;

static void le_rand_callback(uint64_t random) { le_rand_set.set_value(random); }

TEST_F(ControllerTest, leRandTest) {
  le_rand_set = std::promise<uint64_t>();
  auto le_rand_set_future = le_rand_set.get_future();

  controller_->LeRand(client_handler_->BindOnce(le_rand_callback));

  ASSERT_EQ(std::future_status::ready, le_rand_set_future.wait_for(2s));
  ASSERT_EQ(kRandomNumber, le_rand_set_future.get());
}

TEST_F(ControllerTest, le_read_buffer_size_v2_supported) {
  // Assert the LE buffer sizes
  ASSERT_EQ(controller_->GetLeBufferSize().le_data_packet_length_,
            test_hci_layer_->le_data_packet_length_v2);
  ASSERT_EQ(controller_->GetLeBufferSize().total_num_le_packets_,
            test_hci_layer_->le_total_num_packets_v2);

  // Assert the ISO buffer sizes
  ASSERT_EQ(controller_->GetControllerIsoBufferSize().le_data_packet_length_,
            test_hci_layer_->iso_data_packet_length_v2);
  ASSERT_EQ(controller_->GetControllerIsoBufferSize().total_num_le_packets_,
            test_hci_layer_->iso_total_num_packets_v2);
}

TEST_F(ControllerTest, testLeEventMask) {
  LocalVersionInformation version;
  version.hci_version_ = HciVersion::V_5_3;

  // Update the function and this test when adding new bits.
  ASSERT_TRUE(ControllerImpl::kLeEventMask53 > ControllerImpl::kDefaultLeEventMask);

  ASSERT_EQ(ControllerImpl::MaskLeEventMask(version.hci_version_,
                                            ControllerImpl::kDefaultLeEventMask),
            ControllerImpl::kDefaultLeEventMask);
  ASSERT_LE(ControllerImpl::MaskLeEventMask(version.hci_version_,
                                            ControllerImpl::kDefaultLeEventMask),
            ControllerImpl::kLeEventMask53);
  version.hci_version_ = HciVersion::V_5_2;
  ASSERT_LE(ControllerImpl::MaskLeEventMask(version.hci_version_,
                                            ControllerImpl::kDefaultLeEventMask),
            ControllerImpl::kLeEventMask52);
  version.hci_version_ = HciVersion::V_5_1;
  ASSERT_LE(ControllerImpl::MaskLeEventMask(version.hci_version_,
                                            ControllerImpl::kDefaultLeEventMask),
            ControllerImpl::kLeEventMask51);
  version.hci_version_ = HciVersion::V_4_2;
  ASSERT_LE(ControllerImpl::MaskLeEventMask(version.hci_version_,
                                            ControllerImpl::kDefaultLeEventMask),
            ControllerImpl::kLeEventMask42);
  version.hci_version_ = HciVersion::V_4_1;
  ASSERT_LE(ControllerImpl::MaskLeEventMask(version.hci_version_,
                                            ControllerImpl::kDefaultLeEventMask),
            ControllerImpl::kLeEventMask41);
}

TEST_F(ControllerTest, GetLePeriodicAdvertiserListSize) {
  ASSERT_EQ(controller_->GetLePeriodicAdvertiserListSize(), 0);
}

TEST_F(ControllerTest, GetLeSuggestedDefaultDataLength) {
  ASSERT_EQ(controller_->GetLeSuggestedDefaultDataLength(), 0);
}
TEST_F(ControllerTest, GetLeFilterAcceptListSize) {
  ASSERT_EQ(controller_->GetLeFilterAcceptListSize(), 0);
}
TEST_F(ControllerTest, GetLeResolvingListSize) {
  ASSERT_EQ(controller_->GetLeResolvingListSize(), 0);
}

TEST_F(ControllerTest, SetEventFilterCommands) {
  // Test SetEventFilterClearAll
  controller_->SetEventFilterClearAll();
  auto clear_all_packet = test_hci_layer_->GetCommand(OpCode::SET_EVENT_FILTER);
  auto clear_all_view =
          SetEventFilterClearAllView::Create(SetEventFilterView::Create(clear_all_packet));
  ASSERT_TRUE(clear_all_view.IsValid());

  // Test SetEventFilterInquiryResultAddress
  Address address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  controller_->SetEventFilterInquiryResultAddress(address);
  auto address_packet = test_hci_layer_->GetCommand(OpCode::SET_EVENT_FILTER);
  auto address_view = SetEventFilterInquiryResultAddressView::Create(
          SetEventFilterInquiryResultView::Create(SetEventFilterView::Create(address_packet)));
  ASSERT_TRUE(address_view.IsValid());
  ASSERT_EQ(address_view.GetAddress(), address);

  // Test SetEventFilterConnectionSetupAllDevices
  AutoAcceptFlag flag = AutoAcceptFlag::AUTO_ACCEPT_ON_ROLE_SWITCH_DISABLED;
  controller_->SetEventFilterConnectionSetupAllDevices(flag);
  auto all_devices_packet = test_hci_layer_->GetCommand(OpCode::SET_EVENT_FILTER);
  auto all_devices_view = SetEventFilterConnectionSetupAllDevicesView::Create(
          SetEventFilterConnectionSetupView::Create(
                  SetEventFilterView::Create(all_devices_packet)));
  ASSERT_TRUE(all_devices_view.IsValid());
  ASSERT_EQ(all_devices_view.GetAutoAcceptFlag(), flag);
}
TEST_F(ControllerTest, AllOpCodeMappings) {
  // Use a map to define the expected support status for each OpCode.
  // By default, the mock HCI layer supports many commands. We'll explicitly
  // list the ones that are not supported by the default mock setup.
  std::map<OpCode, bool> expected_support;

  expected_support[OpCode::INQUIRY] = true;
  expected_support[OpCode::INQUIRY_CANCEL] = true;
  expected_support[OpCode::PERIODIC_INQUIRY_MODE] = true;
  expected_support[OpCode::EXIT_PERIODIC_INQUIRY_MODE] = true;
  expected_support[OpCode::CREATE_CONNECTION] = true;
  expected_support[OpCode::DISCONNECT] = true;
  expected_support[OpCode::CREATE_CONNECTION_CANCEL] = true;
  expected_support[OpCode::ACCEPT_CONNECTION_REQUEST] = true;
  expected_support[OpCode::REJECT_CONNECTION_REQUEST] = true;
  expected_support[OpCode::LINK_KEY_REQUEST_REPLY] = true;
  expected_support[OpCode::LINK_KEY_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::PIN_CODE_REQUEST_REPLY] = true;
  expected_support[OpCode::PIN_CODE_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::CHANGE_CONNECTION_PACKET_TYPE] = true;
  expected_support[OpCode::AUTHENTICATION_REQUESTED] = true;
  expected_support[OpCode::SET_CONNECTION_ENCRYPTION] = true;
  expected_support[OpCode::CHANGE_CONNECTION_LINK_KEY] = true;
  expected_support[OpCode::CENTRAL_LINK_KEY] = true;
  expected_support[OpCode::REMOTE_NAME_REQUEST] = true;
  expected_support[OpCode::REMOTE_NAME_REQUEST_CANCEL] = true;
  expected_support[OpCode::READ_REMOTE_SUPPORTED_FEATURES] = true;
  expected_support[OpCode::READ_REMOTE_EXTENDED_FEATURES] = true;
  expected_support[OpCode::READ_REMOTE_VERSION_INFORMATION] = true;
  expected_support[OpCode::READ_CLOCK_OFFSET] = true;
  expected_support[OpCode::READ_LMP_HANDLE] = true;
  expected_support[OpCode::SETUP_SYNCHRONOUS_CONNECTION] = true;
  expected_support[OpCode::ACCEPT_SYNCHRONOUS_CONNECTION] = true;
  expected_support[OpCode::REJECT_SYNCHRONOUS_CONNECTION] = true;
  expected_support[OpCode::IO_CAPABILITY_REQUEST_REPLY] = true;
  expected_support[OpCode::USER_CONFIRMATION_REQUEST_REPLY] = true;
  expected_support[OpCode::USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::USER_PASSKEY_REQUEST_REPLY] = true;
  expected_support[OpCode::USER_PASSKEY_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::REMOTE_OOB_DATA_REQUEST_REPLY] = true;
  expected_support[OpCode::REMOTE_OOB_DATA_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::IO_CAPABILITY_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION] = true;
  expected_support[OpCode::ENHANCED_ACCEPT_SYNCHRONOUS_CONNECTION] = true;
  expected_support[OpCode::TRUNCATED_PAGE] = true;
  expected_support[OpCode::TRUNCATED_PAGE_CANCEL] = true;
  expected_support[OpCode::SET_CONNECTIONLESS_PERIPHERAL_BROADCAST] = true;
  expected_support[OpCode::SET_CONNECTIONLESS_PERIPHERAL_BROADCAST_RECEIVE] = true;
  expected_support[OpCode::START_SYNCHRONIZATION_TRAIN] = true;
  expected_support[OpCode::RECEIVE_SYNCHRONIZATION_TRAIN] = true;
  expected_support[OpCode::REMOTE_OOB_EXTENDED_DATA_REQUEST_REPLY] = true;
  expected_support[OpCode::HOLD_MODE] = true;
  expected_support[OpCode::SNIFF_MODE] = true;
  expected_support[OpCode::EXIT_SNIFF_MODE] = true;
  expected_support[OpCode::PARK_STATE] = true;
  expected_support[OpCode::EXIT_PARK_STATE] = true;
  expected_support[OpCode::QOS_SETUP] = true;
  expected_support[OpCode::ROLE_DISCOVERY] = true;
  expected_support[OpCode::SWITCH_ROLE] = true;
  expected_support[OpCode::READ_LINK_POLICY_SETTINGS] = true;
  expected_support[OpCode::WRITE_LINK_POLICY_SETTINGS] = true;
  expected_support[OpCode::READ_DEFAULT_LINK_POLICY_SETTINGS] = true;
  expected_support[OpCode::WRITE_DEFAULT_LINK_POLICY_SETTINGS] = true;
  expected_support[OpCode::FLOW_SPECIFICATION] = true;
  expected_support[OpCode::SNIFF_SUBRATING] = true;
  expected_support[OpCode::SET_EVENT_MASK] = true;
  expected_support[OpCode::RESET] = true;
  expected_support[OpCode::SET_EVENT_FILTER] = true;
  expected_support[OpCode::FLUSH] = true;
  expected_support[OpCode::READ_PIN_TYPE] = true;
  expected_support[OpCode::WRITE_PIN_TYPE] = true;
  expected_support[OpCode::READ_STORED_LINK_KEY] = true;
  expected_support[OpCode::WRITE_STORED_LINK_KEY] = true;
  expected_support[OpCode::DELETE_STORED_LINK_KEY] = true;
  expected_support[OpCode::WRITE_LOCAL_NAME] = true;
  expected_support[OpCode::READ_LOCAL_NAME] = true;
  expected_support[OpCode::READ_CONNECTION_ACCEPT_TIMEOUT] = true;
  expected_support[OpCode::WRITE_CONNECTION_ACCEPT_TIMEOUT] = true;
  expected_support[OpCode::READ_PAGE_TIMEOUT] = true;
  expected_support[OpCode::WRITE_PAGE_TIMEOUT] = true;
  expected_support[OpCode::READ_SCAN_ENABLE] = true;
  expected_support[OpCode::WRITE_SCAN_ENABLE] = true;
  expected_support[OpCode::READ_PAGE_SCAN_ACTIVITY] = true;
  expected_support[OpCode::WRITE_PAGE_SCAN_ACTIVITY] = true;
  expected_support[OpCode::READ_INQUIRY_SCAN_ACTIVITY] = true;
  expected_support[OpCode::WRITE_INQUIRY_SCAN_ACTIVITY] = true;
  expected_support[OpCode::READ_AUTHENTICATION_ENABLE] = true;
  expected_support[OpCode::WRITE_AUTHENTICATION_ENABLE] = true;
  expected_support[OpCode::READ_CLASS_OF_DEVICE] = true;
  expected_support[OpCode::WRITE_CLASS_OF_DEVICE] = true;
  expected_support[OpCode::READ_VOICE_SETTING] = true;
  expected_support[OpCode::WRITE_VOICE_SETTING] = true;
  expected_support[OpCode::READ_AUTOMATIC_FLUSH_TIMEOUT] = true;
  expected_support[OpCode::WRITE_AUTOMATIC_FLUSH_TIMEOUT] = true;
  expected_support[OpCode::READ_NUM_BROADCAST_RETRANSMITS] = true;
  expected_support[OpCode::WRITE_NUM_BROADCAST_RETRANSMITS] = true;
  expected_support[OpCode::READ_HOLD_MODE_ACTIVITY] = true;
  expected_support[OpCode::WRITE_HOLD_MODE_ACTIVITY] = true;
  expected_support[OpCode::READ_TRANSMIT_POWER_LEVEL] = true;
  expected_support[OpCode::READ_SYNCHRONOUS_FLOW_CONTROL_ENABLE] = true;
  expected_support[OpCode::WRITE_SYNCHRONOUS_FLOW_CONTROL_ENABLE] = true;
  expected_support[OpCode::SET_CONTROLLER_TO_HOST_FLOW_CONTROL] = true;
  expected_support[OpCode::HOST_BUFFER_SIZE] = true;
  expected_support[OpCode::HOST_NUMBER_OF_COMPLETED_PACKETS] = true;
  expected_support[OpCode::READ_LINK_SUPERVISION_TIMEOUT] = true;
  expected_support[OpCode::WRITE_LINK_SUPERVISION_TIMEOUT] = true;
  expected_support[OpCode::READ_NUMBER_OF_SUPPORTED_IAC] = true;
  expected_support[OpCode::READ_CURRENT_IAC_LAP] = true;
  expected_support[OpCode::WRITE_CURRENT_IAC_LAP] = true;
  expected_support[OpCode::SET_AFH_HOST_CHANNEL_CLASSIFICATION] = true;
  expected_support[OpCode::READ_INQUIRY_SCAN_TYPE] = true;
  expected_support[OpCode::WRITE_INQUIRY_SCAN_TYPE] = true;
  expected_support[OpCode::READ_INQUIRY_MODE] = true;
  expected_support[OpCode::WRITE_INQUIRY_MODE] = true;
  expected_support[OpCode::READ_PAGE_SCAN_TYPE] = true;
  expected_support[OpCode::WRITE_PAGE_SCAN_TYPE] = true;
  expected_support[OpCode::READ_AFH_CHANNEL_ASSESSMENT_MODE] = true;
  expected_support[OpCode::WRITE_AFH_CHANNEL_ASSESSMENT_MODE] = true;
  expected_support[OpCode::READ_EXTENDED_INQUIRY_RESPONSE] = true;
  expected_support[OpCode::WRITE_EXTENDED_INQUIRY_RESPONSE] = true;
  expected_support[OpCode::REFRESH_ENCRYPTION_KEY] = true;
  expected_support[OpCode::READ_SIMPLE_PAIRING_MODE] = true;
  expected_support[OpCode::WRITE_SIMPLE_PAIRING_MODE] = true;
  expected_support[OpCode::READ_LOCAL_OOB_DATA] = true;
  expected_support[OpCode::READ_INQUIRY_RESPONSE_TRANSMIT_POWER_LEVEL] = true;
  expected_support[OpCode::WRITE_INQUIRY_TRANSMIT_POWER_LEVEL] = true;
  expected_support[OpCode::READ_DEFAULT_ERRONEOUS_DATA_REPORTING] = true;
  expected_support[OpCode::WRITE_DEFAULT_ERRONEOUS_DATA_REPORTING] = true;
  expected_support[OpCode::ENHANCED_FLUSH] = true;
  expected_support[OpCode::SEND_KEYPRESS_NOTIFICATION] = true;
  expected_support[OpCode::SET_EVENT_MASK_PAGE_2] = true;
  expected_support[OpCode::READ_FLOW_CONTROL_MODE] = true;
  expected_support[OpCode::WRITE_FLOW_CONTROL_MODE] = true;
  expected_support[OpCode::READ_ENHANCED_TRANSMIT_POWER_LEVEL] = true;
  expected_support[OpCode::READ_LE_HOST_SUPPORT] = true;
  expected_support[OpCode::WRITE_LE_HOST_SUPPORT] = true;
  expected_support[OpCode::SET_MWS_CHANNEL_PARAMETERS] = true;
  expected_support[OpCode::SET_EXTERNAL_FRAME_CONFIGURATION] = true;
  expected_support[OpCode::SET_MWS_SIGNALING] = true;
  expected_support[OpCode::SET_MWS_TRANSPORT_LAYER] = true;
  expected_support[OpCode::SET_MWS_SCAN_FREQUENCY_TABLE] = true;
  expected_support[OpCode::SET_MWS_PATTERN_CONFIGURATION] = true;
  expected_support[OpCode::SET_RESERVED_LT_ADDR] = true;
  expected_support[OpCode::DELETE_RESERVED_LT_ADDR] = true;
  expected_support[OpCode::SET_CONNECTIONLESS_PERIPHERAL_BROADCAST_DATA] = true;
  expected_support[OpCode::READ_SYNCHRONIZATION_TRAIN_PARAMETERS] = true;
  expected_support[OpCode::WRITE_SYNCHRONIZATION_TRAIN_PARAMETERS] = true;
  expected_support[OpCode::READ_SECURE_CONNECTIONS_HOST_SUPPORT] = true;
  expected_support[OpCode::WRITE_SECURE_CONNECTIONS_HOST_SUPPORT] = true;
  expected_support[OpCode::READ_AUTHENTICATED_PAYLOAD_TIMEOUT] = true;
  expected_support[OpCode::WRITE_AUTHENTICATED_PAYLOAD_TIMEOUT] = true;
  expected_support[OpCode::READ_LOCAL_OOB_EXTENDED_DATA] = true;
  expected_support[OpCode::READ_EXTENDED_PAGE_TIMEOUT] = true;
  expected_support[OpCode::WRITE_EXTENDED_PAGE_TIMEOUT] = true;
  expected_support[OpCode::READ_EXTENDED_INQUIRY_LENGTH] = true;
  expected_support[OpCode::WRITE_EXTENDED_INQUIRY_LENGTH] = true;
  expected_support[OpCode::SET_ECOSYSTEM_BASE_INTERVAL] = true;
  expected_support[OpCode::CONFIGURE_DATA_PATH] = true;
  expected_support[OpCode::SET_MIN_ENCRYPTION_KEY_SIZE] = true;
  expected_support[OpCode::READ_LOCAL_VERSION_INFORMATION] = true;
  expected_support[OpCode::READ_LOCAL_SUPPORTED_FEATURES] = true;
  expected_support[OpCode::READ_LOCAL_EXTENDED_FEATURES] = true;
  expected_support[OpCode::READ_BUFFER_SIZE] = true;
  expected_support[OpCode::READ_BD_ADDR] = true;
  expected_support[OpCode::READ_DATA_BLOCK_SIZE] = true;
  expected_support[OpCode::READ_LOCAL_SUPPORTED_CODECS_V1] = true;
  expected_support[OpCode::READ_LOCAL_SIMPLE_PAIRING_OPTIONS] = true;
  expected_support[OpCode::READ_LOCAL_SUPPORTED_CODECS_V2] = true;
  expected_support[OpCode::READ_LOCAL_SUPPORTED_CODEC_CAPABILITIES] = true;
  expected_support[OpCode::READ_LOCAL_SUPPORTED_CONTROLLER_DELAY] = true;
  expected_support[OpCode::READ_FAILED_CONTACT_COUNTER] = true;
  expected_support[OpCode::RESET_FAILED_CONTACT_COUNTER] = true;
  expected_support[OpCode::READ_LINK_QUALITY] = true;
  expected_support[OpCode::READ_RSSI] = true;
  expected_support[OpCode::READ_AFH_CHANNEL_MAP] = true;
  expected_support[OpCode::READ_CLOCK] = true;
  expected_support[OpCode::READ_ENCRYPTION_KEY_SIZE] = true;
  expected_support[OpCode::GET_MWS_TRANSPORT_LAYER_CONFIGURATION] = true;
  expected_support[OpCode::SET_TRIGGERED_CLOCK_CAPTURE] = true;
  expected_support[OpCode::READ_LOOPBACK_MODE] = true;
  expected_support[OpCode::WRITE_LOOPBACK_MODE] = true;
  expected_support[OpCode::ENABLE_DEVICE_UNDER_TEST_MODE] = true;
  expected_support[OpCode::WRITE_SIMPLE_PAIRING_DEBUG_MODE] = true;
  expected_support[OpCode::WRITE_SECURE_CONNECTIONS_TEST_MODE] = true;
  expected_support[OpCode::LE_SET_EVENT_MASK] = true;
  expected_support[OpCode::LE_READ_BUFFER_SIZE_V1] = true;
  expected_support[OpCode::LE_READ_LOCAL_SUPPORTED_FEATURES] = true;
  expected_support[OpCode::LE_SET_RANDOM_ADDRESS] = true;
  expected_support[OpCode::LE_SET_ADVERTISING_PARAMETERS] = true;
  expected_support[OpCode::LE_READ_ADVERTISING_PHYSICAL_CHANNEL_TX_POWER] = true;
  expected_support[OpCode::LE_SET_ADVERTISING_DATA] = true;
  expected_support[OpCode::LE_SET_SCAN_RESPONSE_DATA] = true;
  expected_support[OpCode::LE_SET_ADVERTISING_ENABLE] = true;
  expected_support[OpCode::LE_SET_SCAN_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_SCAN_ENABLE] = true;
  expected_support[OpCode::LE_CREATE_CONNECTION] = true;
  expected_support[OpCode::LE_CREATE_CONNECTION_CANCEL] = true;
  expected_support[OpCode::LE_READ_FILTER_ACCEPT_LIST_SIZE] = true;
  expected_support[OpCode::LE_CLEAR_FILTER_ACCEPT_LIST] = true;
  expected_support[OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST] = true;
  expected_support[OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST] = true;
  expected_support[OpCode::LE_CONNECTION_UPDATE] = true;
  expected_support[OpCode::LE_SET_HOST_CHANNEL_CLASSIFICATION] = true;
  expected_support[OpCode::LE_READ_CHANNEL_MAP] = true;
  expected_support[OpCode::LE_READ_REMOTE_FEATURES] = true;
  expected_support[OpCode::LE_ENCRYPT] = true;
  expected_support[OpCode::LE_RAND] = true;
  expected_support[OpCode::LE_START_ENCRYPTION] = true;
  expected_support[OpCode::LE_LONG_TERM_KEY_REQUEST_REPLY] = true;
  expected_support[OpCode::LE_LONG_TERM_KEY_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::LE_READ_SUPPORTED_STATES] = true;
  expected_support[OpCode::LE_RECEIVER_TEST_V1] = true;
  expected_support[OpCode::LE_TRANSMITTER_TEST_V1] = true;
  expected_support[OpCode::LE_TEST_END] = true;
  expected_support[OpCode::LE_REMOTE_CONNECTION_PARAMETER_REQUEST_REPLY] = true;
  expected_support[OpCode::LE_REMOTE_CONNECTION_PARAMETER_REQUEST_NEGATIVE_REPLY] = true;
  expected_support[OpCode::LE_SET_DATA_LENGTH] = true;
  expected_support[OpCode::LE_READ_SUGGESTED_DEFAULT_DATA_LENGTH] = true;
  expected_support[OpCode::LE_WRITE_SUGGESTED_DEFAULT_DATA_LENGTH] = true;
  expected_support[OpCode::LE_READ_LOCAL_P_256_PUBLIC_KEY] = true;
  expected_support[OpCode::LE_GENERATE_DHKEY_V1] = true;
  expected_support[OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST] = true;
  expected_support[OpCode::LE_REMOVE_DEVICE_FROM_RESOLVING_LIST] = true;
  expected_support[OpCode::LE_CLEAR_RESOLVING_LIST] = true;
  expected_support[OpCode::LE_READ_RESOLVING_LIST_SIZE] = true;
  expected_support[OpCode::LE_READ_PEER_RESOLVABLE_ADDRESS] = true;
  expected_support[OpCode::LE_READ_LOCAL_RESOLVABLE_ADDRESS] = true;
  expected_support[OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE] = true;
  expected_support[OpCode::LE_SET_RESOLVABLE_PRIVATE_ADDRESS_TIMEOUT] = true;
  expected_support[OpCode::LE_SET_RESOLVABLE_PRIVATE_ADDRESS_TIMEOUT_V2] = true;
  expected_support[OpCode::LE_READ_MAXIMUM_DATA_LENGTH] = true;
  expected_support[OpCode::LE_READ_PHY] = true;
  expected_support[OpCode::LE_SET_DEFAULT_PHY] = true;
  expected_support[OpCode::LE_SET_PHY] = true;
  expected_support[OpCode::LE_RECEIVER_TEST_V2] = true;
  expected_support[OpCode::LE_TRANSMITTER_TEST_V2] = true;
  expected_support[OpCode::LE_SET_ADVERTISING_SET_RANDOM_ADDRESS] = true;
  expected_support[OpCode::LE_SET_EXTENDED_ADVERTISING_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_EXTENDED_ADVERTISING_DATA] = true;
  expected_support[OpCode::LE_SET_EXTENDED_SCAN_RESPONSE_DATA] = true;
  expected_support[OpCode::LE_SET_EXTENDED_ADVERTISING_ENABLE] = true;
  expected_support[OpCode::LE_READ_MAXIMUM_ADVERTISING_DATA_LENGTH] = true;
  expected_support[OpCode::LE_READ_NUMBER_OF_SUPPORTED_ADVERTISING_SETS] = true;
  expected_support[OpCode::LE_REMOVE_ADVERTISING_SET] = true;
  expected_support[OpCode::LE_CLEAR_ADVERTISING_SETS] = false;
  expected_support[OpCode::LE_SET_PERIODIC_ADVERTISING_PARAMETERS] = false;
  expected_support[OpCode::LE_SET_PERIODIC_ADVERTISING_DATA] = true;
  expected_support[OpCode::LE_SET_PERIODIC_ADVERTISING_ENABLE] = true;
  expected_support[OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_EXTENDED_SCAN_ENABLE] = true;
  expected_support[OpCode::LE_EXTENDED_CREATE_CONNECTION] = true;
  expected_support[OpCode::LE_PERIODIC_ADVERTISING_CREATE_SYNC] = true;
  expected_support[OpCode::LE_PERIODIC_ADVERTISING_CREATE_SYNC_CANCEL] = true;
  expected_support[OpCode::LE_PERIODIC_ADVERTISING_TERMINATE_SYNC] = true;
  expected_support[OpCode::LE_ADD_DEVICE_TO_PERIODIC_ADVERTISER_LIST] = true;
  expected_support[OpCode::LE_REMOVE_DEVICE_FROM_PERIODIC_ADVERTISER_LIST] = true;
  expected_support[OpCode::LE_CLEAR_PERIODIC_ADVERTISER_LIST] = true;
  expected_support[OpCode::LE_READ_PERIODIC_ADVERTISER_LIST_SIZE] = true;
  expected_support[OpCode::LE_READ_TRANSMIT_POWER] = true;
  expected_support[OpCode::LE_READ_RF_PATH_COMPENSATION_POWER] = true;
  expected_support[OpCode::LE_WRITE_RF_PATH_COMPENSATION_POWER] = true;
  expected_support[OpCode::LE_SET_PRIVACY_MODE] = true;
  expected_support[OpCode::LE_RECEIVER_TEST_V3] = true;
  expected_support[OpCode::LE_TRANSMITTER_TEST_V3] = true;
  expected_support[OpCode::LE_SET_CONNECTIONLESS_CTE_TRANSMIT_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_CONNECTIONLESS_CTE_TRANSMIT_ENABLE] = true;
  expected_support[OpCode::LE_SET_CONNECTIONLESS_IQ_SAMPLING_ENABLE] = true;
  expected_support[OpCode::LE_SET_CONNECTION_CTE_RECEIVE_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_CONNECTION_CTE_TRANSMIT_PARAMETERS] = true;
  expected_support[OpCode::LE_CONNECTION_CTE_REQUEST_ENABLE] = true;
  expected_support[OpCode::LE_CONNECTION_CTE_RESPONSE_ENABLE] = true;
  expected_support[OpCode::LE_READ_ANTENNA_INFORMATION] = true;
  expected_support[OpCode::LE_SET_PERIODIC_ADVERTISING_RECEIVE_ENABLE] = true;
  expected_support[OpCode::LE_PERIODIC_ADVERTISING_SYNC_TRANSFER] = true;
  expected_support[OpCode::LE_PERIODIC_ADVERTISING_SET_INFO_TRANSFER] = true;
  expected_support[OpCode::LE_SET_PERIODIC_ADVERTISING_SYNC_TRANSFER_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_DEFAULT_PERIODIC_ADVERTISING_SYNC_TRANSFER_PARAMETERS] = true;
  expected_support[OpCode::LE_GENERATE_DHKEY_V2] = true;
  expected_support[OpCode::LE_MODIFY_SLEEP_CLOCK_ACCURACY] = true;
  expected_support[OpCode::LE_READ_BUFFER_SIZE_V2] = true;
  expected_support[OpCode::LE_READ_ISO_TX_SYNC] = true;
  expected_support[OpCode::LE_SET_CIG_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_CIG_PARAMETERS_TEST] = true;
  expected_support[OpCode::LE_CREATE_CIS] = true;
  expected_support[OpCode::LE_REMOVE_CIG] = true;
  expected_support[OpCode::LE_ACCEPT_CIS_REQUEST] = true;
  expected_support[OpCode::LE_REJECT_CIS_REQUEST] = true;
  expected_support[OpCode::LE_CREATE_BIG] = true;
  expected_support[OpCode::LE_CREATE_BIG_TEST] = true;
  expected_support[OpCode::LE_TERMINATE_BIG] = true;
  expected_support[OpCode::LE_BIG_CREATE_SYNC] = true;
  expected_support[OpCode::LE_BIG_TERMINATE_SYNC] = true;
  expected_support[OpCode::LE_REQUEST_PEER_SCA] = true;
  expected_support[OpCode::LE_SETUP_ISO_DATA_PATH] = true;
  expected_support[OpCode::LE_REMOVE_ISO_DATA_PATH] = true;
  expected_support[OpCode::LE_ISO_TRANSMIT_TEST] = true;
  expected_support[OpCode::LE_ISO_RECEIVE_TEST] = true;
  expected_support[OpCode::LE_ISO_READ_TEST_COUNTERS] = true;
  expected_support[OpCode::LE_ISO_TEST_END] = true;
  expected_support[OpCode::LE_SET_HOST_FEATURE] = true;
  expected_support[OpCode::LE_READ_ISO_LINK_QUALITY] = true;
  expected_support[OpCode::LE_ENHANCED_READ_TRANSMIT_POWER_LEVEL] = true;
  expected_support[OpCode::LE_READ_REMOTE_TRANSMIT_POWER_LEVEL] = true;
  expected_support[OpCode::LE_SET_PATH_LOSS_REPORTING_PARAMETERS] = true;
  expected_support[OpCode::LE_SET_PATH_LOSS_REPORTING_ENABLE] = true;
  expected_support[OpCode::LE_SET_TRANSMIT_POWER_REPORTING_ENABLE] = true;
  expected_support[OpCode::LE_TRANSMITTER_TEST_V4] = true;
  expected_support[OpCode::LE_SET_DATA_RELATED_ADDRESS_CHANGES] = true;
  expected_support[OpCode::LE_SET_DEFAULT_SUBRATE] = true;
  expected_support[OpCode::LE_SUBRATE_REQUEST] = true;
  // Mark specific opcodes as not supported based on the default mock setup.
  expected_support[OpCode::ADD_SCO_CONNECTION] = false;
  expected_support[OpCode::READ_LOCAL_SUPPORTED_COMMANDS] = true;
  expected_support[OpCode::NONE] = false;

  // Mark MSFT opcodes as not supported.
  expected_support[OpCode::MSFT_OPCODE_INTEL] = false;
  expected_support[OpCode::MSFT_OPCODE_MEDIATEK] = false;
  expected_support[OpCode::MSFT_OPCODE_QUALCOMM] = false;

  // The is_supported() method has logic for vendor-specific OpCodes.
  // We need to test these separately.

  // Iterate through all OpCode values to ensure full coverage.
  // Note: This requires a mechanism to iterate through all OpCode enum values.
  // Assuming a helper function exists for this purpose.
  for (const auto& pair : expected_support) {
    OpCode op_code = pair.first;
    bool expected = pair.second;
    ASSERT_EQ(controller_->IsSupported(op_code), expected)
            << "Opcode " << OpCodeText(op_code) << " failed";
  }
}

TEST_F(ControllerTest, LeChannelSoundingOpCodesNotSupported) {
  std::vector<OpCode> cs_opcodes = {
          OpCode::LE_CS_READ_LOCAL_SUPPORTED_CAPABILITIES,
          OpCode::LE_CS_READ_REMOTE_SUPPORTED_CAPABILITIES,
          OpCode::LE_CS_WRITE_CACHED_REMOTE_SUPPORTED_CAPABILITIES,
          OpCode::LE_CS_SECURITY_ENABLE,
          OpCode::LE_CS_SET_DEFAULT_SETTINGS,
          OpCode::LE_CS_READ_REMOTE_FAE_TABLE,
          OpCode::LE_CS_WRITE_CACHED_REMOTE_FAE_TABLE,
          OpCode::LE_CS_CREATE_CONFIG,
          OpCode::LE_CS_REMOVE_CONFIG,
          OpCode::LE_CS_SET_CHANNEL_CLASSIFICATION,
          OpCode::LE_CS_PROCEDURE_ENABLE,
          OpCode::LE_CS_TEST,
          OpCode::LE_CS_TEST_END,
          OpCode::LE_CS_SET_PROCEDURE_PARAMETERS,
  };

  for (OpCode opcode : cs_opcodes) {
    ASSERT_FALSE(controller_->IsSupported(opcode))
            << "Opcode " << OpCodeText(opcode) << " should not be supported";
  }
}

TEST_F(ControllerTest, Dump) {
  // Use a pipe to capture the output of the dump function.
  int pipefd[2];
  ASSERT_EQ(pipe(pipefd), 0);
  int read_fd = pipefd[0];
  int write_fd = pipefd[1];

  // Call the dump function with the write end of the pipe.
  controller_->Dump(write_fd);
  close(write_fd);

  // Read the output from the pipe.
  char buffer[1024];
  ssize_t bytes_read = read(read_fd, buffer, sizeof(buffer) - 1);
  close(read_fd);

  // Assert that some data was written to the pipe.
  ASSERT_GT(bytes_read, 0);
  buffer[bytes_read] = '\0';

  // Optionally, you can assert that the output contains expected strings.
  // For example, checking for a key phrase in the dump output.
  ASSERT_NE(std::string(buffer).find("HCI ControllerImpl Dumpsys:"), std::string::npos);
}
}  // namespace hci
}  // namespace bluetooth
