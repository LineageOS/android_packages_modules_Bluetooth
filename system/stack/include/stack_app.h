/*
 * Copyright 2026 The Android Open Source Project
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

#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/uuid.h>

#include "stack/include/btm_ble_api_types.h"
#include "stack/include/gatt_api.h"

namespace bluetooth::stack {

/* discover result callback function */
typedef void(tGATT_DISC_RES_CB)(tCONN_ID conn_id, tGATT_DISC_TYPE disc_type,
                                tGATT_DISC_RES* p_data);
/* discover complete callback function */
typedef void(tGATT_DISC_CMPL_CB)(tCONN_ID conn_id, tGATT_DISC_TYPE disc_type, tGATT_STATUS status);
/* Define a callback function for when read/write/disc/config operation is
 * completed. */
typedef void(tGATT_CMPL_CBACK)(tCONN_ID conn_id, tGATTC_OPTYPE op, tGATT_STATUS status,
                               tGATT_CL_COMPLETE* p_data);
/* Define a callback function when an initialized connection is established. */
typedef void(tGATT_CONN_CBACK)(tGATT_IF gatt_if, const RawAddress& bda, tCONN_ID conn_id,
                               bool connected, tGATT_DISCONN_REASON reason,
                               tBT_TRANSPORT transport);

/* attribute request callback for ATT server */
struct tGATT_REQ_CBACK {
  void (&read_characteristic_cb)(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                                 uint16_t handle, uint16_t offset, bool is_long);
  void (&read_descriptor_cb)(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                             uint16_t handle, uint16_t offset, bool is_long);
  void (&write_characteristic_cb)(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                                  uint16_t handle, uint16_t offset, bool need_rsp, bool is_prep,
                                  uint8_t* value, uint16_t len);
  void (&write_descriptor_cb)(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                              uint16_t handle, uint16_t offset, bool need_rsp, bool is_prep,
                              uint8_t* value, uint16_t len);
  void (&exec_write_cb)(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                        tGATT_EXEC_FLAG exec_write);
  void (&mtu_changed_cb)(tCONN_ID conn_id, const RawAddress& remote_bda, uint16_t mtu);
  void (&conf_cb)(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda);

  /* in case your server implementation needs to do nothing... */
  template <typename... Args>
  static void do_nothing(Args...) noexcept {}
};
/* channel congestion/uncongestion callback */
typedef void(tGATT_CONGESTION_CBACK)(tCONN_ID conn_id, bool congested);
/* Define a callback function when encryption is established. */
typedef void(tGATT_ENC_CMPL_CB)(tGATT_IF gatt_if, const RawAddress& bda);
/* Define a callback function when phy is updated. */
typedef void(tGATT_PHY_UPDATE_CB)(tGATT_IF gatt_if, tCONN_ID conn_id, uint8_t tx_phy,
                                  uint8_t rx_phy, tGATT_STATUS status);
/* Define a callback function when connection parameters are updated */
typedef void(tGATT_CONN_UPDATE_CB)(tGATT_IF gatt_if, tCONN_ID conn_id, uint16_t interval,
                                   uint16_t latency, uint16_t timeout, tGATT_STATUS status);
/* Define a callback function when subrate change event is received */
typedef void(tGATT_SUBRATE_CHG_CB)(tGATT_IF gatt_if, tCONN_ID conn_id, uint16_t subrate_factor,
                                   uint16_t latency, uint16_t cont_num, uint16_t timeout,
                                   tGATT_SUBRATE_MODE subrate_mode, tGATT_STATUS status);
/* Define a callback function when characteristics unoffload event is received
 */
typedef void(tGATT_CHARACTERISTICS_UNOFFLOADED_CB)(tGATT_IF gatt_if, tCONN_ID conn_id,
                                                   uint32_t session_id, tGATT_STATUS status);
/* Define a callback function when offloaded service change indication is requested */
typedef void(tGATT_OFFLOADED_SERVICE_CHG_CB)(tCONN_ID conn_id);

/* Define the structure that applications use to register with
 * GATT. This structure includes callback functions. All functions
 * MUST be provided.
 */
typedef struct {
  tGATT_CONN_CBACK* p_conn_cb{nullptr};
  tGATT_CMPL_CBACK* p_cmpl_cb{nullptr};
  tGATT_DISC_RES_CB* p_disc_res_cb{nullptr};
  tGATT_DISC_CMPL_CB* p_disc_cmpl_cb{nullptr};
  tGATT_REQ_CBACK* p_req_cb{nullptr};
  tGATT_ENC_CMPL_CB* p_enc_cmpl_cb{nullptr};
  tGATT_CONGESTION_CBACK* p_congestion_cb{nullptr};
  tGATT_PHY_UPDATE_CB* p_phy_update_cb{nullptr};
  tGATT_CONN_UPDATE_CB* p_conn_update_cb{nullptr};
  tGATT_SUBRATE_CHG_CB* p_subrate_chg_cb{nullptr};
  tGATT_CHARACTERISTICS_UNOFFLOADED_CB* p_characteristics_unoffloaded_cb{nullptr};
  tGATT_OFFLOADED_SERVICE_CHG_CB* p_offloaded_service_chg_cb{nullptr};
} tGATT_CBACK;

inline constexpr tGATT_IF GATT_IF_INVALID = static_cast<tGATT_IF>(0);

/*******************************************************************************
 *
 * Function         stack::appRegister
 *
 * Description      This function is called to register an  application
 *                  with GATT
 *
 * Parameter        p_app_uuid128: Application UUID
 *                  p_cb_info: callback functions.
 *                  eatt_support: set support for eatt
 *
 * Returns          GATT_IF_INVALID for error, otherwise the index of the client registered
 *                  with GATT
 *
 ******************************************************************************/
[[nodiscard]] tGATT_IF appRegister(const bluetooth::Uuid& p_app_uuid128, const std::string& name,
                                   const tGATT_CBACK* p_cb_info, bool eatt_support);

/*******************************************************************************
 *
 * Function         stack::appDeregister
 *
 * Description      This function deregistered the application from GATT.
 *
 * Parameters       gatt_if: application interface.
 *
 * Returns          None.
 *
 ******************************************************************************/
void appDeregister(tGATT_IF gatt_if);

/*******************************************************************************
 *
 * Function         stack::appStartIf
 *
 * Description      This function is called after registration to start
 *                  receiving callbacks for registered interface.  Function may
 *                  call back with connection status and queued notifications
 *
 * Parameter        gatt_if: application interface.
 *
 * Returns          None
 *
 ******************************************************************************/
void appStartIf(tGATT_IF gatt_if);

}  // namespace bluetooth::stack
