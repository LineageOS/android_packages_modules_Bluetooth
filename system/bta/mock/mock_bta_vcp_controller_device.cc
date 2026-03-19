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
 *   Functions generated:12
 */

#include <vector>

#include "bta/vcp/vcp_controller_devices.h"
#include "test/common/mock_functions.h"

using bluetooth::vcp::internal::VolumeControllerDevice;

bool VolumeControllerDevice::EnableEncryption() {
  inc_func_call_count(__func__);
  return true;
}
bool VolumeControllerDevice::EnqueueInitialRequests(tGATT_IF /* gatt_if */,
                                                    GATT_READ_OP_CB /* chrc_read_cb */,
                                                    GATT_WRITE_OP_CB /* cccd_write_cb */) {
  inc_func_call_count(__func__);
  return false;
}
bool VolumeControllerDevice::IsEncryptionEnabled() {
  inc_func_call_count(__func__);
  return false;
}
bool VolumeControllerDevice::UpdateHandles(void) {
  inc_func_call_count(__func__);
  return false;
}
bool VolumeControllerDevice::VerifyReady(uint16_t /* handle */) {
  inc_func_call_count(__func__);
  return false;
}
bool VolumeControllerDevice::set_volume_control_service_handles(
        const gatt::Service& /* service */) {
  inc_func_call_count(__func__);
  return false;
}
void VolumeControllerDevice::set_volume_offset_control_service_handles(
        const gatt::Service& /* service */) {
  inc_func_call_count(__func__);
}
bool VolumeControllerDevice::subscribe_for_notifications(tGATT_IF /* gatt_if */,
                                                         uint16_t /* handle */,
                                                         uint16_t /* ccc_handle */,
                                                         GATT_WRITE_OP_CB /* cb */) {
  inc_func_call_count(__func__);
  return false;
}
uint16_t VolumeControllerDevice::find_ccc_handle(uint16_t /* chrc_handle */) {
  inc_func_call_count(__func__);
  return 0;
}
void VolumeControllerDevice::ControlPointOperation(uint8_t /* opcode */,
                                                   const std::vector<uint8_t>* /* arg */,
                                                   GATT_WRITE_OP_CB /* cb */, void* /* cb_data */) {
  inc_func_call_count(__func__);
}
void VolumeControllerDevice::Disconnect(tGATT_IF /* gatt_if */) { inc_func_call_count(__func__); }
void VolumeControllerDevice::EnqueueRemainingRequests(
        tGATT_IF /* gatt_if */, GATT_READ_OP_CB /* chrc_read_cb */,
        GATT_READ_MULTI_OP_CB /* chrc_multi_read_cb */, GATT_WRITE_OP_CB /* cccd_write_cb */) {
  inc_func_call_count(__func__);
}
void VolumeControllerDevice::ResetHandles(void) { inc_func_call_count(__func__); }
