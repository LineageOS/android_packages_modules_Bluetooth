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

// Stubbed non-standard codec.

#include "stack/include/a2dp_vendor_ldac.h"

bool A2DP_IsCodecValidLdac(const uint8_t* p_codec_info) { return false; }

bool A2DP_VendorUsesRtpHeaderLdac(bool content_protection_enabled,
                                  const uint8_t* p_codec_info) {
  return false;
}

bool A2DP_VendorCodecTypeEqualsLdac(const uint8_t* p_codec_info_a,
                                    const uint8_t* p_codec_info_b) {
  return false;
}

bool A2DP_VendorCodecEqualsLdac(const uint8_t* p_codec_info_a,
                                const uint8_t* p_codec_info_b) {
  return false;
}

int A2DP_VendorGetTrackSampleRateLdac(const uint8_t* p_codec_info) { return -1; }

int A2DP_VendorGetTrackBitsPerSampleLdac(const uint8_t* p_codec_info) { return -1; }

int A2DP_VendorGetTrackChannelCountLdac(const uint8_t* p_codec_info) { return -1; }

int A2DP_VendorGetChannelModeCodeLdac(const uint8_t* p_codec_info) { return -1; }

int A2DP_VendorGetFrameSizeLdac(const uint8_t* p_codec_info) { return -1; }

bool A2DP_VendorGetPacketTimestampLdac(const uint8_t* p_codec_info, const uint8_t* p_data,
                                       uint32_t* p_timestamp) {
  return false;
}

bool A2DP_VendorBuildCodecHeaderLdac(const uint8_t* p_codec_info, BT_HDR* p_buf,
                                     uint16_t frames_per_packet) {
  return false;
}

std::string A2DP_VendorCodecInfoStringLdac(const uint8_t* p_codec_info) {
  return "Unsupported codec: Ldac";
}

const tA2DP_ENCODER_INTERFACE* A2DP_VendorGetEncoderInterfaceLdac(const uint8_t* p_codec_info) {
  return nullptr;
}

bool A2DP_VendorAdjustCodecLdac(uint8_t* p_codec_info) { return false; }

bool A2DP_VendorInitCodecConfigLdac(AvdtpSepConfig* p_cfg) { return false; }

A2dpCodecConfigLdacSource::A2dpCodecConfigLdacSource(btav_a2dp_codec_priority_t codec_priority)
    : A2dpCodecConfigLdacBase(BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC, "LDAC", codec_priority, true) {}

A2dpCodecConfigLdacSource::~A2dpCodecConfigLdacSource() {}

bool A2dpCodecConfigLdacSource::init() { return false; }

bool A2dpCodecConfigLdacSource::useRtpHeaderMarkerBit() const { return false; }

void A2dpCodecConfigLdacSource::debug_codec_dump(int fd) {}

tA2DP_STATUS A2dpCodecConfigLdacBase::setCodecConfig(const uint8_t* p_peer_codec_info,
                                                     bool is_capability,
                                                     uint8_t* p_result_codec_config) {
  return AVDTP_UNSUPPORTED_CONFIGURATION;
}

bool A2dpCodecConfigLdacBase::setPeerCodecCapabilities(const uint8_t* p_peer_codec_capabilities) {
  return false;
}
