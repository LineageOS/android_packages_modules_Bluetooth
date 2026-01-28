/*
 * Copyright 2023 The Android Open Source Project
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
 */

#pragma once

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <bluetooth/types/bt_transport.h>

#include <cstdint>
#include <optional>

#include "stack/include/bt_dev_class.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/security_client_callbacks.h"

/*****************************************************************************
 *  SECURITY MANAGEMENT FUNCTIONS
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         BTM_Sec_Init
 *
 * Description      Initialize the security manager.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_Sec_Init();

/*******************************************************************************
 *
 * Function         BTM_Sec_Free
 *
 * Description      Free resources used by the security manager.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_Sec_Free();

/*******************************************************************************
 *
 * Function         BTM_SetPinType
 *
 * Description      Set PIN type for the device.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SetPinType(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len);

/*******************************************************************************
 *
 * Function         BTM_SecGetDeviceLinkKeyType
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
tBTM_LINK_KEY_TYPE BTM_SecGetDeviceLinkKeyType(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_ConfirmReqReply
 *
 * Description      This function is called to confirm the numeric value for
 *                  Simple Pairing in response to BTM_SP_CFM_REQ_EVT
 *
 * Parameters:      res           - result of the operation tBTM_STATUS::BTM_SUCCESS if
 *                                  success
 *                  bd_addr       - Address of the peer device
 *
 ******************************************************************************/
void BTM_ConfirmReqReply(tBTM_STATUS res, const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_PasskeyReqReply
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
void BTM_PasskeyReqReply(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey);

/*******************************************************************************
 *
 * Function         BTM_ReadLocalOobData
 *
 * Description      This function is called to read the local OOB data from
 *                  LM
 *
 ******************************************************************************/
void BTM_ReadLocalOobData(void);

/*******************************************************************************
 *
 * Function         BTM_PeerSupportsSecureConnections
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
bool BTM_PeerSupportsSecureConnections(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SecRegister
 *
 * Description      Register the security client callback.
 *
 * Returns          true if registered successfully, false otherwise.
 *
 ******************************************************************************/
bool BTM_SecRegister(const tBTM_APPL_INFO* p_cb_info);

/*******************************************************************************
 *
 * Function         BTM_BleLoadLocalKeys
 *
 * Description      Load local BLE keys.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BleLoadLocalKeys(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key);

/*******************************************************************************
 *
 * Function         BTM_SecAddDevice
 *
 * Description      Add/modify device.  This function will be normally called
 *                  during host startup to restore all required information
 *                  stored in the NVRAM.
 *                  dev_class, link_key are NULL if unknown
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecAddDevice(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                      const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                      uint8_t pin_length);

/*******************************************************************************
 *
 * Function         BTM_SecAddBleDevice
 *
 * Description      Add/modify BLE device.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecAddBleDevice(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type,
                         tBLE_ADDR_TYPE addr_type);

/** Free resources associated with the device associated with |bd_addr| address.
 *
 * *** WARNING ***
 * BtmDevice associated with bd_addr becomes invalid after this function
 * is called, also any of its fields. i.e. if you use p_device->bd_addr, it is
 * no longer valid!
 * *** WARNING ***
 *
 * Returns true if removed OK, false if not found or ACL link is active.
 */
bool BTM_SecDeleteDevice(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SecAddBleKey
 *
 * Description      Add/modify BLE key.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecAddBleKey(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                      const tBTM_LE_KEY_VALUE& key);

/*******************************************************************************
 *
 * Function         BTM_SecClearSecurityFlags
 *
 * Description      Reset the security flags (mark as not-paired) for a given
 *                  remove device.
 *
 ******************************************************************************/
void BTM_SecClearSecurityFlags(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SetEncryption
 *
 * Description      Set encryption for the link.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
 *
 ******************************************************************************/
tBTM_STATUS BTM_SetEncryption(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                              tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                              tBTM_BLE_SEC_ACT sec_act);

/*******************************************************************************
 *
 * Function         BTM_IsEncrypted
 *
 * Description      Check if the link is encrypted.
 *
 * Returns          true if encrypted, false otherwise.
 *
 ******************************************************************************/
bool BTM_IsEncrypted(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_SecIsLeSecurityPending
 *
 * Description      Check if LE security is pending.
 *
 * Returns          true if pending, false otherwise.
 *
 ******************************************************************************/
bool BTM_SecIsLeSecurityPending(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_IsBonded
 *
 * Description      Is the specified device is a bonded device
 *
 * Returns          true - dev is bonded
 *
 ******************************************************************************/
bool BTM_IsBonded(const RawAddress& bd_addr, tBT_TRANSPORT transport = BT_TRANSPORT_AUTO);

/*******************************************************************************
 *
 * Function         BTM_SetSecurityLevel
 *
 * Description      Set security level for a service.
 *
 * Returns          true if successful, false otherwise.
 *
 ******************************************************************************/
bool BTM_SetSecurityLevel(bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
                          uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id);

/*******************************************************************************
 *
 * Function         BTM_SecClrService
 *
 * Description      Clear service security record.
 *
 * Returns          number of records cleared.
 *
 ******************************************************************************/
uint8_t BTM_SecClrService(uint8_t service_id);

/*******************************************************************************
 *
 * Function         BTM_SecClrServiceByPsm
 *
 * Description      Clear service security record by PSM.
 *
 * Returns          number of records cleared.
 *
 ******************************************************************************/
uint8_t BTM_SecClrServiceByPsm(uint16_t psm);

/*******************************************************************************
 *
 * Function         BTM_SecBond
 *
 * Description      Initiate bonding.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
 *
 ******************************************************************************/
tBTM_STATUS BTM_SecBond(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                        tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_SecBondCancel
 *
 * Description      Cancel bonding.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
 *
 ******************************************************************************/
tBTM_STATUS BTM_SecBondCancel(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_RemoteOobDataReply
 *
 * Description      Reply to remote OOB data request.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_RemoteOobDataReply(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                            const Octet16& r);

/*******************************************************************************
 *
 * Function         BTM_PINCodeReply
 *
 * Description      Reply to PIN code request.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_PINCodeReply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len,
                      PinCode pin_code);

/*******************************************************************************
 *
 * Function         BTM_SecConfirmReqReply
 *
 * Description      Reply to user confirmation request.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecConfirmReqReply(tBTM_STATUS res, tBT_TRANSPORT transport, const RawAddress bd_addr);

/*******************************************************************************
 *
 * Function         BTM_BleSirkConfirmDeviceReply
 *
 * Description      This procedure confirms requested to validate set device.
 *
 * Parameter        bd_addr     - BD address of the peer
 *                  res         - confirmation result tBTM_STATUS::BTM_SUCCESS if success
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BleSirkConfirmDeviceReply(const RawAddress& bd_addr, tBTM_STATUS res);

/*******************************************************************************
 *
 * Function         BTM_BlePasskeyReply
 *
 * Description      Reply to BLE passkey request.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BlePasskeyReply(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey);

/*******************************************************************************
 *
 * Function         BTM_BleReadSecKeySize
 *
 * Description      Read the security key size for the device.
 *
 * Returns          Key size in bytes.
 *
 ******************************************************************************/
uint8_t BTM_BleReadSecKeySize(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SecHciDeleteStoredLinkKey
 *
 * Description      Instructs the controller to delete the stored link key for the device.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecHciDeleteStoredLinkKey(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_GetSecurityMode
 *
 * Description      Get the current security mode.
 *
 * Returns          Security mode.
 *
 ******************************************************************************/
uint8_t BTM_GetSecurityMode();

/*******************************************************************************
 *
 * Function         BTM_SecReadDevName
 *
 * Description      Read the device name.
 *
 * Returns          Device name string.
 *
 ******************************************************************************/
const char* BTM_SecReadDevName(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SecReadDevClass
 *
 * Description      Read the device class.
 *
 * Returns          Device class.
 *
 ******************************************************************************/
DEV_CLASS BTM_SecReadDevClass(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_SecReportBondLoss
 *
 * Description      Report bond loss.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
 *
 ******************************************************************************/
tBTM_STATUS BTM_SecReportBondLoss(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/** Returns local device encryption root (ER) */
const Octet16& BTM_GetDeviceEncRoot();

/** Returns local device identity root (IR) */
const Octet16& BTM_GetDeviceIDRoot();

/** Return local device DHK. */
const Octet16& BTM_GetDeviceDHK();

/*******************************************************************************
 *
 * Function         BTM_SecurityGrant
 *
 * Description      This function is called to grant security process.
 *
 * Parameters       bd_addr - peer device bd address.
 *                  res     - result of the operation tBTM_STATUS::BTM_SUCCESS if success.
 *                            Otherwise, BTM_REPEATED_ATTEMPTS is too many
 *                            attempts.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTM_SecurityGrant(const RawAddress& bd_addr, tBTM_STATUS res);

/*******************************************************************************
 *
 * Function         BTM_BleConfirmReply
 *
 * Description      This function is called after Security Manager submitted
 *                  numeric comparison request to the application.
 *
 * Parameters:      bd_addr      - Address of the device with which numeric
 *                                 comparison was requested
 *                  res          - comparison result tBTM_STATUS::BTM_SUCCESS if success
 *
 ******************************************************************************/
void BTM_BleConfirmReply(const RawAddress& bd_addr, tBTM_STATUS res);

/*******************************************************************************
 *
 * Function         BTM_BleOobDataReply
 *
 * Description      This function is called to provide the OOB data for
 *                  SMP in response to BTM_LE_OOB_REQ_EVT
 *
 * Parameters:      bd_addr     - Address of the peer device
 *                  res         - result of the operation SMP_SUCCESS if success
 *                  p_data      - simple pairing Randomizer  C.
 *
 ******************************************************************************/
void BTM_BleOobDataReply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len, uint8_t* p_data);

/*******************************************************************************
 *
 * Function         BTM_BleSecureConnectionOobDataReply
 *
 * Description      This function is called to provide the OOB data for
 *                  SMP in response to BTM_LE_OOB_REQ_EVT when secure connection
 *                  data is available
 *
 * Parameters:      bd_addr     - Address of the peer device
 *                  p_c         - pointer to Confirmation
 *                  p_r         - pointer to Randomizer.
 *
 ******************************************************************************/
void BTM_BleSecureConnectionOobDataReply(const RawAddress& bd_addr, uint8_t* p_c, uint8_t* p_r);

/*******************************************************************************
 *
 * Function         BTM_BleDataSignature
 *
 * Description      This function is called to sign the data using AES128 CMAC
 *                  algorithm.
 *
 * Parameter        bd_addr: target device the data to be signed for.
 *                  p_text: singing data
 *                  len: length of the signing data
 *                  signature: output parameter where data signature is going to
 *                             be stored.
 *
 * Returns          true if signing sucessul, otherwise false.
 *
 ******************************************************************************/
bool BTM_BleDataSignature(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                          BLE_SIGNATURE signature);

/*******************************************************************************
 *
 * Function         BTM_BleVerifySignature
 *
 * Description      This function is called to verify the data signature
 *
 * Parameter        bd_addr: target device the data to be signed for.
 *                  p_orig:  original data before signature.
 *                  len: length of the signing data
 *                  counter: counter used when doing data signing
 *                  p_comp: signature to be compared against.

 * Returns          true if signature verified correctly; otherwise false.
 *
 ******************************************************************************/
bool BTM_BleVerifySignature(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len,
                            uint32_t counter, uint8_t* p_comp);

/*******************************************************************************
 *
 * Function         BTM_BleGetPeerLTK
 *
 * Description      This function is used to get the long term key of
 *                  a bonded peer (LE) device.
 *
 * Parameters:      address: address of the peer device
 *
 * Returns          the ltk contained in std::optional if the remote device
 *                  is present in security database
 *                  std::nullopt if the device is not present
 *
 ******************************************************************************/
std::optional<Octet16> BTM_BleGetPeerLTK(const RawAddress address);

/*******************************************************************************
 *
 * Function         BTM_BleGetPeerIRK
 *
 * Description      This function is used to get the IRK of a bonded
 *                  peer (LE) device.
 *
 * Parameters:      address: address of the peer device
 *
 * Returns          the ltk contained in std::optional if the remote device
 *                  is present in security database
 *                  std::nullopt if the device is not present
 *
 ******************************************************************************/
std::optional<Octet16> BTM_BleGetPeerIRK(const RawAddress address);

/*******************************************************************************
 *
 * Function         BTM_BleGetIdentityAddress
 *
 * Description      This function is called to get the identity address
 *                  (with type) of a peer (LE) device.
 *
 * Parameters:      address: address of the peer device
 *
 * Returns          the identity address in std::optional if the remote device
 *                  is present in security database
 *                  std::nullopt if the device is not present
 *
 ******************************************************************************/
std::optional<tBLE_BD_ADDR> BTM_BleGetIdentityAddress(const RawAddress address);

/*****************************************************************************
 *  SECURITY MANAGEMENT FUNCTIONS
 ****************************************************************************/

typedef struct {
  void (*BTM_Sec_Init)();
  void (*BTM_Sec_Free)();

  void (*BTM_SetPinType)(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len);

  tBTM_LINK_KEY_TYPE (*BTM_SecGetDeviceLinkKeyType)(const RawAddress& bd_addr);

  void (*BTM_ConfirmReqReply)(tBTM_STATUS res, const RawAddress& bd_addr);

  void (*BTM_PasskeyReqReply)(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey);

  void (*BTM_ReadLocalOobData)(void);

  bool (*BTM_PeerSupportsSecureConnections)(const RawAddress& bd_addr);

  bool (*BTM_SecRegister)(const tBTM_APPL_INFO* p_cb_info);

  void (*BTM_BleLoadLocalKeys)(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key);

  // Update/Query in-memory device records
  void (*BTM_SecAddDevice)(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                           const PairingType& pairing_type, const LinkKey& link_key,
                           uint8_t key_type, uint8_t pin_length);
  void (*BTM_SecAddBleDevice)(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type,
                              tBLE_ADDR_TYPE addr_type);

  bool (*BTM_SecDeleteDevice)(const RawAddress& bd_addr);

  void (*BTM_SecAddBleKey)(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                           const tBTM_LE_KEY_VALUE& key);

  void (*BTM_SecClearSecurityFlags)(const RawAddress& bd_addr);

  tBTM_STATUS (*BTM_SetEncryption)(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                                   tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                                   tBTM_BLE_SEC_ACT sec_act);
  bool (*BTM_IsEncrypted)(const RawAddress& bd_addr, tBT_TRANSPORT transport);
  bool (*BTM_SecIsLeSecurityPending)(const RawAddress& bd_addr);
  bool (*BTM_IsBonded)(const RawAddress& bd_addr, tBT_TRANSPORT transport);

  // Secure service management
  bool (*BTM_SetSecurityLevel)(bool outgoing, const char* p_name, uint8_t service_id,
                               uint16_t sec_level, uint16_t psm, uint32_t mx_proto_id,
                               uint32_t mx_chan_id);
  uint8_t (*BTM_SecClrService)(uint8_t service_id);
  uint8_t (*BTM_SecClrServiceByPsm)(uint16_t psm);

  // Pairing related APIs
  tBTM_STATUS (*BTM_SecBond)(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                             tBT_TRANSPORT transport);
  tBTM_STATUS (*BTM_SecBondCancel)(const RawAddress& bd_addr);

  void (*BTM_RemoteOobDataReply)(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                                 const Octet16& r);
  void (*BTM_PINCodeReply)(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len,
                           PinCode pin_code);
  void (*BTM_SecConfirmReqReply)(tBTM_STATUS res, tBT_TRANSPORT transport,
                                 const RawAddress bd_addr);
  void (*BTM_BleSirkConfirmDeviceReply)(const RawAddress& bd_addr, tBTM_STATUS res);

  void (*BTM_BlePasskeyReply)(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey);

  uint8_t (*BTM_BleReadSecKeySize)(const RawAddress& bd_addr);

  void (*BTM_SecHciDeleteStoredLinkKey)(const RawAddress& bd_addr);

  // other misc APIs
  uint8_t (*BTM_GetSecurityMode)();

  // remote name request related APIs
  // TODO: remove them from this structure
  const char* (*BTM_SecReadDevName)(const RawAddress& bd_addr);
  DEV_CLASS (*BTM_SecReadDevClass)(const RawAddress& bd_addr);

  tBTM_STATUS (*BTM_SecReportBondLoss)(const RawAddress& bd_addr, tBT_TRANSPORT transport);

  // BLE related APIs
  const Octet16& (*BTM_GetDeviceEncRoot)();
  const Octet16& (*BTM_GetDeviceIDRoot)();
  const Octet16& (*BTM_GetDeviceDHK)();
  void (*BTM_SecurityGrant)(const RawAddress& bd_addr, tBTM_STATUS res);
  void (*BTM_BleConfirmReply)(const RawAddress& bd_addr, tBTM_STATUS res);
  void (*BTM_BleOobDataReply)(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len,
                              uint8_t* p_data);
  void (*BTM_BleSecureConnectionOobDataReply)(const RawAddress& bd_addr, uint8_t* p_c,
                                              uint8_t* p_r);
  bool (*BTM_BleDataSignature)(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                               BLE_SIGNATURE signature);
  bool (*BTM_BleVerifySignature)(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len,
                                 uint32_t counter, uint8_t* p_comp);
  std::optional<Octet16> (*BTM_BleGetPeerLTK)(const RawAddress address);
  std::optional<Octet16> (*BTM_BleGetPeerIRK)(const RawAddress address);
  std::optional<tBLE_BD_ADDR> (*BTM_BleGetIdentityAddress)(const RawAddress address);

  tBTM_BLE_SEC_REQ_ACT (*BTM_BleLinkSecCheck)(const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req);
  void (*BTM_BleLtkRequestReply)(const RawAddress& bda, bool use_stk, const Octet16& stk);
  tBTM_STATUS (*BTM_BleStartEncrypt)(const RawAddress& bda, bool use_stk, Octet16* p_stk);
  tBTM_STATUS (*BTM_BleStartSecCheck)(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                      tBTM_SEC_CALLBACK* p_callback, void* p_ref_data);
  bool (*BTM_GetLocalDiv)(const RawAddress& bd_addr, uint16_t* p_div);
  bool (*BTM_BleGetEncKeyType)(const RawAddress& bd_addr, uint8_t* p_key_types);
  void (*BTM_SecSaveLeKey)(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                           const tBTM_LE_KEY_VALUE& key, bool pass_to_application);
  void (*BTM_BleUpdateSecKeySize)(const RawAddress& bd_addr, uint8_t enc_key_size);
  void (*BTM_BleResetId)(void);
} SecurityClientInterface;

const SecurityClientInterface& get_security_client_interface();
