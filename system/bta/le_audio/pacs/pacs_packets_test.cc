/*
 * Copyright (C) 2025 The Android Open Source Project
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
#include <gtest/gtest.h>

#include <vector>

#define PACKET_TESTING  // Instantiate the generated tests from the packet files
#include "bta/le_audio/le_audio_types.h"
#include "packet/raw_builder.h"
#include "pacs/pacs_packets.h"

namespace bluetooth::le_audio::test {

static std::vector<uint8_t> GetReferencePacRecords() {
  std::vector<uint8_t> pacValue;

  // Number of PAC records
  pacValue.push_back(0x01);

  pacValue.push_back(le_audio::types::kLeAudioCodingFormatLC3);
  pacValue.push_back(0x00);
  pacValue.push_back(0x00);
  pacValue.push_back(0x00);
  pacValue.push_back(0x00);

  // Codec Capability Length
  pacValue.push_back(0x13);

  // Sampling Frequency LTV
  pacValue.push_back(0x03);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedSamplingFrequencies);
  pacValue.push_back((uint8_t)(le_audio::codec_spec_caps::kLeAudioSamplingFreq48000Hz >> 0 & 0xFF));
  pacValue.push_back((uint8_t)(le_audio::codec_spec_caps::kLeAudioSamplingFreq48000Hz >> 8 & 0xFF));

  // Frame Duration LTV
  pacValue.push_back(0x02);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedFrameDurations);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioCodecFrameDur10000us);

  // Channel Count LTV
  pacValue.push_back(0x02);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedAudioChannelCounts);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioCodecChannelCountSingleChannel);

  // Octets Per Frame LTV
  pacValue.push_back(0x05);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedOctetsPerCodecFrame);
  //  min
  pacValue.push_back((uint8_t)(le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 0));
  pacValue.push_back((uint8_t)(le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 8));
  //  max
  pacValue.push_back((uint8_t)(le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 0));
  pacValue.push_back((uint8_t)(le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 8));

  // Codec Blocks Per Sdu LTV
  pacValue.push_back(0x02);
  pacValue.push_back(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu);
  pacValue.push_back(0x01);

  // Metadata length
  pacValue.push_back(0x00);
  return pacValue;
}

TEST(PacsPacketsTests, PacRecordEmpty) {
  std::vector<pacs::PacRecord> pac_records;
  auto pac = pacs::PacCharValueBuilder::Create(pac_records);
  auto pacValue = pac->SerializeToBytes();

  // Expect just the length field
  ASSERT_EQ(pacValue.size(), 1lu);
  ASSERT_EQ(pacValue[0], 0);  // Number of PAC records
}

TEST(PacsPacketsTests, PacRecordSingle) {
  auto codec_spec_cap =
          le_audio::types::LeAudioLtvMap()
                  .Add(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedSamplingFrequencies,
                       (uint16_t)le_audio::codec_spec_caps::kLeAudioSamplingFreq48000Hz)
                  .Add(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedFrameDurations,
                       (uint8_t)le_audio::codec_spec_caps::kLeAudioCodecFrameDur10000us)
                  .Add(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedAudioChannelCounts,
                       le_audio::codec_spec_caps::kLeAudioCodecChannelCountSingleChannel)
                  .Add(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedOctetsPerCodecFrame,
                       std::vector<uint8_t>{
                               (le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 0),
                               le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 8,
                               le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 0,
                               (le_audio::codec_spec_caps::kLeAudioCodecFrameLen120 >> 8)})
                  .Add(le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu,
                       (uint8_t)0x01);

  // Pac characteristic packet builder
  auto pac = pacs::PacCharValueBuilder::Create(std::vector<pacs::PacRecord>{
          pacs::PacRecord(pacs::CodecId(0x06, 0x0000, 0x0000), codec_spec_cap.RawPacket(), {}),
  });

  auto pacValue = pac->SerializeToBytes();
  ASSERT_NE(pacValue.size(), 0lu);
  ASSERT_EQ(GetReferencePacRecords(), pacValue);
}

static std::vector<uint8_t> GetReferenceAudioLocaction() {
  return {
          ((le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
            le_audio::codec_spec_conf::kLeAudioLocationFrontRight) >>
           0) & 0xFF,
          ((le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
            le_audio::codec_spec_conf::kLeAudioLocationFrontRight) >>
           8) & 0xFF,
          ((le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
            le_audio::codec_spec_conf::kLeAudioLocationFrontRight) >>
           16) & 0xFF,
          ((le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
            le_audio::codec_spec_conf::kLeAudioLocationFrontRight) >>
           24) & 0xFF,
  };
}

TEST(PacsPacketsTests, SinkAudioLocation) {
  auto location = pacs::AudioLocationsCharValueBuilder::Create(
          le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
          le_audio::codec_spec_conf::kLeAudioLocationFrontRight);
  auto rawPac = location->SerializeToBytes();
  ASSERT_NE(rawPac.size(), 0lu);
  ASSERT_EQ(GetReferenceAudioLocaction(), rawPac);
}
}  // namespace bluetooth::le_audio::test
