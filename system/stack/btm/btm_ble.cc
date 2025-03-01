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

#include <cstdint>

#include "base/functional/bind.h"
#include "hci/controller_interface.h"
#include "main/shim/entry.h"
#include "stack/btm/btm_int_types.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/acl_api.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btu_hcif.h"
#include "stack/include/gatt_api.h"
#include "stack/include/hcimsgs.h"

using namespace bluetooth;

extern tBTM_CB btm_cb;

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

static void read_phy_cb(base::Callback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb,
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

  cb.Run(tx_phy, rx_phy, status);
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
                    base::Callback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb) {
  if (!get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
    log::error("Wrong mode: no LE link exist or LE not supported");
    cb.Run(0, 0, HCI_ERR_NO_CONNECTION);
    return;
  }

  // The connection PHY is always LE_1M when the controller supports
  // neither LE_2M nor LE_CODED PHYs.
  if (!bluetooth::shim::GetController()->SupportsBle2mPhy() &&
      !bluetooth::shim::GetController()->SupportsBleCodedPhy()) {
    cb.Run(1, 1, HCI_SUCCESS);
    return;
  }

  uint16_t handle = get_btm_client_interface().peer.BTM_GetHCIConnHandle(bd_addr, BT_TRANSPORT_LE);

  const uint8_t len = HCIC_PARAM_SIZE_BLE_READ_PHY;
  uint8_t data[len];
  uint8_t* pp = data;
  UINT16_TO_STREAM(pp, handle);
  btu_hcif_send_cmd_with_cb(HCI_BLE_READ_PHY, data, len, base::Bind(&read_phy_cb, std::move(cb)));
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

  const uint8_t len = HCIC_PARAM_SIZE_BLE_SET_PHY;
  uint8_t data[len];
  uint8_t* pp = data;
  UINT16_TO_STREAM(pp, handle);
  UINT8_TO_STREAM(pp, all_phys);
  UINT8_TO_STREAM(pp, tx_phys);
  UINT8_TO_STREAM(pp, rx_phys);
  UINT16_TO_STREAM(pp, phy_options);
  btu_hcif_send_cmd_with_cb(HCI_BLE_SET_PHY, data, len, base::Bind([](uint8_t*, uint16_t) {}));
}
