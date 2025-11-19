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
 *  This file contains functions for BLE device control utilities, and LE
 *  security functions.
 *
 ******************************************************************************/

#define LOG_TAG "ble"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "base/functional/bind.h"
#include "btif/include/btif_storage.h"
#include "hci/controller.h"
#include "main/shim/entry.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/acl_api.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btu_hcif.h"
#include "stack/include/gatt_api.h"
#include "stack/include/hcimsgs.h"
#include "stack/l2cap/l2c_int.h"

using namespace bluetooth;

/*******************************************************************************
 *
 * Function         BTM_BleReceiverTest
 *
 * Description      This function is called to start the LE Receiver test
 *
 * Parameter       rx_freq - Frequency Range
 *               p_cmd_cmpl_cback - Command Complete callback
 *
 ******************************************************************************/
void BTM_BleReceiverTest(uint8_t rx_freq, tBTM_CMPL_CB* p_cmd_cmpl_cback) {
  btm_cb.devcb.p_le_test_cmd_cmpl_cb = p_cmd_cmpl_cback;

  btsnd_hcic_ble_receiver_test(rx_freq);
}

/*******************************************************************************
 *
 * Function         BTM_BleTransmitterTest
 *
 * Description      This function is called to start the LE Transmitter test
 *
 * Parameter       tx_freq - Frequency Range
 *                       test_data_len - Length in bytes of payload data in each
 *                                       packet
 *                       packet_payload - Pattern to use in the payload
 *                       p_cmd_cmpl_cback - Command Complete callback
 *
 ******************************************************************************/
void BTM_BleTransmitterTest(uint8_t tx_freq, uint8_t test_data_len, uint8_t packet_payload,
                            tBTM_CMPL_CB* p_cmd_cmpl_cback) {
  btm_cb.devcb.p_le_test_cmd_cmpl_cb = p_cmd_cmpl_cback;
  btsnd_hcic_ble_transmitter_test(tx_freq, test_data_len, packet_payload);
}

/*******************************************************************************
 *
 * Function         BTM_BleTestEnd
 *
 * Description      This function is called to stop the in-progress TX or RX
 *                  test
 *
 * Parameter       p_cmd_cmpl_cback - Command complete callback
 *
 ******************************************************************************/
void BTM_BleTestEnd(tBTM_CMPL_CB* p_cmd_cmpl_cback) {
  btm_cb.devcb.p_le_test_cmd_cmpl_cb = p_cmd_cmpl_cback;

  btsnd_hcic_ble_test_end();
}

/*******************************************************************************
 * Internal Functions
 ******************************************************************************/
void btm_ble_test_command_complete(uint8_t* p) {
  tBTM_CMPL_CB* p_cb = btm_cb.devcb.p_le_test_cmd_cmpl_cb;

  btm_cb.devcb.p_le_test_cmd_cmpl_cb = NULL;

  if (p_cb) {
    (*p_cb)(p);
  }
}

/*******************************************************************************
 *
 * Function         BTM_UseLeLink
 *
 * Description      This function is to select the underlying physical link to
 *                  use.
 *
 * Returns          true to use LE, false use BR/EDR.
 *
 ******************************************************************************/
bool BTM_UseLeLink(const RawAddress& bd_addr) {
  if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_BR_EDR)) {
    return false;
  } else if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
    return true;
  }

  tBT_DEVICE_TYPE dev_type;
  tBLE_ADDR_TYPE addr_type;
  get_btm_client_interface().peer.BTM_ReadDevInfo(bd_addr, &dev_type, &addr_type);
  return dev_type == BT_DEVICE_TYPE_BLE;
}

static void read_phy_cb(base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb,
                        uint8_t* data, uint16_t len) {
  uint8_t status, tx_phy, rx_phy;
  uint16_t handle;

  log::assert_that(len == 5, "Received bad response length:{}", len);
  uint8_t* pp = data;
  STREAM_TO_UINT8(status, pp);
  STREAM_TO_UINT16(handle, pp);
  handle = handle & 0x0FFF;
  STREAM_TO_UINT8(tx_phy, pp);
  STREAM_TO_UINT8(rx_phy, pp);

  std::move(cb).Run(tx_phy, rx_phy, status);
}

/*******************************************************************************
 *
 * Function         BTM_GetRemoteDeviceName
 *
 * Description      This function is called to get the dev name of remote device
 *                  from NV
 *
 * Returns          TRUE if success; otherwise failed.
 *
 ******************************************************************************/
bool BTM_GetRemoteDeviceName(const RawAddress& bd_addr, BD_NAME bd_name) {
  bool ret = false;
  bt_bdname_t bdname = {};
  bt_property_t prop_name;
  BTIF_STORAGE_FILL_PROPERTY(&prop_name, BT_PROPERTY_BDNAME, sizeof(bt_bdname_t), &bdname);

  if (btif_storage_get_remote_device_property(&bd_addr, &prop_name)) {
    bd_name_copy(bd_name, bdname.name);
    ret = true;
  }
  log::verbose("bd_addr:{} name:{}", bd_addr, reinterpret_cast<const char*>(bdname.name));
  return ret;
}

/********************************************************
 *
 * Function         BTM_BleSetPrefConnParams
 *
 * Description      Set a peripheral's preferred connection parameters
 *
 * Parameters:      bd_addr          - BD address of the peripheral
 *                  scan_interval: scan interval
 *                  scan_window: scan window
 *                  min_conn_int     - minimum preferred connection interval
 *                  max_conn_int     - maximum preferred connection interval
 *                  peripheral_latency    - preferred peripheral latency
 *                  supervision_tout - preferred supervision timeout
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BleSetPrefConnParams(const RawAddress& bd_addr, uint16_t min_conn_int,
                              uint16_t max_conn_int, uint16_t peripheral_latency,
                              uint16_t supervision_tout) {
  BtmDevice* p_device = btm_get_dev(bd_addr);

  log::verbose("min:{},max:{},latency:{},tout:{}", min_conn_int, max_conn_int, peripheral_latency,
               supervision_tout);

  if (BTM_BLE_ISVALID_PARAM(min_conn_int, BTM_BLE_CONN_INT_MIN, BTM_BLE_CONN_INT_MAX) &&
      BTM_BLE_ISVALID_PARAM(max_conn_int, BTM_BLE_CONN_INT_MIN, BTM_BLE_CONN_INT_MAX) &&
      BTM_BLE_ISVALID_PARAM(supervision_tout, BTM_BLE_CONN_SUP_TOUT_MIN,
                            BTM_BLE_CONN_SUP_TOUT_MAX) &&
      (peripheral_latency <= BTM_BLE_CONN_LATENCY_MAX ||
       peripheral_latency == BTM_BLE_CONN_PARAM_UNDEF)) {
    if (p_device) {
      /* expect conn int and stout and peripheral latency to be updated all
       * together
       */
      if (min_conn_int != BTM_BLE_CONN_PARAM_UNDEF || max_conn_int != BTM_BLE_CONN_PARAM_UNDEF) {
        if (min_conn_int != BTM_BLE_CONN_PARAM_UNDEF) {
          p_device->conn_params.min_conn_int = min_conn_int;
        } else {
          p_device->conn_params.min_conn_int = max_conn_int;
        }

        if (max_conn_int != BTM_BLE_CONN_PARAM_UNDEF) {
          p_device->conn_params.max_conn_int = max_conn_int;
        } else {
          p_device->conn_params.max_conn_int = min_conn_int;
        }

        if (peripheral_latency != BTM_BLE_CONN_PARAM_UNDEF) {
          p_device->conn_params.peripheral_latency = peripheral_latency;
        } else {
          p_device->conn_params.peripheral_latency = BTM_BLE_CONN_PERIPHERAL_LATENCY_DEF;
        }

        if (supervision_tout != BTM_BLE_CONN_PARAM_UNDEF) {
          p_device->conn_params.supervision_tout = supervision_tout;
        } else {
          p_device->conn_params.supervision_tout = BTM_BLE_CONN_TIMEOUT_DEF;
        }
      }

    } else {
      log::error("Unknown Device, setting rejected");
    }
  } else {
    log::error("Illegal Connection Parameters");
  }
}

/*******************************************************************************
 *
 * Function         BTM_ReadDevInfo
 *
 * Description      This function is called to read the device/address type
 *                  of BD address.
 *
 * Parameter        remote_bda: remote device address
 *                  p_dev_type: output parameter to read the device type.
 *                  p_addr_type: output parameter to read the address type.
 *
 ******************************************************************************/
void BTM_ReadDevInfo(const RawAddress& remote_bda, tBT_DEVICE_TYPE* p_dev_type,
                     tBLE_ADDR_TYPE* p_addr_type) {
  BtmDevice* p_device = btm_get_dev(remote_bda);
  tBTM_INQ_INFO* p_inq_info = BTM_InqDbRead(remote_bda);

  *p_addr_type = BLE_ADDR_PUBLIC;

  if (!p_device) {
    *p_dev_type = BT_DEVICE_TYPE_BREDR;
    /* Check with the BT manager if details about remote device are known */
    if (p_inq_info != NULL) {
      *p_dev_type = p_inq_info->results.device_type;
      *p_addr_type = p_inq_info->results.ble_addr_type;
    } else {
      /* unknown device, assume BR/EDR */
      log::verbose("unknown device, BR/EDR assumed");
    }
  } else /* there is a security device record existing */
  {
    /* new inquiry result, merge device type in security device record */
    if (p_inq_info) {
      p_device->device_type |= p_inq_info->results.device_type;
      if (is_ble_addr_type_known(p_inq_info->results.ble_addr_type)) {
        p_device->ble.SetAddressType(p_inq_info->results.ble_addr_type);
      } else {
        log::warn(
                "Please do not update device record from anonymous le "
                "advertisement");
      }
    }

    if (p_device->bd_addr == remote_bda && p_device->ble.pseudo_addr == remote_bda) {
      *p_dev_type = p_device->device_type;
      *p_addr_type = p_device->ble.AddressType();
    } else if (p_device->ble.pseudo_addr == remote_bda) {
      *p_dev_type = BT_DEVICE_TYPE_BLE;
      *p_addr_type = p_device->ble.AddressType();
    } else /* matching static address only */ {
      if (p_device->device_type != BT_DEVICE_TYPE_UNKNOWN) {
        *p_dev_type = p_device->device_type;
      } else {
        log::warn("device_type not set; assuming BR/EDR");
        *p_dev_type = BT_DEVICE_TYPE_BREDR;
      }
      *p_addr_type = BLE_ADDR_PUBLIC;
    }
  }
  log::debug("Determined device_type:{} addr_type:{}", DeviceTypeText(*p_dev_type),
             AddressTypeText(*p_addr_type));
}

/*******************************************************************************
 *
 * Function         BTM_ReadConnectedTransportAddress
 *
 * Description      This function is called to read the paired device/address
 *                  type of other device paired corresponding to the BD_address
 *
 * Parameter        remote_bda: remote device address, carry out the transport
 *                              address
 *                  transport: active transport
 *
 * Return           true if an active link is identified; false otherwise
 *
 ******************************************************************************/
bool BTM_ReadConnectedTransportAddress(RawAddress* remote_bda, tBT_TRANSPORT transport) {
  const BtmDevice* p_device = btm_find_dev(*remote_bda);

  /* if no device can be located, return */
  if (p_device == nullptr) {
    return false;
  }

  if (transport == BT_TRANSPORT_BR_EDR) {
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(p_device->bd_addr, transport)) {
      *remote_bda = p_device->bd_addr;
      return true;
    } else if (p_device->device_type & BT_DEVICE_TYPE_BREDR) {
      *remote_bda = p_device->bd_addr;
    } else {
      *remote_bda = RawAddress::kEmpty;
    }
    return false;
  }

  if (transport == BT_TRANSPORT_LE) {
    *remote_bda = p_device->ble.pseudo_addr;
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(p_device->ble.pseudo_addr,
                                                              transport)) {
      return true;
    } else {
      return false;
    }
  }

  return false;
}

/*******************************************************************************
 *
 * Function         BTM_GetConnectedTransportAddress
 *
 * Description      This function gets the pseudo and identity address of the
 *                  remote device
 *
 * Parameter        remote_bda: remote device address
 *
 * Return           pseudo and identity address pair of the remote device
 *
 ******************************************************************************/
std::pair<RawAddress, RawAddress> BTM_GetConnectedTransportAddress(RawAddress remote_bda) {
  const BtmDevice* p_device = btm_find_dev(remote_bda);
  std::pair<RawAddress, RawAddress> pseudo_identity_addr_pair =
          std::make_pair(RawAddress::kEmpty, RawAddress::kEmpty);

  /* if no device can be located, return */
  if (p_device == nullptr) {
    return pseudo_identity_addr_pair;
  }

  // Get pseudo address
  pseudo_identity_addr_pair.first = p_device->ble.pseudo_addr;

  // Get the identity address
  if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(p_device->bd_addr,
                                                            BT_TRANSPORT_BR_EDR) ||
      (p_device->device_type & BT_DEVICE_TYPE_BREDR)) {
    pseudo_identity_addr_pair.second = p_device->bd_addr;
  }

  return pseudo_identity_addr_pair;
}

tBTM_STATUS BTM_SetBleDataLength(const RawAddress& bd_addr, uint16_t tx_pdu_length,
                                 bool is_privileged_client) {
  if (!bluetooth::shim::GetController()->SupportsBleDataPacketLengthExtension()) {
    log::info("Local controller does not support le packet extension");
    return tBTM_STATUS::BTM_ILLEGAL_VALUE;
  }

  log::info("bd_addr:{}, tx_pdu_length:{}", bd_addr, tx_pdu_length);

  auto p_device = btm_get_dev(bd_addr);
  if (p_device == nullptr) {
    log::error("Device {} not found", bd_addr);
    return tBTM_STATUS::BTM_UNKNOWN_ADDR;
  }

  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(bd_addr, BT_TRANSPORT_LE);
  if (p_lcb == nullptr) {
    log::error("L2CAP lcb for {} not found", bd_addr);
    return tBTM_STATUS::BTM_UNKNOWN_ADDR;
  }

  if (tx_pdu_length > BTM_BLE_DATA_SIZE_MAX) {
    tx_pdu_length = BTM_BLE_DATA_SIZE_MAX;
  } else if (tx_pdu_length < BTM_BLE_DATA_SIZE_MIN) {
    tx_pdu_length = BTM_BLE_DATA_SIZE_MIN;
  }

  if (com_android_bluetooth_flags_set_max_data_length_for_lecoc() &&
      p_lcb->is_datalen_set_by_privileged_client() && !is_privileged_client) {
    log::info(
            "Data length set by prev client can't be overridden by non-privileged clienit, "
            "currently set to {}",
            p_device->get_suggested_tx_octets());
    return tBTM_STATUS::BTM_MODE_UNSUPPORTED;
  }

  /* privileged client can override to have lesser Data length & this can happen
   * multiple times from privileged clients.
   */
  if (p_device->get_suggested_tx_octets() >= tx_pdu_length && !is_privileged_client) {
    log::info("Suggested TX octet already set to controller {} >= {}",
              p_device->get_suggested_tx_octets(), tx_pdu_length);
    return tBTM_STATUS::BTM_SUCCESS;
  }

  uint16_t tx_time = BTM_BLE_DATA_TX_TIME_MAX_LEGACY;

  if (bluetooth::shim::GetController()->GetLocalVersionInformation().hci_version_ >=
      bluetooth::hci::HciVersion::V_5_0) {
    tx_time = BTM_BLE_DATA_TX_TIME_MAX;
  }

  if (!get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
    log::info("Unable to set data length because no le acl link connected to device");
    return tBTM_STATUS::BTM_WRONG_MODE;
  }

  uint16_t hci_handle =
          get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);

  if (!acl_peer_supports_ble_packet_extension(hci_handle)) {
    log::info("Remote device unable to support le packet extension");
    return tBTM_STATUS::BTM_ILLEGAL_VALUE;
  }

  tx_pdu_length = std::min<uint16_t>(
          tx_pdu_length,
          bluetooth::shim::GetController()->GetLeMaximumDataLength().supported_max_tx_octets_);
  tx_time = std::min<uint16_t>(
          tx_time,
          bluetooth::shim::GetController()->GetLeMaximumDataLength().supported_max_tx_time_);

  log::info("Requesting actual tx_pdu_length:{} and tx_time:{} for bd_addr:{}", tx_pdu_length,
            tx_time, bd_addr);

  btsnd_hcic_ble_set_data_length(hci_handle, tx_pdu_length, tx_time);
  p_device->set_suggested_tx_octect(tx_pdu_length);
  if (is_privileged_client) {
    p_lcb->set_is_datalen_set_by_privileged_client(true);
  }

  return tBTM_STATUS::BTM_SUCCESS;
}

/*******************************************************************************
 *
 * Function         BTM_BleReadPhy
 *
 * Description      To read the current PHYs for specified LE connection
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BleReadPhy(const RawAddress& bd_addr,
                    base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb) {
  if (!get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
    log::error("Wrong mode: no LE link exist or LE not supported");
    std::move(cb).Run(0, 0, HCI_ERR_NO_CONNECTION);
    return;
  }

  // The connection PHY is always LE_1M when the controller supports
  // neither LE_2M nor LE_CODED PHYs.
  if (!bluetooth::shim::GetController()->SupportsBle2mPhy() &&
      !bluetooth::shim::GetController()->SupportsBleCodedPhy()) {
    std::move(cb).Run(1, 1, HCI_SUCCESS);
    return;
  }

  uint16_t handle = get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);

  constexpr uint8_t kLen = HCIC_PARAM_SIZE_BLE_READ_PHY;
  uint8_t data[kLen];
  uint8_t* pp = data;
  UINT16_TO_STREAM(pp, handle);
  btu_hcif_send_cmd_with_cb(HCI_BLE_READ_PHY, data, kLen,
                            base::BindOnce(&read_phy_cb, std::move(cb)));
}

void BTM_BleSetPhy(const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys,
                   uint16_t phy_options) {
  if (!get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
    log::info(
            "Unable to set phy preferences because no le acl is connected to "
            "device");
    return;
  }

  uint8_t all_phys = 0;
  if (tx_phys == 0) {
    all_phys &= 0x01;
  }
  if (rx_phys == 0) {
    all_phys &= 0x02;
  }

  uint16_t handle = get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);

  // checking if local controller supports it!
  if (!bluetooth::shim::GetController()->SupportsBle2mPhy() &&
      !bluetooth::shim::GetController()->SupportsBleCodedPhy()) {
    log::info("Local controller unable to support setting of le phy parameters");
    gatt_notify_phy_updated(static_cast<tHCI_STATUS>(GATT_REQ_NOT_SUPPORTED), handle, tx_phys,
                            rx_phys);
    return;
  }

  if (!acl_peer_supports_ble_2m_phy(handle) && !acl_peer_supports_ble_coded_phy(handle)) {
    log::info("Remote device unable to support setting of le phy parameter");
    gatt_notify_phy_updated(static_cast<tHCI_STATUS>(GATT_REQ_NOT_SUPPORTED), handle, tx_phys,
                            rx_phys);
    return;
  }

  constexpr uint8_t kLen = HCIC_PARAM_SIZE_BLE_SET_PHY;
  uint8_t data[kLen];
  uint8_t* pp = data;
  UINT16_TO_STREAM(pp, handle);
  UINT8_TO_STREAM(pp, all_phys);
  UINT8_TO_STREAM(pp, tx_phys);
  UINT8_TO_STREAM(pp, rx_phys);
  UINT16_TO_STREAM(pp, phy_options);
  btu_hcif_send_cmd_with_cb(HCI_BLE_SET_PHY, data, kLen, base::BindOnce([](uint8_t*, uint16_t) {}));
}
