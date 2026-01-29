/*
 * Copyright 2025 The Android Open Source Project
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

#define LOG_TAG "TestA2dpEncodingAidlUtils"

#include "aidl/a2dp/a2dp_encoding_aidl_utils.h"

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "stack/include/a2dp_constants.h"
#include "stack/include/a2dp_vendor_aptx_constants.h"
#include "stack/include/a2dp_vendor_aptx_hd_constants.h"
#include "stack/include/a2dp_vendor_ldac_constants.h"
#include "stack/include/a2dp_vendor_opus_constants.h"

using ::aidl::android::hardware::bluetooth::audio::ChannelMode;
using ::aidl::android::hardware::bluetooth::audio::CodecId;
using ::aidl::android::hardware::bluetooth::audio::CodecInfo;

using bluetooth::audio::a2dp::Status;
using ::testing::_;
using ::testing::Eq;
using ::testing::Return;
using ::testing::Test;

using namespace bluetooth::audio::aidl::a2dp;

static std::string codecInfoToString(const uint8_t* codec_info) {
  std::string result_string;
  auto out = std::back_inserter(result_string);
  for (int i = 0; i < AVDT_CODEC_SIZE; i++) {
    std::format_to(out, "0x{}, ", codec_info[i]);
  }
  std::format_to(out, "\n");
  return result_string;
}

class A2dpEncodingAidlUtilsTest : public Test {
public:
  void SetUp() override {}

  void TearDown() override {}
};

//=============================================================================
// A2dpEncodingAidlUtilsTest
//=============================================================================

TEST_F(A2dpEncodingAidlUtilsTest, StackChannelMode) {
  ASSERT_EQ(convertChannelMode(ChannelMode::MONO), BTAV_A2DP_CODEC_CHANNEL_MODE_MONO);
  ASSERT_EQ(convertChannelMode(ChannelMode::STEREO), BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO);
  ASSERT_EQ(convertChannelMode(ChannelMode::DUALMONO), BTAV_A2DP_CODEC_CHANNEL_MODE_NONE);
  ASSERT_EQ(convertChannelMode(ChannelMode::UNKNOWN), BTAV_A2DP_CODEC_CHANNEL_MODE_NONE);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackChannelModeVector) {
  std::vector<ChannelMode> aidl_channel_modes = {ChannelMode::MONO, ChannelMode::STEREO,
                                                 ChannelMode::DUALMONO};
  btav_a2dp_codec_channel_mode_t stack_channel_modes = convertChannelMode(aidl_channel_modes);
  ASSERT_EQ(stack_channel_modes,
            BTAV_A2DP_CODEC_CHANNEL_MODE_MONO | BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackSampleRateSample) {
  ASSERT_EQ(convertSampleRate(16000), BTAV_A2DP_CODEC_SAMPLE_RATE_16000);
  ASSERT_EQ(convertSampleRate(24000), BTAV_A2DP_CODEC_SAMPLE_RATE_24000);
  ASSERT_EQ(convertSampleRate(44100), BTAV_A2DP_CODEC_SAMPLE_RATE_44100);
  ASSERT_EQ(convertSampleRate(48000), BTAV_A2DP_CODEC_SAMPLE_RATE_48000);
  ASSERT_EQ(convertSampleRate(88200), BTAV_A2DP_CODEC_SAMPLE_RATE_88200);
  ASSERT_EQ(convertSampleRate(96000), BTAV_A2DP_CODEC_SAMPLE_RATE_96000);
  ASSERT_EQ(convertSampleRate(176400), BTAV_A2DP_CODEC_SAMPLE_RATE_176400);
  ASSERT_EQ(convertSampleRate(192000), BTAV_A2DP_CODEC_SAMPLE_RATE_192000);
  ASSERT_EQ(convertSampleRate(123456), BTAV_A2DP_CODEC_SAMPLE_RATE_NONE);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackSampleRateVector) {
  std::vector<int32_t> aidl_sample_rate = {16000, 24000,  44100,  48000, 88200,
                                           96000, 176400, 192000, 123456};
  btav_a2dp_codec_sample_rate_t stack_sample_rate = convertSampleRate(aidl_sample_rate);
  ASSERT_EQ(stack_sample_rate,
            BTAV_A2DP_CODEC_SAMPLE_RATE_16000 | BTAV_A2DP_CODEC_SAMPLE_RATE_24000 |
                    BTAV_A2DP_CODEC_SAMPLE_RATE_44100 | BTAV_A2DP_CODEC_SAMPLE_RATE_48000 |
                    BTAV_A2DP_CODEC_SAMPLE_RATE_88200 | BTAV_A2DP_CODEC_SAMPLE_RATE_96000 |
                    BTAV_A2DP_CODEC_SAMPLE_RATE_176400 | BTAV_A2DP_CODEC_SAMPLE_RATE_192000);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackBitsPerSample) {
  ASSERT_EQ(convertBitsPerSample(16), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16);
  ASSERT_EQ(convertBitsPerSample(24), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24);
  ASSERT_EQ(convertBitsPerSample(32), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32);
  ASSERT_EQ(convertBitsPerSample(123), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackBitsPerSampleVector) {
  std::vector<int32_t> aidl_bits_per_sample = {16, 24, 32, 123};
  btav_a2dp_codec_bits_per_sample_t stack_bits_per_sample =
          convertBitsPerSample(aidl_bits_per_sample);
  ASSERT_EQ(stack_bits_per_sample,
            BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16 | BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24 |
                    BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32 | BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecCapabilitiesSbc) {
  uint8_t codec_info[AVDT_CODEC_SIZE] = {0};
  uint8_t result_sbc_codec_info[AVDT_CODEC_SIZE] = {
          0x06,  // Length of service category: 6
          0x00,  // Media Type: Audio
          0x00,  // Media codec audio type: SBC
          0x21,  // Sampling Frequency: 44100Hz, Chanel mode: Stereo
          0x15,  // Block length: 16, Subbands: 8, Allocation method: Loudness
          0x02,  // Minimum bitpool
          0x35,  // Maximum bitpool
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
  std::vector<uint8_t> capabilities = {
          0x21,  // Sampling Frequency: 44100Hz, Chanel mode: Stereo
          0x15,  // Block length: 16, Subbands: 8, Allocation method: Loudness
          0x02,  // Minimum bitpool
          0x35,  // Maximum bitpool
  };
  CodecId sbc =
          CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(::bluetooth::a2dp::CodecId::SBC));

  ASSERT_TRUE(convertCodecCapabilities(sbc, capabilities, codec_info));

  bluetooth::log::info("codec_info:\n{}", codecInfoToString(codec_info));
  bluetooth::log::info("result_sbc_codec_info:\n{}", codecInfoToString(result_sbc_codec_info));

  ASSERT_EQ(memcmp(codec_info, result_sbc_codec_info, sizeof(result_sbc_codec_info)), 0);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecCapabilitiesVendor) {
  uint8_t codec_info[AVDT_CODEC_SIZE] = {0};
  uint8_t result_ldac_codec_info[AVDT_CODEC_SIZE] = {
          0x0a,                    // Length of service category: 10
          0x00,                    // Media Type: Audio
          0xff,                    // Media codec audio type: non-A2DP
          0x2d, 0x01, 0x00, 0x00,  // Vendor ID: 0x0000012D
          0xaa, 0x00,              // Codec ID: 0x00AA
          0x04, 0x01,              // Vendor Specific Codec Capabilities
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
  std::vector<uint8_t> capabilities = {
          0x04,  // Sampling Frequency: 96kHz
          0x01   // Channel Mode: Stereo
  };
  CodecId ldac = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)A2DP_LDAC_VENDOR_ID, .codecId = A2DP_LDAC_CODEC_ID}));

  ASSERT_TRUE(convertCodecCapabilities(ldac, capabilities, codec_info));

  bluetooth::log::info("codec_info:\n{}", codecInfoToString(codec_info));
  bluetooth::log::info("result_ldac_codec_info:\n{}", codecInfoToString(result_ldac_codec_info));

  ASSERT_EQ(memcmp(codec_info, result_ldac_codec_info, sizeof(result_ldac_codec_info)), 0);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecCapabilitiesCore) {
  uint8_t codec_info[AVDT_CODEC_SIZE] = {0};
  std::vector<uint8_t> capabilities = {};
  CodecId core = CodecId::make<CodecId::core>(static_cast<CodecId::Core>(0));

  ASSERT_FALSE(convertCodecCapabilities(core, capabilities, codec_info));
}

TEST_F(A2dpEncodingAidlUtilsTest, AidlCodecId) {
  // Stack known codecs
  CodecId sbc =
          CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(::bluetooth::a2dp::CodecId::SBC));
  CodecId aac =
          CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(::bluetooth::a2dp::CodecId::AAC));
  CodecId aptx = CodecId::make<CodecId::vendor>(CodecId::Vendor(
          {.id = (int32_t)A2DP_APTX_VENDOR_ID, .codecId = A2DP_APTX_CODEC_ID_BLUETOOTH}));
  CodecId aptx_hd = CodecId::make<CodecId::vendor>(CodecId::Vendor(
          {.id = (int32_t)A2DP_APTX_HD_VENDOR_ID, .codecId = A2DP_APTX_HD_CODEC_ID_BLUETOOTH}));
  CodecId ldac = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)A2DP_LDAC_VENDOR_ID, .codecId = A2DP_LDAC_CODEC_ID}));
  CodecId opus = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)A2DP_OPUS_VENDOR_ID, .codecId = A2DP_OPUS_CODEC_ID}));

  // Vendor unknown codec
  uint32_t foobar_vendor_id = 0x1234;
  uint16_t foobar_codec_id = 0x4321;
  ::bluetooth::a2dp::CodecId foobar_codec = static_cast<::bluetooth::a2dp::CodecId>(
          ::bluetooth::a2dp::VendorCodecId(foobar_vendor_id, foobar_codec_id));
  CodecId foobar = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)foobar_vendor_id, .codecId = foobar_codec_id}));

  std::vector<CodecId> aidl_codecs = {sbc, aac, aptx, aptx_hd, ldac, opus, foobar};
  std::vector<::bluetooth::a2dp::CodecId> stack_codecs = {::bluetooth::a2dp::CodecId::SBC,
                                                          ::bluetooth::a2dp::CodecId::AAC,
                                                          ::bluetooth::a2dp::CodecId::APTX,
                                                          ::bluetooth::a2dp::CodecId::APTX_HD,
                                                          ::bluetooth::a2dp::CodecId::LDAC,
                                                          ::bluetooth::a2dp::CodecId::OPUS,
                                                          foobar_codec};

  for (size_t i = 0; i < stack_codecs.size(); i++) {
    bluetooth::log::info("stack codec: {}", ::bluetooth::a2dp::CodecIdToString(stack_codecs[i]));
    auto result = convertCodecId(stack_codecs[i]);
    ASSERT_TRUE(result.has_value());
    bluetooth::log::info("result codec: {}, expected codec: {}", result.value().toString(),
                         aidl_codecs[i].toString());
    ASSERT_EQ(result.value(), aidl_codecs[i]);
  }

  auto result = convertCodecId(static_cast<::bluetooth::a2dp::CodecId>(0x1234));
  ASSERT_EQ(result, std::nullopt);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecId) {
  // Stack known codecs
  CodecId sbc =
          CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(::bluetooth::a2dp::CodecId::SBC));
  CodecId aac =
          CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(::bluetooth::a2dp::CodecId::AAC));
  CodecId aptx = CodecId::make<CodecId::vendor>(CodecId::Vendor(
          {.id = (int32_t)A2DP_APTX_VENDOR_ID, .codecId = A2DP_APTX_CODEC_ID_BLUETOOTH}));
  CodecId aptx_hd = CodecId::make<CodecId::vendor>(CodecId::Vendor(
          {.id = (int32_t)A2DP_APTX_HD_VENDOR_ID, .codecId = A2DP_APTX_HD_CODEC_ID_BLUETOOTH}));
  CodecId ldac = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)A2DP_LDAC_VENDOR_ID, .codecId = A2DP_LDAC_CODEC_ID}));
  CodecId opus = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)A2DP_OPUS_VENDOR_ID, .codecId = A2DP_OPUS_CODEC_ID}));

  // Vendor unknown codec
  uint32_t foobar_vendor_id = 0x1234;
  uint16_t foobar_codec_id = 0x4321;
  ::bluetooth::a2dp::CodecId foobar_codec = static_cast<::bluetooth::a2dp::CodecId>(
          ::bluetooth::a2dp::VendorCodecId(foobar_vendor_id, foobar_codec_id));
  CodecId foobar = CodecId::make<CodecId::vendor>(
          CodecId::Vendor({.id = (int32_t)foobar_vendor_id, .codecId = foobar_codec_id}));

  // Unsupported codec
  CodecId core = CodecId::make<CodecId::core>(static_cast<CodecId::Core>(0));

  std::vector<CodecId> aidl_codecs = {sbc, aac, aptx, aptx_hd, ldac, opus, foobar};
  std::vector<::bluetooth::a2dp::CodecId> stack_codecs = {::bluetooth::a2dp::CodecId::SBC,
                                                          ::bluetooth::a2dp::CodecId::AAC,
                                                          ::bluetooth::a2dp::CodecId::APTX,
                                                          ::bluetooth::a2dp::CodecId::APTX_HD,
                                                          ::bluetooth::a2dp::CodecId::LDAC,
                                                          ::bluetooth::a2dp::CodecId::OPUS,
                                                          foobar_codec};

  for (size_t i = 0; i < aidl_codecs.size(); i++) {
    bluetooth::log::info("aidl codec: {}", aidl_codecs[i].toString());
    auto result = convertCodecId(aidl_codecs[i]);
    ASSERT_TRUE(result.has_value());
    bluetooth::log::info("result codec: {}, expected codec: {}",
                         ::bluetooth::a2dp::CodecIdToString(result.value()),
                         ::bluetooth::a2dp::CodecIdToString(stack_codecs[i]));
    ASSERT_EQ(result.value(), stack_codecs[i]);
  }

  auto result = convertCodecId(core);
  ASSERT_EQ(result, std::nullopt);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecInfo) {
  btav_a2dp_codec_info_t stack_codec_info = {
          .codec_id = ::bluetooth::a2dp::CodecId::SBC,
          .name = "SBC",
          .media_codec_capabilites =
                  {0x06,  // Length of service category: 6
                   0x00,  // Media Type: Audio
                   0x00,  // Media codec audio type: SBC
                   0x3A,  // Sampling Frequency: 44100Hz|48000Hz, Chanel mode: Mono|Stereo
                   0xFF,  // Block length: 4|8|12|16, Subbands: 4|8, Allocation method: SNR|Loudness
                   0x02,  // Minimum bitpool
                   0x35,  // Maximum bitpool
                   0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
          .codec_capabilities =
                  {.codec_type = BTAV_A2DP_CODEC_INDEX_SOURCE_MIN,
                   .codec_priority = BTAV_A2DP_CODEC_PRIORITY_DEFAULT,
                   .sample_rate = static_cast<btav_a2dp_codec_sample_rate_t>(
                           BTAV_A2DP_CODEC_SAMPLE_RATE_44100 | BTAV_A2DP_CODEC_SAMPLE_RATE_48000),
                   .bits_per_sample = static_cast<btav_a2dp_codec_bits_per_sample_t>(
                           BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16 | BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24 |
                           BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32),
                   .channel_mode = static_cast<btav_a2dp_codec_channel_mode_t>(
                           BTAV_A2DP_CODEC_CHANNEL_MODE_MONO | BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO),
                   .codec_specific_1 = 0,
                   .codec_specific_2 = 0,
                   .codec_specific_3 = 0,
                   .codec_specific_4 = 0},
          .lossless = false};

  CodecInfo aidl_codec_info;
  aidl_codec_info.id = CodecId::A2dp::SBC;
  aidl_codec_info.name = "SBC";
  aidl_codec_info.transport = CodecInfo::Transport::make<CodecInfo::Transport::Tag::a2dp>();
  auto& transport = aidl_codec_info.transport.get<CodecInfo::Transport::Tag::a2dp>();
  transport.capabilities = {
          0x3A,  // Sampling Frequency: 44100Hz|48000Hz, Chanel mode: Mono|Stereo
          0xFF,  // Block length: 4|8|12|16, Subbands: 4|8, Allocation method: SNR|Loudness
          0x02,  // Minimum bitpool
          0x35,  // Maximum bitpool
  };
  transport.channelMode = {ChannelMode::MONO, ChannelMode::STEREO};
  transport.samplingFrequencyHz = {44100, 48000};
  transport.bitdepth = {16, 24, 32};
  transport.lossless = false;

  auto result = convertCodecInfo(aidl_codec_info);
  ASSERT_TRUE(result.has_value());
  btav_a2dp_codec_info_t stack_codec_info_result = result.value();
  ASSERT_EQ(stack_codec_info_result.codec_id, stack_codec_info.codec_id);
  ASSERT_EQ(stack_codec_info_result.codec_id, stack_codec_info.codec_id);
  ASSERT_EQ(stack_codec_info_result.name, stack_codec_info.name);
  bluetooth::log::info("stack_codec_info: \n{}",
                       codecInfoToString(stack_codec_info.media_codec_capabilites));
  bluetooth::log::info("stack_codec_info_result: \n{}",
                       codecInfoToString(stack_codec_info_result.media_codec_capabilites));
  ASSERT_EQ(memcmp(stack_codec_info_result.media_codec_capabilites,
                   stack_codec_info.media_codec_capabilites,
                   sizeof(stack_codec_info_result.media_codec_capabilites)),
            0);
  ASSERT_EQ(stack_codec_info_result.codec_capabilities, stack_codec_info.codec_capabilities);
  ASSERT_EQ(stack_codec_info_result.lossless, stack_codec_info.lossless);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecInfo_Failure) {
  CodecInfo aidl_codec_info;
  aidl_codec_info.id = CodecId::make<CodecId::core>(static_cast<CodecId::Core>(0));
  aidl_codec_info.name = "FAIL";
  aidl_codec_info.transport = CodecInfo::Transport::make<CodecInfo::Transport::Tag::a2dp>();
  auto result = convertCodecInfo(aidl_codec_info);
  ASSERT_EQ(result, std::nullopt);
}
