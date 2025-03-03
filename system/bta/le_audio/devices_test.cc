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

#include "devices.h"

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include "btif_storage_mock.h"
#include "btm_api_mock.h"
#include "device_groups.h"
#include "hardware/bt_le_audio.h"
#include "hci/controller_interface_mock.h"
#include "le_audio/le_audio_utils.h"
#include "le_audio_set_configuration_provider.h"
#include "le_audio_types.h"
#include "mock_codec_manager.h"
#include "mock_csis_client.h"
#include "stack/btm/btm_int_types.h"
#include "test/mock/mock_main_shim_entry.h"

const tBLE_BD_ADDR BTM_Sec_GetAddressWithType(const RawAddress& bd_addr) {
  return tBLE_BD_ADDR{.type = BLE_ADDR_PUBLIC, .bda = bd_addr};
}

namespace bluetooth {
namespace le_audio {
namespace internal {
namespace {

using ::bluetooth::le_audio::DeviceConnectState;
using ::bluetooth::le_audio::LeAudioDevice;
using ::bluetooth::le_audio::LeAudioDeviceGroup;
using ::bluetooth::le_audio::LeAudioDevices;
using ::bluetooth::le_audio::types::AseState;
using ::bluetooth::le_audio::types::AudioContexts;
using ::bluetooth::le_audio::types::AudioLocations;
using ::bluetooth::le_audio::types::BidirectionalPair;
using ::bluetooth::le_audio::types::CisType;
using ::bluetooth::le_audio::types::LeAudioContextType;
using testing::_;
using testing::Invoke;
using testing::NiceMock;
using testing::Return;
using testing::Test;

auto constexpr kVendorCodecIdOne = bluetooth::le_audio::types::LeAudioCodecId(
        {.coding_format = types::kLeAudioCodingFormatVendorSpecific,
         .vendor_company_id = 0xF00D,
         .vendor_codec_id = 0x0001});

types::CodecConfigSetting kVendorCodecOne = {
        .id = kVendorCodecIdOne,
        .params = types::LeAudioLtvMap({
                // Add the Sampling Freq and AudioChannelAllocation which are
                // mandatory even for the Vendor codec provider (multicodec AIDL)
                {codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                 UINT8_TO_VEC_UINT8(codec_spec_conf::kLeAudioSamplingFreq16000Hz)},
        }),
        // Some opaque data buffer
        .vendor_params = std::vector<uint8_t>({0x01, 0xC0, 0xDE, 0xF0, 0x0D}),
        .channel_count_per_iso_stream = 1,
};

types::CodecConfigSetting kVendorCodecOneSwb = {
        .id = kVendorCodecIdOne,
        .params = types::LeAudioLtvMap({
                // Add the Sampling Freq and AudioChannelAllocation which are
                // mandatory even for the Vendor codec provider (multicodec AIDL)
                {codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                 UINT8_TO_VEC_UINT8(codec_spec_conf::kLeAudioSamplingFreq32000Hz)},
        }),
        // Some opaque data buffer
        .vendor_params = std::vector<uint8_t>({0x01, 0xC0, 0xDE, 0xF0, 0x0F}),
        .channel_count_per_iso_stream = 1,
};

RawAddress GetTestAddress(int index) {
  EXPECT_LT(index, UINT8_MAX);
  RawAddress result = {{0xC0, 0xDE, 0xC0, 0xDE, 0x00, static_cast<uint8_t>(index)}};
  return result;
}

class LeAudioDevicesTest : public Test {
protected:
  void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com::android::bluetooth::flags::provider_->reset_flags();
    devices_ = new LeAudioDevices();
    bluetooth::manager::SetMockBtmInterface(&btm_interface);
    bluetooth::storage::SetMockBtifStorageInterface(&mock_btif_storage_);
  }

  void TearDown() override {
    bluetooth::manager::SetMockBtmInterface(nullptr);
    bluetooth::storage::SetMockBtifStorageInterface(nullptr);
    delete devices_;
  }

  LeAudioDevices* devices_ = nullptr;
  bluetooth::manager::MockBtmInterface btm_interface;
  bluetooth::storage::MockBtifStorageInterface mock_btif_storage_;
};

TEST_F(LeAudioDevicesTest, test_add) {
  RawAddress test_address_0 = GetTestAddress(0);
  ASSERT_EQ((size_t)0, devices_->Size());
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  ASSERT_EQ((size_t)1, devices_->Size());
  devices_->Add(GetTestAddress(1), DeviceConnectState::CONNECTING_BY_USER, 1);
  ASSERT_EQ((size_t)2, devices_->Size());
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  ASSERT_EQ((size_t)2, devices_->Size());
  devices_->Add(GetTestAddress(1), DeviceConnectState::CONNECTING_BY_USER, 2);
  ASSERT_EQ((size_t)2, devices_->Size());
}

TEST_F(LeAudioDevicesTest, test_remove) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_1 = GetTestAddress(1);
  devices_->Add(test_address_1, DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_2 = GetTestAddress(2);
  devices_->Add(test_address_2, DeviceConnectState::CONNECTING_BY_USER);
  ASSERT_EQ((size_t)3, devices_->Size());
  devices_->Remove(test_address_0);
  ASSERT_EQ((size_t)2, devices_->Size());
  devices_->Remove(GetTestAddress(3));
  ASSERT_EQ((size_t)2, devices_->Size());
  devices_->Remove(test_address_0);
  ASSERT_EQ((size_t)2, devices_->Size());
}

TEST_F(LeAudioDevicesTest, test_find_by_address_success) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_1 = GetTestAddress(1);
  devices_->Add(test_address_1, DeviceConnectState::DISCONNECTED);
  RawAddress test_address_2 = GetTestAddress(2);
  devices_->Add(test_address_2, DeviceConnectState::CONNECTING_BY_USER);
  LeAudioDevice* device = devices_->FindByAddress(test_address_1);
  ASSERT_NE(nullptr, device);
  ASSERT_EQ(test_address_1, device->address_);
}

TEST_F(LeAudioDevicesTest, test_find_by_address_failed) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_2 = GetTestAddress(2);
  devices_->Add(test_address_2, DeviceConnectState::CONNECTING_BY_USER);
  LeAudioDevice* device = devices_->FindByAddress(GetTestAddress(1));
  ASSERT_EQ(nullptr, device);
}

TEST_F(LeAudioDevicesTest, test_get_by_address_success) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_1 = GetTestAddress(1);
  devices_->Add(test_address_1, DeviceConnectState::DISCONNECTED);
  RawAddress test_address_2 = GetTestAddress(2);
  devices_->Add(test_address_2, DeviceConnectState::CONNECTING_BY_USER);
  std::shared_ptr<LeAudioDevice> device = devices_->GetByAddress(test_address_1);
  ASSERT_NE(nullptr, device);
  ASSERT_EQ(test_address_1, device->address_);
}

TEST_F(LeAudioDevicesTest, test_get_by_address_failed) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_2 = GetTestAddress(2);
  devices_->Add(test_address_2, DeviceConnectState::CONNECTING_BY_USER);
  std::shared_ptr<LeAudioDevice> device = devices_->GetByAddress(GetTestAddress(1));
  ASSERT_EQ(nullptr, device);
}

TEST_F(LeAudioDevicesTest, test_find_by_conn_id_success) {
  devices_->Add(GetTestAddress(1), DeviceConnectState::CONNECTING_BY_USER);
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  devices_->Add(GetTestAddress(4), DeviceConnectState::CONNECTING_BY_USER);
  LeAudioDevice* device = devices_->FindByAddress(test_address_0);
  device->conn_id_ = 0x0005;
  ASSERT_EQ(device, devices_->FindByConnId(0x0005));
}

TEST_F(LeAudioDevicesTest, test_find_by_conn_id_failed) {
  devices_->Add(GetTestAddress(1), DeviceConnectState::CONNECTING_BY_USER);
  devices_->Add(GetTestAddress(0), DeviceConnectState::CONNECTING_BY_USER);
  devices_->Add(GetTestAddress(4), DeviceConnectState::CONNECTING_BY_USER);
  ASSERT_EQ(nullptr, devices_->FindByConnId(0x0006));
}

TEST_F(LeAudioDevicesTest, test_get_device_model_name_success) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  std::shared_ptr<LeAudioDevice> device = devices_->GetByAddress(test_address_0);
  ASSERT_NE(nullptr, device);
  device->model_name_ = "Test";
  ON_CALL(mock_btif_storage_, GetRemoteDeviceProperty(_, _))
          .WillByDefault(Return(BT_STATUS_SUCCESS));
  device->GetDeviceModelName();
  ASSERT_EQ("", device->model_name_);
}

TEST_F(LeAudioDevicesTest, test_get_device_model_name_failed) {
  RawAddress test_address_0 = GetTestAddress(0);
  devices_->Add(test_address_0, DeviceConnectState::CONNECTING_BY_USER);
  std::shared_ptr<LeAudioDevice> device = devices_->GetByAddress(test_address_0);
  ASSERT_NE(nullptr, device);
  device->model_name_ = "Test";
  ON_CALL(mock_btif_storage_, GetRemoteDeviceProperty(_, _)).WillByDefault(Return(BT_STATUS_FAIL));
  device->GetDeviceModelName();
  ASSERT_EQ("Test", device->model_name_);
}

/* TODO: Add FindByCisConnHdl test cases (ASE) */

}  // namespace

namespace {
using namespace ::bluetooth::le_audio::codec_spec_caps;
using namespace ::bluetooth::le_audio::types;

static const hdl_pair hdl_pair_nil = hdl_pair(0x0000, 0x0000);

enum class Lc3SettingId {
  _BEGIN,
  LC3_8_1 = _BEGIN,
  LC3_8_2,
  LC3_16_1,
  LC3_16_2,
  LC3_24_1,
  LC3_24_2,
  LC3_32_1,
  LC3_32_2,
  LC3_441_1,
  LC3_441_2,
  LC3_48_1,
  LC3_48_2,
  LC3_48_3,
  LC3_48_4,
  LC3_48_5,
  LC3_48_6,
  LC3_VND_1,
  _END,
  UNSUPPORTED = _END,
};
static constexpr int Lc3SettingIdBegin = static_cast<int>(Lc3SettingId::_BEGIN);
static constexpr int Lc3SettingIdEnd = static_cast<int>(Lc3SettingId::_END);

bool IsLc3SettingSupported(LeAudioContextType context_type, Lc3SettingId id) {
  /* Update those values, on any change of codec linked with content type */
  switch (context_type) {
    case LeAudioContextType::RINGTONE:
    case LeAudioContextType::CONVERSATIONAL:
      if (id == Lc3SettingId::LC3_16_1 || id == Lc3SettingId::LC3_16_2 ||
          id == Lc3SettingId::LC3_24_1 || id == Lc3SettingId::LC3_24_2 ||
          id == Lc3SettingId::LC3_32_1 || id == Lc3SettingId::LC3_32_2 ||
          id == Lc3SettingId::LC3_48_1 || id == Lc3SettingId::LC3_48_2 ||
          id == Lc3SettingId::LC3_48_3 || id == Lc3SettingId::LC3_48_4 ||
          id == Lc3SettingId::LC3_VND_1) {
        return true;
      }

      break;

    case LeAudioContextType::MEDIA:
    case LeAudioContextType::ALERTS:
    case LeAudioContextType::INSTRUCTIONAL:
    case LeAudioContextType::NOTIFICATIONS:
    case LeAudioContextType::EMERGENCYALARM:
    case LeAudioContextType::UNSPECIFIED:
      if (id == Lc3SettingId::LC3_16_1 || id == Lc3SettingId::LC3_16_2 ||
          id == Lc3SettingId::LC3_48_4 || id == Lc3SettingId::LC3_48_1 ||
          id == Lc3SettingId::LC3_48_2 || id == Lc3SettingId::LC3_VND_1 ||
          id == Lc3SettingId::LC3_24_2) {
        return true;
      }

      break;

    default:
      if (id == Lc3SettingId::LC3_16_2) {
        return true;
      }

      break;
  };

  return false;
}

static constexpr uint8_t kLeAudioSamplingFreqRfu = 0x0E;
uint8_t GetSamplingFrequency(Lc3SettingId id) {
  switch (id) {
    case Lc3SettingId::LC3_8_1:
    case Lc3SettingId::LC3_8_2:
      return codec_spec_conf::kLeAudioSamplingFreq8000Hz;
    case Lc3SettingId::LC3_16_1:
    case Lc3SettingId::LC3_16_2:
      return codec_spec_conf::kLeAudioSamplingFreq16000Hz;
    case Lc3SettingId::LC3_24_1:
    case Lc3SettingId::LC3_24_2:
      return codec_spec_conf::kLeAudioSamplingFreq24000Hz;
    case Lc3SettingId::LC3_32_1:
    case Lc3SettingId::LC3_32_2:
      return codec_spec_conf::kLeAudioSamplingFreq32000Hz;
    case Lc3SettingId::LC3_441_1:
    case Lc3SettingId::LC3_441_2:
      return codec_spec_conf::kLeAudioSamplingFreq44100Hz;
    case Lc3SettingId::LC3_48_1:
    case Lc3SettingId::LC3_48_2:
    case Lc3SettingId::LC3_48_3:
    case Lc3SettingId::LC3_48_4:
    case Lc3SettingId::LC3_48_5:
    case Lc3SettingId::LC3_48_6:
    case Lc3SettingId::LC3_VND_1:
      return codec_spec_conf::kLeAudioSamplingFreq48000Hz;
    case Lc3SettingId::UNSUPPORTED:
      return kLeAudioSamplingFreqRfu;
  }
}

static constexpr uint8_t kLeAudioCodecFrameDurRfu = 0x02;
uint8_t GetFrameDuration(Lc3SettingId id) {
  switch (id) {
    case Lc3SettingId::LC3_8_1:
    case Lc3SettingId::LC3_16_1:
    case Lc3SettingId::LC3_24_1:
    case Lc3SettingId::LC3_32_1:
    case Lc3SettingId::LC3_441_1:
    case Lc3SettingId::LC3_48_1:
    case Lc3SettingId::LC3_48_3:
    case Lc3SettingId::LC3_48_5:
      return codec_spec_conf::kLeAudioCodecFrameDur7500us;
    case Lc3SettingId::LC3_8_2:
    case Lc3SettingId::LC3_16_2:
    case Lc3SettingId::LC3_24_2:
    case Lc3SettingId::LC3_32_2:
    case Lc3SettingId::LC3_441_2:
    case Lc3SettingId::LC3_48_2:
    case Lc3SettingId::LC3_48_4:
    case Lc3SettingId::LC3_48_6:
    case Lc3SettingId::LC3_VND_1:
      return codec_spec_conf::kLeAudioCodecFrameDur10000us;
    case Lc3SettingId::UNSUPPORTED:
      return kLeAudioCodecFrameDurRfu;
  }
}

static constexpr uint8_t kLeAudioCodecLC3OctetsPerCodecFrameInvalid = 0;
uint16_t GetOctetsPerCodecFrame(Lc3SettingId id) {
  switch (id) {
    case Lc3SettingId::LC3_8_1:
      return 26;
    case Lc3SettingId::LC3_8_2:
    case Lc3SettingId::LC3_16_1:
      return 30;
    case Lc3SettingId::LC3_16_2:
      return 40;
    case Lc3SettingId::LC3_24_1:
      return 45;
    case Lc3SettingId::LC3_24_2:
    case Lc3SettingId::LC3_32_1:
      return 60;
    case Lc3SettingId::LC3_32_2:
      return 80;
    case Lc3SettingId::LC3_441_1:
      return 97;
    case Lc3SettingId::LC3_441_2:
      return 130;
    case Lc3SettingId::LC3_48_1:
      return 75;
    case Lc3SettingId::LC3_48_2:
    case Lc3SettingId::LC3_VND_1:
      return 100;
    case Lc3SettingId::LC3_48_3:
      return 90;
    case Lc3SettingId::LC3_48_4:
      return 120;
    case Lc3SettingId::LC3_48_5:
      return 116;
    case Lc3SettingId::LC3_48_6:
      return 155;
    case Lc3SettingId::UNSUPPORTED:
      return kLeAudioCodecLC3OctetsPerCodecFrameInvalid;
  }
}

class PublishedAudioCapabilitiesBuilder {
public:
  PublishedAudioCapabilitiesBuilder() {}

  void Add(LeAudioCodecId codec_id, uint8_t conf_sampling_frequency, uint8_t conf_frame_duration,
           uint8_t audio_channel_counts, uint16_t octets_per_frame,
           uint8_t codec_frames_per_sdu = 0) {
    uint16_t sampling_frequencies = SamplingFreqConfig2Capability(conf_sampling_frequency);
    uint8_t frame_durations = FrameDurationConfig2Capability(conf_frame_duration);
    uint8_t max_codec_frames_per_sdu = codec_frames_per_sdu;
    uint32_t octets_per_frame_range = octets_per_frame | (octets_per_frame << 16);

    auto ltv_map = LeAudioLtvMap();
    ltv_map.Add(kLeAudioLtvTypeSupportedSamplingFrequencies, (uint16_t)sampling_frequencies)
            .Add(kLeAudioLtvTypeSupportedFrameDurations, (uint8_t)frame_durations)
            .Add(kLeAudioLtvTypeSupportedAudioChannelCounts, (uint8_t)audio_channel_counts)
            .Add(kLeAudioLtvTypeSupportedOctetsPerCodecFrame, (uint32_t)octets_per_frame_range)
            .Add(kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu, (uint8_t)max_codec_frames_per_sdu);

    auto record = acs_ac_record(
            {.codec_id = codec_id,
             .codec_spec_caps = (codec_id.coding_format != kLeAudioCodingFormatVendorSpecific
                                         ? ltv_map
                                         : LeAudioLtvMap()),
             .codec_spec_caps_raw = ltv_map.RawPacket(),
             .metadata = LeAudioLtvMap()});
    pac_records_.push_back(record);
  }

  void Add(LeAudioCodecId codec_id, uint16_t capa_sampling_frequency, uint8_t capa_frame_duration,
           uint8_t audio_channel_counts, uint16_t octets_per_frame_min,
           uint16_t ocets_per_frame_max, uint8_t codec_frames_per_sdu = 1) {
    uint32_t octets_per_frame_range = octets_per_frame_min | (ocets_per_frame_max << 16);

    auto ltv_map = LeAudioLtvMap({
            {kLeAudioLtvTypeSupportedSamplingFrequencies,
             UINT16_TO_VEC_UINT8(capa_sampling_frequency)},
            {kLeAudioLtvTypeSupportedFrameDurations, UINT8_TO_VEC_UINT8(capa_frame_duration)},
            {kLeAudioLtvTypeSupportedAudioChannelCounts, UINT8_TO_VEC_UINT8(audio_channel_counts)},
            {kLeAudioLtvTypeSupportedOctetsPerCodecFrame,
             UINT32_TO_VEC_UINT8(octets_per_frame_range)},
            {kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu,
             UINT8_TO_VEC_UINT8(codec_frames_per_sdu)},
    });
    pac_records_.push_back(
            acs_ac_record({.codec_id = codec_id,
                           // Transparent LTV map capabilities only for the LC3 codec
                           .codec_spec_caps = (codec_id.coding_format == kLeAudioCodingFormatLC3)
                                                      ? ltv_map
                                                      : LeAudioLtvMap(),
                           .codec_spec_caps_raw = ltv_map.RawPacket(),
                           .metadata = LeAudioLtvMap()}));
  }

  void Add(LeAudioCodecId codec_id, const std::vector<uint8_t>& vendor_data,
           uint8_t audio_channel_counts) {
    pac_records_.push_back(
            acs_ac_record({.codec_id = codec_id,
                           .codec_spec_caps = LeAudioLtvMap({
                                   {kLeAudioLtvTypeSupportedAudioChannelCounts,
                                    UINT8_TO_VEC_UINT8(audio_channel_counts)},
                           }),
                           // For now assume that vendor representation of codec capabilities
                           // equals the representation of codec settings
                           .codec_spec_caps_raw = vendor_data,
                           .metadata = LeAudioLtvMap()}));
  }

  void Add(const CodecConfigSetting& setting, uint8_t audio_channel_counts) {
    if (setting.id != LeAudioCodecIdLc3) {
      Add(setting.id, setting.vendor_params, audio_channel_counts);
      return;
    }

    const LeAudioCoreCodecConfig core_config = setting.params.GetAsCoreCodecConfig();
    Add(setting.id, *core_config.sampling_frequency, *core_config.frame_duration,
        audio_channel_counts, *core_config.octets_per_codec_frame);
  }

  void Reset() { pac_records_.clear(); }

  PublishedAudioCapabilities Get() {
    return PublishedAudioCapabilities({{hdl_pair_nil, pac_records_}});
  }

private:
  std::vector<acs_ac_record> pac_records_;
};

struct TestGroupAseConfigurationData {
  LeAudioDevice* device;
  uint8_t audio_channel_counts_snk;
  uint8_t audio_channel_counts_src;

  /* Note, do not confuse ASEs with channels num. */
  uint8_t expected_active_channel_num_snk;
  uint8_t expected_active_channel_num_src;
};

class LeAudioAseConfigurationTest : public Test, public ::testing::WithParamInterface<uint16_t> {
protected:
  uint16_t codec_coding_format_ = 0x0000;

  void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    codec_coding_format_ = GetParam();

    group_ = new LeAudioDeviceGroup(group_id_);
    desired_group_size_ = -1;

    bluetooth::manager::SetMockBtmInterface(&btm_interface_);
    bluetooth::hci::testing::mock_controller_ = &controller_interface_;

    auto codec_location = ::bluetooth::le_audio::types::CodecLocation::HOST;
    bluetooth::le_audio::AudioSetConfigurationProvider::Initialize(codec_location);
    MockCsisClient::SetMockInstanceForTesting(&mock_csis_client_module_);
    ON_CALL(mock_csis_client_module_, Get()).WillByDefault(Return(&mock_csis_client_module_));
    ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));
    ON_CALL(mock_csis_client_module_, GetDeviceList(_))
            .WillByDefault(Invoke([this](int /*group_id*/) { return addresses_; }));
    ON_CALL(mock_csis_client_module_, GetDesiredSize(_))
            .WillByDefault(Invoke([this](int /*group_id*/) {
              return desired_group_size_ > 0 ? desired_group_size_ : (int)(addresses_.size());
            }));
    SetUpMockCodecManager(codec_location);
  }

  static std::vector<AseConfiguration> GetVendorAseConfigurationsForRequirements(
          const bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements& requirements,
          const CodecConfigSetting& codec, uint8_t direction) {
    std::vector<AseConfiguration> ase_confs;

    auto const& required_pacs = (direction == kLeAudioDirectionSink) ? requirements.sink_pacs
                                                                     : requirements.source_pacs;
    auto direction_requirements = (direction == kLeAudioDirectionSink)
                                          ? requirements.sink_requirements
                                          : requirements.source_requirements;

    if (std::count_if(required_pacs->begin(), required_pacs->end(),
                      [](auto const& pac) { return pac.codec_spec_caps_raw.empty(); })) {
      return ase_confs;
    }

    if (!required_pacs.has_value() || (required_pacs->size() == 0)) {
      return ase_confs;
    }

    AseConfiguration endpoint_cfg(codec, {.target_latency = kTargetLatencyLower,
                                          .retransmission_number = 3,
                                          .max_transport_latency = kMaxTransportLatencyMin});

    // Finding the max channel count
    uint32_t target_max_channel_counts_per_ase_bitmap = 0b1;  // bit 0 - one channel
    for (auto const& pac : *required_pacs) {
      auto caps = pac.codec_spec_caps.GetAsCoreCodecCapabilities();
      if (caps.HasSupportedAudioChannelCounts()) {
        auto new_counts = caps.supported_audio_channel_counts.value();
        if (new_counts > target_max_channel_counts_per_ase_bitmap) {
          target_max_channel_counts_per_ase_bitmap = new_counts;
        }
      }
    }

    uint8_t target_max_channel_counts_per_ase = 0;
    while (target_max_channel_counts_per_ase_bitmap) {
      ++target_max_channel_counts_per_ase;
      target_max_channel_counts_per_ase_bitmap = target_max_channel_counts_per_ase_bitmap >> 1;
    }

    // For sink we always put a requirement here, but for source there are
    // some conditions
    auto sourceAsesNeeded =
            (!kLeAudioContextAllRemoteSinkOnly.test(requirements.audio_context_type) ||
             (requirements.audio_context_type == LeAudioContextType::RINGTONE)) &&
            (requirements.audio_context_type != types::LeAudioContextType::UNSPECIFIED);
    if ((direction == kLeAudioDirectionSink) || sourceAsesNeeded) {
      // Create ASE configurations with the proper audio channel allocation
      uint8_t count = 0;
      uint32_t allocations = 0;
      for (auto const& req : *direction_requirements) {
        auto req_allocations = VEC_UINT8_TO_UINT32(
                req.params.At(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation));

        // Create the list of requested audio allocations
        std::list<uint32_t> split_allocations;
        uint8_t bit_pos = 0;
        while (req_allocations) {
          if (req_allocations & 0b1) {
            split_allocations.push_back(1 << bit_pos);
          }
          req_allocations = req_allocations >> 1;
          bit_pos++;
        }

        if (split_allocations.empty()) {
          // Add a single ASE mono configuration
          endpoint_cfg.codec.params.Add(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
                                        (uint32_t)codec_spec_conf::kLeAudioLocationMonoAudio);
          ase_confs.push_back(endpoint_cfg);
          continue;
        }

        // Pick a number of allocations from the list (depending on supported
        // channel counts per ASE) and create an ASE configuration.
        while (split_allocations.size()) {
          auto num_of_allocations_per_ase =
                  std::min(target_max_channel_counts_per_ase, (uint8_t)split_allocations.size());
          // Note: This is very important to set for the unit test
          // Configuration provider
          endpoint_cfg.codec.channel_count_per_iso_stream = num_of_allocations_per_ase;

          // Consume the `num_of_allocations_per_ase` amount of allocations for
          // this particular ASE
          uint32_t ase_allocations = 0;
          while (num_of_allocations_per_ase) {
            ase_allocations |= split_allocations.front();
            split_allocations.pop_front();
            --num_of_allocations_per_ase;
          }
          endpoint_cfg.codec.params.Add(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
                                        ase_allocations);

          // Add the ASE configuration
          ase_confs.push_back(endpoint_cfg);
        }
      }
    }

    return ase_confs;
  }

  static auto MockVendorCodecProvider(
          const bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements& requirements) {
    AudioSetConfiguration cfg = {
            .name = "Example Vendor Codec Configuration",
            .packing = bluetooth::hci::kIsoCigPackingSequential,
            .confs = {.sink = {}, .source = {}},
    };

    CodecConfigSetting codec =
            bluetooth::le_audio::CodecManager::GetInstance()->IsDualBiDirSwbSupported()
                    ? kVendorCodecOneSwb
                    : kVendorCodecOne;
    if (requirements.sink_requirements) {
      cfg.confs.sink =
              GetVendorAseConfigurationsForRequirements(requirements, codec, kLeAudioDirectionSink);
    }

    if (requirements.source_requirements) {
      cfg.confs.source = GetVendorAseConfigurationsForRequirements(requirements, codec,
                                                                   kLeAudioDirectionSource);
    }

    log::debug("{}: snk confs size: {}", cfg.name, cfg.confs.sink.size());
    log::debug("{}: src confs size: {}", cfg.name, cfg.confs.source.size());
    return (!cfg.confs.sink.empty() || !cfg.confs.source.empty())
                   ? std::make_unique<AudioSetConfiguration>(cfg)
                   : nullptr;
  }

  void SetUpMockCodecManager(bluetooth::le_audio::types::CodecLocation location) {
    codec_manager_ = bluetooth::le_audio::CodecManager::GetInstance();
    ASSERT_NE(codec_manager_, nullptr);
    std::vector<bluetooth::le_audio::btle_audio_codec_config_t> mock_offloading_preference(0);
    codec_manager_->Start(mock_offloading_preference);
    mock_codec_manager_ = MockCodecManager::GetInstance();
    ASSERT_NE((void*)mock_codec_manager_, (void*)codec_manager_);
    ASSERT_NE(mock_codec_manager_, nullptr);
    ON_CALL(*mock_codec_manager_, GetCodecLocation()).WillByDefault(Return(location));

    // Set up the config provider for the Lc3 codec
    if (codec_coding_format_ == kLeAudioCodingFormatLC3) {
      // Regardless of the codec location, return all the possible
      // configurations
      ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(true));
    }

    ON_CALL(*mock_codec_manager_, GetCodecConfig)
            .WillByDefault(Invoke(
                    [&](const bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements&
                                requirements,
                        bluetooth::le_audio::CodecManager::UnicastConfigurationProvider provider) {
                      if (codec_coding_format_ == kLeAudioCodingFormatLC3) {
                        auto filtered =
                                *bluetooth::le_audio::AudioSetConfigurationProvider::Get()
                                         ->GetConfigurations(requirements.audio_context_type);
                        // Filter out the dual bidir SWB configurations
                        if (!bluetooth::le_audio::CodecManager::GetInstance()
                                     ->IsDualBiDirSwbSupported()) {
                          filtered.erase(
                                  std::remove_if(filtered.begin(), filtered.end(),
                                                 [](auto const& el) {
                                                   if (el->confs.source.empty()) {
                                                     return false;
                                                   }
                                                   return AudioSetConfigurationProvider::Get()
                                                           ->CheckConfigurationIsDualBiDirSwb(*el);
                                                 }),
                                  filtered.end());
                        }
                        auto cfg = provider(requirements, &filtered);
                        if (cfg == nullptr) {
                          return std::unique_ptr<AudioSetConfiguration>(nullptr);
                        }
                        return std::make_unique<AudioSetConfiguration>(*cfg);
                      } else {
                        return MockVendorCodecProvider(requirements);
                      }
                    }));

    ON_CALL(*mock_codec_manager_, CheckCodecConfigIsBiDirSwb)
            .WillByDefault(
                    Invoke([](const bluetooth::le_audio::types::AudioSetConfiguration& config) {
                      return AudioSetConfigurationProvider::Get()->CheckConfigurationIsBiDirSwb(
                              config);
                    }));
    ON_CALL(*mock_codec_manager_, CheckCodecConfigIsDualBiDirSwb)
            .WillByDefault(
                    Invoke([](const bluetooth::le_audio::types::AudioSetConfiguration& config) {
                      return AudioSetConfigurationProvider::Get()->CheckConfigurationIsDualBiDirSwb(
                              config);
                    }));
  }

  void TearDown() override {
    bluetooth::manager::SetMockBtmInterface(nullptr);
    devices_.clear();
    addresses_.clear();
    delete group_;
    ::bluetooth::le_audio::AudioSetConfigurationProvider::Cleanup();

    if (mock_codec_manager_) {
      testing::Mock::VerifyAndClearExpectations(mock_codec_manager_);
    }
    if (codec_manager_) {
      codec_manager_->Stop();
    }
  }

  LeAudioDevice* AddTestDevice(int snk_ase_num, int src_ase_num, int snk_ase_num_cached = 0,
                               int src_ase_num_cached = 0, bool invert_ases_emplacement = false,
                               bool out_of_range_device = false,
                               std::optional<uint32_t> snk_allocation = kChannelAllocationStereo,
                               std::optional<uint32_t> src_allocation = kChannelAllocationStereo) {
    int index = group_->Size() + 1;
    auto device = (std::make_shared<LeAudioDevice>(GetTestAddress(index),
                                                   DeviceConnectState::DISCONNECTED));
    devices_.push_back(device);
    addresses_.push_back(device->address_);
    log::info("Number of devices {}", (int)(addresses_.size()));

    if (out_of_range_device == false) {
      group_->AddNode(device);
    }

    int ase_id = 1;
    for (int i = 0; i < (invert_ases_emplacement ? snk_ase_num : src_ase_num); i++) {
      device->ases_.emplace_back(
              0x0000, 0x0000,
              invert_ases_emplacement ? kLeAudioDirectionSink : kLeAudioDirectionSource, ase_id++);
    }

    for (int i = 0; i < (invert_ases_emplacement ? src_ase_num : snk_ase_num); i++) {
      device->ases_.emplace_back(
              0x0000, 0x0000,
              invert_ases_emplacement ? kLeAudioDirectionSource : kLeAudioDirectionSink, ase_id++);
    }

    for (int i = 0; i < (invert_ases_emplacement ? snk_ase_num_cached : src_ase_num_cached); i++) {
      struct ase ase(0x0000, 0x0000,
                     invert_ases_emplacement ? kLeAudioDirectionSink : kLeAudioDirectionSource,
                     ase_id++);
      ase.state = AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;
      device->ases_.push_back(ase);
    }

    for (int i = 0; i < (invert_ases_emplacement ? src_ase_num_cached : snk_ase_num_cached); i++) {
      struct ase ase(0x0000, 0x0000,
                     invert_ases_emplacement ? kLeAudioDirectionSource : kLeAudioDirectionSink,
                     ase_id++);
      ase.state = AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;
      device->ases_.push_back(ase);
    }

    device->SetSupportedContexts({.sink = AudioContexts(kLeAudioContextAllTypes),
                                  .source = AudioContexts(kLeAudioContextAllTypes)});
    device->SetAvailableContexts({.sink = AudioContexts(kLeAudioContextAllTypes),
                                  .source = AudioContexts(kLeAudioContextAllTypes)});

    if (snk_allocation) {
      device->audio_locations_.sink.emplace(hdl_pair(0x00ea, 0x0eb),
                                            types::AudioLocations(snk_allocation.value()));
    }

    if (src_allocation) {
      device->audio_locations_.source.emplace(hdl_pair(0x00fa, 0x00fb),
                                              types::AudioLocations(src_allocation.value()));
    }

    device->conn_id_ = index;
    device->SetConnectionState(out_of_range_device ? DeviceConnectState::DISCONNECTED
                                                   : DeviceConnectState::CONNECTED);
    group_->ReloadAudioDirections();
    group_->ReloadAudioLocations();
    return device.get();
  }

  LeAudioDevice* AddTestDevice(std::optional<std::pair<int, uint32_t>> sink,
                               std::optional<std::pair<int, uint32_t>> source = std::nullopt) {
    return AddTestDevice(sink ? sink->first : 0, source ? source->first : 0, 0, 0, false, false,
                         sink ? sink->second : 0, source ? source->second : 0);
  }

  bool TestGroupAseConfigurationVerdict(const TestGroupAseConfigurationData& data,
                                        uint8_t directions_to_verify) {
    BidirectionalPair<uint8_t> active_channel_num = {0, 0};

    if (directions_to_verify == 0) {
      return false;
    }
    if (data.device->HaveActiveAse() == 0) {
      return false;
    }

    for (ase* ase = data.device->GetFirstActiveAse(); ase;
         ase = data.device->GetNextActiveAse(ase)) {
      active_channel_num.get(ase->direction) += ase->codec_config.channel_count_per_iso_stream;
    }

    bool result = true;
    if (directions_to_verify & kLeAudioDirectionSink) {
      result &= (data.expected_active_channel_num_snk ==
                 active_channel_num.get(kLeAudioDirectionSink));
    }
    if (directions_to_verify & kLeAudioDirectionSource) {
      result &= (data.expected_active_channel_num_src ==
                 active_channel_num.get(kLeAudioDirectionSource));
    }
    return result;
  }

  void SetCisInformationToActiveAse(void) {
    uint8_t cis_id = 1;
    uint16_t cis_conn_hdl = 0x0060;

    for (auto& device : devices_) {
      for (auto& ase : device->ases_) {
        if (ase.active) {
          ase.cis_id = cis_id++;
          ase.cis_conn_hdl = cis_conn_hdl++;
        }
      }
    }
  }

  const CodecConfigSetting PreparePreferredCodecConfig(
          const CodecConfigSetting& audio_set_codec_conf,
          const btle_audio_codec_config_t& preferred_config) {
    constexpr uint8_t supported_codec_frames_per_sdu = 1;
    return {.id = LeAudioCodecIdLc3,
            .params = LeAudioLtvMap({
                    {codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                     UINT8_TO_VEC_UINT8(codec_spec_conf::SingleSamplingFreqCapability2Config(
                             preferred_config.sample_rate))},
                    {codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                     UINT8_TO_VEC_UINT8(codec_spec_conf::SingleFrameDurationCapability2Config(
                             preferred_config.frame_duration))},
                    {codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
                     UINT16_TO_VEC_UINT8(preferred_config.octets_per_frame)},
                    {codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                     UINT8_TO_VEC_UINT8(supported_codec_frames_per_sdu)},
            }),
            .channel_count_per_iso_stream = audio_set_codec_conf.GetChannelCountPerIsoStream()};
  }

  void TestSingleAseConfiguration(LeAudioContextType context_type,
                                  TestGroupAseConfigurationData* data, uint8_t data_size,
                                  const AudioSetConfiguration* audio_set_conf,
                                  uint8_t directions_to_verify) {
    // the configuration should fail if there are no active ases expected
    bool success_expected = data_size > 0;
    uint8_t configuration_directions = 0;

    for (int i = 0; i < data_size; i++) {
      success_expected &= (data[i].expected_active_channel_num_snk +
                           data[i].expected_active_channel_num_src) > 0;

      /* Prepare PAC's */
      PublishedAudioCapabilitiesBuilder snk_pac_builder, src_pac_builder;
      for (const auto& entry : (*audio_set_conf).confs.sink) {
        configuration_directions |= kLeAudioDirectionSink;
        snk_pac_builder.Add(entry.codec, data[i].audio_channel_counts_snk);
      }
      for (const auto& entry : (*audio_set_conf).confs.source) {
        configuration_directions |= kLeAudioDirectionSource;
        src_pac_builder.Add(entry.codec, data[i].audio_channel_counts_src);
      }

      data[i].device->snk_pacs_ = snk_pac_builder.Get();
      data[i].device->src_pacs_ = src_pac_builder.Get();
    }

    BidirectionalPair<AudioContexts> group_audio_locations = {
            .sink = AudioContexts(context_type), .source = AudioContexts(context_type)};

    ASSERT_EQ(success_expected, group_->Configure(context_type, group_audio_locations));

    bool result = true;
    for (int i = 0; i < data_size; i++) {
      result &= TestGroupAseConfigurationVerdict(data[i],
                                                 directions_to_verify & configuration_directions);
    }
    ASSERT_TRUE(result);
  }

  int getNumOfAses(LeAudioDevice* device, uint8_t direction) {
    return std::count_if(device->ases_.begin(), device->ases_.end(),
                         [direction](auto& a) { return a.direction == direction; });
  }

  void TestGroupAseVendorConfiguration(LeAudioContextType context_type,
                                       TestGroupAseConfigurationData* data, uint8_t data_size,
                                       uint8_t directions_to_verify = kLeAudioDirectionSink |
                                                                      kLeAudioDirectionSource) {
    for (int i = 0; i < data_size; i++) {
      /* Add PACs and check if each of the devices has activated ASEs as
       * expected */
      PublishedAudioCapabilitiesBuilder snk_pac_builder, src_pac_builder;

      // Prepare the PACs
      for (auto direction : {kLeAudioDirectionSink, kLeAudioDirectionSource}) {
        auto const& data_channel_counts = (direction == kLeAudioDirectionSink)
                                                  ? data[i].audio_channel_counts_snk
                                                  : data[i].audio_channel_counts_src;

        PublishedAudioCapabilitiesBuilder pac_builder;
        for (auto codec : {kVendorCodecOne, kVendorCodecOneSwb}) {
          codec.channel_count_per_iso_stream = data_channel_counts;
          pac_builder.Add(codec, data_channel_counts);
        }

        // Set the PACs
        auto& dest_pacs = (direction == kLeAudioDirectionSink) ? data[i].device->snk_pacs_
                                                               : data[i].device->src_pacs_;
        dest_pacs = pac_builder.Get();
      }
    }

    // Verify if ASEs are configured
    BidirectionalPair<AudioContexts> metadata = {.sink = AudioContexts(context_type),
                                                 .source = AudioContexts(context_type)};
    ASSERT_EQ(true, group_->Configure(context_type, metadata));

    for (int i = 0; i < data_size; i++) {
      ASSERT_TRUE(TestGroupAseConfigurationVerdict(data[i], directions_to_verify));
    }

    group_->Deactivate();
    TestAsesInactive();
  }

  void TestGroupAseConfiguration(LeAudioContextType context_type,
                                 TestGroupAseConfigurationData* data, uint8_t data_size,
                                 uint8_t directions_to_verify = kLeAudioDirectionSink |
                                                                kLeAudioDirectionSource,
                                 btle_audio_codec_config_t* preferred_codec_config = nullptr,
                                 bool should_use_preferred_codec = false) {
    if (codec_coding_format_ != kLeAudioCodingFormatLC3) {
      return TestGroupAseVendorConfiguration(context_type, data, data_size, directions_to_verify);
    }

    const auto* configurations =
            ::bluetooth::le_audio::AudioSetConfigurationProvider::Get()->GetConfigurations(
                    context_type);

    bool is_expected_to_match_config = directions_to_verify != 0;
    int num_of_matching_configurations = 0;
    for (const auto& audio_set_conf : *configurations) {
      bool interesting_configuration = true;
      uint8_t expected_configuration_directions = 0;

      /* Let's go thru devices in the group and configure them*/
      for (int i = 0; i < data_size; i++) {
        BidirectionalPair<int> num_of_ase{0, 0};

        /* Prepare PAC's for each device. Also make sure configuration is in our
         * interest to test */
        for (auto direction : {kLeAudioDirectionSink, kLeAudioDirectionSource}) {
          auto const& ase_confs = audio_set_conf->confs;
          if (ase_confs.get(direction).size() == 0) {
            // Skip the direction if not available
            continue;
          }

          /* Make sure the strategy is the expected one */
          auto ase_config_strategy = bluetooth::le_audio::utils::GetStrategyForAseConfig(
                  ase_confs.get(direction), data_size);
          if (group_->GetGroupSinkStrategy() != ase_config_strategy) {
            log::debug("Sink strategy mismatch group!=cfg.entry ({}!={})",
                       static_cast<int>(group_->GetGroupSinkStrategy()),
                       static_cast<int>(ase_config_strategy));
            interesting_configuration = false;
          }

          auto& dest_pacs = (direction == kLeAudioDirectionSink) ? data[i].device->snk_pacs_
                                                                 : data[i].device->src_pacs_;
          auto const& pacs_data_channel_counts = (direction == kLeAudioDirectionSink)
                                                         ? data[i].audio_channel_counts_snk
                                                         : data[i].audio_channel_counts_src;

          if (((direction == kLeAudioDirectionSink)
                       ? data[i].expected_active_channel_num_snk
                       : data[i].expected_active_channel_num_src) > 0) {
            expected_configuration_directions |= direction;
          }

          /* Add PAC records */
          PublishedAudioCapabilitiesBuilder pac_builder;
          for (const auto& entry : ase_confs.get(direction)) {
            pac_builder.Add(entry.codec, pacs_data_channel_counts);
            if (preferred_codec_config && should_use_preferred_codec) {
              const auto customized_codec_config =
                      PreparePreferredCodecConfig(entry.codec, *preferred_codec_config);
              pac_builder.Add(customized_codec_config, pacs_data_channel_counts);
            }
            dest_pacs = pac_builder.Get();
          }
          num_of_ase.get(direction) += ase_confs.get(direction).size();
          num_of_ase.get(direction) /= data_size;
        }

        /* Make sure configuration can satisfy number of expected active ASEs*/
        if (num_of_ase.sink > data[i].device->GetAseCount(kLeAudioDirectionSink)) {
          interesting_configuration = false;
        }

        if (num_of_ase.source > data[i].device->GetAseCount(kLeAudioDirectionSource)) {
          interesting_configuration = false;
        }
      }

      /* Set preferred codec*/
      if (preferred_codec_config) {
        group_->SetPreferredAudioSetConfiguration(*preferred_codec_config, *preferred_codec_config);
      }

      group_->UpdateAudioSetConfigurationCache(context_type);

      BidirectionalPair<AudioContexts> group_audio_locations = {
              .sink = AudioContexts(context_type), .source = AudioContexts(context_type)};
      auto configuration_result = group_->Configure(context_type, group_audio_locations);

      /* In case of configuration #ase is same as the one we expected to be
       * activated verify, ASEs are actually active */
      uint8_t configuration_directions =
              (audio_set_conf->confs.sink.size() ? kLeAudioDirectionSink : 0) |
              (audio_set_conf->confs.source.size() ? kLeAudioDirectionSource : 0);

      if (interesting_configuration &&
          (expected_configuration_directions == configuration_directions)) {
        ASSERT_TRUE(configuration_result);
        ASSERT_EQ(group_->GetPreferredConfiguration(context_type) != nullptr,
                  should_use_preferred_codec);
        ASSERT_EQ(group_->IsUsingPreferredAudioSetConfiguration(context_type),
                  should_use_preferred_codec);
        bool matching_conf = true;
        /* Check if each of the devices has activated ASEs as expected */
        for (int i = 0; i < data_size; i++) {
          matching_conf &= TestGroupAseConfigurationVerdict(data[i], configuration_directions);
        }

        if (matching_conf) {
          num_of_matching_configurations++;
        }
      }
      group_->Deactivate();

      TestAsesInactive();
    }

    if (is_expected_to_match_config) {
      ASSERT_GT(num_of_matching_configurations, 0);
    } else {
      ASSERT_EQ(0, num_of_matching_configurations);
    }
  }

  auto TestGroupAseConfiguration(LeAudioContextType context_type,
                                 std::vector<TestGroupAseConfigurationData> data,
                                 uint8_t directions_to_verify = kLeAudioDirectionSink |
                                                                kLeAudioDirectionSource,
                                 btle_audio_codec_config_t* preferred_codec_config = nullptr,
                                 bool should_use_preferred_codec = false) {
    return TestGroupAseConfiguration(context_type, data.data(), data.size(), directions_to_verify,
                                     preferred_codec_config, should_use_preferred_codec);
  }

  void TestAsesActive(LeAudioCodecId codec_id, uint8_t sampling_frequency, uint8_t frame_duration,
                      uint16_t octets_per_frame, uint8_t codec_frame_blocks_per_sdu = 1) {
    bool active_ase = false;

    for (const auto& device : devices_) {
      for (const auto& ase : device->ases_) {
        if (!ase.active) {
          continue;
        }

        /* Configure may request only partial ases to be activated */
        if (!active_ase && ase.active) {
          active_ase = true;
        }

        ASSERT_EQ(ase.codec_config.id, codec_id);

        /* FIXME: Validate other codec parameters than LC3 if any */
        ASSERT_EQ(ase.codec_config.id, LeAudioCodecIdLc3);
        if (ase.codec_config.id == LeAudioCodecIdLc3) {
          auto core_config = ase.codec_config.params.GetAsCoreCodecConfig();
          ASSERT_EQ(core_config.sampling_frequency, sampling_frequency);
          ASSERT_EQ(core_config.frame_duration, frame_duration);
          ASSERT_EQ(core_config.octets_per_codec_frame, octets_per_frame);
          ASSERT_EQ(core_config.codec_frames_blocks_per_sdu.value_or(0),
                    codec_frame_blocks_per_sdu);
        }
      }
    }

    ASSERT_TRUE(active_ase);
  }

  void TestActiveAses(void) {
    for (auto& device : devices_) {
      for (const auto& ase : device->ases_) {
        if (ase.active) {
          ASSERT_FALSE(ase.cis_id == ::bluetooth::le_audio::kInvalidCisId);
        }
      }
    }
  }

  void TestAsesInactivated(const LeAudioDevice* device) {
    for (const auto& ase : device->ases_) {
      ASSERT_FALSE(ase.active);
      ASSERT_TRUE(ase.cis_id == ::bluetooth::le_audio::kInvalidCisId);
      ASSERT_TRUE(ase.cis_conn_hdl == ::bluetooth::le_audio::kInvalidCisConnHandle);
    }
  }

  void TestAsesInactive() {
    for (const auto& device : devices_) {
      for (const auto& ase : device->ases_) {
        ASSERT_FALSE(ase.active);
      }
    }
  }

  void TestLc3CodecConfig(LeAudioContextType context_type, uint8_t max_codec_frames_per_sdu = 1) {
    for (int i = Lc3SettingIdBegin; i < Lc3SettingIdEnd; i++) {
      // test each configuration parameter against valid and invalid value
      std::array<Lc3SettingId, 2> test_variants = {static_cast<Lc3SettingId>(i),
                                                   Lc3SettingId::UNSUPPORTED};

      const bool is_lc3_setting_supported =
              IsLc3SettingSupported(context_type, static_cast<Lc3SettingId>(i));

      for (const auto sf_variant : test_variants) {
        uint8_t sampling_frequency = GetSamplingFrequency(sf_variant);
        for (const auto fd_variant : test_variants) {
          uint8_t frame_duration = GetFrameDuration(fd_variant);
          for (const auto opcf_variant : test_variants) {
            uint16_t octets_per_frame = GetOctetsPerCodecFrame(opcf_variant);

            PublishedAudioCapabilitiesBuilder pac_builder;
            pac_builder.Add(
                    LeAudioCodecIdLc3, sampling_frequency, frame_duration,
                    kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
                    octets_per_frame, max_codec_frames_per_sdu);
            for (auto& device : devices_) {
              /* For simplicity configure both PACs with the same
              parameters*/
              device->snk_pacs_ = pac_builder.Get();
              device->src_pacs_ = pac_builder.Get();
            }

            bool success_expected = is_lc3_setting_supported;
            if (is_lc3_setting_supported && (sf_variant == Lc3SettingId::UNSUPPORTED ||
                                             fd_variant == Lc3SettingId::UNSUPPORTED ||
                                             opcf_variant == Lc3SettingId::UNSUPPORTED)) {
              success_expected = false;
            }

            group_->UpdateAudioSetConfigurationCache(context_type);
            BidirectionalPair<AudioContexts> group_audio_locations = {
                    .sink = AudioContexts(context_type), .source = AudioContexts(context_type)};
            ASSERT_EQ(success_expected, group_->Configure(context_type, group_audio_locations));
            if (success_expected) {
              TestAsesActive(LeAudioCodecIdLc3, sampling_frequency, frame_duration,
                             octets_per_frame, max_codec_frames_per_sdu);
              group_->Deactivate();
            }

            TestAsesInactive();
          }
        }
      }
    }
  }

  void TestSingleDevDualBidir(LeAudioDevice* device, LeAudioContextType context_type) {
    // Build PACs for device
    PublishedAudioCapabilitiesBuilder snk_pac_builder, src_pac_builder;
    snk_pac_builder.Reset();
    src_pac_builder.Reset();

    const uint32_t supported_octets_per_codec_frame_80 = 80;
    const uint32_t supported_octets_per_codec_frame_40 = 40;
    const uint32_t supported_codec_frames_per_sdu = 1;
    CodecConfigSetting swb = {
            .id = LeAudioCodecIdLc3,
            .params = LeAudioLtvMap({
                    {codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                     UINT8_TO_VEC_UINT8(codec_spec_conf::kLeAudioSamplingFreq32000Hz)},
                    {codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                     UINT8_TO_VEC_UINT8(codec_spec_conf::kLeAudioCodecFrameDur10000us)},
                    {codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
                     UINT16_TO_VEC_UINT8(supported_octets_per_codec_frame_80)},
                    {codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                     UINT8_TO_VEC_UINT8(supported_codec_frames_per_sdu)},
            }),
            .channel_count_per_iso_stream = 1};

    auto swb_config = AudioSetConfiguration({
            .name = "Two-OneChan-SnkAse-Lc3_32_2-Two-OneChan-SrcAse-Lc3_32_2_SWB",
            .confs = {.sink = {AseConfiguration(swb), AseConfiguration(swb)},
                      .source = {AseConfiguration(swb), AseConfiguration(swb)}},
    });

    auto swb_config_single = AudioSetConfiguration({
            .name = "One-OneChan-SnkAse-Lc3_32_2-One-OneChan-SrcAse-Lc3_32_2_SWB",
            .confs = {.sink =
                              {
                                      AseConfiguration(swb),
                              },
                      .source =
                              {
                                      AseConfiguration(swb),
                              }},
    });

    ASSERT_FALSE(swb.params.IsEmpty());
    ASSERT_TRUE(swb.params.Find(codec_spec_conf::kLeAudioLtvTypeSamplingFreq).has_value());

    CodecConfigSetting non_swb = {
            .id = LeAudioCodecIdLc3,
            .params = LeAudioLtvMap({
                    {codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                     UINT8_TO_VEC_UINT8(codec_spec_conf::kLeAudioSamplingFreq16000Hz)},
                    {codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                     UINT8_TO_VEC_UINT8(codec_spec_conf::kLeAudioCodecFrameDur10000us)},
                    {codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
                     UINT16_TO_VEC_UINT8(supported_octets_per_codec_frame_40)},
                    {codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                     UINT8_TO_VEC_UINT8(supported_codec_frames_per_sdu)},
            }),
            .channel_count_per_iso_stream = 1};
    auto non_swb_config = AudioSetConfiguration({
            .name = "Two-OneChan-SnkAse-Lc3_16_2-Two-OneChan-SrcAse-Lc3_16_2_NON_SWB",
            .confs = {.sink = {AseConfiguration(non_swb), AseConfiguration(non_swb)},
                      .source = {AseConfiguration(non_swb), AseConfiguration(non_swb)}},
    });
    auto non_swb_config_single = AudioSetConfiguration({
            .name = "One-OneChan-SnkAse-Lc3_16_2-One-OneChan-SrcAse-Lc3_16_2_NON_SWB",
            .confs = {.sink = {AseConfiguration(non_swb)}, .source = {AseConfiguration(non_swb)}},
    });
    AudioSetConfigurations configs = {
            {&swb_config, &swb_config_single, &non_swb_config, &non_swb_config_single}};

    // Support single channel per ASE to activate two ASES on both direction
    for (auto config : configs) {
      for (const auto& entry : config->confs.sink) {
        snk_pac_builder.Add(entry.codec, kLeAudioCodecChannelCountSingleChannel);
      }
      for (const auto& entry : config->confs.source) {
        src_pac_builder.Add(entry.codec, kLeAudioCodecChannelCountSingleChannel);
      }
    }

    // Inject `configs` as there's no such config in the json file
    ON_CALL(*mock_codec_manager_, GetCodecConfig)
            .WillByDefault(Invoke([&configs](const bluetooth::le_audio::CodecManager::
                                                     UnicastConfigurationRequirements& requirements,
                                             bluetooth::le_audio::CodecManager::
                                                     UnicastConfigurationProvider provider) {
              auto filtered = configs;
              // Filter out the dual bidir SWB configurations
              if (!bluetooth::le_audio::CodecManager::GetInstance()->IsDualBiDirSwbSupported()) {
                filtered.erase(std::remove_if(filtered.begin(), filtered.end(),
                                              [](auto const& el) {
                                                if (el->confs.source.empty()) {
                                                  return false;
                                                }
                                                return AudioSetConfigurationProvider::Get()
                                                        ->CheckConfigurationIsDualBiDirSwb(*el);
                                              }),
                               filtered.end());
              }
              auto cfg = provider(requirements, &filtered);
              if (cfg == nullptr) {
                return std::unique_ptr<AudioSetConfiguration>(nullptr);
              }
              return std::make_unique<AudioSetConfiguration>(*cfg);
            }));

    // Make two ASES available in both directions with equal capabilities
    device->snk_pacs_ = snk_pac_builder.Get();
    device->src_pacs_ = src_pac_builder.Get();

    ASSERT_TRUE(group_->Configure(context_type, {.sink = AudioContexts(context_type),
                                                 .source = AudioContexts(context_type)}));

    // Verify Dual-Bidir - the amount of ASES configured
    TestGroupAseConfigurationData data[] = {{device, kLeAudioCodecChannelCountSingleChannel,
                                             kLeAudioCodecChannelCountSingleChannel, 2, 2}};
    TestGroupAseConfigurationVerdict(data[0], kLeAudioDirectionSink | kLeAudioDirectionSource);
  }

  /* Helper */
  static const AudioSetConfiguration* getSpecificConfiguration(const char* config_name,
                                                               LeAudioContextType context) {
    auto all_configurations =
            ::bluetooth::le_audio::AudioSetConfigurationProvider::Get()->GetConfigurations(context);

    if (all_configurations == nullptr) {
      return nullptr;
    }
    if (all_configurations->end() == all_configurations->begin()) {
      return nullptr;
    }

    auto iter = std::find_if(
            all_configurations->begin(), all_configurations->end(),
            [config_name](auto& configuration) { return configuration->name == config_name; });
    if (iter == all_configurations->end()) {
      return nullptr;
    }
    return *iter;
  }

  void TestDualDevDualBidir(LeAudioDevice* left, LeAudioDevice* right,
                            LeAudioContextType context_type) {
    // Build PACs for device
    PublishedAudioCapabilitiesBuilder snk_pac_builder, src_pac_builder;
    snk_pac_builder.Reset();
    src_pac_builder.Reset();

    /* Create PACs for conversational scenario, SWB and non SWB */
    for (auto config :
         {getSpecificConfiguration("Two-OneChan-SnkAse-Lc3_16_2-Two-OneChan-SrcAse-Lc3_16_2_1",
                                   context_type),
          getSpecificConfiguration("Two-OneChan-SnkAse-Lc3_32_2-Two-OneChan-SrcAse-Lc3_32_2_1",
                                   context_type)}) {
      ASSERT_NE(nullptr, config);
      for (const auto& entry : (*config).confs.sink) {
        snk_pac_builder.Add(entry.codec, kLeAudioCodecChannelCountSingleChannel);
      }
      for (const auto& entry : (*config).confs.source) {
        src_pac_builder.Add(entry.codec, kLeAudioCodecChannelCountSingleChannel);
      }
    }

    // Add pacs for remote to support the configs above
    for (auto& dev : {left, right}) {
      dev->snk_pacs_ = snk_pac_builder.Get();
      dev->src_pacs_ = src_pac_builder.Get();
    }

    /* Change location as by default it is stereo */
    left->audio_locations_.sink->value = codec_spec_conf::kLeAudioLocationFrontLeft;
    left->audio_locations_.source->value = codec_spec_conf::kLeAudioLocationFrontLeft;
    right->audio_locations_.sink->value = codec_spec_conf::kLeAudioLocationFrontRight;
    right->audio_locations_.source->value = codec_spec_conf::kLeAudioLocationFrontRight;
    group_->ReloadAudioLocations();

    ASSERT_TRUE(group_->Configure(context_type, {.sink = AudioContexts(context_type),
                                                 .source = AudioContexts(context_type)}));

    // Verify the amount of ASES configured
    TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                             kLeAudioCodecChannelCountSingleChannel, 1, 1},
                                            {right, kLeAudioCodecChannelCountSingleChannel,
                                             kLeAudioCodecChannelCountSingleChannel, 1, 1}};
    TestGroupAseConfigurationVerdict(data[0], kLeAudioDirectionSink | kLeAudioDirectionSource);
    TestGroupAseConfigurationVerdict(data[1], kLeAudioDirectionSink | kLeAudioDirectionSource);
  }

  void SetAsesToCachedConfiguration(LeAudioDevice* device, LeAudioContextType context_type,
                                    uint8_t directions) {
    for (struct ase& ase : device->ases_) {
      if (ase.direction & directions) {
        ase.state = AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;
        ase.active = false;
        ase.configured_for_context_type = context_type;
      }
    }
  }

  const int group_id_ = 6;
  int desired_group_size_ = -1;

  std::vector<std::shared_ptr<LeAudioDevice>> devices_;
  std::vector<RawAddress> addresses_;
  LeAudioDeviceGroup* group_ = nullptr;
  bluetooth::manager::MockBtmInterface btm_interface_;
  MockCsisClient mock_csis_client_module_;
  NiceMock<bluetooth::hci::testing::MockControllerInterface> controller_interface_;

  bluetooth::le_audio::CodecManager* codec_manager_;
  MockCodecManager* mock_codec_manager_;
};

TEST_P(LeAudioAseConfigurationTest, test_context_update) {
  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});

  ASSERT_EQ(2, group_->Size());

  /* Put the PACS */
  auto conversational_configuration = getSpecificConfiguration(
          "Two-OneChan-SnkAse-Lc3_16_2-One-OneChan-SrcAse-Lc3_16_2_Low_Latency",
          LeAudioContextType::CONVERSATIONAL);
  auto media_configuration = getSpecificConfiguration(
          "One-TwoChan-SnkAse-Lc3_48_4_High_Reliability", LeAudioContextType::MEDIA);
  ASSERT_NE(nullptr, conversational_configuration);
  ASSERT_NE(nullptr, media_configuration);

  /* Create PACs for conversational and media scenarios */
  PublishedAudioCapabilitiesBuilder snk_pac_builder, src_pac_builder;
  for (auto const& cfg : {conversational_configuration, media_configuration}) {
    for (const auto& entry : cfg->confs.sink) {
      snk_pac_builder.Add(entry.codec, 1);
    }
    for (const auto& entry : cfg->confs.source) {
      src_pac_builder.Add(entry.codec, 1);
    }
  }
  left->snk_pacs_ = snk_pac_builder.Get();
  left->src_pacs_ = src_pac_builder.Get();
  right->snk_pacs_ = snk_pac_builder.Get();
  right->src_pacs_ = src_pac_builder.Get();

  /* UNSPECIFIED must be supported, MEDIA is on the remote sink only... */
  auto remote_snk_supp_contexts =
          AudioContexts(LeAudioContextType::MEDIA | LeAudioContextType::CONVERSATIONAL |
                        LeAudioContextType::SOUNDEFFECTS | LeAudioContextType::UNSPECIFIED);
  auto remote_src_supp_contexts =
          AudioContexts(LeAudioContextType::CONVERSATIONAL | LeAudioContextType::UNSPECIFIED);

  left->SetSupportedContexts(
          {.sink = remote_snk_supp_contexts, .source = remote_src_supp_contexts});

  auto right_bud_only_context = LeAudioContextType::ALERTS;
  right->SetSupportedContexts({.sink = remote_snk_supp_contexts | right_bud_only_context,
                               .source = remote_src_supp_contexts | right_bud_only_context});

  /* ...but UNSPECIFIED and SOUNDEFFECTS are unavailable */
  auto remote_snk_avail_contexts =
          AudioContexts(LeAudioContextType::MEDIA | LeAudioContextType::CONVERSATIONAL);
  auto remote_src_avail_contexts = AudioContexts(LeAudioContextType::CONVERSATIONAL);

  left->SetAvailableContexts(
          {.sink = remote_snk_avail_contexts, .source = remote_src_avail_contexts});
  ASSERT_EQ(left->GetAvailableContexts(), remote_snk_avail_contexts | remote_src_avail_contexts);

  // Make an additional context available on the right earbud sink
  right->SetAvailableContexts({.sink = remote_snk_avail_contexts | right_bud_only_context,
                               .source = remote_src_avail_contexts});
  ASSERT_EQ(right->GetAvailableContexts(),
            remote_snk_avail_contexts | remote_src_avail_contexts | right_bud_only_context);

  /* Now add the right earbud contexts - mind the extra context on that bud */
  ASSERT_NE(group_->GetAvailableContexts(), left->GetAvailableContexts());
  ASSERT_EQ(group_->GetAvailableContexts(),
            left->GetAvailableContexts() | right->GetAvailableContexts());

  /* Since no device is being added or removed from the group this should not
   * change the configuration set.
   */
  ASSERT_EQ(group_->GetAvailableContexts(),
            left->GetAvailableContexts() | right->GetAvailableContexts());

  /* MEDIA Available on remote sink direction only */
  auto config = group_->GetConfiguration(LeAudioContextType::MEDIA);
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink).size());
  ASSERT_FALSE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSource).size());
  ASSERT_EQ(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink)
                    .at(0)
                    .codec.GetChannelCountPerIsoStream(),
            ::bluetooth::le_audio::LeAudioCodecConfiguration::kChannelNumberMono);

  /* CONVERSATIONAL Available on both directions */
  config = group_->GetConfiguration(LeAudioContextType::CONVERSATIONAL);
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink).size());
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSource).size());

  /* UNSPECIFIED Unavailable yet supported */
  config = group_->GetConfiguration(LeAudioContextType::UNSPECIFIED);
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink).size());
  ASSERT_FALSE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSource).size());

  /* SOUNDEFFECTS Unavailable yet supported on sink only */
  config = group_->GetConfiguration(LeAudioContextType::SOUNDEFFECTS);
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink).size());
  ASSERT_FALSE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSource).size());

  /* INSTRUCTIONAL Unavailable and not supported, while UNSPECIFIED not
   * available */
  config = group_->GetConfiguration(LeAudioContextType::INSTRUCTIONAL);
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink).size());
  ASSERT_FALSE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSource).size());

  /* ALERTS on sink only */
  config = group_->GetConfiguration(LeAudioContextType::ALERTS);
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink).size());
  ASSERT_FALSE(config->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSource).size());

  /* We should get the config for ALERTS for both channels as the other has
   * UNSPECIFIED context supported.
   */
  auto sink_configs = group_->GetConfiguration(LeAudioContextType::ALERTS)
                              ->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink);
  ASSERT_EQ(2lu, sink_configs.size());
  ASSERT_TRUE(group_->IsAudioSetConfigurationAvailable(LeAudioContextType::ALERTS));

  /* Turn off the ALERTS context */
  right->SetAvailableContexts(
          {.sink = right->GetAvailableContexts(
                           ::bluetooth::le_audio::types::kLeAudioDirectionSink) &
                   ~AudioContexts(LeAudioContextType::ALERTS),
           .source = right->GetAvailableContexts(
                   ::bluetooth::le_audio::types::kLeAudioDirectionSource)});

  /* Right one was changed but the config exist, just not available */
  ASSERT_EQ(group_->GetAvailableContexts(),
            left->GetAvailableContexts() | right->GetAvailableContexts());
  ASSERT_FALSE(group_->GetAvailableContexts().test(LeAudioContextType::ALERTS));
  ASSERT_TRUE(group_->GetConfiguration(LeAudioContextType::ALERTS)
                      ->confs.get(bluetooth::le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group_->IsAudioSetConfigurationAvailable(LeAudioContextType::ALERTS));
}

TEST_P(LeAudioAseConfigurationTest, test_mono_speaker_ringtone_loc0) {
  /* mono location */
  LeAudioDevice* mono_speaker = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationMonoAudio}});
  TestGroupAseConfigurationData data({mono_speaker, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 1, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_mono_speaker_ringtone) {
  /* left only location */
  LeAudioDevice* mono_speaker = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data({mono_speaker, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 1, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_mono_speaker_conversational_loc0) {
  /* mono location */
  LeAudioDevice* mono_speaker = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationMonoAudio}});
  TestGroupAseConfigurationData data({mono_speaker, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountNone, 1, 0});

  /* Microphone should be used on the phone */
  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_mono_speaker_conversational) {
  /* left only location */
  LeAudioDevice* mono_speaker = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data({mono_speaker, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountNone, 1, 0});

  /* Microphone should be used on the phone */
  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_mono_speaker_media_loc0) {
  /* mono location */
  LeAudioDevice* mono_speaker = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationMonoAudio}});
  TestGroupAseConfigurationData data({mono_speaker, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountNone, 1, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::MEDIA, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_mono_speaker_media) {
  /* left only location */
  LeAudioDevice* mono_speaker = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data({mono_speaker, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountNone, 1, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::MEDIA, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headphones_ringtone) {
  LeAudioDevice* banded_headphones = AddTestDevice(2, 0);
  TestGroupAseConfigurationData data({banded_headphones, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 2, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headphones_conversational) {
  LeAudioDevice* banded_headphones = AddTestDevice(2, 0);
  TestGroupAseConfigurationData data({banded_headphones, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountNone, 2, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headphones_media) {
  LeAudioDevice* banded_headphones = AddTestDevice(2, 0);
  TestGroupAseConfigurationData data({banded_headphones, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountNone, 2, 0});

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::MEDIA, &data, 1, direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_ringtone_mono_microphone) {
  /* mono source */
  LeAudioDevice* banded_headset = AddTestDevice({{2, codec_spec_conf::kLeAudioLocationStereo}},
                                                {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data({banded_headset, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_ringtone_mono_microphone_loc0) {
  /* mono source */
  LeAudioDevice* banded_headset = AddTestDevice({{2, codec_spec_conf::kLeAudioLocationStereo}},
                                                {{1, codec_spec_conf::kLeAudioLocationMonoAudio}});
  TestGroupAseConfigurationData data({banded_headset, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_ringtone_stereo_microphone) {
  LeAudioDevice* banded_headset = AddTestDevice(2, 2);
  TestGroupAseConfigurationData data(
          {banded_headset,
           kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
           kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel, 2, 2});

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_earbuds_conversational_stereo_microphone_no_swb) {
  // Turn off the dual bidir SWB support
  ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(false));
  ASSERT_FALSE(CodecManager::GetInstance()->IsDualBiDirSwbSupported());

  const auto context_type = LeAudioContextType::CONVERSATIONAL;
  TestDualDevDualBidir(AddTestDevice(1, 1), AddTestDevice(1, 1), context_type);

  // Verify non-SWB config was selected
  auto config = group_->GetCachedConfiguration(context_type).get();
  ASSERT_NE(nullptr, config);
  ASSERT_FALSE(CodecManager::GetInstance()->CheckCodecConfigIsDualBiDirSwb(*config));
}

TEST_P(LeAudioAseConfigurationTest,
       test_earbuds_conversational_stereo_microphone_no_swb_one_bonded) {
  /* There will be 2 eabuds eventually but for the moment only 1 is bonded
   * Turn off the dual bidir SWB support
   */
  desired_group_size_ = 2;
  ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(false));
  ASSERT_FALSE(CodecManager::GetInstance()->IsDualBiDirSwbSupported());

  const auto context_type = LeAudioContextType::CONVERSATIONAL;
  TestSingleDevDualBidir(
          AddTestDevice(1, 1, 0, 0, false, false, codec_spec_conf::kLeAudioLocationFrontLeft,
                        codec_spec_conf::kLeAudioLocationFrontLeft),
          context_type);

  // Verify non-SWB config was selected
  auto config = group_->GetCachedConfiguration(context_type).get();
  ASSERT_NE(nullptr, config);
  ASSERT_FALSE(CodecManager::GetInstance()->CheckCodecConfigIsDualBiDirSwb(*config));
  ASSERT_FALSE(CodecManager::GetInstance()->CheckCodecConfigIsBiDirSwb(*config));
}

TEST_P(LeAudioAseConfigurationTest, test_earbuds_conversational_stereo_microphone_swb) {
  // Turn on the dual bidir SWB support
  ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(true));
  ASSERT_TRUE(CodecManager::GetInstance()->IsDualBiDirSwbSupported());

  const auto context_type = LeAudioContextType::CONVERSATIONAL;
  TestDualDevDualBidir(AddTestDevice(1, 1), AddTestDevice(1, 1), context_type);

  // Verify SWB config was selected
  auto config = group_->GetCachedConfiguration(context_type).get();
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(CodecManager::GetInstance()->CheckCodecConfigIsDualBiDirSwb(*config));
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_ringtone_stereo_microphone_no_swb) {
  // Turn off the dual bidir SWB support
  ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(false));
  ASSERT_FALSE(CodecManager::GetInstance()->IsDualBiDirSwbSupported());

  // Verify non-SWB config was selected
  auto context_type = LeAudioContextType::CONVERSATIONAL;
  TestSingleDevDualBidir(AddTestDevice(2, 2), context_type);
  auto config = group_->GetCachedConfiguration(context_type).get();
  ASSERT_NE(nullptr, config);
  ASSERT_FALSE(CodecManager::GetInstance()->CheckCodecConfigIsDualBiDirSwb(*config));
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_ringtone_stereo_microphone_swb) {
  // Turn on the dual bidir SWB support
  ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(true));
  ASSERT_TRUE(CodecManager::GetInstance()->IsDualBiDirSwbSupported());

  // Verify SWB config was selected
  auto context_type = LeAudioContextType::CONVERSATIONAL;
  TestSingleDevDualBidir(AddTestDevice(2, 2), context_type);
  auto config = group_->GetCachedConfiguration(context_type).get();
  ASSERT_NE(nullptr, config);
  ASSERT_TRUE(CodecManager::GetInstance()->CheckCodecConfigIsDualBiDirSwb(*config));
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_conversational) {
  LeAudioDevice* banded_headset = AddTestDevice(2, 1);
  TestGroupAseConfigurationData data({banded_headset, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_banded_headset_media) {
  LeAudioDevice* banded_headset = AddTestDevice(2, 1);
  TestGroupAseConfigurationData data({banded_headset, kLeAudioCodecChannelCountTwoChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 2, 0});

  uint8_t directions_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::MEDIA, &data, 1, directions_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_earbuds_ringtone) {
  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1}};

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, data, 2);
}

TEST_P(LeAudioAseConfigurationTest, test_earbuds_conversational) {
  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1}};

  group_->ReloadAudioLocations();

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, data, 2);
}

TEST_P(LeAudioAseConfigurationTest, test_earbuds_media) {
  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0}};

  uint8_t directions_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::MEDIA, data, 2, directions_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_mono_ringtone) {
  LeAudioDevice* handsfree = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                           {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data({handsfree, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 1, 1});

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_stereo_ringtone) {
  LeAudioDevice* handsfree = AddTestDevice({{1, kChannelAllocationStereo}},
                                           {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data(
          {handsfree, kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
           kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::RINGTONE, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_mono_conversational) {
  LeAudioDevice* handsfree = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                           {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data({handsfree, kLeAudioCodecChannelCountSingleChannel,
                                      kLeAudioCodecChannelCountSingleChannel, 1, 1});

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_stereo_conversational) {
  LeAudioDevice* handsfree = AddTestDevice(1, 1);
  TestGroupAseConfigurationData data(
          {handsfree, kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
           kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_full_cached_conversational) {
  LeAudioDevice* handsfree = AddTestDevice(0, 0, 1, 1);
  TestGroupAseConfigurationData data(
          {handsfree, kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
           kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_partial_cached_conversational) {
  LeAudioDevice* handsfree = AddTestDevice(1, 0, 0, 1);
  TestGroupAseConfigurationData data(
          {handsfree, kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
           kLeAudioCodecChannelCountSingleChannel, 2, 1});

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, &data, 1);
}

TEST_P(LeAudioAseConfigurationTest, test_handsfree_media_two_channels_allocation_stereo) {
  LeAudioDevice* handsfree = AddTestDevice(1, 1);
  TestGroupAseConfigurationData data(
          {handsfree, kLeAudioCodecChannelCountSingleChannel | kLeAudioCodecChannelCountTwoChannel,
           kLeAudioCodecChannelCountSingleChannel, 2, 0});

  uint8_t directions_to_verify = kLeAudioDirectionSink;
  TestGroupAseConfiguration(LeAudioContextType::MEDIA, &data, 1, directions_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_lc3_config_ringtone) {
  if (codec_coding_format_ != kLeAudioCodingFormatLC3) {
    GTEST_SKIP();
  }

  AddTestDevice(1, 1);

  TestLc3CodecConfig(LeAudioContextType::RINGTONE);
}

TEST_P(LeAudioAseConfigurationTest, test_lc3_config_conversational) {
  if (codec_coding_format_ != kLeAudioCodingFormatLC3) {
    GTEST_SKIP();
  }

  AddTestDevice(1, 1);

  TestLc3CodecConfig(LeAudioContextType::CONVERSATIONAL);
}

TEST_P(LeAudioAseConfigurationTest, test_lc3_config_media) {
  if (codec_coding_format_ != kLeAudioCodingFormatLC3) {
    GTEST_SKIP();
  }

  AddTestDevice(1, 1);

  TestLc3CodecConfig(LeAudioContextType::MEDIA);
}

TEST_P(LeAudioAseConfigurationTest, test_use_codec_preference_earbuds_media) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0}};

  // this would be also built into pac record
  btle_audio_codec_config_t preferred_codec_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  uint8_t directions_to_verify = kLeAudioDirectionSink;
  bool should_use_preferred_codec = true;

  TestGroupAseConfiguration(LeAudioContextType::MEDIA, data, 2, directions_to_verify,
                            &preferred_codec_config, should_use_preferred_codec);
}

TEST_P(LeAudioAseConfigurationTest, test_not_use_codec_preference_earbuds_media) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0}};

  // this would be also built into pac record
  btle_audio_codec_config_t preferred_codec_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  uint8_t directions_to_verify = kLeAudioDirectionSink;
  bool should_use_preferred_codec = false;

  TestGroupAseConfiguration(LeAudioContextType::MEDIA, data, 2, directions_to_verify,
                            &preferred_codec_config, should_use_preferred_codec);
}

TEST_P(LeAudioAseConfigurationTest, test_use_codec_preference_earbuds_conv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1}};

  // this would be also built into pac record
  btle_audio_codec_config_t preferred_codec_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 80};

  uint8_t directions_to_verify = kLeAudioDirectionBoth;
  bool should_use_preferred_codec = true;

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, data, 2, directions_to_verify,
                            &preferred_codec_config, should_use_preferred_codec);
}

TEST_P(LeAudioAseConfigurationTest, test_not_use_codec_preference_earbuds_conv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  LeAudioDevice* left = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{1, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 1}};

  // this would be also built into pac record
  btle_audio_codec_config_t preferred_codec_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 10};

  uint8_t directions_to_verify = kLeAudioDirectionBoth;
  bool should_use_preferred_codec = false;

  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, data, 2, directions_to_verify,
                            &preferred_codec_config, should_use_preferred_codec);
}

TEST_P(LeAudioAseConfigurationTest, test_lc3_config_media_codec_extensibility_fb2) {
  if (codec_coding_format_ != kLeAudioCodingFormatLC3) {
    GTEST_SKIP();
  }

  bool is_fb2_passed_as_requirement = false;
  auto max_codec_frames_per_sdu = 2;

  // Mock the configuration provider to give us config with 2 frame blocks per
  // SDU if it receives the proper PAC entry in the requirements
  // ON_CALL(*mock_codec_manager_, IsUsingCodecExtensibility)
  //       .WillByDefault(Return(true));
  ON_CALL(*mock_codec_manager_, GetCodecConfig)
          .WillByDefault(Invoke(
                  [&](const bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements&
                              requirements,
                      bluetooth::le_audio::CodecManager::UnicastConfigurationProvider provider) {
                    auto filtered = *bluetooth::le_audio::AudioSetConfigurationProvider::Get()
                                             ->GetConfigurations(requirements.audio_context_type);
                    // Filter out the dual bidir SWB configurations
                    if (!bluetooth::le_audio::CodecManager::GetInstance()
                                 ->IsDualBiDirSwbSupported()) {
                      filtered.erase(
                              std::remove_if(filtered.begin(), filtered.end(),
                                             [](auto const& el) {
                                               if (el->confs.source.empty()) {
                                                 return false;
                                               }
                                               return AudioSetConfigurationProvider::Get()
                                                       ->CheckConfigurationIsDualBiDirSwb(*el);
                                             }),
                              filtered.end());
                    }
                    auto cfg = provider(requirements, &filtered);
                    if (cfg == nullptr) {
                      return std::unique_ptr<AudioSetConfiguration>(nullptr);
                    }

                    auto config = *cfg;

                    if (requirements.sink_pacs.has_value()) {
                      for (auto const& rec : requirements.sink_pacs.value()) {
                        auto caps = rec.codec_spec_caps.GetAsCoreCodecCapabilities();
                        if (caps.HasSupportedMaxCodecFramesPerSdu()) {
                          if (caps.supported_max_codec_frames_per_sdu.value() ==
                              max_codec_frames_per_sdu) {
                            // Inject the proper Codec Frames Per SDU as the json
                            // configs are conservative and will always give us 1
                            for (auto& entry : config.confs.sink) {
                              entry.codec.params.Add(
                                      codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                                      (uint8_t)max_codec_frames_per_sdu);
                            }
                            is_fb2_passed_as_requirement = true;
                          }
                        }
                      }
                    }
                    return std::make_unique<AudioSetConfiguration>(config);
                  }));

  AddTestDevice(1, 1);

  TestLc3CodecConfig(LeAudioContextType::MEDIA, max_codec_frames_per_sdu);

  // Make sure the CodecManager mock gets the proper PAC record
  ASSERT_TRUE(is_fb2_passed_as_requirement);
}

TEST_P(LeAudioAseConfigurationTest, test_unsupported_codec) {
  if (codec_coding_format_ == kLeAudioCodingFormatVendorSpecific) {
    GTEST_SKIP();
  }

  const LeAudioCodecId UnsupportedCodecId = {
          .coding_format = kLeAudioCodingFormatVendorSpecific,
          .vendor_company_id = 0xBAD,
          .vendor_codec_id = 0xC0DE,
  };

  LeAudioDevice* device = AddTestDevice(1, 0);

  PublishedAudioCapabilitiesBuilder pac_builder;
  pac_builder.Add(UnsupportedCodecId, GetSamplingFrequency(Lc3SettingId::LC3_16_2),
                  GetFrameDuration(Lc3SettingId::LC3_16_2), kLeAudioCodecChannelCountSingleChannel,
                  GetOctetsPerCodecFrame(Lc3SettingId::LC3_16_2));
  device->snk_pacs_ = pac_builder.Get();
  device->src_pacs_ = pac_builder.Get();

  ASSERT_FALSE(group_->Configure(LeAudioContextType::RINGTONE,
                                 {AudioContexts(LeAudioContextType::RINGTONE),
                                  AudioContexts(LeAudioContextType::RINGTONE)}));
  TestAsesInactive();
}

static auto PrepareStackMetadataLtvBase() {
  ::bluetooth::le_audio::types::LeAudioLtvMap metadata_ltvs;
  // Prepare the metadata LTVs
  metadata_ltvs
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeProgramInfo,
               std::string{"ProgramInfo"})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeLanguage, std::string{"ice"})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeparentalRating, (uint8_t)0x01)
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeProgramInfoUri,
               std::string{"ProgramInfoUri"})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState, false)
          .Add(::bluetooth::le_audio::types::
                       kLeAudioMetadataTypeBroadcastAudioImmediateRenderingFlag,
               true)
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeExtendedMetadata,
               std::vector<uint8_t>{1, 2, 3})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeVendorSpecific,
               std::vector<uint8_t>{1, 2, 3});
  return metadata_ltvs;
}

TEST_P(LeAudioAseConfigurationTest, test_reconnection_media) {
  LeAudioDevice* left = AddTestDevice({{2, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{2, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0}};

  auto all_configurations =
          ::bluetooth::le_audio::AudioSetConfigurationProvider::Get()->GetConfigurations(
                  LeAudioContextType::MEDIA);
  ASSERT_NE(nullptr, all_configurations);
  ASSERT_NE(all_configurations->end(), all_configurations->begin());
  auto configuration = *all_configurations->begin();

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  TestSingleAseConfiguration(LeAudioContextType::MEDIA, data, 2, configuration,
                             direction_to_verify);

  // Get the proper configuration for the group
  configuration = group_->GetConfiguration(LeAudioContextType::MEDIA).get();

  /* Generate CISes, symulate CIG creation and assign cis handles to ASEs.*/
  group_->cig.GenerateCisIds(LeAudioContextType::MEDIA);
  std::vector<uint16_t> handles = {0x0012, 0x0013};
  group_->cig.AssignCisConnHandles(handles);
  group_->cig.AssignCisIds(left);
  group_->cig.AssignCisIds(right);

  TestActiveAses();
  /* Left got disconnected */
  left->DeactivateAllAses();

  /* Unassign from the group*/
  group_->cig.UnassignCis(left, 0x0012);
  group_->cig.UnassignCis(left, 0x0013);

  TestAsesInactivated(left);

  /* Prepare reconfiguration */
  uint8_t number_of_active_ases = 1;  // Right one
  auto* ase = right->GetFirstActiveAseByDirection(kLeAudioDirectionSink);
  ASSERT_NE(nullptr, ase);

  auto core_config = ase->codec_config.params.GetAsCoreCodecConfig();
  BidirectionalPair<AudioLocations> group_audio_locations = {
          .sink = *core_config.audio_channel_allocation,
          .source = *core_config.audio_channel_allocation};

  /* Get entry for the sink direction and use it to set configuration */
  BidirectionalPair<std::vector<uint8_t>> ccid_lists = {{}, {}};
  BidirectionalPair<AudioContexts> audio_contexts = {AudioContexts(), AudioContexts()};
  if (!configuration->confs.sink.empty()) {
    left->ConfigureAses(configuration, group_->Size(), kLeAudioDirectionSink,
                        group_->GetConfigurationContextType(), &number_of_active_ases,
                        group_audio_locations.get(kLeAudioDirectionSink),
                        audio_contexts.get(kLeAudioDirectionSink),
                        ccid_lists.get(kLeAudioDirectionSink), false);
  }
  if (!configuration->confs.source.empty()) {
    left->ConfigureAses(configuration, group_->Size(), kLeAudioDirectionSource,
                        group_->GetConfigurationContextType(), &number_of_active_ases,
                        group_audio_locations.get(kLeAudioDirectionSource),
                        audio_contexts.get(kLeAudioDirectionSource),
                        ccid_lists.get(kLeAudioDirectionSource), false);
  }

  ASSERT_EQ(number_of_active_ases, 2);
  ASSERT_EQ(group_audio_locations.sink, kChannelAllocationStereo);

  uint8_t directions_to_verify = ::bluetooth::le_audio::types::kLeAudioDirectionSink;
  for (int i = 0; i < 2; i++) {
    TestGroupAseConfigurationVerdict(data[i], directions_to_verify);
  }

  /* Before device is rejoining, and group already exist, cis handles are
   * assigned before sending codec config
   */
  group_->cig.AssignCisIds(left);
  group_->AssignCisConnHandlesToAses(left);

  TestActiveAses();
}

TEST_P(LeAudioAseConfigurationTest, test_ase_metadata) {
  /* Set desired size to 1 */
  desired_group_size_ = 1;

  LeAudioDevice* headphones = AddTestDevice(2, 1);

  AudioSetConfiguration media_configuration = *getSpecificConfiguration(
          "One-TwoChan-SnkAse-Lc3_48_4_High_Reliability", LeAudioContextType::MEDIA);

  // Build PACs for device
  PublishedAudioCapabilitiesBuilder snk_pac_builder;
  snk_pac_builder.Reset();

  /* Create PACs for media. Single PAC for each direction is enough.
   */
  if (media_configuration.confs.sink.size()) {
    snk_pac_builder.Add(LeAudioCodecIdLc3, 0x00b5, 0x03, 0x03, 0x001a, 0x00f0, 2);
  }

  headphones->snk_pacs_ = snk_pac_builder.Get();

  ::bluetooth::le_audio::types::AudioLocations group_snk_audio_locations = 3;
  ::bluetooth::le_audio::types::AudioLocations group_src_audio_locations = 0;
  BidirectionalPair<AudioLocations> group_audio_locations = {.sink = group_snk_audio_locations,
                                                             .source = group_src_audio_locations};

  /* Get entry for the sink direction and use it to set configuration */
  BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {0xC0}, .source = {}};
  BidirectionalPair<AudioContexts> audio_contexts = {
          .sink = AudioContexts(LeAudioContextType::MEDIA), .source = AudioContexts()};

  /* Get entry for the sink direction and use it to set configuration */
  ASSERT_FALSE(media_configuration.confs.sink.empty());
  if (!media_configuration.confs.sink.empty()) {
    // Prepare a base metadata - as if it was received from the configuration provider
    for (auto& cfg : media_configuration.confs.sink) {
      cfg.metadata = PrepareStackMetadataLtvBase();
    }
    uint8_t number_of_already_active_ases = 0;
    headphones->ConfigureAses(&media_configuration, group_->Size(), kLeAudioDirectionSink,
                              group_->GetConfigurationContextType(), &number_of_already_active_ases,
                              group_audio_locations.get(kLeAudioDirectionSink),
                              audio_contexts.get(kLeAudioDirectionSink),
                              ccid_lists.get(kLeAudioDirectionSink), false);
  }

  /* Generate CIS, simulate CIG creation and assign cis handles to ASEs.*/
  std::vector<uint16_t> handles = {0x0012};
  group_->cig.GenerateCisIds(LeAudioContextType::MEDIA);

  /* Verify the metadata content */
  for (auto* ase = headphones->GetFirstActiveAse(); ase; ase = headphones->GetNextActiveAse(ase)) {
    /* The base metadata as if received from the audio set configuration provider */
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().program_info, std::string("ProgramInfo"));
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().language, std::string("ice"));
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().parental_rating, 0x01);
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().program_info_uri, std::string("ProgramInfoUri"));
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().audio_active_state, false);
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().broadcast_audio_immediate_rendering, true);
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().extended_metadata,
              (std::vector<uint8_t>{1, 2, 3}));
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().vendor_specific,
              (std::vector<uint8_t>{1, 2, 3}));

    /* The adidtional metadata appended by the host stack */
    uint16_t streaming_context =
            ase->metadata.GetAsLeAudioMetadata().streaming_audio_context.value().value();
    ASSERT_EQ(streaming_context, (uint16_t)LeAudioContextType::MEDIA);
    ASSERT_EQ(ase->metadata.GetAsLeAudioMetadata().ccid_list, (std::vector<uint8_t>{0xC0}));
  }
}

/*
 * Failure happens when restarting conversational scenario and when
 * remote device uses caching.
 *
 * Failing scenario.
 * 1. Conversational scenario set up with
 *  - ASE 1 and ASE 5 using bidirectional CIS 0
 *  - ASE 2  being unidirectional on CIS 1
 * 2. Stop stream and go to CONFIGURED STATE.
 * 3. Trying to configure ASES again would end up in incorrectly assigned
 *    CISes
 *  - ASE 1 and ASE 5 set to CIS 0
 *  - ASE 2 stay on CIS 1 but ASE 5 got reassigned to CIS 1 (error)
 *
 * The problem is finding matching_bidir_ase which shall not be just next
 * active ase with different direction, but it shall be also available (Cis
 * not assigned) or assigned to the same CIS ID as the opposite direction.
 */
TEST_P(LeAudioAseConfigurationTest, test_reactivation_conversational) {
  LeAudioDevice* tws_headset = AddTestDevice(0, 0, 2, 1, true, false, kChannelAllocationStereo,
                                             codec_spec_conf::kLeAudioLocationFrontLeft);

  auto conversational_configuration = getSpecificConfiguration(
          "Two-OneChan-SnkAse-Lc3_16_2-One-OneChan-SrcAse-Lc3_16_2_Low_Latency",
          LeAudioContextType::CONVERSATIONAL);
  ASSERT_NE(nullptr, conversational_configuration);

  // Build PACs for device
  PublishedAudioCapabilitiesBuilder snk_pac_builder, src_pac_builder;
  snk_pac_builder.Reset();
  src_pac_builder.Reset();

  /* Create PACs for conversational scenario which covers also media. Single
   * PAC for each direction is enough.
   */
  for (const auto& entry : (*conversational_configuration).confs.sink) {
    snk_pac_builder.Add(entry.codec, 1);
  }
  for (const auto& entry : (*conversational_configuration).confs.source) {
    src_pac_builder.Add(entry.codec, 1);
  }

  tws_headset->snk_pacs_ = snk_pac_builder.Get();
  tws_headset->src_pacs_ = src_pac_builder.Get();

  ::bluetooth::le_audio::types::AudioLocations group_snk_audio_locations = 0;
  ::bluetooth::le_audio::types::AudioLocations group_src_audio_locations = 0;
  BidirectionalPair<uint8_t> number_of_already_active_ases = {0, 0};

  BidirectionalPair<AudioLocations> group_audio_locations = {.sink = group_snk_audio_locations,
                                                             .source = group_src_audio_locations};

  /* Get entry for the sink direction and use it to set configuration */
  BidirectionalPair<std::vector<uint8_t>> ccid_lists = {{}, {}};
  BidirectionalPair<AudioContexts> audio_contexts = {AudioContexts(), AudioContexts()};

  /* Get entry for the sink direction and use it to set configuration */
  if (!conversational_configuration->confs.sink.empty()) {
    tws_headset->ConfigureAses(conversational_configuration, group_->Size(), kLeAudioDirectionSink,
                               group_->GetConfigurationContextType(),
                               &number_of_already_active_ases.get(kLeAudioDirectionSink),
                               group_audio_locations.get(kLeAudioDirectionSink),
                               audio_contexts.get(kLeAudioDirectionSink),
                               ccid_lists.get(kLeAudioDirectionSink), false);
  }
  if (!conversational_configuration->confs.source.empty()) {
    tws_headset->ConfigureAses(conversational_configuration, group_->Size(),
                               kLeAudioDirectionSource, group_->GetConfigurationContextType(),
                               &number_of_already_active_ases.get(kLeAudioDirectionSource),
                               group_audio_locations.get(kLeAudioDirectionSource),
                               audio_contexts.get(kLeAudioDirectionSource),
                               ccid_lists.get(kLeAudioDirectionSource), false);
  }

  /* Generate CISes, simulate CIG creation and assign cis handles to ASEs.*/
  std::vector<uint16_t> handles = {0x0012, 0x0013};
  group_->cig.GenerateCisIds(LeAudioContextType::CONVERSATIONAL);
  group_->cig.AssignCisConnHandles(handles);
  group_->cig.AssignCisIds(tws_headset);

  TestActiveAses();

  /* Simulate stopping stream with caching codec configuration in ASEs */
  group_->cig.UnassignCis(tws_headset, 0x0012);
  group_->cig.UnassignCis(tws_headset, 0x0013);
  SetAsesToCachedConfiguration(tws_headset, LeAudioContextType::CONVERSATIONAL,
                               kLeAudioDirectionSink | kLeAudioDirectionSource);

  /* As context type is the same as previous and no changes were made in PACs
   * the same CIS ID can be used. This would lead to only activating group
   * without reconfiguring CIG.
   */
  group_->Activate(LeAudioContextType::CONVERSATIONAL, audio_contexts, ccid_lists);

  TestActiveAses();
  ASSERT_NE(this->group_->cig.cises.size(), 0lu);

  /* Verify ASEs assigned CISes by counting assigned to bi-directional CISes */
  int bi_dir_ases_count =
          std::count_if(tws_headset->ases_.begin(), tws_headset->ases_.end(), [this](auto& ase) {
            if (ase.cis_id == kInvalidCisId) {
              return false;
            }
            return this->group_->cig.cises[ase.cis_id].type == CisType::CIS_TYPE_BIDIRECTIONAL;
          });

  /* Only two ASEs can be bonded to one bi-directional CIS */
  ASSERT_EQ(bi_dir_ases_count, 2);
}

TEST_P(LeAudioAseConfigurationTest, test_num_of_connected) {
  auto device1 = AddTestDevice(2, 1);
  auto device2 = AddTestDevice(2, 1);
  ASSERT_EQ(2, group_->NumOfConnected());

  // Drop the ACL connection
  device1->conn_id_ = GATT_INVALID_CONN_ID;
  ASSERT_EQ(1, group_->NumOfConnected());

  // Fully disconnect the other device
  device2->SetConnectionState(DeviceConnectState::DISCONNECTING);
  ASSERT_EQ(0, group_->NumOfConnected());
}

/*
 * Failure happens when there is no matching single device scenario for dual
 * device scanario. Stereo location for single earbud seems to be invalid but
 * possible and stack should handle it.
 *
 * Failing scenario:
 * 1. Connect two - stereo location earbuds
 * 2. Disconnect one of earbud
 * 3. CIS generator will look for dual device scenario with matching strategy
 * 4. There is no dual device scenario with strategy stereo channels per device
 */
TEST_P(LeAudioAseConfigurationTest, test_getting_cis_count) {
  /* Set desired size to 2 */
  desired_group_size_ = 2;

  LeAudioDevice* left =
          AddTestDevice({{2, kChannelAllocationStereo}}, {{1, kChannelAllocationStereo}});
  LeAudioDevice* right = AddTestDevice(0, 0, 0, 0, false, true);

  auto media_configuration = getSpecificConfiguration(
          "One-TwoChan-SnkAse-Lc3_48_4_High_Reliability", LeAudioContextType::MEDIA);
  ASSERT_NE(nullptr, media_configuration);

  // Build PACs for device
  PublishedAudioCapabilitiesBuilder snk_pac_builder;
  snk_pac_builder.Reset();

  /* Create PACs for media. Single PAC for each direction is enough.
   */
  if (media_configuration->confs.sink.size()) {
    snk_pac_builder.Add(LeAudioCodecIdLc3, 0x00b5, 0x03, 0x03, 0x001a, 0x00f0, 2);
  }

  left->snk_pacs_ = snk_pac_builder.Get();
  right->snk_pacs_ = snk_pac_builder.Get();

  ::bluetooth::le_audio::types::AudioLocations group_snk_audio_locations = 3;
  ::bluetooth::le_audio::types::AudioLocations group_src_audio_locations = 0;
  uint8_t number_of_already_active_ases = 0;

  BidirectionalPair<AudioLocations> group_audio_locations = {.sink = group_snk_audio_locations,
                                                             .source = group_src_audio_locations};

  /* Get entry for the sink direction and use it to set configuration */
  BidirectionalPair<std::vector<uint8_t>> ccid_lists = {{}, {}};
  BidirectionalPair<AudioContexts> audio_contexts = {AudioContexts(), AudioContexts()};

  /* Get entry for the sink direction and use it to set configuration */
  if (!media_configuration->confs.sink.empty()) {
    left->ConfigureAses(media_configuration, group_->Size(), kLeAudioDirectionSink,
                        group_->GetConfigurationContextType(), &number_of_already_active_ases,
                        group_audio_locations.get(kLeAudioDirectionSink),
                        audio_contexts.get(kLeAudioDirectionSink),
                        ccid_lists.get(kLeAudioDirectionSink), false);
  }

  /* Generate CIS, simulate CIG creation and assign cis handles to ASEs.*/
  std::vector<uint16_t> handles = {0x0012};
  group_->cig.GenerateCisIds(LeAudioContextType::MEDIA);

  /* Verify prepared CISes by counting generated entries */
  int snk_cis_count = std::count_if(
          this->group_->cig.cises.begin(), this->group_->cig.cises.end(),
          [](auto& cis) { return cis.type == CisType::CIS_TYPE_UNIDIRECTIONAL_SINK; });

  /* Two CIS should be prepared for dual dev expected set */
  ASSERT_EQ(snk_cis_count, 2);
}

TEST_P(LeAudioAseConfigurationTest, test_config_support) {
  LeAudioDevice* left =
          AddTestDevice({{2, kChannelAllocationStereo}}, {{1, kChannelAllocationStereo}});
  LeAudioDevice* right = AddTestDevice(0, 0, 0, 0, false, true);

  auto test_config = getSpecificConfiguration(
          "One-OneChan-SnkAse-Lc3_48_4-One-OneChan-SrcAse-Lc3_16_2_Balanced_Reliability",
          LeAudioContextType::VOICEASSISTANTS);
  ASSERT_NE(nullptr, test_config);

  /* Create PACs for sink */
  PublishedAudioCapabilitiesBuilder snk_pac_builder;
  snk_pac_builder.Reset();
  for (const auto& entry : (*test_config).confs.sink) {
    snk_pac_builder.Add(entry.codec, 1);
  }
  left->snk_pacs_ = snk_pac_builder.Get();
  right->snk_pacs_ = snk_pac_builder.Get();

  ASSERT_FALSE(left->IsAudioSetConfigurationSupported(test_config));
  ASSERT_FALSE(right->IsAudioSetConfigurationSupported(test_config));

  /* Create PACs for source */
  PublishedAudioCapabilitiesBuilder src_pac_builder;
  src_pac_builder.Reset();
  for (const auto& entry : (*test_config).confs.source) {
    src_pac_builder.Add(entry.codec, 1);
  }
  left->src_pacs_ = src_pac_builder.Get();
  right->src_pacs_ = src_pac_builder.Get();

  ASSERT_TRUE(left->IsAudioSetConfigurationSupported(test_config));
  ASSERT_TRUE(right->IsAudioSetConfigurationSupported(test_config));
}

TEST_P(LeAudioAseConfigurationTest, test_vendor_codec_configure_incomplete_group) {
  // A group of two earbuds
  LeAudioDevice* left = AddTestDevice({{2, codec_spec_conf::kLeAudioLocationFrontLeft}},
                                      {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  LeAudioDevice* right = AddTestDevice({{2, codec_spec_conf::kLeAudioLocationFrontRight}},
                                       {{1, codec_spec_conf::kLeAudioLocationFrontRight}});

  // The Right earbud is currently disconnected
  right->SetConnectionState(DeviceConnectState::DISCONNECTED);

  uint8_t direction_to_verify = kLeAudioDirectionSink;
  uint8_t devices_to_verify = 1;
  TestGroupAseConfigurationData data[] = {{left, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 1, 0},
                                          {right, kLeAudioCodecChannelCountSingleChannel,
                                           kLeAudioCodecChannelCountSingleChannel, 0, 0}};

  TestGroupAseConfiguration(LeAudioContextType::MEDIA, data, devices_to_verify,
                            direction_to_verify);
}

TEST_P(LeAudioAseConfigurationTest, test_mono_microphone_conversational_loc0) {
  /* Mono microphone - Speaker should be used on the phone */
  auto mono_microphone =
          AddTestDevice(std::nullopt, {{1, codec_spec_conf::kLeAudioLocationMonoAudio}});
  TestGroupAseConfigurationData data(
          {.device = mono_microphone,
           .audio_channel_counts_snk = kLeAudioCodecChannelCountNone,
           .audio_channel_counts_src = kLeAudioCodecChannelCountSingleChannel,
           .expected_active_channel_num_snk = 0,
           .expected_active_channel_num_src = 1});
  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, {data});
}

TEST_P(LeAudioAseConfigurationTest, test_mono_microphone_conversational) {
  /* Mono microphone - Speaker should be used on the phone */
  auto mono_microphone =
          AddTestDevice(std::nullopt, {{1, codec_spec_conf::kLeAudioLocationFrontLeft}});
  TestGroupAseConfigurationData data(
          {.device = mono_microphone,
           .audio_channel_counts_snk = kLeAudioCodecChannelCountNone,
           .audio_channel_counts_src = kLeAudioCodecChannelCountSingleChannel,
           .expected_active_channel_num_snk = 0,
           .expected_active_channel_num_src = 1});
  TestGroupAseConfiguration(LeAudioContextType::CONVERSATIONAL, {data});
}

TEST_P(LeAudioAseConfigurationTest, test_get_metadata_no_ccid) {
  auto mono_microphone = AddTestDevice(1, 0);
  auto metadata = mono_microphone->GetMetadata(
          bluetooth::le_audio::types::AudioContexts(
                  bluetooth::le_audio::types::LeAudioContextType::MEDIA),
          std::vector<uint8_t>());
  ASSERT_EQ(metadata.Find(types::kLeAudioMetadataTypeCcidList), std::nullopt);
  ASSERT_TRUE(metadata.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext)
                      .has_value());
  ASSERT_EQ(metadata.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext)
                    .value()[0],
            uint8_t(LeAudioContextType::MEDIA));
  ASSERT_EQ(metadata.Find(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext)
                    .value()[1],
            uint8_t((uint16_t)LeAudioContextType::MEDIA >> 8));
}

INSTANTIATE_TEST_CASE_P(Test, LeAudioAseConfigurationTest,
                        ::testing::Values(kLeAudioCodingFormatLC3,
                                          kLeAudioCodingFormatVendorSpecific));

}  // namespace
}  // namespace internal
}  // namespace le_audio
}  // namespace bluetooth
