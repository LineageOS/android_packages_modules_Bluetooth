/*
 * Copyright 2016 The Android Open Source Project
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
 *  Utility functions to help build and parse the aptX Codec Information
 *  Element and Media Payload.
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth-a2dp"

#include "a2dp_vendor_aptx.h"

#include <bluetooth/log.h>
#include <string.h>

#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>

#include "a2dp_api.h"
#include "a2dp_codec_api.h"
#include "a2dp_constants.h"
#include "a2dp_vendor.h"
#include "a2dp_vendor_aptx_constants.h"
#include "a2dp_vendor_aptx_encoder.h"
#include "avdt_api.h"
#include "btif/include/btif_av_co.h"
#include "hardware/bt_av.h"
#include "internal_include/bt_trace.h"
#include "stack/include/bt_hdr.h"

using namespace bluetooth;

// data type for the aptX Codec Information Element */
typedef struct {
  uint32_t vendorId;
  uint16_t codecId;    /* Codec ID for aptX */
  uint8_t sampleRate;  /* Sampling Frequency */
  uint8_t channelMode; /* STEREO/DUAL/MONO */
  uint8_t future1;
  uint8_t future2;
  btav_a2dp_codec_bits_per_sample_t bits_per_sample;
} tA2DP_APTX_CIE;

/* aptX Source codec capabilities */
static const tA2DP_APTX_CIE a2dp_aptx_source_caps = {
        A2DP_APTX_VENDOR_ID,                                       /* vendorId */
        A2DP_APTX_CODEC_ID_BLUETOOTH,                              /* codecId */
        (A2DP_APTX_SAMPLERATE_44100 | A2DP_APTX_SAMPLERATE_48000), /* sampleRate */
        A2DP_APTX_CHANNELS_STEREO,                                 /* channelMode */
        A2DP_APTX_FUTURE_1,                                        /* future1 */
        A2DP_APTX_FUTURE_2,                                        /* future2 */
        BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16                         /* bits_per_sample */
};

/* Default aptX codec configuration */
static const tA2DP_APTX_CIE a2dp_aptx_default_config = {
        A2DP_APTX_VENDOR_ID,               /* vendorId */
        A2DP_APTX_CODEC_ID_BLUETOOTH,      /* codecId */
        A2DP_APTX_SAMPLERATE_48000,        /* sampleRate */
        A2DP_APTX_CHANNELS_STEREO,         /* channelMode */
        A2DP_APTX_FUTURE_1,                /* future1 */
        A2DP_APTX_FUTURE_2,                /* future2 */
        BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16 /* bits_per_sample */
};

static const tA2DP_ENCODER_INTERFACE a2dp_encoder_interface_aptx = {
        a2dp_vendor_aptx_encoder_init,
        a2dp_vendor_aptx_encoder_cleanup,
        a2dp_vendor_aptx_feeding_reset,
        a2dp_vendor_aptx_feeding_flush,
        a2dp_vendor_aptx_get_encoder_interval_ms,
        a2dp_vendor_aptx_get_effective_frame_size,
        a2dp_vendor_aptx_send_frames,
        nullptr  // set_transmit_queue_length
};

// Builds the aptX Media Codec Capabilities byte sequence beginning from the
// LOSC octet. |media_type| is the media type |AVDT_MEDIA_TYPE_*|.
// |p_ie| is a pointer to the aptX Codec Information Element information.
// The result is stored in |p_result|. Returns A2DP_SUCCESS on success,
// otherwise the corresponding A2DP error status code.
static bool A2DP_BuildInfoAptx(uint8_t media_type, const tA2DP_APTX_CIE* p_ie, uint8_t* p_result) {
  if (p_ie == NULL || p_result == NULL) {
    return false;
  }

  *p_result++ = A2DP_APTX_CODEC_LEN;
  *p_result++ = (media_type << 4);
  *p_result++ = A2DP_MEDIA_CT_NON_A2DP;
  *p_result++ = (uint8_t)(p_ie->vendorId & 0x000000FF);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0x0000FF00) >> 8);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0x00FF0000) >> 16);
  *p_result++ = (uint8_t)((p_ie->vendorId & 0xFF000000) >> 24);
  *p_result++ = (uint8_t)(p_ie->codecId & 0x00FF);
  *p_result++ = (uint8_t)((p_ie->codecId & 0xFF00) >> 8);
  *p_result++ = p_ie->sampleRate | p_ie->channelMode;

  return true;
}

// Parses the aptX Media Codec Capabilities byte sequence beginning from the
// LOSC octet. The result is stored in |p_ie|. The byte sequence to parse is
// |p_codec_info|. If |is_capability| is true, the byte sequence is
// codec capabilities, otherwise is codec configuration.
// Returns A2DP_SUCCESS on success, otherwise the corresponding A2DP error
// status code.
static tA2DP_STATUS A2DP_ParseInfoAptx(tA2DP_APTX_CIE* p_ie, const uint8_t* p_codec_info,
                                       bool is_capability) {
  uint8_t losc;
  uint8_t media_type;
  tA2DP_CODEC_TYPE codec_type;

  if (p_ie == NULL || p_codec_info == NULL) {
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  // Check the codec capability length
  losc = *p_codec_info++;
  if (losc != A2DP_APTX_CODEC_LEN) {
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  media_type = (*p_codec_info++) >> 4;
  codec_type = static_cast<tA2DP_CODEC_TYPE>(*p_codec_info++);
  /* Check the Media Type and Media Codec Type */
  if (media_type != AVDT_MEDIA_TYPE_AUDIO || codec_type != A2DP_MEDIA_CT_NON_A2DP) {
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  // Check the Vendor ID and Codec ID */
  p_ie->vendorId = (*p_codec_info & 0x000000FF) | (*(p_codec_info + 1) << 8 & 0x0000FF00) |
                   (*(p_codec_info + 2) << 16 & 0x00FF0000) |
                   (*(p_codec_info + 3) << 24 & 0xFF000000);
  p_codec_info += 4;
  p_ie->codecId = (*p_codec_info & 0x00FF) | (*(p_codec_info + 1) << 8 & 0xFF00);
  p_codec_info += 2;
  if (p_ie->vendorId != A2DP_APTX_VENDOR_ID || p_ie->codecId != A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return AVDTP_UNSUPPORTED_CONFIGURATION;
  }

  p_ie->channelMode = *p_codec_info & 0x0F;
  p_ie->sampleRate = *p_codec_info & 0xF0;
  p_codec_info++;

  if (is_capability) {
    // NOTE: The checks here are very liberal. We should be using more
    // pedantic checks specific to the SRC or SNK as specified in the spec.
    if (A2DP_BitsSet(p_ie->sampleRate) == A2DP_SET_ZERO_BIT) {
      return A2DP_INVALID_SAMPLING_FREQUENCY;
    }
    if (A2DP_BitsSet(p_ie->channelMode) == A2DP_SET_ZERO_BIT) {
      return A2DP_INVALID_CHANNEL_MODE;
    }

    return A2DP_SUCCESS;
  }

  if (A2DP_BitsSet(p_ie->sampleRate) != A2DP_SET_ONE_BIT) {
    return A2DP_INVALID_SAMPLING_FREQUENCY;
  }
  if (A2DP_BitsSet(p_ie->channelMode) != A2DP_SET_ONE_BIT) {
    return A2DP_INVALID_CHANNEL_MODE;
  }

  return A2DP_SUCCESS;
}

bool A2DP_IsCodecValidAptx(const uint8_t* p_codec_info) {
  tA2DP_APTX_CIE cfg_cie;

  /* Use a liberal check when parsing the codec info */
  return (A2DP_ParseInfoAptx(&cfg_cie, p_codec_info, false) == A2DP_SUCCESS) ||
         (A2DP_ParseInfoAptx(&cfg_cie, p_codec_info, true) == A2DP_SUCCESS);
}

bool A2DP_VendorUsesRtpHeaderAptx(bool /* content_protection_enabled */,
                                  const uint8_t* /* p_codec_info */) {
  // no RTP header for aptX classic and no Copy Protection byte
  return false;
}

const char* A2DP_VendorCodecNameAptx(const uint8_t* /* p_codec_info */) { return "aptX"; }

bool A2DP_VendorCodecTypeEqualsAptx(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b) {
  tA2DP_APTX_CIE aptx_cie_a;
  tA2DP_APTX_CIE aptx_cie_b;

  // Check whether the codec info contains valid data
  tA2DP_STATUS a2dp_status = A2DP_ParseInfoAptx(&aptx_cie_a, p_codec_info_a, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }
  a2dp_status = A2DP_ParseInfoAptx(&aptx_cie_b, p_codec_info_b, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  return true;
}

bool A2DP_VendorCodecEqualsAptx(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b) {
  tA2DP_APTX_CIE aptx_cie_a;
  tA2DP_APTX_CIE aptx_cie_b;

  // Check whether the codec info contains valid data
  tA2DP_STATUS a2dp_status = A2DP_ParseInfoAptx(&aptx_cie_a, p_codec_info_a, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }
  a2dp_status = A2DP_ParseInfoAptx(&aptx_cie_b, p_codec_info_b, true);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return false;
  }

  return (aptx_cie_a.sampleRate == aptx_cie_b.sampleRate) &&
         (aptx_cie_a.channelMode == aptx_cie_b.channelMode);
}

int A2DP_VendorGetBitRateAptx(const uint8_t* p_codec_info) {
  A2dpCodecConfig* CodecConfig = bta_av_get_a2dp_current_codec();
  tA2DP_BITS_PER_SAMPLE bits_per_sample = CodecConfig->getAudioBitsPerSample();
  uint16_t samplerate = A2DP_GetTrackSampleRate(p_codec_info);
  return (samplerate * bits_per_sample * 2) / 4;
}

int A2DP_VendorGetTrackSampleRateAptx(const uint8_t* p_codec_info) {
  tA2DP_APTX_CIE aptx_cie;

  // Check whether the codec info contains valid data
  tA2DP_STATUS a2dp_status = A2DP_ParseInfoAptx(&aptx_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  if (aptx_cie.sampleRate == A2DP_APTX_SAMPLERATE_44100) {
    return 44100;
  }
  if (aptx_cie.sampleRate == A2DP_APTX_SAMPLERATE_48000) {
    return 48000;
  }

  return -1;
}

int A2DP_VendorGetTrackBitsPerSampleAptx(const uint8_t* p_codec_info) {
  tA2DP_APTX_CIE aptx_cie;

  // Check whether the codec info contains valid data
  tA2DP_STATUS a2dp_status = A2DP_ParseInfoAptx(&aptx_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  // NOTE: The bits per sample never changes for aptX
  return 16;
}

int A2DP_VendorGetTrackChannelCountAptx(const uint8_t* p_codec_info) {
  tA2DP_APTX_CIE aptx_cie;

  // Check whether the codec info contains valid data
  tA2DP_STATUS a2dp_status = A2DP_ParseInfoAptx(&aptx_cie, p_codec_info, false);
  if (a2dp_status != A2DP_SUCCESS) {
    log::error("cannot decode codec information: {}", a2dp_status);
    return -1;
  }

  switch (aptx_cie.channelMode) {
    case A2DP_APTX_CHANNELS_MONO:
      return 1;
    case A2DP_APTX_CHANNELS_STEREO:
      return 2;
  }

  return -1;
}

bool A2DP_VendorGetPacketTimestampAptx(const uint8_t* /* p_codec_info */, const uint8_t* p_data,
                                       uint32_t* p_timestamp) {
  // TODO: Is this function really codec-specific?
  *p_timestamp = *(const uint32_t*)p_data;
  return true;
}

bool A2DP_VendorBuildCodecHeaderAptx(const uint8_t* /* p_codec_info */, BT_HDR* /* p_buf */,
                                     uint16_t /* frames_per_packet */) {
  // Nothing to do
  return true;
}

std::string A2DP_VendorCodecInfoStringAptx(const uint8_t* p_codec_info) {
  std::stringstream res;
  std::string field;
  tA2DP_STATUS a2dp_status;
  tA2DP_APTX_CIE aptx_cie;

  a2dp_status = A2DP_ParseInfoAptx(&aptx_cie, p_codec_info, true);
  if (a2dp_status != A2DP_SUCCESS) {
    res << "A2DP_ParseInfoAptx fail: " << loghex(static_cast<uint8_t>(a2dp_status));
    return res.str();
  }

  res << "\tname: aptX\n";

  // Sample frequency
  field.clear();
  AppendField(&field, (aptx_cie.sampleRate == 0), "NONE");
  AppendField(&field, (aptx_cie.sampleRate & A2DP_APTX_SAMPLERATE_44100), "44100");
  AppendField(&field, (aptx_cie.sampleRate & A2DP_APTX_SAMPLERATE_48000), "48000");
  res << "\tsamp_freq: " << field << " (" << loghex(aptx_cie.sampleRate) << ")\n";

  // Channel mode
  field.clear();
  AppendField(&field, (aptx_cie.channelMode == 0), "NONE");
  AppendField(&field, (aptx_cie.channelMode & A2DP_APTX_CHANNELS_MONO), "Mono");
  AppendField(&field, (aptx_cie.channelMode & A2DP_APTX_CHANNELS_STEREO), "Stereo");
  res << "\tch_mode: " << field << " (" << loghex(aptx_cie.channelMode) << ")\n";

  return res.str();
}

const tA2DP_ENCODER_INTERFACE* A2DP_VendorGetEncoderInterfaceAptx(
    const uint8_t* p_codec_info) {
  if (!A2DP_IsCodecValidAptx(p_codec_info)) {
    return NULL;
  }

  return &a2dp_encoder_interface_aptx;
}

bool A2DP_VendorAdjustCodecAptx(uint8_t* p_codec_info) {
  tA2DP_APTX_CIE cfg_cie;

  // Nothing to do: just verify the codec info is valid
  if (A2DP_ParseInfoAptx(&cfg_cie, p_codec_info, true) != A2DP_SUCCESS) {
    return false;
  }

  return true;
}

btav_a2dp_codec_index_t A2DP_VendorSourceCodecIndexAptx(const uint8_t* /* p_codec_info */) {
  return BTAV_A2DP_CODEC_INDEX_SOURCE_APTX;
}

const char* A2DP_VendorCodecIndexStrAptx(void) { return "aptX"; }

bool A2DP_VendorInitCodecConfigAptx(AvdtpSepConfig* p_cfg) {
  return A2DP_BuildInfoAptx(AVDT_MEDIA_TYPE_AUDIO, &a2dp_aptx_source_caps, p_cfg->codec_info);
}

A2dpCodecConfigAptx::A2dpCodecConfigAptx(btav_a2dp_codec_priority_t codec_priority)
    : A2dpCodecConfig(BTAV_A2DP_CODEC_INDEX_SOURCE_APTX, bluetooth::a2dp::CodecId::APTX,
                      A2DP_VendorCodecIndexStrAptx(), codec_priority) {
  // Compute the local capability
  if (a2dp_aptx_source_caps.sampleRate & A2DP_APTX_SAMPLERATE_44100) {
    codec_local_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
  }
  if (a2dp_aptx_source_caps.sampleRate & A2DP_APTX_SAMPLERATE_48000) {
    codec_local_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
  }
  codec_local_capability_.bits_per_sample = a2dp_aptx_source_caps.bits_per_sample;
  if (a2dp_aptx_source_caps.channelMode & A2DP_APTX_CHANNELS_MONO) {
    codec_local_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
  }
  if (a2dp_aptx_source_caps.channelMode & A2DP_APTX_CHANNELS_STEREO) {
    codec_local_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  }
}

A2dpCodecConfigAptx::~A2dpCodecConfigAptx() {}

bool A2dpCodecConfigAptx::init() {
  // Load the encoder
  if (A2DP_VendorLoadEncoderAptx() != LOAD_SUCCESS) {
    log::error("cannot load the encoder");
    return false;
  }

  return true;
}

bool A2dpCodecConfigAptx::useRtpHeaderMarkerBit() const { return false; }

//
// Selects the best sample rate from |sampleRate|.
// The result is stored in |p_result| and p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_best_sample_rate(uint8_t sampleRate, tA2DP_APTX_CIE* p_result,
                                    btav_a2dp_codec_config_t* p_codec_config) {
  if (sampleRate & A2DP_APTX_SAMPLERATE_48000) {
    p_result->sampleRate = A2DP_APTX_SAMPLERATE_48000;
    p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
    return true;
  }
  if (sampleRate & A2DP_APTX_SAMPLERATE_44100) {
    p_result->sampleRate = A2DP_APTX_SAMPLERATE_44100;
    p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
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
                                     uint8_t sampleRate, tA2DP_APTX_CIE* p_result,
                                     btav_a2dp_codec_config_t* p_codec_config) {
  switch (p_codec_audio_config->sample_rate) {
    case BTAV_A2DP_CODEC_SAMPLE_RATE_44100:
      if (sampleRate & A2DP_APTX_SAMPLERATE_44100) {
        p_result->sampleRate = A2DP_APTX_SAMPLERATE_44100;
        p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_48000:
      if (sampleRate & A2DP_APTX_SAMPLERATE_48000) {
        p_result->sampleRate = A2DP_APTX_SAMPLERATE_48000;
        p_codec_config->sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_88200:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_96000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_176400:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_192000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_16000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_24000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_NONE:
      break;
  }
  return false;
}

//
// Selects the best bits per sample.
// The result is stored in |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_best_bits_per_sample(btav_a2dp_codec_config_t* p_codec_config) {
  p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
  return true;
}

//
// Selects the audio bits per sample from |p_codec_audio_config|.
// The result is stored in |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_audio_bits_per_sample(const btav_a2dp_codec_config_t* p_codec_audio_config,
                                         btav_a2dp_codec_config_t* p_codec_config) {
  switch (p_codec_audio_config->bits_per_sample) {
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16:
      p_codec_config->bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
      return true;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24:
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32:
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE:
      break;
  }
  return false;
}

//
// Selects the best channel mode from |channelMode|.
// The result is stored in |p_result| and |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_best_channel_mode(uint8_t channelMode, tA2DP_APTX_CIE* p_result,
                                     btav_a2dp_codec_config_t* p_codec_config) {
  if (channelMode & A2DP_APTX_CHANNELS_STEREO) {
    p_result->channelMode = A2DP_APTX_CHANNELS_STEREO;
    p_codec_config->channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
    return true;
  }
  if (channelMode & A2DP_APTX_CHANNELS_MONO) {
    p_result->channelMode = A2DP_APTX_CHANNELS_MONO;
    p_codec_config->channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
    return true;
  }
  return false;
}

//
// Selects the audio channel mode from |p_codec_audio_config|.
// |channelMode| contains the capability.
// The result is stored in |p_result| and |p_codec_config|.
// Returns true if a selection was made, otherwise false.
//
static bool select_audio_channel_mode(const btav_a2dp_codec_config_t* p_codec_audio_config,
                                      uint8_t channelMode, tA2DP_APTX_CIE* p_result,
                                      btav_a2dp_codec_config_t* p_codec_config) {
  switch (p_codec_audio_config->channel_mode) {
    case BTAV_A2DP_CODEC_CHANNEL_MODE_MONO:
      if (channelMode & A2DP_APTX_CHANNELS_MONO) {
        p_result->channelMode = A2DP_APTX_CHANNELS_MONO;
        p_codec_config->channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO:
      if (channelMode & A2DP_APTX_CHANNELS_STEREO) {
        p_result->channelMode = A2DP_APTX_CHANNELS_STEREO;
        p_codec_config->channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
        return true;
      }
      break;
    case BTAV_A2DP_CODEC_CHANNEL_MODE_NONE:
      break;
  }

  return false;
}

tA2DP_STATUS A2dpCodecConfigAptx::setCodecConfig(const uint8_t* p_peer_codec_info,
                                                 bool is_capability,
                                                 uint8_t* p_result_codec_config) {
  std::lock_guard<std::recursive_mutex> lock(codec_mutex_);
  tA2DP_APTX_CIE peer_info_cie;
  tA2DP_APTX_CIE result_config_cie;
  uint8_t sampleRate;
  uint8_t channelMode;

  peer_info_cie.future1 = A2DP_APTX_FUTURE_1;
  peer_info_cie.future2 = A2DP_APTX_FUTURE_2;

  // Save the internal state
  btav_a2dp_codec_config_t saved_codec_config = codec_config_;
  btav_a2dp_codec_config_t saved_codec_selectable_capability = codec_selectable_capability_;
  btav_a2dp_codec_config_t saved_codec_user_config = codec_user_config_;
  btav_a2dp_codec_config_t saved_codec_audio_config = codec_audio_config_;
  uint8_t saved_ota_codec_config[AVDT_CODEC_SIZE];
  uint8_t saved_ota_codec_peer_capability[AVDT_CODEC_SIZE];
  uint8_t saved_ota_codec_peer_config[AVDT_CODEC_SIZE];
  memcpy(saved_ota_codec_config, ota_codec_config_, sizeof(ota_codec_config_));
  memcpy(saved_ota_codec_peer_capability, ota_codec_peer_capability_,
         sizeof(ota_codec_peer_capability_));
  memcpy(saved_ota_codec_peer_config, ota_codec_peer_config_, sizeof(ota_codec_peer_config_));

  tA2DP_STATUS status = A2DP_ParseInfoAptx(&peer_info_cie, p_peer_codec_info, is_capability);
  if (status != A2DP_SUCCESS) {
    log::error("can't parse peer's capabilities: error = {}", status);
    goto fail;
  }

  //
  // Build the preferred configuration
  //
  memset(&result_config_cie, 0, sizeof(result_config_cie));
  result_config_cie.vendorId = a2dp_aptx_source_caps.vendorId;
  result_config_cie.codecId = a2dp_aptx_source_caps.codecId;

  //
  // Select the sample frequency
  //
  sampleRate = a2dp_aptx_source_caps.sampleRate & peer_info_cie.sampleRate;
  codec_config_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
  switch (codec_user_config_.sample_rate) {
    case BTAV_A2DP_CODEC_SAMPLE_RATE_44100:
      if (sampleRate & A2DP_APTX_SAMPLERATE_44100) {
        result_config_cie.sampleRate = A2DP_APTX_SAMPLERATE_44100;
        codec_config_.sample_rate = codec_user_config_.sample_rate;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_48000:
      if (sampleRate & A2DP_APTX_SAMPLERATE_48000) {
        result_config_cie.sampleRate = A2DP_APTX_SAMPLERATE_48000;
        codec_config_.sample_rate = codec_user_config_.sample_rate;
      }
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_88200:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_96000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_176400:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_192000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_16000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_24000:
    case BTAV_A2DP_CODEC_SAMPLE_RATE_NONE:
      codec_config_.sample_rate = BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
      break;
  }

  // Select the sample frequency if there is no user preference
  do {
    // Compute the selectable capability
    if (sampleRate & A2DP_APTX_SAMPLERATE_44100) {
      codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
    }
    if (sampleRate & A2DP_APTX_SAMPLERATE_48000) {
      codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
    }

    if (codec_config_.sample_rate != BTAV_A2DP_CODEC_SAMPLE_RATE_NONE) {
      break;
    }

    // No user preference - try the codec audio config
    if (select_audio_sample_rate(&codec_audio_config_, sampleRate, &result_config_cie,
                                 &codec_config_)) {
      break;
    }

    // No user preference - try the default config
    if (select_best_sample_rate(a2dp_aptx_default_config.sampleRate & peer_info_cie.sampleRate,
                                &result_config_cie, &codec_config_)) {
      break;
    }

    // No user preference - use the best match
    if (select_best_sample_rate(sampleRate, &result_config_cie, &codec_config_)) {
      break;
    }
  } while (false);
  if (codec_config_.sample_rate == BTAV_A2DP_CODEC_SAMPLE_RATE_NONE) {
    log::error("cannot match sample frequency: local caps = 0x{:x} peer info = 0x{:x}",
               a2dp_aptx_source_caps.sampleRate, peer_info_cie.sampleRate);
    status = A2DP_NOT_SUPPORTED_SAMPLING_FREQUENCY;
    goto fail;
  }

  //
  // Select the bits per sample
  //
  // NOTE: this information is NOT included in the aptX A2DP codec
  // description that is sent OTA.
  codec_config_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
  switch (codec_user_config_.bits_per_sample) {
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16:
      codec_config_.bits_per_sample = codec_user_config_.bits_per_sample;
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24:
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32:
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE:
      codec_config_.bits_per_sample = BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
      break;
  }

  // Select the bits per sample if there is no user preference
  do {
    // Compute the selectable capability
    codec_selectable_capability_.bits_per_sample = a2dp_aptx_source_caps.bits_per_sample;

    if (codec_config_.bits_per_sample != BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE) {
      break;
    }

    // No user preference - try the codec audio config
    if (select_audio_bits_per_sample(&codec_audio_config_, &codec_config_)) {
      break;
    }

    // No user preference - try the default config
    if (select_best_bits_per_sample(&codec_config_)) {
      break;
    }

    // No user preference - use the best match
    // NOTE: no-op - kept here for consistency
    if (select_best_bits_per_sample(&codec_config_)) {
      break;
    }
  } while (false);
  if (codec_config_.bits_per_sample == BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE) {
    log::error("cannot match bits per sample: user preference = 0x{:x}",
               codec_user_config_.bits_per_sample);
    status = A2DP_NOT_SUPPORTED_BIT_RATE;
    goto fail;
  }

  //
  // Select the channel mode
  //
  channelMode = a2dp_aptx_source_caps.channelMode & peer_info_cie.channelMode;
  codec_config_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_NONE;
  switch (codec_user_config_.channel_mode) {
    case BTAV_A2DP_CODEC_CHANNEL_MODE_MONO:
      if (channelMode & A2DP_APTX_CHANNELS_MONO) {
        result_config_cie.channelMode = A2DP_APTX_CHANNELS_MONO;
        codec_config_.channel_mode = codec_user_config_.channel_mode;
      }
      break;
    case BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO:
      if (channelMode & A2DP_APTX_CHANNELS_STEREO) {
        result_config_cie.channelMode = A2DP_APTX_CHANNELS_STEREO;
        codec_config_.channel_mode = codec_user_config_.channel_mode;
      }
      break;
    case BTAV_A2DP_CODEC_CHANNEL_MODE_NONE:
      codec_config_.channel_mode = BTAV_A2DP_CODEC_CHANNEL_MODE_NONE;
      break;
  }

  // Select the channel mode if there is no user preference
  do {
    // Compute the selectable capability
    if (channelMode & A2DP_APTX_CHANNELS_MONO) {
      codec_selectable_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
    }
    if (channelMode & A2DP_APTX_CHANNELS_STEREO) {
      codec_selectable_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
    }

    if (codec_config_.channel_mode != BTAV_A2DP_CODEC_CHANNEL_MODE_NONE) {
      break;
    }

    // No user preference - try the codec audio config
    if (select_audio_channel_mode(&codec_audio_config_, channelMode, &result_config_cie,
                                  &codec_config_)) {
      break;
    }

    // No user preference - try the default config
    if (select_best_channel_mode(a2dp_aptx_default_config.channelMode & peer_info_cie.channelMode,
                                 &result_config_cie, &codec_config_)) {
      break;
    }

    // No user preference - use the best match
    if (select_best_channel_mode(channelMode, &result_config_cie, &codec_config_)) {
      break;
    }
  } while (false);
  if (codec_config_.channel_mode == BTAV_A2DP_CODEC_CHANNEL_MODE_NONE) {
    log::error("cannot match channel mode: local caps = 0x{:x} peer info = 0x{:x}",
               a2dp_aptx_source_caps.channelMode, peer_info_cie.channelMode);
    status = A2DP_NOT_SUPPORTED_CHANNEL_MODE;
    goto fail;
  }

  //
  // Set the rest of the fields as bit-wise AND operation
  //
  result_config_cie.future1 = a2dp_aptx_source_caps.future1 & peer_info_cie.future1;
  result_config_cie.future2 = a2dp_aptx_source_caps.future2 & peer_info_cie.future2;

  if (!A2DP_BuildInfoAptx(AVDT_MEDIA_TYPE_AUDIO, &result_config_cie, p_result_codec_config)) {
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

  // Create a local copy of the peer codec capability/config, and the
  // result codec config.
  if (is_capability) {
    log::assert_that(
            A2DP_BuildInfoAptx(AVDT_MEDIA_TYPE_AUDIO, &peer_info_cie, ota_codec_peer_capability_),
            "Failed to build media codec capabilities");
  } else {
    log::assert_that(
            A2DP_BuildInfoAptx(AVDT_MEDIA_TYPE_AUDIO, &peer_info_cie, ota_codec_peer_config_),
            "Failed to build media codec capabilities");
  }
  log::assert_that(A2DP_BuildInfoAptx(AVDT_MEDIA_TYPE_AUDIO, &result_config_cie, ota_codec_config_),
                   "Failed to build media codec capabilities");

  return A2DP_SUCCESS;

fail:
  // Restore the internal state
  codec_config_ = saved_codec_config;
  codec_selectable_capability_ = saved_codec_selectable_capability;
  codec_user_config_ = saved_codec_user_config;
  codec_audio_config_ = saved_codec_audio_config;
  memcpy(ota_codec_config_, saved_ota_codec_config, sizeof(ota_codec_config_));
  memcpy(ota_codec_peer_capability_, saved_ota_codec_peer_capability,
         sizeof(ota_codec_peer_capability_));
  memcpy(ota_codec_peer_config_, saved_ota_codec_peer_config, sizeof(ota_codec_peer_config_));
  return status;
}

bool A2dpCodecConfigAptx::setPeerCodecCapabilities(const uint8_t* p_peer_codec_capabilities) {
  std::lock_guard<std::recursive_mutex> lock(codec_mutex_);
  tA2DP_APTX_CIE peer_info_cie;
  uint8_t sampleRate;
  uint8_t channelMode;

  // Save the internal state
  btav_a2dp_codec_config_t saved_codec_selectable_capability = codec_selectable_capability_;
  uint8_t saved_ota_codec_peer_capability[AVDT_CODEC_SIZE];
  memcpy(saved_ota_codec_peer_capability, ota_codec_peer_capability_,
         sizeof(ota_codec_peer_capability_));

  tA2DP_STATUS status = A2DP_ParseInfoAptx(&peer_info_cie, p_peer_codec_capabilities, true);
  if (status != A2DP_SUCCESS) {
    log::error("can't parse peer's capabilities: error = {}", status);
    goto fail;
  }

  // Compute the selectable capability - sample rate
  sampleRate = a2dp_aptx_source_caps.sampleRate & peer_info_cie.sampleRate;
  if (sampleRate & A2DP_APTX_SAMPLERATE_44100) {
    codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
  }
  if (sampleRate & A2DP_APTX_SAMPLERATE_48000) {
    codec_selectable_capability_.sample_rate |= BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
  }

  // Compute the selectable capability - bits per sample
  codec_selectable_capability_.bits_per_sample = a2dp_aptx_source_caps.bits_per_sample;

  // Compute the selectable capability - channel mode
  channelMode = a2dp_aptx_source_caps.channelMode & peer_info_cie.channelMode;
  if (channelMode & A2DP_APTX_CHANNELS_MONO) {
    codec_selectable_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
  }
  if (channelMode & A2DP_APTX_CHANNELS_STEREO) {
    codec_selectable_capability_.channel_mode |= BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
  }

  log::assert_that(
          A2DP_BuildInfoAptx(AVDT_MEDIA_TYPE_AUDIO, &peer_info_cie, ota_codec_peer_capability_),
          "Failed to build media codec capabilities");
  return true;

fail:
  // Restore the internal state
  codec_selectable_capability_ = saved_codec_selectable_capability;
  memcpy(ota_codec_peer_capability_, saved_ota_codec_peer_capability,
         sizeof(ota_codec_peer_capability_));
  return false;
}
