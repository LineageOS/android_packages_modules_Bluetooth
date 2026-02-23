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

#include "osi/include/alarm.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/bt_name.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/rnr_interface.h"
#include "stack/include/security_client_callbacks.h"

namespace bluetooth {
namespace stack {
namespace rnr {

class RemoteNameRequest {
public:
  tBTM_NAME_CMPL_CB* p_remname_cmpl_cb{nullptr};
  alarm_t* remote_name_timer{nullptr};
  RawAddress remname_bda{};   /* Name of bd addr for active remote name request */
  bool remname_active{false}; /* State of a remote name request by external API */
  tBT_DEVICE_TYPE remname_dev_type{
          BT_DEVICE_TYPE_UNKNOWN}; /* Whether it's LE or BREDR name request */
  BtmRemoteNameCallback* p_rmt_name_callback{nullptr};
};

}  // namespace rnr
}  // namespace stack
}  // namespace bluetooth

/*******************************************************************************
 *
 * Function         BTM_SecAddRmtNameNotifyCallback
 *
 * Description      Register a callback to be called when remote name is read.
 *
 * Parameters:      callback: Callback to return remote name.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_SecAddRmtNameNotifyCallback(BtmRemoteNameCallback& callback);

/*******************************************************************************
 *
 * Function         BTM_IsRemoteNameKnown
 *
 * Description      Look up the device record using the bluetooth device
 *                  address and if a record is found check if the name
 *                  has been acquired and cached.
 *
 * Parameters:      bd_addr: Bluetooth device address
 *                  transport: UNUSED
 *
 * Returns          true if name is cached, false otherwise
 *
 ******************************************************************************/
bool BTM_IsRemoteNameKnown(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_ReadRemoteDeviceName
 *
 * Description      This function initiates a remote device HCI command to the
 *                  controller and calls the callback when the process has
 *                  completed.
 *
 * Input Params:    remote_bda      - bluetooth device address of name to
 *                                    retrieve
 *                  p_cb            - callback function called when
 *                                    remote name is received or when procedure
 *                                    timed out.
 *                  transport       - transport used to query the remote name
 * Returns
 *                  tBTM_STATUS::BTM_CMD_STARTED is returned if the request was successfully
 *                                    sent to HCI.
 *                  BTM_BUSY if already in progress
 *                  BTM_UNKNOWN_ADDR if device address is bad
 *                  BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
tBTM_STATUS BTM_ReadRemoteDeviceName(const RawAddress& remote_bda, tBTM_NAME_CMPL_CB* p_cb,
                                     tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_CancelRemoteDeviceName
 *
 * Description      This function initiates the cancel request for the specified
 *                  remote device.
 *
 * Input Params:    None
 *
 * Returns
 *                  tBTM_STATUS::BTM_CMD_STARTED is returned if the request was successfully
 *                                  sent to HCI.
 *                  BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  BTM_WRONG_MODE if there is not an active remote name
 *                                 request.
 *
 ******************************************************************************/
tBTM_STATUS BTM_CancelRemoteDeviceName(void);

/*******************************************************************************
 *
 * Function         btm_process_remote_name
 *
 * Description      This function is called when a remote name is received from
 *                  the device. If remote names are cached, it updates the
 *                  inquiry database.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_process_remote_name(const RawAddress* bda, const BD_NAME bdn, uint16_t /* evt_len */,
                             tHCI_STATUS hci_status);

void btm_inq_remote_name_timer_timeout(void* data);

namespace bluetooth {
namespace stack {
namespace rnr {

class Impl : public bluetooth::stack::rnr::Interface {
public:
  Impl() = default;

  void BTM_SecAddRmtNameNotifyCallback(BtmRemoteNameCallback& callback) override;
  [[nodiscard]] bool BTM_IsRemoteNameKnown(const RawAddress& bd_addr, tBT_TRANSPORT transport);
  [[nodiscard]] tBTM_STATUS BTM_ReadRemoteDeviceName(const RawAddress& remote_bda,
                                                     tBTM_NAME_CMPL_CB* p_cb,
                                                     tBT_TRANSPORT transport);
  [[nodiscard]] tBTM_STATUS BTM_CancelRemoteDeviceName(void);
  void btm_process_remote_name(const RawAddress* bda, const BD_NAME bdn, uint16_t /* evt_len */,
                               tHCI_STATUS hci_status);
};

}  // namespace rnr
}  // namespace stack
}  // namespace bluetooth
