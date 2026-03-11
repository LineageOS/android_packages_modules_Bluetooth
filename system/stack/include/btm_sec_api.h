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

class SecurityClientInterface {
public:
  virtual ~SecurityClientInterface() = default;

  /** Initialize the security manager. */
  virtual void BTM_Sec_Init() const = 0;

  /** Free resources used by the security manager. */
  virtual void BTM_Sec_Free() const = 0;

  /** Set PIN type for the device. */
  virtual void BTM_SetPinType(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len) const = 0;

  /**
   * This function is called to obtain link key type for the device.
   * It returns tBTM_STATUS::BTM_SUCCESS if link key is available, or tBTM_STATUS::BTM_UNKNOWN_ADDR
   * if Security Manager does not know about the device or device record does not contain link key
   * info. Returns BTM_LKEY_TYPE_IGNORE if link key is unknown, link type otherwise.
   */
  virtual tBTM_LINK_KEY_TYPE BTM_SecGetDeviceLinkKeyType(const RawAddress& bd_addr) const = 0;

  /**
   * This function is called to confirm the numeric value for Simple Pairing in response to
   * BTM_SP_CFM_REQ_EVT
   *  Parameters:      res           - result of the operation tBTM_STATUS::BTM_SUCCESS if success
   *                   bd_addr       - Address of the peer device
   */
  virtual void BTM_ConfirmReqReply(tBTM_STATUS res, const RawAddress& bd_addr) const = 0;

  /**
   * This function is called to provide the passkey for Simple Pairing in response to
   * BTM_SP_KEY_REQ_EVT
   *  Parameters:      res     - result of the operation tBTM_STATUS::BTM_SUCCESS if success
   *                   bd_addr - Address of the peer device
   *                   passkey - numeric value in the range of
   *                   BTM_MIN_PASSKEY_VAL(0) - BTM_MAX_PASSKEY_VAL(999999(0xF423F)).
   */
  virtual void BTM_PasskeyReqReply(tBTM_STATUS res, const RawAddress& bd_addr,
                                   uint32_t passkey) const = 0;

  /** This function is called to read the local OOB data from LM */
  virtual void BTM_ReadLocalOobData(void) const = 0;

  /**
   * This function is called to check if the peer supports BR/EDR Secure Connections.
   *  Parameters:      bd_addr - address of the peer
   *  Returns          true if BR/EDR Secure Connections are supported by the peer, else false.
   */
  virtual bool BTM_PeerSupportsSecureConnections(const RawAddress& bd_addr) const = 0;

  /**
   * Register the security client callback.
   * Returns true if registered successfully, false otherwise.
   */
  virtual bool BTM_SecRegister(const BtmAppReg& app_reg) const = 0;

  /** Load local BLE keys. */
  virtual void BTM_BleLoadLocalKeys(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key) const = 0;

  /**
   * Add/modify device.
   * This function will be normally called during host startup to restore all required
   * information stored in the NVRAM. dev_class, link_key are NULL if unknown
   */
  virtual void BTM_SecAddDevice(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                                const PairingType& pairing_type, const LinkKey& link_key,
                                uint8_t key_type, uint8_t pin_length) const = 0;

  /** Add/modify BLE device. */
  virtual void BTM_SecAddBleDevice(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type,
                                   tBLE_ADDR_TYPE addr_type) const = 0;

  /**
   * Free resources associated with the device associated with |bd_addr| address.
   *  *** WARNING ***
   *  BtmDevice associated with bd_addr becomes invalid after this function
   *  is called, also any of its fields. i.e. if you use p_device->bd_addr, it is
   *  no longer valid!
   *  *** WARNING ***
   *  Returns true if removed OK, false if not found or ACL link is active.
   */
  virtual bool BTM_SecDeleteDevice(const RawAddress& bd_addr) const = 0;

  /** Add/modify BLE key. */
  virtual void BTM_SecAddBleKey(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                                const tBTM_LE_KEY_VALUE& key) const = 0;

  /** Reset the security flags (mark as not-paired) for a given remove device. */
  virtual void BTM_SecClearSecurityFlags(const RawAddress& bd_addr) const = 0;

  /**
   * Set encryption for the link.
   * Returns tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
   */
  virtual tBTM_STATUS BTM_SetEncryption(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                                        tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                                        tBTM_BLE_SEC_ACT sec_act) const = 0;

  /**
   * Check if the link is encrypted.
   * Returns true if encrypted, false otherwise.
   */
  virtual bool BTM_IsEncrypted(const RawAddress& bd_addr, tBT_TRANSPORT transport) const = 0;

  /**
   * Check if LE security is pending.
   * Returns true if pending, false otherwise.
   */
  virtual bool BTM_SecIsLeSecurityPending(const RawAddress& bd_addr) const = 0;

  /**
   * Is the specified device is a bonded device.
   * Returns true if dev is bonded, false otherwise.
   */
  virtual bool BTM_IsBonded(const RawAddress& bd_addr,
                            tBT_TRANSPORT transport = BT_TRANSPORT_AUTO) const = 0;

  /**
   * Set security level for a service.
   * Returns true if successful, false otherwise.
   */
  virtual bool BTM_SetSecurityLevel(bool outgoing, const char* p_name, uint8_t service_id,
                                    uint16_t sec_level, uint16_t psm, uint32_t mx_proto_id,
                                    uint32_t mx_chan_id) const = 0;

  /**
   * Clear service security record.
   * Returns number of records cleared.
   */
  virtual uint8_t BTM_SecClrService(uint8_t service_id) const = 0;

  /**
   * Clear service security record by PSM.
   * Returns number of records cleared.
   */
  virtual uint8_t BTM_SecClrServiceByPsm(uint16_t psm) const = 0;

  /**
   * Initiate bonding.
   * Returns tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
   */
  virtual tBTM_STATUS BTM_SecBond(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                                  tBT_TRANSPORT transport) const = 0;

  /**
   * Cancel bonding.
   * Returns tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
   */
  virtual tBTM_STATUS BTM_SecBondCancel(const RawAddress& bd_addr) const = 0;

  /** Reply to remote OOB data request. */
  virtual void BTM_RemoteOobDataReply(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                                      const Octet16& r) const = 0;

  /** Reply to PIN code request. */
  virtual void BTM_PINCodeReply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len,
                                PinCode pin_code) const = 0;

  /** Reply to user confirmation request. */
  virtual void BTM_SecConfirmReqReply(tBTM_STATUS res, tBT_TRANSPORT transport,
                                      const RawAddress bd_addr) const = 0;

  /**
   * This procedure confirms requested to validate set device.
   *  Parameter        bd_addr     - BD address of the peer
   *                   res         - confirmation result tBTM_STATUS::BTM_SUCCESS if success
   */
  virtual void BTM_BleSirkConfirmDeviceReply(const RawAddress& bd_addr, tBTM_STATUS res) const = 0;

  /** Reply to BLE passkey request. */
  virtual void BTM_BlePasskeyReply(const RawAddress& bd_addr, tBTM_STATUS res,
                                   uint32_t passkey) const = 0;

  /**
   * Read the security key size for the device.
   * Returns the key size in bytes.
   */
  virtual uint8_t BTM_BleReadSecKeySize(const RawAddress& bd_addr) const = 0;

  /** Instructs the controller to delete the stored link key for the device. */
  virtual void BTM_SecHciDeleteStoredLinkKey(const RawAddress& bd_addr) const = 0;

  /** Get the current security mode. */
  virtual uint8_t BTM_GetSecurityMode() const = 0;

  // remote name request related APIs
  // TODO: remove them from this structure

  /** Read the device name. */
  virtual const char* BTM_SecReadDevName(const RawAddress& bd_addr) const = 0;

  /** Read the device class. */
  virtual DEV_CLASS BTM_SecReadDevClass(const RawAddress& bd_addr) const = 0;

  /**
   * Report bond loss.
   * Returns tBTM_STATUS::BTM_SUCCESS if successful, error code otherwise.
   */
  virtual tBTM_STATUS BTM_SecReportBondLoss(const RawAddress& bd_addr,
                                            tBT_TRANSPORT transport) const = 0;

  /** Returns local device encryption root (ER) */
  virtual const Octet16& BTM_GetDeviceEncRoot() const = 0;

  /** Returns local device identity root (IR) */
  virtual const Octet16& BTM_GetDeviceIDRoot() const = 0;

  /** Return local device DHK. */
  virtual const Octet16& BTM_GetDeviceDHK() const = 0;

  /**
   * This function is called to grant security process.
   *  Parameters       bd_addr - peer device bd address.
   *                   res     - result of the operation tBTM_STATUS::BTM_SUCCESS if success.
   *                             Otherwise, BTM_REPEATED_ATTEMPTS is too many attempts.
   */
  virtual void BTM_SecurityGrant(const RawAddress& bd_addr, tBTM_STATUS res) const = 0;

  /**
   * This function is called after Security Manager submitted numeric comparison request to the
   * application.
   *  Parameters:      bd_addr      - Address of the device with which numeric
   *                                  comparison was requested
   *                   res          - comparison result tBTM_STATUS::BTM_SUCCESS if success
   */
  virtual void BTM_BleConfirmReply(const RawAddress& bd_addr, tBTM_STATUS res) const = 0;

  /**
   * This function is called to provide the OOB data for SMP in response to BTM_LE_OOB_REQ_EVT.
   *  Parameters:      bd_addr     - Address of the peer device
   *                   res         - result of the operation SMP_SUCCESS if success
   *                   p_data      - simple pairing Randomizer  C.
   */
  virtual void BTM_BleOobDataReply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len,
                                   uint8_t* p_data) const = 0;

  /**
   * This function is called to provide the OOB data for SMP in response to BTM_LE_OOB_REQ_EVT when
   * secure connection data is available.
   *  Parameters:      bd_addr     - Address of the peer device
   *                   p_c         - pointer to Confirmation
   *                   p_r         - pointer to Randomizer.
   */
  virtual void BTM_BleSecureConnectionOobDataReply(const RawAddress& bd_addr, uint8_t* p_c,
                                                   uint8_t* p_r) const = 0;

  /**
   * This function is called to sign the data using AES128 CMAC algorithm.
   *  Parameter        bd_addr: target device the data to be signed for.
   *                   p_text: singing data
   *                   len: length of the signing data
   *                   signature: output parameter where data signature is going to be stored.
   *  Returns          true if signing sucessul, otherwise false.
   */
  virtual bool BTM_BleDataSignature(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                                    BLE_SIGNATURE signature) const = 0;

  /**
   * This function is called to verify the data signature
   *  Parameter        bd_addr: target device the data to be signed for.
   *                   p_orig:  original data before signature.
   *                   len: length of the signing data
   *                   counter: counter used when doing data signing
   *                   p_comp: signature to be compared against.
   *  Returns          true if signature verified correctly; otherwise false.
   */
  virtual bool BTM_BleVerifySignature(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len,
                                      uint32_t counter, uint8_t* p_comp) const = 0;

  /**
   * This function is used to get the long term key of a bonded peer (LE) device.
   *  Parameters:      address: address of the peer device
   *  Returns          the ltk contained in std::optional if the remote device
   *                   is present in security database
   *                   std::nullopt if the device is not present
   */
  virtual std::optional<Octet16> BTM_BleGetPeerLTK(const RawAddress address) const = 0;

  /**
   * This function is used to get the IRK of a bonded peer (LE) device.
   *  Parameters:      address: address of the peer device
   *  Returns          the ltk contained in std::optional if the remote device
   *                   is present in security database
   *                   std::nullopt if the device is not present
   */
  virtual std::optional<Octet16> BTM_BleGetPeerIRK(const RawAddress address) const = 0;

  /**
   * This function is called to get the identity address (with type) of a peer (LE) device.
   *  Parameters:      address: address of the peer device
   *  Returns          the identity address in std::optional if the remote device
   *                   is present in security database
   *                   std::nullopt if the device is not present
   */
  virtual std::optional<tBLE_BD_ADDR> BTM_BleGetIdentityAddress(const RawAddress address) const = 0;

  virtual tBTM_BLE_SEC_REQ_ACT BTM_BleLinkSecCheck(const RawAddress& bd_addr,
                                                   tBTM_LE_AUTH_REQ auth_req) const = 0;
  virtual void BTM_BleLtkRequestReply(const RawAddress& bda, bool use_stk,
                                      const Octet16& stk) const = 0;
  virtual tBTM_STATUS BTM_BleStartEncrypt(const RawAddress& bda, bool use_stk,
                                          Octet16* p_stk) const = 0;
  virtual tBTM_STATUS BTM_BleStartSecCheck(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                           tBTM_SEC_CALLBACK* p_callback,
                                           void* p_ref_data) const = 0;
  virtual bool BTM_GetLocalDiv(const RawAddress& bd_addr, uint16_t* p_div) const = 0;
  virtual bool BTM_BleGetEncKeyType(const RawAddress& bd_addr, uint8_t* p_key_types) const = 0;
  virtual void BTM_SecSaveLeKey(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                                const tBTM_LE_KEY_VALUE& key, bool pass_to_application) const = 0;
  virtual void BTM_BleUpdateSecKeySize(const RawAddress& bd_addr, uint8_t enc_key_size) const = 0;
  virtual void BTM_BleResetId(void) const = 0;
};

const SecurityClientInterface& get_security_client_interface();
