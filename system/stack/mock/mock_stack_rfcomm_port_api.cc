/*
 * Copyright 2021 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:20
 */

#include <bluetooth/types/address.h>

#include <functional>

#include "stack/include/port_api.h"
#include "test/common/mock_functions.h"

std::function<int(uint8_t, RawAddress*, uint16_t*)> PORT_CheckConnection_Fn;
int PORT_CheckConnection(uint8_t handle, RawAddress* bd_addr, uint16_t* p_lcid) {
  inc_func_call_count(__func__);
  if (PORT_CheckConnection_Fn) {
    return PORT_CheckConnection_Fn(handle, bd_addr, p_lcid);
  }
  return 0;
}
int PORT_ClearKeepHandleFlag(uint8_t /* port_handle */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_FlowControl_MaxCredit(uint8_t /* handle */, bool /* enable */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_GetSettings(uint8_t /* handle */, PortSettings* /* p_settings */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_ReadData(uint8_t /* handle */, char* /* p_data */, uint16_t /* max_len */,
                  uint16_t* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetDataCOCallback(uint8_t /* port_handle */, tPORT_DATA_CO_CALLBACK* /* p_port_cb */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetEventMaskAndCallback(uint8_t /* port_handle */, uint32_t /* mask */,
                                 tPORT_CALLBACK* /* p_port_cb */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetSettings(uint8_t /* handle */, PortSettings* /* p_settings */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_WriteData(uint8_t /* handle */, const char* /* p_data */, uint16_t /* max_len */,
                   uint16_t* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_WriteDataCO(uint8_t /* handle */, int* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
int RFCOMM_CreateConnectionWithSecurity(uint16_t /* uuid */, uint8_t /* scn */,
                                        bool /* is_server */, uint16_t /* mtu */,
                                        const RawAddress& /* bd_addr */, uint8_t* /* p_handle */,
                                        tPORT_MGMT_CALLBACK* /* p_mgmt_callback */,
                                        uint16_t /* sec_mask */, RfcommCfgInfo /* cfg */) {
  inc_func_call_count(__func__);
  return 0;
}
int RFCOMM_ControlReqFromBTSOCK(uint8_t /* dlci */, const RawAddress& /* bd_addr */,
                                uint8_t /* modem_signal */, uint8_t /* break_signal */,
                                uint8_t /* discard_buffers */, uint8_t /* break_signal_seq */,
                                bool /* fc */) {
  inc_func_call_count(__func__);
  return 0;
}
std::function<int(uint8_t)> RFCOMM_RemoveConnection_Fn;
int RFCOMM_RemoveConnection(uint8_t handle) {
  inc_func_call_count(__func__);
  if (RFCOMM_RemoveConnection_Fn) {
    return RFCOMM_RemoveConnection_Fn(handle);
  }
  return 0;
}
std::function<int(uint8_t)> RFCOMM_RemoveServer_Fn;
int RFCOMM_RemoveServer(uint8_t handle) {
  inc_func_call_count(__func__);
  if (RFCOMM_RemoveServer_Fn) {
    return RFCOMM_RemoveServer_Fn(handle);
  }
  return 0;
}
int PORT_GetSecurityMask(uint8_t /* handle */, uint16_t* /* sec_mask */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_GetChannelInfo(uint8_t /* handle */, uint16_t* /* local_mtu */, uint16_t* /* remote_mtu */,
                        uint16_t* /* local_credit */, uint16_t* /* remote_credit */,
                        uint16_t* /* local_cid */, uint16_t* /* remote_cid */, uint16_t* /* dlci */,
                        uint16_t* /* max_frame_size */, uint16_t* /* acl_handle */,
                        bool* /* mux_initiator */) {
  inc_func_call_count(__func__);
  return 0;
}
void RFCOMM_Init(void) { inc_func_call_count(__func__); }
bool PORT_IsCollisionDetected(RawAddress /* bd_addr */) {
  inc_func_call_count(__func__);
  return false;
}
int PORT_SetAppUid(uint8_t /* handle */, uint32_t /* app_uid */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetSdpDuration(uint8_t /* handle */, uint64_t /* sdp_duration_ms */) {
  inc_func_call_count(__func__);
  return 0;
}
