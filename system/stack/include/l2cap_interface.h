/*
 * Copyright 2024 The Android Open Source Project
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

#include <bluetooth/log.h>
#include <stdbool.h>

#include <cstdint>
#include <vector>

#include "stack/include/bt_hdr.h"
#include "stack/include/l2cap_types.h"
#include "types/bt_transport.h"
#include "types/hci_role.h"
#include "types/raw_address.h"

/* result code for L2CA_DataWrite() */
enum class tL2CAP_DW_RESULT : uint8_t {
  FAILED = 0,
  SUCCESS = 1,
  CONGESTED = 2,
};

/*********************************
 *  Callback Functions Prototypes
 *********************************/

/* Connection indication callback prototype. Parameters are
 *              BD Address of remote
 *              Local CID assigned to the connection
 *              PSM that the remote wants to connect to
 *              Identifier that the remote sent
 */
typedef void(tL2CA_CONNECT_IND_CB)(const RawAddress&, uint16_t, uint16_t, uint8_t);

/* Connection confirmation callback prototype. Parameters are
 *              Local CID
 *              Result - 0 = connected
 *              If there is an error, tL2CA_ERROR_CB is invoked
 */
typedef void(tL2CA_CONNECT_CFM_CB)(uint16_t, tL2CAP_CONN);

/* Configuration indication callback prototype. Parameters are
 *              Local CID assigned to the connection
 *              Pointer to configuration info
 */
typedef void(tL2CA_CONFIG_IND_CB)(uint16_t, tL2CAP_CFG_INFO*);

/* Configuration confirm callback prototype. Parameters are
 *              Local CID assigned to the connection
 *              Initiator (1 for local, 0 for remote)
 *              Initial config from remote
 * If there is an error, tL2CA_ERROR_CB is invoked
 */
typedef void(tL2CA_CONFIG_CFM_CB)(uint16_t, uint16_t, tL2CAP_CFG_INFO*);

/* Disconnect indication callback prototype. Parameters are
 *              Local CID
 *              Boolean whether upper layer should ack this
 */
typedef void(tL2CA_DISCONNECT_IND_CB)(uint16_t, bool);

/* Disconnect confirm callback prototype. Parameters are
 *              Local CID
 *              Result
 */
typedef void(tL2CA_DISCONNECT_CFM_CB)(uint16_t, uint16_t);

/* Disconnect confirm callback prototype. Parameters are
 *              Local CID
 *              Result
 */
typedef void(tL2CA_DATA_IND_CB)(uint16_t, BT_HDR*);

/* Congestion status callback protype. This callback is optional. If
 * an application tries to send data when the transmit queue is full,
 * the data will anyways be dropped. The parameter is:
 *              Local CID
 *              true if congested, false if uncongested
 */
typedef void(tL2CA_CONGESTION_STATUS_CB)(uint16_t, bool);

/* Transmit complete callback protype. This callback is optional. If
 * set, L2CAP will call it when packets are sent or flushed. If the
 * count is 0xFFFF, it means all packets are sent for that CID (eRTM
 * mode only). The parameters are:
 *              Local CID
 *              Number of SDUs sent or dropped
 */
typedef void(tL2CA_TX_COMPLETE_CB)(uint16_t, uint16_t);

/*
 * Notify the user when the remote send error result on ConnectRsp or ConfigRsp
 * The parameters are:
 *              Local CID
 *              Error code
 */
typedef void(tL2CA_ERROR_CB)(uint16_t, uint16_t);

/* Create credit based connection request callback prototype. Parameters are
 *              BD Address of remote
 *              Vector of allocated local cids to accept
 *              PSM
 *              Peer MTU
 *              Identifier that the remote sent
 */
typedef void(tL2CA_CREDIT_BASED_CONNECT_IND_CB)(const RawAddress& bdaddr,
                                                std::vector<uint16_t>& lcids, uint16_t psm,
                                                uint16_t peer_mtu, uint8_t identifier);

/* Collision Indication callback prototype. Used to notify upper layer that
 * remote devices sent Credit Based Connection Request but it was rejected due
 * to ongoing local request. Upper layer might want to sent another request when
 * local request is completed. Parameters are:
 *              BD Address of remote
 */
typedef void(tL2CA_CREDIT_BASED_COLLISION_IND_CB)(const RawAddress& bdaddr);

/* Credit based connection confirmation callback prototype. Parameters are
 *              BD Address of remote
 *              Connected Local CIDs
 *              Peer MTU
 *              Result - 0 = connected, non-zero means CID is not connected
 */
typedef void(tL2CA_CREDIT_BASED_CONNECT_CFM_CB)(const RawAddress& bdaddr, uint16_t lcid,
                                                uint16_t peer_mtu, tL2CAP_LE_RESULT_CODE result);

/* Credit based reconfiguration confirm callback prototype. Parameters are
 *              BD Address of remote
 *              Local CID assigned to the connection
 *              Flag indicating if this is local or peer configuration
 *              Pointer to configuration info
 */
typedef void(tL2CA_CREDIT_BASED_RECONFIG_COMPLETED_CB)(const RawAddress& bdaddr, uint16_t lcid,
                                                       bool is_local_cfg,
                                                       tL2CAP_LE_CFG_INFO* p_cfg);

/* Define the structure that applications use to register with
 * L2CAP. This structure includes callback functions. All functions
 * MUST be provided, with the exception of the "connect pending"
 * callback and "congestion status" callback.
 */
struct tL2CAP_APPL_INFO {
  tL2CA_CONNECT_IND_CB* pL2CA_ConnectInd_Cb;
  tL2CA_CONNECT_CFM_CB* pL2CA_ConnectCfm_Cb;
  tL2CA_CONFIG_IND_CB* pL2CA_ConfigInd_Cb;
  tL2CA_CONFIG_CFM_CB* pL2CA_ConfigCfm_Cb;
  tL2CA_DISCONNECT_IND_CB* pL2CA_DisconnectInd_Cb;
  tL2CA_DISCONNECT_CFM_CB* pL2CA_DisconnectCfm_Cb;
  tL2CA_DATA_IND_CB* pL2CA_DataInd_Cb;
  tL2CA_CONGESTION_STATUS_CB* pL2CA_CongestionStatus_Cb;
  tL2CA_TX_COMPLETE_CB* pL2CA_TxComplete_Cb;
  tL2CA_ERROR_CB* pL2CA_Error_Cb;
  tL2CA_CREDIT_BASED_CONNECT_IND_CB* pL2CA_CreditBasedConnectInd_Cb;
  tL2CA_CREDIT_BASED_CONNECT_CFM_CB* pL2CA_CreditBasedConnectCfm_Cb;
  tL2CA_CREDIT_BASED_RECONFIG_COMPLETED_CB* pL2CA_CreditBasedReconfigCompleted_Cb;
  tL2CA_CREDIT_BASED_COLLISION_IND_CB* pL2CA_CreditBasedCollisionInd_Cb;
};

/*******************************************************************************
 *
 *                      Fixed Channel callback prototypes
 *
 ******************************************************************************/

/* Fixed channel connected and disconnected. Parameters are
 *      channel
 *      BD Address of remote
 *      true if channel is connected, false if disconnected
 *      Reason for connection failure
 *      transport : physical transport, BR/EDR or LE
 */
typedef void(tL2CA_FIXED_CHNL_CB)(uint16_t, const RawAddress&, bool, uint16_t, tBT_TRANSPORT);

/* Signalling data received. Parameters are
 *      channel
 *      BD Address of remote
 *      Pointer to buffer with data
 */
typedef void(tL2CA_FIXED_DATA_CB)(uint16_t, const RawAddress&, BT_HDR*);

/* Congestion status callback protype. This callback is optional. If
 * an application tries to send data when the transmit queue is full,
 * the data will anyways be dropped. The parameter is:
 *      remote BD_ADDR
 *      true if congested, false if uncongested
 */
typedef void(tL2CA_FIXED_CONGESTION_STATUS_CB)(const RawAddress&, bool);

/* Fixed channel registration info (the callback addresses and channel config)
 */
struct tL2CAP_FIXED_CHNL_REG {
  tL2CA_FIXED_CHNL_CB* pL2CA_FixedConn_Cb;
  tL2CA_FIXED_DATA_CB* pL2CA_FixedData_Cb;
  tL2CA_FIXED_CONGESTION_STATUS_CB* pL2CA_FixedCong_Cb;

  uint16_t default_idle_tout;
  tL2CA_TX_COMPLETE_CB* pL2CA_FixedTxComplete_Cb; /* fixed channel tx complete callback */
};

/*******************************************************************************
 *
 *                      Fixed Channel callback prototypes
 *
 ******************************************************************************/

/* Fixed channel connected and disconnected. Parameters are
 *      channel
 *      BD Address of remote
 *      true if channel is connected, false if disconnected
 *      Reason for connection failure
 *      transport : physical transport, BR/EDR or LE
 */
typedef void(tL2CA_FIXED_CHNL_CB)(uint16_t, const RawAddress&, bool, uint16_t, tBT_TRANSPORT);

/* Signalling data received. Parameters are
 *      channel
 *      BD Address of remote
 *      Pointer to buffer with data
 */
typedef void(tL2CA_FIXED_DATA_CB)(uint16_t, const RawAddress&, BT_HDR*);

/* Congestion status callback protype. This callback is optional. If
 * an application tries to send data when the transmit queue is full,
 * the data will anyways be dropped. The parameter is:
 *      remote BD_ADDR
 *      true if congested, false if uncongested
 */
typedef void(tL2CA_FIXED_CONGESTION_STATUS_CB)(const RawAddress&, bool);

/* Fixed channel registration info (the callback addresses and channel config)
 */
namespace bluetooth {
namespace stack {
namespace l2cap {

class Interface {
public:
  virtual ~Interface() = default;

  /*******************************************************************************
   **
   ** Function         L2CA_Register
   **
   ** Description      Register for L2CAP a PSM service.
   **
   ** Parameters:      psm: L2cap PSM service to register
   **                  p_cb_info: Set of l2cap callbacks
   **                  enable_snoop: Enable to disable snooping on this PSM
   **                  p_ertm_info:
   **                  my_mtu:
   **                  required_remote_mtu:
   **                  sec_level: Security requirements for connection
   **
   ** Returns          PSM to use or zero if error. Typically, the PSM returned
   **                  is the same as was passed in, but for an outgoing-only
   **                  connection to a dynamic PSM, a "virtual" PSM is returned
   **                  and should be used in the calls to L2CA_ConnectReq() and
   **                  BTM_SetSecurityLevel().
   **
   ******************************************************************************/
  virtual uint16_t L2CA_Register(uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, bool enable_snoop,
                                 tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu,
                                 uint16_t required_remote_mtu, uint16_t sec_level) = 0;
  virtual uint16_t L2CA_RegisterWithSecurity(uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info,
                                             bool enable_snoop, tL2CAP_ERTM_INFO* p_ertm_info,
                                             uint16_t my_mtu, uint16_t required_remote_mtu,
                                             uint16_t sec_level) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_Deregister
   **
   ** Description      Other layers call this function to deregister for L2CAP
   **                  services.
   **
   ** Parameters:      psm: L2cap PSM value to deregister
   **
   ** Returns          void
   **
   ******************************************************************************/
  virtual void L2CA_Deregister(uint16_t psm) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_AllocateLePSM
   **
   ** Description      Find an unused LE PSM for an L2CAP service.
   **
   ** Returns          LE_PSM to use if success. Otherwise returns 0.
   **
   ******************************************************************************/
  virtual uint16_t L2CA_AllocateLePSM(void) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_FreeLePSM
   **
   ** Description      Free an assigned LE PSM.
   **
   ** Parameters:      psm: L2cap PSM value to free.
   **
   ** Returns          void
   **
   ******************************************************************************/
  virtual void L2CA_FreeLePSM(uint16_t psm) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_ConnectReq
   **
   ** Description      Create an L2CAP connection to a target device requesting
   **                  the PSM service.
   **                  Note that the connection is not established at this time,
   **                  but connection establishment gets started. The callback
   **                  will be invoked when connection establishes or fails.
   **
   ** Parameters:      psm: L2cap PSM on remote to request connection.
   **                  bd_addr: Remote address of peer connection device.
   **                  sec_level: Security requirements for connection.
   **
   ** Returns          Local CID of the connection, or 0 if it failed to
   **                  start
   **
   ******************************************************************************/
  virtual uint16_t L2CA_ConnectReq(uint16_t psm, const RawAddress& p_bd_addr) = 0;
  virtual uint16_t L2CA_ConnectReqWithSecurity(uint16_t psm, const RawAddress& p_bd_addr,
                                               uint16_t sec_level) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_RegisterLECoc
   **
   ** Description      Register for L2CAP Connection Oriented Channel.
   **
   ** Parameters:      psm: L2cap PSM service to register
   **                  p_cb_info: Set of l2cap callbacks
   **                  sec_level: Security requirements for connection
   **                  cfg: Le configuration info.
   **
   ** Returns          PSM to use or zero if error. Typically, the PSM returned
   **                  is the same as was passed in, but for an outgoing-only
   **                  connection to a dynamic PSM, a "virtual" PSM is returned
   **                  and should be used in the calls to L2CA_ConnectLECocReq()
   **                  and BTM_SetSecurityLevel().
   **
   ******************************************************************************/
  virtual uint16_t L2CA_RegisterLECoc(uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info,
                                      uint16_t sec_level, tL2CAP_LE_CFG_INFO cfg) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_DeregisterLECoc
   **
   ** Description      Other layers call this function to deregister for L2CAP
   **                  Connection Oriented Channel.
   **
   ** Parameters:      psm: L2cap PSM service to deregister
   **
   ** Returns          void
   **
   ******************************************************************************/
  virtual void L2CA_DeregisterLECoc(uint16_t psm) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_ConnectLECocReq
   **
   ** Description      Higher layers call this function to create an L2CAP LE
   **                  COC. Note that the connection is not established at this
   **                  time, but connection establishment gets started. The
   **                  callback will be invoked when connection establishes or
   **                  fails.
   **
   ** Parameters:      psm: L2cap PSM service to register
   **                  bd_addr: Peer bluetooth device address
   **                  p_cfg: Peer le configuration info
   **                  sec_level: Security requirements for connection
   **
   ** Returns          the CID of the connection, or 0 if it failed to start
   **
   ******************************************************************************/
  virtual uint16_t L2CA_ConnectLECocReq(uint16_t psm, const RawAddress& p_bd_addr,
                                        tL2CAP_LE_CFG_INFO* p_cfg, uint16_t sec_level) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_ConnectCreditBasedReq
   **
   ** Description      With this function L2CAP will initiate setup of up to 5
   **                  credit based connections for given psm using provided
   **                  configuration. L2CAP will notify user on the connection
   **                  result, by calling pL2CA_CreditBasedConnectCfm_Cb for
   **                  each cid with a result.
   **
   ** Parameters:      psm: PSM of peer service for connection
   **                  bd_addr: Peer bluetooth device address
   **                  p_cfg: Peer le configuration info
   **
   ** Returns          Local cids allocated for the connection
   **
   ******************************************************************************/
  virtual std::vector<uint16_t> L2CA_ConnectCreditBasedReq(uint16_t psm,
                                                           const RawAddress& p_bd_addr,
                                                           tL2CAP_LE_CFG_INFO* p_cfg) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_GetPeerLECocCredit
   **
   ** Description      Get peers current credit for LE Connection Oriented
   **                  Channel.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  lcid: Local l2cap channel id
   **
   ** Returns          Number of the peer current credit
   **
   ******************************************************************************/
  virtual uint16_t L2CA_GetPeerLECocCredit(const RawAddress& bd_addr, uint16_t lcid) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_ReconfigCreditBasedConnsReq
   **
   ** Description      Start reconfigure procedure on Connection Oriented
   **                  Channel.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  lcids: Local channel ids for reconfiguration
   **                  p_cfg: Peer le configuration info
   **
   ** Returns          true if peer is connected
   **
   ******************************************************************************/
  virtual bool L2CA_ReconfigCreditBasedConnsReq(const RawAddress& bd_addr,
                                                std::vector<uint16_t>& lcids,
                                                tL2CAP_LE_CFG_INFO* p_cfg) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_ConnectCreditBasedRsp
   **
   ** Description      Response for the pL2CA_CreditBasedConnectInd_Cb which is
   **                  the indication for peer requesting credit based
   **                  connection.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  id:
   **                  accepted_lcids:
   **                  result:
   **                  p_cfg: Peer le configuration info
   **
   ** Returns          true if peer is connected false otherwise
   **
   ******************************************************************************/
  virtual bool L2CA_ConnectCreditBasedRsp(const RawAddress& p_bd_addr, uint8_t id,
                                          std::vector<uint16_t>& accepted_lcids,
                                          tL2CAP_LE_RESULT_CODE result,
                                          tL2CAP_LE_CFG_INFO* p_cfg) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetIdleTimeoutByBdAddr
   **
   ** Description      Higher layers call this function to set the idle timeout
   **                  for a connection. The "idle timeout" is the amount of
   *time
   **                  that a connection can remain up with no L2CAP channels on
   **                  it. A timeout of zero means that the connection will be
   **                  torn down immediately when the last channel is removed.
   **                  A timeout of 0xFFFF means no timeout. Values are in
   **                  seconds. A bd_addr is the remote BD address. If
   **                  bd_addr = RawAddress::kAny, then the idle timeouts for
   **                  all active l2cap links will be changed.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  timeout: Timeout value for ACL link
   **                  transport: Transport to set timeout (BR/EDR or BLE)
   **
   ** Returns          true if command succeeded, false if failed
   **
   ** NOTE             This timeout applies to all logical channels active on
   *the
   **                  ACL link.
   ******************************************************************************/
  virtual bool L2CA_SetIdleTimeoutByBdAddr(const RawAddress& bd_addr, uint16_t timeout,
                                           tBT_TRANSPORT transport) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_UseLatencyMode
   **
   ** Description      Sets use latency mode for an ACL channel.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  use_latency_mode: Enable or disable latency mode
   **
   ** Returns          true if command succeeded, false if failed
   **
   ******************************************************************************/
  virtual bool L2CA_UseLatencyMode(const RawAddress& bd_addr, bool use_latency_mode) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetAclPriority
   **
   ** Description      Sets the transmission priority for an ACL channel.
   **                  (For initial implementation only two values are valid.
   **                  L2CAP_PRIORITY_NORMAL and L2CAP_PRIORITY_HIGH).
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  priority: Priority for ACL to peer
   **
   ** Returns          true if command succeeded, false if failed
   **
   ******************************************************************************/
  virtual bool L2CA_SetAclPriority(const RawAddress& bd_addr, tL2CAP_PRIORITY priority) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetAclLatency
   **
   ** Description      Sets the transmission latency for a channel.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  latency: Latency value for the ACL link
   **
   ** Returns          true if command succeeded, false if failed
   **
   ******************************************************************************/
  virtual bool L2CA_SetAclLatency(const RawAddress& bd_addr, tL2CAP_LATENCY latency) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_GetPeerFeatures
   **
   ** Description      Request peer features and fixed channel map
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  p_ext_feat: Peer features
   **                  p_chnl_mask: Peer fixed channel map
   **
   ** Returns          true if command succeeded, false if failed
   **
   ******************************************************************************/
  virtual bool L2CA_GetPeerFeatures(const RawAddress& bd_addr, uint32_t* p_ext_feat,
                                    uint8_t* p_chnl_mask) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetLeGattTimeout
   **
   ** Description      Higher layers call this function to set the idle timeout
   **                  for a fixed channel. The "idle timeout" is the amount of
   **                  time that a connection can remain up with no L2CAP
   **                  channels on it. A timeout of zero means that the
   **                  connection will be torn down immediately when the last
   **                  channel is removed. A timeout of 0xFFFF means no timeout.
   **                  Values are in seconds. A bd_addr is the remote BD
   *address.
   **                  If bd_addr = RawAddress::kAny, then the idle timeouts for
   **                  all active l2cap links will be changed.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  idle_tout: Idle timeout for GATT BLE connection
   **
   ** Returns          true if command succeeded, false if failed
   **
   ******************************************************************************/
  virtual bool L2CA_SetLeGattTimeout(const RawAddress& rem_bda, uint16_t idle_tout) = 0;

  virtual bool L2CA_MarkLeLinkAsActive(const RawAddress& rem_bda) = 0;

  virtual bool L2CA_UpdateBleConnParams(const RawAddress& rem_bda, uint16_t min_int,
                                        uint16_t max_int, uint16_t latency, uint16_t timeout,
                                        uint16_t min_ce_len, uint16_t max_ce_len) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_LockBleConnParamsForServiceDiscovery
   **
   ** Description:     When called with lock=true, LE connection parameters will
   **                  be locked on fastest value, and we won't accept request
   **                  to change it from remote. When called with lock=false,
   **                  parameters are relaxed.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  lock: Determines fast or relaxed parameters
   **
   ** Returns          void
   **
   ******************************************************************************/
  virtual void L2CA_LockBleConnParamsForServiceDiscovery(const RawAddress& rem_bda, bool lock) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_LockBleConnParamsForProfileConnection
   **
   ** Description:     When called with lock=true, LE connection parameters will
   **                  be locked on fastest value, and we won't accept request
   **                  to change it from remote. When called with lock=false,
   **                  parameters are relaxed.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  lock: Determines fast or relaxed parameters
   **
   ** Returns          void
   **
   ******************************************************************************/
  virtual void L2CA_LockBleConnParamsForProfileConnection(const RawAddress& rem_bda, bool lock) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_Consolidate
   **
   ** Description      This function consolidates two addresses.
   **
   ** Parameters:      identity_addr: Identity address of peer
   **                  rpa: Resolvable Private Address of peer
   **
   ** Returns          void
   **
   ******************************************************************************/
  virtual void L2CA_Consolidate(const RawAddress& identity_addr, const RawAddress& rpa) = 0;
  virtual tHCI_ROLE L2CA_GetBleConnRole(const RawAddress& bd_addr) = 0;
  virtual uint16_t L2CA_GetBleConnInterval(const RawAddress& bd_addr) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_IsLinkEstablished
   **
   ** Description      Check if a BR/EDR or BLE link to the remote device is
   **                  established.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  transport: Transport to check (BR/EDR or BLE)
   **
   ** Returns          true if peer is connected false otherwise
   **
   ******************************************************************************/
  virtual bool L2CA_IsLinkEstablished(const RawAddress& bd_addr, tBT_TRANSPORT transport) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SubrateRequest
   **
   ** Description      BLE Subrate request.
   **
   ** Parameters:      bd_addr: Peer bluetooth device address
   **                  Power subrating parameters
   **
   ** Return value:    true if update started
   **
   ******************************************************************************/
  virtual bool L2CA_SubrateRequest(const RawAddress& rem_bda, uint16_t subrate_min,
                                   uint16_t subrate_max, uint16_t max_latency, uint16_t cont_num,
                                   uint16_t timeout) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_GetPeerLECocConfig
   **
   ** Description      Request peer configuration for LE Connection Oriented
   **                  Channel.
   **
   ** Parameters:      cid: Local channel id of L2CAP connection
   **                  peer_cfg: Peer LE CoC configuration
   **
   ** Return value:    true if peer is connected
   **
   ******************************************************************************/
  virtual bool L2CA_GetPeerLECocConfig(uint16_t cid, tL2CAP_LE_CFG_INFO* peer_cfg) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_DisconnectReq
   **
   ** Description      Higher layers call this function to disconnect a channel.
   **
   ** Parameters:      cid: Local channel id of L2CAP connection
   **
   ** Returns          true if disconnect sent, else false
   **
   ******************************************************************************/
  virtual bool L2CA_DisconnectReq(uint16_t cid) = 0;
  virtual bool L2CA_DisconnectLECocReq(uint16_t cid) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_DataWrite
   **
   ** Description      Higher layers call this function to write data.
   **
   ** Parameters:      cid: Local channel id of L2CAP connection
   **                  p_data: Data to write to peer
   **
   ** Returns          L2CAP_DW_SUCCESS, if data accepted, else false
   **                  L2CAP_DW_CONGESTED, if data accepted and the channel is
   **                                      congested
   **                  L2CAP_DW_FAILED, if error
   **
   ******************************************************************************/
  virtual tL2CAP_DW_RESULT L2CA_DataWrite(uint16_t cid, BT_HDR* p_data) = 0;
  virtual tL2CAP_DW_RESULT L2CA_LECocDataWrite(uint16_t cid, BT_HDR* p_data) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_GetRemoteChannelId
   **
   ** Description      Given a local channel identifier, |lcid|, this function
   **                  returns the bound remote channel identifier, |rcid|. If
   **                  |lcid| is not known or is invalid, this function returns
   **                  false and does not modify the value pointed at by |rcid|.
   **                  |rcid| may be NULL.
   **
   ** Parameters:      cid: Local channel id of L2CAP connection
   **                  rcid: Remote channel id of L2CAP connection
   **
   ** Returns          true if remote cid exists, false otherwise
   **
   ******************************************************************************/
  virtual bool L2CA_GetRemoteChannelId(uint16_t cid, uint16_t* rcid) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_FlushChannel
   **
   ** Description      This function flushes none, some or all buffers queued up
   **                  for xmission for a particular CID. If called with
   **                  L2CAP_FLUSH_CHANS_GET (0), it simply returns the number
   **                  of buffers queued for that CID L2CAP_FLUSH_CHANS_ALL
   **                  (0xffff) flushes all buffers.  All other values specifies
   **                  the maximum buffers to flush.
   **
   ** Parameters:      lcid: Local channel id of L2CAP connection
   **                  num_to_flush: Number of buffers to flush or
   **                  L2CAP_FLUSH_CHANS_ALL
   **
   ** Returns          Number of buffers left queued for that CID
   **
   ******************************************************************************/
  virtual uint16_t L2CA_FlushChannel(uint16_t cid, uint16_t num_to_flush) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetTxPriority
   **
   ** Description      Sets the transmission priority for a channel. (FCR Mode)
   **
   ** Parameters:      cid: Local channel id of L2CAP connection
   **                  priority: L2CAP channel priority
   **
   ** Returns          true if a valid channel, else false
   **
   ******************************************************************************/
  virtual bool L2CA_SetTxPriority(uint16_t cid, tL2CAP_CHNL_PRIORITY priority) = 0;

  /*******************************************************************************
   *
   * Function         L2CA_SetChnlFlushability
   *
   * Description      Higher layers call this function to set a channels
   *                  flushability flags
   *
   ** Parameters:      cid: Local channel id of L2CAP connection
   **                  is_flushable: Set or clear flushability flag for channel
   * Returns          true if CID found, else false
   *
   ******************************************************************************/
  virtual bool L2CA_SetChnlFlushability(uint16_t cid, bool is_flushable) = 0;

  /*******************************************************************************
   **
   **  Function        L2CA_RegisterFixedChannel
   **
   **  Description     Register a fixed channel.
   **
   **  Parameters:     fixed_cid: Fixed Channel #
   **                  p_freg: Channel Callbacks and config
   **
   **  Return value:   true if registered OK, false otherwise
   **
   ******************************************************************************/
  virtual bool L2CA_RegisterFixedChannel(uint16_t fixed_cid, tL2CAP_FIXED_CHNL_REG* p_freg) = 0;

  /*******************************************************************************
   **
   **  Function        L2CA_ConnectFixedChnl
   **
   **  Description     Connect an fixed signalling channel to a remote device.
   **
   **  Parameters:     fixed_cid: Fixed CID
   **                  bd_addr: BD Address of remote
   **
   **  Return value:   true if connection started, false otherwise
   **
   ******************************************************************************/
  virtual bool L2CA_ConnectFixedChnl(uint16_t fixed_cid, const RawAddress& bd_addr) = 0;

  /*******************************************************************************
   **
   **  Function        L2CA_SendFixedChnlData
   **
   **  Description     Write data on a fixed signalling channel.
   **
   **  Parameters:     fixed_cid: Fixed CID
   **                  bd_addr: BD Address of remote
   **                  p_buf: Pointer to data buffer
   **
   ** Return value     L2CAP_DW_SUCCESS, if data accepted
   **                  L2CAP_DW_FAILED,  if error
   **
   ******************************************************************************/
  virtual tL2CAP_DW_RESULT L2CA_SendFixedChnlData(uint16_t fixed_cid, const RawAddress& rem_bda,
                                                  BT_HDR* p_buf) = 0;

  /*******************************************************************************
   **
   **  Function        L2CA_RemoveFixedChnl
   **
   **  Description     Remove a fixed channel to a remote device.
   **
   **  Parameters:     fixed_cid: Fixed CID
   **                  bd_addr: Mac address of remote
   **
   **  Return value:   true if channel removed, false otherwise
   **
   ******************************************************************************/
  virtual bool L2CA_RemoveFixedChnl(uint16_t fixed_cid, const RawAddress& rem_bda) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_AdjustConnectionIntervals
   **
   ** Description      Adjust connection intervals
   **
   ** Parameters:      Connection intervals
   **
   ** Return value:    void
   **
   ******************************************************************************/
  virtual void L2CA_AdjustConnectionIntervals(uint16_t* min_interval, uint16_t* max_interval,
                                              uint16_t floor_interval) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetEcosystemBaseInterval
   **
   ** Description      Sets the base ecosystem interval
   **
   ** Parameters:      Base interval
   **
   ** Return value:    void
   **
   ******************************************************************************/
  virtual void L2CA_SetEcosystemBaseInterval(uint32_t base_interval) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_SetMediaStreamChannel
   **
   ** Description      This function is called to set/reset the ccb of active
   **                  media streaming channel
   **
   **  Parameters:     local_media_cid: The local cid provided to A2DP to be
   **                    used for streaming
   **                  status: The status of media streaming on this channel
   **
   ** Returns          void
   **
   *******************************************************************************/
  virtual void L2CA_SetMediaStreamChannel(uint16_t local_media_cid, bool status) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_isMediaChannel
   **
   ** Description      This function returns if the channel id passed as
   **                  parameter is an A2DP streaming channel
   **
   **  Parameters:     handle: Connection handle with the remote device
   **                  channel_id: Channel ID
   **                  is_local_cid: Signifies if the channel id passed is local
   **                    cid or remote cid (true if local, remote otherwise)
   **
   ** Returns          bool
   **
   *******************************************************************************/
  virtual bool L2CA_isMediaChannel(uint16_t handle, uint16_t channel_id, bool is_local_cid) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_GetAclHandle
   **
   ** Description      Given a local channel identifier, |lcid|, this function
   **                  returns the handle of the corresponding ACL connection, |acl_handle|. If
   **                  |lcid| is not known or is invalid, this function returns false and does not
   **                  modify the value pointed at by |acl_handle|.
   **
   ** Parameters:      lcid: Local CID
   **                  acl_handle: Pointer to ACL handle must NOT be nullptr
   **
   ** Returns          true if acl_handle lookup was successful
   **
   ******************************************************************************/
  virtual bool L2CA_GetAclHandle(uint16_t lcid, uint16_t* acl_handle) = 0;

  /*******************************************************************************
   **
   ** Function         L2CA_GetLocalMtu
   **
   ** Description      Given a local channel identifier, |lcid|, this function
   **                  returns the L2CAP local mtu, |local_mtu|. If
   **                  |lcid| is not known or is invalid, this function returns false and does not
   **                  modify the value pointed at by |local_mtu|.
   **
   ** Parameters:      lcid: Local CID
   **                  local_mtu: Pointer to L2CAP local mtu must NOT be nullptr
   **
   ** Returns          true if local_mtu lookup was successful
   **
   ******************************************************************************/
  virtual bool L2CA_GetLocalMtu(uint16_t lcid, uint16_t* local_mtu) = 0;
};

Interface& get_interface();

}  // namespace l2cap
}  // namespace stack
}  // namespace bluetooth
