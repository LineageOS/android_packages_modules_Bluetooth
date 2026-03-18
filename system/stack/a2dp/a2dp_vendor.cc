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

/**
 * Vendor Specific A2DP Codecs Support
 */

#define LOG_TAG "bluetooth-a2dp"

#include "stack/include/a2dp_vendor.h"

#include <cstddef>
#include <cstdint>
#include <string>

#include "hardware/bt_av.h"
#include "stack/include/a2dp_codec_api.h"
#include "stack/include/a2dp_constants.h"
#include "stack/include/a2dp_vendor_aptx.h"
#include "stack/include/a2dp_vendor_aptx_constants.h"
#include "stack/include/a2dp_vendor_aptx_hd.h"
#include "stack/include/a2dp_vendor_aptx_hd_constants.h"
#include "stack/include/a2dp_vendor_ldac.h"
#include "stack/include/a2dp_vendor_ldac_constants.h"
#include "stack/include/a2dp_vendor_lhdcv5.h"
#include "stack/include/a2dp_vendor_lhdcv5_constants.h"
#include "stack/include/a2dp_vendor_opus.h"
#include "stack/include/a2dp_vendor_opus_constants.h"
#include "stack/include/avdt_api.h"
#include "stack/include/bt_hdr.h"

bool A2DP_IsVendorSourceCodecValid(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID &&
      codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_IsCodecValidAptx(p_codec_info);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID &&
      codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_IsCodecValidAptxHd(p_codec_info);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_IsCodecValidLdac(p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_IsCodecValidOpus(p_codec_info);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_IsCodecValidLhdcV5(p_codec_info);
  }

  // Add checks based on <vendor_id, codec_id>

  return false;
}

bool A2DP_IsVendorPeerSourceCodecValid(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Add checks based on <vendor_id, codec_id>
  // NOTE: Should be done only for local Sink codecs.

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_IsCodecValidLdac(p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_IsCodecValidOpus(p_codec_info);
  }

  return false;
}

bool A2DP_IsVendorPeerSinkCodecValid(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID &&
      codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_IsCodecValidAptx(p_codec_info);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID &&
      codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_IsCodecValidAptxHd(p_codec_info);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_IsCodecValidLdac(p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_IsCodecValidOpus(p_codec_info);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_IsCodecValidLhdcV5(p_codec_info);
  }

  // Add checks based on <vendor_id, codec_id>

  return false;
}

tA2DP_STATUS A2DP_IsVendorSinkCodecSupported(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Add checks based on <vendor_id, codec_id>
  // NOTE: Should be done only for local Sink codecs.

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_IsVendorSinkCodecSupportedOpus(p_codec_info);
  }

  return A2DP_NOT_SUPPORTED_CODEC_TYPE;
}

bool A2DP_VendorUsesRtpHeader(bool content_protection_enabled, const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID && codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorUsesRtpHeaderAptx(content_protection_enabled, p_codec_info);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID && codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorUsesRtpHeaderAptxHd(content_protection_enabled, p_codec_info);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_VendorUsesRtpHeaderLdac(content_protection_enabled, p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorUsesRtpHeaderOpus(content_protection_enabled, p_codec_info);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_VendorUsesRtpHeaderLhdcV5(content_protection_enabled, p_codec_info);
  }

  // Add checks based on <content_protection_enabled, vendor_id, codec_id>

  return true;
}

bool A2DP_VendorCodecTypeEquals(const uint8_t* p_codec_info_a, const uint8_t* p_codec_info_b) {
  tA2DP_CODEC_TYPE codec_type_a = A2DP_GetCodecType(p_codec_info_a);
  tA2DP_CODEC_TYPE codec_type_b = A2DP_GetCodecType(p_codec_info_b);

  if ((codec_type_a != codec_type_b) || (codec_type_a != A2DP_MEDIA_CT_NON_A2DP)) {
    return false;
  }

  uint32_t vendor_id_a = A2DP_VendorCodecGetVendorId(p_codec_info_a);
  uint16_t codec_id_a = A2DP_VendorCodecGetCodecId(p_codec_info_a);
  uint32_t vendor_id_b = A2DP_VendorCodecGetVendorId(p_codec_info_b);
  uint16_t codec_id_b = A2DP_VendorCodecGetCodecId(p_codec_info_b);

  if (vendor_id_a != vendor_id_b || codec_id_a != codec_id_b) {
    return false;
  }

  // Check for aptX
  if (vendor_id_a == A2DP_APTX_VENDOR_ID && codec_id_a == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorCodecTypeEqualsAptx(p_codec_info_a, p_codec_info_b);
  }

  // Check for aptX-HD
  if (vendor_id_a == A2DP_APTX_HD_VENDOR_ID && codec_id_a == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorCodecTypeEqualsAptxHd(p_codec_info_a, p_codec_info_b);
  }

  // Check for LDAC
  if (vendor_id_a == A2DP_LDAC_VENDOR_ID && codec_id_a == A2DP_LDAC_CODEC_ID) {
    return A2DP_VendorCodecTypeEqualsLdac(p_codec_info_a, p_codec_info_b);
  }

  // Check for Opus
  if (vendor_id_a == A2DP_OPUS_VENDOR_ID && codec_id_a == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorCodecTypeEqualsOpus(p_codec_info_a, p_codec_info_b);
  }

  // Check for LHDCv5
  if (vendor_id_a == A2DP_LHDC_VENDOR_ID && codec_id_a == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_VendorCodecTypeEqualsLhdcV5(p_codec_info_a, p_codec_info_b);
  }

  // OPTIONAL: Add extra vendor-specific checks based on the
  // vendor-specific data stored in "p_codec_info_a" and "p_codec_info_b".

  return true;
}

int A2DP_VendorGetSinkTrackChannelType(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Add checks based on <vendor_id, codec_id>
  // NOTE: Should be done only for local Sink codecs.

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorGetSinkTrackChannelTypeOpus(p_codec_info);
  }

  return -1;
}

bool A2DP_VendorBuildCodecHeader(const uint8_t* p_codec_info, BT_HDR* p_buf,
                                 uint16_t frames_per_packet) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID && codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorBuildCodecHeaderAptx(p_codec_info, p_buf, frames_per_packet);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID && codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorBuildCodecHeaderAptxHd(p_codec_info, p_buf, frames_per_packet);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_VendorBuildCodecHeaderLdac(p_codec_info, p_buf, frames_per_packet);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorBuildCodecHeaderOpus(p_codec_info, p_buf, frames_per_packet);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_VendorBuildCodecHeaderLhdcV5(p_codec_info, p_buf, frames_per_packet);
  }

  // Add checks based on <vendor_id, codec_id>

  return false;
}

const tA2DP_ENCODER_INTERFACE* A2DP_VendorGetEncoderInterface(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID && codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorGetEncoderInterfaceAptx(p_codec_info);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID && codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorGetEncoderInterfaceAptxHd(p_codec_info);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_VendorGetEncoderInterfaceLdac(p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorGetEncoderInterfaceOpus(p_codec_info);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_VendorGetEncoderInterfaceLhdcV5(p_codec_info);
  }

  // Add checks based on <vendor_id, codec_id>

  return NULL;
}

const tA2DP_DECODER_INTERFACE* A2DP_VendorGetDecoderInterface(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Add checks based on <vendor_id, codec_id>
  // NOTE: Should be done only for local Sink codecs.

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorGetDecoderInterfaceOpus(p_codec_info);
  }

  return NULL;
}

bool A2DP_VendorAdjustCodec(uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID && codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorAdjustCodecAptx(p_codec_info);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID && codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorAdjustCodecAptxHd(p_codec_info);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_VendorAdjustCodecLdac(p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorAdjustCodecOpus(p_codec_info);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_VendorAdjustCodecLhdcV5(p_codec_info);
  }

  // Add checks based on <vendor_id, codec_id>

  return false;
}

btav_a2dp_codec_index_t A2DP_VendorSourceCodecIndex(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID && codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return BTAV_A2DP_CODEC_INDEX_SOURCE_APTX;
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID && codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD;
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC;
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS;
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV5;
  }

  // Add checks based on <vendor_id, codec_id>

  return BTAV_A2DP_CODEC_INDEX_MAX;
}

btav_a2dp_codec_index_t A2DP_VendorSinkCodecIndex(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Add checks based on <vendor_id, codec_id>
  // NOTE: Should be done only for local Sink codecs.

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return BTAV_A2DP_CODEC_INDEX_SINK_OPUS;
  }

  return BTAV_A2DP_CODEC_INDEX_MAX;
}

bool A2DP_VendorInitCodecConfig(btav_a2dp_codec_index_t codec_index, AvdtpSepConfig* p_cfg) {
  // Add checks based on codec_index
  switch (codec_index) {
    case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
    case BTAV_A2DP_CODEC_INDEX_SINK_SBC:
    case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
    case BTAV_A2DP_CODEC_INDEX_SINK_AAC:
      break;  // These are not vendor-specific codecs
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
      return A2DP_VendorInitCodecConfigAptx(p_cfg);
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD:
      return A2DP_VendorInitCodecConfigAptxHd(p_cfg);
    case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC:
      return A2DP_VendorInitCodecConfigLdac(p_cfg);
    case BTAV_A2DP_CODEC_INDEX_SOURCE_LC3:
      break;  // not implemented
    case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS:
      return A2DP_VendorInitCodecConfigOpus(p_cfg);
    case BTAV_A2DP_CODEC_INDEX_SINK_OPUS:
      return A2DP_VendorInitCodecConfigOpusSink(p_cfg);
    case BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV5:
      return A2DP_VendorInitCodecConfigLhdcV5(p_cfg);
    // Add a switch statement for each vendor-specific codec
    case BTAV_A2DP_CODEC_INDEX_MAX:
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN:
    case BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN:
      break;
  }

  return false;
}

std::string A2DP_VendorCodecInfoString(const uint8_t* p_codec_info) {
  uint32_t vendor_id = A2DP_VendorCodecGetVendorId(p_codec_info);
  uint16_t codec_id = A2DP_VendorCodecGetCodecId(p_codec_info);

  // Check for aptX
  if (vendor_id == A2DP_APTX_VENDOR_ID && codec_id == A2DP_APTX_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorCodecInfoStringAptx(p_codec_info);
  }

  // Check for aptX-HD
  if (vendor_id == A2DP_APTX_HD_VENDOR_ID && codec_id == A2DP_APTX_HD_CODEC_ID_BLUETOOTH) {
    return A2DP_VendorCodecInfoStringAptxHd(p_codec_info);
  }

  // Check for LDAC
  if (vendor_id == A2DP_LDAC_VENDOR_ID && codec_id == A2DP_LDAC_CODEC_ID) {
    return A2DP_VendorCodecInfoStringLdac(p_codec_info);
  }

  // Check for Opus
  if (vendor_id == A2DP_OPUS_VENDOR_ID && codec_id == A2DP_OPUS_CODEC_ID) {
    return A2DP_VendorCodecInfoStringOpus(p_codec_info);
  }

  // Check for LHDCv5
  if (vendor_id == A2DP_LHDC_VENDOR_ID && codec_id == A2DP_LHDCV5_CODEC_ID) {
    return A2DP_VendorCodecInfoStringLhdcV5(p_codec_info);
  }

  // Add checks based on <vendor_id, codec_id>

  return std::format("Unsupported codec vendor_id: 0x{:x} codec_id: 0x{:x}, codec info: {}",
                     vendor_id, codec_id, DumpAvdtCodecInfo(p_codec_info));
}
