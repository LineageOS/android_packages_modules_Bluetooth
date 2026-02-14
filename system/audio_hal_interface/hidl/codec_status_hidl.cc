/*
 * Copyright 2019 The Android Open Source Project
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

#define LOG_TAG "BTAudioCodecStatusHIDL"

#include "codec_status_hidl.h"

#include <bluetooth/log.h>

#include <vector>

#include "a2dp_encoding.h"
#include "bta/include/bta_av_api.h"
#include "client_interface_hidl.h"
#include "stack/include/a2dp_aac_constants.h"
#include "stack/include/a2dp_sbc_constants.h"
#include "stack/include/a2dp_vendor_aptx_constants.h"
#include "stack/include/a2dp_vendor_aptx_hd_constants.h"
#include "stack/include/a2dp_vendor_ldac_constants.h"

namespace bluetooth::audio::hidl::codec {

using ::android::hardware::bluetooth::audio::V2_0::AacObjectType;
using ::android::hardware::bluetooth::audio::V2_0::AacParameters;
using ::android::hardware::bluetooth::audio::V2_0::AacVariableBitRate;
using ::android::hardware::bluetooth::audio::V2_0::AptxParameters;
using ::android::hardware::bluetooth::audio::V2_0::AudioCapabilities;
using ::android::hardware::bluetooth::audio::V2_0::BitsPerSample;
using ::android::hardware::bluetooth::audio::V2_0::ChannelMode;
using ::android::hardware::bluetooth::audio::V2_0::CodecType;
using ::android::hardware::bluetooth::audio::V2_0::LdacChannelMode;
using ::android::hardware::bluetooth::audio::V2_0::LdacParameters;
using ::android::hardware::bluetooth::audio::V2_0::LdacQualityIndex;
using ::android::hardware::bluetooth::audio::V2_0::SampleRate;
using ::android::hardware::bluetooth::audio::V2_0::SbcAllocMethod;
using ::android::hardware::bluetooth::audio::V2_0::SbcBlockLength;
using ::android::hardware::bluetooth::audio::V2_0::SbcChannelMode;
using ::android::hardware::bluetooth::audio::V2_0::SbcNumSubbands;
using ::android::hardware::bluetooth::audio::V2_0::SbcParameters;

using ::bluetooth::audio::a2dp::ahal_codec_configuration;

namespace {

// capabilities from BluetoothAudioSinkClientInterface::GetAudioCapabilities()
std::vector<AudioCapabilities> audio_hal_capabilities(0);
// capabilities that audio HAL supports and frameworks / Bluetooth SoC / runtime
// preference would like to use.
std::vector<AudioCapabilities> offloading_preference(0);

bool sbc_offloading_capability_match(const SbcParameters& sbc_capability,
                                     const SbcParameters& sbc_config) {
  if ((static_cast<SampleRate>(sbc_capability.sampleRate & sbc_config.sampleRate) ==
       SampleRate::RATE_UNKNOWN) ||
      (static_cast<SbcChannelMode>(sbc_capability.channelMode & sbc_config.channelMode) ==
       SbcChannelMode::UNKNOWN) ||
      (static_cast<SbcBlockLength>(sbc_capability.blockLength & sbc_config.blockLength) ==
       static_cast<SbcBlockLength>(0)) ||
      (static_cast<SbcNumSubbands>(sbc_capability.numSubbands & sbc_config.numSubbands) ==
       static_cast<SbcNumSubbands>(0)) ||
      (static_cast<SbcAllocMethod>(sbc_capability.allocMethod & sbc_config.allocMethod) ==
       static_cast<SbcAllocMethod>(0)) ||
      (static_cast<BitsPerSample>(sbc_capability.bitsPerSample & sbc_config.bitsPerSample) ==
       BitsPerSample::BITS_UNKNOWN) ||
      (sbc_config.minBitpool < sbc_capability.minBitpool ||
       sbc_config.maxBitpool < sbc_config.minBitpool ||
       sbc_capability.maxBitpool < sbc_config.maxBitpool)) {
    log::warn("software codec={} capability={}", toString(sbc_config), toString(sbc_capability));
    return false;
  }
  log::verbose("offloading codec={} capability={}", toString(sbc_config), toString(sbc_capability));
  return true;
}

bool aac_offloading_capability_match(const AacParameters& aac_capability,
                                     const AacParameters& aac_config) {
  if ((static_cast<AacObjectType>(aac_capability.objectType & aac_config.objectType) ==
       static_cast<AacObjectType>(0)) ||
      (static_cast<SampleRate>(aac_capability.sampleRate & aac_config.sampleRate) ==
       SampleRate::RATE_UNKNOWN) ||
      (static_cast<ChannelMode>(aac_capability.channelMode & aac_config.channelMode) ==
       ChannelMode::UNKNOWN) ||
      (aac_capability.variableBitRateEnabled != AacVariableBitRate::ENABLED &&
       aac_config.variableBitRateEnabled != AacVariableBitRate::DISABLED) ||
      (static_cast<BitsPerSample>(aac_capability.bitsPerSample & aac_config.bitsPerSample) ==
       BitsPerSample::BITS_UNKNOWN)) {
    log::warn("software codec={} capability={}", toString(aac_config), toString(aac_capability));
    return false;
  }
  log::verbose("offloading codec={} capability={}", toString(aac_config), toString(aac_capability));
  return true;
}

bool aptx_offloading_capability_match(const AptxParameters& aptx_capability,
                                      const AptxParameters& aptx_config) {
  if ((static_cast<SampleRate>(aptx_capability.sampleRate & aptx_config.sampleRate) ==
       SampleRate::RATE_UNKNOWN) ||
      (static_cast<ChannelMode>(aptx_capability.channelMode & aptx_config.channelMode) ==
       ChannelMode::UNKNOWN) ||
      (static_cast<BitsPerSample>(aptx_capability.bitsPerSample & aptx_config.bitsPerSample) ==
       BitsPerSample::BITS_UNKNOWN)) {
    log::warn("software codec={} capability={}", toString(aptx_config), toString(aptx_capability));
    return false;
  }
  log::verbose("offloading codec={} capability={}", toString(aptx_config),
               toString(aptx_capability));
  return true;
}

bool ldac_offloading_capability_match(const LdacParameters& ldac_capability,
                                      const LdacParameters& ldac_config) {
  if ((static_cast<SampleRate>(ldac_capability.sampleRate & ldac_config.sampleRate) ==
       SampleRate::RATE_UNKNOWN) ||
      (static_cast<LdacChannelMode>(ldac_capability.channelMode & ldac_config.channelMode) ==
       LdacChannelMode::UNKNOWN) ||
      (static_cast<BitsPerSample>(ldac_capability.bitsPerSample & ldac_config.bitsPerSample) ==
       BitsPerSample::BITS_UNKNOWN)) {
    log::warn("software codec={} capability={}", toString(ldac_config), toString(ldac_capability));
    return false;
  }
  log::verbose("offloading codec={} capability={}", toString(ldac_config),
               toString(ldac_capability));
  return true;
}

// Check whether this codec is supported by the audio HAL and is allowed to use
// by preference of framework / Bluetooth SoC / runtime property.
bool IsCodecOffloadingEnabled(const CodecConfiguration& codec_config) {
  for (auto preference : offloading_preference) {
    if (codec_config.codecType != preference.codecCapabilities().codecType) {
      continue;
    }
    auto codec_capability = preference.codecCapabilities();
    switch (codec_capability.codecType) {
      case CodecType::SBC: {
        auto sbc_capability = codec_capability.capabilities.sbcCapabilities();
        auto sbc_config = codec_config.config.sbcConfig();
        return sbc_offloading_capability_match(sbc_capability, sbc_config);
      }
      case CodecType::AAC: {
        auto aac_capability = codec_capability.capabilities.aacCapabilities();
        auto aac_config = codec_config.config.aacConfig();
        return aac_offloading_capability_match(aac_capability, aac_config);
      }
      case CodecType::APTX:
        [[fallthrough]];
      case CodecType::APTX_HD: {
        auto aptx_capability = codec_capability.capabilities.aptxCapabilities();
        auto aptx_config = codec_config.config.aptxConfig();
        return aptx_offloading_capability_match(aptx_capability, aptx_config);
      }
      case CodecType::LDAC: {
        auto ldac_capability = codec_capability.capabilities.ldacCapabilities();
        auto ldac_config = codec_config.config.ldacConfig();
        return ldac_offloading_capability_match(ldac_capability, ldac_config);
      }
      case CodecType::UNKNOWN:
        [[fallthrough]];
      default:
        log::error("Unknown codecType={}", toString(codec_capability.codecType));
        return false;
    }
  }
  log::info("software codec={}", toString(codec_config));
  return false;
}

static SampleRate A2dpCodecToHalSampleRate(const btav_a2dp_codec_config_t& a2dp_codec_config) {
  switch (a2dp_codec_config.sample_rate) {
    case BTAV_A2DP_CODEC_SAMPLE_RATE_44100:
      return SampleRate::RATE_44100;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_48000:
      return SampleRate::RATE_48000;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_88200:
      return SampleRate::RATE_88200;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_96000:
      return SampleRate::RATE_96000;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_176400:
      return SampleRate::RATE_176400;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_192000:
      return SampleRate::RATE_192000;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_16000:
      return SampleRate::RATE_16000;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_24000:
      return SampleRate::RATE_24000;
    default:
      return SampleRate::RATE_UNKNOWN;
  }
}

static BitsPerSample A2dpCodecToHalBitsPerSample(
        const btav_a2dp_codec_config_t& a2dp_codec_config) {
  switch (a2dp_codec_config.bits_per_sample) {
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16:
      return BitsPerSample::BITS_16;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24:
      return BitsPerSample::BITS_24;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32:
      return BitsPerSample::BITS_32;
    default:
      return BitsPerSample::BITS_UNKNOWN;
  }
}

static ChannelMode A2dpCodecToHalChannelMode(const btav_a2dp_codec_config_t& a2dp_codec_config) {
  switch (a2dp_codec_config.channel_mode) {
    case BTAV_A2DP_CODEC_CHANNEL_MODE_MONO:
      return ChannelMode::MONO;
    case BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO:
      return ChannelMode::STEREO;
    default:
      return ChannelMode::UNKNOWN;
  }
}

static bool A2dpSbcToHalConfig(const ahal_codec_configuration& config,
                               CodecConfiguration* codec_config) {
  if (config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SOURCE_SBC &&
      config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SINK_SBC) {
    *codec_config = {};
    return false;
  }
  codec_config->codecType = CodecType::SBC;
  codec_config->config.sbcConfig({});
  auto sbc_config = codec_config->config.sbcConfig();
  sbc_config.sampleRate = A2dpCodecToHalSampleRate(config.codec_config);
  if (sbc_config.sampleRate == SampleRate::RATE_UNKNOWN) {
    log::error("Unknown SBC sample_rate={}", config.codec_config.sample_rate);
    return false;
  }
  uint8_t channel_mode =
          config.codec_specific_information_elements[A2DP_SBC_IE_CH_MD_IDX] & A2DP_SBC_IE_CH_MD_MSK;
  switch (channel_mode) {
    case A2DP_SBC_IE_CH_MD_JOINT:
      sbc_config.channelMode = SbcChannelMode::JOINT_STEREO;
      break;
    case A2DP_SBC_IE_CH_MD_STEREO:
      sbc_config.channelMode = SbcChannelMode::STEREO;
      break;
    case A2DP_SBC_IE_CH_MD_DUAL:
      sbc_config.channelMode = SbcChannelMode::DUAL;
      break;
    case A2DP_SBC_IE_CH_MD_MONO:
      sbc_config.channelMode = SbcChannelMode::MONO;
      break;
    default:
      log::error("Unknown SBC channel_mode={}", channel_mode);
      sbc_config.channelMode = SbcChannelMode::UNKNOWN;
      return false;
  }
  uint8_t block_length = config.codec_specific_information_elements[A2DP_SBC_IE_BLOCKS_IDX] &
                         A2DP_SBC_IE_BLOCKS_MSK;
  switch (block_length) {
    case A2DP_SBC_IE_BLOCKS_4:
      sbc_config.blockLength = SbcBlockLength::BLOCKS_4;
      break;
    case A2DP_SBC_IE_BLOCKS_8:
      sbc_config.blockLength = SbcBlockLength::BLOCKS_8;
      break;
    case A2DP_SBC_IE_BLOCKS_12:
      sbc_config.blockLength = SbcBlockLength::BLOCKS_12;
      break;
    case A2DP_SBC_IE_BLOCKS_16:
      sbc_config.blockLength = SbcBlockLength::BLOCKS_16;
      break;
    default:
      log::error("Unknown SBC block_length={}", block_length);
      return false;
  }
  uint8_t sub_bands = config.codec_specific_information_elements[A2DP_SBC_IE_SUBBAND_IDX] &
                      A2DP_SBC_IE_SUBBAND_MSK;
  switch (sub_bands) {
    case A2DP_SBC_IE_SUBBAND_4:
      sbc_config.numSubbands = SbcNumSubbands::SUBBAND_4;
      break;
    case A2DP_SBC_IE_SUBBAND_8:
      sbc_config.numSubbands = SbcNumSubbands::SUBBAND_8;
      break;
    default:
      log::error("Unknown SBC Subbands={}", sub_bands);
      return false;
  }
  uint8_t alloc_method = config.codec_specific_information_elements[A2DP_SBC_IE_ALLOC_MD_IDX] &
                         A2DP_SBC_IE_ALLOC_MD_MSK;
  switch (alloc_method) {
    case A2DP_SBC_IE_ALLOC_MD_S:
      sbc_config.allocMethod = SbcAllocMethod::ALLOC_MD_S;
      break;
    case A2DP_SBC_IE_ALLOC_MD_L:
      sbc_config.allocMethod = SbcAllocMethod::ALLOC_MD_L;
      break;
    default:
      log::error("Unknown SBC alloc_method={}", alloc_method);
      return false;
  }
  sbc_config.minBitpool = config.codec_specific_information_elements[A2DP_SBC_IE_MIN_BITPOOL_IDX];
  sbc_config.maxBitpool = config.codec_specific_information_elements[A2DP_SBC_IE_MAX_BITPOOL_IDX];
  sbc_config.bitsPerSample = A2dpCodecToHalBitsPerSample(config.codec_config);
  if (sbc_config.bitsPerSample == BitsPerSample::BITS_UNKNOWN) {
    log::error("Unknown SBC bits_per_sample={}", config.codec_config.bits_per_sample);
    return false;
  }
  codec_config->config.sbcConfig(sbc_config);
  return true;
}

static bool A2dpAacToHalConfig(const ahal_codec_configuration& config,
                               CodecConfiguration* codec_config) {
  if (config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SOURCE_AAC &&
      config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SINK_AAC) {
    *codec_config = {};
    return false;
  }
  codec_config->codecType = CodecType::AAC;
  codec_config->config.aacConfig({});
  auto aac_config = codec_config->config.aacConfig();
  uint8_t object_type = config.codec_specific_information_elements[A2DP_AAC_OBJECT_TYPE_IDX];
  switch (object_type) {
    case A2DP_AAC_OBJECT_TYPE_MPEG2_LC:
      aac_config.objectType = AacObjectType::MPEG2_LC;
      break;
    case A2DP_AAC_OBJECT_TYPE_MPEG4_LC:
      aac_config.objectType = AacObjectType::MPEG4_LC;
      break;
    case A2DP_AAC_OBJECT_TYPE_MPEG4_LTP:
      aac_config.objectType = AacObjectType::MPEG4_LTP;
      break;
    case A2DP_AAC_OBJECT_TYPE_MPEG4_SCALABLE:
      aac_config.objectType = AacObjectType::MPEG4_SCALABLE;
      break;
    default:
      log::error("Unknown AAC object_type={}", object_type);
      return false;
  }
  aac_config.sampleRate = A2dpCodecToHalSampleRate(config.codec_config);
  if (aac_config.sampleRate == SampleRate::RATE_UNKNOWN) {
    log::error("Unknown AAC sample_rate={}", config.codec_config.sample_rate);
    return false;
  }
  aac_config.channelMode = A2dpCodecToHalChannelMode(config.codec_config);
  if (aac_config.channelMode == ChannelMode::UNKNOWN) {
    log::error("Unknown AAC channel_mode={}", config.codec_config.channel_mode);
    return false;
  }
  uint8_t vbr_enabled = config.codec_specific_information_elements[A2DP_AAC_VARIABLE_BIT_RATE_IDX] &
                        A2DP_AAC_VARIABLE_BIT_RATE_MASK;
  switch (vbr_enabled) {
    case A2DP_AAC_VARIABLE_BIT_RATE_ENABLED:
      aac_config.variableBitRateEnabled = AacVariableBitRate::ENABLED;
      break;
    case A2DP_AAC_VARIABLE_BIT_RATE_DISABLED:
      aac_config.variableBitRateEnabled = AacVariableBitRate::DISABLED;
      break;
    default:
      log::error("Unknown AAC VBR={}", vbr_enabled);
      return false;
  }
  aac_config.bitsPerSample = A2dpCodecToHalBitsPerSample(config.codec_config);
  if (aac_config.bitsPerSample == BitsPerSample::BITS_UNKNOWN) {
    log::error("Unknown AAC bits_per_sample={}", config.codec_config.bits_per_sample);
    return false;
  }
  codec_config->config.aacConfig(aac_config);
  return true;
}

static bool A2dpAptxToHalConfig(const ahal_codec_configuration& config,
                                CodecConfiguration* codec_config) {
  if (config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SOURCE_APTX &&
      config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD) {
    *codec_config = {};
    return false;
  }
  if (config.codec_config.codec_type == BTAV_A2DP_CODEC_INDEX_SOURCE_APTX) {
    codec_config->codecType = CodecType::APTX;
  } else {
    codec_config->codecType = CodecType::APTX_HD;
  }
  codec_config->config.aptxConfig({});
  auto aptx_config = codec_config->config.aptxConfig();
  aptx_config.sampleRate = A2dpCodecToHalSampleRate(config.codec_config);
  if (aptx_config.sampleRate == SampleRate::RATE_UNKNOWN) {
    log::error("Unknown aptX sample_rate={}", config.codec_config.sample_rate);
    return false;
  }
  aptx_config.channelMode = A2dpCodecToHalChannelMode(config.codec_config);
  if (aptx_config.channelMode == ChannelMode::UNKNOWN) {
    log::error("Unknown aptX channel_mode={}", config.codec_config.channel_mode);
    return false;
  }
  aptx_config.bitsPerSample = A2dpCodecToHalBitsPerSample(config.codec_config);
  if (aptx_config.bitsPerSample == BitsPerSample::BITS_UNKNOWN) {
    log::error("Unknown aptX bits_per_sample={}", config.codec_config.bits_per_sample);
    return false;
  }
  codec_config->config.aptxConfig(aptx_config);
  return true;
}

bool A2dpLdacToHalConfig(const ahal_codec_configuration& config, CodecConfiguration* codec_config) {
  if (config.codec_config.codec_type != BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC) {
    codec_config = {};
    return false;
  }

  codec_config->codecType = CodecType::LDAC;
  codec_config->config.ldacConfig({});
  auto ldac_config = codec_config->config.ldacConfig();
  ldac_config.sampleRate = A2dpCodecToHalSampleRate(config.codec_config);
  if (ldac_config.sampleRate == SampleRate::RATE_UNKNOWN) {
    log::error("Unknown LDAC sample_rate={}", config.codec_config.sample_rate);
    return false;
  }
  int channel_mode = config.codec_specific_information_elements[A2DP_LDAC_CHANNEL_MODE_IDX];
  switch (channel_mode) {
    case A2DP_LDAC_CHANNEL_MODE_STEREO:
      ldac_config.channelMode = LdacChannelMode::STEREO;
      break;
    case A2DP_LDAC_CHANNEL_MODE_DUAL:
      ldac_config.channelMode = LdacChannelMode::DUAL;
      break;
    case A2DP_LDAC_CHANNEL_MODE_MONO:
      ldac_config.channelMode = LdacChannelMode::MONO;
      break;
    default:
      log::error("Unknown LDAC channel_mode={}", channel_mode);
      ldac_config.channelMode = LdacChannelMode::UNKNOWN;
      return false;
  }
  int ldac_quality = A2DP_LDAC_QUALITY_ABR_OFFLOAD;
  if (config.codec_config.codec_specific_1 != 0) {
    switch (config.codec_config.codec_specific_1 % 10) {
      case 0:
        ldac_quality = A2DP_LDAC_QUALITY_HIGH;
        break;
      case 1:
        ldac_quality = A2DP_LDAC_QUALITY_MID;
        break;
      case 2:
        ldac_quality = A2DP_LDAC_QUALITY_LOW;
        break;
      default:
        ldac_quality = A2DP_LDAC_QUALITY_ABR_OFFLOAD;
        break;
    }
  }
  switch (ldac_quality) {
    case A2DP_LDAC_QUALITY_HIGH:
      ldac_config.qualityIndex = LdacQualityIndex::QUALITY_HIGH;
      break;
    case A2DP_LDAC_QUALITY_MID:
      ldac_config.qualityIndex = LdacQualityIndex::QUALITY_MID;
      break;
    case A2DP_LDAC_QUALITY_LOW:
      ldac_config.qualityIndex = LdacQualityIndex::QUALITY_LOW;
      break;
    case A2DP_LDAC_QUALITY_ABR_OFFLOAD:
      ldac_config.qualityIndex = LdacQualityIndex::QUALITY_ABR;
      break;
    default:
      log::error("Unknown LDAC QualityIndex={}", ldac_quality);
      return false;
  }
  ldac_config.bitsPerSample = A2dpCodecToHalBitsPerSample(config.codec_config);
  if (ldac_config.bitsPerSample == BitsPerSample::BITS_UNKNOWN) {
    log::error("Unknown LDAC bits_per_sample={}", config.codec_config.bits_per_sample);
    return false;
  }
  codec_config->config.ldacConfig(ldac_config);
  return true;
}

}  // namespace

bool UpdateOffloadingCapabilities(
        const std::vector<btav_a2dp_codec_config_t>& framework_preference) {
  audio_hal_capabilities = BluetoothAudioSinkClientInterface::GetAudioCapabilities(
          SessionType::A2DP_HARDWARE_OFFLOAD_DATAPATH);
  uint32_t codec_type_masks = static_cast<uint32_t>(CodecType::UNKNOWN);
  for (auto preference : framework_preference) {
    switch (preference.codec_type) {
      case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
        codec_type_masks |= CodecType::SBC;
        break;
      case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
        codec_type_masks |= CodecType::AAC;
        break;
      case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
        codec_type_masks |= CodecType::APTX;
        break;
      case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD:
        codec_type_masks |= CodecType::APTX_HD;
        break;
      case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC:
        codec_type_masks |= CodecType::LDAC;
        break;
      case BTAV_A2DP_CODEC_INDEX_SOURCE_LC3:
        log::warn("Ignore source codec_type={}, not supported", preference.codec_type);
        break;
      case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS:
        log::warn("Ignore source codec_type={}, not supported on HIDL", preference.codec_type);
        break;
      case BTAV_A2DP_CODEC_INDEX_SINK_SBC:
      case BTAV_A2DP_CODEC_INDEX_SINK_AAC:
      case BTAV_A2DP_CODEC_INDEX_SINK_OPUS:
        log::warn("Ignore sink codec_type={}", preference.codec_type);
        break;
      case BTAV_A2DP_CODEC_INDEX_MAX:
      default:
        log::error("Unknown codec_type={}", preference.codec_type);
        return false;
    }
  }
  offloading_preference.clear();
  for (auto capability : audio_hal_capabilities) {
    if (static_cast<CodecType>(capability.codecCapabilities().codecType & codec_type_masks) !=
        CodecType::UNKNOWN) {
      log::info("enabled offloading capability={}", toString(capability));
      offloading_preference.push_back(capability);
    } else {
      log::info("disabled offloading capability={}", toString(capability));
    }
  }
  // TODO: Bluetooth SoC and runtime property
  return true;
}

bool getHalPcmConfiguration(const ahal_codec_configuration& config,
                            PcmParameters* pcm_configuration) {
  if (pcm_configuration == nullptr) {
    return false;
  }

  pcm_configuration->sampleRate = A2dpCodecToHalSampleRate(config.codec_config);
  pcm_configuration->bitsPerSample = A2dpCodecToHalBitsPerSample(config.codec_config);
  pcm_configuration->channelMode = A2dpCodecToHalChannelMode(config.codec_config);

  return pcm_configuration->sampleRate != SampleRate::RATE_UNKNOWN &&
         pcm_configuration->bitsPerSample != BitsPerSample::BITS_UNKNOWN &&
         pcm_configuration->channelMode != ChannelMode::UNKNOWN;
}

bool getHalCodecConfiguration(const ahal_codec_configuration& config,
                              CodecConfiguration* codec_configuration) {
  if (codec_configuration == nullptr) {
    return false;
  }

  codec_configuration->encodedAudioBitrate = config.codec_bitrate;
  codec_configuration->peerMtu = config.peer_mtu;

  switch (config.codec_config.codec_type) {
    case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
    case BTAV_A2DP_CODEC_INDEX_SINK_SBC:
      return A2dpSbcToHalConfig(config, codec_configuration) &&
             IsCodecOffloadingEnabled(*codec_configuration);

    case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
    case BTAV_A2DP_CODEC_INDEX_SINK_AAC:
      return A2dpAacToHalConfig(config, codec_configuration) &&
             IsCodecOffloadingEnabled(*codec_configuration);

    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD:
      return A2dpAptxToHalConfig(config, codec_configuration) &&
             IsCodecOffloadingEnabled(*codec_configuration);

    case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC:
      return A2dpLdacToHalConfig(config, codec_configuration) &&
             IsCodecOffloadingEnabled(*codec_configuration);

    case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS:
    case BTAV_A2DP_CODEC_INDEX_MAX:
    default:
      break;
  }

  return false;
}

}  // namespace bluetooth::audio::hidl::codec
