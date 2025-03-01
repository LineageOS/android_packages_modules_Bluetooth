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

#include "port_api.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

const char* PORT_GetResultString(const uint8_t /* result_code */) {
  inc_func_call_count(__func__);
  return nullptr;
}
int PORT_CheckConnection(uint16_t /* handle */, RawAddress* /* bd_addr */, uint16_t* /* p_lcid */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_ClearKeepHandleFlag(uint16_t /* port_handle */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_FlowControl_MaxCredit(uint16_t /* handle */, bool /* enable */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_GetSettings(uint16_t /* handle */, PortSettings* /* p_settings */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_ReadData(uint16_t /* handle */, char* /* p_data */, uint16_t /* max_len */,
                  uint16_t* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetDataCOCallback(uint16_t /* port_handle */, tPORT_DATA_CO_CALLBACK* /* p_port_cb */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetEventMaskAndCallback(uint16_t /* port_handle */, uint32_t /* mask */,
                                 tPORT_CALLBACK* /* p_port_cb */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetSettings(uint16_t /* handle */, PortSettings* /* p_settings */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_WriteData(uint16_t /* handle */, const char* /* p_data */, uint16_t /* max_len */,
                   uint16_t* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_WriteDataCO(uint16_t /* handle */, int* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
int RFCOMM_CreateConnectionWithSecurity(uint16_t /* uuid */, uint8_t /* scn */,
                                        bool /* is_server */, uint16_t /* mtu */,
                                        const RawAddress& /* bd_addr */, uint16_t* /* p_handle */,
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
int RFCOMM_RemoveConnection(uint16_t /* handle */) {
  inc_func_call_count(__func__);
  return 0;
}
int RFCOMM_RemoveServer(uint16_t /* handle */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_GetSecurityMask(uint16_t /* handle */, uint16_t* /* sec_mask */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_GetChannelInfo(uint16_t /* handle */, uint16_t* /* local_mtu */,
                        uint16_t* /* remote_mtu */, uint16_t* /* local_credit */,
                        uint16_t* /* remote_credit */, uint16_t* /* local_cid */,
                        uint16_t* /* remote_cid */, uint16_t* /* dlci */,
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
int PORT_SetAppUid(uint16_t /* handle */, uint32_t /* app_uid */) {
  inc_func_call_count(__func__);
  return 0;
}
int PORT_SetSdpDuration(uint16_t /* handle */, uint64_t /* sdp_duration_ms */) {
  inc_func_call_count(__func__);
  return 0;
}
