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

#include <bluetooth/types/address.h>

#include <cstdint>

#include "stack/include/bt_name.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/security_client_callbacks.h"

/* Structure returned with remote name request */
struct tBTM_REMOTE_DEV_NAME {
  RawAddress bd_addr;
  BD_NAME remote_bd_name;
  tBTM_STATUS btm_status;
  tHCI_STATUS hci_status;
};

typedef void(tBTM_NAME_CMPL_CB)(const tBTM_REMOTE_DEV_NAME*);

namespace bluetooth {
namespace stack {
namespace rnr {

class Interface {
public:
  virtual ~Interface() = default;

  /*******************************************************************************
   *
   * Function         BTM_SecAddRmtNameNotifyCallback
   *
   * Description      Register a callback to be called when remote name is read.
   *
   * Parameters       callback: Callback to return remote name.
   *
   * Returns          void
   *
   ******************************************************************************/
  virtual void BTM_SecAddRmtNameNotifyCallback(BtmRemoteNameCallback& callback) = 0;

  /*******************************************************************************
   *
   * Function         BTM_IsRemoteNameKnown
   *
   * Description      Look up the device record using the bluetooth device
   *                  address and if a record is found check if the name
   *                  has been acquired and cached.
   *
   * Parameters       bd_addr: Bluetooth device address
   *                  transport: Transport used to retrieve remote name
   *
   * Returns          true if name is cached, false otherwise
   *
   ******************************************************************************/
  virtual bool BTM_IsRemoteNameKnown(const RawAddress& bd_addr, tBT_TRANSPORT transport) = 0;

  /*******************************************************************************
   *
   * Function         BTM_ReadRemoteDeviceName
   *
   * Description      This function initiates a remote device HCI command to the
   *                  controller and calls the callback when the process has
   *                  completed.
   *
   * Parameters       bd_addr      - bluetooth device address of name to
   *                                    retrieve
   *                  p_callback            - callback function called when
   *                                    remote name is received or when procedure
   *                                    timed out.
   *                  transport       - transport used to query the remote name
   *
   * Returns          tBTM_STATUS::BTM_CMD_STARTED is returned if the request was
   *                                    successfully sent to HCI.
   *                  tBTM_STATUS::BTM_BUSY if already in progress
   *                  tBTM_STATUS::BTM_UNKNOWN_ADDR if device address is bad
   *                  tBTM_STATUS::BTM_NO_RESOURCES if could not allocate resources to start
   *                                   the command
   *                  tBTM_STATUS::BTM_WRONG_MODE if the device is not up.
   *
   ******************************************************************************/
  virtual tBTM_STATUS BTM_ReadRemoteDeviceName(const RawAddress& bd_addr,
                                               tBTM_NAME_CMPL_CB* p_callback,
                                               tBT_TRANSPORT transport) = 0;

  /*******************************************************************************
   *
   * Function         BTM_CancelRemoteDeviceName
   *
   * Description      This function initiates the cancel request for the outstanding
   *                  specified remote device.
   *
   * Parameters       None
   *
   * Returns          tBTM_STATUS::BTM_CMD_STARTED is returned if the request was
   *                                 successfully sent to HCI.
   *                  tBTM_STATUS::BTM_NO_RESOURCES if could not allocate resources to start
   *                                 the command
   *                  tBTM_STATUS::BTM_WRONG_MODE if there is not an active remote name
   *                                 request.
   *
   ******************************************************************************/
  virtual tBTM_STATUS BTM_CancelRemoteDeviceName() = 0;

  /*******************************************************************************
   *
   * Function         btm_process_remote_name
   *
   * Description      This function is called when a remote name is received from
   *                  the device. If remote names are cached, it updates the
   *                  inquiry database.
   *
   * Parameters       bd_addr      - bluetooth device address of name to
   *                                    retrieve
   *                  bd_name      - bluetooth device name
   *                  evt_len      - length of blueooth device name
   *                  hci_status   - Hci event status
   *
   * Returns          void
   *
   ******************************************************************************/
  virtual void btm_process_remote_name(const RawAddress* bd_addr, const BD_NAME bd_name,
                                       uint16_t evt_len, tHCI_STATUS hci_status) = 0;
};

}  // namespace rnr
}  // namespace stack
}  // namespace bluetooth

bluetooth::stack::rnr::Interface& get_stack_rnr_interface();
