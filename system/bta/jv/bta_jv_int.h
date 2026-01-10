/******************************************************************************
 *
 *  Copyright 2006-2012 Broadcom Corporation
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

/******************************************************************************
 *
 *  This is the private interface file for the BTA Java I/F
 *
 ******************************************************************************/
#ifndef BTA_JV_INT_H
#define BTA_JV_INT_H

#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include <memory>
#include <unordered_set>

#include "bta/include/bta_jv_api.h"
#include "bta/include/bta_sec_api.h"
#include "internal_include/bt_target.h"
#include "macros.h"
#include "osi/include/alarm.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/rfcdefs.h"
#include "stack/include/sdp_status.h"

/*****************************************************************************
 *  Constants
 ****************************************************************************/

#ifndef BTA_JV_RFC_EV_MASK
#define BTA_JV_RFC_EV_MASK (PORT_EV_RXCHAR | PORT_EV_TXEMPTY | PORT_EV_FC | PORT_EV_FCS)
#endif

enum BtaJvPmState {
  BTA_JV_PM_FREE_ST = 0,  // empty PM slot
  BTA_JV_PM_IDLE_ST,
  BTA_JV_PM_BUSY_ST,
  BTA_JV_PM_BUSY_TO_IDLE_ST
};

/* BTA JV PM control block */
struct BtaJvPmCb {
  uint32_t handle;          // The connection handle
  BtaJvPmState state;       // see above enum
  tBTA_JV_PM_ID app_id;     // JV app specific id indicating power table to use
  RawAddress peer_bd_addr;  // Peer BD address
  alarm_t* idle_timer;      // Intermediate timer for preventing frequent state transition
};

enum BtaJvState {
  BTA_JV_ST_NONE = 0,
  BTA_JV_ST_CL_OPENING,
  BTA_JV_ST_CL_OPEN,
  BTA_JV_ST_CL_CLOSING,
  BTA_JV_ST_SR_LISTEN,
  BTA_JV_ST_SR_OPEN,
  BTA_JV_ST_SR_CLOSING
};
#define BTA_JV_ST_CL_MAX BTA_JV_ST_CL_CLOSING

/* JV L2CAP control block */
struct BtaJvL2capCb {
  tBTA_JV_L2CAP_CBACK* p_cback;  // the callback function
  uint16_t psm;                  // the psm used for this server connection
  BtaJvState state;              // the state of this control block
  tBTA_SERVICE_ID sec_id;        // service id
  uint32_t handle;               // the handle reported to java app (same as gap handle)
  bool cong;                     // true, if congested
  BtaJvPmCb* p_pm_cb;            // ptr to pm control block, NULL: unused
  uint32_t l2cap_socket_id;
};

#define BTA_JV_RFC_HDL_MASK 0xFF
#define BTA_JV_RFCOMM_MASK 0x80
#define BTA_JV_ALL_APP_ID 0xFF
#define BTA_JV_RFC_HDL_TO_SIDX(r) (((r) & 0xFF00) >> 8)
#define BTA_JV_RFC_H_S_TO_HDL(h, s) ((h) | ((s) << 8))

/* port control block */
struct BtaJvPcb {
  uint32_t handle;       // the rfcomm session handle at jv
  uint16_t port_handle;  // port handle
  BtaJvState state;      // the state of this control block
  uint8_t max_sess;      // max sessions
  uint32_t rfcomm_slot_id;
  bool cong;               // true, if congested
  BtaJvPmCb* p_pm_cb;      // ptr to pm control block, NULL: unused
};

/* JV RFCOMM control block */
struct BtaJvRfcommCb {
  tBTA_JV_RFCOMM_CBACK* p_cback;                  // the callback function
  uint8_t port_hdls[BTA_JV_MAX_RFC_SR_SESSION];   // array of port handles based on session index
  tBTA_SERVICE_ID sec_id;                         // service id
  uint8_t handle;                                 // index: the handle reported to java app
  uint8_t scn;                                    // the scn of the server
  uint8_t max_sess;                               // max sessions
  int curr_sess;                                  // current sessions count
};

/* JV control block */
struct BtaJvCb {
  /* the SDP handle reported to JV user is the (index + 1) to sdp_handle[].
   * if sdp_handle[i]==0, it's not used.
   * otherwise sdp_handle[i] is the stack SDP handle. */
  uint32_t sdp_handle[BTA_JV_MAX_SDP_REC];  // SDP records created
  tBTA_JV_DM_CBACK* p_dm_cback;
  BtaJvL2capCb l2c_cb[BTA_JV_MAX_L2C_CONN];  // index is GAP handle (index)
  BtaJvRfcommCb rfc_cb[BTA_JV_MAX_RFC_CONN];
  BtaJvPcb port_cb[MAX_RFC_PORTS];              // index of this array is the port_handle
  uint8_t sec_id[BTA_JV_NUM_SERVICE_ID];        // service ID
  uint16_t free_psm_list[BTA_JV_MAX_L2C_CONN];  // PSMs freed by java (can be reused)
  bool scn_in_use[RFCOMM_MAX_SCN];
  uint8_t scn_search_index;  // used to search for free scns

  struct sdp_cb {
    bool sdp_active{false};
    RawAddress bd_addr{RawAddress::kEmpty};         // current bd_addr of sdp discovery
    bluetooth::Uuid uuid{bluetooth::Uuid::kEmpty};  // current uuid of sdp discovery
  } sdp_cb;

  BtaJvPmCb pm_cb[BTA_JV_PM_MAX_NUM];  // PM on a per JV handle bases

  uint16_t dyn_psm;  // Next dynamic PSM value to try to assign
};

// JV control block
extern BtaJvCb bta_jv_cb;

extern std::unordered_set<uint16_t> used_l2cap_classic_dynamic_psm;

void bta_jv_enable(tBTA_JV_DM_CBACK* p_cback);
void bta_jv_disable();
void bta_jv_get_channel_id(tBTA_JV_CONN_TYPE type, int32_t channel, uint32_t l2cap_socket_id,
                           uint32_t rfcomm_slot_id, uint32_t lecoc_fixed_psm_slots);
void bta_jv_free_scn(tBTA_JV_CONN_TYPE type, uint16_t scn);
void bta_jv_start_discovery(const RawAddress& bd_addr, uint16_t num_uuid,
                            bluetooth::Uuid* uuid_list, uint32_t rfcomm_slot_id);
void bta_jv_cancel_discovery(uint32_t rfcomm_slot_id);
void bta_jv_create_record(uint32_t rfcomm_slot_id);
void bta_jv_delete_record(uint32_t handle);
void bta_jv_l2cap_connect(tBTA_JV_CONN_TYPE type, tBTA_SEC sec_mask, uint16_t remote_psm,
                          uint16_t rx_mtu, const RawAddress& peer_bd_addr,
                          std::unique_ptr<tL2CAP_CFG_INFO> cfg,
                          std::unique_ptr<tL2CAP_ERTM_INFO> ertm_info, tBTA_JV_L2CAP_CBACK* p_cback,
                          uint32_t l2cap_socket_id);
void bta_jv_l2cap_close(uint32_t handle, BtaJvL2capCb* p_cb);
void bta_jv_l2cap_start_server(tBTA_JV_CONN_TYPE type, tBTA_SEC sec_mask, uint16_t local_psm,
                               uint16_t rx_mtu, std::unique_ptr<tL2CAP_CFG_INFO> cfg_param,
                               std::unique_ptr<tL2CAP_ERTM_INFO> ertm_info,
                               tBTA_JV_L2CAP_CBACK* p_cback, uint32_t l2cap_socket_id);
void bta_jv_l2cap_stop_server(uint16_t local_psm, uint32_t l2cap_socket_id);
void bta_jv_l2cap_write(uint32_t handle, uint32_t req_id, BT_HDR* msg, uint32_t user_id,
                        BtaJvL2capCb* p_cb);
void bta_jv_rfcomm_connect(tBTA_SEC sec_mask, uint8_t remote_scn, const RawAddress& peer_bd_addr,
                           tBTA_JV_RFCOMM_CBACK* p_cback, uint32_t rfcomm_slot_id,
                           RfcommCfgInfo cfg, uint32_t app_uid, uint64_t sdp_duration_ms);
void bta_jv_rfcomm_close(uint32_t handle, uint32_t rfcomm_slot_id);
void bta_jv_rfcomm_start_server(tBTA_SEC sec_mask, uint8_t local_scn, uint8_t max_session,
                                tBTA_JV_RFCOMM_CBACK* p_cback, uint32_t rfcomm_slot_id,
                                RfcommCfgInfo cfg, uint32_t app_uid);
void bta_jv_rfcomm_stop_server(uint32_t handle, uint32_t rfcomm_slot_id);
void bta_jv_rfcomm_write(uint32_t handle, uint32_t req_id, BtaJvRfcommCb* p_cb, BtaJvPcb* p_pcb);
void bta_jv_set_pm_profile(uint32_t handle, tBTA_JV_PM_ID app_id, tBTA_JV_CONN_STATE init_st);

void bta_jv_l2cap_stop_server_le(uint16_t local_chan);
void bta_jv_idle_timeout_handler(void* data);

inline std::string bta_jv_pm_state_text(const BtaJvPmState& state) {
  switch (state) {
    CASE_RETURN_TEXT(BTA_JV_PM_FREE_ST);
    CASE_RETURN_TEXT(BTA_JV_PM_IDLE_ST);
    CASE_RETURN_TEXT(BTA_JV_PM_BUSY_ST);
    CASE_RETURN_TEXT(BTA_JV_PM_BUSY_TO_IDLE_ST);
    default:
      return std::string("UNKNOWN[") + std::to_string(state) + std::string("]");
  }
}

inline std::string bta_jv_state_text(const BtaJvState& state) {
  switch (state) {
    CASE_RETURN_TEXT(BTA_JV_ST_NONE);
    CASE_RETURN_TEXT(BTA_JV_ST_CL_OPENING);
    CASE_RETURN_TEXT(BTA_JV_ST_CL_OPEN);
    CASE_RETURN_TEXT(BTA_JV_ST_CL_CLOSING);
    CASE_RETURN_TEXT(BTA_JV_ST_SR_LISTEN);
    CASE_RETURN_TEXT(BTA_JV_ST_SR_OPEN);
    CASE_RETURN_TEXT(BTA_JV_ST_SR_CLOSING);
    default:
      return std::string("UNKNOWN[") + std::to_string(state) + std::string("]");
  }
}

namespace std {
template <>
struct formatter<BtaJvPmState> : enum_formatter<BtaJvPmState> {};
template <>
struct formatter<BtaJvState> : enum_formatter<BtaJvState> {};
}  // namespace std

namespace bluetooth::legacy::testing {
void bta_jv_start_discovery_cback(uint32_t rfcomm_slot_id, const RawAddress& bd_addr,
                                  tSDP_RESULT result);
}  // namespace bluetooth::legacy::testing

#endif  // BTA_JV_INT_H
