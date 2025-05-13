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

#include "a2dp_constants.h"
#include "a2dp_vendor_aptx_constants.h"
#include "a2dp_vendor_aptx_hd_constants.h"
#include "a2dp_vendor_ldac_constants.h"
#include "a2dp_vendor_opus_constants.h"

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

TEST_F(A2dpEncodingAidlUtilsTest, StackBitsPerSample) {
  ASSERT_EQ(convertBitsPerSample(16), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16);
  ASSERT_EQ(convertBitsPerSample(24), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24);
  ASSERT_EQ(convertBitsPerSample(32), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32);
  ASSERT_EQ(convertBitsPerSample(123), BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE);
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

  bluetooth::log::info("codec_info: \n{}", codecInfoToString(codec_info));
  bluetooth::log::info("result_sbc_codec_info: \n{}", codecInfoToString(result_sbc_codec_info));

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

  bluetooth::log::info("codec_info: \n{}", codecInfoToString(codec_info));
  bluetooth::log::info("result_ldac_codec_info: \n{}", codecInfoToString(result_ldac_codec_info));

  ASSERT_EQ(memcmp(codec_info, result_ldac_codec_info, sizeof(result_ldac_codec_info)), 0);
}

TEST_F(A2dpEncodingAidlUtilsTest, StackCodecCapabilitiesCore) {
  uint8_t codec_info[AVDT_CODEC_SIZE] = {0};
  std::vector<uint8_t> capabilities = {};
  CodecId core = CodecId::make<CodecId::core>(static_cast<CodecId::Core>(0));

  ASSERT_FALSE(convertCodecCapabilities(core, capabilities, codec_info));
}
