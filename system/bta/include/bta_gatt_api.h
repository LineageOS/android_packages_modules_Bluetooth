/******************************************************************************
 *
 *  Copyright 2003-2013 Broadcom Corporation
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
 *  This is the public interface file for BTA GATT.
 *
 ******************************************************************************/

#ifndef BTA_GATT_API_H
#define BTA_GATT_API_H

#include <base/functional/callback_forward.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include <list>
#include <string>
#include <vector>

#include "bta/gatt/database.h"
#include "hardware/bt_gatt_types.h"
#include "macros.h"
#include "stack/include/gatt_api.h"
#include "stack/include/stack_app.h"

#ifndef BTA_GATT_DEBUG
#define BTA_GATT_DEBUG false
#endif

/*****************************************************************************
 *  Constants and data types
 ****************************************************************************/
/**************************
 *  Common Definitions
 **************************/
/* GATT ID */
typedef struct {
  bluetooth::Uuid uuid; /* uuid of the attribute */
  uint8_t inst_id;      /* instance ID */
} __attribute__((packed)) tBTA_GATT_ID;

/* Client callback function events */
typedef enum : uint8_t {
  BTA_GATTC_OPEN_EVT = 2,                         /* GATTC open request status  event */
  BTA_GATTC_CLOSE_EVT = 5,                        /* GATTC  close request status event */
  BTA_GATTC_SEARCH_CMPL_EVT = 6,                  /* GATT discovery complete event */
  BTA_GATTC_SRVC_DISC_DONE_EVT = 8,               /* GATT service discovery done event */
  BTA_GATTC_NOTIF_EVT = 10,                       /* GATT attribute notification event */
  BTA_GATTC_EXEC_EVT = 12,                        /* execute write complete event */
  BTA_GATTC_SRVC_CHG_EVT = 15,                    /* service change event */
  BTA_GATTC_ENC_CMPL_CB_EVT = 17,                 /* encryption complete callback event */
  BTA_GATTC_CFG_MTU_EVT = 18,                     /* configure MTU complete event */
  BTA_GATTC_CONGEST_EVT = 24,                     /* Congestion event */
  BTA_GATTC_PHY_UPDATE_EVT = 25,                  /* PHY change event */
  BTA_GATTC_CONN_UPDATE_EVT = 26,                 /* Connection parameters update event */
  BTA_GATTC_SUBRATE_CHG_EVT = 27,                 /* Subrate Change event */
  BTA_GATTC_CHARACTERISTICS_UNOFFLOADED_EVT = 28, /* Characteristics unoffloaded event */
} tBTA_GATTC_EVT;

inline std::string gatt_client_event_text(const tBTA_GATTC_EVT& event) {
  switch (event) {
    CASE_RETURN_TEXT(BTA_GATTC_OPEN_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_CLOSE_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_SEARCH_CMPL_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_SRVC_DISC_DONE_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_NOTIF_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_EXEC_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_SRVC_CHG_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_ENC_CMPL_CB_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_CFG_MTU_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_CONGEST_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_PHY_UPDATE_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_CONN_UPDATE_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_SUBRATE_CHG_EVT);
    CASE_RETURN_TEXT(BTA_GATTC_CHARACTERISTICS_UNOFFLOADED_EVT);
    default:
      return std::format("UNKNOWN[{}]", static_cast<uint8_t>(event));
  }
}

typedef struct {
  uint16_t unit;  /* as UUIUD defined by SIG */
  uint16_t descr; /* as UUID as defined by SIG */
  tGATT_FORMAT format;
  int8_t exp;
  uint8_t name_spc; /* The name space of the description */
} tBTA_GATT_CHAR_PRES;

/* Characteristic Aggregate Format attribute value
 */
#define BTA_GATT_AGGR_HANDLE_NUM_MAX 10
typedef struct {
  uint8_t num_handle;
  uint16_t handle_list[BTA_GATT_AGGR_HANDLE_NUM_MAX];
} tBTA_GATT_CHAR_AGGRE;

typedef struct {
  uint16_t len;
  uint8_t* p_value;
} tBTA_GATT_UNFMT;

typedef struct {
  uint8_t num_attr;
  uint16_t handles[GATT_MAX_READ_MULTI_HANDLES];
} tBTA_GATTC_MULTI;

/* callback data structure */
typedef struct {
  tGATT_STATUS status;
  tGATT_IF client_if;
} tBTA_GATTC_REG;

typedef struct {
  tCONN_ID conn_id;
  tGATT_STATUS status;
  uint16_t handle;
  uint16_t len;
  uint8_t value[GATT_MAX_ATTR_LEN];
} tBTA_GATTC_READ;

typedef struct {
  tCONN_ID conn_id;
  tGATT_STATUS status;
  uint16_t handle;
} tBTA_GATTC_WRITE;

typedef struct {
  tCONN_ID conn_id;
  tGATT_STATUS status;
} tBTA_GATTC_EXEC_CMPL;

typedef struct {
  tCONN_ID conn_id;
  tGATT_STATUS status;
} tBTA_GATTC_SEARCH_CMPL;

typedef struct {
  tCONN_ID conn_id;
  tGATT_STATUS status;
  uint16_t mtu;
} tBTA_GATTC_CFG_MTU;

typedef struct {
  tGATT_STATUS status;
  tCONN_ID conn_id;
  tGATT_IF client_if;
  RawAddress remote_bda;
  tBT_TRANSPORT transport;
  uint16_t mtu;
} tBTA_GATTC_OPEN;

typedef struct {
  tCONN_ID conn_id;
  tGATT_STATUS status;
  tGATT_IF client_if;
  RawAddress remote_bda;
  tBT_TRANSPORT transport;
  tGATT_DISCONN_REASON reason;
} tBTA_GATTC_CLOSE;

typedef struct {
  tCONN_ID conn_id;
  RawAddress bda;
  uint16_t handle;
  uint16_t len;
  uint8_t value[GATT_MAX_ATTR_LEN];
  bool is_notify;
  uint16_t cid;
} tBTA_GATTC_NOTIFY;

typedef struct {
  tCONN_ID conn_id;
  bool congested; /* congestion indicator */
} tBTA_GATTC_CONGEST;

typedef struct {
  tGATT_STATUS status;
  tGATT_IF client_if;
  tCONN_ID conn_id;
  RawAddress remote_bda;
} tBTA_GATTC_OPEN_CLOSE;

typedef struct {
  tGATT_IF client_if;
  RawAddress remote_bda;
} tBTA_GATTC_ENC_CMPL_CB;

typedef struct {
  tGATT_IF server_if;
  tCONN_ID conn_id;
  uint8_t tx_phy;
  uint8_t rx_phy;
  tGATT_STATUS status;
} tBTA_GATTC_PHY_UPDATE;

typedef struct {
  tGATT_IF server_if;
  tCONN_ID conn_id;
  uint16_t interval;
  uint16_t latency;
  uint16_t timeout;
  tGATT_STATUS status;
} tBTA_GATTC_CONN_UPDATE;

typedef struct {
  RawAddress remote_bda;
  tCONN_ID conn_id;
} tBTA_GATTC_SERVICE_CHANGED;

typedef struct {
  RawAddress remote_bda;
} tBTA_GATTC_SERVICE_DISCOVERY_DONE;

typedef struct {
  tGATT_IF server_if;
  tCONN_ID conn_id;
  uint16_t subrate_factor;
  uint16_t latency;
  uint16_t cont_num;
  uint16_t timeout;
  tGATT_SUBRATE_MODE subrate_mode;
  tGATT_STATUS status;
} tBTA_GATTC_SUBRATE_CHG;

typedef struct {
  tCONN_ID conn_id;
  uint32_t session_id;
  tGATT_STATUS status;
} tBTA_GATTC_CHARACTERISTICS_UNOFFLOADED;

typedef union {
  tGATT_STATUS status;

  tBTA_GATTC_SEARCH_CMPL search_cmpl; /* discovery complete */
  tBTA_GATTC_REG reg_oper;            /* registration data */
  tBTA_GATTC_OPEN open;
  tBTA_GATTC_CLOSE close;
  tBTA_GATTC_READ read;           /* read attribute/descriptor data */
  tBTA_GATTC_WRITE write;         /* write complete data */
  tBTA_GATTC_EXEC_CMPL exec_cmpl; /*  execute complete */
  tBTA_GATTC_NOTIFY notify;       /* notification/indication event data */
  tBTA_GATTC_ENC_CMPL_CB enc_cmpl;
  tBTA_GATTC_CFG_MTU cfg_mtu; /* configure MTU operation */
  tBTA_GATTC_CONGEST congest;
  tBTA_GATTC_PHY_UPDATE phy_update;
  tBTA_GATTC_CONN_UPDATE conn_update;
  tBTA_GATTC_SERVICE_CHANGED service_changed;
  tBTA_GATTC_SERVICE_DISCOVERY_DONE service_discovery_done;
  tBTA_GATTC_SUBRATE_CHG subrate_chg;
  tBTA_GATTC_CHARACTERISTICS_UNOFFLOADED characteristics_unoffloaded;
} tBTA_GATTC;

/* GATTC enable callback function */
typedef void(tBTA_GATTC_ENB_CBACK)(tGATT_STATUS status);

/* Client callback function */
typedef void(tBTA_GATTC_CBACK)(tBTA_GATTC_EVT event, tBTA_GATTC* p_data);

#define BTA_GATTS_INVALID_APP 0xff

#define BTA_GATTS_INVALID_IF 0

/*****************************************************************************
 *  External Function Declarations
 ****************************************************************************/

/**************************
 *  Client Functions
 **************************/

/*******************************************************************************
 *
 * Function         BTA_GATTC_Disable
 *
 * Description      This function is called to disable the GATTC module
 *
 * Parameters       None.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_Disable(void);

using BtaAppRegisterCallback = base::OnceCallback<void(uint8_t /* app_id */, uint8_t /* status */)>;

/**
 * This function is called to register application callbacks with BTA GATTC
 *module.
 * p_client_cb - pointer to the application callback function.
 **/
void BTA_GATTC_AppRegister(const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                           BtaAppRegisterCallback cb, bool eatt_support);

/*******************************************************************************
 *
 * Function         BTA_GATTC_AppDeregister
 *
 * Description      This function is called to deregister an application
 *                  from BTA GATTC module.
 *
 * Parameters       client_if - client interface identifier.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_AppDeregister(tGATT_IF client_if);

/*******************************************************************************
 *
 * Function         BTA_GATTC_Open
 *
 * Description      Open a direct connection or add a background auto connection
 *                  bd address
 *
 * Parameters       client_if: server interface.
 *                  remote_bda: remote device BD address.
 *                  connection_type: connection type used for the peer device
 *                  transport: Transport to be used for GATT connection
 *                             (BREDR/LE)
 *                  opportunistic: whether the connection shall be opportunistic and
 *                                 don't impact the disconnection timer
 *                  auto_mtu_enabled: triggers mtu exchange with default mtu on connection
 *
 ******************************************************************************/
void BTA_GATTC_Open(tGATT_IF client_if, const RawAddress& remote_bda,
                    tBTM_BLE_CONN_TYPE connection_type);
void BTA_GATTC_Open(tGATT_IF client_if, const RawAddress& remote_bda, tBLE_ADDR_TYPE addr_type,
                    tBTM_BLE_CONN_TYPE connection_type, tBT_TRANSPORT transport,
                    uint16_t preferred_mtu, bool prefer_relax_mode, bool auto_mtu_enabled = false);

/*******************************************************************************
 *
 * Function         BTA_GATTC_CancelOpen
 *
 * Description      Open a direct connection or add a background auto connection
 *                  bd address
 *
 * Parameters       client_if: server interface.
 *                  remote_bda: remote device BD address.
 *                  is_direct: direct connection or background auto connection
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_GATTC_CancelOpen(tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct);

/*******************************************************************************
 *
 * Function         BTA_GATTC_Close
 *
 * Description      Close a connection to a GATT server.
 *
 * Parameters       conn_id: connection ID to be closed.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_GATTC_Close(tCONN_ID conn_id);

/*******************************************************************************
 *
 * Function         BTA_GATTC_ServiceSearchRequest
 *
 * Description      This function is called to request a GATT service discovery
 *                  on a GATT server. This function report service search result
 *                  by a callback event, and followed by a service search
 *                  complete event.
 *
 * Parameters       conn_id: connection ID.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_ServiceSearchRequest(tCONN_ID conn_id);

/**
 * This function is called to send "Find service by UUID" request. Used only for
 * PTS tests.
 */
void BTA_GATTC_DiscoverServiceByUuid(tCONN_ID conn_id, const bluetooth::Uuid& srvc_uuid);

/*******************************************************************************
 *
 * Function         BTA_GATTC_GetServices
 *
 * Description      This function is called to find the services on the given
 *                  server.
 *
 * Parameters       conn_id: connection ID which identify the server.
 *
 * Returns          returns list of gatt::Service or NULL.
 *
 ******************************************************************************/
const std::list<gatt::Service>* BTA_GATTC_GetServices(tCONN_ID conn_id);

/*******************************************************************************
 *
 * Function         BTA_GATTC_GetCharacteristic
 *
 * Description      This function is called to find the characteristic on the
 *                  given server.
 *
 * Parameters       conn_id: connection ID which identify the server.
 *                  handle: characteristic handle
 *
 * Returns          returns pointer to gatt::Characteristic or NULL.
 *
 ******************************************************************************/
const gatt::Characteristic* BTA_GATTC_GetCharacteristic(tCONN_ID conn_id, uint16_t handle);

/*******************************************************************************
 *
 * Function         BTA_GATTC_GetDescriptor
 *
 * Description      This function is called to find the characteristic on the
 *                  given server.
 *
 * Parameters       conn_id: connection ID which identify the server.
 *                  handle: descriptor handle
 *
 * Returns          returns pointer to gatt::Descriptor or NULL.
 *
 ******************************************************************************/
const gatt::Descriptor* BTA_GATTC_GetDescriptor(tCONN_ID conn_id, uint16_t handle);

/* Return characteristic that owns descriptor with handle equal to |handle|, or
 * NULL */
const gatt::Characteristic* BTA_GATTC_GetOwningCharacteristic(tCONN_ID conn_id, uint16_t handle);

/* Return service that owns descriptor or characteristic with handle equal to
 * |handle|, or NULL */
const gatt::Service* BTA_GATTC_GetOwningService(tCONN_ID conn_id, uint16_t handle);

/*******************************************************************************
 *
 * Function         BTA_GATTC_GetGattDb
 *
 * Description      This function is called to get gatt db.
 *
 * Parameters       conn_id: connection ID which identify the server.
 *                  db: output parameter which will contain gatt db copy.
 *                      Caller is responsible for freeing it.
 *                  count: number of elements in db.
 *
 ******************************************************************************/
void BTA_GATTC_GetGattDb(tCONN_ID conn_id, uint16_t start_handle, uint16_t end_handle,
                         btgatt_db_element_t** db, int* count);

typedef void (*GATT_READ_OP_CB)(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                uint16_t len, uint8_t* value, void* data);
typedef void (*GATT_WRITE_OP_CB)(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                 uint16_t len, const uint8_t* value, void* data);
typedef void (*GATT_CONFIGURE_MTU_OP_CB)(tCONN_ID conn_id, tGATT_STATUS status, void* data);
typedef void (*GATT_READ_MULTI_OP_CB)(tCONN_ID conn_id, tGATT_STATUS status,
                                      tBTA_GATTC_MULTI& handles, uint16_t len, uint8_t* value,
                                      void* data);
/*******************************************************************************
 *
 * Function         BTA_GATTC_ReadCharacteristic
 *
 * Description      This function is called to read a characteristics value
 *
 * Parameters       conn_id - connection ID.
 *                  handle - characteristic handle to read.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_ReadCharacteristic(tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ auth_req,
                                  GATT_READ_OP_CB callback, void* cb_data);

/**
 * This function is called to read a value of characteristic with uuid equal to
 * |uuid|
 */
void BTA_GATTC_ReadUsingCharUuid(tCONN_ID conn_id, const bluetooth::Uuid& uuid, uint16_t s_handle,
                                 uint16_t e_handle, tGATT_AUTH_REQ auth_req,
                                 GATT_READ_OP_CB callback, void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_ReadCharDescr
 *
 * Description      This function is called to read a descriptor value.
 *
 * Parameters       conn_id - connection ID.
 *                  handle - descriptor handle to read.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_ReadCharDescr(tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ auth_req,
                             GATT_READ_OP_CB callback, void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_WriteCharValue
 *
 * Description      This function is called to write characteristic value.
 *
 * Parameters       conn_id - connection ID.
 *                  handle - characteristic handle to write.
 *                  write_type - type of write.
 *                  value - the value to be written.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_WriteCharValue(tCONN_ID conn_id, uint16_t handle, tGATT_WRITE_TYPE write_type,
                              std::vector<uint8_t> value, tGATT_AUTH_REQ auth_req,
                              GATT_WRITE_OP_CB callback, void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_WriteCharDescr
 *
 * Description      This function is called to write descriptor value.
 *
 * Parameters       conn_id - connection ID
 *                  handle - descriptor handle to write.
 *                  value - the value to be written.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_WriteCharDescr(tCONN_ID conn_id, uint16_t handle, std::vector<uint8_t> value,
                              tGATT_AUTH_REQ auth_req, GATT_WRITE_OP_CB callback, void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_SendIndConfirm
 *
 * Description      This function is called to send handle value confirmation.
 *
 * Parameters       conn_id - connection ID.
 *                  cid - channel id
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_SendIndConfirm(tCONN_ID conn_id, uint16_t cid);

/*******************************************************************************
 *
 * Function         BTA_GATTC_RegisterForNotifications
 *
 * Description      This function is called to register for notification of a
 *                  service.
 *
 * Parameters       client_if - client interface.
 *                  remote_bda - target GATT server.
 *                  handle - GATT characteristic handle.
 *
 * Returns          OK if registration succeed, otherwise failed.
 *
 ******************************************************************************/
tGATT_STATUS BTA_GATTC_RegisterForNotifications(tGATT_IF client_if, const RawAddress& remote_bda,
                                                uint16_t handle);

/*******************************************************************************
 *
 * Function         BTA_GATTC_DeregisterForNotifications
 *
 * Description      This function is called to de-register for notification of a
 *                  service.
 *
 * Parameters       client_if - client interface.
 *                  remote_bda - target GATT server.
 *                  handle - GATT characteristic handle.
 *
 * Returns          OK if deregistration succeed, otherwise failed.
 *
 ******************************************************************************/
tGATT_STATUS BTA_GATTC_DeregisterForNotifications(tGATT_IF client_if, const RawAddress& remote_bda,
                                                  uint16_t handle);

/*******************************************************************************
 *
 * Function         BTA_GATTC_PrepareWrite
 *
 * Description      This function is called to prepare write a characteristic
 *                  value.
 *
 * Parameters       conn_id - connection ID.
 *                  handle - GATT characteristic handle.
 *                  offset - offset of the write value.
 *                  value - the value to be written.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_PrepareWrite(tCONN_ID conn_id, uint16_t handle, uint16_t offset,
                            std::vector<uint8_t> value, tGATT_AUTH_REQ auth_req,
                            GATT_WRITE_OP_CB callback, void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_ExecuteWrite
 *
 * Description      This function is called to execute write a prepare write
 *                  sequence.
 *
 * Parameters       conn_id - connection ID.
 *                    is_execute - execute or cancel.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_ExecuteWrite(tCONN_ID conn_id, bool is_execute);

/*******************************************************************************
 *
 * Function         BTA_GATTC_ReadMultiple
 *
 * Description      This function is called to read multiple characteristic or
 *                  characteristic descriptors.
 *
 * Parameters       conn_id - connection ID.
 *                  p_read_multi - read multiple parameters.
 *                  variable_len - whether "read multi variable length" variant
 *                                 shall be used.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTC_ReadMultiple(tCONN_ID conn_id, tBTA_GATTC_MULTI& p_read_multi, bool variable_len,
                            tGATT_AUTH_REQ auth_req, GATT_READ_MULTI_OP_CB callback, void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_Refresh
 *
 * Description      Refresh the server cache of the remote device
 *
 * Parameters       client_if - the client requesting refresh.
 *                  remote_bda - remote device BD address.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_GATTC_Refresh(tGATT_IF client_if, const RawAddress& remote_bda);

/*******************************************************************************
 *
 * Function         BTA_GATTC_ConfigureMTU
 *
 * Description      Configure the MTU size in the GATT channel. This can be done
 *                  only once per connection.
 *
 * Parameters       conn_id: connection ID.
 *                  mtu: desired MTU size to use.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_GATTC_ConfigureMTU(tCONN_ID conn_id, uint16_t mtu);
void BTA_GATTC_ConfigureMTU(tCONN_ID conn_id, uint16_t mtu, GATT_CONFIGURE_MTU_OP_CB callback,
                            void* cb_data);

/*******************************************************************************
 *
 * Function         BTA_GATTC_OffloadCharacteristics
 *
 * Description      This function is called to offload characteristics.
 *
 * Parameters       conn_id - connection ID.
 *                  service - vector describing service.
 *                  endpoint_id - ID of the hub end point.
 *                  hub_id - ID of the hub to which the end point belongs.
 *                  uid - UID of the app.
 *                  attribution_tag - attribution tag of the app.
 *                  promise - object used to signal the completion status.
 *
 ******************************************************************************/
void BTA_GATTC_OffloadCharacteristics(tCONN_ID conn_id, std::vector<btgatt_db_element_t> service,
                                      uint64_t endpoint_id, uint64_t hub_id, int uid,
                                      std::string attribution_tag,
                                      std::promise<btgatt_offload_result_t> promise);

/*******************************************************************************
 *
 * Function         BTA_GATTC_UnoffloadCharacteristics
 *
 * Description      This function is called to unoffload characteristics.
 *
 * Parameters       conn_id - connection ID.
 *                  session_id - session ID.
 *
 ******************************************************************************/
void BTA_GATTC_UnoffloadCharacteristics(tCONN_ID conn_id, int session_id);

/*******************************************************************************
 *  BTA GATT Server API
 ******************************************************************************/

/*******************************************************************************
 *
 * Function         BTA_GATTS_Init
 *
 * Description      This function is called to initialize GATTS module
 *
 * Parameters       None
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTS_Init();

/*******************************************************************************
 *
 * Function         BTA_GATTS_Disable
 *
 * Description      This function is called to disable GATTS module
 *
 * Parameters       None.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTS_Disable(void);

/*******************************************************************************
 *
 * Function         BTA_GATTS_AppRegister
 *
 * Description      This function is called to register application callbacks
 *                    with BTA GATTS module.
 *
 * Parameters       p_app_uuid - application UUID
 *                  p_cback - pointer to the application callback function.
 *                  eatt_support: indicate eatt support.
 *
 * Returns          GATT_IF_INVALID for error, otherwise the app id
 *
 ******************************************************************************/
tGATT_IF BTA_GATTS_AppRegister(const bluetooth::Uuid& app_uuid,
                               const bluetooth::stack::tGATT_CBACK* p_cback, bool eatt_support);

/*******************************************************************************
 *
 * Function         BTA_GATTS_AppDeregister
 *
 * Description      De-register with BTA GATT Server.
 *
 * Parameters       server_if: server interface
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_GATTS_AppDeregister(tGATT_IF server_if);

/*******************************************************************************
 *
 * Function         BTA_GATTS_AddService
 *
 * Description      Add the given |service| and all included elements to the
 *                  GATT database. a |BTA_GATTS_ADD_SRVC_EVT| is triggered to
 *                  report the status and attribute handles.
 *
 * Parameters       server_if: server interface.
 *                  service: pointer to vector describing service.
 *
 * Returns          Returns |GATT_SERVICE_STARTED| on success or error if the
 *                  service cannot be added.
 *
 ******************************************************************************/
tGATT_STATUS BTA_GATTS_AddService(tGATT_IF server_if, std::vector<btgatt_db_element_t>* service);

/*******************************************************************************
 *
 * Function         BTA_GATTS_DeleteService
 *
 * Description      This function is called to delete a service. When this is
 *                  done, a callback event BTA_GATTS_DELETE_EVT is report with
 *                  the status.
 *
 * Parameters       service_id: service_id to be deleted.
 *
 * Returns          returns true on success, false otherwise.
 *
 ******************************************************************************/
bool BTA_GATTS_DeleteService(tGATT_IF server_if, uint16_t service_id);

/*******************************************************************************
 *
 * Function         BTA_GATTS_HandleValueIndication
 *
 * Description      This function is called to read a characteristics
 *                  descriptor.
 *
 * Parameters       conn_id - connection identifier.
 *                  attr_id - attribute ID to indicate.
 *                  value - data to indicate.
 *                  need_confirm - if this indication expects a confirmation or
 *                                 not.
 *
 * Returns          GATT_SUCCESS or error code
 *
 ******************************************************************************/
tGATT_STATUS BTA_GATTS_HandleValueIndication(tCONN_ID conn_id, uint16_t attr_id,
                                             std::vector<uint8_t> value, bool need_confirm);

/*******************************************************************************
 *
 * Function         BTA_GATTS_SendRsp
 *
 * Description      This function is called to send a response to a request.
 *
 * Parameters       conn_id - connection identifier.
 *                  trans_id - transaction ID.
 *                  status - response status
 *                  rsp - response data.
 *
 * Returns          None
 *
 ******************************************************************************/
void BTA_GATTS_SendRsp(tCONN_ID conn_id, uint32_t trans_id, tGATT_STATUS status,
                       std::unique_ptr<tGATTS_RSP> rsp);

// Adds bonded device for GATT server tracking service changes
void BTA_GATTS_InitBonded(void);

/* Initialize power management callbacks for GATT */
extern void BTA_GATT_Init_gatt_pm_callbacks();

namespace std {
template <>
struct formatter<tBTA_GATTC_EVT> : enum_formatter<tBTA_GATTC_EVT> {};
}  // namespace std

#endif /* BTA_GATT_API_H */
