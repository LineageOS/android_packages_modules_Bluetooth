/*
 * Copyright (C) 2026, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// A2DP support for the LHDC V3 codec.

#pragma once

#include <cstdint>
#include <string>

#include "stack/include/a2dp_codec_api.h"
#include "stack/include/a2dp_constants.h"
#include "stack/include/a2dp_vendor_lhdcv3_constants.h"
#include "stack/include/avdt_api.h"

typedef struct {
  uint32_t vendorId;
  uint16_t codecId;
  uint8_t sampleRate;
  uint8_t bitsPerSample;
  uint8_t version;
  uint8_t maxTargetBitrate;
  uint8_t channelSplit;
  bool hasFeatureJAS;
  bool hasFeatureAR;
  bool hasFeatureLL;
  bool hasFeatureLLAC;
  bool hasFeatureMETA;
  bool hasFeatureMinBitrate;
  bool hasFeatureLARC;
  bool hasFeatureV4;
} tA2DP_LHDCV3_CIE;

class A2dpCodecConfigLhdcV3Source : public A2dpCodecConfig {
public:
  A2dpCodecConfigLhdcV3Source(btav_a2dp_codec_priority_t codec_priority);
  virtual ~A2dpCodecConfigLhdcV3Source();

  bool init() override;
  tA2DP_STATUS setCodecConfig(const uint8_t* p_peer_codec_info, bool is_capability,
                              uint8_t* p_result_codec_config) override;
  bool setPeerCodecCapabilities(const uint8_t* p_peer_codec_capabilities) override;
  int getTrackBitRate() const override;

private:
  bool useRtpHeaderMarkerBit() const override;
  void debug_codec_dump(int fd) override;
};

bool A2DP_IsCodecValidLhdcV3(const uint8_t* p_codec_info);
bool A2DP_VendorUsesRtpHeaderLhdcV3(bool content_protection_enabled, const uint8_t* p_codec_info);
const char* A2DP_VendorCodecNameLhdcV3(const uint8_t* p_codec_info);
bool A2DP_VendorCodecTypeEqualsLhdcV3(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b);
bool A2DP_VendorCodecEqualsLhdcV3(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b);
int A2DP_VendorGetTrackSampleRateLhdcV3(const uint8_t* p_codec_info);
int A2DP_VendorGetTrackBitsPerSampleLhdcV3(const uint8_t* p_codec_info);
int A2DP_VendorGetTrackChannelCountLhdcV3(const uint8_t* p_codec_info);
int A2DP_VendorGetChannelModeCodeLhdcV3(const uint8_t* p_codec_info);
bool A2DP_VendorGetPacketTimestampLhdcV3(const uint8_t* p_codec_info, const uint8_t* p_data,
                                         uint32_t* p_timestamp);
bool A2DP_VendorBuildCodecHeaderLhdcV3(const uint8_t* p_codec_info, BT_HDR* p_buf,
                                       uint16_t frames_per_packet);
std::string A2DP_VendorCodecInfoStringLhdcV3(const uint8_t* p_codec_info);
bool A2DP_VendorGetMaxBitRateLhdcV3(uint32_t* retval, const uint8_t* p_codec_info);
bool A2DP_VendorGetVersionLhdcV3(uint32_t* retval, const uint8_t* p_codec_info);
bool A2DP_VendorHasLLFlagLhdcV3(uint8_t* retval, const uint8_t* p_codec_info);
const tA2DP_ENCODER_INTERFACE* A2DP_VendorGetEncoderInterfaceLhdcV3(const uint8_t* p_codec_info);
bool A2DP_VendorAdjustCodecLhdcV3(uint8_t* p_codec_info);
btav_a2dp_codec_index_t A2DP_VendorSourceCodecIndexLhdcV3(const uint8_t* p_codec_info);
bool A2DP_VendorInitCodecConfigLhdcV3(AvdtpSepConfig* p_cfg);
