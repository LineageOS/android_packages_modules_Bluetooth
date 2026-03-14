/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "hcimsgs.h"
#include "stack/include/bt_hdr.h"

namespace bluetooth {
namespace hci {

constexpr uint8_t kIsoCodingFormatTransparent = 0x03;
constexpr uint8_t kIsoCodingFormatLc3 = 0x06;
constexpr uint8_t kIsoCodingFormatVendorSpecific = 0xFF;

constexpr uint8_t kIsoCigPackingSequential = 0x00;
constexpr uint8_t kIsoCigPackingInterleaved = 0x01;

constexpr uint8_t kIsoCigFramingUnframed = 0x00;
constexpr uint8_t kIsoCigFramingFramed = 0x01;

constexpr uint8_t kIsoCigPhy1M = 0x01;
constexpr uint8_t kIsoCigPhy2M = 0x02;
constexpr uint8_t kIsoCigPhyC = 0x04;

namespace iso_manager {

// A handle to identify a registered ISO client.
// A value of 0 is considered invalid.
using IsoClientHandle = uint8_t;
constexpr IsoClientHandle kInvalidIsoClientHandle = 0;

constexpr uint8_t kIsoDataPathDirectionIn = 0x00;
constexpr uint8_t kIsoDataPathDirectionOut = 0x01;

constexpr uint8_t kRemoveIsoDataPathDirectionInput = 0x01;
constexpr uint8_t kRemoveIsoDataPathDirectionOutput = 0x02;

constexpr uint8_t kIsoDataPathHci = 0x00;
constexpr uint8_t kIsoDataPathPlatformDefault = 0x01;
constexpr uint8_t kIsoDataPathDisabled = 0xFF;

constexpr uint8_t kIsoSca251To500Ppm = 0x00;
constexpr uint8_t kIsoSca151To250Ppm = 0x01;
constexpr uint8_t kIsoSca101To150Ppm = 0x02;
constexpr uint8_t kIsoSca76To100Ppm = 0x03;
constexpr uint8_t kIsoSca51To75Ppm = 0x04;
constexpr uint8_t kIsoSca31To50Ppm = 0x05;
constexpr uint8_t kIsoSca21To30Ppm = 0x06;
constexpr uint8_t kIsoSca0To20Ppm = 0x07;

constexpr uint8_t kIsoEventCisDataAvailable = 0x00;
constexpr uint8_t kIsoEventCisEstablishCmpl = 0x01;
constexpr uint8_t kIsoEventCisDisconnected = 0x02;
constexpr uint8_t kIsoEventBisDataAvailable = 0x03;
constexpr uint8_t kIsoEventCisRequest = 0x04;
constexpr uint8_t kIsoEventCisRequestRejectStatus = 0x05;

constexpr uint8_t kIsoEventCigOnCreateCmpl = 0x00;
constexpr uint8_t kIsoEventCigOnReconfigureCmpl = 0x01;
constexpr uint8_t kIsoEventCigOnRemoveCmpl = 0x02;

enum class BigSourceEvent : uint8_t {
  kCreateCmpl = 0x00,
  kTerminateCmpl,
};

enum class BigSinkEvent : uint8_t {
  kSyncEst = 0x00,
  kSyncLost,
  kTerminateSyncCmpl,
};

struct cig_create_params {
  uint32_t sdu_itv_c_to_p;
  uint32_t sdu_itv_p_to_c;
  uint8_t sca;
  uint8_t packing;
  uint8_t framing;
  uint16_t max_trans_lat_c_to_p;
  uint16_t max_trans_lat_p_to_c;
  std::vector<EXT_CIS_CFG> cis_cfgs;
};

struct cig_remove_cmpl_evt {
  uint8_t status;
  uint8_t cig_id;
};

struct cig_create_cmpl_evt {
  uint8_t status;
  uint8_t cig_id;
  std::vector<uint16_t> conn_handles;
};

struct cis_data_evt {
  uint8_t cig_id;
  uint16_t cis_conn_hdl;
  uint32_t ts;
  uint16_t evt_lost;
  uint16_t seq_nb;
  BT_HDR* p_msg;
};

struct cis_establish_params {
  std::vector<EXT_CIS_CREATE_CFG> conn_pairs;
};

struct cis_establish_cmpl_evt {
  uint8_t status;
  uint8_t cig_id;
  uint16_t cis_conn_hdl;
  uint32_t cig_sync_delay;
  uint32_t cis_sync_delay;
  uint32_t trans_lat_c_to_p;
  uint32_t trans_lat_p_to_c;
  uint8_t phy_c_to_p;
  uint8_t phy_p_to_c;
  uint8_t nse;
  uint8_t bn_c_to_p;
  uint8_t bn_p_to_c;
  uint8_t ft_c_to_p;
  uint8_t ft_p_to_c;
  uint16_t max_pdu_c_to_p;
  uint16_t max_pdu_p_to_c;
  uint16_t iso_itv;
};

struct cis_request_evt {
  uint16_t acl_conn_hdl;
  uint16_t cis_conn_hdl;
  uint8_t cig_id;
  uint8_t cis_id;
};

struct reject_cis_request_reject_status {
  uint8_t status;
  uint16_t cis_conn_hdl;
};

struct cis_disconnected_evt {
  uint8_t reason;
  uint8_t cig_id;
  uint16_t cis_conn_hdl;
};

struct big_create_params {
  uint8_t adv_handle;
  uint8_t num_bis;
  uint32_t sdu_itv;
  uint16_t max_sdu_size;
  uint16_t max_transport_latency;
  uint8_t rtn;
  uint8_t phy;
  uint8_t packing;
  uint8_t framing;
  uint8_t enc;
  std::array<uint8_t, 16> enc_code;
};

struct big_create_cmpl_evt {
  uint8_t status;
  uint8_t big_handle;
  uint32_t big_sync_delay;
  uint32_t transport_latency_big;
  uint8_t phy;
  uint8_t nse;
  uint8_t bn;
  uint8_t pto;
  uint8_t irc;
  uint16_t max_pdu;
  uint16_t iso_interval;
  std::vector<uint16_t> conn_handles;
};

struct big_terminate_cmpl_evt {
  uint8_t big_handle;
  uint8_t reason;
};

struct iso_data_path_params {
  uint8_t data_path_dir;
  uint8_t data_path_id;
  uint8_t codec_id_format;
  uint16_t codec_id_company;
  uint16_t codec_id_vendor;
  uint32_t controller_delay;
  std::vector<uint8_t> codec_conf;
};

struct big_create_sync_params {
  uint8_t big_handle;
  uint16_t sync_handle;
  uint8_t encryption;
  std::array<uint8_t, 16> broadcast_code;
  uint8_t mse;
  uint16_t big_sync_timeout;
  std::vector<uint8_t> bis;
};

struct big_sync_est_evt {
  uint8_t status;
  uint8_t big_handle;
  uint32_t transport_latency_big;
  uint8_t nse;
  uint8_t bn;
  uint8_t pto;
  uint8_t irc;
  uint16_t max_pdu;
  uint16_t iso_interval;
  std::vector<uint16_t> conn_handles;
};

struct big_sync_lost_evt {
  uint8_t big_handle;
  uint8_t reason;
};

struct big_terminate_sync_cmpl_evt {
  uint8_t status;
  uint8_t big_handle;
};

struct bis_data_evt {
  uint8_t big_handle;
  uint16_t bis_conn_hdl;
  uint32_t ts;
  uint16_t evt_lost;
  uint16_t seq_nb;
  BT_HDR* p_msg;
};

}  // namespace iso_manager
}  // namespace hci
}  // namespace bluetooth
