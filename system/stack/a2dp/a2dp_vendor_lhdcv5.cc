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

/******************************************************************************
 *
 *  Utility functions to help build and parse the LHDCV5 Codec Information
 *  Element and Media Payload.
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth-a2dp"

#include "stack/include/a2dp_vendor_lhdcv5.h"

#include <bluetooth/log.h>

#include <cstring>

#include "internal_include/bt_trace.h"
#include "osi/include/osi.h"
#include "stack/include/a2dp_constants.h"
#include "stack/include/a2dp_vendor_lhdcv5_encoder.h"

using namespace bluetooth;

// data type for the LHDC Codec Information Element
typedef struct {
  uint32_t vendorId;         /* Vendor ID */
  uint16_t codecId;          /* Codec ID */
  uint8_t sampleRate;        /* Sampling Frequency Type */
  uint8_t bitsPerSample;     /* Bits Per Sample Type */
  uint8_t channelMode;       /* Channel Mode */
  uint8_t version;           /* Codec SubVersion Number */
  uint8_t frameLenType;      /* Frame Length Type */
  uint8_t maxTargetBitrate;  /* Max Target Bit Rate Type */
  uint8_t minTargetBitrate;  /* Min Target Bit Rate Type */
  bool hasFeatureAR;         /* FeatureSupported: AR */
  bool hasFeatureJAS;        /* FeatureSupported: JAS */
  bool hasFeatureMETA;       /* FeatureSupported: META */
  bool hasFeatureLL;         /* FeatureSupported: Low Latency */
  bool hasFeatureLLESS48K;   /* FeatureSupported: Lossless enable/disable (standard 48 KHz) */
  bool hasFeatureLLESS24Bit; /* Lossless extended configurable: 24 bit-per-sample */
  bool hasFeatureLLESS96K;   /* Lossless extended configurable: 96 KHz */
} tA2DP_LHDCV5_CIE;

// source capabilities
static const tA2DP_LHDCV5_CIE a2dp_lhdcv5_source_caps = {
        .vendorId = A2DP_LHDC_VENDOR_ID,
        .codecId = A2DP_LHDCV5_CODEC_ID,
        .sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_44100 | A2DP_LHDCV5_SAMPLING_FREQ_48000 |
                      A2DP_LHDCV5_SAMPLING_FREQ_96000 | A2DP_LHDCV5_SAMPLING_FREQ_192000,
        .bitsPerSample = A2DP_LHDCV5_BIT_FMT_16 | A2DP_LHDCV5_BIT_FMT_24,
        .channelMode = A2DP_LHDCV5_CHANNEL_MODE_STEREO,
        .version = A2DP_LHDCV5_VER_1,
        .frameLenType = A2DP_LHDCV5_FRAME_LEN_5MS,
        .maxTargetBitrate = A2DP_LHDCV5_MAX_BIT_RATE_1000K,
        .minTargetBitrate = A2DP_LHDCV5_MIN_BIT_RATE_64K,
        .hasFeatureAR = false,
        .hasFeatureJAS = false,
        .hasFeatureMETA = false,
        .hasFeatureLL = true,
        .hasFeatureLLESS48K = false,
        .hasFeatureLLESS24Bit = false,
        .hasFeatureLLESS96K = false,
};

// default source capabilities for best select
static const tA2DP_LHDCV5_CIE a2dp_lhdcv5_source_default_caps = {
        .vendorId = A2DP_LHDC_VENDOR_ID,
        .codecId = A2DP_LHDCV5_CODEC_ID,
        .sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_48000,
        .bitsPerSample = A2DP_LHDCV5_BIT_FMT_24,
        .channelMode = A2DP_LHDCV5_CHANNEL_MODE_STEREO,
        .version = A2DP_LHDCV5_VER_1,
        .frameLenType = A2DP_LHDCV5_FRAME_LEN_5MS,
        .maxTargetBitrate = A2DP_LHDCV5_MAX_BIT_RATE_1000K,
        .minTargetBitrate = A2DP_LHDCV5_MIN_BIT_RATE_64K,
        .hasFeatureAR = false,
        .hasFeatureJAS = false,
        .hasFeatureMETA = false,
        .hasFeatureLL = true,
        .hasFeatureLLESS48K = false,
        .hasFeatureLLESS24Bit = false,
        .hasFeatureLLESS96K = false,
};

// sink capabilities
static const tA2DP_LHDCV5_CIE a2dp_lhdcv5_sink_caps = {
        .vendorId = A2DP_LHDC_VENDOR_ID,
        .codecId = A2DP_LHDCV5_CODEC_ID,
        .sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_44100 | A2DP_LHDCV5_SAMPLING_FREQ_48000 |
                      A2DP_LHDCV5_SAMPLING_FREQ_96000 | A2DP_LHDCV5_SAMPLING_FREQ_192000,
        .bitsPerSample = A2DP_LHDCV5_BIT_FMT_16 | A2DP_LHDCV5_BIT_FMT_24 | A2DP_LHDCV5_BIT_FMT_32,
        .channelMode = A2DP_LHDCV5_CHANNEL_MODE_STEREO,
        .version = A2DP_LHDCV5_VER_1,
        .frameLenType = A2DP_LHDCV5_FRAME_LEN_5MS,
        .maxTargetBitrate = A2DP_LHDCV5_MAX_BIT_RATE_1000K,
        .minTargetBitrate = A2DP_LHDCV5_MIN_BIT_RATE_64K,
        .hasFeatureAR = false,
        .hasFeatureJAS = false,
        .hasFeatureMETA = false,
        .hasFeatureLL = true,
        .hasFeatureLLESS48K = false,
        .hasFeatureLLESS24Bit = false,
        .hasFeatureLLESS96K = false,
};

// default sink capabilities
[[maybe_unused]]
static const tA2DP_LHDCV5_CIE a2dp_lhdcv5_sink_default_caps = {
        .vendorId = A2DP_LHDC_VENDOR_ID,
        .codecId = A2DP_LHDCV5_CODEC_ID,
        .sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_48000,
        .bitsPerSample = A2DP_LHDCV5_BIT_FMT_24,
        .channelMode = A2DP_LHDCV5_CHANNEL_MODE_STEREO,
        .version = A2DP_LHDCV5_VER_1,
        .frameLenType = A2DP_LHDCV5_FRAME_LEN_5MS,
        .maxTargetBitrate = A2DP_LHDCV5_MAX_BIT_RATE_1000K,
        .minTargetBitrate = A2DP_LHDCV5_MIN_BIT_RATE_64K,
        .hasFeatureAR = false,
        .hasFeatureJAS = false,
        .hasFeatureMETA = false,
        .hasFeatureLL = true,
        .hasFeatureLLESS48K = false,
        .hasFeatureLLESS24Bit = false,
        .hasFeatureLLESS96K = false,
};

//
// Utilities for LHDC configuration on A2DP specifics - START
//
typedef struct {
  btav_a2dp_codec_config_t* _codec_config_;
  btav_a2dp_codec_config_t* _codec_local_capability_;
  btav_a2dp_codec_config_t* _codec_selectable_capability_;
  btav_a2dp_codec_config_t* _codec_user_config_;
  btav_a2dp_codec_config_t* _codec_audio_config_;
} tA2DP_CODEC_CONFIGS_PACK;

typedef struct {
  uint8_t featureCode; /* code of LHDC features */
  uint8_t inSpecBank;  /* target specific to store the feature flag */
  uint8_t bitPos;      /* the bit index(0~63) of the specific(int64_t) that bit store */
  int64_t value;       /* real value of the bit position written to the target specific */
} tA2DP_LHDC_FEATURE_POS;

// default settings of LHDC features configuration on specifics
// info of feature: Low Latency
static const tA2DP_LHDC_FEATURE_POS a2dp_lhdcv5_source_spec_LL = {
        LHDCV5_FEATURE_CODE_LL,
        LHDCV5_FEATURE_ON_A2DP_SPECIFIC_2,
        LHDCV5_FEATURE_LL_SPEC_BIT_POS,
        0x1ULL << LHDCV5_FEATURE_LL_SPEC_BIT_POS,
};

UNUSED_ATTR static const tA2DP_LHDC_FEATURE_POS a2dp_lhdcv5_source_spec_all[] = {
        a2dp_lhdcv5_source_spec_LL,
};

// to check if target feature bit is set in codec_user_config_
static bool A2DP_IsFeatureInUserConfigLhdcV5(tA2DP_CODEC_CONFIGS_PACK* cfgsPtr,
                                             uint8_t featureCode) {
  if (cfgsPtr == nullptr) {
    log::error("invalid cfgsPtr parameter");
    return false;
  }

  switch (featureCode) {
    case LHDCV5_FEATURE_CODE_LL: {
      return LHDCV5_CHECK_IN_A2DP_SPEC(cfgsPtr->_codec_user_config_,
                                       a2dp_lhdcv5_source_spec_LL.inSpecBank,
                                       a2dp_lhdcv5_source_spec_LL.value);
    } break;
    default:
      break;
  }

  return false;
}

// to check if target feature bit is set in codec_config_
static bool A2DP_IsFeatureInCodecConfigLhdcV5(tA2DP_CODEC_CONFIGS_PACK* cfgsPtr,
                                              uint8_t featureCode) {
  if (cfgsPtr == nullptr) {
    log::error("invalid cfgsPtr parameter");
    return false;
  }

  switch (featureCode) {
    case LHDCV5_FEATURE_CODE_LL:
      return LHDCV5_CHECK_IN_A2DP_SPEC(cfgsPtr->_codec_config_,
                                       a2dp_lhdcv5_source_spec_LL.inSpecBank,
                                       a2dp_lhdcv5_source_spec_LL.value);
    default:
      break;
  }

  return false;
}

static void A2DP_UpdateFeatureToSpecLhdcV5(tA2DP_CODEC_CONFIGS_PACK* cfgsPtr, uint16_t toCodecCfg,
                                           bool hasFeature, uint8_t toSpec, int64_t value) {
  if (cfgsPtr == nullptr) {
    log::error("invalid cfgsPtr parameter");
    return;
  }

  if (toCodecCfg & A2DP_LHDC_TO_A2DP_CODEC_CONFIG_) {
    LHDC_SETUP_A2DP_SPEC(cfgsPtr->_codec_config_, toSpec, hasFeature, value);
  }
  if (toCodecCfg & A2DP_LHDC_TO_A2DP_CODEC_LOCAL_CAP_) {
    LHDC_SETUP_A2DP_SPEC(cfgsPtr->_codec_local_capability_, toSpec, hasFeature, value);
  }
  if (toCodecCfg & A2DP_LHDC_TO_A2DP_CODEC_SELECT_CAP_) {
    LHDC_SETUP_A2DP_SPEC(cfgsPtr->_codec_selectable_capability_, toSpec, hasFeature, value);
  }
  if (toCodecCfg & A2DP_LHDC_TO_A2DP_CODEC_USER_) {
    LHDC_SETUP_A2DP_SPEC(cfgsPtr->_codec_user_config_, toSpec, hasFeature, value);
  }
  if (toCodecCfg & A2DP_LHDC_TO_A2DP_CODEC_AUDIO_) {
    LHDC_SETUP_A2DP_SPEC(cfgsPtr->_codec_audio_config_, toSpec, hasFeature, value);
  }
}

// to update feature bit value to target codec config's specific
static void A2DP_UpdateFeatureToA2dpConfigLhdcV5(tA2DP_CODEC_CONFIGS_PACK* cfgsPtr,
                                                 uint8_t featureCode, uint16_t toCodecCfg,
                                                 bool hasFeature) {
  if (cfgsPtr == nullptr) {
    log::error("invalid cfgsPtr parameter");
    return;
  }

  switch (featureCode) {
    case LHDCV5_FEATURE_CODE_LL:
      A2DP_UpdateFeatureToSpecLhdcV5(cfgsPtr, toCodecCfg, hasFeature,
                                     a2dp_lhdcv5_source_spec_LL.inSpecBank,
                                     a2dp_lhdcv5_source_spec_LL.value);
      break;
    default:
      break;
  }
}

// Utilities for LHDC configuration on A2DP specifics - END

static const tA2DP_ENCODER_INTERFACE a2dp_encoder_interface_lhdcv5 = {
        a2dp_vendor_lhdcv5_encoder_init,
        a2dp_vendor_lhdcv5_encoder_cleanup,
        a2dp_vendor_lhdcv5_feeding_reset,
        a2dp_vendor_lhdcv5_feeding_flush,
        a2dp_vendor_lhdcv5_get_encoder_interval_ms,
        a2dp_vendor_lhdcv5_get_effective_frame_size,
        a2dp_vendor_lhdcv5_send_frames,
        a2dp_vendor_lhdcv5_set_transmit_queue_length,
};

UNUSED_ATTR static tA2DP_STATUS A2DP_CodecInfoMatchesCapabilityLhdcV5(const tA2DP_LHDCV5_CIE* p_cap,
                                                                      const uint8_t* p_codec_info,
                                                                      bool is_capability);

// check if target version is supported right now
static bool is_codec_version_supported(uint8_t version, bool is_source) {
  const tA2DP_LHDCV5_CIE* p_a2dp_lhdcv5_caps =
          (is_source) ? &a2dp_lhdcv5_source_caps : &a2dp_lhdcv5_sink_caps;

  if ((version & p_a2dp_lhdcv5_caps->version) != A2DP_LHDCV5_VER_NS) {
    return true;
  }

  log::debug("unsupported version! peer:{} local:{}", version, p_a2dp_lhdcv5_caps->version);
  return false;
}

// Builds the LHDC Media Codec Capabilities byte sequence beginning from the
// LOSC octet. |media_type| is the media type |AVDT_MEDIA_TYPE_*|.
// |p_ie| is a pointer to the LHDC Codec Information Element information.
// The result is stored in |p_result|. Returns A2DP_SUCCESS on success,
// otherwise the corresponding A2DP error status code.
static tA2DP_STATUS A2DP_BuildInfoLhdcV5(uint8_t media_type, const tA2DP_LHDCV5_CIE* p_ie,
                                         uint8_t* p_result) {
  const uint8_t* tmpInfo = p_result;
  uint8_t para = 0;

  if (p_ie == nullptr || p_result == nullptr) {
    log::error("nullptr input");
    return A2DP_INVALID_CODEC_PARAMETER;
  }

  *p_result++ = A2DP_LHDCV5_CODEC_LEN;   // H0
  *p_result++ = (media_type << 4);       // H1
  *p_result++ = A2DP_MEDIA_CT_NON_A2DP;  // H2

  // Vendor ID(P0-P3) and Codec ID(P4-P5)
  *p_result++ = (uint8_t)(p_ie->vendorId & 0x000000FF);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0x0000FF00) >> 8);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0x00FF0000) >> 16);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0xFF000000) >> 24);
  *p_result++ = (uint8_t)(p_ie->codecId & 0x00FF);
  *p_result++ = (uint8_t)((p_ie->codecId & 0xFF00) >> 8);

  para = 0;
  // P6[5:0] Sampling Frequency
  if ((p_ie->sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_MASK) != A2DP_LHDCV5_SAMPLING_FREQ_NS) {
    para |= (p_ie->sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_MASK);
  } else {
    log::error("invalid sample rate (0x{:02X})", p_ie->sampleRate);
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  // update P6
  *p_result++ = para;
  para = 0;

  // P7[2:0] Bit Depth
  if ((p_ie->bitsPerSample & A2DP_LHDCV5_BIT_FMT_MASK) != A2DP_LHDCV5_BIT_FMT_NS) {
    para |= (p_ie->bitsPerSample & A2DP_LHDCV5_BIT_FMT_MASK);
  } else {
    log::error("invalid bits per sample (0x{:02X})", p_ie->bitsPerSample);
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  // P7[5:4] Max Target Bit Rate
  para |= (p_ie->maxTargetBitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK);
  // P7[7:6] Min Target Bit Rate
  para |= (p_ie->minTargetBitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK);
  // update P7
  *p_result++ = para;
  para = 0;

  // P8[3:0] Codec SubVersion
  if ((p_ie->version & A2DP_LHDCV5_VERSION_MASK) != A2DP_LHDCV5_VER_NS) {
    para = para | (p_ie->version & A2DP_LHDCV5_VERSION_MASK);
  } else {
    log::error("invalid codec subversion (0x{:02X})", p_ie->version);
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  // P8[5:4] Frame Length Type
  if ((p_ie->frameLenType & A2DP_LHDCV5_FRAME_LEN_MASK) != A2DP_LHDCV5_FRAME_LEN_NS) {
    para = para | (p_ie->frameLenType & A2DP_LHDCV5_FRAME_LEN_MASK);
  } else {
    log::error("invalid frame length type (0x{:02X})", p_ie->frameLenType);
    return A2DP_INVALID_CODEC_PARAMETER;
  }
  // update P8
  *p_result++ = para;
  para = 0;

  // P9[6] HasLL
  if (p_ie->hasFeatureLL) {
    para |= A2DP_LHDCV5_FEATURE_LL;
  }
  // update P9
  *p_result++ = para;
  para = 0;

  // update P10
  *p_result++ = para;
  para = 0;

  log::debug(
          "codec info built = H0-H2[{:02X} {:02X} {:02X}] P0-P3[{:02X} {:02X} {:02X} {:02X}]"
          " P4-P5[{:02X} {:02X}] P6[{:02X}] P7[{:02X}] P8[{:02X}] P9[{:02X}] P10[{:02X}]",
          tmpInfo[0], tmpInfo[1], tmpInfo[2], tmpInfo[3], tmpInfo[4], tmpInfo[5], tmpInfo[6],
          tmpInfo[7], tmpInfo[8], tmpInfo[9], tmpInfo[10], tmpInfo[11], tmpInfo[12],
          tmpInfo[A2DP_LHDCV5_CODEC_LEN]);

  return A2DP_SUCCESS;
}

// Parses the LHDC Media Codec Capabilities byte sequence beginning from the
// LOSC octet. The result is stored in |p_ie|. The byte sequence to parse is
// |p_codec_info|. If |is_capability| is true, the byte sequence is
// codec capabilities, otherwise is codec configuration.
// Returns A2DP_SUCCESS on success, otherwise the corresponding A2DP error
// status code.
static tA2DP_STATUS A2DP_ParseInfoLhdcV5(tA2DP_LHDCV5_CIE* p_ie, const uint8_t* p_codec_info,
                                         bool is_capability) {
  uint8_t losc;
  uint8_t media_type;
  tA2DP_CODEC_TYPE codec_type;
  const uint8_t* tmpInfo = p_codec_info;

  if (p_ie == nullptr || p_codec_info == nullptr) {
    log::error("nullptr input");
    return A2DP_INVALID_CODEC_PARAMETER;
  }

  // Codec capability length
  losc = *p_codec_info++;
  if (losc != A2DP_LHDCV5_CODEC_LEN) {
    log::error("wrong length {}", losc);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  media_type = (*p_codec_info++) >> 4;
  codec_type = static_cast<tA2DP_CODEC_TYPE>(*p_codec_info++);

  // Media Type and Media Codec Type
  if (media_type != AVDT_MEDIA_TYPE_AUDIO || codec_type != A2DP_MEDIA_CT_NON_A2DP) {
    log::error("invalid media type 0x{:X} codec_type 0x{:X}", media_type, codec_type);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  // Vendor ID(P0-P3) and Codec ID(P4-P5)
  p_ie->vendorId = (*p_codec_info & 0x000000FF) | (*(p_codec_info + 1) << 8 & 0x0000FF00) |
                   (*(p_codec_info + 2) << 16 & 0x00FF0000) |
                   (*(p_codec_info + 3) << 24 & 0xFF000000);
  p_codec_info += 4;
  p_ie->codecId = (*p_codec_info & 0x00FF) | (*(p_codec_info + 1) << 8 & 0xFF00);
  p_codec_info += 2;
  if (p_ie->vendorId != A2DP_LHDC_VENDOR_ID || p_ie->codecId != A2DP_LHDCV5_CODEC_ID) {
    log::error("invalid vendorId 0x{:X} codecId 0x{:X}", p_ie->vendorId, p_ie->codecId);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  // P6[5:0] Sampling Frequency
  p_ie->sampleRate = (*p_codec_info & A2DP_LHDCV5_SAMPLING_FREQ_MASK);
  if (p_ie->sampleRate == A2DP_LHDCV5_SAMPLING_FREQ_NS) {
    log::error("invalid sample rate 0x{:X}", p_ie->sampleRate);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }
  p_codec_info += 1;

  // P7[2:0] Bits Per Sample
  p_ie->bitsPerSample = (*p_codec_info & A2DP_LHDCV5_BIT_FMT_MASK);
  if (p_ie->bitsPerSample == A2DP_LHDCV5_BIT_FMT_NS) {
    log::error("invalid bit per sample 0x{:X}", p_ie->bitsPerSample);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }
  // P7[5:4] Max Target Bit Rate
  p_ie->maxTargetBitrate = (*p_codec_info & A2DP_LHDCV5_MAX_BIT_RATE_MASK);
  // P7[7:6] Min Target Bit Rate
  p_ie->minTargetBitrate = (*p_codec_info & A2DP_LHDCV5_MIN_BIT_RATE_MASK);
  p_codec_info += 1;

  // Channel Mode: stereo only
  p_ie->channelMode = A2DP_LHDCV5_CHANNEL_MODE_STEREO;

  // P8[3:0] Codec SubVersion
  p_ie->version = (*p_codec_info & A2DP_LHDCV5_VERSION_MASK);
  if (p_ie->version == A2DP_LHDCV5_VER_NS) {
    log::error("invalid version 0x{:X}", p_ie->version);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }
  // P8[5:4] Frame Length Type
  p_ie->frameLenType = (*p_codec_info & A2DP_LHDCV5_FRAME_LEN_MASK);
  if (p_ie->frameLenType == A2DP_LHDCV5_FRAME_LEN_NS) {
    log::error("invalid frame length mode 0x{:X}", p_ie->frameLenType);
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }
  p_codec_info += 1;

  // Features:
  // P9[6] HasLL
  p_ie->hasFeatureLL = ((*p_codec_info & A2DP_LHDCV5_FEATURE_LL) != 0) ? true : false;
  p_codec_info += 1;

  log::debug(
          "codec info parsed = H0-H2[{:02X} {:02X} {:02X}] P0-P3[{:02X} "
          "{:02X} {:02X} {:02X}] P4-P5[{:02X} {:02X}] P6[{:02X}] P7[{:02X}] P8[{:02X}] P9[{:02X}] "
          "P10[{:02X}]",
          tmpInfo[0], tmpInfo[1], tmpInfo[2], tmpInfo[3], tmpInfo[4], tmpInfo[5], tmpInfo[6],
          tmpInfo[7], tmpInfo[8], tmpInfo[9], tmpInfo[10], tmpInfo[11], tmpInfo[12],
          tmpInfo[A2DP_LHDCV5_CODEC_LEN]);

  log::debug("isCap:{} SR:{:#x} BPS:{:#x} Ver:{:#x} FL:{:#x} MBR:{:#x} mBR:{:#x} LL:{}",
             is_capability, p_ie->sampleRate, p_ie->bitsPerSample, p_ie->version,
             p_ie->frameLenType, p_ie->maxTargetBitrate, p_ie->minTargetBitrate,
             p_ie->hasFeatureLL);

  return A2DP_SUCCESS;
}

bool A2DP_IsCodecValidLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE cfg_cie;
  /* Use a liberal check when parsing the codec info */
  return (A2DP_ParseInfoLhdcV5(&cfg_cie, p_codec_info, false) == A2DP_SUCCESS) ||
         (A2DP_ParseInfoLhdcV5(&cfg_cie, p_codec_info, true) == A2DP_SUCCESS);
}

// NOTE: Should be done only for local Sink codec
bool A2DP_IsVendorSinkCodecSupportedLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE cfg_cie;
  tA2DP_STATUS status = A2DP_ParseInfoLhdcV5(&cfg_cie, p_codec_info, false);
  if (status != A2DP_SUCCESS) {
    return false;
  }
  if (!is_codec_version_supported(cfg_cie.version, /*is_source*/ false)) {
    log::error("unsupported version 0x{:X}", cfg_cie.version);
    return false;
  }
  return A2DP_CodecInfoMatchesCapabilityLhdcV5(&a2dp_lhdcv5_sink_caps, p_codec_info, false) ==
         A2DP_SUCCESS;
}

// NOTE: Should be done only for local Sink codec
bool A2DP_IsPeerSourceCodecSupportedLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE cfg_cie;
  tA2DP_STATUS status = A2DP_ParseInfoLhdcV5(&cfg_cie, p_codec_info, false);
  if (status != A2DP_SUCCESS) {
    return false;
  }
  if (!is_codec_version_supported(cfg_cie.version, /*is_source*/ true)) {
    log::error("unsupported version 0x{:X}", cfg_cie.version);
    return false;
  }
  return A2DP_CodecInfoMatchesCapabilityLhdcV5(&a2dp_lhdcv5_sink_caps, p_codec_info, true) ==
         A2DP_SUCCESS;
}

// Checks whether A2DP LHDC codec configuration matches with a device's codec
// capabilities.
//  |p_cap| is the LHDC local codec capabilities.
//  |p_codec_info| is peer's codec capabilities acting as an A2DP source.
// If |is_capability| is true, the byte sequence is codec capabilities,
// otherwise is codec configuration.
// Returns A2DP_SUCCESS if the codec configuration matches with capabilities,
// otherwise the corresponding A2DP error status code.
static tA2DP_STATUS A2DP_CodecInfoMatchesCapabilityLhdcV5(const tA2DP_LHDCV5_CIE* p_cap,
                                                          const uint8_t* p_codec_info,
                                                          bool is_capability) {
  tA2DP_STATUS status;
  tA2DP_LHDCV5_CIE cfg_cie;

  if (p_cap == nullptr || p_codec_info == nullptr) {
    log::error("nullptr input");
    return A2DP_INVALID_CODEC_PARAMETER;
  }

  // parse configuration
  status = A2DP_ParseInfoLhdcV5(&cfg_cie, p_codec_info, is_capability);
  if (status != A2DP_SUCCESS) {
    log::error("parsing failed {}", status);
    return status;
  }

  // verify that each parameter is in range
  log::debug("FREQ peer: 0x{:x}, capability 0x{:x}", cfg_cie.sampleRate, p_cap->sampleRate);

  log::debug("BIT_FMT peer: 0x{:x}, capability 0x{:x}", cfg_cie.bitsPerSample,
             p_cap->bitsPerSample);

  // sampling frequency
  if ((cfg_cie.sampleRate & p_cap->sampleRate) == 0) {
    return A2DP_NOT_SUPPORTED_SAMPLING_FREQUENCY;
  }

  // bits per sample
  if ((cfg_cie.bitsPerSample & p_cap->bitsPerSample) == 0) {
    return A2DP_NOT_SUPPORTED_BIT_RATE;
  }

  return A2DP_SUCCESS;
}

bool A2DP_VendorUsesRtpHeaderLhdcV5(UNUSED_ATTR bool content_protection_enabled,
                                    UNUSED_ATTR const uint8_t* p_codec_info) {
  // TODO: Is this correct? The RTP header is always included?
  return true;
}

bool A2DP_VendorCodecTypeEqualsLhdcV5(const uint8_t* p_codec_info_a,
                                      const uint8_t* p_codec_info_b) {
  tA2DP_LHDCV5_CIE lhdc_cie_a;
  tA2DP_LHDCV5_CIE lhdc_cie_b;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info_a == nullptr || p_codec_info_b == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie_a, p_codec_info_a, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie_b, p_codec_info_b, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  return true;
}

bool A2DP_VendorCodecEqualsLhdcV5(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b) {
  tA2DP_LHDCV5_CIE lhdc_cie_a;
  tA2DP_LHDCV5_CIE lhdc_cie_b;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info_a == nullptr || p_codec_info_b == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie_a, p_codec_info_a, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information of a: {}", a2dp_status);
    return false;
  }
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie_b, p_codec_info_b, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information of b: {}", a2dp_status);
    return false;
  }

  // exam items that require to update codec config with peer if different
  return (lhdc_cie_a.sampleRate == lhdc_cie_b.sampleRate) &&
         (lhdc_cie_a.bitsPerSample == lhdc_cie_b.bitsPerSample) &&
         (lhdc_cie_a.channelMode == lhdc_cie_b.channelMode) &&
         (lhdc_cie_a.frameLenType == lhdc_cie_b.frameLenType) &&
         (lhdc_cie_a.hasFeatureLL == lhdc_cie_b.hasFeatureLL) &&
         (lhdc_cie_a.hasFeatureLLESS48K == lhdc_cie_b.hasFeatureLLESS48K);
}

int A2dpCodecConfigLhdcV5Base::getTrackBitRate() const {
  uint8_t bitRateIndex = 0;

  if ((codec_config_.codec_specific_1 & A2DP_LHDC_VENDOR_CMD_MASK) == A2DP_LHDC_QUALITY_MAGIC_NUM) {
    bitRateIndex = codec_config_.codec_specific_1 & A2DP_LHDC_QUALITY_MASK;
    switch (bitRateIndex) {
      case A2DP_LHDC_QUALITY_LOW0:
        return 64000;
      case A2DP_LHDC_QUALITY_LOW1:
        return 160000;
      case A2DP_LHDC_QUALITY_LOW2:
        return 192000;
      case A2DP_LHDC_QUALITY_LOW3:
        return 256000;
      case A2DP_LHDC_QUALITY_LOW4:
        return 320000;
      case A2DP_LHDC_QUALITY_LOW:
        return 400000;
      case A2DP_LHDC_QUALITY_MID:
        return 500000;
      case A2DP_LHDC_QUALITY_HIGH:
        return 900000;
      case A2DP_LHDC_QUALITY_HIGH1:
        return 1000000;
      case A2DP_LHDC_QUALITY_ABR:
        return 9999999;
      default:
        log::debug("non-supported bitrate index ({})", bitRateIndex);
        return -1;
    }
  }
  return 400000;
}

int A2DP_VendorGetTrackSampleRateLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return -1;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  switch (lhdc_cie.sampleRate) {
    case A2DP_LHDCV5_SAMPLING_FREQ_44100:
      return 44100;
    case A2DP_LHDCV5_SAMPLING_FREQ_48000:
      return 48000;
    case A2DP_LHDCV5_SAMPLING_FREQ_96000:
      return 96000;
    case A2DP_LHDCV5_SAMPLING_FREQ_192000:
      return 192000;
  }

  return -1;
}

int A2DP_VendorGetTrackBitsPerSampleLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return -1;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  switch (lhdc_cie.bitsPerSample) {
    case A2DP_LHDCV5_BIT_FMT_16:
      return 16;
    case A2DP_LHDCV5_BIT_FMT_24:
      return 24;
    case A2DP_LHDCV5_BIT_FMT_32:
      return 32;
  }

  return -1;
}

int A2DP_VendorGetTrackChannelCountLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return -1;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  switch (lhdc_cie.channelMode) {
    case A2DP_LHDCV5_CHANNEL_MODE_MONO:
      return 1;
    case A2DP_LHDCV5_CHANNEL_MODE_DUAL:
      return 2;
    case A2DP_LHDCV5_CHANNEL_MODE_STEREO:
      return 2;
  }

  return -1;
}

int A2DP_VendorGetSinkTrackChannelTypeLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return -1;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  switch (lhdc_cie.channelMode) {
    case A2DP_LHDCV5_CHANNEL_MODE_MONO:
      return 1;
    case A2DP_LHDCV5_CHANNEL_MODE_DUAL:
      return 3;
    case A2DP_LHDCV5_CHANNEL_MODE_STEREO:
      return 3;
  }

  return -1;
}

int A2DP_VendorGetChannelModeCodeLhdcV5(const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return -1;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  switch (lhdc_cie.channelMode) {
    case A2DP_LHDCV5_CHANNEL_MODE_MONO:
    case A2DP_LHDCV5_CHANNEL_MODE_DUAL:
    case A2DP_LHDCV5_CHANNEL_MODE_STEREO:
      return lhdc_cie.channelMode;
    default:
      break;
  }

  return -1;
}

bool A2DP_VendorGetPacketTimestampLhdcV5(UNUSED_ATTR const uint8_t* p_codec_info,
                                         const uint8_t* p_data, uint32_t* p_timestamp) {
  if (p_codec_info == nullptr || p_data == nullptr || p_timestamp == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // TODO: Is this function really codec-specific?
  *p_timestamp = *(const uint32_t*)p_data;
  return true;
}

bool A2DP_VendorBuildCodecHeaderLhdcV5(UNUSED_ATTR const uint8_t* p_codec_info, BT_HDR* p_buf,
                                       uint16_t frames_per_packet) {
  uint8_t* p;

  if (p_codec_info == nullptr || p_buf == nullptr) {
    log::error("nullptr input");
    return false;
  }

  p_buf->offset -= A2DP_LHDC_MPL_HDR_LEN;
  p = (uint8_t*)(p_buf + 1) + p_buf->offset;
  p_buf->len += A2DP_LHDC_MPL_HDR_LEN;

  // Not support fragmentation
  p[0] = (uint8_t)(frames_per_packet & 0xff);
  p[1] = (uint8_t)((frames_per_packet >> 8) & 0xff);

  return true;
}

std::string A2DP_VendorCodecInfoStringLhdcV5(const uint8_t* p_codec_info) {
  std::stringstream res;
  std::string field;
  tA2DP_STATUS a2dp_status;
  tA2DP_LHDCV5_CIE lhdc_cie;

  if (p_codec_info == nullptr) {
    res << "A2DP_VendorCodecInfoStringLhdcV5 nullptr";
    return res.str();
  }

  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, true);
  if (a2dp_status != A2DP_SUCCESS) {
    res << std::format("A2DP_ParseInfoLhdcV5 fail: 0x{:x}", a2dp_status);
    return res.str();
  }

  res << "\tname: LHDC V5\n";

  // Sample frequency
  field.clear();
  AppendField(&field, lhdc_cie.sampleRate == A2DP_LHDCV5_SAMPLING_FREQ_NS, "NONE");
  AppendField(&field, lhdc_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100, "44100");
  AppendField(&field, lhdc_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000, "48000");
  AppendField(&field, lhdc_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000, "96000");
  AppendField(&field, lhdc_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000, "192000");
  res << std::format("\tsamp_freq: {} (0x{:x})\n", field, lhdc_cie.sampleRate);

  // bits per sample
  field.clear();
  AppendField(&field, lhdc_cie.bitsPerSample == A2DP_LHDCV5_BIT_FMT_NS, "NONE");
  AppendField(&field, lhdc_cie.bitsPerSample & A2DP_LHDCV5_BIT_FMT_16, "16");
  AppendField(&field, lhdc_cie.bitsPerSample & A2DP_LHDCV5_BIT_FMT_24, "24");
  AppendField(&field, lhdc_cie.bitsPerSample & A2DP_LHDCV5_BIT_FMT_32, "24");
  res << std::format("\tbits_depth: {} bits (0x{:x})\n", field, lhdc_cie.bitsPerSample);

  // Channel mode
  field.clear();
  AppendField(&field, lhdc_cie.channelMode == A2DP_LHDCV5_CHANNEL_MODE_NS, "NONE");
  AppendField(&field, lhdc_cie.channelMode & A2DP_LHDCV5_CHANNEL_MODE_MONO, "Mono");
  AppendField(&field, lhdc_cie.channelMode & A2DP_LHDCV5_CHANNEL_MODE_DUAL, "Dual");
  AppendField(&field, lhdc_cie.channelMode & A2DP_LHDCV5_CHANNEL_MODE_STEREO, "Stereo");
  res << std::format("\tch_mode: {} (0x{:x})\n", field, lhdc_cie.channelMode);

  // Version
  field.clear();
  AppendField(&field, lhdc_cie.version == A2DP_LHDCV5_VER_NS, "NONE");
  AppendField(&field, lhdc_cie.version == A2DP_LHDCV5_VER_1, "LHDC V5 Ver1");
  res << std::format("\tversion: {} (0x{:x})\n", field, lhdc_cie.version);

  // Max target bit rate...
  field.clear();
  AppendField(&field,
              (lhdc_cie.maxTargetBitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MAX_BIT_RATE_1000K,
              "1000Kbps");
  AppendField(&field,
              (lhdc_cie.maxTargetBitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MAX_BIT_RATE_900K,
              "900Kbps");
  AppendField(&field,
              (lhdc_cie.maxTargetBitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MAX_BIT_RATE_500K,
              "500Kbps");
  AppendField(&field,
              (lhdc_cie.maxTargetBitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MAX_BIT_RATE_400K,
              "400Kbps");
  res << std::format("\tMax target-rate: {} (0x{:x})\n", field,
                     lhdc_cie.maxTargetBitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK);

  // Min target bit rate...
  field.clear();
  AppendField(&field,
              (lhdc_cie.minTargetBitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MIN_BIT_RATE_400K,
              "400Kbps");
  AppendField(&field,
              (lhdc_cie.minTargetBitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MIN_BIT_RATE_256K,
              "256Kbps");
  AppendField(&field,
              (lhdc_cie.minTargetBitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MIN_BIT_RATE_160K,
              "160Kbps");
  AppendField(&field,
              (lhdc_cie.minTargetBitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK) ==
                      A2DP_LHDCV5_MIN_BIT_RATE_64K,
              "64Kbps");
  res << std::format("\tMin target-rate: {} (0x{:x})\n", field,
                     lhdc_cie.minTargetBitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK);

  return res.str();
}

const tA2DP_ENCODER_INTERFACE* A2DP_VendorGetEncoderInterfaceLhdcV5(const uint8_t* p_codec_info) {
  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return NULL;
  }

  if (!A2DP_IsCodecValidLhdcV5(p_codec_info)) {
    return NULL;
  }

  return &a2dp_encoder_interface_lhdcv5;
}

bool A2DP_VendorAdjustCodecLhdcV5(uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE cfg_cie;
  if (p_codec_info == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Nothing to do: just verify the codec info is valid
  if (A2DP_ParseInfoLhdcV5(&cfg_cie, p_codec_info, true) != A2DP_SUCCESS) {
    return false;
  }

  return true;
}

bool A2DP_VendorInitCodecConfigLhdcV5(AvdtpSepConfig* p_cfg) {
  if (p_cfg == nullptr) {
    log::error("nullptr input");
    return false;
  }

  if (A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &a2dp_lhdcv5_source_caps, p_cfg->codec_info) !=
      A2DP_SUCCESS) {
    return false;
  }

#if (BTA_AV_CO_CP_SCMS_T == TRUE)
  /* Content protection info - support SCMS-T */
  uint8_t* p = p_cfg->protect_info;
  *p++ = AVDT_CP_LOSC;
  UINT16_TO_STREAM(p, AVDT_CP_SCMS_T_ID);
  p_cfg->num_protect = 1;
#endif

  return true;
}

bool A2DP_VendorInitCodecConfigLhdcV5Sink(AvdtpSepConfig* p_cfg) {
  if (p_cfg == nullptr) {
    log::error("nullptr input");
    return false;
  }

  if (A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &a2dp_lhdcv5_sink_caps, p_cfg->codec_info) !=
      A2DP_SUCCESS) {
    return false;
  }

  return true;
}

UNUSED_ATTR static void build_codec_config(const tA2DP_LHDCV5_CIE& config_cie,
                                           btav_a2dp_codec_config_t* result) {
  if (result == nullptr) {
    log::error("nullptr input");
    return;
  }

  // sample rate
  result->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  if (config_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
    result->sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
  }
  if (config_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
    result->sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
  }
  if (config_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
    result->sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
  }
  if (config_cie.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
    result->sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
  }

  // bits per sample
  result->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  if (config_cie.bitsPerSample & A2DP_LHDCV5_BIT_FMT_16) {
    result->bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
  }
  if (config_cie.bitsPerSample & A2DP_LHDCV5_BIT_FMT_24) {
    result->bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
  }
  if (config_cie.bitsPerSample & A2DP_LHDCV5_BIT_FMT_32) {
    result->bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
  }

  // channel mode
  result->channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_NONE;
  if (config_cie.channelMode & A2DP_LHDCV5_CHANNEL_MODE_MONO) {
    result->channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
  }
  if (config_cie.channelMode & (A2DP_LHDCV5_CHANNEL_MODE_DUAL | A2DP_LHDCV5_CHANNEL_MODE_STEREO)) {
    result->channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  }
}

A2dpCodecConfigLhdcV5Source::A2dpCodecConfigLhdcV5Source(btav_a2dp_codec_priority_t codec_priority)
    : A2dpCodecConfigLhdcV5Base(BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV5, "LHDCv5", codec_priority,
                                true) {
  // Compute the local capability
  codec_local_capability_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  if (a2dp_lhdcv5_source_caps.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
    codec_local_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
  }
  if (a2dp_lhdcv5_source_caps.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
    codec_local_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
  }
  if (a2dp_lhdcv5_source_caps.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
    codec_local_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
  }
  if (a2dp_lhdcv5_source_caps.sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
    codec_local_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
  }

  codec_local_capability_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  if (a2dp_lhdcv5_source_caps.bitsPerSample & A2DP_LHDCV5_BIT_FMT_16) {
    codec_local_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
  }
  if (a2dp_lhdcv5_source_caps.bitsPerSample & A2DP_LHDCV5_BIT_FMT_24) {
    codec_local_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
  }
  if (a2dp_lhdcv5_source_caps.bitsPerSample & A2DP_LHDCV5_BIT_FMT_32) {
    codec_local_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
  }

  codec_local_capability_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_NONE;
  if (a2dp_lhdcv5_source_caps.channelMode & A2DP_LHDCV5_CHANNEL_MODE_MONO) {
    codec_local_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
  }
  if (a2dp_lhdcv5_source_caps.channelMode & A2DP_LHDCV5_CHANNEL_MODE_DUAL) {
    codec_local_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  }
  if (a2dp_lhdcv5_source_caps.channelMode & A2DP_LHDCV5_CHANNEL_MODE_STEREO) {
    codec_local_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  }
}

A2dpCodecConfigLhdcV5Source::~A2dpCodecConfigLhdcV5Source() {}

bool A2dpCodecConfigLhdcV5Source::init() {
  // Load the encoder
  if (!A2DP_VendorLoadEncoderLhdcV5()) {
    log::error("cannot load the encoder");
    return false;
  }

  return true;
}

bool A2dpCodecConfigLhdcV5Source::useRtpHeaderMarkerBit() const { return false; }

//
// Selects the best sample rate from |sampleRate|.
// The result is stored in |p_result| and |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_best_sample_rate(uint8_t sampleRate, tA2DP_LHDCV5_CIE* p_result,
                                    btav_a2dp_codec_config_t* p_codec_config) {
  if (p_codec_config == nullptr || p_result == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // LHDC V5 priority: 48K > 44.1K > 96K > 192K > others(min to max)
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
    p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_48000;
    p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
    return true;
  }
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
    p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_44100;
    p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
    return true;
  }
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
    p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_96000;
    p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
    return true;
  }
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
    p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_192000;
    p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
    return true;
  }
  return false;
}

//
// Selects the audio sample rate from |p_codec_audio_config|.
// |sampleRate| contains the capability.
// The result is stored in |p_result| and |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_audio_sample_rate(const btav_a2dp_codec_config_t* p_codec_audio_config,
                                     uint8_t sampleRate, tA2DP_LHDCV5_CIE* p_result,
                                     btav_a2dp_codec_config_t* p_codec_config) {
  if (p_codec_audio_config == nullptr || p_result == nullptr || p_codec_config == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // LHDC V5 priority: 48K > 44.1K > 96K > 192K > others(min to max)
  switch (p_codec_audio_config->sample_rate) {
    case BTAV_A2DP_CODEC_SAMPLE_RATE_48000:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
        p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_48000;
        p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_44100:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
        p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_44100;
        p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_96000:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
        p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_96000;
        p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_192000:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
        p_result->sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_192000;
        p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_16000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_24000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_176400:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_88200:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_NONE:
      break;
  }
  return false;
}

//
// Selects the best bits per sample from |bitsPerSample|.
// |bitsPerSample| contains the capability.
// The result is stored in |p_result| and |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_best_bits_per_sample(uint8_t bitsPerSample, tA2DP_LHDCV5_CIE* p_result,
                                        btav_a2dp_codec_config_t* p_codec_config) {
  if (p_result == nullptr || p_codec_config == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // LHDC V5 priority: 24 > 16 > 32
  if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_24) {
    p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
    p_result->bitsPerSample = A2DP_LHDCV5_BIT_FMT_24;
    return true;
  }
  if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_16) {
    p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
    p_result->bitsPerSample = A2DP_LHDCV5_BIT_FMT_16;
    return true;
  }
  if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_32) {
    p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
    p_result->bitsPerSample = A2DP_LHDCV5_BIT_FMT_32;
    return true;
  }
  return false;
}

//
// Selects the audio bits per sample from |p_codec_audio_config|.
// |bitsPerSample| contains the capability.
// The result is stored in |p_result| and |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_audio_bits_per_sample(const btav_a2dp_codec_config_t* p_codec_audio_config,
                                         uint8_t bitsPerSample, tA2DP_LHDCV5_CIE* p_result,
                                         btav_a2dp_codec_config_t* p_codec_config) {
  if (p_codec_audio_config == nullptr || p_result == nullptr || p_codec_config == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // LHDC V5 priority: 24 > 16 > 32
  switch (p_codec_audio_config->bits_per_sample) {
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24:
      if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_24) {
        p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
        p_result->bitsPerSample = A2DP_LHDCV5_BIT_FMT_24;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16:
      if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_16) {
        p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
        p_result->bitsPerSample = A2DP_LHDCV5_BIT_FMT_16;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32:
      if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_32) {
        p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
        p_result->bitsPerSample = A2DP_LHDCV5_BIT_FMT_32;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE:
      break;
  }
  return false;
}

static bool A2DP_MaxBitRatetoQualityLevelLhdcV5(uint8_t* mode, uint8_t bitrate) {
  if (mode == nullptr) {
    log::error("nullptr input");
    return false;
  }

  switch (bitrate & A2DP_LHDCV5_MAX_BIT_RATE_MASK) {
    case A2DP_LHDCV5_MAX_BIT_RATE_1000K:
      *mode = A2DP_LHDC_QUALITY_HIGH1;
      return true;
    case A2DP_LHDCV5_MAX_BIT_RATE_900K:
      *mode = A2DP_LHDC_QUALITY_HIGH;
      return true;
    case A2DP_LHDCV5_MAX_BIT_RATE_500K:
      *mode = A2DP_LHDC_QUALITY_MID;
      return true;
    case A2DP_LHDCV5_MAX_BIT_RATE_400K:
      *mode = A2DP_LHDC_QUALITY_LOW;
      return true;
  }
  return false;
}

static bool A2DP_MinBitRatetoQualityLevelLhdcV5(uint8_t* mode, uint8_t bitrate) {
  if (mode == nullptr) {
    log::error("nullptr input");
    return false;
  }

  switch (bitrate & A2DP_LHDCV5_MIN_BIT_RATE_MASK) {
    case A2DP_LHDCV5_MIN_BIT_RATE_400K:
      *mode = A2DP_LHDC_QUALITY_LOW;
      return true;
    case A2DP_LHDCV5_MIN_BIT_RATE_256K:
      *mode = A2DP_LHDC_QUALITY_LOW3;
      return true;
    case A2DP_LHDCV5_MIN_BIT_RATE_160K:
      *mode = A2DP_LHDC_QUALITY_LOW1;
      return true;
    case A2DP_LHDCV5_MIN_BIT_RATE_64K:
      *mode = A2DP_LHDC_QUALITY_LOW0;
      return true;
  }
  return false;
}

static std::string lhdcV5_sampleRate_toString(uint8_t value) {
  switch ((int)value) {
    case A2DP_LHDCV5_SAMPLING_FREQ_44100:
      return "44.1 KHz";
    case A2DP_LHDCV5_SAMPLING_FREQ_48000:
      return "48 KHz";
    case A2DP_LHDCV5_SAMPLING_FREQ_96000:
      return "96 KHz";
    case A2DP_LHDCV5_SAMPLING_FREQ_192000:
      return "192 KHz";
    default:
      return "Unknown Sample Rate";
  }
}

static std::string lhdcV5_bitPerSample_toString(uint8_t value) {
  switch ((int)value) {
    case A2DP_LHDCV5_BIT_FMT_16:
      return "16";
    case A2DP_LHDCV5_BIT_FMT_24:
      return "24";
    case A2DP_LHDCV5_BIT_FMT_32:
      return "32";
    default:
      return "Unknown Bit Per Sample";
  }
}

static std::string lhdcV5_frameLenType_toString(uint8_t value) {
  switch ((int)value) {
    case A2DP_LHDCV5_FRAME_LEN_5MS:
      return "5ms";
    default:
      return "Unknown frame length type";
  }
}

static std::string lhdcV5_MaxTargetBitRate_toString(uint8_t value) {
  switch ((int)value) {
    case A2DP_LHDCV5_MAX_BIT_RATE_900K:
      return "900Kbps";
    case A2DP_LHDCV5_MAX_BIT_RATE_500K:
      return "500Kbps";
    case A2DP_LHDCV5_MAX_BIT_RATE_400K:
      return "400Kbps";
    case A2DP_LHDCV5_MAX_BIT_RATE_1000K:
      return "1000Kbps";
    default:
      return "Unknown Max Bit Rate";
  }
}

static std::string lhdcV5_MinTargetBitRate_toString(uint8_t value) {
  switch ((int)value) {
    case A2DP_LHDCV5_MIN_BIT_RATE_400K:
      return "400Kbps";
    case A2DP_LHDCV5_MIN_BIT_RATE_256K:
      return "256Kbps";
    case A2DP_LHDCV5_MIN_BIT_RATE_160K:
      return "160Kbps";
    case A2DP_LHDCV5_MIN_BIT_RATE_64K:
      return "64Kbps";
    default:
      return "Unknown Min Bit Rate";
  }
}

static std::string lhdcV5_QualityModeBitRate_toString(uint8_t value) {
  switch ((int)value) {
    case A2DP_LHDC_QUALITY_ABR:
      return "ABR";
    case A2DP_LHDC_QUALITY_HIGH1:
      return "HIGH 1 (1000 Kbps)";
    case A2DP_LHDC_QUALITY_HIGH:
      return "HIGH (900 Kbps)";
    case A2DP_LHDC_QUALITY_MID:
      return "MID (500 Kbps)";
    case A2DP_LHDC_QUALITY_LOW:
      return "LOW (400 Kbps)";
    case A2DP_LHDC_QUALITY_LOW4:
      return "LOW 4 (320 Kbps)";
    case A2DP_LHDC_QUALITY_LOW3:
      return "LOW 3 (256 Kbps)";
    case A2DP_LHDC_QUALITY_LOW2:
      return "LOW 2 (192 Kbps)";
    case A2DP_LHDC_QUALITY_LOW1:
      return "LOW 1 (160 Kbps)";
    case A2DP_LHDC_QUALITY_LOW0:
      return "LOW 0 (64 Kbps)";
    default:
      return "Unknown Bit Rate Mode";
  }
}

tA2DP_STATUS A2dpCodecConfigLhdcV5Base::setCodecConfig(const uint8_t* p_peer_codec_info,
                                                       bool is_capability,
                                                       uint8_t* p_result_codec_config) {
  std::lock_guard<std::recursive_mutex> lock(codec_mutex_);
  tA2DP_LHDCV5_CIE sink_info_cie;
  tA2DP_LHDCV5_CIE result_config_cie;
  uint8_t sampleRate = 0;
  uint8_t bitsPerSample = 0;
  bool hasFeature = false;
  bool hasUserSet = false;
  uint8_t qualityMode = 0;
  uint8_t bitRateQmode = 0;

  const tA2DP_LHDCV5_CIE* p_a2dp_lhdcv5_caps =
          (is_source_) ? &a2dp_lhdcv5_source_caps : &a2dp_lhdcv5_sink_caps;

  // Save the internal state
  btav_a2dp_codec_config_t saved_codec_config = codec_config_;
  btav_a2dp_codec_config_t saved_codec_selectable_capability = codec_selectable_capability_;
  btav_a2dp_codec_config_t saved_codec_user_config = codec_user_config_;
  btav_a2dp_codec_config_t saved_codec_audio_config = codec_audio_config_;
  bluetooth::a2dp::MediaCodecCapabilities saved_ota_codec_config = ota_codec_config_;
  bluetooth::a2dp::MediaCodecCapabilities saved_ota_codec_peer_capability =
          ota_codec_peer_capability_;
  bluetooth::a2dp::MediaCodecCapabilities saved_ota_codec_peer_config = ota_codec_peer_config_;

  tA2DP_CODEC_CONFIGS_PACK allCfgPack;
  allCfgPack._codec_config_ = &codec_config_;
  allCfgPack._codec_local_capability_ = &codec_local_capability_;
  allCfgPack._codec_selectable_capability_ = &codec_selectable_capability_;
  allCfgPack._codec_user_config_ = &codec_user_config_;
  allCfgPack._codec_audio_config_ = &codec_audio_config_;

  tA2DP_STATUS status = A2DP_ParseInfoLhdcV5(&sink_info_cie, p_peer_codec_info, is_capability);
  if (status != A2DP_SUCCESS) {
    log::error("can't parse peer's Sink capabilities: error = {}", status);
    goto fail;
  }

  //
  // Build the preferred configuration
  //
  memset(&result_config_cie, 0, sizeof(result_config_cie));
  result_config_cie.vendorId = p_a2dp_lhdcv5_caps->vendorId;
  result_config_cie.codecId = p_a2dp_lhdcv5_caps->codecId;
  result_config_cie.version = sink_info_cie.version;

  //
  // Select the sample frequency
  //
  sampleRate = p_a2dp_lhdcv5_caps->sampleRate & sink_info_cie.sampleRate;
  log::debug("sampleRate:[peer:0x{:02X} local:0x{:02X} cap:0x{:02X} user:0x{:02X}]",
             sink_info_cie.sampleRate, p_a2dp_lhdcv5_caps->sampleRate, sampleRate,
             codec_user_config_.sample_rate);

  codec_config_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  switch (codec_user_config_.sample_rate) {
    case BTAV_A2DP_CODEC_SAMPLE_RATE_44100:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
        result_config_cie.sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_44100;
        codec_config_.sample_rate = codec_user_config_.sample_rate;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_48000:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
        result_config_cie.sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_48000;
        codec_config_.sample_rate = codec_user_config_.sample_rate;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_96000:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
        result_config_cie.sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_96000;
        codec_config_.sample_rate = codec_user_config_.sample_rate;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_192000:
      if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
        result_config_cie.sampleRate = A2DP_LHDCV5_SAMPLING_FREQ_192000;
        codec_config_.sample_rate = codec_user_config_.sample_rate;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_16000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_24000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_88200:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_176400:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_NONE:
      codec_config_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
      break;
  }

  // Select the sample frequency if there is no user preference
  do {
    // Compute the selectable capability
    if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
      codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
    }
    if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
      codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
    }
    if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
      codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
    }
    if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
      codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
    }

    if (codec_config_.sample_rate != BTAV_A2DP_CODEC_SAMPLE_RATE_NONE) {
      log::debug("sample rate configured by UI successfully 0x{:02X}",
                 result_config_cie.sampleRate);
      break;
    }
    // Ignore follows if codec config is setup, otherwise pick a best one from default rules

    // No user preference - try the codec audio config
    if (select_audio_sample_rate(&codec_audio_config_, sampleRate, &result_config_cie,
                                 &codec_config_)) {
      log::debug("select sample rate from audio: 0x{:02X}", result_config_cie.sampleRate);
      break;
    }

    // No user preference - try the default config
    if (select_best_sample_rate(
                a2dp_lhdcv5_source_default_caps.sampleRate & sink_info_cie.sampleRate,
                &result_config_cie, &codec_config_)) {
      log::debug("select sample rate from default: 0x{:02X}", result_config_cie.sampleRate);
      break;
    }

    // No user preference - use the best match
    if (select_best_sample_rate(sampleRate, &result_config_cie, &codec_config_)) {
      log::debug("select sample rate from best match: 0x{:02X}", result_config_cie.sampleRate);
      break;
    }
  } while (false);

  if (codec_config_.sample_rate == BTAV_A2DP_CODEC_SAMPLE_RATE_NONE) {
    log::error(
            "cannot match sample frequency: local caps = 0x{:02X} "
            "peer info = 0x{:02X}",
            p_a2dp_lhdcv5_caps->sampleRate, sink_info_cie.sampleRate);
    status = A2DP_NOT_SUPPORTED_SAMPLING_FREQUENCY;
    goto fail;
  }
  codec_user_config_.sample_rate = codec_config_.sample_rate;
  log::debug("=> sample rate(0x{:02X}) = {}", result_config_cie.sampleRate,
             lhdcV5_sampleRate_toString(result_config_cie.sampleRate));

  //
  // Select the bits per sample
  //
  bitsPerSample = p_a2dp_lhdcv5_caps->bitsPerSample & sink_info_cie.bitsPerSample;
  log::debug("bitsPerSample:[peer:0x{:02X} local:0x{:02X} cap:0x{:02X} user:0x{:02X}]",
             sink_info_cie.bitsPerSample, p_a2dp_lhdcv5_caps->bitsPerSample, bitsPerSample,
             codec_user_config_.bits_per_sample);

  codec_config_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  switch (codec_user_config_.bits_per_sample) {
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16:
      if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_16) {
        result_config_cie.bitsPerSample = A2DP_LHDCV5_BIT_FMT_16;
        codec_config_.bits_per_sample = codec_user_config_.bits_per_sample;
      }
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24:
      if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_24) {
        result_config_cie.bitsPerSample = A2DP_LHDCV5_BIT_FMT_24;
        codec_config_.bits_per_sample = codec_user_config_.bits_per_sample;
      }
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32:
      if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_32) {
        result_config_cie.bitsPerSample = A2DP_LHDCV5_BIT_FMT_32;
        codec_config_.bits_per_sample = codec_user_config_.bits_per_sample;
      }
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE:
      result_config_cie.bitsPerSample = A2DP_LHDCV5_BIT_FMT_NS;
      codec_config_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
      break;
  }

  // Select the bits per sample if there is no user preference
  do {
    // Compute the selectable capability
    if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_16) {
      codec_selectable_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
    }
    if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_24) {
      codec_selectable_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
    }
    if (bitsPerSample & A2DP_LHDCV5_BIT_FMT_32) {
      codec_selectable_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
    }

    if (codec_config_.bits_per_sample != BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE) {
      log::debug("bit_per_sample configured by UI successfully 0x{:02X}",
                 result_config_cie.bitsPerSample);
      break;
    }
    // Ignore follows if codec config is setup, otherwise pick a best one from default rules

    // No user preference - try the codec audio config
    if (select_audio_bits_per_sample(&codec_audio_config_, bitsPerSample, &result_config_cie,
                                     &codec_config_)) {
      log::debug("select bit per sample from audio: 0x{:02X}", result_config_cie.bitsPerSample);
      break;
    }

    // No user preference - try the default config
    if (select_best_bits_per_sample(
                a2dp_lhdcv5_source_default_caps.bitsPerSample & sink_info_cie.bitsPerSample,
                &result_config_cie, &codec_config_)) {
      log::debug("select bit per sample from default: 0x{:02X}", result_config_cie.bitsPerSample);
      break;
    }

    // No user preference - use the best match
    if (select_best_bits_per_sample(bitsPerSample, &result_config_cie, &codec_config_)) {
      log::debug("select sample rate from best match: 0x{:02X}", result_config_cie.bitsPerSample);
      break;
    }
  } while (false);

  if (codec_config_.bits_per_sample == BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE) {
    log::error(
            "cannot match bits per sample: local caps = 0x{:02X} "
            "peer info = 0x{:02X}",
            p_a2dp_lhdcv5_caps->bitsPerSample, sink_info_cie.bitsPerSample);
    status = A2DP_NOT_SUPPORTED_BIT_RATE;
    goto fail;
  }
  codec_user_config_.bits_per_sample = codec_config_.bits_per_sample;
  log::debug("=> bit per sample(0x{:02X}) = {}", result_config_cie.bitsPerSample,
             lhdcV5_bitPerSample_toString(result_config_cie.bitsPerSample));

  // Select the channel mode
  codec_user_config_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  codec_selectable_capability_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  codec_config_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  log::debug("channelMode = Only supported stereo");

  // Update frameLenType
  result_config_cie.frameLenType = sink_info_cie.frameLenType;
  log::debug("=> frame length type(0x{:02X}) = {}", result_config_cie.frameLenType,
             lhdcV5_frameLenType_toString(result_config_cie.frameLenType));

  // Update maxTargetBitrate
  result_config_cie.maxTargetBitrate = sink_info_cie.maxTargetBitrate;
  log::debug("=> peer Max Bit Rate(0x{:02X}) = {}", result_config_cie.maxTargetBitrate,
             lhdcV5_MaxTargetBitRate_toString(result_config_cie.maxTargetBitrate));

  // Update minTargetBitrate
  result_config_cie.minTargetBitrate = sink_info_cie.minTargetBitrate;
  log::debug("=> peer Min Bit Rate(0x{:02X}) = {}", result_config_cie.minTargetBitrate,
             lhdcV5_MinTargetBitRate_toString(result_config_cie.minTargetBitrate));

  //
  // Update Feature/Capabilities to A2DP specifics
  //
  /*******************************************
   * for features that can be enabled by user-control, exam features tag on the specific.
   * current user-control enabling features:
   *******************************************/
  // features on specific 3
  if ((codec_user_config_.codec_specific_3 & A2DP_LHDC_VENDOR_FEATURE_MASK) !=
      A2DP_LHDCV5_FEATURE_MAGIC_NUM) {
    // reset the specific and apply tag
    codec_user_config_.codec_specific_3 = A2DP_LHDCV5_FEATURE_MAGIC_NUM;

    // get previous status of user-control enabling features from codec_config, then restore to user
    // settings
    //
    // Feature: LL
    hasUserSet = A2DP_IsFeatureInCodecConfigLhdcV5(&allCfgPack, LHDCV5_FEATURE_CODE_LL);
    A2DP_UpdateFeatureToA2dpConfigLhdcV5(&allCfgPack, LHDCV5_FEATURE_CODE_LL,
                                         A2DP_LHDC_TO_A2DP_CODEC_USER_, hasUserSet);
    log::debug("LHDC features tag check fail, default UI status[LL] => {}", hasUserSet);
  }

  /*************************************************
   *  quality mode initialize
   *************************************************/
  if ((codec_user_config_.codec_specific_1 & A2DP_LHDC_VENDOR_CMD_MASK) !=
      A2DP_LHDC_QUALITY_MAGIC_NUM) {
    codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
    codec_user_config_.codec_specific_1 |= (A2DP_LHDC_QUALITY_MAGIC_NUM | A2DP_LHDC_QUALITY_ABR);
    log::debug("tag not match, use default Quality Mode: ABR");
  }
  qualityMode = (uint8_t)codec_user_config_.codec_specific_1 & A2DP_LHDC_QUALITY_MASK;

  /*******************************************
   *  Low Latency: user-control enabling
   *******************************************/
  {
    hasFeature = (p_a2dp_lhdcv5_caps->hasFeatureLL & sink_info_cie.hasFeatureLL);
    // reset first
    result_config_cie.hasFeatureLL = false;
    hasUserSet = A2DP_IsFeatureInUserConfigLhdcV5(&allCfgPack, LHDCV5_FEATURE_CODE_LL);

    A2DP_UpdateFeatureToA2dpConfigLhdcV5(
            &allCfgPack, LHDCV5_FEATURE_CODE_LL,
            A2DP_LHDC_TO_A2DP_CODEC_CONFIG_ | A2DP_LHDC_TO_A2DP_CODEC_CAP_ |
                    A2DP_LHDC_TO_A2DP_CODEC_SELECT_CAP_ | A2DP_LHDC_TO_A2DP_CODEC_USER_,
            false);
    // update
    if (hasFeature && hasUserSet) {
      result_config_cie.hasFeatureLL = true;
      A2DP_UpdateFeatureToA2dpConfigLhdcV5(&allCfgPack, LHDCV5_FEATURE_CODE_LL,
                                           A2DP_LHDC_TO_A2DP_CODEC_CAP_ |
                                                   A2DP_LHDC_TO_A2DP_CODEC_SELECT_CAP_ |
                                                   A2DP_LHDC_TO_A2DP_CODEC_USER_,
                                           true);
    }
    log::debug("featureLL: enabled? <{}> [Peer:0x{:02X} Local:0x{:02X} User:{}]",
               result_config_cie.hasFeatureLL ? "Y" : "N", sink_info_cie.hasFeatureLL,
               p_a2dp_lhdcv5_caps->hasFeatureLL, hasUserSet ? "Y" : "N");
  }

  log::debug("current quality_mode = 0x{:X}", qualityMode);

  /*******************************************
   * Update LHDC Peer Max Bitrate Index to specific 1
   *******************************************/
  // store peer cap: max target bitrate index for UI reference
  codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_PEER_MAX_BITRATE_MASK);
  switch (result_config_cie.maxTargetBitrate) {
    case A2DP_LHDCV5_MAX_BIT_RATE_400K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MAX_BIT_RATE_400K << 4);
      break;
    }
    case A2DP_LHDCV5_MAX_BIT_RATE_500K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MAX_BIT_RATE_500K << 4);
      break;
    }
    case A2DP_LHDCV5_MAX_BIT_RATE_900K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MAX_BIT_RATE_900K << 4);
      break;
    }
    case A2DP_LHDCV5_MAX_BIT_RATE_1000K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MAX_BIT_RATE_1000K << 4);
      break;
    }
  }

  /*******************************************
   * Update LHDC Peer Min Bitrate Index to specific 1
   *******************************************/
  // store peer cap: max target bitrate index for UI reference
  codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_PEER_MIN_BITRATE_MASK);
  switch (result_config_cie.minTargetBitrate) {
    case A2DP_LHDCV5_MIN_BIT_RATE_64K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MIN_BIT_RATE_64K << 12);
      break;
    }
    case A2DP_LHDCV5_MIN_BIT_RATE_160K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MIN_BIT_RATE_160K << 12);
      break;
    }
    case A2DP_LHDCV5_MIN_BIT_RATE_256K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MIN_BIT_RATE_256K << 12);
      break;
    }
    case A2DP_LHDCV5_MIN_BIT_RATE_400K: {
      codec_user_config_.codec_specific_1 |= (A2DP_LHDCV5_MIN_BIT_RATE_400K << 12);
      break;
    }
  }

  //
  // special rules: quality mode re-adjustion
  //
  // non-ABR qualityMode re-adjusting
  if (qualityMode != A2DP_LHDC_QUALITY_ABR) {
    // get corresponding quality mode of the max target bit rate
    if (!A2DP_MaxBitRatetoQualityLevelLhdcV5(&bitRateQmode, result_config_cie.maxTargetBitrate)) {
      log::error("get quality mode from maxTargetBitrate error");
      status = AVDTP_UNSUPPORTED_CONFIGURATION;
      goto fail;
    }
    // downgrade audio quality according to the max target bit rate
    if (qualityMode > bitRateQmode) {
      codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
      codec_user_config_.codec_specific_1 |= (A2DP_LHDC_QUALITY_MAGIC_NUM | bitRateQmode);
      qualityMode = bitRateQmode;
      log::debug("downgrade quality mode to 0x{:02x}", qualityMode);
    }

    // get corresponding quality mode of the min target bit rate
    if (!A2DP_MinBitRatetoQualityLevelLhdcV5(&bitRateQmode, result_config_cie.minTargetBitrate)) {
      log::debug("get quality mode from minTargetBitrate error");
      status = AVDTP_UNSUPPORTED_CONFIGURATION;
      goto fail;
    }
    // upgrade audio quality according to the min target bit rate
    if (qualityMode < bitRateQmode) {
      codec_user_config_.codec_specific_1 &= ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
      codec_user_config_.codec_specific_1 |= (A2DP_LHDC_QUALITY_MAGIC_NUM | bitRateQmode);
      qualityMode = bitRateQmode;
      log::debug("upgrade quality mode to 0x{:02x}", qualityMode);
    }

    // rule: if (sample rate >= 96KHz) fixed bitrate must be at least 256kbps(LOW3),
    if (result_config_cie.sampleRate == A2DP_LHDCV5_SAMPLING_FREQ_96000 ||
        result_config_cie.sampleRate == A2DP_LHDCV5_SAMPLING_FREQ_192000) {
      if (qualityMode < A2DP_LHDC_QUALITY_LOW3) {
        codec_user_config_.codec_specific_1 &=
                ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
        codec_user_config_.codec_specific_1 |=
                (A2DP_LHDC_QUALITY_MAGIC_NUM | A2DP_LHDC_QUALITY_LOW3);
        qualityMode = A2DP_LHDC_QUALITY_LOW3;
        log::debug("upgrade quality mode to 0x{:02x} due to higher sample rates", qualityMode);
      }
    }

    // rule: if (sample rate <= 48KHz) fixed bitrate must be at most 900kbps(HIGH),
    if (result_config_cie.sampleRate == A2DP_LHDCV5_SAMPLING_FREQ_44100 ||
        result_config_cie.sampleRate == A2DP_LHDCV5_SAMPLING_FREQ_48000) {
      if (qualityMode >= A2DP_LHDC_QUALITY_HIGH1 && qualityMode != A2DP_LHDC_QUALITY_ABR) {
        codec_user_config_.codec_specific_1 &=
                ~(A2DP_LHDC_VENDOR_CMD_MASK | A2DP_LHDC_QUALITY_MASK);
        codec_user_config_.codec_specific_1 =
                (A2DP_LHDC_QUALITY_MAGIC_NUM | A2DP_LHDC_QUALITY_HIGH);
        qualityMode = A2DP_LHDC_QUALITY_HIGH;
        log::debug("downgrade quality mode to 0x{:02x} due to lower sample rates", qualityMode);
      }
    }
  }

  log::debug("=> final quality mode(0x{:02X}) = {}", qualityMode,
             lhdcV5_QualityModeBitRate_toString(qualityMode));

  /* Setup final nego result config to peer */
  if (A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &result_config_cie, p_result_codec_config) !=
      A2DP_SUCCESS) {
    log::error("A2DP build info fail");
    status = AVDTP_UNSUPPORTED_CONFIGURATION;
    goto fail;
  }

  //
  // Copy the codec-specific fields if they are not zero
  //
  if (codec_user_config_.codec_specific_1 != 0) {
    codec_config_.codec_specific_1 = codec_user_config_.codec_specific_1;
  }
  if (codec_user_config_.codec_specific_2 != 0) {
    codec_config_.codec_specific_2 = codec_user_config_.codec_specific_2;
  }
  if (codec_user_config_.codec_specific_3 != 0) {
    codec_config_.codec_specific_3 = codec_user_config_.codec_specific_3;
  }
  if (codec_user_config_.codec_specific_4 != 0) {
    codec_config_.codec_specific_4 = codec_user_config_.codec_specific_4;
  }

  // Create a local copy of the peer codec capability, and the
  // result codec config.
  if (is_capability) {
    status = A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &sink_info_cie,
                                  ota_codec_peer_capability_.data());
  } else {
    status = A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &sink_info_cie,
                                  ota_codec_peer_config_.data());
  }
  CHECK(status == A2DP_SUCCESS);

  status =
          A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &result_config_cie, ota_codec_config_.data());
  CHECK(status == A2DP_SUCCESS);
  return A2DP_SUCCESS;

fail:
  // Restore the internal state
  codec_config_ = saved_codec_config;
  codec_selectable_capability_ = saved_codec_selectable_capability;
  codec_user_config_ = saved_codec_user_config;
  codec_audio_config_ = saved_codec_audio_config;
  ota_codec_config_ = saved_ota_codec_config;
  ota_codec_peer_capability_ = saved_ota_codec_peer_capability;
  ota_codec_peer_config_ = saved_ota_codec_peer_config;
  return status;
}

bool A2dpCodecConfigLhdcV5Base::setPeerCodecCapabilities(const uint8_t* p_peer_codec_capabilities) {
  std::lock_guard<std::recursive_mutex> lock(codec_mutex_);
  tA2DP_LHDCV5_CIE peer_info_cie;
  uint8_t sampleRate;
  uint8_t bits_per_sample;
  tA2DP_STATUS status;
  const tA2DP_LHDCV5_CIE* p_a2dp_lhdcv5_caps =
          (is_source_) ? &a2dp_lhdcv5_source_caps : &a2dp_lhdcv5_sink_caps;

  // Save the internal state
  btav_a2dp_codec_config_t saved_codec_selectable_capability = codec_selectable_capability_;
  bluetooth::a2dp::MediaCodecCapabilities saved_ota_codec_peer_capability =
          ota_codec_peer_capability_;

  if (p_peer_codec_capabilities == nullptr) {
    log::error("nullptr input");
    goto fail;
  }

  status = A2DP_ParseInfoLhdcV5(&peer_info_cie, p_peer_codec_capabilities, true);
  if (status != A2DP_SUCCESS) {
    log::error("can't parse peer's capabilities: error = {}", status);
    goto fail;
  }

  // Compute the selectable capability - sample rate
  sampleRate = p_a2dp_lhdcv5_caps->sampleRate & peer_info_cie.sampleRate;
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_44100) {
    codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
  }
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_48000) {
    codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
  }
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_96000) {
    codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
  }
  if (sampleRate & A2DP_LHDCV5_SAMPLING_FREQ_192000) {
    codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
  }

  // Compute the selectable capability - bits per sample
  bits_per_sample = p_a2dp_lhdcv5_caps->bitsPerSample & peer_info_cie.bitsPerSample;
  if (bits_per_sample & A2DP_LHDCV5_BIT_FMT_16) {
    codec_selectable_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
  }
  if (bits_per_sample & A2DP_LHDCV5_BIT_FMT_24) {
    codec_selectable_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
  }
  if (bits_per_sample & A2DP_LHDCV5_BIT_FMT_32) {
    codec_selectable_capability_.bits_per_sample |= BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
  }

  // Compute the selectable capability - channel mode
  codec_selectable_capability_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;

  status = A2DP_BuildInfoLhdcV5(AVDT_MEDIA_TYPE_AUDIO, &peer_info_cie,
                                ota_codec_peer_capability_.data());
  CHECK(status == A2DP_SUCCESS);
  return true;

fail:
  // Restore the internal state
  codec_selectable_capability_ = saved_codec_selectable_capability;
  ota_codec_peer_capability_ = saved_ota_codec_peer_capability;
  return false;
}

////////
//    APIs for calling from encoder/decoder module - START
////////
bool A2DP_VendorGetMaxBitRateLhdcV5(uint32_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr || retval == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  switch (lhdc_cie.maxTargetBitrate) {
    case A2DP_LHDCV5_MAX_BIT_RATE_1000K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_HIGH1;
      return true;
    case A2DP_LHDCV5_MAX_BIT_RATE_900K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_HIGH;
      return true;
    case A2DP_LHDCV5_MAX_BIT_RATE_500K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_MID;
      return true;
    case A2DP_LHDCV5_MAX_BIT_RATE_400K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_LOW;
      return true;
  }

  return false;
}

bool A2DP_VendorGetMinBitRateLhdcV5(uint32_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr || retval == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  switch (lhdc_cie.minTargetBitrate) {
    case A2DP_LHDCV5_MIN_BIT_RATE_400K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_LOW;
      return true;
    case A2DP_LHDCV5_MIN_BIT_RATE_256K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_LOW3;
      return true;
    case A2DP_LHDCV5_MIN_BIT_RATE_160K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_LOW1;
      return true;
    case A2DP_LHDCV5_MIN_BIT_RATE_64K:
      *retval = (uint32_t)A2DP_LHDC_QUALITY_LOW0;
      return true;
  }

  return false;
}

bool A2DP_VendorGetVersionLhdcV5(uint32_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr || retval == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  *retval = (uint32_t)lhdc_cie.version;

  return true;
}

bool A2DP_VendorGetBitPerSampleLhdcV5(uint8_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr || retval == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  *retval = (uint32_t)lhdc_cie.bitsPerSample;

  return true;
}

bool A2DP_VendorHasLLFlagLhdcV5(uint8_t* retval, const uint8_t* p_codec_info) {
  tA2DP_LHDCV5_CIE lhdc_cie;
  tA2DP_STATUS a2dp_status;

  if (p_codec_info == nullptr || retval == nullptr) {
    log::error("nullptr input");
    return false;
  }

  // Check whether the codec info contains valid data
  a2dp_status = A2DP_ParseInfoLhdcV5(&lhdc_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }
  *retval = lhdc_cie.hasFeatureLL ? 1 : 0;

  return true;
}
