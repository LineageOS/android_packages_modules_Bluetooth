/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
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
 *  This file contains functions for the Bluetooth Security Manager
 *
 ******************************************************************************/

#pragma once
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/bt_octets.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/hci_role.h>

#include <cstdint>
#include <string>

#include "stack/btm/btm_device_record.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/security_client_callbacks.h"
#include "stack/include/smp_api_types.h"

#define BTM_SEC_MAX_COLLISION_DELAY (5000)

constexpr int MIN_KEY_SIZE = 7;
constexpr int MIN_KEY_SIZE_DEFAULT = MIN_KEY_SIZE;
constexpr int MAX_KEY_SIZE = 16;

/*******************************************************************************
 *
 * Function         btm_sec_register
 *
 * Description      Application manager calls this function to register for
 *                  security services.  There can be one and only one
 *                  application saving link keys.  BTM allows only first
 *                  registration.
 *
 * Returns          true if registered OK, else false
 *
 ******************************************************************************/
bool btm_sec_register(const BtmAppReg& app_reg);

bool btm_is_encrypted(const RawAddress& bd_addr, tBT_TRANSPORT transport);
bool btm_is_link_key_authed(const RawAddress& bd_addr, tBT_TRANSPORT transport);
bool btm_is_authenticated(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         btm_set_pin_type
 *
 * Description      Set PIN type for the device.
 *
 * Returns          void
 *
 ******************************************************************************/
// TODO : Remove when the flag local_pin_key_type is shipped
void btm_set_pin_type(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len);

/*******************************************************************************
 *
 * Function         btm_set_security_level
 *
 * Description      Register service security level with Security Manager
 *
 * Parameters:      outgoing    - true if originating the connection
 *                  p_name      - Name of the service relevant only if
 *                                authorization will show this name to user.
 *                                Ignored if BT_MAX_SERVICE_NAME_LEN is 0.
 *                  service_id  - service ID for the service passed to
 *                                authorization callback
 *                  sec_level   - bit mask of the security features
 *                  psm         - L2CAP PSM
 *                  mx_proto_id - protocol ID of multiplexing proto below
 *                  mx_chan_id  - channel ID of multiplexing proto below
 *
 * Returns          true if registered OK, else false
 *
 ******************************************************************************/
bool btm_set_security_level(bool outgoing, const char* p_name, uint8_t service_id,
                            uint16_t sec_level, uint16_t psm, uint32_t mx_proto_id,
                            uint32_t mx_chan_id);

/*******************************************************************************
 *
 * Function         btm_sec_clr_service
 *
 * Description      Removes specified service record(s) from the security
 *                  database. All service records with the specified name are
 *                  removed. Typically used only by devices with limited RAM so
 *                  that it can reuse an old security service record.
 *
 *                  Note: Unpredictable results may occur if a service is
 *                      cleared that is still in use by an application/profile.
 *
 * Parameters       Service ID - Id of the service to remove. '0' removes all
 *                          service records (except SDP).
 *
 * Returns          Number of records that were freed.
 *
 ******************************************************************************/
uint8_t btm_sec_clr_service(uint8_t service_id);

/*******************************************************************************
 *
 * Function         btm_sec_clr_service_by_psm
 *
 * Description      Removes specified service record from the security database.
 *                  All service records with the specified psm are removed.
 *                  Typically used by L2CAP to free up the service record used
 *                  by dynamic PSM clients when the channel is closed.
 *                  The given psm must be a virtual psm.
 *
 * Parameters       Service ID - Id of the service to remove. '0' removes all
 *                          service records (except SDP).
 *
 * Returns          Number of records that were freed.
 *
 ******************************************************************************/
uint8_t btm_sec_clr_service_by_psm(uint16_t psm);

/*******************************************************************************
 *
 * Function         btm_pin_code_reply
 *
 * Description      This function is called after Security Manager submitted
 *                  PIN code request to the UI.
 *
 * Parameters:      bd_addr      - Address of the device for which PIN was
 *                                 requested
 *                  res          - result of the operation tBTM_STATUS::BTM_SUCCESS
 *                                 if success
 *                  pin_len      - length in bytes of the PIN Code
 *                  p_pin        - pointer to array with the PIN Code
 *
 ******************************************************************************/
void btm_pin_code_reply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len,
                        PinCode pin_code);

/*******************************************************************************
 *
 * Function         btm_sec_bond_by_transport
 *
 * Description      this is the bond function that will start either SSP or SMP.
 *
 * Parameters:      bd_addr      - Address of the device to bond
 *                  pin_len      - length in bytes of the PIN Code
 *                  p_pin        - pointer to array with the PIN Code
 *
 *  Note: After 2.1 parameters are not used and preserved here not to change API
 ******************************************************************************/
tBTM_STATUS btm_sec_bond_by_transport(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                                      tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         btm_sec_bond
 *
 * Description      This function is called to perform bonding with peer device.
 *                  If the connection is already up, but not secure, pairing
 *                  is attempted.  If already paired tBTM_STATUS::BTM_SUCCESS is returned.
 *
 * Parameters:      bd_addr      - Address of the device to bond
 *                  addr_type    - Address type of the device to bond
 *                  transport    - doing SSP over BR/EDR or SMP over LE
 ******************************************************************************/
tBTM_STATUS btm_sec_bond(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                         tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         btm_sec_bond_cancel
 *
 * Description      This function is called to cancel ongoing bonding process
 *                  with peer device.
 *
 * Parameters:      bd_addr      - Address of the peer device
 *                  transport    - false for BR/EDR link; true for LE link
 *
 ******************************************************************************/
tBTM_STATUS btm_sec_bond_cancel(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_get_device_link_key_type
 *
 * Description      This function is called to obtain link key type for the
 *                  device.
 *                  it returns tBTM_STATUS::BTM_SUCCESS if link key is available, or
 *                  tBTM_STATUS::BTM_UNKNOWN_ADDR if Security Manager does not know about
 *                  the device or device record does not contain link key info
 *
 * Returns          BTM_LKEY_TYPE_IGNORE if link key is unknown, link type
 *                  otherwise.
 *
 ******************************************************************************/
tBTM_LINK_KEY_TYPE btm_sec_get_device_link_key_type(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_set_encryption
 *
 * Description      This function is called to ensure that connection is
 *                  encrypted.  Should be called only on an open connection.
 *                  Typically only needed for connections that first want to
 *                  bring up unencrypted links, then later encrypt them.
 *
 * Parameters:      bd_addr       - Address of the peer device
 *                  transport     - Link transport
 *                  p_callback    - Pointer to callback function called if
 *                                  this function returns PENDING after required
 *                                  procedures are completed.  Can be set to
 *                                  NULL if status is not desired.
 *                  p_ref_data    - pointer to any data the caller wishes to
 *                                  receive in the callback function upon
 *                                  completion. can be set to NULL if not used.
 *                  sec_act       - LE security action, unused for BR/EDR
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS   - already encrypted
 *                  BTM_PENDING   - command will be returned in the callback
 *                  tBTM_STATUS::BTM_WRONG_MODE- connection not up.
 *                  tBTM_STATUS::BTM_BUSY      - security procedures are currently active
 *                  tBTM_STATUS::BTM_ERR_KEY_MISSING  - link key is missing.
 *                  tBTM_STATUS::BTM_MODE_UNSUPPORTED - if security manager not linked in.
 *
 ******************************************************************************/
tBTM_STATUS btm_set_encryption(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                               tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                               tBTM_BLE_SEC_ACT sec_act);

bool btm_sec_is_le_security_pending(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_confirm_req_reply
 *
 * Description      This function is called to confirm the numeric value for
 *                  Simple Pairing in response to BTM_SP_CFM_REQ_EVT
 *
 * Parameters:      res           - result of the operation tBTM_STATUS::BTM_SUCCESS if
 *                                  success
 *                  bd_addr       - Address of the peer device
 *
 ******************************************************************************/
void btm_confirm_req_reply(tBTM_STATUS res, const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_passkey_req_reply
 *
 * Description      This function is called to provide the passkey for
 *                  Simple Pairing in response to BTM_SP_KEY_REQ_EVT
 *
 * Parameters:      res     - result of the operation tBTM_STATUS::BTM_SUCCESS if success
 *                  bd_addr - Address of the peer device
 *                  passkey - numeric value in the range of
 *                  BTM_MIN_PASSKEY_VAL(0) -
 *                  BTM_MAX_PASSKEY_VAL(999999(0xF423F)).
 *
 ******************************************************************************/
void btm_passkey_req_reply(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey);

/*******************************************************************************
 *
 * Function         btm_read_local_oob_data
 *
 * Description      This function is called to read the local OOB data from
 *                  LM
 *
 ******************************************************************************/
void btm_read_local_oob_data(void);

/*******************************************************************************
 *
 * Function         btm_remote_oob_data_reply
 *
 * Description      This function is called to provide the remote OOB data for
 *                  Simple Pairing in response to BTM_SP_RMT_OOB_EVT
 *
 * Parameters:      bd_addr     - Address of the peer device
 *                  c           - simple pairing Hash C.
 *                  r           - simple pairing Randomizer  C.
 *
 ******************************************************************************/
void btm_remote_oob_data_reply(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                               const Octet16& r);

/*******************************************************************************
 *
 * Function         btm_peer_supports_secure_connections
 *
 * Description      This function is called to check if the peer supports
 *                  BR/EDR Secure Connections.
 *
 * Parameters:      bd_addr - address of the peer
 *
 * Returns          true if BR/EDR Secure Connections are supported by the peer,
 *                  else false.
 *
 ******************************************************************************/
bool btm_peer_supports_secure_connections(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_GetInitialSecurityMode
 *
 * Description      This function is called to retrieve the configured
 *                  security mode.
 *
 ******************************************************************************/
uint8_t btm_get_security_mode();

/*******************************************************************************
 *
 * Function         btm_sec_report_bond_loss
 *
 * Description      This function is called to report remote bond loss.
 *
 ******************************************************************************/
tBTM_STATUS btm_sec_report_bond_loss(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         btm_sec_l2cap_access_req
 *
 * Description      This function is called by the L2CAP to grant permission to
 *                  establish L2CAP connection to or from the peer device.
 *
 * Parameters:      bd_addr       - Address of the peer device
 *                  psm           - L2CAP PSM
 *                  is_originator - true if protocol above L2CAP originates
 *                                  connection
 *                  p_callback    - Pointer to callback function called if
 *                                  this function returns PENDING after required
 *                                  procedures are complete. MUST NOT BE NULL.
 *
 * Returns          tBTM_STATUS
 *
 ******************************************************************************/
tBTM_STATUS btm_sec_l2cap_access_req(const RawAddress& bd_addr, uint16_t psm, bool is_originator,
                                     tBTM_SEC_CALLBACK* p_callback, void* p_ref_data);

// Allow enforcing security by specific requirement (from shim layer).
tBTM_STATUS btm_sec_l2cap_access_req_by_requirement(const RawAddress& bd_addr,
                                                    uint16_t security_required, bool is_originator,
                                                    tBTM_SEC_CALLBACK* p_callback,
                                                    void* p_ref_data);

/*******************************************************************************
 *
 * Function         btm_sec_service_access_request
 *
 * Description      This function is called by all Multiplexing Protocols
 *                  during establishing connection to or from peer device to
 *                  grant permission to establish application connection.
 *
 * Parameters:      bd_addr       - Address of the peer device
 *                  psm           - L2CAP PSM
 *                  is_originator - true if protocol above L2CAP originates
 *                                  connection
 *                  p_callback    - Pointer to callback function called if
 *                                  this function returns PENDING after required
 *                                  procedures are completed
 *                  p_ref_data -    Pointer to any reference data needed by the
 *                                  callback function.
 *
 * Returns          tBTM_STATUS::BTM_CMD_STARTED when the security procedure is
 *                  started.
 *                  tBTM_STATUS::BTM_CMD_STORED when the request is stored in
 *                  the queue.
 *                  tBTM_STATUS::BTM_SUCCESS when the security procedure is
 *                  completed.
 *
 ******************************************************************************/
tBTM_STATUS btm_sec_service_access_request(const RawAddress& bd_addr, bool is_originator,
                                           uint16_t security_requirement,
                                           tBTM_SEC_CALLBACK* p_callback, void* p_ref_data);

/*******************************************************************************
 *
 * Function         btm_sec_conn_req
 *
 * Description      This function is when the peer device is requesting
 *                  connection
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_conn_req(const RawAddress& bda, const DEV_CLASS dc);

/*******************************************************************************
 *
 * Function         btm_create_conn_cancel_complete
 *
 * Description      This function is called when the command complete message
 *                  is received from the HCI for the create connection cancel
 *                  command.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_create_conn_cancel_complete(uint8_t status, const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_dev_reset
 *
 * Description      This function should be called after device reset
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_dev_reset(void);

/*******************************************************************************
 *
 * Function         btm_sec_abort_access_req
 *
 * Description      This function is called by the L2CAP or RFCOMM to abort
 *                  the pending operation.
 *
 * Parameters:      bd_addr       - Address of the peer device
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_abort_access_req(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_rmt_name_request_complete
 *
 * Description      This function is called when remote name was obtained from
 *                  the peer device
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_rmt_name_request_complete(const RawAddress* p_bd_addr, const uint8_t* p_bd_name,
                                       tHCI_STATUS status);

/*******************************************************************************
 *
 * Function         btm_sec_rmt_host_support_feat_evt
 *
 * Description      This function is called when the
 *                  HCI_RMT_HOST_SUP_FEAT_NOTIFY_EVT is received
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_rmt_host_support_feat_evt(const RawAddress& bd_addr, uint8_t features_0);

/*******************************************************************************
 *
 * Function         btm_io_capabilities_req
 *
 * Description      This function is called when LM request for the IO
 *                  capability of the local device and
 *                  if the OOB data is present for the device in the event
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_io_capabilities_req(const RawAddress& bda);

/*******************************************************************************
 *
 * Function         btm_io_capabilities_rsp
 *
 * Description      This function is called when the IO capability of the
 *                  specified device is received
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_io_capabilities_rsp(const tBTM_SP_IO_RSP evt_data);

/*******************************************************************************
 *
 * Function         btm_proc_sp_req_evt
 *
 * Description      This function is called to process/report
 *                  HCI_USER_CONFIRMATION_REQUEST_EVT
 *                  or HCI_USER_PASSKEY_REQUEST_EVT
 *                  or HCI_USER_PASSKEY_NOTIFY_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_proc_sp_req_evt(tBTM_SP_EVT event, const RawAddress& bda, uint32_t value);

/*******************************************************************************
 *
 * Function         btm_simple_pair_complete
 *
 * Description      This function is called when simple pairing process is
 *                  complete
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_simple_pair_complete(const RawAddress& bd_addr, uint8_t status);

/*******************************************************************************
 *
 * Function         btm_rem_oob_req
 *
 * Description      This function is called to process/report
 *                  HCI_REMOTE_OOB_DATA_REQUEST_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_rem_oob_req(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_read_local_oob_complete
 *
 * Description      This function is called when read local oob data is
 *                  completed by the LM
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_read_local_oob_complete(const tBTM_SP_LOC_OOB evt_data);

/*******************************************************************************
 *
 * Function         btm_sec_auth_complete
 *
 * Description      This function is when authentication of the connection is
 *                  completed by the LM
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_auth_complete(uint16_t handle, tHCI_STATUS status);

/*******************************************************************************
 *
 * Function         btm_sec_encryption_change_evt
 *
 * Description      This function is called to process an encryption change.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_encryption_change_evt(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable,
                                   uint8_t key_size);

/*******************************************************************************
 *
 * Function         btm_sec_encrypt_change
 *
 * Description      This function is when encryption of the connection is
 *                  completed by the LM
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_encrypt_change(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable,
                            uint8_t key_size, bool from_key_refresh);

/*******************************************************************************
 *
 * Function         btm_sec_connected
 *
 * Description      This function is when a connection to the peer device is
 *                  established
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_connected(const RawAddress& bda, uint16_t handle, tHCI_STATUS status, uint8_t enc_mode,
                       bool locally_initiated, tHCI_ROLE assigned_role = HCI_ROLE_PERIPHERAL);

/*******************************************************************************
 *
 * Function         btm_sec_disconnect
 *
 * Description      This function is called to disconnect HCI link
 *
 * Returns          btm status
 *
 ******************************************************************************/
tBTM_STATUS btm_sec_disconnect(uint16_t handle, tHCI_STATUS reason, std::string);

/*******************************************************************************
 *
 * Function         btm_sec_disconnected
 *
 * Description      This function is when a connection to the peer device is
 *                  dropped
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_disconnected(uint16_t handle, tHCI_STATUS reason, std::string comment);

/*******************************************************************************
 *
 * Function         btm_sec_role_changed
 *
 * Description      This function is called when receiving an HCI role change
 *                  event
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_role_changed(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role);

/** This function is called when a new connection link key is generated */
void btm_sec_link_key_notification(const RawAddress& p_bda, const Octet16& link_key,
                                   uint8_t key_type);

/** This function is called for each encryption key refresh complete event */
void btm_sec_encryption_key_refresh_complete(uint16_t handle, tHCI_STATUS status);

/*******************************************************************************
 *
 * Function         btm_sec_link_key_request
 *
 * Description      This function is called when controller requests link key
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
void btm_sec_link_key_request(const RawAddress& bda);

/*******************************************************************************
 *
 * Function         btm_sec_pin_code_request
 *
 * Description      This function is called when controller requests PIN code
 *
 * Returns          Pointer to the record or NULL
 *
 ******************************************************************************/
void btm_sec_pin_code_request(const RawAddress& bda);

/*******************************************************************************
 *
 * Function         btm_sec_update_clock_offset
 *
 * Description      This function is called to update clock offset
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_update_clock_offset(uint16_t handle, uint16_t clock_offset);

/*******************************************************************************
 *
 * Function         btm_sec_dev_rec_cback_event
 *
 * Description      This function calls the callback function with the given
 *                  result and clear the callback function.
 *
 * Parameters:      void
 *
 ******************************************************************************/
void btm_sec_dev_rec_cback_event(BtmDevice* p_device, tBTM_STATUS res, bool is_le_transport);

/*******************************************************************************
 *
 * Function         btm_sec_clear_ble_keys
 *
 * Description      This function is called to clear out the BLE keys.
 *                  Typically when devices are removed in btm_sec_delete_device,
 *                  or when a new BT Link key is generated.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_clear_ble_keys(BtmDevice* p_device);

/*******************************************************************************
 *
 * Function         btm_sec_set_peer_sec_caps
 *
 * Description      This function is called to set sm4 and rmt_sec_caps fields
 *                  based on the available peer device features.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_set_peer_sec_caps(uint16_t hci_handle, bool ssp_supported, bool host_sc_supported,
                               bool controller_sc_supported, bool hci_role_switch_supported,
                               bool br_edr_supported, bool le_supported);

/*******************************************************************************
 *
 * Function         btm_sec_cr_loc_oob_data_cback_event
 *
 * Description      This function is called to pass the local oob up to caller
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_sec_cr_loc_oob_data_cback_event(const RawAddress& address, tSMP_LOC_OOB_DATA loc_oob_data);

/*******************************************************************************
 *
 * Function         btm_is_bond_lost
 *
 * Description      This function is called to check if the bond is lost
 *
 * Returns          bool
 *
 ******************************************************************************/
bool btm_is_bond_lost(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_update_bond_lost
 *
 * Description      This function is called to set the bond lost status.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_update_bond_lost(const RawAddress& bd_addr, bool bond_lost);

/*******************************************************************************
 *
 * Function         btm_is_bonded
 *
 * Description      Is the specified device is a bonded device.
 *
 * Returns          bool
 *
 ******************************************************************************/
bool btm_is_bonded(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         btm_sec_hci_delete_stored_link_key
 *
 * Description      Delete stored link key.
 *
 * Returns          bool
 *
 ******************************************************************************/
void btm_sec_hci_delete_stored_link_key(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         btm_sec_get_min_enc_key_size
 *
 * Description      Get the minimum encryption key size allowed by the system.
 *
 * Returns          The minimum encryption key size.
 *
 ******************************************************************************/
uint8_t btm_sec_get_min_enc_key_size();
