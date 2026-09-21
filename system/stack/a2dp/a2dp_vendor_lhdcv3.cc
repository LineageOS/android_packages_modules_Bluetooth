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

#define LOG_TAG "a2dp_vendor_lhdcv3"

#include "stack/include/a2dp_vendor_lhdcv3.h"

#include <bluetooth/log.h>
#include <string.h>

#include <cstdint>
#include <string>

#include "stack/include/a2dp_api.h"
#include "stack/include/a2dp_codec_api.h"
#include "stack/include/a2dp_vendor_lhdcv3_encoder.h"
#include "stack/include/a2dp_vendor_lhdcv5_constants.h"
#include "stack/include/bt_hdr.h"

using namespace bluetooth;

static const tA2DP_LHDCV3_CIE a2dp_lhdcv3_source_caps = {
        .vendorId = A2DP_LHDC_VENDOR_ID_V3,
        .codecId = A2DP_LHDCV3_CODEC_ID,
        .sampleRate = A2DP_LHDCV3_SAMPLING_FREQ_44100 | A2DP_LHDCV3_SAMPLING_FREQ_48000 |
                      A2DP_LHDCV3_SAMPLING_FREQ_96000,
        .bitsPerSample = A2DP_LHDCV3_BIT_FMT_16 | A2DP_LHDCV3_BIT_FMT_24,
        .version = A2DP_LHDCV3_VER_3,
        .maxTargetBitrate = A2DP_LHDCV3_MAX_BIT_RATE_900K,
        .channelSplit = A2DP_LHDCV3_CH_SPLIT_NONE,
        .hasFeatureJAS = false,
        .hasFeatureAR = false,
        .hasFeatureLL = false,
        .hasFeatureLLAC = false,
        .hasFeatureMETA = false,
        .hasFeatureMinBitrate = true,
        .hasFeatureLARC = false,
        .hasFeatureV4 = false,
};

static const tA2DP_ENCODER_INTERFACE a2dp_encoder_interface_lhdcv3 = {
        a2dp_vendor_lhdcv3_encoder_init,
        a2dp_vendor_lhdcv3_encoder_cleanup,
        a2dp_vendor_lhdcv3_feeding_reset,
        a2dp_vendor_lhdcv3_feeding_flush,
        a2dp_vendor_lhdcv3_get_encoder_interval_ms,
        a2dp_vendor_lhdcv3_get_effective_frame_size,
        a2dp_vendor_lhdcv3_send_frames,
        a2dp_vendor_lhdcv3_set_transmit_queue_length,
};

static tA2DP_STATUS A2DP_BuildInfoLhdcV3(uint8_t media_type, const tA2DP_LHDCV3_CIE* p_ie,
                                         uint8_t* p_result) {
  if (p_ie == nullptr || p_result == nullptr) {
    log::error("nullptr input");
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  if ((p_ie->sampleRate & A2DP_LHDCV3_SAMPLING_FREQ_MASK) == A2DP_LHDCV3_SAMPLING_FREQ_NS) {
    log::error("invalid sample rate (0x{:02X})", p_ie->sampleRate);
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  if ((p_ie->bitsPerSample & A2DP_LHDCV3_BIT_FMT_MASK) == A2DP_LHDCV3_BIT_FMT_NS) {
    log::error("invalid bits per sample (0x{:02X})", p_ie->bitsPerSample);
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  if ((p_ie->version & A2DP_LHDCV3_VERSION_MASK) == A2DP_LHDCV3_VER_NS) {
    log::error("invalid codec version (0x{:02X})", p_ie->version);
    return A2DP_INVALID_CODEC_PARAMETER;
  }

  *p_result++ = A2DP_LHDCV3_CODEC_LEN;   // H0
  *p_result++ = (media_type << 4);       // H1
  *p_result++ = A2DP_MEDIA_CT_NON_A2DP;  // H2

  *p_result++ = (uint8_t)(p_ie->vendorId & 0x000000FF);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0x0000FF00) >> 8);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0x00FF0000) >> 16);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0xFF000000) >> 24);
  *p_result++ = (uint8_t)(p_ie->codecId & 0x00FF);
  *p_result++ = (uint8_t)((p_ie->codecId & 0xFF00) >> 8);

  uint8_t para = (p_ie->sampleRate & A2DP_LHDCV3_SAMPLING_FREQ_MASK) |
                 (p_ie->bitsPerSample & A2DP_LHDCV3_BIT_FMT_MASK);
  if (p_ie->hasFeatureJAS) {
    para |= A2DP_LHDCV3_FEATURE_JAS;
  }
  if (p_ie->hasFeatureAR) {
    para |= A2DP_LHDCV3_FEATURE_AR;
  }
  *p_result++ = para;  // P6

  para = (p_ie->version & A2DP_LHDCV3_VERSION_MASK) |
         (p_ie->maxTargetBitrate & A2DP_LHDCV3_MAX_BIT_RATE_MASK);
  if (p_ie->hasFeatureLL) {
    para |= A2DP_LHDCV3_FEATURE_LL;
  }
  if (p_ie->hasFeatureLLAC) {
    para |= A2DP_LHDCV3_FEATURE_LLAC;
  }
  *p_result++ = para;  // P7

  para = p_ie->channelSplit & A2DP_LHDCV3_CH_SPLIT_MASK;
  if (p_ie->hasFeatureMETA) {
    para |= A2DP_LHDCV3_FEATURE_META;
  }
  if (p_ie->hasFeatureMinBitrate) {
    para |= A2DP_LHDCV3_FEATURE_MIN_BR;
  }
  if (p_ie->hasFeatureLARC) {
    para |= A2DP_LHDCV3_FEATURE_LARC;
  }
  if (p_ie->hasFeatureV4) {
    para |= A2DP_LHDCV3_FEATURE_V4;
  }
  *p_result++ = para;  // P8

  return A2DP_SUCCESS;
}

// A configuration, unlike a capability, names exactly one sample rate and one
// bit depth.
static tA2DP_STATUS A2DP_ParseInfoLhdcV3(tA2DP_LHDCV3_CIE* p_ie, const uint8_t* p_codec_info,
                                         bool is_capability) {
  if (p_ie == nullptr || p_codec_info == nullptr) {
    log::error("nullptr input");
    return A2DP_INVALID_CODEC_PARAMETER;
  }

  uint8_t losc = *p_codec_info++;
  uint8_t media_type = (*p_codec_info++) >> 4;
  tA2DP_CODEC_TYPE codec_type = static_cast<tA2DP_CODEC_TYPE>(*p_codec_info++);
  if (losc != A2DP_LHDCV3_CODEC_LEN) {
    log::error("wrong length {}", losc);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }
  if (media_type != AVDT_MEDIA_TYPE_AUDIO || codec_type != A2DP_MEDIA_CT_NON_A2DP) {
    log::error("invalid media type 0x{:X} codec_type 0x{:X}", media_type, codec_type);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  p_ie->vendorId = (*p_codec_info & 0x000000FF) | (*(p_codec_info + 1) << 8 & 0x0000FF00) |
                   (*(p_codec_info + 2) << 16 & 0x00FF0000) |
                   (*(p_codec_info + 3) << 24 & 0xFF000000);
  p_codec_info += 4;
  p_ie->codecId = (*p_codec_info & 0x00FF) | (*(p_codec_info + 1) << 8 & 0xFF00);
  p_codec_info += 2;
  if (p_ie->vendorId != A2DP_LHDC_VENDOR_ID_V3 || p_ie->codecId != A2DP_LHDCV3_CODEC_ID) {
    log::error("wrong vendor (0x{:08X}) or codec id (0x{:04X})", p_ie->vendorId, p_ie->codecId);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  p_ie->sampleRate = *p_codec_info & A2DP_LHDCV3_SAMPLING_FREQ_MASK;
  p_ie->bitsPerSample = *p_codec_info & A2DP_LHDCV3_BIT_FMT_MASK;
  p_ie->hasFeatureJAS = (*p_codec_info & A2DP_LHDCV3_FEATURE_JAS) != 0;
  p_ie->hasFeatureAR = (*p_codec_info & A2DP_LHDCV3_FEATURE_AR) != 0;
  p_codec_info++;

  p_ie->version = *p_codec_info & A2DP_LHDCV3_VERSION_MASK;
  p_ie->maxTargetBitrate = *p_codec_info & A2DP_LHDCV3_MAX_BIT_RATE_MASK;
  p_ie->hasFeatureLL = (*p_codec_info & A2DP_LHDCV3_FEATURE_LL) != 0;
  p_ie->hasFeatureLLAC = (*p_codec_info & A2DP_LHDCV3_FEATURE_LLAC) != 0;
  p_codec_info++;

  p_ie->channelSplit = *p_codec_info & A2DP_LHDCV3_CH_SPLIT_MASK;
  p_ie->hasFeatureMETA = (*p_codec_info & A2DP_LHDCV3_FEATURE_META) != 0;
  p_ie->hasFeatureMinBitrate = (*p_codec_info & A2DP_LHDCV3_FEATURE_MIN_BR) != 0;
  p_ie->hasFeatureLARC = (*p_codec_info & A2DP_LHDCV3_FEATURE_LARC) != 0;
  p_ie->hasFeatureV4 = (*p_codec_info & A2DP_LHDCV3_FEATURE_V4) != 0;

  if (is_capability) {
    return A2DP_SUCCESS;
  }
  if (A2DP_BitsSet(p_ie->sampleRate) != A2DP_SET_ONE_BIT) {
    log::error("a configuration names {} sample rates", A2DP_BitsSet(p_ie->sampleRate));
    return A2DP_INVALID_SAMPLING_FREQUENCY;
  }
  if (A2DP_BitsSet(p_ie->bitsPerSample) != A2DP_SET_ONE_BIT) {
    log::error("a configuration names {} bit depths", A2DP_BitsSet(p_ie->bitsPerSample));
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  return A2DP_SUCCESS;
}

bool A2DP_IsCodecValidLhdcV3(const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cfg_cie;
  return A2DP_ParseInfoLhdcV3(&cfg_cie, p_codec_info, false) == A2DP_SUCCESS ||
         A2DP_ParseInfoLhdcV3(&cfg_cie, p_codec_info, true) == A2DP_SUCCESS;
}

bool A2DP_VendorUsesRtpHeaderLhdcV3(bool /* content_protection_enabled */,
                                    const uint8_t* /* p_codec_info */) {
  return true;
}

const char* A2DP_VendorCodecNameLhdcV3(const uint8_t* /* p_codec_info */) { return "LHDC V3"; }

bool A2DP_VendorCodecTypeEqualsLhdcV3(const uint8_t* p_codec_info_a,
                                      const uint8_t* p_codec_info_b) {
  tA2DP_LHDCV3_CIE a, b;
  if (A2DP_ParseInfoLhdcV3(&a, p_codec_info_a, true) != A2DP_SUCCESS) {
    return false;
  }
  if (A2DP_ParseInfoLhdcV3(&b, p_codec_info_b, true) != A2DP_SUCCESS) {
    return false;
  }
  return true;
}

bool A2DP_VendorCodecEqualsLhdcV3(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b) {
  tA2DP_LHDCV3_CIE a, b;
  if (A2DP_ParseInfoLhdcV3(&a, p_codec_info_a, true) != A2DP_SUCCESS) {
    return false;
  }
  if (A2DP_ParseInfoLhdcV3(&b, p_codec_info_b, true) != A2DP_SUCCESS) {
    return false;
  }
  return a.sampleRate == b.sampleRate && a.bitsPerSample == b.bitsPerSample &&
         a.version == b.version && a.maxTargetBitrate == b.maxTargetBitrate;
}

int A2DP_VendorGetTrackSampleRateLhdcV3(const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (A2DP_ParseInfoLhdcV3(&cie, p_codec_info, false) != A2DP_SUCCESS) {
    return -1;
  }
  switch (cie.sampleRate) {
    case A2DP_LHDCV3_SAMPLING_FREQ_44100:
      return 44100;
    case A2DP_LHDCV3_SAMPLING_FREQ_48000:
      return 48000;
    case A2DP_LHDCV3_SAMPLING_FREQ_88200:
      return 88200;
    case A2DP_LHDCV3_SAMPLING_FREQ_96000:
      return 96000;
    default:
      return -1;
  }
}

int A2DP_VendorGetTrackBitsPerSampleLhdcV3(const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (A2DP_ParseInfoLhdcV3(&cie, p_codec_info, false) != A2DP_SUCCESS) {
    return -1;
  }
  switch (cie.bitsPerSample) {
    case A2DP_LHDCV3_BIT_FMT_16:
      return 16;
    case A2DP_LHDCV3_BIT_FMT_24:
      return 24;
    default:
      return -1;
  }
}

int A2DP_VendorGetTrackChannelCountLhdcV3(const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (A2DP_ParseInfoLhdcV3(&cie, p_codec_info, false) != A2DP_SUCCESS) {
    return -1;
  }
  return 2;
}

int A2DP_VendorGetChannelModeCodeLhdcV3(const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (A2DP_ParseInfoLhdcV3(&cie, p_codec_info, false) != A2DP_SUCCESS) {
    return -1;
  }
  return BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
}

bool A2DP_VendorGetPacketTimestampLhdcV3(const uint8_t* /* p_codec_info */, const uint8_t* p_data,
                                         uint32_t* p_timestamp) {
  if (p_data == nullptr || p_timestamp == nullptr) {
    return false;
  }
  *p_timestamp = *(const uint32_t*)p_data;
  return true;
}

bool A2DP_VendorBuildCodecHeaderLhdcV3(const uint8_t* /* p_codec_info */, BT_HDR* p_buf,
                                       uint16_t frames_per_packet) {
  if (p_buf == nullptr) {
    return false;
  }
  if (p_buf->offset < A2DP_LHDCV3_MPL_HDR_LEN) {
    log::error("no room for the media payload header");
    return false;
  }
  p_buf->offset -= A2DP_LHDCV3_MPL_HDR_LEN;
  p_buf->len += A2DP_LHDCV3_MPL_HDR_LEN;
  uint8_t* p = (uint8_t*)(p_buf + 1) + p_buf->offset;
  uint16_t header = frames_per_packet << A2DP_LHDCV3_HDR_NUM_SHIFT;
  *p++ = (uint8_t)(header & 0xFF);
  *p = (uint8_t)((header >> 8) & 0xFF);
  return true;
}

std::string A2DP_VendorCodecInfoStringLhdcV3(const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  tA2DP_STATUS status = A2DP_ParseInfoLhdcV3(&cie, p_codec_info, true);
  if (status != A2DP_SUCCESS) {
    return "A2DP_ParseInfoLhdcV3 failed: " + std::to_string(status);
  }
  std::string res = "\tname: LHDC V3\n";
  res += "\tsample rate mask: " + std::to_string(cie.sampleRate) + "\n";
  res += "\tbit depth mask: " + std::to_string(cie.bitsPerSample) + "\n";
  res += "\tversion: " + std::to_string(cie.version) + "\n";
  res += "\tmax target bitrate: " + std::to_string(cie.maxTargetBitrate) + "\n";
  return res;
}

bool A2DP_VendorGetMaxBitRateLhdcV3(uint32_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (retval == nullptr || A2DP_ParseInfoLhdcV3(&cie, p_codec_info, true) != A2DP_SUCCESS) {
    return false;
  }
  switch (cie.maxTargetBitrate) {
    case A2DP_LHDCV3_MAX_BIT_RATE_400K:
      *retval = 400;
      break;
    case A2DP_LHDCV3_MAX_BIT_RATE_500K:
      *retval = 500;
      break;
    default:
      *retval = 900;
      break;
  }
  return true;
}

bool A2DP_VendorGetVersionLhdcV3(uint32_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (retval == nullptr || A2DP_ParseInfoLhdcV3(&cie, p_codec_info, true) != A2DP_SUCCESS) {
    return false;
  }
  *retval = cie.version;
  return true;
}

bool A2DP_VendorHasLLFlagLhdcV3(uint8_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cie;
  if (retval == nullptr || A2DP_ParseInfoLhdcV3(&cie, p_codec_info, true) != A2DP_SUCCESS) {
    return false;
  }
  *retval = cie.hasFeatureLL ? 1 : 0;
  return true;
}

const tA2DP_ENCODER_INTERFACE* A2DP_VendorGetEncoderInterfaceLhdcV3(const uint8_t* p_codec_info) {
  if (!A2DP_IsCodecValidLhdcV3(p_codec_info)) {
    return nullptr;
  }
  return &a2dp_encoder_interface_lhdcv3;
}

bool A2DP_VendorAdjustCodecLhdcV3(uint8_t* p_codec_info) {
  tA2DP_LHDCV3_CIE cfg_cie;
  return A2DP_ParseInfoLhdcV3(&cfg_cie, p_codec_info, true) == A2DP_SUCCESS;
}

btav_a2dp_codec_index_t A2DP_VendorSourceCodecIndexLhdcV3(const uint8_t* /* p_codec_info */) {
  return BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV3;
}

bool A2DP_VendorInitCodecConfigLhdcV3(AvdtpSepConfig* p_cfg) {
  return A2DP_BuildInfoLhdcV3(AVDT_MEDIA_TYPE_AUDIO, &a2dp_lhdcv3_source_caps, p_cfg->codec_info) ==
         A2DP_SUCCESS;
}

// Highest first, so the search takes the best both ends allow.
static const struct {
  uint8_t bit;
  btav_a2dp_codec_sample_rate_t value;
} kSampleRates[] = {
        {A2DP_LHDCV3_SAMPLING_FREQ_96000, BTAV_A2DP_CODEC_SAMPLE_RATE_96000},
        {A2DP_LHDCV3_SAMPLING_FREQ_88200, BTAV_A2DP_CODEC_SAMPLE_RATE_88200},
        {A2DP_LHDCV3_SAMPLING_FREQ_48000, BTAV_A2DP_CODEC_SAMPLE_RATE_48000},
        {A2DP_LHDCV3_SAMPLING_FREQ_44100, BTAV_A2DP_CODEC_SAMPLE_RATE_44100},
};

static const struct {
  uint8_t bit;
  btav_a2dp_codec_bits_per_sample_t value;
} kBitDepths[] = {
        {A2DP_LHDCV3_BIT_FMT_24, BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24},
        {A2DP_LHDCV3_BIT_FMT_16, BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16},
};

A2dpCodecConfigLhdcV3Source::A2dpCodecConfigLhdcV3Source(
        btav_a2dp_codec_priority_t codec_priority)
    : A2dpCodecConfig(BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV3, bluetooth::a2dp::CodecId::LHDCV3,
                      "LHDC V3", codec_priority) {
  codec_local_capability_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  for (const auto& entry : kSampleRates) {
    if (a2dp_lhdcv3_source_caps.sampleRate & entry.bit) {
      codec_local_capability_.sample_rate |= entry.value;
    }
  }
  codec_local_capability_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  for (const auto& entry : kBitDepths) {
    if (a2dp_lhdcv3_source_caps.bitsPerSample & entry.bit) {
      codec_local_capability_.bits_per_sample |= entry.value;
    }
  }
  codec_local_capability_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
}

A2dpCodecConfigLhdcV3Source::~A2dpCodecConfigLhdcV3Source() {}

bool A2dpCodecConfigLhdcV3Source::init() {
  if (!A2DP_VendorLoadEncoderLhdcV3()) {
    log::error("cannot load the encoder");
    return false;
  }
  return true;
}

bool A2dpCodecConfigLhdcV3Source::useRtpHeaderMarkerBit() const { return false; }

int A2dpCodecConfigLhdcV3Source::getTrackBitRate() const {
  return a2dp_vendor_lhdcv3_get_bitrate();
}

bool A2dpCodecConfigLhdcV3Source::setPeerCodecCapabilities(
        const uint8_t* p_peer_codec_capabilities) {
  std::lock_guard<std::recursive_mutex> lock(codec_mutex_);
  tA2DP_LHDCV3_CIE peer;
  if (A2DP_ParseInfoLhdcV3(&peer, p_peer_codec_capabilities, true) != A2DP_SUCCESS) {
    log::error("can't parse the peer's capabilities");
    return false;
  }

  codec_selectable_capability_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  for (const auto& entry : kSampleRates) {
    if (a2dp_lhdcv3_source_caps.sampleRate & peer.sampleRate & entry.bit) {
      codec_selectable_capability_.sample_rate |= entry.value;
    }
  }
  codec_selectable_capability_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  for (const auto& entry : kBitDepths) {
    if (a2dp_lhdcv3_source_caps.bitsPerSample & peer.bitsPerSample & entry.bit) {
      codec_selectable_capability_.bits_per_sample |= entry.value;
    }
  }
  codec_selectable_capability_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;

  if (A2DP_BuildInfoLhdcV3(AVDT_MEDIA_TYPE_AUDIO, &peer, ota_codec_peer_capability_.data()) !=
      A2DP_SUCCESS) {
    log::error("can't keep the peer's capabilities");
    return false;
  }
  return true;
}

// The quality field only carries a choice when it carries the magic with it.
static uint8_t A2DP_QualityForMaxBitRateLhdcV3(uint8_t max_target_bitrate) {
  switch (max_target_bitrate) {
    case A2DP_LHDCV3_MAX_BIT_RATE_400K:
      return A2DP_LHDC_QUALITY_LOW;
    case A2DP_LHDCV3_MAX_BIT_RATE_500K:
      return A2DP_LHDC_QUALITY_MID;
    default:
      return A2DP_LHDC_QUALITY_HIGH;
  }
}

tA2DP_STATUS A2dpCodecConfigLhdcV3Source::setCodecConfig(const uint8_t* p_peer_codec_info,
                                                        bool is_capability,
                                                        uint8_t* p_result_codec_config) {
  std::lock_guard<std::recursive_mutex> lock(codec_mutex_);
  tA2DP_LHDCV3_CIE peer;
  tA2DP_STATUS status = A2DP_ParseInfoLhdcV3(&peer, p_peer_codec_info, is_capability);
  if (status != A2DP_SUCCESS) {
    log::error("can't parse the peer's Sink capabilities: error = {}", status);
    return status;
  }

  tA2DP_LHDCV3_CIE result = {};
  result.vendorId = a2dp_lhdcv3_source_caps.vendorId;
  result.codecId = a2dp_lhdcv3_source_caps.codecId;
  // The peer says which version of the codec it speaks, and the frame does not
  // carry it, so the configuration keeps what the peer asked for.
  result.version = peer.version != A2DP_LHDCV3_VER_NS ? peer.version
                                                      : a2dp_lhdcv3_source_caps.version;
  result.maxTargetBitrate = peer.maxTargetBitrate;
  result.channelSplit = A2DP_LHDCV3_CH_SPLIT_NONE;
  result.hasFeatureLL = peer.hasFeatureLL && a2dp_lhdcv3_source_caps.hasFeatureLL;
  result.hasFeatureMinBitrate =
          peer.hasFeatureMinBitrate && a2dp_lhdcv3_source_caps.hasFeatureMinBitrate;

  uint8_t rates = a2dp_lhdcv3_source_caps.sampleRate & peer.sampleRate;
  codec_config_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  for (const auto& entry : kSampleRates) {
    if ((rates & entry.bit) && codec_user_config_.sample_rate == entry.value) {
      result.sampleRate = entry.bit;
      codec_config_.sample_rate = entry.value;
      break;
    }
  }
  if (codec_config_.sample_rate == BTAV_A2DP_CODEC_SAMPLE_RATE_NONE) {
    for (const auto& entry : kSampleRates) {
      if (rates & entry.bit) {
        result.sampleRate = entry.bit;
        codec_config_.sample_rate = entry.value;
        break;
      }
    }
  }
  if (codec_config_.sample_rate == BTAV_A2DP_CODEC_SAMPLE_RATE_NONE) {
    log::error("no sample rate in common: local 0x{:02X} peer 0x{:02X}",
               a2dp_lhdcv3_source_caps.sampleRate, peer.sampleRate);
    return A2DP_NOT_SUPPORTED_CODEC_PARAMETER;
  }

  uint8_t depths = a2dp_lhdcv3_source_caps.bitsPerSample & peer.bitsPerSample;
  codec_config_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  for (const auto& entry : kBitDepths) {
    if ((depths & entry.bit) && codec_user_config_.bits_per_sample == entry.value) {
      result.bitsPerSample = entry.bit;
      codec_config_.bits_per_sample = entry.value;
      break;
    }
  }
  if (codec_config_.bits_per_sample == BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE) {
    for (const auto& entry : kBitDepths) {
      if (depths & entry.bit) {
        result.bitsPerSample = entry.bit;
        codec_config_.bits_per_sample = entry.value;
        break;
      }
    }
  }
  if (codec_config_.bits_per_sample == BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE) {
    log::error("no bit depth in common: local 0x{:02X} peer 0x{:02X}",
               a2dp_lhdcv3_source_caps.bitsPerSample, peer.bitsPerSample);
    return A2DP_NOT_SUPPORTED_CODEC_PARAMETER;
  }

  codec_config_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  codec_config_.codec_type = BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV3;

  uint8_t ceiling = A2DP_QualityForMaxBitRateLhdcV3(result.maxTargetBitrate);
  if ((codec_user_config_.codec_specific_1 & A2DP_LHDC_VENDOR_CMD_MASK) !=
      A2DP_LHDC_QUALITY_MAGIC_NUM) {
    codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
    codec_user_config_.codec_specific_1 |= (A2DP_LHDC_QUALITY_MAGIC_NUM | ceiling);
  }
  uint8_t quality = codec_user_config_.codec_specific_1 & A2DP_LHDC_QUALITY_MASK;
  if (quality != A2DP_LHDC_QUALITY_ABR && quality > ceiling) {
    codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
    codec_user_config_.codec_specific_1 |= (A2DP_LHDC_QUALITY_MAGIC_NUM | ceiling);
  }
  codec_config_.codec_specific_1 = codec_user_config_.codec_specific_1;

  status = A2DP_BuildInfoLhdcV3(AVDT_MEDIA_TYPE_AUDIO, &result, p_result_codec_config);
  if (status != A2DP_SUCCESS) {
    log::error("can't build the codec configuration: error = {}", status);
    return status;
  }

  status = A2DP_BuildInfoLhdcV3(AVDT_MEDIA_TYPE_AUDIO, &result, ota_codec_config_.data());
  if (status != A2DP_SUCCESS) {
    log::error("can't keep the codec configuration: error = {}", status);
    return status;
  }
  status = A2DP_BuildInfoLhdcV3(
          AVDT_MEDIA_TYPE_AUDIO, &peer,
          is_capability ? ota_codec_peer_capability_.data() : ota_codec_peer_config_.data());
  if (status != A2DP_SUCCESS) {
    log::error("can't keep the peer's codec information: error = {}", status);
    return status;
  }
  return A2DP_SUCCESS;
}
