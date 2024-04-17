/******************************************************************************
 *
 *  Copyright 2015 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "device/include/esco_parameters.h"

#include <android_bluetooth_flags.h>
#include <bluetooth/log.h>

#include "hci/controller_interface.h"
#include "main/shim/entry.h"

using namespace bluetooth;

static const enh_esco_params_t default_esco_parameters[ESCO_NUM_CODECS] = {
    // CVSD D1
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_64K_RATE,
        .output_bandwidth = INPUT_OUTPUT_64K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 0xFFFF,  // Don't care
        .packet_types = (ESCO_PKT_TYPES_MASK_HV1 | ESCO_PKT_TYPES_MASK_HV2 |
                         ESCO_PKT_TYPES_MASK_HV3),
        .retransmission_effort = ESCO_RETRANSMISSION_OFF,
        .coding_format = ESCO_CODING_FORMAT_CVSD,
    },  // CVSD S1
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_64K_RATE,
        .output_bandwidth = INPUT_OUTPUT_64K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 7,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_HV1 | ESCO_PKT_TYPES_MASK_HV2 |
             ESCO_PKT_TYPES_MASK_HV3 | ESCO_PKT_TYPES_MASK_EV3 |
             ESCO_PKT_TYPES_MASK_EV4 | ESCO_PKT_TYPES_MASK_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV3 | ESCO_PKT_TYPES_MASK_NO_2_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV5),
        .retransmission_effort = ESCO_RETRANSMISSION_POWER,
        .coding_format = ESCO_CODING_FORMAT_CVSD,
    },
    // CVSD S3
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_64K_RATE,
        .output_bandwidth = INPUT_OUTPUT_64K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 10,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_HV1 | ESCO_PKT_TYPES_MASK_HV2 |
             ESCO_PKT_TYPES_MASK_HV3 | ESCO_PKT_TYPES_MASK_EV3 |
             ESCO_PKT_TYPES_MASK_EV4 | ESCO_PKT_TYPES_MASK_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV3 | ESCO_PKT_TYPES_MASK_NO_2_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV5),
        .retransmission_effort = ESCO_RETRANSMISSION_POWER,
        .coding_format = ESCO_CODING_FORMAT_CVSD,
    },
    // CVSD S4
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_CVSD,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_64K_RATE,
        .output_bandwidth = INPUT_OUTPUT_64K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 12,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_HV1 | ESCO_PKT_TYPES_MASK_HV2 |
             ESCO_PKT_TYPES_MASK_HV3 | ESCO_PKT_TYPES_MASK_EV3 |
             ESCO_PKT_TYPES_MASK_EV4 | ESCO_PKT_TYPES_MASK_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV3 | ESCO_PKT_TYPES_MASK_NO_2_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV5),
        .retransmission_effort = ESCO_RETRANSMISSION_QUALITY,
        .coding_format = ESCO_CODING_FORMAT_CVSD,
    },
    // mSBC T1
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_MSBC,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_MSBC,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_128K_RATE,
        .output_bandwidth = INPUT_OUTPUT_128K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 8,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_EV3 | ESCO_PKT_TYPES_MASK_NO_3_EV3 |
             ESCO_PKT_TYPES_MASK_NO_2_EV5 | ESCO_PKT_TYPES_MASK_NO_3_EV5 |
             ESCO_PKT_TYPES_MASK_NO_2_EV3),
        .retransmission_effort = ESCO_RETRANSMISSION_QUALITY,
        .coding_format = ESCO_CODING_FORMAT_MSBC,
    },
    // mSBC T2
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_MSBC,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_MSBC,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_128K_RATE,
        .output_bandwidth = INPUT_OUTPUT_128K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 13,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_EV3 | ESCO_PKT_TYPES_MASK_NO_3_EV3 |
             ESCO_PKT_TYPES_MASK_NO_2_EV5 | ESCO_PKT_TYPES_MASK_NO_3_EV5),
        .retransmission_effort = ESCO_RETRANSMISSION_QUALITY,
        .coding_format = ESCO_CODING_FORMAT_MSBC,
    },
    // LC3 T1
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_LC3,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_LC3,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_256K_RATE,
        .output_bandwidth = INPUT_OUTPUT_256K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 8,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_EV3 | ESCO_PKT_TYPES_MASK_NO_3_EV3 |
             ESCO_PKT_TYPES_MASK_NO_2_EV5 | ESCO_PKT_TYPES_MASK_NO_3_EV5 |
             ESCO_PKT_TYPES_MASK_NO_2_EV3),
        .retransmission_effort = ESCO_RETRANSMISSION_QUALITY,
        .coding_format = ESCO_CODING_FORMAT_LC3,
    },
    // LC3 T2
    {
        .transmit_bandwidth = TXRX_64KBITS_RATE,
        .receive_bandwidth = TXRX_64KBITS_RATE,
        .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_LC3,
                                   .company_id = 0x0000,
                                   .vendor_specific_codec_id = 0x0000},
        .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_LC3,
                                  .company_id = 0x0000,
                                  .vendor_specific_codec_id = 0x0000},
        .transmit_codec_frame_size = 60,
        .receive_codec_frame_size = 60,
        .input_bandwidth = INPUT_OUTPUT_256K_RATE,
        .output_bandwidth = INPUT_OUTPUT_256K_RATE,
        .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                .company_id = 0x0000,
                                .vendor_specific_codec_id = 0x0000},
        .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                                 .company_id = 0x0000,
                                 .vendor_specific_codec_id = 0x0000},
        .input_coded_data_size = 16,
        .output_coded_data_size = 16,
        .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
        .input_pcm_payload_msb_position = 0,
        .output_pcm_payload_msb_position = 0,
        .input_data_path = ESCO_DATA_PATH_PCM,
        .output_data_path = ESCO_DATA_PATH_PCM,
        .input_transport_unit_size = 0x00,
        .output_transport_unit_size = 0x00,
        .max_latency_ms = 13,
        .packet_types =
            (ESCO_PKT_TYPES_MASK_NO_3_EV3 | ESCO_PKT_TYPES_MASK_NO_2_EV5 |
             ESCO_PKT_TYPES_MASK_NO_3_EV5),
        .retransmission_effort = ESCO_RETRANSMISSION_QUALITY,
        .coding_format = ESCO_CODING_FORMAT_LC3,
    },
    // aptX Voice SWB
    {.transmit_bandwidth = TXRX_64KBITS_RATE,
     .receive_bandwidth = TXRX_64KBITS_RATE,
     .transmit_coding_format = {.coding_format = ESCO_CODING_FORMAT_VS,
                                .company_id = 0x000A,
                                .vendor_specific_codec_id = 0x0000},
     .receive_coding_format = {.coding_format = ESCO_CODING_FORMAT_VS,
                               .company_id = 0x000A,
                               .vendor_specific_codec_id = 0x0000},
     .transmit_codec_frame_size = 60,
     .receive_codec_frame_size = 60,
     .input_bandwidth = INPUT_OUTPUT_128K_RATE,
     .output_bandwidth = INPUT_OUTPUT_128K_RATE,
     .input_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                             .company_id = 0x0000,
                             .vendor_specific_codec_id = 0x0000},
     .output_coding_format = {.coding_format = ESCO_CODING_FORMAT_LINEAR,
                              .company_id = 0x0000,
                              .vendor_specific_codec_id = 0x0000},
     .input_coded_data_size = 16,
     .output_coded_data_size = 16,
     .input_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
     .output_pcm_data_format = ESCO_PCM_DATA_FORMAT_2_COMP,
     .input_pcm_payload_msb_position = 0,
     .output_pcm_payload_msb_position = 0,
     .input_data_path = ESCO_DATA_PATH_PCM,
     .output_data_path = ESCO_DATA_PATH_PCM,
     .input_transport_unit_size = 0x00,
     .output_transport_unit_size = 0x00,
     .max_latency_ms = 14,
     .packet_types = 0x0380,
     .retransmission_effort = ESCO_RETRANSMISSION_QUALITY}};

enh_esco_params_t esco_parameters_for_codec(esco_codec_t codec, bool offload) {
  log::assert_that((int)codec >= 0, "codec index {}< 0", (int)codec);
  log::assert_that(codec < ESCO_NUM_CODECS, "codec index {} > {}", (int)codec,
                   ESCO_NUM_CODECS);

  std::vector<uint8_t> codecIds;
  if (IS_FLAG_ENABLED(use_dsp_codec_when_controller_does_not_support)) {
    auto controller = bluetooth::shim::GetController();
    if (controller == nullptr) {
      log::warn("controller is null");
    } else {
      codecIds = controller->GetLocalSupportedBrEdrCodecIds();
      if (std::find(codecIds.begin(), codecIds.end(), ESCO_CODING_FORMAT_LC3) ==
          codecIds.end()) {
        if (codec == ESCO_CODEC_LC3_T1 || codec == ESCO_CODEC_LC3_T2) {
          log::info("BT controller does not support LC3 codec, use DSP codec");
          enh_esco_params_t param = default_esco_parameters[codec];
          param.input_coding_format.coding_format = ESCO_CODING_FORMAT_LC3;
          param.output_coding_format.coding_format = ESCO_CODING_FORMAT_LC3;
          param.input_bandwidth = TXRX_64KBITS_RATE;
          param.output_bandwidth = TXRX_64KBITS_RATE;
          return param;
        }
      }
    }
  }

  if (offload) {
    if (codec == ESCO_CODEC_SWB_Q0 || codec == ESCO_CODEC_SWB_Q1 ||
        codec == ESCO_CODEC_SWB_Q2 || codec == ESCO_CODEC_SWB_Q3) {
      return default_esco_parameters[ESCO_CODEC_SWB_Q0];
    }
    return default_esco_parameters[codec];
  }

  log::assert_that(codec < ESCO_LEGACY_NUM_CODECS, "legacy codec index {} > {}",
                   (int)codec, ESCO_LEGACY_NUM_CODECS);
  enh_esco_params_t param = default_esco_parameters[codec];
  param.input_data_path = param.output_data_path = ESCO_DATA_PATH_HCI;

  if (codec >= ESCO_CODEC_MSBC_T1) {
    param.transmit_coding_format.coding_format = ESCO_CODING_FORMAT_TRANSPNT;
    param.receive_coding_format.coding_format = ESCO_CODING_FORMAT_TRANSPNT;
    param.input_coding_format.coding_format = ESCO_CODING_FORMAT_TRANSPNT;
    param.output_coding_format.coding_format = ESCO_CODING_FORMAT_TRANSPNT;
    param.input_bandwidth = TXRX_64KBITS_RATE;
    param.output_bandwidth = TXRX_64KBITS_RATE;
  }

#if TARGET_FLOSS
  esco_packet_types_t new_packet_types = param.packet_types;
  if (codec == ESCO_CODEC_CVSD_S3 || codec == ESCO_CODEC_CVSD_S4 ||
      codec == ESCO_CODEC_MSBC_T2 || codec == ESCO_CODEC_LC3_T2) {
    new_packet_types =
        (ESCO_PKT_TYPES_MASK_NO_3_EV3 | ESCO_PKT_TYPES_MASK_NO_2_EV5 |
         ESCO_PKT_TYPES_MASK_NO_3_EV5);
  } else if (codec == ESCO_CODEC_CVSD_S1) {
    new_packet_types =
        (ESCO_PKT_TYPES_MASK_EV3 | ESCO_PKT_TYPES_MASK_EV4 |
         ESCO_PKT_TYPES_MASK_EV5 | ESCO_PKT_TYPES_MASK_NO_3_EV3 |
         ESCO_PKT_TYPES_MASK_NO_2_EV5 | ESCO_PKT_TYPES_MASK_NO_3_EV5);
  }

  if (param.packet_types != new_packet_types) {
    log::info("Applying restricted packet types for codec %d: 0x%04x -> 0x%04x",
              (int)codec, param.packet_types, new_packet_types);
    param.packet_types = new_packet_types;
  }
#endif

  return param;
}
