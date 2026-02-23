/******************************************************************************
 *
 *  Copyright 1999-2013 Broadcom Corporation
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

#define LOG_TAG "bt_srvc"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>

#include "hardware/bt_gatt_types.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "srvc_dis_int.h"
#include "srvc_eng_int.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/gatt_api.h"

using namespace bluetooth;

static const uint16_t dis_attr_uuid[] = {
        GATT_UUID_SYSTEM_ID,      GATT_UUID_MODEL_NUMBER_STR, GATT_UUID_SERIAL_NUMBER_STR,
        GATT_UUID_FW_VERSION_STR, GATT_UUID_HW_VERSION_STR,   GATT_UUID_SW_VERSION_STR,
        GATT_UUID_MANU_NAME,      GATT_UUID_IEEE_DATA,        GATT_UUID_PNP_ID};

tDIS_CB dis_cb;

static tDIS_ATTR_MASK dis_uuid_to_attr(uint16_t uuid) {
  switch (uuid) {
    case GATT_UUID_SYSTEM_ID:
      return DIS_ATTR_SYS_ID_BIT;
    case GATT_UUID_MODEL_NUMBER_STR:
      return DIS_ATTR_MODEL_NUM_BIT;
    case GATT_UUID_SERIAL_NUMBER_STR:
      return DIS_ATTR_SERIAL_NUM_BIT;
    case GATT_UUID_FW_VERSION_STR:
      return DIS_ATTR_FW_NUM_BIT;
    case GATT_UUID_HW_VERSION_STR:
      return DIS_ATTR_HW_NUM_BIT;
    case GATT_UUID_SW_VERSION_STR:
      return DIS_ATTR_SW_NUM_BIT;
    case GATT_UUID_MANU_NAME:
      return DIS_ATTR_MANU_NAME_BIT;
    case GATT_UUID_IEEE_DATA:
      return DIS_ATTR_IEEE_DATA_BIT;
    case GATT_UUID_PNP_ID:
      return DIS_ATTR_PNP_ID_BIT;
    default:
      return 0;
  };
}

/*******************************************************************************
 *
 * Function         dis_gatt_c_read_dis_value_cmpl
 *
 * Description      Client read DIS database complete callback.
 *
 * Returns          void
 *
 ******************************************************************************/
static void dis_gatt_c_read_dis_value_cmpl(tCONN_ID conn_id) {
  tSRVC_CLCB* p_clcb = srvc_eng_find_clcb_by_conn_id(conn_id);

  dis_cb.dis_read_uuid_idx = 0xff;

  srvc_eng_release_channel(conn_id);

  if (dis_cb.p_read_dis_cback && p_clcb) {
    log::info("conn_id:{} attr_mask = 0x{:04x}", conn_id, p_clcb->dis_value.attr_mask);

    (*dis_cb.p_read_dis_cback)(p_clcb->bda, &p_clcb->dis_value);
    dis_cb.p_read_dis_cback = NULL;
  }

  while (!dis_cb.pend_reqs.empty()) {
    tDIS_REQ req = dis_cb.pend_reqs.front();
    dis_cb.pend_reqs.pop();
    log::info("Dequeue pending DIS request. Address:{}, mask:0x{:04x}", req.addr, req.mask);

    // Only process the pending DIS if the device is connected
    uint16_t _conn_id;
    if (GATT_GetConnIdIfConnected(srvc_eng_cb.gatt_if, req.addr, &_conn_id, BT_TRANSPORT_LE) &&
        DIS_ReadDISInfo(req.addr, req.p_read_dis_cback, req.mask)) {
      break;
    } else if (req.p_read_dis_cback) {
      tDIS_VALUE empty = {};
      req.p_read_dis_cback(req.addr, &empty);
    }
  }
}

/*******************************************************************************
 *
 * Function         dis_gatt_c_read_dis_req
 *
 * Description      Read remote device DIS attribute request.
 *
 * Returns          void
 *
 ******************************************************************************/
static bool dis_gatt_c_read_dis_req(tCONN_ID conn_id) {
  tGATT_READ_PARAM param;

  memset(&param, 0, sizeof(tGATT_READ_PARAM));

  param.service.s_handle = 1;
  param.service.e_handle = 0xFFFF;
  param.service.auth_req = 0;

  while (dis_cb.dis_read_uuid_idx < (sizeof(dis_attr_uuid) / sizeof(dis_attr_uuid[0]))) {
    if (dis_uuid_to_attr(dis_attr_uuid[dis_cb.dis_read_uuid_idx]) & dis_cb.request_mask) {
      param.service.uuid = bluetooth::Uuid::From16Bit(dis_attr_uuid[dis_cb.dis_read_uuid_idx]);

      if (GATTC_Read(conn_id, GATT_READ_BY_TYPE, &param) == GATT_SUCCESS) {
        return true;
      }

      log::error("Read DISInfo: {} GATT_Read Failed", param.service.uuid);
    }

    dis_cb.dis_read_uuid_idx++;
  }

  dis_gatt_c_read_dis_value_cmpl(conn_id);

  return false;
}

/*******************************************************************************
 *
 * Function         dis_c_cmpl_cback
 *
 * Description      Client operation complete callback.
 *
 * Returns          void
 *
 ******************************************************************************/
void dis_c_cmpl_cback(tSRVC_CLCB* p_clcb, tGATTC_OPTYPE op, tGATT_STATUS status,
                      tGATT_CL_COMPLETE* p_data) {
  uint16_t read_type;
  uint8_t *pp = NULL, *p_str;
  tCONN_ID conn_id = p_clcb->conn_id;

  if (dis_cb.dis_read_uuid_idx >= (sizeof(dis_attr_uuid) / sizeof(dis_attr_uuid[0]))) {
    log::error("invalid dis_cb.dis_read_uuid_idx");
    return;
  }

  read_type = dis_attr_uuid[dis_cb.dis_read_uuid_idx];

  log::verbose("op_code: 0x{:02x}  status: 0x{:02x} read_type: 0x{:04x}", op, status, read_type);

  if (op != GATTC_OPTYPE_READ) {
    return;
  }

  if (p_data != NULL && status == GATT_SUCCESS) {
    pp = p_data->att_value.value;

    switch (read_type) {
      case GATT_UUID_SYSTEM_ID:
        log::verbose("DIS_ATTR_SYS_ID_BIT");
        if (p_data->att_value.len == DIS_SYSTEM_ID_SIZE) {
          p_clcb->dis_value.attr_mask |= DIS_ATTR_SYS_ID_BIT;
          /* save system ID*/
          STREAM_TO_UINT64(p_clcb->dis_value.system_id, pp);
        }
        break;

      case GATT_UUID_PNP_ID:
        if (p_data->att_value.len == DIS_PNP_ID_SIZE) {
          p_clcb->dis_value.attr_mask |= DIS_ATTR_PNP_ID_BIT;
          STREAM_TO_UINT8(p_clcb->dis_value.pnp_id.vendor_id_src, pp);
          STREAM_TO_UINT16(p_clcb->dis_value.pnp_id.vendor_id, pp);
          STREAM_TO_UINT16(p_clcb->dis_value.pnp_id.product_id, pp);
          STREAM_TO_UINT16(p_clcb->dis_value.pnp_id.product_version, pp);
        }
        break;

      case GATT_UUID_MODEL_NUMBER_STR:
      case GATT_UUID_SERIAL_NUMBER_STR:
      case GATT_UUID_FW_VERSION_STR:
      case GATT_UUID_HW_VERSION_STR:
      case GATT_UUID_SW_VERSION_STR:
      case GATT_UUID_MANU_NAME:
      case GATT_UUID_IEEE_DATA:
        p_str = p_clcb->dis_value.data_string[read_type - GATT_UUID_MODEL_NUMBER_STR];
        osi_free(p_str);
        p_str = (uint8_t*)osi_malloc(p_data->att_value.len + 1);
        p_clcb->dis_value.attr_mask |= dis_uuid_to_attr(read_type);
        memcpy(p_str, p_data->att_value.value, p_data->att_value.len);
        p_str[p_data->att_value.len] = 0;
        p_clcb->dis_value.data_string[read_type - GATT_UUID_MODEL_NUMBER_STR] = p_str;
        break;

      default:
        break;

        break;
    }  // end switch
  }  // end if

  dis_cb.dis_read_uuid_idx++;

  dis_gatt_c_read_dis_req(conn_id);
}

/*******************************************************************************
 *
 * Function         DIS_ReadDISInfo
 *
 * Description      Read remote device DIS information.
 *
 * Returns          true on success, false otherwise
 *
 ******************************************************************************/
bool DIS_ReadDISInfo(const RawAddress& peer_bda, tDIS_READ_CBACK* p_cback, tDIS_ATTR_MASK mask) {
  tCONN_ID conn_id;

  // Initialize the DIS client if it hasn't been initialized already.
  srvc_eng_init();

  if (p_cback == NULL) {
    return false;
  }

  if (dis_cb.dis_read_uuid_idx != 0xff) {
    // GATT is busy, so let's queue the request
    tDIS_REQ req = {
            .p_read_dis_cback = p_cback,
            .mask = mask,
            .addr = peer_bda,
    };
    dis_cb.pend_reqs.push(req);

    return true;
  }

  // For now, we don't serve the request if GATT isn't connected.
  // We need to call stack::leConnectionConnect and implement the handler for both success and
  // failure case.
  if (!GATT_GetConnIdIfConnected(srvc_eng_cb.gatt_if, peer_bda, &conn_id, BT_TRANSPORT_LE)) {
    return false;
  }

  dis_cb.p_read_dis_cback = p_cback;
  // Mark currently active operation
  dis_cb.dis_read_uuid_idx = 0;

  dis_cb.request_mask = mask;

  log::verbose("BDA: {} cl_read_uuid: 0x{:04x}", peer_bda, dis_attr_uuid[dis_cb.dis_read_uuid_idx]);

  // Need to enhance it as multiple service is needed
  srvc_eng_request_channel(peer_bda, SRVC_ID_DIS);

  return dis_gatt_c_read_dis_req(conn_id);
}
